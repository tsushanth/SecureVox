package com.securevox.app.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.securevox.app.R
import com.securevox.app.engines.EngineDownloadState
import com.securevox.app.engines.EngineGates
import com.securevox.app.engines.EngineId
import com.securevox.app.engines.EngineManager
import com.securevox.app.engines.EngineModels
import com.securevox.app.engines.EngineRouter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * "Speech engines": download, cancel and delete the alternative recognizers, plus a one-tap suggestion when the
 * selected language has a better engine that is not installed. Self-contained (own state in [EngineManager]) so
 * the rest of Settings is untouched. Draws nothing when no alternative engine is shipped.
 */
@Composable
fun EngineSettingsSection(languageCode: String, languageName: String) {
    val engines = listOf(EngineId.NEMO_FAST_CONFORMER, EngineId.SENSE_VOICE).filter { it in EngineGates.available }
    if (engines.isEmpty()) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val states by EngineManager.states.collectAsState()
    @Suppress("UNUSED_VARIABLE") val installVersion by EngineManager.installVersion.collectAsState()
    val whisperOnly by remember(context) {
        context.dataStore.data.map { it[EngineManager.KEY_WHISPER_ONLY] ?: false }
    }.collectAsState(initial = false)

    val suggested = EngineRouter.betterEngineFor(languageCode)
        ?.takeIf { !EngineManager.isInstalled(context, it) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.engines_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = stringResource(R.string.engines_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (suggested != null) {
            val mb = EngineModels.packageFor(suggested)?.downloadMb ?: 0
            val busy = states[suggested] is EngineDownloadState.Downloading
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.engines_suggestion, engineName(suggested), languageName, mb),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    Button(enabled = !busy, onClick = { EngineManager.download(context, suggested) }) {
                        Text(stringResource(R.string.engine_download))
                    }
                }
            }
        }

        engines.forEach { id ->
            val pkg = EngineModels.packageFor(id) ?: return@forEach
            val installed = EngineManager.isInstalled(context, id)
            val state = states[id] ?: EngineDownloadState.Idle
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(engineName(id), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(engineDescription(id)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    when (state) {
                        is EngineDownloadState.Downloading -> {
                            LinearProgressIndicator(progress = state.progress, modifier = Modifier.fillMaxWidth())
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    stringResource(R.string.engine_downloading, (state.progress * 100).toInt()),
                                    style = MaterialTheme.typography.labelMedium
                                )
                                TextButton(onClick = { EngineManager.cancel(id) }) {
                                    Text(stringResource(R.string.engine_cancel))
                                }
                            }
                        }
                        else -> Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = when {
                                    state is EngineDownloadState.Failed -> stringResource(R.string.engine_failed, state.message)
                                    installed -> stringResource(R.string.engine_installed)
                                    else -> stringResource(R.string.engine_size_format, pkg.downloadMb)
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (state is EngineDownloadState.Failed) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            if (installed) {
                                OutlinedButton(onClick = { EngineManager.delete(context, id) }) {
                                    Text(stringResource(R.string.engine_delete))
                                }
                            } else {
                                Button(onClick = { EngineManager.download(context, id) }) {
                                    Text(stringResource(R.string.engine_download))
                                }
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.engines_use_alternative), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.engines_use_alternative_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = !whisperOnly,
                onCheckedChange = { scope.launch { EngineManager.setWhisperOnly(context, !it) } }
            )
        }
    }
}

@Composable
private fun engineName(id: EngineId): String = when (id) {
    EngineId.NEMO_FAST_CONFORMER -> stringResource(R.string.engine_nemo_name)
    EngineId.SENSE_VOICE -> stringResource(R.string.engine_sense_voice_name)
    EngineId.WHISPER -> id.displayName
}

private fun engineDescription(id: EngineId): Int = when (id) {
    EngineId.NEMO_FAST_CONFORMER -> R.string.engine_nemo_desc
    else -> R.string.engine_sense_voice_desc
}
