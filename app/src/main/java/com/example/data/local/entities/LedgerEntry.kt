package com.example.data.local.entities

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

@Immutable
@Entity(tableName = "ledger_entries")
data class LedgerEntry(
    @PrimaryKey val id: String,
    val partyType: String, // "CUSTOMER" or "SUPPLIER"
    val partyId: String,
    val partyName: String,
    val type: String,      // "SALE_CREDIT", "PAYMENT_RECEIVED", "PURCHASE_CREDIT", "PAYMENT_MADE"
    val amount: Double,
    val datetime: Long = System.currentTimeMillis(),
    val note: String? = null,
    val referenceId: String? = null, // Sale ID or Purchase ID if applicable
    val dueDate: Long? = null, // Payment due date (timestamp in ms) for credit entries
    val needsSync: Boolean = true
)

@Immutable
@Entity(tableName = "expenses")
data class Expense(
    @PrimaryKey val id: String,
    val date: Long = System.currentTimeMillis(),
    val category: String, // "Rent", "Electricity", "Wages", "Transport", "Misc"
    val amount: Double,
    val note: String? = null,
    val isRecurring: Boolean = false,
    val needsSync: Boolean = true
)
