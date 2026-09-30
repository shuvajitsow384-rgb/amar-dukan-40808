package com.example.data.local.entities

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

@Immutable
@Entity(tableName = "customers")
data class Customer(
    @PrimaryKey val id: String,
    val name: String,
    val phone: String,
    val balance: Double = 0.0, // Positive balance = customer owes money to shop (receivable)
    val photoUri: String? = null,
    val creditLimit: Double? = null, // Maximum credit allowed in ₹ (null or <= 0 means unlimited)
    val shareToken: String? = null, // Secure unguessable token for public shared ledger link
    val interestExempt: Boolean = false, // If true, interest is never charged regardless of global store setting
    val customInterestRate: Double? = null, // Per-customer monthly interest rate override (% per month)
    val customGracePeriodDays: Int? = null, // Per-customer grace period override in days
    val needsSync: Boolean = true
) {
    fun hasShareToken(): Boolean = !shareToken.isNullOrBlank()
    fun hasCreditLimit(): Boolean = creditLimit != null && creditLimit > 0.0

    fun isOverCreditLimit(additionalAmount: Double = 0.0): Boolean {
        val limit = creditLimit ?: return false
        if (limit <= 0.0) return false
        return (balance + additionalAmount) > limit
    }

    fun getAvailableCredit(): Double {
        val limit = creditLimit ?: return Double.MAX_VALUE
        if (limit <= 0.0) return Double.MAX_VALUE
        return (limit - balance).coerceAtLeast(0.0)
    }

    fun getCreditUtilizationPercent(): Float {
        val limit = creditLimit ?: return 0f
        if (limit <= 0.0) return 0f
        return ((balance / limit).toFloat()).coerceIn(0f, 2f)
    }
}

@Immutable
@Entity(tableName = "suppliers")
data class Supplier(
    @PrimaryKey val id: String,
    val name: String,
    val phone: String,
    val balance: Double = 0.0, // Positive balance = shop owes money to supplier (payable)
    val address: String? = null,
    val gstin: String? = null,
    val notes: String? = null,
    val photoUri: String? = null,
    val needsSync: Boolean = true
)
