package com.example.ttspdfreader.presentation.reader

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ttspdfreader.data.local.PdfRendererWrapper
import com.example.ttspdfreader.domain.repository.IPdfFileRepository
import com.example.ttspdfreader.domain.usecase.SaveLastPageUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class ReaderUiState {
    object Loading : ReaderUiState()
    data class Success(
        val docId: Long,
        val path: String,
        val currentPage: Int,
        val totalPages: Int,
        val bitmaps: Map<Int, Bitmap>
    ) : ReaderUiState()
    data class Error(val message: String) : ReaderUiState()
}

@HiltViewModel
class ReaderViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: IPdfFileRepository,
    private val saveLastPageUseCase: SaveLastPageUseCase,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val uiState: StateFlow<ReaderUiState> = _uiState

    private var pdfRenderer: PdfRendererWrapper? = null
    
    // Cache map: pageIndex -> Bitmap
    private val cachedBitmaps = mutableMapOf<Int, Bitmap>()
    
    // Active jobs for rendering pages
    private val renderingJobs = mutableMapOf<Int, Job>()

    init {
        val encodedUri: String? = savedStateHandle["uri"]
        if (encodedUri != null) {
            val uriString = Uri.decode(encodedUri)
            loadPdf(uriString)
        } else {
            _uiState.value = ReaderUiState.Error("No PDF URI supplied")
        }
    }

    private fun loadPdf(uriString: String) {
        viewModelScope.launch {
            try {
                // Try taking persistable permission if content URI
                try {
                    val uri = Uri.parse(uriString)
                    if (uri.scheme == "content") {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                val renderer = PdfRendererWrapper(context, uriString)
                pdfRenderer = renderer

                // Query DB to see if we have history
                val existingDoc = repository.getFileByPath(uriString)
                val initialPage = existingDoc?.lastPage ?: 0
                val docId = existingDoc?.id ?: -1L

                _uiState.value = ReaderUiState.Success(
                    docId = docId,
                    path = uriString,
                    currentPage = initialPage,
                    totalPages = renderer.pageCount,
                    bitmaps = emptyMap()
                )

                // Start loading the initial sliding window
                updateCache(initialPage)

            } catch (e: Exception) {
                _uiState.value = ReaderUiState.Error(e.message ?: "Failed to open PDF")
            }
        }
    }

    fun onPageSelected(pageIndex: Int) {
        val currentState = _uiState.value
        if (currentState is ReaderUiState.Success) {
            if (pageIndex == currentState.currentPage) return
            
            _uiState.value = currentState.copy(currentPage = pageIndex)
            updateCache(pageIndex)
            
            // Save page progress immediately
            viewModelScope.launch {
                if (currentState.docId != -1L) {
                    saveLastPageUseCase(currentState.docId, pageIndex)
                }
            }
        }
    }

    private fun updateCache(centerPage: Int) {
        val renderer = pdfRenderer ?: return
        val currentState = _uiState.value as? ReaderUiState.Success ?: return
        val totalPages = currentState.totalPages

        val range = (centerPage - 2)..(centerPage + 2)

        // 1. Evict pages outside the sliding window
        val keysToRemove = cachedBitmaps.keys.filter { it !in range }
        keysToRemove.forEach { key ->
            renderingJobs[key]?.cancel()
            renderingJobs.remove(key)
            val bitmap = cachedBitmaps.remove(key)
            bitmap?.recycle()
        }

        // 2. Load missing pages within the sliding window
        for (pageIndex in range) {
            if (pageIndex in 0 until totalPages && pageIndex !in cachedBitmaps && pageIndex !in renderingJobs) {
                renderingJobs[pageIndex] = viewModelScope.launch {
                    val bitmap = renderer.renderPage(pageIndex)
                    if (bitmap != null) {
                        cachedBitmaps[pageIndex] = bitmap
                        val currentSuccess = _uiState.value as? ReaderUiState.Success
                        if (currentSuccess != null) {
                            _uiState.value = currentSuccess.copy(bitmaps = cachedBitmaps.toMap())
                        }
                    }
                    renderingJobs.remove(pageIndex)
                }
            }
        }
    }

    fun saveProgressBeforeExit() {
        val currentState = _uiState.value as? ReaderUiState.Success ?: return
        viewModelScope.launch {
            if (currentState.docId != -1L) {
                saveLastPageUseCase(currentState.docId, currentState.currentPage)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        saveProgressBeforeExit()
        
        renderingJobs.values.forEach { it.cancel() }
        renderingJobs.clear()
        
        cachedBitmaps.values.forEach { it.recycle() }
        cachedBitmaps.clear()
        
        pdfRenderer?.close()
    }
}
