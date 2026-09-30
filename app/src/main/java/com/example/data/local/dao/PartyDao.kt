package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entities.Customer
import com.example.data.local.entities.Supplier
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomerDao {
    @Query("SELECT * FROM customers ORDER BY name ASC")
    fun getAllCustomers(): Flow<List<Customer>>

    @Query("SELECT * FROM customers ORDER BY name ASC")
    suspend fun getAllCustomersList(): List<Customer>

    @Query("SELECT * FROM customers WHERE id = :id")
    suspend fun getCustomerById(id: String): Customer?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomer(customer: Customer)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomers(customers: List<Customer>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomersFromSync(customers: List<Customer>)

    @Query("SELECT * FROM customers WHERE needsSync = 1 ORDER BY name ASC")
    suspend fun getUnsyncedCustomersList(): List<Customer>

    @Query("UPDATE customers SET needsSync = 0 WHERE id IN (:ids)")
    suspend fun markCustomersSynced(ids: List<String>)

    @Update
    suspend fun updateCustomerRaw(customer: Customer)

    suspend fun updateCustomer(customer: Customer) {
        updateCustomerRaw(customer.copy(needsSync = true))
    }

    @Query("UPDATE customers SET balance = balance + :amountDelta, needsSync = 1 WHERE id = :customerId")
    suspend fun updateBalance(customerId: String, amountDelta: Double)

    @Delete
    suspend fun deleteCustomer(customer: Customer)
}

@Dao
interface SupplierDao {
    @Query("SELECT * FROM suppliers ORDER BY name ASC")
    fun getAllSuppliers(): Flow<List<Supplier>>

    @Query("SELECT * FROM suppliers ORDER BY name ASC")
    suspend fun getAllSuppliersList(): List<Supplier>

    @Query("SELECT * FROM suppliers WHERE id = :id")
    suspend fun getSupplierById(id: String): Supplier?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSupplier(supplier: Supplier)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSuppliers(suppliers: List<Supplier>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSuppliersFromSync(suppliers: List<Supplier>)

    @Query("SELECT * FROM suppliers WHERE needsSync = 1 ORDER BY name ASC")
    suspend fun getUnsyncedSuppliersList(): List<Supplier>

    @Query("UPDATE suppliers SET needsSync = 0 WHERE id IN (:ids)")
    suspend fun markSuppliersSynced(ids: List<String>)

    @Update
    suspend fun updateSupplierRaw(supplier: Supplier)

    suspend fun updateSupplier(supplier: Supplier) {
        updateSupplierRaw(supplier.copy(needsSync = true))
    }

    @Query("UPDATE suppliers SET balance = balance + :amountDelta, needsSync = 1 WHERE id = :supplierId")
    suspend fun updateBalance(supplierId: String, amountDelta: Double)

    @Delete
    suspend fun deleteSupplier(supplier: Supplier)
}
