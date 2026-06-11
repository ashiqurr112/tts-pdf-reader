package com.example.ttspdfreader.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.ttspdfreader.data.model.Bookmark
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE pdfDocumentId = :docId ORDER BY pageIndex ASC")
    fun getBookmarksForPdf(docId: Long): Flow<List<Bookmark>>

    @Insert
    suspend fun insertBookmark(bookmark: Bookmark): Long

    @Query("DELETE FROM bookmarks WHERE id = :bookmarkId")
    suspend fun deleteBookmark(bookmarkId: Long)
}
