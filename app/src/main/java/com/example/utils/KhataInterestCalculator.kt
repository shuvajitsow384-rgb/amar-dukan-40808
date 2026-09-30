package com.example.utils

import com.example.data.local.entities.Customer
import com.example.data.local.entities.LedgerEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow
import kotlin.math.round

data class KhataInterestSettings(
    val enabled: Boolean = false,
    val monthlyRatePercent: Double = 2.0, // e.g., 2.0% per month
    val gracePeriodDays: Int = 45,        // default 45 days
    val calculationMode: String = "SIMPLE", // "SIMPLE" or "COMPOUNDING"
    val applyRetroactively: Boolean = false, // If false, only sales created after activationDate accrue interest
    val activationDate: Long = 0L,
    val disclaimerText: String = "Late payment interest of {rate}% per month is applicable on unpaid credit balances after {grace_days} days of grace period."
) {
    fun getEffectiveRate(customer: Customer?): Double {
        if (customer?.interestExempt == true) return 0.0
        return (customer?.customInterestRate ?: monthlyRatePercent).coerceAtLeast(0.0)
    }

    fun getEffectiveGraceDays(customer: Customer?): Int {
        return (customer?.customGracePeriodDays ?: gracePeriodDays).coerceAtLeast(0)
    }

    fun isCustomerExempt(customer: Customer?): Boolean {
        return customer?.interestExempt == true
    }

    fun isInterestActiveFor(customer: Customer?): Boolean {
        if (customer?.interestExempt == true) return false
        return enabled || (customer?.customInterestRate != null && customer.customInterestRate > 0.0)
    }
}

data class OverdueCreditItem(
    val ledgerEntryId: String,
    val entryDate: Long,
    val dueDate: Long,
    val originalAmount: Double,
    val unpaidPrincipal: Double,
    val daysOverdue: Int,
    val accruedInterest: Double,
    val note: String? = null
)

data class AgingBuckets(
    val currentAmount: Double = 0.0,       // 0 - 30 days
    val gracePeriodAmount: Double = 0.0,   // 31 - graceDays
    val overdue1To30Days: Double = 0.0,    // 1 - 30 days past due
    val overdue31To60Days: Double = 0.0,   // 31 - 60 days past due
    val overdue60PlusDays: Double = 0.0    // > 60 days past due
)

data class CustomerInterestBreakdown(
    val customerId: String,
    val customerName: String,
    val isExempt: Boolean,
    val effectiveMonthlyRate: Double,
    val effectiveGraceDays: Int,
    val totalLedgerBalance: Double,             // Net ledger balance currently on record
    val principalDue: Double,                   // Unpaid principal debt
    val totalAccruedInterest: Double,           // Newly accrued unposted interest
    val totalPostedInterest: Double,            // Interest already booked as ledger entries
    val totalOutstandingWithAccrued: Double,    // Total due (principal + posted interest + accrued unposted interest)
    val overdueEntriesCount: Int,
    val maxDaysOverdue: Int,
    val overdueItems: List<OverdueCreditItem>,
    val agingBuckets: AgingBuckets
)

object KhataInterestCalculator {

    private const val MS_PER_DAY = 86_400_000L

