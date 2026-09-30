package com.example.ui.components

import android.app.DatePickerDialog
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.entities.BarcodeVariant
import com.example.data.local.entities.Product
import com.example.ui.theme.*
import com.example.utils.BengaliReceiptTranslator
import com.example.utils.EscPosPrinter
import com.example.utils.LanguageManager
import com.example.utils.PdfReceiptHelper
import com.example.utils.StoreInfoManager
import com.example.viewmodel.StoreViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

enum class BarcodeLabelSize(val title: String, val widthMm: Int, val heightMm: Int, val gapMm: Int) {
    SIZE_50_25("50 × 25 mm (Sticker)", 50, 25, 2),
    SIZE_50_30("50 × 30 mm", 50, 30, 2),
    SIZE_40_30("40 × 30 mm", 40, 30, 2),
    SIZE_38_25("38 × 25 mm", 38, 25, 2),
    SIZE_58_CONT("58 mm (Roll)", 48, 30, 0)
}

/**
 * Calculates a future date formatted as YYYY-MM-DD based on current time + months.
 */
fun calculateExpiryPresetDate(monthsToAdd: Int): String {
    val cal = Calendar.getInstance()
    cal.add(Calendar.MONTH, monthsToAdd)
    val y = cal.get(Calendar.YEAR)
    val m = cal.get(Calendar.MONTH) + 1
    val d = cal.get(Calendar.DAY_OF_MONTH)
    return String.format(Locale.US, "%04d-%02d-%02d", y, m, d)
}

/**
 * Crash-safe parser for price/MRP strings.
 * Handles intermediate input states:
 * - Empty string or whitespace -> returns 0.0
 * - Incomplete decimals (e.g. "120.", ".") -> parses "120" -> 120.0 or 0.0
 * - Multiple decimal points or invalid chars -> returns fallback
 * - Very large values -> coerced safely within [0.0, 99999999.0]
 * - Never throws NumberFormatException
 */
