package com.example.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Calendar

@Entity(
    tableName = "product_batches",
    indices = [Index(value = ["productId"]), Index(value = ["expiryDate"])]
)
data class ProductBatch(
    @PrimaryKey val id: String,
    val productId: String,
    val batchNumber: String, // e.g., "BATCH-2026-A", "LOT-802"
    val quantity: Double,    // Stock remaining in primary unit
    val expiryDate: String? = null, // Format: YYYY-MM-DD
    val mfgDate: String? = null,    // Format: YYYY-MM-DD
    val costPrice: Double? = null,  // Optional batch-specific cost price
    val sellingPrice: Double? = null, // Optional batch-specific selling price
    val addedTimestamp: Long = System.currentTimeMillis(),
    val needsSync: Boolean = true
) {
    fun getExpiryStatus(): ExpiryStatus {
        if (expiryDate.isNullOrBlank()) return ExpiryStatus.NO_EXPIRY
        return try {
            val parts = expiryDate.split("-")
            if (parts.size != 3) return ExpiryStatus.NO_EXPIRY
            val year = parts[0].toInt()
            val month = parts[1].toInt() - 1
            val day = parts[2].toInt()

            val cal = Calendar.getInstance()
            val today = Calendar.getInstance()
            today.set(Calendar.HOUR_OF_DAY, 0)
            today.set(Calendar.MINUTE, 0)
            today.set(Calendar.SECOND, 0)
            today.set(Calendar.MILLISECOND, 0)

            cal.set(year, month, day, 0, 0, 0)
            cal.set(Calendar.MILLISECOND, 0)

            val diffMs = cal.timeInMillis - today.timeInMillis
            val diffDays = (diffMs / (1000L * 60 * 60 * 24)).toInt()

            when {
                diffDays < 0 -> ExpiryStatus.EXPIRED
                diffDays <= 7 -> ExpiryStatus.EXPIRING_SOON
                else -> ExpiryStatus.FRESH
            }
        } catch (e: Exception) {
            ExpiryStatus.NO_EXPIRY
        }
    }

    fun getDaysUntilExpiry(): Int? {
        if (expiryDate.isNullOrBlank()) return null
        return try {
            val parts = expiryDate.split("-")
            if (parts.size != 3) return null
            val cal = Calendar.getInstance()
            val today = Calendar.getInstance()
            today.set(Calendar.HOUR_OF_DAY, 0)
            today.set(Calendar.MINUTE, 0)
            today.set(Calendar.SECOND, 0)
            today.set(Calendar.MILLISECOND, 0)

            cal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt(), 0, 0, 0)
            cal.set(Calendar.MILLISECOND, 0)

            val diffMs = cal.timeInMillis - today.timeInMillis
            ((diffMs) / (1000L * 60 * 60 * 24)).toInt()
        } catch (e: Exception) {
            null
        }
    }
}
