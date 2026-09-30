package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entities.ReturnItem
import com.example.data.local.entities.SaleReturn
import com.example.data.local.entities.SaleReturnWithItems
import kotlinx.coroutines.flow.Flow

@Dao
interface SaleReturnDao {
    @Transaction
    @Query("SELECT * FROM sale_returns ORDER BY datetime DESC")
    fun getAllReturns(): Flow<List<SaleReturnWithItems>>

    @Transaction
    @Query("SELECT * FROM sale_returns WHERE saleId = :saleId ORDER BY datetime DESC")
    fun getReturnsForSale(saleId: String): Flow<List<SaleReturnWithItems>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReturn(saleReturn: SaleReturn)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReturnItems(items: List<ReturnItem>)

    @Query("DELETE FROM return_items WHERE returnId = :returnId")
    suspend fun deleteReturnItems(returnId: String)

    @Transaction
    suspend fun insertFullReturn(saleReturn: SaleReturn, items: List<ReturnItem>) {
        insertReturn(saleReturn)
        deleteReturnItems(saleReturn.id)
        if (items.isNotEmpty()) {
            insertReturnItems(items)
        }
    }

    @Query("DELETE FROM sale_returns WHERE id = :returnId")
    suspend fun deleteReturn(returnId: String)

    @Transaction
    @Query("SELECT * FROM sale_returns ORDER BY datetime DESC")
    suspend fun getAllReturnsList(): List<SaleReturnWithItems>

    @Transaction
    @Query("SELECT * FROM sale_returns WHERE needsSync = 1 ORDER BY datetime DESC")
    suspend fun getUnsyncedReturnsList(): List<SaleReturnWithItems>

    @Query("UPDATE sale_returns SET needsSync = 0 WHERE id IN (:ids)")
    suspend fun markReturnsSynced(ids: List<String>)

    @Transaction
    suspend fun insertFullReturnFromSync(saleReturn: SaleReturn, items: List<ReturnItem>) {
        insertReturn(saleReturn.copy(needsSync = false))
        deleteReturnItems(saleReturn.id)
        if (items.isNotEmpty()) {
            insertReturnItems(items)
        }
    }
}
