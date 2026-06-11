package com.example.ttspdfreader.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.ttspdfreader.MainActivity
import com.example.ttspdfreader.R
import com.example.ttspdfreader.data.local.SettingsManager
import com.example.ttspdfreader.data.tts.AudioRenderer
import com.example.ttspdfreader.data.tts.NeuTTSEngine
import com.example.ttspdfreader.data.tts.Sentence
import com.example.ttspdfreader.data.tts.SentenceChunker
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

enum class TtsState {
    IDLE,
    LOADING,
    PLAYING,
    PAUSED,
    ERROR
}

@AndroidEntryPoint
class ReadAloudService : Service(), AudioManager.OnAudioFocusChangeListener {

    companion object {
        private const val TAG = "ReadAloudService"
        private const val CHANNEL_ID = "read_aloud_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY = "com.example.ttspdfreader.ACTION_PLAY"
        const val ACTION_PAUSE = "com.example.ttspdfreader.ACTION_PAUSE"
        const val ACTION_STOP = "com.example.ttspdfreader.ACTION_STOP"
        const val ACTION_PREVIOUS = "com.example.ttspdfreader.ACTION_PREVIOUS"
        const val ACTION_NEXT = "com.example.ttspdfreader.ACTION_NEXT"
    }

    @Inject
    lateinit var ttsEngine: NeuTTSEngine

    @Inject
    lateinit var audioRenderer: AudioRenderer

    @Inject
    lateinit var sentenceChunker: SentenceChunker

    @Inject
    lateinit var settingsManager: SettingsManager

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private var playbackJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    // Service state flows
    private val _ttsState = MutableStateFlow(TtsState.IDLE)
    val ttsState: StateFlow<TtsState> = _ttsState.asStateFlow()

    private val _currentSentenceIndex = MutableStateFlow(-1)
    val currentSentenceIndex: StateFlow<Int> = _currentSentenceIndex.asStateFlow()

    private val _currentBookTitle = MutableStateFlow("")
    val currentBookTitle: StateFlow<String> = _currentBookTitle.asStateFlow()

    private val _currentPage = MutableStateFlow(-1)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _sentences = MutableStateFlow<List<Sentence>>(emptyList())
    val sentences: StateFlow<List<Sentence>> = _sentences.asStateFlow()

    private var pdfUri: Uri? = null
    private var totalPagesCount = 0

