package com.example.data.local.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "purchases")
data class Purchase(
    @PrimaryKey val id: String,
    val datetime: Long = System.currentTimeMillis(),
    val supplierId: String? = null,
    val supplierName: String? = null,
    val totalAmount: Double,
    val amountPaid: Double = totalAmount,
    val paidVia: String = "CASH", // "CASH", "UPI", "BANK"
    val dueAmount: Double = (totalAmount - amountPaid).coerceAtLeast(0.0),
    val previousBalance: Double = 0.0,
    val paymentMode: String = if (amountPaid >= totalAmount) paidVia else if (amountPaid > 0) "PARTIAL" else "CREDIT",
    val notes: String? = null,
    val needsSync: Boolean = true
)

@Entity(
    tableName = "purchase_items",
    foreignKeys = [
        ForeignKey(
            entity = Purchase::class,
            parentColumns = ["id"],
            childColumns = ["purchaseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["purchaseId"])]
)
data class PurchaseItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val purchaseId: String,
    val productId: String,
    val productNameEn: String,
    val productNameBn: String,
    val quantity: Double,
    val costPrice: Double,
    val subtotal: Double
)
