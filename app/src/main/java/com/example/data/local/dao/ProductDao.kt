package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entities.Product
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductDao {
    @Query("SELECT * FROM products ORDER BY nameEn ASC")
    fun getAllProducts(): Flow<List<Product>>

    @Query("SELECT COUNT(*) FROM products")
    suspend fun getProductsCount(): Int

    @Query("SELECT * FROM products ORDER BY nameEn ASC")
    suspend fun getAllProductsList(): List<Product>

    @Query("SELECT * FROM products WHERE id = :id")
    suspend fun getProductById(id: String): Product?

    @Query("SELECT * FROM products WHERE currentStock <= lowStockThreshold ORDER BY currentStock ASC")
    fun getLowStockProducts(): Flow<List<Product>>

    @Query("SELECT COUNT(*) FROM products WHERE currentStock <= lowStockThreshold")
    suspend fun getLowStockProductsCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProduct(product: Product)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProducts(products: List<Product>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProductsFromSync(products: List<Product>)

    @Query("SELECT * FROM products WHERE needsSync = 1 ORDER BY nameEn ASC")
    suspend fun getUnsyncedProductsList(): List<Product>

    @Query("SELECT COUNT(*) FROM products WHERE needsSync = 1")
    suspend fun getUnsyncedProductsCount(): Int

    @Query("UPDATE products SET needsSync = 0 WHERE id IN (:ids)")
    suspend fun markProductsSynced(ids: List<String>)

    @Update
    suspend fun updateProductRaw(product: Product)

    suspend fun updateProduct(product: Product) {
        updateProductRaw(product.copy(needsSync = true))
    }

    @Delete
    suspend fun deleteProduct(product: Product)

    @Query("UPDATE products SET currentStock = currentStock - :qty, needsSync = 1 WHERE id = :id")
    suspend fun deductStock(id: String, qty: Double)

    @Query("UPDATE products SET currentStock = currentStock + :qty, needsSync = 1 WHERE id = :id")
    suspend fun addStock(id: String, qty: Double)

    @Query("UPDATE products SET category = :newCategory, needsSync = 1 WHERE category = :oldCategory")
    suspend fun updateCategoryName(oldCategory: String, newCategory: String)

    @Query("UPDATE products SET category = :replacementCategory, needsSync = 1 WHERE category = :categoryToDelete")
    suspend fun reassignCategory(categoryToDelete: String, replacementCategory: String = "General")
}
