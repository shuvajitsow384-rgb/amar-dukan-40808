package com.example.ui.components

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.example.data.local.entities.Product
import com.example.ui.theme.*
import com.example.utils.CameraPreferenceManager
import com.example.utils.DefaultCameraOption
import com.example.utils.LanguageManager
import com.example.viewmodel.StoreViewModel
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraX Image Analyzer powered by Google ML Kit Barcode Scanning.
 * Supports all retail product 1D and 2D barcode formats:
 * EAN-13, EAN-8, UPC-A, UPC-E, CODE-128, CODE-39, CODE-93, CODABAR, ITF, QR_CODE, DATA_MATRIX, PDF417, AZTEC.
 */
class BarcodeAnalyzer(
    private val onBarcodeScanned: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val scanner: BarcodeScanner by lazy {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_CODE_93,
                Barcode.FORMAT_CODABAR,
                Barcode.FORMAT_ITF,
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_DATA_MATRIX,
                Barcode.FORMAT_PDF417,
                Barcode.FORMAT_AZTEC
            )
            .build()
        BarcodeScanning.getClient(options)
    }

    @Volatile
    private var lastScannedCode: String? = null
    @Volatile
    private var lastScannedTime: Long = 0L

    private fun triggerScanned(code: String) {
        val clean = code.trim()
        if (clean.isBlank()) return
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (clean != lastScannedCode || (now - lastScannedTime) > 1500) {
                lastScannedCode = clean
                lastScannedTime = now
                mainHandler.post {
                    onBarcodeScanned(clean)
                }
            }
        }
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)
            scanner.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    for (barcode in barcodes) {
                        val rawValue = barcode.rawValue ?: barcode.displayValue
                        if (!rawValue.isNullOrBlank()) {
                            triggerScanned(rawValue)
                            break
                        }
                    }
                }
                .addOnFailureListener { e ->
                    Log.w("BarcodeAnalyzer", "Barcode scan processing error: ${e.message}")
                }
                .addOnCompleteListener {
                    try {
                        imageProxy.close()
                    } catch (e: Exception) {
                        Log.e("BarcodeAnalyzer", "Error closing ImageProxy", e)
                    }
                }
        } else {
            try {
                imageProxy.close()
            } catch (_: Exception) {}
        }
    }
}

