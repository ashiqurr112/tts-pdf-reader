package com.example.ttspdfreader.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ttspdfreader.data.model.PdfDocument
import com.example.ttspdfreader.domain.usecase.GetRecentFilesUseCase
import com.example.ttspdfreader.domain.usecase.OpenPdfUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    getRecentFilesUseCase: GetRecentFilesUseCase,
    private val openPdfUseCase: OpenPdfUseCase
) : ViewModel() {

    val recentFiles: StateFlow<List<PdfDocument>> = getRecentFilesUseCase()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun openPdf(uriString: String, onSuccess: (PdfDocument) -> Unit, onError: (Throwable) -> Unit) {
        viewModelScope.launch {
            try {
                val doc = openPdfUseCase(uriString)
                onSuccess(doc)
            } catch (e: Exception) {
                onError(e)
            }
        }
    }
}
