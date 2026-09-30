package com.example.ui.screens.inventory

import android.Manifest
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.example.ui.components.CollapsingHeaderLayout
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.local.dao.PurchaseWithItems
import com.example.data.local.entities.*
import com.example.ui.components.FirebaseSyncStatusBadge
import com.example.ui.components.LowStockDashboardCardWidget
import com.example.ui.components.PrintBarcodeModalDialog
import com.example.ui.components.SingleBarcodeScannerDialog
import com.example.ui.screens.suppliers.saveBitmapToCache
import com.example.ui.theme.*
import com.example.utils.BengaliReceiptTranslator
import com.example.utils.EscPosPrinter
import com.example.utils.LanguageManager
import com.example.viewmodel.StoreViewModel
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryScreen(viewModel: StoreViewModel) {
    val isBn = LanguageManager.isBengali
    val products by viewModel.allProducts.collectAsState()
    val allPurchases by viewModel.allPurchases.collectAsState()
    val lowStockProducts by viewModel.lowStockProducts.collectAsState()
    val expiredProducts by viewModel.expiredProducts.collectAsState()
    val expiringSoonProducts by viewModel.expiringSoonProducts.collectAsState()
    val suppliers by viewModel.allSuppliers.collectAsState()
    val categories by viewModel.allCategories.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    // 300ms debounce for inventory search query
    var debouncedSearchQuery by remember { mutableStateOf("") }
    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            debouncedSearchQuery = ""
        } else {
            kotlinx.coroutines.delay(300)
            debouncedSearchQuery = searchQuery
        }
    }

    var filterLowStockOnly by remember { mutableStateOf(false) }
    var filterExpiryOnly by remember { mutableStateOf(false) }
    var filterDiscountOnly by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf("ALL") }
    var showManageCategoriesDialog by remember { mutableStateOf(false) }

    // BackHandler: Clear search query or filters before navigating back to POS
    BackHandler(enabled = searchQuery.isNotBlank() || filterLowStockOnly || filterExpiryOnly || filterDiscountOnly || selectedCategory != "ALL") {
        searchQuery = ""
        filterLowStockOnly = false
        filterExpiryOnly = false
        filterDiscountOnly = false
        selectedCategory = "ALL"
    }

    var showAddProductDialog by remember { mutableStateOf(false) }
    var editingProduct by remember { mutableStateOf<Product?>(null) }
    var restockProduct by remember { mutableStateOf<Product?>(null) }
    var stockOutProduct by remember { mutableStateOf<Product?>(null) }
    var printBarcodeProduct by remember { mutableStateOf<Product?>(null) }
    var selectedPrintVariant by remember { mutableStateOf<com.example.data.local.entities.BarcodeVariant?>(null) }
    var quickAddVariantProduct by remember { mutableStateOf<Product?>(null) }
    var batchManageProduct by remember { mutableStateOf<Product?>(null) }
    var productForPurchaseHistory by remember { mutableStateOf<Product?>(null) }
    var deletingProduct by remember { mutableStateOf<Product?>(null) }
    var showOverallPurchaseHistoryHub by remember { mutableStateOf(false) }
    var showOffersHubDialog by remember { mutableStateOf(false) }
    var selectedInvoiceForDetail by remember { mutableStateOf<PurchaseWithItems?>(null) }
    var previewPhotoProduct by remember { mutableStateOf<Product?>(null) }

    // Handle App Shortcut to open Add Product dialog
    LaunchedEffect(viewModel.triggerShowAddProduct) {
        if (viewModel.triggerShowAddProduct) {
            if (com.example.utils.StaffManager.canManageInventory()) {
                showAddProductDialog = true
            }
            viewModel.triggerShowAddProduct = false
        }
    }

    val discountedProductsCount = remember(products) {
        products.count { it.hasMrpDiscount() }
    }

    val productCountsByCategory = remember(products) {
        products.groupingBy { it.category.lowercase() }.eachCount()
    }

    val filteredList = remember(products, debouncedSearchQuery, filterLowStockOnly, filterExpiryOnly, filterDiscountOnly, selectedCategory) {
        products.filter { prod ->
            val matchQuery = debouncedSearchQuery.isBlank() ||
                    prod.nameEn.contains(debouncedSearchQuery, ignoreCase = true) ||
                    prod.nameBn.contains(debouncedSearchQuery, ignoreCase = true) ||
                    prod.category.contains(debouncedSearchQuery, ignoreCase = true)
            val matchLowStock = !filterLowStockOnly || (prod.currentStock <= prod.lowStockThreshold)
            val matchExpiry = !filterExpiryOnly || (prod.getExpiryStatus() == ExpiryStatus.EXPIRED || prod.getExpiryStatus() == ExpiryStatus.EXPIRING_SOON)
            val matchDiscount = !filterDiscountOnly || prod.hasMrpDiscount()
            val matchCategory = selectedCategory == "ALL" || prod.category.equals(selectedCategory, ignoreCase = true)
            matchQuery && matchLowStock && matchExpiry && matchDiscount && matchCategory
        }
    }

    val totalCostValuation = remember(products) {
        products.sumOf {
            if (it.unitType.equals("gram", ignoreCase = true)) (it.currentStock / 1000.0) * it.costPrice else it.currentStock * it.costPrice
        }
    }

    val totalRetailValuation = remember(products) {
        products.sumOf {
            if (it.unitType.equals("gram", ignoreCase = true)) (it.currentStock / 1000.0) * it.sellingPrice else it.currentStock * it.sellingPrice
        }
    }

    Scaffold(
        floatingActionButton = {
            if (com.example.utils.StaffManager.canManageInventory()) {
                FloatingActionButton(
                    onClick = { showAddProductDialog = true },
                    containerColor = StoreRedPrimary,
                    contentColor = Color.White
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Product")
                }
            }
        }
    ) { innerPadding ->
        CollapsingHeaderLayout(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp),
            header = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 4.dp)
                ) {
                    // Valuation & Overview Header Card (Compact Design)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = StoreRedPrimary),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = LanguageManager.getString("Inventory Overview", "স্টক ও ইনভেন্টরি তথ্য"),
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.labelSmall
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            if (com.example.utils.StaffManager.canViewCostPrice()) {
                                Text(
                                    text = "₹%.2f".format(totalCostValuation),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = LanguageManager.getString("(Cost Value)", "(ক্রয়মূল্য)"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.8f),
                                    modifier = Modifier.padding(bottom = 2.dp)
                                )
                            } else {
                                Text(
                                    text = "${products.size} Products",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FirebaseSyncStatusBadge(
                            viewModel = viewModel,
                            compact = true
                        )
                        Surface(
                            color = StoreGold,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "${products.size} ${LanguageManager.getString("Items", "টি")}",
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Low Stock Reorder Dashboard Widget
            LowStockDashboardCardWidget(viewModel = viewModel)

            // Expiry Alert Card Widget
            if (expiredProducts.isNotEmpty() || expiringSoonProducts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { filterExpiryOnly = !filterExpiryOnly },
                    colors = CardDefaults.cardColors(
                        containerColor = if (expiredProducts.isNotEmpty()) StoreRedAlert.copy(alpha = 0.12f) else StoreSaffronAccent.copy(alpha = 0.12f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                imageVector = if (expiredProducts.isNotEmpty()) Icons.Default.EventBusy else Icons.Default.HourglassBottom,
                                contentDescription = null,
                                tint = if (expiredProducts.isNotEmpty()) StoreRedAlert else StoreSaffronAccent,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = if (isBn) "মেয়াদ সতর্কতা (Expiry Alert)" else "Expiry Alert Management",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = TextDark
                                )
                                val alertText = buildString {
                                    if (expiredProducts.isNotEmpty()) {
                                        append("${expiredProducts.size} " + LanguageManager.getString("expired items", "টি মেয়াদ উত্তীর্ণ পণ্য"))
                                    }
                                    if (expiringSoonProducts.isNotEmpty()) {
                                        if (isNotEmpty()) append(" • ")
                                        append("${expiringSoonProducts.size} " + LanguageManager.getString("expiring soon", "টি শীঘ্রই মেয়াদ শেষ"))
                                    }
                                }
                                Text(
                                    text = alertText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted
                                )
                            }
                        }

                        Button(
                            onClick = { filterExpiryOnly = !filterExpiryOnly },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.height(32.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (filterExpiryOnly) StorePrimary else (if (expiredProducts.isNotEmpty()) StoreRedAlert else StoreSaffronAccent)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = if (filterExpiryOnly) (if (isBn) "সব দেখুন" else "Show All") else (if (isBn) "ফিল্টার করুন" else "View Items"),
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(LanguageManager.getString("Search stock items...", "পণ্য খুঁজুন...")) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Quick Status Filters (Low Stock, Expiry, Discounted)
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item {
                    FilterChip(
                        selected = filterLowStockOnly,
                        onClick = {
                            filterLowStockOnly = !filterLowStockOnly
                            if (filterLowStockOnly) {
                                filterExpiryOnly = false
                                filterDiscountOnly = false
                            }
                        },
                        label = { Text(LanguageManager.getString("Low Stock", "কম স্টক")) },
                        leadingIcon = {
                            if (filterLowStockOnly) Icon(Icons.Default.Check, contentDescription = null)
                        }
                    )
                }
                item {
                    FilterChip(
                        selected = filterExpiryOnly,
                        onClick = {
                            filterExpiryOnly = !filterExpiryOnly
                            if (filterExpiryOnly) {
                                filterLowStockOnly = false
                                filterDiscountOnly = false
                            }
                        },
                        label = { Text(if (isBn) "মেয়াদ ⌛" else "Expiry ⌛") },
                        leadingIcon = {
                            if (filterExpiryOnly) Icon(Icons.Default.Check, contentDescription = null)
                        }
                    )
                }
                item {
                    FilterChip(
                        selected = filterDiscountOnly,
                        onClick = {
                            filterDiscountOnly = !filterDiscountOnly
                            if (filterDiscountOnly) {
                                filterLowStockOnly = false
                                filterExpiryOnly = false
                            }
                        },
                        label = { Text(if (isBn) "ছাড় 🏷️ ($discountedProductsCount)" else "Discounts 🏷️ ($discountedProductsCount)") },
                        leadingIcon = {
                            if (filterDiscountOnly) Icon(Icons.Default.Check, contentDescription = null)
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = StoreGreenProfit.copy(alpha = 0.18f),
                            selectedLabelColor = StoreGreenProfit
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Category Filter Chips Bar & Category Manager Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    item {
                        FilterChip(
                            selected = selectedCategory == "ALL",
                            onClick = { selectedCategory = "ALL" },
                            label = { Text("${LanguageManager.getString("All Items", "সব পণ্য")} (${products.size})") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = StorePrimary,
                                selectedLabelColor = Color.White
                            )
                        )
                    }

                    items(categories, key = { it }) { categoryName ->
                        val count = productCountsByCategory[categoryName.lowercase()] ?: 0
                        FilterChip(
                            selected = selectedCategory.equals(categoryName, ignoreCase = true),
                            onClick = { selectedCategory = categoryName },
                            label = { Text("$categoryName ($count)") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = StorePrimary,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                IconButton(
                    onClick = { showManageCategoriesDialog = true },
                    modifier = Modifier
                        .background(StorePrimary.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                        .size(38.dp)
                ) {
                    Icon(
                        Icons.Default.Category,
                        contentDescription = "Manage Categories",
                        tint = StorePrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                IconButton(
                    onClick = { showOverallPurchaseHistoryHub = true },
                    modifier = Modifier
                        .background(StorePrimary.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                        .size(38.dp)
                ) {
                    Icon(
                        Icons.Default.ReceiptLong,
                        contentDescription = "Purchase History Hub",
                        tint = StorePrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                IconButton(
                    onClick = { showOffersHubDialog = true },
                    modifier = Modifier
                        .background(StorePrimary.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                        .size(38.dp)
                ) {
                    Icon(
                        Icons.Default.LocalOffer,
                        contentDescription = "Offers Hub",
                        tint = StorePrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Conversion conflict review banner (Flag products with conflicting unit-ratio vs piecesPerBox)
            val conflictingProducts = products.filter { it.hasConversionConflict() }
            if (conflictingProducts.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            editingProduct = conflictingProducts.first()
                        },
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)),
                    border = BorderStroke(1.5.dp, Color(0xFFEF4444)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color(0xFFDC2626),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isBn) 
                                    "⚠️ ${conflictingProducts.size}টি পণ্যে বক্স অনুপাত অমিল (রিভিউ প্রয়োজন)" 
                                else 
                                    "⚠️ ${conflictingProducts.size} Product(s) Need Review (Ratio Conflict)",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF991B1B),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = if (isBn)
                                    "সাব-ইউনিট অনুপাত ও পুরানো বক্সে পিসের মান অমিল রয়েছে। ঠিক করতে ট্যাপ করুন।"
                                else
                                    "Conflicting box-to-piece ratios detected (Sub-Unit Ratio vs Pieces/Box). Tap to review & fix.",
                                fontSize = 11.sp,
                                color = Color(0xFF7F1D1D)
                            )
                        }
                        TextButton(
                            onClick = { editingProduct = conflictingProducts.first() },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFDC2626))
                        ) {
                            Text(if (isBn) "রিভিউ" else "Review", fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }
                }
            },
            content = {
                // Products List
                if (filteredList.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            LanguageManager.getString("No inventory items match search", "কোন পণ্য খুঁজে পাওয়া যায়নি"),
                            color = TextMuted
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 88.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredList, key = { it.id }, contentType = { "INVENTORY_ITEM" }) { product ->
                            InventoryItemCard(
                                product = product,
                                isBn = isBn,
                                onEdit = { editingProduct = product },
                                onRestock = { restockProduct = product },
                                onStockOut = { stockOutProduct = product },
                                onPrintBarcode = {
                                    selectedPrintVariant = null
                                    printBarcodeProduct = product
                                },
                                onManageBatches = { batchManageProduct = product },
                                onViewPurchaseHistory = { productForPurchaseHistory = product },
                                onDelete = { deletingProduct = product },
                                onPhotoClick = { previewPhotoProduct = product },
                                onPrintVariant = { variant ->
                                    selectedPrintVariant = variant
                                    printBarcodeProduct = product
                                },
                                onAddVariant = { quickAddVariantProduct = product }
                            )
                        }
                    }
                }
            }
        )
    }

    // Add / Edit Product Dialog
    if (showAddProductDialog || editingProduct != null) {
        val prodToEdit = editingProduct
        key(prodToEdit?.id ?: "new_product") {
            ProductFormDialog(
                existingProduct = prodToEdit,
                availableCategories = categories,
                isBn = isBn,
                viewModel = viewModel,
                onDismiss = {
                    showAddProductDialog = false
                    editingProduct = null
                },
                onSave = { product ->
                    viewModel.saveProduct(product)
                    showAddProductDialog = false
                    editingProduct = null
                },
                onDelete = if (prodToEdit != null) {
                    {
                        val targetProd = prodToEdit
                        editingProduct = null
                        showAddProductDialog = false
                        deletingProduct = targetProd
                    }
                } else null
            )
        }
    }

    // Barcode Printing Dialog for Inventory List Card
    if (printBarcodeProduct != null) {
        val targetProd = printBarcodeProduct!!
        // If product doesn't have a barcode yet, generate a temporary default one
        val codeToPrint = targetProd.barcode?.ifBlank { null } ?: EscPosPrinter.generateValidEan13Barcode()
        PrintBarcodeModalDialog(
            productName = targetProd.getDisplayName(isBn),
            barcodeStr = codeToPrint,
            sellingPrice = targetProd.sellingPrice,
            mrpPrice = targetProd.getEffectiveMrp(),
            viewModel = viewModel,
            initialQuantity = "1 ${targetProd.unitType.replaceFirstChar { it.uppercase() }}",
            initialExpiryDate = targetProd.expiryDate,
            product = targetProd,
            barcodeVariants = targetProd.getBarcodeVariants(),
            initialSelectedVariant = selectedPrintVariant,
            onRequestAddVariant = {
                quickAddVariantProduct = targetProd
            },
            onSaveNewVariant = { newVar ->
                val existing = targetProd.getBarcodeVariants()
                val updatedVariants = existing + newVar
                val updatedProd = targetProd.withBarcodeVariants(updatedVariants)
                viewModel.saveProduct(updatedProd)
                printBarcodeProduct = updatedProd
                selectedPrintVariant = newVar
            },
            onPriceOrMrpChanged = { newSellingPrice, newMrp ->
                if (newSellingPrice != targetProd.sellingPrice || newMrp != targetProd.mrp) {
                    val updatedProd = targetProd.copy(
                        sellingPrice = if (newSellingPrice > 0.0) newSellingPrice else targetProd.sellingPrice,
                        mrp = newMrp
                    )
                    viewModel.saveProduct(updatedProd)
                    printBarcodeProduct = updatedProd
                }
            },
            onDismiss = {
                printBarcodeProduct = null
                selectedPrintVariant = null
            }
        )
    }

    // Quick Add Barcode Variant Modal Dialog directly from Inventory
    if (quickAddVariantProduct != null) {
        val targetProd = quickAddVariantProduct!!
        BarcodeVariantEditDialog(
            initialVariant = null,
            baseProduct = targetProd,
            primaryBarcode = targetProd.barcode,
            existingVariants = targetProd.getBarcodeVariants(),
            viewModel = viewModel,
            isBn = isBn,
            onDismiss = { quickAddVariantProduct = null },
            onSaveVariant = { newVariant ->
                val existing = targetProd.getBarcodeVariants()
                val updatedVariants = existing + newVariant
                val updatedProd = targetProd.withBarcodeVariants(updatedVariants)
                viewModel.saveProduct(updatedProd)
                quickAddVariantProduct = null
                // Automatically open print dialog for this newly created bulk price variant!
                selectedPrintVariant = newVariant
                printBarcodeProduct = updatedProd
            }
        )
    }

    // Category Management Modal Dialog
    if (showManageCategoriesDialog) {
        CategoryManagementDialog(
            viewModel = viewModel,
            isBn = isBn,
            onDismiss = { showManageCategoriesDialog = false }
        )
    }

    // Restock / Stock-IN Dialog
    if (restockProduct != null) {
        val prod = restockProduct!!
        RestockDialog(
            product = prod,
            suppliers = suppliers,
            isBn = isBn,
            onDismiss = { restockProduct = null },
            onConfirm = { addedQty, supplier, newCostPrice, newSellingPrice, isCredit, batchNumber, expiryDate ->
                viewModel.recordStockIn(prod, addedQty, supplier, newCostPrice, newSellingPrice, isCredit, batchNumber, expiryDate)
                restockProduct = null
            }
        )
    }

    // Stock-OUT Dialog
    if (stockOutProduct != null) {
        val prod = stockOutProduct!!
        val prodBatches by viewModel.getBatchesForProduct(prod.id).collectAsState(initial = emptyList())
        StockOutDialog(
            product = prod,
            batches = prodBatches,
            isBn = isBn,
            onDismiss = { stockOutProduct = null },
            onConfirm = { removedQty, reason, note, batchId ->
                viewModel.recordStockOut(prod, removedQty, reason, note, batchId)
                stockOutProduct = null
            }
        )
    }

    // Multi-Batch Expiry Tracking Dialog
    if (batchManageProduct != null) {
        ProductBatchesDialog(
            product = batchManageProduct!!,
            viewModel = viewModel,
            isBn = isBn,
            onDismiss = { batchManageProduct = null }
        )
    }

    // Product Purchase History Dialog
    if (productForPurchaseHistory != null) {
        val prod = productForPurchaseHistory!!
        ProductPurchaseHistoryDialog(
            product = prod,
            allPurchases = allPurchases,
            suppliers = suppliers,
            isBn = isBn,
            onDismiss = { productForPurchaseHistory = null },
            onViewFullInvoice = { pWithItems ->
                selectedInvoiceForDetail = pWithItems
            },
            onStockIn = {
                restockProduct = prod
            }
        )
    }

    // Store-Wide Purchase History & Invoices Hub
    if (showOverallPurchaseHistoryHub) {
        StorePurchaseHistoryHubDialog(
            viewModel = viewModel,
            isBn = isBn,
            onDismiss = { showOverallPurchaseHistoryHub = false },
            onViewFullInvoice = { pWithItems ->
                selectedInvoiceForDetail = pWithItems
            }
        )
    }

    // Purchase Invoice Detail Voucher Dialog
    if (selectedInvoiceForDetail != null) {
        val invoice = selectedInvoiceForDetail!!
        PurchaseInvoiceDetailDialog(
            purchaseWithItems = invoice,
            isBn = isBn,
            onDismiss = { selectedInvoiceForDetail = null },
            onDelete = {
                viewModel.deletePurchase(invoice.purchase.id)
                selectedInvoiceForDetail = null
            }
        )
    }

    if (showOffersHubDialog) {
        com.example.ui.screens.offers.OffersManagementDialog(
            viewModel = viewModel,
            onDismiss = { showOffersHubDialog = false }
        )
    }

    // Delete Product Confirmation Dialog
    if (deletingProduct != null) {
        val prod = deletingProduct!!
        DeleteProductConfirmationDialog(
            product = prod,
            isBn = isBn,
            onDismiss = { deletingProduct = null },
            onConfirm = {
                viewModel.deleteProduct(prod)
                deletingProduct = null
            }
        )
    }

    if (previewPhotoProduct != null) {
        val prod = previewPhotoProduct!!
        com.example.ui.components.ZoomablePaymentScreenshotDialog(
            imageModel = com.example.utils.ImageSyncHelper.getImageModel(prod.imageUri),
            title = prod.getDisplayName(isBn),
            subtitle = "₹${"%.2f".format(prod.sellingPrice)} • Stock: ${prod.currentStock} ${prod.unitType}",
            onDismiss = { previewPhotoProduct = null }
        )
    }
}

@Composable
fun InventoryItemCard(
    product: Product,
    isBn: Boolean,
    onEdit: () -> Unit,
    onRestock: () -> Unit,
    onStockOut: () -> Unit,
    onPrintBarcode: () -> Unit,
    onManageBatches: () -> Unit,
    onViewPurchaseHistory: () -> Unit = {},
    onDelete: () -> Unit,
    onPhotoClick: (() -> Unit)? = null,
    onPrintVariant: ((BarcodeVariant) -> Unit)? = null,
    onAddVariant: (() -> Unit)? = null
) {
    val isLowStock = product.currentStock <= product.lowStockThreshold

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        elevation = CardDefaults.cardElevation(2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.Top) {
                    // Product Photo Thumbnail with Tap to View
                    val hasPhoto = !product.imageUri.isNullOrBlank()
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(SurfaceWarm)
                            .then(
                                if (hasPhoto && onPhotoClick != null) Modifier.clickable { onPhotoClick() }
                                else Modifier
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (hasPhoto) {
                            AsyncImage(
                                model = com.example.utils.ImageSyncHelper.getImageModel(product.imageUri),
                                contentDescription = product.getDisplayName(isBn),
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(2.dp)
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.55f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.ZoomIn,
                                    contentDescription = "View Photo",
                                    tint = Color.White,
                                    modifier = Modifier.size(11.dp)
                                )
                            }
                        } else {
                            Icon(
                                Icons.Default.Fastfood,
                                contentDescription = null,
                                tint = StorePrimary.copy(alpha = 0.6f),
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = product.getDisplayName(isBn),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${product.category} • Unit: ${product.unitType}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                        if (!product.barcode.isNullOrBlank()) {
                            Surface(
                                color = StorePrimary.copy(alpha = 0.08f),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.padding(top = 3.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Icon(
                                        Icons.Default.QrCode,
                                        contentDescription = null,
                                        tint = StorePrimary,
                                        modifier = Modifier.size(11.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = product.barcode ?: "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = StorePrimary,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 10.5.sp
                                    )
                                }
                            }
                        }
                    }
                }

                val expiryStatus = product.getExpiryStatus()
                val daysLeft = product.getDaysUntilExpiry()

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (isLowStock) {
                        Surface(
                            color = StoreRedPrimary.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = LanguageManager.getString("Low Stock!", "কম স্টক!"),
                                color = StoreRedPrimary,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    when (expiryStatus) {
                        ExpiryStatus.EXPIRED -> {
                            Surface(
                                color = StoreRedAlert,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Icon(Icons.Default.EventBusy, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = if (isBn) "মেয়াদ উত্তীর্ণ (${product.expiryDate})" else "EXPIRED (${product.expiryDate})",
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        ExpiryStatus.EXPIRING_SOON -> {
                            Surface(
                                color = AccentYellowContainer,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Icon(Icons.Default.HourglassBottom, contentDescription = null, tint = AccentYellowText, modifier = Modifier.size(12.dp))
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = if (isBn) "মেয়াদ শেষ $daysLeft দিনে" else "Expiring in $daysLeft days",
                                        color = AccentYellowText,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        ExpiryStatus.FRESH -> {
                            Surface(
                                color = StoreGreenProfit.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "Exp: ${product.expiryDate}",
                                    color = StoreGreenProfit,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        ExpiryStatus.NO_EXPIRY -> {}
                    }

                    if (product.hasMrpDiscount()) {
                        Surface(
                            color = StoreGreenProfit.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "🏷️ -₹%.2f (%.0f%%)".format(product.calculateMrpDiscount(), product.getDiscountPercent()),
                                color = StoreGreenProfit,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    val canSeeCost = com.example.utils.StaffManager.canViewCostPrice()
                    if (canSeeCost) {
                        Text("Cost: ₹%.2f".format(product.costPrice), style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    }
                    val sellUnitLabel = if (product.hasBoxPricing() || product.unitType.equals("box", ignoreCase = true)) {
                        if (isBn) "পিস" else "piece"
                    } else {
                        product.unitType
                    }
                    
                    if (product.hasMrpDiscount()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "MRP: ₹%.2f".format(product.getEffectiveMrp()),
                                style = MaterialTheme.typography.bodySmall.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough),
                                color = TextMuted
                            )
                            Surface(
                                color = StoreGreenProfit.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    "Save ₹%.2f (%.0f%% off)".format(product.calculateMrpDiscount(), product.getDiscountPercent()),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = StoreGreenProfit,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                    
                    Text("Selling: ₹%.2f / $sellUnitLabel".format(product.sellingPrice), fontWeight = FontWeight.Bold, color = StoreSaffronAccent)

                    // Item Profit Badge
                    if (canSeeCost && (product.costPrice > 0.0 || product.sellingPrice > 0.0)) {
                        val profit = product.sellingPrice - product.costPrice
                        val margin = if (product.sellingPrice > 0.0) (profit / product.sellingPrice) * 100.0 else 0.0
                        val isProfitable = profit >= 0.0

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Surface(
                                color = if (isProfitable) StoreGreenProfit.copy(alpha = 0.12f) else StoreRedPrimary.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = if (isProfitable) {
                                        "Profit: ₹%.2f (%.0f%%)".format(profit, margin)
                                    } else {
                                        "Loss: ₹%.2f (%.0f%%)".format(kotlin.math.abs(profit), margin)
                                    },
                                    color = if (isProfitable) StoreGreenProfit else StoreRedPrimary,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }

                    if (product.hasBulkPricing()) {
                        val bPrice = product.getEffectiveBulkPrice()
                        val bQty = product.getEffectiveBulkQuantity()
                        val bUnit = product.getEffectiveBulkUnit()
                        val bQtyStr = if (bQty % 1.0 == 0.0) bQty.toLong().toString() else "%.1f".format(bQty)
                        Text(
                            text = "Bulk: ₹%.2f (%s %s / %s)".format(bPrice, bQtyStr, product.unitType, bUnit),
                            style = MaterialTheme.typography.bodySmall,
                            color = StorePrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (product.wholesalePrice > 0.0) {
                        Text("Wholesale: ₹%.2f".format(product.wholesalePrice), style = MaterialTheme.typography.bodySmall, color = StoreGreenProfit, fontWeight = FontWeight.SemiBold)
                    }
                }

                // Upgraded Stock Health & Status Badge
                val isOutOfStock = product.currentStock <= 0.0
                val stockBadgeBg = when {
                    isOutOfStock -> StoreRedAlert.copy(alpha = 0.12f)
                    isLowStock -> StoreOrangeWarning.copy(alpha = 0.12f)
                    else -> StoreGreenProfit.copy(alpha = 0.10f)
                }
                val stockBadgeBorder = when {
                    isOutOfStock -> StoreRedAlert.copy(alpha = 0.40f)
                    isLowStock -> StoreOrangeWarning.copy(alpha = 0.40f)
                    else -> StoreGreenProfit.copy(alpha = 0.35f)
                }
                val stockTextColor = when {
                    isOutOfStock -> StoreRedAlert
                    isLowStock -> StoreOrangeWarning
                    else -> StoreGreenProfit
                }
                val stockIcon = when {
                    isOutOfStock -> Icons.Default.RemoveCircleOutline
                    isLowStock -> Icons.Default.WarningAmber
                    else -> Icons.Default.CheckCircle
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = stockBadgeBg,
                    border = BorderStroke(1.dp, stockBadgeBorder),
                    modifier = Modifier.clickable { onRestock() }
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = stockIcon,
                                contentDescription = null,
                                tint = stockTextColor,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = if (isOutOfStock) {
                                    if (isBn) "স্টক শেষ (০)" else "Out of Stock"
                                } else {
                                    if (isBn) "স্টক: ${product.getFormattedStockDisplay(true)}"
                                    else "Stock: ${product.getFormattedStockDisplay(false)}"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = stockTextColor,
                                fontSize = 13.sp
                            )
                        }

                        val looseBreakdown = product.getDetailedLooseBreakdown(isBn)
                        if (looseBreakdown != null && !isOutOfStock) {
                            Text(
                                text = "($looseBreakdown)",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextDark.copy(alpha = 0.75f)
                            )
                        } else if (isLowStock && !isOutOfStock) {
                            Text(
                                text = if (isBn) "সর্বনিম্ন: ${Product.formatQuantity(product.lowStockThreshold)} ${BengaliReceiptTranslator.translateUnit(product.unitType, true)}"
                                       else "Min: ${Product.formatQuantity(product.lowStockThreshold)} ${product.unitType}",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                color = StoreOrangeWarning,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // Pack size & barcode variants row (clean full-width row when variants exist)
            val variants = product.getBarcodeVariants()
            if (variants.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    Surface(
                        color = StorePrimary.copy(alpha = 0.10f),
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(0.5.dp, StorePrimary.copy(alpha = 0.3f))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        ) {
                            Icon(Icons.Default.QrCode2, contentDescription = null, tint = StorePrimary, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "${variants.size} " + (if (isBn) "ভ্যারিয়েন্ট" else "Variants"),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.5.sp,
                                color = StorePrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    variants.forEach { v ->
                        Surface(
                            onClick = { onPrintVariant?.invoke(v) },
                            color = SurfaceWarm,
                            shape = RoundedCornerShape(4.dp),
                            border = BorderStroke(0.5.dp, StorePrimary.copy(alpha = 0.35f))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = "${v.getDisplayTitle(product.getDisplayName(isBn))}: ₹%.2f".format(v.price),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.5.sp,
                                    color = TextDark
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.Print,
                                    contentDescription = "Print variant barcode",
                                    tint = StoreGreenProfit,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }
                    // Quick add variant button
                    Surface(
                        onClick = { onAddVariant?.invoke() },
                        color = StorePrimary.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(0.5.dp, StorePrimary.copy(alpha = 0.35f))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = StorePrimary, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = if (isBn) "ভ্যারিয়েন্ট যোগ" else "+ Variant",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.5.sp,
                                color = StorePrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            if (product.hasConversionConflict()) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFFFEF2F2),
                    border = BorderStroke(1.dp, Color(0xFFEF4444)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEdit() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color(0xFFDC2626),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isBn)
                                "⚠️ অনুপাত অমিল: অনুপাত (${product.secondaryUnitRatio.toInt()}) ≠ পুরানো বক্সে পিস (${product.piecesPerBox})। ঠিক করতে ট্যাপ করুন।"
                            else
                                "⚠️ Ratio Conflict: Ratio (${product.secondaryUnitRatio.toInt()}) ≠ Legacy Pieces/Box (${product.piecesPerBox}). Tap to review.",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFB91C1C)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = TextMuted.copy(alpha = 0.15f))
            Spacer(modifier = Modifier.height(8.dp))

            // 1. Primary Inventory Actions (Stock-IN & Stock-OUT with clear touch targets and visual differentiation)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Stock-IN (Restock)
                Surface(
                    onClick = onRestock,
                    shape = RoundedCornerShape(8.dp),
                    color = StoreGreenProfit.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, StoreGreenProfit.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddCircle,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = StoreGreenProfit
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = LanguageManager.getString("Stock-IN", "স্টক ইন"),
                            color = StoreGreenProfit,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Stock-OUT (Deduct / Adjustment)
                Surface(
                    onClick = onStockOut,
                    shape = RoundedCornerShape(8.dp),
                    color = StoreRedAlert.copy(alpha = 0.10f),
                    border = BorderStroke(1.dp, StoreRedAlert.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.RemoveCircleOutline,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = StoreRedAlert
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = LanguageManager.getString("Stock-OUT", "স্টক আউট"),
                            color = StoreRedAlert,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 2. Secondary Operations Bar (Batches, Purchase History, Label, Edit & Delete)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Tracking & Records
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Batches Chip
                    Surface(
                        onClick = onManageBatches,
                        shape = RoundedCornerShape(6.dp),
                        color = StoreSaffronAccent.copy(alpha = 0.10f),
                        border = BorderStroke(0.5.dp, StoreSaffronAccent.copy(alpha = 0.3f)),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Layers,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = StoreSaffronAccent
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = LanguageManager.getString("Batches", "ব্যাচসমূহ"),
                                color = StoreSaffronAccent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Purchase History Chip
                    Surface(
                        onClick = onViewPurchaseHistory,
                        shape = RoundedCornerShape(6.dp),
                        color = StorePrimary.copy(alpha = 0.08f),
                        border = BorderStroke(0.5.dp, StorePrimary.copy(alpha = 0.25f)),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.ReceiptLong,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = StorePrimary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = LanguageManager.getString("History", "ক্রয় খতিয়ান"),
                                color = StorePrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Barcode Label Print Chip
                    Surface(
                        onClick = onPrintBarcode,
                        shape = RoundedCornerShape(6.dp),
                        color = StoreGreenProfit.copy(alpha = 0.10f),
                        border = BorderStroke(0.5.dp, StoreGreenProfit.copy(alpha = 0.35f)),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Print,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = StoreGreenProfit
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = LanguageManager.getString("Label", "লেবেল"),
                                color = StoreGreenProfit,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // Administration (Edit / Delete)
                if (com.example.utils.StaffManager.canManageInventory()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            onClick = onEdit,
                            shape = RoundedCornerShape(6.dp),
                            color = StorePrimary.copy(alpha = 0.08f),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = null,
                                    modifier = Modifier.size(13.dp),
                                    tint = StorePrimary
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = LanguageManager.getString("Edit", "সম্পাদনা"),
                                    color = StorePrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Delete",
                                tint = StoreRedAlert.copy(alpha = 0.8f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ProductFormDialog(
    existingProduct: Product?,
    availableCategories: List<String> = emptyList(),
    isBn: Boolean,
    viewModel: StoreViewModel,
    onDismiss: () -> Unit,
    onSave: (Product) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var nameEn by remember(existingProduct?.id) { mutableStateOf(existingProduct?.nameEn ?: "") }
    var nameBn by remember(existingProduct?.id) { mutableStateOf(existingProduct?.nameBn ?: "") }
    var category by remember(existingProduct?.id) { mutableStateOf(existingProduct?.category ?: "Staples") }
    var unitType by remember(existingProduct?.id) { mutableStateOf(existingProduct?.unitType ?: "piece") }
    var secondaryUnitType by remember(existingProduct?.id) { mutableStateOf(existingProduct?.getEffectiveSecondaryUnit() ?: "piece") }
    var secondaryUnitRatioText by remember(existingProduct?.id) {
        val r = existingProduct?.getEffectiveSecondaryRatio() ?: 1.0
        mutableStateOf(if (r % 1.0 == 0.0) r.toInt().toString() else r.toString())
    }

    val defaultPrimaryUnits = remember {
        listOf("piece", "kg", "litre", "box", "packet", "dozen", "bottle", "meter", "bundle", "strip", "can", "bag")
    }
    val defaultSecondaryUnits = remember {
        listOf("piece", "gram", "ml", "packet", "tablet", "capsule", "strip", "slice", "sheet", "leaf", "meter")
    }

    val storeCustomPrimaryUnits = remember(viewModel.allProducts) {
        viewModel.allProducts.value.map { it.unitType.trim() }
            .filter { it.isNotBlank() && !Product.isKgUnit(it) && !Product.isLitreUnit(it) && it !in defaultPrimaryUnits }
            .distinct()
    }
    val storeCustomSecondaryUnits = remember(viewModel.allProducts) {
        viewModel.allProducts.value.mapNotNull { it.secondaryUnitType?.trim() }
            .filter { it.isNotBlank() && !Product.isGramUnit(it) && !Product.isMlUnit(it) && it !in defaultSecondaryUnits }
            .distinct()
    }

    var isCustomPrimaryUnit by remember(existingProduct?.id) {
        mutableStateOf(
            existingProduct != null &&
            !Product.isKgUnit(unitType) &&
            !Product.isLitreUnit(unitType) &&
            unitType !in defaultPrimaryUnits &&
            unitType !in storeCustomPrimaryUnits
        )
    }
    var customPrimaryUnitText by remember(existingProduct?.id) {
        mutableStateOf(if (isCustomPrimaryUnit) unitType else "")
    }

    var isCustomSecondaryUnit by remember(existingProduct?.id) {
        mutableStateOf(
            existingProduct != null &&
            !Product.isGramUnit(secondaryUnitType) &&
            !Product.isMlUnit(secondaryUnitType) &&
            secondaryUnitType !in defaultSecondaryUnits &&
            secondaryUnitType !in storeCustomSecondaryUnits
        )
    }
    var customSecondaryUnitText by remember(existingProduct?.id) {
        mutableStateOf(if (isCustomSecondaryUnit) secondaryUnitType else "")
    }
    var mrpText by remember(existingProduct?.id, existingProduct?.mrp) {
        mutableStateOf(existingProduct?.mrp?.let { if (it > 0.0) (if (it % 1.0 == 0.0) it.toLong().toString() else it.toString()) else "" } ?: "")
    }
    var costPriceText by remember(existingProduct?.id, existingProduct?.costPrice) {
        mutableStateOf(existingProduct?.costPrice?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: "")
    }
    var sellingPriceText by remember(existingProduct?.id, existingProduct?.sellingPrice) {
        mutableStateOf(existingProduct?.sellingPrice?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: "")
    }
    var wholesalePriceText by remember(existingProduct?.id) { mutableStateOf(if ((existingProduct?.wholesalePrice ?: 0.0) > 0.0) (if (existingProduct!!.wholesalePrice % 1.0 == 0.0) existingProduct!!.wholesalePrice.toLong().toString() else existingProduct!!.wholesalePrice.toString()) else "") }
    var bulkPriceText by remember(existingProduct?.id) {
        val bp = existingProduct?.bulkPrice ?: existingProduct?.boxPrice
        mutableStateOf(if ((bp ?: 0.0) > 0.0) (if (bp!! % 1.0 == 0.0) bp.toLong().toString() else bp.toString()) else "")
    }
    var bulkMrpText by remember(existingProduct?.id) {
        val bmrp = existingProduct?.boxMrp ?: 0.0
        mutableStateOf(if (bmrp > 0.0) (if (bmrp % 1.0 == 0.0) bmrp.toLong().toString() else bmrp.toString()) else "")
    }
    var bulkQuantityText by remember(existingProduct?.id) {
        val bq = existingProduct?.bulkQuantity ?: existingProduct?.piecesPerBox?.toDouble()
        mutableStateOf(if ((bq ?: 0.0) > 0.0) (if (bq!! % 1.0 == 0.0) bq.toLong().toString() else bq.toString()) else "")
    }
    var bulkUnitTypeText by remember(existingProduct?.id) {
        val bu = existingProduct?.bulkUnitType ?: if ((existingProduct?.boxPrice ?: 0.0) > 0.0 || existingProduct?.piecesPerBox != null) "box" else ""
        mutableStateOf(bu)
    }
    var currentStockText by remember { mutableStateOf(existingProduct?.let { Product.formatQuantity(it.currentStock) } ?: "") }
    var lowStockThresholdText by remember { mutableStateOf(existingProduct?.let { Product.formatQuantity(it.lowStockThreshold) } ?: "5") }
    var barcodeText by remember { mutableStateOf(existingProduct?.barcode ?: "") }
    var barcodeVariants by remember { mutableStateOf(existingProduct?.getBarcodeVariants() ?: emptyList()) }
    var editingVariant by remember { mutableStateOf<BarcodeVariant?>(null) }
    var showVariantDialog by remember { mutableStateOf(false) }
    var printVariantTarget by remember { mutableStateOf<BarcodeVariant?>(null) }
    var formValidationError by remember { mutableStateOf<String?>(null) }
    var imageUri by remember { mutableStateOf(existingProduct?.imageUri ?: "") }
    var showEnlargedFormPhoto by remember { mutableStateOf(false) }
    var expiryDate by remember { mutableStateOf(existingProduct?.expiryDate ?: "") }
    var showPresetPhotoPicker by remember { mutableStateOf(false) }
    var showSingleScanner by remember { mutableStateOf(false) }
    var showBarcodePrintModal by remember { mutableStateOf(false) }

    val context = LocalContext.current

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val syncable = com.example.utils.ImageSyncHelper.compressUriToDataUrl(context, it)
            if (!syncable.isNullOrBlank()) {
                imageUri = syncable
            }
        }
    }

    val launchCameraAction = com.example.utils.rememberHighResCameraCapture(
        maxDimension = 1080,
        quality = 85
    ) { dataUrl ->
        imageUri = dataUrl
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (existingProduct == null) LanguageManager.getString("Add New Product", "নতুন পণ্য যোগ করুন")
                else LanguageManager.getString("Edit Product", "পণ্য সম্পাদনা করুন"),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (existingProduct?.hasConversionConflict() == true) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)),
                        border = BorderStroke(1.dp, Color(0xFFEF4444)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = Color(0xFFDC2626),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isBn) "⚠️ রূপান্তর অনুপাত অমিল (ম্যানুয়াল রিভিউ)" else "⚠️ Conversion Ratio Conflict (Manual Review)",
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF991B1B),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isBn)
                                    "এই পণ্যে পূর্বের দুটি ভিন্ন মান ছিল: সাব-ইউনিট অনুপাত = ${existingProduct.secondaryUnitRatio.toInt()} বনাম পুরানো বক্সে পিস = ${existingProduct.piecesPerBox}। এখন 'সাব-ইউনিট অনুপাত' ফিল্ডটিই একমাত্র উৎস। সঠিক মানটি দিয়ে সংরক্ষণ করুন।"
                                else
                                    "This product previously had conflicting conversion values: Sub-Unit Ratio was ${existingProduct.secondaryUnitRatio.toInt()} while legacy Pieces/Box was ${existingProduct.piecesPerBox}. The single 'Sub-Units per Primary Unit' ratio field below is now the only source of truth. Please verify the ratio and tap Save.",
                                fontSize = 11.sp,
                                color = Color(0xFF7F1D1D),
                                lineHeight = 15.sp
                            )
                        }
                    }
                }

                // Item Photo Upload & Picker Box
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceWarm),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (isBn) "পণ্যের ছবি (Item Photo)" else "Item Photo",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextMuted
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        if (imageUri.isNotBlank()) {
                                            showEnlargedFormPhoto = true
                                        } else {
                                            launchCameraAction()
                                        }
                                    },
                                color = Color.White,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.5.dp, if (imageUri.isNotBlank()) StorePrimary else Color(0xFFCBD5E1)),
                                shadowElevation = 1.dp
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (imageUri.isNotBlank()) {
                                        AsyncImage(
                                            model = com.example.utils.ImageSyncHelper.getImageModel(imageUri),
                                            contentDescription = "Item Photo",
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(3.dp)
                                                .size(20.dp)
                                                .clip(CircleShape)
                                                .background(Color.Black.copy(alpha = 0.6f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                Icons.Default.ZoomIn,
                                                contentDescription = "View Photo",
                                                tint = Color.White,
                                                modifier = Modifier.size(13.dp)
                                            )
                                        }
                                    } else {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center,
                                            modifier = Modifier.padding(4.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.AddAPhoto,
                                                contentDescription = "Add Photo",
                                                tint = StorePrimary,
                                                modifier = Modifier.size(22.dp)
                                            )
                                            Spacer(modifier = Modifier.height(3.dp))
                                            Text(
                                                text = if (isBn) "ক্যামেরা" else "Photo",
                                                fontSize = 10.sp,
                                                color = StorePrimary,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Button(
                                        onClick = { launchCameraAction() },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(32.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = StorePrimary),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(13.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (isBn) "ক্যামেরা" else "Camera",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1
                                        )
                                    }

                                    OutlinedButton(
                                        onClick = { photoPickerLauncher.launch("image/*") },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(32.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, Color(0xFFCBD5E1))
                                    ) {
                                        Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(13.dp), tint = TextDark)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (isBn) "গ্যালারি" else "Gallery",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextDark,
                                            maxLines = 1
                                        )
                                    }
                                }

                                OutlinedButton(
                                    onClick = { showPresetPhotoPicker = !showPresetPhotoPicker },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(32.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, StoreSaffronAccent.copy(alpha = 0.5f))
                                ) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(13.dp), tint = StoreSaffronAccent)
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = if (isBn) "নমুনা ছবি বাছাই করুন" else "Preset Sample Photos",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = TextDark,
                                        maxLines = 1
                                    )
                                }

                                if (imageUri.isNotBlank()) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        TextButton(
                                            onClick = {
                                                val enhanced = com.example.utils.ImageSyncHelper.enhanceDataUrl(context, imageUri)
                                                if (!enhanced.isNullOrBlank()) {
                                                    imageUri = enhanced
                                                    Toast.makeText(
                                                        context,
                                                        if (isBn) "✨ ছবির ক্ল্যারিটি ও শার্পনেস বৃদ্ধি করা হয়েছে" else "✨ Photo clarity & sharpness enhanced",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            },
                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                            modifier = Modifier.height(24.dp)
                                        ) {
                                            Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(12.dp), tint = StoreGold)
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text(
                                                text = if (isBn) "শার্প করুন (HD)" else "Sharpen (HD)",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = StoreGold
                                            )
                                        }

                                        TextButton(
                                            onClick = { imageUri = "" },
                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                            modifier = Modifier.height(24.dp)
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(12.dp), tint = StoreRedAlert)
                                            Spacer(modifier = Modifier.width(2.dp))
                                            Text(
                                                text = if (isBn) "ছবি মুছুন" else "Remove Photo",
                                                fontSize = 10.sp,
                                                color = StoreRedAlert
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Preset Photos Quick Select Grid
                        if (showPresetPhotoPicker) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (isBn) "ক্লিক করে দ্রুত ছবি সিলেক্ট করুন:" else "Tap a preset photo to apply:",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextDark,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))

                            val presets = listOf(
                                "Rice" to "https://images.unsplash.com/photo-1586201375761-83865001e31c?w=300",
                                "Dal" to "https://images.unsplash.com/photo-1515543237350-b3eea1ec8082?w=300",
                                "Oil" to "https://images.unsplash.com/photo-1474979266404-7eaacbcd87c5?w=300",
                                "Milk" to "https://images.unsplash.com/photo-1550583724-b2692b85b150?w=300",
                                "Biscuits" to "https://images.unsplash.com/photo-1558961363-fa8fdf82db35?w=300",
                                "Spices" to "https://images.unsplash.com/photo-1596040033229-a9821ebd058d?w=300",
                                "Soap" to "https://images.unsplash.com/photo-1607006411601-775c8cc642b2?w=300",
                                "Drinks" to "https://images.unsplash.com/photo-1622483767028-3f66f32aef97?w=300",
                                "Tea" to "https://images.unsplash.com/photo-1576092768241-dec231879fc3?w=300",
                                "Flour" to "https://images.unsplash.com/photo-1509440159596-0249088772ff?w=300",
                                "Eggs" to "https://images.unsplash.com/photo-1516467508483-a7212febe31a?w=300",
                                "Veggies" to "https://images.unsplash.com/photo-1610832958506-aa56368176cf?w=300"
                            )

                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(presets, key = { it.first }) { (title, url) ->
                                    Card(
                                        modifier = Modifier
                                            .width(60.dp)
                                            .clickable {
                                                imageUri = url
                                                showPresetPhotoPicker = false
                                            },
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(
                                            1.dp,
                                            if (imageUri == url) StorePrimary else Color.Transparent
                                        )
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            AsyncImage(
                                                model = url,
                                                contentDescription = title,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(44.dp),
                                                contentScale = ContentScale.Crop
                                            )
                                            Text(
                                                text = title,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                modifier = Modifier.padding(2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = nameEn,
                    onValueChange = { nameEn = it },
                    label = { Text("English Name (e.g. Minikit Rice)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = nameBn,
                    onValueChange = { nameBn = it },
                    label = { Text("বাংলা নাম (যেমন: মিনিকিট চাল)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Category Selection Section
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = category,
                        onValueChange = { category = it },
                        label = { Text("Category (e.g. Staples, Dairy, Snacks)") },
                        leadingIcon = { Icon(Icons.Default.Category, contentDescription = null, tint = StorePrimary) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (availableCategories.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isBn) "দ্রুত ক্যাটাগরি বেছে নিন:" else "Quick Select Category:",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(availableCategories, key = { it }) { cat ->
                                SuggestionChip(
                                    onClick = { category = cat },
                                    label = { Text(cat) },
                                    colors = SuggestionChipDefaults.suggestionChipColors(
                                        containerColor = if (category.equals(cat, ignoreCase = true)) StorePrimary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                )
                            }
                        }
                    }
                }

                // Barcode / EAN field with camera scanner & auto-generate option
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = barcodeText,
                            onValueChange = { barcodeText = it },
                            label = { Text("Product Barcode / EAN / UPC") },
                            placeholder = { Text("e.g. 8901030000001") },
                            leadingIcon = { Icon(Icons.Default.QrCode, contentDescription = null, tint = StorePrimary) },
                            trailingIcon = {
                                if (barcodeText.isNotEmpty()) {
                                    IconButton(onClick = { barcodeText = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear barcode")
                                    }
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        IconButton(
                            onClick = { showSingleScanner = true },
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .background(StorePrimary.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                                .size(50.dp)
                        ) {
                            Icon(
                                Icons.Default.QrCodeScanner,
                                contentDescription = "Scan Barcode with Camera",
                                tint = StorePrimary
                            )
                        }
                    }

                    if (barcodeText.isBlank()) {
                        TextButton(
                            onClick = {
                                barcodeText = EscPosPrinter.generateValidEan13Barcode()
                            },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(14.dp), tint = StoreSaffronAccent)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isBn) "বারকোড নম্বর স্বয়ংক্রিয় তৈরি করুন" else "Auto-generate barcode number",
                                style = MaterialTheme.typography.labelSmall,
                                color = StoreSaffronAccent
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = {
                                    barcodeText = EscPosPrinter.generateValidEan13Barcode()
                                },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(14.dp), tint = StoreSaffronAccent)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isBn) "নতুন বারকোড তৈরি করুন" else "Re-generate barcode",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StoreSaffronAccent
                                )
                            }

                            Button(
                                onClick = { showBarcodePrintModal = true },
                                colors = ButtonDefaults.buttonColors(containerColor = StoreGreenProfit),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isBn) "প্রিন্ট বারকোড লেবেল" else "Print Barcode Labels",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                if (showBarcodePrintModal && barcodeText.isNotBlank()) {
                    val parsedSell = sellingPriceText.trim().replace(',', '.').toDoubleOrNull() ?: 0.0
                    val parsedMrp = mrpText.trim().replace("₹", "").replace(',', '.').toDoubleOrNull() ?: parsedSell
                    PrintBarcodeModalDialog(
                        productName = nameEn.ifBlank { nameBn },
                        barcodeStr = barcodeText,
                        sellingPrice = parsedSell,
                        mrpPrice = if (parsedMrp > 0.0) parsedMrp else parsedSell,
                        viewModel = viewModel,
                        initialQuantity = "1 ${unitType.replaceFirstChar { it.uppercase() }}",
                        initialExpiryDate = expiryDate.ifBlank { null },
                        product = existingProduct,
                        barcodeVariants = barcodeVariants,
                        onPriceOrMrpChanged = { newSellingPrice, newMrp ->
                            if (newSellingPrice > 0.0) {
                                sellingPriceText = if (newSellingPrice % 1.0 == 0.0) newSellingPrice.toLong().toString() else newSellingPrice.toString()
                            }
                            if (newMrp != null && newMrp > 0.0) {
                                mrpText = if (newMrp % 1.0 == 0.0) newMrp.toLong().toString() else newMrp.toString()
                            }
                        },
                        onDismiss = { showBarcodePrintModal = false }
                    )
                }

                // --- Multi-Barcode Pre-packed Variants Section ---
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceWarm),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, StorePrimary.copy(alpha = 0.2f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f, fill = false),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.QrCode2,
                                    contentDescription = null,
                                    tint = StorePrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isBn) "বারকোড ভ্যারিয়েন্ট (${barcodeVariants.size})" else "Barcode Variants (${barcodeVariants.size})",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = TextDark,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            Button(
                                onClick = {
                                    editingVariant = null
                                    showVariantDialog = true
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = StorePrimary),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isBn) "ভ্যারিয়েন্ট যোগ" else "Add Variant",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isBn)
                                "বিভিন্ন সাইজের প্যাকেটের জন্য আলাদা বারকোড এবং মূল্য সেট করুন (যেমন ৫০০ গ্রাম প্যাকেট = ₹৪০, ১ কেজি = ₹৭৫)। বিলিংয়ের সময় স্ক্যান করলে সরাসরি সঠিক প্যাকেট সিলেক্ট হবে।"
                            else
                                "Map separate barcodes to fixed pack sizes & prices (e.g. 500g pouch, 1L bottle, Box of 12). Scanning automatically bills the exact variant and deducts stock.",
                            fontSize = 11.sp,
                            color = TextMuted,
                            lineHeight = 14.sp
                        )

                        if (barcodeVariants.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                barcodeVariants.forEachIndexed { index, variant ->
                                    val tempProduct = Product(
                                        id = existingProduct?.id ?: "temp",
                                        nameEn = nameEn,
                                        nameBn = nameBn,
                                        category = category,
                                        unitType = unitType,
                                        costPrice = costPriceText.toDoubleOrNull() ?: 0.0,
                                        sellingPrice = sellingPriceText.toDoubleOrNull() ?: 0.0,
                                        currentStock = currentStockText.toDoubleOrNull() ?: 0.0,
                                        secondaryUnitType = secondaryUnitType,
                                        secondaryUnitRatio = secondaryUnitRatioText.toDoubleOrNull() ?: 1.0
                                    )
                                    val baseDeduct = tempProduct.convertQuantityToBaseUnit(variant.quantity, variant.unitType)

                                    Surface(
                                        color = Color.White,
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, StorePrimary.copy(alpha = 0.25f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = variant.getDisplayTitle(nameEn.ifBlank { nameBn.ifBlank { "Variant" } }),
                                                        style = MaterialTheme.typography.labelMedium,
                                                        fontWeight = FontWeight.Bold,
                                                        color = TextDark
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Surface(
                                                        color = StoreGreenProfit.copy(alpha = 0.15f),
                                                        shape = RoundedCornerShape(4.dp)
                                                    ) {
                                                        Text(
                                                            text = "₹%.2f".format(variant.price),
                                                            style = MaterialTheme.typography.labelSmall,
                                                            fontWeight = FontWeight.Bold,
                                                            color = StoreGreenProfit,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }

                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = "Code: ${variant.barcode} • Deducts: %.2f %s base stock".format(baseDeduct, unitType),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontSize = 10.sp,
                                                    color = TextMuted
                                                )
                                            }

                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                IconButton(
                                                    onClick = { printVariantTarget = variant },
                                                    modifier = Modifier.size(28.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.Print,
                                                        contentDescription = "Print Label",
                                                        tint = StoreGreenProfit,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }

                                                IconButton(
                                                    onClick = {
                                                        editingVariant = variant
                                                        showVariantDialog = true
                                                    },
                                                    modifier = Modifier.size(28.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.Edit,
                                                        contentDescription = "Edit Variant",
                                                        tint = StorePrimary,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }

                                                IconButton(
                                                    onClick = {
                                                        barcodeVariants = barcodeVariants.filterIndexed { i, _ -> i != index }
                                                    },
                                                    modifier = Modifier.size(28.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.Delete,
                                                        contentDescription = "Delete Variant",
                                                        tint = StoreRedAlert,
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
                }

                if (printVariantTarget != null) {
                    val target = printVariantTarget!!
                    PrintBarcodeModalDialog(
                        productName = target.getDisplayTitle(nameEn.ifBlank { nameBn }),
                        barcodeStr = target.barcode,
                        sellingPrice = target.price,
                        mrpPrice = target.getEffectiveMrp(),
                        viewModel = viewModel,
                        initialQuantity = target.getShortLabel().ifBlank { "1 N" },
                        initialExpiryDate = expiryDate.ifBlank { null },
                        product = existingProduct,
                        barcodeVariants = barcodeVariants,
                        initialSelectedVariant = target,
                        onDismiss = { printVariantTarget = null }
                    )
                }

                if (formValidationError != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)),
                        border = BorderStroke(1.dp, Color(0xFFEF4444)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Error, contentDescription = null, tint = Color(0xFFDC2626), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = formValidationError!!,
                                color = Color(0xFF991B1B),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Unit Type dropdown/buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isBn) "প্রাইমারি ইউনিট টাইপ:" else "Primary Unit Type:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (isCustomPrimaryUnit) {
                        Surface(
                            color = StorePrimary.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = if (isBn) "কাস্টম ইউনিট সক্রিয়" else "Custom Active",
                                fontSize = 10.sp,
                                color = StorePrimary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    (defaultPrimaryUnits + storeCustomPrimaryUnits).distinct().forEach { unit ->
                        val isSelected = !isCustomPrimaryUnit && (
                            unitType.equals(unit, ignoreCase = true) ||
                            (Product.isLitreUnit(unit) && Product.isLitreUnit(unitType)) ||
                            (Product.isKgUnit(unit) && Product.isKgUnit(unitType))
                        )
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                isCustomPrimaryUnit = false
                                unitType = if (Product.isLitreUnit(unit) && Product.isLitreUnit(unitType)) unitType else unit
                                secondaryUnitType = when {
                                    Product.isKgUnit(unit) -> "gram"
                                    Product.isLitreUnit(unit) -> "ml"
                                    unit.equals("box", ignoreCase = true) -> "piece"
                                    unit.equals("dozen", ignoreCase = true) -> "piece"
                                    unit.equals("strip", ignoreCase = true) -> "tablet"
                                    unit.equals("bundle", ignoreCase = true) -> "piece"
                                    unit.equals("bottle", ignoreCase = true) -> "ml"
                                    unit.equals("meter", ignoreCase = true) -> "cm"
                                    else -> "piece"
                                }
                                secondaryUnitRatioText = when {
                                    Product.isKgUnit(unit) -> "1000.0"
                                    Product.isLitreUnit(unit) -> "1000.0"
                                    unit.equals("box", ignoreCase = true) -> "24.0"
                                    unit.equals("dozen", ignoreCase = true) -> "12.0"
                                    unit.equals("strip", ignoreCase = true) -> "10.0"
                                    unit.equals("bundle", ignoreCase = true) -> "20.0"
                                    unit.equals("bottle", ignoreCase = true) -> "750.0"
                                    unit.equals("meter", ignoreCase = true) -> "100.0"
                                    else -> "1.0"
                                }
                            },
                            label = { Text(BengaliReceiptTranslator.translateUnit(unit, isBn)) }
                        )
                    }

                    // + Custom Primary Unit Chip
                    FilterChip(
                        selected = isCustomPrimaryUnit,
                        onClick = {
                            isCustomPrimaryUnit = true
                            if (customPrimaryUnitText.isNotBlank()) {
                                unitType = customPrimaryUnitText.trim()
                            }
                        },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(if (isBn) "+ কাস্টম ইউনিট" else "+ Custom Unit")
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = StorePrimary.copy(alpha = 0.15f),
                            selectedLabelColor = StorePrimary
                        )
                    )
                }

                if (isCustomPrimaryUnit) {
                    OutlinedTextField(
                        value = customPrimaryUnitText,
                        onValueChange = {
                            customPrimaryUnitText = it
                            val trimmed = it.trim()
                            unitType = trimmed.ifBlank { "piece" }
                            if (Product.isLitreUnit(unitType)) {
                                if (secondaryUnitType.isBlank() || secondaryUnitType == "gram" || secondaryUnitType == "piece") {
                                    secondaryUnitType = "ml"
                                    secondaryUnitRatioText = "1000.0"
                                }
                            } else if (Product.isKgUnit(unitType)) {
                                if (secondaryUnitType.isBlank() || secondaryUnitType == "ml" || secondaryUnitType == "piece") {
                                    secondaryUnitType = "gram"
                                    secondaryUnitRatioText = "1000.0"
                                }
                            }
                        },
                        label = { Text(if (isBn) "কাস্টম প্রাইমারি ইউনিট লিখুন (যেমন: bottle, bundle, roll, strip, can)" else "Enter Custom Primary Unit (e.g. bottle, bundle, roll, strip, can)") },
                        placeholder = { Text("e.g. bottle, bundle, roll, strip, can, jar, bag") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = StorePrimary) },
                        trailingIcon = {
                            if (customPrimaryUnitText.isNotEmpty()) {
                                IconButton(onClick = {
                                    customPrimaryUnitText = ""
                                    unitType = "piece"
                                    isCustomPrimaryUnit = false
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear")
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isBn) "সেকেন্ডারি সাব-ইউনিট টাইপ:" else "Secondary Sub-Unit Type (e.g. gram for kg, ml for litre, piece for box):",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (isCustomSecondaryUnit) {
                        Surface(
                            color = StorePrimary.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = if (isBn) "কাস্টম সাব-ইউনিট সক্রিয়" else "Custom Active",
                                fontSize = 10.sp,
                                color = StorePrimary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    (defaultSecondaryUnits + storeCustomSecondaryUnits).distinct().forEach { sec ->
                        FilterChip(
                            selected = !isCustomSecondaryUnit && secondaryUnitType.equals(sec, ignoreCase = true),
                            onClick = {
                                isCustomSecondaryUnit = false
                                secondaryUnitType = sec
                            },
                            label = { Text(BengaliReceiptTranslator.translateUnit(sec, isBn)) }
                        )
                    }

                    // + Custom Secondary Sub-Unit Chip
                    FilterChip(
                        selected = isCustomSecondaryUnit,
                        onClick = {
                            isCustomSecondaryUnit = true
                            if (customSecondaryUnitText.isNotBlank()) {
                                secondaryUnitType = customSecondaryUnitText.trim()
                            }
                        },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(if (isBn) "+ কাস্টম সাব-ইউনিট" else "+ Custom Sub-Unit")
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = StorePrimary.copy(alpha = 0.15f),
                            selectedLabelColor = StorePrimary
                        )
                    )
                }

                if (isCustomSecondaryUnit) {
                    OutlinedTextField(
                        value = customSecondaryUnitText,
                        onValueChange = {
                            customSecondaryUnitText = it
                            secondaryUnitType = it.trim().ifBlank { "piece" }
                        },
                        label = { Text(if (isBn) "কাস্টম সাব-ইউনিট লিখুন (যেমন: tablet, capsule, leaf, slice, sheet)" else "Enter Custom Sub-Unit (e.g. tablet, capsule, leaf, slice, sheet)") },
                        placeholder = { Text("e.g. tablet, capsule, leaf, slice, sheet") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = StorePrimary) },
                        trailingIcon = {
                            if (customSecondaryUnitText.isNotEmpty()) {
                                IconButton(onClick = {
                                    customSecondaryUnitText = ""
                                    secondaryUnitType = "piece"
                                    isCustomSecondaryUnit = false
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear")
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                val ratioLabel = when {
                    unitType.equals("box", ignoreCase = true) || secondaryUnitType.equals("piece", ignoreCase = true) ->
                        if (isBn) "$unitType প্রতি $secondaryUnitType অনুপাত (যেমন ৩০)" else "$secondaryUnitType per $unitType Ratio (e.g. 30 for $unitType->$secondaryUnitType)"
                    Product.isKgUnit(unitType) ->
                        if (isBn) "সাব-ইউনিট অনুপাত (যেমন ১০০০ g/kg)" else "Sub-Units per Primary Unit (e.g. 1000 for kg->g)"
                    Product.isLitreUnit(unitType) ->
                        if (isBn) "সাব-ইউনিট অনুপাত (যেমন ১০০০ ml/L)" else "Sub-Units per Primary Unit (e.g. 1000 for L->ml)"
                    else ->
                        if (isBn) "$unitType প্রতি $secondaryUnitType অনুপাত (যেমন ১০)" else "Sub-Units per Primary Unit ($secondaryUnitType per $unitType Ratio e.g. 10)"
                }

                OutlinedTextField(
                    value = secondaryUnitRatioText,
                    onValueChange = { secondaryUnitRatioText = it },
                    label = { Text(ratioLabel) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                val sellingPriceLabel = when {
                    secondaryUnitType.equals("piece", ignoreCase = true) || unitType.equals("box", ignoreCase = true) || unitType.equals("dozen", ignoreCase = true) ->
                        if (isBn) "বিক্রয় মূল্য (প্রতি পিস) (₹)" else "Selling Price (per piece)"
                    secondaryUnitType.isNotBlank() && secondaryUnitType != unitType ->
                        if (isBn) "বিক্রয় মূল্য (প্রতি $unitType) (₹)" else "Selling Price (per $unitType)"
                    else ->
                        if (isBn) "বিক্রয় মূল্য (₹)" else "Selling Price (₹)"
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = mrpText,
                        onValueChange = { mrpText = it },
                        label = { Text("MRP (₹) [Max Retail Price]") },
                        placeholder = { Text("Optional / Pack MRP") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )

                    OutlinedTextField(
                        value = costPriceText,
                        onValueChange = { costPriceText = it },
                        label = { Text("Cost Price (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = sellingPriceText,
                    onValueChange = { sellingPriceText = it },
                    label = { Text(sellingPriceLabel) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                // Live Profit & Margin & MRP Discount Indicator Box
                val parsedCost = costPriceText.toDoubleOrNull() ?: 0.0
                val parsedSell = sellingPriceText.toDoubleOrNull() ?: 0.0
                val parsedMrp = mrpText.toDoubleOrNull() ?: 0.0
                if (parsedCost > 0.0 || parsedSell > 0.0 || parsedMrp > 0.0) {
                    val unitProfit = parsedSell - parsedCost
                    val marginPct = if (parsedSell > 0.0) (unitProfit / parsedSell) * 100.0 else 0.0
                    val markupPct = if (parsedCost > 0.0) (unitProfit / parsedCost) * 100.0 else 0.0
                    val isProfit = unitProfit >= 0.0
                    val hasDiscount = parsedMrp > parsedSell && parsedSell > 0.0
                    val discountAmount = if (hasDiscount) parsedMrp - parsedSell else 0.0
                    val discountPct = if (parsedMrp > 0.0) (discountAmount / parsedMrp) * 100.0 else 0.0

                    Surface(
                        color = if (isProfit) StoreGreenProfit.copy(alpha = 0.08f) else StoreRedPrimary.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, if (isProfit) StoreGreenProfit.copy(alpha = 0.3f) else StoreRedPrimary.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        if (isProfit) Icons.Default.TrendingUp else Icons.Default.TrendingDown,
                                        contentDescription = null,
                                        tint = if (isProfit) StoreGreenProfit else StoreRedPrimary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (isProfit) {
                                            if (isBn) "মুনাফা / লাভ (Profit per item):" else "Estimated Profit / Item:"
                                        } else {
                                            if (isBn) "লোকসান / ক্ষতি (Loss per item):" else "Estimated Loss / Item:"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isProfit) StoreGreenProfit else StoreRedPrimary
                                    )
                                }
                                Text(
                                    text = "₹%.2f".format(kotlin.math.abs(unitProfit)),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isProfit) StoreGreenProfit else StoreRedPrimary
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (isBn) "মার্জিন: %.1f%% (বিক্রয়ের উপর)".format(marginPct) else "Margin: %.1f%% (on Sale)".format(marginPct),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 11.sp,
                                    color = if (isProfit) TextMuted else StoreRedPrimary
                                )
                                if (parsedCost > 0.0) {
                                    Text(
                                        text = if (isBn) "মার্কআপ: %.1f%% (কেনার উপর)".format(markupPct) else "Markup: %.1f%% (on Cost)".format(markupPct),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 11.sp,
                                        color = TextMuted
                                    )
                                }
                            }

                            if (hasDiscount) {
                                Spacer(modifier = Modifier.height(4.dp))
                                HorizontalDivider(color = StoreGreenProfit.copy(alpha = 0.2f), thickness = 0.5.dp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Customer Discount (MRP ₹%.2f):".format(parsedMrp),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = StoreGreenProfit
                                    )
                                    Text(
                                        text = "Save ₹%.2f (%.1f%% off)".format(discountAmount, discountPct),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = StoreGreenProfit
                                    )
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = wholesalePriceText,
                    onValueChange = { wholesalePriceText = it },
                    label = { Text("Wholesale Rate (₹)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = currentStockText,
                        onValueChange = { currentStockText = it },
                        label = { Text("Initial Stock ($unitType)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )

                    OutlinedTextField(
                        value = lowStockThresholdText,
                        onValueChange = { lowStockThresholdText = it },
                        label = { Text("Low Stock Alert") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }

                // Bulk / Pack Pricing (Optional) Card - Available for ALL products (kg, litre, piece, box, etc.)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceWarm),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Inventory2, contentDescription = null, tint = StorePrimary, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isBn) "বাল্ক / প্যাক বিশেষ মূল্য (Bulk Price - ঐচ্ছিক)" else "Bulk / Pack Pricing (Optional)",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TextDark
                                )
                            }
                            if (bulkPriceText.isNotBlank() || bulkQuantityText.isNotBlank() || bulkUnitTypeText.isNotBlank()) {
                                TextButton(
                                    onClick = {
                                        bulkPriceText = ""
                                        bulkQuantityText = ""
                                        bulkUnitTypeText = ""
                                    },
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text(
                                        if (isBn) "মুছুন" else "Clear",
                                        fontSize = 11.sp,
                                        color = StoreRedAlert
                                    )
                                }
                            }
                        }

                        // Reordered Flow per PRD:
                        // 1. Bulk Unit (tin/jar/can/bottle/bag/box — existing suggestion chips)
                        // 2. Pack Size — "How many litres are in one tin?" (with smart defaults on chip tap)
                        // 3. Price per {Bulk Unit} (₹)
                        // 4. Live computed summary (plain language) & Advisory sanity-check warning banners

                        Spacer(modifier = Modifier.height(4.dp))

                        // 1. Bulk Unit Field
                        OutlinedTextField(
                            value = bulkUnitTypeText,
                            onValueChange = { bulkUnitTypeText = it },
                            label = { Text(if (isBn) "বাল্ক ইউনিট (Bulk Unit)" else "Bulk Unit") },
                            placeholder = {
                                val defaultUnitHint = when {
                                    Product.isKgUnit(unitType) -> "bag"
                                    Product.isLitreUnit(unitType) -> "tin"
                                    else -> "box"
                                }
                                Text(defaultUnitHint)
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Suggestion Chips with Smart Defaults
                        val suggestedBulkUnits = when {
                            Product.isKgUnit(unitType) || Product.isGramUnit(unitType) -> listOf("bag", "sack", "packet", "kg", "box")
                            Product.isLitreUnit(unitType) || Product.isMlUnit(unitType) -> listOf("tin", "jar", "can", "bottle", "litre", "box")
                            else -> listOf("box", "case", "carton", "packet", "strip", "piece")
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isBn) "প্রস্তাবিত ইউনিট:" else "Suggested:",
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                            suggestedBulkUnits.forEach { u ->
                                val isSelected = bulkUnitTypeText.equals(u, ignoreCase = true)
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        bulkUnitTypeText = u
                                        // Smart defaults per container (editable, not forced) if field is still empty
                                        if (bulkQuantityText.isBlank()) {
                                            val defaultQty = when (u.lowercase().trim()) {
                                                "tin" -> "15"
                                                "jar" -> "1"
                                                "bottle" -> "1"
                                                "can" -> "5"
                                                "bag" -> "25"
                                                "sack" -> "50"
                                                "packet" -> "1"
                                                "box" -> "12"
                                                "case" -> "24"
                                                "carton" -> "24"
                                                "strip" -> "10"
                                                "piece", "kg", "litre" -> "1"
                                                else -> null
                                            }
                                            if (defaultQty != null) {
                                                bulkQuantityText = defaultQty
                                            }
                                        }
                                    },
                                    label = { Text(BengaliReceiptTranslator.translateUnit(u, isBn), fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = StorePrimary.copy(alpha = 0.15f),
                                        selectedLabelColor = StorePrimary
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // 2. Pack Size Field with dynamic helper text pulling product's base unit
                        val displayBaseUnitPlural = if (isBn) {
                            BengaliReceiptTranslator.translateUnit(unitType, true)
                        } else {
                            when (unitType.lowercase().trim()) {
                                "litre", "liter", "l" -> "litres"
                                "kg" -> "kg"
                                "piece", "pc", "pcs" -> "pcs"
                                "gram", "g" -> "grams"
                                "ml" -> "ml"
                                "meter", "m" -> "meters"
                                "box" -> "boxes"
                                "packet", "pkt" -> "packets"
                                else -> unitType
                            }
                        }
                        val displayBulkUnitName = bulkUnitTypeText.trim().ifBlank {
                            if (isBn) "প্যাক" else "pack"
                        }
                        val displayBulkUnitNameBn = BengaliReceiptTranslator.translateUnit(displayBulkUnitName, true)
                        val packSizeHelperText = if (isBn) {
                            "একটি $displayBulkUnitNameBn-এ কত $displayBaseUnitPlural থাকে?"
                        } else {
                            "How many $displayBaseUnitPlural are in one $displayBulkUnitName?"
                        }

                        OutlinedTextField(
                            value = bulkQuantityText,
                            onValueChange = { bulkQuantityText = it },
                            label = { Text(if (isBn) "প্যাক সাইজ (Pack Size)" else "Pack Size") },
                            placeholder = {
                                val defaultQtyHint = when {
                                    Product.isKgUnit(unitType) -> "25"
                                    Product.isLitreUnit(unitType) -> "15"
                                    else -> "12"
                                }
                                Text(defaultQtyHint)
                            },
                            supportingText = {
                                Text(
                                    text = packSizeHelperText,
                                    fontSize = 11.sp,
                                    color = StorePrimary,
                                    fontWeight = FontWeight.Medium
                                )
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        // 3. Price per {Bulk Unit} (₹)
                        val priceFieldLabel = if (bulkUnitTypeText.isNotBlank()) {
                            val capitalizedUnit = bulkUnitTypeText.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                            if (isBn) {
                                "${BengaliReceiptTranslator.translateUnit(bulkUnitTypeText.trim(), true)} প্রতি মূল্য (₹)"
                            } else {
                                "Price per $capitalizedUnit (₹)"
                            }
                        } else {
                            if (isBn) "প্যাক প্রতি মূল্য (₹)" else "Price per Pack (₹)"
                        }

                        OutlinedTextField(
                            value = bulkPriceText,
                            onValueChange = { 
                                bulkPriceText = it
                                // Auto-calculate piece price if pack size is set
                                val bPrice = it.toDoubleOrNull()
                                val bQty = bulkQuantityText.toDoubleOrNull()
                                if (bPrice != null && bQty != null && bQty > 0.0) {
                                    sellingPriceText = (bPrice / bQty).toString()
                                }
                            },
                            label = { Text(priceFieldLabel) },
                            placeholder = { Text("e.g. 2400.0") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Box MRP Field
                        OutlinedTextField(
                            value = bulkMrpText,
                            onValueChange = {
                                bulkMrpText = it
                                // Auto-calculate piece MRP if pack size is set
                                val bMrp = it.toDoubleOrNull()
                                val bQty = bulkQuantityText.toDoubleOrNull()
                                if (bMrp != null && bQty != null && bQty > 0.0) {
                                    mrpText = (bMrp / bQty).toString()
                                }
                            },
                            label = { Text(if (isBn) "বক্স MRP (₹)" else "Box MRP (₹)") },
                            placeholder = { Text("e.g. 3000.0") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )

                        // 4. Dynamic Live Computed Summary & Advisory Sanity Warnings
                        val bPrice = bulkPriceText.toDoubleOrNull()
                        val bQty = bulkQuantityText.toDoubleOrNull()
                        val regularPrice = sellingPriceText.toDoubleOrNull()
                        val costPrice = costPriceText.toDoubleOrNull()

                        if (bPrice != null && bPrice > 0.0 && bQty != null && bQty > 0.0) {
                            Spacer(modifier = Modifier.height(8.dp))
                            val targetUnit = bulkUnitTypeText.trim().ifBlank { if (isBn) "প্যাক" else "pack" }
                            val targetUnitBn = BengaliReceiptTranslator.translateUnit(targetUnit, true)
                            val regularTotal = (regularPrice ?: 0.0) * bQty
                            val customerSavings = regularTotal - bPrice
                            val effectiveRatePerUnit = bPrice / bQty
                            val profit = if (costPrice != null && costPrice > 0.0) bPrice - (costPrice * bQty) else null
                            val marginPercent = if (profit != null && bPrice > 0.0) (profit / bPrice) * 100.0 else null

                            val qtyFormatted = if (bQty % 1.0 == 0.0) bQty.toLong().toString() else "%.2f".format(bQty)
                            val priceFormatted = if (bPrice % 1.0 == 0.0) bPrice.toLong().toString() else "%.2f".format(bPrice)
                            val rateFormatted = if (effectiveRatePerUnit % 1.0 == 0.0) effectiveRatePerUnit.toLong().toString() else "%.2f".format(effectiveRatePerUnit)
                            val baseUnitDisplay = when (unitType.lowercase().trim()) {
                                "litre", "liter" -> if (isBn) "লিটার" else "L"
                                "kg" -> if (isBn) "কেজি" else "kg"
                                "piece", "pcs" -> if (isBn) "পিস" else "pcs"
                                "gram", "g" -> if (isBn) "গ্রাম" else "g"
                                "ml" -> if (isBn) "মিলি" else "ml"
                                else -> if (isBn) BengaliReceiptTranslator.translateUnit(unitType, true) else unitType
                            }
                            val unitRateLabel = if (isBn) BengaliReceiptTranslator.translateUnit(unitType, true) else unitType

                            val plainSummaryText = if (isBn) {
                                "১ $targetUnitBn ($qtyFormatted $baseUnitDisplay) - ₹$priceFormatted → ₹$rateFormatted/$unitRateLabel"
                            } else {
                                "1 $targetUnit ($qtyFormatted $baseUnitDisplay) for ₹$priceFormatted → ₹$rateFormatted/$unitRateLabel"
                            }

                            Surface(
                                color = StorePrimary.copy(alpha = 0.07f),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(0.5.dp, StorePrimary.copy(alpha = 0.25f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(
                                        text = plainSummaryText,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = StorePrimary
                                    )
                                    if (regularTotal > bPrice && customerSavings > 0.0) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        val savePercent = (customerSavings / regularTotal) * 100.0
                                        Text(
                                            text = if (isBn)
                                                "গ্রাহক সাশ্রয়: ₹%.2f (%.1f%% ছাড় | নিয়মিত মূল্য: ₹%.2f)".format(customerSavings, savePercent, regularTotal)
                                            else
                                                "Customer Saves: ₹%.2f (%.1f%% OFF vs regular ₹%.2f)".format(customerSavings, savePercent, regularTotal),
                                            fontSize = 11.sp,
                                            color = StoreGreenProfit,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    if (profit != null && marginPercent != null) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = if (isBn)
                                                "প্যাকে আনুমানিক লাভ: ₹%.2f (মার্জিন: %.1f%%)".format(profit, marginPercent)
                                            else
                                                "Estimated Profit: ₹%.2f (Margin: %.1f%%)".format(profit, marginPercent),
                                            fontSize = 10.sp,
                                            color = if (profit >= 0.0) StoreGreenProfit else StoreRedAlert
                                        )
                                    }
                                }
                            }

                            // Advisory Sanity-check warning banners
                            val wholesaleRate = wholesalePriceText.toDoubleOrNull()?.takeIf { it > 0.0 }
                            val normalRateToCompare = wholesaleRate ?: regularPrice?.takeIf { it > 0.0 }
                            val isRateHigherThanNormal = normalRateToCompare != null && effectiveRatePerUnit > normalRateToCompare
                            val isHighMargin = marginPercent != null && marginPercent > 70.0

                            if (isRateHigherThanNormal) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Surface(
                                    color = Color(0xFFFFF3E0),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, Color(0xFFFFB74D)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(8.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Icon(
                                            Icons.Default.WarningAmber,
                                            contentDescription = null,
                                            tint = Color(0xFFE65100),
                                            modifier = Modifier.size(16.dp).padding(top = 1.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (isBn)
                                                "⚠️ এই বাল্ক রেট আপনার সাধারণ রেটের চেয়ে বেশি — বাল্ক মূল্যে সাধারণত বড় পরিমাণের জন্য ছাড় দেওয়া হয়। প্যাক সাইজ (Pack Size) পুনরায় যাচাই করুন।"
                                            else
                                                "⚠️ This bulk rate is higher than your normal rate — bulk pricing is usually a discount for larger quantities. Double-check Pack Size.",
                                            fontSize = 11.sp,
                                            color = Color(0xFFBF360C),
                                            fontWeight = FontWeight.Medium,
                                            lineHeight = 15.sp
                                        )
                                    }
                                }
                            }

                            if (isHighMargin) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Surface(
                                    color = Color(0xFFFFF3E0),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, Color(0xFFFFB74D)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(8.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Icon(
                                            Icons.Default.WarningAmber,
                                            contentDescription = null,
                                            tint = Color(0xFFE65100),
                                            modifier = Modifier.size(16.dp).padding(top = 1.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (isBn)
                                                "⚠️ অস্বাভাবিক বেশি মার্জিন (%.1f%%) — সাধারণত প্যাক সাইজ ভুলবশত কম দিলে এমন হয়। প্যাক সাইজ (Pack Size) পুনরায় যাচাই করুন।".format(marginPercent)
                                            else
                                                "⚠️ Unusually high margin (%.1f%%) — this usually means Pack Size is too small for the price entered. Double-check Pack Size.".format(marginPercent),
                                            fontSize = 11.sp,
                                            color = Color(0xFFBF360C),
                                            fontWeight = FontWeight.Medium,
                                            lineHeight = 15.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Expiry Date Management Box
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceWarm),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isBn) "মেয়াদ শেষের তারিখ (Expiry Date)" else "Expiry Date (YYYY-MM-DD)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextDark
                            )
                            if (expiryDate.isNotBlank()) {
                                TextButton(
                                    onClick = { expiryDate = "" },
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text(
                                        if (isBn) "মুছুন" else "Clear",
                                        fontSize = 11.sp,
                                        color = StoreRedAlert
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        OutlinedTextField(
                            value = expiryDate,
                            onValueChange = { expiryDate = it },
                            placeholder = { Text("YYYY-MM-DD") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                IconButton(onClick = {
                                    val cal = java.util.Calendar.getInstance()
                                    if (expiryDate.isNotBlank()) {
                                        try {
                                            val parts = expiryDate.split("-")
                                            if (parts.size == 3) {
                                                cal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
                                            }
                                        } catch (e: Exception) {}
                                    }
                                    android.app.DatePickerDialog(
                                        context,
                                        { _, y, m, d ->
                                            expiryDate = String.format("%04d-%02d-%02d", y, m + 1, d)
                                        },
                                        cal.get(java.util.Calendar.YEAR),
                                        cal.get(java.util.Calendar.MONTH),
                                        cal.get(java.util.Calendar.DAY_OF_MONTH)
                                    ).show()
                                }) {
                                    Icon(Icons.Default.CalendarToday, contentDescription = "Pick Date", tint = StorePrimary)
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = if (isBn) "দ্রুত মেয়াদের দিন সিলেক্ট করুন:" else "Quick Expiry Presets:",
                            fontSize = 11.sp,
                            color = TextMuted
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            val today = java.time.LocalDate.now()
                            listOf(
                                ("+1 Month" to today.plusMonths(1)),
                                ("+3 Months" to today.plusMonths(3)),
                                ("+6 Months" to today.plusMonths(6)),
                                ("+1 Year" to today.plusYears(1))
                            ).forEach { (label, targetDate) ->
                                OutlinedButton(
                                    onClick = { expiryDate = targetDate.toString() },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(label, fontSize = 10.sp)
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
                    formValidationError = null
                    val cleanPrimaryBarcode = barcodeText.trim().ifBlank { null }
                    val targetProdId = existingProduct?.id ?: ("prod_" + UUID.randomUUID().toString().take(8))

                    // 1. Validate primary barcode uniqueness
                    if (cleanPrimaryBarcode != null) {
                        val (available, reason) = viewModel.isBarcodeAvailableAcrossCatalog(
                            barcode = cleanPrimaryBarcode,
                            excludeProductId = existingProduct?.id
                        )
                        if (!available) {
                            formValidationError = "Primary Barcode Error: $reason"
                            return@Button
                        }
                    }

                    // 2. Validate variant barcodes uniqueness against each other, primary barcode, and catalog
                    val seenVariantCodes = mutableSetOf<String>()
                    for (variant in barcodeVariants) {
                        val code = variant.barcode.trim()
                        if (code.isBlank()) {
                            formValidationError = "A variant barcode cannot be blank"
                            return@Button
                        }
                        if (cleanPrimaryBarcode != null && code.equals(cleanPrimaryBarcode, ignoreCase = true)) {
                            formValidationError = "Variant barcode '$code' is the same as the primary barcode. Use distinct barcodes."
                            return@Button
                        }
                        if (code in seenVariantCodes) {
                            formValidationError = "Duplicate variant barcode '$code' found in this product's variants list."
                            return@Button
                        }
                        seenVariantCodes.add(code)

                        val (available, reason) = viewModel.isBarcodeAvailableAcrossCatalog(
                            barcode = code,
                            excludeProductId = existingProduct?.id,
                            excludeVariantBarcode = code
                        )
                        if (!available) {
                            formValidationError = "Variant Barcode '$code': $reason"
                            return@Button
                        }
                    }

                    val cost = costPriceText.trim().replace(',', '.').toDoubleOrNull() ?: 0.0
                    val selling = sellingPriceText.trim().replace(',', '.').toDoubleOrNull() ?: 0.0
                    val mrpVal = mrpText.trim().replace("₹", "").replace(',', '.').toDoubleOrNull()
                    val wholesale = wholesalePriceText.toDoubleOrNull() ?: 0.0
                    val stock = Product.roundQuantity(currentStockText.trim().replace(',', '.').toDoubleOrNull() ?: 0.0)
                    val threshold = Product.roundQuantity(lowStockThresholdText.trim().replace(',', '.').toDoubleOrNull() ?: 5.0)
                    val ratio = secondaryUnitRatioText.toDoubleOrNull() ?: 1.0
                    val effectivePrimaryUnit = if (isCustomPrimaryUnit && customPrimaryUnitText.isNotBlank()) {
                        customPrimaryUnitText.trim()
                    } else {
                        unitType.trim().ifBlank { "piece" }
                    }
                    val effectiveSecondaryUnit = if (isCustomSecondaryUnit && customSecondaryUnitText.isNotBlank()) {
                        customSecondaryUnitText.trim()
                    } else if (Product.isLitreUnit(effectivePrimaryUnit) && (secondaryUnitType.isBlank() || secondaryUnitType.equals("gram", ignoreCase = true) || secondaryUnitType.equals("piece", ignoreCase = true))) {
                        "ml"
                    } else if (Product.isKgUnit(effectivePrimaryUnit) && (secondaryUnitType.isBlank() || secondaryUnitType.equals("ml", ignoreCase = true) || secondaryUnitType.equals("piece", ignoreCase = true))) {
                        "gram"
                    } else {
                        secondaryUnitType.trim().ifBlank { "piece" }
                    }
                    val effectiveRatio = if ((Product.isLitreUnit(effectivePrimaryUnit) && Product.isMlUnit(effectiveSecondaryUnit)) ||
                        (Product.isKgUnit(effectivePrimaryUnit) && Product.isGramUnit(effectiveSecondaryUnit))) {
                        if (ratio <= 1.0) 1000.0 else ratio
                    } else {
                        ratio
                    }
                    val isLoose = Product.isLooseUnit(effectivePrimaryUnit)
                    val rawBulkPrice = bulkPriceText.toDoubleOrNull()
                    val rawBulkMrp = bulkMrpText.toDoubleOrNull()
                    val rawBulkQty = bulkQuantityText.toDoubleOrNull()?.coerceAtLeast(0.01)
                    val rawBulkUnit = bulkUnitTypeText.trim().ifBlank { null }

                    val finalBulkPrice = if (rawBulkPrice != null && rawBulkPrice > 0.0) rawBulkPrice else null
                    val finalBulkMrp = if (rawBulkMrp != null && rawBulkMrp > 0.0) rawBulkMrp else null
                    val finalBulkQty = if (finalBulkPrice != null) (rawBulkQty ?: if (!isLoose && effectiveRatio > 1.0) effectiveRatio else 1.0) else null
                    val finalBulkUnit = if (finalBulkPrice != null) (rawBulkUnit ?: if (isLoose) (if (Product.isKgUnit(effectivePrimaryUnit)) "bag" else "tin") else "box") else null

                    val piecesPerBox = if (finalBulkQty != null && finalBulkQty >= 1.0) finalBulkQty.toInt() else if (!isLoose && effectivePrimaryUnit.equals("box", ignoreCase = true)) effectiveRatio.toInt().coerceAtLeast(1) else null
                    val finalBoxPrice = finalBulkPrice

                    val product = Product(
                        id = targetProdId,
                        nameEn = nameEn.ifBlank { nameBn.ifBlank { "Item" } },
                        nameBn = nameBn.ifBlank { nameEn.ifBlank { "Item" } },
                        category = category.ifBlank { "General" },
                        unitType = effectivePrimaryUnit,
                        costPrice = cost,
                        sellingPrice = selling,
                        currentStock = stock,
                        lowStockThreshold = threshold,
                        barcode = cleanPrimaryBarcode,
                        barcodeVariantsJson = BarcodeVariant.listToJson(barcodeVariants),
                        secondaryUnitType = effectiveSecondaryUnit,
                        secondaryUnitRatio = effectiveRatio,
                        imageUri = com.example.utils.ImageSyncHelper.processImageUriForSync(context, imageUri.trim().ifBlank { null }),
                        expiryDate = expiryDate.trim().ifBlank { null },
                        wholesalePrice = wholesale,
                        wholesaleMinQty = 0.0,
                        piecesPerBox = piecesPerBox,
                        boxPrice = finalBoxPrice,
                        boxMrp = finalBulkMrp,
                        mrp = mrpVal,
                        bulkUnitType = finalBulkUnit,
                        bulkQuantity = finalBulkQty,
                        bulkPrice = finalBulkPrice
                    )
                    onSave(product)
                },
                colors = ButtonDefaults.buttonColors(containerColor = StoreRedPrimary)
            ) {
                Text(LanguageManager.getString("Save", "সংরক্ষণ করুন"))
            }
        },
        dismissButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (existingProduct != null && onDelete != null && com.example.utils.StaffManager.canManageInventory()) {
                    TextButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.textButtonColors(contentColor = StoreRedAlert)
                    ) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = StoreRedAlert
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(LanguageManager.getString("Delete", "মুছুন"), fontWeight = FontWeight.SemiBold)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(LanguageManager.getString("Cancel", "বাতিল"))
                }
            }
        }
    )

    if (showSingleScanner) {
        SingleBarcodeScannerDialog(
            onBarcodeCaptured = { code ->
                barcodeText = code
                showSingleScanner = false
            },
            onDismiss = { showSingleScanner = false }
        )
    }

    if (showVariantDialog) {
        val tempProduct = Product(
            id = existingProduct?.id ?: "temp",
            nameEn = nameEn,
            nameBn = nameBn,
            category = category,
            unitType = unitType,
            costPrice = costPriceText.toDoubleOrNull() ?: 0.0,
            sellingPrice = sellingPriceText.toDoubleOrNull() ?: 0.0,
            currentStock = currentStockText.toDoubleOrNull() ?: 0.0,
            secondaryUnitType = secondaryUnitType,
            secondaryUnitRatio = secondaryUnitRatioText.toDoubleOrNull() ?: 1.0
        )
        BarcodeVariantEditDialog(
            initialVariant = editingVariant,
            baseProduct = tempProduct,
            primaryBarcode = barcodeText.trim().ifBlank { null },
            existingVariants = barcodeVariants,
            viewModel = viewModel,
            isBn = isBn,
            onDismiss = { showVariantDialog = false },
            onSaveVariant = { newVariant ->
                val currentList = barcodeVariants.toMutableList()
                if (editingVariant != null) {
                    val idx = currentList.indexOfFirst { it.barcode == editingVariant!!.barcode }
                    if (idx >= 0) {
                        currentList[idx] = newVariant
                    } else {
                        currentList.add(newVariant)
                    }
                } else {
                    currentList.add(newVariant)
                }
                barcodeVariants = currentList
                showVariantDialog = false
            }
        )
    }

    if (showEnlargedFormPhoto && imageUri.isNotBlank()) {
        com.example.ui.components.ZoomablePaymentScreenshotDialog(
            imageModel = com.example.utils.ImageSyncHelper.getImageModel(imageUri),
            title = if (nameEn.isNotBlank()) nameEn else (if (isBn) "পণ্যের ছবি" else "Product Photo"),
            subtitle = if (category.isNotBlank()) "$category • $unitType" else null,
            onDismiss = { showEnlargedFormPhoto = false }
        )
    }
}

@Composable
fun DeleteProductConfirmationDialog(
    product: Product,
    isBn: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val displayName = if (isBn && product.nameBn.isNotBlank()) product.nameBn else product.nameEn
    val hasActiveStock = product.currentStock > 0.0
    val requiresPin = !com.example.utils.StaffManager.isOwner()
    var enteredPin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Surface(
                shape = CircleShape,
                color = StoreRedAlert.copy(alpha = 0.12f),
                modifier = Modifier.size(52.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.DeleteForever,
                        contentDescription = null,
                        tint = StoreRedAlert,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        },
        title = {
            Text(
                text = if (isBn) "পণ্য মুছবেন?" else "Delete Product?",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = if (isBn)
                        "আপনি কি নিশ্চিত যে আপনি '$displayName' পণ্যটি মুছে ফেলতে চান?"
                    else
                        "Are you sure you want to delete '$displayName'?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextDark
                )

                // Product summary preview
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceWarm),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (isBn) "ক্যাটাগরি:" else "Category:",
                                fontSize = 12.sp,
                                color = TextMuted
                            )
                            Text(
                                text = product.category,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextDark
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (isBn) "বর্তমান স্টক:" else "Current Stock:",
                                fontSize = 12.sp,
                                color = TextMuted
                            )
                            Text(
                                text = product.getFormattedStockDisplay(isBn),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (hasActiveStock) StorePrimary else TextMuted
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (isBn) "বিক্রয় মূল্য:" else "Selling Price:",
                                fontSize = 12.sp,
                                color = TextMuted
                            )
                            Text(
                                text = "₹%.2f".format(product.sellingPrice),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = StoreGreenProfit
                            )
                        }
                    }
                }

                // Active stock warning
                if (hasActiveStock) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)),
                        border = BorderStroke(1.dp, Color(0xFFFCA5A5)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = StoreRedAlert,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isBn)
                                    "সতর্কতা: এই পণ্যের বর্তমানে ${product.getFormattedStockDisplay(isBn)} স্টক আছে। মুছে ফেললে সমস্ত স্টক ও ব্যাচ স্থায়ীভাবে ডিলিট হয়ে যাবে।"
                                else
                                    "Warning: This item still has active stock (${product.getFormattedStockDisplay(false)}). Deleting it will permanently remove all associated batches and stock history.",
                                fontSize = 11.sp,
                                color = Color(0xFF991B1B),
                                lineHeight = 14.sp
                            )
                        }
                    }
                }

                // Owner PIN verification for non-owner staff
                if (requiresPin) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (isBn) "মালিকের পিন (Owner PIN) প্রবেশ করুন:" else "Enter Owner PIN to authorize deletion:",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextDark
                    )
                    OutlinedTextField(
                        value = enteredPin,
                        onValueChange = {
                            enteredPin = it
                            pinError = false
                        },
                        placeholder = { Text("4-digit PIN") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        isError = pinError,
                        supportingText = if (pinError) {
                            {
                                Text(
                                    text = if (isBn) "ভুল পিন! সঠিক মালিকের পিন দিন।" else "Incorrect PIN! Please enter the correct Owner PIN.",
                                    color = StoreRedAlert
                                )
                            }
                        } else null,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (requiresPin) {
                        if (com.example.utils.StaffManager.verifyOwnerPin(enteredPin)) {
                            onConfirm()
                        } else {
                            pinError = true
                        }
                    } else {
                        onConfirm()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = StoreRedAlert)
            ) {
                Icon(
                    Icons.Default.DeleteForever,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (isBn) "হ্যাঁ, মুছুন" else "Delete Permanently",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(LanguageManager.getString("Cancel", "বাতিল"))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BarcodeVariantEditDialog(
    initialVariant: BarcodeVariant?,
    baseProduct: Product,
    primaryBarcode: String?,
    existingVariants: List<BarcodeVariant>,
    viewModel: StoreViewModel,
    isBn: Boolean,
    onDismiss: () -> Unit,
    onSaveVariant: (BarcodeVariant) -> Unit
) {
    var barcode by remember { mutableStateOf(initialVariant?.barcode ?: "") }
    var unitType by remember { mutableStateOf(initialVariant?.unitType ?: baseProduct.getEffectiveSecondaryUnit()) }
    var quantityText by remember { mutableStateOf(initialVariant?.quantity?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() } ?: "") }
    var priceText by remember { mutableStateOf(initialVariant?.price?.let { if (it > 0.0) it.toString() else "" } ?: "") }
    var mrpText by remember { mutableStateOf(initialVariant?.mrp?.let { if (it > 0.0) it.toString() else "" } ?: "") }
    var costPriceText by remember { mutableStateOf(initialVariant?.costPrice?.let { if (it > 0.0) it.toString() else "" } ?: "") }
    var packagingUnit by remember { mutableStateOf(initialVariant?.packagingUnit?.ifBlank { "pack" } ?: "pack") }
    var label by remember { mutableStateOf(initialVariant?.label ?: "") }
    var showScanner by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }

    val availableUnits = remember(baseProduct) {
        val list = mutableListOf<String>()
        list.add(baseProduct.unitType)
        if (baseProduct.getEffectiveSecondaryUnit() !in list) {
            list.add(baseProduct.getEffectiveSecondaryUnit())
        }
        listOf("gram", "kg", "ml", "litre", "piece", "packet", "box", "dozen", "tablet", "capsule", "strip", "bottle").forEach {
            if (it !in list) list.add(it)
        }
        list
    }

    var isCustomVariantUnit by remember { mutableStateOf(unitType !in availableUnits) }
    var customVariantUnitText by remember { mutableStateOf(if (isCustomVariantUnit) unitType else "") }

    val parsedQty = quantityText.toDoubleOrNull() ?: 0.0
    val convertedBaseQty = if (parsedQty > 0.0) {
        baseProduct.convertQuantityToBaseUnit(parsedQty, unitType)
    } else 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (initialVariant == null)
                    (if (isBn) "প্রি-প্যাকড ভ্যারিয়েন্ট বারকোড যোগ করুন" else "Add Pre-packed Barcode Variant")
                else
                    (if (isBn) "ভ্যারিয়েন্ট সম্পাদনা করুন" else "Edit Barcode Variant"),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (isBn)
                        "একটি নির্দিষ্ট মাপের প্যাকেটের জন্য বারকোড, পরিমাণ এবং বিক্রয় মূল্য দিন।"
                    else
                        "Map a unique barcode to a fixed pack quantity, unit, and selling price.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )

                // Barcode input
                OutlinedTextField(
                    value = barcode,
                    onValueChange = {
                        barcode = it
                        validationError = null
                    },
                    label = { Text("Variant Barcode *") },
                    placeholder = { Text("Scan or enter barcode") },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { showScanner = true }) {
                                Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan", tint = StorePrimary)
                            }
                            if (barcode.isNotBlank()) {
                                IconButton(onClick = { barcode = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear")
                                }
                            }
                        }
                    },
                    singleLine = true,
                    isError = validationError != null,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            barcode = EscPosPrinter.generateValidEan13Barcode()
                            validationError = null
                        },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(14.dp), tint = StoreSaffronAccent)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isBn) "স্বয়ংক্রিয় বারকোড তৈরি" else "Auto-generate barcode", fontSize = 11.sp, color = StoreSaffronAccent)
                    }
                }

                if (validationError != null) {
                    Text(
                        text = validationError!!,
                        color = StoreRedAlert,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Optional Variant Label
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Display Label (Optional)") },
                    placeholder = { Text("e.g. 500g Pouch, Box of 12, 1L Bottle") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Unit Type selection
                Text("Variant Unit Type:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    availableUnits.forEach { u ->
                        FilterChip(
                            selected = !isCustomVariantUnit && unitType.equals(u, ignoreCase = true),
                            onClick = {
                                isCustomVariantUnit = false
                                unitType = u
                            },
                            label = { Text(BengaliReceiptTranslator.translateUnit(u, isBn)) }
                        )
                    }

                    FilterChip(
                        selected = isCustomVariantUnit,
                        onClick = {
                            isCustomVariantUnit = true
                            if (customVariantUnitText.isNotBlank()) {
                                unitType = customVariantUnitText.trim()
                            }
                        },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(if (isBn) "+ কাস্টম" else "+ Custom")
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = StorePrimary.copy(alpha = 0.15f),
                            selectedLabelColor = StorePrimary
                        )
                    )
                }

                if (isCustomVariantUnit) {
                    OutlinedTextField(
                        value = customVariantUnitText,
                        onValueChange = {
                            customVariantUnitText = it
                            unitType = it.trim().ifBlank { "piece" }
                        },
                        label = { Text(if (isBn) "কাস্টম ভ্যারিয়েন্ট ইউনিট লিখুন" else "Enter Custom Variant Unit") },
                        placeholder = { Text("e.g. tablet, capsule, strip, pouch, can") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Quantity & Variant Price
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = quantityText,
                        onValueChange = { quantityText = it },
                        label = { Text("Pack Qty ($unitType) *") },
                        placeholder = { Text("500") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )

                    OutlinedTextField(
                        value = priceText,
                        onValueChange = { priceText = it },
                        label = { Text("Selling Price (₹) *") },
                        placeholder = { Text("45.0") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }

                // MRP & Cost Price (Optional)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = mrpText,
                        onValueChange = { mrpText = it },
                        label = { Text("MRP (₹, Optional)") },
                        placeholder = { Text(if (priceText.isNotBlank()) priceText else "50.0") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )

                    OutlinedTextField(
                        value = costPriceText,
                        onValueChange = { costPriceText = it },
                        label = { Text("Cost Price (₹, Optional)") },
                        placeholder = { Text("35.0") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }

                // Packaging Unit (e.g. pack, pouch, bottle, box, strip, can, bag, piece)
                Text(
                    text = if (isBn) "প্যাকেজিং প্রকার:" else "Packaging Type:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf("pack", "pouch", "bottle", "box", "can", "strip", "bag", "piece", "tin").forEach { pkg ->
                        FilterChip(
                            selected = packagingUnit.equals(pkg, ignoreCase = true),
                            onClick = { packagingUnit = pkg },
                            label = { Text(BengaliReceiptTranslator.translateUnit(pkg, isBn)) }
                        )
                    }
                }

                // Stock conversion preview card
                if (parsedQty > 0.0) {
                    val pVal = priceText.toDoubleOrNull() ?: 0.0
                    val mVal = mrpText.toDoubleOrNull() ?: pVal
                    val cVal = costPriceText.toDoubleOrNull() ?: 0.0
                    val hasDisc = mVal > pVal && pVal > 0.0
                    val discPct = if (hasDisc) ((mVal - pVal) / mVal * 100.0) else 0.0

                    Surface(
                        color = StorePrimary.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, StorePrimary.copy(alpha = 0.25f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = StorePrimary, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isBn) "বেস স্টক কর্তন হিসাব:" else "Base Stock Deduction:",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = StorePrimary
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "1 $packagingUnit of this variant deducts %.2f %s from base inventory".format(convertedBaseQty, baseProduct.unitType),
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 11.sp,
                                color = TextDark
                            )
                            if (hasDisc) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "MRP: ₹%.2f • Selling: ₹%.2f (%.0f%% OFF)".format(mVal, pVal, discPct),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFEA580C)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanBarcode = barcode.trim()
                    if (cleanBarcode.isBlank()) {
                        validationError = "Barcode cannot be blank"
                        return@Button
                    }
                    if (primaryBarcode != null && cleanBarcode.equals(primaryBarcode, ignoreCase = true)) {
                        validationError = "Barcode cannot be identical to the product's primary barcode"
                        return@Button
                    }
                    val duplicateOtherVariant = existingVariants.any {
                        it.barcode.equals(cleanBarcode, ignoreCase = true) &&
                                (initialVariant == null || !it.barcode.equals(initialVariant.barcode, ignoreCase = true))
                    }
                    if (duplicateOtherVariant) {
                        validationError = "Another variant in this product already has barcode '$cleanBarcode'"
                        return@Button
                    }
                    val qty = quantityText.toDoubleOrNull()
                    if (qty == null || qty <= 0.0) {
                        validationError = "Please enter a valid quantity > 0"
                        return@Button
                    }
                    val price = priceText.toDoubleOrNull()
                    if (price == null || price < 0.0) {
                        validationError = "Please enter a valid price >= 0"
                        return@Button
                    }

                    // Validate barcode uniqueness across catalog
                    val (available, reason) = viewModel.isBarcodeAvailableAcrossCatalog(
                        barcode = cleanBarcode,
                        excludeProductId = if (baseProduct.id != "temp") baseProduct.id else null,
                        excludeVariantBarcode = initialVariant?.barcode
                    )
                    if (!available) {
                        validationError = reason ?: "Barcode is already in use elsewhere in catalog"
                        return@Button
                    }

                    val effectiveVarUnit = if (isCustomVariantUnit && customVariantUnitText.isNotBlank()) {
                        customVariantUnitText.trim()
                    } else {
                        unitType.trim().ifBlank { "piece" }
                    }

                    val mrpVal = mrpText.toDoubleOrNull() ?: price
                    val costVal = costPriceText.toDoubleOrNull() ?: 0.0

                    onSaveVariant(
                        BarcodeVariant(
                            id = initialVariant?.id ?: java.util.UUID.randomUUID().toString().take(8),
                            barcode = cleanBarcode,
                            unitType = effectiveVarUnit,
                            quantity = qty,
                            price = price,
                            label = label.trim(),
                            mrp = if (mrpVal > 0.0) mrpVal else price,
                            costPrice = costVal,
                            packagingUnit = packagingUnit.trim().ifBlank { "pack" },
                            imageUri = initialVariant?.imageUri
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = StorePrimary)
            ) {
                Text(if (isBn) "সংরক্ষণ করুন" else "Save Variant")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (isBn) "বাতিল" else "Cancel")
            }
        }
    )

    if (showScanner) {
        SingleBarcodeScannerDialog(
            onBarcodeCaptured = { code ->
                barcode = code
                showScanner = false
            },
            onDismiss = { showScanner = false }
        )
    }
}

@Composable
fun RestockDialog(
    product: Product,
    suppliers: List<Supplier>,
    isBn: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Double, Supplier?, Double, Double, Boolean, String?, String?) -> Unit
) {
    val context = LocalContext.current
    var addedQtyText by remember { mutableStateOf("") }
    var selectedSupplier by remember { mutableStateOf<Supplier?>(null) }
    var costPriceText by remember { mutableStateOf(product.costPrice.toString()) }
    var sellingPriceText by remember { mutableStateOf(product.sellingPrice.toString()) }
    var expiryDate by remember { mutableStateOf(product.expiryDate ?: "") }
    var batchNumber by remember { mutableStateOf("") }
    var isCreditPurchase by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stock-IN / Restock: ${product.getDisplayName(isBn)}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = addedQtyText,
                    onValueChange = { addedQtyText = it },
                    label = { Text("Add Stock Quantity (${product.unitType})") },
                    placeholder = { Text("e.g. 10") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = costPriceText,
                        onValueChange = { costPriceText = it },
                        label = { Text("New Cost (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )

                    OutlinedTextField(
                        value = sellingPriceText,
                        onValueChange = { sellingPriceText = it },
                        label = { Text("New Selling (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }

                // Expiry Date Field & Quick Presets
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceWarm.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, BorderDivider.copy(alpha = 0.7f))
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isBn) "মেয়াদ শেষের তারিখ (Expiry Date)" else "Expiry Date (Optional)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = TextDark
                            )
                            if (expiryDate.isNotBlank()) {
                                TextButton(
                                    onClick = { expiryDate = "" },
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                    modifier = Modifier.height(24.dp)
                                ) {
                                    Text(
                                        if (isBn) "মুছুন" else "Clear",
                                        fontSize = 11.sp,
                                        color = StoreRedAlert
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        OutlinedTextField(
                            value = expiryDate,
                            onValueChange = { expiryDate = it },
                            placeholder = { Text("YYYY-MM-DD") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                IconButton(onClick = {
                                    val cal = java.util.Calendar.getInstance()
                                    if (expiryDate.isNotBlank()) {
                                        try {
                                            val parts = expiryDate.split("-")
                                            if (parts.size == 3) {
                                                cal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
                                            }
                                        } catch (e: Exception) {}
                                    }
                                    android.app.DatePickerDialog(
                                        context,
                                        { _, y, m, d ->
                                            expiryDate = String.format("%04d-%02d-%02d", y, m + 1, d)
                                        },
                                        cal.get(java.util.Calendar.YEAR),
                                        cal.get(java.util.Calendar.MONTH),
                                        cal.get(java.util.Calendar.DAY_OF_MONTH)
                                    ).show()
                                }) {
                                    Icon(Icons.Default.CalendarToday, contentDescription = "Pick Expiry Date", tint = StorePrimary)
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = if (isBn) "দ্রুত মেয়াদের দিন সিলেক্ট করুন:" else "Quick Expiry Presets:",
                            fontSize = 11.sp,
                            color = TextMuted
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            val today = java.time.LocalDate.now()
                            listOf(
                                ("+1 Month" to today.plusMonths(1)),
                                ("+3 Months" to today.plusMonths(3)),
                                ("+6 Months" to today.plusMonths(6)),
                                ("+1 Year" to today.plusYears(1))
                            ).forEach { (label, targetDate) ->
                                OutlinedButton(
                                    onClick = { expiryDate = targetDate.toString() },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(label, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }

                // Batch Number (Optional)
                OutlinedTextField(
                    value = batchNumber,
                    onValueChange = { batchNumber = it },
                    label = { Text(if (isBn) "ব্যাচ নম্বর (ঐচ্ছিক)" else "Batch Number (Optional)") },
                    placeholder = { Text("e.g. BATCH-${System.currentTimeMillis().toString().takeLast(4)}") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Supplier Link
                Text("Select Supplier (Optional):", style = MaterialTheme.typography.labelSmall)
                if (suppliers.isEmpty()) {
                    Text("No suppliers saved", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 100.dp)) {
                        items(suppliers, key = { it.id }) { supp ->
                            val isSelected = selectedSupplier?.id == supp.id
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedSupplier = if (isSelected) null else supp }
                                    .padding(vertical = 2.dp),
                                colors = CardDefaults.cardColors(containerColor = if (isSelected) StoreRedPrimary.copy(alpha = 0.1f) else SurfaceWarm)
                            ) {
                                Text(
                                    text = supp.name,
                                    modifier = Modifier.padding(8.dp),
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }

                if (selectedSupplier != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = isCreditPurchase,
                            onCheckedChange = { isCreditPurchase = it }
                        )
                        Text("Bought on Credit (Add to Supplier Payable Khata)", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val qty = addedQtyText.toDoubleOrNull() ?: 0.0
                    val cost = costPriceText.toDoubleOrNull() ?: product.costPrice
                    val sell = sellingPriceText.toDoubleOrNull() ?: product.sellingPrice
                    if (qty > 0) {
                        onConfirm(
                            qty,
                            selectedSupplier,
                            cost,
                            sell,
                            isCreditPurchase,
                            batchNumber.trim().ifBlank { null },
                            expiryDate.trim().ifBlank { null }
                        )
                        android.widget.Toast.makeText(
                            context,
                            if (isBn) "✅ স্টক সফলভাবে যুক্ত হয়েছে! (+${qty} ${product.unitType})" else "✅ Stock added successfully! (+${qty} ${product.unitType})",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        android.widget.Toast.makeText(
                            context,
                            if (isBn) "অনুগ্রহ করে সঠিক পরিমাণ লিখুন" else "Please enter a valid stock quantity",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = StoreRedPrimary)
            ) {
                Text(if (isBn) "স্টক নিশ্চিত করুন" else "Confirm Restock")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (isBn) "বাতিল" else "Cancel") }
        }
    )
}

data class StockOutUnitOption(
    val id: String,
    val unitLabel: String,
    val ratioToBase: Double
)

data class StockOutReasonItem(
    val title: String,
    val descriptionEn: String,
    val descriptionBn: String,
    val isLoss: Boolean,
    val isPersonal: Boolean,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockOutDialog(
    product: Product,
    batches: List<ProductBatch> = emptyList(),
    isBn: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (removedQty: Double, reason: String, note: String?, batchId: String?) -> Unit
) {
    // 1. Available Unit Conversions
    val unitOptions = remember(product, isBn) {
        val list = mutableListOf<StockOutUnitOption>()
        list.add(StockOutUnitOption("base", product.unitType, 1.0))

        val ppb = product.getPiecesPerBoxRatio()
        if (ppb > 1 && !Product.isLooseUnit(product.unitType)) {
            val boxLabel = if (isBn) "বাক্স ($ppb ${product.unitType})" else "Box ($ppb ${product.unitType})"
            list.add(StockOutUnitOption("box", boxLabel, ppb.toDouble()))
        } else if (product.bulkUnitType != null && (product.bulkQuantity ?: 0.0) > 1.0) {
            val bq = product.bulkQuantity!!
            val bulkLabel = "${product.bulkUnitType} ($bq ${product.unitType})"
            list.add(StockOutUnitOption("bulk", bulkLabel, bq))
        }

        if (Product.isKgUnit(product.unitType)) {
            list.add(StockOutUnitOption("gram", if (isBn) "গ্রাম (g)" else "Gram (g)", 0.001))
        } else if (Product.isLitreUnit(product.unitType)) {
            list.add(StockOutUnitOption("ml", if (isBn) "মিলি (ml)" else "Millilitre (ml)", 0.001))
        } else if (!product.secondaryUnitType.isNullOrBlank() && product.secondaryUnitRatio > 1.0 &&
            !product.secondaryUnitType.equals(product.unitType, ignoreCase = true)
        ) {
            val secLabel = "${product.secondaryUnitType} (1/${product.secondaryUnitRatio.toInt()} ${product.unitType})"
            list.add(StockOutUnitOption("sec", secLabel, 1.0 / product.secondaryUnitRatio))
        }
        list
    }

    var selectedUnit by remember { mutableStateOf(unitOptions.first()) }
    var selectedBatchId by remember { mutableStateOf<String?>(null) }
    var outQtyText by remember { mutableStateOf("") }
    var selectedReason by remember { mutableStateOf("Damaged / ক্ষতিগ্রস্ত") }
    var noteText by remember { mutableStateOf("") }

    val selectedBatch = remember(selectedBatchId, batches) {
        batches.find { it.id == selectedBatchId }
    }

    val maxStockAllowed = selectedBatch?.quantity ?: product.currentStock
    val parsedQty = outQtyText.toDoubleOrNull() ?: 0.0
    val baseQty = Product.roundQuantity(parsedQty * selectedUnit.ratioToBase)
    val isExceeding = baseQty > maxStockAllowed
    val remainingStock = Product.roundQuantity((maxStockAllowed - baseQty).coerceAtLeast(0.0))

    val reasons = listOf(
        StockOutReasonItem(
            title = "Damaged / ক্ষতিগ্রস্ত",
            descriptionEn = "Broken packaging, spilled, or physically ruined",
            descriptionBn = "ভাঙা, নষ্ট বা ক্ষতিগ্রস্ত পণ্য",
            isLoss = true,
            isPersonal = false,
            icon = Icons.Default.ReportProblem
        ),
        StockOutReasonItem(
            title = "Expired / মেয়াদ উত্তীর্ণ",
            descriptionEn = "Past expiration date, unsellable to customers",
            descriptionBn = "মেয়াদ শেষ হয়ে যাওয়া পণ্য",
            isLoss = true,
            isPersonal = false,
            icon = Icons.Default.EventBusy
        ),
        StockOutReasonItem(
            title = "Wastage / অপচয়",
            descriptionEn = "Handling spillage, moisture loss, pests or leakage",
            descriptionBn = "হ্যান্ডলিং অপচয়, আর্দ্রতায় নষ্ট বা কীটনাশকের ক্ষতি",
            isLoss = true,
            isPersonal = false,
            icon = Icons.Default.DeleteOutline
        ),
        StockOutReasonItem(
            title = "Personal Use / নিজস্ব ব্যবহার",
            descriptionEn = "Owner or family consumption (Owner's Draw)",
            descriptionBn = "মালিক বা পরিবারের ব্যক্তিগত ব্যবহার (ব্যবসার ক্ষতি নয়)",
            isLoss = false,
            isPersonal = true,
            icon = Icons.Default.Person
        ),
        StockOutReasonItem(
            title = "Other / অন্যান্য",
            descriptionEn = "Promotional sample, giveaway or stock audit recount",
            descriptionBn = "প্রচারমূলক নমুনা, উপহার বা স্টক মেলানো",
            isLoss = false,
            isPersonal = false,
            icon = Icons.Default.MoreHoriz
        )
    )

    val currentReason = reasons.find { it.title == selectedReason } ?: reasons.first()

    val stepSize = when (selectedUnit.id) {
        "gram", "ml" -> 100.0
        else -> if (Product.isLooseUnit(product.unitType) && selectedUnit.ratioToBase == 1.0) 0.5 else 1.0
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = StoreRedAlert.copy(alpha = 0.12f),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.RemoveCircleOutline,
                            contentDescription = null,
                            tint = StoreRedAlert,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Column {
                    Text(
                        text = LanguageManager.getString("Stock-OUT (Stock Reduction)", "স্টক আউট (পণ্য হ্রাস)"),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextDark
                    )
                    Text(
                        text = LanguageManager.getString("Safely write off damaged, expired or consumed stock", "ক্ষতিগ্রস্ত বা অপচয়কৃত পণ্যের হিসাব সংরক্ষণ করুন"),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Product Summary Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceWarm),
                    border = BorderStroke(1.dp, TextMuted.copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = product.getDisplayName(isBn),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = TextDark,
                                modifier = Modifier.weight(1f)
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            ) {
                                Text(
                                    text = product.category,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
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
                                    "Stock: ${product.getFormattedStockDisplay(false)}",
                                    "মজুদ: ${product.getFormattedStockDisplay(true)}"
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                            Text(
                                text = "Cost: ₹%.2f / ${product.unitType}".format(product.costPrice),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = TextDark
                            )
                        }
                    }
                }

                // Batch Selection (When Batches Exist)
                if (batches.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = LanguageManager.getString("Select Batch / Lot (Optional):", "নির্দিষ্ট ব্যাচ নির্বাচন করুন (ঐচ্ছিক):"),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextDark
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = selectedBatchId == null,
                                onClick = { selectedBatchId = null },
                                label = { Text(LanguageManager.getString("Auto FIFO (All Batches)", "স্বয়ংক্রিয় FIFO")) },
                                leadingIcon = {
                                    Icon(Icons.Default.Inventory2, contentDescription = null, modifier = Modifier.size(14.dp))
                                }
                            )
                            batches.forEach { b ->
                                val isExp = b.getExpiryStatus() == ExpiryStatus.EXPIRED
                                FilterChip(
                                    selected = selectedBatchId == b.id,
                                    onClick = {
                                        selectedBatchId = b.id
                                        if (isExp) {
                                            selectedReason = "Expired / মেয়াদ উত্তীর্ণ"
                                        }
                                    },
                                    label = {
                                        Text(
                                            "${b.batchNumber} (${b.quantity} ${product.unitType})${if (isExp) " ⚠️ Expired" else ""}",
                                            fontSize = 11.sp
                                        )
                                    },
                                    colors = if (isExp) {
                                        FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = StoreRedAlert.copy(alpha = 0.15f),
                                            selectedLabelColor = StoreRedAlert
                                        )
                                    } else FilterChipDefaults.filterChipColors()
                                )
                            }
                        }
                    }
                }

                // Unit Selection Chips (If product supports multiple units)
                if (unitOptions.size > 1) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = LanguageManager.getString("Reduction Unit:", "অপসারণের একক:"),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextDark
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            unitOptions.forEach { opt ->
                                val isSelected = selectedUnit.id == opt.id
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        selectedUnit = opt
                                        outQtyText = ""
                                    },
                                    label = { Text(opt.unitLabel, fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) }
                                )
                            }
                        }
                    }
                }

                // Quantity Input Row with Stepper [-] Input [+]
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = LanguageManager.getString(
                            "Out Quantity (${selectedUnit.unitLabel}):",
                            "অপসারণের পরিমাণ (${selectedUnit.unitLabel}):"
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextDark
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Minus Stepper
                        FilledTonalIconButton(
                            onClick = {
                                val next = (parsedQty - stepSize).coerceAtLeast(0.0)
                                outQtyText = if (next == 0.0) "" else if (next % 1.0 == 0.0) next.toInt().toString() else "%.2f".format(java.util.Locale.US, next)
                            },
                            enabled = parsedQty > 0.0,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = "Decrease")
                        }

                        // Text Field
                        OutlinedTextField(
                            value = outQtyText,
                            onValueChange = { input ->
                                outQtyText = input.filter { it.isDigit() || it == '.' }
                            },
                            placeholder = { Text("0") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            isError = isExceeding,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.weight(1f),
                            trailingIcon = {
                                Text(
                                    text = if (selectedUnit.id == "box") "box" else if (selectedUnit.id in listOf("gram", "ml")) selectedUnit.id else product.unitType,
                                    modifier = Modifier.padding(end = 10.dp),
                                    color = TextMuted,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )

                        // Plus Stepper
                        FilledTonalIconButton(
                            onClick = {
                                val next = Product.roundQuantity(parsedQty + stepSize)
                                val nextBase = next * selectedUnit.ratioToBase
                                if (nextBase <= maxStockAllowed) {
                                    outQtyText = if (next % 1.0 == 0.0) next.toInt().toString() else "%.2f".format(java.util.Locale.US, next)
                                }
                            },
                            enabled = baseQty < maxStockAllowed,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Increase")
                        }
                    }

                    if (selectedUnit.ratioToBase != 1.0 && parsedQty > 0) {
                        Text(
                            text = LanguageManager.getString(
                                "Equivalent: $baseQty ${product.unitType} in primary stock",
                                "মূল এককে রূপান্তর: $baseQty ${product.unitType}"
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Quick Quantity Reduction Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val quickOpts: List<Double> = when {
                        selectedUnit.id == "base" -> listOf(1.0, 2.0, 5.0, 10.0).filter { it <= maxStockAllowed }
                        selectedUnit.id == "box" -> {
                            val maxBoxes = (maxStockAllowed / selectedUnit.ratioToBase).toInt()
                            listOf(1.0, 2.0, 5.0).filter { it <= maxBoxes }
                        }
                        selectedUnit.id in listOf("gram", "ml") -> {
                            val maxGrams = maxStockAllowed * 1000.0
                            listOf(100.0, 250.0, 500.0, 1000.0).filter { it <= maxGrams }
                        }
                        else -> listOf(1.0, 2.0, 5.0).filter { it * selectedUnit.ratioToBase <= maxStockAllowed }
                    }

                    quickOpts.forEach { q ->
                        FilterChip(
                            selected = parsedQty == q,
                            onClick = {
                                outQtyText = if (q % 1.0 == 0.0) q.toInt().toString() else "%.2f".format(java.util.Locale.US, q)
                            },
                            label = { Text("+$q", fontSize = 11.sp) }
                        )
                    }

                    // Max / All Stock Button
                    if (maxStockAllowed > 0.0) {
                        val allInSelectedUnit = maxStockAllowed / selectedUnit.ratioToBase
                        SuggestionChip(
                            onClick = {
                                outQtyText = if (allInSelectedUnit % 1.0 == 0.0) allInSelectedUnit.toInt().toString() else "%.2f".format(java.util.Locale.US, allInSelectedUnit)
                            },
                            label = {
                                Text(
                                    text = LanguageManager.getString("All (${Product.formatQuantity(maxStockAllowed)} ${product.unitType})", "সব স্টক"),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    color = StoreRedAlert
                                )
                            },
                            colors = SuggestionChipDefaults.suggestionChipColors(containerColor = StoreRedAlert.copy(alpha = 0.08f))
                        )
                    }

                    if (outQtyText.isNotBlank()) {
                        SuggestionChip(
                            onClick = { outQtyText = "" },
                            label = { Text(LanguageManager.getString("Clear", "মুছুন"), fontSize = 11.sp) }
                        )
                    }
                }

                // Live Stock Impact Card (Before -> Deducting -> Remaining)
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isExceeding) StoreRedAlert.copy(alpha = 0.08f) else SurfaceWarm
                    ),
                    border = BorderStroke(1.dp, if (isExceeding) StoreRedAlert else TextMuted.copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = LanguageManager.getString("Current Stock", "বর্তমান মজুদ"),
                                    fontSize = 11.sp,
                                    color = TextMuted
                                )
                                Text(
                                    text = if (selectedBatch != null) "${selectedBatch.quantity} ${product.unitType}" else product.getFormattedStockDisplay(isBn),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = TextDark
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ArrowForward,
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = LanguageManager.getString("Deducting", "হ্রাস পাচ্ছে"),
                                    fontSize = 11.sp,
                                    color = if (isExceeding) StoreRedAlert else StoreRedAlert.copy(alpha = 0.8f)
                                )
                                Text(
                                    text = "-$baseQty ${product.unitType}",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = StoreRedAlert
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ArrowForward,
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = LanguageManager.getString("Remaining", "অবশিষ্ট"),
                                    fontSize = 11.sp,
                                    color = TextMuted
                                )
                                Text(
                                    text = if (isExceeding) {
                                        LanguageManager.getString("Deficit!", "ঘাটতি!")
                                    } else {
                                        "$remainingStock ${product.unitType}"
                                    },
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = if (isExceeding) StoreRedAlert else if (remainingStock == 0.0) TextMuted else ProfitGreen
                                )
                            }
                        }

                        if (isExceeding) {
                            Text(
                                text = LanguageManager.getString(
                                    "Warning: Quantity exceeds current available stock ($maxStockAllowed ${product.unitType})!",
                                    "সতর্কতা: বর্তমান মজুদের চেয়ে ($maxStockAllowed ${product.unitType}) বেশি পরিমাণ প্রবেশ করানো হয়েছে!"
                                ),
                                color = StoreRedAlert,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Reason for Stock-OUT
                Text(
                    text = LanguageManager.getString("Reason for Stock-OUT:", "স্টক আউটের কারণ:"),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextDark
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    reasons.forEach { item ->
                        val isSelected = selectedReason == item.title
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedReason = item.title },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) {
                                    if (item.isLoss) StoreRedAlert.copy(alpha = 0.10f)
                                    else if (item.isPersonal) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                    else SurfaceWarm
                                } else SurfaceWarm
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) {
                                    if (item.isLoss) StoreRedAlert else MaterialTheme.colorScheme.primary
                                } else TextMuted.copy(alpha = 0.15f)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { selectedReason = item.title },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = if (item.isLoss) StoreRedAlert else MaterialTheme.colorScheme.primary
                                    ),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = null,
                                    tint = if (isSelected) {
                                        if (item.isLoss) StoreRedAlert else MaterialTheme.colorScheme.primary
                                    } else TextMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) {
                                            if (item.isLoss) StoreRedAlert else MaterialTheme.colorScheme.primary
                                        } else TextDark
                                    )
                                    Text(
                                        text = if (isBn) item.descriptionBn else item.descriptionEn,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMuted,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }
                }

                // Financial Impact Breakdown Card
                if (baseQty > 0) {
                    val unitCost = product.costPrice
                    val totalLossValue = baseQty * unitCost

                    Surface(
                        color = if (currentReason.isLoss) StoreRedAlert.copy(alpha = 0.08f)
                        else if (currentReason.isPersonal) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        else SurfaceWarm,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(
                            1.dp,
                            if (currentReason.isLoss) StoreRedAlert.copy(alpha = 0.3f)
                            else if (currentReason.isPersonal) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                            else TextMuted.copy(alpha = 0.15f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (currentReason.isLoss) {
                                        if (isBn) "প্রকৃত ব্যবসার ক্ষতি (Stock Loss):" else "Genuine Business Loss (P&L):"
                                    } else if (currentReason.isPersonal) {
                                        if (isBn) "ব্যক্তিগত ব্যবহার (ব্যবসার ক্ষতি নয়):" else "Personal Draw (Excluded from Loss):"
                                    } else {
                                        if (isBn) "স্টক অপসারণ মূল্য:" else "Stock Removal Valuation:"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (currentReason.isLoss) StoreRedAlert else if (currentReason.isPersonal) MaterialTheme.colorScheme.primary else TextDark
                                )
                                Text(
                                    text = if (selectedUnit.ratioToBase != 1.0) {
                                        "$parsedQty ${selectedUnit.unitLabel} (= $baseQty ${product.unitType}) × ₹%.2f".format(product.costPrice)
                                    } else {
                                        "$baseQty ${product.unitType} × ₹%.2f".format(product.costPrice)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted,
                                    fontSize = 11.sp
                                )
                            }
                            Text(
                                text = "₹%.2f".format(totalLossValue),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (currentReason.isLoss) StoreRedAlert else if (currentReason.isPersonal) MaterialTheme.colorScheme.primary else TextDark
                            )
                        }
                    }
                }

                // Notes / Remarks
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text(LanguageManager.getString("Notes / Remarks (Optional)", "মন্তব্য (ঐচ্ছিক)")) },
                    placeholder = { Text(LanguageManager.getString("e.g. Sachet burst during transit", "যেমন: পরিবহনের সময় ক্ষতিগ্রস্ত")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (baseQty > 0 && !isExceeding) {
                        onConfirm(baseQty, selectedReason, noteText.ifBlank { null }, selectedBatchId)
                    }
                },
                enabled = baseQty > 0 && !isExceeding,
                colors = ButtonDefaults.buttonColors(containerColor = StoreRedAlert)
            ) {
                Icon(Icons.Default.Remove, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (baseQty > 0) {
                        LanguageManager.getString("Confirm Stock Out ($baseQty ${product.unitType})", "স্টক আউট নিশ্চিত করুন ($baseQty ${product.unitType})")
                    } else {
                        LanguageManager.getString("Confirm Stock Out", "স্টক আউট নিশ্চিত করুন")
                    },
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(LanguageManager.getString("Cancel", "বাতিল"))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockOutDialog(
    product: Product,
    isBn: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Double, String, String?) -> Unit
) {
    StockOutDialog(
        product = product,
        batches = emptyList(),
        isBn = isBn,
        onDismiss = onDismiss,
        onConfirm = { qty, reason, note, _ -> onConfirm(qty, reason, note) }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryManagementDialog(
    viewModel: StoreViewModel,
    isBn: Boolean,
    onDismiss: () -> Unit
) {
    val categories by viewModel.allCategories.collectAsState()
    val products by viewModel.allProducts.collectAsState()
    val productCountsByCategory = remember(products) {
        products.groupingBy { it.category.trim().lowercase() }.eachCount()
    }

    var newCategoryText by remember { mutableStateOf("") }
    var renamingCategory by remember { mutableStateOf<String?>(null) }
    var deletingCategory by remember { mutableStateOf<String?>(null) }

    val presetSuggestions = listOf(
        "Dairy", "Snacks", "Bakery & Bread", "Frozen Foods", 
        "Spices & Masala", "Beverages", "Cosmetics", "Stationery", "Baby Care", "Pet Care"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Category, contentDescription = null, tint = StorePrimary)
                Text(
                    text = LanguageManager.getString("Manage Product Categories", "ক্যাটাগরি ব্যবস্থাপনা"),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Add New Category Input Row
                OutlinedTextField(
                    value = newCategoryText,
                    onValueChange = { newCategoryText = it },
                    label = { Text(LanguageManager.getString("New Category Name", "নতুন ক্যাটাগরির নাম")) },
                    placeholder = { Text("e.g. Dairy, Snacks, Spices") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                    trailingIcon = {
                        if (newCategoryText.isNotBlank()) {
                            IconButton(onClick = {
                                viewModel.addCustomCategory(newCategoryText)
                                newCategoryText = ""
                            }) {
                                Icon(Icons.Default.Check, contentDescription = "Save", tint = StorePrimary)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = {
                        if (newCategoryText.isNotBlank()) {
                            viewModel.addCustomCategory(newCategoryText)
                            newCategoryText = ""
                        }
                    },
                    enabled = newCategoryText.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = StorePrimary)
                ) {
                    Icon(Icons.Default.AddCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(LanguageManager.getString("Add Category", "ক্যাটাগরি যোগ করুন"))
                }

                // Quick Presets
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = LanguageManager.getString("Quick Presets (Tap to Add):", "দ্রুত যোগ করার জন্য ক্লিক করুন:"),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(presetSuggestions.filter { preset -> !categories.any { it.equals(preset, ignoreCase = true) } }, key = { it }) { preset ->
                            SuggestionChip(
                                onClick = { viewModel.addCustomCategory(preset) },
                                label = { Text("+ $preset", fontSize = 11.sp) },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = StorePrimary.copy(alpha = 0.08f)
                                )
                            )
                        }
                    }
                }

                Divider(modifier = Modifier.padding(vertical = 4.dp))

                // Existing Categories List
                Text(
                    text = "${LanguageManager.getString("Existing Categories", "বিদ্যমান ক্যাটাগরি সমূহ")} (${categories.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                if (categories.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = LanguageManager.getString("No categories created yet", "কোন ক্যাটাগরি তৈরি করা হয়নি"),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                    ) {
                        items(categories, key = { it }) { categoryName ->
                            val itemCount = productCountsByCategory[categoryName.lowercase()] ?: 0
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = SurfaceWarm),
                                elevation = CardDefaults.cardElevation(1.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Folder,
                                            contentDescription = null,
                                            tint = StorePrimary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = categoryName,
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            Text(
                                                text = "$itemCount ${LanguageManager.getString("products assigned", "টি পণ্য অন্তর্ভুক্ত")}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextMuted
                                            )
                                        }
                                    }

                                    Row {
                                        IconButton(
                                            onClick = { renamingCategory = categoryName },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Edit,
                                                contentDescription = "Rename",
                                                tint = StorePrimary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }

                                        if (!categoryName.equals("General", ignoreCase = true)) {
                                            IconButton(
                                                onClick = { deletingCategory = categoryName },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    contentDescription = "Delete",
                                                    tint = StoreRedAlert,
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
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(LanguageManager.getString("Close", "বন্ধ করুন"))
            }
        }
    )

    // Sub-dialog for renaming a category
    renamingCategory?.let { oldName ->
        RenameCategoryDialog(
            currentName = oldName,
            isBn = isBn,
            onDismiss = { renamingCategory = null },
            onConfirm = { newName ->
                viewModel.renameCategory(oldName, newName)
                renamingCategory = null
            }
        )
    }

    // Sub-dialog for deleting a category
    deletingCategory?.let { catToDelete ->
        DeleteCategoryDialog(
            categoryName = catToDelete,
            affectedProductCount = products.count { it.category.equals(catToDelete, ignoreCase = true) },
            isBn = isBn,
            onDismiss = { deletingCategory = null },
            onConfirm = {
                viewModel.deleteCategory(catToDelete, "General")
                deletingCategory = null
            }
        )
    }
}

@Composable
fun RenameCategoryDialog(
    currentName: String,
    isBn: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var newNameText by remember { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = LanguageManager.getString("Rename Category", "ক্যাটাগরির নাম পরিবর্তন করুন"),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = LanguageManager.getString(
                        "Renaming will update all products under '$currentName'.",
                        "নাম পরিবর্তন করলে '$currentName' এর অধীনে থাকা সকল পণ্যের ক্যাটাগরি আপডেট হবে।"
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
                OutlinedTextField(
                    value = newNameText,
                    onValueChange = { newNameText = it },
                    label = { Text(LanguageManager.getString("Category Name", "ক্যাটাগরির নাম")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (newNameText.isNotBlank()) {
                        onConfirm(newNameText.trim())
                    }
                },
                enabled = newNameText.isNotBlank() && newNameText.trim() != currentName,
                colors = ButtonDefaults.buttonColors(containerColor = StorePrimary)
            ) {
                Text(LanguageManager.getString("Update", "আপডেট করুন"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(LanguageManager.getString("Cancel", "বাতিল"))
            }
        }
    )
}

@Composable
fun DeleteCategoryDialog(
    categoryName: String,
    affectedProductCount: Int,
    isBn: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = LanguageManager.getString("Delete Category?", "ক্যাটাগরি মুছে ফেলবেন?"),
                fontWeight = FontWeight.Bold,
                color = StoreRedAlert
            )
        },
        text = {
            Text(
                text = if (affectedProductCount > 0) {
                    LanguageManager.getString(
                        "Are you sure you want to delete '$categoryName'? $affectedProductCount assigned products will be reassigned to 'General'.",
                        "আপনি কি নিশ্চিত যে '$categoryName' মুছে ফেলতে চান? অন্তর্ভুক্ত $affectedProductCount টি পণ্যের ক্যাটাগরি 'General' এ স্থানান্তরিত হবে।"
                    )
                } else {
                    LanguageManager.getString(
                        "Are you sure you want to delete category '$categoryName'?",
                        "আপনি কি নিশ্চিত যে ক্যাটাগরি '$categoryName' মুছে ফেলতে চান?"
                    )
                },
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = StoreRedAlert)
            ) {
                Text(LanguageManager.getString("Delete & Reassign", "মুছে ফেলুন"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(LanguageManager.getString("Cancel", "বাতিল"))
            }
        }
    )
}

@Composable
fun ProductBatchesDialog(
    product: Product,
    viewModel: StoreViewModel,
    isBn: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val batches by viewModel.getBatchesForProduct(product.id).collectAsState(initial = emptyList())

    var showAddForm by remember { mutableStateOf(false) }
    var editingBatch by remember { mutableStateOf<ProductBatch?>(null) }

    var batchNumber by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("") }
    var expiryDate by remember { mutableStateOf("") }
    var mfgDate by remember { mutableStateOf("") }
    var costPrice by remember { mutableStateOf("") }
    var sellingPrice by remember { mutableStateOf("") }

    val resetForm = {
        batchNumber = "LOT-${(100..999).random()}"
        quantity = ""
        expiryDate = product.expiryDate ?: ""
        mfgDate = ""
        costPrice = if (product.costPrice > 0) product.costPrice.toString() else ""
        sellingPrice = if (product.sellingPrice > 0) product.sellingPrice.toString() else ""
        editingBatch = null
        showAddForm = false
    }

    val startEditing = { b: ProductBatch ->
        editingBatch = b
        batchNumber = b.batchNumber
        quantity = b.quantity.toString()
        expiryDate = b.expiryDate ?: ""
        mfgDate = b.mfgDate ?: ""
        costPrice = b.costPrice?.toString() ?: ""
        sellingPrice = b.sellingPrice?.toString() ?: ""
        showAddForm = true
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp)
                .heightIn(max = 680.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Layers, contentDescription = null, tint = StoreSaffronAccent)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isBn) "ব্যাচ এবং মেয়াদ ট্র্যাকিং" else "Multi-Batch Expiry Tracking",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = product.getDisplayName(isBn) + " (${product.unitType})",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Combined Stock & Status Summary
                Surface(
                    color = StorePrimary.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = if (isBn) "মোট স্টক (সব ব্যাচ):" else "Total Stock (All Batches):",
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                            Text(
                                text = "%.2f %s".format(product.currentStock, product.unitType),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = StorePrimary
                            )
                        }

                        Button(
                            onClick = {
                                resetForm()
                                showAddForm = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = StoreSaffronAccent),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isBn) "নতুন ব্যাচ যোগ করুন" else "New Batch",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // If form is visible, show Add/Edit Form
                if (showAddForm) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceWarm),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = if (editingBatch != null) (if (isBn) "ব্যাচ সম্পাদনা" else "Edit Batch") else (if (isBn) "নতুন ব্যাচ এন্ট্রি" else "Add New Stock Batch"),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = StorePrimary
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = batchNumber,
                                    onValueChange = { batchNumber = it },
                                    label = { Text(if (isBn) "ব্যাচ নং *" else "Batch No *") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = quantity,
                                    onValueChange = { quantity = it },
                                    label = { Text(if (isBn) "পরিমাণ *" else "Quantity *") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = expiryDate,
                                    onValueChange = { expiryDate = it },
                                    label = { Text(if (isBn) "মেয়াদের তারিখ" else "Expiry Date") },
                                    placeholder = { Text("YYYY-MM-DD") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    trailingIcon = {
                                        IconButton(onClick = {
                                            val cal = java.util.Calendar.getInstance()
                                            android.app.DatePickerDialog(
                                                context,
                                                { _, y, m, d ->
                                                    expiryDate = String.format("%04d-%02d-%02d", y, m + 1, d)
                                                },
                                                cal.get(java.util.Calendar.YEAR),
                                                cal.get(java.util.Calendar.MONTH),
                                                cal.get(java.util.Calendar.DAY_OF_MONTH)
                                            ).show()
                                        }) {
                                            Icon(Icons.Default.CalendarToday, contentDescription = null, tint = StorePrimary)
                                        }
                                    }
                                )

                                OutlinedTextField(
                                    value = mfgDate,
                                    onValueChange = { mfgDate = it },
                                    label = { Text(if (isBn) "উৎপাদন তারিখ" else "Mfg Date (Opt)") },
                                    placeholder = { Text("YYYY-MM-DD") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(onClick = { resetForm() }) {
                                    Text(LanguageManager.getString("Cancel", "বাতিল"))
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Button(
                                    onClick = {
                                        val q = quantity.toDoubleOrNull() ?: 0.0
                                        if (batchNumber.isNotBlank() && q > 0) {
                                            val newBatch = ProductBatch(
                                                id = editingBatch?.id ?: UUID.randomUUID().toString(),
                                                productId = product.id,
                                                batchNumber = batchNumber.trim(),
                                                quantity = q,
                                                expiryDate = expiryDate.ifBlank { null },
                                                mfgDate = mfgDate.ifBlank { null },
                                                costPrice = costPrice.toDoubleOrNull(),
                                                sellingPrice = sellingPrice.toDoubleOrNull()
                                            )
                                            if (editingBatch != null) {
                                                viewModel.updateBatch(newBatch)
                                            } else {
                                                viewModel.addBatch(newBatch)
                                            }
                                            resetForm()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = StorePrimary)
                                ) {
                                    Text(LanguageManager.getString("Save Batch", "সংরক্ষণ করুন"))
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Batch Items List
                Text(
                    text = if (isBn) "বিদ্যমান ব্যাচসমূহ (FEFO অনুযায়ী সাজানো):" else "Active Batches (FEFO Expiry Order):",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted
                )

                Spacer(modifier = Modifier.height(6.dp))

                if (batches.isEmpty()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBackground)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = if (isBn) "কোন ব্যাচ এন্ট্রি করা নেই।" else "No batch records created yet.",
                                color = TextMuted,
                                fontSize = 13.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    batchNumber = "BATCH-01"
                                    quantity = Product.formatQuantity(product.currentStock)
                                    expiryDate = product.expiryDate ?: ""
                                    costPrice = product.costPrice.toString()
                                    sellingPrice = product.sellingPrice.toString()
                                    showAddForm = true
                                }
                            ) {
                                Icon(Icons.Default.AddCircleOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (isBn) "প্রাথমিক ব্যাচ তৈরি করুন" else "Create Initial Batch")
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(batches, key = { it.id }, contentType = { "BATCH_ITEM" }) { b ->
                            val status = b.getExpiryStatus()
                            val daysLeft = b.getDaysUntilExpiry()

                            val (statusBg, statusText, statusColor) = when (status) {
                                ExpiryStatus.EXPIRED -> Triple(StoreRedAlert.copy(alpha = 0.15f), if (isBn) "মেয়াদউত্তীর্ণ!" else "EXPIRED!", StoreRedAlert)
                                ExpiryStatus.EXPIRING_SOON -> Triple(StoreSaffronAccent.copy(alpha = 0.15f), if (isBn) "${daysLeft} দিন অবশিষ্ট" else "Expiring in ${daysLeft} days", StoreSaffronAccent)
                                ExpiryStatus.FRESH -> Triple(StoreGreenProfit.copy(alpha = 0.12f), if (isBn) "ভালো" else "Fresh", StoreGreenProfit)
                                ExpiryStatus.NO_EXPIRY -> Triple(TextMuted.copy(alpha = 0.1f), if (isBn) "মেয়াদ নেই" else "No Expiry", TextMuted)
                            }

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = CardBackground),
                                elevation = CardDefaults.cardElevation(1.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Surface(
                                                color = StorePrimary.copy(alpha = 0.12f),
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    text = b.batchNumber,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = StorePrimary,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }

                                            Spacer(modifier = Modifier.width(8.dp))

                                            Surface(
                                                color = statusBg,
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    text = statusText,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = statusColor,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(4.dp))

                                        Text(
                                            text = "Stock: %.2f %s".format(b.quantity, product.unitType) +
                                                    if (!b.expiryDate.isNullOrBlank()) " • Exp: ${b.expiryDate}" else "",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )

                                        if (!b.mfgDate.isNullOrBlank()) {
                                            Text(
                                                text = "Mfg Date: ${b.mfgDate}",
                                                fontSize = 11.sp,
                                                color = TextMuted
                                            )
                                        }
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = { startEditing(b) }, modifier = Modifier.size(32.dp)) {
                                            Icon(Icons.Default.Edit, contentDescription = "Edit Batch", tint = StorePrimary, modifier = Modifier.size(16.dp))
                                        }
                                        IconButton(
                                            onClick = { viewModel.deleteBatch(b.id, product.id) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete Batch", tint = StoreRedAlert, modifier = Modifier.size(16.dp))
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
}
