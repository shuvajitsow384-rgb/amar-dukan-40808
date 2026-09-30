package com.example.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "employee_attendance",
    indices = [
        Index(value = ["employeeId", "date"], unique = true),
        Index(value = ["date"])
    ]
)
data class EmployeeAttendance(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val employeeId: String,
    val employeeName: String = "",
    val date: String, // Format: YYYY-MM-DD
    val status: String = "PRESENT", // "PRESENT", "HALF_DAY", "ABSENT", "PAID_LEAVE", "SICK_LEAVE", "HOLIDAY"
    val checkInTime: String = "", // e.g. "09:30 AM"
    val checkOutTime: String = "", // e.g. "08:00 PM"
    val overtimeHours: Double = 0.0,
    val notes: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val needsSync: Boolean = true
)
