package com.securevox.app.presentation.recordings

import com.securevox.app.presentation.theme.FavoriteAmber
import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.securevox.app.whisper.DownloadState
import com.securevox.app.whisper.LiveCaption
import com.securevox.app.whisper.WhisperModel
import com.securevox.app.IncomingAction
import com.securevox.app.IncomingActions
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
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
import com.securevox.app.presentation.theme.*
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
    val liveCaption by viewModel.liveCaption.collectAsState()
    val showModelUpgrade by viewModel.showModelUpgrade.collectAsState()
    val modelDownload by viewModel.downloadState.collectAsState()
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

    // Files shared into the app and launches from the tile or shortcut. The screen is only
    // composed once the app lock and first-run setup are out of the way, so this is the
    // right place to act on them.
    val incoming by IncomingActions.pending.collectAsState()
    LaunchedEffect(incoming) {
        val action = incoming ?: return@LaunchedEffect
        when (action) {
            is IncomingAction.ImportMedia -> viewModel.importMedia(action.uri)
            IncomingAction.StartRecording -> {
                val hasMic = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (hasMic && !isRecording) viewModel.startRecording()
            }
        }
        IncomingActions.consume(action)
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
                    title = {
                        Column {
                            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                            // The product's promise, stated where it is always visible.
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                                Text(
                                    text = stringResource(R.string.on_device_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    actions = {
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
            if (!showSearch) {
                SearchPill(onClick = { showSearch = true })
            }

            // Recording indicator
            if (isRecording) {
                RecordingIndicator(
                    duration = recordingDuration,
                    audioLevel = audioLevel,
                    isPaused = isPaused,
                    caption = liveCaption,
                    onPause = { viewModel.pauseRecording() },
                    onResume = { viewModel.resumeRecording() },
                    onStop = {
                        viewModel.stopRecording()
                        (context as? Activity)?.let { RatingKit.trackAction(it) }
                    }
                )
            }

            if (showModelUpgrade && !isRecording) {
                ModelUpgradeCard(
                    downloading = modelDownload as? DownloadState.Downloading,
                    onUpgrade = { viewModel.upgradeToBase() },
                    onDismiss = { viewModel.dismissModelUpgrade() }
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
                    verticalArrangement = Arrangement.spacedBy(0.dp)
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
private fun SearchPill(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.search_recordings),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun ModelUpgradeCard(
    downloading: DownloadState.Downloading?,
    onUpgrade: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.upgrade_model_title),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.upgrade_model_body, WhisperModel.BASE.sizeMB),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        if (downloading != null) {
            LinearProgressIndicator(
                progress = downloading.progress,
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onUpgrade) { Text(stringResource(R.string.upgrade_model_action)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.upgrade_model_dismiss)) }
            }
        }
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
    // Coral, not lavender: coral is reserved for "recording is live" throughout
    // this screen, lavender for playback/resume. Matches Recorder's own record
    // button, which is never the same color as its Play/Resume controls.
    LargeFloatingActionButton(
        onClick = onClick,
        shape = CircleShape,
        containerColor = MaterialTheme.colorScheme.secondary,
        contentColor = MaterialTheme.colorScheme.onSecondary
    ) {
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = stringResource(R.string.start_recording),
            modifier = Modifier.size(32.dp)
        )
    }
}

/** The last few lines of live text: settled words in full colour, the phrase still forming dimmer. */
@Composable
private fun LiveCaptionText(caption: LiveCaption) {
    val settled = MaterialTheme.colorScheme.onSurface
    val forming = MaterialTheme.colorScheme.onSurfaceVariant
    // Clip from the front, at a word boundary, so the newest words are always the ones shown.
    val full = listOf(caption.committed, caption.partial).filter { it.isNotEmpty() }.joinToString(" ")
    val dropped = (full.length - 150).coerceAtLeast(0)
    val start = if (dropped == 0) 0 else full.indexOf(' ', dropped).let { if (it < 0) dropped else it + 1 }
    val committedEnd = caption.committed.length
    Text(
        text = buildAnnotatedString {
            val visible = full.substring(start)
            val settledLen = (committedEnd - start).coerceIn(0, visible.length)
            withStyle(SpanStyle(color = settled)) { append(visible.substring(0, settledLen)) }
            withStyle(SpanStyle(color = forming)) { append(visible.substring(settledLen)) }
        },
        style = TranscriptStyle.copy(fontSize = 17.sp, lineHeight = 26.sp),
        maxLines = 4,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun RecordingIndicator(
    duration: Long,
    audioLevel: Float,
    isPaused: Boolean,
    caption: LiveCaption,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
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
                            .background(MaterialTheme.colorScheme.secondary.copy(alpha = alpha))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    )
                }
                Text(
                    text = if (isPaused) "Paused" else stringResource(R.string.recording_label),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = formatDuration(duration),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Real live amplitude, not decorative: this is the same audioLevel
            // stream driving the record button's pulse, rendered the way
            // Recorder renders its own live waveform during active recording.
            LiveWaveform(audioLevel = audioLevel, isPaused = isPaused)

            if (!caption.isEmpty) {
                Spacer(modifier = Modifier.height(16.dp))
                LiveCaptionText(caption)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    onClick = if (isPaused) onResume else onPause,
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 14.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (isPaused) "Resume" else "Pause",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
                Surface(
                    onClick = onStop,
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 14.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.stop_recording),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }
    }
}

/**
 * Live amplitude bars, one new bar appended per composition tick while
 * recording. Heights come directly from [audioLevel] — the same value already
 * driving the record button — not a decorative placeholder.
 */
@Composable
private fun LiveWaveform(audioLevel: Float, isPaused: Boolean) {
    val barCount = 40
    val levels = remember { mutableStateListOf<Float>().apply { repeat(barCount) { add(0f) } } }

    LaunchedEffect(audioLevel, isPaused) {
        if (!isPaused) {
            levels.removeAt(0)
            levels.add(audioLevel.coerceIn(0f, 1f))
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        levels.forEach { level ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 3.dp)
                    .fillMaxHeight(fraction = (0.08f + level * 0.92f).coerceIn(0.08f, 1f))
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (isPaused) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.secondary)
            )
        }
    }
}

@Composable
private fun TranscriptionStatusChip(status: TranscriptionStatus) {
    // PENDING is neutral, not coral — coral means "live/destructive" everywhere
    // else in this app, and a queued transcription is neither.
    val (text, color) = when (status) {
        TranscriptionStatus.PENDING -> stringResource(R.string.status_pending) to MaterialTheme.colorScheme.onSurfaceVariant
        TranscriptionStatus.IN_PROGRESS -> stringResource(R.string.status_processing) to MaterialTheme.colorScheme.primary
        TranscriptionStatus.COMPLETED -> stringResource(R.string.status_done) to MaterialTheme.colorScheme.primary
        TranscriptionStatus.FAILED -> stringResource(R.string.status_failed) to MaterialTheme.colorScheme.error
    }

    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.16f)
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
        // Selection uses lavender explicitly rather than the default FilterChip
        // color (which reads colorScheme.secondary — coral here) — coral is
        // reserved for record/live/destructive, and "this filter is active" is
        // neither.
        val selectedChipColors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
            selectedLabelColor = MaterialTheme.colorScheme.primary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.primary
        )
        FilterChip(
            selected = currentFilter == RecordingsFilter.ALL,
            onClick = { onFilterSelected(RecordingsFilter.ALL) },
            label = { Text(stringResource(R.string.filter_all)) },
            shape = RoundedCornerShape(50),
            colors = selectedChipColors,
            leadingIcon = if (currentFilter == RecordingsFilter.ALL) {
                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
            } else null
        )
        FilterChip(
            selected = currentFilter == RecordingsFilter.FAVORITES,
            onClick = { onFilterSelected(RecordingsFilter.FAVORITES) },
            label = { Text(stringResource(R.string.filter_favorites)) },
            shape = RoundedCornerShape(50),
            colors = selectedChipColors,
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

    val rule = MaterialTheme.colorScheme.outlineVariant
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .drawBehind {
                drawLine(
                    color = rule,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx()
                )
            },
        shape = RectangleShape,
        color = Color.Transparent
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
                                tint = FavoriteAmber,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatDuration(recording.duration),
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
                            tint = if (recording.isFavorite) FavoriteAmber else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Delete button
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.delete),
                            tint = MaterialTheme.colorScheme.outline
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
