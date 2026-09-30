package com.example.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.utils.ThemeManager

// Brand Colors (from Kali Mata Logo & Color Palette)
val BrandDeepRed = Color(0xFF8B1E3F)      // Primary — Deep Maroon/Red (#8B1E3F)
val BrandPrimaryLight = Color(0xFFA83254)  // Primary Light (#A83254)
val BrandGold = Color(0xFFC9971C)          // Secondary — Warm Gold/Amber (#C9971C)
val BrandLightGold = Color(0xFFE5B94E)     // Accent — Light Gold (#E5B94E)

// Status Colors
val StatusSuccessPaid = Color(0xFF2E7D32)  // Success / Paid / Synced — Deep Green (#2E7D32)
val StatusWarningLowStock = Color(0xFFD97706) // Warning — Low Stock (#D97706)
val StatusAlertOverdue = Color(0xFFD32F2F) // Alert / Error — Distinct M3 Red-Orange (#D32F2F)
val ProfitGreen = Color(0xFF2E7D32)
val LossRed = Color(0xFFD32F2F)
val WarningOrange = Color(0xFFD97706)

// Neutrals
val NeutralBackground = Color(0xFFFAF9F7)  // Background (#FAF9F7)
val NeutralSurfaceCard = Color(0xFFFFFFFF) // Surface / Card (#FFFFFF)
val NeutralTextPrimary = Color(0xFF2B2A28) // Text Primary (#2B2A28)
val NeutralTextSecondary = Color(0xFF6B6963) // Text Secondary (#6B6963)
val NeutralBorderDivider = Color(0xFFE5E2DC) // Border / Divider (#E5E2DC)

// Dynamic Theme Colors (Responsive to Light/Dark mode)
val StorePrimary: Color
    @Composable get() {
        val isDark = ThemeManager.isDarkMode()
        val palette = ThemeManager.currentPalette
        return if (isDark) palette.primaryDark else palette.primaryLight
    }

val StorePrimaryDark: Color
    @Composable get() {
        val isDark = ThemeManager.isDarkMode()
        val palette = ThemeManager.currentPalette
        return if (isDark) palette.primaryContainerDark else palette.onPrimaryContainerLight
    }

val StorePrimaryContainer: Color
    @Composable get() {
        val isDark = ThemeManager.isDarkMode()
        val palette = ThemeManager.currentPalette
        return if (isDark) palette.primaryContainerDark else palette.primaryContainerLight
    }

val StoreOnPrimaryContainer: Color
    @Composable get() {
        val isDark = ThemeManager.isDarkMode()
        val palette = ThemeManager.currentPalette
        return if (isDark) palette.onPrimaryContainerDark else palette.onPrimaryContainerLight
    }

// Primary accent aliases
val StoreRedPrimary: Color
    @Composable get() = if (ThemeManager.isDarkMode()) Color(0xFFEF5350) else BrandDeepRed

val StoreRedDark: Color
    @Composable get() = if (ThemeManager.isDarkMode()) Color(0xFFC62828) else BrandDeepRed

val StoreOnPrimary: Color
    @Composable get() = if (ThemeManager.isDarkMode()) Color(0xFF121212) else Color.White

val StoreSaffronAccent: Color
    @Composable get() = if (ThemeManager.isDarkMode()) BrandLightGold else BrandGold

val StoreGold: Color
    @Composable get() = if (ThemeManager.isDarkMode()) BrandLightGold else BrandGold

val StoreAccentGold: Color
    @Composable get() = BrandLightGold

// Functional Status Colors (High contrast & readability)
val StoreGreenProfit: Color
    @Composable get() = if (ThemeManager.isDarkMode()) Color(0xFF4ADE80) else StatusSuccessPaid

val StoreBlueUPI: Color
    @Composable get() = if (ThemeManager.isDarkMode()) Color(0xFF60A5FA) else Color(0xFF2563EB)

val StoreOrangeWarning: Color
    @Composable get() = if (ThemeManager.isDarkMode()) Color(0xFFFB923C) else StatusWarningLowStock

val StoreRedAlert: Color
    @Composable get() = if (ThemeManager.isDarkMode()) Color(0xFFF87171) else StatusAlertOverdue

// Category / Action Containers & Text Tokens
val AccentBlueContainer: Color
    @Composable get() = if (ThemeManager.isDarkMode()) Color(0xFF1E3A8A) else Color(0xFFDBEAFE)

val AccentBlueText: Color
    @Composable get() = if (ThemeManager.isDarkMode()) Color(0xFFBFDBFE) else Color(0xFF1E40AF)

val AccentYellowContainer: Color
    @Composable get() = if (ThemeManager.isDarkMode()) Color(0xFF713F12) else Color(0xFFFEF3C7)

val AccentYellowText: Color
    @Composable get() = if (ThemeManager.isDarkMode()) Color(0xFFFDE68A) else Color(0xFF92400E)

val AccentPinkContainer: Color
    @Composable get() = if (ThemeManager.isDarkMode()) Color(0xFF831843) else Color(0xFFFCE7F3)

val AccentPinkText: Color
    @Composable get() = if (ThemeManager.isDarkMode()) Color(0xFFFBCFE8) else Color(0xFF9D174D)

val AccentGreenContainer: Color
    @Composable get() = if (ThemeManager.isDarkMode()) Color(0xFF064E3B) else Color(0xFFD1FAE5)

val AccentGreenText: Color
    @Composable get() = if (ThemeManager.isDarkMode()) Color(0xFFA7F3D0) else Color(0xFF065F46)

// Surface & Typography (Dynamic for Light and Dark modes)
val SurfaceWarm: Color
    @Composable get() {
        val isDark = ThemeManager.isDarkMode()
        val palette = ThemeManager.currentPalette
        return if (isDark) palette.surfaceWarmDark else palette.surfaceWarmLight
    }

val CardBackground: Color
    @Composable get() {
        val isDark = ThemeManager.isDarkMode()
        val palette = ThemeManager.currentPalette
        return if (isDark) palette.cardBgDark else palette.cardBgLight
    }

val TextDark: Color
    @Composable get() = if (ThemeManager.isDarkMode()) Color(0xFFF8FAFC) else NeutralTextPrimary

val TextMuted: Color
    @Composable get() = if (ThemeManager.isDarkMode()) Color(0xFFA1A1AA) else NeutralTextSecondary

val BorderDivider: Color
    @Composable get() = if (ThemeManager.isDarkMode()) Color(0xFF2A2A2A) else NeutralBorderDivider

val BackgroundLight: Color
    @Composable get() = if (ThemeManager.isDarkMode()) Color(0xFF1E293B) else NeutralBackground

