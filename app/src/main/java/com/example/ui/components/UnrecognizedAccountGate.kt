package com.example.ui.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.firestore.FirestoreUserRole
import com.example.data.firestore.PERMANENT_ADMIN_EMAILS
import com.example.ui.theme.*
import com.example.utils.LanguageManager
import com.example.viewmodel.StoreViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun UnrecognizedAccountGate(
    userRole: FirestoreUserRole,
    viewModel: StoreViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isBengali = LanguageManager.isBengali
    val authUser by viewModel.currentUser.collectAsState()
    var isChecking by remember { mutableStateOf(false) }

    val sdf = remember { SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()) }
    val firstSignInStr = remember(userRole.createdAt) {
        if (userRole.createdAt > 0) sdf.format(Date(userRole.createdAt)) else "Just now"
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A)) // High-contrast security backdrop
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            border = BorderStroke(1.5.dp, StoreRedPrimary.copy(alpha = 0.5f)),
            elevation = CardDefaults.cardElevation(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Shield / Blocked Icon
                Surface(
                    shape = CircleShape,
                    color = StoreRedPrimary.copy(alpha = 0.15f),
                    border = BorderStroke(2.dp, StoreRedPrimary.copy(alpha = 0.6f)),
                    modifier = Modifier.size(72.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.NoAccounts,
                            contentDescription = "Access Restricted",
                            tint = StoreRedPrimary,
                            modifier = Modifier.size(38.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Title
                Text(
                    text = if (isBengali) "অননুমোদিত অ্যাকাউন্ট — প্রবেশ নিষেধ" else "Access Restricted — Unrecognized Account",
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Subtitle
                Text(
                    text = if (isBengali)
                        "এই Google অ্যাকাউন্টটি স্টোরের অনুমোদিত কর্মী তালিকায় অন্তর্ভুক্ত নেই। জিরো-অ্যাক্সেস নীতি অনুযায়ী ডেটা গোপন রাখা হয়েছে।"
                    else
                        "This Google account has not been authorized as a staff member or administrator for this store. Zero access is granted by default.",
                    fontSize = 13.sp,
                    color = Color(0xFF94A3B8),
                    textAlign = TextAlign.Center,
                    lineHeight = 19.sp
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Account Information Box
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF0F172A),
                    border = BorderStroke(1.dp, Color(0xFF334155))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.AccountCircle,
                                contentDescription = null,
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = authUser?.email ?: userRole.email ?: "Unknown Email",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = Color(0xFF1E293B), thickness = 1.dp)
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isBengali) "বর্তমান স্ট্যাটাস:" else "Permission Status:",
                                fontSize = 12.sp,
                                color = Color(0xFF94A3B8)
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = StoreRedPrimary.copy(alpha = 0.2f),
                                border = BorderStroke(1.dp, StoreRedPrimary)
                            ) {
                                Text(
                                    text = if (isBengali) "⛔ জিরো এক্সেস (নিষিদ্ধ)" else "⛔ Zero Access Granted",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFCA5A5),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isBengali) "লগইন চেষ্টা:" else "Sign-in Attempted:",
                                fontSize = 12.sp,
                                color = Color(0xFF94A3B8)
                            )
                            Text(
                                text = firstSignInStr,
                                fontSize = 11.5.sp,
                                color = Color(0xFFCBD5E1)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Owner Contact Banner
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = StoreGold.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, StoreGold.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = StoreGold,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = if (isBengali) "স্টোর ওনারের সাথে যোগাযোগ করুন" else "Contact Store Owner",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = StoreGold
                            )
                            Text(
                                text = "${PERMANENT_ADMIN_EMAILS.first()}",
                                fontSize = 11.sp,
                                color = Color(0xFFE2E8F0)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Action Buttons
                Button(
                    onClick = {
                        isChecking = true
                        viewModel.refreshFirestoreSync()
                        Toast.makeText(
                            context,
                            if (isBengali) "স্ট্যাটাস রিফ্রেশ করা হচ্ছে..." else "Refreshing authorization status...",
                            Toast.LENGTH_SHORT
                        ).show()
                        isChecking = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = StorePrimary)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isBengali) "অনুমোদন স্ট্যাটাস রিচেক করুন" else "Check Approval Status",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedButton(
                    onClick = {
                        viewModel.signOut()
                        Toast.makeText(
                            context,
                            if (isBengali) "সফলভাবে সাইন আউট করা হয়েছে" else "Signed out successfully",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFF64748B)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Icon(Icons.Default.Logout, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFFF87171))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isBengali) "সাইন আউট / অন্য অ্যাকাউন্টে যান" else "Sign Out / Switch Account",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.5.sp,
                        color = Color.White
                    )
                }
            }
        }
    }
}
