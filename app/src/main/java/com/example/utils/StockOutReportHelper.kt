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
import android.net.Uri
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
import com.example.data.local.entities.StockOutEntry
import com.example.data.repository.StockOutReportSummary
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object StockOutReportHelper {

    private fun formatDateRange(startTime: Long, endTime: Long): String {
        val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        return if (startTime <= 0) {
            "All Time (up to ${sdf.format(Date(endTime))})"
        } else {
            "${sdf.format(Date(startTime))} - ${sdf.format(Date(endTime))}"
        }
    }

    // ==========================================
    // 1. WhatsApp Text Summary Generator
    // ==========================================
    fun generateWhatsAppText(summary: StockOutReportSummary, isBn: Boolean = false): String {
        val sb = StringBuilder()
        val storeName = StoreInfoManager.storeName.ifBlank { "Kali Mata Store" }
        val dateRangeStr = formatDateRange(summary.startTime, summary.endTime)

        if (isBn) {
            sb.append("📋 *স্টক আউট ও অপচয় ক্ষতি রিপোর্ট*\n")
            sb.append("🏪 *দোকান:* $storeName\n")
            sb.append("🗓️ *সময়কাল:* $dateRangeStr\n")
            sb.append("--------------------------------\n")
            sb.append("🚨 *প্রকৃত ব্যবসার ক্ষতি (Stock Loss):* ₹%.2f\n".format(summary.totalBusinessLossCost))
            sb.append("  • ক্ষতিগ্রস্ত (Damaged): ₹%.2f (%.1f items)\n".format(summary.damagedCost, summary.damagedQty))
            sb.append("  • মেয়াদ উত্তীর্ণ (Expired): ₹%.2f (%.1f items)\n".format(summary.expiredCost, summary.expiredQty))
            sb.append("  • অপচয় (Wastage): ₹%.2f (%.1f items)\n".format(summary.wastageCost, summary.wastageQty))
            sb.append("--------------------------------\n")
            sb.append("👤 *ব্যক্তিগত ব্যবহার (Personal Use):* ₹%.2f (%.1f items)\n".format(summary.personalUseCost, summary.personalUseQty))
            sb.append("   _(মালিকের নিজস্ব ব্যবহার - ব্যবসার ক্ষতি নয়)_\n")
            if (summary.otherCost > 0) {
                sb.append("📦 *অন্যান্য (Other):* ₹%.2f (%.1f items)\n".format(summary.otherCost, summary.otherQty))
            }
            sb.append("--------------------------------\n")
            sb.append("📊 *সর্বমোট স্টক অপসারণ মূল্য:* ₹%.2f\n".format(summary.combinedTotalCost))
            sb.append("🔢 *মোট স্টক আউট এন্ট্রি:* ${summary.entries.size} টি\n\n")

            if (summary.entries.isNotEmpty()) {
                sb.append("📝 *সাম্প্রতিক স্টক আউট আইটেম সমূহ:*\n")
                val itemSdf = SimpleDateFormat("dd/MM hh:mm a", Locale.getDefault())
                summary.entries.take(15).forEachIndexed { idx, entry ->
                    val name = entry.getDisplayName(true)
                    val dateStr = itemSdf.format(Date(entry.timestamp))
                    val tag = if (entry.isBusinessLoss()) "❌ ক্ষতি" else if (entry.isPersonalUse()) "👤 নিজস্ব" else "ℹ️ অন্যান্য"
                    sb.append("${idx + 1}. $name - ${entry.quantity} ${entry.unitType} @ ₹%.2f = ₹%.2f [$tag]\n".format(entry.costPrice, entry.totalCostValue))
                    if (!entry.note.isNullOrBlank()) {
                        sb.append("   _নোট: ${entry.note}_\n")
                    }
                }
                if (summary.entries.size > 15) {
                    sb.append("... এবং আরও ${summary.entries.size - 15} টি এন্ট্রি।\n")
                }
            }
        } else {
            sb.append("📋 *STOCK-OUT & WASTAGE COST REPORT*\n")
            sb.append("🏪 *Store:* $storeName\n")
            sb.append("🗓️ *Period:* $dateRangeStr\n")
            sb.append("--------------------------------\n")
            sb.append("🚨 *GENUINE BUSINESS LOSS (Stock Loss):* ₹%.2f\n".format(summary.totalBusinessLossCost))
            sb.append("  • Damaged: ₹%.2f (%.1f qty)\n".format(summary.damagedCost, summary.damagedQty))
            sb.append("  • Expired: ₹%.2f (%.1f qty)\n".format(summary.expiredCost, summary.expiredQty))
            sb.append("  • Wastage: ₹%.2f (%.1f qty)\n".format(summary.wastageCost, summary.wastageQty))
            sb.append("--------------------------------\n")
            sb.append("👤 *PERSONAL USE (Owner Draw):* ₹%.2f (%.1f qty)\n".format(summary.personalUseCost, summary.personalUseQty))
            sb.append("   _(Owner's personal use — excluded from business loss)_\n")
            if (summary.otherCost > 0) {
                sb.append("📦 *Other Stock Out:* ₹%.2f (%.1f qty)\n".format(summary.otherCost, summary.otherQty))
            }
            sb.append("--------------------------------\n")
            sb.append("📊 *TOTAL STOCK REMOVED VALUE:* ₹%.2f\n".format(summary.combinedTotalCost))
            sb.append("🔢 *Total Stock-Out Records:* ${summary.entries.size}\n\n")

            if (summary.entries.isNotEmpty()) {
                sb.append("📝 *Itemized Stock-Out Records:*\n")
                val itemSdf = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
                summary.entries.take(15).forEachIndexed { idx, entry ->
                    val name = entry.productNameEn
                    val tag = if (entry.isBusinessLoss()) "❌ LOSS" else if (entry.isPersonalUse()) "👤 PERSONAL" else "ℹ️ OTHER"
                    sb.append("${idx + 1}. $name — ${entry.quantity} ${entry.unitType} @ ₹%.2f = ₹%.2f [$tag]\n".format(entry.costPrice, entry.totalCostValue))
                    if (!entry.note.isNullOrBlank()) {
                        sb.append("   _Note: ${entry.note}_\n")
                    }
                }
                if (summary.entries.size > 15) {
                    sb.append("... and ${summary.entries.size - 15} more records.\n")
                }
            }
        }

        sb.append("\n_Generated from $storeName POS system on ${SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())}_")
        return sb.toString()
    }

    fun shareWhatsApp(context: Context, summary: StockOutReportSummary, isBn: Boolean = false) {
        val text = generateWhatsAppText(summary, isBn)
        WhatsAppHelper.sendWhatsAppMessage(context, null, text)
    }

    fun shareSummaryText(context: Context, summary: StockOutReportSummary, isBn: Boolean = false) {
        val text = generateWhatsAppText(summary, isBn)
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, if (isBn) "স্টক আউট ও অপচয় রিপোর্ট" else "Stock-Out & Wastage Report")
            type = "text/plain"
        }
        val shareIntent = Intent.createChooser(sendIntent, if (isBn) "রিপোর্ট শেয়ার করুন" else "Share Stock-Out Report")
        context.startActivity(shareIntent)
    }

    // ==========================================
    // 2. CSV Export Generator
    // ==========================================
    fun generateCsvFile(context: Context, summary: StockOutReportSummary): File {
        val fileName = "Stock_Out_Wastage_Report_${System.currentTimeMillis()}.csv"
        val file = File(context.cacheDir, fileName)
        val sdfDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val sdfTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        file.bufferedWriter().use { out ->
            // Metadata header
            out.write("# Store: \"${StoreInfoManager.storeName.replace("\"", "\"\"")}\"\n")
            out.write("# Period: \"${formatDateRange(summary.startTime, summary.endTime)}\"\n")
            out.write("# Total Business Loss Cost (Damaged+Expired+Wastage): ₹%.2f\n".format(summary.totalBusinessLossCost))
            out.write("# Total Personal Use Cost (Not a Loss): ₹%.2f\n".format(summary.personalUseCost))
            out.write("# Combined Total Removed Cost: ₹%.2f\n".format(summary.combinedTotalCost))
            out.write("# Generated At: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n\n")

            // Column headers
            out.write("Date,Time,Product ID,Product Name,Quantity,Unit,Cost Price (INR),Total Cost Value (INR),Reason,Category,Is Business Loss,Note\n")

            for (entry in summary.entries) {
                val date = sdfDate.format(Date(entry.timestamp))
                val time = sdfTime.format(Date(entry.timestamp))
                val pid = escapeCsv(entry.productId)
                val name = escapeCsv(entry.productNameEn)
                val qty = entry.quantity
                val unit = escapeCsv(entry.unitType)
                val costPrice = "%.2f".format(Locale.US, entry.costPrice)
                val totalCost = "%.2f".format(Locale.US, entry.totalCostValue)
                val reason = escapeCsv(entry.reason)
                val category = escapeCsv(entry.getReasonCategory().name)
                val isLoss = if (entry.isBusinessLoss()) "YES" else "NO"
                val note = escapeCsv(entry.note ?: "")

                out.write("$date,$time,$pid,$name,$qty,$unit,$costPrice,$totalCost,$reason,$category,$isLoss,$note\n")
            }
        }
        return file
    }

    private fun escapeCsv(value: String): String {
        var str = value.replace("\n", " ").replace("\r", "")
        if (str.contains(",") || str.contains("\"")) {
            str = "\"" + str.replace("\"", "\"\"") + "\""
        }
        return str
    }

    fun exportAndShareCsv(context: Context, summary: StockOutReportSummary) {
        try {
            val csvFile = generateCsvFile(context, summary)
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                csvFile
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Stock-Out & Wastage Cost Report (${formatDateRange(summary.startTime, summary.endTime)})")
                putExtra(Intent.EXTRA_TEXT, "Attached is the Stock-Out & Wastage Cost CSV spreadsheet report.")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Export Stock-Out Report (CSV)"))
        } catch (e: Exception) {
            Toast.makeText(context, "Error exporting CSV: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ==========================================
    // 3. PDF Document Generator & Printing
    // ==========================================
    fun generatePdfReport(context: Context, summary: StockOutReportSummary, isBn: Boolean = false): File {
        val pdfDocument = PdfDocument()
        val pageWidth = 595 // Standard A4 width in points
        val pageHeight = 842 // Standard A4 height in points
        val margin = 36f
        val contentWidth = pageWidth - (margin * 2)

        var pageNumber = 1
        var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
        var page = pdfDocument.startPage(pageInfo)
        var canvas: Canvas = page.canvas

        val titlePaint = Paint().apply {
            color = Color.rgb(24, 43, 73)
            textSize = 16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val subtitlePaint = Paint().apply {
            color = Color.rgb(90, 100, 115)
            textSize = 10f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            isAntiAlias = true
        }

        val sectionHeadingPaint = Paint().apply {
            color = Color.rgb(30, 41, 59)
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val textPaint = Paint().apply {
            color = Color.rgb(30, 30, 30)
            textSize = 9.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            isAntiAlias = true
        }

        val boldTextPaint = Paint().apply {
            color = Color.rgb(15, 23, 42)
            textSize = 9.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val headerBgPaint = Paint().apply {
            color = Color.rgb(238, 242, 246)
        }

        val lossCardBgPaint = Paint().apply {
            color = Color.rgb(254, 242, 242)
        }

        val lossCardBorderPaint = Paint().apply {
            color = Color.rgb(239, 68, 68)
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }

        val personalCardBgPaint = Paint().apply {
            color = Color.rgb(239, 246, 255)
        }

        val personalCardBorderPaint = Paint().apply {
            color = Color.rgb(59, 130, 246)
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }

        val totalCardBgPaint = Paint().apply {
            color = Color.rgb(243, 244, 246)
        }

        val totalCardBorderPaint = Paint().apply {
            color = Color.rgb(156, 163, 175)
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }

        val linePaint = Paint().apply {
            color = Color.rgb(226, 232, 240)
            strokeWidth = 1f
        }

        var y = margin + 10f

        // Draw Header
        val storeName = StoreInfoManager.storeName.ifBlank { "Kali Mata Store" }
        canvas.drawText(storeName, margin, y, titlePaint)
        y += 18f

        val reportTitle = if (isBn) "স্টক আউট ও অপচয় ক্ষতি রিপোর্ট (Stock-Out & Wastage Report)" else "Stock-Out & Wastage Cost Report"
        canvas.drawText(reportTitle, margin, y, sectionHeadingPaint)
        y += 14f

        val periodStr = "Period: ${formatDateRange(summary.startTime, summary.endTime)}   |   Generated: ${SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())}"
        canvas.drawText(periodStr, margin, y, subtitlePaint)
        y += 18f

        canvas.drawLine(margin, y, pageWidth - margin, y, linePaint)
        y += 16f

        // Draw 3 Summary KPI Cards (Loss, Personal Use, Total)
        val cardSpacing = 8f
        val cardWidth = (contentWidth - (cardSpacing * 2)) / 3f
        val cardHeight = 62f

        // Card 1: Business Loss (Red)
        val card1Rect = RectF(margin, y, margin + cardWidth, y + cardHeight)
        canvas.drawRoundRect(card1Rect, 6f, 6f, lossCardBgPaint)
        canvas.drawRoundRect(card1Rect, 6f, 6f, lossCardBorderPaint)

        val cardTitlePaintLoss = Paint().apply {
            color = Color.rgb(185, 28, 28)
            textSize = 8.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        val cardValPaintLoss = Paint().apply {
            color = Color.rgb(185, 28, 28)
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        val cardSubPaint = Paint().apply {
            color = Color.rgb(100, 116, 139)
            textSize = 7.5f
            isAntiAlias = true
        }

        canvas.drawText("GENUINE BUSINESS LOSS", margin + 8f, y + 15f, cardTitlePaintLoss)
        canvas.drawText("₹%.2f".format(summary.totalBusinessLossCost), margin + 8f, y + 33f, cardValPaintLoss)
        canvas.drawText("Damaged+Expired+Wastage", margin + 8f, y + 48f, cardSubPaint)
        canvas.drawText("(Deducted from Net Profit)", margin + 8f, y + 56f, cardSubPaint)

        // Card 2: Personal Use (Blue)
        val card2X = margin + cardWidth + cardSpacing
        val card2Rect = RectF(card2X, y, card2X + cardWidth, y + cardHeight)
        canvas.drawRoundRect(card2Rect, 6f, 6f, personalCardBgPaint)
        canvas.drawRoundRect(card2Rect, 6f, 6f, personalCardBorderPaint)

        val cardTitlePaintPersonal = Paint().apply {
            color = Color.rgb(29, 78, 216)
            textSize = 8.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        val cardValPaintPersonal = Paint().apply {
            color = Color.rgb(29, 78, 216)
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        canvas.drawText("PERSONAL USE (OWNER)", card2X + 8f, y + 15f, cardTitlePaintPersonal)
        canvas.drawText("₹%.2f".format(summary.personalUseCost), card2X + 8f, y + 33f, cardValPaintPersonal)
        canvas.drawText("Owner Drawings (${summary.personalUseQty} qty)", card2X + 8f, y + 48f, cardSubPaint)
        canvas.drawText("*NOT a Business Loss*", card2X + 8f, y + 56f, cardTitlePaintPersonal)

        // Card 3: Combined Total
        val card3X = card2X + cardWidth + cardSpacing
        val card3Rect = RectF(card3X, y, card3X + cardWidth, y + cardHeight)
        canvas.drawRoundRect(card3Rect, 6f, 6f, totalCardBgPaint)
        canvas.drawRoundRect(card3Rect, 6f, 6f, totalCardBorderPaint)

        val cardTitlePaintTotal = Paint().apply {
            color = Color.rgb(55, 65, 81)
            textSize = 8.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        val cardValPaintTotal = Paint().apply {
            color = Color.rgb(17, 24, 39)
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        canvas.drawText("TOTAL STOCK REMOVED", card3X + 8f, y + 15f, cardTitlePaintTotal)
        canvas.drawText("₹%.2f".format(summary.combinedTotalCost), card3X + 8f, y + 33f, cardValPaintTotal)
        canvas.drawText("${summary.entries.size} Total Outflow Records", card3X + 8f, y + 48f, cardSubPaint)
        canvas.drawText("All reasons combined", card3X + 8f, y + 56f, cardSubPaint)

        y += cardHeight + 20f

        // Reason Breakdown Summary Section
        canvas.drawText("COST BREAKDOWN BY REASON", margin, y, sectionHeadingPaint)
        y += 12f

        // Table Header for Breakdown
        val breakHeaderRect = RectF(margin, y, pageWidth - margin, y + 20f)
        canvas.drawRoundRect(breakHeaderRect, 4f, 4f, headerBgPaint)

        val col1 = margin + 8f
        val col2 = margin + 140f
        val col3 = margin + 240f
        val col4 = margin + 350f

        canvas.drawText("Reason / Category", col1, y + 14f, boldTextPaint)
        canvas.drawText("Total Quantity", col2, y + 14f, boldTextPaint)
        canvas.drawText("Total Cost (₹)", col3, y + 14f, boldTextPaint)
        canvas.drawText("P&L Treatment", col4, y + 14f, boldTextPaint)
        y += 24f

        val breakdownRows = listOf(
            Triple("Damaged / ক্ষতিগ্রস্ত", "%.1f".format(summary.damagedQty), Pair(summary.damagedCost, "🚨 Business Loss (Deducted)")),
            Triple("Expired / মেয়াদ উত্তীর্ণ", "%.1f".format(summary.expiredQty), Pair(summary.expiredCost, "🚨 Business Loss (Deducted)")),
            Triple("Wastage / অপচয়", "%.1f".format(summary.wastageQty), Pair(summary.wastageCost, "🚨 Business Loss (Deducted)")),
            Triple("Personal Use / নিজস্ব ব্যবহার", "%.1f".format(summary.personalUseQty), Pair(summary.personalUseCost, "👤 Personal Draw (NOT a loss)")),
            Triple("Other / অন্যান্য", "%.1f".format(summary.otherQty), Pair(summary.otherCost, "ℹ️ Other"))
        )

        for (row in breakdownRows) {
            val isLoss = row.second.length > 0 && (row.first.contains("Damaged") || row.first.contains("Expired") || row.first.contains("Wastage"))
            val isPersonal = row.first.contains("Personal")

            val rowTextPaint = when {
                isLoss -> Paint(textPaint).apply { color = Color.rgb(185, 28, 28) }
                isPersonal -> Paint(textPaint).apply { color = Color.rgb(29, 78, 216) }
                else -> textPaint
            }

            canvas.drawText(row.first, col1, y + 12f, rowTextPaint)
            canvas.drawText(row.second, col2, y + 12f, textPaint)
            canvas.drawText("₹%.2f".format(row.third.first), col3, y + 12f, boldTextPaint)
            canvas.drawText(row.third.second, col4, y + 12f, rowTextPaint)

            y += 18f
            canvas.drawLine(margin, y, pageWidth - margin, y, linePaint)
            y += 4f
        }

        y += 16f

        // Itemized Entries Ledger
        canvas.drawText("ITEMIZED STOCK-OUT LEDGER (${summary.entries.size} Entries)", margin, y, sectionHeadingPaint)
        y += 12f

        // Ledger Table Header
        val ledgerHeaderRect = RectF(margin, y, pageWidth - margin, y + 20f)
        canvas.drawRoundRect(ledgerHeaderRect, 4f, 4f, headerBgPaint)

        val lColDate = margin + 6f
        val lColName = margin + 85f
        val lColQty = margin + 235f
        val lColPrice = margin + 300f
        val lColTotal = margin + 375f
        val lColReason = margin + 445f

        canvas.drawText("Date & Time", lColDate, y + 14f, boldTextPaint)
        canvas.drawText("Product", lColName, y + 14f, boldTextPaint)
        canvas.drawText("Quantity", lColQty, y + 14f, boldTextPaint)
        canvas.drawText("Cost/Unit", lColPrice, y + 14f, boldTextPaint)
        canvas.drawText("Total (₹)", lColTotal, y + 14f, boldTextPaint)
        canvas.drawText("Reason", lColReason, y + 14f, boldTextPaint)
        y += 24f

        val itemSdf = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault())

        for (entry in summary.entries) {
            // Check if page overflow
            if (y > pageHeight - margin - 30f) {
                pdfDocument.finishPage(page)
                pageNumber++
                pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                page = pdfDocument.startPage(pageInfo)
                canvas = page.canvas
                y = margin + 10f

                // Re-draw small header on subsequent pages
                canvas.drawText("$reportTitle - Page $pageNumber", margin, y, boldTextPaint)
                y += 16f
                val subHeaderRect = RectF(margin, y, pageWidth - margin, y + 20f)
                canvas.drawRoundRect(subHeaderRect, 4f, 4f, headerBgPaint)
                canvas.drawText("Date & Time", lColDate, y + 14f, boldTextPaint)
                canvas.drawText("Product", lColName, y + 14f, boldTextPaint)
                canvas.drawText("Quantity", lColQty, y + 14f, boldTextPaint)
                canvas.drawText("Cost/Unit", lColPrice, y + 14f, boldTextPaint)
                canvas.drawText("Total (₹)", lColTotal, y + 14f, boldTextPaint)
                canvas.drawText("Reason", lColReason, y + 14f, boldTextPaint)
                y += 24f
            }

            val dateStr = itemSdf.format(Date(entry.timestamp))
            val nameStr = if (entry.productNameEn.length > 24) entry.productNameEn.take(22) + ".." else entry.productNameEn
            val qtyStr = "%.1f %s".format(entry.quantity, entry.unitType)
            val costStr = "₹%.2f".format(entry.costPrice)
            val totalValStr = "₹%.2f".format(entry.totalCostValue)
            val reasonStr = entry.reason.take(18)

            val reasonColor = when {
                entry.isBusinessLoss() -> Color.rgb(185, 28, 28)
                entry.isPersonalUse() -> Color.rgb(29, 78, 216)
                else -> Color.rgb(75, 85, 99)
            }
            val reasonPaint = Paint(textPaint).apply { color = reasonColor }

            canvas.drawText(dateStr, lColDate, y + 12f, textPaint)
            canvas.drawText(nameStr, lColName, y + 12f, textPaint)
            canvas.drawText(qtyStr, lColQty, y + 12f, textPaint)
            canvas.drawText(costStr, lColPrice, y + 12f, textPaint)
            canvas.drawText(totalValStr, lColTotal, y + 12f, boldTextPaint)
            canvas.drawText(reasonStr, lColReason, y + 12f, reasonPaint)

            y += 16f

            if (!entry.note.isNullOrBlank()) {
                val noteStr = "  ↳ Note: ${entry.note}"
                val notePaint = Paint(subtitlePaint).apply { textSize = 7.5f }
                canvas.drawText(noteStr, lColName, y + 8f, notePaint)
                y += 12f
            }

            canvas.drawLine(margin, y, pageWidth - margin, y, linePaint)
            y += 3f
        }

        // Footer on last page
        y += 15f
        if (y < pageHeight - margin - 20f) {
            val footerText = "Generated via Kali Mata Store POS • Stock & Loss Management Subsystem"
            canvas.drawText(footerText, margin, pageHeight - margin - 10f, subtitlePaint)
        }

        pdfDocument.finishPage(page)

        val outputFile = File(context.cacheDir, "Stock_Out_Report_${System.currentTimeMillis()}.pdf")
        val fos = FileOutputStream(outputFile)
        pdfDocument.writeTo(fos)
        fos.close()
        pdfDocument.close()

        return outputFile
    }

    fun printOrSharePdf(context: Context, summary: StockOutReportSummary, isBn: Boolean = false) {
        try {
            val pdfFile = generatePdfReport(context, summary, isBn)
            val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
            if (printManager != null) {
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
                        val info = PrintDocumentInfo.Builder("Stock_Out_Report.pdf")
                            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
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
                            val inputStream = FileInputStream(pdfFile)
                            val outputStream = FileOutputStream(destination?.fileDescriptor)
                            val buf = ByteArray(1024)
                            var bytesRead: Int
                            while (inputStream.read(buf).also { bytesRead = it } > 0) {
                                outputStream.write(buf, 0, bytesRead)
                            }
                            callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                            inputStream.close()
                            outputStream.close()
                        } catch (e: Exception) {
                            callback?.onWriteFailed(e.message)
                        }
                    }
                }

                printManager.print("Stock_Out_Wastage_Report", printAdapter, PrintAttributes.Builder().build())
            } else {
                // Fallback to sharing PDF
                sharePdfFile(context, pdfFile, summary)
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to print/export PDF: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun sharePdfFile(context: Context, pdfFile: File, summary: StockOutReportSummary) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                pdfFile
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Stock-Out & Wastage Cost Report")
                putExtra(Intent.EXTRA_TEXT, "Attached is the official Stock-Out and Wastage Cost Report (${formatDateRange(summary.startTime, summary.endTime)}).")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share Stock-Out Report PDF"))
        } catch (e: Exception) {
            Toast.makeText(context, "Error sharing PDF: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
