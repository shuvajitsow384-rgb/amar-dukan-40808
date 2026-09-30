package com.example.utils

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.storeInfoDataStore: DataStore<Preferences> by preferencesDataStore(name = "store_info_preferences")

enum class PrintSpeed(
    val id: String,
    val labelEn: String,
    val labelBn: String,
    val descriptionEn: String,
    val descriptionBn: String,
    val chunkSize: Int,
    val delayMs: Long,
    val heatingInterval: Byte
) {
    SLOW(
        id = "SLOW",
        labelEn = "Slow (Max Dwell Time / Dark)",
        labelBn = "ধীর (সর্বোচ্চ হিট ডুয়েলিং / ডার্ক)",
        descriptionEn = "128B chunks, 15ms delay — maximizes thermal head heating dwell time for faint/budget printers",
        descriptionBn = "১২৮-বাইট চাঙ্ক, ১৫মি.সে. বিরতি — প্রিন্টার হেডকে পর্যাপ্ত তাপ দিয়ে সর্বোচ্চ গাঢ় প্রিন্ট দেয়",
        chunkSize = 128,
        delayMs = 15L,
        heatingInterval = 4.toByte()
    ),
    NORMAL(
        id = "NORMAL",
        labelEn = "Normal",
        labelBn = "স্বাভাবিক",
        descriptionEn = "256B chunks, 5ms delay",
        descriptionBn = "২৫৬-বাইট চাঙ্ক, ৫মি.সে. বিরতি",
        chunkSize = 256,
        delayMs = 5L,
        heatingInterval = 2.toByte()
    ),
    FAST(
        id = "FAST",
        labelEn = "Fast",
        labelBn = "দ্রুত",
        descriptionEn = "512B chunks, 0ms delay",
        descriptionBn = "৫১২-বাইট চাঙ্ক, ০মি.সে. বিরতি",
        chunkSize = 512,
        delayMs = 0L,
        heatingInterval = 1.toByte()
    );

    companion object {
        fun fromId(id: String?): PrintSpeed = when (id?.uppercase()) {
            "SLOW" -> SLOW
            "FAST" -> FAST
            else -> NORMAL
        }
    }
}

object StoreInfoManager {
    const val DEFAULT_UPI_VPA = "9609319228-1@okbizaxis"
    const val BASE_WEB_HOST = "https://amar-dukan-40808.web.app"

    private val STORE_NAME_KEY = stringPreferencesKey("store_name")
    private val STORE_ADDRESS_KEY = stringPreferencesKey("store_address")
    private val OWNER_NAME_KEY = stringPreferencesKey("owner_name")
    private val PHONE_KEY = stringPreferencesKey("store_phone")
    private val TAGLINE_KEY = stringPreferencesKey("store_tagline")
    private val GSTIN_KEY = stringPreferencesKey("store_gstin")
    private val UPI_VPA_KEY = stringPreferencesKey("upi_vpa")
    private val MERCHANT_UPI_ID_KEY = stringPreferencesKey("merchant_upi_id")
    private val MERCHANT_NAME_KEY = stringPreferencesKey("merchant_name")
    private val MERCHANT_UPI_SIGN_KEY = stringPreferencesKey("merchant_upi_sign")
    private val SHOW_QR_ON_PDF_KEY = stringPreferencesKey("show_qr_on_pdf")
    private val PDF_PAPER_SIZE_KEY = stringPreferencesKey("pdf_paper_size")
    private val CUSTOM_FOOTER_NOTE_KEY = stringPreferencesKey("custom_footer_note")
    private val PDF_HEADER_COLOR_KEY = stringPreferencesKey("pdf_header_color")
    private val AUTO_SEND_CREDIT_SMS_KEY = stringPreferencesKey("auto_send_credit_sms")
    private val THERMAL_DENSITY_KEY = stringPreferencesKey("thermal_printer_density")
    private val THERMAL_THRESHOLD_KEY = intPreferencesKey("thermal_threshold")
    private val THERMAL_PRINT_SPEED_KEY = stringPreferencesKey("thermal_print_speed")
    private val THERMAL_FONT_SIZE_KEY = stringPreferencesKey("thermal_receipt_font_size")
    private val THERMAL_FEED_LINES_KEY = stringPreferencesKey("thermal_feed_lines")
    private val SAVED_PRINTER_ADDRESS_KEY = stringPreferencesKey("saved_printer_address")
    private val SAVED_PRINTER_NAME_KEY = stringPreferencesKey("saved_printer_name")
    private val ENFORCE_COST_PRICE_DISCOUNT_LIMIT_KEY = stringPreferencesKey("enforce_cost_price_discount_limit")
    private val INTEREST_ENABLED_KEY = stringPreferencesKey("interest_enabled")
    private val INTEREST_RATE_MONTHLY_KEY = stringPreferencesKey("interest_rate_monthly")
    private val INTEREST_GRACE_PERIOD_DAYS_KEY = stringPreferencesKey("interest_grace_period_days")
    private val INTEREST_CALCULATION_MODE_KEY = stringPreferencesKey("interest_calculation_mode")
    private val INTEREST_APPLY_RETROACTIVELY_KEY = stringPreferencesKey("interest_apply_retroactively")
    private val INTEREST_ACTIVATION_DATE_KEY = stringPreferencesKey("interest_activation_date")
    private val INTEREST_DISCLAIMER_TEXT_KEY = stringPreferencesKey("interest_disclaimer_text")
    private val BACKUP_PASSWORD_BANNER_DISMISSED_KEY = stringPreferencesKey("backup_password_banner_dismissed")
    private val BILL_LANGUAGE_KEY = stringPreferencesKey("bill_language")
    private val SMS_LANGUAGE_KEY = stringPreferencesKey("sms_language")
    private val STORE_NAME_BN_KEY = stringPreferencesKey("store_name_bn")
    private val STORE_ADDRESS_BN_KEY = stringPreferencesKey("store_address_bn")
    private val ONLINE_ORDERING_ENABLED_KEY = stringPreferencesKey("online_ordering_enabled")
    private val HOME_DELIVERY_ENABLED_KEY = stringPreferencesKey("home_delivery_enabled")
    private val FREE_DELIVERY_MIN_ORDER_VALUE_KEY = stringPreferencesKey("free_delivery_min_order_value")
    private val DELIVERY_FEE_KEY = stringPreferencesKey("delivery_fee")
    private val THERMAL_NATIVE_BOLD_MODE_KEY = booleanPreferencesKey("thermal_native_bold_mode")
    private val THERMAL_RASTER_DILATION_KEY = booleanPreferencesKey("thermal_raster_dilation")

    private val scope = CoroutineScope(Dispatchers.IO)
    private var appContext: Context? = null

    // Online Store & Home Delivery Controls
    var onlineOrderingEnabled by mutableStateOf(true)
        private set
    var homeDeliveryEnabled by mutableStateOf(true)
        private set
    var freeDeliveryMinOrderValue by mutableStateOf(499.0)
        private set
    var deliveryFee by mutableStateOf(20.0)
        private set

    // Backup Password Reminder Banner State (For Google-signed-in users without email password)
    var backupPasswordBannerDismissed by mutableStateOf(false)
        private set

    // Bill & SMS Language Preference: "BN" (Bengali), "EN" (English), "AUTO" (Matches App Language)
    var billLanguage by mutableStateOf("BN")
        private set
    var smsLanguage by mutableStateOf("BN")
        private set

