package com.example.ui.components

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.local.entities.Customer
import com.example.ui.theme.*
import com.example.utils.LanguageManager
import com.example.utils.SmsHelper
import com.example.utils.StoreInfoManager
import com.example.utils.WhatsAppHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KhataSmsReminderDialog(
    customer: Customer,
    isBn: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var selectedLanguageIsBn by remember { mutableStateOf(StoreInfoManager.isSmsBengali()) }
    var selectedTemplate by remember { mutableStateOf(SmsHelper.SmsTemplateType.FRIENDLY) }
    var includeUpi by remember { mutableStateOf(StoreInfoManager.merchantUpiId.isNotBlank()) }
    var customPhone by remember { mutableStateOf(customer.phone) }
    var customUpiId by remember { mutableStateOf(StoreInfoManager.merchantUpiId) }
    var showCustomUpiField by remember { mutableStateOf(false) }

    // Generates base text when template or options change
    var messageText by remember(selectedTemplate, selectedLanguageIsBn, includeUpi, customUpiId, customer) {
        mutableStateOf(
            SmsHelper.generatePaymentReminderSms(
                customer = customer,
                isBengali = selectedLanguageIsBn,
                templateType = selectedTemplate,
                includeUpi = includeUpi,
                customUpiId = customUpiId
            )
        )
    }

    val charCount = messageText.length
    // Standard GSM 7-bit is 160 chars for 1 part, unicode/bengali or concatenated is ~70-153 chars per part
    val estimatedSmsParts = remember(messageText, selectedLanguageIsBn) {
        val maxPerPart = if (selectedLanguageIsBn) 70 else 160
        if (charCount == 0) 0 else ((charCount - 1) / maxPerPart) + 1
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Title & Close Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = StorePrimary.copy(alpha = 0.12f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Sms,
                                    contentDescription = null,
                                    tint = StorePrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = if (selectedLanguageIsBn) "বকেয়া তাগাদা এসএমএস" else "Khata Balance Due SMS",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextDark
                            )
                            Text(
                                text = if (selectedLanguageIsBn) "গ্রাহককে সরাসরি এসএমএস পাঠান" else "Generate & send pre-filled SMS",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                    }
                }

                // Customer Info Banner
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceWarm)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            PartyProfileAvatar(
                                photoUri = customer.photoUri,
                                name = customer.name,
                                size = 42.dp
                            )
                            Column {
                                Text(
                                    text = customer.name,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = TextDark
                                )
                                Text(
                                    text = if (customPhone.isNotBlank()) "Ph: $customPhone" else "No phone number",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted
                                )
                            }
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = if (selectedLanguageIsBn) "বকেয়া বাকি" else "Outstanding Due",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                            Text(
                                text = "₹%.2f".format(kotlin.math.abs(customer.balance)),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (customer.balance > 0) StoreRedPrimary else StoreGreenProfit
                            )
                        }
                    }
                }

                // Language & Template Selector Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (selectedLanguageIsBn) "বার্তা টেমপ্লেট নির্বাচন:" else "Select Message Template:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextDark
                    )

                    // Language Toggle
                    Row(
                        modifier = Modifier
                            .background(SurfaceWarm, RoundedCornerShape(8.dp))
                            .padding(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (!selectedLanguageIsBn) StorePrimary else Color.Transparent,
                            modifier = Modifier
                                .clickable { selectedLanguageIsBn = false }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "English",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (!selectedLanguageIsBn) Color.White else TextMuted
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (selectedLanguageIsBn) StorePrimary else Color.Transparent,
                            modifier = Modifier
                                .clickable { selectedLanguageIsBn = true }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "বাংলা",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedLanguageIsBn) Color.White else TextMuted
                            )
                        }
                    }
                }

                // Template Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SmsHelper.SmsTemplateType.values().forEach { template ->
                        val isSelected = selectedTemplate == template
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                selectedTemplate = template
                                messageText = SmsHelper.generatePaymentReminderSms(
                                    customer = customer,
                                    isBengali = selectedLanguageIsBn,
                                    templateType = template,
                                    includeUpi = includeUpi,
                                    customUpiId = customUpiId
                                )
                            },
                            label = {
                                Text(
                                    text = if (selectedLanguageIsBn) template.titleBn else template.titleEn,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = when (template) {
                                        SmsHelper.SmsTemplateType.FRIENDLY -> Icons.Default.SentimentSatisfied
                                        SmsHelper.SmsTemplateType.STATEMENT -> Icons.Default.ReceiptLong
                                        SmsHelper.SmsTemplateType.URGENT -> Icons.Default.PriorityHigh
                                        SmsHelper.SmsTemplateType.SHORT -> Icons.Default.ChatBubbleOutline
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = StorePrimary.copy(alpha = 0.15f),
                                selectedLabelColor = StorePrimary
                            ),
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }

                // UPI Option Toggle & Custom UPI ID
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceWarm.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
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
                                imageVector = Icons.Default.AccountBalance,
                                contentDescription = null,
                                tint = StorePrimary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = if (selectedLanguageIsBn) "অনলাইন পেমেন্টের UPI ID যোগ করুন" else "Include Store UPI Payment ID",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = TextDark
                            )
                        }

                        Switch(
                            checked = includeUpi,
                            onCheckedChange = {
                                includeUpi = it
                                messageText = SmsHelper.generatePaymentReminderSms(
                                    customer = customer,
                                    isBengali = selectedLanguageIsBn,
                                    templateType = selectedTemplate,
                                    includeUpi = it,
                                    customUpiId = customUpiId
                                )
                            },
                            modifier = Modifier.height(28.dp)
                        )
                    }

                    if (includeUpi) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (customUpiId.isNotBlank()) "UPI: $customUpiId" else "No UPI configured (Tap to enter)",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (customUpiId.isNotBlank()) StoreGreenProfit else StoreRedPrimary,
                                fontWeight = FontWeight.Medium
                            )

                            TextButton(
                                onClick = { showCustomUpiField = !showCustomUpiField },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                modifier = Modifier.height(24.dp)
                            ) {
                                Text(
                                    text = if (showCustomUpiField) "Done" else "Change UPI",
                                    fontSize = 11.sp,
                                    color = StorePrimary
                                )
                            }
                        }

                        if (showCustomUpiField) {
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = customUpiId,
                                onValueChange = {
                                    customUpiId = it
                                    messageText = SmsHelper.generatePaymentReminderSms(
                                        customer = customer,
                                        isBengali = selectedLanguageIsBn,
                                        templateType = selectedTemplate,
                                        includeUpi = includeUpi,
                                        customUpiId = it
                                    )
                                },
                                label = { Text("Merchant UPI ID (e.g. mobile@upi)", fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            )
                        }
                    }
                }

                // Editable SMS Message Box
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (selectedLanguageIsBn) "বার্তা পূর্বরূপ (সম্পাদনাযোগ্য):" else "Message Preview (Editable):",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TextDark
                        )

                        TextButton(
                            onClick = {
                                messageText = SmsHelper.generatePaymentReminderSms(
                                    customer = customer,
                                    isBengali = selectedLanguageIsBn,
                                    templateType = selectedTemplate,
                                    includeUpi = includeUpi,
                                    customUpiId = customUpiId
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                            modifier = Modifier.height(24.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(if (selectedLanguageIsBn) "পুনঃস্থাপন" else "Reset", fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    OutlinedTextField(
                        value = messageText,
                        onValueChange = { messageText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 110.dp, max = 180.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = CardBackground,
                            unfocusedContainerColor = CardBackground
                        )
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "$charCount chars • ~$estimatedSmsParts SMS part${if (estimatedSmsParts > 1) "s" else ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (charCount > 300) StoreRedPrimary else TextMuted
                        )

                        Text(
                            text = "Standard SMS rates apply",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                    }
                }

                // Customer Phone Input Field (Editable recipient number)
                OutlinedTextField(
                    value = customPhone,
                    onValueChange = { customPhone = it },
                    label = { Text(if (selectedLanguageIsBn) "প্রাপকের মোবাইল নম্বর" else "Recipient Mobile Number") },
                    leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null, modifier = Modifier.size(18.dp), tint = StorePrimary) },
                    trailingIcon = {
                        if (customPhone.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    try {
                                        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$customPhone")))
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Cannot dial", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Call, contentDescription = "Call", tint = StoreGreenProfit, modifier = Modifier.size(18.dp))
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Bottom Action Buttons
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Primary Action: Open SMS App
                    Button(
                        onClick = {
                            if (customPhone.isBlank()) {
                                Toast.makeText(context, "Please enter recipient phone number", Toast.LENGTH_SHORT).show()
                            } else {
                                SmsHelper.sendSms(context, customPhone, messageText)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = StorePrimary),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.Sms, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (selectedLanguageIsBn) "এসএমএস অ্যাপে পাঠান (SMS Send)" else "Send SMS to Customer",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }

                    // Secondary Action Row: Copy & WhatsApp
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                SmsHelper.copyToClipboard(context, messageText, "Khata Balance SMS")
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp), tint = TextDark)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (selectedLanguageIsBn) "কপি করুন" else "Copy SMS",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextDark
                            )
                        }

                        Button(
                            onClick = {
                                WhatsAppHelper.sendWhatsAppMessage(context, customPhone, messageText)
                            },
                            modifier = Modifier.weight(1.2f),
                            colors = ButtonDefaults.buttonColors(containerColor = StoreGreenProfit),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (selectedLanguageIsBn) "হোয়াটসঅ্যাপেও দিন" else "Share WhatsApp",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