    /**
     * Replays the customer ledger history using Strict FIFO (First-In, First-Out) payment allocation.
     * Payments reduce the oldest unpaid debit entries first.
     * For any debit entry that remains unpaid past its due date (sale date + grace period),
     * interest is calculated according to the configured settings (Simple or Compounding).
     */
    fun calculateCustomerInterest(
        customer: Customer,
        allLedgerEntries: List<LedgerEntry>,
        settings: KhataInterestSettings,
        calculationTimeMs: Long = System.currentTimeMillis()
    ): CustomerInterestBreakdown {
        val custEntries = allLedgerEntries.filter {
            it.partyId == customer.id && (it.partyType.equals("CUSTOMER", ignoreCase = true) || it.partyType.isBlank())
        }

        val isExempt = customer.interestExempt
        val effectiveRate = settings.getEffectiveRate(customer)
        val effectiveGraceDays = settings.getEffectiveGraceDays(customer)

        // Separate entries
        val debitEntries = mutableListOf<LedgerEntry>()
        val paymentEntries = mutableListOf<LedgerEntry>()
        val postedInterestEntries = mutableListOf<LedgerEntry>()

        for (entry in custEntries) {
            val t = entry.type.uppercase()
            when {
                t in listOf("INTEREST", "INTEREST_ACCRUED", "INTEREST_CHARGED") -> {
                    postedInterestEntries.add(entry)
                }
                t in listOf("SALE_CREDIT", "CREDIT_GIVEN", "CREDIT", "REPLACEMENT_DUE", "DUE", "OPENING_BALANCE", "INITIAL_DUE", "OPENING_DUE", "OPENING_CREDIT") ||
                (entry.note?.contains("credit", ignoreCase = true) == true || entry.note?.contains("due", ignoreCase = true) == true || entry.note?.contains("opening", ignoreCase = true) == true) -> {
                    debitEntries.add(entry)
                }
                t in listOf("PAYMENT_RECEIVED", "PAYMENT", "RETURN_REFUND", "CREDIT_REDUCED", "REFUND") -> {
                    paymentEntries.add(entry)
                }
                else -> {
                    paymentEntries.add(entry)
                }
            }
        }

        // Sort chronologically ascending
        debitEntries.sortBy { it.datetime }
        paymentEntries.sortBy { it.datetime }

        val totalPayments = paymentEntries.sumOf { it.amount }
        val totalPostedInterest = postedInterestEntries.sumOf { it.amount }

        var remainingPaymentPool = totalPayments
        val overdueItems = mutableListOf<OverdueCreditItem>()

        var principalDue = 0.0
        var totalAccruedInterest = 0.0
        var maxDaysOverdue = 0
        var overdueCount = 0

        var currentAmt = 0.0
        var graceAmt = 0.0
        var overdue1to30Amt = 0.0
        var overdue31to60Amt = 0.0
        var overdue60PlusAmt = 0.0

        for (debit in debitEntries) {
            val originalAmt = debit.amount
            if (originalAmt <= 0.0) continue

            val unpaidAmt: Double
            if (remainingPaymentPool >= originalAmt) {
                remainingPaymentPool -= originalAmt
                unpaidAmt = 0.0
            } else if (remainingPaymentPool > 0.0) {
                unpaidAmt = originalAmt - remainingPaymentPool
                remainingPaymentPool = 0.0
            } else {
                unpaidAmt = originalAmt
            }

            if (unpaidAmt <= 0.001) continue

            principalDue += unpaidAmt

            // Calculate aging and due date
            val entryDate = debit.datetime
            val dueDate = debit.dueDate ?: (entryDate + (effectiveGraceDays * MS_PER_DAY))
            val daysSinceEntry = ((calculationTimeMs - entryDate) / MS_PER_DAY).toInt().coerceAtLeast(0)
            val daysPastDue = if (calculationTimeMs > dueDate) {
                ((calculationTimeMs - dueDate) / MS_PER_DAY).toInt().coerceAtLeast(1)
            } else {
                0
            }

            // Aging bucket distribution
            when {
                daysSinceEntry <= 30 -> currentAmt += unpaidAmt
                daysSinceEntry <= effectiveGraceDays -> graceAmt += unpaidAmt
                daysPastDue in 1..30 -> overdue1to30Amt += unpaidAmt
                daysPastDue in 31..60 -> overdue31to60Amt += unpaidAmt
                else -> overdue60PlusAmt += unpaidAmt
            }

            // Interest Accrual Logic
            var accruedForThisItem = 0.0
            val isInterestActive = (settings.enabled || (customer.customInterestRate != null && customer.customInterestRate > 0.0)) && !isExempt && effectiveRate > 0.0
            if (isInterestActive && daysPastDue > 0) {
                val isEligible = if (settings.applyRetroactively || customer.customInterestRate != null) {
                    true
                } else {
                    settings.activationDate <= 0L || entryDate >= settings.activationDate
                }

                if (isEligible) {
                    val monthsOverdue = daysPastDue / 30.0
                    val computedInterest = if (settings.calculationMode.equals("COMPOUNDING", ignoreCase = true)) {
                        // Monthly compounding: P * ((1 + r)^months - 1)
                        unpaidAmt * ((1.0 + (effectiveRate / 100.0)).pow(monthsOverdue) - 1.0)
                    } else {
                        // Simple interest: P * (r / 100) * (days / 30)
                        unpaidAmt * (effectiveRate / 100.0) * monthsOverdue
                    }
                    accruedForThisItem = (round(computedInterest * 100.0) / 100.0).coerceAtLeast(0.0)
                    totalAccruedInterest += accruedForThisItem
                    overdueCount++
                    if (daysPastDue > maxDaysOverdue) {
                        maxDaysOverdue = daysPastDue
                    }
                }
            }

            if (daysPastDue > 0) {
                overdueItems.add(
                    OverdueCreditItem(
                        ledgerEntryId = debit.id,
                        entryDate = entryDate,
                        dueDate = dueDate,
                        originalAmount = originalAmt,
                        unpaidPrincipal = unpaidAmt,
                        daysOverdue = daysPastDue,
                        accruedInterest = accruedForThisItem,
                        note = debit.note
                    )
                )
            }
        }

        // Ledger balance based on actual ledger items
        val rawLedgerBalance = LedgerCalculator.calculateCustomerBalance(customer.id, custEntries)
        val finalPrincipalDue = round(principalDue * 100.0) / 100.0
        val finalAccruedInterest = round(totalAccruedInterest * 100.0) / 100.0
        val finalTotalOutstanding = round((rawLedgerBalance + finalAccruedInterest) * 100.0) / 100.0

        return CustomerInterestBreakdown(
            customerId = customer.id,
            customerName = customer.name,
            isExempt = isExempt,
            effectiveMonthlyRate = effectiveRate,
            effectiveGraceDays = effectiveGraceDays,
            totalLedgerBalance = rawLedgerBalance,
            principalDue = finalPrincipalDue,
            totalAccruedInterest = finalAccruedInterest,
            totalPostedInterest = totalPostedInterest,
            totalOutstandingWithAccrued = finalTotalOutstanding,
            overdueEntriesCount = overdueCount,
            maxDaysOverdue = maxDaysOverdue,
            overdueItems = overdueItems,
            agingBuckets = AgingBuckets(
                currentAmount = round(currentAmt * 100.0) / 100.0,
                gracePeriodAmount = round(graceAmt * 100.0) / 100.0,
                overdue1To30Days = round(overdue1to30Amt * 100.0) / 100.0,
                overdue31To60Days = round(overdue31to60Amt * 100.0) / 100.0,
                overdue60PlusDays = round(overdue60PlusAmt * 100.0) / 100.0
            )
        )
    }

