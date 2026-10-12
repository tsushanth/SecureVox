package com.securevox.app.speakers

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import java.io.File

object SpeakerLabels {
    /**
     * Whether the Settings toggle is shown. Off for release: on a Pixel the speaker pass ran
     * (about 3 s for 24 s of audio) but found one speaker in a clip with two clearly different
     * voices, so it is not ready to ship. Everything else here stays in place so it can be
     * turned on once that is fixed.
     */
    const val AVAILABLE = false

    val KEY_ENABLED = booleanPreferencesKey("label_speakers_enabled")

    /**
     * Longest recording that gets labelled. The speaker models need the whole recording in
     * memory as floats (4 bytes a sample, about 115 MB at this limit), so longer ones are
     * left unlabelled rather than risking running out of memory.
     */
    const val MAX_SAMPLES = 30L * 60 * 16000

    fun modelDir(context: Context) = File(context.filesDir, SpeakerModels.DIARIZATION_DIR)
}
