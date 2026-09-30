package com.example.ui.screens.suppliers

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.local.dao.PurchaseWithItems
import com.example.data.local.entities.Product
import com.example.data.local.entities.PurchaseItem
import com.example.data.local.entities.Supplier
import com.example.ui.components.PartyProfileAvatar
import com.example.ui.components.EnlargedPhotoDialog
import com.example.ui.components.LedgerDateRangeFilterComponent
import com.example.ui.components.LedgerDatePreset
import com.example.ui.components.getPresetDateBounds
import com.example.ui.screens.credit.RecordPaymentDialog
import com.example.ui.screens.inventory.ProductFormDialog
import com.example.ui.theme.*
import com.example.utils.LanguageManager
import com.example.utils.WhatsAppHelper
import com.example.viewmodel.StoreViewModel
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

fun saveBitmapToCache(context: Context, bitmap: Bitmap): Uri? {
    return try {
        val file = File(context.cacheDir, "bill_photo_${System.currentTimeMillis()}.jpg")
        val out = FileOutputStream(file)
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        out.flush()
        out.close()
        Uri.fromFile(file)
    } catch (e: Exception) {
        null
    }
}

fun extractPhotoUri(note: String?): String? {
    if (note == null) return null
    val regex = Regex("\\[(?:Bill Photo|Photo): (.*?)\\]")
    val match = regex.find(note)
    return match?.groupValues?.get(1)
}

