package com.example.utils

import com.example.data.local.dao.SaleWithItems
import com.example.data.local.entities.SaleReturnWithItems
import com.example.ui.screens.pos.CategoryProfitSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DailySummaryReportHelper {

    fun generateDailySummaryText(
        storeName: String,
        periodLabel: String,
        sales: List<SaleWithItems>,
        totalRevenue: Double,
        grossProfit: Double,
        totalExpenses: Double,
        netProfit: Double,
        overallGrossMarginPct: Double,
        cashSales: Double,
        upiSales: Double,
        creditSales: Double,
        totalItemsCount: Double,
        averageBillValue: Double,
        categorySummaries: List<CategoryProfitSummary> = emptyList(),
        returns: List<SaleReturnWithItems> = emptyList()
    ): String {
        val safeStoreName = storeName.ifBlank { "Store" }
        val dateStr = try {
            SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date())
        } catch (_: Exception) {
            SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date())
        }

        val timeFormat = try {
            SimpleDateFormat("hh:mm a", Locale.getDefault())
        } catch (_: Exception) {
            SimpleDateFormat("hh:mm a", Locale.US)
        }

        return buildString {
            appendLine("📊 DAILY SALES SUMMARY ($periodLabel)")
            appendLine("🏪 $safeStoreName • $dateStr")
            appendLine("----------------------------------")
            appendLine("Total Revenue: ₹${String.format(Locale.US, "%.2f", totalRevenue)}")
            appendLine("Gross Profit: ₹${String.format(Locale.US, "%.2f", grossProfit)}")
            if (totalExpenses > 0) {
                appendLine("Total Expenses: ₹${String.format(Locale.US, "%.2f", totalExpenses)}")
            }
            appendLine("Net Profit: ₹${String.format(Locale.US, "%.2f", netProfit)}")
            appendLine("Overall Margin: ${String.format(Locale.US, "%.1f", overallGrossMarginPct)}%")
            appendLine("----------------------------------")
            appendLine("Total Transactions: ${sales.size} Bills")
            appendLine("Avg Bill Value: ₹${String.format(Locale.US, "%.2f", averageBillValue)}")
            val itemsFormatted = if (totalItemsCount % 1.0 == 0.0) {
                "${totalItemsCount.toInt()} units"
            } else {
                "${String.format(Locale.US, "%.2f", totalItemsCount)} units"
            }
            appendLine("Total Items Sold: $itemsFormatted")
            appendLine("----------------------------------")
            appendLine("💵 Cash Sales: ₹${String.format(Locale.US, "%.2f", cashSales)}")
            appendLine("📱 UPI Sales: ₹${String.format(Locale.US, "%.2f", upiSales)}")
            appendLine("📖 Credit (Khata): ₹${String.format(Locale.US, "%.2f", creditSales)}")

            if (returns.isNotEmpty()) {
                val retAmt = returns.sumOf { it.saleReturn.totalReturnedAmount }
                val repAmt = returns.sumOf { it.saleReturn.totalReplacementAmount }
                appendLine("----------------------------------")
                appendLine("🔄 RETURNS / REPLACEMENTS (${returns.size}):")
                appendLine("• Refunded: ₹${String.format(Locale.US, "%.2f", retAmt)} | Replaced: ₹${String.format(Locale.US, "%.2f", repAmt)}")
            }

            if (categorySummaries.isNotEmpty()) {
                appendLine("----------------------------------")
                appendLine("📈 CATEGORY MARGIN BREAKDOWN:")
                val sortedCats = categorySummaries.sortedByDescending { it.totalProfit }
                val displayCats = sortedCats.take(6)
                for (cat in displayCats) {
                    val catName = cat.categoryName.ifBlank { "General" }
                    val rev = String.format(Locale.US, "%.2f", cat.totalRevenue)
                    val prof = String.format(Locale.US, "%.2f", cat.totalProfit)
                    val margin = String.format(Locale.US, "%.1f", cat.profitMarginPct)
                    appendLine("• $catName: Sales ₹$rev | Profit ₹$prof ($margin% Margin)")
                }
                if (sortedCats.size > 6) {
                    appendLine("  ...and ${sortedCats.size - 6} more categories")
                }
            }

            if (sales.isNotEmpty()) {
                appendLine("----------------------------------")
                appendLine("🧾 TRANSACTIONS (${sales.size}):")
                val recentSales = sales.take(8)
                for (saleItem in recentSales) {
                    val s = saleItem.sale
                    val cust = s.customerName?.ifBlank { "Walk-in" } ?: "Walk-in"
                    val t = try { timeFormat.format(Date(s.datetime)) } catch (_: Exception) { "" }
                    val mode = s.paymentMode.uppercase().ifBlank { "CASH" }
                    val amt = String.format(Locale.US, "%.2f", s.finalAmount)
                    val timePrefix = if (t.isNotBlank()) "$t • " else ""
                    appendLine("• $timePrefix$cust: ₹$amt ($mode)")
                }
                if (sales.size > 8) {
                    appendLine("  ...and ${sales.size - 8} more bills")
                }
            }

            appendLine("----------------------------------")
            appendLine("Generated by Store POS")
        }
    }
}
