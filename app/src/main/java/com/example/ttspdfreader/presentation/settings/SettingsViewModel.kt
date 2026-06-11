package com.example.ttspdfreader.presentation.settings

import androidx.lifecycle.ViewModel
import com.example.ttspdfreader.data.local.SettingsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsManager: SettingsManager
) : ViewModel() {

    val isDarkTheme: StateFlow<Boolean> = settingsManager.isDarkTheme
    val textSize: StateFlow<Float> = settingsManager.textSize

    fun setDarkTheme(enabled: Boolean) {
        settingsManager.setDarkTheme(enabled)
    }

    fun setTextSize(size: Float) {
        settingsManager.setTextSize(size)
    }
}