fun cleanNotes(note: String?): String {
    if (note == null) return ""
    return note.replace(Regex("\\[(?:Bill Photo|Photo): .*?\\]"), "").trim()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupplierManagementView(
    viewModel: StoreViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    if (!com.example.utils.StaffManager.canViewKhata()) {
        com.example.ui.components.StaffAccessGate(
            screenTitle = "Supplier Ledger & Khata",
            screenDescription = "Supplier purchase ledgers, payment records, and payables are restricted to authorized personnel."
        )
        return
    }

    val isBn = LanguageManager.isBengali

    val rawSuppliers by viewModel.allSuppliers.collectAsState()
    val allPurchases by viewModel.allPurchases.collectAsState()
    val allProducts by viewModel.allProducts.collectAsState()
    val allLedgerEntries by viewModel.allLedgerEntries.collectAsState()

    val suppliers = remember(rawSuppliers, allLedgerEntries) {
        rawSuppliers.map { s -> com.example.utils.LedgerCalculator.reconcileSupplier(s, allLedgerEntries) }
    }

    var searchQuery by remember { mutableStateOf("") }
    // 300ms debounce for supplier search
    var debouncedSearchQuery by remember { mutableStateOf("") }
    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            debouncedSearchQuery = ""
        } else {
            kotlinx.coroutines.delay(300)
            debouncedSearchQuery = searchQuery
        }
    }

    var selectedFilterChip by remember { mutableIntStateOf(0) } // 0: All, 1: Dues Pending, 2: Settled

    // BackHandler: Clear search query or filter before propagating back to parent
    BackHandler(enabled = searchQuery.isNotBlank() || selectedFilterChip != 0) {
        searchQuery = ""
        selectedFilterChip = 0
    }

    var showAddSupplierDialog by remember { mutableStateOf(false) }
    var editingSupplier by remember { mutableStateOf<Supplier?>(null) }
    var paymentSupplier by remember { mutableStateOf<Supplier?>(null) }
    var newPurchaseSupplier by remember { mutableStateOf<Supplier?>(null) }
    var selectedSupplierForDetails by remember { mutableStateOf<Supplier?>(null) }
    var supplierToDelete by remember { mutableStateOf<Supplier?>(null) }
    var showSelectSupplierToPayDialog by remember { mutableStateOf(false) }

    val totalPayables = remember(suppliers) { suppliers.sumOf { it.balance } }

    val purchasesBySupplier = remember(allPurchases) {
        allPurchases.groupBy { it.purchase.supplierId }
    }

    val filteredSuppliers = remember(suppliers, debouncedSearchQuery, selectedFilterChip) {
        suppliers.filter { supp ->
            val matchQuery = debouncedSearchQuery.isBlank() ||
                    supp.name.contains(debouncedSearchQuery, ignoreCase = true) ||
                    supp.phone.contains(debouncedSearchQuery, ignoreCase = true) ||
                    (supp.gstin != null && supp.gstin.contains(debouncedSearchQuery, ignoreCase = true))

            val matchFilter = when (selectedFilterChip) {
                1 -> supp.balance > 0
                2 -> supp.balance <= 0
                else -> true
            }

            matchQuery && matchFilter
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        // --- 1. Action Header Bar ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = LanguageManager.getString("Supplier Accounts", "মহাজনদের তালিকা"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                Button(
                    onClick = {
                        val pending = suppliers.filter { it.balance > 0 }
                        if (pending.size == 1) {
                            paymentSupplier = pending.first()
                        } else {
                            showSelectSupplierToPayDialog = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StoreGreenProfit),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Payments, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(LanguageManager.getString("Pay Dues", "বকেয়া শোধ করুন"), fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = { showAddSupplierDialog = true },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(LanguageManager.getString("Add Supplier", "নতুন মহাজন"))
                }

                Button(
                    onClick = { newPurchaseSupplier = suppliers.firstOrNull() },
                    colors = ButtonDefaults.buttonColors(containerColor = StoreRedPrimary),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.AddShoppingCart, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(LanguageManager.getString("New Purchase", "মাল খরিদ"))
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // --- 2. Search & Filter Bar ---
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text(LanguageManager.getString("Search supplier by name, phone...", "মহাজন নাম বা ফোন নম্বর দিয়ে খুঁজুন...")) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp), tint = TextMuted) },
            trailingIcon = if (searchQuery.isNotEmpty()) {
                { IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(18.dp)) } }
            } else null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedFilterChip == 0,
                onClick = { selectedFilterChip = 0 },
                label = { Text("All (${suppliers.size})") }
            )
            FilterChip(
                selected = selectedFilterChip == 1,
                onClick = { selectedFilterChip = 1 },
                label = { Text("Pending Dues (${suppliers.count { it.balance > 0 }})") }
            )
            FilterChip(
                selected = selectedFilterChip == 2,
                onClick = { selectedFilterChip = 2 },
                label = { Text("Settled (${suppliers.count { it.balance <= 0 }})") }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // --- 3. Supplier Cards List ---
        if (filteredSuppliers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.LocalShipping, contentDescription = null, modifier = Modifier.size(48.dp), tint = TextMuted)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = LanguageManager.getString("No supplier accounts found", "কোন মহাজন পাওয়া যায়নি"),
                        color = TextMuted,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(filteredSuppliers, key = { it.id }, contentType = { "SUPPLIER_CARD" }) { supplier ->
                    val supplierPurchases = purchasesBySupplier[supplier.id] ?: emptyList()

                    SupplierCardItem(
                        supplier = supplier,
                        purchaseCount = supplierPurchases.size,
                        isBn = isBn,
                        onViewDetails = { selectedSupplierForDetails = supplier },
                        onRecordPayment = { paymentSupplier = supplier },
                        onNewPurchase = { newPurchaseSupplier = supplier },
                        onEdit = { editingSupplier = supplier },
                        onDelete = { supplierToDelete = supplier },
                        onWhatsApp = {
                            val totalVal = supplierPurchases.sumOf { it.purchase.totalAmount }
                            val msg = WhatsAppHelper.generateSupplierStatement(
                                supplierName = supplier.name,
                                phone = supplier.phone,
                                currentBalance = supplier.balance,
                                purchaseCount = supplierPurchases.size,
                                totalPurchasedValuation = totalVal,
                                isBengali = isBn
                            )
                            WhatsAppHelper.sendWhatsAppMessage(context, supplier.phone, msg)
                        }
                    )
                }
            }
        }
    }

    // --- Dialogs & Modals ---

    // Add Supplier Dialog
    if (showAddSupplierDialog) {
        SupplierFormDialog(
            supplier = null,
            isBn = isBn,
            onDismiss = { showAddSupplierDialog = false },
            onSave = { supp ->
                viewModel.saveSupplier(supp, initialDue = supp.balance)
                showAddSupplierDialog = false
            }
        )
    }

    // Edit Supplier Dialog
    if (editingSupplier != null) {
        SupplierFormDialog(
            supplier = editingSupplier,
            isBn = isBn,
            onDismiss = { editingSupplier = null },
            onSave = { supp ->
                viewModel.saveSupplier(supp)
                editingSupplier = null
            }
        )
    }

    // Delete Supplier Confirmation
    if (supplierToDelete != null) {
        val supp = supplierToDelete!!
        AlertDialog(
            onDismissRequest = { supplierToDelete = null },
            title = { Text("Delete Supplier Account?") },
            text = { Text("Are you sure you want to delete ${supp.name}? Transaction records will be retained in ledger history.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteSupplier(supp)
                        supplierToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StoreRedPrimary)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { supplierToDelete = null }) { Text("Cancel") }
            }
        )
    }

    // Select Supplier to Pay Dues Dialog
    if (showSelectSupplierToPayDialog) {
        val suppliersWithDues = remember(suppliers) {
            val pending = suppliers.filter { it.balance > 0 }
            if (pending.isNotEmpty()) pending else suppliers
        }
        AlertDialog(
            onDismissRequest = { showSelectSupplierToPayDialog = false },
            title = {
                Text(
                    text = LanguageManager.getString("Select Supplier to Pay Dues", "মহাজন বকেয়া পরিশোধ করুন"),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                if (suppliersWithDues.isEmpty()) {
                    Text(LanguageManager.getString("No suppliers found", "কোন মহাজন অ্যাকাউন্ট পাওয়া যায়নি"))
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)
                    ) {
                        items(suppliersWithDues, key = { it.id }, contentType = { "SUPPLIER_DUE_CARD" }) { supp ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        paymentSupplier = supp
                                        showSelectSupplierToPayDialog = false
                                    },
                                colors = CardDefaults.cardColors(containerColor = SurfaceWarm),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(supp.name, fontWeight = FontWeight.Bold)
                                        Text(supp.phone, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            "₹%.2f".format(supp.balance),
                                            fontWeight = FontWeight.Bold,
                                            color = if (supp.balance > 0) StoreRedPrimary else StoreGreenProfit
                                        )
                                        Text(
                                            if (supp.balance > 0) LanguageManager.getString("Payable", "দেবো") else LanguageManager.getString("Settled", "পরিশোধিত"),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextMuted
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSelectSupplierToPayDialog = false }) {
                    Text(LanguageManager.getString("Cancel", "বাতিল"))
                }
            }
        )
    }

    // Record Payment Dialog
    if (paymentSupplier != null) {
        val supp = paymentSupplier!!
        RecordPaymentDialog(
            partyName = supp.name,
            currentBalance = supp.balance,
            isCustomer = false,
            isBn = isBn,
            onDismiss = { paymentSupplier = null },
            onConfirmCustom = { amount, isCreditTaken, paymentMode, note ->
                viewModel.recordSupplierCustomEntry(supp.id, amount, isCreditTaken, paymentMode, note)
                paymentSupplier = null
            }
        )
    }

    // New Purchase Bill Dialog
    if (newPurchaseSupplier != null) {
        NewPurchaseBillDialog(
            initialSupplier = newPurchaseSupplier,
            suppliers = suppliers,
            products = allProducts,
            isBn = isBn,
            viewModel = viewModel,
            onDismiss = { newPurchaseSupplier = null },
            onConfirm = { selectedSupp, items, paidAmount, paymentMode, notes, otherCharges ->
                viewModel.recordMultiItemPurchase(selectedSupp, items, paidAmount, paymentMode, notes, otherCharges)
                newPurchaseSupplier = null
            },
            onAddNewProduct = { newProduct ->
                viewModel.saveProduct(newProduct)
            }
        )
    }

    // Supplier Comprehensive Details Modal (Items provided, Bills & Ledger)
    if (selectedSupplierForDetails != null) {
        val supp = selectedSupplierForDetails!!
        val supplierPurchases = remember(allPurchases, supp.id) {
            allPurchases.filter { it.purchase.supplierId == supp.id }
        }
        val supplierLedger = remember(allLedgerEntries, supp.id) {
            allLedgerEntries.filter { it.partyType == "SUPPLIER" && it.partyId == supp.id }
        }

        SupplierDetailsModal(
            supplier = supp,
            purchases = supplierPurchases,
            ledgerEntries = supplierLedger,
            isBn = isBn,
            onDismiss = { selectedSupplierForDetails = null },
            onRecordPayment = {
                paymentSupplier = supp
                selectedSupplierForDetails = null
            },
            onNewPurchase = {
                newPurchaseSupplier = supp
                selectedSupplierForDetails = null
            }
        )
    }
}

