# Replace NeuTTS with Kokoro-82M ONNX TTS

Replace the existing NeuTTS engine (llama.cpp + ONNX NeuCodec) with Kokoro-82M ONNX for text-to-speech synthesis. Preserves all existing architecture, UI patterns, service plumbing, and public API signatures while swapping the synthesis pipeline internals and replacing voice cloning with a preset voice picker.

## Critical Implementation Constraints

> [!CAUTION]
> **Use `onnx-community/Kokoro-82M-v1.0-ONNX` model ONLY.** Do not use `thewh1teagle/kokoro-onnx` — it has different input tensor names (`tokens` vs `input_ids`) and different filenames (`kokoro-v1_0.onnx` vs `model.onnx`). Inference will silently fail if the wrong model is used.

> [!CAUTION]
> **Voice files are individual raw float32 `.bin` files** from HuggingFace (e.g. `af_heart.bin`). They are NOT NPZ archives, NOT NumPy `.npy` files, NOT PyTorch pickle files. Each `.bin` is a flat binary of raw little-endian float32 values. Load with `FileInputStream → ByteBuffer(LITTLE_ENDIAN) → FloatArray`. Reshape to `[512, 1, 256]`. No ZIP parsing, no header parsing needed.

> [!WARNING]
> **Max 510 phoneme tokens per inference call.** Kokoro's context window is 512 tokens including 2 pad tokens. In `NeuTTSEngine.synthesize()`, if a sentence exceeds 510 tokens after tokenization, split it at the nearest word boundary below the limit and run multiple inference passes. Do NOT truncate silently — split and synthesize all chunks.

> [!WARNING]
> **KokoroTokenizer must silently skip any phoneme character not found in the vocab map.** espeak-ng's IPA output includes characters outside Kokoro's 178-symbol vocab. Do not throw on unknown symbols — log a warning and skip them.

---

## Key Technical Details

### ONNX Model Inputs/Outputs (onnx-community/Kokoro-82M-v1.0-ONNX)

| Input | Type | Shape | Description |
|---|---|---|---|
| `input_ids` | int64 | `[1, N]` | Phoneme token IDs. Padded: `[0, t1, t2, ..., tN, 0]`. Max N+2 = 512. |
| `style` | float32 | `[1, 256]` | Voice style embedding indexed by token sequence length |
| `speed` | float32 | `[1]` or scalar `[]` | Speech rate multiplier. **Inspect model metadata at runtime and handle both shapes.** |

| Output | Type | Shape | Description |
|---|---|---|---|
| `waveform` | float32 | `[1, audio_length]` | Raw PCM waveform at **24,000 Hz** |

### Voice Embedding Files (Individual .bin from HuggingFace)

Each voice is a **separate raw float32 binary file** downloaded from:
```
https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/main/voices/{voice_id}.bin
```

- **Format**: Flat array of little-endian float32 values, no header
- **Size**: `512 × 1 × 256 = 131,072 floats = 524,288 bytes` per voice (~512 KB each)
- **Shape after reshape**: `[512, 1, 256]` — 512 rows, one per possible token sequence length (0–511)
- **Indexing**: `style_vector = voice_data[len(tokens)]` → shape `[1, 256]`, ready for ONNX input
- **Loading**: `FileInputStream → ByteBuffer(ByteOrder.LITTLE_ENDIAN) → FloatArray`

### Phoneme Vocabulary (178 tokens, from hexgrad/Kokoro-82M config.json)

Pad token `$` = 0. Sequence format: `[0, token1, token2, ..., tokenN, 0]`.

