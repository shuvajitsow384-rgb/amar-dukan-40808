package com.example.ui.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.local.entities.Employee
import com.example.ui.theme.*
import com.example.utils.StaffManager
import com.example.utils.StoreInfoManager

@Composable
fun StaffSwitchDialog(
    employees: List<Employee>,
    onDismiss: () -> Unit,
    onManageEmployees: () -> Unit = {}
) {
    val context = LocalContext.current

    val ownerEmployee = employees.firstOrNull { emp ->
        emp.email.trim().equals("shuvajitsow384@gmail.com", ignoreCase = true) ||
        emp.name.trim().contains("Shuvajit", ignoreCase = true) ||
        (StoreInfoManager.ownerName.isNotBlank() && StoreInfoManager.ownerName != "Store Owner" && emp.name.trim().equals(StoreInfoManager.ownerName.trim(), ignoreCase = true))
    }

    val ownerDisplayName = when {
        ownerEmployee != null && ownerEmployee.name.isNotBlank() -> ownerEmployee.name.trim()
        StoreInfoManager.ownerName.isNotBlank() && StoreInfoManager.ownerName != "Store Owner" -> StoreInfoManager.ownerName.trim()
        else -> "Shuvajit Show"
    }

    val masterTitle = "$ownerDisplayName (Owner / Admin)"
    val ownerEmail = ownerEmployee?.email?.takeIf { it.isNotBlank() } ?: "shuvajitsow384@gmail.com"

    // Employee List (Deduplicated by normalized email and excluding the Owner)
    val activeStaffList = remember(employees, ownerDisplayName) {
        val active = employees.filter { it.isActive }
        val nonOwnerEmployees = active.filterNot { emp ->
            emp.email.trim().equals("shuvajitsow384@gmail.com", ignoreCase = true) ||
            emp.name.trim().equals(ownerDisplayName, ignoreCase = true) ||
            emp.name.trim().contains("Shuvajit", ignoreCase = true)
        }

        val byEmail = nonOwnerEmployees.groupBy { it.email.trim().lowercase() }
        val result = mutableListOf<Employee>()
        for ((email, group) in byEmail) {
            if (email.isBlank()) {
                result.addAll(group)
            } else {
                // Pick the best employee record: prefer real name, then real ID
                val best = group.sortedWith(
                    compareByDescending<Employee> { it.name.isNotBlank() && it.name != "App User" && it.name != "Employee" && it.name != "Unrecognized User" }
                        .thenByDescending { !it.id.contains("@") }
                        .thenByDescending { it.createdAt }
                ).first()
                result.add(best)
            }
        }
        result
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
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
                                    Icons.Default.Badge,
                                    contentDescription = "Staff",
                                    tint = StoreRedPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Switch Staff Profile",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextDark
                            )
                            Text(
                                text = "Active: ${StaffManager.getCurrentStaffDisplayName()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = StoreRedPrimary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Profile selection list
                Text(
                    text = "SELECT ACTIVE OPERATOR",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted,
                    letterSpacing = 1.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Store Owner Item (Unified Master Profile)
                    item {
                        val isCurrentlyOwner = StaffManager.isOwnerMode() || (ownerEmployee != null && StaffManager.activeStaff?.id == ownerEmployee.id)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isCurrentlyOwner) StoreRedPrimary.copy(alpha = 0.1f) else BackgroundLight,
                            border = BorderStroke(
                                1.dp,
                                if (isCurrentlyOwner) StoreRedPrimary else Color.Transparent
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isCurrentlyOwner && StaffManager.isOwnerMode()) {
                                        Toast.makeText(context, "Already in $masterTitle mode", Toast.LENGTH_SHORT).show()
                                    } else {
                                        StaffManager.loginAsOwner()
                                        Toast.makeText(context, "Switched to $masterTitle", Toast.LENGTH_SHORT).show()
                                        onDismiss()
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = StoreGold.copy(alpha = 0.2f),
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.AdminPanelSettings,
                                            contentDescription = "Owner",
                                            tint = StoreGold,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = masterTitle,
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = TextDark,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        if (isCurrentlyOwner) {
                                            Surface(
                                                color = StoreGreenProfit.copy(alpha = 0.15f),
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    text = "Active",
                                                    color = StoreGreenProfit,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = "Full Access (P&L, Costs, Stock, Settings) • $ownerEmail",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextMuted,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Icon(
                                    Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = TextMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // Employee List (Deduplicated by normalized email)
                    if (activeStaffList.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No staff members registered yet.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted
                                )
                            }
                        }
                    } else {
                        items(activeStaffList, key = { it.id }, contentType = { "STAFF_SWITCH_ITEM" }) { employee ->
                            val isCurrent = StaffManager.activeStaff?.id == employee.id
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isCurrent) StoreRedPrimary.copy(alpha = 0.1f) else BackgroundLight,
                                border = BorderStroke(
                                    1.dp,
                                    if (isCurrent) StoreRedPrimary else Color.Transparent
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isCurrent) {
                                            Toast.makeText(context, "Already logged in as ${employee.name}", Toast.LENGTH_SHORT).show()
                                        } else {
                                            StaffManager.loginAsStaff(employee)
                                            Toast.makeText(context, "Logged in as ${employee.name} (${employee.role})", Toast.LENGTH_SHORT).show()
                                            onDismiss()
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = StoreRedPrimary.copy(alpha = 0.12f),
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = employee.name.take(1).uppercase(),
                                                fontWeight = FontWeight.Bold,
                                                color = StoreRedPrimary,
                                                fontSize = 14.sp
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = employee.name,
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = TextDark,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            if (isCurrent) {
                                                Surface(
                                                    color = StoreGreenProfit.copy(alpha = 0.15f),
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        text = "Active",
                                                        color = StoreGreenProfit,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        maxLines = 1,
                                                        softWrap = false,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            text = "${employee.role} ${if (employee.email.isNotBlank()) "• ${employee.email}" else if (employee.phone.isNotBlank()) "• ${employee.phone}" else ""}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextMuted,
                                            fontSize = 11.sp
                                        )
                                    }
                                    Icon(
                                        Icons.Default.ChevronRight,
                                        contentDescription = null,
                                        tint = TextMuted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Manage Staff button
                OutlinedButton(
                    onClick = {
                        onDismiss()
                        onManageEmployees()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.People, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add or Manage Staff Members")
                }
            }
        }
    }
}
