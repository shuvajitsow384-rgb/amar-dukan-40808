package com.example.ui.screens.pos

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.local.dao.SaleWithItems
import com.example.ui.theme.*
import com.example.utils.LanguageManager
import com.example.utils.PdfReceiptHelper
import com.example.utils.SmsHelper
import com.example.utils.StoreInfoManager
import com.example.utils.WhatsAppHelper
import com.example.viewmodel.StoreViewModel
import java.text.SimpleDateFormat
import java.util.*

enum class DailySummaryFilter {
    TODAY, YESTERDAY, ALL_TIME
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailySalesSummaryDialog(
    viewModel: StoreViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isBn = LanguageManager.isBengali
    var selectedFilter by remember { mutableStateOf(DailySummaryFilter.TODAY) }

    val allSales by viewModel.allSales.collectAsState()
    val allExpenses by viewModel.allExpenses.collectAsState()
    val allProducts by viewModel.allProducts.collectAsState()
    val allReturns by viewModel.allReturns.collectAsState()
    val allCustomers by viewModel.allCustomers.collectAsState()
    val allLedgerEntries by viewModel.allLedgerEntries.collectAsState()
    var showEditPdfFormatDialog by remember { mutableStateOf(false) }

    var selectedSaleForDetail by remember { mutableStateOf<SaleWithItems?>(null) }
    var selectedReturnForDetail by remember { mutableStateOf<com.example.data.local.entities.SaleReturnWithItems?>(null) }
    var saleToReturnFromDetail by remember { mutableStateOf<SaleWithItems?>(null) }
    var selectedTransactionTab by remember { mutableIntStateOf(0) } // 0 = Sales Bills, 1 = Returns & Exchanges

    // Filter sales and expenses based on selected filter
    val filteredData = remember(allSales, allExpenses, selectedFilter) {
        val cal = Calendar.getInstance()
        when (selectedFilter) {
            DailySummaryFilter.TODAY -> {
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val start = cal.timeInMillis
                cal.set(Calendar.HOUR_OF_DAY, 23)
                cal.set(Calendar.MINUTE, 59)
                cal.set(Calendar.SECOND, 59)
                cal.set(Calendar.MILLISECOND, 999)
                val end = cal.timeInMillis

                val sales = allSales.filter { it.sale.datetime in start..end }
                val exp = allExpenses.filter { it.date in start..end }
                Triple(sales, exp, "Today")
            }
            DailySummaryFilter.YESTERDAY -> {
                cal.add(Calendar.DAY_OF_YEAR, -1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val start = cal.timeInMillis
                cal.set(Calendar.HOUR_OF_DAY, 23)
                cal.set(Calendar.MINUTE, 59)
                cal.set(Calendar.SECOND, 59)
                cal.set(Calendar.MILLISECOND, 999)
                val end = cal.timeInMillis

                val sales = allSales.filter { it.sale.datetime in start..end }
                val exp = allExpenses.filter { it.date in start..end }
                Triple(sales, exp, "Yesterday")
            }
            DailySummaryFilter.ALL_TIME -> {
                Triple(allSales, allExpenses, "All Time")
            }
        }
    }

    val sales = filteredData.first
    val expenses = filteredData.second

    // Returns for selected filter
    val returns = remember(allReturns, selectedFilter) {
        val cal = Calendar.getInstance()
        when (selectedFilter) {
            DailySummaryFilter.TODAY -> {
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val start = cal.timeInMillis
                allReturns.filter { it.saleReturn.datetime >= start }
            }
            DailySummaryFilter.YESTERDAY -> {
                cal.add(Calendar.DAY_OF_YEAR, -1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val start = cal.timeInMillis
                cal.set(Calendar.HOUR_OF_DAY, 23)
                cal.set(Calendar.MINUTE, 59)
                cal.set(Calendar.SECOND, 59)
                cal.set(Calendar.MILLISECOND, 999)
                val end = cal.timeInMillis
                allReturns.filter { it.saleReturn.datetime in start..end }
            }
            DailySummaryFilter.ALL_TIME -> allReturns
        }
    }

    // Customer Debt Repayments (Khata Debt Collected) for selected filter
    val customerDebtRepayments = remember(allLedgerEntries, selectedFilter) {
        val cal = Calendar.getInstance()
        when (selectedFilter) {
            DailySummaryFilter.TODAY -> {
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val start = cal.timeInMillis
                cal.set(Calendar.HOUR_OF_DAY, 23)
                cal.set(Calendar.MINUTE, 59)
                cal.set(Calendar.SECOND, 59)
                cal.set(Calendar.MILLISECOND, 999)
                val end = cal.timeInMillis
                allLedgerEntries.filter {
                    it.partyType == "CUSTOMER" &&
                    (it.type.contains("RECEIVED") || it.type in listOf("PAYMENT", "RETURN_REFUND", "CREDIT_REDUCED", "REFUND")) &&
                    it.datetime in start..end
                }
            }
            DailySummaryFilter.YESTERDAY -> {
                cal.add(Calendar.DAY_OF_YEAR, -1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val start = cal.timeInMillis
                cal.set(Calendar.HOUR_OF_DAY, 23)
                cal.set(Calendar.MINUTE, 59)
                cal.set(Calendar.SECOND, 59)
                cal.set(Calendar.MILLISECOND, 999)
                val end = cal.timeInMillis
                allLedgerEntries.filter {
                    it.partyType == "CUSTOMER" &&
                    (it.type.contains("RECEIVED") || it.type in listOf("PAYMENT", "RETURN_REFUND", "CREDIT_REDUCED", "REFUND")) &&
                    it.datetime in start..end
                }
            }
            DailySummaryFilter.ALL_TIME -> {
                allLedgerEntries.filter {
                    it.partyType == "CUSTOMER" &&
                    (it.type.contains("RECEIVED") || it.type in listOf("PAYMENT", "RETURN_REFUND", "CREDIT_REDUCED", "REFUND"))
                }
            }
        }
    }
    val totalDebtRepaidAmount = remember(customerDebtRepayments) { customerDebtRepayments.sumOf { it.amount } }

    // Aggregations
    val grossSalesRevenue = remember(sales) { sales.sumOf { it.sale.finalAmount } }
    val returnedRevenue = remember(returns) { returns.sumOf { it.saleReturn.totalReturnedAmount } }
    val replacementRevenue = remember(returns) { returns.sumOf { it.saleReturn.totalReplacementAmount } }
    val totalRevenue = (grossSalesRevenue - returnedRevenue + replacementRevenue).coerceAtLeast(0.0)

    val grossCogs = remember(sales) {
        sales.sumOf { saleWithItems ->
            saleWithItems.consolidatedItems.sumOf { it.totalCost }
        }
    }
    val returnCogsAdjustment = remember(returns, allProducts) {
        val productMap = allProducts.associateBy { it.id }
        returns.sumOf { ret ->
            ret.items.sumOf { rItem ->
                val product = productMap[rItem.productId]
                val purchasePrice = product?.costPrice ?: (rItem.unitPrice * 0.7)
                val itemCost = purchasePrice * rItem.quantity
                if (rItem.isReplacement) itemCost else -itemCost
            }
        }
    }
    val totalCogs = (grossCogs + returnCogsAdjustment).coerceAtLeast(0.0)
    val grossProfit = totalRevenue - totalCogs
    val totalExpensesAmount = remember(expenses) { expenses.sumOf { it.amount } }
    val netProfit = grossProfit - totalExpensesAmount

    val cashSales = remember(sales, returns) {
        val cashSalesGross = sales.filter { it.sale.paymentMode.equals("CASH", ignoreCase = true) }.sumOf { it.sale.finalAmount }
        val cashReturns = returns.filter { it.saleReturn.refundPaymentMode.equals("CASH", ignoreCase = true) }.sumOf { it.saleReturn.netAmount }
        (cashSalesGross - cashReturns).coerceAtLeast(0.0)
    }
    val upiSales = remember(sales, returns) {
        val upiSalesGross = sales.filter { it.sale.paymentMode.equals("UPI", ignoreCase = true) }.sumOf { it.sale.finalAmount }
        val upiReturns = returns.filter { it.saleReturn.refundPaymentMode.equals("UPI", ignoreCase = true) }.sumOf { it.saleReturn.netAmount }
        (upiSalesGross - upiReturns).coerceAtLeast(0.0)
    }
    val creditSales = remember(sales, returns) {
        val creditSalesGross = sales.filter { it.sale.paymentMode.equals("CREDIT", ignoreCase = true) }.sumOf { it.sale.finalAmount }
        val creditReturns = returns.filter { it.saleReturn.refundPaymentMode.equals("CREDIT", ignoreCase = true) }.sumOf { it.saleReturn.netAmount }
        (creditSalesGross - creditReturns).coerceAtLeast(0.0)
    }

    val totalItemsCount = remember(sales, returns) {
        val soldQty = sales.sumOf { saleWithItems -> saleWithItems.consolidatedItems.sumOf { it.quantity } }
        val returnQtyAdjustment = returns.sumOf { ret ->
            ret.items.sumOf { rItem ->
                if (rItem.isReplacement) rItem.quantity else -rItem.quantity
            }
        }
        (soldQty + returnQtyAdjustment).coerceAtLeast(0.0)
    }
    val averageBillValue = if (sales.isNotEmpty()) totalRevenue / sales.size else 0.0
    val overallGrossMarginPct = if (totalRevenue > 0) (grossProfit / totalRevenue) * 100.0 else 0.0

    // Category Profit Margin Breakdown Analysis
    val categorySummaries = remember(sales, returns, allProducts) {
        val productMap = allProducts.associateBy { it.id }
        val categoryMap = mutableMapOf<String, Triple<Double, Double, Double>>() // category -> (revenue, cost, qty)

        sales.forEach { saleWithItems ->
            val consolidated = saleWithItems.consolidatedItems
            consolidated.forEach { item ->
                val product = productMap[item.productId]
                val catName = product?.category?.ifBlank { "General" } ?: "General"
                val curr = categoryMap.getOrDefault(catName, Triple(0.0, 0.0, 0.0))
                categoryMap[catName] = Triple(
                    curr.first + item.subtotal,
                    curr.second + item.totalCost,
                    curr.third + item.quantity
                )
            }
        }

        // Adjust for product returns and replacements
        returns.forEach { retWithItems ->
            retWithItems.items.forEach { rItem ->
                val product = productMap[rItem.productId]
                val catName = product?.category?.ifBlank { "General" } ?: "General"
                val purchasePrice = product?.costPrice ?: (rItem.unitPrice * 0.7)
                val itemCost = purchasePrice * rItem.quantity
                val curr = categoryMap.getOrDefault(catName, Triple(0.0, 0.0, 0.0))

                if (rItem.isReplacement) {
                    categoryMap[catName] = Triple(
                        curr.first + rItem.subtotal,
                        curr.second + itemCost,
                        curr.third + rItem.quantity
                    )
                } else {
                    categoryMap[catName] = Triple(
                        curr.first - rItem.subtotal,
                        curr.second - itemCost,
                        curr.third - rItem.quantity
                    )
                }
            }
        }

        val totalPeriodRevenue = categoryMap.values.sumOf { it.first }.coerceAtLeast(0.0)
        val maxMargin = categoryMap.map { (_, triple) ->
            val rev = triple.first
            val profit = rev - triple.second
            if (rev > 0) (profit / rev) * 100.0 else 0.0
        }.maxOrNull() ?: 0.0

        val maxRevenue = categoryMap.values.maxOfOrNull { it.first } ?: 0.0

        categoryMap.map { (catName, triple) ->
            val rev = triple.first
            val cost = triple.second
            val profit = rev - cost
            val marginPct = if (rev > 0) (profit / rev) * 100.0 else 0.0
            val sharePct = if (totalPeriodRevenue > 0 && rev > 0) (rev / totalPeriodRevenue) * 100.0 else 0.0

            CategoryProfitSummary(
                categoryName = catName,
                totalRevenue = rev,
                totalCost = cost,
                totalProfit = profit,
                profitMarginPct = marginPct,
                itemsCount = triple.third.coerceAtLeast(0.0),
                revenueSharePct = sharePct,
                isTopMargin = marginPct > 0 && Math.abs(marginPct - maxMargin) < 0.001 && categoryMap.size > 1,
                isTopRevenue = rev > 0 && Math.abs(rev - maxRevenue) < 0.001 && categoryMap.size > 1
            )
        }
    }

    val timeFormat = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.background,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = StorePrimaryContainer,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Assessment,
                                    contentDescription = null,
                                    tint = StorePrimary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = LanguageManager.getString("Daily Sales Summary", "দৈনিক বিক্রয় সারসংক্ষেপ"),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = LanguageManager.getString("Aggregated billing & profit insights", "আজকের সমস্ত বিক্রয় ও লাভের হিসাব"),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Date/Period Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedFilter == DailySummaryFilter.TODAY,
                        onClick = { selectedFilter = DailySummaryFilter.TODAY },
                        label = { Text(LanguageManager.getString("Today", "আজ")) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = selectedFilter == DailySummaryFilter.YESTERDAY,
                        onClick = { selectedFilter = DailySummaryFilter.YESTERDAY },
                        label = { Text(LanguageManager.getString("Yesterday", "গতকাল")) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = selectedFilter == DailySummaryFilter.ALL_TIME,
                        onClick = { selectedFilter = DailySummaryFilter.ALL_TIME },
                        label = { Text(LanguageManager.getString("All Time", "সর্বমোট")) },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Revenue & Profit Cards Grid
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Total Daily Revenue Card
                            Card(
                                modifier = Modifier.weight(1f),
                                colors = CardDefaults.cardColors(containerColor = StoreGreenProfit.copy(alpha = 0.12f)),
                                border = androidx.compose.foundation.BorderStroke(1.dp, StoreGreenProfit.copy(alpha = 0.4f)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = LanguageManager.getString("TOTAL REVENUE", "মোট বিক্রি (আয়)"),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = StoreGreenProfit
                                        )
                                        Icon(
                                            Icons.Default.TrendingUp,
                                            contentDescription = null,
                                            tint = StoreGreenProfit,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "₹%.2f".format(totalRevenue),
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = StoreGreenProfit
                                    )
                                    Text(
                                        text = "${sales.size} ${LanguageManager.getString("Bills completed", "টি বিল ক্লিয়ার")}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMuted
                                    )
                                }
                            }

                            // Total Daily Profit Card
                            Card(
                                modifier = Modifier.weight(1f),
                                colors = CardDefaults.cardColors(containerColor = StorePrimary.copy(alpha = 0.12f)),
                                border = androidx.compose.foundation.BorderStroke(1.dp, StorePrimary.copy(alpha = 0.4f)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = LanguageManager.getString("GROSS PROFIT", "মোট লাভ (Profit)"),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = StorePrimary
                                        )
                                        Icon(
                                            Icons.Default.Payments,
                                            contentDescription = null,
                                            tint = StorePrimary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "₹%.2f".format(grossProfit),
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = StorePrimary
                                    )
                                    Text(
                                        text = "Net: ₹%.2f".format(netProfit),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (netProfit >= 0) StoreGreenProfit else StoreRedAlert,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }

                    // Category Profit Margin Breakdown Card
                    item {
                        CategoryProfitMarginBreakdownCard(
                            categorySummaries = categorySummaries,
                            overallGrossMarginPct = overallGrossMarginPct,
                            isBn = isBn
                        )
                    }

                    // Key Breakdown Statistics Card
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = CardBackground),
                            elevation = CardDefaults.cardElevation(2.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = LanguageManager.getString("Payment Mode Aggregations", "পেমেন্ট মাধ্যম অনুযায়ী জমা"),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(horizontalAlignment = Alignment.Start) {
                                        Text(LanguageManager.getString("💵 Cash", "💵 নগদ"), style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                        Text("₹%.2f".format(cashSales), fontWeight = FontWeight.Bold, color = StoreGreenProfit)
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(LanguageManager.getString("📱 UPI Digital", "📱 ইউপিআই"), style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                        Text("₹%.2f".format(upiSales), fontWeight = FontWeight.Bold, color = StorePrimary)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(LanguageManager.getString("📖 Credit (Khata)", "📖 বাকী (খাতা)"), style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                        Text("₹%.2f".format(creditSales), fontWeight = FontWeight.Bold, color = StoreRedAlert)
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(StoreGreenProfit.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 10.dp, vertical = 7.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = LanguageManager.getString("🤝 Debt Repaid (Khata Collection):", "🤝 বকেয়া আদায় (খাতা কালেকশন):"),
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium,
                                            color = TextDark
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            color = StoreGreenProfit.copy(alpha = 0.15f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = "${customerDebtRepayments.size} ${if (isBn) "টি জমা" else "rcv'd"}",
                                                fontSize = 9.sp,
                                                color = StoreGreenProfit,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = "+₹%.2f".format(totalDebtRepaidAmount),
                                        fontWeight = FontWeight.ExtraBold,
                                        color = StoreGreenProfit,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }

                                Divider(modifier = Modifier.padding(vertical = 10.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = LanguageManager.getString("Avg Order Value:", "গড় অর্ডার মূল্য:"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextMuted
                                    )
                                    Text(
                                        text = "₹%.2f".format(averageBillValue),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = LanguageManager.getString("Total Items Sold:", "মোট বিক্রি হওয়া আইটেম:"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextMuted
                                    )
                                    Text(
                                        text = if (totalItemsCount % 1.0 == 0.0) "${totalItemsCount.toInt()} units" else "%.2f units".format(totalItemsCount),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    // Transactions Section Tab Header
                    item {
                        TabRow(
                            selectedTabIndex = selectedTransactionTab,
                            containerColor = CardBackground,
                            contentColor = StorePrimary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                        ) {
                            Tab(
                                selected = selectedTransactionTab == 0,
                                onClick = { selectedTransactionTab = 0 },
                                text = {
                                    Text(
                                        text = "${LanguageManager.getString("Sales Bills", "বিক্রয় বিল")} (${sales.size})",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                            )
                            Tab(
                                selected = selectedTransactionTab == 1,
                                onClick = { selectedTransactionTab = 1 },
                                text = {
                                    Text(
                                        text = "${LanguageManager.getString("Returns & Exchanges", "ফেরত ও পরিবর্তন")} (${returns.size})",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                            )
                        }
                    }

                    if (selectedTransactionTab == 0) {
                        if (sales.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            Icons.Default.ReceiptLong,
                                            contentDescription = null,
                                            modifier = Modifier.size(48.dp),
                                            tint = TextMuted.copy(alpha = 0.5f)
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = LanguageManager.getString("No transactions found for this period", "এই সময়ের মধ্যে কোনো বিল তৈরি হয়নি"),
                                            color = TextMuted,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                        } else {
                            items(sales, key = { it.sale.id }, contentType = { "DAILY_SALE_SUMMARY_ROW" }) { saleWithItems ->
                                val sale = saleWithItems.sale
                                val consolidated = saleWithItems.consolidatedItems
                                val saleCogs = consolidated.sumOf { it.totalCost }
                                val saleProfit = sale.finalAmount - saleCogs
                                val timeStr = timeFormat.format(Date(sale.datetime))

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedSaleForDetail = saleWithItems },
                                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                                    elevation = CardDefaults.cardElevation(1.dp),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                // Payment Mode Tag
                                                val modeColor = when (sale.paymentMode.uppercase()) {
                                                    "CASH" -> StoreGreenProfit
                                                    "UPI" -> StorePrimary
                                                    else -> StoreRedAlert
                                                }
                                                Surface(
                                                    color = modeColor.copy(alpha = 0.15f),
                                                    shape = RoundedCornerShape(6.dp)
                                                ) {
                                                    Text(
                                                        text = sale.paymentMode.uppercase(),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = modeColor,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = sale.customerName ?: LanguageManager.getString("Walk-in Customer", "সাধারণ খদ্দের"),
                                                    fontWeight = FontWeight.Bold,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }

                                            Text(
                                                text = "₹%.2f".format(sale.finalAmount),
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.titleSmall,
                                                color = StoreGreenProfit
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(6.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "$timeStr • ${consolidated.size} ${LanguageManager.getString("Items", "টি পণ্য")}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = TextMuted
                                            )

                                            Text(
                                                text = "Profit: ₹%.2f".format(saleProfit),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = StorePrimary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // Returns and Replacements Tab
                        if (returns.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            Icons.Default.AssignmentReturn,
                                            contentDescription = null,
                                            modifier = Modifier.size(48.dp),
                                            tint = TextMuted.copy(alpha = 0.5f)
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = if (isBn) "এই সময়ের মধ্যে কোনো ফেরত বা পরিবর্তন নেই" else "No returns or replacements in this period",
                                            color = TextMuted,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                        } else {
                            items(returns, key = { it.saleReturn.id }) { ret ->
                                val sr = ret.saleReturn
                                val isRepl = sr.type.equals("REPLACEMENT", ignoreCase = true)
                                val timeStr = timeFormat.format(Date(sr.datetime))

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedReturnForDetail = ret },
                                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                                    elevation = CardDefaults.cardElevation(1.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, if (isRepl) StorePrimary.copy(alpha = 0.3f) else StoreRedAlert.copy(alpha = 0.3f))
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Surface(
                                                    color = if (isRepl) StorePrimary.copy(alpha = 0.15f) else StoreRedAlert.copy(alpha = 0.15f),
                                                    shape = RoundedCornerShape(6.dp)
                                                ) {
                                                    Text(
                                                        text = if (isRepl) (if (isBn) "বদল" else "REPLACE") else (if (isBn) "ফেরত" else "RETURN"),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (isRepl) StorePrimary else StoreRedAlert,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = sr.customerName ?: if (isBn) "সাধারণ গ্রাহক" else "Walk-in Customer",
                                                    fontWeight = FontWeight.Bold,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }

                                            Text(
                                                text = "₹%.2f".format(kotlin.math.abs(sr.netAmount)),
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.titleSmall,
                                                color = if (sr.netAmount > 0) StoreRedAlert else if (sr.netAmount < 0) StoreGreenProfit else StorePrimary
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(6.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "$timeStr • Bill #${sr.saleId.takeLast(6)} • ${ret.items.size} items",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = TextMuted
                                            )

                                            Text(
                                                text = if (sr.netAmount > 0) "Refund (${sr.refundPaymentMode})" else if (sr.netAmount < 0) "Collected (${sr.refundPaymentMode})" else "Even",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (sr.netAmount > 0) StoreRedAlert else if (sr.netAmount < 0) StoreGreenProfit else StorePrimary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Bottom Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            try {
                                val summaryText = com.example.utils.DailySummaryReportHelper.generateDailySummaryText(
                                    storeName = com.example.utils.StoreInfoManager.storeName,
                                    periodLabel = filteredData.third,
                                    sales = sales,
                                    totalRevenue = totalRevenue,
                                    grossProfit = grossProfit,
                                    totalExpenses = totalExpensesAmount,
                                    netProfit = netProfit,
                                    overallGrossMarginPct = overallGrossMarginPct,
                                    cashSales = cashSales,
                                    upiSales = upiSales,
                                    creditSales = creditSales,
                                    totalItemsCount = totalItemsCount,
                                    averageBillValue = averageBillValue,
                                    categorySummaries = categorySummaries,
                                    returns = returns
                                )

                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "Daily Sales Summary - ${com.example.utils.StoreInfoManager.storeName}")
                                    putExtra(Intent.EXTRA_TEXT, summaryText)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                val chooser = Intent.createChooser(shareIntent, "Share Summary via").apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(chooser)
                            } catch (e: Exception) {
                                android.util.Log.e("DailySalesSummary", "Error sharing summary: ${e.message}", e)
                                android.widget.Toast.makeText(
                                    context,
                                    if (isBn) "সারসংক্ষেপ শেয়ার করতে ব্যর্থ হয়েছে: ${e.localizedMessage ?: "অজানা ত্রুটি"}" else "Failed to share summary: ${e.localizedMessage ?: "Unknown error"}",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(LanguageManager.getString("Share Summary", "শেয়ার করুন"))
                    }

                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = StorePrimary)
                    ) {
                        Text(LanguageManager.getString("Close", "বন্ধ করুন"))
                    }
                }
            }
        }
    }

    // Bill Detail Receipt Modal
    selectedSaleForDetail?.let { saleWithItems ->
        com.example.ui.components.TransactionSuccessReceiptModal(
            saleWithItems = saleWithItems,
            customers = allCustomers,
            printerStatusMessage = viewModel.printerStatusMessage,
            onDismiss = { selectedSaleForDetail = null },
            onPrintThermal = { viewModel.printCurrentSale(saleWithItems) },
            onPrintThermalWithLang = { isBn -> viewModel.printCurrentSale(saleWithItems, isBengali = isBn) },
            onNewSale = { selectedSaleForDetail = null },
            onOpenPdfSettings = { showEditPdfFormatDialog = true },
            onReturnOrReplace = {
                val s = selectedSaleForDetail
                selectedSaleForDetail = null
                saleToReturnFromDetail = s
            }
        )
    }

    // Return & Replacement Dialog triggered from bill detail
    saleToReturnFromDetail?.let { saleWithItems ->
        ReturnReplacementDialog(
            saleWithItems = saleWithItems,
            allProducts = allProducts,
            allReturns = allReturns,
            allCustomers = allCustomers,
            onDismiss = { saleToReturnFromDetail = null },
            onConfirmReturn = { saleReturn, returnItems ->
                val returnWithItems = com.example.data.local.entities.SaleReturnWithItems(saleReturn, returnItems)
                viewModel.processReturnOrReplacement(saleReturn, returnItems) {
                    saleToReturnFromDetail = null
                    selectedReturnForDetail = returnWithItems
                }
            }
        )
    }

    // Return Success / Detail Modal
    selectedReturnForDetail?.let { returnWithItems ->
        com.example.ui.components.ReturnSuccessReceiptModal(
            returnWithItems = returnWithItems,
            onDismiss = { selectedReturnForDetail = null },
            onPrintThermal = {
                viewModel.printSaleReturnReceipt(returnWithItems)
            },
            printerStatusMessage = viewModel.printerStatusMessage
        )
    }

    if (showEditPdfFormatDialog) {
        com.example.ui.components.EditPdfFormatDialog(onDismiss = { showEditPdfFormatDialog = false })
    }
}

@Composable
fun TransactionDetailDialog(
    saleWithItems: SaleWithItems,
    viewModel: StoreViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isBn = LanguageManager.isBengali
    var showReturnDialog by remember { mutableStateOf(false) }
    val allProducts by viewModel.allProducts.collectAsState()
    val sale = saleWithItems.sale
    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy hh:mm a", Locale.getDefault()) }

    val consolidatedItems = remember(saleWithItems) {
        saleWithItems.consolidatedItems
    }
    val saleCogs = consolidatedItems.sumOf { it.totalCost }
    val saleProfit = sale.finalAmount - saleCogs

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = LanguageManager.getString("Bill Transaction Details", "বিল রসিদ বিবরণ"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = dateFormat.format(Date(sale.datetime)),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = StorePrimaryContainer.copy(alpha = 0.2f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Customer: ${sale.customerName ?: LanguageManager.getString("Walk-in Customer", "সাধারণ গ্রাহক")}",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "Mode: ${sale.paymentMode}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "Total: ₹%.2f".format(sale.finalAmount),
                                fontWeight = FontWeight.Bold,
                                color = StoreGreenProfit,
                                style = MaterialTheme.typography.titleSmall
                            )
                            if (sale.discount > 0) {
                                val discPct = if (sale.totalAmount > 0) (sale.discount / sale.totalAmount) * 100.0 else 0.0
                                Text(
                                    text = "Discount: -₹%.2f (%.1f%%)".format(sale.discount, discPct),
                                    fontWeight = FontWeight.Bold,
                                    color = StoreGreenProfit,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            Text(
                                text = "Profit: ₹%.2f".format(saleProfit),
                                fontWeight = FontWeight.Bold,
                                color = StorePrimary,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }

                Text(
                    text = LanguageManager.getString("Purchased Items:", "ক্রয়কৃত আইটেমস:"),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )

                LazyColumn(
                    modifier = Modifier.heightIn(max = 200.dp)
                ) {
                    items(consolidatedItems, key = { it.productId }, contentType = { "DAILY_CONSOLIDATED_ITEM" }) { item ->
                        val name = if (isBn && item.productNameBn.isNotBlank()) item.productNameBn else if (item.productNameEn.isNotBlank()) item.productNameEn else "Item"
                        val qtyStr = if (item.unitType.equals("gram", ignoreCase = true)) {
                            "${item.quantity.toInt()}g"
                        } else if (item.quantity % 1.0 == 0.0) {
                            "${item.quantity.toInt()} ${item.unitType}"
                        } else {
                            "%.2f ${item.unitType}".format(item.quantity)
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text("$qtyStr @ ₹%.2f".format(item.unitPrice), style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                    if (item.hasDiscount()) {
                                        Surface(
                                            color = StoreGreenProfit.copy(alpha = 0.15f),
                                            shape = RoundedCornerShape(3.dp)
                                        ) {
                                            Text(
                                                "Save ₹%.2f (%.0f%% off)".format(item.getSavingsAmount(), item.getDiscountPercent()),
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = StoreGreenProfit,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                            }
                            Text("₹%.2f".format(item.subtotal), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        }
                        Divider(modifier = Modifier.padding(vertical = 2.dp), color = Color.LightGray.copy(alpha = 0.5f))
                    }
                }
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = {
                            val pdfFile = PdfReceiptHelper.generateReceiptPdf(context, saleWithItems)
                            PdfReceiptHelper.printPdf(context, pdfFile, "Receipt_${saleWithItems.sale.id}")
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = StorePrimary)
                    ) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("PDF Print", fontSize = 11.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            viewModel.printCurrentSale(saleWithItems)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Bluetooth", fontSize = 11.sp)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val pdfFile = PdfReceiptHelper.generateReceiptPdf(context, saleWithItems)
                            PdfReceiptHelper.sharePdf(context, pdfFile)
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("PDF", fontSize = 11.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            val msg = SmsHelper.generateBillSms(saleWithItems, StoreInfoManager.isSmsBengali())
                            val phone = saleWithItems.sale.customerId?.let { id ->
                                viewModel.allCustomers.value.find { it.id == id }?.phone
                            }
                            SmsHelper.sendSms(context, phone, msg)
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Sms, contentDescription = null, modifier = Modifier.size(14.dp), tint = StorePrimary)
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("SMS", fontSize = 11.sp, color = StorePrimary, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            val msg = WhatsAppHelper.generateBillMessage(saleWithItems, isBn)
                            val phone = saleWithItems.sale.customerId?.let { id ->
                                viewModel.allCustomers.value.find { it.id == id }?.phone
                            }
                            WhatsAppHelper.sendWhatsAppMessage(context, phone, msg)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = StoreGreenProfit),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("WhatsApp", fontSize = 11.sp)
                    }
                }

                // Return or Replace Action Row
                Button(
                    onClick = { showReturnDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = StoreRedAlert),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(vertical = 6.dp)
                ) {
                    Icon(Icons.Default.AssignmentReturn, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isBn) "পণ্য ফেরত বা পরিবর্তন করুন (Return / Replace)" else "Return / Replace Items",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(LanguageManager.getString("Close", "বন্ধ করুন"))
            }
        }
    )

    if (showReturnDialog) {
        ReturnReplacementDialog(
            saleWithItems = saleWithItems,
            allProducts = allProducts,
            allReturns = viewModel.allReturns.value,
            allCustomers = viewModel.allCustomers.value,
            onDismiss = { showReturnDialog = false },
            onConfirmReturn = { saleReturn, returnItems ->
                viewModel.processReturnOrReplacement(saleReturn, returnItems) {
                    showReturnDialog = false
                    onDismiss()
                }
            }
        )
    }
}

enum class CategorySortMode {
    PROFIT_DESC, MARGIN_DESC, REVENUE_DESC
}

data class CategoryProfitSummary(
    val categoryName: String,
    val totalRevenue: Double,
    val totalCost: Double,
    val totalProfit: Double,
    val profitMarginPct: Double,
    val itemsCount: Double,
    val revenueSharePct: Double,
    val isTopMargin: Boolean = false,
    val isTopRevenue: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryProfitMarginBreakdownCard(
    categorySummaries: List<CategoryProfitSummary>,
    overallGrossMarginPct: Double,
    isBn: Boolean
) {
    var sortMode by remember { mutableStateOf(CategorySortMode.PROFIT_DESC) }
    var isExpanded by remember { mutableStateOf(false) }

    val sortedSummaries = remember(categorySummaries, sortMode) {
        when (sortMode) {
            CategorySortMode.PROFIT_DESC -> categorySummaries.sortedByDescending { it.totalProfit }
            CategorySortMode.MARGIN_DESC -> categorySummaries.sortedByDescending { it.profitMarginPct }
            CategorySortMode.REVENUE_DESC -> categorySummaries.sortedByDescending { it.totalRevenue }
        }
    }

    val topPerformer = remember(categorySummaries) {
        categorySummaries.maxByOrNull { it.totalProfit }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        elevation = CardDefaults.cardElevation(2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Category,
                        contentDescription = null,
                        tint = StorePrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = LanguageManager.getString("Category Profit Margins", "ক্যাটাগরি ভিত্তিক লাভ ও মার্জিন"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                Surface(
                    color = StorePrimary.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = "Avg Margin: %.1f%%".format(overallGrossMarginPct),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = StorePrimary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (categorySummaries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = LanguageManager.getString("No category sales in this period", "এই সময়ে কোনো ক্যাটাগরির পণ্য বিক্রি হয়নি"),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                }
            } else {
                // Top Performer Highlight Insight Banner
                topPerformer?.let { top ->
                    if (top.totalProfit > 0) {
                        Surface(
                            color = StoreGreenProfit.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, StoreGreenProfit.copy(alpha = 0.25f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.TrendingUp,
                                    contentDescription = null,
                                    tint = StoreGreenProfit,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = LanguageManager.getString(
                                        "Top High-Performing Category: ${top.categoryName} (₹%.2f profit, %.1f%% margin)".format(top.totalProfit, top.profitMarginPct),
                                        "সেরা পারফর্মকারী ক্যাটাগরি: ${top.categoryName} (লাভ: ₹%.2f, মার্জিন: %.1f%%)".format(top.totalProfit, top.profitMarginPct)
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = StoreGreenProfit
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Sort Options Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = LanguageManager.getString("Sort by:", "সাজান:"),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(
                            selected = sortMode == CategorySortMode.PROFIT_DESC,
                            onClick = { sortMode = CategorySortMode.PROFIT_DESC },
                            label = { Text("Profit ₹", fontSize = 10.sp) },
                            modifier = Modifier.height(28.dp)
                        )
                        FilterChip(
                            selected = sortMode == CategorySortMode.MARGIN_DESC,
                            onClick = { sortMode = CategorySortMode.MARGIN_DESC },
                            label = { Text("Margin %", fontSize = 10.sp) },
                            modifier = Modifier.height(28.dp)
                        )
                        FilterChip(
                            selected = sortMode == CategorySortMode.REVENUE_DESC,
                            onClick = { sortMode = CategorySortMode.REVENUE_DESC },
                            label = { Text("Sales ₹", fontSize = 10.sp) },
                            modifier = Modifier.height(28.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Render list of categories
                val displayList = if (isExpanded) sortedSummaries else sortedSummaries.take(3)

                displayList.forEachIndexed { index, cat ->
                    CategoryMarginItemRow(summary = cat, isBn = isBn)
                    if (index < displayList.size - 1) {
                        Divider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                }

                if (sortedSummaries.size > 3) {
                    Spacer(modifier = Modifier.height(6.dp))
                    TextButton(
                        onClick = { isExpanded = !isExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (isExpanded) {
                                LanguageManager.getString("Show Top 3 Categories", "শীর্ষ ৩ টি ক্যাটাগরি দেখুন")
                            } else {
                                LanguageManager.getString("Show All (${sortedSummaries.size}) Categories", "সব (${sortedSummaries.size} টি) ক্যাটাগরি দেখুন")
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = StorePrimary
                        )
                        Icon(
                            if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = StorePrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CategoryMarginItemRow(
    summary: CategoryProfitSummary,
    isBn: Boolean
) {
    val marginColor = when {
        summary.profitMarginPct >= 20.0 -> StoreGreenProfit
        summary.profitMarginPct >= 10.0 -> StorePrimary
        summary.profitMarginPct >= 0.0 -> Color(0xFFE65100)
        else -> StoreRedAlert
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = summary.categoryName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (summary.isTopMargin) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Surface(
                        color = StoreGreenProfit.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "🔥 High Margin",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = StoreGreenProfit,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                } else if (summary.isTopRevenue) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Surface(
                        color = StorePrimary.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "💰 Top Seller",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = StorePrimary,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // Margin Badge
            Surface(
                color = marginColor.copy(alpha = 0.12f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "%.1f%% Margin".format(summary.profitMarginPct),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = marginColor,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Visual Proportion Bar (Revenue Share + Profit Progress Bar)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Revenue Contribution Bar Track
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(8.dp)
                    .background(Color.LightGray.copy(alpha = 0.3f), shape = RoundedCornerShape(4.dp))
            ) {
                // Revenue Share width fill
                val shareFraction = (summary.revenueSharePct / 100.0).coerceIn(0.02, 1.0).toFloat()
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction = shareFraction)
                        .background(marginColor, shape = RoundedCornerShape(4.dp))
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "%.1f%% sales share".format(summary.revenueSharePct),
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                fontSize = 10.sp
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Detailed Financial Metrics Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Sales: ₹%.2f".format(summary.totalRevenue),
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted
            )

            Text(
                text = "Profit: ₹%.2f".format(summary.totalProfit),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = marginColor
            )

            Text(
                text = "Cost: ₹%.2f".format(summary.totalCost),
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )

            val itemsCountText = if (summary.itemsCount % 1.0 == 0.0) "${summary.itemsCount.toInt()} pcs" else "%.1f units".format(summary.itemsCount)
            Text(
                text = itemsCountText,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )
        }
    }
}