```kotlin
val VOCAB: Map<Char, Int> = mapOf(
    // Punctuation (1-17)
    ';' to 1, ':' to 2, ',' to 3, '.' to 4, '!' to 5, '?' to 6,
    '—' to 9, '…' to 10, '"' to 11, '(' to 12, ')' to 13,
    '\u201C' to 14, '\u201D' to 15, ' ' to 16, '\u0303' to 17,
    // Special affricates (18-22)
    'ʣ' to 18, 'ʥ' to 19, 'ʦ' to 20, 'ʨ' to 21, 'ᵝ' to 22,
    // Misc (23-42)
    '\uAB67' to 23, 'A' to 24, 'I' to 25, 'O' to 31, 'Q' to 33,
    'S' to 35, 'T' to 36, 'W' to 39, 'Y' to 41, 'ᵊ' to 42,
    // Lowercase letters (43-68)
    'a' to 43, 'b' to 44, 'c' to 45, 'd' to 46, 'e' to 47,
    'f' to 48, 'h' to 50, 'i' to 51, 'j' to 52, 'k' to 53,
    'l' to 54, 'm' to 55, 'n' to 56, 'o' to 57, 'p' to 58,
    'q' to 59, 'r' to 60, 's' to 61, 't' to 62, 'u' to 63,
    'v' to 64, 'w' to 65, 'x' to 66, 'y' to 67, 'z' to 68,
    // IPA vowels & consonants (69-177)
    'ɑ' to 69, 'ɐ' to 70, 'ɒ' to 71, 'æ' to 72, 'β' to 75,
    'ɔ' to 76, 'ɕ' to 77, 'ç' to 78, 'ɖ' to 80, 'ð' to 81,
    'ʤ' to 82, 'ə' to 83, 'ɚ' to 85, 'ɛ' to 86, 'ɜ' to 87,
    'ɟ' to 90, 'ɡ' to 92, 'ɥ' to 99, 'ɨ' to 101, 'ɪ' to 102,
    'ʝ' to 103, 'ɯ' to 110, 'ɰ' to 111, 'ŋ' to 112, 'ɳ' to 113,
    'ɲ' to 114, 'ɴ' to 115, 'ø' to 116, 'ɸ' to 118, 'θ' to 119,
    'œ' to 120, 'ɹ' to 123, 'ɾ' to 125, 'ɻ' to 126, 'ʁ' to 128,
    'ɽ' to 129, 'ʂ' to 130, 'ʃ' to 131, 'ʈ' to 132, 'ʧ' to 133,
    'ʊ' to 135, 'ʋ' to 136, 'ʌ' to 138, 'ɣ' to 139, 'ɤ' to 140,
    'χ' to 142, 'ʎ' to 143, 'ʒ' to 147, 'ʔ' to 148,
    // Prosodic markers (156-177)
    'ˈ' to 156, 'ˌ' to 157, 'ː' to 158, 'ʰ' to 162, 'ʲ' to 164,
    '↓' to 169, '→' to 171, '↗' to 172, '↘' to 173, 'ᵻ' to 177
)
```

### espeak-ng: Keep for Phonemization

espeak-ng native library stays — it produces IPA phoneme output that maps to the Kokoro vocab above. Remove only llama.cpp from CMake build. Simplify `CMakeLists.txt` to build espeak-ng + JNI bridge only. Simplify `neutts_jni.cpp` to keep only `nativeInitEspeak`, `nativePhonemeize`, `nativeFreeEspeak`.

---

## Proposed Changes

### Data Layer — New Files

---

#### [NEW] [KokoroTokenizer.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/data/tts/KokoroTokenizer.kt)

Pure-Kotlin phoneme-to-token-ID mapper for Kokoro-82M:

- Contains the complete 178-symbol `VOCAB` map shown above (from `config.json`)
- `fun tokenize(phonemes: String): LongArray`:
  1. Iterate over each character in the espeak-ng IPA phoneme string
  2. Look up each character in `VOCAB` → if found, add to token list; if not found, **silently skip** (log warning)
  3. Prepend pad token `0`, append pad token `0`
  4. Return as `LongArray` (int64 for ONNX input)
- `fun tokenizeWithLimit(phonemes: String, maxTokens: Int = 510): List<LongArray>`:
  1. Tokenize the full phoneme string
  2. If total tokens ≤ maxTokens, return single-element list
  3. If exceeds limit, split at nearest space token (ID 16) boundary below the limit
  4. Each chunk gets its own pad tokens `[0, ..., 0]`
