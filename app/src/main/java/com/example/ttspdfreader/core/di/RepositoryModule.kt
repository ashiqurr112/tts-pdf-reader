package com.example.ttspdfreader.core.di

import com.example.ttspdfreader.data.local.PdfFileRepository
import com.example.ttspdfreader.domain.repository.IPdfFileRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindPdfFileRepository(
        pdfFileRepository: PdfFileRepository
    ): IPdfFileRepository
}
