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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.firestore.AppRole
import com.example.data.firestore.FirestoreUserRole
import com.example.ui.theme.*
import com.example.utils.LanguageManager
import com.example.viewmodel.StoreViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class PendingStaffTab(val labelEn: String, val labelBn: String) {
    PENDING("Pending", "অপেক্ষমান"),
    APPROVED("Approved", "অনুমোদিত"),
    REJECTED("Declined", "প্রত্যাখ্যাত"),
    ALL("All Users", "সকল ইউজার");

    fun getLabel(): String = if (LanguageManager.isBengali) labelBn else labelEn
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingStaffAccessDialog(
    viewModel: StoreViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val allUsers by viewModel.allFirestoreUsers.collectAsState()
    var selectedTab by remember { mutableStateOf(PendingStaffTab.PENDING) }
    var searchQuery by remember { mutableStateOf("") }

    // Dialog state for approval/rejection confirmation
    var userToApprove by remember { mutableStateOf<FirestoreUserRole?>(null) }
    var userToDecline by remember { mutableStateOf<FirestoreUserRole?>(null) }
    var isActionInProgress by remember { mutableStateOf(false) }

    // Filter requests
    val filteredUsers = remember(allUsers, selectedTab, searchQuery) {
        allUsers.filter { user ->
            val matchesTab = when (selectedTab) {
                PendingStaffTab.PENDING -> user.isPendingApproval
                PendingStaffTab.APPROVED -> user.isApproved && !user.isPermanentAdmin
                PendingStaffTab.REJECTED -> user.isRejected
                PendingStaffTab.ALL -> true
            }

            val query = searchQuery.trim().lowercase()
            val matchesQuery = if (query.isBlank()) true else {
                (user.displayName?.lowercase()?.contains(query) == true) ||
                (user.email?.lowercase()?.contains(query) == true) ||
                user.uid.lowercase().contains(query) ||
                user.role.lowercase().contains(query)
            }

            matchesTab && matchesQuery
        }.sortedByDescending { it.lastActiveAt.coerceAtLeast(it.createdAt) }
    }

    val pendingCount = remember(allUsers) {
        allUsers.count { it.isPendingApproval }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = StorePrimary.copy(alpha = 0.12f),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.HowToReg,
                                    contentDescription = null,
                                    tint = StorePrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = if (LanguageManager.isBengali) "স্টাফ অনুমোদন আবেদন" else "Pending Staff Access Requests",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                if (pendingCount > 0) {
                                    Surface(
                                        shape = CircleShape,
                                        color = Color(0xFFF59E0B)
                                    ) {
                                        Text(
                                            text = "$pendingCount",
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }

                            Text(
                                text = if (LanguageManager.isBengali)
                                    "নতুন সাইন-ইন করা অ্যাকাউন্টের স্টাফ হিসেবে যোগদানের আবেদন যাচাই ও অনুমোদন করুন"
                                else
                                    "Review new sign-in requests before granting store access",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Search Bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            if (LanguageManager.isBengali) "নাম, ইমেইল বা UID দিয়ে খুঁজুন..." else "Search by name, email, or UID...",
                            fontSize = 13.sp
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    Icons.Default.Clear,
                                    contentDescription = "Clear",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = StorePrimary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Filter Tabs
                ScrollableTabRow(
                    selectedTabIndex = selectedTab.ordinal,
                    edgePadding = 0.dp,
                    divider = {},
                    containerColor = Color.Transparent,
                    indicator = {}
                ) {
                    PendingStaffTab.values().forEach { tab ->
                        val count = when (tab) {
                            PendingStaffTab.PENDING -> pendingCount
                            PendingStaffTab.APPROVED -> allUsers.count { it.isApproved && !it.isPermanentAdmin }
                            PendingStaffTab.REJECTED -> allUsers.count { it.isRejected }
                            PendingStaffTab.ALL -> allUsers.size
                        }

                        Tab(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            text = {
                                Text(
                                    text = "${tab.getLabel()} ($count)",
                                    fontSize = 12.sp,
                                    fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selectedTab == tab) StorePrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

                Spacer(modifier = Modifier.height(10.dp))

                // List of Requests
                if (filteredUsers.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Shield,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = if (LanguageManager.isBengali)
                                    "কোনো আবেদন পাওয়া যায়নি"
                                else
                                    "No staff access requests found",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(filteredUsers, key = { it.uid.ifBlank { it.email ?: it.documentId } }) { user ->
                            StaffRequestCard(
                                user = user,
                                viewModel = viewModel,
                                onApprove = { userToApprove = user },
                                onDecline = { userToDecline = user }
                            )
                        }
                    }
                }
            }
        }
    }

    // Confirmation Dialog for APPROVE
    userToApprove?.let { user ->
        ApproveStaffDialog(
            user = user,
            viewModel = viewModel,
            isLoading = isActionInProgress,
            onDismiss = { if (!isActionInProgress) userToApprove = null },
            onConfirm = { assignedRole ->
                isActionInProgress = true
                viewModel.reviewPendingStaffAccess(
                    uid = user.uid,
                    approve = true,
                    assignedRole = assignedRole
                ) { success, msg ->
                    isActionInProgress = false
                    userToApprove = null
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // Confirmation Dialog for DECLINE
    userToDecline?.let { user ->
        DeclineStaffDialog(
            user = user,
            isLoading = isActionInProgress,
            onDismiss = { if (!isActionInProgress) userToDecline = null },
            onConfirm = {
                isActionInProgress = true
                viewModel.reviewPendingStaffAccess(
                    uid = user.uid,
                    approve = false
                ) { success, msg ->
                    isActionInProgress = false
                    userToDecline = null
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}

@Composable
private fun StaffRequestCard(
    user: FirestoreUserRole,
    viewModel: StoreViewModel,
    onApprove: () -> Unit,
    onDecline: () -> Unit
) {
    val isBengali = LanguageManager.isBengali
    val dateFormatter = remember { SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()) }
    val lastActiveFormatted = remember(user.lastActiveAt) {
        if (user.lastActiveAt > 0) dateFormatter.format(Date(user.lastActiveAt)) else "Never"
    }
    val createdFormatted = remember(user.createdAt) {
        if (user.createdAt > 0) dateFormatter.format(Date(user.createdAt)) else ""
    }

    // Live Read from Firestore users/{uid}
    var liveUserRole by remember { mutableStateOf<FirestoreUserRole?>(null) }
    var isLoadingLive by remember { mutableStateOf(false) }

    LaunchedEffect(user.uid) {
        if (user.uid.isNotBlank() && !user.uid.startsWith("preassigned_")) {
            isLoadingLive = true
            liveUserRole = viewModel.getLiveStaffUserDetails(user.uid)
            isLoadingLive = false
        }
    }

    val effectiveStatus = liveUserRole?.status ?: user.status
    val effectiveRole = liveUserRole?.role ?: user.role

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = if (user.isPendingApproval) 1.5.dp else 1.dp,
            color = if (user.isPendingApproval) Color(0xFFF59E0B) else MaterialTheme.colorScheme.outlineVariant
        ),
        tonalElevation = if (user.isPendingApproval) 2.dp else 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Header Row: Avatar, Name/Email, Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Avatar
                Surface(
                    shape = CircleShape,
                    color = when {
                        user.isPendingApproval -> Color(0xFFFEF3C7)
                        user.isApproved -> StoreGreenProfit.copy(alpha = 0.15f)
                        else -> StoreRedPrimary.copy(alpha = 0.15f)
                    },
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = (user.displayName?.take(1) ?: user.email?.take(1) ?: "U").uppercase(),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                user.isPendingApproval -> Color(0xFFB45309)
                                user.isApproved -> StoreGreenProfit
                                else -> StoreRedPrimary
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = user.displayName?.ifBlank { user.email ?: "Unknown Account" } ?: "Unknown Account",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = user.email ?: "No email registered",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Status Badge
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = when {
                        user.isPendingApproval -> Color(0xFFFEF3C7)
                        user.isApproved -> StoreGreenProfit.copy(alpha = 0.12f)
                        else -> StoreRedPrimary.copy(alpha = 0.12f)
                    }
                ) {
                    Text(
                        text = when {
                            user.isPendingApproval -> if (isBengali) "অপেক্ষমান" else "PENDING"
                            user.isApproved -> if (isBengali) "অনুমোদিত" else "APPROVED"
                            else -> if (isBengali) "প্রত্যাখ্যাত" else "DECLINED"
                        },
                        color = when {
                            user.isPendingApproval -> Color(0xFFB45309)
                            user.isApproved -> StoreGreenProfit
                            else -> StoreRedPrimary
                        },
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Timestamps and UID
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "UID: ${user.uid.take(12)}...",
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )

                Text(
                    text = if (isBengali) "লগইন চেষ্টা: $lastActiveFormatted" else "Attempt: $lastActiveFormatted",
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // MANDATORY LIVE READ OF users/{uid} BOX
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = if (user.isPendingApproval) Color(0xFFF8FAFC) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CloudSync,
                                contentDescription = null,
                                tint = StorePrimary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "LIVE FIRESTORE READ: users/${user.uid.take(8)}",
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = StorePrimary,
                                letterSpacing = 0.4.sp
                            )
                        }

                        if (isLoadingLive) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(10.dp),
                                    strokeWidth = 1.5.dp,
                                    color = StorePrimary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Reading live...", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            Text(
                                text = "Live Role: $effectiveRole | Status: $effectiveStatus",
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (effectiveRole in listOf("ADMIN", "STORE_MANAGER", "EMPLOYEE") && effectiveStatus == "APPROVED") StoreGreenProfit else Color(0xFFB45309)
                            )
                        }
                    }

                    if (user.reviewedBy != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Reviewed by: ${user.reviewedBy} ${user.reviewedAt?.let { "on " + dateFormatter.format(Date(it)) } ?: ""}",
                            fontSize = 9.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Action Buttons
            if (user.isPendingApproval || !user.isApproved) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDecline,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = StoreRedPrimary
                        ),
                        border = BorderStroke(1.dp, StoreRedPrimary.copy(alpha = 0.5f)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isBengali) "প্রত্যাখ্যান" else "Decline",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = onApprove,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StorePrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isBengali) "অনুমোদন করুন" else "Approve Access",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ApproveStaffDialog(
    user: FirestoreUserRole,
    viewModel: StoreViewModel,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (AppRole) -> Unit
) {
    val isBengali = LanguageManager.isBengali
    var selectedRole by remember { mutableStateOf(AppRole.EMPLOYEE) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = StorePrimary.copy(alpha = 0.15f),
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = StorePrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Text(
                    text = if (isBengali) "স্টাফ অ্যাক্সেস অনুমোদন" else "Approve Staff Access",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // User Details Card
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = user.displayName?.ifBlank { user.email ?: "Applicant" } ?: "Applicant",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = user.email ?: "No email",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "UID: ${user.uid}",
                            fontSize = 10.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }

                Text(
                    text = if (isBengali) "অনুমোদিত পদবি নির্বাচন করুন:" else "Assign Role to this Account:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )

                // Role Options
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    RoleOptionRow(
                        role = AppRole.EMPLOYEE,
                        selected = selectedRole == AppRole.EMPLOYEE,
                        title = if (isBengali) "ক্যাশিয়ার / বিক্রয়কর্মী (Cashier / Staff)" else "Cashier / Staff (Employee)",
                        subtitle = if (isBengali) "কাউন্টার বিলিং, বারকোড ও বিক্রয় করতে পারবে; লাভ-ক্ষতি ও কেনা দাম গোপন থাকবে" else "POS billing & sales only; profit reports and cost prices remain hidden",
                        onClick = { selectedRole = AppRole.EMPLOYEE }
                    )

                    RoleOptionRow(
                        role = AppRole.STORE_MANAGER,
                        selected = selectedRole == AppRole.STORE_MANAGER,
                        title = if (isBengali) "স্টোর ম্যানেজার (Store Manager)" else "Store Manager",
                        subtitle = if (isBengali) "স্টক সমন্বয়, খরচ ও খাতা দেখতে পারবে; স্টোর সেটিংস সীমাবদ্ধ" else "Inventory adjustments, expense logging, and khata; restricted settings",
                        onClick = { selectedRole = AppRole.STORE_MANAGER }
                    )

                    RoleOptionRow(
                        role = AppRole.ADMIN,
                        selected = selectedRole == AppRole.ADMIN,
                        title = if (isBengali) "মালিক / অ্যাডমিন (Store Admin)" else "Store Admin",
                        subtitle = if (isBengali) "সকল আর্থিক হিসাব, কেনা দাম, রিপোর্ট ও ইউজার ব্যবস্থাপনায় পূর্ণ অ্যাক্সেস" else "Full unrestricted access to profit reports, cost prices, settings, and staff",
                        onClick = { selectedRole = AppRole.ADMIN }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedRole) },
                enabled = !isLoading,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = StorePrimary)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    if (isBengali) "অনুমোদন নিশ্চিত করুন" else "Confirm Approval",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isLoading
            ) {
                Text(if (isBengali) "বাতিল" else "Cancel")
            }
        }
    )
}

@Composable
private fun RoleOptionRow(
    role: AppRole,
    selected: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) StorePrimary.copy(alpha = 0.08f) else Color.Transparent,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) StorePrimary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(selectedColor = StorePrimary)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Column {
                Text(
                    text = title,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) StorePrimary else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 14.sp
                )
            }
        }
    }
}

