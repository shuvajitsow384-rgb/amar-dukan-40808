package com.example.ui.screens.employees

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.local.entities.Employee
import com.example.data.local.entities.EmployeeAttendance
import com.example.data.local.entities.EmployeeSalaryDue
import com.example.ui.theme.*
import com.example.utils.SalaryBreakdown
import com.example.utils.SalaryCalculator
import com.example.viewmodel.StoreViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

@Composable
fun AccrueMonthSalaryDialog(
    initialMonthYear: String,
    viewModel: StoreViewModel,
    activeEmployees: List<Employee>,
    allSalaryDues: List<EmployeeSalaryDue>,
    isBengali: Boolean,
    onDismiss: () -> Unit,
    onAccrualComplete: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var currentMonthStr by remember { mutableStateOf(SalaryCalculator.formatStandardMonthYear(initialMonthYear)) }
    var breakdowns by remember { mutableStateOf<List<SalaryBreakdown>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var isSaving by remember { mutableStateOf(false) }

    // Load breakdowns whenever month or refreshTrigger changes
    LaunchedEffect(currentMonthStr, refreshTrigger) {
        isLoading = true
        breakdowns = viewModel.getSalaryBreakdownsForMonth(currentMonthStr)
        isLoading = false
    }

    val totalCalendarDays = remember(currentMonthStr) {
        SalaryCalculator.getTotalCalendarDaysInMonth(currentMonthStr)
    }

    val totalMissingDays = remember(breakdowns) {
        breakdowns.sumOf { it.unmarkedDates.size }
    }

    val totalCalculatedDues = remember(breakdowns) {
        breakdowns.sumOf { it.totalCalculatedDue }
    }

    // Check which employees already have dues recorded for this month
    val alreadyAccruedEmpIds = remember(allSalaryDues, currentMonthStr) {
        allSalaryDues.filter { it.monthYear.equals(currentMonthStr, ignoreCase = true) }
            .map { it.employeeId }
            .toSet()
    }

    val pendingBreakdownsToAccrue = remember(breakdowns, alreadyAccruedEmpIds) {
        breakdowns.filter { it.employeeId !in alreadyAccruedEmpIds }
    }

    val canAccrue = totalMissingDays == 0 && pendingBreakdownsToAccrue.isNotEmpty() && !isSaving

    Dialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isBengali) "হাজিরা-ভিত্তিক মাসিক বেতন হিসাব" else "Attendance-Based Salary Accrual",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = StoreRedPrimary
                        )
                        Text(
                            text = if (isBengali) "প্রতি দিনের উপস্থিতি অনুযায়ী বেতন নির্ণয়" else "Calculates exact dues from daily attendance records",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                    IconButton(onClick = onDismiss, enabled = !isSaving) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Month Selector Bar
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                val cal = SalaryCalculator.parseMonthYear(currentMonthStr)
                                cal.add(Calendar.MONTH, -1)
                                currentMonthStr = SimpleDateFormat("MMMM yyyy", Locale.ENGLISH).format(cal.time)
                            },
                            enabled = !isSaving
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous Month")
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = currentMonthStr,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium,
                                color = TextDark
                            )
                            Text(
                                text = "$totalCalendarDays ${if (isBengali) "দিন এই মাসে" else "calendar days in month"}",
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                        }

                        IconButton(
                            onClick = {
                                val cal = SalaryCalculator.parseMonthYear(currentMonthStr)
                                cal.add(Calendar.MONTH, 1)
                                currentMonthStr = SimpleDateFormat("MMMM yyyy", Locale.ENGLISH).format(cal.time)
                            },
                            enabled = !isSaving
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next Month")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Formula rule banner
                Surface(
                    color = StoreRedPrimary.copy(alpha = 0.07f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Calculate, contentDescription = null, tint = StoreRedPrimary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isBengali) "নিয়ম: দৈনিক হার = মূল বেতন ÷ মাসের দিন ($totalCalendarDays দিন)। উপস্থিত = পূর্ণ হার, হাফ-ডে = অর্ধেক হার, অনুপস্থিত = ₹০।"
                                   else "Rule: Daily Rate = Salary ÷ $totalCalendarDays days. Present = 100%, Half-day = 50%, Absent = ₹0.",
                            fontSize = 11.sp,
                            color = StoreRedPrimary,
                            lineHeight = 14.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Missing Attendance Alert Banner (Mandatory Rule #4)
                if (totalMissingDays > 0) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = LossRed.copy(alpha = 0.12f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = LossRed, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isBengali) "হাজিরা অসম্পূর্ণ: $totalMissingDays টি দিনে কোনো হাজিরা নেই!" else "Attendance Incomplete: $totalMissingDays unmarked days found!",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = LossRed
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isBengali) "বেতন হিসাব নিশ্চিত করার আগে প্রতিটি দিনের হাজিরা চিহ্নিত করুন। নিচে দ্রুত চিহ্নিত করার অপশন রয়েছে:"
                                       else "Before finalizing calculation, every day in the month must have attendance marked. Use the quick buttons below:",
                                fontSize = 11.sp,
                                color = TextDark
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // Batch Action Buttons to Mark All Missing
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            val attendances = mutableListOf<EmployeeAttendance>()
                                            breakdowns.forEach { b ->
                                                b.unmarkedDates.forEach { date ->
                                                    attendances.add(
                                                        EmployeeAttendance(
                                                            id = UUID.randomUUID().toString(),
                                                            employeeId = b.employeeId,
                                                            employeeName = b.employeeName,
                                                            date = date,
                                                            status = "PRESENT",
                                                            checkInTime = "09:00 AM",
                                                            checkOutTime = "08:00 PM"
                                                        )
                                                    )
                                                }
                                            }
                                            viewModel.markAttendanceBatch(attendances) {
                                                refreshTrigger++
                                                Toast.makeText(context, if (isBengali) "সকল খালি দিনে উপস্থিত চিহ্নিত করা হয়েছে" else "Marked all missing days as Present", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = ProfitGreen),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.DoneAll, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (isBengali) "সব খালি দিন উপস্থিত" else "Mark All Missing Present", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }

                                OutlinedButton(
                                    onClick = {
                                        coroutineScope.launch {
                                            val attendances = mutableListOf<EmployeeAttendance>()
                                            breakdowns.forEach { b ->
                                                b.unmarkedDates.forEach { date ->
                                                    attendances.add(
                                                        EmployeeAttendance(
                                                            id = UUID.randomUUID().toString(),
                                                            employeeId = b.employeeId,
                                                            employeeName = b.employeeName,
                                                            date = date,
                                                            status = "ABSENT",
                                                            checkInTime = "",
                                                            checkOutTime = ""
                                                        )
                                                    )
                                                }
                                            }
                                            viewModel.markAttendanceBatch(attendances) {
                                                refreshTrigger++
                                                Toast.makeText(context, if (isBengali) "সকল খালি দিনে অনুপস্থিত চিহ্নিত করা হয়েছে" else "Marked all missing days as Absent", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (isBengali) "সব খালি দিন অনুপস্থিত" else "Mark All Missing Absent", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // List of Employee Salary Breakdowns
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = StoreRedPrimary)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(breakdowns, key = { it.employeeId }) { breakdown ->
                            val emp = activeEmployees.find { it.id == breakdown.employeeId }
                            val isAlreadyAccrued = breakdown.employeeId in alreadyAccruedEmpIds

                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (breakdown.unmarkedDates.isNotEmpty()) LossRed.copy(alpha = 0.05f)
                                    else if (isAlreadyAccrued) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                    else MaterialTheme.colorScheme.surface
                                ),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (breakdown.unmarkedDates.isNotEmpty()) LossRed.copy(alpha = 0.4f)
                                    else if (isAlreadyAccrued) Color.LightGray.copy(alpha = 0.5f)
                                    else StoreRedPrimary.copy(alpha = 0.3f)
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    // Row 1: Name, Role, and Status Badge
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = breakdown.employeeName,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp,
                                                color = TextDark
                                            )
                                            Text(
                                                text = "${emp?.designation?.ifBlank { emp.role } ?: "Staff"} • Base: ₹${"%.0f".format(breakdown.baseMonthlySalary)}/mo",
                                                fontSize = 11.sp,
                                                color = TextMuted
                                            )
                                        }

                                        if (isAlreadyAccrued) {
                                            Surface(
                                                color = ProfitGreen.copy(alpha = 0.15f),
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Text(
                                                    text = if (isBengali) "ইতিমধ্যে যুক্ত" else "Already Accrued",
                                                    color = ProfitGreen,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        } else if (breakdown.unmarkedDates.isNotEmpty()) {
                                            Surface(
                                                color = LossRed.copy(alpha = 0.15f),
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Text(
                                                    text = "${breakdown.unmarkedDates.size} ${if (isBengali) "দিন বাকি" else "Days Missing"}",
                                                    color = LossRed,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        } else {
                                            Surface(
                                                color = StoreRedPrimary.copy(alpha = 0.15f),
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Text(
                                                    text = if (isBengali) "প্রস্তুত" else "Ready to Accrue",
                                                    color = StoreRedPrimary,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    // Per-Day Rate Calculation Formula (Rule #1)
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Per-Day Rate (₹${"%.0f".format(breakdown.baseMonthlySalary)} ÷ ${breakdown.totalCalendarDays}d):",
                                                fontSize = 11.sp,
                                                color = TextDark
                                            )
                                            Text(
                                                text = "₹${"%.2f".format(breakdown.perDayRate)} / day",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp,
                                                color = StoreRedPrimary
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    // Attendance Counts Grid (Rule #2, #5)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        // Present Box
                                        Surface(
                                            color = ProfitGreen.copy(alpha = 0.1f),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Column(modifier = Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text("Present", fontSize = 10.sp, color = ProfitGreen, fontWeight = FontWeight.Bold)
                                                Text("${breakdown.presentDays + breakdown.paidLeaveDays} d", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ProfitGreen)
                                                Text("₹${"%.0f".format((breakdown.presentDays + breakdown.paidLeaveDays) * breakdown.perDayRate)}", fontSize = 10.sp, color = TextMuted)
                                            }
                                        }

                                        // Half-Day Box
                                        Surface(
                                            color = StoreGold.copy(alpha = 0.15f),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Column(modifier = Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text("Half-Day", fontSize = 10.sp, color = TextDark, fontWeight = FontWeight.Bold)
                                                Text("${breakdown.halfDays} d", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextDark)
                                                Text("₹${"%.0f".format(breakdown.halfDays * 0.5 * breakdown.perDayRate)}", fontSize = 10.sp, color = TextMuted)
                                            }
                                        }

                                        // Absent Box
                                        Surface(
                                            color = LossRed.copy(alpha = 0.1f),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Column(modifier = Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text("Absent", fontSize = 10.sp, color = LossRed, fontWeight = FontWeight.Bold)
                                                Text("${breakdown.absentDays} d", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LossRed)
                                                Text("₹0", fontSize = 10.sp, color = TextMuted)
                                            }
                                        }
                                    }

                                    // Unmarked Dates Inline Resolution (Rule #4)
                                    if (breakdown.unmarkedDates.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(LossRed.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                                                .padding(8.dp)
                                        ) {
                                            Text(
                                                text = "⚠️ Unmarked Days (${breakdown.unmarkedDates.size}) — Mark status below:",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = LossRed
                                            )
                                            Spacer(modifier = Modifier.height(6.dp))

                                            // Show chips for each unmarked date
                                            breakdown.unmarkedDates.forEach { dateStr ->
                                                val displayDate = try {
                                                    val d = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).parse(dateStr)
                                                    SimpleDateFormat("dd MMM", Locale.ENGLISH).format(d!!)
                                                } catch (_: Exception) { dateStr }

                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(vertical = 2.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(text = "• $displayDate ($dateStr)", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                        // Present Chip
                                                        Surface(
                                                            color = ProfitGreen,
                                                            shape = RoundedCornerShape(4.dp),
                                                            modifier = Modifier.clickable {
                                                                viewModel.markAttendanceForDate(breakdown.employeeId, breakdown.employeeName, dateStr, "PRESENT") {
                                                                    refreshTrigger++
                                                                }
                                                            }
                                                        ) {
                                                            Text("Present", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                                        }

                                                        // Half-Day Chip
                                                        Surface(
                                                            color = StoreGold,
                                                            shape = RoundedCornerShape(4.dp),
                                                            modifier = Modifier.clickable {
                                                                viewModel.markAttendanceForDate(breakdown.employeeId, breakdown.employeeName, dateStr, "HALF_DAY") {
                                                                    refreshTrigger++
                                                                }
                                                            }
                                                        ) {
                                                            Text("Half", color = TextDark, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                                        }

                                                        // Absent Chip
                                                        Surface(
                                                            color = LossRed,
                                                            shape = RoundedCornerShape(4.dp),
                                                            modifier = Modifier.clickable {
                                                                viewModel.markAttendanceForDate(breakdown.employeeId, breakdown.employeeName, dateStr, "ABSENT") {
                                                                    refreshTrigger++
                                                                }
                                                            }
                                                        ) {
                                                            Text("Absent", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    // Resulting Total Due (Rule #3, #5)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (isBengali) "হিসাবকৃত বকেয়া বেতন:" else "Calculated Salary Due:",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextDark
                                        )
                                        Text(
                                            text = "₹${"%.2f".format(breakdown.totalCalculatedDue)}",
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = StoreRedPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Bottom Accrual Summary & Confirmation Button
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = if (isBengali) "মোট প্রদেয় বেতন বকেয়া" else "Total Net Dues to Accrue:",
                                    fontSize = 11.sp,
                                    color = TextMuted
                                )
                                Text(
                                    text = "₹${"%.2f".format(pendingBreakdownsToAccrue.sumOf { it.totalCalculatedDue })}",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = StoreRedPrimary
                                )
                            }

                            Text(
                                text = "${pendingBreakdownsToAccrue.size} ${if (isBengali) "জন কর্মীর জন্য" else "staff to accrue"}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextDark
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = {
                                isSaving = true
                                viewModel.recordSalaryDuesBatch(pendingBreakdownsToAccrue) { success, msg ->
                                    isSaving = false
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    if (success) {
                                        onAccrualComplete()
                                        onDismiss()
                                    }
                                }
                            },
                            enabled = canAccrue,
                            colors = ButtonDefaults.buttonColors(containerColor = StoreRedPrimary),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(vertical = 12.dp)
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Recording Dues...")
                            } else {
                                Icon(Icons.Default.PostAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (totalMissingDays > 0) {
                                        if (isBengali) "বাকি দিনগুলো পূরণ করুন ($totalMissingDays দিন)" else "Fill Missing Days to Accrue ($totalMissingDays left)"
                                    } else if (pendingBreakdownsToAccrue.isEmpty()) {
                                        if (isBengali) "সকল কর্মীর বকেয়া ইতিমধ্যে নথিভুক্ত" else "All Staff Dues Already Accrued"
                                    } else {
                                        if (isBengali) "বেতন বকেয়া নিশ্চিত ও নথিভুক্ত করুন (₹${"%.0f".format(pendingBreakdownsToAccrue.sumOf { it.totalCalculatedDue })})"
                                        else "Confirm & Record Salary Dues (₹${"%.0f".format(pendingBreakdownsToAccrue.sumOf { it.totalCalculatedDue })})"
                                    },
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
