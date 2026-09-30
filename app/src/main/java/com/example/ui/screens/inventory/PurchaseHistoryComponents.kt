package com.example.ui.screens.inventory

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.local.dao.PurchaseWithItems
import com.example.data.local.entities.Product
import com.example.data.local.entities.Purchase
import com.example.data.local.entities.PurchaseItem
import com.example.data.local.entities.Supplier
import com.example.ui.components.PartyProfileAvatar
import com.example.ui.screens.suppliers.cleanNotes
import com.example.ui.screens.suppliers.extractPhotoUri
import com.example.ui.theme.*
import com.example.utils.LanguageManager
import com.example.utils.StoreInfoManager
import com.example.viewmodel.StoreViewModel
import java.text.SimpleDateFormat
import java.util.*

/**
 * Dialog showing complete purchase history for a single product.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductPurchaseHistoryDialog(
    product: Product,
    allPurchases: List<PurchaseWithItems>,
    suppliers: List<Supplier>,
    isBn: Boolean,
    onDismiss: () -> Unit,
    onViewFullInvoice: (PurchaseWithItems) -> Unit,
    onStockIn: () -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilterPeriod by remember { mutableIntStateOf(0) } // 0: All time, 1: Last 30 Days, 2: This Month
    var viewEnlargedPhotoUri by remember { mutableStateOf<String?>(null) }

    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()) }
    val dateOnlyFormat = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }

    // Filter purchases that contain this product
    data class ProductPurchaseRecord(
        val purchase: Purchase,
        val item: PurchaseItem,
        val fullPurchaseWithItems: PurchaseWithItems,
        val supplier: Supplier?
    )

    val relevantRecords = remember(allPurchases, product.id, searchQuery, selectedFilterPeriod) {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()

        val periodStart = when (selectedFilterPeriod) {
            1 -> now - (30L * 24 * 60 * 60 * 1000) // Last 30 Days
            2 -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis
            }
            else -> 0L
        }

        val records = mutableListOf<ProductPurchaseRecord>()
        for (pWithItems in allPurchases) {
            if (pWithItems.purchase.datetime < periodStart) continue

            val matchingItems = pWithItems.items.filter { it.productId == product.id }
            val supp = suppliers.firstOrNull { it.id == pWithItems.purchase.supplierId }

            for (item in matchingItems) {
                val matchesQuery = searchQuery.isBlank() ||
                        pWithItems.purchase.supplierName?.contains(searchQuery, ignoreCase = true) == true ||
                        pWithItems.purchase.notes?.contains(searchQuery, ignoreCase = true) == true ||
                        pWithItems.purchase.id.contains(searchQuery, ignoreCase = true)

                if (matchesQuery) {
                    records.add(
                        ProductPurchaseRecord(
                            purchase = pWithItems.purchase,
                            item = item,
                            fullPurchaseWithItems = pWithItems,
                            supplier = supp
                        )
                    )
                }
            }
        }
        records.sortedByDescending { it.purchase.datetime }
    }

    // Summary calculations
    val totalPurchasedQty = remember(relevantRecords) { relevantRecords.sumOf { it.item.quantity } }
    val totalPurchasedSpend = remember(relevantRecords) { relevantRecords.sumOf { it.item.subtotal } }
    val avgCostPrice = remember(totalPurchasedQty, totalPurchasedSpend) {
        if (totalPurchasedQty > 0) totalPurchasedSpend / totalPurchasedQty else product.costPrice
    }
    val lastRecord = relevantRecords.firstOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Surface(
                        color = StorePrimary.copy(alpha = 0.15f),
                        shape = CircleShape,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Default.ReceiptLong,
                            contentDescription = null,
                            tint = StorePrimary,
                            modifier = Modifier
                                .padding(8.dp)
                                .size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = product.getDisplayName(isBn),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = LanguageManager.getString("Purchase & Restock History", "ক্রয় ও স্টক চালানের ইতিহাস"),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                }

                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // --- 1. Top Metrics Summary Card ---
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceWarm),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, TextMuted.copy(alpha = 0.15f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = LanguageManager.getString("Total Purchased", "মোট ক্রয় পরিমাণ"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted
                                )
                                Text(
                                    text = "%.1f %s".format(totalPurchasedQty, product.unitType),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = StorePrimary
                                )
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = LanguageManager.getString("Total Procurement Cost", "মোট ব্যয়িত অর্থ"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted
                                )
                                Text(
                                    text = "₹%.2f".format(totalPurchasedSpend),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = StoreRedPrimary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(color = TextMuted.copy(alpha = 0.2f))
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = LanguageManager.getString("Avg. Cost Price", "গড় ক্রয়মূল্য"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted
                                )
                                Text(
                                    text = "₹%.2f / %s".format(avgCostPrice, product.unitType),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = StoreGreenProfit
                                )
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = LanguageManager.getString("Current Stock", "বর্তমান মজুদ"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted
                                )
                                Text(
                                    text = "%.1f %s".format(product.currentStock, product.unitType),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (product.currentStock <= product.lowStockThreshold) StoreRedAlert else TextDark
                                )
                            }
                        }

                        if (lastRecord != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "${LanguageManager.getString("Last Restocked:", "সর্বশেষ ইনভয়েস:")} ${dateOnlyFormat.format(Date(lastRecord.purchase.datetime))} " +
                                        (if (!lastRecord.purchase.supplierName.isNullOrBlank()) "(${lastRecord.purchase.supplierName})" else ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                // --- 2. Filter Period Chips & Search Bar ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(
                        0 to LanguageManager.getString("All Time", "সব সময়"),
                        1 to LanguageManager.getString("Last 30 Days", "গত ৩০ দিন"),
                        2 to LanguageManager.getString("This Month", "এই মাস")
                    ).forEach { (periodKey, periodLabel) ->
                        FilterChip(
                            selected = selectedFilterPeriod == periodKey,
                            onClick = { selectedFilterPeriod = periodKey },
                            label = { Text(periodLabel, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = StorePrimary,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(LanguageManager.getString("Search by supplier, bill #...", "মহাজন বা চালান নং খুঁজুন..."), fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                // --- 3. Purchase Records List ---
                if (relevantRecords.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.ReceiptLong,
                                contentDescription = null,
                                tint = TextMuted.copy(alpha = 0.5f),
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = LanguageManager.getString("No purchase history found for this product.", "এই পণ্যের কোন ক্রয়ের রেকর্ড নেই।"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMuted,
                                textAlign = TextAlign.Center
                            )
                            Button(
                                onClick = {
                                    onDismiss()
                                    onStockIn()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = StoreGreenProfit),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(LanguageManager.getString("Add Stock-IN / Purchase", "নতুন চালান / স্টক-ইন যোগ করুন"))
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(relevantRecords, key = { "${it.purchase.id}_${it.item.id}" }, contentType = { "PURCHASE_HISTORY_RECORD" }) { record ->
                            val p = record.purchase
                            val item = record.item
                            val photoUri = extractPhotoUri(p.notes)
                            val cleanNote = cleanNotes(p.notes)

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onViewFullInvoice(record.fullPurchaseWithItems) },
                                colors = CardDefaults.cardColors(containerColor = CardBackground),
                                shape = RoundedCornerShape(10.dp),
                                elevation = CardDefaults.cardElevation(1.dp),
                                border = BorderStroke(0.5.dp, TextMuted.copy(alpha = 0.2f))
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Surface(
                                                color = when {
                                                    p.amountPaid >= p.totalAmount -> StoreGreenProfit.copy(alpha = 0.12f)
                                                    p.amountPaid <= 0.0 -> StoreRedPrimary.copy(alpha = 0.12f)
                                                    else -> Color(0xFFF59E0B).copy(alpha = 0.15f)
                                                },
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    text = when {
                                                        p.amountPaid >= p.totalAmount -> if (p.paidVia.contains("UPI", ignoreCase = true) || p.paidVia.contains("ONLINE", ignoreCase = true)) "UPI" else LanguageManager.getString("Paid (${p.paidVia})", "নগদ পরিশোধ")
                                                        p.amountPaid <= 0.0 -> LanguageManager.getString("Credit Bill", "বাকি চালান")
                                                        else -> "Partial (₹%.0f/₹%.0f)".format(p.amountPaid, p.totalAmount)
                                                    },
                                                    color = when {
                                                        p.amountPaid >= p.totalAmount -> StoreGreenProfit
                                                        p.amountPaid <= 0.0 -> StoreRedPrimary
                                                        else -> Color(0xFFD97706)
                                                    },
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "Bill #${p.id.takeLast(6)}",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }

                                        Text(
                                            text = dateFormat.format(Date(p.datetime)),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextMuted,
                                            fontSize = 11.sp
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.Storefront, contentDescription = null, modifier = Modifier.size(14.dp), tint = TextMuted)
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = p.supplierName ?: LanguageManager.getString("Direct Market Purchase", "সরাসরি বাজার ক্রয়"),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                            Text(
                                                text = "${LanguageManager.getString("Qty:", "পরিমাণ:")} %.1f %s @ ₹%.2f".format(item.quantity, product.unitType, item.costPrice),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = TextMuted
                                            )
                                        }

                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(
                                                text = "₹%.2f".format(item.subtotal),
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = StoreRedPrimary
                                            )
                                            Text(
                                                text = LanguageManager.getString("Item Total", "আইটেম মূল্য"),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextMuted,
                                                fontSize = 10.sp
                                            )
                                        }
                                    }

                                    if (cleanNote.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Note: $cleanNote",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextMuted,
                                            fontSize = 11.sp
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (photoUri != null) {
                                            Surface(
                                                color = StorePrimary.copy(alpha = 0.12f),
                                                shape = RoundedCornerShape(6.dp),
                                                modifier = Modifier.clickable { viewEnlargedPhotoUri = photoUri }
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(Icons.Default.Image, contentDescription = null, tint = StorePrimary, modifier = Modifier.size(13.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(LanguageManager.getString("Bill Photo 📷", "বিলের ছবি 📷"), fontSize = 11.sp, color = StorePrimary, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        } else {
                                            Spacer(modifier = Modifier.width(1.dp))
                                        }

                                        Text(
                                            text = "${LanguageManager.getString("Full Bill Details", "সম্পূর্ণ বিল দেখুন")} →",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = StorePrimary,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.clickable { onViewFullInvoice(record.fullPurchaseWithItems) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onDismiss()
                    onStockIn()
                },
                colors = ButtonDefaults.buttonColors(containerColor = StoreGreenProfit),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(LanguageManager.getString("New Stock-IN / Buy", "নতুন স্টক ইন"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(LanguageManager.getString("Close", "বন্ধ করুন"))
            }
        }
    )

    // Photo Preview Modal
    if (viewEnlargedPhotoUri != null) {
        AlertDialog(
            onDismissRequest = { viewEnlargedPhotoUri = null },
            title = { Text(LanguageManager.getString("Purchase Bill Photo", "ক্রয় বিলের ছবি"), fontWeight = FontWeight.Bold) },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = com.example.utils.ImageSyncHelper.getImageModel(viewEnlargedPhotoUri),
                        contentDescription = "Bill Photo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewEnlargedPhotoUri = null }) {
                    Text(LanguageManager.getString("Close", "বন্ধ করুন"))
                }
            }
        )
    }
}

/**
 * Store-Wide Purchase History Hub Dialog
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorePurchaseHistoryHubDialog(
    viewModel: StoreViewModel,
    isBn: Boolean,
    onDismiss: () -> Unit,
    onViewFullInvoice: (PurchaseWithItems) -> Unit
) {
    val purchases by viewModel.allPurchases.collectAsState()
    val suppliers by viewModel.allSuppliers.collectAsState()
    val products by viewModel.allProducts.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedSupplierId by remember { mutableStateOf<String?>("ALL") }
    var selectedPaymentMode by remember { mutableStateOf("ALL") }
    var selectedPeriod by remember { mutableIntStateOf(0) } // 0: All, 1: Today, 2: This Week, 3: This Month

    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()) }

    // Filter purchases
    val filteredPurchases = remember(purchases, searchQuery, selectedSupplierId, selectedPaymentMode, selectedPeriod) {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()

        val periodStart = when (selectedPeriod) {
            1 -> {
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis
            }
            2 -> {
                cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis
            }
            3 -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis
            }
            else -> 0L
        }

        purchases.filter { pWithItems ->
            val p = pWithItems.purchase
            if (p.datetime < periodStart) return@filter false

            val matchesSupplier = selectedSupplierId == "ALL" || p.supplierId == selectedSupplierId
            val matchesPayment = selectedPaymentMode == "ALL" || p.paymentMode.equals(selectedPaymentMode, ignoreCase = true)

            val matchesSearch = searchQuery.isBlank() ||
                    p.id.contains(searchQuery, ignoreCase = true) ||
                    p.supplierName?.contains(searchQuery, ignoreCase = true) == true ||
                    p.notes?.contains(searchQuery, ignoreCase = true) == true ||
                    pWithItems.items.any {
                        it.productNameEn.contains(searchQuery, ignoreCase = true) ||
                                it.productNameBn.contains(searchQuery, ignoreCase = true)
                    }

            matchesSupplier && matchesPayment && matchesSearch
        }.sortedByDescending { it.purchase.datetime }
    }

    val totalPurchasesSpend = remember(filteredPurchases) { filteredPurchases.sumOf { it.purchase.totalAmount } }
    val totalItemsCount = remember(filteredPurchases) { filteredPurchases.sumOf { it.items.sumOf { item -> item.quantity } } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ReceiptLong, contentDescription = null, tint = StorePrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = LanguageManager.getString("Purchase History & Invoices", "ক্রয় খতিয়ান ও চালানসমূহ"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // --- Overview Header ---
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceWarm),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(LanguageManager.getString("Total Purchases", "মোট ক্রয় চালান"), style = MaterialTheme.typography.labelSmall, color = TextMuted)
                            Text("${filteredPurchases.size} ${LanguageManager.getString("Bills", "টি চালান")}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = StorePrimary)
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(LanguageManager.getString("Total Bill Value", "মোট ক্রয় মূল্য"), style = MaterialTheme.typography.labelSmall, color = TextMuted)
                            Text("₹%.2f".format(totalPurchasesSpend), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = StoreRedPrimary)
                        }
                    }
                }

                // --- Search Bar ---
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(LanguageManager.getString("Search by item, supplier, invoice #...", "পণ্য, মহাজন বা ইনভয়েস খুঁজুন..."), fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                // --- Filter Chips for Date ---
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(
                        0 to LanguageManager.getString("All Time", "সব"),
                        1 to LanguageManager.getString("Today", "আজ"),
                        2 to LanguageManager.getString("This Week", "এই সপ্তাহ"),
                        3 to LanguageManager.getString("This Month", "এই মাস")
                    ).forEach { (pKey, pLabel) ->
                        item {
                            FilterChip(
                                selected = selectedPeriod == pKey,
                                onClick = { selectedPeriod = pKey },
                                label = { Text(pLabel, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = StorePrimary,
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }
                }

                // --- Supplier Filter Chips ---
                if (suppliers.isNotEmpty()) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        item {
                            FilterChip(
                                selected = selectedSupplierId == "ALL",
                                onClick = { selectedSupplierId = "ALL" },
                                label = { Text(LanguageManager.getString("All Suppliers", "সব মহাজন"), fontSize = 11.sp) }
                            )
                        }
                        items(suppliers, key = { it.id }) { supp ->
                            FilterChip(
                                selected = selectedSupplierId == supp.id,
                                onClick = { selectedSupplierId = supp.id },
                                label = { Text(supp.name, fontSize = 11.sp) }
                            )
                        }
                    }
                }

                // --- Purchases List ---
                if (filteredPurchases.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.Inbox, contentDescription = null, tint = TextMuted.copy(alpha = 0.5f), modifier = Modifier.size(40.dp))
                            Text(
                                text = LanguageManager.getString("No purchase invoices found matching filters.", "ফিল্টারের সাথে মেলে এমন কোন ক্রয় চালান পাওয়া যায়নি।"),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredPurchases, key = { it.purchase.id }, contentType = { "PURCHASE_INVOICE_CARD" }) { pWithItems ->
                            val p = pWithItems.purchase
                            val items = pWithItems.items
                            var isExpanded by remember { mutableStateOf(false) }

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onViewFullInvoice(pWithItems) },
                                colors = CardDefaults.cardColors(containerColor = CardBackground),
                                elevation = CardDefaults.cardElevation(1.dp),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(0.5.dp, TextMuted.copy(alpha = 0.2f))
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = "Bill #${p.id.takeLast(6)}",
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Surface(
                                                    color = when {
                                                        p.amountPaid >= p.totalAmount -> StoreGreenProfit.copy(alpha = 0.12f)
                                                        p.amountPaid <= 0.0 -> StoreRedPrimary.copy(alpha = 0.12f)
                                                        else -> Color(0xFFF59E0B).copy(alpha = 0.15f)
                                                    },
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        text = when {
                                                            p.amountPaid >= p.totalAmount -> "PAID (${p.paidVia})"
                                                            p.amountPaid <= 0.0 -> "CREDIT"
                                                            else -> "PARTIAL (₹%.0f)".format(p.amountPaid)
                                                        },
                                                        color = when {
                                                            p.amountPaid >= p.totalAmount -> StoreGreenProfit
                                                            p.amountPaid <= 0.0 -> StoreRedPrimary
                                                            else -> Color(0xFFD97706)
                                                        },
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = p.supplierName ?: LanguageManager.getString("Direct Market Purchase", "সরাসরি ক্রয়"),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = TextDark
                                            )
                                            Text(
                                                text = dateFormat.format(Date(p.datetime)),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextMuted
                                            )
                                        }

                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(
                                                text = "₹%.2f".format(p.totalAmount),
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = StoreRedPrimary
                                            )
                                            Text(
                                                text = "${items.size} ${LanguageManager.getString("Items", "টি আইটেম")}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextMuted
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))
                                    HorizontalDivider(color = TextMuted.copy(alpha = 0.15f))
                                    Spacer(modifier = Modifier.height(6.dp))

                                    // Item preview tags
                                    Text(
                                        text = items.joinToString(", ") { "${it.productNameEn} (%.1f)".format(it.quantity) },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextMuted,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val photoUri = extractPhotoUri(p.notes)
                                        if (photoUri != null) {
                                            Text(
                                                text = "📷 ${LanguageManager.getString("Photo Attached", "ছবি সংযুক্ত")}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = StorePrimary,
                                                fontWeight = FontWeight.Bold
                                            )
                                        } else {
                                            Spacer(modifier = Modifier.width(1.dp))
                                        }

                                        Text(
                                            text = "${LanguageManager.getString("View Bill Details", "বিস্তারিত দেখুন")} →",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = StorePrimary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(LanguageManager.getString("Close", "বন্ধ করুন"))
            }
        }
    )
}

/**
 * Purchase Invoice Details Modal with Bill Breakdown & Print/Share Options
 */
