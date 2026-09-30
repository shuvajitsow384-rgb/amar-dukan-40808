package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.dao.SaleWithItems
import com.example.data.local.entities.Customer
import com.example.data.local.entities.LedgerEntry
import com.example.data.local.entities.Sale
import com.example.data.local.entities.SaleItem
import com.example.utils.SmsHelper
import com.example.utils.StoreInfoManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CreditSaleSmsNotificationTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        StoreInfoManager.updateLanguagePreferences("EN", "EN", context)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        StoreInfoManager.updateLanguagePreferences("BN", "BN")
        db.close()
    }

    @Test
    fun `test credit SMS includes personalized greeting with customer name`() {
        val sms = SmsHelper.generateCreditSaleSms(
            customerName = "Rahul Sharma",
            storeName = "Kali Mata Variety Store",
            billTotal = 360.0,
            paidAmount = 100.0,
            creditAdded = 260.0,
            totalOutstandingBalance = 560.0,
            transactionDateMs = 1756195200000L // 26/08/25
        )

        assertTrue("SMS must contain greeting containing customer name", sms.contains("Hi Rahul Sharma"))
        assertTrue("SMS must contain store name", sms.contains("Kali Mata Variety Store"))
    }

    @Test
    fun `test credit SMS includes transaction date`() {
        val dateMs = 1756195200000L // specific timestamp
        val expectedDateStr = SimpleDateFormat("dd/MM/yy", Locale.US).format(Date(dateMs))

        val sms = SmsHelper.generateCreditSaleSms(
            customerName = "Anita Roy",
            storeName = "Kali Mata Variety Store",
            billTotal = 500.0,
            paidAmount = 200.0,
            creditAdded = 300.0,
            totalOutstandingBalance = 300.0,
            transactionDateMs = dateMs
        )

        assertTrue("SMS must contain transaction date ($expectedDateStr)", sms.contains("($expectedDateStr)"))
    }

    @Test
    fun `test credit SMS includes UPI deep link with exact amount and parameters`() {
        val upiId = "kalimata@okaxis"
        val payeeName = "Kali Mata Variety Store"
        val totalDue = 560.0

        val sms = SmsHelper.generateCreditSaleSms(
            customerName = "Rahul",
            storeName = "Kali Mata Variety Store",
            billTotal = 360.0,
            paidAmount = 100.0,
            creditAdded = 260.0,
            totalOutstandingBalance = totalDue,
            merchantUpiId = upiId,
            merchantPayeeName = payeeName
        )

        assertTrue("SMS must contain UPI payment instruction", sms.contains("💳 UPI ID:"))
        assertTrue("SMS must contain merchant UPI ID", sms.contains("kalimata@okaxis"))
        assertTrue("Hosted pay link must start with https://amar-dukan-40808.web.app/pay/?", sms.contains("https://amar-dukan-40808.web.app/pay/?"))
        assertTrue("Hosted pay link must contain pa parameter", sms.contains("pa=kalimata%40okaxis") || sms.contains("pa=kalimata@okaxis"))
        assertTrue("Hosted pay link must contain am parameter with exact due amount 560.00", sms.contains("am=560.00"))
        assertTrue("Hosted pay link must contain cu=INR", sms.contains("cu=INR"))
        assertTrue("Hosted pay link must contain store parameter", sms.contains("store=Kali+Mata+Variety+Store"))
        assertTrue("Hosted pay link must NOT contain bpsign", !sms.contains("bpsign="))
        assertTrue("SMS must contain plain-text readable UPI ID", sms.contains("UPI ID: kalimata@okaxis"))
        assertTrue("SMS must contain plain-text readable Total Due", sms.contains("Total Due: Rs.560"))
    }

    @Test
    fun `test initial UPI VPA matches user specified BharatPe ID`() {
        StoreInfoManager.setUpiVpaDirect("9609319228-1@okbizaxis")
        assertEquals("9609319228-1@okbizaxis", StoreInfoManager.upiVpa)
        assertEquals("9609319228-1@okbizaxis", StoreInfoManager.merchantUpiId)
    }

    @Test
    fun `test StoreInfoManager syncs upiVpa dynamically from Firestore document data`() {
        val firestoreData = mapOf(
            "upiVpa" to "9609319228-1@okbizaxis",
            "storeName" to "Kali Mata Variety Store",
            "merchantPayeeName" to "SHUVAJITSOW"
        )
        StoreInfoManager.syncFromFirestore(firestoreData)

        assertEquals("9609319228-1@okbizaxis", StoreInfoManager.upiVpa)
        assertEquals("Kali Mata Variety Store", StoreInfoManager.storeName)

        // Verify that SmsHelper automatically pulls the VPA synced from Firestore
        val sms = SmsHelper.generateCreditSaleSms(
            customerName = "Rahul",
            storeName = "Kali Mata Variety Store",
            billTotal = 300.0,
            paidAmount = 100.0,
            creditAdded = 200.0,
            totalOutstandingBalance = 200.0,
            includeUpi = true
        )
        assertTrue("SMS must include UPI VPA synced from Firestore", sms.contains("9609319228-1@okbizaxis") || sms.contains("9609319228-1%40okbizaxis"))
        assertTrue("SMS must contain hosted payment URL", sms.contains("https://amar-dukan-40808.web.app/pay/?"))
    }

    @Test
    fun `test fallback when upiVpa in Firestore is missing or empty - no broken payment link sent`() {
        // Clear VPA to simulate missing or empty field in Firestore
        StoreInfoManager.setUpiVpaDirect("")
        assertEquals("", StoreInfoManager.upiVpa)

        // Build hosted pay url directly
        val payUrl = StoreInfoManager.buildHostedPayUrl(upiId = "")
        assertEquals("Hosted pay URL must be empty when VPA is missing", "", payUrl)

        val upiUrl = StoreInfoManager.buildUpiPayUrl(upiId = "")
        assertEquals("UPI deep link URL must be empty when VPA is missing", "", upiUrl)

        // Generate Credit Sale SMS with includeUpi = true
        val sms = SmsHelper.generateCreditSaleSms(
            customerName = "Rahul",
            storeName = "Kali Mata Variety Store",
            billTotal = 300.0,
            paidAmount = 100.0,
            creditAdded = 200.0,
            totalOutstandingBalance = 200.0,
            merchantUpiId = null,
            includeUpi = true
        )

        // Ensure no broken payment URL is appended
        assertFalse("SMS must not contain broken pay URL", sms.contains("https://amar-dukan-40808.web.app/pay/?"))
        assertFalse("SMS must not contain broken UPI scheme", sms.contains("upi://pay"))
        assertTrue("SMS must still contain complete bill info", sms.contains("Bill: Rs.300"))
        assertTrue("SMS must still contain Total Due", sms.contains("Total Due: Rs.200"))

        // Reset back to initial VPA
        StoreInfoManager.setUpiVpaDirect("9609319228-1@okbizaxis")
    }

    @Test
    fun `test hosted payment link builder format and parameters`() {
        val payUrl = com.example.utils.StoreInfoManager.buildHostedPayUrl(
            upiId = "9609319228-1@okbizaxis",
            payeeName = "SHUVAJITSOW",
            amount = 160.0,
            note = "Due Payment",
            store = "Kali Mata Variety Store"
        )

        assertTrue("Pay URL must start with https://amar-dukan-40808.web.app/pay/?", payUrl.startsWith("https://amar-dukan-40808.web.app/pay/?"))
        assertTrue("Pay URL must contain pa", payUrl.contains("pa=9609319228-1%40okbizaxis") || payUrl.contains("pa=9609319228-1@okbizaxis"))
        assertTrue("Pay URL must contain pn", payUrl.contains("pn=SHUVAJITSOW"))
        assertTrue("Pay URL must contain exact due amount am=160.00", payUrl.contains("am=160.00"))
        assertTrue("Pay URL must contain cu=INR", payUrl.contains("cu=INR"))
        assertTrue("Pay URL must contain note tn=Due+Payment", payUrl.contains("tn=Due+Payment"))
        assertTrue("Pay URL must contain store=Kali+Mata+Variety+Store", payUrl.contains("store=Kali+Mata+Variety+Store"))
        assertFalse("Pay URL must never contain bpsign", payUrl.contains("bpsign"))
    }

    @Test
    fun `test credit SMS length fits in 1 or 2 SMS segments`() {
        // Without UPI: should fit in 1 standard GSM segment (<= 160 characters)
        val smsWithoutUpi = SmsHelper.generateCreditSaleSms(
            customerName = "Rahul",
            storeName = "Kali Mata Variety Store",
            billTotal = 360.0,
            paidAmount = 100.0,
            creditAdded = 260.0,
            totalOutstandingBalance = 560.0,
            includeUpi = false
        )
        assertTrue(
            "SMS without UPI must fit in 1 segment (<= 160 chars, was ${smsWithoutUpi.length}): $smsWithoutUpi",
            smsWithoutUpi.length <= 160
        )

        // With UPI / Hosted Pay Link: should comfortably fit in standard multipart SMS segments (<= 360 characters)
        val smsWithUpi = SmsHelper.generateCreditSaleSms(
            customerName = "Rahul",
            storeName = "Kali Mata Variety Store",
            billTotal = 360.0,
            paidAmount = 100.0,
            creditAdded = 260.0,
            totalOutstandingBalance = 560.0,
            merchantUpiId = "kalimata@okaxis",
            merchantPayeeName = "Kali Mata Store"
        )
        assertTrue(
            "SMS with hosted UPI link must fit in multipart segments (<= 360 chars, was ${smsWithUpi.length}): $smsWithUpi",
            smsWithUpi.length <= 360
        )
    }

    @Test
    fun `test credit SMS Total Due accurately combines existing previous due with new credit`() = runBlocking {
        val custId = "cust_prior_due_test"
        val initialDue = 264.00

        // Customer already had existing prior due balance of Rs 264.00
        val customer = Customer(
            id = custId,
            name = "Vivek Bose",
            phone = "9876543210",
            balance = initialDue,
            creditLimit = 2000.0
        )
        db.customerDao().insertCustomer(customer)

        val priorLedger = LedgerEntry(
            id = "ledger_prior",
            partyType = "CUSTOMER",
            partyId = custId,
            partyName = customer.name,
            type = "SALE_CREDIT",
            amount = initialDue,
            datetime = System.currentTimeMillis() - 86400000L,
            note = "Previous credit sale"
        )
        db.ledgerDao().insertLedgerEntry(priorLedger)

        // Verify customer has initial previous balance of Rs 264.00
        val fetchedCust = db.customerDao().getCustomerById(custId)
        assertNotNull(fetchedCust)
        assertEquals(264.00, fetchedCust!!.balance, 0.001)

        // New transaction: Total bill Rs 10.00, Paid Rs 0.00, Credit added Rs 10.00
        val billTotal = 10.00
        val paidAmount = 0.00
        val creditAdded = 10.00
        val previousBalance = fetchedCust.balance
        val combinedTotalDue = previousBalance + creditAdded // 264.00 + 10.00 = 274.00

        val sms = SmsHelper.generateCreditSaleSms(
            customerName = fetchedCust.name,
            storeName = "Kali Mata Variety Store",
            billTotal = billTotal,
            paidAmount = paidAmount,
            creditAdded = creditAdded,
            totalOutstandingBalance = combinedTotalDue,
            merchantUpiId = "kalimata@okaxis"
        )

        // Verify that Total Due is 274.00, NOT just 10.00
        assertTrue("SMS must show Total Due as Rs.274 (combined previous 264 + new 10)", sms.contains("Total Due: Rs.274"))
        assertTrue("SMS must state Credit Added Rs.10", sms.contains("Credit Added: Rs.10"))
        assertTrue("SMS must greet Vivek Bose", sms.contains("Hi Vivek Bose"))
        assertTrue("UPI payment must contain merchant UPI ID", sms.contains("kalimata@okaxis"))
    }

    @Test
    fun `test generic UPI deep link format without bpsign and with correct parameters`() {
        val upiLink = com.example.utils.StoreInfoManager.buildUpiPayUrl(
            upiId = "9609319228-1@okbizaxis",
            payeeName = "Kali Mata Variety Store",
            amount = 160.0,
            note = "Due Payment",
            transactionId = "TXN12345"
        )

        assertFalse("UPI link must never contain bpsign", upiLink.contains("bpsign"))
        assertTrue("UPI link must contain pa", upiLink.contains("pa=9609319228-1@okbizaxis"))
        assertTrue("UPI link must contain payee name pn", upiLink.contains("pn=Kali+Mata+Variety+Store"))
        assertTrue("UPI link must contain exact due amount am=160.00", upiLink.contains("am=160.00"))
        assertTrue("UPI link must contain cu=INR", upiLink.contains("cu=INR"))
        assertTrue("UPI link must contain transaction note tn", upiLink.contains("tn=Due+Payment"))
        assertTrue("UPI link must contain transaction id tr", upiLink.contains("tr=TXN12345"))
        assertTrue("UPI link must start with upi://pay?", upiLink.startsWith("upi://pay?"))
    }

    @Test
    fun `test WhatsApp payment reminder includes generic UPI link and plain readable UPI info`() {
        val cust = Customer(
            id = "cust_wa_test",
            name = "Shuvajit",
            phone = "9876543210",
            balance = 160.0
        )
        val waMsg = com.example.utils.WhatsAppHelper.generatePaymentReminder(cust, isBengali = false)

        assertFalse("WhatsApp message must not contain bpsign", waMsg.contains("bpsign"))
        assertTrue("WhatsApp message must contain UPI ID", waMsg.contains(com.example.utils.StoreInfoManager.merchantUpiId))
        assertTrue("WhatsApp message must contain amount Rs.160", waMsg.contains("160"))
        assertTrue("WhatsApp message must contain online pay link or UPI ID", waMsg.contains("Pay Online") || waMsg.contains("UPI ID"))
    }

    @Test
    fun `test SMS reminder templates contain plain readable UPI ID and amount`() {
        val cust = Customer(
            id = "cust_sms_test",
            name = "Shuvajit",
            phone = "9876543210",
            balance = 160.0
        )

        for (template in SmsHelper.SmsTemplateType.entries) {
            val sms = SmsHelper.generatePaymentReminderSms(
                customer = cust,
                isBengali = false,
                templateType = template,
                includeUpi = true
            )
            assertFalse("Template $template must not contain bpsign", sms.contains("bpsign"))
            assertTrue("Template $template must contain plain readable UPI ID", sms.contains(com.example.utils.StoreInfoManager.merchantUpiId))
            assertTrue("Template $template must contain amount 160.00", sms.contains("160.00"))
        }
    }

    @Test
    fun `test Bill checkout QR UPI generator preserves all full parameters`() {
        val billUpi = com.example.utils.StoreInfoManager.buildBillCheckoutUpiPayUrl(
            upiId = "9609319228-1@okbizaxis",
            payeeName = "Kali Mata Variety Store",
            amount = 450.50,
            note = "Bill #1024",
            transactionId = "BILL1024"
        )
        assertTrue("Bill QR must contain pa", billUpi.contains("pa=9609319228-1@okbizaxis"))
        assertTrue("Bill QR must contain pn", billUpi.contains("pn=Kali+Mata+Variety+Store"))
        assertTrue("Bill QR must contain am=450.50", billUpi.contains("am=450.50"))
        assertTrue("Bill QR must contain cu=INR", billUpi.contains("cu=INR"))
        assertTrue("Bill QR must contain tn note parameter", billUpi.contains("tn=Bill"))
        assertTrue("Bill QR must contain tr=BILL1024", billUpi.contains("tr=BILL1024"))
    }

    @Test
    fun `test VPA-only UPI generator contains only pa parameter`() {
        val vpaOnly = com.example.utils.StoreInfoManager.buildVpaOnlyUpiPayUrl("9609319228-1@okbizaxis")
        assertEquals("upi://pay?pa=9609319228-1@okbizaxis", vpaOnly)
        assertFalse("VPA-only must not contain am", vpaOnly.contains("am="))
        assertFalse("VPA-only must not contain pn", vpaOnly.contains("pn="))
        assertFalse("VPA-only must not contain tn", vpaOnly.contains("tn="))
    }
}
