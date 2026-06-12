package com.example.ttspdfreader.data.tts

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.example.ttspdfreader.data.local.SettingsManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NeuTTSEngine @Inject constructor(
    private val context: Context,
    private val modelManager: ModelManager,
    private val kokoroTokenizer: KokoroTokenizer,
    private val voiceManager: VoiceManager,
    private val settingsManager: SettingsManager
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

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    private var currentVoiceId: String = "af_heart"

    // Dedicated dispatcher for thread-safe native execution
    private val nativeDispatcher: CoroutineDispatcher = Dispatchers.Default

    // Native JNI functions
    private external fun nativeInitEspeak(dataPath: String): Boolean
    private external fun nativePhonemeize(text: String, lang: String): String
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

                // 2. Initialize ONNX Runtime for Kokoro
                if (ortSession == null) {
                    ortEnv = OrtEnvironment.getEnvironment()
                    val opts = OrtSession.SessionOptions()
                    opts.setIntraOpNumThreads(2)
                    
                    // CPU execution is standard and highly optimized for onnxruntime-android
                    Log.i(TAG, "ONNX Runtime CPU provider initialized.")

                    ortSession = ortEnv?.createSession(modelManager.onnxFile.absolutePath, opts)
                }

                // 3. Load initial voice embedding
                loadVoiceEmbedding(settingsManager.selectedVoiceId.value)

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

    /**
     * Loads/caches voice embedding. Called during initialization and on voice hot-swap.
     */
    fun loadVoiceEmbedding(voiceId: String) {
        currentVoiceId = voiceId
        try {
            // Trigger loading to cache
            voiceManager.getEmbedding(voiceId, 0)
            Log.i(TAG, "Successfully loaded voice embedding: $voiceId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load voice embedding for $voiceId", e)
        }
    }

    /**
     * Compilation compatibility stubs. Kokoro always has a voice.
     */
    fun setReferenceVoice(tokens: IntArray?) {
        // No-op for compatibility
    }

    fun hasReferenceVoice(): Boolean {
        return true
    }

    /**
     * Synthesizes text using espeak-ng for phonemization and Kokoro ONNX model for inference.
     * Splitting is done if phoneme token count exceeds 510 limit.
     */
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

        val env = ortEnv ?: throw IllegalStateException("ONNX environment is null")
        val session = ortSession ?: throw IllegalStateException("ONNX session is null")

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

        // Tokenize IPA phonemes with KokoroTokenizer (limit to 510 tokens per chunk)
        val chunks = kokoroTokenizer.tokenizeWithLimit(phonemes)

        for (chunk in chunks) {
            // style vector: shape [1, 256]
            val tokenCount = chunk.size
            val styleData = voiceManager.getEmbedding(currentVoiceId, tokenCount)
            
            val inputIdsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(chunk), longArrayOf(1, chunk.size.toLong()))
            val styleTensor = OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(styleData), longArrayOf(1, 256))
            
            // Speed tensor shape handling
            val speedInfo = session.inputInfo["speed"]?.info as? TensorInfo
            val speedTensor = if (speedInfo?.shape?.isEmpty() == true || speedInfo?.shape?.contentEquals(longArrayOf()) == true) {
                OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(floatArrayOf(speed)), longArrayOf())
            } else {
                OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(floatArrayOf(speed)), longArrayOf(1))
            }

            val inputs = mapOf(
                "input_ids" to inputIdsTensor,
                "style" to styleTensor,
                "speed" to speedTensor
            )

            val waveform = engineMutex.withLock {
                try {
                    session.run(inputs).use { results ->
                        val outputTensor = results["waveform"].orElse(null) as? OnnxTensor ?: return@use null
                        val floatBuffer = outputTensor.floatBuffer
                        val outputData = FloatArray(floatBuffer.remaining())
                        floatBuffer.get(outputData)
                        outputData
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "ONNX inference error", e)
                    null
                } finally {
                    inputIdsTensor.close()
                    styleTensor.close()
                    speedTensor.close()
                }
            }

            if (waveform != null && waveform.isNotEmpty()) {
                emit(waveform)
            }
        }
    }.flowOn(nativeDispatcher)

    suspend fun release() = withContext(nativeDispatcher) {
        engineMutex.withLock {
            releaseInternal()
        }
    }

    private fun releaseInternal() {
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
