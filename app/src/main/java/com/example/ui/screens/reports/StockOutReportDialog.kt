package com.example.ui.screens.reports

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.local.entities.StockOutEntry
import com.example.data.repository.StockOutReportSummary
import com.example.ui.theme.*
import com.example.utils.LanguageManager
import com.example.utils.StockOutReportHelper
import com.example.viewmodel.ReportPeriod
import com.example.viewmodel.StoreViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockOutReportDialog(
    viewModel: StoreViewModel,
    onDismiss: () -> Unit
) {
    val isBn = LanguageManager.isBengali
    val context = LocalContext.current

    var selectedPeriod by remember { mutableStateOf(viewModel.selectedReportPeriod) }
    var isCustomDateRange by remember { mutableStateOf(false) }

    val calendar = Calendar.getInstance()
    var customStartDate by remember {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_MONTH, -7)
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        mutableStateOf(c.timeInMillis)
    }
    var customEndDate by remember {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 23)
        c.set(Calendar.MINUTE, 59)
        c.set(Calendar.SECOND, 59)
        mutableStateOf(c.timeInMillis)
    }

    var searchQuery by remember { mutableStateOf("") }
    var selectedReasonFilter by remember { mutableStateOf("ALL") }

    // Load report when period changes
    LaunchedEffect(selectedPeriod, isCustomDateRange, customStartDate, customEndDate) {
        if (isCustomDateRange) {
            viewModel.loadStockOutReport(customStartDate, customEndDate)
        } else {
            viewModel.loadStockOutReport(selectedPeriod)
        }
    }

    val reportSummary = viewModel.stockOutReport

    val sdfDisplay = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
    val itemSdf = remember { SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.94f),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Scaffold(
                topBar = {
                    Surface(
                        tonalElevation = 2.dp,
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .background(StoreRedAlert.copy(alpha = 0.12f), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.RemoveShoppingCart,
                                            contentDescription = null,
                                            tint = StoreRedAlert,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = if (isBn) "স্টক আউট ও অপচয় ক্ষতি রিপোর্ট" else "Stock-Out & Wastage Cost Report",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = if (isBn) "ক্ষতিগ্রস্ত, মেয়াদোত্তীর্ণ ও অপচয় খরচ ট্র্যাকিং" else "Cost tracking of damaged, expired, & wastage stock",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                IconButton(onClick = onDismiss) {
                                    Icon(Icons.Default.Close, contentDescription = "Close")
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Period selector chips
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                ReportPeriod.values().forEach { period ->
                                    val isSelected = !isCustomDateRange && selectedPeriod == period
                                    val label = when (period) {
                                        ReportPeriod.TODAY -> if (isBn) "আজ" else "Today"
                                        ReportPeriod.THIS_WEEK -> if (isBn) "এই সপ্তাহ" else "This Week"
                                        ReportPeriod.THIS_MONTH -> if (isBn) "এই মাস" else "This Month"
                                        ReportPeriod.ALL_TIME -> if (isBn) "সব সময়" else "All Time"
                                    }
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = {
                                            isCustomDateRange = false
                                            selectedPeriod = period
                                        },
                                        label = { Text(label, fontSize = 12.sp) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                FilterChip(
                                    selected = isCustomDateRange,
                                    onClick = { isCustomDateRange = true },
                                    label = { Text(if (isBn) "কাস্টম" else "Custom", fontSize = 12.sp) },
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            if (isCustomDateRange) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            DatePickerDialog(
                                                context,
                                                { _, y, m, d ->
                                                    val c = Calendar.getInstance()
                                                    c.set(y, m, d, 0, 0, 0)
                                                    customStartDate = c.timeInMillis
                                                },
                                                calendar.get(Calendar.YEAR),
                                                calendar.get(Calendar.MONTH),
                                                calendar.get(Calendar.DAY_OF_MONTH)
                                            ).show()
                                        },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(sdfDisplay.format(Date(customStartDate)), fontSize = 11.sp)
                                    }

                                    Text("→", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)

                                    OutlinedButton(
                                        onClick = {
                                            DatePickerDialog(
                                                context,
                                                { _, y, m, d ->
                                                    val c = Calendar.getInstance()
                                                    c.set(y, m, d, 23, 59, 59)
                                                    customEndDate = c.timeInMillis
                                                },
                                                calendar.get(Calendar.YEAR),
                                                calendar.get(Calendar.MONTH),
                                                calendar.get(Calendar.DAY_OF_MONTH)
                                            ).show()
                                        },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(sdfDisplay.format(Date(customEndDate)), fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                },
                bottomBar = {
                    Surface(
                        tonalElevation = 4.dp,
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = if (isBn) "রিপোর্ট এক্সপোর্ট ও শেয়ার অপশন:" else "Export & Share Options:",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // PDF Print / Export
                                Button(
                                    onClick = {
                                        reportSummary?.let {
                                            StockOutReportHelper.printOrSharePdf(context, it, isBn)
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = StorePrimary),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                                ) {
                                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("PDF Print", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }

                                // CSV Export
                                OutlinedButton(
                                    onClick = {
                                        reportSummary?.let {
                                            StockOutReportHelper.exportAndShareCsv(context, it)
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                                ) {
                                    Icon(Icons.Default.TableChart, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("CSV Export", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }

                                // WhatsApp Share
                                Button(
                                    onClick = {
                                        reportSummary?.let {
                                            StockOutReportHelper.shareWhatsApp(context, it, isBn)
                                        }
                                    },
                                    modifier = Modifier.weight(1.1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                                ) {
                                    Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("WhatsApp", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }

                                // Share Chooser
                                IconButton(
                                    onClick = {
                                        reportSummary?.let {
                                            StockOutReportHelper.shareSummaryText(context, it, isBn)
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = "Share", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            ) { paddingValues ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)
                ) {
                    if (reportSummary == null) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    } else {
                        // 1. KPI Cards (Genuine Business Loss, Personal Use, Total Removed)
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                // Card 1: Genuine Business Loss (Deducted from Net Profit)
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = StoreRedAlert.copy(alpha = 0.08f)),
                                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(StoreRedAlert.copy(alpha = 0.4f)))
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(8.dp)
                                                        .background(StoreRedAlert, CircleShape)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = if (isBn) "প্রকৃত ব্যবসার ক্ষতি (Stock Loss)" else "GENUINE BUSINESS LOSS",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = StoreRedAlert
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = "₹%.2f".format(reportSummary.totalBusinessLossCost),
                                                style = MaterialTheme.typography.headlineMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = StoreRedAlert
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = if (isBn) "ক্ষতিগ্রস্ত + মেয়াদোত্তীর্ণ + অপচয় (P&L নিট লাভ থেকে বিয়োগ হয়েছে)" else "Damaged + Expired + Wastage (Subtracted from Net Profit)",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = StoreRedAlert.copy(alpha = 0.85f),
                                                fontSize = 11.sp
                                            )
                                        }

                                        Icon(
                                            Icons.Default.TrendingDown,
                                            contentDescription = null,
                                            tint = StoreRedAlert,
                                            modifier = Modifier.size(36.dp)
                                        )
                                    }
                                }

                                // 2 mini cards: Personal Use and Combined Outflow
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Personal Use Card (Explicitly NOT a loss)
                                    Card(
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)),
                                        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)))
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    Icons.Default.Person,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = if (isBn) "ব্যক্তিগত ব্যবহার" else "Personal Use",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = "₹%.2f".format(reportSummary.personalUseCost),
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Surface(
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    text = if (isBn) "ব্যবসার ক্ষতি নয়" else "NOT a Loss",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 10.sp,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }

                                    // Combined Total Card
                                    Card(
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    Icons.Default.Inventory,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = if (isBn) "সর্বমোট স্টক আউট" else "Total Removed",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = "₹%.2f".format(reportSummary.combinedTotalCost),
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = if (isBn) "${reportSummary.entries.size} টি এন্ট্রি" else "${reportSummary.entries.size} Outflows",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = 10.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 2. Reason Breakdown Section
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(
                                        text = if (isBn) "কারণ অনুযায়ী খরচের বিবরণ (Breakdown by Reason)" else "Cost Breakdown by Reason",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))

                                    val breakdownItems = listOf(
                                        BreakdownRowData(
                                            title = if (isBn) "ক্ষতিগ্রস্ত (Damaged)" else "Damaged",
                                            qty = reportSummary.damagedQty,
                                            cost = reportSummary.damagedCost,
                                            isLoss = true,
                                            isPersonal = false,
                                            icon = Icons.Default.BrokenImage,
                                            color = StoreRedAlert
                                        ),
                                        BreakdownRowData(
                                            title = if (isBn) "মেয়াদ উত্তীর্ণ (Expired)" else "Expired",
                                            qty = reportSummary.expiredQty,
                                            cost = reportSummary.expiredCost,
                                            isLoss = true,
                                            isPersonal = false,
                                            icon = Icons.Default.TimerOff,
                                            color = Color(0xFFD97706)
                                        ),
                                        BreakdownRowData(
                                            title = if (isBn) "অপচয় (Wastage)" else "Wastage",
                                            qty = reportSummary.wastageQty,
                                            cost = reportSummary.wastageCost,
                                            isLoss = true,
                                            isPersonal = false,
                                            icon = Icons.Default.DeleteOutline,
                                            color = Color(0xFFEA580C)
                                        ),
                                        BreakdownRowData(
                                            title = if (isBn) "ব্যক্তিগত ব্যবহার (Personal Use)" else "Personal Use",
                                            qty = reportSummary.personalUseQty,
                                            cost = reportSummary.personalUseCost,
                                            isLoss = false,
                                            isPersonal = true,
                                            icon = Icons.Default.Person,
                                            color = MaterialTheme.colorScheme.primary
                                        ),
                                        BreakdownRowData(
                                            title = if (isBn) "অন্যান্য (Other)" else "Other",
                                            qty = reportSummary.otherQty,
                                            cost = reportSummary.otherCost,
                                            isLoss = false,
                                            isPersonal = false,
                                            icon = Icons.Default.HelpOutline,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    )

                                    breakdownItems.forEach { item ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.weight(1.2f)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(28.dp)
                                                        .background(item.color.copy(alpha = 0.12f), CircleShape),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(item.icon, contentDescription = null, tint = item.color, modifier = Modifier.size(15.dp))
                                                }
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Column {
                                                    Text(
                                                        text = item.title,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.Medium,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                    Text(
                                                        text = if (item.isLoss) (if (isBn) "ব্যবসায়িক ক্ষতি" else "Business Loss")
                                                        else if (item.isPersonal) (if (isBn) "মালিকের ড্রয়িং (ক্ষতি নয়)" else "Owner Draw (Not a Loss)")
                                                        else (if (isBn) "সাধারণ স্টক আউট" else "General Stock-Out"),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = item.color,
                                                        fontSize = 10.sp
                                                    )
                                                }
                                            }

                                            Text(
                                                text = "%.1f qty".format(item.qty),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.weight(0.6f),
                                                textAlign = TextAlign.Center
                                            )

                                            Text(
                                                text = "₹%.2f".format(item.cost),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = if (item.cost > 0 && item.isLoss) StoreRedAlert else MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.weight(0.8f),
                                                textAlign = TextAlign.End
                                            )
                                        }
                                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                    }
                                }
                            }
                        }

                        // 3. Itemized Stock-Out List
                        item {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = if (isBn) "আইটেম অনুযায়ী তালিকা (${reportSummary.entries.size})" else "Itemized Outflow Records (${reportSummary.entries.size})",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // Filter Search & Chips
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    placeholder = { Text(if (isBn) "আইটেমের নাম বা কারণ খুঁজুন..." else "Search product name or reason...", fontSize = 12.sp) },
                                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    trailingIcon = {
                                        if (searchQuery.isNotEmpty()) {
                                            IconButton(onClick = { searchQuery = "" }) {
                                                Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    shape = RoundedCornerShape(10.dp)
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                // Reason Filter Chips
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    val filters = listOf(
                                        "ALL" to if (isBn) "সব" else "All",
                                        "LOSS" to if (isBn) "ক্ষতি" else "Loss",
                                        "PERSONAL" to if (isBn) "ব্যক্তিগত" else "Personal",
                                        "OTHER" to if (isBn) "অন্যান্য" else "Other"
                                    )
                                    filters.forEach { (key, label) ->
                                        FilterChip(
                                            selected = selectedReasonFilter == key,
                                            onClick = { selectedReasonFilter = key },
                                            label = { Text(label, fontSize = 11.sp) }
                                        )
                                    }
                                }
                            }
                        }

                        val filteredEntries = reportSummary.entries.filter { entry ->
                            val matchesSearch = searchQuery.isBlank() ||
                                    entry.productNameEn.contains(searchQuery, ignoreCase = true) ||
                                    (entry.productNameBn?.contains(searchQuery, ignoreCase = true) == true) ||
                                    entry.reason.contains(searchQuery, ignoreCase = true) ||
                                    (entry.note?.contains(searchQuery, ignoreCase = true) == true)

                            val matchesFilter = when (selectedReasonFilter) {
                                "LOSS" -> entry.isBusinessLoss()
                                "PERSONAL" -> entry.isPersonalUse()
                                "OTHER" -> !entry.isBusinessLoss() && !entry.isPersonalUse()
                                else -> true
                            }

                            matchesSearch && matchesFilter
                        }

                        if (filteredEntries.isEmpty()) {
                            item {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(24.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (isBn) "এই সময়কালে কোন স্টক আউট এন্ট্রি পাওয়া যায়নি।" else "No stock-out entries found for this time period.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        } else {
                            items(filteredEntries, key = { it.id }) { entry ->
                                StockOutEntryRow(
                                    entry = entry,
                                    isBn = isBn,
                                    itemSdf = itemSdf,
                                    onDelete = { viewModel.deleteStockOut(entry) }
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
private fun StockOutEntryRow(
    entry: StockOutEntry,
    isBn: Boolean,
    itemSdf: SimpleDateFormat,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (entry.isBusinessLoss()) StoreRedAlert.copy(alpha = 0.04f)
            else if (entry.isPersonalUse()) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
            else MaterialTheme.colorScheme.surface
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                if (entry.isBusinessLoss()) StoreRedAlert.copy(alpha = 0.25f)
                else if (entry.isPersonalUse()) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Product Name & Date
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.getDisplayName(isBn),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = itemSdf.format(Date(entry.timestamp)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }

                // Cost & Total Value
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "₹%.2f".format(entry.totalCostValue),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (entry.isBusinessLoss()) StoreRedAlert else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "%.1f %s @ ₹%.2f".format(entry.quantity, entry.unitType, entry.costPrice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Reason Badge & Note & Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Reason Badge
                    val badgeBg = when {
                        entry.isBusinessLoss() -> StoreRedAlert.copy(alpha = 0.15f)
                        entry.isPersonalUse() -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                    val badgeColor = when {
                        entry.isBusinessLoss() -> StoreRedAlert
                        entry.isPersonalUse() -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    val badgeText = when {
                        entry.isBusinessLoss() -> "🚨 ${entry.getFormattedReason(isBn)}"
                        entry.isPersonalUse() -> "👤 ${entry.getFormattedReason(isBn)} (Not a Loss)"
                        else -> "ℹ️ ${entry.getFormattedReason(isBn)}"
                    }

                    Surface(
                        color = badgeBg,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = badgeColor,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 10.sp
                        )
                    }

                    if (!entry.note.isNullOrBlank()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Note: ${entry.note}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp,
                            maxLines = 1
                        )
                    }
                }

                IconButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(if (isBn) "এন্ট্রি মুছে ফেলবেন?" else "Delete Stock-Out Entry?") },
            text = {
                Text(
                    if (isBn) "আপনি কি নিশ্চিত যে এই স্টক আউট রেকর্ডটি মুছে ফেলতে চান? এটি রিপোর্ট থেকে অপসারিত হবে।"
                    else "Are you sure you want to delete this stock-out entry? It will be removed from stock loss calculations."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = StoreRedAlert)
                ) {
                    Text(if (isBn) "মুছুন" else "Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(if (isBn) "বাতিল" else "Cancel")
                }
            }
        )
    }
}

private data class BreakdownRowData(
    val title: String,
    val qty: Double,
    val cost: Double,
    val isLoss: Boolean,
    val isPersonal: Boolean,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val color: Color
)
