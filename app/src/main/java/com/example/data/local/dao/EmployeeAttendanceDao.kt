package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entities.EmployeeAttendance
import kotlinx.coroutines.flow.Flow

@Dao
interface EmployeeAttendanceDao {
    @Query("SELECT * FROM employee_attendance ORDER BY date DESC, employeeName ASC")
    fun getAllAttendance(): Flow<List<EmployeeAttendance>>

    @Query("SELECT * FROM employee_attendance ORDER BY date DESC, employeeName ASC")
    suspend fun getAllAttendanceList(): List<EmployeeAttendance>

    @Query("SELECT * FROM employee_attendance WHERE date = :date ORDER BY employeeName ASC")
    fun getAttendanceForDate(date: String): Flow<List<EmployeeAttendance>>

    @Query("SELECT * FROM employee_attendance WHERE date = :date")
    suspend fun getAttendanceListForDate(date: String): List<EmployeeAttendance>

    @Query("SELECT * FROM employee_attendance WHERE employeeId = :employeeId ORDER BY date DESC")
    fun getAttendanceForEmployee(employeeId: String): Flow<List<EmployeeAttendance>>

    @Query("SELECT * FROM employee_attendance WHERE employeeId = :employeeId AND date LIKE :monthPrefix || '%' ORDER BY date ASC")
    fun getAttendanceForEmployeeInMonth(employeeId: String, monthPrefix: String): Flow<List<EmployeeAttendance>>

    @Query("SELECT * FROM employee_attendance WHERE employeeId = :employeeId AND date LIKE :monthPrefix || '%'")
    suspend fun getAttendanceListForEmployeeInMonth(employeeId: String, monthPrefix: String): List<EmployeeAttendance>

    @Query("SELECT * FROM employee_attendance WHERE employeeId = :employeeId AND date = :date LIMIT 1")
    suspend fun getAttendance(employeeId: String, date: String): EmployeeAttendance?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttendance(attendance: EmployeeAttendance)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttendanceBatch(list: List<EmployeeAttendance>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttendanceBatchFromSync(list: List<EmployeeAttendance>)

    @Query("SELECT * FROM employee_attendance WHERE needsSync = 1")
    suspend fun getUnsyncedAttendanceList(): List<EmployeeAttendance>

    @Query("UPDATE employee_attendance SET needsSync = 0 WHERE id IN (:ids)")
    suspend fun markAttendanceSynced(ids: List<String>)

    @Update
    suspend fun updateAttendanceRaw(attendance: EmployeeAttendance)

    suspend fun updateAttendance(attendance: EmployeeAttendance) {
        updateAttendanceRaw(attendance.copy(needsSync = true))
    }

    @Delete
    suspend fun deleteAttendance(attendance: EmployeeAttendance)

    @Query("DELETE FROM employee_attendance WHERE employeeId = :employeeId AND date = :date")
    suspend fun deleteAttendanceByEmployeeAndDate(employeeId: String, date: String)

    @Query("DELETE FROM employee_attendance WHERE employeeId = :employeeId")
    suspend fun deleteAttendanceByEmployeeId(employeeId: String): Int
}
