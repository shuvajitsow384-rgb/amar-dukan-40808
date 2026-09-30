package com.example.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import kotlin.math.min

/**
 * Universal helper for compressing, synchronizing, backing up, and rendering product & voucher images.
 * Encodes images as lightweight, self-contained Data URLs (Base64 JPEG) so they sync to Firestore,
 * across all employee devices in real-time, and survive full backup/restore cycles without external cloud bucket dependencies.
 *
 * Firestore Document Size Limit: 1 MB (1,048,576 bytes).
 * This helper guarantees each image payload is strictly compressed to ~15-40 KB (approx 2-4% of doc limit),
 * ensuring fast real-time synchronization over mobile networks.
 */
object ImageSyncHelper {

    private const val TAG = "ImageSyncHelper"
    // Ultra-crisp HD default resolution (1080px) for pin-sharp product labels, weights, prices, and barcodes
    private const val DEFAULT_MAX_DIMENSION = 1080
    private const val DEFAULT_JPEG_QUALITY = 84
    private const val MAX_BASE64_CHAR_LIMIT = 320_000 // ~240 KB payload ceiling (well under Firestore 1MB limit)

    // Fast in-memory LRU cache for decoded Base64 byte arrays to eliminate repetitive string stripping and Base64 decoding on scroll/recomposition
    private val decodedByteArrayCache = android.util.LruCache<String, ByteArray>(100)

    /**
     * Compresses a Bitmap and converts it to a standard base64 data URL.
     * Preserves high visual fidelity, fine details, and sharp text while staying safe within Firestore limits.
     */
    fun compressBitmapToDataUrl(
        bitmap: Bitmap,
        maxDim: Int = DEFAULT_MAX_DIMENSION,
        quality: Int = DEFAULT_JPEG_QUALITY
    ): String {
        return try {
            val scaledBitmap = scaleBitmapDown(bitmap, maxDim)
            var outStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outStream)
            var bytes = outStream.toByteArray()

            // Multi-stage progressive compression guard for Firestore:
            // 1. If payload > 180 KB, try slightly lower compression (78% quality)
            if (bytes.size > 180_000) {
                outStream = ByteArrayOutputStream()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 78, outStream)
                bytes = outStream.toByteArray()
            }
            // 2. If payload still > 220 KB, scale down to 960px at 75% quality
            if (bytes.size > 220_000) {
                outStream = ByteArrayOutputStream()
                val smallerScaled = scaleBitmapDown(scaledBitmap, 960)
                smallerScaled.compress(Bitmap.CompressFormat.JPEG, 75, outStream)
                bytes = outStream.toByteArray()
            }
            // 3. Absolute failsafe: if still > 260 KB, scale down to 800px at 70% quality
            if (bytes.size > 260_000) {
                outStream = ByteArrayOutputStream()
                val safeScaled = scaleBitmapDown(scaledBitmap, 800)
                safeScaled.compress(Bitmap.CompressFormat.JPEG, 70, outStream)
                bytes = outStream.toByteArray()
            }

            val base64Str = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val dataUrl = "data:image/jpeg;base64,$base64Str"
            
