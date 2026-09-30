package com.example

import android.graphics.Bitmap
import android.graphics.Color
import com.example.utils.EscPosPrinter
import com.example.utils.ThermalPrintingService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReceiptPhysicalCalibrationTest {

    @Test
    fun testNativeWordWrap_neverBreaksMidWord() {
        val storeName = "Kali Mata Variety Store"

        // 1. At 32 columns (standard 58mm / 384 dots width at 1x1 font):
        // "Kali Mata Variety Store" (23 chars) must fit on a single line
        val wrapped32 = EscPosPrinter.wrapTextToLines(storeName, 32)
        assertEquals(1, wrapped32.size)
        assertEquals("Kali Mata Variety Store", wrapped32[0])

        // 2. At 16 columns (if rendered at 2x width or narrow column):
        // Must wrap ONLY at space, never breaking "Variety" mid-word
        val wrapped16 = EscPosPrinter.wrapTextToLines(storeName, 16)
        assertEquals(2, wrapped16.size)
        assertEquals("Kali Mata", wrapped16[0])
        assertEquals("Variety Store", wrapped16[1])
        assertFalse(wrapped16[0].contains("Variet"))
        assertFalse(wrapped16[1].startsWith("y"))

        // 3. Long store header with multiple words at 32 columns:
        val longStoreName = "Kali Mata Variety Store Wholesale & Retail"
        val wrappedLong = EscPosPrinter.wrapTextToLines(longStoreName, 32)
        assertEquals(2, wrappedLong.size)
        assertEquals("Kali Mata Variety Store", wrappedLong[0])
        assertEquals("Wholesale & Retail", wrappedLong[1])
    }

    @Test
    fun testStoreNameAndItemsLineCountParity_EnglishVsBengali() {
        // Prepare back-to-back transaction receipts with identical items
        val itemsEn = listOf(
            ThermalPrintingService.ReceiptLineItem(nameEn = "SLD", nameBn = "", quantity = 1.0, unitType = "pcs", unitPrice = 9.0, subtotal = 9.0),
            ThermalPrintingService.ReceiptLineItem(nameEn = "Oli", nameBn = "", quantity = 1.0, unitType = "pcs", unitPrice = 185.0, subtotal = 185.0),
            ThermalPrintingService.ReceiptLineItem(nameEn = "Special Tea", nameBn = "", quantity = 2.0, unitType = "cups", unitPrice = 10.0, subtotal = 20.0),
            ThermalPrintingService.ReceiptLineItem(nameEn = "Parle-G", nameBn = "", quantity = 1.0, unitType = "pkt", unitPrice = 10.0, subtotal = 10.0)
        )

        val itemsBn = listOf(
            ThermalPrintingService.ReceiptLineItem(nameEn = "", nameBn = "ডিম", quantity = 1.0, unitType = "পিস", unitPrice = 9.0, subtotal = 9.0),
            ThermalPrintingService.ReceiptLineItem(nameEn = "", nameBn = "মিনিকোট চাল", quantity = 1.0, unitType = "কেজি", unitPrice = 50.0, subtotal = 50.0),
            ThermalPrintingService.ReceiptLineItem(nameEn = "", nameBn = "দার্জিলিং চা", quantity = 2.0, unitType = "কাপ", unitPrice = 10.0, subtotal = 20.0),
            ThermalPrintingService.ReceiptLineItem(nameEn = "", nameBn = "বিস্কুট", quantity = 1.0, unitType = "প্যাকেট", unitPrice = 10.0, subtotal = 10.0)
        )

        val dataEn = ThermalPrintingService.TransactionReceiptData(
            invoiceNo = "INV-00101",
            timestamp = 1710000000000L,
            receiptTitle = "CASH MEMO",
            items = itemsEn,
            subtotal = 224.0,
            grandTotal = 224.0,
            paidAmount = 224.0,
            dueAmount = 0.0,
            paymentMode = "CASH"
        )

        val dataBn = ThermalPrintingService.TransactionReceiptData(
            invoiceNo = "INV-00101",
            timestamp = 1710000000000L,
            receiptTitle = "ক্যাশ মেমো",
            items = itemsBn,
            subtotal = 89.0,
            grandTotal = 89.0,
            paidAmount = 89.0,
            dueAmount = 0.0,
            paymentMode = "নগদ"
        )

        val configEn = ThermalPrintingService.ThermalReceiptConfig(isBengali = false, showStoreHeader = false)
        val configBn = ThermalPrintingService.ThermalReceiptConfig(isBengali = true, showStoreHeader = false)
        val linesEn = ThermalPrintingService.formatTransactionReceiptHybridLines(dataEn, configEn)
        val linesBn = ThermalPrintingService.formatTransactionReceiptHybridLines(dataBn, configBn)

        // 1. Both store name headers (when tested as individual lines) must fit on 1 line
        val titleEn = EscPosPrinter.wrapTextToLines("Kali Mata Variety Store", 32)
        val titleBmp = EscPosPrinter.renderTextLineBitmap(
            EscPosPrinter.HybridReceiptLine.TextLine("কালী মাতা ভ্যারাইটি স্টোর", alignment = 1, isTitle = true),
            384,
            "NORMAL"
        )
        assertEquals("English title must fit on 1 line", 1, titleEn.size)
        assertNotNull(titleBmp)

        // 2. Both English and Bengali items (with length <= 16 chars) must use ThreeColumnLine
        val itemsTableEn = linesEn.filterIsInstance<EscPosPrinter.HybridReceiptLine.ThreeColumnLine>()
        val itemsTableBn = linesBn.filterIsInstance<EscPosPrinter.HybridReceiptLine.ThreeColumnLine>()

        // 1 table header + 4 items = 5 ThreeColumnLines
        assertEquals("English items table line count must match", 5, itemsTableEn.size)
        assertEquals("Bengali items table line count must match", 5, itemsTableBn.size)

        // 3. Total number of receipt structural sections/lines should be identical
        assertEquals("Overall line count must match between English and Bengali receipts", linesEn.size, linesBn.size)
    }

    @Test
    fun testPhysicalCharacterDimensionsAndMapping() {
        // Physical parameters for 58mm thermal printer (384 dots @ 203 DPI, 8.0 dots/mm):
        // 1 dot = 0.125 mm
        val dotsPerMm = 8.0

        // ESC/POS Native Font A (GS ! 0x00):
        // Width: 12 dots = 1.50 mm (32 chars / line on 384-dot paper)
        // Height: 24 dots = 3.00 mm
        val nativeWidthMm = 12.0 / dotsPerMm
        val nativeHeightMm = 24.0 / dotsPerMm
        assertEquals(1.50, nativeWidthMm, 0.01)
        assertEquals(3.00, nativeHeightMm, 0.01)

        // Bitmap Title (21f TextPaint):
        val titleLine = EscPosPrinter.HybridReceiptLine.TextLine("কালী মাতা ভ্যারাইটি স্টোর", alignment = 1, isTitle = true)
        val titleBmp = EscPosPrinter.renderTextLineBitmap(titleLine, 384, "NORMAL")
        val titleHeightMm = titleBmp.height.toDouble() / dotsPerMm

        // Height must be reasonably compact (within 2.5mm - 5.5mm range on standard devices/JVM)
        assertTrue("Title height in mm ($titleHeightMm) must be within 2.5mm - 5.5mm", titleHeightMm in 2.5..5.5)

        // Bitmap Body (14f TextPaint):
        val bodyLine = EscPosPrinter.HybridReceiptLine.ThreeColumnLine("মিনিকোট চাল", "১ কেজি", "₹৫০.০০")
        val bodyBmp = EscPosPrinter.renderThreeColumnLineBitmap(bodyLine, 384, "NORMAL")
        val bodyHeightMm = bodyBmp.height.toDouble() / dotsPerMm

        assertTrue("Body row height in mm ($bodyHeightMm) must be within 2.0mm - 6.0mm", bodyHeightMm in 2.0..6.0)
    }

    @Test
    fun testDividerBitmapHeightAndDashPattern() {
        val dashedDiv = EscPosPrinter.HybridReceiptLine.Divider(isDashed = true)
        val bmpDashed = EscPosPrinter.renderDividerBitmap(dashedDiv, 384)

        // Height must be exactly 12 dots (1.5mm)
        assertEquals(12, bmpDashed.height)
        assertEquals(384, bmpDashed.width)

        var hasBlackPixel = false
        var hasWhitePixel = false
        for (x in 20 until 360) {
            val p = bmpDashed.getPixel(x, 5)
            if (p == Color.BLACK) hasBlackPixel = true
            if (p == Color.WHITE) hasWhitePixel = true
        }
        assertTrue("Dashed line must contain black pixels", hasBlackPixel)
        assertTrue("Dashed line must contain white gaps", hasWhitePixel)
    }

    @Test
    fun testBuildHybridBytes_calibratedLineSpacing() {
        val lines = listOf(
            EscPosPrinter.HybridReceiptLine.TextLine("Kali Mata Variety Store", alignment = 1, isTitle = true),
            EscPosPrinter.HybridReceiptLine.Divider(isDashed = true),
            EscPosPrinter.HybridReceiptLine.ThreeColumnLine("SLD", "1 pcs", "Rs.9.00")
        )

        val bytes = EscPosPrinter.buildHybridBytesFromLines(
            lines = lines,
            widthDots = 384,
            fontSize = "NORMAL",
            feedLines = 2
        )

        assertNotNull(bytes)
        assertTrue("Generated bytes must not be empty", bytes.isNotEmpty())

        // Verify ESC 3 24 (0x1B, 0x33, 0x18) is present in the byte stream for 24-dot line spacing
        var foundEsc3_24 = false
        for (i in 0 until bytes.size - 2) {
            if (bytes[i] == 0x1B.toByte() && bytes[i + 1] == 0x33.toByte() && bytes[i + 2] == 0x18.toByte()) {
                foundEsc3_24 = true
                break
            }
        }
        assertTrue("Byte stream must configure calibrated line spacing ESC 3 24", foundEsc3_24)
    }

    @Test
    fun testCurrentPrinterMode_defaultsToReceiptMode() {
        // Must start in RECEIPT mode by default
        assertEquals(EscPosPrinter.PrinterMode.RECEIPT, EscPosPrinter.currentPrinterMode)
    }

    private fun helperContainsSequence(haystack: ByteArray, needle: ByteArray): Boolean {
        if (haystack.size >= needle.size) {
            for (i in 0..haystack.size - needle.size) {
                var match = true
                for (j in needle.indices) {
                    if (haystack[i + j] != needle[j]) {
                        match = false
                        break
                    }
                }
                if (match) return true
            }
        }
        return false
    }

    @Test
    fun testCustomerBillBytes_neverContainEscReceiptModePrefix() {
        // 1. English bill
        val linesEn = listOf(
            EscPosPrinter.HybridReceiptLine.TextLine("Kali Mata Variety Store", alignment = 1, isTitle = true),
            EscPosPrinter.HybridReceiptLine.Divider(isDashed = true),
            EscPosPrinter.HybridReceiptLine.ThreeColumnLine("SLD", "1 pcs", "Rs.9.00"),
            EscPosPrinter.HybridReceiptLine.TextLine("Thank You! Visit Again", alignment = 1, isBold = true)
        )
        val bytesEn = EscPosPrinter.buildHybridBytesFromLines(linesEn, 384, "NORMAL", "NORMAL", 3)
        assertFalse(
            "English bill must NOT contain ESC_RECEIPT_MODE_PREFIX",
            helperContainsSequence(bytesEn, EscPosPrinter.ESC_RECEIPT_MODE_PREFIX)
        )

        // 2. Bengali bill
        val linesBn = listOf(
            EscPosPrinter.HybridReceiptLine.TextLine("কালী মাতা ভ্যারাইটি স্টোর", alignment = 1, isTitle = true),
            EscPosPrinter.HybridReceiptLine.Divider(isDashed = true),
            EscPosPrinter.HybridReceiptLine.ThreeColumnLine("ডিম", "১ পিস", "₹৯.০০"),
            EscPosPrinter.HybridReceiptLine.TextLine("ধন্যবাদ! আবার আসবেন", alignment = 1, isBold = true)
        )
        val bytesBn = EscPosPrinter.buildHybridBytesFromLines(linesBn, 384, "NORMAL", "NORMAL", 3)
        assertFalse(
            "Bengali bill must NOT contain ESC_RECEIPT_MODE_PREFIX",
            helperContainsSequence(bytesBn, EscPosPrinter.ESC_RECEIPT_MODE_PREFIX)
        )

        // 3. Raster bitmap conversion
        val sampleBmp = android.graphics.Bitmap.createBitmap(384, 50, android.graphics.Bitmap.Config.ARGB_8888)
        val rasterBytes = EscPosPrinter.bitmapToEscPosRaster(sampleBmp, 384)
        assertFalse(
            "bitmapToEscPosRaster must NOT contain ESC_RECEIPT_MODE_PREFIX",
            helperContainsSequence(rasterBytes, EscPosPrinter.ESC_RECEIPT_MODE_PREFIX)
        )

        // 4. ThermalPrintingService 58mm raster
        val serviceRasterBytes = ThermalPrintingService.bitmapTo58mmEscPosRaster(sampleBmp)
        assertFalse(
            "ThermalPrintingService.bitmapTo58mmEscPosRaster must NOT contain ESC_RECEIPT_MODE_PREFIX",
            helperContainsSequence(serviceRasterBytes, EscPosPrinter.ESC_RECEIPT_MODE_PREFIX)
        )
    }

    @Test
    fun testQrCodeSizedForScannabilityWithQuietZoneMargin() {
        val upiPayload = "upi://pay?pa=store@upi&pn=Store&am=150.00"

        // 1. Generate QR code with calibrated 200px size (matching test template for 58mm scannability)
        val qrBmp = ThermalPrintingService.generateReceiptQrCode(upiPayload, 200, quietZonePadding = 6)
        assertNotNull("QR code bitmap must be generated", qrBmp)
        assertEquals("QR code width should be 200 dots", 200, qrBmp!!.width)
        assertEquals("QR code height should be 200 dots", 200, qrBmp.height)

        // 2. Verify white quiet-zone padding around edges (all 4 corners must be pure white)
        val topLeftPixel = qrBmp.getPixel(2, 2)
        val topRightPixel = qrBmp.getPixel(197, 2)
        val bottomLeftPixel = qrBmp.getPixel(2, 197)
        val bottomRightPixel = qrBmp.getPixel(197, 197)

        assertEquals("Quiet zone top-left must be white", Color.WHITE, topLeftPixel)
        assertEquals("Quiet zone top-right must be white", Color.WHITE, topRightPixel)
        assertEquals("Quiet zone bottom-left must be white", Color.WHITE, bottomLeftPixel)
        assertEquals("Quiet zone bottom-right must be white", Color.WHITE, bottomRightPixel)

        // 3. Verify in hybrid receipt line generation for both English and Bengali receipts
        val dummyData = ThermalPrintingService.TransactionReceiptData(
            invoiceNo = "INV-QR-01",
            timestamp = 1710000000000L,
            receiptTitle = "CASH MEMO",
            items = listOf(ThermalPrintingService.ReceiptLineItem("Tea", "চা", 1.0, "cup", 10.0, 10.0)),
            subtotal = 10.0,
            grandTotal = 10.0,
            paidAmount = 0.0,
            dueAmount = 10.0,
            upiId = "merchant@upi"
        )

        val linesEn = ThermalPrintingService.formatTransactionReceiptHybridLines(
            dummyData,
            ThermalPrintingService.ThermalReceiptConfig(isBengali = false, showPaymentQr = true)
        )
        val qrBlockEn = linesEn.filterIsInstance<EscPosPrinter.HybridReceiptLine.ImageBlock>().firstOrNull()
        assertNotNull("English receipt must contain QR image block", qrBlockEn)
        assertEquals("English receipt QR bitmap must be 200 dots wide", 200, qrBlockEn!!.bitmap.width)
        assertTrue("English receipt QR block must have isQrCode flag set", qrBlockEn.isQrCode)

        val linesBn = ThermalPrintingService.formatTransactionReceiptHybridLines(
            dummyData,
            ThermalPrintingService.ThermalReceiptConfig(isBengali = true, showPaymentQr = true)
        )
        val qrBlockBn = linesBn.filterIsInstance<EscPosPrinter.HybridReceiptLine.ImageBlock>().firstOrNull()
        assertNotNull("Bengali receipt must contain QR image block", qrBlockBn)
        assertEquals("Bengali receipt QR bitmap must be 200 dots wide", 200, qrBlockBn!!.bitmap.width)
        assertTrue("Bengali receipt QR block must have isQrCode flag set", qrBlockBn.isQrCode)
    }

    @Test
    fun testPaperFeedUsesEscDNCommand() {
        // Test paper feed command in buildHybridBytesFromLines (ESC d n = 0x1B 0x64 n)
        val hybridLines = listOf(
            EscPosPrinter.HybridReceiptLine.TextLine("Test Line", alignment = 0)
        )
        val bytes4Feed = EscPosPrinter.buildHybridBytesFromLines(hybridLines, 384, "NORMAL", "NORMAL", 4)
        val expectedFeedSequence = byteArrayOf(0x1B, 0x64, 0x04)
        assertTrue("Paper feed of 4 lines must emit ESC d 4", helperContainsSequence(bytes4Feed, expectedFeedSequence))

        // Also test ThermalPrintingService raster conversion
        val dummyBmp = Bitmap.createBitmap(384, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val rasterBytes = ThermalPrintingService.bitmapTo58mmEscPosRaster(dummyBmp, feedLines = 3)
        val expectedRasterFeedSequence = byteArrayOf(0x1B, 0x64, 0x03)
        assertTrue("Raster print job with 3 feed lines must emit ESC d 3", helperContainsSequence(rasterBytes, expectedRasterFeedSequence))
    }

    @Test
    fun testBengaliBitmapThresholdPreservesMatrasAndConjuncts() {
        // 1. Bengali title bitmap renders with increased font scale (23f vs 21f for English)
        val lineBn = EscPosPrinter.HybridReceiptLine.TextLine("কালী মাতা ভ্যারাইটি স্টোর", alignment = 1, isTitle = true)
        val bmpBn = EscPosPrinter.renderTextLineBitmap(lineBn, 384, "NORMAL")
        assertNotNull(bmpBn)
        assertTrue("Bengali title bitmap height must be at least 18 dots", bmpBn.height >= 18)

        // 2. Validate luminance thresholding behavior on anti-aliased matra strokes:
        // Anti-aliased font edges produce pixels in the luminance range 160-200.
        // A standard threshold of 150 washes these out to pure white.
        // The dedicated Bengali threshold (205) preserves them as crisp thermal dots.
        val testBmp = android.graphics.Bitmap.createBitmap(100, 10, android.graphics.Bitmap.Config.ARGB_8888)
        testBmp.eraseColor(Color.WHITE)

        // Draw a simulated anti-aliased horizontal matra stroke with luminance = 180
        val strokeColor = Color.rgb(180, 180, 180)
        for (x in 10..90) {
            testBmp.setPixel(x, 5, strokeColor)
        }

        val percentStandard = EscPosPrinter.calculate1BitBlackPixelPercentage(testBmp, 150)
        val percentBengali = EscPosPrinter.calculate1BitBlackPixelPercentage(testBmp, 205)

        assertEquals("Standard threshold 150 must wash out anti-aliased matra stroke (0.0% black)", 0.0, percentStandard, 0.001)
        assertTrue("Bengali threshold 205 must preserve anti-aliased matra stroke (> 5.0% black)", percentBengali > 5.0)

        // 3. Confirm that Bengali lines in buildHybridBytesFromLines emit raster bitmap commands
        val hybridLinesBn = listOf(
            EscPosPrinter.HybridReceiptLine.TextLine("চাল - ১ কেজি", isBold = true),
            EscPosPrinter.HybridReceiptLine.TwoColumnLine("মোট বিল", "₹৫০.০০", isBold = true)
        )
        val bytesBn = EscPosPrinter.buildHybridBytesFromLines(hybridLinesBn, 384, "NORMAL", "NORMAL", 3)
        // Raster command is GS v 0 (0x1D, 0x76, 0x30)
        assertTrue("Bengali receipt must emit GS v 0 raster commands", helperContainsSequence(bytesBn, byteArrayOf(0x1D, 0x76, 0x30)))
    }

    @Test
    fun testLivePrintPreview_LargeFontAndTestTemplate() {
        val testLines = EscPosPrinter.getTestReceiptHybridLines(
            isBengali = false,
            widthDots = 384,
            density = "150",
            fontSize = "LARGE"
        )
        val previewBmp = EscPosPrinter.renderHybridReceiptPreviewBitmap(
            lines = testLines,
            widthDots = 384,
            threshold = 150,
            fontSize = "LARGE",
            feedLines = 2
        )
        assertNotNull(previewBmp)
    }
}

