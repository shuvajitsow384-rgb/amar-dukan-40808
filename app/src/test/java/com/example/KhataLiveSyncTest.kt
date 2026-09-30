package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.entities.Customer
import com.example.data.local.entities.LedgerEntry
import com.example.data.local.entities.Supplier
import com.example.ui.screens.credit.calculateCustomerCreditRisk
import com.example.utils.LedgerCalculator
import kotlinx.coroutines.flow.first
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
class KhataLiveSyncTest {

    private lateinit var db: AppDatabase

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun `test two phones concurrent payments on same customer reflects both payments correctly`() = runBlocking {
        val customerId = "cust_multi_device_test"
        
        // Initial state: Customer starts with Rs 1,000 credit due from a sale
        val initialCreditEntry = LedgerEntry(
            id = "ledger_sale_initial",
            partyType = "CUSTOMER",
            partyId = customerId,
            partyName = "Concurrent Customer",
            type = "SALE_CREDIT",
            amount = 1000.0,
            datetime = System.currentTimeMillis() - 60000L
        )
        db.ledgerDao().insertLedgerEntry(initialCreditEntry)

        val customer = Customer(
            id = customerId,
            name = "Concurrent Customer",
            phone = "9876543210",
            balance = 1000.0
        )
        db.customerDao().insertCustomer(customer)

        // Verify initial state
        var allLedgers = db.ledgerDao().getAllLedgerEntriesList()
        var calculatedBalance = LedgerCalculator.calculateCustomerBalance(customerId, allLedgers)
        assertEquals(1000.0, calculatedBalance, 0.001)

        // Device A records a payment of Rs 200 at T = 0s
        val paymentPhoneA = LedgerEntry(
            id = "ledger_payment_phone_a",
            partyType = "CUSTOMER",
            partyId = customerId,
            partyName = "Concurrent Customer",
            type = "PAYMENT_RECEIVED",
            amount = 200.0,
            datetime = System.currentTimeMillis() - 5000L,
            note = "Payment received on Phone A"
        )
        db.ledgerDao().insertLedgerEntry(paymentPhoneA)

        // Device B records a payment of Rs 300 at T = 2s
        val paymentPhoneB = LedgerEntry(
            id = "ledger_payment_phone_b",
            partyType = "CUSTOMER",
            partyId = customerId,
            partyName = "Concurrent Customer",
            type = "PAYMENT_RECEIVED",
            amount = 300.0,
            datetime = System.currentTimeMillis() - 3000L,
            note = "Payment received on Phone B"
        )
        db.ledgerDao().insertLedgerEntry(paymentPhoneB)

        // Simulate cloud sync: both ledger entries arrive at both devices
        allLedgers = db.ledgerDao().getAllLedgerEntriesList()
        assertEquals(3, allLedgers.size)

        // Reconcile and calculate true balance from full ledger history
        val finalBalance = LedgerCalculator.calculateCustomerBalance(customerId, allLedgers)
        // True balance MUST be exactly 1000 - 200 - 300 = 500.0
        assertEquals(500.0, finalBalance, 0.001)

        // Reconcile customer entity
        val reconciledCustomer = LedgerCalculator.reconcileCustomer(customer, allLedgers)
        assertEquals(500.0, reconciledCustomer.balance, 0.001)
    }

    @Test
    fun `test two phones concurrent supplier payments reflects both payments correctly`() = runBlocking {
        val supplierId = "supp_multi_device_test"
        
        // Initial state: Store owes supplier Rs 5,000 from purchase
        val initialPurchaseEntry = LedgerEntry(
            id = "ledger_purchase_initial",
            partyType = "SUPPLIER",
            partyId = supplierId,
            partyName = "Supplier ABC",
            type = "PURCHASE_CREDIT",
            amount = 5000.0,
            datetime = System.currentTimeMillis() - 60000L
        )
        db.ledgerDao().insertLedgerEntry(initialPurchaseEntry)

        val supplier = Supplier(
            id = supplierId,
            name = "Supplier ABC",
            phone = "9876543210",
            balance = 5000.0
        )
        db.supplierDao().insertSupplier(supplier)

        // Phone A pays Rs 1,500
        val paymentA = LedgerEntry(
            id = "ledger_supp_pay_a",
            partyType = "SUPPLIER",
            partyId = supplierId,
            partyName = "Supplier ABC",
            type = "PAYMENT_MADE",
            amount = 1500.0,
            datetime = System.currentTimeMillis() - 4000L
        )
        db.ledgerDao().insertLedgerEntry(paymentA)

        // Phone B pays Rs 1,000
        val paymentB = LedgerEntry(
            id = "ledger_supp_pay_b",
            partyType = "SUPPLIER",
            partyId = supplierId,
            partyName = "Supplier ABC",
            type = "PAYMENT_MADE",
            amount = 1000.0,
            datetime = System.currentTimeMillis() - 2000L
        )
        db.ledgerDao().insertLedgerEntry(paymentB)

        // Both devices calculate supplier balance from complete ledger history
        val allLedgers = db.ledgerDao().getAllLedgerEntriesList()
        val finalSupplierBalance = LedgerCalculator.calculateSupplierBalance(supplierId, allLedgers)

        // True balance MUST be 5000 - 1500 - 1000 = 2500.0
        assertEquals(2500.0, finalSupplierBalance, 0.001)

        val reconciledSupplier = LedgerCalculator.reconcileSupplier(supplier, allLedgers)
        assertEquals(2500.0, reconciledSupplier.balance, 0.001)
    }