/**
 * Comprehensive Camera Barcode Scanner Modal Dialog for POS Counter.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BarcodeScannerModal(
    viewModel: StoreViewModel,
    onDismiss: () -> Unit,
    onOrderScanned: ((orderNumber: String) -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val allProducts by viewModel.allProducts.collectAsState()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    var isFlashOn by remember { mutableStateOf(false) }
    var isFlashSupported by remember { mutableStateOf(true) }
    val pmHasFrontCamera = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)
    }
    val defaultInitialFacing = remember {
        val saved = CameraPreferenceManager.getDefaultCamera(context)
        if (saved == CameraSelector.LENS_FACING_FRONT && !pmHasFrontCamera) {
            CameraSelector.LENS_FACING_BACK
        } else {
            saved
        }
    }
    var lensFacing by remember { mutableIntStateOf(defaultInitialFacing) }
    var hasBackCamera by remember { mutableStateOf(true) }
    var hasFrontCamera by remember { mutableStateOf(pmHasFrontCamera) }
    var showSetDefaultCameraDialog by remember { mutableStateOf(false) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var isContinuousMode by remember { mutableStateOf(true) }
    var manualBarcodeQuery by remember { mutableStateOf("") }
    var showManualInput by remember { mutableStateOf(false) }

    // Last Scanned Item Banner state
    var lastScannedProduct by remember { mutableStateOf<Product?>(null) }
    var lastScannedVariant by remember { mutableStateOf<com.example.data.local.entities.BarcodeVariant?>(null) }
    var lastScannedBarcodeStr by remember { mutableStateOf<String?>(null) }
    var isUnrecognizedBarcode by remember { mutableStateOf(false) }
    var showAssignProductSheet by remember { mutableStateOf(false) }

    var cameraRef by remember { mutableStateOf<Camera?>(null) }
    var cameraProviderRef by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            try {
                cameraProviderRef?.unbindAll()
                cameraProviderRef = null
                analysisExecutor.shutdown()
            } catch (e: Exception) {
                Log.e("BarcodeScanner", "Error unbinding camera on dispose", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun playHapticFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                val vibrator = vibratorManager.defaultVibrator
                vibrator.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(120)
                }
            }
        } catch (e: Exception) {
            Log.e("BarcodeScanner", "Haptic error", e)
        }
    }

    fun handleScannedCode(scannedCode: String) {
        val cleanBarcode = scannedCode.trim()
        if (cleanBarcode.isBlank()) return

        // 0. Check for Online Order Packing Slip Barcode format ("ORD:<orderNumber>", "ORD-<orderNumber>", etc.)
        val scannedOrderNum = when {
            cleanBarcode.startsWith("ORD:", ignoreCase = true) -> cleanBarcode.substring(4).trim()
            cleanBarcode.startsWith("ORD-", ignoreCase = true) -> cleanBarcode.trim()
            cleanBarcode.startsWith("ORD", ignoreCase = true) -> cleanBarcode.trim()
            else -> null
        }
        if (scannedOrderNum != null && scannedOrderNum.isNotBlank()) {
            playHapticFeedback()
            if (onOrderScanned != null) {
                onOrderScanned(scannedOrderNum)
                onDismiss()
                return
            } else {
                Toast.makeText(
                    context,
                    "📦 Online Order #$scannedOrderNum Scanned",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        val cleanNoZero = cleanBarcode.trimStart('0')

        // Always check fresh catalog list (state or ViewModel StateFlow fallback)
        val catalog = if (allProducts.isNotEmpty()) allProducts else viewModel.allProducts.value

        // 1. Search for matching pre-packed Barcode Variant first
        for (product in catalog) {
            val variant = product.findVariantByBarcode(cleanBarcode)
            if (variant != null) {
                playHapticFeedback()
                val added = viewModel.addVariantToCart(product, variant, 1.0)
                if (added) {
                    lastScannedProduct = product
                    lastScannedVariant = variant
                    lastScannedBarcodeStr = cleanBarcode
                    isUnrecognizedBarcode = false
                    showAssignProductSheet = false
                    Toast.makeText(
                        context,
                        "✓ Added ${variant.getDisplayTitle(product.getDisplayName())} (₹%.2f)".format(variant.price),
                        Toast.LENGTH_SHORT
                    ).show()
                    if (!isContinuousMode) {
                        onDismiss()
                    }
                } else {
                    playHapticFeedback()
                    Toast.makeText(
                        context,
                        "⚠️ Insufficient stock for ${variant.getDisplayTitle(product.getDisplayName())}!\nAvailable: ${product.getFormattedStockDisplay()}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return
            }
        }

        // 2. Search for matching Primary Product Barcode
        val matchedProduct = catalog.find { p ->
            val pBarcode = p.barcode?.trim() ?: ""
            pBarcode.isNotBlank() && (
                pBarcode.equals(cleanBarcode, ignoreCase = true) ||
                (cleanNoZero.isNotBlank() && pBarcode.trimStart('0') == cleanNoZero)
            )
        }

        if (matchedProduct != null) {
            playHapticFeedback()
            val currentCartBaseQty = viewModel.cartItems
                .filter { it.product.id == matchedProduct.id }
                .sumOf { it.getBaseQuantityDeducted() }
            val addedBaseQty = matchedProduct.convertQuantityToBaseUnit(1.0, matchedProduct.unitType)

            if (addedBaseQty > 0.0 && (currentCartBaseQty + addedBaseQty) > (matchedProduct.currentStock + 0.00001)) {
                playHapticFeedback()
                Toast.makeText(
                    context,
                    "⚠️ Insufficient stock for ${matchedProduct.getDisplayName()}!\nAvailable: ${matchedProduct.getFormattedStockDisplay()}",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                // Add to cart
                viewModel.addToCart(matchedProduct, 1.0)
                lastScannedProduct = matchedProduct
                lastScannedVariant = null
                lastScannedBarcodeStr = cleanBarcode
                isUnrecognizedBarcode = false
                showAssignProductSheet = false

                Toast.makeText(
                    context,
                    "✓ Added ${matchedProduct.getDisplayName()} (₹%.2f)".format(matchedProduct.sellingPrice),
                    Toast.LENGTH_SHORT
                ).show()

                if (!isContinuousMode) {
                    onDismiss()
                }
            }
        } else {
            // Unrecognized Barcode
            playHapticFeedback()
            lastScannedProduct = null
            lastScannedVariant = null
            lastScannedBarcodeStr = cleanBarcode
            isUnrecognizedBarcode = true
        }
    }

    fun bindCameraUseCases(
        provider: ProcessCameraProvider,
        pView: PreviewView,
        facing: Int
    ) {
        try {
            provider.unbindAll()

            val desiredSelector = CameraSelector.Builder().requireLensFacing(facing).build()
            val selector = if (provider.hasCamera(desiredSelector)) {
                desiredSelector
            } else if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                desiredSelector
            }

            val preview = Preview.Builder()
                .setTargetResolution(Size(1280, 720))
                .build().also {
                    it.setSurfaceProvider(pView.surfaceProvider)
                }
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(analysisExecutor, BarcodeAnalyzer { code ->
                handleScannedCode(code)
            })

            val cam = provider.bindToLifecycle(
                lifecycleOwner,
                selector,
                preview,
                imageAnalysis
            )
            cameraRef = cam
            val flashAvailable = cam.cameraInfo.hasFlashUnit()
            isFlashSupported = flashAvailable
            if (!flashAvailable) {
                isFlashOn = false
            } else if (isFlashOn) {
                cam.cameraControl.enableTorch(true)
            }
        } catch (e: Exception) {
            Log.e("BarcodeScanner", "Camera binding failed for facing: $facing", e)
        }
    }

    LaunchedEffect(lensFacing, previewViewRef, cameraProviderRef, hasCameraPermission) {
        val provider = cameraProviderRef
        val pView = previewViewRef
        if (provider != null && pView != null && hasCameraPermission) {
            bindCameraUseCases(provider, pView, lensFacing)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp)
            ) {
                // Header Toolbar
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
                            shape = CircleShape,
                            color = StorePrimary.copy(alpha = 0.15f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.QrCodeScanner,
                                    contentDescription = "Barcode Scanner",
                                    tint = StorePrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Text(
                                text = "POS Barcode Scanner",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (isContinuousMode) "Continuous Scanning Active" else "Single Scan Mode",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isContinuousMode) StoreGreenProfit else TextMuted,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Camera Switch (Back/Front Camera) Toggle
                        if (hasFrontCamera) {
                            Surface(
                                shape = CircleShape,
                                color = if (lensFacing == CameraSelector.LENS_FACING_FRONT)
                                    StorePrimary.copy(alpha = 0.18f)
                                else
                                    SurfaceWarm,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .combinedClickable(
                                        onClick = {
                                            val provider = cameraProviderRef
                                            if (provider == null) {
                                                Toast.makeText(context, "Initializing camera...", Toast.LENGTH_SHORT).show()
                                                return@combinedClickable
                                            }
                                            if (!hasFrontCamera && !hasBackCamera) {
                                                Toast.makeText(context, "Camera switching not supported on this device", Toast.LENGTH_SHORT).show()
                                                return@combinedClickable
                                            }
                                            if (lensFacing == CameraSelector.LENS_FACING_BACK && !hasFrontCamera) {
                                                Toast.makeText(context, "Front camera not found on this device", Toast.LENGTH_SHORT).show()
                                                return@combinedClickable
                                            }
                                            if (lensFacing == CameraSelector.LENS_FACING_FRONT && !hasBackCamera) {
                                                Toast.makeText(context, "Back camera not found on this device", Toast.LENGTH_SHORT).show()
                                                return@combinedClickable
                                            }

                                            playHapticFeedback()

                                            // Turn off flashlight before switching cameras
                                            if (isFlashOn) {
                                                try {
                                                    cameraRef?.cameraControl?.enableTorch(false)
                                                } catch (_: Exception) {}
                                                isFlashOn = false
                                            }

                                            val newFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                                                CameraSelector.LENS_FACING_FRONT
                                            } else {
                                                CameraSelector.LENS_FACING_BACK
                                            }
                                            lensFacing = newFacing
                                            CameraPreferenceManager.setDefaultCamera(context, newFacing)
                                            val isBn = LanguageManager.isBengali
                                            val toastMsg = if (newFacing == CameraSelector.LENS_FACING_FRONT) {
                                                if (isBn) "সামনের ক্যামেরা সক্রিয় • ডিফল্ট হিসেবে সংরক্ষিত" else "Front camera active • Saved as default"
                                            } else {
                                                if (isBn) "পিছনের ক্যামেরা সক্রিয় • ডিফল্ট হিসেবে সংরক্ষিত" else "Back camera active • Saved as default"
                                            }
                                            Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
                                        },
                                        onLongClick = {
                                            playHapticFeedback()
                                            showSetDefaultCameraDialog = true
                                        }
                                    )
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Cameraswitch,
                                        contentDescription = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                                            "Switch to Front Camera (Hold to configure default)"
                                        else
                                            "Switch to Back Camera (Hold to configure default)",
                                        tint = if (lensFacing == CameraSelector.LENS_FACING_FRONT) StorePrimary else TextDark,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        // Flashlight Toggle
                        Surface(
                            onClick = {
                                if (!isFlashSupported) {
                                    Toast.makeText(
                                        context,
                                        "Flashlight not supported on ${if (lensFacing == CameraSelector.LENS_FACING_FRONT) "front" else "this"} camera",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    return@Surface
                                }
                                isFlashOn = !isFlashOn
                                cameraRef?.cameraControl?.enableTorch(isFlashOn)
                            },
                            shape = CircleShape,
                            color = if (isFlashOn) StoreSaffronAccent.copy(alpha = 0.2f)
                            else if (!isFlashSupported) SurfaceWarm.copy(alpha = 0.4f)
                            else SurfaceWarm,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isFlashOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                                    contentDescription = "Flashlight Toggle",
                                    tint = if (isFlashOn) StoreSaffronAccent else if (!isFlashSupported) TextMuted else TextDark,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // Close Button
                        Surface(
                            onClick = onDismiss,
                            shape = CircleShape,
                            color = Color.Transparent,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Close Scanner",
                                    tint = TextDark,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Scanner Camera Preview View Area
                if (!hasCameraPermission) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(20.dp)
                        ) {
                            Icon(
                                Icons.Default.CameraAlt,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(54.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Camera Permission Required",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Please grant camera permission to scan product barcodes directly into POS bills.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                                colors = ButtonDefaults.buttonColors(containerColor = StorePrimary)
                            ) {
                                Text("Grant Camera Permission")
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Black)
                    ) {
                        // CameraX Preview View
                        AndroidView(
                            factory = { ctx ->
                                val previewView = PreviewView(ctx).apply {
                                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                    scaleType = PreviewView.ScaleType.FILL_CENTER
                                }

                                // Tap to Focus and Auto-Exposure Metering
                                previewView.setOnTouchListener { view, event ->
                                    if (event.action == MotionEvent.ACTION_UP) {
                                        try {
                                            val factory = previewView.meteringPointFactory
                                            val point = factory.createPoint(event.x, event.y)
                                            val action = FocusMeteringAction.Builder(
                                                point,
                                                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                                            ).setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS).build()
                                            cameraRef?.cameraControl?.startFocusAndMetering(action)
                                        } catch (e: Exception) {
                                            Log.w("BarcodeScanner", "Focus metering error: ${e.message}")
                                        }
                                        view.performClick()
                                    }
                                    true
                                }

                                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                                val mainExecutor = ContextCompat.getMainExecutor(ctx)

                                cameraProviderFuture.addListener({
                                    val cameraProvider = cameraProviderFuture.get()
                                    cameraProviderRef = cameraProvider
                                    hasBackCamera = try { cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) } catch (_: Exception) { false }
                                    hasFrontCamera = try { cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) } catch (_: Exception) { false }
                                    previewViewRef = previewView
                                }, mainExecutor)

                                previewView
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        // Active Front Camera Badge
                        if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                            val isDefault = CameraPreferenceManager.defaultLensFacing == CameraSelector.LENS_FACING_FRONT
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 12.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { showSetDefaultCameraDialog = true },
                                shape = RoundedCornerShape(16.dp),
                                color = StorePrimary.copy(alpha = 0.88f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Cameraswitch,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = if (isDefault) "Front Camera (Default)" else "Front Camera • Tap to configure",
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        // Reticle Scanning Frame Overlay
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(width = 280.dp, height = 180.dp)
                                    .border(2.dp, StorePrimary, RoundedCornerShape(16.dp))
                                    .background(Color.Black.copy(alpha = 0.1f))
                            ) {
                                // Corner Accent Markers
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .size(24.dp)
                                        .border(
                                            BorderStroke(4.dp, StoreSaffronAccent),
                                            RoundedCornerShape(topStart = 16.dp)
                                        )
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(24.dp)
                                        .border(
                                            BorderStroke(4.dp, StoreSaffronAccent),
                                            RoundedCornerShape(topEnd = 16.dp)
                                        )
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .size(24.dp)
                                        .border(
                                            BorderStroke(4.dp, StoreSaffronAccent),
                                            RoundedCornerShape(bottomStart = 16.dp)
                                        )
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .size(24.dp)
                                        .border(
                                            BorderStroke(4.dp, StoreSaffronAccent),
                                            RoundedCornerShape(bottomEnd = 16.dp)
                                        )
                                )

                                // Center Red Laser Line
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .fillMaxWidth(0.9f)
                                        .height(2.dp)
                                        .background(StoreRedAlert)
                                )
                            }
                        }

                        // Bottom Help Hint Text Overlay inside Camera View
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 12.dp),
                            shape = RoundedCornerShape(20.dp),
                            color = Color.Black.copy(alpha = 0.65f)
                        ) {
                            Text(
                                text = "Point camera at product barcode",
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Last Scanned Status / Feedback Banner
                if (lastScannedProduct != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = StoreGreenProfit.copy(alpha = 0.12f)),
                        border = BorderStroke(1.dp, StoreGreenProfit),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = StoreGreenProfit,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = lastScannedProduct!!.getDisplayName(),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = TextDark
                                    )
                                    Text(
                                        text = "Barcode: ${lastScannedBarcodeStr} • Added 1 unit to cart",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = StoreGreenProfit,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            Text(
                                text = "₹%.2f".format(lastScannedProduct!!.sellingPrice),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = StoreGreenProfit
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                } else if (isUnrecognizedBarcode && lastScannedBarcodeStr != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = StoreRedAlert.copy(alpha = 0.12f)),
                        border = BorderStroke(1.dp, StoreRedAlert),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp)
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
                                    Icon(
                                        Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = StoreRedAlert,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = "Unrecognized Barcode",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = StoreRedAlert
                                        )
                                        Text(
                                            text = "Code: $lastScannedBarcodeStr",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextMuted
                                        )
                                    }
                                }

                                Button(
                                    onClick = { showAssignProductSheet = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = StorePrimary),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Assign to Product", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Controls Row: Continuous Mode Switch & Manual Input Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = isContinuousMode,
                            onCheckedChange = { isContinuousMode = it },
                            thumbContent = {
                                Icon(
                                    imageVector = if (isContinuousMode) Icons.Default.Repeat else Icons.Default.Filter1,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isContinuousMode) "Multi-Scan Mode" else "Single Scan Mode",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    OutlinedButton(
                        onClick = { showManualInput = !showManualInput },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = if (showManualInput) Icons.Default.CameraAlt else Icons.Default.Keyboard,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (showManualInput) "Use Camera" else "Type Barcode",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                // Manual Barcode Keyboard Entry Field
                AnimatedVisibility(visible = showManualInput) {
                    Column {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = manualBarcodeQuery,
                                onValueChange = { manualBarcodeQuery = it },
                                placeholder = { Text("Type or paste barcode number...") },
                                leadingIcon = { Icon(Icons.Default.QrCode, contentDescription = null) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    if (manualBarcodeQuery.isNotBlank()) {
                                        handleScannedCode(manualBarcodeQuery)
                                        manualBarcodeQuery = ""
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = StorePrimary)
                            ) {
                                Text("Add")
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Bottom Action: Finish / Back to POS
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Text(
                        text = "Done / Back to Billing",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    // Modal to assign unrecognized barcode to an existing inventory product
    if (showAssignProductSheet && lastScannedBarcodeStr != null) {
        val currentCatalog = if (allProducts.isNotEmpty()) allProducts else viewModel.allProducts.value
        AssignBarcodeDialog(
            barcodeToAssign = lastScannedBarcodeStr!!,
            products = currentCatalog,
            onDismiss = { showAssignProductSheet = false },
            onAssignProduct = { targetProduct ->
                val updatedProduct = targetProduct.copy(barcode = lastScannedBarcodeStr)
                viewModel.saveProduct(updatedProduct)
                viewModel.addToCart(updatedProduct, 1.0)
                showAssignProductSheet = false
                isUnrecognizedBarcode = false
                lastScannedProduct = updatedProduct

                Toast.makeText(
                    context,
                    "✓ Barcode assigned to ${updatedProduct.getDisplayName()} & added to cart!",
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }

    // Modal to configure default camera lens facing (Back/Front)
    if (showSetDefaultCameraDialog) {
        DefaultCameraSelectionDialog(
            currentDefaultFacing = CameraPreferenceManager.getDefaultCamera(context),
            hasBackCamera = hasBackCamera,
            hasFrontCamera = hasFrontCamera,
            onDismiss = { showSetDefaultCameraDialog = false },
            onSaveDefault = { chosenFacing ->
                CameraPreferenceManager.setDefaultCamera(context, chosenFacing)
                if (lensFacing != chosenFacing) {
                    if (isFlashOn) {
                        try {
                            cameraRef?.cameraControl?.enableTorch(false)
                        } catch (_: Exception) {}
                        isFlashOn = false
                    }
                    lensFacing = chosenFacing
                }
                showSetDefaultCameraDialog = false
                val isBn = LanguageManager.isBengali
                val label = CameraPreferenceManager.getCameraLabel(chosenFacing, isBn)
                Toast.makeText(
                    context,
                    if (isBn) "$label ডিফল্ট ক্যামেরা হিসেবে সেট করা হয়েছে" else "$label set as default scanner camera",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }
}

/**
 * Dialog to configure and persist the default camera (Back or Front) for barcode scanning.
 */
