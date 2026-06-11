package com.example.ttspdfreader.data.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioRenderer @Inject constructor() {
    companion object {
        private const val TAG = "AudioRenderer"
        private const val SAMPLE_RATE = 24000
    }

    private var audioTrack: AudioTrack? = null
    private val mutex = Mutex()
    private var isPlaying = false
    private var playbackSpeed = 1.0f

    suspend fun init() {
        mutex.withLock {
            if (audioTrack != null) return

            val minBufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT
            )

            // Double buffering
            val bufferSize = (minBufferSize * 2).coerceAtLeast(minBufferSize)

            try {
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                setSpeedInternal(playbackSpeed)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create AudioTrack", e)
            }
        }
    }

    suspend fun play(audioChunks: Flow<FloatArray>, onComplete: () -> Unit = {}): Job {
        init()
        isPlaying = true
        try {
            audioTrack?.let { track ->
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    track.play()
                }
            }
        } catch (e: IllegalStateException) {
            Log.e(TAG, "AudioTrack play failed, reinitializing", e)
            mutex.withLock {
                audioTrack?.release()
                audioTrack = null
            }
            init()
            audioTrack?.play()
        }

        return CoroutineScope(Dispatchers.Default).launch {
            try {
                audioChunks.collect { chunk ->
                    write(chunk)
                }
                waitForPlaybackComplete()
            } catch (e: Exception) {
                Log.e(TAG, "Error during audio playback collection", e)
            } finally {
                onComplete()
            }
        }
    }

    private fun write(chunk: FloatArray) {
        var offset = 0
        while (offset < chunk.size && isPlaying) {
            val track = audioTrack ?: break
            val written = track.write(chunk, offset, chunk.size - offset, AudioTrack.WRITE_BLOCKING)
            if (written < 0) {
                Log.e(TAG, "AudioTrack write error: $written")
                break
            }
            offset += written
        }
    }

    private suspend fun waitForPlaybackComplete() {
        val track = audioTrack ?: return
        try {
            // Wait until the audio track head position matches the play length
            val bufferSize = track.bufferSizeInFrames
            val sampleRate = track.sampleRate
            val delayMs = (bufferSize.toFloat() / sampleRate * 1000).toLong()
            delay(delayMs.coerceAtLeast(200L))
        } catch (e: Exception) {
            Log.e(TAG, "Error waiting for audio track completion", e)
        }
    }

    suspend fun pause() {
        mutex.withLock {
            isPlaying = false
            audioTrack?.pause()
        }
    }

    suspend fun resume() {
        mutex.withLock {
            audioTrack?.play()
            isPlaying = true
        }
    }

    suspend fun stop() {
        mutex.withLock {
            isPlaying = false
            audioTrack?.apply {
                try {
                    stop()
                    flush()
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping AudioTrack", e)
                }
            }
        }
    }

    suspend fun setSpeed(speed: Float) {
        mutex.withLock {
            playbackSpeed = speed
            setSpeedInternal(speed)
        }
    }

    private fun setSpeedInternal(speed: Float) {
        val track = audioTrack ?: return
        try {
            val params = PlaybackParams()
            params.speed = speed
            track.playbackParams = params
        } catch (e: Exception) {
            Log.e(TAG, "Error setting playback speed on AudioTrack", e)
        }
    }

    suspend fun release() {
        mutex.withLock {
            isPlaying = false
            audioTrack?.apply {
                try {
                    stop()
                    release()
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing AudioTrack", e)
                }
            }
            audioTrack = null
        }
    }
}
