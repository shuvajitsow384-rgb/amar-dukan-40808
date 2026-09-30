package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entities.ProductBatch
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductBatchDao {
    @Query("SELECT * FROM product_batches WHERE productId = :productId ORDER BY CASE WHEN expiryDate IS NULL THEN 1 ELSE 0 END, expiryDate ASC, addedTimestamp ASC")
    fun getBatchesForProduct(productId: String): Flow<List<ProductBatch>>

    @Query("SELECT * FROM product_batches WHERE productId = :productId ORDER BY CASE WHEN expiryDate IS NULL THEN 1 ELSE 0 END, expiryDate ASC, addedTimestamp ASC")
    suspend fun getBatchesForProductList(productId: String): List<ProductBatch>

    @Query("SELECT * FROM product_batches ORDER BY CASE WHEN expiryDate IS NULL THEN 1 ELSE 0 END, expiryDate ASC")
    fun getAllBatches(): Flow<List<ProductBatch>>

    @Query("SELECT * FROM product_batches ORDER BY CASE WHEN expiryDate IS NULL THEN 1 ELSE 0 END, expiryDate ASC")
    suspend fun getAllBatchesList(): List<ProductBatch>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBatch(batch: ProductBatch)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBatches(batches: List<ProductBatch>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBatchesFromSync(batches: List<ProductBatch>)

    @Query("SELECT * FROM product_batches WHERE needsSync = 1")
    suspend fun getUnsyncedBatchesList(): List<ProductBatch>

    @Query("UPDATE product_batches SET needsSync = 0 WHERE id IN (:ids)")
    suspend fun markBatchesSynced(ids: List<String>)

    @Update
    suspend fun updateBatchRaw(batch: ProductBatch)

    suspend fun updateBatch(batch: ProductBatch) {
        updateBatchRaw(batch.copy(needsSync = true))
    }

    @Query("DELETE FROM product_batches WHERE id = :id")
    suspend fun deleteBatch(id: String)

    @Query("DELETE FROM product_batches WHERE productId = :productId")
    suspend fun deleteBatchesForProduct(productId: String)
}
