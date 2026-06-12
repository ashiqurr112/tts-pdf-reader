package com.example.ttspdfreader.data.tts

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

data class VoiceInfo(val id: String, val displayName: String)

@Singleton
class VoiceManager @Inject constructor(
    private val context: Context,
    private val modelManager: ModelManager
) {
    private val voiceCache = mutableMapOf<String, FloatArray>()

    companion object {
        val AVAILABLE_VOICES = listOf(
            VoiceInfo("af_heart", "Heart (US Female)"),
            VoiceInfo("af_bella", "Bella (US Female)"),
            VoiceInfo("am_michael", "Michael (US Male)"),
            VoiceInfo("am_fenrir", "Fenrir (US Male)"),
            VoiceInfo("bf_emma", "Emma (UK Female)"),
            VoiceInfo("bm_george", "George (UK Male)")
        )
        const val EMBEDDING_LENGTH = 131072 // 512 * 1 * 256
        const val TOKEN_LENGTH = 256
        const val MAX_TOKENS = 512
    }

    fun getAvailableVoices(): List<VoiceInfo> {
        return AVAILABLE_VOICES
    }

    @Synchronized
    fun getEmbedding(voiceId: String, tokenCount: Int): FloatArray {
        // Load voice data into cache if not present
        var cached = voiceCache[voiceId]
        if (cached == null) {
            cached = loadVoiceFile(voiceId)
            voiceCache[voiceId] = cached
        }

        // Clamp tokenCount to 0..511
        val clampedCount = tokenCount.coerceIn(0, MAX_TOKENS - 1)
        val offset = clampedCount * TOKEN_LENGTH
        val embedding = FloatArray(TOKEN_LENGTH)
        System.arraycopy(cached, offset, embedding, 0, TOKEN_LENGTH)
        return embedding
    }

    private fun loadVoiceFile(voiceId: String): FloatArray {
        val voiceFile = File(modelManager.voicesDir, "$voiceId.bin")
        if (!voiceFile.exists()) {
            throw IllegalArgumentException("Voice file not found for voiceId: $voiceId at ${voiceFile.absolutePath}")
        }
        val length = voiceFile.length().toInt()
        val expectedBytes = EMBEDDING_LENGTH * 4 // float32 = 4 bytes
        if (length != expectedBytes) {
            throw IllegalStateException("Invalid voice file size. Expected $expectedBytes bytes, but got $length")
        }

        val floatArray = FloatArray(EMBEDDING_LENGTH)
        FileInputStream(voiceFile).use { fis ->
            val buffer = ByteArray(expectedBytes)
            var bytesRead = 0
            while (bytesRead < expectedBytes) {
                val read = fis.read(buffer, bytesRead, expectedBytes - bytesRead)
                if (read == -1) break
                bytesRead += read
            }
            val byteBuffer = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
            byteBuffer.asFloatBuffer().get(floatArray)
        }
        return floatArray
    }
}
