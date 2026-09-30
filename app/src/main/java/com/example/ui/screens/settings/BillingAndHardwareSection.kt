package com.example.ui.screens.settings

import android.content.Context
import android.graphics.Bitmap
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import androidx.camera.core.CameraSelector
import com.example.utils.CameraPreferenceManager
import com.example.utils.DefaultCameraOption
import com.example.utils.EscPosPrinter
import com.example.utils.LanguageManager
import com.example.utils.PrintSpeed
import com.example.utils.StoreInfoManager
import com.example.viewmodel.StoreViewModel

/**
 * Modernized Billing and Hardware Settings Screen
 * Features:
 * - Interactive Thermal POS Hardware Console with real-time status
 * - Smart Categorization of Bluetooth Devices (separating Thermal Printers from Audio/Accessories)
 * - Collapsible device selector to keep the screen uncluttered
 * - Print Tuning Console (Darkness/Density, Font Size, Tear Bar Feed Lines, Paper Width)
 * - PDF Receipts & Dynamic UPI Payment QR Customizer
 * - Dual Segmented Language Preferences (Receipts & Customer SMS)
 * - Khata Credit & Late Interest Policy Dashboard (with unified single-action UX)
 * - Realistic Customer SMS Notification Bubble Preview
 * - Cost Price Profit Guard & Offers Hub
 */
