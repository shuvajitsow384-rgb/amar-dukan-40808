package com.example.ui.screens.credit

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.utils.LedgerCalculator
import coil.compose.AsyncImage
import com.example.data.local.dao.SaleWithItems
import com.example.ui.theme.AnimationTokens
import com.example.data.local.entities.Customer
import com.example.data.local.entities.LedgerEntry
import com.example.data.local.entities.Supplier
import com.example.data.models.Order
import com.example.data.models.OrderStatus
import com.example.ui.components.PartyProfileAvatar
import com.example.ui.components.EnlargedPhotoDialog
import com.example.ui.components.ZoomablePaymentScreenshotDialog
import com.example.ui.components.KhataSmsReminderDialog
import com.example.ui.components.LedgerDateRangeFilterComponent
import com.example.ui.components.LedgerDatePreset
import com.example.ui.components.getPresetDateBounds
import com.example.ui.screens.suppliers.SupplierManagementView
import com.example.ui.screens.suppliers.cleanNotes
import com.example.ui.screens.suppliers.extractPhotoUri
import com.example.ui.screens.suppliers.saveBitmapToCache
import com.example.ui.theme.*
import com.example.utils.CustomerInterestBreakdown
import com.example.utils.KhataInterestCalculator
import com.example.utils.KhataInterestSettings
import com.example.utils.LanguageManager
import com.example.utils.NotificationHelper
import com.example.utils.SmsHelper
import com.example.utils.StoreInfoManager
import com.example.utils.ThemeManager
import com.example.utils.WhatsAppHelper
import com.example.viewmodel.StoreViewModel
import java.text.SimpleDateFormat
import java.util.*

data class CustomerCreditRiskInfo(
    val customer: Customer,
    val daysOverdue: Int,
    val lastPaymentDate: Long?,
    val lastCreditDate: Long?,
    val isCriticalOverdue: Boolean,
    val isModerateOverdue: Boolean,
    val isHighBalance: Boolean,
    val isOverCreditLimit: Boolean,
    val riskScore: Int
)

data class CustomerPurchaseMetrics(
    val lifetimeOrderCount: Int = 0,
    val inStoreOrderCount: Int = 0,
    val onlineOrderCount: Int = 0,
    val lifetimeSpend: Double = 0.0,
    val inStoreSpend: Double = 0.0,
    val onlineSpend: Double = 0.0,
    val lastPurchaseDate: Long? = null,
    val lastPurchaseChannel: String? = null // "ONLINE" or "IN_STORE"
)

fun normalizePhoneLast10(phone: String?): String {
    if (phone.isNullOrBlank()) return ""
    val digits = phone.replace("\\D".toRegex(), "")
    return if (digits.length >= 10) digits.takeLast(10) else digits
}

fun calculateCustomerPurchaseMetrics(
    customer: Customer,
    allSales: List<SaleWithItems>,
    allOnlineOrders: List<Order>
): CustomerPurchaseMetrics {
    val custPhoneNorm = normalizePhoneLast10(customer.phone)

    // In-store sales: where customerId matches and NOT marked as Online Order
    val inStoreSales = allSales.filter {
        it.sale.customerId == customer.id && it.sale.notes?.contains("Online Order", ignoreCase = true) != true
    }

    // Online orders matched by normalized phone
    val matchedOnlineOrders = if (custPhoneNorm.isNotBlank()) {
        allOnlineOrders.filter { order ->
            val orderPhoneNorm = normalizePhoneLast10(order.customerPhone)
            orderPhoneNorm.isNotBlank() && orderPhoneNorm == custPhoneNorm
        }
    } else {
        emptyList()
    }

    // Valid non-cancelled online orders
    val validOnlineOrders = matchedOnlineOrders.filter {
        it.status != OrderStatus.CANCELLED
    }

    // Also check if any sales for this customer were recorded with online order notes
    val onlineSalesForCustomer = allSales.filter {
        it.sale.customerId == customer.id && it.sale.notes?.contains("Online Order", ignoreCase = true) == true
    }

    val onlineOrderCount = maxOf(validOnlineOrders.size, onlineSalesForCustomer.size)
    val inStoreOrderCount = inStoreSales.size
    val lifetimeOrderCount = inStoreOrderCount + onlineOrderCount

    val inStoreSpend = inStoreSales.sumOf { it.sale.finalAmount }
    val onlineSpend = if (validOnlineOrders.isNotEmpty()) {
        validOnlineOrders.sumOf { it.totalAmount }
    } else {
        onlineSalesForCustomer.sumOf { it.sale.finalAmount }
    }
    val lifetimeSpend = inStoreSpend + onlineSpend

    val lastInStoreDate = inStoreSales.maxOfOrNull { it.sale.datetime }
    val lastOnlineDate = validOnlineOrders.maxOfOrNull { it.createdAt }
        ?: onlineSalesForCustomer.maxOfOrNull { it.sale.datetime }

    val lastPurchaseDate: Long?
    val lastPurchaseChannel: String?

    if (lastInStoreDate != null && lastOnlineDate != null) {
        if (lastOnlineDate >= lastInStoreDate) {
            lastPurchaseDate = lastOnlineDate
            lastPurchaseChannel = "ONLINE"
        } else {
            lastPurchaseDate = lastInStoreDate
            lastPurchaseChannel = "IN_STORE"
        }
    } else if (lastOnlineDate != null) {
        lastPurchaseDate = lastOnlineDate
        lastPurchaseChannel = "ONLINE"
    } else if (lastInStoreDate != null) {
        lastPurchaseDate = lastInStoreDate
        lastPurchaseChannel = "IN_STORE"
    } else {
        lastPurchaseDate = null
        lastPurchaseChannel = null
    }

    return CustomerPurchaseMetrics(
        lifetimeOrderCount = lifetimeOrderCount,
        inStoreOrderCount = inStoreOrderCount,
        onlineOrderCount = onlineOrderCount,
        lifetimeSpend = lifetimeSpend,
        inStoreSpend = inStoreSpend,
        onlineSpend = onlineSpend,
        lastPurchaseDate = lastPurchaseDate,
        lastPurchaseChannel = lastPurchaseChannel
    )
}

fun calculateCustomerCreditRisk(
    customer: Customer,
    allLedgerEntries: List<LedgerEntry>,
    now: Long = System.currentTimeMillis()
): CustomerCreditRiskInfo {
    val custEntries = allLedgerEntries.filter { 
        it.partyId == customer.id && (it.partyType.equals("CUSTOMER", ignoreCase = true) || it.partyType.isBlank()) 
    }
    val paymentEntries = custEntries.filter { entry ->
        val t = entry.type.uppercase()
        t in listOf("PAYMENT_RECEIVED", "PAYMENT", "RETURN_REFUND", "CREDIT_REDUCED", "REFUND")
    }
    val creditEntries = custEntries.filter { entry ->
        val t = entry.type.uppercase()
        t in listOf("SALE_CREDIT", "CREDIT_GIVEN", "CREDIT", "REPLACEMENT_DUE", "DUE", "OPENING_BALANCE", "INITIAL_DUE", "OPENING_DUE", "OPENING_CREDIT") ||
        (entry.note?.contains("credit", ignoreCase = true) == true || entry.note?.contains("due", ignoreCase = true) == true || entry.note?.contains("opening", ignoreCase = true) == true)
    }

    val lastPayment = paymentEntries.maxOfOrNull { it.datetime }
    val lastCredit = creditEntries.maxOfOrNull { it.datetime }
    val oldestCredit = creditEntries.minOfOrNull { it.datetime }

    val daysOverdue = if (customer.balance > 0.0) {
        // 1. Check if any active credit entry has an explicit due date that has elapsed
        val explicitOverdueDays = creditEntries
            .filter { it.dueDate != null && it.dueDate > 0L }
            .map { entry ->
                if (now > entry.dueDate!!) {
                    ((now - entry.dueDate) / (1000L * 60 * 60 * 24)).toInt().coerceAtLeast(1)
                } else {
                    0
                }
            }.maxOrNull()

        if (explicitOverdueDays != null && explicitOverdueDays > 0) {
            explicitOverdueDays
        } else if (lastPayment != null) {
            val daysSincePay = ((now - lastPayment) / (1000L * 60 * 60 * 24)).toInt().coerceAtLeast(0)
            if (lastCredit != null && lastCredit > lastPayment) {
                val daysSinceCredit = ((now - lastCredit) / (1000L * 60 * 60 * 24)).toInt().coerceAtLeast(0)
                daysSinceCredit
            } else {
                daysSincePay
            }
        } else if (oldestCredit != null) {
            ((now - oldestCredit) / (1000L * 60 * 60 * 24)).toInt().coerceAtLeast(0)
        } else {
            0
        }
    } else {
        0
    }

    val isCriticalOverdue = customer.balance > 0.0 && daysOverdue >= 30
    val isModerateOverdue = customer.balance > 0.0 && (daysOverdue in 15..29)
    val isOverLimit = customer.isOverCreditLimit()
    val isHighBalance = customer.balance >= 2500.0 || isOverLimit || (customer.hasCreditLimit() && customer.getCreditUtilizationPercent() >= 0.8f)

    val riskScore = when {
        isCriticalOverdue || isOverLimit || customer.balance >= 5000.0 -> 3
        isModerateOverdue || isHighBalance -> 2
        customer.balance > 0.0 -> 1
        else -> 0
    }

    return CustomerCreditRiskInfo(
        customer = customer,
        daysOverdue = daysOverdue,
        lastPaymentDate = lastPayment,
        lastCreditDate = lastCredit,
        isCriticalOverdue = isCriticalOverdue,
        isModerateOverdue = isModerateOverdue,
        isHighBalance = isHighBalance,
        isOverCreditLimit = isOverLimit,
        riskScore = riskScore
    )
}

