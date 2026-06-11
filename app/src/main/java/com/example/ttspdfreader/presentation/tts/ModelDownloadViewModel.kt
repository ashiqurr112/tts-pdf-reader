package com.example.ttspdfreader.presentation.tts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ttspdfreader.data.local.SettingsManager
import com.example.ttspdfreader.data.tts.DownloadState
import com.example.ttspdfreader.data.tts.ModelManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ModelDownloadViewModel @Inject constructor(
    private val modelManager: ModelManager,
    private val settingsManager: SettingsManager
) : ViewModel() {

    val downloadState: StateFlow<DownloadState> = modelManager.downloadState

    fun getAvailableSpaceBytes(): Long = modelManager.getAvailableStorageSpaceBytes()
    fun getRequiredSpaceBytes(): Long = modelManager.getRequiredStorageSpaceBytes()
    fun modelsExist(): Boolean = modelManager.modelsExist()

    fun startDownload(wifiOnly: Boolean) {
        viewModelScope.launch {
            val success = modelManager.downloadModels(wifiOnly)
            if (success) {
                settingsManager.setHasDownloadedModels(true)
            }
        }
    }

    fun cancelDownload() {
        modelManager.cancelDownload()
    }
}
