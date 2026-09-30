package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.local.entities.Product
import com.example.data.models.InsufficientStockException
import com.example.data.models.InsufficientStockItem
import com.example.ui.theme.*
import com.example.utils.LanguageManager
import com.example.utils.ThemeManager

@Composable
fun InsufficientStockWarningDialog(
    exception: InsufficientStockException,
    isPrivilegedUser: Boolean, // True for Owner/Manager, False for Cashier/Staff
    isBn: Boolean,
    onDismiss: () -> Unit,
    onOverrideAndComplete: () -> Unit,
    onCorrectStock: (InsufficientStockItem) -> Unit,
    onQuickMatchStock: ((InsufficientStockItem) -> Unit)? = null,
    onSellAvailableSingle: ((InsufficientStockItem) -> Unit)? = null,
    onAdjustCartToAvailable: (() -> Unit)? = null,
    onQuickMatchAllStocks: (() -> Unit)? = null
) {
    val items = exception.items
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
                                imageVector = if (isPrivilegedUser) Icons.Default.WarningAmber else Icons.Default.Block,
                                contentDescription = "Stock Alert",
                                tint = warningColor,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isPrivilegedUser) {
                                if (isBn) "মজুদ ঘাটতি সতর্কতা" else "Insufficient Stock Warning"
                            } else {
                                if (isBn) "বিক্রি বন্ধ: পর্যাপ্ত মজুদ নেই" else "Sale Blocked: Out of Stock"
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = warningColor
                        )

                        Text(
                            text = if (isPrivilegedUser) {
                                if (isBn) "অনুরোধকৃত পরিমাণ বিদ্যমান মজুদের চেয়ে বেশি" else "Requested quantity exceeds recorded inventory"
                            } else {
                                if (isBn) "ক্যাশিয়ার বা কর্মীরা মজুদের অতিরিক্ত বিক্রি করতে পারবেন না" else "Staff cannot sell items beyond available stock"
                            },
                            fontSize = 12.sp,
                            color = TextMuted
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                HorizontalDivider(color = BorderDivider)

                Spacer(modifier = Modifier.height(12.dp))

                // Shortage Items List
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isBn) "ঘাটতিযুক্ত পণ্যসমূহ (${items.size}):" else "Items Exceeding Stock (${items.size}):",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextDark
                    )
                    if (items.size > 1) {
                        Text(
                            text = if (isBn) "সংশোধন করতে পণ্যে চাপুন" else "Tap item to edit stock",
                            fontSize = 11.sp,
                            color = StorePrimary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(weight = 1f, fill = false)
                        .heightIn(max = 260.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items, key = { it.productId }, contentType = { "INSUFFICIENT_STOCK_ITEM" }) { item ->
                        InsufficientStockItemCard(
                            item = item,
                            isBn = isBn,
                            isDark = isDark,
                            onCorrectItem = { onCorrectStock(item) },
                            onQuickMatchStock = if (onQuickMatchStock != null) { { onQuickMatchStock(item) } } else null,
                            onSellAvailable = if (onSellAvailableSingle != null) { { onSellAvailableSingle(item) } } else null
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Informational Notice Box
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
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                text = if (isPrivilegedUser) "💡 " else "⛔ ",
                                fontSize = 13.sp
                            )
                            Text(
                                text = if (isPrivilegedUser) {
                                    if (isBn) {
                                        "দোকানে পণ্য থাকলে 'মজুদ কার্ট সমান' বা 'সংশোধন' করুন। অথবা 'বিদ্যমান মজুদ বিক্রি' চেপে অবিলম্বে বিক্রি সম্পন্ন করুন।"
                                    } else {
                                        "Physical stock present? Tap 'Match Stock' or 'Fix Stock'. Or tap 'Sell Available Stock' to sell current stock immediately."
                                    }
                                } else {
                                    if (isBn) {
                                        "অনুরোধকৃত পরিমাণ মজুদ ছাড়িয়ে গেছে। 'বিদ্যমান মজুদ বিক্রি' চাপুন অথবা কার্টের পরিমাণ হ্রাস করুন।"
                                    } else {
                                        "Requested quantity exceeds current stock. Tap 'Sell Available Stock' or adjust your cart."
                                    }
                                },
                                fontSize = 11.sp,
                                color = if (isDark) {
                                    if (isPrivilegedUser) Color(0xFFFDE68A) else Color(0xFFFCA5A5)
                                } else {
                                    if (isPrivilegedUser) Color(0xFF78350F) else Color(0xFF991B1B)
                                },
                                lineHeight = 15.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Action Buttons: Smart Options
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Row 1: Sell Available & Override / Correct
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Action 1: Adjust Cart to Available Stock
                        Button(
                            onClick = { onAdjustCartToAvailable?.invoke() ?: onDismiss() },
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = StorePrimary,
                                contentColor = Color.White
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isBn) "বিদ্যমান মজুদ বিক্রি" else "Sell Available Stock",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Action 2: Override & Sell (If privileged user)
                        if (isPrivilegedUser) {
                            Button(
                                onClick = onOverrideAndComplete,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFD97706),
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isBn) "অগ্রাহ্য করে বিক্রি" else "Override & Sell",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    // Row 2: Secondary options (Match All if privileged, and Cancel/Keep Cart)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isPrivilegedUser && onQuickMatchAllStocks != null) {
                            OutlinedButton(
                                onClick = onQuickMatchAllStocks,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = StorePrimary
                                ),
                                border = BorderStroke(1.dp, StorePrimary.copy(alpha = 0.5f))
                            ) {
                                Text(
                                    text = if (isBn) "সব মজুদ কার্ট সমান" else "Match All to Cart",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = TextDark
                            ),
                            border = BorderStroke(1.dp, BorderDivider)
                        ) {
                            Text(
                                text = if (isBn) "কার্ট রাখুন (বাতিল)" else "Keep Cart (Cancel)",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InsufficientStockItemCard(
    item: InsufficientStockItem,
    isBn: Boolean,
    isDark: Boolean,
    onCorrectItem: (() -> Unit)? = null,
    onQuickMatchStock: (() -> Unit)? = null,
    onSellAvailable: (() -> Unit)? = null
) {
    val name = if (isBn && item.productNameBn.isNotBlank()) item.productNameBn else item.productNameEn
    val availStr = item.formatAvailable()
    val reqStr = item.formatRequested()
    val shortStr = item.formatShortage()

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = SurfaceWarm,
        border = BorderStroke(1.dp, BorderDivider),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // Row 1: Product Name and Fix Stock button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = TextDark,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (onCorrectItem != null) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = StorePrimary.copy(alpha = if (isDark) 0.25f else 0.12f),
                        border = BorderStroke(1.dp, StorePrimary.copy(alpha = 0.35f)),
                        modifier = Modifier.clickable { onCorrectItem() }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = null,
                                tint = StorePrimary,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = if (isBn) "সংশোধন" else "Fix Stock",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = StorePrimary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Row 2: 3-column Responsive Metric Boxes (Available, Requested, Shortage)
            // Equal weights ensure clean layout and NO broken text wrapping!
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 1. Available Column
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    color = if (isDark) Color(0xFF143820) else Color(0xFFDCFCE7),
                    border = BorderStroke(1.dp, if (isDark) Color(0xFF22C55E).copy(alpha = 0.4f) else Color(0xFF86EFAC))
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (isBn) "মজুদ আছে" else "Available",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isDark) Color(0xFF4ADE80) else Color(0xFF166534),
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "$availStr ${item.unitType}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isDark) Color(0xFF4ADE80) else Color(0xFF166534),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // 2. Requested Column
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    color = if (isDark) Color(0xFF1E293B) else Color(0xFFF1F5F9),
                    border = BorderStroke(1.dp, if (isDark) Color(0xFF64748B).copy(alpha = 0.4f) else Color(0xFFCBD5E1))
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (isBn) "অনুরোধ" else "Requested",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isDark) Color(0xFF94A3B8) else Color(0xFF475569),
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "$reqStr ${item.unitType}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isDark) Color(0xFFE2E8F0) else Color(0xFF1E293B),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // 3. Shortage Column (Equal weight, bold text, never broken!)
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    color = if (isDark) Color(0xFF382014) else Color(0xFFFEF3C7),
                    border = BorderStroke(1.dp, if (isDark) Color(0xFFF59E0B).copy(alpha = 0.5f) else Color(0xFFFCD34D))
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (isBn) "ঘাটতি" else "Shortage",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isDark) Color(0xFFFBBF24) else Color(0xFF92400E),
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "$shortStr ${item.unitType}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isDark) Color(0xFFFBBF24) else Color(0xFF92400E),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Row 3: Direct 1-tap quick action chips (Match Stock or Sell Available)
            if (onQuickMatchStock != null || onSellAvailable != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onQuickMatchStock != null) {
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onQuickMatchStock() },
                            shape = RoundedCornerShape(6.dp),
                            color = StorePrimary.copy(alpha = if (isDark) 0.18f else 0.08f),
                            border = BorderStroke(1.dp, StorePrimary.copy(alpha = 0.35f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = StorePrimary,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = if (isBn) "মজুদ $reqStr করুন" else "Match Stock ($reqStr)",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = StorePrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    if (onSellAvailable != null && item.availableStock > 0.0001) {
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onSellAvailable() },
                            shape = RoundedCornerShape(6.dp),
                            color = if (isDark) Color(0xFF143820) else Color(0xFFDCFCE7),
                            border = BorderStroke(1.dp, if (isDark) Color(0xFF22C55E).copy(alpha = 0.4f) else Color(0xFF86EFAC))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = if (isBn) "মজুদ $availStr বিক্রি" else "Sell Avail ($availStr)",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isDark) Color(0xFF4ADE80) else Color(0xFF166534),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Direct, focused dialog to correct product stock quantity on the spot during billing.
 */
@Composable
fun QuickStockCorrectionDialog(
    product: Product,
    shortageItem: InsufficientStockItem? = null,
    isBn: Boolean,
    onDismiss: () -> Unit,
    onSaveStock: (Product) -> Unit
) {
    val isDark = ThemeManager.isDarkMode()
    val displayName = if (isBn && product.nameBn.isNotBlank()) product.nameBn else product.nameEn
    val requestedQty = shortageItem?.requestedQuantity ?: 0.0

    // Initial stock text: recommended to at least cover cart quantity or current stock
    val initialStockVal = if (requestedQty > product.currentStock) requestedQty else product.currentStock
    val initialStockFormatted = InsufficientStockItem.formatQuantity(initialStockVal)
    
    var stockInputText by remember { mutableStateOf(initialStockFormatted) }
    var costPriceText by remember { mutableStateOf(if (product.costPrice % 1.0 == 0.0) "%.0f".format(product.costPrice) else "%.2f".format(product.costPrice)) }
    var sellingPriceText by remember { mutableStateOf(if (product.sellingPrice % 1.0 == 0.0) "%.0f".format(product.sellingPrice) else "%.2f".format(product.sellingPrice)) }
    var showPriceFields by remember { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        try {
            focusRequester.requestFocus()
        } catch (e: Exception) {
            // Ignore focus exception
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 480.dp)
                .padding(vertical = 16.dp),
            shape = RoundedCornerShape(20.dp),
            color = CardBackground,
            shadowElevation = 12.dp,
            border = BorderStroke(1.5.dp, StorePrimary.copy(alpha = 0.5f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = StorePrimary.copy(alpha = if (isDark) 0.25f else 0.12f),
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Inventory2,
                                    contentDescription = null,
                                    tint = StorePrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column {
                            Text(
                                text = if (isBn) "মজুদ সংশোধন করুন" else "Correct Product Stock",
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp,
                                color = TextDark
                            )
                            Text(
                                text = if (isBn) "বিল না কেটে সরাসরি দোকানে বাস্তব মজুদ ঠিক করুন" else "Fix real physical count without leaving the bill",
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = TextMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = BorderDivider)
                Spacer(modifier = Modifier.height(12.dp))

                // Product Identity Card
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = SurfaceWarm,
                    border = BorderStroke(1.dp, BorderDivider),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = displayName,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = TextDark,
                                modifier = Modifier.weight(1f)
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = StorePrimary.copy(alpha = 0.1f)
                            ) {
                                Text(
                                    text = product.category.ifBlank { "General" },
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = StorePrimary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Stock Context Info
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = if (isBn) "রেকর্ডকৃত বর্তমান মজুদ:" else "Recorded in System:",
                                    fontSize = 11.sp,
                                    color = TextMuted
                                )
                                Text(
                                    text = product.getFormattedStockDisplay(isBn),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (product.currentStock <= 0) StoreRedAlert else TextDark
                                )
                            }

                            if (requestedQty > 0) {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = if (isBn) "কার্টে চাওয়া পরিমাণ:" else "Requested in Cart:",
                                        fontSize = 11.sp,
                                        color = TextMuted
                                    )
                                    Text(
                                        text = "$requestedQty ${product.unitType}",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = StorePrimary
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Primary Stock-Edit Input Field
                Text(
                    text = if (isBn) "দোকানে বর্তমান বাস্তব মজুদ পরিমাণ (${product.unitType}):" else "Actual Physical Stock Quantity (${product.unitType}):",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextDark
                )

                Spacer(modifier = Modifier.height(6.dp))

                OutlinedTextField(
                    value = stockInputText,
                    onValueChange = { stockInputText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            val newStock = stockInputText.toDoubleOrNull() ?: product.currentStock
                            val newCost = costPriceText.toDoubleOrNull() ?: product.costPrice
                            val newSelling = sellingPriceText.toDoubleOrNull() ?: product.sellingPrice
                            onSaveStock(
                                product.copy(
                                    currentStock = newStock,
                                    costPrice = newCost,
                                    sellingPrice = newSelling
                                )
                            )
                        }
                    ),
                    trailingIcon = {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = StorePrimary.copy(alpha = 0.12f),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text(
                                text = product.unitType,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = StorePrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = StorePrimary,
                        unfocusedBorderColor = BorderDivider
                    ),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Quick Adjustment Chips (1-tap helpers)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (requestedQty > 0) {
                        val reqFormatted = if (requestedQty % 1.0 == 0.0) "%.0f".format(requestedQty) else "%.2f".format(requestedQty)
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = StorePrimary.copy(alpha = if (isDark) 0.2f else 0.1f),
                            border = BorderStroke(1.dp, StorePrimary.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { stockInputText = reqFormatted }
                        ) {
                            Text(
                                text = if (isBn) "কার্টের সমান ($reqFormatted)" else "Match Cart ($reqFormatted)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = StorePrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                maxLines = 1
                            )
                        }
                    }

                    // Increment buttons
                    listOf(1, 5, 10, 50).forEach { inc ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = SurfaceWarm,
                            border = BorderStroke(1.dp, BorderDivider),
                            modifier = Modifier.clickable {
                                val current = stockInputText.toDoubleOrNull() ?: 0.0
                                val newVal = current + inc
                                stockInputText = if (newVal % 1.0 == 0.0) "%.0f".format(newVal) else "%.2f".format(newVal)
                            }
                        ) {
                            Text(
                                text = "+$inc",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextDark,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Optional Price Tweaks Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showPriceFields = !showPriceFields }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isBn) "মূল্য পরিবর্তন করতে চান? (ঐচ্ছিক)" else "Tweak Price as well? (Optional)",
                        fontSize = 12.sp,
                        color = StorePrimary,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (showPriceFields) "▲" else "▼",
                        fontSize = 12.sp,
                        color = StorePrimary
                    )
                }

                if (showPriceFields) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = costPriceText,
                            onValueChange = { costPriceText = it },
                            label = { Text(if (isBn) "ক্রয় মূল্য" else "Cost Price", fontSize = 11.sp) },
                            prefix = { Text("₹ ", fontSize = 12.sp, color = TextMuted) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = sellingPriceText,
                            onValueChange = { sellingPriceText = it },
                            label = { Text(if (isBn) "বিক্রয় মূল্য" else "Selling Price", fontSize = 11.sp) },
                            prefix = { Text("₹ ", fontSize = 12.sp, color = TextMuted) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            singleLine = true
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, BorderDivider)
                    ) {
                        Text(
                            text = if (isBn) "বাতিল" else "Cancel",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = TextDark
                        )
                    }

                    Button(
                        onClick = {
                            val parsedStock = stockInputText.toDoubleOrNull() ?: product.currentStock
                            val roundedStock = Math.round(parsedStock * 1000.0) / 1000.0
                            val newCost = costPriceText.toDoubleOrNull() ?: product.costPrice
                            val newSelling = sellingPriceText.toDoubleOrNull() ?: product.sellingPrice
                            onSaveStock(
                                product.copy(
                                    currentStock = roundedStock,
                                    costPrice = newCost,
                                    sellingPrice = newSelling
                                )
                            )
                        },
                        modifier = Modifier
                            .weight(1.5f)
                            .height(46.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StorePrimary,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isBn) "মজুদ আপডেট ও চালিয়ে যান" else "Save & Continue Bill",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

