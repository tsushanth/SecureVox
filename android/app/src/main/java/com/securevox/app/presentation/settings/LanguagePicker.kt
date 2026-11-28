package com.securevox.app.presentation.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.securevox.app.R
import com.securevox.app.whisper.WhisperLanguage
import com.securevox.app.whisper.label

/** The transcription language list, shared by Settings and the first-run setup card. */
@Composable
fun LanguagePickerDialog(
    selected: WhisperLanguage,
    onSelect: (WhisperLanguage) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_language)) },
        text = {
            LazyColumn {
                items(WhisperLanguage.entries.toList()) { language ->
                    // The whole row is the target (name and radio), announced as one radio button.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = language == selected,
                                role = Role.RadioButton,
                                onClick = { onSelect(language) }
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // onClick = null: the row handles the tap, so the radio is visual only and is not
                        // announced a second time by accessibility services.
                        RadioButton(selected = language == selected, onClick = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = language.label(), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
