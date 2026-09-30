package com.example.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "stock_out_entries")
data class StockOutEntry(
    @PrimaryKey val id: String = "SO_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().take(6),
    val productId: String,
    val productNameEn: String,
    val productNameBn: String? = null,
    val quantity: Double,
    val unitType: String,
    val costPrice: Double, // Cost price at the time of stock-out
    val totalCostValue: Double = quantity * costPrice,
    val reason: String, // "Damaged / ক্ষতিগ্রস্ত", "Expired / মেয়াদ উত্তীর্ণ", "Wastage / অপচয়", "Personal Use / নিজস্ব ব্যবহার", "Other / অন্যান্য"
    val note: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * Checks if this stock removal is a genuine business loss (Damaged, Expired, Wastage).
     */
    fun isBusinessLoss(): Boolean {
        val r = reason.lowercase()
        return r.contains("damage") || r.contains("ক্ষতিগ্রস্ত") ||
               r.contains("expire") || r.contains("মেয়াদ উত্তীর্ণ") ||
               r.contains("wastage") || r.contains("অপচয়")
    }

    /**
     * Checks if this stock removal was for the owner's personal draw/use.
     * Personal use is not a wasted stock loss.
     */
    fun isPersonalUse(): Boolean {
        val r = reason.lowercase()
        return r.contains("personal") || r.contains("নিজস্ব")
    }

    /**
     * Standardized reason category code: DAMAGED, EXPIRED, WASTAGE, PERSONAL_USE, OTHER
     */
    fun getReasonCategory(): ReasonCategory {
        val r = reason.lowercase()
        return when {
            r.contains("damage") || r.contains("ক্ষতিগ্রস্ত") -> ReasonCategory.DAMAGED
            r.contains("expire") || r.contains("মেয়াদ উত্তীর্ণ") -> ReasonCategory.EXPIRED
            r.contains("wastage") || r.contains("অপচয়") -> ReasonCategory.WASTAGE
            r.contains("personal") || r.contains("নিজস্ব") -> ReasonCategory.PERSONAL_USE
            else -> ReasonCategory.OTHER
        }
    }

    fun getDisplayName(isBn: Boolean): String {
        return if (isBn && !productNameBn.isNullOrBlank()) productNameBn else productNameEn
    }

    fun getFormattedReason(isBn: Boolean = false): String {
        return when (getReasonCategory()) {
            ReasonCategory.DAMAGED -> if (isBn) "ক্ষতিগ্রস্ত (Damaged)" else "Damaged"
            ReasonCategory.EXPIRED -> if (isBn) "মেয়াদ উত্তীর্ণ (Expired)" else "Expired"
            ReasonCategory.WASTAGE -> if (isBn) "অপচয় (Wastage)" else "Wastage"
            ReasonCategory.PERSONAL_USE -> if (isBn) "ব্যক্তিগত ব্যবহার (Personal Use)" else "Personal Use"
            ReasonCategory.OTHER -> if (isBn) "অন্যান্য (Other)" else "Other"
        }
    }

    enum class ReasonCategory {
        DAMAGED,
        EXPIRED,
        WASTAGE,
        PERSONAL_USE,
        OTHER
    }
}