    inner class LocalBinder : Binder() {
        fun getService(): ReadAloudService = this@ReadAloudService
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pauseReading()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TtsPdfReader::ReadAloudWakeLock")

        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        
        // Initialize playback speed from preferences
        _playbackSpeed.value = settingsManager.getSpeed()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> resumeReading()
            ACTION_PAUSE -> pauseReading()
            ACTION_STOP -> stopReading()
            ACTION_PREVIOUS -> skipToPreviousSentence()
            ACTION_NEXT -> skipToNextSentence()
        }
        return START_NOT_STICKY
    }

    fun startReading(uriString: String, title: String, startPage: Int, startSentenceIndex: Int) {
        serviceScope.launch {
            pdfUri = Uri.parse(uriString)
            _currentBookTitle.value = title
            _playbackSpeed.value = settingsManager.getSpeed()
            audioRenderer.setSpeed(_playbackSpeed.value)

            // Start foreground immediately to meet Android OS requirements
            try {
                startForeground(NOTIFICATION_ID, buildNotification())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start foreground service", e)
                _ttsState.value = TtsState.ERROR
                return@launch
            }

            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(10 * 60 * 1000L /*10 minutes*/)
            }

            // Pre-initialize the TTS engine before reading
            try {
                val engineReady = ttsEngine.init()
                if (!engineReady) {
                    Log.e(TAG, "TTS engine failed to initialize")
                    _ttsState.value = TtsState.ERROR
                    return@launch
                }
            } catch (e: Exception) {
                Log.e(TAG, "TTS engine init crashed", e)
                _ttsState.value = TtsState.ERROR
                return@launch
            }

            loadPageText(startPage, startSentenceIndex)
        }
    }

    private fun loadPageText(pageIndex: Int, startSentenceIndex: Int = 0) {
        _ttsState.value = TtsState.LOADING
        _currentPage.value = pageIndex
        updateNotification()

        val uri = pdfUri ?: run {
            Log.e(TAG, "PDF URI is null, cannot load page text")
            _ttsState.value = TtsState.ERROR
            return
        }
        serviceScope.launch {
            try {
                val pageText = withContext(Dispatchers.IO) {
                    try {
                        contentResolver.openInputStream(uri)?.use { input ->
                            PDDocument.load(input).use { document ->
                                totalPagesCount = document.numberOfPages
                                val stripper = PDFTextStripper().apply {
                                    startPage = pageIndex + 1
                                    endPage = pageIndex + 1
                                }
                                stripper.getText(document)
                            }
                        } ?: ""
                    } catch (securityEx: SecurityException) {
                        Log.e(TAG, "SecurityException opening PDF URI - permission may have been revoked", securityEx)
                        ""
                    }
                }

                if (pageText.isBlank() && totalPagesCount == 0) {
                    Log.e(TAG, "Could not read PDF - URI may be invalid or permission revoked")
                    _ttsState.value = TtsState.ERROR
                    updateNotification()
                    return@launch
                }

                val chunkedSentences = sentenceChunker.chunk(pageText)
                _sentences.value = chunkedSentences

                if (chunkedSentences.isEmpty()) {
                    // Try next page if empty page
                    if (pageIndex + 1 < totalPagesCount) {
                        loadPageText(pageIndex + 1, 0)
                    } else {
                        stopReading()
                    }
                    return@launch
                }

                val targetIndex = startSentenceIndex.coerceIn(0, chunkedSentences.lastIndex)
                readSentence(targetIndex)
            } catch (e: Exception) {
                Log.e(TAG, "Error reading page text", e)
                _ttsState.value = TtsState.ERROR
                updateNotification()
            }
        }
    }

    private fun readSentence(index: Int) {
        if (index !in _sentences.value.indices) {
            // Reached end of page
            val nextPage = _currentPage.value + 1
            if (nextPage < totalPagesCount) {
                loadPageText(nextPage, 0)
            } else {
                stopReading()
            }
            return
        }

        _currentSentenceIndex.value = index
        _ttsState.value = TtsState.PLAYING
        updateNotification()

        val sentence = _sentences.value[index]

        if (!requestAudioFocus()) {
            _ttsState.value = TtsState.PAUSED
            updateNotification()
            return
        }

        playbackJob?.cancel()
        playbackJob = serviceScope.launch {
            try {
                ttsEngine.setReferenceVoice(loadReferenceVoiceTokens())

                // Safely attempt synthesis - init may fail if native libs can't load
                val audioFlow = try {
                    ttsEngine.synthesize(sentence.text, _playbackSpeed.value)
                } catch (initError: Exception) {
                    Log.e(TAG, "TTS synthesis/init failed", initError)
                    _ttsState.value = TtsState.ERROR
                    updateNotification()
                    return@launch
                }

                val safeAudioFlow = audioFlow.catch { e ->
                    Log.e(TAG, "Synthesis stream failed", e)
                    _ttsState.value = TtsState.ERROR
                    updateNotification()
                }

                audioRenderer.play(safeAudioFlow) {
                    // Completion callback of playback
                    serviceScope.launch {
                        if (_ttsState.value == TtsState.PLAYING) {
                            readSentence(index + 1)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in playback flow", e)
                _ttsState.value = TtsState.ERROR
                updateNotification()
            }
        }
    }

    private suspend fun loadReferenceVoiceTokens(): IntArray? = withContext(Dispatchers.IO) {
        val tokensFile = File(filesDir, "voice/reference_tokens.dat")
        if (tokensFile.exists() && settingsManager.hasReferenceVoice()) {
            try {
                tokensFile.readBytes().let { bytes ->
                    val ints = IntArray(bytes.size / 4)
                    java.nio.ByteBuffer.wrap(bytes).asIntBuffer().get(ints)
                    return@withContext ints
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load reference voice tokens", e)
            }
        }
        null
    }

    fun pauseReading() {
        serviceScope.launch {
            _ttsState.value = TtsState.PAUSED
            updateNotification()
            audioRenderer.pause()
            playbackJob?.cancel()
        }
    }

    fun resumeReading() {
        if (_ttsState.value == TtsState.PAUSED) {
            val currentIndex = _currentSentenceIndex.value
            if (currentIndex in _sentences.value.indices) {
                readSentence(currentIndex)
            } else {
                loadPageText(_currentPage.value, 0)
            }
        }
    }

    fun stopReading() {
        serviceScope.launch {
            _ttsState.value = TtsState.IDLE
            _currentSentenceIndex.value = -1
            _currentPage.value = -1
            playbackJob?.cancel()
            audioRenderer.stop()
            abandonAudioFocus()
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    fun skipToNextSentence() {
        val nextIndex = _currentSentenceIndex.value + 1
        if (nextIndex in _sentences.value.indices) {
            readSentence(nextIndex)
        } else {
            val nextPage = _currentPage.value + 1
            if (nextPage < totalPagesCount) {
                loadPageText(nextPage, 0)
            }
        }
    }

    fun skipToPreviousSentence() {
        val prevIndex = _currentSentenceIndex.value - 1
        if (prevIndex >= 0) {
            readSentence(prevIndex)
        } else {
            val prevPage = _currentPage.value - 1
            if (prevPage >= 0) {
                // To skip to previous page's last sentence, we load that page and extract
                // But for simplicity, we load previous page at index 0, or we can handle it dynamically
                loadPageText(prevPage, 0)
            }
        }
    }

    fun setSpeed(speed: Float) {
        serviceScope.launch {
            _playbackSpeed.value = speed
            settingsManager.setSpeed(speed)
            audioRenderer.setSpeed(speed)
        }
    }

    private fun requestAudioFocus(): Boolean {
        if (audioManager == null) return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(this)
                .build()

            audioManager?.requestAudioFocus(focusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(
                this,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
            audioManager?.abandonAudioFocusRequest(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(this)
        }
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> pauseReading()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pauseReading()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // We can lower volume if wanted, but for speech, pausing is cleaner
                pauseReading()
            }
            AudioManager.AUDIOFOCUS_GAIN -> resumeReading()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Text-To-Speech Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controls for reading PDF text aloud"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val context = this
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // PendingIntents for Media Controls
        val prevPendingIntent = PendingIntent.getService(
            context, 1, Intent(context, ReadAloudService::class.java).apply { action = ACTION_PREVIOUS },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playActionPendingIntent = PendingIntent.getService(
            context, 2, Intent(context, ReadAloudService::class.java).apply {
                action = if (_ttsState.value == TtsState.PLAYING) ACTION_PAUSE else ACTION_PLAY
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPendingIntent = PendingIntent.getService(
            context, 3, Intent(context, ReadAloudService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nextPendingIntent = PendingIntent.getService(
            context, 4, Intent(context, ReadAloudService::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sentencesList = _sentences.value
        val index = _currentSentenceIndex.value
        val sentenceText = if (index in sentencesList.indices) {
            sentencesList[index].text
        } else if (_ttsState.value == TtsState.LOADING) {
            "Loading page content..."
        } else {
            "Tap play to start reading"
        }

        val playIcon = if (_ttsState.value == TtsState.PLAYING) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }

        val playTitle = if (_ttsState.value == TtsState.PLAYING) "Pause" else "Play"

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(_currentBookTitle.value.ifBlank { "PDF Read Aloud" })
            .setContentText(sentenceText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "Previous", prevPendingIntent)
            .addAction(playIcon, playTitle, playActionPendingIntent)
            .addAction(android.R.drawable.ic_media_next, "Next", nextPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun updateNotification() {
        if (_ttsState.value != TtsState.IDLE) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(noisyReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
        stopReading()
        serviceScope.launch {
            try {
                audioRenderer.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing audio renderer", e)
            }
            try {
                ttsEngine.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing TTS engine", e)
            }
        }
    }
}
