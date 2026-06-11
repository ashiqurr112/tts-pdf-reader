package com.example.ttspdfreader.presentation.tts

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ttspdfreader.data.local.SettingsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import kotlin.math.abs

sealed class RecordingState {
    object Idle : RecordingState()
    object Recording : RecordingState()
    data class Success(val wavFile: File, val durationSeconds: Float) : RecordingState()
    data class Error(val message: String) : RecordingState()
}

@HiltViewModel
class VoiceSetupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsManager: SettingsManager
) : ViewModel() {

    companion object {
        private const val TAG = "VoiceSetupViewModel"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    private val _recordingDuration = MutableStateFlow(0f)
    val recordingDuration: StateFlow<Float> = _recordingDuration.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingJob: Job? = null
    private var timerJob: Job? = null

    private val voiceDir = File(context.filesDir, "voice")
    private val tempPcmFile = File(voiceDir, "temp.pcm")
    val referenceWavFile = File(voiceDir, "reference.wav")
    private val tokensFile = File(voiceDir, "reference_tokens.dat")

    init {
        if (!voiceDir.exists()) {
            voiceDir.mkdirs()
        }
        if (settingsManager.hasReferenceVoice() && referenceWavFile.exists()) {
            _recordingState.value = RecordingState.Success(referenceWavFile, 5f) // Approximate duration
        }
    }

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (isRecording) return

        viewModelScope.launch {
            try {
                val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
                if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                    _recordingState.value = RecordingState.Error("Unsupported audio configuration on this device")
                    return@launch
                }

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    minBufferSize * 2
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    _recordingState.value = RecordingState.Error("Failed to initialize AudioRecord")
                    return@launch
                }

                audioRecord?.startRecording()
                isRecording = true
                _recordingState.value = RecordingState.Recording
                _recordingDuration.value = 0f

                // Audio recording thread
                recordingJob = launch(Dispatchers.IO) {
                    writeAudioDataToFile(minBufferSize)
                }

                // UI timer and amplitude polling
                timerJob = launch {
                    val startTime = System.currentTimeMillis()
                    while (isRecording) {
                        delay(100)
                        val elapsed = (System.currentTimeMillis() - startTime) / 1000f
                        _recordingDuration.value = elapsed

                        if (elapsed >= 15f) { // Max 15 seconds limit
                            stopRecording()
                            break
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start recording", e)
                _recordingState.value = RecordingState.Error("Error starting recording: ${e.message}")
            }
        }
    }

    private suspend fun writeAudioDataToFile(bufferSize: Int) {
        val data = ByteArray(bufferSize)
        var outputStream: FileOutputStream? = null
        try {
            outputStream = FileOutputStream(tempPcmFile)
            while (isRecording) {
                val read = audioRecord?.read(data, 0, bufferSize) ?: 0
                if (read > 0) {
                    outputStream.write(data, 0, read)
                    calculateAmplitude(data, read)
                }
            }
            outputStream.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error writing audio PCM file", e)
        } finally {
            outputStream?.close()
        }
    }

    private fun calculateAmplitude(data: ByteArray, bytesRead: Int) {
        var sum = 0L
        val shortsCount = bytesRead / 2
        if (shortsCount <= 0) return

        val buffer = ByteBuffer.wrap(data, 0, bytesRead).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until shortsCount) {
            if (buffer.remaining() >= 2) {
                val sample = buffer.short
                sum += abs(sample.toInt())
            }
        }
        val avg = sum.toFloat() / shortsCount
        // Normalize between 0.0 and 1.0 (assuming max amplitude is ~20000 for standard speech)
        _amplitude.value = (avg / 20000f).coerceIn(0f, 1f)
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false

        recordingJob?.cancel()
        timerJob?.cancel()

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio recorder", e)
        }

        val duration = _recordingDuration.value
        if (duration < 3.0f) {
            _recordingState.value = RecordingState.Error("Audio clip is too short. Record for at least 3 seconds.")
            tempPcmFile.delete()
            return
        }

        viewModelScope.launch {
            _recordingState.value = RecordingState.Idle // Intermediate state
            val success = withContext(Dispatchers.IO) {
                convertPcmToWav(tempPcmFile, referenceWavFile)
            }
            if (success) {
                _recordingState.value = RecordingState.Success(referenceWavFile, duration)
            } else {
                _recordingState.value = RecordingState.Error("Failed to encode WAV audio")
            }
        }
    }

    private fun convertPcmToWav(pcmFile: File, wavFile: File): Boolean {
        if (!pcmFile.exists()) return false

        try {
            val pcmSize = pcmFile.length()
            val totalDataLen = pcmSize + 36
            val byteRate = SAMPLE_RATE * 2L // 16-bit mono = 2 bytes per sample

            FileInputStream(pcmFile).use { input ->
                FileOutputStream(wavFile).use { output ->
                    writeWavHeader(output, pcmSize, totalDataLen, SAMPLE_RATE.toLong(), 1, byteRate)
                    input.copyTo(output)
                }
            }
            pcmFile.delete()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error converting PCM to WAV", e)
            return false
        }
    }

    private fun writeWavHeader(
        out: FileOutputStream,
        totalAudioLen: Long,
        totalDataLen: Long,
        longSampleRate: Long,
        channels: Int,
        byteRate: Long
    ) {
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte() // RIFF
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte() // WAVE
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte() // 'fmt ' chunk
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16 // subchunk1size (16 for PCM)
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // audioFormat (1 for PCM)
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (longSampleRate and 0xff).toByte()
        header[25] = ((longSampleRate shr 8) and 0xff).toByte()
        header[26] = ((longSampleRate shr 16) and 0xff).toByte()
        header[27] = ((longSampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * 2).toByte() // blockalign
        header[33] = 0
        header[34] = 16 // bitsPerSample (16 bits)
        header[35] = 0
        header[36] = 'd'.code.toByte() // 'data' chunk
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()
        out.write(header, 0, 44)
    }

    fun saveClonedVoice(transcription: String) {
        if (transcription.isBlank()) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Generate and save dummy voice reference tokens for the LLM pipeline
                val dummyTokens = IntArray(128) { (1000..5000).random() }
                val byteBuffer = ByteBuffer.allocate(dummyTokens.size * 4)
                byteBuffer.asIntBuffer().put(dummyTokens)
                tokensFile.writeBytes(byteBuffer.array())

                settingsManager.setHasReferenceVoice(true)
                _recordingState.value = RecordingState.Success(referenceWavFile, _recordingDuration.value)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save reference voice tokens", e)
            }
        }
    }

    fun deleteReferenceVoice() {
        if (referenceWavFile.exists()) referenceWavFile.delete()
        if (tokensFile.exists()) tokensFile.delete()
        settingsManager.setHasReferenceVoice(false)
        _recordingState.value = RecordingState.Idle
        _recordingDuration.value = 0f
    }
}
