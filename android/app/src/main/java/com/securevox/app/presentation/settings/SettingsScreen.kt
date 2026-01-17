package com.securevox.app.presentation.settings

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.securevox.app.whisper.DownloadState
import com.securevox.app.whisper.ModelInfo
import com.securevox.app.whisper.WhisperLanguage
import com.securevox.app.whisper.WhisperModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToFAQ: () -> Unit = {},
    onNavigateToCustomDictionary: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val availableModels by viewModel.availableModels.collectAsState()
    val downloadState by viewModel.downloadState.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val selectedLanguage by viewModel.selectedLanguage.collectAsState()
    val storageUsed by viewModel.storageUsed.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()

    // Recording settings
    val soundEffectsEnabled by viewModel.soundEffectsEnabled.collectAsState()
    val hapticFeedbackEnabled by viewModel.hapticFeedbackEnabled.collectAsState()

    // Transcription settings
    val autoPunctuationEnabled by viewModel.autoPunctuationEnabled.collectAsState()
    val smartCapitalizationEnabled by viewModel.smartCapitalizationEnabled.collectAsState()

    // Data management settings
    val recycleBinRetention by viewModel.recycleBinRetention.collectAsState()
    val autoDeleteAudio by viewModel.autoDeleteAudio.collectAsState()
    val autoCopyToClipboard by viewModel.autoCopyToClipboard.collectAsState()

    var showLanguageDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showRecycleBinDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Transcription Section
            item {
                Text(
                    text = "Transcription Model",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            items(availableModels) { modelInfo ->
                ModelCard(
                    modelInfo = modelInfo,
                    isSelected = modelInfo.model == selectedModel,
                    downloadState = downloadState,
                    onSelect = { viewModel.selectModel(modelInfo.model) },
                    onDownload = { viewModel.downloadModel(modelInfo.model) },
                    onDelete = { viewModel.deleteModel(modelInfo.model) },
                    onCancelDownload = { viewModel.cancelDownload() }
                )
            }

            // Language Section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Language",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            item {
                SettingsCard(
                    title = "Transcription Language",
                    subtitle = selectedLanguage.displayName,
                    icon = Icons.Default.Language,
                    onClick = { showLanguageDialog = true }
                )
            }

            // Transcription Section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Transcription",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            item {
                SettingsToggleCard(
                    title = "Auto-Punctuation",
                    subtitle = "Automatically add punctuation to transcripts",
                    icon = Icons.Default.FormatQuote,
                    checked = autoPunctuationEnabled,
                    onCheckedChange = { viewModel.setAutoPunctuationEnabled(it) }
                )
            }

            item {
                SettingsToggleCard(
                    title = "Smart Capitalization",
                    subtitle = "Capitalize sentences and proper nouns",
                    icon = Icons.Default.TextFormat,
                    checked = smartCapitalizationEnabled,
                    onCheckedChange = { viewModel.setSmartCapitalizationEnabled(it) }
                )
            }

            item {
                SettingsCard(
                    title = "Custom Dictionary",
                    subtitle = "Add words to improve transcription accuracy",
                    icon = Icons.Default.Book,
                    onClick = onNavigateToCustomDictionary
                )
            }

            // Recording Section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Recording",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            item {
                SettingsToggleCard(
                    title = "Sound Effects",
                    subtitle = "Play sounds when starting/stopping recording",
                    icon = Icons.Default.VolumeUp,
                    checked = soundEffectsEnabled,
                    onCheckedChange = { viewModel.setSoundEffectsEnabled(it) }
                )
            }

            item {
                SettingsToggleCard(
                    title = "Haptic Feedback",
                    subtitle = "Vibrate when starting/stopping recording",
                    icon = Icons.Default.Vibration,
                    checked = hapticFeedbackEnabled,
                    onCheckedChange = { viewModel.setHapticFeedbackEnabled(it) }
                )
            }

            // Data Management Section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Data Management",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            item {
                SettingsCard(
                    title = "Recycle Bin",
                    subtitle = if (recycleBinRetention == RecycleBinRetention.DISABLED) {
                        "Recordings deleted immediately"
                    } else {
                        "Keep deleted recordings for ${recycleBinRetention.displayName}"
                    },
                    icon = Icons.Default.Delete,
                    onClick = { showRecycleBinDialog = true }
                )
            }

            item {
                SettingsToggleCard(
                    title = "Auto-Delete Audio",
                    subtitle = "Delete audio files after transcription completes",
                    icon = Icons.Default.DeleteSweep,
                    checked = autoDeleteAudio,
                    onCheckedChange = { viewModel.setAutoDeleteAudio(it) }
                )
            }

            item {
                SettingsToggleCard(
                    title = "Auto-Copy to Clipboard",
                    subtitle = "Copy transcript to clipboard after transcription",
                    icon = Icons.Default.ContentCopy,
                    checked = autoCopyToClipboard,
                    onCheckedChange = { viewModel.setAutoCopyToClipboard(it) }
                )
            }

            // Appearance Section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Appearance",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            item {
                SettingsCard(
                    title = "Theme",
                    subtitle = themeMode.displayName,
                    icon = Icons.Default.Palette,
                    onClick = { showThemeDialog = true }
                )
            }

            // Storage Section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Storage",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Storage,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column {
                                Text(
                                    text = "Models Storage",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = Formatter.formatFileSize(context, storageUsed),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // About Section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "About",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            item {
                SettingsCard(
                    title = "FAQ & Help",
                    subtitle = "Common questions and troubleshooting",
                    icon = Icons.Default.HelpOutline,
                    onClick = onNavigateToFAQ
                )
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "SecureVox",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Version 1.0.0",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Privacy-first voice transcription powered by Whisper AI. All processing happens on your device - your audio never leaves your phone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Bottom spacing
            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // Language selection dialog
    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text("Select Language") },
            text = {
                LazyColumn {
                    items(WhisperLanguage.entries.toList()) { language ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = language == selectedLanguage,
                                onClick = {
                                    viewModel.selectLanguage(language)
                                    showLanguageDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = language.displayName,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Theme selection dialog
    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("Select Theme") },
            text = {
                Column {
                    ThemeMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = mode == themeMode,
                                onClick = {
                                    viewModel.setThemeMode(mode)
                                    showThemeDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = mode.displayName,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = when (mode) {
                                        ThemeMode.SYSTEM -> "Follow system settings"
                                        ThemeMode.LIGHT -> "Always use light theme"
                                        ThemeMode.DARK -> "Always use dark theme"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Recycle bin retention dialog
    if (showRecycleBinDialog) {
        AlertDialog(
            onDismissRequest = { showRecycleBinDialog = false },
            title = { Text("Recycle Bin Retention") },
            text = {
                Column {
                    RecycleBinRetention.entries.forEach { retention ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = retention == recycleBinRetention,
                                onClick = {
                                    viewModel.setRecycleBinRetention(retention)
                                    showRecycleBinDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = retention.displayName,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = when (retention) {
                                        RecycleBinRetention.DISABLED -> "Recordings are permanently deleted immediately"
                                        RecycleBinRetention.DAYS_7 -> "Deleted recordings can be restored within 7 days"
                                        RecycleBinRetention.DAYS_14 -> "Deleted recordings can be restored within 14 days"
                                        RecycleBinRetention.DAYS_30 -> "Deleted recordings can be restored within 30 days"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRecycleBinDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelCard(
    modelInfo: ModelInfo,
    isSelected: Boolean,
    downloadState: DownloadState,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onCancelDownload: () -> Unit
) {
    val context = LocalContext.current
    val model = modelInfo.model
    val isDownloading = downloadState is DownloadState.Downloading && downloadState.model == model

    Card(
        onClick = { if (modelInfo.isDownloaded) onSelect() },
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = if (isSelected) {
            CardDefaults.outlinedCardBorder()
        } else null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = model.displayName,
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (isSelected && modelInfo.isDownloaded) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                Text(
                                    text = "Active",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = model.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        ModelStat(label = "Size", value = "${model.sizeMB}MB")
                        ModelStat(label = "Accuracy", value = model.accuracy)
                        ModelStat(label = "Speed", value = model.speed)
                    }
                }

                // Action button
                when {
                    isDownloading -> {
                        IconButton(onClick = onCancelDownload) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Cancel download",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    modelInfo.isDownloaded -> {
                        if (model != WhisperModel.TINY) {
                            IconButton(onClick = onDelete) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete model",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        } else {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "Downloaded",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    else -> {
                        FilledTonalButton(onClick = onDownload) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Download")
                        }
                    }
                }
            }

            // Download progress
            AnimatedVisibility(visible = isDownloading) {
                if (downloadState is DownloadState.Downloading) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    ) {
                        LinearProgressIndicator(
                            progress = downloadState.progress,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${downloadState.progressPercent}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${Formatter.formatFileSize(context, downloadState.downloadedBytes)} / ${Formatter.formatFileSize(context, downloadState.totalBytes)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Error message
            if (downloadState is DownloadState.Error && downloadState.model == model) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Download failed: ${downloadState.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun ModelStat(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsToggleCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}
