package com.example.data.firestore

val PERMANENT_ADMIN_EMAILS = setOf(
    "shuvajitsow384@gmail.com"
)

enum class AppRole(val roleName: String, val displayName: String, val description: String) {
    ADMIN("ADMIN", "Store Admin / Owner", "Full access to Profit & Loss reports, cost prices, store settings, and user management"),
    EMPLOYEE("EMPLOYEE", "Regular Employee / Cashier", "Restricted from profit reports and cost valuations; can perform billing and counter sales"),
    STORE_MANAGER("STORE_MANAGER", "Store Manager", "Operational manager with inventory, expense, and khata access, but restricted settings"),
    UNASSIGNED("UNASSIGNED", "Pending Approval / Unassigned", "Zero access granted. Account is waiting for shop owner to review and assign an active role."),
    UNRECOGNIZED("UNRECOGNIZED", "Unrecognized Account", "Zero access granted. Account is not an authorized staff member.")
}

data class FirestoreUserRole(
    val uid: String = "",
    val documentId: String = uid,
    val email: String? = null,
    val displayName: String? = null,
    val role: String = AppRole.UNASSIGNED.roleName,
    val status: String = "APPROVED",
    val reviewedBy: String? = null,
    val reviewedAt: Long? = null,
    val canViewReports: Boolean = false,
    val canViewCostPrice: Boolean = false,
    val canManageInventory: Boolean = false,
    val canViewKhata: Boolean = false,
    val canManageExpenses: Boolean = false,
    val canAccessSettings: Boolean = false,
    val canMakeSales: Boolean = false,
    val canGiveDiscount: Boolean = false,
    val canDeleteSales: Boolean = false,
    val storeId: String = "default_store",
    val assignedBy: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastActiveAt: Long = System.currentTimeMillis()
) {
    val isPermanentAdmin: Boolean
        get() = email?.trim()?.lowercase()?.let { it in PERMANENT_ADMIN_EMAILS } == true

    val isAdmin: Boolean
        get() = isPermanentAdmin || role.equals(AppRole.ADMIN.roleName, ignoreCase = true)

    val isEmployee: Boolean
        get() = !isPermanentAdmin && role.equals(AppRole.EMPLOYEE.roleName, ignoreCase = true)

    val isManager: Boolean
        get() = !isPermanentAdmin && role.equals(AppRole.STORE_MANAGER.roleName, ignoreCase = true)

    val isUnrecognized: Boolean
        get() = !isPermanentAdmin && (
            role.equals(AppRole.UNASSIGNED.roleName, ignoreCase = true) ||
            role.equals("PENDING", ignoreCase = true) ||
            role.equals(AppRole.UNRECOGNIZED.roleName, ignoreCase = true) ||
            role.equals("NONE", ignoreCase = true) ||
            role.isBlank() ||
            status.equals("PENDING", ignoreCase = true) ||
            status.equals("REJECTED", ignoreCase = true) ||
            status.equals("DENIED", ignoreCase = true)
        )

    val isPendingApproval: Boolean
        get() = !isPermanentAdmin && (
            status.equals("PENDING", ignoreCase = true) ||
            role.equals(AppRole.UNASSIGNED.roleName, ignoreCase = true) ||
            role.equals("PENDING", ignoreCase = true) ||
            role.equals(AppRole.UNRECOGNIZED.roleName, ignoreCase = true) ||
            role.isBlank()
        ) && !status.equals("REJECTED", ignoreCase = true) && !status.equals("DENIED", ignoreCase = true)

    val isRejected: Boolean
        get() = !isPermanentAdmin && (status.equals("REJECTED", ignoreCase = true) || status.equals("DENIED", ignoreCase = true))

    val isApproved: Boolean
        get() = isPermanentAdmin || (!isPendingApproval && !isRejected && (
            role.equals(AppRole.ADMIN.roleName, ignoreCase = true) ||
            role.equals(AppRole.EMPLOYEE.roleName, ignoreCase = true) ||
            role.equals(AppRole.STORE_MANAGER.roleName, ignoreCase = true)
        ))

    /**
     * Determines if this record corresponds to a genuine Firebase Auth account UID
     * rather than a preassigned placeholder keyed by email.
     */
    val hasRealAuthUid: Boolean
        get() {
            val cleanEmail = email?.trim()?.lowercase() ?: ""
            return uid.isNotBlank() && !uid.contains("@") && uid != cleanEmail && !documentId.contains("@")
        }

    // Effective permissions: Permanent admins and Admins always have full permissions; Unrecognized has 0 permissions
    val effectiveCanViewReports: Boolean get() = !isUnrecognized && (isAdmin || canViewReports)
    val effectiveCanViewCostPrice: Boolean get() = !isUnrecognized && (isAdmin || canViewCostPrice)
    val effectiveCanManageInventory: Boolean get() = !isUnrecognized && (isAdmin || canManageInventory)
    val effectiveCanViewKhata: Boolean get() = !isUnrecognized && (isAdmin || canViewKhata)
    val effectiveCanManageExpenses: Boolean get() = !isUnrecognized && (isAdmin || canManageExpenses)
    val effectiveCanAccessSettings: Boolean get() = !isUnrecognized && (isAdmin || canAccessSettings)
    val effectiveCanMakeSales: Boolean get() = !isUnrecognized && (isAdmin || canMakeSales)
    val effectiveCanGiveDiscount: Boolean get() = !isUnrecognized && (isAdmin || canGiveDiscount)
    val effectiveCanDeleteSales: Boolean get() = !isUnrecognized && (isAdmin || canDeleteSales)

    companion object {
        fun createAdmin(uid: String, email: String?, displayName: String?): FirestoreUserRole {
            val now = System.currentTimeMillis()
            return FirestoreUserRole(
                uid = uid,
                documentId = uid,
                email = email,
                displayName = displayName ?: email?.substringBefore("@") ?: "Admin",
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
                canDeleteSales = true,
                createdAt = now,
                updatedAt = now,
                lastActiveAt = now
            )
        }

        fun createEmployee(uid: String, email: String?, displayName: String?, assignedBy: String? = null): FirestoreUserRole {
            val cleanEmail = email?.trim()?.lowercase()
            if (cleanEmail != null && cleanEmail in PERMANENT_ADMIN_EMAILS) {
                return createAdmin(uid, email, displayName)
            }
            val now = System.currentTimeMillis()
            return FirestoreUserRole(
                uid = uid,
                documentId = uid,
                email = email,
                displayName = displayName ?: email?.substringBefore("@") ?: "Employee",
                role = AppRole.EMPLOYEE.roleName,
                status = "APPROVED",
                canViewReports = false, // Profit reports restricted for regular employees
                canViewCostPrice = false, // Cost price restricted for regular employees
                canManageInventory = false,
                canViewKhata = false,
                canManageExpenses = false,
                canAccessSettings = false,
                canMakeSales = true,
                canGiveDiscount = true,
                canDeleteSales = false,
                assignedBy = assignedBy,
                createdAt = now,
                updatedAt = now,
                lastActiveAt = now
            )
        }

        fun createManager(uid: String, email: String?, displayName: String?, assignedBy: String? = null): FirestoreUserRole {
            val cleanEmail = email?.trim()?.lowercase()
            if (cleanEmail != null && cleanEmail in PERMANENT_ADMIN_EMAILS) {
                return createAdmin(uid, email, displayName)
            }
            val now = System.currentTimeMillis()
            return FirestoreUserRole(
                uid = uid,
                documentId = uid,
                email = email,
                displayName = displayName ?: email?.substringBefore("@") ?: "Manager",
                role = AppRole.STORE_MANAGER.roleName,
                status = "APPROVED",
                canViewReports = true,
                canViewCostPrice = true,
                canManageInventory = true,
                canViewKhata = true,
                canManageExpenses = true,
                canAccessSettings = false,
                canMakeSales = true,
                canGiveDiscount = true,
                canDeleteSales = true,
                assignedBy = assignedBy,
                createdAt = now,
                updatedAt = now,
                lastActiveAt = now
            )
        }

        fun createUnassigned(uid: String, email: String?, displayName: String?): FirestoreUserRole {
            val cleanEmail = email?.trim()?.lowercase()
            if (cleanEmail != null && cleanEmail in PERMANENT_ADMIN_EMAILS) {
                return createAdmin(uid, email, displayName)
            }
            val now = System.currentTimeMillis()
            return FirestoreUserRole(
                uid = uid,
                documentId = uid,
                email = email,
                displayName = displayName ?: email?.substringBefore("@") ?: "New Staff Applicant",
                role = AppRole.UNASSIGNED.roleName,
                status = "PENDING",
                canViewReports = false,
                canViewCostPrice = false,
                canManageInventory = false,
                canViewKhata = false,
                canManageExpenses = false,
                canAccessSettings = false,
                canMakeSales = false,
                canGiveDiscount = false,
                canDeleteSales = false,
                assignedBy = null,
                createdAt = now,
                updatedAt = now,
                lastActiveAt = now
            )
        }

        fun createUnrecognized(uid: String, email: String?, displayName: String?): FirestoreUserRole {
            return createUnassigned(uid, email, displayName)
        }
    }
}