@Composable
fun DefaultCameraSelectionDialog(
    currentDefaultFacing: Int,
    hasBackCamera: Boolean,
    hasFrontCamera: Boolean,
    onDismiss: () -> Unit,
    onSaveDefault: (Int) -> Unit
) {
    var selectedFacing by remember { mutableIntStateOf(currentDefaultFacing) }
    val isBengali = LanguageManager.isBengali

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = StorePrimary.copy(alpha = 0.12f),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Cameraswitch,
                            contentDescription = null,
                            tint = StorePrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (isBengali) "ডিফল্ট ক্যামেরা নির্বাচন" else "Default Scanner Camera",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = if (isBengali)
                        "বারকোড স্ক্যানার চালু করলে কোন ক্যামেরাটি প্রথমে চালু হবে তা নির্বাচন করুন:"
                    else
                        "Choose which camera opens automatically whenever you scan barcodes in POS and Inventory:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                DefaultCameraOption.values().forEach { option ->
                    val isOptionAvailable = if (option == DefaultCameraOption.FRONT) hasFrontCamera else hasBackCamera
                    val isSelected = selectedFacing == option.facing

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = isOptionAvailable) {
                                selectedFacing = option.facing
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected)
                                StorePrimary.copy(alpha = 0.08f)
                            else
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        ),
                        border = BorderStroke(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = if (isSelected) StorePrimary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { selectedFacing = option.facing },
                                enabled = isOptionAvailable,
                                colors = RadioButtonDefaults.colors(selectedColor = StorePrimary)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isBengali) option.titleBn else option.titleEn,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) StorePrimary else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (isBengali) option.subtitleBn else option.subtitleEn,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (!isOptionAvailable) {
                                    Text(
                                        text = if (isBengali) "এই ডিভাইসে ক্যামেরাটি নেই" else "Not available on this device",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = StoreRedAlert,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSaveDefault(selectedFacing) },
                colors = ButtonDefaults.buttonColors(containerColor = StorePrimary),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(if (isBengali) "ডিফল্ট হিসেবে সংরক্ষণ করুন" else "Save as Default")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (isBengali) "বাতিল" else "Cancel")
            }
        }
    )
}

