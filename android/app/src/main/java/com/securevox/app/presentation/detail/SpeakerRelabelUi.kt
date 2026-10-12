package com.securevox.app.presentation.detail

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.securevox.app.R
import com.securevox.app.speakers.SpeakerCount

/** Longest recording the speaker pass accepts (matches SpeakerLabels.MAX_SAMPLES, 30 minutes). */
const val RELABEL_MAX_DURATION_MS = 30L * 60 * 1000

/** Compact line above the transcript: how many speakers, and who decided. */
@Composable
fun SpeakerCountHeader(detected: Int, chosen: Int?, enabled: Boolean, onChange: () -> Unit) {
    val changeLabel = stringResource(R.string.speakers_change_description)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = if (chosen != null) stringResource(R.string.speakers_header_fixed, chosen)
            else stringResource(R.string.speakers_header_auto, detected),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(
            onClick = onChange,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = changeLabel }
        ) {
            Text(stringResource(R.string.speakers_change))
        }
    }
}

/** Unobtrusive banner while the relabel runs, same look as the transcription banner. */
@Composable
fun RelabelBanner() {
    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.relabel_running),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outline
            )
        }
    }
}

/**
 * Pick how many people are talking and re-run the speaker pass. [initial] is the stored choice
 * (null = automatic). [modelsReady] false makes the confirm button a download button (except for
 * a count of 1, which needs no models). [downloadProgress] is 0..1 while downloading.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SpeakerRelabelDialog(
    initial: Int?,
    tooLong: Boolean,
    modelsReady: Boolean,
    downloadProgress: Float?,
    downloadError: String?,
    onConfirm: (Int?) -> Unit,
    onDownloadAndConfirm: (Int?) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(SpeakerCount.normalize(initial)) }
    var showMore by remember { mutableStateOf((initial ?: 0) > SpeakerCount.QUICK_MAX) }
    val downloading = downloadProgress != null
    val needsModels = selected != 1 && !modelsReady
    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        title = { Text(stringResource(R.string.relabel_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.relabel_explain),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (tooLong) {
                    Text(
                        text = stringResource(R.string.relabel_too_long),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilterChip(
                        selected = selected == null,
                        onClick = { selected = null },
                        enabled = !downloading,
                        label = { Text(stringResource(R.string.relabel_automatic)) }
                    )
                    val last = if (showMore) SpeakerCount.MAX else SpeakerCount.QUICK_MAX
                    for (n in 1..last) {
                        val description = if (n == 1) stringResource(R.string.relabel_chip_one_description)
                        else stringResource(R.string.relabel_chip_description, n)
                        FilterChip(
                            selected = selected == n,
                            onClick = { selected = n },
                            enabled = !downloading,
                            label = { Text(n.toString()) },
                            modifier = Modifier.semantics { contentDescription = description }
                        )
                    }
                    if (!showMore) {
                        FilterChip(
                            selected = false,
                            onClick = { showMore = true },
                            enabled = !downloading,
                            label = { Text(stringResource(R.string.relabel_more)) }
                        )
                    }
                }
                if (needsModels && !tooLong) {
                    Text(
                        text = stringResource(R.string.relabel_need_models),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (downloadProgress != null) {
                    Text(
                        text = stringResource(R.string.relabel_downloading, (downloadProgress * 100).toInt()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LinearProgressIndicator(
                        progress = downloadProgress,
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outline
                    )
                }
                if (downloadError != null) {
                    Text(
                        text = stringResource(R.string.relabel_download_failed, downloadError),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !tooLong && !downloading,
                onClick = { if (needsModels) onDownloadAndConfirm(selected) else onConfirm(selected) }
            ) {
                Text(stringResource(if (needsModels) R.string.relabel_download else R.string.relabel_run))
            }
        },
        dismissButton = {
            TextButton(enabled = !downloading, onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