- Injected as `@Singleton` via Hilt

---

#### [NEW] [VoiceManager.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/data/tts/VoiceManager.kt)

Manages Kokoro voice embeddings from individual raw `.bin` files:

- **Loading**: `FileInputStream → ByteBuffer(ByteOrder.LITTLE_ENDIAN) → FloatArray` — no ZIP, no headers
- **Reshape**: Raw float array (131,072 floats) → logical shape `[512, 1, 256]`
- **Indexing**: `getEmbedding(voiceId, tokenCount)` → extracts row `tokenCount` → returns `FloatArray(256)`
- Built-in voice registry:

  | Voice ID | Display Name | File |
  |---|---|---|
  | `af_heart` | Heart (US Female) | `af_heart.bin` |
  | `af_bella` | Bella (US Female) | `af_bella.bin` |
  | `am_michael` | Michael (US Male) | `am_michael.bin` |
  | `am_fenrir` | Fenrir (US Male) | `am_fenrir.bin` |
  | `bf_emma` | Emma (UK Female) | `bf_emma.bin` |
  | `bm_george` | George (UK Male) | `bm_george.bin` |

- `fun getEmbedding(voiceId: String, tokenCount: Int): FloatArray` — returns 256-dim float32 vector for the given voice at the given token count index. Clamp `tokenCount` to 0–511.
- `fun getAvailableVoices(): List<VoiceInfo>` — returns `VoiceInfo(id: String, displayName: String)` list
- Lazy-loads and caches each voice's full float array in memory on first access (~512KB per voice)
- Injected as `@Singleton` via Hilt

---

### Data Layer — Modified Files

---

#### [MODIFY] [ModelManager.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/data/tts/ModelManager.kt)

Replace NeuTTS model references with Kokoro model references:

- **Remove**: `ggufFile` / `ggufUrl` (no more llama.cpp GGUF model)
- **Rename**: `onnxFile` → `File(modelsDir, "model.onnx")` (~330MB). **NOT `kokoro-v1_0.onnx`** — that is the thewh1teagle naming convention. The onnx-community repo uses `model.onnx`.
- **Add**: `voicesDir = File(modelsDir, "voices")` — directory for individual voice `.bin` files
- **Add**: List of voice file names to download: `af_heart.bin`, `af_bella.bin`, `am_michael.bin`, `am_fenrir.bin`, `bf_emma.bin`, `bm_george.bin`
- **Update download URLs**:
  - ONNX: `https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/main/onnx/model.onnx`
  - Each voice: `https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/main/voices/{id}.bin`
- **Update `modelsExist()`**: Check `model.onnx` exists AND at least `af_heart.bin` (default voice) exists
- **Update `getRequiredStorageSpaceBytes()`**: ~340MB (330MB ONNX + 6 × 0.5MB voices)
- **Update `downloadModels()`**:
  - Download ONNX model (0%–90% of progress)
  - Download all 6 voice `.bin` files (90%–100% of progress)
- **Update `deleteModels()`**: Delete new Kokoro files + clean up old NeuTTS files (`neutts-air-Q8_0.gguf`, `neucodec-decoder-int8.onnx`)

---

#### [MODIFY] [NeuTTSEngine.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/data/tts/NeuTTSEngine.kt)

**Complete internal rewrite**, preserving public API signatures:

**Keep identical signatures:**
- `suspend fun init(): Boolean`
- `suspend fun synthesize(text: String, speed: Float): Flow<FloatArray>`
- `suspend fun release()`
- `fun hasReferenceVoice(): Boolean` — return `true` always (Kokoro always has a voice)
- `fun setReferenceVoice(tokens: IntArray?)` — no-op stub for compilation compat

**Remove entirely:**
- `companion object` internals: remove `nativeLibLoaded` flag and the verbose try/catch. **Keep `System.loadLibrary("neutts_jni")` unchanged** — same library name, just fewer functions inside it after llama.cpp removal. The `.so` filename comes from the CMake target name `neutts_jni` which is not changing.
- All `external fun native*()` except `nativeInitEspeak`, `nativePhonemeize`, `nativeFreeEspeak`
- `referenceVoiceTokens` field
- `llamaCtxHandle` field
- `encodeReferenceAudio()` method
- `decodeTokens()` method
- Old `copyEspeakDataFromAssets()` — keep but clean up

