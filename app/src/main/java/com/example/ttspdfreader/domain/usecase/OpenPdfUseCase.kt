package com.example.ttspdfreader.domain.usecase

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.ttspdfreader.data.model.PdfDocument
import com.example.ttspdfreader.domain.repository.IPdfFileRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class OpenPdfUseCase @Inject constructor(
    private val repository: IPdfFileRepository,
    @ApplicationContext private val context: Context
) {
    suspend operator fun invoke(uriString: String): PdfDocument {
        val uri = Uri.parse(uriString)
        var title = "Unknown Document"
        
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        title = cursor.getString(nameIndex) ?: title
                    }
                }
            } catch (e: Exception) {
                // Fallback to last path segment if query fails
                title = uri.lastPathSegment ?: title
            }
        } else {
            val path = uri.path
            if (path != null) {
                val cut = path.lastIndexOf('/')
                if (cut != -1) {
                    title = path.substring(cut + 1)
                } else {
                    title = path
                }
            }
        }

        // Clean up title extension if present
        if (title.lowercase().endsWith(".pdf")) {
            title = title.substring(0, title.length - 4)
        }

        // Check if file already exists in recent list
        val existingDoc = repository.getFileByPath(uriString)
        val docToSave = if (existingDoc != null) {
            existingDoc.copy(lastOpened = System.currentTimeMillis())
        } else {
            PdfDocument(
                path = uriString,
                title = title,
                lastPage = 0,
                lastOpened = System.currentTimeMillis()
            )
        }

        val id = repository.upsertFile(docToSave)
        return docToSave.copy(id = if (existingDoc != null) existingDoc.id else id)
    }
}
