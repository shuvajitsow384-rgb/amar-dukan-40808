package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entities.Offer
import kotlinx.coroutines.flow.Flow

@Dao
interface OfferDao {
    @Query("SELECT * FROM offers ORDER BY createdAt DESC")
    fun getAllOffers(): Flow<List<Offer>>

    @Query("SELECT * FROM offers ORDER BY createdAt DESC")
    suspend fun getAllOffersList(): List<Offer>

    @Query("SELECT * FROM offers WHERE isActive = 1 ORDER BY createdAt DESC")
    fun getActiveOffers(): Flow<List<Offer>>

    @Query("SELECT * FROM offers WHERE id = :id")
    suspend fun getOfferById(id: String): Offer?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOffer(offer: Offer)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOffers(offers: List<Offer>)

    @Update
    suspend fun updateOffer(offer: Offer)

    @Delete
    suspend fun deleteOffer(offer: Offer)

    @Query("DELETE FROM offers WHERE id = :id")
    suspend fun deleteOfferById(id: String)

    @Query("UPDATE offers SET isActive = :isActive, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setOfferActive(id: String, isActive: Boolean, updatedAt: Long = System.currentTimeMillis())
}
