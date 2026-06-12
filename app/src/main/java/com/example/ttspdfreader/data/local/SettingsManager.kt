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

    private val _ttsSpeed = MutableStateFlow(prefs.getFloat("key_tts_speed", 1.0f))
    val ttsSpeed: StateFlow<Float> = _ttsSpeed

    private val _autoReadOnOpen = MutableStateFlow(prefs.getBoolean("key_auto_read_on_open", false))
    val autoReadOnOpen: StateFlow<Boolean> = _autoReadOnOpen

    private val _hasDownloadedModels = MutableStateFlow(prefs.getBoolean("key_has_downloaded_models", false))
    val hasDownloadedModels: StateFlow<Boolean> = _hasDownloadedModels

    private val _hasReferenceVoice = MutableStateFlow(prefs.getBoolean("key_has_reference_voice", false))
    val hasReferenceVoice: StateFlow<Boolean> = _hasReferenceVoice

    private val _selectedVoiceId = MutableStateFlow(prefs.getString("key_selected_voice", "af_heart") ?: "af_heart")
    val selectedVoiceId: StateFlow<String> = _selectedVoiceId

    fun setDarkTheme(enabled: Boolean) {
        prefs.edit().putBoolean("key_dark_theme", enabled).apply()
        _isDarkTheme.value = enabled
    }

    fun setTextSize(size: Float) {
        prefs.edit().putFloat("key_text_size", size).apply()
        _textSize.value = size
    }

    fun getSpeed(): Float = prefs.getFloat("key_tts_speed", 1.0f)

    fun setSpeed(speed: Float) {
        prefs.edit().putFloat("key_tts_speed", speed).apply()
        _ttsSpeed.value = speed
    }

    fun setAutoReadOnOpen(enabled: Boolean) {
        prefs.edit().putBoolean("key_auto_read_on_open", enabled).apply()
        _autoReadOnOpen.value = enabled
    }

    fun setHasDownloadedModels(downloaded: Boolean) {
        prefs.edit().putBoolean("key_has_downloaded_models", downloaded).apply()
        _hasDownloadedModels.value = downloaded
    }

    fun setHasReferenceVoice(hasVoice: Boolean) {
        prefs.edit().putBoolean("key_has_reference_voice", hasVoice).apply()
        _hasReferenceVoice.value = hasVoice
    }

    fun setSelectedVoice(id: String) {
        prefs.edit().putString("key_selected_voice", id).apply()
        _selectedVoiceId.value = id
    }

    fun hasReferenceVoice(): Boolean = prefs.getBoolean("key_has_reference_voice", false)
}
