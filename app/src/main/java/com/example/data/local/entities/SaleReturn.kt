package com.example.data.local.entities

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "sale_returns")
data class SaleReturn(
    @PrimaryKey val id: String, // e.g. "RET-123456"
    val saleId: String,
    val datetime: Long = System.currentTimeMillis(),
    val type: String = "RETURN", // "RETURN" or "REPLACEMENT"
    val customerId: String? = null,
    val customerName: String? = null,
    val totalReturnedAmount: Double = 0.0,
    val totalReplacementAmount: Double = 0.0,
    val netAmount: Double = 0.0, // positive if refund to customer, negative if customer paid extra
    val refundPaymentMode: String = "CASH", // "CASH", "UPI", "CREDIT"
    val notes: String? = null,
    val needsSync: Boolean = true
)

@Entity(
    tableName = "return_items",
    foreignKeys = [
        ForeignKey(
            entity = SaleReturn::class,
            parentColumns = ["id"],
            childColumns = ["returnId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["returnId"])]
)
data class ReturnItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val returnId: String,
    val productId: String,
    val productNameEn: String,
    val productNameBn: String,
    val unitType: String,
    val quantity: Double,
    val unitPrice: Double,
    val subtotal: Double,
    val isReplacement: Boolean = false // false = returned item from customer, true = replacement item given
)

data class SaleReturnWithItems(
    @Embedded val saleReturn: SaleReturn,
    @Relation(
        parentColumn = "id",
        entityColumn = "returnId"
    )
    val items: List<ReturnItem>
)
