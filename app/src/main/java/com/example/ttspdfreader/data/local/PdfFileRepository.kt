package com.example.ttspdfreader.data.local

import com.example.ttspdfreader.data.model.PdfDocument
import com.example.ttspdfreader.domain.repository.IPdfFileRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PdfFileRepository @Inject constructor(
    private val recentFilesDao: RecentFilesDao
) : IPdfFileRepository {

    override fun getRecentFiles(): Flow<List<PdfDocument>> {
        return recentFilesDao.getRecentFiles()
    }

    override suspend fun getFileByPath(path: String): PdfDocument? {
        return recentFilesDao.getFileByPath(path)
    }

    override suspend fun upsertFile(pdfDocument: PdfDocument): Long {
        return recentFilesDao.upsertFile(pdfDocument)
    }

    override suspend fun updateLastPage(id: Long, lastPage: Int) {
        recentFilesDao.updateLastPage(id, lastPage, System.currentTimeMillis())
    }
}
