package com.example.utils

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

enum class DefaultCameraOption(
    val facing: Int,
    val titleEn: String,
    val titleBn: String,
    val subtitleEn: String,
    val subtitleBn: String
) {
    BACK(
        facing = CameraSelector.LENS_FACING_BACK,
        titleEn = "Back Camera (Rear)",
        titleBn = "পিছনের ক্যামেরা (রিয়ার)",
        subtitleEn = "Recommended for handheld barcode scanning",
        subtitleBn = "হাতে ধরে পণ্যের বারকোড স্ক্যান করার জন্য উপযুক্ত"
    ),
    FRONT(
        facing = CameraSelector.LENS_FACING_FRONT,
        titleEn = "Front Camera (Selfie)",
        titleBn = "সামনের ক্যামেরা (সেলফি)",
        subtitleEn = "Recommended for counter stands & hands-free scanning",
        subtitleBn = "কাউন্টার স্ট্যান্ড বা হ্যান্ডস-ফ্রি স্ক্যানিংয়ের জন্য সেরা"
    );

    companion object {
        fun fromFacing(facing: Int): DefaultCameraOption {
            return if (facing == CameraSelector.LENS_FACING_FRONT) FRONT else BACK
        }
    }
}

object CameraPreferenceManager {
    private const val PREFS_NAME = "store_camera_prefs"
    private const val KEY_DEFAULT_CAMERA = "default_scanner_camera" // "BACK" or "FRONT"

    var defaultLensFacing by mutableIntStateOf(CameraSelector.LENS_FACING_BACK)
        private set

    fun init(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val saved = prefs.getString(KEY_DEFAULT_CAMERA, "BACK")
            defaultLensFacing = if (saved == "FRONT") CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        } catch (_: Exception) {
            defaultLensFacing = CameraSelector.LENS_FACING_BACK
        }
    }

    fun getDefaultCamera(context: Context): Int {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val saved = prefs.getString(KEY_DEFAULT_CAMERA, "BACK")
            val facing = if (saved == "FRONT") CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            defaultLensFacing = facing
            facing
        } catch (_: Exception) {
            CameraSelector.LENS_FACING_BACK
        }
    }

    fun setDefaultCamera(context: Context, lensFacing: Int) {
        defaultLensFacing = lensFacing
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val str = if (lensFacing == CameraSelector.LENS_FACING_FRONT) "FRONT" else "BACK"
            prefs.edit().putString(KEY_DEFAULT_CAMERA, str).apply()
        } catch (_: Exception) {}
    }

    fun isFrontCameraDefault(context: Context): Boolean {
        return getDefaultCamera(context) == CameraSelector.LENS_FACING_FRONT
    }

    fun getCameraLabel(facing: Int, isBengali: Boolean = false): String {
        return if (facing == CameraSelector.LENS_FACING_FRONT) {
            if (isBengali) "সামনের ক্যামেরা (Front)" else "Front Camera"
        } else {
            if (isBengali) "পিছনের ক্যামেরা (Back)" else "Back Camera"
        }
    }
}
