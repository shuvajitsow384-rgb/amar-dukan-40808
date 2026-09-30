package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.local.dao.SaleWithItems
import com.example.data.local.entities.Customer
import com.example.ui.theme.*
import com.example.utils.KhataInterestCalculator
import com.example.utils.LanguageManager
import com.example.utils.PdfReceiptHelper
import com.example.utils.SmsHelper
import com.example.utils.StoreInfoManager
import com.example.utils.ThemeManager
import com.example.utils.WhatsAppHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Modern Brand & UPI Colors matching Indian payment apps
private val UpiSuccessGreen = Color(0xFF0A8043)
private val UpiDarkGreen = Color(0xFF056434)
private val UpiPurpleAvatar = Color(0xFF6B21A8)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionSuccessReceiptModal(
    saleWithItems: SaleWithItems,
    customers: List<Customer> = emptyList(),
    printerStatusMessage: String = "",
    onDismiss: () -> Unit,
    onPrintThermal: () -> Unit = {},
    onPrintThermalWithLang: ((isBengali: Boolean) -> Unit)? = null,
    onNewSale: () -> Unit,
    onOpenPdfSettings: () -> Unit,
    onReturnOrReplace: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val isBn = LanguageManager.isBengali
    val isDark = ThemeManager.isDarkMode()
    val sale = saleWithItems.sale
    val items = saleWithItems.items
    var receiptIsBengali by remember { mutableStateOf(StoreInfoManager.isBillBengali()) }

    val formattedDate = remember(sale.datetime) {
        val sdf = SimpleDateFormat("hh:mm a 'on' dd MMM yyyy", Locale.getDefault())
        sdf.format(Date(sale.datetime))
    }

    val customer = remember(sale.customerId, customers) {
        customers.find { it.id == sale.customerId }
    }

    val customerDisplayName = when {
        !sale.customerName.isNullOrBlank() -> sale.customerName
        customer != null -> customer.name
        else -> if (isBn) "সাধারণ ক্রেতা" else "Walk-in Customer"
    }

    val customerPhone = remember(customer) {
        customer?.phone?.takeIf { it.isNotBlank() }
    }

    val transactionId = remember(sale.id) {
        "T" + SimpleDateFormat("yyMMddHHmmss", Locale.getDefault()).format(Date(sale.datetime)) + sale.id.takeLast(8).uppercase()
    }

    val utrNumber = remember(sale.datetime, sale.id) {
        val num = (sale.datetime % 1000000000000L).toString().padStart(12, '6')
        num.take(12)
    }

    var isTransferDetailsExpanded by remember { mutableStateOf(true) }
    var isItemsExpanded by remember { mutableStateOf(false) }

    fun copyToClipboard(label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "$label copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = SurfaceWarm
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
            ) {
                // Top Header Bar - High-Fidelity Green Header
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(UpiDarkGreen, UpiSuccessGreen)
                            )
                        )
                        .padding(horizontal = 8.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = LanguageManager.getString("Transaction Successful", "লেনদেন সফল হয়েছে"),
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = formattedDate,
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Normal
                            )
                        }

                        IconButton(
                            onClick = {
                                val pdfFile = PdfReceiptHelper.generateReceiptPdf(context, saleWithItems)
                                PdfReceiptHelper.sharePdf(context, pdfFile)
                            },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share",
                                tint = Color.White
                            )
                        }
                    }
                }

                // Scrollable Content
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Card 1: Paid to / Customer Details & Amount Banner
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBackground),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        border = BorderStroke(1.dp, BorderDivider)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Text(
                                text = LanguageManager.getString("Paid to", "প্রাপক / ক্রেতা"),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextMuted
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Avatar Circle
                                Surface(
                                    shape = CircleShape,
                                    color = UpiPurpleAvatar,
                                    modifier = Modifier.size(46.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Person,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(26.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = customerDisplayName,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextDark,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (!customerPhone.isNullOrBlank()) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = customerPhone,
                                            fontSize = 13.sp,
                                            color = TextMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                // Prominent Amount
                                Text(
                                    text = if (sale.finalAmount % 1.0 == 0.0) "₹%.0f".format(sale.finalAmount) else "₹%.2f".format(sale.finalAmount),
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = TextDark
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))
                            HorizontalDivider(color = BorderDivider)
                            Spacer(modifier = Modifier.height(10.dp))

                            // Sent to / Payment Channel
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = LanguageManager.getString("Sent via", " মাধ্যমে পাঠানো"),
                                        fontSize = 12.sp,
                                        color = TextMuted
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = ":",
                                        fontSize = 12.sp,
                                        color = TextMuted
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))

                                    // Payment Mode Badge
                                    val (modeBadgeText, modeColor) = when (sale.paymentMode) {
                                        "UPI" -> "G Pay / UPI" to Color(0xFF2563EB)
                                        "CREDIT" -> (if (isBn) "বাকী (Credit)" else "Khata Credit") to Color(0xFFEA580C)
                                        else -> (if (isBn) "নগদ (Cash)" else "Cash POS") to UpiSuccessGreen
                                    }

                                    Surface(
                                        color = modeColor.copy(alpha = if (isDark) 0.22f else 0.12f),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = modeBadgeText,
                                            color = modeColor,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }

                                if (sale.paymentMode == "UPI" && StoreInfoManager.merchantUpiId.isNotBlank()) {
                                    Text(
                                        text = "• ${StoreInfoManager.merchantUpiId}",
                                        fontSize = 12.sp,
                                        color = TextMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    // Card 2: Transfer Details & Identifiers Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBackground),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        border = BorderStroke(1.dp, BorderDivider)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            // Expandable Header
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { isTransferDetailsExpanded = !isTransferDetailsExpanded },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.ReceiptLong,
                                        contentDescription = null,
                                        tint = TextDark,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = LanguageManager.getString("Transfer Details", "লেনদেনের বিবরণ"),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextDark
                                    )
                                }

                                Icon(
                                    imageVector = if (isTransferDetailsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Expand",
                                    tint = TextMuted
                                )
                            }

                            AnimatedVisibility(visible = isTransferDetailsExpanded) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 14.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    HorizontalDivider(color = BorderDivider)

                                    // Transaction ID with Copy
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = LanguageManager.getString("Transaction / Bill ID", "বিল / লেনদেন আইডি"),
                                                fontSize = 12.sp,
                                                color = TextMuted
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = transactionId,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = TextDark
                                            )
                                        }

                                        IconButton(
                                            onClick = { copyToClipboard("Transaction ID", transactionId) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ContentCopy,
                                                contentDescription = "Copy ID",
                                                tint = StorePrimary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }

                                    // Debited from / Paid with
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            // Bank/Method Icon
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = if (isDark) Color(0xFF3F1B1B) else Color(0xFFFEE2E2),
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(
                                                        imageVector = when (sale.paymentMode) {
                                                            "UPI" -> Icons.Default.QrCode
                                                            "CREDIT" -> Icons.Default.AccountBalanceWallet
                                                            else -> Icons.Default.Payments
                                                        },
                                                        contentDescription = null,
                                                        tint = StoreRedAlert,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }

                                            Spacer(modifier = Modifier.width(10.dp))

                                            Column {
                                                Text(
                                                    text = LanguageManager.getString("Debited / Payment via", "পেমেন্ট মাধ্যম"),
                                                    fontSize = 11.sp,
                                                    color = TextMuted
                                                )
                                                Text(
                                                    text = when (sale.paymentMode) {
                                                        "UPI" -> "UPI Digital / Bank"
                                                        "CREDIT" -> "Customer Khata Wallet"
                                                        else -> "Cash Register In-Store"
                                                    },
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = TextDark
                                                )
                                            }
                                        }

                                        Text(
                                            text = "₹%.2f".format(sale.receivedAmount),
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextDark
                                        )
                                    }

                                    // UTR / Reference Number
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                text = "UTR / Auth Ref: $utrNumber",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = TextMuted
                                            )
                                        }

                                        IconButton(
                                            onClick = { copyToClipboard("UTR Number", utrNumber) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ContentCopy,
                                                contentDescription = "Copy UTR",
                                                tint = StorePrimary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }

                                    // Due / Credit summary if any
                                    if (sale.dueAmount > 0 || sale.previousBalance != 0.0 || (customer != null && customer.balance != 0.0)) {
                                        HorizontalDivider(color = BorderDivider)

                                        if (sale.dueAmount > 0) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = LanguageManager.getString("Added to Due Today:", "আজকের বাকী:"),
                                                    fontSize = 12.sp,
                                                    color = Color(0xFFC2410C),
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = "₹%.2f".format(sale.dueAmount),
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFFEA580C)
                                                )
                                            }
                                        }

                                        if (sale.previousBalance != 0.0) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = if (sale.previousBalance > 0)
                                                        LanguageManager.getString("Previous Balance:", "পূর্বের বাকী:")
                                                    else
                                                        LanguageManager.getString("Previous Advance:", "পূর্বের অগ্রিম জমা:"),
                                                    fontSize = 12.sp,
                                                    color = TextMuted
                                                )
                                                Text(
                                                    text = "₹%.2f".format(kotlin.math.abs(sale.previousBalance)),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = if (sale.previousBalance > 0) TextDark else UpiSuccessGreen
                                                )
                                            }
                                        }

                                        val currentCustBalance = customer?.balance ?: (sale.previousBalance + sale.dueAmount - (sale.receivedAmount - sale.finalAmount).coerceAtLeast(0.0))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = if (currentCustBalance < 0)
                                                    LanguageManager.getString("Customer Advance Credit (Khata):", "গ্রাহকের অগ্রিম জমা (খাতা):")
                                                else
                                                    LanguageManager.getString("Total Outstanding Balance:", "মোট বকেয়া বাকী:"),
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (currentCustBalance < 0) UpiSuccessGreen else TextDark
                                            )
                                            Text(
                                                text = "₹%.2f".format(kotlin.math.abs(currentCustBalance)),
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = if (currentCustBalance < 0) UpiSuccessGreen else if (currentCustBalance > 0) StoreRedAlert else TextDark
                                            )
                                        }

                                        // Due Date & Interest Policy Disclaimer Banner
                                        if (currentCustBalance > 0 || sale.dueAmount > 0) {
                                            val interestSettings = remember { StoreInfoManager.getKhataInterestSettings() }
                                            val effectiveGraceDays = customer?.customGracePeriodDays ?: interestSettings.gracePeriodDays
                                            val dueDateMs = remember(sale.datetime, effectiveGraceDays) {
                                                KhataInterestCalculator.calculateDueDateTimestamp(sale.datetime, null, effectiveGraceDays)
                                            }
                                            val formattedDueDate = remember(dueDateMs, effectiveGraceDays, isBn) {
                                                KhataInterestCalculator.formatDueDate(dueDateMs, effectiveGraceDays, isBn)
                                            }
                                            val dueDisclaimer = remember(interestSettings, customer, isBn, dueDateMs, currentCustBalance) {
                                                KhataInterestCalculator.formatDisclaimer(
                                                    settings = interestSettings,
                                                    customer = customer,
                                                    isBengali = isBn,
                                                    dueDateMs = dueDateMs,
                                                    dueAmount = if (currentCustBalance > 0) currentCustBalance else sale.dueAmount
                                                )
                                            }

                                            Surface(
                                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                                color = Color(0xFFFFF7ED),
                                                shape = RoundedCornerShape(8.dp),
                                                border = BorderStroke(1.dp, Color(0xFFFDBA74))
                                            ) {
                                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(
                                                            imageVector = Icons.Default.Event,
                                                            contentDescription = null,
                                                            tint = Color(0xFFC2410C),
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text(
                                                            text = formattedDueDate,
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color(0xFF9A3412)
                                                        )
                                                    }
                                                    if (dueDisclaimer.isNotBlank()) {
                                                        Text(
                                                            text = "⚠️ $dueDisclaimer",
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Medium,
                                                            color = Color(0xFFC2410C),
                                                            lineHeight = 15.sp
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Card 3: Items Breakdown (Collapsible)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBackground),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        border = BorderStroke(1.dp, BorderDivider)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { isItemsExpanded = !isItemsExpanded },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.ShoppingBag,
                                        contentDescription = null,
                                        tint = StorePrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "${items.size} ${if (isBn) "আইটেম ক্রয়কৃত" else "Items Purchased"}",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextDark
                                    )
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = if (isItemsExpanded) "Hide" else "View",
                                        fontSize = 12.sp,
                                        color = StorePrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Icon(
                                        imageVector = if (isItemsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = StorePrimary
                                    )
                                }
                            }

                            AnimatedVisibility(visible = isItemsExpanded) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    HorizontalDivider(color = BorderDivider)

                                    items.forEachIndexed { idx, item ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "${idx + 1}. ${item.productNameEn.ifBlank { item.productNameBn }}",
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = TextDark
                                                )
                                                val qtyStr = if (item.quantity % 1.0 == 0.0) "${item.quantity.toInt()} ${item.unitType}" else "%.2f ${item.unitType}".format(item.quantity)
                                                
                                                if (item.hasDiscount()) {
                                                    Text(
                                                        text = "$qtyStr @ ₹%.2f".format(item.unitPrice),
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        color = TextMuted
                                                    )
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                        modifier = Modifier.padding(top = 1.dp)
                                                    ) {
                                                        Text(
                                                            text = "MRP: ₹%.2f".format(item.getEffectiveMrp()),
                                                            fontSize = 10.5.sp,
                                                            style = MaterialTheme.typography.bodySmall.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough),
                                                            color = TextMuted
                                                        )
                                                        if (item.getSavingsAmount() > 0) {
                                                            Surface(
                                                                color = StoreGreenProfit.copy(alpha = 0.12f),
                                                                shape = RoundedCornerShape(3.dp)
                                                            ) {
                                                                Text(
                                                                    text = "Save ₹%.2f".format(item.getSavingsAmount()),
                                                                    fontSize = 9.5.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = StoreGreenProfit,
                                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                                )
                                                            }
                                                        }
                                                    }
                                                } else {
                                                    Text(
                                                        text = "$qtyStr @ ₹%.2f".format(item.unitPrice),
                                                        fontSize = 11.sp,
                                                        color = TextMuted
                                                    )
                                                }
                                            }
                                            Text(
                                                text = "₹%.2f".format(item.subtotal),
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = TextDark
                                            )
                                        }
                                    }

                                    val totalMrpSum = items.sumOf { it.getMrpSubtotal() }
                                    val totalItemSavings = items.sumOf { it.getSavingsAmount() }
                                    val grandTotalSavings = totalItemSavings + sale.discount

                                    if (grandTotalSavings > 0) {
                                        HorizontalDivider(color = BorderDivider)
                                        if (totalMrpSum > sale.totalAmount) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text("Total MRP Value:", fontSize = 12.sp, color = TextMuted)
                                                Text("₹%.2f".format(totalMrpSum), fontSize = 12.sp, color = TextMuted)
                                            }
                                        }
                                        if (sale.discount > 0) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text("Bill Discount Applied:", fontSize = 12.sp, color = StoreGreenProfit, fontWeight = FontWeight.Bold)
                                                Text("-₹%.2f".format(sale.discount), fontSize = 12.sp, color = StoreGreenProfit, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("Customer Total Savings:", fontSize = 12.sp, color = StoreGreenProfit, fontWeight = FontWeight.Bold)
                                            Text("₹%.2f".format(grandTotalSavings), fontSize = 12.sp, color = StoreGreenProfit, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Circular Quick Actions Grid (Exactly matching Indian UPI screens)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBackground),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        border = BorderStroke(1.dp, BorderDivider)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp, horizontal = 8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            // 1. Next Sale
                            UpiCircleActionButton(
                                icon = Icons.Default.AddShoppingCart,
                                label = LanguageManager.getString("New Sale", "নতুন বিক্রয়"),
                                tintColor = StorePrimary,
                                onClick = onNewSale
                            )

                            // 2. Print Thermal
                            UpiCircleActionButton(
                                icon = Icons.Default.Print,
                                label = LanguageManager.getString("Thermal Print", "থার্মাল প্রিন্ট"),
                                tintColor = Color(0xFF7C3AED),
                                onClick = {
                                    if (onPrintThermalWithLang != null) {
                                        onPrintThermalWithLang(receiptIsBengali)
                                    } else {
                                        onPrintThermal()
                                    }
                                }
                            )

                            // 3. Share PDF
                            UpiCircleActionButton(
                                icon = Icons.Default.PictureAsPdf,
                                label = LanguageManager.getString("PDF Bill", "পিডিএফ বিল"),
                                tintColor = Color(0xFF2563EB),
                                onClick = {
                                    val pdfFile = PdfReceiptHelper.generateReceiptPdf(context, saleWithItems)
                                    PdfReceiptHelper.printPdf(context, pdfFile, "Receipt_${sale.id}")
                                }
                            )

                            // 4. WhatsApp Share
                            UpiCircleActionButton(
                                icon = Icons.AutoMirrored.Filled.Send,
                                label = LanguageManager.getString("WhatsApp", "হোয়াটসঅ্যাপ"),
                                tintColor = Color(0xFF16A34A),
                                onClick = {
                                    val msg = WhatsAppHelper.generateBillMessage(saleWithItems, receiptIsBengali)
                                    val phone = customer?.phone
                                    WhatsAppHelper.sendWhatsAppMessage(context, phone, msg)
                                }
                            )
                        }
                    }

                    // Direct Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val msg = SmsHelper.generateBillSms(saleWithItems, StoreInfoManager.isSmsBengali())
                                val phone = customer?.phone
                                SmsHelper.sendSms(context, phone, msg)
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, StorePrimary)
                        ) {
                            Icon(Icons.Default.Sms, contentDescription = null, modifier = Modifier.size(16.dp), tint = StorePrimary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(LanguageManager.getString("SMS Bill", "এসএমএস"), fontSize = 12.sp, color = StorePrimary, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = onOpenPdfSettings,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, BorderDivider)
                        ) {
                            Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(16.dp), tint = TextDark)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(LanguageManager.getString("PDF Format", "পিডিএফ ফরম্যাট"), fontSize = 12.sp, color = TextDark, fontWeight = FontWeight.Bold)
                        }
                    }

                    onReturnOrReplace?.let { returnAction ->
                        OutlinedButton(
                            onClick = returnAction,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, StoreRedAlert.copy(alpha = 0.5f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StoreRedAlert)
                        ) {
                            Icon(Icons.Default.AssignmentReturn, contentDescription = null, modifier = Modifier.size(16.dp), tint = StoreRedAlert)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isBn) "পণ্য ফেরত বা পরিবর্তন করুন (Return / Replace)" else "Return / Replace Items",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = StoreRedAlert
                            )
                        }
                    }

                    if (printerStatusMessage.isNotEmpty()) {
                        Surface(
                            color = StoreSaffronAccent.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = printerStatusMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = StoreSaffronAccent,
                                modifier = Modifier.padding(10.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Primary Done / Next Sale Full-width Button
                    Button(
                        onClick = onNewSale,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = UpiSuccessGreen)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = LanguageManager.getString("Done / Next Sale", "সম্পন্ন / পরবর্তী বিক্রয়"),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun UpiCircleActionButton(
    icon: ImageVector,
    label: String,
    tintColor: Color,
    onClick: () -> Unit
) {
    val isDark = ThemeManager.isDarkMode()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = tintColor.copy(alpha = if (isDark) 0.22f else 0.12f),
            modifier = Modifier.size(48.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = tintColor,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextDark,
            textAlign = TextAlign.Center
        )
    }
}
