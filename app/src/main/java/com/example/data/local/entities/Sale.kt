package com.example.data.local.entities

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Immutable
@Entity(tableName = "sales")
data class Sale(
    @PrimaryKey val id: String,
    val datetime: Long = System.currentTimeMillis(),
    val totalAmount: Double,
    val discount: Double = 0.0,
    val finalAmount: Double,
    val paymentMode: String, // "CASH", "UPI", "CREDIT"
    val customerId: String? = null,
    val customerName: String? = null,
    val isHeld: Boolean = false,
    val notes: String? = null,
    val receivedAmount: Double = finalAmount,
    val dueAmount: Double = 0.0,
    val previousBalance: Double = 0.0,
    val staffId: String? = null,
    val staffName: String? = null,
    val dueDate: Long? = null, // Payment due date (timestamp in ms) for credit sales
    val needsSync: Boolean = true
) {
    fun toMap(): Map<String, Any?> = hashMapOf(
        "id" to id,
        "datetime" to datetime,
        "totalAmount" to totalAmount,
        "discount" to discount,
        "finalAmount" to finalAmount,
        "paymentMode" to paymentMode,
        "customerId" to customerId,
        "customerName" to customerName,
        "isHeld" to isHeld,
        "notes" to notes,
        "receivedAmount" to receivedAmount,
        "dueAmount" to dueAmount,
        "previousBalance" to previousBalance,
        "staffId" to staffId,
        "staffName" to staffName,
        "dueDate" to dueDate
    )
}

@Immutable
@Entity(
    tableName = "sale_items",
    foreignKeys = [
        ForeignKey(
            entity = Sale::class,
            parentColumns = ["id"],
            childColumns = ["saleId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["saleId"])]
)
data class SaleItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val saleId: String,
    val productId: String,
    val productNameEn: String,
    val productNameBn: String,
    val unitType: String,
    val quantity: Double,
    val unitPrice: Double,
    val costPrice: Double,
    val subtotal: Double,
    val totalCost: Double,
    val mrp: Double = 0.0,
    val variantBarcode: String? = null
) {
    fun getEffectiveMrp(): Double = if (mrp > 0.0) mrp else unitPrice

    fun getSavingsAmount(): Double = ((getEffectiveMrp() - unitPrice) * quantity).coerceAtLeast(0.0)

    fun getUnitDiscount(): Double = (getEffectiveMrp() - unitPrice).coerceAtLeast(0.0)

    fun getDiscountPercent(): Double {
        val effMrp = getEffectiveMrp()
        return if (effMrp > unitPrice && effMrp > 0.0) {
            ((effMrp - unitPrice) / effMrp) * 100.0
        } else {
            0.0
        }
    }

    fun getMrpSubtotal(): Double = getEffectiveMrp() * quantity

    fun isFreeGift(): Boolean = (unitPrice == 0.0 && subtotal == 0.0) && (
        productNameEn.contains("Free Gift", ignoreCase = true) ||
        productNameBn.contains("ফ্রি উপহার") ||
        productNameEn.contains("[FREE GIFT]", ignoreCase = true)
    )

    fun hasDiscount(): Boolean = !isFreeGift() && mrp > unitPrice && mrp > 0.0

    fun toMap(): Map<String, Any?> = hashMapOf(
        "productId" to productId,
        "productNameEn" to productNameEn,
        "productNameBn" to productNameBn,
        "unitType" to unitType,
        "quantity" to quantity,
        "unitPrice" to unitPrice,
        "costPrice" to costPrice,
        "subtotal" to subtotal,
        "totalCost" to totalCost,
        "mrp" to mrp,
        "variantBarcode" to variantBarcode,
        "variant_barcode" to variantBarcode
    )
}
