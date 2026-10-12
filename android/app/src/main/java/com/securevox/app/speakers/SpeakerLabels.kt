package com.securevox.app.speakers

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import java.io.File

object SpeakerLabels {
    val KEY_ENABLED = booleanPreferencesKey("label_speakers_enabled")

    /**
     * Longest recording that gets labelled. The speaker models need the whole recording in
     * memory as floats (4 bytes a sample, about 115 MB at this limit), so longer ones are
     * left unlabelled rather than risking running out of memory.
     */
    const val MAX_SAMPLES = 30L * 60 * 16000

    fun modelDir(context: Context) = File(context.filesDir, SpeakerModels.DIARIZATION_DIR)
}
