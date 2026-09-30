package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entities.Employee
import kotlinx.coroutines.flow.Flow

@Dao
interface EmployeeDao {
    @Query("SELECT * FROM employees ORDER BY CASE WHEN email = '' OR email IS NULL THEN 1 ELSE 0 END, LOWER(email) ASC, LOWER(name) ASC")
    fun getAllEmployees(): Flow<List<Employee>>

    @Query("SELECT * FROM employees WHERE isActive = 1 ORDER BY name ASC")
    fun getActiveEmployees(): Flow<List<Employee>>

    @Query("SELECT * FROM employees WHERE id = :id LIMIT 1")
    suspend fun getEmployeeById(id: String): Employee?

    @Query("SELECT * FROM employees WHERE LOWER(TRIM(email)) = LOWER(TRIM(:email)) LIMIT 1")
    suspend fun getEmployeeByEmail(email: String): Employee?

    @Query("SELECT * FROM employees ORDER BY CASE WHEN email = '' OR email IS NULL THEN 1 ELSE 0 END, LOWER(email) ASC, LOWER(name) ASC")
    suspend fun getAllEmployeesList(): List<Employee>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEmployee(employee: Employee)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEmployees(employees: List<Employee>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEmployeesFromSync(employees: List<Employee>)

    @Query("SELECT * FROM employees WHERE needsSync = 1 ORDER BY name ASC")
    suspend fun getUnsyncedEmployeesList(): List<Employee>

    @Query("UPDATE employees SET needsSync = 0 WHERE id IN (:ids)")
    suspend fun markEmployeesSynced(ids: List<String>)

    @Update
    suspend fun updateEmployeeRaw(employee: Employee)

    suspend fun updateEmployee(employee: Employee) {
        updateEmployeeRaw(employee.copy(needsSync = true))
    }

    @Delete
    suspend fun deleteEmployee(employee: Employee)

    @Query("DELETE FROM employees WHERE id = :id")
    suspend fun deleteEmployeeById(id: String)
}
