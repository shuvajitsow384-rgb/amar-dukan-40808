package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.local.dao.SaleWithItems
import com.example.data.local.entities.Customer
import com.example.data.local.entities.Expense
import com.example.data.local.entities.Product
import com.example.data.local.entities.SaleReturnWithItems
import com.example.ui.theme.*
import com.example.utils.LanguageManager
import com.example.utils.StoreInfoManager
import java.text.SimpleDateFormat
import java.util.*

private enum class DayDetailsTab(val titleEn: String, val titleBn: String) {
    ORDERS("Orders & Bills", "অর্ডার ও বিল"),
    PRODUCTS("Items Sold", "বিক্রীত পণ্য"),
    EXPENSES("Day Expenses", "দিনের খরচ"),
    RETURNS("Returns", "ফেরত/বদল")
}

private data class AggregatedProductItem(
    val productId: String,
    val productNameEn: String,
    val productNameBn: String,
    val unitType: String,
    val totalQuantity: Double,
    val totalRevenue: Double,
    val estimatedProfit: Double,
    val ordersCount: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrendDayDetailsDialog(
    item: DailyTrendItem,
    allSales: List<SaleWithItems>,
    allReturns: List<SaleReturnWithItems>,
    allExpenses: List<Expense>,
    allProducts: List<Product>,
    allCustomers: List<Customer>,
    onDismiss: () -> Unit,
    onViewSaleReceipt: (SaleWithItems) -> Unit
) {
    val context = LocalContext.current
    val isBn = LanguageManager.isBengali

    var selectedTab by remember { mutableStateOf(DayDetailsTab.ORDERS) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedPaymentFilter by remember { mutableStateOf("ALL") } // ALL, CASH, UPI, CREDIT

    // Filter sales belonging to this trend item window
    val periodSales = remember(item, allSales) {
        allSales.filter {
            !it.sale.isHeld && it.sale.datetime in item.timestampMs..item.endTimestampMs
        }.sortedByDescending { it.sale.datetime }
    }

    // Filter expenses in this window
    val periodExpenses = remember(item, allExpenses) {
        allExpenses.filter {
            it.date in item.timestampMs..item.endTimestampMs
        }.sortedByDescending { it.date }
    }

    // Filter returns in this window
    val periodReturns = remember(item, allReturns) {
        allReturns.filter {
            it.saleReturn.datetime in item.timestampMs..item.endTimestampMs
        }.sortedByDescending { it.saleReturn.datetime }
    }

    // Cost map for product calculations
    val productCostMap = remember(allProducts) {
        allProducts.associate { it.id to it.costPrice }
    }

    // Aggregate sold products
    val aggregatedProducts = remember(periodSales, productCostMap) {
        val map = mutableMapOf<String, AggregatedProductItem>()
        periodSales.forEach { saleWithItems ->
            saleWithItems.items.forEach { sItem ->
                val key = sItem.productId.ifBlank { sItem.productNameEn.lowercase().trim() }
                val current = map[key]
                val costPrice = sItem.costPrice.takeIf { it > 0 } ?: (productCostMap[sItem.productId] ?: 0.0)
                val qtyInPrimary = if (sItem.unitType.equals("gram", ignoreCase = true)) sItem.quantity / 1000.0 else sItem.quantity
                val itemCost = qtyInPrimary * costPrice
                val itemProfit = (sItem.subtotal - itemCost).coerceAtLeast(0.0)

                if (current == null) {
                    map[key] = AggregatedProductItem(
                        productId = sItem.productId,
                        productNameEn = sItem.productNameEn,
                        productNameBn = sItem.productNameBn,
                        unitType = sItem.unitType,
                        totalQuantity = sItem.quantity,
                        totalRevenue = sItem.subtotal,
                        estimatedProfit = itemProfit,
                        ordersCount = 1
                    )
                } else {
                    map[key] = current.copy(
                        totalQuantity = current.totalQuantity + sItem.quantity,
                        totalRevenue = current.totalRevenue + sItem.subtotal,
                        estimatedProfit = current.estimatedProfit + itemProfit,
                        ordersCount = current.ordersCount + 1
                    )
                }
            }
        }
        map.values.sortedByDescending { it.totalRevenue }
    }

    val totalDayExpenses = remember(periodExpenses) { periodExpenses.sumOf { it.amount } }
    val netDayProfit = remember(item.profit, totalDayExpenses) { item.profit - totalDayExpenses }
    val profitMarginPct = remember(item.revenue, item.profit) {
        if (item.revenue > 0) (item.profit / item.revenue) * 100.0 else 0.0
    }
    val avgTicketSize = remember(item.revenue, periodSales) {
        if (periodSales.isNotEmpty()) item.revenue / periodSales.size else 0.0
    }

    // Filtered bills based on search query & payment filter
    val displayedSales = remember(periodSales, searchQuery, selectedPaymentFilter) {
        periodSales.filter { saleWithItems ->
            val matchesPayment = when (selectedPaymentFilter) {
                "CASH" -> saleWithItems.sale.paymentMode.equals("CASH", ignoreCase = true)
                "UPI" -> saleWithItems.sale.paymentMode.equals("UPI", ignoreCase = true)
                "CREDIT" -> saleWithItems.sale.paymentMode.equals("CREDIT", ignoreCase = true)
                else -> true
            }

            val matchesSearch = if (searchQuery.isBlank()) true else {
                val q = searchQuery.trim().lowercase()
                val customerName = saleWithItems.sale.customerName?.lowercase() ?: ""
                val billId = saleWithItems.sale.id.lowercase()
                val itemsMatch = saleWithItems.items.any {
                    it.productNameEn.lowercase().contains(q) || it.productNameBn.lowercase().contains(q)
                }
                customerName.contains(q) || billId.contains(q) || itemsMatch
            }

            matchesPayment && matchesSearch
        }
    }

    // Share report logic
    fun shareDayReport() {
        try {
            val storeName = StoreInfoManager.storeName.ifBlank { "Store" }
            val timeFormatter = SimpleDateFormat("hh:mm a", Locale.getDefault())
            val reportText = buildString {
                appendLine("📊 $storeName - ${if (isBn) "দিনের বিস্তারিত বিক্রয় রিপোর্ট" else "Day Sales Report"}")
                appendLine("📅 ${item.fullDateStr}")
                appendLine("----------------------------------------")
                appendLine("💰 ${if (isBn) "মোট বিক্রয় (Net Sales)" else "Total Net Revenue"}: ₹%.2f".format(item.revenue))
                appendLine("📈 ${if (isBn) "আনুমানিক লাভ (Gross Profit)" else "Estimated Profit"}: ₹%.2f (%.1f%%)".format(item.profit, profitMarginPct))
                appendLine("🧾 ${if (isBn) "মোট বিল সংখ্যা" else "Total Orders"}: ${periodSales.size} ${if (isBn) "টি" else "Bills"}")
                appendLine("💳 ${if (isBn) "গড় বিলের পরিমাণ" else "Avg Bill Size"}: ₹%.2f".format(avgTicketSize))
                appendLine("----------------------------------------")
                appendLine("💵 ${if (isBn) "নগদ বিক্রয় (Cash)" else "Cash Sales"}: ₹%.2f".format(item.cashAmount))
                appendLine("📱 ${if (isBn) "ইউপিআই বিক্রয় (UPI)" else "UPI Sales"}: ₹%.2f".format(item.upiAmount))
                if (item.creditAmount > 0) {
                    appendLine("📒 ${if (isBn) "বাকি বিক্রয় (Credit)" else "Credit Sales"}: ₹%.2f".format(item.creditAmount))
                }
                appendLine("----------------------------------------")
                appendLine("💸 ${if (isBn) "দিনের মোট খরচ (Expenses)" else "Total Day Expenses"}: ₹%.2f".format(totalDayExpenses))
                appendLine("🎯 ${if (isBn) "নিট লাভ (Net Profit after Expenses)" else "Net Profit (After Expenses)"}: ₹%.2f".format(netDayProfit))

                if (aggregatedProducts.isNotEmpty()) {
                    appendLine("----------------------------------------")
                    appendLine("🏆 ${if (isBn) "সর্বাধিক বিক্রীত পণ্য (Top Items)" else "Top Sold Products"}:")
                    aggregatedProducts.take(5).forEachIndexed { index, p ->
                        val name = if (isBn && p.productNameBn.isNotBlank()) p.productNameBn else p.productNameEn
                        appendLine("${index + 1}. $name - %.1f %s (₹%.0f)".format(p.totalQuantity, p.unitType, p.totalRevenue))
                    }
                }

                if (periodExpenses.isNotEmpty()) {
                    appendLine("----------------------------------------")
                    appendLine("📝 ${if (isBn) "খরচের বিবরণ" else "Expenses Breakdown"}:")
                    periodExpenses.forEach { exp ->
                        val noteStr = exp.note?.let { " ($it)" } ?: ""
                        appendLine("• ${exp.category}$noteStr: ₹%.2f".format(exp.amount))
                    }
                }
                appendLine("----------------------------------------")
                appendLine("Generated via $storeName POS")
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "$storeName Report - ${item.fullDateStr}")
                putExtra(Intent.EXTRA_TEXT, reportText)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(shareIntent, if (isBn) "রিপোর্ট শেয়ার করুন" else "Share Day Report via").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(context, "Could not share report: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f)
                .testTag("trend_day_details_dialog"),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = StorePrimary.copy(alpha = 0.12f),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.CalendarToday,
                                    contentDescription = null,
                                    tint = StorePrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        Column {
                            Text(
                                text = item.fullDateStr,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextDark
                            )
                            Text(
                                text = if (isBn)
                                    "সম্পূর্ণ বিস্তারিত: ${periodSales.size} টি বিল • ${aggregatedProducts.size} টি পণ্য"
                                else
                                    "Complete Details: ${periodSales.size} Orders • ${aggregatedProducts.size} Products",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton(
                            onClick = { shareDayReport() },
                            modifier = Modifier
                                .size(40.dp)
                                .testTag("share_day_summary_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share Report",
                                tint = StorePrimary
                            )
                        }

                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(40.dp)
                                .testTag("close_day_details_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = TextMuted
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Hero KPI Metrics Row (4 Cards)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Revenue
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = StoreGreenProfit.copy(alpha = 0.08f)),
                        border = BorderStroke(1.dp, StoreGreenProfit.copy(alpha = 0.25f))
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = if (isBn) "মোট বিক্রয়" else "Net Sales",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                            Text(
                                text = "₹%.2f".format(item.revenue),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = StoreGreenProfit
                            )
                            Text(
                                text = "${periodSales.size} ${if (isBn) "টি বিল" else "Bills"}",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                color = TextMuted
                            )
                        }
                    }

                    // Gross Profit
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = StorePrimary.copy(alpha = 0.08f)),
                        border = BorderStroke(1.dp, StorePrimary.copy(alpha = 0.25f))
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = if (isBn) "আনুমানিক লাভ" else "Est. Profit",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                            Text(
                                text = "₹%.2f".format(item.profit),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = StorePrimary
                            )
                            Text(
                                text = "%.1f%% ${if (isBn) "মার্জিন" else "Margin"}".format(profitMarginPct),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = StorePrimary
                            )
                        }
                    }

                    // Expenses
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = StoreRedAlert.copy(alpha = 0.08f)),
                        border = BorderStroke(1.dp, StoreRedAlert.copy(alpha = 0.25f))
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = if (isBn) "দিনের খরচ" else "Day Expense",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                            Text(
                                text = "₹%.2f".format(totalDayExpenses),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (totalDayExpenses > 0) StoreRedAlert else TextDark
                            )
                            Text(
                                text = "${periodExpenses.size} ${if (isBn) "টি এন্ট্রি" else "Entries"}",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                color = TextMuted
                            )
                        }
                    }

                    // Net Profit
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = if (netDayProfit >= 0) StoreGreenProfit.copy(alpha = 0.08f) else StoreRedAlert.copy(alpha = 0.08f)),
                        border = BorderStroke(1.dp, if (netDayProfit >= 0) StoreGreenProfit.copy(alpha = 0.3f) else StoreRedAlert.copy(alpha = 0.3f))
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = if (isBn) "নিট লাভ" else "Net Profit",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                            Text(
                                text = "₹%.2f".format(netDayProfit),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (netDayProfit >= 0) StoreGreenProfit else StoreRedAlert
                            )
                            Text(
                                text = if (isBn) "খরচ বাদ দিয়ে" else "After Expense",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                color = TextMuted
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Payment Modes Breakdown Pill Row
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceWarm),
                    border = BorderStroke(1.dp, StorePrimary.copy(alpha = 0.15f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isBn) "পেমেন্ট মাধ্যম:" else "Payments:",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextDark
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = StoreGreenProfit.copy(alpha = 0.12f)
                            ) {
                                Text(
                                    text = "Cash: ₹%.0f".format(item.cashAmount),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = StoreGreenProfit,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = StorePrimary.copy(alpha = 0.12f)
                            ) {
                                Text(
                                    text = "UPI: ₹%.0f".format(item.upiAmount),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = StorePrimary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }

                            if (item.creditAmount > 0) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = StoreOrangeWarning.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = "Khata: ₹%.0f".format(item.creditAmount),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = StoreOrangeWarning,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Navigation Tabs
                TabRow(
                    selectedTabIndex = selectedTab.ordinal,
                    containerColor = Color.Transparent,
                    contentColor = StorePrimary,
                    divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)) }
                ) {
                    DayDetailsTab.values().forEach { tab ->
                        val count = when (tab) {
                            DayDetailsTab.ORDERS -> periodSales.size
                            DayDetailsTab.PRODUCTS -> aggregatedProducts.size
                            DayDetailsTab.EXPENSES -> periodExpenses.size
                            DayDetailsTab.RETURNS -> periodReturns.size
                        }
                        val title = if (isBn) tab.titleBn else tab.titleEn
                        Tab(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = title,
                                        fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Medium,
                                        fontSize = 12.sp
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (selectedTab == tab) StorePrimary else SurfaceWarm
                                    ) {
                                        Text(
                                            text = "$count",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (selectedTab == tab) Color.White else TextMuted,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Tab Content Area
                Box(modifier = Modifier.weight(1f)) {
                    when (selectedTab) {
                        DayDetailsTab.ORDERS -> {
                            DayOrdersListSection(
                                sales = displayedSales,
                                totalSalesCount = periodSales.size,
                                searchQuery = searchQuery,
                                onSearchQueryChange = { searchQuery = it },
                                selectedPaymentFilter = selectedPaymentFilter,
                                onPaymentFilterChange = { selectedPaymentFilter = it },
                                isBn = isBn,
                                onViewReceipt = onViewSaleReceipt
                            )
                        }
                        DayDetailsTab.PRODUCTS -> {
                            DayProductsListSection(
                                products = aggregatedProducts,
                                isBn = isBn
                            )
                        }
                        DayDetailsTab.EXPENSES -> {
                            DayExpensesListSection(
                                expenses = periodExpenses,
                                totalExpense = totalDayExpenses,
                                cashSales = item.cashAmount,
                                isBn = isBn
                            )
                        }
                        DayDetailsTab.RETURNS -> {
                            DayReturnsListSection(
                                returns = periodReturns,
                                isBn = isBn
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Bottom Action Footer
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { shareDayReport() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isBn) "রিপোর্ট শেয়ার করুন" else "Share Report", fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = StorePrimary),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(if (isBn) "সম্পন্ন" else "Done", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun DayOrdersListSection(
    sales: List<SaleWithItems>,
    totalSalesCount: Int,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    selectedPaymentFilter: String,
    onPaymentFilterChange: (String) -> Unit,
    isBn: Boolean,
    onViewReceipt: (SaleWithItems) -> Unit
) {
    val timeFormatter = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Search bar & Payment Mode Filter Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                placeholder = {
                    Text(
                        if (isBn) "গ্রাহক বা পণ্য খুঁজুন..." else "Search customer, bill, item...",
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp), tint = TextMuted)
                },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { onSearchQueryChange("") }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", modifier = Modifier.size(14.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = StorePrimary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                )
            )

            // Payment Filter Chips
            listOf("ALL" to (if (isBn) "সব" else "All"), "CASH" to "Cash", "UPI" to "UPI", "CREDIT" to (if (isBn) "বাকি" else "Credit")).forEach { (mode, label) ->
                val isSel = selectedPaymentFilter == mode
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSel) StorePrimary else SurfaceWarm,
                    border = BorderStroke(1.dp, if (isSel) StorePrimary else MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.clickable { onPaymentFilterChange(mode) }
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSel) Color.White else TextDark,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (sales.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.ReceiptLong,
                        contentDescription = null,
                        tint = TextMuted.copy(alpha = 0.5f),
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (totalSalesCount == 0) {
                            if (isBn) "এই দিনে কোনো বিক্রয় বা বিল রেকর্ড করা হয়নি।" else "No sales or bills recorded on this date."
                        } else {
                            if (isBn) "কোনো বিল মেলেনি।" else "No matching bills found."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sales, key = { it.sale.id }) { saleWithItems ->
                    var isExpanded by remember { mutableStateOf(false) }
                    val sale = saleWithItems.sale
                    val items = saleWithItems.items
                    val formattedTime = remember(sale.datetime) { timeFormatter.format(Date(sale.datetime)) }
                    val shortBillId = sale.id.takeLast(6).uppercase()

                    val payBadgeColor = when (sale.paymentMode.uppercase()) {
                        "CASH" -> StoreGreenProfit
                        "UPI" -> StorePrimary
                        "CREDIT" -> StoreOrangeWarning
                        else -> StoreGreenProfit
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isExpanded = !isExpanded },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceWarm),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            // Top Row: Time, Bill ID, Payment Mode, Amount
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Text(
                                            text = formattedTime,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextDark,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }

                                    Text(
                                        text = "#$shortBillId",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = TextMuted
                                    )

                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = payBadgeColor.copy(alpha = 0.15f)
                                    ) {
                                        Text(
                                            text = sale.paymentMode.uppercase(),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = payBadgeColor,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                Text(
                                    text = "₹%.2f".format(sale.finalAmount),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = StoreGreenProfit
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            // Customer name & summary
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = null,
                                        tint = TextMuted,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = sale.customerName?.ifBlank { null } ?: (if (isBn) "খুচরা ক্রেতা (Walk-in)" else "Walk-in Customer"),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        color = TextDark,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Text(
                                    text = "${items.size} ${if (isBn) "টি পণ্য" else "Items"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted
                                )
                            }

                            // Items preview or expanded detail
                            AnimatedVisibility(visible = isExpanded) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 10.dp)
                                ) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                    Spacer(modifier = Modifier.height(8.dp))

                                    items.forEach { sItem ->
                                        val itemName = if (isBn && sItem.productNameBn.isNotBlank()) sItem.productNameBn else sItem.productNameEn
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 2.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                modifier = Modifier.weight(1f),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "•",
                                                    color = StorePrimary,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(end = 4.dp)
                                                )
                                                Text(
                                                    text = itemName,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = TextDark,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = "× ${sItem.quantity} ${sItem.unitType}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = TextMuted
                                                )
                                            }

                                            Text(
                                                text = "₹%.2f".format(sItem.subtotal),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = TextDark
                                            )
                                        }
                                    }

                                    if (sale.discount > 0) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = if (isBn) "ছাড় (Discount):" else "Discount Applied:",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = StoreRedAlert
                                            )
                                            Text(
                                                text = "-₹%.2f".format(sale.discount),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = StoreRedAlert
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    // View Receipt Button
                                    Button(
                                        onClick = { onViewReceipt(saleWithItems) },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(containerColor = StorePrimary),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(vertical = 6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Receipt,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (isBn) "সম্পূর্ণ ভাউচার / রসিদ দেখুন" else "View Full Bill Receipt",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayProductsListSection(
    products: List<AggregatedProductItem>,
    isBn: Boolean
) {
    if (products.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Inventory2,
                    contentDescription = null,
                    tint = TextMuted.copy(alpha = 0.5f),
                    modifier = Modifier.size(44.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isBn) "এই দিনে কোনো পণ্য বিক্রয় হয়নি।" else "No item sales recorded for this period.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted
                )
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header summary
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceWarm)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isBn) "মোট বিক্রীত পণ্যের সংখ্যা:" else "Unique Products Sold:",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                    Text(
                        text = "${products.size} ${if (isBn) "টি আইটেম" else "Items"}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextDark
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            val rankedProducts = remember(products) {
                products.mapIndexed { index, item -> Pair(index + 1, item) }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(rankedProducts, key = { (_, prod) -> prod.productId.ifBlank { prod.productNameEn } }, contentType = { "TREND_PRODUCT_ITEM" }) { (rank, prod) ->
                    val displayName = if (isBn && prod.productNameBn.isNotBlank()) prod.productNameBn else prod.productNameEn
                    val rankColor = when (rank) {
                        1 -> StoreGold
                        2 -> Color(0xFF9E9E9E)
                        3 -> Color(0xFFCD7F32)
                        else -> StorePrimary.copy(alpha = 0.15f)
                    }
                    val rankTextColor = when (rank) {
                        1, 2, 3 -> Color.White
                        else -> StorePrimary
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceWarm),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = rankColor,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = "#$rank",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = rankTextColor
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                Column {
                                    Text(
                                        text = displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = TextDark,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "%.1f %s • in %d %s".format(
                                            prod.totalQuantity,
                                            prod.unitType,
                                            prod.ordersCount,
                                            if (isBn) "টি বিলে" else "orders"
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMuted
                                    )
                                }
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "₹%.2f".format(prod.totalRevenue),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = StoreGreenProfit
                                )
                                Text(
                                    text = "+₹%.2f ${if (isBn) "লাভ" else "profit"}".format(prod.estimatedProfit),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = StorePrimary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayExpensesListSection(
    expenses: List<Expense>,
    totalExpense: Double,
    cashSales: Double,
    isBn: Boolean
) {
    val timeFormatter = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }
    val netCashInHand = remember(cashSales, totalExpense) { cashSales - totalExpense }

    Column(modifier = Modifier.fillMaxSize()) {
        // Cash Flow Balance Summary Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceWarm),
            border = BorderStroke(1.dp, StorePrimary.copy(alpha = 0.2f))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isBn) "নগদ বিক্রয় (Cash Sales):" else "Cash Sales Inflow:",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                    Text(
                        text = "₹%.2f".format(cashSales),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = StoreGreenProfit
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isBn) "মোট দৈনিক খরচ (Day Expenses):" else "Total Day Expenses:",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                    Text(
                        text = "-₹%.2f".format(totalExpense),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = StoreRedAlert
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isBn) "ক্যাশ ড্রয়ারে অবশিষ্ট নগদ:" else "Estimated Net Cash in Hand:",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = TextDark
                    )
                    Text(
                        text = "₹%.2f".format(netCashInHand),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (netCashInHand >= 0) StoreGreenProfit else StoreRedAlert
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (expenses.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.MoneyOff,
                        contentDescription = null,
                        tint = TextMuted.copy(alpha = 0.5f),
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (isBn) "এই দিনে কোনো খরচ রেকর্ড করা হয়নি।" else "No expenses logged for this date.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(expenses, key = { it.id }) { exp ->
                    val timeStr = timeFormatter.format(Date(exp.date))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceWarm),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = StoreRedAlert.copy(alpha = 0.12f),
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Receipt,
                                            contentDescription = null,
                                            tint = StoreRedAlert,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                Column {
                                    Text(
                                        text = exp.category,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = TextDark
                                    )
                                    val note = exp.note?.ifBlank { null }
                                    if (note != null) {
                                        Text(
                                            text = note,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextDark.copy(alpha = 0.8f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(
                                        text = timeStr,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp,
                                        color = TextMuted
                                    )
                                }
                            }

                            Text(
                                text = "₹%.2f".format(exp.amount),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = StoreRedAlert
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayReturnsListSection(
    returns: List<SaleReturnWithItems>,
    isBn: Boolean
) {
    val timeFormatter = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }

    if (returns.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.AssignmentReturn,
                    contentDescription = null,
                    tint = TextMuted.copy(alpha = 0.5f),
                    modifier = Modifier.size(44.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isBn) "এই দিনে কোনো পণ্য ফেরত বা বদল হয়নি।" else "No returns or replacements on this date.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(returns, key = { it.saleReturn.id }) { retWithItems ->
                val ret = retWithItems.saleReturn
                val timeStr = timeFormatter.format(Date(ret.datetime))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceWarm),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${if (isBn) "ফেরত নং" else "Return #"} ${ret.id.takeLast(6).uppercase()}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextDark
                            )
                            Text(
                                text = timeStr,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                        }

                        val noteText = ret.notes?.ifBlank { null }
                        if (noteText != null) {
                            Text(
                                text = "${if (isBn) "মন্তব্য:" else "Notes:"} $noteText",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        retWithItems.items.forEach { rItem ->
                            val prodName = if (isBn && rItem.productNameBn.isNotBlank()) rItem.productNameBn else rItem.productNameEn
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${if (rItem.isReplacement) "⤾ [Replace] " else "↩ [Return] "}$prodName × ${rItem.quantity}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (rItem.isReplacement) StorePrimary else StoreOrangeWarning
                                )
                                Text(
                                    text = "₹%.2f".format(rItem.subtotal),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = TextDark
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${if (isBn) "নিট রিফান্ড" else "Net Refund"} (${ret.refundPaymentMode}):",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = TextDark
                            )
                            Text(
                                text = "₹%.2f".format(ret.netAmount),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = StoreRedAlert
                            )
                        }
                    }
                }
            }
        }
    }
}
