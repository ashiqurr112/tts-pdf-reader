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

    // FIX 1: @Volatile so the write() loop on Dispatchers.Default sees the flag
    // immediately when pause()/stop() set it to false on another thread, eliminating
    // the data race that caused audio to keep playing after pause/stop.
    @Volatile
    private var isPlaying = false
    private var playbackSpeed = 1.0f

    // FIX 2: Track the internal playback job so pause() and stop() can cancel it.
    // Previously play() created a detached CoroutineScope(Dispatchers.Default) whose
    // Job was returned to (and ignored by) ReadAloudService. Cancelling playbackJob in
    // the service only killed the serviceScope coroutine; the renderer kept collecting
    // the audio flow and calling write() — and, critically, always fired onComplete(),
    // advancing to the next sentence even after a pause or skip.
    private var rendererJob: Job? = null
    private val rendererScope = CoroutineScope(Dispatchers.Default)
    private var playGeneration = 0

    suspend fun init() {
        mutex.withLock {
            if (audioTrack != null) return

            val minBufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
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
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
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

        // FIX: cancel any still-running renderer job before starting a new one,
        // so stale audio from a previous sentence can't fire onComplete() late.
        rendererJob?.cancel()

        val thisGeneration = ++playGeneration
        isPlaying = true

        // FIX: use the shared rendererScope (not a fresh detached scope) so the
        // job is reachable and cancellable via rendererJob.
        rendererJob = rendererScope.launch {
            try {
                val initialHeadPos = (audioTrack?.playbackHeadPosition?.toLong() ?: 0L) and 0xFFFFFFFFL
                var sentenceFrames = 0
                var isStarted = false
                audioChunks.collect { chunk ->
                    if (!isStarted) {
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
                        isStarted = true
                    }
                    val shortChunk = ShortArray(chunk.size)
                    for (i in chunk.indices) {
                        shortChunk[i] = (chunk[i].coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()
                    }
                    val written = write(shortChunk)
                    sentenceFrames += written
                }
                waitForPlaybackComplete(initialHeadPos + sentenceFrames.toLong())
                audioTrack?.pause()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error during audio playback collection", e)
            } finally {
                // FIX: only call onComplete() if we are still in a playing state,
                // i.e. the job finished naturally rather than being cancelled by
                // pause()/stop()/skip(). This prevents the "advance to next sentence
                // after pause" bug where the detached job always fired onComplete.
                if (isPlaying && thisGeneration == playGeneration) {
                    onComplete()
                }
            }
        }
        return rendererJob!!
    }

    private fun write(chunk: ShortArray): Int {
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
        return offset
    }

    private suspend fun waitForPlaybackComplete(targetFrameCount: Long) {
        val track = audioTrack ?: return
        try {
            while (isPlaying) {
                val currentHead = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                if (currentHead >= targetFrameCount) {
                    break
                }
                val remainingFrames = targetFrameCount - currentHead
                val remainingMs = (remainingFrames.toFloat() / track.sampleRate * 1000 / playbackSpeed).toLong()
                delay(remainingMs.coerceIn(20L, 200L))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error waiting for audio track completion", e)
        }
    }

    suspend fun pause() {
        // FIX: cancel the renderer job first so write() stops and onComplete() is
        // suppressed, then update the flag and pause the hardware track.
        rendererJob?.cancel()
        rendererJob = null
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
        // FIX: same as pause — cancel job before touching the flag.
        rendererJob?.cancel()
        rendererJob = null
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
        rendererJob?.cancel()
        rendererJob = null
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