@Composable
fun BillingAndHardwareSection(
    context: Context,
    viewModel: StoreViewModel,
    pairedPrinters: List<EscPosPrinter.BluetoothPrinterDevice>,
    selectedPrinterAddress: String?,
    selectedDensity: String,
    onDensityChange: (String) -> Unit,
    selectedFontSize: String,
    onFontSizeChange: (String) -> Unit,
    selectedPaperSize: String,
    onPaperSizeChange: (String) -> Unit,
    onEditPdfFormatClicked: () -> Unit,
    onConfigureKhataInterestClicked: () -> Unit,
    onRequestSmsPermission: () -> Unit,
    onOpenOffersHubClicked: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section 1: Bluetooth Thermal Printer Console
        ThermalPrinterHardwareCard(
            context = context,
            viewModel = viewModel,
            pairedPrinters = pairedPrinters,
            selectedPrinterAddress = selectedPrinterAddress,
            selectedDensity = selectedDensity,
            onDensityChange = onDensityChange,
            selectedFontSize = selectedFontSize,
            onFontSizeChange = onFontSizeChange,
            selectedPaperSize = selectedPaperSize,
            onPaperSizeChange = onPaperSizeChange
        )

        // Section 2: Barcode Scanner Camera Configuration
        BarcodeScannerCameraCard(
            context = context
        )

        // Section 3: PDF Receipts & Merchant UPI QR Code
        PdfReceiptsAndUpiCard(
            context = context,
            onEditPdfFormatClicked = onEditPdfFormatClicked
        )

        // Section 3: Language & Regional Preferences
        LanguagePreferencesCard(
            context = context
        )

        // Section 4: Digital Khata Late Interest & Credit Policy
        KhataInterestPolicyCard(
            onConfigureKhataInterestClicked = onConfigureKhataInterestClicked
        )

        // Section 5: Automated Customer SMS for Credit Sales
        AutomatedCreditSmsCard(
            context = context,
            onRequestSmsPermission = onRequestSmsPermission
        )

        // Section 6: Cost Price Profit Protection
        CostPriceProtectionCard(
            context = context
        )

        // Section 7: Unified Offers & Promotions Hub
        UnifiedOffersCard(
            onOpenOffersHubClicked = onOpenOffersHubClicked
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// =========================================================================================
// SECTION 1: BLUETOOTH THERMAL PRINTER HARDWARE CONSOLE
// =========================================================================================

@Composable
private fun ThermalPrinterHardwareCard(
    context: Context,
    viewModel: StoreViewModel,
    pairedPrinters: List<EscPosPrinter.BluetoothPrinterDevice>,
    selectedPrinterAddress: String?,
    selectedDensity: String,
    onDensityChange: (String) -> Unit,
    selectedFontSize: String,
    onFontSizeChange: (String) -> Unit,
    selectedPaperSize: String,
    onPaperSizeChange: (String) -> Unit
) {
    val savedAddr = StoreInfoManager.savedPrinterAddress.orEmpty()
    val savedName = StoreInfoManager.savedPrinterName.orEmpty()
    // Smart categorization of paired devices
    val (detectedPrinters, otherDevices) = remember(pairedPrinters) {
        pairedPrinters.partition { isLikelyPrinter(it.name) }
    }

    var isDeviceListExpanded by remember {
        mutableStateOf(selectedPrinterAddress.isNullOrBlank() && savedAddr.isBlank())
    }
    var showOtherDevicesExpanded by remember(detectedPrinters.isEmpty()) {
        mutableStateOf(detectedPrinters.isEmpty())
    }

    // Active device resolution
    val activeDevice = remember(pairedPrinters, selectedPrinterAddress, StoreInfoManager.savedPrinterAddress, StoreInfoManager.savedPrinterName) {
        val targetAddr = selectedPrinterAddress ?: savedAddr
        pairedPrinters.firstOrNull { it.address.equals(targetAddr, ignoreCase = true) }
            ?: if (savedName.isNotBlank() && targetAddr.isNotBlank()) {
                EscPosPrinter.BluetoothPrinterDevice(
                    name = savedName,
                    address = targetAddr
                )
            } else null
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = StoreRedPrimary.copy(alpha = 0.12f),
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Print,
                                contentDescription = null,
                                tint = StoreRedPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (LanguageManager.isBengali) "ব্লুটুথ থার্মাল প্রিন্টার" else "Bluetooth Thermal Printer",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextDark
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = StoreRedPrimary.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = if (selectedPaperSize == "THERMAL_80MM") "80mm" else "58mm",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = StoreRedPrimary,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = if (LanguageManager.isBengali) "ESC/POS হাই-স্পিড রসিদ ও বারকোড প্রিন্টার" else "ESC/POS High-Speed Receipt & Barcode Printing",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                }

                IconButton(
                    onClick = {
                        viewModel.loadPairedPrinters()
                        Toast.makeText(
                            context,
                            if (LanguageManager.isBengali) "ব্লুটুথ ডিভাইস তালিকা রিফ্রেশ করা হয়েছে" else "Refreshed paired devices",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh paired devices",
                        tint = StoreRedPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Active Printer Status Console
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = SurfaceWarm,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (LanguageManager.isBengali) "সংযুক্ত প্রিন্টার (ACTIVE PRINTER)" else "ACTIVE POS PRINTER",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextMuted,
                                letterSpacing = 0.8.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = activeDevice?.name ?: (if (LanguageManager.isBengali) "কোন প্রিন্টার নির্বাচিত নেই" else "No printer selected"),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = TextDark,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (activeDevice != null) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = activeDevice.address,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = TextMuted
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "• ${if (selectedPaperSize == "THERMAL_80MM") "80mm / 576 dots" else "58mm / 384 dots"}",
                                        fontSize = 11.sp,
                                        color = TextMuted
                                    )
                                }
                            }
                        }

                        // Status Badge
                        PrinterStatusBadge(
                            status = viewModel.printerConnectionStatus,
                            isConnecting = viewModel.isConnectingPrinter
                        )
                    }

                    // Status message alert banner if any error
                    if (viewModel.printerStatusMessage.isNotBlank()) {
                        val isError = viewModel.printerConnectionStatus == "ERROR"
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isError) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f) else StorePrimary.copy(alpha = 0.1f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isError) Icons.Default.Warning else Icons.Default.Info,
                                    contentDescription = null,
                                    tint = if (isError) MaterialTheme.colorScheme.error else StorePrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = viewModel.printerStatusMessage,
                                    fontSize = 11.sp,
                                    color = if (isError) MaterialTheme.colorScheme.onErrorContainer else TextDark,
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }

                    // Action Buttons Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Reconnect Button
                        OutlinedButton(
                            onClick = {
                                viewModel.reconnectPrinter { success, msg ->
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp),
                            shape = RoundedCornerShape(8.dp),
                            enabled = !viewModel.isConnectingPrinter,
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            if (viewModel.isConnectingPrinter) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = StoreRedPrimary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (LanguageManager.isBengali) "সংযোগ হচ্ছে..." else "Connecting...", fontSize = 11.sp)
                            } else {
                                Icon(Icons.Default.BluetoothConnected, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (LanguageManager.isBengali) "পুনঃসংযোগ" else "Reconnect", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        // Test Print Button
                        Button(
                            onClick = {
                                viewModel.testPrintReceipt(
                                    paperSize = selectedPaperSize,
                                    isBengali = LanguageManager.isBengali
                                ) { success, msg ->
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = StoreRedPrimary),
                            enabled = !viewModel.isTestPrinting,
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            if (viewModel.isTestPrinting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (LanguageManager.isBengali) "প্রিন্ট হচ্ছে..." else "Printing...", fontSize = 11.sp, color = Color.White)
                            } else {
                                Icon(Icons.Default.ReceiptLong, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (LanguageManager.isBengali) "টেস্ট প্রিন্ট" else "Test Print", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }

                        // Toggle Picker Button
                        FilledTonalIconButton(
                            onClick = { isDeviceListExpanded = !isDeviceListExpanded },
                            modifier = Modifier.size(38.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = if (isDeviceListExpanded) StoreRedPrimary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Icon(
                                imageVector = if (isDeviceListExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = "Toggle device list",
                                tint = if (isDeviceListExpanded) StoreRedPrimary else TextDark
                            )
                        }
                    }
                }
            }

            // Collapsible Smart Device Picker
            AnimatedVisibility(
                visible = isDeviceListExpanded,
                enter = expandVertically(animationSpec = tween(250)) + fadeIn(),
                exit = shrinkVertically(animationSpec = tween(200)) + fadeOut()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Header with Count & Settings Shortcut
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (LanguageManager.isBengali) "জোড়াবদ্ধ ব্লুটুথ ডিভাইস (${pairedPrinters.size})" else "PAIRED BLUETOOTH DEVICES (${pairedPrinters.size})",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextDark
                        )

                        TextButton(
                            onClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Open Settings > Bluetooth to pair", Toast.LENGTH_SHORT).show()
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp), tint = StoreRedPrimary)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (LanguageManager.isBengali) "নতুন পেয়ার করুন" else "Pair New",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = StoreRedPrimary
                            )
                        }
                    }

                    if (pairedPrinters.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.BluetoothSearching,
                                    contentDescription = null,
                                    tint = TextMuted,
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = if (LanguageManager.isBengali) "কোনো ব্লুটুথ ডিভাইস জোড়াবদ্ধ পাওয়া যায়নি" else "No paired Bluetooth devices detected",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = TextMuted,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (LanguageManager.isBengali) "ফোনের ব্লুটুথ অন করুন এবং প্রিন্টারের সাথে পেয়ার করুন।" else "Turn on Bluetooth and pair your POS printer in Android Settings.",
                                    fontSize = 11.sp,
                                    color = TextMuted,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else {
                        // Section A: Detected Thermal Printers (High Priority)
                        if (detectedPrinters.isNotEmpty()) {
                            Text(
                                text = if (LanguageManager.isBengali) "শনাক্তকৃত থার্মাল প্রিন্টার (${detectedPrinters.size})" else "Detected Thermal Printers (${detectedPrinters.size})",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = StoreGreenProfit
                            )

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                val effectiveSelectedAddr = selectedPrinterAddress ?: StoreInfoManager.savedPrinterAddress.orEmpty()
                                detectedPrinters.forEach { device ->
                                    val isSelected = device.address.equals(effectiveSelectedAddr, ignoreCase = true)
                                    DeviceItemCard(
                                        device = device,
                                        isPrinter = true,
                                        isSelected = isSelected,
                                        onClick = {
                                            viewModel.selectPrinterDevice(device)
                                            StoreInfoManager.updateSavedPrinter(device.address, device.name, context)
                                            isDeviceListExpanded = false
                                            val nameLower = device.name.lowercase()
                                            if (nameLower.contains("58") || nameLower.contains("pos") || nameLower.contains("mpt") || nameLower.contains("mtp") || nameLower.contains("rpp") || nameLower.contains("zj") || nameLower.contains("pt210") || nameLower.contains("pt280") || nameLower.contains("xp")) {
                                                onPaperSizeChange("THERMAL_58MM")
                                                viewModel.updatePaperSize("THERMAL_58MM")
                                                StoreInfoManager.updatePaperSize("THERMAL_58MM", context)
                                            }
                                            Toast.makeText(
                                                context,
                                                if (LanguageManager.isBengali) "${device.name} নির্বাচিত হয়েছে" else "Selected ${device.name}",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    )
                                }
                            }
                        }

                        // Section B: Other Paired Devices (Audio, Earbuds, Smartwatches, etc.)
                        if (otherDevices.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showOtherDevicesExpanded = !showOtherDevicesExpanded }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Headphones,
                                            contentDescription = null,
                                            tint = TextMuted,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = if (LanguageManager.isBengali) "অন্যান্য অডিও ও ব্লুটুথ ডিভাইস (${otherDevices.size})" else "Other Paired Devices (${otherDevices.size})",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = TextMuted
                                        )
                                    }
                                    Icon(
                                        imageVector = if (showOtherDevicesExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = TextMuted,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            AnimatedVisibility(visible = showOtherDevicesExpanded) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    val effectiveSelectedAddr = selectedPrinterAddress ?: StoreInfoManager.savedPrinterAddress.orEmpty()
                                    otherDevices.forEach { device ->
                                        val isSelected = device.address.equals(effectiveSelectedAddr, ignoreCase = true)
                                        val isAudio = isLikelyAudio(device.name)
                                        DeviceItemCard(
                                            device = device,
                                            isPrinter = false,
                                            isAudio = isAudio,
                                            isSelected = isSelected,
                                            onClick = {
                                                viewModel.selectPrinterDevice(device)
                                                StoreInfoManager.updateSavedPrinter(device.address, device.name, context)
                                                isDeviceListExpanded = false
                                                val nameLower = device.name.lowercase()
                                                if (nameLower.contains("58") || nameLower.contains("pos") || nameLower.contains("mpt") || nameLower.contains("mtp") || nameLower.contains("rpp") || nameLower.contains("zj") || nameLower.contains("pt210") || nameLower.contains("pt280") || nameLower.contains("xp")) {
                                                    onPaperSizeChange("THERMAL_58MM")
                                                    viewModel.updatePaperSize("THERMAL_58MM")
                                                    StoreInfoManager.updatePaperSize("THERMAL_58MM", context)
                                                }
                                                Toast.makeText(
                                                    context,
                                                    if (LanguageManager.isBengali) "${device.name} নির্বাচিত হয়েছে" else "Selected ${device.name}",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                modifier = Modifier.padding(vertical = 4.dp)
            )

            // Section C: Print Tuning & Output Settings Console
            PrintTuningControls(
                context = context,
                viewModel = viewModel,
                selectedDensity = selectedDensity,
                onDensityChange = onDensityChange,
                selectedFontSize = selectedFontSize,
                onFontSizeChange = onFontSizeChange,
                selectedPaperSize = selectedPaperSize,
                onPaperSizeChange = onPaperSizeChange
            )
        }
    }
}

@Composable
private fun DeviceItemCard(
    device: EscPosPrinter.BluetoothPrinterDevice,
    isPrinter: Boolean,
    isAudio: Boolean = false,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) StoreRedPrimary.copy(alpha = 0.08f) else SurfaceWarm,
        border = BorderStroke(
            1.dp,
            if (isSelected) StoreRedPrimary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (isSelected) StoreRedPrimary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(34.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = when {
                                isPrinter -> Icons.Default.Print
                                isAudio -> Icons.Default.Headphones
                                else -> Icons.Default.Bluetooth
                            },
                            contentDescription = null,
                            tint = if (isSelected) StoreRedPrimary else TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = device.name,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 13.sp,
                            color = if (isSelected) StoreRedPrimary else TextDark,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isPrinter) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = StoreGreenProfit.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "POS",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = StoreGreenProfit,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = device.address,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextMuted
                    )
                }
            }

            if (isSelected) {
                Surface(
                    shape = CircleShape,
                    color = StoreRedPrimary,
                    modifier = Modifier.size(22.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            } else {
                Text(
                    text = if (LanguageManager.isBengali) "নির্বাচন" else "Select",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = StoreRedPrimary
                )
            }
        }
    }
}

