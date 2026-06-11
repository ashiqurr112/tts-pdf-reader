package com.example.ttspdfreader.domain.repository

import com.example.ttspdfreader.data.model.PdfDocument
import kotlinx.coroutines.flow.Flow

interface IPdfFileRepository {
    fun getRecentFiles(): Flow<List<PdfDocument>>
    suspend fun getFileByPath(path: String): PdfDocument?
    suspend fun upsertFile(pdfDocument: PdfDocument): Long
    suspend fun updateLastPage(id: Long, lastPage: Int)
}
