package com.securevox.app.presentation.detail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.securevox.app.util.MemoryPolicy
import com.securevox.app.speakers.SpeakerLabels
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.securevox.app.R
import com.securevox.app.presentation.theme.*
import com.securevox.app.data.model.TranscriptSegment
import com.securevox.app.data.model.WordLayout
import com.securevox.app.data.model.WordTiming
import com.securevox.app.data.model.activeWordIndex
import com.securevox.app.data.model.timedWords
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import com.securevox.app.data.model.TranscriptionStatus
import com.securevox.app.service.ExportFormat
import com.securevox.app.service.PlaybackSpeed
import com.securevox.app.whisper.WhisperModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingDetailScreen(
    recordingId: String,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current

    // Create ViewModel with factory
    val viewModel = remember {
        RecordingDetailViewModelFactory(context.applicationContext as android.app.Application, recordingId)
            .create(RecordingDetailViewModel::class.java)
    }

    val recording by viewModel.recording.collectAsState()
    val segments by viewModel.segments.collectAsState()
    val speakerSkip by viewModel.speakerSkip.collectAsState()
    val waveform by viewModel.waveform.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val currentPosition by viewModel.currentPosition.collectAsState()
    val duration by viewModel.duration.collectAsState()
    val activeSegment by viewModel.activeSegment.collectAsState()
    val playbackSpeed by viewModel.playbackSpeed.collectAsState()
    val transcriptionProgress by viewModel.transcriptionProgress.collectAsState()
    val relabelState by viewModel.relabelState.collectAsState()
    val chosenCount by viewModel.chosenCount.collectAsState()
    val modelProgress by viewModel.modelProgress.collectAsState()
    val modelError by viewModel.modelError.collectAsState()
    var showSpeakerDialog by remember { mutableStateOf(false) }
    val relabelRunning = relabelState is RecordingDetailViewModel.RelabelState.Running
    LaunchedEffect(relabelRunning) { if (relabelRunning) showSpeakerDialog = false }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showExportMenu by remember { mutableStateOf(false) }
    var showRetranscribeDialog by remember { mutableStateOf(false) }
    var selectedRetranscribeModel by remember { mutableStateOf<WhisperModel?>(null) }
    var showReadAloud by remember { mutableStateOf(false) }
    val downloadedModels = remember { viewModel.getDownloadedModels() }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Auto-scroll to active segment
    LaunchedEffect(activeSegment) {
        activeSegment?.let { segment ->
            val index = segments.indexOf(segment)
            if (index >= 0) {
                listState.animateScrollToItem(index)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = recording?.title ?: stringResource(R.string.recording_label),
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.copy_transcript)) },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                            onClick = {
                                showMenu = false
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Transcript", viewModel.getFullTranscript())
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, context.getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
                            }
                        )
                        if (viewModel.getFullTranscript().isNotBlank()) {
                            DropdownMenuItem(
                                text = { Text("Read Aloud") },
                                leadingIcon = { Icon(Icons.Default.VolumeUp, null) },
                                onClick = {
                                    showMenu = false
                                    showReadAloud = true
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.export)) },
                            leadingIcon = { Icon(Icons.Default.Share, null) },
                            onClick = {
                                showMenu = false
                                showExportMenu = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.export_audio)) },
                            leadingIcon = { Icon(Icons.Default.AudioFile, null) },
                            onClick = {
                                showMenu = false
                                val filePath = recording?.audioFilePath ?: return@DropdownMenuItem
                                val file = File(filePath)
                                if (!file.exists()) {
                                    Toast.makeText(context, context.getString(R.string.audio_file_not_found), Toast.LENGTH_SHORT).show()
                                    return@DropdownMenuItem
                                }
                                try {
                                    val uri: Uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.provider",
                                        file
                                    )
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "audio/wav"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.export_audio)))
                                } catch (e: Exception) {
                                    Toast.makeText(context, context.getString(R.string.export_failed), Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                        if (SpeakerLabels.AVAILABLE && segments.isNotEmpty() && !relabelRunning &&
                            recording?.transcriptionStatus != TranscriptionStatus.IN_PROGRESS) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.relabel_menu)) },
                                leadingIcon = { Icon(Icons.Default.People, null) },
                                onClick = {
                                    showMenu = false
                                    viewModel.clearModelError()
                                    showSpeakerDialog = true
                                }
                            )
                        }
                        if (downloadedModels.size >= 1 &&
                            recording?.transcriptionStatus != TranscriptionStatus.IN_PROGRESS) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.retranscribe)) },
                                leadingIcon = { Icon(Icons.Default.Refresh, null) },
                                onClick = {
                                    showMenu = false
                                    selectedRetranscribeModel = downloadedModels.first()
                                    showRetranscribeDialog = true
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete)) },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = {
                                showMenu = false
                                showDeleteDialog = true
                            }
                        )
                    }

                    // Export format submenu
                    DropdownMenu(
                        expanded = showExportMenu,
                        onDismissRequest = { showExportMenu = false }
                    ) {
                        ExportFormat.entries.forEach { format ->
                            DropdownMenuItem(
                                text = { Text(format.displayName) },
                                onClick = {
                                    showExportMenu = false
                                    val intent = viewModel.exportTranscript(format)
                                    if (intent != null) {
                                        context.startActivity(Intent.createChooser(intent, context.getString(R.string.export_transcript)))
                                    } else {
                                        Toast.makeText(context, context.getString(R.string.export_failed), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Transcript
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                val isInProgress = recording?.transcriptionStatus == TranscriptionStatus.IN_PROGRESS
                when {
                    recording?.transcriptionStatus == TranscriptionStatus.FAILED -> {
                        TranscriptionFailed()
                    }
                    segments.isEmpty() && isInProgress -> {
                        TranscriptionProgress(progress = transcriptionProgress)
                    }
                    segments.isEmpty() && recording?.transcriptionStatus == TranscriptionStatus.COMPLETED -> {
                        NoSpeechDetected()
                    }
                    segments.isEmpty() -> {
                        TranscriptionPending()
                    }
                    else -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Progress banner while still transcribing (segments arriving live)
                            if (isInProgress) {
                                TranscriptionProgressBanner(progress = transcriptionProgress)
                            }
                            if (relabelRunning) RelabelBanner()
                            (relabelState as? RecordingDetailViewModel.RelabelState.Failed)?.let { failed ->
                                Text(
                                    text = stringResource(R.string.relabel_failed, failed.message),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)
                                )
                            }
                            val detected = remember(segments) { segments.map { it.speaker }.filter { it >= 0 }.toSet().size }
                            if (SpeakerLabels.AVAILABLE && detected > 0) {
                                SpeakerCountHeader(
                                    detected = detected,
                                    chosen = chosenCount,
                                    enabled = !relabelRunning && !isInProgress,
                                    onChange = {
                                        viewModel.clearModelError()
                                        showSpeakerDialog = true
                                    }
                                )
                            }
                            speakerSkip?.let { skip ->
                                Text(
                                    text = stringResource(
                                        if (skip == MemoryPolicy.SpeakerSkip.TOO_LONG) R.string.speaker_skipped_long
                                        else R.string.speaker_skipped_memory
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)
                                )
                            }
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                itemsIndexed(segments, key = { _, it -> it.id }) { index, segment ->
                                    val newSpeaker = segment.speaker >= 0 &&
                                        (index == 0 || segments[index - 1].speaker != segment.speaker)
                                    SegmentRow(
                                        segment = segment,
                                        showSpeaker = newSpeaker,
                                        isActive = segment.id == activeSegment?.id,
                                        // Only the playing row needs the clock; passing it to
                                        // every row would recompose the whole list every 100 ms.
                                        currentTime = if (segment.id == activeSegment?.id) currentPosition else -1L,
                                        onClick = { viewModel.seekToSegment(segment) },
                                        onWordClick = { viewModel.seekToWord(it) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Playback controls
            PlaybackControls(
                waveform = waveform,
                isPlaying = isPlaying,
                currentPosition = currentPosition,
                duration = duration,
                playbackSpeed = playbackSpeed,
                onPlayPause = { viewModel.togglePlayPause() },
                onSeek = { viewModel.seekTo(it) },
                onSkipBack = { viewModel.skipBackward() },
                onSkipForward = { viewModel.skipForward() },
                onSpeedChange = { viewModel.setPlaybackSpeed(it) }
            )
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
                        viewModel.deleteRecording()
                        showDeleteDialog = false
                        onNavigateBack()
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

    if (showRetranscribeDialog) {
        AlertDialog(
            onDismissRequest = { showRetranscribeDialog = false },
            title = { Text(stringResource(R.string.retranscribe)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.retranscribe_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    downloadedModels.forEach { model ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedRetranscribeModel = model }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            RadioButton(
                                selected = selectedRetranscribeModel == model,
                                onClick = { selectedRetranscribeModel = model }
                            )
                            Column {
                                Text(
                                    text = model.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "${model.accuracy} accuracy · ${model.speed}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        selectedRetranscribeModel?.let { viewModel.retryTranscription(it) }
                        showRetranscribeDialog = false
                    },
                    enabled = selectedRetranscribeModel != null
                ) {
                    Text(stringResource(R.string.retranscribe_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRetranscribeDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showSpeakerDialog) {
        SpeakerRelabelDialog(
            initial = chosenCount,
            tooLong = (recording?.duration ?: 0L) > RELABEL_MAX_DURATION_MS,
            modelsReady = viewModel.speakerModelsReady(),
            downloadProgress = modelProgress,
            downloadError = modelError,
            onConfirm = { count ->
                if (viewModel.startRelabel(count)) showSpeakerDialog = false
            },
            onDownloadAndConfirm = { count -> viewModel.downloadModelsAndRelabel(count) },
            onDismiss = { showSpeakerDialog = false }
        )
    }

    if (showReadAloud) {
        com.securevox.app.tts.ReadAloudSheet(
            text = viewModel.getFullTranscript(),
            onDismiss = { showReadAloud = false },
        )
    }
}

@Composable
private fun SegmentRow(
    segment: TranscriptSegment,
    showSpeaker: Boolean,
    isActive: Boolean,
    currentTime: Long,
    onClick: () -> Unit,
    onWordClick: (WordTiming) -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    // Timed words, if this segment has trustworthy ones. Decoded once per segment, and only for
    // rows the lazy list actually composes.
    val layout = remember(segment.id, segment.words, segment.text) {
        segment.timedWords().takeIf { it.isNotEmpty() }?.let { WordLayout(it) }
    }
    val baseColor = if (isActive) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
    val activeWord = if (layout != null && isActive) {
        activeWordIndex(layout.words, currentTime, segment.startTimeMs, segment.endTimeMs)
    } else -1
    val highlight = accent.copy(alpha = 0.22f)
    // One AnnotatedString per segment: a background span on the playing word, no per-word composables.
    val annotated = remember(layout, activeWord, highlight) {
        buildAnnotatedString {
            append(layout?.text ?: segment.text)
            if (layout != null && activeWord >= 0) {
                addStyle(
                    SpanStyle(background = highlight),
                    layout.starts[activeWord],
                    layout.endOf(activeWord)
                )
            }
        }
    }
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val wordTapModifier = if (layout == null) Modifier else Modifier.pointerInput(layout) {
        detectTapGestures { tap ->
            val result = textLayout ?: return@detectTapGestures
            val index = layout.wordAt(result.getOffsetForPosition(tap))
            if (index >= 0) onWordClick(layout.words[index])
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .drawBehind {
                // The line being spoken gets a margin rule, like a pencilled mark in a notebook.
                if (isActive) {
                    drawRect(
                        color = accent,
                        topLeft = Offset.Zero,
                        size = Size(3.dp.toPx(), size.height)
                    )
                }
            }
            .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = formatTime(segment.startTimeMs),
            style = MaterialTheme.typography.labelMedium,
            color = if (isActive) accent else MaterialTheme.colorScheme.outline,
            modifier = Modifier.width(44.dp).padding(top = 5.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            if (showSpeaker) {
                Text(
                    text = stringResource(R.string.speaker_n, segment.speaker + 1),
                    style = MaterialTheme.typography.labelLarge,
                    color = SpeakerColors[segment.speaker % SpeakerColors.size],
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
            Text(
                text = annotated,
                style = TranscriptStyle,
                color = baseColor,
                onTextLayout = { textLayout = it },
                modifier = wordTapModifier
            )
        }
    }
}

@Composable
private fun PlaybackControls(
    waveform: FloatArray,
    isPlaying: Boolean,
    currentPosition: Long,
    duration: Long,
    playbackSpeed: PlaybackSpeed,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onSpeedChange: (PlaybackSpeed) -> Unit
) {
    var showSpeedMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            WaveformScrubber(
                waveform = waveform,
                progress = if (duration > 0) currentPosition.toFloat() / duration else 0f,
                onSeekFraction = { onSeek((it * duration).toLong()) },
                modifier = Modifier.fillMaxWidth().height(56.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))

            // Time labels
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatTime(currentPosition),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatTime(duration),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Control buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Speed button
                Box {
                    Surface(
                        onClick = { showSpeedMenu = true },
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.width(64.dp)
                    ) {
                        Text(
                            text = playbackSpeed.displayName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp).fillMaxWidth()
                        )
                    }
                    DropdownMenu(
                        expanded = showSpeedMenu,
                        onDismissRequest = { showSpeedMenu = false }
                    ) {
                        PlaybackSpeed.entries.forEach { speed ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = speed.displayName,
                                        fontWeight = if (speed == playbackSpeed) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                onClick = {
                                    onSpeedChange(speed)
                                    showSpeedMenu = false
                                },
                                trailingIcon = {
                                    if (speed == playbackSpeed) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = stringResource(R.string.selected),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(onClick = onSkipBack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Replay10,
                        contentDescription = stringResource(R.string.skip_back_10),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(26.dp)
                    )
                }

                // Lavender: playback, not recording — coral is reserved for that.
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = onPlayPause),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) stringResource(R.string.pause) else stringResource(R.string.play),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(onClick = onSkipForward),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Forward10,
                        contentDescription = stringResource(R.string.skip_forward_10),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(26.dp)
                    )
                }

                // Spacer for balance
                Spacer(modifier = Modifier.width(64.dp))
            }
        }
    }
}

@Composable
private fun TranscriptionProgress(progress: Int?) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            if (progress != null && progress > 0) {
                CircularProgressIndicator(progress = progress / 100f, color = MaterialTheme.colorScheme.primary)
            } else {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            Text(
                text = if (progress != null && progress > 0) "Transcribing… $progress%" else stringResource(R.string.transcribing),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (progress != null && progress > 0) {
                LinearProgressIndicator(
                    progress = progress / 100f,
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outline
                )
            }
            Text(
                text = stringResource(R.string.transcription_may_take_minutes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TranscriptionProgressBanner(progress: Int?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (progress != null && progress > 0) "Transcribing… $progress%" else "Transcribing…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (progress != null && progress > 0) {
                    Text(
                        text = "${100 - progress}% remaining",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (progress != null && progress > 0) {
                LinearProgressIndicator(
                    progress = progress / 100f,
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outline
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun TranscriptionPending() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                Icons.Default.TextSnippet,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            Text(
                text = stringResource(R.string.transcription_pending),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun NoSpeechDetected() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = stringResource(R.string.no_speech_detected),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.no_speech_detected_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TranscriptionFailed() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.secondary
            )
            Text(
                text = stringResource(R.string.transcription_failed),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.please_try_again),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * The recording drawn as bars; the part already played is filled in verdigris. Tap or drag
 * to seek. Falls back to a flat rule while the waveform is still being read.
 */
@Composable
private fun WaveformScrubber(
    waveform: FloatArray,
    progress: Float,
    onSeekFraction: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val played = MaterialTheme.colorScheme.primary
    val unplayed = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    val description = stringResource(R.string.waveform_scrubber)
    Canvas(
        modifier = modifier
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                detectTapGestures { offset -> onSeekFraction((offset.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    onSeekFraction((change.position.x / size.width).coerceIn(0f, 1f))
                }
            }
    ) {
        val bars = if (waveform.isEmpty()) 1 else waveform.size
        val slot = size.width / bars
        val barWidth = (slot * 0.6f).coerceAtLeast(1.5f)
        val mid = size.height / 2f
        val splitX = size.width * progress.coerceIn(0f, 1f)
        for (i in 0 until bars) {
            val level = if (waveform.isEmpty()) 0.04f else waveform[i].coerceAtLeast(0.04f)
            val half = (size.height * 0.5f * level).coerceAtLeast(1.5f)
            val x = i * slot + slot / 2f
            drawLine(
                color = if (x <= splitX) played else unplayed,
                start = Offset(x, mid - half),
                end = Offset(x, mid + half),
                strokeWidth = barWidth,
                cap = StrokeCap.Round
            )
        }
    }
}

private fun formatTime(millis: Long): String {
    val seconds = (millis / 1000) % 60
    val minutes = (millis / (1000 * 60)) % 60
    val hours = millis / (1000 * 60 * 60)

    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}