/**
 * Dialog to quick-search and assign a scanned barcode to an existing inventory item.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssignBarcodeDialog(
    barcodeToAssign: String,
    products: List<Product>,
    onDismiss: () -> Unit,
    onAssignProduct: (Product) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    val filtered = remember(products, searchQuery) {
        if (searchQuery.isBlank()) products else {
            products.filter { p ->
                p.nameEn.contains(searchQuery, ignoreCase = true) ||
                        p.nameBn.contains(searchQuery, ignoreCase = true) ||
                        p.category.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Assign Barcode",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Code: $barcodeToAssign",
                            style = MaterialTheme.typography.labelSmall,
                            color = StorePrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = null)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search product to assign code...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { it.id }) { product ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onAssignProduct(product) },
                            colors = CardDefaults.cardColors(containerColor = CardBackground),
                            border = BorderStroke(1.dp, StorePrimary.copy(alpha = 0.2f)),
                            shape = RoundedCornerShape(10.dp)
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
                                        text = product.getDisplayName(),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Category: ${product.category.ifBlank { "General" }} • Stock: ${product.getFormattedStockDisplay()}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMuted
                                    )
                                    if (!product.barcode.isNullOrBlank()) {
                                        Text(
                                            text = "Current Barcode: ${product.barcode}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = StoreOrangeWarning
                                        )
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = StorePrimary
                                ) {
                                    Text(
                                        text = "Assign",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
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

/**
 * Dedicated single barcode camera scanner for form input (e.g. Add Product form).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SingleBarcodeScannerDialog(
    onBarcodeCaptured: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    var isFlashOn by remember { mutableStateOf(false) }
    var isFlashSupported by remember { mutableStateOf(true) }
    val pmHasFrontCamera = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)
    }
    val defaultInitialFacing = remember {
        val saved = CameraPreferenceManager.getDefaultCamera(context)
        if (saved == CameraSelector.LENS_FACING_FRONT && !pmHasFrontCamera) {
            CameraSelector.LENS_FACING_BACK
        } else {
            saved
        }
    }
    var lensFacing by remember { mutableIntStateOf(defaultInitialFacing) }
    var hasBackCamera by remember { mutableStateOf(true) }
    var hasFrontCamera by remember { mutableStateOf(pmHasFrontCamera) }
    var showSetDefaultCameraDialog by remember { mutableStateOf(false) }
    var singlePreviewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var cameraRef by remember { mutableStateOf<Camera?>(null) }
    var singleCameraProviderRef by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val singleAnalysisExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            try {
                singleCameraProviderRef?.unbindAll()
                singleCameraProviderRef = null
                singleAnalysisExecutor.shutdown()
            } catch (e: Exception) {
                Log.e("SingleBarcodeScanner", "Error unbinding camera on dispose", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun playHapticFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                val vibrator = vibratorManager.defaultVibrator
                vibrator.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(120)
                }
            }
        } catch (e: Exception) {
            Log.e("SingleBarcodeScanner", "Haptic error", e)
        }
    }

    fun bindSingleCameraUseCases(
        provider: ProcessCameraProvider,
        pView: PreviewView,
        facing: Int
    ) {
        try {
            provider.unbindAll()

            val desiredSelector = CameraSelector.Builder().requireLensFacing(facing).build()
            val selector = if (provider.hasCamera(desiredSelector)) {
                desiredSelector
            } else if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                desiredSelector
            }

            val preview = Preview.Builder()
                .setTargetResolution(Size(1280, 720))
                .build().also {
                    it.setSurfaceProvider(pView.surfaceProvider)
                }
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(singleAnalysisExecutor, BarcodeAnalyzer { code ->
                playHapticFeedback()
                onBarcodeCaptured(code)
            })

            val cam = provider.bindToLifecycle(
                lifecycleOwner,
                selector,
                preview,
                imageAnalysis
            )
            cameraRef = cam
            val flashAvailable = cam.cameraInfo.hasFlashUnit()
            isFlashSupported = flashAvailable
            if (!flashAvailable) {
                isFlashOn = false
            } else if (isFlashOn) {
                cam.cameraControl.enableTorch(true)
            }
        } catch (e: Exception) {
            Log.e("SingleBarcodeScanner", "Camera binding failed for facing: $facing", e)
        }
    }

    LaunchedEffect(lensFacing, singlePreviewViewRef, singleCameraProviderRef, hasCameraPermission) {
        val provider = singleCameraProviderRef
        val pView = singlePreviewViewRef
        if (provider != null && pView != null && hasCameraPermission) {
            bindSingleCameraUseCases(provider, pView, lensFacing)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.75f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp)
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
                            shape = CircleShape,
                            color = StorePrimary.copy(alpha = 0.15f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.QrCodeScanner,
                                    contentDescription = null,
                                    tint = StorePrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Scan Product Barcode",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Camera Switch (Back/Front Camera) Toggle
                        if (hasFrontCamera) {
                            Surface(
                                shape = CircleShape,
                                color = if (lensFacing == CameraSelector.LENS_FACING_FRONT)
                                    StorePrimary.copy(alpha = 0.18f)
                                else
                                    SurfaceWarm,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .combinedClickable(
                                        onClick = {
                                            val provider = singleCameraProviderRef
                                            if (provider == null) {
                                                Toast.makeText(context, "Initializing camera...", Toast.LENGTH_SHORT).show()
                                                return@combinedClickable
                                            }
                                            if (!hasFrontCamera && !hasBackCamera) {
                                                Toast.makeText(context, "Camera switching not supported on this device", Toast.LENGTH_SHORT).show()
                                                return@combinedClickable
                                            }
                                            if (lensFacing == CameraSelector.LENS_FACING_BACK && !hasFrontCamera) {
                                                Toast.makeText(context, "Front camera not found on this device", Toast.LENGTH_SHORT).show()
                                                return@combinedClickable
                                            }
                                            if (lensFacing == CameraSelector.LENS_FACING_FRONT && !hasBackCamera) {
                                                Toast.makeText(context, "Back camera not found on this device", Toast.LENGTH_SHORT).show()
                                                return@combinedClickable
                                            }

                                            playHapticFeedback()

                                            // Turn off flashlight before switching cameras
                                            if (isFlashOn) {
                                                try {
                                                    cameraRef?.cameraControl?.enableTorch(false)
                                                } catch (_: Exception) {}
                                                isFlashOn = false
                                            }

                                            val newFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                                                CameraSelector.LENS_FACING_FRONT
                                            } else {
                                                CameraSelector.LENS_FACING_BACK
                                            }
                                            lensFacing = newFacing
                                            CameraPreferenceManager.setDefaultCamera(context, newFacing)
                                            val isBn = LanguageManager.isBengali
                                            val toastMsg = if (newFacing == CameraSelector.LENS_FACING_FRONT) {
                                                if (isBn) "সামনের ক্যামেরা সক্রিয় • ডিফল্ট হিসেবে সংরক্ষিত" else "Front camera active • Saved as default"
                                            } else {
                                                if (isBn) "পিছনের ক্যামেরা সক্রিয় • ডিফল্ট হিসেবে সংরক্ষিত" else "Back camera active • Saved as default"
                                            }
                                            Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
                                        },
                                        onLongClick = {
                                            playHapticFeedback()
                                            showSetDefaultCameraDialog = true
                                        }
                                    )
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Cameraswitch,
                                        contentDescription = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                                            "Switch to Front Camera (Hold to configure default)"
                                        else
                                            "Switch to Back Camera (Hold to configure default)",
                                        tint = if (lensFacing == CameraSelector.LENS_FACING_FRONT) StorePrimary else TextDark,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        // Flashlight Toggle
                        Surface(
                            onClick = {
                                if (!isFlashSupported) {
                                    Toast.makeText(
                                        context,
                                        "Flashlight not supported on ${if (lensFacing == CameraSelector.LENS_FACING_FRONT) "front" else "this"} camera",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    return@Surface
                                }
                                isFlashOn = !isFlashOn
                                cameraRef?.cameraControl?.enableTorch(isFlashOn)
                            },
                            shape = CircleShape,
                            color = if (isFlashOn) StoreSaffronAccent.copy(alpha = 0.2f)
                            else if (!isFlashSupported) SurfaceWarm.copy(alpha = 0.4f)
                            else SurfaceWarm,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isFlashOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                                    contentDescription = "Flashlight",
                                    tint = if (isFlashOn) StoreSaffronAccent else if (!isFlashSupported) TextMuted else TextDark,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // Close Button
                        Surface(
                            onClick = onDismiss,
                            shape = CircleShape,
                            color = Color.Transparent,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = TextDark,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (!hasCameraPermission) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(20.dp)
                        ) {
                            Text(
                                text = "Camera permission needed to scan barcodes.",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                                Text("Grant Permission")
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Black)
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                val previewView = PreviewView(ctx).apply {
                                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                    scaleType = PreviewView.ScaleType.FILL_CENTER
                                }

                                previewView.setOnTouchListener { view, event ->
                                    if (event.action == MotionEvent.ACTION_UP) {
                                        try {
                                            val factory = previewView.meteringPointFactory
                                            val point = factory.createPoint(event.x, event.y)
                                            val action = FocusMeteringAction.Builder(
                                                point,
                                                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                                            ).setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS).build()
                                            cameraRef?.cameraControl?.startFocusAndMetering(action)
                                        } catch (e: Exception) {
                                            Log.w("SingleBarcodeScanner", "Focus metering error: ${e.message}")
                                        }
                                        view.performClick()
                                    }
                                    true
                                }

                                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                                val mainExecutor = ContextCompat.getMainExecutor(ctx)

                                cameraProviderFuture.addListener({
                                    val cameraProvider = cameraProviderFuture.get()
                                    singleCameraProviderRef = cameraProvider
                                    hasBackCamera = try { cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) } catch (_: Exception) { false }
                                    hasFrontCamera = try { cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) } catch (_: Exception) { false }
                                    singlePreviewViewRef = previewView
                                    bindSingleCameraUseCases(cameraProvider, previewView, lensFacing)
                                }, mainExecutor)

                                previewView
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        // Active Front Camera Badge
                        if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                            val isDefault = CameraPreferenceManager.defaultLensFacing == CameraSelector.LENS_FACING_FRONT
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 12.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { showSetDefaultCameraDialog = true },
                                shape = RoundedCornerShape(16.dp),
                                color = StorePrimary.copy(alpha = 0.88f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Cameraswitch,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = if (isDefault) "Front Camera (Default)" else "Front Camera • Tap to configure",
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        // Reticle Overlay
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(width = 260.dp, height = 160.dp)
                                    .border(2.dp, StorePrimary, RoundedCornerShape(12.dp))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .fillMaxWidth(0.9f)
                                        .height(2.dp)
                                        .background(StoreRedAlert)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSecondaryContainer, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    // Modal to configure default camera lens facing (Back/Front)
    if (showSetDefaultCameraDialog) {
        DefaultCameraSelectionDialog(
            currentDefaultFacing = CameraPreferenceManager.getDefaultCamera(context),
            hasBackCamera = hasBackCamera,
            hasFrontCamera = hasFrontCamera,
            onDismiss = { showSetDefaultCameraDialog = false },
            onSaveDefault = { chosenFacing ->
                CameraPreferenceManager.setDefaultCamera(context, chosenFacing)
                if (lensFacing != chosenFacing) {
                    if (isFlashOn) {
                        try {
                            cameraRef?.cameraControl?.enableTorch(false)
                        } catch (_: Exception) {}
                        isFlashOn = false
                    }
                    lensFacing = chosenFacing
                }
                showSetDefaultCameraDialog = false
                val isBn = LanguageManager.isBengali
                val label = CameraPreferenceManager.getCameraLabel(chosenFacing, isBn)
                Toast.makeText(
                    context,
                    if (isBn) "$label ডিফল্ট ক্যামেরা হিসেবে সেট করা হয়েছে" else "$label set as default scanner camera",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }
}

