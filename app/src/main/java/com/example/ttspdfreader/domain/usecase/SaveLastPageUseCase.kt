package com.example.ttspdfreader.domain.usecase

import com.example.ttspdfreader.domain.repository.IPdfFileRepository
import javax.inject.Inject

class SaveLastPageUseCase @Inject constructor(
    private val repository: IPdfFileRepository
) {
    suspend operator fun invoke(id: Long, lastPage: Int) {
        repository.updateLastPage(id, lastPage)
    }
}
