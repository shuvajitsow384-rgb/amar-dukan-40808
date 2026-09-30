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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.models.CustomerLinkStatus
import com.example.data.models.LiveCustomerDetails
import com.example.data.models.PendingLinkRequest
import com.example.ui.theme.*
import com.example.utils.LanguageManager
import com.example.viewmodel.StoreViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class PendingLinksTab(val labelEn: String, val labelBn: String) {
    PENDING("Pending", "অপেক্ষমান"),
    APPROVED("Approved", "অনুমোদিত"),
    REJECTED("Declined", "প্রত্যাখ্যাত"),
    ALL("All Requests", "সকল আবেদন");

    fun getLabel(): String = if (LanguageManager.isBengali) labelBn else labelEn
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingCustomerLinksDialog(
    viewModel: StoreViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val allRequests by viewModel.pendingCustomerLinks.collectAsState()
    var selectedTab by remember { mutableStateOf(PendingLinksTab.PENDING) }
    var searchQuery by remember { mutableStateOf("") }
    var isRefreshing by remember { mutableStateOf(false) }

    // Dialog state for approval/rejection confirmation
    var requestToApprove by remember { mutableStateOf<PendingLinkRequest?>(null) }
    var requestToDecline by remember { mutableStateOf<PendingLinkRequest?>(null) }
    var liveCustomerForDialog by remember { mutableStateOf<LiveCustomerDetails?>(null) }
    var isActionInProgress by remember { mutableStateOf(false) }

    // Filter requests
    val filteredRequests = remember(allRequests, selectedTab, searchQuery) {
        allRequests.filter { req ->
            val matchesTab = when (selectedTab) {
                PendingLinksTab.PENDING -> req.isPending
                PendingLinksTab.APPROVED -> req.isApproved
                PendingLinksTab.REJECTED -> req.isRejected
                PendingLinksTab.ALL -> true
            }
            val q = searchQuery.trim().lowercase()
            val matchesSearch = q.isEmpty() ||
                req.googleName.lowercase().contains(q) ||
                req.googleEmail.lowercase().contains(q) ||
                req.claimedPhone.contains(q) ||
                req.existingCustomerName.lowercase().contains(q) ||
                req.customerId.lowercase().contains(q)
            matchesTab && matchesSearch
        }
    }

    val pendingCount = remember(allRequests) { allRequests.count { it.isPending } }

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
                // Top Header Row
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
                                Icon(
                                    imageVector = Icons.Default.Link,
                                    contentDescription = null,
                                    tint = StorePrimary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (LanguageManager.isBengali) "খাতা লিঙ্ক অনুমোদন" else "Pending Customer Links",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TextDark
                                )
                                if (pendingCount > 0) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        shape = CircleShape,
                                        color = StoreGold,
                                        modifier = Modifier.padding(2.dp)
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
                                text = if (LanguageManager.isBengali) "অনলাইন গ্রাহক অ্যাকাউন্ট ও অফলাইন খাতা যাচাই" else "Verify online customer links to offline Khata ledgers",
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

                // Informational Banner
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = StorePrimary.copy(alpha = 0.08f)),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, StorePrimary.copy(alpha = 0.25f))
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Security,
                            contentDescription = null,
                            tint = StorePrimary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = if (LanguageManager.isBengali) "দ্বৈত যাচাইকরণ নীতি" else "Two-Step Verification Protection",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = StorePrimary
                            )
                            Text(
                                text = if (LanguageManager.isBengali) {
                                    "গুগল সাইন-ইন শুধুমাত্র ইমেল নিশ্চিত করে, ফোন নম্বর নয়। অননুমোদিত অ্যাক্সেস রোধ করতে গ্রাহক খাতা দেখার পূর্বে আপনার অনুমোদন বাধ্যতামূলক।"
                                } else {
                                    "Google Sign-In only verifies email identity. To prevent unauthorized credit ledger viewing, manual shopkeeper verification is strictly required before linking."
                                },
                                fontSize = 10.5.sp,
                                color = TextDark,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Filter Tabs & Refresh
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ScrollableTabRow(
                        selectedTabIndex = selectedTab.ordinal,
                        edgePadding = 0.dp,
                        containerColor = Color.Transparent,
                        divider = {},
                        indicator = {},
                        modifier = Modifier.weight(1f)
                    ) {
                        PendingLinksTab.values().forEach { tab ->
                            val isSelected = selectedTab == tab
                            val count = when (tab) {
                                PendingLinksTab.PENDING -> pendingCount
                                PendingLinksTab.APPROVED -> allRequests.count { it.isApproved }
                                PendingLinksTab.REJECTED -> allRequests.count { it.isRejected }
                                PendingLinksTab.ALL -> allRequests.size
                            }
                            FilterChip(
                                selected = isSelected,
                                onClick = { selectedTab = tab },
                                label = {
                                    Text(
                                        text = "${tab.getLabel()} ($count)",
                                        fontSize = 11.5.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = StorePrimary,
                                    selectedLabelColor = Color.White
                                ),
                                modifier = Modifier.padding(end = 6.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Search field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            text = if (LanguageManager.isBengali) "গ্রাহকের নাম, ফোন বা ইমেইল খুঁজুন..." else "Search by name, phone or email...",
                            fontSize = 12.sp,
                            color = TextMuted
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted, modifier = Modifier.size(18.dp))
                    },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear", tint = TextMuted, modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = StorePrimary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Request List
                if (filteredRequests.isEmpty()) {
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
                                imageVector = if (selectedTab == PendingLinksTab.PENDING) Icons.Default.CheckCircle else Icons.Default.HourglassEmpty,
                                contentDescription = null,
                                tint = if (selectedTab == PendingLinksTab.PENDING) StoreGreenProfit.copy(alpha = 0.5f) else TextMuted.copy(alpha = 0.5f),
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = when (selectedTab) {
                                    PendingLinksTab.PENDING -> if (LanguageManager.isBengali) "কোন অপেক্ষমান লিঙ্ক নেই" else "No Pending Link Requests"
                                    PendingLinksTab.APPROVED -> if (LanguageManager.isBengali) "কোন অনুমোদিত লিঙ্ক নেই" else "No Approved Links"
                                    PendingLinksTab.REJECTED -> if (LanguageManager.isBengali) "কোন প্রত্যাখ্যাত লিঙ্ক নেই" else "No Declined Links"
                                    PendingLinksTab.ALL -> if (LanguageManager.isBengali) "কোন আবেদন পাওয়া যায়নি" else "No Requests Found"
                                },
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall,
                                color = TextDark
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (selectedTab == PendingLinksTab.PENDING) {
                                    if (LanguageManager.isBengali) "সকল গ্রাহক খাতা লিঙ্ক যাচাই সম্পন্ন হয়েছে।" else "All customer account link requests have been reviewed."
                                } else {
                                    if (LanguageManager.isBengali) "ফিল্টারের সাথে মিলে এমন কোন তথ্য পাওয়া যায়নি।" else "No customer accounts match the current filter."
                                },
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
                        items(filteredRequests, key = { it.requestId }) { request ->
                            PendingLinkItemCard(
                                request = request,
                                viewModel = viewModel,
                                onApproveClick = { liveCustomer ->
                                    requestToApprove = request
                                    liveCustomerForDialog = liveCustomer
                                },
                                onDeclineClick = { liveCustomer ->
                                    requestToDecline = request
                                    liveCustomerForDialog = liveCustomer
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Approve Confirmation Dialog
    requestToApprove?.let { req ->
        val liveCust = liveCustomerForDialog
        AlertDialog(
            onDismissRequest = {
                if (!isActionInProgress) {
                    requestToApprove = null
                    liveCustomerForDialog = null
                }
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StoreGreenProfit)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (LanguageManager.isBengali) "খাতা লিঙ্ক অনুমোদন করবেন?" else "Approve Khata Link?",
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (LanguageManager.isBengali) {
                            "আপনি কি নিশ্চিত যে নিচের গুগল অ্যাকাউন্টকে খাতা গ্রাহকের সাথে যুক্ত করতে চান?"
                        } else {
                            "Are you sure you want to approve and link this customer account?"
                        },
                        fontSize = 13.sp,
                        color = TextDark
                    )

                    Surface(
                        color = StoreGreenProfit.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, StoreGreenProfit.copy(alpha = 0.25f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "👤 Google Account: ${req.googleName} (${req.googleEmail})",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextDark
                            )
                            Text(
                                text = "📱 Claimed Phone: ${req.claimedPhone}",
                                fontSize = 12.sp,
                                color = TextDark
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Divider(color = StoreGreenProfit.copy(alpha = 0.3f), thickness = 0.5.dp)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "📖 Live Khata: ${liveCust?.name ?: req.existingCustomerName} (ID: ${req.customerId})",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = StoreGreenProfit
                            )
                            Text(
                                text = "💰 Current Due Balance: ₹%.2f".format(liveCust?.balance ?: req.existingCustomerBalance),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if ((liveCust?.balance ?: req.existingCustomerBalance) > 0) StoreRedPrimary else StoreGreenProfit
                            )
                        }
                    }

                    Text(
                        text = if (LanguageManager.isBengali) {
                            "অনুমোদনের পর গ্রাহক তার ফোনের স্টোরফ্রন্টে এই খাতার পূর্ণ লেনদেন বিবরণী দেখতে পাবেন এবং অনলাইনে পাওনা পরিশোধ করতে পারবেন।"
                        } else {
                            "Once approved, this customer will be able to view full ledger history and pay outstanding balances directly from their online storefront."
                        },
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isActionInProgress = true
                        viewModel.reviewPendingCustomerLink(req.requestId, approve = true) { success, msg ->
                            isActionInProgress = false
                            requestToApprove = null
                            liveCustomerForDialog = null
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = !isActionInProgress,
                    colors = ButtonDefaults.buttonColors(containerColor = StoreGreenProfit)
                ) {
                    if (isActionInProgress) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(if (LanguageManager.isBengali) "অনুমোদন করুন" else "Approve & Link", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        requestToApprove = null
                        liveCustomerForDialog = null
                    },
                    enabled = !isActionInProgress
                ) {
                    Text(if (LanguageManager.isBengali) "বাতিল" else "Cancel")
                }
            }
        )
    }

    // Decline Confirmation Dialog
    requestToDecline?.let { req ->
        AlertDialog(
            onDismissRequest = {
                if (!isActionInProgress) {
                    requestToDecline = null
                    liveCustomerForDialog = null
                }
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Cancel, contentDescription = null, tint = StoreRedPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (LanguageManager.isBengali) "লিঙ্ক আবেদন প্রত্যাখ্যান করবেন?" else "Decline Link Request?",
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (LanguageManager.isBengali) {
                            "আপনি কি নিশ্চিত যে '${req.googleName}' (${req.googleEmail})-এর আবেদন প্রত্যাখ্যান করতে চান?"
                        } else {
                            "Are you sure you want to decline the link request from '${req.googleName}' (${req.googleEmail})?"
                        },
                        fontSize = 13.sp,
                        color = TextDark
                    )
                    Text(
                        text = if (LanguageManager.isBengali) {
                            "প্রত্যাখ্যান করলে এই গ্রাহক খাতার ব্যালেন্স ও লেনদেন দেখতে পাবেন না।"
                        } else {
                            "The customer will remain an unlinked guest on the storefront and will not be able to view this Khata ledger."
                        },
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isActionInProgress = true
                        viewModel.reviewPendingCustomerLink(req.requestId, approve = false) { success, msg ->
                            isActionInProgress = false
                            requestToDecline = null
                            liveCustomerForDialog = null
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = !isActionInProgress,
                    colors = ButtonDefaults.buttonColors(containerColor = StoreRedPrimary)
                ) {
                    if (isActionInProgress) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(if (LanguageManager.isBengali) "প্রত্যাখ্যান করুন" else "Decline Request", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        requestToDecline = null
                        liveCustomerForDialog = null
                    },
                    enabled = !isActionInProgress
                ) {
                    Text(if (LanguageManager.isBengali) "বাতিল" else "Cancel")
                }
            }
        )
    }
}

@Composable
fun PendingLinkItemCard(
    request: PendingLinkRequest,
    viewModel: StoreViewModel,
    onApproveClick: (LiveCustomerDetails?) -> Unit,
    onDeclineClick: (LiveCustomerDetails?) -> Unit
) {
    // MANDATORY LIVE READ OF customers/{customerId}
    var liveCustomer by remember(request.customerId) { mutableStateOf<LiveCustomerDetails?>(null) }
    var isLoadingLive by remember(request.customerId) { mutableStateOf(true) }

    LaunchedEffect(request.customerId) {
        isLoadingLive = true
        try {
            liveCustomer = viewModel.getLiveCustomerDetails(request.customerId)
        } catch (e: Exception) {
            liveCustomer = null
        } finally {
            isLoadingLive = false
        }
    }

    val requestDateFormatted = remember(request.requestedAt) {
        try {
            SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(request.requestedAt))
        } catch (e: Exception) {
            ""
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            1.dp,
            when {
                request.isPending -> StoreGold.copy(alpha = 0.5f)
                request.isApproved -> StoreGreenProfit.copy(alpha = 0.3f)
                else -> MaterialTheme.colorScheme.outlineVariant
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header: Google Account Profile
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
                        color = StorePrimary.copy(alpha = 0.12f),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = request.googleName.take(1).uppercase().ifBlank { "G" },
                                fontWeight = FontWeight.Bold,
                                color = StorePrimary,
                                fontSize = 16.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Text(
                            text = request.googleName.ifBlank { "Google User" },
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall,
                            color = TextDark,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = request.googleEmail,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Status Badge
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = when {
                        request.isPending -> StoreGold.copy(alpha = 0.12f)
                        request.isApproved -> StoreGreenProfit.copy(alpha = 0.12f)
                        else -> StoreRedPrimary.copy(alpha = 0.12f)
                    }
                ) {
                    Text(
                        text = when {
                            request.isPending -> if (LanguageManager.isBengali) "অপেক্ষমান" else "PENDING"
                            request.isApproved -> if (LanguageManager.isBengali) "অনুমোদিত" else "APPROVED"
                            else -> if (LanguageManager.isBengali) "প্রত্যাখ্যাত" else "DECLINED"
                        },
                        color = when {
                            request.isPending -> Color(0xFFB45309)
                            request.isApproved -> StoreGreenProfit
                            else -> StoreRedPrimary
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Claimed Phone row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Phone,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Claimed Phone: ${request.claimedPhone.ifBlank { "None" }}",
                    fontSize = 11.5.sp,
                    color = TextDark,
                    fontWeight = FontWeight.Medium
                )
                if (requestDateFormatted.isNotBlank()) {
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = requestDateFormatted,
                        fontSize = 10.5.sp,
                        color = TextMuted
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // MANDATORY LIVE READ OF customers/{customerId} BOX
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = if (request.isPending) Color(0xFFF8FAFC) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.MenuBook,
                                contentDescription = null,
                                tint = StorePrimary,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "LIVE KHATA LEDGER MATCH",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = StorePrimary,
                                letterSpacing = 0.5.sp
                            )
                        }

                        if (isLoadingLive) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.5.dp,
                                    color = StorePrimary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Reading live...", fontSize = 10.sp, color = TextMuted)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    if (!isLoadingLive && liveCustomer != null && !liveCustomer!!.exists) {
                        Surface(
                            color = StoreRedPrimary.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "⚠️ Customer record '${request.customerId}' no longer exists in Khata.",
                                fontSize = 11.sp,
                                color = StoreRedPrimary,
                                modifier = Modifier.padding(6.dp)
                            )
                        }
                    } else {
                        // Live Customer Details
                        val currentName = liveCustomer?.name ?: request.existingCustomerName
                        val currentBalance = liveCustomer?.balance ?: request.existingCustomerBalance
                        val billCount = liveCustomer?.billCount ?: 0
                        val livePhone = liveCustomer?.phone ?: request.existingCustomerPhone

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = currentName.ifBlank { "Customer #${request.customerId.take(6)}" },
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = TextDark
                                )
                                Text(
                                    text = "Phone in Khata: ${livePhone.ifBlank { "Not set" }}",
                                    fontSize = 11.sp,
                                    color = TextMuted
                                )
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "₹%.2f".format(currentBalance),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = if (currentBalance > 0) StoreRedPrimary else StoreGreenProfit
                                )
                                Text(
                                    text = "$billCount bills recorded",
                                    fontSize = 10.5.sp,
                                    color = TextMuted
                                )
                            }
                        }

                        // Phone Match Check
                        val normalizedClaimed = request.claimedPhone.replace("\\D".toRegex(), "").takeLast(10)
                        val normalizedLive = livePhone.replace("\\D".toRegex(), "").takeLast(10)
                        val phoneExactMatch = normalizedClaimed.isNotBlank() && normalizedClaimed == normalizedLive

                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (phoneExactMatch) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StoreGreenProfit, modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Phone numbers match",
                                    fontSize = 10.5.sp,
                                    color = StoreGreenProfit,
                                    fontWeight = FontWeight.SemiBold
                                )
                            } else {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFD97706), modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Phone discrepancy — verify customer identity carefully",
                                    fontSize = 10.5.sp,
                                    color = Color(0xFFB45309),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }

            // Action Buttons for PENDING requests
            if (request.isPending) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { onDeclineClick(liveCustomer) },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = StoreRedPrimary),
                        border = BorderStroke(1.dp, StoreRedPrimary.copy(alpha = 0.5f)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (LanguageManager.isBengali) "প্রত্যাখ্যান" else "Decline", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = { onApproveClick(liveCustomer) },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = StoreGreenProfit),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (LanguageManager.isBengali) "অনুমোদন করুন" else "Approve Link", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else if (request.reviewedBy != null) {
                // Reviewed info footer
                Spacer(modifier = Modifier.height(8.dp))
                val reviewedDateFormatted = try {
                    request.reviewedAt?.let { SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(it)) } ?: ""
                } catch (e: Exception) {
                    ""
                }
                Text(
                    text = "Reviewed by: ${request.reviewedBy} ${if (reviewedDateFormatted.isNotBlank()) "• $reviewedDateFormatted" else ""}",
                    fontSize = 10.sp,
                    color = TextMuted
                )
            }
        }
    }
}
