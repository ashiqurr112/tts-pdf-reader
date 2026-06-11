package com.example.ttspdfreader.presentation.documents

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class ScannedPdf(
    val uri: Uri,
    val name: String,
    val path: String,
    val size: Long,
    val dateModified: Long
)

sealed class DocumentsUiState {
    object Initial : DocumentsUiState()
    object Loading : DocumentsUiState()
    data class Success(val pdfs: List<ScannedPdf>) : DocumentsUiState()
    data class Error(val message: String) : DocumentsUiState()
    object PermissionRequired : DocumentsUiState()
}

@HiltViewModel
class DocumentsViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow<DocumentsUiState>(DocumentsUiState.Initial)
    val uiState: StateFlow<DocumentsUiState> = _uiState

    fun onPermissionGranted() {
        scanForPdfs()
    }

    fun onPermissionDenied() {
        _uiState.value = DocumentsUiState.PermissionRequired
    }

    private fun scanForPdfs() {
        _uiState.value = DocumentsUiState.Loading
        viewModelScope.launch {
            try {
                val pdfs = withContext(Dispatchers.IO) {
                    val list = mutableListOf<ScannedPdf>()
                    val collection = MediaStore.Files.getContentUri("external")
                    
                    val projection = arrayOf(
                        MediaStore.Files.FileColumns._ID,
                        MediaStore.Files.FileColumns.DISPLAY_NAME,
                        MediaStore.Files.FileColumns.DATA,
                        MediaStore.Files.FileColumns.SIZE,
                        MediaStore.Files.FileColumns.DATE_MODIFIED
                    )
                    
                    val selection = "${MediaStore.Files.FileColumns.MIME_TYPE} = ?"
                    val selectionArgs = arrayOf("application/pdf")
                    val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"

                    context.contentResolver.query(
                        collection,
                        projection,
                        selection,
                        selectionArgs,
                        sortOrder
                    )?.use { cursor ->
                        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                        val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                        val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                        val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                        val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)

                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idColumn)
                            val name = cursor.getString(nameColumn) ?: "Unknown"
                            val path = cursor.getString(dataColumn) ?: ""
                            val size = cursor.getLong(sizeColumn)
                            val date = cursor.getLong(dateColumn)
                            
                            // Even though MediaStore says it's a PDF, double check it exists
                            if (path.isNotEmpty() && File(path).exists()) {
                                // Important: We pass the 'file://' uri constructed from the DATA path.
                                // This bypasses ContentResolver issues for reading directly if we have MANAGE_EXTERNAL_STORAGE.
                                // Alternatively, we can use the MediaStore content Uri. Let's use the file URI for consistency 
                                // with standard paths, but provide the file path.
                                val fileUri = Uri.fromFile(File(path))
                                
                                list.add(
                                    ScannedPdf(
                                        uri = fileUri,
                                        name = name,
                                        path = path,
                                        size = size,
                                        dateModified = date
                                    )
                                )
                            }
                        }
                    }
                    list
                }
                _uiState.value = DocumentsUiState.Success(pdfs)
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = DocumentsUiState.Error(e.message ?: "Failed to scan files")
            }
        }
    }
}
