package com.example.data.local.dao

import androidx.compose.runtime.Immutable
import androidx.room.*
import com.example.data.local.entities.Sale
import com.example.data.local.entities.SaleItem
import kotlinx.coroutines.flow.Flow

@Immutable
data class SaleWithItems(
    @Embedded val sale: Sale,
    @Relation(
        parentColumn = "id",
        entityColumn = "saleId"
    )
    val items: List<SaleItem>
) {
    val consolidatedItems: List<SaleItem>
        get() = com.example.utils.SaleConsolidationUtils.sanitizeSaleItems(sale, items)
}

@Dao
interface SaleDao {
    @Transaction
    @Query("SELECT * FROM sales WHERE isHeld = 0 ORDER BY datetime DESC")
    fun getAllSales(): Flow<List<SaleWithItems>>

    @Transaction
    @Query("SELECT * FROM sales WHERE isHeld = 1 ORDER BY datetime DESC")
    fun getHeldSales(): Flow<List<SaleWithItems>>

    @Transaction
    @Query("SELECT * FROM sales ORDER BY datetime DESC")
    suspend fun getAllSalesList(): List<SaleWithItems>

    @Transaction
    @Query("SELECT * FROM sales WHERE datetime >= :startTime AND datetime <= :endTime AND isHeld = 0")
    suspend fun getSalesInRange(startTime: Long, endTime: Long): List<SaleWithItems>

    @Transaction
    @Query("SELECT * FROM sales WHERE id = :saleId LIMIT 1")
    suspend fun getSaleById(saleId: String): SaleWithItems?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSale(sale: Sale)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSaleItems(items: List<SaleItem>)

    @Query("DELETE FROM sales WHERE id = :saleId")
    suspend fun deleteSale(saleId: String)

    @Query("DELETE FROM sale_items WHERE saleId = :saleId")
    suspend fun deleteSaleItems(saleId: String)

    @Transaction
    suspend fun replaceSaleWithItems(sale: Sale, items: List<SaleItem>) {
        insertSale(sale)
        deleteSaleItems(sale.id)
        if (items.isNotEmpty()) {
            val cleanItems = items.map { it.copy(id = 0, saleId = sale.id) }
            insertSaleItems(cleanItems)
        }
    }

    @Transaction
    @Query("SELECT * FROM sales WHERE needsSync = 1 ORDER BY datetime DESC")
    suspend fun getUnsyncedSalesList(): List<SaleWithItems>

    @Query("UPDATE sales SET needsSync = 0 WHERE id IN (:ids)")
    suspend fun markSalesSynced(ids: List<String>)

    @Transaction
    suspend fun replaceSaleWithItemsFromSync(sale: Sale, items: List<SaleItem>) {
        insertSale(sale.copy(needsSync = false))
        deleteSaleItems(sale.id)
        if (items.isNotEmpty()) {
            val cleanItems = items.map { it.copy(id = 0, saleId = sale.id) }
            insertSaleItems(cleanItems)
        }
    }

    @Query("UPDATE sales SET staffId = :newStaffId, staffName = :newStaffName, needsSync = 1 WHERE staffId = :oldStaffId")
    suspend fun repointStaffId(oldStaffId: String, newStaffId: String, newStaffName: String): Int

    @Query("UPDATE sales SET staffId = :newStaffId, staffName = :newStaffName, needsSync = 1 WHERE (staffId IS NULL OR staffId = '') AND LOWER(TRIM(staffName)) = LOWER(TRIM(:oldNameOrEmail))")
    suspend fun repointStaffByName(oldNameOrEmail: String, newStaffId: String, newStaffName: String): Int
}
