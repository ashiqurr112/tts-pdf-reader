package com.example.ttspdfreader.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.ttspdfreader.data.model.PdfDocument

@Database(entities = [PdfDocument::class], version = 1, exportSchema = false)
abstract class PdfDatabase : RoomDatabase() {
    abstract fun recentFilesDao(): RecentFilesDao
}
