package com.example.ttspdfreader.presentation.tts

import androidx.lifecycle.ViewModel
import com.example.ttspdfreader.data.local.SettingsManager
import com.example.ttspdfreader.data.tts.VoiceInfo
import com.example.ttspdfreader.data.tts.VoiceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class VoicePickerViewModel @Inject constructor(
    private val settingsManager: SettingsManager,
    private val voiceManager: VoiceManager
) : ViewModel() {

    val selectedVoiceId: StateFlow<String> = settingsManager.selectedVoiceId

    val voices: List<VoiceInfo> = voiceManager.getAvailableVoices()

    fun selectVoice(id: String) {
        settingsManager.setSelectedVoice(id)
    }
}
