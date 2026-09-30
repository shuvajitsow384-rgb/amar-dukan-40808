package com.example.ui.screens.users

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.firestore.AppRole
import com.example.data.firestore.FirestoreUserRole
import com.example.data.firestore.PERMANENT_ADMIN_EMAILS
import com.example.ui.components.StaffAccessGate
import com.example.ui.theme.*
import com.example.utils.LanguageManager
import com.example.utils.StaffManager
import com.example.viewmodel.StoreViewModel
import java.text.SimpleDateFormat
import java.util.*

enum class UserFilterTab {
    ALL,
    STAFF,
    UNRECOGNIZED,
    PREASSIGNED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllAppUsersScreen(
    viewModel: StoreViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val isBengali = LanguageManager.isBengali
    val currentFirestoreRole by viewModel.currentFirestoreUserRole.collectAsState()
    val authUser by viewModel.currentUser.collectAsState()

    // STRICT OWNER / ADMIN ONLY GATE:
    // Not visible to Manager, Cashier, or Unrecognized roles.
    val isStoreAdmin = currentFirestoreRole?.isAdmin == true ||
            authUser?.email?.trim()?.lowercase() in PERMANENT_ADMIN_EMAILS ||
            (StaffManager.isOwner() && authUser == null)

    if (!isStoreAdmin) {
        StaffAccessGate(
            screenTitle = if (isBengali) "অ্যাপ ব্যবহারকারী অডিট (সীমাবদ্ধ)" else "All App Users Audit (Restricted)",
            screenDescription = if (isBengali)
                "এই স্ক্রিনে সাইন-ইন করা সমস্ত Google অ্যাকাউন্টের ইতিহাস দেখার জন্য শুধুমাত্র স্টোর ওনার/অ্যাডমিনের অনুমতি রয়েছে।"
            else
                "Access to the complete sign-in history of all Google accounts is strictly confidential and reserved for the Store Admin / Owner only."
        )
        return
    }

    val allUsers by viewModel.allFirestoreUsers.collectAsState()
    val preassignedRoles by viewModel.allPreassignedRoles.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(UserFilterTab.ALL) }
    var userToEditRole by remember { mutableStateOf<FirestoreUserRole?>(null) }
    var userToDelete by remember { mutableStateOf<FirestoreUserRole?>(null) }
    var showPreassignDialog by remember { mutableStateOf(false) }

    // Deduplicate registered accounts by normalized email using mergeUserRoleGroup
    val distinctUsers = remember(allUsers) {
        val byEmail = allUsers.groupBy { it.email?.trim()?.lowercase() ?: "" }
        val result = mutableListOf<FirestoreUserRole>()
        for ((email, group) in byEmail) {
            if (email.isBlank()) {
                result.addAll(group)
            } else {
                result.add(com.example.data.firestore.mergeUserRoleGroup(group))
            }
        }
        result
    }

    // Merge registered accounts with any preassigned invitations that haven't registered a UID yet
    val registeredEmails = remember(distinctUsers) {
        distinctUsers.mapNotNull { it.email?.trim()?.lowercase() }.toSet()
    }
    val unredeemedInvites = remember(preassignedRoles, registeredEmails) {
        val seen = mutableSetOf<String>()
        preassignedRoles.filter { invite ->
            val email = invite.email?.trim()?.lowercase() ?: ""
            email.isNotBlank() && email !in registeredEmails && seen.add(email)
        }
    }

    // Counts
    val totalRegistered = distinctUsers.size
    val staffCount = distinctUsers.count { it.isAdmin || it.isManager || it.role.equals(AppRole.EMPLOYEE.roleName, ignoreCase = true) }
    val unrecognizedCount = distinctUsers.count { it.isUnrecognized }
    val pendingInviteCount = unredeemedInvites.size

