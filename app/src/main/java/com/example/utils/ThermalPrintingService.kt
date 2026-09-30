package com.example.utils

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.example.data.local.dao.SaleWithItems
import com.example.data.local.entities.Customer
import com.example.data.local.entities.LedgerEntry
import com.example.data.local.entities.Sale
import com.example.data.local.entities.SaleItem
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.EnumMap
import java.util.Locale
import java.util.UUID

/**
 * Production-grade Thermal Printing Service Module dedicated to 58mm POS thermal printers.
 *
 * Technical Specifications for 58mm Thermal Heads:
 * - Standard Paper Width: 58 mm
 * - Printable Width: 48 mm (~384 dots at 203 DPI, exactly 48 bytes per line)
 * - Safe Left/Right Margin Padding: 12 - 16 dots
 * - Protocol: ESC/POS Raster (`GS v 0 \x00`) with high-contrast thresholding
 *
 * Ensures:
 * 1. Pixel-perfect alignment for 3-column items, headers, metadata, and totals.
 * 2. Adaptive font scaling preventing side-truncation or diagonal skewing.
 * 3. Dedicated quiet-zone image padding for 1D Barcodes and 2D UPI QR codes.
 * 4. Full native English & Bengali text rendering via Android Canvas & StaticLayout.
 */
object ThermalPrintingService {

    const val THERMAL_58MM_WIDTH_DOTS = 384
    const val BYTES_PER_LINE_58MM = 48 // 384 / 8
    private const val BT_SPP_UUID_STRING = "00001101-0000-1000-8000-00805F9B34FB"

    data class ThermalReceiptConfig(
        val isBengali: Boolean = false,
        val showStoreHeader: Boolean = true,
        val showCustomerInfo: Boolean = true,
        val showStaffInfo: Boolean = true,
        val showBarcode: Boolean = false,
        val showPaymentQr: Boolean = true,
        val showFooterNote: Boolean = true,
        val customFooterText: String? = null,
        val horizontalPadding: Int = 14,
        val barcodeQuietZonePadding: Int = 16,
        val qrQuietZonePadding: Int = 8,
        val isLargeFont: Boolean = (StoreInfoManager.thermalReceiptFontSize == "LARGE")
    )

    data class TransactionReceiptData(
        val receiptTitle: String = "Cash memo / receipt",
        val invoiceNo: String,
        val timestamp: Long = System.currentTimeMillis(),
        val customerName: String? = null,
        val customerPhone: String? = null,
        val staffName: String? = null,
        val items: List<ReceiptLineItem>,
        val subtotal: Double,
        val discount: Double = 0.0,
        val taxAmount: Double = 0.0,
        val grandTotal: Double,
        val paymentMode: String = "CASH",
        val paidAmount: Double = grandTotal,
        val dueAmount: Double = 0.0,
        val previousBalance: Double = 0.0,
        val upiId: String? = null,
        val upiPayeeName: String? = null,
        val notes: String? = null,
        val customQrUrl: String? = null,
        val qrHeaderLabel: String? = null,
        val paymentStatusBanner: String? = null,
        val totalSavings: Double = 0.0
    )

    data class ReceiptLineItem(
        val nameEn: String,
        val nameBn: String = "",
        val quantity: Double,
        val unitType: String = "pcs",
        val unitPrice: Double,
        val subtotal: Double,
        val mrp: Double = 0.0,
        val discountText: String? = null
    )

    // =========================================================================
    // 1. RECEIPT BITMAP BUILDER (58MM ADAPTIVE LAYOUT & FONT SCALING)
    // =========================================================================

    /**
     * Formats a SaleWithItems transaction into a high-contrast 58mm thermal receipt Bitmap.
     */
    fun formatSaleReceipt58mm(
        saleWithItems: SaleWithItems,
        config: ThermalReceiptConfig = ThermalReceiptConfig(isBengali = StoreInfoManager.isBillBengali())
    ): Bitmap {
        val sale = saleWithItems.sale
        val items = saleWithItems.items
        val totalMrpSavings = items.sumOf { it.getSavingsAmount() }
        val overallSavings = totalMrpSavings + sale.discount
        val transactionData = TransactionReceiptData(
            receiptTitle = if (config.isBengali) "ক্যাশ মেমো / রসিদ" else "Cash memo / receipt",
            invoiceNo = sale.id,
            timestamp = sale.datetime,
            customerName = sale.customerName,
            customerPhone = null,
            staffName = sale.staffName,
            items = items.map { item ->
                val hasItemDiscount = item.hasDiscount()
                val discText = if (hasItemDiscount) {
                    BengaliReceiptTranslator.formatDiscountItemLine(
                        mrp = item.getEffectiveMrp(),
                        unitPrice = item.unitPrice,
                        quantity = item.quantity,
                        isBn = config.isBengali
                    )
                } else null
                ReceiptLineItem(
                    nameEn = item.productNameEn,
                    nameBn = item.productNameBn,
                    quantity = item.quantity,
                    unitType = item.unitType,
                    unitPrice = item.unitPrice,
                    subtotal = item.subtotal,
                    mrp = item.getEffectiveMrp(),
                    discountText = discText
                )
            },
            subtotal = sale.totalAmount,
            discount = sale.discount,
            taxAmount = 0.0,
            grandTotal = sale.finalAmount,
            paymentMode = sale.paymentMode,
            paidAmount = sale.receivedAmount,
            dueAmount = sale.dueAmount,
            previousBalance = sale.previousBalance,
            upiId = if (StoreInfoManager.showQrOnPdf) StoreInfoManager.merchantUpiId else null,
            upiPayeeName = StoreInfoManager.merchantPayeeName.ifBlank { StoreInfoManager.getStoreDisplayName(config.isBengali) },
            notes = sale.notes,
            totalSavings = overallSavings
        )

        return formatTransactionReceipt58mm(transactionData, config)
    }

    /**
     * Formats a complete sale into a list of HybridReceiptLines for hybrid native-text + bitmap printing.
     */
    fun formatSaleReceiptHybridLines(
        saleWithItems: SaleWithItems,
        config: ThermalReceiptConfig = ThermalReceiptConfig()
    ): List<EscPosPrinter.HybridReceiptLine> {
        val sale = saleWithItems.sale
        val items = saleWithItems.items
        val totalMrpSavings = items.sumOf { it.getSavingsAmount() }
        val overallSavings = totalMrpSavings + sale.discount
        val transactionData = TransactionReceiptData(
            receiptTitle = if (config.isBengali) "ক্যাশ মেমো / রসিদ" else "Cash memo / receipt",
            invoiceNo = sale.id,
            timestamp = sale.datetime,
            customerName = sale.customerName,
            customerPhone = null,
            staffName = sale.staffName,
            items = items.map { item ->
                val hasItemDiscount = item.hasDiscount()
                val discText = if (hasItemDiscount) {
                    BengaliReceiptTranslator.formatDiscountItemLine(
                        mrp = item.getEffectiveMrp(),
                        unitPrice = item.unitPrice,
                        quantity = item.quantity,
                        isBn = config.isBengali
                    )
                } else null
                ReceiptLineItem(
                    nameEn = item.productNameEn,
                    nameBn = item.productNameBn,
                    quantity = item.quantity,
                    unitType = item.unitType,
                    unitPrice = item.unitPrice,
                    subtotal = item.subtotal,
                    mrp = item.getEffectiveMrp(),
                    discountText = discText
                )
            },
            subtotal = sale.totalAmount,
            discount = sale.discount,
            taxAmount = 0.0,
            grandTotal = sale.finalAmount,
            paymentMode = sale.paymentMode,
            paidAmount = sale.receivedAmount,
            dueAmount = sale.dueAmount,
            previousBalance = sale.previousBalance,
            upiId = if (StoreInfoManager.showQrOnPdf) StoreInfoManager.merchantUpiId else null,
            upiPayeeName = StoreInfoManager.merchantPayeeName.ifBlank { StoreInfoManager.getStoreDisplayName(config.isBengali) },
            notes = sale.notes,
            totalSavings = overallSavings
        )

        return formatTransactionReceiptHybridLines(transactionData, config)
    }

