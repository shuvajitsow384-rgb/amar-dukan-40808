package com.example.ui.screens.employees

import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.entities.Employee
import com.example.data.local.entities.EmployeeAdvance
import com.example.data.local.entities.EmployeeAttendance
import com.example.data.local.entities.EmployeeSalaryDue
import com.example.data.local.entities.EmployeeSalaryPayment
import com.example.ui.components.StaffAccessGate
import com.example.ui.components.UnifiedAddEditEmployeeDialog
import com.example.ui.theme.*
import com.example.utils.LanguageManager
import com.example.utils.SalaryBreakdown
import com.example.utils.SalaryCalculator
import com.example.utils.StaffManager
import com.example.utils.StoreInfoManager
import com.example.utils.WhatsAppHelper
import com.example.viewmodel.StoreViewModel
import java.text.SimpleDateFormat
import java.util.*

enum class EmployeeTab(val title: String, val titleBn: String, val icon: ImageVector) {
    STAFF("Staff", "স্টাফ তালিকা", Icons.Default.Badge),
    ATTENDANCE("Attendance", "হাজিরা", Icons.Default.EventAvailable),
    SALARY("Salary & Dues", "বেতন ও বকেয়া", Icons.Default.Payments),
    ADVANCES("Advances & Reimb.", "অগ্রিম ও খরচ", Icons.Default.AccountBalanceWallet),
    REPORT("Salary Report", "বেতন রিপোর্ট", Icons.Default.Assessment),
    LOGS("Audit Logs", "অডিট ও স্টাফ লগ", Icons.Default.History)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmployeesScreen(
    viewModel: StoreViewModel,
    initialTab: EmployeeTab = EmployeeTab.STAFF,
    onNavigateBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val isBengali = LanguageManager.isBengali

    val currentFirestoreRole by viewModel.currentFirestoreUserRole.collectAsState()
    val authUser by viewModel.currentUser.collectAsState()

    // RBAC Permission Check: Only Admin and Manager roles can view/edit salary, attendance, and advances
    val isStaffManagementAllowed = if (currentFirestoreRole != null) {
        currentFirestoreRole!!.isAdmin || currentFirestoreRole!!.isManager || currentFirestoreRole!!.canAccessSettings
    } else {
        StaffManager.isOwner() || StaffManager.activeStaff?.role == "STORE_MANAGER" || StaffManager.canAccessSettings()
    }

    if (!isStaffManagementAllowed) {
        StaffAccessGate(
            screenTitle = if (isBengali) "স্টাফ ও বেতন ব্যবস্থাপনা (সীমাবদ্ধ)" else "Staff & Payroll Management (Restricted)",
            screenDescription = if (isBengali)
                "কর্মচারীদের বেতন, উপস্থিতি, ও ঋণ সংক্রান্ত তথ্য দেখার জন্য অ্যাডমিন বা ম্যানেজার পিন প্রয়োজন।"
            else
                "Employee salary figures, attendance records, and advance ledgers are confidential and restricted to Admin and Store Manager roles."
        )
        return
    }

    val allEmployees by viewModel.allEmployees.collectAsState()
    val activeEmployees by viewModel.activeEmployees.collectAsState()

    val deduplicatedEmployees = remember(allEmployees) {
        val uniqueMap = mutableMapOf<String, Employee>()
        for (emp in allEmployees) {
            val key = if (emp.email.isNotBlank()) emp.email.trim().lowercase() else "id_${emp.id}"
            val existing = uniqueMap[key]
            if (existing == null) {
                uniqueMap[key] = emp
            } else {
                val merged = existing.copy(
                    name = if (existing.name.isNotBlank() && existing.name != "App User" && existing.name != "Employee") existing.name else emp.name,
                    phone = existing.phone.ifBlank { emp.phone },
                    baseSalary = if (existing.baseSalary > 0) existing.baseSalary else emp.baseSalary,
                    salaryType = if (existing.salaryType.isNotBlank()) existing.salaryType else emp.salaryType,
                    role = if (existing.role != "CASHIER") existing.role else emp.role,
                    designation = if (existing.designation != "Staff") existing.designation else emp.designation,
                    isActive = existing.isActive || emp.isActive
                )
                uniqueMap[key] = merged
            }
        }
        uniqueMap.values.toList()
    }
    val deduplicatedActiveEmployees = remember(deduplicatedEmployees) {
        deduplicatedEmployees.filter { it.isActive }
    }
    val selectedDate by viewModel.selectedAttendanceDate.collectAsState()
    val dailyAttendance by viewModel.dailyAttendanceList.collectAsState()
    val allSalaryDues by viewModel.allSalaryDues.collectAsState()
    val allSalaryPayments by viewModel.allSalaryPayments.collectAsState()
    val allAdvances by viewModel.allAdvances.collectAsState()

    var currentTab by remember(initialTab) { mutableStateOf(initialTab) }

    // BackHandler: If on a sub-tab, return to the main Staff list tab
    BackHandler(enabled = currentTab != EmployeeTab.STAFF) {
        currentTab = EmployeeTab.STAFF
    }

    // BackHandler: If on main Staff tab and onNavigateBack callback is provided, invoke it
    BackHandler(enabled = currentTab == EmployeeTab.STAFF && onNavigateBack != null) {
        onNavigateBack?.invoke()
    }
    var showAddEditEmployeeDialog by remember { mutableStateOf(false) }
    var employeeToEdit by remember { mutableStateOf<Employee?>(null) }
    var showPaySalaryDialog by remember { mutableStateOf(false) }
    var salaryEmployeeTarget by remember { mutableStateOf<Employee?>(null) }
    var showRecordDueDialog by remember { mutableStateOf(false) }
    var dueEmployeeTarget by remember { mutableStateOf<Employee?>(null) }
    var showAccrueMonthSalaryDialog by remember { mutableStateOf(false) }
    var selectedAccrualMonth by remember { mutableStateOf(SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date())) }
    var showGiveAdvanceDialog by remember { mutableStateOf(false) }
    var advanceEmployeeTarget by remember { mutableStateOf<Employee?>(null) }
    var defaultAdvanceType by remember { mutableStateOf("ADVANCE") }
    var showEmployeeAttendanceHistoryDialog by remember { mutableStateOf<Employee?>(null) }
    var showAllAppUsersDialog by remember { mutableStateOf(false) }

    // Summary calculation
    val todaySdf = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val isViewingToday = selectedDate == todaySdf.format(Date())

    val presentCount = dailyAttendance.count { it.status == "PRESENT" }
    val halfDayCount = dailyAttendance.count { it.status == "HALF_DAY" }
    val absentCount = dailyAttendance.count { it.status == "ABSENT" }

    // Total running balances across all staff
    val totalDuesAll = allSalaryDues.sumOf { it.dueAmount }
    val totalPaidAll = allSalaryPayments.sumOf { it.netSalaryPaid }
    val totalSalaryOwedAll = (totalDuesAll - totalPaidAll).coerceAtLeast(0.0)

    val totalAdvancesGiven = allAdvances.filter { it.type == "ADVANCE" }.sumOf { it.amount }
    val totalAdvancesRepaid = allAdvances.filter { it.type == "ADVANCE" }.sumOf { it.repaidAmount }
    val totalOutstandingAdvancesAll = (totalAdvancesGiven - totalAdvancesRepaid).coerceAtLeast(0.0)

    val totalReimbursementsClaimed = allAdvances.filter { it.type == "REIMBURSEMENT" }.sumOf { it.amount }
    val totalReimbursementsPaid = allAdvances.filter { it.type == "REIMBURSEMENT" }.sumOf { it.repaidAmount }
    val totalOutstandingReimbursementsAll = (totalReimbursementsClaimed - totalReimbursementsPaid).coerceAtLeast(0.0)

