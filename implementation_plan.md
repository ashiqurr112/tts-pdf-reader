# Fix TTS Garbage Audio Output

## Background

The Read Aloud feature produces garbled/garbage audio output. It has **never** produced correct speech. The fundamental reason is that the `espeak-ng` git submodule was never initialized, meaning the native JNI library either fails to compile or silently produces empty phoneme output. All downstream processing (Kokoro ONNX inference) then receives garbage tokens and outputs noise.

Additionally, several secondary bugs in the audio pipeline cause race conditions, overlapping playback, and potential overflow issues.

---

## Bug 1 — espeak-ng Submodule Not Initialized (🔴 ROOT CAUSE)

> [!CAUTION]
> This is the **single root cause** of all garbage audio. Without this fix, nothing else matters.

| Detail | Value |
|--------|-------|
| **Location** | Git submodule at `app/src/main/cpp/espeak-ng/` |
| **Severity** | 🔴 Fatal — app cannot produce speech at all |

### Problem

The espeak-ng submodule is registered in [.gitmodules](file:///home/ashiqur/Pictures/tts%20pdf%20reader/.gitmodules) but was **never initialized**. The directory `app/src/main/cpp/espeak-ng/` is empty.

Evidence:
```bash
$ git submodule status
-fbe4b3764285c35b1f035cb8d09ad9fc19f71c30 app/src/main/cpp/espeak-ng
#  ^ dash prefix means "not initialized"

$ ls app/src/main/cpp/espeak-ng/
# (empty)
```

The [CMakeLists.txt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/cpp/CMakeLists.txt) references espeak-ng source files (lines 13–49) that don't exist, so the native `neutts_jni.so` library either:
- Fails to compile entirely, OR
- Was compiled against stubs/cached objects from a stale build and silently returns empty phonemes

### Fix

```bash
cd "/home/ashiqur/Pictures/tts pdf reader"
git submodule update --init --recursive app/src/main/cpp/espeak-ng
```

> [!NOTE]
> I have already run this command — the submodule is now populated at commit `fbe4b376`. The implementing agent should verify this with `ls app/src/main/cpp/espeak-ng/src/` and confirm source files are present.

---

## Bug 2 — NDK Version Mismatch + Missing `local.properties`

| Detail | Value |
|--------|-------|
| **File** | [app/build.gradle.kts](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/build.gradle.kts#L13) |
| **Severity** | 🔴 Build blocker — Gradle can't find NDK |

### Problem

The project specifies `ndkVersion = "26.1.10909125"` but the local machine has NDK `28.2.13676358`. Also, `local.properties` was missing (the `setup_sdk.sh` script targets a Codespaces `/workspaces/` path).

### Fix Already Applied

I've already made these changes:

1. **Created** `local.properties`:
```properties
sdk.dir=/home/ashiqur/android-sdk
```

2. **Updated** [app/build.gradle.kts](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/build.gradle.kts#L13) line 13:
```diff
-    ndkVersion = "26.1.10909125"
+    ndkVersion = "28.2.13676358"
```

---

## Bug 3 — `espeak_Initialize` Path Already Fixed ✅

| Detail | Value |
|--------|-------|
| **File** | [NeuTTSEngine.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/data/tts/NeuTTSEngine.kt#L84-L86) |
| **Status** | Already fixed in current code |

The code at line 86 correctly passes `context.filesDir.absolutePath` (the parent directory):
```kotlin
espeakInitialized = nativeInitEspeak(context.filesDir.absolutePath)
```

**No action needed.**

---

## Bug 4 — Style Embedding Off-by-One Already Fixed ✅

| Detail | Value |
|--------|-------|
| **File** | [NeuTTSEngine.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/data/tts/NeuTTSEngine.kt#L212) |
| **Status** | Already fixed in current code |

Line 212 correctly subtracts the 2 pad tokens:
```kotlin
val tokenCount = chunk.size - 2  // exclude the 2 pad tokens
```

**No action needed.**

---

## Bug 5 — `onComplete()` Race / Generation Counter Already Fixed ✅

| Detail | Value |
|--------|-------|
| **File** | [AudioRenderer.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/data/tts/AudioRenderer.kt#L44-L133) |
| **Status** | Already fixed in current code |

The `playGeneration` counter (line 44) and guard at line 133 are correctly implemented:
```kotlin
if (isPlaying && thisGeneration == playGeneration) {
    onComplete()
}
```

**No action needed.**

---

## Bug 6 — `playbackHeadPosition` Integer Overflow Already Fixed ✅

| Detail | Value |
|--------|-------|
| **File** | [AudioRenderer.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/data/tts/AudioRenderer.kt#L155-L170) |
| **Status** | Already fixed in current code |

`waitForPlaybackComplete` already uses `Long` arithmetic with unsigned masking at lines 99 and 159:
```kotlin
val currentHead = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
```

**No action needed.**

---

## Bug 7 — Skip Functions Already Fixed ✅

| Detail | Value |
|--------|-------|
| **File** | [ReadAloudService.kt](file:///home/ashiqur/Pictures/tts%20pdf%20reader/app/src/main/java/com/example/ttspdfreader/service/ReadAloudService.kt#L359-L391) |
| **Status** | Already fixed in current code |

Both `skipToNextSentence()` (line 359) and `skipToPreviousSentence()` (line 375) correctly call `audioRenderer.stop()` before proceeding.

**No action needed.**

---

## Summary: What the Implementing Agent Must Do

| # | Task | Status | Action Required |
|---|------|--------|-----------------|
| 1 | Initialize espeak-ng submodule | ✅ Done | **Verify** `ls app/src/main/cpp/espeak-ng/src/` shows source files |
| 2 | Fix NDK version + local.properties | ✅ Done | **Verify** `local.properties` exists and `ndkVersion` matches |
| 3 | espeak init path | ✅ Already in code | None |
| 4 | Style embedding off-by-one | ✅ Already in code | None |
| 5 | Generation counter race | ✅ Already in code | None |
| 6 | Head position overflow | ✅ Already in code | None |
| 7 | Skip race condition | ✅ Already in code | None |
| 8 | **Rebuild & deploy** | ❌ Pending | **`./gradlew assembleDebug`** then install APK |

> [!IMPORTANT]
> The **only remaining action** is to rebuild the app and install it. All code fixes are already in place. The root cause was simply that the espeak-ng submodule was never cloned, so the native library was broken.

## Verification Plan

### Build Verification
```bash
cd "/home/ashiqur/Pictures/tts pdf reader"
./gradlew assembleDebug
```

### Post-Install Verification (on device)
1. Open a PDF, start Read Aloud
2. Check logcat for: `NeuTTS_JNI: Initializing espeak-ng with data path:` — verify the path is `context.filesDir` (NOT `context.filesDir/espeak-ng-data`)
3. Check logcat for: `KokoroTokenizer: Unknown phoneme character skipped:` — should see very few or none (previously would flood with warnings)
4. Verify **clear speech output** instead of garbage noise
5. Test skip forward/back, pause/resume for clean transitions
