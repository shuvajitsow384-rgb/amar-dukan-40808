package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entities.Expense
import com.example.data.local.entities.LedgerEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface LedgerDao {
    @Query("SELECT * FROM ledger_entries WHERE partyType = :partyType AND partyId = :partyId ORDER BY datetime DESC")
    fun getLedgerForParty(partyType: String, partyId: String): Flow<List<LedgerEntry>>

    @Query("SELECT * FROM ledger_entries WHERE partyType = :partyType AND partyId = :partyId")
    suspend fun getLedgerEntriesForPartyList(partyType: String, partyId: String): List<LedgerEntry>

    @Query("SELECT * FROM ledger_entries ORDER BY datetime DESC")
    fun getAllLedgerEntries(): Flow<List<LedgerEntry>>

    @Query("SELECT * FROM ledger_entries ORDER BY datetime DESC")
    suspend fun getAllLedgerEntriesList(): List<LedgerEntry>

    @Query("SELECT * FROM ledger_entries WHERE referenceId = :referenceId")
    suspend fun getLedgerEntriesByReference(referenceId: String): List<LedgerEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLedgerEntry(entry: LedgerEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLedgerEntries(entries: List<LedgerEntry>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLedgerEntriesFromSync(entries: List<LedgerEntry>)

    @Query("SELECT * FROM ledger_entries WHERE needsSync = 1 ORDER BY datetime DESC")
    suspend fun getUnsyncedLedgerEntriesList(): List<LedgerEntry>

    @Query("UPDATE ledger_entries SET needsSync = 0 WHERE id IN (:ids)")
    suspend fun markLedgerEntriesSynced(ids: List<String>)

    @Delete
    suspend fun deleteLedgerEntry(entry: LedgerEntry)

    @Query("DELETE FROM ledger_entries WHERE referenceId = :referenceId")
    suspend fun deleteLedgerEntriesByReference(referenceId: String)
}

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expenses ORDER BY date DESC")
    fun getAllExpenses(): Flow<List<Expense>>

    @Query("SELECT * FROM expenses ORDER BY date DESC")
    suspend fun getAllExpensesList(): List<Expense>

    @Query("SELECT * FROM expenses WHERE date >= :startTime AND date <= :endTime")
    suspend fun getExpensesInRange(startTime: Long, endTime: Long): List<Expense>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpense(expense: Expense)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpenses(expenses: List<Expense>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpensesFromSync(expenses: List<Expense>)

    @Query("SELECT * FROM expenses WHERE needsSync = 1 ORDER BY date DESC")
    suspend fun getUnsyncedExpensesList(): List<Expense>

    @Query("UPDATE expenses SET needsSync = 0 WHERE id IN (:ids)")
    suspend fun markExpensesSynced(ids: List<String>)

    @Delete
    suspend fun deleteExpense(expense: Expense)
}
