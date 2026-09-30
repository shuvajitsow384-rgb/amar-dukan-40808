package com.example.utils

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "theme_preferences")

enum class AppThemeMode {
    LIGHT, DARK, SYSTEM
}

enum class ColorPalette(
    val displayName: String,
    val primaryLight: Color,
    val primaryDark: Color,
    val primaryContainerLight: Color,
    val primaryContainerDark: Color,
    val onPrimaryContainerLight: Color,
    val onPrimaryContainerDark: Color,
    val secondaryLight: Color = Color(0xFFD4A017),
    val secondaryDark: Color = Color(0xFFF2C94C),
    val accentLight: Color = Color(0xFFF2C94C),
    val accentDark: Color = Color(0xFFF2C94C),
    val surfaceWarmLight: Color = Color(0xFFFAF9F7), // Neutral Background (#FAF9F7)
    val surfaceWarmDark: Color = Color(0xFF121212),  // Dark Gray Background (#121212)
    val cardBgLight: Color = Color(0xFFFFFFFF),      // Neutral Surface / Card (#FFFFFF)
    val cardBgDark: Color = Color(0xFF1E1E1E),       // Elevated Dark Gray (#1E1E1E)
    val borderLight: Color = Color(0xFFE5E2DC),      // Neutral Border / Divider (#E5E2DC)
    val borderDark: Color = Color(0xFF2A2A2A),
    val textPrimaryLight: Color = Color(0xFF2B2A28), // Text Primary (#2B2A28)
    val textPrimaryDark: Color = Color(0xFFF8FAFC),
    val textSecondaryLight: Color = Color(0xFF6B6963), // Text Secondary (#6B6963)
    val textSecondaryDark: Color = Color(0xFFA1A1AA)
) {
    KALI_MATA(
        displayName = "Kali Mata Brand",
        primaryLight = Color(0xFF8B1E3F), // Deep Maroon #8B1E3F
        primaryDark = Color(0xFFEF5350),  // Crisp red on dark
        primaryContainerLight = Color(0xFFFCE7EC),
        primaryContainerDark = Color(0xFF5A0C22),
        onPrimaryContainerLight = Color(0xFF8B1E3F),
        onPrimaryContainerDark = Color(0xFFFFD9E2),
        secondaryLight = Color(0xFFC9971C), // Secondary - Warm Gold #C9971C
        secondaryDark = Color(0xFFE5B94E),
        accentLight = Color(0xFFC9971C),    // Accent - Warm Gold #C9971C
        accentDark = Color(0xFFE5B94E),
        surfaceWarmLight = Color(0xFFFAF9F7),
        surfaceWarmDark = Color(0xFF121212),
        cardBgLight = Color(0xFFFFFFFF),
        cardBgDark = Color(0xFF1E1E1E),
        borderLight = Color(0xFFE5E2DC),
        borderDark = Color(0xFF2A2A2A),
        textPrimaryLight = Color(0xFF2B2A28),
        textPrimaryDark = Color(0xFFF8FAFC),
        textSecondaryLight = Color(0xFF6B6963),
        textSecondaryDark = Color(0xFFA1A1AA)
    ),
    INDIGO(
        displayName = "Kali Mata Brand",
        primaryLight = Color(0xFF8B1E3F),
        primaryDark = Color(0xFFEF5350),
        primaryContainerLight = Color(0xFFFCE7EC),
        primaryContainerDark = Color(0xFF5A0C22),
        onPrimaryContainerLight = Color(0xFF8B1E3F),
        onPrimaryContainerDark = Color(0xFFFFD9E2),
        secondaryLight = Color(0xFFC9971C),
        secondaryDark = Color(0xFFE5B94E),
        accentLight = Color(0xFFC9971C),
        accentDark = Color(0xFFE5B94E),
        surfaceWarmLight = Color(0xFFFAF9F7),
        surfaceWarmDark = Color(0xFF121212),
        cardBgLight = Color(0xFFFFFFFF),
        cardBgDark = Color(0xFF1E1E1E),
        borderLight = Color(0xFFE5E2DC),
        borderDark = Color(0xFF2A2A2A),
        textPrimaryLight = Color(0xFF2B2A28),
        textPrimaryDark = Color(0xFFF8FAFC),
        textSecondaryLight = Color(0xFF6B6963),
        textSecondaryDark = Color(0xFFA1A1AA)
    )
}

object ThemeManager {
    private val THEME_MODE_KEY = stringPreferencesKey("app_theme_mode")
    private val PALETTE_KEY = stringPreferencesKey("app_color_palette")
    private val scope = CoroutineScope(Dispatchers.IO)
    private var appContext: Context? = null

    var themeMode by mutableStateOf(AppThemeMode.LIGHT)
        private set

    var currentPalette by mutableStateOf(ColorPalette.KALI_MATA)
        private set

    fun init(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        scope.launch {
            appCtx.dataStore.data
                .map { preferences ->
                    val savedModeName = preferences[THEME_MODE_KEY] ?: AppThemeMode.LIGHT.name
                    val mode = runCatching { AppThemeMode.valueOf(savedModeName) }.getOrDefault(AppThemeMode.LIGHT)
                    val savedPaletteName = preferences[PALETTE_KEY] ?: ColorPalette.KALI_MATA.name
                    val palette = runCatching { ColorPalette.valueOf(savedPaletteName) }.getOrDefault(ColorPalette.KALI_MATA)
                    Pair(mode, palette)
                }
                .collect { (mode, palette) ->
                    themeMode = mode
                    currentPalette = palette
                }
        }
    }

    fun setThemeMode(mode: AppThemeMode, context: Context? = null) {
        themeMode = mode
        val ctx = context?.applicationContext ?: appContext
        ctx?.let { c ->
            scope.launch {
                c.dataStore.edit { preferences ->
                    preferences[THEME_MODE_KEY] = mode.name
                }
            }
        }
    }

    fun setColorPalette(palette: ColorPalette, context: Context? = null) {
        currentPalette = palette
        val ctx = context?.applicationContext ?: appContext
        ctx?.let { c ->
            scope.launch {
                c.dataStore.edit { preferences ->
                    preferences[PALETTE_KEY] = palette.name
                }
            }
        }
    }

    fun toggleTheme(context: Context? = null) {
        val nextMode = if (isDarkMode()) AppThemeMode.LIGHT else AppThemeMode.DARK
        setThemeMode(nextMode, context)
    }

    fun isDarkMode(systemInDark: Boolean = false): Boolean {
        return when (themeMode) {
            AppThemeMode.LIGHT -> false
            AppThemeMode.DARK -> true
            AppThemeMode.SYSTEM -> systemInDark
        }
    }
}