**New constructor:** `NeuTTSEngine(context, modelManager, kokoroTokenizer, voiceManager, settingsManager)`

**New fields:**
- `currentVoiceData: FloatArray?` — cached full voice embedding array (131,072 floats)
- `currentVoiceId: String` — tracks which voice is loaded

**New `init()` logic:**
1. Check `modelManager.modelsExist()`
2. Initialize espeak-ng (same as before — copy data from assets, call `nativeInitEspeak`)
3. Create `OrtEnvironment` and `OrtSession` for `model.onnx`
4. Try GPU execution provider first (`SessionOptions().addGPU(0)`), fall back to CPU. **Do NOT use NNAPI** — it is deprecated on Android 14+ and unreliable on Snapdragon 8 Gen 3. Wrap the GPU provider attempt in try/catch and silently fall back to CPU-only `SessionOptions` on failure.
5. Load initial voice embedding via `loadVoiceEmbedding(settingsManager.selectedVoiceId.value)`

**New `synthesize()` pipeline:**
```
text
  → nativePhonemeize(text, "en-us")              // espeak-ng IPA output
  → kokoroTokenizer.tokenizeWithLimit(phonemes)   // LongArray chunks, max 510 tokens each
  → for each chunk:
      → clamp tokenCount to 0–511
      → style = voiceManager.getEmbedding(currentVoiceId, tokenCount)
      → create ONNX input tensors:
          input_ids: LongArray → OnnxTensor shape [1, seq_len]
          style: FloatArray → OnnxTensor shape [1, 256]
          speed: Float → OnnxTensor shape [1] or scalar (inspect model metadata at runtime)
      → engineMutex.withLock { ortSession.run() }
      → extract output "waveform" FloatArray
      → emit via Flow
```

**New public method:**
- `fun loadVoiceEmbedding(voiceId: String)` — loads from VoiceManager, updates `currentVoiceData` and `currentVoiceId`. Called by ReadAloudService on voice change.

**Speed tensor handling:**
```kotlin
// Inspect model's speed input metadata at runtime
val speedInfo = session.inputInfo["speed"]?.info as? TensorInfo
val speedTensor = if (speedInfo?.shape?.isEmpty() == true || speedInfo?.shape?.contentEquals(longArrayOf()) == true) {
    // Scalar tensor
    OnnxTensor.createTensor(env, speed)
} else {
    // Shape [1]
    OnnxTensor.createTensor(env, floatArrayOf(speed), longArrayOf(1))
}
```

---

#### [MODIFY] [SettingsManager.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/data/local/SettingsManager.kt)

- **Add**: `private val _selectedVoiceId = MutableStateFlow(prefs.getString("key_selected_voice", "af_heart") ?: "af_heart")`
- **Add**: `val selectedVoiceId: StateFlow<String> = _selectedVoiceId`
- **Add**: `fun setSelectedVoice(id: String)` — writes to prefs + updates flow
- **Keep**: `hasReferenceVoice` / `setHasReferenceVoice` fields in SettingsManager — still referenced by SettingsScreen for display compat. **However, `hasReferenceVoice` is no longer used for flow control anywhere.** In ReadAloudService, all `hasReferenceVoice` checks are removed — voice is always available via Kokoro, no gating needed.

---

### Presentation Layer

---

#### [NEW] [VoicePickerScreen.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/presentation/tts/VoicePickerScreen.kt)

**Replaces VoiceSetupScreen.kt entirely** (delete old file):

