package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entities.PaymentClaim
import kotlinx.coroutines.flow.Flow

@Dao
interface PaymentClaimDao {

    @Query("SELECT * FROM payment_claims ORDER BY timestamp DESC")
    fun getAllPaymentClaims(): Flow<List<PaymentClaim>>

    @Query("SELECT * FROM payment_claims ORDER BY timestamp DESC")
    suspend fun getAllPaymentClaimsList(): List<PaymentClaim>

    @Query("SELECT * FROM payment_claims WHERE status = 'pending' ORDER BY timestamp DESC")
    fun getPendingPaymentClaims(): Flow<List<PaymentClaim>>

    @Query("SELECT * FROM payment_claims WHERE customerId = :customerId ORDER BY timestamp DESC")
    fun getPaymentClaimsByCustomer(customerId: String): Flow<List<PaymentClaim>>

    @Query("SELECT * FROM payment_claims WHERE id = :id LIMIT 1")
    suspend fun getPaymentClaimById(id: String): PaymentClaim?

    @Query("SELECT * FROM payment_claims WHERE shareToken = :shareToken ORDER BY timestamp DESC")
    suspend fun getPaymentClaimsByShareToken(shareToken: String): List<PaymentClaim>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPaymentClaim(claim: PaymentClaim)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPaymentClaims(claims: List<PaymentClaim>)

    @Update
    suspend fun updatePaymentClaim(claim: PaymentClaim)

    @Delete
    suspend fun deletePaymentClaim(claim: PaymentClaim)

    @Query("DELETE FROM payment_claims WHERE id = :id")
    suspend fun deletePaymentClaimById(id: String)
}
