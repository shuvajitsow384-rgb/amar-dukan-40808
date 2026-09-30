package com.example.utils

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.data.local.dao.PurchaseWithItems
import com.example.data.local.dao.SaleWithItems
import com.example.data.local.entities.Customer
import com.example.data.local.entities.LedgerEntry
import com.example.data.local.entities.Supplier
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class ProcessedReceiptItem(
    val lines: List<String>,
    val qtyStr: String,
    val subtotalStr: String,
    val discountText: String? = null
)

object PdfReceiptHelper {

    fun generateReceiptPdf(
        context: Context,
        saleWithItems: SaleWithItems,
        isBengali: Boolean = StoreInfoManager.isBillBengali()
    ): File {
        val paperFormat = StoreInfoManager.pdfPaperSize
        return if (paperFormat == "THERMAL_58MM") {
            generateThermal58mmReceipt(context, saleWithItems, isBengali)
        } else if (paperFormat == "A4") {
            generateA4StandardReceipt(context, saleWithItems, isBengali)
        } else {
            generateThermal80mmReceipt(context, saleWithItems, isBengali)
        }
    }

    private fun generateA4StandardReceipt(
        context: Context,
        saleWithItems: SaleWithItems,
        isBengali: Boolean = StoreInfoManager.isBillBengali()
    ): File {
        val pdfDocument = PdfDocument()

        val pageWidth = 595
        val pageHeight = 842
        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        val page = pdfDocument.startPage(pageInfo)
        val canvas: Canvas = page.canvas

        val isBn = isBengali
        val headerColorInt = parseColorHex(StoreInfoManager.pdfHeaderColor, Color.rgb(29, 108, 49))

        // Paints
        val headerBgPaint = Paint().apply {
            color = headerColorInt
        }

        val titlePaint = Paint().apply {
            color = Color.WHITE
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        val headerSubPaint = Paint().apply {
            color = Color.rgb(230, 245, 235)
            textSize = 10f
            typeface = Typeface.DEFAULT
            textAlign = Paint.Align.CENTER
        }

        val sectionTitlePaint = Paint().apply {
            color = headerColorInt
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val textPaint = Paint().apply {
            color = Color.rgb(30, 30, 30)
            textSize = 10.5f
            typeface = Typeface.DEFAULT
        }

        val textBoldPaint = Paint().apply {
            color = Color.rgb(30, 30, 30)
            textSize = 10.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val textRightPaint = Paint().apply {
            color = Color.rgb(30, 30, 30)
            textSize = 10.5f
            typeface = Typeface.DEFAULT
            textAlign = Paint.Align.RIGHT
        }

        val tableHeaderPaint = Paint().apply {
            color = Color.WHITE
            textSize = 10.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val tableHeaderBgPaint = Paint().apply {
            color = headerColorInt
        }

        val linePaint = Paint().apply {
            color = Color.rgb(220, 220, 220)
            strokeWidth = 1f
        }

        val leftMargin = 40f
        val rightMargin = pageWidth - 40f

        // 1. Header Banner
        canvas.drawRect(0f, 0f, pageWidth.toFloat(), 90f, headerBgPaint)

        var headerY = 32f
        val storeDisplayName = StoreInfoManager.getStoreDisplayName(isBn)
        val storeDisplayAddress = StoreInfoManager.getStoreDisplayAddress(isBn)
        canvas.drawText(storeDisplayName.uppercase(), pageWidth / 2f, headerY, titlePaint)
        headerY += 16f

        val storeSubHeader = buildString {
            append(storeDisplayAddress)
            if (StoreInfoManager.phone.isNotBlank()) append(if (isBn) " | ফোন: ${StoreInfoManager.phone}" else " | Ph: ${StoreInfoManager.phone}")
        }
        canvas.drawText(storeSubHeader, pageWidth / 2f, headerY, headerSubPaint)
        headerY += 14f

        val extraSub = buildString {
            if (StoreInfoManager.tagline.isNotBlank()) append("“${StoreInfoManager.tagline}” ")
            if (StoreInfoManager.gstin.isNotBlank()) append(" | GSTIN: ${StoreInfoManager.gstin}")
        }
        if (extraSub.isNotBlank()) {
            canvas.drawText(extraSub, pageWidth / 2f, headerY, headerSubPaint)
        }

        var y = 110f

        // Invoice Badge
        val invoiceType = if (isBn) "ট্যাক্স ইনভয়েস / ক্যাশ মেমো" else "TAX INVOICE / CASH RECEIPT"
        canvas.drawText(invoiceType, leftMargin, y, sectionTitlePaint)

        y += 15f
        canvas.drawLine(leftMargin, y, rightMargin, y, linePaint)
        y += 18f

        // 2. Transaction Meta
        val sale = saleWithItems.sale
        val dateFormat = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        val dateStr = dateFormat.format(Date(sale.datetime))

        val billNoLabel = if (isBn) "বিল নং:" else "Bill No:"
        val dateLabel = if (isBn) "তারিখ:" else "Date:"
        canvas.drawText("$billNoLabel #${sale.id.takeLast(8).uppercase()}", leftMargin, y, textBoldPaint)
        canvas.drawText("$dateLabel $dateStr", rightMargin, y, textRightPaint)
        y += 16f

        val custName = BengaliReceiptTranslator.translateCustomerName(sale.customerName, isBn)
        val staffDisplay = BengaliReceiptTranslator.translateStaffName(sale.staffName, isBn)
        val staffText = if (staffDisplay.isNotBlank()) " (${if (isBn) "কর্মী" else "Billed by"}: $staffDisplay)" else ""
        val custPrefix = if (isBn) "গ্রাহক:" else "Customer:"
        val payModeDisplay = BengaliReceiptTranslator.translatePaymentMode(sale.paymentMode, isBn)
        val payModePrefix = if (isBn) "পেমেন্ট মাধ্যম:" else "Payment Mode:"
        canvas.drawText("$custPrefix $custName$staffText", leftMargin, y, textPaint)
        canvas.drawText("$payModePrefix $payModeDisplay", rightMargin, y, textRightPaint)
        y += 24f

        // 3. Table Header
        val colItemX = leftMargin + 10f
        val colQtyX = leftMargin + 270f
        val colPriceX = leftMargin + 370f
        val colTotalX = rightMargin - 10f

        canvas.drawRect(leftMargin, y, rightMargin, y + 22f, tableHeaderBgPaint)
        val tableHeaderY = y + 15f
        canvas.drawText(if (isBn) "সামগ্রীর বিবরণ" else "ITEM DESCRIPTION", colItemX, tableHeaderY, tableHeaderPaint)
        canvas.drawText(if (isBn) "পরিমাণ" else "QTY", colQtyX, tableHeaderY, tableHeaderPaint)
        canvas.drawText(if (isBn) "দর (₹)" else "RATE (₹)", colPriceX, tableHeaderY, tableHeaderPaint)
        canvas.drawText(if (isBn) "মোট (₹)" else "TOTAL (₹)", colTotalX, tableHeaderY, Paint(tableHeaderPaint).apply { textAlign = Paint.Align.RIGHT })

        y += 28f

        // 4. Items List
        saleWithItems.items.forEachIndexed { index, item ->
            val rawName = if (isBn) {
                if (item.productNameBn.isNotBlank() && BengaliReceiptTranslator.containsBengali(item.productNameBn)) {
                    item.productNameBn
                } else {
                    BengaliReceiptTranslator.translateItem(item.productNameEn.ifBlank { item.productNameBn })
                }
            } else {
                item.productNameEn.ifBlank { item.productNameBn }.ifBlank { "Item" }
            }
            val itemName = BengaliReceiptTranslator.cleanReceiptProductName(rawName)
            val qtyStr = formatReceiptQtyWithUnit(item.quantity, item.unitType, isBn)
            val rateStr = "₹%.2f".format(item.unitPrice)
            val subtotalStr = "₹%.2f".format(item.subtotal)

            canvas.drawText("${index + 1}. $itemName", colItemX, y, textPaint)
            canvas.drawText(qtyStr, colQtyX, y, textPaint)
            canvas.drawText(rateStr, colPriceX, y, textPaint)
            canvas.drawText(subtotalStr, colTotalX, y, textRightPaint)

            if (item.hasDiscount()) {
                y += 12f
                val discLine = BengaliReceiptTranslator.formatDiscountItemLine(item.getEffectiveMrp(), item.unitPrice, item.quantity, isBn)
                val discountItemPaint = Paint().apply {
                    color = Color.rgb(46, 125, 50)
                    textSize = 8.5f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                }
                canvas.drawText("   $discLine", colItemX, y, discountItemPaint)
            }

            y += 16f
            canvas.drawLine(leftMargin, y, rightMargin, y, linePaint)
            y += 12f
        }

        y += 10f

        // 5. Total & Merchant QR Layout
        val calcLabelX = leftMargin + 300f

        if (sale.discount > 0) {
            val discPct = if (sale.totalAmount > 0) (sale.discount / sale.totalAmount) * 100.0 else 0.0
            canvas.drawText(if (isBn) "উপমোট:" else "Subtotal:", calcLabelX, y, textPaint)
            canvas.drawText("₹%.2f".format(sale.totalAmount), colTotalX, y, textRightPaint)
            y += 16f

            val discLabel = if (isBn) "ছাড় (${"%.1f".format(discPct)}%):" else "Discount (${"%.1f".format(discPct)}%):"
            canvas.drawText(discLabel, calcLabelX, y, textPaint)
            canvas.drawText("- ₹%.2f".format(sale.discount), colTotalX, y, textRightPaint)
            y += 16f
        }

        val totalMrpSavingsA4 = saleWithItems.items.sumOf { it.getSavingsAmount() }
        val overallSavingsA4 = totalMrpSavingsA4 + sale.discount
        if (overallSavingsA4 > 0.0) {
            val savingsPctA4 = if ((sale.totalAmount + totalMrpSavingsA4) > 0) (overallSavingsA4 / (sale.totalAmount + totalMrpSavingsA4)) * 100.0 else 0.0
            val savPaint = Paint().apply {
                color = Color.rgb(46, 125, 50)
                textSize = 10.5f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val savRightPaint = Paint(savPaint).apply { textAlign = Paint.Align.RIGHT }
            val savLabel = if (isBn) "মোট সাশ্রয় (${"%.0f".format(savingsPctA4)}%):" else "Total Savings (${"%.0f".format(savingsPctA4)}%):"
            canvas.drawText(savLabel, calcLabelX, y, savPaint)
            canvas.drawText("₹%.2f".format(overallSavingsA4), colTotalX, y, savRightPaint)
            y += 16f
        }

        canvas.drawLine(calcLabelX, y, rightMargin, y, linePaint)
        y += 16f

        val netPaint = Paint().apply {
            color = headerColorInt
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val netRightPaint = Paint(netPaint).apply { textAlign = Paint.Align.RIGHT }

        canvas.drawText(if (isBn) "সর্বমোট:" else "GRAND TOTAL:", calcLabelX, y, netPaint)
        canvas.drawText("₹%.2f".format(sale.finalAmount), colTotalX, y, netRightPaint)
        y += 16f

        val excessPaid = (sale.receivedAmount - sale.finalAmount).coerceAtLeast(0.0)
        val netRemainingDue = (sale.previousBalance + sale.dueAmount - excessPaid).coerceAtLeast(0.0)
        val advanceCredit = (excessPaid - (sale.previousBalance + sale.dueAmount)).coerceAtLeast(0.0)
        val hasCustomer = !sale.customerName.isNullOrBlank() || !sale.customerId.isNullOrBlank()

        canvas.drawText("${if (isBn) "পরিশোধিত" else "Amount Paid"} ($payModeDisplay):", calcLabelX, y, textPaint)
        canvas.drawText("₹%.2f".format(sale.receivedAmount), colTotalX, y, textRightPaint)
        y += 14f

        if (sale.dueAmount > 0) {
            canvas.drawText(if (isBn) "আজকের বাকি:" else "Added to Credit Today:", calcLabelX, y, textPaint)
            canvas.drawText("₹%.2f".format(sale.dueAmount), colTotalX, y, textRightPaint)
            y += 14f
        }

        if (excessPaid > 0 && sale.previousBalance > 0) {
            val dueCleared = minOf(sale.previousBalance, excessPaid)
            canvas.drawText(if (isBn) "পূর্বের বাকি শোধ:" else "Paid Towards Prev Due:", calcLabelX, y, textPaint)
            canvas.drawText("₹%.2f".format(dueCleared), colTotalX, y, textRightPaint)
            y += 14f

            canvas.drawText(if (isBn) "পূর্বের বকেয়া:" else "Previous Due Balance:", calcLabelX, y, textPaint)
            canvas.drawText("₹%.2f".format(sale.previousBalance), colTotalX, y, textRightPaint)
            y += 14f

            val dueTotalPaint = Paint().apply {
                color = if (netRemainingDue > 0) Color.RED else Color.parseColor("#1D6C31")
                textSize = 12f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val dueTotalRightPaint = Paint(dueTotalPaint).apply { textAlign = Paint.Align.RIGHT }

            canvas.drawText(if (isBn) "বর্তমান মোট বাকি:" else "TOTAL BALANCE DUE:", calcLabelX, y, dueTotalPaint)
            canvas.drawText("₹%.2f".format(netRemainingDue), colTotalX, y, dueTotalRightPaint)
            y += 16f

            if (advanceCredit > 0) {
                canvas.drawText(if (isBn) "অগ্রিম জমা ব্যালেন্স:" else "Advance Credit Balance:", calcLabelX, y, textPaint)
                canvas.drawText("₹%.2f".format(advanceCredit), colTotalX, y, textRightPaint)
                y += 14f
            }
        } else if (excessPaid > 0) {
            if (hasCustomer) {
                canvas.drawText(if (isBn) "অগ্রিম জমা ব্যালেন্স:" else "Advance Credit Balance:", calcLabelX, y, textPaint)
                canvas.drawText("₹%.2f".format(excessPaid), colTotalX, y, textRightPaint)
                y += 14f
            } else {
                canvas.drawText(if (isBn) "ফেরত টাকা:" else "Change Returned:", calcLabelX, y, textPaint)
                canvas.drawText("₹%.2f".format(excessPaid), colTotalX, y, textRightPaint)
                y += 14f
            }
        } else if (sale.previousBalance > 0 || sale.dueAmount > 0) {
            if (sale.previousBalance > 0) {
                canvas.drawText(if (isBn) "পূর্বের বকেয়া:" else "Previous Due Balance:", calcLabelX, y, textPaint)
                canvas.drawText("₹%.2f".format(sale.previousBalance), colTotalX, y, textRightPaint)
                y += 14f
            }

            val dueTotalPaint = Paint().apply {
                color = Color.RED
                textSize = 12f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val dueTotalRightPaint = Paint(dueTotalPaint).apply { textAlign = Paint.Align.RIGHT }

            canvas.drawText(if (isBn) "বর্তমান মোট বাকি:" else "TOTAL BALANCE DUE:", calcLabelX, y, dueTotalPaint)
            canvas.drawText("₹%.2f".format(sale.previousBalance + sale.dueAmount), colTotalX, y, dueTotalRightPaint)
            y += 16f
        }

        // 6. Merchant Payment QR Code Section
        y += 35f

        val totalDue = netRemainingDue
        val paymentModeUpper = sale.paymentMode.uppercase()
        val shouldShowQr = StoreInfoManager.showQrOnPdf &&
                StoreInfoManager.merchantUpiId.isNotBlank() &&
                (totalDue > 0.0 || paymentModeUpper.contains("UPI"))

        val interestSettingsA4 = StoreInfoManager.getKhataInterestSettings()
        val hasCreditDueA4 = totalDue > 0.0 || sale.dueAmount > 0.0 || sale.previousBalance > 0 || paymentModeUpper.contains("CREDIT")
        val dueDateMsA4 = KhataInterestCalculator.calculateDueDateTimestamp(sale.datetime, sale.dueDate, interestSettingsA4.gracePeriodDays)
        val dueDateFormattedA4 = KhataInterestCalculator.formatDueDate(dueDateMsA4, interestSettingsA4.gracePeriodDays, isBengali = false)

        if (shouldShowQr) {
            val qrAmount = if (totalDue > 0.0) totalDue else sale.finalAmount
            val qrNote = if (totalDue > 0.0) "Due Payment Bill #${sale.id.takeLast(6)}" else "Bill #${sale.id.takeLast(6)}"
            try {
                val upiUrl = buildUpiString(
                    upiId = StoreInfoManager.merchantUpiId,
                    payeeName = StoreInfoManager.merchantPayeeName,
                    amount = qrAmount,
                    note = qrNote
                )
                val qrBitmap = generateQrCodeBitmap(upiUrl, 130, 130)

                if (qrBitmap != null) {
                    val qrCardBg = Paint().apply {
                        color = Color.rgb(245, 247, 250)
                    }
                    val qrCardBorder = Paint().apply {
                        color = Color.rgb(200, 215, 230)
                        style = Paint.Style.STROKE
                        strokeWidth = 1f
                    }

                    val qrBoxLeft = leftMargin
                    val qrBoxTop = y
                    val qrBoxRight = leftMargin + 260f
                    val qrBoxBottom = y + 140f

                    canvas.drawRoundRect(RectF(qrBoxLeft, qrBoxTop, qrBoxRight, qrBoxBottom), 8f, 8f, qrCardBg)
                    canvas.drawRoundRect(RectF(qrBoxLeft, qrBoxTop, qrBoxRight, qrBoxBottom), 8f, 8f, qrCardBorder)

                    canvas.drawBitmap(qrBitmap, qrBoxLeft + 10f, qrBoxTop + 5f, null)

                    var qrTextY = qrBoxTop + 25f
                    val qrTitlePaint = Paint().apply {
                        color = headerColorInt
                        textSize = 10f
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    }
                    val qrSubPaint = Paint().apply {
                        color = Color.rgb(80, 80, 80)
                        textSize = 8.5f
                    }

                    val qrTitleHeader = if (totalDue > 0.0) "SCAN & PAY DUE BALANCE" else "SCAN & PAY VIA UPI"
                    canvas.drawText(qrTitleHeader, qrBoxLeft + 145f, qrTextY, qrTitlePaint)
                    qrTextY += 14f
                    canvas.drawText("GPay • PhonePe • Paytm • BHIM", qrBoxLeft + 145f, qrTextY, qrSubPaint)
                    qrTextY += 16f
                    canvas.drawText("UPI ID: ${StoreInfoManager.merchantUpiId}", qrBoxLeft + 145f, qrTextY, Paint(qrSubPaint).apply { typeface = Typeface.DEFAULT_BOLD; color = Color.BLACK })
                    qrTextY += 14f
                    canvas.drawText("Merchant: ${StoreInfoManager.merchantPayeeName}", qrBoxLeft + 145f, qrTextY, qrSubPaint)
                    qrTextY += 14f
                    val amtLabel = if (totalDue > 0.0) "Total Due: ₹%.2f".format(qrAmount) else "Amount: ₹%.2f".format(qrAmount)
                    canvas.drawText(amtLabel, qrBoxLeft + 145f, qrTextY, Paint(qrSubPaint).apply { color = headerColorInt; typeface = Typeface.DEFAULT_BOLD })

                    y += 150f
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        y += 15f
        canvas.drawLine(leftMargin, y, rightMargin, y, linePaint)
        y += 18f

        // 6.5 Khata Interest Policy & Due Day Disclaimer (if credit due exists)
        if (hasCreditDueA4) {
            val disclaimerA4 = KhataInterestCalculator.formatDisclaimer(
                settings = interestSettingsA4,
                customer = null,
                isBengali = isBn,
                dueDateMs = dueDateMsA4,
                dueAmount = totalDue
            )
            if (disclaimerA4.isNotBlank()) {
                val disclaimerPaint = Paint().apply {
                    color = Color.rgb(180, 80, 0)
                    textSize = 8.5f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textAlign = Paint.Align.CENTER
                }
                val cleanedDisclaimer = disclaimerA4.replace("⚠️", "").trim()
                drawCenteredMultiLineText(canvas, cleanedDisclaimer, pageWidth / 2f, y, disclaimerPaint, (rightMargin - leftMargin) - 40f, 11f)
                y += 18f
            }
        }

        // 7. Footer Note
        val footerPaint = Paint().apply {
            color = Color.rgb(100, 100, 100)
            textSize = 9.5f
            textAlign = Paint.Align.CENTER
        }

        val footerText = BengaliReceiptTranslator.getFooterGreeting(isBn, StoreInfoManager.customFooterNote)
        canvas.drawText(footerText, pageWidth / 2f, y, footerPaint)
        y += 14f
        val softwareTag = if (isBn) "সফটওয়্যার পরিচালনায় AI Studio POS" else "Software Powered by AI Studio POS"
        canvas.drawText(softwareTag, pageWidth / 2f, y, Paint(footerPaint).apply { textSize = 8.5f })

        pdfDocument.finishPage(page)

        val outputFile = File(context.cacheDir, "Receipt_${sale.id}.pdf")
        if (outputFile.exists()) outputFile.delete()

        FileOutputStream(outputFile).use { out ->
            pdfDocument.writeTo(out)
        }
        pdfDocument.close()

        return outputFile
    }

    private fun generateThermal80mmReceipt(
        context: Context,
        saleWithItems: SaleWithItems,
        isBengali: Boolean = StoreInfoManager.isBillBengali()
    ): File {
        val pdfDocument = PdfDocument()

        // 80mm = ~226 points width
        val pageWidth = 226
        val leftMargin = 12f
        val rightMargin = pageWidth - 12f
        val contentWidth = rightMargin - leftMargin
        val isBn = isBengali
        val sale = saleWithItems.sale

        val titlePaint = Paint().apply {
            color = Color.BLACK
            textSize = 13.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        val subPaint = Paint().apply {
            color = Color.BLACK
            textSize = 8.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }

        val subBoldPaint = Paint().apply {
            color = Color.BLACK
            textSize = 8.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        val textPaint = Paint().apply {
            color = Color.BLACK
            textSize = 8.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }

        val textBoldPaint = Paint().apply {
            color = Color.BLACK
            textSize = 9.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val textRightPaint = Paint().apply {
            color = Color.BLACK
            textSize = 8.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textAlign = Paint.Align.RIGHT
        }

        val textRightBoldPaint = Paint().apply {
            color = Color.BLACK
            textSize = 9.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }

        val totalDueLabelPaint = Paint().apply {
            color = Color.BLACK
            textSize = 11.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val totalDueValuePaint = Paint().apply {
            color = Color.BLACK
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }

        val linePaint = Paint().apply {
            color = Color.BLACK
            strokeWidth = 1.0f
        }

        val doubleLinePaint = Paint().apply {
            color = Color.BLACK
            strokeWidth = 1.6f
        }

        val excessPaid80 = (sale.receivedAmount - sale.finalAmount).coerceAtLeast(0.0)
        val netRemainingDue80 = (sale.previousBalance + sale.dueAmount - excessPaid80).coerceAtLeast(0.0)
        val advanceCredit80 = (excessPaid80 - (sale.previousBalance + sale.dueAmount)).coerceAtLeast(0.0)
        val hasCustomer80 = !sale.customerName.isNullOrBlank() || !sale.customerId.isNullOrBlank()

        val totalDue = if (excessPaid80 > 0 && sale.previousBalance > 0) netRemainingDue80 else (sale.previousBalance + sale.dueAmount)
        val paymentModeUpper = sale.paymentMode.uppercase()
        val shouldShowQr = StoreInfoManager.showQrOnPdf &&
                StoreInfoManager.merchantUpiId.isNotBlank() &&
                (totalDue > 0.0 || paymentModeUpper.contains("UPI"))

        val interestSettings80 = StoreInfoManager.getKhataInterestSettings()
        val hasCreditOrDue80 = totalDue > 0.0 || sale.dueAmount > 0.0 || sale.previousBalance > 0 || paymentModeUpper.contains("CREDIT")
        val dueDateMs80 = KhataInterestCalculator.calculateDueDateTimestamp(sale.datetime, sale.dueDate, interestSettings80.gracePeriodDays)
        val dueDateStr80 = KhataInterestCalculator.formatDueDate(dueDateMs80, interestSettings80.gracePeriodDays, isBn)

        // Process items with clean Bengali translation and multi-line wrapping
        val processedItems80 = saleWithItems.items.map { item ->
            val rawName = if (isBn) {
                if (item.productNameBn.isNotBlank() && BengaliReceiptTranslator.containsBengali(item.productNameBn)) {
                    item.productNameBn
                } else {
                    BengaliReceiptTranslator.translateItem(item.productNameEn.ifBlank { item.productNameBn })
                }
            } else {
                item.productNameEn.ifBlank { item.productNameBn }.ifBlank { "Item" }
            }
            val cleaned = BengaliReceiptTranslator.cleanReceiptProductName(rawName)
            val lines = splitTextIntoLines(cleaned, textPaint, 90f)
            val qtyStr = formatReceiptQtyWithUnit(item.quantity, item.unitType, isBn)
            val subtotalStr = "₹%.2f".format(item.subtotal)
            val discountText = if (item.hasDiscount()) {
                BengaliReceiptTranslator.formatDiscountItemLine(item.getEffectiveMrp(), item.unitPrice, item.quantity, isBn)
            } else null
            ProcessedReceiptItem(lines, qtyStr, subtotalStr, discountText)
        }

        // Dynamic height calculation
        val totalItemLines = processedItems80.sumOf { it.lines.size }
        val totalDiscountItemLines80 = processedItems80.count { it.discountText != null }
        val totalMrpSavings80 = saleWithItems.items.sumOf { it.getSavingsAmount() }
        val overallSavings80 = totalMrpSavings80 + sale.discount
        var estimatedHeight = 180 + (totalItemLines * 13) + (totalDiscountItemLines80 * 10) + (saleWithItems.items.size * 3)
        if (StoreInfoManager.storeAddress.isNotBlank()) estimatedHeight += 14
        if (StoreInfoManager.phone.isNotBlank()) estimatedHeight += 12
        if (StoreInfoManager.gstin.isNotBlank()) estimatedHeight += 12
        if (!sale.customerName.isNullOrBlank()) estimatedHeight += 18
        if (!sale.staffName.isNullOrBlank()) estimatedHeight += 18
        if (sale.discount > 0) estimatedHeight += 24
        if (overallSavings80 > 0.0) estimatedHeight += 14
        if (totalDue > 0.0 || excessPaid80 > 0 || sale.previousBalance > 0) estimatedHeight += 65
        if (shouldShowQr) estimatedHeight += 130
        if (hasCreditOrDue80) estimatedHeight += 35
        estimatedHeight += 45 // Footer and margin
        val pageHeight = estimatedHeight.coerceAtLeast(280)

        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        val page = pdfDocument.startPage(pageInfo)
        val canvas: Canvas = page.canvas

        var y = 14f

        // 1. HEADER SECTION (Store Name, Address, Phone, GSTIN)
        // Store Title
        val storeDisplayName = StoreInfoManager.getStoreDisplayName(isBn)
        val storeDisplayAddress = StoreInfoManager.getStoreDisplayAddress(isBn)
        y = drawCenteredMultiLineText(canvas, storeDisplayName.uppercase(), pageWidth / 2f, y, titlePaint, contentWidth, 14f)
        y += 2f

        if (storeDisplayAddress.isNotBlank()) {
            y = drawCenteredMultiLineText(canvas, storeDisplayAddress, pageWidth / 2f, y, subPaint, contentWidth, 10f)
            y += 2f
        }

        if (StoreInfoManager.phone.isNotBlank()) {
            val phoneLabel = if (isBn) "ফোন: ${StoreInfoManager.phone}" else "Ph: ${StoreInfoManager.phone}"
            canvas.drawText(phoneLabel, pageWidth / 2f, y, subPaint)
            y += 10f
        }

        if (StoreInfoManager.gstin.isNotBlank()) {
            canvas.drawText("GSTIN: ${StoreInfoManager.gstin}", pageWidth / 2f, y, subPaint)
            y += 10f
        }

        // Section 1 Divider
        canvas.drawLine(leftMargin, y, rightMargin, y, linePaint)
        y += 11f

        // 2. BILL INFO SECTION
        val dateFormat = SimpleDateFormat("dd/MM/yy hh:mm a", Locale.getDefault())
        val dateStr = dateFormat.format(Date(sale.datetime))

        val billNoLabel = if (isBn) "বিল নং:" else "Bill:"
        canvas.drawText("$billNoLabel #${sale.id.takeLast(6).uppercase()}", leftMargin, y, textBoldPaint)
        canvas.drawText(dateStr, rightMargin, y, textRightPaint)
        y += 11f

        val payModeDisplay = BengaliReceiptTranslator.translatePaymentMode(sale.paymentMode, isBn)
        canvas.drawText("${if (isBn) "পেমেন্ট মাধ্যম:" else "Payment:"} $payModeDisplay", leftMargin, y, textPaint)
        y += 11f

        val custName = BengaliReceiptTranslator.translateCustomerName(sale.customerName, isBn)
        y = drawLeftMultiLineText(canvas, "${if (isBn) "গ্রাহক:" else "Cust:"} $custName", leftMargin, y, textPaint, contentWidth, 11f)

        if (!sale.staffName.isNullOrBlank()) {
            val staffDisplay = BengaliReceiptTranslator.translateStaffName(sale.staffName, isBn)
            y = drawLeftMultiLineText(canvas, "${if (isBn) "কর্মী:" else "Staff:"} $staffDisplay", leftMargin, y, textPaint, contentWidth, 11f)
        }

        // Section 2 Divider
        canvas.drawLine(leftMargin, y, rightMargin, y, linePaint)
        y += 11f

        // 3. ITEMS TABLE SECTION
        canvas.drawText(if (isBn) "সামগ্রী" else "ITEM", leftMargin, y, textBoldPaint)
        canvas.drawText(if (isBn) "পরিমাণ" else "QTY", leftMargin + 95f, y, textBoldPaint)
        canvas.drawText(if (isBn) "মূল্য(₹)" else "AMT(₹)", rightMargin, y, textRightBoldPaint)
        y += 9f

        canvas.drawLine(leftMargin, y, rightMargin, y, linePaint)
        y += 11f

        processedItems80.forEach { itemRow ->
            canvas.drawText(itemRow.lines[0], leftMargin, y, textPaint)
            canvas.drawText(itemRow.qtyStr, leftMargin + 95f, y, textPaint)
            canvas.drawText(itemRow.subtotalStr, rightMargin, y, textRightPaint)
            y += 11f
            for (i in 1 until itemRow.lines.size) {
                canvas.drawText(itemRow.lines[i], leftMargin, y, textPaint)
                y += 10f
            }
            if (itemRow.discountText != null) {
                val discountItemPaint80 = Paint().apply {
                    color = Color.rgb(46, 125, 50)
                    textSize = 7.5f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                }
                canvas.drawText("${itemRow.discountText}", leftMargin + 4f, y, discountItemPaint80)
                y += 9f
            }
            y += 2f
        }

        // Section 3 Divider
        canvas.drawLine(leftMargin, y, rightMargin, y, linePaint)
        y += 11f

        // 4. TOTALS & CREDIT SUMMARY SECTION
        val summaryLabelX = leftMargin + 50f

        if (sale.discount > 0) {
            val discPct = if (sale.totalAmount > 0) (sale.discount / sale.totalAmount) * 100.0 else 0.0
            canvas.drawText(if (isBn) "উপমোট:" else "Subtotal:", summaryLabelX, y, textPaint)
            canvas.drawText("₹%.2f".format(sale.totalAmount), rightMargin, y, textRightPaint)
            y += 11f

            val discLabel = if (isBn) "ছাড় (${"%.1f".format(discPct)}%):" else "Discount (${"%.1f".format(discPct)}%):"
            canvas.drawText(discLabel, summaryLabelX, y, textPaint)
            canvas.drawText("-₹%.2f".format(sale.discount), rightMargin, y, textRightPaint)
            y += 11f
        }

        if (overallSavings80 > 0.0) {
            val overallPct80 = if ((sale.totalAmount + totalMrpSavings80) > 0) (overallSavings80 / (sale.totalAmount + totalMrpSavings80)) * 100.0 else 0.0
            val savPaint80 = Paint().apply {
                color = Color.rgb(46, 125, 50)
                textSize = 9.5f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val savRightPaint80 = Paint(savPaint80).apply { textAlign = Paint.Align.RIGHT }
            val savLabel = if (isBn) "মোট সাশ্রয় (${"%.0f".format(overallPct80)}%):" else "Total Savings (${"%.0f".format(overallPct80)}%):"
            canvas.drawText(savLabel, summaryLabelX, y, savPaint80)
            canvas.drawText("₹%.2f".format(overallSavings80), rightMargin, y, savRightPaint80)
            y += 11f
        }

        canvas.drawText(if (isBn) "সর্বমোট:" else "TOTAL:", summaryLabelX, y, textBoldPaint)
        canvas.drawText("₹%.2f".format(sale.finalAmount), rightMargin, y, textRightBoldPaint)
        y += 12f

        canvas.drawText("${if (isBn) "পরিশোধিত" else "Paid"} ($payModeDisplay):", summaryLabelX, y, textPaint)
        canvas.drawText("₹%.2f".format(sale.receivedAmount), rightMargin, y, textRightPaint)
        y += 11f

        if (sale.dueAmount > 0) {
            canvas.drawText(if (isBn) "আজকের বাকি:" else "Added Credit:", summaryLabelX, y, textPaint)
            canvas.drawText("₹%.2f".format(sale.dueAmount), rightMargin, y, textRightPaint)
            y += 11f
        }

        if (excessPaid80 > 0 && sale.previousBalance > 0) {
            val dueCleared = minOf(sale.previousBalance, excessPaid80)
            canvas.drawText(if (isBn) "পূর্বের বাকি শোধ:" else "Paid Towards Prev Due:", summaryLabelX, y, textPaint)
            canvas.drawText("₹%.2f".format(dueCleared), rightMargin, y, textRightPaint)
            y += 11f

            canvas.drawText(if (isBn) "পূর্বের বকেয়া:" else "Prev Due Balance:", summaryLabelX, y, textPaint)
            canvas.drawText("₹%.2f".format(sale.previousBalance), rightMargin, y, textRightPaint)
            y += 11f

            // PROMINENT TOTAL DUE NOW
            y += 4f
            canvas.drawLine(leftMargin, y, rightMargin, y, doubleLinePaint)
            y += 13f
            canvas.drawText(if (isBn) "বর্তমান মোট বকেয়া:" else "TOTAL DUE NOW:", leftMargin, y, totalDueLabelPaint)
            canvas.drawText("₹%.2f".format(netRemainingDue80), rightMargin, y, totalDueValuePaint)
            y += 5f
            canvas.drawLine(leftMargin, y, rightMargin, y, doubleLinePaint)
            y += 11f

            if (advanceCredit80 > 0) {
                canvas.drawText(if (isBn) "অগ্রিম জমা ব্যালেন্স:" else "Advance Credit:", summaryLabelX, y, textPaint)
                canvas.drawText("₹%.2f".format(advanceCredit80), rightMargin, y, textRightPaint)
                y += 11f
            }
        } else if (excessPaid80 > 0) {
            if (hasCustomer80) {
                canvas.drawText(if (isBn) "অগ্রিম জমা ব্যালেন্স:" else "Advance Credit:", summaryLabelX, y, textPaint)
                canvas.drawText("₹%.2f".format(excessPaid80), rightMargin, y, textRightPaint)
                y += 11f
            } else {
                canvas.drawText(if (isBn) "ফেরত টাকা:" else "Change Returned:", summaryLabelX, y, textPaint)
                canvas.drawText("₹%.2f".format(excessPaid80), rightMargin, y, textRightPaint)
                y += 11f
            }
        } else if (sale.previousBalance > 0 || sale.dueAmount > 0) {
            if (sale.previousBalance > 0) {
                canvas.drawText(if (isBn) "পূর্বের বকেয়া:" else "Prev Due Balance:", summaryLabelX, y, textPaint)
                canvas.drawText("₹%.2f".format(sale.previousBalance), rightMargin, y, textRightPaint)
                y += 11f
            }

            val dueTotal = sale.previousBalance + sale.dueAmount
            // PROMINENT TOTAL DUE NOW
            y += 4f
            canvas.drawLine(leftMargin, y, rightMargin, y, doubleLinePaint)
            y += 13f
            canvas.drawText(if (isBn) "বর্তমান মোট বকেয়া:" else "TOTAL DUE NOW:", leftMargin, y, totalDueLabelPaint)
            canvas.drawText("₹%.2f".format(dueTotal), rightMargin, y, totalDueValuePaint)
            y += 5f
            canvas.drawLine(leftMargin, y, rightMargin, y, doubleLinePaint)
            y += 11f
        }

        // Display Due Date line under totals if there is an active due
        if (hasCreditOrDue80) {
            val dueDatePaint80 = Paint(textBoldPaint).apply {
                color = Color.rgb(194, 65, 12) // Dark orange
                textSize = 9.0f
                textAlign = Paint.Align.CENTER
            }
            val duePrefix = if (isBn) "পরিশোধের শেষ তারিখ: " else "Due Date: "
            canvas.drawText("$duePrefix$dueDateStr80", pageWidth / 2f, y, dueDatePaint80)
            y += 12f
        }

        // Section 4 Divider
        canvas.drawLine(leftMargin, y, rightMargin, y, linePaint)
        y += 11f

        // 5. MERCHANT QR CODE SECTION
        if (shouldShowQr) {
            val qrAmount = if (totalDue > 0.0) totalDue else sale.finalAmount
            val qrNote = if (totalDue > 0.0) "Due Payment Bill #${sale.id.takeLast(6)}" else "Bill #${sale.id.takeLast(6)}"
            try {
                val upiUrl = buildUpiString(
                    upiId = StoreInfoManager.merchantUpiId,
                    payeeName = StoreInfoManager.merchantPayeeName,
                    amount = qrAmount,
                    note = qrNote
                )
                val qrBitmap = generateQrCodeBitmap(upiUrl, 90, 90)
                if (qrBitmap != null) {
                    val qrX = (pageWidth - 90) / 2f
                    canvas.drawBitmap(qrBitmap, qrX, y, null)
                    y += 95f
                    val scanText = if (totalDue > 0.0) {
                        if (isBn) "বকেয়া পরিশোধের জন্য স্ক্যান করুন (₹%.2f)".format(qrAmount) else "Scan to Pay Due (₹%.2f)".format(qrAmount)
                    } else {
                        if (isBn) "ইউপিআই পেমেন্টের জন্য স্ক্যান করুন" else "Scan to Pay via UPI"
                    }
                    canvas.drawText(scanText, pageWidth / 2f, y, subBoldPaint)
                    y += 10f
                    canvas.drawText("UPI: ${StoreInfoManager.merchantUpiId}", pageWidth / 2f, y, subBoldPaint)
                    y += 11f
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Section 5 Divider
            canvas.drawLine(leftMargin, y, rightMargin, y, linePaint)
            y += 11f
        }

        // 5.5 KHATA INTEREST POLICY & DUE DAY DISCLAIMER
        if (hasCreditOrDue80) {
            val disclaimerText80 = KhataInterestCalculator.formatDisclaimer(
                settings = interestSettings80,
                customer = null,
                isBengali = isBn,
                dueDateMs = dueDateMs80,
                dueAmount = totalDue
            )
            if (disclaimerText80.isNotBlank()) {
                val discPaint80 = Paint(subPaint).apply {
                    textSize = 8.0f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    color = Color.rgb(180, 80, 0)
                }
                val cleanedDisclaimer80 = disclaimerText80.replace("⚠️", "").trim()
                y = drawCenteredMultiLineText(canvas, cleanedDisclaimer80, pageWidth / 2f, y, discPaint80, contentWidth, 10f)
                y += 4f
                canvas.drawLine(leftMargin, y, rightMargin, y, linePaint)
                y += 9f
            }
        }

        // 6. FOOTER NOTE SECTION
        val footerText = BengaliReceiptTranslator.getFooterGreeting(isBn, StoreInfoManager.customFooterNote)
        drawCenteredMultiLineText(canvas, footerText, pageWidth / 2f, y, subPaint, contentWidth, 10f)

        pdfDocument.finishPage(page)

        val outputFile = File(context.cacheDir, "Thermal_Receipt_${sale.id}.pdf")
        if (outputFile.exists()) outputFile.delete()

        FileOutputStream(outputFile).use { out ->
            pdfDocument.writeTo(out)
        }
        pdfDocument.close()

        return outputFile
    }

    private fun formatReceiptQtyWithUnit(qty: Double, unitType: String, isBengali: Boolean): String {
        val cleanUnit = unitType.trim()
        val isGram = com.example.data.local.entities.Product.isGramUnit(cleanUnit)
        val isKg = com.example.data.local.entities.Product.isKgUnit(cleanUnit)
        val isPcs = cleanUnit.equals("pcs", ignoreCase = true) || cleanUnit.equals("piece", ignoreCase = true) || cleanUnit.equals("pieces", ignoreCase = true) || cleanUnit.contains("পিস", ignoreCase = true)
        val isLitre = com.example.data.local.entities.Product.isLitreUnit(cleanUnit)
        val isMl = com.example.data.local.entities.Product.isMlUnit(cleanUnit)
        val isBox = cleanUnit.equals("box", ignoreCase = true) || cleanUnit.equals("boxes", ignoreCase = true) || cleanUnit.contains("বক্স", ignoreCase = true) || cleanUnit.contains("বাক্স", ignoreCase = true)
        val isPacket = cleanUnit.equals("packet", ignoreCase = true) || cleanUnit.equals("pkt", ignoreCase = true) || cleanUnit.contains("প্যাকেট", ignoreCase = true)

        val rawQty = if (isGram || isMl) {
            "${qty.toInt()}"
        } else if (qty % 1.0 == 0.0) {
            "${qty.toInt()}"
        } else {
            "%.2f".format(Locale.US, qty)
        }

        val displayUnit = if (isBengali) {
            when {
                isGram -> "গ্রাম"
                isKg -> "কেজি"
                isPcs -> "পিস"
                isLitre -> "লিটার"
                isMl -> "মিলি"
                isBox -> "বক্স"
                isPacket -> "প্যাকেট"
                cleanUnit.isNotBlank() -> cleanUnit
                else -> "পিস"
            }
        } else {
            when {
                isGram -> "g"
                isKg -> "kg"
                isPcs -> "pcs"
                isLitre -> "L"
                isMl -> "ml"
                isBox -> if (qty == 1.0) "box" else "boxes"
                isPacket -> if (qty == 1.0) "pkt" else "pkts"
                cleanUnit.isNotBlank() -> cleanUnit
                else -> "pcs"
            }
        }

        return "$rawQty $displayUnit"
    }

    private fun splitTextIntoLines(text: String, paint: Paint, maxLineWidth: Float): List<String> {
        if (text.isBlank()) return listOf("")
        if (paint.measureText(text) <= maxLineWidth) return listOf(text)

        val lines = mutableListOf<String>()
        val words = text.split(" ")
        var currentLine = ""

        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            if (paint.measureText(testLine) > maxLineWidth && currentLine.isNotEmpty()) {
                lines.add(currentLine)
                currentLine = word
            } else {
                currentLine = testLine
            }
        }
        if (currentLine.isNotEmpty()) {
            lines.add(currentLine)
        }
        return if (lines.isEmpty()) listOf(text) else lines
    }

    private fun drawLeftMultiLineText(
        canvas: Canvas,
        text: String,
        x: Float,
        startY: Float,
        paint: Paint,
        maxLineWidth: Float,
        lineSpacing: Float = 9f
    ): Float {
        if (text.isBlank()) return startY
        var curY = startY
        val words = text.split(" ")
        var currentLine = ""
        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            if (paint.measureText(testLine) > maxLineWidth && currentLine.isNotEmpty()) {
                canvas.drawText(currentLine, x, curY, paint)
                curY += lineSpacing
                currentLine = word
            } else {
                currentLine = testLine
            }
        }
        if (currentLine.isNotEmpty()) {
            canvas.drawText(currentLine, x, curY, paint)
            curY += lineSpacing
        }
        return curY
    }

    private fun drawCenteredMultiLineText(
        canvas: Canvas,
        text: String,
        centerX: Float,
        startY: Float,
        paint: Paint,
        maxLineWidth: Float,
        lineSpacing: Float = 9f
    ): Float {
        if (text.isBlank()) return startY
        var curY = startY
        val words = text.split(" ")
        var currentLine = ""
        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            if (paint.measureText(testLine) > maxLineWidth && currentLine.isNotEmpty()) {
                canvas.drawText(currentLine, centerX, curY, paint)
                curY += lineSpacing
                currentLine = word
            } else {
                currentLine = testLine
            }
        }
        if (currentLine.isNotEmpty()) {
            canvas.drawText(currentLine, centerX, curY, paint)
            curY += lineSpacing
        }
        return curY
    }

    private fun generateThermal58mmReceipt(
        context: Context,
        saleWithItems: SaleWithItems,
        isBengali: Boolean = StoreInfoManager.isBillBengali()
    ): File {
        val pdfDocument = PdfDocument()

        val pageWidth = 164
        val leftMargin = 7f
        val rightMargin = pageWidth - 7f
        val contentWidth = rightMargin - leftMargin
        val isBn = isBengali
        val sale = saleWithItems.sale

        val titlePaint = Paint().apply {
            color = Color.BLACK
            textSize = 11.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        val subPaint = Paint().apply {
            color = Color.BLACK
            textSize = 7.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }

        val subBoldPaint = Paint().apply {
            color = Color.BLACK
            textSize = 7.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        val textPaint = Paint().apply {
            color = Color.BLACK
            textSize = 7.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }

        val textBoldPaint = Paint().apply {
            color = Color.BLACK
            textSize = 8f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val textRightPaint = Paint().apply {
            color = Color.BLACK
            textSize = 7.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textAlign = Paint.Align.RIGHT
        }

        val textRightBoldPaint = Paint().apply {
            color = Color.BLACK
            textSize = 8f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }

        val totalDueLabelPaint = Paint().apply {
            color = Color.BLACK
            textSize = 10f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val totalDueValuePaint = Paint().apply {
            color = Color.BLACK
            textSize = 10.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }

        val linePaint = Paint().apply {
            color = Color.BLACK
            strokeWidth = 0.8f
        }

        val doubleLinePaint = Paint().apply {
            color = Color.BLACK
            strokeWidth = 1.3f
        }

        val excessPaid58 = (sale.receivedAmount - sale.finalAmount).coerceAtLeast(0.0)
        val netRemainingDue58 = (sale.previousBalance + sale.dueAmount - excessPaid58).coerceAtLeast(0.0)
        val advanceCredit58 = (excessPaid58 - (sale.previousBalance + sale.dueAmount)).coerceAtLeast(0.0)
        val hasCustomer58 = !sale.customerName.isNullOrBlank() || !sale.customerId.isNullOrBlank()

        val totalDue = if (excessPaid58 > 0 && sale.previousBalance > 0) netRemainingDue58 else (sale.previousBalance + sale.dueAmount)
        val paymentModeUpper = sale.paymentMode.uppercase()
        val shouldShowQr = StoreInfoManager.showQrOnPdf &&
                StoreInfoManager.merchantUpiId.isNotBlank() &&
                (totalDue > 0.0 || paymentModeUpper.contains("UPI"))

        val interestSettings58 = StoreInfoManager.getKhataInterestSettings()
        val hasCreditOrDue58 = totalDue > 0.0 || sale.dueAmount > 0.0 || sale.previousBalance > 0 || paymentModeUpper.contains("CREDIT")
        val dueDateMs58 = KhataInterestCalculator.calculateDueDateTimestamp(sale.datetime, sale.dueDate, interestSettings58.gracePeriodDays)
        val dueDateStr58 = KhataInterestCalculator.formatDueDate(dueDateMs58, interestSettings58.gracePeriodDays, isBn)

        // Process items with clean Bengali translation and multi-line wrapping
        val processedItems58 = saleWithItems.items.map { item ->
            val rawName = if (isBn) {
                if (item.productNameBn.isNotBlank() && BengaliReceiptTranslator.containsBengali(item.productNameBn)) {
                    item.productNameBn
                } else {
                    BengaliReceiptTranslator.translateItem(item.productNameEn.ifBlank { item.productNameBn })
                }
            } else {
                item.productNameEn.ifBlank { item.productNameBn }.ifBlank { "Item" }
            }
            val cleaned = BengaliReceiptTranslator.cleanReceiptProductName(rawName)
            val lines = splitTextIntoLines(cleaned, textPaint, 58f)
            val qtyStr = formatReceiptQtyWithUnit(item.quantity, item.unitType, isBn)
            val subtotalStr = "₹%.2f".format(item.subtotal)
            val discountText = if (item.hasDiscount()) {
                BengaliReceiptTranslator.formatDiscountItemLine(item.getEffectiveMrp(), item.unitPrice, item.quantity, isBn)
            } else null
            ProcessedReceiptItem(lines, qtyStr, subtotalStr, discountText)
        }

        // Dynamic height estimation
        val totalItemLines58 = processedItems58.sumOf { it.lines.size }
        val totalDiscountLines58 = processedItems58.count { it.discountText != null }
        val totalMrpSavings58 = saleWithItems.items.sumOf { it.getSavingsAmount() }
        val overallSavings58 = totalMrpSavings58 + sale.discount
        var estimatedHeight58 = 160 + (totalItemLines58 * 12) + (totalDiscountLines58 * 9) + (saleWithItems.items.size * 2)
        if (StoreInfoManager.storeAddress.isNotBlank()) estimatedHeight58 += 12
        if (StoreInfoManager.phone.isNotBlank()) estimatedHeight58 += 10
        if (StoreInfoManager.gstin.isNotBlank()) estimatedHeight58 += 10
        if (!sale.customerName.isNullOrBlank()) estimatedHeight58 += 16
        if (!sale.staffName.isNullOrBlank()) estimatedHeight58 += 16
        if (sale.discount > 0) estimatedHeight58 += 20
        if (overallSavings58 > 0.0) estimatedHeight58 += 12
        if (totalDue > 0.0 || excessPaid58 > 0 || sale.previousBalance > 0) estimatedHeight58 += 60
        if (shouldShowQr) estimatedHeight58 += 115
        if (hasCreditOrDue58) estimatedHeight58 += 35
        estimatedHeight58 += 45 // Footer and padding
        val pageHeight = estimatedHeight58.coerceAtLeast(260)

        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        val page = pdfDocument.startPage(pageInfo)
        val canvas: Canvas = page.canvas

        var y = 12f

        // 1. HEADER SECTION (Store Name, Address, Phone, GSTIN)
        // Store Name
        val storeDisplayName = StoreInfoManager.getStoreDisplayName(isBn)
        val storeDisplayAddress = StoreInfoManager.getStoreDisplayAddress(isBn)
        y = drawCenteredMultiLineText(canvas, storeDisplayName.uppercase(), pageWidth / 2f, y, titlePaint, contentWidth, 12f)
        y += 1f

        // Store Address
        if (storeDisplayAddress.isNotBlank()) {
            y = drawCenteredMultiLineText(canvas, storeDisplayAddress, pageWidth / 2f, y, subPaint, contentWidth, 9f)
            y += 1f
        }

        // Phone
        if (StoreInfoManager.phone.isNotBlank()) {
            val phoneLabel = if (isBn) "ফোন: ${StoreInfoManager.phone}" else "Ph: ${StoreInfoManager.phone}"
            canvas.drawText(phoneLabel, pageWidth / 2f, y, subPaint)
            y += 9f
        }

        // GSTIN
        if (StoreInfoManager.gstin.isNotBlank()) {
            canvas.drawText("GSTIN: ${StoreInfoManager.gstin}", pageWidth / 2f, y, subPaint)
            y += 9f
        }

        // Section 1 Divider
        canvas.drawLine(leftMargin, y, rightMargin, y, linePaint)
        y += 9f

        // 2. BILL INFO SECTION (Bill No, Date, Payment Mode, Customer, Staff)
        val dateFormat = SimpleDateFormat("dd/MM/yy hh:mm a", Locale.getDefault())
        val dateStr = dateFormat.format(Date(sale.datetime))

        val billNoLabel = if (isBn) "বিল নং:" else "Bill:"
        canvas.drawText("$billNoLabel #${sale.id.takeLast(6).uppercase()}", leftMargin, y, textBoldPaint)
        canvas.drawText(dateStr, rightMargin, y, textRightPaint)
        y += 9f

        val payModeDisplay = BengaliReceiptTranslator.translatePaymentMode(sale.paymentMode, isBn)
        canvas.drawText("${if (isBn) "পেমেন্ট মাধ্যম:" else "Payment:"} $payModeDisplay", leftMargin, y, textPaint)
        y += 9f

        // Customer Name (Wraps to multi-lines cleanly without truncation)
        val custName = BengaliReceiptTranslator.translateCustomerName(sale.customerName, isBn)
        y = drawLeftMultiLineText(canvas, "${if (isBn) "গ্রাহক:" else "Cust:"} $custName", leftMargin, y, textPaint, contentWidth, 9f)

        // Staff Name (Wraps to multi-lines cleanly without truncation)
        if (!sale.staffName.isNullOrBlank()) {
            val staffDisplay = BengaliReceiptTranslator.translateStaffName(sale.staffName, isBn)
            y = drawLeftMultiLineText(canvas, "${if (isBn) "কর্মী:" else "Staff:"} $staffDisplay", leftMargin, y, textPaint, contentWidth, 9f)
        }

        // Section 2 Divider
        canvas.drawLine(leftMargin, y, rightMargin, y, linePaint)
        y += 9f

        // 3. ITEMS TABLE SECTION
        canvas.drawText(if (isBn) "সামগ্রী" else "ITEM", leftMargin, y, textBoldPaint)
        canvas.drawText(if (isBn) "পরিমাণ" else "QTY", leftMargin + 62f, y, textBoldPaint)
        canvas.drawText(if (isBn) "মূল্য(₹)" else "AMT(₹)", rightMargin, y, textRightBoldPaint)
        y += 8f

        canvas.drawLine(leftMargin, y, rightMargin, y, linePaint)
        y += 9f

        processedItems58.forEach { itemRow ->
            canvas.drawText(itemRow.lines[0], leftMargin, y, textPaint)
            canvas.drawText(itemRow.qtyStr, leftMargin + 62f, y, textPaint)
            canvas.drawText(itemRow.subtotalStr, rightMargin, y, textRightPaint)
            y += 9f
            for (i in 1 until itemRow.lines.size) {
                canvas.drawText(itemRow.lines[i], leftMargin, y, textPaint)
                y += 9f
            }
            if (itemRow.discountText != null) {
                val discountItemPaint58 = Paint().apply {
                    color = Color.rgb(46, 125, 50)
                    textSize = 6.8f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                }
                canvas.drawText("${itemRow.discountText}", leftMargin + 2f, y, discountItemPaint58)
                y += 8f
            }
            y += 2f
        }

        // Section 3 Divider
        canvas.drawLine(leftMargin, y, rightMargin, y, linePaint)
        y += 9f

        // 4. TOTALS & CREDIT SUMMARY SECTION
        val summaryLabelX = leftMargin + 32f

        if (sale.discount > 0) {
            val discPct = if (sale.totalAmount > 0) (sale.discount / sale.totalAmount) * 100.0 else 0.0
            canvas.drawText(if (isBn) "উপমোট:" else "Subtotal:", summaryLabelX, y, textPaint)
            canvas.drawText("₹%.2f".format(sale.totalAmount), rightMargin, y, textRightPaint)
            y += 9f

            val discLabel = if (isBn) "ছাড় (${"%.1f".format(discPct)}%):" else "Discount (${"%.1f".format(discPct)}%):"
            canvas.drawText(discLabel, summaryLabelX, y, textPaint)
            canvas.drawText("-₹%.2f".format(sale.discount), rightMargin, y, textRightPaint)
            y += 9f
        }

        if (overallSavings58 > 0.0) {
            val overallPct58 = if ((sale.totalAmount + totalMrpSavings58) > 0) (overallSavings58 / (sale.totalAmount + totalMrpSavings58)) * 100.0 else 0.0
            val savPaint58 = Paint().apply {
                color = Color.rgb(46, 125, 50)
                textSize = 8.5f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val savRightPaint58 = Paint(savPaint58).apply { textAlign = Paint.Align.RIGHT }
            val savLabel = if (isBn) "মোট সাশ্রয় (${"%.0f".format(overallPct58)}%):" else "Total Savings (${"%.0f".format(overallPct58)}%):"
            canvas.drawText(savLabel, summaryLabelX, y, savPaint58)
            canvas.drawText("₹%.2f".format(overallSavings58), rightMargin, y, savRightPaint58)
            y += 9f
        }

        canvas.drawText(if (isBn) "সর্বমোট:" else "TOTAL:", summaryLabelX, y, textBoldPaint)
        canvas.drawText("₹%.2f".format(sale.finalAmount), rightMargin, y, textRightBoldPaint)
        y += 10f

        canvas.drawText("${if (isBn) "পরিশোধিত" else "Paid"} ($payModeDisplay):", summaryLabelX, y, textPaint)
        canvas.drawText("₹%.2f".format(sale.receivedAmount), rightMargin, y, textRightPaint)
        y += 9f

        if (sale.dueAmount > 0) {
            canvas.drawText(if (isBn) "আজকের বাকি:" else "Added Credit:", summaryLabelX, y, textPaint)
            canvas.drawText("₹%.2f".format(sale.dueAmount), rightMargin, y, textRightPaint)
            y += 9f
        }

        if (excessPaid58 > 0 && sale.previousBalance > 0) {
            val dueCleared = minOf(sale.previousBalance, excessPaid58)
            canvas.drawText(if (isBn) "পূর্বের বাকি শোধ:" else "Paid to Prev Due:", summaryLabelX, y, textPaint)
            canvas.drawText("₹%.2f".format(dueCleared), rightMargin, y, textRightPaint)
            y += 9f

            canvas.drawText(if (isBn) "পূর্বের বকেয়া:" else "Prev Due Balance:", summaryLabelX, y, textPaint)
            canvas.drawText("₹%.2f".format(sale.previousBalance), rightMargin, y, textRightPaint)
            y += 9f

            // PROMINENT TOTAL DUE NOW
            y += 3f
            canvas.drawLine(leftMargin, y, rightMargin, y, doubleLinePaint)
            y += 11f
            canvas.drawText(if (isBn) "বর্তমান মোট বকেয়া:" else "TOTAL DUE NOW:", leftMargin, y, totalDueLabelPaint)
            canvas.drawText("₹%.2f".format(netRemainingDue58), rightMargin, y, totalDueValuePaint)
            y += 4f
            canvas.drawLine(leftMargin, y, rightMargin, y, doubleLinePaint)
            y += 9f

            if (advanceCredit58 > 0) {
                canvas.drawText(if (isBn) "অগ্রিম জমা ব্যালেন্স:" else "Advance Credit:", summaryLabelX, y, textPaint)
                canvas.drawText("₹%.2f".format(advanceCredit58), rightMargin, y, textRightPaint)
                y += 9f
            }
        } else if (excessPaid58 > 0) {
            if (hasCustomer58) {
                canvas.drawText(if (isBn) "অগ্রিম জমা ব্যালেন্স:" else "Advance Credit:", summaryLabelX, y, textPaint)
                canvas.drawText("₹%.2f".format(excessPaid58), rightMargin, y, textRightPaint)
                y += 9f
            } else {
                canvas.drawText(if (isBn) "ফেরত টাকা:" else "Change Returned:", summaryLabelX, y, textPaint)
                canvas.drawText("₹%.2f".format(excessPaid58), rightMargin, y, textRightPaint)
                y += 9f
            }
        } else if (sale.previousBalance > 0 || sale.dueAmount > 0) {
            if (sale.previousBalance > 0) {
                canvas.drawText(if (isBn) "পূর্বের বকেয়া:" else "Prev Due Balance:", summaryLabelX, y, textPaint)
                canvas.drawText("₹%.2f".format(sale.previousBalance), rightMargin, y, textRightPaint)
                y += 9f
            }

            val dueTotal = sale.previousBalance + sale.dueAmount
            // PROMINENT TOTAL DUE NOW
            y += 3f
            canvas.drawLine(leftMargin, y, rightMargin, y, doubleLinePaint)
            y += 11f
            canvas.drawText(if (isBn) "বর্তমান মোট বকেয়া:" else "TOTAL DUE NOW:", leftMargin, y, totalDueLabelPaint)
            canvas.drawText("₹%.2f".format(dueTotal), rightMargin, y, totalDueValuePaint)
            y += 4f
            canvas.drawLine(leftMargin, y, rightMargin, y, doubleLinePaint)
            y += 9f
        }

        // Display Due Date line under totals if there is an active due
        if (hasCreditOrDue58) {
            val dueDatePaint58 = Paint(textBoldPaint).apply {
                color = Color.rgb(194, 65, 12) // Dark orange
                textSize = 7.5f
                textAlign = Paint.Align.CENTER
            }
            val duePrefix = if (isBn) "পরিশোধের শেষ তারিখ: " else "Due Date: "
            canvas.drawText("$duePrefix$dueDateStr58", pageWidth / 2f, y, dueDatePaint58)
            y += 10f
        }

        // Section 4 Divider
        canvas.drawLine(leftMargin, y, rightMargin, y, linePaint)
        y += 9f

        // 5. MERCHANT QR CODE SECTION
        if (shouldShowQr) {
            val qrAmount = if (totalDue > 0.0) totalDue else sale.finalAmount
            val qrNote = if (totalDue > 0.0) "Due Payment Bill #${sale.id.takeLast(6)}" else "Bill #${sale.id.takeLast(6)}"
            try {
                val upiUrl = buildUpiString(
                    upiId = StoreInfoManager.merchantUpiId,
                    payeeName = StoreInfoManager.merchantPayeeName,
                    amount = qrAmount,
                    note = qrNote
                )
                val qrBitmap = generateQrCodeBitmap(upiUrl, 74, 74)
                if (qrBitmap != null) {
                    val qrX = (pageWidth - 74) / 2f
                    canvas.drawBitmap(qrBitmap, qrX, y, null)
                    y += 78f
                    val scanText = if (totalDue > 0.0) {
                        if (isBn) "বকেয়া পরিশোধের জন্য স্ক্যান করুন (₹%.2f)".format(qrAmount) else "Scan to Pay Due (₹%.2f)".format(qrAmount)
                    } else {
                        if (isBn) "ইউপিআই পেমেন্টের জন্য স্ক্যান করুন" else "Scan to Pay via UPI"
                    }
                    canvas.drawText(scanText, pageWidth / 2f, y, subBoldPaint)
                    y += 9f
                    canvas.drawText("UPI: ${StoreInfoManager.merchantUpiId}", pageWidth / 2f, y, subBoldPaint)
                    y += 9f
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Section 5 Divider
            canvas.drawLine(leftMargin, y, rightMargin, y, linePaint)
            y += 9f
        }

        // 5.5 KHATA INTEREST POLICY & DUE DAY DISCLAIMER
        if (hasCreditOrDue58) {
            val disclaimerText58 = KhataInterestCalculator.formatDisclaimer(
                settings = interestSettings58,
                customer = null,
                isBengali = isBn,
                dueDateMs = dueDateMs58,
                dueAmount = totalDue
            )
            if (disclaimerText58.isNotBlank()) {
                val discPaint58 = Paint(subPaint).apply {
                    textSize = 7.0f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    color = Color.rgb(180, 80, 0)
                }
                val cleanedDisclaimer58 = disclaimerText58.replace("⚠️", "").trim()
                y = drawCenteredMultiLineText(canvas, cleanedDisclaimer58, pageWidth / 2f, y, discPaint58, contentWidth, 9f)
                y += 3f
                canvas.drawLine(leftMargin, y, rightMargin, y, linePaint)
                y += 8f
            }
        }

        // 6. FOOTER NOTE SECTION
        val footerText = BengaliReceiptTranslator.getFooterGreeting(isBn, StoreInfoManager.customFooterNote)
        drawCenteredMultiLineText(canvas, footerText, pageWidth / 2f, y, subPaint, contentWidth, 9f)

        pdfDocument.finishPage(page)

        val outputFile = File(context.cacheDir, "Thermal58_Receipt_${sale.id}.pdf")
        if (outputFile.exists()) outputFile.delete()

        FileOutputStream(outputFile).use { out ->
            pdfDocument.writeTo(out)
        }
        pdfDocument.close()

        return outputFile
    }

    private fun buildUpiString(upiId: String, payeeName: String, amount: Double, note: String): String {
        return StoreInfoManager.buildUpiPayUrl(
            upiId = upiId,
            payeeName = payeeName,
            amount = amount,
            note = note
        )
    }

    fun generateQrCodeBitmap(text: String, width: Int = 180, height: Int = 180): Bitmap? {
        if (text.isBlank()) return null
        return try {
            val bitMatrix: BitMatrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, width, height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    fun generate1DBarcodeBitmap(
        text: String,
        width: Int = 360,
        height: Int = 110,
        preferredModuleWidth: Int? = null
    ): Bitmap? {
        val cleanText = text.trim().replace(" ", "")
        if (cleanText.isBlank()) return null
        val hints = java.util.EnumMap<com.google.zxing.EncodeHintType, Any>(com.google.zxing.EncodeHintType::class.java).apply {
            put(com.google.zxing.EncodeHintType.MARGIN, 0)
        }

        val isAllDigits = cleanText.all { it.isDigit() }
        val candidateFormats = when {
            cleanText.length == 13 && isAllDigits && EscPosPrinter.isValidEan13(cleanText) ->
                listOf(BarcodeFormat.EAN_13, BarcodeFormat.CODE_128)
            cleanText.length == 12 && isAllDigits && EscPosPrinter.isValidUpcA(cleanText) ->
                listOf(BarcodeFormat.UPC_A, BarcodeFormat.CODE_128)
            cleanText.length == 8 && isAllDigits && EscPosPrinter.isValidEan8(cleanText) ->
                listOf(BarcodeFormat.EAN_8, BarcodeFormat.CODE_128)
            else ->
                listOf(BarcodeFormat.CODE_128, BarcodeFormat.CODE_39)
        }

        var bitMatrix: BitMatrix? = null
        for (format in candidateFormats) {
            try {
                // Pass width=0 to extract the raw, unpadded 1-dot-per-module BitMatrix
                bitMatrix = MultiFormatWriter().encode(cleanText, format, 0, 1, hints)
                if (bitMatrix != null && bitMatrix.width > 0) break
            } catch (_: Throwable) {}
        }

        if (bitMatrix == null) {
            try {
                bitMatrix = MultiFormatWriter().encode(cleanText, BarcodeFormat.CODE_128, 0, 1, hints)
            } catch (_: Throwable) {
                return null
            }
        }

        val numModules = bitMatrix.width
        if (numModules <= 0) return null

        val targetHeight = height.coerceAtLeast(16)
        val targetWidth = width.coerceAtLeast(numModules)

        // Calculate module width (dots per module):
        // 203 DPI thermal printheads need at least 2 dots per module to prevent thermal bleed bridging.
        val computedModuleWidth = if (preferredModuleWidth != null && preferredModuleWidth > 0) {
            preferredModuleWidth
        } else {
            val fitWidth = targetWidth / numModules
            if (fitWidth >= 2) fitWidth else 1
        }
        val moduleWidth = computedModuleWidth.coerceAtLeast(1)

        val actualWidth = numModules * moduleWidth
        val bitmap = Bitmap.createBitmap(actualWidth, targetHeight, Bitmap.Config.ARGB_8888)

        for (m in 0 until numModules) {
            val isBlack = bitMatrix.get(m, 0)
            val color = if (isBlack) Color.BLACK else Color.WHITE
            val startX = m * moduleWidth
            for (dx in 0 until moduleWidth) {
                val px = startX + dx
                for (y in 0 until targetHeight) {
                    bitmap.setPixel(px, y, color)
                }
            }
        }

        return bitmap
    }

    fun generateBarcodeLabelsPdf(
        context: Context,
        productName: String,
        barcodeStr: String,
        price: Double,
        mrp: Double,
        quantity: Int,
        quantityOrUnit: String = "1 N",
        sizeOrVariant: String? = null,
        subtitleOrTag: String = "",
        discountPercentage: Int? = null,
        storeName: String = StoreInfoManager.storeName,
        expiryDate: String? = null,
        labelStyle: String = "MODERN"
    ): File? {
        return try {
            val pdfDocument = PdfDocument()
            val cleanStore = storeName.ifBlank { StoreInfoManager.storeName }
            val cleanQty = (sizeOrVariant?.takeIf { it.isNotBlank() } ?: quantityOrUnit).trim().ifBlank { "1 N" }
            val cleanExp = EscPosPrinter.formatExpiryDate(expiryDate)
            val hasExpiry = !cleanExp.isNullOrBlank()

            val pageWidth = 595 // Standard A4 width in points
            val pageHeight = 842 // Standard A4 height in points
            val columns = 3
            val labelWidth = (pageWidth - 40) / columns // ~185 pt
            val labelHeight = 110 // pt
            val marginX = 20f
            val marginY = 30f

            val labelsPerPage = columns * 6 // 18 labels per page
            val totalPages = (quantity + labelsPerPage - 1) / labelsPerPage

            var labelCounter = 0

            for (p in 0 until totalPages) {
                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, p + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                val canvas = page.canvas

                val paint = Paint().apply { isAntiAlias = true }
                val barcodeBmp = generate1DBarcodeBitmap(barcodeStr, 300, 90)

                for (i in 0 until labelsPerPage) {
                    if (labelCounter >= quantity) break

                    val col = i % columns
                    val row = i / columns

                    val left = marginX + (col * labelWidth) + 5f
                    val top = marginY + (row * (labelHeight + 15f))
                    val right = left + labelWidth - 10f
                    val bottom = top + labelHeight

                    // Draw Sticker Border
                    paint.color = Color.BLACK
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 0.8f
                    canvas.drawRoundRect(RectF(left, top, right, bottom), 5f, 5f, paint)

                    val contentLeft = left + 8f
                    val contentRight = right - 8f
                    val centerX = (left + right) / 2f
                    var curY = top + 8f

                    // 1. Store Name Header
                    // 1. Store Name Header (Centered, bold sans-serif, deep navy, no black box)
                    val displayStore = cleanStore.uppercase().take(28)
                    paint.style = Paint.Style.FILL
                    paint.color = Color.rgb(30, 58, 110)
                    paint.textSize = 8.8f
                    paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    paint.textAlign = Paint.Align.CENTER
                    curY += 8.5f
                    canvas.drawText(displayStore, centerX, curY, paint)

                    // 2. Row 1: Item Name (Left) & Tag / Branch (Right)
                    curY += 8.5f
                    paint.color = Color.BLACK
                    paint.textAlign = Paint.Align.LEFT
                    paint.textSize = 7.5f
                    paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

                    val itemPrefix = "Item : "
                    val itemPrefW = paint.measureText(itemPrefix)

                    val displayTag = subtitleOrTag.trim().uppercase()
                    val tagW = if (displayTag.isNotBlank()) paint.measureText(displayTag) else 0f
                    if (displayTag.isNotBlank()) {
                        canvas.drawText(displayTag, contentRight - tagW, curY, paint)
                    }

                    val spaceForProd = if (displayTag.isNotBlank()) {
                        contentRight - contentLeft - itemPrefW - tagW - 6f
                    } else {
                        contentRight - contentLeft - itemPrefW
                    }
                    val prodName = productName.ifBlank { "ITEM" }.uppercase()
                    val displayProd = if (paint.measureText(prodName) > spaceForProd) prodName.take(14) + ".." else prodName
                    canvas.drawText(itemPrefix, contentLeft, curY, paint)
                    canvas.drawText(displayProd, contentLeft + itemPrefW, curY, paint)

                    // 3. Price & Discount Calculations
                    val safePrice = if (price.isNaN() || price.isInfinite() || price < 0.0) 0.0 else price.coerceAtMost(99_999_999.0)
                    val safeMrp = if (mrp.isNaN() || mrp.isInfinite() || mrp < 0.0) 0.0 else mrp.coerceAtMost(99_999_999.0)
                    val effMrp = if (safeMrp > 0.0) safeMrp else if (safePrice > 0.0) safePrice else 0.0
                    val effPrice = if (safePrice > 0.0) safePrice else effMrp
                    val discPct = if (discountPercentage != null && discountPercentage > 0) {
                        discountPercentage.coerceIn(1, 99)
                    } else if (effMrp > effPrice && effMrp > 0.0) {
                        Math.round(((effMrp - effPrice) / effMrp) * 100.0).toInt().coerceIn(1, 99)
                    } else {
                        0
                    }
                    val formattedMrp = if (effMrp % 1.0 == 0.0) effMrp.toLong().toString() else "%.2f".format(effMrp)
                    val formattedPrice = if (effPrice % 1.0 == 0.0) effPrice.toLong().toString() else "%.2f".format(effPrice)
                    val hasDiscount = discPct > 0 && effMrp > effPrice

                    // Row 2: Qty (Left) & Exp (Right)
                    curY += 8.2f
                    val qtyText = "Qty : ${cleanQty.trim()}"
                    canvas.drawText(qtyText, contentLeft, curY, paint)

                    if (!cleanExp.isNullOrBlank()) {
                        val expText = "Exp : $cleanExp"
                        val expW = paint.measureText(expText)
                        canvas.drawText(expText, contentRight - expW, curY, paint)
                    }

                    // Row 3: Offer (Left) & Struck MRP + % OFF (Right)
                    curY += 8.5f
                    if (hasDiscount) {
                        // Left: Offer : <Price>/-
                        canvas.drawText("Offer : $formattedPrice/-", contentLeft, curY, paint)

                        // Right: ~~MRP : <MRP>/-~~  <DISCOUNT>% OFF
                        val mrpText = "MRP : $formattedMrp/-"
                        val discText = "$discPct% OFF"
                        val mrpW = paint.measureText(mrpText)
                        val spaceW = paint.measureText("  ")
                        val discW = paint.measureText(discText)
                        val totalRightW = mrpW + spaceW + discW
                        val rightStartX = contentRight - totalRightW

                        canvas.drawText(mrpText, rightStartX, curY, paint)

                        // Strikethrough line across MRP
                        val strikeY = curY - 2.2f
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = 0.8f
                        canvas.drawLine(rightStartX - 1f, strikeY, rightStartX + mrpW + 1f, strikeY, paint)
                        paint.style = Paint.Style.FILL

                        // Discount text in bold
                        canvas.drawText(discText, rightStartX + mrpW + spaceW, curY, paint)
                    } else {
                        val mrpLine = if (effMrp > 0.0) "MRP : $formattedMrp/-" else if (effPrice > 0.0) "Price : $formattedPrice/-" else "MRP : --"
                        canvas.drawText(mrpLine, contentLeft, curY, paint)
                    }

                    // Barcode Image (No divider line above barcode)
                    if (barcodeBmp != null) {
                        val barcodeW = (right - left - 24f).coerceAtLeast(100f)
                        val barcodeH = 26f
                        val bmpDst = RectF(centerX - (barcodeW / 2f), curY + 3.5f, centerX + (barcodeW / 2f), curY + 3.5f + barcodeH)
                        canvas.drawBitmap(barcodeBmp, null, bmpDst, null)
                        curY += 3.5f + barcodeH
                    }

                    // Barcode Digits (Formatted with groups, e.g. "8509 106 09 0450")
                    curY += 7.5f
                    paint.textAlign = Paint.Align.CENTER
                    paint.textSize = 6.8f
                    paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    val displayDigits = EscPosPrinter.formatBarcodeDisplayDigits(barcodeStr)
                    canvas.drawText(displayDigits, centerX, curY, paint)

                    labelCounter++
                }

                pdfDocument.finishPage(page)
            }

            val file = File(context.cacheDir, "Barcode_Labels_${System.currentTimeMillis()}.pdf")
            val outputStream = FileOutputStream(file)
            pdfDocument.writeTo(outputStream)
            outputStream.close()
            pdfDocument.close()
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    data class MultiVariantBarcodeItem(
        val productName: String,
        val barcodeStr: String,
        val price: Double,
        val mrp: Double,
        val quantityOrUnit: String = "1 N",
        val subtitleOrTag: String = "",
        val discountPercentage: Int? = null
    )

    fun generateMultiVariantBarcodeLabelsPdf(
        context: Context,
        items: List<MultiVariantBarcodeItem>,
        copiesPerItem: Int = 1,
        storeName: String = StoreInfoManager.storeName,
        expiryDate: String? = null,
        labelStyle: String = "MODERN"
    ): File? {
        if (items.isEmpty()) return null
        return try {
            val pdfDocument = PdfDocument()
            val cleanStore = storeName.ifBlank { StoreInfoManager.storeName }
            val cleanExp = EscPosPrinter.formatExpiryDate(expiryDate)

            val flatLabels = mutableListOf<MultiVariantBarcodeItem>()
            for (item in items) {
                repeat(copiesPerItem.coerceIn(1, 100)) {
                    flatLabels.add(item)
                }
            }

            val totalCount = flatLabels.size
            val pageWidth = 595
            val pageHeight = 842
            val columns = 3
            val labelWidth = (pageWidth - 40) / columns
            val labelHeight = 110
            val marginX = 20f
            val marginY = 30f
            val labelsPerPage = columns * 6
            val totalPages = (totalCount + labelsPerPage - 1) / labelsPerPage

            var labelCounter = 0
            val barcodeBmpCache = mutableMapOf<String, Bitmap?>()

            for (p in 0 until totalPages) {
                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, p + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                val canvas = page.canvas
                val paint = Paint().apply { isAntiAlias = true }

                for (i in 0 until labelsPerPage) {
                    if (labelCounter >= totalCount) break
                    val currentItem = flatLabels[labelCounter]

                    val col = i % columns
                    val row = i / columns
                    val left = marginX + (col * labelWidth) + 5f
                    val top = marginY + (row * (labelHeight + 15f))
                    val right = left + labelWidth - 10f
                    val bottom = top + labelHeight

                    // Border
                    paint.color = Color.BLACK
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 0.8f
                    canvas.drawRoundRect(RectF(left, top, right, bottom), 5f, 5f, paint)

                    val contentLeft = left + 8f
                    val contentRight = right - 8f
                    val centerX = (left + right) / 2f
                    var curY = top + 8f

                    // 1. Store Header
                    val displayStore = cleanStore.uppercase().take(28)
                    paint.style = Paint.Style.FILL
                    paint.color = Color.rgb(30, 58, 110)
                    paint.textSize = 8.8f
                    paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    paint.textAlign = Paint.Align.CENTER
                    curY += 8.5f
                    canvas.drawText(displayStore, centerX, curY, paint)

                    // 2. Row 1: Item Name & Tag
                    curY += 8.5f
                    paint.color = Color.BLACK
                    paint.textAlign = Paint.Align.LEFT
                    paint.textSize = 7.5f
                    paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

                    val displayTag = currentItem.subtitleOrTag.trim().uppercase()
                    val tagW = if (displayTag.isNotBlank()) paint.measureText(displayTag) else 0f
                    if (displayTag.isNotBlank()) {
                        canvas.drawText(displayTag, contentRight - tagW, curY, paint)
                    }

                    val spaceForProd = if (displayTag.isNotBlank()) contentRight - tagW - contentLeft - 4f else contentRight - contentLeft
                    var displayProd = "Item : " + currentItem.productName.trim().uppercase()
                    while (paint.measureText(displayProd) > spaceForProd && displayProd.length > 8) {
                        displayProd = displayProd.dropLast(1)
                    }
                    canvas.drawText(displayProd, contentLeft, curY, paint)

                    // 3. Price & Discount Calculations
                    val safePrice = if (currentItem.price.isNaN() || currentItem.price.isInfinite() || currentItem.price < 0.0) 0.0 else currentItem.price.coerceAtMost(99_999_999.0)
                    val safeMrp = if (currentItem.mrp.isNaN() || currentItem.mrp.isInfinite() || currentItem.mrp < 0.0) 0.0 else currentItem.mrp.coerceAtMost(99_999_999.0)
                    val effMrp = if (safeMrp > 0.0) safeMrp else if (safePrice > 0.0) safePrice else 0.0
                    val effPrice = if (safePrice > 0.0) safePrice else effMrp
                    val discPct = if (currentItem.discountPercentage != null && currentItem.discountPercentage > 0) {
                        currentItem.discountPercentage.coerceIn(1, 99)
                    } else if (effMrp > effPrice && effMrp > 0.0) {
                        Math.round(((effMrp - effPrice) / effMrp) * 100.0).toInt().coerceIn(1, 99)
                    } else {
                        0
                    }
                    val formattedMrp = if (effMrp % 1.0 == 0.0) effMrp.toLong().toString() else "%.2f".format(effMrp)
                    val formattedPrice = if (effPrice % 1.0 == 0.0) effPrice.toLong().toString() else "%.2f".format(effPrice)
                    val hasDiscount = discPct > 0 && effMrp > effPrice

                    // Row 2: Qty & Exp
                    curY += 8.2f
                    val qtyText = "Qty : ${currentItem.quantityOrUnit.trim()}"
                    canvas.drawText(qtyText, contentLeft, curY, paint)

                    if (!cleanExp.isNullOrBlank()) {
                        val expText = "Exp : $cleanExp"
                        val expW = paint.measureText(expText)
                        canvas.drawText(expText, contentRight - expW, curY, paint)
                    }

                    // Row 3: Offer & MRP
                    curY += 8.5f
                    if (hasDiscount) {
                        canvas.drawText("Offer : $formattedPrice/-", contentLeft, curY, paint)
                        val mrpText = "MRP : $formattedMrp/-"
                        val discText = "$discPct% OFF"
                        val mrpW = paint.measureText(mrpText)
                        val spaceW = paint.measureText("  ")
                        val discW = paint.measureText(discText)
                        val totalRightW = mrpW + spaceW + discW
                        val rightStartX = contentRight - totalRightW
                        canvas.drawText(mrpText, rightStartX, curY, paint)
                        val strikeY = curY - 2.2f
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = 0.8f
                        canvas.drawLine(rightStartX - 1f, strikeY, rightStartX + mrpW + 1f, strikeY, paint)
                        paint.style = Paint.Style.FILL
                        canvas.drawText(discText, rightStartX + mrpW + spaceW, curY, paint)
                    } else {
                        val mrpLine = if (effMrp > 0.0) "MRP : $formattedMrp/-" else if (effPrice > 0.0) "Price : $formattedPrice/-" else "MRP : --"
                        canvas.drawText(mrpLine, contentLeft, curY, paint)
                    }

                    // Barcode Image
                    val barcodeBmp = barcodeBmpCache.getOrPut(currentItem.barcodeStr) {
                        generate1DBarcodeBitmap(currentItem.barcodeStr, 300, 90)
                    }
                    if (barcodeBmp != null) {
                        val barcodeW = (right - left - 24f).coerceAtLeast(100f)
                        val barcodeH = 26f
                        val bmpDst = RectF(centerX - (barcodeW / 2f), curY + 3.5f, centerX + (barcodeW / 2f), curY + 3.5f + barcodeH)
                        canvas.drawBitmap(barcodeBmp, null, bmpDst, null)
                        curY += 3.5f + barcodeH
                    }

                    // Barcode Digits
                    curY += 7.5f
                    paint.textAlign = Paint.Align.CENTER
                    paint.textSize = 6.8f
                    paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    val displayDigits = EscPosPrinter.formatBarcodeDisplayDigits(currentItem.barcodeStr)
                    canvas.drawText(displayDigits, centerX, curY, paint)

                    labelCounter++
                }
                pdfDocument.finishPage(page)
            }

            val file = File(context.cacheDir, "MultiVariant_Labels_${System.currentTimeMillis()}.pdf")
            val outputStream = FileOutputStream(file)
            pdfDocument.writeTo(outputStream)
            outputStream.close()
            pdfDocument.close()
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun parseColorHex(hex: String, defaultColor: Int): Int {
        return try {
            Color.parseColor(hex)
        } catch (e: Exception) {
            defaultColor
        }
    }

    fun printPdf(context: Context, pdfFile: File, jobName: String = "POS_Receipt_Print") {
        try {
            val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
            val printAdapter = object : PrintDocumentAdapter() {
                override fun onLayout(
                    oldAttributes: PrintAttributes?,
                    newAttributes: PrintAttributes?,
                    cancellationSignal: CancellationSignal?,
                    callback: LayoutResultCallback?,
                    extras: Bundle?
                ) {
                    if (cancellationSignal?.isCanceled == true) {
                        callback?.onLayoutCancelled()
                        return
                    }
                    val info = PrintDocumentInfo.Builder(pdfFile.name)
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(1)
                        .build()
                    callback?.onLayoutFinished(info, true)
                }

                override fun onWrite(
                    pages: Array<out PageRange>?,
                    destination: ParcelFileDescriptor?,
                    cancellationSignal: CancellationSignal?,
                    callback: WriteResultCallback?
                ) {
                    try {
                        FileInputStream(pdfFile).use { input ->
                            FileOutputStream(destination?.fileDescriptor).use { output ->
                                input.copyTo(output)
                            }
                        }
                        callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                    } catch (e: Exception) {
                        callback?.onWriteFailed(e.message)
                    }
                }
            }
            printManager.print(jobName, printAdapter, null)
        } catch (e: Exception) {
            Toast.makeText(context, "Error printing PDF: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun sharePdf(context: Context, pdfFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                pdfFile
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share PDF Receipt"))
        } catch (e: Exception) {
            Toast.makeText(context, "Error sharing PDF: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun generateCustomerStatementPdf(
        context: Context,
        customer: Customer,
        sales: List<SaleWithItems>,
        ledgerEntries: List<LedgerEntry>,
        periodLabel: String = "All-Time"
    ): File {
        val pdfDocument = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842

        val isBn = StoreInfoManager.isBillBengali()
        val headerColorInt = parseColorHex(StoreInfoManager.pdfHeaderColor, Color.rgb(29, 108, 49))

        val headerBgPaint = Paint().apply { color = headerColorInt }
        val titlePaint = Paint().apply {
            color = Color.WHITE
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val headerSubPaint = Paint().apply {
            color = Color.rgb(230, 245, 235)
            textSize = 10f
            typeface = Typeface.DEFAULT
            textAlign = Paint.Align.CENTER
        }
        val sectionTitlePaint = Paint().apply {
            color = headerColorInt
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val textPaint = Paint().apply {
            color = Color.rgb(30, 30, 30)
            textSize = 9.5f
            typeface = Typeface.DEFAULT
        }
        val textBoldPaint = Paint().apply {
            color = Color.rgb(30, 30, 30)
            textSize = 9.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val textRightPaint = Paint().apply {
            color = Color.rgb(30, 30, 30)
            textSize = 9.5f
            typeface = Typeface.DEFAULT
            textAlign = Paint.Align.RIGHT
        }
        val tableHeaderPaint = Paint().apply {
            color = Color.WHITE
            textSize = 9.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val linePaint = Paint().apply {
            color = Color.rgb(220, 220, 220)
            strokeWidth = 1f
        }

        // Timeline items
        val transactions = mutableListOf<WhatsAppHelper.StatementTransactionItem>()
        sales.forEach { sWithItems ->
            val s = sWithItems.sale
            val dueAdded = if (s.paymentMode.equals("KHATA", ignoreCase = true) || s.paymentMode.equals("CREDIT", ignoreCase = true)) {
                s.dueAmount.coerceAtLeast(0.0).ifZeroThen(s.finalAmount - s.receivedAmount).coerceAtLeast(0.0)
            } else {
                s.dueAmount.coerceAtLeast(0.0)
            }
            transactions.add(
                WhatsAppHelper.StatementTransactionItem(
                    datetime = s.datetime,
                    type = "SALE",
                    refNo = "Bill #${s.id.takeLast(6)}",
                    totalAmount = s.finalAmount,
                    paidAmount = s.receivedAmount,
                    dueImpact = dueAdded,
                    note = s.paymentMode
                )
            )
        }

        val linkedSaleIds = sales.map { it.sale.id }.toSet()
        ledgerEntries.forEach { entry ->
            val isPayment = entry.type.contains("PAYMENT") || entry.type == "PAYMENT_RECEIVED"
            val isCredit = entry.type.contains("CREDIT") || entry.type == "SALE_CREDIT"

            if (entry.referenceId != null && linkedSaleIds.contains(entry.referenceId)) return@forEach

            if (isPayment) {
                transactions.add(
                    WhatsAppHelper.StatementTransactionItem(
                        datetime = entry.datetime,
                        type = "PAYMENT",
                        refNo = "Payment",
                        totalAmount = entry.amount,
                        paidAmount = entry.amount,
                        dueImpact = -entry.amount,
                        note = entry.note
                    )
                )
            } else if (isCredit) {
                transactions.add(
                    WhatsAppHelper.StatementTransactionItem(
                        datetime = entry.datetime,
                        type = "CREDIT_ENTRY",
                        refNo = "Khata Credit",
                        totalAmount = entry.amount,
                        paidAmount = 0.0,
                        dueImpact = entry.amount,
                        note = entry.note
                    )
                )
            }
        }
        transactions.sortBy { it.datetime }

        val leftMargin = 35f
        val rightMargin = pageWidth - 35f

        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        val page = pdfDocument.startPage(pageInfo)
        val canvas: Canvas = page.canvas

        // Header Banner
        canvas.drawRect(0f, 0f, pageWidth.toFloat(), 85f, headerBgPaint)
        var headerY = 28f
        canvas.drawText(StoreInfoManager.storeName.uppercase(), pageWidth / 2f, headerY, titlePaint)
        headerY += 15f

        val storeSubHeader = buildString {
            if (StoreInfoManager.storeAddress.isNotBlank()) append(StoreInfoManager.storeAddress)
            if (StoreInfoManager.phone.isNotBlank()) append(" | Ph: ${StoreInfoManager.phone}")
            if (StoreInfoManager.gstin.isNotBlank()) append(" | GSTIN: ${StoreInfoManager.gstin}")
        }
        if (storeSubHeader.isNotBlank()) {
            canvas.drawText(storeSubHeader, pageWidth / 2f, headerY, headerSubPaint)
            headerY += 13f
        }
        if (StoreInfoManager.tagline.isNotBlank()) {
            canvas.drawText("“${StoreInfoManager.tagline}”", pageWidth / 2f, headerY, headerSubPaint)
        }

        var y = 105f
        canvas.drawText(if (isBn) "গ্রাহক খাতা স্টেটমেন্ট" else "CUSTOMER KHATA STATEMENT", leftMargin, y, sectionTitlePaint)
        canvas.drawText("Period: $periodLabel", rightMargin, y, textRightPaint)
        y += 14f
        canvas.drawLine(leftMargin, y, rightMargin, y, linePaint)
        y += 16f

        // Customer Info Card
        canvas.drawText("Customer: ${customer.name}", leftMargin, y, textBoldPaint)
        val sdfDate = SimpleDateFormat("dd/MM/yyyy hh:mm a", Locale.getDefault())
        canvas.drawText("Date: ${sdfDate.format(Date())}", rightMargin, y, textRightPaint)
        y += 14f

        if (customer.phone.isNotBlank()) {
            canvas.drawText("Phone: ${customer.phone}", leftMargin, y, textPaint)
            y += 14f
        }

        y += 6f
        // Table Header
        canvas.drawRect(leftMargin, y, rightMargin, y + 20f, headerBgPaint)
        val colDate = leftMargin + 8f
        val colDesc = leftMargin + 80f
        val colBill = leftMargin + 260f
        val colPaid = leftMargin + 340f
        val colDue = leftMargin + 420f
        val colRun = rightMargin - 8f

        canvas.drawText("DATE", colDate, y + 14f, tableHeaderPaint)
        canvas.drawText("TRANSACTION / REF", colDesc, y + 14f, tableHeaderPaint)
        canvas.drawText("BILL (₹)", colBill, y + 14f, tableHeaderPaint)
        canvas.drawText("PAID (₹)", colPaid, y + 14f, tableHeaderPaint)
        canvas.drawText("DUE (+/-)", colDue, y + 14f, tableHeaderPaint)
        canvas.drawText("BALANCE (₹)", colRun, y + 14f, Paint(tableHeaderPaint).apply { textAlign = Paint.Align.RIGHT })
        y += 24f

        val itemDateFmt = SimpleDateFormat("dd/MM/yy", Locale.getDefault())
        var runningBal = 0.0

        if (transactions.isEmpty()) {
            canvas.drawText("No transactions found in this selected period.", leftMargin + 8f, y + 14f, textPaint)
            y += 24f
        } else {
            transactions.take(25).forEach { item ->
                runningBal += item.dueImpact
                val dtStr = itemDateFmt.format(Date(item.datetime))
                val refStr = when (item.type) {
                    "SALE" -> item.refNo
                    "PAYMENT" -> "Payment Received"
                    else -> item.refNo
                }
                val billStr = if (item.type == "SALE") "₹%.2f".format(item.totalAmount) else "-"
                val paidStr = if (item.paidAmount > 0) "₹%.2f".format(item.paidAmount) else "-"
                val dueStr = if (item.dueImpact > 0) "+₹%.2f".format(item.dueImpact) else if (item.dueImpact < 0) "-₹%.2f".format(-item.dueImpact) else "₹0.00"
                val balStr = "₹%.2f".format(runningBal)

                canvas.drawText(dtStr, colDate, y + 12f, textPaint)
                canvas.drawText(refStr, colDesc, y + 12f, textBoldPaint)
                canvas.drawText(billStr, colBill, y + 12f, textPaint)
                canvas.drawText(paidStr, colPaid, y + 12f, textPaint)
                canvas.drawText(dueStr, colDue, y + 12f, textPaint)
                canvas.drawText(balStr, colRun, y + 12f, textRightPaint)

                y += 16f
                canvas.drawLine(leftMargin, y, rightMargin, y, linePaint)
                y += 4f
            }
        }

        y += 10f
        // Summary Card
        val totalBilled = sales.sumOf { it.sale.finalAmount }
        val totalRecv = sales.sumOf { it.sale.receivedAmount } + ledgerEntries.filter { it.type.contains("PAYMENT") || it.type == "PAYMENT_RECEIVED" }.sumOf { it.amount }

        canvas.drawRect(rightMargin - 220f, y, rightMargin, y + 65f, Paint().apply { color = Color.rgb(245, 245, 245) })
        canvas.drawText("Total Purchases:", rightMargin - 210f, y + 16f, textPaint)
        canvas.drawText("₹%.2f".format(totalBilled), rightMargin - 10f, y + 16f, textRightPaint)

        canvas.drawText("Total Payments:", rightMargin - 210f, y + 32f, textPaint)
        canvas.drawText("₹%.2f".format(totalRecv), rightMargin - 10f, y + 32f, textRightPaint)

        canvas.drawText("Current Due Balance:", rightMargin - 210f, y + 52f, textBoldPaint)
        canvas.drawText("₹%.2f".format(customer.balance), rightMargin - 10f, y + 52f, Paint(textBoldPaint).apply { color = headerColorInt; textAlign = Paint.Align.RIGHT })

        // UPI QR Code if enabled
        if (StoreInfoManager.merchantUpiId.isNotBlank() && customer.balance > 0) {
            val qrText = StoreInfoManager.buildUpiPayUrl(
                upiId = StoreInfoManager.merchantUpiId,
                payeeName = StoreInfoManager.merchantPayeeName,
                amount = customer.balance,
                note = "Credit Bill Payment"
            )
            val qrBitmap = generateQrCodeBitmap(qrText, 70, 70)
            if (qrBitmap != null) {
                canvas.drawBitmap(qrBitmap, leftMargin, y, null)
                canvas.drawText("Scan to Pay Due Balance", leftMargin + 80f, y + 25f, textBoldPaint)
                canvas.drawText("UPI ID: ${StoreInfoManager.merchantUpiId}", leftMargin + 80f, y + 42f, textPaint)
            }
        }

        // Footer
        val footerY = pageHeight - 35f
        canvas.drawLine(leftMargin, footerY - 10f, rightMargin, footerY - 10f, linePaint)
        canvas.drawText("Thank you for your business! Please clear your pending dues at the earliest.", pageWidth / 2f, footerY, Paint(textPaint).apply { textAlign = Paint.Align.CENTER })

        pdfDocument.finishPage(page)

        val outputFile = File(context.cacheDir, "Statement_${customer.id}_${System.currentTimeMillis()}.pdf")
        if (outputFile.exists()) outputFile.delete()
        FileOutputStream(outputFile).use { out -> pdfDocument.writeTo(out) }
        pdfDocument.close()
        return outputFile
    }

    fun generateSupplierStatementPdf(
        context: Context,
        supplier: Supplier,
        purchases: List<PurchaseWithItems>,
        ledgerEntries: List<LedgerEntry>,
        periodLabel: String = "All-Time"
    ): File {
        val pdfDocument = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842

        val isBn = StoreInfoManager.isBillBengali()
        val headerColorInt = parseColorHex(StoreInfoManager.pdfHeaderColor, Color.rgb(29, 108, 49))

        val headerBgPaint = Paint().apply { color = headerColorInt }
        val titlePaint = Paint().apply {
            color = Color.WHITE
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val headerSubPaint = Paint().apply {
            color = Color.rgb(230, 245, 235)
            textSize = 10f
            typeface = Typeface.DEFAULT
            textAlign = Paint.Align.CENTER
        }
        val sectionTitlePaint = Paint().apply {
            color = headerColorInt
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val textPaint = Paint().apply {
            color = Color.rgb(30, 30, 30)
            textSize = 9.5f
            typeface = Typeface.DEFAULT
        }
        val textBoldPaint = Paint().apply {
            color = Color.rgb(30, 30, 30)
            textSize = 9.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val textRightPaint = Paint().apply {
            color = Color.rgb(30, 30, 30)
            textSize = 9.5f
            typeface = Typeface.DEFAULT
            textAlign = Paint.Align.RIGHT
        }
        val tableHeaderPaint = Paint().apply {
            color = Color.WHITE
            textSize = 9.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val linePaint = Paint().apply {
            color = Color.rgb(220, 220, 220)
            strokeWidth = 1f
        }

        // Timeline items
        val transactions = mutableListOf<WhatsAppHelper.StatementTransactionItem>()
        purchases.forEach { pWithItems ->
            val p = pWithItems.purchase
            val duePortion = if (p.dueAmount > 0) p.dueAmount else (p.totalAmount - p.amountPaid).coerceAtLeast(0.0)
            transactions.add(
                WhatsAppHelper.StatementTransactionItem(
                    datetime = p.datetime,
                    type = "PURCHASE",
                    refNo = "Bill #${p.id.takeLast(6)}",
                    totalAmount = p.totalAmount,
                    paidAmount = p.amountPaid,
                    dueImpact = duePortion,
                    note = p.notes
                )
            )
        }

        val linkedPurchaseIds = purchases.map { it.purchase.id }.toSet()
        ledgerEntries.forEach { entry ->
            val isPayment = entry.type.contains("PAYMENT") || entry.type == "PAYMENT_MADE"
            val isCredit = entry.type.contains("CREDIT") || entry.type == "PURCHASE_CREDIT"

            if (entry.referenceId != null && linkedPurchaseIds.contains(entry.referenceId)) return@forEach

            if (isPayment) {
                transactions.add(
                    WhatsAppHelper.StatementTransactionItem(
                        datetime = entry.datetime,
                        type = "PAYMENT",
                        refNo = "Payment",
                        totalAmount = entry.amount,
                        paidAmount = entry.amount,
                        dueImpact = -entry.amount,
                        note = entry.note
                    )
                )
            } else if (isCredit) {
                transactions.add(
                    WhatsAppHelper.StatementTransactionItem(
                        datetime = entry.datetime,
                        type = "PURCHASE_CREDIT",
                        refNo = "Payable Due",
                        totalAmount = entry.amount,
                        paidAmount = 0.0,
                        dueImpact = entry.amount,
                        note = entry.note
                    )
                )
            }
        }
        transactions.sortBy { it.datetime }

        val leftMargin = 35f
        val rightMargin = pageWidth - 35f

        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        val page = pdfDocument.startPage(pageInfo)
        val canvas: Canvas = page.canvas

        // Header Banner
        canvas.drawRect(0f, 0f, pageWidth.toFloat(), 85f, headerBgPaint)
        var headerY = 28f
        canvas.drawText(StoreInfoManager.storeName.uppercase(), pageWidth / 2f, headerY, titlePaint)
        headerY += 15f

        val storeSubHeader = buildString {
            if (StoreInfoManager.storeAddress.isNotBlank()) append(StoreInfoManager.storeAddress)
            if (StoreInfoManager.phone.isNotBlank()) append(" | Ph: ${StoreInfoManager.phone}")
            if (StoreInfoManager.gstin.isNotBlank()) append(" | GSTIN: ${StoreInfoManager.gstin}")
        }
        if (storeSubHeader.isNotBlank()) {
            canvas.drawText(storeSubHeader, pageWidth / 2f, headerY, headerSubPaint)
            headerY += 13f
        }
        if (StoreInfoManager.tagline.isNotBlank()) {
            canvas.drawText("“${StoreInfoManager.tagline}”", pageWidth / 2f, headerY, headerSubPaint)
        }

        var y = 105f
        canvas.drawText(if (isBn) "মহাজন / সাপ্লায়ার খাতা স্টেটমেন্ট" else "SUPPLIER KHATA STATEMENT", leftMargin, y, sectionTitlePaint)
        canvas.drawText("Period: $periodLabel", rightMargin, y, textRightPaint)
        y += 14f
        canvas.drawLine(leftMargin, y, rightMargin, y, linePaint)
        y += 16f

        // Supplier Info Card
        canvas.drawText("Supplier: ${supplier.name}", leftMargin, y, textBoldPaint)
        val sdfDate = SimpleDateFormat("dd/MM/yyyy hh:mm a", Locale.getDefault())
        canvas.drawText("Date: ${sdfDate.format(Date())}", rightMargin, y, textRightPaint)
        y += 14f

        if (supplier.phone.isNotBlank()) {
            canvas.drawText("Phone: ${supplier.phone}", leftMargin, y, textPaint)
            if (!supplier.address.isNullOrBlank()) {
                canvas.drawText("Address: ${supplier.address}", rightMargin, y, textRightPaint)
            }
            y += 14f
        }

        y += 6f
        // Table Header
        canvas.drawRect(leftMargin, y, rightMargin, y + 20f, headerBgPaint)
        val colDate = leftMargin + 8f
        val colDesc = leftMargin + 80f
        val colBill = leftMargin + 260f
        val colPaid = leftMargin + 340f
        val colDue = leftMargin + 420f
        val colRun = rightMargin - 8f

        canvas.drawText("DATE", colDate, y + 14f, tableHeaderPaint)
        canvas.drawText("BILL / PAYMENT REF", colDesc, y + 14f, tableHeaderPaint)
        canvas.drawText("BILL (₹)", colBill, y + 14f, tableHeaderPaint)
        canvas.drawText("PAID (₹)", colPaid, y + 14f, tableHeaderPaint)
        canvas.drawText("DUES (+/-)", colDue, y + 14f, tableHeaderPaint)
        canvas.drawText("RUNNING DUES (₹)", colRun, y + 14f, Paint(tableHeaderPaint).apply { textAlign = Paint.Align.RIGHT })
        y += 24f

        val itemDateFmt = SimpleDateFormat("dd/MM/yy", Locale.getDefault())
        var runningDues = 0.0

        if (transactions.isEmpty()) {
            canvas.drawText("No bills or payments found in this selected period.", leftMargin + 8f, y + 14f, textPaint)
            y += 24f
        } else {
            transactions.take(25).forEach { item ->
                runningDues += item.dueImpact
                val dtStr = itemDateFmt.format(Date(item.datetime))
                val refStr = when (item.type) {
                    "PURCHASE" -> item.refNo
                    "PAYMENT" -> "Payment Made"
                    else -> item.refNo
                }
                val billStr = if (item.type == "PURCHASE") "₹%.2f".format(item.totalAmount) else "-"
                val paidStr = if (item.paidAmount > 0) "₹%.2f".format(item.paidAmount) else "-"
                val dueStr = if (item.dueImpact > 0) "+₹%.2f".format(item.dueImpact) else if (item.dueImpact < 0) "-₹%.2f".format(-item.dueImpact) else "₹0.00"
                val balStr = "₹%.2f".format(runningDues)

                canvas.drawText(dtStr, colDate, y + 12f, textPaint)
                canvas.drawText(refStr, colDesc, y + 12f, textBoldPaint)
                canvas.drawText(billStr, colBill, y + 12f, textPaint)
                canvas.drawText(paidStr, colPaid, y + 12f, textPaint)
                canvas.drawText(dueStr, colDue, y + 12f, textPaint)
                canvas.drawText(balStr, colRun, y + 12f, textRightPaint)

                y += 16f
                canvas.drawLine(leftMargin, y, rightMargin, y, linePaint)
                y += 4f
            }
        }

        y += 10f
        // Summary Card
        val totalPurchased = purchases.sumOf { it.purchase.totalAmount }
        val totalPaidToSupp = purchases.sumOf { it.purchase.amountPaid } + ledgerEntries.filter { it.type.contains("PAYMENT") || it.type == "PAYMENT_MADE" }.sumOf { it.amount }

        canvas.drawRect(rightMargin - 220f, y, rightMargin, y + 65f, Paint().apply { color = Color.rgb(245, 245, 245) })
        canvas.drawText("Total Purchase Bills:", rightMargin - 210f, y + 16f, textPaint)
        canvas.drawText("₹%.2f".format(totalPurchased), rightMargin - 10f, y + 16f, textRightPaint)

        canvas.drawText("Total Paid to Supplier:", rightMargin - 210f, y + 32f, textPaint)
        canvas.drawText("₹%.2f".format(totalPaidToSupp), rightMargin - 10f, y + 32f, textRightPaint)

        canvas.drawText("Current Payable Dues:", rightMargin - 210f, y + 52f, textBoldPaint)
        canvas.drawText("₹%.2f".format(supplier.balance), rightMargin - 10f, y + 52f, Paint(textBoldPaint).apply { color = headerColorInt; textAlign = Paint.Align.RIGHT })

        // Footer
        val footerY = pageHeight - 35f
        canvas.drawLine(leftMargin, footerY - 10f, rightMargin, footerY - 10f, linePaint)
        canvas.drawText("Thank you for your valued partnership and support!", pageWidth / 2f, footerY, Paint(textPaint).apply { textAlign = Paint.Align.CENTER })

        pdfDocument.finishPage(page)

        val outputFile = File(context.cacheDir, "Supplier_Statement_${supplier.id}_${System.currentTimeMillis()}.pdf")
        if (outputFile.exists()) outputFile.delete()
        FileOutputStream(outputFile).use { out -> pdfDocument.writeTo(out) }
        pdfDocument.close()
        return outputFile
    }

    private fun Double.ifZeroThen(fallback: Double): Double = if (this == 0.0) fallback else this
}
