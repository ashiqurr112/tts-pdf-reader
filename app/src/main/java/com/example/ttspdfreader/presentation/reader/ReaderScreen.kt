package com.example.ttspdfreader.presentation.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.pdf.viewer.fragment.PdfViewerFragment
import androidx.pdf.view.PdfView
import android.content.Context
import android.content.ContextWrapper
import com.example.ttspdfreader.R
import android.os.Bundle
import androidx.compose.foundation.shape.CircleShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import androidx.core.content.FileProvider

import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.compose.ui.viewinterop.AndroidView
import android.net.Uri
import androidx.compose.foundation.background

@Composable
fun ReaderScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReaderViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    
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
                    onBack = onBack
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
                                            // Always clean up existing fragment first to prevent blank screens on reload
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
                                    update = {}
                                )
                            }

                            // Floating circular back button at top start (overlaying the full-screen PDF view)
                            IconButton(
                                onClick = onBack,
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
                        }
                    }
                }
            }
        }
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
}
