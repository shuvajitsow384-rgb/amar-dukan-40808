package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CreditCardOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.models.CreditLimitExceededException
import com.example.ui.theme.*
import com.example.utils.ThemeManager

@Composable
fun CreditLimitExceededWarningDialog(
    exception: CreditLimitExceededException,
    isPrivilegedUser: Boolean, // True for Owner/Admin
    isBn: Boolean,
    onDismiss: () -> Unit,
    onOverrideAndComplete: () -> Unit,
    onAuthorizeWithPin: (() -> Unit)? = null
) {
    val isDark = ThemeManager.isDarkMode()

    val warningColor = if (isPrivilegedUser) {
        if (isDark) Color(0xFFFBBF24) else Color(0xFFD97706)
    } else {
        StoreRedAlert
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 520.dp)
                .padding(vertical = 16.dp),
            shape = RoundedCornerShape(20.dp),
            color = CardBackground,
            shadowElevation = 10.dp,
            border = BorderStroke(
                1.5.dp,
                if (isPrivilegedUser) Color(0xFFF59E0B) else StoreRedAlert
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header Row with Icon and Title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        shape = CircleShape,
                        color = warningColor.copy(alpha = if (isDark) 0.22f else 0.12f),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isPrivilegedUser) Icons.Default.WarningAmber else Icons.Default.CreditCardOff,
                                contentDescription = "Credit Alert",
                                tint = warningColor,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isPrivilegedUser) {
                                if (isBn) "বাকী সীমা সতর্কতা (অনুমোদন প্রয়োজন)" else "Credit Limit Exceeded"
                            } else {
                                if (isBn) "বাকী বিক্রি বন্ধ: সীমা অতিক্রম করেছে" else "Credit Sale Blocked: Over Limit"
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = warningColor
                        )

                        Text(
                            text = if (isPrivilegedUser) {
                                if (isBn) "মালিক/অ্যাডমিন বিশেষ অনুমতি দিয়ে বিক্রি সম্পন্ন করতে পারেন" else "Store Owner/Admin can authorize override for this sale"
                            } else {
                                if (isBn) "শুধুমাত্র মালিক বা অ্যাডমিন বাকী সীমা ওভাররাইড করতে পারেন" else "Only Store Owner or Admin can override credit limits"
                            },
                            fontSize = 12.sp,
                            color = TextMuted
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = BorderDivider)
                Spacer(modifier = Modifier.height(14.dp))

                // Breakdown Card
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = SurfaceWarm,
                    border = BorderStroke(1.dp, BorderDivider),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = exception.customerName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = TextDark
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (isBn) "নির্ধারিত বাকী সীমা:" else "Approved Credit Limit:",
                                fontSize = 13.sp,
                                color = TextMuted
                            )
                            Text(
                                text = "₹%.2f".format(exception.creditLimit),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextDark
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (isBn) "পূর্বের বকেয়া:" else "Current Balance:",
                                fontSize = 13.sp,
                                color = TextMuted
                            )
                            Text(
                                text = "₹%.2f".format(exception.currentBalance),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextDark
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (isBn) "বর্তমান বিলে বাকি:" else "Requested Credit (Bill):",
                                fontSize = 13.sp,
                                color = TextMuted
                            )
                            Text(
                                text = "+ ₹%.2f".format(exception.attemptedCreditAmount),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = StoreRedAlert
                            )
                        }

                        HorizontalDivider(color = BorderDivider)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (isBn) "বিক্রির পর মোট বকেয়া:" else "New Resulting Balance:",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextDark
                            )
                            Text(
                                text = "₹%.2f".format(exception.newBalance),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = StoreRedAlert
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Info banner
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isDark) {
                        if (isPrivilegedUser) Color(0xFF382D12) else Color(0xFF3A1818)
                    } else {
                        if (isPrivilegedUser) Color(0xFFFFFBEB) else Color(0xFFFEF2F2)
                    },
                    border = BorderStroke(
                        1.dp,
                        if (isDark) {
                            if (isPrivilegedUser) Color(0xFF785B18) else Color(0xFF782525)
                        } else {
                            if (isPrivilegedUser) Color(0xFFFDE68A) else Color(0xFFFECACA)
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(text = if (isPrivilegedUser) "👑 " else "🔒 ", fontSize = 14.sp)
                        Text(
                            text = if (isPrivilegedUser) {
                                if (isBn) {
                                    "আপনি অ্যাডমিন/মালিক হিসেবে এই লেনদেনটি অনুমোদন করতে পারেন, অথবা গ্রাহককে নগদ পরিশোধ করতে বলতে পারেন।"
                                } else {
                                    "As Store Owner/Admin, you may grant explicit override to commit this credit sale, or request cash payment."
                                }
                            } else {
                                if (isBn) {
                                    "এই গ্রাহকের বাকী সীমা শেষ। মালিক বা অ্যাডমিনের সাথে যোগাযোগ করুন অথবা নগদ পেমেন্ট নির্বাচন করুন।"
                                } else {
                                    "Customer has exceeded their credit allowance. Please contact Store Owner or switch to Cash payment."
                                }
                            },
                            fontSize = 12.sp,
                            color = if (isDark) {
                                if (isPrivilegedUser) Color(0xFFFDE68A) else Color(0xFFFCA5A5)
                            } else {
                                if (isPrivilegedUser) Color(0xFF78350F) else Color(0xFF991B1B)
                            },
                            lineHeight = 16.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Action buttons
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (isPrivilegedUser) {
                        Button(
                            onClick = onOverrideAndComplete,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFD97706),
                                contentColor = Color.White
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.VerifiedUser,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isBn) "মালিক হিসেবে অনুমোদন করুন ও বিক্রি সম্পন্ন করুন" else "Owner Override & Authorize Sale",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    } else if (onAuthorizeWithPin != null) {
                        Button(
                            onClick = onAuthorizeWithPin,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFD97706),
                                contentColor = Color.White
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isBn) "মালিকের পিন দিয়ে অনুমোদন করুন" else "Authorize with Owner PIN",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = TextDark
                        ),
                        border = BorderStroke(1.dp, BorderDivider)
                    ) {
                        Text(
                            text = if (isBn) "বাতিল / নগদ পেমেন্ট করুন" else "Cancel / Pay Cash",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}
