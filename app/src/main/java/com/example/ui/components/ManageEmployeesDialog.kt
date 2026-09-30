package com.example.ui.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.local.entities.Employee
import com.example.ui.theme.*
import com.example.viewmodel.StoreViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageEmployeesDialog(
    viewModel: StoreViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val allEmployees by viewModel.allEmployees.collectAsState()
    val sortedEmployees = remember(allEmployees) {
        val uniqueMap = mutableMapOf<String, Employee>()
        for (emp in allEmployees) {
            val key = if (emp.email.isNotBlank()) emp.email.trim().lowercase() else "id_${emp.id}"
            val existing = uniqueMap[key]
            if (existing == null) {
                uniqueMap[key] = emp
            } else {
                // Keep the record with phone or salary or non-blank details
                val merged = existing.copy(
                    name = if (existing.name.isNotBlank() && existing.name != "App User" && existing.name != "Employee") existing.name else emp.name,
                    phone = existing.phone.ifBlank { emp.phone },
                    baseSalary = if (existing.baseSalary > 0) existing.baseSalary else emp.baseSalary,
                    salaryType = if (existing.salaryType.isNotBlank()) existing.salaryType else emp.salaryType,
                    role = if (existing.role != "CASHIER") existing.role else emp.role,
                    designation = if (existing.designation != "Staff") existing.designation else emp.designation
                )
                uniqueMap[key] = merged
            }
        }
        uniqueMap.values.sortedWith(
            compareBy(
                { if (it.email.isBlank()) "zzzzzz" else it.email.trim().lowercase() },
                { it.name.trim().lowercase() }
            )
        )
    }

    var showAddEditDialog by remember { mutableStateOf(false) }
    var editingEmployee by remember { mutableStateOf<Employee?>(null) }
    var employeeToDelete by remember { mutableStateOf<Employee?>(null) }
    var showChangePinDialog by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = StoreRedPrimary.copy(alpha = 0.12f),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.ManageAccounts,
                                    contentDescription = "Staff Management",
                                    tint = StoreRedPrimary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Employee & Staff Management",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextDark
                            )
                            Text(
                                text = "Google Sign-In Access, Roles & Permissions",
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

                // Multi-Device Cloud Sync Info Banner
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = StorePrimary.copy(alpha = 0.08f)),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, StorePrimary.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CloudDone, contentDescription = null, tint = StorePrimary, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Google Sign-In Access",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = StorePrimary
                            )
                            Text(
                                text = "Employees sign in with their own Google account on their phone. When added here, they automatically receive their assigned role & permissions without needing any PIN.",
                                fontSize = 10.5.sp,
                                color = TextDark,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Owner Master PIN Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBEB)),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFFFDE68A))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = StoreGold.copy(alpha = 0.2f),
                                modifier = Modifier.size(32.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.AdminPanelSettings,
                                        contentDescription = "Master PIN",
                                        tint = Color(0xFFB45309),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Owner Master PIN",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = Color(0xFF92400E)
                                )
                                Text(
                                    text = "Used to unlock offline mode & admin control",
                                    fontSize = 10.5.sp,
                                    color = Color(0xFFB45309)
                                )
                            }
                        }

                        Button(
                            onClick = { showChangePinDialog = true },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(13.dp), tint = Color.White)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Change PIN", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Action Bar: Add Staff Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "REGISTERED STAFF (${sortedEmployees.size})",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextMuted,
                        letterSpacing = 1.sp
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = {
                                viewModel.mergeDuplicateStaffProfiles { res ->
                                    Toast.makeText(context, if (res.isSuccess) "Staff profiles synced & deduplicated" else "Sync completed", Toast.LENGTH_SHORT).show()
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Sync", modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Sync", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                editingEmployee = null
                                showAddEditDialog = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = StoreRedPrimary),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Add Employee", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Employee List
                if (allEmployees.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Icon(
                                Icons.Default.GroupAdd,
                                contentDescription = null,
                                tint = TextMuted.copy(alpha = 0.5f),
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No Employees Added Yet",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall,
                                color = TextDark
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Add cashier or sales staff with their Google Email to let them bill on their phones with assigned permissions.",
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(sortedEmployees, key = { it.id }) { employee ->
                            EmployeeItemCard(
                                employee = employee,
                                onEdit = {
                                    editingEmployee = employee
                                    showAddEditDialog = true
                                },
                                onDelete = {
                                    employeeToDelete = employee
                                },
                                onToggleActive = { isActive ->
                                    viewModel.updateEmployee(employee.copy(isActive = isActive))
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    val currentFirestoreRole by viewModel.currentFirestoreUserRole.collectAsState()
    val isOwnerOrAdminUser = if (currentFirestoreRole != null) {
        currentFirestoreRole!!.isAdmin
    } else {
        com.example.utils.StaffManager.isOwner()
    }

    // Unified Add / Edit Employee Dialog
    if (showChangePinDialog) {
        ChangeOwnerPinDialog(
            onDismiss = { showChangePinDialog = false }
        )
    }

    if (showAddEditDialog) {
        UnifiedAddEditEmployeeDialog(
            initialEmployee = editingEmployee,
            existingEmployees = allEmployees,
            isOwnerOrAdmin = isOwnerOrAdminUser,
            onDismiss = { showAddEditDialog = false },
            onSave = { emp ->
                if (editingEmployee == null) {
                    viewModel.saveEmployee(emp) {
                        Toast.makeText(context, "Employee saved successfully!", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    viewModel.updateEmployee(emp) {
                        Toast.makeText(context, "Employee updated!", Toast.LENGTH_SHORT).show()
                    }
                }
                showAddEditDialog = false
            }
        )
    }

    // Delete Confirmation Dialog
    employeeToDelete?.let { emp ->
        AlertDialog(
            onDismissRequest = { employeeToDelete = null },
            title = { Text("Delete Employee?", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to remove ${emp.name}? Their Google Sign-In role and access will be revoked.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteEmployee(emp) {
                            Toast.makeText(context, "${emp.name} deleted", Toast.LENGTH_SHORT).show()
                        }
                        employeeToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { employeeToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmployeeItemCard(
    employee: Employee,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleActive: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (employee.isActive) Color(0xFFE2E8F0) else Color(0xFFFFCDD2)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header Row: Avatar, Name & Role, and Action controls (Switch, Edit, Delete)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (employee.isActive) StoreRedPrimary.copy(alpha = 0.12f) else TextMuted.copy(alpha = 0.15f),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = employee.name.take(1).uppercase(),
                                fontWeight = FontWeight.Bold,
                                color = if (employee.isActive) StoreRedPrimary else TextMuted,
                                fontSize = 15.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Text(
                            text = employee.name,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (employee.isActive) TextDark else TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        
                        Spacer(modifier = Modifier.height(2.dp))

                        Surface(
                            color = when (employee.role) {
                                "CASHIER" -> Color(0xFFE0F2FE)
                                "STORE_MANAGER" -> Color(0xFFFEF3C7)
                                "SALES_STAFF" -> Color(0xFFDCFCE7)
                                else -> Color(0xFFF1F5F9)
                            },
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = employee.role.replace("_", " "),
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = when (employee.role) {
                                    "CASHIER" -> Color(0xFF0284C7)
                                    "STORE_MANAGER" -> Color(0xFFD97706)
                                    "SALES_STAFF" -> Color(0xFF16A34A)
                                    else -> TextDark
                                },
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    Switch(
                        checked = employee.isActive,
                        onCheckedChange = onToggleActive,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = StoreGreenProfit
                        ),
                        modifier = Modifier.scale(0.75f)
                    )

                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit", tint = TextMuted, modifier = Modifier.size(16.dp))
                    }

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    }
                }
            }

            // Google Email & Details (Phone / Salary) Box
            if (employee.email.isNotBlank() || employee.phone.isNotBlank() || employee.baseSalary > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF8FAFC), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    if (employee.email.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AlternateEmail, contentDescription = null, tint = StorePrimary, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = employee.email,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = StorePrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (employee.phone.isNotBlank() || employee.baseSalary > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (employee.phone.isNotBlank()) {
                                Icon(Icons.Default.Phone, contentDescription = null, tint = TextMuted, modifier = Modifier.size(11.dp))
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = employee.phone,
                                    fontSize = 10.5.sp,
                                    color = TextMuted
                                )
                            }
                            if (employee.phone.isNotBlank() && employee.baseSalary > 0) {
                                Text(text = " • ", fontSize = 10.5.sp, color = TextMuted)
                            }
                            if (employee.baseSalary > 0) {
                                Text(
                                    text = "Salary: ₹${"%.0f".format(employee.baseSalary)}/mo",
                                    fontSize = 10.5.sp,
                                    color = TextDark,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Permission badges summary with wrapping FlowRow
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                StaffPermissionPill(label = "Billing", allowed = employee.canMakeSales)
                StaffPermissionPill(label = "Discounts", allowed = employee.canGiveDiscount)
                StaffPermissionPill(label = "Cost Price", allowed = employee.canViewCostPrice)
                StaffPermissionPill(label = "P&L Reports", allowed = employee.canViewReports)
                StaffPermissionPill(label = "Stock", allowed = employee.canManageInventory)
                StaffPermissionPill(label = "Khata", allowed = employee.canViewKhata)
            }
        }
    }
}

@Composable
fun StaffPermissionPill(label: String, allowed: Boolean) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = if (allowed) StoreGreenProfit.copy(alpha = 0.12f) else Color(0xFFFFEBEE)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp)
        ) {
            Icon(
                if (allowed) Icons.Default.Check else Icons.Default.Lock,
                contentDescription = null,
                tint = if (allowed) StoreGreenProfit else Color(0xFFD32F2F),
                modifier = Modifier.size(10.dp)
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = label,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                color = if (allowed) StoreGreenProfit else Color(0xFFD32F2F),
                maxLines = 1
            )
        }
    }
}
