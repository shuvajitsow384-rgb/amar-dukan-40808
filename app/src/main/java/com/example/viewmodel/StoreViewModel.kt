package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.auth.AuthManager
import com.example.data.auth.AuthUser
import com.example.data.auth.GoogleSignInAvailability
import com.example.data.auth.SignInRepository
import com.example.data.drive.DriveBackupFile
import com.example.data.drive.GoogleDriveBackupManager
import com.example.data.firestore.FirestoreProfitReport
import com.example.data.firestore.FirestoreUserRole
import com.example.data.firestore.PERMANENT_ADMIN_EMAILS
import com.example.data.local.AppDatabase
import com.example.data.local.dao.PurchaseWithItems
import com.example.data.local.dao.SaleWithItems
import com.example.data.local.entities.*
import com.example.data.models.CreditLimitExceededException
import com.example.data.models.InsufficientStockException
import com.example.data.models.InsufficientStockItem
import com.example.data.repository.ProfitAndLossReport
import com.example.data.repository.StockOutReportSummary
import com.example.data.repository.StoreRepository
import com.example.data.sync.SyncWorkManager
import com.example.data.sync.SyncWorker
import com.example.utils.CustomerInterestBreakdown
import com.example.utils.EscPosPrinter
import com.example.utils.KhataInterestCalculator
import com.example.utils.KhataInterestSettings
import com.example.utils.LanguageManager
import com.example.utils.NotificationHelper
import com.example.utils.SalaryBreakdown
import com.example.utils.SalaryCalculator
import com.example.utils.SmsHelper
import com.example.utils.StaffManager
import com.example.utils.StoreInfoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

data class CartItem(
    val product: Product,
    val quantity: Double,
    val unitPrice: Double,
    val unitType: String,
    val isWholesaleOverride: Boolean = false,
    val variantBarcode: String? = null,
    val variantLabel: String? = null,
    val variantQuantity: Double? = null,
    val variantUnitType: String? = null,
    val isFreeGift: Boolean = false,
    val freeGiftOfferId: String? = null,
    val freeGiftOfferName: String? = null
) {
    val isVariant: Boolean
        get() = !variantBarcode.isNullOrBlank()

    fun getDisplayName(isBn: Boolean = false): String {
        val baseName = product.getDisplayName(isBn)
        val nameWithVariant = if (!variantLabel.isNullOrBlank()) {
            "$baseName ($variantLabel)"
        } else {
            baseName
        }
        return if (isFreeGift) {
            val campaign = com.example.utils.BengaliReceiptTranslator.extractCleanGiftCampaignName(freeGiftOfferName)
            val giftLabel = if (isBn) {
                if (campaign.isNotBlank()) " (ফ্রি উপহার: $campaign)" else " (ফ্রি উপহার)"
            } else {
                if (campaign.isNotBlank()) " (Free Gift: $campaign)" else " (Free Gift)"
            }
            "$nameWithVariant$giftLabel"
        } else {
            nameWithVariant
        }
    }

    fun getBaseQuantityDeducted(): Double {
        return if (isVariant && variantQuantity != null && variantQuantity > 0.0) {
            val vUnit = variantUnitType ?: unitType
            quantity * product.convertQuantityToBaseUnit(variantQuantity, vUnit)
        } else {
            product.convertQuantityToBaseUnit(quantity, unitType)
        }
    }

    fun isUsingWholesale(isGlobalWholesale: Boolean = false): Boolean {
        if (isVariant || isFreeGift) return false
        return isGlobalWholesale || isWholesaleOverride
    }

    fun getEffectiveUnitPrice(isGlobalWholesale: Boolean = false): Double {
        if (isFreeGift) {
            return 0.0
        }
        if (isVariant) {
            return unitPrice
        }
        if (unitType.equals("box", ignoreCase = true) || unitType.equals("case", ignoreCase = true) || (product.hasBulkPricing() && unitType.equals(product.getEffectiveBulkUnit(), ignoreCase = true))) {
            return if (unitPrice > 0.0) unitPrice else product.getEffectiveBulkPrice()
        }
        return if (isUsingWholesale(isGlobalWholesale)) {
            product.getEffectiveWholesalePrice()
        } else {
            unitPrice
        }
    }

    fun getEffectiveMrp(): Double {
        if (isVariant) {
            val v = product.findVariantByBarcode(variantBarcode ?: "")
            if (v != null && v.mrp > 0.0) return v.mrp
            return unitPrice
        }
        return product.getMrpPerUnit(unitType)
    }

    fun getMrpSubtotal(): Double {
        if (isVariant) {
            return quantity * getEffectiveMrp()
        }
        return product.calculateMrpPrice(quantity, unitType)
    }

    fun getItemMrpSavings(isGlobalWholesale: Boolean = false): Double {
        if (isFreeGift) {
            return getMrpSubtotal().coerceAtLeast(product.calculatePrice(quantity, unitType))
        }
        if (isVariant) {
            return (getMrpSubtotal() - getSubtotal(isGlobalWholesale)).coerceAtLeast(0.0)
        }
        return (getMrpSubtotal() - getSubtotal(isGlobalWholesale)).coerceAtLeast(0.0)
    }

    fun getMrpSavings(isGlobalWholesale: Boolean = false): Double {
        return getItemMrpSavings(isGlobalWholesale)
    }

    fun getUnitCost(): Double {
        if (isVariant) {
            val v = product.findVariantByBarcode(variantBarcode ?: "")
            if (v != null && v.costPrice > 0.0) return v.costPrice
            if (variantQuantity != null && variantQuantity > 0.0) {
                val vUnit = variantUnitType ?: unitType
                val baseCostPerUnit = product.costPrice
                val baseUnitsPerPack = product.convertQuantityToBaseUnit(variantQuantity, vUnit)
                return baseCostPerUnit * baseUnitsPerPack
            }
        }
        return product.getCostPriceForUnit(unitType)
    }

    fun getMinSafeUnitPrice(): Double {
        return getUnitCost()
    }

    fun isSellingBelowCost(isGlobalWholesale: Boolean = false): Boolean {
        if (isFreeGift) return false
        return getEffectiveUnitPrice(isGlobalWholesale) < getUnitCost()
    }

    fun getUnitDiscountOnMrp(isGlobalWholesale: Boolean = false): Double {
        if (isVariant) return 0.0
        return (getEffectiveMrp() - getEffectiveUnitPrice(isGlobalWholesale)).coerceAtLeast(0.0)
    }

    fun getSubtotal(isGlobalWholesale: Boolean = false): Double {
        if (isFreeGift) {
            return 0.0
        }
        if (isVariant) {
            return quantity * unitPrice
        }
        return product.calculatePrice(
            qty = quantity,
            unit = unitType,
            isWholesale = isUsingWholesale(isGlobalWholesale),
            customUnitPrice = if (unitType.equals("box", ignoreCase = true) || unitType.equals("case", ignoreCase = true) || (product.hasBulkPricing() && unitType.equals(product.getEffectiveBulkUnit(), ignoreCase = true))) {
                if (unitPrice > 0.0) unitPrice else product.getEffectiveBulkPrice()
            } else {
                unitPrice
            }
        )
    }

    val subtotal: Double
        get() = getSubtotal(false)

    val totalCost: Double
        get() {
            if (isVariant && variantQuantity != null && variantQuantity > 0.0) {
                val vUnit = variantUnitType ?: unitType
                val baseUnitsPerPack = product.convertQuantityToBaseUnit(variantQuantity, vUnit)
                return quantity * (baseUnitsPerPack * product.costPrice)
            }
            return product.calculateCost(quantity, unitType)
        }
}

data class DiscountValidationResult(
    val isValid: Boolean,
    val requestedDiscount: Double,
    val maxSafeDiscount: Double,
    val currentProfitBeforeDiscount: Double,
    val estimatedProfitAfterDiscount: Double,
    val lossAmount: Double = 0.0,
    val warningMessage: String? = null
)

enum class ReportPeriod {
    TODAY, THIS_WEEK, THIS_MONTH, ALL_TIME
}

class StoreViewModel(application: Application) : AndroidViewModel(application) {

    val signInRepository = SignInRepository()
    val authManager = AuthManager(signInRepository)
    val currentUser: StateFlow<AuthUser?> = signInRepository.currentUser
    val googleSignInStatus: StateFlow<GoogleSignInAvailability?> = signInRepository.lastGoogleSignInStatus
    var isAuthLoading by mutableStateOf(false)
    var authError by mutableStateOf<String?>(null)

    // Backup Password Prompt Dialog State
    var showSetBackupPasswordDialog by mutableStateOf(false)
    var isFirstTimeBackupPasswordPrompt by mutableStateOf(false)

    fun openSetBackupPasswordDialog(isFirstTimePrompt: Boolean = false) {
        isFirstTimeBackupPasswordPrompt = isFirstTimePrompt
        showSetBackupPasswordDialog = true
    }

    fun closeSetBackupPasswordDialog() {
        showSetBackupPasswordDialog = false
        isFirstTimeBackupPasswordPrompt = false
    }

    private val _currentFirestoreUserRole = MutableStateFlow<FirestoreUserRole?>(null)
    val currentFirestoreUserRole: StateFlow<FirestoreUserRole?> = _currentFirestoreUserRole.asStateFlow()

    private val _cloudProfitReports = MutableStateFlow<List<FirestoreProfitReport>>(emptyList())
    val cloudProfitReports: StateFlow<List<FirestoreProfitReport>> = _cloudProfitReports.asStateFlow()

    private val _cloudReportErrorMessage = MutableStateFlow<String?>(null)
    val cloudReportErrorMessage: StateFlow<String?> = _cloudReportErrorMessage.asStateFlow()

    private val _isSyncingAllData = MutableStateFlow(false)
    val isSyncingAllData: StateFlow<Boolean> = _isSyncingAllData.asStateFlow()

    private val _lastSyncSummary = MutableStateFlow<com.example.data.repository.SyncSummary?>(null)
    val lastSyncSummary: StateFlow<com.example.data.repository.SyncSummary?> = _lastSyncSummary.asStateFlow()

    private val _lastSyncTimestamp = MutableStateFlow(System.currentTimeMillis())
    val lastSyncTimestamp: StateFlow<Long> = _lastSyncTimestamp.asStateFlow()

    private val backupPrefs = application.getSharedPreferences("auto_backup_prefs", android.content.Context.MODE_PRIVATE)

    val googleDriveManager = GoogleDriveBackupManager(application)

    private val _driveBackupsList = MutableStateFlow<List<DriveBackupFile>>(emptyList())
    val driveBackupsList: StateFlow<List<DriveBackupFile>> = _driveBackupsList.asStateFlow()

    private val _isDriveLoading = MutableStateFlow(false)
    val isDriveLoading: StateFlow<Boolean> = _isDriveLoading.asStateFlow()

    private val _driveStatusMessage = MutableStateFlow<String?>(null)
    val driveStatusMessage: StateFlow<String?> = _driveStatusMessage.asStateFlow()

    private val _driveAccountEmail = MutableStateFlow<String?>(
        googleDriveManager.savedAccountEmail 
            ?: googleDriveManager.getAvailableGoogleAccounts().firstOrNull() 
            ?: "shuvajitsow384@gmail.com"
    )
    val driveAccountEmail: StateFlow<String?> = _driveAccountEmail.asStateFlow()

    private val _driveAuthIntent = MutableStateFlow<android.content.Intent?>(null)
    val driveAuthIntent: StateFlow<android.content.Intent?> = _driveAuthIntent.asStateFlow()

    fun clearDriveAuthIntent() {
        _driveAuthIntent.value = null
    }

    private val _lastDriveBackupTimestamp = MutableStateFlow(googleDriveManager.lastBackupTime)
    val lastDriveBackupTimestamp: StateFlow<Long> = _lastDriveBackupTimestamp.asStateFlow()

    private val _isAutoSyncEnabled = MutableStateFlow(backupPrefs.getBoolean("auto_sync_enabled", true))
    val isAutoSyncEnabled: StateFlow<Boolean> = _isAutoSyncEnabled.asStateFlow()

    private val _autoSyncIntervalMinutes = MutableStateFlow(backupPrefs.getInt("auto_sync_interval", 5))
    val autoSyncIntervalMinutes: StateFlow<Int> = _autoSyncIntervalMinutes.asStateFlow()

    private val _isAutoCloudBackupEnabled = MutableStateFlow(backupPrefs.getBoolean("auto_cloud_backup_enabled", true))
    val isAutoCloudBackupEnabled: StateFlow<Boolean> = _isAutoCloudBackupEnabled.asStateFlow()

    private val _isAutoLocalBackupEnabled = MutableStateFlow(backupPrefs.getBoolean("auto_local_backup_enabled", true))
    val isAutoLocalBackupEnabled: StateFlow<Boolean> = _isAutoLocalBackupEnabled.asStateFlow()

    private val _lastAutoBackupTimestamp = MutableStateFlow(backupPrefs.getLong("last_auto_backup_time", 0L))
    val lastAutoBackupTimestamp: StateFlow<Long> = _lastAutoBackupTimestamp.asStateFlow()

    private val _localBackupSnapshots = MutableStateFlow<List<com.example.utils.LocalBackupFileInfo>>(emptyList())
    val localBackupSnapshots: StateFlow<List<com.example.utils.LocalBackupFileInfo>> = _localBackupSnapshots.asStateFlow()

    private val _cloudBackupSnapshots = MutableStateFlow<List<com.example.data.firestore.CloudBackupSnapshotInfo>>(emptyList())
    val cloudBackupSnapshots: StateFlow<List<com.example.data.firestore.CloudBackupSnapshotInfo>> = _cloudBackupSnapshots.asStateFlow()

    private val _isBackupProcessing = MutableStateFlow(false)
    val isBackupProcessing: StateFlow<Boolean> = _isBackupProcessing.asStateFlow()

    private val _isBackfillingPublicLedger = MutableStateFlow(false)
    val isBackfillingPublicLedger: StateFlow<Boolean> = _isBackfillingPublicLedger.asStateFlow()

    private val _publicLedgerBackfillResult = MutableStateFlow<com.example.data.firestore.PublicLedgerBackfillResult?>(null)
    val publicLedgerBackfillResult: StateFlow<com.example.data.firestore.PublicLedgerBackfillResult?> = _publicLedgerBackfillResult.asStateFlow()

    fun clearPublicLedgerBackfillResult() {
        _publicLedgerBackfillResult.value = null
    }

    fun runPublicKhataBackfill(onComplete: (com.example.data.firestore.PublicLedgerBackfillResult) -> Unit = {}) {
        if (_isBackfillingPublicLedger.value) return
        viewModelScope.launch {
            _isBackfillingPublicLedger.value = true
            val result = repository.firestoreManager.backfillCustomerPublicLedgers()
            _isBackfillingPublicLedger.value = false
            _publicLedgerBackfillResult.value = result
            onComplete(result)
        }
    }

    // --- Bluetooth Printer State ---
    var pairedPrinters = mutableStateListOf<EscPosPrinter.BluetoothPrinterDevice>()
    var selectedPrinterAddress by mutableStateOf<String?>(null)
    var printerConnectionStatus by mutableStateOf("DISCONNECTED") // DISCONNECTED, CONNECTING, CONNECTED, ERROR
    var isConnectingPrinter by mutableStateOf(false)
    var isTestPrinting by mutableStateOf(false)
    var printerStatusMessage by mutableStateOf("")

    // --- Reports State ---
    var selectedReportPeriod by mutableStateOf(ReportPeriod.TODAY)
    var pnlReport by mutableStateOf<ProfitAndLossReport?>(null)
    var stockOutReport by mutableStateOf<StockOutReportSummary?>(null)
    var comprehensiveReport by mutableStateOf<com.example.utils.ComprehensiveBusinessReport?>(null)
    var isComprehensiveReportLoading by mutableStateOf(false)

    // Shortcut action triggers
    var triggerShowDailySummary by mutableStateOf(false)
    var triggerShowAddExpense by mutableStateOf(false)
    var triggerShowAddProduct by mutableStateOf(false)

    // Staff Duplicate Merge State
    var isMergingDuplicateStaff by mutableStateOf(false)
    var staffMergeResult by mutableStateOf<com.example.data.repository.StaffMergeResult?>(null)
    var showStaffMergeResultDialog by mutableStateOf(false)

    val repository: StoreRepository

    val allFirestoreUsers: StateFlow<List<FirestoreUserRole>>
    val allPreassignedRoles: StateFlow<List<FirestoreUserRole>>

    val pendingStaffRequests: StateFlow<List<FirestoreUserRole>>
    val pendingStaffCount: StateFlow<Int>

    private val _isDataReady = MutableStateFlow(false)
    val isDataReady: StateFlow<Boolean> = _isDataReady.asStateFlow()

    init {
        val db = AppDatabase.getDatabase(application)
        repository = StoreRepository(db, application)
        allFirestoreUsers = repository.firestoreManager.getAllUsersFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
        allPreassignedRoles = repository.firestoreManager.getPreassignedRolesFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
        pendingStaffRequests = allFirestoreUsers.map { users ->
            users.filter { it.isPendingApproval }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
        pendingStaffCount = pendingStaffRequests.map { it.size }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.seedSampleDataIfEmpty()
            } catch (e: Throwable) {
                android.util.Log.w("StoreViewModel", "Seed data note: ${e.message}")
            } finally {
                _isDataReady.value = true
            }

            // Defer real-time network sync listeners after first frame has rendered to eliminate startup UI jank
            kotlinx.coroutines.delay(1000L)
            repository.startFirestoreSync(viewModelScope)

            // Defer non-essential background migrations and printer/report caching after first screen render
            kotlinx.coroutines.delay(1000L)
            try {
                loadPairedPrinters()
                loadPnlReport(ReportPeriod.TODAY)
                repository.autoMigrateLegacyImageUris()
                repository.syncInitialProductsToFirestore()
                refreshBackupSnapshots()

                // Trigger background sync with cooldown check
                val prefs = application.getSharedPreferences("sync_prefs", android.content.Context.MODE_PRIVATE)
                val lastSync = prefs.getLong("last_auto_sync_time", 0L)
                val now = System.currentTimeMillis()
                val cooldownMs = 15 * 60 * 1000L // 15 minutes cooldown window
                if (now - lastSync >= cooldownMs) {
                    prefs.edit().putLong("last_auto_sync_time", now).apply()
                    triggerImmediateAutoSync()
                } else {
                    android.util.Log.d(
                        "StoreViewModel",
                        "Startup auto-sync dropped: within 15min cooldown window (${(now - lastSync) / 1000}s elapsed)"
                    )
                }

                // Initialize WorkManager Periodic Background Sync layer
                if (_isAutoSyncEnabled.value) {
                    SyncWorkManager.schedulePeriodicSync(
                        application,
                        _autoSyncIntervalMinutes.value.toLong()
                    )
                }
            } catch (e: Throwable) {
                android.util.Log.w("StoreViewModel", "Deferred background tasks note: ${e.message}")
            }
        }

        // Observe network state: when internet becomes available, debounce and auto-sync with 15-minute cooldown
        viewModelScope.launch {
            @OptIn(FlowPreview::class)
            com.example.utils.NetworkMonitor.isOnlineFlow
                .distinctUntilChanged()
                .filter { it }
                .debounce(30_000L) // 30-second debounce to prevent sync stacking during flaky mobile connectivity
                .collect {
                    if (_isAutoSyncEnabled.value) {
                        val prefs = application.getSharedPreferences("sync_prefs", android.content.Context.MODE_PRIVATE)
                        val lastSync = prefs.getLong("last_auto_sync_time", 0L)
                        val now = System.currentTimeMillis()
                        val cooldownMs = 15 * 60 * 1000L // 15 minutes cooldown window
                        if (now - lastSync >= cooldownMs) {
                            prefs.edit().putLong("last_auto_sync_time", now).apply()
                            triggerImmediateAutoSync()
                        } else {
                            android.util.Log.d(
                                "StoreViewModel",
                                "Network auto-sync dropped: within 15min cooldown window (${(now - lastSync) / 1000}s elapsed)"
                            )
                        }
                    }
                }
        }

        // Observe WorkManager periodic and one-time sync status
        viewModelScope.launch {
            SyncWorkManager.getPeriodicSyncWorkInfoFlow(application).collect { workInfo ->
                if (workInfo != null && workInfo.state == androidx.work.WorkInfo.State.SUCCEEDED) {
                    val ts = workInfo.outputData.getLong(SyncWorker.KEY_TIMESTAMP, System.currentTimeMillis())
                    _lastSyncTimestamp.value = ts
                }
            }
        }
        viewModelScope.launch {
            SyncWorkManager.getOneTimeSyncWorkInfoFlow(application).collect { workInfo ->
                if (workInfo != null && workInfo.state == androidx.work.WorkInfo.State.SUCCEEDED) {
                    val ts = workInfo.outputData.getLong(SyncWorker.KEY_TIMESTAMP, System.currentTimeMillis())
                    _lastSyncTimestamp.value = ts
                }
            }
        }

