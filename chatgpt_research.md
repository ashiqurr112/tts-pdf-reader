# Executive Summary

Our review indicates that the overall **Kokoro** integration in the app is largely correct, but several subtle mismatches and omissions could degrade audio quality. Key points are:

- **Phoneme extraction:** We use eSpeak-NG in synchronous mode to generate **IPA phoneme** strings (`espeak_TextToPhonemes(..., mode=2)`), which matches Kokoro’s expected IPA input. However, eSpeak may insert or omit characters (e.g. stress markers) differently from Kokoro’s training data, possibly causing distortion.  
- **Tokenization:** The `KokoroTokenizer`’s vocabulary matches the official model’s 178-token mapping (including IPA symbols), so input tokenization appears correct.  
- **ONNX model inference:** We feed token IDs, style embedding, and a **float** speed tensor into the ONNX runtime. The code handles speed as a scalar tensor as expected, so that is correct. The output is a float waveform at 24 kHz.  
- **Audio postprocessing:** The model outputs raw float PCM samples (nominal range [-1.0,1.0]). Android’s `AudioTrack` is configured for **ENCODING_PCM_FLOAT** at 24000 Hz, matching this range. In standard usage one would multiply the floats by 32767 to convert to 16-bit PCM, but since we use float mode, direct delivery is fine. We do *not* perform any additional normalization.  
- **Memory and JNI:** The JNI signatures and buffer handling look correct. We release jstring resources properly and close input tensors. One minor gap is that the output `OnnxTensor` is not explicitly closed (though it’s scoped inside a `use` block).  

In summary, **no single glaring mismatch** is obvious from code alone. The “garbage” sound likely arises from *data mismatches* (e.g. phoneme spelling differences, unexpected bytes in audio, missing normalization) or device-specific audio issues. Below we detail each stage, compare against Kokoro’s specs, and list discrepancies.

# Detailed Findings

## Phoneme Generation and Language Handling

- **eSpeak initialization:** We initialize eSpeak with `espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 0, data_path, 0)`. Using *synchronous* mode means no real-time audio output from eSpeak; it’s purely for text→phonemes, which is correct for our use-case. Upstream Kokoro uses a mixture of **Misaki G2P** and eSpeak for fallback, but our code bypasses Misaki entirely. In effect, all languages use eSpeak with voice name `"en-us"`, which forces American English pronunciation even for non-English text. This could produce incorrect phoneme sequences for, e.g., Chinese. If the wrong language or voice is set, eSpeak still returns something (defaulting to its built-in English), leading to gibberish. *Recommendation:* Verify that the `lang` parameter passed to `nativePhonemeize(text, lang)` matches the actual text language.  

- **IPA mode:** We call `espeak_TextToPhonemes(&ptr, 1, 2)`, where mode=2 selects IPA output. This aligns with Kokoro’s expected IPA input. The code loops over the text pointer and concatenates returned phoneme chunks with a space. (eSpeak returns IPA up to each sentence or punctuation, then advances the pointer.) The manual notes bit 1=IPA, which we use correctly. A minor issue: **spacing.** The code adds a space after each chunk even if eSpeak’s output already contains inter-phoneme spaces. This could introduce double spaces; however, our tokenizer treats space as a PAD token or ignores it, so this is likely harmless.  

- **Voice names:** In JNI we do `espeak_SetVoiceByName(lang_str)` and check the return. If the voice name is invalid, it logs an error but **does not abort**; phonemization then proceeds (likely with a default voice). Ideally, we should abort or fallback. As is, a wrong `lang` silently yields eSpeak’s default, which may be a culprit.  

- **Mapping to Kokoro vocab:** The IPA string is passed to `KokoroTokenizer`, which splits it into individual Unicode characters and maps each to an index via `VOCAB`. The vocabulary in our tokenizer matches the official config.json exactly. For example, characters like “ʣ” (U+02E3) map to ID 18, matching Kokoro’s map. Thus, our tokenization is consistent with the model’s training. Unrecognized characters (if any sneaked through eSpeak) are skipped with a warning.  

## Model Inputs and ONNX Execution

- **ONNX model and files:** The app’s `ModelManager` downloads `model.onnx` and the required voice `.bin` files from HuggingFace’s ONNX-Community repository. The URLs appear correct (e.g. `https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/main/onnx/model.onnx`) and voice IDs match the supported voices list. We confirmed (via HuggingFace VOICES.md) that “af_heart”, “am_michael”, etc. are valid voice names.  

