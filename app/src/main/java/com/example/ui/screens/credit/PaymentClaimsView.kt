package com.example.ui.screens.credit

import android.graphics.BitmapFactory
import android.util.Base64
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.example.data.local.entities.Customer
import com.example.data.local.entities.PaymentClaim
import com.example.ui.components.PartyProfileAvatar
import com.example.ui.components.ZoomablePaymentScreenshotDialog
import com.example.ui.theme.*
import com.example.utils.LanguageManager
import com.example.viewmodel.StoreViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

@Composable
fun PaymentClaimsView(
    viewModel: StoreViewModel,
    modifier: Modifier = Modifier,
    onNavigateToCustomer: ((Customer) -> Unit)? = null
) {
    val context = LocalContext.current
    val allClaims by viewModel.allPaymentClaims.collectAsState()
    val pendingClaims by viewModel.pendingPaymentClaims.collectAsState()
    val customers by viewModel.allCustomers.collectAsState()
    val allLedgerEntries by viewModel.allLedgerEntries.collectAsState()

    var showRejectDialogForClaim by remember { mutableStateOf<PaymentClaim?>(null) }
    var showConfirmDialogForClaim by remember { mutableStateOf<PaymentClaim?>(null) }
    var rejectionReason by remember { mutableStateOf("") }
    var confirmCustomNote by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }

    var isHistoryExpanded by remember { mutableStateOf(false) }
    var historyFilter by remember { mutableStateOf("ALL") } // ALL, CONFIRMED, REJECTED, EXPIRED

    val customerMap = remember(customers) { customers.associateBy { it.id } }

    val historyClaims = remember(allClaims, historyFilter) {
        val nonPending = allClaims.filter { it.status != PaymentClaim.STATUS_PENDING }
            .sortedByDescending { it.timestamp }
        when (historyFilter) {
            "CONFIRMED" -> nonPending.filter { it.status == PaymentClaim.STATUS_CONFIRMED }
            "REJECTED" -> nonPending.filter { it.status == PaymentClaim.STATUS_REJECTED }
            "EXPIRED" -> nonPending.filter { it.status == PaymentClaim.STATUS_EXPIRED }
            else -> nonPending
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
        // Pending Claims Header & Count
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (pendingClaims.isNotEmpty()) Color(0xFFFFFBEB) else CardBackground
                ),
                border = BorderStroke(
                    1.dp,
                    if (pendingClaims.isNotEmpty()) Color(0xFFF59E0B).copy(alpha = 0.4f) else TextMuted.copy(alpha = 0.15f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (pendingClaims.isNotEmpty()) Color(0xFFF59E0B) else StoreGreenProfit.copy(alpha = 0.15f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (pendingClaims.isNotEmpty()) Icons.Default.PendingActions else Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = if (pendingClaims.isNotEmpty()) Color.White else StoreGreenProfit,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Column {
                            Text(
                                text = LanguageManager.getString("Self-Reported UPI Claims", "গ্রাহক রিপোর্ট করা UPI পেমেন্ট"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextDark
                            )
                            Text(
                                text = if (pendingClaims.isNotEmpty()) {
                                    LanguageManager.getString(
                                        "${pendingClaims.size} payment(s) awaiting your verification",
                                        "${pendingClaims.size} টি পেমেন্ট যাচাইয়ের অপেক্ষায়"
                                    )
                                } else {
                                    LanguageManager.getString("All payments verified and cleared", "কোনো পেন্ডিং পেমেন্ট ক্লেইম নেই")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                        }
                    }

                    if (pendingClaims.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFD97706)
                        ) {
                            Text(
                                text = "${pendingClaims.size} " + LanguageManager.getString("Pending", "পেন্ডিং"),
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        // Section Title for Pending
        if (pendingClaims.isNotEmpty()) {
            item {
                Text(
                    text = LanguageManager.getString("Action Required", "যাচাই ও কনফার্ম করুন"),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF92400E),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            items(pendingClaims, key = { it.id }, contentType = { "PENDING_CLAIM" }) { claim ->
                val currentCustomer = customerMap[claim.customerId]
                PendingClaimCard(
                    claim = claim,
                    currentCustomer = currentCustomer,
                    onConfirm = {
                        confirmCustomNote = if (!claim.note.isNullOrBlank()) "Self-reported UPI: ${claim.note}" else "Self-reported UPI Khata payment"
                        showConfirmDialogForClaim = claim
                    },
                    onReject = {
                        rejectionReason = ""
                        showRejectDialogForClaim = claim
                    },
                    onCustomerClick = {
                        if (currentCustomer != null && onNavigateToCustomer != null) {
                            onNavigateToCustomer(currentCustomer)
                        }
                    }
                )
            }
        } else {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                    border = BorderStroke(1.dp, TextMuted.copy(alpha = 0.1f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = StoreGreenProfit.copy(alpha = 0.1f),
                            modifier = Modifier.size(54.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Verified, contentDescription = null, tint = StoreGreenProfit, modifier = Modifier.size(28.dp))
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = LanguageManager.getString("No Pending Payment Claims", "কোনো পেন্ডিং ক্লেইম নেই"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextDark
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = LanguageManager.getString(
                                "When customers tap 'I\\'ve Paid' on their public Khata link, claims will appear here for 1-tap confirmation.",
                                "গ্রাহক তাদের খাতা লিঙ্কে 'আমি পরিশোধ করেছি' বাটনে ক্লিক করলে তা এখানে আসবে।"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }

        // Collapsible History Section
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isHistoryExpanded = !isHistoryExpanded },
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                border = BorderStroke(1.dp, TextMuted.copy(alpha = 0.15f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.History, contentDescription = null, tint = TextMuted, modifier = Modifier.size(20.dp))
                        Text(
                            text = LanguageManager.getString("Claims History & Audit", "পূর্ববর্তী ক্লেইম হিস্ট্রি") + " (${historyClaims.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextDark
                        )
                    }
                    Icon(
                        imageVector = if (isHistoryExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = TextMuted
                    )
                }
            }
        }

        if (isHistoryExpanded) {
            // Filter chips for history
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HistoryFilterChip("ALL", LanguageManager.getString("All", "সকল"), historyFilter) { historyFilter = it }
                    HistoryFilterChip("CONFIRMED", LanguageManager.getString("Confirmed", "গৃহীত"), historyFilter) { historyFilter = it }
                    HistoryFilterChip("REJECTED", LanguageManager.getString("Rejected", "প্রত্যাখ্যাত"), historyFilter) { historyFilter = it }
                    HistoryFilterChip("EXPIRED", LanguageManager.getString("Expired", "মেয়াদোত্তীর্ণ"), historyFilter) { historyFilter = it }
                }
            }

            if (historyClaims.isEmpty()) {
                item {
                    Text(
                        text = LanguageManager.getString("No history records matching filter.", "কোনো রেকর্ড পাওয়া যায়নি।"),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                items(historyClaims, key = { it.id }, contentType = { "HISTORY_CLAIM" }) { claim ->
                    HistoryClaimCard(claim = claim)
                }
            }
        }
    }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }

    // --- CONFIRM PAYMENT DIALOG ---
    if (showConfirmDialogForClaim != null) {
        val claim = showConfirmDialogForClaim!!
        val currentCust = customerMap[claim.customerId]
        val currentBal = currentCust?.balance ?: claim.dueBalanceAtClaim
        val isAlreadyCredited = remember(claim.id, allLedgerEntries) {
            allLedgerEntries.any { it.referenceId == claim.id && it.type == "PAYMENT_RECEIVED" }
        }
        val isBalChanged = !isAlreadyCredited && Math.abs(currentBal - claim.dueBalanceAtClaim) > 0.01

        AlertDialog(
            onDismissRequest = { if (!isProcessing) showConfirmDialogForClaim = null },
            icon = {
                Surface(shape = CircleShape, color = StoreGreenProfit.copy(alpha = 0.15f), modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StoreGreenProfit, modifier = Modifier.size(24.dp))
                    }
                }
            },
            title = {
                Text(
                    text = if (isAlreadyCredited) {
                        LanguageManager.getString("Finalize Payment Claim", "পেমেন্ট ক্লেইম সম্পন্ন করুন")
                    } else {
                        LanguageManager.getString("Confirm Payment Claim", "পেমেন্ট নিশ্চিত করুন")
                    },
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = if (isAlreadyCredited) {
                            LanguageManager.getString(
                                "This payment of ₹%.2f from %s was already credited to the Khata ledger. Confirming will close this pending claim.".format(claim.claimedAmount, claim.customerName),
                                "%s এর ₹%.2f টাকা ইতিমধ্যে খাতায় জমা হয়েছে। কনফার্ম করলে ক্লেইমটি সম্পন্ন হবে।".format(claim.customerName, claim.claimedAmount)
                            )
                        } else {
                            LanguageManager.getString(
                                "Confirm receipt of ₹%.2f from %s?".format(claim.claimedAmount, claim.customerName),
                                "%s এর কাছ থেকে ₹%.2f প্রাপ্তি নিশ্চিত করতে চান?".format(claim.customerName, claim.claimedAmount)
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextDark
                    )

                    Card(
                        colors = CardDefaults.cardColors(containerColor = SurfaceWarm),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(LanguageManager.getString("Claimed Amount:", "দাবীকৃত টাকা:"), style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                Text("₹%.2f".format(claim.claimedAmount), fontWeight = FontWeight.Bold, color = StoreGreenProfit)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(LanguageManager.getString("Current Due Balance:", "বর্তমান বাকি বকেয়া:"), style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                Text("₹%.2f".format(currentBal), fontWeight = FontWeight.Bold, color = if (currentBal > 0) StoreRedPrimary else StoreGreenProfit)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(LanguageManager.getString("Balance After Confirm:", "জমার পর বাকি থাকবে:"), style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                val afterBal = if (isAlreadyCredited) currentBal else (currentBal - claim.claimedAmount)
                                Text("₹%.2f".format(afterBal), fontWeight = FontWeight.Bold, color = if (afterBal > 0) StoreRedPrimary else StoreGreenProfit)
                            }
                        }
                    }

                    if (isAlreadyCredited) {
                        Surface(
                            color = StoreGreenProfit.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, StoreGreenProfit.copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StoreGreenProfit, modifier = Modifier.size(16.dp))
                                Text(
                                    text = LanguageManager.getString(
                                        "✓ Already credited to customer ledger. Confirming will close and sync claim status without duplicate entry.",
                                        "✓ ইতিমধ্যে খাতায় জমা সম্পন্ন। কনফার্ম করলে ডুপ্লিকেট ছাড়া ক্লেইমটি স্ট্যাটাস আপডেট হবে।"
                                    ),
                                    fontSize = 11.sp,
                                    color = Color(0xFF065F46)
                                )
                            }
                        }
                    } else if (isBalChanged) {
                        Surface(
                            color = Color(0xFFFEF3C7),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(0xFFF59E0B))
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFD97706), modifier = Modifier.size(16.dp))
                                Text(
                                    text = LanguageManager.getString(
                                        "Note: Customer due changed since submission (Was ₹%.2f, now ₹%.2f).".format(claim.dueBalanceAtClaim, currentBal),
                                        "বিজ্ঞপ্তি: সাবমিট করার পর গ্রাহকের বকেয়া পরিবর্তিত হয়েছে (ছিল ₹%.2f, এখন ₹%.2f)।".format(claim.dueBalanceAtClaim, currentBal)
                                    ),
                                    fontSize = 11.sp,
                                    color = Color(0xFF92400E)
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = confirmCustomNote,
                        onValueChange = { confirmCustomNote = it },
                        label = { Text(LanguageManager.getString("Ledger Note (Optional)", "খাতা নোট (ঐচ্ছিক)")) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isProcessing = true
                        viewModel.confirmPaymentClaim(
                            claim = claim,
                            note = confirmCustomNote.takeIf { it.isNotBlank() },
                            onSuccess = {
                                isProcessing = false
                                showConfirmDialogForClaim = null
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        message = LanguageManager.getString("✓ Payment confirmed and credited to Khata!", "✓ পেমেন্ট কনফার্ম হয়েছে ও খাতায় জমা হয়েছে!"),
                                        duration = SnackbarDuration.Short
                                    )
                                }
                            },
                            onError = { err ->
                                isProcessing = false
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        message = "Error: $err",
                                        duration = SnackbarDuration.Long
                                    )
                                }
                            }
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StoreGreenProfit),
                    enabled = !isProcessing
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text(
                            if (isAlreadyCredited) {
                                LanguageManager.getString("Finalize & Clear", "সম্পন্ন করুন")
                            } else {
                                LanguageManager.getString("Confirm & Credit", "কনফার্ম করুন")
                            }
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showConfirmDialogForClaim = null },
                    enabled = !isProcessing
                ) {
                    Text(LanguageManager.getString("Cancel", "বাতিল"))
                }
            }
        )
    }

    // --- REJECT PAYMENT CLAIM DIALOG ---
    if (showRejectDialogForClaim != null) {
        val claim = showRejectDialogForClaim!!
        val presetReasons = listOf(
            LanguageManager.getString("Payment not received in bank/UPI", "ব্যাংক বা UPI তে টাকা ঢোকেনি"),
            LanguageManager.getString("Incorrect amount claimed", "ভুল টাকার অঙ্ক ক্লেইম করা হয়েছে"),
            LanguageManager.getString("Duplicate submission", "একই পেমেন্ট দুইবার সাবমিট করা হয়েছে"),
            LanguageManager.getString("Invalid transaction reference", "ভুল লেনদেন রেফারেন্স")
        )

        AlertDialog(
            onDismissRequest = { if (!isProcessing) showRejectDialogForClaim = null },
            icon = {
                Surface(shape = CircleShape, color = StoreRedPrimary.copy(alpha = 0.15f), modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Close, contentDescription = null, tint = StoreRedPrimary, modifier = Modifier.size(24.dp))
                    }
                }
            },
            title = {
                Text(
                    text = LanguageManager.getString("Reject Payment Claim", "পেমেন্ট ক্লেইম বাতিল করুন"),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = LanguageManager.getString(
                            "Rejecting will notify the customer on their Khata link without modifying ledger balance.",
                            "বাতিল করলে গ্রাহকের খাতা লিঙ্কে কারণ প্রদর্শিত হবে এবং লেজার অপরিবর্তিত থাকবে।"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )

                    Text(
                        text = LanguageManager.getString("Select Reason:", "কারণ বেছে নিন:"),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        presetReasons.forEach { preset ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (rejectionReason == preset) StoreRedPrimary.copy(alpha = 0.12f) else SurfaceWarm,
                                border = BorderStroke(1.dp, if (rejectionReason == preset) StoreRedPrimary else TextMuted.copy(alpha = 0.2f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { rejectionReason = preset }
                            ) {
                                Text(
                                    text = preset,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (rejectionReason == preset) StoreRedPrimary else TextDark,
                                    fontWeight = if (rejectionReason == preset) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = rejectionReason,
                        onValueChange = { rejectionReason = it },
                        label = { Text(LanguageManager.getString("Or type custom reason", "বা অন্য কারণ লিখুন")) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isProcessing = true
                        val finalReason = rejectionReason.ifBlank { "Payment not received" }
                        viewModel.rejectPaymentClaim(
                            claim = claim,
                            reason = finalReason,
                            onSuccess = {
                                isProcessing = false
                                showRejectDialogForClaim = null
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        message = LanguageManager.getString("Claim marked as rejected.", "ক্লেইম বাতিল করা হয়েছে।"),
                                        duration = SnackbarDuration.Short
                                    )
                                }
                            },
                            onError = { err ->
                                isProcessing = false
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        message = "Error: $err",
                                        duration = SnackbarDuration.Long
                                    )
                                }
                            }
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StoreRedPrimary),
                    enabled = !isProcessing
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text(LanguageManager.getString("Reject Claim", "বাতিল করুন"))
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showRejectDialogForClaim = null },
                    enabled = !isProcessing
                ) {
                    Text(LanguageManager.getString("Cancel", "ফিরে যান"))
                }
            }
        )
    }
}