        // Automated Periodic Backup Loop (every 30 mins checks if >= 6 hours since last snapshot)
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(30 * 60 * 1000L)
                // Auto backup check (create snapshot if enabled and >= 6 hours since last backup)
                val now = System.currentTimeMillis()
                val lastBackup = _lastAutoBackupTimestamp.value
                val minIntervalMillis = 6 * 60 * 60 * 1000L
                if ((_isAutoCloudBackupEnabled.value || _isAutoLocalBackupEnabled.value) && (now - lastBackup >= minIntervalMillis)) {
                    createAutoBackupSnapshotInternal()
                }
            }
        }

        // Listen for Firebase Auth user changes and sync Firestore RBAC role
        viewModelScope.launch {
            currentUser.collect { authUser ->
                if (authUser != null) {
                    val cleanEmail = authUser.email?.trim()?.lowercase()
                    val defaultRole = if (cleanEmail != null && cleanEmail in PERMANENT_ADMIN_EMAILS) {
                        FirestoreUserRole.createAdmin(
                            uid = authUser.uid,
                            email = authUser.email,
                            displayName = authUser.displayName
                        )
                    } else {
                        FirestoreUserRole.createAdmin(
                            uid = authUser.uid,
                            email = authUser.email,
                            displayName = authUser.displayName ?: authUser.email?.substringBefore("@") ?: "Store Owner"
                        )
                    }
                    if (_currentFirestoreUserRole.value == null) {
                        _currentFirestoreUserRole.value = defaultRole
                        applyFirestoreRoleToStaffSession(defaultRole)
                    }

                    val resolvedRole = try {
                        kotlinx.coroutines.withTimeoutOrNull(6000L) {
                            repository.firestoreManager.initOrFetchUserRole(
                                uid = authUser.uid,
                                email = authUser.email,
                                displayName = authUser.displayName
                            )
                        } ?: run {
                            Log.w("StoreViewModel", "initOrFetchUserRole timed out after 6s. Using fallback role.")
                            defaultRole
                        }
                    } catch (e: Exception) {
                        Log.e("StoreViewModel", "Error fetching user role: ${e.message}", e)
                        defaultRole
                    }
                    _currentFirestoreUserRole.value = resolvedRole
                    applyFirestoreRoleToStaffSession(resolvedRole)
                    repository.syncAppUsersToEmployees(listOf(resolvedRole))

                    // Real-time listener for this user's role profile in Firestore
                    launch {
                        repository.firestoreManager.listenUserRole(authUser.uid, authUser.email).collect { updatedRole ->
                            if (updatedRole != null) {
                                _currentFirestoreUserRole.value = updatedRole
                                applyFirestoreRoleToStaffSession(updatedRole)
                                repository.syncAppUsersToEmployees(listOf(updatedRole))
                            }
                        }
                    }

                    // Real-time live snapshot listener for Profit & Loss reports across devices (< 3s latency)
                    launch {
                        repository.firestoreManager.getProfitReportsFlow(resolvedRole).collect { liveReports ->
                            _cloudProfitReports.value = liveReports
                        }
                    }
                } else {
                    _currentFirestoreUserRole.value = null
                    _cloudProfitReports.value = emptyList()
                    _cloudReportErrorMessage.value = null
                    StaffManager.clearStaffSession()
                }
            }
        }

        // Monitor low stock items and dispatch local notifications when stock falls below threshold
        viewModelScope.launch {
            repository.lowStockProducts.collect { lowList ->
                lowList.forEach { product ->
                    NotificationHelper.checkAndNotifyLowStock(
                        context = getApplication(),
                        productId = product.id,
                        productName = product.getDisplayName(LanguageManager.isBengali),
                        currentStock = product.currentStock,
                        threshold = product.lowStockThreshold,
                        unitType = product.unitType
                    )
                }
            }
        }

        // Maintain active staff session validity with local employee database
        viewModelScope.launch {
            repository.allEmployees.collect { employees ->
                val savedId = StaffManager.savedStaffId
                val currentStaff = StaffManager.activeStaff
                if (currentStaff != null) {
                    val matching = employees.find { it.id == currentStaff.id }
                    if (matching == null || !matching.isActive) {
                        StaffManager.loginAsOwner()
                    } else if (matching != currentStaff) {
                        StaffManager.loginAsStaff(matching)
                    }
                } else if (savedId != null) {
                    val matching = employees.find { it.id == savedId && it.isActive }
                    if (matching != null) {
                        StaffManager.loginAsStaff(matching)
                    } else {
                        StaffManager.loginAsOwner()
                    }
                }
            }
        }

        // Initialize and refresh home screen widget stats on launch
        viewModelScope.launch {
            com.example.widget.TodaySalesWidgetProvider.triggerWidgetUpdate(application)
        }

        // Audit and backfill missing opening balances for customers and suppliers (deferred)
        viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(2000L)
            repository.auditAndBackfillOpeningBalances()
        }
    }

    private fun applyFirestoreRoleToStaffSession(role: FirestoreUserRole) {
        if (role.isPermanentAdmin || role.isAdmin) {
            StaffManager.loginAsOwner()
        } else if (role.isUnrecognized) {
            val unrecognizedEmp = Employee(
                id = "cloud_" + role.uid,
                name = role.displayName ?: role.email ?: "Unrecognized User",
                email = role.email ?: "",
                role = "UNRECOGNIZED",
                canMakeSales = false,
                canViewCostPrice = false,
                canManageInventory = false,
                canViewKhata = false,
                canManageExpenses = false,
                canViewReports = false,
                canAccessSettings = false,
                canGiveDiscount = false,
                canDeleteSales = false
            )
            StaffManager.loginAsStaff(unrecognizedEmp)
        } else {
            val emp = Employee(
                id = "cloud_" + role.uid,
                name = role.displayName ?: role.email ?: "Employee",
                email = role.email ?: "",
                role = role.role,
                canMakeSales = role.effectiveCanMakeSales,
                canViewCostPrice = role.effectiveCanViewCostPrice,
                canManageInventory = role.effectiveCanManageInventory,
                canViewKhata = role.effectiveCanViewKhata,
                canManageExpenses = role.effectiveCanManageExpenses,
                canViewReports = role.effectiveCanViewReports, // Strictly restricted for regular employees
                canAccessSettings = role.effectiveCanAccessSettings,
                canGiveDiscount = role.effectiveCanGiveDiscount,
                canDeleteSales = role.effectiveCanDeleteSales
            )
            StaffManager.loginAsStaff(emp)
        }
    }

    fun refreshFirestoreSync() {
        viewModelScope.launch {
            repository.syncInitialProductsToFirestore()
            loadPnlReport(selectedReportPeriod)
            repository.mergeDuplicateStaffProfiles()
        }
    }

    fun sendTestLowStockNotification() {
        val lowList = lowStockProducts.value
        if (lowList.isNotEmpty()) {
            val sample = lowList.first()
            NotificationHelper.checkAndNotifyLowStock(
                context = getApplication(),
                productId = sample.id,
                productName = sample.getDisplayName(LanguageManager.isBengali),
                currentStock = sample.currentStock,
                threshold = sample.lowStockThreshold,
                unitType = sample.unitType,
                forceNotify = true
            )
        } else {
            NotificationHelper.checkAndNotifyLowStock(
                context = getApplication(),
                productId = "demo_low_stock",
                productName = if (LanguageManager.isBengali) "গোবিন্দভোগ চাল" else "Gobindobhog Rice",
                currentStock = 2.0,
                threshold = 5.0,
                unitType = "kg",
                forceNotify = true
            )
        }
    }

    val allProducts: StateFlow<List<Product>> = repository.allProducts
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allBatches: StateFlow<List<ProductBatch>> = repository.allBatches
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val lowStockProducts: StateFlow<List<Product>> = repository.lowStockProducts
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val expiredProducts: StateFlow<List<Product>> = repository.allProducts
        .map { list -> list.filter { it.getExpiryStatus() == ExpiryStatus.EXPIRED } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val expiringSoonProducts: StateFlow<List<Product>> = repository.allProducts
        .map { list -> list.filter { it.getExpiryStatus() == ExpiryStatus.EXPIRING_SOON } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allCustomers: StateFlow<List<Customer>> = repository.allCustomers
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allSuppliers: StateFlow<List<Supplier>> = repository.allSuppliers
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allSales: StateFlow<List<SaleWithItems>> = repository.allSales
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val heldSales: StateFlow<List<SaleWithItems>> = repository.heldSales
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allReturns: StateFlow<List<SaleReturnWithItems>> = repository.allReturns
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allPurchases: StateFlow<List<PurchaseWithItems>> = repository.allPurchases
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allExpenses: StateFlow<List<Expense>> = repository.allExpenses
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allStockOuts: StateFlow<List<StockOutEntry>> = repository.allStockOuts
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allLedgerEntries: StateFlow<List<LedgerEntry>> = repository.allLedgerEntries
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allEmployees: StateFlow<List<Employee>> = repository.allEmployees
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeEmployees: StateFlow<List<Employee>> = repository.activeEmployees
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allOffers: StateFlow<List<Offer>> = repository.allOffers
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val activeOffers: StateFlow<List<Offer>> = repository.activeOffers
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val allPaymentClaims: StateFlow<List<PaymentClaim>> = repository.allPaymentClaims
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingPaymentClaims: StateFlow<List<PaymentClaim>> = repository.pendingPaymentClaims
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingClaimsCount: StateFlow<Int> = repository.pendingPaymentClaims
        .map { it.size }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Online Ordering Flows & State (Phase 3)
    val allOnlineOrders: StateFlow<List<com.example.data.models.Order>> = repository.getOrdersFlow()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingOrdersCount: StateFlow<Int> = allOnlineOrders
        .map { orders -> orders.count { it.status == com.example.data.models.OrderStatus.PLACED } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    var activeOrderInAppAlert by mutableStateOf<com.example.data.models.Order?>(null)
    private val knownPlacedOrderIds = mutableSetOf<String>()
    private var isInitialOrdersLoad = true

    init {
        // Safe foreground listener for new incoming online orders (runs after all properties are initialized)
        viewModelScope.launch {
            try {
                allOnlineOrders.collect { orders ->
                    val placedOrders = orders.filter { it.status == com.example.data.models.OrderStatus.PLACED }
                    if (isInitialOrdersLoad) {
                        knownPlacedOrderIds.addAll(placedOrders.map { it.id })
                        isInitialOrdersLoad = false
                    } else {
                        val newOrders = placedOrders.filter { it.id !in knownPlacedOrderIds }
                        if (newOrders.isNotEmpty()) {
                            knownPlacedOrderIds.addAll(newOrders.map { it.id })
                            val latestOrder = newOrders.first()
                            activeOrderInAppAlert = latestOrder
                            try {
                                com.example.utils.NotificationHelper.playOrderAlertSound(application)
                                com.example.utils.NotificationHelper.notifyNewOnlineOrder(
                                    application,
                                    latestOrder,
                                    com.example.utils.LanguageManager.isBengali
                                )
                            } catch (e: Throwable) {
                                android.util.Log.w("StoreViewModel", "Order alert sound note: ${e.message}")
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                android.util.Log.w("StoreViewModel", "Online orders collect note: ${e.message}")
            }
        }
    }

    fun dismissOrderInAppAlert() {
        activeOrderInAppAlert = null
    }

    fun acceptOnlineOrder(
        order: com.example.data.models.Order,
        allowShortageOverride: Boolean = false,
        onShortage: (List<com.example.data.models.InsufficientStockItem>) -> Unit,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                // Live stock availability check before confirming (unless overridden)
                if (!allowShortageOverride) {
                    val shortages = repository.checkOrderStockAvailability(order)
                    if (shortages.isNotEmpty()) {
                        onShortage(shortages)
                        return@launch
                    }
                }
                val staffActor = com.example.utils.StaffManager.getActiveStaffOrOwnerName()
                val success = repository.updateOrderStatus(
                    orderId = order.id,
                    newStatus = com.example.data.models.OrderStatus.CONFIRMED,
                    staffName = staffActor,
                    customerUid = order.customerUid
                )
                if (success) {
                    onSuccess()
                } else {
                    onError("Failed to update order status")
                }
            } catch (e: Exception) {
                onError(e.message ?: "Failed to accept order")
            }
        }
    }

    fun cancelOnlineOrder(
        order: com.example.data.models.Order,
        reason: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val success = repository.updateOrderStatus(
                    orderId = order.id,
                    newStatus = com.example.data.models.OrderStatus.CANCELLED,
                    cancelReason = reason,
                    customerUid = order.customerUid
                )
                if (success) {
                    onSuccess()
                } else {
                    onError("Failed to cancel order")
                }
            } catch (e: Exception) {
                onError(e.message ?: "Failed to cancel order")
            }
        }
    }

    fun markOnlineOrderReadyOrDispatched(
        order: com.example.data.models.Order,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val nextStatus = if (order.fulfillmentType == com.example.data.models.FulfillmentType.DELIVERY) {
                    com.example.data.models.OrderStatus.OUT_FOR_DELIVERY
                } else {
                    com.example.data.models.OrderStatus.READY
                }
                val success = repository.updateOrderStatus(
                    orderId = order.id,
                    newStatus = nextStatus,
                    customerUid = order.customerUid
                )
                if (success) {
                    onSuccess()
                } else {
                    onError("Failed to update status")
                }
            } catch (e: Exception) {
                onError(e.message ?: "Failed to update status")
            }
        }
    }

    fun markOnlineOrderCompleted(
        order: com.example.data.models.Order,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val staffActor = com.example.utils.StaffManager.getActiveStaffOrOwnerName()
                val success = repository.updateOrderStatus(
                    orderId = order.id,
                    newStatus = com.example.data.models.OrderStatus.COMPLETED,
                    staffName = staffActor,
                    customerUid = order.customerUid
                )
                if (success) {
                    onSuccess()
                } else {
                    onError("Failed to mark order as completed")
                }
            } catch (e: Exception) {
                onError(e.message ?: "Failed to mark order as completed")
            }
        }
    }

    fun markOnlineOrderPaid(
        order: com.example.data.models.Order,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val success = repository.updateOrderPaymentStatus(order.id, com.example.data.models.OrderPaymentStatus.PAID)
                if (success) {
                    onSuccess()
                } else {
                    onError("Failed to update payment status")
                }
            } catch (e: Exception) {
                onError(e.message ?: "Failed to update payment status")
            }
        }
    }

    fun fulfillAndCompleteOnlineOrder(
        order: com.example.data.models.Order,
        finalPaymentMethod: String? = null,
        creditCustomer: com.example.data.local.entities.Customer? = null,
        ownerOverrideCreditLimit: Boolean = false,
        allowNegativeStockOverride: Boolean = true,
        onShortage: (List<com.example.data.models.InsufficientStockItem>) -> Unit,
        onCreditLimitExceeded: ((CreditLimitExceededException) -> Unit)? = null,
        onSuccess: (saleId: String) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val staffActor = com.example.utils.StaffManager.getActiveStaffOrOwnerName()
                val result = repository.fulfillOnlineOrder(
                    order = order,
                    finalPaymentMethod = finalPaymentMethod,
                    creditCustomer = creditCustomer,
                    ownerOverrideCreditLimit = ownerOverrideCreditLimit,
                    dispatchedByStaffName = staffActor,
                    allowNegativeStockOverride = allowNegativeStockOverride
                )
                if (result.isSuccess) {
                    onSuccess(result.getOrNull() ?: "")
                } else {
                    val ex = result.exceptionOrNull()
                    when (ex) {
                        is com.example.data.models.InsufficientStockException -> onShortage(ex.items)
                        is CreditLimitExceededException -> {
                            if (onCreditLimitExceeded != null) {
                                onCreditLimitExceeded(ex)
                            } else {
                                onError("Credit limit exceeded for ${ex.customerName} (Limit: ₹${"%.2f".format(ex.creditLimit)}, Current: ₹${"%.2f".format(ex.currentBalance)})")
                            }
                        }
                        else -> onError(ex?.message ?: "Order fulfillment failed")
                    }
                }
            } catch (e: com.example.data.models.InsufficientStockException) {
                onShortage(e.items)
            } catch (e: CreditLimitExceededException) {
                if (onCreditLimitExceeded != null) {
                    onCreditLimitExceeded(e)
                } else {
                    onError("Credit limit exceeded for ${e.customerName}")
                }
            } catch (e: Exception) {
                onError(e.message ?: "Order fulfillment failed")
            }
        }
    }

    // ==========================================
    // GOOGLE SIGN-IN CUSTOMER ACCOUNTS & PENDING LINKS
    // ==========================================

    val pendingCustomerLinks: StateFlow<List<com.example.data.models.PendingLinkRequest>> =
        repository.firestoreManager.listenPendingCustomerLinks()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingCustomerLinksCount: StateFlow<Int> = pendingCustomerLinks
        .map { list -> list.count { it.isPending } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    suspend fun getLiveCustomerDetails(customerId: String): com.example.data.models.LiveCustomerDetails {
        return repository.firestoreManager.getLiveCustomerDetails(customerId)
    }

    fun reviewPendingCustomerLink(
        requestId: String,
        approve: Boolean,
        onResult: (Boolean, String) -> Unit
    ) {
        viewModelScope.launch {
            val reviewer = currentUser.value?.email ?: StaffManager.activeStaff?.name ?: "Shopkeeper"
            val result = repository.firestoreManager.reviewPendingCustomerLink(requestId, approve, reviewer)
            if (result.isSuccess) {
                onResult(true, if (approve) "Khata link approved successfully!" else "Link request declined.")
            } else {
                onResult(false, result.exceptionOrNull()?.message ?: "Failed to process link request.")
            }
        }
    }

    fun confirmPaymentClaim(
        claim: PaymentClaim,
        note: String? = null,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val operator = StaffManager.activeStaff?.name ?: currentUser.value?.displayName ?: "Shopkeeper"
            val result = repository.confirmPaymentClaim(claim, note, operator)
            if (result.isSuccess) {
                // Instantly notify UI to dismiss dialog and show success feedback
                onSuccess()

                // Auto-confirm SMS to customer in background without blocking UI
                if (StoreInfoManager.autoSendCreditSms) {
                    launch(Dispatchers.IO) {
                        try {
                            val customer = repository.getCustomerById(claim.customerId) ?: allCustomers.value.find { it.id == claim.customerId }
                            val custPhone = customer?.phone?.trim()?.ifBlank { null } ?: claim.customerPhone.trim().ifBlank { null }
                            val custName = customer?.name ?: claim.customerName.ifBlank { "Customer" }
                            val remainingBalance = customer?.balance ?: 0.0

                            if (!custPhone.isNullOrBlank()) {
                                val khataUrl = if (customer != null) {
                                    val token = if (!customer.shareToken.isNullOrBlank()) {
                                        customer.shareToken
                                    } else {
                                        repository.getOrCreateCustomerShareToken(customer)
                                    }
                                    StoreInfoManager.buildCustomerKhataUrl(token)
                                } else ""

                                val confirmSms = SmsHelper.generatePaymentClaimConfirmedSms(
                                    customerName = custName,
                                    storeName = StoreInfoManager.storeName,
                                    confirmedAmount = claim.claimedAmount,
                                    remainingBalance = remainingBalance,
                                    khataUrl = khataUrl.ifBlank { null },
                                    isBengali = StoreInfoManager.isSmsBengali()
                                )

                                if (SmsHelper.hasSmsPermission(getApplication())) {
                                    val sendRes = SmsHelper.sendDirectSms(getApplication(), custPhone, confirmSms)
                                    if (sendRes.isSuccess) {
                                        lastSmsStatusMessage = "📲 Payment confirmation SMS sent to $custName ($custPhone)"
                                        android.util.Log.i("StoreViewModel", "Auto-confirm payment SMS sent to $custPhone")
                                    } else {
                                        val err = sendRes.exceptionOrNull()?.message ?: "Send failed"
                                        lastSmsStatusMessage = "⚠️ Confirmation SMS not sent: $err"
                                        android.util.Log.w("StoreViewModel", "Auto-confirm payment SMS failed: $err")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("StoreViewModel", "Non-blocking auto-confirm SMS note: ${e.message}")
                        }
                    }
                }
            } else {
                onError(result.exceptionOrNull()?.message ?: "Failed to confirm payment")
            }
        }
    }

    fun rejectPaymentClaim(
        claim: PaymentClaim,
        reason: String,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val operator = StaffManager.activeStaff?.name ?: currentUser.value?.displayName ?: "Shopkeeper"
            val result = repository.rejectPaymentClaim(claim, reason, operator)
            if (result.isSuccess) {
                onSuccess()
            } else {
                onError(result.exceptionOrNull()?.message ?: "Failed to reject claim")
            }
        }
    }

    fun updatePaymentClaimStatus(
        customerId: String,
        claimId: String,
        status: String,
        rejectionReason: String? = null,
        onComplete: (Boolean) -> Unit = {}
    ) {
        viewModelScope.launch {
            val operator = StaffManager.activeStaff?.name ?: currentUser.value?.displayName ?: "Shopkeeper"
            val success = repository.updatePaymentClaimStatus(customerId, claimId, status, operator, rejectionReason)
            onComplete(success)
        }
    }

    // --- ATTENDANCE MANAGEMENT ---
    private val _selectedAttendanceDate = MutableStateFlow(
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    )
    val selectedAttendanceDate: StateFlow<String> = _selectedAttendanceDate.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val dailyAttendanceList: StateFlow<List<EmployeeAttendance>> = _selectedAttendanceDate
        .flatMapLatest { date -> repository.getAttendanceForDate(date) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allAttendance: StateFlow<List<EmployeeAttendance>> = repository.getAllAttendance()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSelectedAttendanceDate(date: String) {
        _selectedAttendanceDate.value = date
    }

    fun markAttendance(
        employee: Employee,
        status: String,
        checkInTime: String = "09:00 AM",
        checkOutTime: String = "08:00 PM",
        overtimeHours: Double = 0.0,
        notes: String = ""
    ) {
        viewModelScope.launch {
            val attendance = EmployeeAttendance(
                id = UUID.randomUUID().toString(),
                employeeId = employee.id,
                employeeName = employee.name,
                date = _selectedAttendanceDate.value,
                status = status,
                checkInTime = checkInTime,
                checkOutTime = checkOutTime,
                overtimeHours = overtimeHours,
                notes = notes
            )
            repository.saveAttendance(attendance)
        }
    }

    fun markAttendanceForDate(
        employeeId: String,
        employeeName: String,
        date: String,
        status: String,
        checkInTime: String = "09:00 AM",
        checkOutTime: String = "08:00 PM",
        onComplete: () -> Unit = {}
    ) {
        viewModelScope.launch {
            val attendance = EmployeeAttendance(
                id = UUID.randomUUID().toString(),
                employeeId = employeeId,
                employeeName = employeeName,
                date = date,
                status = status,
                checkInTime = checkInTime,
                checkOutTime = checkOutTime
            )
            repository.saveAttendance(attendance)
            onComplete()
        }
    }

    fun markAttendanceBatch(
        attendances: List<EmployeeAttendance>,
        onComplete: () -> Unit = {}
    ) {
        viewModelScope.launch {
            repository.saveAttendanceBatch(attendances)
            onComplete()
        }
    }

    suspend fun getSalaryBreakdownsForMonth(monthYear: String): List<SalaryBreakdown> {
        return repository.getSalaryBreakdownsForMonth(monthYear)
    }

    suspend fun getSalaryBreakdownForEmployee(employeeId: String, monthYear: String): SalaryBreakdown? {
        return repository.getSalaryBreakdownForEmployee(employeeId, monthYear)
    }

    fun recordSalaryDuesBatch(
        breakdowns: List<SalaryBreakdown>,
        onComplete: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            try {
                repository.recordSalaryDuesBatchFromBreakdowns(breakdowns)
                onComplete(true, "Successfully recorded attendance-calculated salary dues for ${breakdowns.size} staff members.")
            } catch (e: Exception) {
                onComplete(false, "Failed to record salary dues: ${e.message}")
            }
        }
    }

    fun markAllEmployeesPresent(date: String = _selectedAttendanceDate.value, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            repository.markAllEmployeesPresent(date)
            onComplete()
        }
    }

    fun deleteAttendanceRecord(employeeId: String, date: String = _selectedAttendanceDate.value) {
        viewModelScope.launch {
            repository.deleteAttendance(employeeId, date)
        }
    }

    // --- SALARY PAYMENTS & ADVANCES ---
    val allSalaryDues: StateFlow<List<EmployeeSalaryDue>> = repository.allSalaryDues
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allSalaryPayments: StateFlow<List<EmployeeSalaryPayment>> = repository.allSalaryPayments
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allAdvances: StateFlow<List<EmployeeAdvance>> = repository.allAdvances
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun recordSalaryDue(
        employeeId: String,
        employeeName: String,
        monthYear: String,
        dueAmount: Double,
        notes: String = "",
        onComplete: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            try {
                val due = EmployeeSalaryDue(
                    id = UUID.randomUUID().toString(),
                    employeeId = employeeId,
                    employeeName = employeeName,
                    monthYear = monthYear,
                    dueAmount = dueAmount,
                    dueDate = System.currentTimeMillis(),
                    notes = notes
                )
                repository.recordSalaryDue(due)
                onComplete(true, "Recorded ₹$dueAmount salary due for $monthYear ($employeeName)")
            } catch (e: Exception) {
                onComplete(false, "Failed to record salary due: ${e.message}")
            }
        }
    }

    fun accrueSalaryDueForMonth(
        monthYear: String,
        onComplete: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            try {
                repository.recordSalaryDueForMonthForAllStaff(monthYear)
                onComplete(true, "Salary dues accrued for $monthYear across active staff.")
            } catch (e: Exception) {
                onComplete(false, "Failed to accrue salary: ${e.message}")
            }
        }
    }

    fun deleteSalaryDueRecord(id: String, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            repository.deleteSalaryDue(id)
            onComplete()
        }
    }

    fun recordSalaryPayment(
        payment: EmployeeSalaryPayment,
        autoRecordExpense: Boolean = true,
        onComplete: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            try {
                repository.recordSalaryPayment(payment, autoRecordExpense)
                onComplete(true, "Salary payment of ₹${payment.netSalaryPaid} recorded for ${payment.employeeName}!")
            } catch (e: Exception) {
                onComplete(false, "Failed to record payment: ${e.message}")
            }
        }
    }

    fun recordEmployeeAdvance(
        employeeId: String,
        employeeName: String,
        amount: Double,
        reason: String,
        type: String = "ADVANCE", // "ADVANCE" (advance given to employee) or "REIMBURSEMENT" (employee paid expense for shop)
        paymentMode: String = "CASH",
        onComplete: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            try {
                val advance = EmployeeAdvance(
                    id = UUID.randomUUID().toString(),
                    employeeId = employeeId,
                    employeeName = employeeName,
                    type = type,
                    amount = amount,
                    reason = reason,
                    paymentMode = paymentMode,
                    date = System.currentTimeMillis()
                )
                repository.recordAdvance(advance)
                val msg = if (type == "REIMBURSEMENT") {
                    "Recorded ₹$amount reimbursement claim for $employeeName"
                } else {
                    "Advance of ₹$amount given to $employeeName successfully recorded."
                }
                onComplete(true, msg)
            } catch (e: Exception) {
                onComplete(false, "Failed to record entry: ${e.message}")
            }
        }
    }

    fun settleEmployeeAdvanceOrReimbursement(
        advance: EmployeeAdvance,
        settleNotes: String = "Settled in full",
        onComplete: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            try {
                val updated = advance.copy(
                    repaidAmount = advance.amount,
                    status = "SETTLED",
                    settledDate = System.currentTimeMillis(),
                    settlementNotes = settleNotes
                )
                repository.updateAdvance(updated)
                onComplete(true, "Record marked as settled!")
            } catch (e: Exception) {
                onComplete(false, "Failed to settle: ${e.message}")
            }
        }
    }

    fun updateEmployeeAdvance(advance: EmployeeAdvance, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            repository.updateAdvance(advance)
            onComplete()
        }
    }

    fun deleteEmployeeAdvance(id: String, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            repository.deleteAdvance(id)
            onComplete()
        }
    }

    fun deleteSalaryPaymentRecord(id: String, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            repository.deleteSalaryPayment(id)
            onComplete()
        }
    }

    fun saveEmployee(employee: Employee, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            repository.saveEmployee(employee)
            if (employee.email.isNotBlank()) {
                val firestoreRole = FirestoreUserRole(
                    uid = "",
                    email = employee.email.trim().lowercase(),
                    displayName = employee.name,
                    role = employee.role,
                    canViewReports = employee.canViewReports,
                    canViewCostPrice = employee.canViewCostPrice,
                    canManageInventory = employee.canManageInventory,
                    canViewKhata = employee.canViewKhata,
                    canManageExpenses = employee.canManageExpenses,
                    canAccessSettings = employee.canAccessSettings,
                    canMakeSales = employee.canMakeSales,
                    canGiveDiscount = employee.canGiveDiscount,
                    canDeleteSales = employee.canDeleteSales,
                    storeId = "default_store",
                    assignedBy = authManager.getCurrentAuthUser()?.email ?: "Store Owner"
                )
                repository.firestoreManager.savePreassignedRole(employee.email, firestoreRole)
            }
            onComplete()
        }
    }

    fun updateEmployee(employee: Employee, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            repository.updateEmployee(employee)
            if (employee.email.isNotBlank()) {
                val firestoreRole = FirestoreUserRole(
                    uid = "",
                    email = employee.email.trim().lowercase(),
                    displayName = employee.name,
                    role = employee.role,
                    canViewReports = employee.canViewReports,
                    canViewCostPrice = employee.canViewCostPrice,
                    canManageInventory = employee.canManageInventory,
                    canViewKhata = employee.canViewKhata,
                    canManageExpenses = employee.canManageExpenses,
                    canAccessSettings = employee.canAccessSettings,
                    canMakeSales = employee.canMakeSales,
                    canGiveDiscount = employee.canGiveDiscount,
                    canDeleteSales = employee.canDeleteSales,
                    storeId = "default_store",
                    assignedBy = authManager.getCurrentAuthUser()?.email ?: "Store Owner"
                )
                repository.firestoreManager.savePreassignedRole(employee.email, firestoreRole)
            }
            if (com.example.utils.StaffManager.activeStaff?.id == employee.id) {
                if (!employee.isActive) {
                    com.example.utils.StaffManager.loginAsOwner()
                } else {
                    com.example.utils.StaffManager.loginAsStaff(employee)
                }
            }
            onComplete()
        }
    }

    fun deleteEmployee(employee: Employee, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            repository.deleteEmployee(employee)
            if (employee.email.isNotBlank()) {
                repository.firestoreManager.deletePreassignedRole(employee.email)
            }
            if (com.example.utils.StaffManager.activeStaff?.id == employee.id) {
                com.example.utils.StaffManager.loginAsOwner()
            }
            onComplete()
        }
    }

    // --- Category Management ---
    private val categoryPrefs = application.getSharedPreferences("inventory_categories", android.content.Context.MODE_PRIVATE)
    private val defaultCategories = listOf(
        "Staples", "Oil & Masala", "Dairy", "Packaged Snacks", 
        "Beverages", "Personal Care", "Household", "Vegetables", "General"
    )

    private val _customCategories = MutableStateFlow<List<String>>(loadSavedCategories())
    val customCategories: StateFlow<List<String>> = _customCategories

    val allCategories: StateFlow<List<String>> = combine(
        allProducts,
        _customCategories
    ) { products, custom ->
        val productCats = products.map { it.category.trim() }.filter { it.isNotBlank() }
        (defaultCategories + custom + productCats).distinct().sorted()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), defaultCategories)

    private fun loadSavedCategories(): List<String> {
        val savedSet = categoryPrefs.getStringSet("custom_cat_set", null)
        return savedSet?.toList()?.sorted() ?: listOf("Dairy", "Packaged Snacks", "Bakery & Bread", "Frozen Foods", "Cosmetics", "Stationery")
    }

    private fun saveCategoriesToPrefs(list: List<String>) {
        categoryPrefs.edit().putStringSet("custom_cat_set", list.toSet()).apply()
    }

    fun addCustomCategory(categoryName: String) {
        val trimmed = categoryName.trim()
        if (trimmed.isBlank()) return
        val current = _customCategories.value.toMutableList()
        if (!current.contains(trimmed)) {
            current.add(trimmed)
            _customCategories.value = current
            saveCategoriesToPrefs(current)
        }
    }

    fun renameCategory(oldCategory: String, newCategory: String) {
        val oldTrimmed = oldCategory.trim()
        val newTrimmed = newCategory.trim()
        if (oldTrimmed.isBlank() || newTrimmed.isBlank() || oldTrimmed == newTrimmed) return

        viewModelScope.launch {
            repository.updateCategoryName(oldTrimmed, newTrimmed)

            val current = _customCategories.value.toMutableList()
            val index = current.indexOf(oldTrimmed)
            if (index != -1) {
                current[index] = newTrimmed
            } else {
                current.add(newTrimmed)
            }
            _customCategories.value = current.distinct()
            saveCategoriesToPrefs(current)
        }
    }

    fun deleteCategory(categoryToDelete: String, replacementCategory: String = "General") {
        val catTrimmed = categoryToDelete.trim()
        if (catTrimmed.isBlank()) return

        viewModelScope.launch {
            repository.reassignCategory(catTrimmed, replacementCategory)

            val current = _customCategories.value.filter { it != catTrimmed }
            _customCategories.value = current
            saveCategoriesToPrefs(current)
        }
    }

    // --- POS Cart State ---
    private val cartLock = Any()
    val cartItems = mutableStateListOf<CartItem>()
    val cartMap = mutableStateMapOf<String, CartItem>()

    fun syncCartMapInternal() {
        val currentProductIds = mutableSetOf<String>()
        for (item in cartItems) {
            if (!item.isFreeGift) {
                val pid = item.product.id
                currentProductIds.add(pid)
                val existing = cartMap[pid]
                if (existing != item) {
                    cartMap[pid] = item
                }
            }
        }
        val toRemove = cartMap.keys.filter { it !in currentProductIds }
        for (pid in toRemove) {
            cartMap.remove(pid)
        }
    }
    var selectedCustomer by mutableStateOf<Customer?>(null)
    var cartDiscount by mutableDoubleStateOf(0.0)
    var isRoundOff by mutableStateOf(false)
    var selectedPaymentMode by mutableStateOf("CASH") // CASH, UPI, CREDIT
    var posSearchQuery by mutableStateOf("")
    var posSelectedCategory by mutableStateOf("ALL")
    var isWholesaleBillingMode by mutableStateOf(false) // Toggle Wholesale Mode
    var lastCompletedSale by mutableStateOf<SaleWithItems?>(null)
    var showCheckoutSuccessDialog by mutableStateOf(false)
    var customReceivedAmountInput by mutableStateOf<String?>(null)
    var depositExcessToKhata by mutableStateOf(true) // Deposit excess cash to customer Khata
    var creditDueDays by mutableIntStateOf(7) // Default payment due period: 7 days
    var customCreditDueDate by mutableStateOf<Long?>(null) // Custom due date timestamp if selected

    val effectiveCreditDueDate: Long
        get() {
            if (customCreditDueDate != null) return customCreditDueDate!!
            val days = if (creditDueDays > 0) creditDueDays else 7
            return System.currentTimeMillis() + days * 24L * 60 * 60 * 1000L
        }

    // Stock Validation & Override State
    var stockValidationError by mutableStateOf<InsufficientStockException?>(null)
    var showInsufficientStockDialog by mutableStateOf(false)
    var isProcessingCheckout by mutableStateOf(false)
    var pendingSaleIsHold by mutableStateOf(false)

    // Credit Limit Enforcement & Owner Override State
    var creditLimitValidationError by mutableStateOf<CreditLimitExceededException?>(null)
    var showCreditLimitExceededDialog by mutableStateOf(false)

    // Automated Customer SMS for Credit Sales
    var pendingCreditSms by mutableStateOf<PendingCreditSms?>(null)
    var showSmsPermissionExplanationDialog by mutableStateOf(false)
    var lastSmsStatusMessage by mutableStateOf<String?>(null)

    fun dispatchPendingCreditSms(context: android.content.Context) {
        val pending = pendingCreditSms ?: return
        viewModelScope.launch {
            try {
                if (!pending.phone.isNullOrBlank()) {
                    val result = SmsHelper.sendDirectSms(context, pending.phone, pending.message)
                    if (result.isSuccess) {
                        lastSmsStatusMessage = "📲 Credit SMS sent to ${pending.customerName} (${pending.phone})"
                        Toast.makeText(
                            context,
                            "Credit SMS sent to ${pending.customerName}",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        val ex = result.exceptionOrNull()
                        lastSmsStatusMessage = "⚠️ Credit SMS could not be sent: ${ex?.message}"
                        Toast.makeText(
                            context,
                            "SMS send failed: ${ex?.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    lastSmsStatusMessage = "ℹ️ No phone number for ${pending.customerName} — SMS skipped"
                    Toast.makeText(
                        context,
                        "Customer has no phone number — SMS skipped",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("StoreViewModel", "Error dispatching pending credit SMS: ${e.message}", e)
            } finally {
                pendingCreditSms = null
                showSmsPermissionExplanationDialog = false
            }
        }
    }

    fun dismissSmsPermissionExplanation() {
        showSmsPermissionExplanationDialog = false
        pendingCreditSms = null
    }

    val effectiveReceivedAmount: Double
        get() {
            if (selectedPaymentMode == "CREDIT") {
                val inputVal = customReceivedAmountInput?.toDoubleOrNull()
                return inputVal ?: 0.0
            }
            val inputVal = customReceivedAmountInput?.toDoubleOrNull()
            return inputVal ?: cartFinalTotal
        }

    val effectiveDueAmount: Double
        get() = (cartFinalTotal - effectiveReceivedAmount).coerceAtLeast(0.0)

    val effectiveExcessAmount: Double
        get() = (effectiveReceivedAmount - cartFinalTotal).coerceAtLeast(0.0)

    val effectiveChangeAmount: Double
        get() {
            if (selectedCustomer != null && depositExcessToKhata) {
                return 0.0 // Excess is deposited into customer's Khata account
            }
            return effectiveExcessAmount
        }

    fun getCustomerBalance(customerId: String): Double {
        return com.example.utils.LedgerCalculator.calculateCustomerBalance(customerId, allLedgerEntries.value)
    }

    fun getSupplierBalance(supplierId: String): Double {
        return com.example.utils.LedgerCalculator.calculateSupplierBalance(supplierId, allLedgerEntries.value)
    }

    val customerPreviousBalance: Double
        get() = selectedCustomer?.let { getCustomerBalance(it.id) } ?: 0.0

    val customerNewTotalBalance: Double
        get() {
            if (effectiveDueAmount > 0.0) {
                return customerPreviousBalance + effectiveDueAmount
            }
            if (effectiveExcessAmount > 0.0 && depositExcessToKhata && selectedCustomer != null) {
                return customerPreviousBalance - effectiveExcessAmount
            }
            return customerPreviousBalance
        }

    val isCustomerOverCreditLimit: Boolean
        get() {
            val cust = selectedCustomer ?: return false
            if (!cust.hasCreditLimit()) return false
            return customerNewTotalBalance > cust.creditLimit!!
        }

    val customerCreditLimitExceededAmount: Double
        get() {
            val cust = selectedCustomer ?: return 0.0
            if (!cust.hasCreditLimit()) return 0.0
            return (customerNewTotalBalance - cust.creditLimit!!).coerceAtLeast(0.0)
        }

    val customerAvailableCredit: Double
        get() {
            val cust = selectedCustomer ?: return Double.MAX_VALUE
            return cust.getAvailableCredit()
        }

    val cartTotalBeforeDiscount: Double
        get() = cartItems.sumOf { it.getSubtotal(isWholesaleBillingMode) }

    fun getActiveOfferForProduct(productId: String): Offer? {
        val todayStr = Offer.getTodayDateString()
        val matchingOffers = activeOffers.value.filter { it.appliesToProduct(productId, todayStr) }
        if (matchingOffers.isEmpty()) return null
        // Specific product offers take precedence over store-wide general offers
        val specific = matchingOffers.firstOrNull { !it.isStoreWide() }
        return specific ?: matchingOffers.firstOrNull()
    }

    fun getBogoPromptForCartItem(item: CartItem): String? {
        val todayStr = Offer.getTodayDateString()
        val matchingOffers = activeOffers.value.filter { 
            it.appliesToProduct(item.product.id, todayStr) && it.getOfferTypeEnum() == OfferType.BUY_X_GET_Y 
        }
        val bogo = matchingOffers.firstOrNull() ?: return null
        val buyQ = bogo.buyQty.coerceAtLeast(1.0)
        val getQ = bogo.getQty.coerceAtLeast(1.0)
        val setSize = buyQ + getQ
        val remainder = item.quantity % setSize
        if (remainder >= buyQ && remainder < setSize) {
            val needed = setSize - remainder
            val nStr = if (needed % 1.0 == 0.0) "${needed.toInt()}" else "%.1f".format(needed)
            val bStr = if (buyQ % 1.0 == 0.0) "${buyQ.toInt()}" else "%.1f".format(buyQ)
            val gStr = if (getQ % 1.0 == 0.0) "${getQ.toInt()}" else "%.1f".format(getQ)
            return if (LanguageManager.isBengali) "কিনুন $bStr পান $gStr ফ্রি ($nStr টি ফ্রি পাবেন)" else "Buy $bStr Get $gStr FREE ($nStr free item available)"
        }
        return null
    }

    fun calculateCartItemOfferDiscount(item: CartItem, isWholesale: Boolean = isWholesaleBillingMode): AppliedOfferDiscount? {
        val todayStr = Offer.getTodayDateString()
        val matchingOffers = activeOffers.value.filter { it.appliesToProduct(item.product.id, todayStr) }
        if (matchingOffers.isEmpty()) return null

        val baseSubtotal = item.getSubtotal(isWholesale)
        if (baseSubtotal <= 0.0) return null

        // Evaluate all candidate offers (prioritizing specific ones, or best discount value)
        val calculatedDiscounts = matchingOffers.mapNotNull { offer ->
            when (offer.getOfferTypeEnum()) {
                OfferType.PERCENT_DISCOUNT -> {
                    if (offer.discountValue <= 0.0) null
                    else {
                        val discountAmt = (baseSubtotal * (offer.discountValue / 100.0)).coerceIn(0.0, baseSubtotal)
                        val pStr = if (offer.discountValue % 1.0 == 0.0) "${offer.discountValue.toInt()}%" else "%.1f%%".format(offer.discountValue)
                        val desc = "Offer (${offer.name}): -₹" + "%.2f".format(discountAmt) + " ($pStr off)"
                        val afterRevenue = (baseSubtotal - discountAmt)
                        val isLoss = item.totalCost > 0.0 && afterRevenue < item.totalCost
                        val lossAmt = if (isLoss) (item.totalCost - afterRevenue) else 0.0
                        AppliedOfferDiscount(
                            offer = offer,
                            discountAmount = discountAmt,
                            description = desc,
                            freeQty = 0.0,
                            isBogo = false,
                            isLossLeader = isLoss,
                            lossAmount = lossAmt
                        )
                    }
                }
                OfferType.FLAT_DISCOUNT -> {
                    if (offer.discountValue <= 0.0) null
                    else {
                        val baseQty = item.product.convertQuantityToBaseUnit(item.quantity, item.unitType)
                        val discountAmt = (offer.discountValue * baseQty).coerceIn(0.0, baseSubtotal)
                        val fStr = if (offer.discountValue % 1.0 == 0.0) "₹${offer.discountValue.toInt()}" else "₹%.2f".format(offer.discountValue)
                        val desc = "Offer (${offer.name}): -₹" + "%.2f".format(discountAmt) + " ($fStr flat off)"
                        val afterRevenue = (baseSubtotal - discountAmt)
                        val isLoss = item.totalCost > 0.0 && afterRevenue < item.totalCost
                        val lossAmt = if (isLoss) (item.totalCost - afterRevenue) else 0.0
                        AppliedOfferDiscount(
                            offer = offer,
                            discountAmount = discountAmt,
                            description = desc,
                            freeQty = 0.0,
                            isBogo = false,
                            isLossLeader = isLoss,
                            lossAmount = lossAmt
                        )
                    }
                }
                OfferType.BUY_X_GET_Y -> {
                    val buyQ = offer.buyQty.coerceAtLeast(1.0)
                    val getQ = offer.getQty.coerceAtLeast(1.0)
                    val setSize = buyQ + getQ
                    val effectiveUnitPrice = item.getEffectiveUnitPrice(isWholesale)
                    val setCount = (item.quantity / setSize).toInt()
                    val freeQty = setCount * getQ
                    val discountPercent = if (offer.getDiscountPercent > 0.0) (offer.getDiscountPercent / 100.0) else 1.0
                    val discountAmt = (freeQty * effectiveUnitPrice * discountPercent).coerceIn(0.0, baseSubtotal)
                    if (discountAmt > 0.0) {
                        val bStr = if (buyQ % 1.0 == 0.0) "${buyQ.toInt()}" else "%.1f".format(buyQ)
                        val gStr = if (getQ % 1.0 == 0.0) "${getQ.toInt()}" else "%.1f".format(getQ)
                        val freeQtyStr = if (freeQty % 1.0 == 0.0) "${freeQty.toInt()}" else "%.1f".format(freeQty)
                        val desc = "Offer (${offer.name}): Buy $bStr Get $gStr FREE (-₹" + "%.2f".format(discountAmt) + " • $freeQtyStr Free)"
                        val afterRevenue = (baseSubtotal - discountAmt)
                        val isLoss = item.totalCost > 0.0 && afterRevenue < item.totalCost
                        val lossAmt = if (isLoss) (item.totalCost - afterRevenue) else 0.0
                        AppliedOfferDiscount(
                            offer = offer,
                            discountAmount = discountAmt,
                            description = desc,
                            freeQty = freeQty,
                            isBogo = true,
                            isLossLeader = isLoss,
                            lossAmount = lossAmt
                        )
                    } else null
                }
                OfferType.FREE_GIFT -> null
                OfferType.COMBO_BUNDLE -> null
            }
        }

        if (calculatedDiscounts.isEmpty()) return null

        // Give the customer the best discount (maximum saving), prioritizing specific product offers if discounts are equal
        return calculatedDiscounts.maxWithOrNull(
            compareBy<AppliedOfferDiscount> { it.discountAmount }
                .thenBy { !it.offer.isStoreWide() }
        )
    }

    /**
     * Calculates the qualifying spend for a specific offer in the current cart.
     * Store-wide offers sum all non-gift items; specific product offers sum only matching non-gift items.
     */
    fun getCartQualifyingSpendForOffer(offer: Offer): Double {
        val todayStr = Offer.getTodayDateString()
        if (!offer.isCurrentlyApplying(todayStr)) return 0.0
        val nonGiftItems = cartItems.filterNot { it.isFreeGift }
        return if (offer.isStoreWide()) {
            nonGiftItems.sumOf { it.getSubtotal(isWholesaleBillingMode) }
        } else {
            val pIds = offer.getProductIdsList()
            nonGiftItems.filter { pIds.contains(it.product.id) }.sumOf { it.getSubtotal(isWholesaleBillingMode) }
        }
    }

    /**
     * Returns all active FREE_GIFT offers that the current cart currently qualifies for,
     * along with the gift Product entity from catalog.
     */
    fun getEligibleFreeGiftOffers(): List<Pair<Offer, Product>> {
        val todayStr = Offer.getTodayDateString()
        val freeOffers = activeOffers.value.filter {
            it.isCurrentlyApplying(todayStr) &&
            it.getOfferTypeEnum() == OfferType.FREE_GIFT &&
            it.minSpendAmount > 0.0 &&
            !it.freeProductId.isNullOrBlank()
        }
        if (freeOffers.isEmpty()) return emptyList()

        val results = mutableListOf<Pair<Offer, Product>>()
        for (offer in freeOffers) {
            val qualifyingSpend = getCartQualifyingSpendForOffer(offer)
            if (qualifyingSpend >= offer.minSpendAmount) {
                val giftProd = allProducts.value.firstOrNull { it.id == offer.freeProductId }
                if (giftProd != null) {
                    results.add(offer to giftProd)
                }
            }
        }
        return results
    }

    /**
     * If not eligible yet, returns the nearest active upcoming FREE_GIFT offer,
     * along with the gift Product and the remaining amount needed to unlock it.
     */
    fun getUpcomingFreeGiftOffer(): Triple<Offer, Product, Double>? {
        val todayStr = Offer.getTodayDateString()
        val freeOffers = activeOffers.value.filter {
            it.isCurrentlyApplying(todayStr) &&
            it.getOfferTypeEnum() == OfferType.FREE_GIFT &&
            it.minSpendAmount > 0.0 &&
            !it.freeProductId.isNullOrBlank()
        }
        if (freeOffers.isEmpty()) return null

        var bestMatch: Triple<Offer, Product, Double>? = null
        for (offer in freeOffers) {
            val qualifyingSpend = getCartQualifyingSpendForOffer(offer)
            if (qualifyingSpend > 0.0 && qualifyingSpend < offer.minSpendAmount) {
                val remaining = offer.minSpendAmount - qualifyingSpend
                val giftProd = allProducts.value.firstOrNull { it.id == offer.freeProductId }
                if (giftProd != null) {
                    if (bestMatch == null || remaining < bestMatch.third) {
                        bestMatch = Triple(offer, giftProd, remaining)
                    }
                }
            }
        }
        return bestMatch
    }

    /**
     * Checks if a free gift item for this offer is already present in the cart.
     */
    fun isFreeGiftInCart(offerId: String): Boolean {
        return cartItems.any { it.isFreeGift && it.freeGiftOfferId == offerId }
    }

    /**
     * Adds the free gift product to the cart with price = 0.0.
     */
    fun addFreeGiftToCart(offer: Offer, giftProduct: Product) {
        synchronized(cartLock) {
            val existingIndex = cartItems.indexOfFirst { it.isFreeGift && it.freeGiftOfferId == offer.id }
            val giftQty = offer.freeProductQty.coerceAtLeast(0.001)
            val effectiveUnit = offer.freeProductUnit?.trim()?.takeIf { it.isNotBlank() } ?: giftProduct.unitType
            if (existingIndex >= 0) {
                val existing = cartItems[existingIndex]
                cartItems[existingIndex] = existing.copy(
                    quantity = giftQty,
                    unitType = effectiveUnit
                )
            } else {
                cartItems.add(
                    CartItem(
                        product = giftProduct,
                        quantity = giftQty,
                        unitPrice = 0.0,
                        unitType = effectiveUnit,
                        isFreeGift = true,
                        freeGiftOfferId = offer.id,
                        freeGiftOfferName = offer.name
                    )
                )
            }
            consolidateCartInternal()
        }
    }

    /**
     * Removes the free gift item for this offer from the cart.
     */
    fun removeFreeGiftFromCart(offerId: String) {
        synchronized(cartLock) {
            cartItems.removeAll { it.isFreeGift && it.freeGiftOfferId == offerId }
            consolidateCartInternal()
        }
    }

    /**
     * Checks if any free gift items in the cart are no longer qualified (e.g. if spend dropped).
     */
    fun getDisqualifiedFreeGiftNames(): List<String> {
        val disqualified = mutableListOf<String>()
        val todayStr = Offer.getTodayDateString()
        for (item in cartItems.filter { it.isFreeGift }) {
            val offerId = item.freeGiftOfferId ?: continue
            val offer = activeOffers.value.firstOrNull { it.id == offerId }
            if (offer == null || !offer.isCurrentlyApplying(todayStr) || getCartQualifyingSpendForOffer(offer) < offer.minSpendAmount) {
                disqualified.add(item.product.getDisplayName(false))
            }
        }
        return disqualified
    }

    val totalOffersDiscount: Double
        get() {
            return cartItems.sumOf { item ->
                calculateCartItemOfferDiscount(item, isWholesaleBillingMode)?.discountAmount ?: 0.0
            }
        }

    val totalMrpSavings: Double
        get() {
            return cartItems.sumOf { it.getItemMrpSavings(isWholesaleBillingMode) }
        }

    val totalCartDiscountAmount: Double
        get() = totalOffersDiscount + cartDiscount

    val cartTotalCost: Double
        get() = cartItems.sumOf { it.totalCost }

    val cartTotalMrp: Double
        get() = cartItems.sumOf { it.getMrpSubtotal() }

    val cartItemDiscountsOnMrp: Double
        get() = (cartTotalMrp - cartTotalBeforeDiscount).coerceAtLeast(0.0)

    val maxAllowedSafeDiscount: Double
        get() = (cartTotalBeforeDiscount - cartTotalCost).coerceAtLeast(0.0)

    val cartTotalCustomerSavings: Double
        get() = (cartTotalMrp - cartFinalTotal).coerceAtLeast(0.0)

    fun validateDiscountAgainstCostPrice(discountAmount: Double): DiscountValidationResult {
        val totalCost = cartTotalCost
        val subtotal = cartTotalBeforeDiscount
        val safeLimit = (subtotal - totalCost).coerceAtLeast(0.0)
        val profitBefore = (subtotal - totalCost).coerceAtLeast(0.0)
        val profitAfter = (subtotal - discountAmount - totalCost)
        val isBelowCost = profitAfter < -0.01

        val warningMsg = if (isBelowCost) {
            "Discount ₹%.2f exceeds total cost margin (Max safe discount: ₹%.2f). Estimated loss: ₹%.2f".format(
                discountAmount,
                safeLimit,
                kotlin.math.abs(profitAfter)
            )
        } else null

        return DiscountValidationResult(
            isValid = !isBelowCost || !StoreInfoManager.enforceCostPriceDiscountLimit,
            requestedDiscount = discountAmount,
            maxSafeDiscount = safeLimit,
            currentProfitBeforeDiscount = profitBefore,
            estimatedProfitAfterDiscount = profitAfter,
            lossAmount = if (profitAfter < 0) kotlin.math.abs(profitAfter) else 0.0,
            warningMessage = warningMsg
        )
    }

    fun applyCartDiscountSafe(discountAmount: Double, bypassCostLimit: Boolean = false): Boolean {
        val validation = validateDiscountAgainstCostPrice(discountAmount)
        if (!validation.isValid && !bypassCostLimit) {
            cartDiscount = validation.maxSafeDiscount
            return false
        }
        cartDiscount = discountAmount.coerceAtLeast(0.0)
        return true
    }

    val roundOffAmount: Double
        get() {
            if (!isRoundOff) return 0.0
            val afterDiscount = (cartTotalBeforeDiscount - totalCartDiscountAmount).coerceAtLeast(0.0)
            val rounded = kotlin.math.round(afterDiscount)
            return rounded - afterDiscount
        }

    val cartFinalTotal: Double
        get() {
            val afterDiscount = (cartTotalBeforeDiscount - totalCartDiscountAmount).coerceAtLeast(0.0)
            return if (isRoundOff) kotlin.math.round(afterDiscount) else afterDiscount
        }

    fun addToCart(product: Product, qty: Double = 1.0) {
        val defaultUnit = if (product.hasBoxPricing() || product.unitType.equals("box", ignoreCase = true)) "piece" else product.unitType
        addToCartWithUnit(product = product, quantity = qty, selectedUnit = defaultUnit)
    }

    private fun consolidateCartInternal() {
        val nonQuick = cartItems.filterNot { it.product.id.startsWith("quick_") }
        val duplicates = nonQuick.groupBy { "${it.product.id}__${it.variantBarcode ?: ""}__${if (it.isFreeGift) "free_${it.freeGiftOfferId}" else "regular"}" }.filter { it.value.size > 1 }
        if (duplicates.isEmpty()) return

        val newCart = mutableListOf<CartItem>()
        val processedKeys = mutableSetOf<String>()

        for (item in cartItems) {
            if (item.product.id.startsWith("quick_")) {
                newCart.add(item)
                continue
            }
            val groupKey = "${item.product.id}__${item.variantBarcode ?: ""}__${if (item.isFreeGift) "free_${item.freeGiftOfferId}" else "regular"}"
            if (processedKeys.contains(groupKey)) continue

            val sameItems = cartItems.filter { "${it.product.id}__${it.variantBarcode ?: ""}__${if (it.isFreeGift) "free_${it.freeGiftOfferId}" else "regular"}" == groupKey }
            if (sameItems.size == 1) {
                newCart.add(item)
            } else {
                if (item.isVariant) {
                    val totalQty = sameItems.sumOf { it.quantity }
                    newCart.add(item.copy(quantity = totalQty))
                } else {
                    var totalBaseQty = 0.0
                    for (s in sameItems) {
                        totalBaseQty += s.product.convertQuantityToBaseUnit(s.quantity, s.unitType)
                    }
                    val first = sameItems.first()
                    val isExistingBox = first.unitType.equals("box", ignoreCase = true) || first.unitType.equals("case", ignoreCase = true)
                    val isExistingSubUnit = (first.unitType.equals(first.product.getEffectiveSecondaryUnit(), ignoreCase = true) ||
                            Product.isGramUnit(first.unitType) ||
                            Product.isMlUnit(first.unitType)) &&
                            (Product.isKgUnit(first.product.unitType) ||
                             Product.isLitreUnit(first.product.unitType) ||
                             first.product.unitType.equals("quintal", ignoreCase = true))

                    val newQty = when {
                        isExistingBox -> totalBaseQty / first.product.getPiecesPerBoxRatio().toDouble()
                        isExistingSubUnit -> totalBaseQty * first.product.getEffectiveSecondaryRatio()
                        else -> totalBaseQty
                    }
                    newCart.add(first.copy(quantity = newQty))
                }
            }
            processedKeys.add(groupKey)
        }

        cartItems.clear()
        cartItems.addAll(newCart)
        syncCartMapInternal()
    }

    fun addVariantToCart(product: Product, variant: com.example.data.local.entities.BarcodeVariant, qty: Double = 1.0): Boolean {
        if (qty <= 0.0) return false
        val variantBaseQty = product.convertQuantityToBaseUnit(variant.quantity, variant.unitType)
        val addedBaseQty = qty * variantBaseQty

        synchronized(cartLock) {
            val currentCartBaseQty = cartItems
                .filter { it.product.id == product.id }
                .sumOf { it.getBaseQuantityDeducted() }

            if (addedBaseQty > 0.0 && (currentCartBaseQty + addedBaseQty) > (product.currentStock + 0.00001)) {
                // Insufficient base stock
                return false
            }

            val existingIndex = cartItems.indexOfFirst {
                it.product.id == product.id && it.variantBarcode == variant.barcode
            }

            if (existingIndex >= 0) {
                val existing = cartItems[existingIndex]
                cartItems[existingIndex] = existing.copy(quantity = existing.quantity + qty)
            } else {
                val packUnit = variant.packagingUnit.ifBlank { "pack" }
                val vLabel = variant.label.ifBlank { variant.getShortLabel() }
                cartItems.add(
                    CartItem(
                        product = product,
                        quantity = qty,
                        unitPrice = variant.price,
                        unitType = packUnit,
                        variantBarcode = variant.barcode,
                        variantLabel = vLabel,
                        variantQuantity = variant.quantity,
                        variantUnitType = variant.unitType
                    )
                )
            }
            consolidateCartInternal()
            syncCartMapInternal()
        }
        return true
    }

    /**
     * Checks if a barcode is globally unique across all products and variants in the catalog.
     * Returns true if the barcode is available (unused), false if already assigned elsewhere.
     */
    fun isBarcodeAvailableAcrossCatalog(
        barcode: String,
        excludeProductId: String? = null,
        excludeVariantBarcode: String? = null
    ): Pair<Boolean, String?> {
        val clean = barcode.trim()
        if (clean.isBlank()) return Pair(true, null)
        val cleanNoZero = clean.trimStart('0')

        for (prod in allProducts.value) {
            // Check primary barcode
            if (prod.id != excludeProductId) {
                val pBarcode = prod.barcode?.trim() ?: ""
                if (pBarcode.isNotBlank() && (
                    pBarcode.equals(clean, ignoreCase = true) ||
                    (cleanNoZero.isNotBlank() && pBarcode.trimStart('0') == cleanNoZero)
                )) {
                    return Pair(false, "Already assigned as primary barcode of '${prod.getDisplayName()}'")
                }
            }

            // Check variant barcodes
            for (variant in prod.getBarcodeVariants()) {
                if (prod.id == excludeProductId && variant.barcode.equals(excludeVariantBarcode, ignoreCase = true)) {
                    continue
                }
                val vBarcode = variant.barcode.trim()
                if (vBarcode.isNotBlank() && (
                    vBarcode.equals(clean, ignoreCase = true) ||
                    (cleanNoZero.isNotBlank() && vBarcode.trimStart('0') == cleanNoZero)
                )) {
                    return Pair(false, "Already assigned to variant '${variant.getDisplayTitle(prod.getDisplayName())}'")
                }
            }
        }
        return Pair(true, null)
    }

    fun consolidateCart() {
        synchronized(cartLock) {
            consolidateCartInternal()
        }
    }

    fun setOrUpdateProductInCartWithUnit(product: Product, quantity: Double, selectedUnit: String) {
        if (quantity <= 0.0) {
            synchronized(cartLock) {
                val existingIndex = cartItems.indexOfFirst { it.product.id == product.id && !it.isFreeGift && !it.isVariant }
                if (existingIndex >= 0) {
                    android.util.Log.d("POS_TRACE", "[CART_REMOVE] Product=${product.nameEn}, removing from cart as qty <= 0")
                    cartItems.removeAt(existingIndex)
                    syncCartMapInternal()
                }
            }
            return
        }
        val cleanQty = if (product.isDiscreteUnit(selectedUnit)) {
            if (quantity % 1.0 != 0.0) quantity.toInt().toDouble().coerceAtLeast(1.0) else quantity
        } else {
            quantity
        }
        synchronized(cartLock) {
            val isBulk = product.hasBulkPricing() && (selectedUnit.equals(product.getEffectiveBulkUnit(), ignoreCase = true) || selectedUnit.equals("box", ignoreCase = true) || selectedUnit.equals("case", ignoreCase = true))
            val unitPrice = if (isBulk) product.getEffectiveBulkPrice() else product.sellingPrice
            val existingIndex = cartItems.indexOfFirst { it.product.id == product.id && !it.isFreeGift && !it.isVariant }
            val newItem = CartItem(
                product = product,
                quantity = cleanQty,
                unitPrice = unitPrice,
                unitType = selectedUnit
            )
            android.util.Log.d("POS_TRACE", "[CART_SET] Product=${product.nameEn} (ID=${product.id}), SetQty=$cleanQty $selectedUnit, ExistingIndex=$existingIndex, PrevQty=${if (existingIndex >= 0) cartItems[existingIndex].quantity else "none"}")
            if (existingIndex >= 0) {
                cartItems[existingIndex] = newItem
            } else {
                cartItems.add(newItem)
            }
            syncCartMapInternal()
        }
    }

    fun addToCartWithUnit(product: Product, quantity: Double, selectedUnit: String) {
        if (quantity <= 0.0) return
        val cleanQty = if (product.isDiscreteUnit(selectedUnit)) {
            if (quantity % 1.0 != 0.0) quantity.toInt().toDouble().coerceAtLeast(1.0) else quantity
        } else {
            quantity
        }
        synchronized(cartLock) {
            val existingIndex = cartItems.indexOfFirst { it.product.id == product.id && !it.isFreeGift && !it.isVariant }
            android.util.Log.d("POS_TRACE", "[CART_ADD] Product=${product.nameEn} (ID=${product.id}), AddedQty=$cleanQty $selectedUnit, ExistingIndex=$existingIndex")
            if (existingIndex >= 0) {
                val existing = cartItems[existingIndex]
                val existingBaseQty = existing.product.convertQuantityToBaseUnit(existing.quantity, existing.unitType)
                val addedBaseQty = product.convertQuantityToBaseUnit(cleanQty, selectedUnit)
                val totalBaseQty = existingBaseQty + addedBaseQty

                // If existing item is already in base unit, keep base unit; otherwise convert back to existing unitType
                val isExistingBulk = existing.product.hasBulkPricing() && (existing.unitType.equals(existing.product.getEffectiveBulkUnit(), ignoreCase = true) || existing.unitType.equals("box", ignoreCase = true) || existing.unitType.equals("case", ignoreCase = true))
                val isExistingSubUnit = (existing.unitType.equals(existing.product.getEffectiveSecondaryUnit(), ignoreCase = true) ||
                        Product.isGramUnit(existing.unitType) ||
                        Product.isMlUnit(existing.unitType)) &&
                        (Product.isKgUnit(existing.product.unitType) ||
                         Product.isLitreUnit(existing.product.unitType) ||
                         existing.product.unitType.equals("quintal", ignoreCase = true))

                val newRawQty = when {
                    isExistingBulk -> totalBaseQty / existing.product.getEffectiveBulkQuantity()
                    isExistingSubUnit -> totalBaseQty * existing.product.getEffectiveSecondaryRatio()
                    else -> totalBaseQty
                }
                val newQty = if (existing.product.isDiscreteUnit(existing.unitType)) {
                    if (newRawQty % 1.0 != 0.0) newRawQty.toInt().toDouble().coerceAtLeast(1.0) else newRawQty
                } else {
                    newRawQty
                }

                android.util.Log.d("POS_TRACE", "[CART_ADD_MERGE] Product=${product.nameEn}, OldQty=${existing.quantity} ${existing.unitType}, NewQty=$newQty ${existing.unitType}")
                cartItems[existingIndex] = existing.copy(quantity = newQty)
            } else {
                val isBulk = product.hasBulkPricing() && (selectedUnit.equals(product.getEffectiveBulkUnit(), ignoreCase = true) || selectedUnit.equals("box", ignoreCase = true) || selectedUnit.equals("case", ignoreCase = true))
                val unitPrice = if (isBulk) product.getEffectiveBulkPrice() else product.sellingPrice
                cartItems.add(
                    CartItem(
                        product = product,
                        quantity = cleanQty,
                        unitPrice = unitPrice,
                        unitType = selectedUnit
                    )
                )
            }
            consolidateCartInternal()
        }
    }

    fun updateCartItemWithUnit(index: Int, product: Product, quantity: Double, selectedUnit: String) {
        synchronized(cartLock) {
            if (index in cartItems.indices) {
                if (quantity <= 0.0) {
                    cartItems.removeAt(index)
                } else {
                    val cleanQty = if (product.isDiscreteUnit(selectedUnit)) {
                        if (quantity % 1.0 != 0.0) quantity.toInt().toDouble().coerceAtLeast(1.0) else quantity
                    } else {
                        quantity
                    }
                    val oldItem = cartItems[index]
                    if (oldItem.isVariant) {
                        cartItems[index] = oldItem.copy(
                            quantity = cleanQty,
                            unitType = selectedUnit
                        )
                    } else {
                        val isBulk = product.hasBulkPricing() && (selectedUnit.equals(product.getEffectiveBulkUnit(), ignoreCase = true) || selectedUnit.equals("box", ignoreCase = true) || selectedUnit.equals("case", ignoreCase = true))
                        val unitPrice = if (isBulk) product.getEffectiveBulkPrice() else product.sellingPrice
                        cartItems[index] = CartItem(
                            product = product,
                            quantity = cleanQty,
                            unitPrice = unitPrice,
                            unitType = selectedUnit
                        )
                    }
                }
                consolidateCartInternal()
            }
        }
    }

    fun addQuickItemToCart(name: String, price: Double, unitType: String, qty: Double) {
        val quickProd = Product(
            id = "quick_" + System.currentTimeMillis(),
            nameEn = name,
            nameBn = name,
            category = "General",
            unitType = unitType,
            costPrice = price * 0.8,
            sellingPrice = price,
            currentStock = 999.0
        )
        synchronized(cartLock) {
            cartItems.add(
                CartItem(
                    product = quickProd,
                    quantity = qty,
                    unitPrice = price,
                    unitType = unitType
                )
            )
            syncCartMapInternal()
        }
    }

    fun seedSampleDataForce() {
        viewModelScope.launch {
            repository.seedSampleDataForce()
        }
    }

    fun quickIncrementProductInCart(product: Product) {
        synchronized(cartLock) {
            val defaultUnit = if (product.hasBoxPricing() || product.unitType.equals("box", ignoreCase = true)) "piece" else product.unitType
            val idx = cartItems.indexOfFirst { it.product.id == product.id }
            if (idx >= 0) {
                val item = cartItems[idx]
                val step = if (item.isVariant) {
                    1.0
                } else if (Product.isGramUnit(item.unitType) || Product.isMlUnit(item.unitType)) {
                    if (item.quantity < 1000.0) 100.0 else 250.0
                } else {
                    1.0
                }
                updateCartItemQuantityInternal(idx, item.quantity + step)
            } else {
                addToCartWithUnit(product, 1.0, defaultUnit)
            }
        }
    }

    fun quickDecrementProductInCart(product: Product) {
        synchronized(cartLock) {
            val idx = cartItems.indexOfFirst { it.product.id == product.id }
            if (idx >= 0) {
                val item = cartItems[idx]
                val step = if (item.isVariant) {
                    1.0
                } else if (Product.isGramUnit(item.unitType) || Product.isMlUnit(item.unitType)) {
                    if (item.quantity <= 100.0) item.quantity else 100.0
                } else {
                    1.0
                }
                val newQty = item.quantity - step
                if (newQty <= 0.0001) {
                    cartItems.removeAt(idx)
                    syncCartMapInternal()
                } else {
                    updateCartItemQuantityInternal(idx, newQty)
                }
            }
        }
    }

    private fun updateCartItemQuantityInternal(index: Int, newQty: Double) {
        if (index in cartItems.indices) {
            if (newQty <= 0) {
                cartItems.removeAt(index)
            } else {
                val existing = cartItems[index]
                cartItems[index] = existing.copy(quantity = newQty)
            }
            consolidateCartInternal()
            syncCartMapInternal()
        }
    }

    fun updateCartItemQuantity(index: Int, newQty: Double) {
        synchronized(cartLock) {
            updateCartItemQuantityInternal(index, newQty)
        }
    }

    fun removeFromCart(index: Int) {
        synchronized(cartLock) {
            if (index in cartItems.indices) {
                cartItems.removeAt(index)
                syncCartMapInternal()
            }
        }
    }

    fun toggleCartItemWholesale(index: Int) {
        synchronized(cartLock) {
            if (index in cartItems.indices) {
                val current = cartItems[index]
                cartItems[index] = current.copy(isWholesaleOverride = !current.isWholesaleOverride)
                syncCartMapInternal()
            }
        }
    }

    fun clearCart() {
        synchronized(cartLock) {
            cartItems.clear()
            cartMap.clear()
            selectedCustomer = null
            cartDiscount = 0.0
            isRoundOff = false
            selectedPaymentMode = "CASH"
            customReceivedAmountInput = null
            depositExcessToKhata = true
            creditDueDays = 7
            customCreditDueDate = null
        }
    }

    fun validateCartStock(): List<InsufficientStockItem> {
        val productDeductions = mutableMapOf<String, Double>()
        val productMap = mutableMapOf<String, Product>()

        for (item in cartItems) {
            if (item.product.id.startsWith("quick_")) continue
            val latestProd = allProducts.value.find { it.id == item.product.id } ?: item.product
            productMap[item.product.id] = latestProd
            val deductQty = item.getBaseQuantityDeducted()
            productDeductions[item.product.id] = (productDeductions[item.product.id] ?: 0.0) + deductQty
        }

        val insufficientList = mutableListOf<InsufficientStockItem>()
        for ((prodId, requiredBaseQty) in productDeductions) {
            val prod = productMap[prodId] ?: continue
            if (requiredBaseQty > (prod.currentStock + 0.00001)) {
                insufficientList.add(
                    InsufficientStockItem(
                        productId = prodId,
                        productNameEn = prod.nameEn,
                        productNameBn = prod.nameBn,
                        unitType = prod.unitType,
                        availableStock = prod.currentStock,
                        requestedQuantity = requiredBaseQty
                    )
                )
            }
        }
        return insufficientList
    }

    /**
     * Intelligently adjusts all cart items exceeding stock to their available quantities,
     * or removes completely exhausted items, allowing the user to sell all currently available stock.
     */
    fun adjustCartToAvailableStock(shortages: List<InsufficientStockItem>) {
        synchronized(cartLock) {
            val shortageMap = shortages.associateBy { it.productId }
            val updatedCart = mutableListOf<CartItem>()
            for (item in cartItems) {
                val shortage = shortageMap[item.product.id]
                if (shortage != null) {
                    val availableBase = shortage.availableStock
                    if (availableBase > 0.0001) {
                        val baseDeductionPerUnit = if (item.quantity > 0.0) item.getBaseQuantityDeducted() / item.quantity else 1.0
                        val newQty = if (baseDeductionPerUnit > 0.0) availableBase / baseDeductionPerUnit else availableBase
                        val roundedQty = Math.round(newQty * 1000.0) / 1000.0
                        if (roundedQty > 0) {
                            updatedCart.add(item.copy(quantity = roundedQty))
                        }
                    }
                } else {
                    updatedCart.add(item)
                }
            }
            cartItems.clear()
            cartItems.addAll(updatedCart)
            consolidateCartInternal()
            showInsufficientStockDialog = false
            stockValidationError = null
        }
    }

    /**
     * Adjusts a single product in the cart to its available recorded stock.
     */
    fun adjustSingleCartItemToAvailable(item: InsufficientStockItem) {
        synchronized(cartLock) {
            val updatedCart = mutableListOf<CartItem>()
            for (cartItem in cartItems) {
                if (cartItem.product.id == item.productId) {
                    val availableBase = item.availableStock
                    if (availableBase > 0.0001) {
                        val baseDeductionPerUnit = if (cartItem.quantity > 0.0) cartItem.getBaseQuantityDeducted() / cartItem.quantity else 1.0
                        val newQty = if (baseDeductionPerUnit > 0.0) availableBase / baseDeductionPerUnit else availableBase
                        val roundedQty = Math.round(newQty * 1000.0) / 1000.0
                        if (roundedQty > 0) {
                            updatedCart.add(cartItem.copy(quantity = roundedQty))
                        }
                    }
                } else {
                    updatedCart.add(cartItem)
                }
            }
            cartItems.clear()
            cartItems.addAll(updatedCart)
            consolidateCartInternal()

            // Update remaining shortages
            val remainingShortages = validateCartStock()
            if (remainingShortages.isEmpty()) {
                showInsufficientStockDialog = false
                stockValidationError = null
            } else {
                stockValidationError = InsufficientStockException(remainingShortages)
            }
        }
    }

    /**
     * Quickly updates recorded stock of a product to match the requested quantity.
     */
    fun quickMatchProductStock(productId: String, targetQuantity: Double) {
        val prod = allProducts.value.find { it.id == productId } ?: return
        val roundedStock = Math.round(targetQuantity * 1000.0) / 1000.0
        saveProduct(prod.copy(currentStock = roundedStock))

        val currentShortages = stockValidationError?.items?.filter { it.productId != productId } ?: emptyList()
        if (currentShortages.isEmpty()) {
            showInsufficientStockDialog = false
            stockValidationError = null
        } else {
            stockValidationError = InsufficientStockException(currentShortages)
        }
    }

    /**
     * Quickly updates recorded stocks for all shortage items to match their requested quantities.
     */
    fun quickMatchAllStocks(shortages: List<InsufficientStockItem>) {
        for (item in shortages) {
            val prod = allProducts.value.find { it.id == item.productId }
            if (prod != null) {
                val roundedStock = Math.round(item.requestedQuantity * 1000.0) / 1000.0
                saveProduct(prod.copy(currentStock = roundedStock))
            }
        }
        showInsufficientStockDialog = false
        stockValidationError = null
    }

    fun canOverrideStockShortage(): Boolean {
        val activeStaff = StaffManager.activeStaff
        if (activeStaff != null) {
            val role = activeStaff.role.uppercase()
            return role == "STORE_MANAGER" || role == "ADMIN" || role == "OWNER"
        }

        if (StaffManager.isOwner()) {
            return true
        }

        val cloudRole = _currentFirestoreUserRole.value
        if (cloudRole != null) {
            return cloudRole.isAdmin || cloudRole.isManager
        }

        return true
    }

    fun canOverrideCreditLimit(): Boolean {
        val activeStaff = StaffManager.activeStaff
        if (activeStaff != null) {
            val role = activeStaff.role.uppercase()
            return role == "ADMIN" || role == "OWNER"
        }

        if (StaffManager.isOwner()) {
            return true
        }

        val cloudRole = _currentFirestoreUserRole.value
        if (cloudRole != null) {
            return cloudRole.isAdmin
        }

        return true
    }

    fun completeCheckout(
        isHold: Boolean = false,
        allowNegativeStockOverride: Boolean = false,
        allowCreditLimitOverride: Boolean = false
    ) {
        if (isProcessingCheckout) return

        // Real-time Staff Internet requirement: Staff accounts must have active internet on this device
        if (StaffManager.isStaffBlockedDueToOffline()) {
            android.widget.Toast.makeText(
                getApplication(),
                "An internet connection is required while using a staff account.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }

        val saleId = "SALE_" + System.currentTimeMillis()
        val sale: Sale
        val saleItemsList: List<SaleItem>
        val custId: String?
        val excessAmt: Double
        val payMode: String
        val prevBal: Double

        synchronized(cartLock) {
            if (cartItems.isEmpty()) return

            // 0. Ensure cart is strictly consolidated before computing totals & stock deductions
            consolidateCartInternal()

            // 1. Fast local stock check before launching transaction
            if (!isHold && !allowNegativeStockOverride) {
                val shortages = validateCartStock()
                if (shortages.isNotEmpty()) {
                    stockValidationError = InsufficientStockException(shortages)
                    pendingSaleIsHold = isHold
                    showInsufficientStockDialog = true
                    return
                }
            }

            isProcessingCheckout = true

            val totalAmount = cartTotalBeforeDiscount
            val finalAmount = cartFinalTotal

            val recAmt = if (isHold) 0.0 else effectiveReceivedAmount
            val dueAmt = if (isHold) 0.0 else effectiveDueAmount
            prevBal = customerPreviousBalance
            excessAmt = if (!isHold && selectedCustomer != null && depositExcessToKhata) effectiveExcessAmount else 0.0
            custId = selectedCustomer?.id
            payMode = selectedPaymentMode

            sale = Sale(
                id = saleId,
                datetime = System.currentTimeMillis(),
                totalAmount = totalAmount,
                discount = totalCartDiscountAmount,
                finalAmount = finalAmount,
                paymentMode = if (isHold) "HOLD" else selectedPaymentMode,
                customerId = selectedCustomer?.id,
                customerName = selectedCustomer?.name,
                isHeld = isHold,
                receivedAmount = recAmt,
                dueAmount = dueAmt,
                previousBalance = prevBal,
                staffId = com.example.utils.StaffManager.activeStaff?.id,
                staffName = com.example.utils.StaffManager.getCurrentStaffDisplayName(),
                dueDate = if (dueAmt > 0.0) effectiveCreditDueDate else null
            )

            val rawSaleItemsList = cartItems.map { cart ->
                val effectiveUnitPrice = cart.getEffectiveUnitPrice(isWholesaleBillingMode)
                val effectiveSubtotal = cart.getSubtotal(isWholesaleBillingMode)
                val isGift = cart.isFreeGift
                SaleItem(
                    saleId = saleId,
                    productId = cart.product.id,
                    productNameEn = cart.getDisplayName(false),
                    productNameBn = cart.getDisplayName(true),
                    unitType = cart.unitType,
                    quantity = cart.quantity,
                    unitPrice = effectiveUnitPrice,
                    costPrice = cart.getUnitCost(),
                    subtotal = effectiveSubtotal,
                    totalCost = cart.totalCost,
                    mrp = cart.getEffectiveMrp(),
                    variantBarcode = cart.variantBarcode
                )
            }

            android.util.Log.d("POS_TRACE", "[CHECKOUT_START] SaleID=$saleId, TotalBeforeDiscount=$totalAmount, Discount=$cartDiscount, FinalAmount=$finalAmount, RecAmt=$recAmt, DueAmt=$dueAmt, CartItemCount=${cartItems.size}")
            rawSaleItemsList.forEachIndexed { i, item ->
                android.util.Log.d("POS_TRACE", "  CartItem[$i]: Product=${item.productNameEn} (ID=${item.productId}), Qty=${item.quantity} ${item.unitType}, UnitPrice=${item.unitPrice}, Subtotal=${item.subtotal}")
            }

            saleItemsList = com.example.utils.SaleConsolidationUtils.consolidateSaleItems(
                rawSaleItemsList
            ) { prodId -> allProducts.value.find { it.id == prodId } }

            android.util.Log.d("POS_TRACE", "[CHECKOUT_CONSOLIDATED] ConsolidatedItemCount=${saleItemsList.size}")
            saleItemsList.forEachIndexed { i, item ->
                android.util.Log.d("POS_TRACE", "  ConsolidatedItem[$i]: Product=${item.productNameEn}, Qty=${item.quantity} ${item.unitType}, Subtotal=${item.subtotal}")
            }
        }

        val ownerOverride = allowCreditLimitOverride && canOverrideCreditLimit()
        viewModelScope.launch {
            try {
                val result = repository.completeSale(
                    sale = sale,
                    items = saleItemsList,
                    isHeldBill = isHold,
                    allowNegativeStockOverride = allowNegativeStockOverride,
                    ownerOverrideCreditLimit = ownerOverride
                )

                if (result.isSuccess) {
                    showInsufficientStockDialog = false
                    stockValidationError = null
                    showCreditLimitExceededDialog = false
                    creditLimitValidationError = null
                    if (!isHold) {
                        lastCompletedSale = SaleWithItems(sale, saleItemsList)
                        showCheckoutSuccessDialog = true
                        if (excessAmt > 0.0 && !custId.isNullOrBlank()) {
                            val noteText = "Deposit excess from Bill #${sale.id.takeLast(6)}"
                            repository.recordCustomerPayment(
                                customerId = custId,
                                amount = excessAmt,
                                paymentMode = payMode,
                                note = noteText
                            )
                        }
                        // Instantly update Home Screen Widget when sale completes
                        com.example.widget.TodaySalesWidgetProvider.triggerWidgetUpdate(getApplication())

                        // Automated SMS for Credit Sale (if unpaid balance exists & enabled in Settings)
                        if (sale.dueAmount > 0.0 && StoreInfoManager.autoSendCreditSms) {
                            try {
                                val targetCust = if (!custId.isNullOrBlank()) {
                                    repository.getCustomerById(custId) ?: allCustomers.value.find { it.id == custId }
                                } else null

                                val custName = targetCust?.name ?: sale.customerName ?: "Customer"
                                val custPhone = targetCust?.phone?.trim()
                                val totalOutstanding = (prevBal + sale.dueAmount).coerceAtLeast(0.0)

                                // Auto-generate shareToken on credit sale if customer doesn't have one yet
                                val khataUrl = if (targetCust != null) {
                                    val token = repository.getOrCreateCustomerShareToken(targetCust)
                                    StoreInfoManager.buildCustomerKhataUrl(token)
                                } else ""

                                val creditSmsMsg = SmsHelper.generateCreditSaleSms(
                                    customerName = custName,
                                    storeName = StoreInfoManager.storeName,
                                    billTotal = sale.finalAmount,
                                    paidAmount = sale.receivedAmount,
                                    creditAdded = sale.dueAmount,
                                    totalOutstandingBalance = totalOutstanding,
                                    transactionDateMs = sale.datetime,
                                    merchantUpiId = StoreInfoManager.merchantUpiId.ifBlank { null },
                                    merchantPayeeName = StoreInfoManager.merchantPayeeName.ifBlank { null },
                                    isBengali = StoreInfoManager.isSmsBengali(),
                                    khataUrl = khataUrl.ifBlank { null }
                                )

                                if (SmsHelper.hasSmsPermission(getApplication())) {
                                    if (!custPhone.isNullOrBlank()) {
                                        val sendRes = SmsHelper.sendDirectSms(getApplication(), custPhone, creditSmsMsg)
                                        if (sendRes.isSuccess) {
                                            lastSmsStatusMessage = "📲 Credit SMS sent to $custName ($custPhone)"
                                            Toast.makeText(
                                                getApplication(),
                                                "Credit SMS sent to $custPhone",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        } else {
                                            val err = sendRes.exceptionOrNull()?.message ?: "Send failed"
                                            lastSmsStatusMessage = "⚠️ Credit SMS not sent: $err"
                                            Toast.makeText(
                                                getApplication(),
                                                "Notice: Credit SMS not sent ($err)",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    } else {
                                        lastSmsStatusMessage = "ℹ️ No phone number for $custName — SMS skipped"
                                        Toast.makeText(
                                            getApplication(),
                                            "Customer has no phone number — SMS skipped",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                } else {
                                    // First time / permission not yet granted -> Queue pending SMS & prompt permission rationale dialog
                                    pendingCreditSms = PendingCreditSms(
                                        customerName = custName,
                                        phone = custPhone,
                                        message = creditSmsMsg,
                                        saleId = sale.id
                                    )
                                    showSmsPermissionExplanationDialog = true
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("StoreViewModel", "Non-blocking credit SMS handling error: ${e.message}", e)
                            }
                        }
                    }
                    clearCart()
                } else {
                    val ex = result.exceptionOrNull()
                    if (ex is InsufficientStockException) {
                        stockValidationError = ex
                        pendingSaleIsHold = isHold
                        showInsufficientStockDialog = true
                    } else if (ex is CreditLimitExceededException) {
                        creditLimitValidationError = ex
                        pendingSaleIsHold = isHold
                        showCreditLimitExceededDialog = true
                    } else {
                        android.util.Log.e("StoreViewModel", "Checkout failed: ${ex?.message}")
                    }
                }
            } finally {
                isProcessingCheckout = false
            }
        }
    }

    fun processReturnOrReplacement(
        saleReturn: SaleReturn,
        returnItems: List<ReturnItem>,
        onComplete: () -> Unit = {}
    ) {
        viewModelScope.launch {
            repository.processReturnOrReplacement(saleReturn, returnItems)
            com.example.widget.TodaySalesWidgetProvider.triggerWidgetUpdate(getApplication())
            onComplete()
        }
    }

    fun resumeHeldSale(saleWithItems: SaleWithItems) {
        viewModelScope.launch {
            repository.deleteHeldSale(saleWithItems.sale.id)
            cartItems.clear()
            val consolidatedItems = saleWithItems.consolidatedItems

            consolidatedItems.forEach { item ->
                val prod = allProducts.value.find { it.id == item.productId }
                    ?: Product(
                        id = item.productId,
                        nameEn = item.productNameEn,
                        nameBn = item.productNameBn,
                        category = "General",
                        unitType = item.unitType,
                        costPrice = item.costPrice,
                        sellingPrice = item.unitPrice,
                        currentStock = 0.0
                    )
                cartItems.add(
                    CartItem(
                        product = prod,
                        quantity = item.quantity,
                        unitPrice = item.unitPrice,
                        unitType = item.unitType
                    )
                )
            }
            selectedPaymentMode = if (saleWithItems.sale.paymentMode != "HOLD") saleWithItems.sale.paymentMode else "CASH"
            cartDiscount = saleWithItems.sale.discount
            syncCartMapInternal()
        }
    }

    suspend fun auditPastSalesForDuplicates(): List<com.example.utils.SaleConsolidationUtils.DuplicateSaleAuditResult> {
        return repository.auditPastSalesForDuplicateProducts()
    }

    suspend fun repairDuplicateSaleLineItems(autoAdjustStock: Boolean = true): Int {
        return repository.repairDuplicateSaleLineItems(autoAdjustStock)
    }

    // --- Product / Inventory Actions ---
    fun getBatchesForProduct(productId: String) = repository.getBatchesForProduct(productId)

    fun addBatch(batch: ProductBatch) {
        viewModelScope.launch {
            repository.addBatch(batch)
        }
    }

    fun updateBatch(batch: ProductBatch) {
        viewModelScope.launch {
            repository.updateBatch(batch)
        }
    }

    fun deleteBatch(batchId: String, productId: String) {
        viewModelScope.launch {
            repository.deleteBatch(batchId, productId)
        }
    }

    fun saveProductWithBatches(product: Product, batches: List<ProductBatch>) {
        viewModelScope.launch {
            repository.saveProductWithBatches(product, batches)
        }
    }

    fun saveProduct(product: Product) {
        viewModelScope.launch {
            repository.saveProduct(product)
        }
    }

    fun deleteProduct(product: Product) {
        viewModelScope.launch {
            repository.deleteProduct(product)
        }
    }

    fun recordStockIn(
        product: Product,
        addedQty: Double,
        supplier: Supplier?,
        newCostPrice: Double,
        newSellingPrice: Double,
        isCredit: Boolean,
        batchNumber: String? = null,
        expiryDate: String? = null
    ) {
        viewModelScope.launch {
            val purchaseId = "PURCHASE_" + System.currentTimeMillis()
            val finalCost = if (newCostPrice > 0) newCostPrice else product.costPrice
            val finalSell = if (newSellingPrice > 0) newSellingPrice else product.sellingPrice
            val finalExpiry = if (!expiryDate.isNullOrBlank()) expiryDate else product.expiryDate
            val totalCost = addedQty * finalCost

            val purchase = Purchase(
                id = purchaseId,
                datetime = System.currentTimeMillis(),
                supplierId = supplier?.id,
                supplierName = supplier?.name,
                totalAmount = totalCost,
                amountPaid = if (isCredit) 0.0 else totalCost,
                paidVia = "CASH",
                dueAmount = if (isCredit) totalCost else 0.0,
                previousBalance = supplier?.let { getSupplierBalance(it.id) } ?: 0.0,
                paymentMode = if (isCredit) "CREDIT" else "CASH",
                notes = "Stock-IN for ${product.getDisplayName()}"
            )
            val purchaseItem = PurchaseItem(
                purchaseId = purchaseId,
                productId = product.id,
                productNameEn = product.nameEn,
                productNameBn = product.nameBn,
                quantity = addedQty,
                costPrice = finalCost,
                subtotal = totalCost
            )

            val batchNo = if (!batchNumber.isNullOrBlank()) batchNumber.trim() else "PUR-${purchaseId.takeLast(4)}-${System.currentTimeMillis().toString().takeLast(4)}"
            val batch = ProductBatch(
                id = java.util.UUID.randomUUID().toString(),
                productId = product.id,
                batchNumber = batchNo,
                quantity = addedQty,
                expiryDate = finalExpiry,
                costPrice = finalCost,
                sellingPrice = finalSell
            )

            val updatedProduct = product.copy(
                currentStock = Product.roundQuantity(product.currentStock + addedQty),
                costPrice = finalCost,
                sellingPrice = finalSell,
                expiryDate = finalExpiry
            )

            repository.completePurchase(
                purchase = purchase,
                items = listOf(purchaseItem),
                batchOverrides = mapOf(product.id to batch),
                productOverrides = mapOf(product.id to updatedProduct)
            )
        }
    }

    fun bulkRecordStockIn(
        items: List<Pair<Product, Double>>,
        supplier: Supplier? = null
    ) {
        viewModelScope.launch {
            for ((prod, qty) in items) {
                if (qty > 0) {
                    recordStockIn(
                        product = prod,
                        addedQty = qty,
                        supplier = supplier,
                        newCostPrice = prod.costPrice,
                        newSellingPrice = prod.sellingPrice,
                        isCredit = false,
                        batchNumber = null,
                        expiryDate = prod.expiryDate
                    )
                }
            }
        }
    }

    fun recordStockOut(
        product: Product,
        removedQty: Double,
        reason: String,
        note: String? = null,
        batchId: String? = null
    ) {
        viewModelScope.launch {
            val newStock = Product.roundQuantity((product.currentStock - removedQty).coerceAtLeast(0.0))
            val updatedProduct = product.copy(currentStock = newStock)
            repository.saveProduct(updatedProduct)

            val batches = repository.getBatchesForProductList(product.id)
            var batchPrefix: String? = null
            if (batches.isNotEmpty()) {
                if (batchId != null) {
                    val batch = batches.find { it.id == batchId }
                    if (batch != null) {
                        batchPrefix = "[Lot: ${batch.batchNumber}]"
                        val newQty = Product.roundQuantity((batch.quantity - removedQty).coerceAtLeast(0.0))
                        repository.updateBatch(batch.copy(quantity = newQty))
                    }
                } else {
                    var remaining = removedQty
                    for (batch in batches) {
                        if (remaining <= 0) break
                        if (batch.quantity <= 0) continue
                        if (batch.quantity <= remaining) {
                            remaining -= batch.quantity
                            repository.updateBatch(batch.copy(quantity = 0.0))
                        } else {
                            val newQty = Product.roundQuantity(batch.quantity - remaining)
                            remaining = 0.0
                            repository.updateBatch(batch.copy(quantity = newQty))
                        }
                    }
                }
            }

            val finalNote = when {
                batchPrefix != null && !note.isNullOrBlank() -> "$batchPrefix $note"
                batchPrefix != null -> batchPrefix
                else -> note
            }

            // Record in StockOutEntry table with quantity, reason, unit, and cost price at that time
            val stockOutEntry = StockOutEntry(
                productId = product.id,
                productNameEn = product.nameEn,
                productNameBn = product.nameBn,
                quantity = removedQty,
                unitType = product.unitType,
                costPrice = product.costPrice,
                totalCostValue = removedQty * product.costPrice,
                reason = reason,
                note = finalNote,
                timestamp = System.currentTimeMillis()
            )
            repository.recordStockOut(stockOutEntry)

            if (stockOutEntry.isPersonalUse()) {
                val expense = Expense(
                    id = "EXP_" + System.currentTimeMillis() + "_" + java.util.UUID.randomUUID().toString().take(6),
                    date = System.currentTimeMillis(),
                    category = "Personal Use Withdrawal",
                    amount = stockOutEntry.totalCostValue,
                    note = "Auto-logged from Personal Use Stock-out of ${stockOutEntry.productNameEn}"
                )
                repository.saveExpense(expense)
            }

            // Refresh financial reports
            loadPnlReport(selectedReportPeriod)
            loadStockOutReport(selectedReportPeriod)
        }
    }

    // --- Customer / Supplier / Ledger Actions ---
    fun saveCustomer(customer: Customer, initialDue: Double = 0.0, initialDueNote: String? = null) {
        viewModelScope.launch {
            repository.saveCustomer(customer, initialDue, initialDueNote)
        }
    }

    fun deleteCustomer(customer: Customer) {
        viewModelScope.launch {
            repository.deleteCustomer(customer)
        }
    }

    fun getOrCreateCustomerShareToken(customer: Customer, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val token = repository.getOrCreateCustomerShareToken(customer)
            onResult(token)
        }
    }

    fun regenerateCustomerShareToken(customer: Customer, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val token = repository.regenerateCustomerShareToken(customer)
            onResult(token)
        }
    }

    fun revokeCustomerShareToken(customer: Customer, onCompleted: () -> Unit = {}) {
        viewModelScope.launch {
            repository.revokeCustomerShareToken(customer)
            onCompleted()
        }
    }

    fun saveSupplier(supplier: Supplier, initialDue: Double = 0.0, initialDueNote: String? = null) {
        viewModelScope.launch {
            repository.saveSupplier(supplier, initialDue, initialDueNote)
        }
    }

    fun deleteSupplier(supplier: Supplier) {
        viewModelScope.launch {
            repository.deleteSupplier(supplier)
        }
    }

    fun recordMultiItemPurchase(
        supplier: Supplier,
        items: List<PurchaseItem>,
        paidAmount: Double = 0.0,
        paymentMode: String = "CASH",
        notes: String? = null,
        otherCharges: Double = 0.0
    ) {
        viewModelScope.launch {
            val itemsSubtotal = items.sumOf { it.subtotal }
            val totalAmount = itemsSubtotal + otherCharges.coerceAtLeast(0.0)
            val purchaseId = "PURCHASE_" + System.currentTimeMillis()
            val finalNotes = buildString {
                if (!notes.isNullOrBlank()) append(notes.trim())
                if (otherCharges > 0.0 && (notes == null || !notes.contains("[Delivery / Other Charges:"))) {
                    if (isNotEmpty()) append(" ")
                    append("[Delivery / Other Charges: ₹%.2f]".format(otherCharges))
                }
            }.ifBlank { null }
            val purchase = Purchase(
                id = purchaseId,
                datetime = System.currentTimeMillis(),
                supplierId = supplier.id,
                supplierName = supplier.name,
                totalAmount = totalAmount,
                amountPaid = paidAmount,
                paidVia = paymentMode,
                dueAmount = (totalAmount - paidAmount).coerceAtLeast(0.0),
                previousBalance = getSupplierBalance(supplier.id),
                paymentMode = if (paidAmount >= totalAmount) paymentMode else if (paidAmount > 0) "PARTIAL" else "CREDIT",
                notes = finalNotes ?: "Multi-item purchase bill from ${supplier.name}"
            )
            val updatedItems = items.map { it.copy(purchaseId = purchaseId) }
            
            // Record purchase bill in DB (updates stock, creates batches, handles unpaid/overpaid credit ledger & supplier balance, and syncs)
            repository.completePurchase(purchase, updatedItems)
        }
    }

    fun deletePurchase(purchaseId: String, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            repository.deletePurchase(purchaseId)
            onComplete()
        }
    }

    fun recordCustomerPayment(customerId: String, amount: Double, paymentMode: String, note: String?) {
        viewModelScope.launch {
            repository.recordCustomerPayment(customerId, amount, paymentMode, note)
        }
    }

    fun recordCustomerCustomEntry(
        customerId: String,
        amount: Double,
        isCreditGiven: Boolean,
        paymentMode: String,
        note: String?,
        dueDate: Long? = null
    ) {
        viewModelScope.launch {
            val result = repository.recordCustomerCustomEntry(
                customerId = customerId,
                amount = amount,
                isCreditGiven = isCreditGiven,
                paymentMode = paymentMode,
                note = note,
                dueDate = dueDate
            )

            // Automated SMS for Due Repayment or Custom Credit Given
            if (result.isSuccess && amount > 0.0 && StoreInfoManager.autoSendCreditSms) {
                try {
                    val targetCust = repository.getCustomerById(customerId) ?: allCustomers.value.find { it.id == customerId }
                    val custName = targetCust?.name ?: "Customer"
                    val custPhone = targetCust?.phone?.trim()
                    val remainingBalance = targetCust?.balance ?: 0.0

                    if (!custPhone.isNullOrBlank()) {
                        val khataUrl = if (targetCust != null) {
                            val token = repository.getOrCreateCustomerShareToken(targetCust)
                            StoreInfoManager.buildCustomerKhataUrl(token)
                        } else ""

                        val smsMsg = if (isCreditGiven) {
                            SmsHelper.generateCreditSaleSms(
                                customerName = custName,
                                storeName = StoreInfoManager.storeName,
                                billTotal = amount,
                                paidAmount = 0.0,
                                creditAdded = amount,
                                totalOutstandingBalance = remainingBalance,
                                transactionDateMs = System.currentTimeMillis(),
                                merchantUpiId = StoreInfoManager.merchantUpiId.ifBlank { null },
                                merchantPayeeName = StoreInfoManager.merchantPayeeName.ifBlank { null },
                                isBengali = StoreInfoManager.isSmsBengali(),
                                khataUrl = khataUrl.ifBlank { null }
                            )
                        } else {
                            SmsHelper.generatePaymentRepaymentSms(
                                customerName = custName,
                                storeName = StoreInfoManager.storeName,
                                repaidAmount = amount,
                                paymentMode = paymentMode,
                                remainingBalance = remainingBalance,
                                transactionDateMs = System.currentTimeMillis(),
                                isBengali = StoreInfoManager.isSmsBengali(),
                                khataUrl = khataUrl.ifBlank { null }
                            )
                        }

                        if (SmsHelper.hasSmsPermission(getApplication())) {
                            val sendRes = SmsHelper.sendDirectSms(getApplication(), custPhone, smsMsg)
                            if (sendRes.isSuccess) {
                                lastSmsStatusMessage = "📲 SMS sent to $custName ($custPhone)"
                                Toast.makeText(
                                    getApplication(),
                                    if (LanguageManager.isBengali) "এসএমএস পাঠানো হয়েছে: $custPhone" else "SMS sent to $custPhone",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                val err = sendRes.exceptionOrNull()?.message ?: "Send failed"
                                lastSmsStatusMessage = "⚠️ SMS not sent: $err"
                                Toast.makeText(
                                    getApplication(),
                                    "Notice: SMS not sent ($err)",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("StoreViewModel", "Automated custom entry SMS error: ${e.message}", e)
                }
            }
        }
    }

    fun recordSupplierPayment(supplierId: String, amount: Double, paymentMode: String, note: String?) {
        viewModelScope.launch {
            repository.recordSupplierPayment(supplierId, amount, paymentMode, note)
        }
    }

    fun recordSupplierCustomEntry(supplierId: String, amount: Double, isCreditTaken: Boolean, paymentMode: String, note: String?) {
        viewModelScope.launch {
            repository.recordSupplierCustomEntry(supplierId, amount, isCreditTaken, paymentMode, note)
        }
    }

    // --- Expense Actions ---
    fun saveExpense(expense: Expense) {
        viewModelScope.launch {
            repository.saveExpense(expense)
            com.example.widget.TodaySalesWidgetProvider.triggerWidgetUpdate(getApplication())
        }
    }

    fun deleteExpense(expense: Expense) {
        viewModelScope.launch {
            repository.deleteExpense(expense)
            com.example.widget.TodaySalesWidgetProvider.triggerWidgetUpdate(getApplication())
        }
    }

    fun loadPnlReport(period: ReportPeriod) {
        selectedReportPeriod = period
        viewModelScope.launch {
            try {
                val (startTime, endTime) = getPeriodTimeBounds(period)
                pnlReport = repository.generatePnlReport(startTime, endTime)
                stockOutReport = repository.generateStockOutReport(startTime, endTime)
                loadComprehensiveReport(period)
            } catch (e: Throwable) {
                android.util.Log.w("StoreViewModel", "loadPnlReport note: ${e.message}")
            }
        }
    }

    fun loadComprehensiveReport(period: ReportPeriod = selectedReportPeriod) {
        viewModelScope.launch {
            try {
                isComprehensiveReportLoading = true
                val (startTime, endTime) = getPeriodTimeBounds(period)
                comprehensiveReport = repository.generateComprehensiveBusinessReport(startTime, endTime)
            } catch (e: Throwable) {
                android.util.Log.w("StoreViewModel", "loadComprehensiveReport note: ${e.message}")
            } finally {
                isComprehensiveReportLoading = false
            }
        }
    }

    fun loadStockOutReport(period: ReportPeriod) {
        viewModelScope.launch {
            try {
                val (startTime, endTime) = getPeriodTimeBounds(period)
                stockOutReport = repository.generateStockOutReport(startTime, endTime)
            } catch (e: Throwable) {
                android.util.Log.w("StoreViewModel", "loadStockOutReport note: ${e.message}")
            }
        }
    }

    fun loadStockOutReport(startTime: Long, endTime: Long) {
        viewModelScope.launch {
            try {
                stockOutReport = repository.generateStockOutReport(startTime, endTime)
            } catch (e: Throwable) {
                android.util.Log.w("StoreViewModel", "loadStockOutReport range note: ${e.message}")
            }
        }
    }

    fun deleteStockOut(entry: StockOutEntry) {
        viewModelScope.launch {
            try {
                repository.deleteStockOut(entry)
                loadPnlReport(selectedReportPeriod)
                loadStockOutReport(selectedReportPeriod)
            } catch (e: Throwable) {
                android.util.Log.w("StoreViewModel", "deleteStockOut note: ${e.message}")
            }
        }
    }

    fun getPeriodTimeBounds(period: ReportPeriod): Pair<Long, Long> {
        val startCal = Calendar.getInstance()
        val endCal = Calendar.getInstance()

        endCal.set(Calendar.HOUR_OF_DAY, 23)
        endCal.set(Calendar.MINUTE, 59)
        endCal.set(Calendar.SECOND, 59)
        endCal.set(Calendar.MILLISECOND, 999)

        when (period) {
            ReportPeriod.TODAY -> {
                startCal.set(Calendar.HOUR_OF_DAY, 0)
                startCal.set(Calendar.MINUTE, 0)
                startCal.set(Calendar.SECOND, 0)
                startCal.set(Calendar.MILLISECOND, 0)
            }
            ReportPeriod.THIS_WEEK -> {
                startCal.set(Calendar.DAY_OF_WEEK, startCal.firstDayOfWeek)
                startCal.set(Calendar.HOUR_OF_DAY, 0)
                startCal.set(Calendar.MINUTE, 0)
                startCal.set(Calendar.SECOND, 0)
                startCal.set(Calendar.MILLISECOND, 0)
            }
            ReportPeriod.THIS_MONTH -> {
                startCal.set(Calendar.DAY_OF_MONTH, 1)
                startCal.set(Calendar.HOUR_OF_DAY, 0)
                startCal.set(Calendar.MINUTE, 0)
                startCal.set(Calendar.SECOND, 0)
                startCal.set(Calendar.MILLISECOND, 0)
            }
            ReportPeriod.ALL_TIME -> {
                return Pair(0L, Long.MAX_VALUE)
            }
        }
        return Pair(startCal.timeInMillis, endCal.timeInMillis)
    }

    fun loadPairedPrinters() {
        try {
            val devices = EscPosPrinter.getPairedDevices()
            viewModelScope.launch(Dispatchers.Main.immediate) {
                try {
                    pairedPrinters.clear()
                    pairedPrinters.addAll(devices)
                    val savedAddr = StoreInfoManager.savedPrinterAddress
                    if (!savedAddr.isNullOrBlank() && devices.any { it.address == savedAddr }) {
                        selectedPrinterAddress = savedAddr
                    } else if (devices.isNotEmpty() && selectedPrinterAddress == null) {
                        val preferred = devices.firstOrNull { dev ->
                            val n = dev.name.lowercase()
                            n.contains("58") || n.contains("pos") || n.contains("printer") || n.contains("thermal") || n.contains("mpt") || n.contains("mtp") || n.contains("rpp")
                        } ?: devices.first()
                        selectedPrinterAddress = preferred.address
                        StoreInfoManager.savedPrinterAddress = preferred.address
                        StoreInfoManager.savedPrinterName = preferred.name
                    }
                } catch (e: Throwable) {
                    android.util.Log.w("StoreViewModel", "loadPairedPrinters update note: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            android.util.Log.w("StoreViewModel", "loadPairedPrinters note: ${e.message}")
        }
    }

    fun selectPrinterDevice(device: EscPosPrinter.BluetoothPrinterDevice) {
        selectedPrinterAddress = device.address
        StoreInfoManager.savedPrinterAddress = device.address
        StoreInfoManager.savedPrinterName = device.name
        printerStatusMessage = "Selected printer: ${device.name}"
        if (device.name.lowercase().contains("58") || device.name.lowercase().contains("pos")) {
            updatePaperSize("THERMAL_58MM")
        }
        reconnectPrinter()
    }

    fun reconnectPrinter(onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        val address = selectedPrinterAddress ?: StoreInfoManager.savedPrinterAddress
        if (address.isNullOrBlank()) {
            val msg = "No Bluetooth printer selected"
            printerStatusMessage = msg
            printerConnectionStatus = "DISCONNECTED"
            onResult(false, msg)
            return
        }
        viewModelScope.launch {
            isConnectingPrinter = true
            printerConnectionStatus = "CONNECTING"
            printerStatusMessage = "Connecting to Bluetooth printer..."
            val result = EscPosPrinter.testConnection(address)
            isConnectingPrinter = false
            result.onSuccess {
                printerConnectionStatus = "CONNECTED"
                val msg = "Printer connected successfully!"
                printerStatusMessage = msg
                onResult(true, msg)
            }.onFailure { err ->
                printerConnectionStatus = "ERROR"
                val msg = "Connection error: ${err.message}"
                printerStatusMessage = msg
                onResult(false, msg)
            }
        }
    }

    fun updatePrinterDensity(density: String) {
        StoreInfoManager.thermalPrinterDensity = density
    }

    fun updatePrinterThreshold(threshold: Int) {
        StoreInfoManager.thermalThreshold = threshold.coerceIn(0, 255)
        StoreInfoManager.thermalPrinterDensity = threshold.toString()
    }

    fun updatePrinterSpeed(speed: com.example.utils.PrintSpeed) {
        StoreInfoManager.thermalPrintSpeed = speed
    }

    fun updateReceiptFontSize(fontSize: String) {
        StoreInfoManager.thermalReceiptFontSize = fontSize
    }

    fun updatePrinterFeedLines(feedLines: Int) {
        StoreInfoManager.updateThermalFeedLines(feedLines, getApplication())
    }

    fun testFeedPaper(
        lines: Int = StoreInfoManager.thermalFeedLines,
        onResult: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        val address = selectedPrinterAddress ?: StoreInfoManager.savedPrinterAddress
        if (address.isNullOrBlank()) {
            val msg = "No Bluetooth thermal printer selected in Settings"
            printerStatusMessage = msg
            onResult(false, msg)
            return
        }
        viewModelScope.launch {
            val result = EscPosPrinter.feedPaper(address, lines)
            result.onSuccess {
                val msg = "Advanced paper by $lines lines successfully!"
                printerStatusMessage = msg
                onResult(true, msg)
            }.onFailure { err ->
                val msg = "Paper Feed Error: ${err.message}"
                printerStatusMessage = msg
                onResult(false, msg)
            }
        }
    }

    fun updatePaperSize(paperSize: String) {
        StoreInfoManager.updatePaperSize(paperSize)
    }

    fun printCurrentSale(
        saleWithItems: SaleWithItems,
        isBengali: Boolean = StoreInfoManager.isBillBengali(),
        paperSize: String = StoreInfoManager.pdfPaperSize
    ) {
        val address = selectedPrinterAddress ?: StoreInfoManager.savedPrinterAddress
        if (address.isNullOrBlank()) {
            printerStatusMessage = "No printer selected in Settings"
            return
        }
        viewModelScope.launch {
            printerStatusMessage = "Printing thermal receipt..."
            val result = EscPosPrinter.printReceipt(address, saleWithItems, isBengali, paperSize)
            result.onSuccess {
                printerConnectionStatus = "CONNECTED"
                printerStatusMessage = "Printed successfully!"
            }.onFailure { err ->
                printerConnectionStatus = "ERROR"
                printerStatusMessage = "Print Error: ${err.message}"
            }
        }
    }

    fun printOnlineOrderPackingSlip(
        order: com.example.data.models.Order,
        isBengali: Boolean = StoreInfoManager.isBillBengali(),
        paperSize: String = StoreInfoManager.pdfPaperSize,
        onResult: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        val address = selectedPrinterAddress ?: StoreInfoManager.savedPrinterAddress
        if (address.isNullOrBlank()) {
            val msg = "No thermal printer selected in Settings"
            printerStatusMessage = msg
            onResult(false, msg)
            return
        }
        viewModelScope.launch {
            printerStatusMessage = "Printing packing slip..."
            val result = EscPosPrinter.printOnlineOrderPackingSlip(address, order, isBengali, paperSize)
            result.onSuccess {
                printerConnectionStatus = "CONNECTED"
                val successMsg = "Packing slip printed successfully!"
                printerStatusMessage = successMsg
                onResult(true, successMsg)
            }.onFailure { err ->
                printerConnectionStatus = "ERROR"
                val errMsg = "Print Error: ${err.message}"
                printerStatusMessage = errMsg
                onResult(false, errMsg)
            }
        }
    }

    fun printSaleReturnReceipt(
        returnWithItems: com.example.data.local.entities.SaleReturnWithItems,
        isBengali: Boolean = StoreInfoManager.isBillBengali(),
        paperSize: String = StoreInfoManager.pdfPaperSize,
        onResult: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        val address = selectedPrinterAddress ?: StoreInfoManager.savedPrinterAddress
        if (address.isNullOrBlank()) {
            val msg = "No thermal printer selected in Settings"
            printerStatusMessage = msg
            onResult(false, msg)
            return
        }
        viewModelScope.launch {
            printerStatusMessage = "Printing return/exchange voucher..."
            val result = EscPosPrinter.printSaleReturnReceipt(address, returnWithItems, isBengali, paperSize)
            result.onSuccess {
                printerConnectionStatus = "CONNECTED"
                val successMsg = "Return voucher printed successfully!"
                printerStatusMessage = successMsg
                onResult(true, successMsg)
            }.onFailure { err ->
                printerConnectionStatus = "ERROR"
                val errMsg = "Print Error: ${err.message}"
                printerStatusMessage = errMsg
                onResult(false, errMsg)
            }
        }
    }

    fun printCreditStatement(
        customer: Customer,
        ledgerEntries: List<LedgerEntry>,
        sales: List<SaleWithItems>,
        periodLabel: String = "All-Time",
        startTimestamp: Long? = null,
        endTimestamp: Long? = null,
        openingBalance: Double = 0.0,
        isBengali: Boolean = LanguageManager.isBengali,
        paperSize: String = StoreInfoManager.pdfPaperSize,
        onResult: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        val address = selectedPrinterAddress ?: StoreInfoManager.savedPrinterAddress
        if (address.isNullOrBlank()) {
            val msg = "No thermal printer selected. Please select a Bluetooth printer in Settings."
            printerStatusMessage = msg
            onResult(false, msg)
            return
        }
        viewModelScope.launch {
            val isFiltered = periodLabel != "All-Time" && periodLabel != "সব সময়"
            val displayPeriod = if (isFiltered) " ($periodLabel)" else ""
            printerStatusMessage = "Printing thermal credit statement$displayPeriod..."
            val liveDueBalance = try {
                repository.getCustomerLiveBalance(customer.id)
            } catch (e: Exception) {
                com.example.utils.LedgerCalculator.calculateCustomerBalance(customer.id, ledgerEntries)
            }
            val result = EscPosPrinter.printCreditStatement(
                deviceAddress = address,
                customer = customer,
                ledgerEntries = ledgerEntries,
                sales = sales,
                periodLabel = periodLabel,
                startTimestamp = startTimestamp,
                endTimestamp = endTimestamp,
                openingBalance = openingBalance,
                isBengali = isBengali,
                paperSize = paperSize,
                currentLiveBalance = liveDueBalance
            )
            result.onSuccess {
                printerConnectionStatus = "CONNECTED"
                val msg = "Credit statement$displayPeriod printed successfully!"
                printerStatusMessage = msg
                onResult(true, msg)
            }.onFailure { err ->
                printerConnectionStatus = "ERROR"
                val msg = "Thermal Print Error: ${err.message}"
                printerStatusMessage = msg
                onResult(false, msg)
            }
        }
    }

    fun testPrintReceipt(
        paperSize: String = StoreInfoManager.pdfPaperSize,
        isBengali: Boolean = LanguageManager.isBengali,
        onResult: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        val address = selectedPrinterAddress ?: StoreInfoManager.savedPrinterAddress
        if (address.isNullOrBlank()) {
            val msg = "No Bluetooth thermal printer selected in Settings"
            printerStatusMessage = msg
            onResult(false, msg)
            return
        }
        viewModelScope.launch {
            isTestPrinting = true
            printerConnectionStatus = "CONNECTING"
            printerStatusMessage = "Sending test receipt to thermal printer..."
            val result = EscPosPrinter.testPrint(address, isBengali, paperSize)
            isTestPrinting = false
            result.onSuccess {
                printerConnectionStatus = "CONNECTED"
                val msg = "Thermal test receipt printed successfully!"
                printerStatusMessage = msg
                onResult(true, msg)
            }.onFailure { err ->
                printerConnectionStatus = "ERROR"
                val msg = "Thermal Print Error: ${err.message}"
                printerStatusMessage = msg
                onResult(false, msg)
            }
        }
    }

    fun testPrintEsc7Comparison(
        paperSize: String = StoreInfoManager.pdfPaperSize,
        onResult: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        val address = selectedPrinterAddress ?: StoreInfoManager.savedPrinterAddress
        if (address.isNullOrBlank()) {
            val msg = "No Bluetooth thermal printer selected in Settings"
            printerStatusMessage = msg
            onResult(false, msg)
            return
        }
        viewModelScope.launch {
            isTestPrinting = true
            printerConnectionStatus = "CONNECTING"
            printerStatusMessage = "Sending ESC 7 Min vs Max test to printer..."
            val result = EscPosPrinter.testPrintEsc7Comparison(address, paperSize)
            isTestPrinting = false
            result.onSuccess {
                printerConnectionStatus = "CONNECTED"
                val msg = "ESC 7 Diagnostic printed! Check if Min and Max blocks are visually identical."
                printerStatusMessage = msg
                onResult(true, msg)
            }.onFailure { err ->
                printerConnectionStatus = "ERROR"
                val msg = "Diagnostic Print Error: ${err.message}"
                printerStatusMessage = msg
                onResult(false, msg)
            }
        }
    }

    fun printBarcodeLabels(
        productName: String,
        barcodeStr: String,
        price: Double,
        mrp: Double,
        quantity: Int,
        protocol: String = "TSPL",
        widthMm: Int = 50,
        heightMm: Int = 25,
        gapMm: Int = 2,
        invertOrientation: Boolean = false,
        density: String = StoreInfoManager.thermalPrinterDensity,
        quantityOrUnit: String = "1 N",
        sizeOrVariant: String? = null,
        subtitleOrTag: String = "",
        discountPercentage: Int? = null,
        storeName: String = StoreInfoManager.storeName,
        expiryDate: String? = null,
        labelStyle: String = "MODERN",
        autoCalibrate: Boolean = false,
        onResult: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        val address = selectedPrinterAddress
        if (address.isNullOrBlank()) {
            val msg = "No thermal printer selected in Settings"
            printerStatusMessage = msg
            onResult(false, msg)
            return
        }
        val effectiveQty = (sizeOrVariant?.takeIf { it.isNotBlank() } ?: quantityOrUnit).trim().ifBlank { "1 N" }
        viewModelScope.launch {
            printerStatusMessage = "Printing $quantity barcode label(s)..."
            val result = EscPosPrinter.printBarcodeLabels(
                deviceAddress = address,
                productName = productName,
                barcodeStr = barcodeStr,
                price = price,
                mrp = mrp,
                quantity = quantity,
                protocol = protocol,
                widthMm = widthMm,
                heightMm = heightMm,
                gapMm = gapMm,
                invertOrientation = invertOrientation,
                density = density,
                quantityOrUnit = effectiveQty,
                subtitleOrTag = subtitleOrTag,
                discountPercentage = discountPercentage,
                storeName = storeName,
                expiryDate = expiryDate,
                labelStyle = labelStyle,
                autoCalibrate = autoCalibrate
            )
            result.onSuccess {
                val successMsg = "Successfully printed $quantity barcode label(s)!"
                printerStatusMessage = successMsg
                onResult(true, successMsg)
            }.onFailure { err ->
                val errorMsg = "Thermal Print Error: ${err.message}"
                printerStatusMessage = errorMsg
                onResult(false, errorMsg)
            }
        }
    }

    fun calibratePrinterGap(
        protocol: String = "TSPL",
        widthMm: Int = 50,
        heightMm: Int = 25,
        gapMm: Int = 2,
        onResult: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        val address = selectedPrinterAddress
        if (address.isNullOrBlank()) {
            onResult(false, "No thermal printer selected")
            return
        }
        viewModelScope.launch {
            val res = EscPosPrinter.calibrateLabelPrinter(address, protocol, widthMm, heightMm, gapMm)
            res.onSuccess {
                onResult(true, "Sensor calibrated and label aligned!")
            }.onFailure { err ->
                onResult(false, "Calibration failed: ${err.message}")
            }
        }
    }

    fun clearAllData() {
        viewModelScope.launch {
            repository.clearAllData()
            loadPnlReport(ReportPeriod.TODAY)
        }
    }

    fun seedSampleData() {
        viewModelScope.launch {
            repository.seedSampleDataForce()
        }
    }

    fun checkGoogleSignInAvailability(context: android.content.Context): GoogleSignInAvailability {
        return signInRepository.checkGoogleSignInAvailability(context)
    }

    fun signInWithGoogle(context: android.content.Context, onComplete: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            isAuthLoading = true
            authError = null
            Log.i("StoreViewModel", "[Google Auth] Initiating signInWithGoogle...")
            val result = authManager.signInWithGoogle(context)
            isAuthLoading = false
            result.onSuccess { user ->
                Log.i("StoreViewModel", "[Google Auth] Successfully signed in user: ${user.email}")
                authError = null
                onComplete(true, "Signed in as ${user.displayName ?: user.email}")
                // If user signed in with Google and doesn't have an email password linked yet,
                // and this is a new user (or banner hasn't been dismissed), prompt to set backup password
                if (!user.hasPasswordProvider && (user.isNewUser || !com.example.utils.StoreInfoManager.backupPasswordBannerDismissed)) {
                    openSetBackupPasswordDialog(isFirstTimePrompt = true)
                }
            }.onFailure { e ->
                val isCancelled = e is com.example.data.auth.GoogleSignInCancelledException
                val errMsg = e.message ?: "Google Sign-In failed"
                if (isCancelled) {
                    Log.d("StoreViewModel", "[Google Auth] Google Sign-In was cancelled by user.")
                    authError = errMsg
                    onComplete(false, errMsg)
                } else {
                    Log.e("StoreViewModel", "[Google Auth] Sign in failure: $errMsg", e)
                    authError = errMsg
                    onComplete(false, errMsg)
                }
            }
        }
    }

    fun linkBackupPassword(password: String, context: Context? = null, onComplete: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            isAuthLoading = true
            authError = null
            val result = authManager.linkBackupPassword(password, context)
            isAuthLoading = false
            result.onSuccess { user ->
                authError = null
                onComplete(true, "Backup password successfully set for ${user.email}!")
            }.onFailure { e ->
                authError = e.message
                onComplete(false, e.message)
            }
        }
    }

    fun reauthenticateAndSetPassword(password: String, context: Context, onComplete: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            isAuthLoading = true
            authError = null
            val reauthResult = authManager.reauthenticateWithGoogle(context)
            if (reauthResult.isFailure) {
                isAuthLoading = false
                val errorMsg = reauthResult.exceptionOrNull()?.message ?: "Google verification failed."
                authError = errorMsg
                onComplete(false, errorMsg)
                return@launch
            }
            val linkResult = authManager.linkBackupPassword(password, context)
            isAuthLoading = false
            linkResult.onSuccess { user ->
                authError = null
                onComplete(true, "Backup password successfully set for ${user.email}!")
            }.onFailure { e ->
                authError = e.message
                onComplete(false, e.message)
            }
        }
    }

    fun signInWithEmail(email: String, pass: String, onComplete: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            isAuthLoading = true
            authError = null
            val result = authManager.signInWithEmail(email.trim(), pass)
            isAuthLoading = false
            result.onSuccess { user ->
                onComplete(true, "Welcome ${user.displayName ?: user.email}")
            }.onFailure { e ->
                authError = e.message
                onComplete(false, e.message)
            }
        }
    }

    fun signUpWithEmail(
        email: String,
        pass: String,
        displayName: String? = null,
        storeName: String? = null,
        onComplete: (Boolean, String?) -> Unit
    ) {
        viewModelScope.launch {
            isAuthLoading = true
            authError = null
            val result = authManager.signUpWithEmail(email.trim(), pass, displayName)
            isAuthLoading = false
            result.onSuccess { user ->
                if (!storeName.isNullOrBlank()) {
                    try {
                        com.example.utils.StoreInfoManager.updateStoreInfo(
                            name = storeName.trim(),
                            address = com.example.utils.StoreInfoManager.storeAddress,
                            owner = displayName?.trim() ?: user.displayName ?: com.example.utils.StoreInfoManager.ownerName,
                            phoneNum = com.example.utils.StoreInfoManager.phone,
                            taglineStr = com.example.utils.StoreInfoManager.tagline,
                            gstinStr = com.example.utils.StoreInfoManager.gstin,
                            context = getApplication()
                        )
                    } catch (e: Exception) {
                        Log.w("StoreViewModel", "Could not save initial store name: ${e.message}")
                    }
                }
                onComplete(true, "Account created for ${user.email}")
            }.onFailure { e ->
                authError = e.message
                onComplete(false, e.message)
            }
        }
    }

    fun signUpWithEmail(email: String, pass: String, onComplete: (Boolean, String?) -> Unit) {
        signUpWithEmail(email, pass, null, null, onComplete)
    }

    fun sendPasswordReset(email: String, onComplete: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            isAuthLoading = true
            authError = null
            val result = authManager.sendPasswordReset(email.trim())
            isAuthLoading = false
            result.onSuccess {
                onComplete(true, "Password reset email sent to $email! Please check your inbox.")
            }.onFailure { e ->
                authError = e.message
                onComplete(false, e.message)
            }
        }
    }

    fun signOut(context: android.content.Context? = null) {
        viewModelScope.launch {
            authManager.signOut(context)
        }
    }

    fun exportBackup(context: android.content.Context, outputStream: java.io.OutputStream, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val res = com.example.utils.BackupHelper.exportDataToJson(context, outputStream)
            res.onSuccess {
                onResult(true, "Data backup exported successfully!")
            }.onFailure { err ->
                onResult(false, "Export failed: ${err.message}")
            }
        }
    }

    fun importBackup(context: android.content.Context, inputStream: java.io.InputStream, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val res = com.example.utils.BackupHelper.importDataFromJson(context, inputStream)
            res.onSuccess { count ->
                loadPnlReport(ReportPeriod.TODAY)
                onResult(true, "Backup restored successfully! ($count records loaded)")
            }.onFailure { err ->
                onResult(false, "Import failed: ${err.message}")
            }
        }
    }

    // ==========================================
    // FIRESTORE RBAC & CLOUD REPORT METHODS
    // ==========================================

    fun claimOrRestoreAdminRole(onComplete: (Boolean, String) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val authUser = authManager.getCurrentAuthUser()
            if (authUser != null) {
                val cleanEmail = authUser.email?.trim()?.lowercase()
                val isPermanent = cleanEmail != null && cleanEmail in com.example.data.firestore.PERMANENT_ADMIN_EMAILS
                if (!isPermanent) {
                    onComplete(false, "Permission Denied: Only the store owner account (${com.example.data.firestore.PERMANENT_ADMIN_EMAILS.first()}) can claim Master Admin privileges.")
                    return@launch
                }
                val success = repository.firestoreManager.promoteUserToAdmin(
                    uid = authUser.uid,
                    email = authUser.email,
                    displayName = authUser.displayName
                )
                if (success) {
                    val adminRole = FirestoreUserRole.createAdmin(authUser.uid, authUser.email, authUser.displayName)
                    _currentFirestoreUserRole.value = adminRole
                    applyFirestoreRoleToStaffSession(adminRole)
                    onComplete(true, "👑 Master Admin privileges verified & confirmed in Firestore!")
                } else {
                    onComplete(false, "Failed to update Admin role in Firestore.")
                }
            } else {
                StaffManager.loginAsOwner()
                onComplete(true, "Switched to Master Store Owner (Admin) mode.")
            }
        }
    }

    fun updateUserRoleInFirestore(userRole: FirestoreUserRole, onComplete: (Boolean, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val currentRole = _currentFirestoreUserRole.value
            val authUser = authManager.getCurrentAuthUser()
            val isCurrentAdmin = currentRole?.isAdmin == true || 
                authUser?.email?.trim()?.lowercase() in com.example.data.firestore.PERMANENT_ADMIN_EMAILS ||
                authUser == null

            if (!isCurrentAdmin && !userRole.isPermanentAdmin) {
                onComplete(false, "Permission Denied: Only Store Admin can change user roles in Firestore.")
                return@launch
            }
            val success = repository.firestoreManager.saveUserRole(userRole)
            if (success) {
                repository.syncAppUsersToEmployees(listOf(userRole))
                onComplete(true, "Role and permissions for ${userRole.displayName ?: userRole.email} updated in Firestore!")
            } else {
                onComplete(false, "Failed to save user role to Firestore.")
            }
        }
    }

    fun deleteCloudUserFromFirestore(userRole: FirestoreUserRole, onComplete: (Boolean, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val currentRole = _currentFirestoreUserRole.value
            val authUser = authManager.getCurrentAuthUser()
            val isCurrentAdmin = currentRole?.isAdmin == true || 
                authUser?.email?.trim()?.lowercase() in com.example.data.firestore.PERMANENT_ADMIN_EMAILS ||
                authUser == null

            if (!isCurrentAdmin) {
                onComplete(false, "Permission Denied: Only Store Admin can delete users from Firestore.")
                return@launch
            }

            // Only block deleting the exact currently active session UID
            if (authUser != null && userRole.uid == authUser.uid) {
                onComplete(false, "You cannot delete your own currently active session.")
                return@launch
            }

            val success = repository.firestoreManager.deleteUserRole(
                uid = userRole.uid,
                email = userRole.email,
                documentId = userRole.documentId
            )
            if (success) {
                val cleanEmail = userRole.email?.trim()?.lowercase() ?: ""
                val existingEmp = repository.getEmployeeById("cloud_${userRole.uid}")
                    ?: repository.getEmployeeById("cloud_${cleanEmail.replace(".", "_").replace("@", "_")}")
                    ?: (if (cleanEmail.isNotBlank()) repository.getEmployeeByEmail(cleanEmail) else null)
                if (existingEmp != null) {
                    repository.deleteEmployee(existingEmp)
                }
                onComplete(true, "Cloud user '${userRole.displayName ?: userRole.email ?: userRole.uid}' removed successfully.")
            } else {
                onComplete(false, "Failed to remove user from Cloud Firestore.")
            }
        }
    }

    fun cleanDuplicateCloudUsers(onComplete: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val authUser = authManager.getCurrentAuthUser()
            val deletedCount = repository.firestoreManager.cleanDuplicateUsers(authUser?.uid)
            if (deletedCount > 0) {
                onComplete(true, "Cleaned up $deletedCount duplicate cloud user account(s).")
            } else {
                onComplete(true, "No duplicate cloud accounts found to clean.")
            }
        }
    }

    fun savePreassignedRole(email: String, userRole: FirestoreUserRole, onComplete: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val currentRole = _currentFirestoreUserRole.value
            val authUser = authManager.getCurrentAuthUser()
            val isCurrentAdmin = currentRole?.isAdmin == true || 
                authUser?.email?.trim()?.lowercase() in com.example.data.firestore.PERMANENT_ADMIN_EMAILS ||
                authUser == null

            if (!isCurrentAdmin) {
                onComplete(false, "Permission Denied: Only Store Admin can assign employee roles.")
                return@launch
            }

            val success = repository.firestoreManager.savePreassignedRole(email, userRole)
            if (success) {
                repository.syncAppUsersToEmployees(listOf(userRole))
                onComplete(true, "Role for $email successfully pre-assigned / updated!")
            } else {
                onComplete(false, "Failed to save preassigned role.")
            }
        }
    }

    fun deletePreassignedRole(email: String, onComplete: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val currentRole = _currentFirestoreUserRole.value
            val authUser = authManager.getCurrentAuthUser()
            val isCurrentAdmin = currentRole?.isAdmin == true || 
                authUser?.email?.trim()?.lowercase() in com.example.data.firestore.PERMANENT_ADMIN_EMAILS ||
                authUser == null

            if (!isCurrentAdmin) {
                onComplete(false, "Permission Denied: Only Store Admin can remove employee roles.")
                return@launch
            }

            val success = repository.firestoreManager.deletePreassignedRole(email)
            if (success) {
                val cleanEmail = email.trim().lowercase()
                val existingEmp = repository.getEmployeeById("cloud_${cleanEmail.replace(".", "_").replace("@", "_")}")
                    ?: (if (cleanEmail.isNotBlank()) repository.getEmployeeByEmail(cleanEmail) else null)
                if (existingEmp != null) {
                    repository.deleteEmployee(existingEmp)
                }
                onComplete(true, "Preassigned role for $email removed.")
            } else {
                onComplete(false, "Failed to remove preassigned role.")
            }
        }
    }

    fun publishProfitReportToFirestore(period: ReportPeriod, onComplete: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val report = pnlReport ?: run {
                val (st, et) = getPeriodTimeBounds(period)
                repository.generatePnlReport(st, et)
            }
            val (startTime, endTime) = getPeriodTimeBounds(period)
            val authUser = authManager.getCurrentAuthUser()
            val firestoreReport = FirestoreProfitReport(
                reportId = "REPORT_${period.name}_${System.currentTimeMillis()}",
                period = period.name,
                startTime = startTime,
                endTime = endTime,
                totalRevenue = report.totalRevenue,
                totalCostOfGoods = report.totalCogs,
                grossProfit = report.grossProfit,
                totalExpenses = report.totalExpenses,
                stockLoss = report.stockLoss,
                netProfit = report.netProfit,
                profitMarginPercent = if (report.totalRevenue > 0) (report.netProfit / report.totalRevenue) * 100.0 else 0.0,
                totalSalesCount = report.totalSalesCount,
                generatedAt = System.currentTimeMillis(),
                generatedByUid = authUser?.uid ?: "admin_local",
                generatedByName = authUser?.displayName ?: authUser?.email ?: "Store Admin",
                requiredRole = "ADMIN",
                storeId = "default_store"
            )
            val result = repository.firestoreManager.saveProfitReport(firestoreReport, _currentFirestoreUserRole.value)
            result.onSuccess {
                fetchCloudProfitReports()
                onComplete(true, "Profit report securely synced to Firestore database!")
            }.onFailure { err ->
                onComplete(false, err.message ?: "Failed to publish report to Firestore")
            }
        }
    }

    fun fetchCloudProfitReports(onComplete: (List<FirestoreProfitReport>?, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val result = repository.firestoreManager.getProfitReports(_currentFirestoreUserRole.value)
            result.onSuccess { reports ->
                _cloudProfitReports.value = reports
                _cloudReportErrorMessage.value = null
                onComplete(reports, null)
            }.onFailure { err ->
                _cloudReportErrorMessage.value = err.message
                onComplete(null, err.message)
            }
        }
    }

    // ==========================================
    // ALL-DATA TWO-WAY CLOUD SYNCHRONIZATION
    // ==========================================

    fun triggerImmediateAutoSync(
        showLoading: Boolean = false,
        onComplete: ((Boolean, String) -> Unit)? = null
    ) {
        viewModelScope.launch {
            if (_isSyncingAllData.value) {
                onComplete?.invoke(false, "Sync is already running in background.")
                return@launch
            }
            if (showLoading) _isSyncingAllData.value = true
            try {
                // Also trigger WorkManager immediate sync to guarantee execution across app lifecycle
                SyncWorkManager.triggerImmediateSync(getApplication())

                val summary = repository.syncAllData()
                _lastSyncSummary.value = summary
                if (summary.success) {
                    val now = System.currentTimeMillis()
                    _lastSyncTimestamp.value = now
                    val prefs = getApplication<android.app.Application>().getSharedPreferences("sync_prefs", android.content.Context.MODE_PRIVATE)
                    prefs.edit().putLong("last_auto_sync_time", now).apply()
                    com.example.utils.NetworkMonitor.markSynced()
                    onComplete?.invoke(true, summary.message)
                } else {
                    onComplete?.invoke(false, summary.message)
                }
            } catch (e: Exception) {
                android.util.Log.w("StoreViewModel", "Immediate auto-sync error note: ${e.message}")
                onComplete?.invoke(false, e.message ?: "Immediate sync error")
            } finally {
                if (showLoading) _isSyncingAllData.value = false
            }
        }
    }

    fun syncAllDataNow(onComplete: (Boolean, String) -> Unit = { _, _ -> }) {
        triggerImmediateAutoSync(showLoading = true, onComplete = onComplete)
    }

    fun syncUploadAllToCloud(onComplete: (Boolean, String) -> Unit = { _, _ -> }) {
        if (_isSyncingAllData.value) {
            onComplete(false, "Sync is already in progress.")
            return
        }
        viewModelScope.launch {
            _isSyncingAllData.value = true
            val summary = repository.syncAllDataToFirestore()
            _isSyncingAllData.value = false
            _lastSyncSummary.value = summary
            if (summary.success) {
                _lastSyncTimestamp.value = System.currentTimeMillis()
                onComplete(true, summary.message)
            } else {
                onComplete(false, summary.message)
            }
        }
    }

    fun syncDownloadAllFromCloud(onComplete: (Boolean, String) -> Unit = { _, _ -> }) {
        if (_isSyncingAllData.value) {
            onComplete(false, "Sync is already in progress.")
            return
        }
        viewModelScope.launch {
            _isSyncingAllData.value = true
            val summary = repository.syncAllDataFromFirestore()
            _isSyncingAllData.value = false
            _lastSyncSummary.value = summary
            if (summary.success) {
                _lastSyncTimestamp.value = System.currentTimeMillis()
                onComplete(true, summary.message)
            } else {
                onComplete(false, summary.message)
            }
        }
    }

    // ==========================================
    // AUTOMATIC SYNC & BACKUP CONTROLS
    // ==========================================

    fun setAutoSyncEnabled(enabled: Boolean) {
        _isAutoSyncEnabled.value = enabled
        backupPrefs.edit().putBoolean("auto_sync_enabled", enabled).apply()
        if (enabled) {
            SyncWorkManager.schedulePeriodicSync(
                getApplication(),
                _autoSyncIntervalMinutes.value.toLong(),
                forceUpdate = true
            )
        } else {
            SyncWorkManager.cancelPeriodicSync(getApplication())
        }
    }

    fun setAutoSyncIntervalMinutes(minutes: Int) {
        val validMins = minutes.coerceIn(1, 1440)
        _autoSyncIntervalMinutes.value = validMins
        backupPrefs.edit().putInt("auto_sync_interval", validMins).apply()
        if (_isAutoSyncEnabled.value) {
            SyncWorkManager.schedulePeriodicSync(
                getApplication(),
                validMins.toLong(),
                forceUpdate = true
            )
        }
    }

    fun setDriveAccountEmail(email: String?) {
        val cleanEmail = email?.trim()
        googleDriveManager.savedAccountEmail = cleanEmail
        _driveAccountEmail.value = cleanEmail
    }

    fun getEffectiveDriveAccountEmail(explicitEmail: String? = null): String {
        return explicitEmail?.trim()?.ifBlank { null }
            ?: _driveAccountEmail.value?.trim()?.ifBlank { null }
            ?: googleDriveManager.savedAccountEmail?.trim()?.ifBlank { null }
            ?: googleDriveManager.getAvailableGoogleAccounts().firstOrNull()
            ?: currentUser.value?.email?.trim()?.ifBlank { null }
            ?: "shuvajitsow384@gmail.com"
    }

    fun backupToGoogleDrive(
        accountEmail: String? = null,
        labelNote: String? = null,
        onComplete: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        if (_isDriveLoading.value) {
            onComplete(false, "Drive operation currently in progress.")
            return
        }
        val targetEmail = getEffectiveDriveAccountEmail(accountEmail)

        viewModelScope.launch {
            _isDriveLoading.value = true
            _driveStatusMessage.value = "Connecting to Google Drive ($targetEmail)..."
            try {
                val uploadResult = googleDriveManager.executeWithTokenRefresh(targetEmail) { token ->
                    _driveStatusMessage.value = "Uploading backup snapshot to 'Amar Dukan Backups'..."
                    googleDriveManager.backupNow(token, labelNote)
                }
                _isDriveLoading.value = false

                if (uploadResult.isSuccess) {
                    val backupFile = uploadResult.getOrThrow()
                    _lastDriveBackupTimestamp.value = backupFile.timestamp
                    _driveStatusMessage.value = "Backup saved to Google Drive: ${backupFile.name}"
                    // Refresh file listing
                    fetchGoogleDriveBackups(targetEmail)
                    onComplete(true, "Backup saved to Google Drive folder 'Amar Dukan Backups'!\nFile: ${backupFile.name} (${backupFile.formattedSize})")
                } else {
                    val ex = uploadResult.exceptionOrNull()
                    if (ex is com.google.android.gms.auth.UserRecoverableAuthException) {
                        _driveAuthIntent.value = ex.intent
                    }
                    val err = ex?.message ?: "Failed to upload to Google Drive"
                    _driveStatusMessage.value = err
                    onComplete(false, err)
                }
            } catch (e: Exception) {
                _isDriveLoading.value = false
                if (e is com.google.android.gms.auth.UserRecoverableAuthException) {
                    _driveAuthIntent.value = e.intent
                }
                val err = e.message ?: "Google Drive backup failed"
                _driveStatusMessage.value = err
                onComplete(false, err)
            }
        }
    }

    fun fetchGoogleDriveBackups(
        accountEmail: String? = null,
        onComplete: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        val targetEmail = getEffectiveDriveAccountEmail(accountEmail)

        viewModelScope.launch {
            _isDriveLoading.value = true
            try {
                val listResult = googleDriveManager.executeWithTokenRefresh(targetEmail) { token ->
                    googleDriveManager.listBackups(token)
                }
                _isDriveLoading.value = false
                if (listResult.isSuccess) {
                    val files = listResult.getOrThrow()
                    _driveBackupsList.value = files
                    onComplete(true, "Found ${files.size} backups in Google Drive.")
                } else {
                    val ex = listResult.exceptionOrNull()
                    if (ex is com.google.android.gms.auth.UserRecoverableAuthException) {
                        _driveAuthIntent.value = ex.intent
                    }
                    val err = ex?.message ?: "Failed to fetch backups from Drive"
                    onComplete(false, err)
                }
            } catch (e: Exception) {
                _isDriveLoading.value = false
                if (e is com.google.android.gms.auth.UserRecoverableAuthException) {
                    _driveAuthIntent.value = e.intent
                }
                onComplete(false, "Drive error: ${e.message}")
            }
        }
    }

    fun restoreFromGoogleDriveBackup(
        file: DriveBackupFile,
        accountEmail: String? = null,
        onComplete: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        if (_isDriveLoading.value) {
            onComplete(false, "Operation already in progress.")
            return
        }
        val targetEmail = getEffectiveDriveAccountEmail(accountEmail)

        viewModelScope.launch {
            _isDriveLoading.value = true
            _driveStatusMessage.value = "Downloading ${file.name} from Google Drive..."
            try {
                val restoreResult = googleDriveManager.executeWithTokenRefresh(targetEmail) { token ->
                    googleDriveManager.restoreFromBackup(token, file.id)
                }
                _isDriveLoading.value = false

                if (restoreResult.isSuccess) {
                    val count = restoreResult.getOrThrow()
                    _driveStatusMessage.value = "Restored $count records from Google Drive backup."
                    // Refresh PNL & local view data
                    loadPnlReport(ReportPeriod.TODAY)
                    onComplete(true, "Successfully restored $count records from Google Drive backup:\n${file.name}")
                } else {
                    val ex = restoreResult.exceptionOrNull()
                    if (ex is com.google.android.gms.auth.UserRecoverableAuthException) {
                        _driveAuthIntent.value = ex.intent
                    }
                    val err = ex?.message ?: "Failed to restore backup from Drive"
                    _driveStatusMessage.value = err
                    onComplete(false, err)
                }
            } catch (e: Exception) {
                _isDriveLoading.value = false
                if (e is com.google.android.gms.auth.UserRecoverableAuthException) {
                    _driveAuthIntent.value = e.intent
                }
                val err = "Drive restore error: ${e.message}"
                _driveStatusMessage.value = err
                onComplete(false, err)
            }
        }
    }

    fun deleteGoogleDriveBackup(
        fileId: String,
        accountEmail: String? = null,
        onComplete: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        val targetEmail = getEffectiveDriveAccountEmail(accountEmail)

        viewModelScope.launch {
            _isDriveLoading.value = true
            try {
                val deleteResult = googleDriveManager.executeWithTokenRefresh(targetEmail) { token ->
                    googleDriveManager.deleteBackup(token, fileId)
                }
                _isDriveLoading.value = false

                if (deleteResult.isSuccess) {
                    fetchGoogleDriveBackups(targetEmail)
                    onComplete(true, "Backup file deleted from Google Drive.")
                } else {
                    val ex = deleteResult.exceptionOrNull()
                    if (ex is com.google.android.gms.auth.UserRecoverableAuthException) {
                        _driveAuthIntent.value = ex.intent
                    }
                    val err = ex?.message ?: "Failed to delete backup file"
                    onComplete(false, err)
                }
            } catch (e: Exception) {
                _isDriveLoading.value = false
                if (e is com.google.android.gms.auth.UserRecoverableAuthException) {
                    _driveAuthIntent.value = e.intent
                }
                onComplete(false, "Error: ${e.message}")
            }
        }
    }

    fun setAutoCloudBackupEnabled(enabled: Boolean) {
        _isAutoCloudBackupEnabled.value = enabled
        backupPrefs.edit().putBoolean("auto_cloud_backup_enabled", enabled).apply()
    }

    fun setAutoLocalBackupEnabled(enabled: Boolean) {
        _isAutoLocalBackupEnabled.value = enabled
        backupPrefs.edit().putBoolean("auto_local_backup_enabled", enabled).apply()
    }

    fun refreshBackupSnapshots() {
        viewModelScope.launch {
            // Local files
            val localFiles = com.example.utils.BackupHelper.listLocalBackupFiles(getApplication())
            _localBackupSnapshots.value = localFiles

            // Cloud snapshots from Firestore
            val cloudSnaps = repository.firestoreManager.getCloudBackupSnapshotsList()
            _cloudBackupSnapshots.value = cloudSnaps
        }
    }

    private suspend fun createAutoBackupSnapshotInternal() {
        val app = getApplication<Application>()
        try {
            val jsonRes = com.example.utils.BackupHelper.exportDataToJsonString(app)
            if (jsonRes.isSuccess) {
                val jsonPayload = jsonRes.getOrNull() ?: return
                var totalRecs = 0
                try {
                    val root = org.json.JSONObject(jsonPayload)
                    val keys = listOf("products", "productBatches", "sales", "returns", "purchases", "customers", "suppliers", "expenses", "ledgerEntries", "employees", "employeeAttendance", "employeeSalaryPayments", "employeeSalaryDues", "employeeAdvances")
                    for (k in keys) {
                        if (root.has(k)) totalRecs += root.getJSONArray(k).length()
                    }
                } catch (e: Exception) {
                    totalRecs = 0
                }

                if (_isAutoLocalBackupEnabled.value) {
                    com.example.utils.BackupHelper.createLocalAutoBackup(app, "auto")
                }

                if (_isAutoCloudBackupEnabled.value) {
                    val snapId = "snap_${System.currentTimeMillis()}"
                    val user = currentUser.value
                    repository.firestoreManager.saveCloudBackupSnapshot(
                        snapshotId = snapId,
                        label = "Auto Cloud Snapshot",
                        totalRecords = totalRecs,
                        jsonPayload = jsonPayload,
                        userEmail = user?.email
                    )
                }

                val now = System.currentTimeMillis()
                _lastAutoBackupTimestamp.value = now
                backupPrefs.edit().putLong("last_auto_backup_time", now).apply()
                refreshBackupSnapshots()
            }
        } catch (e: Exception) {
            android.util.Log.w("StoreViewModel", "Internal auto backup error note: ${e.message}")
        }
    }

    fun createInstantBackup(label: String = "Manual", onComplete: (Boolean, String) -> Unit = { _, _ -> }) {
        if (_isBackupProcessing.value) {
            onComplete(false, "Backup is currently processing.")
            return
        }
        viewModelScope.launch {
            _isBackupProcessing.value = true
            val app = getApplication<Application>()
            try {
                val jsonRes = com.example.utils.BackupHelper.exportDataToJsonString(app)
                if (jsonRes.isSuccess) {
                    val jsonPayload = jsonRes.getOrNull() ?: ""
                    var totalRecs = 0
                    try {
                        val root = org.json.JSONObject(jsonPayload)
                        val keys = listOf("products", "productBatches", "sales", "returns", "purchases", "customers", "suppliers", "expenses", "ledgerEntries", "employees", "employeeAttendance", "employeeSalaryPayments", "employeeSalaryDues", "employeeAdvances")
                        for (k in keys) {
                            if (root.has(k)) totalRecs += root.getJSONArray(k).length()
                        }
                    } catch (e: Exception) {
                        totalRecs = 0
                    }

                    // Save local copy
                    val localRes = com.example.utils.BackupHelper.createLocalAutoBackup(app, label.lowercase().replace(" ", "_"))

                    // Save cloud snapshot
                    val snapId = "snap_${System.currentTimeMillis()}"
                    val user = currentUser.value
                    val cloudSaved = repository.firestoreManager.saveCloudBackupSnapshot(
                        snapshotId = snapId,
                        label = label,
                        totalRecords = totalRecs,
                        jsonPayload = jsonPayload,
                        userEmail = user?.email
                    )

                    val now = System.currentTimeMillis()
                    _lastAutoBackupTimestamp.value = now
                    backupPrefs.edit().putLong("last_auto_backup_time", now).apply()
                    refreshBackupSnapshots()
                    _isBackupProcessing.value = false

                    val msg = if (cloudSaved && localRes.isSuccess) {
                        "Backup created successfully! ($totalRecs records saved to Local Storage & Cloud Firestore)"
                    } else if (localRes.isSuccess) {
                        "Local backup created ($totalRecs records). Note: Cloud sync requires network/auth."
                    } else {
                        "Backup created with $totalRecs records."
                    }
                    onComplete(true, msg)
                } else {
                    _isBackupProcessing.value = false
                    onComplete(false, jsonRes.exceptionOrNull()?.message ?: "Failed to generate backup JSON")
                }
            } catch (e: Exception) {
                _isBackupProcessing.value = false
                onComplete(false, "Backup error: ${e.message}")
            }
        }
    }

    fun restoreFromLocalBackupFile(file: java.io.File, onComplete: (Boolean, String) -> Unit = { _, _ -> }) {
        if (_isBackupProcessing.value) {
            onComplete(false, "Operation in progress.")
            return
        }
        viewModelScope.launch {
            _isBackupProcessing.value = true
            try {
                val inputStream = java.io.FileInputStream(file)
                val result = com.example.utils.BackupHelper.importDataFromJson(getApplication(), inputStream)
                _isBackupProcessing.value = false
                if (result.isSuccess) {
                    val count = result.getOrNull() ?: 0
                    onComplete(true, "Successfully restored $count records from local snapshot: ${file.name}")
                } else {
                    onComplete(false, result.exceptionOrNull()?.message ?: "Failed to restore backup")
                }
            } catch (e: Exception) {
                _isBackupProcessing.value = false
                onComplete(false, "Restore error: ${e.message}")
            }
        }
    }

    fun restoreFromCloudBackupSnapshot(snapshotId: String, onComplete: (Boolean, String) -> Unit = { _, _ -> }) {
        if (_isBackupProcessing.value) {
            onComplete(false, "Operation in progress.")
            return
        }
        viewModelScope.launch {
            _isBackupProcessing.value = true
            try {
                val payload = repository.firestoreManager.getCloudBackupSnapshotPayload(snapshotId)
                if (payload.isNullOrBlank()) {
                    _isBackupProcessing.value = false
                    onComplete(false, "Could not find snapshot payload in Firestore.")
                    return@launch
                }
                val result = com.example.utils.BackupHelper.importDataFromJsonString(getApplication(), payload)
                _isBackupProcessing.value = false
                if (result.isSuccess) {
                    val count = result.getOrNull() ?: 0
                    onComplete(true, "Successfully restored $count records from cloud snapshot!")
                } else {
                    onComplete(false, result.exceptionOrNull()?.message ?: "Failed to parse cloud snapshot")
                }
            } catch (e: Exception) {
                _isBackupProcessing.value = false
                onComplete(false, "Cloud restore error: ${e.message}")
            }
        }
    }

    fun deleteLocalBackupSnapshot(file: java.io.File) {
        com.example.utils.BackupHelper.deleteLocalBackupFile(file)
        refreshBackupSnapshots()
    }

    fun deleteCloudBackupSnapshot(snapshotId: String) {
        viewModelScope.launch {
            repository.firestoreManager.deleteCloudBackupSnapshot(snapshotId)
            refreshBackupSnapshots()
        }
    }

    private val _isDeletingAllData = MutableStateFlow(false)
    val isDeletingAllData: StateFlow<Boolean> = _isDeletingAllData.asStateFlow()

    private val _dataDeletionStatusMessage = MutableStateFlow<String?>(null)
    val dataDeletionStatusMessage: StateFlow<String?> = _dataDeletionStatusMessage.asStateFlow()

    /**
     * Wipes all application data based on user selections:
     * - Local SQLite database
     * - Firestore cloud database records
     * - Local automatic and manual JSON backup snapshots
     * - Google Drive backup files in 'Amar Dukan Backups' folder (if requested and signed in)
     */
    fun deleteAllAppData(
        deleteLocal: Boolean = true,
        deleteCloud: Boolean = true,
        deleteLocalBackups: Boolean = true,
        deleteDriveBackups: Boolean = false,
        onComplete: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            _isDeletingAllData.value = true
            _dataDeletionStatusMessage.value = "Deleting data..."
            try {
                val result = repository.deleteAllAppData(
                    deleteLocalData = deleteLocal,
                    deleteCloudData = deleteCloud,
                    deleteLocalBackups = deleteLocalBackups,
                    context = getApplication()
                )

                if (deleteDriveBackups) {
                    try {
                        val targetEmail = getEffectiveDriveAccountEmail()
                        val driveResult = googleDriveManager.executeWithTokenRefresh(targetEmail) { token ->
                            val listResult = googleDriveManager.listBackups(token)
                            if (listResult.isSuccess) {
                                val files = listResult.getOrDefault(emptyList())
                                for (file in files) {
                                    googleDriveManager.deleteBackup(token, file.id)
                                }
                                Result.success(files.size)
                            } else {
                                Result.failure(listResult.exceptionOrNull() ?: Exception("Failed to list drive backups"))
                            }
                        }
                        if (driveResult.isSuccess) {
                            fetchGoogleDriveBackups(targetEmail)
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("StoreViewModel", "Drive backup deletion error: ${e.message}")
                    }
                }

                if (deleteLocal) {
                    clearCart()
                }

                refreshBackupSnapshots()

                if (result.isSuccess) {
                    val msg = result.getOrNull() ?: "All selected data was successfully deleted."
                    _dataDeletionStatusMessage.value = msg
                    onComplete(true, msg)
                } else {
                    val err = result.exceptionOrNull()?.message ?: "Failed to delete data."
                    _dataDeletionStatusMessage.value = "Error: $err"
                    onComplete(false, err)
                }
            } catch (e: Exception) {
                val err = e.message ?: "Unknown error occurred"
                _dataDeletionStatusMessage.value = "Error: $err"
                onComplete(false, err)
            } finally {
                _isDeletingAllData.value = false
            }
        }
    }

    // ==================== OFFERS MANAGEMENT ====================

    fun canManageOffers(): Boolean {
        if (com.example.utils.StaffManager.isOwner()) return true
        val role = currentFirestoreUserRole.value
        if (role != null) {
            if (role.isAdmin || role.isManager || role.effectiveCanManageInventory || role.effectiveCanAccessSettings) {
                return true
            }
        }
        val staff = com.example.utils.StaffManager.activeStaff
        if (staff != null) {
            if (staff.role == "STORE_MANAGER" || staff.canManageInventory || staff.canAccessSettings) {
                return true
            }
        }
        return false
    }

    fun saveOffer(
        offer: Offer,
        authorized: Boolean = false,
        onComplete: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        if (!authorized && !canManageOffers()) {
            onComplete(false, "Permission Denied: Only Admin or Store Manager can manage offers.")
            return
        }
        viewModelScope.launch {
            try {
                repository.insertOffer(offer)
                onComplete(true, "Offer '${offer.name}' saved successfully!")
            } catch (e: Exception) {
                onComplete(false, "Failed to save offer: ${e.message}")
            }
        }
    }

    fun toggleOfferActive(
        offerId: String,
        isActive: Boolean,
        authorized: Boolean = false,
        onComplete: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        if (!authorized && !canManageOffers()) {
            onComplete(false, "Permission Denied: Only Admin or Store Manager can manage offers.")
            return
        }
        viewModelScope.launch {
            try {
                repository.toggleOfferActive(offerId, isActive)
                onComplete(true, if (isActive) "Offer activated!" else "Offer deactivated!")
            } catch (e: Exception) {
                onComplete(false, "Failed to update offer: ${e.message}")
            }
        }
    }

    fun deleteOffer(
        offer: Offer,
        authorized: Boolean = false,
        onComplete: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        if (!authorized && !canManageOffers()) {
            onComplete(false, "Permission Denied: Only Admin or Store Manager can manage offers.")
            return
        }
        viewModelScope.launch {
            try {
                repository.deleteOffer(offer)
                onComplete(true, "Offer deleted successfully!")
            } catch (e: Exception) {
                onComplete(false, "Failed to delete offer: ${e.message}")
            }
        }
    }

    // ==================== KHATA LATE PAYMENT INTEREST ====================

    fun updateKhataInterestSettings(
        enabled: Boolean,
        monthlyRate: Double,
        graceDays: Int,
        calculationMode: String,
        applyRetroactively: Boolean,
        disclaimerText: String,
        onComplete: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        try {
            StoreInfoManager.updateInterestSettings(
                enabled = enabled,
                monthlyRate = monthlyRate,
                graceDays = graceDays,
                calculationMode = calculationMode,
                applyRetroactively = applyRetroactively,
                disclaimerText = disclaimerText,
                context = getApplication()
            )
            onComplete(true, "Khata interest settings updated successfully!")
        } catch (e: Exception) {
            onComplete(false, "Failed to save settings: ${e.message}")
        }
    }

    fun calculateCustomerInterest(
        customer: Customer,
        entries: List<LedgerEntry> = emptyList()
    ): CustomerInterestBreakdown {
        return KhataInterestCalculator.calculateCustomerInterest(
            customer = customer,
            allLedgerEntries = entries,
            settings = StoreInfoManager.getKhataInterestSettings()
        )
    }

    fun calculateCustomerInterestAsync(
        customer: Customer,
        onResult: (CustomerInterestBreakdown) -> Unit
    ) {
        viewModelScope.launch {
            val breakdown = repository.calculateCustomerInterestBreakdown(customer)
            onResult(breakdown)
        }
    }

    fun postCustomerAccruedInterest(
        customer: Customer,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            try {
                val breakdown = repository.calculateCustomerInterestBreakdown(customer)
                if (breakdown.totalAccruedInterest <= 0.0) {
                    onError("No accrued interest to post")
                    return@launch
                }
                val res = repository.postAccruedInterestToLedger(customer, breakdown.totalAccruedInterest)
                res.onSuccess {
                    onSuccess()
                }.onFailure { ex ->
                    onError(ex.message ?: "Failed to post interest")
                }
            } catch (e: Exception) {
                onError(e.message ?: "Error calculating interest")
            }
        }
    }

    fun postAccruedCustomerInterest(
        customer: Customer,
        amount: Double,
        customNote: String? = null,
        onComplete: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            val res = repository.postAccruedInterestToLedger(customer, amount, customNote)
            res.onSuccess { entry ->
                onComplete(true, "Interest of ₹${"%.2f".format(entry.amount)} posted to ${customer.name}'s Khata!")
            }.onFailure { ex ->
                onComplete(false, "Failed to post interest: ${ex.message}")
            }
        }
    }

    /**
     * Executes the one-time admin staff deduplication and merge operation.
     */
    fun mergeDuplicateStaffProfiles(onComplete: (com.example.data.repository.StaffMergeResult) -> Unit = {}) {
        viewModelScope.launch {
            isMergingDuplicateStaff = true
            val result = repository.mergeDuplicateStaffProfiles()
            staffMergeResult = result
            isMergingDuplicateStaff = false
            showStaffMergeResultDialog = true
            onComplete(result)
        }
    }

    suspend fun getLiveStaffUserDetails(uid: String): FirestoreUserRole? {
        return repository.firestoreManager.getLiveStaffUserDetails(uid)
    }

    fun reviewPendingStaffAccess(
        uid: String,
        approve: Boolean,
        assignedRole: com.example.data.firestore.AppRole = com.example.data.firestore.AppRole.EMPLOYEE,
        onComplete: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            val reviewer = currentUser.value?.email ?: currentUser.value?.uid ?: "Store Owner"
            val result = repository.firestoreManager.reviewPendingStaffAccess(
                uid = uid,
                approve = approve,
                assignedRole = assignedRole,
                reviewerEmailOrUid = reviewer
            )
            result.onSuccess {
                val action = if (approve) "approved" else "declined"
                onComplete(true, "Staff access request successfully $action.")
            }.onFailure { err ->
                onComplete(false, err.message ?: "Failed to process request")
            }
        }
    }
}

data class PendingCreditSms(
    val customerName: String,
    val phone: String?,
    val message: String,
    val saleId: String
)