@Composable
private fun PrinterStatusBadge(
    status: String,
    isConnecting: Boolean
) {
    val (label, bg, fg, icon) = when {
        isConnecting || status == "CONNECTING" -> Quadruple(
            if (LanguageManager.isBengali) "সংযোগ হচ্ছে..." else "Connecting...",
            StoreGold.copy(alpha = 0.15f),
            StoreGold,
            Icons.Default.Refresh
        )
        status == "CONNECTED" -> Quadruple(
            if (LanguageManager.isBengali) "সংযুক্ত ও প্রস্তুত" else "Connected & Ready",
            StoreGreenProfit.copy(alpha = 0.15f),
            StoreGreenProfit,
            Icons.Default.CheckCircle
        )
        status == "ERROR" -> Quadruple(
            if (LanguageManager.isBengali) "সংযোগ ত্রুটি" else "Connection Error",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.error,
            Icons.Default.Warning
        )
        else -> Quadruple(
            if (LanguageManager.isBengali) "বিচ্ছিন্ন" else "Standby",
            MaterialTheme.colorScheme.surfaceVariant,
            TextMuted,
            Icons.Default.Bluetooth
        )
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bg
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = fg
            )
        }
    }
}

// Data helper for status quadruple
private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

// =========================================================================================
// SECTION 1B: PRINT TUNING CONTROLS (DENSITY, FONT, FEED, WIDTH)
// =========================================================================================

