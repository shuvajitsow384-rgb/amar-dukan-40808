package com.example.data.local.entities

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

@Immutable
@Entity(tableName = "payment_claims")
data class PaymentClaim(
    @PrimaryKey val id: String,
    val customerId: String,
    val customerName: String,
    val customerPhone: String = "",
    val shareToken: String = "",
    val claimedAmount: Double = 0.0,
    val dueBalanceAtClaim: Double = 0.0,
    val timestamp: Long = System.currentTimeMillis(),
    val status: String = STATUS_PENDING, // "pending", "confirmed", "rejected", "expired"
    val note: String? = null,
    val rejectionReason: String? = null,
    val confirmedAt: Long? = null,
    val confirmedBy: String? = null,
    val rejectedAt: Long? = null,
    val rejectedBy: String? = null,
    val screenshotData: String? = null,
    val screenshotUrl: String? = null
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_CONFIRMED = "confirmed"
        const val STATUS_REJECTED = "rejected"
        const val STATUS_EXPIRED = "expired"

        const val EXPIRY_DURATION_MS = 48 * 60 * 60 * 1000L // 48 hours
    }

    val isPending: Boolean get() = status == STATUS_PENDING && !isExpiredByTime()
    val isConfirmed: Boolean get() = status == STATUS_CONFIRMED
    val isRejected: Boolean get() = status == STATUS_REJECTED
    val isExpired: Boolean get() = status == STATUS_EXPIRED || (status == STATUS_PENDING && isExpiredByTime())

    fun isExpiredByTime(): Boolean {
        return System.currentTimeMillis() - timestamp > EXPIRY_DURATION_MS
    }

    fun getEffectiveStatus(): String {
        return if (status == STATUS_PENDING && isExpiredByTime()) STATUS_EXPIRED else status
    }

    fun getRemainingHours(): Int {
        val elapsed = System.currentTimeMillis() - timestamp
        val remainingMs = EXPIRY_DURATION_MS - elapsed
        return if (remainingMs > 0) (remainingMs / (60 * 60 * 1000L)).toInt() else 0
    }
}