fun parseSafePriceInput(input: String, fallback: Double = 0.0): Double {
    return try {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed == ".") return 0.0

        val clean = if (trimmed.endsWith(".") && trimmed.count { it == '.' } == 1) {
            trimmed.dropLast(1)
        } else {
            trimmed
        }

        val parsed = clean.toDoubleOrNull() ?: return fallback
        when {
            parsed.isNaN() || parsed.isInfinite() -> fallback
            parsed < 0.0 -> 0.0
            parsed > 99_999_999.0 -> 99_999_999.0
            else -> parsed
        }
    } catch (_: Throwable) {
        fallback
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintBarcodeModalDialog(
    productName: String,
    barcodeStr: String,
    sellingPrice: Double,
    mrpPrice: Double,
    viewModel: StoreViewModel,
    initialQuantity: String = "1 N",
    initialSize: String? = null,
    initialTag: String = "",
    initialExpiryDate: String? = null,
    onPriceOrMrpChanged: ((newSellingPrice: Double, newMrp: Double?) -> Unit)? = null,
    product: Product? = null,
    barcodeVariants: List<BarcodeVariant> = product?.getBarcodeVariants() ?: emptyList(),
    initialSelectedVariant: BarcodeVariant? = null,
    onRequestAddVariant: (() -> Unit)? = null,
    onSaveNewVariant: ((BarcodeVariant) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isBn = LanguageManager.isBengali
    val coroutineScope = rememberCoroutineScope()

    var activeVariant by remember { mutableStateOf(initialSelectedVariant) }
    var effectiveVariants by remember(barcodeVariants, product) {
        mutableStateOf(barcodeVariants.ifEmpty { product?.getBarcodeVariants() ?: emptyList() })
    }
    var activeBarcodeStr by remember(barcodeStr, initialSelectedVariant) {
        mutableStateOf(initialSelectedVariant?.barcode?.ifBlank { null } ?: barcodeStr)
    }

    var showInlineAddVariant by remember { mutableStateOf(false) }
    var isBatchPrintingAll by remember { mutableStateOf(false) }

    var labelQtyText by remember { mutableStateOf("1") }
    val labelQty = remember(labelQtyText) {
        try {
            val trimmed = labelQtyText.trim()
            if (trimmed.isEmpty()) {
                1
            } else {
                val parsedLong = trimmed.toLongOrNull()
                    ?: if (trimmed.all { it.isDigit() } && trimmed.isNotEmpty()) trimmed.take(6).toLongOrNull() else null
                val safeLong = parsedLong ?: 1L
                safeLong.coerceIn(1L, 500L).toInt()
            }
        } catch (_: Throwable) {
            1
        }
    }

    var selectedSize by remember { mutableStateOf(BarcodeLabelSize.SIZE_50_25) }
    var selectedProtocol by remember { mutableStateOf("TSPL") } // Default "TSPL" (Sticker Roll with Gap) or "ESCPOS" (Receipt Roll)
    var invertOrientation by remember { mutableStateOf(false) }
    var selectedDensity by remember { mutableStateOf(StoreInfoManager.thermalPrinterDensity) }
    var showPrinterGuide by remember { mutableStateOf(false) }

    var isPrinting by remember { mutableStateOf(false) }
    var isCalibrating by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf("") }

    val pairedPrinters = viewModel.pairedPrinters
    val selectedPrinterAddress = viewModel.selectedPrinterAddress

    LaunchedEffect(Unit) {
        viewModel.loadPairedPrinters()
    }

    // Editable label content matching thermal sticker format in photo
    var editableStoreName by remember {
        mutableStateOf(StoreInfoManager.storeName.ifBlank { "KALI MATA VARIETY STORE" })
    }
    var editableItemName by remember(productName, initialSelectedVariant) {
        mutableStateOf(initialSelectedVariant?.getDisplayTitle(productName) ?: productName.ifBlank { "SLD" })
    }
    val effectiveInitialQty = (initialSelectedVariant?.getShortLabel() ?: initialSize?.takeIf { it.isNotBlank() && it != "FREE ASN" } ?: initialQuantity).ifBlank { "1 Piece" }
    var editableQuantity by remember(effectiveInitialQty, initialSelectedVariant) {
        mutableStateOf(initialSelectedVariant?.getShortLabel() ?: effectiveInitialQty)
    }
    var editableTag by remember(initialTag, initialSelectedVariant) {
        mutableStateOf(if (initialSelectedVariant != null) "VARIANT" else initialTag)
    }
    var editableExpiryDate by remember(initialExpiryDate) {
        mutableStateOf(initialExpiryDate?.trim() ?: "")
    }
    var selectedLabelStyle by remember {
        mutableStateOf("MODERN") // Default "MODERN" (Black banner header) as requested in upgrade
    }

    // MRP & Selling price with automatic discount percentage logic
    val initialEffectiveMrp = if (initialSelectedVariant != null) initialSelectedVariant.getEffectiveMrp() else if (mrpPrice > 0.0) mrpPrice else if (sellingPrice > 0.0) sellingPrice else 0.0
    val initialSellPrice = if (initialSelectedVariant != null) initialSelectedVariant.price else sellingPrice
    var editableMrpText by remember(mrpPrice, sellingPrice, initialSelectedVariant) {
        mutableStateOf(if (initialEffectiveMrp > 0.0) (if (initialEffectiveMrp % 1.0 == 0.0) initialEffectiveMrp.toLong().toString() else "%.2f".format(initialEffectiveMrp)) else "")
    }
    var editablePriceText by remember(sellingPrice, initialSelectedVariant) {
        mutableStateOf(if (initialSellPrice > 0.0) (if (initialSellPrice % 1.0 == 0.0) initialSellPrice.toLong().toString() else "%.2f".format(initialSellPrice)) else "")
    }
    var shouldUpdateStorePrice by remember { mutableStateOf(false) }

    // Selection helpers for Variants, Wholesale & Bulk Box
    val selectVariant: (BarcodeVariant?) -> Unit = { variant ->
        activeVariant = variant
        if (variant != null) {
            activeBarcodeStr = variant.barcode
            editableItemName = variant.getDisplayTitle(productName)
            editableQuantity = variant.getShortLabel()
            editablePriceText = if (variant.price % 1.0 == 0.0) variant.price.toLong().toString() else "%.2f".format(variant.price)
            val effMrp = variant.getEffectiveMrp()
            editableMrpText = if (effMrp % 1.0 == 0.0) effMrp.toLong().toString() else "%.2f".format(effMrp)
            editableTag = "VARIANT"
        } else {
            activeBarcodeStr = barcodeStr
            editableItemName = productName
            editableQuantity = effectiveInitialQty
            editablePriceText = if (sellingPrice > 0.0) (if (sellingPrice % 1.0 == 0.0) sellingPrice.toLong().toString() else "%.2f".format(sellingPrice)) else ""
            editableMrpText = if (initialEffectiveMrp > 0.0) (if (initialEffectiveMrp % 1.0 == 0.0) initialEffectiveMrp.toLong().toString() else "%.2f".format(initialEffectiveMrp)) else ""
            editableTag = initialTag
        }
    }

    val selectWholesale: () -> Unit = {
        activeVariant = null
        activeBarcodeStr = barcodeStr
        editableItemName = productName
        editableQuantity = effectiveInitialQty
        val wsPrice = product?.wholesalePrice ?: 0.0
        editablePriceText = if (wsPrice > 0.0) (if (wsPrice % 1.0 == 0.0) wsPrice.toLong().toString() else "%.2f".format(wsPrice)) else ""
        editableMrpText = if (initialEffectiveMrp > 0.0) (if (initialEffectiveMrp % 1.0 == 0.0) initialEffectiveMrp.toLong().toString() else "%.2f".format(initialEffectiveMrp)) else ""
        editableTag = "WHOLESALE"
    }

    val selectBulkBox: () -> Unit = {
        if (product != null) {
            val bulkQty = product.getEffectiveBulkQuantity()
            val bulkUnit = product.getEffectiveBulkUnit()
            val bulkP = product.getEffectiveBulkPrice()
            val formattedQty = "${if (bulkQty % 1.0 == 0.0) bulkQty.toInt() else bulkQty} $bulkUnit"

            // 1. Look for existing Box variant in effectiveVariants
            val matchingBoxVariant = effectiveVariants.find { v ->
                v.unitType.equals("box", ignoreCase = true) ||
                v.label.contains("box", ignoreCase = true) ||
                v.label.contains("বক্স", ignoreCase = true) ||
                v.label.contains("bulk", ignoreCase = true) ||
                v.label.contains("বাল্ক", ignoreCase = true) ||
                (v.quantity == bulkQty && v.quantity > 1.0)
            }

            if (matchingBoxVariant != null) {
                activeVariant = matchingBoxVariant
                activeBarcodeStr = matchingBoxVariant.barcode
                editableItemName = matchingBoxVariant.getDisplayTitle(productName)
                editableQuantity = matchingBoxVariant.getShortLabel()
                editablePriceText = if (matchingBoxVariant.price % 1.0 == 0.0) matchingBoxVariant.price.toLong().toString() else "%.2f".format(matchingBoxVariant.price)
                editableMrpText = editablePriceText
                editableTag = "BULK BOX"
            } else {
                // 2. Generate a distinct, guaranteed unique valid GS1 EAN-13 barcode for the Bulk Box
                var boxBarcode = EscPosPrinter.generateValidEan13Barcode("890")
                while (boxBarcode == barcodeStr) {
                    boxBarcode = EscPosPrinter.generateValidEan13Barcode("890")
                }
                activeBarcodeStr = boxBarcode
                editableItemName = "${productName.take(18)} (Box)"
                editableQuantity = formattedQty
                editablePriceText = if (bulkP % 1.0 == 0.0) bulkP.toLong().toString() else "%.2f".format(bulkP)
                editableMrpText = editablePriceText
                editableTag = "BULK BOX"

                // Create and auto-save the box variant so scanners recognize it
                val newBoxVariant = BarcodeVariant(
                    barcode = boxBarcode,
                    unitType = bulkUnit,
                    quantity = bulkQty,
                    price = bulkP,
                    label = "Box ($formattedQty)"
                )
                effectiveVariants = effectiveVariants + newBoxVariant
                activeVariant = newBoxVariant
                onSaveNewVariant?.invoke(newBoxVariant)
            }
        }
    }

    val currentPrice = remember(editablePriceText) {
        parseSafePriceInput(editablePriceText, fallback = 0.0)
    }
    val currentMrp = remember(editableMrpText, currentPrice) {
        val parsed = parseSafePriceInput(editableMrpText, fallback = 0.0)
        if (parsed > 0.0) parsed else currentPrice
    }

    // Calculate current discount %
    val currentDiscPct = remember(currentMrp, currentPrice) {
        if (currentMrp > currentPrice && currentMrp > 0.0 && currentPrice > 0.0) {
            Math.round(((currentMrp - currentPrice) / currentMrp) * 100.0).toInt().coerceIn(1, 99)
        } else {
            0
        }
    }

    // High quality dynamic preview bitmap generated directly from EscPosPrinter
    val previewBitmap = remember(
        editableStoreName,
        editableItemName,
        activeBarcodeStr,
        currentPrice,
        currentMrp,
        selectedSize,
        editableQuantity,
        editableTag,
        currentDiscPct,
        editableExpiryDate,
        selectedLabelStyle
    ) {
        EscPosPrinter.generateBarcodeLabelBitmap(
            storeName = editableStoreName,
            productName = editableItemName,
            barcodeStr = activeBarcodeStr,
            price = currentPrice,
            mrp = currentMrp,
            widthMm = selectedSize.widthMm,
            heightMm = selectedSize.heightMm,
            quantityOrUnit = editableQuantity,
            subtitleOrTag = editableTag,
            discountPercentage = if (currentDiscPct > 0) currentDiscPct else null,
            expiryDate = editableExpiryDate.trim().ifBlank { null },
            labelStyle = selectedLabelStyle
        )
    }

    AlertDialog(
        onDismissRequest = {
            onDismiss()
        },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    color = StorePrimary.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Print,
                            contentDescription = null,
                            tint = StorePrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Column {
                    Text(
                        text = if (isBn) "বারকোড স্টিকার প্রিন্ট করুন" else "Print Barcode Stickers",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isBn) "MRP ও অফার প্রাইস সহ থার্মাল লেবেল" else "Thermal Label with MRP & Offer Price",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 0. Pack Size & Bulk Price Variant Selector Card
                val hasPackOptions = effectiveVariants.isNotEmpty() || (product != null && (product.wholesalePrice > 0 || product.hasBulkPricing()))
                if (hasPackOptions) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(StorePrimary.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
                            .border(1.dp, StorePrimary.copy(alpha = 0.22f), RoundedCornerShape(10.dp))
                            .padding(9.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f).padding(end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Default.Layers, contentDescription = null, tint = StorePrimary, modifier = Modifier.size(14.dp))
                                Text(
                                    text = if (isBn) "📦 ভ্যারিয়েন্টসমূহ:" else "📦 Variant Options:",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = StorePrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Surface(
                                onClick = {
                                    showInlineAddVariant = true
                                },
                                color = StorePrimary,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = if (isBn) "+ যোগ করুন" else "+ Add",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }

                        // Horizontally scrollable row of variant & price options
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Option 1: Standard Retail
                            val isRetailSelected = activeVariant == null && editableTag != "WHOLESALE" && editableTag != "BULK BOX"
                            DialogPillSegmentButton(
                                selected = isRetailSelected,
                                label = if (isBn) "খুচরা (Retail)" else "Standard Retail",
                                icon = Icons.Default.Sell,
                                onClick = { selectVariant(null) }
                            )

                            // Option 2: Wholesale Price (if available)
                            if (product?.wholesalePrice != null && product.wholesalePrice > 0) {
                                val isWholesaleSelected = activeVariant == null && editableTag == "WHOLESALE"
                                DialogPillSegmentButton(
                                    selected = isWholesaleSelected,
                                    label = "পাইকারি (₹${"%.0f".format(product.wholesalePrice)})",
                                    icon = Icons.Default.Storefront,
                                    onClick = selectWholesale
                                )
                            }

                            // Option 3: Bulk Box (if available)
                            if (product?.hasBulkPricing() == true) {
                                val isBoxSelected = activeVariant == null && editableTag == "BULK BOX"
                                DialogPillSegmentButton(
                                    selected = isBoxSelected,
                                    label = "বক্স/বাল্ক (₹${"%.0f".format(product.getEffectiveBulkPrice())})",
                                    icon = Icons.Default.Inventory2,
                                    onClick = selectBulkBox
                                )
                            }

                            // Option 4: Pre-packed Barcode Variants
                            effectiveVariants.forEach { v ->
                                val isVarSelected = activeVariant?.barcode == v.barcode
                                DialogPillSegmentButton(
                                    selected = isVarSelected,
                                    label = "${v.getShortLabel()} (₹${"%.0f".format(v.price)})",
                                    icon = Icons.Default.QrCode,
                                    onClick = { selectVariant(v) }
                                )
                            }
                        }

                        if (activeVariant != null) {
                            Surface(
                                color = Color.White,
                                shape = RoundedCornerShape(6.dp),
                                border = androidx.compose.foundation.BorderStroke(0.5.dp, StorePrimary.copy(alpha = 0.3f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "ভ্যারিয়েন্ট বারকোড: ${activeBarcodeStr}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.5.sp,
                                        color = StorePrimary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "মূল্য: ₹${editablePriceText}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.5.sp,
                                        color = StoreGreenProfit,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                } else if (product != null) {
                    // Subtle chip allowing user to create a bulk pack variant if they wish
                    Surface(
                        onClick = { showInlineAddVariant = true },
                        color = StorePrimary.copy(alpha = 0.06f),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, StorePrimary.copy(alpha = 0.25f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.Layers, contentDescription = null, tint = StorePrimary, modifier = Modifier.size(16.dp))
                                Text(
                                    text = if (isBn) "আলাদা মাপের প্যাকেটের বারকোড চান? (৫০০গ্রা, ১কেজি...)" else "Different pack size barcode? (500g, 1Kg...)",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 11.5.sp,
                                    color = StorePrimary,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Text(
                                text = if (isBn) "+ ভ্যারিয়েন্ট" else "+ Add Pack",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = StorePrimary
                            )
                        }
                    }
                }

                // 1. Barcode Label Visual Preview Box (Matches paper aspect ratio)
                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, StorePrimary.copy(alpha = 0.35f)),
                    shadowElevation = 3.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp)
                    ) {
                        if (previewBitmap != null) {
                            Image(
                                bitmap = previewBitmap.asImageBitmap(),
                                contentDescription = "Label Graphic Preview",
                                modifier = Modifier
                                    .fillMaxWidth(0.96f)
                                    .heightIn(min = 110.dp, max = 170.dp)
                            )
                        } else {
                            val formattedMrp = if (currentMrp % 1.0 == 0.0) currentMrp.toLong().toString() else "%.2f".format(currentMrp)
                            val formattedPrice = if (currentPrice % 1.0 == 0.0) currentPrice.toLong().toString() else "%.2f".format(currentPrice)

                            // Top: Store Name Header (Centered, bold sans-serif, dark navy)
                            Text(
                                text = editableStoreName.uppercase(),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFF1E3A6E),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp)
                            )

                            // Row 1: Item Name (Left) & Tag / Branch (Right)
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Item : ${editableItemName.ifBlank { "ITEM" }.uppercase()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = TextDark,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (editableTag.isNotBlank()) {
                                    Text(
                                        text = editableTag.uppercase(),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = TextDark
                                    )
                                }
                            }

                            // Row 2: Quantity (Left) & Expiry Date (Right)
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Qty : ${editableQuantity.trim()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = TextDark
                                )
                                val cleanExp = EscPosPrinter.formatExpiryDate(editableExpiryDate)
                                if (!cleanExp.isNullOrBlank()) {
                                    Text(
                                        text = "Exp : $cleanExp",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = TextDark
                                    )
                                }
                            }

                            // Row 3: Offer Price (Left) & Struck MRP with % OFF (Right)
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (currentDiscPct > 0 && currentMrp > currentPrice) {
                                    Text(
                                        text = "Offer : $formattedPrice/-",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = TextDark
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = "MRP : $formattedMrp/-",
                                            style = MaterialTheme.typography.bodySmall.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough),
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextDark
                                        )
                                        Text(
                                            text = "$currentDiscPct% OFF",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Bold,
                                            color = TextDark
                                        )
                                    }
                                } else {
                                    val priceText = if (currentMrp > 0.0) "MRP : $formattedMrp/-" else if (currentPrice > 0.0) "Price : $formattedPrice/-" else "MRP : --"
                                    Text(
                                        text = priceText,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = TextDark
                                    )
                                }
                            }
                        }
                    }
                }

                // 2. MRP, Discount & Price Configuration Card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(StorePrimary.copy(alpha = 0.04f), RoundedCornerShape(10.dp))
                        .border(1.dp, StorePrimary.copy(alpha = 0.18f), RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (isBn) "💰 দাম ও ডিসকাউন্ট সেটিং (সঠিক MRP লজিক):" else "💰 Price & MRP Configuration (Proper Logic):",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = StorePrimary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = editableMrpText,
                            onValueChange = { input ->
                                if (input.length <= 12) {
                                    editableMrpText = input
                                }
                            },
                            label = { Text("MRP (₹)") },
                            placeholder = { Text("e.g. 225") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )

                        OutlinedTextField(
                            value = editablePriceText,
                            onValueChange = { input ->
                                if (input.length <= 12) {
                                    editablePriceText = input
                                }
                            },
                            label = { Text(if (isBn) "অফার / বিক্রয় মূল্য (₹) *" else "Offer / Selling Price (₹) *") },
                            placeholder = { Text("Selling Price") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Warning if Offer Price exceeds MRP
                    if (currentPrice > currentMrp && currentMrp > 0.0) {
                        Surface(
                            color = StoreOrangeWarning.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (isBn) "⚠️ অফার মূল্য MRP এর চেয়ে বেশি হতে পারে না।" else "⚠️ Offer price cannot exceed MRP.",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = StoreOrangeWarning,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    onClick = { editableMrpText = editablePriceText },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(if (isBn) "MRP ঠিক করুন" else "Fix MRP", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    // Quick Discount % Selection Chips
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = if (isBn) "দ্রুত ডিসকাউন্ট (% OFF নির্বাচন করুন):" else "Quick Discount (% OFF):",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextDark
                        )

                        val discountOptions = listOf(0, 5, 10, 15, 20, 25, 50)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            discountOptions.forEach { pct ->
                                val isSelected = currentDiscPct == pct
                                DialogPillSegmentButton(
                                    selected = isSelected,
                                    label = if (pct == 0) (if (isBn) "০%" else "None") else "$pct%",
                                    isRecommended = pct == 10,
                                    onClick = {
                                        val effMrp = if (currentMrp > 0.0) currentMrp else currentPrice
                                        if (effMrp > 0.0) {
                                            if (editableMrpText.isBlank() || currentMrp <= 0.0) {
                                                editableMrpText = if (effMrp % 1.0 == 0.0) effMrp.toLong().toString() else "%.2f".format(effMrp)
                                            }
                                            if (pct == 0) {
                                                editablePriceText = editableMrpText
                                            } else {
                                                val discountedPrice = effMrp * (1.0 - (pct / 100.0))
                                                editablePriceText = if (discountedPrice % 1.0 == 0.0) discountedPrice.toLong().toString() else "%.2f".format(discountedPrice)
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    // Savings banner if discount is present
                    if (currentDiscPct > 0 && currentMrp > currentPrice) {
                        val savings = currentMrp - currentPrice
                        Surface(
                            color = StoreGreenProfit.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.LocalOffer, contentDescription = null, tint = StoreGreenProfit, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isBn) {
                                        "গ্রাহক ছাড়: ${currentDiscPct}% OFF (সাশ্রয় ₹" + "%.2f".format(savings) + ")"
                                    } else {
                                        "Customer Discount: ${currentDiscPct}% OFF (Save ₹" + "%.2f".format(savings) + ")"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = StoreGreenProfit
                                )
                            }
                        }
                    }

                    // Optional toggle to sync edited price to store inventory
                    if (onPriceOrMrpChanged != null && activeVariant == null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    shouldUpdateStorePrice = !shouldUpdateStorePrice
                                    if (shouldUpdateStorePrice) {
                                        onPriceOrMrpChanged(currentPrice, if (currentMrp > 0.0) currentMrp else null)
                                    }
                                }
                                .padding(vertical = 2.dp)
                        ) {
                            Checkbox(
                                checked = shouldUpdateStorePrice,
                                onCheckedChange = { checked ->
                                    shouldUpdateStorePrice = checked
                                    if (checked) {
                                        onPriceOrMrpChanged(currentPrice, if (currentMrp > 0.0) currentMrp else null)
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isBn) "দোকানের মূল পণ্যেও এই বিক্রয়মূল্য/MRP সংরক্ষণ করুন" else "Also save new Price/MRP to store inventory",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextDark
                            )
                        }
                    }
                }

                // 3. Sticker Label Content: Store Name, Item, Quantity, Tag, Expiry Date
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (isBn) "🏷️ লেবেল টেক্সট, পরিমাণ ও মেয়াদ:" else "🏷️ Label Text, Quantity & Expiry Date:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextDark
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = editableStoreName,
                            onValueChange = { editableStoreName = it },
                            label = { Text(if (isBn) "দোকানের নাম" else "Store / Brand") },
                            placeholder = { Text("e.g. KALI MATA VARIETY STORE") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )

                        OutlinedTextField(
                            value = editableTag,
                            onValueChange = { editableTag = it },
                            label = { Text(if (isBn) "ট্যাগ / শাখা" else "Tag / Branch") },
                            placeholder = { Text("e.g. RETAIL") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    OutlinedTextField(
                        value = editableItemName,
                        onValueChange = { editableItemName = it },
                        label = { Text(if (isBn) "পণ্যের নাম (Item : )" else "Product Name (Item : )") },
                        placeholder = { Text("e.g. SLD") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = editableQuantity,
                        onValueChange = { editableQuantity = it },
                        label = { Text(if (isBn) "পরিমাণ / ইউনিট (Qty : )" else "Quantity / Unit (Qty : )") },
                        placeholder = { Text("e.g. 1 Piece, 1 Pc, 500ml, 1 Kg") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Quick Quantity Preset Chips (Horizontally Scrollable)
                    val qtyPresets = listOf(
                        "1 Piece", "1 Pc", "1 N", "1 Pkt", "1 Box",
                        "500ml", "1 L", "250ml", "200ml", "100ml",
                        "1 Kg", "500g", "250g", "100g", "2 Kg", "5 L"
                    )
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(horizontal = 2.dp)
                    ) {
                        items(qtyPresets, key = { it }) { preset ->
                            val isSelected = editableQuantity.trim().equals(preset, ignoreCase = true)
                            Surface(
                                color = if (isSelected) StorePrimary else MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(20.dp),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isSelected) StorePrimary else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                                ),
                                shadowElevation = if (isSelected) 1.dp else 0.dp,
                                modifier = Modifier.clickable { editableQuantity = preset }
                            ) {
                                Text(
                                    text = preset,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) Color.White else TextDark,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    // Expiry Date Field (Exp Date)
                    OutlinedTextField(
                        value = editableExpiryDate,
                        onValueChange = { 
                            // Sanitize double slashes or double dashes
                            editableExpiryDate = it.replace(Regex("""/{2,}"""), "/").replace(Regex("""-{2,}"""), "-")
                        },
                        label = { Text(if (isBn) "মেয়াদোত্তীর্ণের তারিখ (Exp Date)" else "Expiry Date (Exp Date)") },
                        placeholder = { Text("DD/MM/YY (e.g. 06/03/27 or 2027-03-06)") },
                        leadingIcon = {
                            Icon(Icons.Default.Event, contentDescription = null, tint = StorePrimary, modifier = Modifier.size(20.dp))
                        },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (editableExpiryDate.isNotBlank()) {
                                    IconButton(
                                        onClick = { editableExpiryDate = "" },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear", tint = TextMuted, modifier = Modifier.size(16.dp))
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        val cal = Calendar.getInstance()
                                        if (editableExpiryDate.isNotBlank()) {
                                            try {
                                                val parts = editableExpiryDate.split("-", "/")
                                                if (parts.size >= 3) {
                                                    if (parts[0].length == 4) {
                                                        cal.set(Calendar.YEAR, parts[0].toInt())
                                                        cal.set(Calendar.MONTH, parts[1].toInt() - 1)
                                                        cal.set(Calendar.DAY_OF_MONTH, parts[2].toInt())
                                                    } else if (parts[2].length == 4) {
                                                        cal.set(Calendar.YEAR, parts[2].toInt())
                                                        cal.set(Calendar.MONTH, parts[1].toInt() - 1)
                                                        cal.set(Calendar.DAY_OF_MONTH, parts[0].toInt())
                                                    }
                                                }
                                            } catch (_: Exception) {}
                                        }
                                        DatePickerDialog(
                                            context,
                                            { _, selectedYear, selectedMonth, selectedDay ->
                                                editableExpiryDate = String.format(Locale.US, "%04d-%02d-%02d", selectedYear, selectedMonth + 1, selectedDay)
                                            },
                                            cal.get(Calendar.YEAR),
                                            cal.get(Calendar.MONTH),
                                            cal.get(Calendar.DAY_OF_MONTH)
                                        ).show()
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(Icons.Default.CalendarMonth, contentDescription = "Pick Date", tint = StorePrimary, modifier = Modifier.size(20.dp))
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Quick Expiry Date Preset Pills (+1M, +3M, +6M, +1Y, +2Y, Clear)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(
                            "+1M" to 1,
                            "+3M" to 3,
                            "+6M" to 6,
                            "+1Y" to 12,
                            "+2Y" to 24
                        ).forEach { (label, months) ->
                            DialogPillSegmentButton(
                                selected = false,
                                label = label,
                                onClick = { editableExpiryDate = calculateExpiryPresetDate(months) },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        if (editableExpiryDate.isNotBlank()) {
                            DialogPillSegmentButton(
                                selected = false,
                                label = if (isBn) "ক্লিয়ার" else "Clear",
                                onClick = { editableExpiryDate = "" },
                                modifier = Modifier.weight(1.1f)
                            )
                        }
                    }

                    Text(
                        text = if (isBn) "💡 স্টিকারে Qty-এর পাশে Exp ছাপা হবে (যেমন: Exp : 12/26 বা 31/12/2026)" else "💡 Prints on label next to Qty (e.g. Exp : 12/26 or 31/12/2026)",
                        fontSize = 10.5.sp,
                        color = TextMuted,
                        lineHeight = 13.sp,
                        modifier = Modifier.padding(horizontal = 2.dp)
                    )
                }

                // 3B. Barcode Value, Generation & Conflict Management
                val isConflictWithSinglePiece = (editableTag == "BULK BOX" || activeVariant != null) &&
                        activeBarcodeStr.trim().isNotBlank() &&
                        barcodeStr.isNotBlank() &&
                        activeBarcodeStr.trim() == barcodeStr.trim()

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isConflictWithSinglePiece) StoreRedAlert.copy(alpha = 0.08f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            RoundedCornerShape(10.dp)
                        )
                        .border(
                            1.dp,
                            if (isConflictWithSinglePiece) StoreRedAlert.copy(alpha = 0.4f)
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                            RoundedCornerShape(10.dp)
                        )
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(Icons.Default.QrCode, contentDescription = null, tint = StorePrimary, modifier = Modifier.size(16.dp))
                            Text(
                                text = if (isBn) "বারকোড নম্বর ও ম্যাপিং:" else "Barcode Value & Mapping:",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextDark
                            )
                        }

                        // Barcode format chip (EAN-13, UPC-A, Code-128)
                        val cleanCode = activeBarcodeStr.trim().replace(" ", "")
                        val isEan13 = EscPosPrinter.isValidEan13(cleanCode)
                        val isUpcA = EscPosPrinter.isValidUpcA(cleanCode)
                        Surface(
                            color = if (isEan13 || isUpcA) StoreGreenProfit.copy(alpha = 0.15f) else StorePrimary.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = when {
                                    isEan13 -> "✓ GS1 EAN-13"
                                    isUpcA -> "✓ UPC-A"
                                    cleanCode.all { it.isDigit() } && cleanCode.length == 8 -> "✓ EAN-8"
                                    else -> "Code-128 (${cleanCode.length} chars)"
                                },
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isEan13 || isUpcA) StoreGreenProfit else StorePrimary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    OutlinedTextField(
                        value = activeBarcodeStr,
                        onValueChange = { newVal ->
                            activeBarcodeStr = newVal.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
                            // If currently on a variant or Bulk Box, update the variant
                            if (activeVariant != null) {
                                val updated = activeVariant!!.copy(barcode = activeBarcodeStr)
                                effectiveVariants = effectiveVariants.map { if (it.barcode == activeVariant!!.barcode) updated else it }
                                activeVariant = updated
                                onSaveNewVariant?.invoke(updated)
                            }
                        },
                        label = {
                            Text(
                                text = if (editableTag == "BULK BOX") {
                                    if (isBn) "বক্স বারকোড (Box Barcode)" else "Box Barcode"
                                } else if (activeVariant != null) {
                                    if (isBn) "ভ্যারিয়েন্ট বারকোড (Variant Barcode)" else "Variant Barcode"
                                } else {
                                    if (isBn) "খুচরা বারকোড (Retail Barcode)" else "Retail Barcode"
                                }
                            )
                        },
                        placeholder = { Text("e.g. 8901234567890") },
                        leadingIcon = {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = StorePrimary, modifier = Modifier.size(20.dp))
                        },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (activeBarcodeStr.isNotBlank()) {
                                    IconButton(
                                        onClick = { activeBarcodeStr = "" },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear", tint = TextMuted, modifier = Modifier.size(16.dp))
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        var newGen = EscPosPrinter.generateValidEan13Barcode("890")
                                        if (editableTag == "BULK BOX" || activeVariant != null) {
                                            while (newGen == barcodeStr) {
                                                newGen = EscPosPrinter.generateValidEan13Barcode("890")
                                            }
                                        }
                                        activeBarcodeStr = newGen
                                        if (activeVariant != null) {
                                            val updated = activeVariant!!.copy(barcode = newGen)
                                            effectiveVariants = effectiveVariants.map { if (it.barcode == activeVariant!!.barcode) updated else it }
                                            activeVariant = updated
                                            onSaveNewVariant?.invoke(updated)
                                        } else if (editableTag == "BULK BOX" && product != null) {
                                            val bulkQty = product.getEffectiveBulkQuantity()
                                            val bulkUnit = product.getEffectiveBulkUnit()
                                            val newBoxVariant = BarcodeVariant(
                                                barcode = newGen,
                                                unitType = bulkUnit,
                                                quantity = bulkQty,
                                                price = currentPrice,
                                                label = "Box (${if (bulkQty % 1.0 == 0.0) bulkQty.toInt() else bulkQty} $bulkUnit)"
                                            )
                                            effectiveVariants = effectiveVariants + newBoxVariant
                                            activeVariant = newBoxVariant
                                            onSaveNewVariant?.invoke(newBoxVariant)
                                        }
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = "Generate EAN-13", tint = StoreSaffronAccent, modifier = Modifier.size(20.dp))
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Conflict Warning & Resolution Banner
                    if (isConflictWithSinglePiece) {
                        Surface(
                            color = StoreRedAlert.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, StoreRedAlert.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Icon(Icons.Default.Warning, contentDescription = null, tint = StoreRedAlert, modifier = Modifier.size(16.dp))
                                    Text(
                                        text = if (isBn) "⚠️ বারকোড কনফ্লিক্ট!" else "⚠️ Barcode Collision Warning!",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = StoreRedAlert
                                    )
                                }
                                Text(
                                    text = if (isBn) {
                                        "এই বারকোডটি খুচরা পণ্যের (১ পিস) বারকোডের সাথে হুবহু এক! বাল্ক বক্স বা ভ্যারিয়েন্টের জন্য আলাদা বারকোড থাকা বাধ্যতামূলক, নাহলে স্ক্যানার পুরো বক্সকে সিঙ্গেল প্যাকেট হিসেবে গণ্য করবে।"
                                    } else {
                                        "This barcode is identical to the Single Piece retail barcode ($barcodeStr). A Bulk Box or Pack variant must have its own distinct barcode so checkout scanners charge the Box price (₹$currentPrice) instead of single piece."
                                    },
                                    fontSize = 11.sp,
                                    color = TextDark,
                                    lineHeight = 14.sp
                                )

                                Button(
                                    onClick = {
                                        var freshBarcode = EscPosPrinter.generateValidEan13Barcode("890")
                                        while (freshBarcode == barcodeStr) {
                                            freshBarcode = EscPosPrinter.generateValidEan13Barcode("890")
                                        }
                                        activeBarcodeStr = freshBarcode
                                        if (activeVariant != null) {
                                            val updated = activeVariant!!.copy(barcode = freshBarcode)
                                            effectiveVariants = effectiveVariants.map { if (it.barcode == activeVariant!!.barcode) updated else it }
                                            activeVariant = updated
                                            onSaveNewVariant?.invoke(updated)
                                        } else if (editableTag == "BULK BOX" && product != null) {
                                            val bulkQty = product.getEffectiveBulkQuantity()
                                            val bulkUnit = product.getEffectiveBulkUnit()
                                            val newBoxVariant = BarcodeVariant(
                                                barcode = freshBarcode,
                                                unitType = bulkUnit,
                                                quantity = bulkQty,
                                                price = currentPrice,
                                                label = "Box (${if (bulkQty % 1.0 == 0.0) bulkQty.toInt() else bulkQty} $bulkUnit)"
                                            )
                                            effectiveVariants = effectiveVariants + newBoxVariant
                                            activeVariant = newBoxVariant
                                            onSaveNewVariant?.invoke(newBoxVariant)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = StoreRedAlert),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (isBn) "আলাদা বক্স বারকোড তৈরি করুন" else "Generate Distinct Box Barcode",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    } else if (editableTag == "BULK BOX" || activeVariant != null) {
                        // Helpful note that this distinct barcode is mapped
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(horizontal = 2.dp)
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StoreGreenProfit, modifier = Modifier.size(13.dp))
                            Text(
                                text = if (isBn) "✓ এই বারকোডটি স্ক্যান করলে সরাসরি ${editableQuantity}-এর মূল্য (₹${editablePriceText}) যোগ হবে"
                                else "✓ Scanning this barcode will ring up ${editableQuantity} at ₹${editablePriceText}",
                                fontSize = 10.5.sp,
                                color = StoreGreenProfit,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                // 4. Sticker Label Size Selector
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                        .padding(9.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(Icons.Default.AspectRatio, contentDescription = null, tint = StorePrimary, modifier = Modifier.size(15.dp))
                            Text(
                                text = if (isBn) "লেবেল স্টিকার সাইজ:" else "Sticker Label Size:",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextDark
                            )
                        }
                        Surface(
                            color = StorePrimary.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "${selectedSize.widthMm} × ${selectedSize.heightMm} mm" + (if (selectedSize.gapMm > 0) " (Gap ${selectedSize.gapMm}mm)" else " (Roll)"),
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = StorePrimary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    // Row 1: 50x25, 50x30, 40x30
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        DialogPillSegmentButton(
                            selected = selectedSize == BarcodeLabelSize.SIZE_50_25,
                            label = "50 × 25 mm",
                            onClick = { selectedSize = BarcodeLabelSize.SIZE_50_25 },
                            modifier = Modifier.weight(1f)
                        )
                        DialogPillSegmentButton(
                            selected = selectedSize == BarcodeLabelSize.SIZE_50_30,
                            label = "50 × 30 mm",
                            onClick = { selectedSize = BarcodeLabelSize.SIZE_50_30 },
                            modifier = Modifier.weight(1f)
                        )
                        DialogPillSegmentButton(
                            selected = selectedSize == BarcodeLabelSize.SIZE_40_30,
                            label = "40 × 30 mm",
                            onClick = { selectedSize = BarcodeLabelSize.SIZE_40_30 },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Row 2: 38x25, 58mm Roll
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        DialogPillSegmentButton(
                            selected = selectedSize == BarcodeLabelSize.SIZE_38_25,
                            label = "38 × 25 mm",
                            onClick = { selectedSize = BarcodeLabelSize.SIZE_38_25 },
                            modifier = Modifier.weight(1f)
                        )
                        DialogPillSegmentButton(
                            selected = selectedSize == BarcodeLabelSize.SIZE_58_CONT,
                            label = if (isBn) "৫৮ মিমি (রোল)" else "58 mm (Roll)",
                            onClick = { selectedSize = BarcodeLabelSize.SIZE_58_CONT },
                            modifier = Modifier.weight(1.25f)
                        )
                    }
                }

                // 3. Printer Protocol Mode (TSPL with Gap vs ESC/POS), Direction & Darkness Tuning
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(StorePrimary.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(Icons.Default.Print, contentDescription = null, tint = StorePrimary, modifier = Modifier.size(15.dp))
                            Text(
                                text = if (isBn) "প্রিন্টার মোড ও প্রোটোকল:" else "Printer Mode & Protocol:",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = StorePrimary
                            )
                        }

                        // Calibrate Gap button
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = StorePrimary.copy(alpha = 0.12f),
                            modifier = Modifier.clickable {
                                if (selectedPrinterAddress.isNullOrBlank()) {
                                    Toast.makeText(context, "Select printer first", Toast.LENGTH_SHORT).show()
                                    return@clickable
                                }
                                isCalibrating = true
                                viewModel.calibratePrinterGap(
                                    protocol = selectedProtocol,
                                    widthMm = selectedSize.widthMm,
                                    heightMm = selectedSize.heightMm,
                                    gapMm = selectedSize.gapMm
                                ) { success, msg ->
                                    isCalibrating = false
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isCalibrating) {
                                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = StorePrimary)
                                } else {
                                    Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(13.dp), tint = StorePrimary)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (isBn) "গ্যাপ ফিড করুন" else "Align Gap",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = StorePrimary
                                    )
                                }
                            }
                        }
                    }

                    // TSPL vs ESC/POS
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        DialogPillSegmentButton(
                            selected = selectedProtocol == "TSPL",
                            label = if (isBn) "TSPL (স্টিকার রোল)" else "TSPL (Stickers with Gap)",
                            icon = Icons.Default.Label,
                            onClick = { selectedProtocol = "TSPL" },
                            modifier = Modifier.weight(1f)
                        )
                        DialogPillSegmentButton(
                            selected = selectedProtocol == "ESCPOS",
                            label = if (isBn) "ESC/POS (রিসিপ্ট রোল)" else "ESC/POS (Continuous)",
                            icon = Icons.Default.ReceiptLong,
                            onClick = { selectedProtocol = "ESCPOS" },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Orientation Selector (Normal 0° vs Inverted 180°)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.width(78.dp)
                        ) {
                            Icon(Icons.Default.SyncAlt, contentDescription = null, tint = TextMuted, modifier = Modifier.size(13.dp))
                            Text(
                                text = if (isBn) "দিকমুখ:" else "Direction:",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = TextMuted
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            DialogPillSegmentButton(
                                selected = !invertOrientation,
                                label = if (isBn) "সোজা (0°)" else "Normal (0°)",
                                icon = Icons.Default.ArrowUpward,
                                onClick = { invertOrientation = false },
                                modifier = Modifier.weight(1f)
                            )
                            DialogPillSegmentButton(
                                selected = invertOrientation,
                                label = if (isBn) "উল্টো (180°)" else "Inverted (180°)",
                                icon = Icons.Default.RotateRight,
                                onClick = { invertOrientation = true },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // FIXED & UPGRADED: Dedicated Print Darkness / Density Section
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Contrast,
                                    contentDescription = null,
                                    tint = StorePrimary,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = if (isBn) "প্রিন্ট ডার্কনেস (ঘনত্ব):" else "Print Darkness (Density):",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = TextDark
                                )
                            }

                            // Dynamic explanatory badge
                            Surface(
                                color = when (selectedDensity) {
                                    "DARK" -> StorePrimary.copy(alpha = 0.12f)
                                    "EXTRA_DARK" -> StoreOrangeWarning.copy(alpha = 0.15f)
                                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                },
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = when (selectedDensity) {
                                        "LIGHT" -> if (isBn) "হালকা (-15%)" else "Light (-15%)"
                                        "NORMAL" -> if (isBn) "স্বাভাবিক (মানক)" else "Normal (Std)"
                                        "DARK" -> if (isBn) "গাঢ় ★ (প্রস্তাবিত)" else "Dark ★ (Recommended)"
                                        "EXTRA_DARK" -> if (isBn) "অতি গাঢ় (+30%)" else "Extra Dark (+30%)"
                                        else -> if (isBn) "গাঢ় ★" else "Dark ★"
                                    },
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = when (selectedDensity) {
                                        "DARK" -> StorePrimary
                                        "EXTRA_DARK" -> StoreOrangeWarning
                                        else -> TextDark
                                    },
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        // Full-width 4-segment row: single line, no text wrapping
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val densityOptions = listOf(
                                "LIGHT" to (if (isBn) "হালকা" else "Light"),
                                "NORMAL" to (if (isBn) "স্বাভাবিক" else "Normal"),
                                "DARK" to (if (isBn) "গাঢ় ★" else "Dark ★"),
                                "EXTRA_DARK" to (if (isBn) "অতি গাঢ়" else "Extra Dark")
                            )

                            densityOptions.forEach { (key, label) ->
                                val isSelected = selectedDensity == key
                                DialogPillSegmentButton(
                                    selected = isSelected,
                                    label = label,
                                    isRecommended = key == "DARK",
                                    onClick = {
                                        selectedDensity = key
                                        StoreInfoManager.thermalPrinterDensity = key
                                        viewModel.updatePrinterDensity(key)
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    // Seznik / 2-in-1 Hardware Tip Toggle
                    TextButton(
                        onClick = { showPrinterGuide = !showPrinterGuide },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(13.dp), tint = StorePrimary)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (showPrinterGuide) {
                                if (isBn) "টিপস লুকান ▲" else "Hide Hardware Guide ▲"
                            } else {
                                if (isBn) "💡 2-in-1 / Seznik প্রিন্টার সেটিংস নির্দেশিকা ▼" else "💡 2-in-1 Printer (Seznik) Guide ▼"
                            },
                            fontSize = 11.sp,
                            color = StorePrimary,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    if (showPrinterGuide) {
                        Surface(
                            color = Color.White,
                            shape = RoundedCornerShape(6.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, StorePrimary.copy(alpha = 0.25f)),
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
                        ) {
                            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = if (isBn) "📌 Seznik 2-in-1 প্রিন্টারের জন্য জরুরি:" else "📌 Important for Seznik 2-in-1 Printers:",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    color = StorePrimary
                                )
                                Text(
                                    text = if (isBn)
                                        "১. স্টিকার প্রিন্ট করতে প্রিন্টার অফ করে 'MODE' বাটন চেপে ধরে 'POWER' অন করুন (বিপ শোনা পর্যন্ত)। এটি হার্ডওয়্যারকে 'Label Mode' এ নেবে।\n২. অ্যাপে মোড 'TSPL' রাখুন যাতে স্টিকারের গ্যাপ স্বয়ংক্রিয়ভাবে ডিটেক্ট হয়।"
                                    else
                                        "1. For stickers, turn printer OFF, then hold 'MODE' and press 'POWER' ON until it beeps (switches hardware to Label Mode).\n2. Keep app mode on 'TSPL' for automatic gap alignment.",
                                    fontSize = 10.5.sp,
                                    lineHeight = 14.sp,
                                    color = Color(0xFF2D3748)
                                )
                            }
                        }
                    }
                }

                // 4. Custom Quantity Selector
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                        .padding(8.dp)
                ) {
                    Text(
                        text = if (isBn) "প্রিন্ট লেবেল সংখ্যা:" else "Print Label Quantity:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        IconButton(
                            onClick = {
                                val current = labelQty
                                if (current > 1) labelQtyText = (current - 1).toString()
                            },
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                                .size(36.dp)
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = "Decrease")
                        }

                        OutlinedTextField(
                            value = labelQtyText,
                            onValueChange = { input ->
                                if (input.isEmpty() || (input.all { it.isDigit() } && input.length <= 4)) {
                                    labelQtyText = input
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(
                                textAlign = TextAlign.Center,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            ),
                            modifier = Modifier.weight(1f)
                        )

                        IconButton(
                            onClick = {
                                val current = labelQty
                                labelQtyText = (current + 1).toString()
                            },
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                                .size(36.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Increase")
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Preset quantity pills
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        listOf(1, 2, 5, 10, 20).forEach { preset ->
                            val isSelected = labelQty == preset
                            DialogPillSegmentButton(
                                selected = isSelected,
                                label = "${preset}x",
                                onClick = { labelQtyText = preset.toString() },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // 5. Bluetooth Thermal Printer Selection Card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                        .padding(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.Bluetooth, contentDescription = null, tint = StorePrimary, modifier = Modifier.size(16.dp))
                            Text(
                                text = if (isBn) "থার্মাল প্রিন্টার:" else "Thermal Printer:",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        IconButton(
                            onClick = { viewModel.loadPairedPrinters() },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = StorePrimary, modifier = Modifier.size(14.dp))
                        }
                    }

                    if (pairedPrinters.isEmpty()) {
                        Text(
                            text = if (isBn) "কোনো পেয়ার করা প্রিন্টার পাওয়া যায়নি (ফোনের ব্লুটুথ সেটিংসে আগে পেয়ার করুন)" else "No paired Bluetooth printers found. Pair in Android Bluetooth Settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = StoreRedAlert,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    } else {
                        var expanded by remember { mutableStateOf(false) }
                        val activeDevice = pairedPrinters.find { it.address == selectedPrinterAddress }

                        ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = !expanded },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        ) {
                            OutlinedTextField(
                                value = activeDevice?.name ?: (if (isBn) "প্রিন্টার নির্বাচন করুন" else "Select Printer"),
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth(),
                                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                            )
                            ExposedDropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                pairedPrinters.forEach { device ->
                                    DropdownMenuItem(
                                        text = { Text("${device.name} (${device.address})") },
                                        onClick = {
                                            viewModel.selectedPrinterAddress = device.address
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                if (statusMsg.isNotBlank()) {
                    Text(
                        text = statusMsg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (statusMsg.contains("Error") || statusMsg.contains("No")) StoreRedAlert else StoreGreenProfit,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Row with Test Print 1 label and Batch Print
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Test 1 Label button
                    OutlinedButton(
                        onClick = {
                            if (selectedPrinterAddress.isNullOrBlank()) {
                                statusMsg = if (isBn) "দয়া করে প্রিন্টার নির্বাচন করুন" else "Please select a thermal printer"
                                Toast.makeText(context, statusMsg, Toast.LENGTH_SHORT).show()
                                return@OutlinedButton
                            }
                            isPrinting = true
                            statusMsg = if (isBn) "১টি টেস্ট লেবেল প্রিন্ট হচ্ছে..." else "Printing 1 test label..."
                            viewModel.printBarcodeLabels(
                                productName = editableItemName,
                                barcodeStr = activeBarcodeStr,
                                price = currentPrice,
                                mrp = currentMrp,
                                quantity = 1,
                                protocol = selectedProtocol,
                                widthMm = selectedSize.widthMm,
                                heightMm = selectedSize.heightMm,
                                gapMm = selectedSize.gapMm,
                                invertOrientation = invertOrientation,
                                density = selectedDensity,
                                quantityOrUnit = editableQuantity,
                                subtitleOrTag = editableTag,
                                discountPercentage = if (currentDiscPct > 0) currentDiscPct else null,
                                storeName = editableStoreName,
                                expiryDate = editableExpiryDate.trim().ifBlank { null },
                                labelStyle = selectedLabelStyle
                            ) { success, msg ->
                                isPrinting = false
                                statusMsg = msg
                                if (success) {
                                    Toast.makeText(context, if (isBn) "টেস্ট প্রিন্ট সফল!" else "Test print successful!", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier.weight(0.42f),
                        shape = RoundedCornerShape(8.dp),
                        enabled = !isPrinting && activeBarcodeStr.isNotBlank()
                    ) {
                        Icon(Icons.Default.Science, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isBn) "টেস্ট (১টি)" else "Test (1x)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Button 1: Print via Thermal Printer
                    Button(
                        onClick = {
                            if (shouldUpdateStorePrice && activeVariant == null) {
                                onPriceOrMrpChanged?.invoke(currentPrice, if (currentMrp > 0.0) currentMrp else null)
                            }
                            if (selectedPrinterAddress.isNullOrBlank()) {
                                statusMsg = if (isBn) "দয়া করে প্রিন্টার নির্বাচন করুন" else "Please select a thermal printer"
                                Toast.makeText(context, statusMsg, Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            isPrinting = true
                            statusMsg = if (isBn) "${labelQty}টি বারকোড প্রিন্ট হচ্ছে..." else "Printing $labelQty barcode(s)..."
                            viewModel.printBarcodeLabels(
                                productName = editableItemName,
                                barcodeStr = activeBarcodeStr,
                                price = currentPrice,
                                mrp = currentMrp,
                                quantity = labelQty,
                                protocol = selectedProtocol,
                                widthMm = selectedSize.widthMm,
                                heightMm = selectedSize.heightMm,
                                gapMm = selectedSize.gapMm,
                                invertOrientation = invertOrientation,
                                density = selectedDensity,
                                quantityOrUnit = editableQuantity,
                                subtitleOrTag = editableTag,
                                discountPercentage = if (currentDiscPct > 0) currentDiscPct else null,
                                storeName = editableStoreName,
                                expiryDate = editableExpiryDate.trim().ifBlank { null },
                                labelStyle = selectedLabelStyle
                            ) { success, msg ->
                                isPrinting = false
                                statusMsg = msg
                                if (success) {
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier.weight(0.58f),
                        colors = ButtonDefaults.buttonColors(containerColor = StoreGreenProfit),
                        shape = RoundedCornerShape(8.dp),
                        enabled = !isPrinting && activeBarcodeStr.isNotBlank()
                    ) {
                        if (isPrinting) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Printing...")
                        } else {
                            Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isBn) "প্রিন্ট (${labelQty}টি)" else "Print (${labelQty}x)",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Button 2: Print/Save PDF Label Sheet via System
                OutlinedButton(
                    onClick = {
                        if (shouldUpdateStorePrice && activeVariant == null) {
                            onPriceOrMrpChanged?.invoke(currentPrice, if (currentMrp > 0.0) currentMrp else null)
                        }
                        val pdfFile = PdfReceiptHelper.generateBarcodeLabelsPdf(
                            context = context,
                            productName = editableItemName,
                            barcodeStr = activeBarcodeStr,
                            price = currentPrice,
                            mrp = currentMrp,
                            quantity = labelQty,
                            quantityOrUnit = editableQuantity,
                            subtitleOrTag = editableTag,
                            discountPercentage = if (currentDiscPct > 0) currentDiscPct else null,
                            storeName = editableStoreName,
                            expiryDate = editableExpiryDate.trim().ifBlank { null },
                            labelStyle = selectedLabelStyle
                        )
                        if (pdfFile != null) {
                            PdfReceiptHelper.printPdf(context, pdfFile, "Barcode_Labels_$activeBarcodeStr")
                        } else {
                            Toast.makeText(context, "Failed to generate PDF sheet", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    enabled = activeBarcodeStr.isNotBlank()
                ) {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp), tint = StorePrimary)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isBn) "লেবেল শিট PDF ডাউনলোড/প্রিন্ট" else "Print / Save PDF Label Sheet",
                        fontWeight = FontWeight.Bold,
                        color = StorePrimary
                    )
                }

                // Dedicated Batch Print All Variants Section (if multiple variants exist)
                if (effectiveVariants.size >= 2) {
                    Surface(
                        color = StorePrimary.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, StorePrimary.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = if (isBn) "⚡ সব বাল্ক ভ্যারিয়েন্ট একসাথে প্রিন্ট করুন (${effectiveVariants.size}টি ভ্যারিয়েন্ট):" else "⚡ Bulk Print All Variants (${effectiveVariants.size} variants):",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = StorePrimary
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // 1. Thermal print all variants
                                OutlinedButton(
                                    onClick = {
                                        if (selectedPrinterAddress.isNullOrBlank()) {
                                            statusMsg = if (isBn) "দয়া করে প্রিন্টার নির্বাচন করুন" else "Please select a thermal printer"
                                            Toast.makeText(context, statusMsg, Toast.LENGTH_SHORT).show()
                                            return@OutlinedButton
                                        }
                                        isBatchPrintingAll = true
                                        statusMsg = if (isBn) "সব ভ্যারিয়েন্ট প্রিন্ট হচ্ছে..." else "Printing all variants..."
                                        coroutineScope.launch {
                                            for (v in effectiveVariants) {
                                                val vTitle = v.getDisplayTitle(productName)
                                                viewModel.printBarcodeLabels(
                                                    productName = vTitle,
                                                    barcodeStr = v.barcode,
                                                    price = v.price,
                                                    mrp = v.price,
                                                    quantity = labelQty,
                                                    protocol = selectedProtocol,
                                                    widthMm = selectedSize.widthMm,
                                                    heightMm = selectedSize.heightMm,
                                                    gapMm = selectedSize.gapMm,
                                                    invertOrientation = invertOrientation,
                                                    density = selectedDensity,
                                                    quantityOrUnit = v.getShortLabel(),
                                                    subtitleOrTag = "VARIANT",
                                                    discountPercentage = null,
                                                    storeName = editableStoreName,
                                                    expiryDate = editableExpiryDate.trim().ifBlank { null },
                                                    labelStyle = selectedLabelStyle
                                                ) { _, _ -> }
                                                delay(400)
                                            }
                                            isBatchPrintingAll = false
                                            statusMsg = if (isBn) "সবগুলো ভ্যারিয়েন্ট প্রিন্ট সফল!" else "All variants printed successfully!"
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp),
                                    enabled = !isBatchPrintingAll && !isPrinting
                                ) {
                                    Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (isBn) "সব থার্মাল (${labelQty}টি করে)" else "Print All (${labelQty}x each)",
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                // 2. Multi-variant PDF Sheet
                                OutlinedButton(
                                    onClick = {
                                        val items = effectiveVariants.map { v ->
                                            PdfReceiptHelper.MultiVariantBarcodeItem(
                                                productName = v.getDisplayTitle(productName),
                                                barcodeStr = v.barcode,
                                                price = v.price,
                                                mrp = v.price,
                                                quantityOrUnit = v.getShortLabel(),
                                                subtitleOrTag = "VARIANT"
                                            )
                                        }
                                        val pdfFile = PdfReceiptHelper.generateMultiVariantBarcodeLabelsPdf(
                                            context = context,
                                            items = items,
                                            copiesPerItem = labelQty,
                                            storeName = editableStoreName,
                                            expiryDate = editableExpiryDate.trim().ifBlank { null },
                                            labelStyle = selectedLabelStyle
                                        )
                                        if (pdfFile != null) {
                                            PdfReceiptHelper.printPdf(context, pdfFile, "All_Variants_${System.currentTimeMillis()}")
                                        } else {
                                            Toast.makeText(context, "Failed to generate PDF sheet", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(15.dp), tint = StorePrimary)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (isBn) "সব ভ্যারিয়েন্ট PDF" else "All Variants PDF",
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = StorePrimary
                                    )
                                }
                            }
                        }
                    }
                }

                // Button 3: Close Button
                TextButton(
                    onClick = {
                        onPriceOrMrpChanged?.invoke(currentPrice, if (currentMrp > 0.0) currentMrp else null)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isBn) "বন্ধ করুন" else "Close",
                        fontWeight = FontWeight.Medium,
                        color = TextMuted
                    )
                }
            }
        },
        dismissButton = null
    )

    // Inline Dialog for Quick Adding Bulk Pack Barcode Variant
    if (showInlineAddVariant) {
        var newVarBarcode by remember { mutableStateOf(EscPosPrinter.generateValidEan13Barcode()) }
        var newVarUnit by remember { mutableStateOf(product?.getEffectiveSecondaryUnit() ?: product?.unitType ?: "piece") }
        var newVarQty by remember { mutableStateOf("") }
        var newVarPrice by remember { mutableStateOf("") }
        var newVarLabel by remember { mutableStateOf("") }
        var newVarError by remember { mutableStateOf<String?>(null) }

        val commonUnits = listOf("gram", "kg", "ml", "litre", "piece", "packet", "box", "dozen", "strip", "bottle")

        AlertDialog(
            onDismissRequest = { showInlineAddVariant = false },
            title = {
                Text(
                    text = if (isBn) "বাল্ক ভ্যারিয়েন্ট বারকোড যোগ করুন" else "Add Bulk Pack Variant Barcode",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (isBn) "যেমন ৫০০ গ্রাম প্যাকেট = ₹৪০, ১ কেজি = ₹৭৫, ১২টির বক্স = ₹৪৫০।" else "E.g. 500g pouch = ₹40, 1 Kg pack = ₹75, Box of 12 = ₹450.",
                        fontSize = 11.5.sp,
                        color = TextMuted
                    )

                    OutlinedTextField(
                        value = newVarBarcode,
                        onValueChange = { newVarBarcode = it; newVarError = null },
                        label = { Text("Variant Barcode *") },
                        trailingIcon = {
                            IconButton(onClick = { newVarBarcode = EscPosPrinter.generateValidEan13Barcode() }) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = "Auto-generate", tint = StoreSaffronAccent)
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = newVarQty,
                            onValueChange = { newVarQty = it; newVarError = null },
                            label = { Text("Quantity *") },
                            placeholder = { Text("e.g. 500 or 1") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )

                        OutlinedTextField(
                            value = newVarPrice,
                            onValueChange = { newVarPrice = it; newVarError = null },
                            label = { Text("Selling Price (₹) *") },
                            placeholder = { Text("e.g. 45") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Unit selector chips
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        commonUnits.forEach { u ->
                            val isSel = newVarUnit.equals(u, ignoreCase = true)
                            FilterChip(
                                selected = isSel,
                                onClick = { newVarUnit = u },
                                label = { Text(BengaliReceiptTranslator.translateUnit(u, isBn)) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = newVarLabel,
                        onValueChange = { newVarLabel = it },
                        label = { Text("Display Label (Optional)") },
                        placeholder = { Text("e.g. 500g Pouch, Family Pack") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (newVarError != null) {
                        Text(newVarError!!, color = StoreRedAlert, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cleanCode = newVarBarcode.trim()
                        if (cleanCode.isBlank()) {
                            newVarError = "Barcode is required"
                            return@Button
                        }
                        val q = newVarQty.toDoubleOrNull()
                        if (q == null || q <= 0.0) {
                            newVarError = "Enter valid quantity > 0"
                            return@Button
                        }
                        val p = newVarPrice.toDoubleOrNull()
                        if (p == null || p < 0.0) {
                            newVarError = "Enter valid price >= 0"
                            return@Button
                        }
                        val newVar = BarcodeVariant(
                            barcode = cleanCode,
                            unitType = newVarUnit.trim().ifBlank { "piece" },
                            quantity = q,
                            price = p,
                            label = newVarLabel.trim()
                        )
                        effectiveVariants = effectiveVariants + newVar
                        onSaveNewVariant?.invoke(newVar)
                        selectVariant(newVar)
                        showInlineAddVariant = false
                        Toast.makeText(context, if (isBn) "ভ্যারিয়েন্ট তৈরি ও নির্বাচন করা হয়েছে!" else "Variant created & selected!", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StorePrimary)
                ) {
                    Text(if (isBn) "সংরক্ষণ ও নির্বাচন" else "Save & Select")
                }
            },
            dismissButton = {
                TextButton(onClick = { showInlineAddVariant = false }) {
                    Text(if (isBn) "বাতিল" else "Cancel")
                }
            }
        )
    }
}

@Composable
private fun DialogPillSegmentButton(
    selected: Boolean,
    label: String,
    isRecommended: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = if (selected) {
        StorePrimary
    } else {
        MaterialTheme.colorScheme.surface
    }
    val contentColor = if (selected) {
        Color.White
    } else {
        TextDark
    }
    val borderColor = if (selected) {
        StorePrimary
    } else if (isRecommended) {
        StorePrimary.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    }

    Surface(
        modifier = modifier
            .height(34.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        shadowElevation = if (selected) 1.5.dp else 0.dp
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                }
                Text(
                    text = label,
                    fontSize = if (label.length > 9) 10.sp else 11.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = contentColor,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