- **Input tensors:** For each chunk of tokens, we create: 
  - `input_ids` as a LongBuffer of shape `[1, N]` (N = token count), 
  - `style` as a FloatBuffer of shape `[1,256]` (the speaker embedding from the .bin file), 
  - `speed` as a FloatBuffer either of shape `[]` or `[1]` depending on the model’s expected shape. This matches upstream: Kokoro’s Python uses a float speed (0.5–2.0). We correctly wrap our Kotlin `Float` into a FloatBuffer. (An issue was reported that a Chinese variant of Kokoro wrongly casts speed to int, but we use v1.0 where speed should work as float.)  

- **Output tensor:** We run the ONNX session and retrieve the `"waveform"` output tensor (an `OnnxTensor`). We then copy its `floatBuffer` into a `FloatArray`. The examples for Kokoro show the raw audio as a float32 array. In Python they do `wav = result.audio.cpu().numpy()`, then scale by 32767 to get int16. Our code skips scaling because we use `PCM_FLOAT`. According to Android docs, the range **must be [-1.0,1.0]**, otherwise it will be clamped. So it’s crucial that the model’s float output indeed lies in [-1,1]; the Kokoro pipeline documentation implies it does (since they multiply by 32767). We found no code that renormalizes the tensor; if the model outputs slightly beyond ±1, the sound could distort (AudioTrack will clamp out-of-range values).  

- **Normalization:** Unlike some TTS pipelines, we do not apply a volume normalization (e.g. no gain adjustment, no dithering). This is normally fine if the model’s waveform is already at full scale. If our output sounds “low” or “noisy,” we might need to add normalization.  

- **Memory handling:** Input tensors (`inputIdsTensor`, `styleTensor`, `speedTensor`) are closed in a `finally` block, which is correct. However, the output tensor (`outputTensor`) is not explicitly closed (it’s inside a `use` on results). In practice this may not cause immediate errors, but long-running use could leak memory. It would be better to call `outputTensor.close()` after copying its data. JNI strings (from `NewStringUTF`) are correctly wrapped in Java strings and the C char* pointers are released with `ReleaseStringUTFChars`.  

## Audio Output and Rendering

- **AudioTrack configuration:** We set `SAMPLE_RATE = 24000`, `CHANNEL_OUT_MONO`, and `ENCODING_PCM_FLOAT`. This matches Kokoro’s specification: audio is generated at **24 kHz, mono** (with 16-bit float precision). Indeed, one Kokoro example notes “Audio sample rate = 24000, format=paInt16” (PortAudio). Our AudioTrack uses floats, but the sample rate matches. Using 24 kHz is correct.  

- **Buffering:** We compute `minBufferSize = AudioTrack.getMinBufferSize(24000, MONO, PCM_FLOAT)` and use twice that as our buffer. This is a safe practice. Audio is played via `write(floatArray, ..., WRITE_BLOCKING)` in a loop. One potential issue: if any write call returns a negative error, we break out, possibly halting audio. We should ensure to log such errors. The code tries to handle interruptions (via a `running` flag marked `@Volatile`) so that stopping works correctly. 

- **Encoding:** Since `PCM_FLOAT` is native-endian, we just pass the `FloatArray` from the model. This is consistent with [32]: “32-bit float (native endian), nominal range [-1.0,1.0].” If somehow the Android device’s endianness differs (e.g. big-endian ARM), the audio would be garbled, but most Android CPUs (ARM) are little-endian, same as typical model output. We should verify device endianness matches expectations (Android docs note it’s native, and most are little-endian).  

- **Volume and muting:** We do not explicitly set the track’s volume (so it defaults to full). If the output seems too loud or quiet, that could be adjusted. The code does allow playback speed changes (`setPlaybackParams`) but that shouldn’t distort the waveform itself.  

## JNI and Threading

- **JNI signatures:** The native methods in `NeuTTSEngine.kt` (`nativeInitEspeak`, `nativePhonemeize`, `nativeFreeEspeak`) exactly match the C++ names (`Java_com_example_ttspdfreader_data_tts_NeuTTSEngine_native*`) and signatures (strings and void/boolean returns). We found no signature mismatch (otherwise Kotlin would crash or we’d get an `UnsatisfiedLinkError`).  

- **Thread safety:** The TTS runs in a coroutine on a background dispatcher (`nativeDispatcher = Dispatchers.Default`). eSpeak is called inside `withContext(nativeDispatcher)`, which should prevent Android’s main thread from blocking. eSpeak itself is not thread-safe, but we only call it from one coroutine at a time (the code uses a mutex around the whole `synthesize()` flow). Onnx inference is also protected by `engineMutex` to avoid concurrent sessions. This is good.  

- **Buffer lifetimes:** We pass Java arrays and ByteBuffers into JNI and OnnxTensor. All allocations appear valid. The pointer `ptr` in `nativePhonemeize` is advanced by eSpeak. There’s no unintentional pointer aliasing. We do free `data_path_str` after use. 