    fun isBillBengali(): Boolean {
        return when (billLanguage) {
            "BN" -> true
            "EN" -> false
            else -> LanguageManager.isBengali
        }
    }

    fun isSmsBengali(): Boolean {
        return when (smsLanguage) {
            "BN" -> true
            "EN" -> false
            else -> LanguageManager.isBengali
        }
    }

    var storeName by mutableStateOf("Kali Mata Variety Store")
        private set
    var storeAddress by mutableStateOf("")
        private set
    var storeNameBn by mutableStateOf("কালী মাতা ভ্যারাইটি স্টোর")
        private set
    var storeAddressBn by mutableStateOf("কুরমিঠা, বীরভূম জেলা, পশ্চিমবঙ্গ")
        private set

    fun getStoreDisplayName(isBn: Boolean = isBillBengali()): String {
        return if (isBn) {
            if (storeNameBn.isNotBlank()) storeNameBn
            else BengaliReceiptTranslator.translateStoreName(storeName)
        } else {
            storeName
        }
    }

    fun getStoreDisplayAddress(isBn: Boolean = isBillBengali()): String {
        return if (isBn) {
            if (storeAddressBn.isNotBlank()) storeAddressBn
            else BengaliReceiptTranslator.translateAddress(storeAddress)
        } else {
            storeAddress
        }
    }

    fun setBillLanguagePreference(billLang: String, context: Context? = null) {
        updateLanguagePreferences(billLang, smsLanguage, context)
    }

    fun updateStoreNameBn(nameBn: String, addressBn: String, context: Context? = null) {
        storeNameBn = nameBn.trim()
        storeAddressBn = addressBn.trim()
        val ctx = context?.applicationContext ?: appContext
        ctx?.let { c ->
            scope.launch {
                try {
                    c.storeInfoDataStore.edit { prefs ->
                        prefs[STORE_NAME_BN_KEY] = storeNameBn
                        prefs[STORE_ADDRESS_BN_KEY] = storeAddressBn
                    }
                } catch (e: Exception) {
                    Log.w("StoreInfoManager", "Failed to persist store BN info: ${e.message}")
                }
            }
        }
    }
    val address: String
        get() = storeAddress
    var ownerName by mutableStateOf("Store Owner")
        private set
    var phone by mutableStateOf("")
        private set
    var tagline by mutableStateOf("Your Trusted Neighborhood Retail Store")
        private set
    var gstin by mutableStateOf("")
        private set

    // Discount Protection (Limit discount so selling price doesn't drop below cost price)
    var enforceCostPriceDiscountLimit by mutableStateOf(true)
        private set

    // Automated Customer SMS for Credit / Due Sales (Default: ON)
    var autoSendCreditSms by mutableStateOf(true)
        private set

    // Khata Interest Settings (Configurable Late Payment Interest)
    var interestEnabled by mutableStateOf(false)
        private set
    var interestRateMonthly by mutableStateOf(2.0)
        private set
    val monthlyInterestRate: Double
        get() = interestRateMonthly
    val interestRateMonthlyPercent: Double
        get() = interestRateMonthly
    var interestGracePeriodDays by mutableStateOf(45)
        private set
    var interestCalculationMode by mutableStateOf("SIMPLE") // "SIMPLE" or "COMPOUNDING"
        private set
    var interestApplyRetroactively by mutableStateOf(false)
        private set
    var interestActivationDate by mutableStateOf(0L)
        private set
    var interestDisclaimerText by mutableStateOf("Late payment interest of {rate}% per month is applicable on unpaid credit balances after {grace_days} days of grace period.")
        private set

    // Thermal Printer Settings
    var thermalThreshold by mutableStateOf(150) // Hard threshold (0-255, default ~150)
    var thermalPrinterDensity by mutableStateOf("150") // Backward-compatible string representation
    var thermalPrintSpeed by mutableStateOf(PrintSpeed.NORMAL)
    var thermalReceiptFontSize by mutableStateOf("NORMAL") // "NORMAL", "LARGE"
    var thermalFeedLines by mutableStateOf(4) // 4 lines margin (~15-16mm) to cleanly clear the tear bar
    var savedPrinterAddress by mutableStateOf<String?>(null)
    var savedPrinterName by mutableStateOf<String?>(null)
    var thermalNativeBoldMode by mutableStateOf(true)
    var thermalRasterDilation by mutableStateOf(true)

    // UPI VPA (Dynamic from Firestore / DataStore)
    var upiVpa by mutableStateOf(DEFAULT_UPI_VPA)
        private set

    // Backward-compatible alias for existing references
    var merchantUpiId: String
        get() = upiVpa
        set(value) {
            upiVpa = value
        }

    var merchantPayeeName by mutableStateOf("SHUVAJITSOW")
        private set
    var merchantUpiSign by mutableStateOf("")
        private set
    var showQrOnPdf by mutableStateOf(true)
        private set
    var pdfPaperSize by mutableStateOf("THERMAL_58MM") // Default to 58mm (384 dots) for portable POS thermal printers
        private set
    var customFooterNote by mutableStateOf("Thank you for shopping with us! Please visit again")
        private set
    var pdfHeaderColor by mutableStateOf("#1D6C31") // Emerald Green accent
        private set

    /**
     * Direct setter for runtime/unit test override
     */
    fun setUpiVpaDirect(newVpa: String) {
        val clean = newVpa.trim()
        if (clean.isBlank()) {
            Log.e("StoreInfoManager", "UPI VPA is empty or missing. Payment links will not be attached.")
            upiVpa = ""
        } else {
            upiVpa = clean
        }
    }