@Composable
fun PendingClaimCard(
    claim: PaymentClaim,
    currentCustomer: Customer?,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
    onCustomerClick: () -> Unit
) {
    val currentBalance = currentCustomer?.balance ?: claim.dueBalanceAtClaim
    val isBalanceChanged = Math.abs(currentBalance - claim.dueBalanceAtClaim) > 0.01

    val elapsedHours = TimeUnit.MILLISECONDS.toHours(System.currentTimeMillis() - claim.timestamp)
    val remainingHours = (48 - elapsedHours).coerceAtLeast(0)

    val timeFormatted = remember(claim.timestamp) {
        val sdf = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
        sdf.format(Date(claim.timestamp))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        elevation = CardDefaults.cardElevation(2.dp),
        border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Top Row: Customer Info & Elapsed Time
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.clickable { onCustomerClick() }
                ) {
                    PartyProfileAvatar(
                        name = claim.customerName,
                        photoUri = currentCustomer?.photoUri,
                        size = 38.dp
                    )

                    Column {
                        Text(
                            text = claim.customerName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextDark
                        )
                        Text(
                            text = claim.customerPhone.ifBlank { LanguageManager.getString("No Phone", "ফোন নেই") },
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFFFEF3C7)
                ) {
                    Text(
                        text = "⏳ $timeFormatted",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF92400E),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }

            HorizontalDivider(color = TextMuted.copy(alpha = 0.12f))

            // Middle Comparison Row: Claimed Amount vs Current Due Balance
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceWarm, RoundedCornerShape(10.dp))
                    .padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Claimed Amount
                Column {
                    Text(
                        text = LanguageManager.getString("Claimed Payment", "পরিশোধের দাবি"),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = TextMuted
                    )
                    Text(
                        text = "₹%.2f".format(claim.claimedAmount),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = StoreGreenProfit
                    )
                }

                Icon(
                    imageVector = Icons.Default.ArrowForward,
                    contentDescription = null,
                    tint = TextMuted.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp)
                )

                // Current Balance
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = LanguageManager.getString("Current Due (Dr)", "বর্তমান বকেয়া"),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = TextMuted
                    )
                    Text(
                        text = "₹%.2f".format(currentBalance),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (currentBalance > 0) StoreRedPrimary else StoreGreenProfit
                    )
                }
            }

            // Note / UTR reference if provided
            if (!claim.note.isNullOrBlank()) {
                Surface(
                    color = Color(0xFFEFF6FF),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFFBFDBFE))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ReceiptLong, contentDescription = null, tint = Color(0xFF1D4ED8), modifier = Modifier.size(16.dp))
                        Text(
                            text = LanguageManager.getString("Ref / Note: ", "নোট: ") + claim.note,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF1E3A8A)
                        )
                    }
                }
            }

            // Attached Payment Screenshot (Inline review for shopkeeper)
            val hasScreenshot = !claim.screenshotData.isNullOrBlank() || !claim.screenshotUrl.isNullOrBlank()
            if (hasScreenshot) {
                var showFullScreenImage by remember { mutableStateOf(false) }
                val screenshotBitmap by produceState<android.graphics.Bitmap?>(initialValue = null, claim.screenshotData, claim.screenshotUrl) {
                    value = withContext(Dispatchers.IO) {
                        val raw = claim.screenshotData ?: claim.screenshotUrl
                        if (!raw.isNullOrBlank() && !raw.startsWith("http://") && !raw.startsWith("https://")) {
                            try {
                                val cleanBase64 = if (raw.contains(",")) raw.substringAfter(",") else raw
                                val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            } catch (_: Exception) {
                                null
                            }
                        } else null
                    }
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFFF8FAFC),
                    border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Image,
                                    contentDescription = null,
                                    tint = StorePrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = LanguageManager.getString("Payment Screenshot", "পেমেন্ট স্ক্রিনশট"),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TextDark
                                )
                            }
                            Text(
                                text = LanguageManager.getString("Tap to enlarge", "বড় করে দেখতে চাপুন"),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                color = StorePrimary
                            )
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White)
                                .clickable { showFullScreenImage = true },
                            contentAlignment = Alignment.Center
                        ) {
                            val currentBmp = screenshotBitmap
                            if (currentBmp != null) {
                                Image(
                                    bitmap = currentBmp.asImageBitmap(),
                                    contentDescription = "Payment Screenshot",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else if (!claim.screenshotUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = claim.screenshotUrl,
                                    contentDescription = "Payment Screenshot",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }

                if (showFullScreenImage) {
                    ZoomablePaymentScreenshotDialog(
                        bitmap = screenshotBitmap,
                        imageUrl = claim.screenshotUrl,
                        base64Data = claim.screenshotData,
                        title = LanguageManager.getString("Payment Screenshot", "পেমেন্ট স্ক্রিনশট"),
                        subtitle = "₹${"%.2f".format(claim.claimedAmount)} • ${claim.customerName}",
                        onDismiss = { showFullScreenImage = false }
                    )
                }
            }

            // Warning if balance changed since claim submission
            if (isBalanceChanged) {
                Surface(
                    color = Color(0xFFFFF7ED),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFFFDBA74))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFEA580C), modifier = Modifier.size(16.dp))
                        Text(
                            text = LanguageManager.getString(
                                "Due balance was ₹%.2f at claim time (Now: ₹%.2f)".format(claim.dueBalanceAtClaim, currentBalance),
                                "ক্লেইমের সময় বাকি ছিল ₹%.2f (এখন: ₹%.2f)".format(claim.dueBalanceAtClaim, currentBalance)
                            ),
                            fontSize = 11.sp,
                            color = Color(0xFF9A3412)
                        )
                    }
                }
            }

            // Auto-expiry badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = LanguageManager.getString("Auto-expires in ${remainingHours}h", "${remainingHours} ঘণ্টার মধ্যে মেয়াদ শেষ হবে"),
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 10.sp,
                    color = TextMuted
                )

                // 1-Tap Counter-Optimized Action Buttons (48dp height touch targets)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onReject,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = StatusAlertOverdue,
                            containerColor = StatusAlertOverdue.copy(alpha = 0.05f)
                        ),
                        border = BorderStroke(1.5.dp, StatusAlertOverdue.copy(alpha = 0.7f)),
                        modifier = Modifier.height(48.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = LanguageManager.getString("Reject", "বাতিল"),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = onConfirm,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StatusSuccessPaid,
                            contentColor = Color.White
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
                        modifier = Modifier.height(48.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = LanguageManager.getString("Confirm & Credit", "কনফার্ম ও জমা"),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryClaimCard(claim: PaymentClaim) {
    val dateFormatted = remember(claim.timestamp) {
        val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        sdf.format(Date(claim.timestamp))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        border = BorderStroke(1.dp, TextMuted.copy(alpha = 0.12f)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = claim.customerName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextDark
                    )
                    StatusChip(status = claim.status)
                }

                Text(
                    text = "₹%.2f • %s".format(claim.claimedAmount, dateFormatted),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    fontSize = 11.sp
                )

                if (!claim.rejectionReason.isNullOrBlank()) {
                    Text(
                        text = LanguageManager.getString("Reason: ", "কারণ: ") + claim.rejectionReason,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color = StatusAlertOverdue
                    )
                }
            }

            Text(
                text = "₹%.2f".format(claim.claimedAmount),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = when (claim.status) {
                    PaymentClaim.STATUS_CONFIRMED -> StatusSuccessPaid
                    PaymentClaim.STATUS_REJECTED -> StatusAlertOverdue
                    else -> TextMuted
                }
            )
        }
    }
}

