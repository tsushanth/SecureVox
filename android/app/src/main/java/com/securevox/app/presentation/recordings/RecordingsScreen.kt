package com.securevox.app.presentation.recordings

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.play.core.review.ReviewManagerFactory
import com.kreativekoala.ratingkit.RatingKit
import com.securevox.app.R
import com.securevox.app.data.model.Recording
import com.securevox.app.data.model.TranscriptionStatus
import com.securevox.app.service.MediaImportService
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(
    onRecordingClick: (String) -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: RecordingsViewModel = viewModel()
) {
    val recordings by viewModel.recordings.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val isPaused by viewModel.isPaused.collectAsState()
    val audioLevel by viewModel.audioLevel.collectAsState()
    val recordingDuration by viewModel.recordingDuration.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val isImporting by viewModel.isImporting.collectAsState()
    val importError by viewModel.importError.collectAsState()
    val transcriptionProgressMap by viewModel.transcriptionProgress.collectAsState()

    // Recording state lives in AudioRecorderService, independent of this screen's
    // lifecycle — but backing out of the app while recording (this is the start
    // destination, so back = exit) skips the ViewModel-level bookkeeping in
    // stopRecording() (duration/fileSize update + kicking off transcription).
    // Intercept back while actively recording so the user explicitly stops first.
    var showBackDuringRecordingDialog by remember { mutableStateOf(false) }
    BackHandler(enabled = isRecording) {
        showBackDuringRecordingDialog = true
    }

    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.triggerReview.collect {
            val activity = context as? Activity ?: return@collect
            val manager = ReviewManagerFactory.create(context)
            manager.requestReviewFlow().addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    manager.launchReviewFlow(activity, task.result)
                }
            }
        }
    }

    var showSearch by remember { mutableStateOf(false) }

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importMedia(it) }
    }

    Scaffold(
        topBar = {
            if (showSearch) {
                SearchBar(
                    query = searchQuery,
                    onQueryChange = { viewModel.setSearchQuery(it) },
                    onClose = {
                        showSearch = false
                        viewModel.setSearchQuery("")
                    }
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    actions = {
                        IconButton(onClick = { showSearch = true }) {
                            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search))
                        }
                        IconButton(
                            onClick = {
                                filePickerLauncher.launch(MediaImportService.getSupportedMimeTypes())
                            },
                            enabled = !isImporting
                        ) {
                            Icon(Icons.Default.FileOpen, contentDescription = stringResource(R.string.import_media))
                        }
                        IconButton(onClick = onSettingsClick) {
                            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (!isRecording) {
                RecordButton(
                    audioLevel = audioLevel,
                    onClick = { viewModel.startRecording() }
                )
            }
        },
        floatingActionButtonPosition = FabPosition.Center
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Recording indicator
            if (isRecording) {
                RecordingIndicator(
                    duration = recordingDuration,
                    audioLevel = audioLevel,
                    isPaused = isPaused,
                    onPause = { viewModel.pauseRecording() },
                    onResume = { viewModel.resumeRecording() },
                    onStop = {
                        viewModel.stopRecording()
                        (context as? Activity)?.let { RatingKit.trackAction(it) }
                    }
                )
            }

            // Importing indicator
            if (isImporting) {
                val importProgress by viewModel.importProgress.collectAsState()
                ImportingIndicator(progress = importProgress)
            }

            // Filter tabs
            FilterTabs(
                currentFilter = filter,
                onFilterSelected = { viewModel.setFilter(it) }
            )

            // Recordings list
            if (recordings.isEmpty() && !isRecording) {
                EmptyState(
                    isFavoritesFilter = filter == RecordingsFilter.FAVORITES,
                    hasSearchQuery = searchQuery.isNotBlank()
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 100.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(recordings, key = { it.id }) { recording ->
                        SwipeableRecordingItem(
                            recording = recording,
                            transcriptionProgress = transcriptionProgressMap[recording.id],
                            onClick = { onRecordingClick(recording.id) },
                            onDelete = { viewModel.deleteRecording(recording) },
                            onToggleFavorite = { viewModel.toggleFavorite(recording) },
                            onRetryTranscription = { viewModel.retryTranscription(recording) }
                        )
                    }
                }
            }
        }
    }

    // Import error dialog
    importError?.let { error ->
        AlertDialog(
            onDismissRequest = { viewModel.clearImportError() },
            title = { Text(stringResource(R.string.import_failed_title)) },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearImportError() }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }

    // Back-while-recording confirmation
    if (showBackDuringRecordingDialog) {
        AlertDialog(
            onDismissRequest = { showBackDuringRecordingDialog = false },
            title = { Text(stringResource(R.string.stop_recording_before_leaving_title)) },
            text = { Text(stringResource(R.string.stop_recording_before_leaving_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.stopRecording()
                        showBackDuringRecordingDialog = false
                    }
                ) {
                    Text(stringResource(R.string.stop_recording))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBackDuringRecordingDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun ImportingIndicator(progress: Float) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.importing_media),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = if (progress > 0f) "${(progress * 100).toInt()}%" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            if (progress > 0f) {
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
                )
            }
        }
    }
}

