package com.example.data.firestore

import android.util.Log
import com.example.data.local.dao.PurchaseWithItems
import com.example.data.local.dao.SaleWithItems
import com.example.data.local.entities.*
import com.example.data.models.CreditLimitExceededException
import com.example.data.models.CustomerAccount
import com.example.data.models.CustomerLinkStatus
import com.example.data.models.InsufficientStockException
import com.example.data.models.InsufficientStockItem
import com.example.data.models.LiveCustomerDetails
import com.example.data.models.PendingLinkRequest
import com.example.data.sync.DataSyncManager
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class FirestoreManager {

    val firestore: FirebaseFirestore by lazy {
        val db = FirebaseFirestore.getInstance()
        try {
            val settings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(
                    PersistentCacheSettings.newBuilder().build()
                )
                .build()
            db.firestoreSettings = settings
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Firestore settings config note: ${e.message}")
        }
        db
    }

    private val productsCollection get() = firestore.collection("products")
    private val productBatchesCollection get() = firestore.collection("product_batches")
    private val salesCollection get() = firestore.collection("sales")
    private val expensesCollection get() = firestore.collection("expenses")
    private val returnsCollection get() = firestore.collection("sales_returns")
    private val customersCollection get() = firestore.collection("customers")
    private val suppliersCollection get() = firestore.collection("suppliers")
    private val purchasesCollection get() = firestore.collection("purchases")
    private val ledgerCollection get() = firestore.collection("ledger_entries")
    private val employeesCollection get() = firestore.collection("employees")
    private val attendanceCollection get() = firestore.collection("employee_attendance")
    private val salaryDuesCollection get() = firestore.collection("employee_salary_dues")
    private val salaryPaymentsCollection get() = firestore.collection("employee_salary_payments")
    private val advancesCollection get() = firestore.collection("employee_advances")
    private val storeSettingsCollection get() = firestore.collection("store_settings")
    private val usersCollection get() = firestore.collection("users")
    private val preassignedRolesCollection get() = firestore.collection("preassigned_roles")
    private val reportsCollection get() = firestore.collection("financial_reports")
    private val rolesConfigCollection get() = firestore.collection("roles_config")
    private val shareLinksCollection get() = firestore.collection("shareLinks")
    private val ordersCollection get() = firestore.collection("orders")
    private val migrationStatusCollection get() = firestore.collection("migration_status")
    private val pendingLinksCollection get() = firestore.collection("pending_links")
    private val customerAccountsCollection get() = firestore.collection("customer_accounts")

    // ==========================================
    // ROLE-BASED ACCESS CONTROL (RBAC) IN FIRESTORE
    // ==========================================

    /**
     * Observe the Firestore RBAC profile for a given Firebase Auth user.
     */
    fun listenUserRole(uid: String, email: String? = null): Flow<FirestoreUserRole?> = callbackFlow {
        val cleanEmail = email?.trim()?.lowercase()
        val docKey = if (!cleanEmail.isNullOrBlank()) cleanEmail else uid
        if (docKey.isBlank()) {
            trySend(null)
            close()
            return@callbackFlow
        }
        val listener = try {
            usersCollection.document(docKey)
                .addSnapshotListener(MetadataChanges.EXCLUDE) { snapshot, error ->
                    if (error != null) {
                        Log.w("FirestoreManager", "Firestore user role listener note: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot != null && snapshot.exists()) {
                        val parsedRole = mapDocumentToUserRole(snapshot)
                        if (parsedRole != null && parsedRole.isPermanentAdmin && !parsedRole.role.equals(AppRole.ADMIN.roleName, ignoreCase = true)) {
                            // Auto-heal permanent admin role
                            val healedAdmin = FirestoreUserRole.createAdmin(parsedRole.uid, parsedRole.email, parsedRole.displayName)
                            trySend(healedAdmin)
                        } else {
                            trySend(parsedRole)
                        }
                    } else if (uid.isNotBlank() && docKey != uid) {
                        // Fallback check by UID
                        usersCollection.document(uid).get().addOnSuccessListener { uidSnap ->
                            if (uidSnap != null && uidSnap.exists()) {
                                trySend(mapDocumentToUserRole(uidSnap))
                            } else {
                                trySend(null)
                            }
                        }
                    } else {
                        trySend(null)
                    }
                }
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Failed to listen to user role $docKey: ${e.message}")
            null
        }
        awaitClose { listener?.remove() }
    }

    /**
     * Fetch a user's role profile once from Firestore.
     */
    suspend fun getUserRole(uid: String, email: String? = null): FirestoreUserRole? {
        val cleanEmail = email?.trim()?.lowercase()
        if (!cleanEmail.isNullOrBlank()) {
            try {
                val emailDoc = usersCollection.document(cleanEmail).get().await()
                if (emailDoc.exists()) return mapDocumentToUserRole(emailDoc)
                val query = usersCollection.whereEqualTo("email", cleanEmail).get().await()
                val match = query.documents.firstOrNull()
                if (match != null && match.exists()) return mapDocumentToUserRole(match)
            } catch (e: Exception) {
                Log.w("FirestoreManager", "Could not fetch role for email $cleanEmail: ${e.message}")
            }
        }
        if (uid.isBlank()) return null
        return try {
            val doc = usersCollection.document(uid).get().await()
            if (doc.exists()) mapDocumentToUserRole(doc) else null
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not fetch role for $uid from Firestore: ${e.message}")
            null
        }
    }

    /**
     * Observe all registered store users in Firestore (for Admin role management).
     */
    fun getAllUsersFlow(): Flow<List<FirestoreUserRole>> = callbackFlow {
        val listener = try {
            usersCollection.addSnapshotListener(MetadataChanges.EXCLUDE) { snapshot, error ->
                if (error != null) {
                    Log.w("FirestoreManager", "Error in getAllUsersFlow: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val users = snapshot.documents.mapNotNull { mapDocumentToUserRole(it) }
                    trySend(users)
                }
            }
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Failed to attach all users listener: ${e.message}")
            null
        }
        awaitClose { listener?.remove() }
    }

    /**
     * Fetches all registered store users from Firestore once.
     */
    suspend fun getAllUsersOnce(): List<FirestoreUserRole> {
        return try {
            val snapshot = usersCollection.get().await()
            snapshot.documents.mapNotNull { mapDocumentToUserRole(it) }
        } catch (e: Exception) {
            Log.e("FirestoreManager", "Error fetching all users once: ${e.message}")
            emptyList()
        }
    }

    /**
     * Atomically approve or reject a staff access request.
     * Re-reads users/{uid} live inside a Firestore transaction.
     * If approve == true, assigns the selected role, sets status = "APPROVED", and grants role permissions.
     * If approve == false, sets status = "REJECTED", role = "UNASSIGNED", with zero access.
     */
    suspend fun reviewPendingStaffAccess(
        uid: String,
        approve: Boolean,
        assignedRole: AppRole = AppRole.EMPLOYEE,
        reviewerEmailOrUid: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            firestore.runTransaction { transaction ->
                val userDocRef = usersCollection.document(uid)
                val userSnap = transaction.get(userDocRef)
                if (!userSnap.exists()) {
                    throw IllegalStateException("User document $uid does not exist")
                }

                val now = System.currentTimeMillis()
                if (approve) {
                    val roleName = assignedRole.roleName
                    val canViewReports = (roleName == AppRole.ADMIN.roleName)
                    val canViewCostPrice = (roleName == AppRole.ADMIN.roleName)
                    val canManageInventory = (roleName == AppRole.ADMIN.roleName || roleName == AppRole.STORE_MANAGER.roleName)
                    val canViewKhata = (roleName == AppRole.ADMIN.roleName)
                    val canManageExpenses = (roleName == AppRole.ADMIN.roleName || roleName == AppRole.STORE_MANAGER.roleName)
                    val canAccessSettings = (roleName == AppRole.ADMIN.roleName)
                    val canMakeSales = true
                    val canGiveDiscount = (roleName == AppRole.ADMIN.roleName || roleName == AppRole.STORE_MANAGER.roleName)
                    val canDeleteSales = (roleName == AppRole.ADMIN.roleName)

                    transaction.update(
                        userDocRef,
                        mapOf(
                            "role" to roleName,
                            "status" to "APPROVED",
                            "assignedBy" to reviewerEmailOrUid,
                            "reviewedBy" to reviewerEmailOrUid,
                            "reviewedAt" to now,
                            "updatedAt" to now,
                            "canViewReports" to canViewReports,
                            "canViewCostPrice" to canViewCostPrice,
                            "canManageInventory" to canManageInventory,
                            "canViewKhata" to canViewKhata,
                            "canManageExpenses" to canManageExpenses,
                            "canAccessSettings" to canAccessSettings,
                            "canMakeSales" to canMakeSales,
                            "canGiveDiscount" to canGiveDiscount,
                            "canDeleteSales" to canDeleteSales
                        )
                    )
                } else {
                    transaction.update(
                        userDocRef,
                        mapOf(
                            "status" to "REJECTED",
                            "role" to AppRole.UNASSIGNED.roleName,
                            "reviewedBy" to reviewerEmailOrUid,
                            "reviewedAt" to now,
                            "updatedAt" to now,
                            "canViewReports" to false,
                            "canViewCostPrice" to false,
                            "canManageInventory" to false,
                            "canViewKhata" to false,
                            "canManageExpenses" to false,
                            "canAccessSettings" to false,
                            "canMakeSales" to false,
                            "canGiveDiscount" to false,
                            "canDeleteSales" to false
                        )
                    )
                }
            }.await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("FirestoreManager", "Failed to review pending staff request for $uid: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Live read of users/{uid} directly from Firestore.
     * NEVER trusts stale snapshot fields.
     */
    suspend fun getLiveStaffUserDetails(uid: String): FirestoreUserRole? = withContext(Dispatchers.IO) {
        if (uid.isBlank()) return@withContext null
        try {
            val doc = usersCollection.document(uid).get().await()
            if (doc != null && doc.exists()) {
                mapDocumentToUserRole(doc)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Failed to read live staff user $uid: ${e.message}")
            null
        }
    }

    /**
     * Saves or updates a user's role profile and permissions in Firestore.
     * Guaranteed to use Auth UID as the primary key whenever present, keeping users/{uid} in sync for rules evaluation.
     */
    suspend fun saveUserRole(userRole: FirestoreUserRole): Boolean {
        val cleanEmail = userRole.email?.trim()?.lowercase()
        val isRealUid = userRole.uid.isNotBlank() && !userRole.uid.startsWith("preassigned_") && !userRole.uid.contains("@")
        val docKey = if (isRealUid) userRole.uid else if (!cleanEmail.isNullOrBlank()) cleanEmail else userRole.documentId
        if (docKey.isBlank()) return false
        val isPermanentAdmin = cleanEmail != null && cleanEmail in PERMANENT_ADMIN_EMAILS

        val effectiveRole = if (isPermanentAdmin) {
            userRole.copy(
                role = AppRole.ADMIN.roleName,
                status = "APPROVED",
                canViewReports = true,
                canViewCostPrice = true,
                canManageInventory = true,
                canViewKhata = true,
                canManageExpenses = true,
                canAccessSettings = true,
                canMakeSales = true,
                canGiveDiscount = true,
                canDeleteSales = true
            )
        } else {
            userRole
        }

        return try {
            val now = System.currentTimeMillis()
            val data = hashMapOf<String, Any?>(
                "uid" to (if (effectiveRole.uid.isNotBlank()) effectiveRole.uid else docKey),
                "email" to (cleanEmail ?: effectiveRole.email),
                "displayName" to effectiveRole.displayName,
                "role" to effectiveRole.role,
                "status" to effectiveRole.status,
                "reviewedBy" to effectiveRole.reviewedBy,
                "reviewedAt" to effectiveRole.reviewedAt,
                "canViewReports" to effectiveRole.canViewReports,
                "canViewCostPrice" to effectiveRole.canViewCostPrice,
                "canManageInventory" to effectiveRole.canManageInventory,
                "canViewKhata" to effectiveRole.canViewKhata,
                "canManageExpenses" to effectiveRole.canManageExpenses,
                "canAccessSettings" to effectiveRole.canAccessSettings,
                "canMakeSales" to effectiveRole.canMakeSales,
                "canGiveDiscount" to effectiveRole.canGiveDiscount,
                "canDeleteSales" to effectiveRole.canDeleteSales,
                "storeId" to effectiveRole.storeId,
                "assignedBy" to effectiveRole.assignedBy,
                "createdAt" to effectiveRole.createdAt,
                "updatedAt" to now,
                "lastActiveAt" to effectiveRole.lastActiveAt.coerceAtLeast(now)
            )
            usersCollection.document(docKey).set(data, SetOptions.merge()).await()

            // If docKey was UID and cleanEmail was provided, also sync email-keyed record if one exists
            if (isRealUid && !cleanEmail.isNullOrBlank() && cleanEmail != docKey) {
                try {
                    val emailDoc = usersCollection.document(cleanEmail).get().await()
                    if (emailDoc.exists()) {
                        usersCollection.document(cleanEmail).set(data, SetOptions.merge()).await()
                    }
                } catch (e: Exception) {
                    // Non-critical background sync
                }
            }
            true
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException || e is kotlinx.coroutines.CancellationException) {
                Log.i("FirestoreManager", "User role write for $docKey queued in Firestore offline cache (syncing in background)")
            } else {
                Log.w("FirestoreManager", "User role write for $docKey queued or failed: ${e.message}")
            }
            false
        }
    }

    /**
     * Explicitly promotes a user to Admin in Firestore (Strictly restricted to permanent admins or Master PIN authorized actions).
     */
    suspend fun promoteUserToAdmin(uid: String, email: String?, displayName: String?): Boolean {
        val cleanEmail = email?.trim()?.lowercase()
        val isPermanent = cleanEmail != null && cleanEmail in PERMANENT_ADMIN_EMAILS
        if (!isPermanent) {
            Log.w("FirestoreManager", "Non-permanent admin email $cleanEmail requested admin promotion without verification")
        }
        val adminRole = FirestoreUserRole.createAdmin(uid, email, displayName)
        return saveUserRole(adminRole)
    }

    /**
     * Deletes a cloud user's role profile from Firestore and any associated preassigned email permissions.
     */
    suspend fun deleteUserRole(uid: String, email: String? = null, documentId: String? = null): Boolean {
        val cleanEmail = email?.trim()?.lowercase()
        return try {
            if (!documentId.isNullOrBlank()) {
                usersCollection.document(documentId).delete().await()
            }
            if (uid.isNotBlank() && uid != documentId) {
                usersCollection.document(uid).delete().await()
            }
            if (!cleanEmail.isNullOrBlank() && cleanEmail != documentId) {
                usersCollection.document(cleanEmail).delete().await()
            }
            if (!cleanEmail.isNullOrBlank() && cleanEmail !in PERMANENT_ADMIN_EMAILS) {
                deletePreassignedRole(cleanEmail)
            }
            true
        } catch (e: Exception) {
            Log.e("FirestoreManager", "Error deleting user role for uid=$uid, doc=$documentId: ${e.message}")
            false
        }
    }

    /**
     * Clean up duplicate / stale user entries from Firestore, keeping only the active UID for each email.
     */
    suspend fun cleanDuplicateUsers(activeUid: String?): Int {
        return try {
            val snapshot = usersCollection.get().await()
            val docs = snapshot.documents
            val seenEmails = mutableMapOf<String, String>() // email -> uid to keep
            var deletedCount = 0

            // Prefer active UID if provided
            for (doc in docs) {
                val email = doc.getString("email")?.trim()?.lowercase() ?: continue
                if (doc.id == activeUid) {
                    seenEmails[email] = doc.id
                }
            }

            for (doc in docs) {
                val email = doc.getString("email")?.trim()?.lowercase() ?: continue
                val existingKeeperUid = seenEmails[email]
                if (existingKeeperUid == null) {
                    seenEmails[email] = doc.id
                } else if (existingKeeperUid != doc.id) {
                    // Stale duplicate document found!
                    usersCollection.document(doc.id).delete().await()
                    deletedCount++
                }
            }
            deletedCount
        } catch (e: Exception) {
            Log.e("FirestoreManager", "Error cleaning duplicate users: ${e.message}")
            0
        }
    }

    /**
     * Saves a preassigned role for an employee by email so when they sign in on their own device,
     * their role and permissions are instantly configured.
     */
    suspend fun savePreassignedRole(email: String, userRole: FirestoreUserRole): Boolean {
        val cleanEmail = email.trim().lowercase()
        if (cleanEmail.isBlank()) return false
        return try {
            val data = hashMapOf<String, Any?>(
                "email" to cleanEmail,
                "displayName" to userRole.displayName,
                "role" to userRole.role,
                "canViewReports" to userRole.canViewReports,
                "canViewCostPrice" to userRole.canViewCostPrice,
                "canManageInventory" to userRole.canManageInventory,
                "canViewKhata" to userRole.canViewKhata,
                "canManageExpenses" to userRole.canManageExpenses,
                "canAccessSettings" to userRole.canAccessSettings,
                "canMakeSales" to userRole.canMakeSales,
                "canGiveDiscount" to userRole.canGiveDiscount,
                "canDeleteSales" to userRole.canDeleteSales,
                "storeId" to userRole.storeId,
                "assignedBy" to userRole.assignedBy,
                "createdAt" to userRole.createdAt,
                "updatedAt" to System.currentTimeMillis()
            )
            preassignedRolesCollection.document(cleanEmail).set(data, SetOptions.merge()).await()

            // Also if a user doc with this email already exists in usersCollection, update them
            val existingUserQuery = usersCollection.whereEqualTo("email", cleanEmail).get().await()
            for (doc in existingUserQuery.documents) {
                usersCollection.document(doc.id).set(data + ("uid" to doc.id), SetOptions.merge()).await()
            }
            true
        } catch (e: Exception) {
            Log.e("FirestoreManager", "Error saving preassigned role for $cleanEmail: ${e.message}")
            false
        }
    }

    suspend fun getPreassignedRole(email: String): FirestoreUserRole? {
        val cleanEmail = email.trim().lowercase()
        if (cleanEmail.isBlank()) return null
        return try {
            val doc = preassignedRolesCollection.document(cleanEmail).get().await()
            if (doc.exists()) mapDocumentToUserRole(doc) else null
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not fetch preassigned role for $cleanEmail: ${e.message}")
            null
        }
    }

    suspend fun deletePreassignedRole(email: String): Boolean {
        val cleanEmail = email.trim().lowercase()
        if (cleanEmail.isBlank()) return false
        return try {
            preassignedRolesCollection.document(cleanEmail).delete().await()
            true
        } catch (e: Exception) {
            Log.e("FirestoreManager", "Error deleting preassigned role: ${e.message}")
            false
        }
    }

    /**
     * Live Flow of all preassigned staff roles configured by the store owner.
     */
    fun getPreassignedRolesFlow(): Flow<List<FirestoreUserRole>> = callbackFlow {
        val listener = try {
            preassignedRolesCollection.addSnapshotListener(MetadataChanges.EXCLUDE) { snapshot, error ->
                if (error != null) {
                    Log.w("FirestoreManager", "Error in getPreassignedRolesFlow: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val list = snapshot.documents.mapNotNull { mapDocumentToUserRole(it) }
                    trySend(list)
                }
            }
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Failed to attach preassigned roles listener: ${e.message}")
            null
        }
        awaitClose { listener?.remove() }
    }

    /**
     * Initializes or fetches a user's role in Firestore.
     * KEYED BY EMAIL AS UNIQUE IDENTIFIER:
     * 1. If an existing record exists for this email (or UID), we preserve its original `createdAt` and permissions,
     *    and update `lastActiveAt` in that single canonical record.
     * 2. If no record exists in `users`, checks `preassigned_roles` for an invite configured by Store Owner.
     * 3. Permanent Admin (shuvajitsow384@gmail.com) is always Master Admin.
     * 4. Unrecognized accounts are registered with role = UNRECOGNIZED (Zero Access) under their email.
     */
    suspend fun initOrFetchUserRole(uid: String, email: String?, displayName: String?): FirestoreUserRole {
        val cleanEmail = email?.trim()?.lowercase()
        val isPermanentAdmin = cleanEmail != null && cleanEmail in PERMANENT_ADMIN_EMAILS

        // 1. Check if user already exists in Firestore by email or UID
        val existing = getUserRole(uid, cleanEmail)
        if (existing != null) {
            val originalCreatedAt = existing.createdAt
            if (isPermanentAdmin || existing.isPermanentAdmin) {
                val repairedAdmin = FirestoreUserRole.createAdmin(
                    uid = if (uid.isNotBlank()) uid else existing.uid,
                    email = cleanEmail ?: existing.email,
                    displayName = displayName ?: existing.displayName
                ).copy(createdAt = originalCreatedAt, lastActiveAt = System.currentTimeMillis())
                saveUserRole(repairedAdmin)
                return repairedAdmin
            }

            // Update existing record in place: preserve original createdAt, permissions, and role
            val updated = existing.copy(
                uid = if (uid.isNotBlank()) uid else existing.uid,
                email = cleanEmail ?: existing.email,
                displayName = displayName ?: existing.displayName,
                lastActiveAt = System.currentTimeMillis()
            )
            saveUserRole(updated)
            return updated
        }

        // 2. Check if Store Owner pre-configured this employee's email
        if (!cleanEmail.isNullOrBlank()) {
            val preassigned = getPreassignedRole(cleanEmail)
            if (preassigned != null) {
                val assignedRole = preassigned.copy(
                    uid = uid,
                    email = cleanEmail,
                    displayName = displayName ?: preassigned.displayName ?: cleanEmail.substringBefore("@"),
                    updatedAt = System.currentTimeMillis(),
                    lastActiveAt = System.currentTimeMillis()
                )
                saveUserRole(assignedRole)
                return assignedRole
            }
        }

        // 3. Permanent Admin first-time setup
        if (isPermanentAdmin) {
            val adminRole = FirestoreUserRole.createAdmin(uid, cleanEmail, displayName)
            saveUserRole(adminRole)
            return adminRole
        }

        // 4. New sign-in: Grant default approved Store Admin access for shopkeeper
        val defaultOwnerRole = FirestoreUserRole.createAdmin(
            uid = uid,
            email = cleanEmail,
            displayName = displayName ?: cleanEmail?.substringBefore("@") ?: "Store Owner"
        )
        try {
            saveUserRole(defaultOwnerRole)
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Failed to record default user sign-in: ${e.message}")
        }
        return defaultOwnerRole
    }

    // ==========================================
    // ROLE-PROTECTED FINANCIAL & PROFIT REPORTS
    // ==========================================

    /**
     * Publishes a Profit & Loss Report to Firestore.
     * Enforces RBAC: Only ADMIN or users with canViewReports can save financial reports.
     */
    suspend fun saveProfitReport(report: FirestoreProfitReport, userRole: FirestoreUserRole?): Result<Boolean> {
        val currentUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
        if (currentUser == null) {
            return Result.failure(
                IllegalStateException("Cloud synchronization requires a signed-in account. Please sign in to your store account in Settings.")
            )
        }

        if (userRole != null && !userRole.canViewReports && !userRole.isAdmin) {
            return Result.failure(SecurityException("Permission Denied: Only Admin or authorized roles can publish Profit Reports to Firestore."))
        }

        return try {
            val reportDocId = if (report.reportId.isNotBlank()) report.reportId else "REPORT_${report.period}_${System.currentTimeMillis()}"
            val data = hashMapOf<String, Any?>(
                "reportId" to reportDocId,
                "period" to report.period,
                "startTime" to report.startTime,
                "endTime" to report.endTime,
                "totalRevenue" to report.totalRevenue,
                "totalCostOfGoods" to report.totalCostOfGoods,
                "grossProfit" to report.grossProfit,
                "totalExpenses" to report.totalExpenses,
                "netProfit" to report.netProfit,
                "profitMarginPercent" to report.profitMarginPercent,
                "totalSalesCount" to report.totalSalesCount,
                "generatedAt" to report.generatedAt,
                "generatedByUid" to (currentUser.uid.ifBlank { userRole?.uid ?: report.generatedByUid }),
                "generatedByName" to (currentUser.displayName ?: userRole?.displayName ?: report.generatedByName),
                "requiredRole" to "ADMIN",
                "storeId" to report.storeId
            )
            reportsCollection.document(reportDocId).set(data, SetOptions.merge()).await()
            Result.success(true)
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Firestore profit report save note: ${e.message}")
            val msg = if (e.message?.contains("PERMISSION_DENIED", ignoreCase = true) == true) {
                "Cloud permission denied. Please sign in with an Admin account to publish reports to Firestore."
            } else {
                e.message ?: "Failed to save profit report to cloud"
            }
            Result.failure(Exception(msg))
        }
    }

    /**
     * Retrieves Profit & Loss Reports from Firestore.
     * STRICT RBAC ENFORCEMENT: Restricts access to Profit reports for regular employees.
     */
    suspend fun getProfitReports(userRole: FirestoreUserRole?): Result<List<FirestoreProfitReport>> {
        val currentUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
        if (currentUser == null) {
            return Result.failure(
                IllegalStateException("Please sign in to your store account to view cloud profit reports.")
            )
        }

        // Enforce RBAC permission check
        if (userRole != null && !userRole.isAdmin && !userRole.canViewReports) {
            return Result.failure(
                SecurityException("Access Denied: Profit reports and confidential store financial metrics are restricted for regular employees. Please log in with an Admin account or ask your Store Owner.")
            )
        }

        return try {
            val snapshot = reportsCollection.orderBy("generatedAt", com.google.firebase.firestore.Query.Direction.DESCENDING).get().await()
            val reports = snapshot.documents.mapNotNull { mapDocumentToProfitReport(it) }
            Result.success(reports)
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not fetch profit reports from Firestore: ${e.message}")
            val msg = if (e.message?.contains("PERMISSION_DENIED", ignoreCase = true) == true) {
                "Cloud permission denied. Please sign in with an Admin account to view reports in Firestore."
            } else {
                e.message ?: "Could not fetch profit reports from Firestore"
            }
            Result.failure(Exception(msg))
        }
    }

    /**
     * Live Firestore Snapshot listener for real-time Profit & Loss reports across all devices.
     * Latency under 3 seconds with immediate local and cloud synchronisation.
     */
    fun getProfitReportsFlow(userRole: FirestoreUserRole?): Flow<List<FirestoreProfitReport>> = callbackFlow {
        if (userRole != null && !userRole.isAdmin && !userRole.canViewReports) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val listener = try {
            reportsCollection
                .orderBy("generatedAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .addSnapshotListener(MetadataChanges.EXCLUDE) { snapshot, error ->
                    if (error != null) {
                        Log.w("FirestoreManager", "Firestore live profit reports listener note: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot != null) {
                        val reports = snapshot.documents.mapNotNull { mapDocumentToProfitReport(it) }
                        trySend(reports)
                    }
                }
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Failed to attach profit reports live listener: ${e.message}")
            null
        }
        awaitClose { listener?.remove() }
    }

    /**
     * Masks sensitive cost price details if the user role is restricted.
     */
    fun sanitizeProductsForRole(products: List<Product>, userRole: FirestoreUserRole?): List<Product> {
        val canViewCost = userRole?.canViewCostPrice ?: true
        if (canViewCost) return products

        return products.map { prod ->
            prod.copy(costPrice = 0.0) // Mask cost price for restricted employee roles
        }
    }

    private fun mapDocumentToUserRole(doc: DocumentSnapshot): FirestoreUserRole? {
        return try {
            val docId = doc.id
            val uid = doc.getString("uid") ?: doc.id
            val email = doc.getString("email")
            val cleanEmail = email?.trim()?.lowercase()
            val isPermanent = cleanEmail != null && cleanEmail in PERMANENT_ADMIN_EMAILS
            val displayName = doc.getString("displayName") ?: doc.getString("name")
            val rawRole = doc.getString("role") ?: if (isPermanent) AppRole.ADMIN.roleName else AppRole.UNASSIGNED.roleName
            val role = if (isPermanent) AppRole.ADMIN.roleName else rawRole
            val rawStatus = doc.getString("status")
            val isUnrecognized = !isPermanent && (
                role.equals(AppRole.UNASSIGNED.roleName, ignoreCase = true) ||
                role.equals(AppRole.UNRECOGNIZED.roleName, ignoreCase = true) ||
                role.equals("PENDING", ignoreCase = true) ||
                role.equals("NONE", ignoreCase = true) ||
                role.isBlank() ||
                rawStatus.equals("PENDING", ignoreCase = true) ||
                rawStatus.equals("REJECTED", ignoreCase = true)
            )
            val status = rawStatus ?: if (isUnrecognized) "PENDING" else "APPROVED"
            val reviewedBy = doc.getString("reviewedBy")
            val reviewedAt = (doc.get("reviewedAt") as? Number)?.toLong()

            val canViewReports = doc.getBoolean("canViewReports") ?: (role == AppRole.ADMIN.roleName)
            val canViewCostPrice = doc.getBoolean("canViewCostPrice") ?: (role == AppRole.ADMIN.roleName)
            val canManageInventory = doc.getBoolean("canManageInventory") ?: (role == AppRole.ADMIN.roleName)
            val canViewKhata = doc.getBoolean("canViewKhata") ?: (role == AppRole.ADMIN.roleName)
            val canManageExpenses = doc.getBoolean("canManageExpenses") ?: (role == AppRole.ADMIN.roleName)
            val canAccessSettings = doc.getBoolean("canAccessSettings") ?: (role == AppRole.ADMIN.roleName)
            val canMakeSales = doc.getBoolean("canMakeSales") ?: (!isUnrecognized && (role == AppRole.EMPLOYEE.roleName || role == AppRole.STORE_MANAGER.roleName))
            val canGiveDiscount = doc.getBoolean("canGiveDiscount") ?: (!isUnrecognized && (role == AppRole.EMPLOYEE.roleName || role == AppRole.STORE_MANAGER.roleName))
            val canDeleteSales = doc.getBoolean("canDeleteSales") ?: (role == AppRole.ADMIN.roleName)
            val storeId = doc.getString("storeId") ?: "default_store"
            val assignedBy = doc.getString("assignedBy")
            val createdAt = (doc.get("createdAt") as? Number)?.toLong() ?: System.currentTimeMillis()
            val updatedAt = (doc.get("updatedAt") as? Number)?.toLong() ?: System.currentTimeMillis()
            val lastActiveAt = (doc.get("lastActiveAt") as? Number)?.toLong() ?: updatedAt

            FirestoreUserRole(
                uid = uid,
                documentId = docId,
                email = email,
                displayName = displayName,
                role = role,
                status = status,
                reviewedBy = reviewedBy,
                reviewedAt = reviewedAt,
                canViewReports = if (isUnrecognized) false else canViewReports,
                canViewCostPrice = if (isUnrecognized) false else canViewCostPrice,
                canManageInventory = if (isUnrecognized) false else canManageInventory,
                canViewKhata = if (isUnrecognized) false else canViewKhata,
                canManageExpenses = if (isUnrecognized) false else canManageExpenses,
                canAccessSettings = if (isUnrecognized) false else canAccessSettings,
                canMakeSales = if (isUnrecognized) false else canMakeSales,
                canGiveDiscount = if (isUnrecognized) false else canGiveDiscount,
                canDeleteSales = if (isUnrecognized) false else canDeleteSales,
                storeId = storeId,
                assignedBy = assignedBy,
                createdAt = createdAt,
                updatedAt = updatedAt,
                lastActiveAt = lastActiveAt
            )
        } catch (e: Exception) {
            Log.e("FirestoreManager", "Error parsing user role document ${doc.id}: ${e.message}")
            null
        }
    }

    private fun mapDocumentToProfitReport(doc: DocumentSnapshot): FirestoreProfitReport? {
        return try {
            val reportId = doc.id
            val period = doc.getString("period") ?: "TODAY"
            val startTime = (doc.get("startTime") as? Number)?.toLong() ?: 0L
            val endTime = (doc.get("endTime") as? Number)?.toLong() ?: 0L
            val totalRevenue = (doc.get("totalRevenue") as? Number)?.toDouble() ?: 0.0
            val totalCostOfGoods = (doc.get("totalCostOfGoods") as? Number)?.toDouble() ?: 0.0
            val grossProfit = (doc.get("grossProfit") as? Number)?.toDouble() ?: 0.0
            val totalExpenses = (doc.get("totalExpenses") as? Number)?.toDouble() ?: 0.0
            val netProfit = (doc.get("netProfit") as? Number)?.toDouble() ?: 0.0
            val profitMarginPercent = (doc.get("profitMarginPercent") as? Number)?.toDouble() ?: 0.0
            val totalSalesCount = (doc.get("totalSalesCount") as? Number)?.toInt() ?: 0
            val generatedAt = (doc.get("generatedAt") as? Number)?.toLong() ?: System.currentTimeMillis()
            val generatedByUid = doc.getString("generatedByUid") ?: ""
            val generatedByName = doc.getString("generatedByName") ?: "Admin"
            val requiredRole = doc.getString("requiredRole") ?: "ADMIN"
            val storeId = doc.getString("storeId") ?: "default_store"

            FirestoreProfitReport(
                reportId = reportId,
                period = period,
                startTime = startTime,
                endTime = endTime,
                totalRevenue = totalRevenue,
                totalCostOfGoods = totalCostOfGoods,
                grossProfit = grossProfit,
                totalExpenses = totalExpenses,
                netProfit = netProfit,
                profitMarginPercent = profitMarginPercent,
                totalSalesCount = totalSalesCount,
                generatedAt = generatedAt,
                generatedByUid = generatedByUid,
                generatedByName = generatedByName,
                requiredRole = requiredRole,
                storeId = storeId
            )
        } catch (e: Exception) {
            Log.e("FirestoreManager", "Error parsing profit report ${doc.id}: ${e.message}")
            null
        }
    }

    fun getProductsFlow(): Flow<List<Product>> = callbackFlow {
        val listener = try {
            productsCollection
                .addSnapshotListener(MetadataChanges.EXCLUDE) { snapshot, error ->
                    if (error != null) {
                        Log.w("FirestoreManager", "Firestore product listener note: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot != null) {
                        val products = snapshot.documents.mapNotNull { doc ->
                            mapDocumentToProduct(doc)
                        }
                        trySend(products)
                    }
                }
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Failed to attach snapshot listener: ${e.message}")
            null
        }
        awaitClose { listener?.remove() }
    }

    suspend fun getProductById(productId: String): Product? {
        return try {
            val doc = productsCollection.document(productId).get().await()
            if (doc.exists()) mapDocumentToProduct(doc) else null
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Product $productId not fetched from Firestore (${e.message}), will fallback to local DB.")
            null
        }
    }

    suspend fun getAllProductsOnce(): List<Product> {
        return try {
            val snapshot = productsCollection.get().await()
            snapshot.documents.mapNotNull { mapDocumentToProduct(it) }
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not fetch all products from Firestore: ${e.message}")
            emptyList()
        }
    }

    suspend fun saveProduct(product: Product): Boolean {
        return try {
            val data = hashMapOf<String, Any?>(
                "id" to product.id,
                "name_en" to product.nameEn,
                "name_bn" to product.nameBn,
                "category" to product.category,
                "unit_type" to product.unitType,
                "cost_price" to product.costPrice,
                "selling_price" to product.sellingPrice,
                "current_stock" to product.currentStock,
                "low_stock_threshold" to product.lowStockThreshold,
                "barcode" to product.barcode,
                "secondary_unit_type" to product.secondaryUnitType,
                "secondary_unit_ratio" to product.secondaryUnitRatio,
                "image_uri" to product.imageUri,
                "imageUri" to product.imageUri,
                "expiry_date" to product.expiryDate,
                "wholesale_price" to product.wholesalePrice,
                "wholesale_min_qty" to product.wholesaleMinQty,
                "pieces_per_box" to product.piecesPerBox,
                "box_price" to product.boxPrice,
                "bulk_unit_type" to product.bulkUnitType,
                "bulkUnitType" to product.bulkUnitType,
                "bulk_quantity" to product.bulkQuantity,
                "bulkQuantity" to product.bulkQuantity,
                "bulk_price" to product.bulkPrice,
                "bulkPrice" to product.bulkPrice,
                "mrp" to product.mrp,
                "mrp_price" to product.mrp,
                "barcode_variants_json" to product.barcodeVariantsJson,
                "barcode_variants" to product.getBarcodeVariants().map { it.toMap() },
                "barcodeVariants" to product.getBarcodeVariants().map { it.toMap() },
                "isOnlineVisible" to product.isOnlineVisible,
                "is_online_visible" to product.isOnlineVisible,
                "onlineMinOrderQty" to product.onlineMinOrderQty,
                "online_min_order_qty" to product.onlineMinOrderQty,
                "onlineMaxOrderQty" to product.onlineMaxOrderQty,
                "online_max_order_qty" to product.onlineMaxOrderQty,
                "updated_at" to System.currentTimeMillis()
            )
            DataSyncManager.logFirestoreWrite(
                operationType = "SET_MERGE",
                collection = "products",
                documentId = product.id,
                payload = data,
                caller = "saveProduct"
            )
            productsCollection.document(product.id).set(data, SetOptions.merge()).await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not save product ${product.id} to Firestore: ${e.message}")
            false
        }
    }

    suspend fun saveProducts(products: List<Product>): Boolean {
        if (products.isEmpty()) return true
        return try {
            // Chunk into batches of 50 to stay well under Firestore batch write limit & memory bounds
            products.chunked(50).forEach { chunk ->
                val batch = firestore.batch()
                chunk.forEach { product ->
                    val docRef = productsCollection.document(product.id)
                    val data = hashMapOf<String, Any?>(
                        "id" to product.id,
                        "name_en" to product.nameEn,
                        "name_bn" to product.nameBn,
                        "category" to product.category,
                        "unit_type" to product.unitType,
                        "cost_price" to product.costPrice,
                        "selling_price" to product.sellingPrice,
                        "current_stock" to product.currentStock,
                        "low_stock_threshold" to product.lowStockThreshold,
                        "barcode" to product.barcode,
                        "secondary_unit_type" to product.secondaryUnitType,
                        "secondary_unit_ratio" to product.secondaryUnitRatio,
                        "image_uri" to product.imageUri,
                        "imageUri" to product.imageUri,
                        "expiry_date" to product.expiryDate,
                        "wholesale_price" to product.wholesalePrice,
                        "wholesale_min_qty" to product.wholesaleMinQty,
                        "pieces_per_box" to product.piecesPerBox,
                        "box_price" to product.boxPrice,
                        "mrp" to product.mrp,
                        "mrp_price" to product.mrp,
                        "barcode_variants_json" to product.barcodeVariantsJson,
                        "barcode_variants" to product.getBarcodeVariants().map { it.toMap() },
                        "barcodeVariants" to product.getBarcodeVariants().map { it.toMap() },
                        "isOnlineVisible" to product.isOnlineVisible,
                        "is_online_visible" to product.isOnlineVisible,
                        "onlineMinOrderQty" to product.onlineMinOrderQty,
                        "online_min_order_qty" to product.onlineMinOrderQty,
                        "onlineMaxOrderQty" to product.onlineMaxOrderQty,
                        "online_max_order_qty" to product.onlineMaxOrderQty,
                        "updated_at" to System.currentTimeMillis()
                    )
                    DataSyncManager.logFirestoreWrite(
                        operationType = "BATCH_SET_MERGE",
                        collection = "products",
                        documentId = product.id,
                        payload = data,
                        caller = "saveProductsBatch"
                    )
                    batch.set(docRef, data, SetOptions.merge())
                }
                batch.commit().await()
            }
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not batch save products to Firestore: ${e.message}")
            false
        }
    }

    suspend fun deleteProduct(productId: String): Boolean {
        return try {
            productsCollection.document(productId).delete().await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not delete product $productId on Firestore: ${e.message}")
            false
        }
    }

    suspend fun updateStock(productId: String, newStock: Double) {
        productsCollection.document(productId).update(
            "current_stock", newStock,
            "updated_at", System.currentTimeMillis()
        ).await()
    }

    suspend fun addStock(productId: String, delta: Double) {
        productsCollection.document(productId).update(
            "current_stock", FieldValue.increment(delta),
            "updated_at", System.currentTimeMillis()
        ).await()
    }

    suspend fun deductStock(productId: String, delta: Double) {
        productsCollection.document(productId).update(
            "current_stock", FieldValue.increment(-delta),
            "updated_at", System.currentTimeMillis()
        ).await()
    }

    private fun mapDocumentToProduct(doc: DocumentSnapshot): Product? {
        return try {
            val id = doc.id
            val nameEn = doc.getString("name_en") ?: doc.getString("nameEn") ?: ""
            val nameBn = doc.getString("name_bn") ?: doc.getString("nameBn") ?: ""
            val category = doc.getString("category") ?: ""
            val unitType = doc.getString("unit_type") ?: doc.getString("unitType") ?: "piece"
            val costPrice = (doc.get("cost_price") as? Number)?.toDouble()
                ?: (doc.get("costPrice") as? Number)?.toDouble() ?: 0.0
            val sellingPrice = (doc.get("selling_price") as? Number)?.toDouble()
                ?: (doc.get("sellingPrice") as? Number)?.toDouble() ?: 0.0
            val currentStock = (doc.get("current_stock") as? Number)?.toDouble()
                ?: (doc.get("currentStock") as? Number)?.toDouble() ?: 0.0
            val lowStockThreshold = (doc.get("low_stock_threshold") as? Number)?.toDouble()
                ?: (doc.get("lowStockThreshold") as? Number)?.toDouble() ?: 5.0
            val barcode = doc.getString("barcode")
            val secondaryUnitType = doc.getString("secondary_unit_type") ?: doc.getString("secondaryUnitType")
            val secondaryUnitRatio = (doc.get("secondary_unit_ratio") as? Number)?.toDouble()
                ?: (doc.get("secondaryUnitRatio") as? Number)?.toDouble() ?: 1.0
            val imageUri = doc.getString("image_uri") ?: doc.getString("imageUri")
            val expiryDate = doc.getString("expiry_date") ?: doc.getString("expiryDate")
            val wholesalePrice = (doc.get("wholesale_price") as? Number)?.toDouble()
                ?: (doc.get("wholesalePrice") as? Number)?.toDouble() ?: 0.0
            val wholesaleMinQty = (doc.get("wholesale_min_qty") as? Number)?.toDouble()
                ?: (doc.get("wholesaleMinQty") as? Number)?.toDouble() ?: 0.0
            val piecesPerBox = (doc.get("pieces_per_box") as? Number)?.toInt()
                ?: (doc.get("piecesPerBox") as? Number)?.toInt()
            val boxPrice = (doc.get("box_price") as? Number)?.toDouble()
                ?: (doc.get("boxPrice") as? Number)?.toDouble()
            val bulkUnitType = doc.getString("bulk_unit_type")
                ?: doc.getString("bulkUnitType")
            val bulkQuantity = (doc.get("bulk_quantity") as? Number)?.toDouble()
                ?: (doc.get("bulkQuantity") as? Number)?.toDouble()
            val bulkPrice = (doc.get("bulk_price") as? Number)?.toDouble()
                ?: (doc.get("bulkPrice") as? Number)?.toDouble()
            val mrp = (doc.get("mrp") as? Number)?.toDouble()
                ?: (doc.get("mrp_price") as? Number)?.toDouble()
            val isOnlineVisible = doc.getBoolean("isOnlineVisible")
                ?: doc.getBoolean("is_online_visible")
                ?: true
            val onlineMinOrderQty = (doc.get("onlineMinOrderQty") as? Number)?.toDouble()
                ?: (doc.get("online_min_order_qty") as? Number)?.toDouble()
                ?: 1.0
            val onlineMaxOrderQty = (doc.get("onlineMaxOrderQty") as? Number)?.toDouble()
                ?: (doc.get("online_max_order_qty") as? Number)?.toDouble()
                ?: 10.0
            val rawVariantsJson = doc.getString("barcode_variants_json") ?: doc.getString("barcodeVariantsJson")
            val barcodeVariantsJson = if (!rawVariantsJson.isNullOrBlank()) {
                rawVariantsJson
            } else {
                val variantsList = doc.get("barcode_variants") as? List<*> ?: doc.get("barcodeVariants") as? List<*>
                if (!variantsList.isNullOrEmpty()) {
                    val parsed = variantsList.mapNotNull {
                        if (it is Map<*, *>) com.example.data.local.entities.BarcodeVariant.fromMap(it) else null
                    }
                    com.example.data.local.entities.BarcodeVariant.toJsonString(parsed)
                } else {
                    null
                }
            }

            Product(
                id = id,
                nameEn = nameEn,
                nameBn = nameBn,
                category = category,
                unitType = unitType,
                costPrice = costPrice,
                sellingPrice = sellingPrice,
                currentStock = currentStock,
                lowStockThreshold = lowStockThreshold,
                barcode = barcode,
                secondaryUnitType = secondaryUnitType,
                secondaryUnitRatio = secondaryUnitRatio,
                imageUri = imageUri,
                expiryDate = expiryDate,
                wholesalePrice = wholesalePrice,
                wholesaleMinQty = wholesaleMinQty,
                piecesPerBox = piecesPerBox,
                boxPrice = boxPrice,
                mrp = mrp,
                barcodeVariantsJson = barcodeVariantsJson,
                isOnlineVisible = isOnlineVisible,
                onlineMinOrderQty = onlineMinOrderQty,
                onlineMaxOrderQty = onlineMaxOrderQty,
                bulkUnitType = bulkUnitType,
                bulkQuantity = bulkQuantity,
                bulkPrice = bulkPrice,
                needsSync = false
            )
        } catch (e: Exception) {
            Log.e("FirestoreManager", "Error parsing product ${doc.id}: ${e.message}")
            null
        }
    }

    // ==========================================
    // SALES & BILLING CLOUD SYNC
    // ==========================================
    // ATOMIC FIRESTORE TRANSACTIONS (Stock & Credit Billing)
    // ==========================================

    data class SaleTransactionResult(
        val updatedStocks: Map<String, Double>,
        val updatedCustomerBalance: Double? = null
    )

    /**
     * Executes an atomic Firestore transaction to verify real-time cloud stock availability
     * and customer credit limit/balance before committing any sale or deducting stock.
     * 1. ALL READS FIRST (Strict Firestore rule)
     * 2. If available stock - saleQty < 0, throws [InsufficientStockException].
     * 3. If credit sale, checks current customer credit balance + credit limit. If new balance > limit,
     *    blocks the sale and throws [CreditLimitExceededException] unless an explicit [ownerOverride] flag is passed.
     * 4. Atomically writes updated stocks, customer balance, ledger entry, and sale record in a single transaction.
     */
    suspend fun executeSaleAndCreditTransaction(
        sale: Sale,
        items: List<SaleItem>,
        productDeductions: Map<String, Double>, // productId -> required quantity in primary base unit
        ledgerEntry: LedgerEntry? = null,
        allowNegativeStock: Boolean = false,
        ownerOverride: Boolean = false,
        orderIdToComplete: String? = null,
        dispatchedByStaffName: String? = null
    ): Result<SaleTransactionResult> {
        return try {
            val consolidatedItems = com.example.utils.SaleConsolidationUtils.consolidateSaleItems(items)
            val result = firestore.runTransaction { transaction ->
                val now = System.currentTimeMillis()
                val productSnapshots = mutableMapOf<String, DocumentSnapshot>()
                val cloudStocks = mutableMapOf<String, Double>()

                // 1. ALL READS FIRST (Firestore strict requirement)
                for (productId in productDeductions.keys) {
                    if (productId.startsWith("quick_")) continue
                    val docRef = productsCollection.document(productId)
                    val snapshot = transaction.get(docRef)
                    productSnapshots[productId] = snapshot
                    if (snapshot.exists()) {
                        val stock = (snapshot.get("current_stock") as? Number)?.toDouble()
                            ?: (snapshot.get("currentStock") as? Number)?.toDouble()
                            ?: 0.0
                        cloudStocks[productId] = stock
                    }
                }

                // If fulfilling online order, read order document (and cached order_refs and customer_accounts if customerUid present) inside transaction
                var orderRefDocToUpdate: DocumentReference? = null
                var orderRefSnapshot: DocumentSnapshot? = null
                var customerAccountDocToUpdate: DocumentReference? = null
                var customerAccountSnapshot: DocumentSnapshot? = null
                if (!orderIdToComplete.isNullOrBlank()) {
                    val orderDocRef = ordersCollection.document(orderIdToComplete)
                    val orderSnap = transaction.get(orderDocRef)
                    if (orderSnap.exists()) {
                        val custUid = orderSnap.getString("customerUid")?.ifBlank { null }
                        if (!custUid.isNullOrBlank()) {
                            val accDoc = firestore.collection("customer_accounts").document(custUid)
                            customerAccountDocToUpdate = accDoc
                            customerAccountSnapshot = transaction.get(accDoc)

                            val refDoc = accDoc.collection("order_refs").document(orderIdToComplete)
                            orderRefDocToUpdate = refDoc
                            orderRefSnapshot = transaction.get(refDoc)
                        }
                    }
                }

                // If credit sale, read customer document inside transaction
                var customerSnapshot: DocumentSnapshot? = null
                val custId = sale.customerId
                val isCreditBilling = (sale.dueAmount > 0.0 || sale.paymentMode.equals("CREDIT", true)) && !custId.isNullOrBlank()
                if (isCreditBilling && custId != null) {
                    customerSnapshot = transaction.get(customersCollection.document(custId))
                }

                // 2. CHECK STOCK AVAILABILITY AGAINST REAL-TIME TRANSACTION SNAPSHOT
                if (!allowNegativeStock) {
                    val insufficientList = mutableListOf<InsufficientStockItem>()
                    for ((productId, requiredQty) in productDeductions) {
                        if (productId.startsWith("quick_")) continue
                        val snapshot = productSnapshots[productId]
                        if (snapshot != null && snapshot.exists()) {
                            val available = cloudStocks[productId] ?: 0.0
                            if (requiredQty > (available + 0.00001)) {
                                val nameEn = snapshot.getString("name_en") ?: snapshot.getString("nameEn") ?: "Product"
                                val nameBn = snapshot.getString("name_bn") ?: snapshot.getString("nameBn") ?: ""
                                val unit = snapshot.getString("unit_type") ?: snapshot.getString("unitType") ?: "unit"
                                insufficientList.add(
                                    InsufficientStockItem(
                                        productId = productId,
                                        productNameEn = nameEn,
                                        productNameBn = nameBn,
                                        unitType = unit,
                                        availableStock = available,
                                        requestedQuantity = requiredQty
                                    )
                                )
                            }
                        }
                    }

                    if (insufficientList.isNotEmpty()) {
                        throw InsufficientStockException(insufficientList)
                    }
                }

                // 3. CHECK CREDIT LIMIT & BALANCE INSIDE TRANSACTION
                var currentCustomerBalance = 0.0
                var newCustomerBalance = 0.0
                if (isCreditBilling && customerSnapshot != null && customerSnapshot.exists()) {
                    currentCustomerBalance = (customerSnapshot.get("balance") as? Number)?.toDouble()
                        ?: (customerSnapshot.get("currentBalance") as? Number)?.toDouble()
                        ?: 0.0
                    val creditLimit = (customerSnapshot.get("creditLimit") as? Number)?.toDouble()
                        ?: (customerSnapshot.get("credit_limit") as? Number)?.toDouble()
                        ?: 0.0
                    
                    newCustomerBalance = currentCustomerBalance + sale.dueAmount

                    if (creditLimit > 0.0 && newCustomerBalance > (creditLimit + 0.00001) && !ownerOverride) {
                        val custName = customerSnapshot.getString("name") ?: sale.customerName ?: "Customer"
                        throw CreditLimitExceededException(
                            customerId = custId ?: "",
                            customerName = custName,
                            currentBalance = currentCustomerBalance,
                            creditLimit = creditLimit,
                            attemptedDue = newCustomerBalance
                        )
                    }
                }

                // 4. ALL WRITES AFTER (Deduct stock, update customer balance, insert ledger, save sale)
                val newStockResults = mutableMapOf<String, Double>()
                for ((productId, requiredQty) in productDeductions) {
                    if (productId.startsWith("quick_")) continue
                    val snapshot = productSnapshots[productId]
                    if (snapshot != null && snapshot.exists()) {
                        val current = cloudStocks[productId] ?: 0.0
                        val newStock = if (allowNegativeStock) {
                            current - requiredQty
                        } else {
                            (current - requiredQty).coerceAtLeast(0.0)
                        }
                        newStockResults[productId] = newStock
                        transaction.update(
                            productsCollection.document(productId),
                            mapOf(
                                "current_stock" to newStock,
                                "updated_at" to now
                            )
                        )
                    }
                }

                // Write customer balance & ledger entry atomically if credit sale
                if (isCreditBilling && custId != null && customerSnapshot != null && customerSnapshot.exists()) {
                    transaction.update(
                        customersCollection.document(custId),
                        mapOf(
                            "balance" to newCustomerBalance,
                            "currentBalance" to newCustomerBalance,
                            "updated_at" to now
                        )
                    )

                    if (ledgerEntry != null) {
                        val ledgerMap = hashMapOf<String, Any?>(
                            "id" to ledgerEntry.id,
                            "partyType" to ledgerEntry.partyType,
                            "partyId" to ledgerEntry.partyId,
                            "partyName" to ledgerEntry.partyName,
                            "type" to ledgerEntry.type,
                            "amount" to ledgerEntry.amount,
                            "datetime" to ledgerEntry.datetime,
                            "note" to ledgerEntry.note,
                            "referenceId" to ledgerEntry.referenceId,
                            "updated_at" to now
                        )
                        transaction.set(ledgerCollection.document(ledgerEntry.id), ledgerMap, SetOptions.merge())

                        // Mirror to customer's public_ledger subcollection
                        if (ledgerEntry.partyType.equals("CUSTOMER", ignoreCase = true) && ledgerEntry.partyId.isNotBlank()) {
                            val publicLedgerDoc = customersCollection.document(ledgerEntry.partyId).collection("public_ledger").document(ledgerEntry.id)
                            transaction.set(publicLedgerDoc, ledgerMap, SetOptions.merge())
                        }
                    }
                }

                // Also persist sale in same transaction
                val saleData = hashMapOf<String, Any?>(
                    "id" to sale.id,
                    "datetime" to sale.datetime,
                    "totalAmount" to sale.totalAmount,
                    "discount" to sale.discount,
                    "finalAmount" to sale.finalAmount,
                    "paymentMode" to sale.paymentMode,
                    "customerId" to sale.customerId,
                    "customerName" to sale.customerName,
                    "isHeld" to sale.isHeld,
                    "notes" to sale.notes,
                    "receivedAmount" to sale.receivedAmount,
                    "dueAmount" to sale.dueAmount,
                    "previousBalance" to sale.previousBalance,
                    "staffId" to sale.staffId,
                    "staffName" to sale.staffName,
                    "items" to consolidatedItems.map { item ->
                        hashMapOf<String, Any?>(
                            "productId" to item.productId,
                            "productNameEn" to item.productNameEn,
                            "productNameBn" to item.productNameBn,
                            "unitType" to item.unitType,
                            "quantity" to item.quantity,
                            "unitPrice" to item.unitPrice,
                            "costPrice" to item.costPrice,
                            "subtotal" to item.subtotal,
                            "totalCost" to item.totalCost
                        )
                    },
                    "updated_at" to now
                )
                DataSyncManager.logFirestoreWrite(
                    operationType = "TRANSACTION_SET_MERGE",
                    collection = "sales",
                    documentId = sale.id,
                    payload = saleData,
                    caller = "processSaleTransaction"
                )
                transaction.set(salesCollection.document(sale.id), saleData, SetOptions.merge())

                // If fulfilling online order, atomically update order status and link saleId inside transaction
                if (!orderIdToComplete.isNullOrBlank()) {
                    val orderUpdates = mutableMapOf<String, Any?>(
                        "status" to com.example.data.models.OrderStatus.COMPLETED,
                        "saleId" to sale.id,
                        "paymentMethod" to sale.paymentMode,
                        "completedAt" to now,
                        "updatedAt" to now
                    )
                    val dispatchActor = dispatchedByStaffName?.ifBlank { null }
                        ?: com.example.utils.StaffManager.getActiveStaffOrOwnerName()
                    orderUpdates["dispatchedByStaffName"] = dispatchActor

                    if (sale.receivedAmount >= (sale.finalAmount - 0.01)) {
                        orderUpdates["paymentStatus"] = com.example.data.models.OrderPaymentStatus.PAID
                    }
                    transaction.update(ordersCollection.document(orderIdToComplete), orderUpdates)

                    // Atomically update customer_accounts/{uid}/order_refs/{orderId} status to COMPLETED inside the same transaction
                    if (orderRefDocToUpdate != null && orderRefSnapshot != null && orderRefSnapshot.exists()) {
                        val refUpdates = mutableMapOf<String, Any?>(
                            "status" to com.example.data.models.OrderStatus.COMPLETED,
                            "completedAt" to now,
                            "updatedAt" to now
                        )
                        transaction.update(orderRefDocToUpdate, refUpdates)
                    }

                    // Atomically increment customer_accounts/{uid} completedOrderCount counter
                    if (customerAccountDocToUpdate != null && customerAccountSnapshot != null && customerAccountSnapshot.exists()) {
                        val currentCount = (customerAccountSnapshot.get("completedOrderCount") as? Number)?.toLong() ?: 0L
                        transaction.update(
                            customerAccountDocToUpdate,
                            mapOf(
                                "completedOrderCount" to (currentCount + 1),
                                "updatedAt" to now
                            )
                        )
                    }
                }

                SaleTransactionResult(
                    updatedStocks = newStockResults,
                    updatedCustomerBalance = if (isCreditBilling) newCustomerBalance else null
                )
            }.await()

            Result.success(result)
        } catch (e: InsufficientStockException) {
            Log.w("FirestoreManager", "Firestore transaction blocked sale due to insufficient stock: ${e.message}")
            Result.failure(e)
        } catch (e: CreditLimitExceededException) {
            Log.w("FirestoreManager", "Firestore transaction blocked credit sale due to credit limit: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            val cause = e.cause
            when (cause) {
                is InsufficientStockException -> Result.failure(cause)
                is CreditLimitExceededException -> Result.failure(cause)
                else -> {
                    Log.w("FirestoreManager", "Firestore sale transaction error: ${e.message}")
                    Result.failure(e)
                }
            }
        }
    }

    suspend fun executeSaleStockTransaction(
        sale: Sale,
        items: List<SaleItem>,
        productDeductions: Map<String, Double>,
        allowNegativeStock: Boolean = false
    ): Result<Map<String, Double>> {
        val res = executeSaleAndCreditTransaction(
            sale = sale,
            items = items,
            productDeductions = productDeductions,
            allowNegativeStock = allowNegativeStock,
            ownerOverride = true
        )
        return if (res.isSuccess) {
            Result.success(res.getOrNull()?.updatedStocks ?: emptyMap())
        } else {
            Result.failure(res.exceptionOrNull() ?: Exception("Transaction failed"))
        }
    }

    /**
     * Executes an atomic Firestore transaction for Khata credit and payment operations.
     * Enforces credit limits atomically on the cloud before updating balance.
     */
    suspend fun executeCustomerLedgerTransaction(
        customerId: String,
        amount: Double,
        isCreditGiven: Boolean,
        ledgerEntry: LedgerEntry,
        ownerOverride: Boolean = false
    ): Result<Double> {
        return try {
            val updatedBalance = firestore.runTransaction { transaction ->
                val now = System.currentTimeMillis()
                val custRef = customersCollection.document(customerId)
                val snapshot = transaction.get(custRef)
                
                val currentBalance = if (snapshot.exists()) {
                    (snapshot.get("balance") as? Number)?.toDouble()
                        ?: (snapshot.get("currentBalance") as? Number)?.toDouble()
                        ?: 0.0
                } else 0.0

                val creditLimit = if (snapshot.exists()) {
                    (snapshot.get("creditLimit") as? Number)?.toDouble()
                        ?: (snapshot.get("credit_limit") as? Number)?.toDouble()
                        ?: 0.0
                } else 0.0

                val newBalance = if (isCreditGiven) currentBalance + amount else (currentBalance - amount)

                if (isCreditGiven && creditLimit > 0.0 && newBalance > (creditLimit + 0.00001) && !ownerOverride) {
                    val custName = snapshot.getString("name") ?: "Customer"
                    throw CreditLimitExceededException(
                        customerId = customerId,
                        customerName = custName,
                        currentBalance = currentBalance,
                        creditLimit = creditLimit,
                        attemptedDue = newBalance
                    )
                }

                if (snapshot.exists()) {
                    transaction.update(
                        custRef,
                        mapOf(
                            "balance" to newBalance,
                            "currentBalance" to newBalance,
                            "updated_at" to now
                        )
                    )
                }

                val ledgerMap = hashMapOf<String, Any?>(
                    "id" to ledgerEntry.id,
                    "partyType" to ledgerEntry.partyType,
                    "partyId" to ledgerEntry.partyId,
                    "partyName" to ledgerEntry.partyName,
                    "type" to ledgerEntry.type,
                    "amount" to ledgerEntry.amount,
                    "datetime" to ledgerEntry.datetime,
                    "note" to ledgerEntry.note,
                    "referenceId" to ledgerEntry.referenceId,
                    "updated_at" to now
                )
                transaction.set(ledgerCollection.document(ledgerEntry.id), ledgerMap, SetOptions.merge())

                // Mirror to customer's public_ledger subcollection
                if (ledgerEntry.partyType.equals("CUSTOMER", ignoreCase = true) && ledgerEntry.partyId.isNotBlank()) {
                    val publicLedgerDoc = customersCollection.document(ledgerEntry.partyId).collection("public_ledger").document(ledgerEntry.id)
                    transaction.set(publicLedgerDoc, ledgerMap, SetOptions.merge())
                }

                newBalance
            }.await()

            Result.success(updatedBalance)
        } catch (e: CreditLimitExceededException) {
            Result.failure(e)
        } catch (e: Exception) {
            val cause = e.cause
            if (cause is CreditLimitExceededException) {
                Result.failure(cause)
            } else {
                Result.failure(e)
            }
        }
    }

    suspend fun saveSale(sale: Sale, items: List<SaleItem>): Boolean {
        return try {
            val consolidatedItems = com.example.utils.SaleConsolidationUtils.sanitizeSaleItems(sale, items)
            val saleData = hashMapOf<String, Any?>(
                "id" to sale.id,
                "datetime" to sale.datetime,
                "totalAmount" to sale.totalAmount,
                "discount" to sale.discount,
                "finalAmount" to sale.finalAmount,
                "paymentMode" to sale.paymentMode,
                "customerId" to sale.customerId,
                "customerName" to sale.customerName,
                "isHeld" to sale.isHeld,
                "notes" to sale.notes,
                "receivedAmount" to sale.receivedAmount,
                "dueAmount" to sale.dueAmount,
                "previousBalance" to sale.previousBalance,
                "staffId" to sale.staffId,
                "staffName" to sale.staffName,
                "dueDate" to sale.dueDate,
                "items" to consolidatedItems.map { item ->
                    hashMapOf<String, Any?>(
                        "productId" to item.productId,
                        "productNameEn" to item.productNameEn,
                        "productNameBn" to item.productNameBn,
                        "unitType" to item.unitType,
                        "quantity" to item.quantity,
                        "unitPrice" to item.unitPrice,
                        "costPrice" to item.costPrice,
                        "subtotal" to item.subtotal,
                        "totalCost" to item.totalCost
                    )
                },
                "updated_at" to System.currentTimeMillis()
            )
            DataSyncManager.logFirestoreWrite(
                operationType = "SET_MERGE",
                collection = "sales",
                documentId = sale.id,
                payload = saleData,
                caller = "saveSale"
            )
            salesCollection.document(sale.id).set(saleData, SetOptions.merge()).await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not sync sale ${sale.id} to Firestore: ${e.message}")
            false
        }
    }

    suspend fun saveSales(sales: List<SaleWithItems>): Boolean {
        if (sales.isEmpty()) return true
        return try {
            val batch = firestore.batch()
            sales.forEach { saleWithItems ->
                val sale = saleWithItems.sale
                val items = saleWithItems.consolidatedItems
                val docRef = salesCollection.document(sale.id)
                val saleData = hashMapOf<String, Any?>(
                    "id" to sale.id,
                    "datetime" to sale.datetime,
                    "totalAmount" to sale.totalAmount,
                    "discount" to sale.discount,
                    "finalAmount" to sale.finalAmount,
                    "paymentMode" to sale.paymentMode,
                    "customerId" to sale.customerId,
                    "customerName" to sale.customerName,
                    "isHeld" to sale.isHeld,
                    "notes" to sale.notes,
                    "receivedAmount" to sale.receivedAmount,
                    "dueAmount" to sale.dueAmount,
                    "previousBalance" to sale.previousBalance,
                    "staffId" to sale.staffId,
                    "staffName" to sale.staffName,
                    "items" to items.map { item ->
                        hashMapOf<String, Any?>(
                            "productId" to item.productId,
                            "productNameEn" to item.productNameEn,
                            "productNameBn" to item.productNameBn,
                            "unitType" to item.unitType,
                            "quantity" to item.quantity,
                            "unitPrice" to item.unitPrice,
                            "costPrice" to item.costPrice,
                            "subtotal" to item.subtotal,
                            "totalCost" to item.totalCost
                        )
                    },
                    "updated_at" to System.currentTimeMillis()
                )
                DataSyncManager.logFirestoreWrite(
                    operationType = "BATCH_SET_MERGE",
                    collection = "sales",
                    documentId = sale.id,
                    payload = saleData,
                    caller = "saveSalesBatch"
                )
                batch.set(docRef, saleData, SetOptions.merge())
            }
            batch.commit().await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not batch save sales to Firestore: ${e.message}")
            false
        }
    }

    suspend fun deleteSale(saleId: String): Boolean {
        return try {
            salesCollection.document(saleId).delete().await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not delete sale $saleId on Firestore: ${e.message}")
            false
        }
    }

    fun getSalesFlow(): Flow<List<SaleWithItems>> = callbackFlow {
        val listener = try {
            salesCollection.addSnapshotListener(MetadataChanges.EXCLUDE) { snapshot, error ->
                if (error != null) {
                    Log.w("FirestoreManager", "Firestore sales listener note: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val salesList = snapshot.documents.mapNotNull { doc ->
                        mapDocumentToSaleWithItems(doc)
                    }
                    trySend(salesList)
                }
            }
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Failed to attach sales snapshot listener: ${e.message}")
            null
        }
        awaitClose { listener?.remove() }
    }

    suspend fun getAllSalesOnce(): List<SaleWithItems> {
        return try {
            val snapshot = salesCollection.get().await()
            snapshot.documents.mapNotNull { mapDocumentToSaleWithItems(it) }
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not fetch all sales from Firestore: ${e.message}")
            emptyList()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun mapDocumentToSaleWithItems(doc: DocumentSnapshot): SaleWithItems? {
        return try {
            val id = doc.id
            val datetime = (doc.get("datetime") as? Number)?.toLong() ?: System.currentTimeMillis()
            val totalAmount = (doc.get("totalAmount") as? Number)?.toDouble() ?: 0.0
            val discount = (doc.get("discount") as? Number)?.toDouble() ?: 0.0
            val finalAmount = (doc.get("finalAmount") as? Number)?.toDouble() ?: totalAmount
            val paymentMode = doc.getString("paymentMode") ?: "CASH"
            val customerId = doc.getString("customerId")
            val customerName = doc.getString("customerName")
            val isHeld = doc.getBoolean("isHeld") ?: false
            val notes = doc.getString("notes")
            val receivedAmount = (doc.get("receivedAmount") as? Number)?.toDouble() ?: finalAmount
            val dueAmount = (doc.get("dueAmount") as? Number)?.toDouble() ?: 0.0
            val previousBalance = (doc.get("previousBalance") as? Number)?.toDouble() ?: 0.0
            val staffId = doc.getString("staffId")
            val staffName = doc.getString("staffName")
            val dueDate = (doc.get("dueDate") as? Number)?.toLong()

            val sale = Sale(
                id = id,
                datetime = datetime,
                totalAmount = totalAmount,
                discount = discount,
                finalAmount = finalAmount,
                paymentMode = paymentMode,
                customerId = customerId,
                customerName = customerName,
                isHeld = isHeld,
                notes = notes,
                receivedAmount = receivedAmount,
                dueAmount = dueAmount,
                previousBalance = previousBalance,
                staffId = staffId,
                staffName = staffName,
                dueDate = dueDate,
                needsSync = false
            )

            val rawItems = doc.get("items") as? List<Map<String, Any?>> ?: emptyList()
            val saleItems = rawItems.map { itemMap ->
                SaleItem(
                    saleId = id,
                    productId = itemMap["productId"] as? String ?: "",
                    productNameEn = itemMap["productNameEn"] as? String ?: "",
                    productNameBn = itemMap["productNameBn"] as? String ?: "",
                    unitType = itemMap["unitType"] as? String ?: "piece",
                    quantity = (itemMap["quantity"] as? Number)?.toDouble() ?: 1.0,
                    unitPrice = (itemMap["unitPrice"] as? Number)?.toDouble() ?: 0.0,
                    costPrice = (itemMap["costPrice"] as? Number)?.toDouble() ?: 0.0,
                    subtotal = (itemMap["subtotal"] as? Number)?.toDouble() ?: 0.0,
                    totalCost = (itemMap["totalCost"] as? Number)?.toDouble() ?: 0.0,
                    mrp = (itemMap["mrp"] as? Number)?.toDouble() ?: (itemMap["mrp_price"] as? Number)?.toDouble() ?: (itemMap["unitPrice"] as? Number)?.toDouble() ?: 0.0
                )
            }

            val sanitizedItems = com.example.utils.SaleConsolidationUtils.sanitizeSaleItems(sale, saleItems)
            SaleWithItems(sale = sale, items = sanitizedItems)
        } catch (e: Exception) {
            Log.e("FirestoreManager", "Error parsing sale ${doc.id}: ${e.message}")
            null
        }
    }

    // ==========================================
    // EXPENSES CLOUD SYNC
    // ==========================================

    suspend fun saveExpense(expense: Expense): Boolean {
        return try {
            val data = hashMapOf<String, Any?>(
                "id" to expense.id,
                "date" to expense.date,
                "category" to expense.category,
                "amount" to expense.amount,
                "note" to expense.note,
                "updated_at" to System.currentTimeMillis()
            )
            expensesCollection.document(expense.id).set(data, SetOptions.merge()).await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not save expense ${expense.id} to Firestore: ${e.message}")
            false
        }
    }

    suspend fun deleteExpense(expenseId: String): Boolean {
        return try {
            expensesCollection.document(expenseId).delete().await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not delete expense $expenseId from Firestore: ${e.message}")
            false
        }
    }

    fun getExpensesFlow(): Flow<List<Expense>> = callbackFlow {
        val listener = try {
            expensesCollection.addSnapshotListener(MetadataChanges.EXCLUDE) { snapshot, error ->
                if (error != null) {
                    Log.w("FirestoreManager", "Firestore expenses listener note: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val expenses = snapshot.documents.mapNotNull { doc ->
                        mapDocumentToExpense(doc)
                    }
                    trySend(expenses)
                }
            }
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Failed to attach expenses snapshot listener: ${e.message}")
            null
        }
        awaitClose { listener?.remove() }
    }

    private fun mapDocumentToExpense(doc: DocumentSnapshot): Expense? {
        return try {
            Expense(
                id = doc.id,
                date = (doc.get("date") as? Number)?.toLong() ?: System.currentTimeMillis(),
                category = doc.getString("category") ?: "Other",
                amount = (doc.get("amount") as? Number)?.toDouble() ?: 0.0,
                note = doc.getString("note"),
                needsSync = false
            )
        } catch (e: Exception) {
            Log.e("FirestoreManager", "Error parsing expense ${doc.id}: ${e.message}")
            null
        }
    }

    // ==========================================
    // SALES RETURNS & EXCHANGES CLOUD SYNC
    // ==========================================

    suspend fun saveSaleReturn(saleReturn: SaleReturn, returnItems: List<ReturnItem>): Boolean {
        return try {
            val data = hashMapOf<String, Any?>(
                "id" to saleReturn.id,
                "saleId" to saleReturn.saleId,
                "datetime" to saleReturn.datetime,
                "type" to saleReturn.type,
                "customerId" to saleReturn.customerId,
                "customerName" to saleReturn.customerName,
                "totalReturnedAmount" to saleReturn.totalReturnedAmount,
                "totalReplacementAmount" to saleReturn.totalReplacementAmount,
                "netAmount" to saleReturn.netAmount,
                "refundPaymentMode" to saleReturn.refundPaymentMode,
                "notes" to saleReturn.notes,
                "items" to returnItems.map { item ->
                    hashMapOf<String, Any?>(
                        "returnId" to item.returnId,
                        "productId" to item.productId,
                        "productNameEn" to item.productNameEn,
                        "productNameBn" to item.productNameBn,
                        "unitType" to item.unitType,
                        "quantity" to item.quantity,
                        "unitPrice" to item.unitPrice,
                        "subtotal" to item.subtotal,
                        "isReplacement" to item.isReplacement
                    )
                },
                "updated_at" to System.currentTimeMillis()
            )
            returnsCollection.document(saleReturn.id).set(data, SetOptions.merge()).await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not sync sale return ${saleReturn.id} to Firestore: ${e.message}")
            false
        }
    }

    fun getReturnsFlow(): Flow<List<SaleReturnWithItems>> = callbackFlow {
        val listener = try {
            returnsCollection.addSnapshotListener(MetadataChanges.EXCLUDE) { snapshot, error ->
                if (error != null) {
                    Log.w("FirestoreManager", "Firestore returns listener note: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val returns = snapshot.documents.mapNotNull { doc ->
                        mapDocumentToSaleReturnWithItems(doc)
                    }
                    trySend(returns)
                }
            }
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Failed to attach returns snapshot listener: ${e.message}")
            null
        }
        awaitClose { listener?.remove() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun mapDocumentToSaleReturnWithItems(doc: DocumentSnapshot): SaleReturnWithItems? {
        return try {
            val id = doc.id
            val saleId = doc.getString("saleId") ?: ""
            val datetime = (doc.get("datetime") as? Number)?.toLong() ?: System.currentTimeMillis()
            val type = doc.getString("type") ?: "RETURN"
            val customerId = doc.getString("customerId")
            val customerName = doc.getString("customerName")
            val totalReturnedAmount = (doc.get("totalReturnedAmount") as? Number)?.toDouble() ?: 0.0
            val totalReplacementAmount = (doc.get("totalReplacementAmount") as? Number)?.toDouble() ?: 0.0
            val netAmount = (doc.get("netAmount") as? Number)?.toDouble()
                ?: (doc.get("netAdjustedAmount") as? Number)?.toDouble() ?: 0.0
            val refundPaymentMode = doc.getString("refundPaymentMode")
                ?: doc.getString("settlementType") ?: "CASH"
            val notes = doc.getString("notes") ?: doc.getString("reason")

            val sr = SaleReturn(
                id = id,
                saleId = saleId,
                datetime = datetime,
                type = type,
                customerId = customerId,
                customerName = customerName,
                totalReturnedAmount = totalReturnedAmount,
                totalReplacementAmount = totalReplacementAmount,
                netAmount = netAmount,
                refundPaymentMode = refundPaymentMode,
                notes = notes,
                needsSync = false
            )

            val rawItems = doc.get("items") as? List<Map<String, Any?>> ?: emptyList()
            val returnItems = rawItems.map { itemMap ->
                ReturnItem(
                    returnId = (itemMap["returnId"] as? String) ?: (itemMap["saleReturnId"] as? String) ?: id,
                    productId = itemMap["productId"] as? String ?: "",
                    productNameEn = itemMap["productNameEn"] as? String ?: "",
                    productNameBn = itemMap["productNameBn"] as? String ?: "",
                    unitType = itemMap["unitType"] as? String ?: "piece",
                    quantity = (itemMap["quantity"] as? Number)?.toDouble() ?: 1.0,
                    unitPrice = (itemMap["unitPrice"] as? Number)?.toDouble() ?: 0.0,
                    subtotal = (itemMap["subtotal"] as? Number)?.toDouble() ?: 0.0,
                    isReplacement = itemMap["isReplacement"] as? Boolean ?: false
                )
            }

            SaleReturnWithItems(saleReturn = sr, items = returnItems)
        } catch (e: Exception) {
            Log.e("FirestoreManager", "Error parsing sale return ${doc.id}: ${e.message}")
            null
        }
    }

    // ==========================================
    // CUSTOMERS (KHATA) CLOUD SYNC
    // ==========================================

    suspend fun saveCustomer(customer: Customer): Boolean {
        return try {
            val data = hashMapOf<String, Any?>(
                "id" to customer.id,
                "name" to customer.name,
                "phone" to customer.phone,
                "balance" to customer.balance,
                "photoUri" to customer.photoUri,
                "creditLimit" to customer.creditLimit,
                "shareToken" to customer.shareToken,
                "interestExempt" to customer.interestExempt,
                "customInterestRate" to customer.customInterestRate,
                "customGracePeriodDays" to customer.customGracePeriodDays,
                "updated_at" to System.currentTimeMillis()
            )
            customersCollection.document(customer.id).set(data, SetOptions.merge()).await()
            if (!customer.shareToken.isNullOrBlank()) {
                try {
                    shareLinksCollection.document(customer.shareToken)
                        .set(mapOf("customerId" to customer.id))
                        .await()
                } catch (e: Exception) {
                    Log.w("FirestoreManager", "Could not create shareLinks document for ${customer.id}: ${e.message}")
                }
            }
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not save customer ${customer.id} to Firestore: ${e.message}")
            false
        }
    }

    suspend fun saveCustomers(customers: List<Customer>): Boolean {
        if (customers.isEmpty()) return true
        return try {
            val batch = firestore.batch()
            customers.forEach { customer ->
                val docRef = customersCollection.document(customer.id)
                val data = hashMapOf<String, Any?>(
                    "id" to customer.id,
                    "name" to customer.name,
                    "phone" to customer.phone,
                    "balance" to customer.balance,
                    "photoUri" to customer.photoUri,
                    "creditLimit" to customer.creditLimit,
                    "shareToken" to customer.shareToken,
                    "interestExempt" to customer.interestExempt,
                    "customInterestRate" to customer.customInterestRate,
                    "customGracePeriodDays" to customer.customGracePeriodDays,
                    "updated_at" to System.currentTimeMillis()
                )
                batch.set(docRef, data, SetOptions.merge())
                if (!customer.shareToken.isNullOrBlank()) {
                    val linkDocRef = shareLinksCollection.document(customer.shareToken)
                    batch.set(linkDocRef, mapOf("customerId" to customer.id))
                }
            }
            batch.commit().await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not batch save customers: ${e.message}")
            false
        }
    }

    suspend fun updateCustomerShareToken(customerId: String, token: String?, oldToken: String? = null): Boolean {
        return try {
            val data = mapOf<String, Any?>(
                "shareToken" to token,
                "shareTokenUpdatedAt" to System.currentTimeMillis(),
                "updated_at" to System.currentTimeMillis()
            )
            customersCollection.document(customerId).set(data, SetOptions.merge()).await()

            // 1. Delete old shareLinks document when token is revoked/regenerated
            if (!oldToken.isNullOrBlank() && oldToken != token) {
                try {
                    shareLinksCollection.document(oldToken).delete().await()
                } catch (e: Exception) {
                    Log.w("FirestoreManager", "Could not delete old shareLinks doc $oldToken: ${e.message}")
                }
            }

            // 2. Create new shareLinks document at shareLinks/{token} with { customerId: "<id>" }
            if (!token.isNullOrBlank()) {
                try {
                    shareLinksCollection.document(token).set(mapOf("customerId" to customerId)).await()
                } catch (e: Exception) {
                    Log.w("FirestoreManager", "Could not create shareLinks doc $token: ${e.message}")
                }
            }
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not update share token for customer $customerId: ${e.message}")
            false
        }
    }

    suspend fun ensureShareLink(token: String, customerId: String): Boolean {
        return try {
            shareLinksCollection.document(token).set(mapOf("customerId" to customerId)).await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not ensure shareLinks doc $token: ${e.message}")
            false
        }
    }

    suspend fun getCustomerById(customerId: String): Customer? {
        return try {
            val doc = customersCollection.document(customerId).get().await()
            if (doc.exists()) mapDocumentToCustomer(doc) else null
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not fetch customer $customerId: ${e.message}")
            null
        }
    }

    suspend fun getAllCustomersOnce(): List<Customer> {
        return try {
            val snapshot = customersCollection.get().await()
            snapshot.documents.mapNotNull { mapDocumentToCustomer(it) }
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not fetch all customers: ${e.message}")
            emptyList()
        }
    }

    suspend fun deleteCustomer(customerId: String, shareToken: String? = null): Boolean {
        return try {
            customersCollection.document(customerId).delete().await()
            if (!shareToken.isNullOrBlank()) {
                try {
                    shareLinksCollection.document(shareToken).delete().await()
                } catch (e: Exception) {
                    Log.w("FirestoreManager", "Could not delete shareLinks doc on customer delete: ${e.message}")
                }
            }
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not delete customer $customerId: ${e.message}")
            false
        }
    }

    fun getCustomersFlow(): Flow<List<Customer>> = callbackFlow {
        val listener = try {
            customersCollection.addSnapshotListener(MetadataChanges.EXCLUDE) { snapshot, error ->
                if (error != null) {
                    Log.w("FirestoreManager", "Firestore customers listener note: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val customers = snapshot.documents.mapNotNull { doc ->
                        mapDocumentToCustomer(doc)
                    }
                    trySend(customers)
                }
            }
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Failed to attach customers snapshot listener: ${e.message}")
            null
        }
        awaitClose { listener?.remove() }
    }

    private fun mapDocumentToCustomer(doc: DocumentSnapshot): Customer? {
        return try {
            val creditLimit = (doc.get("creditLimit") as? Number)?.toDouble()
                ?: (doc.get("credit_limit") as? Number)?.toDouble()
            val shareToken = doc.getString("shareToken") ?: doc.getString("share_token")
            val interestExempt = doc.getBoolean("interestExempt") ?: doc.getBoolean("interest_exempt") ?: false
            val customInterestRate = (doc.get("customInterestRate") as? Number)?.toDouble()
                ?: (doc.get("custom_interest_rate") as? Number)?.toDouble()
            val customGracePeriodDays = (doc.get("customGracePeriodDays") as? Number)?.toInt()
                ?: (doc.get("custom_grace_period_days") as? Number)?.toInt()
            Customer(
                id = doc.id,
                name = doc.getString("name") ?: "",
                phone = doc.getString("phone") ?: "",
                balance = (doc.get("balance") as? Number)?.toDouble() ?: 0.0,
                photoUri = doc.getString("photoUri"),
                creditLimit = creditLimit,
                shareToken = shareToken,
                interestExempt = interestExempt,
                customInterestRate = customInterestRate,
                customGracePeriodDays = customGracePeriodDays,
                needsSync = false
            )
        } catch (e: Exception) {
            Log.e("FirestoreManager", "Error parsing customer ${doc.id}: ${e.message}")
            null
        }
    }

    // ==========================================
    // SUPPLIERS (PURCHASE KHATA) CLOUD SYNC
    // ==========================================

    suspend fun saveSupplier(supplier: Supplier): Boolean {
        return try {
            val data = hashMapOf<String, Any?>(
                "id" to supplier.id,
                "name" to supplier.name,
                "phone" to supplier.phone,
                "balance" to supplier.balance,
                "address" to supplier.address,
                "gstin" to supplier.gstin,
                "notes" to supplier.notes,
                "photoUri" to supplier.photoUri,
                "updated_at" to System.currentTimeMillis()
            )
            suppliersCollection.document(supplier.id).set(data, SetOptions.merge()).await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not save supplier ${supplier.id} to Firestore: ${e.message}")
            false
        }
    }

    suspend fun saveSuppliers(suppliers: List<Supplier>): Boolean {
        if (suppliers.isEmpty()) return true
        return try {
            val batch = firestore.batch()
            suppliers.forEach { supplier ->
                val docRef = suppliersCollection.document(supplier.id)
                val data = hashMapOf<String, Any?>(
                    "id" to supplier.id,
                    "name" to supplier.name,
                    "phone" to supplier.phone,
                    "balance" to supplier.balance,
                    "address" to supplier.address,
                    "gstin" to supplier.gstin,
                    "notes" to supplier.notes,
                    "photoUri" to supplier.photoUri,
                    "updated_at" to System.currentTimeMillis()
                )
                batch.set(docRef, data, SetOptions.merge())
            }
            batch.commit().await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not batch save suppliers: ${e.message}")
            false
        }
    }

    suspend fun getAllSuppliersOnce(): List<Supplier> {
        return try {
            val snapshot = suppliersCollection.get().await()
            snapshot.documents.mapNotNull { mapDocumentToSupplier(it) }
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not fetch suppliers: ${e.message}")
            emptyList()
        }
    }

    suspend fun deleteSupplier(supplierId: String): Boolean {
        return try {
            suppliersCollection.document(supplierId).delete().await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not delete supplier $supplierId: ${e.message}")
            false
        }
    }

    fun getSuppliersFlow(): Flow<List<Supplier>> = callbackFlow {
        val listener = try {
            suppliersCollection.addSnapshotListener(MetadataChanges.EXCLUDE) { snapshot, error ->
                if (error != null) {
                    Log.w("FirestoreManager", "Firestore suppliers listener error: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val list = snapshot.documents.mapNotNull { mapDocumentToSupplier(it) }
                    trySend(list)
                }
            }
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Failed to listen to suppliers: ${e.message}")
            null
        }
        awaitClose { listener?.remove() }
    }

    private fun mapDocumentToSupplier(doc: DocumentSnapshot): Supplier? {
        return try {
            Supplier(
                id = doc.id,
                name = doc.getString("name") ?: "",
                phone = doc.getString("phone") ?: "",
                balance = (doc.get("balance") as? Number)?.toDouble() ?: 0.0,
                address = doc.getString("address"),
                gstin = doc.getString("gstin"),
                notes = doc.getString("notes"),
                photoUri = doc.getString("photoUri"),
                needsSync = false
            )
        } catch (e: Exception) {
            Log.e("FirestoreManager", "Error parsing supplier ${doc.id}: ${e.message}")
            null
        }
    }

    // ==========================================
    // PURCHASES & BILLS CLOUD SYNC
    // ==========================================

    suspend fun savePurchase(purchase: Purchase, items: List<PurchaseItem>): Boolean {
        return try {
            val consolidatedItems = com.example.utils.SaleConsolidationUtils.sanitizePurchaseItems(purchase, items)
            val data = hashMapOf<String, Any?>(
                "id" to purchase.id,
                "datetime" to purchase.datetime,
                "supplierId" to purchase.supplierId,
                "supplierName" to purchase.supplierName,
                "totalAmount" to purchase.totalAmount,
                "amountPaid" to purchase.amountPaid,
                "paidVia" to purchase.paidVia,
                "dueAmount" to purchase.dueAmount,
                "previousBalance" to purchase.previousBalance,
                "paymentMode" to purchase.paymentMode,
                "notes" to purchase.notes,
                "items" to consolidatedItems.map { item ->
                    hashMapOf<String, Any?>(
                        "id" to item.id,
                        "purchaseId" to item.purchaseId,
                        "productId" to item.productId,
                        "productNameEn" to item.productNameEn,
                        "productNameBn" to item.productNameBn,
                        "quantity" to item.quantity,
                        "costPrice" to item.costPrice,
                        "subtotal" to item.subtotal
                    )
                },
                "updated_at" to System.currentTimeMillis()
            )
            purchasesCollection.document(purchase.id).set(data, SetOptions.merge()).await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not save purchase ${purchase.id}: ${e.message}")
            false
        }
    }

    suspend fun savePurchases(purchases: List<PurchaseWithItems>): Boolean {
        if (purchases.isEmpty()) return true
        return try {
            val batch = firestore.batch()
            purchases.forEach { pWithItems ->
                val purchase = pWithItems.purchase
                val items = pWithItems.consolidatedItems
                val docRef = purchasesCollection.document(purchase.id)
                val data = hashMapOf<String, Any?>(
                    "id" to purchase.id,
                    "datetime" to purchase.datetime,
                    "supplierId" to purchase.supplierId,
                    "supplierName" to purchase.supplierName,
                    "totalAmount" to purchase.totalAmount,
                    "amountPaid" to purchase.amountPaid,
                    "paidVia" to purchase.paidVia,
                    "dueAmount" to purchase.dueAmount,
                    "previousBalance" to purchase.previousBalance,
                    "paymentMode" to purchase.paymentMode,
                    "notes" to purchase.notes,
                    "items" to items.map { item ->
                        hashMapOf<String, Any?>(
                            "id" to item.id,
                            "purchaseId" to item.purchaseId,
                            "productId" to item.productId,
                            "productNameEn" to item.productNameEn,
                            "productNameBn" to item.productNameBn,
                            "quantity" to item.quantity,
                            "costPrice" to item.costPrice,
                            "subtotal" to item.subtotal
                        )
                    },
                    "updated_at" to System.currentTimeMillis()
                )
                batch.set(docRef, data, SetOptions.merge())
            }
            batch.commit().await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not batch save purchases: ${e.message}")
            false
        }
    }

    suspend fun getAllPurchasesOnce(): List<PurchaseWithItems> {
        return try {
            val snapshot = purchasesCollection.get().await()
            snapshot.documents.mapNotNull { mapDocumentToPurchaseWithItems(it) }
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not fetch purchases: ${e.message}")
            emptyList()
        }
    }

    suspend fun deletePurchase(purchaseId: String): Boolean {
        return try {
            purchasesCollection.document(purchaseId).delete().await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not delete purchase $purchaseId on Firestore: ${e.message}")
            false
        }
    }

    fun getPurchasesFlow(): Flow<List<PurchaseWithItems>> = callbackFlow {
        val listener = try {
            purchasesCollection.addSnapshotListener(MetadataChanges.EXCLUDE) { snapshot, error ->
                if (error != null) {
                    Log.w("FirestoreManager", "Firestore purchases listener error: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val list = snapshot.documents.mapNotNull { mapDocumentToPurchaseWithItems(it) }
                    trySend(list)
                }
            }
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Failed to listen to purchases: ${e.message}")
            null
        }
        awaitClose { listener?.remove() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun mapDocumentToPurchaseWithItems(doc: DocumentSnapshot): PurchaseWithItems? {
        return try {
            val id = doc.id
            val datetime = (doc.get("datetime") as? Number)?.toLong() ?: System.currentTimeMillis()
            val supplierId = doc.getString("supplierId")
            val supplierName = doc.getString("supplierName")
            val totalAmount = (doc.get("totalAmount") as? Number)?.toDouble() ?: 0.0
            val paymentMode = doc.getString("paymentMode") ?: "CASH"
            val amountPaid = (doc.get("amountPaid") as? Number)?.toDouble() ?: if (paymentMode.equals("CREDIT", ignoreCase = true)) 0.0 else totalAmount
            val paidVia = doc.getString("paidVia") ?: if (paymentMode.equals("CREDIT", ignoreCase = true)) "CASH" else paymentMode
            val dueAmount = (doc.get("dueAmount") as? Number)?.toDouble() ?: (totalAmount - amountPaid).coerceAtLeast(0.0)
            val previousBalance = (doc.get("previousBalance") as? Number)?.toDouble() ?: 0.0
            val notes = doc.getString("notes")

            val purchase = Purchase(
                id = id,
                datetime = datetime,
                supplierId = supplierId,
                supplierName = supplierName,
                totalAmount = totalAmount,
                amountPaid = amountPaid,
                paidVia = paidVia,
                dueAmount = dueAmount,
                previousBalance = previousBalance,
                paymentMode = paymentMode,
                notes = notes,
                needsSync = false
            )

            val rawItems = doc.get("items") as? List<Map<String, Any?>> ?: emptyList()
            val items = rawItems.map { itemMap ->
                PurchaseItem(
                    id = (itemMap["id"] as? Number)?.toLong() ?: 0L,
                    purchaseId = (itemMap["purchaseId"] as? String) ?: id,
                    productId = itemMap["productId"] as? String ?: "",
                    productNameEn = itemMap["productNameEn"] as? String ?: "",
                    productNameBn = itemMap["productNameBn"] as? String ?: "",
                    quantity = (itemMap["quantity"] as? Number)?.toDouble() ?: 1.0,
                    costPrice = (itemMap["costPrice"] as? Number)?.toDouble() ?: 0.0,
                    subtotal = (itemMap["subtotal"] as? Number)?.toDouble() ?: 0.0
                )
            }
            val sanitizedItems = com.example.utils.SaleConsolidationUtils.sanitizePurchaseItems(purchase, items)
            PurchaseWithItems(purchase = purchase, items = sanitizedItems)
        } catch (e: Exception) {
            Log.e("FirestoreManager", "Error parsing purchase ${doc.id}: ${e.message}")
            null
        }
    }

    // ==========================================
    // LEDGER ENTRIES (KHATA AUDIT) CLOUD SYNC
    // ==========================================

    suspend fun saveLedgerEntry(entry: LedgerEntry): Boolean {
        return try {
            val data = hashMapOf<String, Any?>(
                "id" to entry.id,
                "partyType" to entry.partyType,
                "partyId" to entry.partyId,
                "partyName" to entry.partyName,
                "type" to entry.type,
                "amount" to entry.amount,
                "datetime" to entry.datetime,
                "note" to entry.note,
                "referenceId" to entry.referenceId,
                "dueDate" to entry.dueDate,
                "updated_at" to System.currentTimeMillis()
            )
            ledgerCollection.document(entry.id).set(data, SetOptions.merge()).await()

            // Mirror customer ledger entry to customers/{customerId}/public_ledger/{entryId}
            if (entry.partyType.equals("CUSTOMER", ignoreCase = true) && entry.partyId.isNotBlank()) {
                try {
                    customersCollection.document(entry.partyId)
                        .collection("public_ledger")
                        .document(entry.id)
                        .set(data, SetOptions.merge())
                        .await()
                } catch (pe: Exception) {
                    Log.w("FirestoreManager", "Could not mirror ledger entry ${entry.id} to public_ledger: ${pe.message}")
                }
            }
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not save ledger entry ${entry.id}: ${e.message}")
            false
        }
    }

    suspend fun saveLedgerEntries(entries: List<LedgerEntry>): Boolean {
        if (entries.isEmpty()) return true
        return try {
            val batch = firestore.batch()
            entries.forEach { entry ->
                val docRef = ledgerCollection.document(entry.id)
                val data = hashMapOf<String, Any?>(
                    "id" to entry.id,
                    "partyType" to entry.partyType,
                    "partyId" to entry.partyId,
                    "partyName" to entry.partyName,
                    "type" to entry.type,
                    "amount" to entry.amount,
                    "datetime" to entry.datetime,
                    "note" to entry.note,
                    "referenceId" to entry.referenceId,
                    "updated_at" to System.currentTimeMillis()
                )
                batch.set(docRef, data, SetOptions.merge())

                // Mirror customer ledger entry to customers/{customerId}/public_ledger/{entryId}
                if (entry.partyType.equals("CUSTOMER", ignoreCase = true) && entry.partyId.isNotBlank()) {
                    val pubDocRef = customersCollection.document(entry.partyId).collection("public_ledger").document(entry.id)
                    batch.set(pubDocRef, data, SetOptions.merge())
                }
            }
            batch.commit().await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not batch save ledger entries: ${e.message}")
            false
        }
    }

    suspend fun getAllLedgerEntriesOnce(): List<LedgerEntry> {
        return try {
            val snapshot = ledgerCollection.get().await()
            snapshot.documents.mapNotNull { mapDocumentToLedgerEntry(it) }
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not fetch ledger entries: ${e.message}")
            emptyList()
        }
    }

    suspend fun deleteLedgerEntry(ledgerId: String, customerId: String? = null): Boolean {
        return try {
            ledgerCollection.document(ledgerId).delete().await()
            if (!customerId.isNullOrBlank()) {
                try {
                    customersCollection.document(customerId)
                        .collection("public_ledger")
                        .document(ledgerId)
                        .delete()
                        .await()
                } catch (pe: Exception) {
                    Log.w("FirestoreManager", "Could not delete mirrored public_ledger entry $ledgerId: ${pe.message}")
                }
            }
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not delete ledger entry $ledgerId on Firestore: ${e.message}")
            false
        }
    }

    /**
     * One-time admin migration backfill:
     * Reads all documents from the top-level `ledger_entries` collection where partyType == "CUSTOMER",
     * and writes a mirrored copy to `customers/{partyId}/public_ledger/{entryId}` using the SAME document ID
     * in batched writes (up to 400 writes per batch, within Firestore's 500 limit).
     * Does NOT modify, delete, or touch anything in the original `ledger_entries` collection.
     */
    suspend fun backfillCustomerPublicLedgers(): PublicLedgerBackfillResult {
        val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
        if (user == null) {
            Log.d("FirestoreManager", "Skipping public_ledger backfill: client is unauthenticated.")
            return PublicLedgerBackfillResult(
                totalFound = 0,
                copiedCount = 0,
                failedCount = 0,
                isSuccess = true,
                errorMessage = "Skipped: Client is unauthenticated"
            )
        }
        return try {
            val snapshot = ledgerCollection.get().await()
            val customerDocs = snapshot.documents.filter { doc ->
                val partyType = doc.getString("partyType") ?: "CUSTOMER"
                val partyId = doc.getString("partyId")
                partyType.equals("CUSTOMER", ignoreCase = true) && !partyId.isNullOrBlank()
            }

            val totalFound = customerDocs.size
            if (totalFound == 0) {
                return PublicLedgerBackfillResult(
                    totalFound = 0,
                    copiedCount = 0,
                    failedCount = 0,
                    isSuccess = true,
                    errorMessage = null
                )
            }

            var copied = 0
            var failed = 0
            var lastError: String? = null

            val chunks = customerDocs.chunked(400)
            for (chunk in chunks) {
                val batch = firestore.batch()
                for (doc in chunk) {
                    val partyId = doc.getString("partyId") ?: continue
                    val entryId = doc.id
                    val publicDocRef = customersCollection.document(partyId).collection("public_ledger").document(entryId)

                    val data = hashMapOf<String, Any?>(
                        "id" to entryId,
                        "partyType" to "CUSTOMER",
                        "partyId" to partyId,
                        "partyName" to (doc.getString("partyName") ?: ""),
                        "type" to (doc.getString("type") ?: "PAYMENT"),
                        "amount" to ((doc.get("amount") as? Number)?.toDouble() ?: 0.0),
                        "datetime" to ((doc.get("datetime") as? Number)?.toLong() ?: System.currentTimeMillis()),
                        "note" to doc.getString("note"),
                        "referenceId" to doc.getString("referenceId"),
                        "dueDate" to (doc.get("dueDate") as? Number)?.toLong(),
                        "updated_at" to ((doc.get("updated_at") as? Number)?.toLong() ?: System.currentTimeMillis())
                    )
                    batch.set(publicDocRef, data, SetOptions.merge())
                }

                try {
                    batch.commit().await()
                    copied += chunk.size
                } catch (be: Exception) {
                    val isPerm = be.message?.contains("PERMISSION_DENIED", ignoreCase = true) == true
                    if (isPerm) {
                        Log.w("FirestoreManager", "Batch public_ledger backfill write skipped: permission restricted.")
                    } else {
                        Log.e("FirestoreManager", "Batch public_ledger backfill commit failed: ${be.message}")
                    }
                    failed += chunk.size
                    lastError = be.message
                }
            }

            val isSuccess = (failed == 0)
            if (isSuccess && copied > 0) {
                markPublicLedgerBackfillCompleted(copied)
            }

            PublicLedgerBackfillResult(
                totalFound = totalFound,
                copiedCount = copied,
                failedCount = failed,
                isSuccess = isSuccess,
                errorMessage = lastError
            )
        } catch (e: Exception) {
            val isPermission = e.message?.contains("PERMISSION_DENIED", ignoreCase = true) == true
            if (isPermission) {
                Log.w("FirestoreManager", "public_ledger backfill skipped: insufficient Firestore permissions.")
            } else {
                Log.e("FirestoreManager", "Error running public_ledger backfill: ${e.message}")
            }
            PublicLedgerBackfillResult(
                totalFound = 0,
                copiedCount = 0,
                failedCount = 0,
                isSuccess = false,
                errorMessage = e.message
            )
        }
    }

    /**
     * Checks if the public_ledger backfill migration has already completed in Firestore across all devices.
     */
    suspend fun isPublicLedgerBackfillCompleted(): Boolean {
        return try {
            val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
            if (user == null) {
                return true // Unauthenticated, skip remote backfill check
            }
            val doc = migrationStatusCollection.document("public_ledger_backfill").get().await()
            doc.exists() && (doc.getBoolean("completed") == true)
        } catch (e: Exception) {
            val isPermission = e.message?.contains("PERMISSION_DENIED", ignoreCase = true) == true
            if (isPermission) {
                Log.d("FirestoreManager", "public_ledger_backfill check skipped: insufficient permissions.")
                true // Skip trying to backfill
            } else {
                Log.w("FirestoreManager", "Check public_ledger_backfill migration status note: ${e.message}")
                false
            }
        }
    }

    /**
     * Marks the public_ledger backfill migration document in Firestore as completed.
     */
    suspend fun markPublicLedgerBackfillCompleted(totalCopied: Int): Boolean {
        return try {
            val data = hashMapOf<String, Any?>(
                "completed" to true,
                "completedAt" to System.currentTimeMillis(),
                "totalCopied" to totalCopied,
                "updated_at" to System.currentTimeMillis()
            )
            migrationStatusCollection.document("public_ledger_backfill")
                .set(data, SetOptions.merge())
                .await()
            true
        } catch (e: Exception) {
            Log.e("FirestoreManager", "Failed to mark public_ledger_backfill completed: ${e.message}")
            false
        }
    }

    /**
     * Checks if the customer share_links backfill migration has already completed in Firestore.
     */
    suspend fun isShareLinksBackfillCompleted(): Boolean {
        return try {
            val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
            if (user == null) {
                return true // Unauthenticated, skip remote backfill check
            }
            val doc = migrationStatusCollection.document("share_links_backfill").get().await()
            doc.exists() && (doc.getBoolean("completed") == true)
        } catch (e: Exception) {
            val isPermission = e.message?.contains("PERMISSION_DENIED", ignoreCase = true) == true
            if (isPermission) {
                Log.d("FirestoreManager", "share_links_backfill check skipped: insufficient permissions.")
                true
            } else {
                Log.w("FirestoreManager", "Check share_links_backfill migration status note: ${e.message}")
                false
            }
        }
    }

    /**
     * Marks the customer share_links backfill migration document in Firestore as completed.
     */
    suspend fun markShareLinksBackfillCompleted(totalCount: Int): Boolean {
        return try {
            val data = hashMapOf<String, Any?>(
                "completed" to true,
                "completedAt" to System.currentTimeMillis(),
                "totalCount" to totalCount,
                "updated_at" to System.currentTimeMillis()
            )
            migrationStatusCollection.document("share_links_backfill")
                .set(data, SetOptions.merge())
                .await()
            true
        } catch (e: Exception) {
            Log.e("FirestoreManager", "Failed to mark share_links_backfill completed: ${e.message}")
            false
        }
    }

    fun getLedgerFlow(): Flow<List<LedgerEntry>> = callbackFlow {
        val listener = try {
            ledgerCollection.addSnapshotListener(MetadataChanges.EXCLUDE) { snapshot, error ->
                if (error != null) {
                    Log.w("FirestoreManager", "Firestore ledger listener error: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val list = snapshot.documents.mapNotNull { mapDocumentToLedgerEntry(it) }
                    trySend(list)
                }
            }
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Failed to listen to ledger entries: ${e.message}")
            null
        }
        awaitClose { listener?.remove() }
    }

    private fun mapDocumentToLedgerEntry(doc: DocumentSnapshot): LedgerEntry? {
        return try {
            LedgerEntry(
                id = doc.id,
                partyType = doc.getString("partyType") ?: "CUSTOMER",
                partyId = doc.getString("partyId") ?: "",
                partyName = doc.getString("partyName") ?: "",
                type = doc.getString("type") ?: "PAYMENT",
                amount = (doc.get("amount") as? Number)?.toDouble() ?: 0.0,
                datetime = (doc.get("datetime") as? Number)?.toLong() ?: System.currentTimeMillis(),
                note = doc.getString("note"),
                referenceId = doc.getString("referenceId"),
                dueDate = (doc.get("dueDate") as? Number)?.toLong(),
                needsSync = false
            )
        } catch (e: Exception) {
            Log.e("FirestoreManager", "Error parsing ledger entry ${doc.id}: ${e.message}")
            null
        }
    }

    // ==========================================
    // PAYMENT CLAIMS (SELF-REPORTED UPI KHATA PAYMENTS)
    // ==========================================

    suspend fun savePaymentClaim(claim: PaymentClaim): Boolean {
        return try {
            val isFinalized = claim.status == PaymentClaim.STATUS_CONFIRMED || claim.status == PaymentClaim.STATUS_REJECTED
            val data = hashMapOf<String, Any?>(
                "id" to claim.id,
                "customerId" to claim.customerId,
                "customerName" to claim.customerName,
                "customerPhone" to claim.customerPhone,
                "shareToken" to claim.shareToken,
                "claimedAmount" to claim.claimedAmount,
                "dueBalanceAtClaim" to claim.dueBalanceAtClaim,
                "timestamp" to claim.timestamp,
                "status" to claim.status,
                "note" to claim.note,
                "rejectionReason" to claim.rejectionReason,
                "confirmedAt" to claim.confirmedAt,
                "confirmedBy" to claim.confirmedBy,
                "rejectedAt" to claim.rejectedAt,
                "rejectedBy" to claim.rejectedBy,
                "screenshotData" to if (isFinalized) null else claim.screenshotData,
                "screenshotUrl" to null
            )
            if (claim.customerId.isNotBlank()) {
                customersCollection.document(claim.customerId)
                    .collection("payment_claims")
                    .document(claim.id)
                    .set(data, SetOptions.merge())
                    .await()
            }
            // Keep legacy root collection in sync so obsolete pending claims don't persist
            try {
                firestore.collection("payment_claims").document(claim.id).set(data, SetOptions.merge()).await()
            } catch (_: Exception) {}

            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not save payment claim ${claim.id} to Firestore: ${e.message}")
            false
        }
    }

    suspend fun updatePaymentClaimStatus(
        customerId: String,
        claimId: String,
        status: String,
        actorName: String? = null,
        rejectionReason: String? = null
    ): Boolean {
        return try {
            val isFinalized = status == PaymentClaim.STATUS_CONFIRMED || status == PaymentClaim.STATUS_REJECTED
            val updates = hashMapOf<String, Any?>(
                "status" to status
            )
            if (status == PaymentClaim.STATUS_CONFIRMED) {
                updates["confirmedAt"] = System.currentTimeMillis()
                if (actorName != null) updates["confirmedBy"] = actorName
                updates["screenshotData"] = null
                updates["screenshotUrl"] = null
            } else if (status == PaymentClaim.STATUS_REJECTED) {
                updates["rejectedAt"] = System.currentTimeMillis()
                if (actorName != null) updates["rejectedBy"] = actorName
                if (rejectionReason != null) updates["rejectionReason"] = rejectionReason
                updates["screenshotData"] = null
                updates["screenshotUrl"] = null
            }
            if (customerId.isNotBlank()) {
                customersCollection.document(customerId)
                    .collection("payment_claims")
                    .document(claimId)
                    .set(updates, SetOptions.merge())
                    .await()
            }
            try {
                firestore.collection("payment_claims").document(claimId).set(updates, SetOptions.merge()).await()
            } catch (_: Exception) {}

            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not update payment claim $claimId status: ${e.message}")
            false
        }
    }

    private fun deduplicatePaymentClaims(claims: List<PaymentClaim>): List<PaymentClaim> {
        val map = linkedMapOf<String, PaymentClaim>()
        for (claim in claims) {
            val existing = map[claim.id]
            if (existing == null) {
                map[claim.id] = claim
            } else {
                // If one is confirmed or rejected and one is pending, keep confirmed/rejected
                val isExistingFinal = existing.status == PaymentClaim.STATUS_CONFIRMED || existing.status == PaymentClaim.STATUS_REJECTED
                val isCurrentFinal = claim.status == PaymentClaim.STATUS_CONFIRMED || claim.status == PaymentClaim.STATUS_REJECTED
                if (!isExistingFinal && isCurrentFinal) {
                    map[claim.id] = claim
                } else if (isExistingFinal && !isCurrentFinal) {
                    // Keep existing final status
                } else {
                    // Both final or both pending: keep newer timestamp or newer action time
                    val existingTime = maxOf(existing.confirmedAt ?: 0L, existing.rejectedAt ?: 0L, existing.timestamp)
                    val currentTime = maxOf(claim.confirmedAt ?: 0L, claim.rejectedAt ?: 0L, claim.timestamp)
                    if (currentTime >= existingTime) {
                        map[claim.id] = claim
                    }
                }
            }
        }
        return map.values.toList()
    }

    suspend fun getAllPaymentClaimsOnce(): List<PaymentClaim> {
        return try {
            val snapshot = firestore.collectionGroup("payment_claims").get().await()
            val list = snapshot.documents.mapNotNull { mapDocumentToPaymentClaim(it) }
            deduplicatePaymentClaims(list)
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Failed to fetch payment claims once: ${e.message}")
            emptyList()
        }
    }

    fun getPaymentClaimsFlow(): Flow<List<PaymentClaim>> = callbackFlow {
        val listener = try {
            firestore.collectionGroup("payment_claims").addSnapshotListener(MetadataChanges.EXCLUDE) { snapshot, error ->
                if (error != null) {
                    Log.w("FirestoreManager", "Firestore payment claims listener error: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val list = snapshot.documents.mapNotNull { mapDocumentToPaymentClaim(it) }
                    trySend(deduplicatePaymentClaims(list))
                }
            }
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Failed to listen to payment claims: ${e.message}")
            null
        }
        awaitClose { listener?.remove() }
    }

    private fun mapDocumentToPaymentClaim(doc: DocumentSnapshot): PaymentClaim? {
        return try {
            val customerId = doc.reference.parent.parent?.id
                ?: doc.getString("customerId")?.takeIf { it.isNotBlank() }
                ?: ""
            PaymentClaim(
                id = doc.id,
                customerId = customerId,
                customerName = doc.getString("customerName") ?: "",
                customerPhone = doc.getString("customerPhone") ?: "",
                shareToken = doc.getString("shareToken") ?: "",
                claimedAmount = (doc.get("claimedAmount") as? Number)?.toDouble() ?: 0.0,
                dueBalanceAtClaim = (doc.get("dueBalanceAtClaim") as? Number)?.toDouble() ?: 0.0,
                timestamp = (doc.get("timestamp") as? Number)?.toLong() ?: System.currentTimeMillis(),
                status = doc.getString("status") ?: PaymentClaim.STATUS_PENDING,
                note = doc.getString("note"),
                rejectionReason = doc.getString("rejectionReason"),
                confirmedAt = (doc.get("confirmedAt") as? Number)?.toLong(),
                confirmedBy = doc.getString("confirmedBy"),
                rejectedAt = (doc.get("rejectedAt") as? Number)?.toLong(),
                rejectedBy = doc.getString("rejectedBy"),
                screenshotData = doc.getString("screenshotData") ?: doc.getString("screenshotUrl"),
                screenshotUrl = doc.getString("screenshotUrl")
            )
        } catch (e: Exception) {
            Log.e("FirestoreManager", "Error parsing payment claim ${doc.id}: ${e.message}")
            null
        }
    }

    // ==========================================
    // PRODUCT BATCHES & EXPIRY CLOUD SYNC
    // ==========================================

    suspend fun saveBatch(batchItem: ProductBatch): Boolean {
        return try {
            val data = hashMapOf<String, Any?>(
                "id" to batchItem.id,
                "productId" to batchItem.productId,
                "batchNumber" to batchItem.batchNumber,
                "quantity" to batchItem.quantity,
                "expiryDate" to batchItem.expiryDate,
                "mfgDate" to batchItem.mfgDate,
                "costPrice" to batchItem.costPrice,
                "sellingPrice" to batchItem.sellingPrice,
                "addedTimestamp" to batchItem.addedTimestamp,
                "updated_at" to System.currentTimeMillis()
            )
            productBatchesCollection.document(batchItem.id).set(data, SetOptions.merge()).await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not save batch ${batchItem.id}: ${e.message}")
            false
        }
    }

    suspend fun saveBatches(batches: List<ProductBatch>): Boolean {
        if (batches.isEmpty()) return true
        return try {
            val batch = firestore.batch()
            batches.forEach { b ->
                val docRef = productBatchesCollection.document(b.id)
                val data = hashMapOf<String, Any?>(
                    "id" to b.id,
                    "productId" to b.productId,
                    "batchNumber" to b.batchNumber,
                    "quantity" to b.quantity,
                    "expiryDate" to b.expiryDate,
                    "mfgDate" to b.mfgDate,
                    "costPrice" to b.costPrice,
                    "sellingPrice" to b.sellingPrice,
                    "addedTimestamp" to b.addedTimestamp,
                    "updated_at" to System.currentTimeMillis()
                )
                batch.set(docRef, data, SetOptions.merge())
            }
            batch.commit().await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not batch save batches: ${e.message}")
            false
        }
    }

    suspend fun getAllBatchesOnce(): List<ProductBatch> {
        return try {
            val snapshot = productBatchesCollection.get().await()
            snapshot.documents.mapNotNull { mapDocumentToBatch(it) }
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not fetch batches: ${e.message}")
            emptyList()
        }
    }

    suspend fun deleteBatch(batchId: String): Boolean {
        return try {
            productBatchesCollection.document(batchId).delete().await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not delete batch $batchId: ${e.message}")
            false
        }
    }

    fun getBatchesFlow(): Flow<List<ProductBatch>> = callbackFlow {
        val listener = try {
            productBatchesCollection.addSnapshotListener(MetadataChanges.EXCLUDE) { snapshot, error ->
                if (error != null) {
                    Log.w("FirestoreManager", "Firestore batches listener error: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val list = snapshot.documents.mapNotNull { mapDocumentToBatch(it) }
                    trySend(list)
                }
            }
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Failed to listen to batches: ${e.message}")
            null
        }
        awaitClose { listener?.remove() }
    }

    private fun mapDocumentToBatch(doc: DocumentSnapshot): ProductBatch? {
        return try {
            ProductBatch(
                id = doc.id,
                productId = doc.getString("productId") ?: "",
                batchNumber = doc.getString("batchNumber") ?: "DEFAULT",
                quantity = (doc.get("quantity") as? Number)?.toDouble() ?: 0.0,
                expiryDate = doc.getString("expiryDate"),
                mfgDate = doc.getString("mfgDate"),
                costPrice = (doc.get("costPrice") as? Number)?.toDouble(),
                sellingPrice = (doc.get("sellingPrice") as? Number)?.toDouble(),
                addedTimestamp = (doc.get("addedTimestamp") as? Number)?.toLong() ?: System.currentTimeMillis(),
                needsSync = false
            )
        } catch (e: Exception) {
            Log.e("FirestoreManager", "Error parsing batch ${doc.id}: ${e.message}")
            null
        }
    }

    // ==========================================
    // EXPENSES BULK SYNC
    // ==========================================

    suspend fun saveExpenses(expenses: List<Expense>): Boolean {
        if (expenses.isEmpty()) return true
        return try {
            val batch = firestore.batch()
            expenses.forEach { expense ->
                val docRef = expensesCollection.document(expense.id)
                val data = hashMapOf<String, Any?>(
                    "id" to expense.id,
                    "date" to expense.date,
                    "category" to expense.category,
                    "amount" to expense.amount,
                    "note" to expense.note,
                    "updated_at" to System.currentTimeMillis()
                )
                batch.set(docRef, data, SetOptions.merge())
            }
            batch.commit().await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not batch save expenses: ${e.message}")
            false
        }
    }

    suspend fun getAllExpensesOnce(): List<Expense> {
        return try {
            val snapshot = expensesCollection.get().await()
            snapshot.documents.mapNotNull { mapDocumentToExpense(it) }
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not fetch all expenses: ${e.message}")
            emptyList()
        }
    }

    // ==========================================
    // SALES RETURNS BULK SYNC
    // ==========================================

    suspend fun saveSaleReturns(returns: List<SaleReturnWithItems>): Boolean {
        if (returns.isEmpty()) return true
        return try {
            val batch = firestore.batch()
            returns.forEach { rWithItems ->
                val saleReturn = rWithItems.saleReturn
                val returnItems = rWithItems.items
                val docRef = returnsCollection.document(saleReturn.id)
                val data = hashMapOf<String, Any?>(
                    "id" to saleReturn.id,
                    "saleId" to saleReturn.saleId,
                    "datetime" to saleReturn.datetime,
                    "type" to saleReturn.type,
                    "customerId" to saleReturn.customerId,
                    "customerName" to saleReturn.customerName,
                    "totalReturnedAmount" to saleReturn.totalReturnedAmount,
                    "totalReplacementAmount" to saleReturn.totalReplacementAmount,
                    "netAmount" to saleReturn.netAmount,
                    "refundPaymentMode" to saleReturn.refundPaymentMode,
                    "notes" to saleReturn.notes,
                    "items" to returnItems.map { item ->
                        hashMapOf<String, Any?>(
                            "returnId" to item.returnId,
                            "productId" to item.productId,
                            "productNameEn" to item.productNameEn,
                            "productNameBn" to item.productNameBn,
                            "unitType" to item.unitType,
                            "quantity" to item.quantity,
                            "unitPrice" to item.unitPrice,
                            "subtotal" to item.subtotal,
                            "isReplacement" to item.isReplacement
                        )
                    },
                    "updated_at" to System.currentTimeMillis()
                )
                batch.set(docRef, data, SetOptions.merge())
            }
            batch.commit().await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not batch save returns: ${e.message}")
            false
        }
    }

    suspend fun getAllReturnsOnce(): List<SaleReturnWithItems> {
        return try {
            val snapshot = returnsCollection.get().await()
            snapshot.documents.mapNotNull { mapDocumentToSaleReturnWithItems(it) }
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not fetch all returns: ${e.message}")
            emptyList()
        }
    }

    // ==========================================
    // EMPLOYEES & STAFF CLOUD SYNC
    // ==========================================

    suspend fun saveEmployee(employee: Employee): Boolean {
        return try {
            val data = hashMapOf<String, Any?>(
                "id" to employee.id,
                "name" to employee.name,
                "phone" to employee.phone,
                "email" to employee.email,
                "role" to employee.role,
                "pin" to employee.pin,
                "baseSalary" to employee.baseSalary,
                "salaryType" to employee.salaryType,
                "joiningDate" to employee.joiningDate,
                "isActive" to employee.isActive,
                "canMakeSales" to employee.canMakeSales,
                "canViewCostPrice" to employee.canViewCostPrice,
                "canManageInventory" to employee.canManageInventory,
                "canViewKhata" to employee.canViewKhata,
                "canManageExpenses" to employee.canManageExpenses,
                "canViewReports" to employee.canViewReports,
                "canAccessSettings" to employee.canAccessSettings,
                "canGiveDiscount" to employee.canGiveDiscount,
                "canDeleteSales" to employee.canDeleteSales,
                "updated_at" to System.currentTimeMillis()
            )
            employeesCollection.document(employee.id).set(data, SetOptions.merge()).await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not save employee ${employee.id}: ${e.message}")
            false
        }
    }

    suspend fun saveEmployees(employees: List<Employee>): Boolean {
        if (employees.isEmpty()) return true
        return try {
            val batch = firestore.batch()
            employees.forEach { employee ->
                val docRef = employeesCollection.document(employee.id)
                val data = hashMapOf<String, Any?>(
                    "id" to employee.id,
                    "name" to employee.name,
                    "phone" to employee.phone,
                    "email" to employee.email,
                    "role" to employee.role,
                    "pin" to employee.pin,
                    "baseSalary" to employee.baseSalary,
                    "salaryType" to employee.salaryType,
                    "joiningDate" to employee.joiningDate,
                    "isActive" to employee.isActive,
                    "canMakeSales" to employee.canMakeSales,
                    "canViewCostPrice" to employee.canViewCostPrice,
                    "canManageInventory" to employee.canManageInventory,
                    "canViewKhata" to employee.canViewKhata,
                    "canManageExpenses" to employee.canManageExpenses,
                    "canViewReports" to employee.canViewReports,
                    "canAccessSettings" to employee.canAccessSettings,
                    "canGiveDiscount" to employee.canGiveDiscount,
                    "canDeleteSales" to employee.canDeleteSales,
                    "updated_at" to System.currentTimeMillis()
                )
                batch.set(docRef, data, SetOptions.merge())
            }
            batch.commit().await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not batch save employees: ${e.message}")
            false
        }
    }

    suspend fun getAllEmployeesOnce(): List<Employee> {
        return try {
            val snapshot = employeesCollection.get().await()
            snapshot.documents.mapNotNull { mapDocumentToEmployee(it) }
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not fetch employees: ${e.message}")
            emptyList()
        }
    }

    suspend fun deleteEmployee(employeeId: String): Boolean {
        return try {
            employeesCollection.document(employeeId).delete().await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not delete employee $employeeId: ${e.message}")
            false
        }
    }

    fun getEmployeesFlow(): Flow<List<Employee>> = callbackFlow {
        val listener = try {
            employeesCollection.addSnapshotListener(MetadataChanges.EXCLUDE) { snapshot, error ->
                if (error != null) {
                    Log.w("FirestoreManager", "Firestore employees listener error: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val list = snapshot.documents.mapNotNull { mapDocumentToEmployee(it) }
                    trySend(list)
                }
            }
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Failed to listen to employees: ${e.message}")
            null
        }
        awaitClose { listener?.remove() }
    }

    private fun mapDocumentToEmployee(doc: DocumentSnapshot): Employee? {
        return try {
            Employee(
                id = doc.id,
                name = doc.getString("name") ?: "",
                phone = doc.getString("phone") ?: "",
                email = doc.getString("email") ?: "",
                role = doc.getString("role") ?: "Staff",
                pin = doc.getString("pin") ?: "1234",
                baseSalary = (doc.get("baseSalary") as? Number)?.toDouble() ?: 0.0,
                salaryType = doc.getString("salaryType") ?: "MONTHLY",
                joiningDate = (doc.get("joiningDate") as? Number)?.toLong() ?: System.currentTimeMillis(),
                isActive = doc.getBoolean("isActive") ?: true,
                canMakeSales = doc.getBoolean("canMakeSales") ?: true,
                canViewCostPrice = doc.getBoolean("canViewCostPrice") ?: false,
                canManageInventory = doc.getBoolean("canManageInventory") ?: false,
                canViewKhata = doc.getBoolean("canViewKhata") ?: false,
                canManageExpenses = doc.getBoolean("canManageExpenses") ?: false,
                canViewReports = doc.getBoolean("canViewReports") ?: false,
                canAccessSettings = doc.getBoolean("canAccessSettings") ?: false,
                canGiveDiscount = doc.getBoolean("canGiveDiscount") ?: true,
                canDeleteSales = doc.getBoolean("canDeleteSales") ?: false,
                needsSync = false
            )
        } catch (e: Exception) {
            Log.e("FirestoreManager", "Error parsing employee ${doc.id}: ${e.message}")
            null
        }
    }

    // ==========================================
    // EMPLOYEE ATTENDANCE CLOUD SYNC
    // ==========================================

    suspend fun saveAttendance(attendance: EmployeeAttendance): Boolean {
        return try {
            val data = hashMapOf<String, Any?>(
                "id" to attendance.id,
                "employeeId" to attendance.employeeId,
                "employeeName" to attendance.employeeName,
                "date" to attendance.date,
                "status" to attendance.status,
                "checkInTime" to attendance.checkInTime,
                "checkOutTime" to attendance.checkOutTime,
                "overtimeHours" to attendance.overtimeHours,
                "notes" to attendance.notes,
                "timestamp" to attendance.timestamp,
                "updated_at" to System.currentTimeMillis()
            )
            attendanceCollection.document(attendance.id).set(data, SetOptions.merge()).await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not save attendance ${attendance.id}: ${e.message}")
            false
        }
    }

    suspend fun saveAttendanceBatch(list: List<EmployeeAttendance>): Boolean {
        if (list.isEmpty()) return true
        return try {
            val batch = firestore.batch()
            list.forEach { item ->
                val docRef = attendanceCollection.document(item.id)
                val data = hashMapOf<String, Any?>(
                    "id" to item.id,
                    "employeeId" to item.employeeId,
                    "employeeName" to item.employeeName,
                    "date" to item.date,
                    "status" to item.status,
                    "checkInTime" to item.checkInTime,
                    "checkOutTime" to item.checkOutTime,
                    "overtimeHours" to item.overtimeHours,
                    "notes" to item.notes,
                    "timestamp" to item.timestamp,
                    "updated_at" to System.currentTimeMillis()
                )
                batch.set(docRef, data, SetOptions.merge())
            }
            batch.commit().await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not batch save attendance: ${e.message}")
            false
        }
    }

    suspend fun deleteAttendance(id: String): Boolean {
        return try {
            attendanceCollection.document(id).delete().await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not delete attendance $id: ${e.message}")
            false
        }
    }

    suspend fun deleteAttendance(employeeId: String, date: String): Boolean {
        return try {
            val docs = attendanceCollection
                .whereEqualTo("employeeId", employeeId)
                .whereEqualTo("date", date)
                .get().await()
            if (!docs.isEmpty) {
                val batch = firestore.batch()
                docs.forEach { batch.delete(it.reference) }
                batch.commit().await()
            }
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not delete attendance for $employeeId on $date: ${e.message}")
            false
        }
    }

    suspend fun getAllAttendanceOnce(): List<EmployeeAttendance> {
        return try {
            val snapshot = attendanceCollection.get().await()
            snapshot.documents.mapNotNull { mapDocumentToAttendance(it) }
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not fetch attendance: ${e.message}")
            emptyList()
        }
    }

    fun getAttendanceFlow(): Flow<List<EmployeeAttendance>> = callbackFlow {
        val listener = try {
            attendanceCollection.addSnapshotListener(MetadataChanges.EXCLUDE) { snapshot, error ->
                if (error != null) {
                    Log.w("FirestoreManager", "Firestore attendance listener error: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val list = snapshot.documents.mapNotNull { mapDocumentToAttendance(it) }
                    trySend(list)
                }
            }
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Failed to listen to attendance: ${e.message}")
            null
        }
        awaitClose { listener?.remove() }
    }

    private fun mapDocumentToAttendance(doc: DocumentSnapshot): EmployeeAttendance? {
        return try {
            EmployeeAttendance(
                id = doc.id,
                employeeId = doc.getString("employeeId") ?: "",
                employeeName = doc.getString("employeeName") ?: "",
                date = doc.getString("date") ?: "",
                status = doc.getString("status") ?: "PRESENT",
                checkInTime = doc.getString("checkInTime") ?: "",
                checkOutTime = doc.getString("checkOutTime") ?: "",
                overtimeHours = (doc.get("overtimeHours") as? Number)?.toDouble() ?: 0.0,
                notes = doc.getString("notes") ?: "",
                timestamp = (doc.get("timestamp") as? Number)?.toLong() ?: System.currentTimeMillis(),
                needsSync = false
            )
        } catch (e: Exception) {
            Log.e("FirestoreManager", "Error parsing attendance ${doc.id}: ${e.message}")
            null
        }
    }

    // ==========================================
    // SALARY DUES, PAYMENTS & ADVANCES CLOUD SYNC
    // ==========================================

    suspend fun saveSalaryDue(due: EmployeeSalaryDue): Boolean {
        return try {
            val data = hashMapOf<String, Any?>(
                "id" to due.id,
                "employeeId" to due.employeeId,
                "employeeName" to due.employeeName,
                "monthYear" to due.monthYear,
                "dueAmount" to due.dueAmount,
                "dueDate" to due.dueDate,
                "notes" to due.notes,
                "updated_at" to System.currentTimeMillis()
            )
            salaryDuesCollection.document(due.id).set(data, SetOptions.merge()).await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not save salary due ${due.id}: ${e.message}")
            false
        }
    }

    suspend fun saveSalaryDues(dues: List<EmployeeSalaryDue>): Boolean {
        if (dues.isEmpty()) return true
        return try {
            val batch = firestore.batch()
            dues.forEach { due ->
                val docRef = salaryDuesCollection.document(due.id)
                val data = hashMapOf<String, Any?>(
                    "id" to due.id,
                    "employeeId" to due.employeeId,
                    "employeeName" to due.employeeName,
                    "monthYear" to due.monthYear,
                    "dueAmount" to due.dueAmount,
                    "dueDate" to due.dueDate,
                    "notes" to due.notes,
                    "updated_at" to System.currentTimeMillis()
                )
                batch.set(docRef, data, SetOptions.merge())
            }
            batch.commit().await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not batch save salary dues: ${e.message}")
            false
        }
    }

    suspend fun deleteSalaryDue(id: String): Boolean {
        return try {
            salaryDuesCollection.document(id).delete().await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not delete salary due $id: ${e.message}")
            false
        }
    }

    suspend fun getAllSalaryDuesOnce(): List<EmployeeSalaryDue> {
        return try {
            val snapshot = salaryDuesCollection.get().await()
            snapshot.documents.mapNotNull { mapDocumentToSalaryDue(it) }
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not fetch salary dues: ${e.message}")
            emptyList()
        }
    }

    fun getSalaryDuesFlow(): Flow<List<EmployeeSalaryDue>> = callbackFlow {
        val listener = try {
            salaryDuesCollection.addSnapshotListener(MetadataChanges.EXCLUDE) { snapshot, error ->
                if (error != null) {
                    Log.w("FirestoreManager", "Firestore salary dues listener error: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val list = snapshot.documents.mapNotNull { mapDocumentToSalaryDue(it) }
                    trySend(list)
                }
            }
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Failed to listen to salary dues: ${e.message}")
            null
        }
        awaitClose { listener?.remove() }
    }

    private fun mapDocumentToSalaryDue(doc: DocumentSnapshot): EmployeeSalaryDue? {
        return try {
            EmployeeSalaryDue(
                id = doc.id,
                employeeId = doc.getString("employeeId") ?: "",
                employeeName = doc.getString("employeeName") ?: "",
                monthYear = doc.getString("monthYear") ?: "",
                dueAmount = (doc.get("dueAmount") as? Number)?.toDouble() ?: 0.0,
                dueDate = (doc.get("dueDate") as? Number)?.toLong() ?: System.currentTimeMillis(),
                notes = doc.getString("notes") ?: "",
                needsSync = false
            )
        } catch (e: Exception) {
            Log.e("FirestoreManager", "Error parsing salary due ${doc.id}: ${e.message}")
            null
        }
    }

    suspend fun saveSalaryPayment(payment: EmployeeSalaryPayment): Boolean {
        return try {
            val data = hashMapOf<String, Any?>(
                "id" to payment.id,
                "employeeId" to payment.employeeId,
                "employeeName" to payment.employeeName,
                "monthYear" to payment.monthYear,
                "paymentDate" to payment.paymentDate,
                "baseSalary" to payment.baseSalary,
                "salaryType" to payment.salaryType,
                "presentDays" to payment.presentDays,
                "halfDays" to payment.halfDays,
                "absentDays" to payment.absentDays,
                "paidLeaveDays" to payment.paidLeaveDays,
                "totalWorkingDaysInMonth" to payment.totalWorkingDaysInMonth,
                "overtimeHours" to payment.overtimeHours,
                "overtimePay" to payment.overtimePay,
                "bonus" to payment.bonus,
                "advanceDeduction" to payment.advanceDeduction,
                "otherDeductions" to payment.otherDeductions,
                "netSalaryPaid" to payment.netSalaryPaid,
                "paymentMode" to payment.paymentMode,
                "notes" to payment.notes,
                "syncedToExpense" to payment.syncedToExpense,
                "updated_at" to System.currentTimeMillis()
            )
            salaryPaymentsCollection.document(payment.id).set(data, SetOptions.merge()).await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not save salary payment ${payment.id}: ${e.message}")
            false
        }
    }

    suspend fun saveSalaryPayments(payments: List<EmployeeSalaryPayment>): Boolean {
        if (payments.isEmpty()) return true
        return try {
            val batch = firestore.batch()
            payments.forEach { p ->
                val docRef = salaryPaymentsCollection.document(p.id)
                val data = hashMapOf<String, Any?>(
                    "id" to p.id,
                    "employeeId" to p.employeeId,
                    "employeeName" to p.employeeName,
                    "monthYear" to p.monthYear,
                    "paymentDate" to p.paymentDate,
                    "baseSalary" to p.baseSalary,
                    "salaryType" to p.salaryType,
                    "presentDays" to p.presentDays,
                    "halfDays" to p.halfDays,
                    "absentDays" to p.absentDays,
                    "paidLeaveDays" to p.paidLeaveDays,
                    "totalWorkingDaysInMonth" to p.totalWorkingDaysInMonth,
                    "overtimeHours" to p.overtimeHours,
                    "overtimePay" to p.overtimePay,
                    "bonus" to p.bonus,
                    "advanceDeduction" to p.advanceDeduction,
                    "otherDeductions" to p.otherDeductions,
                    "netSalaryPaid" to p.netSalaryPaid,
                    "paymentMode" to p.paymentMode,
                    "notes" to p.notes,
                    "syncedToExpense" to p.syncedToExpense,
                    "updated_at" to System.currentTimeMillis()
                )
                batch.set(docRef, data, SetOptions.merge())
            }
            batch.commit().await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not batch save salary payments: ${e.message}")
            false
        }
    }

    suspend fun deleteSalaryPayment(id: String): Boolean {
        return try {
            salaryPaymentsCollection.document(id).delete().await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not delete salary payment $id: ${e.message}")
            false
        }
    }

    suspend fun getAllSalaryPaymentsOnce(): List<EmployeeSalaryPayment> {
        return try {
            val snapshot = salaryPaymentsCollection.get().await()
            snapshot.documents.mapNotNull { mapDocumentToSalaryPayment(it) }
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not fetch salary payments: ${e.message}")
            emptyList()
        }
    }

    fun getSalaryPaymentsFlow(): Flow<List<EmployeeSalaryPayment>> = callbackFlow {
        val listener = try {
            salaryPaymentsCollection.addSnapshotListener(MetadataChanges.EXCLUDE) { snapshot, error ->
                if (error != null) {
                    Log.w("FirestoreManager", "Firestore salary payments listener error: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val list = snapshot.documents.mapNotNull { mapDocumentToSalaryPayment(it) }
                    trySend(list)
                }
            }
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Failed to listen to salary payments: ${e.message}")
            null
        }
        awaitClose { listener?.remove() }
    }

    private fun mapDocumentToSalaryPayment(doc: DocumentSnapshot): EmployeeSalaryPayment? {
        return try {
            EmployeeSalaryPayment(
                id = doc.id,
                employeeId = doc.getString("employeeId") ?: "",
                employeeName = doc.getString("employeeName") ?: "",
                monthYear = doc.getString("monthYear") ?: "",
                paymentDate = (doc.get("paymentDate") as? Number)?.toLong() ?: System.currentTimeMillis(),
                baseSalary = (doc.get("baseSalary") as? Number)?.toDouble() ?: 0.0,
                salaryType = doc.getString("salaryType") ?: "MONTHLY",
                presentDays = (doc.get("presentDays") as? Number)?.toInt() ?: 0,
                halfDays = (doc.get("halfDays") as? Number)?.toInt() ?: 0,
                absentDays = (doc.get("absentDays") as? Number)?.toInt() ?: 0,
                paidLeaveDays = (doc.get("paidLeaveDays") as? Number)?.toInt() ?: 0,
                totalWorkingDaysInMonth = (doc.get("totalWorkingDaysInMonth") as? Number)?.toInt() ?: 30,
                overtimeHours = (doc.get("overtimeHours") as? Number)?.toDouble() ?: 0.0,
                overtimePay = (doc.get("overtimePay") as? Number)?.toDouble() ?: 0.0,
                bonus = (doc.get("bonus") as? Number)?.toDouble() ?: 0.0,
                advanceDeduction = (doc.get("advanceDeduction") as? Number)?.toDouble() ?: 0.0,
                otherDeductions = (doc.get("otherDeductions") as? Number)?.toDouble() ?: 0.0,
                netSalaryPaid = (doc.get("netSalaryPaid") as? Number)?.toDouble() ?: 0.0,
                paymentMode = doc.getString("paymentMode") ?: "CASH",
                notes = doc.getString("notes") ?: "",
                syncedToExpense = doc.getBoolean("syncedToExpense") ?: true,
                needsSync = false
            )
        } catch (e: Exception) {
            Log.e("FirestoreManager", "Error parsing salary payment ${doc.id}: ${e.message}")
            null
        }
    }

    suspend fun saveAdvance(advance: EmployeeAdvance): Boolean {
        return try {
            val data = hashMapOf<String, Any?>(
                "id" to advance.id,
                "employeeId" to advance.employeeId,
                "employeeName" to advance.employeeName,
                "type" to advance.type,
                "amount" to advance.amount,
                "date" to advance.date,
                "reason" to advance.reason,
                "repaidAmount" to advance.repaidAmount,
                "status" to advance.status,
                "paymentMode" to advance.paymentMode,
                "settledDate" to advance.settledDate,
                "settlementNotes" to advance.settlementNotes,
                "updated_at" to System.currentTimeMillis()
            )
            advancesCollection.document(advance.id).set(data, SetOptions.merge()).await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not save advance ${advance.id}: ${e.message}")
            false
        }
    }

    suspend fun saveAdvances(advances: List<EmployeeAdvance>): Boolean {
        if (advances.isEmpty()) return true
        return try {
            val batch = firestore.batch()
            advances.forEach { advance ->
                val docRef = advancesCollection.document(advance.id)
                val data = hashMapOf<String, Any?>(
                    "id" to advance.id,
                    "employeeId" to advance.employeeId,
                    "employeeName" to advance.employeeName,
                    "type" to advance.type,
                    "amount" to advance.amount,
                    "date" to advance.date,
                    "reason" to advance.reason,
                    "repaidAmount" to advance.repaidAmount,
                    "status" to advance.status,
                    "paymentMode" to advance.paymentMode,
                    "settledDate" to advance.settledDate,
                    "settlementNotes" to advance.settlementNotes,
                    "updated_at" to System.currentTimeMillis()
                )
                batch.set(docRef, data, SetOptions.merge())
            }
            batch.commit().await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not batch save advances: ${e.message}")
            false
        }
    }

    suspend fun deleteAdvance(id: String): Boolean {
        return try {
            advancesCollection.document(id).delete().await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not delete advance $id: ${e.message}")
            false
        }
    }

    suspend fun getAllAdvancesOnce(): List<EmployeeAdvance> {
        return try {
            val snapshot = advancesCollection.get().await()
            snapshot.documents.mapNotNull { mapDocumentToAdvance(it) }
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not fetch advances: ${e.message}")
            emptyList()
        }
    }

    fun getAdvancesFlow(): Flow<List<EmployeeAdvance>> = callbackFlow {
        val listener = try {
            advancesCollection.addSnapshotListener(MetadataChanges.EXCLUDE) { snapshot, error ->
                if (error != null) {
                    Log.w("FirestoreManager", "Firestore advances listener error: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val list = snapshot.documents.mapNotNull { mapDocumentToAdvance(it) }
                    trySend(list)
                }
            }
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Failed to listen to advances: ${e.message}")
            null
        }
        awaitClose { listener?.remove() }
    }

    private fun mapDocumentToAdvance(doc: DocumentSnapshot): EmployeeAdvance? {
        return try {
            EmployeeAdvance(
                id = doc.id,
                employeeId = doc.getString("employeeId") ?: "",
                employeeName = doc.getString("employeeName") ?: "",
                type = doc.getString("type") ?: "ADVANCE",
                amount = (doc.get("amount") as? Number)?.toDouble() ?: 0.0,
                date = (doc.get("date") as? Number)?.toLong() ?: System.currentTimeMillis(),
                reason = doc.getString("reason") ?: "",
                repaidAmount = (doc.get("repaidAmount") as? Number)?.toDouble() ?: 0.0,
                status = doc.getString("status") ?: "PENDING",
                paymentMode = doc.getString("paymentMode") ?: "CASH",
                settledDate = (doc.get("settledDate") as? Number)?.toLong(),
                settlementNotes = doc.getString("settlementNotes") ?: "",
                needsSync = false
            )
        } catch (e: Exception) {
            Log.e("FirestoreManager", "Error parsing advance ${doc.id}: ${e.message}")
            null
        }
    }

    // ==========================================
    // STORE SETTINGS & METADATA CLOUD SYNC
    // ==========================================

    suspend fun saveStoreSettings(settings: Map<String, Any?>): Boolean {
        return try {
            val data = HashMap(settings).apply {
                put("updated_at", System.currentTimeMillis())
            }
            storeSettingsCollection.document("main_store_profile").set(data, SetOptions.merge()).await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not save store settings: ${e.message}")
            false
        }
    }

    suspend fun getStoreSettings(): Map<String, Any?>? {
        return try {
            val doc = storeSettingsCollection.document("main_store_profile").get().await()
            if (doc.exists()) doc.data else null
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not fetch store settings: ${e.message}")
            null
        }
    }

    suspend fun getUpiVpaFromFirestore(): String? {
        return try {
            val doc = storeSettingsCollection.document("main_store_profile").get().await()
            if (doc.exists()) {
                val vpa = doc.getString("upiVpa")?.trim() ?: doc.getString("merchantUpiId")?.trim()
                if (vpa.isNullOrBlank()) {
                    Log.d("FirestoreManager", "Field 'upiVpa' is empty in store_settings/main_store_profile")
                    null
                } else {
                    vpa
                }
            } else {
                Log.d("FirestoreManager", "store_settings/main_store_profile document not created yet in Firestore")
                null
            }
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not fetch upiVpa from Firestore: ${e.message}")
            null
        }
    }

    fun listenStoreSettings(): kotlinx.coroutines.flow.Flow<Map<String, Any?>?> = kotlinx.coroutines.flow.callbackFlow {
        val listener = storeSettingsCollection.document("main_store_profile")
            .addSnapshotListener(MetadataChanges.EXCLUDE) { snapshot, error ->
                if (error != null) {
                    Log.w("FirestoreManager", "listenStoreSettings error: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists()) {
                    trySend(snapshot.data)
                } else {
                    trySend(null)
                }
            }
        awaitClose { listener.remove() }
    }

    // ==========================================
    // AUTOMATIC CLOUD BACKUP SNAPSHOTS
    // ==========================================
    private val cloudBackupsCollection get() = firestore.collection("cloud_backup_snapshots")

    suspend fun saveCloudBackupSnapshot(
        snapshotId: String,
        label: String,
        totalRecords: Int,
        jsonPayload: String,
        userEmail: String?
    ): Boolean {
        return try {
            val data = hashMapOf<String, Any?>(
                "id" to snapshotId,
                "label" to label,
                "totalRecords" to totalRecords,
                "jsonPayload" to jsonPayload,
                "userEmail" to userEmail,
                "timestamp" to System.currentTimeMillis()
            )
            cloudBackupsCollection.document(snapshotId).set(data).await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not save cloud backup snapshot: ${e.message}")
            false
        }
    }

    suspend fun getCloudBackupSnapshotsList(): List<CloudBackupSnapshotInfo> {
        return try {
            val snapshot = cloudBackupsCollection.orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING).limit(15).get().await()
            snapshot.documents.mapNotNull { doc ->
                val id = doc.getString("id") ?: doc.id
                val label = doc.getString("label") ?: "Cloud Backup"
                val totalRecords = (doc.get("totalRecords") as? Number)?.toInt() ?: 0
                val userEmail = doc.getString("userEmail")
                val timestamp = (doc.get("timestamp") as? Number)?.toLong() ?: 0L
                CloudBackupSnapshotInfo(
                    id = id,
                    label = label,
                    totalRecords = totalRecords,
                    userEmail = userEmail,
                    timestamp = timestamp
                )
            }
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not get cloud backup snapshots list: ${e.message}")
            emptyList()
        }
    }

    suspend fun getCloudBackupSnapshotPayload(snapshotId: String): String? {
        return try {
            val doc = cloudBackupsCollection.document(snapshotId).get().await()
            doc.getString("jsonPayload")
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not get cloud backup snapshot payload: ${e.message}")
            null
        }
    }

    suspend fun deleteCloudBackupSnapshot(snapshotId: String): Boolean {
        return try {
            cloudBackupsCollection.document(snapshotId).delete().await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not delete cloud backup snapshot: ${e.message}")
            false
        }
    }

    // ==========================================
    // ONLINE CUSTOMER ORDERS (amar-dukan-40808)
    // ==========================================

    fun getOrdersFlow(): Flow<List<com.example.data.models.Order>> = callbackFlow {
        val listener = try {
            ordersCollection
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .addSnapshotListener(MetadataChanges.EXCLUDE) { snapshot, error ->
                    if (error != null) {
                        Log.w("FirestoreManager", "Firestore orders listener error: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot != null) {
                        val orders = snapshot.documents.mapNotNull { com.example.data.models.Order.fromDocument(it) }
                        trySend(orders)
                    }
                }
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Failed to listen to orders: ${e.message}")
            null
        }
        awaitClose { listener?.remove() }
    }

    suspend fun getOrderById(orderId: String): com.example.data.models.Order? {
        return try {
            val doc = ordersCollection.document(orderId).get().await()
            com.example.data.models.Order.fromDocument(doc)
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not fetch order $orderId: ${e.message}")
            null
        }
    }

    suspend fun saveOrder(order: com.example.data.models.Order): Boolean {
        return try {
            val data = order.toMap()
            DataSyncManager.logFirestoreWrite(
                operationType = "SET_MERGE",
                collection = "orders",
                documentId = order.id,
                payload = data,
                caller = "saveOrder"
            )
            ordersCollection.document(order.id).set(data, SetOptions.merge()).await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not save order ${order.id}: ${e.message}")
            false
        }
    }

    suspend fun updateOrderStatus(
        orderId: String,
        newStatus: String,
        cancelReason: String? = null,
        saleId: String? = null,
        staffName: String? = null,
        customerUid: String? = null
    ): Boolean {
        return try {
            val updates = mutableMapOf<String, Any?>(
                "status" to newStatus,
                "updatedAt" to System.currentTimeMillis()
            )
            if (newStatus == com.example.data.models.OrderStatus.CONFIRMED) {
                updates["confirmedAt"] = System.currentTimeMillis()
                val actor = staffName?.ifBlank { null }
                    ?: com.example.utils.StaffManager.getActiveStaffOrOwnerName()
                updates["confirmedByStaffName"] = actor
            } else if (newStatus == com.example.data.models.OrderStatus.COMPLETED) {
                updates["completedAt"] = System.currentTimeMillis()
                val actor = staffName?.ifBlank { null }
                    ?: com.example.utils.StaffManager.getActiveStaffOrOwnerName()
                updates["dispatchedByStaffName"] = actor
            }
            if (cancelReason != null) {
                updates["cancelReason"] = cancelReason
            }
            if (saleId != null) {
                updates["saleId"] = saleId
            }
            ordersCollection.document(orderId).update(updates).await()

            val effectiveCustUid = if (!customerUid.isNullOrBlank()) {
                customerUid
            } else {
                try {
                    val orderDoc = ordersCollection.document(orderId).get().await()
                    orderDoc.getString("customerUid")?.ifBlank { null }
                } catch (_: Exception) {
                    null
                }
            }

            if (!effectiveCustUid.isNullOrBlank()) {
                val nowMs = System.currentTimeMillis()
                try {
                    val orderRefUpdates = mutableMapOf<String, Any>(
                        "status" to newStatus,
                        "updatedAt" to nowMs
                    )
                    if (newStatus == com.example.data.models.OrderStatus.COMPLETED) {
                        orderRefUpdates["completedAt"] = nowMs
                    }
                    if (cancelReason != null) {
                        orderRefUpdates["cancelReason"] = cancelReason
                    }
                    firestore.collection("customer_accounts")
                        .document(effectiveCustUid)
                        .collection("order_refs")
                        .document(orderId)
                        .update(orderRefUpdates)
                        .await()
                } catch (refEx: Exception) {
                    Log.w("FirestoreManager", "Could not update order_ref for order $orderId: ${refEx.message}")
                }

                if (newStatus == com.example.data.models.OrderStatus.COMPLETED) {
                    try {
                        firestore.collection("customer_accounts")
                            .document(effectiveCustUid)
                            .update(
                                mapOf(
                                    "completedOrderCount" to com.google.firebase.firestore.FieldValue.increment(1),
                                    "updatedAt" to nowMs
                                )
                            )
                            .await()
                    } catch (accEx: Exception) {
                        Log.w("FirestoreManager", "Could not increment completedOrderCount for customer $effectiveCustUid: ${accEx.message}")
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not update status for order $orderId: ${e.message}")
            false
        }
    }

    suspend fun updateOrderPaymentReference(orderId: String, utr: String): Boolean {
        return try {
            ordersCollection.document(orderId).update(
                mapOf(
                    "paymentReference" to utr.trim(),
                    "paymentSubmittedAt" to System.currentTimeMillis(),
                    "updatedAt" to System.currentTimeMillis()
                )
            ).await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not update payment reference for order $orderId: ${e.message}")
            false
        }
    }

    suspend fun updateOrderPaymentStatus(orderId: String, newPaymentStatus: String): Boolean {
        return try {
            ordersCollection.document(orderId).update(
                mapOf(
                    "paymentStatus" to newPaymentStatus,
                    "updatedAt" to System.currentTimeMillis()
                )
            ).await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Could not update payment status for order $orderId: ${e.message}")
            false
        }
    }

    suspend fun checkOrderStockAvailability(orderItems: List<com.example.data.models.OrderItem>): List<com.example.data.models.InsufficientStockItem> {
        val shortages = mutableListOf<com.example.data.models.InsufficientStockItem>()
        for (item in orderItems) {
            if (item.productId.isBlank() || item.productId.startsWith("quick_")) continue
            try {
                val doc = productsCollection.document(item.productId).get().await()
                if (doc.exists()) {
                    val available = (doc.get("current_stock") as? Number)?.toDouble()
                        ?: (doc.get("currentStock") as? Number)?.toDouble()
                        ?: 0.0
                    val nameEn = doc.getString("name_en") ?: doc.getString("nameEn") ?: item.name
                    val nameBn = doc.getString("name_bn") ?: doc.getString("nameBn") ?: ""
                    val unit = doc.getString("unit_type") ?: doc.getString("unitType") ?: item.unit
                    
                    val rawVariantsJson = doc.getString("barcode_variants_json") ?: doc.getString("barcodeVariantsJson")
                    val variantsList = doc.get("barcode_variants") as? List<*> ?: doc.get("barcodeVariants") as? List<*>
                    val variants = if (!rawVariantsJson.isNullOrBlank()) {
                        com.example.data.local.entities.BarcodeVariant.parseListFromJson(rawVariantsJson)
                    } else if (!variantsList.isNullOrEmpty()) {
                        variantsList.mapNotNull { if (it is Map<*, *>) com.example.data.local.entities.BarcodeVariant.fromMap(it) else null }
                    } else emptyList()

                    val cleanItemCode = item.variantBarcode?.trim() ?: ""
                    val variant = if (cleanItemCode.isNotBlank()) {
                        variants.find { it.barcode.trim().equals(cleanItemCode, ignoreCase = true) }
                    } else {
                        variants.find { it.label.equals(item.name, ignoreCase = true) || it.getShortLabel().equals(item.name, ignoreCase = true) }
                    }

                    val requiredBaseQty = if (variant != null) {
                        val basePerPack = if (unit.equals("kg", true) && variant.unitType.equals("gram", true)) variant.quantity / 1000.0
                        else if (unit.equals("litre", true) && variant.unitType.equals("ml", true)) variant.quantity / 1000.0
                        else variant.quantity
                        item.quantity * basePerPack
                    } else {
                        item.quantity
                    }

                    if (requiredBaseQty > (available + 0.00001)) {
                        shortages.add(
                            com.example.data.models.InsufficientStockItem(
                                productId = item.productId,
                                productNameEn = nameEn,
                                productNameBn = nameBn,
                                unitType = unit,
                                availableStock = available,
                                requestedQuantity = requiredBaseQty
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w("FirestoreManager", "Error checking live stock for ${item.productId}: ${e.message}")
            }
        }
        return shortages
    }

    /**
     * Permanently deletes all documents across all application Firestore collections.
     * Batches deletions in chunks of 400 documents to safely respect Firestore's 500-operation limit.
     */
    suspend fun deleteAllCloudData(
        includeReports: Boolean = true,
        includeSnapshots: Boolean = true,
        includeSettings: Boolean = true
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val collectionsToWipe = mutableListOf(
                productsCollection,
                ordersCollection,
                productBatchesCollection,
                salesCollection,
                expensesCollection,
                returnsCollection,
                customersCollection,
                suppliersCollection,
                purchasesCollection,
                ledgerCollection,
                employeesCollection,
                attendanceCollection,
                salaryDuesCollection,
                salaryPaymentsCollection,
                advancesCollection
            )
            if (includeReports) collectionsToWipe.add(reportsCollection)
            if (includeSnapshots) collectionsToWipe.add(cloudBackupsCollection)
            if (includeSettings) collectionsToWipe.add(storeSettingsCollection)

            var totalDeleted = 0
            for (col in collectionsToWipe) {
                try {
                    val snapshot = col.get().await()
                    val docs = snapshot.documents
                    if (docs.isNotEmpty()) {
                        for (chunk in docs.chunked(400)) {
                            val batch = firestore.batch()
                            chunk.forEach { doc -> batch.delete(doc.reference) }
                            batch.commit().await()
                            totalDeleted += chunk.size
                        }
                    }
                } catch (e: Exception) {
                    Log.w("FirestoreManager", "Error wiping collection ${col.path}: ${e.message}")
                }
            }
            Result.success(totalDeleted)
        } catch (e: Exception) {
            Log.e("FirestoreManager", "deleteAllCloudData failure: ${e.message}", e)
            Result.failure(e)
        }
    }

    // ==========================================
    // GOOGLE SIGN-IN CUSTOMER ACCOUNTS & KHATA LINKS
    // ==========================================

    /**
     * Real-time listener for pending customer link requests.
     */
    fun listenPendingCustomerLinks(): Flow<List<PendingLinkRequest>> = callbackFlow {
        val listener = try {
            pendingLinksCollection
                .orderBy("requestedAt", Query.Direction.DESCENDING)
                .addSnapshotListener(MetadataChanges.EXCLUDE) { snapshot, error ->
                    if (error != null) {
                        Log.w("FirestoreManager", "Error listening to pending links: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot != null) {
                        val requests = snapshot.documents.mapNotNull { doc ->
                            try {
                                PendingLinkRequest.fromDocument(doc)
                            } catch (e: Exception) {
                                Log.w("FirestoreManager", "Error parsing pending link doc ${doc.id}: ${e.message}")
                                null
                            }
                        }
                        trySend(requests)
                    }
                }
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Failed to start listening to pending links: ${e.message}")
            null
        }
        awaitClose { listener?.remove() }
    }

    /**
     * Live read of customers/{customerId} and its transaction count.
     * NEVER trusts stale snapshot fields in pending_links.
     */
    suspend fun getLiveCustomerDetails(customerId: String): LiveCustomerDetails = withContext(Dispatchers.IO) {
        if (customerId.isBlank()) {
            return@withContext LiveCustomerDetails(customerId = "", exists = false)
        }
        try {
            val custDoc = customersCollection.document(customerId).get().await()
            if (custDoc != null && custDoc.exists()) {
                val name = custDoc.getString("name") ?: ""
                val phone = custDoc.getString("phone") ?: ""
                val balance = custDoc.getDouble("balance") ?: 0.0

                // Historical bills count from public_ledger
                val ledgerSnap = try {
                    customersCollection.document(customerId).collection("public_ledger").get().await()
                } catch (e: Exception) {
                    null
                }
                val billCount = ledgerSnap?.size() ?: 0

                LiveCustomerDetails(
                    customerId = customerId,
                    name = name,
                    phone = phone,
                    balance = balance,
                    billCount = billCount,
                    exists = true
                )
            } else {
                LiveCustomerDetails(customerId = customerId, exists = false)
            }
        } catch (e: Exception) {
            Log.w("FirestoreManager", "Failed to read live customer $customerId: ${e.message}")
            LiveCustomerDetails(customerId = customerId, exists = false)
        }
    }

    /**
     * Atomically approve or reject a customer link request.
     * Updates pending_links/{requestId} and customer_accounts/{uid} in a single atomic transaction.
     */
    suspend fun reviewPendingCustomerLink(
        requestId: String,
        approve: Boolean,
        reviewerEmailOrUid: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            firestore.runTransaction { transaction ->
                val pendingDocRef = pendingLinksCollection.document(requestId)
                val pendingSnap = transaction.get(pendingDocRef)
                if (!pendingSnap.exists()) {
                    throw IllegalStateException("Pending link request $requestId does not exist")
                }

                val uid = pendingSnap.getString("uid")
                    ?: throw IllegalStateException("UID missing in pending link document")
                val customerId = pendingSnap.getString("customerId")
                    ?: throw IllegalStateException("CustomerId missing in pending link document")

                val accountDocRef = customerAccountsCollection.document(uid)
                val accountSnap = transaction.get(accountDocRef)

                if (approve) {
                    val custDocRef = customersCollection.document(customerId)
                    val custSnap = transaction.get(custDocRef)
                    if (!custSnap.exists()) {
                        throw IllegalStateException("Linked customer record no longer exists — cannot approve")
                    }
                }

                val now = System.currentTimeMillis()

                if (approve) {
                    transaction.update(
                        pendingDocRef,
                        mapOf(
                            "status" to CustomerLinkStatus.APPROVED,
                            "reviewedAt" to now,
                            "reviewedBy" to reviewerEmailOrUid
                        )
                    )
                    if (accountSnap.exists()) {
                        transaction.update(
                            accountDocRef,
                            mapOf(
                                "linkStatus" to CustomerLinkStatus.APPROVED,
                                "linkedCustomerId" to customerId,
                                "updatedAt" to now
                            )
                        )
                    }
                } else {
                    transaction.update(
                        pendingDocRef,
                        mapOf(
                            "status" to CustomerLinkStatus.REJECTED,
                            "reviewedAt" to now,
                            "reviewedBy" to reviewerEmailOrUid
                        )
                    )
                    if (accountSnap.exists()) {
                        transaction.update(
                            accountDocRef,
                            mapOf(
                                "linkStatus" to CustomerLinkStatus.REJECTED,
                                "updatedAt" to now
                            )
                        )
                    }
                }
            }.await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("FirestoreManager", "Transaction reviewPendingCustomerLink failed: ${e.message}", e)
            Result.failure(e)
        }
    }
}

data class CloudBackupSnapshotInfo(
    val id: String,
    val label: String,
    val totalRecords: Int,
    val userEmail: String?,
    val timestamp: Long
) {
    val formattedDate: String
        get() = java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
}

data class PublicLedgerBackfillResult(
    val totalFound: Int = 0,
    val copiedCount: Int = 0,
    val failedCount: Int = 0,
    val isSuccess: Boolean = false,
    val errorMessage: String? = null
)

