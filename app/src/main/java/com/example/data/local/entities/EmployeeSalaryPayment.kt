package com.example.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "employee_salary_dues",
    indices = [
        Index(value = ["employeeId"]),
        Index(value = ["monthYear"])
    ]
)
data class EmployeeSalaryDue(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val employeeId: String,
    val employeeName: String,
    val monthYear: String, // e.g. "August 2026"
    val dueAmount: Double,
    val dueDate: Long = System.currentTimeMillis(),
    val notes: String = "",
    val needsSync: Boolean = true
)

@Entity(
    tableName = "employee_salary_payments",
    indices = [
        Index(value = ["employeeId"]),
        Index(value = ["monthYear"])
    ]
)
data class EmployeeSalaryPayment(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val employeeId: String,
    val employeeName: String,
    val monthYear: String, // e.g. "August 2026" or "2026-08"
    val paymentDate: Long = System.currentTimeMillis(),
    val baseSalary: Double,
    val salaryType: String = "MONTHLY", // "MONTHLY", "DAILY", "HOURLY"
    val presentDays: Int = 0,
    val halfDays: Int = 0,
    val absentDays: Int = 0,
    val paidLeaveDays: Int = 0,
    val totalWorkingDaysInMonth: Int = 30,
    val overtimeHours: Double = 0.0,
    val overtimePay: Double = 0.0,
    val bonus: Double = 0.0,
    val advanceDeduction: Double = 0.0,
    val otherDeductions: Double = 0.0,
    val netSalaryPaid: Double,
    val paymentMode: String = "CASH", // "CASH", "UPI", "BANK_TRANSFER"
    val notes: String = "",
    val syncedToExpense: Boolean = true,
    val needsSync: Boolean = true
)

@Entity(
    tableName = "employee_advances",
    indices = [
        Index(value = ["employeeId"]),
        Index(value = ["type"]),
        Index(value = ["status"])
    ]
)
data class EmployeeAdvance(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val employeeId: String,
    val employeeName: String,
    val type: String = "ADVANCE", // "ADVANCE" (advance given to employee), "REIMBURSEMENT" (staff paid shop expense)
    val date: Long = System.currentTimeMillis(),
    val amount: Double,
    val repaidAmount: Double = 0.0,
    val reason: String = "",
    val status: String = "PENDING", // "PENDING", "DEDUCTED", "SETTLED"
    val paymentMode: String = "CASH",
    val settledDate: Long? = null,
    val settlementNotes: String = "",
    val needsSync: Boolean = true
)
