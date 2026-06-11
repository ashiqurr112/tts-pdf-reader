package com.example.ttspdfreader.data.local

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsManager @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("pdf_reader_settings", Context.MODE_PRIVATE)

    private val _isDarkTheme = MutableStateFlow(prefs.getBoolean("key_dark_theme", false))
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme

    private val _textSize = MutableStateFlow(prefs.getFloat("key_text_size", 16f))
    val textSize: StateFlow<Float> = _textSize

    fun setDarkTheme(enabled: Boolean) {
        prefs.edit().putBoolean("key_dark_theme", enabled).apply()
        _isDarkTheme.value = enabled
    }

    fun setTextSize(size: Float) {
        prefs.edit().putFloat("key_text_size", size).apply()
        _textSize.value = size
    }
}
