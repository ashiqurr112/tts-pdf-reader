# Phase 2: TTS & Voice Cloning Integration

End-to-end on-device text-to-speech with voice cloning for the TTS PDF Reader Android app. The pipeline converts PDF text to natural speech using NeuTTS Air (LLM backbone) and NeuCodec (neural audio codec), with optional voice cloning from a short reference audio clip.

## User Review Required

> [!IMPORTANT]
> **Architecture deviation from original spec:** Research reveals NeuCodec has an official ONNX decoder variant (`neucodec-onnx-decoder-int8`, ~312MB) which is far more practical than compiling the full PyTorch model (~2.3GB) into a native `.so`. The plan uses **ONNX Runtime Android** for NeuCodec and **native NDK** only for llama.cpp + eSpeak-NG.

> [!WARNING]
> **Voice cloning limitation:** The NeuCodec **encoder** (needed to convert reference audio → codec tokens for voice cloning) is only available as PyTorch (`pytorch_model.bin`, 2.3GB). It is NOT available in ONNX format. Options:
> 1. **(Recommended)** Export the encoder to ONNX ourselves using a Python script, then bundle the ONNX encoder on-device (~small, encoder is much lighter than decoder)
> 2. Pre-compute reference embeddings via a one-time server call
> 3. Defer voice cloning to a future phase and ship TTS with a built-in default voice first
>
> **Please decide which approach you prefer.**

> [!WARNING]
> **Total model download size:** ~1.1 GB (803 MB GGUF + 312 MB ONNX decoder). Models must be downloaded on first use. The app will need a model download manager with progress UI, Wi-Fi-only option, and retry logic.

> [!CAUTION]
> **Device requirements:** Running a Qwen 0.5B model on-device requires 4GB+ RAM and ARM64 (arm64-v8a) CPU. Older/low-end devices may experience very slow inference or OOM crashes. We should add a device capability check.

## Open Questions

1. **Voice cloning approach:** Which of the 3 encoder options above do you prefer? (Recommended: option 1 — export encoder to ONNX)
2. **Model download:** Should models be bundled in the APK (massive APK size) or downloaded on first use? (Recommended: download on first use with progress UI)
3. **Q4 vs Q8:** Should we also offer the Q4 variant (~400MB, faster but lower quality) as a "lite" option?
4. **eSpeak-NG data files:** eSpeak needs ~5MB of language data files. Bundle in assets or download?

---

## Inference Pipeline Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                    ReadAloudService                           │
│                  (Foreground Service)                         │
│                                                              │
│  PDF Text ──► SentenceChunker ──► Queue<Sentence>            │
│                                       │                      │
│                                       ▼                      │
│                              ┌─────────────────┐            │
│                              │  eSpeak-NG       │            │
│                              │  (libespeak.so)  │            │
│                              │  JNI Bridge      │            │
│                              └────────┬────────┘            │
│                                       │ IPA phonemes         │
│                                       ▼                      │
│                              ┌─────────────────┐            │
│                              │  llama.cpp       │            │
│                              │  (libllama.so)   │            │
│                              │  NeuTTS GGUF     │            │
│                              │  JNI Bridge      │            │
│                              └────────┬────────┘            │
│                                       │ codec tokens (int[]) │
│                                       ▼                      │
│                              ┌─────────────────┐            │
│                              │  NeuCodec ONNX   │            │
│                              │  (ORT Android)   │            │
│                              │  Decoder int8    │            │
│                              └────────┬────────┘            │
│                                       │ float[] waveform     │
│                                       ▼                      │
│                              ┌─────────────────┐            │
│                              │  AudioRenderer   │            │
│                              │  (AudioTrack)    │            │
│                              │  24kHz PCM       │            │
│                              └─────────────────┘            │
└──────────────────────────────────────────────────────────────┘
```

---

## Proposed Changes

### Component 1: NDK Build System

Set up CMake + NDK to compile llama.cpp and eSpeak-NG as shared native libraries.

#### [NEW] [CMakeLists.txt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/cpp/CMakeLists.txt)
- Top-level CMake file for the native build
- Configures llama.cpp as a static/shared library target
- Configures eSpeak-NG as a static/shared library target
- Creates `libneutts_jni.so` — the final JNI bridge library that links both
- Target ABI: `arm64-v8a` only (to minimize APK size)
- Compiler flags: `-march=armv8.4a -O3 -DNDEBUG` for NEON optimization

#### [NEW] [neutts_jni.cpp](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/cpp/neutts_jni.cpp)
- JNI bridge functions exposed to Kotlin:
  - `nativeInitEspeak(dataPath: String)` → initialize eSpeak-NG with data directory
  - `nativePhonemeize(text: String, lang: String): String` → text → IPA phonemes
  - `nativeInitLlama(modelPath: String, nThreads: Int): Long` → load GGUF model, return context handle
  - `nativeGenerate(ctx: Long, phonemes: String, refTokens: IntArray?, speed: Float): IntArray` → generate codec tokens
  - `nativeFreeLlama(ctx: Long)` → release model memory
  - `nativeFreeEspeak()` → release eSpeak resources

#### [MODIFY] [build.gradle.kts](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/build.gradle.kts)
- Add `externalNativeBuild { cmake { path("src/main/cpp/CMakeLists.txt") } }`
- Add `ndkVersion` specification
- Add `ndk { abiFilters += "arm64-v8a" }`
- Add ONNX Runtime dependency: `implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.0")`
- Add coroutines dependency for background processing

