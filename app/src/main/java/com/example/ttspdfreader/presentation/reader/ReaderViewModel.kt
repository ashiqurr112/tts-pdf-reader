package com.example.ttspdfreader.presentation.reader

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ttspdfreader.data.local.SettingsManager
import com.example.ttspdfreader.data.tts.ModelManager
import com.example.ttspdfreader.data.tts.Sentence
import com.example.ttspdfreader.domain.repository.IPdfFileRepository
import com.example.ttspdfreader.domain.usecase.OpenPdfUseCase
import com.example.ttspdfreader.domain.usecase.SaveLastPageUseCase
import com.example.ttspdfreader.service.ReadAloudService
import com.example.ttspdfreader.service.TtsState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import javax.inject.Inject

sealed class ReaderUiState {
    object Loading : ReaderUiState()
    data class Success(
        val docId: Long,
        val uri: Uri,
        val title: String,
        val initialPage: Int
    ) : ReaderUiState()
    data class Error(val message: String) : ReaderUiState()
}

@HiltViewModel
class ReaderViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: IPdfFileRepository,
    private val openPdfUseCase: OpenPdfUseCase,
    private val saveLastPageUseCase: SaveLastPageUseCase,
    private val modelManager: ModelManager,
    private val settingsManager: SettingsManager,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val uiState: StateFlow<ReaderUiState> = _uiState

    private var currentDocId: Long = -1L
    private var currentPage: Int = 0

    // TTS Service Binding State
    private var readAloudService: ReadAloudService? = null
    private var isBound = false

    private val _ttsState = MutableStateFlow(TtsState.IDLE)
    val ttsState: StateFlow<TtsState> = _ttsState.asStateFlow()

    private val _currentSentenceIndex = MutableStateFlow(-1)
    val currentSentenceIndex: StateFlow<Int> = _currentSentenceIndex.asStateFlow()

    private val _sentences = MutableStateFlow<List<Sentence>>(emptyList())
    val sentences: StateFlow<List<Sentence>> = _sentences.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _servicePage = MutableStateFlow(-1)
    val servicePage: StateFlow<Int> = _servicePage.asStateFlow()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ReadAloudService.LocalBinder
            val s = binder.getService()
            readAloudService = s
            isBound = true

            viewModelScope.launch {
                s.ttsState.collect { _ttsState.value = it }
            }
            viewModelScope.launch {
                s.currentSentenceIndex.collect { _currentSentenceIndex.value = it }
            }
            viewModelScope.launch {
                s.sentences.collect { _sentences.value = it }
            }
            viewModelScope.launch {
                s.playbackSpeed.collect { _playbackSpeed.value = it }
            }
            viewModelScope.launch {
                s.currentPage.collect {
                    if (it != -1) {
                        _servicePage.value = it
                        currentPage = it
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            readAloudService = null
            isBound = false
        }
    }

    init {
        val encodedUri: String? = savedStateHandle["uri"]
        if (encodedUri != null) {
            val uriString = Uri.decode(encodedUri)
            loadPdf(uriString)
        } else {
            _uiState.value = ReaderUiState.Error("No PDF URI supplied")
        }
        
        // Auto-bind to service if it's already running
        bindTtsService()
    }

    private fun loadPdf(uriString: String) {
        viewModelScope.launch {
            try {
                val doc = openPdfUseCase(uriString)
                val originalUri = Uri.parse(uriString)

                if (originalUri.scheme == "content" && !uriString.contains("media")) {
                    try {
                        context.contentResolver.takePersistableUriPermission(
                            originalUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                currentDocId = doc.id
                currentPage = doc.lastPage

                _uiState.value = ReaderUiState.Success(
                    docId = doc.id,
                    uri = originalUri,
                    title = doc.title,
                    initialPage = doc.lastPage
                )

            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = ReaderUiState.Error(e.message ?: "Failed to load PDF")
            }
        }
    }

    fun onPageChanged(pageIndex: Int) {
        if (pageIndex == currentPage) return
        currentPage = pageIndex

        viewModelScope.launch {
            if (currentDocId != -1L) {
                saveLastPageUseCase(currentDocId, pageIndex)
            }
        }
    }

    fun hasDownloadedModels(): Boolean {
        return modelManager.modelsExist()
    }

    fun bindTtsService() {
        if (!isBound) {
            val intent = Intent(context, ReadAloudService::class.java)
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    fun unbindTtsService() {
        if (isBound) {
            context.unbindService(serviceConnection)
            isBound = false
            readAloudService = null
        }
    }

    fun startReading(uri: Uri, title: String) {
        val intent = Intent(context, ReadAloudService::class.java)
        context.startForegroundService(intent)
        bindTtsService()

        viewModelScope.launch {
            var waited = 0L
            while (readAloudService == null && waited < 5000L) {
                delay(50)
                waited += 50
            }
            if (readAloudService != null) {
                readAloudService?.startReading(uri.toString(), title, currentPage, 0)
            } else {
                Log.e("ReaderViewModel", "Failed to bind to ReadAloudService within timeout")
            }
        }
    }

    fun pauseReading() {
        readAloudService?.pauseReading()
    }

    fun resumeReading() {
        readAloudService?.resumeReading()
    }

    fun stopReading() {
        readAloudService?.stopReading()
    }

    fun setSpeed(speed: Float) {
        readAloudService?.setSpeed(speed)
    }

    fun skipNext() {
        readAloudService?.skipToNextSentence()
    }

    fun skipPrev() {
        readAloudService?.skipToPreviousSentence()
    }

    override fun onCleared() {
        super.onCleared()
        unbindTtsService()
        viewModelScope.launch {
            if (currentDocId != -1L) {
                saveLastPageUseCase(currentDocId, currentPage)
            }
        }
    }
}