    @Test
    fun `test Customer Khata live balance and overdue update upon payment`() = runBlocking {
        val customerId = "cust_123"
        val customer = Customer(
            id = customerId,
            name = "John Doe",
            phone = "9876543210",
            balance = 1500.0,
            creditLimit = 2000.0
        )
        db.customerDao().insertCustomer(customer)

        // Verify initial live flow state
        val initialCustomers = db.customerDao().getAllCustomers().first()
        assertEquals(1, initialCustomers.size)
        assertEquals(1500.0, initialCustomers[0].balance, 0.01)

        // Add an overdue credit ledger entry
        val oldTimestamp = System.currentTimeMillis() - (35L * 24 * 60 * 60 * 1000)
        val creditEntry = LedgerEntry(
            id = "entry_1",
            partyType = "CUSTOMER",
            partyId = customerId,
            partyName = "John Doe",
            type = "SALE_CREDIT",
            amount = 1500.0,
            datetime = oldTimestamp
        )
        db.ledgerDao().insertLedgerEntry(creditEntry)

        var ledgerList = db.ledgerDao().getAllLedgerEntries().first()
        var risk = calculateCustomerCreditRisk(initialCustomers[0], ledgerList)
        assertTrue("Customer should be critically overdue (>30 days)", risk.isCriticalOverdue)

        // Now record a payment (simulating remote Firestore sync or local payment)
        val updatedCustomer = customer.copy(balance = 0.0)
        db.customerDao().insertCustomer(updatedCustomer)

        val paymentEntry = LedgerEntry(
            id = "entry_2",
            partyType = "CUSTOMER",
            partyId = customerId,
            partyName = "John Doe",
            type = "PAYMENT_RECEIVED",
            amount = 1500.0,
            datetime = System.currentTimeMillis()
        )
        db.ledgerDao().insertLedgerEntry(paymentEntry)

        // Collect new state from the live flow directly
        val updatedCustomers = db.customerDao().getAllCustomers().first()
        assertEquals(0.0, updatedCustomers[0].balance, 0.01)

        ledgerList = db.ledgerDao().getAllLedgerEntries().first()
        risk = calculateCustomerCreditRisk(updatedCustomers[0], ledgerList)
        assertFalse("Customer with 0 balance should no longer be overdue", risk.isCriticalOverdue)
        assertEquals(0, risk.riskScore)
    }

    @Test
    fun `test Supplier Khata live balance updates instantly`() = runBlocking {
        val supplierId = "sup_456"
        val supplier = Supplier(
            id = supplierId,
            name = "Wholesale Mart",
            phone = "9123456780",
            balance = 5000.0
        )
        db.supplierDao().insertSupplier(supplier)

        // Verify initial state
        val initialSuppliers = db.supplierDao().getAllSuppliers().first()
        assertEquals(1, initialSuppliers.size)
        assertEquals(5000.0, initialSuppliers[0].balance, 0.01)

        // Record a supplier payment
        val updatedSupplier = supplier.copy(balance = 2000.0)
        db.supplierDao().insertSupplier(updatedSupplier)

        // Live flow immediately reflects updated balance
        val currentSuppliers = db.supplierDao().getAllSuppliers().first()
        assertEquals(2000.0, currentSuppliers[0].balance, 0.01)
    }

    @Test
    fun `test Newly added customer with opening dues is not marked overdue immediately`() = runBlocking {
        val newCustomerId = "cust_new_123"
        val newCustomer = Customer(
            id = newCustomerId,
            name = "Shuvajit",
            phone = "9609319228",
            balance = 100.0,
            creditLimit = 2000.0
        )
        db.customerDao().insertCustomer(newCustomer)

        val openingLedger = LedgerEntry(
            id = "led_open_123",
            partyType = "CUSTOMER",
            partyId = newCustomerId,
            partyName = "Shuvajit",
            type = "OPENING_BALANCE",
            amount = 100.0,
            datetime = System.currentTimeMillis(),
            note = "Opening balance at setup"
        )
        db.ledgerDao().insertLedgerEntry(openingLedger)

        val ledgerList = db.ledgerDao().getAllLedgerEntries().first()
        val risk = calculateCustomerCreditRisk(newCustomer, ledgerList)

        assertEquals("New customer days overdue should be 0", 0, risk.daysOverdue)
        assertFalse("New customer must not be marked moderately overdue (15-29d)", risk.isModerateOverdue)
        assertFalse("New customer must not be marked critically overdue (30d+)", risk.isCriticalOverdue)
        assertFalse("Customer is well within limit", risk.isOverCreditLimit)
        assertEquals("Active normal due risk score should be 1", 1, risk.riskScore)
    }
}