---

### Component 2: eSpeak-NG Integration

#### [NEW] `app/src/main/cpp/espeak-ng/` (git submodule or source copy)
- eSpeak-NG source code for NDK compilation
- Only the core library (no GUI, no standalone binary)
- Language data files for English (bundled in `assets/espeak-ng-data/`)

#### [NEW] [assets/espeak-ng-data/](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/assets/espeak-ng-data/)
- English phoneme data, dictionary files, intonation data (~5MB)
- Copied from eSpeak-NG build output

---

### Component 3: llama.cpp Integration

#### [NEW] `app/src/main/cpp/llama.cpp/` (git submodule)
- llama.cpp source code
- Only core inference library (ggml, llama) — no examples, no server
- Compiled with NEON/ARM64 optimizations, OpenMP disabled

---

### Component 4: Model Management

#### [NEW] [ModelManager.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/data/tts/ModelManager.kt)
- Singleton responsible for downloading and managing TTS models
- Downloads from HuggingFace CDN:
  - `neutts-air-Q8_0.gguf` → `filesDir/models/neutts-air-Q8_0.gguf`
  - `model.onnx` (neucodec int8) → `filesDir/models/neucodec-decoder-int8.onnx`
- Features:
  - Progress tracking via `StateFlow<DownloadState>` (idle, downloading with %, complete, error)
  - Resumable downloads (HTTP Range header)
  - Wi-Fi-only option
  - SHA256 verification after download
  - Automatic cleanup of partial downloads

#### [NEW] [ModelDownloadScreen.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/presentation/tts/ModelDownloadScreen.kt)
- UI for model download with animated progress
- Shows individual model sizes and total
- Download/Cancel/Retry buttons
- Wi-Fi-only toggle
- Storage space check before download

---

### Component 5: TTS Engine (Kotlin Layer)

#### [NEW] [NeuTTSEngine.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/data/tts/NeuTTSEngine.kt)
- Main TTS engine class wrapping native calls + ONNX Runtime
- `init(context: Context)`: Loads native libraries, initializes eSpeak, loads GGUF model, creates ORT session
- `synthesize(text: String, speed: Float = 1.0f): Flow<FloatArray>`: Streaming synthesis
  1. Phonemize text via eSpeak JNI
  2. Generate codec tokens via llama.cpp JNI (streaming, emit chunks of ~50 tokens = ~1 second)
  3. Decode tokens → waveform via ONNX Runtime
  4. Emit waveform chunks as `Flow<FloatArray>`
- `setReferenceVoice(wavPath: String, transcription: String)`: Load reference audio for voice cloning
- `release()`: Free all resources
- Thread safety: All native calls dispatched to a dedicated single-thread dispatcher

#### [NEW] [SentenceChunker.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/data/tts/SentenceChunker.kt)
- Splits PDF page text into sentences using ICU BreakIterator
- Returns `List<Sentence>` where `Sentence` has `text`, `startOffset`, `endOffset`
- Handles abbreviations, decimals, and other edge cases

#### [NEW] [AudioRenderer.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/data/tts/AudioRenderer.kt)
- Manages `AudioTrack` for PCM playback
- Configuration: 24kHz, mono, ENCODING_PCM_FLOAT
- `play(audioChunks: Flow<FloatArray>)`: Streams audio chunks to AudioTrack
- `pause()` / `resume()` / `stop()`: Playback controls
- `setSpeed(speed: Float)`: Adjusts playback speed (0.75x–2.0x) using AudioTrack playback params
- Double-buffering for smooth playback

---

### Component 6: ReadAloud Foreground Service

#### [NEW] [ReadAloudService.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/service/ReadAloudService.kt)
- `Foreground Service` with persistent notification showing:
  - Current sentence being read
  - Play/Pause/Stop media buttons
  - Book title
- Manages the full pipeline: `SentenceChunker → NeuTTSEngine → AudioRenderer`
- Communicates with UI via bound service or `StateFlow` (shared ViewModel)
- Handles:
  - Page transitions (reads current page, auto-advances)
  - Sentence tracking (reports current sentence index back to UI)
  - Speed changes in real-time
  - Audio focus management
  - Headphone disconnect → auto-pause
  - Wakelock to prevent sleep during reading

