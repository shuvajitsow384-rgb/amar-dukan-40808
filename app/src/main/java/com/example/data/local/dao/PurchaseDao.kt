package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entities.Purchase
import com.example.data.local.entities.PurchaseItem
import kotlinx.coroutines.flow.Flow

data class PurchaseWithItems(
    @Embedded val purchase: Purchase,
    @Relation(
        parentColumn = "id",
        entityColumn = "purchaseId"
    )
    val items: List<PurchaseItem>
) {
    val consolidatedItems: List<PurchaseItem>
        get() = com.example.utils.SaleConsolidationUtils.sanitizePurchaseItems(purchase, items)
}

@Dao
interface PurchaseDao {
    @Transaction
    @Query("SELECT * FROM purchases ORDER BY datetime DESC")
    fun getAllPurchases(): Flow<List<PurchaseWithItems>>

    @Transaction
    @Query("SELECT * FROM purchases ORDER BY datetime DESC")
    suspend fun getAllPurchasesList(): List<PurchaseWithItems>

    @Transaction
    @Query("SELECT * FROM purchases WHERE datetime >= :startTime AND datetime <= :endTime")
    suspend fun getPurchasesInRange(startTime: Long, endTime: Long): List<PurchaseWithItems>

    @Transaction
    @Query("SELECT * FROM purchases WHERE id = :purchaseId LIMIT 1")
    suspend fun getPurchaseById(purchaseId: String): PurchaseWithItems?

    @Transaction
    @Query("SELECT * FROM purchases WHERE supplierId = :supplierId ORDER BY datetime DESC")
    fun getPurchasesForSupplier(supplierId: String): Flow<List<PurchaseWithItems>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPurchase(purchase: Purchase)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPurchaseItems(items: List<PurchaseItem>)

    @Query("DELETE FROM purchases WHERE id = :purchaseId")
    suspend fun deletePurchase(purchaseId: String)

    @Query("DELETE FROM purchase_items WHERE purchaseId = :purchaseId")
    suspend fun deletePurchaseItems(purchaseId: String)

    @Transaction
    suspend fun replacePurchaseWithItems(purchase: Purchase, items: List<PurchaseItem>) {
        insertPurchase(purchase)
        deletePurchaseItems(purchase.id)
        if (items.isNotEmpty()) {
            val cleanItems = items.map { it.copy(id = 0, purchaseId = purchase.id) }
            insertPurchaseItems(cleanItems)
        }
    }

    @Transaction
    @Query("SELECT * FROM purchases WHERE needsSync = 1 ORDER BY datetime DESC")
    suspend fun getUnsyncedPurchasesList(): List<PurchaseWithItems>

    @Query("UPDATE purchases SET needsSync = 0 WHERE id IN (:ids)")
    suspend fun markPurchasesSynced(ids: List<String>)

    @Transaction
    suspend fun replacePurchaseWithItemsFromSync(purchase: Purchase, items: List<PurchaseItem>) {
        insertPurchase(purchase.copy(needsSync = false))
        deletePurchaseItems(purchase.id)
        if (items.isNotEmpty()) {
            val cleanItems = items.map { it.copy(id = 0, purchaseId = purchase.id) }
            insertPurchaseItems(cleanItems)
        }
    }
}