@Composable
private fun PrintTuningControls(
    context: Context,
    viewModel: StoreViewModel,
    selectedDensity: String,
    onDensityChange: (String) -> Unit,
    selectedFontSize: String,
    onFontSizeChange: (String) -> Unit,
    selectedPaperSize: String,
    onPaperSizeChange: (String) -> Unit
) {
    val initialThreshold = (selectedDensity.toIntOrNull() ?: StoreInfoManager.thermalThreshold).coerceIn(0, 255)
    var thresholdState by remember { mutableStateOf(initialThreshold.toFloat().coerceIn(0f, 255f)) }
    LaunchedEffect(selectedDensity) {
        val parsed = (selectedDensity.toIntOrNull() ?: StoreInfoManager.thermalThreshold).coerceIn(0, 255)
        if (thresholdState.toInt() != parsed) {
            thresholdState = parsed.toFloat()
        }
    }
    var printSpeedState by remember { mutableStateOf(StoreInfoManager.thermalPrintSpeed) }
    var feedLinesState by remember { mutableStateOf(StoreInfoManager.thermalFeedLines.coerceIn(1, 8)) }
    var nativeBoldState by remember { mutableStateOf(StoreInfoManager.thermalNativeBoldMode) }
    var rasterDilationState by remember { mutableStateOf(StoreInfoManager.thermalRasterDilation) }
    var isRunningEsc7Diag by remember { mutableStateOf(false) }
    var showHciGuide by remember { mutableStateOf(false) }

    val isBengali = LanguageManager.isBengali
    var previewMode by remember { mutableStateOf("TEST") } // "TEST" (Test Template) or "SAMPLE" (Sample Bill)

    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(thresholdState.toInt().coerceIn(0, 255), isBengali, selectedFontSize, previewMode, rasterDilationState, nativeBoldState, feedLinesState) {
        val currentThreshold = thresholdState.toInt().coerceIn(0, 255)
        val currentFeedLines = feedLinesState.coerceIn(1, 8)
        kotlinx.coroutines.delay(20)
        val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            try {
                val lines = if (previewMode == "TEST") {
                    EscPosPrinter.getTestReceiptHybridLines(
                        isBengali = isBengali,
                        widthDots = 384,
                        density = currentThreshold.toString(),
                        fontSize = selectedFontSize
                    )
                } else {
                    EscPosPrinter.getSampleReceiptHybridLines(
                        isBengali = isBengali,
                        widthDots = 384,
                        density = currentThreshold.toString(),
                        fontSize = selectedFontSize
                    )
                }
                EscPosPrinter.renderHybridReceiptPreviewBitmap(
                    lines = lines,
                    widthDots = 384,
                    threshold = currentThreshold,
                    fontSize = selectedFontSize,
                    feedLines = currentFeedLines
                )
            } catch (e: Throwable) {
                android.util.Log.e("BillingAndHardware", "Failed to render hybrid preview bitmap", e)
                try {
                    EscPosPrinter.generateSamplePreviewBitmap(
                        isBengali = isBengali,
                        widthDots = 384,
                        threshold = currentThreshold,
                        feedLines = currentFeedLines
                    )
                } catch (_: Throwable) {
                    Bitmap.createBitmap(384, 100, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(android.graphics.Color.WHITE)
                    }
                }
            }
        }
        previewBitmap = bmp
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = null,
                tint = StoreRedPrimary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (isBengali) "প্রিন্ট ও হার্ডওয়্যার সেটিংস (PRINT TUNING)" else "PRINT TUNING & HARDWARE SETTINGS",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = TextDark,
                letterSpacing = 0.6.sp
            )
        }

        // --- LIVE PREVIEW BITMAP (Exact 1-bit monochrome output) ---
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = CardBackground,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = null,
                            tint = StoreRedPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isBengali) "লাইভ প্রিন্ট প্রিভিউ (1-বিট মনোক্রোম)" else "LIVE PRINT PREVIEW (1-BIT MONO)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextDark,
                            letterSpacing = 0.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${thresholdState.toInt()} / 255",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = StoreRedPrimary,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                }

                Text(
                    text = if (isBengali)
                        "প্রিন্টারে যেমন ছাপা হবে ঠিক তেমনই আউটপুট (কোনো ডিথারিং ছাড়া নিখুঁত হার্ড থ্রেশহোল্ড)।"
                    else
                        "Exact dot-for-dot output printed on 58mm paper (hard threshold, zero dithering).",
                    fontSize = 10.sp,
                    color = TextMuted,
                    lineHeight = 13.sp
                )

                // Template Switcher (Test Template / Sample Memo)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PillSegmentButton(
                        selected = previewMode == "TEST",
                        label = if (isBengali) "টেস্ট পেজ টেমপ্লেট" else "Test Template",
                        onClick = { previewMode = "TEST" },
                        modifier = Modifier.weight(1f)
                    )
                    PillSegmentButton(
                        selected = previewMode == "SAMPLE",
                        label = if (isBengali) "নমুনা মেমো" else "Sample Bill",
                        onClick = { previewMode = "SAMPLE" },
                        modifier = Modifier.weight(1f)
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF5F5F7), RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(2.dp),
                        color = Color.White,
                        shadowElevation = 3.dp,
                        border = BorderStroke(1.dp, Color(0xFFDDDDDD))
                    ) {
                        val currentBmp = previewBitmap
                        if (currentBmp != null) {
                            Image(
                                bitmap = currentBmp.asImageBitmap(),
                                contentDescription = "Monochrome Live Print Preview",
                                modifier = Modifier
                                    .widthIn(max = 280.dp)
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 8.dp),
                                contentScale = ContentScale.FillWidth
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .widthIn(max = 280.dp)
                                    .fillMaxWidth()
                                    .height(180.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                    color = StoreRedPrimary
                                )
                            }
                        }
                    }
                }
            }
        }

        // 1. Print Darkness / Contrast (Hard Threshold 0 - 255)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (isBengali) "প্রিন্ট ডার্কনেস / থ্রেশহোল্ড (Print Darkness):" else "Print Darkness / Hard Threshold:",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextDark
                    )
                    Text(
                        text = if (isBengali) "মান বেশি হলে গাঢ় ছাপা হবে (০-২৫৫, ডিফল্ট ১৫০)" else "Higher value = darker print (0-255, default ~150)",
                        fontSize = 10.sp,
                        color = TextMuted
                    )
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = StoreRedPrimary.copy(alpha = 0.1f)
                ) {
                    Text(
                        text = "${thresholdState.toInt()}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = StoreRedPrimary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            Slider(
                value = thresholdState.coerceIn(0f, 255f),
                onValueChange = { newVal ->
                    val safeVal = newVal.coerceIn(0f, 255f)
                    thresholdState = safeVal
                },
                onValueChangeFinished = {
                    val intVal = thresholdState.toInt().coerceIn(0, 255)
                    onDensityChange(intVal.toString())
                    viewModel.updatePrinterThreshold(intVal)
                    StoreInfoManager.updateThermalThreshold(intVal, context)
                },
                valueRange = 0f..255f,
                steps = 254,
                colors = SliderDefaults.colors(
                    thumbColor = StoreRedPrimary,
                    activeTrackColor = StoreRedPrimary,
                    inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // Quick preset shortcut chips for convenience
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val presets = listOf(
                    130 to (if (isBengali) "হালকা (130)" else "Light (130)"),
                    150 to (if (isBengali) "স্বাভাবিক (150) ★" else "Normal (150) ★"),
                    165 to (if (isBengali) "গাঢ় (165)" else "Dark (165)"),
                    180 to (if (isBengali) "অতি গাঢ় (180)" else "Extra Dark (180)")
                )

                presets.forEach { (thresholdVal, label) ->
                    val isSelected = thresholdState.toInt() == thresholdVal
                    PillSegmentButton(
                        selected = isSelected,
                        label = label,
                        onClick = {
                            thresholdState = thresholdVal.toFloat()
                            onDensityChange(thresholdVal.toString())
                            viewModel.updatePrinterThreshold(thresholdVal)
                            StoreInfoManager.updateThermalThreshold(thresholdVal, context)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 2. Print Speed / Buffer Safety Setting
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (isBengali) "প্রিন্ট গতি ও বাফার সুরক্ষা (Print Speed):" else "Print Speed / Buffer Safety:",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextDark
                    )
                    Text(
                        text = if (isBengali) "ব্লুটুথ বাফার সুরক্ষার জন্য চাঙ্ক সাইজ ও বিরতি" else "Chunk size & inter-packet delay for buffer safety",
                        fontSize = 10.sp,
                        color = TextMuted
                    )
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = StoreGreenProfit.copy(alpha = 0.1f)
                ) {
                    Text(
                        text = if (isBengali) printSpeedState.labelBn else printSpeedState.labelEn,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = StoreGreenProfit,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PrintSpeed.values().forEach { speed ->
                    val isSelected = printSpeedState == speed
                    PillSegmentButton(
                        selected = isSelected,
                        label = if (isBengali) speed.labelBn else speed.labelEn,
                        onClick = {
                            printSpeedState = speed
                            viewModel.updatePrinterSpeed(speed)
                            StoreInfoManager.updateThermalPrintSpeed(speed, context)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Text(
                text = if (isBengali)
                    when (printSpeedState) {
                        PrintSpeed.SLOW -> "ধীর (নিরাপদ): ১২৮-বাইট চাঙ্ক, ১৫মি.সে. বিরতি — পুরোনো বা সাধারণ থার্মাল প্রিন্টারের বাফার ওভারফ্লো রোধ করে।"
                        PrintSpeed.NORMAL -> "স্বাভাবিক: ২৫৬-বাইট চাঙ্ক, ৫মি.সে. বিরতি — বেশিরভাগ ব্লুটুথ থার্মাল প্রিন্টারের জন্য সেরা।"
                        PrintSpeed.FAST -> "দ্রুত: ৫১২-বাইট চাঙ্ক, ০মি.সে. বিরতি — হাই-স্পিড আধুনিক প্রিন্টারের জন্য।"
                    }
                else
                    when (printSpeedState) {
                        PrintSpeed.SLOW -> "Slow (Safe): 128-byte chunks, 15ms delay — prevents buffer overflow on older or budget mini thermal printers."
                        PrintSpeed.NORMAL -> "Normal: 256-byte chunks, 5ms delay — optimal balance for most 58mm Bluetooth thermal printers."
                        PrintSpeed.FAST -> "Fast: 512-byte chunks, 0ms delay — maximum throughput for modern high-speed thermal printers."
                    },
                fontSize = 10.sp,
                color = TextMuted,
                lineHeight = 13.sp
            )
        }

        // 3. Receipt Text Font Size
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = if (isBengali) "রসিদের ফন্ট সাইজ (Receipt Font Size):" else "Receipt Text Font Size:",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextDark
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val isLarge = selectedFontSize == "LARGE"

                PillSegmentButton(
                    selected = !isLarge,
                    label = if (isBengali) "সাধারণ ফন্ট (Normal Standard)" else "Normal Size (Standard)",
                    icon = Icons.Default.FormatSize,
                    onClick = {
                        onFontSizeChange("NORMAL")
                        viewModel.updateReceiptFontSize("NORMAL")
                        StoreInfoManager.updateThermalSettings(
                            density = thresholdState.toInt().toString(),
                            fontSize = "NORMAL",
                            context = context,
                            feedLines = feedLinesState
                        )
                    },
                    modifier = Modifier.weight(1f)
                )

                PillSegmentButton(
                    selected = isLarge,
                    label = if (isBengali) "বড় ও স্পষ্ট ফন্ট (+18% Crisp)" else "Large Font (+18% High Legibility)",
                    icon = Icons.Default.FormatSize,
                    onClick = {
                        onFontSizeChange("LARGE")
                        viewModel.updateReceiptFontSize("LARGE")
                        StoreInfoManager.updateThermalSettings(
                            density = thresholdState.toInt().toString(),
                            fontSize = "LARGE",
                            context = context,
                            feedLines = feedLinesState
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 4. Paper Feed After Print (Clear Tear Bar)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (isBengali) "প্রিন্টের পর কাগজ ফিড (Clear Tear Bar):" else "Paper Feed After Print (Clear Tear Bar):",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextDark
                    )
                    Text(
                        text = if (isBengali) "প্রিন্টারের টিয়ার বার ক্লিয়ার করার মার্জিন" else "Paper feed margin to clear tear bar",
                        fontSize = 10.sp,
                        color = TextMuted
                    )
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = StoreRedPrimary.copy(alpha = 0.1f)
                ) {
                    Text(
                        text = "$feedLinesState ${if (isBengali) "লাইন" else "Lines"}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = StoreRedPrimary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            // Presets Row 1: 1, 2, 3 Lines
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val feedOptionsRow1 = listOf(
                    1 to (if (isBengali) "১ লাইন (নূন্যতম)" else "1 Line (Min)"),
                    2 to (if (isBengali) "২ লাইন (কম্প্যাক্ট)" else "2 Lines (Compact)"),
                    3 to (if (isBengali) "৩ লাইন (স্ট্যান্ডার্ড)" else "3 Lines (Std)")
                )

                feedOptionsRow1.forEach { (lines, label) ->
                    val isSelected = feedLinesState == lines
                    PillSegmentButton(
                        selected = isSelected,
                        label = label,
                        onClick = {
                            feedLinesState = lines
                            viewModel.updatePrinterFeedLines(lines)
                            StoreInfoManager.updateThermalSettings(
                                density = thresholdState.toInt().toString(),
                                fontSize = selectedFontSize,
                                context = context,
                                feedLines = lines
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Presets Row 2: 4, 5, 6 Lines (Tear Bar Clearance)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val feedOptionsRow2 = listOf(
                    4 to (if (isBengali) "৪ লাইন (টিয়ার বার)" else "4 Lines (Tear Bar)"),
                    5 to (if (isBengali) "৫ লাইন (ক্লিয়ার টিয়ার ★)" else "5 Lines (Clear Tear ★)"),
                    6 to (if (isBengali) "৬ লাইন (অতিরিক্ত)" else "6 Lines (Extra)")
                )

                feedOptionsRow2.forEach { (lines, label) ->
                    val isSelected = feedLinesState == lines
                    PillSegmentButton(
                        selected = isSelected,
                        label = label,
                        onClick = {
                            feedLinesState = lines
                            viewModel.updatePrinterFeedLines(lines)
                            StoreInfoManager.updateThermalSettings(
                                density = thresholdState.toInt().toString(),
                                fontSize = selectedFontSize,
                                context = context,
                                feedLines = lines
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Action row: Live Test Feed Button & Info
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isBengali)
                        "টিয়ার বার লাইন প্রিভিউতে নিচে ড্যাশ (--- ✂ ---) আকারে দৃশ্যমান।"
                    else
                        "Visual tear line shown at bottom of receipt preview.",
                    fontSize = 10.sp,
                    color = TextMuted,
                    modifier = Modifier.weight(1f)
                )

                OutlinedButton(
                    onClick = {
                        viewModel.testFeedPaper(feedLinesState) { success, msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = StoreRedPrimary
                    ),
                    border = BorderStroke(1.dp, StoreRedPrimary.copy(alpha = 0.4f))
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isBengali) "ফিড টেস্ট" else "Test Feed",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // 5. Thermal Paper Width Format
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = if (isBengali) "থার্মাল কাগজের প্রস্থ (Paper Width):" else "Thermal Paper Width:",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextDark
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val is80mm = selectedPaperSize == "THERMAL_80MM"

                PillSegmentButton(
                    selected = !is80mm,
                    label = if (isBengali) "৫৮ মিমি (2-ইঞ্চি POS / 384 ডট)" else "58mm (2-inch / 384 dots)",
                    icon = Icons.Default.Receipt,
                    onClick = {
                        onPaperSizeChange("THERMAL_58MM")
                        viewModel.updatePaperSize("THERMAL_58MM")
                        StoreInfoManager.updatePaperSize("THERMAL_58MM", context)
                        Toast.makeText(
                            context,
                            if (isBengali) "৫৮ মিমি থার্মাল মোড সক্রিয় (384 ডট)" else "58mm Thermal Mode Active (384 dots)",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    modifier = Modifier.weight(1f)
                )

                PillSegmentButton(
                    selected = is80mm,
                    label = if (isBengali) "৮০ মিমি (3-ইঞ্চি POS / 576 ডট)" else "80mm (3-inch / 576 dots)",
                    icon = Icons.Default.Receipt,
                    onClick = {
                        onPaperSizeChange("THERMAL_80MM")
                        viewModel.updatePaperSize("THERMAL_80MM")
                        StoreInfoManager.updatePaperSize("THERMAL_80MM", context)
                        Toast.makeText(
                            context,
                            if (isBengali) "৮০ মিমি থার্মাল মোড সক্রিয় (576 ডট)" else "80mm Thermal Mode Active (576 dots)",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            // 58mm Printer Hardware Info Card
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                shape = RoundedCornerShape(8.dp),
                color = StoreGreenProfit.copy(alpha = 0.08f),
                border = BorderStroke(1.dp, StoreGreenProfit.copy(alpha = 0.25f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = StoreGreenProfit,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isBengali)
                            "৫৮ মিমি থার্মাল মোড প্রস্তুত: ৩৮৪ ডট প্রস্থ, হার্ড থ্রেশহোল্ড বাংলা ও ইংরেজি ফন্ট এবং বাফার-সুরক্ষিত চাঙ্কড ডাটা ট্রান্সমিশন সক্রিয়।"
                        else
                            "58mm Mode Active: 384 dots width, hard-threshold Bengali & English font rendering, and buffer-safe chunked Bluetooth transmission active.",
                        fontSize = 11.sp,
                        color = TextDark,
                        lineHeight = 15.sp
                    )
                }
            }
        }

        // 6. Seznik 58mm Thermal Darkness & Diagnostic Fixes
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (isBengali) "সেজনিক ৫৮ মিমি প্রিন্ট ডার্কনেস ফিক্স:" else "Seznik 58mm Print Darkness Fixes:",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextDark
                    )
                    Text(
                        text = if (isBengali) "ESC 7 উপেক্ষা করা প্রিন্টারের জন্য সরাসরি ফন্ট ও রাস্টার ফিক্স" else "Font & raster dilation for printers ignoring ESC 7",
                        fontSize = 10.sp,
                        color = TextMuted
                    )
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = StoreRedPrimary.copy(alpha = 0.1f)
                ) {
                    Text(
                        text = if (isBengali) "সক্রিয়" else "Active",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = StoreRedPrimary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            // Fix 1: Native Bold Mode Switch
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = SurfaceWarm,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isBengali) "১. নেটিভ বোল্ড মোড (ESC E 1)" else "1. Native Bold Mode (ESC E 1)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextDark
                        )
                        Text(
                            text = if (isBengali)
                                "ইংরেজি ও সংখ্যা টেক্সট সর্বদা মোটা হরফে প্রিন্ট হয় (হিটিং ছাড়া গাঢ় ফন্ট)"
                            else
                                "Forces ESC E 1 bold font selection for heavy, dark ASCII text without heating",
                            fontSize = 10.sp,
                            color = TextMuted,
                            lineHeight = 13.sp
                        )
                    }
                    Switch(
                        checked = nativeBoldState,
                        onCheckedChange = { checked ->
                            nativeBoldState = checked
                            StoreInfoManager.updateThermalNativeBoldMode(checked, context)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = StoreRedPrimary
                        )
                    )
                }
            }

            // Fix 2: Bitmap 3x3 Dilation Switch
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = SurfaceWarm,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isBengali) "২. রাস্টার ৩x৩ ডাইলেশন (+১px থিকেনিং)" else "2. 1-Bit 3x3 Dilation (+1px Thicken)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextDark
                        )
                        Text(
                            text = if (isBengali)
                                "বাংলা হরফ, কিউআর ও লোগোর প্রতিটি কালো পিক্সেল ১px মোটা করে অটুট রাখে"
                            else
                                "Max-filter thickens Bengali text, conjuncts, QR modules & logo strokes by 1px",
                            fontSize = 10.sp,
                            color = TextMuted,
                            lineHeight = 13.sp
                        )
                    }
                    Switch(
                        checked = rasterDilationState,
                        onCheckedChange = { checked ->
                            rasterDilationState = checked
                            StoreInfoManager.updateThermalRasterDilation(checked, context)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = StoreRedPrimary
                        )
                    )
                }
            }

            // Diagnostic Test Print Button
            OutlinedButton(
                onClick = {
                    viewModel.testPrintEsc7Comparison(
                        paperSize = selectedPaperSize
                    ) { success, msg ->
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = StoreRedPrimary
                ),
                border = BorderStroke(1.dp, StoreRedPrimary),
                enabled = !viewModel.isTestPrinting
            ) {
                Icon(
                    imageVector = Icons.Default.Compare,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = StoreRedPrimary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isBengali) "ESC 7 ডায়াগনস্টিক টেস্ট প্রিন্ট (ন্যূনতম vs সর্বোচ্চ)" else "ESC 7 Diagnostic Test Print (Min vs Max)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = StoreRedPrimary
                )
            }
            Text(
                text = if (isBengali)
                    "ন্যূনতম (n1=1, n2=10) এবং সর্বোচ্চ (n1=7, n2=255) হিটিং টেস্ট প্রিন্ট করে তুলনা করুন। যদি দুটি একই দেখায়, তবে প্রিন্টারের বোর্ড ESC 7 উপেক্ষা করে এবং উপরের ফিক্স দুটি কার্যকর হবে।"
                else
                    "Prints back-to-back blocks at minimum (n1=1, n2=10) vs maximum (n1=7, n2=255) heating. If identical, printer ignores ESC 7 and relies on the above active font & dilation fixes.",
                fontSize = 10.sp,
                color = TextMuted,
                lineHeight = 13.sp
            )

            // Fix 3: Dwell Time Speed Shortcut
            if (printSpeedState != PrintSpeed.SLOW) {
                OutlinedButton(
                    onClick = {
                        printSpeedState = PrintSpeed.SLOW
                        viewModel.updatePrinterSpeed(PrintSpeed.SLOW)
                        StoreInfoManager.updateThermalPrintSpeed(PrintSpeed.SLOW, context)
                        Toast.makeText(
                            context,
                            if (isBengali) "ধীর গতি (সর্বোচ্চ হিট ডুয়েলিং) সক্রিয় হয়েছে" else "Slow Speed (Max Dwell Time) Activated",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, StoreRedPrimary.copy(alpha = 0.5f)),
                    contentPadding = PaddingValues(vertical = 8.dp, horizontal = 12.dp)
                ) {
                    Icon(Icons.Default.HourglassBottom, contentDescription = null, tint = StoreRedPrimary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isBengali) "৩. গতি 'ধীর' করুন (সর্বোচ্চ হিট ডুয়েলিং)" else "3. Switch to Slow Speed (Max Thermal Dwell Time)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = StoreRedPrimary
                    )
                }
            }

            // Vendor Sniffing Guide via Bluetooth HCI Snoop Log
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = SurfaceWarm,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showHciGuide = !showHciGuide },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isBengali) "ভেন্ডর ডার্কনেস প্যাকেট ক্যাপচার (HCI Snoop)" else "Capture Vendor Density via Bluetooth HCI Snoop",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextDark
                            )
                        }
                        Icon(
                            imageVector = if (showHciGuide) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    if (showHciGuide) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (isBengali)
                                "প্রিন্টার প্রস্তুতকারকের নিজস্ব টেস্ট অ্যাপ থাকলে তার স্পেশাল ডার্কনেস কমান্ড এভাবে ক্যাপচার করা যায়:\n" +
                                "১. অ্যান্ড্রয়েড Settings > Developer Options এ 'Enable Bluetooth HCI snoop log' চালু করুন।\n" +
                                "২. প্রস্তুতকারকের অফিসিয়াল টেস্ট অ্যাপ থেকে সর্বোচ্চ ডার্কনেসে একটি টেস্ট প্রিন্ট দিন।\n" +
                                "৩. লগটি Wireshark এ ওপেন করে RFCOMM/btl2cap প্যাকেট দেখুন এবং প্রিন্টের শুরুতে পাঠানো প্রিঅ্যাম্বল বাইটগুলো নোট করুন।"
                            else
                                "If the printer manufacturer has an official setup app:\n" +
                                "1. In Android Developer Options, enable 'Bluetooth HCI snoop log'.\n" +
                                "2. Open the manufacturer's app and print a receipt set to Maximum Density.\n" +
                                "3. Pull btsnoop_hci.log and inspect the RFCOMM payload in Wireshark to extract the proprietary preamble bytes.",
                            fontSize = 10.sp,
                            color = TextMuted,
                            lineHeight = 13.sp
                        )
                    }
                }
            }
        }
    }
}