#### [MODIFY] [AndroidManifest.xml](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/AndroidManifest.xml)
- Add `FOREGROUND_SERVICE` permission
- Add `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission
- Add `WAKE_LOCK` permission
- Add `INTERNET` permission (for model download)
- Register `ReadAloudService` with `foregroundServiceType="mediaPlayback"`
- Add `POST_NOTIFICATIONS` permission (Android 13+)

---

### Component 7: Reader UI Enhancements

#### [MODIFY] [ReaderScreen.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/presentation/reader/ReaderScreen.kt)
- Add TTS control bar at bottom of reader:
  - Play/Pause FAB (animated icon transition)
  - Speed slider (0.75x – 2.0x) in expandable bottom sheet
  - Current sentence indicator / progress
- Sentence highlighting overlay (subtle background highlight on current sentence)
- Connect to `ReadAloudService` state

#### [MODIFY] [ReaderViewModel.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/presentation/reader/ReaderViewModel.kt)
- Add TTS state management:
  - `ttsState: StateFlow<TtsUiState>` (idle, loading, playing, paused, error)
  - `currentSentenceIndex: StateFlow<Int>`
  - `playbackSpeed: StateFlow<Float>`
- Actions: `startReading()`, `pauseReading()`, `resumeReading()`, `stopReading()`, `setSpeed(Float)`
- Service binding/unbinding lifecycle

---

### Component 8: Voice Cloning (Reference Audio)

#### [NEW] [VoiceSetupScreen.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/presentation/tts/VoiceSetupScreen.kt)
- Record or import 3-15 second reference audio clip
- Waveform visualizer during recording
- Transcription input field (user types what they said)
- Save to `filesDir/voice/reference.wav`
- Preview button to test cloned voice

#### [NEW] [VoiceSetupViewModel.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/presentation/tts/VoiceSetupViewModel.kt)
- Recording state management
- Audio format enforcement (16kHz, mono, 16-bit PCM)
- Duration validation (3-15 seconds)

---

### Component 9: Navigation & Settings Updates

#### [MODIFY] [Navigation.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/Navigation.kt)
- Add routes: `model_download`, `voice_setup`
- Model download accessible from Settings or first TTS attempt

#### [MODIFY] [SettingsScreen.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/presentation/settings/SettingsScreen.kt)
- Add "TTS Settings" section:
  - Model download status & management
  - Voice setup (record/import reference audio)
  - Default speed preference
  - Auto-read on open toggle

#### [MODIFY] [SettingsManager.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/data/local/SettingsManager.kt)
- Add TTS preferences: `ttsSpeed`, `autoReadOnOpen`, `hasDownloadedModels`, `hasReferenceVoice`

---

### Component 10: DI Updates

#### [MODIFY] [DatabaseModule.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/core/di/DatabaseModule.kt) or [NEW] TtsModule.kt
- Provide `NeuTTSEngine` as singleton
- Provide `ModelManager` as singleton
- Provide `SentenceChunker`
- Provide `AudioRenderer`

---

## Implementation Phases

### Phase 2A: Foundation (NDK + Model Management)
1. Set up NDK build with CMake
2. Compile llama.cpp for arm64
3. Compile eSpeak-NG for arm64
4. Write JNI bridge (`neutts_jni.cpp`)
5. Implement `ModelManager` with download UI
6. Verify native libraries load successfully on device

### Phase 2B: Core TTS Pipeline
1. Implement `NeuTTSEngine` (phonemize → generate → decode)
2. Implement `SentenceChunker`
3. Implement `AudioRenderer` with AudioTrack streaming
4. End-to-end test: text in → audio out

### Phase 2C: Service & UI Integration
1. Implement `ReadAloudService` foreground service
2. Add TTS controls to `ReaderScreen`
3. Implement sentence highlighting
4. Add speed control
5. Wire up service ↔ ViewModel communication

### Phase 2D: Voice Cloning & Polish
1. Implement `VoiceSetupScreen` with recording
2. Integrate reference voice with NeuTTSEngine
3. Add TTS settings section
4. Navigation updates
5. Error handling, edge cases, device compatibility checks

---

## Verification Plan

### Automated Tests
```bash
# Build the native libraries
cd "/home/ashiqur/Pictures/tts pdf reader"
./gradlew assembleDebug

# Run unit tests
./gradlew testDebugUnitTest
```

### Manual Verification
- **NDK Build:** Verify `libneutts_jni.so` is generated in APK for arm64-v8a
- **Model Download:** Test download progress, pause/resume, Wi-Fi check
- **eSpeak Phonemization:** Input "Hello world" → verify IPA output via logs
- **Token Generation:** Verify llama.cpp produces valid codec tokens (0–65535 range)
- **Audio Output:** Verify NeuCodec ONNX produces 24kHz waveform
- **End-to-End:** Open a PDF, tap play, hear audio, see sentence highlighting
- **Service:** Verify notification appears, media buttons work, survives app backgrounding
- **Voice Clone:** Record reference, verify cloned voice differs from default
- **Speed Control:** Verify 0.75x–2.0x range works without artifacts
