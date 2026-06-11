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
import android.content.Context
import android.content.ContextWrapper
import com.example.ttspdfreader.R
import android.os.Bundle

import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun ReaderScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReaderViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    BackHandler {
        onBack()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            if (state is ReaderUiState.Success) {
                // You can add an AnimatedVisibility here if you want to toggle it
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
                .padding(paddingValues)
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
                    // For maximum compatibility and stability (and to guarantee search UI), 
                    // we use the official PdfViewerFragment via AndroidView.
                    // The Fragment provides the built-in app bar with search, bookmarks, and continuous scrolling!
                    val context = LocalContext.current
                    
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
                                if (existingFragment == null) {
                                    val fragment = PdfViewerFragment().apply {
                                        arguments = Bundle().apply {
                                            putParcelable("documentUri", currentState.uri)
                                        }
                                    }
                                    fragmentManager.beginTransaction()
                                        .replace(R.id.pdf_container, fragment)
                                        .commit()
                                }
                            }
                            container
                        },
                        update = { container ->
                            val activity = container.context.findActivity()
                            if (activity != null) {
                                val fragment = activity.supportFragmentManager.findFragmentById(R.id.pdf_container) as? PdfViewerFragment
                                if (fragment != null && fragment.documentUri != currentState.uri) {
                                    fragment.documentUri = currentState.uri
                                }
                            }
                        }
                    )
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