@Composable
private fun RecordButton(
    audioLevel: Float,
    onClick: () -> Unit
) {
    LargeFloatingActionButton(
        onClick = onClick,
        shape = CircleShape,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = Color.White
    ) {
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = stringResource(R.string.start_recording),
            modifier = Modifier.size(32.dp)
        )
    }
}

@Composable
private fun RecordingIndicator(
    duration: Long,
    audioLevel: Float,
    isPaused: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!isPaused) {
                        val alpha by rememberInfiniteTransition(label = "dot").animateFloat(
                            initialValue = 1f,
                            targetValue = 0.3f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(500),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "dot"
                        )
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(Color.Red.copy(alpha = alpha))
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.4f))
                        )
                    }
                    Text(
                        text = if (isPaused) "Paused" else stringResource(R.string.recording_label),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatDuration(duration),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    // Pause / Resume
                    IconButton(onClick = if (isPaused) onResume else onPause) {
                        Icon(
                            imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = if (isPaused) "Resume" else "Pause",
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    // Stop
                    IconButton(onClick = onStop) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = stringResource(R.string.stop_recording),
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // Audio level bar (hidden while paused)
            if (!isPaused) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.2f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(audioLevel)
                            .fillMaxHeight()
                            .background(Color.Red)
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun TranscriptionStatusChip(status: TranscriptionStatus) {
    val (text, color) = when (status) {
        TranscriptionStatus.PENDING -> stringResource(R.string.status_pending) to MaterialTheme.colorScheme.secondary
        TranscriptionStatus.IN_PROGRESS -> stringResource(R.string.status_processing) to MaterialTheme.colorScheme.tertiary
        TranscriptionStatus.COMPLETED -> stringResource(R.string.status_done) to MaterialTheme.colorScheme.primary
        TranscriptionStatus.FAILED -> stringResource(R.string.status_failed) to MaterialTheme.colorScheme.error
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = color.copy(alpha = 0.1f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit
) {
    TopAppBar(
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text(stringResource(R.string.search_recordings)) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                modifier = Modifier.fillMaxWidth()
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.close_search))
            }
        },
        actions = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.clear))
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterTabs(
    currentFilter: RecordingsFilter,
    onFilterSelected: (RecordingsFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = currentFilter == RecordingsFilter.ALL,
            onClick = { onFilterSelected(RecordingsFilter.ALL) },
            label = { Text(stringResource(R.string.filter_all)) },
            leadingIcon = if (currentFilter == RecordingsFilter.ALL) {
                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
            } else null
        )
        FilterChip(
            selected = currentFilter == RecordingsFilter.FAVORITES,
            onClick = { onFilterSelected(RecordingsFilter.FAVORITES) },
            label = { Text(stringResource(R.string.filter_favorites)) },
            leadingIcon = {
                Icon(
                    if (currentFilter == RecordingsFilter.FAVORITES) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
        )
    }
}

@Composable
private fun SwipeableRecordingItem(
    recording: Recording,
    transcriptionProgress: Int?,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRetryTranscription: () -> Unit = {}
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showActions by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = recording.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (recording.isFavorite) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = stringResource(R.string.favorite),
                                tint = Color(0xFFFFD700),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatDuration(recording.duration),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = formatDate(recording.createdAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TranscriptionStatusChip(recording.transcriptionStatus)

                    // Retry button for failed or stuck-in-progress transcriptions
                    val canRetry = recording.transcriptionStatus == TranscriptionStatus.FAILED ||
                        (recording.transcriptionStatus == TranscriptionStatus.IN_PROGRESS && transcriptionProgress == null)
                    if (canRetry) {
                        IconButton(onClick = onRetryTranscription) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Retry transcription",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // Favorite button
                    IconButton(onClick = onToggleFavorite) {
                        Icon(
                            if (recording.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = if (recording.isFavorite) stringResource(R.string.remove_from_favorites) else stringResource(R.string.add_to_favorites),
                            tint = if (recording.isFavorite) Color(0xFFFFD700) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Delete button
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.delete),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // Transcription progress bar
            if (recording.transcriptionStatus == TranscriptionStatus.IN_PROGRESS) {
                val progress = transcriptionProgress

                // Tick every 30s to keep ETA fresh
                var tickMs by remember { mutableStateOf(System.currentTimeMillis()) }
                val startMs = remember { System.currentTimeMillis() }
                LaunchedEffect(recording.id) {
                    while (true) {
                        kotlinx.coroutines.delay(30_000)
                        tickMs = System.currentTimeMillis()
                    }
                }

                // Whisper tiny ≈ 4× real-time on device; use as fallback estimate
                val durationMs = recording.duration.coerceAtLeast(1L)
                val estimatedTotalMs = durationMs * 4

                val statusText = if (progress != null && progress > 0) {
                    val elapsedMs = tickMs - startMs
                    val remainingMs = (elapsedMs.toFloat() / progress * (100 - progress)).toLong()
                    val remainingMin = (remainingMs / 60_000).coerceAtLeast(1)
                    "Transcribing… $progress% · ~$remainingMin min left"
                } else {
                    val estMin = (estimatedTotalMs / 60_000).coerceAtLeast(1)
                    "Transcribing… · Est. ~$estMin min total"
                }

                if (progress != null && progress > 0) {
                    LinearProgressIndicator(
                        progress = progress / 100f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                        color = MaterialTheme.colorScheme.tertiary
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, bottom = 12.dp)
                )
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_recording_title)) },
            text = { Text(stringResource(R.string.delete_recording_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteDialog = false
                    }
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun EmptyState(
    isFavoritesFilter: Boolean = false,
    hasSearchQuery: Boolean = false
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                when {
                    hasSearchQuery -> Icons.Default.SearchOff
                    isFavoritesFilter -> Icons.Default.StarBorder
                    else -> Icons.Default.Mic
                },
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Text(
                text = when {
                    hasSearchQuery -> stringResource(R.string.no_recordings_found)
                    isFavoritesFilter -> stringResource(R.string.no_favorites_yet)
                    else -> stringResource(R.string.no_recordings_yet)
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = when {
                    hasSearchQuery -> stringResource(R.string.try_different_search)
                    isFavoritesFilter -> stringResource(R.string.swipe_to_favorite)
                    else -> stringResource(R.string.tap_mic_to_start)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

private fun formatDuration(millis: Long): String {
    val seconds = (millis / 1000) % 60
    val minutes = (millis / (1000 * 60)) % 60
    val hours = millis / (1000 * 60 * 60)

    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM d", Locale.US)
    return sdf.format(Date(timestamp))
}