@Composable
fun CreditScreen(viewModel: StoreViewModel) {
    val context = LocalContext.current

    if (!com.example.utils.StaffManager.canViewKhata()) {
        com.example.ui.components.StaffAccessGate(
            screenTitle = "Khata & Customer Credit",
            screenDescription = "Customer debt ledgers, credit books, and supplier accounts are restricted to authorized personnel."
        )
        return
    }

    val isBn = LanguageManager.isBengali

    val rawCustomers by viewModel.allCustomers.collectAsState()
    val rawSuppliers by viewModel.allSuppliers.collectAsState()
    val allSales by viewModel.allSales.collectAsState()
    val allLedgerEntries by viewModel.allLedgerEntries.collectAsState()
    val allOnlineOrders by viewModel.allOnlineOrders.collectAsState()

    val customers = remember(rawCustomers, allLedgerEntries) {
        rawCustomers.map { c -> LedgerCalculator.reconcileCustomer(c, allLedgerEntries) }
    }
    val suppliers = remember(rawSuppliers, allLedgerEntries) {
        rawSuppliers.map { s -> LedgerCalculator.reconcileSupplier(s, allLedgerEntries) }
    }

    var selectedTabIndex by remember { mutableIntStateOf(0) } // 0: Customers (Receivables), 1: Payment Claims, 2: Suppliers (Payables)
    val pendingClaimsCount by viewModel.pendingClaimsCount.collectAsState()

    var customerSearchQuery by remember { mutableStateOf("") }
    // 300ms debounce for customer search query
    var debouncedCustomerSearchQuery by remember { mutableStateOf("") }
    LaunchedEffect(customerSearchQuery) {
        if (customerSearchQuery.isBlank()) {
            debouncedCustomerSearchQuery = ""
        } else {
            kotlinx.coroutines.delay(300)
            debouncedCustomerSearchQuery = customerSearchQuery
        }
    }

    var selectedCustomerFilter by remember { mutableStateOf("ALL") } // ALL, DUES, OVERDUE_30, OVERDUE_15, HIGH_BALANCE, OVER_LIMIT, SETTLED
    var customerSortBy by remember { mutableStateOf("RISK") } // RISK, BALANCE, DAYS, NAME
    var showSortMenu by remember { mutableStateOf(false) }

    var showAddCustomerDialog by remember { mutableStateOf(false) }
    val pendingLinksCount by viewModel.pendingCustomerLinksCount.collectAsState()
    var showPendingLinksDialog by remember { mutableStateOf(false) }
    var editingCustomer by remember { mutableStateOf<Customer?>(null) }
    var enlargedPhotoPair by remember { mutableStateOf<Pair<String?, String>?>(null) }
    var showAddSupplierDialog by remember { mutableStateOf(false) }

    var paymentCustomer by remember { mutableStateOf<Customer?>(null) }
    var paymentSupplier by remember { mutableStateOf<Supplier?>(null) }

    // Compute Risk & Aging details for each customer
    val customerRiskMap = remember(customers, allLedgerEntries) {
        customers.associate { cust ->
            cust.id to calculateCustomerCreditRisk(cust, allLedgerEntries)
        }
    }

    // Compute Lifetime Purchase & Channel Metrics for each customer
    val customerMetricsMap = remember(customers, allSales, allOnlineOrders) {
        customers.associate { cust ->
            cust.id to calculateCustomerPurchaseMetrics(cust, allSales, allOnlineOrders)
        }
    }

    val duesCount = remember(customers) { customers.count { it.balance > 0 } }
    val settledCount = remember(customers) { customers.count { it.balance <= 0 } }
    val overLimitCount = remember(customers) { customers.count { it.isOverCreditLimit() } }
    val criticalOverdueCount = remember(customerRiskMap) { customerRiskMap.values.count { it.isCriticalOverdue } }
    val moderateOverdueCount = remember(customerRiskMap) { customerRiskMap.values.count { it.isModerateOverdue } }
    val highBalanceCount = remember(customerRiskMap) { customerRiskMap.values.count { it.isHighBalance && it.customer.balance > 0 } }
    val totalOverdueCount = criticalOverdueCount + moderateOverdueCount
    val totalOverdueAmount = remember(customerRiskMap) {
        customerRiskMap.values.filter { it.isCriticalOverdue || it.isModerateOverdue }.sumOf { it.customer.balance }
    }

    // Proactive Notification Trigger for Critical Khata Overdues (Persistent 24-hour rate limiting)
    LaunchedEffect(criticalOverdueCount, totalOverdueAmount) {
        if (criticalOverdueCount > 0) {
            val topOverdueCustomerName = customerRiskMap.values
                .filter { it.isCriticalOverdue }
                .maxByOrNull { it.customer.balance }?.customer?.name

            NotificationHelper.checkAndNotifyCreditOverdue(
                context = context,
                overdueCount = criticalOverdueCount,
                totalOverdueAmount = totalOverdueAmount,
                topCustomerName = topOverdueCustomerName,
                forceNotify = false
            )
        }
    }

    // BackHandler: If on non-zero tab, switch back to Customers tab
    BackHandler(enabled = selectedTabIndex != 0) {
        selectedTabIndex = 0
    }

    // BackHandler: If search query or filter is active on Customers, reset before navigating away
    BackHandler(enabled = selectedTabIndex == 0 && (customerSearchQuery.isNotBlank() || selectedCustomerFilter != "ALL")) {
        customerSearchQuery = ""
        selectedCustomerFilter = "ALL"
    }

    val filteredCustomers = remember(customers, debouncedCustomerSearchQuery, selectedCustomerFilter, customerSortBy, customerRiskMap) {
        val filtered = customers.filter { cust ->
            val risk = customerRiskMap[cust.id]
            val matchesSearch = debouncedCustomerSearchQuery.isBlank() ||
                cust.name.contains(debouncedCustomerSearchQuery, ignoreCase = true) ||
                cust.phone.contains(debouncedCustomerSearchQuery, ignoreCase = true)

            val matchesFilter = when (selectedCustomerFilter) {
                "DUES" -> cust.balance > 0
                "SETTLED" -> cust.balance <= 0
                "OVERDUE_30" -> risk?.isCriticalOverdue == true
                "OVERDUE_15" -> risk?.isModerateOverdue == true
                "HIGH_BALANCE" -> risk?.isHighBalance == true && cust.balance > 0
                "OVER_LIMIT" -> cust.isOverCreditLimit()
                else -> true
            }

            matchesSearch && matchesFilter
        }

        when (customerSortBy) {
            "RISK" -> filtered.sortedWith(
                compareByDescending<Customer> { customerRiskMap[it.id]?.riskScore ?: 0 }
                    .thenByDescending { it.balance }
            )
            "BALANCE" -> filtered.sortedByDescending { it.balance }
            "DAYS" -> filtered.sortedByDescending { customerRiskMap[it.id]?.daysOverdue ?: 0 }
            "NAME" -> filtered.sortedBy { it.name.lowercase() }
            else -> filtered
        }
    }

    var selectedCustomerForDetails by remember { mutableStateOf<Customer?>(null) }
    var customerToDelete by remember { mutableStateOf<Customer?>(null) }
    var smsCustomer by remember { mutableStateOf<Customer?>(null) }

    val totalReceivables = remember(customers) { customers.sumOf { it.balance } }
    val totalPayables = remember(suppliers) { suppliers.sumOf { it.balance } }
    val isDarkTheme = ThemeManager.isDarkMode()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 4.dp)
        ) {
                // Combined Overview Dues Banner (Compact)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            elevation = CardDefaults.cardElevation(1.dp),
            border = BorderStroke(1.dp, TextMuted.copy(alpha = 0.15f)),
            shape = RoundedCornerShape(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Customers Side (To Collect / পাবো)
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start
                ) {
                    Surface(
                        shape = CircleShape,
                        color = StoreGreenProfit.copy(alpha = 0.12f),
                        modifier = Modifier.size(28.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.CallReceived, contentDescription = null, tint = StoreGreenProfit, modifier = Modifier.size(14.dp))
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = LanguageManager.getString("To Collect (পাবো)", "পাবো (গ্রাহক)"),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            color = TextMuted
                        )
                        Text(
                            text = "₹%.2f".format(totalReceivables),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = StoreGreenProfit
                        )
                    }
                }

                HorizontalDivider(
                    modifier = Modifier
                        .height(26.dp)
                        .width(1.dp)
                )

                // Suppliers Side (To Pay / দেবো)
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start
                ) {
                    Surface(
                        shape = CircleShape,
                        color = StoreRedPrimary.copy(alpha = 0.12f),
                        modifier = Modifier.size(28.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.CallMade, contentDescription = null, tint = StoreRedPrimary, modifier = Modifier.size(14.dp))
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = LanguageManager.getString("To Pay (দেবো)", "দেবো (মহাজন)"),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            color = TextMuted
                        )
                        Text(
                            text = "₹%.2f".format(totalPayables),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = StoreRedPrimary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        val isDarkTheme = ThemeManager.isDarkMode()

        // High Risk & Overdue Khata Alert Center Banner (Compact Slim Strip)
        if (selectedTabIndex == 0 && (totalOverdueCount > 0 || highBalanceCount > 0 || overLimitCount > 0)) {
            Surface(
                color = if (criticalOverdueCount > 0 || overLimitCount > 0) {
                    if (isDarkTheme) Color(0xFF2E1416) else Color(0xFFFEF2F2)
                } else {
                    if (isDarkTheme) Color(0xFF2E2412) else Color(0xFFFFFBEB)
                },
                border = BorderStroke(
                    1.dp,
                    if (criticalOverdueCount > 0 || overLimitCount > 0) {
                        if (isDarkTheme) Color(0xFF7F1D1D) else Color(0xFFFCA5A5)
                    } else {
                        if (isDarkTheme) Color(0xFF785012) else Color(0xFFFDE68A)
                    }
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (criticalOverdueCount > 0) Icons.Default.Warning else Icons.Default.HourglassTop,
                        contentDescription = null,
                        tint = if (criticalOverdueCount > 0) {
                            if (isDarkTheme) Color(0xFFEF5350) else Color(0xFFDC2626)
                        } else {
                            if (isDarkTheme) Color(0xFFF59E0B) else Color(0xFFD97706)
                        },
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = LanguageManager.getString("Alerts:", "নোটিশ:"),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (criticalOverdueCount > 0) {
                            if (isDarkTheme) Color(0xFFFCA5A5) else Color(0xFF991B1B)
                        } else {
                            if (isDarkTheme) Color(0xFFFDE68A) else Color(0xFF92400E)
                        },
                        fontSize = 10.5.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))

                    // Interactive quick badges
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (criticalOverdueCount > 0) {
                            Surface(
                                color = Color(0xFFDC2626),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.clickable { selectedCustomerFilter = "OVERDUE_30" }
                            ) {
                                Text(
                                    text = "🔴 $criticalOverdueCount " + LanguageManager.getString("Overdue 30d+", "৩০+ দিন বকেয়া"),
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        }

                        if (moderateOverdueCount > 0) {
                            Surface(
                                color = Color(0xFFF59E0B),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.clickable { selectedCustomerFilter = "OVERDUE_15" }
                            ) {
                                Text(
                                    text = "🟠 $moderateOverdueCount " + LanguageManager.getString("15-29 Days", "১৫-২৯ দিন"),
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        }

                        if (highBalanceCount > 0) {
                            Surface(
                                color = Color(0xFF4B5563),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.clickable { selectedCustomerFilter = "HIGH_BALANCE" }
                            ) {
                                Text(
                                    text = "🚨 $highBalanceCount " + LanguageManager.getString("High Balance (₹2500+)", "ভারী বকেয়া (₹২৫০০+)"),
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        }

                        if (overLimitCount > 0) {
                            Surface(
                                color = Color(0xFF7F1D1D),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.clickable { selectedCustomerFilter = "OVER_LIMIT" }
                            ) {
                                Text(
                                    text = "⚠️ $overLimitCount " + LanguageManager.getString("Over Limit", "সীমা অতিক্রান্ত"),
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    IconButton(
                        onClick = {
                            NotificationHelper.checkAndNotifyCreditOverdue(
                                context = context,
                                overdueCount = totalOverdueCount,
                                totalOverdueAmount = totalOverdueAmount,
                                forceNotify = true
                            )
                            Toast.makeText(
                                context,
                                if (isBn) "নোটিফিকেশন পাঠানো হয়েছে!" else "Overdue notification generated!",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.NotificationsActive,
                            contentDescription = "Test Notification",
                            tint = if (criticalOverdueCount > 0) {
                                if (isDarkTheme) Color(0xFFEF5350) else Color(0xFFDC2626)
                            } else {
                                if (isDarkTheme) Color(0xFFF59E0B) else Color(0xFFD97706)
                            },
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        }

        // Tab Selector
        ScrollableTabRow(
            selectedTabIndex = selectedTabIndex,
            containerColor = SurfaceWarm,
            edgePadding = 0.dp
        ) {
            Tab(
                selected = selectedTabIndex == 0,
                onClick = { selectedTabIndex = 0 },
                text = { Text(LanguageManager.getString("Customer Khata", "গ্রাহক খাতা (${customers.size})"), fontWeight = FontWeight.Bold) }
            )
            Tab(
                selected = selectedTabIndex == 1,
                onClick = { selectedTabIndex = 1 },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = LanguageManager.getString("Payment Claims", "পেমেন্ট ক্লেইম"),
                            fontWeight = FontWeight.Bold
                        )
                        if (pendingClaimsCount > 0) {
                            Surface(
                                shape = CircleShape,
                                color = Color(0xFFD97706)
                            ) {
                                Text(
                                    text = "$pendingClaimsCount",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                }
            )
            Tab(
                selected = selectedTabIndex == 2,
                onClick = { selectedTabIndex = 2 },
                text = { Text(LanguageManager.getString("Supplier Dues", "মহাজন খাতা (${suppliers.size})"), fontWeight = FontWeight.Bold) }
            )
            Tab(
                selected = selectedTabIndex == 3,
                onClick = { selectedTabIndex = 3 },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(15.dp))
                        Text(
                            text = LanguageManager.getString("Transaction History", "লেনদেন ইতিহাস (${allLedgerEntries.size})"),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            )
        }
    }

    AnimatedContent(
        targetState = selectedTabIndex,
        transitionSpec = {
            fadeIn(animationSpec = tween(180)) togetherWith fadeOut(animationSpec = tween(120))
        },
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        label = "CreditTabAnimation"
    ) { currentTab ->
        when (currentTab) {
            0 -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp)
                ) {
                    if (pendingLinksCount > 0) {
                        item(key = "pending_links_banner") {
                            Surface(
                                color = if (isDarkTheme) Color(0xFF452205) else Color(0xFFFEF3C7),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, Color(0xFFF59E0B)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showPendingLinksDialog = true }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            Icons.Default.Link,
                                            contentDescription = null,
                                            tint = Color(0xFFD97706),
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = if (isBn)
                                                "$pendingLinksCount টি অনলাইন গ্রাহক খাতা সংযোগের আবেদন জমা রয়েছে"
                                            else
                                                "$pendingLinksCount online customer link request(s) pending",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isDarkTheme) Color(0xFFFDE68A) else Color(0xFF92400E)
                                        )
                                    }
                                    Text(
                                        text = if (isBn) "যাচাই করুন >" else "Review >",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFFD97706)
                                    )
                                }
                            }
                        }
                    }

                    item(key = "customer_action_header") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = LanguageManager.getString("Customer Accounts", "গ্রাহকদের তালিকা"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedIconButton(
                                    onClick = { showPendingLinksDialog = true },
                                    modifier = Modifier.size(38.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    border = if (pendingLinksCount > 0) BorderStroke(1.5.dp, StoreGold) else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                                ) {
                                    BadgedBox(
                                        badge = {
                                            if (pendingLinksCount > 0) {
                                                Badge(
                                                    containerColor = StoreGold,
                                                    contentColor = Color.White
                                                ) {
                                                    Text("$pendingLinksCount", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.Link,
                                            contentDescription = if (isBn) "অনলাইন লিঙ্ক" else "Online Links",
                                            tint = if (pendingLinksCount > 0) StoreGold else StorePrimary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                Button(
                                    onClick = { showAddCustomerDialog = true },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = StorePrimary),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier.height(38.dp)
                                ) {
                                    Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = LanguageManager.getString("Add Customer", "নতুন গ্রাহক"),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.5.sp,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }

                    item(key = "customer_search_sort") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = customerSearchQuery,
                                onValueChange = { customerSearchQuery = it },
                                placeholder = {
                                    Text(
                                        text = LanguageManager.getString("Search by customer name or phone...", "গ্রাহকের নাম বা মোবাইল নম্বর দিয়ে খুঁজুন..."),
                                        fontSize = 13.sp,
                                        maxLines = 1
                                    )
                                },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp), tint = TextMuted) },
                                trailingIcon = if (customerSearchQuery.isNotEmpty()) {
                                    { IconButton(onClick = { customerSearchQuery = "" }) { Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(18.dp)) } }
                                } else null,
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            )

                            Box {
                                OutlinedButton(
                                    onClick = { showSortMenu = true },
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(56.dp)
                                ) {
                                    Icon(Icons.Default.Sort, contentDescription = "Sort", modifier = Modifier.size(18.dp), tint = StorePrimary)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = when (customerSortBy) {
                                            "RISK" -> LanguageManager.getString("Risk", "রিস্ক")
                                            "BALANCE" -> LanguageManager.getString("Balance", "বকেয়া")
                                            "DAYS" -> LanguageManager.getString("Days", "দিন")
                                            else -> LanguageManager.getString("Name", "নাম")
                                        },
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextDark,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }

                                DropdownMenu(
                                    expanded = showSortMenu,
                                    onDismissRequest = { showSortMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(LanguageManager.getString("🚨 Highest Risk First", "🚨 সবচেয়ে বেশি ঝুঁকিপূর্ণ আগে")) },
                                        onClick = { customerSortBy = "RISK"; showSortMenu = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(LanguageManager.getString("💰 Highest Balance First", "💰 সর্বোচ্চ বকেয়া আগে")) },
                                        onClick = { customerSortBy = "BALANCE"; showSortMenu = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(LanguageManager.getString("⏱️ Most Overdue Days", "⏱️ সবচেয়ে পুরোনো বকেয়া")) },
                                        onClick = { customerSortBy = "DAYS"; showSortMenu = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(LanguageManager.getString("🔤 Customer Name (A-Z)", "🔤 গ্রাহকের নাম (A-Z)")) },
                                        onClick = { customerSortBy = "NAME"; showSortMenu = false }
                                    )
                                }
                            }
                        }
                    }

                    item(key = "customer_filter_chips") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilterChip(
                                selected = false,
                                onClick = { showPendingLinksDialog = true },
                                label = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = if (isBn) "অনলাইন লিঙ্ক" else "Online Links",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        if (pendingLinksCount > 0) {
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Surface(
                                                shape = CircleShape,
                                                color = StoreGold
                                            ) {
                                                Text(
                                                    text = "$pendingLinksCount",
                                                    color = Color.White,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                    }
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Link,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = if (pendingLinksCount > 0) StoreGold else StorePrimary
                                    )
                                },
                                border = if (pendingLinksCount > 0) BorderStroke(1.5.dp, StoreGold) else null,
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = if (pendingLinksCount > 0) (if (isDarkTheme) Color(0xFF452205) else Color(0xFFFEF3C7)) else Color.Transparent
                                ),
                                shape = RoundedCornerShape(8.dp)
                            )
                            FilterChip(
                                selected = selectedCustomerFilter == "ALL",
                                onClick = { selectedCustomerFilter = "ALL" },
                                label = { Text(LanguageManager.getString("All (${customers.size})", "সবাই (${customers.size})"), fontSize = 12.sp) },
                                shape = RoundedCornerShape(8.dp)
                            )

                            FilterChip(
                                selected = selectedCustomerFilter == "DUES",
                                onClick = { selectedCustomerFilter = "DUES" },
                                label = { Text(LanguageManager.getString("All Dues ($duesCount)", "সব বাকি ($duesCount)"), fontSize = 12.sp) },
                                leadingIcon = if (duesCount > 0) {
                                    { Icon(Icons.Default.PriorityHigh, contentDescription = null, tint = StoreRedAlert, modifier = Modifier.size(14.dp)) }
                                } else null,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = StoreRedAlert.copy(alpha = 0.15f),
                                    selectedLabelColor = StoreRedAlert
                                ),
                                shape = RoundedCornerShape(8.dp)
                            )

                            if (criticalOverdueCount > 0) {
                                FilterChip(
                                    selected = selectedCustomerFilter == "OVERDUE_30",
                                    onClick = { selectedCustomerFilter = "OVERDUE_30" },
                                    label = { Text(LanguageManager.getString("🔴 30d+ Overdue ($criticalOverdueCount)", "🔴 ৩০+ দিন বকেয়া ($criticalOverdueCount)"), fontSize = 12.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = if (isDarkTheme) Color(0xFF5F1D1D) else Color(0xFFFEE2E2),
                                        selectedLabelColor = if (isDarkTheme) Color(0xFFFCA5A5) else Color(0xFF991B1B)
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                )
                            }

                            if (moderateOverdueCount > 0) {
                                FilterChip(
                                    selected = selectedCustomerFilter == "OVERDUE_15",
                                    onClick = { selectedCustomerFilter = "OVERDUE_15" },
                                    label = { Text(LanguageManager.getString("🟠 15-29d ($moderateOverdueCount)", "🟠 ১৫-২৯ দিন ($moderateOverdueCount)"), fontSize = 12.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = if (isDarkTheme) Color(0xFF523311) else Color(0xFFFEF3C7),
                                        selectedLabelColor = if (isDarkTheme) Color(0xFFFDE68A) else Color(0xFF92400E)
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                )
                            }

                            if (highBalanceCount > 0) {
                                FilterChip(
                                    selected = selectedCustomerFilter == "HIGH_BALANCE",
                                    onClick = { selectedCustomerFilter = "HIGH_BALANCE" },
                                    label = { Text(LanguageManager.getString("🚨 High Balance ($highBalanceCount)", "🚨 ভারী বকেয়া ($highBalanceCount)"), fontSize = 12.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = if (isDarkTheme) Color(0xFF374151) else Color(0xFFE5E7EB),
                                        selectedLabelColor = if (isDarkTheme) Color(0xFFF3F4F6) else Color(0xFF1F2937)
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                )
                            }

                            if (overLimitCount > 0) {
                                FilterChip(
                                    selected = selectedCustomerFilter == "OVER_LIMIT",
                                    onClick = { selectedCustomerFilter = "OVER_LIMIT" },
                                    label = { Text(LanguageManager.getString("⚠️ Over Limit ($overLimitCount)", "⚠️ সীমা অতিক্রম ($overLimitCount)"), fontSize = 12.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = if (isDarkTheme) Color(0xFF5F1D1D) else Color(0xFFFEE2E2),
                                        selectedLabelColor = if (isDarkTheme) Color(0xFFFCA5A5) else Color(0xFFB91C1C)
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                )
                            }

                            FilterChip(
                                selected = selectedCustomerFilter == "SETTLED",
                                onClick = { selectedCustomerFilter = "SETTLED" },
                                label = { Text(LanguageManager.getString("Settled ($settledCount)", "পরিষ্কার ($settledCount)"), fontSize = 12.sp) },
                                leadingIcon = { Icon(Icons.Default.Check, contentDescription = null, tint = StoreGreenProfit, modifier = Modifier.size(14.dp)) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = StoreGreenProfit.copy(alpha = 0.15f),
                                    selectedLabelColor = StoreGreenProfit
                                ),
                                shape = RoundedCornerShape(8.dp)
                            )
                        }
                    }

                    if (filteredCustomers.isEmpty()) {
                        item(key = "customer_empty_state") {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                colors = CardDefaults.cardColors(containerColor = CardBackground),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(Icons.Default.PersonSearch, contentDescription = null, tint = TextMuted, modifier = Modifier.size(48.dp))
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = if (customerSearchQuery.isNotEmpty() || selectedCustomerFilter != "ALL")
                                            LanguageManager.getString("No matching customers found", "কোন নির্দেশিত গ্রাহক পাওয়া যায়নি")
                                        else
                                            LanguageManager.getString("No customer accounts created yet", "কোন গ্রাহকের খাতা তৈরি করা হয়নি"),
                                        fontWeight = FontWeight.Bold,
                                        color = TextDark
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = LanguageManager.getString("Tap '+ Add Customer' to create a new customer khata", "নতুন খাতা খুলতে '+ নতুন গ্রাহক' এ ক্লিক করুন"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextMuted
                                    )
                                }
                            }
                        }
                    } else {
                        items(filteredCustomers, key = { it.id }, contentType = { "CUSTOMER_CARD" }) { customer ->
                            val riskInfo = customerRiskMap[customer.id] ?: calculateCustomerCreditRisk(customer, allLedgerEntries)
                            val metrics = customerMetricsMap[customer.id] ?: calculateCustomerPurchaseMetrics(customer, allSales, allOnlineOrders)
                            CustomerCard(
                                modifier = Modifier.animateItem(),
                                customer = customer,
                                riskInfo = riskInfo,
                                purchaseMetrics = metrics,
                                isBn = isBn,
                                onRecordPayment = { paymentCustomer = customer },
                                onSendReminder = {
                                    val msg = if (riskInfo.isCriticalOverdue || riskInfo.isModerateOverdue || riskInfo.isHighBalance) {
                                        WhatsAppHelper.generateOverduePaymentReminder(
                                            customer = customer,
                                            daysOverdue = riskInfo.daysOverdue,
                                            isOverLimit = riskInfo.isOverCreditLimit,
                                            isBengali = isBn
                                        )
                                    } else {
                                        WhatsAppHelper.generatePaymentReminder(customer, isBn)
                                    }
                                    WhatsAppHelper.sendWhatsAppMessage(context, customer.phone, msg)
                                },
                                onSendSms = {
                                    smsCustomer = customer
                                },
                                onViewLedger = {
                                    selectedCustomerForDetails = customer
                                },
                                onEdit = {
                                    editingCustomer = customer
                                },
                                onDelete = {
                                    customerToDelete = customer
                                },
                                onPhotoClick = {
                                    enlargedPhotoPair = customer.photoUri to customer.name
                                }
                            )
                        }
                    }
                }
            }
            1 -> {
                PaymentClaimsView(
                    viewModel = viewModel,
                    onNavigateToCustomer = { cust ->
                        selectedCustomerForDetails = cust
                    }
                )
            }
            2 -> {
                SupplierManagementView(viewModel = viewModel)
            }
            else -> {
                TransactionHistoryLedgerView(
                    allLedgerEntries = allLedgerEntries,
                    customers = customers,
                    suppliers = suppliers,
                    allSales = allSales,
                    viewModel = viewModel,
                    onNavigateToCustomer = { cust ->
                        selectedCustomerForDetails = cust
                    },
                    onNavigateToSupplier = { _ ->
                        selectedTabIndex = 2
                    }
                )
            }
        }
    }
}

    val currentFirestoreRole by viewModel.currentFirestoreUserRole.collectAsState()
    val isOwnerOrAdmin = if (currentFirestoreRole != null) {
        currentFirestoreRole!!.isAdmin
    } else {
        com.example.utils.StaffManager.isOwner()
    }

    // Add Customer Dialog
    if (showAddCustomerDialog) {
        PartyFormDialog(
            title = LanguageManager.getString("Add Customer Khata", "নতুন গ্রাহক খাতা খুলুন"),
            isBn = isBn,
            isCustomer = true,
            canEditCreditLimit = isOwnerOrAdmin,
            onDismiss = { showAddCustomerDialog = false },
            onSave = { name, phone, photoUri, creditLimit, initialBalance, interestExempt, customGrace, customRate ->
                val cust = Customer(
                    id = "cust_" + UUID.randomUUID().toString().take(8),
                    name = name,
                    phone = phone,
                    photoUri = photoUri,
                    creditLimit = if (isOwnerOrAdmin) creditLimit else null,
                    interestExempt = interestExempt,
                    customGracePeriodDays = customGrace,
                    customInterestRate = customRate
                )
                viewModel.saveCustomer(cust, initialDue = initialBalance)
                showAddCustomerDialog = false
            }
        )
    }

    // Pending Customer Links Approval Dialog
    if (showPendingLinksDialog) {
        com.example.ui.components.PendingCustomerLinksDialog(
            viewModel = viewModel,
            onDismiss = { showPendingLinksDialog = false }
        )
    }

    // Edit Customer Dialog
    if (editingCustomer != null) {
        val cust = editingCustomer!!
        PartyFormDialog(
            title = LanguageManager.getString("Edit Customer Details", "গ্রাহকের তথ্য পরিবর্তন করুন"),
            initialName = cust.name,
            initialPhone = cust.phone,
            initialPhotoUri = cust.photoUri,
            initialCreditLimit = cust.creditLimit,
            initialInterestExempt = cust.interestExempt,
            initialCustomGracePeriodDays = cust.customGracePeriodDays,
            initialCustomInterestRate = cust.customInterestRate,
            isBn = isBn,
            isCustomer = true,
            canEditCreditLimit = isOwnerOrAdmin,
            onDismiss = { editingCustomer = null },
            onSave = { name, phone, photoUri, creditLimit, _, interestExempt, customGrace, customRate ->
                val effectiveLimit = if (isOwnerOrAdmin) creditLimit else cust.creditLimit
                val updated = cust.copy(
                    name = name,
                    phone = phone,
                    photoUri = photoUri,
                    creditLimit = effectiveLimit,
                    interestExempt = interestExempt,
                    customGracePeriodDays = customGrace,
                    customInterestRate = customRate
                )
                viewModel.saveCustomer(updated)
                editingCustomer = null
            }
        )
    }

    // Add Supplier Dialog
    if (showAddSupplierDialog) {
        PartyFormDialog(
            title = LanguageManager.getString("Add Supplier Khata", "নতুন মহাজন খাতা খুলুন"),
            isBn = isBn,
            isCustomer = false,
            onDismiss = { showAddSupplierDialog = false },
            onSave = { name, phone, photoUri, _, initialBalance, _, _, _ ->
                val supp = Supplier(
                    id = "supp_" + UUID.randomUUID().toString().take(8),
                    name = name,
                    phone = phone,
                    photoUri = photoUri
                )
                viewModel.saveSupplier(supp, initialDue = initialBalance)
                showAddSupplierDialog = false
            }
        )
    }

    if (enlargedPhotoPair != null) {
        val (photoUri, name) = enlargedPhotoPair!!
        EnlargedPhotoDialog(
            photoUri = photoUri,
            title = name,
            onDismiss = { enlargedPhotoPair = null }
        )
    }

    // Customer Payment Entry Dialog
    if (paymentCustomer != null) {
        val cust = paymentCustomer!!
        RecordPaymentDialog(
            partyName = cust.name,
            currentBalance = cust.balance,
            isCustomer = true,
            isBn = isBn,
            onDismiss = { paymentCustomer = null },
            onConfirmCustom = { amount, isCreditGiven, paymentMode, note ->
                viewModel.recordCustomerCustomEntry(cust.id, amount, isCreditGiven, paymentMode, note)
                paymentCustomer = null
            }
        )
    }

    // Supplier Payment Entry Dialog
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

    // Customer Comprehensive Details & Full Transaction History Modal
    if (selectedCustomerForDetails != null) {
        val cust = selectedCustomerForDetails!!
        val liveCustomer = customers.find { it.id == cust.id } ?: cust
        val customerSales = remember(allSales, cust.id) {
            allSales.filter { it.sale.customerId == cust.id }
        }
        val customerLedger = remember(allLedgerEntries, cust.id) {
            allLedgerEntries.filter { it.partyType == "CUSTOMER" && it.partyId == cust.id }
        }
        val customerMetrics = customerMetricsMap[liveCustomer.id]
            ?: calculateCustomerPurchaseMetrics(liveCustomer, allSales, allOnlineOrders)
        val custNormPhone = normalizePhoneLast10(liveCustomer.phone)
        val customerOnlineOrders = remember(allOnlineOrders, custNormPhone) {
            if (custNormPhone.isNotBlank()) {
                allOnlineOrders.filter { order ->
                    val oNorm = normalizePhoneLast10(order.customerPhone)
                    oNorm.isNotBlank() && oNorm == custNormPhone
                }
            } else emptyList()
        }

        CustomerDetailsModal(
            customer = liveCustomer,
            sales = customerSales,
            ledgerEntries = customerLedger,
            purchaseMetrics = customerMetrics,
            onlineOrders = customerOnlineOrders,
            isBn = isBn,
            viewModel = viewModel,
            onDismiss = { selectedCustomerForDetails = null },
            onRecordPayment = {
                paymentCustomer = liveCustomer
            },
            onSendSms = {
                smsCustomer = liveCustomer
            },
            onPrintThermal = { periodLabel, startTs, endTs ->
                viewModel.printCreditStatement(
                    customer = liveCustomer,
                    ledgerEntries = customerLedger,
                    sales = customerSales,
                    periodLabel = periodLabel,
                    startTimestamp = startTs,
                    endTimestamp = endTs,
                    isBengali = isBn
                )
            }
        )
    }

    // Khata Balance SMS Generator Dialog
    if (smsCustomer != null) {
        KhataSmsReminderDialog(
            customer = smsCustomer!!,
            isBn = isBn,
            onDismiss = { smsCustomer = null }
        )
    }

    // Delete Customer Confirmation Dialog
    if (customerToDelete != null) {
        val cust = customerToDelete!!
        AlertDialog(
            onDismissRequest = { customerToDelete = null },
            title = { Text(LanguageManager.getString("Delete Customer Account?", "গ্রাহক অ্যাকাউন্ট ডিলিট করবেন?")) },
            text = { Text(LanguageManager.getString("Are you sure you want to delete ${cust.name}? Transaction records will be retained in ledger history.", "আপনি কি নিশ্চিত যে ${cust.name}-কে ডিলিট করতে চান? লেজার ইতিহাসে লেনদেনের তথ্য থাকবে।")) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteCustomer(cust)
                        customerToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StoreRedPrimary)
                ) {
                    Text(LanguageManager.getString("Delete", "ডিলিট"))
                }
            },
            dismissButton = {
                TextButton(onClick = { customerToDelete = null }) { Text(LanguageManager.getString("Cancel", "বাতিল")) }
            }
        )
    }
}

@Composable
fun CustomerCard(
    modifier: Modifier = Modifier,
    customer: Customer,
    riskInfo: CustomerCreditRiskInfo? = null,
    purchaseMetrics: CustomerPurchaseMetrics? = null,
    isBn: Boolean,
    onRecordPayment: () -> Unit,
    onSendReminder: () -> Unit,
    onSendSms: () -> Unit = {},
    onViewLedger: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onPhotoClick: () -> Unit
) {
    val context = LocalContext.current
    val isDark = ThemeManager.isDarkMode()

    val isCritical = riskInfo?.isCriticalOverdue == true || customer.isOverCreditLimit()
    val isModerate = riskInfo?.isModerateOverdue == true || (riskInfo?.isHighBalance == true && customer.balance > 0)

    val cardBorder = when {
        isCritical -> BorderStroke(1.5.dp, if (isDark) Color(0xFFEF5350) else Color(0xFFDC2626))
        isModerate -> BorderStroke(1.dp, if (isDark) Color(0xFFF59E0B) else Color(0xFFF59E0B))
        else -> null
    }

    val cardBg = when {
        isCritical -> if (isDark) Color(0xFF241416) else Color(0xFFFFF5F5)
        isModerate -> if (isDark) Color(0xFF241E14) else Color(0xFFFFFDF5)
        else -> CardBackground
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onViewLedger() },
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = cardBorder,
        elevation = CardDefaults.cardElevation(if (isCritical) 3.dp else 2.dp),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Overdue / High Balance Warning Banner on Card Top
            if (customer.balance > 0 && riskInfo != null && (riskInfo.daysOverdue >= 15 || riskInfo.isHighBalance || riskInfo.isOverCreditLimit)) {
                Surface(
                    color = if (riskInfo.isCriticalOverdue || riskInfo.isOverCreditLimit) {
                        if (isDark) Color(0xFF381517) else Color(0xFFFEE2E2)
                    } else {
                        if (isDark) Color(0xFF382A14) else Color(0xFFFEF3C7)
                    },
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (riskInfo.isCriticalOverdue || riskInfo.isOverCreditLimit) Icons.Default.Warning else Icons.Default.HourglassTop,
                                contentDescription = null,
                                tint = if (riskInfo.isCriticalOverdue || riskInfo.isOverCreditLimit) {
                                    if (isDark) Color(0xFFEF5350) else Color(0xFFDC2626)
                                } else {
                                    if (isDark) Color(0xFFF59E0B) else Color(0xFFD97706)
                                },
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = when {
                                    riskInfo.isCriticalOverdue -> LanguageManager.getString("Overdue (${riskInfo.daysOverdue} days unpaid)", "${riskInfo.daysOverdue} দিন ধরে বকেয়া")
                                    riskInfo.isModerateOverdue -> LanguageManager.getString("Pending (${riskInfo.daysOverdue} days)", "${riskInfo.daysOverdue} দিন পেন্ডিং")
                                    riskInfo.isHighBalance -> LanguageManager.getString("High Balance Alert", "ভারী বকেয়া সতর্কতা")
                                    else -> LanguageManager.getString("Credit Alert", "বকেয়া সতর্কতা")
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (riskInfo.isCriticalOverdue || riskInfo.isOverCreditLimit) {
                                    if (isDark) Color(0xFFFCA5A5) else Color(0xFF991B1B)
                                } else {
                                    if (isDark) Color(0xFFFDE68A) else Color(0xFF92400E)
                                }
                            )
                        }

                        if (riskInfo.lastPaymentDate != null) {
                            val df = SimpleDateFormat("dd MMM", Locale.getDefault())
                            Text(
                                text = (if (isBn) "শেষ জমা: " else "Last paid: ") + df.format(Date(riskInfo.lastPaymentDate)),
                                fontSize = 10.sp,
                                color = TextMuted
                            )
                        }
                    }
                }
            }

            // Top Section: Profile, Name, Phone & Quick Actions (Edit/Delete)
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
                        photoUri = customer.photoUri,
                        name = customer.name,
                        size = 48.dp,
                        onClick = onPhotoClick
                    )

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = customer.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextDark
                            )
                            if (isCritical) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Surface(
                                    color = Color(0xFFDC2626),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = "OVERDUE",
                                        color = Color.White,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = if (customer.phone.isNotBlank()) "Ph: ${customer.phone}" else LanguageManager.getString("No Phone", "ফোন নম্বর নেই"),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                            if (customer.phone.isNotBlank()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Phone,
                                        contentDescription = "Call Customer",
                                        tint = StorePrimary,
                                        modifier = Modifier
                                            .size(15.dp)
                                            .clickable {
                                                try {
                                                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${customer.phone}"))
                                                    context.startActivity(intent)
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, "Cannot open dialer", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                    )
                                    Icon(
                                        imageVector = Icons.Default.Sms,
                                        contentDescription = "Send SMS",
                                        tint = StorePrimary,
                                        modifier = Modifier
                                            .size(15.dp)
                                            .clickable {
                                                onSendSms()
                                            }
                                    )
                                }
                            }
                        }
                    }
                }

                // Edit & Delete Icons in top right
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit Customer",
                            tint = TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Customer",
                            tint = StoreRedPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Due Balance Banner Row
            Surface(
                color = if (customer.balance > 0) {
                    if (isDark) Color(0xFF351417) else StoreRedPrimary.copy(alpha = 0.08f)
                } else {
                    if (isDark) Color(0xFF132A1C) else StoreGreenProfit.copy(alpha = 0.08f)
                },
                shape = RoundedCornerShape(8.dp),
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
                        Surface(
                            shape = CircleShape,
                            color = if (customer.balance > 0) StoreRedPrimary else StoreGreenProfit,
                            modifier = Modifier.size(8.dp)
                        ) {}
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = when {
                                customer.balance > 0 -> LanguageManager.getString("Due Balance (পাবো)", "বকেয়া টাকা (পাবো)")
                                customer.balance < 0 -> LanguageManager.getString("Advance Balance (অগ্রিম)", "অগ্রিম জমা")
                                else -> LanguageManager.getString("Khata Cleared / No Dues", "হিসাব পরিষ্কার (বকেয়া নেই)")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TextDark
                        )
                    }

                    Text(
                        text = "₹%.2f".format(kotlin.math.abs(customer.balance)),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (customer.balance > 0) StoreRedPrimary else StoreGreenProfit
                    )
                }
            }

            // Credit Limit Badge & Progress Bar
            if (customer.hasCreditLimit()) {
                val limit = customer.creditLimit!!
                val isOver = customer.isOverCreditLimit()
                val avail = customer.getAvailableCredit()
                val utilPercent = (customer.getCreditUtilizationPercent() * 100).coerceAtLeast(0f)

                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    color = if (isOver) {
                        if (isDark) Color(0xFF381517) else Color(0xFFFEF2F2)
                    } else SurfaceWarm,
                    border = BorderStroke(
                        1.dp,
                        if (isOver) {
                            if (isDark) Color(0xFFEF5350) else Color(0xFFFCA5A5)
                        } else TextMuted.copy(alpha = 0.15f)
                    ),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isOver) Icons.Default.Warning else Icons.Default.CreditScore,
                                    contentDescription = null,
                                    tint = if (isOver) (if (isDark) Color(0xFFEF5350) else Color(0xFFDC2626)) else StorePrimary,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isBn) "বাকী সীমা: ₹%.0f".format(limit) else "Credit Limit: ₹%.0f".format(limit),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isOver) (if (isDark) Color(0xFFFCA5A5) else Color(0xFFB91C1C)) else TextDark
                                )
                            }

                            Text(
                                text = if (isOver) {
                                    if (isBn) "⚠️ সীমা অতিক্রম (+₹%.2f)".format(customer.balance - limit)
                                    else "⚠️ Over by ₹%.2f".format(customer.balance - limit)
                                } else {
                                    val usedPercent = ((customer.balance / limit) * 100).coerceAtLeast(0.0)
                                    if (isBn) "বাকি আছে: ₹%.2f".format(avail)
                                    else "Avail: ₹%.2f (%.0f%%)".format(avail, usedPercent)
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isOver) (if (isDark) Color(0xFFEF5350) else Color(0xFFDC2626)) else if (utilPercent > 80) Color(0xFFD97706) else StoreGreenProfit
                            )
                        }

                        // Utilization Linear Progress Indicator
                        LinearProgressIndicator(
                            progress = { ((customer.balance / limit).toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp),
                            color = if (isOver) Color(0xFFDC2626) else if (utilPercent > 80) Color(0xFFF59E0B) else StoreGreenProfit,
                            trackColor = Color.LightGray.copy(alpha = 0.3f),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Bottom Action Buttons with equal weights for perfect responsiveness
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (customer.balance > 0) {
                    OutlinedButton(
                        onClick = onSendSms,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sms,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = StorePrimary
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "SMS",
                            fontSize = 11.sp,
                            color = StorePrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedButton(
                        onClick = onSendReminder,
                        modifier = Modifier.weight(1.3f),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = StoreGreenProfit
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = LanguageManager.getString("WhatsApp", "হোয়াটসঅ্যাপ"),
                            fontSize = 11.sp,
                            color = StoreGreenProfit,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = onRecordPayment,
                        modifier = Modifier.weight(1.4f),
                        colors = ButtonDefaults.buttonColors(containerColor = StoreGreenProfit),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Payments, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = LanguageManager.getString("Payment", "টাকা জমা"),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    if (customer.phone.isNotBlank()) {
                        OutlinedButton(
                            onClick = onSendSms,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Sms, contentDescription = null, modifier = Modifier.size(14.dp), tint = StorePrimary)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("SMS", fontSize = 12.sp, color = StorePrimary, fontWeight = FontWeight.Bold)
                        }
                    }

                    Button(
                        onClick = onRecordPayment,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = StoreGreenProfit),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Payments, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = LanguageManager.getString("+ Add Khata / Payment Entry", "+ খাতায় এন্ট্রি বা টাকা জমা নিন"),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SupplierCard(
    supplier: Supplier,
    isBn: Boolean,
    onRecordPayment: () -> Unit,
    onViewLedger: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onViewLedger() },
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        elevation = CardDefaults.cardElevation(2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(supplier.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Ph: ${supplier.phone}", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }

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

            Spacer(modifier = Modifier.height(8.dp))
            Divider()
            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = onRecordPayment,
                    colors = ButtonDefaults.buttonColors(containerColor = StoreRedPrimary)
                ) {
                    Icon(Icons.Default.Payments, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(LanguageManager.getString("Pay Supplier", "পেমেন্ট দিন"))
                }
            }
        }
    }
}

@Composable
fun PartyFormDialog(
    title: String,
    initialName: String = "",
    initialPhone: String = "",
    initialPhotoUri: String? = null,
    initialCreditLimit: Double? = null,
    initialBalance: Double = 0.0,
    initialInterestExempt: Boolean = false,
    initialCustomGracePeriodDays: Int? = null,
    initialCustomInterestRate: Double? = null,
    isCustomer: Boolean = false,
    canEditCreditLimit: Boolean = false,
    isBn: Boolean,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        phone: String,
        photoUri: String?,
        creditLimit: Double?,
        initialBalance: Double,
        interestExempt: Boolean,
        customGracePeriodDays: Int?,
        customInterestRate: Double?
    ) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var phone by remember { mutableStateOf(initialPhone) }
    var photoUri by remember { mutableStateOf<String?>(initialPhotoUri) }
    var initialBalanceText by remember {
        mutableStateOf(if (initialBalance > 0) "%.2f".format(initialBalance) else "")
    }
    var creditLimitText by remember {
        mutableStateOf(
            if (initialCreditLimit != null && initialCreditLimit > 0) {
                if (initialCreditLimit % 1.0 == 0.0) "%.0f".format(initialCreditLimit) else "%.2f".format(initialCreditLimit)
            } else ""
        )
    }
    var interestExempt by remember { mutableStateOf(initialInterestExempt) }
    var customGraceText by remember {
        mutableStateOf(initialCustomGracePeriodDays?.toString() ?: "")
    }
    var customRateText by remember {
        mutableStateOf(initialCustomInterestRate?.let { if (it % 1.0 == 0.0) "%.0f".format(it) else "%.2f".format(it) } ?: "")
    }
    var showAdvancedInterest by remember { mutableStateOf(initialInterestExempt || initialCustomGracePeriodDays != null || initialCustomInterestRate != null) }

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

    val launchCustomerCamera = com.example.utils.rememberHighResCameraCapture { dataUrl ->
        photoUri = dataUrl
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Profile Avatar Preview
                PartyProfileAvatar(
                    photoUri = photoUri,
                    name = name.ifBlank { "P" },
                    size = 72.dp,
                    showEditBadge = true,
                    onClick = { launchCustomerCamera() }
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { launchCustomerCamera() },
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
                    label = { Text(LanguageManager.getString("Full Name", "সম্পূর্ণ নাম")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text(LanguageManager.getString("Phone Number", "মোবাইল নম্বর")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (initialName.isBlank()) {
                    OutlinedTextField(
                        value = initialBalanceText,
                        onValueChange = { initialBalanceText = it },
                        label = { Text(LanguageManager.getString(if (isCustomer) "Previous Due / Opening Balance (₹)" else "Opening Payable Balance (₹)", if (isCustomer) "পূর্বের বাকী / জের (₹)" else "পূর্বের পাওনা / জের (₹)")) },
                        placeholder = { Text("0.00") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        leadingIcon = {
                            Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, tint = StoreRedPrimary, modifier = Modifier.size(18.dp))
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (isCustomer) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedTextField(
                            value = creditLimitText,
                            onValueChange = { if (canEditCreditLimit) creditLimitText = it },
                            enabled = canEditCreditLimit,
                            label = { Text(LanguageManager.getString("Credit Limit / বাকী সীমা (₹)", "বাকী সীমা / Credit Limit (₹)")) },
                            placeholder = { Text(LanguageManager.getString("e.g. 5000 (Empty = Unlimited)", "যেমন ৫০০০ (খালি রাখলে সীমাহীন)")) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            leadingIcon = {
                                Icon(
                                    if (canEditCreditLimit) Icons.Default.CreditScore else Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = if (canEditCreditLimit) StorePrimary else TextMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            trailingIcon = if (canEditCreditLimit && creditLimitText.isNotEmpty()) {
                                {
                                    IconButton(onClick = { creditLimitText = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                                    }
                                }
                            } else null,
                            supportingText = if (!canEditCreditLimit) {
                                {
                                    Text(
                                        text = LanguageManager.getString(
                                            "🔒 Only Owner or Admin can set/change credit limits",
                                            "🔒 শুধুমাত্র মালিক বা অ্যাডমিন বাকী সীমা পরিবর্তন করতে পারেন"
                                        ),
                                        fontSize = 10.sp,
                                        color = TextMuted
                                    )
                                }
                            } else null,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Quick presets for Credit Limit (Enabled only for Owner / Admin)
                        if (canEditCreditLimit) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(1000.0, 2000.0, 5000.0, 10000.0, 20000.0).forEach { presetLimit ->
                                    Surface(
                                        onClick = {
                                            creditLimitText = "%.0f".format(presetLimit)
                                        },
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (creditLimitText == "%.0f".format(presetLimit)) StorePrimary.copy(alpha = 0.15f) else SurfaceWarm,
                                        border = BorderStroke(1.dp, if (creditLimitText == "%.0f".format(presetLimit)) StorePrimary else TextMuted.copy(alpha = 0.2f)),
                                        modifier = Modifier.height(28.dp)
                                    ) {
                                        Text(
                                            text = "₹%.0f".format(presetLimit),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (creditLimitText == "%.0f".format(presetLimit)) StorePrimary else TextDark,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                        )
                                    }
                                }

                                Surface(
                                    onClick = { creditLimitText = "" },
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (creditLimitText.isBlank()) StoreGreenProfit.copy(alpha = 0.15f) else SurfaceWarm,
                                    border = BorderStroke(1.dp, if (creditLimitText.isBlank()) StoreGreenProfit else TextMuted.copy(alpha = 0.2f)),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text(
                                        text = if (isBn) "সীমাহীন" else "No Limit",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (creditLimitText.isBlank()) StoreGreenProfit else TextDark,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }

                        // Late Interest Custom Terms & Exemption Section
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = SurfaceWarm),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { showAdvancedInterest = !showAdvancedInterest },
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Icon(Icons.Default.Percent, contentDescription = null, tint = StorePrimary, modifier = Modifier.size(16.dp))
                                        Text(
                                            text = LanguageManager.getString("Interest & Grace Terms", "বিলম্ব সুদ ও গ্রেস সময়"),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Icon(
                                        imageVector = if (showAdvancedInterest) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = null,
                                        tint = TextMuted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                if (showAdvancedInterest) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = LanguageManager.getString("Exempt from Late Interest", "সুদ মুক্ত গ্রাহক (Exempt)"),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = LanguageManager.getString("No late interest will accrue on this customer's dues", "এই গ্রাহকের বাকীতে কখনো সুদ ধার্য হবে না"),
                                                fontSize = 10.sp,
                                                color = TextMuted
                                            )
                                        }
                                        Switch(
                                            checked = interestExempt,
                                            onCheckedChange = { interestExempt = it },
                                            modifier = Modifier.scale(0.8f)
                                        )
                                    }

                                    if (!interestExempt) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            OutlinedTextField(
                                                value = customGraceText,
                                                onValueChange = { customGraceText = it.filter { ch -> ch.isDigit() } },
                                                label = { Text(LanguageManager.getString("Grace Days", "গ্রেস দিন"), fontSize = 11.sp) },
                                                placeholder = { Text("${com.example.utils.StoreInfoManager.interestGracePeriodDays}") },
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                modifier = Modifier.weight(1f),
                                                singleLine = true
                                            )

                                            OutlinedTextField(
                                                value = customRateText,
                                                onValueChange = { customRateText = it },
                                                label = { Text(LanguageManager.getString("Monthly %", "মাসিক %"), fontSize = 11.sp) },
                                                placeholder = { Text("${com.example.utils.StoreInfoManager.interestRateMonthlyPercent}") },
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                                modifier = Modifier.weight(1f),
                                                singleLine = true
                                            )
                                        }
                                        Text(
                                            text = LanguageManager.getString(
                                                "Leave empty to inherit global store settings (${com.example.utils.StoreInfoManager.interestGracePeriodDays} days @ ${com.example.utils.StoreInfoManager.interestRateMonthlyPercent}%/mo).",
                                                "খালি রাখলে দোকানের মূল সেটিং প্রযোজ্য হবে (${com.example.utils.StoreInfoManager.interestGracePeriodDays} দিন ও ${com.example.utils.StoreInfoManager.interestRateMonthlyPercent}%)।"
                                            ),
                                            fontSize = 9.sp,
                                            color = TextMuted
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
                    if (name.isNotBlank()) {
                        val limit = creditLimitText.toDoubleOrNull()
                        val validLimit = if (limit != null && limit > 0.0) limit else null
                        val initBal = initialBalanceText.toDoubleOrNull() ?: 0.0
                        val customGrace = customGraceText.toIntOrNull()
                        val customRate = customRateText.toDoubleOrNull()
                        onSave(name, phone, photoUri, validLimit, initBal, interestExempt, customGrace, customRate)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = StoreRedPrimary)
            ) {
                Text(LanguageManager.getString("Save", "সংরক্ষণ"))
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
fun RecordPaymentDialog(
    partyName: String,
    currentBalance: Double,
    isCustomer: Boolean,
    isBn: Boolean,
    onDismiss: () -> Unit,
    onConfirmCustom: ((amount: Double, isCreditAddition: Boolean, paymentMode: String, note: String?) -> Unit)? = null,
    onConfirm: ((Double, String, String?) -> Unit)? = null
) {
    var isCreditAddition by remember { mutableStateOf(false) }
    var amountText by remember { mutableStateOf("") }
    var paymentMode by remember { mutableStateOf("CASH") }
    var note by remember { mutableStateOf("") }
    var attachedImageUri by remember { mutableStateOf<String?>(null) }
    var showEnlargedPhoto by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
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

    val launchProofCamera = com.example.utils.rememberHighResCameraCapture { dataUrl ->
        attachedImageUri = dataUrl
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isCreditAddition) Icons.Default.AddCard else Icons.Default.Payments,
                    contentDescription = null,
                    tint = if (isCreditAddition) StoreRedPrimary else StoreGreenProfit,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = LanguageManager.getString("Custom Payment & Khata - $partyName", "পেমেন্ট ও খাতা এন্ট্রি - $partyName"),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                // Current Balance Banner
                Surface(
                    color = StoreRedPrimary.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = if (isCustomer) LanguageManager.getString("Current Due Balance:", "বর্তমান বাকি বকেয়া:")
                                       else LanguageManager.getString("Current Outstanding Dues:", "মোট বাকি দেনা:"),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "₹%.2f".format(currentBalance),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = StoreRedPrimary
                            )
                        }

                        if (currentBalance > 0 && !isCreditAddition) {
                            OutlinedButton(
                                onClick = {
                                    amountText = if (currentBalance % 1.0 == 0.0) "%.0f".format(currentBalance) else "%.2f".format(currentBalance)
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, StoreGreenProfit)
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(14.dp), tint = StoreGreenProfit)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = LanguageManager.getString("Clear Full Due", "বাকি শূন্য করুন"),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = StoreGreenProfit
                                )
                            }
                        }
                    }
                }

                // Entry Type Toggle (Payment vs Credit / Due Addition)
                Text(
                    text = LanguageManager.getString("Entry Type:", "এন্ট্রির ধরণ:"),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = !isCreditAddition,
                        onClick = { isCreditAddition = false },
                        label = {
                            Text(
                                if (isCustomer) LanguageManager.getString("Payment Received (- Due)", "জমা পাওয়া গেল (- বাকি)")
                                else LanguageManager.getString("Payment Made (- Due)", "টাকা শোধ করা হল (- দেনা)"),
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        },
                        leadingIcon = { Icon(Icons.Default.ArrowDownward, contentDescription = null, modifier = Modifier.size(14.dp)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = StoreGreenProfit.copy(alpha = 0.15f),
                            selectedLabelColor = StoreGreenProfit,
                            selectedLeadingIconColor = StoreGreenProfit
                        ),
                        modifier = Modifier.weight(1f)
                    )

                    FilterChip(
                        selected = isCreditAddition,
                        onClick = { isCreditAddition = true },
                        label = {
                            Text(
                                if (isCustomer) LanguageManager.getString("Give Credit (+ Due)", "উধার / বাকি দেওয়া হল (+ বাকি)")
                                else LanguageManager.getString("Due Added (+ Due)", "মাল কেনা / দেনা যোগ (+ দেনা)"),
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        },
                        leadingIcon = { Icon(Icons.Default.ArrowUpward, contentDescription = null, modifier = Modifier.size(14.dp)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = StoreRedPrimary.copy(alpha = 0.15f),
                            selectedLabelColor = StoreRedPrimary,
                            selectedLeadingIconColor = StoreRedPrimary
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }

                // Amount Field
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = {
                        Text(
                            if (!isCreditAddition) {
                                if (isCustomer) LanguageManager.getString("Amount Received (₹)", "জমা প্রাপ্তি পরিমাণ (₹)")
                                else LanguageManager.getString("Payment Amount Paid (₹)", "পরিশোধিত অর্থ (₹)")
                            } else {
                                if (isCustomer) LanguageManager.getString("Credit / Due Amount (₹)", "বাকি বা উধারের পরিমাণ (₹)")
                                else LanguageManager.getString("Purchase / Due Amount (₹)", "নতুন বাকি বা ক্রয়ের পরিমাণ (₹)")
                            }
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                // Quick Amount Presets
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (currentBalance > 0 && !isCreditAddition) {
                        FilterChip(
                            selected = amountText.toDoubleOrNull() == currentBalance,
                            onClick = {
                                amountText = if (currentBalance % 1.0 == 0.0) "%.0f".format(currentBalance) else "%.2f".format(currentBalance)
                            },
                            label = { Text("Full Due (₹%.0f)".format(currentBalance), fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = StoreGreenProfit,
                                selectedLabelColor = Color.White,
                                containerColor = StoreGreenProfit.copy(alpha = 0.15f),
                                labelColor = StoreGreenProfit
                            ),
                            shape = RoundedCornerShape(6.dp)
                        )
                    }
                    listOf(100.0, 500.0, 1000.0, 2000.0, 5000.0).forEach { preset ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                val current = amountText.toDoubleOrNull() ?: 0.0
                                amountText = "%.0f".format(current + preset)
                            },
                            label = { Text("+₹%.0f".format(preset), fontSize = 11.sp) },
                            shape = RoundedCornerShape(6.dp)
                        )
                    }
                    if (amountText.isNotEmpty()) {
                        FilterChip(
                            selected = false,
                            onClick = { amountText = "" },
                            label = { Text(LanguageManager.getString("Clear", "ক্লিয়ার"), fontSize = 11.sp) },
                            shape = RoundedCornerShape(6.dp)
                        )
                    }
                }

                // Payment Mode Selection
                Text(
                    text = LanguageManager.getString("Payment Mode / Channel:", "পেমেন্ট মাধ্যম:"),
                    style = MaterialTheme.typography.labelSmall
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("CASH", "UPI", "BANK TRANSFER", "CHEQUE", "OTHER").forEach { mode ->
                        FilterChip(
                            selected = paymentMode == mode,
                            onClick = { paymentMode = mode },
                            label = { Text(mode, fontSize = 11.sp) },
                            shape = RoundedCornerShape(6.dp)
                        )
                    }
                }

                // Bill Photo Camera / Gallery section
                Text(
                    text = LanguageManager.getString("Bill / Receipt Photo (Camera / Gallery):", "বিলের ছবি / রসিদের ফটো (ক্যামেরা / গ্যালারি):"),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { launchProofCamera() },
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

                // Note / Ref No / Voucher
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(LanguageManager.getString("Note / Ref / Bill No. (Optional)", "নোট / রেফারেন্স / ভাউচার নং (ঐচ্ছিক)")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
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

                        if (onConfirmCustom != null) {
                            onConfirmCustom(amt, isCreditAddition, paymentMode, finalNote)
                        } else if (onConfirm != null) {
                            if (!isCreditAddition) {
                                onConfirm(amt, paymentMode, finalNote)
                            } else {
                                onConfirm(-amt, paymentMode, finalNote)
                            }
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isCreditAddition) StoreRedPrimary else StoreGreenProfit
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = if (isCreditAddition) LanguageManager.getString("Save Credit Entry", "এন্ট্রি সংরক্ষণ করুন")
                           else LanguageManager.getString("Save Payment", "পেমেন্ট সংরক্ষণ করুন"),
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(LanguageManager.getString("Cancel", "বাতিল")) }
        }
    )

    if (showEnlargedPhoto != null) {
        ZoomablePaymentScreenshotDialog(
            imageModel = com.example.utils.ImageSyncHelper.getImageModel(showEnlargedPhoto),
            title = LanguageManager.getString("Attached Photo", "সংযুক্ত ছবি"),
            onDismiss = { showEnlargedPhoto = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerDetailsModal(
    customer: Customer,
    sales: List<SaleWithItems>,
    ledgerEntries: List<LedgerEntry>,
    purchaseMetrics: CustomerPurchaseMetrics? = null,
    onlineOrders: List<Order> = emptyList(),
    isBn: Boolean,
    viewModel: StoreViewModel? = null,
    onDismiss: () -> Unit,
    onRecordPayment: () -> Unit,
    onSendSms: () -> Unit = {},
    onPrintThermal: (periodLabel: String, startTimestamp: Long?, endTimestamp: Long?) -> Unit = { _, _, _ -> }
) {
    val context = LocalContext.current
    val isDark = ThemeManager.isDarkMode()
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Ledger History, 1: Bills & Items, 2: Account Summary
    var viewPhotoUri by remember { mutableStateOf<String?>(null) }
    var showTokenManageDialog by remember { mutableStateOf(false) }
    var isTokenLoading by remember { mutableStateOf(false) }
    var showEditCustomerInterestDialog by remember { mutableStateOf(false) }
    var isPostingInterest by remember { mutableStateOf(false) }

    val interestBreakdown: CustomerInterestBreakdown = remember(customer, ledgerEntries, StoreInfoManager.interestEnabled, StoreInfoManager.interestRateMonthly, StoreInfoManager.interestGracePeriodDays) {
        KhataInterestCalculator.calculateCustomerInterest(
            customer = customer,
            allLedgerEntries = ledgerEntries,
            settings = StoreInfoManager.getKhataInterestSettings()
        )
    }

    val shareKhataLink: () -> Unit = {
        if (customer.hasShareToken()) {
            val url = StoreInfoManager.buildCustomerKhataUrl(customer.shareToken!!)
            val msg = WhatsAppHelper.generateSharedKhataMessage(customer.name, customer.balance, url, isBn)
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Customer Khata - ${StoreInfoManager.storeName}")
                putExtra(Intent.EXTRA_TEXT, msg)
            }
            try {
                context.startActivity(Intent.createChooser(sendIntent, if (isBn) "খাতা লিঙ্ক শেয়ার করুন" else "Share Customer Khata Link"))
            } catch (e: Exception) {
                Toast.makeText(context, "Cannot share link", Toast.LENGTH_SHORT).show()
            }
        } else {
            isTokenLoading = true
            viewModel?.getOrCreateCustomerShareToken(customer) { token ->
                isTokenLoading = false
                val url = StoreInfoManager.buildCustomerKhataUrl(token)
                val msg = WhatsAppHelper.generateSharedKhataMessage(customer.name, customer.balance, url, isBn)
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Customer Khata - ${StoreInfoManager.storeName}")
                    putExtra(Intent.EXTRA_TEXT, msg)
                }
                try {
                    context.startActivity(Intent.createChooser(sendIntent, if (isBn) "খাতা লিঙ্ক শেয়ার করুন" else "Share Customer Khata Link"))
                } catch (e: Exception) {
                    Toast.makeText(context, "Cannot share link", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val copyKhataLink: (String) -> Unit = { url ->
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Customer Khata Link", url)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, if (isBn) "খাতা লিঙ্ক কপি করা হয়েছে!" else "Khata link copied to clipboard!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to copy link", Toast.LENGTH_SHORT).show()
        }
    }

    val itemsBoughtSummary = remember(sales) {
        val map = mutableMapOf<String, Triple<String, Double, Double>>() // productId -> (Name, TotalQty, LastPrice)
        sales.forEach { saleWithItems ->
            saleWithItems.items.forEach { item ->
                val current = map[item.productId]
                val name = if (item.productNameEn.isNotBlank()) item.productNameEn else item.productNameBn
                if (current == null) {
                    map[item.productId] = Triple(name, item.quantity, item.unitPrice)
                } else {
                    map[item.productId] = Triple(current.first, current.second + item.quantity, item.unitPrice)
                }
            }
        }
        map.values.toList()
    }

    val totalBilled = remember(sales) { sales.sumOf { it.sale.finalAmount } }
    val dateFormat = SimpleDateFormat("dd/MM/yyyy hh:mm a", Locale.getDefault())

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

    val activeBounds = remember(ledgerDatePreset, ledgerCustomStartDate, ledgerCustomEndDate) {
        if (ledgerDatePreset == LedgerDatePreset.CUSTOM) {
            Pair(ledgerCustomStartDate, ledgerCustomEndDate)
        } else {
            getPresetDateBounds(ledgerDatePreset)
        }
    }

    val activePeriodLabel = remember(ledgerDatePreset, ledgerCustomStartDate, ledgerCustomEndDate, isBn) {
        val sdfDate = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        when (ledgerDatePreset) {
            LedgerDatePreset.ALL -> if (isBn) "সব সময়" else "All-Time"
            LedgerDatePreset.CUSTOM -> {
                "${sdfDate.format(Date(ledgerCustomStartDate))} - ${sdfDate.format(Date(ledgerCustomEndDate))}"
            }
            LedgerDatePreset.TODAY -> if (isBn) "আজকে" else "Today"
            LedgerDatePreset.YESTERDAY -> if (isBn) "গতকাল" else "Yesterday"
            LedgerDatePreset.LAST_7_DAYS -> if (isBn) "গত ৭ দিন" else "Last 7 Days"
            LedgerDatePreset.THIS_MONTH -> if (isBn) "এই মাস" else "This Month"
        }
    }

    val handlePrintThermalStatement: () -> Unit = {
        val (startTs, endTs) = activeBounds
        if (viewModel != null) {
            viewModel.printCreditStatement(
                customer = customer,
                ledgerEntries = ledgerEntries,
                sales = sales,
                periodLabel = activePeriodLabel,
                startTimestamp = startTs,
                endTimestamp = endTs,
                isBengali = isBn
            )
        } else {
            onPrintThermal(activePeriodLabel, startTs, endTs)
        }
    }

    val filteredLedgerEntries = remember(ledgerEntries, activeBounds) {
        val (startTs, endTs) = activeBounds
        ledgerEntries.filter { entry ->
            val afterStart = startTs == null || entry.datetime >= startTs
            val beforeEnd = endTs == null || entry.datetime <= endTs
            afterStart && beforeEnd
        }.sortedByDescending { it.datetime }
    }

    val periodDebits = remember(filteredLedgerEntries) {
        filteredLedgerEntries.filter { !it.type.contains("RECEIVED") }.sumOf { it.amount }
    }
    val periodCredits = remember(filteredLedgerEntries) {
        filteredLedgerEntries.filter { it.type.contains("RECEIVED") }.sumOf { it.amount }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        PartyProfileAvatar(
                            photoUri = customer.photoUri,
                            name = customer.name,
                            size = 48.dp,
                            onClick = {
                                if (!customer.photoUri.isNullOrBlank()) {
                                    viewPhotoUri = customer.photoUri
                                }
                            }
                        )

                        Column {
                            Text(
                                text = customer.name,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Ph: ${customer.phone}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted
                                )
                                if (customer.phone.isNotBlank()) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        imageVector = Icons.Default.Phone,
                                        contentDescription = "Call",
                                        tint = StorePrimary,
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clickable {
                                                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${customer.phone}"))
                                                context.startActivity(intent)
                                            }
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        imageVector = Icons.Default.Sms,
                                        contentDescription = "SMS",
                                        tint = StorePrimary,
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clickable {
                                                onSendSms()
                                            }
                                    )
                                }
                            }
                        }
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = if (isBn) "বকেয়া (Due)" else "Due Balance",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                        Text(
                            text = "₹%.2f".format(customer.balance),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (customer.balance > 0) StoreRedAlert else StoreGreenProfit
                        )
                        if (interestBreakdown.totalAccruedInterest > 0) {
                            Text(
                                text = "+₹%.2f %s".format(interestBreakdown.totalAccruedInterest, if (isBn) "সুদ" else "interest"),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = StoreGold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                val totalOrdersTabCount = purchaseMetrics?.lifetimeOrderCount ?: (sales.size + onlineOrders.size)

                // Tabs
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = if (isDark) Color(0xFF1F1E1D) else Color(0xFFF6F3EF),
                    contentColor = StorePrimary
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        selectedContentColor = StorePrimary,
                        unselectedContentColor = TextMuted,
                        text = { Text(if (isBn) "লেনদেন (${filteredLedgerEntries.size})" else "History (${filteredLedgerEntries.size})", fontSize = 11.sp, fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        selectedContentColor = StorePrimary,
                        unselectedContentColor = TextMuted,
                        text = { Text(if (isBn) "অর্ডার ও মেমো ($totalOrdersTabCount)" else "Bills & Orders ($totalOrdersTabCount)", fontSize = 11.sp, fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal) }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        selectedContentColor = StorePrimary,
                        unselectedContentColor = TextMuted,
                        text = { Text(if (isBn) "সামারি" else "Summary", fontSize = 11.sp, fontWeight = if (selectedTab == 2) FontWeight.Bold else FontWeight.Normal) }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                ) {
                    when (selectedTab) {
                    0 -> {
                        // Ledger History Tab with Custom Date Range Filter
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

                            // Quick Period Ledger Movement Strip
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
                                        text = if (isBn) "মোট জমা: ₹%.2f".format(periodCredits) else "Received: ₹%.2f".format(periodCredits),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = StoreGreenProfit
                                    )
                                    Text(
                                        text = if (isBn) "ধারে বিক্রি: ₹%.2f".format(periodDebits) else "Credit Given: ₹%.2f".format(periodDebits),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = StoreRedAlert
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
                                val salesById = remember(sales) { sales.associateBy { it.sale.id } }
                                LazyColumn(
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                ) {
                                    items(
                                        items = filteredLedgerEntries,
                                        key = { it.id },
                                        contentType = { entry -> if (entry.type.contains("RECEIVED")) "RECEIVED" else "DEBIT" }
                                    ) { entry ->
                                    val linkedSale = entry.referenceId?.let { refId -> salesById[refId] }
                                    var expanded by remember { mutableStateOf(false) }

                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .animateContentSize(animationSpec = AnimationTokens.accordionSpec())
                                            .clickable(enabled = linkedSale != null) { expanded = !expanded },
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (entry.type.contains("RECEIVED")) StoreGreenProfit.copy(alpha = 0.06f) else StoreRedAlert.copy(alpha = 0.05f)
                                        ),
                                        border = BorderStroke(
                                            1.dp,
                                            if (entry.type.contains("RECEIVED")) StoreGreenProfit.copy(alpha = 0.25f) else StoreRedAlert.copy(alpha = 0.2f)
                                        ),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Surface(
                                                        color = when {
                                                            entry.type.contains("RECEIVED") -> StoreGreenProfit
                                                            entry.type.contains("INTEREST") -> StoreGold
                                                            else -> StorePrimary
                                                        },
                                                        shape = RoundedCornerShape(4.dp)
                                                    ) {
                                                        Text(
                                                            text = when (entry.type) {
                                                                "SALE_CREDIT" -> if (isBn) "ধারে বিক্রি (Debit)" else "Credit Sale (Debit)"
                                                                "PAYMENT_RECEIVED" -> if (isBn) "টাকা জমা (Credit)" else "Payment Received (Credit)"
                                                                "OPENING_BALANCE", "INITIAL_DUE" -> if (isBn) "পূর্বের বকেয়া (প্রারম্ভিক জের)" else "Opening Balance"
                                                                "INTEREST", "INTEREST_CHARGE" -> if (isBn) "বিলম্ব সুদ (Late Interest)" else "Late Interest Fee"
                                                                else -> entry.type
                                                            },
                                                            color = Color.White,
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = dateFormat.format(Date(entry.datetime)),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = TextMuted
                                                    )
                                                    val entryPhotoUri = extractPhotoUri(entry.note)
                                                    val entryCleanNote = cleanNotes(entry.note)
                                                    if (entryCleanNote.isNotEmpty()) {
                                                        Text(
                                                            text = "Note: $entryCleanNote",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            fontWeight = FontWeight.Medium
                                                        )
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
                                                                Text("View Photo 📷", style = MaterialTheme.typography.labelSmall, color = StorePrimary, fontWeight = FontWeight.Bold)
                                                            }
                                                        }
                                                    }
                                                }

                                                Column(horizontalAlignment = Alignment.End) {
                                                    Text(
                                                        text = (if (entry.type.contains("RECEIVED")) "- ₹%.2f" else "+ ₹%.2f").format(entry.amount),
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 15.sp,
                                                        color = if (entry.type.contains("RECEIVED")) StoreGreenProfit else StoreRedAlert
                                                    )

                                                    if (linkedSale != null) {
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            modifier = Modifier.padding(top = 2.dp)
                                                        ) {
                                                            Text(
                                                                text = if (expanded) "Hide Items" else "View Items (${linkedSale.items.size})",
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

                                            // Expanded Sale Items
                                            if (expanded && linkedSale != null) {
                                                Spacer(modifier = Modifier.height(8.dp))
                                                Divider(color = Color.LightGray.copy(alpha = 0.5f))
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Text(
                                                    text = "Bill #${linkedSale.sale.id.takeLast(6)} Item Details:",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = TextDark
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

                    1 -> {
                        // Bills & Items Tab
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            if (purchaseMetrics != null && purchaseMetrics.lifetimeOrderCount > 0) {
                                item {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = if (isDark) Color(0xFF1E293B) else Color(0xFFF1F5F9)),
                                        shape = RoundedCornerShape(10.dp)
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
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.ShoppingBag,
                                                    contentDescription = null,
                                                    tint = StorePrimary,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                                Text(
                                                    text = if (isBn) "মোট অর্ডার: ${purchaseMetrics.lifetimeOrderCount} (${purchaseMetrics.inStoreOrderCount} দোকান, ${purchaseMetrics.onlineOrderCount} অনলাইন)"
                                                           else "Orders: ${purchaseMetrics.lifetimeOrderCount} (${purchaseMetrics.inStoreOrderCount} Store, ${purchaseMetrics.onlineOrderCount} Online)",
                                                    fontSize = 11.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = TextDark
                                                )
                                            }
                                            Text(
                                                text = "₹%.2f".format(purchaseMetrics.lifetimeSpend),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = StoreGreenProfit
                                            )
                                        }
                                    }
                                }
                            }

                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = SurfaceWarm)
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(
                                            text = if (isBn) "গ্রাহকের কেনা পন্যসমূহ (${itemsBoughtSummary.size}):" else "Items Bought (${itemsBoughtSummary.size}):",
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        if (itemsBoughtSummary.isEmpty()) {
                                            Text("No purchase bills recorded yet.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                        } else {
                                            itemsBoughtSummary.forEach { (name, totalQty, lastPrice) ->
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Text("• $name", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                                    Text("Qty: %.1f | Price: ₹%.2f".format(totalQty, lastPrice), style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            item {
                                Text(
                                    text = if (isBn) "মেমো/বিল তালিকা:" else "Sales Invoices History:",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall
                                )
                            }

                            if (sales.isEmpty()) {
                                item {
                                    Text("No bills for this customer.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                                }
                            } else {
                                items(sales, key = { it.sale.id }, contentType = { "SALE_INVOICE" }) { sWithItems ->
                                    val sale = sWithItems.sale
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
                                                    Text("Bill #${sale.id.takeLast(6)}", fontWeight = FontWeight.Bold)
                                                    Text(dateFormat.format(Date(sale.datetime)), style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                                }
                                                Column(horizontalAlignment = Alignment.End) {
                                                    Text("₹%.2f".format(sale.finalAmount), fontWeight = FontWeight.Bold, color = StoreRedPrimary)
                                                    Text("Mode: ${sale.paymentMode}", style = MaterialTheme.typography.labelSmall, color = StorePrimary)
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(4.dp))
                                            sWithItems.items.forEach { sItem ->
                                                val itemName = if (isBn && sItem.productNameBn.isNotBlank()) sItem.productNameBn else sItem.productNameEn
                                                Text(
                                                    text = " • $itemName: %.1f @ ₹%.1f = ₹%.2f".format(sItem.quantity, sItem.unitPrice, sItem.subtotal),
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                            }

                                            Spacer(modifier = Modifier.height(6.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.End,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                TextButton(
                                                    onClick = {
                                                        val smsMsg = SmsHelper.generateStatementSms(
                                                            customerName = customer.name,
                                                            currentBalance = sale.finalAmount,
                                                            salesCount = 1,
                                                            totalBilledValuation = sale.finalAmount,
                                                            isBengali = StoreInfoManager.isSmsBengali()
                                                        )
                                                        SmsHelper.sendSms(context, customer.phone, smsMsg)
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                                                ) {
                                                    Icon(Icons.Default.Sms, contentDescription = null, modifier = Modifier.size(12.dp), tint = StorePrimary)
                                                    Spacer(modifier = Modifier.width(3.dp))
                                                    Text("SMS Bill", fontSize = 11.sp, color = StorePrimary)
                                                }

                                                Spacer(modifier = Modifier.width(4.dp))

                                                TextButton(
                                                    onClick = {
                                                        val msg = WhatsAppHelper.generateBillMessage(sWithItems, isBn)
                                                        WhatsAppHelper.sendWhatsAppMessage(context, customer.phone, msg)
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                                                ) {
                                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(12.dp), tint = StoreGreenProfit)
                                                    Spacer(modifier = Modifier.width(3.dp))
                                                    Text("WhatsApp Bill", fontSize = 11.sp, color = StoreGreenProfit)
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            if (onlineOrders.isNotEmpty()) {
                                item {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = if (isBn) "অনলাইন অর্ডার তালিকা (${onlineOrders.size}):" else "Online Orders (${onlineOrders.size}):",
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        Surface(
                                            color = Color(0xFF0284C7).copy(alpha = 0.12f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.PhoneAndroid,
                                                    contentDescription = null,
                                                    tint = Color(0xFF0284C7),
                                                    modifier = Modifier.size(11.dp)
                                                )
                                                Text(
                                                    text = "matched by phone",
                                                    fontSize = 9.sp,
                                                    color = Color(0xFF0284C7)
                                                )
                                            }
                                        }
                                    }
                                }

                                items(onlineOrders, key = { it.id }, contentType = { "ONLINE_ORDER" }) { ord ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = CardBackground),
                                        border = BorderStroke(1.dp, Color(0xFF0284C7).copy(alpha = 0.25f)),
                                        elevation = CardDefaults.cardElevation(1.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column {
                                                    Text("Order #${ord.orderNumber}", fontWeight = FontWeight.Bold, color = Color(0xFF0284C7))
                                                    Text(dateFormat.format(Date(ord.createdAt)), style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                                }
                                                Column(horizontalAlignment = Alignment.End) {
                                                    Text("₹%.2f".format(ord.totalAmount), fontWeight = FontWeight.Bold, color = StoreGreenProfit)
                                                    Text(ord.status, style = MaterialTheme.typography.labelSmall, color = StorePrimary)
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = "${ord.fulfillmentType} • ${ord.paymentMethod}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = TextMuted
                                            )

                                            if (ord.items.isNotEmpty()) {
                                                Spacer(modifier = Modifier.height(4.dp))
                                                ord.items.forEach { item ->
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Text("• ${item.name}", style = MaterialTheme.typography.bodySmall)
                                                        Text("x%.1f @ ₹%.2f".format(item.quantity, item.price), style = MaterialTheme.typography.bodySmall, color = TextMuted)
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
                        // Summary Tab
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = SurfaceWarm)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("Customer Info", fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("Name: ${customer.name}")
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text("Phone: ${customer.phone}")
                                        if (customer.phone.isNotBlank()) {
                                            Icon(
                                                imageVector = Icons.Default.Phone,
                                                contentDescription = "Call",
                                                tint = StorePrimary,
                                                modifier = Modifier
                                                    .size(16.dp)
                                                    .clickable {
                                                        try {
                                                            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${customer.phone}")))
                                                        } catch (e: Exception) {
                                                            Toast.makeText(context, "Cannot dial", Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                            )
                                            Icon(
                                                imageVector = Icons.Default.Sms,
                                                contentDescription = "SMS",
                                                tint = StorePrimary,
                                                modifier = Modifier
                                                    .size(16.dp)
                                                    .clickable {
                                                        onSendSms()
                                                    }
                                            )
                                        }
                                    }
                                }
                            }

                            // Purchase Activity & Lifetime History (Enriched Khata Data)
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = CardBackground)
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (isBn) "ক্রয় ও অর্ডার সংক্ষিপ্ত বিবরণ" else "Purchase & Order Lifetime Summary",
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        Icon(
                                            imageVector = Icons.Default.ShoppingBag,
                                            contentDescription = null,
                                            tint = StorePrimary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(2.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(if (isBn) "মোট অর্ডার (সর্বমোট):" else "Total Lifetime Orders:", style = MaterialTheme.typography.bodySmall)
                                        Text(
                                            "${purchaseMetrics?.lifetimeOrderCount ?: sales.size}",
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(if (isBn) "দোকানের কাউন্টার অর্ডার:" else "In-Store / Counter Orders:", style = MaterialTheme.typography.bodySmall)
                                        Text(
                                            "${purchaseMetrics?.inStoreOrderCount ?: sales.size} (₹%.2f)".format(purchaseMetrics?.inStoreSpend ?: totalBilled),
                                            fontWeight = FontWeight.SemiBold,
                                            color = StoreGreenProfit
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(if (isBn) "অনলাইন স্টোর অর্ডার:" else "Online Store Orders:", style = MaterialTheme.typography.bodySmall)
                                            Surface(
                                                color = Color(0xFF0284C7).copy(alpha = 0.12f),
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.PhoneAndroid,
                                                        contentDescription = "Matched by phone",
                                                        tint = Color(0xFF0284C7),
                                                        modifier = Modifier.size(10.dp)
                                                    )
                                                    Text(
                                                        text = "phone match",
                                                        fontSize = 9.sp,
                                                        color = Color(0xFF0284C7)
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            "${purchaseMetrics?.onlineOrderCount ?: 0} (₹%.2f)".format(purchaseMetrics?.onlineSpend ?: 0.0),
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFF0284C7)
                                        )
                                    }

                                    Divider(color = Color.LightGray.copy(alpha = 0.3f))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(if (isBn) "সর্বমোট কেনাকাটা (Spend):" else "Total Lifetime Spend:", fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "₹%.2f".format(purchaseMetrics?.lifetimeSpend ?: totalBilled),
                                            fontWeight = FontWeight.Bold,
                                            color = StoreGreenProfit
                                        )
                                    }

                                    if (purchaseMetrics?.lastPurchaseDate != null) {
                                        val dateFmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
                                        val channelLabel = if (purchaseMetrics.lastPurchaseChannel == "ONLINE") {
                                            if (isBn) "অনলাইন স্টোর (ফোন দ্বারা ম্যাচ)" else "Online Store (phone match)"
                                        } else {
                                            if (isBn) "দোকানের কাউন্টার" else "In-Store Counter"
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(if (isBn) "সর্বশেষ কেনাকাটা:" else "Last Purchase Date:", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                            Text(
                                                dateFmt.format(Date(purchaseMetrics.lastPurchaseDate)),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                        Text(
                                            text = "via $channelLabel",
                                            fontSize = 11.sp,
                                            color = if (purchaseMetrics.lastPurchaseChannel == "ONLINE") Color(0xFF0284C7) else StoreGreenProfit,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.align(Alignment.End)
                                        )
                                    }

                                    // Small informational disclaimer
                                    Surface(
                                        color = if (isDark) Color(0xFF1E293B) else Color(0xFFF1F5F9),
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Info,
                                                contentDescription = null,
                                                tint = TextMuted,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Text(
                                                text = if (isBn)
                                                    "অনলাইন অর্ডার তথ্য গ্রাহকের মোবাইল (${customer.phone}) দিয়ে ম্যাচ করা হয়েছে। এটি শুধুমাত্র তথ্য প্রদর্শনের জন্য এবং খাতা বাকী/সীমায় প্রভাব ফেলে না।"
                                                else
                                                    "Online order stats matched by customer phone (${customer.phone}). Best-effort match for display only — does not affect customer credit limit or due balance.",
                                                fontSize = 10.sp,
                                                color = TextMuted,
                                                lineHeight = 13.sp
                                            )
                                        }
                                    }
                                }
                            }

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = CardBackground)
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Khata & Credit Limit Summary", fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text("Total Purchase Invoices: ${sales.size}")
                                    Text("Total Billed Valuation: ₹%.2f".format(totalBilled))
                                    Text(
                                        "Current Due Balance: ₹%.2f".format(customer.balance),
                                        fontWeight = FontWeight.Bold,
                                        color = if (customer.balance > 0) StoreRedPrimary else StoreGreenProfit
                                    )

                                    Divider(color = Color.LightGray.copy(alpha = 0.4f))

                                    if (customer.hasCreditLimit()) {
                                        val limit = customer.creditLimit!!
                                        val isOver = customer.isOverCreditLimit()
                                        val avail = customer.getAvailableCredit()
                                        val utilPercent = (customer.getCreditUtilizationPercent() * 100).coerceAtLeast(0f)

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(if (isBn) "বাকী সীমা (Credit Limit):" else "Credit Limit:", style = MaterialTheme.typography.bodySmall)
                                            Text("₹%.0f".format(limit), fontWeight = FontWeight.Bold)
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                if (isOver) (if (isBn) "সীমা অতিরিক্ত (Over Limit):" else "Over Limit:")
                                                else (if (isBn) "অবশিষ্ট বাকি সীমা (Available):" else "Available Credit:"),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (isOver) Color(0xFFDC2626) else TextDark
                                            )
                                            Text(
                                                if (isOver) "₹%.2f".format(customer.balance - limit) else "₹%.2f".format(avail),
                                                fontWeight = FontWeight.Bold,
                                                color = if (isOver) Color(0xFFDC2626) else StoreGreenProfit
                                            )
                                        }

                                        LinearProgressIndicator(
                                            progress = { ((customer.balance / limit).toFloat()).coerceIn(0f, 1f) },
                                            modifier = Modifier.fillMaxWidth().height(6.dp),
                                            color = if (isOver) Color(0xFFDC2626) else if (utilPercent > 80) Color(0xFFF59E0B) else StoreGreenProfit,
                                            trackColor = Color.LightGray.copy(alpha = 0.3f)
                                        )

                                        Text(
                                            text = if (isOver) "⚠️ Credit limit exceeded by ₹%.2f".format(customer.balance - limit)
                                                   else "Credit Usage: %.1f%% used".format(utilPercent),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isOver) Color(0xFFDC2626) else TextMuted
                                        )
                                    } else {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(if (isBn) "বাকী সীমা (Credit Limit):" else "Credit Limit:", style = MaterialTheme.typography.bodySmall)
                                            Text(if (isBn) "সীমাহীন (No Limit)" else "No Limit (Unlimited)", fontWeight = FontWeight.SemiBold, color = StoreGreenProfit)
                                        }
                                    }
                                }
                            }

                            // Late Interest & Credit Terms Card
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = if (customer.interestExempt) SurfaceWarm else StoreGold.copy(alpha = 0.08f)),
                                border = BorderStroke(1.dp, if (customer.interestExempt) Color.LightGray.copy(alpha = 0.5f) else StoreGold.copy(alpha = 0.4f)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Icon(Icons.Default.Percent, contentDescription = null, tint = StoreGold, modifier = Modifier.size(18.dp))
                                            Text(
                                                text = if (isBn) "বাকি বিলম্ব সুদ ও ক্রেডিট পলিসি" else "Late Interest & Grace Policy",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                color = if (customer.interestExempt) TextDark else StoreGold
                                            )
                                        }
                                        IconButton(
                                            onClick = { showEditCustomerInterestDialog = true },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(Icons.Default.Edit, contentDescription = "Edit Terms", tint = StoreGold, modifier = Modifier.size(16.dp))
                                        }
                                    }

                                    if (customer.interestExempt) {
                                        Surface(
                                            color = StoreGreenProfit.copy(alpha = 0.12f),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = if (isBn) "✓ এই গ্রাহক সুদ-মুক্ত (Exempt from Late Interest)" else "✓ Customer is Exempt from Late Interest",
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = StoreGreenProfit
                                            )
                                        }
                                    } else if (!StoreInfoManager.interestEnabled && customer.customInterestRate == null) {
                                        Text(
                                            text = if (isBn) "দোকানের সার্বিক বাকী সুদ বন্ধ আছে।" else "Global store late interest is currently disabled.",
                                            fontSize = 11.sp,
                                            color = TextMuted
                                        )
                                    } else if (interestBreakdown != null) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(if (isBn) "মাসিক সুদের হার:" else "Monthly Rate:", fontSize = 11.sp, color = TextMuted)
                                            Text(
                                                text = "%.2f%% / মাস %s".format(
                                                    interestBreakdown.effectiveMonthlyRate,
                                                    if (customer.customInterestRate != null) (if (isBn) "(কাস্টম)" else "(Custom)") else ""
                                                ),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.sp
                                            )
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(if (isBn) "গ্রেস পিরিয়ড (সুদমুক্ত সময়):" else "Grace Period:", fontSize = 11.sp, color = TextMuted)
                                            Text(
                                                text = "%d দিন %s".format(
                                                    interestBreakdown.effectiveGraceDays,
                                                    if (customer.customGracePeriodDays != null) (if (isBn) "(কাস্টম)" else "(Custom)") else ""
                                                ),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.sp
                                            )
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(if (isBn) "বকেয়া অতিক্রান্ত দিন:" else "Overdue Days:", fontSize = 11.sp, color = TextMuted)
                                            Text(
                                                text = "%d দিন (Days)".format(interestBreakdown.maxDaysOverdue),
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 11.sp
                                            )
                                        }

                                        Divider(color = StoreGold.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = 2.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(if (isBn) "হিসাবকৃত অতিরিক্ত সুদ:" else "Accrued Late Interest:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = StoreGold)
                                            Text("₹%.2f".format(interestBreakdown.totalAccruedInterest), fontWeight = FontWeight.Bold, fontSize = 12.sp, color = StoreGold)
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(if (isBn) "সর্বমোট বকেয়া (সুদ সহ):" else "Total Due (Inc. Interest):", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            Text("₹%.2f".format(interestBreakdown.totalOutstandingWithAccrued), fontWeight = FontWeight.Bold, fontSize = 12.sp, color = StoreRedPrimary)
                                        }

                                        if (interestBreakdown.totalAccruedInterest > 0) {
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Button(
                                                onClick = {
                                                    isPostingInterest = true
                                                    viewModel?.postCustomerAccruedInterest(
                                                        customer = customer,
                                                        onSuccess = {
                                                            isPostingInterest = false
                                                            Toast.makeText(context, if (isBn) "খাতায় সুদ সফলভাবে যোগ করা হয়েছে!" else "Accrued interest posted to ledger!", Toast.LENGTH_SHORT).show()
                                                        },
                                                        onError = { msg: String ->
                                                            isPostingInterest = false
                                                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                                        }
                                                    )
                                                },
                                                enabled = !isPostingInterest,
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = ButtonDefaults.buttonColors(containerColor = StoreGold),
                                                shape = RoundedCornerShape(8.dp),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                            ) {
                                                Icon(Icons.Default.AddCard, contentDescription = null, modifier = Modifier.size(15.dp), tint = Color.White)
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = if (isPostingInterest) (if (isBn) "যোগ হচ্ছে..." else "Posting...")
                                                           else (if (isBn) "খাতায় ₹%.2f সুদ যোগ করুন (Post to Ledger)".format(interestBreakdown.totalAccruedInterest) else "Post ₹%.2f Interest to Ledger".format(interestBreakdown.totalAccruedInterest)),
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 11.sp,
                                                    color = Color.White
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // Public Shareable Customer Khata Card
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = if (customer.hasShareToken()) Color(0xFFEFF6FF) else SurfaceWarm),
                                border = BorderStroke(1.dp, if (customer.hasShareToken()) Color(0xFF93C5FD) else Color.LightGray.copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Public,
                                                contentDescription = null,
                                                tint = StorePrimary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                text = if (isBn) "অনলাইন খাতা স্টেটমেন্ট লিঙ্ক" else "Public Khata Web Ledger",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                color = StorePrimary
                                            )
                                        }

                                        Surface(
                                            color = if (customer.hasShareToken()) Color(0xFFDCFCE7) else Color(0xFFF3F4F6),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Text(
                                                text = if (customer.hasShareToken()) (if (isBn) "● সক্রিয়" else "● Active") else (if (isBn) "তৈরি হয়নি" else "Inactive"),
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (customer.hasShareToken()) Color(0xFF15803D) else Color.Gray
                                            )
                                        }
                                    }

                                    Text(
                                        text = if (isBn) "গ্রাহকের সাথে সুরক্ষিত খাতার লিঙ্ক শেয়ার করুন যাতে তারা ট্রানজাকশন হিস্ট্রি ও UPI দিয়ে সরাসরি পেমেন্ট করতে পারে।"
                                        else "Share a secure web ledger link with real-time balance & Pay Now UPI button.",
                                        fontSize = 11.sp,
                                        color = TextMuted,
                                        lineHeight = 15.sp
                                    )

                                    if (customer.hasShareToken()) {
                                        val khataUrl = StoreInfoManager.buildCustomerKhataUrl(customer.shareToken!!)
                                        Surface(
                                            color = Color.White,
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(1.dp, Color(0xFFBFDBFE)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = khataUrl,
                                                    fontSize = 11.sp,
                                                    maxLines = 1,
                                                    modifier = Modifier.weight(1f),
                                                    color = Color(0xFF1E40AF)
                                                )
                                                IconButton(
                                                    onClick = { copyKhataLink(khataUrl) },
                                                    modifier = Modifier.size(28.dp)
                                                ) {
                                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(16.dp), tint = StorePrimary)
                                                }
                                                IconButton(
                                                    onClick = {
                                                        try {
                                                            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(khataUrl))
                                                            context.startActivity(browserIntent)
                                                        } catch (e: Exception) {
                                                            Toast.makeText(context, "Cannot open browser", Toast.LENGTH_SHORT).show()
                                                        }
                                                    },
                                                    modifier = Modifier.size(28.dp)
                                                ) {
                                                    Icon(Icons.Default.OpenInBrowser, contentDescription = "Open", modifier = Modifier.size(18.dp), tint = Color(0xFF2563EB))
                                                }
                                            }
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Button(
                                                onClick = shareKhataLink,
                                                modifier = Modifier.weight(1f),
                                                colors = ButtonDefaults.buttonColors(containerColor = StoreGreenProfit),
                                                shape = RoundedCornerShape(8.dp),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                            ) {
                                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(15.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(if (isBn) "লিঙ্ক শেয়ার করুন" else "Share Khata Link", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }

                                            OutlinedButton(
                                                onClick = { showTokenManageDialog = true },
                                                modifier = Modifier.weight(0.9f),
                                                shape = RoundedCornerShape(8.dp),
                                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                                            ) {
                                                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(14.dp), tint = TextDark)
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(if (isBn) "লিঙ্ক ম্যানেজ" else "Manage Link", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextDark)
                                            }
                                        }
                                    } else {
                                        Button(
                                            onClick = shareKhataLink,
                                            enabled = !isTokenLoading,
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(containerColor = StorePrimary),
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                                        ) {
                                            if (isTokenLoading) {
                                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(if (isBn) "লিঙ্ক তৈরি হচ্ছে..." else "Generating Link...", fontSize = 12.sp)
                                            } else {
                                                Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = if (isBn) "🌐 খাতা লিঙ্ক তৈরি ও শেয়ার করুন" else "🌐 Generate & Share Khata Link",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Button(
                                onClick = handlePrintThermalStatement,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = StorePrimary),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                val printBtnText = if (ledgerDatePreset != LedgerDatePreset.ALL) {
                                    if (isBn) "থার্মাল প্রিন্ট ($activePeriodLabel)" else "Print Thermal ($activePeriodLabel)"
                                } else {
                                    if (isBn) "থার্মাল প্রিন্ট স্টেটমেন্ট" else "Print Thermal Credit Statement"
                                }
                                Text(
                                    text = printBtnText,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onSendSms,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                                ) {
                                    Icon(Icons.Default.Sms, contentDescription = null, modifier = Modifier.size(16.dp), tint = StorePrimary)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("SMS Statement", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = StorePrimary)
                                }

                                Button(
                                    onClick = {
                                        val statementMsg = WhatsAppHelper.generateCustomerStatement(
                                            customerName = customer.name,
                                            phone = customer.phone,
                                            currentBalance = customer.balance,
                                            salesCount = sales.size,
                                            totalBilledValuation = totalBilled,
                                            isBengali = isBn
                                        )
                                        WhatsAppHelper.sendWhatsAppMessage(context, customer.phone, statementMsg)
                                    },
                                    modifier = Modifier.weight(1.3f),
                                    colors = ButtonDefaults.buttonColors(containerColor = StoreGreenProfit),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (isBn) "হোয়াটসঅ্যাপ স্টেটমেন্ট" else "WhatsApp Statement", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
                Divider(color = Color.LightGray.copy(alpha = 0.4f))
                Spacer(modifier = Modifier.height(10.dp))

                // Action Buttons Footer
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Utility Row: Khata Link, Print, SMS, WhatsApp
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = shareKhataLink,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFF2563EB))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(if (isBn) "খাতা লিঙ্ক" else "Khata Link", fontSize = 11.sp, color = Color(0xFF2563EB), fontWeight = FontWeight.Bold, maxLines = 1)
                        }

                        OutlinedButton(
                            onClick = handlePrintThermalStatement,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(14.dp), tint = StorePrimary)
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(if (isBn) "প্রিন্ট" else "Print", fontSize = 11.sp, color = StorePrimary, fontWeight = FontWeight.Bold, maxLines = 1)
                        }

                        if (customer.balance > 0) {
                            OutlinedButton(
                                onClick = onSendSms,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Sms, contentDescription = null, modifier = Modifier.size(14.dp), tint = StorePrimary)
                                Spacer(modifier = Modifier.width(3.dp))
                                Text("SMS", fontSize = 11.sp, color = StorePrimary, fontWeight = FontWeight.Bold, maxLines = 1)
                            }

                            OutlinedButton(
                                onClick = {
                                    val msg = WhatsAppHelper.generatePaymentReminder(customer, isBn)
                                    WhatsAppHelper.sendWhatsAppMessage(context, customer.phone, msg)
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp), tint = StoreGreenProfit)
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(if (isBn) "তাগাদা" else "WhatsApp", fontSize = 11.sp, color = StoreGreenProfit, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                        }
                    }

                    // Main Action Row: Receive Payment & Close
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                onDismiss()
                                onRecordPayment()
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = StoreGreenProfit),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.Payments, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isBn) "টাকা জমা নিন" else "Receive Payment",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                maxLines = 1
                            )
                        }

                        OutlinedButton(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(if (isBn) "বন্ধ" else "Close", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextDark)
                        }
                    }
                }
            }
        }
    }

    if (showTokenManageDialog) {
        val currentUrl = if (customer.hasShareToken()) StoreInfoManager.buildCustomerKhataUrl(customer.shareToken!!) else ""
        AlertDialog(
            onDismissRequest = { showTokenManageDialog = false },
            icon = {
                Icon(Icons.Default.Public, contentDescription = null, tint = StorePrimary)
            },
            title = {
                Text(
                    text = if (isBn) "অনলাইন খাতা লিঙ্ক ম্যানেজমেন্ট" else "Customer Khata Link Management",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (isBn) "নতুন লিঙ্ক তৈরি করলে পুরোনো লিঙ্কটি নিষ্ক্রিয় হয়ে যাবে। লিঙ্ক বন্ধ করলে কেউ আর ওয়েব পেজে খাতা দেখতে পারবে না।"
                        else "Regenerating creates a brand-new unguessable link and immediately invalidates the old one. Revoking disables public access completely.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted
                    )

                    if (customer.hasShareToken()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isBn) "বর্তমান সক্রিয় লিঙ্ক:" else "Current Active Link:",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            color = SurfaceWarm,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = currentUrl,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(8.dp),
                                color = Color(0xFF1E40AF)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = {
                            showTokenManageDialog = false
                            isTokenLoading = true
                            viewModel?.regenerateCustomerShareToken(customer) { newToken ->
                                isTokenLoading = false
                                val newUrl = StoreInfoManager.buildCustomerKhataUrl(newToken)
                                copyKhataLink(newUrl)
                                Toast.makeText(context, if (isBn) "নতুন খাতা লিঙ্ক তৈরি ও কপি করা হয়েছে!" else "New Khata link generated & copied!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = StorePrimary),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isBn) "🔄 নতুন লিঙ্ক তৈরি করুন (Regenerate)" else "🔄 Regenerate New Link", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    if (customer.hasShareToken()) {
                        OutlinedButton(
                            onClick = {
                                showTokenManageDialog = false
                                viewModel?.revokeCustomerShareToken(customer)
                                Toast.makeText(context, if (isBn) "খাতা লিঙ্ক বন্ধ করা হয়েছে" else "Khata link revoked", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFDC2626)),
                            border = BorderStroke(1.dp, Color(0xFFFCA5A5)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.LinkOff, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isBn) "🚫 লিঙ্ক বাতিল করুন (Revoke Access)" else "🚫 Revoke Link (Disable)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    TextButton(
                        onClick = { showTokenManageDialog = false },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text(if (isBn) "বন্ধ" else "Cancel", color = TextDark)
                    }
                }
            },
            dismissButton = null
        )
    }

    if (showEditCustomerInterestDialog) {
        CustomerInterestTermsDialog(
            customer = customer,
            ledgerEntries = ledgerEntries,
            isBn = isBn,
            onDismiss = { showEditCustomerInterestDialog = false },
            onSave = { updatedCust ->
                viewModel?.saveCustomer(updatedCust)
                Toast.makeText(
                    context,
                    if (isBn) "গ্রাহকের বাকী সুদের শর্তাবলী সংরক্ষিত হয়েছে" else "Customer late interest terms updated successfully",
                    Toast.LENGTH_SHORT
                ).show()
                showEditCustomerInterestDialog = false
            }
        )
    }

    if (viewPhotoUri != null) {
        ZoomablePaymentScreenshotDialog(
            imageModel = com.example.utils.ImageSyncHelper.getImageModel(viewPhotoUri),
            title = LanguageManager.getString("Attached Photo / Bill", "সংযুক্ত ফটো / চালান"),
            onDismiss = { viewPhotoUri = null }
        )
    }
}

@Composable
fun CustomerDetailView(
    customer: Customer,
    sales: List<SaleWithItems>,
    ledgerEntries: List<LedgerEntry>,
    purchaseMetrics: CustomerPurchaseMetrics? = null,
    onlineOrders: List<Order> = emptyList(),
    isBn: Boolean = LanguageManager.isBengali,
    viewModel: StoreViewModel? = null,
    onDismiss: () -> Unit,
    onRecordPayment: () -> Unit,
    onSendSms: () -> Unit = {},
    onPrintThermal: (periodLabel: String, startTimestamp: Long?, endTimestamp: Long?) -> Unit = { _, _, _ -> }
) {
    CustomerDetailsModal(
        customer = customer,
        sales = sales,
        ledgerEntries = ledgerEntries,
        purchaseMetrics = purchaseMetrics,
        onlineOrders = onlineOrders,
        isBn = isBn,
        viewModel = viewModel,
        onDismiss = onDismiss,
        onRecordPayment = onRecordPayment,
        onSendSms = onSendSms,
        onPrintThermal = onPrintThermal
    )
}

private fun String?.isNull_or_blank(): Boolean {
    return this == null || this.trim().isEmpty()
}
