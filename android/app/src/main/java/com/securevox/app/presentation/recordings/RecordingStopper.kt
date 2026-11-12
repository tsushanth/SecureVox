package com.securevox.app.presentation.recordings

import com.securevox.app.data.model.Recording
import com.securevox.app.service.EmptyRecording
import java.io.File

/** The few row operations a stopped recording needs; the ViewModel backs it with the repository. */
interface RecordingRows {
    suspend fun get(id: String): Recording?
    suspend fun update(recording: Recording)
    suspend fun delete(id: String)
}

/**
 * Finishes a recording once the recorder has stopped: a real recording gets its duration and size and goes
 * to transcription; one that captured nothing leaves no row behind and is never transcribed.
 */
class RecordingStopper(
    private val rows: RecordingRows,
    private val startTranscription: suspend (recordingId: String) -> Unit
) {
    enum class Result { KEPT, DISCARDED, IGNORED }

    /** [filePath] is what the recorder returned: null when it had nothing to keep. */
    suspend fun onStopped(recordingId: String, filePath: String?, durationMs: Long): Result {
        val row = rows.get(recordingId) ?: return Result.IGNORED
        if (filePath != null) {
            rows.update(row.copy(duration = durationMs, fileSize = File(filePath).length()))
            startTranscription(recordingId)
            return Result.KEPT
        }
        // The recorder returns null for "nothing recorded" but also when it was not recording at all; only
        // remove the row when its audio really is missing or header-only, never a row with audio behind it.
        val audio = File(row.audioFilePath)
        if (!audio.exists() || EmptyRecording.isEmpty(audio)) {
            rows.delete(recordingId)
            return Result.DISCARDED
        }
        return Result.IGNORED
    }
}
