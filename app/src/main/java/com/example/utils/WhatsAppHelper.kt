package com.example.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.data.local.dao.SaleWithItems
import com.example.data.local.entities.Customer
import com.example.data.local.entities.Product
import java.text.SimpleDateFormat
import java.util.*

object WhatsAppHelper {

    private fun formatBengaliDigits(input: String): String {
        val bnDigits = charArrayOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')
        val sb = StringBuilder()
        for (ch in input) {
            if (ch in '0'..'9') {
                sb.append(bnDigits[ch - '0'])
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun formatQtyWithUnit(qty: Double, unitType: String, isBengali: Boolean): String {
        val isGram = com.example.data.local.entities.Product.isGramUnit(unitType)
        val isKg = com.example.data.local.entities.Product.isKgUnit(unitType)
        val isPcs = unitType.equals("pcs", ignoreCase = true) || unitType.equals("piece", ignoreCase = true) || unitType.contains("পিস", ignoreCase = true)
        val isLitre = com.example.data.local.entities.Product.isLitreUnit(unitType)
        val isMl = com.example.data.local.entities.Product.isMlUnit(unitType)
        val isBox = unitType.equals("box", ignoreCase = true) || unitType.contains("বক্স", ignoreCase = true) || unitType.contains("বাক্স", ignoreCase = true)

        val rawQty = if (isGram || isMl || qty % 1.0 == 0.0) {
            "${qty.toInt()}"
        } else {
            "%.2f".format(Locale.US, qty)
        }

        val unitName = if (isBengali) {
            when {
                isGram -> "গ্রাম"
                isKg -> "কেজি"
                isPcs -> "পিস"
                isLitre -> "লিটার"
                isMl -> "মিলি"
                isBox -> "বক্স"
                else -> unitType
            }
        } else {
            when {
                isGram -> "g"
                isKg -> "kg"
                isPcs -> "pcs"
                isLitre -> "L"
                isMl -> "ml"
                isBox -> if (qty == 1.0) "box" else "boxes"
                else -> unitType
            }
        }

        val numStr = if (isBengali) formatBengaliDigits(rawQty) else rawQty
        return "$numStr $unitName"
    }

    fun generateBillMessage(saleWithItems: SaleWithItems, isBengali: Boolean = StoreInfoManager.isBillBengali()): String {
        val dateFormat = SimpleDateFormat("dd/MM/yy hh:mma", Locale.US)
        val dateRaw = dateFormat.format(Date(saleWithItems.sale.datetime))
        val dateStr = if (isBengali) formatBengaliDigits(dateRaw) else dateRaw

        val billNoRaw = saleWithItems.sale.id.takeLast(6)
        val billNoStr = if (isBengali) formatBengaliDigits(billNoRaw) else billNoRaw

        val phoneStr = if (isBengali) formatBengaliDigits(StoreInfoManager.phone) else StoreInfoManager.phone
        val storeName = StoreInfoManager.getStoreDisplayName(isBengali).ifBlank { "Store" }
        val storeAddress = StoreInfoManager.getStoreDisplayAddress(isBengali)

        val sb = StringBuilder()
        sb.append("================================\n")
        sb.append("    *$storeName*    \n")
        if (storeAddress.isNotBlank()) {
            if (phoneStr.isNotBlank()) {
                sb.append("  $storeAddress | Ph: $phoneStr\n")
            } else {
                sb.append("  $storeAddress\n")
            }
        } else if (phoneStr.isNotBlank()) {
            sb.append("  Ph: $phoneStr\n")
        }
        sb.append("--------------------------------\n")
        
        // Bill metadata row: Bill No & Date/Time
        val billHeader = if (isBengali) "বিল নং: #$billNoStr" else "Bill No: #$billNoStr"
        sb.append("$billHeader     $dateStr\n")

        // Customer & Staff row
        val rawCust = saleWithItems.sale.customerName?.trim()?.ifBlank { null }
        val custName = BengaliReceiptTranslator.translateCustomerName(rawCust, isBengali)
        val rawStaff = saleWithItems.sale.staffName?.trim()?.ifBlank { null }
        val staffName = BengaliReceiptTranslator.translateStaffName(rawStaff, isBengali)
        val custLabel = if (isBengali) "গ্রাহক: $custName" else "Customer: $custName"
        val staffLabel = if (isBengali) "স্টাফ: $staffName" else "Staff: $staffName"
        sb.append("$custLabel        $staffLabel\n")

        // Payment mode row
        val sale = saleWithItems.sale
        val payModeStr = if (isBengali) {
            when {
                sale.dueAmount > 0 && sale.receivedAmount > 0 -> {
                    if (sale.paymentMode.contains("UPI", ignoreCase = true)) "আংশিক অনলাইন / বাকি" else "আংশিক নগদ / বাকি"
                }
                sale.dueAmount > 0 && sale.receivedAmount == 0.0 -> "বাকি (খাতা)"
                sale.paymentMode.contains("UPI", ignoreCase = true) -> "অনলাইন (UPI)"
                sale.paymentMode.contains("CASH", ignoreCase = true) -> "নগদ (Cash)"
                else -> BengaliReceiptTranslator.translatePaymentMode(sale.paymentMode, true)
            }
        } else {
            when {
                sale.dueAmount > 0 && sale.receivedAmount > 0 -> {
                    if (sale.paymentMode.contains("UPI", ignoreCase = true)) "Partial Online / Due" else "Partial Cash / Due"
                }
                sale.dueAmount > 0 && sale.receivedAmount == 0.0 -> "Khata (Due)"
                sale.paymentMode.contains("UPI", ignoreCase = true) -> "Online (UPI)"
                sale.paymentMode.contains("CASH", ignoreCase = true) -> "Cash"
                else -> sale.paymentMode
            }
        }
        val payModeLine = if (isBengali) "পেমেন্ট মোড: $payModeStr" else "Payment Mode: $payModeStr"
        sb.append("$payModeLine\n")
        sb.append("--------------------------------\n")

        // Items Header
        val colHeader = if (isBengali) {
            "পণ্য             পরিমাণ     মোট(₹)"
        } else {
            "Item             Qty       Total(₹)"
        }
        sb.append("$colHeader\n")
        sb.append("--------------------------------\n")

        saleWithItems.items.forEach { item ->
            val name = if (isBengali) {
                if (item.productNameBn.isNotBlank() && item.productNameEn.isNotBlank() && !item.productNameBn.equals(item.productNameEn, ignoreCase = true)) {
                    val cleanBn = BengaliReceiptTranslator.cleanReceiptProductName(item.productNameBn)
                    val cleanEn = BengaliReceiptTranslator.cleanReceiptProductName(item.productNameEn)
                    if (cleanBn.equals(cleanEn, ignoreCase = true)) cleanBn else "$cleanBn ($cleanEn)"
                } else if (item.productNameBn.isNotBlank()) {
                    BengaliReceiptTranslator.cleanReceiptProductName(item.productNameBn)
                } else {
                    BengaliReceiptTranslator.translateItem(item.productNameEn)
                }
            } else {
                BengaliReceiptTranslator.cleanReceiptProductName(if (item.productNameEn.isNotBlank()) item.productNameEn else item.productNameBn.ifBlank { "Item" })
            }
            val qtyStr = formatQtyWithUnit(item.quantity, item.unitType, isBengali)
            val subtotalNum = "%.2f".format(Locale.US, item.subtotal)
            val subtotalStr = if (isBengali) formatBengaliDigits(subtotalNum) else subtotalNum

            sb.append("%-16s %-9s %s\n".format(Locale.US, name.take(16), qtyStr, subtotalStr))
        }

        sb.append("--------------------------------\n")
        val totalNum = "%.2f".format(Locale.US, sale.totalAmount)
        val discNum = "%.2f".format(Locale.US, sale.discount)
        val finalNum = "%.2f".format(Locale.US, sale.finalAmount)

        val totalDisplay = if (isBengali) "₹${formatBengaliDigits(finalNum)}" else "₹$finalNum"
        val purLabel = if (isBengali) "মোট কেনাকাটা:" else "Total Purchase:"
        sb.append("%-22s %s\n".format(Locale.US, purLabel, totalDisplay))

        if (sale.discount > 0) {
            val discDisplay = if (isBengali) "-₹${formatBengaliDigits(discNum)}" else "-₹$discNum"
            val discLabel = if (isBengali) "ছাড় (Discount):" else "Discount:"
            sb.append("%-22s %s\n".format(Locale.US, discLabel, discDisplay))
        }

        val recNum = "%.2f".format(Locale.US, sale.receivedAmount)
        val recDisplay = if (isBengali) "₹${formatBengaliDigits(recNum)}" else "₹$recNum"
        val paidLabel = if (isBengali) {
            if (sale.paymentMode.contains("UPI", ignoreCase = true)) "অনলাইন জমা:" else if (sale.paymentMode.contains("CASH", ignoreCase = true)) "নগদ জমা:" else "নগদ/অনলাইন জমা:"
        } else {
            if (sale.paymentMode.contains("UPI", ignoreCase = true)) "UPI Paid:" else if (sale.paymentMode.contains("CASH", ignoreCase = true)) "Cash Paid:" else "Paid Amount:"
        }
        sb.append("%-22s %s\n".format(Locale.US, paidLabel, recDisplay))

        if (sale.dueAmount > 0) {
            val dueNum = "%.2f".format(Locale.US, sale.dueAmount)
            val dueDisplay = if (isBengali) "₹${formatBengaliDigits(dueNum)}" else "₹$dueNum"
            val dueLabel = if (isBengali) "আজকের বাকি:" else "Today's Due:"
            sb.append("%-22s %s\n".format(Locale.US, dueLabel, dueDisplay))
        }

        val prevNum = "%.2f".format(Locale.US, sale.previousBalance)
        val prevDisplay = if (isBengali) "₹${formatBengaliDigits(prevNum)}" else "₹$prevNum"
        val prevLabel = if (isBengali) "পূর্বের বকেয়া:" else "Previous Due:"
        sb.append("%-22s %s\n".format(Locale.US, prevLabel, prevDisplay))

        val excessPaid = (sale.receivedAmount - sale.finalAmount).coerceAtLeast(0.0)
        val netRemainingDue = (sale.previousBalance + sale.dueAmount - excessPaid).coerceAtLeast(0.0)
        val advanceCredit = (excessPaid - (sale.previousBalance + sale.dueAmount)).coerceAtLeast(0.0)

        if (excessPaid > 0 && netRemainingDue <= 0.0 && advanceCredit <= 0.0) {
            val exNum = "%.2f".format(Locale.US, excessPaid)
            val exDisplay = if (isBengali) "₹${formatBengaliDigits(exNum)}" else "₹$exNum"
            val exLabel = if (isBengali) "ফেরত টাকা:" else "Change Returned:"
            sb.append("%-22s %s\n".format(Locale.US, exLabel, exDisplay))
        }

        sb.append("================================\n")
        if (netRemainingDue > 0) {
            val netDueNum = "%.2f".format(Locale.US, netRemainingDue)
            val netDueDisplay = if (isBengali) "₹${formatBengaliDigits(netDueNum)}" else "₹$netDueNum"
            if (isBengali) {
                sb.append("  বর্তমান মোট বাকি: $netDueDisplay  \n")
            } else {
                sb.append("  CURRENT TOTAL DUE: $netDueDisplay  \n")
            }
        } else if (advanceCredit > 0) {
            val advNum = "%.2f".format(Locale.US, advanceCredit)
            val advDisplay = if (isBengali) "₹${formatBengaliDigits(advNum)}" else "₹$advNum"
            if (isBengali) {
                sb.append("  অগ্রিম জমা ব্যালেন্স: $advDisplay  \n")
            } else {
                sb.append("  ADVANCE BALANCE: $advDisplay  \n")
            }
        } else {
            if (isBengali) {
                sb.append("  পরিশোধিত / কোনো বাকি নেই  \n")
            } else {
                sb.append("  ALL DUES CLEARED (₹0.00)  \n")
            }
        }
        sb.append("================================\n")

        val interestSettings = StoreInfoManager.getKhataInterestSettings()
        val finalBal = netRemainingDue
        val dueDateMs = KhataInterestCalculator.calculateDueDateTimestamp(sale.datetime, null, interestSettings.gracePeriodDays)
        val dueDateStr = KhataInterestCalculator.formatDueDate(dueDateMs, interestSettings.gracePeriodDays, isBengali)

        if (netRemainingDue > 0) {
            sb.append(if (isBengali) "পরিশোধের শেষ তারিখ: $dueDateStr\n\n" else "Payment Due Date: $dueDateStr\n\n")
        }

        val merchantUpi = StoreInfoManager.merchantUpiId.trim().ifBlank { StoreInfoManager.DEFAULT_UPI_VPA }
        val payDueAmt = if (finalBal > 0.0) finalBal else if (sale.dueAmount > 0.0) sale.dueAmount else sale.finalAmount
        if (merchantUpi.isNotBlank()) {
            val dueAmtFormatted = if (isBengali) "₹${formatBengaliDigits("%.2f".format(Locale.US, payDueAmt))}" else "₹%.2f".format(Locale.US, payDueAmt)
            sb.append("            [ QR CODE ]         \n")
            sb.append("       Scan to Pay $dueAmtFormatted\n")
            sb.append("   UPI: $merchantUpi\n")
            val upiLink = StoreInfoManager.buildVpaOnlyUpiPayUrl(merchantUpi)
            if (upiLink.isNotBlank()) {
                sb.append("👉 ${if (isBengali) "সরাসরি পেমেন্ট লিংক" else "Direct Pay Link"}: $upiLink\n")
            }
            sb.append("\n")
        }

        if (netRemainingDue > 0) {
            val disc = KhataInterestCalculator.formatDisclaimer(
                settings = interestSettings,
                customer = null,
                isBengali = isBengali,
                dueDateMs = dueDateMs,
                dueAmount = finalBal
            )
            if (disc.isNotBlank()) {
                sb.append("⚠️ $disc\n")
                sb.append("--------------------------------\n")
            }
        }

        val customFooter = BengaliReceiptTranslator.getFooterGreeting(isBengali, StoreInfoManager.customFooterNote)
        if (customFooter.isNotBlank()) {
            sb.append("     $customFooter\n")
        }
        sb.append(if (isBengali) "     ধন্যবাদ! আবার আসবেন 🙏\n" else "     Thank You! Visit Again 🙏\n")
        sb.append("================================")

        return sb.toString()
    }

    fun generateCreditSaleWhatsApp(
        customerName: String,
        storeName: String,
        billTotal: Double,
        paidAmount: Double,
        creditAdded: Double,
        totalOutstandingBalance: Double,
        transactionDateMs: Long = System.currentTimeMillis(),
        merchantUpiId: String? = null,
        merchantPayeeName: String? = null,
        isBengali: Boolean = false,
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

        val cleanUpi = merchantUpiId?.trim().takeIf { !it.isNullOrBlank() } ?: StoreInfoManager.merchantUpiId.trim()
        val duePayAmount = if (totalOutstandingBalance > 0.0) totalOutstandingBalance else if (creditAdded > 0.0) creditAdded else billTotal

        val fallbackPayUrl = if (cleanKhata.isBlank() && cleanUpi.isNotBlank() && duePayAmount > 0.0) {
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

        val dueLine = if (totalOutstandingBalance > 0.0 && interestSettings.enabled) {
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
                        "📅 পরিশোধের শেষ তারিখ: $dueDateDisplay ($daysBn দিন পর মাসিক $rateDisplay বিলম্ব সুদ)"
                    } else {
                        "📅 পরিশোধের শেষ তারিখ: $dueDateDisplay (বিলম্বের পর মাসিক $rateDisplay সুদ)"
                    }
                } else {
                    if (effectiveGraceDays > 0) {
                        "📅 Due Date: $dueDateDisplay ($rateStr/mo interest after $effectiveGraceDays days)"
                    } else {
                        "📅 Due Date: $dueDateDisplay ($rateStr/mo late interest)"
                    }
                }
            } else {
                "📅 " + KhataInterestCalculator.formatDueDate(dueDateMs, effectiveGraceDays, isBengali)
            }
        } else ""

        return if (isBengali) {
            buildString {
                append("*$store*\n")
                append("নমস্কার *$cleanName* ($dateStr),\n\n")
                append("🧾 বিল: *₹$billStr* | জমা: *₹$paidStr*\n")
                append("➕ বাকি যোগ: *₹$creditStr*\n")
                append("📌 মোট বকেয়া: *₹$totalDueStr*\n")
                if (dueLine.isNotBlank()) {
                    append("$dueLine\n")
                }
                append("\n")
                if (cleanUpi.isNotBlank()) {
                    append("💳 *UPI ID:* `$cleanUpi`\n")
                }
                if (cleanKhata.isNotBlank()) {
                    append("🔗 *খাতা ও পেমেন্ট:* $cleanKhata\n")
                } else if (fallbackPayUrl.isNotBlank()) {
                    append("🔗 *অনলাইন পেমেন্ট:* $fallbackPayUrl\n")
                }
                append("\nধন্যবাদ! 🙏")
            }
        } else {
            buildString {
                append("*$store*\n")
                append("Hi *$cleanName* ($dateStr),\n\n")
                append("🧾 Bill: *Rs.$billStr* | Paid: *Rs.$paidStr*\n")
                append("➕ Credit Added: *Rs.$creditStr*\n")
                append("📌 Total Due: *Rs.$totalDueStr*\n")
                if (dueLine.isNotBlank()) {
                    append("$dueLine\n")
                }
                append("\n")
                if (cleanUpi.isNotBlank()) {
                    append("💳 *UPI ID:* `$cleanUpi`\n")
                }
                if (cleanKhata.isNotBlank()) {
                    append("🔗 *View Khata & Pay:* $cleanKhata\n")
                } else if (fallbackPayUrl.isNotBlank()) {
                    append("🔗 *Pay Online:* $fallbackPayUrl\n")
                }
                append("\nThank you! 🙏")
            }
        }
    }

    fun generatePaymentReminder(customer: Customer, isBengali: Boolean = false): String {
        val balanceNum = "%.2f".format(Locale.US, customer.balance).trimEnd('0').trimEnd('.')
        val balanceStr = if (isBengali) "₹${formatBengaliDigits(balanceNum)}" else "Rs.$balanceNum"
        val merchantUpi = StoreInfoManager.merchantUpiId.trim()
        val absBal = kotlin.math.abs(customer.balance)

        val khataUrl = customer.shareToken?.takeIf { it.isNotBlank() }?.let { StoreInfoManager.buildCustomerKhataUrl(it) } ?: ""

        val webPayUrl = if (khataUrl.isBlank() && merchantUpi.isNotBlank() && absBal > 0.0) {
            StoreInfoManager.buildHostedPayUrl(
                upiId = merchantUpi,
                payeeName = StoreInfoManager.merchantPayeeName,
                amount = absBal,
                note = "Due Payment",
                store = StoreInfoManager.storeName
            )
        } else ""

        val upiPart = buildString {
            if (merchantUpi.isNotBlank()) {
                append("\n\n💳 *UPI ID:* `$merchantUpi`")
            }
            if (khataUrl.isNotBlank()) {
                append("\n🔗 *${if (isBengali) "খাতা ও পেমেন্ট" else "View Khata & Pay"}:* $khataUrl")
            } else if (webPayUrl.isNotBlank()) {
                append("\n🌐 *${if (isBengali) "অনলাইন পেমেন্ট" else "Pay Online"}:* $webPayUrl")
            }
        }

        val interestSettings = StoreInfoManager.getKhataInterestSettings()
        val effectiveGraceDays = customer.customGracePeriodDays ?: interestSettings.gracePeriodDays
        val dueDateMs = KhataInterestCalculator.calculateDueDateTimestamp(System.currentTimeMillis(), null, effectiveGraceDays)
        val dueDateFormatted = KhataInterestCalculator.formatDueDate(dueDateMs, effectiveGraceDays, isBengali)
        val dueDateNote = if (absBal > 0.0) {
            "\n📅 *$dueDateFormatted*"
        } else ""

        val interestNote = if (absBal > 0.0 && interestSettings.enabled) {
            val disc = KhataInterestCalculator.formatDisclaimer(
                settings = interestSettings,
                customer = customer,
                isBengali = isBengali,
                dueDateMs = dueDateMs,
                dueAmount = absBal
            )
            if (disc.isNotBlank()) "\n\n⚠️ *$disc*" else ""
        } else ""

        return if (isBengali) {
            "প্রিয় ${customer.name},\n\n" +
                    "${StoreInfoManager.storeName}-এ আপনার বর্তমান বাকি বকেয়া *$balanceStr*।$dueDateNote\n\n" +
                    "অনুগ্রহ করে সুবিধামতো বকেয়া পরিশোধ করার অনুরোধ রইল।$upiPart$interestNote\n\n" +
                    "ধন্যবাদ! 🙏\n- ${StoreInfoManager.storeName}"
        } else {
            "Dear ${customer.name},\n\n" +
                    "Your current balance due at ${StoreInfoManager.storeName} is *$balanceStr*.$dueDateNote\n\n" +
                    "Kindly clear the pending balance at your earliest convenience.$upiPart$interestNote\n\n" +
                    "Thank you! 🙏\n- ${StoreInfoManager.storeName}"
        }
    }

    fun generateSharedKhataMessage(
        customerName: String,
        currentBalance: Double,
        shareUrl: String,
        isBengali: Boolean = false
    ): String {
        val storeName = StoreInfoManager.storeName.ifBlank { "Kali Mata Variety Store" }
        val balNum = "%.2f".format(Locale.US, currentBalance)
        val balStr = if (isBengali) "৳${formatBengaliDigits(balNum)}" else "₹$balNum"

        return if (isBengali) {
            "📋 *ডিজিটাল খাতা স্টেটমেন্ট লিঙ্ক*\n" +
                    "দোকান: *$storeName*\n" +
                    "গ্রাহক: *$customerName*\n" +
                    "বর্তমান মোট বকেয়া: *$balStr*\n\n" +
                    "👉 আপনার সম্পূর্ণ খাতা হিসাব দেখতে এবং অনলাইনে পেমেন্ট করতে নিচের লিঙ্কে ক্লিক করুন:\n" +
                    "$shareUrl\n\n" +
                    "ধন্যবাদ! 🙏"
        } else {
            "📋 *ONLINE KHATA STATEMENT LINK*\n" +
                    "Store: *$storeName*\n" +
                    "Customer: *$customerName*\n" +
                    "Current Due Balance: *$balStr*\n\n" +
                    "👉 View your complete ledger history & pay online via UPI:\n" +
                    "$shareUrl\n\n" +
                    "Thank you! 🙏"
        }
    }

    fun generateOverduePaymentReminder(
        customer: Customer,
        daysOverdue: Int,
        isOverLimit: Boolean = false,
        isBengali: Boolean = false
    ): String {
        val balanceNum = "%.2f".format(Locale.US, customer.balance)
        val balanceStr = if (isBengali) "৳${formatBengaliDigits(balanceNum)}" else "Rs.$balanceNum"
        val daysStr = if (isBengali) "${formatBengaliDigits(daysOverdue.toString())} দিন" else "$daysOverdue days"
        val merchantUpi = StoreInfoManager.merchantUpiId.trim()
        val absBal = kotlin.math.abs(customer.balance)

        val upiLink = if (merchantUpi.isNotBlank() && absBal > 0.0) {
            StoreInfoManager.buildBillCheckoutUpiPayUrl(
                upiId = merchantUpi,
                payeeName = StoreInfoManager.merchantPayeeName,
                amount = absBal,
                note = "Due Payment"
            )
        } else ""

        val webPayUrl = if (merchantUpi.isNotBlank() && absBal > 0.0) {
            StoreInfoManager.buildHostedPayUrl(
                upiId = merchantUpi,
                payeeName = StoreInfoManager.merchantPayeeName,
                amount = absBal,
                note = "Due Payment",
                store = StoreInfoManager.storeName
            )
        } else ""

        val upiPart = if (upiLink.isNotBlank()) {
            if (isBengali) {
                "\n\n💳 *ইউপিআই আইডি:* $merchantUpi (পরিমাণ: $balanceStr)\n🌐 *অনলাইনে পেমেন্ট ও রসিদ:* $webPayUrl\n⚡ *সরাসরি UPI অ্যাপে পেমেন্ট:* $upiLink"
            } else {
                "\n\n💳 *UPI ID:* $merchantUpi (Amount: $balanceStr)\n🌐 *Pay Online / Get Receipt:* $webPayUrl\n⚡ *Direct UPI App Link:* $upiLink"
            }
        } else ""

        return if (isBengali) {
            "⚠️ *জরুরি বকেয়া নোটিশ (Payment Reminder)* ⚠️\n\n" +
                    "প্রিয় ${customer.name},\n" +
                    "${StoreInfoManager.storeName}-এ আপনার মোট বাকি বকেয়া *$balanceStr* " +
                    (if (daysOverdue > 0) "বিগত *$daysStr* যাবৎ অপরিশোধিত রয়েছে।" else "বকেয়া রয়েছে।") + "\n" +
                    (if (isOverLimit) "⚠️ আপনার বর্তমান বাকি অনুমোদিত বাকী সীমা অতিক্রম করেছে।\n" else "") +
                    "\nব্যবসায়িক সুসম্পর্ক বজায় রাখতে এবং নতুন কেনাকাটা অব্যাহত রাখতে অনুগ্রহ করে অতি দ্রুত আপনার বকেয়া টাকা পরিশোধ করুন।$upiPart\n\n" +
                    "ধন্যবাদ! 🙏\n- ${StoreInfoManager.storeName}"
        } else {
            "⚠️ *PAYMENT REMINDER / OVERDUE NOTICE* ⚠️\n\n" +
                    "Dear ${customer.name},\n" +
                    "Your outstanding balance of *$balanceStr* at ${StoreInfoManager.storeName} has been pending for *$daysStr*.\n" +
                    (if (isOverLimit) "⚠️ Your balance has exceeded the approved credit limit.\n" else "") +
                    "\nTo maintain an uninterrupted credit account and continued purchases, please clear your outstanding dues at the earliest via Cash or UPI.$upiPart\n\n" +
                    "Thank you for your cooperation! 🙏\n- ${StoreInfoManager.storeName}"
        }
    }

    fun generateCustomerStatement(
        customerName: String,
        phone: String,
        currentBalance: Double,
        salesCount: Int,
        totalBilledValuation: Double,
        periodLabel: String = "All-Time",
        sales: List<SaleWithItems> = emptyList(),
        ledgerEntries: List<com.example.data.local.entities.LedgerEntry> = emptyList(),
        isBengali: Boolean = false
    ): String {
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val dateRaw = dateFormat.format(Date())
        val dateStr = if (isBengali) formatBengaliDigits(dateRaw) else dateRaw

        val totalNum = "%.2f".format(Locale.US, totalBilledValuation)
        val totalStr = if (isBengali) "৳${formatBengaliDigits(totalNum)}" else "₹$totalNum"

        val balNum = "%.2f".format(Locale.US, currentBalance)
        val balStr = if (isBengali) "৳${formatBengaliDigits(balNum)}" else "₹$balNum"

        val countStr = if (isBengali) "${formatBengaliDigits(salesCount.toString())}টি" else "$salesCount"
        val phoneStr = if (isBengali) formatBengaliDigits(phone) else phone

        val sb = StringBuilder()
        sb.append(if (isBengali) "📋 *গ্রাহক খাতা স্টেটমেন্ট*\n" else "📋 *CUSTOMER KHATA STATEMENT*\n")
        sb.append(StoreInfoManager.getFormattedStoreHeader(isBengali))
        sb.append(if (isBengali) "📅 সময়কাল (Period): *$periodLabel*\n" else "📅 Statement Period: *$periodLabel*\n")
        sb.append(if (isBengali) "🕒 প্রস্তুতের তারিখ: $dateStr\n" else "🕒 Generated On: $dateStr\n")
        sb.append("--------------------------------\n")
        sb.append(if (isBengali) "গ্রাহক: *$customerName*\n" else "Customer: *$customerName*\n")
        if (phoneStr.isNotBlank()) {
            sb.append(if (isBengali) "ফোন: $phoneStr\n" else "Phone: $phoneStr\n")
        }
        sb.append("--------------------------------\n")

        // Build itemized timeline
        val transactions = mutableListOf<StatementTransactionItem>()

        // 1. Sales
        sales.forEach { sWithItems ->
            val s = sWithItems.sale
            val dueAdded = if (s.paymentMode.equals("KHATA", ignoreCase = true) || s.paymentMode.equals("CREDIT", ignoreCase = true)) {
                s.dueAmount.coerceAtLeast(0.0).ifZeroThen(s.finalAmount - s.receivedAmount).coerceAtLeast(0.0)
            } else {
                s.dueAmount.coerceAtLeast(0.0)
            }
            transactions.add(
                StatementTransactionItem(
                    datetime = s.datetime,
                    type = "SALE",
                    refNo = "Bill #${s.id.takeLast(6)}",
                    totalAmount = s.finalAmount,
                    paidAmount = s.receivedAmount,
                    dueImpact = dueAdded,
                    note = s.paymentMode
                )
            )
        }

        // 2. Ledger Payments (deduplicate sales already included)
        val linkedSaleIds = sales.map { it.sale.id }.toSet()
        ledgerEntries.forEach { entry ->
            val isPayment = entry.type.contains("PAYMENT") || entry.type == "PAYMENT_RECEIVED"
            val isCredit = entry.type.contains("CREDIT") || entry.type == "SALE_CREDIT"

            if (entry.referenceId != null && linkedSaleIds.contains(entry.referenceId)) {
                // Already represented in sales
                return@forEach
            }

            if (isPayment) {
                transactions.add(
                    StatementTransactionItem(
                        datetime = entry.datetime,
                        type = "PAYMENT",
                        refNo = "Payment",
                        totalAmount = entry.amount,
                        paidAmount = entry.amount,
                        dueImpact = -entry.amount,
                        note = entry.note
                    )
                )
            } else if (isCredit) {
                transactions.add(
                    StatementTransactionItem(
                        datetime = entry.datetime,
                        type = "CREDIT_ENTRY",
                        refNo = "Khata Credit",
                        totalAmount = entry.amount,
                        paidAmount = 0.0,
                        dueImpact = entry.amount,
                        note = entry.note
                    )
                )
            }
        }

        transactions.sortBy { it.datetime }

        if (transactions.isNotEmpty()) {
            sb.append(if (isBengali) "📜 *লেনদেনের বিবরণী (Itemized History):*\n" else "📜 *ITEMIZED TRANSACTIONS:*\n")
            var runningDue = 0.0
            val itemDateFormat = SimpleDateFormat("dd/MM/yy", Locale.getDefault())

            transactions.forEachIndexed { idx, item ->
                runningDue += item.dueImpact
                val itemDateStr = itemDateFormat.format(Date(item.datetime))
                val dtDisplay = if (isBengali) formatBengaliDigits(itemDateStr) else itemDateStr
                val idxDisplay = if (isBengali) formatBengaliDigits((idx + 1).toString()) else "${idx + 1}"

                val totAmt = "%.2f".format(Locale.US, item.totalAmount)
                val totAmtStr = if (isBengali) "৳${formatBengaliDigits(totAmt)}" else "₹$totAmt"
                val paidAmt = "%.2f".format(Locale.US, item.paidAmount)
                val paidAmtStr = if (isBengali) "৳${formatBengaliDigits(paidAmt)}" else "₹$paidAmt"
                val dueAmt = "%.2f".format(Locale.US, kotlin.math.abs(item.dueImpact))
                val dueAmtStr = if (isBengali) "৳${formatBengaliDigits(dueAmt)}" else "₹$dueAmt"
                val runDueStr = if (isBengali) "৳${formatBengaliDigits("%.2f".format(Locale.US, runningDue))}" else "₹%.2f".format(Locale.US, runningDue)

                when (item.type) {
                    "SALE" -> {
                        sb.append("$idxDisplay. 🗓️ $dtDisplay • ${item.refNo}\n")
                        sb.append(if (isBengali) "   •মোট বিল: $totAmtStr (পরিশোধ: $paidAmtStr)\n" else "   •Bill Total: $totAmtStr (Paid: $paidAmtStr)\n")
                        if (item.dueImpact > 0) {
                            sb.append(if (isBengali) "   •বাকি যোগ: +$dueAmtStr | ব্যালেন্স: $runDueStr\n" else "   •Due Added: +$dueAmtStr | Balance: $runDueStr\n")
                        } else {
                            sb.append(if (isBengali) "   •সম্পূর্ণ পরিশোধিত | ব্যালেন্স: $runDueStr\n" else "   •Fully Paid | Balance: $runDueStr\n")
                        }
                    }
                    "PAYMENT" -> {
                        sb.append("$idxDisplay. 🗓️ $dtDisplay • ${if (isBengali) "পেমেন্ট জমা (পরিশোধ)" else "Payment Received"}\n")
                        val cleanN = cleanNotes(item.note)
                        val notePart = if (cleanN.isNotBlank()) " ($cleanN)" else ""
                        sb.append(if (isBengali) "   •জমা: -$paidAmtStr$notePart | বাকি ব্যালেন্স: $runDueStr\n" else "   •Received: -$paidAmtStr$notePart | Balance: $runDueStr\n")
                    }
                    else -> {
                        sb.append("$idxDisplay. 🗓️ $dtDisplay • ${item.refNo}\n")
                        sb.append(if (isBengali) "   •বাকি যোগ: +$dueAmtStr | ব্যালেন্স: $runDueStr\n" else "   •Due Added: +$dueAmtStr | Balance: $runDueStr\n")
                    }
                }
            }
            sb.append("--------------------------------\n")
        }

        // Summary Block
        sb.append(if (isBengali) "📊 *স্টেটমেন্ট সারাংশ ($periodLabel):*\n" else "📊 *STATEMENT SUMMARY ($periodLabel):*\n")
        sb.append(if (isBengali) "• মোট কেনাকাটা/বিল: $countStr ($totalStr)\n" else "• Total Purchases/Bills: $countStr ($totalStr)\n")
        sb.append("--------------------------------\n")
        sb.append(if (isBengali) "*বর্তমান বাকি বকেয়া: $balStr*\n" else "*CURRENT DUE BALANCE: $balStr*\n")
        sb.append("--------------------------------\n")
        sb.append(
            if (isBengali) "অনুগ্রহ করে শীঘ্রই ক্যাশ বা ইউপিআই-এর মাধ্যমে আপনার বকেয়া টাকা পরিশোধ করুন।\nআমাদের সাথে কেনাকাটার জন্য ধন্যবাদ! 🙏"
            else "Kindly clear your dues at your earliest convenience via Cash or UPI.\nThank you for shopping with us! 🙏"
        )

        return sb.toString()
    }

    fun generateSupplierStatement(
        supplierName: String,
        phone: String,
        currentBalance: Double,
        purchaseCount: Int,
        totalPurchasedValuation: Double,
        periodLabel: String = "All-Time",
        purchases: List<com.example.data.local.dao.PurchaseWithItems> = emptyList(),
        ledgerEntries: List<com.example.data.local.entities.LedgerEntry> = emptyList(),
        isBengali: Boolean = false
    ): String {
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val dateRaw = dateFormat.format(Date())
        val dateStr = if (isBengali) formatBengaliDigits(dateRaw) else dateRaw

        val totalNum = "%.2f".format(Locale.US, totalPurchasedValuation)
        val totalStr = if (isBengali) "৳${formatBengaliDigits(totalNum)}" else "₹$totalNum"

        val balNum = "%.2f".format(Locale.US, currentBalance)
        val balStr = if (isBengali) "৳${formatBengaliDigits(balNum)}" else "₹$balNum"

        val countStr = if (isBengali) "${formatBengaliDigits(purchaseCount.toString())}টি" else "$purchaseCount"
        val phoneStr = if (isBengali) formatBengaliDigits(phone) else phone

        val sb = StringBuilder()
        sb.append(if (isBengali) "🤝 *সাপ্লায়ার খাতা স্টেটমেন্ট*\n" else "🤝 *SUPPLIER KHATA STATEMENT*\n")
        sb.append(StoreInfoManager.getFormattedStoreHeader(isBengali))
        sb.append(if (isBengali) "📅 সময়কাল (Period): *$periodLabel*\n" else "📅 Statement Period: *$periodLabel*\n")
        sb.append(if (isBengali) "🕒 প্রস্তুতের তারিখ: $dateStr\n" else "🕒 Generated On: $dateStr\n")
        sb.append("--------------------------------\n")
        sb.append(if (isBengali) "সাপ্লায়ার: *$supplierName*\n" else "Supplier: *$supplierName*\n")
        if (phoneStr.isNotBlank()) {
            sb.append(if (isBengali) "ফোন: $phoneStr\n" else "Phone: $phoneStr\n")
        }
        sb.append("--------------------------------\n")

        // Build itemized timeline
        val transactions = mutableListOf<StatementTransactionItem>()

        // 1. Purchases
        purchases.forEach { pWithItems ->
            val p = pWithItems.purchase
            val duePortion = if (p.dueAmount > 0) p.dueAmount else (p.totalAmount - p.amountPaid).coerceAtLeast(0.0)
            transactions.add(
                StatementTransactionItem(
                    datetime = p.datetime,
                    type = "PURCHASE",
                    refNo = "Bill #${p.id.takeLast(6)}",
                    totalAmount = p.totalAmount,
                    paidAmount = p.amountPaid,
                    dueImpact = duePortion,
                    note = p.notes
                )
            )
        }

        // 2. Ledger entries (Payment Made or Standalone Credit)
        val linkedPurchaseIds = purchases.map { it.purchase.id }.toSet()
        ledgerEntries.forEach { entry ->
            val isPayment = entry.type.contains("PAYMENT") || entry.type == "PAYMENT_MADE"
            val isCredit = entry.type.contains("CREDIT") || entry.type == "PURCHASE_CREDIT"

            if (entry.referenceId != null && linkedPurchaseIds.contains(entry.referenceId)) {
                return@forEach
            }

            if (isPayment) {
                transactions.add(
                    StatementTransactionItem(
                        datetime = entry.datetime,
                        type = "PAYMENT",
                        refNo = "Payment",
                        totalAmount = entry.amount,
                        paidAmount = entry.amount,
                        dueImpact = -entry.amount,
                        note = entry.note
                    )
                )
            } else if (isCredit) {
                transactions.add(
                    StatementTransactionItem(
                        datetime = entry.datetime,
                        type = "PURCHASE_CREDIT",
                        refNo = "Payable Due",
                        totalAmount = entry.amount,
                        paidAmount = 0.0,
                        dueImpact = entry.amount,
                        note = entry.note
                    )
                )
            }
        }

        transactions.sortBy { it.datetime }

        if (transactions.isNotEmpty()) {
            sb.append(if (isBengali) "📜 *চালান ও পেমেন্টের বিবরণী (Itemized History):*\n" else "📜 *ITEMIZED BILLS & PAYMENTS:*\n")
            var runningDues = 0.0
            val itemDateFormat = SimpleDateFormat("dd/MM/yy", Locale.getDefault())

            transactions.forEachIndexed { idx, item ->
                runningDues += item.dueImpact
                val itemDateStr = itemDateFormat.format(Date(item.datetime))
                val dtDisplay = if (isBengali) formatBengaliDigits(itemDateStr) else itemDateStr
                val idxDisplay = if (isBengali) formatBengaliDigits((idx + 1).toString()) else "${idx + 1}"

                val totAmt = "%.2f".format(Locale.US, item.totalAmount)
                val totAmtStr = if (isBengali) "৳${formatBengaliDigits(totAmt)}" else "₹$totAmt"
                val paidAmt = "%.2f".format(Locale.US, item.paidAmount)
                val paidAmtStr = if (isBengali) "৳${formatBengaliDigits(paidAmt)}" else "₹$paidAmt"
                val dueAmt = "%.2f".format(Locale.US, kotlin.math.abs(item.dueImpact))
                val dueAmtStr = if (isBengali) "৳${formatBengaliDigits(dueAmt)}" else "₹$dueAmt"
                val runDueStr = if (isBengali) "৳${formatBengaliDigits("%.2f".format(Locale.US, runningDues))}" else "₹%.2f".format(Locale.US, runningDues)

                when (item.type) {
                    "PURCHASE" -> {
                        sb.append("$idxDisplay. 🗓️ $dtDisplay • ${item.refNo}\n")
                        sb.append(if (isBengali) "   •চালান মোট: $totAmtStr (পরিশোধ: $paidAmtStr)\n" else "   •Bill Total: $totAmtStr (Paid: $paidAmtStr)\n")
                        if (item.dueImpact > 0) {
                            sb.append(if (isBengali) "   •বকেয়া যোগ: +$dueAmtStr | মোট দেয়: $runDueStr\n" else "   •Dues Added: +$dueAmtStr | Running Dues: $runDueStr\n")
                        } else {
                            sb.append(if (isBengali) "   •সম্পূর্ণ নগদ পরিশোধ | মোট দেয়: $runDueStr\n" else "   •Fully Paid | Running Dues: $runDueStr\n")
                        }
                    }
                    "PAYMENT" -> {
                        sb.append("$idxDisplay. 🗓️ $dtDisplay • ${if (isBengali) "মহাজন পেমেন্ট প্রদান" else "Payment Made to Supplier"}\n")
                        val cleanN = cleanNotes(item.note)
                        val notePart = if (cleanN.isNotBlank()) " ($cleanN)" else ""
                        sb.append(if (isBengali) "   •পরিশোধ: -$paidAmtStr$notePart | মোট দেয়: $runDueStr\n" else "   •Paid: -$paidAmtStr$notePart | Running Dues: $runDueStr\n")
                    }
                    else -> {
                        sb.append("$idxDisplay. 🗓️ $dtDisplay • ${item.refNo}\n")
                        sb.append(if (isBengali) "   •বকেয়া যোগ: +$dueAmtStr | মোট দেয়: $runDueStr\n" else "   •Dues Added: +$dueAmtStr | Running Dues: $runDueStr\n")
                    }
                }
            }
            sb.append("--------------------------------\n")
        }

        // Summary Block
        sb.append(if (isBengali) "📊 *স্টেটমেন্ট সারাংশ ($periodLabel):*\n" else "📊 *STATEMENT SUMMARY ($periodLabel):*\n")
        sb.append(if (isBengali) "• মোট পারচেজ বিল: $countStr ($totalStr)\n" else "• Total Purchase Bills: $countStr ($totalStr)\n")
        sb.append("--------------------------------\n")
        sb.append(if (isBengali) "*বর্তমান দেয় বকেয়া: $balStr*\n" else "*CURRENT PAYABLE DUES: $balStr*\n")
        sb.append("--------------------------------\n")
        sb.append(
            if (isBengali) "ব্যবসায়িক সহযোগিতার জন্য আপনাকে ধন্যবাদ! 🙏"
            else "Thank you for your business & support! 🙏"
        )

        return sb.toString()
    }

    private fun Double.ifZeroThen(fallback: Double): Double = if (this == 0.0) fallback else this

    private fun cleanNotes(note: String?): String {
        if (note.isNullOrBlank()) return ""
        return note.replace(Regex("\\[PHOTO:[^\\]]+\\]"), "").trim()
    }

    data class StatementTransactionItem(
        val datetime: Long,
        val type: String,
        val refNo: String,
        val totalAmount: Double,
        val paidAmount: Double,
        val dueImpact: Double,
        val note: String? = null
    )

    fun generatePurchaseOrder(
        products: List<Product>,
        supplierName: String? = null,
        isBengali: Boolean = false,
        customQuantities: Map<String, Double>? = null
    ): String {
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val dateRaw = dateFormat.format(Date())
        val dateStr = if (isBengali) formatBengaliDigits(dateRaw) else dateRaw

        val sb = StringBuilder()
        sb.append(if (isBengali) "📦 *পুনরায় অর্ডার / পারচেজ অর্ডার*\n" else "📦 *REORDER / PURCHASE ORDER*\n")
        val storeHeader = if (StoreInfoManager.storeAddress.isNotBlank()) {
            "🏪 ${StoreInfoManager.storeName}, ${StoreInfoManager.storeAddress}\n"
        } else {
            "🏪 ${StoreInfoManager.storeName}\n"
        }
        sb.append(storeHeader)
        sb.append(if (isBengali) "📅 তারিখ: $dateStr\n" else "📅 Date: $dateStr\n")
        if (!supplierName.isNullOrBlank()) {
            sb.append(if (isBengali) "👤 সাপ্লায়ার: $supplierName\n" else "👤 Supplier: $supplierName\n")
        }
        sb.append("--------------------------------\n")

        var totalEstVal = 0.0
        products.forEachIndexed { index, p ->
            val qtyToOrder = customQuantities?.get(p.id) ?: (p.lowStockThreshold * 2.0 - p.currentStock).coerceAtLeast(p.lowStockThreshold)
            val name = if (isBengali) p.nameBn.ifBlank { p.getDisplayName() } else p.getDisplayName()
            val stockUnit = p.unitType
            val idxStr = if (isBengali) formatBengaliDigits((index + 1).toString()) else "${index + 1}"
            val orderStockStr = formatQtyWithUnit(qtyToOrder, stockUnit, isBengali)
            val itemEstCost = if (p.unitType.equals("gram", ignoreCase = true)) (qtyToOrder / 1000.0) * p.costPrice else qtyToOrder * p.costPrice
            totalEstVal += itemEstCost

            sb.append("$idxStr. *$name*\n")
            sb.append(if (isBengali) "   •অর্ডার: *$orderStockStr*" else "   •Order: *$orderStockStr*")
            if (p.costPrice > 0) {
                val costFmt = "₹%.2f".format(Locale.US, itemEstCost)
                val costStr = if (isBengali) formatBengaliDigits(costFmt) else costFmt
                sb.append(if (isBengali) " (আনুমানিক: $costStr)" else " (Est: $costStr)")
            }
            sb.append("\n")
        }

        val countStr = if (isBengali) formatBengaliDigits(products.size.toString()) else "${products.size}"
        sb.append("--------------------------------\n")
        sb.append(if (isBengali) "মোট পুনরায় অর্ডারের সংখ্যা: $countStr\n" else "Total Items to Reorder: $countStr\n")
        if (totalEstVal > 0) {
            val totalFmt = "₹%.2f".format(Locale.US, totalEstVal)
            val totalStr = if (isBengali) formatBengaliDigits(totalFmt) else totalFmt
            sb.append(if (isBengali) "মোট আনুমানিক খরচ: $totalStr\n" else "Total Estimated Cost: $totalStr\n")
        }
        sb.append("--------------------------------")

        return sb.toString()
    }

    fun generateSalarySlipText(
        payment: com.example.data.local.entities.EmployeeSalaryPayment,
        employee: com.example.data.local.entities.Employee? = null
    ): String {
        val isBengali = LanguageManager.isBengali
        val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        val dateStr = sdf.format(Date(payment.paymentDate))
        val storeName = StoreInfoManager.storeName.ifBlank { "Kali Mata Variety Store" }

        val sb = StringBuilder()
        sb.append(if (isBengali) "🧾 *কর্মচারী বেতন রশিদ / PAYSLIP*\n" else "🧾 *EMPLOYEE PAYSLIP*\n")
        sb.append("*$storeName*\n")
        if (StoreInfoManager.address.isNotBlank()) sb.append("${StoreInfoManager.address}\n")
        if (StoreInfoManager.phone.isNotBlank()) sb.append("Ph: ${StoreInfoManager.phone}\n")
        sb.append("--------------------------------\n")
        sb.append(if (isBengali) "কর্মচারীর নাম: *${payment.employeeName}*\n" else "Employee: *${payment.employeeName}*\n")
        if (employee != null && employee.designation.isNotBlank()) {
            sb.append(if (isBengali) "পদবী: ${employee.designation}\n" else "Designation: ${employee.designation}\n")
        }
        sb.append(if (isBengali) "বেতন মাস: *${payment.monthYear}*\n" else "Salary Month: *${payment.monthYear}*\n")
        sb.append(if (isBengali) "প্রদানের তারিখ: $dateStr\n" else "Payment Date: $dateStr\n")
        sb.append("--------------------------------\n")
        sb.append(if (isBengali) "মূল বেতন: ₹${"%.2f".format(payment.baseSalary)}\n" else "Base Salary: ₹${"%.2f".format(payment.baseSalary)}\n")
        if (payment.presentDays > 0 || payment.halfDays > 0 || payment.absentDays > 0) {
            sb.append(if (isBengali) "উপস্থিত দিন: ${payment.presentDays} | হাফ-ডে: ${payment.halfDays} | অনুপস্থিত: ${payment.absentDays}\n" 
                      else "Present: ${payment.presentDays} | Half Days: ${payment.halfDays} | Absent: ${payment.absentDays}\n")
        }
        if (payment.overtimeHours > 0) {
            sb.append(if (isBengali) "ওভারটাইম: ${payment.overtimeHours} ঘণ্টা (+₹${"%.2f".format(payment.overtimePay)})\n" 
                      else "Overtime: ${payment.overtimeHours} hrs (+₹${"%.2f".format(payment.overtimePay)})\n")
        }
        if (payment.bonus > 0) {
            sb.append(if (isBengali) "বোনাস / ইনসেন্টিভ: +₹${"%.2f".format(payment.bonus)}\n" else "Bonus / Incentive: +₹${"%.2f".format(payment.bonus)}\n")
        }
        if (payment.advanceDeduction > 0) {
            sb.append(if (isBengali) "অগ্রিম কর্তন: -₹${"%.2f".format(payment.advanceDeduction)}\n" else "Advance Deduction: -₹${"%.2f".format(payment.advanceDeduction)}\n")
        }
        if (payment.otherDeductions > 0) {
            sb.append(if (isBengali) "অন্যান্য কর্তন: -₹${"%.2f".format(payment.otherDeductions)}\n" else "Other Deductions: -₹${"%.2f".format(payment.otherDeductions)}\n")
        }
        sb.append("--------------------------------\n")
        sb.append(if (isBengali) "✅ *মোট প্রদত্ত বেতন: ₹${"%.2f".format(payment.netSalaryPaid)}*\n" 
                  else "✅ *NET SALARY PAID: ₹${"%.2f".format(payment.netSalaryPaid)}*\n")
        sb.append(if (isBengali) "পেমেন্ট মাধ্যম: ${payment.paymentMode}\n" else "Payment Mode: ${payment.paymentMode}\n")
        if (payment.notes.isNotBlank()) {
            sb.append(if (isBengali) "মন্তব্য: ${payment.notes}\n" else "Notes: ${payment.notes}\n")
        }
        sb.append("--------------------------------\n")
        sb.append(if (isBengali) "ধন্যবাদ! শুভকামনা রইল।" else "Thank you for your hard work!")
        return sb.toString()
    }

    fun sendWhatsAppMessage(context: Context, phone: String?, message: String) {
        try {
            val cleanPhone = phone?.replace(Regex("[^0-9]"), "") ?: ""
            val formattedPhone = if (cleanPhone.length == 10) "91$cleanPhone" else cleanPhone

            val url = if (formattedPhone.isNotBlank()) {
                "https://api.whatsapp.com/send?phone=$formattedPhone&text=${Uri.encode(message)}"
            } else {
                "https://api.whatsapp.com/send?text=${Uri.encode(message)}"
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(url)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, message)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share Bill via"))
        }
    }
}