@Composable
fun StatusChip(status: String) {
    val (bgColor, textColor, label) = when (status) {
        PaymentClaim.STATUS_CONFIRMED -> Triple(Color(0xFFE8F5E9), Color(0xFF2E7D32), LanguageManager.getString("Confirmed", "গৃহীত"))
        PaymentClaim.STATUS_REJECTED -> Triple(Color(0xFFFFEBEE), Color(0xFFD32F2F), LanguageManager.getString("Rejected", "প্রত্যাখ্যাত"))
        PaymentClaim.STATUS_EXPIRED -> Triple(Color(0xFFF1F5F9), Color(0xFF64748B), LanguageManager.getString("Expired", "মেয়াদোত্তীর্ণ"))
        else -> Triple(Color(0xFFFEF3C7), Color(0xFFC9971C), LanguageManager.getString("Pending", "পেন্ডিং"))
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = bgColor,
        border = BorderStroke(1.dp, textColor.copy(alpha = 0.3f))
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = textColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun HistoryFilterChip(
    key: String,
    label: String,
    selected: String,
    onSelect: (String) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected == key) StorePrimary else SurfaceWarm,
        border = BorderStroke(1.dp, if (selected == key) StorePrimary else TextMuted.copy(alpha = 0.2f)),
        modifier = Modifier.clickable { onSelect(key) }
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (selected == key) FontWeight.Bold else FontWeight.Normal,
            color = if (selected == key) Color.White else TextDark,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}
