package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.example.utils.ThemeManager

@Composable
fun MyApplicationTheme(
    systemDark: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val isDark = ThemeManager.isDarkMode(systemDark)
    val palette = ThemeManager.currentPalette

    val darkColorScheme = darkColorScheme(
        primary = palette.primaryDark,
        primaryContainer = palette.primaryContainerDark,
        onPrimary = Color(0xFF121212),
        onPrimaryContainer = palette.onPrimaryContainerDark,
        secondary = palette.secondaryDark,
        secondaryContainer = Color(0xFF3B2D05),
        onSecondaryContainer = Color(0xFFFDE68A),
        tertiary = palette.accentDark,
        background = palette.surfaceWarmDark,
        surface = palette.cardBgDark,
        surfaceVariant = palette.cardBgDark,
        outline = palette.borderDark,
        outlineVariant = palette.borderDark,
        onBackground = palette.textPrimaryDark,
        onSurface = palette.textPrimaryDark,
        onSurfaceVariant = palette.textSecondaryDark
    )

    val lightColorScheme = lightColorScheme(
        primary = palette.primaryLight,
        primaryContainer = palette.primaryContainerLight,
        onPrimary = Color.White,
        onPrimaryContainer = palette.onPrimaryContainerLight,
        secondary = palette.secondaryLight,
        secondaryContainer = Color(0xFFFEF3C7),
        onSecondary = Color.White,
        onSecondaryContainer = Color(0xFF78350F),
        tertiary = palette.accentLight,
        background = palette.surfaceWarmLight,
        surface = palette.cardBgLight,
        surfaceVariant = Color(0xFFF4F2EC),
        outline = palette.borderLight,
        outlineVariant = palette.borderLight,
        onBackground = palette.textPrimaryLight,
        onSurface = palette.textPrimaryLight,
        onSurfaceVariant = palette.textSecondaryLight
    )

    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            isDark -> darkColorScheme
            else -> lightColorScheme
        }

    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}

