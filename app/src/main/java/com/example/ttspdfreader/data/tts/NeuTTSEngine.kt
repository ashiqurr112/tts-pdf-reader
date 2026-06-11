package com.example.ttspdfreader.data.tts

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.IntBuffer
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NeuTTSEngine @Inject constructor(
    private val context: Context,
    private val modelManager: ModelManager
) {
    companion object {
        private const val TAG = "NeuTTSEngine"
        private var nativeLibLoaded = false

        init {
            try {
                System.loadLibrary("neutts_jni")
                nativeLibLoaded = true
                Log.i(TAG, "Loaded neutts_jni native library successfully.")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load neutts_jni native library", e)
            }
        }
    }

    private val engineMutex = Mutex()
    private var isInitialized = false
    private var espeakInitialized = false
    private var llamaCtxHandle: Long = 0L

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    // Reference audio tokens for voice cloning
    private var referenceVoiceTokens: IntArray? = null

    // Dedicated dispatcher for thread-safe native execution
    private val nativeDispatcher: CoroutineDispatcher = Dispatchers.Default

    // Native JNI functions
    private external fun nativeInitEspeak(dataPath: String): Boolean
    private external fun nativePhonemeize(text: String, lang: String): String
    private external fun nativeInitLlama(modelPath: String, nThreads: Int): Long
    private external fun nativeGenerate(ctxHandle: Long, phonemes: String, refTokens: IntArray?, speed: Float): IntArray
    // FIX: New JNI function that runs the NeuCodec encoder to convert raw PCM
    //      float samples into codec tokens suitable for voice cloning.
    private external fun nativeEncodeAudio(samples: FloatArray, sampleRate: Int): IntArray
    private external fun nativeFreeLlama(ctxHandle: Long)
    private external fun nativeFreeEspeak()

    suspend fun init(): Boolean = withContext(nativeDispatcher) {
        engineMutex.withLock {
            if (isInitialized) return@withLock true

            if (!nativeLibLoaded) {
                Log.e(TAG, "Cannot initialize NeuTTSEngine: native library not loaded.")
                return@withLock false
            }

            if (!modelManager.modelsExist()) {
                Log.e(TAG, "Models do not exist. Cannot initialize NeuTTSEngine.")
                return@withLock false
            }

            try {
                // 1. Initialize espeak-ng by copying data to local storage first
                val espeakDataDir = File(context.filesDir, "espeak-ng-data")
                if (!espeakDataDir.exists()) {
                    copyEspeakDataFromAssets()
                }

                if (!espeakInitialized) {
                    espeakInitialized = nativeInitEspeak(espeakDataDir.absolutePath)
                    if (!espeakInitialized) {
                        Log.e(TAG, "Failed to initialize native espeak-ng")
                        return@withLock false
                    }
                }

                // 2. Initialize llama.cpp
                if (llamaCtxHandle == 0L) {
                    val nThreads = Runtime.getRuntime().availableProcessors().coerceAtLeast(1).coerceAtMost(4)
                    llamaCtxHandle = nativeInitLlama(modelManager.ggufFile.absolutePath, nThreads)
                    if (llamaCtxHandle == 0L) {
                        Log.e(TAG, "Failed to initialize native llama context")
                        return@withLock false
                    }
                }

                // 3. Initialize ONNX Runtime for NeuCodec
                if (ortSession == null) {
                    ortEnv = OrtEnvironment.getEnvironment()
                    val opts = OrtSession.SessionOptions()
                    opts.setIntraOpNumThreads(2)
                    ortSession = ortEnv?.createSession(modelManager.onnxFile.absolutePath, opts)
                }

                isInitialized = true
                Log.i(TAG, "NeuTTSEngine initialized successfully.")
                return@withLock true
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Native method not found - library may be corrupted", e)
                return@withLock false
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing NeuTTSEngine", e)
                releaseInternal()
                return@withLock false
            }
        }
    }

    private fun copyEspeakDataFromAssets() {
        val assetManager = context.assets
        val espeakDataDir = File(context.filesDir, "espeak-ng-data")
        espeakDataDir.mkdirs()

        fun copyAssetDir(path: String) {
            val assets = assetManager.list(path) ?: return
            if (assets.isEmpty()) {
                // It is a file
                val destFile = File(context.filesDir, path)
                destFile.parentFile?.mkdirs()
                assetManager.open(path).use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } else {
                // It is a directory
                for (asset in assets) {
                    val subPath = if (path.isEmpty()) asset else "$path/$asset"
                    copyAssetDir(subPath)
                }
            }
        }

        copyAssetDir("espeak-ng-data")
    }

    fun setReferenceVoice(tokens: IntArray?) {
        referenceVoiceTokens = tokens
    }

    fun hasReferenceVoice(): Boolean {
        return referenceVoiceTokens != null && referenceVoiceTokens!!.isNotEmpty()
    }

    /**
     * FIX: Encodes the reference WAV audio file into NeuCodec tokens for voice cloning.
     *
     * Previously, VoiceSetupViewModel.saveClonedVoice() wrote 128 random integers to
     * reference_tokens.dat. These random tokens were prepended to the LLM prompt as
     * fake "reference audio" which broke voice cloning entirely — the model received
     * nonsense tokens and produced audio that sounded nothing like the recorded speaker.
     *
     * This method:
     *   1. Reads the PCM samples out of the WAV file (skipping the 44-byte header).
     *   2. Normalises the Int16 samples to [-1, 1] floats.
     *   3. Calls nativeEncodeAudio() (JNI) which runs the NeuCodec encoder inside
     *      llama.cpp to produce the real codec token sequence for the audio.
     *   4. Returns that IntArray so the caller can persist it and/or pass it to
     *      setReferenceVoice().
     *
     * Returns null if the native library is not loaded, the engine is not initialised,
     * or the WAV file cannot be read.
     */
    suspend fun encodeReferenceAudio(wavFile: java.io.File): IntArray? = withContext(nativeDispatcher) {
        if (!nativeLibLoaded) {
            Log.e(TAG, "Cannot encode reference audio: native library not loaded.")
            return@withContext null
        }

        // Ensure the engine (and therefore llama.cpp context) is ready — the codec
        // encoder lives in the same native library.
        if (!isInitialized) {
            val ok = init()
            if (!ok) {
                Log.e(TAG, "Cannot encode reference audio: engine failed to initialise.")
                return@withContext null
            }
        }

        if (!wavFile.exists() || wavFile.length() < 44) {
            Log.e(TAG, "Reference WAV file is missing or too small: ${wavFile.absolutePath}")
            return@withContext null
        }

        try {
            // Read PCM Int16 samples from the WAV file, skipping the 44-byte header.
            val rawBytes = wavFile.readBytes()
            val pcmOffset = 44
            val pcmByteCount = rawBytes.size - pcmOffset
            if (pcmByteCount <= 0) {
                Log.e(TAG, "WAV file has no PCM data after header")
                return@withContext null
            }

            val sampleCount = pcmByteCount / 2 // 16-bit mono
            val samples = FloatArray(sampleCount)
            val pcmBuf = java.nio.ByteBuffer.wrap(rawBytes, pcmOffset, pcmByteCount)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until sampleCount) {
                // Normalise Int16 → [-1.0, 1.0]
                samples[i] = pcmBuf.short / 32768f
            }

            // Delegate to the native encoder.
            try {
                val tokens = nativeEncodeAudio(samples, 16000)
                if (tokens == null || tokens.isEmpty()) {
                    Log.e(TAG, "nativeEncodeAudio returned empty result")
                    return@withContext null
                }
                return@withContext tokens
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "nativeEncodeAudio not found — native library may be outdated", e)
                return@withContext null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading or encoding reference WAV", e)
            return@withContext null
        }
    }

    suspend fun synthesize(text: String, speed: Float = 1.0f): Flow<FloatArray> = flow {
        if (!isInitialized) {
            val success = init()
            if (!success) {
                throw IllegalStateException("NeuTTSEngine could not be initialized")
            }
        }

        if (!nativeLibLoaded) {
            throw IllegalStateException("Native library not loaded")
        }

        val phonemes = withContext(nativeDispatcher) {
            try {
                nativePhonemeize(text, "en-us")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "nativePhonemeize failed - library issue", e)
                ""
            }
        }

        if (phonemes.isBlank()) {
            return@flow
        }

        // Generate tokens from Llama model
        val tokens = withContext(nativeDispatcher) {
            try {
                nativeGenerate(llamaCtxHandle, phonemes, referenceVoiceTokens, speed)
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "nativeGenerate failed - library issue", e)
                null
            }
        }

        if (tokens == null || tokens.isEmpty()) {
            Log.e(TAG, "Generated tokens were empty or null.")
            return@flow
        }

        // Decode tokens using ONNX session
        val waveform = decodeTokens(tokens)
        if (waveform != null && waveform.isNotEmpty()) {
            emit(waveform)
        }
    }.flowOn(nativeDispatcher)

    private fun decodeTokens(tokens: IntArray): FloatArray? {
        val env = ortEnv ?: return null
        val session = ortSession ?: return null

        try {
            val inputName = session.inputNames.iterator().next()
            val inputInfo = session.inputInfo[inputName] ?: return null
            val tensorInfo = inputInfo.info as? TensorInfo ?: return null

            val numTokens = tokens.size
            val shape = when (tensorInfo.shape.size) {
                2 -> longArrayOf(1, numTokens.toLong())
                3 -> longArrayOf(1, 1, numTokens.toLong())
                else -> longArrayOf(1, 1, 1, numTokens.toLong())
            }

            val tensor = when (tensorInfo.type) {
                OnnxJavaType.INT64 -> {
                    val longTokens = LongArray(tokens.size) { tokens[it].toLong() }
                    OnnxTensor.createTensor(env, LongBuffer.wrap(longTokens), shape)
                }
                OnnxJavaType.INT32 -> {
                    OnnxTensor.createTensor(env, IntBuffer.wrap(tokens), shape)
                }
                else -> {
                    val floatTokens = FloatArray(tokens.size) { tokens[it].toFloat() }
                    OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(floatTokens), shape)
                }
            }

            val outputName = session.outputNames.iterator().next()
            tensor.use {
                session.run(mapOf(inputName to tensor)).use { results ->
                    val outputTensor = results[outputName].orElse(null) as? OnnxTensor ?: return null
                    val floatBuffer = outputTensor.floatBuffer
                    val outputData = FloatArray(floatBuffer.remaining())
                    floatBuffer.get(outputData)
                    return outputData
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding tokens via ONNX", e)
            return null
        }
    }

    suspend fun release() = withContext(nativeDispatcher) {
        engineMutex.withLock {
            releaseInternal()
        }
    }

    private fun releaseInternal() {
        if (llamaCtxHandle != 0L) {
            nativeFreeLlama(llamaCtxHandle)
            llamaCtxHandle = 0L
        }
        if (espeakInitialized) {
            nativeFreeEspeak()
            espeakInitialized = false
        }
        try {
            ortSession?.close()
            ortEnv?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing ORT session/env", e)
        } finally {
            ortSession = null
            ortEnv = null
        }
        isInitialized = false
    }
}
