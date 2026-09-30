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
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import com.example.utils.LanguageManager
import com.example.utils.StaffManager
import com.example.viewmodel.StoreViewModel
import java.text.SimpleDateFormat
import java.util.*

enum class MonthlyTrendSpan {
    LAST_6_MONTHS,
    LAST_12_MONTHS
}

enum class MonthlyChartType {
    AREA_SPLINE,
    BAR_COMPARISON
}

data class MonthlyProfitItem(
    val monthLabel: String,         // e.g. "Mar", "Aug"
    val fullMonthName: String,      // e.g. "August 2026"
    val grossRevenue: Double,       // Total Sales
    val cogs: Double,               // Cost of Goods Sold
    val grossProfit: Double,        // Revenue - COGS
    val expenses: Double,           // Operating Expenses (non-salary)
    val salaryDisbursed: Double,    // Employee Salaries paid
    val stockLoss: Double = 0.0,    // Damaged/Expired/Wastage
    val totalExpenseOutflow: Double,// Expenses + Salaries
    val netProfit: Double,          // Gross Profit - Total Expenses - Stock Loss
    val marginPercent: Double,      // (Net Profit / Revenue) * 100
    val expenseCategories: Map<String, Double>,
    val orderCount: Int,
    val startTimestamp: Long,
    val endTimestamp: Long
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MonthlyProfitTrendWidget(
    viewModel: StoreViewModel,
    modifier: Modifier = Modifier
) {
    val currentFirestoreRole by viewModel.currentFirestoreUserRole.collectAsState()
    val isAdmin = currentFirestoreRole?.isAdmin ?: StaffManager.isOwner()

    // RBAC: Monthly Profit & Expense Analytics restricted to Admin/Owner
    if (!isAdmin) {
        return
    }

    val isBn = LanguageManager.isBengali
    val allSales by viewModel.allSales.collectAsState()
    val allProducts by viewModel.allProducts.collectAsState()
    val allReturns by viewModel.allReturns.collectAsState()
    val allExpenses by viewModel.allExpenses.collectAsState()
    val allSalaryPayments by viewModel.allSalaryPayments.collectAsState()
    val allStockOuts by viewModel.allStockOuts.collectAsState()

    var trendSpan by remember { mutableStateOf(MonthlyTrendSpan.LAST_6_MONTHS) }
    var chartType by remember { mutableStateOf(MonthlyChartType.AREA_SPLINE) }
    var selectedMonthIndex by remember { mutableStateOf<Int?>(null) }
    
    // Metric Series visibility toggles (Recharts style)
    var showNetProfit by remember { mutableStateOf(true) }
    var showRevenue by remember { mutableStateOf(true) }
    var showExpenses by remember { mutableStateOf(true) }

    // Map products cost price for precise margin calculation
    val productCostMap = remember(allProducts) {
        allProducts.associate { it.id to it.costPrice }
    }

    // Process past 6 or 12 months data based on Ledger, Sales & Expense tables
    val monthlyDataList = remember(allSales, allReturns, allExpenses, allSalaryPayments, allStockOuts, productCostMap, trendSpan) {
        val monthCount = if (trendSpan == MonthlyTrendSpan.LAST_6_MONTHS) 6 else 12
        val list = mutableListOf<MonthlyProfitItem>()
        val monthLabelFormat = SimpleDateFormat("MMM", Locale.getDefault())
        val fullMonthFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())

        for (i in (monthCount - 1) downTo 0) {
            val cal = Calendar.getInstance()
            cal.add(Calendar.MONTH, -i)
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val startMs = cal.timeInMillis

            val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            cal.set(Calendar.DAY_OF_MONTH, maxDay)
            cal.set(Calendar.HOUR_OF_DAY, 23)
            cal.set(Calendar.MINUTE, 59)
            cal.set(Calendar.SECOND, 59)
            cal.set(Calendar.MILLISECOND, 999)
            val endMs = cal.timeInMillis

            // 1. Calculate Monthly Sales & COGS
            val monthSales = allSales.filter { !it.sale.isHeld && it.sale.datetime in startMs..endMs }
            val grossSales = monthSales.sumOf { it.sale.finalAmount }
            var totalCogs = 0.0

            monthSales.forEach { saleWithItems ->
                val saleCost = saleWithItems.items.sumOf { item ->
                    val cost = productCostMap[item.productId] ?: (item.unitPrice * 0.7)
                    val qty = if (item.unitType.equals("gram", ignoreCase = true)) item.quantity / 1000.0 else item.quantity
                    qty * cost
                }
                totalCogs += saleCost
            }

            // Adjust for Returns in this month
            val monthReturns = allReturns.filter { it.saleReturn.datetime in startMs..endMs }
            var netRevenue = grossSales
            monthReturns.forEach { ret ->
                netRevenue -= ret.saleReturn.totalReturnedAmount
                netRevenue += ret.saleReturn.totalReplacementAmount
                ret.items.forEach { rItem ->
                    val cost = productCostMap[rItem.productId] ?: (rItem.unitPrice * 0.7)
                    if (rItem.isReplacement) {
                        totalCogs += (cost * rItem.quantity)
                    } else {
                        totalCogs -= (cost * rItem.quantity)
                    }
                }
            }
            netRevenue = netRevenue.coerceAtLeast(0.0)
            totalCogs = totalCogs.coerceAtLeast(0.0)
            val grossProfit = (netRevenue - totalCogs).coerceAtLeast(0.0)

            // 2. Calculate Monthly Operating Expenses
            val monthExpenses = allExpenses.filter { it.date in startMs..endMs }
            // Filter out stock loss/wastage categories from general expenses to prevent double-counting
            val filteredMonthExpenses = monthExpenses.filterNot {
                it.category.contains("Stock Loss", ignoreCase = true) || it.category.contains("Wastage", ignoreCase = true)
            }

            // Separate Salary category vs Non-Salary operational expenses
            val salaryExpensesInTable = filteredMonthExpenses
                .filter { it.category.equals("Salary", ignoreCase = true) }
                .sumOf { it.amount }
            val nonSalaryOpsExpenses = filteredMonthExpenses
                .filterNot { it.category.equals("Salary", ignoreCase = true) }
                .sumOf { it.amount }

            // 3. Calculate Monthly Staff Salaries Paid
            val monthSalaries = allSalaryPayments.filter { it.paymentDate in startMs..endMs }
            val totalSalariesPaid = monthSalaries.sumOf { it.netSalaryPaid }

            // De-duplicate salary: avoid double-counting if salary payment was auto-recorded into expenses table
            val effectiveSalaryExpense = maxOf(salaryExpensesInTable, totalSalariesPaid)
            val totalExpenseOutflow = nonSalaryOpsExpenses + effectiveSalaryExpense

            // 4. Calculate Stock Loss (Damaged/Expired/Wastage) for this month
            val monthStockLoss = allStockOuts.filter { it.timestamp in startMs..endMs && it.isBusinessLoss() }.sumOf { it.totalCostValue }

            // Net Profit exactly aligns with P&L calculation: Net Revenue - COGS - Total Expense Outflow - Stock Loss
            val netProfit = netRevenue - totalCogs - totalExpenseOutflow - monthStockLoss
            val margin = if (netRevenue > 0) (netProfit / netRevenue) * 100.0 else 0.0

            val expenseCats = filteredMonthExpenses
                .filterNot { it.category.equals("Salary", ignoreCase = true) }
                .groupBy { it.category }
                .mapValues { entry -> entry.value.sumOf { it.amount } }

            list.add(
                MonthlyProfitItem(
                    monthLabel = monthLabelFormat.format(Date(startMs)),
                    fullMonthName = fullMonthFormat.format(Date(startMs)),
                    grossRevenue = netRevenue,
                    cogs = totalCogs,
                    grossProfit = grossProfit,
                    expenses = nonSalaryOpsExpenses,
                    salaryDisbursed = effectiveSalaryExpense,
                    stockLoss = monthStockLoss,
                    totalExpenseOutflow = totalExpenseOutflow,
                    netProfit = netProfit,
                    marginPercent = margin,
                    expenseCategories = expenseCats,
                    orderCount = monthSales.size,
                    startTimestamp = startMs,
                    endTimestamp = endMs
                )
            )
        }
        list
    }

    // High level summary metrics
    val totalRevenuePeriod = remember(monthlyDataList) { monthlyDataList.sumOf { it.grossRevenue } }
    val totalExpensePeriod = remember(monthlyDataList) { monthlyDataList.sumOf { it.totalExpenseOutflow } }
    val totalNetProfitPeriod = remember(monthlyDataList) { monthlyDataList.sumOf { it.netProfit } }
    val avgMonthlyProfit = remember(monthlyDataList, totalNetProfitPeriod) {
        if (monthlyDataList.isNotEmpty()) totalNetProfitPeriod / monthlyDataList.size else 0.0
    }
    val bestMonth = remember(monthlyDataList) { monthlyDataList.maxByOrNull { it.netProfit } }

    // Chart scaling bounds
    val maxChartVal = remember(monthlyDataList) {
        val maxRev = monthlyDataList.maxOfOrNull { it.grossRevenue } ?: 100.0
        val maxExp = monthlyDataList.maxOfOrNull { it.totalExpenseOutflow } ?: 50.0
        val maxNet = monthlyDataList.maxOfOrNull { it.netProfit } ?: 50.0
        maxOf(maxRev, maxExp, maxNet, 500.0) * 1.15
    }
    val minChartVal = remember(monthlyDataList) {
        val minNet = monthlyDataList.minOfOrNull { it.netProfit } ?: 0.0
        if (minNet < 0) minNet * 1.15 else 0.0
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.25f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Header with Title & Chart Mode Selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF10B981).copy(alpha = 0.14f)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                            contentDescription = "Net Profit Trends",
                            tint = Color(0xFF059669),
                            modifier = Modifier
                                .padding(8.dp)
                                .size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Text(
                            text = if (isBn) "মাসিক নিট লাভ ও ট্রেন্ড" else "Monthly Profit & Trend",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextDark,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (isBn) "লেজার, বিক্রয় ও খরচের নিট আয়" else "Based on ledger & expense data",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Polished Chart Type Toggle (Area Spline vs Bar Comparison)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFF1F5F9),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    modifier = Modifier.testTag("chart_type_selector_row")
                ) {
                    Row(
                        modifier = Modifier.padding(3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        val isArea = chartType == MonthlyChartType.AREA_SPLINE
                        // Spline Area Option
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (isArea) Color.White else Color.Transparent)
                                .then(
                                    if (isArea) Modifier.shadow(1.dp, RoundedCornerShape(9.dp), ambientColor = Color.Black.copy(alpha = 0.1f))
                                    else Modifier
                                )
                                .clickable(
                                    role = Role.RadioButton,
                                    onClick = {
                                        if (chartType != MonthlyChartType.AREA_SPLINE) {
                                            chartType = MonthlyChartType.AREA_SPLINE
                                        }
                                    }
                                )
                                .testTag("chart_toggle_spline")
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.ShowChart,
                                contentDescription = if (isBn) "লাইন ও এরিয়া চার্ট" else "Spline Area Chart",
                                tint = if (isArea) Color(0xFF059669) else Color(0xFF64748B),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        val isBar = chartType == MonthlyChartType.BAR_COMPARISON
                        // Bar Comparison Option
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (isBar) Color.White else Color.Transparent)
                                .then(
                                    if (isBar) Modifier.shadow(1.dp, RoundedCornerShape(9.dp), ambientColor = Color.Black.copy(alpha = 0.1f))
                                    else Modifier
                                )
                                .clickable(
                                    role = Role.RadioButton,
                                    onClick = {
                                        if (chartType != MonthlyChartType.BAR_COMPARISON) {
                                            chartType = MonthlyChartType.BAR_COMPARISON
                                        }
                                    }
                                )
                                .testTag("chart_toggle_bar")
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.BarChart,
                                contentDescription = if (isBn) "বার তুলনা চার্ট" else "Bar Comparison Chart",
                                tint = if (isBar) Color(0xFF059669) else Color(0xFF64748B),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Time Span & Quick Stats Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Timespan selector pills
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = trendSpan == MonthlyTrendSpan.LAST_6_MONTHS,
                        onClick = {
                            trendSpan = MonthlyTrendSpan.LAST_6_MONTHS
                            selectedMonthIndex = null
                        },
                        label = { Text(if (isBn) "বিগত ৬ মাস" else "6 Months", fontSize = 11.sp) },
                        modifier = Modifier.height(28.dp),
                        shape = RoundedCornerShape(14.dp)
                    )
                    FilterChip(
                        selected = trendSpan == MonthlyTrendSpan.LAST_12_MONTHS,
                        onClick = {
                            trendSpan = MonthlyTrendSpan.LAST_12_MONTHS
                            selectedMonthIndex = null
                        },
                        label = { Text(if (isBn) "বিগত ১২ মাস" else "12 Months", fontSize = 11.sp) },
                        modifier = Modifier.height(28.dp),
                        shape = RoundedCornerShape(14.dp)
                    )
                }

                // Period Net Profit Indicator
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = if (isBn) "সর্বমোট নিট লাভ" else "Period Net Profit",
                        fontSize = 10.sp,
                        color = TextMuted
                    )
                    Text(
                        text = "₹${String.format(Locale.US, "%,.0f", totalNetProfitPeriod)}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (totalNetProfitPeriod >= 0) Color(0xFF059669) else Color(0xFFDC2626)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Recharts-style Interactive Legend / Series Toggles
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RechartsSeriesPill(
                    label = if (isBn) "নিট লাভ (Net Profit)" else "Net Profit",
                    color = Color(0xFF10B981),
                    isActive = showNetProfit,
                    onClick = { showNetProfit = !showNetProfit }
                )
                RechartsSeriesPill(
                    label = if (isBn) "মোট বিক্রয় (Revenue)" else "Revenue",
                    color = Color(0xFF3B82F6),
                    isActive = showRevenue,
                    onClick = { showRevenue = !showRevenue }
                )
                RechartsSeriesPill(
                    label = if (isBn) "মোট খরচ (Expenses)" else "Expenses",
                    color = Color(0xFFEF4444),
                    isActive = showExpenses,
                    onClick = { showExpenses = !showExpenses }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Interactive Chart Canvas (Spline Area / Multi-Bar with tap inspection)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(Color(0xFFFAFAFA), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                val primaryColor = Color(0xFF10B981)
                val blueColor = Color(0xFF3B82F6)
                val redColor = Color(0xFFEF4444)
                val gridLineColor = Color(0xFFE2E8F0)

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(monthlyDataList, trendSpan) {
                            detectTapGestures { offset ->
                                if (monthlyDataList.isEmpty()) return@detectTapGestures
                                val width = size.width
                                val stepX = width / monthlyDataList.size
                                val clickedIdx = (offset.x / stepX).toInt().coerceIn(0, monthlyDataList.size - 1)
                                selectedMonthIndex = if (selectedMonthIndex == clickedIdx) null else clickedIdx
                            }
                        }
                ) {
                    val width = size.width
                    val height = size.height
                    val paddingBottom = 22f
                    val chartHeight = height - paddingBottom
                    val count = monthlyDataList.size

                    if (count < 2) return@Canvas

                    val valRange = (maxChartVal - minChartVal).toFloat().coerceAtLeast(1f)
                    fun getY(value: Double): Float {
                        val norm = ((value - minChartVal) / valRange).toFloat().coerceIn(0f, 1f)
                        return chartHeight - (norm * chartHeight)
                    }

                    // 1. Draw horizontal grid reference lines
                    val gridSteps = 4
                    for (i in 0..gridSteps) {
                        val gridY = (chartHeight / gridSteps) * i
                        drawLine(
                            color = gridLineColor,
                            start = Offset(0f, gridY),
                            end = Offset(width, gridY),
                            strokeWidth = 1f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                        )
                    }

                    // Draw Zero Baseline if min < 0
                    if (minChartVal < 0) {
                        val zeroY = getY(0.0)
                        drawLine(
                            color = Color(0xFF94A3B8),
                            start = Offset(0f, zeroY),
                            end = Offset(width, zeroY),
                            strokeWidth = 1.5f
                        )
                    }

                    // 2. Draw Chart based on Selected Mode
                    if (chartType == MonthlyChartType.AREA_SPLINE) {
                        val stepX = width / (count - 1).coerceAtLeast(1)

                        // Draw Revenue Curve
                        if (showRevenue) {
                            val revPath = Path()
                            val revPoints = monthlyDataList.mapIndexed { idx, item ->
                                Offset(idx * stepX, getY(item.grossRevenue))
                            }
                            drawSmoothSpline(revPath, revPoints)
                            drawPath(
                                path = revPath,
                                color = blueColor.copy(alpha = 0.8f),
                                style = Stroke(width = 2.5f, cap = StrokeCap.Round)
                            )
                        }

                        // Draw Expenses Curve
                        if (showExpenses) {
                            val expPath = Path()
                            val expPoints = monthlyDataList.mapIndexed { idx, item ->
                                Offset(idx * stepX, getY(item.totalExpenseOutflow))
                            }
                            drawSmoothSpline(expPath, expPoints)
                            drawPath(
                                path = expPath,
                                color = redColor.copy(alpha = 0.8f),
                                style = Stroke(width = 2.5f, cap = StrokeCap.Round)
                            )
                        }

                        // Draw Net Profit Curve with Gradient Area Fill
                        if (showNetProfit) {
                            val netPoints = monthlyDataList.mapIndexed { idx, item ->
                                Offset(idx * stepX, getY(item.netProfit))
                            }

                            // Fill Area below Net Profit
                            val fillPath = Path()
                            drawSmoothSpline(fillPath, netPoints)
                            fillPath.lineTo(width, chartHeight)
                            fillPath.lineTo(0f, chartHeight)
                            fillPath.close()

                            drawPath(
                                path = fillPath,
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        primaryColor.copy(alpha = 0.35f),
                                        primaryColor.copy(alpha = 0.05f),
                                        Color.Transparent
                                    ),
                                    startY = 0f,
                                    endY = chartHeight
                                )
                            )

                            // Stroke line for Net Profit
                            val netStrokePath = Path()
                            drawSmoothSpline(netStrokePath, netPoints)
                            drawPath(
                                path = netStrokePath,
                                color = primaryColor,
                                style = Stroke(width = 3.5f, cap = StrokeCap.Round)
                            )

                            // Data points dots
                            netPoints.forEachIndexed { idx, pt ->
                                val isSelected = selectedMonthIndex == idx
                                drawCircle(
                                    color = Color.White,
                                    radius = if (isSelected) 6f else 4f,
                                    center = pt
                                )
                                drawCircle(
                                    color = primaryColor,
                                    radius = if (isSelected) 6f else 4f,
                                    center = pt,
                                    style = Stroke(width = if (isSelected) 3f else 2f)
                                )
                            }
                        }

                    } else {
                        // Multi-Bar Grouped Comparison
                        val groupWidth = width / count
                        val barWidth = (groupWidth * 0.24f).coerceAtLeast(3f)

                        monthlyDataList.forEachIndexed { idx, item ->
                            val centerX = idx * groupWidth + (groupWidth / 2f)
                            val zeroY = getY(0.0)

                            if (showRevenue) {
                                val revY = getY(item.grossRevenue)
                                val revH = (chartHeight - revY).coerceAtLeast(2f)
                                drawRoundRect(
                                    color = blueColor.copy(alpha = 0.85f),
                                    topLeft = Offset(centerX - barWidth * 1.5f, revY),
                                    size = Size(barWidth, revH),
                                    cornerRadius = CornerRadius(3f, 3f)
                                )
                            }

                            if (showExpenses) {
                                val expY = getY(item.totalExpenseOutflow)
                                val expH = (chartHeight - expY).coerceAtLeast(2f)
                                drawRoundRect(
                                    color = redColor.copy(alpha = 0.85f),
                                    topLeft = Offset(centerX - barWidth * 0.5f, expY),
                                    size = Size(barWidth, expH),
                                    cornerRadius = CornerRadius(3f, 3f)
                                )
                            }

                            if (showNetProfit) {
                                val netY = getY(item.netProfit)
                                val isPositive = item.netProfit >= 0
                                val netTop = if (isPositive) netY else zeroY
                                val netH = if (isPositive) (chartHeight - netY).coerceAtLeast(2f) else (netY - zeroY).coerceAtLeast(2f)
                                drawRoundRect(
                                    color = if (isPositive) primaryColor else Color(0xFFDC2626),
                                    topLeft = Offset(centerX + barWidth * 0.5f, netTop),
                                    size = Size(barWidth, netH),
                                    cornerRadius = CornerRadius(3f, 3f)
                                )
                            }
                        }
                    }

                    // 3. Highlight Selected Month Vertical Cursor Line
                    selectedMonthIndex?.let { selIdx ->
                        if (selIdx in monthlyDataList.indices) {
                            val stepX = if (chartType == MonthlyChartType.AREA_SPLINE) {
                                width / (count - 1).coerceAtLeast(1)
                            } else {
                                width / count
                            }
                            val cursorX = if (chartType == MonthlyChartType.AREA_SPLINE) {
                                selIdx * stepX
                            } else {
                                selIdx * stepX + (stepX / 2f)
                            }

                            drawLine(
                                color = Color(0xFF64748B),
                                start = Offset(cursorX, 0f),
                                end = Offset(cursorX, chartHeight),
                                strokeWidth = 1.5f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
                            )
                        }
                    }
                }

                // Month Labels along X-Axis
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    monthlyDataList.forEachIndexed { idx, item ->
                        val isSelected = selectedMonthIndex == idx
                        Text(
                            text = item.monthLabel,
                            fontSize = 9.5.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) Color(0xFF059669) else TextMuted
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Interactive Tooltip / Month Breakdown Card (Recharts style)
            val activeItem = selectedMonthIndex?.let { monthlyDataList.getOrNull(it) } ?: monthlyDataList.lastOrNull()

            activeItem?.let { item ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.CalendarMonth,
                                    contentDescription = null,
                                    tint = Color(0xFF059669),
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = item.fullMonthName,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = TextDark
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (item.netProfit >= 0) Color(0xFFDCFCE7) else Color(0xFFFEE2E2)
                                ) {
                                    Text(
                                        text = "${if (item.netProfit >= 0) "+" else ""}${"%.1f".format(item.marginPercent)}% Margin",
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (item.netProfit >= 0) Color(0xFF16A34A) else Color(0xFFDC2626),
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }

                            Text(
                                text = "Net: ₹${String.format(Locale.US, "%,.0f", item.netProfit)}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = if (item.netProfit >= 0) Color(0xFF059669) else Color(0xFFDC2626)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Metric Stat Pills Grid
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            MonthlyStatTile(
                                label = if (isBn) "বিক্রয়" else "Revenue",
                                value = "₹${String.format(Locale.US, "%,.0f", item.grossRevenue)}",
                                color = Color(0xFF3B82F6),
                                modifier = Modifier.weight(1f)
                            )
                            MonthlyStatTile(
                                label = if (isBn) "পণ্য খরচ (COGS)" else "Cost (COGS)",
                                value = "₹${String.format(Locale.US, "%,.0f", item.cogs)}",
                                color = Color(0xFFF59E0B),
                                modifier = Modifier.weight(1f)
                            )
                            MonthlyStatTile(
                                label = if (isBn) "মোট ব্যয়" else "Expenses",
                                value = "₹${String.format(Locale.US, "%,.0f", item.totalExpenseOutflow)}",
                                color = Color(0xFFEF4444),
                                modifier = Modifier.weight(1f)
                            )
                            MonthlyStatTile(
                                label = if (isBn) "অর্ডার সংখ্যা" else "Orders",
                                value = "${item.orderCount}",
                                color = Color(0xFF8B5CF6),
                                modifier = Modifier.weight(0.8f)
                            )
                        }

                        // Expense category breakdown progress chips if any
                        if (item.expenseCategories.isNotEmpty() || item.salaryDisbursed > 0 || item.stockLoss > 0) {
                            Spacer(modifier = Modifier.height(6.dp))
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (item.salaryDisbursed > 0) {
                                    Surface(
                                        color = Color(0xFFFEF3C7),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = "Salary: ₹${String.format(Locale.US, "%,.0f", item.salaryDisbursed)}",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color(0xFF92400E),
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                if (item.stockLoss > 0) {
                                    Surface(
                                        color = Color(0xFFFEE2E2),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = "Stock Loss: ₹${String.format(Locale.US, "%,.0f", item.stockLoss)}",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color(0xFF991B1B),
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                item.expenseCategories.forEach { (cat, amt) ->
                                    Surface(
                                        color = Color(0xFFF1F5F9),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = "$cat: ₹${String.format(Locale.US, "%,.0f", amt)}",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color(0xFF475569),
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Bottom Performance Insights
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isBn) "গড় মাসিক লাভ: ₹${String.format(Locale.US, "%,.0f", avgMonthlyProfit)}" 
                               else "Avg Monthly Profit: ₹${String.format(Locale.US, "%,.0f", avgMonthlyProfit)}",
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                }

                bestMonth?.let { best ->
                    Text(
                        text = if (isBn) "সেরা মাস: ${best.monthLabel} (₹${String.format(Locale.US, "%,.0f", best.netProfit)})"
                               else "Peak: ${best.monthLabel} (₹${String.format(Locale.US, "%,.0f", best.netProfit)})",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF059669)
                    )
                }
            }
        }
    }
}

@Composable
private fun RechartsSeriesPill(
    label: String,
    color: Color,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isActive) color.copy(alpha = 0.12f) else Color(0xFFF1F5F9),
        border = BorderStroke(1.dp, if (isActive) color else Color(0xFFCBD5E1)),
        modifier = Modifier.clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(if (isActive) color else Color(0xFF94A3B8), CircleShape)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = label,
                fontSize = 10.5.sp,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                color = if (isActive) TextDark else TextMuted
            )
        }
    }
}

@Composable
private fun MonthlyStatTile(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
    ) {
        Column(
            modifier = Modifier.padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                fontSize = 9.sp,
                color = TextMuted,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = value,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = color,
                maxLines = 1
            )
        }
    }
}

/**
 * Helper to compute smooth cubic spline through points
 */
private fun drawSmoothSpline(path: Path, points: List<Offset>) {
    if (points.isEmpty()) return
    path.reset()
    path.moveTo(points.first().x, points.first().y)
    for (i in 0 until points.size - 1) {
        val p0 = points[i]
        val p1 = points[i + 1]
        val controlPoint1 = Offset(p0.x + (p1.x - p0.x) / 2f, p0.y)
        val controlPoint2 = Offset(p0.x + (p1.x - p0.x) / 2f, p1.y)
        path.cubicTo(
            controlPoint1.x, controlPoint1.y,
            controlPoint2.x, controlPoint2.y,
            p1.x, p1.y
        )
    }
}
