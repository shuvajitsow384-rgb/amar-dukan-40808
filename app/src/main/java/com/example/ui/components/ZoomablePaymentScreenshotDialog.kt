package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.ui.theme.StoreGold
import com.example.ui.theme.StorePrimary
import com.example.utils.LanguageManager

@Composable
fun ZoomablePaymentScreenshotDialog(
    bitmap: Bitmap? = null,
    imageUrl: String? = null,
    base64Data: String? = null,
    imageModel: Any? = null,
    title: String = LanguageManager.getString("Payment Screenshot", "পেমেন্ট স্ক্রিনশট"),
    subtitle: String? = null,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isBn = LanguageManager.isBengali
    var isEnhancedClarity by remember { mutableStateOf(false) }

    val isUriOrUrl = remember(base64Data) {
        base64Data?.let {
            val str = it.trim()
            str.startsWith("http://") || str.startsWith("https://") ||
                    str.startsWith("content://") || str.startsWith("file://") ||
                    str.startsWith("/")
        } ?: false
    }

    val baseDecodedBitmap by produceState<Bitmap?>(initialValue = bitmap, bitmap, base64Data, imageModel, imageUrl) {
        value = if (bitmap != null) {
            bitmap
        } else {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    when {
                        imageModel is ByteArray -> {
                            BitmapFactory.decodeByteArray(imageModel, 0, imageModel.size)
                        }
                        imageModel is Bitmap -> {
                            imageModel
                        }
                        imageModel is String -> {
                            com.example.utils.ImageSyncHelper.decodeImageUriToBitmap(context, imageModel)
                        }
                        !base64Data.isNullOrBlank() && !isUriOrUrl -> {
                            val cleanBase64 = if (base64Data.contains(",")) base64Data.substringAfter(",") else base64Data
                            val clean = cleanBase64.replace("\n", "").replace("\r", "").replace(" ", "").trim()
                            val bytes = Base64.decode(clean, Base64.DEFAULT)
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        }
                        !imageUrl.isNullOrBlank() -> {
                            com.example.utils.ImageSyncHelper.decodeImageUriToBitmap(context, imageUrl)
                        }
                        !base64Data.isNullOrBlank() && isUriOrUrl -> {
                            com.example.utils.ImageSyncHelper.decodeImageUriToBitmap(context, base64Data)
                        }
                        else -> null
                    }
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    val displayBitmap by produceState<Bitmap?>(initialValue = baseDecodedBitmap, baseDecodedBitmap, isEnhancedClarity) {
        value = if (baseDecodedBitmap != null && isEnhancedClarity) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                com.example.utils.ImageSyncHelper.enhanceBitmapClarity(baseDecodedBitmap!!, sharpenFactor = 0.55f, contrastFactor = 1.15f)
            }
        } else {
            baseDecodedBitmap
        }
    }

    val resolvedImageUrl = remember(imageUrl, base64Data, imageModel) {
        if (imageModel != null) imageModel
        else if (!imageUrl.isNullOrBlank()) com.example.utils.ImageSyncHelper.getImageModel(imageUrl)
        else if (!base64Data.isNullOrBlank() && isUriOrUrl) com.example.utils.ImageSyncHelper.getImageModel(base64Data)
        else null
    }

    // Zoom & Pan state
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var rotationDegrees by remember { mutableFloatStateOf(0f) }

    fun resetTransform() {
        scale = 1f
        offset = Offset.Zero
        rotationDegrees = 0f
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.96f))
                .systemBarsPadding()
        ) {
            // Main Viewport
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds(),
                contentAlignment = Alignment.Center
            ) {
                val containerWidth = constraints.maxWidth.toFloat()
                val containerHeight = constraints.maxHeight.toFloat()

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val newScale = (scale * zoom).coerceIn(1f, 7f)
                                scale = newScale
                                if (newScale > 1f) {
                                    val maxOffsetX = (containerWidth * (newScale - 1f)) / 2f + 120f
                                    val maxOffsetY = (containerHeight * (newScale - 1f)) / 2f + 120f
                                    offset = Offset(
                                        x = (offset.x + pan.x).coerceIn(-maxOffsetX, maxOffsetX),
                                        y = (offset.y + pan.y).coerceIn(-maxOffsetY, maxOffsetY)
                                    )
                                } else {
                                    offset = Offset.Zero
                                }
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = {
                                    if (scale > 1.2f) {
                                        scale = 1f
                                        offset = Offset.Zero
                                    } else {
                                        scale = 2.5f
                                        offset = Offset.Zero
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val imageModifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp, vertical = 64.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                            rotationZ = rotationDegrees
                        }

                    val currentBmp = displayBitmap
                    if (currentBmp != null) {
                        Image(
                            bitmap = currentBmp.asImageBitmap(),
                            contentDescription = "Zoomable Photo",
                            contentScale = ContentScale.Fit,
                            filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                            modifier = imageModifier
                        )
                    } else if (resolvedImageUrl != null) {
                        AsyncImage(
                            model = resolvedImageUrl,
                            contentDescription = "Zoomable Photo",
                            contentScale = ContentScale.Fit,
                            filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                            modifier = imageModifier
                        )
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.BrokenImage,
                                contentDescription = null,
                                tint = Color.Gray,
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = if (isBn) "ছবি লোড করা যায়নি" else "Image unavailable",
                                color = Color.White,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            // Top Header Bar
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter),
                color = Color.Black.copy(alpha = 0.65f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.ReceiptLong,
                                contentDescription = null,
                                tint = StoreGold,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = title,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            // Zoom Level Badge
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (scale > 1.05f) StoreGold.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, if (scale > 1.05f) StoreGold else Color.White.copy(alpha = 0.3f))
                            ) {
                                Text(
                                    text = "${(scale * 100).toInt()}%",
                                    color = if (scale > 1.05f) StoreGold else Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                )
                            }
                            // HD Clarity Enhancement Toggle
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isEnhancedClarity) StoreGold.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, if (isEnhancedClarity) StoreGold else Color.White.copy(alpha = 0.3f)),
                                modifier = Modifier.clickable { isEnhancedClarity = !isEnhancedClarity }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Icon(
                                        Icons.Default.AutoFixHigh,
                                        contentDescription = null,
                                        tint = if (isEnhancedClarity) StoreGold else Color.White,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (isEnhancedClarity) {
                                            if (isBn) "HD শার্প চালু" else "HD Sharp ON"
                                        } else {
                                            if (isBn) "HD শার্প" else "HD Clarity"
                                        },
                                        color = if (isEnhancedClarity) StoreGold else Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        if (!subtitle.isNullOrBlank()) {
                            Text(
                                text = subtitle,
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 12.sp
                            )
                        }
                    }

                    // Close Button
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color.White.copy(alpha = 0.18f), CircleShape)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Bottom Controls Bar (Zoom In, Zoom Out, Reset / Fit, Rotate)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
                color = Color.Black.copy(alpha = 0.75f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Control Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Zoom Out
                        OutlinedButton(
                            onClick = {
                                val nextScale = (scale - 0.5f).coerceAtLeast(1f)
                                scale = nextScale
                                if (nextScale <= 1f) {
                                    offset = Offset.Zero
                                }
                            },
                            enabled = scale > 1f,
                            shape = CircleShape,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color.White,
                                disabledContentColor = Color.White.copy(alpha = 0.3f)
                            ),
                            border = BorderStroke(1.dp, if (scale > 1f) Color.White.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.15f)),
                            modifier = Modifier.size(42.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(Icons.Default.ZoomOut, contentDescription = "Zoom Out", modifier = Modifier.size(20.dp))
                        }

                        // Fit / Reset (1x)
                        Button(
                            onClick = { resetTransform() },
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (scale > 1.05f || rotationDegrees != 0f) StorePrimary else Color.White.copy(alpha = 0.18f)
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.FitScreen, contentDescription = "Reset Zoom", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isBn) "রিসেট (1x)" else "Fit (1x)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        // Zoom In
                        OutlinedButton(
                            onClick = {
                                scale = (scale + 0.5f).coerceAtMost(7f)
                            },
                            enabled = scale < 7f,
                            shape = CircleShape,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color.White,
                                disabledContentColor = Color.White.copy(alpha = 0.3f)
                            ),
                            border = BorderStroke(1.dp, if (scale < 7f) Color.White.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.15f)),
                            modifier = Modifier.size(42.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(Icons.Default.ZoomIn, contentDescription = "Zoom In", modifier = Modifier.size(20.dp))
                        }

                        // Rotate 90° Clockwise
                        OutlinedButton(
                            onClick = {
                                rotationDegrees = (rotationDegrees + 90f) % 360f
                            },
                            shape = CircleShape,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
                            modifier = Modifier.size(42.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(Icons.Default.RotateRight, contentDescription = "Rotate 90°", modifier = Modifier.size(20.dp))
                        }
                    }

                    // Touch gesture guidance text
                    Text(
                        text = if (isBn) "💡 পিঞ্চ বা ডাবল ট্যাপ করে জুম করুন • ড্র্যাগ করে স্ক্রোল করুন" else "💡 Pinch or double tap to zoom • Drag to pan across details",
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
