package com.example.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.graphics.vector.ImageVector

enum class SettingsTab(
    val titleEn: String,
    val titleBn: String,
    val icon: ImageVector,
    val descriptionEn: String,
    val descriptionBn: String
) {
    STORE_STAFF(
        titleEn = "Store & Staff",
        titleBn = "দোকান ও স্টাফ",
        icon = Icons.Default.Storefront,
        descriptionEn = "Store profile, payroll, cashier & PIN",
        descriptionBn = "দোকানের তথ্য, হাজিরা, বেতন ও ক্যাশিয়ার"
    ),
    BILLING_HARDWARE(
        titleEn = "Billing & Hardware",
        titleBn = "বিলিং ও প্রিন্টার",
        icon = Icons.Default.Print,
        descriptionEn = "Thermal printer, PDF, SMS & profit rules",
        descriptionBn = "থার্মাল প্রিন্টার, পিডিএফ, এসএমএস ও অফার"
    ),
    CLOUD_BACKUP(
        titleEn = "Cloud & Backup",
        titleBn = "ক্লাউড ও ব্যাকআপ",
        icon = Icons.Default.CloudSync,
        descriptionEn = "Firebase sync, Drive backup & accounts",
        descriptionBn = "ফায়ারস্টোর সিঙ্ক, ড্রাইভ ব্যাকআপ ও হিস্ট্রি"
    ),
    SYSTEM(
        titleEn = "App & System",
        titleBn = "অ্যাপ ও সিস্টেম",
        icon = Icons.Default.Tune,
        descriptionEn = "Theme, home widget, security & reset",
        descriptionBn = "ডার্ক থিম, হোম উইজেট ও ডেটা রিসেট"
    );

    fun getLabel(): String = if (com.example.utils.LanguageManager.isBengali) titleBn else titleEn
    fun getDescription(): String = if (com.example.utils.LanguageManager.isBengali) descriptionBn else descriptionEn
}
