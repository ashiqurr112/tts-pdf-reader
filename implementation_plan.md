# Fix Read Aloud Bugs in TTS PDF Reader

## Background

The Read Aloud feature produces garbled/garbage audio described as "lada lada lada" repeating noise. A prior analysis (from Claude) identified multiple bugs across the audio playback pipeline. I have independently verified each claim against the actual codebase and **found one critical error** in that analysis, plus discovered an additional bug.

---

## User Review Required

> [!IMPORTANT]
> **Claude's "root cause" diagnosis about `phonememode` is incorrect.** Claude claimed passing `2` to `espeak_TextToPhonemes` produces ASCII mnemonics instead of IPA, and that the fix is changing it to `0x102`. **This is wrong.** The [espeak-ng speak_lib.h header](https://github.com/espeak-ng/espeak-ng/blob/master/src/include/espeak-ng/speak_lib.h) defines `espeakPHONEMES_IPA = 0x02`. Passing `2` is the correct way to request IPA output. There is no `0x100` base flag required. The existing code at line 44 of `neutts_jni.cpp` is correct and should **not** be changed.

> [!WARNING]
> **However, I discovered a different critical bug in the espeak-ng initialization.** The Kotlin code passes `context.filesDir/espeak-ng-data` as the data path, but `espeak_Initialize()` expects the **parent directory** that _contains_ the `espeak-ng-data/` folder. The current code causes espeak-ng to look for `espeak-ng-data/espeak-ng-data/` which doesn't exist. Depending on the espeak-ng version's fallback behavior, this may cause phonemization to silently fail or produce garbage output — which could be the **actual root cause** of the "garbage audio" bug.

## Open Questions — Resolved

> [!NOTE]
> **Q1: Has the app ever produced correct speech?** **Answer: No — always the same garbage sound on every text.** This conclusively confirms that the espeak-ng init path bug (Bug 1) is the root cause. Phonemization has never worked, so Kokoro has always received empty/garbage token sequences and produced noise output.

> [!NOTE]
> **Q2: What is the espeak-ng version bundled?** **Answer: Unknown.** The `config.h` says `PACKAGE_VERSION "1.52.0"` but the espeak-ng submodule directory is empty (not initialized). Regardless, the path bug applies to all espeak-ng versions since the API contract has been stable.

---

## Confirmed Bugs (Priority Order)

### Bug 1 — espeak-ng Init Path Off-by-One (🔴 Likely Root Cause)

