package com.example.ttspdfreader.core.di

import android.content.Context
import androidx.room.Room
import com.example.ttspdfreader.data.local.PdfDatabase
import com.example.ttspdfreader.data.local.RecentFilesDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PdfDatabase {
        return Room.databaseBuilder(
            context,
            PdfDatabase::class.java,
            "pdf_reader_db"
        ).build()
    }

    @Provides
    @Singleton
    fun provideRecentFilesDao(database: PdfDatabase): RecentFilesDao {
        return database.recentFilesDao()
    }
}
