package com.securevox.app.whisper

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.securevox.app.R
import java.util.Locale

/**
 * Name of a language written in [locale] ("ja" shown to a German user is "Japanisch"), first letter capitalised for
 * lists and sentences. Falls back to the code if the platform has no name for it.
 */
fun languageNameIn(code: String, locale: Locale): String {
    val name = Locale.forLanguageTag(code).getDisplayLanguage(locale)
    if (name.isBlank() || name == code) return code
    return name.replaceFirstChar { it.titlecase(locale) }
}

/** Language name in the app's current UI language (follows the per-app language and the system language). */
@Composable
fun WhisperLanguage.label(): String {
    val locale = LocalConfiguration.current.locales[0]
    return if (this == WhisperLanguage.AUTO) stringResource(R.string.language_auto_detect)
    else remember(code, locale) { languageNameIn(code, locale) }
}