# Comparison Table: Implementation vs. Expected

| **File / Component**               | **Function/Item**          | **Our Behavior**                                           | **Upstream Expectation**                                     | **Discrepancy / Notes**                                      |
|-----------------------------------|----------------------------|-----------------------------------------------------------|--------------------------------------------------------------|-------------------------------------------------------------|
| `neutts_jni.cpp`                  | `nativeInitEspeak`         | Calls `espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS,…)`. No audio is output by eSpeak.       | eSpeak docs: first arg selects output mode;  synchronous (0) is valid for phoneme-only usage.           | Ok. Could use espeakCHARS_UTF8=1 (UTF-8 mode) by passing 1 as we did. No issue here. |
|                                   | `nativePhonemeize`         | Loops `espeak_TextToPhonemes(&ptr, 1, 2)` to get IPA phonemes; appends a space after each chunk. | eSpeak: mode=2 (IPA) is correct for Kokoro. Expects UTF-8 input (we pass 1) and returns IPA.            | We add extra spaces; eSpeak may not expect trailing spaces but our tokenizer handles them. Good practice to check multi-phoneme tokens (see below). |
|                                   | `nativeFreeEspeak`         | Calls `espeak_Terminate()`; releases eSpeak resources.       | Matches eSpeak’s recommended cleanup.                         | None.                                                       |
| `KokoroTokenizer.kt`              | `VOCAB` mapping            | Defined 178 tokens (including IPA chars). Matches config.json tokens exactly. | Kokoro v1.0 uses 178-token vocab (config.json).                               | None. Vocabulary is consistent.                             |
|                                   | `tokenizeRaw`              | Converts IPA string to token IDs char-by-char. Unrecognized chars are skipped. | Kokoro expects token IDs for each phoneme/character. Ideally misaki would produce the same characters. | Potential gap: multi-character phonemes (e.g. “ts”) should appear as single IPA “ʦ”. If eSpeak outputs them differently (e.g. “ts” as two chars), our tokenizer would treat them as two tokens or skip if unmapped. This could degrade output. |
|                                   | `tokenizeWithLimit`        | Splits long phoneme sequences on whitespace to <510 tokens. Surrounds each chunk with PAD=0. | Kokoro inference suggests max 512 tokens (incl. start/end); splitting text is standard practice. | None; logic matches typical batching.                        |
| `NeuTTSEngine.kt`                 | ONNX inputs                | Names inputs `"input_ids"`, `"style"`, `"speed"` and output `"waveform"`. Creates FloatBuffer/LongBuffer as needed. | Kokoro’s ONNX model v1.0 expects these names and shapes. (Speed is scalar.)          | None apparent. The speed handling even checks if the model expects a scalar or 1D tensor. |
|                                   | ONNX execution             | Uses `session.run(inputs)`, reads tensor `"waveform"` into FloatArray. | Expected: float waveform at 24 kHz (as confirmed by Kokoro docs/tests).            | None, aside from not normalizing output.                     |
| `AudioRenderer.kt`                | Audio format               | `AudioTrack` built with ENCODING_PCM_FLOAT, 24000 Hz, MONO. Writes the raw float samples. | Android docs: `PCM_FLOAT` expects [-1.0,1.0] range. Kokoro audio is nominally normalized. | If any waveform samples exceed [-1,1], Android will clamp them, causing distortion. We do not clamp ourselves, so out-of-range output (if any) is a risk. |
|                                   | Buffering/Threading        | Use `getMinBufferSize`, doubled buffer, MODE_STREAM, WRITE_BLOCKING. Audio is written in a background coroutine. | Standard streaming setup.                                         | Thread-safety looks fine.                                       |

# Recommended Fixes (Ranked)

1. **Verify language and phoneme mapping:** Ensure the correct language code is passed to `nativePhonemeize`. The code currently hardcodes `"en-us"` in the Kotlin synthesize function. If synthesizing non-English text, this will produce incorrect IPA. For multilingual support, use `SettingsManager` or TTS parameters to pass the user’s selected language/voice to eSpeak.  
   *Rationale:* Mismatched phonemes are a common cause of gibberish output. Aligning eSpeak voice to the intended language fixes a large class of errors. *(High impact, easy fix)*  

2. **Phoneme character handling:** Check if eSpeak produces any multi-character phonemes or unexpected symbols. The tokenizer currently splits by Unicode char; if eSpeak outputs e.g. “ts” instead of the single “ʦ” character, the model input will be off. Consider using a normalization step or a stricter phoneme extraction (e.g. configure eSpeak’s `espeak_SetPhonemeTrace` with a custom separator) to ensure one-token-per-phoneme.  
   *Rationale:* Any mismatch between eSpeak output and the model’s expected IPA will degrade quality. This is a subtle issue but important. *(Medium impact)*  

