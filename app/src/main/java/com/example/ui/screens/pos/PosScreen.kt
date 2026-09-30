package com.example.ui.screens.pos

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.example.data.local.dao.SaleWithItems
import com.example.data.local.entities.*
import com.example.ui.components.BarcodeScannerModal
import com.example.ui.components.PosVariantSelectorModal
import com.example.ui.components.CollapsingHeaderLayout
import com.example.ui.components.CollapsingHeaderState
import com.example.ui.components.FirebaseSyncStatusBadge
import com.example.ui.components.rememberCollapsingHeaderState
import com.example.ui.theme.*
import com.example.utils.BengaliReceiptTranslator
import com.example.utils.LanguageManager
import com.example.utils.PdfReceiptHelper
import com.example.utils.SmsHelper
import com.example.utils.ThemeManager
import com.example.utils.WhatsAppHelper
import com.example.viewmodel.CartItem
import com.example.viewmodel.StoreViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosScreen(
    viewModel: StoreViewModel,
    onNavigateToIncomingOrders: () -> Unit = {}
) {
    if (!com.example.utils.StaffManager.canMakeSales()) {
        com.example.ui.components.StaffAccessGate(
            screenTitle = "POS & Billing Counter",
            screenDescription = "Billing and sales transactions are restricted for your current staff role."
        )
        return
    }

    val currentContext = LocalContext.current
    val isBn = LanguageManager.isBengali
    val products by viewModel.allProducts.collectAsState()
    val activeOffers by viewModel.activeOffers.collectAsState()
    val rawCustomers by viewModel.allCustomers.collectAsState()
    val heldSales by viewModel.heldSales.collectAsState()
    val allSales by viewModel.allSales.collectAsState()
    val allReturns by viewModel.allReturns.collectAsState()
    val allEmployees by viewModel.allEmployees.collectAsState()
    val allLedgerEntries by viewModel.allLedgerEntries.collectAsState()
    val pendingOrdersCount by viewModel.pendingOrdersCount.collectAsState()

    val customers = remember(rawCustomers, allLedgerEntries) {
        rawCustomers.map { c -> com.example.utils.LedgerCalculator.reconcileCustomer(c, allLedgerEntries) }
    }

    var showStaffSwitchDialog by remember { mutableStateOf(false) }
    var showHeldBillsSheet by remember { mutableStateOf(false) }
    var showDailySummaryDialog by remember { mutableStateOf(false) }
    var showBarcodeScannerModal by remember { mutableStateOf(false) }
    var showQuickReturnSearchModal by remember { mutableStateOf(false) }
    var returnReceiptToShow by remember { mutableStateOf<com.example.data.local.entities.SaleReturnWithItems?>(null) }
    var showOffersDialog by remember { mutableStateOf(false) }
    var selectedProductForUnitSelector by remember { mutableStateOf<Product?>(null) }
    var selectedProductForVariantSelector by remember { mutableStateOf<Product?>(null) }
    var editingCartIndexForUnitSelector by remember { mutableStateOf<Int?>(null) }
    var productForQuickStockCorrection by remember { mutableStateOf<Pair<Product, com.example.data.models.InsufficientStockItem?>?>(null) }
    var selectedMobileTab by remember { mutableIntStateOf(0) }
    var previewPosProductPhoto by remember { mutableStateOf<Product?>(null) }

    val smsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.dispatchPendingCreditSms(currentContext)
        } else {
            Toast.makeText(
                currentContext,
                if (isBn) "এসএমএস অনুমতি দেওয়া হয়নি - বিক্রয় সম্পন্ন হয়েছে" else "SMS permission not granted. Sale completed normally.",
                Toast.LENGTH_SHORT
            ).show()
            viewModel.dismissSmsPermissionExplanation()
        }
    }

    // Handle App Shortcut to open Today's Summary
    LaunchedEffect(viewModel.triggerShowDailySummary) {
        if (viewModel.triggerShowDailySummary) {
            if (com.example.utils.StaffManager.canViewReports()) {
                showDailySummaryDialog = true
            }
            viewModel.triggerShowDailySummary = false
        }
    }

    // Intercept back press when on Billing/Cart tab to return to Products selection screen
    BackHandler(enabled = selectedMobileTab == 1) {
        selectedMobileTab = 0
    }

    // Intercept back press when filters are active on Products screen to clear them before exiting
    BackHandler(enabled = selectedMobileTab == 0 && (viewModel.posSearchQuery.isNotBlank() || viewModel.posSelectedCategory != "ALL")) {
        viewModel.posSearchQuery = ""
        viewModel.posSelectedCategory = "ALL"
    }

    // Calculate Today's Sales Live Aggregation
    val todaySalesSummary = remember(allSales, allReturns, products) {
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.set(java.util.Calendar.HOUR_OF_DAY, 23)
        cal.set(java.util.Calendar.MINUTE, 59)
        cal.set(java.util.Calendar.SECOND, 59)
        cal.set(java.util.Calendar.MILLISECOND, 999)
        val end = cal.timeInMillis

        val todaySales = allSales.filter { it.sale.datetime in start..end }
        val todayReturns = allReturns.filter { it.saleReturn.datetime in start..end }

        val grossRev = todaySales.sumOf { it.sale.finalAmount }
        val retRev = todayReturns.sumOf { it.saleReturn.totalReturnedAmount }
        val replRev = todayReturns.sumOf { it.saleReturn.totalReplacementAmount }
        val rev = (grossRev - retRev + replRev).coerceAtLeast(0.0)

        val grossCogs = todaySales.sumOf { saleWithItems ->
            saleWithItems.consolidatedItems.sumOf { it.totalCost }
        }
        val productMap = products.associateBy { it.id }
        val returnCogsAdj = todayReturns.sumOf { ret ->
            ret.items.sumOf { rItem ->
                val product = productMap[rItem.productId]
                val purchasePrice = product?.costPrice ?: (rItem.unitPrice * 0.7)
                val itemCost = purchasePrice * rItem.quantity
                if (rItem.isReplacement) itemCost else -itemCost
            }
        }
        val cogs = (grossCogs + returnCogsAdj).coerceAtLeast(0.0)
        val profit = rev - cogs
        Triple(todaySales.size, rev, profit)
    }

    val categories = remember(products) {
        listOf("ALL") + products.map { it.category }.distinct().filter { it.isNotBlank() }
    }

    val filteredProducts = remember(products, viewModel.posSearchQuery, viewModel.posSelectedCategory) {
        val query = viewModel.posSearchQuery.trim().lowercase()
        products.filter { prod ->
            val matchCategory = viewModel.posSelectedCategory == "ALL" || prod.category == viewModel.posSelectedCategory
            val matchQuery = query.isEmpty() || prod.matchesQueryWithVariants(query)
            matchCategory && matchQuery
        }
    }

    val posCollapsingState = rememberCollapsingHeaderState()

    LaunchedEffect(selectedMobileTab) {
        posCollapsingState.snapTo(0f)
    }

    LaunchedEffect(viewModel.posSearchQuery) {
        if (viewModel.posSearchQuery.isNotBlank()) {
            posCollapsingState.snapTo(0f)
        }
    }

    CollapsingHeaderLayout(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        state = posCollapsingState,
        enabled = selectedMobileTab == 0,
        header = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                // Action Tool Bar (Wholesale/Retail, Returns, Offers, and Sync Status)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Wholesale / Retail Toggle Quick Button
                    Surface(
                        onClick = {
                            viewModel.isWholesaleBillingMode = !viewModel.isWholesaleBillingMode
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = if (viewModel.isWholesaleBillingMode) StoreGreenProfit else SurfaceWarm,
                        border = BorderStroke(
                            1.dp,
                            if (viewModel.isWholesaleBillingMode) StoreGreenProfit else StorePrimary.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 6.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (viewModel.isWholesaleBillingMode) Icons.Default.Storefront else Icons.Default.ShoppingBag,
                                contentDescription = null,
                                tint = if (viewModel.isWholesaleBillingMode) Color.White else StorePrimary,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (viewModel.isWholesaleBillingMode) {
                                    if (isBn) "পাইকারি" else "Wholesale"
                                } else {
                                    if (isBn) "খুচরা" else "Retail"
                                },
                                color = if (viewModel.isWholesaleBillingMode) Color.White else TextDark,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                maxLines = 1
                            )
                        }
                    }

                    // Product Return & Replacement Quick Button
                    Surface(
                        onClick = {
                            showQuickReturnSearchModal = true
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFEA580C).copy(alpha = 0.10f),
                        border = BorderStroke(1.dp, Color(0xFFEA580C).copy(alpha = 0.5f)),
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 6.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.AssignmentReturn,
                                contentDescription = "Return & Replacement",
                                tint = Color(0xFFEA580C),
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isBn) "ফেরত" else "Return",
                                color = Color(0xFFEA580C),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                maxLines = 1
                            )
                        }
                    }

                    // Special Offers Button
                    Surface(
                        onClick = {
                            showOffersDialog = true
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = if (activeOffers.isNotEmpty()) Color(0xFFEA580C).copy(alpha = 0.12f) else StorePrimary.copy(alpha = 0.10f),
                        border = BorderStroke(1.dp, if (activeOffers.isNotEmpty()) Color(0xFFEA580C).copy(alpha = 0.6f) else StorePrimary.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 6.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocalOffer,
                                contentDescription = "Special Offers",
                                tint = if (activeOffers.isNotEmpty()) Color(0xFFEA580C) else StorePrimary,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            val offerLabel = if (activeOffers.isNotEmpty()) {
                                if (isBn) "অফার (${activeOffers.size})" else "Offers (${activeOffers.size})"
                            } else {
                                if (isBn) "অফার" else "Offers"
                            }
                            Text(
                                text = offerLabel,
                                color = if (activeOffers.isNotEmpty()) Color(0xFFEA580C) else StorePrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                maxLines = 1
                            )
                        }
                    }

                    // Online Store Orders Button
                    Surface(
                        onClick = onNavigateToIncomingOrders,
                        shape = RoundedCornerShape(8.dp),
                        color = if (pendingOrdersCount > 0) Color(0xFFEA580C).copy(alpha = 0.14f) else StorePrimary.copy(alpha = 0.10f),
                        border = BorderStroke(
                            1.dp,
                            if (pendingOrdersCount > 0) Color(0xFFEA580C) else StorePrimary.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier
                            .weight(1.1f)
                            .height(34.dp)
                            .testTag("pos_btn_online_orders")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 6.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.ShoppingBag,
                                contentDescription = "Online Orders",
                                tint = if (pendingOrdersCount > 0) Color(0xFFEA580C) else StorePrimary,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            val ordersLabel = if (pendingOrdersCount > 0) {
                                if (isBn) "অর্ডার ($pendingOrdersCount)" else "Orders ($pendingOrdersCount)"
                            } else {
                                if (isBn) "অর্ডার" else "Orders"
                            }
                            Text(
                                text = ordersLabel,
                                color = if (pendingOrdersCount > 0) Color(0xFFEA580C) else StorePrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                maxLines = 1
                            )
                        }
                    }

                    // Real-time Firebase Online / Offline Sync Status Badge
                    FirebaseSyncStatusBadge(
                        viewModel = viewModel,
                        compact = true,
                        modifier = Modifier.height(34.dp)
                    )
                }

                // Dismissible Backup Password reminder banner for Google Users
                val currentUser by viewModel.currentUser.collectAsState()
                if (currentUser != null && currentUser?.hasGoogleProvider == true && currentUser?.hasPasswordProvider == false && !com.example.utils.StoreInfoManager.backupPasswordBannerDismissed) {
                    com.example.ui.screens.settings.BackupPasswordReminderBanner(
                        onSetPasswordClicked = { viewModel.openSetBackupPasswordDialog(isFirstTimePrompt = false) },
                        onDismissClicked = { com.example.utils.StoreInfoManager.setBackupPasswordBannerDismissed(true, currentContext) },
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                if (selectedMobileTab == 0) {
                    Spacer(modifier = Modifier.height(6.dp))

                    // Live Today's Sales Summary Card Banner
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showDailySummaryDialog = true },
                        colors = CardDefaults.cardColors(containerColor = StoreGreenProfit.copy(alpha = 0.08f)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, StoreGreenProfit.copy(alpha = 0.3f)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Assessment,
                                    contentDescription = "Daily Summary",
                                    tint = StoreGreenProfit,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Today's Sales:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextDark,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "₹%.2f".format(todaySalesSummary.second),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = StoreGreenProfit
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Profit: ₹%.2f".format(todaySalesSummary.third),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = StorePrimary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = StorePrimary.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = "${todaySalesSummary.first} Bills",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = StorePrimary,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(2.dp))
                                Icon(
                                    Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = StorePrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Compact Search Input & Scan Barcode Action Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = viewModel.posSearchQuery,
                            onValueChange = { viewModel.posSearchQuery = it },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                imeAction = androidx.compose.ui.text.input.ImeAction.Search
                            ),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                onSearch = {
                                    val q = viewModel.posSearchQuery.trim()
                                    if (q.isNotEmpty()) {
                                        val cleanNoZero = q.trimStart('0')
                                        // 1. Check if exact match to a variant barcode in catalog
                                        for (p in products) {
                                            val v = p.findVariantByBarcode(q)
                                            if (v != null) {
                                                val added = viewModel.addVariantToCart(p, v, 1.0)
                                                if (added) {
                                                    viewModel.posSearchQuery = ""
                                                    android.widget.Toast.makeText(
                                                        currentContext,
                                                        if (isBn) "${v.getDisplayTitle(p.getDisplayName(true))} যোগ করা হয়েছে"
                                                        else "Added ${v.getDisplayTitle(p.getDisplayName(false))}",
                                                        android.widget.Toast.LENGTH_SHORT
                                                    ).show()
                                                } else {
                                                    android.widget.Toast.makeText(
                                                        currentContext,
                                                        if (isBn) "পর্যাপ্ত স্টক নেই!" else "Insufficient stock!",
                                                        android.widget.Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                                return@KeyboardActions
                                            }
                                        }
                                        // 2. Check if exact match to primary barcode
                                        val matchedProd = products.find { p ->
                                            val pb = p.barcode?.trim() ?: ""
                                            pb.isNotBlank() && (pb.equals(q, ignoreCase = true) || (cleanNoZero.isNotBlank() && pb.trimStart('0') == cleanNoZero))
                                        }
                                        if (matchedProd != null) {
                                            if (matchedProd.getBarcodeVariants().isNotEmpty()) {
                                                selectedProductForVariantSelector = matchedProd
                                                viewModel.posSearchQuery = ""
                                            } else {
                                                viewModel.quickIncrementProductInCart(matchedProd)
                                                viewModel.posSearchQuery = ""
                                                android.widget.Toast.makeText(
                                                    currentContext,
                                                    if (isBn) "${matchedProd.getDisplayName(true)} যোগ করা হয়েছে"
                                                    else "Added ${matchedProd.getDisplayName(false)}",
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    }
                                }
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            placeholder = {
                                Text(
                                    text = LanguageManager.getString("Search item or scan barcode...", "চাল, ডাল, আলু বা বারকোড খুঁজুন..."),
                                    fontSize = 12.sp,
                                    color = TextMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = null,
                                    tint = TextMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            trailingIcon = {
                                if (viewModel.posSearchQuery.isNotEmpty()) {
                                    IconButton(
                                        onClick = { viewModel.posSearchQuery = "" },
                                        modifier = Modifier.size(20.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Clear",
                                            tint = TextMuted,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontSize = 13.sp,
                                color = TextDark,
                                fontWeight = FontWeight.Medium
                            ),
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextDark,
                                unfocusedTextColor = TextDark,
                                focusedPlaceholderColor = TextMuted,
                                unfocusedPlaceholderColor = TextMuted,
                                focusedContainerColor = SurfaceWarm,
                                unfocusedContainerColor = SurfaceWarm,
                                focusedBorderColor = StorePrimary,
                                unfocusedBorderColor = TextMuted.copy(alpha = 0.3f)
                            )
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = { showBarcodeScannerModal = true },
                            modifier = Modifier.height(40.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = StorePrimary),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                        ) {
                            Icon(
                                Icons.Default.QrCodeScanner,
                                contentDescription = "Scan Barcode",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isBn) "স্ক্যান" else "Scan",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
        ) {
                if (selectedMobileTab == 0) {
                    // Category Chips Row
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(categories, key = { it }) { category ->
                            val isSelected = viewModel.posSelectedCategory == category
                            FilterChip(
                                selected = isSelected,
                                onClick = { viewModel.posSelectedCategory = category },
                                label = { Text(if (category == "ALL") LanguageManager.getString("All Items", "সব পণ্য") else category) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = StorePrimary,
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }
                }

                // Main Content Area (Products Grid / Cart)
                val hasCartItems by remember { derivedStateOf { viewModel.cartItems.isNotEmpty() } }

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
            val isWideScreen = maxWidth >= 600.dp

            if (isWideScreen) {
                // Tablet/Desktop Side-by-Side Layout
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(SurfaceWarm)
                            .padding(8.dp)
                    ) {
                        ProductsGridContent(
                            products = products,
                            filteredProducts = filteredProducts,
                            cartItems = viewModel.cartItems,
                            cartMap = viewModel.cartMap,
                            isBn = isBn,
                            hasCartItems = hasCartItems,
                            getActiveOffer = { viewModel.getActiveOfferForProduct(it) },
                            onProductClick = { prod ->
                                if (prod.getBarcodeVariants().isNotEmpty()) {
                                    selectedProductForVariantSelector = prod
                                } else {
                                    selectedProductForUnitSelector = prod
                                }
                            },
                            onQuickIncrement = { prod ->
                                if (prod.getBarcodeVariants().isNotEmpty()) {
                                    selectedProductForVariantSelector = prod
                                } else {
                                    viewModel.quickIncrementProductInCart(prod)
                                }
                            },
                            onQuickDecrement = { prod ->
                                if (prod.getBarcodeVariants().isNotEmpty()) {
                                    selectedProductForVariantSelector = prod
                                } else {
                                    viewModel.quickDecrementProductInCart(prod)
                                }
                            },
                            onClearFilters = {
                                viewModel.posSearchQuery = ""
                                viewModel.posSelectedCategory = "ALL"
                            },
                            onPhotoClick = { previewPosProductPhoto = it }
                        )
                    }

                    Surface(
                        modifier = Modifier
                            .widthIn(min = 380.dp, max = 460.dp)
                            .fillMaxHeight(),
                        shadowElevation = 6.dp,
                        color = CardBackground
                    ) {
                        CartSection(
                            viewModel = viewModel,
                            isBn = isBn,
                            customers = customers,
                            products = products,
                            onEditUnitQty = { index ->
                                if (index in viewModel.cartItems.indices) {
                                    val cartItem = viewModel.cartItems[index]
                                    if (cartItem.isVariant) {
                                        selectedProductForVariantSelector = cartItem.product
                                    } else {
                                        editingCartIndexForUnitSelector = index
                                    }
                                }
                            },
                            onSelectProduct = { prod ->
                                if (prod.getBarcodeVariants().isNotEmpty()) {
                                    selectedProductForVariantSelector = prod
                                } else {
                                    selectedProductForUnitSelector = prod
                                }
                            },
                            onPhotoClick = { previewPosProductPhoto = it }
                        )
                    }
                }
            } else {
                // Mobile Portrait Layout (<600dp width)
                Column(modifier = Modifier.fillMaxSize()) {
                    SecondaryTabRow(
                        selectedTabIndex = selectedMobileTab,
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = StorePrimary
                    ) {
                        Tab(
                            selected = selectedMobileTab == 0,
                            onClick = { selectedMobileTab = 0 },
                            text = {
                                Text(
                                    text = LanguageManager.getString("📦 Products (${filteredProducts.size})", "📦 পণ্যসমূহ (${filteredProducts.size})"),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        )
                        Tab(
                            selected = selectedMobileTab == 1,
                            onClick = { selectedMobileTab = 1 },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = LanguageManager.getString("🛒 Bill / Cart", "🛒 কার্ট/বিল"),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    PosCartTabBadge(itemCountProvider = { viewModel.cartItems.size })
                                }
                            }
                        )
                    }

                    AnimatedContent(
                        targetState = selectedMobileTab,
                        transitionSpec = {
                            if (targetState > initialState) {
                                slideInHorizontally { width -> width / 4 } + fadeIn() togetherWith
                                        slideOutHorizontally { width -> -width / 4 } + fadeOut()
                            } else {
                                slideInHorizontally { width -> -width / 4 } + fadeIn() togetherWith
                                        slideOutHorizontally { width -> width / 4 } + fadeOut()
                            }.using(SizeTransform(clip = false))
                        },
                        label = "mobileTabContent",
                        modifier = Modifier.weight(1f)
                    ) { targetTab ->
                        if (targetTab == 0) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(SurfaceWarm)
                                    .padding(8.dp)
                            ) {
                                ProductsGridContent(
                                    products = products,
                                    filteredProducts = filteredProducts,
                                    cartItems = viewModel.cartItems,
                                    cartMap = viewModel.cartMap,
                                    isBn = isBn,
                                    hasCartItems = hasCartItems,
                                    getActiveOffer = { viewModel.getActiveOfferForProduct(it) },
                                    onProductClick = { prod ->
                                        if (prod.getBarcodeVariants().isNotEmpty()) {
                                            selectedProductForVariantSelector = prod
                                        } else {
                                            selectedProductForUnitSelector = prod
                                        }
                                    },
                                    onQuickIncrement = { prod ->
                                        if (prod.getBarcodeVariants().isNotEmpty()) {
                                            selectedProductForVariantSelector = prod
                                        } else {
                                            viewModel.quickIncrementProductInCart(prod)
                                        }
                                    },
                                    onQuickDecrement = { prod ->
                                        if (prod.getBarcodeVariants().isNotEmpty()) {
                                            selectedProductForVariantSelector = prod
                                        } else {
                                            viewModel.quickDecrementProductInCart(prod)
                                        }
                                    },
                                    onClearFilters = {
                                        viewModel.posSearchQuery = ""
                                        viewModel.posSelectedCategory = "ALL"
                                    },
                                    onPhotoClick = { previewPosProductPhoto = it }
                                )

                                PosFloatingCartOverlay(
                                    viewModel = viewModel,
                                    isBn = isBn,
                                    onViewBill = { selectedMobileTab = 1 },
                                    modifier = Modifier.align(Alignment.BottomCenter)
                                )
                            }
                        } else {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = CardBackground
                            ) {
                                CartSection(
                                    viewModel = viewModel,
                                    isBn = isBn,
                                    customers = customers,
                                    products = products,
                                    onEditUnitQty = { index ->
                                        if (index in viewModel.cartItems.indices) {
                                            val cartItem = viewModel.cartItems[index]
                                            if (cartItem.isVariant) {
                                                selectedProductForVariantSelector = cartItem.product
                                            } else {
                                                editingCartIndexForUnitSelector = index
                                            }
                                        }
                                    },
                                    onSelectProduct = { prod ->
                                        if (prod.getBarcodeVariants().isNotEmpty()) {
                                            selectedProductForVariantSelector = prod
                                        } else {
                                            selectedProductForUnitSelector = prod
                                        }
                                    },
                                    onAddMoreItems = { selectedMobileTab = 0 },
                                    onScanBarcode = { showBarcodeScannerModal = true },
                                    onPhotoClick = { previewPosProductPhoto = it }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

    // Checkout Success Modal styled with high-fidelity UPI / POS Transaction UI
    if (viewModel.showCheckoutSuccessDialog && viewModel.lastCompletedSale != null) {
        val completedSale = viewModel.lastCompletedSale!!
        var showEditPdfFormatDialog by remember { mutableStateOf(false) }

        com.example.ui.components.TransactionSuccessReceiptModal(
            saleWithItems = completedSale,
            customers = customers,
            printerStatusMessage = viewModel.printerStatusMessage,
            onDismiss = { viewModel.showCheckoutSuccessDialog = false },
            onPrintThermal = { viewModel.printCurrentSale(completedSale) },
            onPrintThermalWithLang = { isBn -> viewModel.printCurrentSale(completedSale, isBengali = isBn) },
            onNewSale = {
                viewModel.showCheckoutSuccessDialog = false
                viewModel.clearCart()
            },
            onOpenPdfSettings = { showEditPdfFormatDialog = true }
        )

        if (showEditPdfFormatDialog) {
            com.example.ui.components.EditPdfFormatDialog(onDismiss = { showEditPdfFormatDialog = false })
        }
    }

    // Insufficient Stock Alert / Block Modal
    if (viewModel.showInsufficientStockDialog && viewModel.stockValidationError != null) {
        val shortageException = viewModel.stockValidationError!!
        val isPrivileged = viewModel.canOverrideStockShortage()

        com.example.ui.components.InsufficientStockWarningDialog(
            exception = shortageException,
            isPrivilegedUser = isPrivileged,
            isBn = isBn,
            onDismiss = { viewModel.showInsufficientStockDialog = false },
            onOverrideAndComplete = {
                viewModel.showInsufficientStockDialog = false
                viewModel.completeCheckout(
                    isHold = viewModel.pendingSaleIsHold,
                    allowNegativeStockOverride = true
                )
            },
            onCorrectStock = { shortageItem ->
                val targetProduct = products.find { it.id == shortageItem.productId }
                if (targetProduct != null) {
                    productForQuickStockCorrection = Pair(targetProduct, shortageItem)
                } else {
                    android.widget.Toast.makeText(currentContext, "Product not found", android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            onQuickMatchStock = { shortageItem ->
                viewModel.quickMatchProductStock(shortageItem.productId, shortageItem.requestedQuantity)
                android.widget.Toast.makeText(
                    currentContext,
                    if (isBn) "${shortageItem.productNameBn.ifBlank { shortageItem.productNameEn }} এর মজুদ ${shortageItem.formatRequested()} ${shortageItem.unitType} এ আপডেট করা হয়েছে"
                    else "Stock updated to ${shortageItem.formatRequested()} ${shortageItem.unitType}",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            },
            onSellAvailableSingle = { shortageItem ->
                viewModel.adjustSingleCartItemToAvailable(shortageItem)
                android.widget.Toast.makeText(
                    currentContext,
                    if (isBn) "কার্টের পরিমাণ মজুদ (${shortageItem.formatAvailable()} ${shortageItem.unitType}) অনুযায়ী পরিবর্তন করা হয়েছে"
                    else "Cart updated to available stock (${shortageItem.formatAvailable()} ${shortageItem.unitType})",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            },
            onAdjustCartToAvailable = {
                viewModel.adjustCartToAvailableStock(shortageException.items)
                android.widget.Toast.makeText(
                    currentContext,
                    if (isBn) "কার্ট বিদ্যমান মজুদের সাথে সমন্বয় করা হয়েছে"
                    else "Cart adjusted to available stock",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            },
            onQuickMatchAllStocks = {
                viewModel.quickMatchAllStocks(shortageException.items)
                android.widget.Toast.makeText(
                    currentContext,
                    if (isBn) "সকল পণ্যের মজুদ কার্টের পরিমাণের সাথে সমন্বয় করা হয়েছে"
                    else "All stocks matched to cart quantities",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        )
    }

    // Customer Credit Limit Exceeded Alert / Owner Authorization Modal
    if (viewModel.showCreditLimitExceededDialog && viewModel.creditLimitValidationError != null) {
        val creditException = viewModel.creditLimitValidationError!!
        val isPrivileged = viewModel.canOverrideCreditLimit()

        com.example.ui.components.CreditLimitExceededWarningDialog(
            exception = creditException,
            isPrivilegedUser = isPrivileged,
            isBn = isBn,
            onDismiss = { viewModel.showCreditLimitExceededDialog = false },
            onOverrideAndComplete = {
                viewModel.showCreditLimitExceededDialog = false
                viewModel.completeCheckout(
                    isHold = viewModel.pendingSaleIsHold,
                    allowNegativeStockOverride = false,
                    allowCreditLimitOverride = true
                )
            }
        )
    }

    // Automated Credit Sale SMS Runtime Permission Explanation Modal
    if (viewModel.showSmsPermissionExplanationDialog && viewModel.pendingCreditSms != null) {
        val pending = viewModel.pendingCreditSms!!
        com.example.ui.components.CreditSmsPermissionDialog(
            pendingSms = pending,
            isBn = isBn,
            onRequestPermission = {
                smsPermissionLauncher.launch(android.Manifest.permission.SEND_SMS)
            },
            onDismiss = {
                viewModel.dismissSmsPermissionExplanation()
            }
        )
    }

    // Quick Stock Correction Modal on the spot without leaving POS
    if (productForQuickStockCorrection != null) {
        val (targetProduct, shortageItem) = productForQuickStockCorrection!!
        com.example.ui.components.QuickStockCorrectionDialog(
            product = targetProduct,
            shortageItem = shortageItem,
            isBn = isBn,
            onDismiss = { productForQuickStockCorrection = null },
            onSaveStock = { updatedProduct ->
                viewModel.saveProduct(updatedProduct)
                // If warning dialog was open, dismiss it and clear error so bill checkout can proceed seamlessly
                viewModel.showInsufficientStockDialog = false
                viewModel.stockValidationError = null
                productForQuickStockCorrection = null
                android.widget.Toast.makeText(
                    currentContext,
                    if (isBn) "${targetProduct.getDisplayName(true)} এর মজুদ সফলভাবে আপডেট হয়েছে (${updatedProduct.getFormattedStockDisplay(true)})"
                    else "Stock updated for ${targetProduct.nameEn} to ${updatedProduct.getFormattedStockDisplay(false)}",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        )
    }

    // Unit & Quantity Selector Modal for Product Quick Add
    if (selectedProductForUnitSelector != null) {
        val prod = selectedProductForUnitSelector!!
        val existingItem = viewModel.cartItems.find { it.product.id == prod.id }
        UnitAndQtySelectorModal(
            product = prod,
            initialUnit = existingItem?.unitType,
            initialQty = existingItem?.quantity,
            isBn = isBn,
            onDismiss = { selectedProductForUnitSelector = null },
            onConfirm = { unit, qty ->
                android.util.Log.d("POS_TRACE", "[MODAL_ADD_CONFIRM] Product=${prod.nameEn}, ConfirmedQty=$qty $unit, ExistingCartItem=${existingItem != null}")
                viewModel.setOrUpdateProductInCartWithUnit(prod, qty, unit)
                selectedProductForUnitSelector = null
            }
        )
    }

    // Unit & Quantity Selector Modal for Editing Cart Item
    if (editingCartIndexForUnitSelector != null) {
        val idx = editingCartIndexForUnitSelector!!
        if (idx in viewModel.cartItems.indices) {
            val cartItem = viewModel.cartItems[idx]
            UnitAndQtySelectorModal(
                product = cartItem.product,
                initialUnit = cartItem.unitType,
                initialQty = cartItem.quantity,
                isBn = isBn,
                onDismiss = { editingCartIndexForUnitSelector = null },
                onConfirm = { unit, qty ->
                    android.util.Log.d("POS_TRACE", "[MODAL_EDIT_CONFIRM] Index=$idx, Product=${cartItem.product.nameEn}, ConfirmedQty=$qty $unit")
                    viewModel.updateCartItemWithUnit(idx, cartItem.product, qty, unit)
                    editingCartIndexForUnitSelector = null
                }
            )
        }
    }

    // Barcode Variant Pack Selector Modal for POS
    if (selectedProductForVariantSelector != null) {
        val prod = selectedProductForVariantSelector!!
        PosVariantSelectorModal(
            product = prod,
            viewModel = viewModel,
            isBn = isBn,
            onDismiss = { selectedProductForVariantSelector = null },
            onOpenLooseWeightModal = {
                val p = prod
                selectedProductForVariantSelector = null
                selectedProductForUnitSelector = p
            }
        )
    }



    // Camera Barcode Scanner Modal
    if (showBarcodeScannerModal) {
        BarcodeScannerModal(
            viewModel = viewModel,
            onDismiss = { showBarcodeScannerModal = false }
        )
    }

    // Held Bills Sheet
    if (showHeldBillsSheet) {
        AlertDialog(
            onDismissRequest = { showHeldBillsSheet = false },
            title = {
                Text(
                    LanguageManager.getString("Held Bills", "আটকে রাখা বিলসমূহ"),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                if (heldSales.isEmpty()) {
                    Text(LanguageManager.getString("No held bills", "কোন বিল আটকে নেই"))
                } else {
                    LazyColumn {
                        items(heldSales, key = { it.sale.id }, contentType = { "HELD_SALE_CARD" }) { saleWithItems ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(containerColor = SurfaceWarm)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .padding(12.dp)
                                        .fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            "Bill #${saleWithItems.sale.id.takeLast(6)}",
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text("Total: ₹%.2f".format(saleWithItems.sale.finalAmount))
                                        Text("${saleWithItems.items.size} items", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Button(
                                        onClick = {
                                            viewModel.resumeHeldSale(saleWithItems)
                                            showHeldBillsSheet = false
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = StorePrimary)
                                    ) {
                                        Text(LanguageManager.getString("Resume", "পুনরায় খুলুন"))
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showHeldBillsSheet = false }) {
                    Text(LanguageManager.getString("Close", "বন্ধ করুন"))
                }
            }
        )
    }

    // Daily Sales Summary Modal
    if (showDailySummaryDialog) {
        DailySalesSummaryDialog(
            viewModel = viewModel,
            onDismiss = { showDailySummaryDialog = false }
        )
    }

    if (previewPosProductPhoto != null) {
        val prod = previewPosProductPhoto!!
        com.example.ui.components.ZoomablePaymentScreenshotDialog(
            imageModel = com.example.utils.ImageSyncHelper.getImageModel(prod.imageUri),
            title = prod.getDisplayName(isBn),
            subtitle = "₹${"%.2f".format(prod.sellingPrice)} • Stock: ${prod.currentStock} ${prod.unitType}",
            onDismiss = { previewPosProductPhoto = null }
        )
    }

    var showManageEmployeesInPos by remember { mutableStateOf(false) }

    // Staff Switch Dialog
    if (showStaffSwitchDialog) {
        com.example.ui.components.StaffSwitchDialog(
            employees = allEmployees,
            onDismiss = { showStaffSwitchDialog = false },
            onManageEmployees = {
                showManageEmployeesInPos = true
            }
        )
    }

    // Manage Employees Dialog from POS
    if (showManageEmployeesInPos) {
        com.example.ui.components.ManageEmployeesDialog(
            viewModel = viewModel,
            onDismiss = { showManageEmployeesInPos = false }
        )
    }

    // Quick Return & Replace Search Modal
    if (showQuickReturnSearchModal) {
        QuickReturnSearchDialog(
            allSales = allSales,
            allProducts = products,
            allReturns = allReturns,
            allCustomers = customers,
            onDismiss = { showQuickReturnSearchModal = false },
            onConfirmReturn = { saleReturn, returnItems ->
                val returnWithItems = com.example.data.local.entities.SaleReturnWithItems(saleReturn, returnItems)
                viewModel.processReturnOrReplacement(saleReturn, returnItems) {
                    showQuickReturnSearchModal = false
                    returnReceiptToShow = returnWithItems
                }
            },
            onViewPastReturn = { pastReturn ->
                showQuickReturnSearchModal = false
                returnReceiptToShow = pastReturn
            }
        )
    }

    // Return & Replacement Receipt Success / Detail Modal
    returnReceiptToShow?.let { returnWithItems ->
        com.example.ui.components.ReturnSuccessReceiptModal(
            returnWithItems = returnWithItems,
            onDismiss = { returnReceiptToShow = null },
            onPrintThermal = {
                viewModel.printSaleReturnReceipt(returnWithItems)
            },
            printerStatusMessage = viewModel.printerStatusMessage
        )
    }

    // Unified Offers Management Dialog
    if (showOffersDialog) {
        com.example.ui.screens.offers.OffersManagementDialog(
            viewModel = viewModel,
            onDismiss = { showOffersDialog = false }
        )
    }
}

@Composable
fun PosCartTabBadge(
    itemCountProvider: () -> Int,
    modifier: Modifier = Modifier
) {
    val count by remember { derivedStateOf { itemCountProvider() } }
    if (count > 0) {
        Spacer(modifier = Modifier.width(4.dp))
        Badge(
            containerColor = StorePrimary,
            contentColor = Color.White,
            modifier = modifier
        ) {
            Text("$count")
        }
    }
}

@Composable
fun PosFloatingCartOverlay(
    viewModel: StoreViewModel,
    isBn: Boolean,
    onViewBill: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasCartItems by remember { derivedStateOf { viewModel.cartItems.isNotEmpty() } }

    AnimatedVisibility(
        visible = hasCartItems,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier
    ) {
        Column {
            PosFloatingCartChipsStrip(
                cartItems = viewModel.cartItems,
                isBn = isBn,
                onRemoveItem = { idx -> viewModel.removeFromCart(idx) }
            )
            PosFloatingTotalBar(
                itemCountProvider = { viewModel.cartItems.size },
                totalProvider = { viewModel.cartFinalTotal },
                onViewBill = onViewBill
            )
        }
    }
}

@Composable
fun PosFloatingCartChipsStrip(
    cartItems: List<CartItem>,
    isBn: Boolean,
    onRemoveItem: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        itemsIndexed(
            items = cartItems,
            key = { _, cartItem -> "${cartItem.product.id}_${cartItem.variantBarcode ?: "std"}_${cartItem.isFreeGift}" },
            contentType = { _, _ -> "POS_CART_CHIP" }
        ) { idx, cartItem ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = CardBackground,
                shadowElevation = 3.dp,
                border = BorderStroke(1.dp, if (cartItem.isFreeGift) Color(0xFF0D9488) else StorePrimary.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val qtyLabel = if (cartItem.quantity % 1.0 == 0.0) "${cartItem.quantity.toInt()}" else "%.1f".format(cartItem.quantity)
                    Text(
                        text = cartItem.product.getDisplayName(isBn),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextDark,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Surface(
                        color = if (cartItem.isFreeGift) Color(0xFF0D9488) else StoreGreenProfit,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = if (cartItem.isFreeGift) "FREE ($qtyLabel ${cartItem.unitType})" else "$qtyLabel ${cartItem.unitType}",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Surface(
                        onClick = { onRemoveItem(idx) },
                        shape = CircleShape,
                        color = StoreRedAlert.copy(alpha = 0.12f),
                        modifier = Modifier.size(18.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Close, contentDescription = "Remove", tint = StoreRedAlert, modifier = Modifier.size(12.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PosFloatingTotalBar(
    itemCountProvider: () -> Int,
    totalProvider: () -> Double,
    onViewBill: () -> Unit,
    modifier: Modifier = Modifier
) {
    val itemCount by remember { derivedStateOf { itemCountProvider() } }
    val cartTotal by remember { derivedStateOf { totalProvider() } }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp)
            .clickable { onViewBill() },
        shape = RoundedCornerShape(12.dp),
        color = StorePrimary,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ShoppingBag, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "$itemCount ${if (itemCount == 1) "item" else "items"} in bill",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "Total: ₹%.2f".format(cartTotal),
                        style = MaterialTheme.typography.labelMedium,
                        color = StoreGold
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color.White.copy(alpha = 0.2f)
            ) {
                Text(
                    text = LanguageManager.getString("View Bill ➔", "বিল দেখুন ➔"),
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
fun ProductsGridContent(
    products: List<Product>,
    filteredProducts: List<Product>,
    cartItems: List<CartItem> = emptyList(),
    cartMap: Map<String, CartItem> = emptyMap(),
    isBn: Boolean,
    hasCartItems: Boolean = false,
    getActiveOffer: (String) -> Offer? = { null },
    onProductClick: (Product) -> Unit,
    onQuickIncrement: ((Product) -> Unit)? = null,
    onQuickDecrement: ((Product) -> Unit)? = null,
    onClearFilters: () -> Unit,
    onPhotoClick: ((Product) -> Unit)? = null
) {
    if (products.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.Storefront,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = StorePrimary
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = LanguageManager.getString("No products in store database yet", "দোকানে এখনও কোন পণ্য যুক্ত করা নেই"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = LanguageManager.getString("Go to the Inventory tab to add products and start billing.", "ইনভেন্টরি সেকশনে নতুন পণ্য যুক্ত করে বিক্রি শুরু করুন।"),
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                textAlign = TextAlign.Center
            )
        }
    } else if (filteredProducts.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.SearchOff,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = TextMuted
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = LanguageManager.getString("No products match search criteria", "কোন পণ্য খুঁজে পাওয়া যায়নি"),
                style = MaterialTheme.typography.bodyLarge,
                color = TextMuted
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onClearFilters
            ) {
                Text(LanguageManager.getString("Clear Filters", "ফিল্টার মুছুন"))
            }
        }
    } else {
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Adaptive(minSize = 135.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalItemSpacing = 8.dp,
            contentPadding = PaddingValues(
                start = 8.dp,
                end = 8.dp,
                top = 8.dp,
                bottom = if (hasCartItems) 110.dp else 16.dp
            ),
            modifier = Modifier.fillMaxSize()
        ) {
            items(filteredProducts, key = { it.id }) { product ->
                val matchingCartItems by remember(product.id, cartItems) {
                    derivedStateOf {
                        cartItems.filter { it.product.id == product.id && !it.isFreeGift }
                    }
                }
                val cardCartItem by remember(product.id, cartMap, cartItems) {
                    derivedStateOf {
                        if (cartMap.isNotEmpty() || cartItems.isEmpty()) {
                            cartMap[product.id]
                        } else {
                            matchingCartItems.firstOrNull()
                        }
                    }
                }
                val activeOffer = remember(product.id) { getActiveOffer(product.id) }
                ProductCard(
                    product = product,
                    cartItem = cardCartItem,
                    allCartItemsForProduct = matchingCartItems,
                    activeOffer = activeOffer,
                    isBn = isBn,
                    onClick = { onProductClick(product) },
                    onQuickIncrement = { onQuickIncrement?.invoke(product) },
                    onQuickDecrement = { onQuickDecrement?.invoke(product) },
                    onPhotoClick = { onPhotoClick?.invoke(product) },
                    modifier = Modifier.animateItem()
                )
            }
        }
    }
}

@Composable
fun ProductCard(
    product: Product,
    cartItem: CartItem? = null,
    allCartItemsForProduct: List<CartItem> = emptyList(),
    activeOffer: Offer? = null,
    isBn: Boolean,
    onClick: () -> Unit,
    onQuickIncrement: (() -> Unit)? = null,
    onQuickDecrement: (() -> Unit)? = null,
    onPhotoClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val primaryName = if (isBn) {
        product.nameBn.ifBlank { product.nameEn }
    } else {
        product.nameEn.ifBlank { product.nameBn }
    }.ifBlank { "Item" }

    val secondaryName = if (isBn) {
        if (product.nameBn.isNotBlank() && product.nameEn.isNotBlank()) product.nameEn else ""
    } else {
        if (product.nameEn.isNotBlank() && product.nameBn.isNotBlank()) product.nameBn else ""
    }

    val isLowStock = product.currentStock <= product.lowStockThreshold
    val secUnit = product.getEffectiveSecondaryUnit()
    val secRatio = product.getEffectiveSecondaryRatio()
    val variants = remember(product.barcodeVariantsJson) { product.getBarcodeVariants() }
    val hasVariants = variants.isNotEmpty()
    val totalPacks = if (hasVariants) allCartItemsForProduct.sumOf { it.quantity } else 0.0
    val isInCart = cartItem != null || totalPacks > 0.0

    val targetBorderColor = if (isInCart) StoreGreenProfit else if (activeOffer != null) Color(0xFFEA580C).copy(alpha = 0.5f) else TextMuted.copy(alpha = 0.15f)
    val animatedBorderColor by animateColorAsState(targetValue = targetBorderColor, animationSpec = tween(180), label = "cardBorderColor")
    val animatedContainerColor by animateColorAsState(targetValue = if (isInCart) SurfaceWarm else CardBackground, animationSpec = tween(180), label = "cardContainerColor")
    val animatedElevation by animateDpAsState(targetValue = if (isInCart) 4.dp else 1.5.dp, animationSpec = tween(180), label = "cardElevation")

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("pos_product_card_${product.id}"),
        onClick = onClick,
        elevation = CardDefaults.cardElevation(animatedElevation),
        colors = CardDefaults.cardColors(containerColor = animatedContainerColor),
        border = BorderStroke(
            width = if (isInCart) 2.dp else 1.dp,
            color = animatedBorderColor
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(8.dp)
        ) {
            // Cart Status Badge at top if added to bill
            if (isInCart) {
                Surface(
                    color = StoreGreenProfit,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.ShoppingCart, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        val badgeLabel = if (hasVariants && totalPacks > 0.0) {
                            val packQtyStr = if (totalPacks % 1.0 == 0.0) "${totalPacks.toInt()}" else "%.1f".format(totalPacks)
                            if (isBn) "বিলে আছে: ${packQtyStr}টি প্যাক" else "In Bill: $packQtyStr Packs"
                        } else if (cartItem != null) {
                            val qtyStr = if (cartItem.quantity % 1.0 == 0.0) {
                                "${cartItem.quantity.toInt()}"
                            } else {
                                "%.2f".format(cartItem.quantity).trimEnd('0').trimEnd('.')
                            }
                            val unitLabel = if (cartItem.unitType.equals("piece", ignoreCase = true) && isBn) "পিস" else cartItem.unitType
                            if (isBn) "বিলে আছে: $qtyStr $unitLabel" else "In Bill: $qtyStr $unitLabel"
                        } else ""
                        Text(
                            text = badgeLabel,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    if (!product.imageUri.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(SurfaceWarm)
                                .clickable { onPhotoClick?.invoke() ?: onClick() },
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = com.example.utils.ImageSyncHelper.getImageModel(product.imageUri),
                                contentDescription = primaryName,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = primaryName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextDark,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (secondaryName.isNotBlank()) {
                            Text(
                                text = secondaryName,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                val expiryStatus = product.getExpiryStatus()

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (hasVariants) {
                        Surface(
                            color = StorePrimary.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.QrCode2,
                                    contentDescription = null,
                                    tint = StorePrimary,
                                    modifier = Modifier.size(10.dp)
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = if (isBn) "${variants.size}টি সাইজ" else "${variants.size} Packs",
                                    color = StorePrimary,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.sp
                                )
                            }
                        }
                    }

                    if (activeOffer != null) {
                        Surface(
                            color = Color(0xFFEA580C),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.LocalOffer,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(10.dp)
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = activeOffer.getBadgeText(isBn),
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.sp
                                )
                            }
                        }
                    }

                    if (expiryStatus == ExpiryStatus.EXPIRED) {
                        Surface(
                            color = StoreRedAlert,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = if (isBn) "মেয়াদ শেষ" else "Expired",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else if (expiryStatus == ExpiryStatus.EXPIRING_SOON) {
                        Surface(
                            color = StoreSaffronAccent,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = if (isBn) "মেয়াদপ্রায়" else "Exp Soon",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (isLowStock) {
                        Surface(
                            color = StoreRedAlert.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = if (isBn) "কম" else "Low",
                                color = StoreRedAlert,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Primary Price
            val primaryUnitLabel = if (product.hasBoxPricing() || product.unitType.equals("box", ignoreCase = true)) {
                val pUnit = if (product.unitType.equals("box", ignoreCase = true)) "piece" else product.unitType
                BengaliReceiptTranslator.translateUnit(pUnit, isBn)
            } else {
                BengaliReceiptTranslator.translateUnit(product.unitType, isBn)
            }

            if (hasVariants) {
                val minPrice = variants.minOfOrNull { it.price } ?: product.sellingPrice
                val maxPrice = variants.maxOfOrNull { it.price } ?: product.sellingPrice
                val priceLabel = if (minPrice == maxPrice) {
                    "₹%.2f".format(minPrice)
                } else {
                    "₹%.0f - ₹%.0f".format(minPrice, maxPrice)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = priceLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = StoreGreenProfit
                    )
                    Text(
                        text = if (isBn) "(${variants.size}টি মাপ)" else "(${variants.size} sizes)",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        fontSize = 10.5.sp
                    )
                }
            } else if (activeOffer != null && activeOffer.getOfferTypeEnum() == com.example.data.local.entities.OfferType.PERCENT_DISCOUNT && activeOffer.discountValue > 0) {
                val discPrice = (product.sellingPrice * (1.0 - (activeOffer.discountValue / 100.0))).coerceAtLeast(0.0)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "₹%.2f / %s".format(discPrice, primaryUnitLabel),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = StoreGreenProfit
                    )
                    Text(
                        text = "₹%.2f".format(product.sellingPrice),
                        style = MaterialTheme.typography.bodySmall.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough),
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }
            } else if (activeOffer != null && activeOffer.getOfferTypeEnum() == com.example.data.local.entities.OfferType.FLAT_DISCOUNT && activeOffer.discountValue > 0) {
                val discPrice = (product.sellingPrice - activeOffer.discountValue).coerceAtLeast(0.0)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "₹%.2f / %s".format(discPrice, primaryUnitLabel),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = StoreGreenProfit
                    )
                    Text(
                        text = "₹%.2f".format(product.sellingPrice),
                        style = MaterialTheme.typography.bodySmall.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough),
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }
            } else if (activeOffer != null && activeOffer.getOfferTypeEnum() == com.example.data.local.entities.OfferType.BUY_X_GET_Y) {
                val buyQ = activeOffer.buyQty.coerceAtLeast(1.0)
                val getQ = activeOffer.getQty.coerceAtLeast(1.0)
                val setSize = buyQ + getQ
                val effUnitPrice = (product.sellingPrice * buyQ) / setSize
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "₹%.2f / %s".format(product.sellingPrice, primaryUnitLabel),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = StorePrimary
                    )
                    Surface(
                        shape = RoundedCornerShape(3.dp),
                        color = Color(0xFFEA580C).copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = if (isBn) "কার্যকর: ₹%.2f".format(effUnitPrice) else "Eff: ₹%.2f".format(effUnitPrice),
                            color = Color(0xFFEA580C),
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                        )
                    }
                }
            } else {
                Text(
                    text = "₹%.2f / %s".format(product.sellingPrice, primaryUnitLabel),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = StorePrimary
                )
            }

            if (product.wholesalePrice > 0.0) {
                Text(
                    text = "WS: ₹%.2f / %s".format(product.wholesalePrice, primaryUnitLabel),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = StoreGreenProfit
                )
            }

            // Secondary / Bulk Unit Price display if applicable
            if (product.hasBulkPricing()) {
                val bPrice = product.getEffectiveBulkPrice()
                val bUnit = product.getEffectiveBulkUnit()
                val bQty = product.getEffectiveBulkQuantity()
                val qtyStr = if (bQty % 1.0 == 0.0) bQty.toInt().toString() else "%.1f".format(bQty)
                val baseUnitLabel = BengaliReceiptTranslator.translateUnit(if (product.unitType.equals("box", ignoreCase = true)) "piece" else product.unitType, isBn)
                val bulkUnitLabel = BengaliReceiptTranslator.translateUnit(bUnit, isBn)
                Text(
                    text = "$bulkUnitLabel: ₹%.0f ($qtyStr $baseUnitLabel)".format(bPrice),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = StoreBlueUPI
                )
            } else if (product.isSecondaryUnitSupported()) {
                val secPrice = if (secUnit.lowercase() in listOf("gram", "ml")) {
                    (100.0 / secRatio) * product.sellingPrice
                } else {
                    product.sellingPrice / secRatio
                }
                val secLabel = if (secUnit.lowercase() in listOf("gram", "ml")) "100$secUnit" else "1 $secUnit"
                Text(
                    text = "(₹%.2f / %s)".format(secPrice, secLabel),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Bottom row: Stock & Add / Stepper controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Stock: ${product.getFormattedStockDisplay(isBn)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isLowStock) StoreRedAlert else TextMuted,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                Spacer(modifier = Modifier.width(2.dp))

                if (hasVariants) {
                    Surface(
                        onClick = onClick,
                        shape = RoundedCornerShape(8.dp),
                        color = if (isInCart) StoreGreenProfit else StorePrimary,
                        modifier = Modifier.testTag("product_variant_action_${product.id}")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                if (isInCart) Icons.Default.Check else Icons.Default.Layers,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            val packQtyStr = if (totalPacks > 0.0) {
                                if (totalPacks % 1.0 == 0.0) "${totalPacks.toInt()}" else "%.1f".format(totalPacks)
                            } else ""
                            Text(
                                text = if (totalPacks > 0.0) {
                                    if (isBn) "প্যাক আছে ($packQtyStr)" else "Packs in Bill ($packQtyStr)"
                                } else {
                                    if (isBn) "প্যাক বাছুন" else "Packs"
                                },
                                color = Color.White,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    AnimatedContent(
                        targetState = isInCart && cartItem != null,
                        transitionSpec = {
                            (fadeIn(animationSpec = tween(150)) + scaleIn(initialScale = 0.82f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)))
                                .togetherWith(fadeOut(animationSpec = tween(100)) + scaleOut(targetScale = 0.82f))
                        },
                        label = "cartButtonTransition"
                    ) { hasCartItem ->
                        if (hasCartItem && cartItem != null) {
                            // Active Stepper inside Card: [-] [qty] [+]
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Surface(
                                    onClick = { onQuickDecrement?.invoke() },
                                    shape = CircleShape,
                                    color = Color(0xFFFFDADA),
                                    modifier = Modifier
                                        .size(28.dp)
                                        .testTag("stepper_decrement_${product.id}")
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.Remove,
                                            contentDescription = "Decrease Quantity",
                                            tint = Color(0xFFD32F2F),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }

                                Surface(
                                    onClick = onClick,
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFFE2EEDE),
                                    modifier = Modifier
                                        .height(28.dp)
                                        .defaultMinSize(minWidth = 24.dp)
                                        .testTag("stepper_qty_${product.id}")
                                ) {
                                    val qtyText = if (cartItem.quantity % 1.0 == 0.0) {
                                        "${cartItem.quantity.toInt()}"
                                    } else {
                                        "%.2f".format(cartItem.quantity).trimEnd('0').trimEnd('.')
                                    }
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.padding(horizontal = 6.dp)
                                    ) {
                                        Text(
                                            text = qtyText,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF1E6B35)
                                        )
                                    }
                                }

                                Surface(
                                    onClick = {
                                        android.util.Log.d("POS_TRACE", "[STEPPER_INC] Product=${product.nameEn}")
                                        onQuickIncrement?.invoke()
                                    },
                                    shape = CircleShape,
                                    color = Color(0xFF23783A),
                                    modifier = Modifier
                                        .size(28.dp)
                                        .testTag("stepper_increment_${product.id}")
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.Add,
                                            contentDescription = "Increase Quantity",
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        } else {
                            Surface(
                                onClick = {
                                    android.util.Log.d("POS_TRACE", "[PRODUCT_ADD_CLICK] Product=${product.nameEn}")
                                    onQuickIncrement?.invoke() ?: onClick()
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = StorePrimary,
                                modifier = Modifier
                                    .testTag("product_add_button_${product.id}")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = if (isBn) "যোগ" else "Add",
                                        color = Color.White,
                                        fontSize = 12.sp,
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

@Composable
fun CartSection(
    viewModel: StoreViewModel,
    isBn: Boolean,
    customers: List<Customer>,
    products: List<Product> = emptyList(),
    onEditUnitQty: (Int) -> Unit,
    onSelectProduct: ((Product) -> Unit)? = null,
    onAddMoreItems: (() -> Unit)? = null,
    onScanBarcode: (() -> Unit)? = null,
    onPhotoClick: ((Product) -> Unit)? = null
) {
    var showCustomerPicker by remember { mutableStateOf(false) }
    var showAddCustomerDialog by remember { mutableStateOf(false) }
    var showDiscountDialog by remember { mutableStateOf(false) }
    var showReceivedAmountDialog by remember { mutableStateOf(false) }
    var showCreditLimitExceededDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        // Cart Title Header (Compact & High-Efficiency)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onAddMoreItems != null) {
                    IconButton(
                        onClick = onAddMoreItems,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Back to items",
                            tint = StorePrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(2.dp))
                }
                Text(
                    text = LanguageManager.getString("Current Bill", "চলতি বিল"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextDark,
                    fontSize = 14.sp
                )
                if (viewModel.cartItems.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        color = StorePrimary.copy(alpha = 0.12f),
                        shape = CircleShape
                    ) {
                        Text(
                            text = "${viewModel.cartItems.size}",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = StorePrimary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.5.dp)
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onScanBarcode != null) {
                    IconButton(
                        onClick = onScanBarcode,
                        modifier = Modifier
                            .background(StorePrimary.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                            .size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.QrCodeScanner,
                            contentDescription = "Scan Barcode",
                            tint = StorePrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                if (onAddMoreItems != null) {
                    OutlinedButton(
                        onClick = onAddMoreItems,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, StorePrimary)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(13.dp), tint = StorePrimary)
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = LanguageManager.getString("Add", "পণ্য যোগ"),
                            color = StorePrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        if (viewModel.cartItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Surface(
                        shape = CircleShape,
                        color = SurfaceWarm,
                        modifier = Modifier.size(64.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.ShoppingBag,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = TextMuted.copy(alpha = 0.6f)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = LanguageManager.getString("Cart is empty", "কার্ট খালি রয়েছে"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextDark
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = LanguageManager.getString("Select items from the catalog to build a bill", "বিল তৈরি করতে ক্যাটালগ থেকে পণ্য নির্বাচন করুন"),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        textAlign = TextAlign.Center
                    )
                    if (onAddMoreItems != null) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onAddMoreItems,
                            colors = ButtonDefaults.buttonColors(containerColor = StorePrimary),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = LanguageManager.getString("Browse Products", "পণ্য পছন্দ করুন"),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(key = "free_gift_promo_section") {
                    FreeGiftPromoSection(viewModel = viewModel, isBn = isBn)
                }

                itemsIndexed(
                    items = viewModel.cartItems,
                    key = { _, item -> "${item.product.id}_${item.variantBarcode ?: "std"}_${item.isFreeGift}" },
                    contentType = { _, item -> if (item.isFreeGift) "CART_ITEM_FREE_GIFT" else "CART_ITEM_NORMAL" }
                ) { index, item ->
                    val appliedOffer = remember(item.quantity, item.unitType, item.product.id, viewModel.isWholesaleBillingMode, viewModel.activeOffers.value.size) {
                        viewModel.calculateCartItemOfferDiscount(item, viewModel.isWholesaleBillingMode)
                    }
                    val bogoPrompt = remember(item.quantity, item.unitType, item.product.id, viewModel.activeOffers.value.size) {
                        viewModel.getBogoPromptForCartItem(item)
                    }
                    CartItemRow(
                        item = item,
                        itemIndex = index + 1,
                        isBn = isBn,
                        isWholesaleBillingMode = viewModel.isWholesaleBillingMode,
                        appliedOffer = appliedOffer,
                        bogoPrompt = bogoPrompt,
                        onPhotoClick = { onPhotoClick?.invoke(item.product) },
                        onAddFreeItem = {
                            val todayStr = com.example.data.local.entities.Offer.getTodayDateString()
                            val bogoOffer = viewModel.activeOffers.value.firstOrNull { 
                                it.appliesToProduct(item.product.id, todayStr) && it.getOfferTypeEnum() == com.example.data.local.entities.OfferType.BUY_X_GET_Y 
                            }
                            if (bogoOffer != null) {
                                val buyQ = bogoOffer.buyQty.coerceAtLeast(1.0)
                                val getQ = bogoOffer.getQty.coerceAtLeast(1.0)
                                val setSize = buyQ + getQ
                                val remainder = item.quantity % setSize
                                val needed = setSize - remainder
                                viewModel.updateCartItemQuantity(index, item.quantity + needed)
                            }
                        },
                        onIncrease = {
                            val addStep = if (Product.isGramUnit(item.unitType) || Product.isMlUnit(item.unitType)) 100.0 else 1.0
                            viewModel.updateCartItemQuantity(index, item.quantity + addStep)
                        },
                        onDecrease = {
                            val subStep = if (Product.isGramUnit(item.unitType) || Product.isMlUnit(item.unitType)) 100.0 else 1.0
                            viewModel.updateCartItemQuantity(index, item.quantity - subStep)
                        },
                        onRemove = { viewModel.removeFromCart(index) },
                        onEditUnitQty = { onEditUnitQty(index) },
                        onToggleWholesale = { viewModel.toggleCartItemWholesale(index) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = TextMuted.copy(alpha = 0.2f))
        Spacer(modifier = Modifier.height(8.dp))

        // Streamlined Compact Payment & Billing Section
        Spacer(modifier = Modifier.height(4.dp))
        HorizontalDivider(color = TextMuted.copy(alpha = 0.15f))
        Spacer(modifier = Modifier.height(4.dp))

        // Row 1: Payment Mode Selection (Cash | UPI | Credit)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("CASH", "UPI", "CREDIT").forEach { mode ->
                val isSelected = viewModel.selectedPaymentMode == mode || (viewModel.selectedPaymentMode != "UPI" && viewModel.selectedPaymentMode != "CREDIT" && mode == "CASH")
                val (modeLabel, icon) = when (mode) {
                    "CASH" -> (if (isBn) "নগদ" else "Cash") to Icons.Default.Payments
                    "UPI" -> "UPI" to Icons.Default.QrCode
                    else -> (if (isBn) "বাকী" else "Credit") to Icons.Default.AccountBalanceWallet
                }

                val activeColor = when (mode) {
                    "CASH" -> StoreGreenProfit
                    "UPI" -> StoreBlueUPI
                    else -> Color(0xFFEA580C)
                }

                Surface(
                    onClick = {
                        viewModel.selectedPaymentMode = mode
                        if (mode == "CREDIT") {
                            viewModel.customReceivedAmountInput = "0"
                            if (viewModel.selectedCustomer == null) {
                                showCustomerPicker = true
                            }
                        } else if (viewModel.customReceivedAmountInput == "0") {
                            viewModel.customReceivedAmountInput = null
                        }
                    },
                    shape = RoundedCornerShape(6.dp),
                    color = if (isSelected) activeColor else SurfaceWarm,
                    border = BorderStroke(1.dp, if (isSelected) activeColor else TextMuted.copy(alpha = 0.2f)),
                    modifier = Modifier
                        .weight(1f)
                        .height(32.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (isSelected) Color.White else TextDark,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = modeLabel,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) Color.White else TextDark,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Row 2: Enhanced Amount Received + Customer Quick Selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Amount Received Box with prominent edit & keypad trigger
            Surface(
                onClick = { showReceivedAmountDialog = true },
                shape = RoundedCornerShape(6.dp),
                color = CardBackground,
                border = BorderStroke(1.5.dp, if (viewModel.customReceivedAmountInput != null) StoreGreenProfit else StorePrimary.copy(alpha = 0.5f)),
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Payments,
                        contentDescription = null,
                        tint = StoreGreenProfit,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = if (viewModel.selectedPaymentMode == "CREDIT") "Paid: " else "Recv: ",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = StoreGreenProfit
                    )

                    // Inline text display / edit trigger
                    Text(
                        text = "₹%.2f".format(viewModel.effectiveReceivedAmount),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = TextDark,
                        modifier = Modifier.weight(1f)
                    )

                    // Quick Calculator & Reset Buttons
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (viewModel.customReceivedAmountInput != null) {
                            Surface(
                                shape = CircleShape,
                                color = TextMuted.copy(alpha = 0.15f),
                                modifier = Modifier
                                    .size(20.dp)
                                    .clickable { viewModel.customReceivedAmountInput = null }
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Reset",
                                    tint = TextDark,
                                    modifier = Modifier.padding(3.dp)
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = StorePrimary.copy(alpha = 0.12f),
                            modifier = Modifier
                                .height(22.dp)
                                .clickable { showReceivedAmountDialog = true }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit Amount", tint = StorePrimary, modifier = Modifier.size(11.dp))
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = if (isBn) "হিসাব" else "Calc",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = StorePrimary
                                )
                            }
                        }
                    }
                }
            }

            // Customer Selector Pill
            Surface(
                onClick = { showCustomerPicker = true },
                shape = RoundedCornerShape(6.dp),
                color = if (viewModel.effectiveDueAmount > 0 && viewModel.selectedCustomer == null)
                    StoreRedAlert.copy(alpha = 0.12f)
                else if (viewModel.isCustomerOverCreditLimit)
                    Color(0xFFDC2626).copy(alpha = 0.12f)
                else SurfaceWarm,
                border = BorderStroke(
                    1.dp,
                    if (viewModel.effectiveDueAmount > 0 && viewModel.selectedCustomer == null) StoreRedAlert
                    else if (viewModel.isCustomerOverCreditLimit) Color(0xFFDC2626)
                    else TextMuted.copy(alpha = 0.25f)
                ),
                modifier = Modifier.height(36.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        tint = if (viewModel.isCustomerOverCreditLimit) Color(0xFFDC2626)
                               else if (viewModel.selectedCustomer != null) StorePrimary
                               else StoreRedAlert,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    val custText = viewModel.selectedCustomer?.name?.take(10) ?: (if (isBn) "গ্রাহক" else "Customer")
                    Text(
                        text = custText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (viewModel.isCustomerOverCreditLimit) Color(0xFFDC2626) else TextDark,
                        maxLines = 1
                    )
                    if (viewModel.isCustomerOverCreditLimit) {
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = "⚠️",
                            fontSize = 10.sp
                        )
                    }
                }
            }

            // Add Customer Quick Button
            Surface(
                onClick = { showAddCustomerDialog = true },
                shape = RoundedCornerShape(6.dp),
                color = Color(0xFFEA580C).copy(alpha = 0.15f),
                modifier = Modifier.height(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 6.dp)) {
                    Icon(Icons.Default.PersonAdd, contentDescription = "Add Customer", tint = Color(0xFFEA580C), modifier = Modifier.size(14.dp))
                }
            }
        }

        // Quick Cash Suggestion Chips
        if (viewModel.selectedPaymentMode == "CASH" && viewModel.cartFinalTotal > 0) {
            Spacer(modifier = Modifier.height(4.dp))
            val currentTotal = viewModel.cartFinalTotal
            val nextHundred = (kotlin.math.ceil(currentTotal / 100.0) * 100.0).coerceAtLeast(currentTotal)
            val nextFiveHundred = (kotlin.math.ceil(currentTotal / 500.0) * 500.0).coerceAtLeast(currentTotal)
            val nextThousand = (kotlin.math.ceil(currentTotal / 1000.0) * 1000.0).coerceAtLeast(currentTotal)

            val suggestedChips = buildList {
                add(null to (if (isBn) "সঠিক ₹%.2f".format(currentTotal) else "Exact ₹%.2f".format(currentTotal)))
                if (nextHundred > currentTotal && nextHundred != nextFiveHundred) {
                    add(nextHundred to "₹%.0f".format(nextHundred))
                }
                if (nextFiveHundred > currentTotal && nextFiveHundred != nextThousand) {
                    add(nextFiveHundred to "₹%.0f".format(nextFiveHundred))
                }
                if (nextThousand > currentTotal) {
                    add(nextThousand to "₹%.0f".format(nextThousand))
                }
                listOf(500.0, 1000.0, 2000.0, 5000.0).forEach { note ->
                    if (note >= currentTotal && note != nextHundred && note != nextFiveHundred && note != nextThousand) {
                        add(note to "₹%.0f".format(note))
                    }
                }
            }.distinctBy { it.second }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                suggestedChips.forEach { (amt, label) ->
                    val isChipSelected = if (amt == null) viewModel.customReceivedAmountInput == null
                    else viewModel.customReceivedAmountInput?.toDoubleOrNull() == amt

                    Surface(
                        onClick = {
                            if (amt == null) {
                                viewModel.customReceivedAmountInput = null
                            } else {
                                viewModel.customReceivedAmountInput = "%.2f".format(amt)
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isChipSelected) StoreGreenProfit else SurfaceWarm,
                        border = BorderStroke(1.dp, if (isChipSelected) StoreGreenProfit else TextMuted.copy(alpha = 0.25f)),
                        modifier = Modifier.height(24.dp)
                    ) {
                        Text(
                            text = label,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isChipSelected) Color.White else TextDark,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        } else if (viewModel.selectedPaymentMode == "CREDIT" && viewModel.cartFinalTotal > 0) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(
                    0.0 to (if (isBn) "সম্পূর্ণ বাকী (₹০)" else "Full Credit (₹0)"),
                    100.0 to "₹100 Paid",
                    500.0 to "₹500 Paid",
                    1000.0 to "₹1000 Paid"
                ).forEach { (amt, label) ->
                    val isChipSelected = viewModel.customReceivedAmountInput?.toDoubleOrNull() == amt

                    Surface(
                        onClick = { viewModel.customReceivedAmountInput = "%.2f".format(amt) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isChipSelected) Color(0xFFEA580C) else SurfaceWarm,
                        border = BorderStroke(1.dp, if (isChipSelected) Color(0xFFEA580C) else TextMuted.copy(alpha = 0.25f)),
                        modifier = Modifier.height(24.dp)
                    ) {
                        Text(
                            text = label,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isChipSelected) Color.White else TextDark,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }

        // Credit Due Note if applicable
        if (viewModel.effectiveDueAmount > 0) {
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Due Credit: ₹%.2f".format(viewModel.effectiveDueAmount),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFEA580C)
                )
                if (viewModel.selectedCustomer != null) {
                    val cust = viewModel.selectedCustomer!!
                    if (viewModel.isCustomerOverCreditLimit) {
                        Text(
                            text = "⚠️ Total: ₹%.2f (Limit ₹%.0f Exceeded)".format(viewModel.customerNewTotalBalance, cust.creditLimit ?: 0.0),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFFDC2626)
                        )
                    } else if (cust.hasCreditLimit()) {
                        Text(
                            text = "Total: ₹%.2f (Avail: ₹%.2f)".format(viewModel.customerNewTotalBalance, viewModel.customerAvailableCredit),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = StoreGreenProfit
                        )
                    } else {
                        Text(
                            text = "Total Due: ₹%.2f".format(viewModel.customerNewTotalBalance),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = StoreRedAlert
                        )
                    }
                } else {
                    Text(
                        text = "⚠️ Select customer to save credit",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = StoreRedAlert
                    )
                }
            }

            // Payment Due Date Selector for Credit Sales
            Spacer(modifier = Modifier.height(3.dp))
            val dueDateDf = remember { SimpleDateFormat("dd MMM", Locale.getDefault()) }
            val formattedDueDate = remember(viewModel.effectiveCreditDueDate) {
                dueDateDf.format(Date(viewModel.effectiveCreditDueDate))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "📅 " + (if (isBn) "পরিশোধের মেয়াদ:" else "Due Date:"),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted
                )
                listOf(
                    3 to (if (isBn) "৩ দিন" else "3 Days"),
                    7 to (if (isBn) "৭ দিন (ডিফল্ট)" else "7 Days (Def)"),
                    15 to (if (isBn) "১৫ দিন" else "15 Days"),
                    30 to (if (isBn) "৩০ দিন" else "30 Days")
                ).forEach { (days, label) ->
                    val isSelected = viewModel.customCreditDueDate == null && viewModel.creditDueDays == days
                    Surface(
                        onClick = {
                            viewModel.customCreditDueDate = null
                            viewModel.creditDueDays = days
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) StorePrimary else SurfaceWarm,
                        border = BorderStroke(1.dp, if (isSelected) StorePrimary else TextMuted.copy(alpha = 0.25f)),
                        modifier = Modifier.height(22.dp)
                    ) {
                        Text(
                            text = label,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) Color.White else TextDark,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Surface(
                    color = StorePrimary.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(22.dp)
                ) {
                    Text(
                        text = "📅 $formattedDueDate",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = StorePrimary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        } else if (viewModel.effectiveExcessAmount > 0) {
            Spacer(modifier = Modifier.height(2.dp))
            if (viewModel.selectedCustomer != null) {
                Surface(
                    onClick = { viewModel.depositExcessToKhata = !viewModel.depositExcessToKhata },
                    shape = RoundedCornerShape(6.dp),
                    color = if (viewModel.depositExcessToKhata) StoreGreenProfit.copy(alpha = 0.12f) else SurfaceWarm,
                    border = BorderStroke(1.dp, if (viewModel.depositExcessToKhata) StoreGreenProfit else TextMuted.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                imageVector = if (viewModel.depositExcessToKhata) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (viewModel.depositExcessToKhata) StoreGreenProfit else TextMuted,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            val labelText = if (viewModel.depositExcessToKhata) {
                                if (viewModel.customerPreviousBalance > 0) {
                                    val dueCleared = minOf(viewModel.customerPreviousBalance, viewModel.effectiveExcessAmount)
                                    val advanceCredit = (viewModel.effectiveExcessAmount - viewModel.customerPreviousBalance).coerceAtLeast(0.0)
                                    if (advanceCredit > 0) "₹%.0f Due Cleared + ₹%.2f Advance to Khata".format(dueCleared, advanceCredit)
                                    else "₹%.2f Due Paid to Khata".format(dueCleared)
                                } else {
                                    "Deposit ₹%.2f Advance to Khata".format(viewModel.effectiveExcessAmount)
                                }
                            } else {
                                "Return Cash Change: ₹%.2f".format(viewModel.effectiveExcessAmount)
                            }
                            Text(
                                text = labelText,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (viewModel.depositExcessToKhata) StoreGreenProfit else TextDark,
                                maxLines = 1
                            )
                        }

                        val bal = viewModel.customerNewTotalBalance
                        Text(
                            text = if (viewModel.depositExcessToKhata) {
                                if (bal < 0) "New Adv: ₹%.2f".format(kotlin.math.abs(bal))
                                else "New Due: ₹%.2f".format(bal)
                            } else {
                                "Change: ₹%.2f".format(viewModel.effectiveExcessAmount)
                            },
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (bal < 0 && viewModel.depositExcessToKhata) StoreGreenProfit else TextDark
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Change to Return:",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = StoreGreenProfit
                    )
                    Text(
                        text = "₹%.2f".format(viewModel.effectiveExcessAmount),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = StoreGreenProfit
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Row 3: Compact Discount, Round Off, & Net Payable Bar
        Surface(
            color = SurfaceWarm,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, TextMuted.copy(alpha = 0.15f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Auto Offers Discount Chip
                        if (viewModel.totalOffersDiscount > 0) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = StoreGreenProfit.copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, StoreGreenProfit)
                            ) {
                                Text(
                                    text = "Offers: -₹%.2f".format(viewModel.totalOffersDiscount),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = StoreGreenProfit,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        }

                        // Manual Discount Chip
                        if (com.example.utils.StaffManager.canGiveDiscount()) {
                            Surface(
                                onClick = { showDiscountDialog = true },
                                shape = RoundedCornerShape(4.dp),
                                color = if (viewModel.cartDiscount > 0) StoreGreenProfit.copy(alpha = 0.15f) else CardBackground,
                                border = BorderStroke(1.dp, if (viewModel.cartDiscount > 0) StoreGreenProfit else TextMuted.copy(alpha = 0.25f))
                            ) {
                                Text(
                                    text = if (viewModel.cartDiscount > 0) "Disc: -₹%.2f".format(viewModel.cartDiscount) else "+ Discount",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (viewModel.cartDiscount > 0) StoreGreenProfit else TextDark,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        }

                        // Round Off Chip
                        Surface(
                            onClick = { viewModel.isRoundOff = !viewModel.isRoundOff },
                            shape = RoundedCornerShape(4.dp),
                            color = if (viewModel.isRoundOff) StoreSaffronAccent.copy(alpha = 0.15f) else CardBackground,
                            border = BorderStroke(1.dp, if (viewModel.isRoundOff) StoreSaffronAccent else TextMuted.copy(alpha = 0.25f))
                        ) {
                            Text(
                                text = if (viewModel.isRoundOff) "Round: ₹%+.2f".format(viewModel.roundOffAmount) else "Round Off",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (viewModel.isRoundOff) StoreSaffronAccent else TextMuted,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }

                    val totalMrpSavings = viewModel.totalMrpSavings
                    val totalCombinedSavings = totalMrpSavings + viewModel.cartDiscount + viewModel.totalOffersDiscount
                    if (totalCombinedSavings > 0.0) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = StoreGreenProfit.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "Save ₹%.2f".format(totalCombinedSavings),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = StoreGreenProfit,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Text(
                        text = "Subtotal: ₹%.2f".format(viewModel.cartTotalBeforeDiscount),
                        fontSize = 10.sp,
                        color = TextMuted
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isBn) "মোট প্রদেয়:" else "Net Payable:",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = TextDark
                    )
                    Text(
                        text = "₹%.2f".format(viewModel.cartFinalTotal),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (viewModel.selectedPaymentMode == "CREDIT") Color(0xFFEA580C) else StorePrimary
                    )
                }
            }
        }

        // Checkout & Cancel Action Buttons
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { viewModel.clearCart() },
                enabled = viewModel.cartItems.isNotEmpty() && !viewModel.isProcessingCheckout,
                modifier = Modifier.weight(0.35f),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = StoreRedAlert),
                border = BorderStroke(1.dp, if (viewModel.cartItems.isNotEmpty() && !viewModel.isProcessingCheckout) Color(0xFFFCA5A5) else TextMuted.copy(alpha = 0.3f))
            ) {
                Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = LanguageManager.getString("Cancel", "বাতিল"),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }

            val ctx = LocalContext.current
            Button(
                onClick = {
                    if (viewModel.isProcessingCheckout) return@Button
                    if (viewModel.effectiveDueAmount > 0 && viewModel.selectedCustomer == null) {
                        Toast.makeText(ctx, "Please select or add a customer to record ₹%.2f credit".format(viewModel.effectiveDueAmount), Toast.LENGTH_SHORT).show()
                        showCustomerPicker = true
                    } else if (viewModel.selectedPaymentMode == "CREDIT" && viewModel.selectedCustomer == null) {
                        showCustomerPicker = true
                    } else if (viewModel.isCustomerOverCreditLimit) {
                        showCreditLimitExceededDialog = true
                    } else {
                        viewModel.completeCheckout(isHold = false)
                    }
                },
                enabled = viewModel.cartItems.isNotEmpty() && !viewModel.isProcessingCheckout,
                modifier = Modifier.weight(0.65f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (viewModel.selectedPaymentMode == "CREDIT") Color(0xFFEA580C) else StorePrimary,
                    disabledContainerColor = (if (viewModel.selectedPaymentMode == "CREDIT") Color(0xFFEA580C) else StorePrimary).copy(alpha = 0.6f)
                )
            ) {
                if (viewModel.isProcessingCheckout) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = LanguageManager.getString("Processing...", "প্রসেসিং হচ্ছে..."),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = Color.White
                    )
                } else {
                    Icon(
                        imageVector = if (viewModel.selectedPaymentMode == "CREDIT") Icons.Default.AccountBalanceWallet else Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "₹%.2f %s".format(
                            viewModel.cartFinalTotal,
                            if (viewModel.selectedPaymentMode == "CREDIT") LanguageManager.getString("Credit Sale", "বাকীতে বিক্রি")
                            else LanguageManager.getString("Pay", "পরিশোধ")
                        ),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }

    // Credit Limit Exceeded Warning Dialog
    if (showCreditLimitExceededDialog && viewModel.selectedCustomer != null) {
        val cust = viewModel.selectedCustomer!!
        val limit = cust.creditLimit ?: 0.0
        val currBal = cust.balance
        val saleDue = viewModel.effectiveDueAmount
        val newBal = viewModel.customerNewTotalBalance
        val excess = viewModel.customerCreditLimitExceededAmount

        AlertDialog(
            onDismissRequest = { showCreditLimitExceededDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFDC2626))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = LanguageManager.getString("Credit Limit Exceeded", "বাকী সীমা অতিক্রম করেছে"),
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFDC2626)
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = LanguageManager.getString(
                            "This sale exceeds ${cust.name}'s credit limit!",
                            "এই বিক্রয়টি ${cust.name}-এর নির্ধারিত বাকী সীমা অতিক্রম করেছে!"
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Surface(
                        color = SurfaceWarm,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFFDC2626).copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(LanguageManager.getString("Credit Limit:", "বাকী সীমা:"), style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                Text("₹%.0f".format(limit), fontWeight = FontWeight.Bold)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(LanguageManager.getString("Current Due:", "বর্তমান বাকি:"), style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                Text("₹%.2f".format(currBal), fontWeight = FontWeight.Bold)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(LanguageManager.getString("This Sale Due:", "এই বিলের বাকি:"), style = MaterialTheme.typography.bodySmall, color = Color(0xFFEA580C))
                                Text("₹%.2f".format(saleDue), fontWeight = FontWeight.Bold, color = Color(0xFFEA580C))
                            }
                            HorizontalDivider(color = TextMuted.copy(alpha = 0.2f))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(LanguageManager.getString("New Projected Due:", "নতুন মোট বাকি:"), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                Text("₹%.2f".format(newBal), fontWeight = FontWeight.ExtraBold, color = Color(0xFFDC2626))
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(LanguageManager.getString("Over Limit By:", "সীমা অতিরিক্ত:"), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color(0xFFDC2626))
                                Text("₹%.2f".format(excess), fontWeight = FontWeight.ExtraBold, color = Color(0xFFDC2626))
                            }
                        }
                    }

                    Text(
                        text = LanguageManager.getString(
                            "Do you want to authorize this sale anyway or adjust the cash payment?",
                            "আপনি কি এই বিক্রয় অনুমোদন করতে চান নাকি নগদ পরিশোধ বৃদ্ধি করবেন?"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCreditLimitExceededDialog = false
                        viewModel.completeCheckout(isHold = false)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                ) {
                    Text(LanguageManager.getString("Authorize & Sell", "অনুমোদন ও বিক্রি করুন"), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showCreditLimitExceededDialog = false }) {
                    Text(LanguageManager.getString("Adjust Payment", "পেমেন্ট পরিবর্তন"))
                }
            }
        )
    }

    // Discount Dialog Modal
    if (showDiscountDialog) {
        val context = LocalContext.current
        var discountInput by remember { mutableStateOf(if (viewModel.cartDiscount > 0) "%.2f".format(viewModel.cartDiscount) else "") }
        var discountTypePercent by remember { mutableStateOf(false) }
        var bypassLimitOverride by remember { mutableStateOf(false) }

        val rawVal = discountInput.toDoubleOrNull() ?: 0.0
        val currentSubtotal = viewModel.cartTotalBeforeDiscount
        val calculatedDiscount = if (discountTypePercent) {
            (currentSubtotal * (rawVal / 100.0))
        } else {
            rawVal
        }

        val discountValidation = viewModel.validateDiscountAgainstCostPrice(calculatedDiscount)
        val canSeeCost = com.example.utils.StaffManager.canViewCostPrice()

        AlertDialog(
            onDismissRequest = { showDiscountDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocalOffer, contentDescription = null, tint = StoreGreenProfit)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = LanguageManager.getString("Apply Bill Discount", "বিলে ছাড় (Discount) যোগ করুন"),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Subtotal: ₹%.2f".format(currentSubtotal),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TextDark
                        )
                        if (canSeeCost) {
                            Text(
                                text = "Total Cost: ₹%.2f".format(viewModel.cartTotalCost),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                        }
                    }

                    // Cost Price Limit Protection Banner
                    if (canSeeCost) {
                        Surface(
                            color = if (discountValidation.isValid) StoreGreenProfit.copy(alpha = 0.08f) else StoreRedAlert.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, if (discountValidation.isValid) StoreGreenProfit.copy(alpha = 0.3f) else StoreRedAlert.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Max Safe Discount (to Cost):",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (discountValidation.isValid) StoreGreenProfit else StoreRedAlert
                                    )
                                    Text(
                                        text = "₹%.2f".format(discountValidation.maxSafeDiscount),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = if (discountValidation.isValid) StoreGreenProfit else StoreRedAlert
                                    )
                                }

                                if (!discountValidation.isValid) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "⚠️ Warning: Selling below cost price! Estimated loss: ₹%.2f".format(discountValidation.lossAmount),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = StoreRedAlert
                                    )
                                } else if (calculatedDiscount > 0) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Estimated Remaining Profit: ₹%.2f".format(discountValidation.estimatedProfitAfterDiscount),
                                        fontSize = 11.sp,
                                        color = StoreGreenProfit,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }

                    // Quick Preset Chips (Fixed Amount / Percentage)
                    Text(LanguageManager.getString("Quick Preset:", "দ্রুত নির্বাচন:"), style = MaterialTheme.typography.labelSmall)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(5.0, 10.0, 20.0, 50.0, 100.0).forEach { amt ->
                            val isSafe = amt <= discountValidation.maxSafeDiscount || !com.example.utils.StoreInfoManager.enforceCostPriceDiscountLimit
                            FilterChip(
                                selected = false,
                                onClick = {
                                    discountTypePercent = false
                                    discountInput = "%.0f".format(amt)
                                },
                                label = { Text("₹%.0f".format(amt), fontSize = 11.sp, color = if (isSafe) Color.Unspecified else StoreRedAlert) },
                                shape = RoundedCornerShape(6.dp)
                            )
                        }
                        listOf(5, 10, 15, 20).forEach { pct ->
                            FilterChip(
                                selected = false,
                                onClick = {
                                    discountTypePercent = true
                                    discountInput = pct.toString()
                                },
                                label = { Text("$pct%", fontSize = 11.sp) },
                                shape = RoundedCornerShape(6.dp)
                            )
                        }
                    }

                    // Input Field & Toggle Type
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = discountInput,
                            onValueChange = { discountInput = it },
                            label = {
                                Text(
                                    if (discountTypePercent) LanguageManager.getString("Discount (%)", "ছাড়ের শতাংশ (%)")
                                    else LanguageManager.getString("Discount Amount (₹)", "ছাড়ের পরিমাণ (₹)")
                                )
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            isError = !discountValidation.isValid && !bypassLimitOverride,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        )

                        Row {
                            FilterChip(
                                selected = !discountTypePercent,
                                onClick = { discountTypePercent = false },
                                label = { Text("₹", fontWeight = FontWeight.Bold) },
                                shape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)
                            )
                            FilterChip(
                                selected = discountTypePercent,
                                onClick = { discountTypePercent = true },
                                label = { Text("%", fontWeight = FontWeight.Bold) },
                                shape = RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)
                            )
                        }
                    }

                    if (!discountValidation.isValid && com.example.utils.StaffManager.isOwner()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Checkbox(
                                checked = bypassLimitOverride,
                                onCheckedChange = { bypassLimitOverride = it }
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Owner Override: Allow selling below cost",
                                fontSize = 11.5.sp,
                                color = StoreRedAlert,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (viewModel.cartDiscount > 0) {
                        TextButton(
                            onClick = {
                                viewModel.cartDiscount = 0.0
                                showDiscountDialog = false
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = StoreRedAlert)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(LanguageManager.getString("Remove Current Discount", "বর্তমান ছাড় তুলে নিন"))
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val applied = viewModel.applyCartDiscountSafe(calculatedDiscount, bypassCostLimit = bypassLimitOverride)
                        if (!applied && !bypassLimitOverride) {
                            Toast.makeText(
                                context,
                                "Discount capped to ₹%.2f to protect cost price".format(viewModel.cartDiscount),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        showDiscountDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StoreGreenProfit)
                ) {
                    Text(LanguageManager.getString("Apply", "প্রয়োগ করুন"), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscountDialog = false }) {
                    Text(LanguageManager.getString("Cancel", "বাতিল"))
                }
            }
        )
    }

    // Customer Picker Dialog
    if (showCustomerPicker) {
        CustomerPickerDialog(
            customers = customers,
            selectedCustomerId = viewModel.selectedCustomer?.id,
            onCustomerSelected = { cust ->
                viewModel.selectedCustomer = cust
                showCustomerPicker = false
            },
            onAddNewCustomerClick = { showAddCustomerDialog = true },
            onDismiss = { showCustomerPicker = false }
        )
    }

    // Add New Customer Modal
    if (showAddCustomerDialog) {
        AddCustomerDialog(
            onDismiss = { showAddCustomerDialog = false },
            onSave = { newCust ->
                viewModel.saveCustomer(newCust, initialDue = newCust.balance)
                viewModel.selectedCustomer = newCust
                showAddCustomerDialog = false
                showCustomerPicker = false
            }
        )
    }

    // Cash Received & Change Calculator Dialog Modal
    if (showReceivedAmountDialog) {
        ReceivedAmountCalculatorDialog(
            billTotal = viewModel.cartFinalTotal,
            initialAmount = viewModel.effectiveReceivedAmount,
            isBn = isBn,
            isCreditMode = viewModel.selectedPaymentMode == "CREDIT",
            onDismiss = { showReceivedAmountDialog = false },
            onConfirm = { customAmount ->
                if (customAmount == null || (viewModel.selectedPaymentMode != "CREDIT" && kotlin.math.abs(customAmount - viewModel.cartFinalTotal) < 0.001)) {
                    viewModel.customReceivedAmountInput = if (viewModel.selectedPaymentMode == "CREDIT") "0" else null
                } else {
                    viewModel.customReceivedAmountInput = "%.2f".format(customAmount)
                }
                showReceivedAmountDialog = false
            }
        )
    }
}

@Composable
fun ReceivedAmountCalculatorDialog(
    billTotal: Double,
    initialAmount: Double,
    isBn: Boolean,
    isCreditMode: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (Double?) -> Unit
) {
    var amountString by remember {
        mutableStateOf(
            if (initialAmount > 0) {
                if (initialAmount % 1.0 == 0.0) "%.0f".format(initialAmount) else "%.2f".format(initialAmount)
            } else ""
        )
    }

    val currentEnteredAmount = amountString.toDoubleOrNull() ?: 0.0
    val returnChange = (currentEnteredAmount - billTotal).coerceAtLeast(0.0)
    val dueAmount = (billTotal - currentEnteredAmount).coerceAtLeast(0.0)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = CardBackground,
            shadowElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isCreditMode) Icons.Default.AccountBalanceWallet else Icons.Default.Payments,
                            contentDescription = null,
                            tint = if (isCreditMode) Color(0xFFEA580C) else StoreGreenProfit,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isCreditMode) LanguageManager.getString("Down Payment / Cash Paid", "জমা নেওয়া টাকা (নগদ)")
                            else LanguageManager.getString("Cash Received Calculator", "নগদ টাকা ও ফেরত হিসাব"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextDark
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                    }
                }

                // Bill Amount vs Received Display Card
                Surface(
                    color = SurfaceWarm,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, TextMuted.copy(alpha = 0.2f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = LanguageManager.getString("Net Bill Amount:", "মোট বিল:"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMuted
                            )
                            Text(
                                text = "₹%.2f".format(billTotal),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = StorePrimary
                            )
                        }

                        HorizontalDivider(color = TextMuted.copy(alpha = 0.15f))

                        // Large Digital Received Input Box
                        Column {
                            Text(
                                text = if (isCreditMode) LanguageManager.getString("Cash Paid Now:", "নগদ জমা টাকা:")
                                else LanguageManager.getString("Cash Received from Customer:", "গ্রাহকের কাছ থেকে নেওয়া নগদ:"),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextMuted
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "₹" + if (amountString.isEmpty()) "0" else amountString,
                                    fontSize = 26.sp,
                                    fontWeight = FontWeight.Black,
                                    color = if (isCreditMode) Color(0xFFEA580C) else StoreGreenProfit
                                )
                                if (amountString.isNotEmpty()) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = TextMuted.copy(alpha = 0.15f),
                                        modifier = Modifier.clickable { amountString = "" }
                                    ) {
                                        Text(
                                            text = LanguageManager.getString("Clear", "মুছুন"),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextDark,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Change / Due Status Banner
                if (!isCreditMode) {
                    if (currentEnteredAmount >= billTotal && billTotal > 0) {
                        Surface(
                            color = StoreGreenProfit.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, StoreGreenProfit.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StoreGreenProfit, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = LanguageManager.getString("Return Change:", "ফেরত দিতে হবে:"),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = StoreGreenProfit
                                    )
                                }
                                Text(
                                    text = "₹%.2f".format(returnChange),
                                    fontWeight = FontWeight.Black,
                                    fontSize = 16.sp,
                                    color = StoreGreenProfit
                                )
                            }
                        }
                    } else if (currentEnteredAmount > 0 && currentEnteredAmount < billTotal) {
                        Surface(
                            color = Color(0xFFEA580C).copy(alpha = 0.12f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Color(0xFFEA580C).copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFEA580C), modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = LanguageManager.getString("Remaining Due (Credit):", "বাকী থাকবে:"),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = Color(0xFFEA580C)
                                    )
                                }
                                Text(
                                    text = "₹%.2f".format(dueAmount),
                                    fontWeight = FontWeight.Black,
                                    fontSize = 16.sp,
                                    color = Color(0xFFEA580C)
                                )
                            }
                        }
                    }
                } else {
                    Surface(
                        color = Color(0xFFEA580C).copy(alpha = 0.12f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color(0xFFEA580C).copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = LanguageManager.getString("Recorded as Khata Credit:", "খাতায় বাকী লেখা হবে:"),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = Color(0xFFEA580C)
                            )
                            Text(
                                text = "₹%.2f".format(dueAmount),
                                fontWeight = FontWeight.Black,
                                fontSize = 15.sp,
                                color = Color(0xFFEA580C)
                            )
                        }
                    }
                }

                // Quick Currency Notes
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        onClick = {
                            amountString = if (billTotal % 1.0 == 0.0) "%.0f".format(billTotal) else "%.2f".format(billTotal)
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = StorePrimary.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, StorePrimary.copy(alpha = 0.4f)),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text(
                            text = LanguageManager.getString("Exact (₹%.0f)".format(billTotal), "সঠিক (₹%.0f)".format(billTotal)),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = StorePrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                        )
                    }

                    listOf(50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0).forEach { noteVal ->
                        Surface(
                            onClick = { amountString = "%.0f".format(noteVal) },
                            shape = RoundedCornerShape(8.dp),
                            color = SurfaceWarm,
                            border = BorderStroke(1.dp, TextMuted.copy(alpha = 0.25f)),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text(
                                text = "₹%.0f".format(noteVal),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextDark,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                            )
                        }
                    }
                }

                // Tactile On-Screen Numeric Keypad
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val keyRows = listOf(
                        listOf("1", "2", "3"),
                        listOf("4", "5", "6"),
                        listOf("7", "8", "9"),
                        listOf(".", "0", "⌫")
                    )

                    keyRows.forEach { rowKeys ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            rowKeys.forEach { key ->
                                Surface(
                                    onClick = {
                                        when (key) {
                                            "⌫" -> {
                                                if (amountString.isNotEmpty()) {
                                                    amountString = amountString.dropLast(1)
                                                }
                                            }
                                            "." -> {
                                                if (!amountString.contains(".")) {
                                                    amountString = if (amountString.isEmpty()) "0." else "$amountString."
                                                }
                                            }
                                            else -> {
                                                if (amountString == "0") {
                                                    amountString = key
                                                } else {
                                                    val dotIdx = amountString.indexOf('.')
                                                    if (dotIdx == -1 || amountString.length - dotIdx <= 2) {
                                                        amountString += key
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (key == "⌫") SurfaceWarm else CardBackground,
                                    border = BorderStroke(1.dp, TextMuted.copy(alpha = 0.2f)),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        if (key == "⌫") {
                                            Icon(Icons.Default.Backspace, contentDescription = "Backspace", tint = StoreRedAlert, modifier = Modifier.size(20.dp))
                                        } else {
                                            Text(
                                                text = key,
                                                fontSize = 18.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = TextDark
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Incremental Add Buttons Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(10.0, 50.0, 100.0, 500.0).forEach { inc ->
                            Surface(
                                onClick = {
                                    val current = amountString.toDoubleOrNull() ?: 0.0
                                    val newVal = current + inc
                                    amountString = if (newVal % 1.0 == 0.0) "%.0f".format(newVal) else "%.2f".format(newVal)
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = SurfaceWarm,
                                border = BorderStroke(1.dp, TextMuted.copy(alpha = 0.2f)),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(34.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "+₹%.0f".format(inc),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = StoreGreenProfit
                                    )
                                }
                            }
                        }
                    }
                }

                // Action Buttons (Cancel / Apply)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(0.35f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(LanguageManager.getString("Cancel", "বাতিল"), fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            val parsed = amountString.toDoubleOrNull()
                            onConfirm(parsed)
                        },
                        modifier = Modifier.weight(0.65f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isCreditMode) Color(0xFFEA580C) else StoreGreenProfit
                        )
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        val btnLabel = if (currentEnteredAmount >= billTotal && !isCreditMode && billTotal > 0) {
                            "Apply & Return ₹%.2f".format(returnChange)
                        } else {
                            "Set ₹%.2f".format(currentEnteredAmount)
                        }
                        Text(btnLabel, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun AddCustomerDialog(
    initialName: String = "",
    initialPhone: String = "",
    onDismiss: () -> Unit,
    onSave: (Customer) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var phone by remember { mutableStateOf(initialPhone) }
    var initialBalanceText by remember { mutableStateOf("") }
    var creditLimitText by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PersonAdd, contentDescription = null, tint = StorePrimary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = LanguageManager.getString("Add New Customer", "নতুন গ্রাহক যোগ করুন"),
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        if (it.isNotBlank()) isError = false
                    },
                    label = { Text(LanguageManager.getString("Customer Name *", "গ্রাহকের নাম *")) },
                    isError = isError && name.isBlank(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text(LanguageManager.getString("Mobile Number", "মোবাইল নম্বর")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = initialBalanceText,
                    onValueChange = { initialBalanceText = it },
                    label = { Text(LanguageManager.getString("Previous Balance / Due (₹)", "পূর্বের বাকী / দেনা (₹)")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = creditLimitText,
                    onValueChange = { creditLimitText = it },
                    label = { Text(LanguageManager.getString("Credit Limit (₹) - Optional", "সর্বোচ্চ বাকী সীমা (₹) - ঐচ্ছিক")) },
                    placeholder = { Text(LanguageManager.getString("0 = Unlimited", "খালি/০ = সীমাহীন বাকী")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Quick Credit Limit Chips
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(
                        1000.0 to "₹1,000",
                        2000.0 to "₹2,000",
                        5000.0 to "₹5,000",
                        10000.0 to "₹10,000",
                        null to (if (LanguageManager.isBengali) "সীমাহীন" else "No Limit")
                    ).forEach { (amt, label) ->
                        Surface(
                            onClick = { creditLimitText = if (amt != null) "%.0f".format(amt) else "" },
                            shape = RoundedCornerShape(12.dp),
                            color = SurfaceWarm,
                            border = BorderStroke(1.dp, TextMuted.copy(alpha = 0.3f)),
                            modifier = Modifier.height(24.dp)
                        ) {
                            Text(
                                text = label,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextDark,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isBlank()) {
                        isError = true
                        return@Button
                    }
                    val balance = initialBalanceText.toDoubleOrNull() ?: 0.0
                    val creditLimit = creditLimitText.toDoubleOrNull()
                    val newCust = Customer(
                        id = "cust_" + UUID.randomUUID().toString().take(8),
                        name = name.trim(),
                        phone = phone.trim(),
                        balance = balance,
                        creditLimit = if (creditLimit != null && creditLimit > 0) creditLimit else null
                    )
                    onSave(newCust)
                },
                colors = ButtonDefaults.buttonColors(containerColor = StorePrimary)
            ) {
                Text(LanguageManager.getString("Save & Select", "সংরক্ষণ ও নির্বাচন"))
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
fun CustomerPickerDialog(
    customers: List<Customer>,
    selectedCustomerId: String? = null,
    initialSearchQuery: String = "",
    onCustomerSelected: (Customer) -> Unit,
    onAddNewCustomerClick: () -> Unit,
    onDismiss: () -> Unit
) {
    var customerSearchQuery by remember { mutableStateOf(initialSearchQuery) }
    var isSearchActive by remember { mutableStateOf(initialSearchQuery.isNotBlank()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = LanguageManager.getString("Select Customer for Credit Sale", "বাকী বিক্রয়ের জন্য গ্রাহক সিলেক্ট করুন"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = {
                        isSearchActive = !isSearchActive
                        if (!isSearchActive) customerSearchQuery = ""
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search Customer",
                        tint = if (isSearchActive) StoreBlueUPI else StorePrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(
                    onClick = onAddNewCustomerClick,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PersonAdd,
                        contentDescription = "Add Customer",
                        tint = StorePrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        },
        text = {
            Column {
                if (isSearchActive) {
                    OutlinedTextField(
                        value = customerSearchQuery,
                        onValueChange = { customerSearchQuery = it },
                        placeholder = {
                            Text(
                                LanguageManager.getString("Search by name or phone...", "নাম বা ফোন দিয়ে খুঁজুন..."),
                                fontSize = 12.sp,
                                color = TextMuted
                            )
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null, tint = StorePrimary, modifier = Modifier.size(18.dp))
                        },
                        trailingIcon = {
                            if (customerSearchQuery.isNotBlank()) {
                                IconButton(
                                    onClick = { customerSearchQuery = "" },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    )
                }

                val filteredCustomers = remember(customers, customerSearchQuery) {
                    if (customerSearchQuery.isBlank()) {
                        customers
                    } else {
                        val q = customerSearchQuery.trim().lowercase()
                        customers.filter {
                            it.name.lowercase().contains(q) || it.phone.contains(q)
                        }
                    }
                }

                if (customers.isEmpty()) {
                    Text(
                        text = LanguageManager.getString("No customers added yet. Click + above to add a new customer.", "কোন গ্রাহক যুক্ত করা নেই। নতুন গ্রাহক যোগ করতে উপরে + আইকন চাপুন।"),
                        color = TextMuted,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else if (filteredCustomers.isEmpty()) {
                    Text(
                        text = LanguageManager.getString("No matching customer found.", "কোন গ্রাহক পাওয়া যায়নি।"),
                        color = TextMuted,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 280.dp)
                    ) {
                        items(filteredCustomers, key = { it.id }, contentType = { "POS_CUSTOMER_CARD" }) { cust ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onCustomerSelected(cust)
                                    }
                                    .padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (selectedCustomerId == cust.id) StorePrimary.copy(alpha = 0.12f) else SurfaceWarm
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(cust.name, fontWeight = FontWeight.Bold)
                                        if (cust.phone.isNotBlank()) {
                                            Text(cust.phone, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                        }
                                        if (cust.hasCreditLimit()) {
                                            Text(
                                                text = "Limit: ₹%.0f".format(cust.creditLimit ?: 0.0),
                                                fontSize = 11.sp,
                                                color = TextMuted
                                            )
                                        }
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            "Due: ₹%.2f".format(cust.balance),
                                            color = if (cust.balance > 0) StoreRedAlert else StoreGreenProfit,
                                            fontWeight = FontWeight.Bold
                                        )
                                        if (cust.hasCreditLimit()) {
                                            if (cust.isOverCreditLimit()) {
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = Color(0xFFDC2626).copy(alpha = 0.15f)
                                                ) {
                                                    Text(
                                                        text = "⚠️ Over Limit",
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFFDC2626),
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
                                            } else {
                                                Text(
                                                    text = "Avail: ₹%.2f".format(cust.getAvailableCredit()),
                                                    fontSize = 10.sp,
                                                    color = StoreGreenProfit,
                                                    fontWeight = FontWeight.Medium
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
}

@Composable
fun CartItemRow(
    item: CartItem,
    itemIndex: Int = 0,
    isBn: Boolean,
    isWholesaleBillingMode: Boolean = false,
    appliedOffer: AppliedOfferDiscount? = null,
    bogoPrompt: String? = null,
    onAddFreeItem: (() -> Unit)? = null,
    onIncrease: () -> Unit,
    onDecrease: () -> Unit,
    onRemove: () -> Unit,
    onEditUnitQty: () -> Unit,
    onToggleWholesale: (() -> Unit)? = null,
    onPhotoClick: (() -> Unit)? = null
) {
    val isWholesaleActive = item.isUsingWholesale(isWholesaleBillingMode)
    val effectiveUnitPrice = item.getEffectiveUnitPrice(isWholesaleBillingMode)
    val lineSubtotal = item.getSubtotal(isWholesaleBillingMode)
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp),
        border = BorderStroke(
            1.dp,
            if (item.isFreeGift) Color(0xFF0D9488)
            else if (appliedOffer != null) StoreGreenProfit.copy(alpha = 0.5f)
            else if (bogoPrompt != null) Color(0xFFEA580C).copy(alpha = 0.4f)
            else if (isWholesaleActive) StoreGreenProfit.copy(alpha = 0.4f)
            else StorePrimary.copy(alpha = 0.15f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(10.dp)
                .fillMaxWidth()
        ) {
            // TOP ROW: Index + Thumbnail + Name & Rate (Left) and Subtotal (Right)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left side: Index badge, thumbnail & Product Info
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    if (item.isFreeGift) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFF0D9488).copy(alpha = 0.18f),
                            modifier = Modifier.size(24.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.CardGiftcard,
                                    contentDescription = null,
                                    tint = Color(0xFF0D9488),
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    } else if (itemIndex > 0) {
                        Surface(
                            shape = CircleShape,
                            color = if (appliedOffer != null) StoreGreenProfit.copy(alpha = 0.15f)
                            else if (bogoPrompt != null) Color(0xFFEA580C).copy(alpha = 0.15f)
                            else StorePrimary.copy(alpha = 0.12f),
                            modifier = Modifier.size(24.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "#$itemIndex",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (appliedOffer != null) StoreGreenProfit
                                    else if (bogoPrompt != null) Color(0xFFEA580C)
                                    else StorePrimary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    if (!item.product.imageUri.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(SurfaceWarm)
                                .clickable { onPhotoClick?.invoke() },
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = com.example.utils.ImageSyncHelper.getImageModel(item.product.imageUri),
                                contentDescription = item.product.nameEn,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    // Product Details
                    Column(modifier = Modifier.weight(1f)) {
                        val displayName = item.getDisplayName(isBn).ifBlank {
                            item.product.nameEn.ifBlank { item.product.nameBn.ifBlank { "Item" } }
                        }
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextDark,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        val secRatio = item.product.getEffectiveSecondaryRatio()
                        val isGram = Product.isGramUnit(item.unitType)
                        val isMl = Product.isMlUnit(item.unitType)
                        val unitPriceStr = if (item.isVariant) {
                            val uLabel = BengaliReceiptTranslator.translateUnit(item.unitType, isBn)
                            if (isBn) "দর: ₹%.2f/%s (প্যাক)".format(effectiveUnitPrice, uLabel) else "Rate: ₹%.2f/%s (Pack)".format(effectiveUnitPrice, uLabel)
                        } else if (isGram || isMl) {
                            val effSecRatio = if (secRatio >= 1000.0) secRatio else 1000.0
                            val secPrice100g = (100.0 / effSecRatio) * effectiveUnitPrice
                            val subUnitLabel = if (isGram) (if (isBn) "গ্রাম" else "g") else (if (isBn) "মিলি" else "ml")
                            val primaryUnitLabel = BengaliReceiptTranslator.translateUnit(item.product.unitType, isBn)
                            if (isBn) {
                                "দর: ₹%.2f/%s (₹%.2f/১০০ %s)".format(effectiveUnitPrice, primaryUnitLabel, secPrice100g, subUnitLabel)
                            } else {
                                "Rate: ₹%.2f/%s (₹%.2f/100%s)".format(effectiveUnitPrice, primaryUnitLabel, secPrice100g, subUnitLabel)
                            }
                        } else {
                            val uLabel = BengaliReceiptTranslator.translateUnit(item.unitType, isBn)
                            if (isBn) "দর: ₹%.2f/%s".format(effectiveUnitPrice, uLabel) else "Rate: ₹%.2f/%s".format(effectiveUnitPrice, uLabel)
                        }

                        val effectiveMrp = item.getEffectiveMrp()
                        val hasMrpDiscount = effectiveMrp > effectiveUnitPrice
                        val savingsPerUnit = item.getItemMrpSavings(isWholesaleActive)

                        if (item.isFreeGift) {
                            Text(
                                text = if (isBn) "মূল্য: ₹০.০০ (ফ্রি উপহার)" else "Rate: FREE (Gift)",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF0D9488),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        } else if (hasMrpDiscount) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = unitPriceStr,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isWholesaleActive) StoreGreenProfit else TextDark,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "MRP ₹%.2f".format(effectiveMrp),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough
                                    ),
                                    color = TextMuted,
                                    fontSize = 10.sp
                                )
                                if (savingsPerUnit > 0) {
                                    Surface(
                                        color = StoreGreenProfit.copy(alpha = 0.12f),
                                        shape = RoundedCornerShape(3.dp)
                                    ) {
                                        Text(
                                            text = "Save ₹%.2f".format(savingsPerUnit),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = StoreGreenProfit,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 3.dp, vertical = 0.5.dp)
                                        )
                                    }
                                }
                            }
                        } else {
                            Text(
                                text = unitPriceStr,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isWholesaleActive) StoreGreenProfit else TextMuted,
                                fontSize = 11.sp,
                                fontWeight = if (isWholesaleActive) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Price Section on Top-Right
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (item.isFreeGift) {
                        Text(
                            text = "FREE",
                            fontWeight = FontWeight.ExtraBold,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color(0xFF0D9488)
                        )
                        Text(
                            text = "₹%.2f".format(item.product.calculatePrice(item.quantity, item.unitType)),
                            fontSize = 10.sp,
                            color = TextMuted,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough
                        )
                    } else if (appliedOffer != null) {
                        val netSubtotal = (lineSubtotal - appliedOffer.discountAmount).coerceAtLeast(0.0)
                        Text(
                            text = "₹%.2f".format(netSubtotal),
                            fontWeight = FontWeight.ExtraBold,
                            style = MaterialTheme.typography.bodyLarge,
                            color = StoreGreenProfit
                        )
                        Text(
                            text = "₹%.2f".format(lineSubtotal),
                            fontSize = 10.sp,
                            color = TextMuted,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough
                        )
                    } else {
                        Text(
                            text = "₹%.2f".format(lineSubtotal),
                            fontWeight = FontWeight.ExtraBold,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isWholesaleActive) StoreGreenProfit else StorePrimary
                        )
                    }
                }
            }

            // MIDDLE SECTION 1: APPLIED OFFER BANNER (Full width, single line, elegant)
            if (appliedOffer != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = StoreGreenProfit.copy(alpha = 0.10f),
                    border = BorderStroke(1.dp, StoreGreenProfit.copy(alpha = 0.3f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            Toast.makeText(context, appliedOffer.description, Toast.LENGTH_SHORT).show()
                        }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.LocalOffer,
                                contentDescription = null,
                                tint = StoreGreenProfit,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = appliedOffer.getFormattedBadge(isBn),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = StoreGreenProfit,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = "-₹%.2f".format(appliedOffer.discountAmount),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = StoreGreenProfit
                        )
                    }
                }
            }

            // MIDDLE SECTION 2: BOGO OPPORTUNITY ACTION BANNER (Full width amber banner with Claim Free button)
            if (bogoPrompt != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFFFFF7ED),
                    border = BorderStroke(1.dp, Color(0xFFFDBA74)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.CardGiftcard,
                                contentDescription = null,
                                tint = Color(0xFFEA580C),
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = bogoPrompt,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFC2410C),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (onAddFreeItem != null) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                onClick = { onAddFreeItem() },
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFFEA580C)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(11.dp)
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text(
                                        text = if (isBn) "ফ্রি নিন" else "Claim Free",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // BOTTOM ROW: Wholesale toggle (Left) and Quantity Controls + Delete (Right)
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left side: Wholesale pill if available
                if (onToggleWholesale != null && item.product.wholesalePrice != null && item.product.wholesalePrice > 0.0) {
                    Surface(
                        onClick = onToggleWholesale,
                        shape = RoundedCornerShape(4.dp),
                        color = if (isWholesaleActive) StoreGreenProfit.copy(alpha = 0.15f) else SurfaceWarm,
                        border = BorderStroke(1.dp, if (isWholesaleActive) StoreGreenProfit.copy(alpha = 0.6f) else TextMuted.copy(alpha = 0.3f))
                    ) {
                        Text(
                            text = if (isWholesaleActive) (if (isBn) "পাইকারি সক্রিয়" else "Wholesale ON") else (if (isBn) "পাইকারি" else "Wholesale"),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isWholesaleActive) StoreGreenProfit else TextMuted,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }

                // Right side: Quantity [-] [qty] [+] and Remove button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    // Decrease button
                    Surface(
                        onClick = onDecrease,
                        shape = CircleShape,
                        color = StoreRedAlert.copy(alpha = 0.12f),
                        modifier = Modifier.size(28.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Remove, contentDescription = "Decrease", tint = StoreRedAlert, modifier = Modifier.size(15.dp))
                        }
                    }

                    // Quantity display button
                    Surface(
                        modifier = Modifier
                            .clickable { onEditUnitQty() }
                            .padding(horizontal = 2.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = SurfaceWarm,
                        border = BorderStroke(1.dp, StorePrimary.copy(alpha = 0.3f))
                    ) {
                        val formattedQty = if (item.quantity % 1.0 == 0.0) {
                            "${item.quantity.toInt()}"
                        } else {
                            "%.3f".format(item.quantity).trimEnd('0').trimEnd('.')
                        }
                        val uLabel = BengaliReceiptTranslator.translateUnit(item.unitType, isBn)
                        val qtyLabel = "$formattedQty $uLabel"
                        Text(
                            text = qtyLabel,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            fontWeight = FontWeight.ExtraBold,
                            color = TextDark,
                            fontSize = 11.5.sp
                        )
                    }

                    // Increase button
                    Surface(
                        onClick = onIncrease,
                        shape = CircleShape,
                        color = StoreGreenProfit,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Add, contentDescription = "Increase", tint = Color.White, modifier = Modifier.size(15.dp))
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Delete button
                    IconButton(
                        onClick = onRemove,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Delete",
                            tint = StoreRedAlert,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FreeGiftPromoSection(
    viewModel: StoreViewModel,
    isBn: Boolean
) {
    val eligibleOffers = remember(viewModel.cartItems.map { it.product.id to it.quantity }, viewModel.activeOffers.value, viewModel.isWholesaleBillingMode) {
        viewModel.getEligibleFreeGiftOffers()
    }
    val upcomingOffer = remember(viewModel.cartItems.map { it.product.id to it.quantity }, viewModel.activeOffers.value, viewModel.isWholesaleBillingMode) {
        viewModel.getUpcomingFreeGiftOffer()
    }
    val allProducts = viewModel.allProducts.value
    val todayStr = remember { com.example.data.local.entities.Offer.getTodayDateString() }

    // Check for gifts currently in cart that no longer qualify due to items removed/reduced
    val disqualifiedGiftItems = remember(viewModel.cartItems.map { it.product.id to it.quantity }, viewModel.activeOffers.value, viewModel.isWholesaleBillingMode) {
        viewModel.cartItems.filter { it.isFreeGift && it.freeGiftOfferId != null }.mapNotNull { cartItem ->
            val offer = viewModel.activeOffers.value.firstOrNull { it.id == cartItem.freeGiftOfferId }
            if (offer == null || !offer.isCurrentlyApplying(todayStr) || viewModel.getCartQualifyingSpendForOffer(offer) < offer.minSpendAmount) {
                cartItem to (offer?.name ?: cartItem.freeGiftOfferName ?: "Gift")
            } else null
        }
    }

    if (eligibleOffers.isEmpty() && upcomingOffer == null && disqualifiedGiftItems.isEmpty()) {
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 1. Disqualified Free Gift Warning (if qualifying cart items were removed)
        disqualifiedGiftItems.forEach { (giftItem, offerName) ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = StoreRedAlert.copy(alpha = 0.08f),
                border = BorderStroke(1.dp, StoreRedAlert.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = StoreRedAlert,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        val displayOfferName = if (com.example.utils.BengaliReceiptTranslator.isGenericGiftOfferName(offerName)) {
                            if (isBn) "ফ্রি উপহার" else "Free Gift"
                        } else offerName
                        Text(
                            text = if (isBn) "ন্যূনতম কেনাকাটার শর্ত পূরণ না হওয়ায় '$displayOfferName' আর প্রযোজ্য নয়"
                            else "Minimum spend no longer met for '$displayOfferName'",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = StoreRedAlert
                        )
                    }
                    TextButton(
                        onClick = {
                            giftItem.freeGiftOfferId?.let { viewModel.removeFreeGiftFromCart(it) }
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = if (isBn) "মুছুন" else "Remove",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = StoreRedAlert
                        )
                    }
                }
            }
        }

        // 2. Eligible Free Gifts (Unlocked!)
        eligibleOffers.forEach { (offer, giftProd) ->
            val isClaimed = viewModel.cartItems.any { it.isFreeGift && it.freeGiftOfferId == offer.id }
            val giftName = giftProd.getDisplayName(isBn)
            val freeQtyStr = if (offer.freeProductQty % 1.0 == 0.0) "${offer.freeProductQty.toInt()}" else "%.1f".format(offer.freeProductQty)
            val freeUnitStr = offer.freeProductUnit?.trim()?.takeIf { it.isNotBlank() } ?: giftProd.unitType
            val freeQtyWithUnit = "$freeQtyStr $freeUnitStr"
            val minSpendStr = if (offer.minSpendAmount % 1.0 == 0.0) "₹${offer.minSpendAmount.toInt()}" else "₹%.2f".format(offer.minSpendAmount)

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (isClaimed) Color(0xFF0D9488).copy(alpha = 0.08f) else Color(0xFFF0FDF4),
                border = BorderStroke(1.5.dp, if (isClaimed) Color(0xFF0D9488).copy(alpha = 0.4f) else Color(0xFF16A34A)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (isClaimed) Color(0xFF0D9488).copy(alpha = 0.18f) else Color(0xFF16A34A).copy(alpha = 0.18f),
                            modifier = Modifier.size(32.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.CardGiftcard,
                                    contentDescription = null,
                                    tint = if (isClaimed) Color(0xFF0D9488) else Color(0xFF16A34A),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = if (isClaimed) (if (isBn) "🎉 ফ্রি উপহার যুক্ত হয়েছে!" else "🎉 Free Gift In Cart!")
                                else (if (isBn) "🎉 ফ্রি উপহার আনলক হয়েছে!" else "🎉 Free Gift Unlocked!"),
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 12.sp,
                                color = if (isClaimed) Color(0xFF0D9488) else Color(0xFF15803D)
                            )
                            Text(
                                text = if (isBn) "$minSpendStr+ কেনাকাটায়: $freeQtyWithUnit $giftName বিনামূল্যে"
                                else "Spend $minSpendStr+: Get $freeQtyWithUnit x $giftName FREE",
                                fontSize = 11.sp,
                                color = TextDark,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    if (isClaimed) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFF0D9488).copy(alpha = 0.15f)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color(0xFF0D9488),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = if (isBn) "যুক্ত আছে" else "Added",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0D9488)
                                )
                            }
                        }
                    } else {
                        Button(
                            onClick = {
                                viewModel.addFreeGiftToCart(offer, giftProd)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CardGiftcard,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isBn) "উপহার নিন" else "Claim Gift",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // 3. Upcoming Free Gift (Encourages customers to reach minimum spend)
        if (eligibleOffers.isEmpty() && upcomingOffer != null) {
            val (offer, giftProd, needed) = upcomingOffer
            val giftName = giftProd.getDisplayName(isBn)
            val freeQtyStr = if (offer.freeProductQty % 1.0 == 0.0) "${offer.freeProductQty.toInt()}" else "%.1f".format(offer.freeProductQty)
            val freeUnitStr = offer.freeProductUnit?.trim()?.takeIf { it.isNotBlank() } ?: giftProd.unitType
            val freeQtyWithUnit = "$freeQtyStr $freeUnitStr"
            val qualifyingSpend = viewModel.getCartQualifyingSpendForOffer(offer)
            val progress = (qualifyingSpend / offer.minSpendAmount).toFloat().coerceIn(0f, 1f)

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFFF0FDF4).copy(alpha = 0.7f),
                border = BorderStroke(1.dp, Color(0xFF0D9488).copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CardGiftcard,
                                contentDescription = null,
                                tint = Color(0xFF0D9488),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isBn) "আর মাত্র ₹%.0f কেনাকাটা করলেই পাবেন ফ্রি $giftName!".format(needed)
                                else "Add ₹%.0f more to get $freeQtyWithUnit x $giftName for FREE!".format(needed),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0F766E)
                            )
                        }
                        Text(
                            text = "₹%.0f / ₹%.0f".format(qualifyingSpend, offer.minSpendAmount),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextMuted
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = Color(0xFF0D9488),
                        trackColor = Color(0xFF0D9488).copy(alpha = 0.15f)
                    )
                }
            }
        }
    }
}

@Composable
fun UnitAndQtySelectorModal(
    product: Product,
    initialUnit: String? = null,
    initialQty: Double? = null,
    isBn: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (selectedUnit: String, quantity: Double) -> Unit
) {
    val hasBulk = product.hasBulkPricing()
    val hasBox = product.hasBoxPricing()
    val bulkUnit = product.getEffectiveBulkUnit()
    val bulkQty = product.getEffectiveBulkQuantity()
    val bulkPrice = product.getEffectiveBulkPrice()
    val primaryUnit = product.unitType
    val secondaryUnit = product.getEffectiveSecondaryUnit()
    val secRatio = product.getEffectiveSecondaryRatio()
    val pieceUnit = if (primaryUnit.equals("box", ignoreCase = true)) "piece" else primaryUnit

    var selectedUnit by remember {
        mutableStateOf(initialUnit ?: if (hasBulk) pieceUnit else primaryUnit)
    }

    var isSubmitting by remember { mutableStateOf(false) }

    // Tab state: 0 = Quick Presets, 1 = Exact Weight / Grams, 2 = Buy by Amount (₹)
    var selectedTab by remember { mutableIntStateOf(0) }

    val defaultInitialQty = when {
        initialQty != null -> initialQty
        selectedUnit.equals("gram", ignoreCase = true) || selectedUnit.equals("ml", ignoreCase = true) -> 250.0
        else -> 1.0
    }

    var qtyText by remember { mutableStateOf(if (defaultInitialQty % 1.0 == 0.0) defaultInitialQty.toInt().toString() else defaultInitialQty.toString()) }
    var priceText by remember {
        val initPrice = product.calculatePrice(defaultInitialQty, selectedUnit)
        mutableStateOf(if (initPrice % 1.0 == 0.0) initPrice.toInt().toString() else "%.2f".format(initPrice))
    }

    fun updateFromQty(newQtyStr: String, currentUnit: String = selectedUnit) {
        qtyText = newQtyStr
        val rawQ = newQtyStr.toDoubleOrNull() ?: 0.0
        val isDiscrete = product.isDiscreteUnit(currentUnit)
        val q = if (isDiscrete) {
            if (rawQ > 0.0 && rawQ % 1.0 != 0.0) rawQ.toInt().toDouble() else rawQ
        } else {
            rawQ
        }
        val p = product.calculatePrice(q, currentUnit)
        priceText = if (p % 1.0 == 0.0) p.toInt().toString() else "%.2f".format(p)
    }

    fun updateFromPrice(newPriceStr: String, currentUnit: String = selectedUnit) {
        priceText = newPriceStr
        val p = newPriceStr.toDoubleOrNull() ?: 0.0
        val isDiscrete = product.isDiscreteUnit(currentUnit)

        if (currentUnit.equals(bulkUnit, ignoreCase = true) || currentUnit.equals("box", ignoreCase = true) || currentUnit.equals("case", ignoreCase = true)) {
            val bp = bulkPrice
            if (bp > 0) {
                val wholeBoxes = (p / bp).toInt()
                val calculatedBoxes = if (p > 0.0 && wholeBoxes == 0) 1 else wholeBoxes
                qtyText = calculatedBoxes.toString()
            }
        } else if (isDiscrete) {
            val unitPrice = product.sellingPrice
            if (unitPrice > 0) {
                val wholePieces = (p / unitPrice).toInt()
                val calculatedPieces = if (p > 0.0 && wholePieces == 0) 1 else wholePieces
                qtyText = calculatedPieces.toString()
            }
        } else if (product.sellingPrice > 0) {
            val isSubMetric = (Product.isGramUnit(currentUnit) && Product.isKgUnit(product.unitType)) ||
                    (Product.isMlUnit(currentUnit) && Product.isLitreUnit(product.unitType)) ||
                    (currentUnit.equals(secondaryUnit, ignoreCase = true) && secRatio > 1.0)
            val calculatedQty = if (isSubMetric) {
                val ratio = if (Product.isGramUnit(currentUnit) || Product.isMlUnit(currentUnit)) {
                    if (secRatio >= 1000.0) secRatio else 1000.0
                } else {
                    secRatio
                }
                (p / product.sellingPrice) * ratio
            } else {
                p / product.sellingPrice
            }
            qtyText = if (calculatedQty % 1.0 == 0.0) calculatedQty.toInt().toString() else "%.3f".format(calculatedQty)
        }
    }

    val rawEnteredQty = qtyText.toDoubleOrNull() ?: 0.0
    val finalEnteredQty = if (product.isDiscreteUnit(selectedUnit)) {
        if (rawEnteredQty % 1.0 != 0.0) rawEnteredQty.toInt().toDouble() else rawEnteredQty
    } else {
        rawEnteredQty
    }
    val finalCalculatedPrice = product.calculatePrice(finalEnteredQty, selectedUnit)

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = product.getDisplayName(isBn),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = TextDark,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )

                            // Badge
                            val badgeLabel = when {
                                hasBulk -> {
                                    val bName = bulkUnit.uppercase()
                                    val pName = (if (primaryUnit.equals("box", ignoreCase = true)) "PIECE" else primaryUnit).uppercase()
                                    if (isBn) "$bName / $pName সেল" else "$pName / $bName SALE"
                                }
                                Product.isLitreUnit(primaryUnit) -> "LOOSE SALE (LITER)"
                                Product.isKgUnit(primaryUnit) || Product.isGramUnit(primaryUnit) -> "LOOSE SALE (KG)"
                                else -> "LOOSE SALE (${primaryUnit.uppercase()})"
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (hasBulk) StorePrimary.copy(alpha = 0.15f) else AccentYellowContainer
                            ) {
                                Text(
                                    text = badgeLabel,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (hasBulk) StorePrimary else AccentYellowText,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Rate: ",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                            val rateDisplay = if (selectedUnit.equals(bulkUnit, ignoreCase = true) || selectedUnit.equals("box", ignoreCase = true)) {
                                val bulkSubUnitLabel = BengaliReceiptTranslator.translateUnit(pieceUnit, isBn)
                                val bulkQtyStr = if (bulkQty % 1.0 == 0.0) bulkQty.toInt().toString() else "%.1f".format(bulkQty)
                                val bUnitLabel = BengaliReceiptTranslator.translateUnit(bulkUnit, isBn)
                                "₹%.0f / %s (%s %s)".format(bulkPrice, bUnitLabel, bulkQtyStr, bulkSubUnitLabel)
                            } else {
                                val unitLabel = if (hasBulk) BengaliReceiptTranslator.translateUnit(pieceUnit, isBn) else BengaliReceiptTranslator.translateUnit(primaryUnit, isBn)
                                "₹%.0f / %s".format(product.sellingPrice, unitLabel)
                            }
                            Text(
                                text = rateDisplay,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = StoreBlueUPI
                            )
                            Text(
                                text = "  •  Stock: ",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                            Text(
                                text = product.getFormattedStockDisplay(isBn),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = TextDark
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = TextMuted
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Unit Selection Switch (Bulk vs Single Unit)
                if (hasBulk) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = SurfaceWarm,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val singleUnitDisplayName = BengaliReceiptTranslator.translateUnit(pieceUnit, isBn)
                            val bulkUnitDisplayName = BengaliReceiptTranslator.translateUnit(bulkUnit, isBn)
                            val bulkQtyStr = if (bulkQty % 1.0 == 0.0) bulkQty.toInt().toString() else "%.1f".format(bulkQty)
                            val options = listOf(
                                pieceUnit to "%s (₹%.0f/%s)".format(singleUnitDisplayName, product.sellingPrice, singleUnitDisplayName),
                                bulkUnit to "%s (₹%.0f • %s %s)".format(bulkUnitDisplayName, bulkPrice, bulkQtyStr, singleUnitDisplayName)
                            )
                            options.forEach { (uKey, label) ->
                                val isSelected = selectedUnit.equals(uKey, ignoreCase = true)
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            if (!selectedUnit.equals(uKey, ignoreCase = true)) {
                                                selectedUnit = uKey
                                                updateFromQty("1", uKey)
                                            }
                                        },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) StoreBlueUPI else Color.Transparent,
                                    border = if (!isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)) else null,
                                    shadowElevation = if (isSelected) 2.dp else 0.dp
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 10.dp, horizontal = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = label,
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) Color.White else TextMuted,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Segmented Tab Control (Pill container)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = SurfaceWarm,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val tabs = listOf(
                            if (isBn) "দ্রুত প্রিসেট" else "Quick Presets",
                            if (isBn) "সঠিক ওজন / পরিমাণ" else "Exact Qty /\nWeight",
                            if (isBn) "টাকা অনুযায়ী (₹)" else "Buy by Amount\n(₹)"
                        )

                        tabs.forEachIndexed { index, label ->
                            val isSelected = selectedTab == index
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { selectedTab = index },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) CardBackground else Color.Transparent,
                                border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)) else null,
                                shadowElevation = if (isSelected) 1.dp else 0.dp
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 10.dp, horizontal = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) TextDark else TextMuted,
                                        textAlign = TextAlign.Center,
                                        lineHeight = 14.sp
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Tab Content
                when (selectedTab) {
                    0 -> {
                        // Tab 0: Quick Presets
                        Text(
                            text = if (isBn) "একটি দ্রুত পরিমাণ / ওজন নির্বাচন করুন:" else "Select a quick preset quantity or weight:",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        val isBulkSelected = selectedUnit.equals(bulkUnit, ignoreCase = true) || selectedUnit.equals("box", ignoreCase = true)
                        val presets = when {
                            isBulkSelected -> listOf("1 $bulkUnit", "2 $bulkUnit", "3 $bulkUnit", "5 $bulkUnit", "10 $bulkUnit", "20 $bulkUnit")
                            hasBulk -> {
                                val bQInt = bulkQty.toInt()
                                if (Product.isKgUnit(primaryUnit)) {
                                    listOf("250g", "500g", "1 kg", "2 kg", "$bQInt kg", "${bQInt * 2} kg")
                                } else if (Product.isLitreUnit(primaryUnit)) {
                                    listOf("250ml", "500ml", "1 L", "2 L", "$bQInt L", "${bQInt * 2} L")
                                } else {
                                    listOf("1 $pieceUnit", "2 $pieceUnit", "5 $pieceUnit", "10 $pieceUnit", "$bQInt $pieceUnit", "${bQInt * 2} $pieceUnit")
                                }
                            }
                            Product.isGramUnit(primaryUnit) -> listOf("100g", "200g", "250g", "500g", "1000g")
                            Product.isMlUnit(primaryUnit) -> listOf("100ml", "200ml", "250ml", "500ml", "1000ml")
                            Product.isKgUnit(primaryUnit) -> listOf("100g", "250g", "500g", "1 kg", "2 kg", "5 kg")
                            Product.isLitreUnit(primaryUnit) -> listOf("100ml", "250ml", "500ml", "1 L", "2 L", "5 L")
                            else -> listOf("1 pc", "2 pc", "5 pc", "10 pc", "12 pc")
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            presets.chunked(3).forEach { rowPresets ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    rowPresets.forEach { presetLabel ->
                                        val clean = presetLabel.trim()
                                        val cleanLower = clean.lowercase()
                                        val isKg = cleanLower.endsWith("kg")
                                        val isGram = !isKg && (cleanLower.endsWith("g") || cleanLower.endsWith("gram"))
                                        val isMl = cleanLower.endsWith("ml")
                                        val isLiter = cleanLower.endsWith("l") || cleanLower.endsWith("liter") || cleanLower.endsWith("litre")
                                        val isBulkMatch = isBulkSelected || cleanLower.contains(bulkUnit.lowercase())

                                        val targetUnit = when {
                                            isBulkMatch -> bulkUnit
                                            isKg -> "kg"
                                            isGram -> "gram"
                                            isMl -> "ml"
                                            isLiter -> primaryUnit
                                            hasBulk -> pieceUnit
                                            else -> primaryUnit
                                        }

                                        val targetQty = when {
                                            isKg -> clean.substring(0, clean.length - 2).trim()
                                            isGram -> clean.replace(Regex("[^0-9.]"), "")
                                            isMl -> clean.replace(Regex("[^0-9.]"), "")
                                            isLiter -> clean.replace(Regex("[^0-9.]"), "")
                                            isBulkMatch -> clean.replace(Regex("[^0-9.]"), "").ifEmpty { "1" }
                                            else -> clean.replace(Regex("[^0-9.]"), "").ifEmpty { "1" }
                                        }

                                        val isPresetActive = selectedUnit.equals(targetUnit, ignoreCase = true) && qtyText == targetQty

                                        Button(
                                            onClick = {
                                                selectedUnit = targetUnit
                                                updateFromQty(targetQty, targetUnit)
                                            },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (isPresetActive) StoreBlueUPI else SurfaceWarm,
                                                contentColor = if (isPresetActive) Color.White else StoreBlueUPI
                                            ),
                                            border = BorderStroke(1.dp, if (isPresetActive) StoreBlueUPI else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                                            contentPadding = PaddingValues(vertical = 10.dp)
                                        ) {
                                            Text(
                                                text = presetLabel,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    repeat(3 - rowPresets.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }

                    1 -> {
                        // Tab 1: Exact Weight / Grams
                        Text(
                            text = if (isBn) "সঠিক পরিমাণ বা ওজন লিখুন:" else "Enter exact weight or quantity:",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        if (!hasBox && product.isSecondaryUnitSupported()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(primaryUnit, secondaryUnit).forEach { u ->
                                    val isSelected = selectedUnit.equals(u, ignoreCase = true)
                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                if (!selectedUnit.equals(u, ignoreCase = true)) {
                                                    val oldUnit = selectedUnit
                                                    selectedUnit = u
                                                    val q = qtyText.toDoubleOrNull() ?: 1.0
                                                    if (Product.isKgUnit(oldUnit) && Product.isGramUnit(u)) {
                                                        updateFromQty((q * 1000.0).toInt().toString(), u)
                                                    } else if (Product.isGramUnit(oldUnit) && Product.isKgUnit(u)) {
                                                        updateFromQty("%.3f".format(q / 1000.0), u)
                                                    } else if (Product.isLitreUnit(oldUnit) && Product.isMlUnit(u)) {
                                                        updateFromQty((q * 1000.0).toInt().toString(), u)
                                                    } else if (Product.isMlUnit(oldUnit) && Product.isLitreUnit(u)) {
                                                        updateFromQty("%.3f".format(q / 1000.0), u)
                                                    } else {
                                                        updateFromQty(if (Product.isGramUnit(u) || Product.isMlUnit(u)) "250" else "1", u)
                                                    }
                                                }
                                            },
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isSelected) StoreBlueUPI else SurfaceWarm
                                    ) {
                                        Box(
                                            modifier = Modifier.padding(vertical = 8.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = u.uppercase(),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSelected) Color.White else TextDark
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        val unitDisplay = if (selectedUnit.equals(bulkUnit, ignoreCase = true) || selectedUnit.equals("box", ignoreCase = true)) {
                            BengaliReceiptTranslator.translateUnit(bulkUnit, isBn)
                        } else if (hasBulk) {
                            BengaliReceiptTranslator.translateUnit(pieceUnit, isBn)
                        } else {
                            BengaliReceiptTranslator.translateUnit(selectedUnit, isBn)
                        }

                        OutlinedTextField(
                            value = qtyText,
                            onValueChange = { updateFromQty(it, selectedUnit) },
                            label = { Text(if (isBn) "পরিমাণ ($unitDisplay)" else "Quantity / Weight ($unitDisplay)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        )
                    }

                    2 -> {
                        // Tab 2: Buy by Amount (₹)
                        Text(
                            text = if (isBn) "গ্রাহক নির্দিষ্ট টাকার সমপরিমাণ চায় (যেমন ₹৫০ টাকার):" else "Customer wants fixed money worth (e.g. ₹50 worth):",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = SurfaceWarm),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Target Amount (₹)",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TextDark
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                OutlinedTextField(
                                    value = priceText,
                                    onValueChange = { updateFromPrice(it, selectedUnit) },
                                    leadingIcon = {
                                        Text(
                                            text = "₹",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 20.sp,
                                            color = TextDark
                                        )
                                    },
                                    textStyle = androidx.compose.ui.text.TextStyle(
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextDark
                                    ),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = CardBackground,
                                        unfocusedContainerColor = CardBackground
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    listOf(10, 20, 50, 100, 200, 500).forEach { amount ->
                                        Surface(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable { updateFromPrice(amount.toString(), selectedUnit) },
                                            shape = RoundedCornerShape(6.dp),
                                            color = CardBackground,
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                                        ) {
                                            Box(
                                                modifier = Modifier.padding(vertical = 6.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = "₹$amount",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = TextDark
                                                )
                                            }
                                        }
                                    }
                                }

                                if (product.isDiscreteUnit(selectedUnit)) {
                                    val unitRate = if (selectedUnit.equals(bulkUnit, ignoreCase = true) || selectedUnit.equals("box", ignoreCase = true) || selectedUnit.equals("case", ignoreCase = true)) {
                                        bulkPrice
                                    } else {
                                        product.sellingPrice
                                    }
                                    val targetP = priceText.toDoubleOrNull() ?: 0.0
                                    val changeDue = (targetP - finalCalculatedPrice).coerceAtLeast(0.0)
                                    val isUnderMin = targetP > 0.0 && targetP < unitRate
                                    val unitLabel = BengaliReceiptTranslator.translateUnit(selectedUnit, isBn)

                                    if (targetP > 0.0) {
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Surface(
                                            color = if (changeDue > 0.0) Color(0xFFF0FDF4) else CardBackground,
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(1.dp, if (changeDue > 0.0) StoreGreenProfit.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(modifier = Modifier.padding(10.dp)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = "${finalEnteredQty.toInt()} $unitLabel × ₹%.2f".format(unitRate),
                                                        fontWeight = FontWeight.SemiBold,
                                                        fontSize = 12.sp,
                                                        color = TextDark
                                                    )
                                                    Text(
                                                        text = "= ₹%.2f".format(finalCalculatedPrice),
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 13.sp,
                                                        color = TextDark
                                                    )
                                                }
                                                if (changeDue > 0.0) {
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(
                                                            text = if (isBn) "ফেরত বাকি (Return Change):" else "Return Change to Customer:",
                                                            fontSize = 11.5.sp,
                                                            fontWeight = FontWeight.Medium,
                                                            color = StoreGreenProfit
                                                        )
                                                        Text(
                                                            text = "₹%.2f".format(changeDue),
                                                            fontSize = 12.5.sp,
                                                            fontWeight = FontWeight.ExtraBold,
                                                            color = StoreGreenProfit
                                                        )
                                                    }
                                                } else if (isUnderMin) {
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = if (isBn) "নূন্যতম ১টি বিক্রি হবে (₹%.2f)".format(unitRate) else "Minimum 1 whole unit (₹%.2f)".format(unitRate),
                                                        fontSize = 11.sp,
                                                        color = AccentYellowText,
                                                        fontWeight = FontWeight.Medium
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

                Spacer(modifier = Modifier.height(16.dp))

                // Calculated Summary Banner
                val formattedQtyDisplay = if (finalEnteredQty % 1.0 == 0.0) {
                    finalEnteredQty.toInt().toString()
                } else {
                    "%.3f".format(finalEnteredQty)
                }

                val unitAbbrev = when (selectedUnit.lowercase()) {
                    "liter", "litre" -> "L"
                    "gram" -> "g"
                    "piece", "pc", "pcs" -> if (isBn) "পিস" else "pc"
                    "box" -> if (isBn) "বক্স" else "box"
                    else -> BengaliReceiptTranslator.translateUnit(selectedUnit, isBn)
                }

                val formattedPriceDisplay = if (finalCalculatedPrice % 1.0 == 0.0) {
                    "₹${finalCalculatedPrice.toInt()}"
                } else {
                    "₹%.2f".format(finalCalculatedPrice)
                }

                Surface(
                    color = AccentBlueContainer,
                    border = BorderStroke(1.dp, StoreBlueUPI.copy(alpha = 0.3f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "SELECTED QUANTITY",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = AccentBlueText
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "$formattedQtyDisplay $unitAbbrev",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextDark
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "CALCULATED PRICE",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = AccentBlueText
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = formattedPriceDisplay,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = StoreBlueUPI
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text(
                            text = LanguageManager.getString("Cancel", "বাতিল"),
                            color = TextDark,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Button(
                        onClick = {
                            if (!isSubmitting && finalEnteredQty > 0) {
                                isSubmitting = true
                                onConfirm(selectedUnit, finalEnteredQty)
                            }
                        },
                        enabled = !isSubmitting,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = StoreBlueUPI)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = LanguageManager.getString("Add to Bill", "বিলে যোগ করুন"),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}



@Composable
fun BadgeBox(badgeCount: Int, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = StoreOrangeWarning.copy(alpha = 0.15f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.HourglassTop, contentDescription = null, tint = StoreOrangeWarning, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                "$badgeCount Held",
                style = MaterialTheme.typography.labelSmall,
                color = StoreOrangeWarning,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickReturnSearchDialog(
    allSales: List<SaleWithItems>,
    allProducts: List<Product>,
    allReturns: List<com.example.data.local.entities.SaleReturnWithItems> = emptyList(),
    allCustomers: List<com.example.data.local.entities.Customer> = emptyList(),
    onDismiss: () -> Unit,
    onConfirmReturn: (SaleReturn, List<ReturnItem>) -> Unit,
    onViewPastReturn: (com.example.data.local.entities.SaleReturnWithItems) -> Unit = {}
) {
    val isBn = LanguageManager.isBengali
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = New Return (Search Bills), 1 = Past Returns History
    var searchQuery by remember { mutableStateOf("") }
    var selectedSale by remember { mutableStateOf<SaleWithItems?>(null) }
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()) }

    val filteredSales = remember(allSales, searchQuery) {
        if (searchQuery.isBlank()) allSales.take(20)
        else allSales.filter { s ->
            s.sale.id.contains(searchQuery, ignoreCase = true) ||
                    (s.sale.customerName?.contains(searchQuery, ignoreCase = true) == true) ||
                    s.items.any { item -> item.productNameEn.contains(searchQuery, ignoreCase = true) || item.productNameBn.contains(searchQuery, ignoreCase = true) }
        }
    }

    val filteredReturns = remember(allReturns, searchQuery) {
        if (searchQuery.isBlank()) allReturns.take(30)
        else allReturns.filter { ret ->
            ret.saleReturn.id.contains(searchQuery, ignoreCase = true) ||
                    ret.saleReturn.saleId.contains(searchQuery, ignoreCase = true) ||
                    (ret.saleReturn.customerName?.contains(searchQuery, ignoreCase = true) == true) ||
                    (ret.saleReturn.notes?.contains(searchQuery, ignoreCase = true) == true) ||
                    ret.items.any { item -> item.productNameEn.contains(searchQuery, ignoreCase = true) || item.productNameBn.contains(searchQuery, ignoreCase = true) }
        }
    }

    if (selectedSale != null) {
        ReturnReplacementDialog(
            saleWithItems = selectedSale!!,
            allProducts = allProducts,
            allReturns = allReturns,
            allCustomers = allCustomers,
            onDismiss = { selectedSale = null },
            onConfirmReturn = { saleReturn, returnItems ->
                onConfirmReturn(saleReturn, returnItems)
                selectedSale = null
            }
        )
    } else {
        Dialog(onDismissRequest = onDismiss) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.88f)
                    .padding(8.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AssignmentReturn, contentDescription = null, tint = StoreRedAlert)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isBn) "পণ্য ফেরত ও পরিবর্তন (Returns & Exchanges)" else "Returns & Replacements",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Tab Row: New Return vs Past Returns
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = CardBackground,
                        contentColor = StorePrimary,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = {
                                Text(
                                    text = if (isBn) "নতুন ফেরত / বদল" else "New Return / Replace",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = {
                                Text(
                                    text = if (isBn) "পূর্বের ফেরত তালিকা (${allReturns.size})" else "Return History (${allReturns.size})",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            Text(
                                if (selectedTab == 0) {
                                    if (isBn) "বিল নং, কাস্টমার বা পণ্য খুঁজুন..." else "Search by Bill #, Customer or Product..."
                                } else {
                                    if (isBn) "ভাউচার নং, বিল নং বা কাস্টমার খুঁজুন..." else "Search by Voucher #, Bill #, Customer..."
                                }
                            )
                        },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    if (selectedTab == 0) {
                        // TAB 0: Sales Bills
                        Text(
                            text = if (isBn) "বিক্রয় বিলসমূহ (ফেরত বা বদল দিতে ক্লিক করুন):" else "Sales Bills (Click to Return or Replace):",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextMuted
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        if (filteredSales.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (isBn) "কোনো বিক্রয় বিল পাওয়া যায়নি" else "No matching sales bills found",
                                    color = TextMuted
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(filteredSales, key = { it.sale.id }, contentType = { "POS_RECENT_SALE_CARD" }) { saleWithItems ->
                                    val sale = saleWithItems.sale
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { selectedSale = saleWithItems },
                                        colors = CardDefaults.cardColors(containerColor = CardBackground),
                                        border = BorderStroke(1.dp, TextMuted.copy(alpha = 0.2f)),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "Bill #${sale.id.takeLast(8)}",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp,
                                                    color = StorePrimary
                                                )
                                                Text(
                                                    text = "${sale.customerName ?: if (isBn) "সাধারণ গ্রাহক" else "Walk-in Customer"} • ${dateFormat.format(Date(sale.datetime))}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = TextMuted
                                                )
                                                Text(
                                                    text = "${saleWithItems.items.size} items • ${sale.paymentMode}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = TextMuted
                                                )
                                            }

                                            Column(horizontalAlignment = Alignment.End) {
                                                Text(
                                                    text = "₹%.2f".format(sale.finalAmount),
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 14.sp,
                                                    color = StoreGreenProfit
                                                )
                                                Surface(
                                                    color = StoreRedAlert.copy(alpha = 0.15f),
                                                    shape = RoundedCornerShape(4.dp),
                                                    modifier = Modifier.padding(top = 2.dp)
                                                ) {
                                                    Text(
                                                        text = if (isBn) "ফেরত / বদল" else "Return / Replace",
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = StoreRedAlert,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // TAB 1: Returns History
                        Text(
                            text = if (isBn) "পূর্বের ফেরত ও বদল ভাউচারসমূহ:" else "Past Return & Exchange Vouchers:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextMuted
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        if (filteredReturns.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (isBn) "কোনো ফেরত ভাউচার পাওয়া যায়নি" else "No return vouchers found",
                                    color = TextMuted
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(filteredReturns, key = { it.saleReturn.id }) { ret ->
                                    val sr = ret.saleReturn
                                    val isRepl = sr.type.equals("REPLACEMENT", ignoreCase = true)
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                onViewPastReturn(ret)
                                            },
                                        colors = CardDefaults.cardColors(containerColor = CardBackground),
                                        border = BorderStroke(1.dp, if (isRepl) StorePrimary.copy(alpha = 0.3f) else StoreRedAlert.copy(alpha = 0.3f)),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Surface(
                                                        color = if (isRepl) StorePrimary.copy(alpha = 0.15f) else StoreRedAlert.copy(alpha = 0.15f),
                                                        shape = RoundedCornerShape(4.dp)
                                                    ) {
                                                        Text(
                                                            text = if (isRepl) (if (isBn) "বদল" else "REPLACE") else (if (isBn) "ফেরত" else "RETURN"),
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = if (isRepl) StorePrimary else StoreRedAlert,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = "Voucher #${sr.id}",
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 13.sp,
                                                        color = TextDark
                                                    )
                                                }
                                                Text(
                                                    text = "Ref Bill #${sr.saleId.takeLast(8)} • ${dateFormat.format(Date(sr.datetime))}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = TextMuted
                                                )
                                                Text(
                                                    text = "${sr.customerName ?: if (isBn) "সাধারণ গ্রাহক" else "Walk-in Customer"} • ${ret.items.size} items • ${sr.refundPaymentMode}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = TextMuted
                                                )
                                            }

                                            Column(horizontalAlignment = Alignment.End) {
                                                Text(
                                                    text = "₹%.2f".format(kotlin.math.abs(sr.netAmount)),
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 14.sp,
                                                    color = if (sr.netAmount > 0) StoreRedAlert else if (sr.netAmount < 0) StoreGreenProfit else StorePrimary
                                                )
                                                Text(
                                                    text = if (sr.netAmount > 0) "Refund" else if (sr.netAmount < 0) "Collected" else "Even",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = TextMuted
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
    }
}
