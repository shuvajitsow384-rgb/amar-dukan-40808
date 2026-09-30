package com.example.ui.screens.employees

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.firestore.FirestoreUserRole
import com.example.data.local.dao.SaleWithItems
import com.example.data.local.entities.Employee
import com.example.data.local.entities.EmployeeAdvance
import com.example.data.local.entities.EmployeeAttendance
import com.example.data.local.entities.EmployeeSalaryPayment
import com.example.ui.theme.*
import com.example.utils.LanguageManager
import com.example.utils.StoreInfoManager
import com.example.viewmodel.StoreViewModel
import java.text.SimpleDateFormat
import java.util.*

enum class LogActivityType(val labelEn: String, val labelBn: String, val icon: ImageVector, val color: Color) {
    ALL("All Activities", "সকল কার্যকলাপ", Icons.Default.AllInclusive, Color(0xFF8B0000)),
    SALES("Sales & Billing", "সেলস ও বিলিং", Icons.Default.ShoppingCart, Color(0xFF2E7D32)),
    ATTENDANCE("Attendance", "হাজিরা ও শিফট", Icons.Default.EventAvailable, Color(0xFFE65100)),
    PAYROLL("Salary & Advance", "বেতন ও অগ্রিম", Icons.Default.Payments, Color(0xFF673AB7)),
    ACCESS("Users & Auth", "ইউজার ও অ্যাক্সেস", Icons.Default.Security, Color(0xFF00838F))
}

enum class LogDateRange(val labelEn: String, val labelBn: String) {
    TODAY("Today", "আজ"),
    YESTERDAY("Yesterday", "গতকাল"),
    LAST_7_DAYS("Last 7 Days", "গত ৭ দিন"),
    THIS_MONTH("This Month", "চলতি মাস"),
    ALL_TIME("All Time", "সর্বমোট")
}

sealed class StaffActivityItem(
    val id: String,
    val timestamp: Long,
    val staffName: String,
    val staffId: String?,
    val category: LogActivityType
) {
    data class SaleActivity(
        val saleWithItems: SaleWithItems,
        val invoiceNo: String,
        val totalAmount: Double,
        val discount: Double,
        val paymentMode: String,
        val customerName: String?,
        val itemsCount: Int
    ) : StaffActivityItem(
        id = "sale_${saleWithItems.sale.id}",
        timestamp = saleWithItems.sale.datetime,
        staffName = saleWithItems.sale.staffName?.takeIf { it.isNotBlank() } ?: "Store Owner",
        staffId = saleWithItems.sale.staffId,
        category = LogActivityType.SALES
    )

    data class AttendanceActivity(
        val attendance: EmployeeAttendance
    ) : StaffActivityItem(
        id = "att_${attendance.id}",
        timestamp = parseDateToMillis(attendance.date),
        staffName = attendance.employeeName,
        staffId = attendance.employeeId,
        category = LogActivityType.ATTENDANCE
    )

    data class PayrollActivity(
        val payment: EmployeeSalaryPayment
    ) : StaffActivityItem(
        id = "pay_${payment.id}",
        timestamp = payment.paymentDate,
        staffName = payment.employeeName,
        staffId = payment.employeeId,
        category = LogActivityType.PAYROLL
    )

    data class AdvanceActivity(
        val advance: EmployeeAdvance
    ) : StaffActivityItem(
        id = "adv_${advance.id}",
        timestamp = advance.date,
        staffName = advance.employeeName,
        staffId = advance.employeeId,
        category = LogActivityType.PAYROLL
    )

    data class UserAccessActivity(
        val userRole: FirestoreUserRole
    ) : StaffActivityItem(
        id = "user_${userRole.uid}_${userRole.updatedAt}",
        timestamp = userRole.lastActiveAt.takeIf { it > 0 } ?: userRole.updatedAt,
        staffName = userRole.displayName?.takeIf { it.isNotBlank() } ?: userRole.email ?: "App User",
        staffId = userRole.uid,
        category = LogActivityType.ACCESS
    )
}

