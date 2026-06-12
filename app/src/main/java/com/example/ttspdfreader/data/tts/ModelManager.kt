package com.example.ttspdfreader.data.tts

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(val progress: Float, val bytesDownloaded: Long, val totalBytes: Long) : DownloadState()
    object Complete : DownloadState()
    data class Error(val message: String) : DownloadState()
}

@Singleton
class ModelManager @Inject constructor(
    private val context: Context
) {
    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private var isCancelled = false

    private val modelsDir = File(context.getExternalFilesDir(null), "models")
    val voicesDir = File(modelsDir, "voices")

    val onnxFile = File(modelsDir, "model.onnx")

    // Download URLs
    private val onnxUrl = "https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/main/onnx/model.onnx"
    private val voiceIds = listOf("af_heart", "af_bella", "am_michael", "am_fenrir", "bf_emma", "bm_george")

    fun modelsExist(): Boolean {
        // Kokoro requires the ONNX model and at least the default voice (af_heart.bin)
        val defaultVoiceFile = File(voicesDir, "af_heart.bin")
        return onnxFile.exists() && onnxFile.length() > 0 &&
               defaultVoiceFile.exists() && defaultVoiceFile.length() > 0
    }

    fun getRequiredStorageSpaceBytes(): Long {
        // ONNX is approx 330MB, 6 voices are approx 3MB total. Allocate 350MB.
        return 350L * 1024L * 1024L
    }

    fun getAvailableStorageSpaceBytes(): Long {
        val path = context.filesDir
        return path.usableSpace
    }

    suspend fun downloadModels(wifiOnly: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (modelsExist()) {
            _downloadState.value = DownloadState.Complete
            return@withContext true
        }

        isCancelled = false

        if (wifiOnly && !isWifiConnected()) {
            _downloadState.value = DownloadState.Error("Wi-Fi is not connected and Wi-Fi only mode is enabled.")
            return@withContext false
        }

        if (getAvailableStorageSpaceBytes() < getRequiredStorageSpaceBytes()) {
            _downloadState.value = DownloadState.Error("Insufficient storage space. At least 350 MB required.")
            return@withContext false
        }

        if (!modelsDir.exists()) {
            modelsDir.mkdirs()
        }
        if (!voicesDir.exists()) {
            voicesDir.mkdirs()
        }

        try {
            // Download ONNX model (0% to 90% of progress)
            if (!onnxFile.exists() || onnxFile.length() == 0L) {
                downloadFile(onnxUrl, onnxFile, 0.0f, 0.9f)
            } else {
                _downloadState.value = DownloadState.Downloading(0.9f, onnxFile.length(), onnxFile.length())
            }

            if (isCancelled) {
                _downloadState.value = DownloadState.Idle
                return@withContext false
            }

            // Download 6 voice .bin files (90% to 100% of progress, shared equally: ~1.67% each)
            val voiceWeight = 0.1f / voiceIds.size
            for ((index, voiceId) in voiceIds.withIndex()) {
                if (isCancelled) break
                val voiceFile = File(voicesDir, "$voiceId.bin")
                if (!voiceFile.exists() || voiceFile.length() == 0L) {
                    val voiceUrl = "https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/main/voices/$voiceId.bin"
                    downloadFile(voiceUrl, voiceFile, 0.9f + (index * voiceWeight), voiceWeight)
                }
            }

            if (isCancelled) {
                _downloadState.value = DownloadState.Idle
                return@withContext false
            }

            _downloadState.value = DownloadState.Complete
            return@withContext true
        } catch (e: Exception) {
            Log.e("ModelManager", "Error during model downloading", e)
            if (isCancelled) {
                _downloadState.value = DownloadState.Idle
            } else {
                _downloadState.value = DownloadState.Error(e.message ?: "Unknown download error")
            }
            return@withContext false
        }
    }

    fun cancelDownload() {
        isCancelled = true
    }

    private suspend fun downloadFile(
        urlString: String,
        targetFile: File,
        progressOffset: Float,
        progressWeight: Float
    ): Unit = withContext(Dispatchers.IO) {
        val tempFile = File(targetFile.absolutePath + ".tmp")
        var connection: HttpURLConnection? = null
        var input: InputStream? = null
        var output: FileOutputStream? = null

        try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            var existingLength = 0L
            if (tempFile.exists()) {
                existingLength = tempFile.length()
                connection.setRequestProperty("Range", "bytes=$existingLength-")
            }

            connection.connect()

            val responseCode = connection.responseCode
            val isResume = responseCode == HttpURLConnection.HTTP_PARTIAL

            if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                // If Range requests are not supported or return error, retry from beginning
                if (existingLength > 0) {
                    tempFile.delete()
                    downloadFile(urlString, targetFile, progressOffset, progressWeight)
                    return@withContext
                }
                throw Exception("Server returned HTTP $responseCode")
            }

            val contentLength = connection.contentLengthLong
            val totalBytes = if (isResume) contentLength + existingLength else contentLength

            input = connection.inputStream
            output = FileOutputStream(tempFile, isResume)

            val buffer = ByteArray(16384)
            var bytesRead: Int
            var bytesDownloaded = if (isResume) existingLength else 0L
            var lastUpdateMillis = 0L
            while (input.read(buffer).also { bytesRead = it } != -1) {
                if (isCancelled) {
                    break
                }
                output.write(buffer, 0, bytesRead)
                bytesDownloaded += bytesRead

                if (totalBytes > 0) {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastUpdateMillis >= 150L || bytesDownloaded == totalBytes) {
                        lastUpdateMillis = currentTime
                        val fileProgress = bytesDownloaded.toFloat() / totalBytes
                        val overallProgress = progressOffset + (fileProgress * progressWeight)
                        _downloadState.value = DownloadState.Downloading(overallProgress, bytesDownloaded, totalBytes)
                    }
                }
            }

            output.flush()
            output.close()
            output = null

            if (isCancelled) {
                return@withContext
            }

            // Verify size matches or is valid
            if (totalBytes > 0 && tempFile.length() < totalBytes) {
                throw Exception("File download was incomplete. Size mismatch.")
            }

            if (tempFile.renameTo(targetFile)) {
                Log.d("ModelManager", "Downloaded file successfully to: ${targetFile.name}")
            } else {
                throw Exception("Failed to rename temp file to ${targetFile.name}")
            }

        } finally {
            input?.close()
            output?.close()
            connection?.disconnect()
        }
    }

    private fun isWifiConnected(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun deleteModels() {
        // Clean up new Kokoro files
        if (onnxFile.exists()) onnxFile.delete()
        if (voicesDir.exists()) {
            voicesDir.listFiles()?.forEach { it.delete() }
            voicesDir.delete()
        }
        // Clean up old NeuTTS files if they exist
        val oldGgufFile = File(modelsDir, "neutts-air-Q8_0.gguf")
        if (oldGgufFile.exists()) oldGgufFile.delete()
        val oldOnnxFile = File(modelsDir, "neucodec-decoder-int8.onnx")
        if (oldOnnxFile.exists()) oldOnnxFile.delete()

        _downloadState.value = DownloadState.Idle
    }
}