/**
 * Merges a group of duplicate Firestore user role documents representing the same real person.
 * Strictly prioritizes genuine Firebase Auth logins (UID-keyed) over email-keyed placeholders,
 * and retains the authentic Google / Auth display name and latest activity timestamps.
 */
fun mergeUserRoleGroup(roles: List<FirestoreUserRole>): FirestoreUserRole {
    if (roles.isEmpty()) return FirestoreUserRole()
    if (roles.size == 1) return roles.first()

    val primary = roles.sortedWith(
        compareByDescending<FirestoreUserRole> { it.hasRealAuthUid }
            .thenByDescending { !it.displayName.isNullOrBlank() && it.displayName != "App User" && it.displayName != "Employee" && it.displayName != "Unrecognized User" }
            .thenByDescending { it.lastActiveAt }
            .thenByDescending { it.updatedAt }
    ).first()

    // Find best display name from the group if primary has a placeholder or blank name
    val bestDisplayName = roles.mapNotNull { it.displayName?.trim() }
        .firstOrNull { it.isNotBlank() && it != "App User" && it != "Employee" && it != "Unrecognized User" }
        ?: primary.displayName

    // Find best role if primary is unrecognized but an invited/assigned role exists in group
    val bestRole = if (!primary.isUnrecognized) primary.role else {
        roles.map { it.role }.firstOrNull { !it.equals(AppRole.UNRECOGNIZED.roleName, ignoreCase = true) && it.isNotBlank() } ?: primary.role
    }

    return primary.copy(
        displayName = bestDisplayName,
        role = bestRole
    )
}
