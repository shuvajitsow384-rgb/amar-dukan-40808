package com.example.utils

import android.annotation.SuppressLint
import android.util.Log
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.example.data.local.dao.PurchaseWithItems
import com.example.data.local.dao.SaleWithItems
import com.example.data.local.entities.Customer
import com.example.data.local.entities.LedgerEntry
import com.example.data.local.entities.SaleReturnWithItems
import com.example.data.local.entities.Supplier
import com.example.data.models.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*

object EscPosPrinter {

    data class BluetoothPrinterDevice(
        val name: String,
        val address: String
    )

    // --- Hardware Mode Command Byte Sequences (Dual-Mode / 2-in-1 Printers) ---

    /**
     * Explicit Receipt Mode command sequence:
     * 1. 1F 1B 1F 80 04 05 05: Vendor hardware switch to Continuous Receipt Mode (Xprinter / POS / Seznik clones)
     * 2. 1B 53: ESC S (Select Standard Continuous Receipt Mode)
     * 3. 1B 40: ESC @ (Initialize printer to standard receipt defaults)
     * 4. 1B 63 30 00: ESC c 0 0 (Disable paper label gap sensor)
     * 5. 1B 32: ESC 2 (Reset default 1/6-inch line spacing)
     */
    val ESC_RECEIPT_MODE_PREFIX = byteArrayOf(
        0x1F, 0x1B, 0x1F, 0x80.toByte(), 0x04, 0x05, 0x05,
        0x1B, 0x53,
        0x1B, 0x40,
        0x1B, 0x63, 0x30, 0x00,
        0x1B, 0x32
    )

    /**
     * Clean ESC/POS label initialization:
     * 1B 40 : ESC @ (Reset printer state to standard mode)
     * 1B 33 00 : ESC 3 0 (Set line spacing to 0 dots for seamless raster graphics)
     * Never sends test print / hex-dump commands (like 1D 28 41) or non-standard vendor bytes.
     */
    val ESC_LABEL_INIT = byteArrayOf(
        0x1B, 0x40,
        0x1B, 0x33, 0x00
    )

    @Deprecated("Use ESC_LABEL_INIT for clean label printing without hex-dump corruptions")
    val ESC_LABEL_MODE_PREFIX = ESC_LABEL_INIT

    /**
     * Discrete Label Gap Feed command:
     * 1D 0C: GS FF (Print buffered data and feed marked paper to the start of the next label)
     * Tells the printer's optical sensor to advance until the die-cut gap boundary is sensed.
     */
    val ESC_FEED_TO_GAP = byteArrayOf(0x1D, 0x0C)

    /**
     * Tracked hardware printer operating mode.
     * Starts as RECEIPT by default.
     * Only transitions to LABEL when a label print or label calibration command is actually dispatched.
     */
    enum class PrinterMode {
        RECEIPT,
        LABEL
    }

    @Volatile
    var currentPrinterMode: PrinterMode = PrinterMode.RECEIPT

    // --- Hybrid Native-Text + Bitmap Printing Data Structures & Classifiers ---

    sealed class HybridReceiptLine {
        data class TextLine(
            val text: String,
            val alignment: Int = 0, // 0=Left, 1=Center, 2=Right
            val isBold: Boolean = false,
            val isTitle: Boolean = false,
            val isSubtitle: Boolean = false
        ) : HybridReceiptLine()

        data class TwoColumnLine(
            val left: String,
            val right: String,
            val isBold: Boolean = false
        ) : HybridReceiptLine()

        data class ThreeColumnLine(
            val col1: String,
            val col2: String,
            val col3: String,
            val isBold: Boolean = false
        ) : HybridReceiptLine()

        data class Divider(
            val isDashed: Boolean = false,
            val isDouble: Boolean = false
        ) : HybridReceiptLine()

        data class ImageBlock(
            val bitmap: Bitmap,
            val isBarcode: Boolean = false,
            val isQrCode: Boolean = false
        ) : HybridReceiptLine()
    }

    enum class DarknessPreset {
        LIGHT, NORMAL, DARK, EXTRA_DARK
    }

    fun resolveDarknessPreset(density: String): DarknessPreset {
        val upper = density.trim().uppercase()
        val num = density.toIntOrNull()
        return when {
            upper == "LIGHT" -> DarknessPreset.LIGHT
            upper == "NORMAL" -> DarknessPreset.NORMAL
            upper == "DARK" -> DarknessPreset.DARK
            upper == "EXTRA_DARK" -> DarknessPreset.EXTRA_DARK
            num != null -> when {
                num < 140 -> DarknessPreset.LIGHT
                num < 160 -> DarknessPreset.NORMAL
                num < 175 -> DarknessPreset.DARK
                else -> DarknessPreset.EXTRA_DARK
            }
            else -> DarknessPreset.NORMAL
        }
    }

