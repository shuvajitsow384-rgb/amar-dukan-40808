package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.ui.theme.StorePrimary
import com.example.ui.theme.TextDark
import com.example.utils.ImageSyncHelper
import com.example.utils.LanguageManager

@Composable
fun PaymentScreenshotViewer(
    screenshotData: String?,
    modifier: Modifier = Modifier,
    cardHeight: Dp = 150.dp
) {
    if (screenshotData.isNullOrBlank()) return

    var showFullScreenImage by remember { mutableStateOf(false) }

    val isUriOrUrl = remember(screenshotData) {
        val str = screenshotData.trim()
        str.startsWith("http://") || str.startsWith("https://") ||
                str.startsWith("content://") || str.startsWith("file://") ||
                str.startsWith("/")
    }

    val screenshotBitmap by produceState<Bitmap?>(initialValue = null, screenshotData) {
        value = if (!screenshotData.isNullOrBlank() && !isUriOrUrl) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val cleanBase64 = if (screenshotData.contains(",")) screenshotData.substringAfter(",") else screenshotData
                    val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (_: Exception) {
                    null
                }
            }
        } else {
            null
        }
    }

    val isBn = LanguageManager.isBengali

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFFF8FAFC),
        border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
        modifier = modifier
            .fillMaxWidth()
            .clickable { showFullScreenImage = true }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = null,
                        tint = StorePrimary,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = if (isBn) "পেমেন্ট স্ক্রিনশট" else "Payment Screenshot",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextDark,
                        fontSize = 11.sp
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ZoomIn,
                        contentDescription = null,
                        tint = StorePrimary,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = if (isBn) "বড় করে দেখতে চাপুন" else "Tap to view full-screen",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = StorePrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(cardHeight)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                val currentBmp = screenshotBitmap
                if (currentBmp != null) {
                    Image(
                        bitmap = currentBmp.asImageBitmap(),
                        contentDescription = "Payment Screenshot",
                        contentScale = ContentScale.Fit,
                        filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    AsyncImage(
                        model = ImageSyncHelper.getImageModel(screenshotData),
                        contentDescription = "Payment Screenshot",
                        contentScale = ContentScale.Fit,
                        filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    if (showFullScreenImage) {
        ZoomablePaymentScreenshotDialog(
            bitmap = screenshotBitmap,
            base64Data = if (!isUriOrUrl) screenshotData else null,
            imageModel = if (isUriOrUrl) ImageSyncHelper.getImageModel(screenshotData) else null,
            title = if (isBn) "পেমেন্ট স্ক্রিনশট" else "Payment Screenshot",
            onDismiss = { showFullScreenImage = false }
        )
    }
}