3. **Normalize or clip audio range:** After ONNX inference, optionally inspect the float array. If values exceed [-1,1], normalize the array to  avoid clamping. For example:  
   ```kotlin
   val maxVal = outputData.maxOf { abs(it) }.coerceAtLeast(1.0f)
   for (i in outputData.indices) outputData[i] /= maxVal
   ```  
   Alternatively, use `AudioTrack.setVolume()` or similar to adjust gain.  
   *Rationale:* Android will clamp float PCM outside [-1,1], cutting peaks and introducing distortion. Ensuring outputs are within range preserves waveform fidelity. *(Medium impact)*  

4. **Close ONNX output tensor:** After copying the waveform, call `outputTensor.close()` (or use a `use` block) to free native memory. While unlikely to cause immediate audio issues, it prevents memory leaks during long runs.  
   *Rationale:* Proper resource cleanup is best practice and could prevent crashes if many syntheses occur. *(Low impact)*  

5. **Enable additional espeak options:** Experiment with `espeak_SetParameter` (e.g. speech rate, punctuation, capitalization) to see if phoneme output improves. For example, disabling automatic punctuation may yield more continuous phoneme output. Also consider using `espeak_SetPhonemeTrace(espeakPHONEMES_IPA, file)` during development to log exactly what phonemes are generated.  
   *Rationale:* Fine-tuning eSpeak settings can subtly improve phoneme output quality. *(Low impact)*  

# Suggested Tests and Diagnostics

- **Reproduce with known text:** Run TTS on a short English sentence (e.g. “Hello world”) and save both the app’s output and a reference audio. Using [45]’s method, you can generate a WAV via Python:
  ```bash
  python3 - <<EOF
  from kokoro import KPipeline
  import soundfile as sf
  pipeline = KPipeline(lang_code='a', repo_id='hexgrad/Kokoro-82M')
  result = next(pipeline("Hello world", voice='af_heart'))
  sf.write("ref.wav", result.audio, 24000)
  EOF
  ```
  Compare `ref.wav` to the app’s output (e.g. record via device microphone or internal loopback). This isolates whether the ONNX model or the Android renderer is at fault.

- **Capture Android logs:** Use `adb logcat` to collect runtime logs. For example:
  ```bash
  adb logcat -s NeuTTSEngine *:E > tts_debug.txt
  ```
  Look for any errors or warnings (the code logs errors on failure). Ensure the `nativePhonemeize` call succeeds (no `UnsatisfiedLinkError`) and that each chunk is processed.

- **Inspect phoneme strings:** Modify the app temporarily to log the IPA string before tokenization:
  ```kotlin
  Log.d("TTS", "Phonemes: \"$phonemes\"")
  ```
  Then run some inputs (especially multilingual) to verify the phoneme output is sane. This directly checks the preprocessing stage.

- **Waveform dump:** After each `emit(outputData)`, you could write the float array to a file (if allowed) or convert to PCM bytes and save via `AudioTrack.write`. On a rooted device or emulator, you might record the output track via `tinymix` or `screencap` (though Android makes this hard). Alternatively, capture and pull logs or use a native method to dump PCM to storage for inspection.  

- **Unit-test tokenizer:** Write a quick unit test in Java/Kotlin that feeds known IPA strings into `KokoroTokenizer` and checks the output ID sequence against the model config. For example, verify that `"hello"`→`"h ə l oʊ"` (IPA) maps to the expected ID array.

- **Profile performance:** The distortions could stem from performance issues (buffer underruns). Use `adb shell dumpsys media.audio_flinger` or Android Studio profiler to ensure audio is not underrunning. If so, increase `minBufferSize` or adjust threading priorities.

# Data Flow Diagram

```mermaid
graph LR
    Text["Text input"] -->|eSpeak-NG (native)| IPA["IPA phoneme string"]
    IPA -->|KokoroTokenizer| TOKENS["Token indices (+PAD)"]
    TOKENS -->|ONNX Runtime| WAVE["Float waveform (PCM)"]
    WAVE -->|AudioRenderer| PCM["PCM float buffer ([-1,1])"]
    PCM -->|AudioTrack| Speaker["Device speaker"]
```

Each arrow above represents a processing step in the pipeline. We verified that each step aligns with Kokoro’s design: *e.g.* IPA mode in eSpeak, a matching token vocab, 24 kHz waveform output, and float PCM playback.

**Sources:** Official Kokoro documentation/configuration, eSpeak-NG API docs, Android `AudioFormat` docs, and Kokoro example code were used to verify expected behavior.