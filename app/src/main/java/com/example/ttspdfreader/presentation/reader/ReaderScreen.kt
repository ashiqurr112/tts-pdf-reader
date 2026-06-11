package com.example.ttspdfreader.presentation.reader

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.pdf.view.PdfView
import androidx.pdf.viewer.fragment.PdfViewerFragment
import com.example.ttspdfreader.R
import com.example.ttspdfreader.service.TtsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale

@Composable
fun ReaderScreen(
    onBack: () -> Unit,
    onNavigateToDownload: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReaderViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val ttsState by viewModel.ttsState.collectAsStateWithLifecycle()
    val currentSentenceIndex by viewModel.currentSentenceIndex.collectAsStateWithLifecycle()
    val sentences by viewModel.sentences.collectAsStateWithLifecycle()
    val playbackSpeed by viewModel.playbackSpeed.collectAsStateWithLifecycle()
    val servicePage by viewModel.servicePage.collectAsStateWithLifecycle()

    var sharedUri by remember { mutableStateOf<Uri?>(null) }
    var isCopying by remember { mutableStateOf(false) }

    LaunchedEffect(state) {
        val currentState = state
        if (currentState is ReaderUiState.Success) {
            isCopying = true
            sharedUri = null
            withContext(Dispatchers.IO) {
                try {
                    // Clean up old cached files
                    context.cacheDir.listFiles()?.forEach { file ->
                        if (file.name.startsWith("pdf_") && file.name.endsWith(".pdf")) {
                            file.delete()
                        }
                    }
                    val cacheFile = File(context.cacheDir, "pdf_${System.currentTimeMillis()}.pdf")
                    context.contentResolver.openInputStream(currentState.uri)?.use { inputStream ->
                        cacheFile.outputStream().use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    } ?: throw FileNotFoundException("Could not open PDF file")

                    sharedUri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        cacheFile
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    isCopying = false
                }
            }
        }
    }

    BackHandler {
        viewModel.stopReading()
        onBack()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            if (state is ReaderUiState.Success) {
                // Hiding top bar on success to avoid double toolbar / large top padding
            } else {
                ReaderTopBar(
                    title = "PDF Reader",
                    onBack = {
                        viewModel.stopReading()
                        onBack()
                    }
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (state is ReaderUiState.Success) PaddingValues(0.dp) else paddingValues)
        ) {
            when (val currentState = state) {
                is ReaderUiState.Loading -> {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        CircularProgressIndicator()
                    }
                }
                is ReaderUiState.Error -> {
                    Column(
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxSize().padding(24.dp)
                    ) {
                        Text(
                            text = currentState.message,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = onBack) {
                            Text("Go Back")
                        }
                    }
                }
                is ReaderUiState.Success -> {
                    val uri = sharedUri
                    if (isCopying || uri == null) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            CircularProgressIndicator()
                        }
                    } else {
                        DisposableEffect(uri) {
                            onDispose {
                                val activity = context.findActivity()
                                if (activity != null) {
                                    val fragmentManager = activity.supportFragmentManager
                                    val fragment = fragmentManager.findFragmentById(R.id.pdf_container)
                                    if (fragment != null) {
                                        fragmentManager.beginTransaction()
                                            .remove(fragment)
                                            .commitAllowingStateLoss()
                                    }
                                }
                            }
                        }

                        Box(modifier = Modifier.fillMaxSize()) {
                            key(uri) {
                                AndroidView(
                                    modifier = Modifier.fillMaxSize(),
                                    factory = { ctx ->
                                        val container = FragmentContainerView(ctx).apply {
                                            id = R.id.pdf_container
                                        }
                                        val activity = ctx.findActivity()
                                        if (activity != null) {
                                            val fragmentManager = activity.supportFragmentManager
                                            val existingFragment = fragmentManager.findFragmentById(R.id.pdf_container)
                                            if (existingFragment != null) {
                                                fragmentManager.beginTransaction().remove(existingFragment).commitNow()
                                            }

                                            val fragment = TtsPdfViewerFragment().apply {
                                                setDocumentUriToLoad(uri)
                                                setInitialPage(currentState.initialPage)
                                                setOnPageChangedListener { page ->
                                                    viewModel.onPageChanged(page)
                                                }
                                            }

                                            fragmentManager.beginTransaction()
                                                .replace(R.id.pdf_container, fragment)
                                                .commit()
                                        }
                                        container
                                    },
                                    update = { container ->
                                        if (ttsState == TtsState.PLAYING || ttsState == TtsState.LOADING) {
                                            val activity = container.context.findActivity()
                                            if (activity != null) {
                                                val fragment = activity.supportFragmentManager.findFragmentById(R.id.pdf_container) as? TtsPdfViewerFragment
                                                if (fragment != null && servicePage != -1) {
                                                    fragment.scrollToPage(servicePage)
                                                }
                                            }
                                        }
                                    }
                                )
                            }

                            // Floating back button
                            IconButton(
                                onClick = {
                                    viewModel.stopReading()
                                    onBack()
                                },
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(16.dp)
                                    .statusBarsPadding()
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), shape = CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            // TTS Bottom Control Panel
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .padding(16.dp)
                                    .navigationBarsPadding()
                            ) {
                                val currentSentenceText = if (currentSentenceIndex in sentences.indices) {
                                    sentences[currentSentenceIndex].text
                                } else ""

                                TtsControlPanel(
                                    ttsState = ttsState,
                                    currentSentenceText = currentSentenceText,
                                    playbackSpeed = playbackSpeed,
                                    hasModels = viewModel.hasDownloadedModels(),
                                    onPlayPause = {
                                        if (!viewModel.hasDownloadedModels()) {
                                            onNavigateToDownload()
                                        } else {
                                            if (ttsState == TtsState.PLAYING) {
                                                viewModel.pauseReading()
                                            } else if (ttsState == TtsState.PAUSED) {
                                                viewModel.resumeReading()
                                            } else {
                                                viewModel.startReading(currentState.uri, currentState.title)
                                            }
                                        }
                                    },
                                    onStop = { viewModel.stopReading() },
                                    onPrev = { viewModel.skipPrev() },
                                    onNext = { viewModel.skipNext() },
                                    onSpeedChange = { viewModel.setSpeed(it) },
                                    onNavigateToDownload = onNavigateToDownload
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TtsControlPanel(
    ttsState: TtsState,
    currentSentenceText: String,
    playbackSpeed: Float,
    hasModels: Boolean,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onNavigateToDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showSpeedDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Sentence overlay text
        if (currentSentenceText.isNotBlank() && ttsState != TtsState.IDLE) {
            Text(
                text = currentSentenceText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                    .padding(12.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Speed Dialog Trigger
            IconButton(onClick = { showSpeedDialog = true }) {
                Icon(
                    imageVector = Icons.Default.Speed,
                    contentDescription = "Playback Speed",
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            // Prev Sentence
            IconButton(
                onClick = onPrev,
                enabled = ttsState != TtsState.IDLE
            ) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Previous Sentence"
                )
            }

            // Play/Pause FAB
            FloatingActionButton(
                onClick = onPlayPause,
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                val icon = when (ttsState) {
                    TtsState.PLAYING -> Icons.Default.Pause
                    TtsState.LOADING -> Icons.Default.HourglassEmpty
                    else -> Icons.Default.PlayArrow
                }
                Icon(
                    imageVector = icon,
                    contentDescription = if (ttsState == TtsState.PLAYING) "Pause" else "Play",
                    modifier = Modifier.size(28.dp)
                )
            }

            // Next Sentence
            IconButton(
                onClick = onNext,
                enabled = ttsState != TtsState.IDLE
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next Sentence"
                )
            }

            // Stop Button
            IconButton(
                onClick = onStop,
                enabled = ttsState != TtsState.IDLE
            ) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = "Stop Reading",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }

        // Inform user if models need download
        if (!hasModels) {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onNavigateToDownload) {
                Icon(Icons.Default.CloudDownload, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("TTS speech models required. Download now.")
            }
        }
    }

    if (showSpeedDialog) {
        AlertDialog(
            onDismissRequest = { showSpeedDialog = false },
            title = { Text("Playback Speed") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = String.format(Locale.US, "%.2fx", playbackSpeed),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Slider(
                        value = playbackSpeed,
                        onValueChange = onSpeedChange,
                        valueRange = 0.75f..2.0f,
                        steps = 5
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showSpeedDialog = false }) {
                    Text("OK")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back"
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
        )
    )
}

private fun Context.findActivity(): FragmentActivity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is FragmentActivity) return context
        context = context.baseContext
    }
    return null
}

class TtsPdfViewerFragment : PdfViewerFragment() {
    private var onPageChangedListener: ((Int) -> Unit)? = null
    private var initialPage: Int = 0
    private var documentUriToLoad: Uri? = null
    private var pdfViewReference: PdfView? = null

    fun setOnPageChangedListener(listener: (Int) -> Unit) {
        this.onPageChangedListener = listener
    }

    fun setInitialPage(page: Int) {
        this.initialPage = page
    }

    fun setDocumentUriToLoad(uri: Uri) {
        this.documentUriToLoad = uri
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        documentUriToLoad?.let { uri ->
            documentUri = uri
        }
    }

    @OptIn(androidx.pdf.ExperimentalPdfApi::class)
    override fun onPdfViewCreated(pdfView: PdfView) {
        super.onPdfViewCreated(pdfView)
        this.pdfViewReference = pdfView

        // 1. Scroll to initial page when content loads
        pdfView.addOnFirstContentLoadListener {
            if (initialPage > 0) {
                pdfView.scrollToPage(initialPage)
            }
        }

        // 2. Save page progress when viewport changes
        pdfView.addOnViewportChangedListener(object : PdfView.OnViewportChangedListener {
            override fun onViewportChanged(
                firstVisiblePage: Int,
                visiblePagesCount: Int,
                pageLocations: android.util.SparseArray<android.graphics.RectF>,
                zoomLevel: Float
            ) {
                onPageChangedListener?.invoke(firstVisiblePage)
            }
        })
    }

    @OptIn(androidx.pdf.ExperimentalPdfApi::class)
    fun scrollToPage(page: Int) {
        pdfViewReference?.let { view ->
            try {
                view.scrollToPage(page)
            } catch (e: Exception) {
                // View might not be laid out yet
            }
        }
    }
}