- Material3 Scaffold with TopAppBar ("Select Voice", same style/colors as other screens)
- Gradient background matching existing screens
- `LazyColumn` of voice cards, each showing:
  - Voice display name (e.g. "Heart (US Female)") — `titleMedium`, bold
  - Voice ID subtitle (e.g. "af_heart") — `bodySmall`, onSurfaceVariant
  - Trailing checkmark icon (`Icons.Default.Check`) on the currently selected voice, colored green
  - Card uses same `RoundedCornerShape(16.dp)` and `surfaceVariant.copy(alpha=0.4f)` as SettingsScreen cards
- Tapping a card calls `viewModel.selectVoice(voiceId)` then `onBack()`
- No microphone, no recording, no transcription

---

#### [NEW] [VoicePickerViewModel.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/presentation/tts/VoicePickerViewModel.kt)

**Replaces VoiceSetupViewModel.kt entirely** (delete old file):

- `@HiltViewModel` with `@Inject constructor(settingsManager: SettingsManager, voiceManager: VoiceManager)`
- `val selectedVoiceId: StateFlow<String> = settingsManager.selectedVoiceId`
- `val voices: List<VoiceInfo> = voiceManager.getAvailableVoices()`
- `fun selectVoice(id: String)` — calls `settingsManager.setSelectedVoice(id)`

---

#### [MODIFY] [ModelDownloadScreen.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/presentation/tts/ModelDownloadScreen.kt)

Minimal text changes:
- Line 129: `"NeuTTS Air & NeuCodec Models"` → `"Kokoro TTS Voice Model"`
- Line 139: `"~1.1 GB of neural speech modules"` → `"~340 MB of Kokoro voice synthesis model"`

---

#### [MODIFY] [SettingsScreen.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/presentation/settings/SettingsScreen.kt)

- Line 190: Section title `"Text-To-Speech & Voice Cloning"` → `"Text-To-Speech"`
- Line 233: Download subtitle: `"Offline models verified (1.1 GB)"` → `"Kokoro model verified (340 MB)"`, `"Download offline speech modules"` → `"Download Kokoro speech model"`
- Voice card (lines 249-296): Title `"Custom Cloned Voice"` → `"Voice Selection"`. Subtitle: show selected voice display name from ViewModel (e.g. `"Heart (US Female)"`) instead of `"Cloned voice is active"`. Status badge: show voice name instead of `"Active"`/`"Setup"`.
- Expose `selectedVoiceId` from ViewModel for display name lookup

#### [MODIFY] [SettingsViewModel.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/presentation/settings/SettingsViewModel.kt)

- **Add**: `val selectedVoiceId: StateFlow<String> = settingsManager.selectedVoiceId`

---

### Navigation

---

#### [MODIFY] [Navigation.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/Navigation.kt)

- Replace import: `VoiceSetupScreen` → `VoicePickerScreen`
- Route `"voice_setup"` composable body: `VoiceSetupScreen(...)` → `VoicePickerScreen(...)`
- Keep the route string as `"voice_setup"` for backward compat (no deep-link breakage)

---

### Service Layer

---

#### [MODIFY] [ReadAloudService.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/service/ReadAloudService.kt)

Changes to voice loading:

- **Remove**: `cachedReferenceVoiceTokens` field
- **Remove**: `loadReferenceVoiceTokens()` private method (lines 319-341)
- **Remove**: Lines 193-194 (`cachedReferenceVoiceTokens = loadReferenceVoiceTokens()` / `ttsEngine.setReferenceVoice(...)`)
- **Remove**: All `hasReferenceVoice` / `settingsManager.hasReferenceVoice()` checks in this file. Voice is always available via Kokoro — no gating needed. On a fresh install, `hasReferenceVoice` returns `false`, which would cause the old code to skip voice loading entirely — this must not happen.
- **Add** in `startReading()`, after engine init: `ttsEngine.loadVoiceEmbedding(settingsManager.selectedVoiceId.value)` — unconditionally, no `if` gate.
- **Add** in `onCreate()`: Collect `settingsManager.selectedVoiceId` in a coroutine — on change, call `ttsEngine.loadVoiceEmbedding(newId)` to hot-swap voice without service restart. Voice change takes effect on the next sentence.

---

### DI Layer

---

#### [MODIFY] [TtsModule.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/core/di/TtsModule.kt)