    /**
     * Line classifier for hybrid printing:
     * Returns true if the string consists solely of ASCII/Latin characters, digits,
     * punctuation, and common currency symbols (like ₹, €, $).
     * Returns false if the string contains Bengali or any other non-Latin Unicode characters.
     */
    fun isNativeTextLine(text: String): Boolean {
        if (text.isEmpty()) return true
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            // Check for Bengali Unicode block (0x0980 - 0x09FF)
            if (codePoint in 0x0980..0x09FF) {
                return false
            }
            // Check for other complex non-Latin scripts (Devanagari, Arabic, CJK, etc.)
            if (codePoint in 0x0900..0x0D7F || // Indic blocks
                codePoint in 0x0600..0x06FF || // Arabic
                codePoint in 0x4E00..0x9FFF || // CJK Ideographs
                codePoint in 0x1F300..0x1F9FF // Emoji
            ) {
                return false
            }
            // Common ASCII & Latin-1 & Currency & Punctuation allowed
            val isAllowed = (codePoint in 0x20..0x7E) ||
                    codePoint == 0x0A || codePoint == 0x0D || codePoint == 0x09 ||
                    codePoint == 0x20B9 || // ₹ Indian Rupee
                    codePoint == 0x20AC || // € Euro
                    codePoint in 0x00A0..0x00FF || // Latin-1
                    codePoint in 0x2010..0x2027 // Hyphens, quotes, bullets
            if (!isAllowed) {
                return false
            }
            i += Character.charCount(codePoint)
        }
        return true
    }

    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothPrinterDevice> {
        return try {
            val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
            val paired = bluetoothAdapter?.bondedDevices ?: emptySet()
            paired.map {
                BluetoothPrinterDevice(
                    name = it.name ?: "Unknown Device",
                    address = it.address
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun sendBytesToPrinter(
        deviceAddress: String,
        data: ByteArray,
        onStatusChange: ((String) -> Unit)? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val bluetoothAdapter: BluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            ?: return@withContext Result.failure(Exception("Bluetooth is not supported on this device"))

        if (!bluetoothAdapter.isEnabled) {
            return@withContext Result.failure(Exception("Bluetooth is turned off. Please enable Bluetooth in your device settings."))
        }

        val device: BluetoothDevice = try {
            bluetoothAdapter.getRemoteDevice(deviceAddress)
        } catch (e: Exception) {
            return@withContext Result.failure(Exception("Invalid printer Bluetooth address: $deviceAddress"))
        }

        val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // Standard SerialPortServiceClass_UUID

        // Adaptive draining delay based on raster payload size to ensure the printer platen motor
        // finishes printing and clearing the tear bar before closing the Bluetooth RFCOMM socket.
        val waitDelayMs = ((data.size / 10).coerceIn(2000, 4500)).toLong()

        val speed = StoreInfoManager.thermalPrintSpeed
        // ESC 7 n1 n2 n3 = 0x1B 0x37
        // n1 = max heating dots (7)
        // n2 = heating time (default ~100, higher = darker but slower)
        // n3 = heating interval (Slow=4, Normal=2, Fast=1)
        val heatingCommand = byteArrayOf(0x1B, 0x37, 0x07, 100.toByte(), speed.heatingInterval)

        fun writePayloadSafely(stream: OutputStream, payload: ByteArray) {
            val chunkSize = speed.chunkSize
            var offset = 0
            while (offset < payload.size) {
                val len = Math.min(chunkSize, payload.size - offset)
                stream.write(payload, offset, len)
                stream.flush()
                offset += len
                if (speed.delayMs > 0 && offset < payload.size) {
                    try { Thread.sleep(speed.delayMs) } catch (_: Exception) {}
                }
            }
        }

        suspend fun attemptSocketConnection(): Result<Boolean> {
            var socket: BluetoothSocket? = null
            var outputStream: OutputStream? = null
            return try {
                socket = device.createRfcommSocketToServiceRecord(uuid)
                socket.connect()
                outputStream = socket.outputStream

                // Only reset to RECEIPT mode if the printer was actually switched to Label mode earlier in the session
                if (currentPrinterMode == PrinterMode.LABEL) {
                    Log.i("EscPosPrinter", "Printer state is LABEL. Restoring to RECEIPT mode...")
                    outputStream.write(byteArrayOf(0x1B, 0x40)) // ESC @ (Reset to defaults)
                    outputStream.write(byteArrayOf(0x1B, 0x32)) // ESC 2 (Default line spacing)
                    outputStream.flush()
                    kotlinx.coroutines.delay(100)
                    currentPrinterMode = PrinterMode.RECEIPT
                }
                // Send the ESC/POS heating command once at connection time
                outputStream.write(heatingCommand)
                outputStream.flush()

                writePayloadSafely(outputStream, data)
                kotlinx.coroutines.delay(waitDelayMs)
                try { socket.close() } catch (_: Exception) {}
                Result.success(true)
            } catch (e: Exception) {
                try { outputStream?.close() } catch (_: Exception) {}
                try { socket?.close() } catch (_: Exception) {}

                // Fallback attempt with reflection for non-standard ESC/POS chips
                try {
                    val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    socket = m.invoke(device, 1) as? BluetoothSocket
                    socket?.connect()
                    outputStream = socket?.outputStream
                    if (outputStream != null) {
                        // Only reset to RECEIPT mode if the printer was actually switched to Label mode earlier in the session
                        if (currentPrinterMode == PrinterMode.LABEL) {
                            Log.i("EscPosPrinter", "Printer state is LABEL. Restoring to RECEIPT mode (fallback socket)...")
                            outputStream.write(byteArrayOf(0x1B, 0x40)) // ESC @ (Reset to defaults)
                            outputStream.write(byteArrayOf(0x1B, 0x32)) // ESC 2 (Default line spacing)
                            outputStream.flush()
                            kotlinx.coroutines.delay(100)
                            currentPrinterMode = PrinterMode.RECEIPT
                        }
                        // Send the ESC/POS heating command once at connection time
                        outputStream.write(heatingCommand)
                        outputStream.flush()

                        writePayloadSafely(outputStream, data)
                        kotlinx.coroutines.delay(waitDelayMs)
                        try { socket?.close() } catch (_: Exception) {}
                        Result.success(true)
                    } else {
                        Result.failure(e)
                    }
                } catch (fallbackEx: Exception) {
                    try { outputStream?.close() } catch (_: Exception) {}
                    try { socket?.close() } catch (_: Exception) {}
                    Result.failure(e)
                }
            }
        }

        // Attempt 1
        val firstResult = attemptSocketConnection()
        if (firstResult.isSuccess) {
            return@withContext firstResult
        }

        // Auto-reconnect retry: If connection failed or dropped mid-print, wait and retry once automatically
        onStatusChange?.invoke("Reconnecting to printer...")
        kotlinx.coroutines.delay(800)

        val retryResult = attemptSocketConnection()
        if (retryResult.isSuccess) {
            return@withContext retryResult
        }

        val deviceName = try { device.name ?: deviceAddress } catch (_: Exception) { deviceAddress }
        Result.failure(
            Exception("Could not connect to printer \"$deviceName\" ($deviceAddress). Please make sure the printer is turned on, sufficiently charged, and within Bluetooth range.")
        )
    }

    suspend fun testConnection(deviceAddress: String): Result<Boolean> = withContext(Dispatchers.IO) {
        // Send ESC @ (Printer Init Command - 2 bytes)
        val pingData = byteArrayOf(0x1B, 0x40)
        sendBytesToPrinter(deviceAddress, pingData)
    }

    suspend fun printReceipt(
        deviceAddress: String,
        saleWithItems: SaleWithItems,
        isBengali: Boolean = StoreInfoManager.isBillBengali(),
        paperSize: String = StoreInfoManager.pdfPaperSize
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val widthDots = if (paperSize == "THERMAL_58MM") 384 else 576
        val bytes = buildHybridReceiptBytes(
            saleWithItems = saleWithItems,
            isBengali = isBengali,
            widthDots = widthDots,
            density = StoreInfoManager.thermalPrinterDensity,
            fontSize = StoreInfoManager.thermalReceiptFontSize,
            feedLines = StoreInfoManager.thermalFeedLines
        )
        sendBytesToPrinter(deviceAddress, bytes)
    }

    suspend fun printSaleReturnReceipt(
        deviceAddress: String,
        returnWithItems: SaleReturnWithItems,
        isBengali: Boolean = StoreInfoManager.isBillBengali(),
        paperSize: String = StoreInfoManager.pdfPaperSize
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val widthDots = if (paperSize == "THERMAL_58MM") 384 else 576
        val bytes = buildHybridSaleReturnReceiptBytes(
            returnWithItems = returnWithItems,
            isBengali = isBengali,
            widthDots = widthDots,
            density = StoreInfoManager.thermalPrinterDensity,
            fontSize = StoreInfoManager.thermalReceiptFontSize,
            feedLines = StoreInfoManager.thermalFeedLines
        )
        sendBytesToPrinter(deviceAddress, bytes)
    }

    suspend fun printCreditStatement(
        deviceAddress: String,
        customer: Customer,
        ledgerEntries: List<LedgerEntry>,
        sales: List<SaleWithItems>,
        periodLabel: String = "All-Time",
        startTimestamp: Long? = null,
        endTimestamp: Long? = null,
        openingBalance: Double = 0.0,
        isBengali: Boolean = StoreInfoManager.isBillBengali(),
        paperSize: String = StoreInfoManager.pdfPaperSize,
        currentLiveBalance: Double? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val widthDots = if (paperSize == "THERMAL_58MM") 384 else 576
        val bitmap = generateCreditStatementBitmap(
            customer = customer,
            ledgerEntries = ledgerEntries,
            sales = sales,
            periodLabel = periodLabel,
            startTimestamp = startTimestamp,
            endTimestamp = endTimestamp,
            openingBalance = openingBalance,
            isBengali = isBengali,
            widthDots = widthDots,
            currentLiveBalance = currentLiveBalance
        )
        val bytes = bitmapToEscPosRaster(bitmap, widthDots)
        sendBytesToPrinter(deviceAddress, bytes)
    }

    suspend fun printSupplierStatement(
        deviceAddress: String,
        supplier: Supplier,
        ledgerEntries: List<LedgerEntry>,
        purchases: List<PurchaseWithItems>,
        periodLabel: String = "All-Time",
        startTimestamp: Long? = null,
        endTimestamp: Long? = null,
        openingBalance: Double = 0.0,
        isBengali: Boolean = StoreInfoManager.isBillBengali(),
        paperSize: String = StoreInfoManager.pdfPaperSize
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val widthDots = if (paperSize == "THERMAL_58MM") 384 else 576
        val bitmap = generateSupplierStatementBitmap(
            supplier = supplier,
            ledgerEntries = ledgerEntries,
            purchases = purchases,
            periodLabel = periodLabel,
            startTimestamp = startTimestamp,
            endTimestamp = endTimestamp,
            openingBalance = openingBalance,
            isBengali = isBengali,
            widthDots = widthDots
        )
        val bytes = bitmapToEscPosRaster(bitmap, widthDots)
        sendBytesToPrinter(deviceAddress, bytes)
    }

    suspend fun printOnlineOrderPackingSlip(
        deviceAddress: String,
        order: Order,
        isBengali: Boolean = StoreInfoManager.isBillBengali(),
        paperSize: String = StoreInfoManager.pdfPaperSize
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val widthDots = if (paperSize == "THERMAL_58MM") 384 else 576
        val bytes = buildHybridPackingSlipBytes(
            order = order,
            isBengali = isBengali,
            widthDots = widthDots,
            density = StoreInfoManager.thermalPrinterDensity,
            fontSize = StoreInfoManager.thermalReceiptFontSize,
            feedLines = StoreInfoManager.thermalFeedLines
        )
        sendBytesToPrinter(deviceAddress, bytes)
    }

    fun buildOnlineOrderPackingSlipData(
        order: Order,
        isBengali: Boolean
    ): Pair<ThermalPrintingService.TransactionReceiptData, ThermalPrintingService.ThermalReceiptConfig> {
        val lineItems = order.items.map { item ->
            ThermalPrintingService.ReceiptLineItem(
                nameEn = item.name,
                nameBn = "",
                quantity = item.quantity,
                unitType = item.unit,
                unitPrice = item.price,
                subtotal = item.subtotal
            )
        }

        val fulfillmentText = if (order.fulfillmentType == FulfillmentType.DELIVERY) {
            if (isBengali) "হোম ডেলিভারি" else "HOME DELIVERY"
        } else {
            if (isBengali) "দোকান পিকআপ" else "STORE PICKUP"
        }

        val addressText = order.deliveryAddress?.let { addr ->
            val textParts = listOfNotNull(addr.streetAddress, addr.landmark, addr.pinCode).filter { it.isNotBlank() }.joinToString(", ")
            if (addr.latitude != null && addr.longitude != null) {
                if (textParts.isNotBlank()) "$textParts [GPS: %.5f, %.5f]".format(addr.latitude, addr.longitude)
                else "GPS: %.5f, %.5f".format(addr.latitude, addr.longitude)
            } else {
                textParts
            }
        } ?: ""

        val notesCombined = buildString {
            append("Type: $fulfillmentText")
            if (!order.deliverySlot.isNullOrBlank()) {
                append("\nSlot: ${order.deliverySlot}")
            }
            if (addressText.isNotBlank()) {
                append("\nAddress: $addressText")
            }
            if (order.deliveryFee > 0.0) {
                append("\nDelivery Fee: ₹%.2f".format(Locale.US, order.deliveryFee))
            }
            if (order.customerNotes.isNotBlank()) {
                append("\nNote: ${order.customerNotes}")
            }
            if (!order.paymentReference.isNullOrBlank()) {
                append("\nUTR: ${order.paymentReference}")
            }
        }

        val isPaid = (order.paymentStatus == OrderPaymentStatus.PAID)
        val merchantUpi = StoreInfoManager.upiVpa.trim().ifBlank { StoreInfoManager.merchantUpiId.trim() }
        val payeeName = StoreInfoManager.merchantPayeeName.trim().ifBlank { StoreInfoManager.storeName.trim() }

        // Reuse exact same buildBillCheckoutUpiPayUrl pattern as Credit Statement
        val dynamicUpiUrl = if (!isPaid && merchantUpi.isNotBlank()) {
            StoreInfoManager.buildBillCheckoutUpiPayUrl(
                upiId = merchantUpi,
                payeeName = payeeName,
                amount = order.totalAmount,
                note = order.orderNumber
            )
        } else null

        val qrHeaderLabel = if (!isPaid && dynamicUpiUrl != null) {
            if (isBengali) {
                "স্ক্যান করে পরিশোধ করুন: ₹${formatBengaliDigits("%.2f".format(order.totalAmount))}\n(Scan to Pay)"
            } else {
                "Scan to Pay: ₹%.2f".format(Locale.US, order.totalAmount)
            }
        } else null

        val paymentStatusBanner = if (isPaid) {
            if (isBengali) "✓ পেমেন্ট সম্পন্ন হয়েছে (Payment Received)" else "✓ Payment Received"
        } else null

        val cleanOrderNo = if (order.orderNumber.startsWith("ORD", ignoreCase = true)) {
            order.orderNumber
        } else {
            "ORD-${order.orderNumber}"
        }

        val transactionData = ThermalPrintingService.TransactionReceiptData(
            receiptTitle = if (isBengali) "অনলাইন অর্ডার প্যাকিং স্লিপ" else "ONLINE ORDER PACKING SLIP",
            invoiceNo = cleanOrderNo,
            timestamp = order.createdAt,
            customerName = order.customerName,
            customerPhone = order.customerPhone,
            staffName = null,
            items = lineItems,
            subtotal = order.totalAmount,
            discount = 0.0,
            grandTotal = order.totalAmount,
            paymentMode = "${order.paymentMethod} (${order.paymentStatus})",
            paidAmount = if (isPaid) order.totalAmount else 0.0,
            dueAmount = if (!isPaid) order.totalAmount else 0.0,
            upiId = if (!isPaid) merchantUpi else null,
            upiPayeeName = if (!isPaid) payeeName else null,
            notes = notesCombined,
            customQrUrl = dynamicUpiUrl,
            qrHeaderLabel = qrHeaderLabel,
            paymentStatusBanner = paymentStatusBanner
        )

        val config = ThermalPrintingService.ThermalReceiptConfig(
            isBengali = isBengali,
            showStoreHeader = true,
            showCustomerInfo = true,
            showStaffInfo = false,
            showBarcode = true,
            showPaymentQr = (!isPaid && dynamicUpiUrl != null),
            showFooterNote = true,
            customFooterText = if (isBengali) "অর্ডার ডিসপ্যাচ করতে বারকোড স্ক্যান করুন\nধন্যবাদ!" else "Scan barcode to dispatch & fulfill\nThank You!"
        )

        return Pair(transactionData, config)
    }

    fun generateOnlineOrderPackingSlipBitmap(
        order: Order,
        isBengali: Boolean,
        widthDots: Int
    ): Bitmap {
        val (transactionData, config) = buildOnlineOrderPackingSlipData(order, isBengali)
        return ThermalPrintingService.formatTransactionReceipt58mm(transactionData, config)
    }

    fun buildHybridReceiptBytes(
        saleWithItems: SaleWithItems,
        isBengali: Boolean,
        widthDots: Int = 384,
        density: String = StoreInfoManager.thermalPrinterDensity,
        fontSize: String = StoreInfoManager.thermalReceiptFontSize,
        feedLines: Int = StoreInfoManager.thermalFeedLines
    ): ByteArray {
        val config = ThermalPrintingService.ThermalReceiptConfig(isBengali = isBengali)
        val lines = ThermalPrintingService.formatSaleReceiptHybridLines(saleWithItems, config)
        return buildHybridBytesFromLines(lines, widthDots, density, fontSize, feedLines)
    }

    fun buildHybridTransactionReceiptBytes(
        data: ThermalPrintingService.TransactionReceiptData,
        config: ThermalPrintingService.ThermalReceiptConfig,
        widthDots: Int = 384,
        density: String = StoreInfoManager.thermalPrinterDensity,
        fontSize: String = StoreInfoManager.thermalReceiptFontSize,
        feedLines: Int = StoreInfoManager.thermalFeedLines
    ): ByteArray {
        val lines = ThermalPrintingService.formatTransactionReceiptHybridLines(data, config)
        return buildHybridBytesFromLines(lines, widthDots, density, fontSize, feedLines)
    }

    fun buildHybridPackingSlipBytes(
        order: Order,
        isBengali: Boolean,
        widthDots: Int = 384,
        density: String = StoreInfoManager.thermalPrinterDensity,
        fontSize: String = StoreInfoManager.thermalReceiptFontSize,
        feedLines: Int = StoreInfoManager.thermalFeedLines
    ): ByteArray {
        val (data, config) = buildOnlineOrderPackingSlipData(order, isBengali)
        val lines = ThermalPrintingService.formatTransactionReceiptHybridLines(data, config)
        return buildHybridBytesFromLines(lines, widthDots, density, fontSize, feedLines)
    }

    fun buildSaleReturnReceiptData(
        returnWithItems: SaleReturnWithItems,
        isBengali: Boolean
    ): Pair<ThermalPrintingService.TransactionReceiptData, ThermalPrintingService.ThermalReceiptConfig> {
        val saleReturn = returnWithItems.saleReturn
        val isRepl = saleReturn.type.equals("REPLACEMENT", ignoreCase = true)
        val title = if (isRepl) {
            if (isBengali) "পণ্য পরিবর্তন ভাউচার" else "EXCHANGE / REPLACEMENT VOUCHER"
        } else {
            if (isBengali) "পণ্য ফেরত ভাউচার" else "SALES RETURN VOUCHER"
        }

        val banner = if (saleReturn.netAmount > 0) {
            if (isBengali) "গ্রাহককে রিফান্ড: ₹%.2f (%s)".format(saleReturn.netAmount, saleReturn.refundPaymentMode)
            else "REFUND TO CUSTOMER: ₹%.2f (%s)".format(saleReturn.netAmount, saleReturn.refundPaymentMode)
        } else if (saleReturn.netAmount < 0) {
            if (isBengali) "গ্রাহকের থেকে সংগ্রহ: ₹%.2f (%s)".format(kotlin.math.abs(saleReturn.netAmount), saleReturn.refundPaymentMode)
            else "COLLECTED FROM CUSTOMER: ₹%.2f (%s)".format(kotlin.math.abs(saleReturn.netAmount), saleReturn.refundPaymentMode)
        } else {
            if (isBengali) "সমান বিনিময় (EVEN EXCHANGE)" else "EVEN EXCHANGE (₹0.00)"
        }

        val items = returnWithItems.items.map { item ->
            ThermalPrintingService.ReceiptLineItem(
                nameEn = if (item.isReplacement) "[NEW] ${item.productNameEn}" else "[RET] ${item.productNameEn}",
                nameBn = if (item.isReplacement) "[নতুন] ${item.productNameBn.ifBlank { item.productNameEn }}" else "[ফেরত] ${item.productNameBn.ifBlank { item.productNameEn }}",
                quantity = item.quantity,
                unitType = item.unitType,
                unitPrice = item.unitPrice,
                subtotal = item.subtotal
            )
        }

        val notesCombined = buildString {
            if (saleReturn.totalReturnedAmount > 0.0) {
                append("Total Returned: ₹%.2f".format(saleReturn.totalReturnedAmount))
            }
            if (saleReturn.totalReplacementAmount > 0.0) {
                if (isNotEmpty()) append(" | ")
                append("Total Replaced: ₹%.2f".format(saleReturn.totalReplacementAmount))
            }
            if (!saleReturn.notes.isNullOrBlank()) {
                if (isNotEmpty()) append("\nReason: ")
                append(saleReturn.notes)
            }
        }

        val data = ThermalPrintingService.TransactionReceiptData(
            receiptTitle = title,
            invoiceNo = "${saleReturn.id} (Bill #${saleReturn.saleId.takeLast(8)})",
            timestamp = saleReturn.datetime,
            customerName = saleReturn.customerName,
            items = items,
            subtotal = saleReturn.totalReturnedAmount,
            discount = 0.0,
            taxAmount = 0.0,
            grandTotal = kotlin.math.abs(saleReturn.netAmount),
            paymentMode = saleReturn.refundPaymentMode,
            paidAmount = kotlin.math.abs(saleReturn.netAmount),
            dueAmount = 0.0,
            notes = notesCombined,
            paymentStatusBanner = banner
        )

        val config = ThermalPrintingService.ThermalReceiptConfig(
            isBengali = isBengali,
            showStoreHeader = true,
            showCustomerInfo = true,
            showStaffInfo = false,
            showBarcode = true,
            showPaymentQr = false,
            showFooterNote = true,
            customFooterText = if (isBengali) "পণ্য ফেরত বা পরিবর্তনের রসিদ সংরক্ষণ করুন\nধন্যবাদ!" else "Keep voucher for your return records\nThank You!"
        )

        return Pair(data, config)
    }

    fun buildHybridSaleReturnReceiptBytes(
        returnWithItems: SaleReturnWithItems,
        isBengali: Boolean,
        widthDots: Int = 384,
        density: String = StoreInfoManager.thermalPrinterDensity,
        fontSize: String = StoreInfoManager.thermalReceiptFontSize,
        feedLines: Int = StoreInfoManager.thermalFeedLines
    ): ByteArray {
        val (data, config) = buildSaleReturnReceiptData(returnWithItems, isBengali)
        val lines = ThermalPrintingService.formatTransactionReceiptHybridLines(data, config)
        return buildHybridBytesFromLines(lines, widthDots, density, fontSize, feedLines)
    }

    fun getTestReceiptHybridLines(
        isBengali: Boolean,
        widthDots: Int = 384,
        density: String = StoreInfoManager.thermalPrinterDensity,
        fontSize: String = StoreInfoManager.thermalReceiptFontSize
    ): List<HybridReceiptLine> {
        val storeName = StoreInfoManager.getStoreDisplayName(isBengali).ifBlank { "DUKAAN POS" }
        val lines = mutableListOf<HybridReceiptLine>()

        // 1. Store Header & Title
        lines.add(HybridReceiptLine.TextLine(storeName, alignment = 1, isBold = true, isTitle = true))
        val testTitle = if (isBengali) "৫8mm ব্লুটুথ থার্মাল প্রিন্টার টেস্ট" else "58mm Thermal Printer Test"
        lines.add(HybridReceiptLine.TextLine(testTitle, alignment = 1, isBold = true))
        val paperLabel = if (widthDots == 384) "58mm (384 Dots @ 203 DPI)" else "80mm (576 Dots @ 203 DPI)"
        lines.add(HybridReceiptLine.TextLine(paperLabel, alignment = 1, isSubtitle = true))
        lines.add(HybridReceiptLine.Divider(isDashed = false))

        // 2. Hybrid Mode Verification Header
        lines.add(HybridReceiptLine.TextLine("HYBRID PRINT ENGINE ACTIVE", alignment = 1, isBold = true))
        lines.add(HybridReceiptLine.TextLine("Native ASCII + 1-Bit Raster", alignment = 1, isSubtitle = true))
        lines.add(HybridReceiptLine.Divider(isDashed = true))

        // 3. Metadata
        val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        val dateRaw = sdf.format(Date())
        val dateDisplay = if (isBengali) formatBengaliDigits(dateRaw) else dateRaw
        lines.add(HybridReceiptLine.TwoColumnLine(if (isBengali) "তারিখ:" else "Date:", dateDisplay))
        val darknessPreset = resolveDarknessPreset(density)
        lines.add(HybridReceiptLine.TwoColumnLine(
            if (isBengali) "প্রিন্ট ডেনসিটি:" else "Darkness:",
            "$density ($darknessPreset)"
        ))
        lines.add(HybridReceiptLine.TwoColumnLine(
            if (isBengali) "ফন্ট সাইজ:" else "Font Size:",
            fontSize
        ))
        lines.add(HybridReceiptLine.Divider(isDashed = false))

        // 4. Test Table with English items and Bengali mixed line
        lines.add(HybridReceiptLine.ThreeColumnLine("Item", "Qty", "Price", isBold = true))
        lines.add(HybridReceiptLine.Divider(isDashed = true))
        lines.add(HybridReceiptLine.ThreeColumnLine("Special Tea", "2 cups", "Rs.20.00"))
        // Bengali line (triggers bitmap fallback)
        lines.add(HybridReceiptLine.TextLine("দার্জিলিং স্পেশাল চা (Bengali)", alignment = 0, isBold = true))
        lines.add(HybridReceiptLine.TwoColumnLine("  1 cup", "Rs.35.00"))
        lines.add(HybridReceiptLine.ThreeColumnLine("Parle-G Biscuit", "1 pkt", "Rs.10.00"))
        lines.add(HybridReceiptLine.Divider(isDashed = false))

        // 5. Totals
        lines.add(HybridReceiptLine.TwoColumnLine("Total Amount:", "Rs.65.00", isBold = true))
        lines.add(HybridReceiptLine.TwoColumnLine("Payment Mode:", "CASH (Paid)", isBold = true))
        lines.add(HybridReceiptLine.Divider(isDouble = true))

        // 6. Bengali Unicode verification banner
        val bnSample = if (isBengali) "বাংলা টেক্সট টেস্ট: ক্যাশ মেমো ও খাতা" else "Bengali Text Test: ক্যাশ মেমো ও খাতা"
        lines.add(HybridReceiptLine.TextLine(bnSample, alignment = 1, isBold = true))
        lines.add(HybridReceiptLine.Divider(isDashed = false))

        // 7. Diagnostic QR Code
        val qrSize = if (widthDots == 384) 200 else 240
        val qrPayload = if (StoreInfoManager.merchantUpiId.isNotBlank()) {
            StoreInfoManager.buildUpiPayUrl(
                upiId = StoreInfoManager.merchantUpiId,
                payeeName = StoreInfoManager.merchantPayeeName.ifBlank { storeName },
                amount = 1.0,
                note = "Printer Test"
            )
        } else {
            "upi://pay?pa=printer.test@upi&pn=${URLEncoder.encode(storeName, "UTF-8")}&am=1.0&tn=ThermalTest"
        }
        val qrBmp = generateQrBitmap(qrPayload, qrSize)
        if (qrBmp != null) {
            lines.add(HybridReceiptLine.ImageBlock(qrBmp, isQrCode = true))
            lines.add(HybridReceiptLine.TextLine("Diagnostic QR Code", alignment = 1, isSubtitle = true))
            lines.add(HybridReceiptLine.Divider(isDashed = false))
        }

        // 8. Footer
        val greeting = if (isBengali) "ধন্যবাদ! আবার আসবেন" else "Thank You! Visit Again"
        lines.add(HybridReceiptLine.TextLine(greeting, alignment = 1, isBold = true))

        return lines
    }

    fun getSampleReceiptHybridLines(
        isBengali: Boolean,
        widthDots: Int = 384,
        density: String = StoreInfoManager.thermalPrinterDensity,
        fontSize: String = StoreInfoManager.thermalReceiptFontSize
    ): List<HybridReceiptLine> {
        val storeName = if (isBengali) StoreInfoManager.storeNameBn.ifBlank { "কালী মাতা ভ্যারাইটি স্টোর" } else StoreInfoManager.storeName.ifBlank { "KALI MATA VARIETY STORE" }
        val sampleItem1 = if (isBengali) "১. বাসমতী চাল (৫ কেজি)" to "₹৪৫০.০০" else "1. Basmati Rice (5kg)" to "Rs 450.00"
        val sampleItem2 = if (isBengali) "২. সরিষার তেল (১ লিটার)" to "₹১৭৫.০০" else "2. Mustard Oil (1L)" to "Rs 175.00"
        val totalText = if (isBengali) "মোট প্রদেয়:" to "₹৬২৫.০০" else "Total Payable:" to "Rs 625.00"
        val footerText = if (isBengali) "ধন্যবাদ! আবার আসবেন।" else "Thank You! Visit Again"

        val lines = mutableListOf<HybridReceiptLine>()
        lines.add(HybridReceiptLine.TextLine(storeName, alignment = 1, isBold = true, isTitle = true))
        lines.add(HybridReceiptLine.Divider(isDashed = false))
        lines.add(HybridReceiptLine.TwoColumnLine(sampleItem1.first, sampleItem1.second))
        lines.add(HybridReceiptLine.TwoColumnLine(sampleItem2.first, sampleItem2.second))
        lines.add(HybridReceiptLine.Divider(isDashed = true))
        lines.add(HybridReceiptLine.TwoColumnLine(totalText.first, totalText.second, isBold = true))
        lines.add(HybridReceiptLine.Divider(isDouble = true))
        lines.add(HybridReceiptLine.TextLine(footerText, alignment = 1, isBold = true))
        return lines
    }

    fun buildHybridTestReceiptBytes(
        isBengali: Boolean,
        widthDots: Int = 384,
        density: String = StoreInfoManager.thermalPrinterDensity,
        fontSize: String = StoreInfoManager.thermalReceiptFontSize,
        feedLines: Int = StoreInfoManager.thermalFeedLines
    ): ByteArray {
        val lines = getTestReceiptHybridLines(isBengali, widthDots, density, fontSize)
        return buildHybridBytesFromLines(lines, widthDots, density, fontSize, feedLines)
    }

    suspend fun testPrint(
        deviceAddress: String,
        isBengali: Boolean = StoreInfoManager.isBillBengali(),
        paperSize: String = StoreInfoManager.pdfPaperSize
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val widthDots = if (paperSize == "THERMAL_58MM") 384 else 576
        val bytes = buildHybridTestReceiptBytes(
            isBengali = isBengali,
            widthDots = widthDots,
            density = StoreInfoManager.thermalPrinterDensity,
            fontSize = StoreInfoManager.thermalReceiptFontSize,
            feedLines = StoreInfoManager.thermalFeedLines
        )
        sendBytesToPrinter(deviceAddress, bytes)
    }

    /**
     * Feeds paper by [lines] lines on the target Bluetooth printer.
     * Uses universal ESC/POS line feed commands (0x0A) with reset line spacing (ESC 2)
     * which is guaranteed to work across all thermal printers (58mm, 80mm, portable, desktop).
     */
    suspend fun feedPaper(
        deviceAddress: String,
        lines: Int = StoreInfoManager.thermalFeedLines
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val safeLines = lines.coerceIn(1, 8)
        val baos = ByteArrayOutputStream()
        // Reset default line spacing (ESC 2 = 1/6 inch)
        baos.write(byteArrayOf(0x1B, 0x32))
        repeat(safeLines) {
            baos.write(0x0A)
        }
        sendBytesToPrinter(deviceAddress, baos.toByteArray())
    }

    /**
     * Morphological 3x3 Max-Filter Dilation for 1-bit thermal printer raster bitmaps.
     *
     * Thickens each black pixel (foreground dot) by 1px in a 3x3 neighborhood before sending
     * to the printer. On budget thermal printers (such as Seznik 58mm and generic POS boards)
     * where ESC 7 heating parameters are ignored by the controller firmware, single-pixel thin
     * strokes (delicate Bengali matras, complex conjuncts, small QR modules, logo lines)
     * fail to deliver enough physical heat to darken the thermal paper coating.
     *
     * Dilation expands every 1-bit black dot into adjacent pixels, providing guaranteed
     * physical stroke thickness and deep ink coverage regardless of whether the printer
     * honors ESC 7 heating/density commands.
     */
    fun dilateBitmap(source: Bitmap, thresholdVal: Int = 180): Bitmap {
        return try {
            val width = source.width
            val height = source.height
            if (width <= 0 || height <= 0) return source

            val srcPixels = IntArray(width * height)
            source.getPixels(srcPixels, 0, width, 0, 0, width, height)

            // 1. Identify all black pixels based on luminance and alpha
            val isBlack = BooleanArray(width * height)
            var hasAnyBlack = false
            for (i in 0 until width * height) {
                val color = srcPixels[i]
                val alpha = (color ushr 24) and 0xFF
                if (alpha > 30) {
                    val r = (color shr 16) and 0xFF
                    val g = (color shr 8) and 0xFF
                    val b = color and 0xFF
                    val effR = (r * alpha + 255 * (255 - alpha)) / 255
                    val effG = (g * alpha + 255 * (255 - alpha)) / 255
                    val effB = (b * alpha + 255 * (255 - alpha)) / 255
                    val lum = (effR * 299 + effG * 587 + effB * 114) / 1000
                    if (lum < thresholdVal) {
                        isBlack[i] = true
                        hasAnyBlack = true
                    }
                }
            }

            if (!hasAnyBlack) {
                return source
            }

            // 2. Horizontal 1-dot dilation (1 dot rightward): thickens vertical strokes like ESC G / double-strike
            // without closing vertical loops/counters in glyphs (prevents 'e', 'a', 'o', '0' from becoming solid black blobs).
            val outBlack = isBlack.clone()
            for (y in 0 until height) {
                val yOffset = y * width
                for (x in 0 until width - 1) {
                    if (isBlack[yOffset + x]) {
                        outBlack[yOffset + x + 1] = true
                    }
                }
            }

            // 3. Construct 1-bit high-contrast output bitmap (0xFF000000 for black, 0xFFFFFFFF for white)
            val outPixels = IntArray(width * height)
            for (i in 0 until width * height) {
                outPixels[i] = if (outBlack[i]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
            Bitmap.createBitmap(outPixels, width, height, Bitmap.Config.ARGB_8888)
        } catch (_: Throwable) {
            source
        }
    }

    /**
     * Builds back-to-back ESC 7 comparison test patterns:
     * 1. Absolute minimum heating (n1=1, n2=10, n3=1)
     * 2. Absolute maximum heating (n1=7, n2=255, n3=20)
     * Followed by demonstrations of the independent fixes (Native Bold ESC E 1 and 3x3 Dilation).
     * If blocks 1 and 2 look identical on physical paper, the printer board ignores ESC 7.
     */
    fun buildEsc7ComparisonTestBytes(widthDots: Int = 384): ByteArray {
        val baos = ByteArrayOutputStream()
        val maxChars = if (widthDots == 384) 32 else 48
        val divider = "-".repeat(maxChars)

        // Initialize printer
        baos.write(byteArrayOf(0x1B, 0x40)) // ESC @
        baos.write(byteArrayOf(0x1B, 0x33, 0x18)) // ESC 3 24 line spacing

        // Title Header
        baos.write(byteArrayOf(0x1B, 0x61, 0x01)) // Center
        baos.write(byteArrayOf(0x1B, 0x45, 0x01)) // Bold ON
        baos.write("=== ESC 7 HEATING TEST ===\n".toByteArray(Charsets.US_ASCII))
        baos.write(byteArrayOf(0x1B, 0x45, 0x00)) // Bold OFF
        baos.write("Seznik 58mm Hardware Diagnostic\n".toByteArray(Charsets.US_ASCII))
        baos.write("$divider\n".toByteArray(Charsets.US_ASCII))

        // --- BLOCK 1: ABSOLUTE MINIMUM ESC 7 HEATING ---
        baos.write(byteArrayOf(0x1B, 0x37, 0x01, 0x0A, 0x01)) // n1=1, n2=10, n3=1
        baos.write(byteArrayOf(0x12, 0x23, 0x00)) // DC2 # 0
        baos.write(byteArrayOf(0x1B, 0x45, 0x00)) // Native Bold OFF
        baos.write(byteArrayOf(0x1B, 0x61, 0x00)) // Left
        baos.write("[1] ABSOLUTE MINIMUM HEATING\n".toByteArray(Charsets.US_ASCII))
        baos.write("Params: n1=1, n2=10, n3=1\n".toByteArray(Charsets.US_ASCII))
        baos.write("Text: ABCDEFGHIJKLMNOP 0123456789\n".toByteArray(Charsets.US_ASCII))
        baos.write("Faint Test: The Quick Brown Fox\n".toByteArray(Charsets.US_ASCII))

        val minBnBmp = renderTextLineBitmap(
            HybridReceiptLine.TextLine("বাংলা টেক্সট টেস্ট (ন্যূনতম হিট)", alignment = 0),
            widthDots,
            "NORMAL"
        )
        appendRasterBitmapToStream(baos, minBnBmp, widthDots, thresholdVal = 130, lineSpacingDots = 24)
        baos.write("\n$divider\n".toByteArray(Charsets.US_ASCII))
        baos.write(0x0A)

        // --- BLOCK 2: ABSOLUTE MAXIMUM ESC 7 HEATING ---
        baos.write(byteArrayOf(0x1B, 0x37, 0x07, 0xFF.toByte(), 0x14)) // n1=7, n2=255, n3=20
        baos.write(byteArrayOf(0x12, 0x23, 0x1F)) // DC2 # 31
        baos.write(byteArrayOf(0x1B, 0x45, 0x00)) // Native Bold OFF
        baos.write(byteArrayOf(0x1B, 0x61, 0x00)) // Left
        baos.write("[2] ABSOLUTE MAXIMUM HEATING\n".toByteArray(Charsets.US_ASCII))
        baos.write("Params: n1=7, n2=255, n3=20\n".toByteArray(Charsets.US_ASCII))
        baos.write("Text: ABCDEFGHIJKLMNOP 0123456789\n".toByteArray(Charsets.US_ASCII))
        baos.write("Faint Test: The Quick Brown Fox\n".toByteArray(Charsets.US_ASCII))

        val maxBnBmp = renderTextLineBitmap(
            HybridReceiptLine.TextLine("বাংলা টেক্সট টেস্ট (সর্বোচ্চ হিট)", alignment = 0),
            widthDots,
            "NORMAL"
        )
        appendRasterBitmapToStream(baos, maxBnBmp, widthDots, thresholdVal = 130, lineSpacingDots = 24)
        baos.write("\n$divider\n".toByteArray(Charsets.US_ASCII))

        // --- EVALUATION INSTRUCTION & ACTIVE FIX DEMO ---
        baos.write(byteArrayOf(0x1B, 0x61, 0x01)) // Center
        baos.write(byteArrayOf(0x1B, 0x45, 0x01)) // Bold ON
        baos.write("--- DIAGNOSTIC VERDICT ---\n".toByteArray(Charsets.US_ASCII))
        baos.write(byteArrayOf(0x1B, 0x45, 0x00)) // Bold OFF
        baos.write("If [1] and [2] look identical:\n".toByteArray(Charsets.US_ASCII))
        baos.write("Printer IGNORES ESC 7 heating!\n".toByteArray(Charsets.US_ASCII))
        baos.write("$divider\n".toByteArray(Charsets.US_ASCII))

        baos.write(byteArrayOf(0x1B, 0x61, 0x00)) // Left
        baos.write("SEZNIK 58MM FIXES (ACTIVE):\n".toByteArray(Charsets.US_ASCII))

        // Demo Fix 1: Native Bold
        baos.write(byteArrayOf(0x1B, 0x45, 0x01)) // ESC E 1
        baos.write("Fix 1: ESC E 1 Bold Font (Heavy)\n".toByteArray(Charsets.US_ASCII))
        baos.write(byteArrayOf(0x1B, 0x45, 0x00)) // ESC E 0

        // Demo Fix 2: Bengali Text Optimization (High Contrast Font Scale & Calibrated Threshold, No Smear Dilation)
        baos.write("Fix 2: High Contrast Font & Threshold:\n".toByteArray(Charsets.US_ASCII))
        val bnFixedBmp = renderTextLineBitmap(
            HybridReceiptLine.TextLine("বাংলা হরফ (উন্নত থ্রেশহোল্ড ও ফন্ট সাইজ)", alignment = 0, isBold = true),
            widthDots,
            "NORMAL"
        )
        val dilatedBn = bnFixedBmp
        appendRasterBitmapToStream(baos, dilatedBn, widthDots, thresholdVal = 205, lineSpacingDots = 24)

        baos.write("\nFix 3: Speed set to 'Slow' for\n".toByteArray(Charsets.US_ASCII))
        baos.write("maximum head heating dwell time.\n".toByteArray(Charsets.US_ASCII))

        // Reset text formatting
        baos.write(byteArrayOf(0x1B, 0x45, 0x00))
        baos.write(byteArrayOf(0x1B, 0x61, 0x00))
        baos.write(byteArrayOf(0x1B, 0x32))

        // Feed
        val linesToFeed = StoreInfoManager.thermalFeedLines.coerceIn(1, 8)
        baos.write(byteArrayOf(0x1B, 0x32))
        repeat(linesToFeed) {
            baos.write(0x0A)
        }

        return baos.toByteArray()
    }

    suspend fun testPrintEsc7Comparison(
        deviceAddress: String,
        paperSize: String = StoreInfoManager.pdfPaperSize
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val widthDots = if (paperSize == "THERMAL_58MM") 384 else 576
        val bytes = buildEsc7ComparisonTestBytes(widthDots)
        sendBytesToPrinter(deviceAddress, bytes)
    }

    // =========================================================================
    // HYBRID NATIVE-TEXT + BITMAP PRINT ENGINE IMPLEMENTATION
    // =========================================================================

    fun buildHybridBytesFromLines(
        lines: List<HybridReceiptLine>,
        widthDots: Int = 384,
        density: String = StoreInfoManager.thermalPrinterDensity,
        fontSize: String = StoreInfoManager.thermalReceiptFontSize,
        feedLines: Int = StoreInfoManager.thermalFeedLines
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        val darkness = resolveDarknessPreset(density)
        val thresholdVal = density.toIntOrNull() ?: when (darkness) {
            DarknessPreset.LIGHT -> 130
            DarknessPreset.NORMAL -> 150
            DarknessPreset.DARK -> 165
            DarknessPreset.EXTRA_DARK -> 180
        }
        // Dedicated Bengali bitmap threshold:
        // Bengali script has delicate horizontal matras (the top bar) and intricate conjuncts.
        // On a 203 DPI thermal printer, standard English threshold (~150) washes out anti-aliased
        // edge pixels, causing matras to disconnect or disappear completely.
        // A dedicated threshold (205-215) ensures even light-gray anti-aliased edge pixels (luminance < 205)
        // are converted to solid black dots, keeping all matras and conjuncts fully intact.
        val bengaliThresholdVal = maxOf(thresholdVal + 45, 205).coerceIn(195, 220)
        val speed = StoreInfoManager.thermalPrintSpeed
        val maxChars = if (widthDots == 384) 32 else 48

        // 1. Initialize printer to standard defaults (ESC @) - no vendor mode switch prefix
        baos.write(byteArrayOf(0x1B, 0x40))

        // 2. ESC 7 - Set optimal heating parameters from speed preset
        baos.write(byteArrayOf(0x1B, 0x37, 0x07, 100.toByte(), speed.heatingInterval))

        // 3. DC2 # - Set thermal print density (0x14 = standard)
        baos.write(byteArrayOf(0x12, 0x23, 0x14.toByte()))

        // 4. Default character font: ESC M 0 (Font A)
        baos.write(byteArrayOf(0x1B, 0x4D, 0x00))

        // Default to ESC/POS Bold mode (ESC E 1) for native ASCII text to guarantee heavy, dark strokes
        if (StoreInfoManager.thermalNativeBoldMode) {
            baos.write(byteArrayOf(0x1B, 0x45, 0x01))
        }

        // 5. Calibrated line spacing: ESC 3 n (24 dots for normal, 26 dots for large)
        // 24 dots @ 203 DPI = 3.0mm, precisely matching the 20-24 dot bitmap row height.
        val lineSpacingDots = if (fontSize == "LARGE") 26 else 24
        baos.write(byteArrayOf(0x1B, 0x33, lineSpacingDots.toByte()))

        for (line in lines) {
            when (line) {
                is HybridReceiptLine.Divider -> {
                    // Render graphical divider bitmap for identical 12-dot vertical section spacing
                    // and pixel-matched dash pattern across both native-text and bitmap modes.
                    val divBitmap = renderDividerBitmap(line, widthDots)
                    val dilated = if (StoreInfoManager.thermalRasterDilation) dilateBitmap(divBitmap, thresholdVal) else divBitmap
                    appendRasterBitmapToStream(baos, dilated, widthDots, thresholdVal, lineSpacingDots)
                }
                is HybridReceiptLine.ImageBlock -> {
                    // QR codes and barcodes must never be dilated: module dimensions and finder patterns must remain strictly un-dilated
                    val dilated = if (StoreInfoManager.thermalRasterDilation && !line.isBarcode && !line.isQrCode) {
                        dilateBitmap(line.bitmap, thresholdVal)
                    } else {
                        line.bitmap
                    }
                    appendRasterBitmapToStream(
                        baos = baos,
                        bitmap = dilated,
                        widthDots = widthDots,
                        thresholdVal = thresholdVal,
                        lineSpacingDots = lineSpacingDots,
                        isBarcodeOrQr = (line.isBarcode || line.isQrCode)
                    )
                }
                is HybridReceiptLine.TextLine -> {
                    if (isNativeTextLine(line.text)) {
                        appendNativeTextLineToStream(baos, line, darkness, fontSize, maxChars)
                    } else {
                        val isBengali = BengaliReceiptTranslator.containsBengali(line.text)
                        val lineBitmap = renderTextLineBitmap(line, widthDots, fontSize)
                        val lineThreshold = if (isBengali) bengaliThresholdVal else thresholdVal
                        // Bengali glyphs must not be dilated: font scale & 205 threshold deliver crisp, legible strokes without solid black smearing
                        val dilated = if (StoreInfoManager.thermalRasterDilation && !isBengali) {
                            dilateBitmap(lineBitmap, lineThreshold)
                        } else {
                            lineBitmap
                        }
                        appendRasterBitmapToStream(baos, dilated, widthDots, lineThreshold, lineSpacingDots)
                    }
                }
                is HybridReceiptLine.TwoColumnLine -> {
                    val combined = "${line.left} ${line.right}"
                    if (isNativeTextLine(combined)) {
                        appendNativeTwoColumnLineToStream(baos, line, darkness, fontSize, maxChars)
                    } else {
                        val isBengali = BengaliReceiptTranslator.containsBengali(combined)
                        val lineBitmap = renderTwoColumnLineBitmap(line, widthDots, fontSize)
                        val lineThreshold = if (isBengali) bengaliThresholdVal else thresholdVal
                        val dilated = if (StoreInfoManager.thermalRasterDilation && !isBengali) {
                            dilateBitmap(lineBitmap, lineThreshold)
                        } else {
                            lineBitmap
                        }
                        appendRasterBitmapToStream(baos, dilated, widthDots, lineThreshold, lineSpacingDots)
                    }
                }
                is HybridReceiptLine.ThreeColumnLine -> {
                    val combined = "${line.col1} ${line.col2} ${line.col3}"
                    if (isNativeTextLine(combined)) {
                        appendNativeThreeColumnLineToStream(baos, line, darkness, fontSize, maxChars)
                    } else {
                        val isBengali = BengaliReceiptTranslator.containsBengali(combined)
                        val lineBitmap = renderThreeColumnLineBitmap(line, widthDots, fontSize)
                        val lineThreshold = if (isBengali) bengaliThresholdVal else thresholdVal
                        val dilated = if (StoreInfoManager.thermalRasterDilation && !isBengali) {
                            dilateBitmap(lineBitmap, lineThreshold)
                        } else {
                            lineBitmap
                        }
                        appendRasterBitmapToStream(baos, dilated, widthDots, lineThreshold, lineSpacingDots)
                    }
                }
            }
        }

        // Reset text formatting
        baos.write(byteArrayOf(0x1B, 0x45, 0x00)) // Bold OFF
        baos.write(byteArrayOf(0x1B, 0x47, 0x00)) // Double-strike OFF
        baos.write(byteArrayOf(0x1B, 0x61, 0x00)) // Left align
        baos.write(byteArrayOf(0x1D, 0x21, 0x00)) // Normal font size
        baos.write(byteArrayOf(0x1B, 0x32))       // Reset default line spacing

        // Paper feed
        if (feedLines > 0) {
            val linesToFeed = feedLines.coerceIn(1, 8)
            baos.write(byteArrayOf(0x1B, 0x32)) // Reset default line spacing (ESC 2)
            repeat(linesToFeed) {
                baos.write(0x0A) // Universal LF
            }
            baos.write(byteArrayOf(0x1B, 0x64, linesToFeed.toByte())) // ESC d n standard command
        }

        return baos.toByteArray()
    }

    /**
     * Splits text into lines breaking ONLY on spaces/word boundaries, never mid-word.
     * Ensures store names like "Kali Mata Variety Store" never split "Variety" in half.
     */
    fun wrapTextToLines(text: String, maxCols: Int): List<String> {
        val safeMaxCols = maxCols.coerceAtLeast(1)
        val result = mutableListOf<String>()
        val paragraphs = text.split("\n")
        for (paragraph in paragraphs) {
            val trimmed = paragraph.trim()
            if (trimmed.isEmpty()) {
                result.add("")
                continue
            }
            if (trimmed.length <= safeMaxCols) {
                result.add(trimmed)
                continue
            }
            val words = trimmed.split(Regex("\\s+"))
            var currentLine = StringBuilder()
            for (word in words) {
                if (word.isEmpty()) continue
                if (currentLine.isEmpty()) {
                    if (word.length <= safeMaxCols) {
                        currentLine.append(word)
                    } else {
                        var remaining = word
                        while (remaining.length > safeMaxCols) {
                            result.add(remaining.take(safeMaxCols))
                            remaining = remaining.substring(safeMaxCols)
                        }
                        currentLine.append(remaining)
                    }
                } else {
                    if (currentLine.length + 1 + word.length <= safeMaxCols) {
                        currentLine.append(" ").append(word)
                    } else {
                        result.add(currentLine.toString())
                        currentLine = StringBuilder()
                        if (word.length <= safeMaxCols) {
                            currentLine.append(word)
                        } else {
                            var remaining = word
                            while (remaining.length > safeMaxCols) {
                                result.add(remaining.take(safeMaxCols))
                                remaining = remaining.substring(safeMaxCols)
                            }
                            currentLine.append(remaining)
                        }
                    }
                }
            }
            if (currentLine.isNotEmpty()) {
                result.add(currentLine.toString())
            }
        }
        return result
    }

    private fun appendNativeTextLineToStream(
        baos: ByteArrayOutputStream,
        line: HybridReceiptLine.TextLine,
        darkness: DarknessPreset,
        fontSize: String,
        maxChars: Int
    ) {
        val shouldBeBold = StoreInfoManager.thermalNativeBoldMode || line.isBold || line.isTitle || darkness == DarknessPreset.DARK || darkness == DarknessPreset.EXTRA_DARK
        val shouldDoubleStrike = line.isTitle || (darkness == DarknessPreset.EXTRA_DARK)

        // Alignment: ESC a n (0=left, 1=center, 2=right)
        baos.write(byteArrayOf(0x1B, 0x61, line.alignment.toByte()))

        // Bold: ESC E n
        baos.write(byteArrayOf(0x1B, 0x45, if (shouldBeBold) 0x01 else 0x00))

        // Double strike: ESC G n
        baos.write(byteArrayOf(0x1B, 0x47, if (shouldDoubleStrike) 0x01 else 0x00))

        // Size: GS ! n
        // At 203 DPI, Font A at 0x00 (1x1) has dimensions: 12 dots (1.5mm) wide x 24 dots (3.0mm) high.
        // This matches the Bengali bitmap title (21f = 25 dots height x 12.5 dots width) and body text (14f = 20 dots height).
        // Using 0x00 allows up to 32 characters per line, so "Kali Mata Variety Store" (23 chars) fits on a single line,
        // matching the 1-line Bengali bitmap title line count and physical letter dimensions.
        val sizeByte: Byte = 0x00
        baos.write(byteArrayOf(0x1D, 0x21, sizeByte))

        val cleanText = line.text.replace("₹", "Rs.")
        val effectiveCols = if (sizeByte.toInt() and 0x10 != 0) maxChars / 2 else maxChars
        val splitLines = wrapTextToLines(cleanText, effectiveCols)
        for (sub in splitLines) {
            baos.write(sub.toByteArray(Charsets.US_ASCII))
            baos.write(0x0A)
        }
    }

    private fun appendNativeTwoColumnLineToStream(
        baos: ByteArrayOutputStream,
        line: HybridReceiptLine.TwoColumnLine,
        darkness: DarknessPreset,
        fontSize: String,
        maxChars: Int
    ) {
        val shouldBeBold = StoreInfoManager.thermalNativeBoldMode || line.isBold || darkness == DarknessPreset.DARK || darkness == DarknessPreset.EXTRA_DARK
        val shouldDoubleStrike = (darkness == DarknessPreset.EXTRA_DARK)

        baos.write(byteArrayOf(0x1B, 0x61, 0x00)) // Left align for full row width
        baos.write(byteArrayOf(0x1B, 0x45, if (shouldBeBold) 0x01 else 0x00))
        baos.write(byteArrayOf(0x1B, 0x47, if (shouldDoubleStrike) 0x01 else 0x00))
        baos.write(byteArrayOf(0x1D, 0x21, 0x00)) // Calibrated 1x1 normal size for exact physical parity

        val formatted = formatLeftRight(line.left, line.right, maxChars)
        baos.write(formatted.toByteArray(Charsets.US_ASCII))
        baos.write(0x0A)
    }

    private fun appendNativeThreeColumnLineToStream(
        baos: ByteArrayOutputStream,
        line: HybridReceiptLine.ThreeColumnLine,
        darkness: DarknessPreset,
        fontSize: String,
        maxChars: Int
    ) {
        val shouldBeBold = StoreInfoManager.thermalNativeBoldMode || line.isBold || darkness == DarknessPreset.DARK || darkness == DarknessPreset.EXTRA_DARK
        val shouldDoubleStrike = (darkness == DarknessPreset.EXTRA_DARK)

        baos.write(byteArrayOf(0x1B, 0x61, 0x00)) // Left align for column table
        baos.write(byteArrayOf(0x1B, 0x45, if (shouldBeBold) 0x01 else 0x00))
        baos.write(byteArrayOf(0x1B, 0x47, if (shouldDoubleStrike) 0x01 else 0x00))
        baos.write(byteArrayOf(0x1D, 0x21, 0x00)) // Calibrated 1x1 normal size for exact physical parity

        val w1 = if (maxChars <= 24) 11 else if (maxChars <= 32) 15 else 24
        val cleanCol1 = line.col1.replace("₹", "Rs ")
        if (cleanCol1.length > w1 && !line.isBold) {
            val firstLineName = cleanCol1.take(w1)
            val restName = cleanCol1.substring(w1).trim()
            val formatted = format3Cols(firstLineName, line.col2, line.col3, maxChars)
            baos.write(formatted.toByteArray(Charsets.US_ASCII))
            baos.write(0x0A)
            if (restName.isNotEmpty()) {
                val splitRest = wrapTextToLines(restName, maxChars)
                for (rLine in splitRest) {
                    baos.write(rLine.toByteArray(Charsets.US_ASCII))
                    baos.write(0x0A)
                }
            }
        } else {
            val formatted = format3Cols(line.col1, line.col2, line.col3, maxChars)
            baos.write(formatted.toByteArray(Charsets.US_ASCII))
            baos.write(0x0A)
        }
    }

    fun formatLeftRight(left: String, right: String, totalCols: Int): String {
        val cleanLeft = left.replace("₹", "Rs ")
        val cleanRight = right.replace("₹", "Rs ")
        val spaceNeeded = totalCols - cleanLeft.length - cleanRight.length
        return if (spaceNeeded >= 1) {
            cleanLeft + " ".repeat(spaceNeeded) + cleanRight
        } else {
            val maxLeft = (totalCols - cleanRight.length - 1).coerceAtLeast(1)
            cleanLeft.take(maxLeft) + " " + cleanRight
        }
    }

    fun format3Cols(col1: String, col2: String, col3: String, totalCols: Int): String {
        val c1 = col1.replace("₹", "Rs ")
        val c2 = col2.replace("₹", "Rs ")
        val c3 = col3.replace("₹", "Rs ")
        val safeTotal = totalCols.coerceAtLeast(16)
        val (w1, w2, w3) = when {
            safeTotal <= 24 -> {
                val q = 5
                val p = 8.coerceAtMost(safeTotal - q - 4)
                val itm = (safeTotal - q - p).coerceAtLeast(4)
                Triple(itm, q, p)
            }
            safeTotal <= 32 -> {
                val q = 7
                val p = 10.coerceAtMost(safeTotal - q - 8)
                val itm = (safeTotal - q - p).coerceAtLeast(8)
                Triple(itm, q, p)
            }
            else -> {
                val q = (safeTotal * 0.22f).toInt().coerceIn(6, 12)
                val p = (safeTotal * 0.28f).toInt().coerceIn(8, 16)
                val itm = (safeTotal - q - p).coerceAtLeast(10)
                Triple(itm, q, p)
            }
        }
        val safeW1 = w1.coerceAtLeast(1)
        val safeW2 = w2.coerceAtLeast(1)
        val safeW3 = w3.coerceAtLeast(1)

        val s1 = if (c1.length > safeW1) c1.take(safeW1) else c1.padEnd(safeW1)
        val s2 = if (c2.length > safeW2) c2.take(safeW2) else c2.padStart(safeW2)
        val s3 = if (c3.length > safeW3) c3.take(safeW3) else c3.padStart(safeW3)
        return s1 + s2 + s3
    }

    fun appendRasterBitmapToStream(
        baos: ByteArrayOutputStream,
        bitmap: Bitmap,
        widthDots: Int,
        thresholdVal: Int,
        lineSpacingDots: Int = 24,
        isBarcodeOrQr: Boolean = false
    ) {
        val bmp = if (bitmap.width != widthDots) {
            if (isBarcodeOrQr) {
                // NEVER linearly stretch barcodes or QR codes: center on white background to preserve exact integer module widths!
                val centered = Bitmap.createBitmap(widthDots, bitmap.height, Bitmap.Config.ARGB_8888)
                centered.eraseColor(Color.WHITE)
                val canvas = Canvas(centered)
                val left = Math.round((widthDots - bitmap.width) / 2f).toFloat().coerceAtLeast(0f)
                val noFilterPaint = Paint().apply {
                    isFilterBitmap = false
                    isAntiAlias = false
                    isDither = false
                }
                canvas.drawBitmap(bitmap, left, 0f, noFilterPaint)
                centered
            } else {
                val scaledHeight = ((bitmap.height.toFloat() / bitmap.width.toFloat()) * widthDots).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bitmap, widthDots, scaledHeight, false)
            }
        } else {
            bitmap
        }
        val height = bmp.height
        val bytesPerLine = widthDots / 8
        val chunkHeight = if (widthDots == 384) 64 else 128
        var y = 0

        // Set line spacing to 0 for continuous raster
        baos.write(byteArrayOf(0x1B, 0x33, 0x00))

        while (y < height) {
            val currentChunkHeight = Math.min(chunkHeight, height - y)
            val xL = (bytesPerLine and 0xFF).toByte()
            val xH = ((bytesPerLine shr 8) and 0xFF).toByte()
            val yL = (currentChunkHeight and 0xFF).toByte()
            val yH = ((currentChunkHeight shr 8) and 0xFF).toByte()

            baos.write(byteArrayOf(0x1D, 0x76, 0x30, 0x00, xL, xH, yL, yH))

            val pixels = IntArray(widthDots * currentChunkHeight)
            bmp.getPixels(pixels, 0, widthDots, 0, y, widthDots, currentChunkHeight)

            for (row in 0 until currentChunkHeight) {
                for (colByte in 0 until bytesPerLine) {
                    var byteVal = 0
                    for (bit in 0..7) {
                        val x = colByte * 8 + bit
                        if (x < widthDots) {
                            val pixel = pixels[row * widthDots + x]
                            val alpha = (pixel ushr 24) and 0xFF
                            val r = (pixel shr 16) and 0xFF
                            val g = (pixel shr 8) and 0xFF
                            val b = pixel and 0xFF
                            val effR = (r * alpha + 255 * (255 - alpha)) / 255
                            val effG = (g * alpha + 255 * (255 - alpha)) / 255
                            val effB = (b * alpha + 255 * (255 - alpha)) / 255
                            val luminance = (effR * 299 + effG * 587 + effB * 114) / 1000

                            if (luminance < thresholdVal) {
                                byteVal = byteVal or (1 shl (7 - bit))
                            }
                        }
                    }
                    baos.write(byteVal)
                }
            }
            y += currentChunkHeight
        }
        // Restore calibrated line spacing to maintain identical row height for subsequent native text
        baos.write(byteArrayOf(0x1B, 0x33, lineSpacingDots.toByte()))
    }

    /**
     * Renders a 12-dot high raster bitmap divider to match the reference receipt's
     * section spacing and dashed pattern across both native and bitmap modes.
     */
    fun renderDividerBitmap(
        line: HybridReceiptLine.Divider,
        widthDots: Int
    ): Bitmap {
        val height = 12
        val paddingX = if (widthDots == 384) 14 else 20
        val bitmap = Bitmap.createBitmap(widthDots, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
            isAntiAlias = false
        }
        val startX = paddingX
        val endX = widthDots - paddingX
        if (line.isDouble) {
            canvas.drawRect(startX.toFloat(), 2f, endX.toFloat(), 4f, paint)
            canvas.drawRect(startX.toFloat(), 8f, endX.toFloat(), 10f, paint)
            for (x in startX until endX) {
                bitmap.setPixel(x, 2, Color.BLACK)
                bitmap.setPixel(x, 3, Color.BLACK)
                bitmap.setPixel(x, 8, Color.BLACK)
                bitmap.setPixel(x, 9, Color.BLACK)
            }
        } else if (line.isDashed) {
            val dashWidth = 8
            val gapWidth = 4
            var curX = startX
            while (curX < endX) {
                val nextX = Math.min(curX + dashWidth, endX)
                canvas.drawRect(curX.toFloat(), 5f, nextX.toFloat(), 7f, paint)
                for (x in curX until nextX) {
                    bitmap.setPixel(x, 5, Color.BLACK)
                    bitmap.setPixel(x, 6, Color.BLACK)
                }
                curX += dashWidth + gapWidth
            }
        } else {
            canvas.drawRect(startX.toFloat(), 5f, endX.toFloat(), 7f, paint)
            for (x in startX until endX) {
                bitmap.setPixel(x, 5, Color.BLACK)
                bitmap.setPixel(x, 6, Color.BLACK)
            }
        }
        return bitmap
    }

    fun renderTextLineBitmap(
        line: HybridReceiptLine.TextLine,
        widthDots: Int,
        fontSize: String
    ): Bitmap {
        val baseScale = if (widthDots == 384) 1.0f else 1.35f
        val fontScale = if (fontSize == "LARGE") 1.18f else 1.0f
        val isBengali = BengaliReceiptTranslator.containsBengali(line.text)
        val textSize = when {
            line.isTitle -> (if (isBengali) 23f else 21f) * baseScale * fontScale
            line.isSubtitle -> (if (isBengali) 14.5f else 13.5f) * baseScale * fontScale
            else -> (if (isBengali) 15.5f else 14f) * baseScale * fontScale
        }
        val isBold = line.isBold || line.isTitle || isBengali
        val paint = TextPaint().apply {
            color = Color.BLACK
            this.textSize = textSize
            typeface = if (isBold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
            isAntiAlias = true
            isSubpixelText = true
        }
        val paddingX = if (widthDots == 384) 14 else 20
        val contentWidth = (widthDots - paddingX * 2).coerceAtLeast(100)
        val alignment = when (line.alignment) {
            1 -> Layout.Alignment.ALIGN_CENTER
            2 -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }
        val layout = createStaticLayout(line.text, paint, contentWidth, alignment)
        val totalH = (layout.height + 2).coerceAtLeast(18)
        val bitmap = Bitmap.createBitmap(widthDots, totalH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.save()
        canvas.translate(paddingX.toFloat(), 1f)
        layout.draw(canvas)
        canvas.restore()
        return bitmap
    }

    fun renderTwoColumnLineBitmap(
        line: HybridReceiptLine.TwoColumnLine,
        widthDots: Int,
        fontSize: String
    ): Bitmap {
        val baseScale = if (widthDots == 384) 1.0f else 1.35f
        val fontScale = if (fontSize == "LARGE") 1.18f else 1.0f
        val isBengali = BengaliReceiptTranslator.containsBengali("${line.left} ${line.right}")
        val isProminent = line.isBold && (
            line.left.contains("Total", ignoreCase = true) ||
            line.left.contains("মোট") ||
            line.left.contains("বাকি") ||
            line.left.contains("Due", ignoreCase = true)
        )
        val baseTextSize = if (isProminent) (if (isBengali) 19.5f else 18f) else (if (isBengali) 15.5f else 14f)
        val textSize = baseTextSize * baseScale * fontScale
        val isBold = line.isBold || isBengali
        val paint = TextPaint().apply {
            color = Color.BLACK
            this.textSize = textSize
            typeface = if (isBold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
            isAntiAlias = true
            isSubpixelText = true
        }
        val paddingX = if (widthDots == 384) 14 else 20
        val contentWidth = (widthDots - paddingX * 2).coerceAtLeast(100)
        val rightWidth = paint.measureText(line.right).toInt()
        val leftAvailableWidth = (contentWidth - rightWidth - 8).coerceAtLeast(40)
        val leftLayout = createStaticLayout(line.left, paint, leftAvailableWidth, Layout.Alignment.ALIGN_NORMAL)
        val rightLayout = createStaticLayout(line.right, paint, rightWidth + 4, Layout.Alignment.ALIGN_OPPOSITE)

        val totalH = maxOf(leftLayout.height, rightLayout.height, textSize.toInt()) + 2
        val bitmap = Bitmap.createBitmap(widthDots, totalH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        canvas.save()
        canvas.translate(paddingX.toFloat(), 1f)
        leftLayout.draw(canvas)
        canvas.restore()

        val rightX = (widthDots - paddingX - rightWidth - 4).toFloat()
        canvas.save()
        canvas.translate(rightX, 1f)
        rightLayout.draw(canvas)
        canvas.restore()

        return bitmap
    }

    fun renderThreeColumnLineBitmap(
        line: HybridReceiptLine.ThreeColumnLine,
        widthDots: Int,
        fontSize: String
    ): Bitmap {
        val baseScale = if (widthDots == 384) 1.0f else 1.35f
        val fontScale = if (fontSize == "LARGE") 1.18f else 1.0f
        val isBengali = BengaliReceiptTranslator.containsBengali("${line.col1} ${line.col2} ${line.col3}")
        val textSize = (if (isBengali) 15.5f else 14f) * baseScale * fontScale
        val isBold = line.isBold || isBengali
        val paint = TextPaint().apply {
            color = Color.BLACK
            this.textSize = textSize
            typeface = if (isBold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
            isAntiAlias = true
            isSubpixelText = true
        }
        val paddingX = if (widthDots == 384) 14 else 20
        val contentWidth = (widthDots - paddingX * 2).coerceAtLeast(100)

        val w1 = (contentWidth * 0.50f).toInt()
        val w2 = (contentWidth * 0.24f).toInt()
        val w3 = contentWidth - w1 - w2

        val l1 = createStaticLayout(line.col1, paint, w1, Layout.Alignment.ALIGN_NORMAL)
        val l2 = createStaticLayout(line.col2, paint, w2, Layout.Alignment.ALIGN_CENTER)
        val l3 = createStaticLayout(line.col3, paint, w3, Layout.Alignment.ALIGN_OPPOSITE)

        val totalH = maxOf(l1.height, l2.height, l3.height) + 2
        val bitmap = Bitmap.createBitmap(widthDots, totalH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        var curX = paddingX.toFloat()
        canvas.save()
        canvas.translate(curX, 1f)
        l1.draw(canvas)
        canvas.restore()

        curX += w1.toFloat()
        canvas.save()
        canvas.translate(curX, 1f)
        l2.draw(canvas)
        canvas.restore()

        curX += w2.toFloat()
        canvas.save()
        canvas.translate(curX, 1f)
        l3.draw(canvas)
        canvas.restore()

        return bitmap
    }

    // --- Bengali & Multi-Language Thermal Bitmap Generation ---

    private fun formatBengaliDigits(input: String): String {
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

    private fun formatQtyWithUnit(qty: Double, unitType: String, isBengali: Boolean): String {
        val isGram = com.example.data.local.entities.Product.isGramUnit(unitType)
        val isKg = com.example.data.local.entities.Product.isKgUnit(unitType)
        val isPcs = unitType.equals("pcs", ignoreCase = true) || unitType.equals("piece", ignoreCase = true) || unitType.contains("পিস", ignoreCase = true)
        val isLitre = com.example.data.local.entities.Product.isLitreUnit(unitType)
        val isMl = com.example.data.local.entities.Product.isMlUnit(unitType)
        val isBox = unitType.equals("box", ignoreCase = true) || unitType.contains("বক্স", ignoreCase = true) || unitType.contains("বাক্স", ignoreCase = true)

        val rawQty = if (isGram || isMl) {
            "${qty.toInt()}"
        } else if (qty % 1.0 == 0.0) {
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

    private fun createStaticLayout(
        text: CharSequence,
        paint: TextPaint,
        width: Int,
        alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL
    ): StaticLayout {
        val safeWidth = width.coerceAtLeast(10)
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
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

    fun generateCreditStatementBitmap(
        customer: Customer,
        ledgerEntries: List<LedgerEntry>,
        sales: List<SaleWithItems>,
        periodLabel: String = "All-Time",
        startTimestamp: Long? = null,
        endTimestamp: Long? = null,
        openingBalance: Double = 0.0,
        isBengali: Boolean,
        widthDots: Int,
        currentLiveBalance: Double? = null
    ): Bitmap {
        val fontScale = if (StoreInfoManager.thermalReceiptFontSize == "LARGE") 1.15f else 1.0f
        val paddingX = if (widthDots == 384) 14 else 22
        val contentWidth = widthDots - (paddingX * 2)

        val titlePaint = TextPaint().apply {
            color = Color.BLACK
            textSize = (if (widthDots == 384) 22f else 26f) * fontScale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val subtitlePaint = TextPaint().apply {
            color = Color.BLACK
            textSize = (if (widthDots == 384) 13.5f else 16f) * fontScale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val headerPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = (if (widthDots == 384) 16f else 19f) * fontScale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val bodyPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = (if (widthDots == 384) 13.5f else 15.5f) * fontScale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            isAntiAlias = true
        }

        val bodyBoldPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = (if (widthDots == 384) 14f else 16.5f) * fontScale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val linePaint = Paint().apply {
            color = Color.BLACK
            strokeWidth = if (widthDots == 384) 2.0f else 2.5f
            style = Paint.Style.STROKE
            isAntiAlias = false
        }

        val rawStoreName = StoreInfoManager.storeName
        val storeAddress = StoreInfoManager.storeAddress
        val storePhone = StoreInfoManager.phone
        val gstin = StoreInfoManager.gstin

        val sdf = SimpleDateFormat("dd/MM/yyyy hh:mm a", Locale.getDefault())
        val dateRaw = sdf.format(Date())
        val dateStr = if (isBengali) formatBengaliDigits(dateRaw) else dateRaw

        // Accurate period filtering and ledger calculation
        val isFilteredRange = startTimestamp != null || endTimestamp != null
        val effectiveOpeningBalance = if (isFilteredRange) {
            if (openingBalance != 0.0) openingBalance
            else if (startTimestamp != null) LedgerCalculator.calculateCustomerBalance(customer.id, ledgerEntries.filter { it.datetime < startTimestamp!! })
            else 0.0
        } else {
            0.0
        }

        val periodEntries = if (isFilteredRange) {
            ledgerEntries.filter { 
                (startTimestamp == null || it.datetime >= startTimestamp) &&
                (endTimestamp == null || it.datetime <= endTimestamp)
            }.sortedBy { it.datetime }
        } else {
            ledgerEntries.sortedBy { it.datetime }
        }

        val totalDebit = periodEntries.filter { entry ->
            val type = entry.type.uppercase()
            type in listOf("SALE_CREDIT", "CREDIT_GIVEN", "CREDIT", "REPLACEMENT_DUE", "DUE", "OPENING_BALANCE", "INITIAL_DUE", "OPENING_DUE", "OPENING_CREDIT", "INTEREST", "INTEREST_ACCRUED", "INTEREST_CHARGED") ||
            entry.note?.contains("credit", ignoreCase = true) == true ||
            entry.note?.contains("due", ignoreCase = true) == true ||
            entry.note?.contains("opening", ignoreCase = true) == true
        }.sumOf { it.amount }

        val totalCredit = periodEntries.filter { entry ->
            val type = entry.type.uppercase()
            type in listOf("PAYMENT_RECEIVED", "PAYMENT", "RETURN_REFUND", "CREDIT_REDUCED", "REFUND") ||
            entry.note?.contains("payment", ignoreCase = true) == true ||
            entry.note?.contains("paid", ignoreCase = true) == true
        }.sumOf { it.amount }

        val closingBalance = if (isFilteredRange) {
            effectiveOpeningBalance + totalDebit - totalCredit
        } else {
            customer.balance
        }

        // Khata Interest Calculation & Transparency
        val interestSettings = StoreInfoManager.getKhataInterestSettings()
        val interestBreakdown = KhataInterestCalculator.calculateCustomerInterest(
            customer = customer,
            allLedgerEntries = ledgerEntries,
            settings = interestSettings
        )
        val hasInterestData = interestSettings.enabled && !customer.interestExempt && 
            (interestBreakdown.totalPostedInterest > 0 || interestBreakdown.totalAccruedInterest > 0 || (closingBalance > 0 && interestSettings.monthlyRatePercent > 0))
        val interestDisclaimer = if (interestSettings.enabled && !customer.interestExempt) {
            KhataInterestCalculator.formatDisclaimer(interestSettings, customer, isBengali)
        } else ""

        var measuredHeight = 20f

        val storeNameLayout = createStaticLayout(rawStoreName, titlePaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
        measuredHeight += storeNameLayout.height + 6f

        if (storeAddress.isNotBlank()) {
            val addrLayout = createStaticLayout(storeAddress, subtitlePaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
            measuredHeight += addrLayout.height + 4f
        }

        if (storePhone.isNotBlank()) {
            val phoneText = if (isBengali) "ফোন: ${formatBengaliDigits(storePhone)}" else "Ph: $storePhone"
            val phoneLayout = createStaticLayout(phoneText, subtitlePaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
            measuredHeight += phoneLayout.height + 4f
        }

        if (gstin.isNotBlank()) {
            val gstinLayout = createStaticLayout("GSTIN: $gstin", subtitlePaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
            measuredHeight += gstinLayout.height + 4f
        }

        measuredHeight += 12f

        val statementTitle = if (isBengali) "খাতা / বাকি বিবরণী" else "KHATA / CREDIT STATEMENT"
        val titleLayout = createStaticLayout(statementTitle, headerPaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
        measuredHeight += titleLayout.height + 8f

        val periodText = if (isBengali) "সময়কাল: $periodLabel" else "Period: $periodLabel"
        val periodLayout = createStaticLayout(periodText, subtitlePaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
        measuredHeight += periodLayout.height + 6f

        val custText = if (isBengali) "গ্রাহক: ${customer.name}" else "Customer: ${customer.name}"
        val custLayout = createStaticLayout(custText, bodyBoldPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
        measuredHeight += custLayout.height + 4f

        val custPhoneText = if (isBengali) "ফোন: ${formatBengaliDigits(customer.phone)}" else "Phone: ${customer.phone}"
        val custPhoneLayout = createStaticLayout(custPhoneText, bodyPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
        measuredHeight += custPhoneLayout.height + 4f

        val genDateText = if (isBengali) "তারিখ: $dateStr" else "Date: $dateStr"
        val genDateLayout = createStaticLayout(genDateText, bodyPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
        measuredHeight += genDateLayout.height + 8f

        measuredHeight += 12f

        // Financial Summary block height
        measuredHeight += 24f // Summary Header
        if (isFilteredRange) {
            measuredHeight += 22f // Opening Balance
            measuredHeight += 22f // Period Debit
            measuredHeight += 22f // Period Credit
            if (hasInterestData) {
                measuredHeight += 22f // Principal component
                measuredHeight += 22f // Interest component
            }
            measuredHeight += 28f // Closing Balance
        } else {
            measuredHeight += 22f // Total Debit
            measuredHeight += 22f // Total Credit
            if (hasInterestData) {
                measuredHeight += 22f // Principal component
                measuredHeight += 22f // Interest component
            }
            measuredHeight += 28f // Current Balance
        }

        measuredHeight += 14f

        val tableTitle = if (isBengali) "লেনদেনের ইতিহাস:" else "TRANSACTION LEDGER HISTORY:"
        val tableTitleLayout = createStaticLayout(tableTitle, bodyBoldPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
        measuredHeight += tableTitleLayout.height + 8f

        if (isFilteredRange && effectiveOpeningBalance != 0.0) {
            measuredHeight += 26f // Opening Balance row in ledger
        }

        if (periodEntries.isEmpty()) {
            measuredHeight += 26f
        } else {
            periodEntries.forEach { entry ->
                measuredHeight += 24f
                if (!entry.note.isNullOrBlank()) {
                    measuredHeight += 18f
                }
                measuredHeight += 6f
            }
        }

        measuredHeight += 12f

        // Interest Disclaimer / Policy block
        var interestPolicyLayout: StaticLayout? = null
        if (interestDisclaimer.isNotBlank()) {
            val fullPolicyText = if (isBengali) "দেরি সুদ সংক্রান্ত শর্তাবলী: $interestDisclaimer" else "Interest Policy Terms: $interestDisclaimer"
            interestPolicyLayout = createStaticLayout(fullPolicyText, subtitlePaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
            measuredHeight += interestPolicyLayout.height + 14f
        }

        val liveDueBalance = currentLiveBalance ?: LedgerCalculator.calculateCustomerBalance(customer.id, ledgerEntries)
        val merchantUpi = StoreInfoManager.upiVpa.trim().ifBlank { StoreInfoManager.merchantUpiId.trim() }
        val payeeName = StoreInfoManager.merchantPayeeName.trim().ifBlank { StoreInfoManager.storeName.trim() }

        var qrBitmap: Bitmap? = null
        var qrHeaderLayout: StaticLayout? = null
        var qrFootLayout: StaticLayout? = null
        var noDuesLayout: StaticLayout? = null

        if (liveDueBalance > 0.0 && merchantUpi.isNotBlank()) {
            val qrNote = "Khata Due - ${customer.name}"
            val upiUrl = StoreInfoManager.buildBillCheckoutUpiPayUrl(
                upiId = merchantUpi,
                payeeName = payeeName,
                amount = liveDueBalance,
                note = qrNote
            )
            if (upiUrl.isNotBlank()) {
                val qrSize = if (widthDots == 384) 200 else 240
                qrBitmap = ThermalPrintingService.generateReceiptQrCode(upiUrl, qrSize, quietZonePadding = 6)
                if (qrBitmap != null) {
                    val qrHeaderText = if (isBengali) {
                        "স্ক্যান করে পরিশোধ করুন: ₹${formatBengaliDigits("%.2f".format(liveDueBalance))}"
                    } else {
                        "Scan to pay ₹%.2f".format(Locale.US, liveDueBalance)
                    }
                    qrHeaderLayout = createStaticLayout(qrHeaderText, bodyBoldPaint, contentWidth, Layout.Alignment.ALIGN_CENTER)

                    val qrFoot = "UPI: $merchantUpi"
                    qrFootLayout = createStaticLayout(qrFoot, subtitlePaint, contentWidth, Layout.Alignment.ALIGN_CENTER)

                    measuredHeight += qrHeaderLayout.height + 8f
                    measuredHeight += qrBitmap.height + 8f
                    measuredHeight += qrFootLayout.height + 8f
                    measuredHeight += 10f
                }
            }
        } else if (liveDueBalance <= 0.0) {
            val noDuesText = if (isBengali) {
                if (liveDueBalance < 0.0) "কোনো বকেয়া বাকি নেই (অগ্রিম জমা: ₹${formatBengaliDigits("%.2f".format(-liveDueBalance))})"
                else "কোনো বকেয়া বাকি নেই"
            } else {
                if (liveDueBalance < 0.0) "No outstanding dues (Advance: ₹%.2f)".format(Locale.US, -liveDueBalance)
                else "No outstanding dues"
            }
            noDuesLayout = createStaticLayout(noDuesText, bodyBoldPaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
            measuredHeight += noDuesLayout.height + 8f
            measuredHeight += 10f
        }

        val footer1 = if (isBengali) "ধন্যবাদ! সঠিক সময়ে বাকি পরিশোধ করুন।" else "Thank you for your business!"
        val footer2 = if (isBengali) "ডিজিটাল খাতা ও পয়েন্ট অফ সেল সিস্টেম" else "Digital Khata & POS Billing System"
        val footer1Layout = createStaticLayout(footer1, subtitlePaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
        val footer2Layout = createStaticLayout(footer2, bodyPaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
        measuredHeight += footer1Layout.height + footer2Layout.height + 10f

        val totalHeight = Math.max(measuredHeight.toInt() + 6, 120)

        val bitmap = Bitmap.createBitmap(widthDots, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        var y = 6f

        canvas.save()
        canvas.translate(paddingX.toFloat(), y)
        storeNameLayout.draw(canvas)
        canvas.restore()
        y += storeNameLayout.height + 6f

        if (storeAddress.isNotBlank()) {
            val addrLayout = createStaticLayout(storeAddress, subtitlePaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
            canvas.save()
            canvas.translate(paddingX.toFloat(), y)
            addrLayout.draw(canvas)
            canvas.restore()
            y += addrLayout.height + 4f
        }

        if (storePhone.isNotBlank()) {
            val phoneLayout = createStaticLayout(if (isBengali) "ফোন: ${formatBengaliDigits(storePhone)}" else "Ph: $storePhone", subtitlePaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
            canvas.save()
            canvas.translate(paddingX.toFloat(), y)
            phoneLayout.draw(canvas)
            canvas.restore()
            y += phoneLayout.height + 4f
        }

        if (gstin.isNotBlank()) {
            val gstinLayout = createStaticLayout("GSTIN: $gstin", subtitlePaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
            canvas.save()
            canvas.translate(paddingX.toFloat(), y)
            gstinLayout.draw(canvas)
            canvas.restore()
            y += gstinLayout.height + 4f
        }

        y += 4f
        canvas.drawLine(paddingX.toFloat(), y, (widthDots - paddingX).toFloat(), y, linePaint)
        y += 8f

        canvas.save()
        canvas.translate(paddingX.toFloat(), y)
        titleLayout.draw(canvas)
        canvas.restore()
        y += titleLayout.height + 6f

        canvas.save()
        canvas.translate(paddingX.toFloat(), y)
        periodLayout.draw(canvas)
        canvas.restore()
        y += periodLayout.height + 8f

        canvas.save()
        canvas.translate(paddingX.toFloat(), y)
        custLayout.draw(canvas)
        canvas.restore()
        y += custLayout.height + 4f

        canvas.save()
        canvas.translate(paddingX.toFloat(), y)
        custPhoneLayout.draw(canvas)
        canvas.restore()
        y += custPhoneLayout.height + 4f

        canvas.save()
        canvas.translate(paddingX.toFloat(), y)
        genDateLayout.draw(canvas)
        canvas.restore()
        y += genDateLayout.height + 8f

        canvas.drawLine(paddingX.toFloat(), y, (widthDots - paddingX).toFloat(), y, linePaint)
        y += 10f

        val sumTitle = if (isBengali) "হিসাব সারসংক্ষেপ (SUMMARY)" else "ACCOUNT FINANCIAL SUMMARY"
        val sumTitleLayout = createStaticLayout(sumTitle, headerPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
        canvas.save()
        canvas.translate(paddingX.toFloat(), y)
        sumTitleLayout.draw(canvas)
        canvas.restore()
        y += sumTitleLayout.height + 6f

        if (isFilteredRange) {
            val openBalStr = if (isBengali) "প্রারম্ভিক জের (Opening Due): ₹${formatBengaliDigits("%.2f".format(effectiveOpeningBalance))}" else "Opening Balance: Rs.%.2f".format(effectiveOpeningBalance)
            val openBalLayout = createStaticLayout(openBalStr, bodyPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
            canvas.save()
            canvas.translate(paddingX.toFloat(), y)
            openBalLayout.draw(canvas)
            canvas.restore()
            y += 22f

            val totalDebitStr = if (isBengali) "এই সময়ের বাকি/ক্রয়: +₹${formatBengaliDigits("%.2f".format(totalDebit))}" else "Period Billed/Credit: +Rs.%.2f".format(totalDebit)
            val debLayout = createStaticLayout(totalDebitStr, bodyPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
            canvas.save()
            canvas.translate(paddingX.toFloat(), y)
            debLayout.draw(canvas)
            canvas.restore()
            y += 22f

            val totalCredStr = if (isBengali) "এই সময়ের জমা পরিশোধ: -₹${formatBengaliDigits("%.2f".format(totalCredit))}" else "Period Paid Received: -Rs.%.2f".format(totalCredit)
            val credLayout = createStaticLayout(totalCredStr, bodyPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
            canvas.save()
            canvas.translate(paddingX.toFloat(), y)
            credLayout.draw(canvas)
            canvas.restore()
            y += 22f

            if (hasInterestData) {
                val principalAmtStr = if (isBengali) "মূল বকেয়া (Principal Due): ₹${formatBengaliDigits("%.2f".format(interestBreakdown.principalDue))}" else "Principal Due: Rs.%.2f".format(interestBreakdown.principalDue)
                val princLayout = createStaticLayout(principalAmtStr, bodyPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
                canvas.save()
                canvas.translate(paddingX.toFloat(), y)
                princLayout.draw(canvas)
                canvas.restore()
                y += 22f

                val interestAmt = interestBreakdown.totalAccruedInterest + interestBreakdown.totalPostedInterest
                val interestAmtStr = if (isBengali) "দেরি সুদ (Late Interest): +₹${formatBengaliDigits("%.2f".format(interestAmt))}" else "Late Interest: +Rs.%.2f".format(interestAmt)
                val intLayout = createStaticLayout(interestAmtStr, bodyPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
                canvas.save()
                canvas.translate(paddingX.toFloat(), y)
                intLayout.draw(canvas)
                canvas.restore()
                y += 22f
            }

            val netBalStr = if (isBengali) "সমাপনী অবশিষ্ট বাকি: ₹${formatBengaliDigits("%.2f".format(closingBalance))}" else "Closing Due Balance: Rs.%.2f".format(closingBalance)
            val netBalLayout = createStaticLayout(netBalStr, bodyBoldPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
            canvas.save()
            canvas.translate(paddingX.toFloat(), y)
            netBalLayout.draw(canvas)
            canvas.restore()
            y += 28f
        } else {
            val totalDebitStr = if (isBengali) "মোট বাকি/ক্রয়: ₹${formatBengaliDigits("%.2f".format(totalDebit))}" else "Total Billed/Credit: Rs.%.2f".format(totalDebit)
            val debLayout = createStaticLayout(totalDebitStr, bodyPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
            canvas.save()
            canvas.translate(paddingX.toFloat(), y)
            debLayout.draw(canvas)
            canvas.restore()
            y += 22f

            val totalCredStr = if (isBengali) "মোট জমা পরিশোধ: ₹${formatBengaliDigits("%.2f".format(totalCredit))}" else "Total Paid Received: Rs.%.2f".format(totalCredit)
            val credLayout = createStaticLayout(totalCredStr, bodyPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
            canvas.save()
            canvas.translate(paddingX.toFloat(), y)
            credLayout.draw(canvas)
            canvas.restore()
            y += 22f

            if (hasInterestData) {
                val principalAmtStr = if (isBengali) "মূল বকেয়া (Principal Due): ₹${formatBengaliDigits("%.2f".format(interestBreakdown.principalDue))}" else "Principal Due: Rs.%.2f".format(interestBreakdown.principalDue)
                val princLayout = createStaticLayout(principalAmtStr, bodyPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
                canvas.save()
                canvas.translate(paddingX.toFloat(), y)
                princLayout.draw(canvas)
                canvas.restore()
                y += 22f

                val interestAmt = interestBreakdown.totalAccruedInterest + interestBreakdown.totalPostedInterest
                val interestAmtStr = if (isBengali) "দেরি সুদ (Late Interest): +₹${formatBengaliDigits("%.2f".format(interestAmt))}" else "Late Interest: +Rs.%.2f".format(interestAmt)
                val intLayout = createStaticLayout(interestAmtStr, bodyPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
                canvas.save()
                canvas.translate(paddingX.toFloat(), y)
                intLayout.draw(canvas)
                canvas.restore()
                y += 22f
            }

            val netBalStr = if (isBengali) "বর্তমান অবশিষ্ট বাকি: ₹${formatBengaliDigits("%.2f".format(closingBalance))}" else "Current Due Balance: Rs.%.2f".format(closingBalance)
            val netBalLayout = createStaticLayout(netBalStr, bodyBoldPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
            canvas.save()
            canvas.translate(paddingX.toFloat(), y)
            netBalLayout.draw(canvas)
            canvas.restore()
            y += 28f
        }

        canvas.drawLine(paddingX.toFloat(), y, (widthDots - paddingX).toFloat(), y, linePaint)
        y += 10f

        canvas.save()
        canvas.translate(paddingX.toFloat(), y)
        tableTitleLayout.draw(canvas)
        canvas.restore()
        y += tableTitleLayout.height + 6f

        val entrySdf = SimpleDateFormat("dd/MM hh:mm a", Locale.getDefault())

        if (isFilteredRange && effectiveOpeningBalance != 0.0) {
            val openLabel = if (isBengali) "প্রারম্ভিক জের (Opening Balance)" else "Opening Balance Carried"
            val openAmtStr = if (isBengali) "₹${formatBengaliDigits("%.2f".format(effectiveOpeningBalance))}" else "Rs.%.2f".format(effectiveOpeningBalance)
            val row1Layout = createStaticLayout(openLabel, bodyBoldPaint, (contentWidth * 0.65).toInt(), Layout.Alignment.ALIGN_NORMAL)
            val amtLayout = createStaticLayout(openAmtStr, bodyBoldPaint, (contentWidth * 0.35).toInt(), Layout.Alignment.ALIGN_OPPOSITE)

            canvas.save()
            canvas.translate(paddingX.toFloat(), y)
            row1Layout.draw(canvas)
            canvas.restore()

            canvas.save()
            canvas.translate((paddingX + (contentWidth * 0.65)).toFloat(), y)
            amtLayout.draw(canvas)
            canvas.restore()

            y += Math.max(row1Layout.height, amtLayout.height) + 6f
        }

        if (periodEntries.isEmpty()) {
            val emptyTxt = if (isBengali) "নির্বাচিত সময়কালে কোন লেনদেন রেকর্ড করা হয়নি" else "No ledger transactions recorded in this period"
            val emptyLayout = createStaticLayout(emptyTxt, bodyPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
            canvas.save()
            canvas.translate(paddingX.toFloat(), y)
            emptyLayout.draw(canvas)
            canvas.restore()
            y += 24f
        } else {
            periodEntries.forEach { entry ->
                val dateFmt = entrySdf.format(Date(entry.datetime))
                val dateDisplay = if (isBengali) formatBengaliDigits(dateFmt) else dateFmt

                val typeDisplay = when (entry.type) {
                    "SALE_CREDIT" -> if (isBengali) "ধারে বিক্রি (Dr)" else "Credit Sale (Dr)"
                    "PAYMENT_RECEIVED" -> if (isBengali) "টাকা জমা (Cr)" else "Payment Recv (Cr)"
                    "OPENING_BALANCE", "INITIAL_DUE" -> if (isBengali) "পূর্বের বকেয়া" else "Opening Balance"
                    "INTEREST_ACCRUED", "INTEREST_CHARGED", "INTEREST" -> if (isBengali) "দেরি ফি / সুদ (Dr)" else "Late Interest (Dr)"
                    else -> entry.type
                }

                val amtStr = if (entry.type.contains("RECEIVED") || entry.type.contains("PAYMENT") || entry.type.contains("REFUND")) {
                    if (isBengali) "-₹${formatBengaliDigits("%.2f".format(entry.amount))}" else "-Rs.%.2f".format(entry.amount)
                } else {
                    if (isBengali) "+₹${formatBengaliDigits("%.2f".format(entry.amount))}" else "+Rs.%.2f".format(entry.amount)
                }

                val row1 = "$dateDisplay | $typeDisplay"
                val row1Layout = createStaticLayout(row1, bodyPaint, (contentWidth * 0.68).toInt(), Layout.Alignment.ALIGN_NORMAL)
                val amtLayout = createStaticLayout(amtStr, bodyBoldPaint, (contentWidth * 0.32).toInt(), Layout.Alignment.ALIGN_OPPOSITE)

                canvas.save()
                canvas.translate(paddingX.toFloat(), y)
                row1Layout.draw(canvas)
                canvas.restore()

                canvas.save()
                canvas.translate((paddingX + (contentWidth * 0.68)).toFloat(), y)
                amtLayout.draw(canvas)
                canvas.restore()

                y += Math.max(row1Layout.height, amtLayout.height) + 2f

                if (!entry.note.isNullOrBlank()) {
                    val noteStr = "Note: ${entry.note}"
                    val noteLayout = createStaticLayout(noteStr, subtitlePaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
                    canvas.save()
                    canvas.translate(paddingX.toFloat(), y)
                    noteLayout.draw(canvas)
                    canvas.restore()
                    y += noteLayout.height + 2f
                }
                y += 6f
            }
        }

        canvas.drawLine(paddingX.toFloat(), y, (widthDots - paddingX).toFloat(), y, linePaint)
        y += 10f

        if (interestPolicyLayout != null) {
            canvas.save()
            canvas.translate(paddingX.toFloat(), y)
            interestPolicyLayout.draw(canvas)
            canvas.restore()
            y += interestPolicyLayout.height + 8f

            canvas.drawLine(paddingX.toFloat(), y, (widthDots - paddingX).toFloat(), y, linePaint)
            y += 10f
        }

        if (qrBitmap != null && qrHeaderLayout != null) {
            canvas.save()
            canvas.translate(paddingX.toFloat(), y)
            qrHeaderLayout.draw(canvas)
            canvas.restore()
            y += qrHeaderLayout.height + 8f

            val qrLeft = (widthDots - qrBitmap.width) / 2f
            canvas.drawBitmap(qrBitmap, qrLeft, y, null)
            y += qrBitmap.height + 8f

            if (qrFootLayout != null) {
                canvas.save()
                canvas.translate(paddingX.toFloat(), y)
                qrFootLayout.draw(canvas)
                canvas.restore()
                y += qrFootLayout.height + 8f
            }

            canvas.drawLine(paddingX.toFloat(), y, (widthDots - paddingX).toFloat(), y, linePaint)
            y += 10f
        } else if (noDuesLayout != null) {
            canvas.save()
            canvas.translate(paddingX.toFloat(), y)
            noDuesLayout.draw(canvas)
            canvas.restore()
            y += noDuesLayout.height + 8f

            canvas.drawLine(paddingX.toFloat(), y, (widthDots - paddingX).toFloat(), y, linePaint)
            y += 10f
        }

        canvas.save()
        canvas.translate(paddingX.toFloat(), y)
        footer1Layout.draw(canvas)
        canvas.restore()
        y += footer1Layout.height + 4f

        canvas.save()
        canvas.translate(paddingX.toFloat(), y)
        footer2Layout.draw(canvas)
        canvas.restore()

        return bitmap
    }

    fun generateSupplierStatementBitmap(
        supplier: Supplier,
        ledgerEntries: List<LedgerEntry>,
        purchases: List<PurchaseWithItems>,
        periodLabel: String = "All-Time",
        startTimestamp: Long? = null,
        endTimestamp: Long? = null,
        openingBalance: Double = 0.0,
        isBengali: Boolean,
        widthDots: Int
    ): Bitmap {
        val fontScale = if (StoreInfoManager.thermalReceiptFontSize == "LARGE") 1.15f else 1.0f
        val paddingX = if (widthDots == 384) 14 else 22
        val contentWidth = widthDots - (paddingX * 2)

        val titlePaint = TextPaint().apply {
            color = Color.BLACK
            textSize = (if (widthDots == 384) 22f else 26f) * fontScale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val subtitlePaint = TextPaint().apply {
            color = Color.BLACK
            textSize = (if (widthDots == 384) 13.5f else 16f) * fontScale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val headerPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = (if (widthDots == 384) 16f else 19f) * fontScale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val bodyPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = (if (widthDots == 384) 13.5f else 15.5f) * fontScale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            isAntiAlias = true
        }

        val bodyBoldPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = (if (widthDots == 384) 14f else 16.5f) * fontScale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val linePaint = Paint().apply {
            color = Color.BLACK
            strokeWidth = if (widthDots == 384) 2.0f else 2.5f
            style = Paint.Style.STROKE
            isAntiAlias = false
        }

        val rawStoreName = StoreInfoManager.storeName
        val storeAddress = StoreInfoManager.storeAddress
        val storePhone = StoreInfoManager.phone
        val gstin = StoreInfoManager.gstin

        val sdf = SimpleDateFormat("dd/MM/yyyy hh:mm a", Locale.getDefault())
        val dateRaw = sdf.format(Date())
        val dateStr = if (isBengali) formatBengaliDigits(dateRaw) else dateRaw

        val isFilteredRange = startTimestamp != null && endTimestamp != null
        val effectiveOpeningBalance = if (isFilteredRange) {
            if (openingBalance != 0.0) openingBalance
            else LedgerCalculator.calculateSupplierBalance(supplier.id, ledgerEntries.filter { it.datetime < startTimestamp!! })
        } else {
            0.0
        }

        val periodPurchases = if (isFilteredRange) {
            purchases.filter { it.purchase.datetime in startTimestamp!!..endTimestamp!! }
        } else {
            purchases
        }

        val periodLedgers = if (isFilteredRange) {
            ledgerEntries.filter { it.datetime in startTimestamp!!..endTimestamp!! }
        } else {
            ledgerEntries
        }

        val totalPurchased = periodPurchases.sumOf { it.purchase.totalAmount }
        val totalPaid = periodPurchases.sumOf { it.purchase.amountPaid } + periodLedgers.filter { it.type.contains("PAYMENT") || it.type == "PAYMENT_MADE" }.sumOf { it.amount }
        
        val netBalance = if (isFilteredRange) {
            effectiveOpeningBalance + periodLedgers.filter { 
                it.type in listOf("PURCHASE_CREDIT", "CREDIT_TAKEN", "PURCHASE_DUE", "DUE", "OPENING_BALANCE", "INITIAL_DUE", "OPENING_DUE", "OPENING_CREDIT") ||
                it.note?.contains("purchase", ignoreCase = true) == true ||
                it.note?.contains("due", ignoreCase = true) == true
            }.sumOf { it.amount } - periodLedgers.filter { 
                it.type in listOf("PAYMENT_MADE", "PAYMENT", "PURCHASE_EXTRA_PAID") ||
                it.note?.contains("payment", ignoreCase = true) == true
            }.sumOf { it.amount }
        } else {
            supplier.balance
        }

        // Build itemized timeline
        val transactions = mutableListOf<WhatsAppHelper.StatementTransactionItem>()
        periodPurchases.forEach { pWithItems ->
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

        val linkedPurchaseIds = periodPurchases.map { it.purchase.id }.toSet()
        periodLedgers.forEach { entry ->
            val isPayment = entry.type.contains("PAYMENT") || entry.type == "PAYMENT_MADE"
            val isCredit = entry.type.contains("CREDIT") || entry.type == "PURCHASE_CREDIT"

            if (entry.referenceId != null && linkedPurchaseIds.contains(entry.referenceId)) {
                return@forEach
            }

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

        var measuredHeight = 20f

        val storeNameLayout = createStaticLayout(rawStoreName, titlePaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
        measuredHeight += storeNameLayout.height + 6f

        if (storeAddress.isNotBlank()) {
            val addrLayout = createStaticLayout(storeAddress, subtitlePaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
            measuredHeight += addrLayout.height + 4f
        }

        if (storePhone.isNotBlank()) {
            val phoneLayout = createStaticLayout(if (isBengali) "ফোন: ${formatBengaliDigits(storePhone)}" else "Ph: $storePhone", subtitlePaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
            measuredHeight += phoneLayout.height + 4f
        }

        if (gstin.isNotBlank()) {
            val gstinLayout = createStaticLayout("GSTIN: $gstin", subtitlePaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
            measuredHeight += gstinLayout.height + 4f
        }

        measuredHeight += 12f

        val statementTitle = if (isBengali) "মহাজন / সাপ্লায়ার খাতা স্টেটমেন্ট" else "SUPPLIER KHATA STATEMENT"
        val titleLayout = createStaticLayout(statementTitle, headerPaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
        measuredHeight += titleLayout.height + 8f

        val periodText = if (isBengali) "সময়কাল: $periodLabel" else "Period: $periodLabel"
        val periodLayout = createStaticLayout(periodText, subtitlePaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
        measuredHeight += periodLayout.height + 6f

        val suppText = if (isBengali) "মহাজন / সাপ্লায়ার: ${supplier.name}" else "Supplier: ${supplier.name}"
        val suppLayout = createStaticLayout(suppText, bodyBoldPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
        measuredHeight += suppLayout.height + 4f

        if (supplier.phone.isNotBlank()) {
            val suppPhoneText = if (isBengali) "ফোন: ${formatBengaliDigits(supplier.phone)}" else "Phone: ${supplier.phone}"
            val suppPhoneLayout = createStaticLayout(suppPhoneText, bodyPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
            measuredHeight += suppPhoneLayout.height + 4f
        }

        val genDateText = if (isBengali) "তারিখ: $dateStr" else "Date: $dateStr"
        val genDateLayout = createStaticLayout(genDateText, bodyPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
        measuredHeight += genDateLayout.height + 8f

        measuredHeight += 12f

        measuredHeight += 24f // summary title
        if (isFilteredRange) {
            measuredHeight += 22f // opening balance
            measuredHeight += 22f // total purchase
            measuredHeight += 22f // total paid
            measuredHeight += 28f // closing balance
        } else {
            measuredHeight += 22f
            measuredHeight += 22f
            measuredHeight += 28f
        }

        measuredHeight += 14f

        val tableTitle = if (isBengali) "চালান ও পেমেন্টের বিবরণী:" else "ITEMIZED BILLS & PAYMENTS:"
        val tableTitleLayout = createStaticLayout(tableTitle, bodyBoldPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
        measuredHeight += tableTitleLayout.height + 8f

        if (isFilteredRange && effectiveOpeningBalance != 0.0) {
            measuredHeight += 26f
        }

        if (transactions.isEmpty() && (!isFilteredRange || effectiveOpeningBalance == 0.0)) {
            measuredHeight += 26f
        } else {
            transactions.forEach { _ ->
                measuredHeight += 24f
                measuredHeight += 20f
            }
        }

        measuredHeight += 12f

        val footer1 = if (isBengali) "ব্যবসায়িক সহযোগিতার জন্য ধন্যবাদ!" else "Thank you for your business & support!"
        val footer2 = if (isBengali) "ডিজিটাল মহাজন খাতা সিস্টেম" else "Digital Supplier Khata System"
        val footer1Layout = createStaticLayout(footer1, subtitlePaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
        val footer2Layout = createStaticLayout(footer2, bodyPaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
        measuredHeight += footer1Layout.height + footer2Layout.height + 10f

        val totalHeight = Math.max(measuredHeight.toInt() + 6, 120)

        val bitmap = Bitmap.createBitmap(widthDots, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        var y = 6f

        canvas.save()
        canvas.translate(paddingX.toFloat(), y)
        storeNameLayout.draw(canvas)
        canvas.restore()
        y += storeNameLayout.height + 6f

        if (storeAddress.isNotBlank()) {
            val addrLayout = createStaticLayout(storeAddress, subtitlePaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
            canvas.save()
            canvas.translate(paddingX.toFloat(), y)
            addrLayout.draw(canvas)
            canvas.restore()
            y += addrLayout.height + 4f
        }

        if (storePhone.isNotBlank()) {
            val phoneLayout = createStaticLayout(if (isBengali) "ফোন: ${formatBengaliDigits(storePhone)}" else "Ph: $storePhone", subtitlePaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
            canvas.save()
            canvas.translate(paddingX.toFloat(), y)
            phoneLayout.draw(canvas)
            canvas.restore()
            y += phoneLayout.height + 4f
        }

        if (gstin.isNotBlank()) {
            val gstinLayout = createStaticLayout("GSTIN: $gstin", subtitlePaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
            canvas.save()
            canvas.translate(paddingX.toFloat(), y)
            gstinLayout.draw(canvas)
            canvas.restore()
            y += gstinLayout.height + 4f
        }

        y += 4f
        canvas.drawLine(paddingX.toFloat(), y, (widthDots - paddingX).toFloat(), y, linePaint)
        y += 8f

        canvas.save()
        canvas.translate(paddingX.toFloat(), y)
        titleLayout.draw(canvas)
        canvas.restore()
        y += titleLayout.height + 6f

        canvas.save()
        canvas.translate(paddingX.toFloat(), y)
        periodLayout.draw(canvas)
        canvas.restore()
        y += periodLayout.height + 8f

        canvas.save()
        canvas.translate(paddingX.toFloat(), y)
        suppLayout.draw(canvas)
        canvas.restore()
        y += suppLayout.height + 4f

        if (supplier.phone.isNotBlank()) {
            val suppPhoneLayout = createStaticLayout(if (isBengali) "ফোন: ${formatBengaliDigits(supplier.phone)}" else "Phone: ${supplier.phone}", bodyPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
            canvas.save()
            canvas.translate(paddingX.toFloat(), y)
            suppPhoneLayout.draw(canvas)
            canvas.restore()
            y += suppPhoneLayout.height + 4f
        }

        canvas.save()
        canvas.translate(paddingX.toFloat(), y)
        genDateLayout.draw(canvas)
        canvas.restore()
        y += genDateLayout.height + 8f

        canvas.drawLine(paddingX.toFloat(), y, (widthDots - paddingX).toFloat(), y, linePaint)
        y += 10f

        val sumTitle = if (isBengali) "মহাজন হিসাব সারসংক্ষেপ (SUMMARY)" else "SUPPLIER FINANCIAL SUMMARY"
        val sumTitleLayout = createStaticLayout(sumTitle, headerPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
        canvas.save()
        canvas.translate(paddingX.toFloat(), y)
        sumTitleLayout.draw(canvas)
        canvas.restore()
        y += sumTitleLayout.height + 6f

        if (isFilteredRange) {
            val openBalStr = if (isBengali) "প্রারম্ভিক পাওনা (Opening Payable): ₹${formatBengaliDigits("%.2f".format(effectiveOpeningBalance))}" else "Opening Payable: Rs.%.2f".format(effectiveOpeningBalance)
            val openLayout = createStaticLayout(openBalStr, bodyPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
            canvas.save()
            canvas.translate(paddingX.toFloat(), y)
            openLayout.draw(canvas)
            canvas.restore()
            y += 22f
        }

        val totalPurchStr = if (isBengali) "মোট ক্রয় চালান: ₹${formatBengaliDigits("%.2f".format(totalPurchased))}" else "Total Purchased: Rs.%.2f".format(totalPurchased)
        val debLayout = createStaticLayout(totalPurchStr, bodyPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
        canvas.save()
        canvas.translate(paddingX.toFloat(), y)
        debLayout.draw(canvas)
        canvas.restore()
        y += 22f

        val totalPaidStr = if (isBengali) "মোট পরিশোধিত: ₹${formatBengaliDigits("%.2f".format(totalPaid))}" else "Total Payments Made: Rs.%.2f".format(totalPaid)
        val credLayout = createStaticLayout(totalPaidStr, bodyPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
        canvas.save()
        canvas.translate(paddingX.toFloat(), y)
        credLayout.draw(canvas)
        canvas.restore()
        y += 22f

        val netBalStr = if (isBengali) "বর্তমান দেয় বকেয়া: ₹${formatBengaliDigits("%.2f".format(netBalance))}" else "Current Payable Dues: Rs.%.2f".format(netBalance)
        val netBalLayout = createStaticLayout(netBalStr, bodyBoldPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
        canvas.save()
        canvas.translate(paddingX.toFloat(), y)
        netBalLayout.draw(canvas)
        canvas.restore()
        y += 28f

        canvas.drawLine(paddingX.toFloat(), y, (widthDots - paddingX).toFloat(), y, linePaint)
        y += 10f

        canvas.save()
        canvas.translate(paddingX.toFloat(), y)
        tableTitleLayout.draw(canvas)
        canvas.restore()
        y += tableTitleLayout.height + 6f

        if (isFilteredRange && effectiveOpeningBalance != 0.0) {
            val openLabel = if (isBengali) "প্রারম্ভিক দেনা (Opening Balance)" else "Opening Payable Balance"
            val openAmtStr = if (isBengali) "₹${formatBengaliDigits("%.2f".format(effectiveOpeningBalance))}" else "Rs.%.2f".format(effectiveOpeningBalance)
            val row1Layout = createStaticLayout(openLabel, bodyBoldPaint, (contentWidth * 0.65).toInt(), Layout.Alignment.ALIGN_NORMAL)
            val amtLayout = createStaticLayout(openAmtStr, bodyBoldPaint, (contentWidth * 0.35).toInt(), Layout.Alignment.ALIGN_OPPOSITE)

            canvas.save()
            canvas.translate(paddingX.toFloat(), y)
            row1Layout.draw(canvas)
            canvas.restore()

            canvas.save()
            canvas.translate((paddingX + (contentWidth * 0.65)).toFloat(), y)
            amtLayout.draw(canvas)
            canvas.restore()

            y += Math.max(row1Layout.height, amtLayout.height) + 6f
        }

        if (transactions.isEmpty() && (!isFilteredRange || effectiveOpeningBalance == 0.0)) {
            val emptyTxt = if (isBengali) "কোন চালান বা পেমেন্ট পাওয়া যায়নি" else "No bills or payments in this period"
            val emptyLayout = createStaticLayout(emptyTxt, bodyPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
            canvas.save()
            canvas.translate(paddingX.toFloat(), y)
            emptyLayout.draw(canvas)
            canvas.restore()
            y += 24f
        } else {
            val entrySdf = SimpleDateFormat("dd/MM hh:mm a", Locale.getDefault())
            transactions.forEach { item ->
                val dateFmt = entrySdf.format(Date(item.datetime))
                val dateDisplay = if (isBengali) formatBengaliDigits(dateFmt) else dateFmt

                val typeDisplay = when (item.type) {
                    "PURCHASE" -> if (isBengali) "চালান (${item.refNo})" else "Bill (${item.refNo})"
                    "PAYMENT" -> if (isBengali) "পেমেন্ট প্রদান" else "Payment Made"
                    else -> item.refNo
                }

                val amtStr = if (item.type == "PAYMENT") {
                    if (isBengali) "-₹${formatBengaliDigits("%.2f".format(item.paidAmount))}" else "-Rs.%.2f".format(item.paidAmount)
                } else {
                    if (isBengali) "+₹${formatBengaliDigits("%.2f".format(item.dueImpact))}" else "+Rs.%.2f".format(item.dueImpact)
                }

                val row1 = "$dateDisplay | $typeDisplay"
                val row1Layout = createStaticLayout(row1, bodyPaint, (contentWidth * 0.68).toInt(), Layout.Alignment.ALIGN_NORMAL)
                val amtLayout = createStaticLayout(amtStr, bodyBoldPaint, (contentWidth * 0.32).toInt(), Layout.Alignment.ALIGN_OPPOSITE)

                canvas.save()
                canvas.translate(paddingX.toFloat(), y)
                row1Layout.draw(canvas)
                canvas.restore()

                canvas.save()
                canvas.translate((paddingX + (contentWidth * 0.68)).toFloat(), y)
                amtLayout.draw(canvas)
                canvas.restore()

                y += Math.max(row1Layout.height, amtLayout.height) + 2f

                if (!item.note.isNullOrBlank()) {
                    val noteStr = "Note: ${item.note}"
                    val noteLayout = createStaticLayout(noteStr, subtitlePaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
                    canvas.save()
                    canvas.translate(paddingX.toFloat(), y)
                    noteLayout.draw(canvas)
                    canvas.restore()
                    y += noteLayout.height + 2f
                }
                y += 6f
            }
        }

        canvas.drawLine(paddingX.toFloat(), y, (widthDots - paddingX).toFloat(), y, linePaint)
        y += 10f

        canvas.save()
        canvas.translate(paddingX.toFloat(), y)
        footer1Layout.draw(canvas)
        canvas.restore()
        y += footer1Layout.height + 4f

        canvas.save()
        canvas.translate(paddingX.toFloat(), y)
        footer2Layout.draw(canvas)
        canvas.restore()

        return bitmap
    }

    private fun cleanEscPosNotes(note: String?): String {
        if (note.isNullOrBlank()) return ""
        return note.replace(Regex("\\[PHOTO:[^\\]]+\\]"), "").trim()
    }

    fun generateThermalReceiptBitmap(
        saleWithItems: SaleWithItems,
        isBengali: Boolean,
        widthDots: Int
    ): Bitmap {
        if (widthDots == 384) {
            return ThermalPrintingService.formatSaleReceipt58mm(
                saleWithItems = saleWithItems,
                config = ThermalPrintingService.ThermalReceiptConfig(isBengali = isBengali)
            )
        }
        val paddingX = if (widthDots == 384) 16 else 24
        val contentWidth = widthDots - (paddingX * 2)

        // TextPaints - Bolder & optimized font scale for safe high-contrast 58mm / 80mm thermal printing without side overflow
        val titlePaint = TextPaint().apply {
            color = Color.BLACK
            textSize = if (widthDots == 384) 22f else 26f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val subtitlePaint = TextPaint().apply {
            color = Color.BLACK
            textSize = if (widthDots == 384) 13.5f else 16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val headerPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = if (widthDots == 384) 16f else 19f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val bodyPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = if (widthDots == 384) 14f else 16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val bodyBoldPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = if (widthDots == 384) 14.5f else 17f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val linePaint = Paint().apply {
            color = Color.BLACK
            strokeWidth = if (widthDots == 384) 2.0f else 2.5f
            style = Paint.Style.STROKE
            isAntiAlias = false
        }

        // Store Details
        val rawStoreName = StoreInfoManager.getStoreDisplayName(isBengali).ifBlank { "DUKAAN POS" }
        val storeAddress = StoreInfoManager.getStoreDisplayAddress(isBengali)
        val storePhone = StoreInfoManager.phone
        val gstin = StoreInfoManager.gstin

        // Format Date
        val sdf = SimpleDateFormat("dd/MM/yyyy hh:mm a", Locale.getDefault())
        val dateRaw = sdf.format(Date(saleWithItems.sale.datetime))
        val dateStr = if (isBengali) formatBengaliDigits(dateRaw) else dateRaw

        val billNoRaw = saleWithItems.sale.id.takeLast(6)
        val billNoStr = if (isBengali) formatBengaliDigits(billNoRaw) else billNoRaw

        // First Pass: Measure Total Height Required
        var measuredHeight = 15f

        val storeNameLayout = createStaticLayout(rawStoreName, titlePaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
        measuredHeight += storeNameLayout.height + 6f

        if (storeAddress.isNotBlank()) {
            val addrLayout = createStaticLayout(storeAddress, subtitlePaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
            measuredHeight += addrLayout.height + 4f
        }

        val phoneText = if (isBengali) "ফোন: ${formatBengaliDigits(storePhone)}" else "Ph: $storePhone"
        val phoneLayout = createStaticLayout(phoneText, subtitlePaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
        measuredHeight += phoneLayout.height + 4f

        if (gstin.isNotBlank()) {
            val gstinText = "GSTIN: $gstin"
            val gstinLayout = createStaticLayout(gstinText, subtitlePaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
            measuredHeight += gstinLayout.height + 4f
        }

        measuredHeight += 12f // Divider

        val memoTitle = if (isBengali) "ক্যাশ মেমো / রসিদ" else "CASH MEMO / RECEIPT"
        val memoLayout = createStaticLayout(memoTitle, headerPaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
        measuredHeight += memoLayout.height + 6f

        val billNoText = if (isBengali) "বিল নং: #$billNoStr" else "Bill No: #$billNoStr"
        val billNoLayout = createStaticLayout(billNoText, bodyPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
        measuredHeight += billNoLayout.height + 2f

        val dateText = if (isBengali) "তারিখ: $dateStr" else "Date: $dateStr"
        val dateLayout = createStaticLayout(dateText, bodyPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
        measuredHeight += dateLayout.height + 2f

        if (!saleWithItems.sale.customerName.isNullOrBlank()) {
            val custName = BengaliReceiptTranslator.translateCustomerName(saleWithItems.sale.customerName, isBengali)
            val custText = if (isBengali) "গ্রাহক: $custName" else "Customer: $custName"
            val custLayout = createStaticLayout(custText, bodyPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
            measuredHeight += custLayout.height + 2f
        }

        if (!saleWithItems.sale.staffName.isNullOrBlank()) {
            val staffName = BengaliReceiptTranslator.translateStaffName(saleWithItems.sale.staffName, isBengali)
            val staffText = if (isBengali) "স্টাফ: $staffName" else "Staff: $staffName"
            val staffLayout = createStaticLayout(staffText, bodyPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
            measuredHeight += staffLayout.height + 2f
        }

        measuredHeight += 12f // Divider

        // Items Table Header Height
        measuredHeight += 22f + 8f

        // Item Rows Height
        saleWithItems.items.forEach { item ->
            val rawName = if (isBengali) {
                if (item.productNameBn.isNotBlank() && BengaliReceiptTranslator.containsBengali(item.productNameBn)) {
                    item.productNameBn
                } else {
                    BengaliReceiptTranslator.translateItem(item.productNameEn.ifBlank { item.productNameBn })
                }
            } else {
                item.productNameEn.ifBlank { item.productNameBn }.ifBlank { "Item" }
            }
            val cleanedName = BengaliReceiptTranslator.cleanReceiptProductName(rawName)

            val nameLayout = createStaticLayout(cleanedName, bodyBoldPaint, (contentWidth * 0.50).toInt(), Layout.Alignment.ALIGN_NORMAL)
            var rowH = Math.max(nameLayout.height + 2f, 24f)
            if (item.hasDiscount()) {
                rowH += 16f
            }
            measuredHeight += rowH + 4f
        }

        measuredHeight += 12f // Divider

        // Totals Height
        val totalAmount = saleWithItems.sale.totalAmount
        val discount = saleWithItems.sale.discount
        val finalAmount = saleWithItems.sale.finalAmount
        val totalMrpSavingsEsc = saleWithItems.items.sumOf { it.getSavingsAmount() }
        val overallSavingsEsc = totalMrpSavingsEsc + discount

        if (discount > 0) {
            measuredHeight += 20f
            measuredHeight += 20f
        }
        if (overallSavingsEsc > 0.0) {
            measuredHeight += 20f
        }
        measuredHeight += 26f // Grand total

        val sale = saleWithItems.sale
        val excessPaid = (sale.receivedAmount - sale.finalAmount).coerceAtLeast(0.0)
        val netRemainingDue = (sale.previousBalance + sale.dueAmount - excessPaid).coerceAtLeast(0.0)
        val advanceCredit = (excessPaid - (sale.previousBalance + sale.dueAmount)).coerceAtLeast(0.0)
        val hasCustomer = !sale.customerName.isNullOrBlank() || !sale.customerId.isNullOrBlank()

        measuredHeight += 20f // Paid amount line
        if (sale.dueAmount > 0) measuredHeight += 20f // Added credit line
        if (excessPaid > 0 && sale.previousBalance > 0) {
            measuredHeight += 20f // Paid towards prev due
            measuredHeight += 20f // Prev due
            measuredHeight += 34f // Prominent Total due with double line
            if (advanceCredit > 0) measuredHeight += 20f // Advance credit
        } else if (excessPaid > 0) {
            measuredHeight += 20f // Change returned or Advance
        } else if (sale.previousBalance > 0 || sale.dueAmount > 0) {
            if (sale.previousBalance > 0) measuredHeight += 20f // Prev due
            measuredHeight += 34f // Prominent Total due with double line
        }

        val payModeDisplay = BengaliReceiptTranslator.translatePaymentMode(saleWithItems.sale.paymentMode, isBengali)
        val payModeText = if (isBengali) "পেমেন্ট: $payModeDisplay" else "Payment: $payModeDisplay"
        measuredHeight += 20f + 12f // Divider

        // UPI QR Code (if configured & applicable)
        val upiId = StoreInfoManager.merchantUpiId
        val payeeName = StoreInfoManager.merchantPayeeName.ifBlank { rawStoreName }
        val totalDue = netRemainingDue
        val paymentModeUpper = sale.paymentMode.uppercase()
        val shouldShowQr = StoreInfoManager.showQrOnPdf &&
                upiId.isNotBlank() &&
                (totalDue > 0.0 || paymentModeUpper.contains("UPI"))

        var qrBmp: Bitmap? = null
        var qrAmount = 0.0
        if (shouldShowQr) {
            qrAmount = if (totalDue > 0.0) totalDue else finalAmount
            val tnText = if (totalDue > 0.0) "Due Payment Bill #$billNoRaw" else "Bill #$billNoRaw"
            val upiPayload = StoreInfoManager.buildUpiPayUrl(
                upiId = upiId,
                payeeName = payeeName,
                amount = qrAmount,
                note = tnText
            )
            val qrSize = if (widthDots == 384) 210 else 280
            qrBmp = generateQrBitmap(upiPayload, qrSize)
            if (qrBmp != null) {
                measuredHeight += qrBmp.height + 24f
            }
        }

        // Footer Note
        val footerNote = BengaliReceiptTranslator.getFooterGreeting(isBengali, StoreInfoManager.customFooterNote)
        if (footerNote.isNotBlank()) {
            val footerLayout = createStaticLayout(footerNote, subtitlePaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
            measuredHeight += footerLayout.height + 8f
        }

        measuredHeight += 6f // Bottom padding

        // Second Pass: Render Bitmap
        val bitmap = Bitmap.createBitmap(widthDots, measuredHeight.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        var curY = 4f

        // Draw Store Name
        canvas.save()
        canvas.translate(paddingX.toFloat(), curY)
        storeNameLayout.draw(canvas)
        canvas.restore()
        curY += storeNameLayout.height + 4f

        if (storeAddress.isNotBlank()) {
            val addrLayout = createStaticLayout(storeAddress, subtitlePaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
            canvas.save()
            canvas.translate(paddingX.toFloat(), curY)
            addrLayout.draw(canvas)
            canvas.restore()
            curY += addrLayout.height + 3f
        }

        canvas.save()
        canvas.translate(paddingX.toFloat(), curY)
        phoneLayout.draw(canvas)
        canvas.restore()
        curY += phoneLayout.height + 3f

        if (gstin.isNotBlank()) {
            val gstinText = "GSTIN: $gstin"
            val gstinLayout = createStaticLayout(gstinText, subtitlePaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
            canvas.save()
            canvas.translate(paddingX.toFloat(), curY)
            gstinLayout.draw(canvas)
            canvas.restore()
            curY += gstinLayout.height + 3f
        }

        // Dashed Divider
        curY += 6f
        drawDashedLine(canvas, paddingX.toFloat(), curY, (widthDots - paddingX).toFloat(), linePaint)
        curY += 8f

        // Memo Title
        canvas.save()
        canvas.translate(paddingX.toFloat(), curY)
        memoLayout.draw(canvas)
        canvas.restore()
        curY += memoLayout.height + 6f

        // Bill Info
        canvas.save()
        canvas.translate(paddingX.toFloat(), curY)
        billNoLayout.draw(canvas)
        canvas.restore()
        curY += billNoLayout.height + 2f

        canvas.save()
        canvas.translate(paddingX.toFloat(), curY)
        dateLayout.draw(canvas)
        canvas.restore()
        curY += dateLayout.height + 2f

        if (!saleWithItems.sale.customerName.isNullOrBlank()) {
            val custName = BengaliReceiptTranslator.translateCustomerName(saleWithItems.sale.customerName, isBengali)
            val custText = if (isBengali) "গ্রাহক: $custName" else "Customer: $custName"
            val custLayout = createStaticLayout(custText, bodyPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
            canvas.save()
            canvas.translate(paddingX.toFloat(), curY)
            custLayout.draw(canvas)
            canvas.restore()
            curY += custLayout.height + 2f
        }

        if (!saleWithItems.sale.staffName.isNullOrBlank()) {
            val staffName = BengaliReceiptTranslator.translateStaffName(saleWithItems.sale.staffName, isBengali)
            val staffText = if (isBengali) "স্টাফ: $staffName" else "Staff: $staffName"
            val staffLayout = createStaticLayout(staffText, bodyPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
            canvas.save()
            canvas.translate(paddingX.toFloat(), curY)
            staffLayout.draw(canvas)
            canvas.restore()
            curY += staffLayout.height + 2f
        }

        // Dashed Divider
        curY += 6f
        drawDashedLine(canvas, paddingX.toFloat(), curY, (widthDots - paddingX).toFloat(), linePaint)
        curY += 8f

        // Items Table Header
        val col1Title = if (isBengali) "আইটেম (Item)" else "Item"
        val col2Title = if (isBengali) "পরিমাণ (Qty)" else "Qty"
        val col3Title = if (isBengali) "মূল্য (Amt)" else "Amount"

        val col1Width = (contentWidth * 0.50).toFloat()
        val col2Width = (contentWidth * 0.25).toFloat()
        val col3Width = (contentWidth * 0.25).toFloat()

        val xCol1 = paddingX.toFloat()
        val xCol2 = xCol1 + col1Width
        val xCol3 = xCol2 + col2Width

        canvas.drawText(col1Title, xCol1, curY + 14f, bodyBoldPaint)

        val rightAlignPaint = TextPaint(bodyBoldPaint).apply { textAlign = Paint.Align.RIGHT }
        canvas.drawText(col2Title, xCol2 + col2Width - 4f, curY + 14f, rightAlignPaint)
        canvas.drawText(col3Title, xCol3 + col3Width, curY + 14f, rightAlignPaint)

        curY += 20f
        canvas.drawLine(paddingX.toFloat(), curY, (widthDots - paddingX).toFloat(), curY, linePaint)
        curY += 6f

        // Render Item Rows
        val bodyRightPaint = TextPaint(bodyPaint).apply { textAlign = Paint.Align.RIGHT }

        saleWithItems.items.forEach { item ->
            val rawName = if (isBengali) {
                if (item.productNameBn.isNotBlank() && BengaliReceiptTranslator.containsBengali(item.productNameBn)) {
                    item.productNameBn
                } else {
                    BengaliReceiptTranslator.translateItem(item.productNameEn.ifBlank { item.productNameBn })
                }
            } else {
                item.productNameEn.ifBlank { item.productNameBn }.ifBlank { "Item" }
            }
            val cleanedName = BengaliReceiptTranslator.cleanReceiptProductName(rawName)

            val qtyStr = formatQtyWithUnit(item.quantity, item.unitType, isBengali)
            val priceNum = "%.2f".format(Locale.US, item.subtotal)
            val priceStr = if (isBengali) "৳${formatBengaliDigits(priceNum)}" else "Rs.$priceNum"

            val nameLayout = createStaticLayout(cleanedName, bodyBoldPaint, col1Width.toInt(), Layout.Alignment.ALIGN_NORMAL)

            canvas.save()
            canvas.translate(xCol1, curY)
            nameLayout.draw(canvas)
            canvas.restore()

            canvas.drawText(qtyStr, xCol2 + col2Width - 4f, curY + 14f, bodyRightPaint)
            canvas.drawText(priceStr, xCol3 + col3Width, curY + 14f, bodyRightPaint)

            var rowH = Math.max(nameLayout.height + 2f, 22f)
            if (item.hasDiscount()) {
                val discLine = BengaliReceiptTranslator.formatDiscountItemLine(item.getEffectiveMrp(), item.unitPrice, item.quantity, isBengali)
                val discPaint = TextPaint(subtitlePaint).apply {
                    color = Color.rgb(46, 125, 50)
                    textSize = if (widthDots == 384) 10.5f else 12.5f
                }
                canvas.drawText(discLine, xCol1, curY + rowH + 10f, discPaint)
                rowH += 16f
            }
            curY += rowH + 4f
        }

        // Dashed Divider
        curY += 4f
        drawDashedLine(canvas, paddingX.toFloat(), curY, (widthDots - paddingX).toFloat(), linePaint)
        curY += 8f

        // Subtotal & Discount
        if (discount > 0) {
            val subNum = "%.2f".format(Locale.US, totalAmount)
            val subStr = if (isBengali) "৳${formatBengaliDigits(subNum)}" else "Rs.$subNum"
            val subLabel = if (isBengali) "উপমোট:" else "Subtotal:"

            canvas.drawText(subLabel, xCol1, curY + 14f, bodyPaint)
            canvas.drawText(subStr, xCol3 + col3Width, curY + 14f, bodyRightPaint)
            curY += 20f

            val discNum = "%.2f".format(Locale.US, discount)
            val discStr = if (isBengali) "-৳${formatBengaliDigits(discNum)}" else "-Rs.$discNum"
            val discLabel = if (isBengali) "ছাড়:" else "Discount:"

            canvas.drawText(discLabel, xCol1, curY + 14f, bodyPaint)
            canvas.drawText(discStr, xCol3 + col3Width, curY + 14f, bodyRightPaint)
            curY += 20f
        }

        if (overallSavingsEsc > 0.0) {
            val overallPctEsc = if ((totalAmount + totalMrpSavingsEsc) > 0) (overallSavingsEsc / (totalAmount + totalMrpSavingsEsc)) * 100.0 else 0.0
            val savNum = "%.2f".format(Locale.US, overallSavingsEsc)
            val savStr = if (isBengali) "৳${formatBengaliDigits(savNum)}" else "Rs.$savNum"
            val savLabel = if (isBengali) "মোট সাশ্রয় (${"%.0f".format(overallPctEsc)}%):" else "Total Savings (${"%.0f".format(overallPctEsc)}%):"
            val savPaint = TextPaint(bodyBoldPaint).apply { color = Color.rgb(46, 125, 50) }
            val savRightPaint = TextPaint(savPaint).apply { textAlign = Paint.Align.RIGHT }

            canvas.drawText(savLabel, xCol1, curY + 14f, savPaint)
            canvas.drawText(savStr, xCol3 + col3Width, curY + 14f, savRightPaint)
            curY += 20f
        }

        // Grand Total
        val grandNum = "%.2f".format(Locale.US, finalAmount)
        val grandStr = if (isBengali) "৳${formatBengaliDigits(grandNum)}" else "Rs.$grandNum"
        val grandLabel = if (isBengali) "মোট:" else "Total:"

        val grandLabelPaint = TextPaint(bodyBoldPaint).apply { textSize = if (widthDots == 384) 18f else 22f }
        val grandValuePaint = TextPaint(bodyBoldPaint).apply {
            textSize = if (widthDots == 384) 18f else 22f
            textAlign = Paint.Align.RIGHT
        }

        canvas.drawText(grandLabel, xCol1, curY + 16f, grandLabelPaint)
        canvas.drawText(grandStr, xCol3 + col3Width, curY + 16f, grandValuePaint)
        curY += 26f

        val recNum = "%.2f".format(Locale.US, sale.receivedAmount)
        val recStr = if (isBengali) "৳${formatBengaliDigits(recNum)}" else "Rs.$recNum"
        val recLabel = if (isBengali) "প্রাপ্ত টাকা (${sale.paymentMode}):" else "Paid (${sale.paymentMode}):"
        canvas.drawText(recLabel, xCol1, curY + 14f, bodyPaint)
        canvas.drawText(recStr, xCol3 + col3Width, curY + 14f, bodyRightPaint)
        curY += 20f

        if (sale.dueAmount > 0) {
            val dueNum = "%.2f".format(Locale.US, sale.dueAmount)
            val dueStr = if (isBengali) "৳${formatBengaliDigits(dueNum)}" else "Rs.$dueNum"
            val dueLabel = if (isBengali) "আজ বাকী যোগ:" else "Added Credit:"
            canvas.drawText(dueLabel, xCol1, curY + 14f, bodyPaint)
            canvas.drawText(dueStr, xCol3 + col3Width, curY + 14f, bodyRightPaint)
            curY += 20f
        }

        if (excessPaid > 0 && sale.previousBalance > 0) {
            val dueCleared = minOf(sale.previousBalance, excessPaid)
            val clearedNum = "%.2f".format(Locale.US, dueCleared)
            val clearedStr = if (isBengali) "৳${formatBengaliDigits(clearedNum)}" else "Rs.$clearedNum"
            val clearedLabel = if (isBengali) "পূর্বের বাকি শোধ:" else "Paid to Prev Due:"
            canvas.drawText(clearedLabel, xCol1, curY + 14f, bodyPaint)
            canvas.drawText(clearedStr, xCol3 + col3Width, curY + 14f, bodyRightPaint)
            curY += 20f

            val prevNum = "%.2f".format(Locale.US, sale.previousBalance)
            val prevStr = if (isBengali) "৳${formatBengaliDigits(prevNum)}" else "Rs.$prevNum"
            val prevLabel = if (isBengali) "পূর্বের বাকী বকেয়া:" else "Prev Due Balance:"
            canvas.drawText(prevLabel, xCol1, curY + 14f, bodyPaint)
            canvas.drawText(prevStr, xCol3 + col3Width, curY + 14f, bodyRightPaint)
            curY += 20f

            val totNum = "%.2f".format(Locale.US, netRemainingDue)
            val totStr = if (isBengali) "৳${formatBengaliDigits(totNum)}" else "Rs.$totNum"
            val totLabel = if (isBengali) "সর্বমোট বাকী (TOTAL DUE):" else "TOTAL DUE NOW:"
            
            curY += 4f
            canvas.drawLine(paddingX.toFloat(), curY, (widthDots - paddingX).toFloat(), curY, linePaint)
            curY += 6f
            canvas.drawText(totLabel, xCol1, curY + 16f, grandLabelPaint)
            canvas.drawText(totStr, xCol3 + col3Width, curY + 16f, grandValuePaint)
            curY += 22f
            canvas.drawLine(paddingX.toFloat(), curY, (widthDots - paddingX).toFloat(), curY, linePaint)
            curY += 8f

            if (advanceCredit > 0) {
                val advNum = "%.2f".format(Locale.US, advanceCredit)
                val advStr = if (isBengali) "৳${formatBengaliDigits(advNum)}" else "Rs.$advNum"
                val advLabel = if (isBengali) "অগ্রিম জমা ব্যালেন্স:" else "Advance Balance:"
                canvas.drawText(advLabel, xCol1, curY + 14f, bodyPaint)
                canvas.drawText(advStr, xCol3 + col3Width, curY + 14f, bodyRightPaint)
                curY += 20f
            }
        } else if (excessPaid > 0) {
            val exNum = "%.2f".format(Locale.US, excessPaid)
            val exStr = if (isBengali) "৳${formatBengaliDigits(exNum)}" else "Rs.$exNum"
            val exLabel = if (hasCustomer) {
                if (isBengali) "অগ্রিম জমা ব্যালেন্স:" else "Advance Balance:"
            } else {
                if (isBengali) "ফেরত দেওয়া টাকা:" else "Change Returned:"
            }
            canvas.drawText(exLabel, xCol1, curY + 14f, bodyPaint)
            canvas.drawText(exStr, xCol3 + col3Width, curY + 14f, bodyRightPaint)
            curY += 20f
        } else if (sale.previousBalance > 0 || sale.dueAmount > 0) {
            if (sale.previousBalance > 0) {
                val prevNum = "%.2f".format(Locale.US, sale.previousBalance)
                val prevStr = if (isBengali) "৳${formatBengaliDigits(prevNum)}" else "Rs.$prevNum"
                val prevLabel = if (isBengali) "পূর্বের বাকী বকেয়া:" else "Prev Due Balance:"
                canvas.drawText(prevLabel, xCol1, curY + 14f, bodyPaint)
                canvas.drawText(prevStr, xCol3 + col3Width, curY + 14f, bodyRightPaint)
                curY += 20f
            }

            val totNum = "%.2f".format(Locale.US, sale.previousBalance + sale.dueAmount)
            val totStr = if (isBengali) "৳${formatBengaliDigits(totNum)}" else "Rs.$totNum"
            val totLabel = if (isBengali) "সর্বমোট বাকী (TOTAL DUE):" else "TOTAL DUE NOW:"
            
            curY += 4f
            canvas.drawLine(paddingX.toFloat(), curY, (widthDots - paddingX).toFloat(), curY, linePaint)
            curY += 6f
            canvas.drawText(totLabel, xCol1, curY + 16f, grandLabelPaint)
            canvas.drawText(totStr, xCol3 + col3Width, curY + 16f, grandValuePaint)
            curY += 22f
            canvas.drawLine(paddingX.toFloat(), curY, (widthDots - paddingX).toFloat(), curY, linePaint)
            curY += 8f
        }

        // Payment Mode
        val payLayout = createStaticLayout(payModeText, bodyPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
        canvas.save()
        canvas.translate(xCol1, curY)
        payLayout.draw(canvas)
        canvas.restore()
        curY += payLayout.height + 6f

        // Divider
        drawDashedLine(canvas, paddingX.toFloat(), curY, (widthDots - paddingX).toFloat(), linePaint)
        curY += 10f

        // QR Code
        if (qrBmp != null) {
            val qrX = (widthDots - qrBmp.width) / 2f
            canvas.drawBitmap(qrBmp, qrX, curY, null)
            curY += qrBmp.height + 6f

            val amtFmt = String.format(Locale.US, "%.2f", qrAmount)
            val qrText = if (totalDue > 0.0) {
                if (isBengali) "বকেয়া পরিশোধের জন্য স্ক্যান করুন (₹$amtFmt)" else "Scan to Pay Total Due (₹$amtFmt)"
            } else {
                if (isBengali) "ইউপিআই পেমেন্টের জন্য স্ক্যান করুন" else "Scan to Pay via UPI"
            }
            val qrTextLayout = createStaticLayout(qrText, subtitlePaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
            canvas.save()
            canvas.translate(paddingX.toFloat(), curY)
            qrTextLayout.draw(canvas)
            canvas.restore()
            curY += qrTextLayout.height + 10f

            drawDashedLine(canvas, paddingX.toFloat(), curY, (widthDots - paddingX).toFloat(), linePaint)
            curY += 10f
        }

        // Footer Note
        if (footerNote.isNotBlank()) {
            val footerLayout = createStaticLayout(footerNote, subtitlePaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
            canvas.save()
            canvas.translate(paddingX.toFloat(), curY)
            footerLayout.draw(canvas)
            canvas.restore()
            curY += footerLayout.height + 6f
        }

        return bitmap
    }

    fun generateTestReceiptBitmap(
        isBengali: Boolean,
        widthDots: Int,
        threshold: Int = StoreInfoManager.thermalThreshold
    ): Bitmap {
        val paddingX = if (widthDots == 384) 12 else 18
        val contentWidth = widthDots - (paddingX * 2)
        val fontScale = if (StoreInfoManager.thermalReceiptFontSize == "LARGE") 1.18f else 1.0f

        val titlePaint = TextPaint().apply {
            color = Color.BLACK
            textSize = (if (widthDots == 384) 22f else 26f) * fontScale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val subtitlePaint = TextPaint().apply {
            color = Color.BLACK
            textSize = (if (widthDots == 384) 13.5f else 16f) * fontScale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val bodyPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = (if (widthDots == 384) 14.5f else 17f) * fontScale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val linePaint = Paint().apply {
            color = Color.BLACK
            strokeWidth = if (widthDots == 384) 2.0f else 2.5f
            style = Paint.Style.STROKE
            isAntiAlias = false
        }

        val storeName = StoreInfoManager.storeName.ifBlank { "DUKAAN STORE" }
        val titleText = if (isBengali) "৫8mm ব্লুটুথ থার্মাল প্রিন্টার টেস্ট" else "58mm Thermal Printer Test"
        val paperLabel = if (widthDots == 384) "58mm (384 Dots @ 203 DPI)" else "80mm (576 Dots @ 203 DPI)"

        val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        val dateRaw = sdf.format(Date())
        val dateDisplay = if (isBengali) formatBengaliDigits(dateRaw) else dateRaw
        val dateText = if (isBengali) "তারিখ ও সময়: $dateDisplay" else "Date & Time: $dateDisplay"

        val effectiveDensity = threshold.coerceIn(0, 255).toString()
        val densityText = if (isBengali) "প্রিন্ট ডেনসিটি: ${formatBengaliDigits(effectiveDensity)}" else "Print Density: $effectiveDensity"
        val fontText = if (isBengali) "ফন্ট সাইজ: ${StoreInfoManager.thermalReceiptFontSize}" else "Font Size: ${StoreInfoManager.thermalReceiptFontSize}"

        // Bengali Unicode test strings verifying the actual Bengali font/bitmap rendering pipeline
        val bnSample1 = if (isBengali) "বাংলা টেক্সট টেস্ট: আমার দোকান - ক্যাশ মেমো, খাতা ও বারকোড প্রিন্টিং" else "Bengali Text Test: আমার দোকান - ক্যাশ মেমো, খাতা ও বারকোড প্রিন্টিং"
        val bnSample2 = "আমার দোকান - ক্যাশ মেমো, খাতা ও বারকোড প্রিন্টিং"
        val placeholderBnSample3 = if (isBengali) "পরিষ্কার ও গাঢ় ১-বিট থার্মাল প্রিন্ট: PASS (15.0%)" else "Crisp 1-Bit Thermal Print: PASS (15.0%)"

        val titleLayout = createStaticLayout(storeName, titlePaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
        val subLayout = createStaticLayout(titleText, bodyPaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
        val paperLayout = createStaticLayout(paperLabel, subtitlePaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
        val dateLayout = createStaticLayout(dateText, subtitlePaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
        val densityLayout = createStaticLayout(densityText, subtitlePaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
        val fontLayout = createStaticLayout(fontText, subtitlePaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
        val bn1Layout = createStaticLayout(bnSample1, bodyPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
        val bn2Layout = createStaticLayout(bnSample2, subtitlePaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
        val bn3Layout = createStaticLayout(placeholderBnSample3, bodyPaint, contentWidth, Layout.Alignment.ALIGN_CENTER)

        // Generate QR code for printer diagnostic test (merchant UPI or test diagnostic payload)
        val qrSize = if (widthDots == 384) 120 else 150
        val qrPayload = if (StoreInfoManager.merchantUpiId.isNotBlank()) {
            StoreInfoManager.buildUpiPayUrl(
                upiId = StoreInfoManager.merchantUpiId,
                payeeName = StoreInfoManager.merchantPayeeName.ifBlank { storeName },
                amount = 1.0,
                note = "Printer Test"
            )
        } else {
            "upi://pay?pa=printer.test@upi&pn=${java.net.URLEncoder.encode(storeName, "UTF-8")}&am=1.0&tn=ThermalTest"
        }
        val qrBmp = generateQrBitmap(qrPayload, qrSize)

        var totalH = 4f + titleLayout.height + 3f + subLayout.height + 3f + paperLayout.height + 6f +
                dateLayout.height + 2f + densityLayout.height + 2f + fontLayout.height + 6f +
                bn1Layout.height + 2f + bn2Layout.height + 4f + bn3Layout.height + 6f

        if (qrBmp != null) {
            totalH += qrBmp.height + 10f
        }
        totalH += 6f

        val bitmap = Bitmap.createBitmap(widthDots, totalH.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        var curY = 4f
        canvas.save()
        canvas.translate(paddingX.toFloat(), curY)
        titleLayout.draw(canvas)
        canvas.restore()
        curY += titleLayout.height + 4f

        canvas.save()
        canvas.translate(paddingX.toFloat(), curY)
        subLayout.draw(canvas)
        canvas.restore()
        curY += subLayout.height + 4f

        canvas.save()
        canvas.translate(paddingX.toFloat(), curY)
        paperLayout.draw(canvas)
        canvas.restore()
        curY += paperLayout.height + 8f

        drawDashedLine(canvas, paddingX.toFloat(), curY, (widthDots - paddingX).toFloat(), linePaint)
        curY += 10f

        canvas.save()
        canvas.translate(paddingX.toFloat(), curY)
        dateLayout.draw(canvas)
        canvas.restore()
        curY += dateLayout.height + 3f

        canvas.save()
        canvas.translate(paddingX.toFloat(), curY)
        densityLayout.draw(canvas)
        canvas.restore()
        curY += densityLayout.height + 3f

        canvas.save()
        canvas.translate(paddingX.toFloat(), curY)
        fontLayout.draw(canvas)
        canvas.restore()
        curY += fontLayout.height + 8f

        drawDashedLine(canvas, paddingX.toFloat(), curY, (widthDots - paddingX).toFloat(), linePaint)
        curY += 10f

        canvas.save()
        canvas.translate(paddingX.toFloat(), curY)
        bn1Layout.draw(canvas)
        canvas.restore()
        curY += bn1Layout.height + 3f

        canvas.save()
        canvas.translate(paddingX.toFloat(), curY)
        bn2Layout.draw(canvas)
        canvas.restore()
        curY += bn2Layout.height + 6f

        val bn3Y = curY
        canvas.save()
        canvas.translate(paddingX.toFloat(), curY)
        bn3Layout.draw(canvas)
        canvas.restore()
        curY += bn3Layout.height + 10f

        if (qrBmp != null) {
            drawDashedLine(canvas, paddingX.toFloat(), curY, (widthDots - paddingX).toFloat(), linePaint)
            curY += 10f

            val qrX = ((widthDots - qrBmp.width) / 2).coerceAtLeast(0)
            val qrY = curY.toInt()
            canvas.drawBitmap(qrBmp, qrX.toFloat(), curY, null)
            for (qx in 0 until qrBmp.width) {
                for (qy in 0 until qrBmp.height) {
                    val px = qrBmp.getPixel(qx, qy)
                    if (px != Color.WHITE) {
                        val tx = qrX + qx
                        val ty = qrY + qy
                        if (tx in 0 until widthDots && ty in 0 until bitmap.height) {
                            bitmap.setPixel(tx, ty, px)
                        }
                    }
                }
            }
            curY += qrBmp.height + 10f
        }

        drawDashedLine(canvas, paddingX.toFloat(), curY, (widthDots - paddingX).toFloat(), linePaint)

        // Real 1-bit thermal print check:
        // Calculate the percentage of black pixels across this 1-bit test template bitmap
        val blackPixelPercent = calculate1BitBlackPixelPercentage(bitmap, threshold)
        val densityStatus = evaluateThermalDensityStatus(blackPixelPercent)
        val formattedPercent = "%.1f%%".format(Locale.US, blackPixelPercent)
        val finalBnSample3 = if (isBengali) {
            "পরিষ্কার ও গাঢ় ১-বিট থার্মাল প্রিন্ট: $densityStatus (${formatBengaliDigits(formattedPercent)})"
        } else {
            "Crisp 1-Bit Thermal Print: $densityStatus ($formattedPercent)"
        }

        // Clear the bn3 placeholder region with crisp white background
        val clearPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawRect(
            0f,
            bn3Y - 2f,
            widthDots.toFloat(),
            bn3Y + bn3Layout.height + 4f,
            clearPaint
        )

        // Render the actual diagnostic label reflecting the real black pixel density chosen
        val finalBn3Layout = createStaticLayout(finalBnSample3, bodyPaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
        canvas.save()
        canvas.translate(paddingX.toFloat(), bn3Y)
        finalBn3Layout.draw(canvas)
        canvas.restore()

        return bitmap
    }

    /**
     * Calculates the percentage of black pixels (0.0% to 100.0%) when sourceBitmap
     * is converted to a 1-bit monochrome image using the specified hard luminance threshold.
     */
    fun calculate1BitBlackPixelPercentage(
        sourceBitmap: Bitmap,
        threshold: Int = StoreInfoManager.thermalThreshold
    ): Double {
        val width = sourceBitmap.width
        val height = sourceBitmap.height
        val total = width * height
        if (total == 0) return 0.0
        val pixels = IntArray(total)
        sourceBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val thresholdVal = threshold.coerceIn(0, 255)
        var blackCount = 0
        for (i in 0 until total) {
            val pixel = pixels[i]
            val alpha = (pixel ushr 24) and 0xFF
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            // Accurate alpha compositing onto white background
            val effR = (r * alpha + 255 * (255 - alpha)) / 255
            val effG = (g * alpha + 255 * (255 - alpha)) / 255
            val effB = (b * alpha + 255 * (255 - alpha)) / 255
            val luminance = (effR * 299 + effG * 587 + effB * 114) / 1000
            if (luminance < thresholdVal) {
                blackCount++
            }
        }
        return (blackCount.toDouble() / total.toDouble()) * 100.0
    }

    /**
     * Evaluates black pixel coverage percentage for the thermal test template.
     * Expected range for normal readable thermal print on this template is 5.0% to 22.0%.
     * Returns "PASS", "WARN", or "FAIL".
     */
    fun evaluateThermalDensityStatus(
        percentage: Double,
        expectedMin: Double = 5.0,
        expectedMax: Double = 22.0
    ): String {
        return when {
            percentage in expectedMin..expectedMax -> "PASS"
            percentage < (expectedMin - 2.5) || percentage > (expectedMax + 6.0) -> "FAIL"
            else -> "WARN"
        }
    }

    private fun generateQrBitmap(text: String, size: Int): Bitmap? {
        return ThermalPrintingService.generateReceiptQrCode(text, size, quietZonePadding = 6)
    }

    private fun drawDashedLine(canvas: Canvas, startX: Float, y: Float, endX: Float, paint: Paint) {
        val dashWidth = 8f
        val gapWidth = 4f
        var currentX = startX
        while (currentX < endX) {
            val nextX = Math.min(currentX + dashWidth, endX)
            canvas.drawLine(currentX, y, nextX, y, paint)
            currentX += dashWidth + gapWidth
        }
    }

    // --- ESC/POS Raster Conversion (`GS v 0`) with High-Density Thermal Heating ---

    fun bitmapToEscPosRaster(
        sourceBitmap: Bitmap,
        widthDots: Int,
        feedLines: Int = StoreInfoManager.thermalFeedLines,
        threshold: Int = StoreInfoManager.thermalThreshold,
        isBengali: Boolean = false
    ): ByteArray {
        // Ensure source bitmap perfectly matches widthDots to avoid distortion (nearest-neighbor to avoid interpolation blur)
        val scaled = if (sourceBitmap.width != widthDots) {
            val scaledHeight = ((sourceBitmap.height.toFloat() / sourceBitmap.width.toFloat()) * widthDots).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(sourceBitmap, widthDots, scaledHeight, false)
        } else {
            sourceBitmap
        }

        val bitmap = if (StoreInfoManager.thermalRasterDilation && !isBengali) {
            dilateBitmap(scaled, threshold.coerceIn(0, 255))
        } else {
            scaled
        }

        val height = bitmap.height
        val totalHeight = height
        val bytesPerLine = widthDots / 8
        val baos = ByteArrayOutputStream()

        // 1. Initialize printer to standard defaults (ESC @) - no vendor mode switch prefix
        baos.write(byteArrayOf(0x1B, 0x40))

        // 2. ESC 7 - Set optimal heating parameters: n1=7, n2=100, n3=heating interval from speed preset
        val speed = StoreInfoManager.thermalPrintSpeed
        baos.write(byteArrayOf(0x1B, 0x37, 0x07, 100.toByte(), speed.heatingInterval))

        // 3. DC2 # - Set thermal print density (0x14 = standard)
        baos.write(byteArrayOf(0x12, 0x23, 0x14.toByte()))

        // 4. Set line spacing to 0 for continuous raster
        baos.write(byteArrayOf(0x1B, 0x33, 0x00))

        val chunkHeight = if (widthDots == 384) 64 else 128
        var y = 0
        val thresholdVal = threshold.coerceIn(0, 255)
        while (y < totalHeight) {
            val currentChunkHeight = Math.min(chunkHeight, totalHeight - y)

            val xL = (bytesPerLine and 0xFF).toByte()
            val xH = ((bytesPerLine shr 8) and 0xFF).toByte()
            val yL = (currentChunkHeight and 0xFF).toByte()
            val yH = ((currentChunkHeight shr 8) and 0xFF).toByte()

            baos.write(byteArrayOf(0x1D, 0x76, 0x30, 0x00, xL, xH, yL, yH))

            val pixels = IntArray(widthDots * currentChunkHeight)
            if (y < height) {
                val availableRows = Math.min(currentChunkHeight, height - y)
                bitmap.getPixels(pixels, 0, widthDots, 0, y, widthDots, availableRows)
            }

            for (row in 0 until currentChunkHeight) {
                for (colByte in 0 until bytesPerLine) {
                    var byteVal = 0
                    for (bit in 0..7) {
                        val x = colByte * 8 + bit
                        if (x < widthDots) {
                            val pixel = pixels[row * widthDots + x]
                            val alpha = (pixel ushr 24) and 0xFF
                            val r = (pixel shr 16) and 0xFF
                            val g = (pixel shr 8) and 0xFF
                            val b = pixel and 0xFF
                            // Accurate alpha compositing onto white background
                            val effR = (r * alpha + 255 * (255 - alpha)) / 255
                            val effG = (g * alpha + 255 * (255 - alpha)) / 255
                            val effB = (b * alpha + 255 * (255 - alpha)) / 255
                            val luminance = (effR * 299 + effG * 587 + effB * 114) / 1000

                            // Hard threshold: if luminance < threshold → black, else → white. No error diffusion.
                            if (luminance < thresholdVal) {
                                byteVal = byteVal or (1 shl (7 - bit))
                            }
                        }
                    }
                    baos.write(byteVal)
                }
            }
            y += currentChunkHeight
        }

        // Reset line spacing (ESC 2)
        baos.write(byteArrayOf(0x1B, 0x32))

        // Paper feed: Reset line spacing (ESC 2) and feed linesToFeed lines using LF (0x0A) and ESC d n
        if (feedLines > 0) {
            val linesToFeed = feedLines.coerceIn(1, 8)
            repeat(linesToFeed) {
                baos.write(0x0A)
            }
            baos.write(byteArrayOf(0x1B, 0x64, linesToFeed.toByte()))
        }

        return baos.toByteArray()
    }

    fun bitmapToEscPosRaster(
        sourceBitmap: Bitmap,
        widthDots: Int,
        feedLines: Int = StoreInfoManager.thermalFeedLines,
        density: String
    ): ByteArray {
        val parsedThreshold = density.toIntOrNull() ?: when (density.uppercase()) {
            "LIGHT" -> 130
            "NORMAL" -> 150
            "DARK" -> 165
            "EXTRA_DARK" -> 180
            else -> StoreInfoManager.thermalThreshold
        }
        return bitmapToEscPosRaster(sourceBitmap, widthDots, feedLines, parsedThreshold)
    }

    suspend fun calibrateLabelPrinter(
        deviceAddress: String,
        protocol: String = "TSPL",
        widthMm: Int = 50,
        heightMm: Int = 25,
        gapMm: Int = 2
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        var socket: BluetoothSocket? = null
        var outputStream: OutputStream? = null
        try {
            val bluetoothAdapter: BluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
                ?: return@withContext Result.failure(Exception("Bluetooth not supported"))

            val device: BluetoothDevice = bluetoothAdapter.getRemoteDevice(deviceAddress)
            val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // Standard SPP UUID

            socket = try {
                device.createRfcommSocketToServiceRecord(uuid).also { it.connect() }
            } catch (e: Exception) {
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                (m.invoke(device, 1) as BluetoothSocket).also { it.connect() }
            }
            outputStream = socket.outputStream
            currentPrinterMode = PrinterMode.LABEL

            if (protocol.equals("TSPL", ignoreCase = true)) {
                // TSPL Gap detect & Form feed calibration sequence
                val cmd = "SIZE $widthMm mm, $heightMm mm\r\n" +
                        "GAP $gapMm mm, 0 mm\r\n" +
                        "AUTODETECT\r\n" +
                        "GAPDETECT\r\n" +
                        "FORMFEED\r\n"
                outputStream.write(cmd.toByteArray(Charsets.US_ASCII))
            } else {
                // ESC/POS Label Mode: Send clean label init, re-init gap sensor, and feed to gap
                outputStream.write(ESC_LABEL_INIT)
                outputStream.write(byteArrayOf(0x1D, 0x3C)) // GS <: Initialize/calibrate mark & gap sensor
                outputStream.write(ESC_FEED_TO_GAP)        // GS FF: Feed to die-cut gap
            }
            outputStream.flush()

            kotlinx.coroutines.delay(800)
            socket.close()

            Result.success(true)
        } catch (e: Exception) {
            try {
                outputStream?.close()
                socket?.close()
            } catch (_: Exception) {}
            Result.failure(e)
        }
    }

    suspend fun calibrateTsplPrinter(deviceAddress: String): Result<Boolean> =
        calibrateLabelPrinter(deviceAddress, "TSPL")

    suspend fun printBarcodeLabels(
        deviceAddress: String,
        productName: String,
        barcodeStr: String,
        price: Double,
        mrp: Double,
        quantity: Int,
        protocol: String = "TSPL",
        widthMm: Int = 50,
        heightMm: Int = 25,
        gapMm: Int = 2,
        invertOrientation: Boolean = false,
        density: String = StoreInfoManager.thermalPrinterDensity,
        quantityOrUnit: String = "1 N",
        sizeOrVariant: String? = null,
        subtitleOrTag: String = "",
        discountPercentage: Int? = null,
        storeName: String = StoreInfoManager.storeName,
        expiryDate: String? = null,
        labelStyle: String = "MODERN",
        autoCalibrate: Boolean = false,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        var socket: BluetoothSocket? = null
        var outputStream: OutputStream? = null
        var inputStream: InputStream? = null
        try {
            val bluetoothAdapter: BluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
                ?: return@withContext Result.failure(Exception("Bluetooth not supported"))

            if (!bluetoothAdapter.isEnabled) {
                return@withContext Result.failure(Exception("Bluetooth is turned off. Please enable Bluetooth."))
            }

            val device: BluetoothDevice = bluetoothAdapter.getRemoteDevice(deviceAddress)
            val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // Standard SPP UUID

            socket = try {
                device.createRfcommSocketToServiceRecord(uuid).also { it.connect() }
            } catch (e: Exception) {
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                (m.invoke(device, 1) as BluetoothSocket).also { it.connect() }
            }
            outputStream = socket.outputStream
            inputStream = socket.inputStream

            val effectiveQuantityOrUnit = (sizeOrVariant?.takeIf { it.isNotBlank() } ?: quantityOrUnit).trim().ifBlank { "1 N" }
            val isTspl = protocol.equals("TSPL", ignoreCase = true)
            val speed = StoreInfoManager.thermalPrintSpeed

            // -----------------------------------------------------------------
            // STEP 1: Send explicit GAP / Label-Mode command (Requirement 1)
            // Activates the printer's physical gap sensor and anchors print boundaries
            // -----------------------------------------------------------------
            currentPrinterMode = PrinterMode.LABEL
            if (isTspl) {
                val tsplInit = "SIZE $widthMm mm, $heightMm mm\r\n" +
                        "GAP $gapMm mm, 0 mm\r\n" +
                        "OFFSET 0 mm\r\n" +
                        "SET PEEL OFF\r\n" +
                        "SET CUTTER OFF\r\n" +
                        "SET TEAR ON\r\n" +
                        "CLS\r\n"
                outputStream.write(tsplInit.toByteArray(Charsets.US_ASCII))
            } else {
                // ESC/POS label mode: clean hardware reset and zero line spacing
                outputStream.write(ESC_LABEL_INIT)
                outputStream.write(byteArrayOf(0x1B, 0x37, 0x07, 100.toByte(), speed.heatingInterval))
                outputStream.write(byteArrayOf(0x1B, 0x33, 0x00)) // Zero line spacing
            }
            outputStream.flush()
            kotlinx.coroutines.delay(100)

            // -----------------------------------------------------------------
            // STEP 2: Calibration / auto-detect command (Requirement 2)
            // Sensor learns actual label length and gap size when requested or before first label
            // -----------------------------------------------------------------
            if (autoCalibrate) {
                if (isTspl) {
                    val calibCmd = "GAPDETECT\r\nAUTODETECT\r\nFORMFEED\r\n"
                    outputStream.write(calibCmd.toByteArray(Charsets.US_ASCII))
                } else {
                    outputStream.write(byteArrayOf(0x1D, 0x3C)) // GS <: Initialize mark & gap sensor
                    outputStream.write(ESC_FEED_TO_GAP)        // GS FF: Feed to die-cut gap
                }
                outputStream.flush()
                kotlinx.coroutines.delay(800)
            }

            // Drain any pending bytes in input stream
            try {
                while (inputStream != null && inputStream.available() > 0) {
                    inputStream.read()
                }
            } catch (_: Exception) {}

            // -----------------------------------------------------------------
            // STEP 3 & 4: Discrete per-label print jobs with gap-feed confirmation (Requirement 4)
            // Each label is sent as an independent print job, waiting for gap alignment
            // to eliminate any drift accumulation across the batch
            // -----------------------------------------------------------------
            val actualQty = quantity.coerceAtLeast(1)
            val chunkSize = speed.chunkSize

            for (labelIndex in 1..actualQty) {
                onProgress?.invoke(labelIndex, actualQty)

                val singleLabelBytes = if (isTspl) {
                    buildSingleBarcodeTsplBytes(
                        productName = productName,
                        barcodeStr = barcodeStr,
                        price = price,
                        mrp = mrp,
                        widthMm = widthMm,
                        heightMm = heightMm,
                        gapMm = gapMm,
                        invertOrientation = invertOrientation,
                        density = density,
                        quantityOrUnit = effectiveQuantityOrUnit,
                        subtitleOrTag = subtitleOrTag,
                        discountPercentage = discountPercentage,
                        storeName = storeName,
                        expiryDate = expiryDate,
                        labelStyle = labelStyle
                    )
                } else {
                    buildSingleBarcodeEscPosBytes(
                        productName = productName,
                        barcodeStr = barcodeStr,
                        price = price,
                        mrp = mrp,
                        widthMm = widthMm,
                        heightMm = heightMm,
                        gapMm = gapMm,
                        invertOrientation = invertOrientation,
                        density = density,
                        quantityOrUnit = effectiveQuantityOrUnit,
                        subtitleOrTag = subtitleOrTag,
                        discountPercentage = discountPercentage,
                        storeName = storeName,
                        expiryDate = expiryDate,
                        labelStyle = labelStyle
                    )
                }

                // Transmit single label bytes
                var offset = 0
                while (offset < singleLabelBytes.size) {
                    val len = Math.min(chunkSize, singleLabelBytes.size - offset)
                    outputStream.write(singleLabelBytes, offset, len)
                    outputStream.flush()
                    offset += len
                    if (speed.delayMs > 0 && offset < singleLabelBytes.size) {
                        try { Thread.sleep(speed.delayMs) } catch (_: Exception) {}
                    }
                }
                outputStream.flush()

                // Wait for gap-feed confirmation:
                // 1. Calculate physical mechanical feed duration (speed ~55-70 mm/sec)
                val mechanicalDelayMs = (((heightMm + gapMm) * 1000L) / 55L).coerceIn(450L, 1200L)
                kotlinx.coroutines.delay(mechanicalDelayMs)

                // 2. Poll/drain real-time status if supported by printer hardware
                try {
                    if (!isTspl) {
                        outputStream.write(byteArrayOf(0x10, 0x04, 0x04)) // DLE EOT 4: paper status
                        outputStream.flush()
                    } else {
                        outputStream.write("\u001B!?".toByteArray(Charsets.US_ASCII))
                        outputStream.flush()
                    }
                    val waitStart = System.currentTimeMillis()
                    while (System.currentTimeMillis() - waitStart < 250) {
                        if (inputStream != null && inputStream.available() > 0) {
                            inputStream.read()
                            break
                        }
                        kotlinx.coroutines.delay(25)
                    }
                } catch (_: Exception) {}
            }

            // -----------------------------------------------------------------
            // STEP 5: Clean finish without wasting labels or paper
            // TSPL PRINT 1,1 already advanced to the exact label gap. No extra FORMFEED!
            // ESC/POS GS FF already advanced to the gap. No extra ESC_FEED_TO_GAP!
            // -----------------------------------------------------------------
            try {
                if (!isTspl) {
                    outputStream.write(byteArrayOf(0x1B, 0x40)) // ESC @ (Reset to defaults)
                    outputStream.write(byteArrayOf(0x1B, 0x32)) // ESC 2 (Default line spacing)
                    if (gapMm == 0) {
                        // Continuous rolls only: small 2-line clearance to allow tearing without gap runaways
                        outputStream.write(0x0A)
                        outputStream.write(0x0A)
                    }
                    outputStream.flush()
                }
                currentPrinterMode = PrinterMode.RECEIPT
            } catch (_: Exception) {}

            try { socket.close() } catch (_: Exception) {}
            Result.success(true)
        } catch (e: Exception) {
            try {
                val isTsplProtocol = protocol.equals("TSPL", ignoreCase = true)
                if (!isTsplProtocol && currentPrinterMode == PrinterMode.LABEL) {
                    outputStream?.write(byteArrayOf(0x1B, 0x40))
                    outputStream?.write(byteArrayOf(0x1B, 0x32))
                    outputStream?.flush()
                    currentPrinterMode = PrinterMode.RECEIPT
                }
            } catch (_: Exception) {}
            try { outputStream?.close() } catch (_: Exception) {}
            try { inputStream?.close() } catch (_: Exception) {}
            try { socket?.close() } catch (_: Exception) {}
            Result.failure(e)
        }
    }

    /**
     * Helper to safely ellipsize text so it never overflows label boundaries.
     */
    private fun ellipsizeText(text: String, maxWidth: Float, paint: Paint): String {
        if (maxWidth <= 0f) return ""
        if (paint.measureText(text) <= maxWidth) return text
        var low = 0
        var high = text.length
        var best = ""
        while (low <= high) {
            val mid = (low + high) / 2
            val candidate = text.take(mid) + ".."
            if (paint.measureText(candidate) <= maxWidth) {
                best = candidate
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return best
    }

    /**
     * Generates a high-contrast bitmap calibrated precisely for thermal printers (50x25mm, 50x30mm, 38x25mm, 58mm).
     * Layout strictly matches professional retail thermal stickers:
     * - Line 1: Centered Store / Brand Name (e.g. "KALI MATA VARIETY STORE")
     * - Line 2: "Item : <PRODUCT NAME>" with optional location/subtext tag on right (e.g. "LLAM BAZAR")
     * - Line 3: "Qty : <QUANTITY>" (e.g. "Qty : 1 N", "Qty : 1 Pc", "Qty : 1 Kg")
     * - Line 4: "MRP :<PRICE>/-"
     * - Line 5: "Offer Price :<OFFER_PRICE>/- [DISCOUNT]% OFF" (when discount > 0)
     * - Line 6: 1D High-contrast Barcode
     * - Line 7: Barcode Digits (e.g. "8909091082059")
     */
    fun formatExpiryDate(rawDate: String?): String? {
        if (rawDate.isNullOrBlank()) return null
        // 1. Sanitize any consecutive slashes, dashes, dots or whitespace (e.g. "2//03/27" -> "2/03/27")
        val sanitized = rawDate.trim()
            .replace(Regex("""[/\\.\s-]+"""), "/")
            .trim('/')
        if (sanitized.isBlank()) return null

        val parts = sanitized.split('/')
        if (parts.size == 3) {
            // Check YMD: YYYY/MM/DD
            if (parts[0].length == 4 && parts[0].all { it.isDigit() } && parts[1].all { it.isDigit() } && parts[2].all { it.isDigit() }) {
                val y = parts[0].toIntOrNull() ?: 0
                val m = parts[1].toIntOrNull() ?: 0
                val d = parts[2].toIntOrNull() ?: 0
                if (m in 1..12 && d in 1..31) {
                    return "%02d/%02d/%02d".format(d, m, y % 100)
                }
            }
            // Check DMY: DD/MM/YY or DD/MM/YYYY or D/M/YY
            if (parts[0].all { it.isDigit() } && parts[1].all { it.isDigit() } && parts[2].all { it.isDigit() }) {
                val d = parts[0].toIntOrNull() ?: 0
                val m = parts[1].toIntOrNull() ?: 0
                val y = parts[2].toIntOrNull() ?: 0
                if (m in 1..12 && d in 1..31) {
                    return "%02d/%02d/%02d".format(d, m, y % 100)
                }
            }
        } else if (parts.size == 2) {
            // Check MM/YY or YYYY/MM
            if (parts[0].all { it.isDigit() } && parts[1].all { it.isDigit() }) {
                if (parts[0].length == 4) {
                    val y = parts[0].toIntOrNull() ?: 0
                    val m = parts[1].toIntOrNull() ?: 0
                    if (m in 1..12) return "%02d/%02d".format(m, y % 100)
                } else {
                    val m = parts[0].toIntOrNull() ?: 0
                    val y = parts[1].toIntOrNull() ?: 0
                    if (m in 1..12) return "%02d/%02d".format(m, y % 100)
                }
            }
        }
        return sanitized
    }

    /**
     * Calculates the standard Modulo-10 checksum digit for a 12-digit EAN-13 code.
     */
    fun calculateEan13Checksum(first12: String): Int {
        if (first12.length < 12) return 0
        var sum = 0
        for (i in 0 until 12) {
            val d = first12[i] - '0'
            sum += if (i % 2 == 0) d else d * 3
        }
        val rem = sum % 10
        return if (rem == 0) 0 else 10 - rem
    }

    /**
     * Checks if a 13-digit string has a mathematically valid EAN-13 checksum.
     */
    fun isValidEan13(code: String): Boolean {
        val clean = code.trim().replace(" ", "")
        if (clean.length != 13 || !clean.all { it.isDigit() }) return false
        val expected = calculateEan13Checksum(clean.substring(0, 12))
        return (clean[12] - '0') == expected
    }

    /**
     * Calculates the standard Modulo-10 checksum digit for an 11-digit UPC-A code.
     */
    fun calculateUpcAChecksum(first11: String): Int {
        if (first11.length < 11) return 0
        var sum = 0
        for (i in 0 until 11) {
            val d = first11[i] - '0'
            sum += if (i % 2 == 0) d * 3 else d
        }
        val rem = sum % 10
        return if (rem == 0) 0 else 10 - rem
    }

    /**
     * Checks if a 12-digit string has a valid UPC-A checksum.
     */
    fun isValidUpcA(code: String): Boolean {
        val clean = code.trim().replace(" ", "")
        if (clean.length != 12 || !clean.all { it.isDigit() }) return false
        val expected = calculateUpcAChecksum(clean.substring(0, 11))
        return (clean[11] - '0') == expected
    }

    /**
     * Calculates the standard Modulo-10 checksum digit for a 7-digit EAN-8 code.
     */
    fun calculateEan8Checksum(first7: String): Int {
        if (first7.length < 7) return 0
        var sum = 0
        for (i in 0 until 7) {
            val d = first7[i] - '0'
            sum += if (i % 2 == 0) d * 3 else d
        }
        val rem = sum % 10
        return if (rem == 0) 0 else 10 - rem
    }

    /**
     * Checks if an 8-digit string has a valid EAN-8 checksum.
     */
    fun isValidEan8(code: String): Boolean {
        val clean = code.trim().replace(" ", "")
        if (clean.length != 8 || !clean.all { it.isDigit() }) return false
        val expected = calculateEan8Checksum(clean.substring(0, 7))
        return (clean[7] - '0') == expected
    }

    /**
     * Generates a guaranteed valid GS1 EAN-13 barcode with country prefix and correct checksum.
     */
    fun generateValidEan13Barcode(prefix: String = "890"): String {
        val random9 = (100000000L..999999999L).random().toString()
        val first12 = prefix + random9
        val checksum = calculateEan13Checksum(first12)
        return first12 + checksum
    }

    /**
     * Formats raw barcode digits into standard international human-readable space-separated groups
     * (e.g. "8 901234 567890" for EAN-13, "0 12345 67890 5" for UPC-A, "1234 5678" for EAN-8).
     */
    fun formatBarcodeDisplayDigits(rawBarcode: String): String {
        val clean = rawBarcode.trim()
        if (clean.contains(" ")) return clean
        val digitsOnly = clean.filter { it.isDigit() }
        return if (digitsOnly.length == clean.length && clean.isNotEmpty()) {
            when (clean.length) {
                13 -> "${clean.substring(0, 1)} ${clean.substring(1, 7)} ${clean.substring(7, 13)}"
                12 -> "${clean.substring(0, 1)} ${clean.substring(1, 6)} ${clean.substring(6, 11)} ${clean.substring(11, 12)}"
                10 -> "${clean.substring(0, 3)} ${clean.substring(3, 6)} ${clean.substring(6, 10)}"
                8 -> "${clean.substring(0, 4)} ${clean.substring(4, 8)}"
                else -> clean.chunked(4).joinToString(" ")
            }
        } else {
            clean
        }
    }

    /**
     * Generates a high-contrast bitmap calibrated precisely for thermal printers (50x25mm, 50x30mm, 38x25mm, 58mm).
     * Layout strictly matches professional retail thermal stickers:
     * - Top: Centered Store / Brand Name (e.g. "KALI MATA VARIETY STORE") in bold sans-serif
     * - Line 1: "Item : <PRODUCT NAME>" (e.g. "Item : SLD") with optional location/subtext tag on right
     * - Line 2: "Qty : <QUANTITY>" (e.g. "Qty : 1 Piece") & "Exp : <DATE>" (e.g. "Exp : 06/03/27")
     * - Line 3: "Offer : <PRICE>/-" & "~~MRP : <MRP>/-~~ <DISCOUNT>% OFF"
     * - Line 4: 1D High-contrast Barcode
     * - Line 5: Formatted Barcode Digits (e.g. "8509 106 09 0450")
     */
    fun generateBarcodeLabelBitmap(
        storeName: String,
        productName: String,
        barcodeStr: String,
        price: Double,
        mrp: Double,
        widthMm: Int = 50,
        heightMm: Int = 25,
        quantityOrUnit: String = "1 Piece",
        sizeOrVariant: String? = null,
        subtitleOrTag: String = "",
        discountPercentage: Int? = null,
        expiryDate: String? = null,
        labelStyle: String = "MODERN"
    ): Bitmap? {
        return try {
            val cleanStoreName = storeName.ifBlank { StoreInfoManager.storeName }.trim()
            val cleanBarcode = barcodeStr.trim()
            val cleanProdName = productName.trim().ifBlank { "ITEM" }
            val cleanQty = (sizeOrVariant?.takeIf { it.isNotBlank() } ?: quantityOrUnit).trim().ifBlank { "1 Piece" }
            val cleanTag = subtitleOrTag.trim()

            // Safe prices
            val safePrice = if (price.isNaN() || price.isInfinite() || price < 0.0) 0.0 else price.coerceAtMost(99_999_999.0)
            val safeMrp = if (mrp.isNaN() || mrp.isInfinite() || mrp < 0.0) 0.0 else mrp.coerceAtMost(99_999_999.0)

            // Proper MRP & Offer Price logic:
            val effectiveMrp = if (safeMrp > 0.0) safeMrp else if (safePrice > 0.0) safePrice else 0.0
            val effectivePrice = if (safePrice > 0.0) safePrice else effectiveMrp

            // Discount percentage calculation:
            val effectiveDiscPct = if (discountPercentage != null && discountPercentage > 0) {
                discountPercentage.coerceIn(1, 99)
            } else if (effectiveMrp > effectivePrice && effectiveMrp > 0.0) {
                Math.round(((effectiveMrp - effectivePrice) / effectiveMrp) * 100.0).toInt().coerceIn(1, 99)
            } else {
                0
            }

            // Calculate exact dot dimensions for 203 DPI (8 dots/mm), max 384 dots width for 58mm head
            val rawWidthDots = (widthMm * 8).coerceIn(240, 384)
            val widthDots = (rawWidthDots / 8) * 8 // Multiple of 8 for byte-aligned raw raster
            val heightDots = (heightMm * 8).coerceIn(120, 600)

            val bitmap = Bitmap.createBitmap(widthDots, heightDots, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)

            val textPaint = TextPaint().apply {
                color = Color.BLACK
                isAntiAlias = true
            }

            val leftMargin = (widthDots * 0.045f).coerceIn(12f, 18f)
            val rightMargin = (widthDots * 0.045f).coerceIn(12f, 18f)
            val contentWidth = widthDots - leftMargin - rightMargin

            // 1. Store Name Header (Centered, bold sans-serif, deep navy/black color, no background box)
            val displayStore = cleanStoreName.uppercase()
            val storeSize = (heightDots * 0.088f).coerceIn(14f, 20f)
            var currentY = (heightDots * 0.055f).coerceIn(9f, 15f) + storeSize
            textPaint.color = Color.rgb(30, 58, 110) // Deep navy blue (converts to solid black on thermal)
            textPaint.textSize = storeSize
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textPaint.textAlign = Paint.Align.CENTER
            val storeEllipsized = ellipsizeText(displayStore, contentWidth - 4f, textPaint)
            canvas.drawText(storeEllipsized, widthDots / 2f, currentY, textPaint)
            textPaint.color = Color.BLACK

            // 2. Row 1: Item Name (Left) & Tag / Branch (Right)
            val row1FontSize = (heightDots * 0.076f).coerceIn(12f, 16.5f)
            currentY += (heightDots * 0.042f).coerceIn(7f, 13f) + row1FontSize
            textPaint.textSize = row1FontSize
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textPaint.textAlign = Paint.Align.LEFT

            val itemPrefix = "Item : "
            val itemPrefixW = textPaint.measureText(itemPrefix)

            // Draw Tag / Branch on the far right if present (e.g. "LLAM BAZAR")
            val displayTag = cleanTag.uppercase()
            val tagW = if (displayTag.isNotBlank()) textPaint.measureText(displayTag) else 0f
            if (displayTag.isNotBlank()) {
                canvas.drawText(displayTag, widthDots - rightMargin - tagW, currentY, textPaint)
            }

            // Draw Item prefix and product name on left
            canvas.drawText(itemPrefix, leftMargin, currentY, textPaint)
            val spaceForProd = if (displayTag.isNotBlank()) {
                contentWidth - itemPrefixW - tagW - 8f
            } else {
                contentWidth - itemPrefixW
            }
            val displayProd = ellipsizeText(cleanProdName.uppercase(), spaceForProd.coerceAtLeast(50f), textPaint)
            canvas.drawText(displayProd, leftMargin + itemPrefixW, currentY, textPaint)

            // 3. Row 2: Quantity (Left) & Expiry Date (Right)
            val cleanExp = formatExpiryDate(expiryDate)
            val formattedMrp = if (effectiveMrp % 1.0 == 0.0) effectiveMrp.toLong().toString() else "%.2f".format(effectiveMrp)
            val formattedPrice = if (effectivePrice % 1.0 == 0.0) effectivePrice.toLong().toString() else "%.2f".format(effectivePrice)
            val hasDiscount = effectiveDiscPct > 0 && effectiveMrp > effectivePrice

            val row2FontSize = (heightDots * 0.074f).coerceIn(11.5f, 15.5f)
            currentY += (heightDots * 0.038f).coerceIn(6f, 11f) + row2FontSize
            textPaint.textSize = row2FontSize
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textPaint.textAlign = Paint.Align.LEFT

            val qtyText = "Qty : ${cleanQty.trim()}"
            canvas.drawText(qtyText, leftMargin, currentY, textPaint)

            if (!cleanExp.isNullOrBlank()) {
                val expText = "Exp : $cleanExp"
                val expW = textPaint.measureText(expText)
                val expStartX = widthDots - rightMargin - expW
                canvas.drawText(expText, expStartX, currentY, textPaint)
            }

            // 4. Row 3: Offer Price (Left fixed anchor) & MRP (Right-aligned to constant label edge)
            val offerFontSize = (heightDots * 0.082f).coerceIn(13f, 18f)
            currentY += (heightDots * 0.040f).coerceIn(6f, 12f) + offerFontSize

            val fixedLeftEdge = leftMargin
            val fixedRightEdge = widthDots - rightMargin

            textPaint.textSize = offerFontSize
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.color = Color.BLACK

            val mrpPaint = TextPaint(textPaint).apply {
                textAlign = Paint.Align.RIGHT
                textSize = offerFontSize
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                color = Color.BLACK
            }

            val mrpText = "MRP : $formattedMrp/-"
            val mrpW = mrpPaint.measureText(mrpText)

            // Requirement 3: Always right-align MRP to a constant offset from label edge regardless of digit count
            canvas.drawText(mrpText, fixedRightEdge, currentY, mrpPaint)

            if (hasDiscount) {
                // Left side: Offer : <Price>/- (Bold) anchored at exact fixed left margin
                val offerText = "Offer : $formattedPrice/-"
                canvas.drawText(offerText, fixedLeftEdge, currentY, textPaint)

                // Strikethrough line horizontally centered through MRP text (from fixedRightEdge - mrpW to fixedRightEdge)
                val strikeY = currentY - (offerFontSize * 0.33f)
                val strikePaint = Paint().apply {
                    color = Color.BLACK
                    strokeWidth = (heightDots * 0.009f).coerceIn(1.6f, 2.4f)
                    style = Paint.Style.STROKE
                    isAntiAlias = true
                }
                canvas.drawLine(fixedRightEdge - mrpW - 1f, strikeY, fixedRightEdge + 1f, strikeY, strikePaint)

                // Draw discount percentage cleanly before MRP without moving MRP
                val discText = "$effectiveDiscPct% OFF"
                val discPaint = TextPaint(textPaint).apply {
                    textSize = (offerFontSize * 0.82f).coerceIn(10.5f, 14f)
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    color = Color.BLACK
                    textAlign = Paint.Align.RIGHT
                }
                val discW = discPaint.measureText(discText)
                val offerW = textPaint.measureText(offerText)
                val mrpLeft = fixedRightEdge - mrpW
                val availableSpace = mrpLeft - (fixedLeftEdge + offerW)
                if (availableSpace >= discW + 8f) {
                    canvas.drawText(discText, mrpLeft - 6f, currentY, discPaint)
                }
            } else {
                // Non-discounted: Left side shows "Price : <Price>/-" anchored at fixed left margin
                val priceText = if (effectivePrice > 0.0) "Price : $formattedPrice/-" else "Price : --"
                canvas.drawText(priceText, fixedLeftEdge, currentY, textPaint)

                val offerW = textPaint.measureText(priceText)
                val mrpLeft = fixedRightEdge - mrpW
                val availableSpace = mrpLeft - (fixedLeftEdge + offerW)
                if (availableSpace >= 40f) {
                    val taxText = "(Incl. of taxes)"
                    val taxPaint = TextPaint().apply {
                        textSize = (offerFontSize * 0.70f).coerceIn(8.5f, 11f)
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                        isAntiAlias = true
                        color = Color.BLACK
                        textAlign = Paint.Align.RIGHT
                    }
                    val taxW = taxPaint.measureText(taxText)
                    if (availableSpace >= taxW + 10f) {
                        canvas.drawText(taxText, mrpLeft - 6f, currentY, taxPaint)
                    }
                }
            }

            // 5. 1D Barcode Image & Centered Formatted Digits (No divider line above barcode)
            if (cleanBarcode.isNotBlank()) {
                val cleanBarcodeForEncoding = cleanBarcode.replace(" ", "")
                val digitTextSize = (heightDots * 0.065f).coerceIn(10f, 13.5f)
                val bottomPad = (heightDots * 0.032f).coerceIn(4f, 8f)
                val digitBaselineY = Math.round(heightDots - bottomPad).toFloat()

                // Allow barcode to utilize maximum safe printable width between margins
                val maxBarcodeWidth = (widthDots - (leftMargin + rightMargin).toInt() - 4).coerceAtLeast(160)
                val barcodeTop = Math.round(currentY + (heightDots * 0.022f).coerceIn(4f, 8f)).toFloat()
                val barcodeBottom = digitBaselineY - digitTextSize - 3f
                val barcodeAvailableHeight = (barcodeBottom - barcodeTop).toInt().coerceIn(24, 90)

                val barcodeBmp = PdfReceiptHelper.generate1DBarcodeBitmap(
                    text = cleanBarcodeForEncoding,
                    width = maxBarcodeWidth,
                    height = barcodeAvailableHeight
                )
                if (barcodeBmp != null) {
                    val barcodeLeft = Math.round((widthDots - barcodeBmp.width) / 2f).toFloat().coerceAtLeast(0f)
                    val drawTop = Math.round(barcodeTop).toFloat()
                    val noFilterPaint = Paint().apply {
                        isAntiAlias = false
                        isFilterBitmap = false
                        isDither = false
                    }
                    canvas.drawBitmap(barcodeBmp, barcodeLeft, drawTop, noFilterPaint)
                }

                // 6. Barcode Human-Readable Digits (Centered, Formatted with Spaces, e.g. "8 901234 567890")
                textPaint.textAlign = Paint.Align.CENTER
                textPaint.textSize = digitTextSize
                textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                textPaint.letterSpacing = 0.04f
                val displayDigits = formatBarcodeDisplayDigits(cleanBarcode)
                canvas.drawText(displayDigits, widthDots / 2f, digitBaselineY, textPaint)
                textPaint.letterSpacing = 0f
            }

            // 7. Clean outer sticker border with rounded corners (matches sticker peel cutout)
            val cornerRadius = (heightDots * 0.055f).coerceIn(8f, 18f)
            val outerBorderPaint = Paint().apply {
                color = Color.rgb(200, 205, 215)
                strokeWidth = 1.2f
                style = Paint.Style.STROKE
                isAntiAlias = true
            }
            canvas.drawRoundRect(RectF(1.5f, 1.5f, widthDots - 1.5f, heightDots - 1.5f), cornerRadius, cornerRadius, outerBorderPaint)

            bitmap
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Converts Bitmap directly into raw ESC/POS GS v 0 raster chunks without sending printer resets (ESC @)
     * or line-space resets (ESC 2) which cause cumulative feeding errors and gap drift on label rolls.
     */
    /**
     * Converts a Bitmap to 1-bit monochrome raw ESC/POS raster with hard thresholding.
     */
    fun bitmapToRawEscPosRaster(
        sourceBitmap: Bitmap,
        widthDots: Int,
        threshold: Int = StoreInfoManager.thermalThreshold
    ): ByteArray {
        val bitmap = if (sourceBitmap.width != widthDots) {
            val scaledHeight = ((sourceBitmap.height.toFloat() / sourceBitmap.width.toFloat()) * widthDots).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(sourceBitmap, widthDots, scaledHeight, false)
        } else {
            sourceBitmap
        }

        val height = bitmap.height
        val totalHeight = height
        val bytesPerLine = widthDots / 8
        val baos = ByteArrayOutputStream()
        val thresholdVal = threshold.coerceIn(0, 255)

        val chunkHeight = 128
        var y = 0
        while (y < totalHeight) {
            val currentChunkHeight = Math.min(chunkHeight, totalHeight - y)

            val xL = (bytesPerLine and 0xFF).toByte()
            val xH = ((bytesPerLine shr 8) and 0xFF).toByte()
            val yL = (currentChunkHeight and 0xFF).toByte()
            val yH = ((currentChunkHeight shr 8) and 0xFF).toByte()

            baos.write(byteArrayOf(0x1D, 0x76, 0x30, 0x00, xL, xH, yL, yH))

            val pixels = IntArray(widthDots * currentChunkHeight)
            if (y < height) {
                val availableRows = Math.min(currentChunkHeight, height - y)
                bitmap.getPixels(pixels, 0, widthDots, 0, y, widthDots, availableRows)
            }

            for (row in 0 until currentChunkHeight) {
                for (colByte in 0 until bytesPerLine) {
                    var byteVal = 0
                    for (bit in 0..7) {
                        val x = colByte * 8 + bit
                        if (x < widthDots) {
                            val pixel = pixels[row * widthDots + x]
                            val alpha = (pixel ushr 24) and 0xFF
                            val r = (pixel shr 16) and 0xFF
                            val g = (pixel shr 8) and 0xFF
                            val b = pixel and 0xFF
                            val effR = (r * alpha + 255 * (255 - alpha)) / 255
                            val effG = (g * alpha + 255 * (255 - alpha)) / 255
                            val effB = (b * alpha + 255 * (255 - alpha)) / 255
                            val luminance = (effR * 299 + effG * 587 + effB * 114) / 1000
                            // Hard threshold
                            if (luminance < thresholdVal) {
                                byteVal = byteVal or (1 shl (7 - bit))
                            }
                        }
                    }
                    baos.write(byteVal)
                }
            }
            y += currentChunkHeight
        }

        return baos.toByteArray()
    }

    fun bitmapToRawEscPosRaster(
        sourceBitmap: Bitmap,
        widthDots: Int,
        density: String
    ): ByteArray {
        val parsedThreshold = density.toIntOrNull() ?: when (density.uppercase()) {
            "LIGHT" -> 130
            "NORMAL" -> 150
            "DARK" -> 165
            "EXTRA_DARK" -> 180
            else -> StoreInfoManager.thermalThreshold
        }
        return bitmapToRawEscPosRaster(sourceBitmap, widthDots, parsedThreshold)
    }

    /**
     * Converts an RGB/ARGB Bitmap to a 1-bit monochrome Bitmap using a hard luminance threshold.
     * For each pixel: if luminance < threshold -> Black (0xFF000000), else -> White (0xFFFFFFFF).
     * No error diffusion or dithering.
     */
    fun convertToMonochromePreview(sourceBitmap: Bitmap, threshold: Int): Bitmap {
        val width = sourceBitmap.width
        val height = sourceBitmap.height
        val outBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        sourceBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val thresholdVal = threshold.coerceIn(0, 255)

        for (i in pixels.indices) {
            val color = pixels[i]
            val alpha = (color ushr 24) and 0xFF
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            val effR = (r * alpha + 255 * (255 - alpha)) / 255
            val effG = (g * alpha + 255 * (255 - alpha)) / 255
            val effB = (b * alpha + 255 * (255 - alpha)) / 255
            val luminance = (effR * 299 + effG * 587 + effB * 114) / 1000
            pixels[i] = if (luminance < thresholdVal) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        outBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return outBitmap
    }

    /**
     * Renders a list of HybridReceiptLine directly into a 1-bit monochrome Bitmap for Settings Live Preview.
     * Uses the exact same rasterization, thresholding, and dilation pipeline as the real Bluetooth print job:
     * - Native ASCII text emulation
     * - Bengali text rendered at calibrated 205 threshold without dilation (no smear)
     * - QR codes and Barcodes rendered at 200 dots without dilation (scannable)
     * - Graphical dividers matching physical 12-dot line spacing
     */
    fun renderHybridReceiptPreviewBitmap(
        lines: List<HybridReceiptLine>,
        widthDots: Int = 384,
        threshold: Int = StoreInfoManager.thermalThreshold,
        fontSize: String = StoreInfoManager.thermalReceiptFontSize,
        feedLines: Int = StoreInfoManager.thermalFeedLines
    ): Bitmap {
        val safeWidth = if (widthDots <= 0) 384 else widthDots
        val thresholdVal = threshold.coerceIn(0, 255)
        val bengaliThresholdVal = (thresholdVal + 30).coerceAtMost(220).coerceAtLeast(195)
        val maxChars = if (safeWidth == 384) (if (fontSize == "LARGE") 24 else 32) else 48
        val renderedBitmaps = mutableListOf<Bitmap>()

        for (line in lines) {
            when (line) {
                is HybridReceiptLine.Divider -> {
                    val divBitmap = renderDividerBitmap(line, safeWidth)
                    val dilated = if (StoreInfoManager.thermalRasterDilation) dilateBitmap(divBitmap, thresholdVal) else divBitmap
                    renderedBitmaps.add(convertToMonochromePreview(dilated, thresholdVal))
                }
                is HybridReceiptLine.ImageBlock -> {
                    if (line.bitmap.width > 0 && line.bitmap.height > 0) {
                        val dilated = if (StoreInfoManager.thermalRasterDilation && !line.isBarcode && !line.isQrCode) {
                            dilateBitmap(line.bitmap, thresholdVal)
                        } else {
                            line.bitmap
                        }
                        val centered = if (dilated.width != safeWidth && dilated.height > 0) {
                            val canvasBmp = Bitmap.createBitmap(safeWidth, dilated.height, Bitmap.Config.ARGB_8888)
                            val c = Canvas(canvasBmp)
                            c.drawColor(Color.WHITE)
                            val x = ((safeWidth - dilated.width) / 2f).coerceAtLeast(0f)
                            c.drawBitmap(dilated, x, 0f, null)
                            canvasBmp
                        } else {
                            dilated
                        }
                        if (centered.width > 0 && centered.height > 0) {
                            renderedBitmaps.add(convertToMonochromePreview(centered, thresholdVal))
                        }
                    }
                }
                is HybridReceiptLine.TextLine -> {
                    val isBengali = BengaliReceiptTranslator.containsBengali(line.text)
                    val isNative = isNativeTextLine(line.text)
                    val lineBitmap = if (isNative) {
                        renderNativeTextLinePreviewBitmap(line, safeWidth, fontSize, maxChars)
                    } else {
                        renderTextLineBitmap(line, safeWidth, fontSize)
                    }
                    val lineThreshold = if (isBengali) bengaliThresholdVal else thresholdVal
                    val dilated = if (StoreInfoManager.thermalRasterDilation && !isBengali && !isNative) {
                        dilateBitmap(lineBitmap, lineThreshold)
                    } else {
                        lineBitmap
                    }
                    renderedBitmaps.add(convertToMonochromePreview(dilated, lineThreshold))
                }
                is HybridReceiptLine.TwoColumnLine -> {
                    val combined = "${line.left} ${line.right}"
                    val isBengali = BengaliReceiptTranslator.containsBengali(combined)
                    val isNative = isNativeTextLine(combined)
                    val lineBitmap = if (isNative) {
                        renderNativeTwoColumnPreviewBitmap(line, safeWidth, fontSize, maxChars)
                    } else {
                        renderTwoColumnLineBitmap(line, safeWidth, fontSize)
                    }
                    val lineThreshold = if (isBengali) bengaliThresholdVal else thresholdVal
                    val dilated = if (StoreInfoManager.thermalRasterDilation && !isBengali && !isNative) {
                        dilateBitmap(lineBitmap, lineThreshold)
                    } else {
                        lineBitmap
                    }
                    renderedBitmaps.add(convertToMonochromePreview(dilated, lineThreshold))
                }
                is HybridReceiptLine.ThreeColumnLine -> {
                    val combined = "${line.col1} ${line.col2} ${line.col3}"
                    val isBengali = BengaliReceiptTranslator.containsBengali(combined)
                    val isNative = isNativeTextLine(combined)
                    val lineBitmap = if (isNative) {
                        renderNativeThreeColumnPreviewBitmap(line, safeWidth, fontSize, maxChars)
                    } else {
                        renderThreeColumnLineBitmap(line, safeWidth, fontSize)
                    }
                    val lineThreshold = if (isBengali) bengaliThresholdVal else thresholdVal
                    val dilated = if (StoreInfoManager.thermalRasterDilation && !isBengali && !isNative) {
                        dilateBitmap(lineBitmap, lineThreshold)
                    } else {
                        lineBitmap
                    }
                    renderedBitmaps.add(convertToMonochromePreview(dilated, lineThreshold))
                }
            }
        }

        val contentHeight = renderedBitmaps.sumOf { it.height }.coerceAtLeast(10)
        val feedLineHeight = if (fontSize == "LARGE") 26 else 24
        val safeFeedLines = feedLines.coerceIn(1, 8)
        val feedMarginHeight = safeFeedLines * feedLineHeight
        val totalHeight = contentHeight + feedMarginHeight
        val output = Bitmap.createBitmap(safeWidth.coerceAtLeast(10), totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.WHITE)

        var curY = 0f
        for (bmp in renderedBitmaps) {
            if (bmp.width > 0 && bmp.height > 0) {
                canvas.drawBitmap(bmp, 0f, curY, null)
                curY += bmp.height
            }
        }

        // Draw tear bar indicator at the end of paper feed so user can visually verify tear clearance!
        val tearY = totalHeight - 6f
        val tearPaint = Paint().apply {
            color = Color.rgb(175, 175, 175)
            strokeWidth = 2f
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
            isAntiAlias = true
        }
        canvas.drawLine(12f, tearY, (safeWidth - 12).toFloat(), tearY, tearPaint)

        val textPaint = TextPaint().apply {
            color = Color.rgb(150, 150, 150)
            textSize = 10f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            isAntiAlias = true
        }
        val tearLabel = "- - - ✂ TEAR BAR ($safeFeedLines ${if (safeFeedLines == 1) "LINE" else "LINES"}) - - -"
        val textWidth = textPaint.measureText(tearLabel)
        val textX = ((safeWidth - textWidth) / 2f).coerceAtLeast(4f)
        canvas.drawText(tearLabel, textX, tearY - 4f, textPaint)

        return output
    }

    private fun renderNativeTextLinePreviewBitmap(
        line: HybridReceiptLine.TextLine,
        widthDots: Int,
        fontSize: String,
        maxChars: Int
    ): Bitmap {
        val cleanText = line.text.replace("₹", "Rs.")
        val splitLines = wrapTextToLines(cleanText, maxChars)
        val lineHeight = if (line.isTitle) 32 else (if (fontSize == "LARGE") 26 else 24)
        val totalH = (splitLines.size * lineHeight).coerceAtLeast(lineHeight)
        val bmp = Bitmap.createBitmap(widthDots.coerceAtLeast(10), totalH.coerceAtLeast(10), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        val paint = TextPaint().apply {
            color = Color.BLACK
            typeface = Typeface.create(Typeface.MONOSPACE, if (line.isBold || StoreInfoManager.thermalNativeBoldMode) Typeface.BOLD else Typeface.NORMAL)
            textSize = if (line.isTitle) 18f else (if (fontSize == "LARGE") 15f else 13f)
            isAntiAlias = true
            isSubpixelText = true
        }

        var y = lineHeight - 6f
        for (sub in splitLines) {
            val textW = paint.measureText(sub)
            val x = when (line.alignment) {
                1 -> ((widthDots - textW) / 2f).coerceAtLeast(0f)
                2 -> (widthDots - textW - 8f).coerceAtLeast(0f)
                else -> 8f
            }
            canvas.drawText(sub, x, y, paint)
            y += lineHeight
        }
        return bmp
    }

    private fun renderNativeTwoColumnPreviewBitmap(
        line: HybridReceiptLine.TwoColumnLine,
        widthDots: Int,
        fontSize: String,
        maxChars: Int
    ): Bitmap {
        val lineHeight = if (fontSize == "LARGE") 26 else 24
        val bmp = Bitmap.createBitmap(widthDots.coerceAtLeast(10), lineHeight.coerceAtLeast(10), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        val paint = TextPaint().apply {
            color = Color.BLACK
            typeface = Typeface.create(Typeface.MONOSPACE, if (line.isBold || StoreInfoManager.thermalNativeBoldMode) Typeface.BOLD else Typeface.NORMAL)
            textSize = if (fontSize == "LARGE") 15f else 13f
            isAntiAlias = true
            isSubpixelText = true
        }

        val formatted = formatLeftRight(line.left, line.right, maxChars)
        canvas.drawText(formatted, 8f, lineHeight - 6f, paint)
        return bmp
    }

    private fun renderNativeThreeColumnPreviewBitmap(
        line: HybridReceiptLine.ThreeColumnLine,
        widthDots: Int,
        fontSize: String,
        maxChars: Int
    ): Bitmap {
        val lineHeight = if (fontSize == "LARGE") 26 else 24
        val w1 = if (maxChars <= 24) 11 else if (maxChars <= 32) 15 else 24
        val cleanCol1 = line.col1.replace("₹", "Rs ")
        val rows = mutableListOf<String>()

        if (cleanCol1.length > w1 && !line.isBold) {
            val firstLineName = cleanCol1.take(w1)
            val restName = cleanCol1.substring(w1).trim()
            rows.add(format3Cols(firstLineName, line.col2, line.col3, maxChars))
            if (restName.isNotEmpty()) {
                rows.addAll(wrapTextToLines(restName, maxChars))
            }
        } else {
            rows.add(format3Cols(line.col1, line.col2, line.col3, maxChars))
        }

        val totalH = (rows.size * lineHeight).coerceAtLeast(lineHeight)
        val bmp = Bitmap.createBitmap(widthDots.coerceAtLeast(10), totalH.coerceAtLeast(10), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        val paint = TextPaint().apply {
            color = Color.BLACK
            typeface = Typeface.create(Typeface.MONOSPACE, if (line.isBold || StoreInfoManager.thermalNativeBoldMode) Typeface.BOLD else Typeface.NORMAL)
            textSize = if (fontSize == "LARGE") 15f else 13f
            isAntiAlias = true
            isSubpixelText = true
        }

        var y = lineHeight - 6f
        for (row in rows) {
            canvas.drawText(row, 8f, y, paint)
            y += lineHeight
        }
        return bmp
    }

    /**
     * Generates a realistic sample receipt bitmap for live preview in printer settings.
     */
    fun generateSamplePreviewBitmap(
        isBengali: Boolean,
        widthDots: Int = 384,
        threshold: Int = StoreInfoManager.thermalThreshold,
        feedLines: Int = StoreInfoManager.thermalFeedLines
    ): Bitmap {
        val safeWidth = widthDots.coerceAtLeast(10)
        val thresholdVal = threshold.coerceIn(0, 255)
        val safeFeedLines = feedLines.coerceIn(1, 8)
        val paddingX = 12
        val contentWidth = (safeWidth - (paddingX * 2)).coerceAtLeast(10)
        val fontScale = if (StoreInfoManager.thermalReceiptFontSize == "LARGE") 1.18f else 1.0f
        val feedLineHeight = (24 * fontScale).toInt().coerceAtLeast(20)
        val feedMarginHeight = safeFeedLines * feedLineHeight

        val titlePaint = TextPaint().apply {
            color = Color.BLACK
            textSize = 20f * fontScale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
            isSubpixelText = true
        }

        val bodyPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = 13.5f * fontScale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            isAntiAlias = true
            isSubpixelText = true
        }

        val boldPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = 14f * fontScale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
            isSubpixelText = true
        }

        val linePaint = Paint().apply {
            color = Color.BLACK
            strokeWidth = 2f
            style = Paint.Style.STROKE
            isAntiAlias = false
        }

        val storeName = if (isBengali) StoreInfoManager.storeNameBn.ifBlank { "কালী মাতা ভ্যারাইটি স্টোর" } else StoreInfoManager.storeName.ifBlank { "KALI MATA VARIETY STORE" }
        val sampleItem1 = if (isBengali) "১. বাসমতী চাল (৫ কেজি)" to "₹৪৫০.০০" else "1. Basmati Rice (5kg)" to "₹450.00"
        val sampleItem2 = if (isBengali) "২. সরিষার তেল (১ লিটার)" to "₹১৭৫.০০" else "2. Mustard Oil (1L)" to "₹175.00"
        val totalText = if (isBengali) "মোট প্রদেয়: ₹৬২৫.০০" else "Total Payable: ₹625.00"
        val footerText = if (isBengali) "ধন্যবাদ! আবার আসবেন।" else "Thank you! Please visit again."

        val titleLayout = createStaticLayout(storeName, titlePaint, contentWidth, Layout.Alignment.ALIGN_CENTER)
        val item1Layout = createStaticLayout("${sampleItem1.first}   ${sampleItem1.second}", bodyPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
        val item2Layout = createStaticLayout("${sampleItem2.first}   ${sampleItem2.second}", bodyPaint, contentWidth, Layout.Alignment.ALIGN_NORMAL)
        val totalLayout = createStaticLayout(totalText, boldPaint, contentWidth, Layout.Alignment.ALIGN_OPPOSITE)
        val footerLayout = createStaticLayout(footerText, bodyPaint, contentWidth, Layout.Alignment.ALIGN_CENTER)

        val baseHeight = (24 + titleLayout.height + 12 + item1Layout.height + 6 + item2Layout.height + 12 + totalLayout.height + 12 + footerLayout.height + 24).coerceAtLeast(20)
        val height = baseHeight + feedMarginHeight
        val bitmap = Bitmap.createBitmap(safeWidth, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        var y = 14f
        canvas.save()
        canvas.translate(paddingX.toFloat(), y)
        titleLayout.draw(canvas)
        canvas.restore()

        y += titleLayout.height + 8f
        canvas.drawLine(paddingX.toFloat(), y, (safeWidth - paddingX).toFloat(), y, linePaint)

        y += 8f
        canvas.save()
        canvas.translate(paddingX.toFloat(), y)
        item1Layout.draw(canvas)
        canvas.restore()

        y += item1Layout.height + 4f
        canvas.save()
        canvas.translate(paddingX.toFloat(), y)
        item2Layout.draw(canvas)
        canvas.restore()

        y += item2Layout.height + 8f
        canvas.drawLine(paddingX.toFloat(), y, (safeWidth - paddingX).toFloat(), y, linePaint)

        y += 8f
        canvas.save()
        canvas.translate(paddingX.toFloat(), y)
        totalLayout.draw(canvas)
        canvas.restore()

        y += totalLayout.height + 8f
        canvas.save()
        canvas.translate(paddingX.toFloat(), y)
        footerLayout.draw(canvas)
        canvas.restore()

        // Tear bar indicator
        val tearY = height - 6f
        val tearPaint = Paint().apply {
            color = Color.rgb(175, 175, 175)
            strokeWidth = 2f
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
            isAntiAlias = true
        }
        canvas.drawLine(12f, tearY, (safeWidth - 12).toFloat(), tearY, tearPaint)

        val textPaint = TextPaint().apply {
            color = Color.rgb(150, 150, 150)
            textSize = 10f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            isAntiAlias = true
        }
        val tearLabel = "- - - ✂ TEAR BAR ($safeFeedLines ${if (safeFeedLines == 1) "LINE" else "LINES"}) - - -"
        val textWidth = textPaint.measureText(tearLabel)
        val textX = ((safeWidth - textWidth) / 2f).coerceAtLeast(4f)
        canvas.drawText(tearLabel, textX, tearY - 4f, textPaint)

        val mono = convertToMonochromePreview(bitmap, thresholdVal)
        return if (StoreInfoManager.thermalRasterDilation) dilateBitmap(mono, thresholdVal) else mono
    }

    /**
     * Converts Bitmap to TSPL raw 1-bit monochrome data (Mode 0: 0=black, 1=white).
     * Calibrated threshold prevents anti-aliasing artifacts from artificially swelling font weight on thermal sticker media.
     */
    private fun bitmapToTsplBitmapBytes(
        bitmap: Bitmap,
        widthDots: Int,
        heightDots: Int,
        threshold: Int = 135
    ): ByteArray {
        val bytesPerLine = widthDots / 8
        val totalBytes = bytesPerLine * heightDots
        val result = ByteArray(totalBytes)
        val pixels = IntArray(widthDots * heightDots)
        bitmap.getPixels(pixels, 0, widthDots, 0, 0, widthDots, heightDots)

        var byteIndex = 0
        for (y in 0 until heightDots) {
            for (colByte in 0 until bytesPerLine) {
                var byteVal = 0xFF // Default white in TSPL mode 0
                for (bit in 0..7) {
                    val x = colByte * 8 + bit
                    if (x < widthDots) {
                        val pixel = pixels[y * widthDots + x]
                        val alpha = (pixel ushr 24) and 0xFF
                        if (alpha > 30) {
                            val r = (pixel shr 16) and 0xFF
                            val g = (pixel shr 8) and 0xFF
                            val b = pixel and 0xFF
                            val effR = (r * alpha + 255 * (255 - alpha)) / 255
                            val effG = (g * alpha + 255 * (255 - alpha)) / 255
                            val effB = (b * alpha + 255 * (255 - alpha)) / 255
                            val luminance = (effR * 299 + effG * 587 + effB * 114) / 1000
                            if (luminance < threshold) {
                                // Black dot: clear bit in TSPL (0 = black)
                                byteVal = byteVal and (1 shl (7 - bit)).inv()
                            }
                        }
                    }
                }
                result[byteIndex++] = byteVal.toByte()
            }
        }
        return result
    }

    /**
     * Builds TSPL protocol commands for a single discrete label.
     * Ends with PRINT 1,1\r\n to ensure the printer stops at the gap boundary.
     */
    fun buildSingleBarcodeTsplBytes(
        productName: String,
        barcodeStr: String,
        price: Double,
        mrp: Double,
        widthMm: Int = 50,
        heightMm: Int = 25,
        gapMm: Int = 2,
        invertOrientation: Boolean = false,
        density: String = StoreInfoManager.thermalPrinterDensity,
        quantityOrUnit: String = "1 N",
        sizeOrVariant: String? = null,
        subtitleOrTag: String = "",
        discountPercentage: Int? = null,
        storeName: String = StoreInfoManager.storeName,
        expiryDate: String? = null,
        labelStyle: String = "MODERN"
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        val cleanStore = storeName.ifBlank { StoreInfoManager.storeName }
        val cleanCode = barcodeStr.trim()
        val effectiveQuantityOrUnit = (sizeOrVariant?.takeIf { it.isNotBlank() } ?: quantityOrUnit).trim().ifBlank { "1 N" }

        val bitmap = generateBarcodeLabelBitmap(
            storeName = cleanStore,
            productName = productName,
            barcodeStr = cleanCode,
            price = price,
            mrp = mrp,
            widthMm = widthMm,
            heightMm = heightMm,
            quantityOrUnit = effectiveQuantityOrUnit,
            subtitleOrTag = subtitleOrTag,
            discountPercentage = discountPercentage,
            expiryDate = expiryDate,
            labelStyle = labelStyle
        )

        val (tsplDensity, tsplSpeed, thresholdVal) = when (density) {
            "LIGHT" -> Triple(5, 4, 120)
            "NORMAL" -> Triple(6, 3, 128)
            "EXTRA_DARK" -> Triple(9, 3, 146)
            else -> Triple(7, 3, 135) // DARK
        }

        val dirCmd = if (invertOrientation) "DIRECTION 1,0\r\n" else "DIRECTION 0,0\r\n"

        if (bitmap != null) {
            val widthDots = bitmap.width
            val widthBytes = widthDots / 8
            val heightDots = bitmap.height

            val header = "SIZE $widthMm mm, $heightMm mm\r\n" +
                    "GAP $gapMm mm, 0 mm\r\n" +
                    "OFFSET 0 mm\r\n" +
                    "SPEED $tsplSpeed\r\n" +
                    "DENSITY $tsplDensity\r\n" +
                    dirCmd +
                    "REFERENCE 0,0\r\n" +
                    "SET PEEL OFF\r\n" +
                    "SET CUTTER OFF\r\n" +
                    "SET TEAR ON\r\n" +
                    "CLS\r\n"
            baos.write(header.toByteArray(Charsets.US_ASCII))

            val bmpCmd = "BITMAP 0,0,$widthBytes,$heightDots,0,"
            baos.write(bmpCmd.toByteArray(Charsets.US_ASCII))
            val bmpBytes = bitmapToTsplBitmapBytes(bitmap, widthDots, heightDots, thresholdVal)
            baos.write(bmpBytes)
            baos.write("\r\n".toByteArray(Charsets.US_ASCII))
            baos.write("PRINT 1,1\r\n".toByteArray(Charsets.US_ASCII))
        } else {
            val header = "SIZE $widthMm mm, $heightMm mm\r\nGAP $gapMm mm, 0 mm\r\nOFFSET 0 mm\r\nDENSITY $tsplDensity\r\nSPEED $tsplSpeed\r\n$dirCmd REFERENCE 0,0\r\nSET PEEL OFF\r\nSET CUTTER OFF\r\nSET TEAR ON\r\nCLS\r\n"
            baos.write(header.toByteArray(Charsets.US_ASCII))

            val cleanStoreEsc = cleanStore.take(20).replace("\"", "")
            val cleanProdEsc = productName.take(18).replace("\"", "")
            val cleanTagEsc = subtitleOrTag.take(10).replace("\"", "").trim()
            val cleanQtyEsc = effectiveQuantityOrUnit.take(14).replace("\"", "")
            val effMrp = if (mrp > 0.0) mrp else if (price > 0.0) price else 0.0
            val effPrice = if (price > 0.0) price else effMrp
            val discPct = if (discountPercentage != null && discountPercentage > 0) discountPercentage else if (effMrp > effPrice && effMrp > 0.0) (((effMrp - effPrice) / effMrp) * 100.0).toInt() else 0
            val formattedMrp = if (effMrp % 1.0 == 0.0) effMrp.toLong().toString() else "%.2f".format(effMrp)
            val formattedPrice = if (effPrice % 1.0 == 0.0) effPrice.toLong().toString() else "%.2f".format(effPrice)

            val cleanExp = formatExpiryDate(expiryDate)
            baos.write("TEXT 20,12,\"3\",0,1,1,\"$cleanStoreEsc\"\r\n".toByteArray(Charsets.US_ASCII))
            baos.write("TEXT 20,38,\"2\",0,1,1,\"Item : $cleanProdEsc\"\r\n".toByteArray(Charsets.US_ASCII))
            if (cleanTagEsc.isNotBlank()) {
                baos.write("TEXT 260,38,\"2\",0,1,1,\"$cleanTagEsc\"\r\n".toByteArray(Charsets.US_ASCII))
            }
            val qtyLine = "Qty : $cleanQtyEsc"
            baos.write("TEXT 20,62,\"2\",0,1,1,\"$qtyLine\"\r\n".toByteArray(Charsets.US_ASCII))
            if (!cleanExp.isNullOrBlank()) {
                baos.write("TEXT 240,62,\"2\",0,1,1,\"Exp : $cleanExp\"\r\n".toByteArray(Charsets.US_ASCII))
            }
            if (discPct > 0 && effMrp > effPrice) {
                baos.write("TEXT 20,86,\"2\",0,1,1,\"Offer : $formattedPrice/-\"\r\n".toByteArray(Charsets.US_ASCII))
                baos.write("TEXT 200,86,\"2\",0,1,1,\"MRP : $formattedMrp/- $discPct% OFF\"\r\n".toByteArray(Charsets.US_ASCII))
                if (cleanCode.isNotBlank()) {
                    baos.write("BARCODE 20,110,\"128\",48,1,0,2,2,\"${cleanCode.replace(" ", "")}\"\r\n".toByteArray(Charsets.US_ASCII))
                }
            } else {
                val mrpStr = if (effMrp > 0.0) "MRP : $formattedMrp/-" else if (effPrice > 0.0) "Price : $formattedPrice/-" else "MRP : --"
                baos.write("TEXT 20,86,\"2\",0,1,1,\"$mrpStr\"\r\n".toByteArray(Charsets.US_ASCII))
                if (cleanCode.isNotBlank()) {
                    baos.write("BARCODE 20,110,\"128\",48,1,0,2,2,\"${cleanCode.replace(" ", "")}\"\r\n".toByteArray(Charsets.US_ASCII))
                }
            }
            baos.write("PRINT 1,1\r\n".toByteArray(Charsets.US_ASCII))
        }

        return baos.toByteArray()
    }

    /**
     * Builds TSPL protocol commands for label stickers with gap.
     * DIRECTION 0,0: Standard top-first printing (Right-side up).
     * DIRECTION 1,0: Inverted 180° printing (if user's printer feeds from opposite direction).
     */
    fun buildBarcodeTsplBytes(
        productName: String,
        barcodeStr: String,
        price: Double,
        mrp: Double,
        quantity: Int,
        widthMm: Int = 50,
        heightMm: Int = 25,
        gapMm: Int = 2,
        invertOrientation: Boolean = false,
        density: String = StoreInfoManager.thermalPrinterDensity,
        quantityOrUnit: String = "1 N",
        sizeOrVariant: String? = null,
        subtitleOrTag: String = "",
        discountPercentage: Int? = null,
        storeName: String = StoreInfoManager.storeName,
        expiryDate: String? = null,
        labelStyle: String = "MODERN"
    ): ByteArray {
        val actualQty = quantity.coerceAtLeast(1)
        val single = buildSingleBarcodeTsplBytes(
            productName = productName,
            barcodeStr = barcodeStr,
            price = price,
            mrp = mrp,
            widthMm = widthMm,
            heightMm = heightMm,
            gapMm = gapMm,
            invertOrientation = invertOrientation,
            density = density,
            quantityOrUnit = quantityOrUnit,
            sizeOrVariant = sizeOrVariant,
            subtitleOrTag = subtitleOrTag,
            discountPercentage = discountPercentage,
            storeName = storeName,
            expiryDate = expiryDate,
            labelStyle = labelStyle
        )
        if (actualQty == 1) return single

        val baos = ByteArrayOutputStream()
        repeat(actualQty) {
            baos.write(single)
        }
        return baos.toByteArray()
    }

    /**
     * Builds ESC/POS protocol commands for a single discrete label.
     * Feeds directly to the physical optical gap sensor (GS FF) to anchor each print.
     */
    fun buildSingleBarcodeEscPosBytes(
        productName: String,
        barcodeStr: String,
        price: Double,
        mrp: Double,
        widthMm: Int = 50,
        heightMm: Int = 25,
        gapMm: Int = 2,
        invertOrientation: Boolean = false,
        density: String = StoreInfoManager.thermalPrinterDensity,
        quantityOrUnit: String = "1 N",
        sizeOrVariant: String? = null,
        subtitleOrTag: String = "",
        discountPercentage: Int? = null,
        storeName: String = StoreInfoManager.storeName,
        expiryDate: String? = null,
        labelStyle: String = "MODERN"
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        val cleanStore = storeName.ifBlank { StoreInfoManager.storeName }
        val cleanCode = barcodeStr.trim()
        val effectiveQuantityOrUnit = (sizeOrVariant?.takeIf { it.isNotBlank() } ?: quantityOrUnit).trim().ifBlank { "1 N" }

        val rawBitmap = generateBarcodeLabelBitmap(
            storeName = cleanStore,
            productName = productName,
            barcodeStr = cleanCode,
            price = price,
            mrp = mrp,
            widthMm = widthMm,
            heightMm = heightMm,
            quantityOrUnit = effectiveQuantityOrUnit,
            subtitleOrTag = subtitleOrTag,
            discountPercentage = discountPercentage,
            expiryDate = expiryDate,
            labelStyle = labelStyle
        )
        val bitmap = if (rawBitmap != null && invertOrientation) {
            val matrix = Matrix().apply { postRotate(180f) }
            Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
        } else {
            rawBitmap
        }

        val widthDots = bitmap?.width ?: 384

        // 1. GAP / Label Mode prefix & heat setup
        if (gapMm > 0) {
            baos.write(ESC_LABEL_INIT)
        } else {
            baos.write(byteArrayOf(0x1B, 0x40)) // ESC @ (Reset to defaults)
        }
        baos.write(byteArrayOf(0x1B, 0x37, 0x07, 0xBE.toByte(), 0x02)) // Heat parameters
        baos.write(byteArrayOf(0x12, 0x23, 0x20)) // Thermal density
        baos.write(byteArrayOf(0x1B, 0x33, 0x00)) // Zero line spacing for seamless raster

        if (bitmap != null) {
            val rawRasterBytes = bitmapToRawEscPosRaster(bitmap, widthDots, density)
            baos.write(rawRasterBytes)
        } else {
            val ESC_ALIGN_CENTER = byteArrayOf(0x1B, 0x61, 0x01)
            val ESC_ALIGN_LEFT = byteArrayOf(0x1B, 0x61, 0x00)
            val ESC_BOLD_ON = byteArrayOf(0x1B, 0x45, 0x01)
            val ESC_BOLD_OFF = byteArrayOf(0x1B, 0x45, 0x00)

            val effMrp = if (mrp > 0.0) mrp else if (price > 0.0) price else 0.0
            val effPrice = if (price > 0.0) price else effMrp
            val discPct = if (discountPercentage != null && discountPercentage > 0) discountPercentage else if (effMrp > effPrice && effMrp > 0.0) (((effMrp - effPrice) / effMrp) * 100.0).toInt() else 0
            val formattedMrp = if (effMrp % 1.0 == 0.0) effMrp.toLong().toString() else "%.2f".format(effMrp)
            val formattedPrice = if (effPrice % 1.0 == 0.0) effPrice.toLong().toString() else "%.2f".format(effPrice)

            baos.write(ESC_ALIGN_CENTER)
            baos.write(ESC_BOLD_ON)
            baos.write("${cleanStore.take(24)}\n".toByteArray(Charsets.US_ASCII))
            baos.write(ESC_BOLD_OFF)

            baos.write(ESC_ALIGN_LEFT)
            baos.write(ESC_BOLD_ON)
            baos.write("Item : ${productName.take(22)}\n".toByteArray(Charsets.US_ASCII))
            baos.write("Qty : ${effectiveQuantityOrUnit.take(16)}\n".toByteArray(Charsets.US_ASCII))
            if (discPct > 0 && effMrp > effPrice) {
                baos.write("MRP :$formattedMrp/-\n".toByteArray(Charsets.US_ASCII))
                baos.write("Offer Price :$formattedPrice/- ($discPct% OFF)\n".toByteArray(Charsets.US_ASCII))
            } else {
                val mrpLine = if (effMrp > 0.0) "MRP :$formattedMrp/-\n" else if (effPrice > 0.0) "Price :$formattedPrice/-\n" else "MRP :--\n"
                baos.write(mrpLine.toByteArray(Charsets.US_ASCII))
            }
            baos.write(ESC_BOLD_OFF)

            baos.write(ESC_ALIGN_CENTER)
            baos.write(byteArrayOf(0x1D, 0x68, 55))
            baos.write(byteArrayOf(0x1D, 0x77, 2))
            baos.write(byteArrayOf(0x1D, 0x48, 2))

            if (cleanCode.isNotBlank()) {
                val codeBytes = cleanCode.toByteArray(Charsets.US_ASCII)
                val len = codeBytes.size + 2
                val cmd = byteArrayOf(0x1D, 0x6B, 0x49, len.toByte(), 0x7B, 0x42)
                baos.write(cmd)
                baos.write(codeBytes)
                baos.write("\n".toByteArray(Charsets.US_ASCII))
            }
        }

        // 2. Feed paper
        if (gapMm > 0) {
            // Die-cut label paper: Feed paper until physical optical gap sensor is triggered (GS FF)
            baos.write(ESC_FEED_TO_GAP)
        } else {
            // Continuous roll paper: 2 lines gap feed without runaway gap sensing
            baos.write(byteArrayOf(0x1B, 0x32))
            baos.write(0x0A)
            baos.write(0x0A)
        }
        return baos.toByteArray()
    }

    /**
     * Builds ESC/POS protocol commands for 58mm thermal printers (continuous rolls or portable POS).
     */
    fun buildBarcodeEscPosBytes(
        productName: String,
        barcodeStr: String,
        price: Double,
        mrp: Double,
        quantity: Int,
        widthMm: Int = 50,
        heightMm: Int = 25,
        gapMm: Int = 2,
        invertOrientation: Boolean = false,
        density: String = StoreInfoManager.thermalPrinterDensity,
        quantityOrUnit: String = "1 N",
        sizeOrVariant: String? = null,
        subtitleOrTag: String = "",
        discountPercentage: Int? = null,
        storeName: String = StoreInfoManager.storeName,
        expiryDate: String? = null,
        labelStyle: String = "MODERN"
    ): ByteArray {
        val actualQty = quantity.coerceAtLeast(1)
        val single = buildSingleBarcodeEscPosBytes(
            productName = productName,
            barcodeStr = barcodeStr,
            price = price,
            mrp = mrp,
            widthMm = widthMm,
            heightMm = heightMm,
            gapMm = gapMm,
            invertOrientation = invertOrientation,
            density = density,
            quantityOrUnit = quantityOrUnit,
            sizeOrVariant = sizeOrVariant,
            subtitleOrTag = subtitleOrTag,
            discountPercentage = discountPercentage,
            storeName = storeName,
            expiryDate = expiryDate,
            labelStyle = labelStyle
        )
        if (actualQty == 1) return single

        val baos = ByteArrayOutputStream()
        repeat(actualQty) {
            baos.write(single)
        }
        return baos.toByteArray()
    }
}

