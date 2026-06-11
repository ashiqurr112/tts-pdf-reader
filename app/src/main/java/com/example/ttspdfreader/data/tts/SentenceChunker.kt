package com.example.ttspdfreader.data.tts

import android.icu.text.BreakIterator
import java.util.Locale

data class Sentence(
    val text: String,
    val startOffset: Int,
    val endOffset: Int
)

class SentenceChunker {
    fun chunk(text: String, locale: Locale = Locale.US): List<Sentence> {
        if (text.isBlank()) return emptyList()
        val sentences = mutableListOf<Sentence>()
        val boundary = BreakIterator.getSentenceInstance(locale)
        boundary.setText(text)
        
        var start = boundary.first()
        var end = boundary.next()
        
        while (end != BreakIterator.DONE) {
            val sentenceText = text.substring(start, end)
            // Trim leading/trailing whitespace but keep original offsets for exact highlighting
            val trimmedText = sentenceText.trim()
            if (trimmedText.isNotEmpty()) {
                // Find exact start and end offsets of the trimmed text within the original string
                val startTrimDelta = sentenceText.indexOf(trimmedText)
                val endTrimDelta = sentenceText.length - (startTrimDelta + trimmedText.length)
                sentences.add(
                    Sentence(
                        text = trimmedText,
                        startOffset = start + startTrimDelta,
                        endOffset = end - endTrimDelta
                    )
                )
            }
            start = end
            end = boundary.next()
        }
        return sentences
    }
}
