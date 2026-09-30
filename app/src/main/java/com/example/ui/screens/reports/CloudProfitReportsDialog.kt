package com.example.ui.screens.reports

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.firestore.FirestoreProfitReport
import com.example.ui.theme.*
import com.example.viewmodel.StoreViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudProfitReportsDialog(
    viewModel: StoreViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val cloudReports by viewModel.cloudProfitReports.collectAsState()
    val errorMessage by viewModel.cloudReportErrorMessage.collectAsState()
    val currentUserRole by viewModel.currentFirestoreUserRole.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    var isLoading by remember { mutableStateOf(false) }

    val fetchReports = {
        isLoading = true
        viewModel.fetchCloudProfitReports { _, err ->
            isLoading = false
            if (err != null && currentUser != null) {
                Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(currentUser) {
        fetchReports()
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Dialog Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = StorePrimary.copy(alpha = 0.15f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.CloudSync, contentDescription = null, tint = StorePrimary, modifier = Modifier.size(20.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Firestore Profit Reports",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextDark
                            )
                            Text(
                                text = if (currentUser != null) "Logged in as: ${currentUser?.email ?: currentUser?.displayName}" else "RBAC Security: Admin-Only Collection",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Error or Restricted Banner
                if (currentUser == null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = StoreRedPrimary.copy(alpha = 0.08f)),
                        border = BorderStroke(1.dp, StoreRedPrimary.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Lock, contentDescription = null, tint = StoreRedPrimary, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Sign-In Required for Cloud Sync",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = StoreRedPrimary
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Your offline store calculations are working 100% locally. To view or sync cloud backups with Firestore, sign in to your Store Admin account in Settings.",
                                fontSize = 11.sp,
                                color = TextDark,
                                lineHeight = 15.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                } else if (errorMessage != null) {
                    val isPermissionDenied = errorMessage?.contains("PERMISSION_DENIED", ignoreCase = true) == true
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = StoreRedPrimary.copy(alpha = 0.08f)),
                        border = BorderStroke(1.dp, StoreRedPrimary.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Lock, contentDescription = null, tint = StoreRedPrimary, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isPermissionDenied) "Firestore Security Rules Setup" else "Access Restricted by Firestore RBAC",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = StoreRedPrimary
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isPermissionDenied)
                                    "Firestore cloud database requires published rules in Firebase Console. Go to Firebase Console -> Firestore Database -> Rules tab, and enable access for authenticated users."
                                else
                                    errorMessage ?: "Permission denied by cloud security rules.",
                                fontSize = 11.sp,
                                color = TextDark,
                                lineHeight = 15.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = StorePrimary)
                    }
                } else if (cloudReports.isEmpty() && errorMessage == null) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.CloudQueue, contentDescription = null, tint = TextMuted, modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("No profit reports synced to Firestore yet.", color = TextMuted, fontSize = 13.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Tap 'Sync Cloud' on the P&L screen to publish a snapshot.", color = TextMuted, fontSize = 11.sp)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(cloudReports, key = { it.reportId.ifBlank { "${it.storeId}_${it.generatedAt}" } }, contentType = { "CLOUD_PROFIT_REPORT_CARD" }) { rep ->
                            CloudReportCard(rep)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { fetchReports() },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Refresh")
                    }

                    Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Done")
                    }
                }
            }
        }
    }
}

@Composable
fun CloudReportCard(report: FirestoreProfitReport) {
    val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
    val dateStr = sdf.format(Date(report.generatedAt))

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = StorePrimary.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = report.period,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = StorePrimary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "By ${report.generatedByName}",
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                }

                Text(
                    text = dateStr,
                    fontSize = 10.sp,
                    color = TextMuted
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Revenue", fontSize = 10.sp, color = TextMuted)
                    Text("₹%.2f".format(report.totalRevenue), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextDark)
                }
                Column {
                    Text("COGS", fontSize = 10.sp, color = TextMuted)
                    Text("₹%.2f".format(report.totalCostOfGoods), fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = TextDark)
                }
                Column {
                    Text("Expenses", fontSize = 10.sp, color = TextMuted)
                    Text("₹%.2f".format(report.totalExpenses), fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = StoreRedPrimary)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Net Profit", fontSize = 10.sp, color = TextMuted)
                    Text(
                        text = "₹%.2f".format(report.netProfit),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = if (report.netProfit >= 0) StoreGreenProfit else StoreRedPrimary
                    )
                }
            }
        }
    }
}
