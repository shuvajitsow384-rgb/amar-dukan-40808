package com.example.ui.screens.orders

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.models.*
import com.example.data.local.entities.Customer
import com.example.ui.components.BarcodeScannerModal
import com.example.ui.components.CreditLimitExceededWarningDialog
import com.example.ui.components.PaymentScreenshotViewer
import com.example.ui.screens.pos.AddCustomerDialog
import com.example.ui.screens.pos.CustomerPickerDialog
import com.example.ui.theme.*
import com.example.utils.LanguageManager
import com.example.utils.StaffManager
import com.example.viewmodel.StoreViewModel
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncomingOrdersScreen(
    viewModel: StoreViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val isBn = LanguageManager.isBengali
    val orders by viewModel.allOnlineOrders.collectAsState()
    val pendingCount by viewModel.pendingOrdersCount.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    // 300ms debounce for order search
    var debouncedSearchQuery by remember { mutableStateOf("") }
    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            debouncedSearchQuery = ""
        } else {
            kotlinx.coroutines.delay(300)
            debouncedSearchQuery = searchQuery
        }
    }
    var isSearchExpanded by remember { mutableStateOf(false) }

    var cancellingOrder by remember { mutableStateOf<Order?>(null) }
    var shortageItems by remember { mutableStateOf<List<InsufficientStockItem>?>(null) }
    var pendingShortageOrder by remember { mutableStateOf<Order?>(null) }
    var isProcessingAction by remember { mutableStateOf(false) }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }

    // Barcode Scanning & Order Dispatch Modal States
    var showBarcodeScanner by remember { mutableStateOf(false) }
    var scannedOrderForDispatch by remember { mutableStateOf<Order?>(null) }
    var creditLimitExceededError by remember { mutableStateOf<CreditLimitExceededException?>(null) }
    var pendingCreditFulfillParams by remember { mutableStateOf<Pair<Order, Customer?>?>(null) }
    var showOwnerPinDialogForCreditOverride by remember { mutableStateOf(false) }
    var enteredOwnerPinForOverride by remember { mutableStateOf("") }
    var ownerPinErrorForOverride by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            snackbarMessage = null
        }
    }

    // Categorized Orders
    val pendingOrders = remember(orders, debouncedSearchQuery) {
        orders.filter { it.status == OrderStatus.PLACED }
            .filter { filterOrder(it, debouncedSearchQuery) }
            .sortedByDescending { it.createdAt }
    }

    val inProgressOrders = remember(orders, debouncedSearchQuery) {
        orders.filter { it.status in listOf(OrderStatus.CONFIRMED, OrderStatus.READY, OrderStatus.OUT_FOR_DELIVERY) }
            .filter { filterOrder(it, debouncedSearchQuery) }
            .sortedByDescending { it.updatedAt }
    }

    val historyOrders = remember(orders, debouncedSearchQuery) {
        orders.filter { it.status in listOf(OrderStatus.COMPLETED, OrderStatus.CANCELLED) }
            .filter { filterOrder(it, debouncedSearchQuery) }
            .sortedByDescending { it.updatedAt }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (isBn) "অনলাইন অর্ডার" else "Incoming Orders",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            text = if (isBn) "লাইভ ক্লাউড সিঙ্ক সক্রিয় • ${orders.size} টি মোট অর্ডার"
                            else "Live Firestore Sync Active • ${orders.size} Total Orders",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("orders_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showBarcodeScanner = true },
                        modifier = Modifier.testTag("orders_scan_barcode_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = "Scan Packing Slip Barcode"
                        )
                    }
                    IconButton(
                        onClick = {
                            viewModel.syncUploadAllToCloud { success, message ->
                                snackbarMessage = if (success) {
                                    val count = viewModel.lastSyncSummary.value?.productsCount ?: 0
                                    if (isBn) "$count টি পণ্য ক্লাউডে সফলভাবে আপলোড হয়েছে!" else "$count products uploaded to cloud successfully!"
                                } else {
                                    message
                                }
                            }
                        },
                        modifier = Modifier.testTag("orders_sync_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = "Sync Cloud Data"
                        )
                    }
                    IconButton(
                        onClick = { isSearchExpanded = !isSearchExpanded },
                        modifier = Modifier.testTag("orders_search_toggle")
                    ) {
                        Icon(
                            imageVector = if (isSearchExpanded) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = "Search Orders"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        LaunchedEffect(Unit) {
            viewModel.dismissOrderInAppAlert()
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(NeutralBackground)
        ) {
            // Search Bar (if expanded)
            AnimatedVisibility(visible = isSearchExpanded) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            if (isBn) "অর্ডার নম্বর, নাম বা ফোন নম্বর খুঁজুন..."
                            else "Search by order #, customer name or phone..."
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("orders_search_input")
                )
            }

            // 3-Tab Selector: Pending, In Progress, Completed/History
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = StorePrimary,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    modifier = Modifier.testTag("tab_pending_orders")
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = if (isBn) "নতুন / অপেক্ষমান" else "Pending",
                            fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 13.sp
                        )
                        if (pendingCount > 0) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Badge(
                                containerColor = Color(0xFFEA580C),
                                contentColor = Color.White
                            ) {
                                Text("$pendingCount", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                        }
                    }
                }

                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    modifier = Modifier.testTag("tab_inprogress_orders")
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        val inProgressCount = orders.count {
                            it.status in listOf(OrderStatus.CONFIRMED, OrderStatus.READY, OrderStatus.OUT_FOR_DELIVERY)
                        }
                        Text(
                            text = if (isBn) "চলমান" else "In Progress",
                            fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 13.sp
                        )
                        if (inProgressCount > 0) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Badge(
                                containerColor = Color(0xFF0284C7),
                                contentColor = Color.White
                            ) {
                                Text("$inProgressCount", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                        }
                    }
                }

                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    modifier = Modifier.testTag("tab_history_orders")
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = if (isBn) "ইতিহাস" else "History",
                            fontWeight = if (selectedTab == 2) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            // Tab Content
            val currentList = when (selectedTab) {
                0 -> pendingOrders
                1 -> inProgressOrders
                else -> historyOrders
            }

            if (currentList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = when (selectedTab) {
                                0 -> Icons.Default.ShoppingBag
                                1 -> Icons.Default.LocalShipping
                                else -> Icons.Default.CheckCircle
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = when (selectedTab) {
                                0 -> if (isBn) "কোনো নতুন অপেক্ষমান অর্ডার নেই" else "No Pending Orders"
                                1 -> if (isBn) "বর্তমানে কোনো অর্ডার প্রক্রিয়াধীন নেই" else "No Orders In Progress"
                                else -> if (isBn) "অর্ডার ইতিহাস খালি" else "No Completed Orders Yet"
                            },
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = when (selectedTab) {
                                0 -> if (isBn) "গ্রাহক অনলাইন স্টোর থেকে অর্ডার দিলে তা এখানে প্রদর্শিত হবে।" else "New customer orders from your web storefront will appear here live."
                                else -> if (isBn) "পূর্ববর্তী বা সম্পন্ন অর্ডার এখানে সংরক্ষিত থাকবে।" else "Accepted and fulfilled orders will be logged here."
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(currentList, key = { it.id }, contentType = { "ONLINE_ORDER_CARD" }) { order ->
                        OrderCard(
                            order = order,
                            isBn = isBn,
                            isProcessing = isProcessingAction,
                            onAccept = {
                                isProcessingAction = true
                                viewModel.acceptOnlineOrder(
                                    order = order,
                                    onShortage = { shortList ->
                                        isProcessingAction = false
                                        shortageItems = shortList
                                        pendingShortageOrder = order
                                    },
                                    onSuccess = {
                                        isProcessingAction = false
                                        snackbarMessage = if (isBn) "অর্ডার #${order.orderNumber} নিশ্চিত করা হয়েছে!"
                                        else "Order #${order.orderNumber} Confirmed!"
                                    },
                                    onError = { err ->
                                        isProcessingAction = false
                                        snackbarMessage = "Error: $err"
                                    }
                                )
                            },
                            onCancelClick = {
                                cancellingOrder = order
                            },
                            onDispatch = {
                                isProcessingAction = true
                                viewModel.markOnlineOrderReadyOrDispatched(
                                    order = order,
                                    onSuccess = {
                                        isProcessingAction = false
                                        val statusText = if (order.fulfillmentType == FulfillmentType.DELIVERY) {
                                            if (isBn) "ডেলিভারির জন্য পাঠানো হয়েছে" else "Out for Delivery"
                                        } else {
                                            if (isBn) "পিকআপের জন্য প্রস্তুত" else "Ready for Pickup"
                                        }
                                        snackbarMessage = "Order #${order.orderNumber}: $statusText"
                                    },
                                    onError = { err ->
                                        isProcessingAction = false
                                        snackbarMessage = "Error: $err"
                                    }
                                )
                            },
                            onMarkPaid = {
                                isProcessingAction = true
                                viewModel.markOnlineOrderPaid(
                                    order = order,
                                    onSuccess = {
                                        isProcessingAction = false
                                        snackbarMessage = if (isBn) "অর্ডার #${order.orderNumber} পেমেন্ট পেইড হিসেবে চিহ্নিত হয়েছে!"
                                        else "Order #${order.orderNumber} payment marked as PAID!"
                                    },
                                    onError = { err ->
                                        isProcessingAction = false
                                        snackbarMessage = "Error: $err"
                                    }
                                )
                            },
                            onFulfill = {
                                scannedOrderForDispatch = order
                            },
                            onPrintSlip = {
                                viewModel.printOnlineOrderPackingSlip(
                                    order = order,
                                    isBengali = isBn,
                                    onResult = { _, msg ->
                                        snackbarMessage = msg
                                    }
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    // Barcode Scanner Modal for Quick Packing Slip Scan & Dispatch
    if (showBarcodeScanner) {
        BarcodeScannerModal(
            viewModel = viewModel,
            onDismiss = { showBarcodeScanner = false },
            onOrderScanned = { scannedOrderNumber ->
                showBarcodeScanner = false
                val cleanScanned = scannedOrderNumber.trim()
                val matched = orders.find {
                    it.orderNumber.equals(cleanScanned, ignoreCase = true) ||
                    it.id.equals(cleanScanned, ignoreCase = true) ||
                    it.orderNumber.endsWith(cleanScanned, ignoreCase = true) ||
                    cleanScanned.endsWith(it.orderNumber, ignoreCase = true)
                }
                if (matched != null) {
                    scannedOrderForDispatch = matched
                } else {
                    snackbarMessage = if (isBn) "অর্ডার #$scannedOrderNumber খুঁজে পাওয়া যায়নি" else "Order #$scannedOrderNumber not found"
                }
            }
        )
    }

    // Scanned Order Dispatch & Packing Slip Dialog
    scannedOrderForDispatch?.let { order ->
        ScannedOrderDispatchDialog(
            order = order,
            viewModel = viewModel,
            isBn = isBn,
            isProcessing = isProcessingAction,
            onDismiss = { scannedOrderForDispatch = null },
            onPrintSlip = {
                viewModel.printOnlineOrderPackingSlip(
                    order = order,
                    isBengali = isBn,
                    onResult = { _, msg ->
                        snackbarMessage = msg
                    }
                )
            },
            onAccept = {
                isProcessingAction = true
                viewModel.acceptOnlineOrder(
                    order = order,
                    onShortage = { shortList ->
                        isProcessingAction = false
                        scannedOrderForDispatch = null
                        shortageItems = shortList
                    },
                    onSuccess = {
                        isProcessingAction = false
                        snackbarMessage = if (isBn) "অর্ডার #${order.orderNumber} নিশ্চিত করা হয়েছে!" else "Order #${order.orderNumber} Confirmed!"
                        scannedOrderForDispatch = null
                    },
                    onError = { err ->
                        isProcessingAction = false
                        snackbarMessage = "Error: $err"
                    }
                )
            },
            onDispatch = {
                isProcessingAction = true
                viewModel.markOnlineOrderReadyOrDispatched(
                    order = order,
                    onSuccess = {
                        isProcessingAction = false
                        val statusText = if (order.fulfillmentType == FulfillmentType.DELIVERY) {
                            if (isBn) "ডেলিভারির জন্য পাঠানো হয়েছে" else "Out for Delivery"
                        } else {
                            if (isBn) "পিকআপের জন্য প্রস্তুত" else "Ready for Pickup"
                        }
                        snackbarMessage = "Order #${order.orderNumber}: $statusText"
                        scannedOrderForDispatch = null
                    },
                    onError = { err ->
                        isProcessingAction = false
                        snackbarMessage = "Error: $err"
                    }
                )
            },
            onFulfill = { selectedMethod, creditCustomer ->
                isProcessingAction = true
                pendingCreditFulfillParams = Pair(order, creditCustomer)
                viewModel.fulfillAndCompleteOnlineOrder(
                    order = order,
                    finalPaymentMethod = selectedMethod,
                    creditCustomer = creditCustomer,
                    onShortage = { shortList ->
                        isProcessingAction = false
                        scannedOrderForDispatch = null
                        shortageItems = shortList
                    },
                    onCreditLimitExceeded = { ex ->
                        isProcessingAction = false
                        creditLimitExceededError = ex
                    },
                    onSuccess = { saleId ->
                        isProcessingAction = false
                        pendingCreditFulfillParams = null
                        scannedOrderForDispatch = null
                        snackbarMessage = if (isBn) "অর্ডার সম্পন্ন হয়েছে! বিক্রয় রসিদ #${saleId.takeLast(6)}"
                        else "Order fulfilled! Sale recorded #${saleId.takeLast(6)}"
                    },
                    onError = { err ->
                        isProcessingAction = false
                        snackbarMessage = "Fulfillment Error: $err"
                    }
                )
            }
        )
    }

    // Credit Limit Exceeded Override Dialog
    creditLimitExceededError?.let { ex ->
        val isPrivilegedUser = viewModel.canOverrideCreditLimit()

        val executeOverride = {
            val params = pendingCreditFulfillParams
            creditLimitExceededError = null
            pendingCreditFulfillParams = null
            showOwnerPinDialogForCreditOverride = false
            enteredOwnerPinForOverride = ""
            ownerPinErrorForOverride = false
            if (params != null) {
                val (ord, cust) = params
                isProcessingAction = true
                viewModel.fulfillAndCompleteOnlineOrder(
                    order = ord,
                    finalPaymentMethod = "CREDIT",
                    creditCustomer = cust,
                    ownerOverrideCreditLimit = true,
                    onShortage = { shortList ->
                        isProcessingAction = false
                        scannedOrderForDispatch = null
                        shortageItems = shortList
                    },
                    onCreditLimitExceeded = { newEx ->
                        isProcessingAction = false
                        creditLimitExceededError = newEx
                    },
                    onSuccess = { saleId ->
                        isProcessingAction = false
                        scannedOrderForDispatch = null
                        snackbarMessage = if (isBn) "অর্ডার সম্পন্ন হয়েছে! বিক্রয় রসিদ #${saleId.takeLast(6)}"
                        else "Order fulfilled! Sale recorded #${saleId.takeLast(6)}"
                    },
                    onError = { err ->
                        isProcessingAction = false
                        snackbarMessage = "Fulfillment Error: $err"
                    }
                )
            }
        }

        CreditLimitExceededWarningDialog(
            exception = ex,
            isPrivilegedUser = isPrivilegedUser,
            isBn = isBn,
            onDismiss = {
                creditLimitExceededError = null
                pendingCreditFulfillParams = null
                showOwnerPinDialogForCreditOverride = false
                enteredOwnerPinForOverride = ""
                ownerPinErrorForOverride = false
            },
            onOverrideAndComplete = {
                executeOverride()
            },
            onAuthorizeWithPin = if (!isPrivilegedUser) {
                {
                    enteredOwnerPinForOverride = ""
                    ownerPinErrorForOverride = false
                    showOwnerPinDialogForCreditOverride = true
                }
            } else null
        )

        if (showOwnerPinDialogForCreditOverride) {
            AlertDialog(
                onDismissRequest = {
                    showOwnerPinDialogForCreditOverride = false
                    enteredOwnerPinForOverride = ""
                    ownerPinErrorForOverride = false
                },
                icon = {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFFD97706).copy(alpha = 0.12f),
                        modifier = Modifier.size(52.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = Color(0xFFD97706),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                },
                title = {
                    Text(
                        text = if (isBn) "মালিক পিন যাচাইকরণ" else "Owner PIN Authorization",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = if (isBn)
                                "বর্তমান কর্মী অ্যাকাউন্টে বাকী সীমা বাড়ানোর অনুমতি নেই। বিক্রয় অনুমোদন করতে স্টোর মালিকের পিন লিখুন।"
                            else
                                "Current staff account is not authorized to override customer credit limits. Please enter the Store Owner PIN to authorize this sale.",
                            fontSize = 13.sp,
                            color = TextMuted,
                            lineHeight = 18.sp
                        )
                        OutlinedTextField(
                            value = enteredOwnerPinForOverride,
                            onValueChange = {
                                if (it.length <= 6 && it.all { ch -> ch.isDigit() }) {
                                    enteredOwnerPinForOverride = it
                                    ownerPinErrorForOverride = false
                                }
                            },
                            label = { Text(if (isBn) "মালিক পিন" else "Owner PIN") },
                            placeholder = { Text("1234") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.NumberPassword
                            ),
                            isError = ownerPinErrorForOverride,
                            supportingText = if (ownerPinErrorForOverride) {
                                {
                                    Text(
                                        text = if (isBn) "ভুল পিন! অনুগ্রহ করে সঠিক মালিক পিন দিন" else "Incorrect PIN! Please enter valid Owner PIN",
                                        color = StoreRedAlert,
                                        fontSize = 12.sp
                                    )
                                }
                            } else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("credit_override_owner_pin_input")
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (StaffManager.verifyOwnerPin(enteredOwnerPinForOverride)) {
                                executeOverride()
                            } else {
                                ownerPinErrorForOverride = true
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("credit_override_confirm_pin_button")
                    ) {
                        Text(
                            text = if (isBn) "অনুমোদন করুন" else "Authorize",
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = {
                            showOwnerPinDialogForCreditOverride = false
                            enteredOwnerPinForOverride = ""
                            ownerPinErrorForOverride = false
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(text = if (isBn) "বাতিল" else "Cancel")
                    }
                }
            )
        }
    }

    // Cancel Order Dialog
    cancellingOrder?.let { orderToCancel ->
        CancelOrderDialog(
            order = orderToCancel,
            isBn = isBn,
            onDismiss = { cancellingOrder = null },
            onConfirm = { reason ->
                isProcessingAction = true
                viewModel.cancelOnlineOrder(
                    order = orderToCancel,
                    reason = reason,
                    onSuccess = {
                        isProcessingAction = false
                        cancellingOrder = null
                        snackbarMessage = if (isBn) "অর্ডার বাতিল করা হয়েছে" else "Order #${orderToCancel.orderNumber} cancelled"
                    },
                    onError = { err ->
                        isProcessingAction = false
                        cancellingOrder = null
                        snackbarMessage = "Error: $err"
                    }
                )
            }
        )
    }

    // Shortage / Insufficient Stock Alert Dialog
    shortageItems?.let { shortList ->
        InsufficientStockDialog(
            items = shortList,
            isBn = isBn,
            onDismiss = {
                shortageItems = null
                pendingShortageOrder = null
            },
            onProceedAnyway = {
                val ord = pendingShortageOrder
                shortageItems = null
                pendingShortageOrder = null
                if (ord != null) {
                    isProcessingAction = true
                    viewModel.acceptOnlineOrder(
                        order = ord,
                        allowShortageOverride = true,
                        onShortage = {},
                        onSuccess = {
                            isProcessingAction = false
                            snackbarMessage = if (isBn) "অর্ডার #${ord.orderNumber} নিশ্চিত করা হয়েছে!"
                            else "Order #${ord.orderNumber} Confirmed!"
                        },
                        onError = { err ->
                            isProcessingAction = false
                            snackbarMessage = "Error: $err"
                        }
                    )
                }
            }
        )
    }
}

@Composable
private fun OrderCard(
    order: Order,
    isBn: Boolean,
    isProcessing: Boolean,
    onAccept: () -> Unit,
    onCancelClick: () -> Unit,
    onDispatch: () -> Unit,
    onMarkPaid: () -> Unit,
    onFulfill: () -> Unit,
    onPrintSlip: () -> Unit
) {
    val context = LocalContext.current
    val timeFormatted = remember(order.createdAt) {
        try {
            val sdf = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
            sdf.format(Date(order.createdAt))
        } catch (e: Exception) {
            ""
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("order_card_${order.orderNumber}"),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(
            1.dp,
            when (order.status) {
                OrderStatus.PLACED -> Color(0xFFF59E0B).copy(alpha = 0.5f)
                OrderStatus.CONFIRMED, OrderStatus.READY, OrderStatus.OUT_FOR_DELIVERY -> Color(0xFF0284C7).copy(alpha = 0.4f)
                OrderStatus.COMPLETED -> Color(0xFF10B981).copy(alpha = 0.4f)
                OrderStatus.CANCELLED -> Color(0xFFEF4444).copy(alpha = 0.4f)
                else -> NeutralBorderDivider
            }
        ),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Header Row: Order Number, Relative Time, Fulfillment Badge, Print Slip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "#${order.orderNumber}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = TextDark
                    )
                    Text(
                        text = timeFormatted,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Thermal Packing Slip Print Button
                    IconButton(
                        onClick = onPrintSlip,
                        modifier = Modifier
                            .size(30.dp)
                            .testTag("btn_print_slip_${order.orderNumber}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Print,
                            contentDescription = if (isBn) "প্যাকিং স্লিপ প্রিন্ট" else "Print Packing Slip",
                            tint = StorePrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Fulfillment Badge (Pickup vs Delivery)
                    val isDelivery = order.fulfillmentType == FulfillmentType.DELIVERY
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isDelivery) Color(0xFFE0F2FE) else Color(0xFFF3E8FF),
                        border = BorderStroke(1.dp, if (isDelivery) Color(0xFF0284C7) else Color(0xFF9333EA))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isDelivery) Icons.Default.LocalShipping else Icons.Default.ShoppingBag,
                                contentDescription = null,
                                tint = if (isDelivery) Color(0xFF0369A1) else Color(0xFF7E22CE),
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isDelivery) (if (isBn) "হোম ডেলিভারি" else "Delivery")
                                else (if (isBn) "দোকান পিকআপ" else "Pickup"),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isDelivery) Color(0xFF0369A1) else Color(0xFF7E22CE)
                            )
                        }
                    }

                    // Delivery Slot Badge if set
                    if (isDelivery && !order.deliverySlot.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFFFEF3C7),
                            border = BorderStroke(1.dp, Color(0xFFFCD34D))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Schedule,
                                    contentDescription = null,
                                    tint = Color(0xFFB45309),
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = order.deliverySlot,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF92400E)
                                )
                            }
                        }
                    }

                    // Order Status Badge
                    StatusBadge(status = order.status, isBn = isBn)
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = NeutralBorderDivider)

            // Customer Details & Contact Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = order.customerName,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = TextDark
                    )
                    Text(
                        text = order.customerPhone,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Quick Communication Buttons (Call & WhatsApp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Call Button
                    FilledTonalButton(
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${order.customerPhone.trim()}"))
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Could not open dialer", Toast.LENGTH_SHORT).show()
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(Icons.Default.Phone, contentDescription = "Call", modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isBn) "কল" else "Call", fontSize = 11.sp)
                    }

                    // WhatsApp Button
                    Button(
                        onClick = {
                            try {
                                val cleanPhone = order.customerPhone.replace(Regex("[^0-9]"), "")
                                val waPhone = if (cleanPhone.length == 10) "91$cleanPhone" else cleanPhone
                                val waUrl = "https://wa.me/$waPhone"
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(waUrl))
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Could not open WhatsApp", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("WhatsApp", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Delivery Address if DELIVERY
            if (order.fulfillmentType == FulfillmentType.DELIVERY && order.deliveryAddress != null) {
                val addr = order.deliveryAddress
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = NeutralBackground,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = Color(0xFFDC2626),
                                modifier = Modifier.size(16.dp).padding(top = 1.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            val fullAddr = buildString {
                                append(addr.streetAddress)
                                if (!addr.landmark.isNullOrBlank()) append(" (Near: ${addr.landmark})")
                                if (!addr.pinCode.isNullOrBlank()) append(" - PIN: ${addr.pinCode}")
                            }
                            Text(
                                text = fullAddr,
                                fontSize = 12.sp,
                                color = TextDark,
                                lineHeight = 16.sp
                            )
                        }

                        // Pinned Location Navigation Button
                        if (addr.latitude != null && addr.longitude != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFFE0F2FE),
                                border = BorderStroke(1.dp, Color(0xFFBAE6FD)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        launchGoogleMapsDirections(context, addr.latitude, addr.longitude)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f, fill = false)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Explore,
                                            contentDescription = null,
                                            tint = Color(0xFF0284C7),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Column {
                                            Text(
                                                text = if (isBn) "ম্যাপে পিন করা লোকেশন" else "Pinned Map Location",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF0369A1)
                                            )
                                            Text(
                                                text = "%.5f, %.5f".format(addr.latitude, addr.longitude),
                                                fontSize = 10.sp,
                                                color = Color(0xFF0284C7)
                                            )
                                        }
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = Color(0xFF0284C7)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Directions,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = if (isBn) "গুগল ম্যাপস" else "Google Maps",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Customer Notes if present
            if (!order.customerNotes.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFFFFBEB),
                    border = BorderStroke(1.dp, Color(0xFFFDE68A)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Notes,
                            contentDescription = null,
                            tint = Color(0xFFD97706),
                            modifier = Modifier.size(15.dp).padding(top = 1.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "\"${order.customerNotes}\"",
                            fontSize = 12.sp,
                            color = Color(0xFF78350F),
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Itemized List Header & Rows
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = NeutralBackground,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (isBn) "পণ্য তালিকা (${order.itemsCount})" else "Items (${order.itemsCount})",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (isBn) "মূল্য" else "Amount",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))

                    order.items.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.name,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = TextDark,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val qtyFmt = if (item.quantity % 1.0 == 0.0) item.quantity.toInt().toString() else "%.2f".format(item.quantity)
                                Text(
                                    text = "$qtyFmt ${item.unit} × ₹%.2f".format(item.price),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "₹%.2f".format(item.subtotal),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = TextDark
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp), color = NeutralBorderDivider)

                    if (order.fulfillmentType == FulfillmentType.DELIVERY) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isBn) "পণ্য মূল্য (Subtotal):" else "Items Subtotal:",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "₹%.2f".format(order.subtotal),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextDark
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isBn) "ডেলিভারি চার্জ:" else "Delivery Fee:",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (order.deliveryFee > 0.0) "₹%.2f".format(order.deliveryFee)
                                else (if (isBn) "বিনামূল্যে (FREE)" else "FREE"),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (order.deliveryFee > 0.0) TextDark else StoreGreenProfit
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = NeutralBorderDivider)
                    }

                    // Subtotal & Grand Total
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isBn) "সর্বমোট টাকা:" else "Total Amount:",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = TextDark
                        )
                        Text(
                            text = "₹%.2f".format(order.totalAmount),
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = StoreGreenProfit
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Payment Status Section & UTR Verification
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PaymentStatusBadge(
                    status = order.paymentStatus,
                    method = order.paymentMethod,
                    isBn = isBn
                )

                if (order.status == OrderStatus.COMPLETED && !order.saleId.isNullOrBlank()) {
                    Text(
                        text = "Sale: #${order.saleId?.takeLast(6)}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Staff Attribution (Confirmed By & Dispatched By)
            val hasConfirmedBy = !order.confirmedByStaffName.isNullOrBlank()
            val hasDispatchedBy = !order.dispatchedByStaffName.isNullOrBlank()
            if (hasConfirmedBy || hasDispatchedBy) {
                Spacer(modifier = Modifier.height(4.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (hasConfirmedBy) {
                        Text(
                            text = if (isBn) "নিশ্চিত করেছেন: ${order.confirmedByStaffName}" else "Confirmed by: ${order.confirmedByStaffName}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (hasDispatchedBy) {
                        Text(
                            text = if (isBn) "ডেসপ্যাচ করেছেন: ${order.dispatchedByStaffName}" else "Dispatched by: ${order.dispatchedByStaffName}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Customer Submitted Payment Proof Card (UTR and/or Screenshot if present)
            val hasCardUtr = !order.paymentReference.isNullOrBlank()
            val hasCardScreenshot = !order.paymentScreenshotData.isNullOrBlank()
            if (hasCardUtr || hasCardScreenshot) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (order.paymentStatus == OrderPaymentStatus.PAID) Color(0xFFECFDF5) else Color(0xFFFEF3C7),
                    border = BorderStroke(1.dp, if (order.paymentStatus == OrderPaymentStatus.PAID) Color(0xFF10B981) else Color(0xFFF59E0B)),
                    modifier = Modifier.fillMaxWidth()
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
                            Column(modifier = Modifier.weight(1f, fill = false)) {
                                Text(
                                    text = if (isBn) {
                                        if (hasCardUtr) "গ্রাহকের প্রেরিত UPI UTR:" else "গ্রাহকের প্রেরিত পেমেন্ট প্রমাণ:"
                                    } else {
                                        if (hasCardUtr) "Customer UPI UTR:" else "Customer Payment Proof:"
                                    },
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (hasCardUtr) {
                                    Text(
                                        text = order.paymentReference ?: "",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextDark
                                    )
                                }
                            }

                            // Prominent "Mark as Paid" action if payment is still pending
                            if (order.paymentStatus != OrderPaymentStatus.PAID) {
                                Button(
                                    onClick = onMarkPaid,
                                    enabled = !isProcessing,
                                    colors = ButtonDefaults.buttonColors(containerColor = StoreGreenProfit),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(13.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (isBn) "পেইড নিশ্চিত করুন" else "Mark as Paid", fontSize = 11.sp)
                                }
                            }
                        }

                        // Inline screenshot preview with full-screen viewer
                        if (hasCardScreenshot) {
                            Spacer(modifier = Modifier.height(6.dp))
                            PaymentScreenshotViewer(
                                screenshotData = order.paymentScreenshotData,
                                cardHeight = 130.dp
                            )
                        }
                    }
                }
            }

            // Action Buttons by Status
            Spacer(modifier = Modifier.height(12.dp))
            when (order.status) {
                OrderStatus.PLACED -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onCancelClick,
                            enabled = !isProcessing,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFDC2626)),
                            border = BorderStroke(1.dp, Color(0xFFDC2626)),
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isBn) "বাতিল" else "Reject / Cancel", fontSize = 12.sp)
                        }

                        Button(
                            onClick = onAccept,
                            enabled = !isProcessing,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                            modifier = Modifier
                                .weight(1.3f)
                                .height(40.dp)
                                .testTag("btn_accept_order_${order.orderNumber}")
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isBn) "অর্ডার গ্রহণ করুন" else "Accept Order",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Button(
                        onClick = onFulfill,
                        enabled = !isProcessing,
                        colors = ButtonDefaults.buttonColors(containerColor = StoreGreenProfit),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .testTag("btn_fulfill_order_${order.orderNumber}")
                    ) {
                        Icon(Icons.Default.DoneAll, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isBn) "সম্পন্ন ও স্টক কর্তন (Fulfill)" else "Fulfill & Complete Order",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                OrderStatus.CONFIRMED -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onCancelClick,
                            enabled = !isProcessing,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFDC2626)),
                            border = BorderStroke(1.dp, Color(0xFFDC2626).copy(alpha = 0.5f)),
                            modifier = Modifier
                                .weight(0.9f)
                                .height(40.dp)
                        ) {
                            Text(if (isBn) "বাতিল" else "Cancel", fontSize = 12.sp)
                        }

                        val isDeliv = order.fulfillmentType == FulfillmentType.DELIVERY
                        Button(
                            onClick = onDispatch,
                            enabled = !isProcessing,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                            modifier = Modifier
                                .weight(1.4f)
                                .height(40.dp)
                        ) {
                            Icon(
                                imageVector = if (isDeliv) Icons.Default.LocalShipping else Icons.Default.DoneAll,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isDeliv) {
                                    if (isBn) "ডেলিভারিতে পাঠান" else "Dispatch for Delivery"
                                } else {
                                    if (isBn) "পিকআপের জন্য তৈরি" else "Mark Ready for Pickup"
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Button(
                        onClick = onFulfill,
                        enabled = !isProcessing,
                        colors = ButtonDefaults.buttonColors(containerColor = StoreGreenProfit),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .testTag("btn_fulfill_order_${order.orderNumber}")
                    ) {
                        Icon(Icons.Default.DoneAll, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isBn) "সম্পন্ন ও স্টক কর্তন (Fulfill)" else "Fulfill & Complete Order",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                OrderStatus.READY, OrderStatus.OUT_FOR_DELIVERY -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onCancelClick,
                            enabled = !isProcessing,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFDC2626)),
                            border = BorderStroke(1.dp, Color(0xFFDC2626).copy(alpha = 0.5f)),
                            modifier = Modifier
                                .weight(0.8f)
                                .height(42.dp)
                        ) {
                            Text(if (isBn) "বাতিল" else "Cancel", fontSize = 12.sp)
                        }

                        Button(
                            onClick = onFulfill,
                            enabled = !isProcessing,
                            colors = ButtonDefaults.buttonColors(containerColor = StoreGreenProfit),
                            modifier = Modifier
                                .weight(1.5f)
                                .height(42.dp)
                                .testTag("btn_fulfill_order_${order.orderNumber}")
                        ) {
                            Icon(Icons.Default.DoneAll, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isBn) "সম্পন্ন ও স্টক কর্তন" else "Fulfill & Complete",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                OrderStatus.CANCELLED -> {
                    if (!order.cancelReason.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFFFEE2E2),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (isBn) "বাতিলের কারণ: ${order.cancelReason}" else "Cancellation reason: ${order.cancelReason}",
                                fontSize = 12.sp,
                                color = Color(0xFF991B1B),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                OrderStatus.COMPLETED -> {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFFECFDF5),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isBn) "অর্ডারটি সফলভাবে ডেলিভারি ও বিক্রয় হিসাবে নথিবদ্ধ করা হয়েছে"
                                else "Order fulfilled and recorded into Store Sales ledger",
                                fontSize = 12.sp,
                                color = Color(0xFF065F46)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: String, isBn: Boolean) {
    val (label, bg, fg) = when (status) {
        OrderStatus.PLACED -> Triple(
            if (isBn) "নতুন" else "Placed",
            Color(0xFFFEF3C7),
            Color(0xFFD97706)
        )
        OrderStatus.CONFIRMED -> Triple(
            if (isBn) "গৃহীত" else "Confirmed",
            Color(0xFFE0F2FE),
            Color(0xFF0284C7)
        )
        OrderStatus.READY -> Triple(
            if (isBn) "প্রস্তুত" else "Ready",
            Color(0xFFEDE9FE),
            Color(0xFF7C3AED)
        )
        OrderStatus.OUT_FOR_DELIVERY -> Triple(
            if (isBn) "ডেলিভারিতে" else "Out for Delivery",
            Color(0xFFCCFBF1),
            Color(0xFF0D9488)
        )
        OrderStatus.COMPLETED -> Triple(
            if (isBn) "সম্পন্ন" else "Completed",
            Color(0xFFD1FAE5),
            Color(0xFF059669)
        )
        OrderStatus.CANCELLED -> Triple(
            if (isBn) "বাতিল" else "Cancelled",
            Color(0xFFFEE2E2),
            Color(0xFFDC2626)
        )
        else -> Triple(status, Color(0xFFF3F4F6), Color(0xFF4B5563))
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bg
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = fg,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun PaymentStatusBadge(status: String, method: String, isBn: Boolean) {
    val (label, bg, fg, icon) = when (status) {
        OrderPaymentStatus.PAID -> Quadruple(
            if (isBn) "পরিশোধিত ($method)" else "PAID ($method)",
            Color(0xFFD1FAE5),
            Color(0xFF059669),
            Icons.Default.CheckCircle
        )
        OrderPaymentStatus.COD -> Quadruple(
            if (isBn) "ক্যাশ অন ডেলিভারি / পিকআপ" else "Cash on Delivery / Pickup",
            Color(0xFFFEF3C7),
            Color(0xFFD97706),
            Icons.Default.AttachMoney
        )
        else -> Quadruple(
            if (isBn) "পেমেন্ট বাকি (Pending)" else "Payment Pending",
            Color(0xFFF3F4F6),
            Color(0xFF6B7280),
            Icons.Default.HourglassEmpty
        )
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bg
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = fg, modifier = Modifier.size(13.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = fg
            )
        }
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

@Composable
private fun CancelOrderDialog(
    order: Order,
    isBn: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var selectedReason by remember { mutableStateOf("Out of stock") }
    var customReason by remember { mutableStateOf("") }

    val presetReasons = listOf(
        "Out of stock" to if (isBn) "মজুদ নেই (Out of stock)" else "Out of stock",
        "Shop closed" to if (isBn) "দোকান বন্ধ (Shop closed)" else "Shop closed",
        "Too far" to if (isBn) "অনেক দূরে (Too far for delivery)" else "Too far for delivery",
        "Invalid address" to if (isBn) "ভুল ঠিকানা বা যোগাযোগহীন" else "Invalid address or unreachable",
        "Other" to if (isBn) "অন্যান্য কারণ" else "Other reason"
    )

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
            ) {
                Text(
                    text = if (isBn) "অর্ডার #${order.orderNumber} বাতিল করুন" else "Cancel Order #${order.orderNumber}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color(0xFFDC2626)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isBn) "অনুগ্রহ করে বাতিলের সুনির্দিষ্ট কারণ নির্বাচন করুন:"
                    else "Please select a cancellation reason:",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                presetReasons.forEach { (key, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedReason = key }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedReason == key,
                            onClick = { selectedReason = key },
                            colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFDC2626))
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = label, fontSize = 13.sp, color = TextDark)
                    }
                }

                if (selectedReason == "Other") {
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = customReason,
                        onValueChange = { customReason = it },
                        placeholder = { Text(if (isBn) "কারণ লিখুন..." else "Enter reason...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(if (isBn) "ফিরে যান" else "Dismiss")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val finalReason = if (selectedReason == "Other" && customReason.isNotBlank()) {
                                customReason.trim()
                            } else {
                                selectedReason
                            }
                            onConfirm(finalReason)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                    ) {
                        Text(if (isBn) "বাতিল নিশ্চিত করুন" else "Confirm Cancel", color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun InsufficientStockDialog(
    items: List<InsufficientStockItem>,
    isBn: Boolean,
    onDismiss: () -> Unit,
    onProceedAnyway: (() -> Unit)? = null
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color(0xFFDC2626),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isBn) "মজুদ স্বল্পতা সতর্কবার্তা" else "Insufficient Stock Alert",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = Color(0xFFDC2626)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isBn) "নিম্নলিখিত পণ্যগুলোর লাইভ মজুদ অর্ডারের পরিমাণের চেয়ে কম থাকায় অর্ডারটি নিশ্চিত বা সম্পন্ন করা যাচ্ছে না:"
                    else "The following items do not have sufficient live stock to fulfill or accept this order:",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = NeutralBackground,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        items.forEach { item ->
                            val name = if (isBn && item.productNameBn.isNotBlank()) item.productNameBn else item.productNameEn
                            val availFmt = if (item.availableStock % 1.0 == 0.0) item.availableStock.toInt().toString() else "%.2f".format(item.availableStock)
                            val reqFmt = if (item.requestedQuantity % 1.0 == 0.0) item.requestedQuantity.toInt().toString() else "%.2f".format(item.requestedQuantity)

                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text(
                                    text = name,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = TextDark
                                )
                                Text(
                                    text = if (isBn) "প্রয়োজন: $reqFmt ${item.unitType} • মজুদ আছে: $availFmt ${item.unitType}"
                                    else "Requested: $reqFmt ${item.unitType} • Available: $availFmt ${item.unitType}",
                                    fontSize = 12.sp,
                                    color = Color(0xFFDC2626)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = if (isBn) "পরামর্শ: গ্রাহকের সাথে ফোনে বা WhatsApp-এ যোগাযোগ করে অর্ডার সংশোধন করুন অথবা মজুদ ছাড়াই গ্রহণ করুন।"
                    else "Advice: Call or WhatsApp customer to adjust quantities, or accept anyway to allow negative stock.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(if (isBn) "বাতিল" else "Dismiss")
                    }

                    if (onProceedAnyway != null) {
                        Button(
                            onClick = onProceedAnyway,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isBn) "মজুদ ছাড়াই গ্রহণ করুন" else "Accept Anyway (Override)",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Button(
                            onClick = onDismiss,
                            colors = ButtonDefaults.buttonColors(containerColor = StorePrimary),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(if (isBn) "বুঝেছি" else "Understood", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

private fun filterOrder(order: Order, query: String): Boolean {
    if (query.isBlank()) return true
    val q = query.trim().lowercase()
    return order.orderNumber.lowercase().contains(q) ||
            order.customerName.lowercase().contains(q) ||
            order.customerPhone.contains(q) ||
            (order.paymentReference?.lowercase()?.contains(q) == true)
}

@Composable
private fun ScannedOrderDispatchDialog(
    order: Order,
    viewModel: StoreViewModel,
    isBn: Boolean,
    isProcessing: Boolean,
    onDismiss: () -> Unit,
    onPrintSlip: () -> Unit,
    onAccept: () -> Unit,
    onDispatch: () -> Unit,
    onFulfill: (paymentMethod: String, creditCustomer: Customer?) -> Unit
) {
    val customers by viewModel.allCustomers.collectAsState()
    val context = LocalContext.current

    // Determine initial payment method from order (CASH, UPI, CREDIT)
    val defaultMethod = remember(order.id) {
        val pm = order.paymentMethod.uppercase().trim()
        when {
            pm.contains("CREDIT") || pm.contains("KHATA") || pm.contains("BAKI") -> "CREDIT"
            pm.contains("UPI") || pm.contains("ONLINE") -> "UPI"
            else -> "CASH"
        }
    }
    var selectedPaymentMethod by remember(order.id) { mutableStateOf(defaultMethod) }
    var selectedCreditCustomer by remember(order.id) { mutableStateOf<Customer?>(null) }
    var staffConfirmedUnpaidUpi by remember(order.id) { mutableStateOf(false) }
    var isVerifyingUpiPayment by remember { mutableStateOf(false) }
    var upiErrorMessage by remember { mutableStateOf<String?>(null) }

    var showCustomerPickerModal by remember { mutableStateOf(false) }
    var showAddCustomerModal by remember { mutableStateOf(false) }
    var showConfirmUtrAndFulfillDialog by remember { mutableStateOf(false) }
    var showUnconfirmedUpiPromptDialog by remember { mutableStateOf(false) }

    // Auto-search and link customer if phone matches
    LaunchedEffect(selectedPaymentMethod, order.customerPhone, customers) {
        if (selectedPaymentMethod == "CREDIT" && selectedCreditCustomer == null && order.customerPhone.isNotBlank()) {
            val cleanPhone = order.customerPhone.trim().filter { it.isDigit() }
            val matched = customers.find { cust ->
                val custClean = cust.phone.trim().filter { it.isDigit() }
                custClean.isNotBlank() && (
                    custClean == cleanPhone ||
                    (cleanPhone.length >= 10 && custClean.endsWith(cleanPhone.takeLast(10))) ||
                    (custClean.length >= 10 && cleanPhone.endsWith(custClean.takeLast(10)))
                )
            }
            if (matched != null) {
                selectedCreditCustomer = matched
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.White,
            shadowElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                // Header: Scan icon, order number & fulfillment type badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = StorePrimary.copy(alpha = 0.1f),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.QrCodeScanner,
                                    contentDescription = null,
                                    tint = StorePrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = if (isBn) "অর্ডার #${order.orderNumber}" else "Order #${order.orderNumber}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = TextDark
                            )
                            val fulfillmentLabel = if (order.fulfillmentType == FulfillmentType.DELIVERY) {
                                if (isBn) "হোম ডেলিভারি" else "Home Delivery"
                            } else {
                                if (isBn) "দোকান পিকআপ" else "Store Pickup"
                            }
                            val fulfillmentWithSlot = if (order.fulfillmentType == FulfillmentType.DELIVERY && !order.deliverySlot.isNullOrBlank()) {
                                "$fulfillmentLabel • ⏰ ${order.deliverySlot}"
                            } else {
                                fulfillmentLabel
                            }
                            Text(
                                text = fulfillmentWithSlot,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    StatusBadge(status = order.status, isBn = isBn)
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = NeutralBorderDivider)

                // Customer Information
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = order.customerName,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            color = TextDark
                        )
                        Text(
                            text = order.customerPhone,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val addrText = order.deliveryAddress?.let { addr ->
                        listOfNotNull(addr.streetAddress, addr.landmark, addr.pinCode).filter { it.isNotBlank() }.joinToString(", ")
                    } ?: ""
                    if (addrText.isNotBlank()) {
                        Text(
                            text = addrText,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .padding(start = 12.dp)
                        )
                    }
                }

                // Delivery address card with Google Maps link if pinned
                if (order.fulfillmentType == FulfillmentType.DELIVERY && order.deliveryAddress != null) {
                    val addr = order.deliveryAddress
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = NeutralBackground,
                        border = BorderStroke(1.dp, NeutralBorderDivider),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(
                                    imageVector = Icons.Default.LocationOn,
                                    contentDescription = null,
                                    tint = Color(0xFFDC2626),
                                    modifier = Modifier.size(16.dp).padding(top = 1.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                val fullAddr = buildString {
                                    append(addr.streetAddress)
                                    if (!addr.landmark.isNullOrBlank()) append(" (Near: ${addr.landmark})")
                                    if (!addr.pinCode.isNullOrBlank()) append(" - PIN: ${addr.pinCode}")
                                }
                                Text(
                                    text = fullAddr,
                                    fontSize = 12.sp,
                                    color = TextDark,
                                    lineHeight = 16.sp
                                )
                            }
                            if (addr.latitude != null && addr.longitude != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        launchGoogleMapsDirections(context, addr.latitude, addr.longitude)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier.fillMaxWidth().height(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Directions,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isBn) "গুগল ম্যাপে দিকনির্দেশনা দেখুন" else "Open in Google Maps (Directions)",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Item breakdown summary
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = NeutralBackground,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = if (isBn) "পণ্য তালিকা (${order.itemsCount}):" else "Items (${order.itemsCount}):",
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        order.items.take(3).forEach { item ->
                            val qtyFmt = if (item.quantity % 1.0 == 0.0) item.quantity.toInt().toString() else "%.2f".format(item.quantity)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "• ${item.name} ($qtyFmt ${item.unit})",
                                    fontSize = 12.sp,
                                    color = TextDark,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "₹%.2f".format(item.subtotal),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextDark
                                )
                            }
                        }
                        if (order.items.size > 3) {
                            Text(
                                text = if (isBn) "+ আরও ${order.items.size - 3} টি পণ্য..." else "+ ${order.items.size - 3} more items...",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp), color = NeutralBorderDivider)

                        if (order.fulfillmentType == FulfillmentType.DELIVERY) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (isBn) "পণ্য মূল্য (Subtotal):" else "Items Subtotal:",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "₹%.2f".format(order.subtotal),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextDark
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (isBn) "ডেলিভারি চার্জ:" else "Delivery Fee:",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = if (order.deliveryFee > 0.0) "₹%.2f".format(order.deliveryFee)
                                    else (if (isBn) "বিনামূল্যে (FREE)" else "FREE"),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (order.deliveryFee > 0.0) TextDark else StoreGreenProfit
                                )
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = NeutralBorderDivider)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (isBn) "মোট টাকা:" else "Total Amount:",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = TextDark
                            )
                            Text(
                                text = "₹%.2f".format(order.totalAmount),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = StoreGreenProfit
                            )
                        }
                    }
                }

                // Staff Attribution
                val hasModalConfirmedBy = !order.confirmedByStaffName.isNullOrBlank()
                val hasModalDispatchedBy = !order.dispatchedByStaffName.isNullOrBlank()
                if (hasModalConfirmedBy || hasModalDispatchedBy) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (hasModalConfirmedBy) {
                            Text(
                                text = if (isBn) "নিশ্চিত করেছেন: ${order.confirmedByStaffName}" else "Confirmed by: ${order.confirmedByStaffName}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (hasModalDispatchedBy) {
                            Text(
                                text = if (isBn) "ডেসপ্যাচ করেছেন: ${order.dispatchedByStaffName}" else "Dispatched by: ${order.dispatchedByStaffName}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Payment Method Decision Step (Shown before fulfillment)
                if (order.status in listOf(OrderStatus.PLACED, OrderStatus.CONFIRMED, OrderStatus.READY, OrderStatus.OUT_FOR_DELIVERY)) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = SurfaceWarm,
                        border = BorderStroke(1.dp, NeutralBorderDivider),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Payment,
                                        contentDescription = null,
                                        tint = StorePrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isBn) "পেমেন্ট পদ্ধতি নির্ধারণ" else "Payment Method Decision",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = TextDark
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = StorePrimary.copy(alpha = 0.08f)
                                ) {
                                    val checkoutLabel = if (order.paymentMethod.isBlank()) {
                                        if (isBn) "অনির্ধারিত" else "Unspecified"
                                    } else order.paymentMethod
                                    Text(
                                        text = if (isBn) "চেকআউট: $checkoutLabel" else "Checkout: $checkoutLabel",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = StorePrimary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // 3-Option Segmented Selector: CASH, UPI, CREDIT
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val options = listOf(
                                    Triple("CASH", if (isBn) "নগদ (Cash)" else "Cash", Icons.Default.Payments),
                                    Triple("UPI", if (isBn) "UPI" else "UPI", Icons.Default.QrCode),
                                    Triple("CREDIT", if (isBn) "বাকী (Khata)" else "Credit", Icons.Default.MenuBook)
                                )

                                options.forEach { (methodKey, label, icon) ->
                                    val isSelected = selectedPaymentMethod == methodKey
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isSelected) StorePrimary else Color.White,
                                        border = BorderStroke(1.dp, if (isSelected) StorePrimary else NeutralBorderDivider),
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { selectedPaymentMethod = methodKey }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                                            horizontalArrangement = Arrangement.Center,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = icon,
                                                contentDescription = null,
                                                tint = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(15.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = label,
                                                fontSize = 11.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isSelected) Color.White else TextDark,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Method-specific details and workflows
                            when (selectedPaymentMethod) {
                                "CASH" -> {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0xFFECFDF5),
                                        border = BorderStroke(1.dp, Color(0xFFA7F3D0)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StoreGreenProfit, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = if (isBn) "হস্তান্তরের সময় নগদ ₹%.2f গ্রহণ করুন".format(order.totalAmount)
                                                else "Collect Cash at hand-off: ₹%.2f".format(order.totalAmount),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = Color(0xFF065F46)
                                            )
                                        }
                                    }
                                }
                                "UPI" -> {
                                    val isAlreadyPaid = order.paymentStatus == OrderPaymentStatus.PAID
                                    val hasUtr = !order.paymentReference.isNullOrBlank()
                                    val hasScreenshot = !order.paymentScreenshotData.isNullOrBlank()
                                    val hasPaymentProof = hasUtr || hasScreenshot

                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (isAlreadyPaid) {
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = Color(0xFFECFDF5),
                                                border = BorderStroke(1.dp, Color(0xFFA7F3D0)),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Column(modifier = Modifier.padding(10.dp)) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Icon(Icons.Default.Verified, contentDescription = null, tint = StoreGreenProfit, modifier = Modifier.size(18.dp))
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Column {
                                                            Text(
                                                                text = if (isBn) "✓ UPI পেমেন্ট নিশ্চিত (PAID)" else "✓ UPI Payment Verified (PAID)",
                                                                fontSize = 12.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = Color(0xFF065F46)
                                                            )
                                                            if (hasUtr) {
                                                                Text(
                                                                    text = "UTR: ${order.paymentReference}",
                                                                    fontSize = 11.sp,
                                                                    color = Color(0xFF047857)
                                                                )
                                                            }
                                                        }
                                                    }
                                                    if (hasScreenshot) {
                                                        Spacer(modifier = Modifier.height(6.dp))
                                                        PaymentScreenshotViewer(
                                                            screenshotData = order.paymentScreenshotData,
                                                            cardHeight = 120.dp
                                                        )
                                                    }
                                                }
                                            }
                                        } else if (hasPaymentProof) {
                                            // Pending with payment proof (UTR and/or screenshot) submitted by customer
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = Color(0xFFEFF6FF),
                                                border = BorderStroke(1.dp, Color(0xFFBFDBFE)),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Column(modifier = Modifier.padding(10.dp)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFF2563EB), modifier = Modifier.size(16.dp))
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text(
                                                            text = if (isBn) {
                                                                if (hasUtr && hasScreenshot) "গ্রাহক UTR ও স্ক্রিনশট জমা দিয়েছেন:"
                                                                else if (hasUtr) "গ্রাহক UTR জমা দিয়েছেন:"
                                                                else "গ্রাহক পেমেন্ট স্ক্রিনশট জমা দিয়েছেন:"
                                                            } else {
                                                                if (hasUtr && hasScreenshot) "Customer submitted UTR & screenshot:"
                                                                else if (hasUtr) "Customer submitted UTR:"
                                                                else "Customer submitted payment screenshot:"
                                                            },
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = Color(0xFF1E40AF)
                                                        )
                                                    }
                                                    if (hasUtr) {
                                                        Text(
                                                            text = order.paymentReference ?: "",
                                                            fontSize = 13.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color(0xFF1E3A8A),
                                                            modifier = Modifier.padding(vertical = 4.dp)
                                                        )
                                                    }
                                                    if (hasScreenshot) {
                                                        Spacer(modifier = Modifier.height(4.dp))
                                                        PaymentScreenshotViewer(
                                                            screenshotData = order.paymentScreenshotData,
                                                            cardHeight = 140.dp
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = if (isBn) "স্ট্যাটাস: পেমেন্ট যাচাই অপেক্ষমান" else "Status: Pending Verification",
                                                        fontSize = 11.sp,
                                                        color = Color(0xFF3B82F6)
                                                    )
                                                    Spacer(modifier = Modifier.height(6.dp))
                                                    Button(
                                                        onClick = {
                                                            isVerifyingUpiPayment = true
                                                            viewModel.markOnlineOrderPaid(
                                                                order = order,
                                                                onSuccess = {
                                                                    isVerifyingUpiPayment = false
                                                                },
                                                                onError = { err ->
                                                                    isVerifyingUpiPayment = false
                                                                    upiErrorMessage = err
                                                                }
                                                            )
                                                        },
                                                        enabled = !isVerifyingUpiPayment,
                                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                                                        modifier = Modifier.fillMaxWidth().height(36.dp)
                                                    ) {
                                                        Text(
                                                            text = if (isBn) "যাচাই করুন এবং পেইড মার্ক করুন" else "Verify & Mark as Paid",
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                }
                                            }
                                        } else {
                                            // Pending without UTR
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = Color(0xFFFFFBEB),
                                                border = BorderStroke(1.dp, Color(0xFFFDE68A)),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Column(modifier = Modifier.padding(10.dp)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(Icons.Default.WarningAmber, contentDescription = null, tint = Color(0xFFD97706), modifier = Modifier.size(18.dp))
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text(
                                                            text = if (isBn) "গ্রাহক এখনো পেমেন্ট নিশ্চিত করেননি" else "Customer has not confirmed payment yet",
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color(0xFF92400E)
                                                        )
                                                    }
                                                    Text(
                                                        text = if (isBn) "কোনো পেমেন্ট রেফারেন্স (UTR) নেই। হস্তান্তরের আগে নিশ্চিত করুন।"
                                                        else "No payment reference (UTR) submitted. Staff confirmation required before fulfilling.",
                                                        fontSize = 11.sp,
                                                        color = Color(0xFFB45309),
                                                        modifier = Modifier.padding(vertical = 4.dp)
                                                    )
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier.clickable { staffConfirmedUnpaidUpi = !staffConfirmedUnpaidUpi }
                                                    ) {
                                                        Checkbox(
                                                            checked = staffConfirmedUnpaidUpi,
                                                            onCheckedChange = { staffConfirmedUnpaidUpi = it },
                                                            modifier = Modifier.size(24.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Text(
                                                            text = if (isBn) "কর্মচারী নিশ্চিত করছেন: দোকানে ₹%.2f UPI পেমেন্ট পেয়েছি".format(order.totalAmount)
                                                            else "Staff confirmed: Customer paid ₹%.2f via in-store UPI".format(order.totalAmount),
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Medium,
                                                            color = Color(0xFF92400E)
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        if (upiErrorMessage != null) {
                                            Text(
                                                text = upiErrorMessage ?: "",
                                                fontSize = 11.sp,
                                                color = StoreRedAlert,
                                                modifier = Modifier.padding(top = 2.dp)
                                            )
                                        }
                                    }
                                }
                                "CREDIT" -> {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (selectedCreditCustomer != null) {
                                            val cust = selectedCreditCustomer!!
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = Color.White,
                                                border = BorderStroke(1.dp, StorePrimary.copy(alpha = 0.3f)),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Column(modifier = Modifier.padding(10.dp)) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            modifier = Modifier.weight(1f)
                                                        ) {
                                                            Icon(Icons.Default.AccountCircle, contentDescription = null, tint = StorePrimary, modifier = Modifier.size(20.dp))
                                                            Spacer(modifier = Modifier.width(6.dp))
                                                            Column {
                                                                Text(cust.name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                                                if (cust.phone.isNotBlank()) {
                                                                    Text(cust.phone, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                                }
                                                            }
                                                        }
                                                        TextButton(
                                                            onClick = { showCustomerPickerModal = true },
                                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                                        ) {
                                                            Text(if (isBn) "পরিবর্তন" else "Change", fontSize = 11.sp)
                                                        }
                                                    }

                                                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp), color = NeutralBorderDivider)

                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Column {
                                                            Text(
                                                                text = "Due: ₹%.2f".format(cust.balance),
                                                                fontSize = 11.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = if (cust.balance > 0) StoreRedAlert else StoreGreenProfit
                                                            )
                                                            if (cust.hasCreditLimit()) {
                                                                Text(
                                                                    text = "Limit: ₹%.0f".format(cust.creditLimit ?: 0.0),
                                                                    fontSize = 10.sp,
                                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                                )
                                                            }
                                                        }
                                                        Column(horizontalAlignment = Alignment.End) {
                                                            Text(
                                                                text = "+ New Due: ₹%.2f".format(order.totalAmount),
                                                                fontSize = 11.sp,
                                                                fontWeight = FontWeight.SemiBold,
                                                                color = Color(0xFFD97706)
                                                            )
                                                            if (cust.hasCreditLimit()) {
                                                                val newTotalDue = cust.balance + order.totalAmount
                                                                val limit = cust.creditLimit ?: 0.0
                                                                if (newTotalDue > limit) {
                                                                    Text(
                                                                        text = "⚠️ Will Exceed Limit",
                                                                        fontSize = 10.sp,
                                                                        fontWeight = FontWeight.Bold,
                                                                        color = StoreRedAlert
                                                                    )
                                                                } else {
                                                                    Text(
                                                                        text = "Avail: ₹%.2f".format(limit - newTotalDue),
                                                                        fontSize = 10.sp,
                                                                        color = StoreGreenProfit
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        } else {
                                            // No customer matched yet
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = Color(0xFFFFFBEB),
                                                border = BorderStroke(1.dp, Color(0xFFFDE68A)),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Column(modifier = Modifier.padding(10.dp)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(Icons.Default.PersonSearch, contentDescription = null, tint = Color(0xFFD97706), modifier = Modifier.size(18.dp))
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text(
                                                            text = if (isBn) "খাতায় কোনো গ্রাহক যুক্ত নেই" else "No Khata customer linked",
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color(0xFF92400E)
                                                        )
                                                    }
                                                    Text(
                                                        text = if (isBn) "নম্বর: ${order.customerPhone}। বাকীতে অর্ডার দিতে গ্রাহক তৈরি বা নির্বাচন করুন।"
                                                        else "Phone: ${order.customerPhone}. Search or create a Khata customer to fulfill on credit.",
                                                        fontSize = 11.sp,
                                                        color = Color(0xFFB45309),
                                                        modifier = Modifier.padding(vertical = 4.dp)
                                                    )
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                    ) {
                                                        Button(
                                                            onClick = { showAddCustomerModal = true },
                                                            colors = ButtonDefaults.buttonColors(containerColor = StorePrimary),
                                                            modifier = Modifier.weight(1f).height(34.dp)
                                                        ) {
                                                            Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(14.dp))
                                                            Spacer(modifier = Modifier.width(4.dp))
                                                            Text(if (isBn) "+ নতুন গ্রাহক" else "+ Create Customer", fontSize = 11.sp)
                                                        }
                                                        OutlinedButton(
                                                            onClick = { showCustomerPickerModal = true },
                                                            modifier = Modifier.weight(1f).height(34.dp)
                                                        ) {
                                                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(14.dp))
                                                            Spacer(modifier = Modifier.width(4.dp))
                                                            Text(if (isBn) "গ্রাহক খুঁজুন" else "Search Customer", fontSize = 11.sp)
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

                Spacer(modifier = Modifier.height(14.dp))

                // Action buttons based on order status
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val executeFulfillment: () -> Unit = {
                        when (selectedPaymentMethod) {
                            "CREDIT" -> {
                                if (selectedCreditCustomer == null) {
                                    showCustomerPickerModal = true
                                } else {
                                    onFulfill("CREDIT", selectedCreditCustomer)
                                }
                            }
                            "UPI" -> {
                                if (order.paymentStatus == OrderPaymentStatus.PAID) {
                                    onFulfill("UPI", null)
                                } else if (!order.paymentReference.isNullOrBlank() || !order.paymentScreenshotData.isNullOrBlank()) {
                                    showConfirmUtrAndFulfillDialog = true
                                } else if (!staffConfirmedUnpaidUpi) {
                                    showUnconfirmedUpiPromptDialog = true
                                } else {
                                    viewModel.markOnlineOrderPaid(
                                        order = order,
                                        onSuccess = { onFulfill("UPI", null) },
                                        onError = { onFulfill("UPI", null) }
                                    )
                                }
                            }
                            "CASH" -> {
                                onFulfill("CASH", null)
                            }
                            else -> {
                                onFulfill(selectedPaymentMethod, null)
                            }
                        }
                    }

                    when (order.status) {
                        OrderStatus.PLACED -> {
                            Button(
                                onClick = executeFulfillment,
                                enabled = !isProcessing,
                                colors = ButtonDefaults.buttonColors(containerColor = StoreGreenProfit),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(42.dp)
                            ) {
                                Icon(Icons.Default.DoneAll, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (isBn) "সম্পন্ন ও স্টক কর্তন (Fulfill)" else "Fulfill & Complete Order", fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = onAccept,
                                enabled = !isProcessing,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF0284C7)),
                                border = BorderStroke(1.dp, Color(0xFF0284C7)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (isBn) "অর্ডার গ্রহণ করুন (Accept Only)" else "Accept Order (Move to Confirmed)", fontWeight = FontWeight.Bold)
                            }
                        }
                        OrderStatus.CONFIRMED -> {
                            Button(
                                onClick = executeFulfillment,
                                enabled = !isProcessing,
                                colors = ButtonDefaults.buttonColors(containerColor = StoreGreenProfit),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(42.dp)
                            ) {
                                Icon(Icons.Default.DoneAll, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (isBn) "সম্পন্ন ও স্টক কর্তন (Fulfill)" else "Fulfill & Complete Order", fontWeight = FontWeight.Bold)
                            }

                            val isDeliv = order.fulfillmentType == FulfillmentType.DELIVERY
                            OutlinedButton(
                                onClick = onDispatch,
                                enabled = !isProcessing,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF0284C7)),
                                border = BorderStroke(1.dp, Color(0xFF0284C7)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                            ) {
                                Icon(
                                    imageVector = if (isDeliv) Icons.Default.LocalShipping else Icons.Default.DoneAll,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isDeliv) (if (isBn) "ডেলিভারিতে পাঠান (Dispatch)" else "Dispatch for Delivery")
                                    else (if (isBn) "পিকআপের জন্য প্রস্তুত" else "Mark Ready for Pickup"),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        OrderStatus.READY, OrderStatus.OUT_FOR_DELIVERY -> {
                            Button(
                                onClick = executeFulfillment,
                                enabled = !isProcessing,
                                colors = ButtonDefaults.buttonColors(containerColor = StoreGreenProfit),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(42.dp)
                            ) {
                                Icon(Icons.Default.DoneAll, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (isBn) "সম্পন্ন ও স্টক কর্তন (Fulfill)" else "Fulfill & Complete Order", fontWeight = FontWeight.Bold)
                            }
                        }
                        OrderStatus.COMPLETED -> {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFFECFDF5),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (isBn) "এই অর্ডারটি ইতোমধ্যে সম্পন্ন হয়েছে" else "This order has already been completed",
                                    fontSize = 12.sp,
                                    color = Color(0xFF065F46),
                                    modifier = Modifier.padding(10.dp),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                        OrderStatus.CANCELLED -> {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFFFEF2F2),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (isBn) "এই অর্ডারটি বাতিল করা হয়েছে" else "This order was cancelled",
                                    fontSize = 12.sp,
                                    color = Color(0xFF991B1B),
                                    modifier = Modifier.padding(10.dp),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    // Print Thermal Packing Slip
                    OutlinedButton(
                        onClick = onPrintSlip,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = StorePrimary),
                        border = BorderStroke(1.dp, StorePrimary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                    ) {
                        Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isBn) "প্যাকিং স্লিপ প্রিন্ট করুন" else "Print Packing Slip (Thermal)")
                    }

                    // Dismiss Button
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isBn) "বন্ধ করুন" else "Close", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    // Modal Customer Picker for Credit
    if (showCustomerPickerModal) {
        CustomerPickerDialog(
            customers = customers,
            selectedCustomerId = selectedCreditCustomer?.id,
            initialSearchQuery = order.customerPhone,
            onCustomerSelected = { cust ->
                selectedCreditCustomer = cust
                showCustomerPickerModal = false
            },
            onAddNewCustomerClick = {
                showCustomerPickerModal = false
                showAddCustomerModal = true
            },
            onDismiss = { showCustomerPickerModal = false }
        )
    }

    // Modal Add Customer for Credit
    if (showAddCustomerModal) {
        AddCustomerDialog(
            initialName = order.customerName,
            initialPhone = order.customerPhone,
            onDismiss = { showAddCustomerModal = false },
            onSave = { newCust ->
                viewModel.saveCustomer(newCust, initialDue = newCust.balance)
                selectedCreditCustomer = newCust
                showAddCustomerModal = false
            }
        )
    }

    // Confirm UTR dialog when customer submitted UTR and order is PENDING
    if (showConfirmUtrAndFulfillDialog) {
        val hasConfirmUtr = !order.paymentReference.isNullOrBlank()
        val hasConfirmScreenshot = !order.paymentScreenshotData.isNullOrBlank()
        AlertDialog(
            onDismissRequest = { showConfirmUtrAndFulfillDialog = false },
            title = {
                Text(
                    text = if (isBn) "UPI পেমেন্ট নিশ্চিত করুন" else "Confirm UPI Payment",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    if (hasConfirmUtr) {
                        Text(
                            text = if (isBn) "গ্রাহক UTR রেফারেন্স জমা দিয়েছেন:" else "Customer submitted UTR reference:",
                            fontSize = 13.sp
                        )
                        Text(
                            text = order.paymentReference ?: "",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = Color(0xFF1E40AF),
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                    if (hasConfirmScreenshot) {
                        if (hasConfirmUtr) Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isBn) "গ্রাহকের আপলোডকৃত পেমেন্ট স্ক্রিনশট:" else "Customer uploaded payment screenshot:",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        PaymentScreenshotViewer(
                            screenshotData = order.paymentScreenshotData,
                            cardHeight = 120.dp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    Text(
                        text = if (isBn) "মোট পরিমাণ: ₹%.2f। আপনি কি পেমেন্ট গ্রহণ নিশ্চিত করে অর্ডার সম্পন্ন করতে চান?".format(order.totalAmount)
                        else "Amount: ₹%.2f. Do you confirm receipt of payment and want to fulfill this order?".format(order.totalAmount),
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmUtrAndFulfillDialog = false
                        viewModel.markOnlineOrderPaid(
                            order = order,
                            onSuccess = { onFulfill("UPI", null) },
                            onError = { onFulfill("UPI", null) }
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StoreGreenProfit)
                ) {
                    Text(if (isBn) "পেমেন্ট নিশ্চিত ও সম্পন্ন" else "Confirm & Fulfill")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmUtrAndFulfillDialog = false }) {
                    Text(if (isBn) "বাতিল" else "Cancel")
                }
            }
        )
    }

    // Prompt staff to confirm unpaid UPI without UTR
    if (showUnconfirmedUpiPromptDialog) {
        AlertDialog(
            onDismissRequest = { showUnconfirmedUpiPromptDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.WarningAmber, contentDescription = null, tint = Color(0xFFD97706))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isBn) "অযাচাইকৃত UPI পেমেন্ট" else "Unverified UPI Payment",
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Text(
                    text = if (isBn) "গ্রাহক অনলাইনে পেমেন্ট রেফারেন্স (UTR) দেননি। দোকানে সরাসরি ₹%.2f UPI পেমেন্ট পেয়েছেন কি? নিশ্চিত করলে অর্ডার সম্পন্ন হবে।".format(order.totalAmount)
                    else "Customer has not submitted a payment reference (UTR). Did the customer pay ₹%.2f via in-store UPI? Confirming will mark payment received and fulfill the order.".format(order.totalAmount),
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUnconfirmedUpiPromptDialog = false
                        staffConfirmedUnpaidUpi = true
                        viewModel.markOnlineOrderPaid(
                            order = order,
                            onSuccess = { onFulfill("UPI", null) },
                            onError = { onFulfill("UPI", null) }
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706))
                ) {
                    Text(if (isBn) "হ্যাঁ, পেমেন্ট পেয়েছি ও সম্পন্ন" else "Yes, Received & Fulfill")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnconfirmedUpiPromptDialog = false }) {
                    Text(if (isBn) "বাতিল" else "Cancel")
                }
            }
        )
    }
}

/**
 * Launch Google Maps navigation intent with directions to the pinned coordinates.
 * Falls back to web Google Maps directions if the Google Maps app is unavailable.
 */
fun launchGoogleMapsDirections(context: Context, latitude: Double, longitude: Double) {
    // 1. Try turn-by-turn navigation in Google Maps app
    val navUri = Uri.parse("google.navigation:q=$latitude,$longitude&mode=d")
    val mapIntent = Intent(Intent.ACTION_VIEW, navUri).apply {
        setPackage("com.google.android.apps.maps")
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    try {
        context.startActivity(mapIntent)
    } catch (e: Exception) {
        // 2. Fallback: Google Maps web directions URL
        val webUri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$latitude,$longitude")
        val webIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(webIntent)
        } catch (ex: Exception) {
            Toast.makeText(context, "Could not open map navigation", Toast.LENGTH_SHORT).show()
        }
    }
}
