package com.example.ui.screens.reports

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.*
import com.example.utils.*
import com.example.viewmodel.ReportPeriod
import com.example.viewmodel.StoreViewModel
import java.text.SimpleDateFormat
import java.util.*

enum class AnalyticsTab {
    PROFIT_LOSS,      // Which products are generating the most profit or loss
    MONEY_SPENT,      // Where my money is being spent the most
    FAST_MOVERS       // Which products are selling the fastest
}

enum class ProfitSortOption {
    MOST_PROFITABLE,
    HIGHEST_LOSS,
    HIGHEST_MARGIN,
    LOWEST_MARGIN,
    HIGHEST_REVENUE
}

enum class VelocitySortOption {
    FASTEST_SELLING,
    CRITICAL_STOCKOUT,
    HIGHEST_REVENUE,
    SLOW_MOVERS,
    DEAD_STOCK
}

/**
 * Interactive Deep Business Intelligence Widget embedded on Reports Screen
 */
@Composable
fun ComprehensiveAnalyticsCard(
    viewModel: StoreViewModel,
    onOpenFullReport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isBn = LanguageManager.isBengali
    val report = viewModel.comprehensiveReport
    val isLoading = viewModel.isComprehensiveReportLoading

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onOpenFullReport() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(StorePrimary.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Insights,
                            contentDescription = null,
                            tint = StorePrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = if (isBn) "ব্যবসায়িক বিশ্লেষণ ও পণ্যের রিপোর্ট" else "Business Intelligence & Analytics",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isBn) "লাভ/ক্ষতি, ব্যয়ের খাত ও দ্রুত বিক্রি হওয়া পণ্য" else "Product Profit/Loss, Outflow & Velocity",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                FilledTonalButton(
                    onClick = onOpenFullReport,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(if (isBn) "বিস্তারিত" else "Explore", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(90.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            } else if (report == null) {
                Text(
                    text = if (isBn) "তথ্য লোড হচ্ছে..." else "Loading business insights...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // 3 Highlights Row: Profit/Loss, Spending, Velocity
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 1. Most Profitable
                    val topProfit = report.mostProfitableProduct
                    HighlightInsightPill(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.TrendingUp,
                        iconTint = StoreGreenProfit,
                        title = if (isBn) "সর্বোচ্চ লাভ" else "Top Profit",
                        primaryText = if (topProfit != null) topProfit.getDisplayName(isBn) else "—",
                        secondaryText = if (topProfit != null) "+₹${"%.0f".format(topProfit.netProfit)} (${"%.0f".format(topProfit.profitMarginPercent)}%)" else if (isBn) "বিক্রি নেই" else "No sales",
                        secondaryColor = StoreGreenProfit
                    )

                    // 2. Biggest Money Outflow
                    val topOutflow = report.topSpendingCategory
                    HighlightInsightPill(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.AccountBalanceWallet,
                        iconTint = Color(0xFF1E88E5),
                        title = if (isBn) "প্রধান ব্যয়" else "Top Spending",
                        primaryText = if (topOutflow != null) topOutflow.getDisplayName(isBn) else "—",
                        secondaryText = if (topOutflow != null) "₹${"%.0f".format(topOutflow.amount)} (${"%.0f".format(topOutflow.percentage)}%)" else "₹0",
                        secondaryColor = Color(0xFF1E88E5)
                    )

                    // 3. Fastest Moving Product
                    val topFast = report.topFastestProduct
                    HighlightInsightPill(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Bolt,
                        iconTint = Color(0xFFFB8C00),
                        title = if (isBn) "দ্রুত বিক্রি" else "Fastest Seller",
                        primaryText = if (topFast != null) topFast.getDisplayName(isBn) else "—",
                        secondaryText = if (topFast != null) "${"%.1f".format(topFast.dailyVelocity)} ${topFast.unitType}/day" else if (isBn) "বিক্রি নেই" else "No sales",
                        secondaryColor = Color(0xFFFB8C00)
                    )
                }

                // Loss Alert Banner if any loss-making product exists
                if (report.lossMakingProducts.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    val lossCount = report.lossMakingProducts.size
                    val totalLossAmount = report.lossMakingProducts.sumOf { it.netProfit }.let { kotlin.math.abs(it) }
                    Surface(
                        color = StoreRedAlert.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, StoreRedAlert.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = StoreRedAlert, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isBn) 
                                    "সতর্কতা: $lossCount টি পণ্যে ক্ষতি হচ্ছে (মোট ₹${"%.0f".format(totalLossAmount)})"
                                    else "Alert: $lossCount product(s) operating at a loss (-₹${"%.0f".format(totalLossAmount)})",
                                fontSize = 11.sp,
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
fun HighlightInsightPill(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    title: String,
    primaryText: String,
    secondaryText: String,
    secondaryColor: Color
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(12.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = title,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = primaryText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = secondaryText,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = secondaryColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Full Detailed Dialog for Comprehensive Business Intelligence
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComprehensiveBusinessReportDialog(
    viewModel: StoreViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isBn = LanguageManager.isBengali
    val selectedPeriod = viewModel.selectedReportPeriod
    val report = viewModel.comprehensiveReport
    val isLoading = viewModel.isComprehensiveReportLoading

    var currentTab by remember { mutableStateOf(AnalyticsTab.PROFIT_LOSS) }
    var searchQuery by remember { mutableStateOf("") }
    var profitSort by remember { mutableStateOf(ProfitSortOption.MOST_PROFITABLE) }
    var velocitySort by remember { mutableStateOf(VelocitySortOption.FASTEST_SELLING) }
    var onlyLossProducts by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 16.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top Header with Close & Share
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 2.dp
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = onDismiss) {
                                    Icon(Icons.Default.Close, contentDescription = "Close")
                                }
                                Column {
                                    Text(
                                        text = if (isBn) "ব্যবসায়িক গভীর বিশ্লেষণ রিপোর্ট" else "Comprehensive Business Analytics",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = when (selectedPeriod) {
                                            ReportPeriod.TODAY -> if (isBn) "আজকের বিশ্লেষণ" else "Today's Performance"
                                            ReportPeriod.THIS_WEEK -> if (isBn) "এই সপ্তাহের বিশ্লেষণ" else "This Week's Performance"
                                            ReportPeriod.THIS_MONTH -> if (isBn) "এই মাসের বিশ্লেষণ" else "This Month's Performance"
                                            ReportPeriod.ALL_TIME -> if (isBn) "সর্বমোট বিশ্লেষণ" else "All Time Performance"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Row {
                                IconButton(
                                    onClick = {
                                        if (report != null) {
                                            shareComprehensiveReport(context, report, selectedPeriod, isBn)
                                        } else {
                                            Toast.makeText(context, "No report data to share", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = "Share", tint = StorePrimary)
                                }
                            }
                        }

                        // Period Selector Chips
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            ReportPeriod.values().forEach { period ->
                                val isSelected = selectedPeriod == period
                                val label = when (period) {
                                    ReportPeriod.TODAY -> if (isBn) "আজ" else "Today"
                                    ReportPeriod.THIS_WEEK -> if (isBn) "এই সপ্তাহ" else "This Week"
                                    ReportPeriod.THIS_MONTH -> if (isBn) "এই মাস" else "This Month"
                                    ReportPeriod.ALL_TIME -> if (isBn) "সর্বমোট" else "All Time"
                                }
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        viewModel.loadPnlReport(period)
                                    },
                                    label = { Text(label, fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Navigation Tabs (3 Pillars Requested by User)
                        PrimaryTabRow(
                            selectedTabIndex = currentTab.ordinal,
                            containerColor = MaterialTheme.colorScheme.surface,
                            divider = {}
                        ) {
                            Tab(
                                selected = currentTab == AnalyticsTab.PROFIT_LOSS,
                                onClick = { currentTab = AnalyticsTab.PROFIT_LOSS },
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.TrendingUp, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(if (isBn) "লাভ ও ক্ষতি" else "Profit / Loss", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            )
                            Tab(
                                selected = currentTab == AnalyticsTab.MONEY_SPENT,
                                onClick = { currentTab = AnalyticsTab.MONEY_SPENT },
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(if (isBn) "ব্যয়ের হিসাব" else "Money Spent", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            )
                            Tab(
                                selected = currentTab == AnalyticsTab.FAST_MOVERS,
                                onClick = { currentTab = AnalyticsTab.FAST_MOVERS },
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(if (isBn) "দ্রুত বিক্রি" else "Fast Movers", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            )
                        }
                    }
                }

                // Tab Content Body
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(if (isBn) "রিপোর্ট প্রস্তুত হচ্ছে..." else "Calculating comprehensive business intelligence...")
                        }
                    }
                } else if (report == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(if (isBn) "কোন তথ্য পাওয়া যায়নি" else "No data available for this period.")
                    }
                } else {
                    when (currentTab) {
                        AnalyticsTab.PROFIT_LOSS -> {
                            ProfitLossTabContent(
                                report = report,
                                searchQuery = searchQuery,
                                onSearchQueryChange = { searchQuery = it },
                                sortOption = profitSort,
                                onSortOptionChange = { profitSort = it },
                                onlyLoss = onlyLossProducts,
                                onOnlyLossChange = { onlyLossProducts = it },
                                isBn = isBn
                            )
                        }
                        AnalyticsTab.MONEY_SPENT -> {
                            MoneySpentTabContent(
                                report = report,
                                isBn = isBn,
                                viewModel = viewModel,
                                selectedPeriod = selectedPeriod
                            )
                        }
                        AnalyticsTab.FAST_MOVERS -> {
                            FastMoversTabContent(
                                report = report,
                                searchQuery = searchQuery,
                                onSearchQueryChange = { searchQuery = it },
                                sortOption = velocitySort,
                                onSortOptionChange = { velocitySort = it },
                                isBn = isBn
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * TAB 1: Product Profit & Loss breakdown
 */
@Composable
fun ProfitLossTabContent(
    report: ComprehensiveBusinessReport,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    sortOption: ProfitSortOption,
    onSortOptionChange: (ProfitSortOption) -> Unit,
    onlyLoss: Boolean,
    onOnlyLossChange: (Boolean) -> Unit,
    isBn: Boolean
) {
    val filteredList = remember(report, searchQuery, sortOption, onlyLoss) {
        var list = report.allProductProfits

        if (searchQuery.isNotBlank()) {
            list = list.filter {
                it.productNameEn.contains(searchQuery, ignoreCase = true) ||
                it.productNameBn.contains(searchQuery, ignoreCase = true) ||
                it.category.contains(searchQuery, ignoreCase = true)
            }
        }

        if (onlyLoss) {
            list = list.filter { it.isLossMaking || it.isLowMargin }
        }

        when (sortOption) {
            ProfitSortOption.MOST_PROFITABLE -> list.sortedByDescending { it.netProfit }
            ProfitSortOption.HIGHEST_LOSS -> list.sortedBy { it.netProfit }
            ProfitSortOption.HIGHEST_MARGIN -> list.sortedByDescending { it.profitMarginPercent }
            ProfitSortOption.LOWEST_MARGIN -> list.sortedBy { it.profitMarginPercent }
            ProfitSortOption.HIGHEST_REVENUE -> list.sortedByDescending { it.salesRevenue }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        // Summary Cards
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Total Gross Profit
                Surface(
                    modifier = Modifier.weight(1f),
                    color = StoreGreenProfit.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, StoreGreenProfit.copy(alpha = 0.3f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = if (isBn) "মোট পণ্য লাভ (Gross)" else "Product Gross Profit",
                            fontSize = 11.sp,
                            color = StoreGreenProfit,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "₹%.2f".format(report.grossProfit),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = StoreGreenProfit
                        )
                        Text(
                            text = if (report.totalRevenue > 0) "${"%.1f".format((report.grossProfit / report.totalRevenue) * 100)}% overall margin" else "0% margin",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Loss Making Products Count
                val lossCount = report.lossMakingProducts.size
                Surface(
                    modifier = Modifier.weight(1f),
                    color = if (lossCount > 0) StoreRedAlert.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, if (lossCount > 0) StoreRedAlert.copy(alpha = 0.3f) else MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = if (isBn) "ক্ষতিতে চলা পণ্য" else "Loss-Making Products",
                            fontSize = 11.sp,
                            color = if (lossCount > 0) StoreRedAlert else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "$lossCount Items",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (lossCount > 0) StoreRedAlert else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (lossCount > 0) "Sold below cost/damaged" else "All items healthy",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Search & Filters Row
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(if (isBn) "পণ্যের নাম দিয়ে খুঁজুন..." else "Search product name...", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surface)
                )

                // Filter & Sort Pills
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    item {
                        FilterChip(
                            selected = onlyLoss,
                            onClick = { onOnlyLossChange(!onlyLoss) },
                            label = { Text(if (isBn) "⚠️ শুধু ক্ষতি/কম লাভ" else "⚠️ Loss/Low Margin Only", fontSize = 11.sp) },
                            leadingIcon = if (onlyLoss) {
                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }
                            } else null
                        )
                    }

                    item {
                        FilterChip(
                            selected = sortOption == ProfitSortOption.MOST_PROFITABLE,
                            onClick = { onSortOptionChange(ProfitSortOption.MOST_PROFITABLE) },
                            label = { Text(if (isBn) "সর্বোচ্চ লাভ" else "Highest Profit", fontSize = 11.sp) }
                        )
                    }

                    item {
                        FilterChip(
                            selected = sortOption == ProfitSortOption.HIGHEST_LOSS,
                            onClick = { onSortOptionChange(ProfitSortOption.HIGHEST_LOSS) },
                            label = { Text(if (isBn) "সর্বাধিক ক্ষতি" else "Highest Loss", fontSize = 11.sp) }
                        )
                    }

                    item {
                        FilterChip(
                            selected = sortOption == ProfitSortOption.HIGHEST_MARGIN,
                            onClick = { onSortOptionChange(ProfitSortOption.HIGHEST_MARGIN) },
                            label = { Text(if (isBn) "বেশি মার্জিন %" else "High Margin %", fontSize = 11.sp) }
                        )
                    }

                    item {
                        FilterChip(
                            selected = sortOption == ProfitSortOption.HIGHEST_REVENUE,
                            onClick = { onSortOptionChange(ProfitSortOption.HIGHEST_REVENUE) },
                            label = { Text(if (isBn) "সর্বোচ্চ বিক্রি" else "Top Revenue", fontSize = 11.sp) }
                        )
                    }
                }
            }
        }

        // List Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isBn) "পণ্যের তালিকা (${filteredList.size} টি)" else "Product Profitability List (${filteredList.size})",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (filteredList.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isBn) "কোন পণ্য পাওয়া যায়নি" else "No products found matching filters",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(filteredList, key = { it.productId }) { item ->
                ProductProfitCard(item = item, isBn = isBn)
            }
        }
    }
}

@Composable
fun ProductProfitCard(
    item: ProductProfitItem,
    isBn: Boolean
) {
    val isProfitable = item.netProfit >= 0.0
    val cardBorderColor = when {
        item.isLossMaking -> StoreRedAlert.copy(alpha = 0.5f)
        item.isHighMargin -> StoreGreenProfit.copy(alpha = 0.4f)
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, cardBorderColor)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Title & Margin Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = item.getDisplayName(isBn),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (item.isArchivedOrDeleted) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = if (isBn) "আর্কাইভড" else "Archived",
                                    fontSize = 9.sp,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    val priceDetail = if (item.unitsSold > 0.0 && Math.abs(item.effectiveSellingPrice - item.sellingPrice) > 0.05) {
                        "${item.category} • Cost: ₹${"%.1f".format(item.costPrice)} | Price: ₹${"%.1f".format(item.sellingPrice)} (Avg Sold: ₹${"%.2f".format(item.effectiveSellingPrice)})"
                    } else {
                        "${item.category} • Cost: ₹${"%.1f".format(item.costPrice)} | Price: ₹${"%.1f".format(item.sellingPrice)}"
                    }

                    Text(
                        text = priceDetail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }

                // Profit / Loss Badge
                Surface(
                    color = if (isProfitable) StoreGreenProfit.copy(alpha = 0.12f) else StoreRedAlert.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isProfitable) Icons.Default.TrendingUp else Icons.Default.TrendingDown,
                            contentDescription = null,
                            tint = if (isProfitable) StoreGreenProfit else StoreRedAlert,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${if (isProfitable) "+" else ""}${"%.1f".format(item.profitMarginPercent)}%",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = if (isProfitable) StoreGreenProfit else StoreRedAlert
                        )
                    }
                }
            }

            // Active Offer Banner if applicable
            if (item.activeOfferTitle != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.LocalOffer, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${if (isBn) "সক্রিয় অফার:" else "Active Offer:"} ${item.activeOfferTitle}${if (item.activeOfferDiscountPercent > 0) " (${"%.0f".format(item.activeOfferDiscountPercent)}% Off)" else ""}",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Metrics Grid (Revenue, COGS, Loss, Net Profit)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(if (isBn) "বিক্রি সংখ্যা" else "Units Sold", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${"%.1f".format(item.unitsSold)} ${item.unitType}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }

                Column {
                    Text(if (isBn) "মোট বিক্রি (Revenue)" else "Revenue", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("₹${"%.0f".format(item.salesRevenue)}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }

                Column {
                    Text(if (isBn) "পণ্য ক্রয়মূল্য (COGS)" else "Cost (COGS)", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("₹${"%.0f".format(item.cogs)}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(if (isBn) "প্রকৃত লাভ / ক্ষতি" else "Net Profit", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = "${if (isProfitable) "+" else ""}₹${"%.1f".format(item.netProfit)}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isProfitable) StoreGreenProfit else StoreRedAlert
                    )
                }
            }

            // Diagnostic message for loss making items
            if (!isProfitable && item.lossReason != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    color = StoreRedAlert.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = StoreRedAlert, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        val reasonText = when (item.lossReason) {
                            "SOLD_BELOW_COST" -> if (isBn)
                                "বিক্রয়মূল্য (গড় ₹${"%.2f".format(item.effectiveSellingPrice)}) ক্রয়মূল্যের (₹${"%.2f".format(item.effectiveCostPrice)}) চেয়ে কম ছিল"
                                else "Avg selling price (₹${"%.2f".format(item.effectiveSellingPrice)}) was below cost price (₹${"%.2f".format(item.effectiveCostPrice)})"
                            "WASTAGE_EXCEEDED_PROFIT" -> if (isBn)
                                "নষ্ট ও মেয়াদোত্তীর্ণ মালের ক্ষতি মোট লাভকে ছাড়িয়ে গেছে"
                                else "Wastage / damaged stock loss exceeded sales margin"
                            "DISCOUNT_ERODED_MARGIN" -> if (isBn)
                                "অতিরিক্ত ছাড় বা অফারের কারণে লাভ কমে লোকসান হয়েছে"
                                else "High discount / promotional offers reduced profit into negative"
                            else -> if (isBn) "ক্রয়মূল্য ও ব্যয়ের তুলনায় মুনাফা কম" else "Operational cost exceeded gross margin"
                        }
                        Text(
                            text = reasonText,
                            fontSize = 10.sp,
                            color = StoreRedAlert,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // If there is Stock Loss (Damaged/Expired), show note
            if (item.stockLossCost > 0.0) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    color = StoreRedAlert.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = StoreRedAlert, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isBn) 
                                "নষ্ট/ক্ষতিগ্রস্ত স্টক বাদ: ${"%.1f".format(item.stockLossQty)} ${item.unitType} (-₹${"%.0f".format(item.stockLossCost)})"
                                else "Stock Loss deducted: ${"%.1f".format(item.stockLossQty)} ${item.unitType} (-₹${"%.0f".format(item.stockLossCost)})",
                            fontSize = 10.sp,
                            color = StoreRedAlert
                        )
                    }
                }
            }
        }
    }
}

/**
 * TAB 2: Where Money is Being Spent the Most
 */
@Composable
fun MoneySpentTabContent(
    report: ComprehensiveBusinessReport,
    isBn: Boolean,
    viewModel: StoreViewModel? = null,
    selectedPeriod: ReportPeriod = ReportPeriod.THIS_MONTH
) {
    val context = LocalContext.current
    var showFullExpenseDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        // Master Outflow Hero Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E88E5)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (isBn) "সর্বমোট টাকা ব্যয়ের পরিমাণ (Total Outflow)" else "TOTAL MONEY SPENT / OUTFLOW",
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "₹%.2f".format(Locale.US, report.totalOutflow),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isBn) 
                            "পণ্য ক্রয়, কর্মচারী বেতন, দোকান খরচ এবং নষ্ট মালের মোট ব্যয়" 
                            else "Includes inventory purchases, staff salaries, shop expenses & wastage",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.9f)
                    )

                    if (viewModel != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { showFullExpenseDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Assessment, contentDescription = null, tint = Color(0xFF1E88E5), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isBn) "দোকানের খরচের বিস্তারিত রিপোর্ট দেখুন" else "View Detailed Expense Report",
                                color = Color(0xFF1E88E5),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // Outflow Distribution Bar
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = if (isBn) "টাকা খরচের অনুপাত ও বিভাজন" else "Money Outflow Distribution",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    // Multi-color segmented progress bar
                    if (report.outflowCategories.isNotEmpty() && report.totalOutflow > 0) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(16.dp)
                                .clip(RoundedCornerShape(8.dp))
                        ) {
                            report.outflowCategories.forEach { cat ->
                                if (cat.percentage > 0.5) {
                                    Box(
                                        modifier = Modifier
                                            .weight(cat.percentage.toFloat())
                                            .fillMaxHeight()
                                            .background(Color(cat.colorHex))
                                    )
                                }
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(16.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Quick Breakdown Summary
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(if (isBn) "পণ্য ক্রয় (Stock)" else "Stock Inflow", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("₹${"%.0f".format(report.supplierPurchasesSpend)}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E88E5))
                        }
                        Column {
                            Text(if (isBn) "কর্মচারী বেতন" else "Staff Salaries", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("₹${"%.0f".format(report.staffPayrollSpend)}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF8E24AA))
                        }
                        Column {
                            Text(if (isBn) "দোকানের খরচ" else "Shop Expenses", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("₹${"%.0f".format(report.operationalExpensesSpend)}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFB8C00))
                        }
                        Column {
                            Text(if (isBn) "নষ্ট মাল ক্ষতি" else "Stock Loss", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("₹${"%.0f".format(report.stockLossSpend)}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = StoreRedAlert)
                        }
                    }
                }
            }
        }

        // Section Title: Spending by Category Ranked
        item {
            Text(
                text = if (isBn) "কোথায় সবচেয়ে বেশি টাকা খরচ হয়েছে (র‍্যাঙ্কিং)" else "Where Money is Spent Most (Ranked Categories)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
        }

        if (report.outflowCategories.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (isBn) "এই সময়কালে কোন ব্যয়ের রেকর্ড পাওয়া যায়নি" else "No expense or purchase records for this period.")
                }
            }
        } else {
            items(report.outflowCategories, key = { it.categoryKey }, contentType = { "OUTFLOW_CATEGORY_CARD" }) { category ->
                OutflowCategoryCard(category = category, isBn = isBn)
            }
        }

        // Top Single Expense Transactions
        if (report.topExpenseItems.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isBn) "সবচেয়ে বড় খরচের ভাউচারসমূহ" else "Largest Expense Line Items",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            items(report.topExpenseItems, key = { "${it.titleEn}_${it.date}_${it.amount}" }, contentType = { "EXPENSE_LINE_ITEM" }) { exp ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = exp.titleEn, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            val dateStr = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(exp.date))
                            Text(
                                text = "$dateStr ${if (!exp.note.isNullOrBlank()) "• ${exp.note}" else ""}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "-₹%.2f".format(exp.amount),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = StoreRedAlert
                        )
                    }
                }
            }
        }
    }

    if (showFullExpenseDialog && viewModel != null) {
        FullExpenseReportDialog(
            viewModel = viewModel,
            initialPeriod = selectedPeriod,
            onDismissRequest = { showFullExpenseDialog = false }
        )
    }
}

@Composable
fun OutflowCategoryCard(
    category: OutflowCategoryItem,
    isBn: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(Color(category.colorHex).copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(Color(category.colorHex), CircleShape)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = category.getDisplayName(isBn),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "${category.transactionCount} ${if (isBn) "টি লেনদেন" else "entries"}",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "₹%.2f".format(category.amount),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "${"%.1f".format(category.percentage)}% of total",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        color = Color(category.colorHex)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Percentage Linear Progress Indicator
            LinearProgressIndicator(
                progress = { (category.percentage / 100.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = Color(category.colorHex),
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}

/**
 * TAB 3: Which Products are Selling the Fastest (Sales Velocity & Runout Prediction)
 */
@Composable
fun FastMoversTabContent(
    report: ComprehensiveBusinessReport,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    sortOption: VelocitySortOption,
    onSortOptionChange: (VelocitySortOption) -> Unit,
    isBn: Boolean
) {
    val filteredList = remember(report, searchQuery, sortOption) {
        var list = report.allProductVelocities

        if (searchQuery.isNotBlank()) {
            list = list.filter {
                it.productNameEn.contains(searchQuery, ignoreCase = true) ||
                it.productNameBn.contains(searchQuery, ignoreCase = true) ||
                it.category.contains(searchQuery, ignoreCase = true)
            }
        }

        when (sortOption) {
            VelocitySortOption.FASTEST_SELLING -> list.sortedByDescending { it.dailyVelocity }
            VelocitySortOption.CRITICAL_STOCKOUT -> list.sortedBy { it.daysToRunout }
            VelocitySortOption.HIGHEST_REVENUE -> list.sortedByDescending { it.totalRevenue }
            VelocitySortOption.SLOW_MOVERS -> list.filter { it.unitsSold > 0 }.sortedBy { it.dailyVelocity }
            VelocitySortOption.DEAD_STOCK -> list.filter { it.classification == VelocityClassification.DEAD_STOCK }.sortedByDescending { it.currentStock }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        // Velocity Highlights Row
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Top Speed Item
                val topSpeed = report.topFastestProduct
                Surface(
                    modifier = Modifier.weight(1f),
                    color = Color(0xFFFB8C00).copy(alpha = 0.12f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFFFB8C00).copy(alpha = 0.3f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = if (isBn) "🚀 দ্রুততম বিক্রিত পণ্য" else "🚀 #1 Fastest Seller",
                            fontSize = 11.sp,
                            color = Color(0xFFE65100),
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = topSpeed?.getDisplayName(isBn) ?: "—",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (topSpeed != null) "${"%.1f".format(topSpeed.dailyVelocity)} ${topSpeed.unitType}/day" else "0 units",
                            fontSize = 11.sp,
                            color = Color(0xFFE65100),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Critical Stockout Alert Count
                val criticalCount = report.criticalRunoutProducts.size
                Surface(
                    modifier = Modifier.weight(1f),
                    color = if (criticalCount > 0) StoreRedAlert.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, if (criticalCount > 0) StoreRedAlert.copy(alpha = 0.3f) else MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = if (isBn) "⚠️ দ্রুত ফুরিয়ে যাবে (<৩ দিন)" else "⚠️ Critical Runout (<3d)",
                            fontSize = 11.sp,
                            color = if (criticalCount > 0) StoreRedAlert else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "$criticalCount Products",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (criticalCount > 0) StoreRedAlert else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (criticalCount > 0) "Order soon from supplier" else "Inventory healthy",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Search & Filter Row
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(if (isBn) "পণ্যের নাম বা ক্যাটাগরি দিয়ে খুঁজুন..." else "Search fast sellers...", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surface)
                )

                // Velocity Sort Chips
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    item {
                        FilterChip(
                            selected = sortOption == VelocitySortOption.FASTEST_SELLING,
                            onClick = { onSortOptionChange(VelocitySortOption.FASTEST_SELLING) },
                            label = { Text(if (isBn) "🚀 দ্রুত বিক্রি (Units/Day)" else "🚀 Fastest Velocity", fontSize = 11.sp) }
                        )
                    }

                    item {
                        FilterChip(
                            selected = sortOption == VelocitySortOption.CRITICAL_STOCKOUT,
                            onClick = { onSortOptionChange(VelocitySortOption.CRITICAL_STOCKOUT) },
                            label = { Text(if (isBn) "⚠️ আগে ফুরাবে (Runway)" else "⚠️ Stockout Runway", fontSize = 11.sp) }
                        )
                    }

                    item {
                        FilterChip(
                            selected = sortOption == VelocitySortOption.DEAD_STOCK,
                            onClick = { onSortOptionChange(VelocitySortOption.DEAD_STOCK) },
                            label = { Text(if (isBn) "💤 অবিক্রিত মাল (Dead Stock)" else "💤 Dead Stock (0 Sold)", fontSize = 11.sp) }
                        )
                    }

                    item {
                        FilterChip(
                            selected = sortOption == VelocitySortOption.SLOW_MOVERS,
                            onClick = { onSortOptionChange(VelocitySortOption.SLOW_MOVERS) },
                            label = { Text(if (isBn) "🐢 ধীরগতির পণ্য" else "🐢 Slow Movers", fontSize = 11.sp) }
                        )
                    }

                    item {
                        FilterChip(
                            selected = sortOption == VelocitySortOption.HIGHEST_REVENUE,
                            onClick = { onSortOptionChange(VelocitySortOption.HIGHEST_REVENUE) },
                            label = { Text(if (isBn) "মোট বিক্রি টাকা" else "Top Revenue", fontSize = 11.sp) }
                        )
                    }
                }
            }
        }

        // List Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isBn) "বিক্রির গতিবেগ তালিকা (${filteredList.size} টি)" else "Sales Velocity & Runway (${filteredList.size})",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (filteredList.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isBn) "কোন পণ্য পাওয়া যায়নি" else "No products matching filters",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(filteredList, key = { it.productId }) { item ->
                ProductVelocityCard(item = item, isBn = isBn)
            }
        }
    }
}

@Composable
fun ProductVelocityCard(
    item: ProductVelocityItem,
    isBn: Boolean
) {
    val statusColor = when (item.runoutRisk) {
        StockRunoutRisk.CRITICAL -> StoreRedAlert
        StockRunoutRisk.WARNING -> Color(0xFFFB8C00)
        StockRunoutRisk.OUT_OF_STOCK -> StoreRedPrimary
        StockRunoutRisk.SAFE -> StoreGreenProfit
        StockRunoutRisk.NO_DATA -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (item.runoutRisk == StockRunoutRisk.CRITICAL) StoreRedAlert.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header Row: Name & Speed Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.getDisplayName(isBn),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${item.category} • Current Stock: ${"%.1f".format(item.currentStock)} ${item.unitType}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }

                // Daily Velocity Badge
                Surface(
                    color = Color(0xFFFB8C00).copy(alpha = 0.12f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Bolt, contentDescription = null, tint = Color(0xFFE65100), modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${"%.1f".format(item.dailyVelocity)}/day",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = Color(0xFFE65100)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Metrics Row: Total Sold, Daily Speed, Revenue, Runway Days
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(if (isBn) "মোট বিক্রি" else "Total Sold", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${"%.1f".format(item.unitsSold)} ${item.unitType}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }

                Column {
                    Text(if (isBn) "মোট বিল সংখ্যা" else "In Bills", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${item.billsCount} orders", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }

                Column {
                    Text(if (isBn) "মোট বিক্রয়মূল্য" else "Total Rev", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("₹${"%.0f".format(item.totalRevenue)}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(if (isBn) "স্টক শেষ হওয়ার পূর্বাভাস" else "Stockout Runway", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val runwayText = when {
                        item.currentStock <= 0 -> if (isBn) "স্টক শেষ (০)" else "Out of Stock"
                        item.dailyVelocity <= 0.0001 -> if (isBn) "বিক্রি নেই" else "No Sales"
                        item.daysToRunout < 1.0 -> if (isBn) "আজকেই শেষ হবে" else "< 1 Day Left"
                        item.daysToRunout < 99 -> "${"%.1f".format(item.daysToRunout)} Days left"
                        else -> "> 90 Days"
                    }
                    Text(
                        text = runwayText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                }
            }

            // Urgent Reorder Banner if critical
            if (item.runoutRisk == StockRunoutRisk.CRITICAL && item.currentStock > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    color = StoreRedAlert.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = StoreRedAlert, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isBn) 
                                "জরুরি সতর্কতা: বর্তমান বিক্রির গতি অনুযায়ী ৩ দিনের মধ্যে স্টক শেষ হয়ে যাবে!" 
                                else "Urgent: Stock will run out in < 3 days at current sales speed. Restock soon!",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = StoreRedAlert
                        )
                    }
                }
            }
        }
    }
}

/**
 * Share Comprehensive Report via Native Android Share Sheet
 */
fun shareComprehensiveReport(
    context: Context,
    report: ComprehensiveBusinessReport,
    period: ReportPeriod,
    isBn: Boolean
) {
    try {
        val storeName = StoreInfoManager.storeName.ifBlank { "Store" }
        val periodName = when (period) {
            ReportPeriod.TODAY -> "TODAY"
            ReportPeriod.THIS_WEEK -> "THIS WEEK"
            ReportPeriod.THIS_MONTH -> "THIS MONTH"
            ReportPeriod.ALL_TIME -> "ALL TIME"
        }

        val text = buildString {
            appendLine("📊 $storeName — COMPREHENSIVE BUSINESS INTELLIGENCE")
            appendLine("Period: $periodName")
            appendLine("==========================================")
            appendLine("1. 📈 PRODUCT PROFIT & LOSS INTELLIGENCE")
            appendLine("• Total Sales Revenue: ₹${"%.2f".format(report.totalRevenue)}")
            appendLine("• Cost of Goods (COGS): ₹${"%.2f".format(report.totalCogs)}")
            appendLine("• Gross Product Profit: ₹${"%.2f".format(report.grossProfit)}")
            if (report.mostProfitableProduct != null) {
                val mp = report.mostProfitableProduct
                appendLine("🏆 Top Profit Product: ${mp.productNameEn} (+₹${"%.2f".format(mp.netProfit)}, ${"%.1f".format(mp.profitMarginPercent)}% margin)")
            }
            if (report.lossMakingProducts.isNotEmpty()) {
                appendLine("⚠️ Loss-Making Products (${report.lossMakingProducts.size} items):")
                report.lossMakingProducts.take(3).forEach {
                    appendLine("   - ${it.productNameEn}: ₹${"%.2f".format(it.netProfit)} net")
                }
            }
            appendLine("------------------------------------------")
            appendLine("2. 💰 WHERE MONEY IS BEING SPENT THE MOST")
            appendLine("• Total Outflow: ₹${"%.2f".format(report.totalOutflow)}")
            report.outflowCategories.forEach { cat ->
                appendLine("• ${cat.categoryNameEn}: ₹${"%.2f".format(cat.amount)} (${"%.1f".format(cat.percentage)}%)")
            }
            appendLine("------------------------------------------")
            appendLine("3. 🚀 FASTEST SELLING PRODUCTS & VELOCITY")
            report.fastestMovingProducts.take(5).forEachIndexed { idx, fp ->
                val runway = if (fp.daysToRunout < 99) "${"%.1f".format(fp.daysToRunout)}d left" else "safe"
                appendLine("#${idx + 1} ${fp.productNameEn}: ${"%.1f".format(fp.dailyVelocity)} ${fp.unitType}/day (Stock: ${"%.0f".format(fp.currentStock)}, Runway: $runway)")
            }
            if (report.criticalRunoutProducts.isNotEmpty()) {
                appendLine("⚠️ Critical Stockout Risk (<3 days left):")
                report.criticalRunoutProducts.take(3).forEach {
                    appendLine("   - ${it.productNameEn}: ${"%.1f".format(it.daysToRunout)} days remaining")
                }
            }
            appendLine("==========================================")
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "$storeName Comprehensive Business Report ($periodName)")
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(shareIntent, "Share Comprehensive Analytics via").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    } catch (e: Exception) {
        Toast.makeText(context, "Error sharing report: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