    /**
     * Synchronizes store details & UPI VPA from Firestore store_settings/main_store_profile document map
     */
    fun syncFromFirestore(data: Map<String, Any?>) {
        val firestoreVpa = (data["upiVpa"] as? String)?.trim()
            ?: (data["merchantUpiId"] as? String)?.trim()

        if (!firestoreVpa.isNullOrBlank()) {
            upiVpa = firestoreVpa
            appContext?.let { ctx ->
                scope.launch {
                    ctx.storeInfoDataStore.edit { prefs ->
                        prefs[UPI_VPA_KEY] = firestoreVpa
                        prefs[MERCHANT_UPI_ID_KEY] = firestoreVpa
                    }
                }
            }
        } else {
            Log.e("StoreInfoManager", "UPI VPA field ('upiVpa') is missing or empty in Firestore store info document (store_settings/main_store_profile)!")
        }

        val nameVal = (data["storeName"] as? String ?: data["name"] as? String)?.trim()
        if (!nameVal.isNullOrBlank()) storeName = nameVal

        val addressVal = (data["storeAddress"] as? String ?: data["address"] as? String)?.trim()
        if (!addressVal.isNullOrBlank()) storeAddress = addressVal

        val ownerVal = (data["ownerName"] as? String ?: data["owner"] as? String)?.trim()
        if (!ownerVal.isNullOrBlank()) ownerName = ownerVal

        val phoneVal = (data["phone"] as? String ?: data["storePhone"] as? String)?.trim()
        if (!phoneVal.isNullOrBlank()) phone = phoneVal

        val payeeVal = (data["merchantPayeeName"] as? String ?: data["payeeName"] as? String)?.trim()
        if (!payeeVal.isNullOrBlank()) merchantPayeeName = payeeVal

        val taglineVal = (data["tagline"] as? String ?: data["store_tagline"] as? String)?.trim()
        if (!taglineVal.isNullOrBlank()) tagline = taglineVal

        val gstinVal = (data["gstin"] as? String ?: data["store_gstin"] as? String)?.trim()
        if (!gstinVal.isNullOrBlank()) gstin = gstinVal

        val interestEnabledVal = data["interestEnabled"] as? Boolean ?: (data["interest_enabled"] as? Boolean)
        if (interestEnabledVal != null) interestEnabled = interestEnabledVal

        val interestRateVal = (data["interestRateMonthly"] as? Number)?.toDouble() ?: (data["interest_rate_monthly"] as? Number)?.toDouble()
        if (interestRateVal != null) interestRateMonthly = interestRateVal

        val interestGraceVal = (data["interestGracePeriodDays"] as? Number)?.toInt() ?: (data["interest_grace_period_days"] as? Number)?.toInt()
        if (interestGraceVal != null) interestGracePeriodDays = interestGraceVal

        val interestCalcModeVal = (data["interestCalculationMode"] as? String ?: data["interest_calculation_mode"] as? String)?.trim()
        if (!interestCalcModeVal.isNullOrBlank()) interestCalculationMode = interestCalcModeVal

        val interestRetroVal = data["interestApplyRetroactively"] as? Boolean ?: (data["interest_apply_retroactively"] as? Boolean)
        if (interestRetroVal != null) interestApplyRetroactively = interestRetroVal

        val interestActDateVal = (data["interestActivationDate"] as? Number)?.toLong() ?: (data["interest_activation_date"] as? Number)?.toLong()
        if (interestActDateVal != null) interestActivationDate = interestActDateVal

        val interestDiscVal = (data["interestDisclaimerText"] as? String ?: data["interest_disclaimer_text"] as? String)?.trim()
        if (!interestDiscVal.isNullOrBlank()) interestDisclaimerText = interestDiscVal

        val billLangVal = (data["billLanguage"] as? String ?: data["bill_language"] as? String)?.trim()
        if (!billLangVal.isNullOrBlank()) billLanguage = billLangVal

        val smsLangVal = (data["smsLanguage"] as? String ?: data["sms_language"] as? String)?.trim()
        if (!smsLangVal.isNullOrBlank()) smsLanguage = smsLangVal

        val onlineOrderingVal = data["onlineOrderingEnabled"] as? Boolean ?: (data["online_ordering_enabled"] as? Boolean)
        if (onlineOrderingVal != null) {
            onlineOrderingEnabled = onlineOrderingVal
            appContext?.let { ctx ->
                scope.launch {
                    ctx.storeInfoDataStore.edit { prefs ->
                        prefs[ONLINE_ORDERING_ENABLED_KEY] = onlineOrderingVal.toString()
                    }
                }
            }
        }

        val homeDeliveryVal = data["homeDeliveryEnabled"] as? Boolean ?: (data["home_delivery_enabled"] as? Boolean)
        if (homeDeliveryVal != null) {
            homeDeliveryEnabled = homeDeliveryVal
            appContext?.let { ctx ->
                scope.launch {
                    ctx.storeInfoDataStore.edit { prefs ->
                        prefs[HOME_DELIVERY_ENABLED_KEY] = homeDeliveryVal.toString()
                    }
                }
            }
        }

        val freeDelVal = (data["freeDeliveryMinOrderValue"] as? Number)?.toDouble()
            ?: (data["free_delivery_min_order_value"] as? Number)?.toDouble()
        if (freeDelVal != null && freeDelVal >= 0.0) {
            freeDeliveryMinOrderValue = freeDelVal
            appContext?.let { ctx ->
                scope.launch {
                    ctx.storeInfoDataStore.edit { prefs ->
                        prefs[FREE_DELIVERY_MIN_ORDER_VALUE_KEY] = freeDelVal.toString()
                    }
                }
            }
        }

        val delFeeVal = (data["deliveryFee"] as? Number)?.toDouble()
            ?: (data["delivery_fee"] as? Number)?.toDouble()
        if (delFeeVal != null && delFeeVal >= 0.0) {
            deliveryFee = delFeeVal
            appContext?.let { ctx ->
                scope.launch {
                    ctx.storeInfoDataStore.edit { prefs ->
                        prefs[DELIVERY_FEE_KEY] = delFeeVal.toString()
                    }
                }
            }
        }
    }