@Composable
fun PurchaseInvoiceDetailDialog(
    purchaseWithItems: PurchaseWithItems,
    isBn: Boolean,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val p = purchaseWithItems.purchase
    val items = purchaseWithItems.items
    val dateFormat = remember { SimpleDateFormat("dd MMMM yyyy, hh:mm a", Locale.getDefault()) }
    val photoUri = extractPhotoUri(p.notes)
    val cleanNote = cleanNotes(p.notes)

    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showEnlargedPhoto by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Receipt, contentDescription = null, tint = StorePrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${LanguageManager.getString("Purchase Bill", "ক্রয় চালান")} #${p.id.takeLast(6)}",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Store Info & Supplier Header
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceWarm),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = StoreInfoManager.storeName.ifBlank { "Amar Dukan" },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = dateFormat.format(Date(p.datetime)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted
                                )
                            }

                            Surface(
                                color = if (p.paymentMode == "CREDIT") StoreRedPrimary else StoreGreenProfit,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = p.paymentMode,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(color = TextMuted.copy(alpha = 0.2f))
                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "${LanguageManager.getString("Supplier / Source:", "মহাজন / সরবরাহকারী:")} ${p.supplierName ?: LanguageManager.getString("Direct Market Purchase", "সরাসরি ক্রয়")}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (cleanNote.isNotBlank()) {
                            Text(
                                text = "Note: $cleanNote",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                        }
                    }
                }

                // Itemized Table
                Text(
                    text = LanguageManager.getString("Purchased Items:", "ক্রয়কৃত আইটেম তালিকা:"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, TextMuted.copy(alpha = 0.15f))
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        // Header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(LanguageManager.getString("Item", "পণ্য"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(2f))
                            Text(LanguageManager.getString("Qty", "পরিমাণ"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                            Text(LanguageManager.getString("Rate", "দর"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                            Text(LanguageManager.getString("Total", "মোট"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1.2f), textAlign = TextAlign.End)
                        }
                        HorizontalDivider(color = TextMuted.copy(alpha = 0.2f))
                        Spacer(modifier = Modifier.height(6.dp))

                        items.forEach { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isBn && item.productNameBn.isNotBlank()) item.productNameBn else item.productNameEn,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(2f)
                                )
                                Text(
                                    text = "%.1f".format(item.quantity),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = "₹%.2f".format(item.costPrice),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                    textAlign = TextAlign.End
                                )
                                Text(
                                    text = "₹%.2f".format(item.subtotal),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = StoreRedPrimary,
                                    modifier = Modifier.weight(1.2f),
                                    textAlign = TextAlign.End
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(color = TextMuted.copy(alpha = 0.2f))
                        Spacer(modifier = Modifier.height(8.dp))

                        val itemsSubtotal = items.sumOf { it.subtotal }
                        val extraCharges = (p.totalAmount - itemsSubtotal).coerceAtLeast(0.0)
                        if (extraCharges > 0.009) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = LanguageManager.getString("Goods / Items Subtotal:", "পণ্য উপমোট:"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted
                                )
                                Text(
                                    text = "₹%.2f".format(itemsSubtotal),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = LanguageManager.getString("Delivery / Other Charges:", "ডেলিভারি / অন্যান্য খরচ:"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted
                                )
                                Text(
                                    text = "₹%.2f".format(extraCharges),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = StorePrimary
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }

                        // Purchase Total
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = LanguageManager.getString("Purchase Total:", "চালানের মোট মূল্য:"),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "₹%.2f".format(p.totalAmount),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = StoreRedPrimary
                            )
                        }

                        // Amount Paid
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = LanguageManager.getString("Amount Paid (${p.paidVia}):", "পরিশোধ করা হয়েছে (${p.paidVia}):"),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                            Text(
                                text = "₹%.2f".format(p.amountPaid),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = StoreGreenProfit
                            )
                        }

                        // Previous Due
                        if (p.previousBalance > 0 || !p.supplierId.isNullOrBlank()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = LanguageManager.getString("Previous Due:", "পূর্বের বকেয়া দেনা:"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted
                                )
                                Text(
                                    text = "₹%.2f".format(p.previousBalance),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (p.previousBalance > 0) StoreRedPrimary else TextMuted
                                )
                            }
                        }

                        val newTotalDue = (p.previousBalance + p.totalAmount - p.amountPaid).coerceAtLeast(0.0)
                        if (p.previousBalance > 0 || newTotalDue > 0 || !p.supplierId.isNullOrBlank()) {
                            HorizontalDivider(color = TextMuted.copy(alpha = 0.15f))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = LanguageManager.getString("New Total Due:", "বর্তমান মোট বাকি দেনা:"),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "₹%.2f".format(newTotalDue),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (newTotalDue > 0) StoreRedPrimary else StoreGreenProfit
                                )
                            }
                        }
                    }
                }

                // Bill Photo Preview
                if (photoUri != null) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showEnlargedPhoto = true },
                        colors = CardDefaults.cardColors(containerColor = SurfaceWarm),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Image, contentDescription = null, tint = StorePrimary, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = LanguageManager.getString("View Original Bill Photo 📷", "আসল চালান/বিলের ছবি দেখুন 📷"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = StorePrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextMuted)
                        }
                    }
                }

                // Share / WhatsApp Button
                OutlinedButton(
                    onClick = {
                        val newTotalDue = (p.previousBalance + p.totalAmount - p.amountPaid).coerceAtLeast(0.0)
                        val shareText = buildString {
                            appendLine("📦 *${StoreInfoManager.storeName.ifBlank { "Amar Dukan" }} - Purchase Bill Voucher*")
                            appendLine("Bill No: #${p.id.takeLast(6)}")
                            appendLine("Date: ${dateFormat.format(Date(p.datetime))}")
                            appendLine("Supplier: ${p.supplierName ?: "Direct Purchase"}")
                            appendLine("Paid Via: ${p.paidVia}")
                            appendLine("-------------------------")
                            items.forEach { item ->
                                appendLine("• ${item.productNameEn}: %.1f @ ₹%.2f = ₹%.2f".format(item.quantity, item.costPrice, item.subtotal))
                            }
                            appendLine("-------------------------")
                            appendLine("📦 *Purchase Total: ₹%.2f*".format(p.totalAmount))
                            appendLine("💵 *Amount Paid: ₹%.2f* (${p.paidVia})".format(p.amountPaid))
                            if (p.previousBalance > 0) {
                                appendLine("📋 Previous Due: ₹%.2f".format(p.previousBalance))
                            }
                            appendLine("⚖️ *New Total Due: ₹%.2f*".format(newTotalDue))
                        }
                        val sendIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, shareText)
                            type = "text/plain"
                        }
                        val shareIntent = Intent.createChooser(sendIntent, "Share Purchase Bill")
                        context.startActivity(shareIntent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(LanguageManager.getString("Share Bill Summary / Voucher", "চালানের বিবরণ শেয়ার করুন"))
                }
            }
        },
        confirmButton = {
            if (onDelete != null && com.example.utils.StaffManager.canDeleteSales()) {
                TextButton(
                    onClick = { showDeleteConfirmDialog = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = StoreRedAlert)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(LanguageManager.getString("Delete Bill", "চালান মুছুন"))
                }
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(LanguageManager.getString("Close", "বন্ধ করুন"))
            }
        }
    )

    // Delete Confirmation Dialog
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text(LanguageManager.getString("Delete Purchase Bill?", "এই চালানটি মুছে ফেলতে চান?"), fontWeight = FontWeight.Bold) },
            text = {
                Text(LanguageManager.getString(
                    "Are you sure you want to delete this purchase bill (#${p.id.takeLast(6)})? This action cannot be undone.",
                    "আপনি কি নিশ্চিত যে এই চালানটি (#${p.id.takeLast(6)}) মুছে ফেলবেন? এটি অপরিবর্তনীয়।"
                ))
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        onDelete?.invoke()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StoreRedAlert)
                ) {
                    Text(LanguageManager.getString("Delete", "মুছে ফেলুন"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text(LanguageManager.getString("Cancel", "বাতিল"))
                }
            }
        )
    }

    // Full photo modal
    if (showEnlargedPhoto && photoUri != null) {
        AlertDialog(
            onDismissRequest = { showEnlargedPhoto = false },
            title = { Text(LanguageManager.getString("Original Bill Photo", "আসল বিলের ছবি"), fontWeight = FontWeight.Bold) },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = com.example.utils.ImageSyncHelper.getImageModel(photoUri),
                        contentDescription = "Full Invoice Photo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showEnlargedPhoto = false }) {
                    Text(LanguageManager.getString("Close", "বন্ধ করুন"))
                }
            }
        )
    }
}
