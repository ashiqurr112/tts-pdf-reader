package com.example.ttspdfreader.data.tts

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KokoroTokenizer @Inject constructor() {
    companion object {
        private const val TAG = "KokoroTokenizer"
        const val MAX_TOKENS = 510 // 512 total including start/end pad tokens (which are added on top of the 510 limit)
        const val PAD_TOKEN_ID = 0L
        const val SPACE_TOKEN_ID = 16L

        val VOCAB: Map<Char, Long> = mapOf(
            // Punctuation (1-17)
            ';' to 1L, ':' to 2L, ',' to 3L, '.' to 4L, '!' to 5L, '?' to 6L,
            '—' to 9L, '…' to 10L, '"' to 11L, '(' to 12L, ')' to 13L,
            '\u201C' to 14L, '\u201D' to 15L, ' ' to 16L, '\u0303' to 17L,
            // Special affricates (18-22)
            'ʣ' to 18L, 'ʥ' to 19L, 'ʦ' to 20L, 'ʨ' to 21L, 'ᵝ' to 22L,
            // Misc (23-42)
            '\uAB67' to 23L, 'A' to 24L, 'I' to 25L, 'O' to 31L, 'Q' to 33L,
            'S' to 35L, 'T' to 36L, 'W' to 39L, 'Y' to 41L, 'ᵊ' to 42L,
            // Lowercase letters (43-68)
            'a' to 43L, 'b' to 44L, 'c' to 45L, 'd' to 46L, 'e' to 47L,
            'f' to 48L, 'h' to 50L, 'i' to 51L, 'j' to 52L, 'k' to 53L,
            'l' to 54L, 'm' to 55L, 'n' to 56L, 'o' to 57L, 'p' to 58L,
            'q' to 59L, 'r' to 60L, 's' to 61L, 't' to 62L, 'u' to 63L,
            'v' to 64L, 'w' to 65L, 'x' to 66L, 'y' to 67L, 'z' to 68L,
            // IPA vowels & consonants (69-177)
            'ɑ' to 69L, 'ɐ' to 70L, 'ɒ' to 71L, 'æ' to 72L, 'β' to 75L,
            'ɔ' to 76L, 'ɕ' to 77L, 'ç' to 78L, 'ɖ' to 80L, 'ð' to 81L,
            'ʤ' to 82L, 'ə' to 83L, 'ɚ' to 85L, 'ɛ' to 86L, 'ɜ' to 87L,
            'ɟ' to 90L, 'ɡ' to 92L, 'ɥ' to 99L, 'ɨ' to 101L, 'ɪ' to 102L,
            'ʝ' to 103L, 'ɯ' to 110L, 'ɰ' to 111L, 'ŋ' to 112L, 'ɳ' to 113L,
            'ɲ' to 114L, 'ɴ' to 115L, 'ø' to 116L, 'ɸ' to 118L, 'θ' to 119L,
            'œ' to 120L, 'ɹ' to 123L, 'ɾ' to 125L, 'ɻ' to 126L, 'ʁ' to 128L,
            'ɽ' to 129L, 'ʂ' to 130L, 'ʃ' to 131L, 'ʈ' to 132L, 'ʧ' to 133L,
            'ʊ' to 135L, 'ʋ' to 136L, 'ʌ' to 138L, 'ɣ' to 139L, 'ɤ' to 140L,
            'χ' to 142L, 'ʎ' to 143L, 'ʒ' to 147L, 'ʔ' to 148L,
            // Prosodic markers (156-177)
            'ˈ' to 156L, 'ˌ' to 157L, 'ː' to 158L, 'ʰ' to 162L, 'ʲ' to 164L,
            '↓' to 169L, '→' to 171L, '↗' to 172L, '↘' to 173L, 'ᵻ' to 177L
        )
    }

    /**
     * Tokenizes raw IPA phonemes. Skips unknown characters with a warning.
     * Returns token IDs without start/end padding.
     */
    fun tokenizeRaw(phonemes: String): LongArray {
        val tokens = mutableListOf<Long>()
        for (char in phonemes) {
            val id = VOCAB[char]
            if (id != null) {
                tokens.add(id)
            } else {
                Log.w(TAG, "Unknown phoneme character skipped: '$char' (0x${Integer.toHexString(char.code)})")
            }
        }
        return tokens.toLongArray()
    }

    /**
     * Tokenizes raw IPA phonemes and adds start/end pad tokens (0).
     */
    fun tokenize(phonemes: String): LongArray {
        val raw = tokenizeRaw(phonemes)
        val result = LongArray(raw.size + 2)
        result[0] = PAD_TOKEN_ID
        System.arraycopy(raw, 0, result, 1, raw.size)
        result[result.size - 1] = PAD_TOKEN_ID
        return result
    }

    /**
     * Splits a long phoneme sequence into chunks, each containing <= maxTokens tokens
     * (excluding start/end pads).
     * Splits at the nearest space token (ID 16) below the limit.
     * If no space is found, splits exactly at maxTokens.
     */
    fun tokenizeWithLimit(phonemes: String, maxTokens: Int = 510): List<LongArray> {
        val rawTokens = tokenizeRaw(phonemes)
        if (rawTokens.size <= maxTokens) {
            return listOf(addPadding(rawTokens))
        }

        val chunks = mutableListOf<LongArray>()
        var startIdx = 0
        while (startIdx < rawTokens.size) {
            val remaining = rawTokens.size - startIdx
            if (remaining <= maxTokens) {
                val chunk = rawTokens.copyOfRange(startIdx, rawTokens.size)
                chunks.add(addPadding(chunk))
                break
            }

            // Look for nearest space (16) from (startIdx + maxTokens) backwards
            var splitIdx = startIdx + maxTokens
            var foundSpace = false
            for (i in splitIdx downTo startIdx) {
                if (rawTokens[i] == SPACE_TOKEN_ID) {
                    splitIdx = i
                    foundSpace = true
                    break
                }
            }

            if (!foundSpace) {
                // Fallback: split exactly at maxTokens
                splitIdx = startIdx + maxTokens
            }

            val chunk = rawTokens.copyOfRange(startIdx, splitIdx)
            chunks.add(addPadding(chunk))

            // Skip the space itself if we split on a space
            startIdx = if (foundSpace) splitIdx + 1 else splitIdx
        }

        return chunks
    }

    private fun addPadding(tokens: LongArray): LongArray {
        val result = LongArray(tokens.size + 2)
        result[0] = PAD_TOKEN_ID
        System.arraycopy(tokens, 0, result, 1, tokens.size)
        result[result.size - 1] = PAD_TOKEN_ID
        return result
    }
}
