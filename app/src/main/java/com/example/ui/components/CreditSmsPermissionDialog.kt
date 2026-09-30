package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.*
import com.example.utils.LanguageManager
import com.example.utils.ThemeManager
import com.example.viewmodel.PendingCreditSms

@Composable
fun CreditSmsPermissionDialog(
    pendingSms: PendingCreditSms,
    isBn: Boolean = LanguageManager.isBengali,
    onRequestPermission: () -> Unit,
    onDismiss: () -> Unit
) {
    val isDark = ThemeManager.isDarkMode()
    val primaryAccent = StorePrimary

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 500.dp)
                .padding(vertical = 16.dp),
            shape = RoundedCornerShape(20.dp),
            color = CardBackground,
            shadowElevation = 10.dp,
            border = BorderStroke(1.5.dp, primaryAccent.copy(alpha = 0.6f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                // Header Icon & Title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        shape = CircleShape,
                        color = primaryAccent.copy(alpha = 0.12f),
                        modifier = Modifier.size(46.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Sms,
                                contentDescription = "SMS Permission",
                                tint = primaryAccent,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isBn) "স্বয়ংক্রিয় বাকি এসএমএস অনুমতি" else "Automated Credit SMS Permission",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextDark
                        )
                        Text(
                            text = if (isBn) "বাকি ক্রয়ের সাথে সাথে এসএমএস প্রেরণ" else "Instant SMS Receipt on Credit Sale",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Explanation Banner
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isDark) Color(0xFF1E293B) else Color(0xFFEFF6FF)
                    ),
                    border = BorderStroke(1.dp, if (isDark) Color(0xFF334155) else Color(0xFFBFDBFE))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = Color(0xFF2563EB),
                            modifier = Modifier
                                .size(20.dp)
                                .padding(top = 2.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (isBn) {
                                "যখন কোনো গ্রাহক বাকি বা আংশিক মূল্যে কেনাকাটা করেন, অ্যাপটি সরাসরি সিম থেকে বিল ও মোট বকেয়ার হিসাব গ্রাহকের ফোনে এসএমএস পাঠিয়ে দেয়। এটি কাজ করতে একবার SMS প্রেরণের অনুমতি দিন।"
                            } else {
                                "When a sale is completed with unpaid credit balance, this app can automatically send an instant SMS receipt with bill totals and total outstanding dues directly to the customer's phone without requiring any taps."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isDark) Color(0xFFE2E8F0) else Color(0xFF1E3A8A),
                            lineHeight = 18.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Customer & Target Info
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isDark) Color(0xFF182234) else Color(0xFFF8FAFC)
                    ),
                    border = BorderStroke(1.dp, BorderDivider)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (isBn) "গ্রাহক:" else "Customer:",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = pendingSms.customerName,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = TextDark
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (isBn) "মোবাইল নম্বর:" else "Phone Number:",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = pendingSms.phone?.ifBlank { "Not Provided" } ?: "Not Provided",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = if (pendingSms.phone.isNullOrBlank()) StoreRedAlert else StorePrimary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Message Preview
                Text(
                    text = if (isBn) "এসএমএস প্রিভিউ (SMS Preview):" else "Message Preview:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextDark
                )

                Spacer(modifier = Modifier.height(6.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = if (isDark) Color(0xFF0F172A) else Color(0xFFF1F5F9),
                    border = BorderStroke(1.dp, BorderDivider)
                ) {
                    Text(
                        text = pendingSms.message,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 18.sp
                        ),
                        color = if (isDark) Color(0xFF94A3B8) else Color(0xFF334155)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = if (isBn) "ℹ️ এই নোটিশটি আপনার বিক্রয় সম্পন্ন হতে কোনো বাধা দেবে না।" else "ℹ️ Your sale has already been saved and will not be interrupted.",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = TextMuted
                )

                Spacer(modifier = Modifier.height(18.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .testTag("sms_permission_dismiss_button"),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, BorderDivider)
                    ) {
                        Text(
                            text = if (isBn) "এখন নয় (Skip)" else "Skip For Now",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TextMuted
                        )
                    }

                    Button(
                        onClick = onRequestPermission,
                        modifier = Modifier
                            .weight(1.3f)
                            .height(44.dp)
                            .testTag("sms_permission_grant_button"),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = primaryAccent)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isBn) "অনুমতি দিন ও পাঠান" else "Grant & Send SMS",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}
