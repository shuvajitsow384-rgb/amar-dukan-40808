package com.example.ui.screens.reports

import android.content.Context
import android.content.Intent
import android.widget.Toast
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.data.local.entities.Expense
import com.example.ui.screens.suppliers.cleanNotes
import com.example.ui.screens.suppliers.extractPhotoUri
import com.example.ui.theme.*
import com.example.utils.LanguageManager
import com.example.utils.StoreInfoManager
import com.example.viewmodel.ReportPeriod
import com.example.viewmodel.StoreViewModel
import java.text.SimpleDateFormat
import java.util.*

private enum class ExpenseSortOption {
    NEWEST_DATE,
    OLDEST_DATE,
    HIGHEST_AMOUNT,
    LOWEST_AMOUNT
}

data class ExpenseCategoryItem(
    val categoryName: String,
    val categoryNameBn: String,
    val totalAmount: Double,
    val percentage: Double,
    val count: Int,
    val colorHex: Long
)

private fun mapExpenseCategoryColor(category: String): Long {
    val cat = category.lowercase().trim()
    return when {
        cat.contains("food") || cat.contains("tea") || cat.contains("চা") || cat.contains("খাবার") -> 0xFFE65100 // Deep Orange
        cat.contains("rent") || cat.contains("ভাড়া") -> 0xFF6A1B9A // Purple
        cat.contains("electric") || cat.contains("বিদ্যুৎ") || cat.contains("বিল") -> 0xFFF57F17 // Amber
        cat.contains("wage") || cat.contains("salary") || cat.contains("বেতন") -> 0xFF2E7D32 // Green
        cat.contains("transport") || cat.contains("যাতায়াত") || cat.contains("গাড়ি") -> 0xFF0277BD // Light Blue
        cat.contains("repair") || cat.contains("maintenance") || cat.contains("মেরামত") -> 0xFF4E342E // Brown
        cat.contains("daily") || cat.contains("দৈনন্দিন") -> 0xFFD81B60 // Pink
        else -> 0xFF546E7A // Blue Grey
    }
}