    val totalCommittedMonthlySalary = deduplicatedActiveEmployees.sumOf { it.baseSalary }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (isBengali) "কর্মচারী ও বেতন ব্যবস্থাপনা" else "Staff & Employee Hub",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${StoreInfoManager.storeName.ifBlank { "Store" }} • ${deduplicatedActiveEmployees.size} ${if (isBengali) "জন কর্মী • মোট বেতন: ₹${"%.0f".format(totalCommittedMonthlySalary)}/মাস" else "Active Staff • ₹${"%.0f".format(totalCommittedMonthlySalary)}/mo"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    if (onNavigateBack != null) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    val isOwnerOrAdmin = currentFirestoreRole?.isAdmin == true ||
                            StaffManager.isOwner() ||
                            viewModel.currentUser.value?.email?.trim()?.lowercase() in com.example.data.firestore.PERMANENT_ADMIN_EMAILS
                    if (isOwnerOrAdmin) {
                        IconButton(onClick = { showAllAppUsersDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Group,
                                contentDescription = "All App Users",
                                tint = Color(0xFF2563EB)
                            )
                        }
                    }
                    IconButton(onClick = {
                        employeeToEdit = null
                        showAddEditEmployeeDialog = true
                    }) {
                        Icon(
                            imageVector = Icons.Default.PersonAdd,
                            contentDescription = "Add Employee",
                            tint = StoreRedPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Stats Overview Cards Banner
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StatSummaryBadge(
                        title = if (isBengali) "উপস্থিত" else "Present",
                        value = "$presentCount/${deduplicatedActiveEmployees.size}",
                        color = ProfitGreen,
                        icon = Icons.Default.CheckCircle,
                        modifier = Modifier.weight(1f)
                    )
                    StatSummaryBadge(
                        title = if (isBengali) "বেতন বকেয়া" else "Salary Owed",
                        value = "₹${"%.0f".format(totalSalaryOwedAll)}",
                        color = if (totalSalaryOwedAll > 0) LossRed else ProfitGreen,
                        icon = Icons.Default.Payments,
                        modifier = Modifier.weight(1.2f)
                    )
                    StatSummaryBadge(
                        title = if (isBengali) "অগ্রিম ঋণ" else "Advances",
                        value = "₹${"%.0f".format(totalOutstandingAdvancesAll)}",
                        color = LossRed,
                        icon = Icons.Default.AccountBalanceWallet,
                        modifier = Modifier.weight(1.1f)
                    )
                    StatSummaryBadge(
                        title = if (isBengali) "ফেরতযোগ্য খরচ" else "Reimburse",
                        value = "₹${"%.0f".format(totalOutstandingReimbursementsAll)}",
                        color = StorePrimary,
                        icon = Icons.Default.ReceiptLong,
                        modifier = Modifier.weight(1.1f)
                    )
                }
            }

            // Tabs Selector
            ScrollableTabRow(
                selectedTabIndex = currentTab.ordinal,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = StoreRedPrimary,
                edgePadding = 8.dp
            ) {
                EmployeeTab.values().forEach { tab ->
                    val isSelected = currentTab == tab
                    Tab(
                        selected = isSelected,
                        onClick = { currentTab = tab },
                        text = {
                            Text(
                                text = if (isBengali) tab.titleBn else tab.title,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        icon = {
                            Icon(
                                tab.icon,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                }
            }

            // Tab Content
            Box(modifier = Modifier.weight(1f)) {
                when (currentTab) {
                    EmployeeTab.STAFF -> {
                        StaffOverviewTabContent(
                            viewModel = viewModel,
                            allEmployees = deduplicatedEmployees,
                            allSalaryDues = allSalaryDues,
                            allSalaryPayments = allSalaryPayments,
                            allAdvances = allAdvances,
                            isBengali = isBengali,
                            onEditEmployee = { emp ->
                                employeeToEdit = emp
                                showAddEditEmployeeDialog = true
                            },
                            onAddNewEmployee = {
                                employeeToEdit = null
                                showAddEditEmployeeDialog = true
                            },
                            onPaySalary = { emp ->
                                salaryEmployeeTarget = emp
                                showPaySalaryDialog = true
                            },
                            onRecordDue = { emp ->
                                dueEmployeeTarget = emp
                                showRecordDueDialog = true
                            },
                            onGiveAdvance = { emp ->
                                advanceEmployeeTarget = emp
                                defaultAdvanceType = "ADVANCE"
                                showGiveAdvanceDialog = true
                            },
                            onRecordReimbursement = { emp ->
                                advanceEmployeeTarget = emp
                                defaultAdvanceType = "REIMBURSEMENT"
                                showGiveAdvanceDialog = true
                            },
                            onViewAttendance = { emp ->
                                showEmployeeAttendanceHistoryDialog = emp
                            }
                        )
                    }
                    EmployeeTab.ATTENDANCE -> {
                        AttendanceTabContent(
                            viewModel = viewModel,
                            activeEmployees = deduplicatedActiveEmployees,
                            dailyAttendance = dailyAttendance,
                            selectedDate = selectedDate,
                            isBengali = isBengali,
                            onViewHistory = { emp -> showEmployeeAttendanceHistoryDialog = emp }
                        )
                    }
                    EmployeeTab.SALARY -> {
                        SalaryDuesPayrollTabContent(
                            viewModel = viewModel,
                            activeEmployees = deduplicatedActiveEmployees,
                            allSalaryDues = allSalaryDues,
                            allSalaryPayments = allSalaryPayments,
                            allAdvances = allAdvances,
                            isBengali = isBengali,
                            onPaySalary = { emp ->
                                salaryEmployeeTarget = emp
                                showPaySalaryDialog = true
                            },
                            onRecordDue = { emp ->
                                dueEmployeeTarget = emp
                                showRecordDueDialog = true
                            },
                            onOpenAccrueMonthDialog = { month ->
                                selectedAccrualMonth = month
                                showAccrueMonthSalaryDialog = true
                            }
                        )
                    }
                    EmployeeTab.ADVANCES -> {
                        AdvancesAndReimbursementsTabContent(
                            viewModel = viewModel,
                            allAdvances = allAdvances,
                            activeEmployees = deduplicatedActiveEmployees,
                            isBengali = isBengali,
                            onAddNewEntry = { type ->
                                advanceEmployeeTarget = null
                                defaultAdvanceType = type
                                showGiveAdvanceDialog = true
                            }
                        )
                    }
                    EmployeeTab.REPORT -> {
                        SalaryReportTabContent(
                            activeEmployees = deduplicatedActiveEmployees,
                            allSalaryDues = allSalaryDues,
                            allSalaryPayments = allSalaryPayments,
                            allAdvances = allAdvances,
                            isBengali = isBengali
                        )
                    }
                    EmployeeTab.LOGS -> {
                        StaffActivityLogsTabContent(
                            viewModel = viewModel,
                            allEmployees = deduplicatedEmployees,
                            isBengali = isBengali
                        )
                    }
                }
            }
        }
    }

    // Dialog: Add / Edit Employee
    if (showAddEditEmployeeDialog) {
        val isOwnerOrAdminUser = if (currentFirestoreRole != null) {
            currentFirestoreRole!!.isAdmin
        } else {
            StaffManager.isOwner()
        }

        UnifiedAddEditEmployeeDialog(
            initialEmployee = employeeToEdit,
            isOwnerOrAdmin = isOwnerOrAdminUser,
            onDismiss = { showAddEditEmployeeDialog = false },
            onSave = { emp ->
                if (employeeToEdit == null) {
                    viewModel.saveEmployee(emp) {
                        Toast.makeText(context, if (isBengali) "নতুন কর্মী সফলভাবে যুক্ত হয়েছে!" else "Employee added successfully!", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    viewModel.updateEmployee(emp) {
                        Toast.makeText(context, if (isBengali) "কর্মীর তথ্য আপডেট হয়েছে!" else "Employee updated successfully!", Toast.LENGTH_SHORT).show()
                    }
                }
                showAddEditEmployeeDialog = false
            }
        )
    }

    // Dialog: Record Salary Due
    if (showRecordDueDialog) {
        RecordSalaryDueDialog(
            targetEmployee = dueEmployeeTarget,
            activeEmployees = deduplicatedActiveEmployees,
            viewModel = viewModel,
            isBengali = isBengali,
            onDismiss = {
                showRecordDueDialog = false
                dueEmployeeTarget = null
            },
            onSave = { empId, empName, monthYear, amount, note ->
                viewModel.recordSalaryDue(empId, empName, monthYear, amount, note) { success, msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
                showRecordDueDialog = false
                dueEmployeeTarget = null
            }
        )
    }

    // Dialog: Accrue Month Salary Dues Modal
    if (showAccrueMonthSalaryDialog) {
        AccrueMonthSalaryDialog(
            initialMonthYear = selectedAccrualMonth,
            viewModel = viewModel,
            activeEmployees = deduplicatedActiveEmployees,
            allSalaryDues = allSalaryDues,
            isBengali = isBengali,
            onDismiss = { showAccrueMonthSalaryDialog = false },
            onAccrualComplete = {
                showAccrueMonthSalaryDialog = false
            }
        )
    }

    // Dialog: Pay Salary Modal
    if (showPaySalaryDialog && salaryEmployeeTarget != null) {
        PaySalaryModalDialog(
            employee = salaryEmployeeTarget!!,
            viewModel = viewModel,
            allSalaryDues = allSalaryDues,
            allSalaryPayments = allSalaryPayments,
            allAdvances = allAdvances,
            isBengali = isBengali,
            onDismiss = {
                showPaySalaryDialog = false
                salaryEmployeeTarget = null
            },
            onPaymentRecorded = { payment ->
                viewModel.recordSalaryPayment(payment) { success, msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    if (success) {
                        if (salaryEmployeeTarget!!.phone.isNotBlank()) {
                            val slip = WhatsAppHelper.generateSalarySlipText(payment, salaryEmployeeTarget)
                            WhatsAppHelper.sendWhatsAppMessage(context, salaryEmployeeTarget!!.phone, slip)
                        }
                    }
                }
                showPaySalaryDialog = false
                salaryEmployeeTarget = null
            }
        )
    }

    // Dialog: Give Advance / Record Reimbursement Modal
    if (showGiveAdvanceDialog) {
        GiveAdvanceOrReimbursementModalDialog(
            targetEmployee = advanceEmployeeTarget,
            activeEmployees = activeEmployees,
            initialType = defaultAdvanceType,
            isBengali = isBengali,
            onDismiss = {
                showGiveAdvanceDialog = false
                advanceEmployeeTarget = null
            },
            onSave = { empId, empName, type, amount, reason, mode ->
                viewModel.recordEmployeeAdvance(empId, empName, amount, reason, type, mode) { success, msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
                showGiveAdvanceDialog = false
                advanceEmployeeTarget = null
            }
        )
    }

    // Dialog: Monthly Attendance History
    if (showEmployeeAttendanceHistoryDialog != null) {
        val target = showEmployeeAttendanceHistoryDialog!!
        EmployeeMonthlyAttendanceDialog(
            employee = target,
            viewModel = viewModel,
            isBengali = isBengali,
            onDismiss = { showEmployeeAttendanceHistoryDialog = null }
        )
    }

    // Dialog: All App Users & Sign-In History Audit (Owner / Admin only)
    if (showAllAppUsersDialog) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showAllAppUsersDialog = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                com.example.ui.screens.users.AllAppUsersScreen(
                    viewModel = viewModel,
                    onNavigateBack = { showAllAppUsersDialog = false }
                )
            }
        }
    }
}

// -------------------------------------------------------------
// TAB 1: STAFF OVERVIEW & AT-A-GLANCE STATUS
// -------------------------------------------------------------
@Composable
fun StaffOverviewTabContent(
    viewModel: StoreViewModel,
    allEmployees: List<Employee>,
    allSalaryDues: List<EmployeeSalaryDue>,
    allSalaryPayments: List<EmployeeSalaryPayment>,
    allAdvances: List<EmployeeAdvance>,
    isBengali: Boolean,
    onEditEmployee: (Employee) -> Unit,
    onAddNewEmployee: () -> Unit,
    onPaySalary: (Employee) -> Unit,
    onRecordDue: (Employee) -> Unit,
    onGiveAdvance: (Employee) -> Unit,
    onRecordReimbursement: (Employee) -> Unit,
    onViewAttendance: (Employee) -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }

    val filteredEmployees = remember(allEmployees, searchQuery) {
        if (searchQuery.isBlank()) allEmployees
        else allEmployees.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.phone.contains(searchQuery, ignoreCase = true) ||
            it.role.contains(searchQuery, ignoreCase = true) ||
            it.designation.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(if (isBengali) "স্টাফ খুঁজুন..." else "Search staff...", fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(10.dp)
            )

            Button(
                onClick = onAddNewEmployee,
                colors = ButtonDefaults.buttonColors(containerColor = StoreRedPrimary),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (isBengali) "যোগ করুন" else "Add Staff", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (filteredEmployees.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.PeopleOutline, contentDescription = null, modifier = Modifier.size(56.dp), tint = TextMuted)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (isBengali) "কোন কর্মী পাওয়া যায়নি" else "No employees found",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredEmployees, key = { it.id }) { employee ->
                    // Running Balance Calculations strictly from history:
                    val totalDues = allSalaryDues.filter { it.employeeId == employee.id }.sumOf { it.dueAmount }
                    val totalPaid = allSalaryPayments.filter { it.employeeId == employee.id }.sumOf { it.netSalaryPaid }
                    val salaryOwed = (totalDues - totalPaid).coerceAtLeast(0.0)

                    val advancesGiven = allAdvances.filter { it.employeeId == employee.id && it.type == "ADVANCE" }.sumOf { it.amount }
                    val advancesRepaid = allAdvances.filter { it.employeeId == employee.id && it.type == "ADVANCE" }.sumOf { it.repaidAmount }
                    val outstandingAdvance = (advancesGiven - advancesRepaid).coerceAtLeast(0.0)

                    val reimbursementsClaimed = allAdvances.filter { it.employeeId == employee.id && it.type == "REIMBURSEMENT" }.sumOf { it.amount }
                    val reimbursementsPaid = allAdvances.filter { it.employeeId == employee.id && it.type == "REIMBURSEMENT" }.sumOf { it.repaidAmount }
                    val outstandingReimbursement = (reimbursementsClaimed - reimbursementsPaid).coerceAtLeast(0.0)

                    val netPayable = (salaryOwed + outstandingReimbursement - outstandingAdvance)

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(12.dp),
                        elevation = CardDefaults.cardElevation(2.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            // Staff Header Row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Surface(
                                        shape = CircleShape,
                                        color = if (employee.isActive) StoreRedPrimary.copy(alpha = 0.12f) else Color.Gray.copy(alpha = 0.15f),
                                        modifier = Modifier.size(42.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = employee.name.take(1).uppercase(),
                                                fontWeight = FontWeight.Bold,
                                                color = if (employee.isActive) StoreRedPrimary else Color.Gray,
                                                fontSize = 18.sp
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = employee.name,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = TextDark
                                            )
                                            if (!employee.isActive) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = Color.Gray.copy(alpha = 0.2f)
                                                ) {
                                                    Text(
                                                        text = "INACTIVE",
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color.DarkGray,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                        }
                                        val joinDateFormatted = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(employee.joiningDate))
                                        Text(
                                            text = "${employee.designation.ifBlank { employee.role }} • Joined: $joinDateFormatted",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextMuted
                                        )
                                    }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (employee.phone.isNotBlank()) {
                                        IconButton(
                                            onClick = {
                                                try {
                                                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${employee.phone}"))
                                                    context.startActivity(intent)
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, "Could not open dialer", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Icon(Icons.Default.Call, contentDescription = "Call", tint = StorePrimary, modifier = Modifier.size(16.dp))
                                        }

                                        IconButton(
                                            onClick = {
                                                WhatsAppHelper.sendWhatsAppMessage(context, employee.phone, "Hello ${employee.name}, from ${StoreInfoManager.storeName}.")
                                            },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Icon(Icons.Default.Chat, contentDescription = "WhatsApp", tint = ProfitGreen, modifier = Modifier.size(16.dp))
                                        }
                                    }

                                    IconButton(
                                        onClick = { onEditEmployee(employee) },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit", tint = TextDark, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Spacer(modifier = Modifier.height(10.dp))

                            // Running Balance Breakdown (At a Glance)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(
                                        text = if (isBengali) "মাসিক নির্দিষ্ট বেতন" else "Monthly Salary",
                                        fontSize = 11.sp,
                                        color = TextMuted
                                    )
                                    Text(
                                        text = "₹${"%.0f".format(employee.baseSalary)} / mo",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextDark
                                    )
                                }

                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = if (isBengali) "বকেয়া বেতন" else "Salary Owed",
                                        fontSize = 11.sp,
                                        color = if (salaryOwed > 0) LossRed else TextMuted
                                    )
                                    Text(
                                        text = if (salaryOwed > 0) "₹${"%.2f".format(salaryOwed)}" else "Cleared (₹0)",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (salaryOwed > 0) LossRed else ProfitGreen
                                    )
                                }

                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = if (isBengali) "অগ্রিম ঋণ / খরচ" else "Advance / Reimb.",
                                        fontSize = 11.sp,
                                        color = if (outstandingAdvance > 0) LossRed else if (outstandingReimbursement > 0) StorePrimary else TextMuted
                                    )
                                    val advText = when {
                                        outstandingAdvance > 0 && outstandingReimbursement > 0 -> "Adv: ₹${"%.0f".format(outstandingAdvance)} | Reimb: ₹${"%.0f".format(outstandingReimbursement)}"
                                        outstandingAdvance > 0 -> "Adv: ₹${"%.0f".format(outstandingAdvance)}"
                                        outstandingReimbursement > 0 -> "Reimb: ₹${"%.0f".format(outstandingReimbursement)}"
                                        else -> "None (₹0)"
                                    }
                                    Text(
                                        text = advText,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (outstandingAdvance > 0) LossRed else if (outstandingReimbursement > 0) StorePrimary else ProfitGreen
                                    )
                                }
                            }

                            // Net Status Pill
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (netPayable > 0) LossRed.copy(alpha = 0.08f) else if (netPayable < 0) StoreGold.copy(alpha = 0.12f) else ProfitGreen.copy(alpha = 0.08f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (netPayable > 0) "Shop Owes Staff (Net Payable):" else if (netPayable < 0) "Staff Owes Shop (Net Advance):" else "All Accounts Settled:",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextDark
                                    )
                                    Text(
                                        text = "₹${"%.2f".format(Math.abs(netPayable))}",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (netPayable > 0) LossRed else if (netPayable < 0) StoreGold else ProfitGreen
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Quick Action Buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { onRecordDue(employee) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp)
                                ) {
                                    Text(if (isBengali) "+ বকেয়া" else "+ Due", fontSize = 11.sp)
                                }

                                OutlinedButton(
                                    onClick = { onGiveAdvance(employee) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp)
                                ) {
                                    Text(if (isBengali) "অগ্রিম" else "Advance", fontSize = 11.sp)
                                }

                                OutlinedButton(
                                    onClick = { onRecordReimbursement(employee) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp)
                                ) {
                                    Text(if (isBengali) "ফেরত খরচ" else "Reimburse", fontSize = 11.sp)
                                }

                                Button(
                                    onClick = { onPaySalary(employee) },
                                    modifier = Modifier.weight(1.3f),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = StoreRedPrimary),
                                    contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp)
                                ) {
                                    Icon(Icons.Default.Payments, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (isBengali) "বেতন প্রদান" else "Pay Salary", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// TAB 2: ATTENDANCE TAB CONTENT
// -------------------------------------------------------------
@Composable
fun AttendanceTabContent(
    viewModel: StoreViewModel,
    activeEmployees: List<Employee>,
    dailyAttendance: List<EmployeeAttendance>,
    selectedDate: String,
    isBengali: Boolean,
    onViewHistory: (Employee) -> Unit
) {
    val context = LocalContext.current
    val sdfDisplay = remember { SimpleDateFormat("dd MMMM yyyy (EEEE)", Locale.getDefault()) }
    val sdfIso = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }

    val parsedDate = remember(selectedDate) {
        try { sdfIso.parse(selectedDate) ?: Date() } catch (e: Exception) { Date() }
    }

    val displayDateStr = remember(parsedDate) { sdfDisplay.format(parsedDate) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Date Navigation Bar
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(2.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = {
                        val cal = Calendar.getInstance().apply { time = parsedDate; add(Calendar.DAY_OF_YEAR, -1) }
                        viewModel.setSelectedAttendanceDate(sdfIso.format(cal.time))
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous Day")
                }

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            val cal = Calendar.getInstance().apply { time = parsedDate }
                            DatePickerDialog(
                                context,
                                { _, year, month, dayOfMonth ->
                                    val newCal = Calendar.getInstance().apply {
                                        set(year, month, dayOfMonth)
                                    }
                                    viewModel.setSelectedAttendanceDate(sdfIso.format(newCal.time))
                                },
                                cal.get(Calendar.YEAR),
                                cal.get(Calendar.MONTH),
                                cal.get(Calendar.DAY_OF_MONTH)
                            ).show()
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CalendarMonth,
                        contentDescription = null,
                        tint = StoreRedPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = displayDateStr,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                IconButton(
                    onClick = {
                        val cal = Calendar.getInstance().apply { time = parsedDate; add(Calendar.DAY_OF_YEAR, 1) }
                        viewModel.setSelectedAttendanceDate(sdfIso.format(cal.time))
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next Day")
                }
            }
        }

        // Attendance Info Banner (Daily Attendance Calculation Notice)
        Surface(
            color = StoreRedPrimary.copy(alpha = 0.08f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Calculate, contentDescription = null, tint = StoreRedPrimary, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isBengali) "মাসিক বেতন দৈনিক হাজিরার ভিত্তিতে হিসাব করা হয়: উপস্থিতির জন্য সম্পূর্ণ হার, হাফ-ডে-র জন্য অর্ধেক এবং অনুপস্থিতির জন্য ₹০।" 
                           else "Monthly salary is calculated from daily attendance: Full pay for Present, Half pay for Half-day, ₹0 for Absent.",
                    fontSize = 11.sp,
                    color = StoreRedPrimary,
                    lineHeight = 14.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Quick Action: Mark All Present
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isBengali) "দৈনিক হাজিরা তালিকা" else "Staff Attendance List",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = TextDark
            )

            Button(
                onClick = {
                    viewModel.markAllEmployeesPresent(selectedDate) {
                        Toast.makeText(context, if (isBengali) "সকল কর্মীকে উপস্থিত চিহ্নিত করা হয়েছে" else "Marked all staff as Present!", Toast.LENGTH_SHORT).show()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = ProfitGreen),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Icon(Icons.Default.DoneAll, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (isBengali) "সবাইকে প্রেজেন্ট করুন" else "Mark All Present", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (activeEmployees.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.PeopleOutline, contentDescription = null, modifier = Modifier.size(56.dp), tint = TextMuted)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (isBengali) "কোন সক্রিয় কর্মী নেই" else "No active employees found",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(activeEmployees, key = { it.id }) { employee ->
                    val attendanceRecord = dailyAttendance.find { it.employeeId == employee.id }
                    AttendanceEmployeeCard(
                        employee = employee,
                        attendance = attendanceRecord,
                        isBengali = isBengali,
                        onStatusSelected = { status ->
                            viewModel.markAttendance(
                                employee = employee,
                                status = status,
                                checkInTime = attendanceRecord?.checkInTime.takeIf { !it.isNullOrBlank() } ?: "09:00 AM",
                                checkOutTime = attendanceRecord?.checkOutTime.takeIf { !it.isNullOrBlank() } ?: "08:00 PM"
                            )
                        },
                        onViewMonthlyHistory = { onViewHistory(employee) }
                    )
                }
            }
        }
    }
}

@Composable
fun AttendanceEmployeeCard(
    employee: Employee,
    attendance: EmployeeAttendance?,
    isBengali: Boolean,
    onStatusSelected: (String) -> Unit,
    onViewMonthlyHistory: () -> Unit
) {
    val currentStatus = attendance?.status

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(1.5.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Surface(
                        shape = CircleShape,
                        color = StoreRedPrimary.copy(alpha = 0.12f),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = employee.name.take(1).uppercase(),
                                fontWeight = FontWeight.Bold,
                                color = StoreRedPrimary,
                                fontSize = 16.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = employee.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextDark
                        )
                        Text(
                            text = "${employee.designation.ifBlank { employee.role }} • ₹${"%.0f".format(employee.baseSalary)} / mo",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                }

                IconButton(
                    onClick = onViewMonthlyHistory,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CalendarMonth,
                        contentDescription = "Attendance Calendar",
                        tint = StorePrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 3 Standard Status Action Chips + Leave
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AttendanceStatusChip(
                    label = if (isBengali) "উপস্থিত" else "Present",
                    isSelected = currentStatus == "PRESENT",
                    color = ProfitGreen,
                    modifier = Modifier.weight(1f),
                    onClick = { onStatusSelected("PRESENT") }
                )
                AttendanceStatusChip(
                    label = if (isBengali) "অনুপস্থিত" else "Absent",
                    isSelected = currentStatus == "ABSENT",
                    color = StoreRedPrimary,
                    modifier = Modifier.weight(1f),
                    onClick = { onStatusSelected("ABSENT") }
                )
                AttendanceStatusChip(
                    label = if (isBengali) "হাফ-ডে" else "Half Day",
                    isSelected = currentStatus == "HALF_DAY",
                    color = WarningOrange,
                    modifier = Modifier.weight(1f),
                    onClick = { onStatusSelected("HALF_DAY") }
                )
            }
        }
    }
}

