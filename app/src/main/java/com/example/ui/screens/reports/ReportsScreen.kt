package com.example.ui.screens.reports

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.entities.LedgerEntry
import com.example.ui.components.FirebaseSyncStatusBadge
import com.example.ui.components.LowStockDashboardCardWidget
import com.example.ui.components.MonthlyProfitTrendWidget
import com.example.ui.components.WeeklyRevenueTrendWidget
import com.example.ui.screens.pos.DailySalesSummaryDialog
import com.example.ui.theme.*
import com.example.utils.LanguageManager
import com.example.viewmodel.ReportPeriod
import com.example.viewmodel.StoreViewModel

@Composable
fun ReportsScreen(viewModel: StoreViewModel) {
    val context = LocalContext.current
    val currentFirestoreRole by viewModel.currentFirestoreUserRole.collectAsState()
    val authUser by viewModel.currentUser.collectAsState()

    // Enforce Role-Based Access Control: restrict Profit reports for regular Employees
    val isReportAllowed = if (currentFirestoreRole != null) {
        currentFirestoreRole!!.canViewReports
    } else {
        com.example.utils.StaffManager.canViewReports()
    }

    if (!isReportAllowed) {
        com.example.ui.components.StaffAccessGate(
            screenTitle = if (LanguageManager.isBengali) "লাভ-ক্ষতির রিপোর্ট (সীমাবদ্ধ)" else "Profit & Loss Reports (Restricted by RBAC)",
            screenDescription = if (LanguageManager.isBengali) 
                "গোপনীয় ব্যবসার লাভ ও আয়ের রিপোর্ট সাধারণ কর্মচারীদের (Employee) জন্য ফায়ারস্টোর RBAC সিস্টেম দ্বারা সুরক্ষিত।" 
                else "Confidential revenue, gross/net margins, and financial reports are restricted for regular Employees under Firestore Role-Based Access Control (RBAC)."
        )
        return
    }

    val isBn = LanguageManager.isBengali
    var showDailySummaryDialog by remember { mutableStateOf(false) }
    var showCloudReportsDialog by remember { mutableStateOf(false) }
    var showStockOutReportDialog by remember { mutableStateOf(false) }
    var showComprehensiveReportDialog by remember { mutableStateOf(false) }
    var showFullExpenseReportDialog by remember { mutableStateOf(false) }
    var showDebtRepaymentDetailsDialog by remember { mutableStateOf(false) }
    var isSyncingToCloud by remember { mutableStateOf(false) }

    val selectedPeriod = viewModel.selectedReportPeriod
    val report = viewModel.pnlReport
    val stockOutReport = viewModel.stockOutReport

    val products by viewModel.allProducts.collectAsState()
    val customers by viewModel.allCustomers.collectAsState()
    val suppliers by viewModel.allSuppliers.collectAsState()
    val activeEmployees by viewModel.activeEmployees.collectAsState()
    val allSalaryDues by viewModel.allSalaryDues.collectAsState()
    val allSalaryPayments by viewModel.allSalaryPayments.collectAsState()
    val allAdvances by viewModel.allAdvances.collectAsState()
    val allLedgerEntries by viewModel.allLedgerEntries.collectAsState()

    val totalCommittedSalary = remember(activeEmployees) { activeEmployees.sumOf { it.baseSalary } }
    val totalDues = remember(allSalaryDues) { allSalaryDues.sumOf { it.dueAmount } }
    val totalPaid = remember(allSalaryPayments) { allSalaryPayments.sumOf { it.netSalaryPaid } }
    val totalSalaryOwed = remember(totalDues, totalPaid) { (totalDues - totalPaid).coerceAtLeast(0.0) }
    val totalAdvances = remember(allAdvances) {
        allAdvances.filter { it.type == "ADVANCE" }.sumOf { it.amount - it.repaidAmount }.coerceAtLeast(0.0)
    }

    val totalStockValuationCost = remember(products) {
        products.sumOf {
            if (it.unitType.equals("gram", ignoreCase = true)) (it.currentStock / 1000.0) * it.costPrice else it.currentStock * it.costPrice
        }
    }

    val totalReceivables = remember(customers, allLedgerEntries) {
        customers.sumOf { cust ->
            com.example.utils.LedgerCalculator.calculateCustomerBalance(cust.id, allLedgerEntries)
        }
    }
    val totalPayables = remember(suppliers, allLedgerEntries) {
        suppliers.sumOf { supp ->
            com.example.utils.LedgerCalculator.calculateSupplierBalance(supp.id, allLedgerEntries)
        }
    }

    // Customer Debt Repayments (Khata Debt Collected) Calculations
    val (todayStart, todayEnd) = remember { viewModel.getPeriodTimeBounds(ReportPeriod.TODAY) }
    val (weekStart, weekEnd) = remember { viewModel.getPeriodTimeBounds(ReportPeriod.THIS_WEEK) }
    val (monthStart, monthEnd) = remember { viewModel.getPeriodTimeBounds(ReportPeriod.THIS_MONTH) }
    val (periodStart, periodEnd) = remember(selectedPeriod) { viewModel.getPeriodTimeBounds(selectedPeriod) }

    val todayRepayments = remember(allLedgerEntries) {
        allLedgerEntries.filter {
            it.partyType == "CUSTOMER" &&
            (it.type.contains("RECEIVED") || it.type in listOf("PAYMENT", "RETURN_REFUND", "CREDIT_REDUCED", "REFUND")) &&
            it.datetime in todayStart..todayEnd
        }
    }
    val todayDebtRepaid = remember(todayRepayments) { todayRepayments.sumOf { it.amount } }
    val todayDebtRepaidCount = remember(todayRepayments) { todayRepayments.size }

    val weekRepayments = remember(allLedgerEntries) {
        allLedgerEntries.filter {
            it.partyType == "CUSTOMER" &&
            (it.type.contains("RECEIVED") || it.type in listOf("PAYMENT", "RETURN_REFUND", "CREDIT_REDUCED", "REFUND")) &&
            it.datetime in weekStart..weekEnd
        }
    }
    val weekDebtRepaid = remember(weekRepayments) { weekRepayments.sumOf { it.amount } }
    val weekDebtRepaidCount = remember(weekRepayments) { weekRepayments.size }

    val monthRepayments = remember(allLedgerEntries) {
        allLedgerEntries.filter {
            it.partyType == "CUSTOMER" &&
            (it.type.contains("RECEIVED") || it.type in listOf("PAYMENT", "RETURN_REFUND", "CREDIT_REDUCED", "REFUND")) &&
            it.datetime in monthStart..monthEnd
        }
    }
    val monthDebtRepaid = remember(monthRepayments) { monthRepayments.sumOf { it.amount } }
    val monthDebtRepaidCount = remember(monthRepayments) { monthRepayments.size }

    val periodRepayments = remember(allLedgerEntries, selectedPeriod) {
        allLedgerEntries.filter {
            it.partyType == "CUSTOMER" &&
            (it.type.contains("RECEIVED") || it.type in listOf("PAYMENT", "RETURN_REFUND", "CREDIT_REDUCED", "REFUND")) &&
            it.datetime in periodStart..periodEnd
        }.sortedByDescending { it.datetime }
    }
    val periodDebtRepaid = remember(periodRepayments) { periodRepayments.sumOf { it.amount } }
    val periodDebtRepaidCount = remember(periodRepayments) { periodRepayments.size }

    val periodCreditGivenEntries = remember(allLedgerEntries, selectedPeriod) {
        allLedgerEntries.filter {
            it.partyType == "CUSTOMER" &&
            (it.type == "SALE_CREDIT" || it.type == "CREDIT_GIVEN" || it.type == "CREDIT" || it.type.contains("GIVEN")) &&
            it.datetime in periodStart..periodEnd
        }
    }
    val periodCreditGiven = remember(periodCreditGivenEntries) { periodCreditGivenEntries.sumOf { it.amount } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        // Top RBAC & Cloud Actions Row
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
                    color = if (currentFirestoreRole?.isAdmin == true) StoreGold.copy(alpha = 0.15f) else StorePrimary.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (currentFirestoreRole?.isAdmin == true) Icons.Default.AdminPanelSettings else Icons.Default.Shield,
                            contentDescription = null,
                            tint = if (currentFirestoreRole?.isAdmin == true) StoreGold else StorePrimary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "RBAC: ${currentFirestoreRole?.role ?: if (authUser == null) "ADMIN" else "EMPLOYEE"}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (currentFirestoreRole?.isAdmin == true) StoreGold else StorePrimary
                        )
                    }
                }

                FirebaseSyncStatusBadge(
                    viewModel = viewModel,
                    compact = true
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = { showCloudReportsDialog = true },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Icon(Icons.Default.CloudQueue, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Cloud Syncs", fontSize = 11.sp)
                }

                if (currentFirestoreRole?.isAdmin != false) {
                    Button(
                        onClick = {
                            isSyncingToCloud = true
                            viewModel.publishProfitReportToFirestore(selectedPeriod) { success, msg ->
                                isSyncingToCloud = false
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp),
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = StorePrimary)
                    ) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isSyncingToCloud) "Saving..." else "Sync Cloud", fontSize = 11.sp, color = Color.White)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Period Selector
        Text(
            text = LanguageManager.getString("Select Report Period", "রিপোর্টের সময়কাল সিলেক্ট করুন"),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ReportPeriod.values().forEach { period ->
                val isSelected = selectedPeriod == period
                val label = when (period) {
                    ReportPeriod.TODAY -> LanguageManager.getString("Today", "আজ")
                    ReportPeriod.THIS_WEEK -> LanguageManager.getString("This Week", "এই সপ্তাহ")
                    ReportPeriod.THIS_MONTH -> LanguageManager.getString("This Month", "এই মাস")
                    ReportPeriod.ALL_TIME -> LanguageManager.getString("All Time", "সর্বমোট")
                }
                FilterChip(
                    selected = isSelected,
                    onClick = { viewModel.loadPnlReport(period) },
                    label = { Text(label) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Comprehensive Business Intelligence & Product Analytics Card
            item {
                ComprehensiveAnalyticsCard(
                    viewModel = viewModel,
                    onOpenFullReport = { showComprehensiveReportDialog = true }
                )
            }

            // Net Profit Banner Card
            item {
                val netProfit = report?.netProfit ?: 0.0
                val isProfitable = netProfit >= 0

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isProfitable) StoreGreenProfit else StoreRedPrimary
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth()
                    ) {
                        Text(
                            text = LanguageManager.getString("NET PROFIT / LOSS", "প্রকৃত লাভ / ক্ষতি (Net Profit)"),
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "₹%.2f".format(netProfit),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = if (isProfitable) "Real profit after product cost & expenses" else "Expenses exceeded gross profit for period",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                    }
                }
            }

            // Profit & Loss Breakdown Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                    elevation = CardDefaults.cardElevation(2.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = LanguageManager.getString("Profit & Loss Breakdown", "লাভ ও ক্ষতির বিবরণী"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        ReportRow(
                            label = LanguageManager.getString("Total Sales Revenue", "মোট বিক্রি (Revenue)"),
                            value = "₹%.2f".format(report?.totalRevenue ?: 0.0),
                            valueColor = StoreGreenProfit
                        )
                        ReportRow(
                            label = LanguageManager.getString("Cost of Goods Sold (COGS)", "পণ্য কেনা খরচ (COGS)"),
                            value = "-₹%.2f".format(report?.totalCogs ?: 0.0),
                            valueColor = StoreRedPrimary
                        )
                        Divider(modifier = Modifier.padding(vertical = 6.dp))
                        ReportRow(
                            label = LanguageManager.getString("Gross Profit", "মোট লাভ (Gross Profit)"),
                            value = "₹%.2f".format(report?.grossProfit ?: 0.0),
                            isBold = true
                        )
                        ReportRow(
                            label = LanguageManager.getString("Shop Expenses", "দোকানের পরিচালন খরচ"),
                            value = "-₹%.2f".format(report?.totalExpenses ?: 0.0),
                            valueColor = StoreRedPrimary,
                            badgeText = if (isBn) "বিস্তারিত রিপোর্ট ➔" else "Details ➔",
                            onClick = { showFullExpenseReportDialog = true }
                        )
                        ReportRow(
                            label = LanguageManager.getString("Stock Loss (Damaged/Expired/Wastage)", "স্টক ক্ষতি (ক্ষতিগ্রস্ত/মেয়াদোত্তীর্ণ/অপচয়)"),
                            value = "-₹%.2f".format(report?.stockLoss ?: 0.0),
                            valueColor = StoreRedPrimary
                        )
                        Divider(modifier = Modifier.padding(vertical = 6.dp))
                        ReportRow(
                            label = LanguageManager.getString("NET PROFIT", "প্রকৃত নিট লাভ"),
                            value = "₹%.2f".format(report?.netProfit ?: 0.0),
                            valueColor = if ((report?.netProfit ?: 0.0) >= 0) StoreGreenProfit else StoreRedPrimary,
                            isBold = true
                        )
                    }
                }
            }

            // Sales Channel Breakdown Card (Online Store vs. Walk-in / Counter)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                    elevation = CardDefaults.cardElevation(2.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(Color(0xFF0284C7).copy(alpha = 0.12f), RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Storefront,
                                        contentDescription = null,
                                        tint = Color(0xFF0284C7),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = LanguageManager.getString("Channel Breakdown", "বিক্রয় চ্যানেল বিভাজন"),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = LanguageManager.getString("Online Store vs. Walk-in / Counter", "অনলাইন স্টোর বনাম কাউন্টার সেল"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            val onlinePct = report?.onlineRevenuePercentage ?: 0.0
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Color(0xFF0284C7).copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, Color(0xFF0284C7).copy(alpha = 0.3f))
                            ) {
                                Text(
                                    text = LanguageManager.getString(
                                        "Online: %.1f%%".format(onlinePct),
                                        "অনলাইন: %.1f%%".format(onlinePct)
                                    ),
                                    color = Color(0xFF0284C7),
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Visual proportion bar
                        val onlineShareRatio = ((report?.onlineRevenuePercentage ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(Color(0xFFE2E8F0))
                            ) {
                                if (onlineShareRatio > 0f) {
                                    Box(
                                        modifier = Modifier
                                            .weight(onlineShareRatio.coerceAtLeast(0.02f))
                                            .fillMaxHeight()
                                            .background(Color(0xFF0284C7))
                                    )
                                }
                                val walkInShareRatio = (1f - onlineShareRatio).coerceIn(0f, 1f)
                                if (walkInShareRatio > 0f) {
                                    Box(
                                        modifier = Modifier
                                            .weight(walkInShareRatio.coerceAtLeast(0.02f))
                                            .fillMaxHeight()
                                            .background(StoreGreenProfit)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = LanguageManager.getString(
                                        "● Online: %.1f%%".format(report?.onlineRevenuePercentage ?: 0.0),
                                        "● অনলাইন: %.1f%%".format(report?.onlineRevenuePercentage ?: 0.0)
                                    ),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0284C7)
                                )
                                val walkInPct = if ((report?.totalRevenue ?: 0.0) > 0) 100.0 - (report?.onlineRevenuePercentage ?: 0.0) else 0.0
                                Text(
                                    text = LanguageManager.getString(
                                        "● Walk-in / Counter: %.1f%%".format(walkInPct),
                                        "● কাউন্টার: %.1f%%".format(walkInPct)
                                    ),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = StoreGreenProfit
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Two Column Comparison: Online Store vs Walk-in Counter
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Online Store Box
                            Surface(
                                modifier = Modifier.weight(1f),
                                color = Color(0xFF0284C7).copy(alpha = 0.06f),
                                border = BorderStroke(1.dp, Color(0xFF0284C7).copy(alpha = 0.2f)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.ShoppingCart,
                                            contentDescription = null,
                                            tint = Color(0xFF0284C7),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = LanguageManager.getString("Online Store", "অনলাইন স্টোর"),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = Color(0xFF0284C7)
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Text(
                                        text = LanguageManager.getString("Orders", "অর্ডার সংখ্যা"),
                                        fontSize = 10.sp,
                                        color = TextMuted
                                    )
                                    Text(
                                        text = "${report?.onlineOrderCount ?: 0} ${LanguageManager.getString("Orders", "টি")}",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )

                                    Spacer(modifier = Modifier.height(6.dp))

                                    Text(
                                        text = LanguageManager.getString("Revenue", "বিক্রি"),
                                        fontSize = 10.sp,
                                        color = TextMuted
                                    )
                                    Text(
                                        text = "₹%.2f".format(report?.onlineRevenue ?: 0.0),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = Color(0xFF0284C7)
                                    )

                                    Spacer(modifier = Modifier.height(6.dp))

                                    Text(
                                        text = LanguageManager.getString("Gross Profit", "মোট লাভ"),
                                        fontSize = 10.sp,
                                        color = TextMuted
                                    )
                                    Text(
                                        text = "₹%.2f".format(report?.onlineGrossProfit ?: 0.0),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = if ((report?.onlineGrossProfit ?: 0.0) >= 0) StoreGreenProfit else StoreRedPrimary
                                    )
                                }
                            }

                            // Walk-in / Counter Box
                            Surface(
                                modifier = Modifier.weight(1f),
                                color = StoreGreenProfit.copy(alpha = 0.06f),
                                border = BorderStroke(1.dp, StoreGreenProfit.copy(alpha = 0.2f)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.PointOfSale,
                                            contentDescription = null,
                                            tint = StoreGreenProfit,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = LanguageManager.getString("Walk-in / POS", "কাউন্টার সেল"),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = StoreGreenProfit
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Text(
                                        text = LanguageManager.getString("Orders", "অর্ডার সংখ্যা"),
                                        fontSize = 10.sp,
                                        color = TextMuted
                                    )
                                    Text(
                                        text = "${report?.walkInOrderCount ?: 0} ${LanguageManager.getString("Orders", "টি")}",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )

                                    Spacer(modifier = Modifier.height(6.dp))

                                    Text(
                                        text = LanguageManager.getString("Revenue", "বিক্রি"),
                                        fontSize = 10.sp,
                                        color = TextMuted
                                    )
                                    Text(
                                        text = "₹%.2f".format(report?.walkInRevenue ?: 0.0),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = StoreGreenProfit
                                    )

                                    Spacer(modifier = Modifier.height(6.dp))

                                    Text(
                                        text = LanguageManager.getString("Gross Profit", "মোট লাভ"),
                                        fontSize = 10.sp,
                                        color = TextMuted
                                    )
                                    Text(
                                        text = "₹%.2f".format(report?.walkInGrossProfit ?: 0.0),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = if ((report?.walkInGrossProfit ?: 0.0) >= 0) StoreGreenProfit else StoreRedPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Shop Operating Expenses Report Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                    elevation = CardDefaults.cardElevation(2.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(StoreRedPrimary.copy(alpha = 0.12f), RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.AccountBalanceWallet,
                                        contentDescription = null,
                                        tint = StoreRedPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = if (isBn) "দোকানের পরিচালন খরচ রিপোর্ট" else "Shop Operating Expense Report",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = if (isBn) "নির্বাচিত সময়ের খরচের হিসাব ও বিশ্লেষণ" else "Detailed breakdown of shop expenses for this period",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        val totalExp = report?.totalExpenses ?: 0.0
                        val expenseCount = report?.expensesList?.size ?: 0
                        val topCat = report?.topExpenseCategory

                        ReportRow(
                            label = LanguageManager.getString("Total Shop Expenses", "মোট দোকান খরচ"),
                            value = "₹%.2f".format(totalExp),
                            valueColor = StoreRedPrimary,
                            isBold = true
                        )
                        ReportRow(
                            label = LanguageManager.getString("Total Expense Records", "মোট খরচের এন্ট্রি"),
                            value = "$expenseCount ${if (isBn) "টি" else "records"}"
                        )
                        if (!topCat.isNullOrBlank()) {
                            ReportRow(
                                label = LanguageManager.getString("Highest Expense Category", "সর্বোচ্চ খরচের খাত"),
                                value = topCat,
                                valueColor = StorePrimary,
                                isBold = true
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = { showFullExpenseReportDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = StoreRedPrimary)
                        ) {
                            Icon(
                                Icons.Default.Assessment,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = Color.White
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isBn) "সম্পূর্ণ খরচের রিপোর্ট দেখুন" else "View Detailed Expense Report",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // Stock-Out & Wastage Cost Report Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                    elevation = CardDefaults.cardElevation(2.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(StoreRedAlert.copy(alpha = 0.12f), RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.RemoveShoppingCart,
                                        contentDescription = null,
                                        tint = StoreRedAlert,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = LanguageManager.getString("Stock-Out / Wastage Cost Report", "স্টক আউট ও অপচয় ক্ষতি রিপোর্ট"),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = LanguageManager.getString("Cost value of stock removed & wasted", "অপসারিত ও ক্ষতিগ্রস্ত পণ্যের ক্রয়মূল্য"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        ReportRow(
                            label = LanguageManager.getString("Genuine Business Loss (Stock Loss)", "প্রকৃত ব্যবসার ক্ষতি (ক্ষতিগ্রস্ত/মেয়াদ/অপচয়)"),
                            value = "₹%.2f".format(stockOutReport?.totalBusinessLossCost ?: (report?.stockLoss ?: 0.0)),
                            valueColor = StoreRedAlert,
                            isBold = true
                        )
                        ReportRow(
                            label = LanguageManager.getString("Owner Personal Use (Not a Loss)", "মালিকের ব্যক্তিগত ব্যবহার (ক্ষতি নয়)"),
                            value = "₹%.2f".format(stockOutReport?.personalUseCost ?: 0.0),
                            valueColor = MaterialTheme.colorScheme.primary
                        )
                        ReportRow(
                            label = LanguageManager.getString("Combined Total Stock Removed", "সর্বমোট স্টক অপসারণ মূল্য"),
                            value = "₹%.2f".format(stockOutReport?.combinedTotalCost ?: 0.0),
                            isBold = true
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Button(
                            onClick = { showStockOutReportDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = StoreRedAlert)
                        ) {
                            Icon(
                                Icons.Default.Assessment,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = Color.White
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = LanguageManager.getString("View Detailed Stock-Out Report (PDF/CSV/WhatsApp)", "বিস্তারিত স্টক আউট রিপোর্ট দেখুন (PDF/CSV/WhatsApp)"),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // Payment Mode Breakdown
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                    elevation = CardDefaults.cardElevation(2.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = LanguageManager.getString("Sales Payment Breakdown", "বিক্রয় পেমেন্ট বিভাজন"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        ReportRow(
                            label = LanguageManager.getString("Cash Sales", "নগদ বিক্রি"),
                            value = "₹%.2f".format(report?.cashSales ?: 0.0)
                        )
                        ReportRow(
                            label = LanguageManager.getString("UPI Digital Sales", "ইউপিআই (UPI) বিক্রি"),
                            value = "₹%.2f".format(report?.upiSales ?: 0.0)
                        )
                        ReportRow(
                            label = LanguageManager.getString("Credit (Khata) Sales", "বাকী (খাতা) বিক্রি"),
                            value = "₹%.2f".format(report?.creditSales ?: 0.0),
                            valueColor = StoreRedPrimary
                        )
                        ReportRow(
                            label = LanguageManager.getString("Customer Debt Repaid (Khata Inflow)", "গ্রাহক বকেয়া আদায় (খাতা জমা)"),
                            value = "+₹%.2f".format(periodDebtRepaid),
                            valueColor = StoreGreenProfit,
                            isBold = true,
                            badgeText = "$periodDebtRepaidCount ${if (isBn) "কালেকশন" else "payments"}"
                        )
                        ReportRow(
                            label = LanguageManager.getString("Total Completed Sales", "মোট বিল সংখ্যা"),
                            value = "${report?.totalSalesCount ?: 0} Bills"
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = { showDailySummaryDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                Icons.Default.TrendingUp,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(LanguageManager.getString("View Detailed Daily Transactions List", "আজকের বিষয়ভিত্তিক লেনদেন দেখুন"))
                        }
                    }
                }
            }

            // Customer Debt Repayment & Khata Collections Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                    elevation = CardDefaults.cardElevation(2.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(StoreGreenProfit.copy(alpha = 0.12f), RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.PriceCheck,
                                        contentDescription = null,
                                        tint = StoreGreenProfit,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = LanguageManager.getString("Customer Debt Repaid (Khata)", "গ্রাহক বকেয়া আদায় (খাতা কালেকশন)"),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = LanguageManager.getString("Debt repaid by customers into cashflow", "গ্রাহকদের থেকে আদায়কৃত বকেয়া টাকা"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Quick glance: Today vs This Week vs This Month
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Today Box
                            Surface(
                                modifier = Modifier.weight(1f),
                                color = if (selectedPeriod == ReportPeriod.TODAY) StoreGreenProfit.copy(alpha = 0.15f) else StoreGreenProfit.copy(alpha = 0.06f),
                                border = BorderStroke(1.dp, if (selectedPeriod == ReportPeriod.TODAY) StoreGreenProfit else StoreGreenProfit.copy(alpha = 0.2f)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(
                                        text = LanguageManager.getString("Today Repaid", "আজ আদায়"),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = StoreGreenProfit
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "+₹%.2f".format(todayDebtRepaid),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = StoreGreenProfit
                                    )
                                    Text(
                                        text = "$todayDebtRepaidCount ${if (isBn) "টি জমা" else "rcv'd"}",
                                        fontSize = 10.sp,
                                        color = TextMuted
                                    )
                                }
                            }

                            // This Week Box
                            Surface(
                                modifier = Modifier.weight(1f),
                                color = if (selectedPeriod == ReportPeriod.THIS_WEEK) StorePrimary.copy(alpha = 0.15f) else StorePrimary.copy(alpha = 0.06f),
                                border = BorderStroke(1.dp, if (selectedPeriod == ReportPeriod.THIS_WEEK) StorePrimary else StorePrimary.copy(alpha = 0.2f)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(
                                        text = LanguageManager.getString("This Week", "এই সপ্তাহ"),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = StorePrimary
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "+₹%.2f".format(weekDebtRepaid),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = StorePrimary
                                    )
                                    Text(
                                        text = "$weekDebtRepaidCount ${if (isBn) "টি জমা" else "rcv'd"}",
                                        fontSize = 10.sp,
                                        color = TextMuted
                                    )
                                }
                            }

                            // This Month Box
                            Surface(
                                modifier = Modifier.weight(1f),
                                color = if (selectedPeriod == ReportPeriod.THIS_MONTH) StoreGold.copy(alpha = 0.15f) else StoreGold.copy(alpha = 0.06f),
                                border = BorderStroke(1.dp, if (selectedPeriod == ReportPeriod.THIS_MONTH) StoreGold else StoreGold.copy(alpha = 0.2f)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(
                                        text = LanguageManager.getString("This Month", "এই মাস"),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = StoreGold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "+₹%.2f".format(monthDebtRepaid),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = StoreGold
                                    )
                                    Text(
                                        text = "$monthDebtRepaidCount ${if (isBn) "টি জমা" else "rcv'd"}",
                                        fontSize = 10.sp,
                                        color = TextMuted
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Selected Period Summary
                        val periodLabel = when (selectedPeriod) {
                            ReportPeriod.TODAY -> if (isBn) "আজকের" else "Today's"
                            ReportPeriod.THIS_WEEK -> if (isBn) "এই সপ্তাহের" else "This Week's"
                            ReportPeriod.THIS_MONTH -> if (isBn) "এই মাসের" else "This Month's"
                            ReportPeriod.ALL_TIME -> if (isBn) "সর্বমোট" else "All Time"
                        }

                        ReportRow(
                            label = LanguageManager.getString("$periodLabel Debt Repaid (Cash In)", "$periodLabel বকেয়া আদায় (ক্যাশ জমা)"),
                            value = "+₹%.2f".format(periodDebtRepaid),
                            valueColor = StoreGreenProfit,
                            isBold = true,
                            badgeText = "$periodDebtRepaidCount ${if (isBn) "টি লেনদেন" else "entries"}"
                        )

                        ReportRow(
                            label = LanguageManager.getString("$periodLabel New Credit Given", "$periodLabel নতুন বাকী বিক্রি"),
                            value = "-₹%.2f".format(periodCreditGiven),
                            valueColor = StoreRedAlert
                        )

                        val netCreditMovement = periodDebtRepaid - periodCreditGiven
                        ReportRow(
                            label = LanguageManager.getString("Net Khata Inflow (Repaid - Given)", "নিট খাতা ক্যাশফ্লো (আদায় - নতুন বাকী)"),
                            value = if (netCreditMovement >= 0) "+₹%.2f".format(netCreditMovement) else "-₹%.2f".format(kotlin.math.abs(netCreditMovement)),
                            valueColor = if (netCreditMovement >= 0) StoreGreenProfit else StoreRedAlert,
                            isBold = true
                        )

                        ReportRow(
                            label = LanguageManager.getString("Total Outstanding Dues Remaining", "বর্তমানে মোট অনাদায়ী বকেয়া"),
                            value = "₹%.2f".format(totalReceivables),
                            valueColor = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedButton(
                            onClick = { showDebtRepaymentDetailsDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                Icons.Default.ReceiptLong,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = LanguageManager.getString(
                                    "View $periodLabel Repayment Details ($periodDebtRepaidCount)",
                                    "$periodLabel আদায়ের বিস্তারিত তালিকা ($periodDebtRepaidCount টি)"
                                ),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Valuation & Dues Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                    elevation = CardDefaults.cardElevation(2.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = LanguageManager.getString("Valuation & Dues Summary", "স্টক মূল্য ও বাকীর হিসাব"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        ReportRow(
                            label = LanguageManager.getString("Inventory Valuation (Cost)", "বর্তমান পণ্যের ক্রয়মূল্য"),
                            value = "₹%.2f".format(totalStockValuationCost),
                            valueColor = StoreSaffronAccent
                        )
                        ReportRow(
                            label = LanguageManager.getString("Customer Dues (Receivables)", "গ্রাহকদের কাছে বকেয়া (পাবো)"),
                            value = "₹%.2f".format(totalReceivables),
                            valueColor = StoreGreenProfit
                        )
                        ReportRow(
                            label = LanguageManager.getString("Customer Debt Repaid This Period", "চলতি সময়ে বকেয়া আদায়"),
                            value = "+₹%.2f".format(periodDebtRepaid),
                            valueColor = StoreGreenProfit,
                            isBold = true
                        )
                        ReportRow(
                            label = LanguageManager.getString("Supplier Dues (Payables)", "মহাজনদের পাওনা (দেবো)"),
                            value = "₹%.2f".format(totalPayables),
                            valueColor = StoreRedPrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Staff Salary & Payroll Report Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                    elevation = CardDefaults.cardElevation(2.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = LanguageManager.getString("Staff Payroll & Salary Summary", "কর্মচারী বেতন ও বকেয়া রিপোর্ট"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        ReportRow(
                            label = LanguageManager.getString("Committed Monthly Payroll", "মাসিক সর্বমোট বেতন বাজেট"),
                            value = "₹%.2f / mo".format(totalCommittedSalary)
                        )
                        ReportRow(
                            label = LanguageManager.getString("Total Salary Dues Accrued", "মোট অর্জিত বেতন বকেয়া"),
                            value = "₹%.2f".format(totalDues)
                        )
                        ReportRow(
                            label = LanguageManager.getString("Total Salary Disbursed (Paid)", "মোট পরিশোধিত বেতন"),
                            value = "₹%.2f".format(totalPaid),
                            valueColor = StoreGreenProfit
                        )
                        ReportRow(
                            label = LanguageManager.getString("Outstanding Salary Owed", "বকেয়া বেতন পাওনা"),
                            value = "₹%.2f".format(totalSalaryOwed),
                            valueColor = if (totalSalaryOwed > 0) StoreRedPrimary else StoreGreenProfit,
                            isBold = true
                        )
                        ReportRow(
                            label = LanguageManager.getString("Outstanding Staff Advances", "কর্মীদের কাছে অগ্রিম ঋণ"),
                            value = "₹%.2f".format(totalAdvances),
                            valueColor = StoreRedPrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                MonthlyProfitTrendWidget(viewModel = viewModel)
                Spacer(modifier = Modifier.height(8.dp))
                WeeklyRevenueTrendWidget(viewModel = viewModel)
                Spacer(modifier = Modifier.height(8.dp))
                LowStockDashboardCardWidget(viewModel = viewModel)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Native Share Report Sheet Button
        Button(
            onClick = {
                try {
                    val periodName = selectedPeriod.name
                    val storeName = com.example.utils.StoreInfoManager.storeName.ifBlank { "Store" }
                    val reportSummary = buildString {
                        appendLine("📊 $storeName REPORT ($periodName)")
                        appendLine("----------------------------------")
                        appendLine("Sales Revenue: ₹${String.format(java.util.Locale.US, "%.2f", report?.totalRevenue ?: 0.0)}")
                        appendLine("Cost of Goods: ₹${String.format(java.util.Locale.US, "%.2f", report?.totalCogs ?: 0.0)}")
                        appendLine("Gross Profit: ₹${String.format(java.util.Locale.US, "%.2f", report?.grossProfit ?: 0.0)}")
                        appendLine("Expenses: ₹${String.format(java.util.Locale.US, "%.2f", report?.totalExpenses ?: 0.0)}")
                        appendLine("Stock Loss (Wastage/Damaged): -₹${String.format(java.util.Locale.US, "%.2f", report?.stockLoss ?: 0.0)}")
                        appendLine("NET PROFIT: ₹${String.format(java.util.Locale.US, "%.2f", report?.netProfit ?: 0.0)}")
                        appendLine("----------------------------------")
                        appendLine("Cash Sales: ₹${String.format(java.util.Locale.US, "%.2f", report?.cashSales ?: 0.0)}")
                        appendLine("UPI Sales: ₹${String.format(java.util.Locale.US, "%.2f", report?.upiSales ?: 0.0)}")
                        appendLine("Credit Sales: ₹${String.format(java.util.Locale.US, "%.2f", report?.creditSales ?: 0.0)}")
                        appendLine("----------------------------------")
                        appendLine("Customer Debt Repaid ($periodName): +₹${String.format(java.util.Locale.US, "%.2f", periodDebtRepaid)} ($periodDebtRepaidCount collections)")
                        appendLine("Today's Debt Repaid: ₹${String.format(java.util.Locale.US, "%.2f", todayDebtRepaid)} ($todayDebtRepaidCount) | This Week: ₹${String.format(java.util.Locale.US, "%.2f", weekDebtRepaid)} ($weekDebtRepaidCount)")
                        appendLine("----------------------------------")
                        appendLine("Online Store Sales: ₹${String.format(java.util.Locale.US, "%.2f", report?.onlineRevenue ?: 0.0)} (${report?.onlineOrderCount ?: 0} orders, ${String.format(java.util.Locale.US, "%.1f", report?.onlineRevenuePercentage ?: 0.0)}%)")
                        appendLine("Walk-in Sales: ₹${String.format(java.util.Locale.US, "%.2f", report?.walkInRevenue ?: 0.0)} (${report?.walkInOrderCount ?: 0} orders)")
                        appendLine("----------------------------------")
                        appendLine("Stock Valuation: ₹${String.format(java.util.Locale.US, "%.2f", totalStockValuationCost)}")
                        appendLine("Customer Dues: ₹${String.format(java.util.Locale.US, "%.2f", totalReceivables)}")
                        appendLine("Supplier Dues: ₹${String.format(java.util.Locale.US, "%.2f", totalPayables)}")
                        appendLine("----------------------------------")
                        appendLine("Committed Monthly Salary: ₹${String.format(java.util.Locale.US, "%.2f", totalCommittedSalary)}")
                        appendLine("Outstanding Salary Owed: ₹${String.format(java.util.Locale.US, "%.2f", totalSalaryOwed)}")
                        appendLine("Outstanding Staff Advances: ₹${String.format(java.util.Locale.US, "%.2f", totalAdvances)}")
                    }

                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "$storeName Business Report")
                        putExtra(Intent.EXTRA_TEXT, reportSummary)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val chooser = Intent.createChooser(shareIntent, "Share Report via").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(chooser)
                } catch (e: Exception) {
                    android.util.Log.e("ReportsScreen", "Error sharing report: ${e.message}", e)
                    android.widget.Toast.makeText(
                        context,
                        if (isBn) "রিপোর্ট শেয়ার করতে সমস্যা হয়েছে" else "Failed to share report: ${e.localizedMessage ?: "Unknown error"}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = StoreRedPrimary)
        ) {
            Icon(Icons.Default.Share, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(LanguageManager.getString("Share Full Business Report", "সম্পূর্ণ রিপোর্ট শেয়ার করুন"))
        }

        if (showDailySummaryDialog) {
            DailySalesSummaryDialog(
                viewModel = viewModel,
                onDismiss = { showDailySummaryDialog = false }
            )
        }

        if (showStockOutReportDialog) {
            StockOutReportDialog(
                viewModel = viewModel,
                onDismiss = { showStockOutReportDialog = false }
            )
        }

        if (showCloudReportsDialog) {
            CloudProfitReportsDialog(
                viewModel = viewModel,
                onDismiss = { showCloudReportsDialog = false }
            )
        }

        if (showComprehensiveReportDialog) {
            ComprehensiveBusinessReportDialog(
                viewModel = viewModel,
                onDismiss = { showComprehensiveReportDialog = false }
            )
        }

        if (showFullExpenseReportDialog) {
            FullExpenseReportDialog(
                viewModel = viewModel,
                initialPeriod = selectedPeriod,
                onDismissRequest = { showFullExpenseReportDialog = false }
            )
        }

        if (showDebtRepaymentDetailsDialog) {
            val periodLabel = when (selectedPeriod) {
                ReportPeriod.TODAY -> if (isBn) "আজকের" else "Today's"
                ReportPeriod.THIS_WEEK -> if (isBn) "এই সপ্তাহের" else "This Week's"
                ReportPeriod.THIS_MONTH -> if (isBn) "এই মাসের" else "This Month's"
                ReportPeriod.ALL_TIME -> if (isBn) "সর্বমোট" else "All Time"
            }
            DebtRepaymentDetailsDialog(
                repayments = periodRepayments,
                periodLabel = periodLabel,
                totalRepaid = periodDebtRepaid,
                onDismiss = { showDebtRepaymentDetailsDialog = false }
            )
        }
    }
}

@Composable
fun DebtRepaymentDetailsDialog(
    repayments: List<LedgerEntry>,
    periodLabel: String,
    totalRepaid: Double,
    onDismiss: () -> Unit
) {
    val isBn = LanguageManager.isBengali
    val sdf = remember { java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", java.util.Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.PriceCheck,
                        contentDescription = null,
                        tint = StoreGreenProfit,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = if (isBn) "$periodLabel বকেয়া আদায় (${repayments.size} টি)" else "$periodLabel Debt Repaid (${repayments.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${if (isBn) "মোট আদায়:" else "Total Repaid:"} ₹%.2f".format(totalRepaid),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = StoreGreenProfit
                )
            }
        },
        text = {
            if (repayments.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (isBn) "এই সময়কালে কোনো বকেয়া আদায় হয়নি" else "No customer debt repayments recorded for this period.",
                            color = TextMuted,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(repayments, key = { it.id }) { entry ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = SurfaceWarm),
                            border = BorderStroke(1.dp, NeutralBorderDivider),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = entry.partyName.ifBlank { if (isBn) "গ্রাহক" else "Customer" },
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = sdf.format(java.util.Date(entry.datetime)),
                                        fontSize = 11.sp,
                                        color = TextMuted
                                    )
                                    if (!entry.note.isNullOrBlank()) {
                                        Text(
                                            text = entry.note,
                                            fontSize = 11.sp,
                                            color = StorePrimary,
                                            maxLines = 1
                                        )
                                    }
                                }
                                Text(
                                    text = "+₹%.2f".format(entry.amount),
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 14.sp,
                                    color = StoreGreenProfit
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(if (isBn) "বন্ধ করুন" else "Close", fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
fun ReportRow(
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified,
    isBold: Boolean = false,
    badgeText: String? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal
            )
            if (badgeText != null) {
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    color = StorePrimary.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = badgeText,
                        fontSize = 9.sp,
                        color = StorePrimary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
            color = valueColor
        )
    }
}
