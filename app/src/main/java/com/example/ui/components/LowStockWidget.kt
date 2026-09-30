package com.example.ui.components

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.data.local.entities.Product
import com.example.data.local.entities.Supplier
import com.example.ui.screens.inventory.RestockDialog
import com.example.ui.theme.*
import com.example.utils.ImageSyncHelper
import com.example.utils.LanguageManager
import com.example.utils.WhatsAppHelper
import com.example.viewmodel.StoreViewModel
import java.util.Locale

/**
 * Enhanced Dashboard Widget Card showing low stock alerts and quick reordering access.
 */
@Composable
fun LowStockDashboardCardWidget(
    viewModel: StoreViewModel,
    modifier: Modifier = Modifier,
    onOpenReorderDialog: (() -> Unit)? = null
) {
    val lowStockProducts by viewModel.lowStockProducts.collectAsState()
    var showReorderModal by remember { mutableStateOf(false) }

    if (lowStockProducts.isEmpty()) {
        return
    }

    val isBn = LanguageManager.isBengali
    val outOfStockCount = remember(lowStockProducts) {
        lowStockProducts.count { it.currentStock <= 0 }
    }
    val totalEstReorderCost = remember(lowStockProducts) {
        lowStockProducts.sumOf { p ->
            val qtyToOrder = (p.lowStockThreshold * 2.0 - p.currentStock).coerceAtLeast(p.lowStockThreshold)
            if (p.unitType.equals("gram", ignoreCase = true)) (qtyToOrder / 1000.0) * p.costPrice else qtyToOrder * p.costPrice
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable {
                if (onOpenReorderDialog != null) onOpenReorderDialog() else showReorderModal = true
            },
        colors = CardDefaults.cardColors(
            containerColor = if (outOfStockCount > 0) StoreRedAlert.copy(alpha = 0.09f) else StoreOrangeWarning.copy(alpha = 0.08f)
        ),
        border = BorderStroke(
            1.dp,
            if (outOfStockCount > 0) StoreRedAlert.copy(alpha = 0.35f) else StoreOrangeWarning.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (outOfStockCount > 0) StoreRedAlert.copy(alpha = 0.2f) else StoreOrangeWarning.copy(alpha = 0.2f)
                ) {
                    Icon(
                        imageVector = if (outOfStockCount > 0) Icons.Default.Warning else Icons.Default.WarningAmber,
                        contentDescription = "Low Stock Alert",
                        tint = if (outOfStockCount > 0) StoreRedAlert else StoreOrangeWarning,
                        modifier = Modifier
                            .padding(6.dp)
                            .size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (isBn) {
                                "${lowStockProducts.size}টি পণ্যে স্টক ঘাটতি"
                            } else {
                                "${lowStockProducts.size} Items Need Restocking"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (outOfStockCount > 0) StoreRedAlert else StoreOrangeWarning
                        )
                        if (outOfStockCount > 0) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = StoreRedAlert
                            ) {
                                Text(
                                    text = if (isBn) "$outOfStockCount শেষ" else "$outOfStockCount OUT",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    color = Color.White,
                                    fontWeight = FontWeight.ExtraBold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                    if (totalEstReorderCost > 0) {
                        Text(
                            text = if (isBn) {
                                "আনুমানিক খরচ: ₹%.2f".format(totalEstReorderCost)
                            } else {
                                "Est. Reorder Cost: ₹%.2f".format(totalEstReorderCost)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                    }
                    val previewNames = lowStockProducts.take(2).joinToString(", ") { p ->
                        "${p.getDisplayName(isBn)} (${if (p.currentStock % 1.0 == 0.0) "%.0f".format(p.currentStock) else "%.1f".format(p.currentStock)} ${p.unitType})"
                    } + if (lowStockProducts.size > 2) " +${lowStockProducts.size - 2} more" else ""
                    Text(
                        text = previewNames,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextDark.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (outOfStockCount > 0) StoreRedAlert else StorePrimary,
                onClick = {
                    if (onOpenReorderDialog != null) onOpenReorderDialog() else showReorderModal = true
                }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isBn) "রিঅর্ডার" else "Reorder Hub",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }

    if (showReorderModal) {
        LowStockReorderDialog(
            viewModel = viewModel,
            onDismiss = { showReorderModal = false }
        )
    }
}

/**
 * Filter and sort options for the Low Stock Reorder Hub.
 */
enum class LowStockSortOption {
    URGENCY,
    COST_DESC,
    STOCK_ASC,
    NAME_ASC
}

enum class LowStockFilterStatus {
    ALL,
    OUT_OF_STOCK,
    LOW_STOCK
}

/**
 * Production-Grade Interactive Low Stock & Purchase Order Management Hub.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LowStockReorderDialog(
    viewModel: StoreViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val isBn = LanguageManager.isBengali

    val lowStockProducts by viewModel.lowStockProducts.collectAsState()
    val suppliers by viewModel.allSuppliers.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf(LowStockFilterStatus.ALL) }
    var selectedCategory by remember { mutableStateOf("ALL") }
    var selectedSupplierId by remember { mutableStateOf<String?>(null) }
    var sortOption by remember { mutableStateOf(LowStockSortOption.URGENCY) }

    // Multi-select state for PO & bulk restock
    val selectedProductIds = remember { mutableStateMapOf<String, Boolean>() }
    // Interactive stepper custom order quantities (defaults to suggested qty)
    val customOrderQuantities = remember { mutableStateMapOf<String, Double>() }

    // Dialog state targets
    var restockTargetProduct by remember { mutableStateOf<Product?>(null) }
    var showPoPreview by remember { mutableStateOf(false) }
    var showBulkRestockConfirm by remember { mutableStateOf(false) }

    // Sync product selections and default quantities when lowStockProducts changes
    LaunchedEffect(lowStockProducts) {
        lowStockProducts.forEach { p ->
            if (!selectedProductIds.containsKey(p.id)) {
                selectedProductIds[p.id] = true
            }
            if (!customOrderQuantities.containsKey(p.id)) {
                val suggested = (p.lowStockThreshold * 2.0 - p.currentStock).coerceAtLeast(p.lowStockThreshold)
                customOrderQuantities[p.id] = suggested
            }
        }
    }

    val categories = remember(lowStockProducts) {
        listOf("ALL") + lowStockProducts.map { it.category }.distinct().filter { it.isNotBlank() }
    }

    val outOfStockCount = remember(lowStockProducts) {
        lowStockProducts.count { it.currentStock <= 0 }
    }

    val lowStockWarningCount = remember(lowStockProducts) {
        lowStockProducts.count { it.currentStock > 0 }
    }

    // Filter products
    val filteredProducts = remember(
        lowStockProducts,
        searchQuery,
        statusFilter,
        selectedCategory,
        selectedSupplierId,
        sortOption
    ) {
        lowStockProducts.filter { p ->
            val matchQuery = searchQuery.isBlank() ||
                    p.nameEn.contains(searchQuery, ignoreCase = true) ||
                    p.nameBn.contains(searchQuery, ignoreCase = true) ||
                    (!p.barcode.isNullOrBlank() && p.barcode.contains(searchQuery, ignoreCase = true))

            val matchStatus = when (statusFilter) {
                LowStockFilterStatus.ALL -> true
                LowStockFilterStatus.OUT_OF_STOCK -> p.currentStock <= 0
                LowStockFilterStatus.LOW_STOCK -> p.currentStock > 0
            }

            val matchCat = selectedCategory == "ALL" || p.category.equals(selectedCategory, ignoreCase = true)

            matchQuery && matchStatus && matchCat
        }.sortedWith { a, b ->
            when (sortOption) {
                LowStockSortOption.URGENCY -> {
                    // Out of stock (<=0) comes first, then lowest stock ratio
                    val aOut = a.currentStock <= 0
                    val bOut = b.currentStock <= 0
                    if (aOut != bOut) {
                        if (aOut) -1 else 1
                    } else {
                        a.currentStock.compareTo(b.currentStock)
                    }
                }
                LowStockSortOption.COST_DESC -> {
                    val costA = a.costPrice * (customOrderQuantities[a.id] ?: a.lowStockThreshold)
                    val costB = b.costPrice * (customOrderQuantities[b.id] ?: b.lowStockThreshold)
                    costB.compareTo(costA)
                }
                LowStockSortOption.STOCK_ASC -> a.currentStock.compareTo(b.currentStock)
                LowStockSortOption.NAME_ASC -> a.getDisplayName(isBn).compareTo(b.getDisplayName(isBn), ignoreCase = true)
            }
        }
    }

    // Selected products for PO / Bulk operations
    val selectedProducts = remember(filteredProducts, selectedProductIds) {
        filteredProducts.filter { selectedProductIds[it.id] == true }
    }

    // Calculations based on live custom order quantities
    val totalEstCostAllFiltered = remember(filteredProducts, customOrderQuantities) {
        filteredProducts.sumOf { p ->
            val qty = customOrderQuantities[p.id] ?: ((p.lowStockThreshold * 2.0 - p.currentStock).coerceAtLeast(p.lowStockThreshold))
            if (p.unitType.equals("gram", ignoreCase = true)) (qty / 1000.0) * p.costPrice else qty * p.costPrice
        }
    }

    val totalEstCostSelected = remember(selectedProducts, customOrderQuantities) {
        selectedProducts.sumOf { p ->
            val qty = customOrderQuantities[p.id] ?: ((p.lowStockThreshold * 2.0 - p.currentStock).coerceAtLeast(p.lowStockThreshold))
            if (p.unitType.equals("gram", ignoreCase = true)) (qty / 1000.0) * p.costPrice else qty * p.costPrice
        }
    }

    val selectedSupplier = remember(selectedSupplierId, suppliers) {
        suppliers.find { it.id == selectedSupplierId }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.94f)
                .padding(vertical = 8.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp)
            ) {
                // Header Bar
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
                            color = StoreRedAlert.copy(alpha = 0.15f)
                        ) {
                            Icon(
                                Icons.Default.Inventory2,
                                contentDescription = null,
                                tint = StoreRedAlert,
                                modifier = Modifier
                                    .padding(8.dp)
                                    .size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = if (isBn) "স্টক ঘাটতি ও রিঅর্ডার হাব" else "Low Stock & Reorder Hub",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (isBn) {
                                        "${filteredProducts.size}টি পণ্য তালিকাভুক্ত"
                                    } else {
                                        "${filteredProducts.size} items listed"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted
                                )
                                if (outOfStockCount > 0) {
                                    Text(
                                        text = " • ",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextMuted
                                    )
                                    Text(
                                        text = if (isBn) "${outOfStockCount}টি শেষ" else "$outOfStockCount out of stock",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = StoreRedAlert
                                    )
                                }
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Reset all quantities button
                        IconButton(
                            onClick = {
                                lowStockProducts.forEach { p ->
                                    val suggested = (p.lowStockThreshold * 2.0 - p.currentStock).coerceAtLeast(p.lowStockThreshold)
                                    customOrderQuantities[p.id] = suggested
                                }
                                Toast.makeText(
                                    context,
                                    if (isBn) "অর্ডার পরিমাণ পুনরায় সেট করা হয়েছে" else "Order quantities reset to defaults",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        ) {
                            Icon(
                                Icons.Default.RestartAlt,
                                contentDescription = "Reset Quantities",
                                tint = TextMuted
                            )
                        }

                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Hero Analytics & Smart PO Actions Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = StoreRedAlert.copy(alpha = 0.07f)),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, StoreRedAlert.copy(alpha = 0.25f))
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
                            Column {
                                Text(
                                    text = if (selectedProducts.size == filteredProducts.size) {
                                        if (isBn) "মোট আনুমানিক রিঅর্ডার খরচ" else "Total Estimated Reorder Cost"
                                    } else {
                                        if (isBn) "নির্বাচিত ${selectedProducts.size}টি পণ্যের খরচ" else "Selected (${selectedProducts.size} items) Cost"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted
                                )
                                Text(
                                    text = "₹%.2f".format(totalEstCostSelected),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = StoreRedAlert
                                )
                                if (selectedProducts.size < filteredProducts.size) {
                                    Text(
                                        text = if (isBn) "সবগুলোর মোট: ₹%.2f".format(totalEstCostAllFiltered) else "All Items: ₹%.2f".format(totalEstCostAllFiltered),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMuted,
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            // Primary PO Share & Export Buttons
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Copy PO Button
                                OutlinedButton(
                                    onClick = {
                                        if (selectedProducts.isEmpty()) {
                                            Toast.makeText(context, if (isBn) "কোনো পণ্য নির্বাচিত নেই" else "No items selected", Toast.LENGTH_SHORT).show()
                                        } else {
                                            val poText = WhatsAppHelper.generatePurchaseOrder(
                                                products = selectedProducts,
                                                supplierName = selectedSupplier?.name,
                                                isBengali = isBn,
                                                customQuantities = customOrderQuantities
                                            )
                                            clipboardManager.setText(AnnotatedString(poText))
                                            Toast.makeText(
                                                context,
                                                if (isBn) "পারচেজ অর্ডার ক্লিপবোর্ডে কপি করা হয়েছে!" else "Purchase Order copied to clipboard!",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (isBn) "কপি" else "Copy PO",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }

                                // WhatsApp PO Share Button
                                Button(
                                    onClick = {
                                        if (selectedProducts.isEmpty()) {
                                            Toast.makeText(context, if (isBn) "কোনো পণ্য নির্বাচিত নেই" else "No items selected", Toast.LENGTH_SHORT).show()
                                        } else {
                                            val poText = WhatsAppHelper.generatePurchaseOrder(
                                                products = selectedProducts,
                                                supplierName = selectedSupplier?.name,
                                                isBengali = isBn,
                                                customQuantities = customOrderQuantities
                                            )
                                            WhatsAppHelper.sendWhatsAppMessage(
                                                context = context,
                                                phone = selectedSupplier?.phone,
                                                message = poText
                                            )
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = StoreGreenProfit),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Share,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (isBn) "হোয়াটসঅ্যাপ" else "WhatsApp PO",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Selection summary & Toggle All Checkbox
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val allSelected = filteredProducts.isNotEmpty() && filteredProducts.all { selectedProductIds[it.id] == true }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable {
                                    val nextState = !allSelected
                                    filteredProducts.forEach { p ->
                                        selectedProductIds[p.id] = nextState
                                    }
                                }
                            ) {
                                Checkbox(
                                    checked = allSelected,
                                    onCheckedChange = { isChecked ->
                                        filteredProducts.forEach { p ->
                                            selectedProductIds[p.id] = isChecked
                                        }
                                    },
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (allSelected) {
                                        if (isBn) "সব নির্বাচন বাতিল" else "Deselect All"
                                    } else {
                                        if (isBn) "সব নির্বাচন করুন (${filteredProducts.size})" else "Select All (${filteredProducts.size})"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            // Preview PO text dialog trigger
                            TextButton(
                                onClick = { showPoPreview = true },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isBn) "প্রিভিউ দেখুন" else "Preview Format",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Search Bar & Filter Options
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            if (isBn) "নাম, বারকোড বা ক্যাটাগরি দিয়ে খুঁজুন..." else "Search by name, barcode or category...",
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted)
                    },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Status Filter Chips (All / Out of Stock / Low Stock)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item {
                        FilterChip(
                            selected = statusFilter == LowStockFilterStatus.ALL,
                            onClick = { statusFilter = LowStockFilterStatus.ALL },
                            label = {
                                Text(
                                    if (isBn) "সব (${lowStockProducts.size})" else "All (${lowStockProducts.size})"
                                )
                            },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                    if (outOfStockCount > 0) {
                        item {
                            FilterChip(
                                selected = statusFilter == LowStockFilterStatus.OUT_OF_STOCK,
                                onClick = { statusFilter = LowStockFilterStatus.OUT_OF_STOCK },
                                label = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .background(StoreRedAlert, CircleShape)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            if (isBn) "স্টক শেষ ($outOfStockCount)" else "Out of Stock ($outOfStockCount)",
                                            color = if (statusFilter == LowStockFilterStatus.OUT_OF_STOCK) StoreRedAlert else Color.Unspecified
                                        )
                                    }
                                },
                                shape = RoundedCornerShape(8.dp)
                            )
                        }
                    }
                    if (lowStockWarningCount > 0) {
                        item {
                            FilterChip(
                                selected = statusFilter == LowStockFilterStatus.LOW_STOCK,
                                onClick = { statusFilter = LowStockFilterStatus.LOW_STOCK },
                                label = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .background(StoreOrangeWarning, CircleShape)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            if (isBn) "কম স্টক ($lowStockWarningCount)" else "Low Stock ($lowStockWarningCount)",
                                            color = if (statusFilter == LowStockFilterStatus.LOW_STOCK) StoreOrangeWarning else Color.Unspecified
                                        )
                                    }
                                },
                                shape = RoundedCornerShape(8.dp)
                            )
                        }
                    }

                    // Category filter chips
                    if (categories.size > 2) {
                        items(categories.filter { it != "ALL" }, key = { it }) { cat ->
                            FilterChip(
                                selected = selectedCategory == cat,
                                onClick = {
                                    selectedCategory = if (selectedCategory == cat) "ALL" else cat
                                },
                                label = { Text(cat) },
                                shape = RoundedCornerShape(8.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Sort & Selection Info Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isBn) {
                            "${filteredProducts.size}টির মধ্যে ${selectedProducts.size}টি নির্বাচিত"
                        } else {
                            "${selectedProducts.size} of ${filteredProducts.size} selected"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )

                    // Quick Sort dropdown/toggle
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (isBn) "সাজান: " else "Sort: ",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                        Text(
                            text = when (sortOption) {
                                LowStockSortOption.URGENCY -> if (isBn) "জরুরি প্রথম" else "Urgency"
                                LowStockSortOption.COST_DESC -> if (isBn) "খরচ (বেশি-কম)" else "Highest Cost"
                                LowStockSortOption.STOCK_ASC -> if (isBn) "স্টক (কম-বেশি)" else "Lowest Stock"
                                LowStockSortOption.NAME_ASC -> if (isBn) "নাম (A-Z)" else "Name (A-Z)"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = StorePrimary,
                            modifier = Modifier
                                .clickable {
                                    sortOption = when (sortOption) {
                                        LowStockSortOption.URGENCY -> LowStockSortOption.COST_DESC
                                        LowStockSortOption.COST_DESC -> LowStockSortOption.STOCK_ASC
                                        LowStockSortOption.STOCK_ASC -> LowStockSortOption.NAME_ASC
                                        LowStockSortOption.NAME_ASC -> LowStockSortOption.URGENCY
                                    }
                                }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Products List
                if (filteredProducts.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = StoreGreenProfit.copy(alpha = 0.12f),
                                modifier = Modifier.size(64.dp)
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = StoreGreenProfit,
                                    modifier = Modifier
                                        .padding(14.dp)
                                        .size(36.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = if (isBn) "কোনো ঘাটতি পণ্য পাওয়া যায়নি!" else "All Stock Levels Healthy!",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isBn) {
                                    "আপনার স্টোরের পণ্যগুলোর বর্তমান স্টক ঘাটতি সীমার ওপরে আছে।"
                                } else {
                                    "No items match your active filters or require immediate restocking."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(filteredProducts, key = { it.id }) { product ->
                            val isChecked = selectedProductIds[product.id] ?: true
                            val orderQty = customOrderQuantities[product.id] ?: ((product.lowStockThreshold * 2.0 - product.currentStock).coerceAtLeast(product.lowStockThreshold))

                            LowStockItemReorderCard(
                                product = product,
                                isBn = isBn,
                                isSelected = isChecked,
                                orderQty = orderQty,
                                onToggleSelected = { checked ->
                                    selectedProductIds[product.id] = checked
                                },
                                onQtyChange = { newQty ->
                                    customOrderQuantities[product.id] = newQty
                                },
                                onQuickStockIn = {
                                    viewModel.recordStockIn(
                                        product = product,
                                        addedQty = orderQty,
                                        supplier = selectedSupplier,
                                        newCostPrice = product.costPrice,
                                        newSellingPrice = product.sellingPrice,
                                        isCredit = false,
                                        batchNumber = null,
                                        expiryDate = product.expiryDate
                                    )
                                    Toast.makeText(
                                        context,
                                        if (isBn) {
                                            "${product.getDisplayName(isBn)} রিস্টক করা হয়েছে (+${orderQty.let { if (it % 1.0 == 0.0) "%.0f".format(it) else "%.1f".format(it) }} ${product.unitType})"
                                        } else {
                                            "${product.getDisplayName(false)} restocked (+${orderQty.let { if (it % 1.0 == 0.0) "%.0f".format(it) else "%.1f".format(it) }} ${product.unitType})"
                                        },
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                onDetailedRestockClick = {
                                    restockTargetProduct = product
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Sticky Bottom Action Bar with Bulk Restock & Close
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (selectedProducts.isNotEmpty()) {
                        Button(
                            onClick = { showBulkRestockConfirm = true },
                            modifier = Modifier.weight(1.3f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = StorePrimary)
                        ) {
                            Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isBn) "নির্বাচিত ${selectedProducts.size}টি দ্রুত রিস্টক" else "Bulk Restock (${selectedProducts.size})",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(if (selectedProducts.isNotEmpty()) 0.7f else 1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = if (isBn) "বন্ধ করুন" else "Close",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }

    // Triggered Detailed Restock Dialog
    if (restockTargetProduct != null) {
        val prod = restockTargetProduct!!
        RestockDialog(
            product = prod,
            suppliers = suppliers,
            isBn = isBn,
            onDismiss = { restockTargetProduct = null },
            onConfirm = { addedQty, supplier, cost, sell, isCredit, batchNumber, expiryDate ->
                viewModel.recordStockIn(prod, addedQty, supplier, cost, sell, isCredit, batchNumber, expiryDate)
                restockTargetProduct = null
                Toast.makeText(
                    context,
                    if (isBn) "পণ্য সফলভাবে রিস্টক হয়েছে" else "Product restocked successfully",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }

    // Bulk Restock Confirmation Dialog
    if (showBulkRestockConfirm && selectedProducts.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showBulkRestockConfirm = false },
            icon = {
                Icon(Icons.Default.Bolt, contentDescription = null, tint = StorePrimary, modifier = Modifier.size(32.dp))
            },
            title = {
                Text(
                    text = if (isBn) "একসাথে ${selectedProducts.size}টি পণ্য রিস্টক করবেন?" else "Bulk Restock ${selectedProducts.size} Items?",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = if (isBn) {
                            "নির্বাচিত সকল পণ্যের স্টক তাদের নির্দিষ্ট পরিমাণ অনুযায়ী বর্তমান ক্রয়মূল্যে বাড়িয়ে দেওয়া হবে।"
                        } else {
                            "All selected items will have their stock updated by their chosen order quantities at their current cost price."
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        color = StorePrimary.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = if (isBn) "মোট আনুমানিক ইনভয়েস মূল্য:" else "Total Estimated Stock Value:",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                            Text(
                                text = "₹%.2f".format(totalEstCostSelected),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = StorePrimary
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val itemsToRestock = selectedProducts.map { p ->
                            val qty = customOrderQuantities[p.id] ?: ((p.lowStockThreshold * 2.0 - p.currentStock).coerceAtLeast(p.lowStockThreshold))
                            p to qty
                        }
                        viewModel.bulkRecordStockIn(itemsToRestock, supplier = selectedSupplier)
                        showBulkRestockConfirm = false
                        Toast.makeText(
                            context,
                            if (isBn) "${itemsToRestock.size}টি পণ্য সফলভাবে রিস্টক করা হয়েছে!" else "Successfully bulk restocked ${itemsToRestock.size} items!",
                            Toast.LENGTH_LONG
                        ).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StorePrimary)
                ) {
                    Text(if (isBn) "হ্যাঁ, রিস্টক করুন" else "Confirm Restock", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBulkRestockConfirm = false }) {
                    Text(if (isBn) "বাতিল" else "Cancel")
                }
            }
        )
    }

    // Purchase Order Preview Dialog
    if (showPoPreview) {
        val poText = remember(selectedProducts, selectedSupplier, customOrderQuantities, isBn) {
            WhatsAppHelper.generatePurchaseOrder(
                products = if (selectedProducts.isNotEmpty()) selectedProducts else filteredProducts,
                supplierName = selectedSupplier?.name,
                isBengali = isBn,
                customQuantities = customOrderQuantities
            )
        }

        AlertDialog(
            onDismissRequest = { showPoPreview = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isBn) "পারচেজ অর্ডার প্রিভিউ" else "Purchase Order Preview",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = { showPoPreview = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
            },
            text = {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = CardBackground,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 350.dp)
                ) {
                    LazyColumn(modifier = Modifier.padding(12.dp)) {
                        item {
                            Text(
                                text = poText,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        WhatsAppHelper.sendWhatsAppMessage(
                            context = context,
                            phone = selectedSupplier?.phone,
                            message = poText
                        )
                        showPoPreview = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StoreGreenProfit)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isBn) "হোয়াটসঅ্যাপে পাঠান" else "Share via WhatsApp")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(poText))
                        Toast.makeText(
                            context,
                            if (isBn) "ক্লিপবোর্ডে কপি করা হয়েছে!" else "Copied to clipboard!",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isBn) "কপি করুন" else "Copy Text")
                }
            }
        )
    }
}

/**
 * Production-Grade Interactive Product Reorder Card.
 */
@Composable
fun LowStockItemReorderCard(
    product: Product,
    isBn: Boolean,
    isSelected: Boolean,
    orderQty: Double,
    onToggleSelected: (Boolean) -> Unit,
    onQtyChange: (Double) -> Unit,
    onQuickStockIn: () -> Unit,
    onDetailedRestockClick: () -> Unit
) {
    var previewPhoto by remember { mutableStateOf(false) }
    val isOut = product.currentStock <= 0
    val stockRatio = remember(product) {
        if (product.lowStockThreshold > 0) {
            (product.currentStock / product.lowStockThreshold).coerceIn(0.0, 1.0).toFloat()
        } else 0f
    }

    val itemTotalReorderCost = remember(product, orderQty) {
        if (product.unitType.equals("gram", ignoreCase = true)) {
            (orderQty / 1000.0) * product.costPrice
        } else {
            orderQty * product.costPrice
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) CardBackground else CardBackground.copy(alpha = 0.6f)
        ),
        border = BorderStroke(
            if (isSelected) 1.5.dp else 1.dp,
            if (isOut) {
                StoreRedAlert.copy(alpha = if (isSelected) 0.6f else 0.3f)
            } else {
                StoreOrangeWarning.copy(alpha = if (isSelected) 0.5f else 0.25f)
            }
        ),
        elevation = CardDefaults.cardElevation(if (isSelected) 3.dp else 1.dp),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Top Row: Checkbox, Photo, Title, and Urgency Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                // Multi-select checkbox
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = onToggleSelected,
                    modifier = Modifier.size(24.dp)
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Product Photo / Fallback Icon
                val hasPhoto = !product.imageUri.isNullOrBlank()
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceWarm)
                        .then(
                            if (hasPhoto) Modifier.clickable { previewPhoto = true }
                            else Modifier
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (hasPhoto) {
                        AsyncImage(
                            model = ImageSyncHelper.getImageModel(product.imageUri),
                            contentDescription = product.getDisplayName(isBn),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            Icons.Default.Fastfood,
                            contentDescription = null,
                            tint = if (isOut) StoreRedAlert.copy(alpha = 0.6f) else StoreOrangeWarning.copy(alpha = 0.6f),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                // Product Names & Category
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = product.getDisplayName(isBn),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextDark,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${product.category.ifBlank { "General" }} • Unit: ${product.unitType}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Urgency Status Badge
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isOut) StoreRedAlert else StoreOrangeWarning
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isOut) Icons.Default.ReportProblem else Icons.Default.AccessTime,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(11.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = if (isOut) {
                                if (isBn) "স্টক শেষ" else "OUT OF STOCK"
                            } else {
                                if (isBn) "কম স্টক" else "LOW STOCK"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Stock Health Progress Meter
            Column(modifier = Modifier.fillMaxWidth()) {
                LinearProgressIndicator(
                    progress = { stockRatio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = if (isOut) StoreRedAlert else StoreOrangeWarning,
                    trackColor = SurfaceWarm
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Stock & Cost Metrics Grid
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = SurfaceWarm.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Current Stock & Threshold
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (isBn) "বর্তমান স্টক: " else "Current: ",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                            Text(
                                text = "%.1f %s".format(product.currentStock, product.unitType),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isOut) StoreRedAlert else StoreOrangeWarning
                            )
                        }
                        Text(
                            text = if (isBn) "ঘাটতি সীমা: %.1f %s".format(product.lowStockThreshold, product.unitType) else "Threshold: %.1f %s".format(product.lowStockThreshold, product.unitType),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }

                    // Unit Cost & Selling Price
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = if (isBn) "ক্রয়: ₹%.2f / %s".format(product.costPrice, product.unitType) else "Cost: ₹%.2f / %s".format(product.costPrice, product.unitType),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = TextDark
                        )
                        if (product.sellingPrice > 0) {
                            Text(
                                text = if (isBn) "বিক্রয়: ₹%.2f".format(product.sellingPrice) else "Sell: ₹%.2f".format(product.sellingPrice),
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Interactive Order Quantity Stepper & Quick Increment Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (isBn) "অর্ডার পরিমাণ:" else "Order Quantity:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = StorePrimary
                    )
                    Text(
                        text = "Est: ₹%.2f".format(itemTotalReorderCost),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }

                // Stepper Buttons [-] [ Qty ] [+]
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Decrement Button
                    IconButton(
                        onClick = {
                            val step = if (product.unitType.equals("gram", ignoreCase = true)) 100.0 else 1.0
                            val newQty = (orderQty - step).coerceAtLeast(step)
                            onQtyChange(newQty)
                        },
                        modifier = Modifier
                            .size(32.dp)
                            .background(SurfaceWarm, RoundedCornerShape(6.dp))
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "Decrease", modifier = Modifier.size(16.dp))
                    }

                    // Display Quantity Field
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.padding(horizontal = 2.dp)
                    ) {
                        Text(
                            text = "${if (orderQty % 1.0 == 0.0) "%.0f".format(orderQty) else "%.1f".format(orderQty)} ${product.unitType}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = StorePrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                        )
                    }

                    // Increment Button
                    IconButton(
                        onClick = {
                            val step = if (product.unitType.equals("gram", ignoreCase = true)) 100.0 else 1.0
                            onQtyChange(orderQty + step)
                        },
                        modifier = Modifier
                            .size(32.dp)
                            .background(SurfaceWarm, RoundedCornerShape(6.dp))
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Increase", modifier = Modifier.size(16.dp))
                    }
                }
            }

            // Quick add increment shortcuts (+5, +10, +Box)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // +5 chip
                SuggestionChip(
                    onClick = { onQtyChange(orderQty + 5.0) },
                    label = { Text("+5", fontSize = 11.sp) },
                    modifier = Modifier.height(28.dp)
                )
                // +10 chip
                SuggestionChip(
                    onClick = { onQtyChange(orderQty + 10.0) },
                    label = { Text("+10", fontSize = 11.sp) },
                    modifier = Modifier.height(28.dp)
                )
                // +Box chip if piecesPerBox configured
                if (product.piecesPerBox != null && product.piecesPerBox > 0) {
                    SuggestionChip(
                        onClick = { onQtyChange(orderQty + product.piecesPerBox) },
                        label = { Text("+1 Box (${product.piecesPerBox})", fontSize = 11.sp) },
                        modifier = Modifier.height(28.dp)
                    )
                }
                // Reset to suggested
                SuggestionChip(
                    onClick = {
                        val def = (product.lowStockThreshold * 2.0 - product.currentStock).coerceAtLeast(product.lowStockThreshold)
                        onQtyChange(def)
                    },
                    label = { Text(if (isBn) "প্রস্তাবিত" else "Suggested", fontSize = 11.sp) },
                    modifier = Modifier.height(28.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Action Buttons: Quick Stock-IN & Detailed Restock
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Quick Stock-In Button
                Button(
                    onClick = onQuickStockIn,
                    modifier = Modifier.weight(1.2f),
                    colors = ButtonDefaults.buttonColors(containerColor = StorePrimary),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(
                        Icons.Default.AddShoppingCart,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isBn) "দ্রুত স্টক-ইন (+${if (orderQty % 1.0 == 0.0) "%.0f".format(orderQty) else "%.1f".format(orderQty)})" else "Quick Restock (+${if (orderQty % 1.0 == 0.0) "%.0f".format(orderQty) else "%.1f".format(orderQty)})",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Full Custom Restock (with supplier, batch, expiry)
                OutlinedButton(
                    onClick = onDetailedRestockClick,
                    modifier = Modifier.weight(0.8f),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                ) {
                    Icon(
                        Icons.Default.Tune,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isBn) "বিস্তারিত" else "Custom...",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                }
            }
        }
    }

    if (previewPhoto && !product.imageUri.isNullOrBlank()) {
        ZoomablePaymentScreenshotDialog(
            imageModel = ImageSyncHelper.getImageModel(product.imageUri),
            title = product.getDisplayName(isBn),
            subtitle = "₹${"%.2f".format(product.sellingPrice)} • Stock: ${product.currentStock} ${product.unitType}",
            onDismiss = { previewPhoto = false }
        )
    }
}