    /**
     * Formats generic transaction receipt data into a list of HybridReceiptLines
     * for hybrid native-text + bitmap printing.
     */
    fun formatTransactionReceiptHybridLines(
        data: TransactionReceiptData,
        config: ThermalReceiptConfig = ThermalReceiptConfig()
    ): List<EscPosPrinter.HybridReceiptLine> {
        val lines = mutableListOf<EscPosPrinter.HybridReceiptLine>()

        // 1. Store Header
        val rawStoreName = StoreInfoManager.getStoreDisplayName(config.isBengali).ifBlank { "DUKAAN POS" }
        val storeAddress = StoreInfoManager.getStoreDisplayAddress(config.isBengali)
        val storePhone = StoreInfoManager.phone
        val gstin = StoreInfoManager.gstin

        lines.add(EscPosPrinter.HybridReceiptLine.TextLine(rawStoreName, alignment = 1, isBold = true, isTitle = true))

        if (storeAddress.isNotBlank() && config.showStoreHeader) {
            lines.add(EscPosPrinter.HybridReceiptLine.TextLine(storeAddress, alignment = 1, isSubtitle = true))
        }
        if (storePhone.isNotBlank() && config.showStoreHeader) {
            val pText = if (config.isBengali) "ফোন: ${formatBengaliDigits(storePhone)}" else "Phone: $storePhone"
            lines.add(EscPosPrinter.HybridReceiptLine.TextLine(pText, alignment = 1, isSubtitle = true))
        }
        if (gstin.isNotBlank() && config.showStoreHeader) {
            lines.add(EscPosPrinter.HybridReceiptLine.TextLine("GSTIN: $gstin", alignment = 1, isSubtitle = true))
        }
        lines.add(EscPosPrinter.HybridReceiptLine.Divider(isDashed = true))

        // 2. Metadata
        val sdf = SimpleDateFormat("dd/MM/yyyy hh:mm a", Locale.getDefault())
        val dateRaw = sdf.format(Date(data.timestamp))
        val dateStr = if (config.isBengali) formatBengaliDigits(dateRaw) else dateRaw
        val invoiceRaw = data.invoiceNo.takeLast(9).ifEmpty { data.invoiceNo }
        val invoiceDisplay = if (config.isBengali) formatBengaliDigits(invoiceRaw) else invoiceRaw

        val titleText = if (config.isBengali) "ক্যাশ মেমো / রসিদ" else "Cash memo / receipt"
        lines.add(EscPosPrinter.HybridReceiptLine.TextLine(titleText, alignment = 1, isBold = true))
        lines.add(EscPosPrinter.HybridReceiptLine.Divider(isDashed = true))

        val billLabel = if (config.isBengali) "বিল নং: #$invoiceDisplay" else "Bill no: #$invoiceDisplay"
        lines.add(EscPosPrinter.HybridReceiptLine.TextLine(billLabel, alignment = 0))
        val dateLabel = if (config.isBengali) "তারিখ: $dateStr" else "Date: $dateStr"
        lines.add(EscPosPrinter.HybridReceiptLine.TextLine(dateLabel, alignment = 0))

        if (!data.customerName.isNullOrBlank() && config.showCustomerInfo) {
            val translatedCust = BengaliReceiptTranslator.translateCustomerName(data.customerName, config.isBengali)
            val cText = if (config.isBengali) "গ্রাহক: $translatedCust" else "Customer: $translatedCust"
            lines.add(EscPosPrinter.HybridReceiptLine.TextLine(cText, alignment = 0))
        }
        if (!data.staffName.isNullOrBlank() && config.showStaffInfo) {
            val translatedStaff = BengaliReceiptTranslator.translateStaffName(data.staffName, config.isBengali)
            val sText = if (config.isBengali) "স্টাফ: $translatedStaff" else "Staff: $translatedStaff"
            lines.add(EscPosPrinter.HybridReceiptLine.TextLine(sText, alignment = 0))
        }
        lines.add(EscPosPrinter.HybridReceiptLine.Divider(isDashed = true))

        // 3. Table Column Header
        val col1 = if (config.isBengali) "পণ্য" else "Item"
        val col2 = if (config.isBengali) "পরিমাণ" else "Qty"
        val col3 = if (config.isBengali) "মোট(₹)" else "Total (Rs)"
        lines.add(EscPosPrinter.HybridReceiptLine.ThreeColumnLine(col1, col2, col3, isBold = true))
        lines.add(EscPosPrinter.HybridReceiptLine.Divider(isDashed = true))

        // 4. Line Items - Keep grouped rows (name + qty + price) together as one aligned row
        for (item in data.items) {
            val rawName = if (config.isBengali) {
                if (item.nameBn.isNotBlank() && BengaliReceiptTranslator.containsBengali(item.nameBn)) {
                    item.nameBn
                } else {
                    BengaliReceiptTranslator.translateItem(item.nameEn.ifBlank { item.nameBn })
                }
            } else {
                item.nameEn.ifBlank { item.nameBn }.ifBlank { "Item" }
            }
            val itemName = BengaliReceiptTranslator.cleanReceiptProductName(rawName)
            val qtyStr = formatItemQuantity(item.quantity, item.unitType, config.isBengali)
            val priceNum = "%.2f".format(Locale.US, item.subtotal)
            val priceStr = if (config.isBengali) formatBengaliDigits(priceNum) else priceNum

            lines.add(EscPosPrinter.HybridReceiptLine.ThreeColumnLine(itemName, qtyStr, priceStr))

            if (!item.discountText.isNullOrBlank()) {
                val discLineText = if (config.isBengali) {
                    formatBengaliDigits(item.discountText)
                } else {
                    item.discountText.replace("₹", "Rs ")
                }
                lines.add(EscPosPrinter.HybridReceiptLine.TextLine(discLineText, alignment = 0, isSubtitle = true))
            }
        }
        lines.add(EscPosPrinter.HybridReceiptLine.Divider(isDashed = true))

        // 5. Totals
        val subtotalNum = "%.2f".format(Locale.US, data.subtotal)
        val grandTotalNum = "%.2f".format(Locale.US, data.grandTotal)
        val paidNum = "%.2f".format(Locale.US, data.paidAmount)
        val dueNum = "%.2f".format(Locale.US, data.dueAmount)

        if (data.discount > 0) {
            val discPct = if (data.subtotal > 0) (data.discount / data.subtotal) * 100.0 else 0.0
            val discPctStr = if (discPct % 1.0 == 0.0) "${discPct.toInt()}%" else "%.1f%%".format(Locale.US, discPct)
            val discPctDisplay = if (config.isBengali) formatBengaliDigits(discPctStr) else discPctStr
            val discNum = "%.2f".format(Locale.US, data.discount)
            val subLabel = if (config.isBengali) "উপমোট:" else "Subtotal:"
            val subVal = if (config.isBengali) "₹${formatBengaliDigits(subtotalNum)}" else "Rs $subtotalNum"
            val discLabel = if (config.isBengali) "ছাড় ($discPctDisplay):" else "Discount ($discPctDisplay):"
            val discVal = if (config.isBengali) "-₹${formatBengaliDigits(discNum)}" else "-Rs $discNum"
            lines.add(EscPosPrinter.HybridReceiptLine.TwoColumnLine(subLabel, subVal))
            lines.add(EscPosPrinter.HybridReceiptLine.TwoColumnLine(discLabel, discVal, isBold = true))
        }

        val totalLabel = if (config.isBengali) "মোট কেনাকাটা:" else "Total purchase:"
        val totalVal = if (config.isBengali) "₹${formatBengaliDigits(grandTotalNum)}" else "Rs $grandTotalNum"
        lines.add(EscPosPrinter.HybridReceiptLine.TwoColumnLine(totalLabel, totalVal, isBold = true))

        if (data.totalSavings > 0) {
            val origTotal = data.grandTotal + data.totalSavings
            val savPct = if (origTotal > 0) (data.totalSavings / origTotal) * 100.0 else 0.0
            val savPctStr = if (savPct % 1.0 == 0.0) "${savPct.toInt()}%" else "%.0f%%".format(Locale.US, savPct)
            val savPctDisplay = if (config.isBengali) formatBengaliDigits(savPctStr) else savPctStr
            val savNum = "%.2f".format(Locale.US, data.totalSavings)
            val savLabel = if (config.isBengali) "মোট সাশ্রয় ($savPctDisplay):" else "Total savings ($savPctDisplay):"
            val savVal = if (config.isBengali) "₹${formatBengaliDigits(savNum)}" else "Rs $savNum"
            lines.add(EscPosPrinter.HybridReceiptLine.TwoColumnLine(savLabel, savVal, isBold = true))
        }

        val paidLabel = if (config.isBengali) "নগদ/অনলাইন জমা:" else "Cash/online paid:"
        val paidVal = if (config.isBengali) "₹${formatBengaliDigits(paidNum)}" else "Rs $paidNum"
        lines.add(EscPosPrinter.HybridReceiptLine.TwoColumnLine(paidLabel, paidVal))

        val payModeDisplay = data.paymentMode.trim()
        if (payModeDisplay.isNotBlank()) {
            val pmLabel = if (config.isBengali) "পেমেন্ট মাধ্যম:" else "Payment method:"
            val pmVal = BengaliReceiptTranslator.translatePaymentMode(payModeDisplay, config.isBengali)
            lines.add(EscPosPrinter.HybridReceiptLine.TwoColumnLine(pmLabel, pmVal))
        }

        if (data.dueAmount > 0) {
            val dueLabel = if (config.isBengali) "আজকের বাকি:" else "Today's due:"
            val dueVal = if (config.isBengali) "₹${formatBengaliDigits(dueNum)}" else "Rs $dueNum"
            lines.add(EscPosPrinter.HybridReceiptLine.TwoColumnLine(dueLabel, dueVal))
        }

        val excessPaid = (data.paidAmount - data.grandTotal).coerceAtLeast(0.0)
        val netRemainingDue = (data.previousBalance + data.dueAmount - excessPaid).coerceAtLeast(0.0)
        val advanceCredit = (excessPaid - (data.previousBalance + data.dueAmount)).coerceAtLeast(0.0)

        if (excessPaid > 0 && data.previousBalance > 0) {
            val prevDueLabel = if (config.isBengali) "পূর্বের বকেয়া:" else "Previous due:"
            val prevDueVal = if (config.isBengali) "₹${formatBengaliDigits("%.2f".format(Locale.US, data.previousBalance))}" else "Rs %.2f".format(Locale.US, data.previousBalance)
            lines.add(EscPosPrinter.HybridReceiptLine.TwoColumnLine(prevDueLabel, prevDueVal))

            val clearedDue = Math.min(excessPaid, data.previousBalance)
            val clearedLabel = if (config.isBengali) "বকেয়া শোধ হয়েছে:" else "Prev due cleared:"
            val clearedVal = if (config.isBengali) "-₹${formatBengaliDigits("%.2f".format(Locale.US, clearedDue))}" else "-Rs %.2f".format(Locale.US, clearedDue)
            lines.add(EscPosPrinter.HybridReceiptLine.TwoColumnLine(clearedLabel, clearedVal))

            lines.add(EscPosPrinter.HybridReceiptLine.Divider(isDashed = true))
            val curDueLabel = if (config.isBengali) "বর্তমান মোট বাকি:" else "Current total due:"
            val curDueVal = if (config.isBengali) "₹${formatBengaliDigits("%.2f".format(Locale.US, netRemainingDue))}" else "Rs %.2f".format(Locale.US, netRemainingDue)
            lines.add(EscPosPrinter.HybridReceiptLine.TwoColumnLine(curDueLabel, curDueVal, isBold = true))

            if (advanceCredit > 0) {
                val advLabel = if (config.isBengali) "অতিরিক্ত জমা (অ্যাডভান্স):" else "Advance credit:"
                val advVal = if (config.isBengali) "₹${formatBengaliDigits("%.2f".format(Locale.US, advanceCredit))}" else "Rs %.2f".format(Locale.US, advanceCredit)
                lines.add(EscPosPrinter.HybridReceiptLine.TwoColumnLine(advLabel, advVal))
            }
        } else if (excessPaid > 0) {
            val changeLabel = if (config.isBengali) "ফেরত দেওয়া হয়েছে:" else "Change returned:"
            val changeVal = if (config.isBengali) "₹${formatBengaliDigits("%.2f".format(Locale.US, excessPaid))}" else "Rs %.2f".format(Locale.US, excessPaid)
            lines.add(EscPosPrinter.HybridReceiptLine.TwoColumnLine(changeLabel, changeVal))
        } else if (data.previousBalance > 0 || data.dueAmount > 0) {
            if (data.previousBalance > 0) {
                val prevLabel = if (config.isBengali) "পূর্বের বকেয়া:" else "Previous due:"
                val prevVal = if (config.isBengali) "₹${formatBengaliDigits("%.2f".format(Locale.US, data.previousBalance))}" else "Rs %.2f".format(Locale.US, data.previousBalance)
                lines.add(EscPosPrinter.HybridReceiptLine.TwoColumnLine(prevLabel, prevVal))
            }
            lines.add(EscPosPrinter.HybridReceiptLine.Divider(isDashed = true))
            val totDueLabel = if (config.isBengali) "বর্তমান মোট বাকি:" else "Current total due:"
            val totDueVal = if (config.isBengali) "₹${formatBengaliDigits("%.2f".format(Locale.US, netRemainingDue))}" else "Rs %.2f".format(Locale.US, netRemainingDue)
            lines.add(EscPosPrinter.HybridReceiptLine.TwoColumnLine(totDueLabel, totDueVal, isBold = true))
        }

        // 6. Due Date and Khata Disclaimer
        val totalDue = netRemainingDue
        val interestSettings = StoreInfoManager.getKhataInterestSettings()
        val hasCreditOrDue = totalDue > 0.0 || data.dueAmount > 0.0 || data.previousBalance > 0 || data.paymentMode.contains("CREDIT", ignoreCase = true)
        val dueDateMs = KhataInterestCalculator.calculateDueDateTimestamp(data.timestamp, null, interestSettings.gracePeriodDays)
        val dueDateStr = KhataInterestCalculator.formatDueDate(dueDateMs, interestSettings.gracePeriodDays, config.isBengali)

        if (hasCreditOrDue) {
            val duePrefix = if (config.isBengali) "পরিশোধের শেষ তারিখ: " else "Payment due date: "
            lines.add(EscPosPrinter.HybridReceiptLine.TextLine("$duePrefix$dueDateStr", alignment = 1))
        }
        lines.add(EscPosPrinter.HybridReceiptLine.Divider(isDashed = true))

        // 7. Payment Status Banner
        if (!data.paymentStatusBanner.isNullOrBlank()) {
            lines.add(EscPosPrinter.HybridReceiptLine.TextLine(data.paymentStatusBanner, alignment = 1, isBold = true))
            lines.add(EscPosPrinter.HybridReceiptLine.Divider(isDashed = true))
        }

        // 8. Payment QR Code + Scan to Pay Due line
        val hasCustomQr = !data.customQrUrl.isNullOrBlank()
        val shouldShowQr = config.showPaymentQr &&
                (hasCustomQr || (!data.upiId.isNullOrBlank() &&
                (totalDue > 0.0 || data.paymentMode.contains("UPI", ignoreCase = true) || data.paidAmount < data.grandTotal)))

        if (shouldShowQr) {
            val qrAmount = if (totalDue > 0.0) totalDue else data.grandTotal
            val upiIdDisplay = data.upiId ?: StoreInfoManager.DEFAULT_UPI_VPA
            val upiPayload = if (hasCustomQr) {
                data.customQrUrl!!
            } else {
                val noteText = if (totalDue > 0.0) "Due Payment #${invoiceRaw}" else "Bill #${invoiceRaw}"
                StoreInfoManager.buildUpiPayUrl(
                    upiId = data.upiId!!,
                    payeeName = data.upiPayeeName ?: rawStoreName,
                    amount = qrAmount,
                    note = noteText
                )
            }
            val qrSize = if (StoreInfoManager.pdfPaperSize == "THERMAL_80MM") 240 else 200
            val qrBmp = generateReceiptQrCode(upiPayload, qrSize, config.qrQuietZonePadding)
            if (qrBmp != null) {
                lines.add(EscPosPrinter.HybridReceiptLine.ImageBlock(qrBmp, isQrCode = true))
                val dueAmtStr = if (config.isBengali) "₹${formatBengaliDigits("%.2f".format(Locale.US, qrAmount))}" else "Rs %.2f".format(Locale.US, qrAmount)
                val scanLine = if (!data.qrHeaderLabel.isNullOrBlank()) {
                    data.qrHeaderLabel!!
                } else if (totalDue > 0.0) {
                    if (config.isBengali) "বকেয়া পরিশোধের জন্য স্ক্যান করুন ($dueAmtStr)" else "Scan to pay due ($dueAmtStr)"
                } else {
                    if (config.isBengali) "ইউপিআই পেমেন্টের জন্য স্ক্যান করুন ($dueAmtStr)" else "Scan to pay via UPI ($dueAmtStr)"
                }
                lines.add(EscPosPrinter.HybridReceiptLine.TextLine(scanLine, alignment = 1))
                lines.add(EscPosPrinter.HybridReceiptLine.TextLine("UPI: $upiIdDisplay", alignment = 1))
                lines.add(EscPosPrinter.HybridReceiptLine.Divider(isDashed = true))
            }
        }

        // 9. Late Interest Disclaimer
        val disclaimerText = if (hasCreditOrDue) {
            KhataInterestCalculator.formatDisclaimer(
                settings = interestSettings,
                customer = null,
                isBengali = config.isBengali,
                dueDateMs = dueDateMs,
                dueAmount = totalDue
            )
        } else ""
        if (disclaimerText.isNotBlank()) {
            val cleanedDisclaimer = disclaimerText.replace("⚠️", "").trim()
            lines.add(EscPosPrinter.HybridReceiptLine.TextLine(cleanedDisclaimer, alignment = 1, isSubtitle = true))
            lines.add(EscPosPrinter.HybridReceiptLine.Divider(isDashed = true))
        }

        // 10. Barcode
        val rawBarcodeCode = data.invoiceNo.trim()
        val cleanBarcodeCode = if (rawBarcodeCode.contains("-") && rawBarcodeCode.length >= 20) {
            rawBarcodeCode.takeLast(8).replace("-", "").uppercase()
        } else {
            rawBarcodeCode
        }
        if (config.showBarcode && cleanBarcodeCode.isNotBlank()) {
            val barcodeBmp = generateReceiptBarcode(
                code = cleanBarcodeCode,
                width = 384,
                height = 54,
                horizontalPadding = config.barcodeQuietZonePadding
            )
            if (barcodeBmp != null) {
                lines.add(EscPosPrinter.HybridReceiptLine.ImageBlock(barcodeBmp, isBarcode = true))
                val barcodeLabel = if (rawBarcodeCode.contains("-") && rawBarcodeCode.length >= 20) "Bill #$cleanBarcodeCode" else cleanBarcodeCode
                lines.add(EscPosPrinter.HybridReceiptLine.TextLine(barcodeLabel, alignment = 1, isBold = true))
                lines.add(EscPosPrinter.HybridReceiptLine.Divider(isDashed = true))
            }
        }

        // 11. Thank-you footer
        val footLine1 = if (config.isBengali) "আমাদের সাথে কেনাকাটা করার জন্য ধন্যবাদ!" else "Thank you for shopping with us!"
        val footLine2 = if (config.isBengali) "আবার আসবেন" else "Please visit again"
        lines.add(EscPosPrinter.HybridReceiptLine.TextLine(footLine1, alignment = 1))
        lines.add(EscPosPrinter.HybridReceiptLine.TextLine(footLine2, alignment = 1))

        return lines
    }