// Reusable Pill Segment Button
@Composable
private fun PillSegmentButton(
    selected: Boolean,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) StoreRedPrimary else SurfaceWarm,
        border = BorderStroke(
            1.dp,
            if (selected) StoreRedPrimary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) Color.White else TextDark,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) Color.White else TextDark,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// =========================================================================================
// SECTION 2: PDF RECEIPTS & MERCHANT PAYMENT UPI QR
// =========================================================================================

@Composable
private fun PdfReceiptsAndUpiCard(
    context: Context,
    onEditPdfFormatClicked: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = StoreRedPrimary.copy(alpha = 0.12f),
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.QrCode,
                                contentDescription = null,
                                tint = StoreRedPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = if (LanguageManager.isBengali) "পিডিএফ রসিদ ও পেমেন্ট কিউআর" else "PDF Receipts & UPI Payment QR",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextDark
                        )
                        Text(
                            text = "Size: ${StoreInfoManager.pdfPaperSize} • UPI: ${if (StoreInfoManager.merchantUpiId.isNotBlank()) StoreInfoManager.merchantUpiId else "Not set"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Button(
                    onClick = onEditPdfFormatClicked,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = StoreRedPrimary),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (LanguageManager.isBengali) "কাস্টমাইজ" else "Customize",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = SurfaceWarm,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
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
                            text = if (LanguageManager.isBengali) "রসিদে পেমেন্ট কিউআর কোড প্রিন্ট করুন" else "Print Dynamic UPI QR on Bill",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextDark
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (StoreInfoManager.merchantUpiId.isNotBlank())
                                "✓ UPI ID: ${StoreInfoManager.merchantUpiId}"
                            else
                                (if (LanguageManager.isBengali) "⚠ কোনো মার্চেন্ট UPI আইডি কনফিগার করা নেই" else "⚠ No Merchant UPI ID configured"),
                            fontSize = 11.sp,
                            color = if (StoreInfoManager.merchantUpiId.isNotBlank()) StoreGreenProfit else TextMuted
                        )
                    }

                    Switch(
                        checked = StoreInfoManager.showQrOnPdf,
                        onCheckedChange = { isChecked ->
                            StoreInfoManager.updatePdfFormatSettings(
                                upiId = StoreInfoManager.merchantUpiId,
                                payeeName = StoreInfoManager.merchantPayeeName,
                                showQr = isChecked,
                                paperSize = StoreInfoManager.pdfPaperSize,
                                footerNote = StoreInfoManager.customFooterNote,
                                headerColor = StoreInfoManager.pdfHeaderColor,
                                context = context
                            )
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = StoreRedPrimary
                        )
                    )
                }
            }
        }
    }
}

