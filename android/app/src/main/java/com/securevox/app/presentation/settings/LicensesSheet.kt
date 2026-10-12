package com.securevox.app.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.securevox.app.R

/** One chunk of the license file: a heading line (`=== ... ===`) or a paragraph. */
internal data class LicenseBlock(val text: String, val isHeading: Boolean)

/**
 * Splits the bundled license text into blank-line-separated blocks so a long file scrolls lazily.
 * The upstream texts are hard-wrapped at about 78 columns, which breaks raggedly on a phone, so
 * each paragraph is reflowed: line breaks and indentation inside it become single spaces (the
 * wording is unchanged). Heading blocks keep their lines.
 */
internal fun parseLicenseBlocks(raw: String): List<LicenseBlock> =
    raw.replace("\r\n", "\n")
        .split(Regex("\n{2,}"))
        .map { it.trim('\n') }
        .filter { it.isNotBlank() }
        .map { block ->
            val isHeading = block.startsWith("=== ") && block.lines().first().endsWith(" ===")
            val text = if (isHeading) block else block.lines().joinToString(" ") { it.trim() }
            LicenseBlock(text, isHeading)
        }

/** Credits and full license texts for the third-party components bundled in the app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val blocks = remember {
        context.resources.openRawResource(R.raw.open_source_licenses)
            .bufferedReader().use { parseLicenseBlocks(it.readText()) }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(Modifier.fillMaxHeight(0.92f).navigationBarsPadding()) {
            Text(
                text = stringResource(R.string.licenses_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(blocks) { block ->
                    if (block.isHeading) {
                        Text(
                            text = block.text,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 20.dp)
                        )
                    } else {
                        Text(
                            text = block.text,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
