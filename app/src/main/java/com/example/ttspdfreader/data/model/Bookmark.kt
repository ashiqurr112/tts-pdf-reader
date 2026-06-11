package com.example.ttspdfreader.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = PdfDocument::class,
            parentColumns = ["id"],
            childColumns = ["pdfDocumentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["pdfDocumentId"])]
)
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pdfDocumentId: Long,
    val pageIndex: Int,
    val label: String,
    val timestamp: Long = System.currentTimeMillis()
)
