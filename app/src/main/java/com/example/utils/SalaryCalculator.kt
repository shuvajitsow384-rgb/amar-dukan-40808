package com.example.utils

import com.example.data.local.entities.Employee
import com.example.data.local.entities.EmployeeAttendance
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class SalaryBreakdown(
    val employeeId: String,
    val employeeName: String,
    val monthYear: String,
    val baseMonthlySalary: Double,
    val totalCalendarDays: Int,
    val perDayRate: Double,
    val presentDays: Int,
    val halfDays: Int,
    val absentDays: Int,
    val paidLeaveDays: Int,
    val unmarkedDates: List<String>, // List of "YYYY-MM-DD"
    val totalCalculatedDue: Double,
    val isComplete: Boolean // true if unmarkedDates.isEmpty()
)

object SalaryCalculator {

    /**
     * Parses a month-year string (e.g. "August 2026", "2026-08", "Aug 2026") into Calendar representing 1st day of that month.
     */
    fun parseMonthYear(monthYear: String): Calendar {
        val cal = Calendar.getInstance()
        val patterns = listOf("MMMM yyyy", "MMM yyyy", "yyyy-MM", "yyyy/MM", "MM-yyyy", "MMMM, yyyy")
        for (pattern in patterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.ENGLISH)
                sdf.isLenient = false
                val parsed = sdf.parse(monthYear.trim())
                if (parsed != null) {
                    cal.time = parsed
                    cal.set(Calendar.DAY_OF_MONTH, 1)
                    return cal
                }
            } catch (_: Exception) {}
        }
        // Fallback: try current locale
        try {
            val sdf = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
            val parsed = sdf.parse(monthYear.trim())
            if (parsed != null) {
                cal.time = parsed
                cal.set(Calendar.DAY_OF_MONTH, 1)
                return cal
            }
        } catch (_: Exception) {}
        return cal
    }

    /**
     * Returns total calendar days in the given month/year.
     */
    fun getTotalCalendarDaysInMonth(monthYear: String): Int {
        val cal = parseMonthYear(monthYear)
        return cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    /**
     * Generates all ISO date strings ("YYYY-MM-DD") for the given month.
     */
    fun getAllDatesInMonth(monthYear: String): List<String> {
        val cal = parseMonthYear(monthYear)
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1 // 1..12
        val maxDays = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        return (1..maxDays).map { day ->
            String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
        }
    }

    /**
     * Formats Month Year as standard e.g. "August 2026"
     */
    fun formatStandardMonthYear(monthYear: String): String {
        val cal = parseMonthYear(monthYear)
        return SimpleDateFormat("MMMM yyyy", Locale.ENGLISH).format(cal.time)
    }

    /**
     * Formats month prefix for SQL queries, e.g. "2026-08"
     */
    fun getMonthPrefix(monthYear: String): String {
        val cal = parseMonthYear(monthYear)
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        return String.format(Locale.US, "%04d-%02d", year, month)
    }

    /**
     * Calculates salary breakdown based on actual daily attendance.
     * Logic:
     * 1. Per-day rate = employee's monthly salary / total calendar days in month.
     * 2. Present = 1.0 * per-day rate
     *    Half-day = 0.5 * per-day rate
     *    Absent = ₹0
     * 3. Total sum across all days in month = calculated salary due.
     * 4. Unmarked days are detected and listed in `unmarkedDates`.
     */
    fun calculateSalaryBreakdown(
        employee: Employee,
        monthYear: String,
        attendances: List<EmployeeAttendance>
    ): SalaryBreakdown {
        val allMonthDates = getAllDatesInMonth(monthYear)
        val totalCalendarDays = allMonthDates.size
        val perDayRate = if (totalCalendarDays > 0) employee.baseSalary / totalCalendarDays else 0.0

        val attendanceMap = attendances.associateBy { it.date }

        var presentCount = 0
        var halfDayCount = 0
        var absentCount = 0
        var paidLeaveCount = 0
        val unmarkedList = mutableListOf<String>()

        for (date in allMonthDates) {
            val record = attendanceMap[date]
            if (record == null) {
                unmarkedList.add(date)
            } else {
                when (record.status.uppercase(Locale.ROOT)) {
                    "PRESENT" -> presentCount++
                    "HALF_DAY" -> halfDayCount++
                    "ABSENT" -> absentCount++
                    "PAID_LEAVE", "SICK_LEAVE", "HOLIDAY" -> paidLeaveCount++
                    else -> presentCount++
                }
            }
        }

        val effectiveFullDays = presentCount + paidLeaveCount
        val totalCalculated = (effectiveFullDays * perDayRate) + (halfDayCount * 0.5 * perDayRate)

        return SalaryBreakdown(
            employeeId = employee.id,
            employeeName = employee.name,
            monthYear = formatStandardMonthYear(monthYear),
            baseMonthlySalary = employee.baseSalary,
            totalCalendarDays = totalCalendarDays,
            perDayRate = perDayRate,
            presentDays = presentCount,
            halfDays = halfDayCount,
            absentDays = absentCount,
            paidLeaveDays = paidLeaveCount,
            unmarkedDates = unmarkedList,
            totalCalculatedDue = totalCalculated,
            isComplete = unmarkedList.isEmpty()
        )
    }
}
