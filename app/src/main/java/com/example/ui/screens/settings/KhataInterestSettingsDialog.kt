package com.example.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.*
import com.example.utils.LanguageManager
import com.example.utils.StoreInfoManager
import com.example.viewmodel.StoreViewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KhataInterestSettingsDialog(
    viewModel: StoreViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isBengali = LanguageManager.isBengali

    var enabled by remember { mutableStateOf(StoreInfoManager.interestEnabled) }
    var monthlyRateText by remember { mutableStateOf(StoreInfoManager.monthlyInterestRate.toString()) }
    var gracePeriodText by remember { mutableStateOf(StoreInfoManager.interestGracePeriodDays.toString()) }
    var calculationMode by remember { mutableStateOf(StoreInfoManager.interestCalculationMode) }
    var disclaimerText by remember { mutableStateOf(StoreInfoManager.interestDisclaimerText) }

    val rateValue = monthlyRateText.toDoubleOrNull() ?: 2.0
    val graceValue = gracePeriodText.toIntOrNull() ?: 45

    // Preview calculation with sample ₹1000 debt overdue by 15 days past grace
    val samplePrincipal = 1000.0
    val sampleOverdueDays = 15
    val sampleInterest = if (enabled && rateValue > 0) {
        (samplePrincipal * (rateValue / 100.0) * (sampleOverdueDays.toDouble() / 30.0))
    } else 0.0

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f)
                .padding(vertical = 16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = StoreGold.copy(alpha = 0.15f),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Percent,
                                    contentDescription = null,
                                    tint = StoreGold,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (isBengali) "বাকি ও সুদ পলিসি সেটিংস" else "Khata Late Interest Settings",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextDark
                            )
                            Text(
                                text = if (isBengali) "বিলম্ব সুদের হার ও গ্রেস পিরিয়ড কনফিগারেশন" else "Configure late interest, grace days & calculation mode",
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    // Master On/Off Switch Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (enabled) StoreGold.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        ),
                        border = BorderStroke(1.dp, if (enabled) StoreGold.copy(alpha = 0.5f) else Color.Transparent),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isBengali) "বাকি সুদের মাস্টার সুইচ" else "Enable Khata Late Interest",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextDark
                                )
                                Text(
                                    text = if (enabled) {
                                        if (isBengali) "অনুকূলিত সুদের হিসাব চালু রয়েছে" else "Interest engine is active on unpaid dues beyond grace period"
                                    } else {
                                        if (isBengali) "সম্পূর্ণ বন্ধ রয়েছে (কোনো সুদ ধার্য হবে না)" else "Disabled (No interest will be calculated or charged)"
                                    },
                                    fontSize = 12.sp,
                                    color = TextMuted
                                )
                            }
                            Switch(
                                checked = enabled,
                                onCheckedChange = { enabled = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = StoreGold,
                                    checkedTrackColor = StoreGold.copy(alpha = 0.4f)
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (enabled) {
                        // Rate & Grace Period
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedTextField(
                                value = monthlyRateText,
                                onValueChange = { monthlyRateText = it },
                                label = { Text(if (isBengali) "সুদের হার (%/মাস)" else "Rate (% / month)") },
                                leadingIcon = { Icon(Icons.Default.TrendingUp, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            )

                            OutlinedTextField(
                                value = gracePeriodText,
                                onValueChange = { gracePeriodText = it },
                                label = { Text(if (isBengali) "গ্রেস পিরিয়ড (দিন)" else "Grace Period (days)") },
                                leadingIcon = { Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Calculation Mode Selection
                        Text(
                            text = if (isBengali) "সুদ গণনার ধরন (Calculation Mode):" else "Calculation Mode:",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextDark
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Card(
                                onClick = { calculationMode = "SIMPLE" },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (calculationMode == "SIMPLE") StorePrimary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                ),
                                border = BorderStroke(
                                    width = if (calculationMode == "SIMPLE") 2.dp else 1.dp,
                                    color = if (calculationMode == "SIMPLE") StorePrimary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(
                                            selected = calculationMode == "SIMPLE",
                                            onClick = { calculationMode = "SIMPLE" },
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Simple", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (isBengali) "শুধুমাত্র মূল বাকির উপর সরল সুদ ধার্য হবে।" else "Interest only on original unpaid principal.",
                                        fontSize = 11.sp,
                                        color = TextMuted,
                                        lineHeight = 14.sp
                                    )
                                }
                            }

                            Card(
                                onClick = { calculationMode = "COMPOUNDING" },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (calculationMode == "COMPOUNDING") StorePrimary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                ),
                                border = BorderStroke(
                                    width = if (calculationMode == "COMPOUNDING") 2.dp else 1.dp,
                                    color = if (calculationMode == "COMPOUNDING") StorePrimary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(
                                            selected = calculationMode == "COMPOUNDING",
                                            onClick = { calculationMode = "COMPOUNDING" },
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Compounding", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (isBengali) "মূল বাকি + পূর্বে ধার্যকৃত অপরিশোধিত সুদের উপর চক্রবৃদ্ধি সুদ।" else "Interest accrues on principal + previous unpaid interest.",
                                        fontSize = 11.sp,
                                        color = TextMuted,
                                        lineHeight = 14.sp
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Bill & Statement Disclaimer Text
                        OutlinedTextField(
                            value = disclaimerText,
                            onValueChange = { disclaimerText = it },
                            label = { Text(if (isBengali) "বিল / স্টেটমেন্টে প্রদর্শিত পলিসি নোট" else "Bill & Statement Disclaimer Note") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 3,
                            shape = RoundedCornerShape(10.dp),
                            supportingText = {
                                Text(
                                    if (isBengali) "শর্টকোড: {rate} = সুদের হার, {grace_days} = গ্রেস দিন, {mode} = মোড"
                                    else "Tags available: {rate}, {grace_days}, {mode}",
                                    fontSize = 10.sp
                                )
                            }
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // Live Simulation Card
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Calculate, contentDescription = null, tint = StorePrimary, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isBengali) "লাইভ সিমুলেশন প্রিভিউ (Live Preview):" else "Live Calculation Preview:",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextDark
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = if (isBengali) {
                                        "উদাহরণ: ₹১,০০০ বাকি যদি ${graceValue + sampleOverdueDays} দিন পার হয় (${graceValue} দিন গ্রেস + ${sampleOverdueDays} দিন বিলম্ব):\n" +
                                        "• মূল বকেয়া: ₹১,০০০.০০\n" +
                                        "• অর্জিত সুদ: +₹${String.format(Locale.getDefault(), "%.2f", sampleInterest)} (${rateValue}%/মাস)\n" +
                                        "• সর্বমোট প্রদেয়: ₹${String.format(Locale.getDefault(), "%.2f", samplePrincipal + sampleInterest)}"
                                    } else {
                                        "Example: ₹1,000 credit unpaid after ${graceValue + sampleOverdueDays} days (${graceValue}d grace + ${sampleOverdueDays}d overdue):\n" +
                                        "• Principal Due: ₹1,000.00\n" +
                                        "• Accrued Interest: +₹${String.format(Locale.getDefault(), "%.2f", sampleInterest)} (${rateValue}%/mo, $calculationMode)\n" +
                                        "• Net Balance: ₹${String.format(Locale.getDefault(), "%.2f", samplePrincipal + sampleInterest)}"
                                    },
                                    fontSize = 11.sp,
                                    color = TextDark,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(if (isBengali) "বাতিল" else "Cancel")
                    }

                    Button(
                        onClick = {
                            val r = monthlyRateText.toDoubleOrNull() ?: 2.0
                            val g = gracePeriodText.toIntOrNull() ?: 45
                            val d = disclaimerText.trim().ifBlank {
                                "Interest of {rate}%/month applies after {grace_days} days grace period on unpaid balance."
                            }

                            viewModel.updateKhataInterestSettings(
                                enabled = enabled,
                                monthlyRate = r,
                                graceDays = g,
                                calculationMode = calculationMode,
                                applyRetroactively = StoreInfoManager.interestApplyRetroactively,
                                disclaimerText = d
                            )

                            Toast.makeText(
                                context,
                                if (isBengali) "বাকি সুদের সেটিংস সফলভাবে সেভ করা হয়েছে!" else "Khata interest settings saved & synced!",
                                Toast.LENGTH_SHORT
                            ).show()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = StorePrimary)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isBengali) "সেভ করুন" else "Save Settings", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
