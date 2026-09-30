package com.example.ui.screens.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.auth.AuthUser
import com.example.data.firestore.FirestoreUserRole
import com.example.ui.theme.*
import com.example.utils.LanguageManager
import com.example.viewmodel.StoreViewModel

@Composable
fun CloudAndBackupSection(
    context: Context,
    viewModel: StoreViewModel,
    currentUser: AuthUser?,
    currentFirestoreUserRole: FirestoreUserRole?,
    lastSyncTimestamp: Long,
    isSyncingAllData: Boolean,
    isBackfillingPublicLedger: Boolean,
    driveAccountEmail: String?,
    lastDriveBackupTimestamp: Long,
    isDriveLoading: Boolean,
    driveStatusMessage: String?,
    onOpenAuthDialog: () -> Unit,
    onManageFirestoreRolesClicked: () -> Unit,
    onAllAppUsersClicked: () -> Unit,
    onConfirmBackfillClicked: () -> Unit,
    onOpenDriveBackupDialog: () -> Unit,
    onOpenRestoreDriveDialog: () -> Unit,
    onOpenEditDriveAccountDialog: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Firebase Cloud & User Authentication Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            elevation = CardDefaults.cardElevation(2.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (currentUser != null) StoreGreenProfit.copy(alpha = 0.15f) else StorePrimary.copy(alpha = 0.12f),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (currentUser != null) Icons.Default.AccountCircle else Icons.Default.CloudSync,
                                    contentDescription = null,
                                    tint = if (currentUser != null) StoreGreenProfit else StorePrimary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (currentUser != null) (currentUser.displayName ?: "Signed In User") else "Cloud & User Auth",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = TextDark
                            )
                            Text(
                                text = currentUser?.email ?: "Google Sign-in • Firestore Multi-device Sync",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    if (currentUser != null) {
                        OutlinedButton(
                            onClick = {
                                viewModel.signOut(context)
                                Toast.makeText(context, "Signed out", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(Icons.Default.Logout, contentDescription = "Sign Out", modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Logout", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = onOpenAuthDialog,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = StorePrimary),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(Icons.Default.Login, contentDescription = null, modifier = Modifier.size(13.dp), tint = Color.White)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Sign In", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (currentUser == null) {
                        Button(
                            onClick = {
                                viewModel.signInWithGoogle(context) { success, msg ->
                                    if (success) {
                                        Toast.makeText(context, msg ?: "Signed in!", Toast.LENGTH_SHORT).show()
                                    } else if (!msg.isNullOrBlank() && !msg.contains("cancel", ignoreCase = true) && !msg.contains("dismiss", ignoreCase = true)) {
                                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                        onOpenAuthDialog()
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = StorePrimary)
                        ) {
                            Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Google Sign-In", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = onOpenAuthDialog,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Email Sign-In", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        Icon(Icons.Default.CloudDone, contentDescription = null, tint = StoreGreenProfit, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Role: ${currentFirestoreUserRole?.role ?: "ADMIN"} • UID: ${currentUser.uid.take(6)}...",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextDark
                                        )
                                    }

                                    Surface(
                                        color = if (currentFirestoreUserRole?.isAdmin == true) StoreGold.copy(alpha = 0.2f) else StorePrimary.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = if (currentFirestoreUserRole?.isAdmin == true) "👑 ADMIN" else "👤 EMPLOYEE",
                                            color = if (currentFirestoreUserRole?.isAdmin == true) StoreGold else StorePrimary,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Backup Password Status and Action Card
                            val isPasswordActive = currentUser.hasPasswordProvider
                            Surface(
                                color = if (isPasswordActive) StoreGreenProfit.copy(alpha = 0.08f) else StoreGold.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, if (isPasswordActive) StoreGreenProfit.copy(alpha = 0.35f) else StoreGold.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        Icon(
                                            imageVector = if (isPasswordActive) Icons.Default.Lock else Icons.Default.Security,
                                            contentDescription = null,
                                            tint = if (isPasswordActive) StoreGreenProfit else Color(0xFFD97706),
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = if (isPasswordActive) {
                                                    if (LanguageManager.isBengali) "ইমেইল ও পাসওয়ার্ড লগইন: সক্রিয়" else "Email + Password: Set & Active"
                                                } else {
                                                    if (LanguageManager.isBengali) "ব্যাকআপ পাসওয়ার্ড: সেট নেই (প্রস্তাবিত)" else "Backup Password: Not Set"
                                                },
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isPasswordActive) StoreGreenProfit else Color(0xFF92400E)
                                            )
                                            Text(
                                                text = if (isPasswordActive) {
                                                    if (LanguageManager.isBengali) "Google বা ইমেইল+পাসওয়ার্ড উভয় মাধ্যমেই লগইন সম্ভব" else "Log in with Google or Email+Password anytime"
                                                } else {
                                                    if (LanguageManager.isBengali) "Google অনুপলব্ধ থাকলে সরাসরি লগইন করতে পাসওয়ার্ড দিন" else "Set password for emergency device login"
                                                },
                                                fontSize = 10.sp,
                                                color = TextMuted,
                                                lineHeight = 12.sp
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(6.dp))

                                    Button(
                                        onClick = { viewModel.openSetBackupPasswordDialog(isFirstTimePrompt = false) },
                                        shape = RoundedCornerShape(6.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isPasswordActive) StorePrimary else Color(0xFFD97706)
                                        ),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.height(28.dp)
                                    ) {
                                        Text(
                                            text = if (isPasswordActive) {
                                                if (LanguageManager.isBengali) "পরিবর্তন" else "Change"
                                            } else {
                                                if (LanguageManager.isBengali) "সেট করুন" else "Set Password"
                                            },
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedButton(
                                onClick = onManageFirestoreRolesClicked,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, StorePrimary.copy(alpha = 0.6f))
                            ) {
                                Icon(Icons.Default.AdminPanelSettings, contentDescription = null, tint = StorePrimary, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Admin Privileges & Lockout Recovery", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = StorePrimary)
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            OutlinedButton(
                                onClick = onAllAppUsersClicked,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, Color(0xFF2563EB).copy(alpha = 0.7f))
                            ) {
                                Icon(Icons.Default.Group, contentDescription = null, tint = Color(0xFF2563EB), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("All App Users & Sign-In Audit", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2563EB))
                            }
                        }
                    }
                }
            }
        }

        // Firestore Cloud Synchronization Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            elevation = CardDefaults.cardElevation(2.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Surface(
                            shape = CircleShape,
                            color = if (currentUser != null) StoreGreenProfit.copy(alpha = 0.15f) else StorePrimary.copy(alpha = 0.12f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.CloudSync,
                                    contentDescription = null,
                                    tint = if (currentUser != null) StoreGreenProfit else StorePrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = if (LanguageManager.isBengali) "ক্লাউড সিঙ্ক্রোনাইজেশন (Cloud Sync)" else "Firestore Cloud Synchronization",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall,
                                color = TextDark
                            )
                            Text(
                                text = if (currentUser != null) {
                                    if (LanguageManager.isBengali) "🟢 লাইভ রিয়েল-টাইম সিঙ্ক সক্রিয়" else "🟢 Live Real-Time Multi-Device Sync Active"
                                } else {
                                    if (LanguageManager.isBengali) "⚪ অফলাইন / লোকাল মোড (সিঙ্ক চালু করতে লগইন করুন)" else "⚪ Offline / Local Mode (Sign in above to sync)"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (currentUser != null) StoreGreenProfit else TextMuted
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = if (LanguageManager.isBengali) {
                        "সমস্ত ডাটা (পণ্য, স্টক, বিক্রয়, খাতা লেজার, স্টাফ) ফায়ারস্টোর লাইভ স্ন্যাপশট লিসেনারের মাধ্যমে সব ডিভাইসে তৎক্ষণাৎ স্বয়ংক্রিয়ভাবে সিঙ্ক হয়।"
                    } else {
                        "All inventory, sales, khata ledgers, and staff data synchronize automatically in real-time across all connected devices via Firestore snapshot listeners."
                    },
                    fontSize = 11.sp,
                    color = TextMuted,
                    lineHeight = 15.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                val syncTimeFormat = java.text.SimpleDateFormat("hh:mm:ss a, dd MMM yyyy", java.util.Locale.getDefault())
                val formattedLastSync = if (lastSyncTimestamp > 0) syncTimeFormat.format(java.util.Date(lastSyncTimestamp)) else "Real-time listeners active"

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = if (LanguageManager.isBengali) "সর্বশেষ রিকনসিলিয়েশন:" else "Last Full Reconciliation:",
                                fontSize = 10.sp,
                                color = TextMuted,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = formattedLastSync,
                                fontSize = 11.sp,
                                color = TextDark,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        if (isSyncingAllData) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = StorePrimary)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (LanguageManager.isBengali) "সিঙ্ক হচ্ছে..." else "Syncing...",
                                    fontSize = 11.sp,
                                    color = StorePrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = {
                        viewModel.syncAllDataNow { _, msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSyncingAllData,
                    colors = ButtonDefaults.buttonColors(containerColor = StorePrimary),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (LanguageManager.isBengali) "ফোর্স রিসিঙ্ক (Force Resync)" else "Force Resync",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Public Khata History Backfill Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            elevation = CardDefaults.cardElevation(2.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Surface(
                            shape = CircleShape,
                            color = StoreGold.copy(alpha = 0.15f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.ReceiptLong,
                                    contentDescription = null,
                                    tint = StoreGold,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = if (LanguageManager.isBengali) "পাবলিক খাতা হিস্ট্রি ব্যাকফিল" else "Backfill Public Khata History",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall,
                                color = TextDark
                            )
                            Text(
                                text = if (LanguageManager.isBengali) "এককালীন অ্যাডমিন মাইগ্রেশন" else "One-Time Admin Migration",
                                style = MaterialTheme.typography.bodySmall,
                                color = StoreGold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = if (LanguageManager.isBengali) {
                        "ফায়ারস্টোরের মূল ledger_entries থেকে সমস্ত গ্রাহকের অতীতের হিসাব customers/{customerId}/public_ledger সাব-কালেকশনে কপি করে, যাতে অনলাইন খাতা লিঙ্কে পূর্ণ লেনদেন হিস্ট্রি দেখা যায়।"
                    } else {
                        "Copies all past customer ledger entries into each customer's individual public_ledger subcollection in safe batches. Original ledger entries remain untouched."
                    },
                    fontSize = 11.sp,
                    color = TextMuted,
                    lineHeight = 15.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onConfirmBackfillClicked,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isBackfillingPublicLedger,
                    colors = ButtonDefaults.buttonColors(containerColor = StoreGold),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    if (isBackfillingPublicLedger) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (LanguageManager.isBengali) "ব্যাকফিল হচ্ছে..." else "Backfilling History...",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    } else {
                        Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (LanguageManager.isBengali) "খাতা হিস্ট্রি ব্যাকফিল শুরু করুন" else "Backfill Public Khata History",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }

        // Google Drive Cloud Backup & Restore Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            elevation = CardDefaults.cardElevation(2.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Surface(
                            shape = CircleShape,
                            color = StoreGreenProfit.copy(alpha = 0.12f),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.CloudSync,
                                    contentDescription = null,
                                    tint = StoreGreenProfit,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = if (LanguageManager.isBengali) "গুগল ড্রাইভ ক্লাউড ব্যাকআপ" else "Google Drive Cloud Backup",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall,
                                color = TextDark
                            )
                            Text(
                                text = if (LanguageManager.isBengali) "নিরাপদ পৃথক গুগল ড্রাইভ ব্যাকআপ ও রিস্টোর" else "Dedicated off-site backups in Google Drive",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Account & Destination Folder Info
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(
                                    Icons.Default.AccountCircle,
                                    contentDescription = null,
                                    tint = StorePrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = driveAccountEmail ?: currentUser?.email ?: "shuvajitsow384@gmail.com",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextDark,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            TextButton(
                                onClick = onOpenEditDriveAccountDialog,
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                modifier = Modifier.height(24.dp)
                            ) {
                                Text("Change", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = StorePrimary)
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                tint = StoreSaffronAccent,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Drive Folder: Amar Dukan Backups",
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                        }

                        if (lastDriveBackupTimestamp > 0L) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Schedule,
                                    contentDescription = null,
                                    tint = StoreGreenProfit,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                val dateStr = java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", java.util.Locale.getDefault()).format(java.util.Date(lastDriveBackupTimestamp))
                                Text(
                                    text = "Last backed up: $dateStr",
                                    fontSize = 11.sp,
                                    color = TextDark,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                if (isDriveLoading) {
                    Spacer(modifier = Modifier.height(10.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = StoreGreenProfit
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = driveStatusMessage?.ifBlank { "Processing Google Drive request..." } ?: "Processing Google Drive request...",
                        fontSize = 11.sp,
                        color = StoreGreenProfit,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onOpenDriveBackupDialog,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = StoreRedPrimary),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        enabled = !isDriveLoading
                    ) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (LanguageManager.isBengali) "ব্যাকআপ নিন" else "Backup Now",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            viewModel.fetchGoogleDriveBackups()
                            onOpenRestoreDriveDialog()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.5.dp, StorePrimary),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        enabled = !isDriveLoading
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp), tint = StorePrimary)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (LanguageManager.isBengali) "রিস্টোর করুন" else "Restore Backup",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = StorePrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
