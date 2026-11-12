package com.securevox.app.presentation.onboarding

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.securevox.app.R
import com.securevox.app.presentation.settings.dataStore
import com.securevox.app.util.Connectivity
import com.securevox.app.util.funnel.FunnelModel
import com.securevox.app.util.funnel.LangBucket
import com.securevox.app.util.funnel.SetupEntry
import com.securevox.app.util.funnel.Step
import com.securevox.app.util.funnel.funnel
import com.securevox.app.whisper.DownloadPolicy
import com.securevox.app.whisper.DownloadProblem
import com.securevox.app.whisper.DownloadState
import com.securevox.app.whisper.FirstRunModel
import com.securevox.app.whisper.ModelManager
import com.securevox.app.whisper.QualityTier
import com.securevox.app.whisper.SetupNotice
import com.securevox.app.whisper.SetupStatus
import com.securevox.app.whisper.WhisperLanguage
import com.securevox.app.whisper.WhisperModel
import kotlinx.coroutines.flow.first

/**
 * First-run setup: download the speech model once.
 *
 * Never a dead end: it says plainly when the phone is offline or out of storage, continues an interrupted
 * download, suggests a better model for non-English languages (asking first, with the size), and lets the user
 * carry on without a model via [onSkip].
 */
@Composable
fun SetupScreen(
    modelManager: ModelManager,
    entry: SetupEntry,
    onSetupComplete: () -> Unit,
    onSkip: () -> Unit
) {
    val context = LocalContext.current
    val downloadState by modelManager.downloadState.collectAsState()
    val network by remember { Connectivity.observe(context) }.collectAsState(initial = Connectivity.state(context))

    // The language the user will transcribe in: their saved choice, else the device / app language.
    var language by remember { mutableStateOf(WhisperLanguage.fromDeviceLocale()) }
    LaunchedEffect(Unit) {
        val saved = context.dataStore.data.first()[stringPreferencesKey("selected_language")]
        if (saved != null) language = WhisperLanguage.fromCode(saved)
    }
    val offer = remember(language) { FirstRunModel.offerFor(language) }

    // Usage ledger (on this phone only): the setup screen was shown, and through which door.
    LaunchedEffect(Unit) { funnel().step(Step.SETUP_SHOWN, entry) }

    // A model the user asked to confirm first (Base / Small), or null.
    var pendingConfirm by remember { mutableStateOf<WhisperModel?>(null) }

    // Anything already on this screen's session, or an install from a previous run, finishes setup.
    LaunchedEffect(Unit) {
        if (modelManager.isAnyModelInstalled()) onSetupComplete()
    }
    LaunchedEffect(downloadState) {
        if (downloadState is DownloadState.Completed) onSetupComplete()
    }
    // When the connection comes back, a leftover "offline" failure is stale: return to the ready state without
    // a tap on Retry. This only ever replaces that one failure state, so a running download is not affected.
    LaunchedEffect(downloadState, network) {
        modelManager.clearStaleOfflineError(network)
    }

    val appContext = context.applicationContext
    fun start(model: WhisperModel) {
        modelManager.startDownload(model) { installed ->
            // A non-default model chosen here becomes the one used for transcription. Tiny is the default.
            if (installed != WhisperModel.TINY) {
                appContext.dataStore.edit { it[stringPreferencesKey("selected_model")] = installed.fileName }
            }
        }
    }

    fun requestDownload(model: WhisperModel) {
        if (FirstRunModel.needsSizeConfirmation(model)) pendingConfirm = model else start(model)
    }

    pendingConfirm?.let { model ->
        LaunchedEffect(model) { funnel().step(Step.SIZE_CONFIRM_SHOWN, FunnelModel.of(model)) }
        val metered = FirstRunModel.suggestWifi(model, network)
        AlertDialog(
            onDismissRequest = { funnel().step(Step.SIZE_CONFIRM_CANCELLED, FunnelModel.of(model)); pendingConfirm = null },
            title = { Text(stringResource(R.string.setup_confirm_title, model.displayName, model.sizeMB)) },
            text = {
                Text(
                    stringResource(
                        if (metered) R.string.setup_confirm_body_metered else R.string.setup_confirm_body,
                        model.displayName,
                        model.sizeMB
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { pendingConfirm = null; start(model) }) {
                    Text(stringResource(R.string.setup_confirm_download))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    funnel().step(Step.SIZE_CONFIRM_CANCELLED, FunnelModel.of(model))
                    pendingConfirm = null
                }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.Mic,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.welcome_title),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.welcome_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = stringResource(R.string.download_ai_model),
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = stringResource(R.string.download_model_description, WhisperModel.TINY.sizeMB),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.setup_one_time_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    when (val state = downloadState) {
                        is DownloadState.Downloading -> {
                            DownloadProgress(state)
                        }

                        is DownloadState.Completed -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = stringResource(R.string.download_complete),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }

                        else -> {
                            val error = state as? DownloadState.Error
                            // Offline comes from the live network state, or from a failed attempt that the
                            // network has not yet recovered from (see SetupStatus).
                            val notice = SetupStatus.notice(network, error?.problem)
                            val offline = notice == SetupNotice.OFFLINE
                            val showError = notice == SetupNotice.ERROR
                            val target = error?.model ?: if (offer.offersAlternative) offer.suggested else WhisperModel.TINY
                            val partial = remember(state) { modelManager.partialBytes(target) }

                            if (offline) {
                                LaunchedEffect(Unit) { funnel().step(Step.OFFLINE_AT_SETUP) }
                                OfflineNotice(target)
                                Spacer(modifier = Modifier.height(16.dp))
                            } else if (showError && error != null) {
                                Text(
                                    text = problemText(
                                        error,
                                        DownloadPolicy.remainingBytes(error.model, partial)
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                            }

                            if (partial > 0L) {
                                Text(
                                    text = stringResource(
                                        R.string.setup_partial_note,
                                        Formatter.formatFileSize(context, partial)
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                            }

                            if (showError || offline) {
                                // Retry continues from the partial file. A failed attempt was already confirmed.
                                Button(
                                    onClick = { if (showError) start(target) else requestDownload(target) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(if (partial > 0L) R.string.setup_retry_resume else R.string.retry))
                                }
                                if (target != WhisperModel.TINY) {
                                    TextButton(onClick = { requestDownload(WhisperModel.TINY) }) {
                                        Text(stringResource(R.string.setup_tiny_instead, WhisperModel.TINY.sizeMB))
                                    }
                                }
                            } else if (offer.offersAlternative) {
                                LaunchedEffect(offer.suggested, language) {
                                    funnel().step(Step.LANG_OFFER_SHOWN, LangBucket.of(language, WhisperModel.TINY), FunnelModel.of(offer.suggested))
                                }
                                SuggestedModel(
                                    language = language,
                                    offer = offer,
                                    metered = FirstRunModel.suggestWifi(offer.suggested, network),
                                    remainingBytes = SetupStatus.remainingWhenResuming(
                                        offer.suggested, if (target == offer.suggested) partial else 0L
                                    ),
                                    onDownloadSuggested = { requestDownload(offer.suggested) },
                                    onDownloadTiny = { requestDownload(WhisperModel.TINY) }
                                )
                            } else {
                                Button(
                                    onClick = { requestDownload(WhisperModel.TINY) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        Icons.Default.Download,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    val remaining = SetupStatus.remainingWhenResuming(
                                        WhisperModel.TINY, if (target == WhisperModel.TINY) partial else 0L
                                    )
                                    Text(
                                        if (remaining != null) {
                                            stringResource(
                                                R.string.setup_continue_download,
                                                Formatter.formatFileSize(context, remaining)
                                            )
                                        } else {
                                            stringResource(R.string.download_with_size, WhisperModel.TINY.sizeMB)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (downloadState is DownloadState.Downloading) {
                Text(
                    text = stringResource(R.string.setup_downloading_background),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // The way out: never a dead end.
            TextButton(onClick = onSkip) { Text(stringResource(R.string.setup_skip)) }
            Text(
                text = stringResource(R.string.setup_skip_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.privacy_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DownloadProgress(state: DownloadState.Downloading) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LinearProgressIndicator(
            progress = state.progress,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.downloading_progress, state.progressPercent),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = "${Formatter.formatFileSize(context, state.downloadedBytes)} / ${Formatter.formatFileSize(context, state.totalBytes)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun OfflineNotice(target: WhisperModel) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            Icons.Default.WifiOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error
        )
        Text(
            text = stringResource(R.string.setup_offline_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.error
        )
    }
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = stringResource(R.string.setup_offline_body, target.sizeMB),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun SuggestedModel(
    language: WhisperLanguage,
    offer: com.securevox.app.whisper.SetupOffer,
    metered: Boolean,
    remainingBytes: Long?,
    onDownloadSuggested: () -> Unit,
    onDownloadTiny: () -> Unit
) {
    val tinyWord = stringResource(
        when (offer.tinyTier) {
            QualityTier.GOOD -> R.string.quality_good
            QualityTier.FAIR -> R.string.quality_fair
            QualityTier.POOR -> R.string.quality_poor
        }
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.setup_suggest_title, language.displayName, offer.suggested.displayName),
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.setup_suggest_body, tinyWord, language.displayName, offer.suggested.displayName),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (metered) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.setup_wifi_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = onDownloadSuggested, modifier = Modifier.fillMaxWidth()) {
            Icon(
                Icons.Default.Download,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (remainingBytes != null) {
                Text(
                    stringResource(
                        R.string.setup_continue_download,
                        Formatter.formatFileSize(LocalContext.current, remainingBytes)
                    )
                )
            } else {
                Text(stringResource(R.string.setup_suggest_download, offer.suggested.displayName, offer.suggested.sizeMB))
            }
        }
        TextButton(onClick = onDownloadTiny) {
            Text(stringResource(R.string.setup_tiny_instead, WhisperModel.TINY.sizeMB))
        }
    }
}

@Composable
private fun problemText(error: DownloadState.Error, remainingBytes: Long): String {
    val context = LocalContext.current
    return when (error.problem) {
        DownloadProblem.OFFLINE -> stringResource(R.string.setup_problem_offline)
        DownloadProblem.TIMEOUT -> stringResource(R.string.setup_problem_timeout)
        DownloadProblem.STORAGE_FULL -> {
            stringResource(R.string.setup_problem_storage, Formatter.formatFileSize(context, remainingBytes))
        }
        DownloadProblem.SERVER -> stringResource(R.string.setup_problem_server)
        DownloadProblem.INCOMPLETE -> stringResource(R.string.setup_problem_incomplete)
        DownloadProblem.CHECKSUM -> stringResource(R.string.setup_problem_checksum)
        DownloadProblem.OTHER -> stringResource(R.string.setup_problem_other, error.message)
    }
}
