package com.securevox.app.speakers

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.securevox.app.util.MemoryPolicy
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
     * left unlabelled rather than risking running out of memory. This is only the ceiling:
     * [MemoryPolicy.speakerDecision] lowers it to what the phone can spare at the time.
     */
    const val MAX_SAMPLES = 30L * 60 * 16000

    private const val PREFS = "securevox_prefs"
    private fun noteKey(recordingId: String) = "speaker_note_$recordingId"

    /** Why the speaker pass was skipped for a recording (shown as a hint in the transcript), or null. */
    fun skipNote(context: Context, recordingId: String): MemoryPolicy.SpeakerSkip? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(noteKey(recordingId), null)
            ?.let { name -> MemoryPolicy.SpeakerSkip.entries.firstOrNull { it.name == name } }

    fun setSkipNote(context: Context, recordingId: String, skip: MemoryPolicy.SpeakerSkip?) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (skip == null) edit.remove(noteKey(recordingId)) else edit.putString(noteKey(recordingId), skip.name)
        edit.apply()
    }

    private fun countKey(recordingId: String) = "speaker_count_$recordingId"

    /** The speaker count the user chose for this recording (1..8), or null for automatic. */
    fun chosenCount(context: Context, recordingId: String): Int? =
        SpeakerCount.normalize(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(countKey(recordingId), 0)
        )

    fun setChosenCount(context: Context, recordingId: String, count: Int?) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        val n = SpeakerCount.normalize(count)
        if (n == null) edit.remove(countKey(recordingId)) else edit.putInt(countKey(recordingId), n)
        edit.apply()
    }

    fun modelDir(context: Context) = File(context.filesDir, SpeakerModels.DIARIZATION_DIR)
}
