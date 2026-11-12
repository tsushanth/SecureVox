package com.securevox.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The first-transcript strings (progress text, ready notification, rename, search hint) exist in every
 * locale folder with the same %-placeholders as English, so a translation cannot crash String.format.
 */
class NewStringsLocalizedTest {
    private val keys = listOf(
        "transcribing_progress", "transcribing_progress_left", "transcribing_estimate_total", "retry_transcription",
        "notif_rationale_title", "notif_rationale_body", "not_now", "notif_ready_title", "notif_channel_ready_name",
        "notif_channel_ready_description", "notif_channel_progress_name", "notif_channel_progress_description",
        "notif_progress_title", "notif_progress_text", "rename", "rename_recording_title", "rename_field_label", "save",
        "search_recordings", "nothing_recorded_toast",
        // On this phone (usage ledger) screen
        "funnel_settings_title", "funnel_settings_subtitle", "funnel_stays_on_phone", "funnel_since", "funnel_empty",
        "funnel_row_detail", "funnel_reset", "funnel_reset_title", "funnel_reset_body"
    )

    private fun res(): File = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    private fun strings(file: File): Map<String, String> =
        Regex("""<string name="([^"]+)">([^<]*)</string>""").findAll(file.readText()).associate { it.groupValues[1] to it.groupValues[2] }

    private fun placeholders(s: String): List<String> = Regex("""%\d\$[ds]""").findAll(s).map { it.value }.sorted().toList()

    @Test
    fun everyLocaleHasEveryKeyWithMatchingPlaceholders() {
        val english = strings(File(res(), "values/strings.xml"))
        val locales = res().listFiles { f -> f.isDirectory && f.name.startsWith("values-") && File(f, "strings.xml").exists() }!!
        assertTrue("expected the 19 translated locales", locales.size >= 19)
        for (dir in locales) {
            val translated = strings(File(dir, "strings.xml"))
            for (key in keys) {
                val value = translated[key]
                assertTrue("${dir.name} is missing $key", value != null && value.isNotBlank())
                assertEquals("${dir.name} $key placeholders", placeholders(english.getValue(key)), placeholders(value!!))
            }
        }
    }
}