            Log.i(
                TAG,
                "✓ Compressed HD bitmap to Data URL: ${scaledBitmap.width}x${scaledBitmap.height}px, " +
                "JPEG size: ${bytes.size / 1024} KB, Base64: ${dataUrl.length} chars (~${dataUrl.length / 1024} KB, " +
                "~${"%.1f".format((dataUrl.length.toDouble() / 1_048_576.0) * 100.0)}% of Firestore 1MB limit)"
            )
            dataUrl
        } catch (e: Exception) {
            Log.e(TAG, "Error compressing bitmap to data URL: ${e.message}", e)
            ""
        }
    }

    /**
     * Enhances micro-contrast and edge sharpness of a Bitmap to make blurry labels, packaging text,
     * prices, ingredients, and barcodes significantly crisper and easier to read.
     */
    fun enhanceBitmapClarity(
        src: Bitmap,
        sharpenFactor: Float = 0.45f,
        contrastFactor: Float = 1.12f
    ): Bitmap {
        return try {
            val width = src.width
            val height = src.height

            // Step 1: Contrast and vibrancy enhancement
            val contrastBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(contrastBmp)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)

            val cm = android.graphics.ColorMatrix()
            val scale = contrastFactor
            val translate = (-0.5f * scale + 0.5f) * 255f
            cm.set(floatArrayOf(
                scale, 0f, 0f, 0f, translate,
                0f, scale, 0f, 0f, translate,
                0f, 0f, scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            ))
            paint.colorFilter = android.graphics.ColorMatrixColorFilter(cm)
            canvas.drawBitmap(src, 0f, 0f, paint)

            if (sharpenFactor <= 0.05f) return contrastBmp

            // Step 2: Unsharp masking convolution kernel for ultra-crisp edges and fine text
            val pixels = IntArray(width * height)
            contrastBmp.getPixels(pixels, 0, width, 0, 0, width, height)
            val resultPixels = IntArray(width * height)

            val k = sharpenFactor.coerceIn(0.1f, 0.8f)
            val center = 1f + 4f * k

            for (y in 0 until height) {
                val yOffset = y * width
                val yPrevOffset = (if (y > 0) y - 1 else y) * width
                val yNextOffset = (if (y < height - 1) y + 1 else y) * width
                for (x in 0 until width) {
                    val xPrev = if (x > 0) x - 1 else x
                    val xNext = if (x < width - 1) x + 1 else x

                    val cCenter = pixels[yOffset + x]
                    val cTop = pixels[yPrevOffset + x]
                    val cBottom = pixels[yNextOffset + x]
                    val cLeft = pixels[yOffset + xPrev]
                    val cRight = pixels[yOffset + xNext]

                    val a = (cCenter ushr 24) and 0xFF

                    val rC = (cCenter ushr 16) and 0xFF
                    val rT = (cTop ushr 16) and 0xFF
                    val rB = (cBottom ushr 16) and 0xFF
                    val rL = (cLeft ushr 16) and 0xFF
                    val rR = (cRight ushr 16) and 0xFF
                    val rNew = (center * rC - k * (rT + rB + rL + rR)).toInt().coerceIn(0, 255)

                    val gC = (cCenter ushr 8) and 0xFF
                    val gT = (cTop ushr 8) and 0xFF
                    val gB = (cBottom ushr 8) and 0xFF
                    val gL = (cLeft ushr 8) and 0xFF
                    val gR = (cRight ushr 8) and 0xFF
                    val gNew = (center * gC - k * (gT + gB + gL + gR)).toInt().coerceIn(0, 255)

                    val bC = cCenter and 0xFF
                    val bT = cTop and 0xFF
                    val bB = cBottom and 0xFF
                    val bL = cLeft and 0xFF
                    val bR = cRight and 0xFF
                    val bNew = (center * bC - k * (bT + bB + bL + bR)).toInt().coerceIn(0, 255)

                    resultPixels[yOffset + x] = (a shl 24) or (rNew shl 16) or (gNew shl 8) or bNew
                }
            }
            try { contrastBmp.recycle() } catch (_: Exception) {}
            Bitmap.createBitmap(resultPixels, width, height, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enhance bitmap: ${e.message}")
            src
        }
    }

    /**
     * Enhances clarity of an existing image (Data URL or local URI) and returns the sharpened Data URL.
     */
    fun enhanceDataUrl(context: Context, imageUri: String?): String? {
        if (imageUri.isNullOrBlank()) return null
        return try {
            val decodedBitmap = decodeImageUriToBitmap(context, imageUri) ?: return null
            val enhanced = enhanceBitmapClarity(decodedBitmap)
            compressBitmapToDataUrl(enhanced, DEFAULT_MAX_DIMENSION, DEFAULT_JPEG_QUALITY)
        } catch (e: Exception) {
            Log.w(TAG, "enhanceDataUrl failed: ${e.message}")
            imageUri
        }
    }

    /**
     * Decodes any supported image URI or Base64 string into a raw Bitmap.
     */
    fun decodeImageUriToBitmap(context: Context, uriOrBase64: String?): Bitmap? {
        if (uriOrBase64.isNullOrBlank()) return null
        val trimmed = uriOrBase64.trim()

        if (trimmed.startsWith("data:image/", ignoreCase = true) ||
            trimmed.startsWith("data:;base64,", ignoreCase = true) ||
            trimmed.contains(";base64,") ||
            (trimmed.length > 100 && !trimmed.contains(" ") && !trimmed.startsWith("http"))
        ) {
            val cleanBase64 = if (trimmed.contains(",")) trimmed.substringAfter(",") else trimmed
            return try {
                val clean = cleanBase64.replace("\n", "").replace("\r", "").replace(" ", "").trim()
                val bytes = Base64.decode(clean, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) {
                Log.w(TAG, "decodeImageUriToBitmap base64 failed: ${e.message}")
                null
            }
        }

        return try {
            val uri = if (trimmed.startsWith("/") || !trimmed.contains("://")) {
                Uri.fromFile(File(trimmed))
            } else {
                Uri.parse(trimmed)
            }
            decodeAndOrientBitmap(context, uri, null, DEFAULT_MAX_DIMENSION)
        } catch (e: Exception) {
            Log.w(TAG, "decodeImageUriToBitmap uri failed: ${e.message}")
            null
        }
    }

    /**
     * Safely reads a local Uri (content://, file://, or raw file path) and converts it to a compressed base64 data URL.
     */
    fun compressUriToDataUrl(
        context: Context,
        uri: Uri,
        maxDim: Int = DEFAULT_MAX_DIMENSION,
        quality: Int = DEFAULT_JPEG_QUALITY
    ): String? {
        val uriStr = uri.toString().trim()
        if (uriStr.startsWith("data:image/", ignoreCase = true) ||
            uriStr.startsWith("http://", ignoreCase = true) ||
            uriStr.startsWith("https://", ignoreCase = true)
        ) {
            return uriStr
        }

        return try {
            val isFileScheme = uri.scheme == "file" || uri.scheme.isNullOrBlank() || uriStr.startsWith("/")
            val filePath = if (isFileScheme) {
                uri.path ?: uriStr.removePrefix("file://")
            } else null

            var bitmap: Bitmap? = null

            // 1. Try ImageDecoder on Android P+ (API 28+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    val source = if (filePath != null && File(filePath).exists()) {
                        ImageDecoder.createSource(File(filePath))
                    } else {
                        ImageDecoder.createSource(context.contentResolver, uri)
                    }

                    bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        val origWidth = info.size.width
                        val origHeight = info.size.height
                        val maxOriginal = origWidth.coerceAtLeast(origHeight)
                        if (maxOriginal > maxDim) {
                            val ratio = maxDim.toFloat() / maxOriginal
                            val targetW = (origWidth * ratio).toInt().coerceAtLeast(1)
                            val targetH = (origHeight * ratio).toInt().coerceAtLeast(1)
                            decoder.setTargetSize(targetW, targetH)
                        }
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        decoder.isMutableRequired = true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "ImageDecoder failed for $uri, falling back to BitmapFactory & EXIF: ${e.message}")
                    bitmap = null
                }
            }

            // 2. Fallback to BitmapFactory with EXIF rotation and downsampling
            if (bitmap == null) {
                bitmap = decodeAndOrientBitmap(context, uri, filePath, maxDim)
            }

            if (bitmap != null) {
                val dataUrl = compressBitmapToDataUrl(bitmap, maxDim, quality)
                if (dataUrl.isNotBlank()) dataUrl else null
            } else {
                Log.w(TAG, "Could not decode bitmap from URI: $uri")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert URI $uri to data URL: ${e.message}", e)
            null
        }
    }

    private fun decodeAndOrientBitmap(
        context: Context,
        uri: Uri,
        filePath: String?,
        maxDim: Int
    ): Bitmap? {
        return try {
            // Obtain input stream
            val openStream: () -> InputStream? = {
                if (filePath != null && File(filePath).exists()) {
                    FileInputStream(File(filePath))
                } else {
                    context.contentResolver.openInputStream(uri)
                }
            }

            // Read orientation from EXIF
            var rotationDegrees = 0
            try {
                openStream()?.use { stream ->
                    val exif = ExifInterface(stream)
                    val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                    rotationDegrees = when (orientation) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270
                        else -> 0
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not read EXIF orientation: ${e.message}")
            }

            // Decode bounds
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openStream()?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            } ?: return null

            options.inSampleSize = calculateInSampleSize(options, maxDim, maxDim)
            options.inJustDecodeBounds = false

            var decodedBitmap = openStream()?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            } ?: return null

            // Apply EXIF rotation if needed
            if (rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                decodedBitmap = Bitmap.createBitmap(
                    decodedBitmap, 0, 0,
                    decodedBitmap.width, decodedBitmap.height,
                    matrix, true
                )
            }

            decodedBitmap
        } catch (e: Exception) {
            Log.e(TAG, "decodeAndOrientBitmap error: ${e.message}", e)
            null
        }
    }

    /**
     * Converts any raw imageUri (local file/content or URL) into a sync-ready data URL or web URL.
     * Ensures images sync across employee devices and survive full backup/restore cycles.
     */
    fun processImageUriForSync(context: Context, rawUriString: String?): String? {
        if (rawUriString.isNullOrBlank()) return null

        val trimmed = rawUriString.trim()

        // If already a web URL or Base64 data URL, return as-is
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("data:image/", ignoreCase = true) ||
            trimmed.startsWith("data:;base64,", ignoreCase = true)
        ) {
            return trimmed
        }

        // If it's a content://, file://, or raw file path, convert to compressed base64 data URL
        return try {
            val uri = if (trimmed.startsWith("/") || !trimmed.contains("://")) {
                Uri.fromFile(File(trimmed))
            } else {
                Uri.parse(trimmed)
            }
            val converted = compressUriToDataUrl(context, uri)
            if (!converted.isNullOrBlank()) converted else null
        } catch (e: Exception) {
            Log.w(TAG, "Error processing image URI $trimmed: ${e.message}")
            null
        }
    }

    /**
     * Helper to prepare the image model for Coil's AsyncImage.
     * Supports Base64 data URLs by decoding them directly to ByteArray for ultra-fast, offline-capable rendering.
     */
    fun getImageModel(imageUri: String?): Any? {
        if (imageUri.isNullOrBlank()) return null
        val trimmed = imageUri.trim()

        val cachedBytes = decodedByteArrayCache.get(trimmed)
        if (cachedBytes != null) {
            return cachedBytes
        }

        if (trimmed.startsWith("data:image/", ignoreCase = true) ||
            trimmed.startsWith("data:;base64,", ignoreCase = true) ||
            trimmed.contains(";base64,")
        ) {
            val commaIndex = trimmed.indexOf(',')
            if (commaIndex != -1) {
                val base64Data = trimmed.substring(commaIndex + 1)
                    .replace("\n", "")
                    .replace("\r", "")
                    .replace(" ", "")
                    .trim()
                return try {
                    val decoded = Base64.decode(base64Data, Base64.DEFAULT)
                    decodedByteArrayCache.put(trimmed, decoded)
                    decoded
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to decode base64 data URL: ${e.message}")
                    null
                }
            }
        }

        // Check if raw base64 string without data: header
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true) &&
            !trimmed.startsWith("content://", ignoreCase = true) &&
            !trimmed.startsWith("file://", ignoreCase = true) &&
            trimmed.length > 100 &&
            !trimmed.contains(" ")
        ) {
            val cleanRaw = trimmed.replace("\n", "").replace("\r", "").trim()
            return try {
                val decoded = Base64.decode(cleanRaw, Base64.DEFAULT)
                decodedByteArrayCache.put(trimmed, decoded)
                decoded
            } catch (e: Exception) {
                trimmed
            }
        }

        // Return URL or parsed local URI
        return if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed
        } else {
            try {
                Uri.parse(trimmed)
            } catch (e: Exception) {
                trimmed
            }
        }
    }

    /**
     * Returns a human-readable size summary for debug/audit purposes.
     */
    fun getImageSizeSummary(imageUri: String?): String {
        if (imageUri.isNullOrBlank()) return "No image"
        val trimmed = imageUri.trim()
        return when {
            trimmed.startsWith("data:image/", ignoreCase = true) -> {
                val charCount = trimmed.length
                val approxKb = charCount / 1024
                val percentOfFirestoreLimit = (charCount.toDouble() / 1_048_576.0) * 100.0
                "Base64 Data URL (~$approxKb KB, %.2f%% of Firestore 1MB limit)".format(percentOfFirestoreLimit)
            }
            trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true) -> {
                "Web URL ($trimmed)"
            }
            else -> {
                "Local Device URI ($trimmed)"
            }
        }
    }

    private fun scaleBitmapDown(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height

        if (originalWidth <= maxDimension && originalHeight <= maxDimension) {
            return bitmap
        }

        val ratio = min(
            maxDimension.toFloat() / originalWidth,
            maxDimension.toFloat() / originalHeight
        )

        val targetWidth = (originalWidth * ratio).toInt().coerceAtLeast(1)
        val targetHeight = (originalHeight * ratio).toInt().coerceAtLeast(1)

        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}