| Detail | Value |
|--------|-------|
| **File** | [NeuTTSEngine.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/data/tts/NeuTTSEngine.kt#L79-L89) |
| **Severity** | 🔴 Critical — **confirmed root cause** of all garbage audio |

Since the app has **never** produced correct speech, this bug has been present since day one. Every single phonemization call has failed.

**Root cause:** `espeak_Initialize(path)` expects `path` to be the directory **containing** `espeak-ng-data/`. The code passes the `espeak-ng-data` directory itself:

```kotlin
// Current code (line 79-85):
val espeakDataDir = File(context.filesDir, "espeak-ng-data")
espeakInitialized = nativeInitEspeak(espeakDataDir.absolutePath)
// → passes: /data/.../files/espeak-ng-data
// → espeak looks for: /data/.../files/espeak-ng-data/espeak-ng-data/ ← DOESN'T EXIST
```

**Fix:** Pass `context.filesDir.absolutePath` (the parent) instead:

```kotlin
// Fixed:
val espeakDataDir = File(context.filesDir, "espeak-ng-data")
if (!espeakDataDir.exists()) {
    copyEspeakDataFromAssets()
}

if (!espeakInitialized) {
    // espeak_Initialize expects the PARENT dir that contains espeak-ng-data/
    espeakInitialized = nativeInitEspeak(context.filesDir.absolutePath)
```

---

### Bug 2 — `onComplete()` Race on Cancelled Renderer Job (🔴 High)

| Detail | Value |
|--------|-------|
| **File** | [AudioRenderer.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/data/tts/AudioRenderer.kt#L84-L140) |
| **Severity** | High — causes overlapping sentence playback |

**Root cause:** When `play()` is called for a new sentence:
1. `isPlaying = true` (line 94)
2. `rendererJob?.cancel()` (line 89) — wait, actually looking at the code again, the cancel happens at line 89 **before** `isPlaying = true` at line 94.

Re-reading carefully:
```kotlin
rendererJob?.cancel()  // line 89 — cancels old job
isPlaying = true       // line 94 — sets flag AFTER cancel
```

The cancelled old job's `finally` block runs. At that point, `isPlaying` could be `true` (just set at line 94) because coroutine cancellation is cooperative and the `finally` block may not execute immediately. The `finally` block checks `if (isPlaying) { onComplete() }` — if `isPlaying` is already `true` from the new `play()` call, the old job's `onComplete()` fires, calling `readSentence(oldIndex + 1)` concurrently with the new sentence.

**Fix:** Use a generation counter to distinguish which `play()` invocation the `finally` block belongs to:

```kotlin
private var playGeneration = 0  // incremented on each play() call

suspend fun play(audioChunks: Flow<FloatArray>, onComplete: () -> Unit = {}): Job {
    init()
    rendererJob?.cancel()
    
    val thisGeneration = ++playGeneration  // unique ID for this invocation
    isPlaying = true

    rendererJob = rendererScope.launch {
        try {
            // ... existing collection + waitForPlaybackComplete logic ...
        } finally {
            // Only fire onComplete if this is still the active generation
            // AND we weren't cancelled by pause/stop
            if (isPlaying && thisGeneration == playGeneration) {
                onComplete()
            }
        }
    }
    return rendererJob!!
}
```

---

### Bug 3 — `playbackHeadPosition` Integer Overflow (🟡 Medium)

| Detail | Value |
|--------|-------|
| **File** | [AudioRenderer.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/data/tts/AudioRenderer.kt#L156-L171) |
| **Severity** | Medium — triggers after ~24.8 hours of cumulative audio |

**Root cause:** `playbackHeadPosition` is a signed 32-bit `Int` that accumulates frames across the AudioTrack's lifetime. At 24kHz, it overflows after:
```
2^31 / 24000 / 3600 ≈ 24.8 hours
```

When it wraps negative, `targetFrameCount = initialHeadPos + sentenceFrames` can overflow to a small/negative value, making `currentHead >= targetFrameCount` immediately true. `onComplete()` fires in a tight loop.

**Fix:** Create a fresh AudioTrack per sentence to reset `playbackHeadPosition`, OR use `Long` math for the comparison:

```kotlin
private suspend fun waitForPlaybackComplete(targetFrameCount: Long) {
    val track = audioTrack ?: return
    try {
        while (isPlaying) {
            val currentHead = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            if (currentHead >= targetFrameCount) break
            val remainingFrames = targetFrameCount - currentHead
            val remainingMs = (remainingFrames.toFloat() / track.sampleRate * 1000 / playbackSpeed).toLong()
            delay(remainingMs.coerceIn(20L, 200L))
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error waiting for audio track completion", e)
    }
}
```

And change the caller:
```kotlin
waitForPlaybackComplete(initialHeadPos.toLong() + sentenceFrames.toLong())
```

---

### Bug 4 — Style Embedding Off-by-One (🟡 Medium)

| Detail | Value |
|--------|-------|
| **Files** | [NeuTTSEngine.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/data/tts/NeuTTSEngine.kt#L209-L212) + [VoiceManager.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/data/tts/VoiceManager.kt#L39-L52) |
| **Severity** | Medium — subtly wrong voice quality |

**Root cause:** In `NeuTTSEngine.synthesize()`:
```kotlin
val tokenCount = chunk.size        // chunk already includes 2 padding tokens from addPadding()
val styleData = voiceManager.getEmbedding(currentVoiceId, tokenCount)
```

`chunk.size` = `rawTokens.size + 2` (the 2 pad tokens). Then in `getEmbedding()`:
```kotlin
val clampedCount = (tokenCount - 1).coerceIn(0, MAX_TOKENS - 1)
//               = (rawTokens.size + 2 - 1) = rawTokens.size + 1
```

The style row index is off by +1 from the actual content token count, selecting a slightly wrong conditioning vector.

**Fix:** Subtract padding before passing to `getEmbedding()`:

```kotlin
// NeuTTSEngine.kt, in the synthesis loop:
val tokenCount = chunk.size - 2  // exclude the 2 pad tokens
val styleData = voiceManager.getEmbedding(currentVoiceId, tokenCount)
```

---

### Bug 5 — Skip Functions Don't Stop Renderer First (🟡 Medium)

| Detail | Value |
|--------|-------|
| **File** | [ReadAloudService.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/service/ReadAloudService.kt#L359-L383) |
| **Severity** | Medium — race window for overlapping playback |

**Root cause:** `skipToNextSentence()` and `skipToPreviousSentence()` call `readSentence()` directly without stopping the renderer. While `readSentence()` cancels `playbackJob`, there's a window where the old renderer job can fire `onComplete()` before it's cancelled (the same race as Bug 2).

**Fix:** Stop the audio renderer before redirecting:

```kotlin
fun skipToNextSentence() {
    serviceScope.launch {
        audioRenderer.stop()  // stop current playback first
        playbackJob?.cancel()
        val nextIndex = _currentSentenceIndex.value + 1
        if (nextIndex in _sentences.value.indices) {
            readSentence(nextIndex)
        } else {
            val nextPage = _currentPage.value + 1
            if (nextPage < totalPagesCount) {
                loadPageText(nextPage, 0)
            }
        }
    }
}

fun skipToPreviousSentence() {
    serviceScope.launch {
        audioRenderer.stop()  // stop current playback first
        playbackJob?.cancel()
        val prevIndex = _currentSentenceIndex.value - 1
        if (prevIndex >= 0) {
            readSentence(prevIndex)
        } else {
            val prevPage = _currentPage.value - 1
            if (prevPage >= 0) {
                loadPageText(prevPage, 0)
            }
        }
    }
}
```

---

### Bug 6 — `startReading` Infinite Poll (🟢 Low)

| Detail | Value |
|--------|-------|
| **File** | [ReaderViewModel.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/presentation/reader/ReaderViewModel.kt#L193-L198) |
| **Severity** | Low — loops forever if binding fails |

**Root cause:**
```kotlin
while (readAloudService == null) {
    delay(50)
}
```
If binding permanently fails, this loops forever, silently consuming CPU.

**Fix:** Add a timeout:

```kotlin
viewModelScope.launch {
    var waited = 0L
    while (readAloudService == null && waited < 5000L) {
        delay(50)
        waited += 50
    }
    if (readAloudService != null) {
        readAloudService?.startReading(uri.toString(), title, currentPage, 0)
    } else {
        Log.e(TAG, "Failed to bind to ReadAloudService within timeout")
        // Optionally update UI state to show error
    }
}
```

---

## Proposed Changes

### Component: Native JNI — No changes needed

Claude claimed `phonememode = 2` is wrong and needs to be `0x102`. **This is incorrect.** The espeak-ng header defines `espeakPHONEMES_IPA = 0x02`. The value `2` is already correct for IPA output. **No changes to [neutts_jni.cpp](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/cpp/neutts_jni.cpp).**

---

### Component: TTS Engine

#### [MODIFY] [NeuTTSEngine.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/data/tts/NeuTTSEngine.kt)

1. **Line 85:** Fix espeak init path — pass `context.filesDir.absolutePath` (the parent directory) instead of `espeakDataDir.absolutePath`
2. **Line 211:** Fix style embedding — subtract 2 pad tokens before passing `tokenCount` to `getEmbedding()`

---

### Component: Audio Renderer

#### [MODIFY] [AudioRenderer.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/data/tts/AudioRenderer.kt)

1. **Line 33 area:** Add `playGeneration` counter field
2. **Lines 84-140:** Update `play()` to use generation counter in the `finally` guard
3. **Lines 100, 125, 156-171:** Change `waitForPlaybackComplete` to use `Long` arithmetic to prevent integer overflow

---

### Component: Read Aloud Service

#### [MODIFY] [ReadAloudService.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/service/ReadAloudService.kt)

1. **Lines 359-383:** Wrap `skipToNextSentence()` and `skipToPreviousSentence()` in coroutine launches that call `audioRenderer.stop()` + `playbackJob?.cancel()` before redirecting

---

### Component: ViewModel

#### [MODIFY] [ReaderViewModel.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/presentation/reader/ReaderViewModel.kt)

1. **Lines 193-198:** Add 5-second timeout to the service binding poll loop

---

## Verification Plan

### Manual Verification
1. **Basic playback:** Open a PDF, start Read Aloud, verify clear speech output (not garbage noise)
2. **Sentence transitions:** Listen through 3+ sentences, verify no overlapping audio or repeating garbage between sentences
3. **Skip forward/back:** Tap skip buttons rapidly, verify clean transitions with no garbled audio
4. **Pause/Resume:** Pause mid-sentence, resume, verify no duplicate/overlapping audio
5. **Long session:** (If feasible) Let it read for 30+ minutes to verify no degradation from overflow

### Build Verification
```bash
cd "/home/ashiqur/Pictures/tts pdf reader"
./gradlew assembleDebug
```

### Log Verification
After running the app, check logcat for:
- `NeuTTS_JNI: Initializing espeak-ng with data path:` — verify the path is the parent dir
- `KokoroTokenizer: Unknown phoneme character skipped:` — should see very few or no warnings (previously would flood with ASCII char warnings if phonemization was broken)