private fun parseDateToMillis(dateStr: String): Long {
    return try {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        sdf.parse(dateStr)?.time ?: System.currentTimeMillis()
    } catch (e: Exception) {
        System.currentTimeMillis()
    }
}

@Composable
fun StaffActivityLogsTabContent(
    viewModel: StoreViewModel,
    allEmployees: List<Employee>,
    isBengali: Boolean
) {
    val context = LocalContext.current
    val allSales by viewModel.allSales.collectAsState()
    val allAttendance by viewModel.allAttendance.collectAsState()
    val allSalaryPayments by viewModel.allSalaryPayments.collectAsState()
    val allAdvances by viewModel.allAdvances.collectAsState()
    val allFirestoreUsers by viewModel.allFirestoreUsers.collectAsState()

    var selectedStaffFilter by remember { mutableStateOf<String?>("ALL") } // "ALL", "OWNER", or employeeId
    var selectedCategory by remember { mutableStateOf(LogActivityType.ALL) }
    var selectedDateRange by remember { mutableStateOf(LogDateRange.THIS_MONTH) }
    var searchQuery by remember { mutableStateOf("") }
    // 300ms debounce for search query
    var debouncedSearchQuery by remember { mutableStateOf("") }
    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            debouncedSearchQuery = ""
        } else {
            kotlinx.coroutines.delay(300)
            debouncedSearchQuery = searchQuery
        }
    }
    var selectedActivityDetail by remember { mutableStateOf<StaffActivityItem?>(null) }

    // Calculate Date Range Bounds
    val (startTimestamp, endTimestamp) = remember(selectedDateRange) {
        val cal = Calendar.getInstance()
        val now = cal.timeInMillis
        when (selectedDateRange) {
            LogDateRange.TODAY -> {
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                Pair(cal.timeInMillis, now + 86400000L)
            }
            LogDateRange.YESTERDAY -> {
                cal.add(Calendar.DAY_OF_YEAR, -1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val start = cal.timeInMillis
                cal.set(Calendar.HOUR_OF_DAY, 23)
                cal.set(Calendar.MINUTE, 59)
                cal.set(Calendar.SECOND, 59)
                Pair(start, cal.timeInMillis)
            }
            LogDateRange.LAST_7_DAYS -> {
                cal.add(Calendar.DAY_OF_YEAR, -7)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                Pair(cal.timeInMillis, now + 86400000L)
            }
            LogDateRange.THIS_MONTH -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                Pair(cal.timeInMillis, now + 86400000L)
            }
            LogDateRange.ALL_TIME -> {
                Pair(0L, Long.MAX_VALUE)
            }
        }
    }

    // Build unified activity list filtered by date and staff
    val activitiesList = remember(
        allSales, allAttendance, allSalaryPayments, allAdvances, allFirestoreUsers,
        selectedStaffFilter, selectedCategory, startTimestamp, endTimestamp, searchQuery
    ) {
        val list = mutableListOf<StaffActivityItem>()

        // 1. Sales
        if (selectedCategory == LogActivityType.ALL || selectedCategory == LogActivityType.SALES) {
            for (item in allSales) {
                if (item.sale.datetime in startTimestamp..endTimestamp) {
                    list.add(
                        StaffActivityItem.SaleActivity(
                            saleWithItems = item,
                            invoiceNo = item.sale.id.takeLast(6).uppercase(),
                            totalAmount = item.sale.finalAmount,
                            discount = item.sale.discount,
                            paymentMode = item.sale.paymentMode,
                            customerName = item.sale.customerName,
                            itemsCount = item.items.size
                        )
                    )
                }
            }
        }

        // 2. Attendance
        if (selectedCategory == LogActivityType.ALL || selectedCategory == LogActivityType.ATTENDANCE) {
            for (att in allAttendance) {
                val t = parseDateToMillis(att.date)
                if (t in startTimestamp..endTimestamp) {
                    list.add(StaffActivityItem.AttendanceActivity(att))
                }
            }
        }

        // 3. Salary Payments & Advances
        if (selectedCategory == LogActivityType.ALL || selectedCategory == LogActivityType.PAYROLL) {
            for (pay in allSalaryPayments) {
                if (pay.paymentDate in startTimestamp..endTimestamp) {
                    list.add(StaffActivityItem.PayrollActivity(pay))
                }
            }
            for (adv in allAdvances) {
                if (adv.date in startTimestamp..endTimestamp) {
                    list.add(StaffActivityItem.AdvanceActivity(adv))
                }
            }
        }

        // 4. User Access / Role Activity
        if (selectedCategory == LogActivityType.ALL || selectedCategory == LogActivityType.ACCESS) {
            for (user in allFirestoreUsers) {
                val t = if (user.lastActiveAt > 0) user.lastActiveAt else user.updatedAt
                if (t in startTimestamp..endTimestamp || selectedDateRange == LogDateRange.ALL_TIME) {
                    list.add(StaffActivityItem.UserAccessActivity(user))
                }
            }
        }

        // Filter by Staff
        val staffFiltered = if (selectedStaffFilter == null || selectedStaffFilter == "ALL") {
            list
        } else if (selectedStaffFilter == "OWNER") {
            list.filter { it.staffId == null || it.staffId == "owner" || it.staffName.equals("Store Owner", ignoreCase = true) || it.staffName.contains("Owner", ignoreCase = true) }
        } else {
            val targetEmp = allEmployees.find { it.id == selectedStaffFilter }
            list.filter { item ->
                item.staffId == selectedStaffFilter ||
                (targetEmp != null && (item.staffName.equals(targetEmp.name, ignoreCase = true) || (targetEmp.email.isNotBlank() && item.staffName.contains(targetEmp.email, ignoreCase = true))))
            }
        }

        // Filter by Search Query
        val searchFiltered = if (debouncedSearchQuery.isBlank()) {
            staffFiltered
        } else {
            val q = debouncedSearchQuery.trim().lowercase()
            staffFiltered.filter { item ->
                item.staffName.lowercase().contains(q) ||
                when (item) {
                    is StaffActivityItem.SaleActivity -> {
                        item.invoiceNo.lowercase().contains(q) ||
                        (item.customerName?.lowercase()?.contains(q) == true) ||
                        item.paymentMode.lowercase().contains(q) ||
                        "%.2f".format(item.totalAmount).contains(q)
                    }
                    is StaffActivityItem.AttendanceActivity -> {
                        item.attendance.status.lowercase().contains(q) ||
                        (item.attendance.notes?.lowercase()?.contains(q) == true) ||
                        item.attendance.date.contains(q)
                    }
                    is StaffActivityItem.PayrollActivity -> {
                        item.payment.paymentMode.lowercase().contains(q) ||
                        item.payment.monthYear.contains(q) ||
                        "%.2f".format(item.payment.netSalaryPaid).contains(q)
                    }
                    is StaffActivityItem.AdvanceActivity -> {
                        item.advance.type.lowercase().contains(q) ||
                        item.advance.reason.lowercase().contains(q) ||
                        "%.2f".format(item.advance.amount).contains(q)
                    }
                    is StaffActivityItem.UserAccessActivity -> {
                        (item.userRole.email?.lowercase()?.contains(q) == true) ||
                        item.userRole.role.lowercase().contains(q)
                    }
                }
            }
        }

        searchFiltered.sortedByDescending { it.timestamp }
    }

    // KPI Metrics calculation for current filtered range
    val salesInScope = activitiesList.filterIsInstance<StaffActivityItem.SaleActivity>()
    val totalRevenue = salesInScope.sumOf { it.totalAmount }
    val totalDiscount = salesInScope.sumOf { it.discount }
    val cashSales = salesInScope.filter { it.paymentMode.equals("CASH", ignoreCase = true) }.sumOf { it.totalAmount }
    val onlineSales = salesInScope.filter { it.paymentMode.equals("UPI", ignoreCase = true) || it.paymentMode.equals("ONLINE", ignoreCase = true) }.sumOf { it.totalAmount }
    val creditSales = salesInScope.filter { it.paymentMode.equals("CREDIT", ignoreCase = true) || it.paymentMode.equals("KHATA", ignoreCase = true) }.sumOf { it.totalAmount }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Header & Overview Card
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(StorePrimary.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.History, contentDescription = null, tint = StorePrimary, modifier = Modifier.size(24.dp))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = if (isBengali) "স্টাফ কার্যকলাপ ও অডিট লগ" else "Staff Activity & Audit Logs",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextDark
                                )
                                Text(
                                    text = if (isBengali) "কর্মচারীদের বিলিং, হাজিরা ও নগদ লেনদেনের সম্পূর্ণ রেকর্ড" else "Comprehensive audit trail of staff billing, attendance & cash events",
                                    fontSize = 11.sp,
                                    color = TextMuted
                                )
                            }
                        }

                        // WhatsApp / Share Report Button
                        IconButton(
                            onClick = {
                                val reportText = generateAuditReportText(
                                    storeName = StoreInfoManager.storeName,
                                    dateRange = selectedDateRange,
                                    staffFilter = selectedStaffFilter,
                                    allEmployees = allEmployees,
                                    salesCount = salesInScope.size,
                                    totalRevenue = totalRevenue,
                                    cashSales = cashSales,
                                    onlineSales = onlineSales,
                                    creditSales = creditSales,
                                    totalDiscount = totalDiscount,
                                    activities = activitiesList.take(30),
                                    isBengali = isBengali
                                )
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, reportText)
                                    type = "text/plain"
                                }
                                context.startActivity(Intent.createChooser(sendIntent, if (isBengali) "অডিট লগ রিপোর্ট পাঠান" else "Share Staff Audit Report"))
                            }
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Share", tint = StorePrimary)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // KPI Metrics Grid
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Metric 1: Total Billed
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = StoreGreenProfit.copy(alpha = 0.08f),
                            border = BorderStroke(1.dp, StoreGreenProfit.copy(alpha = 0.3f)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = if (isBengali) "মোট বিক্রয়" else "Total Billed",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = StoreGreenProfit
                                )
                                Text(
                                    text = "₹%.2f".format(Locale.US, totalRevenue),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = StoreGreenProfit
                                )
                                Text(
                                    text = if (isBengali) "${salesInScope.size} টি চালান" else "${salesInScope.size} bills",
                                    fontSize = 10.sp,
                                    color = TextMuted
                                )
                            }
                        }

                        // Metric 2: Cash vs Online
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = StoreGold.copy(alpha = 0.08f),
                            border = BorderStroke(1.dp, StoreGold.copy(alpha = 0.3f)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = if (isBengali) "নগদ / অনলাইন" else "Cash / Online",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = StoreGold
                                )
                                Text(
                                    text = "₹%.0f / ₹%.0f".format(Locale.US, cashSales, onlineSales),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextDark
                                )
                                Text(
                                    text = if (isBengali) "বাকি: ₹%.0f".format(Locale.US, creditSales) else "Due: ₹%.0f".format(Locale.US, creditSales),
                                    fontSize = 10.sp,
                                    color = TextMuted
                                )
                            }
                        }

                        // Metric 3: Total Discount
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = StorePrimary.copy(alpha = 0.08f),
                            border = BorderStroke(1.dp, StorePrimary.copy(alpha = 0.3f)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = if (isBengali) "প্রদত্ত ছাড়" else "Discounts",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = StorePrimary
                                )
                                Text(
                                    text = "₹%.2f".format(Locale.US, totalDiscount),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = StorePrimary
                                )
                                Text(
                                    text = if (isBengali) "${activitiesList.size} কার্যক্রম" else "${activitiesList.size} events",
                                    fontSize = 10.sp,
                                    color = TextMuted
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. Staff Member Filter Horizontal Row
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = if (isBengali) "স্টাফ নির্বাচন করুন" else "Filter by Staff Member",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextDark
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedStaffFilter == "ALL",
                        onClick = { selectedStaffFilter = "ALL" },
                        label = { Text(if (isBengali) "সকল স্টাফ" else "All Staff") },
                        leadingIcon = { Icon(Icons.Default.Groups, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = StorePrimary.copy(alpha = 0.15f),
                            selectedLabelColor = StorePrimary
                        )
                    )

                    FilterChip(
                        selected = selectedStaffFilter == "OWNER",
                        onClick = { selectedStaffFilter = "OWNER" },
                        label = { Text(if (isBengali) "দোকানের মালিক" else "Store Owner") },
                        leadingIcon = { Icon(Icons.Default.AdminPanelSettings, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = StorePrimary.copy(alpha = 0.15f),
                            selectedLabelColor = StorePrimary
                        )
                    )

                    allEmployees.forEach { emp ->
                        FilterChip(
                            selected = selectedStaffFilter == emp.id,
                            onClick = { selectedStaffFilter = emp.id },
                            label = { Text(emp.name) },
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = StorePrimary.copy(alpha = 0.15f),
                                selectedLabelColor = StorePrimary
                            )
                        )
                    }
                }
            }
        }

        // 3. Date Range Selector & Category Filter Chips
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Date Range Filters
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LogDateRange.values().forEach { range ->
                        val isSel = selectedDateRange == range
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = if (isSel) StorePrimary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.clickable { selectedDateRange = range }
                        ) {
                            Text(
                                text = if (isBengali) range.labelBn else range.labelEn,
                                fontSize = 11.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSel) Color.White else TextDark,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                // Activity Category Filters
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LogActivityType.values().forEach { cat ->
                        val isSel = selectedCategory == cat
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSel) cat.color.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, if (isSel) cat.color else Color.LightGray.copy(alpha = 0.4f)),
                            modifier = Modifier.clickable { selectedCategory = cat }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    cat.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = if (isSel) cat.color else TextMuted
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isBengali) cat.labelBn else cat.labelEn,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSel) cat.color else TextDark
                                )
                            }
                        }
                    }
                }

                // Search Box
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            if (isBengali) "চালান নং, কাস্টমার বা স্টাফ নাম খুঁজুন..." else "Search by Invoice #, Customer or Staff...",
                            fontSize = 12.sp
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp)
                )
            }
        }

        // 4. Activity Timeline List
        if (activitiesList.isEmpty()) {
            item {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.HistoryToggleOff, contentDescription = null, tint = TextMuted, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = if (isBengali) "কোন কার্যকলাপ পাওয়া যায়নি" else "No activities found in this period",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextDark
                        )
                        Text(
                            text = if (isBengali) "তারিখের সীমা পরিবর্তন করুন অথবা ফিল্টার রিসেট করুন" else "Try adjusting the date range or selecting a different staff filter",
                            fontSize = 12.sp,
                            color = TextMuted,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(activitiesList, key = { it.id }, contentType = { it.category.name }) { activity ->
                ActivityItemCard(
                    activity = activity,
                    isBengali = isBengali,
                    onClick = { selectedActivityDetail = activity }
                )
            }
        }
    }

    // Activity Detail BottomSheet / Dialog
    if (selectedActivityDetail != null) {
        ActivityDetailDialog(
            item = selectedActivityDetail!!,
            isBengali = isBengali,
            onDismiss = { selectedActivityDetail = null }
        )
    }
}

