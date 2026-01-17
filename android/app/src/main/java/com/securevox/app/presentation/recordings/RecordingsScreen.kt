package com.securevox.app.presentation.recordings

import android.net.Uri
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
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
    val audioLevel by viewModel.audioLevel.collectAsState()
    val recordingDuration by viewModel.recordingDuration.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val isImporting by viewModel.isImporting.collectAsState()
    val importError by viewModel.importError.collectAsState()

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
                    title = { Text("SecureVox") },
                    actions = {
                        IconButton(onClick = { showSearch = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                        IconButton(
                            onClick = {
                                filePickerLauncher.launch(MediaImportService.getSupportedMimeTypes())
                            },
                            enabled = !isImporting
                        ) {
                            Icon(Icons.Default.FileOpen, contentDescription = "Import media")
                        }
                        IconButton(onClick = onSettingsClick) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            RecordButton(
                isRecording = isRecording,
                audioLevel = audioLevel,
                onClick = {
                    if (isRecording) {
                        viewModel.stopRecording()
                    } else {
                        viewModel.startRecording()
                    }
                }
            )
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
                    audioLevel = audioLevel
                )
            }

            // Importing indicator
            if (isImporting) {
                ImportingIndicator()
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
                            onClick = { onRecordingClick(recording.id) },
                            onDelete = { viewModel.deleteRecording(recording) },
                            onToggleFavorite = { viewModel.toggleFavorite(recording) }
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
            title = { Text("Import Failed") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearImportError() }) {
                    Text("OK")
                }
            }
        )
    }
}

@Composable
private fun ImportingIndicator() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp
            )
            Text(
                text = "Importing media...",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun RecordButton(
    isRecording: Boolean,
    audioLevel: Float,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isRecording) 1f + (audioLevel * 0.2f) else 1f,
        animationSpec = spring(dampingRatio = 0.5f),
        label = "scale"
    )

    val pulseAnimation = rememberInfiniteTransition(label = "pulse")
    val pulse by pulseAnimation.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    LargeFloatingActionButton(
        onClick = onClick,
        modifier = Modifier.scale(if (isRecording) scale * pulse else 1f),
        shape = CircleShape,
        containerColor = if (isRecording) Color.Red else MaterialTheme.colorScheme.primary,
        contentColor = Color.White
    ) {
        Icon(
            imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
            contentDescription = if (isRecording) "Stop recording" else "Start recording",
            modifier = Modifier.size(32.dp)
        )
    }
}

@Composable
private fun RecordingIndicator(
    duration: Long,
    audioLevel: Float
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Pulsing red dot
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
                Text(
                    text = "Recording",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }

            Text(
                text = formatDuration(duration),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }

        // Audio level bar
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
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun TranscriptionStatusChip(status: TranscriptionStatus) {
    val (text, color) = when (status) {
        TranscriptionStatus.PENDING -> "Pending" to MaterialTheme.colorScheme.secondary
        TranscriptionStatus.IN_PROGRESS -> "Processing" to MaterialTheme.colorScheme.tertiary
        TranscriptionStatus.COMPLETED -> "Done" to MaterialTheme.colorScheme.primary
        TranscriptionStatus.FAILED -> "Failed" to MaterialTheme.colorScheme.error
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
                placeholder = { Text("Search recordings") },
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
                Icon(Icons.Default.ArrowBack, contentDescription = "Close search")
            }
        },
        actions = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear")
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
            label = { Text("All") },
            leadingIcon = if (currentFilter == RecordingsFilter.ALL) {
                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
            } else null
        )
        FilterChip(
            selected = currentFilter == RecordingsFilter.FAVORITES,
            onClick = { onFilterSelected(RecordingsFilter.FAVORITES) },
            label = { Text("Favorites") },
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
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showActions by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
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
                            contentDescription = "Favorite",
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

                // Favorite button
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (recording.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = if (recording.isFavorite) "Remove from favorites" else "Add to favorites",
                        tint = if (recording.isFavorite) Color(0xFFFFD700) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Delete button
                IconButton(onClick = { showDeleteDialog = true }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Recording") },
            text = { Text("Are you sure you want to delete this recording? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteDialog = false
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
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
                    hasSearchQuery -> "No recordings found"
                    isFavoritesFilter -> "No favorites yet"
                    else -> "No recordings yet"
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = when {
                    hasSearchQuery -> "Try a different search term"
                    isFavoritesFilter -> "Swipe right on a recording to favorite it"
                    else -> "Tap the microphone button to start"
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
