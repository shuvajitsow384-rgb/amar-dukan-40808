package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entities.StockOutEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface StockOutDao {
    @Query("SELECT * FROM stock_out_entries ORDER BY timestamp DESC")
    fun getAllStockOuts(): Flow<List<StockOutEntry>>

    @Query("SELECT * FROM stock_out_entries ORDER BY timestamp DESC")
    suspend fun getAllStockOutsList(): List<StockOutEntry>

    @Query("SELECT * FROM stock_out_entries WHERE timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp DESC")
    suspend fun getStockOutsInRange(startTime: Long, endTime: Long): List<StockOutEntry>

    @Query("SELECT * FROM stock_out_entries WHERE timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp DESC")
    fun getStockOutsInRangeFlow(startTime: Long, endTime: Long): Flow<List<StockOutEntry>>

    @Query("SELECT * FROM stock_out_entries WHERE productId = :productId ORDER BY timestamp DESC")
    suspend fun getStockOutsForProduct(productId: String): List<StockOutEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStockOut(entry: StockOutEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStockOuts(entries: List<StockOutEntry>)

    @Delete
    suspend fun deleteStockOut(entry: StockOutEntry)

    @Query("DELETE FROM stock_out_entries WHERE id = :id")
    suspend fun deleteStockOutById(id: String)
}