    /**
     * Attaches a real-time Firestore listener to keep the store's UPI VPA and profile in sync
     */
    fun startFirestoreSync() {
        try {
            val firestore = FirebaseFirestore.getInstance()
            firestore.collection("store_settings").document("main_store_profile")
                .addSnapshotListener(MetadataChanges.EXCLUDE) { snapshot, error ->
                    if (error != null) {
                        Log.w("StoreInfoManager", "Firestore store_settings listener error: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot != null && snapshot.exists()) {
                        val rawVpa = snapshot.getString("upiVpa")?.trim() ?: snapshot.getString("merchantUpiId")?.trim()
                        if (rawVpa.isNullOrBlank()) {
                            Log.w("StoreInfoManager", "UPI VPA field ('upiVpa') is missing or empty in Firestore document 'store_settings/main_store_profile'.")
                        }
                        val data = snapshot.data
                        if (data != null) {
                            syncFromFirestore(data)
                        }
                    } else {
                        Log.i("StoreInfoManager", "Firestore document 'store_settings/main_store_profile' does not exist yet. Initializing default store settings.")
                        scope.launch {
                            try {
                                val initialData = mapOf(
                                    "storeName" to storeName,
                                    "storeAddress" to storeAddress,
                                    "ownerName" to ownerName,
                                    "phone" to phone,
                                    "tagline" to tagline,
                                    "gstin" to gstin,
                                    "upiVpa" to upiVpa,
                                    "merchantUpiId" to upiVpa,
                                    "merchantPayeeName" to merchantPayeeName,
                                    "showQrOnPdf" to showQrOnPdf,
                                    "pdfPaperSize" to pdfPaperSize,
                                    "customFooterNote" to customFooterNote,
                                    "pdfHeaderColor" to pdfHeaderColor,
                                    "onlineOrderingEnabled" to onlineOrderingEnabled,
                                    "homeDeliveryEnabled" to homeDeliveryEnabled,
                                    "freeDeliveryMinOrderValue" to freeDeliveryMinOrderValue,
                                    "deliveryFee" to deliveryFee,
                                    "updated_at" to System.currentTimeMillis()
                                )
                                firestore.collection("store_settings").document("main_store_profile")
                                    .set(initialData, SetOptions.merge())
                            } catch (e: Exception) {
                                Log.w("StoreInfoManager", "Could not initialize Firestore store profile: ${e.message}")
                            }
                        }
                    }
                }
        } catch (e: Exception) {
            Log.w("StoreInfoManager", "Could not start Firestore store info sync: ${e.message}")
        }
    }

    /**
     * Parses a raw UPI string or complete upi://pay link to extract components (pa, pn)
     */
    fun parseUpiLink(rawInput: String): Triple<String, String, String> {
        val trimmed = rawInput.trim()
        if (!trimmed.startsWith("upi://pay?", ignoreCase = true)) {
            return Triple(trimmed, "", "")
        }
        val query = trimmed.substringAfter("?", "")
        val params = query.split("&").associate { param ->
            val parts = param.split("=", limit = 2)
            if (parts.size == 2) parts[0].lowercase() to try { java.net.URLDecoder.decode(parts[1], "UTF-8") } catch (_: Exception) { parts[1] }
            else parts[0].lowercase() to ""
        }
        val pa = params["pa"] ?: ""
        val pn = params["pn"] ?: ""
        return Triple(pa, pn, "")
    }

    /**
     * [FUNCTION 1: BILL QR CODES AT CHECKOUT - FULL PARAMETERS PRESERVED]
     * Builds complete, full-parameter UPI payment URL specifically for Bill QR codes on receipts,
     * thermal bluetooth prints, PDF invoices, and checkout screens.
     * Preserves: pa (merchant VPA), pn (payee/store name), am (exact bill amount), cu (currency INR),
     * tn (invoice note/bill no), and tr (transaction ID).
     */
    fun buildBillCheckoutUpiPayUrl(
        upiId: String = upiVpa,
        payeeName: String = merchantPayeeName,
        amount: Double? = null,
        note: String? = null,
        sign: String = "",
        transactionId: String? = null
    ): String {
        val cleanId = upiId.trim().ifBlank { upiVpa.trim() }
        if (cleanId.isBlank()) {
            Log.e("StoreInfoManager", "Cannot build Bill QR UPI URL: UPI VPA is missing or empty.")
            return ""
        }
        val cleanName = payeeName.trim().ifBlank { merchantPayeeName.trim().ifBlank { storeName } }
        val encodedName = try { java.net.URLEncoder.encode(cleanName, "UTF-8") } catch (e: Exception) { cleanName.replace(" ", "%20") }

        val sb = StringBuilder("upi://pay?pa=$cleanId&pn=$encodedName")
        if (amount != null && amount > 0.0) {
            sb.append("&am=${"%.2f".format(java.util.Locale.US, amount)}")
        }
        sb.append("&cu=INR")
        if (!note.isNullOrBlank()) {
            val encodedNote = try { java.net.URLEncoder.encode(note.trim(), "UTF-8") } catch (e: Exception) { note.trim().replace(" ", "%20") }
            sb.append("&tn=$encodedNote")
        }
        // NPCI merchant protocol parameters for compliant merchant/store transactions:
        // mc=5411 (Grocery/Retail/Supermarkets), mode=02 (Dynamic link), purpose=00
        val isMerchantHandle = cleanId.contains("biz", ignoreCase = true) || cleanId.contains("fbpe", ignoreCase = true) || cleanId.contains("paytm", ignoreCase = true)
        if (isMerchantHandle) {
            sb.append("&mc=5411&mode=02&purpose=00")
        }
        if (!transactionId.isNullOrBlank()) {
            val cleanTr = transactionId.trim().replace(Regex("[^a-zA-Z0-9_-]"), "")
            if (cleanTr.isNotBlank()) {
                sb.append("&tr=$cleanTr")
            }
        }
        return sb.toString()
    }

    /**
     * Preserves backward compatibility for all existing checkout/bill invoice call sites.
     * Explicitly delegates to [buildBillCheckoutUpiPayUrl].
     */
    fun buildUpiPayUrl(
        upiId: String = upiVpa,
        payeeName: String = merchantPayeeName,
        amount: Double? = null,
        note: String? = null,
        sign: String = "",
        transactionId: String? = null
    ): String {
        return buildBillCheckoutUpiPayUrl(
            upiId = upiId,
            payeeName = payeeName,
            amount = amount,
            note = note,
            sign = sign,
            transactionId = transactionId
        )
    }

    /**
     * [FUNCTION 2: STRIPPED-DOWN VPA-ONLY UPI LINK FOR SMS / PAY-PAGE]
     * Builds a simplified "just VPA" UPI URI: upi://pay?pa=[merchant VPA]
     * completely independent of checkout bill QR codes.
     */
    fun buildVpaOnlyUpiPayUrl(upiId: String = upiVpa): String {
        val cleanId = upiId.trim().ifBlank { upiVpa.trim() }
        if (cleanId.isBlank()) return ""
        return "upi://pay?pa=$cleanId"
    }

    /**
     * Builds an HTTPS hosted payment URL for SMS payment reminders & web links:
     * https://amar-dukan-40808.web.app/pay/?pa=[merchant VPA]&pn=[merchant name]&am=[amount]&cu=INR&tn=[note]&store=[store name]
     */
    fun buildHostedPayUrl(
        upiId: String = upiVpa,
        payeeName: String = merchantPayeeName,
        amount: Double? = null,
        note: String? = null,
        store: String = storeName
    ): String {
        val cleanId = upiId.trim().ifBlank { upiVpa.trim() }
        if (cleanId.isBlank()) {
            Log.e("StoreInfoManager", "Cannot build hosted payment URL: UPI VPA is missing or empty.")
            return ""
        }
        val cleanName = payeeName.trim().ifBlank { merchantPayeeName.trim().ifBlank { storeName } }
        val cleanStore = store.trim().ifBlank { storeName.ifBlank { "Kali Mata Variety Store" } }

        val encodedId = try { java.net.URLEncoder.encode(cleanId, "UTF-8") } catch (e: Exception) { cleanId }
        val encodedName = try { java.net.URLEncoder.encode(cleanName, "UTF-8") } catch (e: Exception) { cleanName.replace(" ", "%20") }
        val encodedStore = try { java.net.URLEncoder.encode(cleanStore, "UTF-8") } catch (e: Exception) { cleanStore.replace(" ", "%20") }

        val sb = StringBuilder("$BASE_WEB_HOST/pay/?pa=$encodedId&pn=$encodedName")
        if (amount != null && amount > 0.0) {
            sb.append("&am=${"%.2f".format(java.util.Locale.US, amount)}")
        }
        sb.append("&cu=INR")
        if (!note.isNullOrBlank()) {
            val encodedNote = try { java.net.URLEncoder.encode(note.trim(), "UTF-8") } catch (e: Exception) { note.trim().replace(" ", "%20") }
            sb.append("&tn=$encodedNote")
        }
        sb.append("&store=$encodedStore")
        return sb.toString()
    }

    /**
     * Builds the public Customer Khata ledger URL using the secure shareToken:
     * https://amar-dukan-40808.web.app/khata/?token=[shareToken]
     */
    fun buildCustomerKhataUrl(shareToken: String): String {
        val cleanToken = shareToken.trim()
        if (cleanToken.isBlank()) return ""
        val encodedToken = try { java.net.URLEncoder.encode(cleanToken, "UTF-8") } catch (e: Exception) { cleanToken }
        return "$BASE_WEB_HOST/khata/?token=$encodedToken"
    }

    fun getFormattedStoreHeader(isBengali: Boolean = false): String {
        return buildString {
            append("🏪 *${storeName.ifBlank { "Store Statement" }}*\n")
            if (storeAddress.isNotBlank()) {
                append("📍 $storeAddress\n")
            }
            if (phone.isNotBlank()) {
                val phoneLabel = if (isBengali) "ফোন" else "Ph"
                append("📞 $phoneLabel: $phone\n")
            }
            if (gstin.isNotBlank()) {
                append("🏛️ GSTIN: $gstin\n")
            }
        }
    }

    fun getStoreLogoBitmap(sizePx: Int): android.graphics.Bitmap? {
        val ctx = appContext ?: return null
        return try {
            val drawable = androidx.core.content.ContextCompat.getDrawable(ctx, com.example.R.drawable.ic_widget_diya)
                ?: androidx.core.content.ContextCompat.getDrawable(ctx, com.example.R.drawable.ic_launcher_foreground)
                ?: return null
            val bitmap = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(canvas)
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    fun init(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        scope.launch {
            appCtx.storeInfoDataStore.data
                .map { prefs ->
                    val storedUpi = prefs[UPI_VPA_KEY] ?: prefs[MERCHANT_UPI_ID_KEY]
                    val resolvedUpi = if (storedUpi.isNullOrBlank() || storedUpi == "shuvajitsow384-2@oksbi" || storedUpi == "BHARATPE.8002787767@fbpe") {
                        DEFAULT_UPI_VPA
                    } else storedUpi
                    val storedPayee = prefs[MERCHANT_NAME_KEY]
                    val resolvedPayee = if (storedPayee.isNullOrBlank()) "SHUVAJITSOW" else storedPayee
                    val storedSign = prefs[MERCHANT_UPI_SIGN_KEY] ?: ""
                    val resolvedSign = storedSign

                    StoreDetails(
                        name = prefs[STORE_NAME_KEY] ?: "Kali Mata Variety Store",
                        address = prefs[STORE_ADDRESS_KEY] ?: "",
                        owner = prefs[OWNER_NAME_KEY] ?: "Store Owner",
                        phone = prefs[PHONE_KEY] ?: "",
                        tagline = prefs[TAGLINE_KEY] ?: "Your Trusted Neighborhood Retail Store",
                        gstin = prefs[GSTIN_KEY] ?: "",
                        upiId = resolvedUpi,
                        payeeName = resolvedPayee,
                        upiSign = resolvedSign,
                        showQr = prefs[SHOW_QR_ON_PDF_KEY]?.toBoolean() ?: true,
                        paperSize = prefs[PDF_PAPER_SIZE_KEY] ?: "THERMAL_58MM",
                        footerNote = (prefs[CUSTOM_FOOTER_NOTE_KEY] ?: "Thank you for shopping with us! Please visit again").replace("🙏", "").replace("🎁", "").trim(),
                        headerColor = prefs[PDF_HEADER_COLOR_KEY] ?: "#1D6C31",
                        autoSendCreditSms = prefs[AUTO_SEND_CREDIT_SMS_KEY]?.toBoolean() ?: true,
                        thermalThreshold = prefs[THERMAL_THRESHOLD_KEY] ?: prefs[THERMAL_DENSITY_KEY]?.toIntOrNull() ?: when (prefs[THERMAL_DENSITY_KEY]) {
                            "LIGHT" -> 130
                            "NORMAL" -> 150
                            "DARK" -> 165
                            "EXTRA_DARK" -> 180
                            else -> 150
                        },
                        thermalPrinterDensity = (prefs[THERMAL_THRESHOLD_KEY] ?: prefs[THERMAL_DENSITY_KEY]?.toIntOrNull() ?: 150).toString(),
                        thermalPrintSpeed = prefs[THERMAL_PRINT_SPEED_KEY]?.let { PrintSpeed.fromId(it) } ?: PrintSpeed.NORMAL,
                        thermalReceiptFontSize = prefs[THERMAL_FONT_SIZE_KEY] ?: "NORMAL",
                        thermalFeedLines = prefs[THERMAL_FEED_LINES_KEY]?.toIntOrNull()?.let { if (it in 1..8) it else 4 } ?: 4,
                        savedPrinterAddress = prefs[SAVED_PRINTER_ADDRESS_KEY],
                        savedPrinterName = prefs[SAVED_PRINTER_NAME_KEY],
                        enforceCostPriceDiscountLimit = prefs[ENFORCE_COST_PRICE_DISCOUNT_LIMIT_KEY]?.toBoolean() ?: true,
                        interestEnabled = prefs[INTEREST_ENABLED_KEY]?.toBoolean() ?: false,
                        interestRateMonthly = prefs[INTEREST_RATE_MONTHLY_KEY]?.toDoubleOrNull() ?: 2.0,
                        interestGracePeriodDays = prefs[INTEREST_GRACE_PERIOD_DAYS_KEY]?.toIntOrNull() ?: 45,
                        interestCalculationMode = prefs[INTEREST_CALCULATION_MODE_KEY] ?: "SIMPLE",
                        interestApplyRetroactively = prefs[INTEREST_APPLY_RETROACTIVELY_KEY]?.toBoolean() ?: false,
                        interestActivationDate = prefs[INTEREST_ACTIVATION_DATE_KEY]?.toLongOrNull() ?: 0L,
                        interestDisclaimerText = prefs[INTEREST_DISCLAIMER_TEXT_KEY] ?: "Late payment interest of {rate}% per month is applicable on unpaid credit balances after {grace_days} days of grace period.",
                        backupPasswordBannerDismissed = prefs[BACKUP_PASSWORD_BANNER_DISMISSED_KEY]?.toBoolean() ?: false,
                        billLanguage = prefs[BILL_LANGUAGE_KEY] ?: "BN",
                        smsLanguage = prefs[SMS_LANGUAGE_KEY] ?: "BN",
                        storeNameBn = prefs[STORE_NAME_BN_KEY] ?: "কালী মাতা ভ্যারাইটি স্টোর",
                        storeAddressBn = prefs[STORE_ADDRESS_BN_KEY] ?: "কুরমিঠা, বীরভূম জেলা, পশ্চিমবঙ্গ",
                        onlineOrderingEnabled = prefs[ONLINE_ORDERING_ENABLED_KEY]?.toBoolean() ?: true,
                        homeDeliveryEnabled = prefs[HOME_DELIVERY_ENABLED_KEY]?.toBoolean() ?: true,
                        freeDeliveryMinOrderValue = prefs[FREE_DELIVERY_MIN_ORDER_VALUE_KEY]?.toDoubleOrNull() ?: 499.0,
                        deliveryFee = prefs[DELIVERY_FEE_KEY]?.toDoubleOrNull() ?: 20.0,
                        thermalNativeBoldMode = prefs[THERMAL_NATIVE_BOLD_MODE_KEY] ?: true,
                        thermalRasterDilation = prefs[THERMAL_RASTER_DILATION_KEY] ?: true
                    )
                }
                .collect { details ->
                    storeName = details.name
                    storeAddress = details.address
                    storeNameBn = details.storeNameBn
                    storeAddressBn = details.storeAddressBn
                    ownerName = details.owner
                    phone = details.phone
                    tagline = details.tagline
                    gstin = details.gstin
                    upiVpa = details.upiId
                    merchantPayeeName = details.payeeName.ifBlank { "SHUVAJITSOW" }
                    merchantUpiSign = details.upiSign
                    showQrOnPdf = details.showQr
                    pdfPaperSize = details.paperSize
                    customFooterNote = details.footerNote
                    pdfHeaderColor = details.headerColor
                    autoSendCreditSms = details.autoSendCreditSms
                    thermalThreshold = details.thermalThreshold
                    thermalPrinterDensity = details.thermalThreshold.toString()
                    thermalPrintSpeed = details.thermalPrintSpeed
                    thermalReceiptFontSize = details.thermalReceiptFontSize
                    thermalFeedLines = if (details.thermalFeedLines in 1..8) details.thermalFeedLines else 4
                    savedPrinterAddress = details.savedPrinterAddress
                    savedPrinterName = details.savedPrinterName
                    enforceCostPriceDiscountLimit = details.enforceCostPriceDiscountLimit
                    interestEnabled = details.interestEnabled
                    interestRateMonthly = details.interestRateMonthly
                    interestGracePeriodDays = details.interestGracePeriodDays
                    interestCalculationMode = details.interestCalculationMode
                    interestApplyRetroactively = details.interestApplyRetroactively
                    interestActivationDate = details.interestActivationDate
                    interestDisclaimerText = details.interestDisclaimerText
                    backupPasswordBannerDismissed = details.backupPasswordBannerDismissed
                    billLanguage = details.billLanguage
                    smsLanguage = details.smsLanguage
                    onlineOrderingEnabled = details.onlineOrderingEnabled
                    homeDeliveryEnabled = details.homeDeliveryEnabled
                    freeDeliveryMinOrderValue = details.freeDeliveryMinOrderValue
                    deliveryFee = details.deliveryFee
                    thermalNativeBoldMode = details.thermalNativeBoldMode
                    thermalRasterDilation = details.thermalRasterDilation
                }
        }
        startFirestoreSync()
    }

    fun updateOnlineOrderingEnabled(enabled: Boolean, context: Context? = null) {
        onlineOrderingEnabled = enabled
        val ctx = context?.applicationContext ?: appContext
        ctx?.let { c ->
            scope.launch {
                c.storeInfoDataStore.edit { prefs ->
                    prefs[ONLINE_ORDERING_ENABLED_KEY] = enabled.toString()
                }
            }
        }
        scope.launch {
            try {
                val firestore = FirebaseFirestore.getInstance()
                firestore.collection("store_settings").document("main_store_profile")
                    .set(
                        mapOf(
                            "onlineOrderingEnabled" to enabled,
                            "updated_at" to System.currentTimeMillis()
                        ),
                        SetOptions.merge()
                    )
            } catch (e: Exception) {
                Log.e("StoreInfoManager", "Error updating onlineOrderingEnabled: ${e.message}")
            }
        }
    }

    fun updateHomeDeliveryEnabled(enabled: Boolean, context: Context? = null) {
        homeDeliveryEnabled = enabled
        val ctx = context?.applicationContext ?: appContext
        ctx?.let { c ->
            scope.launch {
                c.storeInfoDataStore.edit { prefs ->
                    prefs[HOME_DELIVERY_ENABLED_KEY] = enabled.toString()
                }
            }
        }
        scope.launch {
            try {
                val firestore = FirebaseFirestore.getInstance()
                firestore.collection("store_settings").document("main_store_profile")
                    .set(
                        mapOf(
                            "homeDeliveryEnabled" to enabled,
                            "updated_at" to System.currentTimeMillis()
                        ),
                        SetOptions.merge()
                    )
            } catch (e: Exception) {
                Log.e("StoreInfoManager", "Error updating homeDeliveryEnabled: ${e.message}")
            }
        }
    }

    fun updateDeliveryCharges(freeDeliveryThreshold: Double, fee: Double, context: Context? = null) {
        freeDeliveryMinOrderValue = freeDeliveryThreshold
        deliveryFee = fee
        val ctx = context?.applicationContext ?: appContext
        ctx?.let { c ->
            scope.launch {
                c.storeInfoDataStore.edit { prefs ->
                    prefs[FREE_DELIVERY_MIN_ORDER_VALUE_KEY] = freeDeliveryThreshold.toString()
                    prefs[DELIVERY_FEE_KEY] = fee.toString()
                }
            }
        }
        scope.launch {
            try {
                val firestore = FirebaseFirestore.getInstance()
                firestore.collection("store_settings").document("main_store_profile")
                    .set(
                        mapOf(
                            "freeDeliveryMinOrderValue" to freeDeliveryThreshold,
                            "deliveryFee" to fee,
                            "updated_at" to System.currentTimeMillis()
                        ),
                        SetOptions.merge()
                    )
            } catch (e: Exception) {
                Log.e("StoreInfoManager", "Error updating delivery charges: ${e.message}")
            }
        }
    }

    fun updateCostPriceDiscountLimitEnforcement(enabled: Boolean, context: Context? = null) {
        enforceCostPriceDiscountLimit = enabled
        val ctx = context?.applicationContext ?: appContext
        ctx?.let { c ->
            scope.launch {
                c.storeInfoDataStore.edit { prefs ->
                    prefs[ENFORCE_COST_PRICE_DISCOUNT_LIMIT_KEY] = enabled.toString()
                }
            }
        }
    }

    fun updateThermalSettings(density: String, fontSize: String, context: Context? = null, feedLines: Int = thermalFeedLines) {
        val parsedThreshold = density.toIntOrNull() ?: when (density.uppercase()) {
            "LIGHT" -> 130
            "NORMAL" -> 150
            "DARK" -> 165
            "EXTRA_DARK" -> 180
            else -> thermalThreshold
        }
        updateThermalPrinterSettings(parsedThreshold, fontSize, context, feedLines, thermalPrintSpeed)
    }

    fun updateThermalSettings(
        threshold: Int,
        fontSize: String,
        context: Context? = null,
        feedLines: Int = thermalFeedLines,
        speed: PrintSpeed = thermalPrintSpeed
    ) {
        updateThermalPrinterSettings(threshold, fontSize, context, feedLines, speed)
    }

    fun updateThermalThreshold(threshold: Int, context: Context? = null) {
        val safeThreshold = threshold.coerceIn(0, 255)
        thermalThreshold = safeThreshold
        thermalPrinterDensity = safeThreshold.toString()
        val ctx = context?.applicationContext ?: appContext
        ctx?.let { c ->
            scope.launch {
                c.storeInfoDataStore.edit { prefs ->
                    prefs[THERMAL_THRESHOLD_KEY] = safeThreshold
                    prefs[THERMAL_DENSITY_KEY] = safeThreshold.toString()
                }
            }
        }
    }

    fun updateThermalPrintSpeed(speed: PrintSpeed, context: Context? = null) {
        thermalPrintSpeed = speed
        val ctx = context?.applicationContext ?: appContext
        ctx?.let { c ->
            scope.launch {
                c.storeInfoDataStore.edit { prefs ->
                    prefs[THERMAL_PRINT_SPEED_KEY] = speed.id
                }
            }
        }
    }

    fun updateThermalNativeBoldMode(enabled: Boolean, context: Context? = null) {
        thermalNativeBoldMode = enabled
        val ctx = context?.applicationContext ?: appContext
        ctx?.let { c ->
            scope.launch {
                c.storeInfoDataStore.edit { prefs ->
                    prefs[THERMAL_NATIVE_BOLD_MODE_KEY] = enabled
                }
            }
        }
    }

    fun updateThermalRasterDilation(enabled: Boolean, context: Context? = null) {
        thermalRasterDilation = enabled
        val ctx = context?.applicationContext ?: appContext
        ctx?.let { c ->
            scope.launch {
                c.storeInfoDataStore.edit { prefs ->
                    prefs[THERMAL_RASTER_DILATION_KEY] = enabled
                }
            }
        }
    }

    fun updateThermalPrinterSettings(
        density: String,
        fontSize: String,
        context: Context? = null,
        feedLines: Int = thermalFeedLines
    ) {
        val parsedThreshold = density.toIntOrNull() ?: when (density.uppercase()) {
            "LIGHT" -> 130
            "NORMAL" -> 150
            "DARK" -> 165
            "EXTRA_DARK" -> 180
            else -> thermalThreshold
        }
        updateThermalPrinterSettings(parsedThreshold, fontSize, context, feedLines, thermalPrintSpeed)
    }

    fun updateThermalPrinterSettings(
        threshold: Int,
        fontSize: String,
        context: Context? = null,
        feedLines: Int = thermalFeedLines,
        speed: PrintSpeed = thermalPrintSpeed
    ) {
        val safeThreshold = threshold.coerceIn(0, 255)
        val safeFeedLines = feedLines.coerceIn(1, 8)
        thermalThreshold = safeThreshold
        thermalPrinterDensity = safeThreshold.toString()
        thermalReceiptFontSize = fontSize
        thermalFeedLines = safeFeedLines
        thermalPrintSpeed = speed
        val ctx = context?.applicationContext ?: appContext
        ctx?.let { c ->
            scope.launch {
                c.storeInfoDataStore.edit { prefs ->
                    prefs[THERMAL_THRESHOLD_KEY] = safeThreshold
                    prefs[THERMAL_DENSITY_KEY] = safeThreshold.toString()
                    prefs[THERMAL_FONT_SIZE_KEY] = fontSize
                    prefs[THERMAL_FEED_LINES_KEY] = safeFeedLines.toString()
                    prefs[THERMAL_PRINT_SPEED_KEY] = speed.id
                }
            }
        }
    }

    fun updateThermalFeedLines(feedLines: Int, context: Context? = null) {
        val safeFeedLines = feedLines.coerceIn(1, 8)
        thermalFeedLines = safeFeedLines
        val ctx = context?.applicationContext ?: appContext
        ctx?.let { c ->
            scope.launch {
                c.storeInfoDataStore.edit { prefs ->
                    prefs[THERMAL_FEED_LINES_KEY] = safeFeedLines.toString()
                }
            }
        }
    }

    fun updatePaperSize(paperSize: String, context: Context? = null) {
        val cleanSize = if (paperSize.equals("THERMAL_80MM", ignoreCase = true)) "THERMAL_80MM" else "THERMAL_58MM"
        pdfPaperSize = cleanSize
        val ctx = context?.applicationContext ?: appContext
        ctx?.let { c ->
            scope.launch {
                c.storeInfoDataStore.edit { prefs ->
                    prefs[PDF_PAPER_SIZE_KEY] = cleanSize
                }
            }
        }
        scope.launch {
            try {
                val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                firestore.collection("store_settings").document("main_store_profile").set(
                    mapOf(
                        "pdfPaperSize" to cleanSize,
                        "updated_at" to System.currentTimeMillis()
                    ),
                    com.google.firebase.firestore.SetOptions.merge()
                )
            } catch (_: Exception) {}
        }
    }

    fun setPaperSizeDirect(size: String) {
        pdfPaperSize = if (size.equals("THERMAL_80MM", ignoreCase = true)) "THERMAL_80MM" else "THERMAL_58MM"
    }

    fun updateSavedPrinter(address: String?, name: String?, context: Context? = null) {
        savedPrinterAddress = address
        savedPrinterName = name
        val ctx = context?.applicationContext ?: appContext
        ctx?.let { c ->
            scope.launch {
                c.storeInfoDataStore.edit { prefs ->
                    if (address != null) prefs[SAVED_PRINTER_ADDRESS_KEY] = address else prefs.remove(SAVED_PRINTER_ADDRESS_KEY)
                    if (name != null) prefs[SAVED_PRINTER_NAME_KEY] = name else prefs.remove(SAVED_PRINTER_NAME_KEY)
                }
            }
        }
    }

    fun updateAutoSendCreditSms(enabled: Boolean, context: Context? = null) {
        autoSendCreditSms = enabled
        val ctx = context?.applicationContext ?: appContext
        ctx?.let { c ->
            scope.launch {
                c.storeInfoDataStore.edit { prefs ->
                    prefs[AUTO_SEND_CREDIT_SMS_KEY] = enabled.toString()
                }
            }
        }
    }

    fun updateStoreInfo(
        name: String,
        address: String,
        owner: String,
        phoneNum: String,
        taglineStr: String = "",
        gstinStr: String = "",
        context: Context? = null
    ) {
        storeName = name.trim().ifBlank { "Kali Mata Variety Store" }
        storeAddress = address.trim()
        ownerName = owner.trim()
        phone = phoneNum.trim()
        tagline = taglineStr.trim()
        gstin = gstinStr.trim()

        val ctx = context?.applicationContext ?: appContext
        ctx?.let { c ->
            scope.launch {
                c.storeInfoDataStore.edit { prefs ->
                    prefs[STORE_NAME_KEY] = storeName
                    prefs[STORE_ADDRESS_KEY] = storeAddress
                    prefs[OWNER_NAME_KEY] = ownerName
                    prefs[PHONE_KEY] = phone
                    prefs[TAGLINE_KEY] = tagline
                    prefs[GSTIN_KEY] = gstin
                }
            }
        }
        scope.launch {
            try {
                val firestore = FirebaseFirestore.getInstance()
                firestore.collection("store_settings").document("main_store_profile").set(
                    mapOf(
                        "storeName" to storeName,
                        "storeAddress" to storeAddress,
                        "ownerName" to ownerName,
                        "phone" to phone,
                        "tagline" to tagline,
                        "gstin" to gstin,
                        "upiVpa" to upiVpa,
                        "merchantUpiId" to upiVpa,
                        "updated_at" to System.currentTimeMillis()
                    ),
                    SetOptions.merge()
                )
            } catch (e: Exception) {
                Log.w("StoreInfoManager", "Could not sync store info to Firestore: ${e.message}")
            }
        }
    }

    fun updatePdfFormatSettings(
        upiId: String,
        payeeName: String,
        showQr: Boolean,
        paperSize: String,
        footerNote: String,
        headerColor: String,
        upiSign: String = merchantUpiSign,
        context: Context? = null
    ) {
        val (parsedUpi, parsedPayee, parsedSign) = parseUpiLink(upiId)
        val cleanUpi = parsedUpi.ifBlank { upiId.trim() }
        upiVpa = cleanUpi
        merchantPayeeName = (if (parsedPayee.isNotBlank()) parsedPayee else payeeName).trim().ifBlank { storeName }
        if (parsedSign.isNotBlank()) {
            merchantUpiSign = parsedSign
        } else if (upiSign.isNotBlank()) {
            merchantUpiSign = upiSign.trim()
        }
        showQrOnPdf = showQr
        pdfPaperSize = paperSize
        customFooterNote = footerNote.trim()
        pdfHeaderColor = headerColor.trim()

        val ctx = context?.applicationContext ?: appContext
        ctx?.let { c ->
            scope.launch {
                c.storeInfoDataStore.edit { prefs ->
                    prefs[UPI_VPA_KEY] = upiVpa
                    prefs[MERCHANT_UPI_ID_KEY] = upiVpa
                    prefs[MERCHANT_NAME_KEY] = merchantPayeeName
                    prefs[MERCHANT_UPI_SIGN_KEY] = merchantUpiSign
                    prefs[SHOW_QR_ON_PDF_KEY] = showQrOnPdf.toString()
                    prefs[PDF_PAPER_SIZE_KEY] = pdfPaperSize
                    prefs[CUSTOM_FOOTER_NOTE_KEY] = customFooterNote
                    prefs[PDF_HEADER_COLOR_KEY] = pdfHeaderColor
                }
            }
        }
        scope.launch {
            try {
                val firestore = FirebaseFirestore.getInstance()
                firestore.collection("store_settings").document("main_store_profile").set(
                    mapOf(
                        "upiVpa" to upiVpa,
                        "merchantUpiId" to upiVpa,
                        "merchantPayeeName" to merchantPayeeName,
                        "showQrOnPdf" to showQrOnPdf,
                        "pdfPaperSize" to pdfPaperSize,
                        "customFooterNote" to customFooterNote,
                        "pdfHeaderColor" to pdfHeaderColor,
                        "updated_at" to System.currentTimeMillis()
                    ),
                    SetOptions.merge()
                )
            } catch (e: Exception) {
                Log.w("StoreInfoManager", "Could not sync PDF settings & UPI VPA to Firestore: ${e.message}")
            }
        }
    }

    fun getKhataInterestSettings(): KhataInterestSettings {
        return KhataInterestSettings(
            enabled = interestEnabled,
            monthlyRatePercent = interestRateMonthly,
            gracePeriodDays = interestGracePeriodDays,
            calculationMode = interestCalculationMode,
            applyRetroactively = interestApplyRetroactively,
            activationDate = interestActivationDate,
            disclaimerText = interestDisclaimerText
        )
    }

    fun updateInterestSettings(
        enabled: Boolean,
        monthlyRate: Double,
        graceDays: Int,
        calculationMode: String,
        applyRetroactively: Boolean,
        disclaimerText: String,
        context: Context? = null
    ) {
        val wasEnabled = interestEnabled
        interestEnabled = enabled
        interestRateMonthly = monthlyRate.coerceAtLeast(0.0)
        interestGracePeriodDays = graceDays.coerceAtLeast(0)
        interestCalculationMode = if (calculationMode.equals("COMPOUNDING", ignoreCase = true)) "COMPOUNDING" else "SIMPLE"
        this.interestApplyRetroactively = applyRetroactively
        if (enabled && !wasEnabled && interestActivationDate <= 0L) {
            interestActivationDate = System.currentTimeMillis()
        } else if (!enabled) {
            // Keep previous activation date or reset
        }
        if (disclaimerText.isNotBlank()) {
            interestDisclaimerText = disclaimerText.trim()
        }

        val ctx = context?.applicationContext ?: appContext
        ctx?.let { c ->
            scope.launch {
                c.storeInfoDataStore.edit { prefs ->
                    prefs[INTEREST_ENABLED_KEY] = interestEnabled.toString()
                    prefs[INTEREST_RATE_MONTHLY_KEY] = interestRateMonthly.toString()
                    prefs[INTEREST_GRACE_PERIOD_DAYS_KEY] = interestGracePeriodDays.toString()
                    prefs[INTEREST_CALCULATION_MODE_KEY] = interestCalculationMode
                    prefs[INTEREST_APPLY_RETROACTIVELY_KEY] = interestApplyRetroactively.toString()
                    prefs[INTEREST_ACTIVATION_DATE_KEY] = interestActivationDate.toString()
                    prefs[INTEREST_DISCLAIMER_TEXT_KEY] = interestDisclaimerText
                }
            }
        }

        scope.launch {
            try {
                val firestore = FirebaseFirestore.getInstance()
                val interestData = mapOf(
                    "interestEnabled" to interestEnabled,
                    "interestRateMonthly" to interestRateMonthly,
                    "interestGracePeriodDays" to interestGracePeriodDays,
                    "interestCalculationMode" to interestCalculationMode,
                    "interestApplyRetroactively" to interestApplyRetroactively,
                    "interestActivationDate" to interestActivationDate,
                    "interestDisclaimerText" to interestDisclaimerText,
                    "updated_at" to System.currentTimeMillis()
                )
                firestore.collection("store_settings").document("main_store_profile")
                    .set(interestData, SetOptions.merge())
                firestore.collection("store_settings").document("interest_settings")
                    .set(interestData, SetOptions.merge())
            } catch (e: Exception) {
                Log.w("StoreInfoManager", "Could not sync interest settings to Firestore: ${e.message}")
            }
        }
    }

    fun updateLanguagePreferences(billLang: String, smsLang: String, context: Context? = null) {
        billLanguage = billLang
        smsLanguage = smsLang
        val ctx = context?.applicationContext ?: appContext
        ctx?.let { c ->
            scope.launch {
                try {
                    c.storeInfoDataStore.edit { prefs ->
                        prefs[BILL_LANGUAGE_KEY] = billLang
                        prefs[SMS_LANGUAGE_KEY] = smsLang
                    }
                } catch (e: Exception) {
                    Log.w("StoreInfoManager", "Failed to persist language preferences: ${e.message}")
                }
            }
        }
        scope.launch {
            try {
                val firestore = FirebaseFirestore.getInstance()
                firestore.collection("store_settings").document("main_store_profile").set(
                    mapOf(
                        "billLanguage" to billLang,
                        "smsLanguage" to smsLang,
                        "updated_at" to System.currentTimeMillis()
                    ),
                    SetOptions.merge()
                )
            } catch (e: Exception) {
                Log.w("StoreInfoManager", "Could not sync language settings to Firestore: ${e.message}")
            }
        }
    }

    fun setBackupPasswordBannerDismissed(dismissed: Boolean, context: Context? = null) {
        backupPasswordBannerDismissed = dismissed
        val ctx = context?.applicationContext ?: appContext
        ctx?.let { c ->
            scope.launch {
                try {
                    c.storeInfoDataStore.edit { prefs ->
                        prefs[BACKUP_PASSWORD_BANNER_DISMISSED_KEY] = dismissed.toString()
                    }
                } catch (e: Exception) {
                    Log.w("StoreInfoManager", "Failed to persist backup password banner state: ${e.message}")
                }
            }
        }
    }
}

data class StoreDetails(
    val name: String,
    val address: String,
    val owner: String,
    val phone: String,
    val tagline: String,
    val gstin: String,
    val upiId: String,
    val payeeName: String,
    val upiSign: String = "",
    val showQr: Boolean,
    val paperSize: String = "THERMAL_58MM",
    val footerNote: String,
    val headerColor: String,
    val autoSendCreditSms: Boolean = true,
    val thermalThreshold: Int = 150,
    val thermalPrinterDensity: String = "150",
    val thermalPrintSpeed: PrintSpeed = PrintSpeed.NORMAL,
    val thermalReceiptFontSize: String = "NORMAL",
    val thermalFeedLines: Int = 4,
    val savedPrinterAddress: String? = null,
    val savedPrinterName: String? = null,
    val enforceCostPriceDiscountLimit: Boolean = true,
    val interestEnabled: Boolean = false,
    val interestRateMonthly: Double = 2.0,
    val interestGracePeriodDays: Int = 45,
    val interestCalculationMode: String = "SIMPLE",
    val interestApplyRetroactively: Boolean = false,
    val interestActivationDate: Long = 0L,
    val interestDisclaimerText: String = "Late payment interest of {rate}% per month is applicable on unpaid credit balances after {grace_days} days of grace period.",
    val backupPasswordBannerDismissed: Boolean = false,
    val billLanguage: String = "BN",
    val smsLanguage: String = "BN",
    val storeNameBn: String = "কালী মাতা ভ্যারাইটি স্টোর",
    val storeAddressBn: String = "কুরমিঠা, বীরভূম জেলা, পশ্চিমবঙ্গ",
    val onlineOrderingEnabled: Boolean = true,
    val homeDeliveryEnabled: Boolean = true,
    val freeDeliveryMinOrderValue: Double = 499.0,
    val deliveryFee: Double = 20.0,
    val thermalNativeBoldMode: Boolean = true,
    val thermalRasterDilation: Boolean = true
)

