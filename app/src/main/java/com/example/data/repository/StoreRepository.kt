package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.firestore.FirestoreManager
import com.example.data.local.AppDatabase
import com.example.data.local.dao.PurchaseWithItems
import com.example.data.local.dao.SaleWithItems
import com.example.data.local.entities.*
import com.example.data.models.CreditLimitExceededException
import com.example.data.models.InsufficientStockException
import com.example.data.models.InsufficientStockItem
import com.example.data.sync.DataSyncManager
import com.example.utils.BackupHelper
import com.example.utils.CustomerInterestBreakdown
import com.example.utils.KhataInterestCalculator
import com.example.utils.KhataInterestSettings
import com.example.utils.LedgerCalculator
import com.example.utils.NotificationHelper
import com.example.utils.SalaryBreakdown
import com.example.utils.SalaryCalculator
import com.example.utils.StoreInfoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

data class ProfitAndLossReport(
    val totalRevenue: Double,
    val totalCogs: Double,
    val grossProfit: Double,
    val totalExpenses: Double,
    val stockLoss: Double = 0.0,
    val netProfit: Double,
    val totalSalesCount: Int,
    val cashSales: Double,
    val upiSales: Double,
    val creditSales: Double,
    val expensesList: List<com.example.data.local.entities.Expense> = emptyList(),
    val expenseCategoryBreakdown: List<com.example.utils.ExpenseCategorySummary> = emptyList(),
    val totalPurchasesSpend: Double = 0.0,
    val staffSalarySpend: Double = 0.0,
    val totalMoneySpent: Double = 0.0,
    val topExpenseCategory: String? = null,
    val narrativeInWordsEn: String = "",
    val narrativeInWordsBn: String = "",
    val totalExpensesInWordsEn: String = "",
    val totalExpensesInWordsBn: String = "",
    val onlineRevenue: Double = 0.0,
    val onlineCogs: Double = 0.0,
    val onlineGrossProfit: Double = 0.0,
    val onlineOrderCount: Int = 0,
    val walkInRevenue: Double = 0.0,
    val walkInCogs: Double = 0.0,
    val walkInGrossProfit: Double = 0.0,
    val walkInOrderCount: Int = 0,
    val onlineRevenuePercentage: Double = 0.0
)

data class StockOutReportSummary(
    val startTime: Long,
    val endTime: Long,
    val entries: List<StockOutEntry> = emptyList(),
    val damagedCost: Double = 0.0,
    val damagedQty: Double = 0.0,
    val expiredCost: Double = 0.0,
    val expiredQty: Double = 0.0,
    val wastageCost: Double = 0.0,
    val wastageQty: Double = 0.0,
    val personalUseCost: Double = 0.0,
    val personalUseQty: Double = 0.0,
    val otherCost: Double = 0.0,
    val otherQty: Double = 0.0,
    val totalBusinessLossCost: Double = 0.0, // Damaged + Expired + Wastage
    val combinedTotalCost: Double = 0.0 // All stock removals
)

data class InventoryValuationReport(
    val totalItemsCount: Int,
    val totalStockValueCost: Double,
    val totalStockValueRetail: Double,
    val lowStockCount: Int
)

data class SyncSummary(
    val success: Boolean,
    val message: String,
    val productsCount: Int = 0,
    val batchesCount: Int = 0,
    val salesCount: Int = 0,
    val purchasesCount: Int = 0,
    val customersCount: Int = 0,
    val suppliersCount: Int = 0,
    val expensesCount: Int = 0,
    val ledgerCount: Int = 0,
    val employeesCount: Int = 0,
    val attendanceCount: Int = 0,
    val salaryDuesCount: Int = 0,
    val salaryPaymentsCount: Int = 0,
    val advancesCount: Int = 0
) {
    val totalSyncedRecords: Int
        get() = productsCount + batchesCount + salesCount + purchasesCount + customersCount +
                suppliersCount + expensesCount + ledgerCount + employeesCount + attendanceCount +
                salaryDuesCount + salaryPaymentsCount + advancesCount
}

data class StaffMergeResult(
    val duplicateSetsFound: Int = 0,
    val salesRepointed: Int = 0,
    val attendanceRepointed: Int = 0,
    val salaryRecordsRepointed: Int = 0,
    val orphanUsersDeleted: Int = 0,
    val orphanEmployeesDeleted: Int = 0,
    val isSuccess: Boolean = false,
    val details: List<String> = emptyList(),
    val errorMessage: String? = null
)

