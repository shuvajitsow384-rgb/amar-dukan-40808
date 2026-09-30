package com.example.ui.components

import android.app.DatePickerDialog
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.local.entities.Employee
import com.example.ui.theme.*
import com.example.utils.LanguageManager
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedAddEditEmployeeDialog(
    initialEmployee: Employee? = null,
    initialEmail: String = "",
    existingEmployees: List<Employee> = emptyList(),
    isOwnerOrAdmin: Boolean = true,
    onDismiss: () -> Unit,
    onSave: (Employee) -> Unit
) {
    val context = LocalContext.current
    val isBengali = LanguageManager.isBengali

    var name by remember { mutableStateOf(initialEmployee?.name ?: "") }
    var email by remember { mutableStateOf(initialEmployee?.email?.ifBlank { initialEmail } ?: initialEmail) }
    var phone by remember { mutableStateOf(initialEmployee?.phone ?: "") }
    var role by remember { mutableStateOf(initialEmployee?.role ?: "CASHIER") }
    var baseSalaryStr by remember {
        mutableStateOf(
            if ((initialEmployee?.baseSalary ?: 0.0) > 0) "%.0f".format(initialEmployee!!.baseSalary) else ""
        )
    }
    var joiningDateMillis by remember { mutableStateOf(initialEmployee?.joiningDate ?: System.currentTimeMillis()) }
    var address by remember { mutableStateOf(initialEmployee?.address ?: "") }
    var emergencyContact by remember { mutableStateOf(initialEmployee?.emergencyContact ?: "") }
    var isActive by remember { mutableStateOf(initialEmployee?.isActive ?: true) }

    // Unified 6 Permissions
    var canMakeSales by remember { mutableStateOf(initialEmployee?.canMakeSales ?: true) }
    var canGiveDiscount by remember { mutableStateOf(initialEmployee?.canGiveDiscount ?: true) }
    var canViewCostPrice by remember { mutableStateOf(initialEmployee?.canViewCostPrice ?: false) }
    var canViewReports by remember { mutableStateOf(initialEmployee?.canViewReports ?: false) }
    var canManageInventory by remember { mutableStateOf(initialEmployee?.canManageInventory ?: false) }
    var canViewKhata by remember { mutableStateOf(initialEmployee?.canViewKhata ?: false) }

    // Secondary defaults based on role
    var canManageExpenses by remember { mutableStateOf(initialEmployee?.canManageExpenses ?: (role == "STORE_MANAGER")) }
    var canAccessSettings by remember { mutableStateOf(initialEmployee?.canAccessSettings ?: false) }
    var canDeleteSales by remember { mutableStateOf(initialEmployee?.canDeleteSales ?: (role == "STORE_MANAGER")) }

    fun applyRolePreset(newRole: String) {
        role = newRole
        when (newRole) {
            "CASHIER" -> {
                canMakeSales = true
                canGiveDiscount = true
                canViewCostPrice = false
                canViewReports = false
                canManageInventory = false
                canViewKhata = false
                canManageExpenses = false
                canAccessSettings = false
                canDeleteSales = false
            }
            "SALES_STAFF" -> {
                canMakeSales = true
                canGiveDiscount = true
                canViewCostPrice = false
                canViewReports = false
                canManageInventory = false
                canViewKhata = true
                canManageExpenses = false
                canAccessSettings = false
                canDeleteSales = false
            }
            "STORE_MANAGER" -> {
                canMakeSales = true
                canGiveDiscount = true
                canViewCostPrice = true
                canViewReports = true
                canManageInventory = true
                canViewKhata = true
                canManageExpenses = true
                canAccessSettings = false
                canDeleteSales = true
            }
        }
    }

    val joinDateDisplay = remember(joiningDateMillis) {
        SimpleDateFormat("dd MMMM yyyy", Locale.getDefault()).format(Date(joiningDateMillis))
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.94f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = StoreRedPrimary.copy(alpha = 0.12f),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (initialEmployee == null) Icons.Default.PersonAddAlt else Icons.Default.Edit,
                                    contentDescription = null,
                                    tint = StoreRedPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (initialEmployee == null) {
                                    if (isBengali) "নতুন কর্মী নিবন্ধন" else "Add Employee"
                                } else {
                                    if (isBengali) "কর্মী তথ্য সম্পাদনা" else "Edit Employee"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextDark
                            )
                            Text(
                                text = if (isBengali) "গুগল সাইন-ইন ও অনুমতি সংযোগ" else "Google Sign-In Access & Permissions",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // RBAC Notice if Non-Owner/Admin is viewing
                    if (!isOwnerOrAdmin) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFFFEF3C7),
                            border = BorderStroke(1.dp, Color(0xFFF59E0B)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("🔒 ", fontSize = 14.sp)
                                Text(
                                    text = if (initialEmployee == null) {
                                        if (isBengali) "শুধুমাত্র দোকান মালিক বা অ্যাডমিন নতুন কর্মী অ্যাকাউন্ট তৈরি করতে পারবেন।"
                                        else "Only Store Owner or Admin can create employee accounts."
                                    } else {
                                        if (isBengali) "শুধুমাত্র দোকান মালিক বা অ্যাডমিন কর্মচারীর বেতন নির্ধারণ বা পরিবর্তন করতে পারবেন।"
                                        else "Only Store Owner or Admin can set or change employee salaries."
                                    },
                                    fontSize = 11.sp,
                                    color = Color(0xFF92400E),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    // Google Sign-In Email Field
                    val matchingExistingEmployee = remember(email, existingEmployees) {
                        val clean = email.trim()
                        if (clean.isNotBlank() && initialEmployee == null) {
                            existingEmployees.firstOrNull { it.email.isNotBlank() && it.email.trim().equals(clean, ignoreCase = true) }
                        } else null
                    }

                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text(if (isBengali) "কর্মীর গুগল ইমেল আইডি (Google Sign-In)" else "Employee Email ID (Google Sign-In)") },
                        placeholder = { Text("e.g. employee.store@gmail.com") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        leadingIcon = { Icon(Icons.Default.Email, contentDescription = null, tint = StorePrimary) },
                        supportingText = {
                            if (matchingExistingEmployee != null) {
                                Text(
                                    text = "ℹ️ Existing record found for '${matchingExistingEmployee.name}'. Saving will update this employee's details.",
                                    fontSize = 10.5.sp,
                                    color = Color(0xFFD97706),
                                    fontWeight = FontWeight.SemiBold
                                )
                            } else {
                                Text(
                                    text = if (isBengali)
                                        "☁️ কর্মী নিজের ফোনে এই জিমেইল দিয়ে সাইন-ইন করলেই নির্ধারিত ভূমিকায় স্বয়ংক্রিয় প্রবেশাধিকার পাবে।"
                                    else
                                        "☁️ When employee signs into the app on their phone with this Google account, they automatically receive their assigned role & permissions.",
                                    fontSize = 10.5.sp,
                                    color = StorePrimary
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Employee Name & Phone
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text(if (isBengali) "কর্মীর নাম *" else "Employee Name *") },
                            placeholder = { Text("e.g. Rahul Kumar") },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                            modifier = Modifier.weight(1.2f)
                        )

                        OutlinedTextField(
                            value = phone,
                            onValueChange = { phone = it },
                            label = { Text(if (isBengali) "ফোন নম্বর" else "Phone Number") },
                            placeholder = { Text("9876543210") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Role Presets
                    Text(
                        text = if (isBengali) "কর্মীর ভূমিকা (ROLE)" else "EMPLOYEE ROLE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextMuted,
                        letterSpacing = 1.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            "CASHIER" to (if (isBengali) "ক্যাশিয়ার" else "Cashier"),
                            "SALES_STAFF" to (if (isBengali) "সেলস স্টাফ" else "Sales"),
                            "STORE_MANAGER" to (if (isBengali) "ম্যানেজার" else "Manager")
                        ).forEach { (roleKey, label) ->
                            val isSelected = role == roleKey
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) StoreRedPrimary else BackgroundLight,
                                border = BorderStroke(1.dp, if (isSelected) StoreRedPrimary else Color(0xFFCBD5E1)),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { applyRolePreset(roleKey) }
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) Color.White else TextDark
                                    )
                                }
                            }
                        }
                    }

                    // Salary & Joining Date
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = baseSalaryStr,
                            onValueChange = { if (isOwnerOrAdmin) baseSalaryStr = it },
                            enabled = isOwnerOrAdmin,
                            label = { Text(if (isBengali) "মাসিক বেতন (₹)" else "Monthly Salary (₹)") },
                            placeholder = { Text("15000") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            leadingIcon = {
                                Icon(
                                    if (isOwnerOrAdmin) Icons.Default.Payments else Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = if (isOwnerOrAdmin) StoreGreenProfit else TextMuted
                                )
                            },
                            supportingText = if (!isOwnerOrAdmin) {
                                {
                                    Text(
                                        text = if (isBengali) "🔒 শুধুমাত্র মালিক বা অ্যাডমিন বেতন নির্ধারণ করতে পারেন" else "🔒 Only Owner or Admin can set/change salary",
                                        fontSize = 10.sp,
                                        color = TextMuted
                                    )
                                }
                            } else null,
                            modifier = Modifier.weight(1f)
                        )

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isBengali) "যোগদানের তারিখ" else "Joining Date",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = BackgroundLight,
                                border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
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
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.CalendarToday, contentDescription = null, tint = StoreRedPrimary, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(text = joinDateDisplay, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextDark)
                                    }
                                }
                            }
                        }
                    }

                    // Address & Emergency Contact
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = address,
                            onValueChange = { address = it },
                            label = { Text(if (isBengali) "ঠিকানা" else "Address / City") },
                            placeholder = { Text("Kolkata") },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Home, contentDescription = null) },
                            modifier = Modifier.weight(1f)
                        )

                        OutlinedTextField(
                            value = emergencyContact,
                            onValueChange = { emergencyContact = it },
                            label = { Text(if (isBengali) "জরুরী ফোন" else "Emergency Phone") },
                            placeholder = { Text("9876543211") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            leadingIcon = { Icon(Icons.Default.ContactPhone, contentDescription = null) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Unified 6 Permissions Section
                    Text(
                        text = if (isBengali) "অনুমতি তালিকা (PERMISSIONS)" else "PERMISSIONS & ACCESS CONTROL",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextMuted,
                        letterSpacing = 1.sp
                    )

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = BackgroundLight),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            UnifiedPermissionSwitchRow(
                                title = if (isBengali) "বিক্রি ও বিলিং তৈরি (Can Make Sales/Billing)" else "Can Make Sales / Billing",
                                subtitle = if (isBengali) "কাউন্টার বিল তৈরি এবং রসিদ প্রিন্ট করতে পারে" else "Can scan barcodes, create bills, and print receipts",
                                checked = canMakeSales,
                                onCheckedChange = { canMakeSales = it }
                            )

                            HorizontalDivider(color = Color(0xFFE2E8F0))

                            UnifiedPermissionSwitchRow(
                                title = if (isBengali) "ডিসকাউন্ট প্রদান (Can Give Discounts)" else "Can Give Discounts",
                                subtitle = if (isBengali) "বিলিংয়ের সময় ম্যানুয়াল ছাড় প্রয়োগ করতে পারে" else "Can apply cash or percentage discounts during billing",
                                checked = canGiveDiscount,
                                onCheckedChange = { canGiveDiscount = it }
                            )

                            HorizontalDivider(color = Color(0xFFE2E8F0))

                            UnifiedPermissionSwitchRow(
                                title = if (isBengali) "কেনা দাম / ক্রয়মূল্য দর্শন (Can View Cost/Purchase Prices)" else "Can View Cost / Purchase Prices",
                                subtitle = if (isBengali) "পণ্য কেনার আসল দাম ও মার্জিন দেখতে পারে" else "Can see wholesale purchase costs & profit margins",
                                checked = canViewCostPrice,
                                onCheckedChange = { canViewCostPrice = it }
                            )

                            HorizontalDivider(color = Color(0xFFE2E8F0))

                            UnifiedPermissionSwitchRow(
                                title = if (isBengali) "লাভ-ক্ষতি ও P&L রিপোর্ট (Can View P&L/Profit Reports)" else "Can View P&L / Profit Reports",
                                subtitle = if (isBengali) "দোকানের মোট লাভ, নেট রেভিনিউ এবং আর্থিক রিপোর্ট দেখতে পারে" else "Can view confidential store revenue, net margin, and profit reports",
                                checked = canViewReports,
                                onCheckedChange = { canViewReports = it }
                            )

                            HorizontalDivider(color = Color(0xFFE2E8F0))

                            UnifiedPermissionSwitchRow(
                                title = if (isBengali) "ইনভেন্টরি ও স্টক পরিচালনা (Can Manage Inventory Stock)" else "Can Manage Inventory Stock",
                                subtitle = if (isBengali) "নতুন পণ্য যোগ, স্টক পরিবর্তন এবং দাম আপডেট করতে পারে" else "Can add/edit items, adjust stock counts, and edit prices",
                                checked = canManageInventory,
                                onCheckedChange = { canManageInventory = it }
                            )

                            HorizontalDivider(color = Color(0xFFE2E8F0))

                            UnifiedPermissionSwitchRow(
                                title = if (isBengali) "কাস্টমার খাতা পরিচালনা (Can View/Manage Customer Khata)" else "Can View / Manage Customer Khata",
                                subtitle = if (isBengali) "কাস্টমার বাকি খাতা দেখা ও বকেয়া জমা নেওয়ার অনুমতি" else "Can view customer credit ledgers and record due payments",
                                checked = canViewKhata,
                                onCheckedChange = { canViewKhata = it }
                            )
                        }
                    }

                    // Active Status Switch
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = if (isBengali) "কর্মীর সক্রিয় স্ট্যাটাস" else "Active Staff Status",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextDark
                            )
                            Text(
                                text = if (isActive)
                                    (if (isBengali) "বর্তমানে সক্রিয় ও কাউন্টারে কাজ করতে পারে" else "Active and permitted to access the store")
                                else
                                    (if (isBengali) "নিষ্ক্রিয় (লগইন বা বিলিং বন্ধ)" else "Deactivated (login & billing blocked)"),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        }

                        Switch(
                            checked = isActive,
                            onCheckedChange = { isActive = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = StoreGreenProfit
                            ),
                            modifier = Modifier.scale(0.85f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Bottom Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(if (isBengali) "বাতিল" else "Cancel")
                    }

                    Button(
                        onClick = {
                            if (name.isBlank()) {
                                Toast.makeText(context, "Please enter employee name", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            val salary = if (isOwnerOrAdmin) {
                                baseSalaryStr.toDoubleOrNull() ?: 0.0
                            } else {
                                initialEmployee?.baseSalary ?: 0.0
                            }
                            val baseTarget = initialEmployee 
                                ?: (if (email.isNotBlank()) existingEmployees.firstOrNull { it.email.isNotBlank() && it.email.trim().equals(email.trim(), ignoreCase = true) } else null)
                                ?: Employee(name = name.trim())

                            val employee = baseTarget.copy(
                                name = name.trim(),
                                email = email.trim().lowercase(),
                                phone = phone.trim(),
                                pin = "", // PIN login removed completely
                                role = role,
                                designation = when (role) {
                                    "CASHIER" -> "Cashier"
                                    "SALES_STAFF" -> "Sales Staff"
                                    "STORE_MANAGER" -> "Store Manager"
                                    else -> "Staff"
                                },
                                salaryType = "MONTHLY",
                                baseSalary = salary,
                                joiningDate = joiningDateMillis,
                                address = address.trim(),
                                emergencyContact = emergencyContact.trim(),
                                canMakeSales = canMakeSales,
                                canGiveDiscount = canGiveDiscount,
                                canViewCostPrice = canViewCostPrice,
                                canViewReports = canViewReports,
                                canManageInventory = canManageInventory,
                                canViewKhata = canViewKhata,
                                canManageExpenses = canManageExpenses,
                                canAccessSettings = canAccessSettings,
                                canDeleteSales = canDeleteSales,
                                isActive = isActive
                            )
                            onSave(employee)
                        },
                        enabled = name.isNotBlank() && (initialEmployee != null || isOwnerOrAdmin),
                        colors = ButtonDefaults.buttonColors(containerColor = StoreRedPrimary),
                        modifier = Modifier.weight(1.2f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = if (initialEmployee == null) {
                                if (isBengali) "কর্মী যুক্ত করুন" else "Save Employee"
                            } else {
                                if (isBengali) "আপডেট করুন" else "Update Employee"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun UnifiedPermissionSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
                color = TextDark,
                fontSize = 12.5.sp
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                fontSize = 10.5.sp
            )
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = StoreGreenProfit
            ),
            modifier = Modifier.scale(0.8f)
        )
    }
}
