package com.example.ttspdfreader.domain.usecase

import com.example.ttspdfreader.data.model.PdfDocument
import com.example.ttspdfreader.domain.repository.IPdfFileRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetRecentFilesUseCase @Inject constructor(
    private val repository: IPdfFileRepository
) {
    operator fun invoke(): Flow<List<PdfDocument>> {
        return repository.getRecentFiles()
    }
}
