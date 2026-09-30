package com.example.utils

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.data.local.dao.SaleWithItems
import com.example.data.local.entities.Customer
import java.text.SimpleDateFormat
import java.util.*

object SmsHelper {

    enum class SmsTemplateType(val titleEn: String, val titleBn: String) {
        FRIENDLY("Friendly Reminder", "নম্র তাগাদা"),
        STATEMENT("Khata Statement", "খাতা স্টেটমেন্ট"),
        URGENT("Payment Request (UPI)", "জরুরি তাগাদা (UPI)"),
        SHORT("Short SMS (160 Chars)", "সংক্ষিপ্ত বার্তা")
    }

    fun hasSmsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Sends an SMS directly from the device without opening the SMS app.
     * Uses multipart send for messages exceeding one SMS segment (160 characters).
     * Never crashes or throws uncaught exceptions.
     */
    fun sendDirectSms(context: Context, phone: String?, message: String): Result<Unit> {
        return try {
            val cleanPhone = phone?.replace(Regex("[^0-9+]"), "")?.trim() ?: ""
            if (cleanPhone.isBlank()) {
                Log.w("SmsHelper", "Direct SMS skipped: Customer phone number is empty or invalid.")
                return Result.failure(IllegalArgumentException("Customer phone number is missing or invalid."))
            }

            if (!hasSmsPermission(context)) {
                Log.w("SmsHelper", "Direct SMS skipped: SEND_SMS permission is not granted.")
                return Result.failure(SecurityException("SEND_SMS permission not granted."))
            }

            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            val parts = smsManager.divideMessage(message)
            if (parts.size > 1) {
                Log.d("SmsHelper", "Sending multipart SMS (${parts.size} parts) to $cleanPhone")
                smsManager.sendMultipartTextMessage(cleanPhone, null, parts, null, null)
            } else {
                Log.d("SmsHelper", "Sending single SMS to $cleanPhone")
                smsManager.sendTextMessage(cleanPhone, null, message, null, null)
            }
            Log.i("SmsHelper", "Direct SMS successfully dispatched to $cleanPhone")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("SmsHelper", "Failed to send direct SMS to $phone: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun formatPaymentModeName(mode: String, isBengali: Boolean): String {
        if (!isBengali) return mode
        val m = mode.uppercase()
        return when {
            m.contains("CASH") -> "নগদ"
            m.contains("UPI") -> "ইউপিআই"
            m.contains("CREDIT") || m.contains("DUE") || m.contains("KHATA") -> "বাকি"
            m.contains("CARD") -> "কার্ড"
            m.contains("BANK") || m.contains("NET") -> "ব্যাংক"
            m.contains("MIXED") || m.contains("SPLIT") -> "মিশ্র"
            else -> mode
        }
    }

    /**
     * Upgraded credit sale SMS notification format:
     * - Store Name header and personalized customer greeting
     * - Crystal clear breakdown: Bill, Paid, Credit Added, Total Balance Due
     * - Clean, single-line due date & interest indicator (no awkward '0 days' or duplicate sentences)
     * - Single payment link: Khata ledger & UPI pay URL (or fallback payment URL)
     * - Clean layout with lines and emojis, highly readable on any phone
     */
    fun generateCreditSaleSms(
        customerName: String,
        storeName: String,
        billTotal: Double,
        paidAmount: Double,
        creditAdded: Double,
        totalOutstandingBalance: Double,
        transactionDateMs: Long = System.currentTimeMillis(),
        merchantUpiId: String? = null,
        merchantPayeeName: String? = null,
        isBengali: Boolean = StoreInfoManager.isSmsBengali(),
        includeUpi: Boolean = true,
        khataUrl: String? = null
    ): String {
        val cleanName = customerName.trim().ifBlank { if (isBengali) "গ্রাহক" else "Customer" }
        val store = storeName.trim().ifBlank { "Kali Mata Variety Store" }
        val dateStr = SimpleDateFormat("dd/MM/yy", Locale.US).format(Date(transactionDateMs))

        fun formatAmt(v: Double): String {
            return if (v % 1.0 == 0.0) "${v.toLong()}" else "%.2f".format(Locale.US, v).trimEnd('0').trimEnd('.')
        }

        val billStr = formatAmt(billTotal)
        val paidStr = formatAmt(paidAmount)
        val creditStr = formatAmt(creditAdded)
        val totalDueStr = formatAmt(totalOutstandingBalance)
        val cleanKhata = khataUrl?.trim() ?: ""

        val vpa = (merchantUpiId?.takeIf { it.isNotBlank() } ?: StoreInfoManager.upiVpa).trim()
        val cleanUpi = if (includeUpi) vpa else ""

        val duePayAmount = if (totalOutstandingBalance > 0.0) totalOutstandingBalance else if (creditAdded > 0.0) creditAdded else billTotal

        // Use Khata URL if available (gives complete bill breakdown + 1-tap UPI payment).
        // Fallback to hosted pay URL ONLY if no Khata URL exists, preventing duplicate messy links.
        val fallbackPayLink = if (cleanKhata.isBlank() && cleanUpi.isNotBlank() && duePayAmount > 0.0) {
            StoreInfoManager.buildHostedPayUrl(
                upiId = cleanUpi,
                payeeName = merchantPayeeName ?: StoreInfoManager.merchantPayeeName,
                amount = duePayAmount,
                note = "Due Payment",
                store = store
            )
        } else ""

        val interestSettings = StoreInfoManager.getKhataInterestSettings()
        val effectiveGraceDays = interestSettings.gracePeriodDays
        val dueDateMs = KhataInterestCalculator.calculateDueDateTimestamp(transactionDateMs, null, effectiveGraceDays)
        val sdfDate = SimpleDateFormat("dd/MM/yyyy", Locale.US).format(Date(dueDateMs))
        val bnDigits = charArrayOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')
        val dueDateDisplay = if (isBengali) {
            val sb = StringBuilder()
            for (ch in sdfDate) {
                if (ch in '0'..'9') sb.append(bnDigits[ch - '0']) else sb.append(ch)
            }
            sb.toString()
        } else sdfDate

        val dueInfoLine = if (totalOutstandingBalance > 0.0 && interestSettings.enabled) {
            if (interestSettings.monthlyRatePercent > 0.0) {
                val rate = interestSettings.monthlyRatePercent
                val rateStr = if (rate % 1.0 == 0.0) "${rate.toInt()}%" else "%.1f%%".format(Locale.US, rate)
                val rateDisplay = if (isBengali) {
                    val sb = StringBuilder()
                    for (ch in rateStr) {
                        if (ch in '0'..'9') sb.append(bnDigits[ch - '0']) else sb.append(ch)
                    }
                    sb.toString()
                } else rateStr

                if (isBengali) {
                    if (effectiveGraceDays > 0) {
                        val daysBn = StringBuilder().apply {
                            for (ch in effectiveGraceDays.toString()) {
                                if (ch in '0'..'9') append(bnDigits[ch - '0']) else append(ch)
                            }
                        }.toString()
                        "পরিশোধের শেষ তারিখ: $dueDateDisplay ($daysBn দিন পর মাসিক $rateDisplay বিলম্ব সুদ)"
                    } else {
                        "পরিশোধের শেষ তারিখ: $dueDateDisplay (বিলম্বের পর মাসিক $rateDisplay সুদ)"
                    }
                } else {
                    if (effectiveGraceDays > 0) {
                        "Due Date: $dueDateDisplay ($rateStr/mo interest after $effectiveGraceDays days)"
                    } else {
                        "Due Date: $dueDateDisplay ($rateStr/mo late interest)"
                    }
                }
            } else {
                KhataInterestCalculator.formatDueDate(dueDateMs, effectiveGraceDays, isBengali)
            }
        } else ""

        return if (isBengali) {
            buildString {
                append("$store\n")
                append("নমস্কার $cleanName ($dateStr),\n\n")
                append("বিল: ₹$billStr | জমা: ₹$paidStr\n")
                append("বাকি যোগ: ₹$creditStr\n")
                append("মোট বকেয়া: ₹$totalDueStr\n")
                if (dueInfoLine.isNotBlank()) {
                    append("$dueInfoLine\n")
                }
                append("\n")
                if (cleanUpi.isNotBlank()) {
                    append("💳 UPI ID: $cleanUpi\n")
                }
                if (cleanKhata.isNotBlank()) {
                    append("🔗 খাতা ও পেমেন্ট: $cleanKhata\n")
                } else if (fallbackPayLink.isNotBlank()) {
                    append("🔗 অনলাইন পেমেন্ট: $fallbackPayLink\n")
                }
                append("\nধন্যবাদ!")
            }
        } else {
            buildString {
                append("$store\n")
                append("Hi $cleanName ($dateStr),\n\n")
                append("Bill: Rs.$billStr | Paid: Rs.$paidStr\n")
                append("Credit Added: Rs.$creditStr\n")
                append("Total Due: Rs.$totalDueStr\n")
                if (dueInfoLine.isNotBlank()) {
                    append("$dueInfoLine\n")
                }
                append("\n")
                if (cleanUpi.isNotBlank()) {
                    append("💳 UPI ID: $cleanUpi\n")
                }
                if (cleanKhata.isNotBlank()) {
                    append("🔗 View Khata & Pay: $cleanKhata\n")
                } else if (fallbackPayLink.isNotBlank()) {
                    append("🔗 Pay Online: $fallbackPayLink\n")
                }
                append("\nThank you!")
            }
        }
    }

    /**
     * Customer Repayment SMS confirmation format:
     * - Store Name header & Customer greeting
     * - Repaid Amount & Payment Mode
     * - Remaining Due Balance
     * - Online Khata ledger link
     * - Thank you acknowledgment
     */
    fun generatePaymentRepaymentSms(
        customerName: String,
        storeName: String,
        repaidAmount: Double,
        paymentMode: String,
        remainingBalance: Double,
        transactionDateMs: Long = System.currentTimeMillis(),
        isBengali: Boolean = StoreInfoManager.isSmsBengali(),
        khataUrl: String? = null
    ): String {
        val cleanName = customerName.trim().ifBlank { if (isBengali) "গ্রাহক" else "Customer" }
        val store = storeName.trim().ifBlank { "Kali Mata Variety Store" }
        val dateStr = SimpleDateFormat("dd/MM/yy", Locale.US).format(Date(transactionDateMs))

        fun formatAmt(v: Double): String {
            return if (v % 1.0 == 0.0) "${v.toLong()}" else "%.2f".format(Locale.US, v).trimEnd('0').trimEnd('.')
        }

        val repaidStr = formatAmt(repaidAmount)
        val modeStr = formatPaymentModeName(paymentMode, isBengali)
        val cleanKhata = khataUrl?.trim() ?: ""

        return if (isBengali) {
            val balanceStr = when {
                remainingBalance > 0.0 -> "বর্তমান বাকি বকেয়া: ₹${formatAmt(remainingBalance)}"
                remainingBalance < 0.0 -> "অগ্রিম জমা: ₹${formatAmt(kotlin.math.abs(remainingBalance))}"
                else -> "বাকি সম্পূর্ণ পরিশোধ হয়েছে (₹০)"
            }
            buildString {
                append("$store\n")
                append("নমস্কার $cleanName ($dateStr),\n\n")
                append("পরিশোধ গৃহীত: ₹$repaidStr ($modeStr)\n")
                append("$balanceStr\n")
                if (cleanKhata.isNotBlank()) {
                    append("\n🔗 খাতা দেখতে: $cleanKhata\n")
                }
                append("\nধন্যবাদ!")
            }
        } else {
            val balanceStr = when {
                remainingBalance > 0.0 -> "Current Due Balance: Rs.${formatAmt(remainingBalance)}"
                remainingBalance < 0.0 -> "Advance Balance: Rs.${formatAmt(kotlin.math.abs(remainingBalance))}"
                else -> "All Dues Cleared (Rs.0)"
            }
            buildString {
                append("$store\n")
                append("Hi $cleanName ($dateStr),\n\n")
                append("Payment Received: Rs.$repaidStr ($modeStr)\n")
                append("$balanceStr\n")
                if (cleanKhata.isNotBlank()) {
                    append("\n🔗 View Khata: $cleanKhata\n")
                }
                append("\nThank you!")
            }
        }
    }

    /**
     * Customer Payment Claim Confirmation SMS format:
     * Sent when shopkeeper approves an "I've Paid" self-reported claim.
     */
    fun generatePaymentClaimConfirmedSms(
        customerName: String,
        storeName: String,
        confirmedAmount: Double,
        remainingBalance: Double,
        khataUrl: String? = null,
        transactionDateMs: Long = System.currentTimeMillis(),
        isBengali: Boolean = StoreInfoManager.isSmsBengali()
    ): String {
        val cleanName = customerName.trim().ifBlank { if (isBengali) "গ্রাহক" else "Customer" }
        val store = storeName.trim().ifBlank { "Kali Mata Variety Store" }
        val dateStr = SimpleDateFormat("dd/MM/yy", Locale.US).format(Date(transactionDateMs))

        fun formatAmt(v: Double): String {
            return if (v % 1.0 == 0.0) "${v.toLong()}" else "%.2f".format(Locale.US, v).trimEnd('0').trimEnd('.')
        }

        val amtStr = formatAmt(confirmedAmount)
        val cleanKhata = khataUrl?.trim() ?: ""

        return if (isBengali) {
            val balanceStr = when {
                remainingBalance > 0.0 -> "বর্তমান বাকি বকেয়া: ₹${formatAmt(remainingBalance)}"
                remainingBalance < 0.0 -> "অগ্রিম জমা: ₹${formatAmt(kotlin.math.abs(remainingBalance))}"
                else -> "বাকি সম্পূর্ণ পরিশোধ হয়েছে (₹০)"
            }
            buildString {
                append("$store\n")
                append("নমস্কার $cleanName ($dateStr),\n\n")
                append("পেমেন্ট নিশ্চিত: ₹$amtStr জমা হয়েছে।\n")
                append("$balanceStr\n")
                if (cleanKhata.isNotBlank()) {
                    append("\n🔗 খাতা দেখতে: $cleanKhata\n")
                }
                append("\nধন্যবাদ!")
            }
        } else {
            val balanceStr = when {
                remainingBalance > 0.0 -> "Current Due Balance: Rs.${formatAmt(remainingBalance)}"
                remainingBalance < 0.0 -> "Advance Balance: Rs.${formatAmt(kotlin.math.abs(remainingBalance))}"
                else -> "All Dues Cleared (Rs.0)"
            }
            buildString {
                append("$store\n")
                append("Hi $cleanName ($dateStr),\n\n")
                append("Payment Confirmed: Rs.$amtStr received.\n")
                append("$balanceStr\n")
                if (cleanKhata.isNotBlank()) {
                    append("\n🔗 View Khata: $cleanKhata\n")
                }
                append("\nThank you!")
            }
        }
    }

    fun sendSms(context: Context, phone: String?, message: String) {
        try {
            val cleanPhone = phone?.replace(Regex("[^0-9+]"), "") ?: ""
            val uri = if (cleanPhone.isNotBlank()) Uri.parse("smsto:$cleanPhone") else Uri.parse("smsto:")
            val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
                putExtra("sms_body", message)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Cannot open messaging app", Toast.LENGTH_SHORT).show()
        }
    }

    fun copyToClipboard(context: Context, text: String, label: String = "SMS Message") {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(label, text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Message copied to clipboard", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Could not copy message", Toast.LENGTH_SHORT).show()
        }
    }

    fun generateBillSms(saleWithItems: SaleWithItems, isBengali: Boolean = StoreInfoManager.isSmsBengali()): String {
        val dateFormat = SimpleDateFormat("dd/MM/yyyy hh:mm a", Locale.getDefault())
        val dateStr = dateFormat.format(Date(saleWithItems.sale.datetime))
        val storeName = StoreInfoManager.storeName

        val itemsSummary = saleWithItems.items.joinToString(", ") { item ->
            val name = if (isBengali && item.productNameBn.isNotBlank()) {
                item.productNameBn
            } else if (item.productNameEn.isNotBlank()) {
                item.productNameEn
            } else {
                item.productNameBn.ifBlank { if (isBengali) "পণ্য" else "Item" }
            }
            val unitStr = if (isBengali) {
                when {
                    item.unitType.contains("gm", true) || item.unitType.contains("gram", true) || item.unitType.contains("গ্রাম", true) -> "গ্রাম"
                    item.unitType.contains("kg", true) || item.unitType.contains("কেজি", true) -> "কেজি"
                    item.unitType.contains("litre", true) || item.unitType.contains("liter", true) || item.unitType.contains("লিটার", true) -> "লিটার"
                    item.unitType.contains("box", true) || item.unitType.contains("বক্স", true) -> "বক্স"
                    item.unitType.contains("pkt", true) || item.unitType.contains("packet", true) || item.unitType.contains("প্যাকেট", true) -> "প্যাকেট"
                    item.unitType.contains("pcs", true) || item.unitType.contains("piece", true) || item.unitType.contains("পিস", true) -> "পিস"
                    else -> item.unitType.ifBlank { "পিস" }
                }
            } else {
                item.unitType.ifBlank { "pcs" }
            }
            val qtyFormatted = if (item.unitType.equals("gram", ignoreCase = true) || item.unitType.contains("গ্রাম", true)) {
                "${item.quantity.toInt()}$unitStr"
            } else if (item.quantity % 1.0 == 0.0) {
                "${item.quantity.toInt()} $unitStr"
            } else {
                "%.2f $unitStr".format(Locale.US, item.quantity)
            }
            "$name ($qtyFormatted)"
        }

        val sale = saleWithItems.sale
        val excessPaid = (sale.receivedAmount - sale.finalAmount).coerceAtLeast(0.0)
        val totalBal = (sale.previousBalance + sale.dueAmount - excessPaid).coerceAtLeast(0.0)
        val payModeDisplay = formatPaymentModeName(sale.paymentMode, isBengali)

        val creditExtraEn = if (totalBal > 0 || sale.dueAmount > 0 || sale.previousBalance > 0) {
            val prevLine = if (sale.previousBalance > 0) "\nPrevious Due: Rs.%.2f".format(sale.previousBalance) else ""
            "\nPaid: Rs.%.2f (%s)\nAdded Credit: Rs.%.2f%s\nTotal Balance Due: Rs.%.2f".format(sale.receivedAmount, sale.paymentMode, sale.dueAmount, prevLine, totalBal)
        } else {
            "\nPaid: Rs.%.2f (%s)".format(sale.receivedAmount, sale.paymentMode)
        }

        val creditExtraBn = if (totalBal > 0 || sale.dueAmount > 0 || sale.previousBalance > 0) {
            val prevLine = if (sale.previousBalance > 0) "\nপূর্বের বকেয়া বাকি: ₹%.2f".format(sale.previousBalance) else ""
            "\nজমা: ₹%.2f (%s)\nআজ বাকি যোগ: ₹%.2f%s\nসর্বমোট বাকি বকেয়া: ₹%.2f".format(sale.receivedAmount, payModeDisplay, sale.dueAmount, prevLine, totalBal)
        } else {
            "\nজমা: ₹%.2f (%s)".format(sale.receivedAmount, payModeDisplay)
        }

        return if (isBengali) {
            "বিল রসিদ - $storeName\nবিল নং: #${saleWithItems.sale.id.takeLast(6)}\nতারিখ: $dateStr\nপণ্য: $itemsSummary\nমোট বিল: ₹%.2f$creditExtraBn\nকেনাকাটার জন্য ধন্যবাদ!"
                .format(saleWithItems.sale.finalAmount)
        } else {
            "Bill Receipt - $storeName\nBill No: #${saleWithItems.sale.id.takeLast(6)}\nDate: $dateStr\nItems: $itemsSummary\nBill Total: Rs.%.2f$creditExtraEn\nThank you for shopping!"
                .format(saleWithItems.sale.finalAmount)
        }
    }

    fun generatePaymentReminderSms(
        customer: Customer,
        isBengali: Boolean = StoreInfoManager.isSmsBengali(),
        templateType: SmsTemplateType = SmsTemplateType.FRIENDLY,
        includeUpi: Boolean = false,
        customUpiId: String? = null,
        khataUrl: String? = null
    ): String {
        val storeName = StoreInfoManager.storeName.ifBlank { "Kali Mata Variety Store" }
        val storePhone = StoreInfoManager.phone
        val vpa = (customUpiId?.takeIf { it.isNotBlank() } ?: StoreInfoManager.upiVpa).trim()
        val cleanUpi = if (includeUpi) vpa else ""

        val absBalance = kotlin.math.abs(customer.balance)
        val todayStr = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())

        fun formatAmt(v: Double): String {
            return if (v % 1.0 == 0.0) "${v.toLong()}" else "%.2f".format(Locale.US, v).trimEnd('0').trimEnd('.')
        }
        val balanceFormatted = formatAmt(absBalance)

        val resolvedKhataUrl = (khataUrl?.takeIf { it.isNotBlank() }
            ?: customer.shareToken?.takeIf { it.isNotBlank() }?.let { StoreInfoManager.buildCustomerKhataUrl(it) })?.trim() ?: ""

        val fallbackPayUrl = if (resolvedKhataUrl.isBlank() && includeUpi && cleanUpi.isNotBlank() && absBalance > 0.0) {
            StoreInfoManager.buildHostedPayUrl(
                upiId = cleanUpi,
                payeeName = StoreInfoManager.merchantPayeeName,
                amount = absBalance,
                note = "Due Payment",
                store = storeName
            )
        } else ""

        val interestSettings = StoreInfoManager.getKhataInterestSettings()
        val effectiveGraceDays = customer.customGracePeriodDays ?: interestSettings.gracePeriodDays
        val dueDateMs = KhataInterestCalculator.calculateDueDateTimestamp(System.currentTimeMillis(), null, effectiveGraceDays)
        val dueDateFormatted = KhataInterestCalculator.formatDueDate(dueDateMs, effectiveGraceDays, isBengali)
        val dueDateFormattedEn = KhataInterestCalculator.formatDueDate(dueDateMs, effectiveGraceDays, false)

        val interestDisclaimer = if (absBalance > 0.0 && interestSettings.enabled) {
            val disc = KhataInterestCalculator.formatDisclaimer(
                settings = interestSettings,
                customer = customer,
                isBengali = isBengali,
                dueDateMs = dueDateMs,
                dueAmount = absBalance
            )
            if (disc.isNotBlank()) "\n⚠️ $disc" else ""
        } else ""
        val interestDisclaimerEn = if (absBalance > 0.0 && interestSettings.enabled) {
            val disc = KhataInterestCalculator.formatDisclaimer(
                settings = interestSettings,
                customer = customer,
                isBengali = false,
                dueDateMs = dueDateMs,
                dueAmount = absBalance
            )
            if (disc.isNotBlank()) "\n⚠️ $disc" else ""
        } else ""

        val payBlockBn = buildString {
            if (cleanUpi.isNotBlank()) append("\n💳 UPI ID: $cleanUpi")
            if (resolvedKhataUrl.isNotBlank()) {
                append("\n🔗 খাতা ও পেমেন্ট: $resolvedKhataUrl")
            } else if (fallbackPayUrl.isNotBlank()) {
                append("\n🔗 অনলাইন পেমেন্ট: $fallbackPayUrl")
            }
        }

        val payBlockEn = buildString {
            if (cleanUpi.isNotBlank()) append("\n💳 UPI ID: $cleanUpi")
            if (resolvedKhataUrl.isNotBlank()) {
                append("\n🔗 View Khata & Pay: $resolvedKhataUrl")
            } else if (fallbackPayUrl.isNotBlank()) {
                append("\n🔗 Pay Online: $fallbackPayUrl")
            }
        }

        val phoneSuffixEn = if (storePhone.isNotBlank()) "\nPh: $storePhone" else ""
        val phoneSuffixBn = if (storePhone.isNotBlank()) "\nযোগাযোগ: $storePhone" else ""

        return when (templateType) {
            SmsTemplateType.FRIENDLY -> {
                if (isBengali) {
                    "$storeName\nপ্রিয় ${customer.name}, আপনার বর্তমান বাকি বকেয়া ₹$balanceFormatted।\n$dueDateFormatted।$payBlockBn$interestDisclaimer\n\nধন্যবাদ!"
                } else {
                    "$storeName\nDear ${customer.name}, your current due balance is Rs.$balanceFormatted.\n$dueDateFormattedEn.$payBlockEn$interestDisclaimerEn\n\nThank you!"
                }
            }
            SmsTemplateType.STATEMENT -> {
                if (isBengali) {
                    "খাতা স্টেটমেন্ট - $storeName\nগ্রাহক: ${customer.name}\nবকেয়া পরিমাণ: ₹$balanceFormatted\nতারিখ: $todayStr\n$dueDateFormatted$phoneSuffixBn$payBlockBn$interestDisclaimer\n\nধন্যবাদ!"
                } else {
                    "Khata Statement - $storeName\nCustomer: ${customer.name}\nOutstanding Due: Rs.$balanceFormatted\nDate: $todayStr\n$dueDateFormattedEn$phoneSuffixEn$payBlockEn$interestDisclaimerEn\n\nThank you!"
                }
            }
            SmsTemplateType.URGENT -> {
                if (isBengali) {
                    "বকেয়া পরিশোধের তাগাদা - $storeName\nপ্রিয় ${customer.name}, আপনার ₹$balanceFormatted টাকা বকেয়া রয়েছে।\n$dueDateFormatted।\nখাতা সচল রাখতে দ্রুত পরিশোধ করার অনুরোধ রইল।$payBlockBn$phoneSuffixBn$interestDisclaimer\n\nধন্যবাদ।"
                } else {
                    "Payment Due Reminder - $storeName\nDear ${customer.name}, you have a pending balance of Rs.$balanceFormatted.\n$dueDateFormattedEn.\nPlease settle promptly to keep your credit active.$payBlockEn$phoneSuffixEn$interestDisclaimerEn\n\nThank you."
                }
            }
            SmsTemplateType.SHORT -> {
                val upiShort = if (resolvedKhataUrl.isNotBlank()) {
                    " | লিংক: $resolvedKhataUrl"
                } else if (fallbackPayUrl.isNotBlank()) {
                    " | UPI: $cleanUpi ($fallbackPayUrl)"
                } else if (cleanUpi.isNotBlank()) {
                    " | UPI: $cleanUpi"
                } else ""
                if (isBengali) {
                    "$storeName: প্রিয় ${customer.name}, বাকি ₹$balanceFormatted ($dueDateFormatted)$upiShort। ধন্যবাদ!"
                } else {
                    "$storeName: Hi ${customer.name}, balance due is Rs.$balanceFormatted ($dueDateFormattedEn)$upiShort. Thanks!"
                }
            }
        }
    }

