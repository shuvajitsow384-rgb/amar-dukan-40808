package com.example.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * High-resolution camera capture utility using Android FileProvider & ActivityResultContracts.TakePicture.
 * Replaces low-resolution TakePicturePreview() thumbnails with full camera sensor resolution photos.
 */
object CameraCaptureHelper {

    private const val PHOTO_DIR_NAME = "camera_photos"

    /**
     * Creates a temporary file in the app's cache directory and returns a content Uri via FileProvider.
     */
    fun createTempImageFileUri(context: Context): Pair<Uri, File>? {
        return try {
            val photoDir = File(context.cacheDir, PHOTO_DIR_NAME).apply {
                if (!exists()) mkdirs()
            }
            val tempFile = File.createTempFile("photo_${System.currentTimeMillis()}_", ".jpg", photoDir)
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, tempFile)
            Pair(uri, tempFile)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

/**
 * Composable hook that launches the device camera at FULL resolution, saves the captured photo,
 * compresses it using high-definition parameters via ImageSyncHelper, and invokes onImageCaptured
 * with the resulting syncable Base64 Data URL.
 */
@Composable
fun rememberHighResCameraCapture(
    maxDimension: Int = 1080,
    quality: Int = 84,
    onImageCaptured: (dataUrl: String) -> Unit
): () -> Unit {
    val context = LocalContext.current
    var currentTempFile by remember { mutableStateOf<File?>(null) }
    var currentUri by remember { mutableStateOf<Uri?>(null) }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val fileToProcess = currentTempFile
        val uriToProcess = currentUri
        currentTempFile = null
        currentUri = null

        if (success && uriToProcess != null) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val dataUrl = ImageSyncHelper.compressUriToDataUrl(
                        context = context,
                        uri = uriToProcess,
                        maxDim = maxDimension,
                        quality = quality
                    )
                    withContext(Dispatchers.Main) {
                        if (!dataUrl.isNullOrBlank()) {
                            onImageCaptured(dataUrl)
                        } else {
                            Toast.makeText(
                                context,
                                LanguageManager.getString("Failed to process photo", "ছবি প্রক্রিয়াকরণ ব্যর্থ হয়েছে"),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    try {
                        fileToProcess?.delete()
                    } catch (_: Exception) {}
                }
            }
        } else {
            try {
                fileToProcess?.delete()
            } catch (_: Exception) {}
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            try {
                val pair = CameraCaptureHelper.createTempImageFileUri(context)
                if (pair != null) {
                    currentUri = pair.first
                    currentTempFile = pair.second
                    takePictureLauncher.launch(pair.first)
                } else {
                    Toast.makeText(context, "Storage error creating photo file", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Could not open camera: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(
                context,
                LanguageManager.getString(
                    "Camera permission required to capture high-quality photo",
                    "উচ্চ মানের ছবি তুলতে ক্যামেরা পারমিশন প্রয়োজন"
                ),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            try {
                val pair = CameraCaptureHelper.createTempImageFileUri(context)
                if (pair != null) {
                    currentUri = pair.first
                    currentTempFile = pair.second
                    takePictureLauncher.launch(pair.first)
                } else {
                    Toast.makeText(context, "Storage error creating photo file", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Could not launch camera: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
}