private fun mapExpenseCategoryNameBn(category: String): String {
    val cat = category.lowercase().trim()
    return when {
        cat.contains("food") || cat.contains("tea") -> "চা ও নাশতা (Food & Tea)"
        cat.contains("rent") -> "দোকান ভাড়া (Shop Rent)"
        cat.contains("electric") -> "বিদ্যুৎ বিল (Electricity)"
        cat.contains("wage") || cat.contains("salary") -> "কর্মচারী বেতন ও মজুরি"
        cat.contains("transport") -> "যাতায়াত ও পরিবহন খরচ"
        cat.contains("daily") -> "দৈনন্দিন খরচ (Daily Expense)"
        cat.contains("repair") || cat.contains("maintenance") -> "মেরামত ও রক্ষণাবেক্ষণ"
        else -> category
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullExpenseReportDialog(
    viewModel: StoreViewModel,
    initialPeriod: ReportPeriod = ReportPeriod.THIS_MONTH,
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val isBn = LanguageManager.isBengali
    var selectedPeriod by remember { mutableStateOf(initialPeriod) }

    // Filter & Search states
    var selectedCategoryFilter by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var sortOption by remember { mutableStateOf(ExpenseSortOption.NEWEST_DATE) }
    var showQuickAddExpenseDialog by remember { mutableStateOf(false) }
    var viewEnlargedPhotoUri by remember { mutableStateOf<String?>(null) }
    var expenseToDelete by remember { mutableStateOf<Expense?>(null) }

    // All expenses from database
    val allExpenses by viewModel.allExpenses.collectAsState()
    val periodBounds = remember(selectedPeriod) { viewModel.getPeriodTimeBounds(selectedPeriod) }

    // Valid operating expenses for the selected period
    val periodExpenses = remember(allExpenses, periodBounds) {
        allExpenses.filter {
            it.date in periodBounds.first..periodBounds.second &&
            !it.category.contains("Stock Loss", ignoreCase = true) &&
            !it.category.contains("Wastage", ignoreCase = true)
        }
    }

    val totalExpenseAmount = remember(periodExpenses) {
        periodExpenses.sumOf { it.amount }
    }

    // Category breakdown
    val categoryBreakdown = remember(periodExpenses, totalExpenseAmount) {
        val grouped = periodExpenses.groupBy { it.category }
        grouped.map { (cat, list) ->
            val sum = list.sumOf { it.amount }
            val pct = if (totalExpenseAmount > 0) (sum / totalExpenseAmount) * 100.0 else 0.0
            ExpenseCategoryItem(
                categoryName = cat,
                categoryNameBn = mapExpenseCategoryNameBn(cat),
                totalAmount = sum,
                percentage = pct,
                count = list.size,
                colorHex = mapExpenseCategoryColor(cat)
            )
        }.sortedByDescending { it.totalAmount }
    }

    val topCategory = categoryBreakdown.firstOrNull()
    val highestSingleExpense = remember(periodExpenses) {
        periodExpenses.maxByOrNull { it.amount }
    }

    // Filtered transaction list
    val filteredTransactions = remember(periodExpenses, selectedCategoryFilter, searchQuery, sortOption) {
        var list = periodExpenses

        if (selectedCategoryFilter != null) {
            list = list.filter { it.category.equals(selectedCategoryFilter, ignoreCase = true) }
        }

        if (searchQuery.isNotBlank()) {
            val q = searchQuery.trim().lowercase()
            list = list.filter {
                it.category.lowercase().contains(q) ||
                (it.note != null && it.note.lowercase().contains(q)) ||
                "%.2f".format(Locale.US, it.amount).contains(q)
            }
        }

        when (sortOption) {
            ExpenseSortOption.NEWEST_DATE -> list.sortedByDescending { it.date }
            ExpenseSortOption.OLDEST_DATE -> list.sortedBy { it.date }
            ExpenseSortOption.HIGHEST_AMOUNT -> list.sortedByDescending { it.amount }
            ExpenseSortOption.LOWEST_AMOUNT -> list.sortedBy { it.amount }
        }
    }

    val periodLabel = when (selectedPeriod) {
        ReportPeriod.TODAY -> if (isBn) "আজ" else "Today"
        ReportPeriod.THIS_WEEK -> if (isBn) "এই সপ্তাহ" else "This Week"
        ReportPeriod.THIS_MONTH -> if (isBn) "এই মাস" else "This Month"
        ReportPeriod.ALL_TIME -> if (isBn) "সর্বমোট" else "All Time"
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 1. Top App Bar
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(StoreRedPrimary.copy(alpha = 0.12f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.AccountBalanceWallet,
                                        contentDescription = null,
                                        tint = StoreRedPrimary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = if (isBn) "দোকানের খরচের রিপোর্ট" else "Shop Expense Report",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = if (isBn) "পরিচালন ব্যয়ের বিস্তারিত হিসাব ও খাত" else "Operating expenses & category breakdown",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        val storeName = StoreInfoManager.storeName.ifBlank { "My Store" }
                                        val sb = java.lang.StringBuilder()
                                        sb.append("📋 *${if (isBn) "দোকানের খরচের রিপোর্ট" else "Shop Expense Report"} - $storeName*\n")
                                        sb.append("🗓️ *${if (isBn) "সময়কাল" else "Period"}:* $periodLabel\n")
                                        sb.append("💰 *${if (isBn) "মোট খরচ" else "Total Expenses"}:* ₹${"%.2f".format(Locale.US, totalExpenseAmount)}\n")
                                        sb.append("📝 *${if (isBn) "মোট এন্ট্রি" else "Total Entries"}:* ${periodExpenses.size}\n\n")

                                        if (categoryBreakdown.isNotEmpty()) {
                                            sb.append("*${if (isBn) "খাত অনুযায়ী খরচ" else "Category Breakdown"}:*\n")
                                            categoryBreakdown.forEach { cat ->
                                                val name = if (isBn) cat.categoryNameBn else cat.categoryName
                                                sb.append("• $name: ₹${"%.2f".format(Locale.US, cat.totalAmount)} (${"%.1f".format(Locale.US, cat.percentage)}%)\n")
                                            }
                                        }

                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, sb.toString())
                                        }
                                        context.startActivity(Intent.createChooser(shareIntent, "Share Expense Report"))
                                    }
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = "Share", tint = StorePrimary)
                                }

                                IconButton(onClick = onDismissRequest) {
                                    Icon(Icons.Default.Close, contentDescription = "Close")
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Period Selector Chips
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
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
                                        selectedPeriod = period
                                        selectedCategoryFilter = null
                                        viewModel.loadPnlReport(period)
                                    },
                                    label = { Text(label, fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                // Scrollable Content Body
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    // 2. Master Summary Card
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = StoreRedPrimary),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = if (isBn) "মোট দোকান খরচ ($periodLabel)" else "TOTAL SHOP EXPENSES ($periodLabel)",
                                    color = Color.White.copy(alpha = 0.85f),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "₹%.2f".format(Locale.US, totalExpenseAmount),
                                    style = MaterialTheme.typography.headlineLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                // 3 Metric Chips Inside Hero Card
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Surface(
                                        color = Color.White.copy(alpha = 0.18f),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                                            Text(
                                                text = if (isBn) "মোট এন্ট্রি" else "Entries",
                                                color = Color.White.copy(alpha = 0.85f),
                                                fontSize = 10.sp
                                            )
                                            Text(
                                                text = "${periodExpenses.size} ${if (isBn) "টি" else ""}",
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp
                                            )
                                        }
                                    }

                                    Surface(
                                        color = Color.White.copy(alpha = 0.18f),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1.2f)
                                    ) {
                                        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                                            Text(
                                                text = if (isBn) "সর্বোচ্চ খাত" else "Top Category",
                                                color = Color.White.copy(alpha = 0.85f),
                                                fontSize = 10.sp
                                            )
                                            Text(
                                                text = topCategory?.let { if (isBn) it.categoryNameBn.take(12) else it.categoryName.take(12) } ?: "-",
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    Surface(
                                        color = Color.White.copy(alpha = 0.18f),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1.1f)
                                    ) {
                                        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                                            Text(
                                                text = if (isBn) "সর্বোচ্চ একক খরচ" else "Highest Single",
                                                color = Color.White.copy(alpha = 0.85f),
                                                fontSize = 10.sp
                                            )
                                            Text(
                                                text = highestSingleExpense?.let { "₹%.0f".format(it.amount) } ?: "-",
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 3. Category Breakdown
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (isBn) "খাত অনুযায়ী খরচের বণ্টন" else "Category Breakdown",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )

                                    if (selectedCategoryFilter != null) {
                                        TextButton(
                                            onClick = { selectedCategoryFilter = null },
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(if (isBn) "সব খাত ✕" else "Show All ✕", fontSize = 11.sp, color = StoreRedPrimary)
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // Segmented distribution bar
                                if (categoryBreakdown.isNotEmpty() && totalExpenseAmount > 0) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(12.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                    ) {
                                        categoryBreakdown.forEach { cat ->
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
                                            .height(10.dp)
                                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(5.dp))
                                    )
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                if (categoryBreakdown.isEmpty()) {
                                    Text(
                                        text = if (isBn) "এই সময়কালে কোনো খরচ রেকর্ড করা হয়নি।" else "No expense records found for this period.",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        categoryBreakdown.forEach { cat ->
                                            val isSelected = selectedCategoryFilter.equals(cat.categoryName, ignoreCase = true)
                                            Surface(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        selectedCategoryFilter = if (isSelected) null else cat.categoryName
                                                    },
                                                shape = RoundedCornerShape(8.dp),
                                                color = if (isSelected) Color(cat.colorHex).copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                                border = BorderStroke(
                                                    width = if (isSelected) 1.5.dp else 1.dp,
                                                    color = if (isSelected) Color(cat.colorHex) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                                )
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(10.dp)
                                                                .background(Color(cat.colorHex), CircleShape)
                                                        )
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Column {
                                                            Text(
                                                                text = if (isBn) cat.categoryNameBn else cat.categoryName,
                                                                fontWeight = FontWeight.SemiBold,
                                                                fontSize = 13.sp
                                                            )
                                                            Text(
                                                                text = "${cat.count} ${if (isBn) "টি এন্ট্রি" else "entries"}",
                                                                fontSize = 10.sp,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        }
                                                    }

                                                    Column(horizontalAlignment = Alignment.End) {
                                                        Text(
                                                            text = "₹%.2f".format(Locale.US, cat.totalAmount),
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 13.sp
                                                        )
                                                        Text(
                                                            text = "%.1f%%".format(Locale.US, cat.percentage),
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 11.sp,
                                                            color = Color(cat.colorHex)
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

                    // 4. Itemized Expense Entries Header & Search
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isBn) "খরচের এন্ট্রিসমূহ (${filteredTransactions.size} টি)" else "Expense Entries (${filteredTransactions.size})",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )

                                if (selectedCategoryFilter != null) {
                                    TextButton(
                                        onClick = { selectedCategoryFilter = null },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(if (isBn) "ফিল্টার মুছুন ✕" else "Clear Filter ✕", fontSize = 11.sp, color = StoreRedPrimary)
                                    }
                                }
                            }

                            // Search bar
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text(if (isBn) "খরচের নোট বা খাত খুঁজুন..." else "Search expense note or category...", fontSize = 12.sp) },
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

                            // Sort Chips
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                items(ExpenseSortOption.values(), key = { it.name }) { opt ->
                                    val isSelected = sortOption == opt
                                    val label = when (opt) {
                                        ExpenseSortOption.NEWEST_DATE -> if (isBn) "নতুন আগে" else "Newest First"
                                        ExpenseSortOption.OLDEST_DATE -> if (isBn) "পুরোনো আগে" else "Oldest First"
                                        ExpenseSortOption.HIGHEST_AMOUNT -> if (isBn) "সর্বোচ্চ খরচ" else "Highest Amount"
                                        ExpenseSortOption.LOWEST_AMOUNT -> if (isBn) "সর্বনিম্ন খরচ" else "Lowest Amount"
                                    }
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { sortOption = opt },
                                        label = { Text(label, fontSize = 10.sp) }
                                    )
                                }
                            }
                        }
                    }

                    // 5. Individual Expense Items List
                    if (filteredTransactions.isEmpty()) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        Icons.Default.ReceiptLong,
                                        contentDescription = null,
                                        modifier = Modifier.size(44.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = if (isBn) "কোনো খরচের রেকর্ড পাওয়া যায়নি" else "No expense records found",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (isBn) "এই সময়কালের জন্য কোনো খরচের এন্ট্রি নেই।" else "No expense records exist for $periodLabel.",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    if (selectedPeriod == ReportPeriod.TODAY && allExpenses.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(
                                                onClick = {
                                                    selectedPeriod = ReportPeriod.THIS_MONTH
                                                    viewModel.loadPnlReport(ReportPeriod.THIS_MONTH)
                                                }
                                            ) {
                                                Text(if (isBn) "এই মাস দেখুন" else "View This Month", fontSize = 11.sp)
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    selectedPeriod = ReportPeriod.ALL_TIME
                                                    viewModel.loadPnlReport(ReportPeriod.ALL_TIME)
                                                }
                                            ) {
                                                Text(if (isBn) "সর্বমোট দেখুন" else "View All Time", fontSize = 11.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        items(filteredTransactions, key = { it.id }) { expense ->
                            val colorHex = mapExpenseCategoryColor(expense.category)
                            val catBn = mapExpenseCategoryNameBn(expense.category)
                            val dateStr = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(expense.date))
                            val cleanNote = cleanNotes(expense.note)
                            val photoUri = extractPhotoUri(expense.note)

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
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
                                                .background(Color(colorHex).copy(alpha = 0.15f), CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                Icons.Default.Receipt,
                                                contentDescription = null,
                                                tint = Color(colorHex),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(10.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = if (isBn) catBn else expense.category,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp
                                                )
                                                if (expense.isRecurring) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Surface(
                                                        color = StorePrimary.copy(alpha = 0.12f),
                                                        shape = RoundedCornerShape(4.dp)
                                                    ) {
                                                        Text(
                                                            text = if (isBn) "নিয়মিত" else "Recurring",
                                                            fontSize = 9.sp,
                                                            color = StorePrimary,
                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                        )
                                                    }
                                                }
                                            }

                                            if (!cleanNote.isNullOrBlank()) {
                                                Text(
                                                    text = cleanNote,
                                                    fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }

                                            Text(
                                                text = dateStr,
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = "-₹%.2f".format(Locale.US, expense.amount),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = StoreRedPrimary
                                        )

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (!photoUri.isNullOrBlank()) {
                                                Text(
                                                    text = if (isBn) "বিল 📷" else "Photo 📷",
                                                    fontSize = 10.sp,
                                                    color = StorePrimary,
                                                    fontWeight = FontWeight.SemiBold,
                                                    modifier = Modifier.clickable { viewEnlargedPhotoUri = photoUri }
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                            }

                                            IconButton(
                                                onClick = { expenseToDelete = expense },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    contentDescription = "Delete",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Bottom Action Footer
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showQuickAddExpenseDialog = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isBn) "+ নতুন খরচ যোগ" else "+ Log Expense", fontSize = 12.sp)
                        }

                        Button(
                            onClick = onDismissRequest,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = StorePrimary)
                        ) {
                            Text(if (isBn) "বন্ধ করুন" else "Close", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    // Photo Enlarged Viewer Dialog
    if (viewEnlargedPhotoUri != null) {
        AlertDialog(
            onDismissRequest = { viewEnlargedPhotoUri = null },
            confirmButton = {
                TextButton(onClick = { viewEnlargedPhotoUri = null }) {
                    Text(if (isBn) "বন্ধ করুন" else "Close")
                }
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = viewEnlargedPhotoUri,
                        contentDescription = "Bill Photo",
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        )
    }

    // Delete Confirmation Dialog
    if (expenseToDelete != null) {
        val exp = expenseToDelete!!
        AlertDialog(
            onDismissRequest = { expenseToDelete = null },
            title = { Text(if (isBn) "খরচ মুছে ফেলতে চান?" else "Delete Expense?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    if (isBn) 
                        "আপনি কি নিশ্চিত যে আপনি এই খরচের রেকর্ডটি (₹${"%.2f".format(Locale.US, exp.amount)} - ${exp.category}) মুছে ফেলতে চান?"
                        else "Are you sure you want to delete this expense record of ₹${"%.2f".format(Locale.US, exp.amount)} (${exp.category})?"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteExpense(exp)
                        viewModel.loadPnlReport(selectedPeriod)
                        expenseToDelete = null
                        Toast.makeText(context, if (isBn) "খরচ মুছে ফেলা হয়েছে" else "Expense deleted", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StoreRedPrimary)
                ) {
                    Text(if (isBn) "মুছুন" else "Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { expenseToDelete = null }) {
                    Text(if (isBn) "বাতিল" else "Cancel")
                }
            }
        )
    }

    // Quick Add Expense Dialog
    if (showQuickAddExpenseDialog) {
        var quickCat by remember { mutableStateOf("Food & Tea") }
        var quickAmtText by remember { mutableStateOf("") }
        var quickNote by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showQuickAddExpenseDialog = false },
            title = { Text(if (isBn) "নতুন খরচ যুক্ত করুন" else "Add New Expense", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (isBn) "ক্যাটাগরি নির্বাচন করুন:" else "Select Category:", fontSize = 12.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(listOf("Food & Tea", "Daily Expense", "Rent", "Electricity", "Wages", "Transport", "Misc"), key = { it }) { c ->
                            FilterChip(
                                selected = quickCat == c,
                                onClick = { quickCat = c },
                                label = { Text(mapExpenseCategoryNameBn(c), fontSize = 11.sp) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = quickAmtText,
                        onValueChange = { quickAmtText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                        label = { Text(if (isBn) "টাকার পরিমাণ (₹)" else "Amount (₹)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = quickNote,
                        onValueChange = { quickNote = it },
                        label = { Text(if (isBn) "খরচের বিবরণ / নোট" else "Note / Description") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val amt = quickAmtText.toDoubleOrNull() ?: 0.0
                        if (amt > 0) {
                            val newExp = Expense(
                                id = "exp_" + UUID.randomUUID().toString().take(8),
                                date = System.currentTimeMillis(),
                                category = quickCat,
                                amount = amt,
                                note = quickNote.ifBlank { null }
                            )
                            viewModel.saveExpense(newExp)
                            viewModel.loadPnlReport(selectedPeriod)
                            showQuickAddExpenseDialog = false
                            Toast.makeText(context, if (isBn) "খরচ সংরক্ষিত হয়েছে" else "Expense recorded", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StoreRedPrimary)
                ) {
                    Text(if (isBn) "সংরক্ষণ" else "Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showQuickAddExpenseDialog = false }) {
                    Text(if (isBn) "বাতিল" else "Cancel")
                }
            }
        )
    }
}