// =========================================================================================
// SECTION 2B: BARCODE SCANNER CAMERA HARDWARE SETTINGS
// =========================================================================================

@Composable
private fun BarcodeScannerCameraCard(
    context: Context
) {
    val pm = context.packageManager
    val hasBackCamera = remember { pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY) }
    val hasFrontCamera = remember { pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_FRONT) }

    var defaultCameraFacing by remember {
        mutableIntStateOf(CameraPreferenceManager.getDefaultCamera(context))
    }
    val isBengali = LanguageManager.isBengali

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = StorePrimary.copy(alpha = 0.12f),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Cameraswitch,
                            contentDescription = null,
                            tint = StorePrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isBengali) "বারকোড স্ক্যানার ক্যামেরা" else "Barcode Scanner Camera",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextDark
                    )
                    Text(
                        text = if (isBengali) "ডিফল্ট স্ক্যানিং ক্যামেরা নির্বাচন করুন" else "Set default camera lens for scanning",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (defaultCameraFacing == CameraSelector.LENS_FACING_FRONT)
                        StorePrimary.copy(alpha = 0.12f)
                    else
                        StoreGreenProfit.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = if (defaultCameraFacing == CameraSelector.LENS_FACING_FRONT) {
                            if (isBengali) "সামনের ক্যামেরা" else "Front Lens"
                        } else {
                            if (isBengali) "পিছনের ক্যামেরা" else "Back Lens"
                        },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (defaultCameraFacing == CameraSelector.LENS_FACING_FRONT)
                            StorePrimary
                        else
                            StoreGreenProfit
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = if (isBengali)
                    "POS বিলিং ও ইনভেন্টরিতে বারকোড স্ক্যানার খুললে কোন ক্যামেরাটি প্রথমে সক্রিয় হবে:"
                else
                    "Select which camera automatically activates when launching barcode scanner in POS or Inventory:",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Back Camera Option
                val isBackSelected = defaultCameraFacing == CameraSelector.LENS_FACING_BACK
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            defaultCameraFacing = CameraSelector.LENS_FACING_BACK
                            CameraPreferenceManager.setDefaultCamera(context, CameraSelector.LENS_FACING_BACK)
                            Toast.makeText(
                                context,
                                if (isBengali) "পিছনের ক্যামেরা ডিফল্ট হিসেবে সেট করা হয়েছে" else "Back camera set as default scanner",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isBackSelected)
                            StorePrimary.copy(alpha = 0.08f)
                        else
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ),
                    border = BorderStroke(
                        width = if (isBackSelected) 1.5.dp else 1.dp,
                        color = if (isBackSelected) StorePrimary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = isBackSelected,
                                onClick = {
                                    defaultCameraFacing = CameraSelector.LENS_FACING_BACK
                                    CameraPreferenceManager.setDefaultCamera(context, CameraSelector.LENS_FACING_BACK)
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = StorePrimary)
                            )
                            if (isBackSelected) {
                                Surface(
                                    shape = CircleShape,
                                    color = StorePrimary,
                                    modifier = Modifier.size(8.dp)
                                ) {}
                            }
                        }
                        Text(
                            text = if (isBengali) "পিছনের ক্যামেরা" else "Back Camera",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isBackSelected) StorePrimary else TextDark
                        )
                        Text(
                            text = if (isBengali) "সাধারণ বারকোড স্ক্যানিংয়ের জন্য উপযুক্ত" else "Recommended for standard handheld items",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            lineHeight = 14.sp
                        )
                    }
                }

                // Front Camera Option
                val isFrontSelected = defaultCameraFacing == CameraSelector.LENS_FACING_FRONT
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = hasFrontCamera) {
                            defaultCameraFacing = CameraSelector.LENS_FACING_FRONT
                            CameraPreferenceManager.setDefaultCamera(context, CameraSelector.LENS_FACING_FRONT)
                            Toast.makeText(
                                context,
                                if (isBengali) "সামনের ক্যামেরা ডিফল্ট হিসেবে সেট করা হয়েছে" else "Front camera set as default scanner",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isFrontSelected)
                            StorePrimary.copy(alpha = 0.08f)
                        else
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ),
                    border = BorderStroke(
                        width = if (isFrontSelected) 1.5.dp else 1.dp,
                        color = if (isFrontSelected) StorePrimary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = isFrontSelected,
                                onClick = {
                                    if (hasFrontCamera) {
                                        defaultCameraFacing = CameraSelector.LENS_FACING_FRONT
                                        CameraPreferenceManager.setDefaultCamera(context, CameraSelector.LENS_FACING_FRONT)
                                    }
                                },
                                enabled = hasFrontCamera,
                                colors = RadioButtonDefaults.colors(selectedColor = StorePrimary)
                            )
                            if (isFrontSelected) {
                                Surface(
                                    shape = CircleShape,
                                    color = StorePrimary,
                                    modifier = Modifier.size(8.dp)
                                ) {}
                            }
                        }
                        Text(
                            text = if (isBengali) "সামনের ক্যামেরা" else "Front Camera",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isFrontSelected) StorePrimary else TextDark
                        )
                        Text(
                            text = if (!hasFrontCamera) {
                                if (isBengali) "ডিভাইসে ক্যামেরা নেই" else "Unavailable"
                            } else {
                                if (isBengali) "কাউন্টার স্ট্যান্ড বা সেলফি স্ক্যানিং" else "Ideal for mounted counter stands"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (!hasFrontCamera) StoreRedAlert else TextMuted,
                            lineHeight = 14.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = StorePrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isBengali)
                            "টিপ: স্ক্যানার স্ক্রিনে ক্যামেরা সুইচার আইকনটি চেপে ধরলেও ডিফল্ট পরিবর্তন করা যায়।"
                        else
                            "Tip: Long-press the camera switch icon in the scanner dialog to quickly change your default camera anytime.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        lineHeight = 15.sp
                    )
                }
            }
        }
    }
}