- **Add**: `@Provides @Singleton fun provideKokoroTokenizer(): KokoroTokenizer = KokoroTokenizer()`
- **Add**: `@Provides @Singleton fun provideVoiceManager(@ApplicationContext context: Context, modelManager: ModelManager): VoiceManager = VoiceManager(context, modelManager)`
- **Update** `provideNeuTTSEngine()` signature: add `KokoroTokenizer`, `VoiceManager`, `SettingsManager` params → `NeuTTSEngine(context, modelManager, kokoroTokenizer, voiceManager, settingsManager)`

---

### Build Config & Native Code

---

#### [MODIFY] [build.gradle.kts](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/build.gradle.kts)

- **Keep**: `ndkVersion`, `ndk { abiFilters }` — still needed for espeak-ng native build
- **Keep**: Top-level `externalNativeBuild { cmake { path(...) } }` — still needed
- **Remove** from `defaultConfig.externalNativeBuild.cmake`: `arguments += listOf("-DANDROID_STL=c++_shared")` — espeak-ng is pure C, doesn't need C++ STL (evaluate if this can be removed or if it's harmless to keep)
- **Keep**: ONNX Runtime dependency (already present)
- **No new dependencies needed**

#### [MODIFY] [CMakeLists.txt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/cpp/CMakeLists.txt)

- **Remove**: All `LLAMA_BUILD_*` cache variables (lines 9-14)
- **Remove**: `add_subdirectory(llama.cpp)` (line 16)
- **Remove** from `target_include_directories(neutts_jni)`: `llama.cpp/include`, `llama.cpp/ggml/include`
- **Remove** from `target_link_libraries(neutts_jni)`: `llama`
- **Keep**: Entire espeak-ng static library build (lines 18-75)
- **Keep**: `neutts_jni` shared library build, but only linking espeak-ng + log

#### [MODIFY] [neutts_jni.cpp](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/cpp/neutts_jni.cpp)

- **Remove**: `#include "llama.h"`
- **Remove**: `NeuLlamaContext` struct
- **Remove**: `nativeInitLlama()` function
- **Remove**: `nativeGenerate()` function
- **Remove**: `nativeEncodeAudio()` function
- **Remove**: `nativeFreeLlama()` function
- **Keep**: `#include "espeak-ng/speak_lib.h"`
- **Keep**: `nativeInitEspeak()` — unchanged
- **Keep**: `nativePhonemeize()` — unchanged
- **Keep**: `nativeFreeEspeak()` — unchanged

---

## Verification Plan

### Automated Tests
```bash
# Build — must compile with zero errors
./gradlew assembleDebug

# Verify no old references remain
grep -rn "llama.cpp\|NeuCodec\|voice cloning\|nativeGenerate\|nativeInitLlama\|nativeFreeLlama\|nativeEncodeAudio\|referenceVoiceTokens\|encodeReferenceAudio" \
  app/src/main/java/ \
  --include="*.kt" \
  | grep -v "// " | grep -v "TAG ="
# Should return nothing
# Note: "NeuTTSEngine" class name and "neutts_jni" library name are intentionally kept — they refer to
# the slimmed-down engine and native lib that now only contain espeak-ng + Kokoro ONNX inference.

# Run existing unit tests
./gradlew test
```

### Manual Verification
1. **Model Download**: Settings → Download → Progress shows correctly → Downloads ~340MB ONNX + 6 voice files
2. **Voice Picker**: Settings → Voice Selection → 6 voices listed → Select "Michael" → Checkmark moves → Kill app → Reopen → Michael still selected
3. **TTS Playback**: Open PDF → Play → Audio plays at 24kHz → Natural Kokoro speech
4. **Voice Hot-Swap**: While playing → Open voice picker → Switch to "Emma" → Next sentence uses Emma's voice without service restart
5. **Long Sentence**: Find a very long PDF sentence (>510 tokens after phonemization) → Verify it plays fully without crash (split into chunks)
6. **Speed Control**: Change playback speed slider → Audio respects speed parameter