@Composable
private fun ActivityItemCard(
    activity: StaffActivityItem,
    isBengali: Boolean,
    onClick: () -> Unit
) {
    val timeFormatter = remember { SimpleDateFormat("hh:mm a, dd MMM yyyy", Locale.US) }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon Badge
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(activity.category.color.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    activity.category.icon,
                    contentDescription = null,
                    tint = activity.category.color,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Main Info
            Column(modifier = Modifier.weight(1f)) {
                when (activity) {
                    is StaffActivityItem.SaleActivity -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Bill #${activity.invoiceNo}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextDark
                            )
                            Text(
                                text = "₹%.2f".format(Locale.US, activity.totalAmount),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = StoreGreenProfit
                            )
                        }

                        Spacer(modifier = Modifier.height(2.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Staff Name Badge
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = StorePrimary.copy(alpha = 0.1f)
                            ) {
                                Text(
                                    text = "👤 ${activity.staffName}",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = StorePrimary,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }

                            // Payment Mode Badge
                            val paymentColor = when (activity.paymentMode.uppercase()) {
                                "CASH" -> StoreGreenProfit
                                "UPI", "ONLINE" -> Color(0xFF1976D2)
                                else -> StoreGold
                            }
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = paymentColor.copy(alpha = 0.12f)
                            ) {
                                Text(
                                    text = activity.paymentMode.uppercase(),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = paymentColor,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }

                            if (activity.discount > 0.0) {
                                Text(
                                    text = "(-₹%.0f)".format(Locale.US, activity.discount),
                                    fontSize = 10.sp,
                                    color = StoreRedPrimary
                                )
                            }
                        }

                        if (!activity.customerName.isNullOrBlank()) {
                            Text(
                                text = "Customer: ${activity.customerName}",
                                fontSize = 11.sp,
                                color = TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    is StaffActivityItem.AttendanceActivity -> {
                        val att = activity.attendance
                        val statusColor = when (att.status.uppercase()) {
                            "PRESENT" -> StoreGreenProfit
                            "LATE" -> StoreGold
                            "HALF_DAY" -> Color(0xFFE65100)
                            "ABSENT" -> StoreRedPrimary
                            else -> Color(0xFF1976D2)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = att.employeeName,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextDark
                            )
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = statusColor.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = att.status,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = statusColor,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Date: ${att.date} • In: ${att.checkInTime} | Out: ${att.checkOutTime}",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    }

                    is StaffActivityItem.PayrollActivity -> {
                        val pay = activity.payment
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Salary: ${pay.employeeName}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextDark
                            )
                            Text(
                                text = "₹%.2f".format(Locale.US, pay.netSalaryPaid),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF673AB7)
                            )
                        }
                        Text(
                            text = "Month: ${pay.monthYear} • Mode: ${pay.paymentMode}",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    }

                    is StaffActivityItem.AdvanceActivity -> {
                        val adv = activity.advance
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${adv.type}: ${adv.employeeName}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextDark
                            )
                            Text(
                                text = "₹%.2f".format(Locale.US, adv.amount),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = StoreGold
                            )
                        }
                        Text(
                            text = "Date: ${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(adv.date))}${if (adv.reason.isNotBlank()) " • ${adv.reason}" else ""}",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    }

                    is StaffActivityItem.UserAccessActivity -> {
                        val user = activity.userRole
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = user.displayName?.takeIf { it.isNotBlank() } ?: user.email ?: "App User",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextDark
                            )
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = StorePrimary.copy(alpha = 0.12f)
                            ) {
                                Text(
                                    text = user.role.uppercase(),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = StorePrimary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = "Email: ${user.email ?: "N/A"}",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = timeFormatter.format(Date(activity.timestamp)),
                    fontSize = 10.sp,
                    color = TextMuted.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun ActivityDetailDialog(
    item: StaffActivityItem,
    isBengali: Boolean,
    onDismiss: () -> Unit
) {
    val timeFormatter = remember { SimpleDateFormat("hh:mm a, dd MMMM yyyy", Locale.US) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(item.category.icon, contentDescription = null, tint = item.category.color, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when (item) {
                        is StaffActivityItem.SaleActivity -> if (isBengali) "চালান বিবরণী (#${item.invoiceNo})" else "Invoice Details (#${item.invoiceNo})"
                        is StaffActivityItem.AttendanceActivity -> if (isBengali) "হাজিরা বিবরণী" else "Attendance Details"
                        is StaffActivityItem.PayrollActivity -> if (isBengali) "বেতন লেনদেন বিবরণী" else "Salary Payment Details"
                        is StaffActivityItem.AdvanceActivity -> if (isBengali) "অগ্রিম বিবরণী" else "Advance Details"
                        is StaffActivityItem.UserAccessActivity -> if (isBengali) "ইউজার লগ বিবরণী" else "User Account Details"
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailRow(label = if (isBengali) "স্টাফ কর্মী" else "Staff Member", value = item.staffName)
                DetailRow(label = if (isBengali) "তারিখ ও সময়" else "Timestamp", value = timeFormatter.format(Date(item.timestamp)))

                when (item) {
                    is StaffActivityItem.SaleActivity -> {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        DetailRow(label = if (isBengali) "মোট পরিমাণ" else "Total Amount", value = "₹%.2f".format(Locale.US, item.totalAmount), isBold = true)
                        DetailRow(label = if (isBengali) "পেমেন্ট মোড" else "Payment Mode", value = item.paymentMode)
                        if (item.discount > 0.0) {
                            DetailRow(label = if (isBengali) "ডিসকাউন্ট" else "Discount", value = "₹%.2f".format(Locale.US, item.discount))
                        }
                        if (!item.customerName.isNullOrBlank()) {
                            DetailRow(label = if (isBengali) "কাস্টমার" else "Customer", value = item.customerName)
                        }
                        DetailRow(label = if (isBengali) "পণ্যের সংখ্যা" else "Items Count", value = "${item.itemsCount} items")
                    }

                    is StaffActivityItem.AttendanceActivity -> {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        DetailRow(label = if (isBengali) "উপস্থিতি স্ট্যাটাস" else "Status", value = item.attendance.status, isBold = true)
                        DetailRow(label = if (isBengali) "ইন সময়" else "Check In", value = item.attendance.checkInTime)
                        DetailRow(label = if (isBengali) "আউট সময়" else "Check Out", value = item.attendance.checkOutTime)
                        if (item.attendance.overtimeHours > 0) {
                            DetailRow(label = if (isBengali) "ওভারটাইম" else "Overtime", value = "${item.attendance.overtimeHours} hrs")
                        }
                        if (!item.attendance.notes.isNullOrBlank()) {
                            DetailRow(label = if (isBengali) "মন্তব্য" else "Notes", value = item.attendance.notes)
                        }
                    }

                    is StaffActivityItem.PayrollActivity -> {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        DetailRow(label = if (isBengali) "বেতনের মাস" else "Month", value = item.payment.monthYear)
                        DetailRow(label = if (isBengali) "পরিশোধের পরিমাণ" else "Amount Paid", value = "₹%.2f".format(Locale.US, item.payment.netSalaryPaid), isBold = true)
                        DetailRow(label = if (isBengali) "পেমেন্ট মাধ্যম" else "Payment Mode", value = item.payment.paymentMode)
                        if (item.payment.notes.isNotBlank()) {
                            DetailRow(label = if (isBengali) "মন্তব্য" else "Notes", value = item.payment.notes)
                        }
                    }

                    is StaffActivityItem.AdvanceActivity -> {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        DetailRow(label = if (isBengali) "লেনদেনের ধরন" else "Type", value = item.advance.type)
                        DetailRow(label = if (isBengali) "পরিমাণ" else "Amount", value = "₹%.2f".format(Locale.US, item.advance.amount), isBold = true)
                        if (item.advance.reason.isNotBlank()) {
                            DetailRow(label = if (isBengali) "বিবরণ" else "Reason", value = item.advance.reason)
                        }
                    }

                    is StaffActivityItem.UserAccessActivity -> {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        DetailRow(label = if (isBengali) "ইমেইল" else "Email", value = item.userRole.email ?: "N/A")
                        DetailRow(label = if (isBengali) "ভূমিকা ও অধিকার" else "Role", value = item.userRole.role, isBold = true)
                        DetailRow(label = if (isBengali) "অনুমোদিত সেলস" else "Can Make Sales", value = if (item.userRole.canMakeSales) "Yes" else "No")
                        DetailRow(label = if (isBengali) "রিপোর্ট অ্যাক্সেস" else "Can View Reports", value = if (item.userRole.canViewReports) "Yes" else "No")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(if (isBengali) "বন্ধ করুন" else "Close")
            }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String, isBold: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 12.sp, color = TextMuted)
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
            color = TextDark
        )
    }
}

private fun generateAuditReportText(
    storeName: String,
    dateRange: LogDateRange,
    staffFilter: String?,
    allEmployees: List<Employee>,
    salesCount: Int,
    totalRevenue: Double,
    cashSales: Double,
    onlineSales: Double,
    creditSales: Double,
    totalDiscount: Double,
    activities: List<StaffActivityItem>,
    isBengali: Boolean
): String {
    val staffTitle = when {
        staffFilter == null || staffFilter == "ALL" -> if (isBengali) "সকল স্টাফ" else "All Staff"
        staffFilter == "OWNER" -> if (isBengali) "দোকানের মালিক" else "Store Owner"
        else -> allEmployees.find { it.id == staffFilter }?.name ?: staffFilter
    }

    val sb = StringBuilder()
    sb.append("📋 *${if (isBengali) "স্টাফ অডিট ও সেলস রিপোর্ট" else "Staff Audit & Sales Report"}*\n")
    sb.append("🏬 $storeName\n")
    sb.append("👤 ${if (isBengali) "স্টাফ:" else "Staff:"} $staffTitle\n")
    sb.append("📅 ${if (isBengali) "সময়সীমা:" else "Period:"} ${if (isBengali) dateRange.labelBn else dateRange.labelEn}\n")
    sb.append("--------------------------------\n")
    sb.append("📊 *${if (isBengali) "বিক্রয় ও আর্থিক সারাংশ" else "Sales Summary"}*\n")
    sb.append("• ${if (isBengali) "মোট চালান:" else "Total Invoices:"} $salesCount\n")
    sb.append("• ${if (isBengali) "মোট বিক্রয়:" else "Total Billed:"} ₹%.2f\n".format(Locale.US, totalRevenue))
    sb.append("• ${if (isBengali) "নগদ সংগ্রহ:" else "Cash Collected:"} ₹%.2f\n".format(Locale.US, cashSales))
    sb.append("• ${if (isBengali) "অনলাইন/UPI:" else "Online / UPI:"} ₹%.2f\n".format(Locale.US, onlineSales))
    sb.append("• ${if (isBengali) "বাকি/খাতা:" else "Due / Credit:"} ₹%.2f\n".format(Locale.US, creditSales))
    if (totalDiscount > 0.0) {
        sb.append("• ${if (isBengali) "প্রদত্ত ছাড়:" else "Discounts Given:"} ₹%.2f\n".format(Locale.US, totalDiscount))
    }
    sb.append("--------------------------------\n")
    sb.append("🕒 *${if (isBengali) "সাম্প্রতিক কার্যকলাপসমূহ:" else "Recent Activities:"}*\n")
    val timeFormat = SimpleDateFormat("dd MMM, hh:mm a", Locale.US)
    for (act in activities.take(15)) {
        when (act) {
            is StaffActivityItem.SaleActivity -> {
                sb.append("• [Sale] #${act.invoiceNo} | ₹%.2f | ${act.paymentMode} (${act.staffName})\n".format(Locale.US, act.totalAmount))
            }
            is StaffActivityItem.AttendanceActivity -> {
                sb.append("• [Attendance] ${act.staffName} -> ${act.attendance.status} (${act.attendance.date})\n")
            }
            is StaffActivityItem.PayrollActivity -> {
                sb.append("• [Salary] ${act.staffName} -> ₹%.2f (${act.payment.monthYear})\n".format(Locale.US, act.payment.netSalaryPaid))
            }
            is StaffActivityItem.AdvanceActivity -> {
                sb.append("• [Advance] ${act.staffName} -> ₹%.2f (${act.advance.type})\n".format(Locale.US, act.advance.amount))
            }
            is StaffActivityItem.UserAccessActivity -> {
                sb.append("• [Access] ${act.staffName} (${act.userRole.role})\n")
            }
        }
    }
    sb.append("--------------------------------\n")
    sb.append("Generated via Kali Mata POS")
    return sb.toString()
}
