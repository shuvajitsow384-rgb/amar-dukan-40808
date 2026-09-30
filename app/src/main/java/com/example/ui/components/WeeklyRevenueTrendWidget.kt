package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.dao.SaleWithItems
import com.example.ui.theme.*
import com.example.utils.LanguageManager
import com.example.utils.StaffManager
import com.example.viewmodel.StoreViewModel
import java.text.SimpleDateFormat
import java.util.*

enum class TrendViewMode {
    LAST_7_DAYS,
    LAST_4_WEEKS
}

enum class ChartType {
    LINE_CHART,
    BAR_CHART
}

data class DailyTrendItem(
    val label: String,            // e.g. "Mon", "08 Aug"
    val fullDateStr: String,      // e.g. "Thursday, 08 Aug 2026"
    val revenue: Double,          // total revenue for day
    val profit: Double,           // profit for day
    val orderCount: Int,          // sales count
    val cashAmount: Double,
    val upiAmount: Double,
    val creditAmount: Double,
    val timestampMs: Long,
    val endTimestampMs: Long = timestampMs + (24 * 60 * 60 * 1000L) - 1L
)

@Composable
fun WeeklyRevenueTrendWidget(
    viewModel: StoreViewModel,
    modifier: Modifier = Modifier
) {
    val currentFirestoreRole by viewModel.currentFirestoreUserRole.collectAsState()
    val isAdmin = currentFirestoreRole?.isAdmin ?: StaffManager.isOwner()

    // Enforce Admin-only restriction
    if (!isAdmin) {
        Card(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = StoreGold.copy(alpha = 0.15f),
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.AdminPanelSettings,
                            contentDescription = "Admin Restricted",
                            tint = StoreGold,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (LanguageManager.isBengali) "সাপ্তাহিক বিক্রয় ট্রেন্ড (শুধুমাত্র অ্যাডমিন)" else "Weekly Sales Trend (Admin Only)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextDark
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = StoreGold.copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = "ADMIN",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = StoreGold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (LanguageManager.isBengali)
                            "ব্যবসার বিক্রয় ট্রেন্ড লাইন চার্ট ও পারফর্মেন্স অ্যানালিটিক্স শুধুমাত্র অ্যাডমিন অ্যাকাউন্টের জন্য উন্মুক্ত।"
                        else
                            "Sales trend charts & weekly revenue analytics are restricted to Admin / Owner accounts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }
            }
        }
        return
    }

    val allSales by viewModel.allSales.collectAsState()
    val allProducts by viewModel.allProducts.collectAsState()
    val allReturns by viewModel.allReturns.collectAsState()
    val allExpenses by viewModel.allExpenses.collectAsState()
    val allCustomers by viewModel.allCustomers.collectAsState()

    var viewMode by remember { mutableStateOf(TrendViewMode.LAST_7_DAYS) }
    var chartType by remember { mutableStateOf(ChartType.LINE_CHART) }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    var showProfitCurve by remember { mutableStateOf(true) }

    var selectedTrendItemForDetails by remember { mutableStateOf<DailyTrendItem?>(null) }
    var selectedSaleForReceipt by remember { mutableStateOf<SaleWithItems?>(null) }
    var showEditPdfFormatDialog by remember { mutableStateOf(false) }

    // Map products cost price for profit calculation
    val productCostMap = remember(allProducts) {
        allProducts.associate { it.id to it.costPrice }
    }

    // Process sales into daily trend items (Last 7 Days)
    val dailyTrendList = remember(allSales, allReturns, productCostMap) {
        val dayFormat = SimpleDateFormat("EEE", Locale.getDefault())
        val fullDateFormat = SimpleDateFormat("EEEE, dd MMM yyyy", Locale.getDefault())

        val list = mutableListOf<DailyTrendItem>()

        for (i in 6 downTo 0) {
            val dayCal = Calendar.getInstance()
            dayCal.add(Calendar.DAY_OF_YEAR, -i)
            dayCal.set(Calendar.HOUR_OF_DAY, 0)
            dayCal.set(Calendar.MINUTE, 0)
            dayCal.set(Calendar.SECOND, 0)
            dayCal.set(Calendar.MILLISECOND, 0)
            val startMs = dayCal.timeInMillis

            dayCal.set(Calendar.HOUR_OF_DAY, 23)
            dayCal.set(Calendar.MINUTE, 59)
            dayCal.set(Calendar.SECOND, 59)
            dayCal.set(Calendar.MILLISECOND, 999)
            val endMs = dayCal.timeInMillis

            val daySales = allSales.filter {
                !it.sale.isHeld && it.sale.datetime in startMs..endMs
            }

            val totalRev = daySales.sumOf { it.sale.finalAmount }
            var totalProfit = 0.0
            var cashAmt = 0.0
            var upiAmt = 0.0
            var creditAmt = 0.0

            daySales.forEach { saleWithItems ->
                when (saleWithItems.sale.paymentMode.uppercase()) {
                    "CASH" -> cashAmt += saleWithItems.sale.finalAmount
                    "UPI" -> upiAmt += saleWithItems.sale.finalAmount
                    "CREDIT" -> creditAmt += saleWithItems.sale.finalAmount
                    else -> cashAmt += saleWithItems.sale.finalAmount
                }

                val itemsCost = saleWithItems.items.sumOf { item ->
                    val costPrice = productCostMap[item.productId] ?: 0.0
                    val qtyInPrimary = if (item.unitType.equals("gram", ignoreCase = true)) item.quantity / 1000.0 else item.quantity
                    qtyInPrimary * costPrice
                }
                totalProfit += (saleWithItems.sale.finalAmount - itemsCost).coerceAtLeast(0.0)
            }

            // Adjust for returns on this day
            val dayReturns = allReturns.filter { it.saleReturn.datetime in startMs..endMs }
            var netRev = totalRev
            dayReturns.forEach { ret ->
                netRev -= ret.saleReturn.totalReturnedAmount
                netRev += ret.saleReturn.totalReplacementAmount

                val net = ret.saleReturn.netAmount
                when (ret.saleReturn.refundPaymentMode.uppercase()) {
                    "CASH" -> cashAmt -= net
                    "UPI" -> upiAmt -= net
                    "CREDIT" -> creditAmt -= net
                    else -> cashAmt -= net
                }

                ret.items.forEach { rItem ->
                    val costPrice = productCostMap[rItem.productId] ?: (rItem.unitPrice * 0.7)
                    val profitVal = (rItem.unitPrice - costPrice) * rItem.quantity
                    if (rItem.isReplacement) {
                        totalProfit += profitVal
                    } else {
                        totalProfit -= profitVal
                    }
                }
            }
            netRev = netRev.coerceAtLeast(0.0)
            totalProfit = totalProfit.coerceAtLeast(0.0)

            val label = if (i == 0) "Today" else dayFormat.format(dayCal.time)
            list.add(
                DailyTrendItem(
                    label = label,
                    fullDateStr = fullDateFormat.format(dayCal.time),
                    revenue = netRev,
                    profit = totalProfit,
                    orderCount = daySales.size,
                    cashAmount = cashAmt.coerceAtLeast(0.0),
                    upiAmount = upiAmt.coerceAtLeast(0.0),
                    creditAmount = creditAmt.coerceAtLeast(0.0),
                    timestampMs = startMs,
                    endTimestampMs = endMs
                )
            )
        }
        list
    }

    // Process sales into 4-Week trend items
    val weeklyTrendList = remember(allSales, allReturns, productCostMap) {
        val weekFormat = SimpleDateFormat("dd MMM", Locale.getDefault())
        val list = mutableListOf<DailyTrendItem>()

        for (i in 3 downTo 0) {
            val startCal = Calendar.getInstance()
            startCal.add(Calendar.WEEK_OF_YEAR, -i)
            startCal.set(Calendar.DAY_OF_WEEK, startCal.firstDayOfWeek)
            startCal.set(Calendar.HOUR_OF_DAY, 0)
            startCal.set(Calendar.MINUTE, 0)
            startCal.set(Calendar.SECOND, 0)
            startCal.set(Calendar.MILLISECOND, 0)
            val startMs = startCal.timeInMillis

            val endCal = Calendar.getInstance()
            endCal.timeInMillis = startMs
            endCal.add(Calendar.DAY_OF_YEAR, 6)
            endCal.set(Calendar.HOUR_OF_DAY, 23)
            endCal.set(Calendar.MINUTE, 59)
            endCal.set(Calendar.SECOND, 59)
            endCal.set(Calendar.MILLISECOND, 999)
            val endMs = endCal.timeInMillis

            val weekSales = allSales.filter {
                !it.sale.isHeld && it.sale.datetime in startMs..endMs
            }

            val totalRev = weekSales.sumOf { it.sale.finalAmount }
            var totalProfit = 0.0
            var cashAmt = 0.0
            var upiAmt = 0.0
            var creditAmt = 0.0

            weekSales.forEach { saleWithItems ->
                when (saleWithItems.sale.paymentMode.uppercase()) {
                    "CASH" -> cashAmt += saleWithItems.sale.finalAmount
                    "UPI" -> upiAmt += saleWithItems.sale.finalAmount
                    "CREDIT" -> creditAmt += saleWithItems.sale.finalAmount
                    else -> cashAmt += saleWithItems.sale.finalAmount
                }

                val itemsCost = saleWithItems.items.sumOf { item ->
                    val costPrice = productCostMap[item.productId] ?: 0.0
                    val qtyInPrimary = if (item.unitType.equals("gram", ignoreCase = true)) item.quantity / 1000.0 else item.quantity
                    qtyInPrimary * costPrice
                }
                totalProfit += (saleWithItems.sale.finalAmount - itemsCost).coerceAtLeast(0.0)
            }

            // Adjust for returns in this week
            val weekReturns = allReturns.filter { it.saleReturn.datetime in startMs..endMs }
            var netRev = totalRev
            weekReturns.forEach { ret ->
                netRev -= ret.saleReturn.totalReturnedAmount
                netRev += ret.saleReturn.totalReplacementAmount

                val net = ret.saleReturn.netAmount
                when (ret.saleReturn.refundPaymentMode.uppercase()) {
                    "CASH" -> cashAmt -= net
                    "UPI" -> upiAmt -= net
                    "CREDIT" -> creditAmt -= net
                    else -> cashAmt -= net
                }

                ret.items.forEach { rItem ->
                    val costPrice = productCostMap[rItem.productId] ?: (rItem.unitPrice * 0.7)
                    val profitVal = (rItem.unitPrice - costPrice) * rItem.quantity
                    if (rItem.isReplacement) {
                        totalProfit += profitVal
                    } else {
                        totalProfit -= profitVal
                    }
                }
            }
            netRev = netRev.coerceAtLeast(0.0)
            totalProfit = totalProfit.coerceAtLeast(0.0)

            val label = if (i == 0) "This Wk" else "Wk -${i}"
            val fullStr = "Week of ${weekFormat.format(startCal.time)} - ${weekFormat.format(endCal.time)}"

            list.add(
                DailyTrendItem(
                    label = label,
                    fullDateStr = fullStr,
                    revenue = netRev,
                    profit = totalProfit,
                    orderCount = weekSales.size,
                    cashAmount = cashAmt.coerceAtLeast(0.0),
                    upiAmount = upiAmt.coerceAtLeast(0.0),
                    creditAmount = creditAmt.coerceAtLeast(0.0),
                    timestampMs = startMs,
                    endTimestampMs = endMs
                )
            )
        }
        list
    }

    val currentList = if (viewMode == TrendViewMode.LAST_7_DAYS) dailyTrendList else weeklyTrendList

    val totalPeriodRevenue = remember(currentList) { currentList.sumOf { it.revenue } }
    val totalPeriodOrders = remember(currentList) { currentList.sumOf { it.orderCount } }
    val avgDailyRevenue = remember(currentList, totalPeriodRevenue) {
        if (currentList.isNotEmpty()) totalPeriodRevenue / currentList.size else 0.0
    }
    val peakItem = remember(currentList) { currentList.maxByOrNull { it.revenue } }

    // Calculate growth compared to previous 7 days
    val growthPercentage = remember(allSales) {
        val now = System.currentTimeMillis()
        val sevenDaysMs = 7L * 24 * 3600 * 1000
        val currentPeriodRev = allSales.filter { !it.sale.isHeld && it.sale.datetime in (now - sevenDaysMs)..now }.sumOf { it.sale.finalAmount }
        val prevPeriodRev = allSales.filter { !it.sale.isHeld && it.sale.datetime in (now - 2 * sevenDaysMs)..(now - sevenDaysMs) }.sumOf { it.sale.finalAmount }

        if (prevPeriodRev > 0) {
            ((currentPeriodRev - prevPeriodRev) / prevPeriodRev) * 100.0
        } else if (currentPeriodRev > 0) {
            100.0
        } else {
            0.0
        }
    }

    val maxVal = remember(currentList) {
        (currentList.maxOfOrNull { it.revenue } ?: 1.0).coerceAtLeast(100.0)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, StorePrimary.copy(alpha = 0.2f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Top Header & Mode Switcher
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = StorePrimary.copy(alpha = 0.12f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ShowChart,
                            contentDescription = "Revenue Trend",
                            tint = StorePrimary,
                            modifier = Modifier
                                .padding(8.dp)
                                .size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (viewMode == TrendViewMode.LAST_7_DAYS) "Weekly Sales Trend" else "4-Week Sales Trend",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextDark,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = StoreGold.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    text = "ADMIN",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = StoreGold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                        Text(
                            text = "Interactive line & revenue analytics",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Mode Toggle Switcher (7D vs 4W)
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = SurfaceWarm,
                    border = BorderStroke(1.dp, StorePrimary.copy(alpha = 0.2f))
                ) {
                    Row(
                        modifier = Modifier.padding(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = if (viewMode == TrendViewMode.LAST_7_DAYS) StorePrimary else Color.Transparent,
                            modifier = Modifier.clickable {
                                viewMode = TrendViewMode.LAST_7_DAYS
                                selectedIndex = null
                            }
                        ) {
                            Text(
                                text = "7D",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (viewMode == TrendViewMode.LAST_7_DAYS) Color.White else TextMuted,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                maxLines = 1,
                                softWrap = false
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = if (viewMode == TrendViewMode.LAST_4_WEEKS) StorePrimary else Color.Transparent,
                            modifier = Modifier.clickable {
                                viewMode = TrendViewMode.LAST_4_WEEKS
                                selectedIndex = null
                            }
                        ) {
                            Text(
                                text = "4W",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (viewMode == TrendViewMode.LAST_4_WEEKS) Color.White else TextMuted,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Key Metrics Summary Cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Metric 1: Total Revenue
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = StoreGreenProfit.copy(alpha = 0.08f)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "Total Revenue",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                        Text(
                            text = "₹%.0f".format(totalPeriodRevenue),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = StoreGreenProfit
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (growthPercentage >= 0) Icons.Default.TrendingUp else Icons.Default.TrendingDown,
                                contentDescription = null,
                                tint = if (growthPercentage >= 0) StoreGreenProfit else StoreRedAlert,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = "%+.1f%% vs prev".format(growthPercentage),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (growthPercentage >= 0) StoreGreenProfit else StoreRedAlert
                            )
                        }
                    }
                }

                // Metric 2: Daily Avg
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = StorePrimary.copy(alpha = 0.08f)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "Average",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                        Text(
                            text = "₹%.0f".format(avgDailyRevenue),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = StorePrimary
                        )
                        Text(
                            text = "$totalPeriodOrders Orders",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            color = TextMuted
                        )
                    }
                }

                // Metric 3: Peak Revenue Day
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = StoreSaffronAccent.copy(alpha = 0.12f)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "Peak Day",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                        Text(
                            text = peakItem?.label ?: "-",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = StoreSaffronAccent
                        )
                        Text(
                            text = "₹%.0f".format(peakItem?.revenue ?: 0.0),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = StoreSaffronAccent
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Chart Legend & Controls (Type toggle & Profit toggle)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(StorePrimary, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Revenue (₹)",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = StorePrimary
                        )
                    }

                    if (chartType == ChartType.LINE_CHART) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { showProfitCurve = !showProfitCurve }
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(if (showProfitCurve) StoreGreenProfit else TextMuted.copy(alpha = 0.5f), CircleShape)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Profit (₹)",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (showProfitCurve) StoreGreenProfit else TextMuted
                            )
                        }
                    }
                }

                // Chart Type Toggle (Line vs Bar)
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = SurfaceWarm,
                    border = BorderStroke(1.dp, StorePrimary.copy(alpha = 0.2f))
                ) {
                    Row(
                        modifier = Modifier.padding(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (chartType == ChartType.LINE_CHART) StorePrimary else Color.Transparent,
                            modifier = Modifier.clickable { chartType = ChartType.LINE_CHART }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ShowChart,
                                    contentDescription = "Line Chart",
                                    tint = if (chartType == ChartType.LINE_CHART) Color.White else TextMuted,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "Line",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (chartType == ChartType.LINE_CHART) Color.White else TextMuted
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (chartType == ChartType.BAR_CHART) StorePrimary else Color.Transparent,
                            modifier = Modifier.clickable { chartType = ChartType.BAR_CHART }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.BarChart,
                                    contentDescription = "Bar Chart",
                                    tint = if (chartType == ChartType.BAR_CHART) Color.White else TextMuted,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "Bar",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (chartType == ChartType.BAR_CHART) Color.White else TextMuted
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Chart Canvas / Container
            if (chartType == ChartType.LINE_CHART) {
                // Recharts-inspired Jetpack Compose Line Chart with Area Spline & Glowing Nodes
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .background(SurfaceWarm.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    val primaryColor = StorePrimary
                    val profitColor = StoreGreenProfit
                    val saffronColor = StoreSaffronAccent
                    val gridColor = Color.LightGray.copy(alpha = 0.5f)

                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(currentList) {
                                detectTapGestures { offset ->
                                    val count = currentList.size
                                    if (count > 0) {
                                        val padH = 20f
                                        val usableWidth = size.width - 2 * padH
                                        val step = if (count > 1) usableWidth / (count - 1) else 0f
                                        val clickedIndex = if (count > 1) {
                                            ((offset.x - padH + step / 2) / step).toInt().coerceIn(0, count - 1)
                                        } else {
                                            0
                                        }
                                        selectedIndex = if (selectedIndex == clickedIndex) null else clickedIndex
                                    }
                                }
                            }
                    ) {
                        val count = currentList.size
                        if (count == 0) return@Canvas

                        val padH = 20f
                        val padTop = 15f
                        val padBottom = 25f
                        val usableWidth = size.width - 2 * padH
                        val usableHeight = size.height - padTop - padBottom
                        val stepX = if (count > 1) usableWidth / (count - 1) else 0f

                        // Draw Grid Lines
                        val dashedEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                        val gridLevels = listOf(0.25f, 0.5f, 0.75f, 1.0f)
                        gridLevels.forEach { ratio ->
                            val y = size.height - padBottom - (usableHeight * ratio)
                            drawLine(
                                color = gridColor,
                                start = Offset(padH, y),
                                end = Offset(size.width - padH, y),
                                strokeWidth = 1f,
                                pathEffect = dashedEffect
                            )
                        }

                        // Baseline
                        drawLine(
                            color = gridColor.copy(alpha = 0.8f),
                            start = Offset(padH, size.height - padBottom),
                            end = Offset(size.width - padH, size.height - padBottom),
                            strokeWidth = 1.5f
                        )

                        // Calculate Revenue Points
                        val revPoints = currentList.mapIndexed { idx, item ->
                            val x = padH + idx * stepX
                            val y = size.height - padBottom - ((item.revenue / maxVal).toFloat() * usableHeight).coerceIn(0f, usableHeight)
                            Offset(x, y)
                        }

                        // Calculate Profit Points
                        val profitPoints = currentList.mapIndexed { idx, item ->
                            val x = padH + idx * stepX
                            val y = size.height - padBottom - ((item.profit / maxVal).toFloat() * usableHeight).coerceIn(0f, usableHeight)
                            Offset(x, y)
                        }

                        // 1. Draw Revenue Area Gradient (Recharts Area curve style)
                        if (revPoints.isNotEmpty()) {
                            val areaPath = Path()
                            areaPath.moveTo(revPoints.first().x, size.height - padBottom)
                            areaPath.lineTo(revPoints.first().x, revPoints.first().y)

                            for (i in 0 until revPoints.size - 1) {
                                val p0 = revPoints[i]
                                val p1 = revPoints[i + 1]
                                val cx1 = p0.x + (p1.x - p0.x) / 2f
                                val cx2 = cx1
                                areaPath.cubicTo(cx1, p0.y, cx2, p1.y, p1.x, p1.y)
                            }

                            areaPath.lineTo(revPoints.last().x, size.height - padBottom)
                            areaPath.close()

                            drawPath(
                                path = areaPath,
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        primaryColor.copy(alpha = 0.28f),
                                        primaryColor.copy(alpha = 0.02f)
                                    ),
                                    startY = padTop,
                                    endY = size.height - padBottom
                                )
                            )

                            // 2. Draw Revenue Stroke Line
                            val linePath = Path()
                            linePath.moveTo(revPoints.first().x, revPoints.first().y)
                            for (i in 0 until revPoints.size - 1) {
                                val p0 = revPoints[i]
                                val p1 = revPoints[i + 1]
                                val cx1 = p0.x + (p1.x - p0.x) / 2f
                                val cx2 = cx1
                                linePath.cubicTo(cx1, p0.y, cx2, p1.y, p1.x, p1.y)
                            }

                            drawPath(
                                path = linePath,
                                color = primaryColor,
                                style = Stroke(
                                    width = 3.dp.toPx(),
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )
                        }

                        // 3. Draw Optional Profit Line
                        if (showProfitCurve && profitPoints.isNotEmpty()) {
                            val profitLinePath = Path()
                            profitLinePath.moveTo(profitPoints.first().x, profitPoints.first().y)
                            for (i in 0 until profitPoints.size - 1) {
                                val p0 = profitPoints[i]
                                val p1 = profitPoints[i + 1]
                                val cx1 = p0.x + (p1.x - p0.x) / 2f
                                val cx2 = cx1
                                profitLinePath.cubicTo(cx1, p0.y, cx2, p1.y, p1.x, p1.y)
                            }

                            drawPath(
                                path = profitLinePath,
                                color = profitColor,
                                style = Stroke(
                                    width = 2.dp.toPx(),
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                                )
                            )
                        }

                        // 4. Draw Circular Nodes
                        revPoints.forEachIndexed { index, pt ->
                            val isSelected = selectedIndex == index
                            val item = currentList[index]

                            // Outer halo / glow for selected or peak
                            if (isSelected) {
                                drawCircle(
                                    color = saffronColor.copy(alpha = 0.35f),
                                    radius = 11.dp.toPx(),
                                    center = pt
                                )
                            }

                            // Node background
                            drawCircle(
                                color = Color.White,
                                radius = if (isSelected) 6.dp.toPx() else 4.dp.toPx(),
                                center = pt
                            )

                            // Node inner fill
                            drawCircle(
                                color = if (isSelected) saffronColor else primaryColor,
                                radius = if (isSelected) 4.5f.dp.toPx() else 3.dp.toPx(),
                                center = pt
                            )

                            // Selected indicator vertical line
                            if (isSelected) {
                                drawLine(
                                    color = saffronColor,
                                    start = Offset(pt.x, padTop),
                                    end = Offset(pt.x, size.height - padBottom),
                                    strokeWidth = 1.5f,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                                )
                            }
                        }
                    }

                    // Bottom X-Axis Labels
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        currentList.forEachIndexed { index, item ->
                            val isSelected = selectedIndex == index
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (isSelected) StorePrimary else Color.Transparent,
                                modifier = Modifier.clickable {
                                    selectedIndex = if (selectedIndex == index) null else index
                                }
                            ) {
                                Text(
                                    text = item.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 9.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) StoreOnPrimary else TextDark,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                }
            } else {
                // Bar Chart Area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .background(SurfaceWarm.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        currentList.forEachIndexed { index, item ->
                            val isSelected = selectedIndex == index
                            val heightRatio = if (maxVal > 0) (item.revenue / maxVal).toFloat().coerceIn(0.05f, 1f) else 0.05f

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clickable {
                                        selectedIndex = if (selectedIndex == index) null else index
                                    },
                                verticalArrangement = Arrangement.Bottom
                            ) {
                                // Top Value Label on Bar
                                if (item.revenue > 0) {
                                    Text(
                                        text = if (item.revenue >= 1000) "%.1fk".format(item.revenue / 1000.0) else "%.0f".format(item.revenue),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 9.sp,
                                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                        color = if (isSelected) StorePrimary else TextMuted
                                    )
                                } else {
                                    Text(
                                        text = "0",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 9.sp,
                                        color = TextMuted.copy(alpha = 0.5f)
                                    )
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                // Custom Visual Bar Pillar with Gradient Fill
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(if (currentList.size > 5) 0.65f else 0.5f)
                                        .fillMaxHeight(heightRatio * 0.72f)
                                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                        .background(
                                            brush = Brush.verticalGradient(
                                                colors = if (isSelected) {
                                                    listOf(StoreSaffronAccent, StoreOrangeWarning)
                                                } else if (item.revenue == (peakItem?.revenue ?: -1.0) && item.revenue > 0) {
                                                    listOf(StoreGreenProfit, StoreGreenProfit.copy(alpha = 0.7f))
                                                } else {
                                                    listOf(StorePrimary, StorePrimary.copy(alpha = 0.5f))
                                                }
                                            )
                                        )
                                    )

                                Spacer(modifier = Modifier.height(6.dp))

                                // Bottom Day Label
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (isSelected) StorePrimary else Color.Transparent
                                ) {
                                    Text(
                                        text = item.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) StoreOnPrimary else TextDark,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Expanded Tooltip / Detail Card for Selected Day
            AnimatedVisibility(
                visible = selectedIndex != null && selectedIndex!! < currentList.size,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                if (selectedIndex != null && selectedIndex!! < currentList.size) {
                    val sel = currentList[selectedIndex!!]
                    Spacer(modifier = Modifier.height(10.dp))
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedTrendItemForDetails = sel },
                        colors = CardDefaults.cardColors(containerColor = SurfaceWarm),
                        border = BorderStroke(1.dp, StorePrimary.copy(alpha = 0.35f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        ) {
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
                                        imageVector = Icons.Default.Event,
                                        contentDescription = null,
                                        tint = StorePrimary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = sel.fullDateStr,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = TextDark
                                    )
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    FilledTonalIconButton(
                                        onClick = { selectedTrendItemForDetails = sel },
                                        modifier = Modifier.size(28.dp),
                                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                                            containerColor = StorePrimary.copy(alpha = 0.12f),
                                            contentColor = StorePrimary
                                        )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.OpenInFull,
                                            contentDescription = "Show All Details",
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(4.dp))

                                    IconButton(
                                        onClick = { selectedIndex = null },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Close detail", tint = TextMuted)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("Revenue", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                    Text(
                                        "₹%.2f".format(sel.revenue),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = StoreGreenProfit
                                    )
                                }

                                Column {
                                    Text("Estimated Profit", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                    Text(
                                        "₹%.2f".format(sel.profit),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = StorePrimary
                                    )
                                }

                                Column {
                                    Text("Total Orders", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                    Text(
                                        "${sel.orderCount} Bills",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = TextDark
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Payment mode pill tags
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = StoreGreenProfit.copy(alpha = 0.12f)
                                ) {
                                    Text(
                                        text = "Cash: ₹%.0f".format(sel.cashAmount),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp,
                                        color = StoreGreenProfit,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }

                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = StorePrimary.copy(alpha = 0.12f)
                                ) {
                                    Text(
                                        text = "UPI: ₹%.0f".format(sel.upiAmount),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp,
                                        color = StorePrimary,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }

                                if (sel.creditAmount > 0) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = StoreOrangeWarning.copy(alpha = 0.15f)
                                    ) {
                                        Text(
                                            text = "Credit (Khata): ₹%.0f".format(sel.creditAmount),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 10.sp,
                                            color = StoreOrangeWarning,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Action button: Click to show all details
                            Button(
                                onClick = { selectedTrendItemForDetails = sel },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = StorePrimary),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ReceiptLong,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (LanguageManager.isBengali)
                                        "সম্পূর্ণ বিস্তারিত দেখুন (${sel.orderCount} টি বিল)"
                                    else
                                        "View All Details (${sel.orderCount} Bills)",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Full Trend Period / Day Details Dialog
    selectedTrendItemForDetails?.let { item ->
        TrendDayDetailsDialog(
            item = item,
            allSales = allSales,
            allReturns = allReturns,
            allExpenses = allExpenses,
            allProducts = allProducts,
            allCustomers = allCustomers,
            onDismiss = { selectedTrendItemForDetails = null },
            onViewSaleReceipt = { saleWithItems ->
                selectedSaleForReceipt = saleWithItems
            }
        )
    }

    // Bill Detail Receipt Modal
    selectedSaleForReceipt?.let { saleWithItems ->
        TransactionSuccessReceiptModal(
            saleWithItems = saleWithItems,
            customers = allCustomers,
            printerStatusMessage = viewModel.printerStatusMessage,
            onDismiss = { selectedSaleForReceipt = null },
            onPrintThermal = { viewModel.printCurrentSale(saleWithItems) },
            onPrintThermalWithLang = { isBn -> viewModel.printCurrentSale(saleWithItems, isBengali = isBn) },
            onNewSale = { selectedSaleForReceipt = null },
            onOpenPdfSettings = { showEditPdfFormatDialog = true }
        )
    }

    if (showEditPdfFormatDialog) {
        EditPdfFormatDialog(onDismiss = { showEditPdfFormatDialog = false })
    }
}

