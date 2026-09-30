package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entities.EmployeeAdvance
import com.example.data.local.entities.EmployeeSalaryDue
import com.example.data.local.entities.EmployeeSalaryPayment
import kotlinx.coroutines.flow.Flow

@Dao
interface EmployeeSalaryDao {
    // --- Salary Dues (Accruals) ---
    @Query("SELECT * FROM employee_salary_dues ORDER BY dueDate DESC")
    fun getAllSalaryDues(): Flow<List<EmployeeSalaryDue>>

    @Query("SELECT * FROM employee_salary_dues ORDER BY dueDate DESC")
    suspend fun getAllSalaryDuesList(): List<EmployeeSalaryDue>

    @Query("SELECT * FROM employee_salary_dues WHERE employeeId = :employeeId ORDER BY dueDate DESC")
    fun getSalaryDuesForEmployee(employeeId: String): Flow<List<EmployeeSalaryDue>>

    @Query("SELECT * FROM employee_salary_dues WHERE monthYear = :monthYear")
    fun getSalaryDuesForMonth(monthYear: String): Flow<List<EmployeeSalaryDue>>

    @Query("SELECT * FROM employee_salary_dues WHERE employeeId = :employeeId AND monthYear = :monthYear LIMIT 1")
    suspend fun getSalaryDueForEmployeeAndMonth(employeeId: String, monthYear: String): EmployeeSalaryDue?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSalaryDue(due: EmployeeSalaryDue)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSalaryDueBatch(dues: List<EmployeeSalaryDue>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSalaryDueBatchFromSync(dues: List<EmployeeSalaryDue>)

    @Query("SELECT * FROM employee_salary_dues WHERE needsSync = 1")
    suspend fun getUnsyncedSalaryDuesList(): List<EmployeeSalaryDue>

    @Query("UPDATE employee_salary_dues SET needsSync = 0 WHERE id IN (:ids)")
    suspend fun markSalaryDuesSynced(ids: List<String>)

    @Update
    suspend fun updateSalaryDueRaw(due: EmployeeSalaryDue)

    suspend fun updateSalaryDue(due: EmployeeSalaryDue) {
        updateSalaryDueRaw(due.copy(needsSync = true))
    }

    @Delete
    suspend fun deleteSalaryDue(due: EmployeeSalaryDue)

    @Query("DELETE FROM employee_salary_dues WHERE id = :id")
    suspend fun deleteSalaryDueById(id: String)

    @Query("DELETE FROM employee_salary_dues WHERE employeeId = :employeeId")
    suspend fun deleteSalaryDuesByEmployeeId(employeeId: String): Int

    // --- Salary Payments ---
    @Query("SELECT * FROM employee_salary_payments ORDER BY paymentDate DESC")
    fun getAllSalaryPayments(): Flow<List<EmployeeSalaryPayment>>

    @Query("SELECT * FROM employee_salary_payments ORDER BY paymentDate DESC")
    suspend fun getAllSalaryPaymentsList(): List<EmployeeSalaryPayment>

    @Query("SELECT * FROM employee_salary_payments WHERE employeeId = :employeeId ORDER BY paymentDate DESC")
    fun getPaymentsForEmployee(employeeId: String): Flow<List<EmployeeSalaryPayment>>

    @Query("SELECT * FROM employee_salary_payments WHERE monthYear = :monthYear ORDER BY paymentDate DESC")
    fun getPaymentsForMonth(monthYear: String): Flow<List<EmployeeSalaryPayment>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSalaryPayment(payment: EmployeeSalaryPayment)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSalaryPayments(payments: List<EmployeeSalaryPayment>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSalaryPaymentsFromSync(payments: List<EmployeeSalaryPayment>)

    @Query("SELECT * FROM employee_salary_payments WHERE needsSync = 1")
    suspend fun getUnsyncedSalaryPaymentsList(): List<EmployeeSalaryPayment>

    @Query("UPDATE employee_salary_payments SET needsSync = 0 WHERE id IN (:ids)")
    suspend fun markSalaryPaymentsSynced(ids: List<String>)

    @Update
    suspend fun updateSalaryPaymentRaw(payment: EmployeeSalaryPayment)

    suspend fun updateSalaryPayment(payment: EmployeeSalaryPayment) {
        updateSalaryPaymentRaw(payment.copy(needsSync = true))
    }

    @Delete
    suspend fun deleteSalaryPayment(payment: EmployeeSalaryPayment)

    @Query("DELETE FROM employee_salary_payments WHERE id = :id")
    suspend fun deleteSalaryPaymentById(id: String)

    @Query("DELETE FROM employee_salary_payments WHERE employeeId = :employeeId")
    suspend fun deleteSalaryPaymentsByEmployeeId(employeeId: String): Int

    // --- Employee Advances & Reimbursements ---
    @Query("SELECT * FROM employee_advances ORDER BY date DESC")
    fun getAllAdvances(): Flow<List<EmployeeAdvance>>

    @Query("SELECT * FROM employee_advances ORDER BY date DESC")
    suspend fun getAllAdvancesList(): List<EmployeeAdvance>

    @Query("SELECT * FROM employee_advances WHERE employeeId = :employeeId ORDER BY date DESC")
    fun getAdvancesForEmployee(employeeId: String): Flow<List<EmployeeAdvance>>

    @Query("SELECT * FROM employee_advances WHERE employeeId = :employeeId AND status = 'PENDING' ORDER BY date DESC")
    fun getActiveAdvancesForEmployee(employeeId: String): Flow<List<EmployeeAdvance>>

    @Query("SELECT * FROM employee_advances WHERE employeeId = :employeeId AND status = 'PENDING'")
    suspend fun getActiveAdvancesListForEmployee(employeeId: String): List<EmployeeAdvance>

    @Query("SELECT * FROM employee_advances WHERE id = :id LIMIT 1")
    suspend fun getAdvanceById(id: String): EmployeeAdvance?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAdvance(advance: EmployeeAdvance)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAdvances(advances: List<EmployeeAdvance>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAdvancesFromSync(advances: List<EmployeeAdvance>)

    @Query("SELECT * FROM employee_advances WHERE needsSync = 1")
    suspend fun getUnsyncedAdvancesList(): List<EmployeeAdvance>

    @Query("UPDATE employee_advances SET needsSync = 0 WHERE id IN (:ids)")
    suspend fun markAdvancesSynced(ids: List<String>)

    @Update
    suspend fun updateAdvanceRaw(advance: EmployeeAdvance)

    suspend fun updateAdvance(advance: EmployeeAdvance) {
        updateAdvanceRaw(advance.copy(needsSync = true))
    }

    @Delete
    suspend fun deleteAdvance(advance: EmployeeAdvance)

    @Query("DELETE FROM employee_advances WHERE id = :id")
    suspend fun deleteAdvanceById(id: String)

    @Query("DELETE FROM employee_advances WHERE employeeId = :employeeId")
    suspend fun deleteAdvancesByEmployeeId(employeeId: String): Int
}
