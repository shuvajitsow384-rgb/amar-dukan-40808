package com.example.ui.screens.pos

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.local.dao.SaleWithItems
import com.example.data.local.entities.Customer
import com.example.data.local.entities.Product
import com.example.data.local.entities.ReturnItem
import com.example.data.local.entities.SaleItem
import com.example.data.local.entities.SaleReturn
import com.example.data.local.entities.SaleReturnWithItems
import com.example.ui.theme.*
import com.example.utils.LanguageManager
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReturnReplacementDialog(
    saleWithItems: SaleWithItems,
    allProducts: List<Product>,
    allReturns: List<SaleReturnWithItems> = emptyList(),
    allCustomers: List<Customer> = emptyList(),
    onDismiss: () -> Unit,
    onConfirmReturn: (SaleReturn, List<ReturnItem>) -> Unit
) {
    val isBn = LanguageManager.isBengali
    val sale = saleWithItems.sale
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()) }

    var isReplacementMode by remember { mutableStateOf(false) } // false = Return Only, true = Replacement/Exchange

    // Previous returns for this sale to avoid double-refunds
    val previousReturnsForThisSale = remember(allReturns, sale.id) {
        allReturns.filter { it.saleReturn.saleId == sale.id }
    }

    // Track returned quantities per SaleItem ID
    val returnQtys = remember { mutableStateMapOf<Long, Double>() }

    // Track replacement items added
    val replacementItems = remember { mutableStateListOf<ReplacementItemData>() }

    var selectedPaymentMode by remember { mutableStateOf(sale.paymentMode.ifBlank { "CASH" }) }
    var notesText by remember { mutableStateOf("") }

    // Optional customer linking for Khata if the sale had no customer
    var linkedCustomerId by remember { mutableStateOf<String?>(sale.customerId) }
    var linkedCustomerName by remember { mutableStateOf<String?>(sale.customerName) }
    var showCustomerPickerSheet by remember { mutableStateOf(false) }
    var customerSearchQuery by remember { mutableStateOf("") }

    // Dialog for adding a replacement item from inventory
    var showAddReplacementSheet by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // State for manual quantity input dialogs
    var itemForCustomQty by remember { mutableStateOf<SaleItem?>(null) }
    var maxQtyForCustomItem by remember { mutableStateOf(0.0) }
    var customQtyInputText by remember { mutableStateOf("") }

    var replacementItemIndexForCustomQty by remember { mutableStateOf<Int?>(null) }
    var replacementCustomQtyInputText by remember { mutableStateOf("") }

    // Calculate Totals
    val totalReturnedValue = remember(returnQtys.toMap()) {
        saleWithItems.items.sumOf { item ->
            val qty = returnQtys[item.id] ?: 0.0
            if (item.unitType.equals("gram", ignoreCase = true) || item.unitType.equals("ml", ignoreCase = true)) {
                (qty / 1000.0) * item.unitPrice
            } else {
                qty * item.unitPrice
            }
        }
    }

    val totalReplacementValue = remember(replacementItems.toList()) {
        replacementItems.sumOf { it.subtotal }
    }

    // Net Amount: positive = refund to customer; negative = extra to collect from customer
    val netAmount = totalReturnedValue - totalReplacementValue

    // Khata requirement check
    val isKhataSelected = selectedPaymentMode.equals("CREDIT", ignoreCase = true)
    val hasKhataCustomer = !linkedCustomerId.isNullOrBlank()
    val isKhataValid = !isKhataSelected || hasKhataCustomer || netAmount == 0.0

    val isFormValid = totalReturnedValue > 0.0 && isKhataValid

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.94f)
                .padding(8.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isReplacementMode) Icons.Default.SwapHoriz else Icons.Default.AssignmentReturn,
                                contentDescription = null,
                                tint = if (isReplacementMode) StorePrimary else StoreRedAlert,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isBn) "ফেরত ও পরিবর্তন (Return & Replace)" else "Return & Replacement",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = TextDark
                            )
                        }
                        Text(
                            text = "Bill #${sale.id.takeLast(8)} • ${dateFormat.format(Date(sale.datetime))} • ${linkedCustomerName ?: if (isBn) "সাধারণ গ্রাহক" else "Walk-in Customer"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Mode Selector Segmented Control (Return vs Replacement)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CardBackground, RoundedCornerShape(10.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Button(
                        onClick = { isReplacementMode = false },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (!isReplacementMode) StoreRedAlert else Color.Transparent,
                            contentColor = if (!isReplacementMode) Color.White else TextDark
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Undo, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isBn) "শুধু ফেরত (Return Only)" else "Return Only",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = { isReplacementMode = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isReplacementMode) StorePrimary else Color.Transparent,
                            contentColor = if (isReplacementMode) Color.White else TextDark
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isBn) "আইটেম বদল (Replace / Exchange)" else "Replace / Exchange",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Section 1: Purchased Items to Return
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isBn) "১. ফেরত দেওয়ার আইটেম বাছুন:" else "1. Select Items to Return:",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = StorePrimary
                            )

                            if (saleWithItems.items.isNotEmpty()) {
                                TextButton(
                                    onClick = {
                                        val allSelected = saleWithItems.items.all { item ->
                                            val alreadyReturned = previousReturnsForThisSale.flatMap { it.items }
                                                .filter { !it.isReplacement && it.productId == item.productId }
                                                .sumOf { it.quantity }
                                            val maxQty = (item.quantity - alreadyReturned).coerceAtLeast(0.0)
                                            returnQtys[item.id] == maxQty
                                        }
                                        if (allSelected) {
                                            returnQtys.clear()
                                        } else {
                                            saleWithItems.items.forEach { item ->
                                                val alreadyReturned = previousReturnsForThisSale.flatMap { it.items }
                                                    .filter { !it.isReplacement && it.productId == item.productId }
                                                    .sumOf { it.quantity }
                                                val maxQty = (item.quantity - alreadyReturned).coerceAtLeast(0.0)
                                                if (maxQty > 0.0) {
                                                    returnQtys[item.id] = maxQty
                                                }
                                            }
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    val allSelected = saleWithItems.items.all { item ->
                                        val alreadyReturned = previousReturnsForThisSale.flatMap { it.items }
                                            .filter { !it.isReplacement && it.productId == item.productId }
                                            .sumOf { it.quantity }
                                        val maxQty = (item.quantity - alreadyReturned).coerceAtLeast(0.0)
                                        returnQtys[item.id] == maxQty
                                    }
                                    Text(
                                        text = if (allSelected) (if (isBn) "সব বাদ দিন" else "Clear All") else (if (isBn) "সব ফেরত বাছুন" else "Return All Items"),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = StorePrimary
                                    )
                                }
                            }
                        }
                    }

                    items(saleWithItems.items, key = { it.id }, contentType = { "RETURN_REPLACEMENT_ITEM" }) { item ->
                        val name = if (isBn && item.productNameBn.isNotBlank()) item.productNameBn else if (item.productNameEn.isNotBlank()) item.productNameEn else "Item"
                        val curQty = returnQtys[item.id] ?: 0.0

                        val alreadyReturned = remember(previousReturnsForThisSale, item.productId) {
                            previousReturnsForThisSale.flatMap { it.items }
                                .filter { !it.isReplacement && it.productId == item.productId }
                                .sumOf { it.quantity }
                        }
                        val maxReturnableQty = (item.quantity - alreadyReturned).coerceAtLeast(0.0)
                        val isFullyReturned = maxReturnableQty <= 0.0

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isFullyReturned) CardBackground.copy(alpha = 0.5f) else if (curQty > 0.0) StoreRedAlert.copy(alpha = 0.05f) else CardBackground
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (curQty > 0.0) StoreRedAlert.copy(alpha = 0.4f) else TextMuted.copy(alpha = 0.2f)
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = name,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = if (isFullyReturned) TextMuted else TextDark
                                        )
                                        val purchasedStr = if (item.unitType.equals("gram", ignoreCase = true)) {
                                            "${item.quantity.toInt()}g"
                                        } else if (item.quantity % 1.0 == 0.0) {
                                            "${item.quantity.toInt()} ${item.unitType}"
                                        } else {
                                            "%.2f ${item.unitType}".format(item.quantity)
                                        }
                                        Text(
                                            text = "Purchased: $purchasedStr @ ₹%.2f".format(item.unitPrice),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextMuted
                                        )
                                        if (alreadyReturned > 0.0) {
                                            Text(
                                                text = if (isFullyReturned) {
                                                    if (isBn) "⚠️ ইতিমধ্যে সম্পূর্ণ ফেরত নেওয়া হয়েছে" else "⚠️ Already fully returned ($alreadyReturned)"
                                                } else {
                                                    if (isBn) "ইতিমধ্যে ফেরত: $alreadyReturned | অবশিষ্ট: $maxReturnableQty" else "Already returned: $alreadyReturned | Available: $maxReturnableQty"
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isFullyReturned) StoreRedAlert else StoreOrangeWarning,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }

                                    // Quick return full or zero toggle
                                    if (!isFullyReturned) {
                                        FilterChip(
                                            selected = curQty == maxReturnableQty && maxReturnableQty > 0,
                                            onClick = {
                                                if (curQty == maxReturnableQty) {
                                                    returnQtys[item.id] = 0.0
                                                } else {
                                                    returnQtys[item.id] = maxReturnableQty
                                                }
                                            },
                                            label = {
                                                Text(
                                                    text = if (curQty == maxReturnableQty) (if (isBn) "সম্পূর্ণ ফেরত" else "Return Full") else (if (isBn) "বাছুন" else "Select"),
                                                    fontSize = 11.sp
                                                )
                                            },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = StoreRedAlert,
                                                selectedLabelColor = Color.White
                                            )
                                        )
                                    }
                                }

                                if (!isFullyReturned) {
                                    Spacer(modifier = Modifier.height(6.dp))

                                    // Quantity adjustment controls
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(
                                                onClick = {
                                                    val step = if (item.unitType.equals("gram", ignoreCase = true) || item.unitType.equals("ml", ignoreCase = true)) 100.0
                                                    else if (item.unitType.equals("kg", ignoreCase = true) || item.unitType.equals("litre", ignoreCase = true)) 0.25
                                                    else 1.0
                                                    val newQty = (curQty - step).coerceAtLeast(0.0)
                                                    returnQtys[item.id] = Product.roundQuantity(newQty)
                                                },
                                                enabled = curQty > 0,
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(Icons.Default.RemoveCircleOutline, contentDescription = "Decrease", tint = if (curQty > 0) StoreRedAlert else TextMuted)
                                            }

                                            // Clickable quantity to edit directly
                                            Surface(
                                                onClick = {
                                                    itemForCustomQty = item
                                                    maxQtyForCustomItem = maxReturnableQty
                                                    customQtyInputText = if (curQty > 0.0) {
                                                        if (curQty % 1.0 == 0.0) curQty.toInt().toString() else curQty.toString()
                                                    } else ""
                                                },
                                                shape = RoundedCornerShape(6.dp),
                                                color = if (curQty > 0) StoreRedAlert.copy(alpha = 0.12f) else CardBackground,
                                                border = BorderStroke(1.dp, if (curQty > 0) StoreRedAlert else TextMuted.copy(alpha = 0.4f)),
                                                modifier = Modifier.padding(horizontal = 6.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = if (item.unitType.equals("gram", ignoreCase = true) || item.unitType.equals("ml", ignoreCase = true)) {
                                                            "${curQty.toInt()}g"
                                                        } else if (curQty % 1.0 == 0.0) {
                                                            "${curQty.toInt()} ${item.unitType}"
                                                        } else {
                                                            "%.2f ${item.unitType}".format(curQty)
                                                        },
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 13.sp,
                                                        color = if (curQty > 0) StoreRedAlert else TextDark
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Icon(Icons.Default.Edit, contentDescription = "Edit quantity", modifier = Modifier.size(12.dp), tint = TextMuted)
                                                }
                                            }

                                            IconButton(
                                                onClick = {
                                                    val step = if (item.unitType.equals("gram", ignoreCase = true) || item.unitType.equals("ml", ignoreCase = true)) 100.0
                                                    else if (item.unitType.equals("kg", ignoreCase = true) || item.unitType.equals("litre", ignoreCase = true)) 0.25
                                                    else 1.0
                                                    val newQty = (curQty + step).coerceAtMost(maxReturnableQty)
                                                    returnQtys[item.id] = Product.roundQuantity(newQty)
                                                },
                                                enabled = curQty < maxReturnableQty,
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(Icons.Default.AddCircleOutline, contentDescription = "Increase", tint = if (curQty < maxReturnableQty) StoreGreenProfit else TextMuted)
                                            }
                                        }

                                        val itemReturnSubtotal = if (item.unitType.equals("gram", ignoreCase = true) || item.unitType.equals("ml", ignoreCase = true)) {
                                            (curQty / 1000.0) * item.unitPrice
                                        } else {
                                            curQty * item.unitPrice
                                        }

                                        Text(
                                            text = "Refund: ₹%.2f".format(itemReturnSubtotal),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = if (curQty > 0) StoreRedAlert else TextMuted
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Section 2: Replacement Items (If Replacement Mode Enabled)
                    if (isReplacementMode) {
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isBn) "২. পরিবর্তনের জন্য নতুন পণ্য (Exchange):" else "2. Replacement Items to Give:",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = StorePrimary
                                )

                                Button(
                                    onClick = { showAddReplacementSheet = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = StorePrimary),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (isBn) "পণ্য যোগ করুন" else "Add Item", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        if (replacementItems.isEmpty()) {
                            item {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = StoreSaffronAccent.copy(alpha = 0.1f),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, StoreSaffronAccent.copy(alpha = 0.3f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Info, contentDescription = null, tint = StoreSaffronAccent)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = if (isBn) "উপরে '+ পণ্য যোগ করুন' বোতামে চাপ দিয়ে কাস্টমারকে দেওয়ার নতুন পণ্য নির্বাচন করুন।" else "Tap '+ Add Item' above to select replacement items given in exchange.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextDark
                                        )
                                    }
                                }
                            }
                        } else {
                            itemsIndexed(replacementItems, key = { index, replItem -> "${replItem.product.id}_$index" }, contentType = { _, _ -> "REPLACEMENT_ITEM_CARD" }) { index, replItem ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                                    border = BorderStroke(1.dp, StorePrimary.copy(alpha = 0.3f)),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = replItem.product.getDisplayName(isBn),
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp,
                                                    color = TextDark
                                                )
                                                Text(
                                                    text = "Rate: ₹%.2f / ${replItem.unitType} • Stock: ${replItem.product.getFormattedStockDisplay()}".format(replItem.unitPrice),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = TextMuted
                                                )
                                                if (replItem.product.currentStock < replItem.quantity) {
                                                    Text(
                                                        text = if (isBn) "⚠️ স্টকের চেয়ে বেশি (${replItem.product.currentStock})" else "⚠️ Exceeds stock (${replItem.product.currentStock})",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = StoreOrangeWarning,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
                                            }

                                            IconButton(
                                                onClick = { replacementItems.removeAt(index) },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(Icons.Default.Delete, contentDescription = "Remove", tint = StoreRedAlert)
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(6.dp))

                                        // Quantity and Subtotal controls
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                IconButton(
                                                    onClick = {
                                                        val step = if (replItem.unitType.equals("gram", ignoreCase = true) || replItem.unitType.equals("ml", ignoreCase = true)) 100.0
                                                        else if (replItem.unitType.equals("kg", ignoreCase = true) || replItem.unitType.equals("litre", ignoreCase = true)) 0.25
                                                        else 1.0
                                                        val newQty = Product.roundQuantity((replItem.quantity - step).coerceAtLeast(step))
                                                        replacementItems[index] = replItem.copy(
                                                            quantity = newQty,
                                                            subtotal = replItem.product.calculatePrice(newQty, replItem.unitType)
                                                        )
                                                    },
                                                    enabled = replItem.quantity > (if (replItem.unitType.equals("gram", ignoreCase = true)) 100.0 else if (replItem.unitType.equals("kg", ignoreCase = true)) 0.25 else 1.0),
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(Icons.Default.RemoveCircleOutline, contentDescription = "Decrease", tint = StorePrimary)
                                                }

                                                // Clickable replacement quantity
                                                Surface(
                                                    onClick = {
                                                        replacementItemIndexForCustomQty = index
                                                        replacementCustomQtyInputText = if (replItem.quantity % 1.0 == 0.0) replItem.quantity.toInt().toString() else replItem.quantity.toString()
                                                    },
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = StorePrimary.copy(alpha = 0.10f),
                                                    border = BorderStroke(1.dp, StorePrimary.copy(alpha = 0.4f)),
                                                    modifier = Modifier.padding(horizontal = 6.dp)
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        val qtyStr = if (replItem.unitType.equals("gram", ignoreCase = true)) {
                                                            "${replItem.quantity.toInt()}g"
                                                        } else if (replItem.quantity % 1.0 == 0.0) {
                                                            "${replItem.quantity.toInt()} ${replItem.unitType}"
                                                        } else {
                                                            "%.2f ${replItem.unitType}".format(replItem.quantity)
                                                        }
                                                        Text(
                                                            text = qtyStr,
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 13.sp,
                                                            color = StorePrimary
                                                        )
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Icon(Icons.Default.Edit, contentDescription = "Edit quantity", modifier = Modifier.size(12.dp), tint = StorePrimary)
                                                    }
                                                }

                                                IconButton(
                                                    onClick = {
                                                        val step = if (replItem.unitType.equals("gram", ignoreCase = true) || replItem.unitType.equals("ml", ignoreCase = true)) 100.0
                                                        else if (replItem.unitType.equals("kg", ignoreCase = true) || replItem.unitType.equals("litre", ignoreCase = true)) 0.25
                                                        else 1.0
                                                        val newQty = Product.roundQuantity(replItem.quantity + step)
                                                        replacementItems[index] = replItem.copy(
                                                            quantity = newQty,
                                                            subtotal = replItem.product.calculatePrice(newQty, replItem.unitType)
                                                        )
                                                    },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(Icons.Default.AddCircleOutline, contentDescription = "Increase", tint = StoreGreenProfit)
                                                }
                                            }

                                            Text(
                                                text = "₹%.2f".format(replItem.subtotal),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp,
                                                color = StorePrimary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Payment & Customer Khata Section
                    item {
                        Spacer(modifier = Modifier.height(10.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = CardBackground),
                            border = BorderStroke(1.dp, TextMuted.copy(alpha = 0.2f)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = if (netAmount >= 0) {
                                        if (isBn) "ফেরতের মাধ্যম (Refund Payment Mode):" else "Refund Payment Mode:"
                                    } else {
                                        if (isBn) "বাকি সংগ্রহের মাধ্যম (Extra Payment Mode):" else "Extra Payment Mode:"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf("CASH", "UPI", "CREDIT").forEach { mode ->
                                        FilterChip(
                                            selected = selectedPaymentMode == mode,
                                            onClick = { selectedPaymentMode = mode },
                                            label = {
                                                val labelStr = when (mode) {
                                                    "CASH" -> if (isBn) "নগদ (Cash)" else "Cash"
                                                    "UPI" -> if (isBn) "ইউপিআই (UPI)" else "UPI"
                                                    else -> if (isBn) "খাতায় সমন্বয় (Khata)" else "Customer Khata"
                                                }
                                                Text(labelStr, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }

                                // If CREDIT selected and no customer linked to this sale, allow selecting customer
                                if (selectedPaymentMode == "CREDIT" && netAmount != 0.0) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = if (hasKhataCustomer) StoreGreenProfit.copy(alpha = 0.08f) else StoreRedAlert.copy(alpha = 0.08f),
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, if (hasKhataCustomer) StoreGreenProfit.copy(alpha = 0.3f) else StoreRedAlert.copy(alpha = 0.4f))
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { showCustomerPickerSheet = true }
                                                .padding(10.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                                Icon(
                                                    imageVector = Icons.Default.Person,
                                                    contentDescription = null,
                                                    tint = if (hasKhataCustomer) StoreGreenProfit else StoreRedAlert,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Column {
                                                    Text(
                                                        text = if (hasKhataCustomer) "Khata Customer: $linkedCustomerName" else if (isBn) "খাতার জন্য কাস্টমার বাছুন *" else "Select Customer for Khata *",
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 12.sp,
                                                        color = if (hasKhataCustomer) StoreGreenProfit else StoreRedAlert
                                                    )
                                                    Text(
                                                        text = if (hasKhataCustomer) (if (isBn) "পরিবর্তন করতে চাপুন" else "Tap to change customer") else (if (isBn) "খাতায় হিসাব রাখতে কাস্টমার প্রয়োজন" else "Required to adjust Khata balance"),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = TextMuted
                                                    )
                                                }
                                            }

                                            OutlinedButton(
                                                onClick = { showCustomerPickerSheet = true },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Text(if (hasKhataCustomer) (if (isBn) "বদল" else "Change") else (if (isBn) "বাছুন" else "Select"), fontSize = 11.sp)
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                OutlinedTextField(
                                    value = notesText,
                                    onValueChange = { notesText = it },
                                    label = { Text(if (isBn) "কারণ / মন্তব্য (Reason / Notes)" else "Reason / Notes") },
                                    placeholder = { Text("e.g. Defective, Wrong size, Customer requested change") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Bottom Financial Summary Banner & Action Button
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = if (netAmount > 0) StoreRedAlert.copy(alpha = 0.1f) else if (netAmount < 0) StoreGreenProfit.copy(alpha = 0.1f) else StorePrimary.copy(alpha = 0.1f),
                    border = BorderStroke(1.dp, if (netAmount > 0) StoreRedAlert.copy(alpha = 0.4f) else if (netAmount < 0) StoreGreenProfit.copy(alpha = 0.4f) else StorePrimary.copy(alpha = 0.4f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (isBn) "মোট ফেরত পণ্যের মূল্য:" else "Total Returned Value:",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                            Text(
                                text = "₹%.2f".format(totalReturnedValue),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall,
                                color = StoreRedAlert
                            )
                        }

                        if (isReplacementMode) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (isBn) "মোট নতুন পণ্যের মূল্য:" else "Total Replacement Value:",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted
                                )
                                Text(
                                    text = "₹%.2f".format(totalReplacementValue),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StorePrimary
                                )
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = TextMuted.copy(alpha = 0.2f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (netAmount > 0) {
                                    if (isBn) "গ্রাহককে ফেরত দিন (Refund to Customer):" else "Refund to Customer:"
                                } else if (netAmount < 0) {
                                    if (isBn) "গ্রাহকের থেকে নিন (Collect from Customer):" else "Collect from Customer:"
                                } else {
                                    if (isBn) "সমান এক্সচেঞ্জ (Even Exchange):" else "Even Exchange:"
                                },
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextDark
                            )

                            Text(
                                text = "₹%.2f".format(kotlin.math.abs(netAmount)),
                                fontWeight = FontWeight.ExtraBold,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (netAmount > 0) StoreRedAlert else if (netAmount < 0) StoreGreenProfit else StorePrimary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = {
                        val returnId = "RET-${System.currentTimeMillis().toString().takeLast(8)}"
                        val returnItemsList = mutableListOf<ReturnItem>()

                        // 1. Add returned items
                        saleWithItems.items.forEach { item ->
                            val rQty = returnQtys[item.id] ?: 0.0
                            if (rQty > 0.0) {
                                val rSubtotal = if (item.unitType.equals("gram", ignoreCase = true) || item.unitType.equals("ml", ignoreCase = true)) {
                                    (rQty / 1000.0) * item.unitPrice
                                } else {
                                    rQty * item.unitPrice
                                }
                                returnItemsList.add(
                                    ReturnItem(
                                        returnId = returnId,
                                        productId = item.productId,
                                        productNameEn = item.productNameEn,
                                        productNameBn = item.productNameBn,
                                        unitType = item.unitType,
                                        quantity = rQty,
                                        unitPrice = item.unitPrice,
                                        subtotal = rSubtotal,
                                        isReplacement = false
                                    )
                                )
                            }
                        }

                        // 2. Add replacement items if any
                        replacementItems.forEach { rItem ->
                            returnItemsList.add(
                                ReturnItem(
                                    returnId = returnId,
                                    productId = rItem.product.id,
                                    productNameEn = rItem.product.nameEn,
                                    productNameBn = rItem.product.nameBn,
                                    unitType = rItem.unitType,
                                    quantity = rItem.quantity,
                                    unitPrice = rItem.unitPrice,
                                    subtotal = rItem.subtotal,
                                    isReplacement = true
                                )
                            )
                        }

                        val saleReturn = SaleReturn(
                            id = returnId,
                            saleId = sale.id,
                            datetime = System.currentTimeMillis(),
                            type = if (isReplacementMode) "REPLACEMENT" else "RETURN",
                            customerId = linkedCustomerId,
                            customerName = linkedCustomerName,
                            totalReturnedAmount = totalReturnedValue,
                            totalReplacementAmount = totalReplacementValue,
                            netAmount = netAmount,
                            refundPaymentMode = selectedPaymentMode,
                            notes = notesText.trim().ifBlank { null }
                        )

                        onConfirmReturn(saleReturn, returnItemsList)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    enabled = isFormValid,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isReplacementMode) StorePrimary else StoreRedAlert
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = if (isReplacementMode) Icons.Default.SwapHoriz else Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (!isKhataValid) {
                            if (isBn) "খাতার জন্য কাস্টমার বাছুন" else "Select Khata Customer to Proceed"
                        } else if (totalReturnedValue <= 0.0) {
                            if (isBn) "ফেরতের আইটেম বাছুন" else "Select Items to Return"
                        } else if (isReplacementMode) {
                            if (isBn) "পরিবর্তন সম্পন্ন করুন (Confirm Replacement)" else "Confirm Replacement"
                        } else {
                            if (isBn) "ফেরত সম্পন্ন করুন (Confirm Return)" else "Confirm Return"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }

    // Modal to Search & Add Replacement Product
    if (showAddReplacementSheet) {
        Dialog(onDismissRequest = { showAddReplacementSheet = false }) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .padding(8.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isBn) "পরিবর্তনের পণ্য বাছুন" else "Select Replacement Item",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                        IconButton(onClick = { showAddReplacementSheet = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text(if (isBn) "পণ্য খুঁজুন..." else "Search product by name or barcode...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    val filteredProducts = remember(allProducts, searchQuery) {
                        if (searchQuery.isBlank()) allProducts
                        else allProducts.filter {
                            it.nameEn.contains(searchQuery, ignoreCase = true) ||
                                    it.nameBn.contains(searchQuery, ignoreCase = true) ||
                                    (it.barcode?.contains(searchQuery, ignoreCase = true) == true)
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filteredProducts, key = { it.id }, contentType = { "REPLACEMENT_PRODUCT_ITEM" }) { product ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val existingIndex = replacementItems.indexOfFirst { it.product.id == product.id }
                                        if (existingIndex >= 0) {
                                            val existing = replacementItems[existingIndex]
                                            val step = if (product.unitType.equals("gram", ignoreCase = true) || product.unitType.equals("ml", ignoreCase = true)) 100.0 else 1.0
                                            val newQty = Product.roundQuantity(existing.quantity + step)
                                            replacementItems[existingIndex] = existing.copy(
                                                quantity = newQty,
                                                subtotal = product.calculatePrice(newQty, existing.unitType)
                                            )
                                        } else {
                                            val unit = product.unitType
                                            val price = product.sellingPrice
                                            val qty = 1.0
                                            replacementItems.add(
                                                ReplacementItemData(
                                                    product = product,
                                                    quantity = qty,
                                                    unitType = unit,
                                                    unitPrice = price,
                                                    subtotal = product.calculatePrice(qty, unit)
                                                )
                                            )
                                        }
                                        showAddReplacementSheet = false
                                        searchQuery = ""
                                    },
                                colors = CardDefaults.cardColors(containerColor = CardBackground),
                                border = BorderStroke(1.dp, TextMuted.copy(alpha = 0.15f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = product.getDisplayName(isBn),
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = "Stock: ${product.getFormattedStockDisplay()} • ${product.category}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextMuted
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = "₹%.2f".format(product.sellingPrice),
                                            fontWeight = FontWeight.Bold,
                                            color = StorePrimary
                                        )
                                        Surface(
                                            color = StorePrimary.copy(alpha = 0.12f),
                                            shape = RoundedCornerShape(4.dp),
                                            modifier = Modifier.padding(top = 2.dp)
                                        ) {
                                            Text(
                                                text = if (isBn) "+ যোগ করুন" else "+ Add",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = StorePrimary,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
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
    }

    // Modal to Select Khata Customer (if sale was walk-in)
    if (showCustomerPickerSheet) {
        Dialog(onDismissRequest = { showCustomerPickerSheet = false }) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .padding(8.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isBn) "খাতার জন্য কাস্টমার বাছুন" else "Select Khata Customer",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                        IconButton(onClick = { showCustomerPickerSheet = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = customerSearchQuery,
                        onValueChange = { customerSearchQuery = it },
                        placeholder = { Text(if (isBn) "কাস্টমারের নাম বা ফোন খুঁজুন..." else "Search customer by name or phone...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    val filteredCustomers = remember(allCustomers, customerSearchQuery) {
                        if (customerSearchQuery.isBlank()) allCustomers
                        else allCustomers.filter {
                            it.name.contains(customerSearchQuery, ignoreCase = true) ||
                                    (it.phone?.contains(customerSearchQuery) == true)
                        }
                    }

                    if (filteredCustomers.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isBn) "কোনো কাস্টমার পাওয়া যায়নি" else "No matching customers found",
                                color = TextMuted
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(filteredCustomers, key = { it.id }) { cust ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            linkedCustomerId = cust.id
                                            linkedCustomerName = cust.name
                                            showCustomerPickerSheet = false
                                            customerSearchQuery = ""
                                        },
                                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                                    border = BorderStroke(1.dp, TextMuted.copy(alpha = 0.15f))
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = cust.name,
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            if (!cust.phone.isNullOrBlank()) {
                                                Text(
                                                    text = cust.phone,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = TextMuted
                                                )
                                            }
                                        }

                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(
                                                text = "Due: ₹%.2f".format(cust.balance),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp,
                                                color = if (cust.balance > 0) StoreRedAlert else StoreGreenProfit
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
    }

    // Modal to manually input exact Return quantity
    itemForCustomQty?.let { item ->
        AlertDialog(
            onDismissRequest = { itemForCustomQty = null },
            title = {
                Text(
                    text = if (isBn) "ফেরতের পরিমাণ লিখুন" else "Enter Return Quantity",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = "${item.productNameEn} (Max: $maxQtyForCustomItem ${item.unitType})",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = customQtyInputText,
                        onValueChange = { customQtyInputText = it },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            val parsed = customQtyInputText.toDoubleOrNull() ?: 0.0
                            returnQtys[item.id] = parsed.coerceIn(0.0, maxQtyForCustomItem)
                            itemForCustomQty = null
                        }),
                        label = { Text("Quantity (${item.unitType})") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsed = customQtyInputText.toDoubleOrNull() ?: 0.0
                        returnQtys[item.id] = parsed.coerceIn(0.0, maxQtyForCustomItem)
                        itemForCustomQty = null
                    }
                ) {
                    Text(if (isBn) "ঠিক আছে" else "OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { itemForCustomQty = null }) {
                    Text(if (isBn) "বাতিল" else "Cancel")
                }
            }
        )
    }

    // Modal to manually input exact Replacement quantity
    replacementItemIndexForCustomQty?.let { index ->
        val replItem = replacementItems.getOrNull(index)
        if (replItem != null) {
            AlertDialog(
                onDismissRequest = { replacementItemIndexForCustomQty = null },
                title = {
                    Text(
                        text = if (isBn) "পরিবর্তন পণ্যের পরিমাণ লিখুন" else "Enter Replacement Quantity",
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column {
                        Text(
                            text = "${replItem.product.getDisplayName(isBn)} (@ ₹%.2f / ${replItem.unitType})".format(replItem.unitPrice),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = replacementCustomQtyInputText,
                            onValueChange = { replacementCustomQtyInputText = it },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                val parsed = replacementCustomQtyInputText.toDoubleOrNull() ?: replItem.quantity
                                if (parsed > 0.0) {
                                    val newQty = Product.roundQuantity(parsed)
                                    replacementItems[index] = replItem.copy(
                                        quantity = newQty,
                                        subtotal = replItem.product.calculatePrice(newQty, replItem.unitType)
                                    )
                                }
                                replacementItemIndexForCustomQty = null
                            }),
                            label = { Text("Quantity (${replItem.unitType})") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val parsed = replacementCustomQtyInputText.toDoubleOrNull() ?: replItem.quantity
                            if (parsed > 0.0) {
                                val newQty = Product.roundQuantity(parsed)
                                replacementItems[index] = replItem.copy(
                                    quantity = newQty,
                                    subtotal = replItem.product.calculatePrice(newQty, replItem.unitType)
                                )
                            }
                            replacementItemIndexForCustomQty = null
                        }
                    ) {
                        Text(if (isBn) "ঠিক আছে" else "OK")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { replacementItemIndexForCustomQty = null }) {
                        Text(if (isBn) "বাতিল" else "Cancel")
                    }
                }
            )
        }
    }
}

data class ReplacementItemData(
    val product: Product,
    val quantity: Double,
    val unitType: String,
    val unitPrice: Double,
    val subtotal: Double
)
