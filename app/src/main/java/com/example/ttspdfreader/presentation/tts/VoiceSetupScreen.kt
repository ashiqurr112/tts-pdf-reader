package com.example.ttspdfreader.presentation.tts

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSetupScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VoiceSetupViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val recordingState by viewModel.recordingState.collectAsStateWithLifecycle()
    val recordingDuration by viewModel.recordingDuration.collectAsStateWithLifecycle()
    val amplitude by viewModel.amplitude.collectAsStateWithLifecycle()

    var transcription by remember { mutableStateOf("") }
    // FIX: Track whether we are in the "encoding" phase (after Save is tapped but before
    //      the NeuCodec encoder has finished). During this time we disable the button
    //      and show an in-progress indicator so the user doesn't tap twice.
    var isSaving by remember { mutableStateOf(false) }

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            hasMicPermission = isGranted
            if (!isGranted) {
                Toast.makeText(context, "Microphone permission is required to record reference voice", Toast.LENGTH_LONG).show()
            }
        }
    )

    // FIX: Observe recordingState to:
    //   • Navigate back (with a success toast) once saving completes.
    //   • Show an error toast and reset isSaving if encoding fails.
    //   • Reset isSaving when recording transitions away from Success (e.g. deleted).
    LaunchedEffect(recordingState) {
        when (val state = recordingState) {
            is RecordingState.Success -> {
                if (isSaving) {
                    // Encoding finished successfully — navigate away.
                    isSaving = false
                    Toast.makeText(context, "Voice cloned and saved successfully!", Toast.LENGTH_SHORT).show()
                    onBack()
                }
            }
            is RecordingState.Error -> {
                if (isSaving) {
                    isSaving = false
                    Toast.makeText(context, state.message, Toast.LENGTH_LONG).show()
                }
            }
            else -> { /* no-op */ }
        }
    }

    // Store amplitudes over time for the visualizer
    val amplitudeHistory = remember { mutableStateListOf<Float>() }
    LaunchedEffect(recordingState) {
        if (recordingState is RecordingState.Recording) {
            amplitudeHistory.clear()
        }
    }
    LaunchedEffect(amplitude, recordingState) {
        if (recordingState is RecordingState.Recording) {
            amplitudeHistory.add(amplitude)
            if (amplitudeHistory.size > 50) {
                amplitudeHistory.removeAt(0)
            }
        }
    }

    val gradientBackground = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
            MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp)
        )
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voice Cloning Setup", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
                )
            )
        },
        modifier = modifier.fillMaxSize()
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(gradientBackground),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Clone Your Voice",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Record yourself speaking for 3-15 seconds and type the exact transcription below to clone your voice.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Suggestion script
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                        .padding(16.dp)
                ) {
                    Column {
                        Text(
                            text = "Suggested Script to read:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "\"The quick brown fox jumps over the lazy dog. Text to speech with offline voice cloning is running on my mobile device.\"",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Waveform / Visualizer
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (recordingState is RecordingState.Recording) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val barWidth = 8.dp.toPx()
                            val gap = 4.dp.toPx()
                            val totalWidth = size.width
                            val centerY = size.height / 2f

                            val maxVisibleBars = (totalWidth / (barWidth + gap)).toInt()
                            val startIndex = (amplitudeHistory.size - maxVisibleBars).coerceAtLeast(0)

                            for (i in startIndex until amplitudeHistory.size) {
                                val amp = amplitudeHistory[i]
                                val barHeight = (amp * size.height).coerceAtLeast(4.dp.toPx())
                                val x = (i - startIndex) * (barWidth + gap)
                                drawRoundRect(
                                    color = Color(0xFF4CAF50),
                                    topLeft = Offset(x, centerY - barHeight / 2f),
                                    size = Size(barWidth, barHeight),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f)
                                )
                            }
                        }
                    } else if (recordingState is RecordingState.Success) {
                        Text(
                            text = "Voice recorded successfully!",
                            color = Color(0xFF4CAF50),
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Text(
                            text = "Tap MIC to start recording",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Timer & controls
                Text(
                    text = String.format(Locale.US, "%.1fs / 15.0s", recordingDuration),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (recordingDuration in 3.0f..15.0f) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Action Button (Record / Stop / Delete)
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    when (val state = recordingState) {
                        is RecordingState.Recording -> {
                            IconButton(
                                onClick = { viewModel.stopRecording() },
                                modifier = Modifier
                                    .size(72.dp)
                                    .background(MaterialTheme.colorScheme.error, shape = CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Stop,
                                    contentDescription = "Stop Recording",
                                    tint = Color.White,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }
                        is RecordingState.Success -> {
                            IconButton(
                                onClick = { viewModel.deleteReferenceVoice() },
                                modifier = Modifier
                                    .size(56.dp)
                                    .background(MaterialTheme.colorScheme.errorContainer, shape = CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "Delete Recording",
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                        else -> {
                            IconButton(
                                onClick = {
                                    if (hasMicPermission) {
                                        viewModel.startRecording()
                                    } else {
                                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                                modifier = Modifier
                                    .size(72.dp)
                                    .background(MaterialTheme.colorScheme.primary, shape = CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = "Start Recording",
                                    tint = Color.White,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                // Transcription text field & save (Only show if recording completed)
                AnimatedVisibility(
                    visible = recordingState is RecordingState.Success,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column {
                        OutlinedTextField(
                            value = transcription,
                            onValueChange = { transcription = it },
                            label = { Text("Exact Transcription of Recorded Script") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = false,
                            maxLines = 3
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        Button(
                            onClick = {
                                isSaving = true
                                viewModel.saveClonedVoice(transcription)
                                // FIX: Do NOT call onBack() immediately — saveClonedVoice is now
                                // asynchronous (it runs the NeuCodec encoder on a background thread).
                                // Navigation happens via the LaunchedEffect observing recordingState below.
                            },
                            enabled = transcription.isNotBlank() && !isSaving,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Encoding Voice…", fontWeight = FontWeight.Bold)
                            } else {
                                Text("Save and Apply Voice", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
