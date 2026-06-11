package com.example.ttspdfreader.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.ttspdfreader.data.model.PdfDocument

import com.example.ttspdfreader.data.model.Bookmark

@Database(entities = [PdfDocument::class, Bookmark::class], version = 2, exportSchema = false)
abstract class PdfDatabase : RoomDatabase() {
    abstract fun recentFilesDao(): RecentFilesDao
    abstract fun bookmarkDao(): BookmarkDao
}