@Composable
private fun DeclineStaffDialog(
    user: FirestoreUserRole,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val isBengali = LanguageManager.isBengali

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = StoreRedPrimary.copy(alpha = 0.15f),
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Block,
                            contentDescription = null,
                            tint = StoreRedPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Text(
                    text = if (isBengali) "আবেদন প্রত্যাখ্যান করুন" else "Decline Staff Request",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = StoreRedPrimary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (isBengali)
                        "আপনি কি নিশ্চিত যে আপনি ${user.displayName ?: user.email} এর স্টাফ অ্যাক্সেস আবেদন প্রত্যাখ্যান করতে চান?"
                    else
                        "Are you sure you want to decline staff access for ${user.displayName ?: user.email}?",
                    fontSize = 13.sp
                )

                Surface(
                    color = StoreRedPrimary.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isBengali)
                            "⚠️ এই অ্যাকাউন্টটির জিরো-অ্যাক্সেস বজায় থাকবে এবং POS, খাতা বা বিক্রয় ডেটা দেখার কোনো অনুমতি পাবে না।"
                        else
                            "⚠️ This account will remain locked out with zero access. They cannot access POS, Khata, Sales, or any store data.",
                        fontSize = 11.5.sp,
                        color = StoreRedPrimary,
                        modifier = Modifier.padding(8.dp),
                        lineHeight = 16.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isLoading,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = StoreRedPrimary)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    if (isBengali) "প্রত্যাখ্যান নিশ্চিত করুন" else "Confirm Decline",
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isLoading
            ) {
                Text(if (isBengali) "বাতিল" else "Cancel")
            }
        }
    )
}
