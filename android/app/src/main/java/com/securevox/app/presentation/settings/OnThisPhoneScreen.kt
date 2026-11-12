package com.securevox.app.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.securevox.app.R
import com.securevox.app.util.funnel.FunnelHolder
import com.securevox.app.util.funnel.LedgerSnapshot
import com.securevox.app.util.funnel.LedgerSummary
import java.time.ZoneId

/**
 * Settings > On this phone. Shows the on-device usage ledger in plain words: which setup and usage steps this
 * app has counted, how often and when. It states plainly that this stays on the phone, and can be reset.
 * (Whatever the user attaches to a Report a Problem email is the same text, and they see it before sending.)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnThisPhoneScreen(onNavigateBack: () -> Unit) {
    val ledger = FunnelHolder.ledger
    var snapshot by remember { mutableStateOf(ledger?.snapshot() ?: LedgerSnapshot(null, emptyList())) }
    var confirmReset by remember { mutableStateOf(false) }
    val zone = remember { ZoneId.systemDefault() }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.funnel_reset_title)) },
            text = { Text(stringResource(R.string.funnel_reset_body)) },
            confirmButton = {
                TextButton(onClick = {
                    ledger?.reset()
                    snapshot = LedgerSnapshot(null, emptyList())
                    confirmReset = false
                }) { Text(stringResource(R.string.funnel_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.funnel_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.funnel_stays_on_phone),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            snapshot.sinceMs?.let { since ->
                item {
                    Text(
                        text = stringResource(R.string.funnel_since, LedgerSummary.formatTime(since, zone)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            if (snapshot.isEmpty) {
                item {
                    Text(
                        text = stringResource(R.string.funnel_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
            }
            items(snapshot.steps, key = { it.step.code }) { stats ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = stats.step.plain, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(
                                R.string.funnel_row_detail,
                                stats.entry.count,
                                LedgerSummary.formatTime(stats.entry.firstMs, zone),
                                LedgerSummary.formatTime(stats.entry.lastMs, zone)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val dims = LedgerSummary.dimsText(stats)
                        if (dims.isNotEmpty()) {
                            Text(
                                text = dims,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = { confirmReset = true },
                    enabled = !snapshot.isEmpty,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text(stringResource(R.string.funnel_reset)) }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}