    val filteredList = remember(distinctUsers, unredeemedInvites, selectedTab, searchQuery) {
        val baseList: List<Pair<FirestoreUserRole, Boolean>> = when (selectedTab) {
            UserFilterTab.ALL -> {
                val registered = distinctUsers.map { it to false }
                val invites = unredeemedInvites.map { it to true }
                (registered + invites).sortedByDescending { it.first.lastActiveAt.coerceAtLeast(it.first.updatedAt) }
            }
            UserFilterTab.STAFF -> {
                distinctUsers.filter { !it.isUnrecognized }.map { it to false }
                    .sortedByDescending { it.first.lastActiveAt }
            }
            UserFilterTab.UNRECOGNIZED -> {
                distinctUsers.filter { it.isUnrecognized }.map { it to false }
                    .sortedByDescending { it.first.lastActiveAt }
            }
            UserFilterTab.PREASSIGNED -> {
                unredeemedInvites.map { it to true }
            }
        }

        if (searchQuery.isBlank()) {
            baseList
        } else {
            val q = searchQuery.trim().lowercase()
            baseList.filter { (user, _) ->
                (user.email?.lowercase()?.contains(q) == true) ||
                        (user.displayName?.lowercase()?.contains(q) == true) ||
                        (user.role.lowercase().contains(q)) ||
                        (user.uid.lowercase().contains(q))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (isBengali) "সমস্ত অ্যাপ ব্যবহারকারী ও সাইন-ইন অডিট" else "All App Users & Sign-in Audit",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextDark
                        )
                        Text(
                            text = if (isBengali) "স্টোর ওনার সিকিউরিটি কনসোল" else "Owner Security & Identity Console",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshFirestoreSync() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showPreassignDialog = true },
                containerColor = StorePrimary,
                contentColor = Color.White,
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isBengali) "স্টাফ প্রি-অথরাইজ করুন" else "Pre-Authorize Staff",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(BackgroundLight)
        ) {
            // Metrics Header Overview
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isBengali) "সাইন-ইন নিরাপত্তা পরিসংখ্যান" else "Sign-in Security Overview",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.5.sp,
                            color = TextDark
                        )
                        Surface(
                            color = StoreGreenProfit.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "Live Cloud Synced",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = StoreGreenProfit,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MetricCard(
                            label = if (isBengali) "মোট অ্যাকাউন্ট" else "Total Accounts",
                            value = "${totalRegistered + pendingInviteCount}",
                            icon = Icons.Default.People,
                            color = StorePrimary,
                            modifier = Modifier.weight(1f)
                        )
                        MetricCard(
                            label = if (isBengali) "অনুমোদিত স্টাফ" else "Authorized Staff",
                            value = "$staffCount",
                            icon = Icons.Default.VerifiedUser,
                            color = StoreGreenProfit,
                            modifier = Modifier.weight(1f)
                        )
                        MetricCard(
                            label = if (isBengali) "অননুমোদিত চেষ্টা" else "Unrecognized",
                            value = "$unrecognizedCount",
                            icon = Icons.Default.NoAccounts,
                            color = if (unrecognizedCount > 0) StoreRedPrimary else TextMuted,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        if (isBengali) "ইমেইল বা নাম দিয়ে খুঁজুন..." else "Search by email, name or role...",
                        fontSize = 13.sp
                    )
                },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted, modifier = Modifier.size(20.dp))
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(18.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White,
                    focusedBorderColor = StorePrimary,
                    unfocusedBorderColor = Color(0xFFE2E8F0)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp)
            )

            // Filter Tabs
            ScrollableTabRow(
                selectedTabIndex = selectedTab.ordinal,
                edgePadding = 14.dp,
                containerColor = Color.Transparent,
                divider = {},
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                Tab(
                    selected = selectedTab == UserFilterTab.ALL,
                    onClick = { selectedTab = UserFilterTab.ALL },
                    text = { Text("All (${totalRegistered + pendingInviteCount})", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold) }
                )
                Tab(
                    selected = selectedTab == UserFilterTab.STAFF,
                    onClick = { selectedTab = UserFilterTab.STAFF },
                    text = { Text("Staff ($staffCount)", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold) }
                )
                Tab(
                    selected = selectedTab == UserFilterTab.UNRECOGNIZED,
                    onClick = { selectedTab = UserFilterTab.UNRECOGNIZED },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Unrecognized ($unrecognizedCount)", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                            if (unrecognizedCount > 0) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Surface(
                                    color = StoreRedPrimary,
                                    shape = CircleShape,
                                    modifier = Modifier.size(8.dp)
                                ) {}
                            }
                        }
                    }
                )
                Tab(
                    selected = selectedTab == UserFilterTab.PREASSIGNED,
                    onClick = { selectedTab = UserFilterTab.PREASSIGNED },
                    text = { Text("Pending Invites ($pendingInviteCount)", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold) }
                )
            }

            // User List
            if (filteredList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.PersonSearch,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = TextMuted.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (searchQuery.isNotEmpty())
                                "No accounts matching '$searchQuery'"
                            else if (selectedTab == UserFilterTab.UNRECOGNIZED)
                                "No unrecognized or blocked accounts. Everything is secure!"
                            else
                                "No accounts found.",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            color = TextMuted
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(
                        filteredList,
                        key = { (user, isInvite) ->
                            val prefix = if (isInvite) "invite_" else "user_"
                            val rawKey = user.documentId.takeIf { it.isNotBlank() }
                                ?: user.email?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
                                ?: user.uid.takeIf { it.isNotBlank() }
                                ?: user.hashCode().toString()
                            "$prefix$rawKey"
                        },
                        contentType = { (_, isInvite) -> if (isInvite) "INVITE_USER_CARD" else "APP_USER_CARD" }
                    ) { (user, isInvite) ->
                        AppUserAuditCard(
                            user = user,
                            isPendingInvite = isInvite,
                            isCurrentLoggedInUser = authUser?.uid == user.uid,
                            onEditRole = { userToEditRole = user },
                            onDelete = { userToDelete = user }
                        )
                    }
                    item {
                        Spacer(modifier = Modifier.height(72.dp))
                    }
                }
            }
        }
    }

    // Role Modification Dialog
    if (userToEditRole != null) {
        EditUserRoleDialog(
            userRole = userToEditRole!!,
            viewModel = viewModel,
            onDismiss = { userToEditRole = null }
        )
    }

    // Preassign Role Dialog
    if (showPreassignDialog) {
        PreassignStaffDialog(
            viewModel = viewModel,
            onDismiss = { showPreassignDialog = false }
        )
    }

    // Delete Confirmation Dialog
    if (userToDelete != null) {
        val target = userToDelete!!
        AlertDialog(
            onDismissRequest = { userToDelete = null },
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = StoreRedPrimary) },
            title = { Text(text = "Revoke & Delete Cloud Access?") },
            text = {
                Text(
                    text = "Are you sure you want to remove '${target.email ?: target.displayName ?: target.uid}' from Firestore? This account will have all permissions revoked immediately."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteCloudUserFromFirestore(target) { success, msg ->
                            Toast.makeText(context, msg ?: if (success) "Deleted" else "Failed", Toast.LENGTH_SHORT).show()
                            userToDelete = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StoreRedPrimary)
                ) {
                    Text("Delete Account", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { userToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun MetricCard(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = color.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.25f))
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = color)
            Text(text = label, fontSize = 10.sp, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun AppUserAuditCard(
    user: FirestoreUserRole,
    isPendingInvite: Boolean,
    isCurrentLoggedInUser: Boolean,
    onEditRole: () -> Unit,
    onDelete: () -> Unit
) {
    val isBengali = LanguageManager.isBengali
    val isUnrecognized = user.isUnrecognized
    val isPermanent = user.isPermanentAdmin
    val isAdmin = user.isAdmin
    val isManager = user.isManager

    val sdf = remember { SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()) }
    val firstSignInStr = remember(user.createdAt) {
        if (user.createdAt > 0) sdf.format(Date(user.createdAt)) else "Never (Pending Invite)"
    }
    val lastActiveStr = remember(user.lastActiveAt, user.updatedAt) {
        val ts = if (user.lastActiveAt > 0) user.lastActiveAt else user.updatedAt
        if (ts > 0) sdf.format(Date(ts)) else "N/A"
    }

    val badgeColor = when {
        isPermanent || isAdmin -> StoreGold
        isManager -> Color(0xFF2563EB)
        isUnrecognized -> StoreRedPrimary
        isPendingInvite -> Color(0xFFD97706)
        else -> StoreGreenProfit
    }

    val badgeText = when {
        isPermanent -> "👑 Permanent Owner / Admin"
        isAdmin -> "👑 Store Admin"
        isManager -> "👔 Store Manager"
        isPendingInvite -> "⏳ Preassigned Invite (Pending)"
        isUnrecognized -> "⛔ Unrecognized — No Access"
        else -> "👤 Cashier / Staff"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isUnrecognized) Color(0xFFFFF1F2) else CardBackground
        ),
        border = BorderStroke(
            1.dp,
            if (isUnrecognized) StoreRedPrimary.copy(alpha = 0.4f)
            else if (isPermanent) StoreGold.copy(alpha = 0.6f)
            else Color(0xFFE2E8F0)
        ),
        elevation = CardDefaults.cardElevation(if (isUnrecognized) 2.dp else 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header Row: User Identity & Role Badge
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
                        color = badgeColor.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.5f)),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = when {
                                    isPermanent || isAdmin -> Icons.Default.Stars
                                    isManager -> Icons.Default.SupervisorAccount
                                    isUnrecognized -> Icons.Default.PersonOff
                                    isPendingInvite -> Icons.Default.MarkEmailRead
                                    else -> Icons.Default.Person
                                },
                                contentDescription = null,
                                tint = badgeColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = user.displayName ?: user.email?.substringBefore("@") ?: "Unknown User",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = TextDark
                            )
                            if (isCurrentLoggedInUser) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    color = StorePrimary.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = "YOU",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = StorePrimary,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = user.email ?: "No email registered",
                            fontSize = 12.sp,
                            color = TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = badgeColor.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = badgeText,
                        color = badgeColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = Color(0xFFF1F5F9), thickness = 1.dp)
            Spacer(modifier = Modifier.height(10.dp))

            // Timestamps: First Sign In & Last Active
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isBengali) "প্রথম সাইন-ইন:" else "First Sign-in:",
                        fontSize = 10.5.sp,
                        color = TextMuted
                    )
                    Text(
                        text = firstSignInStr,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextDark
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isBengali) "সর্বশেষ সক্রিয়:" else "Last Active:",
                        fontSize = 10.5.sp,
                        color = TextMuted
                    )
                    Text(
                        text = lastActiveStr,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isUnrecognized) StoreRedPrimary else TextDark
                    )
                }
            }

            // Document ID / Unique Identifier Row
            Spacer(modifier = Modifier.height(6.dp))
            Surface(
                color = Color(0xFFF1F5F9),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Fingerprint,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Doc ID: ${user.documentId.ifBlank { user.uid }}",
                        fontSize = 10.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Security Status / Zero Access Explanatory Note
            if (isUnrecognized) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = StoreRedPrimary.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Security,
                            contentDescription = null,
                            tint = StoreRedPrimary,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isBengali)
                                "এই অ্যাকাউন্টের কোনো ডেটা বা বিক্রয় অ্যাক্সেস নেই (জিরো পারমিশন)।"
                            else
                                "Blocked by security rule: zero permissions & zero data access granted.",
                            fontSize = 11.sp,
                            color = StoreRedPrimary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isPermanent) {
                    OutlinedButton(
                        onClick = onDelete,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, StoreRedPrimary.copy(alpha = 0.5f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = StoreRedPrimary),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isBengali) "মুছুন" else "Remove", fontSize = 11.sp)
                    }

                    Spacer(modifier = Modifier.width(8.dp))
                }

                Button(
                    onClick = onEditRole,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isUnrecognized) StoreGreenProfit else StorePrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(
                        imageVector = if (isUnrecognized) Icons.Default.CheckCircle else Icons.Default.ManageAccounts,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isUnrecognized)
                            (if (isBengali) "স্টাফ হিসেবে অনুমোদন দিন" else "Grant Staff Access")
                        else if (isPermanent)
                            (if (isBengali) "পারমিশন দেখুন" else "View Permissions")
                        else
                            (if (isBengali) "রোল পরিবর্তন করুন" else "Manage Role"),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun EditUserRoleDialog(
    userRole: FirestoreUserRole,
    viewModel: StoreViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isBengali = LanguageManager.isBengali
    val isPermanent = userRole.isPermanentAdmin

    var selectedRole by remember { mutableStateOf(userRole.role) }
    var canMakeSales by remember { mutableStateOf(userRole.canMakeSales) }
    var canViewCostPrice by remember { mutableStateOf(userRole.canViewCostPrice) }
    var canManageInventory by remember { mutableStateOf(userRole.canManageInventory) }
    var canViewKhata by remember { mutableStateOf(userRole.canViewKhata) }
    var canManageExpenses by remember { mutableStateOf(userRole.canManageExpenses) }
    var canViewReports by remember { mutableStateOf(userRole.canViewReports) }
    var canAccessSettings by remember { mutableStateOf(userRole.canAccessSettings) }
    var canGiveDiscount by remember { mutableStateOf(userRole.canGiveDiscount) }
    var canDeleteSales by remember { mutableStateOf(userRole.canDeleteSales) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = if (isBengali) "রোল ও পারমিশন সম্পাদনা" else "Configure Role & Permissions",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    text = userRole.email ?: userRole.displayName ?: userRole.uid,
                    fontSize = 12.sp,
                    color = TextMuted
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (isPermanent) {
                    Surface(
                        color = StoreGold.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "👑 Permanent Store Owner: All administrative and report privileges are permanently granted.",
                            color = TextDark,
                            fontSize = 11.5.sp,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                } else {
                    Text(
                        text = if (isBengali) "ভূমিকা নির্বাচন করুন:" else "Select User Role:",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextDark
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = selectedRole == AppRole.EMPLOYEE.roleName,
                            onClick = {
                                selectedRole = AppRole.EMPLOYEE.roleName
                                canMakeSales = true
                                canGiveDiscount = true
                                canViewCostPrice = false
                                canViewReports = false
                                canAccessSettings = false
                            },
                            label = { Text("Cashier", fontSize = 11.sp) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = selectedRole == AppRole.STORE_MANAGER.roleName,
                            onClick = {
                                selectedRole = AppRole.STORE_MANAGER.roleName
                                canMakeSales = true
                                canGiveDiscount = true
                                canManageInventory = true
                                canViewKhata = true
                                canManageExpenses = true
                                canViewCostPrice = true
                                canViewReports = false
                                canAccessSettings = false
                            },
                            label = { Text("Manager", fontSize = 11.sp) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = selectedRole == AppRole.ADMIN.roleName,
                            onClick = {
                                selectedRole = AppRole.ADMIN.roleName
                                canMakeSales = true
                                canGiveDiscount = true
                                canManageInventory = true
                                canViewKhata = true
                                canManageExpenses = true
                                canViewCostPrice = true
                                canViewReports = true
                                canAccessSettings = true
                                canDeleteSales = true
                            },
                            label = { Text("Admin", fontSize = 11.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = Color(0xFFF1F5F9), thickness = 1.dp)
                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = if (isBengali) "নির্দিষ্ট পারমিশনসমূহ:" else "Granular Permissions:",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextDark
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    PermissionCheckbox("POS Billing & Sales", canMakeSales) { canMakeSales = it }
                    PermissionCheckbox("Apply Custom Discounts", canGiveDiscount) { canGiveDiscount = it }
                    PermissionCheckbox("View Product Cost Price", canViewCostPrice) { canViewCostPrice = it }
                    PermissionCheckbox("Manage Stock / Inventory", canManageInventory) { canManageInventory = it }
                    PermissionCheckbox("Customer Credit / Khata", canViewKhata) { canViewKhata = it }
                    PermissionCheckbox("Manage Store Expenses", canManageExpenses) { canManageExpenses = it }
                    PermissionCheckbox("View P&L Profit Reports", canViewReports) { canViewReports = it }
                    PermissionCheckbox("Access Store Settings", canAccessSettings) { canAccessSettings = it }
                    PermissionCheckbox("Delete / Refund Sales", canDeleteSales) { canDeleteSales = it }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isPermanent) {
                        onDismiss()
                        return@Button
                    }
                    val updated = userRole.copy(
                        role = selectedRole,
                        canMakeSales = canMakeSales,
                        canGiveDiscount = canGiveDiscount,
                        canViewCostPrice = canViewCostPrice,
                        canManageInventory = canManageInventory,
                        canViewKhata = canViewKhata,
                        canManageExpenses = canManageExpenses,
                        canViewReports = canViewReports,
                        canAccessSettings = canAccessSettings,
                        canDeleteSales = canDeleteSales,
                        updatedAt = System.currentTimeMillis()
                    )
                    viewModel.updateUserRoleInFirestore(updated) { success, msg ->
                        Toast.makeText(context, msg ?: if (success) "Saved" else "Failed", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = StorePrimary)
            ) {
                Text(if (isPermanent) "Close" else "Save Changes", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            if (!isPermanent) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

@Composable
private fun PermissionCheckbox(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.size(28.dp),
            colors = CheckboxDefaults.colors(checkedColor = StorePrimary)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = title, fontSize = 12.sp, color = TextDark)
    }
}

@Composable
fun PreassignStaffDialog(
    viewModel: StoreViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var emailInput by remember { mutableStateOf("") }
    var nameInput by remember { mutableStateOf("") }
    var selectedRole by remember { mutableStateOf(AppRole.EMPLOYEE.roleName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Pre-Authorize Staff Google Account",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Add an employee's Google email beforehand. When they sign in on their Android phone, their permissions will be automatically configured.",
                    fontSize = 11.5.sp,
                    color = TextMuted
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = emailInput,
                    onValueChange = { emailInput = it },
                    label = { Text("Staff Google Email *", fontSize = 12.sp) },
                    placeholder = { Text("staff@gmail.com", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("Staff Name (Optional)", fontSize = 12.sp) },
                    placeholder = { Text("e.g. Rahul Sharma", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(text = "Assign Role:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = selectedRole == AppRole.EMPLOYEE.roleName,
                        onClick = { selectedRole = AppRole.EMPLOYEE.roleName },
                        label = { Text("Cashier", fontSize = 11.sp) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = selectedRole == AppRole.STORE_MANAGER.roleName,
                        onClick = { selectedRole = AppRole.STORE_MANAGER.roleName },
                        label = { Text("Manager", fontSize = 11.sp) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = selectedRole == AppRole.ADMIN.roleName,
                        onClick = { selectedRole = AppRole.ADMIN.roleName },
                        label = { Text("Admin", fontSize = 11.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanEmail = emailInput.trim().lowercase()
                    if (cleanEmail.isBlank() || !cleanEmail.contains("@")) {
                        Toast.makeText(context, "Please enter a valid Google email address.", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    val role = when (selectedRole) {
                        AppRole.ADMIN.roleName -> FirestoreUserRole.createAdmin(
                            uid = "preassigned_$cleanEmail",
                            email = cleanEmail,
                            displayName = nameInput.trim().ifBlank { cleanEmail.substringBefore("@") }
                        )
                        AppRole.STORE_MANAGER.roleName -> FirestoreUserRole.createManager(
                            uid = "preassigned_$cleanEmail",
                            email = cleanEmail,
                            displayName = nameInput.trim().ifBlank { cleanEmail.substringBefore("@") }
                        )
                        else -> FirestoreUserRole.createEmployee(
                            uid = "preassigned_$cleanEmail",
                            email = cleanEmail,
                            displayName = nameInput.trim().ifBlank { cleanEmail.substringBefore("@") }
                        )
                    }

                    viewModel.savePreassignedRole(cleanEmail, role) { success, msg ->
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        if (success) onDismiss()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = StorePrimary)
            ) {
                Text("Pre-Authorize Account", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
