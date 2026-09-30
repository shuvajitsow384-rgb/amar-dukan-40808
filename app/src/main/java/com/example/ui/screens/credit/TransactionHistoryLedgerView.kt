package com.example.ui.screens.credit

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.dao.SaleWithItems
import com.example.data.local.entities.Customer
import com.example.data.local.entities.LedgerEntry
import com.example.data.local.entities.Supplier
import com.example.ui.components.*
import com.example.ui.screens.suppliers.cleanNotes
import com.example.ui.screens.suppliers.extractPhotoUri
import com.example.ui.theme.*
import com.example.utils.LanguageManager
import com.example.viewmodel.StoreViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionHistoryLedgerView(
    allLedgerEntries: List<LedgerEntry>,
    customers: List<Customer>,
    suppliers: List<Supplier>,
    allSales: List<SaleWithItems>,
    viewModel: StoreViewModel,
    onNavigateToCustomer: (Customer) -> Unit,
    onNavigateToSupplier: (Supplier) -> Unit,
    modifier: Modifier = Modifier
) {
    val isBn = LanguageManager.isBengali
    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy hh:mm a", Locale.getDefault()) }

    var searchQuery by remember { mutableStateOf("") }
    var selectedPartyTypeFilter by remember { mutableStateOf("ALL") } // ALL, CUSTOMER, SUPPLIER
    var selectedDirectionFilter by remember { mutableStateOf("ALL") } // ALL, RECEIVED, GIVEN

    // Date Picker state
    var selectedDatePreset by remember { mutableStateOf(LedgerDatePreset.ALL) }
    var customStartDate by remember {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        mutableLongStateOf(cal.timeInMillis)
    }
    var customEndDate by remember {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        mutableLongStateOf(cal.timeInMillis)
    }

    var enlargedPhotoPair by remember { mutableStateOf<Pair<String, String>?>(null) }

    // 300ms debounce for search query to avoid recomposition on every keystroke
    var debouncedSearchQuery by remember { mutableStateOf(searchQuery) }
    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            debouncedSearchQuery = ""
        } else {
            kotlinx.coroutines.delay(300)
            debouncedSearchQuery = searchQuery
        }
    }

    // Filter ledger entries by Date Range, Party Type, Direction, and Search Query
    val filteredEntries = remember(
        allLedgerEntries,
        selectedDatePreset,
        customStartDate,
        customEndDate,
        selectedPartyTypeFilter,
        selectedDirectionFilter,
        debouncedSearchQuery
    ) {
        val bounds = if (selectedDatePreset == LedgerDatePreset.CUSTOM) {
            Pair(customStartDate, customEndDate)
        } else {
            getPresetDateBounds(selectedDatePreset)
        }

        allLedgerEntries.filter { entry ->
            // Date bounds
            val afterStart = bounds.first == null || entry.datetime >= bounds.first!!
            val beforeEnd = bounds.second == null || entry.datetime <= bounds.second!!
            if (!afterStart || !beforeEnd) return@filter false

            // Party Type
            if (selectedPartyTypeFilter != "ALL" && !entry.partyType.equals(selectedPartyTypeFilter, ignoreCase = true)) {
                return@filter false
            }

            // Direction Filter
            val isReceived = entry.type.contains("RECEIVED") || entry.type.contains("MADE")
            if (selectedDirectionFilter == "RECEIVED" && !isReceived) return@filter false
            if (selectedDirectionFilter == "GIVEN" && isReceived) return@filter false

            // Search query (debounced)
            if (debouncedSearchQuery.isNotBlank()) {
                val q = debouncedSearchQuery.trim().lowercase()
                val matchesName = entry.partyName.lowercase().contains(q)
                val matchesNote = entry.note?.lowercase()?.contains(q) == true
                val matchesRef = entry.referenceId?.lowercase()?.contains(q) == true
                val matchesAmount = "%.2f".format(Locale.US, entry.amount).contains(q)
                if (!matchesName && !matchesNote && !matchesRef && !matchesAmount) return@filter false
            }

            true
        }.sortedByDescending { it.datetime }
    }

    // Period Financial Statistics
    val customerPaymentReceived = remember(filteredEntries) {
        filteredEntries.filter { it.partyType == "CUSTOMER" && it.type.contains("RECEIVED") }.sumOf { it.amount }
    }
    val customerCreditIssued = remember(filteredEntries) {
        filteredEntries.filter { it.partyType == "CUSTOMER" && !it.type.contains("RECEIVED") }.sumOf { it.amount }
    }
    val supplierPaymentsMade = remember(filteredEntries) {
        filteredEntries.filter { it.partyType == "SUPPLIER" && it.type.contains("MADE") }.sumOf { it.amount }
    }
    val supplierCreditPurchased = remember(filteredEntries) {
        filteredEntries.filter { it.partyType == "SUPPLIER" && !it.type.contains("MADE") }.sumOf { it.amount }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Search & Filter Header
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = {
                Text(
                    LanguageManager.getString("Search transactions by party, note, amount...", "নাম, নোট বা পরিমাণ দিয়ে লেনদেন খুঁজুন..."),
                    fontSize = 12.sp
                )
            },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp), tint = TextMuted)
            },
            trailingIcon = {
                if (searchQuery.isNotBlank()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = SurfaceWarm,
                unfocusedContainerColor = SurfaceWarm,
                focusedBorderColor = StorePrimary,
                unfocusedBorderColor = NeutralBorderDivider
            ),
            modifier = Modifier.fillMaxWidth()
        )

        // Date Picker UI Component
        LedgerDateRangeFilterComponent(
            selectedPreset = selectedDatePreset,
            customStartDate = customStartDate,
            customEndDate = customEndDate,
            onPresetChange = { selectedDatePreset = it },
            onCustomRangeChange = { start, end ->
                customStartDate = start
                customEndDate = end
            },
            onReset = {
                selectedDatePreset = LedgerDatePreset.ALL
            },
            isCompact = false,
            isBn = isBn
        )

        // Secondary Filter Chips (Party Type & Direction)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Party Types
            listOf(
                "ALL" to (if (isBn) "সকল খাতা" else "All Parties"),
                "CUSTOMER" to (if (isBn) "গ্রাহক (Customers)" else "Customers"),
                "SUPPLIER" to (if (isBn) "মহাজন (Suppliers)" else "Suppliers")
            ).forEach { (key, label) ->
                val isSelected = selectedPartyTypeFilter == key
                FilterChip(
                    selected = isSelected,
                    onClick = { selectedPartyTypeFilter = key },
                    label = { Text(label, fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = StorePrimaryContainer,
                        selectedLabelColor = StorePrimaryDark,
                        containerColor = CardBackground,
                        labelColor = TextDark
                    ),
                    modifier = Modifier.height(30.dp)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Direction Types
            listOf(
                "ALL" to (if (isBn) "সকল লেনদেন" else "All Entries"),
                "RECEIVED" to (if (isBn) "জমা / পরিশোধ (Payments)" else "Payments In/Out"),
                "GIVEN" to (if (isBn) "বকেয়া / ক্রয় (Credit Taken)" else "Credit Sales / Invoices")
            ).forEach { (key, label) ->
                val isSelected = selectedDirectionFilter == key
                FilterChip(
                    selected = isSelected,
                    onClick = { selectedDirectionFilter = key },
                    label = { Text(label, fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = if (key == "RECEIVED") StoreGreenProfit.copy(alpha = 0.15f) else StoreRedAlert.copy(alpha = 0.15f),
                        selectedLabelColor = if (key == "RECEIVED") StoreGreenProfit else StoreRedAlert,
                        containerColor = CardBackground,
                        labelColor = TextDark
                    ),
                    modifier = Modifier.height(30.dp)
                )
            }
        }

        // Period Summary Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceWarm),
            border = BorderStroke(1.dp, NeutralBorderDivider),
            shape = RoundedCornerShape(10.dp)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isBn) "লেনদেন সারাংশ (${filteredEntries.size} টি এন্ট্রি)" else "Period Ledger Summary (${filteredEntries.size} Entries)",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextMuted
                    )

                    if (selectedDatePreset != LedgerDatePreset.ALL || selectedPartyTypeFilter != "ALL" || selectedDirectionFilter != "ALL" || searchQuery.isNotBlank()) {
                        Text(
                            text = if (isBn) "ফিল্টার প্রযোজ্য" else "Filtered View",
                            fontSize = 10.sp,
                            color = StorePrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Money In (Customer payments)
                    Column {
                        Text(
                            text = if (isBn) "গ্রাহক জমা (Cash In)" else "Payments In",
                            fontSize = 10.sp,
                            color = TextMuted
                        )
                        Text(
                            text = "+₹%.2f".format(customerPaymentReceived),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = StoreGreenProfit
                        )
                    }

                    // Credit Issued
                    Column {
                        Text(
                            text = if (isBn) "ধারে বিক্রি (Credit Given)" else "Credit Given",
                            fontSize = 10.sp,
                            color = TextMuted
                        )
                        Text(
                            text = "₹%.2f".format(customerCreditIssued),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = StoreRedAlert
                        )
                    }

                    // Supplier Paid
                    Column {
                        Text(
                            text = if (isBn) "মহাজন পরিশোধ (Paid)" else "Supplier Paid",
                            fontSize = 10.sp,
                            color = TextMuted
                        )
                        Text(
                            text = "-₹%.2f".format(supplierPaymentsMade),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = StoreGreenProfit
                        )
                    }
                }
            }
        }

        // Ledger Transactions List
        if (filteredEntries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ReceiptLong,
                        contentDescription = null,
                        modifier = Modifier.size(42.dp),
                        tint = TextMuted.copy(alpha = 0.5f)
                    )
                    Text(
                        text = LanguageManager.getString(
                            "No transactions match the selected date range and filters.",
                            "নির্বাচিত তারিখ ও ফিল্টারের মধ্যে কোন লেনদেন পাওয়া যায়নি।"
                        ),
                        color = TextMuted,
                        fontSize = 13.sp
                    )
                    OutlinedButton(
                        onClick = {
                            selectedDatePreset = LedgerDatePreset.ALL
                            selectedPartyTypeFilter = "ALL"
                            selectedDirectionFilter = "ALL"
                            searchQuery = ""
                        },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(LanguageManager.getString("Reset All Filters", "সকল ফিল্টার মুছুন"), fontSize = 12.sp)
                    }
                }
            }
        } else {
            val customersById = remember(customers) { customers.associateBy { it.id } }
            val suppliersById = remember(suppliers) { suppliers.associateBy { it.id } }
            val salesById = remember(allSales) { allSales.associateBy { it.sale.id } }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 60.dp)
            ) {
                items(
                    items = filteredEntries,
                    key = { it.id },
                    contentType = { entry ->
                        if (entry.type.contains("RECEIVED") || entry.type.contains("MADE")) "LEDGER_CREDIT" else "LEDGER_DEBIT"
                    }
                ) { entry ->
                    val isCustomer = entry.partyType == "CUSTOMER"
                    val linkedCustomer = if (isCustomer) customersById[entry.partyId] else null
                    val linkedSupplier = if (!isCustomer) suppliersById[entry.partyId] else null
                    val linkedSale = if (isCustomer && entry.referenceId != null) {
                        salesById[entry.referenceId]
                    } else null

                    var expanded by remember { mutableStateOf(false) }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem()
                            .animateContentSize()
                            .clickable(enabled = linkedSale != null) { expanded = !expanded },
                        colors = CardDefaults.cardColors(
                            containerColor = if (entry.type.contains("RECEIVED") || entry.type.contains("MADE")) {
                                StoreGreenProfit.copy(alpha = 0.06f)
                            } else {
                                StoreRedAlert.copy(alpha = 0.05f)
                            }
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (entry.type.contains("RECEIVED") || entry.type.contains("MADE")) {
                                StoreGreenProfit.copy(alpha = 0.25f)
                            } else {
                                StoreRedAlert.copy(alpha = 0.2f)
                            }
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top
                            ) {
                                // Party Avatar & Details
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    PartyProfileAvatar(
                                        photoUri = linkedCustomer?.photoUri ?: linkedSupplier?.photoUri,
                                        name = entry.partyName,
                                        size = 36.dp,
                                        onClick = {
                                            if (linkedCustomer != null) onNavigateToCustomer(linkedCustomer)
                                            else if (linkedSupplier != null) onNavigateToSupplier(linkedSupplier)
                                        }
                                    )

                                    Column {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = entry.partyName,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                color = TextDark,
                                                modifier = Modifier.clickable {
                                                    if (linkedCustomer != null) onNavigateToCustomer(linkedCustomer)
                                                    else if (linkedSupplier != null) onNavigateToSupplier(linkedSupplier)
                                                }
                                            )

                                            // Party Type Pill
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = if (isCustomer) StorePrimary.copy(alpha = 0.12f) else StoreGold.copy(alpha = 0.18f)
                                            ) {
                                                Text(
                                                    text = if (isCustomer) (if (isBn) "গ্রাহক" else "Cust") else (if (isBn) "মহাজন" else "Supp"),
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isCustomer) StorePrimary else StoreGold,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                        }

                                        // Transaction Tag & Date
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            modifier = Modifier.padding(top = 2.dp)
                                        ) {
                                            Surface(
                                                shape = RoundedCornerShape(3.dp),
                                                color = when {
                                                    entry.type.contains("RECEIVED") -> StoreGreenProfit
                                                    entry.type.contains("MADE") -> StoreGreenProfit
                                                    entry.type.contains("INTEREST") -> StoreGold
                                                    else -> StorePrimary
                                                }
                                            ) {
                                                Text(
                                                    text = when (entry.type) {
                                                        "SALE_CREDIT" -> if (isBn) "ধারে বিক্রি (Debit)" else "Credit Sale"
                                                        "PAYMENT_RECEIVED" -> if (isBn) "টাকা জমা (Credit)" else "Payment Received"
                                                        "PURCHASE_CREDIT" -> if (isBn) "ধারে ক্রয় (Payable)" else "Credit Purchase"
                                                        "PAYMENT_MADE" -> if (isBn) "পরিশোধ (Paid)" else "Payment Made"
                                                        "OPENING_BALANCE", "INITIAL_DUE" -> if (isBn) "প্রারম্ভিক জের" else "Opening Balance"
                                                        "INTEREST", "INTEREST_CHARGE" -> if (isBn) "বিলম্ব সুদ" else "Late Fee"
                                                        else -> entry.type
                                                    },
                                                    color = Color.White,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }

                                            Text(
                                                text = dateFormat.format(Date(entry.datetime)),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontSize = 10.sp,
                                                color = TextMuted
                                            )
                                        }
                                    }
                                }

                                // Amount & Expand toggle
                                Column(horizontalAlignment = Alignment.End) {
                                    val isCreditInflow = entry.type.contains("RECEIVED") || entry.type.contains("MADE")
                                    Text(
                                        text = (if (isCreditInflow) "- ₹%.2f" else "+ ₹%.2f").format(entry.amount),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = if (isCreditInflow) StoreGreenProfit else StoreRedAlert
                                    )

                                    if (linkedSale != null) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(top = 2.dp)
                                        ) {
                                            Text(
                                                text = if (expanded) "Hide Items" else "Items (${linkedSale.items.size})",
                                                fontSize = 10.sp,
                                                color = StorePrimary,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Icon(
                                                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                contentDescription = null,
                                                tint = StorePrimary,
                                                modifier = Modifier.size(12.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // Note & Photo
                            val cleanNote = cleanNotes(entry.note)
                            val photoUri = extractPhotoUri(entry.note)
                            if (cleanNote.isNotBlank() || photoUri != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    if (cleanNote.isNotBlank()) {
                                        Text(
                                            text = "Note: $cleanNote",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontSize = 11.sp,
                                            color = TextDark,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                    }

                                    if (photoUri != null) {
                                        Surface(
                                            color = StorePrimary.copy(alpha = 0.12f),
                                            shape = RoundedCornerShape(4.dp),
                                            modifier = Modifier.clickable {
                                                enlargedPhotoPair = photoUri to "${entry.partyName} Payment Voucher"
                                            }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Default.Photo, contentDescription = null, tint = StorePrimary, modifier = Modifier.size(12.dp))
                                                Spacer(modifier = Modifier.width(3.dp))
                                                Text("Voucher 📷", fontSize = 10.sp, color = StorePrimary, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }

                            // Expanded Sale Items
                            if (expanded && linkedSale != null) {
                                Spacer(modifier = Modifier.height(6.dp))
                                HorizontalDivider(color = NeutralBorderDivider)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Bill #${linkedSale.sale.id.takeLast(6)} Items:",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextMuted
                                )
                                linkedSale.items.forEach { item ->
                                    val itemName = if (isBn && item.productNameBn.isNotBlank()) item.productNameBn else item.productNameEn
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 1.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = " • $itemName (x${item.quantity})",
                                            fontSize = 11.sp,
                                            color = TextDark
                                        )
                                        Text(
                                            text = "₹%.2f".format(item.subtotal),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextDark
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

    // Photo Enlarged Dialog
    if (enlargedPhotoPair != null) {
        val (uri, title) = enlargedPhotoPair!!
        EnlargedPhotoDialog(
            photoUri = uri,
            title = title,
            onDismiss = { enlargedPhotoPair = null }
        )
    }
}