class StoreRepository(
    private val db: AppDatabase,
    private val context: android.content.Context? = null
) {

    val firestoreManager = FirestoreManager()
    private val repositoryScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()

    // Flow from Room for guaranteed offline and local-first reactivity
    val allProducts: Flow<List<Product>> = db.productDao().getAllProducts()
    val lowStockProducts: Flow<List<Product>> = db.productDao().getLowStockProducts()
    val allBatches: Flow<List<ProductBatch>> = db.productBatchDao().getAllBatches()
    val allOffers: Flow<List<Offer>> = db.offerDao().getAllOffers()
    val activeOffers: Flow<List<Offer>> = db.offerDao().getActiveOffers()
    val allPaymentClaims: Flow<List<PaymentClaim>> = db.paymentClaimDao().getAllPaymentClaims()
    val pendingPaymentClaims: Flow<List<PaymentClaim>> = db.paymentClaimDao().getPendingPaymentClaims()

    /**
     * Ensures any product image is converted to a compressed, sync-safe Base64 Data URL
     * before saving to Room or syncing to Firestore.
     */
    fun prepareProductForSync(product: Product): Product {
        val ctx = context ?: return product
        val syncableImage = com.example.utils.ImageSyncHelper.processImageUriForSync(ctx, product.imageUri)
        return if (syncableImage != product.imageUri) {
            product.copy(imageUri = syncableImage)
        } else {
            product
        }
    }

    /**
     * Scans for existing legacy products with device-only local content:// or file:// URIs,
     * compresses them into Base64 Data URLs, updates Room, and pushes the synced versions to Firestore.
     */
    suspend fun autoMigrateLegacyImageUris() = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext
        try {
            val localProducts = db.productDao().getAllProductsList()
            val legacyProducts = localProducts.filter { p ->
                !p.imageUri.isNullOrBlank() &&
                !p.imageUri.startsWith("data:image/", ignoreCase = true) &&
                !p.imageUri.startsWith("data:;base64,", ignoreCase = true) &&
                !p.imageUri.startsWith("http://", ignoreCase = true) &&
                !p.imageUri.startsWith("https://", ignoreCase = true)
            }
            if (legacyProducts.isNotEmpty()) {
                android.util.Log.i("StoreRepository", "Found ${legacyProducts.size} products with legacy local image URIs. Migrating to Base64...")
                legacyProducts.forEach { p ->
                    val syncable = com.example.utils.ImageSyncHelper.processImageUriForSync(ctx, p.imageUri)
                    if (!syncable.isNullOrBlank() && (syncable.startsWith("data:image/") || syncable.startsWith("http"))) {
                        val updated = p.copy(imageUri = syncable)
                        db.productDao().updateProduct(updated)
                        if (firestoreManager.saveProduct(updated)) {
                            db.productDao().markProductsSynced(listOf(updated.id))
                        }
                        android.util.Log.i("StoreRepository", "✓ Migrated legacy image for '${p.nameEn}' (${p.id}) -> Base64 (${syncable.length} chars)")
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("StoreRepository", "Legacy image migration note: ${e.message}")
        }
    }

    fun startFirestoreSync(scope: kotlinx.coroutines.CoroutineScope) {
        // Real-time products catalog sync
        scope.launch(Dispatchers.IO) {
            try {
                firestoreManager.getProductsFlow().collect { remoteProducts ->
                    if (remoteProducts.isNotEmpty()) {
                        val localMap = db.productDao().getAllProductsList().associateBy { it.id }
                        val mergedProducts = remoteProducts.map { remote ->
                            val local = localMap[remote.id]
                            if (local != null) {
                                remote.copy(
                                    mrp = remote.mrp ?: local.mrp,
                                    barcodeVariantsJson = remote.barcodeVariantsJson ?: local.barcodeVariantsJson,
                                    imageUri = remote.imageUri ?: local.imageUri,
                                    needsSync = false
                                )
                            } else {
                                remote.copy(needsSync = false)
                            }
                        }
                        db.productDao().insertProductsFromSync(mergedProducts)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("StoreRepository", "Firestore real-time products sync note: ${e.message}")
            }
        }

        // Real-time product batches sync
        scope.launch(Dispatchers.IO) {
            try {
                firestoreManager.getBatchesFlow().collect { remoteBatches ->
                    if (remoteBatches.isNotEmpty()) {
                        db.productBatchDao().insertBatchesFromSync(remoteBatches.map { it.copy(needsSync = false) })
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("StoreRepository", "Firestore real-time batches sync note: ${e.message}")
            }
        }

        // Real-time sales & bills sync
        scope.launch(Dispatchers.IO) {
            try {
                firestoreManager.getSalesFlow().collect { remoteSales ->
                    if (remoteSales.isNotEmpty()) {
                        syncMutex.withLock {
                            remoteSales.forEach { saleWithItems ->
                                DataSyncManager.logInboundReconciliation(
                                    collection = "sales",
                                    documentId = saleWithItems.sale.id,
                                    payload = mapOf(
                                        "finalAmount" to saleWithItems.sale.finalAmount,
                                        "paymentMode" to saleWithItems.sale.paymentMode,
                                        "customerName" to saleWithItems.sale.customerName,
                                        "items" to saleWithItems.items.map { it.toMap() }
                                    ),
                                    actionTaken = "ROOM_OVERWRITE_AND_INSERT"
                                )
                                db.saleDao().replaceSaleWithItemsFromSync(
                                    saleWithItems.sale.copy(needsSync = false),
                                    saleWithItems.consolidatedItems
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("StoreRepository", "Firestore real-time sales sync note: ${e.message}")
            }
        }

        // Real-time purchases sync
        scope.launch(Dispatchers.IO) {
            try {
                firestoreManager.getPurchasesFlow().collect { remotePurchases ->
                    if (remotePurchases.isNotEmpty()) {
                        syncMutex.withLock {
                            remotePurchases.forEach { purchaseWithItems ->
                                db.purchaseDao().replacePurchaseWithItemsFromSync(
                                    purchaseWithItems.purchase.copy(needsSync = false),
                                    purchaseWithItems.consolidatedItems
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("StoreRepository", "Firestore real-time purchases sync note: ${e.message}")
            }
        }

        // Real-time expenses sync
        scope.launch(Dispatchers.IO) {
            try {
                firestoreManager.getExpensesFlow().collect { remoteExpenses ->
                    if (remoteExpenses.isNotEmpty()) {
                        db.expenseDao().insertExpensesFromSync(remoteExpenses.map { it.copy(needsSync = false) })
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("StoreRepository", "Firestore real-time expenses sync note: ${e.message}")
            }
        }

        // Real-time returns sync
        scope.launch(Dispatchers.IO) {
            try {
                firestoreManager.getReturnsFlow().collect { remoteReturns ->
                    if (remoteReturns.isNotEmpty()) {
                        remoteReturns.forEach { retWithItems ->
                            db.saleReturnDao().insertFullReturnFromSync(retWithItems.saleReturn.copy(needsSync = false), retWithItems.items)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("StoreRepository", "Firestore real-time returns sync note: ${e.message}")
            }
        }

        // Real-time customers (khata) sync
        scope.launch(Dispatchers.IO) {
            try {
                firestoreManager.getCustomersFlow().collect { remoteCustomers ->
                    if (remoteCustomers.isNotEmpty()) {
                        val allLedgers = db.ledgerDao().getAllLedgerEntriesList()
                        val reconciled = remoteCustomers.map { c ->
                            LedgerCalculator.reconcileCustomer(c, allLedgers).copy(needsSync = false)
                        }
                        db.customerDao().insertCustomersFromSync(reconciled)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("StoreRepository", "Firestore real-time customers sync note: ${e.message}")
            }
        }

        // Real-time suppliers sync
        scope.launch(Dispatchers.IO) {
            try {
                firestoreManager.getSuppliersFlow().collect { remoteSuppliers ->
                    if (remoteSuppliers.isNotEmpty()) {
                        val allLedgers = db.ledgerDao().getAllLedgerEntriesList()
                        val reconciled = remoteSuppliers.map { s ->
                            LedgerCalculator.reconcileSupplier(s, allLedgers).copy(needsSync = false)
                        }
                        db.supplierDao().insertSuppliersFromSync(reconciled)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("StoreRepository", "Firestore real-time suppliers sync note: ${e.message}")
            }
        }

        // Real-time ledger sync
        scope.launch(Dispatchers.IO) {
            try {
                firestoreManager.getLedgerFlow().collect { remoteLedger ->
                    if (remoteLedger.isNotEmpty()) {
                        db.ledgerDao().insertLedgerEntriesFromSync(remoteLedger.map { it.copy(needsSync = false) })
                        reconcileAllPartyBalances()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("StoreRepository", "Firestore real-time ledger sync note: ${e.message}")
            }
        }

        // Real-time employees sync
        scope.launch(Dispatchers.IO) {
            try {
                firestoreManager.getEmployeesFlow().collect { remoteEmployees ->
                    if (remoteEmployees.isNotEmpty()) {
                        deduplicateAndInsertRemoteEmployees(remoteEmployees)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("StoreRepository", "Firestore real-time employees sync note: ${e.message}")
            }
        }

        // Real-time app users & preassigned roles sync to employees table
        scope.launch(Dispatchers.IO) {
            try {
                firestoreManager.getAllUsersFlow().collect { userRoles ->
                    if (userRoles.isNotEmpty()) {
                        syncAppUsersToEmployees(userRoles)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("StoreRepository", "Firestore real-time app users sync note: ${e.message}")
            }
        }

        scope.launch(Dispatchers.IO) {
            try {
                firestoreManager.getPreassignedRolesFlow().collect { preassignedRoles ->
                    if (preassignedRoles.isNotEmpty()) {
                        syncAppUsersToEmployees(preassignedRoles)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("StoreRepository", "Firestore real-time preassigned roles sync note: ${e.message}")
            }
        }

        // Real-time employee attendance sync
        scope.launch(Dispatchers.IO) {
            try {
                firestoreManager.getAttendanceFlow().collect { remoteAttendance ->
                    if (remoteAttendance.isNotEmpty()) {
                        db.employeeAttendanceDao().insertAttendanceBatchFromSync(remoteAttendance.map { it.copy(needsSync = false) })
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("StoreRepository", "Firestore real-time attendance sync note: ${e.message}")
            }
        }

        // Real-time salary dues sync
        scope.launch(Dispatchers.IO) {
            try {
                firestoreManager.getSalaryDuesFlow().collect { remoteDues ->
                    if (remoteDues.isNotEmpty()) {
                        db.employeeSalaryDao().insertSalaryDueBatchFromSync(remoteDues.map { it.copy(needsSync = false) })
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("StoreRepository", "Firestore real-time salary dues sync note: ${e.message}")
            }
        }

        // Real-time salary payments sync
        scope.launch(Dispatchers.IO) {
            try {
                firestoreManager.getSalaryPaymentsFlow().collect { remotePayments ->
                    if (remotePayments.isNotEmpty()) {
                        db.employeeSalaryDao().insertSalaryPaymentsFromSync(remotePayments.map { it.copy(needsSync = false) })
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("StoreRepository", "Firestore real-time salary payments sync note: ${e.message}")
            }
        }

        // Real-time employee advances sync
        scope.launch(Dispatchers.IO) {
            try {
                firestoreManager.getAdvancesFlow().collect { remoteAdvances ->
                    if (remoteAdvances.isNotEmpty()) {
                        db.employeeSalaryDao().insertAdvancesFromSync(remoteAdvances.map { it.copy(needsSync = false) })
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("StoreRepository", "Firestore real-time advances sync note: ${e.message}")
            }
        }

        // Real-time payment claims sync (Customer self-reported UPI payments)
        scope.launch(Dispatchers.IO) {
            try {
                var isInitialLoad = true
                firestoreManager.getPaymentClaimsFlow().collect { remoteClaims ->
                    if (remoteClaims.isNotEmpty()) {
                        val currentLocalClaims = db.paymentClaimDao().getAllPaymentClaimsList()
                        val localMap = currentLocalClaims.associateBy { it.id }
                        val knownIds = currentLocalClaims.map { it.id }.toSet()

                        // Prevent remote sync from downgrading locally confirmed/rejected claims back to pending!
                        val mergedClaims = remoteClaims.map { remote ->
                            val local = localMap[remote.id]
                            val hasLedgerEntry = db.ledgerDao().getLedgerEntriesByReference(remote.id)
                                .any { it.type == "PAYMENT_RECEIVED" }

                            if (local?.status == PaymentClaim.STATUS_CONFIRMED || hasLedgerEntry) {
                                // Claim is already confirmed locally! Keep it confirmed
                                if (remote.status == PaymentClaim.STATUS_PENDING) {
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            firestoreManager.updatePaymentClaimStatus(
                                                customerId = remote.customerId.ifBlank { local?.customerId ?: "" },
                                                claimId = remote.id,
                                                status = PaymentClaim.STATUS_CONFIRMED,
                                                actorName = local?.confirmedBy ?: "Store Admin"
                                            )
                                        } catch (_: Exception) {}
                                    }
                                }
                                remote.copy(
                                    status = PaymentClaim.STATUS_CONFIRMED,
                                    confirmedAt = local?.confirmedAt ?: System.currentTimeMillis(),
                                    confirmedBy = local?.confirmedBy ?: "Store Admin",
                                    screenshotData = null,
                                    screenshotUrl = null
                                )
                            } else if (local?.status == PaymentClaim.STATUS_REJECTED) {
                                // Claim was rejected locally! Keep it rejected
                                if (remote.status == PaymentClaim.STATUS_PENDING) {
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            firestoreManager.updatePaymentClaimStatus(
                                                customerId = remote.customerId.ifBlank { local.customerId },
                                                claimId = remote.id,
                                                status = PaymentClaim.STATUS_REJECTED,
                                                actorName = local.rejectedBy,
                                                rejectionReason = local.rejectionReason
                                            )
                                        } catch (_: Exception) {}
                                    }
                                }
                                remote.copy(
                                    status = PaymentClaim.STATUS_REJECTED,
                                    rejectedAt = local.rejectedAt,
                                    rejectedBy = local.rejectedBy,
                                    rejectionReason = local.rejectionReason,
                                    screenshotData = null,
                                    screenshotUrl = null
                                )
                            } else {
                                remote
                            }
                        }

                        db.paymentClaimDao().insertPaymentClaims(mergedClaims)

                        // Trigger notification for newly arrived pending claims
                        if (!isInitialLoad) {
                            val newPendingClaims = mergedClaims.filter { it.isPending && !knownIds.contains(it.id) }
                            context?.let { ctx ->
                                for (c in newPendingClaims) {
                                    NotificationHelper.notifyNewPaymentClaim(
                                        context = ctx,
                                        customerName = c.customerName,
                                        claimedAmount = c.claimedAmount,
                                        claimId = c.id
                                    )
                                }
                            }
                        }
                        isInitialLoad = false

                        // Automatically check and expire stale claims older than 48 hours
                        autoExpireStaleClaimsInternal()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("StoreRepository", "Firestore real-time payment claims sync note: ${e.message}")
            }
        }

        // Automatic one-time public_ledger backfill check across all devices
        scope.launch(Dispatchers.IO) {
            try {
                val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                if (user != null && !firestoreManager.isPublicLedgerBackfillCompleted()) {
                    android.util.Log.i("StoreRepository", "Starting background public_ledger history backfill migration...")
                    val result = firestoreManager.backfillCustomerPublicLedgers()
                    if (result.isSuccess && result.copiedCount > 0) {
                        firestoreManager.markPublicLedgerBackfillCompleted(result.copiedCount)
                        android.util.Log.i("StoreRepository", "Background public_ledger backfill migration completed: copied ${result.copiedCount} entries.")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("StoreRepository", "Auto-backfill check/migration note: ${e.message}")
            }
        }
    }

    fun getBatchesForProduct(productId: String): Flow<List<ProductBatch>> =
        db.productBatchDao().getBatchesForProduct(productId)

    suspend fun getBatchesForProductList(productId: String): List<ProductBatch> =
        db.productBatchDao().getBatchesForProductList(productId)

    suspend fun getProductById(productId: String): Product? =
        db.productDao().getProductById(productId) ?: firestoreManager.getProductById(productId)

    suspend fun syncProductFromBatches(productId: String) = withContext(Dispatchers.IO) {
        val product = db.productDao().getProductById(productId) ?: return@withContext
        val batches = db.productBatchDao().getBatchesForProductList(productId)
        if (batches.isNotEmpty()) {
            val totalStock = batches.sumOf { it.quantity }
            val activeExpiry = batches
                .filter { it.quantity > 0 && !it.expiryDate.isNullOrBlank() }
                .minOfOrNull { it.expiryDate!! }
                ?: batches.filter { !it.expiryDate.isNullOrBlank() }.minOfOrNull { it.expiryDate!! }

            val updatedProduct = product.copy(
                currentStock = totalStock,
                expiryDate = activeExpiry
            )
            db.productDao().updateProduct(updatedProduct)
            try {
                firestoreManager.saveProduct(updatedProduct)
            } catch (e: Exception) {
                android.util.Log.w("StoreRepository", "Firestore batch sync note: ${e.message}")
            }
        }
    }

    suspend fun addBatch(batch: ProductBatch) = withContext(Dispatchers.IO) {
        db.productBatchDao().insertBatch(batch)
        try {
            firestoreManager.saveBatch(batch)
        } catch (e: Exception) {
            android.util.Log.w("StoreRepository", "Firestore save batch note: ${e.message}")
        }
        syncProductFromBatches(batch.productId)
    }

    suspend fun updateBatch(batch: ProductBatch) = withContext(Dispatchers.IO) {
        db.productBatchDao().updateBatch(batch)
        try {
            firestoreManager.saveBatch(batch)
        } catch (e: Exception) {
            android.util.Log.w("StoreRepository", "Firestore update batch note: ${e.message}")
        }
        syncProductFromBatches(batch.productId)
    }

    suspend fun deleteBatch(batchId: String, productId: String) = withContext(Dispatchers.IO) {
        db.productBatchDao().deleteBatch(batchId)
        try {
            firestoreManager.deleteBatch(batchId)
        } catch (e: Exception) {
            android.util.Log.w("StoreRepository", "Firestore delete batch note: ${e.message}")
        }
        syncProductFromBatches(productId)
    }

    suspend fun saveProductWithBatches(product: Product, initialBatches: List<ProductBatch>) = withContext(Dispatchers.IO) {
        val preparedProduct = prepareProductForSync(product)
        db.productDao().insertProduct(preparedProduct)
        try {
            if (firestoreManager.saveProduct(preparedProduct)) {
                db.productDao().markProductsSynced(listOf(preparedProduct.id))
            }
        } catch (e: Exception) {
            android.util.Log.w("StoreRepository", "Firestore save product note: ${e.message}")
        }

        if (initialBatches.isNotEmpty()) {
            db.productBatchDao().insertBatches(initialBatches)
            try {
                if (firestoreManager.saveBatches(initialBatches)) {
                    db.productBatchDao().markBatchesSynced(initialBatches.map { it.id })
                }
            } catch (e: Exception) {
                android.util.Log.w("StoreRepository", "Firestore save batches note: ${e.message}")
            }
            syncProductFromBatches(preparedProduct.id)
        } else if (preparedProduct.currentStock > 0 || !preparedProduct.expiryDate.isNullOrBlank()) {
            val defaultBatch = ProductBatch(
                id = UUID.randomUUID().toString(),
                productId = preparedProduct.id,
                batchNumber = "BATCH-01",
                quantity = preparedProduct.currentStock,
                expiryDate = preparedProduct.expiryDate,
                costPrice = preparedProduct.costPrice,
                sellingPrice = preparedProduct.sellingPrice
            )
            db.productBatchDao().insertBatch(defaultBatch)
            try {
                if (firestoreManager.saveBatch(defaultBatch)) {
                    db.productBatchDao().markBatchesSynced(listOf(defaultBatch.id))
                }
            } catch (e: Exception) {
                android.util.Log.w("StoreRepository", "Firestore save default batch note: ${e.message}")
            }
            syncProductFromBatches(preparedProduct.id)
        }
    }

    val allCustomers: Flow<List<Customer>> = db.customerDao().getAllCustomers()
    val allSuppliers: Flow<List<Supplier>> = db.supplierDao().getAllSuppliers()

    val allSales: Flow<List<SaleWithItems>> = db.saleDao().getAllSales()
    val heldSales: Flow<List<SaleWithItems>> = db.saleDao().getHeldSales()

    val allPurchases: Flow<List<PurchaseWithItems>> = db.purchaseDao().getAllPurchases()
    val allExpenses: Flow<List<Expense>> = db.expenseDao().getAllExpenses()
    val allLedgerEntries: Flow<List<LedgerEntry>> = db.ledgerDao().getAllLedgerEntries()
    val allReturns: Flow<List<SaleReturnWithItems>> = db.saleReturnDao().getAllReturns()

    suspend fun processReturnOrReplacement(
        saleReturn: SaleReturn,
        returnItems: List<ReturnItem>
    ) = withContext(Dispatchers.IO) {
        db.saleReturnDao().insertFullReturn(saleReturn, returnItems)

        try {
            if (firestoreManager.saveSaleReturn(saleReturn, returnItems)) {
                db.saleReturnDao().markReturnsSynced(listOf(saleReturn.id))
            }
        } catch (e: Exception) {
            android.util.Log.w("StoreRepository", "Firestore save sale return sync note: ${e.message}")
        }

        // Adjust inventory stock:
        // 1. For returned items (isReplacement = false): INCREASE stock
        // 2. For replacement items (isReplacement = true): DECREASE stock
        returnItems.forEach { item ->
            val product = db.productDao().getProductById(item.productId)
            if (product != null) {
                val variant = product.findMatchingVariant(item.productNameEn)
                val primaryQty = if (variant != null) {
                    product.calculateVariantBaseDeduction(variant, item.quantity)
                } else {
                    product.convertQuantityToBaseUnit(item.quantity, item.unitType)
                }

                val newStock = Product.roundQuantity(
                    if (item.isReplacement) {
                        (product.currentStock - primaryQty).coerceAtLeast(0.0)
                    } else {
                        product.currentStock + primaryQty
                    }
                )

                val updatedProduct = product.copy(currentStock = newStock)
                db.productDao().updateProduct(updatedProduct)

                val batches = db.productBatchDao().getBatchesForProductList(item.productId)
                if (item.isReplacement) {
                    // Deduct from batches
                    if (batches.isNotEmpty()) {
                        var remaining = primaryQty
                        for (batch in batches) {
                            if (remaining <= 0) break
                            if (batch.quantity <= 0) continue

                            if (batch.quantity <= remaining) {
                                remaining -= batch.quantity
                                db.productBatchDao().updateBatch(batch.copy(quantity = 0.0))
                            } else {
                                val newQty = Product.roundQuantity(batch.quantity - remaining)
                                remaining = 0.0
                                db.productBatchDao().updateBatch(batch.copy(quantity = newQty))
                            }
                        }
                    }
                } else {
                    // Returned item - replenish batch or add return batch
                    if (batches.isNotEmpty()) {
                        val activeBatch = batches.firstOrNull { it.quantity > 0 } ?: batches.first()
                        db.productBatchDao().updateBatch(activeBatch.copy(quantity = Product.roundQuantity(activeBatch.quantity + primaryQty)))
                    }
                }

                try {
                    if (firestoreManager.saveProduct(updatedProduct)) {
                        db.productDao().markProductsSynced(listOf(updatedProduct.id))
                    }
                } catch (e: Exception) {
                    android.util.Log.w("StoreRepository", "Firestore return stock sync note: ${e.message}")
                }
            }
        }

        // Customer khata balance adjustment if needed:
        if (!saleReturn.customerId.isNullOrBlank()) {
            val customer = db.customerDao().getCustomerById(saleReturn.customerId)
            if (customer != null) {
                if (saleReturn.refundPaymentMode.equals("CREDIT", ignoreCase = true)) {
                    val noteText = if (saleReturn.type == "REPLACEMENT") "Replacement adjustment for Sale #${saleReturn.saleId.takeLast(6)}" else "Return refund for Sale #${saleReturn.saleId.takeLast(6)}"
                    val ledgerEntry = LedgerEntry(
                        id = UUID.randomUUID().toString(),
                        partyType = "CUSTOMER",
                        partyId = saleReturn.customerId,
                        partyName = customer.name,
                        type = if (saleReturn.netAmount >= 0) "RETURN_REFUND" else "REPLACEMENT_DUE",
                        amount = kotlin.math.abs(saleReturn.netAmount),
                        datetime = saleReturn.datetime,
                        note = noteText,
                        referenceId = saleReturn.id
                    )
                    db.ledgerDao().insertLedgerEntry(ledgerEntry)
                    try {
                        if (firestoreManager.saveLedgerEntry(ledgerEntry)) {
                            db.ledgerDao().markLedgerEntriesSynced(listOf(ledgerEntry.id))
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("StoreRepository", "Firestore return ledger sync note: ${e.message}")
                    }
                    recalculateCustomerBalance(saleReturn.customerId)

                    val updatedCust = db.customerDao().getCustomerById(saleReturn.customerId)
                    if (updatedCust != null) {
                        try {
                            if (firestoreManager.saveCustomer(updatedCust)) {
                                db.customerDao().markCustomersSynced(listOf(updatedCust.id))
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("StoreRepository", "Firestore return customer sync note: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    suspend fun clearAllData() = withContext(Dispatchers.IO) {
        db.clearAllTables()
    }

    /**
     * Wipes local Room database, local auto-backups, and all cloud Firestore records.
     */
    suspend fun deleteAllAppData(
        deleteLocalData: Boolean = true,
        deleteCloudData: Boolean = true,
        deleteLocalBackups: Boolean = true,
        context: Context? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            var localCleared = false
            var cloudDocsDeleted = 0
            var backupsDeleted = 0

            if (deleteLocalData) {
                db.clearAllTables()
                localCleared = true
            }

            if (deleteLocalBackups && context != null) {
                backupsDeleted = BackupHelper.deleteAllLocalBackups(context)
            }

            if (deleteCloudData) {
                val cloudRes = firestoreManager.deleteAllCloudData()
                if (cloudRes.isSuccess) {
                    cloudDocsDeleted = cloudRes.getOrDefault(0)
                }
            }

            val summary = buildString {
                if (localCleared) append("Local database cleared. ")
                if (deleteCloudData) append("Cloud database wiped ($cloudDocsDeleted records deleted). ")
                if (backupsDeleted > 0) append("$backupsDeleted local backup files removed. ")
            }.trim()

            Result.success(if (summary.isNotBlank()) summary else "All requested data deleted successfully.")
        } catch (e: Exception) {
            Log.e("StoreRepository", "deleteAllAppData error: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun saveProduct(product: Product) = withContext(Dispatchers.IO) {
        val preparedProduct = prepareProductForSync(product)
        db.productDao().insertProduct(preparedProduct)
        try {
            if (firestoreManager.saveProduct(preparedProduct)) {
                db.productDao().markProductsSynced(listOf(preparedProduct.id))
            }
        } catch (e: Exception) {
            android.util.Log.w("StoreRepository", "Failed to sync product to Firestore: ${e.message}")
        }
    }

    suspend fun deleteProduct(product: Product) = withContext(Dispatchers.IO) {
        firestoreManager.deleteProduct(product.id)
        db.productBatchDao().deleteBatchesForProduct(product.id)
        db.productDao().deleteProduct(product)
    }

    suspend fun updateCategoryName(oldCategory: String, newCategory: String) = withContext(Dispatchers.IO) {
        db.productDao().updateCategoryName(oldCategory, newCategory)
        val products = firestoreManager.getAllProductsOnce().filter { it.category.equals(oldCategory, ignoreCase = true) }
        products.forEach { p ->
            val prepared = prepareProductForSync(p.copy(category = newCategory))
            firestoreManager.saveProduct(prepared)
        }
    }

    suspend fun reassignCategory(categoryToDelete: String, replacementCategory: String = "General") = withContext(Dispatchers.IO) {
        db.productDao().reassignCategory(categoryToDelete, replacementCategory)
        val products = firestoreManager.getAllProductsOnce().filter { it.category.equals(categoryToDelete, ignoreCase = true) }
        products.forEach { p ->
            val prepared = prepareProductForSync(p.copy(category = replacementCategory))
            firestoreManager.saveProduct(prepared)
        }
    }

    suspend fun verifyAndSyncCustomerShareLinks() = withContext(Dispatchers.IO) {
        try {
            val prefs = context?.getSharedPreferences("sync_prefs", android.content.Context.MODE_PRIVATE)
            val alreadyRegistered = prefs?.getStringSet("registered_share_tokens", emptySet()) ?: emptySet()
            val customers = db.customerDao().getAllCustomersList()
            val missing = customers.filter { !it.shareToken.isNullOrBlank() && !alreadyRegistered.contains(it.shareToken) }
            if (missing.isNotEmpty()) {
                val newlyRegistered = alreadyRegistered.toMutableSet()
                missing.forEach { c ->
                    try {
                        firestoreManager.ensureShareLink(c.shareToken!!, c.id)
                        newlyRegistered.add(c.shareToken!!)
                    } catch (e: Exception) {
                        android.util.Log.w("StoreRepository", "Failed to ensure share link for customer ${c.id}: ${e.message}")
                    }
                }
                prefs?.edit()?.putStringSet("registered_share_tokens", newlyRegistered)?.apply()
            }
        } catch (e: Exception) {
            android.util.Log.w("StoreRepository", "Customer share link registration check note: ${e.message}")
        }
    }

    suspend fun syncInitialProductsToFirestore() = withContext(Dispatchers.IO) {
        try {
            val unsyncedCount = db.productDao().getUnsyncedProductsCount()
            if (unsyncedCount == 0) {
                // Zero dirty products found; skip Firestore call to conserve Spark quota
                return@withContext
            }

            val unsynced = db.productDao().getUnsyncedProductsList().map { prepareProductForSync(it) }
            if (unsynced.isNotEmpty()) {
                val ok = firestoreManager.saveProducts(unsynced)
                if (ok) {
                    db.productDao().markProductsSynced(unsynced.map { it.id })
                    val prefs = context?.getSharedPreferences("sync_prefs", android.content.Context.MODE_PRIVATE)
                    prefs?.edit()?.putBoolean("hasCompletedInitialFirestoreSeed", true)?.apply()
                    android.util.Log.i("StoreRepository", "Initial sync pushed ${unsynced.size} dirty products to Firestore catalog.")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("StoreRepository", "Initial sync to firestore failed: ${e.message}")
        }
    }

    private suspend fun performUploadSync(): SyncSummary {
        return try {
            autoMigrateLegacyImageUris()
            val unsyncedProducts = db.productDao().getUnsyncedProductsList().map { prepareProductForSync(it) }
            val unsyncedBatches = db.productBatchDao().getUnsyncedBatchesList()
            val unsyncedSales = db.saleDao().getUnsyncedSalesList()
            val unsyncedPurchases = db.purchaseDao().getUnsyncedPurchasesList()
            val unsyncedCustomers = db.customerDao().getUnsyncedCustomersList()
            val unsyncedSuppliers = db.supplierDao().getUnsyncedSuppliersList()
            val unsyncedExpenses = db.expenseDao().getUnsyncedExpensesList()
            val unsyncedLedger = db.ledgerDao().getUnsyncedLedgerEntriesList()
            val unsyncedReturns = db.saleReturnDao().getUnsyncedReturnsList()
            val unsyncedEmployees = db.employeeDao().getUnsyncedEmployeesList()
            val unsyncedAttendance = db.employeeAttendanceDao().getUnsyncedAttendanceList()
            val unsyncedDues = db.employeeSalaryDao().getUnsyncedSalaryDuesList()
            val unsyncedPayments = db.employeeSalaryDao().getUnsyncedSalaryPaymentsList()
            val unsyncedAdvances = db.employeeSalaryDao().getUnsyncedAdvancesList()

            val hasAnyDirty = unsyncedProducts.isNotEmpty() || unsyncedBatches.isNotEmpty() ||
                    unsyncedSales.isNotEmpty() || unsyncedPurchases.isNotEmpty() ||
                    unsyncedCustomers.isNotEmpty() || unsyncedSuppliers.isNotEmpty() ||
                    unsyncedExpenses.isNotEmpty() || unsyncedLedger.isNotEmpty() ||
                    unsyncedReturns.isNotEmpty() || unsyncedEmployees.isNotEmpty() ||
                    unsyncedAttendance.isNotEmpty() || unsyncedDues.isNotEmpty() ||
                    unsyncedPayments.isNotEmpty() || unsyncedAdvances.isNotEmpty()

            if (!hasAnyDirty) {
                return SyncSummary(
                    success = true,
                    message = "Cloud is already up to date. Zero dirty records found.",
                    productsCount = 0,
                    batchesCount = 0,
                    salesCount = 0,
                    purchasesCount = 0,
                    customersCount = 0,
                    suppliersCount = 0,
                    expensesCount = 0,
                    ledgerCount = 0,
                    employeesCount = 0,
                    attendanceCount = 0,
                    salaryDuesCount = 0,
                    salaryPaymentsCount = 0,
                    advancesCount = 0
                )
            }

            var productsUploaded = 0
            var batchesUploaded = 0
            var salesUploaded = 0
            var purchasesUploaded = 0
            var customersUploaded = 0
            var suppliersUploaded = 0
            var expensesUploaded = 0
            var ledgerUploaded = 0
            var returnsUploaded = 0
            var employeesUploaded = 0
            var attendanceUploaded = 0
            var duesUploaded = 0
            var paymentsUploaded = 0
            var advancesUploaded = 0

            if (unsyncedProducts.isNotEmpty()) {
                if (firestoreManager.saveProducts(unsyncedProducts)) {
                    db.productDao().markProductsSynced(unsyncedProducts.map { it.id })
                    productsUploaded = unsyncedProducts.size
                }
            }
            if (unsyncedBatches.isNotEmpty()) {
                if (firestoreManager.saveBatches(unsyncedBatches)) {
                    db.productBatchDao().markBatchesSynced(unsyncedBatches.map { it.id })
                    batchesUploaded = unsyncedBatches.size
                }
            }
            if (unsyncedSales.isNotEmpty()) {
                if (firestoreManager.saveSales(unsyncedSales)) {
                    db.saleDao().markSalesSynced(unsyncedSales.map { it.sale.id })
                    salesUploaded = unsyncedSales.size
                }
            }
            if (unsyncedPurchases.isNotEmpty()) {
                if (firestoreManager.savePurchases(unsyncedPurchases)) {
                    db.purchaseDao().markPurchasesSynced(unsyncedPurchases.map { it.purchase.id })
                    purchasesUploaded = unsyncedPurchases.size
                }
            }
            if (unsyncedCustomers.isNotEmpty()) {
                if (firestoreManager.saveCustomers(unsyncedCustomers)) {
                    db.customerDao().markCustomersSynced(unsyncedCustomers.map { it.id })
                    customersUploaded = unsyncedCustomers.size
                }
            }
            if (unsyncedSuppliers.isNotEmpty()) {
                if (firestoreManager.saveSuppliers(unsyncedSuppliers)) {
                    db.supplierDao().markSuppliersSynced(unsyncedSuppliers.map { it.id })
                    suppliersUploaded = unsyncedSuppliers.size
                }
            }
            if (unsyncedExpenses.isNotEmpty()) {
                if (firestoreManager.saveExpenses(unsyncedExpenses)) {
                    db.expenseDao().markExpensesSynced(unsyncedExpenses.map { it.id })
                    expensesUploaded = unsyncedExpenses.size
                }
            }
            if (unsyncedLedger.isNotEmpty()) {
                if (firestoreManager.saveLedgerEntries(unsyncedLedger)) {
                    db.ledgerDao().markLedgerEntriesSynced(unsyncedLedger.map { it.id })
                    ledgerUploaded = unsyncedLedger.size
                }
            }
            if (unsyncedReturns.isNotEmpty()) {
                if (firestoreManager.saveSaleReturns(unsyncedReturns)) {
                    db.saleReturnDao().markReturnsSynced(unsyncedReturns.map { it.saleReturn.id })
                    returnsUploaded = unsyncedReturns.size
                }
            }
            if (unsyncedEmployees.isNotEmpty()) {
                if (firestoreManager.saveEmployees(unsyncedEmployees)) {
                    db.employeeDao().markEmployeesSynced(unsyncedEmployees.map { it.id })
                    employeesUploaded = unsyncedEmployees.size
                }
            }
            if (unsyncedAttendance.isNotEmpty()) {
                if (firestoreManager.saveAttendanceBatch(unsyncedAttendance)) {
                    db.employeeAttendanceDao().markAttendanceSynced(unsyncedAttendance.map { it.id })
                    attendanceUploaded = unsyncedAttendance.size
                }
            }
            if (unsyncedDues.isNotEmpty()) {
                if (firestoreManager.saveSalaryDues(unsyncedDues)) {
                    db.employeeSalaryDao().markSalaryDuesSynced(unsyncedDues.map { it.id })
                    duesUploaded = unsyncedDues.size
                }
            }
            if (unsyncedPayments.isNotEmpty()) {
                if (firestoreManager.saveSalaryPayments(unsyncedPayments)) {
                    db.employeeSalaryDao().markSalaryPaymentsSynced(unsyncedPayments.map { it.id })
                    paymentsUploaded = unsyncedPayments.size
                }
            }
            if (unsyncedAdvances.isNotEmpty()) {
                if (firestoreManager.saveAdvances(unsyncedAdvances)) {
                    db.employeeSalaryDao().markAdvancesSynced(unsyncedAdvances.map { it.id })
                    advancesUploaded = unsyncedAdvances.size
                }
            }

            val totalUploaded = productsUploaded + batchesUploaded + salesUploaded + purchasesUploaded +
                    customersUploaded + suppliersUploaded + expensesUploaded + ledgerUploaded +
                    returnsUploaded + employeesUploaded + attendanceUploaded + duesUploaded +
                    paymentsUploaded + advancesUploaded

            SyncSummary(
                success = true,
                message = if (totalUploaded > 0) {
                    "Differential sync complete: $totalUploaded unsynced record(s) uploaded to Firestore."
                } else {
                    "Cloud is already up to date. Zero dirty records found."
                },
                productsCount = productsUploaded,
                batchesCount = batchesUploaded,
                salesCount = salesUploaded,
                purchasesCount = purchasesUploaded,
                customersCount = customersUploaded,
                suppliersCount = suppliersUploaded,
                expensesCount = expensesUploaded,
                ledgerCount = ledgerUploaded,
                employeesCount = employeesUploaded,
                attendanceCount = attendanceUploaded,
                salaryDuesCount = duesUploaded,
                salaryPaymentsCount = paymentsUploaded,
                advancesCount = advancesUploaded
            )
        } catch (e: Exception) {
            android.util.Log.e("StoreRepository", "Upload sync error: ${e.message}", e)
            SyncSummary(
                success = false,
                message = "Upload sync error: ${e.message ?: "Unknown error"}"
            )
        }
    }

    private suspend fun performDownloadSync(): SyncSummary {
        return try {
            val remoteProducts = firestoreManager.getAllProductsOnce()
            if (remoteProducts.isNotEmpty()) {
                val localMap = db.productDao().getAllProductsList().associateBy { it.id }
                val mergedProducts = remoteProducts.map { remote ->
                    val local = localMap[remote.id]
                    if (local != null) {
                        remote.copy(
                            mrp = remote.mrp ?: local.mrp,
                            barcodeVariantsJson = remote.barcodeVariantsJson ?: local.barcodeVariantsJson,
                            imageUri = remote.imageUri ?: local.imageUri,
                            needsSync = false
                        )
                    } else {
                        remote.copy(needsSync = false)
                    }
                }
                db.productDao().insertProductsFromSync(mergedProducts)
            }

            val remoteBatches = firestoreManager.getAllBatchesOnce()
            if (remoteBatches.isNotEmpty()) db.productBatchDao().insertBatchesFromSync(remoteBatches.map { it.copy(needsSync = false) })

            val remoteSales = firestoreManager.getAllSalesOnce()
            remoteSales.forEach { saleWithItems ->
                db.saleDao().replaceSaleWithItemsFromSync(saleWithItems.sale.copy(needsSync = false), saleWithItems.consolidatedItems)
            }

            val remotePurchases = firestoreManager.getAllPurchasesOnce()
            remotePurchases.forEach { purchaseWithItems ->
                db.purchaseDao().replacePurchaseWithItemsFromSync(purchaseWithItems.purchase.copy(needsSync = false), purchaseWithItems.consolidatedItems)
            }

            val remoteCustomers = firestoreManager.getAllCustomersOnce()
            if (remoteCustomers.isNotEmpty()) db.customerDao().insertCustomersFromSync(remoteCustomers.map { it.copy(needsSync = false) })

            val remoteSuppliers = firestoreManager.getAllSuppliersOnce()
            if (remoteSuppliers.isNotEmpty()) db.supplierDao().insertSuppliersFromSync(remoteSuppliers.map { it.copy(needsSync = false) })

            val remoteExpenses = firestoreManager.getAllExpensesOnce()
            if (remoteExpenses.isNotEmpty()) db.expenseDao().insertExpensesFromSync(remoteExpenses.map { it.copy(needsSync = false) })

            val remoteLedger = firestoreManager.getAllLedgerEntriesOnce()
            if (remoteLedger.isNotEmpty()) db.ledgerDao().insertLedgerEntriesFromSync(remoteLedger.map { it.copy(needsSync = false) })

            val remoteReturns = firestoreManager.getAllReturnsOnce()
            remoteReturns.forEach { retWithItems ->
                db.saleReturnDao().insertFullReturnFromSync(retWithItems.saleReturn.copy(needsSync = false), retWithItems.items)
            }

            val remoteEmployees = firestoreManager.getAllEmployeesOnce()
            if (remoteEmployees.isNotEmpty()) db.employeeDao().insertEmployeesFromSync(remoteEmployees.map { it.copy(needsSync = false) })

            val remoteAttendance = firestoreManager.getAllAttendanceOnce()
            if (remoteAttendance.isNotEmpty()) db.employeeAttendanceDao().insertAttendanceBatchFromSync(remoteAttendance.map { it.copy(needsSync = false) })

            val remoteSalaryDues = firestoreManager.getAllSalaryDuesOnce()
            if (remoteSalaryDues.isNotEmpty()) db.employeeSalaryDao().insertSalaryDueBatchFromSync(remoteSalaryDues.map { it.copy(needsSync = false) })

            val remoteSalaryPayments = firestoreManager.getAllSalaryPaymentsOnce()
            if (remoteSalaryPayments.isNotEmpty()) db.employeeSalaryDao().insertSalaryPaymentsFromSync(remoteSalaryPayments.map { it.copy(needsSync = false) })

            val remoteAdvances = firestoreManager.getAllAdvancesOnce()
            if (remoteAdvances.isNotEmpty()) db.employeeSalaryDao().insertAdvancesFromSync(remoteAdvances.map { it.copy(needsSync = false) })

            val remoteClaims = firestoreManager.getAllPaymentClaimsOnce()
            if (remoteClaims.isNotEmpty()) db.paymentClaimDao().insertPaymentClaims(remoteClaims)

            SyncSummary(
                success = true,
                message = "All cloud data synced from Firestore to device.",
                productsCount = remoteProducts.size,
                batchesCount = remoteBatches.size,
                salesCount = remoteSales.size,
                purchasesCount = remotePurchases.size,
                customersCount = remoteCustomers.size,
                suppliersCount = remoteSuppliers.size,
                expensesCount = remoteExpenses.size,
                ledgerCount = remoteLedger.size,
                employeesCount = remoteEmployees.size,
                attendanceCount = remoteAttendance.size,
                salaryDuesCount = remoteSalaryDues.size,
                salaryPaymentsCount = remoteSalaryPayments.size,
                advancesCount = remoteAdvances.size
            )
        } catch (e: Exception) {
            android.util.Log.e("StoreRepository", "Download sync error: ${e.message}", e)
            SyncSummary(
                success = false,
                message = "Download sync error: ${e.message ?: "Unknown error"}"
            )
        }
    }

    suspend fun syncAllDataToFirestore(): SyncSummary = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            performUploadSync()
        }
    }

    suspend fun syncAllDataFromFirestore(): SyncSummary = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            performDownloadSync()
        }
    }

    suspend fun syncAllData(): SyncSummary = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            // Two-way synchronization:
            // 1. Download latest data from Firestore and update local Room DB
            val downloadRes = performDownloadSync()
            // 2. Upload any local records (offline bills, updates) to Firestore
            val uploadRes = performUploadSync()

            if (downloadRes.success && uploadRes.success) {
                SyncSummary(
                    success = true,
                    message = "Full bidirectional synchronization complete! (${uploadRes.totalSyncedRecords} total records synced)",
                    productsCount = uploadRes.productsCount,
                    batchesCount = uploadRes.batchesCount,
                    salesCount = uploadRes.salesCount,
                    purchasesCount = uploadRes.purchasesCount,
                    customersCount = uploadRes.customersCount,
                    suppliersCount = uploadRes.suppliersCount,
                    expensesCount = uploadRes.expensesCount,
                    ledgerCount = uploadRes.ledgerCount,
                    employeesCount = uploadRes.employeesCount,
                    attendanceCount = uploadRes.attendanceCount,
                    salaryDuesCount = uploadRes.salaryDuesCount,
                    salaryPaymentsCount = uploadRes.salaryPaymentsCount,
                    advancesCount = uploadRes.advancesCount
                )
            } else {
                val errorMsg = if (!downloadRes.success) downloadRes.message else uploadRes.message
                SyncSummary(
                    success = false,
                    message = "Sync incomplete: $errorMsg"
                )
            }
        }
    }

    suspend fun saveCustomer(
        customer: Customer,
        initialDue: Double = 0.0,
        initialDueNote: String? = null
    ) = withContext(Dispatchers.IO) {
        val existingEntries = db.ledgerDao().getLedgerEntriesForPartyList("CUSTOMER", customer.id)
        val effectiveInitialDue = if (initialDue > 0.0) {
            initialDue
        } else if (existingEntries.isEmpty() && customer.balance > 0.0) {
            customer.balance
        } else {
            0.0
        }

        if (effectiveInitialDue > 0.0) {
            val hasOpening = existingEntries.any {
                it.type.equals("OPENING_BALANCE", ignoreCase = true) || it.type.equals("INITIAL_DUE", ignoreCase = true)
            }
            if (!hasOpening) {
                val ledgerId = "led_open_" + UUID.randomUUID().toString().take(8)
                val ledgerEntry = LedgerEntry(
                    id = ledgerId,
                    partyType = "CUSTOMER",
                    partyId = customer.id,
                    partyName = customer.name,
                    type = "OPENING_BALANCE",
                    amount = effectiveInitialDue,
                    datetime = System.currentTimeMillis(),
                    note = initialDueNote ?: "Opening balance at setup",
                    referenceId = null
                )
                db.ledgerDao().insertLedgerEntry(ledgerEntry)
                try {
                    firestoreManager.saveLedgerEntry(ledgerEntry)
                } catch (e: Exception) {
                    android.util.Log.w("StoreRepository", "Firestore save initial customer ledger note: ${e.message}")
                }
            }
        }

        val allPartyEntries = db.ledgerDao().getLedgerEntriesForPartyList("CUSTOMER", customer.id)
        val calculatedBalance = LedgerCalculator.calculateCustomerBalance(customer.id, allPartyEntries)
        val finalCustomer = customer.copy(balance = calculatedBalance)

        db.customerDao().insertCustomer(finalCustomer)
        try {
            firestoreManager.saveCustomer(finalCustomer)
        } catch (e: Exception) {
            android.util.Log.w("StoreRepository", "Firestore save customer sync note: ${e.message}")
        }
    }

    suspend fun getCustomerById(id: String): Customer? = withContext(Dispatchers.IO) {
        db.customerDao().getCustomerById(id) ?: firestoreManager.getCustomerById(id)
    }

    suspend fun deleteCustomer(customer: Customer) = withContext(Dispatchers.IO) {
        db.customerDao().deleteCustomer(customer)
        try {
            firestoreManager.deleteCustomer(customer.id, customer.shareToken)
        } catch (e: Exception) {
            android.util.Log.w("StoreRepository", "Firestore delete customer sync note: ${e.message}")
        }
    }

    suspend fun getOrCreateCustomerShareToken(customer: Customer): String = withContext(Dispatchers.IO) {
        val existing = customer.shareToken
        if (!existing.isNullOrBlank()) {
            val prefs = context?.getSharedPreferences("sync_prefs", android.content.Context.MODE_PRIVATE)
            val alreadyRegistered = prefs?.getStringSet("registered_share_tokens", emptySet()) ?: emptySet()
            if (!alreadyRegistered.contains(existing)) {
                try {
                    firestoreManager.ensureShareLink(existing, customer.id)
                    val updated = alreadyRegistered.toMutableSet().apply { add(existing) }
                    prefs?.edit()?.putStringSet("registered_share_tokens", updated)?.apply()
                } catch (e: Exception) {
                    // ignore
                }
            }
            return@withContext existing
        }
        val newToken = generateSecureShareToken()
        val updated = customer.copy(shareToken = newToken)
        db.customerDao().insertCustomer(updated)
        try {
            firestoreManager.updateCustomerShareToken(customer.id, newToken, oldToken = null)
            val prefs = context?.getSharedPreferences("sync_prefs", android.content.Context.MODE_PRIVATE)
            val alreadyRegistered = prefs?.getStringSet("registered_share_tokens", emptySet()) ?: emptySet()
            val updatedTokens = alreadyRegistered.toMutableSet().apply { add(newToken) }
            prefs?.edit()?.putStringSet("registered_share_tokens", updatedTokens)?.apply()
        } catch (e: Exception) {
            android.util.Log.w("StoreRepository", "Firestore update share token note: ${e.message}")
        }
        newToken
    }

    suspend fun regenerateCustomerShareToken(customer: Customer): String = withContext(Dispatchers.IO) {
        val oldToken = customer.shareToken
        val newToken = generateSecureShareToken()
        val updated = customer.copy(shareToken = newToken)
        db.customerDao().insertCustomer(updated)
        try {
            firestoreManager.updateCustomerShareToken(customer.id, newToken, oldToken = oldToken)
        } catch (e: Exception) {
            android.util.Log.w("StoreRepository", "Firestore regenerate share token note: ${e.message}")
        }
        newToken
    }

    suspend fun revokeCustomerShareToken(customer: Customer) = withContext(Dispatchers.IO) {
        val oldToken = customer.shareToken
        val updated = customer.copy(shareToken = null)
        db.customerDao().insertCustomer(updated)
        try {
            firestoreManager.updateCustomerShareToken(customer.id, null, oldToken = oldToken)
        } catch (e: Exception) {
            android.util.Log.w("StoreRepository", "Firestore revoke share token note: ${e.message}")
        }
    }

    private fun generateSecureShareToken(): String {
        val randomBytes = ByteArray(24)
        java.security.SecureRandom().nextBytes(randomBytes)
        val hex = randomBytes.joinToString("") { "%02x".format(it) }
        val uuidPart = java.util.UUID.randomUUID().toString().replace("-", "")
        return (hex + uuidPart).take(48)
    }

    // ==================== PAYMENT CLAIMS MANAGEMENT ====================

    suspend fun autoExpireStaleClaimsInternal(): Int = withContext(Dispatchers.IO) {
        var expiredCount = 0
        try {
            val allClaims = db.paymentClaimDao().getAllPaymentClaimsList()
            val stalePending = allClaims.filter { it.status == PaymentClaim.STATUS_PENDING && it.isExpiredByTime() }
            for (c in stalePending) {
                val expiredClaim = c.copy(status = PaymentClaim.STATUS_EXPIRED)
                db.paymentClaimDao().updatePaymentClaim(expiredClaim)
                firestoreManager.savePaymentClaim(expiredClaim)
                expiredCount++
            }
        } catch (e: Exception) {
            android.util.Log.w("StoreRepository", "Auto-expire claims note: ${e.message}")
        }
        expiredCount
    }

    suspend fun confirmPaymentClaim(
        claim: PaymentClaim,
        customNote: String? = null,
        operatorName: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val customer = db.customerDao().getCustomerById(claim.customerId)
            ?: return@withContext Result.failure(IllegalArgumentException("Customer not found"))

        val now = System.currentTimeMillis()

        // 1. Idempotency check: Has this claim already been credited to the customer ledger?
        val existingEntries = db.ledgerDao().getLedgerEntriesByReference(claim.id)
        val alreadyCreditedEntry = existingEntries.firstOrNull { it.type == "PAYMENT_RECEIVED" }

        val effectiveEntry = if (alreadyCreditedEntry == null) {
            val formattedNote = if (!customNote.isNullOrBlank()) {
                customNote.trim()
            } else {
                val refPart = if (!claim.note.isNullOrBlank()) " (${claim.note})" else ""
                "Self-reported UPI Khata payment confirmed$refPart"
            }

            val newEntry = LedgerEntry(
                id = UUID.randomUUID().toString(),
                partyType = "CUSTOMER",
                partyId = customer.id,
                partyName = customer.name,
                type = "PAYMENT_RECEIVED",
                amount = claim.claimedAmount,
                datetime = now,
                note = formattedNote,
                referenceId = claim.id
            )

            db.ledgerDao().insertLedgerEntry(newEntry)
            recalculateCustomerBalance(customer.id)
            newEntry
        } else {
            alreadyCreditedEntry
        }

        // 2. Mark claim as confirmed locally in Room
        val existingClaim = db.paymentClaimDao().getPaymentClaimById(claim.id)
        val confirmedClaim = (existingClaim ?: claim).copy(
            status = PaymentClaim.STATUS_CONFIRMED,
            confirmedAt = existingClaim?.confirmedAt ?: now,
            confirmedBy = existingClaim?.confirmedBy ?: (operatorName ?: "Store Admin"),
            screenshotData = null,
            screenshotUrl = null
        )
        db.paymentClaimDao().insertPaymentClaim(confirmedClaim)

        // 3. Sync ledger, customer balance, and claim status to Firestore in background without blocking
        repositoryScope.launch(Dispatchers.IO) {
            try {
                firestoreManager.updatePaymentClaimStatus(
                    customerId = claim.customerId,
                    claimId = claim.id,
                    status = PaymentClaim.STATUS_CONFIRMED,
                    actorName = operatorName ?: "Store Admin"
                )
            } catch (e: Exception) {
                android.util.Log.w("StoreRepository", "Async update claim status note: ${e.message}")
            }
            try {
                firestoreManager.savePaymentClaim(confirmedClaim)
            } catch (e: Exception) {
                android.util.Log.w("StoreRepository", "Async save payment claim note: ${e.message}")
            }
            try {
                firestoreManager.saveLedgerEntry(effectiveEntry)
            } catch (e: Exception) {
                android.util.Log.w("StoreRepository", "Async save ledger entry note: ${e.message}")
            }
            try {
                val updatedCust = db.customerDao().getCustomerById(customer.id)
                if (updatedCust != null) {
                    firestoreManager.saveCustomer(updatedCust)
                }
            } catch (e: Exception) {
                android.util.Log.w("StoreRepository", "Async save customer note: ${e.message}")
            }
        }

        Result.success(Unit)
    }

    suspend fun rejectPaymentClaim(
        claim: PaymentClaim,
        reason: String,
        operatorName: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val rejectedClaim = claim.copy(
            status = PaymentClaim.STATUS_REJECTED,
            rejectionReason = reason.ifBlank { "Payment not received / unverified" },
            rejectedAt = now,
            rejectedBy = operatorName ?: "Store Admin",
            screenshotData = null,
            screenshotUrl = null
        )

        // Only update claim status in Room
        db.paymentClaimDao().insertPaymentClaim(rejectedClaim)

        repositoryScope.launch(Dispatchers.IO) {
            try {
                firestoreManager.updatePaymentClaimStatus(
                    customerId = claim.customerId,
                    claimId = claim.id,
                    status = PaymentClaim.STATUS_REJECTED,
                    actorName = operatorName ?: "Store Admin",
                    rejectionReason = rejectedClaim.rejectionReason
                )
            } catch (_: Exception) {}
            try {
                firestoreManager.savePaymentClaim(rejectedClaim)
            } catch (e: Exception) {
                android.util.Log.w("StoreRepository", "Async reject payment claim sync note: ${e.message}")
            }
        }

        Result.success(Unit)
    }

    suspend fun updatePaymentClaimStatus(
        customerId: String,
        claimId: String,
        status: String,
        actorName: String? = null,
        rejectionReason: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val claim = db.paymentClaimDao().getPaymentClaimById(claimId)
        val isFinalized = status == PaymentClaim.STATUS_CONFIRMED || status == PaymentClaim.STATUS_REJECTED
        if (claim != null) {
            val updated = claim.copy(
                status = status,
                confirmedAt = if (status == PaymentClaim.STATUS_CONFIRMED) System.currentTimeMillis() else claim.confirmedAt,
                confirmedBy = if (status == PaymentClaim.STATUS_CONFIRMED) actorName else claim.confirmedBy,
                rejectedAt = if (status == PaymentClaim.STATUS_REJECTED) System.currentTimeMillis() else claim.rejectedAt,
                rejectedBy = if (status == PaymentClaim.STATUS_REJECTED) actorName else claim.rejectedBy,
                rejectionReason = rejectionReason ?: claim.rejectionReason,
                screenshotData = if (isFinalized) null else claim.screenshotData,
                screenshotUrl = if (isFinalized) null else claim.screenshotUrl
            )
            db.paymentClaimDao().updatePaymentClaim(updated)
        }
        firestoreManager.updatePaymentClaimStatus(customerId, claimId, status, actorName, rejectionReason)
    }

    suspend fun saveSupplier(
        supplier: Supplier,
        initialDue: Double = 0.0,
        initialDueNote: String? = null
    ) = withContext(Dispatchers.IO) {
        val existingEntries = db.ledgerDao().getLedgerEntriesForPartyList("SUPPLIER", supplier.id)
        val effectiveInitialDue = if (initialDue > 0.0) {
            initialDue
        } else if (existingEntries.isEmpty() && supplier.balance > 0.0) {
            supplier.balance
        } else {
            0.0
        }

        if (effectiveInitialDue > 0.0) {
            val hasOpening = existingEntries.any {
                it.type.equals("OPENING_BALANCE", ignoreCase = true) || it.type.equals("INITIAL_DUE", ignoreCase = true)
            }
            if (!hasOpening) {
                val ledgerId = "led_open_" + UUID.randomUUID().toString().take(8)
                val ledgerEntry = LedgerEntry(
                    id = ledgerId,
                    partyType = "SUPPLIER",
                    partyId = supplier.id,
                    partyName = supplier.name,
                    type = "OPENING_BALANCE",
                    amount = effectiveInitialDue,
                    datetime = System.currentTimeMillis(),
                    note = initialDueNote ?: "Opening balance at setup",
                    referenceId = null
                )
                db.ledgerDao().insertLedgerEntry(ledgerEntry)
                try {
                    firestoreManager.saveLedgerEntry(ledgerEntry)
                } catch (e: Exception) {
                    android.util.Log.w("StoreRepository", "Firestore save initial supplier ledger note: ${e.message}")
                }
            }
        }

        val allPartyEntries = db.ledgerDao().getLedgerEntriesForPartyList("SUPPLIER", supplier.id)
        val calculatedBalance = LedgerCalculator.calculateSupplierBalance(supplier.id, allPartyEntries)
        val finalSupplier = supplier.copy(balance = calculatedBalance)

        db.supplierDao().insertSupplier(finalSupplier)
        try {
            firestoreManager.saveSupplier(finalSupplier)
        } catch (e: Exception) {
            android.util.Log.w("StoreRepository", "Firestore save supplier sync note: ${e.message}")
        }
    }

    suspend fun deleteSupplier(supplier: Supplier) = withContext(Dispatchers.IO) {
        db.supplierDao().deleteSupplier(supplier)
        try {
            firestoreManager.deleteSupplier(supplier.id)
        } catch (e: Exception) {
            android.util.Log.w("StoreRepository", "Firestore delete supplier sync note: ${e.message}")
        }
    }

    suspend fun saveExpense(expense: Expense) = withContext(Dispatchers.IO) {
        db.expenseDao().insertExpense(expense)
        try {
            firestoreManager.saveExpense(expense)
        } catch (e: Exception) {
            android.util.Log.w("StoreRepository", "Firestore save expense sync note: ${e.message}")
        }
    }

    suspend fun deleteExpense(expense: Expense) = withContext(Dispatchers.IO) {
        db.expenseDao().deleteExpense(expense)
        try {
            firestoreManager.deleteExpense(expense.id)
        } catch (e: Exception) {
            android.util.Log.w("StoreRepository", "Firestore delete expense sync note: ${e.message}")
        }
    }

    suspend fun recordCustomerPayment(
        customerId: String,
        amount: Double,
        paymentMode: String,
        note: String?,
        ownerOverrideCreditLimit: Boolean = false
    ): Result<Unit> = recordCustomerCustomEntry(
        customerId = customerId,
        amount = amount,
        isCreditGiven = false,
        paymentMode = paymentMode,
        note = note,
        ownerOverrideCreditLimit = ownerOverrideCreditLimit
    )

    suspend fun recordCustomerCustomEntry(
        customerId: String,
        amount: Double,
        isCreditGiven: Boolean,
        paymentMode: String,
        note: String?,
        ownerOverrideCreditLimit: Boolean = false,
        dueDate: Long? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val customer = db.customerDao().getCustomerById(customerId)
            ?: return@withContext Result.failure(IllegalArgumentException("Customer not found"))

        // Check credit limit if credit is being extended
        val limit = customer.creditLimit ?: 0.0
        if (isCreditGiven && customer.hasCreditLimit() && customer.isOverCreditLimit(amount) && !ownerOverrideCreditLimit) {
            return@withContext Result.failure(
                CreditLimitExceededException(
                    customerId = customerId,
                    customerName = customer.name,
                    currentBalance = customer.balance,
                    creditLimit = limit,
                    attemptedDue = customer.balance + amount
                )
            )
        }

        val entryType = if (isCreditGiven) "CREDIT_GIVEN" else "PAYMENT_RECEIVED"
        val defaultNote = if (isCreditGiven) "Credit sale/Due added ($paymentMode)" else "Payment received via $paymentMode"
        val now = System.currentTimeMillis()
        val defaultGraceDays = customer.customGracePeriodDays ?: StoreInfoManager.interestGracePeriodDays
        val calculatedDueDate = if (isCreditGiven) (dueDate ?: (now + defaultGraceDays.toLong() * 24 * 60 * 60 * 1000L)) else null

        val ledgerEntry = LedgerEntry(
            id = UUID.randomUUID().toString(),
            partyType = "CUSTOMER",
            partyId = customerId,
            partyName = customer.name,
            type = entryType,
            amount = amount,
            datetime = now,
            note = note.takeIf { !it.isNullOrBlank() } ?: defaultNote,
            dueDate = calculatedDueDate
        )

        // 1. Fast local database update (Replay full ledger history to recalculate balance fresh)
        db.ledgerDao().insertLedgerEntry(ledgerEntry)
        recalculateCustomerBalance(customerId)

        // 2. Asynchronous background cloud sync
        repositoryScope.launch {
            try {
                firestoreManager.saveLedgerEntry(ledgerEntry)
                val updatedCust = db.customerDao().getCustomerById(customerId)
                if (updatedCust != null) {
                    firestoreManager.saveCustomer(updatedCust)
                }
            } catch (e: Exception) {
                android.util.Log.w("StoreRepository", "Async customer ledger sync note: ${e.message}")
            }
        }

        Result.success(Unit)
    }

    suspend fun recordSupplierPayment(
        supplierId: String,
        amount: Double,
        paymentMode: String,
        note: String?
    ) = recordSupplierCustomEntry(supplierId, amount, isCreditTaken = false, paymentMode = paymentMode, note = note)

    suspend fun recordSupplierCustomEntry(
        supplierId: String,
        amount: Double,
        isCreditTaken: Boolean,
        paymentMode: String,
        note: String?
    ) = withContext(Dispatchers.IO) {
        val supplier = db.supplierDao().getSupplierById(supplierId) ?: return@withContext

        val entryType = if (isCreditTaken) "CREDIT_TAKEN" else "PAYMENT_MADE"
        val defaultNote = if (isCreditTaken) "Purchase/Due added ($paymentMode)" else "Payment made via $paymentMode"

        val ledgerEntry = LedgerEntry(
            id = UUID.randomUUID().toString(),
            partyType = "SUPPLIER",
            partyId = supplierId,
            partyName = supplier.name,
            type = entryType,
            amount = amount,
            datetime = System.currentTimeMillis(),
            note = note.takeIf { !it.isNullOrBlank() } ?: defaultNote
        )
        db.ledgerDao().insertLedgerEntry(ledgerEntry)
        recalculateSupplierBalance(supplierId)
        
        repositoryScope.launch {
            try {
                firestoreManager.saveLedgerEntry(ledgerEntry)
                val updatedSupplier = db.supplierDao().getSupplierById(supplierId)
                if (updatedSupplier != null) {
                    firestoreManager.saveSupplier(updatedSupplier)
                }
            } catch (e: Exception) {
                android.util.Log.w("StoreRepository", "Firestore record supplier payment sync note: ${e.message}")
            }
        }
    }

    suspend fun completeSale(
        sale: Sale,
        items: List<SaleItem>,
        isHeldBill: Boolean = false,
        allowNegativeStockOverride: Boolean = false,
        ownerOverrideCreditLimit: Boolean = false
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val productIds = items.map { it.productId }.distinct()
            val productMap = mutableMapOf<String, Product>()
            for (pid in productIds) {
                if (!pid.startsWith("quick_")) {
                    val p = db.productDao().getProductById(pid)
                    if (p != null) productMap[pid] = p
                }
            }

            // Deduplicate / consolidate incoming items to guarantee 1 line per unique product
            val consolidatedItems = com.example.utils.SaleConsolidationUtils.consolidateSaleItems(items) { prodId ->
                productMap[prodId]
            }

            android.util.Log.d("POS_TRACE", "[REPO_COMPLETE_SALE] SaleID=${sale.id}, FinalAmount=${sale.finalAmount}, ReceivedAmount=${sale.receivedAmount}, DueAmount=${sale.dueAmount}, IncomingItemCount=${items.size}, ConsolidatedCount=${consolidatedItems.size}")
            consolidatedItems.forEachIndexed { i, it ->
                android.util.Log.d("POS_TRACE", "  SavedItem[$i]: Product=${it.productNameEn} (ID=${it.productId}), Qty=${it.quantity} ${it.unitType}, UnitPrice=${it.unitPrice}, Subtotal=${it.subtotal}")
            }

            // 1. If it's a completed sale (not held), compute required deductions for each product in base units
            val productDeductions = mutableMapOf<String, Double>()

            if (!isHeldBill && !sale.isHeld) {
                for (item in consolidatedItems) {
                    if (item.productId.startsWith("quick_")) continue
                    val product = db.productDao().getProductById(item.productId)
                    if (product != null) {
                        productMap[item.productId] = product
                        val variant = if (!item.variantBarcode.isNullOrBlank()) {
                            product.findVariantByBarcode(item.variantBarcode)
                        } else {
                            product.findMatchingVariant(item.productNameEn)
                        }
                        val deductQty = if (variant != null) {
                            product.calculateVariantBaseDeduction(variant, item.quantity)
                        } else {
                            product.convertQuantityToBaseUnit(item.quantity, item.unitType)
                        }
                        productDeductions[item.productId] = (productDeductions[item.productId] ?: 0.0) + deductQty
                    }
                }

                // 2. Perform fast local stock check before proceeding
                if (!allowNegativeStockOverride) {
                    val localInsufficient = mutableListOf<InsufficientStockItem>()
                    for ((prodId, requiredBaseQty) in productDeductions) {
                        val product = productMap[prodId]
                        if (product != null) {
                            if (requiredBaseQty > (product.currentStock + 0.00001)) {
                                localInsufficient.add(
                                    InsufficientStockItem(
                                        productId = prodId,
                                        productNameEn = product.nameEn,
                                        productNameBn = product.nameBn,
                                        unitType = product.unitType,
                                        availableStock = product.currentStock,
                                        requestedQuantity = requiredBaseQty
                                    )
                                )
                            }
                        }
                    }

                    if (localInsufficient.isNotEmpty()) {
                        return@withContext Result.failure(InsufficientStockException(localInsufficient))
                    }
                }
            }

            // Customer credit check if credit sale
            var creditLedgerEntry: LedgerEntry? = null
            if (sale.dueAmount > 0.0 && !sale.customerId.isNullOrBlank() && !sale.isHeld) {
                val customer = db.customerDao().getCustomerById(sale.customerId)
                if (customer != null) {
                    val limit = customer.creditLimit ?: 0.0
                    if (customer.hasCreditLimit() && customer.isOverCreditLimit(sale.dueAmount) && !ownerOverrideCreditLimit) {
                        return@withContext Result.failure(
                            CreditLimitExceededException(
                                customerId = customer.id,
                                customerName = customer.name,
                                currentBalance = customer.balance,
                                creditLimit = limit,
                                attemptedDue = customer.balance + sale.dueAmount
                            )
                        )
                    }
                }

                val customerName = sale.customerName ?: customer?.name ?: "Customer"
                val defaultGraceDays = customer?.customGracePeriodDays ?: StoreInfoManager.interestGracePeriodDays
                val creditDueDate = sale.dueDate ?: (sale.datetime + defaultGraceDays.toLong() * 24 * 60 * 60 * 1000L)
                creditLedgerEntry = LedgerEntry(
                    id = UUID.randomUUID().toString(),
                    partyType = "CUSTOMER",
                    partyId = sale.customerId,
                    partyName = customerName,
                    type = "SALE_CREDIT",
                    amount = sale.dueAmount,
                    datetime = sale.datetime,
                    note = "Credit sale #${sale.id.takeLast(6)} (Bill: ₹${"%.2f".format(sale.finalAmount)}, Paid: ₹${"%.2f".format(sale.receivedAmount)})",
                    referenceId = sale.id,
                    dueDate = creditDueDate
                )
            }

            // 3. Save sale and consolidated sale items to Room Database (Instant Local Persistence)
            db.saleDao().replaceSaleWithItems(sale, consolidatedItems)

            val updatedProductsToSync = mutableListOf<Product>()

            // 4. Update local stock and FIFO batches
            if (!sale.isHeld) {
                for ((productId, deductQty) in productDeductions) {
                    val product = productMap[productId] ?: db.productDao().getProductById(productId)
                    if (product != null) {
                        val newStock = Product.roundQuantity(
                            if (allowNegativeStockOverride) (product.currentStock - deductQty)
                            else (product.currentStock - deductQty).coerceAtLeast(0.0)
                        )

                        val updatedProduct = product.copy(currentStock = newStock)
                        db.productDao().updateProduct(updatedProduct)
                        updatedProductsToSync.add(updatedProduct)

                        val batches = db.productBatchDao().getBatchesForProductList(productId)
                        if (batches.isNotEmpty()) {
                            var remaining = deductQty
                            for (batch in batches) {
                                if (remaining <= 0) break
                                if (batch.quantity <= 0) continue

                                if (batch.quantity <= remaining) {
                                    remaining -= batch.quantity
                                    db.productBatchDao().updateBatch(batch.copy(quantity = 0.0))
                                } else {
                                    val newQty = Product.roundQuantity(batch.quantity - remaining)
                                    remaining = 0.0
                                    db.productBatchDao().updateBatch(batch.copy(quantity = newQty))
                                }
                            }
                        }
                    }
                }

                // 5. If sale has remaining credit due, add to customer balance & ledger
                if (sale.dueAmount > 0.0 && !sale.customerId.isNullOrBlank()) {
                    if (creditLedgerEntry != null) {
                        db.ledgerDao().insertLedgerEntry(creditLedgerEntry)
                    }
                    recalculateCustomerBalance(sale.customerId)
                }
            }

            // 6. Asynchronous Background Cloud Sync (Non-blocking: POS UI renders immediately in <15ms!)
            repositoryScope.launch {
                try {
                    firestoreManager.saveSale(sale, consolidatedItems)
                    for (prod in updatedProductsToSync) {
                        firestoreManager.saveProduct(prod)
                    }
                    if (creditLedgerEntry != null && !sale.customerId.isNullOrBlank()) {
                        firestoreManager.saveLedgerEntry(creditLedgerEntry)
                        val updatedCust = db.customerDao().getCustomerById(sale.customerId)
                        if (updatedCust != null) {
                            firestoreManager.saveCustomer(updatedCust)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("StoreRepository", "Async sale cloud sync note: ${e.message}")
                }
            }

            Result.success(Unit)
        } catch (e: InsufficientStockException) {
            Result.failure(e)
        } catch (e: CreditLimitExceededException) {
            Result.failure(e)
        } catch (e: Exception) {
            android.util.Log.e("StoreRepository", "completeSale error: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun deleteHeldSale(saleId: String) = withContext(Dispatchers.IO) {
        db.saleDao().deleteSaleItems(saleId)
        db.saleDao().deleteSale(saleId)
        try {
            firestoreManager.deleteSale(saleId)
        } catch (e: Exception) {
            android.util.Log.w("StoreRepository", "Firestore delete sale sync note: ${e.message}")
        }
    }

    suspend fun completePurchase(
        purchase: Purchase,
        items: List<PurchaseItem>,
        batchOverrides: Map<String, ProductBatch>? = null,
        productOverrides: Map<String, Product>? = null
    ) = withContext(Dispatchers.IO) {
        val consolidatedItems = com.example.utils.SaleConsolidationUtils.sanitizePurchaseItems(purchase, items)
        // 1. Instant local persistence in Room Database (Atomic Transaction)
        db.purchaseDao().replacePurchaseWithItems(purchase, consolidatedItems)

        val updatedProductsToSync = mutableListOf<Product>()
        val batchesToSync = mutableListOf<ProductBatch>()
        var creditLedgerToSync: LedgerEntry? = null
        var supplierToSync: Supplier? = null

        // Add inventory stock for each purchase item
        consolidatedItems.forEach { item ->
            val product = db.productDao().getProductById(item.productId)
            val batch = batchOverrides?.get(item.productId) ?: run {
                val batchNo = "PUR-${purchase.id.takeLast(4)}-${System.currentTimeMillis().toString().takeLast(4)}"
                ProductBatch(
                    id = UUID.randomUUID().toString(),
                    productId = item.productId,
                    batchNumber = batchNo,
                    quantity = item.quantity,
                    expiryDate = product?.expiryDate,
                    costPrice = item.costPrice,
                    sellingPrice = product?.sellingPrice
                )
            }
            db.productBatchDao().insertBatch(batch)
            batchesToSync.add(batch)

            val updatedProduct = productOverrides?.get(item.productId) ?: run {
                if (product != null) {
                    val newStock = Product.roundQuantity(product.currentStock + item.quantity)
                    product.copy(
                        currentStock = newStock,
                        costPrice = if (item.costPrice > 0) item.costPrice else product.costPrice
                    )
                } else null
            }

            if (updatedProduct != null) {
                db.productDao().updateProduct(updatedProduct)
                updatedProductsToSync.add(updatedProduct)
            }
        }

        // If purchase has an unpaid portion, update supplier balance & record ledger entry
        val unpaidAmount = purchase.totalAmount - purchase.amountPaid
        if (!purchase.supplierId.isNullOrBlank()) {
            val supplierName = purchase.supplierName ?: "Supplier"
            if (unpaidAmount > 0.0) {
                val ledgerEntry = LedgerEntry(
                    id = UUID.randomUUID().toString(),
                    partyType = "SUPPLIER",
                    partyId = purchase.supplierId,
                    partyName = supplierName,
                    type = "PURCHASE_CREDIT",
                    amount = unpaidAmount,
                    datetime = purchase.datetime,
                    note = "Purchase on credit #${purchase.id.takeLast(6)} (Bill: ₹${"%.2f".format(purchase.totalAmount)}, Paid: ₹${"%.2f".format(purchase.amountPaid)} via ${purchase.paidVia})",
                    referenceId = purchase.id
                )
                db.ledgerDao().insertLedgerEntry(ledgerEntry)
                recalculateSupplierBalance(purchase.supplierId)
                creditLedgerToSync = ledgerEntry
            } else if (unpaidAmount < 0.0) {
                val extraPaid = -unpaidAmount
                val ledgerEntry = LedgerEntry(
                    id = UUID.randomUUID().toString(),
                    partyType = "SUPPLIER",
                    partyId = purchase.supplierId,
                    partyName = supplierName,
                    type = "PAYMENT_MADE",
                    amount = extraPaid,
                    datetime = purchase.datetime,
                    note = "Payment towards previous dues during purchase #${purchase.id.takeLast(6)} (via ${purchase.paidVia})",
                    referenceId = purchase.id
                )
                db.ledgerDao().insertLedgerEntry(ledgerEntry)
                recalculateSupplierBalance(purchase.supplierId)
                creditLedgerToSync = ledgerEntry
            }

            supplierToSync = db.supplierDao().getSupplierById(purchase.supplierId)
        }

        // 2. Asynchronous background cloud sync
        repositoryScope.launch {
            try {
                firestoreManager.savePurchase(purchase, items)
                for (batch in batchesToSync) {
                    firestoreManager.saveBatch(batch)
                }
                for (prod in updatedProductsToSync) {
                    firestoreManager.saveProduct(prod)
                }
                if (creditLedgerToSync != null) {
                    firestoreManager.saveLedgerEntry(creditLedgerToSync)
                }
                if (supplierToSync != null) {
                    firestoreManager.saveSupplier(supplierToSync)
                }
            } catch (e: Exception) {
                android.util.Log.w("StoreRepository", "Async purchase sync note: ${e.message}")
            }
        }
    }

    suspend fun deletePurchase(purchaseId: String) = withContext(Dispatchers.IO) {
        val existingEntries = db.ledgerDao().getLedgerEntriesByReference(purchaseId)
        val affectedSupplierIds = mutableSetOf<String>()
        existingEntries.forEach { entry ->
            if (entry.partyType == "SUPPLIER") {
                affectedSupplierIds.add(entry.partyId)
                db.ledgerDao().deleteLedgerEntry(entry)
                try {
                    firestoreManager.deleteLedgerEntry(entry.id)
                } catch (e: Exception) {
                    android.util.Log.w("StoreRepository", "Firestore delete purchase ledger sync note: ${e.message}")
                }
            }
        }
        for (suppId in affectedSupplierIds) {
            recalculateSupplierBalance(suppId)
            val updatedSupplier = db.supplierDao().getSupplierById(suppId)
            if (updatedSupplier != null) {
                try {
                    firestoreManager.saveSupplier(updatedSupplier)
                } catch (e: Exception) {
                    android.util.Log.w("StoreRepository", "Firestore delete purchase supplier sync note: ${e.message}")
                }
            }
        }
        db.purchaseDao().deletePurchaseItems(purchaseId)
        db.purchaseDao().deletePurchase(purchaseId)
        try {
            firestoreManager.deletePurchase(purchaseId)
        } catch (e: Exception) {
            android.util.Log.w("StoreRepository", "Firestore delete purchase note: ${e.message}")
        }
    }

    suspend fun recalculateCustomerBalance(customerId: String): Double {
        val entries = db.ledgerDao().getLedgerEntriesForPartyList("CUSTOMER", customerId)
        val trueBalance = LedgerCalculator.calculateCustomerBalance(customerId, entries)
        val customer = db.customerDao().getCustomerById(customerId)
        if (customer != null && customer.balance != trueBalance) {
            val updated = customer.copy(balance = trueBalance)
            db.customerDao().updateCustomer(updated)
        }
        return trueBalance
    }

    suspend fun recalculateSupplierBalance(supplierId: String): Double {
        val entries = db.ledgerDao().getLedgerEntriesForPartyList("SUPPLIER", supplierId)
        val trueBalance = LedgerCalculator.calculateSupplierBalance(supplierId, entries)
        val supplier = db.supplierDao().getSupplierById(supplierId)
        if (supplier != null && supplier.balance != trueBalance) {
            val updated = supplier.copy(balance = trueBalance)
            db.supplierDao().updateSupplier(updated)
        }
        return trueBalance
    }

    suspend fun auditAndBackfillOpeningBalances() = withContext(Dispatchers.IO) {
        try {
            val customers = db.customerDao().getAllCustomersList()
            val allLedgers = db.ledgerDao().getAllLedgerEntriesList()
            val missingOpeningLedgers = mutableListOf<LedgerEntry>()

            for (customer in customers) {
                val customerLedgers = allLedgers.filter { it.partyId == customer.id }
                val hasOpening = customerLedgers.any {
                    it.type.equals("OPENING_BALANCE", ignoreCase = true) || it.type.equals("INITIAL_DUE", ignoreCase = true)
                }
                if (!hasOpening && customer.balance > 0.0 && customerLedgers.isEmpty()) {
                    val openingEntry = LedgerEntry(
                        id = "led_open_" + UUID.randomUUID().toString().take(8),
                        partyType = "CUSTOMER",
                        partyId = customer.id,
                        partyName = customer.name,
                        type = "OPENING_BALANCE",
                        amount = customer.balance,
                        datetime = System.currentTimeMillis(),
                        note = "Opening balance at setup",
                        referenceId = null
                    )
                    missingOpeningLedgers.add(openingEntry)
                }
            }

            val suppliers = db.supplierDao().getAllSuppliersList()
            for (supplier in suppliers) {
                val supplierLedgers = allLedgers.filter { it.partyId == supplier.id }
                val hasOpening = supplierLedgers.any {
                    it.type.equals("OPENING_BALANCE", ignoreCase = true) || it.type.equals("INITIAL_DUE", ignoreCase = true)
                }
                if (!hasOpening && supplier.balance > 0.0 && supplierLedgers.isEmpty()) {
                    val openingEntry = LedgerEntry(
                        id = "led_open_" + UUID.randomUUID().toString().take(8),
                        partyType = "SUPPLIER",
                        partyId = supplier.id,
                        partyName = supplier.name,
                        type = "OPENING_BALANCE",
                        amount = supplier.balance,
                        datetime = System.currentTimeMillis(),
                        note = "Opening balance at setup",
                        referenceId = null
                    )
                    missingOpeningLedgers.add(openingEntry)
                }
            }

            if (missingOpeningLedgers.isNotEmpty()) {
                db.ledgerDao().insertLedgerEntries(missingOpeningLedgers)
                try {
                    firestoreManager.saveLedgerEntries(missingOpeningLedgers)
                } catch (e: Exception) {
                    android.util.Log.w("StoreRepository", "Backfill opening balance sync note: ${e.message}")
                }
            }
            reconcileAllPartyBalances()
        } catch (e: Exception) {
            android.util.Log.w("StoreRepository", "Audit opening balances note: ${e.message}")
        }
    }

    suspend fun reconcileAllPartyBalances() {
        val allLedgers = db.ledgerDao().getAllLedgerEntriesList()
        val customers = db.customerDao().getAllCustomersList()
        customers.forEach { c ->
            val trueBal = LedgerCalculator.calculateCustomerBalance(c.id, allLedgers)
            if (c.balance != trueBal) {
                db.customerDao().updateCustomer(c.copy(balance = trueBal))
            }
        }
        val suppliers = db.supplierDao().getAllSuppliersList()
        suppliers.forEach { s ->
            val trueBal = LedgerCalculator.calculateSupplierBalance(s.id, allLedgers)
            if (s.balance != trueBal) {
                db.supplierDao().updateSupplier(s.copy(balance = trueBal))
            }
        }
    }

    fun getLedgerForParty(partyType: String, partyId: String): Flow<List<LedgerEntry>> {
        return db.ledgerDao().getLedgerForParty(partyType, partyId)
    }

    suspend fun getCustomerLiveBalance(customerId: String): Double = withContext(Dispatchers.IO) {
        val entries = db.ledgerDao().getLedgerEntriesForPartyList("CUSTOMER", customerId)
        LedgerCalculator.calculateCustomerBalance(customerId, entries)
    }

    suspend fun generatePnlReport(startTime: Long, endTime: Long): ProfitAndLossReport = withContext(Dispatchers.IO) {
        val sales = db.saleDao().getSalesInRange(startTime, endTime)
        val expenses = db.expenseDao().getExpensesInRange(startTime, endTime)
        val returns = db.saleReturnDao().getAllReturnsList().filter {
            it.saleReturn.datetime in startTime..endTime
        }

        var totalRevenue = 0.0
        var totalCogs = 0.0
        var cashSales = 0.0
        var upiSales = 0.0
        var creditSales = 0.0

        var onlineRevenue = 0.0
        var onlineCogs = 0.0
        var onlineOrderCount = 0
        var walkInRevenue = 0.0
        var walkInCogs = 0.0
        var walkInOrderCount = 0

        val allProductsMap = db.productDao().getAllProductsList().associateBy { it.id }

        sales.forEach { saleWithItems ->
            val sale = saleWithItems.sale
            totalRevenue += sale.finalAmount
            when (sale.paymentMode.uppercase()) {
                "CASH" -> cashSales += sale.finalAmount
                "UPI" -> upiSales += sale.finalAmount
                "CREDIT" -> creditSales += sale.finalAmount
            }

            val isOnline = sale.notes?.contains("Online Order", ignoreCase = true) == true
            if (isOnline) {
                onlineRevenue += sale.finalAmount
                onlineOrderCount++
            } else {
                walkInRevenue += sale.finalAmount
                walkInOrderCount++
            }

            val consolidatedItems = com.example.utils.SaleConsolidationUtils.consolidateSaleItems(saleWithItems.items) { prodId ->
                allProductsMap[prodId]
            }
            var saleCogs = 0.0
            consolidatedItems.forEach { item ->
                saleCogs += item.totalCost
            }
            totalCogs += saleCogs
            if (isOnline) {
                onlineCogs += saleCogs
            } else {
                walkInCogs += saleCogs
            }
        }

        val salesById = sales.associateBy { it.sale.id }

        // Adjust for returns and replacements
        returns.forEach { retWithItems ->
            val sr = retWithItems.saleReturn
            totalRevenue -= sr.totalReturnedAmount
            totalRevenue += sr.totalReplacementAmount

            var retCogs = 0.0
            retWithItems.items.forEach { rItem ->
                val product = db.productDao().getProductById(rItem.productId)
                val purchasePrice = product?.costPrice ?: (rItem.unitPrice * 0.7)
                val itemCost = purchasePrice * rItem.quantity
                if (rItem.isReplacement) {
                    totalCogs += itemCost
                    retCogs -= itemCost
                } else {
                    totalCogs -= itemCost
                    retCogs += itemCost
                }
            }

            val originalSale = salesById[sr.saleId]?.sale ?: db.saleDao().getSaleById(sr.saleId)?.sale
            val isOnline = originalSale?.notes?.contains("Online Order", ignoreCase = true) == true

            if (isOnline) {
                onlineRevenue -= sr.totalReturnedAmount
                onlineRevenue += sr.totalReplacementAmount
                onlineCogs -= retCogs
            } else {
                walkInRevenue -= sr.totalReturnedAmount
                walkInRevenue += sr.totalReplacementAmount
                walkInCogs -= retCogs
            }
        }

        val onlineGrossProfit = onlineRevenue - onlineCogs
        val walkInGrossProfit = walkInRevenue - walkInCogs
        val onlineRevenuePercentage = if (totalRevenue > 0) (onlineRevenue / totalRevenue) * 100.0 else 0.0

        val stockOuts = db.stockOutDao().getStockOutsInRange(startTime, endTime)
        val stockLoss = stockOuts.filter { it.isBusinessLoss() }.sumOf { it.totalCostValue }

        // Filter out any legacy "Stock Loss / Wastage" general expense to prevent double counting
        val filteredExpenses = expenses.filterNot {
            it.category.contains("Stock Loss", ignoreCase = true) || it.category.contains("Wastage", ignoreCase = true)
        }
        val totalExpensesAmount = filteredExpenses.sumOf { it.amount }

        val profitResult = com.example.utils.ProfitCalculatorService.calculateNetProfit(
            salesRevenue = totalRevenue,
            cogs = totalCogs,
            totalExpenses = totalExpensesAmount,
            stockLoss = stockLoss
        )

        val purchases = db.purchaseDao().getPurchasesInRange(startTime, endTime)
        val salaryPayments = db.employeeSalaryDao().getAllSalaryPaymentsList().filter { it.paymentDate in startTime..endTime }
        val purchasesSpend = purchases.sumOf { it.purchase.amountPaid }
        val salarySpend = salaryPayments.sumOf { it.netSalaryPaid }

        val categoryBreakdown = filteredExpenses.groupBy { it.category }.map { (cat, list) ->
            val sum = list.sumOf { it.amount }
            val pct = if (totalExpensesAmount > 0) (sum / totalExpensesAmount) * 100.0 else 0.0
            com.example.utils.ExpenseCategorySummary(
                categoryKey = cat,
                categoryNameEn = cat,
                categoryNameBn = cat,
                totalAmount = sum,
                percentage = pct,
                count = list.size,
                colorHex = 0xFFD32F2F
            )
        }.sortedByDescending { it.totalAmount }

        val topCat = categoryBreakdown.firstOrNull()?.categoryNameEn
        val totalOutflow = purchasesSpend + salarySpend + totalExpensesAmount + stockLoss

        ProfitAndLossReport(
            totalRevenue = profitResult.salesRevenue,
            totalCogs = profitResult.costOfGoodsSold,
            grossProfit = profitResult.grossProfit,
            totalExpenses = profitResult.totalExpenses,
            stockLoss = profitResult.stockLoss,
            netProfit = profitResult.netProfit,
            totalSalesCount = sales.size,
            cashSales = cashSales,
            upiSales = upiSales,
            creditSales = creditSales,
            expensesList = filteredExpenses,
            expenseCategoryBreakdown = categoryBreakdown,
            totalPurchasesSpend = purchasesSpend,
            staffSalarySpend = salarySpend,
            totalMoneySpent = totalOutflow,
            topExpenseCategory = topCat,
            narrativeInWordsEn = "",
            narrativeInWordsBn = "",
            totalExpensesInWordsEn = "",
            totalExpensesInWordsBn = "",
            onlineRevenue = onlineRevenue,
            onlineCogs = onlineCogs,
            onlineGrossProfit = onlineGrossProfit,
            onlineOrderCount = onlineOrderCount,
            walkInRevenue = walkInRevenue,
            walkInCogs = walkInCogs,
            walkInGrossProfit = walkInGrossProfit,
            walkInOrderCount = walkInOrderCount,
            onlineRevenuePercentage = onlineRevenuePercentage
        )
    }

    suspend fun generateComprehensiveBusinessReport(startTime: Long, endTime: Long): com.example.utils.ComprehensiveBusinessReport = withContext(Dispatchers.IO) {
        val sales = db.saleDao().getSalesInRange(startTime, endTime)
        val expenses = db.expenseDao().getExpensesInRange(startTime, endTime)
        val purchases = db.purchaseDao().getPurchasesInRange(startTime, endTime)
        val salaryPayments = db.employeeSalaryDao().getAllSalaryPaymentsList().filter { it.paymentDate in startTime..endTime }
        val stockOuts = db.stockOutDao().getStockOutsInRange(startTime, endTime)
        val products = db.productDao().getAllProductsList()
        val returns = db.saleReturnDao().getAllReturnsList().filter { it.saleReturn.datetime in startTime..endTime }
        val offers = db.offerDao().getAllOffersList()

        com.example.utils.BusinessAnalyticsService.generateComprehensiveReport(
            startTime = startTime,
            endTime = endTime,
            sales = sales,
            expenses = expenses,
            purchases = purchases,
            salaryPayments = salaryPayments,
            stockOuts = stockOuts,
            products = products,
            returns = returns,
            offers = offers
        )
    }

    // --- Stock-Out & Wastage Tracking ---
    val allStockOuts: Flow<List<StockOutEntry>> = db.stockOutDao().getAllStockOuts()

    suspend fun recordStockOut(entry: StockOutEntry) = withContext(Dispatchers.IO) {
        db.stockOutDao().insertStockOut(entry)
    }

    suspend fun deleteStockOut(entry: StockOutEntry) = withContext(Dispatchers.IO) {
        db.stockOutDao().deleteStockOut(entry)
    }

    suspend fun getStockOutsInRange(startTime: Long, endTime: Long): List<StockOutEntry> = withContext(Dispatchers.IO) {
        db.stockOutDao().getStockOutsInRange(startTime, endTime)
    }

    suspend fun generateStockOutReport(startTime: Long, endTime: Long): StockOutReportSummary = withContext(Dispatchers.IO) {
        val list = db.stockOutDao().getStockOutsInRange(startTime, endTime)
        var damagedCost = 0.0
        var damagedQty = 0.0
        var expiredCost = 0.0
        var expiredQty = 0.0
        var wastageCost = 0.0
        var wastageQty = 0.0
        var personalUseCost = 0.0
        var personalUseQty = 0.0
        var otherCost = 0.0
        var otherQty = 0.0

        for (entry in list) {
            val cost = entry.totalCostValue
            val qty = entry.quantity
            when (entry.getReasonCategory()) {
                StockOutEntry.ReasonCategory.DAMAGED -> {
                    damagedCost += cost
                    damagedQty += qty
                }
                StockOutEntry.ReasonCategory.EXPIRED -> {
                    expiredCost += cost
                    expiredQty += qty
                }
                StockOutEntry.ReasonCategory.WASTAGE -> {
                    wastageCost += cost
                    wastageQty += qty
                }
                StockOutEntry.ReasonCategory.PERSONAL_USE -> {
                    personalUseCost += cost
                    personalUseQty += qty
                }
                StockOutEntry.ReasonCategory.OTHER -> {
                    otherCost += cost
                    otherQty += qty
                }
            }
        }

        val totalBusinessLossCost = damagedCost + expiredCost + wastageCost
        val combinedTotalCost = totalBusinessLossCost + personalUseCost + otherCost

        StockOutReportSummary(
            startTime = startTime,
            endTime = endTime,
            entries = list,
            damagedCost = damagedCost,
            damagedQty = damagedQty,
            expiredCost = expiredCost,
            expiredQty = expiredQty,
            wastageCost = wastageCost,
            wastageQty = wastageQty,
            personalUseCost = personalUseCost,
            personalUseQty = personalUseQty,
            otherCost = otherCost,
            otherQty = otherQty,
            totalBusinessLossCost = totalBusinessLossCost,
            combinedTotalCost = combinedTotalCost
        )
    }

    val allEmployees: Flow<List<Employee>> = db.employeeDao().getAllEmployees()
    val activeEmployees: Flow<List<Employee>> = db.employeeDao().getActiveEmployees()

    suspend fun getEmployeeById(id: String): Employee? = withContext(Dispatchers.IO) {
        db.employeeDao().getEmployeeById(id)
    }

    suspend fun getEmployeeByEmail(email: String): Employee? = withContext(Dispatchers.IO) {
        db.employeeDao().getEmployeeByEmail(email)
    }

    suspend fun saveEmployee(employee: Employee) = withContext(Dispatchers.IO) {
        val cleanEmail = employee.email.trim().lowercase()
        val finalEmployee = if (cleanEmail.isNotBlank()) {
            val existing = db.employeeDao().getEmployeeByEmail(cleanEmail)
            if (existing != null) {
                // Email exists: update existing record with new details instead of creating a duplicate document
                employee.copy(
                    id = existing.id,
                    email = cleanEmail,
                    createdAt = existing.createdAt
                )
            } else {
                employee.copy(email = cleanEmail)
            }
        } else {
            employee
        }

        db.employeeDao().insertEmployee(finalEmployee)
        try {
            firestoreManager.saveEmployee(finalEmployee)
        } catch (e: Exception) {
            android.util.Log.w("StoreRepository", "Firestore save employee sync note: ${e.message}")
        }

        // Purge any other duplicate employee records with the same email
        if (cleanEmail.isNotBlank()) {
            try {
                val allEmployees = db.employeeDao().getAllEmployeesList()
                val duplicates = allEmployees.filter { it.email.trim().lowercase() == cleanEmail && it.id != finalEmployee.id }
                for (dup in duplicates) {
                    db.employeeDao().deleteEmployeeById(dup.id)
                    try { firestoreManager.deleteEmployee(dup.id) } catch (e: Exception) {}
                }
            } catch (e: Exception) {
                android.util.Log.w("StoreRepository", "Duplicate purge note: ${e.message}")
            }
        }
    }

    suspend fun updateEmployee(employee: Employee) = withContext(Dispatchers.IO) {
        val cleanEmail = employee.email.trim().lowercase()
        val finalEmployee = if (cleanEmail.isNotBlank()) employee.copy(email = cleanEmail) else employee
        db.employeeDao().insertEmployee(finalEmployee)
        try {
            firestoreManager.saveEmployee(finalEmployee)
        } catch (e: Exception) {
            android.util.Log.w("StoreRepository", "Firestore update employee sync note: ${e.message}")
        }
    }

    suspend fun deleteEmployee(employee: Employee) = withContext(Dispatchers.IO) {
        db.employeeDao().deleteEmployee(employee)
        try {
            firestoreManager.deleteEmployee(employee.id)
        } catch (e: Exception) {
            android.util.Log.w("StoreRepository", "Firestore delete employee sync note: ${e.message}")
        }
    }

    /**
     * Deduplicates and synchronizes remote Employee records from Firestore into SQLite.
     * Consolidates duplicate records sharing the same normalized email address.
     */
    suspend fun deduplicateAndInsertRemoteEmployees(remoteEmployees: List<Employee>) = withContext(Dispatchers.IO) {
        if (remoteEmployees.isEmpty()) return@withContext
        try {
            val existingEmployees = db.employeeDao().getAllEmployeesList()
            val employeesByEmail = remoteEmployees.groupBy { it.email.trim().lowercase() }
            val consolidatedList = mutableListOf<Employee>()
            val idsToDelete = mutableSetOf<String>()

            for ((email, group) in employeesByEmail) {
                if (email.isBlank()) {
                    consolidatedList.addAll(group)
                    continue
                }

                val localMatches = existingEmployees.filter { it.email.trim().lowercase() == email }
                val allForEmail = group + localMatches

                // Pick canonical ID: prefer cloud_ ID or the one with auth/cloud prefix
                val canonicalId = allForEmail.find { it.id.startsWith("cloud_") }?.id
                    ?: group.first().id

                val canonicalName = allForEmail.map { it.name.trim() }.firstOrNull { it.isNotBlank() && it != "App User" && it != "Employee" }
                    ?: group.first().name.ifBlank { email.substringBefore("@") }

                val phone = allForEmail.map { it.phone.trim() }.firstOrNull { it.isNotBlank() } ?: ""
                val baseSalary = allForEmail.map { it.baseSalary }.firstOrNull { it > 0.0 } ?: 0.0
                val salaryType = allForEmail.map { it.salaryType }.firstOrNull { it.isNotBlank() } ?: "MONTHLY"
                val joiningDate = allForEmail.map { it.joiningDate }.firstOrNull { it > 0 } ?: System.currentTimeMillis()
                val address = allForEmail.map { it.address.trim() }.firstOrNull { it.isNotBlank() } ?: ""
                val emergencyContact = allForEmail.map { it.emergencyContact.trim() }.firstOrNull { it.isNotBlank() } ?: ""
                val pin = allForEmail.map { it.pin.trim() }.firstOrNull { it.isNotBlank() } ?: "1234"

                val role = when {
                    allForEmail.any { it.role.equals("STORE_MANAGER", ignoreCase = true) || it.role.equals("ADMIN", ignoreCase = true) } -> "STORE_MANAGER"
                    allForEmail.any { it.role.equals("SALES_STAFF", ignoreCase = true) } -> "SALES_STAFF"
                    else -> group.first().role.ifBlank { "CASHIER" }
                }
                val designation = when (role.uppercase()) {
                    "STORE_MANAGER", "ADMIN" -> "Store Manager"
                    "SALES_STAFF" -> "Sales Staff"
                    else -> "Cashier"
                }

                val consolidated = group.first().copy(
                    id = canonicalId,
                    name = canonicalName,
                    email = email,
                    phone = phone,
                    pin = pin,
                    role = role,
                    designation = designation,
                    baseSalary = baseSalary,
                    salaryType = salaryType,
                    joiningDate = joiningDate,
                    address = address,
                    emergencyContact = emergencyContact,
                    isActive = allForEmail.any { it.isActive }
                )
                consolidatedList.add(consolidated)

                val secondaryIds = allForEmail.map { it.id }.filter { it != canonicalId }.toSet()
                idsToDelete.addAll(secondaryIds)
            }

            db.employeeDao().insertEmployeesFromSync(consolidatedList.map { it.copy(needsSync = false) })
            for (secId in idsToDelete) {
                db.employeeDao().deleteEmployeeById(secId)
                try {
                    firestoreManager.deleteEmployee(secId)
                } catch (e: Exception) {
                    // ignore
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("StoreRepository", "deduplicateAndInsertRemoteEmployees failed: ${e.message}")
        }
    }

    /**
     * Bridges Google authenticated app users (and preassigned roles) from Firestore into the local
     * and cloud Employee database so that every registered/invited user shows up in the Employee roster.
     * Guaranteed to deduplicate by normalized email (email.trim().lowercase()), preferring genuine
     * Firebase Auth UID accounts over pre-assigned email placeholders.
     */
    suspend fun syncAppUsersToEmployees(userRoles: List<com.example.data.firestore.FirestoreUserRole>) = withContext(Dispatchers.IO) {
        if (userRoles.isEmpty()) return@withContext
        try {
            val existingEmployees = db.employeeDao().getAllEmployeesList()
            val employeesToSave = mutableListOf<Employee>()

            // 1. Group by normalized email and merge duplicates into a single consolidated record per person
            val rolesByEmail = userRoles.groupBy { it.email?.trim()?.lowercase() ?: "" }
            val deduplicatedUsers = mutableListOf<com.example.data.firestore.FirestoreUserRole>()

            for ((email, group) in rolesByEmail) {
                if (email.isBlank()) {
                    deduplicatedUsers.addAll(group)
                } else {
                    deduplicatedUsers.add(com.example.data.firestore.mergeUserRoleGroup(group))
                }
            }

            for (user in deduplicatedUsers) {
                val cleanEmail = user.email?.trim()?.lowercase() ?: ""
                if (cleanEmail.isBlank() && user.uid.isBlank()) continue

                // Match with any existing local Employee record by normalized email or ID
                val matchingEmployees = existingEmployees.filter { emp ->
                    val empEmail = emp.email.trim().lowercase()
                    (cleanEmail.isNotBlank() && empEmail == cleanEmail) ||
                    (user.uid.isNotBlank() && emp.id == "cloud_${user.uid}") ||
                    (cleanEmail.isNotBlank() && emp.id == "cloud_${cleanEmail.replace(".", "_").replace("@", "_")}") ||
                    (user.uid.isNotBlank() && emp.id == user.uid) ||
                    (user.documentId.isNotBlank() && emp.id == user.documentId)
                }

                val preferredId = if (user.hasRealAuthUid) {
                    "cloud_${user.uid}"
                } else if (cleanEmail.isNotBlank()) {
                    "cloud_${cleanEmail.replace(".", "_").replace("@", "_")}"
                } else {
                    user.uid.ifBlank { java.util.UUID.randomUUID().toString() }
                }

                val mappedRole = when (user.role.uppercase()) {
                    "ADMIN" -> "STORE_MANAGER"
                    "STORE_MANAGER" -> "STORE_MANAGER"
                    "EMPLOYEE" -> "CASHIER"
                    "CASHIER" -> "CASHIER"
                    "SALES_STAFF" -> "SALES_STAFF"
                    else -> if (user.role.isNotBlank()) user.role else "CASHIER"
                }
                val mappedDesignation = when (user.role.uppercase()) {
                    "ADMIN" -> "Store Admin"
                    "STORE_MANAGER" -> "Store Manager"
                    "EMPLOYEE" -> "Cashier / Staff"
                    "CASHIER" -> "Cashier"
                    "SALES_STAFF" -> "Sales Staff"
                    else -> "Staff"
                }
                val displayName = when {
                    user.hasRealAuthUid && !user.displayName.isNullOrBlank() && user.displayName != "App User" && user.displayName != "Employee" -> user.displayName.trim()
                    matchingEmployees.any { it.name.isNotBlank() && it.name != "App User" && it.name != "Employee" } -> 
                        matchingEmployees.first { it.name.isNotBlank() && it.name != "App User" && it.name != "Employee" }.name
                    !user.displayName.isNullOrBlank() -> user.displayName.trim()
                    cleanEmail.isNotBlank() -> cleanEmail.substringBefore("@").replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }
                    else -> "App User"
                }

                // Consolidate rich details across all matching records
                val phone = matchingEmployees.map { it.phone.trim() }.firstOrNull { it.isNotBlank() } ?: ""
                val baseSalary = matchingEmployees.map { it.baseSalary }.firstOrNull { it > 0.0 } ?: 0.0
                val salaryType = matchingEmployees.map { it.salaryType }.firstOrNull { it.isNotBlank() } ?: "MONTHLY"
                val joiningDate = matchingEmployees.map { it.joiningDate }.firstOrNull { it > 0 } ?: System.currentTimeMillis()
                val address = matchingEmployees.map { it.address.trim() }.firstOrNull { it.isNotBlank() } ?: ""
                val emergencyContact = matchingEmployees.map { it.emergencyContact.trim() }.firstOrNull { it.isNotBlank() } ?: ""
                val pin = matchingEmployees.map { it.pin.trim() }.firstOrNull { it.isNotBlank() } ?: "1234"
                val createdAt = matchingEmployees.map { it.createdAt }.firstOrNull { it > 0 } ?: (if (user.createdAt > 0) user.createdAt else System.currentTimeMillis())

                val consolidatedEmp = Employee(
                    id = preferredId,
                    name = displayName,
                    email = cleanEmail,
                    phone = phone,
                    pin = pin,
                    role = mappedRole,
                    designation = mappedDesignation,
                    baseSalary = baseSalary,
                    salaryType = salaryType,
                    joiningDate = joiningDate,
                    address = address,
                    emergencyContact = emergencyContact,
                    canMakeSales = user.effectiveCanMakeSales,
                    canViewCostPrice = user.effectiveCanViewCostPrice,
                    canManageInventory = user.effectiveCanManageInventory,
                    canViewKhata = user.effectiveCanViewKhata,
                    canManageExpenses = user.effectiveCanManageExpenses,
                    canViewReports = user.effectiveCanViewReports,
                    canAccessSettings = user.effectiveCanAccessSettings,
                    canGiveDiscount = user.effectiveCanGiveDiscount,
                    canDeleteSales = user.effectiveCanDeleteSales,
                    isActive = !user.isUnrecognized,
                    createdAt = createdAt
                )
                employeesToSave.add(consolidatedEmp)

                // Delete secondary duplicate records from Room and Firestore and re-point
                val secondaryIds = matchingEmployees.map { it.id }.filter { it != preferredId }
                for (secId in secondaryIds) {
                    db.saleDao().repointStaffId(secId, preferredId, displayName)
                    db.employeeDao().deleteEmployeeById(secId)
                    try {
                        firestoreManager.deleteEmployee(secId)
                    } catch (e: Exception) {
                        // ignore
                    }
                }
            }

            if (employeesToSave.isNotEmpty()) {
                db.employeeDao().insertEmployees(employeesToSave)
                try {
                    firestoreManager.saveEmployees(employeesToSave)
                } catch (e: Exception) {
                    android.util.Log.w("StoreRepository", "Error syncing employees to firestore: ${e.message}")
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("StoreRepository", "syncAppUsersToEmployees failed: ${e.message}")
        }
    }

    /**
     * One-time admin cleanup action:
     * - Discovers duplicate staff member profiles (same normalized email across multiple document/employee IDs).
     * - Re-points all Sale records (staffId / staffName), EmployeeAttendance (employeeId), and Salary/Dues/Advances (employeeId) from secondary IDs to the primary ID.
     * - Preserves the most authentic Google/Auth profile display name.
     * - Deletes orphaned secondary user documents in Firestore and duplicate local/cloud Employee records only after re-pointing succeeds.
     */
    suspend fun mergeDuplicateStaffProfiles(): StaffMergeResult = withContext(Dispatchers.IO) {
        var duplicateSetsFound = 0
        var salesRepointed = 0
        var attendanceRepointed = 0
        var salaryRecordsRepointed = 0
        var orphanUsersDeleted = 0
        var orphanEmployeesDeleted = 0
        val details = mutableListOf<String>()

        try {
            val allUsers = firestoreManager.getAllUsersOnce()
            val allEmployees = db.employeeDao().getAllEmployeesList()

            // Collect all unique normalized emails
            val allEmails = (allUsers.mapNotNull { it.email?.trim()?.lowercase() } +
                    allEmployees.mapNotNull { it.email.trim().lowercase() })
                .filter { it.isNotBlank() }
                .toSet()

            for (email in allEmails) {
                val usersForEmail = allUsers.filter { it.email?.trim()?.lowercase() == email }
                val employeesForEmail = allEmployees.filter { it.email.trim().lowercase() == email }

                val isDuplicate = usersForEmail.size > 1 || employeesForEmail.size > 1
                if (!isDuplicate) continue

                duplicateSetsFound++

                // 1. Determine Primary User Role
                val primaryUser = com.example.data.firestore.mergeUserRoleGroup(usersForEmail)
                val primaryDisplayName = if (primaryUser.hasRealAuthUid && !primaryUser.displayName.isNullOrBlank() && primaryUser.displayName != "App User" && primaryUser.displayName != "Employee") {
                    primaryUser.displayName.trim()
                } else {
                    employeesForEmail.map { it.name.trim() }.firstOrNull { it.isNotBlank() && it != "App User" && it != "Employee" && it != "Unrecognized User" }
                        ?: primaryUser.displayName ?: email.substringBefore("@")
                }

                // 2. Determine Primary Employee
                val primaryPreferredId = if (primaryUser.hasRealAuthUid) {
                    "cloud_${primaryUser.uid}"
                } else {
                    "cloud_${email.replace(".", "_").replace("@", "_")}"
                }

                val primaryEmp = employeesForEmail.find { it.id == primaryPreferredId }
                    ?: employeesForEmail.find { it.id == primaryUser.uid }
                    ?: employeesForEmail.firstOrNull()
                    ?: Employee(
                        id = primaryPreferredId,
                        name = primaryDisplayName,
                        email = email,
                        phone = "",
                        role = "CASHIER",
                        designation = "Staff"
                    )

                val phone = employeesForEmail.map { it.phone.trim() }.firstOrNull { it.isNotBlank() } ?: ""
                val baseSalary = employeesForEmail.map { it.baseSalary }.firstOrNull { it > 0.0 } ?: 0.0
                val salaryType = employeesForEmail.map { it.salaryType }.firstOrNull { it.isNotBlank() } ?: "MONTHLY"
                val joiningDate = employeesForEmail.map { it.joiningDate }.firstOrNull { it > 0 } ?: System.currentTimeMillis()
                val address = employeesForEmail.map { it.address.trim() }.firstOrNull { it.isNotBlank() } ?: ""
                val emergencyContact = employeesForEmail.map { it.emergencyContact.trim() }.firstOrNull { it.isNotBlank() } ?: ""
                val pin = employeesForEmail.map { it.pin.trim() }.firstOrNull { it.isNotBlank() } ?: "1234"

                val mappedRole = when (primaryUser.role.uppercase()) {
                    "ADMIN" -> "STORE_MANAGER"
                    "STORE_MANAGER" -> "STORE_MANAGER"
                    "EMPLOYEE" -> "CASHIER"
                    "CASHIER" -> "CASHIER"
                    "SALES_STAFF" -> "SALES_STAFF"
                    else -> if (primaryUser.role.isNotBlank()) primaryUser.role else (primaryEmp.role.ifBlank { "CASHIER" })
                }
                val mappedDesignation = when (mappedRole.uppercase()) {
                    "ADMIN", "STORE_MANAGER" -> "Store Manager"
                    "SALES_STAFF" -> "Sales Staff"
                    else -> "Cashier"
                }

                val consolidatedPrimaryEmp = primaryEmp.copy(
                    id = primaryPreferredId,
                    name = primaryDisplayName,
                    email = email,
                    phone = phone,
                    pin = pin,
                    role = mappedRole,
                    designation = mappedDesignation,
                    baseSalary = baseSalary,
                    salaryType = salaryType,
                    joiningDate = joiningDate,
                    address = address,
                    emergencyContact = emergencyContact,
                    canMakeSales = primaryUser.effectiveCanMakeSales,
                    canViewCostPrice = primaryUser.effectiveCanViewCostPrice,
                    canManageInventory = primaryUser.effectiveCanManageInventory,
                    canViewKhata = primaryUser.effectiveCanViewKhata,
                    canManageExpenses = primaryUser.effectiveCanManageExpenses,
                    canViewReports = primaryUser.effectiveCanViewReports,
                    canAccessSettings = primaryUser.effectiveCanAccessSettings,
                    canGiveDiscount = primaryUser.effectiveCanGiveDiscount,
                    canDeleteSales = primaryUser.effectiveCanDeleteSales,
                    isActive = !primaryUser.isUnrecognized
                )

                // 3. Collect Secondary (Orphan) IDs
                val secondaryEmpIds = employeesForEmail.map { it.id }.filter { it != consolidatedPrimaryEmp.id }.toSet()
                val secondaryUserDocIds = usersForEmail.map { it.documentId }.filter { it.isNotBlank() && it != primaryUser.documentId && it != primaryUser.uid }.toSet()
                val secondaryUids = usersForEmail.map { it.uid }.filter { it.isNotBlank() && it != primaryUser.uid }.toSet()
                val allSecondaryIds = (secondaryEmpIds + secondaryUserDocIds + secondaryUids + setOf("cloud_${email.replace(".", "_").replace("@", "_")}"))
                    .filter { it != consolidatedPrimaryEmp.id }
                    .toSet()

                // 4. Re-point Sales records
                for (oldId in allSecondaryIds) {
                    val updatedCount = db.saleDao().repointStaffId(oldId, consolidatedPrimaryEmp.id, consolidatedPrimaryEmp.name)
                    salesRepointed += updatedCount
                }
                val updatedByName = db.saleDao().repointStaffByName(email, consolidatedPrimaryEmp.id, consolidatedPrimaryEmp.name)
                salesRepointed += updatedByName

                // 5. Re-point Employee Attendance records
                val allAttendance = db.employeeAttendanceDao().getAllAttendanceList()
                val secondaryAttendance = allAttendance.filter { it.employeeId in allSecondaryIds }
                for (att in secondaryAttendance) {
                    val existingPrimaryAtt = db.employeeAttendanceDao().getAttendance(consolidatedPrimaryEmp.id, att.date)
                    if (existingPrimaryAtt == null) {
                        db.employeeAttendanceDao().insertAttendance(
                            att.copy(
                                id = UUID.randomUUID().toString(),
                                employeeId = consolidatedPrimaryEmp.id,
                                employeeName = consolidatedPrimaryEmp.name
                            )
                        )
                    }
                    db.employeeAttendanceDao().deleteAttendance(att)
                    attendanceRepointed++
                }

                // 6. Re-point Salary Dues records
                val allDues = db.employeeSalaryDao().getAllSalaryDuesList()
                val secondaryDues = allDues.filter { it.employeeId in allSecondaryIds }
                for (due in secondaryDues) {
                    val existingPrimaryDue = db.employeeSalaryDao().getSalaryDueForEmployeeAndMonth(consolidatedPrimaryEmp.id, due.monthYear)
                    if (existingPrimaryDue == null) {
                        db.employeeSalaryDao().insertSalaryDue(
                            due.copy(
                                id = UUID.randomUUID().toString(),
                                employeeId = consolidatedPrimaryEmp.id,
                                employeeName = consolidatedPrimaryEmp.name
                            )
                        )
                    }
                    db.employeeSalaryDao().deleteSalaryDue(due)
                    salaryRecordsRepointed++
                }

                // 7. Re-point Salary Payments records
                val allPayments = db.employeeSalaryDao().getAllSalaryPaymentsList()
                val secondaryPayments = allPayments.filter { it.employeeId in allSecondaryIds }
                for (pay in secondaryPayments) {
                    db.employeeSalaryDao().insertSalaryPayment(
                        pay.copy(
                            id = pay.id,
                            employeeId = consolidatedPrimaryEmp.id,
                            employeeName = consolidatedPrimaryEmp.name
                        )
                    )
                    salaryRecordsRepointed++
                }

                // 8. Re-point Advances records
                val allAdvances = db.employeeSalaryDao().getAllAdvancesList()
                val secondaryAdvances = allAdvances.filter { it.employeeId in allSecondaryIds }
                for (adv in secondaryAdvances) {
                    db.employeeSalaryDao().insertAdvance(
                        adv.copy(
                            id = adv.id,
                            employeeId = consolidatedPrimaryEmp.id,
                            employeeName = consolidatedPrimaryEmp.name
                        )
                    )
                    salaryRecordsRepointed++
                }

                // 9. Save consolidated primary Employee to Room & Firestore
                db.employeeDao().insertEmployee(consolidatedPrimaryEmp)
                try {
                    firestoreManager.saveEmployee(consolidatedPrimaryEmp)
                } catch (e: Exception) {
                    Log.w("StoreRepository", "Could not sync consolidated primary employee: ${e.message}")
                }

                // 10. Delete orphaned secondary records from local DB and Firestore
                for (secEmpId in secondaryEmpIds) {
                    db.employeeDao().deleteEmployeeById(secEmpId)
                    try {
                        firestoreManager.deleteEmployee(secEmpId)
                    } catch (e: Exception) {
                        Log.w("StoreRepository", "Could not delete secondary cloud employee $secEmpId: ${e.message}")
                    }
                    orphanEmployeesDeleted++
                }

                for (secDocId in secondaryUserDocIds) {
                    try {
                        firestoreManager.deleteUserRole("", secDocId)
                    } catch (e: Exception) {
                        Log.w("StoreRepository", "Could not delete secondary user doc $secDocId: ${e.message}")
                    }
                    orphanUsersDeleted++
                }

                details.add("Merged profile for $email -> Primary: ${consolidatedPrimaryEmp.name} (ID: ${consolidatedPrimaryEmp.id})")
            }

            StaffMergeResult(
                duplicateSetsFound = duplicateSetsFound,
                salesRepointed = salesRepointed,
                attendanceRepointed = attendanceRepointed,
                salaryRecordsRepointed = salaryRecordsRepointed,
                orphanUsersDeleted = orphanUsersDeleted,
                orphanEmployeesDeleted = orphanEmployeesDeleted,
                isSuccess = true,
                details = details
            )
        } catch (e: Exception) {
            Log.e("StoreRepository", "Error in mergeDuplicateStaffProfiles: ${e.message}", e)
            StaffMergeResult(
                duplicateSetsFound = duplicateSetsFound,
                salesRepointed = salesRepointed,
                attendanceRepointed = attendanceRepointed,
                salaryRecordsRepointed = salaryRecordsRepointed,
                orphanUsersDeleted = orphanUsersDeleted,
                orphanEmployeesDeleted = orphanEmployeesDeleted,
                isSuccess = false,
                details = details,
                errorMessage = e.message ?: "An unknown error occurred during merge"
            )
        }
    }

    // --- Employee Attendance ---
    fun getAllAttendance(): Flow<List<EmployeeAttendance>> = db.employeeAttendanceDao().getAllAttendance()

    fun getAttendanceForDate(date: String): Flow<List<EmployeeAttendance>> = db.employeeAttendanceDao().getAttendanceForDate(date)

    suspend fun getAttendanceListForDate(date: String): List<EmployeeAttendance> = withContext(Dispatchers.IO) {
        db.employeeAttendanceDao().getAttendanceListForDate(date)
    }

    fun getAttendanceForEmployee(employeeId: String): Flow<List<EmployeeAttendance>> = db.employeeAttendanceDao().getAttendanceForEmployee(employeeId)

    fun getAttendanceForEmployeeInMonth(employeeId: String, monthPrefix: String): Flow<List<EmployeeAttendance>> =
        db.employeeAttendanceDao().getAttendanceForEmployeeInMonth(employeeId, monthPrefix)

    suspend fun saveAttendance(attendance: EmployeeAttendance) = withContext(Dispatchers.IO) {
        db.employeeAttendanceDao().insertAttendance(attendance)
        try {
            firestoreManager.saveAttendance(attendance)
        } catch (e: Exception) {
            android.util.Log.w("StoreRepository", "Firestore save attendance sync note: ${e.message}")
        }
    }

    suspend fun saveAttendanceBatch(attendanceList: List<EmployeeAttendance>) = withContext(Dispatchers.IO) {
        if (attendanceList.isEmpty()) return@withContext
        db.employeeAttendanceDao().insertAttendanceBatch(attendanceList)
        try {
            firestoreManager.saveAttendanceBatch(attendanceList)
        } catch (e: Exception) {
            android.util.Log.w("StoreRepository", "Firestore save attendance batch sync note: ${e.message}")
        }
    }

    suspend fun markAllEmployeesPresent(date: String) = withContext(Dispatchers.IO) {
        val activeList = db.employeeDao().getAllEmployeesList().filter { it.isActive }
        val attendanceList = activeList.map { emp ->
            EmployeeAttendance(
                id = UUID.randomUUID().toString(),
                employeeId = emp.id,
                employeeName = emp.name,
                date = date,
                status = "PRESENT",
                checkInTime = "09:00 AM",
                checkOutTime = "08:00 PM"
            )
        }
        db.employeeAttendanceDao().insertAttendanceBatch(attendanceList)
        try {
            firestoreManager.saveAttendanceBatch(attendanceList)
        } catch (e: Exception) {
            android.util.Log.w("StoreRepository", "Firestore mark all attendance sync note: ${e.message}")
        }
    }

    suspend fun deleteAttendance(employeeId: String, date: String) = withContext(Dispatchers.IO) {
        db.employeeAttendanceDao().deleteAttendanceByEmployeeAndDate(employeeId, date)
        try {
            firestoreManager.deleteAttendance(employeeId, date)
        } catch (e: Exception) {
            android.util.Log.w("StoreRepository", "Firestore delete attendance sync note: ${e.message}")
        }
    }

    // --- Employee Salary Payments & Advances ---
    val allSalaryDues: Flow<List<EmployeeSalaryDue>> = db.employeeSalaryDao().getAllSalaryDues()

    fun getSalaryDuesForEmployee(employeeId: String): Flow<List<EmployeeSalaryDue>> =
        db.employeeSalaryDao().getSalaryDuesForEmployee(employeeId)

    suspend fun recordSalaryDue(due: EmployeeSalaryDue) = withContext(Dispatchers.IO) {
        db.employeeSalaryDao().insertSalaryDue(due)
        try {
            firestoreManager.saveSalaryDue(due)
        } catch (e: Exception) {
            android.util.Log.w("StoreRepository", "Firestore save salary due sync note: ${e.message}")
        }
    }

    suspend fun getSalaryBreakdownsForMonth(monthYear: String): List<SalaryBreakdown> = withContext(Dispatchers.IO) {
        val activeStaff = db.employeeDao().getAllEmployeesList().filter { it.isActive }
        val monthPrefix = SalaryCalculator.getMonthPrefix(monthYear)
        activeStaff.map { staff ->
            val attendanceList = db.employeeAttendanceDao().getAttendanceListForEmployeeInMonth(staff.id, monthPrefix)
            SalaryCalculator.calculateSalaryBreakdown(staff, monthYear, attendanceList)
        }
    }

    suspend fun getSalaryBreakdownForEmployee(employeeId: String, monthYear: String): SalaryBreakdown? = withContext(Dispatchers.IO) {
        val staff = db.employeeDao().getEmployeeById(employeeId) ?: return@withContext null
        val monthPrefix = SalaryCalculator.getMonthPrefix(monthYear)
        val attendanceList = db.employeeAttendanceDao().getAttendanceListForEmployeeInMonth(staff.id, monthPrefix)
        SalaryCalculator.calculateSalaryBreakdown(staff, monthYear, attendanceList)
    }

    suspend fun recordSalaryDuesBatchFromBreakdowns(breakdowns: List<SalaryBreakdown>) = withContext(Dispatchers.IO) {
        val duesToInsert = mutableListOf<EmployeeSalaryDue>()
        for (breakdown in breakdowns) {
            val existing = db.employeeSalaryDao().getSalaryDueForEmployeeAndMonth(breakdown.employeeId, breakdown.monthYear)
            if (existing == null) {
                val noteText = "Attendance: ${breakdown.presentDays}P, ${breakdown.halfDays}H, ${breakdown.absentDays}A of ${breakdown.totalCalendarDays}d @ ₹${"%.2f".format(breakdown.perDayRate)}/d"
                duesToInsert.add(
                    EmployeeSalaryDue(
                        id = UUID.randomUUID().toString(),
                        employeeId = breakdown.employeeId,
                        employeeName = breakdown.employeeName,
                        monthYear = breakdown.monthYear,
                        dueAmount = breakdown.totalCalculatedDue,
                        dueDate = System.currentTimeMillis(),
                        notes = noteText
                    )
                )
            }
        }
        if (duesToInsert.isNotEmpty()) {
            db.employeeSalaryDao().insertSalaryDueBatch(duesToInsert)
            try {
                firestoreManager.saveSalaryDues(duesToInsert)
            } catch (e: Exception) {
                android.util.Log.w("StoreRepository", "Firestore save salary dues batch sync note: ${e.message}")
            }
        }
    }

    suspend fun recordSalaryDueForMonthForAllStaff(monthYear: String) = withContext(Dispatchers.IO) {
        val breakdowns = getSalaryBreakdownsForMonth(monthYear)
        recordSalaryDuesBatchFromBreakdowns(breakdowns)
    }

    suspend fun deleteSalaryDue(id: String) = withContext(Dispatchers.IO) {
        db.employeeSalaryDao().deleteSalaryDueById(id)
        try {
            firestoreManager.deleteSalaryDue(id)
        } catch (e: Exception) {
            android.util.Log.w("StoreRepository", "Firestore delete salary due sync note: ${e.message}")
        }
    }

    val allSalaryPayments: Flow<List<EmployeeSalaryPayment>> = db.employeeSalaryDao().getAllSalaryPayments()

    fun getSalaryPaymentsForEmployee(employeeId: String): Flow<List<EmployeeSalaryPayment>> =
        db.employeeSalaryDao().getPaymentsForEmployee(employeeId)

    suspend fun recordSalaryPayment(
        payment: EmployeeSalaryPayment,
        autoRecordExpense: Boolean = true
    ) = withContext(Dispatchers.IO) {
        db.employeeSalaryDao().insertSalaryPayment(payment)
        try {
            firestoreManager.saveSalaryPayment(payment)
        } catch (e: Exception) {
            android.util.Log.w("StoreRepository", "Firestore save salary payment sync note: ${e.message}")
        }

        // Mark associated active advances as deducted/settled if any advance was deducted
        if (payment.advanceDeduction > 0) {
            val pendingAdvances = db.employeeSalaryDao().getActiveAdvancesListForEmployee(payment.employeeId)
            var deductionRemaining = payment.advanceDeduction
            for (adv in pendingAdvances) {
                if (deductionRemaining <= 0) break
                val needed = adv.amount - adv.repaidAmount
                if (needed <= deductionRemaining) {
                    deductionRemaining -= needed
                    val updated = adv.copy(
                        repaidAmount = adv.amount,
                        status = "DEDUCTED",
                        settledDate = System.currentTimeMillis(),
                        settlementNotes = "Deducted in salary payout: ${payment.monthYear}"
                    )
                    db.employeeSalaryDao().updateAdvance(updated)
                    try {
                        if (firestoreManager.saveAdvance(updated)) {
                            db.employeeSalaryDao().markAdvancesSynced(listOf(updated.id))
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("StoreRepository", "Firestore save advance sync note: ${e.message}")
                    }
                } else {
                    val updated = adv.copy(
                        repaidAmount = adv.repaidAmount + deductionRemaining,
                        settlementNotes = "Partially deducted (${payment.monthYear})"
                    )
                    db.employeeSalaryDao().updateAdvance(updated)
                    try {
                        if (firestoreManager.saveAdvance(updated)) {
                            db.employeeSalaryDao().markAdvancesSynced(listOf(updated.id))
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("StoreRepository", "Firestore save advance sync note: ${e.message}")
                    }
                    deductionRemaining = 0.0
                }
            }
        }

        // Automatically record as an expense entry so P&L and Expense Reports reflect staff payroll
        if (autoRecordExpense && payment.netSalaryPaid > 0) {
            val expense = Expense(
                id = UUID.randomUUID().toString(),
                date = payment.paymentDate,
                category = "Salary",
                amount = payment.netSalaryPaid,
                note = "Staff Payroll: ${payment.employeeName} (${payment.monthYear}) • Base: ₹${payment.baseSalary}, Bonus: ₹${payment.bonus}, Deductions: ₹${payment.advanceDeduction + payment.otherDeductions}, Mode: ${payment.paymentMode}"
            )
            db.expenseDao().insertExpense(expense)
            try {
                if (firestoreManager.saveExpense(expense)) {
                    db.expenseDao().markExpensesSynced(listOf(expense.id))
                }
            } catch (e: Exception) {
                android.util.Log.w("StoreRepository", "Firestore payroll expense sync note: ${e.message}")
            }
        }
    }

    val allAdvances: Flow<List<EmployeeAdvance>> = db.employeeSalaryDao().getAllAdvances()

    fun getAdvancesForEmployee(employeeId: String): Flow<List<EmployeeAdvance>> =
        db.employeeSalaryDao().getAdvancesForEmployee(employeeId)

    fun getActiveAdvancesForEmployee(employeeId: String): Flow<List<EmployeeAdvance>> =
        db.employeeSalaryDao().getActiveAdvancesForEmployee(employeeId)

    suspend fun recordAdvance(advance: EmployeeAdvance) = withContext(Dispatchers.IO) {
        db.employeeSalaryDao().insertAdvance(advance)
        try {
            if (firestoreManager.saveAdvance(advance)) {
                db.employeeSalaryDao().markAdvancesSynced(listOf(advance.id))
            }
        } catch (e: Exception) {
            android.util.Log.w("StoreRepository", "Firestore save advance sync note: ${e.message}")
        }
    }

    suspend fun settleAdvance(advanceId: String, note: String = "Manually Settled") = withContext(Dispatchers.IO) {
        val adv = db.employeeSalaryDao().getAdvanceById(advanceId) ?: return@withContext
        val updated = adv.copy(
            repaidAmount = adv.amount,
            status = "SETTLED",
            settledDate = System.currentTimeMillis(),
            settlementNotes = note
        )
        db.employeeSalaryDao().updateAdvance(updated)
        try {
            if (firestoreManager.saveAdvance(updated)) {
                db.employeeSalaryDao().markAdvancesSynced(listOf(updated.id))
            }
        } catch (e: Exception) {
            android.util.Log.w("StoreRepository", "Firestore settle advance sync note: ${e.message}")
        }
    }

    suspend fun updateAdvance(advance: EmployeeAdvance) = withContext(Dispatchers.IO) {
        db.employeeSalaryDao().updateAdvance(advance)
        try {
            if (firestoreManager.saveAdvance(advance)) {
                db.employeeSalaryDao().markAdvancesSynced(listOf(advance.id))
            }
        } catch (e: Exception) {
            android.util.Log.w("StoreRepository", "Firestore update advance sync note: ${e.message}")
        }
    }

    suspend fun deleteAdvance(id: String) = withContext(Dispatchers.IO) {
        db.employeeSalaryDao().deleteAdvanceById(id)
        try {
            firestoreManager.deleteAdvance(id)
        } catch (e: Exception) {
            android.util.Log.w("StoreRepository", "Firestore delete advance sync note: ${e.message}")
        }
    }

    suspend fun deleteSalaryPayment(id: String) = withContext(Dispatchers.IO) {
        db.employeeSalaryDao().deleteSalaryPaymentById(id)
        try {
            firestoreManager.deleteSalaryPayment(id)
        } catch (e: Exception) {
            android.util.Log.w("StoreRepository", "Firestore delete salary payment sync note: ${e.message}")
        }
    }

    suspend fun seedSampleDataIfEmpty() = withContext(Dispatchers.IO) {
        // Public production mode: Ensure no sample data exists
        val sampleProd = db.productDao().getProductById("prod_1")
        if (sampleProd != null) {
            db.clearAllTables()
        }
    }

    suspend fun seedSampleDataForce() = withContext(Dispatchers.IO) {
        // No-op for public production build
    }

    suspend fun auditPastSalesForDuplicateProducts(): List<com.example.utils.SaleConsolidationUtils.DuplicateSaleAuditResult> = withContext(Dispatchers.IO) {
        val allSales = db.saleDao().getAllSalesList()
        val allProducts = db.productDao().getAllProductsList().associateBy { it.id }
        com.example.utils.SaleConsolidationUtils.auditSalesForDuplicates(allSales) { prodId -> allProducts[prodId] }
    }

    suspend fun repairDuplicateSaleLineItems(autoAdjustStock: Boolean = true): Int = withContext(Dispatchers.IO) {
        val auditResults = auditPastSalesForDuplicateProducts()
        if (auditResults.isEmpty()) return@withContext 0

        var repairedCount = 0
        val allProductsMap = db.productDao().getAllProductsList().associateBy { it.id }

        for (audit in auditResults) {
            val saleId = audit.saleId
            val saleWithItems = db.saleDao().getAllSalesList().find { it.sale.id == saleId } ?: continue
            val existingItems = saleWithItems.items
            val consolidatedItems = com.example.utils.SaleConsolidationUtils.sanitizeSaleItems(saleWithItems.sale, existingItems) { prodId ->
                allProductsMap[prodId]
            }

            // Replace duplicate sale items with consolidated items in Room atomically
            db.saleDao().replaceSaleWithItems(saleWithItems.sale, consolidatedItems)

            // If auto-adjust stock is requested, refund the extra deducted quantity to product stock
            if (autoAdjustStock) {
                for (entry in audit.duplicateEntries) {
                    val product = db.productDao().getProductById(entry.productId)
                    if (product != null && entry.lineCount > 1) {
                        val primaryBaseQty = product.convertQuantityToBaseUnit(entry.lineQuantities.first(), entry.units.first())
                        val totalDeductedBaseQty = entry.totalQuantity
                        val excessDeductedQty = (totalDeductedBaseQty - primaryBaseQty).coerceAtLeast(0.0)

                        if (excessDeductedQty > 0.0) {
                            val restoredStock = Product.roundQuantity(product.currentStock + excessDeductedQty)
                            val updatedProduct = product.copy(currentStock = restoredStock)
                            db.productDao().updateProduct(updatedProduct)
                            firestoreManager.saveProduct(updatedProduct)
                        }
                    }
                }
            }

            // Sync updated sale to Firestore
            firestoreManager.saveSale(saleWithItems.sale, consolidatedItems)
            repairedCount++
        }

        repairedCount
    }

    // ==================== OFFERS MANAGEMENT ====================

    suspend fun insertOffer(offer: Offer) = withContext(Dispatchers.IO) {
        db.offerDao().insertOffer(offer)
    }

    suspend fun updateOffer(offer: Offer) = withContext(Dispatchers.IO) {
        db.offerDao().updateOffer(offer.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteOffer(offer: Offer) = withContext(Dispatchers.IO) {
        db.offerDao().deleteOffer(offer)
    }

    suspend fun deleteOfferById(id: String) = withContext(Dispatchers.IO) {
        db.offerDao().deleteOfferById(id)
    }

    suspend fun toggleOfferActive(id: String, isActive: Boolean) = withContext(Dispatchers.IO) {
        db.offerDao().setOfferActive(id, isActive)
    }

    // ==================== KHATA LATE PAYMENT INTEREST ENGINE ====================

    suspend fun calculateCustomerInterestBreakdown(
        customer: Customer,
        settings: KhataInterestSettings = StoreInfoManager.getKhataInterestSettings(),
        asOfTimestamp: Long = System.currentTimeMillis()
    ): CustomerInterestBreakdown = withContext(Dispatchers.IO) {
        val entries = db.ledgerDao().getLedgerEntriesForPartyList("CUSTOMER", customer.id)
        KhataInterestCalculator.calculateCustomerInterest(
            customer = customer,
            allLedgerEntries = entries,
            settings = settings,
            calculationTimeMs = asOfTimestamp
        )
    }

    suspend fun postAccruedInterestToLedger(
        customer: Customer,
        accruedAmount: Double,
        customNote: String? = null,
        settings: KhataInterestSettings = StoreInfoManager.getKhataInterestSettings()
    ): Result<LedgerEntry> = withContext(Dispatchers.IO) {
        if (accruedAmount <= 0.0) {
            return@withContext Result.failure(IllegalArgumentException("Interest amount must be greater than 0"))
        }

        val now = System.currentTimeMillis()
        val effectiveRate = settings.getEffectiveRate(customer)
        val note = customNote.takeIf { !it.isNullOrBlank() }
            ?: KhataInterestCalculator.createInterestLedgerNote(effectiveRate, settings.calculationMode)

        val ledgerEntry = LedgerEntry(
            id = UUID.randomUUID().toString(),
            partyType = "CUSTOMER",
            partyId = customer.id,
            partyName = customer.name,
            type = "INTEREST_ACCRUED",
            amount = accruedAmount,
            datetime = now,
            note = note,
            dueDate = null
        )

        db.ledgerDao().insertLedgerEntry(ledgerEntry)
        recalculateCustomerBalance(customer.id)

        repositoryScope.launch {
            try {
                firestoreManager.saveLedgerEntry(ledgerEntry)
                val updatedCust = db.customerDao().getCustomerById(customer.id)
                if (updatedCust != null) {
                    firestoreManager.saveCustomer(updatedCust)
                }
            } catch (e: Exception) {
                android.util.Log.w("StoreRepository", "Firestore post interest sync note: ${e.message}")
            }
        }

        Result.success(ledgerEntry)
    }

    // ==========================================
    // ONLINE ORDERING METHODS (PHASE 3)
    // ==========================================

    fun getOrdersFlow(): Flow<List<com.example.data.models.Order>> = firestoreManager.getOrdersFlow()

    suspend fun updateOrderStatus(
        orderId: String,
        newStatus: String,
        cancelReason: String? = null,
        saleId: String? = null,
        staffName: String? = null,
        customerUid: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        firestoreManager.updateOrderStatus(orderId, newStatus, cancelReason, saleId, staffName, customerUid)
    }

    suspend fun updateOrderPaymentStatus(orderId: String, newPaymentStatus: String): Boolean = withContext(Dispatchers.IO) {
        firestoreManager.updateOrderPaymentStatus(orderId, newPaymentStatus)
    }

    suspend fun checkOrderStockAvailability(order: com.example.data.models.Order): List<com.example.data.models.InsufficientStockItem> = withContext(Dispatchers.IO) {
        val shortages = mutableListOf<com.example.data.models.InsufficientStockItem>()
        val unverifiedItems = mutableListOf<com.example.data.models.OrderItem>()

        for (item in order.items) {
            if (item.productId.isBlank() || item.productId.startsWith("quick_")) continue
            val localProduct = db.productDao().getProductById(item.productId)
            if (localProduct != null) {
                val variant = localProduct.findVariantByBarcode(item.variantBarcode ?: "") ?: localProduct.findMatchingVariant(item.name)
                val requiredBaseQty = if (variant != null) {
                    localProduct.calculateVariantBaseDeduction(variant, item.quantity)
                } else {
                    localProduct.convertQuantityToBaseUnit(item.quantity, item.unit)
                }
                if (requiredBaseQty > (localProduct.currentStock + 0.00001)) {
                    shortages.add(
                        com.example.data.models.InsufficientStockItem(
                            productId = item.productId,
                            productNameEn = localProduct.nameEn,
                            productNameBn = localProduct.nameBn,
                            unitType = localProduct.unitType,
                            availableStock = localProduct.currentStock,
                            requestedQuantity = requiredBaseQty
                        )
                    )
                }
            } else {
                unverifiedItems.add(item)
            }
        }

        if (unverifiedItems.isNotEmpty()) {
            val cloudShortages = firestoreManager.checkOrderStockAvailability(unverifiedItems)
            shortages.addAll(cloudShortages)
        }

        shortages
    }

    suspend fun fulfillOnlineOrder(
        order: com.example.data.models.Order,
        finalPaymentMethod: String? = null,
        creditCustomer: Customer? = null,
        ownerOverrideCreditLimit: Boolean = false,
        dispatchedByStaffName: String? = null,
        allowNegativeStockOverride: Boolean = true
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val saleId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val saleItems = mutableListOf<SaleItem>()
            val productDeductions = mutableMapOf<String, Double>()
            val productMap = mutableMapOf<String, Product>()

            for (item in order.items) {
                if (item.productId.isBlank()) continue
                val product = db.productDao().getProductById(item.productId)
                    ?: firestoreManager.getProductById(item.productId)
                if (product != null) {
                    productMap[item.productId] = product
                }

                val unitPrice = item.price
                val variant = product?.findVariantByBarcode(item.variantBarcode ?: "") ?: product?.findMatchingVariant(item.name)
                val costPrice = if (variant != null && variant.costPrice > 0.0) {
                    variant.costPrice
                } else if (variant != null && variant.quantity > 0.0) {
                    val baseCost = product?.costPrice ?: unitPrice
                    val baseDeductOne = product?.convertQuantityToBaseUnit(variant.quantity, variant.unitType) ?: 1.0
                    baseCost * baseDeductOne
                } else {
                    product?.getCostPriceForUnit(item.unit) ?: (product?.costPrice ?: unitPrice)
                }
                val totalCost = costPrice * item.quantity

                val vNameEn = if (variant != null && !variant.label.isNullOrBlank()) {
                    "${product?.nameEn ?: item.name} (${variant.label})"
                } else if (variant != null) {
                    "${product?.nameEn ?: item.name} (${variant.getShortLabel()})"
                } else {
                    product?.nameEn ?: item.name
                }

                saleItems.add(
                    SaleItem(
                        saleId = saleId,
                        productId = item.productId,
                        productNameEn = vNameEn,
                        productNameBn = product?.nameBn ?: "",
                        unitType = item.unit,
                        quantity = item.quantity,
                        unitPrice = unitPrice,
                        costPrice = costPrice,
                        subtotal = item.subtotal,
                        totalCost = totalCost,
                        mrp = variant?.mrp?.takeIf { it > 0.0 } ?: (product?.mrp ?: unitPrice),
                        variantBarcode = variant?.barcode ?: item.variantBarcode
                    )
                )

                val deductQty = if (variant != null) {
                    product?.calculateVariantBaseDeduction(variant, item.quantity) ?: item.quantity
                } else {
                    product?.convertQuantityToBaseUnit(item.quantity, item.unit) ?: item.quantity
                }
                productDeductions[item.productId] = (productDeductions[item.productId] ?: 0.0) + deductQty
            }

            val consolidatedItems = com.example.utils.SaleConsolidationUtils.consolidateSaleItems(saleItems) { null }

            // Determine payment mode, credit customer, and ledger entry
            val effectivePaymentMethod = (finalPaymentMethod ?: order.paymentMethod).trim().uppercase()
            val isCredit = effectivePaymentMethod == "CREDIT"
            val paymentMode = when {
                isCredit -> "CREDIT"
                effectivePaymentMethod.contains("CASH") -> "CASH"
                else -> "UPI"
            }

            val custId = if (isCredit) creditCustomer?.id else null
            val customerName = if (isCredit) (creditCustomer?.name ?: order.customerName) else order.customerName
            val receivedAmount = if (isCredit) 0.0 else order.totalAmount
            val dueAmount = if (isCredit) order.totalAmount else 0.0
            val prevBal = if (isCredit) (creditCustomer?.balance ?: 0.0) else 0.0

            var creditLedgerEntry: LedgerEntry? = null
            if (isCredit && !custId.isNullOrBlank()) {
                val defaultGraceDays = creditCustomer?.customGracePeriodDays ?: com.example.utils.StoreInfoManager.interestGracePeriodDays
                val creditDueDate = now + defaultGraceDays.toLong() * 24 * 60 * 60 * 1000L
                creditLedgerEntry = LedgerEntry(
                    id = UUID.randomUUID().toString(),
                    partyType = "CUSTOMER",
                    partyId = custId,
                    partyName = customerName,
                    type = "SALE_CREDIT",
                    amount = dueAmount,
                    datetime = now,
                    note = "Credit sale #${saleId.takeLast(6)} for Online Order #${order.orderNumber}",
                    referenceId = saleId,
                    dueDate = creditDueDate
                )
            }

            val notes = "Online Order ${order.orderNumber} (${order.fulfillmentType})" +
                    if (!order.paymentReference.isNullOrBlank()) " UTR: ${order.paymentReference}" else ""

            val sale = Sale(
                id = saleId,
                datetime = now,
                totalAmount = order.totalAmount,
                discount = 0.0,
                finalAmount = order.totalAmount,
                paymentMode = paymentMode,
                customerId = custId,
                customerName = customerName,
                isHeld = false,
                notes = notes,
                receivedAmount = receivedAmount,
                dueAmount = dueAmount,
                previousBalance = prevBal,
                staffId = com.example.utils.StaffManager.activeStaff?.id,
                staffName = com.example.utils.StaffManager.getCurrentStaffDisplayName().ifBlank { "Online Store" },
                dueDate = if (isCredit) creditLedgerEntry?.dueDate else null
            )

            // Reused atomic Firestore transaction verifies stock, deducts cloud stock, saves sale,
            // links saleId onto order, and sets status to COMPLETED
            val txResult = firestoreManager.executeSaleAndCreditTransaction(
                sale = sale,
                items = consolidatedItems,
                productDeductions = productDeductions,
                ledgerEntry = creditLedgerEntry,
                allowNegativeStock = allowNegativeStockOverride,
                ownerOverride = ownerOverrideCreditLimit,
                orderIdToComplete = order.id,
                dispatchedByStaffName = dispatchedByStaffName ?: com.example.utils.StaffManager.getActiveStaffOrOwnerName()
            )

            if (txResult.isFailure) {
                return@withContext Result.failure(txResult.exceptionOrNull() ?: Exception("Transaction failed"))
            }

            // Update local Room database so P&L, reports, and local stock immediately sync
            db.saleDao().replaceSaleWithItems(sale, consolidatedItems)

            for ((productId, deductQty) in productDeductions) {
                val product = productMap[productId] ?: db.productDao().getProductById(productId)
                if (product != null) {
                    val newStock = Product.roundQuantity((product.currentStock - deductQty).coerceAtLeast(0.0))
                    val updatedProduct = product.copy(currentStock = newStock)
                    db.productDao().updateProduct(updatedProduct)

                    val batches = db.productBatchDao().getBatchesForProductList(productId)
                    if (batches.isNotEmpty()) {
                        var remaining = deductQty
                        for (batch in batches) {
                            if (remaining <= 0) break
                            if (batch.quantity <= 0) continue

                            if (batch.quantity <= remaining) {
                                remaining -= batch.quantity
                                db.productBatchDao().updateBatch(batch.copy(quantity = 0.0))
                            } else {
                                val newQty = Product.roundQuantity(batch.quantity - remaining)
                                remaining = 0.0
                                db.productBatchDao().updateBatch(batch.copy(quantity = newQty))
                            }
                        }
                    }
                }
            }

            // If credit sale, update local ledger and customer balance
            if (isCredit && !custId.isNullOrBlank()) {
                if (creditLedgerEntry != null) {
                    db.ledgerDao().insertLedgerEntry(creditLedgerEntry)
                }
                recalculateCustomerBalance(custId)
            }

            Result.success(saleId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