    /**
     * Calculates the exact due date timestamp for a credit sale based on grace days.
     */
    fun calculateDueDateTimestamp(
        transactionDateMs: Long,
        customDueDateMs: Long? = null,
        graceDays: Int = StoreInfoManager.interestGracePeriodDays
    ): Long {
        if (customDueDateMs != null && customDueDateMs > 0) return customDueDateMs
        val effectiveGrace = graceDays.coerceAtLeast(1)
        return transactionDateMs + (effectiveGrace.toLong() * 86_400_000L)
    }

    /**
     * Formats the Due Date string for display on receipts and messages.
     */
    fun formatDueDate(
        dueDateMs: Long,
        graceDays: Int = StoreInfoManager.interestGracePeriodDays,
        isBengali: Boolean = false
    ): String {
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.US)
        val dateRaw = sdf.format(Date(dueDateMs))
        val bnDigits = charArrayOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')
        val dateStr = if (isBengali) {
            val sb = StringBuilder()
            for (ch in dateRaw) {
                if (ch in '0'..'9') sb.append(bnDigits[ch - '0']) else sb.append(ch)
            }
            sb.toString()
        } else dateRaw

        val daysStr = if (isBengali) {
            val sb = StringBuilder()
            val raw = graceDays.toString()
            for (ch in raw) {
                if (ch in '0'..'9') sb.append(bnDigits[ch - '0']) else sb.append(ch)
            }
            sb.toString()
        } else graceDays.toString()

