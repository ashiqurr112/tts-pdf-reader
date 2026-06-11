package com.example.ttspdfreader.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.ttspdfreader.data.model.PdfDocument
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentFilesDao {
    @Query("SELECT * FROM recent_files ORDER BY lastOpened DESC")
    fun getRecentFiles(): Flow<List<PdfDocument>>

    @Query("SELECT * FROM recent_files WHERE path = :path LIMIT 1")
    suspend fun getFileByPath(path: String): PdfDocument?

    @Upsert
    suspend fun upsertFile(pdfDocument: PdfDocument): Long

    @Query("UPDATE recent_files SET lastPage = :lastPage, lastOpened = :lastOpened WHERE id = :id")
    suspend fun updateLastPage(id: Long, lastPage: Int, lastOpened: Long = System.currentTimeMillis())

    @Query("DELETE FROM recent_files WHERE id = :id")
    suspend fun deleteFile(id: Long)
}
