package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.entities.Customer
import com.example.data.local.entities.Expense
import com.example.data.local.entities.Product
import com.example.data.local.entities.Sale
import com.example.data.local.entities.SaleItem
import com.example.data.repository.StoreRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeleteAllDataTest {

    private lateinit var db: AppDatabase
    private lateinit var context: Context
    private lateinit var repository: StoreRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
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
    fun `test deleteAllAppData clears all local Room tables`() = runBlocking {
        // 1. Insert product
        val prod = Product(
            id = "prod_test_1",
            nameEn = "Mustard Oil",
            nameBn = "সরিষার তেল",
            category = "Groceries",
            unitType = "ltr",
            costPrice = 120.0,
            sellingPrice = 145.0,
            currentStock = 20.0
        )
        db.productDao().insertProduct(prod)

        // 2. Insert customer
        val cust = Customer(
            id = "cust_test_1",
            name = "Subhash Bose",
            phone = "9876543210",
            balance = 500.0
        )
        db.customerDao().insertCustomer(cust)

        // 3. Insert sale and sale item
        val sale = Sale(
            id = "sale_test_1",
            datetime = System.currentTimeMillis(),
            totalAmount = 290.0,
            finalAmount = 290.0,
            receivedAmount = 290.0,
            dueAmount = 0.0,
            paymentMode = "CASH"
        )
        val saleItem = SaleItem(
            id = 1L,
            saleId = sale.id,
            productId = prod.id,
            productNameEn = prod.nameEn,
            productNameBn = prod.nameBn,
            unitType = prod.unitType,
            quantity = 2.0,
            unitPrice = 145.0,
            costPrice = 120.0,
            subtotal = 290.0,
            totalCost = 240.0
        )
        db.saleDao().insertSale(sale)
        db.saleDao().insertSaleItems(listOf(saleItem))

        // 4. Insert expense
        val expense = Expense(
            id = "exp_test_1",
            amount = 100.0,
            category = "Electricity",
            date = System.currentTimeMillis()
        )
        db.expenseDao().insertExpense(expense)

        // Verify items were inserted
        assertEquals(1, db.productDao().getAllProductsList().size)
        assertEquals(1, db.customerDao().getAllCustomersList().size)
        assertEquals(1, db.saleDao().getAllSalesList().size)
        assertEquals(1, db.expenseDao().getAllExpensesList().size)

        // Run delete all local data
        val result = repository.deleteAllAppData(
            deleteLocalData = true,
            deleteCloudData = false,
            deleteLocalBackups = true,
            context = context
        )

        assertTrue(result.isSuccess)

        // Verify all tables are completely empty
        assertEquals(0, db.productDao().getAllProductsList().size)
        assertEquals(0, db.customerDao().getAllCustomersList().size)
        assertEquals(0, db.saleDao().getAllSalesList().size)
        assertEquals(0, db.expenseDao().getAllExpensesList().size)
    }
}