    /**
     * Formats generic transaction receipt data into a 58mm thermal bitmap (384 dots width).
     */
    fun formatTransactionReceipt58mm(
        data: TransactionReceiptData,
        config: ThermalReceiptConfig = ThermalReceiptConfig()
    ): Bitmap {
        val widthDots = THERMAL_58MM_WIDTH_DOTS
        val paddingX = config.horizontalPadding.coerceIn(8, 24)
        val contentWidth = widthDots - (paddingX * 2)

        // --- TYPOGRAPHY PAINTS (Precision Scaled & Bolded for 58mm 203 DPI) ---
        val fontScale = if (config.isLargeFont) 1.18f else 1.0f

        val storeTitlePaint = TextPaint().apply {
            color = Color.BLACK
            textSize = (if (config.isBengali) 23f else 21f) * fontScale // Clear display title
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val subtitlePaint = TextPaint().apply {
            color = Color.BLACK
            textSize = (if (config.isBengali) 14.5f else 13.5f) * fontScale // Compact header details
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val headerDocPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = (if (config.isBengali) 16.5f else 15.5f) * fontScale // Document Type (e.g. CASH MEMO)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val metaLabelPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = (if (config.isBengali) 14.5f else 13.5f) * fontScale // Invoice, Date, Customer info
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val metaBoldPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = (if (config.isBengali) 15.5f else 14f) * fontScale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val tableHeaderPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = (if (config.isBengali) 15.5f else 14f) * fontScale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val itemTitlePaint = TextPaint().apply {
            color = Color.BLACK
            textSize = (if (config.isBengali) 15.5f else 14f) * fontScale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val itemDetailPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = (if (config.isBengali) 14.5f else 13.5f) * fontScale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val grandLabelPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = (if (config.isBengali) 19.5f else 18f) * fontScale // Prominent Total
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val grandValuePaint = TextPaint().apply {
            color = Color.BLACK
            textSize = (if (config.isBengali) 19.5f else 18f) * fontScale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
            isAntiAlias = true
        }

        val linePaint = Paint().apply {
            color = Color.BLACK
            strokeWidth = 2.0f
            style = Paint.Style.STROKE
            isAntiAlias = false
        }

        val dashedLinePaint = Paint().apply {
            color = Color.BLACK
            strokeWidth = 2.0f
            style = Paint.Style.STROKE
            isAntiAlias = false
            pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
        }

        // --- PREPARE CONTENT & PRE-MEASURE HEIGHT ---
        val rawStoreName = StoreInfoManager.getStoreDisplayName(config.isBengali).ifBlank { "DUKAAN POS" }
        val storeAddress = StoreInfoManager.getStoreDisplayAddress(config.isBengali)
        val storePhone = StoreInfoManager.phone
        val gstin = StoreInfoManager.gstin

        val sdf = SimpleDateFormat("dd/MM/yyyy hh:mm a", Locale.getDefault())
        val dateRaw = sdf.format(Date(data.timestamp))
        val dateStr = if (config.isBengali) formatBengaliDigits(dateRaw) else dateRaw

        val invoiceRaw = data.invoiceNo.takeLast(8)
        val invoiceDisplay = if (config.isBengali) formatBengaliDigits(invoiceRaw) else invoiceRaw
        val payModeDisplay = data.paymentMode.trim()

        var totalHeight = 4f // Minimal top padding

        // 1. Store Header Section
        val storeNameLayout = createStaticLayout(rawStoreName, storeTitlePaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
        totalHeight += storeNameLayout.height + 4f

        val addrLayout = if (storeAddress.isNotBlank() && config.showStoreHeader) {
            createStaticLayout(storeAddress, subtitlePaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
        } else null
        if (addrLayout != null) totalHeight += addrLayout.height + 3f

        val phoneLayout = if (storePhone.isNotBlank() && config.showStoreHeader) {
            val pText = if (config.isBengali) "ফোন: ${formatBengaliDigits(storePhone)}" else "Phone: $storePhone"
            createStaticLayout(pText, subtitlePaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
        } else null
        if (phoneLayout != null) totalHeight += phoneLayout.height + 3f

        val gstinLayout = if (gstin.isNotBlank() && config.showStoreHeader) {
            createStaticLayout("GSTIN: $gstin", subtitlePaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
        } else null
        if (gstinLayout != null) totalHeight += gstinLayout.height + 3f

        totalHeight += 10f // Divider

        // 2. Metadata Section (Doc title, Bill No, Date, Customer, Staff)
        val docTitleLayout = createStaticLayout(data.receiptTitle, headerDocPaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
        totalHeight += docTitleLayout.height + 6f

        val billLabel = if (config.isBengali) "বিল নং: #$invoiceDisplay" else "Bill no: #$invoiceDisplay"
        val billLayout = createStaticLayout(billLabel, metaBoldPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
        totalHeight += billLayout.height + 3f

        val dateLabel = if (config.isBengali) "তারিখ: $dateStr" else "Date: $dateStr"
        val dateLayout = createStaticLayout(dateLabel, metaLabelPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
        totalHeight += dateLayout.height + 3f

        val custLayout = if (!data.customerName.isNullOrBlank() && config.showCustomerInfo) {
            val translatedCust = BengaliReceiptTranslator.translateCustomerName(data.customerName, config.isBengali)
            val cText = if (config.isBengali) "গ্রাহক: $translatedCust" else "Customer: $translatedCust"
            createStaticLayout(cText, metaBoldPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
        } else null
        if (custLayout != null) totalHeight += custLayout.height + 3f

        val staffLayout = if (!data.staffName.isNullOrBlank() && config.showStaffInfo) {
            val translatedStaff = BengaliReceiptTranslator.translateStaffName(data.staffName, config.isBengali)
            val sText = if (config.isBengali) "স্টাফ: $translatedStaff" else "Staff: $translatedStaff"
            createStaticLayout(sText, metaLabelPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
        } else null
        if (staffLayout != null) totalHeight += staffLayout.height + 3f

        totalHeight += 12f // Divider before table

        // 3. Items Table Pre-calculation
        // 58mm Column Widths: Item Name (50% = ~178 dots), Qty (24% = ~85 dots), Amount (26% = ~93 dots)
        val col1Width = (contentWidth * 0.50f).toInt()
        val col2Width = (contentWidth * 0.24f).toInt()
        val col3Width = contentWidth - col1Width - col2Width

        totalHeight += 24f // Table column header height + padding

        val itemLayouts = data.items.map { item ->
            val rawName = if (config.isBengali) {
                if (item.nameBn.isNotBlank() && BengaliReceiptTranslator.containsBengali(item.nameBn)) {
                    item.nameBn
                } else {
                    BengaliReceiptTranslator.translateItem(item.nameEn.ifBlank { item.nameBn })
                }
            } else {
                item.nameEn.ifBlank { item.nameBn }.ifBlank { "Item" }
            }
            val itemName = BengaliReceiptTranslator.cleanReceiptProductName(rawName)
            val nameLayout = createStaticLayout(itemName, itemTitlePaint, col1Width, Layout.Alignment.ALIGN_NORMAL)
            var rowHeight = Math.max(nameLayout.height + 2f, 20f)
            if (!item.discountText.isNullOrBlank()) {
                rowHeight += 16f
            }
            totalHeight += rowHeight + 4f
            Pair(item, nameLayout)
        }

        totalHeight += 10f // Table bottom divider

        // 4. Totals & Payment Calculations
        if (data.discount > 0) {
            totalHeight += 38f // Subtotal & Discount rows (19f + 19f)
        }
        totalHeight += 24f // Grand Total Row
        if (data.totalSavings > 0) {
            totalHeight += 19f // Total Savings row
        }

        val excessPaid = (data.paidAmount - data.grandTotal).coerceAtLeast(0.0)
        val netRemainingDue = (data.previousBalance + data.dueAmount - excessPaid).coerceAtLeast(0.0)
        val advanceCredit = (excessPaid - (data.previousBalance + data.dueAmount)).coerceAtLeast(0.0)

        totalHeight += 19f // Paid row
        if (payModeDisplay.isNotBlank()) {
            totalHeight += 19f // Payment Mode row
        }
        if (data.dueAmount > 0) totalHeight += 19f
        if (excessPaid > 0 && data.previousBalance > 0) {
            totalHeight += 19f + 19f + 32f // Prev due cleared + prev due + total due now box
            if (advanceCredit > 0) totalHeight += 19f
        } else if (excessPaid > 0) {
            totalHeight += 19f // Change returned
        } else if (data.previousBalance > 0 || data.dueAmount > 0) {
            if (data.previousBalance > 0) totalHeight += 19f
            totalHeight += 32f // Prominent Total Due
        }

        // 4.5 Due Date and Khata Interest Disclaimer Pre-calculation
        val totalDue = netRemainingDue
        val interestSettings = StoreInfoManager.getKhataInterestSettings()
        val hasCreditOrDue = totalDue > 0.0 || data.dueAmount > 0.0 || data.previousBalance > 0 || data.paymentMode.contains("CREDIT", ignoreCase = true)
        val dueDateMs = KhataInterestCalculator.calculateDueDateTimestamp(data.timestamp, null, interestSettings.gracePeriodDays)
        val dueDateStr = KhataInterestCalculator.formatDueDate(dueDateMs, interestSettings.gracePeriodDays, config.isBengali)

        val dueDateLayout = if (hasCreditOrDue) {
            val dueDatePaint = TextPaint(metaBoldPaint).apply {
                textSize = 13.5f * fontScale
            }
            val duePrefix = if (config.isBengali) "পরিশোধের শেষ তারিখ: " else "Payment due date: "
            createStaticLayout("$duePrefix$dueDateStr", dueDatePaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
        } else null
        if (dueDateLayout != null) {
            totalHeight += dueDateLayout.height + 4f
        }

        totalHeight += 12f // Divider after totals / due date (4f + line + 8f)

        // 5. Payment Status Banner (e.g. "Payment Received")
        var statusBannerLayout: StaticLayout? = null
        if (!data.paymentStatusBanner.isNullOrBlank()) {
            val statusPaint = TextPaint(metaBoldPaint).apply {
                textSize = 14f * fontScale
            }
            statusBannerLayout = createStaticLayout(data.paymentStatusBanner, statusPaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
            totalHeight += statusBannerLayout.height + 8f + 12f // banner + space + divider (4f + line + 8f)
        }

        // 5.1 Payment QR Code with dedicated quiet-zone padding
        var qrBitmap: Bitmap? = null
        var qrCaptionLayout: StaticLayout? = null
        val hasCustomQr = !data.customQrUrl.isNullOrBlank()
        val shouldShowQr = config.showPaymentQr &&
                (hasCustomQr || (!data.upiId.isNullOrBlank() &&
                (totalDue > 0.0 || data.paymentMode.contains("UPI", ignoreCase = true) || data.paidAmount < data.grandTotal)))

        if (shouldShowQr) {
            val qrAmount = if (totalDue > 0.0) totalDue else data.grandTotal
            val upiIdDisplay = data.upiId ?: StoreInfoManager.DEFAULT_UPI_VPA
            val upiPayload = if (hasCustomQr) {
                data.customQrUrl!!
            } else {
                val noteText = if (totalDue > 0.0) "Due Payment #${invoiceRaw}" else "Bill #${invoiceRaw}"
                StoreInfoManager.buildUpiPayUrl(
                    upiId = data.upiId!!,
                    payeeName = data.upiPayeeName ?: rawStoreName,
                    amount = qrAmount,
                    note = noteText
                )
            }
            val qrSize = if (StoreInfoManager.pdfPaperSize == "THERMAL_80MM") 240 else 200
            qrBitmap = generateReceiptQrCode(upiPayload, qrSize, config.qrQuietZonePadding)
            if (qrBitmap != null) {
                val dueAmtStr = if (config.isBengali) "₹${formatBengaliDigits("%.2f".format(Locale.US, qrAmount))}" else "Rs %.2f".format(Locale.US, qrAmount)
                val qrCaption = if (!data.qrHeaderLabel.isNullOrBlank()) {
                    val upiFooter = if (!data.upiId.isNullOrBlank()) "\nUPI: ${data.upiId}" else ""
                    "${data.qrHeaderLabel}$upiFooter"
                } else if (totalDue > 0.0) {
                    if (config.isBengali) "বকেয়া পরিশোধের জন্য স্ক্যান করুন ($dueAmtStr)\nUPI: $upiIdDisplay"
                    else "Scan to pay due ($dueAmtStr)\nUPI: $upiIdDisplay"
                } else {
                    if (config.isBengali) "ইউপিআই পেমেন্টের জন্য স্ক্যান করুন ($dueAmtStr)\nUPI: $upiIdDisplay"
                    else "Scan to pay via UPI ($dueAmtStr)\nUPI: $upiIdDisplay"
                }
                qrCaptionLayout = createStaticLayout(qrCaption, subtitlePaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
                totalHeight += qrBitmap.height + 4f + qrCaptionLayout.height + 8f + 12f // QR + space + caption + space + divider (4f + line + 8f)
            }
        }

        // 5.5 Khata Interest Policy & Due Day Disclaimer
        val disclaimerText = if (hasCreditOrDue) {
            KhataInterestCalculator.formatDisclaimer(
                settings = interestSettings,
                customer = null,
                isBengali = config.isBengali,
                dueDateMs = dueDateMs,
                dueAmount = totalDue
            )
        } else ""

        val disclaimerLayout = if (disclaimerText.isNotBlank()) {
            val discPaint = TextPaint(metaBoldPaint).apply {
                textSize = 12.5f * fontScale
            }
            val cleanedDisclaimer = disclaimerText.replace("⚠️", "").trim()
            createStaticLayout(cleanedDisclaimer, discPaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
        } else null
        if (disclaimerLayout != null) {
            totalHeight += disclaimerLayout.height + 6f + 12f // Disclaimer + space + divider (4f + line + 8f)
        }

        // 6. 1D Barcode with dedicated quiet-zone padding for transaction scanning
        var barcodeBitmap: Bitmap? = null
        val rawBarcodeCode = data.invoiceNo.trim()
        val cleanBarcodeCode = if (rawBarcodeCode.contains("-") && rawBarcodeCode.length >= 20) {
            rawBarcodeCode.takeLast(8).replace("-", "").uppercase()
        } else {
            rawBarcodeCode
        }
        val digitPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = 12.5f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        if (config.showBarcode && cleanBarcodeCode.isNotBlank()) {
            val barcodeWidth = (contentWidth * 0.90f).toInt().coerceIn(240, 360)
            val barcodeHeight = 48
            barcodeBitmap = generateReceiptBarcode(
                code = cleanBarcodeCode,
                width = barcodeWidth,
                height = barcodeHeight,
                horizontalPadding = config.barcodeQuietZonePadding
            )
            if (barcodeBitmap != null) {
                totalHeight += barcodeBitmap.height + 3f + 18f + 12f // Barcode + space + human text + space + divider (4f + line + 8f)
            }
        }

        // 7. Footer Note & Greetings
        val customFooter = BengaliReceiptTranslator.getFooterGreeting(config.isBengali, config.customFooterText ?: StoreInfoManager.customFooterNote)
        val footerLayout = if (config.showFooterNote && customFooter.isNotBlank()) {
            createStaticLayout(customFooter, subtitlePaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
        } else null
        if (footerLayout != null) totalHeight += footerLayout.height + 6f

        // Greeting / Watermark
        val greetingText = if (config.isBengali) "আমাদের সাথে কেনাকাটা করার জন্য ধন্যবাদ!\nআবার আসবেন" else "Thank you for shopping with us!\nPlease visit again"
        val greetingLayout = createStaticLayout(greetingText, metaBoldPaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
        totalHeight += greetingLayout.height + 4f

        val finalBitmapHeight = (totalHeight + 4f).toInt()

        // --- SECOND PASS: RENDER BITMAP ON CANVAS ---
        val finalBitmap = Bitmap.createBitmap(widthDots, finalBitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(finalBitmap)
        canvas.drawColor(Color.WHITE)

        var curY = 4f

        // 1. Draw Store Header
        canvas.save()
        canvas.translate(paddingX.toFloat(), curY)
        storeNameLayout.draw(canvas)
        canvas.restore()
        curY += storeNameLayout.height + 4f

        if (addrLayout != null) {
            canvas.save()
            canvas.translate(paddingX.toFloat(), curY)
            addrLayout.draw(canvas)
            canvas.restore()
            curY += addrLayout.height + 3f
        }

        if (phoneLayout != null) {
            canvas.save()
            canvas.translate(paddingX.toFloat(), curY)
            phoneLayout.draw(canvas)
            canvas.restore()
            curY += phoneLayout.height + 3f
        }

        if (gstinLayout != null) {
            canvas.save()
            canvas.translate(paddingX.toFloat(), curY)
            gstinLayout.draw(canvas)
            canvas.restore()
            curY += gstinLayout.height + 3f
        }

        // Divider
        curY += 4f
        canvas.drawLine(paddingX.toFloat(), curY, (widthDots - paddingX).toFloat(), curY, linePaint)
        curY += 8f

        // 2. Draw Metadata
        canvas.save()
        canvas.translate(paddingX.toFloat(), curY)
        docTitleLayout.draw(canvas)
        canvas.restore()
        curY += docTitleLayout.height + 6f

        canvas.save()
        canvas.translate(paddingX.toFloat(), curY)
        billLayout.draw(canvas)
        canvas.restore()
        curY += billLayout.height + 2f

        canvas.save()
        canvas.translate(paddingX.toFloat(), curY)
        dateLayout.draw(canvas)
        canvas.restore()
        curY += dateLayout.height + 2f

        if (custLayout != null) {
            canvas.save()
            canvas.translate(paddingX.toFloat(), curY)
            custLayout.draw(canvas)
            canvas.restore()
            curY += custLayout.height + 2f
        }

        if (staffLayout != null) {
            canvas.save()
            canvas.translate(paddingX.toFloat(), curY)
            staffLayout.draw(canvas)
            canvas.restore()
            curY += staffLayout.height + 2f
        }

        // Dashed Divider before items
        curY += 4f
        canvas.drawLine(paddingX.toFloat(), curY, (widthDots - paddingX).toFloat(), curY, dashedLinePaint)
        curY += 6f

        // 3. Draw Table Column Headers
        val xCol1 = paddingX.toFloat()
        val xCol2 = xCol1 + col1Width
        val xCol3 = xCol2 + col2Width

        val col1Label = if (config.isBengali) "পণ্য" else "Item"
        val col2Label = if (config.isBengali) "পরিমাণ" else "Qty"
        val col3Label = if (config.isBengali) "মোট(₹)" else "Total (Rs)"

        val rightHeaderPaint = TextPaint(tableHeaderPaint).apply { textAlign = Paint.Align.RIGHT }
        val centerHeaderPaint = TextPaint(tableHeaderPaint).apply { textAlign = Paint.Align.CENTER }

        canvas.drawText(col1Label, xCol1, curY + 14f, tableHeaderPaint)
        canvas.drawText(col2Label, xCol2 + (col2Width / 2f), curY + 14f, centerHeaderPaint)
        canvas.drawText(col3Label, xCol3 + col3Width, curY + 14f, rightHeaderPaint)

        curY += 18f
        canvas.drawLine(paddingX.toFloat(), curY, (widthDots - paddingX).toFloat(), curY, dashedLinePaint)
        curY += 6f

        // 4. Draw Item Rows
        val itemRightPaint = TextPaint(itemDetailPaint).apply { textAlign = Paint.Align.RIGHT }
        val itemCenterPaint = TextPaint(itemDetailPaint).apply { textAlign = Paint.Align.CENTER }

        itemLayouts.forEach { (item, nameLayout) ->
            val qtyText = formatItemQuantity(item.quantity, item.unitType, config.isBengali)
            val subtotalNum = "%.2f".format(Locale.US, item.subtotal)
            val subtotalText = if (config.isBengali) formatBengaliDigits(subtotalNum) else subtotalNum

            canvas.save()
            canvas.translate(xCol1, curY)
            nameLayout.draw(canvas)
            canvas.restore()

            canvas.drawText(qtyText, xCol2 + (col2Width / 2f), curY + 13f, itemCenterPaint)
            canvas.drawText(subtotalText, xCol3 + col3Width, curY + 13f, itemRightPaint)

            var rowH = Math.max(nameLayout.height + 2f, 20f)
            if (!item.discountText.isNullOrBlank()) {
                val discLineText = if (config.isBengali) {
                    formatBengaliDigits(item.discountText)
                } else {
                    item.discountText.replace("₹", "Rs ")
                }
                val discPaint = TextPaint(subtitlePaint).apply {
                    textSize = 11.5f * fontScale
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                }
                canvas.drawText(discLineText, xCol1, curY + nameLayout.height + 12f, discPaint)
                rowH += 16f
            }
            curY += rowH + 4f
        }

        // Dashed line below table
        curY += 2f
        canvas.drawLine(paddingX.toFloat(), curY, (widthDots - paddingX).toFloat(), curY, dashedLinePaint)
        curY += 8f

        // 5. Draw Financial Totals & Discounts
        val bodyLabelPaint = TextPaint(metaLabelPaint)
        val bodyRightValuePaint = TextPaint(metaLabelPaint).apply { textAlign = Paint.Align.RIGHT }
        val bodyBoldRightValuePaint = TextPaint(metaBoldPaint).apply { textAlign = Paint.Align.RIGHT }

        if (data.discount > 0) {
            val discPct = if (data.subtotal > 0) (data.discount / data.subtotal) * 100.0 else 0.0
            val discPctStr = if (discPct % 1.0 == 0.0) "${discPct.toInt()}%" else "%.1f%%".format(Locale.US, discPct)
            val discPctDisplay = if (config.isBengali) formatBengaliDigits(discPctStr) else discPctStr
            val subNum = "%.2f".format(Locale.US, data.subtotal)
            val subText = if (config.isBengali) "₹${formatBengaliDigits(subNum)}" else "Rs $subNum"
            val subLabel = if (config.isBengali) "উপমোট:" else "Subtotal:"
            canvas.drawText(subLabel, xCol1, curY + 14f, bodyLabelPaint)
            canvas.drawText(subText, xCol3 + col3Width, curY + 14f, bodyRightValuePaint)
            curY += 19f

            val discNum = "%.2f".format(Locale.US, data.discount)
            val discText = if (config.isBengali) "-₹${formatBengaliDigits(discNum)}" else "-Rs $discNum"
            val discLabel = if (config.isBengali) "ছাড় ($discPctDisplay):" else "Discount ($discPctDisplay):"
            canvas.drawText(discLabel, xCol1, curY + 14f, bodyLabelPaint)
            canvas.drawText(discText, xCol3 + col3Width, curY + 14f, bodyBoldRightValuePaint)
            curY += 19f
        }

        // Grand Total Row with Highlight Box
        val grandNum = "%.2f".format(Locale.US, data.grandTotal)
        val grandText = if (config.isBengali) "₹${formatBengaliDigits(grandNum)}" else "Rs $grandNum"
        val grandLabel = if (config.isBengali) "মোট কেনাকাটা:" else "Total purchase:"

        canvas.drawText(grandLabel, xCol1, curY + 16f, grandLabelPaint)
        canvas.drawText(grandText, xCol3 + col3Width, curY + 16f, grandValuePaint)
        curY += 24f

        if (data.totalSavings > 0) {
            val origTotal = data.grandTotal + data.totalSavings
            val savPct = if (origTotal > 0) (data.totalSavings / origTotal) * 100.0 else 0.0
            val savPctStr = if (savPct % 1.0 == 0.0) "${savPct.toInt()}%" else "%.0f%%".format(Locale.US, savPct)
            val savPctDisplay = if (config.isBengali) formatBengaliDigits(savPctStr) else savPctStr
            val savNum = "%.2f".format(Locale.US, data.totalSavings)
            val savLabel = if (config.isBengali) "মোট সাশ্রয় ($savPctDisplay):" else "Total savings ($savPctDisplay):"
            val savVal = if (config.isBengali) "₹${formatBengaliDigits(savNum)}" else "Rs $savNum"
            canvas.drawText(savLabel, xCol1, curY + 14f, bodyLabelPaint)
            canvas.drawText(savVal, xCol3 + col3Width, curY + 14f, bodyBoldRightValuePaint)
            curY += 19f
        }

        // Amount Paid & Payment Mode
        val paidNum = "%.2f".format(Locale.US, data.paidAmount)
        val paidText = if (config.isBengali) "₹${formatBengaliDigits(paidNum)}" else "Rs $paidNum"
        val paidLabel = if (config.isBengali) "নগদ/অনলাইন জমা:" else "Cash/online paid:"
        canvas.drawText(paidLabel, xCol1, curY + 14f, bodyLabelPaint)
        canvas.drawText(paidText, xCol3 + col3Width, curY + 14f, bodyBoldRightValuePaint)
        curY += 19f

        if (payModeDisplay.isNotBlank()) {
            val payModeLabel = if (config.isBengali) "পেমেন্ট মাধ্যম:" else "Payment method:"
            val payModeVal = BengaliReceiptTranslator.translatePaymentMode(payModeDisplay, config.isBengali)
            canvas.drawText(payModeLabel, xCol1, curY + 14f, bodyLabelPaint)
            canvas.drawText(payModeVal, xCol3 + col3Width, curY + 14f, bodyRightValuePaint)
            curY += 19f
        }

        if (data.dueAmount > 0) {
            val dueNum = "%.2f".format(Locale.US, data.dueAmount)
            val dueText = if (config.isBengali) "₹${formatBengaliDigits(dueNum)}" else "Rs $dueNum"
            val dueLabel = if (config.isBengali) "আজকের বাকি:" else "Today's due:"
            canvas.drawText(dueLabel, xCol1, curY + 14f, bodyLabelPaint)
            canvas.drawText(dueText, xCol3 + col3Width, curY + 14f, bodyBoldRightValuePaint)
            curY += 19f
        }

        // Ledger & Due Balances
        if (excessPaid > 0 && data.previousBalance > 0) {
            val cleared = minOf(data.previousBalance, excessPaid)
            val clrText = if (config.isBengali) "₹${formatBengaliDigits("%.2f".format(cleared))}" else "Rs %.2f".format(Locale.US, cleared)
            val clrLabel = if (config.isBengali) "বকেয়া শোধ হয়েছে:" else "Prev due cleared:"
            canvas.drawText(clrLabel, xCol1, curY + 14f, bodyLabelPaint)
            canvas.drawText(clrText, xCol3 + col3Width, curY + 14f, bodyRightValuePaint)
            curY += 19f

            val prevText = if (config.isBengali) "₹${formatBengaliDigits("%.2f".format(data.previousBalance))}" else "Rs %.2f".format(Locale.US, data.previousBalance)
            val prevLabel = if (config.isBengali) "পূর্বের বকেয়া:" else "Previous due:"
            canvas.drawText(prevLabel, xCol1, curY + 14f, bodyLabelPaint)
            canvas.drawText(prevText, xCol3 + col3Width, curY + 14f, bodyRightValuePaint)
            curY += 19f

            // Total Due Line
            curY += 4f
            canvas.drawLine(paddingX.toFloat(), curY, (widthDots - paddingX).toFloat(), curY, dashedLinePaint)
            curY += 6f
            val totDueText = if (config.isBengali) "₹${formatBengaliDigits("%.2f".format(netRemainingDue))}" else "Rs %.2f".format(Locale.US, netRemainingDue)
            val totDueLabel = if (config.isBengali) "বর্তমান মোট বাকি:" else "Current total due:"
            canvas.drawText(totDueLabel, xCol1, curY + 16f, grandLabelPaint)
            canvas.drawText(totDueText, xCol3 + col3Width, curY + 16f, grandValuePaint)
            curY += 22f

            if (advanceCredit > 0) {
                val advText = if (config.isBengali) "₹${formatBengaliDigits("%.2f".format(advanceCredit))}" else "Rs %.2f".format(Locale.US, advanceCredit)
                val advLabel = if (config.isBengali) "অতিরিক্ত জমা (অ্যাডভান্স):" else "Advance credit:"
                canvas.drawText(advLabel, xCol1, curY + 14f, bodyLabelPaint)
                canvas.drawText(advText, xCol3 + col3Width, curY + 14f, bodyRightValuePaint)
                curY += 19f
            }
        } else if (excessPaid > 0) {
            val exText = if (config.isBengali) "₹${formatBengaliDigits("%.2f".format(excessPaid))}" else "Rs %.2f".format(Locale.US, excessPaid)
            val exLabel = if (config.isBengali) "ফেরত দেওয়া হয়েছে:" else "Change returned:"
            canvas.drawText(exLabel, xCol1, curY + 14f, bodyLabelPaint)
            canvas.drawText(exText, xCol3 + col3Width, curY + 14f, bodyRightValuePaint)
            curY += 19f
        } else if (data.previousBalance > 0 || data.dueAmount > 0) {
            if (data.previousBalance > 0) {
                val prevText = if (config.isBengali) "₹${formatBengaliDigits("%.2f".format(data.previousBalance))}" else "Rs %.2f".format(Locale.US, data.previousBalance)
                val prevLabel = if (config.isBengali) "পূর্বের বকেয়া:" else "Previous due:"
                canvas.drawText(prevLabel, xCol1, curY + 14f, bodyLabelPaint)
                canvas.drawText(prevText, xCol3 + col3Width, curY + 14f, bodyRightValuePaint)
                curY += 19f
            }

            curY += 4f
            canvas.drawLine(paddingX.toFloat(), curY, (widthDots - paddingX).toFloat(), curY, dashedLinePaint)
            curY += 6f
            val totDueText = if (config.isBengali) "₹${formatBengaliDigits("%.2f".format(data.previousBalance + data.dueAmount))}" else "Rs %.2f".format(Locale.US, data.previousBalance + data.dueAmount)
            val totDueLabel = if (config.isBengali) "বর্তমান মোট বাকি:" else "Current total due:"
            canvas.drawText(totDueLabel, xCol1, curY + 16f, grandLabelPaint)
            canvas.drawText(totDueText, xCol3 + col3Width, curY + 16f, grandValuePaint)
            curY += 22f
        }

        // Draw Prominent Due Date Line
        if (dueDateLayout != null) {
            canvas.save()
            canvas.translate(paddingX.toFloat(), curY)
            dueDateLayout.draw(canvas)
            canvas.restore()
            curY += dueDateLayout.height + 4f
        }

        // Divider
        curY += 4f
        canvas.drawLine(paddingX.toFloat(), curY, (widthDots - paddingX).toFloat(), curY, dashedLinePaint)
        curY += 8f

        // 5.5 Draw Payment Status Banner (e.g. "Payment Received") if active
        if (statusBannerLayout != null) {
            canvas.save()
            canvas.translate(paddingX.toFloat(), curY)
            statusBannerLayout.draw(canvas)
            canvas.restore()
            curY += statusBannerLayout.height + 8f

            curY += 4f
            canvas.drawLine(paddingX.toFloat(), curY, (widthDots - paddingX).toFloat(), curY, dashedLinePaint)
            curY += 8f
        }

        // 6. Draw Payment QR Code (if active)
        if (qrBitmap != null && qrCaptionLayout != null) {
            val qrLeft = (widthDots - qrBitmap.width) / 2f
            canvas.drawBitmap(qrBitmap, qrLeft, curY, null)
            curY += qrBitmap.height + 4f

            canvas.save()
            canvas.translate(paddingX.toFloat(), curY)
            qrCaptionLayout.draw(canvas)
            canvas.restore()
            curY += qrCaptionLayout.height + 8f

            curY += 4f
            canvas.drawLine(paddingX.toFloat(), curY, (widthDots - paddingX).toFloat(), curY, dashedLinePaint)
            curY += 8f
        }

        // 6.5 Draw Khata Interest Policy & Due Day Disclaimer
        if (disclaimerLayout != null) {
            canvas.save()
            canvas.translate(paddingX.toFloat(), curY)
            disclaimerLayout.draw(canvas)
            canvas.restore()
            curY += disclaimerLayout.height + 6f

            curY += 4f
            canvas.drawLine(paddingX.toFloat(), curY, (widthDots - paddingX).toFloat(), curY, dashedLinePaint)
            curY += 8f
        }

        // 7. Draw 1D Barcode with Clean Quiet-Zone Padding
        if (barcodeBitmap != null) {
            val barcodeLeft = Math.round((widthDots - barcodeBitmap.width) / 2f).toFloat().coerceAtLeast(0f)
            val noFilterPaint = Paint().apply {
                isFilterBitmap = false
                isAntiAlias = false
            }
            canvas.drawBitmap(barcodeBitmap, barcodeLeft, curY, noFilterPaint)
            curY += barcodeBitmap.height + 3f

            val barcodeText = if (rawBarcodeCode.contains("-") && rawBarcodeCode.length >= 20) "Bill #$cleanBarcodeCode" else cleanBarcodeCode
            canvas.drawText(barcodeText, widthDots / 2f, curY + 12f, digitPaint)
            curY += 18f

            curY += 4f
            canvas.drawLine(paddingX.toFloat(), curY, (widthDots - paddingX).toFloat(), curY, dashedLinePaint)
            curY += 8f
        }

        // 8. Draw Custom Footer & Greetings
        if (footerLayout != null) {
            canvas.save()
            canvas.translate(paddingX.toFloat(), curY)
            footerLayout.draw(canvas)
            canvas.restore()
            curY += footerLayout.height + 6f
        }

        canvas.save()
        canvas.translate(paddingX.toFloat(), curY)
        greetingLayout.draw(canvas)
        canvas.restore()
        curY += greetingLayout.height + 4f

        return finalBitmap
    }

    // =========================================================================
    // 2. HIGH-DENSITY IMAGE & BARCODE GENERATORS WITH DEDICATED PADDING
    // =========================================================================

    /**
     * Generates a 1D Barcode (Code-128) with controlled horizontal and vertical quiet zones (padding)
     * and crisp non-aliased black/white modules for reliable optical scanner gun reading.
     */
    fun generateReceiptBarcode(
        code: String,
        width: Int = 384,
        height: Int = 52,
        horizontalPadding: Int = 20,
        verticalPadding: Int = 4
    ): Bitmap? {
        val cleanCode = code.trim().replace(" ", "")
        if (cleanCode.isBlank()) return null

        val availableWidth = (width - (horizontalPadding * 2)).coerceAtLeast(160)
        val availableHeight = (height - (verticalPadding * 2)).coerceAtLeast(24)

        return try {
            PdfReceiptHelper.generate1DBarcodeBitmap(
                text = cleanCode,
                width = availableWidth,
                height = availableHeight
            )
        } catch (e: Exception) {
            android.util.Log.e("ThermalPrintingService", "Failed to generate receipt barcode for $cleanCode", e)
            null
        }
    }

    /**
     * Generates a 2D QR Code (UPI / URL) with clear white quiet-zone padding around the matrix.
     */
    fun generateReceiptQrCode(
        payload: String,
        size: Int = 200,
        quietZonePadding: Int = 6
    ): Bitmap? {
        val cleanText = payload.trim()
        if (cleanText.isBlank()) return null

        val matrixSize = (size - (quietZonePadding * 2)).coerceAtLeast(80)
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
            put(EncodeHintType.MARGIN, 0)
        }

        return try {
            val bitMatrix: BitMatrix = MultiFormatWriter().encode(cleanText, BarcodeFormat.QR_CODE, matrixSize, matrixSize, hints)
            val outputBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            outputBitmap.eraseColor(Color.WHITE)
            val canvas = Canvas(outputBitmap)

            val startX = (size - bitMatrix.width) / 2
            val startY = (size - bitMatrix.height) / 2

            for (x in 0 until bitMatrix.width) {
                for (y in 0 until bitMatrix.height) {
                    if (bitMatrix[x, y]) {
                        outputBitmap.setPixel(startX + x, startY + y, Color.BLACK)
                    }
                }
            }
            outputBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // =========================================================================
    // 3. ESC/POS RASTER BYTE ENCODER (`GS v 0`)
    // =========================================================================

    /**
     * Converts an Android Bitmap into ESC/POS Raster bit-image commands (`GS v 0 \x00`).
     * Calibrated precisely for 58mm (384 dots width = 48 bytes per line) to prevent diagonal wrapping.
     * Uses crisp hard thresholding without dithering or anti-aliasing speckles for clean, solid Bengali & English text.
     */
    fun bitmapTo58mmEscPosRaster(
        sourceBitmap: Bitmap,
        feedLines: Int = StoreInfoManager.thermalFeedLines,
        cutPaper: Boolean = false,
        threshold: Int = StoreInfoManager.thermalThreshold,
        isBengali: Boolean = false
    ): ByteArray {
        val widthDots = THERMAL_58MM_WIDTH_DOTS
        val widthBytes = BYTES_PER_LINE_58MM

        // Rescale height proportionally if width doesn't match 384 dots (nearest-neighbor to avoid blur)
        val scaled = if (sourceBitmap.width != widthDots) {
            val scaledHeight = ((sourceBitmap.height.toFloat() / sourceBitmap.width.toFloat()) * widthDots).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(sourceBitmap, widthDots, scaledHeight, false)
        } else {
            sourceBitmap
        }

        val bitmap = if (StoreInfoManager.thermalRasterDilation && !isBengali) {
            EscPosPrinter.dilateBitmap(scaled, threshold.coerceIn(0, 255))
        } else {
            scaled
        }

        val height = bitmap.height
        val totalHeight = height
        val baos = ByteArrayOutputStream()

        // 1. Printer Initialization (ESC @) - standard reset without vendor mode prefix
        baos.write(byteArrayOf(0x1B, 0x40))

        // 2. High Thermal Strobe / Density Heating (ESC 7 n1 n2 n3)
        // n1=max heating dots (7), n2=heating time (~100), n3=heating interval mapped from speed preset
        val speed = StoreInfoManager.thermalPrintSpeed
        baos.write(byteArrayOf(0x1B, 0x37, 0x07, 100.toByte(), speed.heatingInterval))

        // 3. Set thermal print density (DC2 #)
        baos.write(byteArrayOf(0x12, 0x23, 0x14.toByte()))

        // 4. Set line spacing to 0 for seamless raster flow (ESC 3 0)
        baos.write(byteArrayOf(0x1B, 0x33, 0x00))

        // 5. Output in manageable chunks (128 lines per chunk) to protect Bluetooth RFCOMM buffer
        val chunkHeight = 128
        var y = 0
        // Dedicated Bengali bitmap threshold:
        // Bengali script features thin horizontal matras and intricate conjuncts.
        // A dedicated threshold (205-215) ensures anti-aliased edge pixels (luminance < 205)
        // are captured cleanly on 203 DPI thermal paper, preventing dropouts.
        val thresholdVal = if (isBengali) {
            maxOf(threshold + 45, 205).coerceIn(195, 220)
        } else {
            threshold.coerceIn(0, 255)
        }
        while (y < totalHeight) {
            val currentChunkHeight = Math.min(chunkHeight, totalHeight - y)

            val xL = (widthBytes and 0xFF).toByte()
            val xH = ((widthBytes shr 8) and 0xFF).toByte()
            val yL = (currentChunkHeight and 0xFF).toByte()
            val yH = ((currentChunkHeight shr 8) and 0xFF).toByte()

            baos.write(byteArrayOf(0x1D, 0x76, 0x30, 0x00, xL, xH, yL, yH))

            val pixels = IntArray(widthDots * currentChunkHeight)
            if (y < height) {
                val availableRows = Math.min(currentChunkHeight, height - y)
                bitmap.getPixels(pixels, 0, widthDots, 0, y, widthDots, availableRows)
            }

            for (row in 0 until currentChunkHeight) {
                for (colByte in 0 until widthBytes) {
                    var byteVal = 0
                    val bitOffset = colByte * 8

                    for (bit in 0 until 8) {
                        val pixelX = bitOffset + bit
                        if (pixelX < widthDots) {
                            val color = pixels[row * widthDots + pixelX]
                            val alpha = (color ushr 24) and 0xFF
                            if (alpha > 30) {
                                val r = (color shr 16) and 0xFF
                                val g = (color shr 8) and 0xFF
                                val b = color and 0xFF
                                // Composite onto white background for accurate luminance
                                val effR = (r * alpha + 255 * (255 - alpha)) / 255
                                val effG = (g * alpha + 255 * (255 - alpha)) / 255
                                val effB = (b * alpha + 255 * (255 - alpha)) / 255
                                val luminance = (effR * 299 + effG * 587 + effB * 114) / 1000

                                // Clean hard threshold: eliminates light-gray anti-aliased speckles while keeping strokes solid
                                if (luminance < thresholdVal) {
                                    byteVal = byteVal or (0x80 shr bit)
                                }
                            }
                        }
                    }
                    baos.write(byteVal)
                }
            }
            y += currentChunkHeight
        }

        // 6. Reset default line spacing (ESC 2)
        baos.write(byteArrayOf(0x1B, 0x32))

        // 7. Clean, reliable paper feed: feeds exactly linesToFeed lines using ESC d n
        if (feedLines > 0) {
            val linesToFeed = feedLines.coerceIn(1, 8)
            baos.write(0x0A)
            baos.write(byteArrayOf(0x1B, 0x64, linesToFeed.toByte()))
        }

        // 8. Optional Auto-Cutter (GS V 66 0)
        if (cutPaper) {
            baos.write(byteArrayOf(0x1D, 0x56, 0x42, 0x00))
        }

        return baos.toByteArray()
    }

    fun bitmapTo58mmEscPosRaster(
        sourceBitmap: Bitmap,
        feedLines: Int = StoreInfoManager.thermalFeedLines,
        cutPaper: Boolean = false,
        density: String
    ): ByteArray {
        val parsedThreshold = density.toIntOrNull() ?: when (density.uppercase()) {
            "LIGHT" -> 130
            "NORMAL" -> 150
            "DARK" -> 165
            "EXTRA_DARK" -> 180
            else -> StoreInfoManager.thermalThreshold
        }
        return bitmapTo58mmEscPosRaster(sourceBitmap, feedLines, cutPaper, parsedThreshold)
    }

    // =========================================================================
    // 4. ASYNCHRONOUS BLUETOOTH PRINTING ROUTINE WITH AUTO-RECONNECT
    // =========================================================================

    /**
     * Transmits the formatted receipt over Bluetooth RFCOMM socket asynchronously with auto-reconnect retry.
     */
    @SuppressLint("MissingPermission")
    suspend fun print58mmReceipt(
        deviceAddress: String,
        receiptBitmap: Bitmap,
        feedLines: Int = StoreInfoManager.thermalFeedLines,
        cutPaper: Boolean = false,
        threshold: Int = StoreInfoManager.thermalThreshold,
        isBengali: Boolean = false
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val rasterBytes = bitmapTo58mmEscPosRaster(receiptBitmap, feedLines, cutPaper, threshold, isBengali)
        EscPosPrinter.sendBytesToPrinter(deviceAddress, rasterBytes)
    }

    @SuppressLint("MissingPermission")
    suspend fun print58mmReceipt(
        deviceAddress: String,
        receiptBitmap: Bitmap,
        feedLines: Int = StoreInfoManager.thermalFeedLines,
        cutPaper: Boolean = false,
        density: String,
        isBengali: Boolean = false
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val parsedThreshold = density.toIntOrNull() ?: when (density.uppercase()) {
            "LIGHT" -> 130
            "NORMAL" -> 150
            "DARK" -> 165
            "EXTRA_DARK" -> 180
            else -> StoreInfoManager.thermalThreshold
        }
        print58mmReceipt(deviceAddress, receiptBitmap, feedLines, cutPaper, parsedThreshold, isBengali)
    }

    @SuppressLint("MissingPermission")
    suspend fun print58mmHybridReceipt(
        deviceAddress: String,
        lines: List<EscPosPrinter.HybridReceiptLine>,
        density: String = StoreInfoManager.thermalPrinterDensity,
        fontSize: String = StoreInfoManager.thermalReceiptFontSize,
        feedLines: Int = StoreInfoManager.thermalFeedLines
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val hybridBytes = EscPosPrinter.buildHybridBytesFromLines(
            lines = lines,
            widthDots = THERMAL_58MM_WIDTH_DOTS,
            density = density,
            fontSize = fontSize,
            feedLines = feedLines
        )
        EscPosPrinter.sendBytesToPrinter(deviceAddress, hybridBytes)
    }

    // =========================================================================
    // 5. HELPER UTILITIES FOR INDIC FONTS & FORMATTING
    // =========================================================================

    private fun createStaticLayout(
        text: CharSequence,
        paint: TextPaint,
        width: Int,
        alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL
    ): StaticLayout {
        val safeWidth = width.coerceAtLeast(10)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, safeWidth)
                .setAlignment(alignment)
                .setLineSpacing(0f, 1.05f)
                .setIncludePad(false)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(text, paint, safeWidth, alignment, 1.05f, 0f, false)
        }
    }

    fun formatBengaliDigits(input: String): String {
        val bnDigits = charArrayOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')
        val sb = StringBuilder()
        for (ch in input) {
            if (ch in '0'..'9') {
                sb.append(bnDigits[ch - '0'])
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    internal fun formatItemQuantity(qty: Double, unitType: String, isBengali: Boolean): String {
        val isGram = com.example.data.local.entities.Product.isGramUnit(unitType)
        val isKg = com.example.data.local.entities.Product.isKgUnit(unitType)
        val isPcs = unitType.equals("pcs", ignoreCase = true) || unitType.equals("piece", ignoreCase = true) || unitType.contains("পিস", ignoreCase = true)
        val isLitre = com.example.data.local.entities.Product.isLitreUnit(unitType)
        val isMl = com.example.data.local.entities.Product.isMlUnit(unitType)
        val isBox = unitType.equals("box", ignoreCase = true) || unitType.contains("বক্স", ignoreCase = true) || unitType.contains("বাক্স", ignoreCase = true)

        val rawQty = if (isGram || isMl || qty % 1.0 == 0.0) {
            "${qty.toInt()}"
        } else {
            "%.2f".format(Locale.US, qty)
        }

        val unitName = if (isBengali) {
            when {
                isGram -> "গ্রাম"
                isKg -> "কেজি"
                isPcs -> "পিস"
                isLitre -> "লিটার"
                isMl -> "মিলি"
                isBox -> "বক্স"
                else -> unitType
            }
        } else {
            when {
                isGram -> "g"
                isKg -> "kg"
                isPcs -> "pcs"
                isLitre -> "L"
                isMl -> "ml"
                isBox -> if (qty == 1.0) "box" else "boxes"
                else -> unitType
            }
        }

        val numStr = if (isBengali) formatBengaliDigits(rawQty) else rawQty
        return "$numStr $unitName"
    }
}
