package com.example.ui.screens.expenses

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.example.ui.components.CollapsingHeaderLayout
import com.example.ui.components.rememberCollapsingHeaderState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.data.local.entities.Expense
import com.example.ui.screens.suppliers.cleanNotes
import com.example.ui.screens.suppliers.extractPhotoUri
import com.example.ui.screens.suppliers.saveBitmapToCache
import com.example.ui.theme.*
import com.example.utils.LanguageManager
import com.example.utils.StaffManager
import com.example.viewmodel.StoreViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseScreen(viewModel: StoreViewModel) {
    val isBn = LanguageManager.isBengali
    val context = LocalContext.current

    if (!com.example.utils.StaffManager.canManageExpenses()) {
        com.example.ui.components.StaffAccessGate(
            screenTitle = "Store Expenses",
            screenDescription = "Store expense logs and financial outflows are restricted to authorized managers."
        )
        return
    }

    val expenses by viewModel.allExpenses.collectAsState()

    var selectedCategoryFilter by remember { mutableStateOf("ALL") }

    // BackHandler: Reset category filter before navigating back to POS
    BackHandler(enabled = selectedCategoryFilter != "ALL") {
        selectedCategoryFilter = "ALL"
    }
    var showAddExpenseDialog by remember { mutableStateOf(false) }
    var presetInitialCategory by remember { mutableStateOf("Food & Tea") }
    var presetInitialNote by remember { mutableStateOf("") }
    var viewPhotoUri by remember { mutableStateOf<String?>(null) }
    var expenseToDelete by remember { mutableStateOf<Expense?>(null) }

    // Handle App Shortcut to open Add Expense
    LaunchedEffect(viewModel.triggerShowAddExpense) {
        if (viewModel.triggerShowAddExpense) {
            if (com.example.utils.StaffManager.canManageExpenses()) {
                showAddExpenseDialog = true
            }
            viewModel.triggerShowAddExpense = false
        }
    }

    val categories = listOf("ALL", "Food & Tea", "Daily Expense", "Rent", "Electricity", "Wages", "Transport", "Misc")

    val filteredExpenses = remember(expenses, selectedCategoryFilter) {
        if (selectedCategoryFilter == "ALL") expenses
        else expenses.filter { it.category == selectedCategoryFilter }
    }

    val totalAmount = remember(expenses) { expenses.sumOf { it.amount } }

    val todayStart = remember {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.timeInMillis
    }

    val todayExpenses = remember(expenses) {
        expenses.filter { it.date >= todayStart }
    }

    val todayTotal = remember(todayExpenses) { todayExpenses.sumOf { it.amount } }

    val todayFoodTotal = remember(todayExpenses) {
        todayExpenses.filter {
            it.category == "Food & Tea" ||
                    it.note?.contains("food", ignoreCase = true) == true ||
                    it.note?.contains("খাবার", ignoreCase = true) == true ||
                    it.note?.contains("টিফিন", ignoreCase = true) == true ||
                    it.note?.contains("চা", ignoreCase = true) == true
        }.sumOf { it.amount }
    }

    fun openAddDialog(category: String = "Food & Tea", defaultNote: String = "") {
        presetInitialCategory = category
        presetInitialNote = defaultNote
        showAddExpenseDialog = true
    }

    val expenseCollapsingState = rememberCollapsingHeaderState()
    LaunchedEffect(selectedCategoryFilter) {
        expenseCollapsingState.expand()
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { openAddDialog("Food & Tea", "") },
                containerColor = StoreRedPrimary,
                contentColor = Color.White
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Expense")
            }
        }
    ) { innerPadding ->
        CollapsingHeaderLayout(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp),
            state = expenseCollapsingState,
            header = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 4.dp)
                ) {
                    // Total Expenses Banner Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                elevation = CardDefaults.cardElevation(3.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = LanguageManager.getString("Total Shop Expenses", "দোকানের মোট খরচ"),
                                style = MaterialTheme.typography.labelMedium,
                                color = TextMuted
                            )
                            Text(
                                text = "₹%.2f".format(totalAmount),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = StoreRedPrimary
                            )
                        }

                        Surface(
                            color = StoreRedPrimary.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Box(modifier = Modifier.padding(12.dp)) {
                                Icon(Icons.Default.ReceiptLong, contentDescription = null, tint = StoreRedPrimary)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Divider(color = TextMuted.copy(alpha = 0.2f))
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = LanguageManager.getString("Today's Total Expense", "আজকের মোট খরচ"),
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                            Text(
                                text = "₹%.2f".format(todayTotal),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = TextDark
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = LanguageManager.getString("Today's Food & Tea 🍲", "আজকের খাবার ও চা 🍲"),
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                            Text(
                                text = "₹%.2f".format(todayFoodTotal),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = StoreSaffronAccent
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Quick Daily Expense Log Shortcuts
            Text(
                text = LanguageManager.getString("Quick Daily Expenses (Tap to Log):", "দ্রুত দৈনিক খরচ যোগ করুন (ট্যাপ করুন):"),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = TextMuted
            )
            Spacer(modifier = Modifier.height(6.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Button(
                        onClick = { openAddDialog("Food & Tea", if (isBn) "কর্মচারীর খাবার / লাঞ্চ" else "Staff Food / Lunch") },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPinkContainer, contentColor = AccentPinkText),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(if (isBn) "🍲 খাবার / লাঞ্চ" else "🍲 Food / Lunch", fontSize = 12.sp)
                    }
                }
                item {
                    Button(
                        onClick = { openAddDialog("Food & Tea", if (isBn) "চা ও টিফিন / বিস্কুট" else "Tea & Tiffin / Snacks") },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPinkContainer, contentColor = AccentPinkText),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(if (isBn) "☕ চা-নাস্তা" else "☕ Tea & Snacks", fontSize = 12.sp)
                    }
                }
                item {
                    Button(
                        onClick = { openAddDialog("Daily Expense", if (isBn) "দোকানের দৈনিক খরচ" else "Daily Shop Misc") },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlueContainer, contentColor = AccentBlueText),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(if (isBn) "📝 দৈনিক খরচ" else "📝 Daily Misc", fontSize = 12.sp)
                    }
                }
                item {
                    Button(
                        onClick = { openAddDialog("Daily Expense", if (isBn) "দোকান পরিষ্কার / জল" else "Water / Cleaning") },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlueContainer, contentColor = AccentBlueText),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(if (isBn) "🧹 ঝাড়ু / জল" else "🧹 Water / Cleaning", fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Category Filter Chips
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(categories, key = { it }) { cat ->
                    val isSelected = selectedCategoryFilter == cat
                    val displayCat = when (cat) {
                        "ALL" -> if (isBn) "সব খরচ" else "All"
                        "Food & Tea" -> if (isBn) "খাবার ও চা 🍲" else "Food & Tea 🍲"
                        "Daily Expense" -> if (isBn) "দৈনিক খরচ 📝" else "Daily Expense 📝"
                        "Rent" -> if (isBn) "ভাড়া" else "Rent"
                        "Electricity" -> if (isBn) "বিদ্যুৎ" else "Electricity"
                        "Wages" -> if (isBn) "বেতন" else "Wages"
                        "Transport" -> if (isBn) "গাড়ি ভাড়া" else "Transport"
                        else -> if (isBn) "অন্যান্য" else "Misc"
                    }
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedCategoryFilter = cat },
                        label = { Text(displayCat) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
                }
            },
            content = {
                // Expenses List
                if (filteredExpenses.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(LanguageManager.getString("No expenses recorded", "কোন খরচের হিসাব পাওয়া যায়নি"), color = TextMuted)
                    }
                } else {
                    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()) }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 88.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                    items(filteredExpenses, key = { it.id }, contentType = { "EXPENSE_ITEM" }) { expense ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = CardBackground),
                            elevation = CardDefaults.cardElevation(2.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(12.dp)
                                    .fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            color = when (expense.category) {
                                                "Food & Tea" -> StoreSaffronAccent.copy(alpha = 0.15f)
                                                "Daily Expense" -> StorePrimary.copy(alpha = 0.15f)
                                                else -> StoreRedPrimary.copy(alpha = 0.1f)
                                            },
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = when (expense.category) {
                                                    "Food & Tea" -> if (isBn) "খাবার ও চা 🍲" else "Food & Tea 🍲"
                                                    "Daily Expense" -> if (isBn) "দৈনিক খরচ 📝" else "Daily Expense 📝"
                                                    else -> expense.category
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = when (expense.category) {
                                                    "Food & Tea" -> StoreSaffronAccent
                                                    "Daily Expense" -> StorePrimary
                                                    else -> StoreRedPrimary
                                                },
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }

                                        if (expense.isRecurring) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                color = StoreGold.copy(alpha = 0.2f),
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    text = if (isBn) "মাসিক" else "Monthly",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = StoreSaffronAccent,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))
                                    val photoUri = extractPhotoUri(expense.note)
                                    val cleanNoteStr = cleanNotes(expense.note)

                                    if (cleanNoteStr.isNotBlank()) {
                                        Text(cleanNoteStr, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                    }

                                    Text(dateFormat.format(Date(expense.date)), style = MaterialTheme.typography.bodySmall, color = TextMuted)

                                    if (photoUri != null) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Surface(
                                            color = StorePrimary.copy(alpha = 0.12f),
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.clickable { viewPhotoUri = photoUri }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Default.Photo, contentDescription = null, tint = StorePrimary, modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    if (isBn) "রসিদের ছবি দেখুন 📷" else "View Receipt Photo 📷",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = StorePrimary,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "₹%.2f".format(expense.amount),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = StoreRedPrimary
                                    )

                                    Spacer(modifier = Modifier.width(6.dp))

                                    IconButton(
                                        onClick = { expenseToDelete = expense },
                                        modifier = Modifier.testTag("delete_expense_${expense.id}")
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = StoreRedPrimary.copy(alpha = 0.08f),
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Default.DeleteOutline,
                                                    contentDescription = if (isBn) "খরচ মুছুন" else "Delete Expense",
                                                    tint = StoreRedPrimary,
                                                    modifier = Modifier.size(18.dp)
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
    )
}

    // Add Expense Modal
    if (showAddExpenseDialog) {
        var category by remember { mutableStateOf(presetInitialCategory) }
        var amountText by remember { mutableStateOf("") }
        var note by remember { mutableStateOf(presetInitialNote) }
        var isRecurring by remember { mutableStateOf(false) }
        var attachedImageUri by remember { mutableStateOf<String?>(null) }
        var showEnlargedPhoto by remember { mutableStateOf<String?>(null) }

        val galleryLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            uri?.let {
                val syncable = com.example.utils.ImageSyncHelper.compressUriToDataUrl(context, it)
                if (!syncable.isNullOrBlank()) {
                    attachedImageUri = syncable
                }
            }
        }

        val launchReceiptCamera = com.example.utils.rememberHighResCameraCapture { dataUrl ->
            attachedImageUri = dataUrl
        }

        AlertDialog(
            onDismissRequest = { showAddExpenseDialog = false },
            title = { Text(LanguageManager.getString("Log New Expense", "নতুন খরচ যোগ করুন"), fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(LanguageManager.getString("Category:", "শ্রেণী:"), style = MaterialTheme.typography.labelSmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(listOf("Food & Tea", "Daily Expense", "Rent", "Electricity", "Wages", "Transport", "Misc"), key = { it }) { cat ->
                            val labelText = when (cat) {
                                "Food & Tea" -> if (isBn) "খাবার ও চা 🍲" else "Food & Tea 🍲"
                                "Daily Expense" -> if (isBn) "দৈনিক খরচ 📝" else "Daily Expense 📝"
                                "Rent" -> if (isBn) "ভাড়া" else "Rent"
                                "Electricity" -> if (isBn) "বিদ্যুৎ" else "Electricity"
                                "Wages" -> if (isBn) "বেতন" else "Wages"
                                "Transport" -> if (isBn) "পরিবহন" else "Transport"
                                else -> if (isBn) "অন্যান্য" else "Misc"
                            }
                            FilterChip(
                                selected = category == cat,
                                onClick = { category = cat },
                                label = { Text(labelText) }
                            )
                        }
                    }

                    // Presets for Food & Daily Expense
                    if (category == "Food & Tea" || category == "Daily Expense") {
                        Text(
                            text = LanguageManager.getString("Quick Presets (Tap to fill note):", "দ্রুত ট্যাপ করুন (বর্ণনা লিখুন):"),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                        val presets = if (category == "Food & Tea") {
                            listOf(
                                if (isBn) "কর্মচারীর খাবার / লাঞ্চ" else "Staff Lunch / Meal",
                                if (isBn) "চা ও বিস্কুট" else "Tea & Biscuits",
                                if (isBn) "দৈনিক টিফিন" else "Daily Tiffin",
                                if (isBn) "জল / বরফ" else "Water / Ice"
                            )
                        } else {
                            listOf(
                                if (isBn) "দোকানের দৈনিক খরচ" else "Shop Daily Misc",
                                if (isBn) "দোকান পরিষ্কার" else "Cleaning Expense",
                                if (isBn) "রিকশা / গাড়ি ভাড়া" else "Rickshaw / Transport",
                                if (isBn) "প্যাকিং কাগজ / ব্যাগ" else "Packing Bags"
                            )
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(presets, key = { it }) { p ->
                                SuggestionChip(
                                    onClick = { note = p },
                                    label = { Text(p, fontSize = 11.sp) }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text(LanguageManager.getString("Amount (₹)", "পরিমাণ (₹)")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text(LanguageManager.getString("Note / Food Item / Description", "নোট / খাবারের বিবরণ")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Optional Voucher/Receipt Photo attachment
                    Text(
                        text = LanguageManager.getString("Receipt / Food Bill Photo (Camera / Gallery):", "রসিদ / বিলের ছবি (ক্যামেরা / গ্যালারি):"),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { launchReceiptCamera() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(LanguageManager.getString("Camera", "ক্যামেরা"), fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = { galleryLauncher.launch("image/*") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(LanguageManager.getString("Gallery", "গ্যালারি"), fontSize = 12.sp)
                        }
                    }

                    if (attachedImageUri != null) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(containerColor = SurfaceWarm)
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                AsyncImage(
                                    model = com.example.utils.ImageSyncHelper.getImageModel(attachedImageUri),
                                    contentDescription = "Attached Photo Preview",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clickable { showEnlargedPhoto = attachedImageUri }
                                )
                                IconButton(
                                    onClick = { attachedImageUri = null },
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp)
                                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                        .size(24.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove Photo", tint = Color.White, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = isRecurring,
                            onCheckedChange = { isRecurring = it }
                        )
                        Text(LanguageManager.getString("Monthly Recurring Expense", "মাসিক পুনরায় ঘটে যাওয়া খরচ"))
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val amt = amountText.toDoubleOrNull() ?: 0.0
                        if (amt > 0) {
                            val finalNote = buildString {
                                if (note.isNotBlank()) append(note.trim())
                                if (!attachedImageUri.isNullOrBlank()) {
                                    if (isNotEmpty()) append(" ")
                                    append("[Bill Photo: $attachedImageUri]")
                                }
                            }.ifBlank { null }

                            val exp = Expense(
                                id = "exp_" + UUID.randomUUID().toString().take(8),
                                date = System.currentTimeMillis(),
                                category = category,
                                amount = amt,
                                note = finalNote,
                                isRecurring = isRecurring
                            )
                            viewModel.saveExpense(exp)
                            showAddExpenseDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StoreRedPrimary)
                ) {
                    Text(LanguageManager.getString("Save", "সংরক্ষণ"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddExpenseDialog = false }) {
                    Text(LanguageManager.getString("Cancel", "বাতিল"))
                }
            }
        )

        if (showEnlargedPhoto != null) {
            AlertDialog(
                onDismissRequest = { showEnlargedPhoto = null },
                title = {
                    Text(LanguageManager.getString("Attached Receipt / Photo", "সংযুক্ত রসিদ / ছবি"), fontWeight = FontWeight.Bold)
                },
                text = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = com.example.utils.ImageSyncHelper.getImageModel(showEnlargedPhoto),
                            contentDescription = "Full Attached Photo",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showEnlargedPhoto = null }) {
                        Text(LanguageManager.getString("Close", "বন্ধ করুন"))
                    }
                }
            )
        }
    }

    if (viewPhotoUri != null) {
        AlertDialog(
            onDismissRequest = { viewPhotoUri = null },
            title = {
                Text(LanguageManager.getString("Expense Receipt / Bill Photo", "খরচের রসিদ / বিলের ছবি"), fontWeight = FontWeight.Bold)
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = com.example.utils.ImageSyncHelper.getImageModel(viewPhotoUri),
                        contentDescription = "Full Expense Photo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewPhotoUri = null }) {
                    Text(LanguageManager.getString("Close", "বন্ধ করুন"))
                }
            }
        )
    }

    // Delete Expense Confirmation Dialog
    if (expenseToDelete != null) {
        val exp = expenseToDelete!!
        val isPayroll = exp.category.equals("Salary", ignoreCase = true) ||
                exp.note?.contains("Staff Payroll", ignoreCase = true) == true
        val requiresPin = !StaffManager.isOwner()
        var enteredPin by remember { mutableStateOf("") }
        var pinError by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { expenseToDelete = null },
            icon = {
                Surface(
                    shape = CircleShape,
                    color = StoreRedPrimary.copy(alpha = 0.12f),
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.DeleteForever,
                            contentDescription = null,
                            tint = StoreRedPrimary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            },
            title = {
                Text(
                    text = if (isBn) "খরচ মুছে ফেলতে চান?" else "Delete Expense Record?",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextDark
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = if (isBn)
                            "আপনি কি নিশ্চিত যে আপনি এই খরচের রেকর্ডটি স্থায়ীভাবে মুছে ফেলতে চান?"
                        else
                            "Are you sure you want to permanently delete this expense record?",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted
                    )

                    // Expense Summary Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = SurfaceWarm),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    color = when (exp.category) {
                                        "Food & Tea" -> StoreSaffronAccent.copy(alpha = 0.15f)
                                        "Daily Expense" -> StorePrimary.copy(alpha = 0.15f)
                                        else -> StoreRedPrimary.copy(alpha = 0.12f)
                                    },
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = when (exp.category) {
                                            "Food & Tea" -> if (isBn) "খাবার ও চা 🍲" else "Food & Tea 🍲"
                                            "Daily Expense" -> if (isBn) "দৈনিক খরচ 📝" else "Daily Expense 📝"
                                            else -> exp.category
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = when (exp.category) {
                                            "Food & Tea" -> StoreSaffronAccent
                                            "Daily Expense" -> StorePrimary
                                            else -> StoreRedPrimary
                                        },
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }

                                Text(
                                    text = "₹%.2f".format(exp.amount),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = StoreRedPrimary
                                )
                            }

                            val cleanNote = cleanNotes(exp.note)
                            if (cleanNote.isNotBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = cleanNote,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = TextDark
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            val dateFormat = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
                            Text(
                                text = dateFormat.format(Date(exp.date)),
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                        }
                    }

                    // Special Staff Payroll Warning
                    if (isPayroll) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = StoreGold.copy(alpha = 0.12f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, StoreGold.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = StoreSaffronAccent,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isBn)
                                        "সতর্কতা: এটি স্টাফ পে-রোল বেতন প্রদানের এন্ট্রি। এটি মুছলে দোকান খরচ ও P&L থেকে বাদ যাবে, তবে পে-রোল হাবে সংরক্ষিত বেতন রেকর্ড অপরিবর্তিত থাকবে।"
                                    else
                                        "Warning: This expense was generated from Staff Payroll. Deleting it will update shop expenses and P&L totals, but will not erase the staff salary disbursement record in Payroll Hub.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextDark,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }

                    // Owner PIN protection for staff
                    if (requiresPin) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (isBn) "খরচ মুছতে মালিকের পিন (Owner PIN) দিন:" else "Enter Owner PIN to authorize deletion:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TextDark
                        )
                        OutlinedTextField(
                            value = enteredPin,
                            onValueChange = {
                                enteredPin = it
                                pinError = false
                            },
                            placeholder = { Text("4-digit Owner PIN") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            visualTransformation = PasswordVisualTransformation(),
                            isError = pinError,
                            supportingText = if (pinError) {
                                {
                                    Text(
                                        text = if (isBn) "ভুল পিন! সঠিক মালিকের পিন দিন।" else "Incorrect PIN! Please enter the correct Owner PIN.",
                                        color = StoreRedAlert
                                    )
                                }
                            } else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("delete_expense_owner_pin_input")
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (requiresPin) {
                            if (StaffManager.verifyOwnerPin(enteredPin)) {
                                viewModel.deleteExpense(exp)
                                viewModel.loadPnlReport(viewModel.selectedReportPeriod)
                                Toast.makeText(
                                    context,
                                    if (isBn) "₹%.2f খরচের রেকর্ড মুছে ফেলা হয়েছে".format(exp.amount)
                                    else "Expense of ₹%.2f deleted successfully".format(exp.amount),
                                    Toast.LENGTH_SHORT
                                ).show()
                                expenseToDelete = null
                            } else {
                                pinError = true
                            }
                        } else {
                            viewModel.deleteExpense(exp)
                            viewModel.loadPnlReport(viewModel.selectedReportPeriod)
                            Toast.makeText(
                                context,
                                if (isBn) "₹%.2f খরচের রেকর্ড মুছে ফেলা হয়েছে".format(exp.amount)
                                else "Expense of ₹%.2f deleted successfully".format(exp.amount),
                                Toast.LENGTH_SHORT
                            ).show()
                            expenseToDelete = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StoreRedPrimary),
                    modifier = Modifier.testTag("confirm_delete_expense_button")
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isBn) "মুছে ফেলুন" else "Delete Expense",
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { expenseToDelete = null }) {
                    Text(if (isBn) "বাতিল" else "Cancel", color = TextMuted)
                }
            }
        )
    }
}

