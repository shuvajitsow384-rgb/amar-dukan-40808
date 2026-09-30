package com.example.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class OfferType(val displayNameEn: String, val displayNameBn: String) {
    PERCENT_DISCOUNT("Percentage (%) Discount", "শতাংশ (%) ছাড়"),
    FLAT_DISCOUNT("Flat (₹) Discount", "নির্দিষ্ট (₹) ছাড়"),
    BUY_X_GET_Y("Buy X Get Y (BOGO)", "X কিনুন Y পান"),
    FREE_GIFT("Free Gift with Purchase", "কেনাকাটায় ফ্রি উপহার"),
    COMBO_BUNDLE("Combo Bundle", "কম্বো বান্ডিল")
}

enum class OfferStatus(val labelEn: String, val labelBn: String) {
    ACTIVE("Active", "সক্রিয়"),
    UPCOMING("Upcoming", "আসন্ন"),
    EXPIRED("Expired", "মেয়াদ শেষ"),
    INACTIVE("Inactive", "নিষ্ক্রিয়")
}

@Entity(tableName = "offers")
data class Offer(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: String, // PERCENT_DISCOUNT, FLAT_DISCOUNT, BUY_X_GET_Y, FREE_GIFT, COMBO_BUNDLE
    val applicableProductIds: String, // Comma-separated product IDs e.g. "prod1,prod2" or blank for store-wide
    val discountValue: Double = 0.0, // Percentage e.g. 10.0 for 10%, or Flat amount e.g. 10.0 for ₹10 off
    val buyQty: Double = 0.0, // For BUY_X_GET_Y
    val getQty: Double = 0.0, // For BUY_X_GET_Y
    val getDiscountPercent: Double = 100.0, // 100.0 = free, < 100 = partial discount
    val comboPrice: Double = 0.0, // For COMBO_BUNDLE
    val comboProductsJson: String = "", // For COMBO_BUNDLE
    val startDate: String? = null, // Optional: YYYY-MM-DD
    val endDate: String? = null, // Optional: YYYY-MM-DD
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    // Free Gift with Purchase: "When customer buys a certain amount of products, give them something for free"
    val minSpendAmount: Double = 0.0, // Minimum spend/purchase amount in ₹ to qualify (e.g. ₹500, ₹1000)
    val freeProductId: String? = null, // Product ID from inventory to give for free
    val freeProductQty: Double = 1.0, // Quantity of the free product given (default 1)
    val freeProductUnit: String? = null // Unit type for the free gift (e.g. piece, box, kg, gram, packet). If null/blank, defaults to product's unitType
) {
    fun getProductIdsList(): List<String> {
        if (applicableProductIds.isBlank()) return emptyList()
        return applicableProductIds.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    fun getEffectiveStatus(todayStr: String = getTodayDateString()): OfferStatus {
        if (!isActive) return OfferStatus.INACTIVE
        val start = startDate?.trim()?.takeIf { it.isNotEmpty() }
        val end = endDate?.trim()?.takeIf { it.isNotEmpty() }

        if (start != null && todayStr < start) {
            return OfferStatus.UPCOMING
        }
        if (end != null && todayStr > end) {
            return OfferStatus.EXPIRED
        }
        return OfferStatus.ACTIVE
    }

    fun isCurrentlyApplying(todayStr: String = getTodayDateString()): Boolean {
        return getEffectiveStatus(todayStr) == OfferStatus.ACTIVE
    }

    fun appliesToProduct(productId: String, todayStr: String = getTodayDateString()): Boolean {
        if (!isCurrentlyApplying(todayStr)) return false
        val list = getProductIdsList()
        return list.isEmpty() || list.contains(productId)
    }

    fun isStoreWide(): Boolean = applicableProductIds.isBlank()

    fun getBadgeText(isBn: Boolean = false): String {
        return when (getOfferTypeEnum()) {
            OfferType.PERCENT_DISCOUNT -> {
                val pStr = if (discountValue % 1.0 == 0.0) "${discountValue.toInt()}%" else "%.1f%%".format(discountValue)
                if (isBn) "$pStr ছাড়" else "$pStr OFF"
            }
            OfferType.FLAT_DISCOUNT -> {
                val fStr = if (discountValue % 1.0 == 0.0) "₹${discountValue.toInt()}" else "₹%.2f".format(discountValue)
                if (isBn) "$fStr ছাড়" else "$fStr OFF"
            }
            OfferType.BUY_X_GET_Y -> {
                val bStr = if (buyQty % 1.0 == 0.0) "${buyQty.toInt()}" else "%.1f".format(buyQty)
                val gStr = if (getQty % 1.0 == 0.0) "${getQty.toInt()}" else "%.1f".format(getQty)
                if (isBn) "$bStr টি কিনলে $gStr টি ফ্রি" else "BUY $bStr GET $gStr FREE"
            }
            OfferType.FREE_GIFT -> {
                val spendStr = if (minSpendAmount % 1.0 == 0.0) "₹${minSpendAmount.toInt()}" else "₹%.2f".format(minSpendAmount)
                if (isBn) "$spendStr এ উপহার" else "Free Gift on $spendStr+"
            }
            OfferType.COMBO_BUNDLE -> {
                if (isBn) "কম্বো অফার" else "COMBO"
            }
        }
    }

    fun getOfferTypeEnum(): OfferType {
        return try {
            OfferType.valueOf(type)
        } catch (e: Exception) {
            OfferType.PERCENT_DISCOUNT
        }
    }

    companion object {
        fun getTodayDateString(): String {
            return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        }
    }
}

data class AppliedOfferDiscount(
    val offer: Offer,
    val discountAmount: Double,
    val description: String,
    val freeQty: Double = 0.0,
    val isBogo: Boolean = false,
    val isLossLeader: Boolean = false,
    val lossAmount: Double = 0.0
) {
    fun getFormattedBadge(isBn: Boolean): String {
        val label = if (offer.name.isNotBlank() && offer.name.trim().length > 1 && !offer.name.trim().equals("x", ignoreCase = true)) {
            offer.name.trim()
        } else if (isBogo) {
            val buyQStr = if (offer.buyQty % 1.0 == 0.0) "${offer.buyQty.toInt()}" else "%.1f".format(offer.buyQty)
            val getQStr = if (offer.getQty % 1.0 == 0.0) "${offer.getQty.toInt()}" else "%.1f".format(offer.getQty)
            if (isBn) "কিনুন $buyQStr পান $getQStr" else "Buy $buyQStr Get $getQStr"
        } else {
            if (isBn) "অফার" else "Offer"
        }

        return if (isBogo && freeQty > 0.0) {
            val freeQtyStr = if (freeQty % 1.0 == 0.0) "${freeQty.toInt()}" else "%.1f".format(freeQty)
            if (isBn) {
                "$label: $freeQtyStr টি ফ্রি"
            } else {
                "$label: $freeQtyStr Free"
            }
        } else {
            label
        }
    }
}