    fun generateStatementSms(
        customerName: String,
        currentBalance: Double,
        salesCount: Int,
        totalBilledValuation: Double,
        isBengali: Boolean = StoreInfoManager.isSmsBengali()
    ): String {
        val storeName = StoreInfoManager.storeName
        return if (isBengali) {
            "গ্রাহক খাতা তথ্য - ${storeName}\nগ্রাহক: ${customerName}\nমোট বিল সংখ্যা: ${salesCount}টি (₹%.2f)\nবর্তমান বকেয়া পরিমাণ: ₹%.2f\nধন্যবাদ!"
                .format(totalBilledValuation, currentBalance)
        } else {
            "Customer Khata Statement - ${storeName}\nCustomer: ${customerName}\nTotal Bills: ${salesCount} (Rs.%.2f)\nCurrent Due Balance: Rs.%.2f\nThank you!"
                .format(totalBilledValuation, currentBalance)
        }
    }

    fun generateSharedKhataSms(
        customerName: String,
        currentBalance: Double,
        shareUrl: String,
        isBengali: Boolean = StoreInfoManager.isSmsBengali()
    ): String {
        val storeName = StoreInfoManager.storeName.ifBlank { "Kali Mata Variety Store" }
        val balanceFormatted = "%.2f".format(Locale.US, currentBalance)
        return if (isBengali) {
            "ডিজিটাল খাতা - ${storeName}\nপ্রিয় ${customerName}, আপনার মোট বাকি বকেয়া ₹${balanceFormatted}। সম্পূর্ণ খাতার হিসাব দেখতে ও পেমেন্ট করতে লিঙ্কে ক্লিক করুন: ${shareUrl}\nধন্যবাদ!"
        } else {
            "Digital Khata - ${storeName}\nHi ${customerName}, your current due balance is Rs.${balanceFormatted}. View complete ledger & pay online: ${shareUrl}\nThank you!"
        }
    }
}
