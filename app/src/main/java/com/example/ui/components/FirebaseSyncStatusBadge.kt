package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.ui.theme.*
import com.example.utils.LanguageManager
import com.example.utils.NetworkMonitor
import com.example.viewmodel.StoreViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FirebaseSyncStatusBadge(
    viewModel: StoreViewModel,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val isOnline = NetworkMonitor.isOnline
    val isSyncing by viewModel.isSyncingAllData.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val currentRole by viewModel.currentFirestoreUserRole.collectAsState()
    var showStatusDialog by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isSyncing) 600 else 1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val badgeBg = when {
        !isOnline -> Color(0xFFFFFBEB) // Warm amber light
        isSyncing -> Color(0xFFFEF3C7) // Golden yellow light
        else -> Color(0xFFECFDF5)      // Mint green light
    }

    val badgeBorder = when {
        !isOnline -> Color(0xFFD97706)
        isSyncing -> Color(0xFFC9971C) // Brand gold
        else -> Color(0xFF2E7D32)      // Deep green
    }

    val badgeText = when {
        !isOnline -> Color(0xFFB45309)
        isSyncing -> Color(0xFF854D0E)
        else -> Color(0xFF2E7D32)
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = badgeBg,
        border = BorderStroke(1.dp, badgeBorder.copy(alpha = 0.7f)),
        modifier = modifier
            .heightIn(min = if (compact) 32.dp else 38.dp)
            .clickable { showStatusDialog = true }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = if (compact) 8.dp else 10.dp, vertical = if (compact) 4.dp else 6.dp)
        ) {
            // Pulsing status dot
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(if (isSyncing || isOnline) badgeBorder.copy(alpha = pulseAlpha) else badgeBorder)
            )

            Spacer(modifier = Modifier.width(6.dp))

            Icon(
                imageVector = when {
                    !isOnline -> Icons.Default.CloudOff
                    isSyncing -> Icons.Default.Sync
                    else -> Icons.Default.CloudDone
                },
                contentDescription = if (isOnline) "Connected to Firebase" else "Offline Mode",
                tint = badgeText,
                modifier = Modifier.size(16.dp)
            )

            if (!compact) {
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = when {
                        !isOnline -> LanguageManager.getString("Offline", "অফলাইন")
                        isSyncing -> LanguageManager.getString("Syncing…", "সিঙ্ক হচ্ছে…")
                        else -> LanguageManager.getString("Synced", "সিঙ্কড")
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = badgeText
                )
            }
        }
    }

    if (showStatusDialog) {
        FirebaseStatusDetailDialog(
            viewModel = viewModel,
            isOnline = isOnline,
            currentUserEmail = currentUser?.email,
            userRole = currentRole?.role ?: "Store Staff",
            onDismiss = { showStatusDialog = false }
        )
    }
}

@Composable
fun FirebaseStatusDetailDialog(
    viewModel: StoreViewModel,
    isOnline: Boolean,
    currentUserEmail: String?,
    userRole: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isSyncing by viewModel.isSyncingAllData.collectAsState()
    val lastSyncSummary by viewModel.lastSyncSummary.collectAsState()
    val lastSyncTimestamp by viewModel.lastSyncTimestamp.collectAsState()

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Icon
                Surface(
                    shape = CircleShape,
                    color = if (isOnline) Color(0xFFD1FAE5) else Color(0xFFFEF3C7),
                    modifier = Modifier.size(52.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isOnline) Icons.Default.CloudDone else Icons.Default.CloudOff,
                            contentDescription = null,
                            tint = if (isOnline) Color(0xFF059669) else Color(0xFFD97706),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = if (isOnline) {
                        LanguageManager.getString("Firebase Cloud Connected", "ফায়ারবেস ক্লাউড সংযুক্ত")
                    } else {
                        LanguageManager.getString("Offline Local Mode", "অফলাইন লোকাল মোড")
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isOnline) Color(0xFF059669) else Color(0xFFD97706)
                )

                Text(
                    text = if (isOnline) {
                        LanguageManager.getString(
                            "Real-time bidirectional synchronization with Firebase Firestore is active for all products, sales, khata & staff payroll.",
                            "পণ্য, বিক্রয়, খাতা ও কর্মচারীদের বেতনের সমস্ত ডাটা ফায়ারবেস ক্লাউডের সাথে রিয়েল-টাইমে সক্রিয় রয়েছে।"
                        )
                    } else {
                        LanguageManager.getString(
                            "Operating seamlessly in local offline mode (Room SQLite). All changes will automatically sync to Firebase once internet connects.",
                            "লোকাল অফলাইন মোডে নির্বিঘ্নে চলছে। ইন্টারনেট সংযোগ পেলে সব ডাটা স্বয়ংক্রিয়ভাবে ক্লাউডে সিঙ্ক হবে।"
                        )
                    },
                    fontSize = 11.sp,
                    color = TextMuted,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                    lineHeight = 15.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                HorizontalDivider(color = TextMuted.copy(alpha = 0.15f))
                Spacer(modifier = Modifier.height(10.dp))

                // Diagnostic Details List
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Internet Status:", fontSize = 11.sp, color = TextMuted)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (isOnline) Color(0xFF10B981) else Color(0xFFEF4444))
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isOnline) "Active (Online)" else "No Connection",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isOnline) Color(0xFF059669) else Color(0xFFDC2626)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Firebase Account:", fontSize = 11.sp, color = TextMuted)
                        Text(
                            text = currentUserEmail ?: "Guest / Local Admin",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Security Role:", fontSize = 11.sp, color = TextMuted)
                        Text(
                            text = userRole,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = StorePrimary
                        )
                    }

                    val dateFormat = remember { SimpleDateFormat("hh:mm:ss a, dd MMM", Locale.getDefault()) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Last Full Sync:", fontSize = 11.sp, color = TextMuted)
                        Text(
                            text = dateFormat.format(Date(lastSyncTimestamp)),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    lastSyncSummary?.let { summary ->
                        if (summary.success) {
                            Text(
                                text = "Synced: ${summary.productsCount} products, ${summary.salesCount} sales, ${summary.customersCount} customers, ${summary.employeesCount} staff",
                                fontSize = 10.sp,
                                color = Color(0xFF059669),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Close", fontSize = 11.sp)
                    }

                    Button(
                        onClick = {
                            viewModel.syncAllDataNow { success, message ->
                                NetworkMonitor.markSynced()
                                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = !isSyncing,
                        modifier = Modifier.weight(1.3f),
                        colors = ButtonDefaults.buttonColors(containerColor = StorePrimary),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Syncing...", fontSize = 11.sp)
                        } else {
                            Icon(
                                Icons.Default.Sync,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Sync All Now", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}
