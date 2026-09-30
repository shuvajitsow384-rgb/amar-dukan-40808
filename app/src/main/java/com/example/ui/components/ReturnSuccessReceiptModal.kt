package com.example.ui.components

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.local.entities.SaleReturnWithItems
import com.example.ui.theme.*
import com.example.utils.LanguageManager
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ReturnSuccessReceiptModal(
    returnWithItems: SaleReturnWithItems,
    onDismiss: () -> Unit,
    onPrintThermal: () -> Unit,
    printerStatusMessage: String? = null
) {
    val context = LocalContext.current
    val isBn = LanguageManager.isBengali
    val saleReturn = returnWithItems.saleReturn
    val items = returnWithItems.items
    val returnedItems = items.filter { !it.isReplacement }
    val replacementItems = items.filter { it.isReplacement }
    val isReplacement = saleReturn.type.equals("REPLACEMENT", ignoreCase = true)

    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()) }
    val dateStr = remember(saleReturn.datetime) { dateFormat.format(Date(saleReturn.datetime)) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.88f)
                .padding(8.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Success Badge Icon
                Surface(
                    shape = CircleShape,
                    color = if (isReplacement) StorePrimary.copy(alpha = 0.15f) else StoreRedAlert.copy(alpha = 0.15f),
                    border = BorderStroke(2.dp, if (isReplacement) StorePrimary else StoreRedAlert),
                    modifier = Modifier.size(54.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isReplacement) Icons.Default.SwapHoriz else Icons.Default.AssignmentReturn,
                            contentDescription = null,
                            tint = if (isReplacement) StorePrimary else StoreRedAlert,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = if (isReplacement) {
                        if (isBn) "পরিবর্তন সফল হয়েছে!" else "Replacement Successful!"
                    } else {
                        if (isBn) "পণ্য ফেরত সম্পন্ন হয়েছে!" else "Return Processed Successfully!"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = TextDark,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "Voucher #${saleReturn.id} • Ref Bill #${saleReturn.saleId.takeLast(8)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Receipt Content Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                    border = BorderStroke(1.dp, TextMuted.copy(alpha = 0.2f))
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Date & Customer Info
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (isBn) "তারিখ ও সময়:" else "Date & Time:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted
                                )
                                Text(
                                    text = dateStr,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextDark
                                )
                            }

                            if (!saleReturn.customerName.isNullOrBlank()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = if (isBn) "গ্রাহকের নাম:" else "Customer:",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMuted
                                    )
                                    Text(
                                        text = saleReturn.customerName,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextDark
                                    )
                                }
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp), color = TextMuted.copy(alpha = 0.2f))
                        }

                        // Returned Items Header
                        if (returnedItems.isNotEmpty()) {
                            item {
                                Text(
                                    text = if (isBn) "ফেরত নেওয়া পণ্য (Returned Items):" else "Returned Items:",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = StoreRedAlert
                                )
                            }

                            items(returnedItems) { rItem ->
                                val name = if (isBn && rItem.productNameBn.isNotBlank()) rItem.productNameBn else rItem.productNameEn
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = name, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = TextDark)
                                        val qtyStr = if (rItem.quantity % 1.0 == 0.0) "${rItem.quantity.toInt()} ${rItem.unitType}" else "%.2f ${rItem.unitType}".format(rItem.quantity)
                                        Text(text = "$qtyStr @ ₹%.2f".format(rItem.unitPrice), fontSize = 11.sp, color = TextMuted)
                                    }
                                    Text(
                                        text = "-₹%.2f".format(rItem.subtotal),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = StoreRedAlert
                                    )
                                }
                            }
                        }

                        // Replacement Items Header
                        if (replacementItems.isNotEmpty()) {
                            item {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (isBn) "নতুন দেওয়া পণ্য (Replacement Items):" else "Replacement Items Given:",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = StorePrimary
                                )
                            }

                            items(replacementItems) { repItem ->
                                val name = if (isBn && repItem.productNameBn.isNotBlank()) repItem.productNameBn else repItem.productNameEn
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = name, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = TextDark)
                                        val qtyStr = if (repItem.quantity % 1.0 == 0.0) "${repItem.quantity.toInt()} ${repItem.unitType}" else "%.2f ${repItem.unitType}".format(repItem.quantity)
                                        Text(text = "$qtyStr @ ₹%.2f".format(repItem.unitPrice), fontSize = 11.sp, color = TextMuted)
                                    }
                                    Text(
                                        text = "+₹%.2f".format(repItem.subtotal),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = StorePrimary
                                    )
                                }
                            }
                        }

                        // Financial Summary Section
                        item {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp), color = TextMuted.copy(alpha = 0.2f))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = if (isBn) "মোট ফেরত মূল্য:" else "Total Returned Value:", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                Text(text = "₹%.2f".format(saleReturn.totalReturnedAmount), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, color = StoreRedAlert)
                            }

                            if (saleReturn.totalReplacementAmount > 0.0) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(text = if (isBn) "নতুন পণ্যের মূল্য:" else "Total Replacement Value:", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                    Text(text = "₹%.2f".format(saleReturn.totalReplacementAmount), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, color = StorePrimary)
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (saleReturn.netAmount > 0) {
                                        if (isBn) "গ্রাহককে রিফান্ড (Refund):" else "Refund to Customer:"
                                    } else if (saleReturn.netAmount < 0) {
                                        if (isBn) "গ্রাহকের থেকে নেওয়া হয়েছে:" else "Collected from Customer:"
                                    } else {
                                        if (isBn) "সমান বিনিময় (Even):" else "Even Exchange:"
                                    },
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = TextDark
                                )

                                Text(
                                    text = "₹%.2f (${saleReturn.refundPaymentMode})".format(kotlin.math.abs(saleReturn.netAmount)),
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 15.sp,
                                    color = if (saleReturn.netAmount > 0) StoreRedAlert else if (saleReturn.netAmount < 0) StoreGreenProfit else StorePrimary
                                )
                            }

                            if (!saleReturn.notes.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Note: ${saleReturn.notes}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted
                                )
                            }
                        }
                    }
                }

                if (!printerStatusMessage.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = printerStatusMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = StorePrimary
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // WhatsApp Share
                    OutlinedButton(
                        onClick = {
                            val shareText = buildString {
                                appendLine("📦 *AMAR DUKAN - RETURN & EXCHANGE VOUCHER*")
                                appendLine("Voucher #${saleReturn.id} • Ref Bill #${saleReturn.saleId.takeLast(8)}")
                                appendLine("Date: $dateStr")
                                if (!saleReturn.customerName.isNullOrBlank()) appendLine("Customer: ${saleReturn.customerName}")
                                appendLine("--------------------------------")
                                if (returnedItems.isNotEmpty()) {
                                    appendLine("*Returned Items:*")
                                    returnedItems.forEach {
                                        appendLine("• ${it.productNameEn} x${it.quantity} = ₹%.2f".format(it.subtotal))
                                    }
                                }
                                if (replacementItems.isNotEmpty()) {
                                    appendLine("*Replacement Items:*")
                                    replacementItems.forEach {
                                        appendLine("• ${it.productNameEn} x${it.quantity} = ₹%.2f".format(it.subtotal))
                                    }
                                }
                                appendLine("--------------------------------")
                                if (saleReturn.netAmount > 0) {
                                    appendLine("Refund to Customer: ₹%.2f via ${saleReturn.refundPaymentMode}".format(saleReturn.netAmount))
                                } else if (saleReturn.netAmount < 0) {
                                    appendLine("Collected from Customer: ₹%.2f via ${saleReturn.refundPaymentMode}".format(kotlin.math.abs(saleReturn.netAmount)))
                                } else {
                                    appendLine("Even Exchange (₹0.00)")
                                }
                                if (!saleReturn.notes.isNullOrBlank()) appendLine("Note: ${saleReturn.notes}")
                                appendLine("Thank you!")
                            }

                            val sendIntent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, shareText)
                                type = "text/plain"
                            }
                            val shareIntent = Intent.createChooser(sendIntent, "Share Return Voucher")
                            context.startActivity(shareIntent)
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isBn) "শেয়ার" else "Share", fontSize = 12.sp)
                    }

                    // Print Receipt Button
                    Button(
                        onClick = onPrintThermal,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = StorePrimary),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isBn) "প্রিন্ট স্লিপ" else "Print Voucher", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    // Done / Close Button
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(0.8f),
                        colors = ButtonDefaults.buttonColors(containerColor = TextDark),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(if (isBn) "ঠিক আছে" else "Done", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
