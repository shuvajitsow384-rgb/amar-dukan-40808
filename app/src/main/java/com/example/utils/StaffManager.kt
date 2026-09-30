package com.example.utils

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.local.entities.Employee
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.staffDataStore: DataStore<Preferences> by preferencesDataStore(name = "staff_preferences")

object StaffManager {
    private val OWNER_PIN_KEY = stringPreferencesKey("owner_master_pin")
    private val ACTIVE_STAFF_ID_KEY = stringPreferencesKey("active_staff_id")

    private val scope = CoroutineScope(Dispatchers.IO)
    private var appContext: Context? = null

    // Owner Master PIN (Default: 1234)
    var ownerPin by mutableStateOf("1234")
        private set

    // Current logged in Staff (null means Store Owner / Admin mode)
    var activeStaff by mutableStateOf<Employee?>(null)
        private set

    // Persisted staff ID from DataStore
    var savedStaffId by mutableStateOf<String?>(null)
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        scope.launch {
            context.staffDataStore.data.collect { preferences ->
                ownerPin = preferences[OWNER_PIN_KEY] ?: "1234"
                savedStaffId = preferences[ACTIVE_STAFF_ID_KEY]
            }
        }
    }

    fun setOwnerMasterPin(newPin: String) {
        val cleanPin = newPin.trim()
        if (cleanPin.length >= 4) {
            ownerPin = cleanPin
            appContext?.let { ctx ->
                scope.launch {
                    ctx.staffDataStore.edit { preferences ->
                        preferences[OWNER_PIN_KEY] = cleanPin
                    }
                }
            }
        }
    }

    fun loginAsOwner(): Boolean {
        activeStaff = null
        savedStaffId = null
        appContext?.let { ctx ->
            scope.launch {
                ctx.staffDataStore.edit { preferences ->
                    preferences.remove(ACTIVE_STAFF_ID_KEY)
                }
            }
        }
        return true
    }

    fun clearStaffSession() {
        loginAsOwner()
    }

    fun loginAsStaff(employee: Employee) {
        activeStaff = employee
        savedStaffId = employee.id
        appContext?.let { ctx ->
            scope.launch {
                ctx.staffDataStore.edit { preferences ->
                    preferences[ACTIVE_STAFF_ID_KEY] = employee.id
                }
            }
        }
    }

    fun isOwnerMode(): Boolean = activeStaff == null
    fun isOwner(): Boolean = activeStaff == null

    /**
     * Requirement: Staff accounts require active internet connectivity on THIS specific device.
     * Owner / Admin sessions are 100% offline-capable with no internet requirement.
     */
    fun requiresInternet(): Boolean = activeStaff != null

    fun isStaffBlockedDueToOffline(): Boolean {
        return requiresInternet() && !NetworkMonitor.isOnline
    }

    fun getCurrentStaffDisplayName(): String {
        val staff = activeStaff
        if (staff != null) {
            return staff.name
        }
        val owner = StoreInfoManager.ownerName.trim()
        return if (owner.isNotBlank() && !owner.equals("Store Owner", ignoreCase = true) && !owner.equals("Owner", ignoreCase = true)) {
            owner
        } else {
            "Shuvajit Show"
        }
    }

    /**
     * Returns the name of the active staff member, or the owner/store name if in owner mode.
     * Never returns blank or null text.
     */
    fun getActiveStaffOrOwnerName(): String {
        val staff = activeStaff?.name?.trim()
        if (!staff.isNullOrBlank()) {
            return staff
        }
        val owner = StoreInfoManager.ownerName.trim()
        if (owner.isNotBlank() && !owner.equals("Store Owner", ignoreCase = true) && !owner.equals("Owner", ignoreCase = true)) {
            return owner
        }
        val store = StoreInfoManager.storeName.trim()
        if (store.isNotBlank() && !store.equals("My Store", ignoreCase = true)) {
            return store
        }
        return "Owner"
    }

    fun getCurrentStaffRoleLabel(): String {
        val staff = activeStaff ?: return "Owner / Admin"
        return when (staff.role) {
            "CASHIER" -> "Cashier"
            "STORE_MANAGER" -> "Store Manager"
            "SALES_STAFF" -> "Sales Staff"
            else -> "Staff (${staff.role})"
        }
    }

    // Permission checks
    fun canMakeSales(): Boolean = activeStaff?.canMakeSales ?: true
    fun canViewCostPrice(): Boolean = activeStaff?.canViewCostPrice ?: true
    fun canManageInventory(): Boolean = activeStaff?.canManageInventory ?: true
    fun canViewKhata(): Boolean = activeStaff?.canViewKhata ?: true
    fun canManageExpenses(): Boolean = activeStaff?.canManageExpenses ?: true
    fun canViewReports(): Boolean = activeStaff?.canViewReports ?: true
    fun canAccessSettings(): Boolean = activeStaff?.canAccessSettings ?: true
    fun canGiveDiscount(): Boolean = activeStaff?.canGiveDiscount ?: true
    fun canDeleteSales(): Boolean = activeStaff?.canDeleteSales ?: true

    fun verifyOwnerPin(inputPin: String): Boolean {
        return inputPin.trim() == ownerPin.trim()
    }
}