@Composable
fun SupplierCardItem(
    supplier: Supplier,
    purchaseCount: Int,
    isBn: Boolean,
    onViewDetails: () -> Unit,
    onRecordPayment: () -> Unit,
    onNewPurchase: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onWhatsApp: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onViewDetails() },
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        elevation = CardDefaults.cardElevation(2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    PartyProfileAvatar(
                        photoUri = supplier.photoUri,
                        name = supplier.name,
                        size = 48.dp,
                        onClick = onViewDetails
                    )

                    Column {
                        Text(
                            text = supplier.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Phone, contentDescription = null, modifier = Modifier.size(14.dp), tint = TextMuted)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = supplier.phone, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                            if (!supplier.address.isNullOrBlank()) {
                                Text(text = " • ${supplier.address}", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                            }
                        }
                        if (!supplier.gstin.isNullOrBlank()) {
                            Text(
                                text = "GSTIN: ${supplier.gstin}",
                                style = MaterialTheme.typography.labelSmall,
                                color = StorePrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Balance display pill
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = LanguageManager.getString("Payable Balance", "পাওনা টাকা"),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                    Text(
                        text = "₹%.2f".format(supplier.balance),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (supplier.balance > 0) StoreRedPrimary else StoreGreenProfit
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Divider()
            Spacer(modifier = Modifier.height(8.dp))

            // Action row buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = SurfaceWarm,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .clickable { onViewDetails() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Inventory, contentDescription = null, modifier = Modifier.size(14.dp), tint = StorePrimary)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "$purchaseCount Bills & Items",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(onClick = onWhatsApp, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Share, contentDescription = "WhatsApp", tint = StoreGreenProfit, modifier = Modifier.size(18.dp))
                    }

                    IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit", tint = TextMuted, modifier = Modifier.size(18.dp))
                    }

                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = StoreRedPrimary, modifier = Modifier.size(18.dp))
                    }

                    OutlinedButton(
                        onClick = onNewPurchase,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(2.dp))
                        Text("Bill", fontSize = 11.sp)
                    }

                    Button(
                        onClick = onRecordPayment,
                        colors = ButtonDefaults.buttonColors(containerColor = StoreRedPrimary),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Payments, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Pay", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun SupplierFormDialog(
    supplier: Supplier?,
    isBn: Boolean,
    onDismiss: () -> Unit,
    onSave: (Supplier) -> Unit
) {
    var name by remember { mutableStateOf(supplier?.name ?: "") }
    var phone by remember { mutableStateOf(supplier?.phone ?: "") }
    var address by remember { mutableStateOf(supplier?.address ?: "") }
    var gstin by remember { mutableStateOf(supplier?.gstin ?: "") }
    var notes by remember { mutableStateOf(supplier?.notes ?: "") }
    var balanceText by remember { mutableStateOf(supplier?.balance?.toString() ?: "0.0") }
    var photoUri by remember { mutableStateOf<String?>(supplier?.photoUri) }

    val context = LocalContext.current

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val syncable = com.example.utils.ImageSyncHelper.compressUriToDataUrl(context, it)
            if (!syncable.isNullOrBlank()) {
                photoUri = syncable
            }
        }
    }

    val launchSupplierCamera = com.example.utils.rememberHighResCameraCapture { dataUrl ->
        photoUri = dataUrl
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (supplier == null) LanguageManager.getString("Add New Supplier", "নতুন মহাজন যুক্ত করুন") else "Edit Supplier Details",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Profile Avatar Preview
                PartyProfileAvatar(
                    photoUri = photoUri,
                    name = name.ifBlank { "S" },
                    size = 72.dp,
                    showEditBadge = true,
                    onClick = { launchSupplierCamera() }
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { launchSupplierCamera() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isBn) "ক্যামেরা" else "Camera", fontSize = 11.sp)
                    }

                    OutlinedButton(
                        onClick = { galleryLauncher.launch("image/*") },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isBn) "গ্যালারি" else "Gallery", fontSize = 11.sp)
                    }

                    if (!photoUri.isNullOrBlank()) {
                        TextButton(
                            onClick = { photoUri = null },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = StoreRedPrimary, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(if (isBn) "মুছুন" else "Remove", fontSize = 11.sp, color = StoreRedPrimary)
                        }
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(LanguageManager.getString("Supplier / Mill Name*", "মহাজনের নাম*")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text(LanguageManager.getString("Phone Number*", "মোবাইল নম্বর*")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text(LanguageManager.getString("Address / Location", "ঠিকানা / মার্কেট")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = gstin,
                        onValueChange = { gstin = it },
                        label = { Text("GSTIN (Optional)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )

                    OutlinedTextField(
                        value = balanceText,
                        onValueChange = { balanceText = it },
                        label = { Text("Opening Due (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes (e.g. Rice wholesaler)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank() && phone.isNotBlank()) {
                        val newSupp = Supplier(
                            id = supplier?.id ?: ("supp_" + UUID.randomUUID().toString().take(8)),
                            name = name,
                            phone = phone,
                            balance = balanceText.toDoubleOrNull() ?: (supplier?.balance ?: 0.0),
                            address = address.ifBlank { null },
                            gstin = gstin.ifBlank { null },
                            notes = notes.ifBlank { null },
                            photoUri = photoUri
                        )
                        onSave(newSupp)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = StoreRedPrimary)
            ) {
                Text(LanguageManager.getString("Save Supplier", "সংরক্ষণ করুন"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewPurchaseBillDialog(
    initialSupplier: Supplier?,
    suppliers: List<Supplier>,
    products: List<Product>,
    isBn: Boolean,
    viewModel: StoreViewModel,
    onDismiss: () -> Unit,
    onConfirm: (Supplier, List<PurchaseItem>, Double, String, String?, Double) -> Unit,
    onAddNewProduct: ((Product) -> Unit)? = null
) {
    var selectedSupplier by remember { mutableStateOf(initialSupplier ?: suppliers.firstOrNull()) }
    var showAddNewProductModal by remember { mutableStateOf(false) }

    var paidAmountText by remember { mutableStateOf("") }
    var selectedPaymentMode by remember { mutableStateOf("CASH") }
    var billNotes by remember { mutableStateOf("") }
    var billImageUri by remember { mutableStateOf<String?>(null) }
    var showEnlargedPhoto by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val syncable = com.example.utils.ImageSyncHelper.compressUriToDataUrl(context, it)
            if (!syncable.isNullOrBlank()) {
                billImageUri = syncable
            }
        }
    }

    val launchBillCamera = com.example.utils.rememberHighResCameraCapture { dataUrl ->
        billImageUri = dataUrl
    }

    // Draft items list for this purchase bill
    val itemsList = remember { mutableStateListOf<PurchaseItem>() }

    // Delivery / Other Charges State
    var otherChargesText by remember { mutableStateOf("") }
    val parsedOtherCharges = otherChargesText.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0

    // Product search query and filter state
    var productSearchQuery by remember { mutableStateOf("") }
    val filteredProducts = remember(products, productSearchQuery) {
        if (productSearchQuery.isBlank()) {
            products
        } else {
            val query = productSearchQuery.trim().lowercase()
            products.filter { prod ->
                prod.nameEn.lowercase().contains(query) ||
                prod.nameBn.lowercase().contains(query) ||
                (prod.barcode?.lowercase()?.contains(query) == true) ||
                prod.category.lowercase().contains(query)
            }
        }
    }

    // Temp item input state
    var selectedProduct by remember { mutableStateOf<Product?>(products.firstOrNull()) }
    var itemQtyText by remember { mutableStateOf("1") }
    var itemCostPriceText by remember { mutableStateOf(selectedProduct?.costPrice?.toString() ?: "0.0") }

    LaunchedEffect(products) {
        if (selectedProduct == null && products.isNotEmpty()) {
            selectedProduct = products.firstOrNull()
            selectedProduct?.let { itemCostPriceText = it.costPrice.toString() }
        }
    }

    LaunchedEffect(selectedProduct) {
        if (selectedProduct != null) {
            itemCostPriceText = selectedProduct!!.costPrice.toString()
        }
    }

    // Calculations
    val previousDue = selectedSupplier?.balance ?: 0.0
    val itemsSubtotal = remember(itemsList.toList()) { itemsList.sumOf { it.subtotal } }
    val billTotalAmount = itemsSubtotal + parsedOtherCharges
    val totalOutstanding = billTotalAmount + previousDue
    val parsedPaidAmount = paidAmountText.toDoubleOrNull() ?: 0.0
    val remainingDue = (totalOutstanding - parsedPaidAmount).coerceAtLeast(0.0)

    // Confirmation dialog state when closing/backing out with unsaved items
    var showDiscardConfirmDialog by remember { mutableStateOf(false) }

    val handleDismissAttempt = {
        if (itemsList.isNotEmpty()) {
            showDiscardConfirmDialog = true
        } else {
            onDismiss()
        }
    }

    // Intercept hardware/system back button
    BackHandler(enabled = true) {
        if (showDiscardConfirmDialog) {
            showDiscardConfirmDialog = false
        } else {
            handleDismissAttempt()
        }
    }

    AlertDialog(
        onDismissRequest = handleDismissAttempt,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ReceiptLong, contentDescription = null, tint = StoreRedPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = LanguageManager.getString("Supplier Purchase Bill", "মহাজন খরিদ চালানের এন্ট্রি"),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                IconButton(onClick = handleDismissAttempt) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // --- 1. Supplier Selector Header ---
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceWarm),
                    border = BorderStroke(1.dp, TextMuted.copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = LanguageManager.getString("Supplier Account:", "মহাজন নির্বাচন:"),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (previousDue > 0) {
                                Surface(
                                    color = StoreRedPrimary.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = LanguageManager.getString("Prev Credit Due: ₹%.2f".format(previousDue), "পূর্বের বাকি দেনা: ₹%.2f".format(previousDue)),
                                        color = StoreRedPrimary,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            } else {
                                Surface(
                                    color = StoreGreenProfit.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = LanguageManager.getString("No Prev Due", "পূর্বের দেনা নেই"),
                                        color = StoreGreenProfit,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        if (suppliers.isEmpty()) {
                            Text("Please add a supplier first!", color = StoreRedPrimary)
                        } else {
                            var expanded by remember { mutableStateOf(false) }
                            ExposedDropdownMenuBox(
                                expanded = expanded,
                                onExpandedChange = { expanded = !expanded }
                            ) {
                                OutlinedTextField(
                                    value = selectedSupplier?.name ?: "Select Supplier",
                                    onValueChange = {},
                                    readOnly = true,
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                    modifier = Modifier
                                        .menuAnchor()
                                        .fillMaxWidth()
                                )
                                ExposedDropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false }
                                ) {
                                    suppliers.forEach { supp ->
                                        DropdownMenuItem(
                                            text = {
                                                Text("${supp.name} (${supp.phone}) — Due: ₹%.2f".format(supp.balance))
                                            },
                                            onClick = {
                                                selectedSupplier = supp
                                                expanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = TextMuted.copy(alpha = 0.2f))

                // --- 2. Add Item Section + Inline Add New Product Button ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = LanguageManager.getString("Add Items to Bill:", "চালানে মাল যোগ করুন:"),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedButton(
                        onClick = { showAddNewProductModal = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, StorePrimary)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp), tint = StorePrimary)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = LanguageManager.getString("+ New Product", "+ নতুন পণ্য"),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = StorePrimary
                        )
                    }
                }

                if (products.isNotEmpty()) {
                    var prodExpanded by remember { mutableStateOf(false) }

                    // Search box to filter products before selecting
                    var showScanner by remember { mutableStateOf(false) }
                    
                    OutlinedTextField(
                        value = productSearchQuery,
                        onValueChange = {
                            productSearchQuery = it
                            prodExpanded = true
                        },
                        label = { Text(LanguageManager.getString("Search Product to Add...", "পণ্য খুঁজুন / ফিল্টার করুন...")) },
                        placeholder = { Text(LanguageManager.getString("Type name or barcode to filter...", "ফিল্টার করতে নাম বা কোড লিখুন...")) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = StorePrimary) },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { showScanner = true }) {
                                    Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan", tint = StorePrimary)
                                }
                                if (productSearchQuery.isNotEmpty()) {
                                    IconButton(onClick = { productSearchQuery = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear", tint = TextMuted)
                                    }
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (showScanner) {
                        com.example.ui.components.SingleBarcodeScannerDialog(
                            onBarcodeCaptured = { code ->
                                productSearchQuery = code
                                prodExpanded = true
                                showScanner = false
                            },
                            onDismiss = { showScanner = false }
                        )
                    }

                    ExposedDropdownMenuBox(
                        expanded = prodExpanded,
                        onExpandedChange = { prodExpanded = !prodExpanded }
                    ) {
                        OutlinedTextField(
                            value = selectedProduct?.let { "${it.getDisplayName(isBn)} (Cost: ₹${it.costPrice}/${it.unitType})" }
                                ?: LanguageManager.getString("Select Product", "পণ্য নির্বাচন করুন"),
                            onValueChange = {},
                            readOnly = true,
                            label = {
                                Text(
                                    if (productSearchQuery.isNotBlank())
                                        LanguageManager.getString("Matching Products (${filteredProducts.size})", "মিলে যাওয়া পণ্য (${filteredProducts.size} টি)")
                                    else
                                        LanguageManager.getString("Select Product", "পণ্য নির্বাচন করুন")
                                )
                            },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = prodExpanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = prodExpanded,
                            onDismissRequest = { prodExpanded = false },
                            modifier = Modifier.heightIn(max = 280.dp)
                        ) {
                            if (filteredProducts.isEmpty()) {
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                text = LanguageManager.getString(
                                                    "No products matching \"$productSearchQuery\"",
                                                    "\"$productSearchQuery\" দিয়ে কোনো পণ্য মেলেনি"
                                                ),
                                                color = StoreRedPrimary,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = LanguageManager.getString("+ Create New Product", "+ নতুন পণ্য তৈরি করুন"),
                                                color = StorePrimary,
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    },
                                    onClick = {
                                        prodExpanded = false
                                        showAddNewProductModal = true
                                    }
                                )
                            } else {
                                filteredProducts.forEach { prod ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(
                                                    text = prod.getDisplayName(isBn),
                                                    fontWeight = FontWeight.SemiBold,
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                                Text(
                                                    text = "Cost: ₹${prod.costPrice} / ${prod.unitType} • Stock: ${prod.currentStock} ${prod.unitType}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = TextMuted
                                                )
                                            }
                                        },
                                        onClick = {
                                            selectedProduct = prod
                                            itemCostPriceText = prod.costPrice.toString()
                                            productSearchQuery = ""
                                            prodExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = itemQtyText,
                            onValueChange = { itemQtyText = it },
                            label = { Text("Qty (${selectedProduct?.unitType ?: "unit"})") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )

                        OutlinedTextField(
                            value = itemCostPriceText,
                            onValueChange = { itemCostPriceText = it },
                            label = { Text("Cost Price (₹)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Button(
                        onClick = {
                            val p = selectedProduct ?: return@Button
                            val qty = itemQtyText.toDoubleOrNull() ?: 0.0
                            val cost = itemCostPriceText.toDoubleOrNull() ?: p.costPrice
                            if (qty > 0) {
                                itemsList.add(
                                    PurchaseItem(
                                        purchaseId = "",
                                        productId = p.id,
                                        productNameEn = p.nameEn,
                                        productNameBn = p.nameBn,
                                        quantity = qty,
                                        costPrice = cost,
                                        subtotal = qty * cost
                                    )
                                )
                                itemQtyText = "1"
                                productSearchQuery = ""
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = StorePrimary)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(LanguageManager.getString("Add Item to Bill List", "চালানের তালিকায় যোগ করুন"))
                    }
                }

                // Table of added items
                if (itemsList.isNotEmpty()) {
                    Text("Added Items (${itemsList.size}):", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = SurfaceWarm)
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            itemsList.forEachIndexed { index, pItem ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${pItem.productNameEn} (%.1f @ ₹%.1f)".format(pItem.quantity, pItem.costPrice),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = "₹%.2f".format(pItem.subtotal),
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    IconButton(
                                        onClick = { itemsList.removeAt(index) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Remove", tint = StoreRedPrimary, modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                            HorizontalDivider()
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(LanguageManager.getString("Goods / Items Subtotal:", "পণ্য উপমোট:"), fontWeight = FontWeight.Bold)
                                Text("₹%.2f".format(itemsSubtotal), fontWeight = FontWeight.Bold, color = StorePrimary)
                            }
                        }
                    }
                }

                // Delivery / Other Charges (₹)
                OutlinedTextField(
                    value = otherChargesText,
                    onValueChange = { otherChargesText = it },
                    label = { Text(LanguageManager.getString("Delivery / Other Charges (₹)", "ডেলিভারি / অন্যান্য খরচ (₹)")) },
                    placeholder = { Text("0.00") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Default.LocalShipping, contentDescription = null, tint = StorePrimary)
                    },
                    trailingIcon = {
                        if (otherChargesText.isNotEmpty()) {
                            IconButton(onClick = { otherChargesText = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear", tint = TextMuted)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                HorizontalDivider(color = TextMuted.copy(alpha = 0.2f))

                // --- 3. Payment Details Card (Today's Bill + Previous Due) ---
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceWarm),
                    border = BorderStroke(1.dp, TextMuted.copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = LanguageManager.getString("Payment & Outstanding Calculation:", "পাওনা পরিশোধ হিসাব (আজকের বিল + পূর্বের বাকি):"),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextDark
                        )

                        // Distinct Goods / Items Subtotal
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(LanguageManager.getString("Goods / Items Subtotal:", "পণ্য উপমোট:"), style = MaterialTheme.typography.bodySmall, color = TextMuted)
                            Text("₹%.2f".format(itemsSubtotal), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        }

                        // Distinct Delivery / Other Charges
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(LanguageManager.getString("Delivery / Other Charges:", "ডেলিভারি / অন্যান্য খরচ:"), style = MaterialTheme.typography.bodySmall, color = TextMuted)
                            Text(
                                text = "₹%.2f".format(parsedOtherCharges),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (parsedOtherCharges > 0) StorePrimary else TextMuted
                            )
                        }

                        HorizontalDivider(color = TextMuted.copy(alpha = 0.15f))

                        // Today's New Purchase Bill (Goods Subtotal + Extra Charges)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = LanguageManager.getString("Today's New Purchase Bill:", "আজকের ক্রয়ের চালান:"),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "₹%.2f".format(billTotalAmount),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = StoreRedPrimary
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(LanguageManager.getString("Previous Credit Balance Due:", "পূর্বের বাকি বকেয়া:"), style = MaterialTheme.typography.bodySmall, color = TextMuted)
                            Text("₹%.2f".format(previousDue), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = if (previousDue > 0) StoreRedPrimary else TextMuted)
                        }

                        HorizontalDivider(color = TextMuted.copy(alpha = 0.2f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(LanguageManager.getString("Total Payable Outstanding:", "মোট দেনা বকেয়া:"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            Text("₹%.2f".format(totalOutstanding), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, color = StoreRedPrimary)
                        }

                        // Quick Payment Presets
                        Text(LanguageManager.getString("Quick Payment Chips:", "সহজ পেমেন্ট বিকল্প:"), style = MaterialTheme.typography.labelSmall, color = TextMuted)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = parsedPaidAmount == billTotalAmount && billTotalAmount > 0,
                                onClick = { paidAmountText = if (billTotalAmount % 1.0 == 0.0) "%.0f".format(billTotalAmount) else "%.2f".format(billTotalAmount) },
                                label = { Text("Today's Bill Only (₹%.0f)".format(billTotalAmount), fontSize = 11.sp) }
                            )
                            if (previousDue > 0) {
                                FilterChip(
                                    selected = parsedPaidAmount == totalOutstanding,
                                    onClick = { paidAmountText = if (totalOutstanding % 1.0 == 0.0) "%.0f".format(totalOutstanding) else "%.2f".format(totalOutstanding) },
                                    label = { Text("Full Outstanding (₹%.0f)".format(totalOutstanding), fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = StoreGreenProfit, selectedLabelColor = Color.White)
                                )
                            }
                            FilterChip(
                                selected = paidAmountText == "0" || paidAmountText == "0.0",
                                onClick = { paidAmountText = "0" },
                                label = { Text("Pay Nothing (Credit All)", fontSize = 11.sp) }
                            )
                        }

                        OutlinedTextField(
                            value = paidAmountText,
                            onValueChange = { paidAmountText = it },
                            label = { Text(LanguageManager.getString("Amount Paid Now to Supplier (₹)", "আজ মহাজনকে দেওয়া টাকা (₹)")) },
                            placeholder = { Text("e.g. ${if (totalOutstanding > 0) totalOutstanding.toInt() else 1000}") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Remaining Balance
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(LanguageManager.getString("Remaining Supplier Due:", "পরিশোধের পর বাকি দেনা:"), style = MaterialTheme.typography.bodySmall, color = TextMuted)
                            Text(
                                text = "₹%.2f".format(remainingDue),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = if (remainingDue > 0) StoreRedPrimary else StoreGreenProfit
                            )
                        }

                        if (parsedPaidAmount > 0) {
                            Text(LanguageManager.getString("Payment Mode:", "পেমেন্ট মোড:"), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("CASH" to "Cash / নগদ", "ONLINE" to "UPI / Online", "BANK" to "Bank Transfer").forEach { (modeKey, modeLabel) ->
                                    FilterChip(
                                        selected = selectedPaymentMode == modeKey,
                                        onClick = { selectedPaymentMode = modeKey },
                                        label = { Text(modeLabel, fontSize = 11.sp) }
                                    )
                                }
                            }
                        }
                    }
                }

                // --- 4. Photo / Snapshot & Notes Section ---
                Text(
                    text = LanguageManager.getString("Bill Photo / Invoice Snapshot (Camera / Gallery):", "বিলের ছবি / চালানের ফটো (ক্যামেরা / গ্যালারি):"),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { launchBillCamera() },
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

                if (billImageUri != null) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceWarm)
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            AsyncImage(
                                model = com.example.utils.ImageSyncHelper.getImageModel(billImageUri),
                                contentDescription = "Bill Photo Preview",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable { showEnlargedPhoto = billImageUri }
                            )
                            IconButton(
                                onClick = { billImageUri = null },
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

                OutlinedTextField(
                    value = billNotes,
                    onValueChange = { billNotes = it },
                    label = { Text(LanguageManager.getString("Bill / Invoice No. / Notes (Optional)", "চালান / ইনভয়েস নং / নোট (ঐচ্ছিক)")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val supp = selectedSupplier
                    if (supp != null && itemsList.isNotEmpty()) {
                        val finalNotes = buildString {
                            if (billNotes.isNotBlank()) append(billNotes.trim())
                            if (parsedOtherCharges > 0.0) {
                                if (isNotEmpty()) append(" ")
                                append("[Delivery / Other Charges: ₹%.2f]".format(parsedOtherCharges))
                            }
                            if (!billImageUri.isNullOrBlank()) {
                                if (isNotEmpty()) append(" ")
                                append("[Bill Photo: $billImageUri]")
                            }
                        }.ifBlank { null }
                        onConfirm(supp, itemsList.toList(), parsedPaidAmount, selectedPaymentMode, finalNotes, parsedOtherCharges)
                    }
                },
                enabled = selectedSupplier != null && itemsList.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = StoreRedPrimary)
            ) {
                Text(
                    text = if (parsedPaidAmount > 0) "Save Purchase & Pay ₹%.2f".format(parsedPaidAmount)
                    else "Save Purchase Bill (₹%.2f)".format(billTotalAmount)
                )
            }
        },
        dismissButton = {
            TextButton(onClick = handleDismissAttempt) { Text(LanguageManager.getString("Cancel", "বাতিল")) }
        }
    )

    // Confirmation dialog when attempting to discard a bill with items
    if (showDiscardConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirmDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = StoreRedPrimary,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = LanguageManager.getString("Discard Purchase Bill?", "খরিদ চালান বাতিল করবেন?"),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                val itemCount = itemsList.size
                Text(
                    text = LanguageManager.getString(
                        "Discard this purchase bill? $itemCount item${if (itemCount > 1) "s" else ""} will be lost.",
                        "এই খরিদ চালানটি বাতিল করতে চান? চালানের $itemCount টি আইটেম মুছে যাবে।"
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDiscardConfirmDialog = false
                        itemsList.clear()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StoreRedPrimary)
                ) {
                    Text(LanguageManager.getString("Discard", "বাতিল করুন"))
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showDiscardConfirmDialog = false }
                ) {
                    Text(LanguageManager.getString("Cancel", "ফিরে যান"))
                }
            }
        )
    }

    // Inline Add New Product Modal Dialog
    if (showAddNewProductModal) {
        ProductFormDialog(
            existingProduct = null,
            isBn = isBn,
            viewModel = viewModel,
            onDismiss = { showAddNewProductModal = false },
            onSave = { newProd ->
                onAddNewProduct?.invoke(newProd)
                selectedProduct = newProd
                itemCostPriceText = newProd.costPrice.toString()
                showAddNewProductModal = false
            }
        )
    }

    // Enlarged Photo Viewer Modal
    if (showEnlargedPhoto != null) {
        AlertDialog(
            onDismissRequest = { showEnlargedPhoto = null },
            title = {
                Text(LanguageManager.getString("Bill / Invoice Photo", "বিলের ছবি"), fontWeight = FontWeight.Bold)
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
                        contentDescription = "Full Bill Photo",
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

@Composable
fun SupplierDetailsModal(
    supplier: Supplier,
    purchases: List<PurchaseWithItems>,
    ledgerEntries: List<com.example.data.local.entities.LedgerEntry>,
    isBn: Boolean,
    onDismiss: () -> Unit,
    onRecordPayment: () -> Unit,
    onNewPurchase: () -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Items Provided & Bills, 1: Ledger & Payments, 2: Profile Info

    // Aggregate unique products supplied across purchases
    val itemsProvidedSummary = remember(purchases) {
        val map = mutableMapOf<String, Triple<String, Double, Double>>() // productId -> (Name, TotalQty, LastCost)
        purchases.forEach { purchaseWithItems ->
            purchaseWithItems.items.forEach { item ->
                val current = map[item.productId]
                if (current == null) {
                    map[item.productId] = Triple(item.productNameEn.ifBlank { item.productNameBn }, item.quantity, item.costPrice)
                } else {
                    map[item.productId] = Triple(current.first, current.second + item.quantity, item.costPrice)
                }
            }
        }
        map.values.toList()
    }

    val totalSpent = remember(purchases) { purchases.sumOf { it.purchase.totalAmount } }
    val dateFormat = SimpleDateFormat("dd/MM/yyyy hh:mm a", Locale.getDefault())
    var viewPhotoUri by remember { mutableStateOf<String?>(null) }

    var ledgerDatePreset by remember { mutableStateOf(LedgerDatePreset.ALL) }
    var ledgerCustomStartDate by remember {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        mutableLongStateOf(cal.timeInMillis)
    }
    var ledgerCustomEndDate by remember {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        mutableLongStateOf(cal.timeInMillis)
    }

    val filteredLedgerEntries = remember(ledgerEntries, ledgerDatePreset, ledgerCustomStartDate, ledgerCustomEndDate) {
        val bounds = if (ledgerDatePreset == LedgerDatePreset.CUSTOM) {
            Pair(ledgerCustomStartDate, ledgerCustomEndDate)
        } else {
            getPresetDateBounds(ledgerDatePreset)
        }
        ledgerEntries.filter { entry ->
            val afterStart = bounds.first == null || entry.datetime >= bounds.first!!
            val beforeEnd = bounds.second == null || entry.datetime <= bounds.second!!
            afterStart && beforeEnd
        }.sortedByDescending { it.datetime }
    }

    val periodPurchases = remember(filteredLedgerEntries) {
        filteredLedgerEntries.filter { !it.type.contains("MADE") }.sumOf { it.amount }
    }
    val periodPaid = remember(filteredLedgerEntries) {
        filteredLedgerEntries.filter { it.type.contains("MADE") }.sumOf { it.amount }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    PartyProfileAvatar(
                        photoUri = supplier.photoUri,
                        name = supplier.name,
                        size = 50.dp,
                        onClick = {
                            if (!supplier.photoUri.isNullOrBlank()) {
                                viewPhotoUri = supplier.photoUri
                            }
                        }
                    )

                    Column(modifier = Modifier.weight(1f)) {
                        Text(supplier.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Ph: ${supplier.phone}", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                            Text("Dues: ₹%.2f".format(supplier.balance), fontWeight = FontWeight.Bold, color = StoreRedPrimary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
            ) {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Items & Bills (${purchases.size})", fontSize = 12.sp) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text(if (isBn) "লেনদেন (${filteredLedgerEntries.size})" else "Ledger (${filteredLedgerEntries.size})", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("Profile Details", fontSize = 12.sp) }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                when (selectedTab) {
                    0 -> {
                        // Items Provided & Bills Tab
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = SurfaceWarm)
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text("Distinct Items Provided (${itemsProvidedSummary.size}):", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        if (itemsProvidedSummary.isEmpty()) {
                                            Text("No purchase bill records yet.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                        } else {
                                            itemsProvidedSummary.forEach { (name, totalQty, lastCost) ->
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Text("• $name", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                                    Text("Total Supplied: %.1f | Last Cost: ₹%.2f".format(totalQty, lastCost), style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            item {
                                Text("Purchase Bills History:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                            }

                            if (purchases.isEmpty()) {
                                item {
                                    Text("No bills recorded for this supplier.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                                }
                            } else {
                                items(purchases, key = { it.purchase.id }, contentType = { "SUPPLIER_PURCHASE" }) { pWithItems ->
                                    val p = pWithItems.purchase
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = CardBackground),
                                        elevation = CardDefaults.cardElevation(1.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column {
                                                    Text("Bill #${p.id.takeLast(6)}", fontWeight = FontWeight.Bold)
                                                    Text(dateFormat.format(Date(p.datetime)), style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                                }
                                                Column(horizontalAlignment = Alignment.End) {
                                                    Text("Total: ₹%.2f".format(p.totalAmount), fontWeight = FontWeight.Bold, color = StoreRedPrimary)
                                                    if (p.amountPaid > 0) {
                                                        Text("Paid: ₹%.2f (${p.paidVia})".format(p.amountPaid), style = MaterialTheme.typography.labelSmall, color = StoreGreenProfit)
                                                    }
                                                    if (p.dueAmount > 0) {
                                                        Text("Due: ₹%.2f".format(p.dueAmount), style = MaterialTheme.typography.labelSmall, color = StoreRedPrimary, fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(4.dp))
                                            pWithItems.items.forEach { pItem ->
                                                Text(
                                                    text = " • ${pItem.productNameEn}: %.1f @ ₹%.1f = ₹%.2f".format(pItem.quantity, pItem.costPrice, pItem.subtotal),
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                            }

                                            val itemsSum = pWithItems.items.sumOf { it.subtotal }
                                            val extraCharges = (p.totalAmount - itemsSum).coerceAtLeast(0.0)
                                            if (extraCharges > 0.009) {
                                                Text(
                                                    text = " • Delivery / Other Charges: ₹%.2f".format(extraCharges),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = StorePrimary,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }

                                            val photoUri = extractPhotoUri(p.notes)
                                            val cleanNote = cleanNotes(p.notes)
                                            if (cleanNote.isNotEmpty()) {
                                                Text("Note: $cleanNote", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                            }
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
                                                        Icon(Icons.Default.ReceiptLong, contentDescription = null, tint = StorePrimary, modifier = Modifier.size(14.dp))
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text("View Bill Photo 📷", style = MaterialTheme.typography.labelSmall, color = StorePrimary, fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    1 -> {
                        // Ledger & Payments Tab with Custom Date Range Filter
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Date-Picker UI Component
                            LedgerDateRangeFilterComponent(
                                selectedPreset = ledgerDatePreset,
                                customStartDate = ledgerCustomStartDate,
                                customEndDate = ledgerCustomEndDate,
                                onPresetChange = { ledgerDatePreset = it },
                                onCustomRangeChange = { start, end ->
                                    ledgerCustomStartDate = start
                                    ledgerCustomEndDate = end
                                },
                                onReset = { ledgerDatePreset = LedgerDatePreset.ALL },
                                isCompact = true,
                                isBn = isBn
                            )

                            // Quick Period Movement Strip
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = SurfaceWarm,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (isBn) "মোট পরিশোধ: ₹%.2f".format(periodPaid) else "Total Paid: ₹%.2f".format(periodPaid),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = StoreGreenProfit
                                    )
                                    Text(
                                        text = if (isBn) "মোট ক্রয়: ₹%.2f".format(periodPurchases) else "Purchases: ₹%.2f".format(periodPurchases),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = StoreRedPrimary
                                    )
                                }
                            }

                            if (filteredLedgerEntries.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = if (ledgerDatePreset != LedgerDatePreset.ALL) {
                                                LanguageManager.getString("No transactions found for this date range.", "এই তারিখের মধ্যে কোন লেনদেন পাওয়া যায়নি।")
                                            } else {
                                                LanguageManager.getString("No ledger entries found.", "কোন লেনদেনের ইতিহাস পাওয়া যায়নি।")
                                            },
                                            color = TextMuted,
                                            fontSize = 12.sp
                                        )
                                        if (ledgerDatePreset != LedgerDatePreset.ALL) {
                                            TextButton(onClick = { ledgerDatePreset = LedgerDatePreset.ALL }) {
                                                Text(LanguageManager.getString("Clear Date Filter", "ফিল্টার রিসেট করুন"), fontSize = 11.sp)
                                            }
                                        }
                                    }
                                }
                            } else {
                                val purchasesById = remember(purchases) { purchases.associateBy { it.purchase.id } }
                                LazyColumn(
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                ) {
                                    items(filteredLedgerEntries, key = { it.id }, contentType = { "SUPPLIER_LEDGER_ENTRY" }) { entry ->
                                    val linkedPurchase = entry.referenceId?.let { refId -> purchasesById[refId] }
                                    var expanded by remember { mutableStateOf(false) }

                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = linkedPurchase != null) { expanded = !expanded },
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (entry.type.contains("MADE")) StoreGreenProfit.copy(alpha = 0.06f) else StoreRedPrimary.copy(alpha = 0.06f)
                                        ),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Surface(
                                                        color = if (entry.type.contains("MADE")) StoreGreenProfit else StoreRedPrimary,
                                                        shape = RoundedCornerShape(4.dp)
                                                    ) {
                                                        Text(
                                                            text = when (entry.type) {
                                                                "PURCHASE_CREDIT" -> if (isBn) "ধারে ক্রয় (Payable)" else "Credit Purchase (Payable)"
                                                                "PAYMENT_MADE" -> if (isBn) "পরিশোধ (Paid)" else "Payment Made (Paid)"
                                                                else -> entry.type
                                                            },
                                                            color = Color.White,
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(dateFormat.format(Date(entry.datetime)), style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                                    val entryPhotoUri = extractPhotoUri(entry.note)
                                                    val entryCleanNote = cleanNotes(entry.note)
                                                    if (entryCleanNote.isNotEmpty()) {
                                                        Text("Note: $entryCleanNote", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                                    }
                                                    if (entryPhotoUri != null) {
                                                        Spacer(modifier = Modifier.height(4.dp))
                                                        Surface(
                                                            color = StorePrimary.copy(alpha = 0.12f),
                                                            shape = RoundedCornerShape(6.dp),
                                                            modifier = Modifier.clickable { viewPhotoUri = entryPhotoUri }
                                                        ) {
                                                            Row(
                                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                                verticalAlignment = Alignment.CenterVertically
                                                            ) {
                                                                Icon(Icons.Default.Photo, contentDescription = null, tint = StorePrimary, modifier = Modifier.size(14.dp))
                                                                Spacer(modifier = Modifier.width(4.dp))
                                                                Text("View Receipt Photo 📷", style = MaterialTheme.typography.labelSmall, color = StorePrimary, fontWeight = FontWeight.Bold)
                                                            }
                                                        }
                                                    }
                                                }

                                                Column(horizontalAlignment = Alignment.End) {
                                                    Text(
                                                        text = (if (entry.type.contains("MADE")) "- ₹%.2f" else "+ ₹%.2f").format(entry.amount),
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 15.sp,
                                                        color = if (entry.type.contains("MADE")) StoreGreenProfit else StoreRedPrimary
                                                    )

                                                    if (linkedPurchase != null) {
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            modifier = Modifier.padding(top = 2.dp)
                                                        ) {
                                                            Text(
                                                                text = if (expanded) "Hide Items" else "View Items (${linkedPurchase.items.size})",
                                                                fontSize = 11.sp,
                                                                color = StorePrimary,
                                                                fontWeight = FontWeight.Bold
                                                            )
                                                            Icon(
                                                                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                                contentDescription = null,
                                                                tint = StorePrimary,
                                                                modifier = Modifier.size(14.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            }

                                            // Expanded Purchase Items
                                            if (expanded && linkedPurchase != null) {
                                                Spacer(modifier = Modifier.height(8.dp))
                                                Divider(color = Color.LightGray.copy(alpha = 0.5f))
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Text(
                                                    text = "Purchase Bill #${linkedPurchase.purchase.id.takeLast(6)} Item Details:",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = TextDark
                                                )
                                                linkedPurchase.items.forEach { item ->
                                                    val itemName = if (isBn && item.productNameBn.isNotBlank()) item.productNameBn else item.productNameEn
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(vertical = 1.dp),
                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Text(
                                                            text = " • $itemName (x${item.quantity})",
                                                            style = MaterialTheme.typography.bodySmall
                                                        )
                                                        Text(
                                                            text = "₹%.2f".format(item.subtotal),
                                                            style = MaterialTheme.typography.bodySmall,
                                                            fontWeight = FontWeight.Bold
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

                    2 -> {
                        // Profile Info Tab
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = SurfaceWarm)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("Supplier Info", fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("Name: ${supplier.name}")
                                    Text("Phone: ${supplier.phone}")
                                    Text("Address: ${supplier.address ?: "Not provided"}")
                                    Text("GSTIN: ${supplier.gstin ?: "N/A"}")
                                    Text("Notes: ${supplier.notes ?: "None"}")
                                }
                            }

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = CardBackground)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("Account Stats", fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("Total Purchase Bills: ${purchases.size}")
                                    Text("Total Purchased Valuation: ₹%.2f".format(totalSpent))
                                    Text("Current Payable Dues: ₹%.2f".format(supplier.balance), fontWeight = FontWeight.Bold, color = StoreRedPrimary)
                                }
                            }

                            Button(
                                onClick = {
                                    val statementMsg = WhatsAppHelper.generateSupplierStatement(
                                        supplierName = supplier.name,
                                        phone = supplier.phone,
                                        currentBalance = supplier.balance,
                                        purchaseCount = purchases.size,
                                        totalPurchasedValuation = totalSpent,
                                        isBengali = isBn
                                    )
                                    WhatsAppHelper.sendWhatsAppMessage(context, supplier.phone, statementMsg)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = StoreRedPrimary)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (isBn) "হোয়াটসঅ্যাপে মহাজন খাতা বিবরণী শেয়ার করুন" else "Share Supplier Statement on WhatsApp")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = onNewPurchase,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.AddShoppingCart, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("New Purchase", fontSize = 12.sp)
                }
                Button(
                    onClick = onRecordPayment,
                    colors = ButtonDefaults.buttonColors(containerColor = StoreRedPrimary),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.Payments, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Pay Supplier", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )

    if (viewPhotoUri != null) {
        AlertDialog(
            onDismissRequest = { viewPhotoUri = null },
            title = {
                Text(LanguageManager.getString("Attached Photo / Bill", "সংযুক্ত ফটো / চালান"), fontWeight = FontWeight.Bold)
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
                        contentDescription = "Full Attached Photo",
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
}
