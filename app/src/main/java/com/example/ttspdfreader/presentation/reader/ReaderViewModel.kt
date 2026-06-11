package com.example.ttspdfreader.presentation.reader

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ttspdfreader.domain.repository.IPdfFileRepository
import com.example.ttspdfreader.domain.usecase.SaveLastPageUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class ReaderUiState {
    object Loading : ReaderUiState()
    data class Success(
        val docId: Long,
        val uri: Uri,
        val initialPage: Int
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

    private var currentDocId: Long = -1L
    private var currentPage: Int = 0

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
                val uri = Uri.parse(uriString)
                
                // Try taking persistable permission if content URI
                if (uri.scheme == "content" && !uriString.contains("media")) {
                    try {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                // Query DB to see if we have history
                val existingDoc = repository.getFileByPath(uriString)
                val initialPage = existingDoc?.lastPage ?: 0
                val docId = existingDoc?.id ?: -1L
                
                currentDocId = docId
                currentPage = initialPage

                _uiState.value = ReaderUiState.Success(
                    docId = docId,
                    uri = uri,
                    initialPage = initialPage
                )

            } catch (e: Exception) {
                _uiState.value = ReaderUiState.Error(e.message ?: "Failed to load PDF metadata")
            }
        }
    }

    fun onPageChanged(pageIndex: Int) {
        if (pageIndex == currentPage) return
        currentPage = pageIndex
        
        // Save page progress immediately
        viewModelScope.launch {
            if (currentDocId != -1L) {
                saveLastPageUseCase(currentDocId, pageIndex)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            if (currentDocId != -1L) {
                saveLastPageUseCase(currentDocId, currentPage)
            }
        }
    }
}
