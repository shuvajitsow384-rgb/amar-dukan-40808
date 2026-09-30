package com.example.ui.components

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import com.example.utils.LanguageManager
import com.example.utils.PdfReceiptHelper
import com.example.utils.StoreInfoManager

/**
 * Reusable modal dialog to view, download, and share the Online Store QR code and link.
 */
@Composable
fun StoreQrModalDialog(
    context: Context,
    storeUrl: String = "https://amar-dukan-40808.web.app/shop/",
    storeName: String = StoreInfoManager.storeName,
    onDismiss: () -> Unit,
    onShareLink: () -> Unit
) {
    val isBn = LanguageManager.isBengali

    // Reuse the existing QR generation pipeline (PdfReceiptHelper)
    val qrBitmap = remember(storeUrl) {
        PdfReceiptHelper.generateQrCodeBitmap(storeUrl, 512, 512)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.QrCode2,
                    contentDescription = null,
                    tint = Color(0xFF0284C7),
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = if (isBn) "অনলাইন স্টোর কিউআর কোড" else "Online Store QR Code",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextDark
                    )
                    Text(
                        text = if (storeName.isNotBlank()) storeName else "Amar Dukan",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // QR Code Container
                if (qrBitmap != null) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.5.dp, Color(0xFF0284C7).copy(alpha = 0.25f)),
                        color = Color.White,
                        shadowElevation = 2.dp,
                        modifier = Modifier.size(230.dp)
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Online Store QR Code",
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(230.dp)
                            .background(Color.LightGray.copy(alpha = 0.2f), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp), color = Color(0xFF0284C7))
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Store URL box with quick copy
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFF1F5F9),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = storeUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF0284C7),
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("Online Store Link", storeUrl)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(
                                    context,
                                    if (isBn) "লিঙ্ক কপি করা হয়েছে!" else "Store link copied!",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "Copy Link",
                                modifier = Modifier.size(16.dp),
                                tint = Color(0xFF0284C7)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = if (isBn)
                        "গ্রাহকরা দোকানে এই কিউআর কোড স্ক্যান করে অনলাইন ক্যাটালগ ও মেনু দেখতে পারবেন এবং সরাসরি অর্ডার দিতে পারবেন।"
                    else
                        "Print or share this QR code so customers can scan to browse your store catalog and place orders directly.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    textAlign = TextAlign.Center,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp
                )
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Save/Download Button
                    OutlinedButton(
                        onClick = {
                            if (qrBitmap != null) {
                                saveQrBitmapToGallery(
                                    context = context,
                                    bitmap = qrBitmap,
                                    fileName = "Store_QR_${storeName.replace(Regex("[^a-zA-Z0-9]"), "_")}"
                                )
                            } else {
                                Toast.makeText(context, "QR code not ready", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isBn) "সেভ করুন" else "Save QR",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Share Link Button (opens Android share sheet)
                    Button(
                        onClick = onShareLink,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isBn) "শেয়ার করুন" else "Share Link",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = if (isBn) "বন্ধ করুন" else "Close")
            }
        }
    )
}

/**
 * Saves the given QR code bitmap to the device's Pictures/StoreQR directory using MediaStore.
 */
fun saveQrBitmapToGallery(context: Context, bitmap: Bitmap, fileName: String): Boolean {
    val isBn = LanguageManager.isBengali
    return try {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$fileName.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/StoreQR")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
            resolver.openOutputStream(uri)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
            Toast.makeText(
                context,
                if (isBn) "কিউআর কোড গ্যালারিতে (Pictures/StoreQR) সংরক্ষিত হয়েছে!" else "QR code saved to Pictures/StoreQR!",
                Toast.LENGTH_LONG
            ).show()
            true
        } else {
            Toast.makeText(context, if (isBn) "সংরক্ষণ করা যায়নি" else "Failed to save QR code", Toast.LENGTH_SHORT).show()
            false
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Error saving QR: ${e.message}", Toast.LENGTH_SHORT).show()
        false
    }
}
