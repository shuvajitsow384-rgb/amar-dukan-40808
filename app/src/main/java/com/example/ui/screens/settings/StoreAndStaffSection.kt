package com.example.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.auth.AuthUser
import com.example.data.firestore.FirestoreUserRole
import com.example.data.local.entities.Employee
import com.example.ui.components.StoreQrModalDialog
import com.example.ui.theme.*
import com.example.utils.LanguageManager
import com.example.utils.StaffManager
import com.example.utils.StoreInfoManager
import com.example.viewmodel.StoreViewModel

@Composable
fun StoreAndStaffSection(
    context: Context,
    viewModel: StoreViewModel,
    currentUser: AuthUser?,
    currentFirestoreUserRole: FirestoreUserRole?,
    allEmployees: List<Employee>,
    onEditStoreClicked: () -> Unit,
    onManageEmployeesClicked: () -> Unit,
    onOpenAttendanceHubClicked: () -> Unit,
    onOpenPayrollHubClicked: () -> Unit,
    onOpenActivityLogHubClicked: () -> Unit,
    onChangeOwnerPinClicked: () -> Unit,
    onSwitchStaffProfileClicked: () -> Unit
) {
    var showStoreQrDialog by remember { mutableStateOf(false) }
    var showPendingStaffDialog by remember { mutableStateOf(false) }
    val pendingStaffCount by viewModel.pendingStaffCount.collectAsState()

    var freeDeliveryMinInput by remember(StoreInfoManager.freeDeliveryMinOrderValue) {
        mutableStateOf(if (StoreInfoManager.freeDeliveryMinOrderValue % 1.0 == 0.0) StoreInfoManager.freeDeliveryMinOrderValue.toInt().toString() else StoreInfoManager.freeDeliveryMinOrderValue.toString())
    }
    var deliveryFeeInput by remember(StoreInfoManager.deliveryFee) {
        mutableStateOf(if (StoreInfoManager.deliveryFee % 1.0 == 0.0) StoreInfoManager.deliveryFee.toInt().toString() else StoreInfoManager.deliveryFee.toString())
    }

    val shareStoreLink: () -> Unit = {
        val storeUrl = "https://amar-dukan-40808.web.app/shop/"
        val storeName = StoreInfoManager.storeName.ifBlank { "Amar Dukan" }
        val isBn = LanguageManager.isBengali
        val shareText = if (isBn) {
            "আমাদের অনলাইন দোকান থেকে কেনাকাটা করুন:\n$storeUrl\n— $storeName"
        } else {
            "Order online from $storeName:\n$storeUrl"
        }
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "$storeName - Online Store")
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        try {
            context.startActivity(
                Intent.createChooser(
                    sendIntent,
                    if (isBn) "অনলাইন স্টোর লিঙ্ক শেয়ার করুন" else "Share Online Store"
                )
            )
        } catch (e: Exception) {
            Toast.makeText(context, "Cannot open share sheet", Toast.LENGTH_SHORT).show()
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Store Header Banner Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = StoreRedPrimary),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (LanguageManager.isBengali) "দোকানের সাধারণ তথ্য" else "STORE INFORMATION",
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )

                    Button(
                        onClick = onEditStoreClicked,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.25f)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit Store", tint = Color.White, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (LanguageManager.isBengali) "এডিট" else "Edit", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = StoreInfoManager.storeName.ifBlank { "My Store" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Text(
                    text = StoreInfoManager.storeAddress.ifBlank { "No address set" },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.9f)
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Owner: ${StoreInfoManager.ownerName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = StoreGold,
                        fontWeight = FontWeight.Bold
                    )

                    if (StoreInfoManager.phone.isNotBlank()) {
                        Text(
                            text = "Ph: ${StoreInfoManager.phone}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                    }
                }

                if (StoreInfoManager.gstin.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "GSTIN: ${StoreInfoManager.gstin}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                    }
                }
            }
        }

        // ONLINE STORE & HOME DELIVERY CONTROLS CARD
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
                            color = Color(0xFF0284C7).copy(alpha = 0.12f),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.ShoppingBag,
                                    contentDescription = null,
                                    tint = Color(0xFF0284C7),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (LanguageManager.isBengali) "অনলাইন স্টোর ও ডেলিভারি" else "Online Store & Delivery",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = TextDark
                            )
                            Text(
                                text = if (LanguageManager.isBengali) "ওয়েব স্টোর ও গ্রাহক অর্ডার সেটিংস" else "Web shop & customer checkout controls",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                        }
                    }

                    // Copy Web Store Link Button
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("Online Store Link", "https://amar-dukan-40808.web.app/shop/")
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(
                                context,
                                if (LanguageManager.isBengali) "অনলাইন স্টোর লিংক কপি করা হয়েছে!" else "Online store link copied to clipboard!",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(12.dp), tint = Color(0xFF0284C7))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (LanguageManager.isBengali) "লিংক" else "Link", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0284C7))
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(modifier = Modifier.height(12.dp))

                // Toggle 1: Online Ordering On/Off
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (LanguageManager.isBengali) "অনলাইন অর্ডার গ্রহণ" else "Online Ordering",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextDark
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (StoreInfoManager.onlineOrderingEnabled) StoreGreenProfit.copy(alpha = 0.12f) else StoreSaffronAccent.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = if (StoreInfoManager.onlineOrderingEnabled) {
                                        if (LanguageManager.isBengali) "চালু" else "ACTIVE"
                                    } else {
                                        if (LanguageManager.isBengali) "স্থগিত" else "PAUSED"
                                    },
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (StoreInfoManager.onlineOrderingEnabled) StoreGreenProfit else StoreSaffronAccent,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = if (LanguageManager.isBengali)
                                "বন্ধ থাকলে ওয়েবসাইট থেকে সাময়িকভাবে নতুন অর্ডার নেওয়া স্থগিত থাকবে।"
                            else
                                "When turned off, online ordering on the web shop is temporarily paused.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Switch(
                        checked = StoreInfoManager.onlineOrderingEnabled,
                        onCheckedChange = { isChecked ->
                            StoreInfoManager.updateOnlineOrderingEnabled(isChecked, context)
                            val msg = if (isChecked) {
                                if (LanguageManager.isBengali) "অনলাইন অর্ডার গ্রহণ চালু করা হয়েছে" else "Online ordering resumed"
                            } else {
                                if (LanguageManager.isBengali) "অনলাইন অর্ডার সাময়িকভাবে স্থগিত করা হয়েছে" else "Online ordering paused"
                            }
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = StoreGreenProfit
                        )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                Spacer(modifier = Modifier.height(12.dp))

                // Toggle 2: Home Delivery On/Off
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.LocalShipping,
                                contentDescription = null,
                                tint = if (StoreInfoManager.homeDeliveryEnabled) Color(0xFF2563EB) else TextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (LanguageManager.isBengali) "হোম ডেলিভারি সার্ভিস" else "Home Delivery Service",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextDark
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (StoreInfoManager.homeDeliveryEnabled) Color(0xFF2563EB).copy(alpha = 0.12f) else Color(0xFFF59E0B).copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = if (StoreInfoManager.homeDeliveryEnabled) {
                                        if (LanguageManager.isBengali) "ডেলিভারি চালু" else "DELIVERY ON"
                                    } else {
                                        if (LanguageManager.isBengali) "পিকআপ বাধ্যতামূলক" else "PICKUP ONLY"
                                    },
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (StoreInfoManager.homeDeliveryEnabled) Color(0xFF2563EB) else Color(0xFFD97706),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = if (LanguageManager.isBengali)
                                "বন্ধ থাকলে চেকআউটে হোম ডেলিভারি লুকিয়ে রাখা হবে এবং গ্রাহক শুধু দোকান থেকে পিকআপ করতে পারবে।"
                            else
                                "When disabled, home delivery is hidden at checkout and customers can only choose Store Pickup.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Switch(
                        checked = StoreInfoManager.homeDeliveryEnabled,
                        onCheckedChange = { isChecked ->
                            StoreInfoManager.updateHomeDeliveryEnabled(isChecked, context)
                            val msg = if (isChecked) {
                                if (LanguageManager.isBengali) "হোম ডেলিভারি সার্ভিস চালু করা হয়েছে" else "Home delivery enabled"
                            } else {
                                if (LanguageManager.isBengali) "হোম ডেলিভারি বন্ধ (শুধুমাত্র দোকান থেকে পিকআপ)" else "Home delivery disabled (Store pickup only)"
                            }
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF2563EB)
                        )
                    )
                }

                if (StoreInfoManager.homeDeliveryEnabled) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFF0FDF4),
                        border = BorderStroke(1.dp, Color(0xFFBBF7D0)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.CurrencyRupee,
                                    contentDescription = null,
                                    tint = Color(0xFF16A34A),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (LanguageManager.isBengali) "ফ্রি ডেলিভারি ও চার্জ কনফিগারেশন" else "Free Delivery & Fee Settings",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF15803D)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (LanguageManager.isBengali)
                                    "ন্যূনতম অর্ডারের নিচে ছোট অর্ডারে ডেলিভারি চার্জ যোগ হবে, অর্ডার আটকানো হবে না।"
                                else
                                    "Orders below threshold will be charged a small delivery fee rather than being blocked.",
                                fontSize = 11.sp,
                                color = TextMuted,
                                lineHeight = 15.sp
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedTextField(
                                    value = freeDeliveryMinInput,
                                    onValueChange = { freeDeliveryMinInput = it.filter { ch -> ch.isDigit() || ch == '.' } },
                                    label = {
                                        Text(
                                            if (LanguageManager.isBengali) "ফ্রি ডেলিভারি ন্যূনতম (₹)" else "Free Delivery Above (₹)",
                                            fontSize = 11.sp
                                        )
                                    },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color(0xFF16A34A),
                                        unfocusedBorderColor = Color(0xFF86EFAC)
                                    )
                                )

                                OutlinedTextField(
                                    value = deliveryFeeInput,
                                    onValueChange = { deliveryFeeInput = it.filter { ch -> ch.isDigit() || ch == '.' } },
                                    label = {
                                        Text(
                                            if (LanguageManager.isBengali) "ডেলিভারি চার্জ (₹)" else "Delivery Fee (₹)",
                                            fontSize = 11.sp
                                        )
                                    },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color(0xFF16A34A),
                                        unfocusedBorderColor = Color(0xFF86EFAC)
                                    )
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Button(
                                onClick = {
                                    val parsedThreshold = freeDeliveryMinInput.toDoubleOrNull() ?: 499.0
                                    val parsedFee = deliveryFeeInput.toDoubleOrNull() ?: 20.0
                                    StoreInfoManager.updateDeliveryCharges(parsedThreshold, parsedFee, context)
                                    Toast.makeText(
                                        context,
                                        if (LanguageManager.isBengali) "ডেলিভারি চার্জের নিয়ম আপডেট করা হয়েছে" else "Delivery settings updated",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.align(Alignment.End),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (LanguageManager.isBengali) "সেভ করুন" else "Save",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                Spacer(modifier = Modifier.height(12.dp))

                // Actions: Share Online Store Link & Display Store QR Code
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Action 1: Share Online Store (opens Android share sheet)
                    Button(
                        onClick = shareStoreLink,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (LanguageManager.isBengali) "স্টোর লিঙ্ক শেয়ার" else "Share Online Store",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Action 2: Store QR Code Modal (with Save/Download & Share)
                    OutlinedButton(
                        onClick = { showStoreQrDialog = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF0284C7))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (LanguageManager.isBengali) "স্টোর QR কোড" else "Store QR Code",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0284C7)
                        )
                    }
                }
            }
        }

        // STAFF & EMPLOYEE ACCESS CONTROL MANAGEMENT CARD
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
                            color = StorePrimary.copy(alpha = 0.12f),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Badge,
                                    contentDescription = null,
                                    tint = StorePrimary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (LanguageManager.isBengali) "কর্মচারী ও প্রবেশাধিকার নিয়ন্ত্রণ" else "Staff & Access Control",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = TextDark
                            )
                            val activeStaff = StaffManager.activeStaff
                            Text(
                                text = if (activeStaff != null) {
                                    if (LanguageManager.isBengali) "বর্তমান ব্যবহারকারী: ${activeStaff.name} (${activeStaff.role})" else "Active User: ${activeStaff.name} (${activeStaff.role})"
                                } else {
                                    if (LanguageManager.isBengali) "দোকানের মালিক (সম্পূর্ণ প্রবেশাধিকার)" else "Store Owner (Full Access)"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (StaffManager.isOwner()) StoreGold else StorePrimary,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { showPendingStaffDialog = true },
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(
                                width = if (pendingStaffCount > 0) 1.5.dp else 1.dp,
                                color = if (pendingStaffCount > 0) Color(0xFFF59E0B) else MaterialTheme.colorScheme.outlineVariant
                            ),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (pendingStaffCount > 0) Color(0xFFFEF3C7) else Color.Transparent
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(
                                Icons.Default.HowToReg,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = if (pendingStaffCount > 0) Color(0xFFB45309) else StorePrimary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (LanguageManager.isBengali) "অনুমোদন" else "Requests",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (pendingStaffCount > 0) Color(0xFFB45309) else TextDark
                            )
                            if (pendingStaffCount > 0) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Surface(
                                    shape = CircleShape,
                                    color = Color(0xFFF59E0B)
                                ) {
                                    Text(
                                        text = "$pendingStaffCount",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }

                        Button(
                            onClick = onManageEmployeesClicked,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = StorePrimary),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(Icons.Default.ManageAccounts, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (LanguageManager.isBengali) "স্টাফ তালিকা" else "Staff List", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }

                if (pendingStaffCount > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = Color(0xFFFEF3C7),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = Color(0xFFB45309),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (LanguageManager.isBengali)
                                        "$pendingStaffCount টি নতুন অ্যাকাউন্টের স্টাফ হিসেবে যোগদানের আবেদন অপেক্ষমান"
                                    else
                                        "$pendingStaffCount new staff access request(s) awaiting review",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF92400E)
                                )
                            }
                            TextButton(
                                onClick = { showPendingStaffDialog = true },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(26.dp)
                            ) {
                                Text(
                                    text = if (LanguageManager.isBengali) "যাচাই করুন" else "Review Now",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFB45309)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Quick Employee Hub shortcuts (Attendance, Payroll, Audit Logs)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onOpenAttendanceHubClicked,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.EventAvailable, contentDescription = null, modifier = Modifier.size(14.dp), tint = StoreGreenProfit)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (LanguageManager.isBengali) "হাজিরা" else "Attendance", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TextDark)
                    }

                    OutlinedButton(
                        onClick = onOpenPayrollHubClicked,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Payments, contentDescription = null, modifier = Modifier.size(14.dp), tint = StoreGold)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (LanguageManager.isBengali) "বেতন খাতা" else "Payroll", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TextDark)
                    }

                    OutlinedButton(
                        onClick = onOpenActivityLogHubClicked,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(14.dp), tint = StorePrimary)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (LanguageManager.isBengali) "অডিট লগ" else "Logs", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TextDark)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Owner PIN & Lock Enforcement Status Row
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val isPinSet = StaffManager.ownerPin.isNotBlank()
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                imageVector = if (isPinSet) Icons.Default.Lock else Icons.Default.LockOpen,
                                contentDescription = null,
                                tint = if (isPinSet) StoreGreenProfit else Color(0xFFE65100),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = if (isPinSet) {
                                        if (LanguageManager.isBengali) "মালিক পিন নিরাপত্তা সক্রিয়" else "Owner PIN Protection: Active"
                                    } else {
                                        if (LanguageManager.isBengali) "মালিক পিন সেট করা নেই" else "Owner PIN Protection: Not Set"
                                    },
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextDark
                                )
                                Text(
                                    text = if (LanguageManager.isBengali)
                                        "সেটিংস, লাভ-লোকসান ও এডিট অপশনে অননুমোদিত প্রবেশ রোধ করে"
                                    else
                                        "Restricts profit view, settings, discounts & history edits to owner",
                                    fontSize = 10.sp,
                                    color = TextMuted,
                                    lineHeight = 13.sp
                                )
                            }
                        }

                        Button(
                            onClick = onChangeOwnerPinClicked,
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isPinSet) StorePrimary else StoreRedPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text(
                                text = if (isPinSet) {
                                    if (LanguageManager.isBengali) "পরিবর্তন" else "Change PIN"
                                } else {
                                    if (LanguageManager.isBengali) "পিন সেট করুন" else "Set PIN"
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Switch Active Staff Profile Button
                OutlinedButton(
                    onClick = onSwitchStaffProfileClicked,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, StorePrimary.copy(alpha = 0.6f))
                ) {
                    Icon(Icons.Default.SwitchAccount, contentDescription = null, tint = StorePrimary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (LanguageManager.isBengali) "স্টাফ প্রোফাইল পরিবর্তন করুন (Switch Profile)" else "Switch Active Staff / Employee Profile",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = StorePrimary
                    )
                }
            }
        }
    }

    if (showStoreQrDialog) {
        StoreQrModalDialog(
            context = context,
            storeUrl = "https://amar-dukan-40808.web.app/shop/",
            storeName = StoreInfoManager.storeName,
            onDismiss = { showStoreQrDialog = false },
            onShareLink = {
                shareStoreLink()
            }
        )
    }

    if (showPendingStaffDialog) {
        com.example.ui.components.PendingStaffAccessDialog(
            viewModel = viewModel,
            onDismiss = { showPendingStaffDialog = false }
        )
    }
}
