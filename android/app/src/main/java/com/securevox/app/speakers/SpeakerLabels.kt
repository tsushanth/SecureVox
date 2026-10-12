package com.securevox.app.speakers

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import java.io.File

object SpeakerLabels {
    /**
     * Whether the Settings toggle is shown. It was off for 1.4.0 after a test with two synthetic
     * voices found one speaker. With two real speakers (LibriSpeech clips, 53 s) on a Pixel it
     * found both and labelled 13 of 14 segments correctly in about 8 s, so it is on.
     */
    const val AVAILABLE = true

    val KEY_ENABLED = booleanPreferencesKey("label_speakers_enabled")

    /**
     * Longest recording that gets labelled. The speaker models need the whole recording in
     * memory as floats (4 bytes a sample, about 115 MB at this limit), so longer ones are
     * left unlabelled rather than risking running out of memory.
     */
    const val MAX_SAMPLES = 30L * 60 * 16000

    fun modelDir(context: Context) = File(context.filesDir, SpeakerModels.DIARIZATION_DIR)
}