// =========================================================================================
// SECTION 3: LANGUAGE & REGIONAL PREFERENCES
// =========================================================================================

@Composable
private fun LanguagePreferencesCard(
    context: Context
) {
    var billLangState by remember { mutableStateOf(StoreInfoManager.billLanguage) }
    var smsLangState by remember { mutableStateOf(StoreInfoManager.smsLanguage) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = StorePrimary.copy(alpha = 0.12f),
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Translate,
                            contentDescription = null,
                            tint = StorePrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = if (LanguageManager.isBengali) "বিল ও এসএমএস ভাষা" else "Bill & SMS Language",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextDark
                    )
                    Text(
                        text = if (LanguageManager.isBengali)
                            "প্রিন্ট রসিদ, পিডিএফ এবং গ্রাহকের এসএমএস-এর ভাষা নির্ধারণ করুন"
                        else
                            "Default language for Printed Receipts, PDF Invoices & Customer SMS",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

            // 1. Bill / Receipt Language Selector
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = if (LanguageManager.isBengali) "বিল ও রসিদের ভাষা (Receipt & Bill Language):" else "Receipt & Bill Language:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextDark
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isBillBn = billLangState.equals("BN", ignoreCase = true) ||
                            billLangState.equals("BANGLA", ignoreCase = true) ||
                            billLangState.equals("BENGALI", ignoreCase = true)

                    PillSegmentButton(
                        selected = isBillBn,
                        label = "বাংলা (Bengali)",
                        icon = if (isBillBn) Icons.Default.Check else null,
                        onClick = {
                            billLangState = "BN"
                            StoreInfoManager.updateLanguagePreferences("BN", smsLangState, context)
                            Toast.makeText(context, if (LanguageManager.isBengali) "বিলের ভাষা বাংলা সেট করা হয়েছে" else "Bill language set to Bengali", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    )

                    PillSegmentButton(
                        selected = !isBillBn,
                        label = "English",
                        icon = if (!isBillBn) Icons.Default.Check else null,
                        onClick = {
                            billLangState = "EN"
                            StoreInfoManager.updateLanguagePreferences("EN", smsLangState, context)
                            Toast.makeText(context, if (LanguageManager.isBengali) "বিলের ভাষা ইংরেজি সেট করা হয়েছে" else "Bill language set to English", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 2. Customer SMS & Alerts Language Selector
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = if (LanguageManager.isBengali) "গ্রাহক এসএমএস ও বার্তার ভাষা (SMS & Alerts Language):" else "SMS & Notifications Language:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextDark
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isSmsBn = smsLangState.equals("BN", ignoreCase = true) ||
                            smsLangState.equals("BANGLA", ignoreCase = true) ||
                            smsLangState.equals("BENGALI", ignoreCase = true)

                    PillSegmentButton(
                        selected = isSmsBn,
                        label = "বাংলা (Bengali)",
                        icon = if (isSmsBn) Icons.Default.Check else null,
                        onClick = {
                            smsLangState = "BN"
                            StoreInfoManager.updateLanguagePreferences(billLangState, "BN", context)
                            Toast.makeText(context, if (LanguageManager.isBengali) "এসএমএস ভাষা বাংলা সেট করা হয়েছে" else "SMS language set to Bengali", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    )

                    PillSegmentButton(
                        selected = !isSmsBn,
                        label = "English",
                        icon = if (!isSmsBn) Icons.Default.Check else null,
                        onClick = {
                            smsLangState = "EN"
                            StoreInfoManager.updateLanguagePreferences(billLangState, "EN", context)
                            Toast.makeText(context, if (LanguageManager.isBengali) "এসএমএস ভাষা ইংরেজি সেট করা হয়েছে" else "SMS language set to English", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

// =========================================================================================
// SECTION 4: DIGITAL KHATA LATE INTEREST & CREDIT POLICY (CLEAN UNIFIED CARD)
// =========================================================================================

@Composable
private fun KhataInterestPolicyCard(
    onConfigureKhataInterestClicked: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = StoreGold.copy(alpha = 0.15f),
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Percent,
                                contentDescription = null,
                                tint = StoreGold,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = if (LanguageManager.isBengali) "বাকি সুদ ও ক্রেডিট পলিসি" else "Khata Interest & Credit Policy",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextDark,
                            lineHeight = 20.sp
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (StoreInfoManager.interestEnabled) StoreGold.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    text = if (StoreInfoManager.interestEnabled) {
                                        if (LanguageManager.isBengali) "সক্রিয়" else "ACTIVE"
                                    } else {
                                        if (LanguageManager.isBengali) "বন্ধ" else "OFF"
                                    },
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false,
                                    color = if (StoreInfoManager.interestEnabled) StoreGold else TextMuted,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Text(
                                text = if (LanguageManager.isBengali)
                                    "মাসিক সুদ ও গ্রেস পিরিয়ড পলিসি"
                                else
                                    "Monthly late fee & grace period",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Single, prominent configure action
                Button(
                    onClick = onConfigureKhataInterestClicked,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = StoreGold),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (LanguageManager.isBengali) "কনফিগার" else "Configure",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            // Summary Metrics Grid
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onConfigureKhataInterestClicked),
                shape = RoundedCornerShape(12.dp),
                color = SurfaceWarm,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MetricMiniBox(
                        label = if (LanguageManager.isBengali) "মাসিক সুদ" else "Monthly Rate",
                        value = if (StoreInfoManager.interestEnabled) "${StoreInfoManager.monthlyInterestRate}% / mo" else "0.0%",
                        highlightColor = if (StoreInfoManager.interestEnabled) StoreGold else TextMuted,
                        modifier = Modifier.weight(1f)
                    )

                    VerticalDivider(
                        modifier = Modifier.height(28.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    MetricMiniBox(
                        label = if (LanguageManager.isBengali) "গ্রেস পিরিয়ড" else "Grace Period",
                        value = if (StoreInfoManager.interestEnabled) "${StoreInfoManager.interestGracePeriodDays} Days" else "Off",
                        highlightColor = TextDark,
                        modifier = Modifier.weight(1f)
                    )

                    VerticalDivider(
                        modifier = Modifier.height(28.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    MetricMiniBox(
                        label = if (LanguageManager.isBengali) "হিসাব মোড" else "Calc Mode",
                        value = if (StoreInfoManager.interestEnabled) StoreInfoManager.interestCalculationMode else "None",
                        highlightColor = TextDark,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricMiniBox(
    label: String,
    value: String,
    highlightColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = TextMuted,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = highlightColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// =========================================================================================
// SECTION 5: AUTOMATED CUSTOMER SMS FOR CREDIT SALES
// =========================================================================================

@Composable
private fun AutomatedCreditSmsCard(
    context: Context,
    onRequestSmsPermission: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (StoreInfoManager.autoSendCreditSms) StorePrimary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Sms,
                                contentDescription = null,
                                tint = if (StoreInfoManager.autoSendCreditSms) StorePrimary else TextMuted,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = if (LanguageManager.isBengali) "স্বয়ংক্রিয় খাতা ও বাকি এসএমএস" else "Auto-Send Khata Credit SMS",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextDark
                        )
                        Text(
                            text = if (LanguageManager.isBengali)
                                "বাকি বিক্রয় ও পরিশোধের পর স্বয়ংক্রিয় অনলাইন খাতা লিংক সহ এসএমএস পাঠানো"
                            else
                                "Instant SMS receipt with live ledger link on credit transactions",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            lineHeight = 15.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Switch(
                    checked = StoreInfoManager.autoSendCreditSms,
                    onCheckedChange = { isChecked ->
                        if (isChecked) {
                            onRequestSmsPermission()
                        }
                        StoreInfoManager.updateAutoSendCreditSms(isChecked, context)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = StorePrimary
                    )
                )
            }

            // Realistic SMS Bubble Preview Widget
            if (StoreInfoManager.autoSendCreditSms) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = SurfaceWarm,
                    border = BorderStroke(1.dp, StorePrimary.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = CircleShape,
                                    color = StorePrimary,
                                    modifier = Modifier.size(8.dp)
                                ) {}
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = (StoreInfoManager.storeName.ifBlank { "STORE" }).uppercase(),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = StorePrimary
                                )
                            }
                            Text(
                                text = "SMS Preview",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextMuted
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 12.dp, bottomStart = 12.dp, bottomEnd = 12.dp),
                            color = StorePrimary.copy(alpha = 0.08f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (LanguageManager.isBengali)
                                    "\"প্রিয় গ্রাহক, ${StoreInfoManager.storeName.ifBlank { "আমাদের দোকান" }} থেকে ₹৫০০ টাকার বাকি হিসাবভুক্ত হয়েছে। মোট বকেয়া: ₹১,২০০। আপনার অনলাইন খাতা দেখতে ভিজিট করুন: https://...\""
                                else
                                    "\"Dear Customer, Rs.500 credit purchase added at ${StoreInfoManager.storeName.ifBlank { "Store" }}. Current Due: Rs.1,200. View live online ledger: https://...\"",
                                fontSize = 11.sp,
                                color = TextDark,
                                lineHeight = 16.sp,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// =========================================================================================
// SECTION 6: COST PRICE PROFIT PROTECTION
// =========================================================================================

@Composable
private fun CostPriceProtectionCard(
    context: Context
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = StoreGreenProfit.copy(alpha = 0.12f),
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = StoreGreenProfit,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = if (LanguageManager.isBengali) "ক্রয়মূল্য নিরাপত্তা ও লোকসান প্রতিরোধ" else "Cost Price Profit Protection",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextDark
                    )
                    Text(
                        text = if (LanguageManager.isBengali)
                            "ক্রয়মূল্যের (Cost Price) নিচে অতিরিক্ত ছাড় দিয়ে লোকসানে বিক্রয় প্রতিরোধ করুন।"
                        else
                            "Guards against selling below purchase cost price, ensuring profit margins.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        lineHeight = 15.sp
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Switch(
                checked = StoreInfoManager.enforceCostPriceDiscountLimit,
                onCheckedChange = { isChecked ->
                    StoreInfoManager.updateCostPriceDiscountLimitEnforcement(isChecked, context)
                    Toast.makeText(
                        context,
                        if (isChecked) "Cost price discount protection enabled" else "Cost price discount restriction relaxed",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = StoreGreenProfit
                )
            )
        }
    }
}

// =========================================================================================
// SECTION 7: UNIFIED OFFERS & PROMOTIONS HUB
// =========================================================================================

@Composable
private fun UnifiedOffersCard(
    onOpenOffersHubClicked: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = StorePrimary.copy(alpha = 0.12f),
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.LocalOffer,
                            contentDescription = null,
                            tint = StorePrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = if (LanguageManager.isBengali) "অফার ও ডিসকাউন্ট হাব" else "Unified Offers & Promotions",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextDark
                    )
                    Text(
                        text = if (LanguageManager.isBengali)
                            "কুপন কোড, বাই-এক্স-গেট-ওয়াই ও শতকরা ছাড় কনফিগার করুন"
                        else
                            "Manage coupon codes, bundle deals, and percentage discounts",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = onOpenOffersHubClicked,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = StorePrimary),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (LanguageManager.isBengali) "ম্যানেজ" else "Manage",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

// =========================================================================================
// HELPER FUNCTIONS FOR SMART DEVICE FILTERING
// =========================================================================================

/**
 * Smart detection heuristics for thermal POS printer devices
 */
private fun isLikelyPrinter(name: String): Boolean {
    val lower = name.lowercase().trim()
    return lower.contains("printer") ||
            lower.contains("pos") ||
            lower.contains("print") ||
            lower.contains("thermal") ||
            lower.contains("esc") ||
            lower.contains("rpp") ||
            lower.contains("mpt") ||
            lower.contains("mtp") ||
            lower.contains("zj") ||
            lower.contains("seznik") ||
            lower.contains("ld-") ||
            lower.contains("bt-") ||
            lower.contains("bt") ||
            lower.contains("bluetooth") ||
            lower.contains("pt-") ||
            lower.contains("pt210") ||
            lower.contains("pt280") ||
            lower.contains("hoin") ||
            lower.contains("netum") ||
            lower.contains("munbyn") ||
            lower.contains("goojprt") ||
            lower.contains("innerprinter") ||
            lower.contains("epson") ||
            lower.contains("tvs") ||
            lower.contains("star") ||
            lower.contains("bixolon") ||
            lower.contains("xprinter") ||
            lower.contains("xp-") ||
            lower.contains("xp") ||
            lower.contains("everycom") ||
            lower.contains("ec-") ||
            lower.contains("bluprint") ||
            lower.contains("pegasus") ||
            lower.contains("amigo") ||
            lower.contains("milestone") ||
            lower.contains("mht") ||
            lower.contains("phomemo") ||
            lower.contains("paperang") ||
            lower.contains("niimbot") ||
            lower.contains("qs-") ||
            lower.contains("receipt") ||
            lower.contains("memo") ||
            lower.contains("label") ||
            lower.contains("d11") ||
            lower.contains("peripage") ||
            lower.contains("58") ||
            lower.contains("80")
}

/**
 * Smart detection heuristics for audio/headphone/accessory devices
 */
private fun isLikelyAudio(name: String): Boolean {
    val lower = name.lowercase().trim()
    return lower.contains("buds") ||
            lower.contains("rockerz") ||
            lower.contains("mivi") ||
            lower.contains("boat") ||
            lower.contains("audio") ||
            lower.contains("airpod") ||
            lower.contains("headphone") ||
            lower.contains("earphone") ||
            lower.contains("speaker") ||
            lower.contains("wireless") ||
            lower.contains("anc") ||
            lower.contains("zeb-") ||
            lower.contains("fpods") ||
            lower.contains("noise") ||
            lower.contains("sound")
}
