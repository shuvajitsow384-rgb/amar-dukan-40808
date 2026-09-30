package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.firestore.FirestoreManager
import com.example.data.local.AppDatabase
import com.example.data.local.entities.Customer
import com.example.data.local.entities.Sale
import com.example.data.local.entities.SaleItem
import com.example.data.repository.StoreRepository
import com.example.utils.LedgerCalculator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CustomerOpeningBalanceTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: StoreRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = StoreRepository(db, context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `test adding customer with initial due creates OPENING_BALANCE ledger entry and correct Khata balance`() = runBlocking {
        val custId = "cust_rahim_100"
        val customer = Customer(
            id = custId,
            name = "Rahim Mia",
            phone = "01700000000",
            balance = 100.0
        )

        // Save customer with initial due ₹100
        repository.saveCustomer(customer, initialDue = 100.0)

        // 1. Verify a real LedgerEntry of type OPENING_BALANCE was created
        val partyLedgers = db.ledgerDao().getLedgerEntriesForPartyList("CUSTOMER", custId)
        assertEquals(1, partyLedgers.size)
        val openingEntry = partyLedgers.first()
        assertEquals("OPENING_BALANCE", openingEntry.type)
        assertEquals(100.0, openingEntry.amount, 0.001)
        assertEquals("Opening balance at setup", openingEntry.note)

        // 2. Verify Khata / customer balance calculated strictly from ledger is ₹100
        val allLedgers = db.ledgerDao().getAllLedgerEntriesList()
        val balanceFromLedger = LedgerCalculator.calculateCustomerBalance(custId, allLedgers)
        assertEquals(100.0, balanceFromLedger, 0.001)

        val savedCust = db.customerDao().getCustomerById(custId)
        assertNotNull(savedCust)
        assertEquals(100.0, savedCust!!.balance, 0.001)

        // 3. Make a new credit sale of ₹50 due amount
        val saleId = "sale_credit_50"
        val sale = Sale(
            id = saleId,
            datetime = System.currentTimeMillis(),
            totalAmount = 50.0,
            discount = 0.0,
            finalAmount = 50.0,
            paymentMode = "CREDIT",
            customerId = custId,
            customerName = "Rahim Mia",
            receivedAmount = 0.0,
            dueAmount = 50.0,
            previousBalance = balanceFromLedger
        )
        val saleItem = SaleItem(
            saleId = saleId,
            productId = "prod_1",
            productNameEn = "Mustard Oil",
            productNameBn = "সর্ষের তেল",
            unitType = "L",
            quantity = 1.0,
            unitPrice = 50.0,
            costPrice = 40.0,
            subtotal = 50.0,
            totalCost = 40.0
        )

        val result = repository.completeSale(sale, listOf(saleItem), isHeldBill = false)
        assertTrue(result.isSuccess)

        // 4. Verify combined total balance is now ₹100 (previous) + ₹50 (new sale) = ₹150
        val updatedLedgers = db.ledgerDao().getAllLedgerEntriesList()
        val updatedBalance = LedgerCalculator.calculateCustomerBalance(custId, updatedLedgers)
        assertEquals(150.0, updatedBalance, 0.001)

        val updatedCust = db.customerDao().getCustomerById(custId)
        assertNotNull(updatedCust)
        assertEquals(150.0, updatedCust!!.balance, 0.001)
    }

    @Test
    fun `test auditAndBackfillOpeningBalances automatically fixes existing customers with missing ledger entries`() = runBlocking {
        val custId = "cust_legacy_unrecorded"
        // Insert a customer directly into DB with balance = 250.0 and NO ledger entries
        val legacyCustomer = Customer(
            id = custId,
            name = "Legacy Customer",
            phone = "01800000000",
            balance = 250.0
        )
        db.customerDao().insertCustomer(legacyCustomer)

        // Confirm 0 ledger entries initially
        val initialLedgers = db.ledgerDao().getLedgerEntriesForPartyList("CUSTOMER", custId)
        assertTrue(initialLedgers.isEmpty())

        // Run auto-audit and backfill
        repository.auditAndBackfillOpeningBalances()

        // Verify that missing opening ledger entry was created
        val backfilledLedgers = db.ledgerDao().getLedgerEntriesForPartyList("CUSTOMER", custId)
        assertEquals(1, backfilledLedgers.size)
        assertEquals("OPENING_BALANCE", backfilledLedgers.first().type)
        assertEquals(250.0, backfilledLedgers.first().amount, 0.001)

        // Verify Khata balance is now accurately calculated from ledger history as ₹250
        val allLedgers = db.ledgerDao().getAllLedgerEntriesList()
        val balance = LedgerCalculator.calculateCustomerBalance(custId, allLedgers)
        assertEquals(250.0, balance, 0.001)
    }

    @Test
    fun `test adding 2 litres oil and 1kg sugar saves exact quantities without doubling`() = runBlocking {
        val oilProd = com.example.data.local.entities.Product(
            id = "prod_oil_1",
            nameEn = "Mustard Oil",
            nameBn = "সর্ষের তেল",
            category = "Oil",
            unitType = "litre",
            costPrice = 150.0,
            sellingPrice = 180.0,
            currentStock = 50.0
        )
        val sugarProd = com.example.data.local.entities.Product(
            id = "prod_sugar_1",
            nameEn = "Sugar",
            nameBn = "চিনি",
            category = "Grocery",
            unitType = "kg",
            costPrice = 50.0,
            sellingPrice = 60.0,
            currentStock = 100.0
        )
        db.productDao().insertProduct(oilProd)
        db.productDao().insertProduct(sugarProd)

        val cartItem1 = com.example.viewmodel.CartItem(
            product = oilProd,
            quantity = 2.0,
            unitPrice = 180.0,
            unitType = "litre"
        )
        val cartItem2 = com.example.viewmodel.CartItem(
            product = sugarProd,
            quantity = 1.0,
            unitPrice = 60.0,
            unitType = "kg"
        )

        val saleId = "sale_exact_qty_1"
        val saleItems = listOf(
            SaleItem(
                saleId = saleId,
                productId = cartItem1.product.id,
                productNameEn = cartItem1.product.nameEn,
                productNameBn = cartItem1.product.nameBn,
                unitType = cartItem1.unitType,
                quantity = cartItem1.quantity,
                unitPrice = cartItem1.unitPrice,
                costPrice = cartItem1.product.costPrice,
                subtotal = cartItem1.subtotal,
                totalCost = cartItem1.totalCost
            ),
            SaleItem(
                saleId = saleId,
                productId = cartItem2.product.id,
                productNameEn = cartItem2.product.nameEn,
                productNameBn = cartItem2.product.nameBn,
                unitType = cartItem2.unitType,
                quantity = cartItem2.quantity,
                unitPrice = cartItem2.unitPrice,
                costPrice = cartItem2.product.costPrice,
                subtotal = cartItem2.subtotal,
                totalCost = cartItem2.totalCost
            )
        )

        val sale = Sale(
            id = saleId,
            datetime = System.currentTimeMillis(),
            totalAmount = 420.0,
            discount = 10.0,
            finalAmount = 410.0,
            paymentMode = "CASH",
            receivedAmount = 410.0,
            dueAmount = 0.0
        )

        val res = repository.completeSale(sale, saleItems)
        assertTrue(res.isSuccess)

        // Verify saved sale in Room database
        val allSales = db.saleDao().getAllSalesList()
        assertEquals(1, allSales.size)
        val savedSaleWithItems = allSales.first()

        assertEquals(420.0, savedSaleWithItems.sale.totalAmount, 0.001)
        assertEquals(10.0, savedSaleWithItems.sale.discount, 0.001)
        assertEquals(410.0, savedSaleWithItems.sale.finalAmount, 0.001)

        // Verify exact saved quantities
        val savedItems = savedSaleWithItems.items
        assertEquals(2, savedItems.size)

        val savedOil = savedItems.find { it.productId == "prod_oil_1" }
        assertNotNull(savedOil)
        assertEquals("Mustard Oil", savedOil!!.productNameEn)
        assertEquals(2.0, savedOil.quantity, 0.001) // MUST BE EXACTLY 2.0 (NOT 4.0!)
        assertEquals("litre", savedOil.unitType)
        assertEquals(360.0, savedOil.subtotal, 0.001)

        val savedSugar = savedItems.find { it.productId == "prod_sugar_1" }
        assertNotNull(savedSugar)
        assertEquals("Sugar", savedSugar!!.productNameEn)
        assertEquals(1.0, savedSugar.quantity, 0.001) // MUST BE EXACTLY 1.0 (NOT 2000.0g!)
        assertEquals("kg", savedSugar.unitType)
        assertEquals(60.0, savedSugar.subtotal, 0.001)
    }
}
