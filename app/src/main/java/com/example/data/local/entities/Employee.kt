package com.example.data.local.entities

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Immutable
@Entity(tableName = "employees")
data class Employee(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val email: String = "",
    val phone: String = "",
    val pin: String = "1234",
    val role: String = "CASHIER", // "CASHIER", "STORE_MANAGER", "SALES_STAFF", "HELPER", "DELIVERY", "CUSTOM"
    val designation: String = "Staff",
    val salaryType: String = "MONTHLY", // "MONTHLY", "DAILY", "HOURLY"
    val baseSalary: Double = 0.0,
    val joiningDate: Long = System.currentTimeMillis(),
    val address: String = "",
    val emergencyContact: String = "",
    val notes: String = "",
    val canMakeSales: Boolean = true,
    val canViewCostPrice: Boolean = false,
    val canManageInventory: Boolean = false,
    val canViewKhata: Boolean = false,
    val canManageExpenses: Boolean = false,
    val canViewReports: Boolean = false,
    val canAccessSettings: Boolean = false,
    val canGiveDiscount: Boolean = true,
    val canDeleteSales: Boolean = false,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val needsSync: Boolean = true
)

