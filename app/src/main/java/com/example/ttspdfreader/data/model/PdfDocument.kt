package com.example.ttspdfreader.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "recent_files",
    indices = [Index(value = ["path"], unique = true)]
)
data class PdfDocument(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val title: String,
    val lastPage: Int = 0,
    val lastOpened: Long = System.currentTimeMillis()
)