@Composable
fun AttendanceStatusChip(
    label: String,
    isSelected: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) color else color.copy(alpha = 0.08f),
        contentColor = if (isSelected) Color.White else color,
        border = if (isSelected) null else androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.35f)),
        modifier = modifier
            .height(34.dp)
            .clickable { onClick() }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}

// -------------------------------------------------------------
// TAB 3: SALARY DUES & PAYROLL LEDGER
// -------------------------------------------------------------
@Composable
fun SalaryDuesPayrollTabContent(
    viewModel: StoreViewModel,
    activeEmployees: List<Employee>,
    allSalaryDues: List<EmployeeSalaryDue>,
    allSalaryPayments: List<EmployeeSalaryPayment>,
    allAdvances: List<EmployeeAdvance>,
    isBengali: Boolean,
    onPaySalary: (Employee) -> Unit,
    onRecordDue: (Employee) -> Unit,
    onOpenAccrueMonthDialog: (String) -> Unit
) {
    val context = LocalContext.current
    var selectedMonth by remember { mutableStateOf(SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date())) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            // Month Accrual & Summary Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = StoreRedPrimary),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = if (isBengali) "মাসিক বেতন ও বকেয়া হিসাব" else "MONTHLY SALARY DUES & PAYROLL",
                                color = Color.White.copy(alpha = 0.8f),
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text(
                                text = selectedMonth,
                                color = Color.White,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Month Prev / Next Navigation buttons
                        Row {
                            IconButton(
                                onClick = {
                                    val cal = SalaryCalculator.parseMonthYear(selectedMonth)
                                    cal.add(Calendar.MONTH, -1)
                                    selectedMonth = SimpleDateFormat("MMMM yyyy", Locale.ENGLISH).format(cal.time)
                                }
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous Month", tint = Color.White)
                            }
                            IconButton(
                                onClick = {
                                    val cal = SalaryCalculator.parseMonthYear(selectedMonth)
                                    cal.add(Calendar.MONTH, 1)
                                    selectedMonth = SimpleDateFormat("MMMM yyyy", Locale.ENGLISH).format(cal.time)
                                }
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next Month", tint = Color.White)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    val duesForMonth = allSalaryDues.filter { it.monthYear.equals(selectedMonth, ignoreCase = true) }
                    val totalMonthDues = duesForMonth.sumOf { it.dueAmount }

                    val paymentsForMonth = allSalaryPayments.filter { it.monthYear.equals(selectedMonth, ignoreCase = true) }
                    val totalMonthPaid = paymentsForMonth.sumOf { it.netSalaryPaid }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(text = "Accrued Dues:", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
                            Text(text = "₹${"%.0f".format(totalMonthDues)}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                        Column {
                            Text(text = "Disbursed / Paid:", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
                            Text(text = "₹${"%.0f".format(totalMonthPaid)}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(text = "Staff Paid:", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
                            Text(
                                text = "${paymentsForMonth.map { it.employeeId }.distinct().size} / ${activeEmployees.size}",
                                color = StoreGold,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Button to open Attendance-based Month Accrual Dialog
                    Button(
                        onClick = {
                            onOpenAccrueMonthDialog(selectedMonth)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.PostAdd, contentDescription = null, tint = StoreRedPrimary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isBengali) "$selectedMonth এর বেতন বকেয়া হিসাব ও নথিভুক্ত করুন" else "Accrue / Calculate $selectedMonth Salary Dues",
                            color = StoreRedPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        item {
            Text(
                text = if (isBengali) "কর্মীদের চলতি বকেয়া ও পরিশোধ" else "Staff Running Dues & Salary Payments",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = TextDark
            )
        }

        items(activeEmployees, key = { it.id }) { employee ->
            val totalDues = allSalaryDues.filter { it.employeeId == employee.id }.sumOf { it.dueAmount }
            val totalPaid = allSalaryPayments.filter { it.employeeId == employee.id }.sumOf { it.netSalaryPaid }
            val runningOwed = (totalDues - totalPaid).coerceAtLeast(0.0)

            val pendingAdvance = allAdvances
                .filter { it.employeeId == employee.id && it.type == "ADVANCE" }
                .sumOf { it.amount - it.repaidAmount }
                .coerceAtLeast(0.0)

            val duesCount = allSalaryDues.count { it.employeeId == employee.id }
            val paymentsCount = allSalaryPayments.count { it.employeeId == employee.id }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = StorePrimary.copy(alpha = 0.12f),
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = employee.name.take(1).uppercase(),
                                        fontWeight = FontWeight.Bold,
                                        color = StorePrimary,
                                        fontSize = 16.sp
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = employee.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = TextDark
                                )
                                Text(
                                    text = "${employee.designation.ifBlank { employee.role }} • Fixed Salary: ₹${"%.0f".format(employee.baseSalary)}/mo",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted
                                )
                            }
                        }

                        // Running Balance Status Chip
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (runningOwed > 0) LossRed.copy(alpha = 0.12f) else ProfitGreen.copy(alpha = 0.12f),
                            contentColor = if (runningOwed > 0) LossRed else ProfitGreen
                        ) {
                            Text(
                                text = if (runningOwed > 0) "OWED ₹${"%.0f".format(runningOwed)}" else "CLEARED",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(10.dp))

                    // Calculations row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(text = "Total Dues Accrued ($duesCount):", fontSize = 11.sp, color = TextMuted)
                            Text(text = "₹${"%.2f".format(totalDues)}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextDark)
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = "Total Paid ($paymentsCount):", fontSize = 11.sp, color = TextMuted)
                            Text(text = "₹${"%.2f".format(totalPaid)}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ProfitGreen)
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(text = "Running Amount Owed:", fontSize = 11.sp, color = if (runningOwed > 0) LossRed else ProfitGreen)
                            Text(
                                text = "₹${"%.2f".format(runningOwed)}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (runningOwed > 0) LossRed else ProfitGreen
                            )
                        }
                    }

                    if (pendingAdvance > 0) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "⚠️ Staff has ₹${"%.0f".format(pendingAdvance)} outstanding advance (can be deducted during payout)",
                            fontSize = 11.sp,
                            color = LossRed,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onRecordDue(employee) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.PostAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isBengali) "বকেয়া যোগ" else "Record Due", fontSize = 11.sp)
                        }

                        Button(
                            onClick = { onPaySalary(employee) },
                            modifier = Modifier.weight(1.2f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = StoreRedPrimary),
                            contentPadding = PaddingValues(vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Payments, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isBengali) "বেতন প্রদান" else "Mark Paid", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Recent Salary Slips & History
        if (allSalaryPayments.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = if (isBengali) "সাম্প্রতিক বেতন রশিদ ও ইতিহাস" else "Recent Salary Payment Slips",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextDark
                )
            }

            items(allSalaryPayments.take(15), key = { it.id }) { payment ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${payment.employeeName} • ${payment.monthYear}",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextDark
                            )
                            val dateStr = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(payment.paymentDate))
                            Text(
                                text = "Paid: $dateStr via ${payment.paymentMode}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "₹${"%.2f".format(payment.netSalaryPaid)}",
                                fontWeight = FontWeight.Bold,
                                color = ProfitGreen,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                IconButton(
                                    onClick = {
                                        val employee = activeEmployees.find { it.id == payment.employeeId }
                                        val slip = WhatsAppHelper.generateSalarySlipText(payment, employee)
                                        WhatsAppHelper.sendWhatsAppMessage(context, employee?.phone, slip)
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = "Share", tint = ProfitGreen, modifier = Modifier.size(16.dp))
                                }

                                IconButton(
                                    onClick = {
                                        viewModel.deleteSalaryPaymentRecord(payment.id) {
                                            Toast.makeText(context, if (isBengali) "বেতন রেকর্ড বাতিল করা হয়েছে" else "Salary payment removed", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = LossRed, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// TAB 4: ADVANCES & REIMBURSEMENTS TAB CONTENT
// -------------------------------------------------------------
@Composable
fun AdvancesAndReimbursementsTabContent(
    viewModel: StoreViewModel,
    allAdvances: List<EmployeeAdvance>,
    activeEmployees: List<Employee>,
    isBengali: Boolean,
    onAddNewEntry: (String) -> Unit
) {
    val context = LocalContext.current
    var selectedFilter by remember { mutableStateOf("ALL") } // "ALL", "ADVANCE", "REIMBURSEMENT"

    val totalAdvancesGiven = allAdvances.filter { it.type == "ADVANCE" }.sumOf { it.amount }
    val totalAdvancesRepaid = allAdvances.filter { it.type == "ADVANCE" }.sumOf { it.repaidAmount }
    val outstandingAdvanceTotal = (totalAdvancesGiven - totalAdvancesRepaid).coerceAtLeast(0.0)

    val totalReimbursementsClaimed = allAdvances.filter { it.type == "REIMBURSEMENT" }.sumOf { it.amount }
    val totalReimbursementsPaid = allAdvances.filter { it.type == "REIMBURSEMENT" }.sumOf { it.repaidAmount }
    val outstandingReimbursementTotal = (totalReimbursementsClaimed - totalReimbursementsPaid).coerceAtLeast(0.0)

    val filteredList = remember(allAdvances, selectedFilter) {
        when (selectedFilter) {
            "ADVANCE" -> allAdvances.filter { it.type == "ADVANCE" }
            "REIMBURSEMENT" -> allAdvances.filter { it.type == "REIMBURSEMENT" }
            else -> allAdvances
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Summary & Action Bar
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(2.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = if (isBengali) "কর্মীদের ঋণ ও দোকান খরচের হিসাব" else "Advances & Reimbursements Ledger",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextDark
                        )
                        Text(
                            text = "Tracked separately from store's own operating expenses",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(text = "Staff Advance Loans Owed:", fontSize = 11.sp, color = LossRed)
                        Text(text = "₹${"%.2f".format(outstandingAdvanceTotal)}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = LossRed)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(text = "Shop Expense Reimb. Due:", fontSize = 11.sp, color = StorePrimary)
                        Text(text = "₹${"%.2f".format(outstandingReimbursementTotal)}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = StorePrimary)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onAddNewEntry("ADVANCE") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.AddCard, contentDescription = null, modifier = Modifier.size(16.dp), tint = StoreRedPrimary)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isBengali) "অগ্রিম ঋণ দিন" else "Give Advance", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = StoreRedPrimary)
                    }

                    Button(
                        onClick = { onAddNewEntry("REIMBURSEMENT") },
                        modifier = Modifier.weight(1.2f),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = StorePrimary),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.ReceiptLong, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isBengali) "ফেরত খরচ নথিভুক্ত" else "Record Reimburse", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Filter Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("ALL" to "All Records", "ADVANCE" to "Staff Advances", "REIMBURSEMENT" to "Reimbursements").forEach { (key, label) ->
                FilterChip(
                    selected = selectedFilter == key,
                    onClick = { selectedFilter = key },
                    label = { Text(label, fontSize = 11.sp) }
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        if (filteredList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.CheckCircleOutline, contentDescription = null, modifier = Modifier.size(56.dp), tint = ProfitGreen)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (isBengali) "কোন অগ্রিম বা খরচ বকেয়া নেই" else "No advances or reimbursement records found",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredList, key = { it.id }) { item ->
                    val isAdvance = item.type == "ADVANCE"
                    val isPending = item.status == "PENDING"
                    val remaining = item.amount - item.repaidAmount

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = if (isAdvance) LossRed.copy(alpha = 0.12f) else StorePrimary.copy(alpha = 0.12f),
                                            contentColor = if (isAdvance) LossRed else StorePrimary
                                        ) {
                                            Text(
                                                text = if (isAdvance) "STAFF ADVANCE" else "SHOP EXPENSE REIMBURSE",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = item.employeeName,
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = TextDark
                                        )
                                    }

                                    val dateStr = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(item.date))
                                    Text(
                                        text = "$dateStr • ${item.reason.ifBlank { if (isAdvance) "Salary Advance" else "Expense on shop behalf" }}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextMuted
                                    )
                                }

                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isPending) (if (isAdvance) LossRed else StorePrimary).copy(alpha = 0.12f) else ProfitGreen.copy(alpha = 0.12f),
                                    contentColor = if (isPending) (if (isAdvance) LossRed else StorePrimary) else ProfitGreen
                                ) {
                                    Text(
                                        text = if (isPending) "PENDING" else "SETTLED",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Amount: ₹${"%.2f".format(item.amount)} (Remaining: ₹${"%.2f".format(remaining)})",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isPending) (if (isAdvance) LossRed else StorePrimary) else TextMuted
                                )

                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (isPending) {
                                        TextButton(
                                            onClick = {
                                                viewModel.settleEmployeeAdvanceOrReimbursement(item) { success, msg ->
                                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                            modifier = Modifier.height(28.dp)
                                        ) {
                                            Text(if (isAdvance) "Settle" else "Mark Reimbursed", fontSize = 11.sp, color = ProfitGreen, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    IconButton(
                                        onClick = {
                                            viewModel.deleteEmployeeAdvance(item.id) {
                                                Toast.makeText(context, "Entry removed", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = LossRed, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// TAB 5: SALARY REPORT & TOTAL MONTHLY COST BREAKDOWN
// -------------------------------------------------------------
@Composable
fun SalaryReportTabContent(
    activeEmployees: List<Employee>,
    allSalaryDues: List<EmployeeSalaryDue>,
    allSalaryPayments: List<EmployeeSalaryPayment>,
    allAdvances: List<EmployeeAdvance>,
    isBengali: Boolean
) {
    val context = LocalContext.current
    val currentMonthStr = remember { SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date()) }

    val totalMonthlyCommitted = activeEmployees.sumOf { it.baseSalary }

    val totalAccruedAll = allSalaryDues.sumOf { it.dueAmount }
    val totalPaidAll = allSalaryPayments.sumOf { it.netSalaryPaid }
    val totalSalaryOwedAll = (totalAccruedAll - totalPaidAll).coerceAtLeast(0.0)

    val totalAdvancesGiven = allAdvances.filter { it.type == "ADVANCE" }.sumOf { it.amount }
    val totalAdvancesRepaid = allAdvances.filter { it.type == "ADVANCE" }.sumOf { it.repaidAmount }
    val totalOutstandingAdvances = (totalAdvancesGiven - totalAdvancesRepaid).coerceAtLeast(0.0)

    val totalReimbClaimed = allAdvances.filter { it.type == "REIMBURSEMENT" }.sumOf { it.amount }
    val totalReimbPaid = allAdvances.filter { it.type == "REIMBURSEMENT" }.sumOf { it.repaidAmount }
    val totalOutstandingReimb = (totalReimbClaimed - totalReimbPaid).coerceAtLeast(0.0)

    val netStaffPayableTotal = (totalSalaryOwedAll + totalOutstandingReimb - totalOutstandingAdvances)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            // Salary Cost Banner
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = StoreRedPrimary),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (isBengali) "মাসিক সর্বমোট বেতন বাজেট ও খরচ" else "MONTHLY TOTAL SALARY COST",
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "₹${"%.2f".format(totalMonthlyCommitted)} / month",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "Fixed base commitment for ${activeEmployees.size} active employees",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.9f)
                    )
                }
            }
        }

        // Summary Breakdown Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(2.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (isBengali) "বেতন ও বকেয়া আর্থিক সারসংক্ষেপ" else "Payroll & Outstanding Summary",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextDark
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    SalaryReportRow("Total Monthly Base Commitment", "₹%.2f".format(totalMonthlyCommitted))
                    SalaryReportRow("Total Salary Dues Accrued", "₹%.2f".format(totalAccruedAll))
                    SalaryReportRow("Total Salary Disbursed (Paid)", "-₹%.2f".format(totalPaidAll), valueColor = ProfitGreen)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    SalaryReportRow("Total Outstanding Salary Owed", "₹%.2f".format(totalSalaryOwedAll), valueColor = LossRed, isBold = true)
                    SalaryReportRow("Outstanding Staff Advances (Shop Asset)", "₹%.2f".format(totalOutstandingAdvances), valueColor = StoreGold)
                    SalaryReportRow("Outstanding Reimbursements (Shop Liability)", "₹%.2f".format(totalOutstandingReimb), valueColor = StorePrimary)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    SalaryReportRow("NET TOTAL STAFF PAYABLE", "₹%.2f".format(netStaffPayableTotal), valueColor = if (netStaffPayableTotal >= 0) LossRed else ProfitGreen, isBold = true)
                }
            }
        }

        item {
            Text(
                text = if (isBengali) "কর্মীভিত্তিক বেতন ও বকেয়ার বিবরণী" else "Employee-by-Employee Salary Status",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = TextDark
            )
        }

        items(activeEmployees, key = { it.id }) { emp ->
            val empDues = allSalaryDues.filter { it.employeeId == emp.id }.sumOf { it.dueAmount }
            val empPaid = allSalaryPayments.filter { it.employeeId == emp.id }.sumOf { it.netSalaryPaid }
            val empSalaryOwed = (empDues - empPaid).coerceAtLeast(0.0)

            val empAdv = allAdvances.filter { it.employeeId == emp.id && it.type == "ADVANCE" }.sumOf { it.amount - it.repaidAmount }.coerceAtLeast(0.0)
            val empReimb = allAdvances.filter { it.employeeId == emp.id && it.type == "REIMBURSEMENT" }.sumOf { it.amount - it.repaidAmount }.coerceAtLeast(0.0)
            val empNet = empSalaryOwed + empReimb - empAdv

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = emp.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, color = TextDark)
                            Text(text = "${emp.designation.ifBlank { emp.role }} • Monthly: ₹${"%.0f".format(emp.baseSalary)}", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                        }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (empSalaryOwed > 0) LossRed.copy(alpha = 0.12f) else ProfitGreen.copy(alpha = 0.12f),
                            contentColor = if (empSalaryOwed > 0) LossRed else ProfitGreen
                        ) {
                            Text(
                                text = if (empSalaryOwed > 0) "OWED ₹${"%.0f".format(empSalaryOwed)}" else "CLEAR",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(text = "Accrued: ₹${"%.0f".format(empDues)} | Paid: ₹${"%.0f".format(empPaid)}", fontSize = 11.sp, color = TextMuted)
                        Text(text = "Net: ₹${"%.2f".format(empNet)}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (empNet > 0) LossRed else ProfitGreen)
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(6.dp))
            Button(
                onClick = {
                    val summary = """
                        📊 KALIMATA VARIETY STORE - STAFF SALARY REPORT
                        ----------------------------------
                        Committed Monthly Salary: ₹%.2f
                        Total Accrued Dues: ₹%.2f
                        Total Salary Disbursed: ₹%.2f
                        TOTAL OUTSTANDING SALARY: ₹%.2f
                        ----------------------------------
                        Outstanding Advances: ₹%.2f
                        Outstanding Reimbursements: ₹%.2f
                        NET STAFF PAYABLE: ₹%.2f
                        ----------------------------------
                        Active Employees: ${activeEmployees.size}
                    """.trimIndent().format(
                        totalMonthlyCommitted,
                        totalAccruedAll,
                        totalPaidAll,
                        totalSalaryOwedAll,
                        totalOutstandingAdvances,
                        totalOutstandingReimb,
                        netStaffPayableTotal
                    )

                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "${StoreInfoManager.storeName} Staff Salary Report")
                        putExtra(Intent.EXTRA_TEXT, summary)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share Salary Report via"))
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = StoreRedPrimary),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Share, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isBengali) "বেতন রিপোর্ট শেয়ার করুন" else "Share Salary Report", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun SalaryReportRow(
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified,
    isBold: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
            fontSize = 12.sp
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
            color = valueColor,
            fontSize = 12.sp
        )
    }
}

// -------------------------------------------------------------
// STAT SUMMARY BADGE COMPONENT
// -------------------------------------------------------------
@Composable
fun StatSummaryBadge(
    title: String,
    value: String,
    color: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = title, fontSize = 9.sp, color = TextMuted, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = color,
                maxLines = 1
            )
        }
    }
}

// -------------------------------------------------------------
// DIALOG: ADD / EDIT EMPLOYEE
// -------------------------------------------------------------
@Composable
fun AddEditEmployeeDialog(
    employee: Employee?,
    isBengali: Boolean,
    onDismiss: () -> Unit,
    onSave: (Employee) -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(employee?.name ?: "") }
    var phone by remember { mutableStateOf(employee?.phone ?: "") }
    var designation by remember { mutableStateOf(employee?.designation ?: "Staff") }
    var role by remember { mutableStateOf(employee?.role ?: "CASHIER") }
    var salaryType by remember { mutableStateOf("MONTHLY") }
    var baseSalaryStr by remember { mutableStateOf(employee?.baseSalary?.takeIf { it > 0 }?.toString() ?: "") }
    var joiningDateMillis by remember { mutableStateOf(employee?.joiningDate ?: System.currentTimeMillis()) }
    var address by remember { mutableStateOf(employee?.address ?: "") }
    var emergencyContact by remember { mutableStateOf(employee?.emergencyContact ?: "") }
    var pin by remember { mutableStateOf(employee?.pin ?: "1234") }
    var isActive by remember { mutableStateOf(employee?.isActive ?: true) }

    // POS Permissions
    var canMakeSales by remember { mutableStateOf(employee?.canMakeSales ?: true) }
    var canViewCostPrice by remember { mutableStateOf(employee?.canViewCostPrice ?: false) }
    var canManageInventory by remember { mutableStateOf(employee?.canManageInventory ?: false) }
    var canViewKhata by remember { mutableStateOf(employee?.canViewKhata ?: false) }
    var canManageExpenses by remember { mutableStateOf(employee?.canManageExpenses ?: false) }
    var canViewReports by remember { mutableStateOf(employee?.canViewReports ?: false) }
    var canGiveDiscount by remember { mutableStateOf(employee?.canGiveDiscount ?: true) }

    val joinDateDisplay = remember(joiningDateMillis) {
        SimpleDateFormat("dd MMMM yyyy", Locale.getDefault()).format(Date(joiningDateMillis))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (employee == null) {
                    if (isBengali) "নতুন কর্মী নিবন্ধন" else "Add New Employee"
                } else {
                    if (isBengali) "কর্মী তথ্য সম্পাদনা" else "Edit Employee"
                },
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(if (isBengali) "কর্মীর নাম *" else "Employee Name *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("Phone Number") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = designation,
                        onValueChange = { designation = it },
                        label = { Text("Role / Position") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Monthly Salary Configuration
                Text(
                    text = if (isBengali) "মাসিক নির্দিষ্ট বেতন" else "Monthly Salary (Fixed)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = StoreRedPrimary
                )

                OutlinedTextField(
                    value = baseSalaryStr,
                    onValueChange = { baseSalaryStr = it },
                    label = { Text("Monthly Salary (₹) *") },
                    placeholder = { Text("e.g. 15000") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Joining Date Picker
                Text(
                    text = if (isBengali) "যোগদানের তারিখ" else "Joining Date",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextDark
                )

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val cal = Calendar.getInstance().apply { timeInMillis = joiningDateMillis }
                            DatePickerDialog(
                                context,
                                { _, year, month, dayOfMonth ->
                                    val newCal = Calendar.getInstance().apply {
                                        set(year, month, dayOfMonth)
                                    }
                                    joiningDateMillis = newCal.timeInMillis
                                },
                                cal.get(Calendar.YEAR),
                                cal.get(Calendar.MONTH),
                                cal.get(Calendar.DAY_OF_MONTH)
                            ).show()
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CalendarToday, contentDescription = null, tint = StoreRedPrimary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = joinDateDisplay, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        Text(text = "Change", color = StorePrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Address / Locality") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = emergencyContact,
                    onValueChange = { emergencyContact = it },
                    label = { Text("Emergency Contact Phone") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 6) pin = it },
                    label = { Text("Staff 4-Digit Login PIN") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Permissions Section
                Text(
                    text = if (isBengali) "অনুমতিসমূহ (POS Permissions)" else "POS Permissions",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = StoreRedPrimary
                )

                PermissionSwitchItem(label = "Can Make Sales / Billing", checked = canMakeSales, onCheckedChange = { canMakeSales = it })
                PermissionSwitchItem(label = "Can Give Discounts", checked = canGiveDiscount, onCheckedChange = { canGiveDiscount = it })
                PermissionSwitchItem(label = "Can View Purchase/Cost Prices", checked = canViewCostPrice, onCheckedChange = { canViewCostPrice = it })
                PermissionSwitchItem(label = "Can Manage Stock / Inventory", checked = canManageInventory, onCheckedChange = { canManageInventory = it })
                PermissionSwitchItem(label = "Can View Customer Khata Due", checked = canViewKhata, onCheckedChange = { canViewKhata = it })
                PermissionSwitchItem(label = "Can Record Store Expenses", checked = canManageExpenses, onCheckedChange = { canManageExpenses = it })
                PermissionSwitchItem(label = "Can View Profit & Loss Reports", checked = canViewReports, onCheckedChange = { canViewReports = it })

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Active Status", fontWeight = FontWeight.Bold)
                    Switch(checked = isActive, onCheckedChange = { isActive = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isBlank()) return@Button
                    val baseSalary = baseSalaryStr.toDoubleOrNull() ?: 0.0
                    val updated = (employee ?: Employee(name = name.trim())).copy(
                        name = name.trim(),
                        phone = phone.trim(),
                        designation = designation.trim(),
                        role = role,
                        salaryType = salaryType,
                        baseSalary = baseSalary,
                        joiningDate = joiningDateMillis,
                        address = address.trim(),
                        emergencyContact = emergencyContact.trim(),
                        pin = pin.trim().ifBlank { "1234" },
                        isActive = isActive,
                        canMakeSales = canMakeSales,
                        canViewCostPrice = canViewCostPrice,
                        canManageInventory = canManageInventory,
                        canViewKhata = canViewKhata,
                        canManageExpenses = canManageExpenses,
                        canViewReports = canViewReports,
                        canGiveDiscount = canGiveDiscount
                    )
                    onSave(updated)
                },
                colors = ButtonDefaults.buttonColors(containerColor = StoreRedPrimary),
                enabled = name.isNotBlank()
            ) {
                Text(if (isBengali) "সংরক্ষণ করুন" else "Save Employee", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun PermissionSwitchItem(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 12.sp, color = TextDark)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.height(24.dp)
        )
    }
}

// -------------------------------------------------------------
// DIALOG: RECORD SALARY DUE
// -------------------------------------------------------------
@Composable
fun RecordSalaryDueDialog(
    targetEmployee: Employee?,
    activeEmployees: List<Employee>,
    viewModel: StoreViewModel,
    isBengali: Boolean,
    onDismiss: () -> Unit,
    onSave: (empId: String, empName: String, monthYear: String, amount: Double, note: String) -> Unit
) {
    val currentMonthStr = remember { SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date()) }
    var selectedEmployeeId by remember { mutableStateOf(targetEmployee?.id ?: activeEmployees.firstOrNull()?.id ?: "") }
    var monthYear by remember { mutableStateOf(currentMonthStr) }

    val selectedEmp = activeEmployees.find { it.id == selectedEmployeeId }
    var breakdown by remember { mutableStateOf<SalaryBreakdown?>(null) }
    var isLoadingBreakdown by remember { mutableStateOf(false) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    var dueAmountStr by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    LaunchedEffect(selectedEmployeeId, monthYear, refreshTrigger) {
        if (selectedEmployeeId.isNotBlank()) {
            isLoadingBreakdown = true
            val b = viewModel.getSalaryBreakdownForEmployee(selectedEmployeeId, monthYear)
            breakdown = b
            if (b != null) {
                dueAmountStr = "%.2f".format(b.totalCalculatedDue)
                notes = "Calculated from attendance: ${b.presentDays + b.paidLeaveDays}P, ${b.halfDays}H, ${b.absentDays}A @ ₹${"%.2f".format(b.perDayRate)}/d"
            }
            isLoadingBreakdown = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = if (isBengali) "হাজিরা-ভিত্তিক বেতন বকেয়া নথিভুক্ত" else "Attendance-Based Salary Due",
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isBengali) "দৈনিক উপস্থিতির হিসাব অনুযায়ী" else "Accrue due based on daily attendance rate",
                    style = MaterialTheme.typography.bodySmall,
                    color = StoreRedPrimary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (targetEmployee == null && activeEmployees.isNotEmpty()) {
                    Text("Select Employee", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextMuted)
                    LazyColumn(modifier = Modifier.heightIn(max = 110.dp)) {
                        items(activeEmployees, key = { it.id }, contentType = { "EMPLOYEE_SELECTION_ITEM" }) { emp ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (selectedEmployeeId == emp.id) StoreRedPrimary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                border = if (selectedEmployeeId == emp.id) androidx.compose.foundation.BorderStroke(1.5.dp, StoreRedPrimary) else null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .clickable { selectedEmployeeId = emp.id }
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = emp.name, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                    Text(text = "Salary: ₹${"%.0f".format(emp.baseSalary)}", fontSize = 11.sp, color = TextMuted)
                                }
                            }
                        }
                    }
                } else if (targetEmployee != null) {
                    Text(
                        text = "Employee: ${targetEmployee.name} (${targetEmployee.designation.ifBlank { targetEmployee.role }})",
                        fontWeight = FontWeight.Bold,
                        color = StoreRedPrimary
                    )
                }

                OutlinedTextField(
                    value = monthYear,
                    onValueChange = { monthYear = it },
                    label = { Text("Salary Month (e.g. August 2026)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Breakdown summary card if available
                if (breakdown != null) {
                    val b = breakdown!!
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Rate: ₹${"%.0f".format(b.baseMonthlySalary)} ÷ ${b.totalCalendarDays}d", fontSize = 11.sp, color = TextDark)
                                Text("₹${"%.2f".format(b.perDayRate)}/day", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = StoreRedPrimary)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Present: ${b.presentDays + b.paidLeaveDays}d", fontSize = 11.sp, color = ProfitGreen, fontWeight = FontWeight.Bold)
                                Text("Half: ${b.halfDays}d", fontSize = 11.sp, color = TextDark, fontWeight = FontWeight.Bold)
                                Text("Absent: ${b.absentDays}d", fontSize = 11.sp, color = LossRed, fontWeight = FontWeight.Bold)
                            }

                            // Unmarked days alert
                            if (b.unmarkedDates.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "⚠️ ${b.unmarkedDates.size} days unmarked! Quick mark:",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = LossRed
                                )
                                b.unmarkedDates.take(3).forEach { dStr ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(dStr, fontSize = 10.sp)
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Surface(
                                                color = ProfitGreen,
                                                shape = RoundedCornerShape(4.dp),
                                                modifier = Modifier.clickable {
                                                    viewModel.markAttendanceForDate(b.employeeId, b.employeeName, dStr, "PRESENT") {
                                                        refreshTrigger++
                                                    }
                                                }
                                            ) {
                                                Text("Present", color = Color.White, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                                            }
                                            Surface(
                                                color = LossRed,
                                                shape = RoundedCornerShape(4.dp),
                                                modifier = Modifier.clickable {
                                                    viewModel.markAttendanceForDate(b.employeeId, b.employeeName, dStr, "ABSENT") {
                                                        refreshTrigger++
                                                    }
                                                }
                                            ) {
                                                Text("Absent", color = Color.White, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = dueAmountStr,
                    onValueChange = { dueAmountStr = it },
                    label = { Text("Due Amount (₹) *") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes / Description") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amount = dueAmountStr.toDoubleOrNull() ?: 0.0
                    if (amount <= 0 || selectedEmp == null) return@Button
                    onSave(selectedEmp.id, selectedEmp.name, monthYear.trim(), amount, notes.trim())
                },
                colors = ButtonDefaults.buttonColors(containerColor = StoreRedPrimary),
                enabled = dueAmountStr.toDoubleOrNull() != null && dueAmountStr.toDouble() > 0 && selectedEmp != null
            ) {
                Text("Record Due Entry", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// -------------------------------------------------------------
// DIALOG: PAY SALARY MODAL & PAYSLIP CALCULATOR
// -------------------------------------------------------------
@Composable
fun PaySalaryModalDialog(
    employee: Employee,
    viewModel: StoreViewModel,
    allSalaryDues: List<EmployeeSalaryDue>,
    allSalaryPayments: List<EmployeeSalaryPayment>,
    allAdvances: List<EmployeeAdvance>,
    isBengali: Boolean,
    onDismiss: () -> Unit,
    onPaymentRecorded: (EmployeeSalaryPayment) -> Unit
) {
    val currentMonthStr = remember { SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date()) }
    var monthYear by remember { mutableStateOf(currentMonthStr) }

    // Running salary owed calculation strictly from history
    val totalDues = remember(allSalaryDues, employee.id) {
        allSalaryDues.filter { it.employeeId == employee.id }.sumOf { it.dueAmount }
    }
    val totalPaid = remember(allSalaryPayments, employee.id) {
        allSalaryPayments.filter { it.employeeId == employee.id }.sumOf { it.netSalaryPaid }
    }
    val runningSalaryOwed = remember(totalDues, totalPaid) {
        (totalDues - totalPaid).coerceAtLeast(0.0)
    }

    val activeAdvanceTotal = remember(allAdvances, employee.id) {
        allAdvances.filter { it.employeeId == employee.id && it.type == "ADVANCE" && it.status == "PENDING" }
            .sumOf { it.amount - it.repaidAmount }
            .coerceAtLeast(0.0)
    }

    var baseSalaryStr by remember {
        mutableStateOf(if (runningSalaryOwed > 0) runningSalaryOwed.toString() else employee.baseSalary.toString())
    }
    var bonusStr by remember { mutableStateOf("0") }
    var advanceDeductionStr by remember { mutableStateOf("0") }
    var otherDeductionStr by remember { mutableStateOf("0") }
    var paymentMode by remember { mutableStateOf("CASH") }
    var notes by remember { mutableStateOf("") }
    var autoRecordExpense by remember { mutableStateOf(true) }

    val baseSalary = baseSalaryStr.toDoubleOrNull() ?: 0.0
    val bonus = bonusStr.toDoubleOrNull() ?: 0.0
    val advanceDeduction = advanceDeductionStr.toDoubleOrNull() ?: 0.0
    val otherDeduction = otherDeductionStr.toDoubleOrNull() ?: 0.0

    val netSalary = (baseSalary + bonus - advanceDeduction - otherDeduction).coerceAtLeast(0.0)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = if (isBengali) "বেতন প্রদান ও রশিদ" else "Pay Salary & Record Payment",
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${employee.name} (${employee.designation}) • Fixed Salary: ₹${"%.0f".format(employee.baseSalary)}/mo",
                    style = MaterialTheme.typography.bodySmall,
                    color = StoreRedPrimary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Running Owed Reminder Banner
                Surface(
                    color = if (runningSalaryOwed > 0) LossRed.copy(alpha = 0.08f) else ProfitGreen.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Running Salary Owed:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextDark)
                        Text(
                            text = "₹${"%.2f".format(runningSalaryOwed)}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (runningSalaryOwed > 0) LossRed else ProfitGreen
                        )
                    }
                }

                OutlinedTextField(
                    value = monthYear,
                    onValueChange = { monthYear = it },
                    label = { Text("Salary Month / Period") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = baseSalaryStr,
                    onValueChange = { baseSalaryStr = it },
                    label = { Text("Salary Amount to Pay (₹) *") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = bonusStr,
                        onValueChange = { bonusStr = it },
                        label = { Text("Bonus (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = advanceDeductionStr,
                        onValueChange = { advanceDeductionStr = it },
                        label = { Text("Deduct Advance (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (activeAdvanceTotal > 0) {
                    Text(
                        text = "💡 Staff has ₹${"%.0f".format(activeAdvanceTotal)} active advance loans.",
                        fontSize = 11.sp,
                        color = LossRed,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedTextField(
                    value = otherDeductionStr,
                    onValueChange = { otherDeductionStr = it },
                    label = { Text("Other Deductions (₹)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Payment Mode
                Text("Payment Mode", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextMuted)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("CASH", "UPI", "BANK_TRANSFER").forEach { mode ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (paymentMode == mode) StorePrimary else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (paymentMode == mode) Color.White else TextDark,
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clickable { paymentMode = mode }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = mode.replace("_", " "),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes / Remarks") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Calculated Net Payout Banner
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = ProfitGreen.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "Gross Salary + Bonus:", fontSize = 12.sp, color = TextDark)
                            Text(text = "₹${"%.2f".format(baseSalary + bonus)}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "Deductions:", fontSize = 12.sp, color = LossRed)
                            Text(text = "-₹${"%.2f".format(advanceDeduction + otherDeduction)}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LossRed)
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "NET DISBURSED:", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ProfitGreen)
                            Text(text = "₹${"%.2f".format(netSalary)}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = ProfitGreen)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Record in Store Expenses (for P&L)", fontSize = 11.sp, color = TextDark)
                    Switch(checked = autoRecordExpense, onCheckedChange = { autoRecordExpense = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val payment = EmployeeSalaryPayment(
                        id = UUID.randomUUID().toString(),
                        employeeId = employee.id,
                        employeeName = employee.name,
                        monthYear = monthYear.trim().ifBlank { currentMonthStr },
                        paymentDate = System.currentTimeMillis(),
                        baseSalary = baseSalary,
                        salaryType = employee.salaryType,
                        presentDays = 30,
                        bonus = bonus,
                        advanceDeduction = advanceDeduction,
                        otherDeductions = otherDeduction,
                        netSalaryPaid = netSalary,
                        paymentMode = paymentMode,
                        notes = notes.trim(),
                        syncedToExpense = autoRecordExpense
                    )
                    onPaymentRecorded(payment)
                },
                colors = ButtonDefaults.buttonColors(containerColor = StoreRedPrimary),
                enabled = netSalary > 0
            ) {
                Text("Confirm & Pay ₹${"%.0f".format(netSalary)}", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// -------------------------------------------------------------
// DIALOG: GIVE ADVANCE OR RECORD REIMBURSEMENT
// -------------------------------------------------------------
@Composable
fun GiveAdvanceOrReimbursementModalDialog(
    targetEmployee: Employee?,
    activeEmployees: List<Employee>,
    initialType: String = "ADVANCE",
    isBengali: Boolean,
    onDismiss: () -> Unit,
    onSave: (empId: String, empName: String, type: String, amount: Double, reason: String, mode: String) -> Unit
) {
    var entryType by remember { mutableStateOf(initialType) } // "ADVANCE" vs "REIMBURSEMENT"
    var selectedEmployeeId by remember { mutableStateOf(targetEmployee?.id ?: activeEmployees.firstOrNull()?.id ?: "") }
    var amountStr by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var paymentMode by remember { mutableStateOf("CASH") }

    val selectedEmp = activeEmployees.find { it.id == selectedEmployeeId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (entryType == "ADVANCE") {
                    if (isBengali) "কর্মীকে অগ্রিম ঋণ প্রদান" else "Give Staff Salary Advance"
                } else {
                    if (isBengali) "দোকানের খরচ ফেরত (রিইমবার্স)" else "Staff Shop Expense Reimbursement"
                },
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Type Selector Switch
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (entryType == "ADVANCE") StoreRedPrimary else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (entryType == "ADVANCE") Color.White else TextDark,
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clickable { entryType = "ADVANCE" }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("Advance Loan (Debt)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (entryType == "REIMBURSEMENT") StorePrimary else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (entryType == "REIMBURSEMENT") Color.White else TextDark,
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clickable { entryType = "REIMBURSEMENT" }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("Reimbursement (Claim)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (targetEmployee == null && activeEmployees.isNotEmpty()) {
                    Text("Select Employee", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextMuted)
                    LazyColumn(modifier = Modifier.heightIn(max = 120.dp)) {
                        items(activeEmployees, key = { it.id }, contentType = { "EMPLOYEE_SELECTION_ITEM" }) { emp ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (selectedEmployeeId == emp.id) StoreRedPrimary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                border = if (selectedEmployeeId == emp.id) androidx.compose.foundation.BorderStroke(1.5.dp, StoreRedPrimary) else null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .clickable { selectedEmployeeId = emp.id }
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = emp.name, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                    Text(text = "Salary: ₹${"%.0f".format(emp.baseSalary)}", fontSize = 11.sp, color = TextMuted)
                                }
                            }
                        }
                    }
                } else if (targetEmployee != null) {
                    Text(
                        text = "Employee: ${targetEmployee.name} (${targetEmployee.designation})",
                        fontWeight = FontWeight.Bold,
                        color = StoreRedPrimary
                    )
                }

                OutlinedTextField(
                    value = amountStr,
                    onValueChange = { amountStr = it },
                    label = { Text("Amount (₹) *") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = {
                        Text(if (entryType == "ADVANCE") "Reason (e.g. Festival advance, Medical)" else "Expense details (e.g. Shop stationery, Delivery fare)")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Payment Mode", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextMuted)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("CASH", "UPI", "BANK").forEach { mode ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (paymentMode == mode) StorePrimary else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (paymentMode == mode) Color.White else TextDark,
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clickable { paymentMode = mode }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(text = mode, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amount = amountStr.toDoubleOrNull() ?: 0.0
                    if (amount <= 0 || selectedEmp == null) return@Button
                    onSave(selectedEmp.id, selectedEmp.name, entryType, amount, reason.trim(), paymentMode)
                },
                colors = ButtonDefaults.buttonColors(containerColor = if (entryType == "ADVANCE") StoreRedPrimary else StorePrimary),
                enabled = amountStr.toDoubleOrNull() != null && amountStr.toDouble() > 0 && selectedEmp != null
            ) {
                Text(if (entryType == "ADVANCE") "Disburse Advance" else "Record Claim", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// -------------------------------------------------------------
// DIALOG: MONTHLY ATTENDANCE SCORECARD / CALENDAR
// -------------------------------------------------------------
@Composable
fun EmployeeMonthlyAttendanceDialog(
    employee: Employee,
    viewModel: StoreViewModel,
    isBengali: Boolean,
    onDismiss: () -> Unit
) {
    val monthName = remember { SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date()) }
    val joinStr = remember(employee.joiningDate) {
        SimpleDateFormat("dd MMMM yyyy", Locale.getDefault()).format(Date(employee.joiningDate))
    }
    var breakdown by remember { mutableStateOf<SalaryBreakdown?>(null) }
    var isLoadingBreakdown by remember { mutableStateOf(true) }

    LaunchedEffect(employee.id, monthName) {
        isLoadingBreakdown = true
        breakdown = viewModel.getSalaryBreakdownForEmployee(employee.id, monthName)
        isLoadingBreakdown = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(text = "${employee.name} - $monthName", fontWeight = FontWeight.Bold)
                Text(
                    text = "${employee.designation.ifBlank { employee.role }} • Base: ₹${"%.0f".format(employee.baseSalary)}/mo",
                    style = MaterialTheme.typography.bodySmall,
                    color = StoreRedPrimary
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = if (isBengali) "কর্মীর প্রোফাইল" else "Staff Profile",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Status:", fontSize = 12.sp, color = TextMuted)
                            Text(if (employee.isActive) "Active Employee" else "Inactive", fontWeight = FontWeight.Bold, color = if (employee.isActive) ProfitGreen else LossRed)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Base Salary:", fontSize = 12.sp, color = TextMuted)
                            Text("₹${"%.0f".format(employee.baseSalary)} / month", fontWeight = FontWeight.Bold)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Joining Date:", fontSize = 12.sp, color = TextMuted)
                            Text(joinStr, fontWeight = FontWeight.Bold)
                        }
                        if (employee.phone.isNotBlank()) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Phone:", fontSize = 12.sp, color = TextMuted)
                                Text(employee.phone, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Attendance-based Salary Breakdown Card
                if (breakdown != null) {
                    val b = breakdown!!
                    Surface(
                        color = StoreRedPrimary.copy(alpha = 0.06f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = if (isBengali) "হাজিরা-ভিত্তিক বর্তমান মাসের হিসাব" else "Daily Attendance Calculation ($monthName)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = StoreRedPrimary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Per-Day Rate (${b.totalCalendarDays} days):", fontSize = 11.sp, color = TextDark)
                                Text("₹${"%.2f".format(b.perDayRate)} / day", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = StoreRedPrimary)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Present: ${b.presentDays + b.paidLeaveDays}d", fontSize = 11.sp, color = ProfitGreen, fontWeight = FontWeight.Bold)
                                Text("Half-Day: ${b.halfDays}d", fontSize = 11.sp, color = TextDark, fontWeight = FontWeight.Bold)
                                Text("Absent: ${b.absentDays}d", fontSize = 11.sp, color = LossRed, fontWeight = FontWeight.Bold)
                            }
                            if (b.unmarkedDates.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "⚠️ ${b.unmarkedDates.size} days not marked yet",
                                    fontSize = 11.sp,
                                    color = LossRed,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Divider(color = StoreRedPrimary.copy(alpha = 0.2f))
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    text = if (isBengali) "অর্জিত বেতন (চলতি):" else "Calculated Salary Earned:",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextDark
                                )
                                Text(
                                    text = "₹${"%.2f".format(b.totalCalculatedDue)}",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = StoreRedPrimary
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", fontWeight = FontWeight.Bold)
            }
        }
    )
}
