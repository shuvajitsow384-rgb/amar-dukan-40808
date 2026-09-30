package com.example.ui.components

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import com.example.utils.LanguageManager
import com.example.utils.PdfReceiptHelper
import com.example.utils.StoreInfoManager

@Composable
fun EditPdfFormatDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val isBn = LanguageManager.isBengali

    var upiId by remember { mutableStateOf(StoreInfoManager.merchantUpiId) }
    var payeeName by remember { mutableStateOf(StoreInfoManager.merchantPayeeName) }
    var upiSign by remember { mutableStateOf(StoreInfoManager.merchantUpiSign) }
    var showQr by remember { mutableStateOf(StoreInfoManager.showQrOnPdf) }
    var paperSize by remember { mutableStateOf(StoreInfoManager.pdfPaperSize) }
    var footerNote by remember { mutableStateOf(StoreInfoManager.customFooterNote) }
    var headerColor by remember { mutableStateOf(StoreInfoManager.pdfHeaderColor) }
    var billLanguage by remember { mutableStateOf(StoreInfoManager.billLanguage) }

    val colorPresets = listOf(
        "#1D6C31" to "Emerald Green",
        "#141E46" to "Navy Blue",
        "#005AC1" to "Royal Blue",
        "#B3261E" to "Crimson Red",
        "#6750A4" to "Purple Accent",
        "#212121" to "Classic Dark"
    )

    // Generate live QR preview
    val previewQrBitmap = remember(upiId, payeeName, upiSign) {
        if (upiId.isNotBlank()) {
            val upiUrl = StoreInfoManager.buildUpiPayUrl(
                upiId = upiId,
                payeeName = payeeName.ifBlank { StoreInfoManager.storeName },
                amount = 100.00,
                note = "Test Payment",
                sign = upiSign
            )
            PdfReceiptHelper.generateQrCodeBitmap(upiUrl, 200, 200)
        } else null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.QrCode2, contentDescription = null, tint = StorePrimary)
                Text(
                    text = if (isBn) "পিডিএফ ফরম্যাট ও মার্চেন্ট QR সিলেক্ট করুন" else "PDF Format & Merchant QR",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Section 1: Merchant QR Payment Settings
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceWarm),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Payment, contentDescription = null, tint = StoreGreenProfit, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isBn) "মার্চেন্ট UPI QR কোড" else "Merchant Payment QR Code",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = TextDark
                                )
                            }
                            Switch(
                                checked = showQr,
                                onCheckedChange = { showQr = it },
                                modifier = Modifier.height(24.dp)
                            )
                        }

                        if (showQr) {
                            OutlinedTextField(
                                value = upiId,
                                onValueChange = { input ->
                                    val (parsedUpi, parsedPayee, parsedSign) = StoreInfoManager.parseUpiLink(input)
                                    if (parsedUpi.isNotBlank()) {
                                        upiId = parsedUpi
                                        if (parsedPayee.isNotBlank()) payeeName = parsedPayee
                                        if (parsedSign.isNotBlank()) upiSign = parsedSign
                                    } else {
                                        upiId = input
                                    }
                                },
                                label = { Text(if (isBn) "মার্চেন্ট / পার্সোনাল UPI ID *" else "Merchant / Personal UPI ID *") },
                                placeholder = { Text("e.g. 9609319228-1@okbizaxis or 9609319228@oksbi") },
                                singleLine = false,
                                maxLines = 2,
                                modifier = Modifier.fillMaxWidth(),
                                leadingIcon = { Icon(Icons.Default.QrCode, contentDescription = null) }
                            )

                            // Informational tip about NPCI self-payment rule
                            Surface(
                                color = StorePrimary.copy(alpha = 0.08f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Info,
                                        contentDescription = null,
                                        tint = StorePrimary,
                                        modifier = Modifier.size(16.dp).padding(top = 1.dp)
                                    )
                                    Text(
                                        text = if (isBn)
                                            "টিপ: গুগল পে বিজনেস (@okbizaxis) বা পার্সোনাল (@oksbi/@ybl) যেকোনো UPI আইডি ব্যবহার করতে পারেন। ব্যাংক নিয়ম অনুযায়ী নিজের ব্যাংক অ্যাকাউন্ট থেকে নিজের মার্চেন্ট QR-এ টেস্ট পেমেন্ট করা যায় না, তবে যেকোনো গ্রাহক অনায়াসে পেমেন্ট করতে পারবেন।"
                                        else
                                            "Tip: You can use Business UPI (@okbizaxis) or Personal UPI (@oksbi/@ybl). Note: Indian banks block self-payments when testing from your own bank account to your own Business VPA. Real customers can pay seamlessly.",
                                        fontSize = 11.sp,
                                        color = TextDark,
                                        lineHeight = 15.sp
                                    )
                                }
                            }

                            OutlinedTextField(
                                value = payeeName,
                                onValueChange = { payeeName = it },
                                label = { Text("Payee / Store Display Name") },
                                placeholder = { Text(StoreInfoManager.storeName) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                leadingIcon = { Icon(Icons.Default.Storefront, contentDescription = null) }
                            )

                            // Live QR Code Preview
                            if (previewQrBitmap != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                                    shape = RoundedCornerShape(8.dp),
                                    border = CardDefaults.outlinedCardBorder()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Image(
                                            bitmap = previewQrBitmap.asImageBitmap(),
                                            contentDescription = "UPI QR Preview",
                                            modifier = Modifier.size(70.dp)
                                        )
                                        Column {
                                            Text("LIVE QR PREVIEW", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = StoreGreenProfit)
                                            Text(upiId, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextDark)
                                            Text("GPay • PhonePe • Paytm • BHIM", fontSize = 10.sp, color = TextMuted)
                                            Text("Scans bill amount directly", fontSize = 9.sp, color = TextMuted)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Section 2: Paper Size & Format Selection
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceWarm),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = if (isBn) "রসিদের থার্মাল পেপার সাইজ" else "Thermal Paper Size & Format",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = TextDark
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val is80mm = paperSize == "THERMAL_80MM" || paperSize == "A4"
                            FilterChip(
                                selected = is80mm,
                                onClick = { paperSize = "THERMAL_80MM" },
                                label = { Text("80mm Thermal Roll") },
                                leadingIcon = { Icon(Icons.Default.ReceiptLong, contentDescription = null) },
                                modifier = Modifier.weight(1f)
                            )

                            FilterChip(
                                selected = paperSize == "THERMAL_58MM",
                                onClick = { paperSize = "THERMAL_58MM" },
                                label = { Text("58mm Mini Thermal") },
                                leadingIcon = { Icon(Icons.Default.Print, contentDescription = null) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // Section 3: Header Color Accent
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceWarm),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = if (isBn) "হেডার কালার থিম" else "Header Accent Color",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = TextDark
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            colorPresets.forEach { (hex, name) ->
                                val colorInt = try { Color(android.graphics.Color.parseColor(hex)) } catch (e: Exception) { StorePrimary }
                                val isSelected = headerColor.equals(hex, ignoreCase = true)

                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(colorInt)
                                        .border(
                                            width = if (isSelected) 3.dp else 1.dp,
                                            color = if (isSelected) StoreGold else Color.Gray,
                                            shape = CircleShape
                                        )
                                        .clickable { headerColor = hex },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(Icons.Default.Check, contentDescription = name, tint = Color.White, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                // Section 4: Bill Language Selection
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceWarm),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = if (isBn) "রসিদের ভাষা (Receipt Language)" else "Bill / Receipt Language",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = TextDark
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val isBillBn = billLanguage.equals("BN", ignoreCase = true) || billLanguage.equals("BANGLA", ignoreCase = true) || billLanguage.equals("BENGALI", ignoreCase = true)
                            FilterChip(
                                selected = isBillBn,
                                onClick = { billLanguage = "BN" },
                                label = { Text("বাংলা (Bengali)", fontSize = 11.sp, fontWeight = if (isBillBn) FontWeight.Bold else FontWeight.Normal) },
                                leadingIcon = { if (isBillBn) Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) else null },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = !isBillBn,
                                onClick = { billLanguage = "EN" },
                                label = { Text("English", fontSize = 11.sp, fontWeight = if (!isBillBn) FontWeight.Bold else FontWeight.Normal) },
                                leadingIcon = { if (!isBillBn) Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) else null },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // Section 5: Custom Footer Message
                OutlinedTextField(
                    value = footerNote,
                    onValueChange = { footerNote = it },
                    label = { Text("Custom Footer Note / Thank You Slogan") },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.FormatQuote, contentDescription = null) }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    StoreInfoManager.updatePdfFormatSettings(
                        upiId = upiId,
                        payeeName = payeeName,
                        showQr = showQr,
                        paperSize = paperSize,
                        footerNote = footerNote,
                        headerColor = headerColor,
                        upiSign = upiSign,
                        context = context
                    )
                    StoreInfoManager.updateLanguagePreferences(
                        billLang = billLanguage,
                        smsLang = StoreInfoManager.smsLanguage,
                        context = context
                    )
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = StorePrimary)
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (isBn) "সেভ করুন" else "Save Settings")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (isBn) "বাতিল" else "Cancel")
            }
        }
    )
}
