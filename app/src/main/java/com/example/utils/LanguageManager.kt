package com.example.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class AppLanguage(val code: String, val label: String, val nativeLabel: String) {
    ENGLISH("en", "English", "English"),
    BENGALI("bn", "Bengali", "বাংলা")
}

object LanguageManager {
    private const val PREFS_NAME = "app_language_prefs"
    private const val KEY_LANGUAGE = "selected_language"

    var currentLanguage by mutableStateOf(AppLanguage.ENGLISH)
        private set

    val isBengali: Boolean
        get() = currentLanguage == AppLanguage.BENGALI

    fun init(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedLangCode = prefs.getString(KEY_LANGUAGE, AppLanguage.ENGLISH.code) ?: AppLanguage.ENGLISH.code
            currentLanguage = AppLanguage.values().find { it.code == savedLangCode } ?: AppLanguage.ENGLISH
        } catch (_: Exception) {
            currentLanguage = AppLanguage.ENGLISH
        }
    }

    fun setLanguage(language: AppLanguage, context: Context? = null) {
        currentLanguage = language
        context?.let { ctx ->
            try {
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putString(KEY_LANGUAGE, language.code).apply()
            } catch (_: Exception) {}
        }
    }

    fun toggleLanguage(context: Context? = null) {
        val newLang = if (currentLanguage == AppLanguage.BENGALI) AppLanguage.ENGLISH else AppLanguage.BENGALI
        setLanguage(newLang, context)
    }

    fun getString(en: String, bn: String = ""): String {
        return if (isBengali && bn.isNotBlank()) bn else en
    }
}