        return if (isBengali) {
            if (graceDays > 0) "পরিশোধের শেষ তারিখ: $dateStr ($daysStr দিনের মধ্যে)"
            else "পরিশোধের শেষ তারিখ: $dateStr"
        } else {
            if (graceDays > 0) "Due Date: $dateStr (Within $graceDays Days)"
            else "Due Date: $dateStr"
        }
    }

    /**
     * Formats the customizable store interest and due day disclaimer with live rate, due date and grace period values.
     */
    fun formatDisclaimer(
        settings: KhataInterestSettings,
        customer: Customer? = null,
        isBengali: Boolean = false,
        dueDateMs: Long? = null,
        dueAmount: Double = 0.0
    ): String {
        val graceDays = settings.getEffectiveGraceDays(customer)
        val targetDueDateMs = dueDateMs ?: (System.currentTimeMillis() + (graceDays.toLong() * 86_400_000L))
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.US)
        val dateRaw = sdf.format(Date(targetDueDateMs))
        val bnDigits = charArrayOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')
        val dateDisplay = if (isBengali) {
            val sb = StringBuilder()
            for (ch in dateRaw) {
                if (ch in '0'..'9') sb.append(bnDigits[ch - '0']) else sb.append(ch)
            }
            sb.toString()
        } else dateRaw

        val daysDisplay = if (isBengali) {
            val sb = StringBuilder()
            for (ch in graceDays.toString()) {
                if (ch in '0'..'9') sb.append(bnDigits[ch - '0']) else sb.append(ch)
            }
            sb.toString()
        } else graceDays.toString()

        if (!settings.enabled && (customer?.customInterestRate == null || customer.customInterestRate <= 0.0)) {
            // Standard store credit & due day disclaimer when interest is disabled
            val dueAmtFormatted = if (isBengali) {
                val raw = "%.2f".format(Locale.US, dueAmount)
                val sb = StringBuilder()
                for (ch in raw) {
                    if (ch in '0'..'9') sb.append(bnDigits[ch - '0']) else sb.append(ch)
                }
                sb.toString()
            } else "%.2f".format(Locale.US, dueAmount)

            val withinDaysBn = if (graceDays > 0) " ($daysDisplay দিনের মধ্যে)" else ""
            val withinDaysEn = if (graceDays > 0) " within $graceDays days" else ""

            return if (isBengali) {
                if (dueAmount > 0.0) {
                    "বাকির টাকা (₹$dueAmtFormatted) পরিশোধের শেষ তারিখ: $dateDisplay$withinDaysBn।"
                } else {
                    "বাকির টাকা পরিশোধের শেষ তারিখ: $dateDisplay$withinDaysBn।"
                }
            } else {
                if (dueAmount > 0.0) {
                    "Payment Due Date: $dateDisplay. Please clear remaining balance of ₹$dueAmtFormatted$withinDaysEn."
                } else {
                    "Payment Due Date: $dateDisplay. Please clear remaining credit balance$withinDaysEn."
                }
            }
        }

        if (customer?.interestExempt == true) {
            return if (isBengali) "এই গ্রাহক সুদমুক্ত (Interest Exempt)। পরিশোধের শেষ তারিখ: $dateDisplay।" else "This customer account is interest-exempt. Due Date: $dateDisplay."
        }

        val rate = settings.getEffectiveRate(customer)
        val rateStr = if (rate % 1.0 == 0.0) "${rate.toInt()}%" else "%.1f%%".format(Locale.US, rate)
        val rateDisplay = if (isBengali) {
            val sb = StringBuilder()
            for (ch in rateStr) {
                if (ch in '0'..'9') sb.append(bnDigits[ch - '0']) else sb.append(ch)
            }
            sb.toString()
        } else rateStr

        if (isBengali) {
            return "$dateDisplay এর পর বকেয়ায় মাসিক $rateDisplay হারে বিলম্ব সুদ প্রযোজ্য হবে।"
        }

        val template = settings.disclaimerText.ifBlank {
            "{rate}% monthly late interest applies on dues after {due_date}."
        }

        val dueAmtFormatted = "%.2f".format(Locale.US, dueAmount)

        return template
            .replace("{rate}", rateStr.replace("%", ""))
            .replace("{grace_days}", graceDays.toString())
            .replace("{graceDays}", graceDays.toString())
            .replace("{due_date}", dateDisplay)
            .replace("{dueDate}", dateDisplay)
            .replace("{due_amount}", dueAmtFormatted)
            .replace("{dueAmount}", dueAmtFormatted)
    }

    /**
     * Generates a descriptive note for an interest ledger entry being posted.
     */
    fun createInterestLedgerNote(
        monthlyRate: Double,
        calculationMode: String,
        isBengali: Boolean = false
    ): String {
        val sdf = SimpleDateFormat("MMM yyyy", Locale.getDefault())
        val monthStr = sdf.format(Date())
        val modeStr = if (calculationMode.equals("COMPOUNDING", ignoreCase = true)) "Compounding" else "Simple"
        val rateStr = if (monthlyRate % 1.0 == 0.0) "${monthlyRate.toInt()}%" else "%.1f%%".format(Locale.US, monthlyRate)

        return if (isBengali) {
            "বিলম্ব সুদ ($monthStr): $rateStr/মাস ($modeStr)"
        } else {
            "Interest — $monthStr ($rateStr/mo $modeStr)"
        }
    }

    /**
     * Converts ASCII digits in a string to Bengali script digits.
     */
    fun toBengaliDigits(input: String): String {
        val bnDigits = charArrayOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')
        val sb = StringBuilder()
        for (ch in input) {
            if (ch in '0'..'9') sb.append(bnDigits[ch - '0']) else sb.append(ch)
        }
        return sb.toString()
    }
}
