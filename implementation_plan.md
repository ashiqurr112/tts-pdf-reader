# Implementation Plan — Fix Kokoro G2P Phoneme Mismatch

## User Review Required

> [!IMPORTANT]
> The primary bug preventing the TTS engine from producing clear speech is that the raw `espeak-ng` IPA output is not mapped to the custom phoneme set expected by the Kokoro model (which was trained on the `Misaki G2P` library format).
>
> To fix this, we need to introduce a translation function that processes the raw espeak phoneme string before tokenization.

## Proposed Changes

### TTS Engine Components

We will add the translation logic in [NeuTTSEngine.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/data/tts/NeuTTSEngine.kt) immediately after fetching the raw phonemes from the JNI method.

---

#### [MODIFY] [NeuTTSEngine.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/data/tts/NeuTTSEngine.kt)

1. Modify `synthesize()` to run the raw phonemes through a new private helper method `translatePhonemes()`.
2. Add the `translatePhonemes()` helper function with the regex and string replacements derived from `Misaki`'s `EN_PHONES.md` specification.

##### Proposed Code Additions:

```kotlin
// In NeuTTSEngine.kt, update the synthesize flow:

        val rawPhonemes = withContext(nativeDispatcher) {
            try {
                nativePhonemeize(text, "en-us")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "nativePhonemeize failed - library issue", e)
                ""
            }
        }

        if (rawPhonemes.isBlank()) {
            return@flow
        }

        // Translate raw espeak-ng IPA to Misaki/Kokoro phoneme format
        val phonemes = translatePhonemes(rawPhonemes, british = false)

        // Tokenize IPA phonemes with KokoroTokenizer (limit to 510 tokens per chunk)
        val chunks = kokoroTokenizer.tokenizeWithLimit(phonemes)
```

And add the helper method:

```kotlin
    /**
     * Translates raw espeak-ng IPA output into the custom phonemes expected by Kokoro (Misaki G2P).
     * Maps diphthongs to uppercase letters (e.g. eɪ -> A) and normalizes consonants/vowels.
     */
    private fun translatePhonemes(ps: String, british: Boolean = false): String {
        var result = ps

        // 1. Remove nasalization tilde (U+0303)
        result = result.replace("\u0303", "")

        // 2. Normalize/remove tie characters (U+0361, U+035C, and caret ^) to simplify matching
        result = result.replace("\u0361", "")
        result = result.replace("\u035C", "")
        result = result.replace("^", "")

        // 3. Handle syllabic consonants (e.g., n followed by U+0329 combining vertical line below becomes ᵊn)
        result = result.replace(Regex("([^\\s])\\u0329"), "\u1d4a$1") // \u1d4a is superscript schwa ᵊ
        result = result.replace("\u0329", "")

        // 4. Mappings from espeak to Misaki (ordered by length descending)
        val replacements = listOf(
            "aɪ" to "I",
            "aʊ" to "W",
            "dʒ" to "ʤ",
            "tʃ" to "ʧ",
            "eɪ" to "A",
            "ɔɪ" to "Y",
            "əl" to "\u1d4al",   // ᵊl
            "ʔn" to "t\u1d4an",  // tᵊn
            "ʲO" to "jO",
            "ʲQ" to "jQ"
        )
        for ((old, new) in replacements) {
            result = result.replace(old, new)
        }

        // 5. Single-character replacements (ordered by length descending)
        val singleReplacements = listOf(
            "e" to "A",
            "r" to "ɹ",
            "x" to "k",
            "ç" to "k",
            "ɐ" to "ə",
            "ɚ" to "əɹ",
            "ɬ" to "l",
            "ʔ" to "t",
            "ʲ" to ""
        )
        for ((old, new) in singleReplacements) {
            result = result.replace(old, new)
        }

        // 6. Language/Dialect specific adjustments
        if (british) {
            result = result.replace("eə", "ɛː")
            result = result.replace("iə", "ɪə")
            result = result.replace("əʊ", "Q")
        } else {
            result = result.replace("oʊ", "O")
            result = result.replace("ɜːɹ", "ɜɹ")
            result = result.replace("ɜː", "ɜɹ")
            result = result.replace("ɪə", "iə")
            result = result.replace("ː", "") // Strip vowel length marker in US English
        }

        return result
    }
```

---

## Verification Plan

### Automated Tests
- Build and run the project locally to confirm compilation succeeds.
  ```bash
  ./gradlew assembleDebug
  ```

### Manual Verification
1. Install the built APK on a device.
2. Select a PDF file and run the **Read Aloud** feature.
3. Check `logcat` for `Unknown phoneme character skipped` warnings from `KokoroTokenizer`. The warning count should drop to zero or near-zero.
4. Verify that the TTS engine outputs **clear, recognizable English speech** instead of garbage noise.
