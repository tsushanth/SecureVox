package com.securevox.app.data

import com.securevox.app.data.model.Recording
import com.securevox.app.data.model.TranscriptionStatus
import com.securevox.app.service.AudioRecorderService
import java.io.File
import java.io.RandomAccessFile

/**
 * Brings back recordings whose recording session ended without the app finishing them.
 *
 * A recording row is saved the moment recording starts, but its WAV header (all zeros until the end),
 * duration, size and transcription are only written by the stop button. If the app is swiped away or the
 * process is killed first, the audio is on disk but the file cannot be played, the row shows 0:00 and
 * "Pending" forever, and nothing ever transcribes it. This repairs the header from the file length, fills
 * in the duration and size, and hands the row back so it can be transcribed.
 */
object RecordingRecovery {

    private const val HEADER_BYTES = 44

    /**
     * A file written this recently may still be open for recording, so it is left alone. A live recording
     * writes many times a second, so this is generous.
     */
    const val ACTIVE_WINDOW_MS = 2 * 60_000L

    /** What was found in a recovered file. */
    data class Recovered(val durationMs: Long, val fileSize: Long)

    /** Rows saved at record start and never completed: still pending, with no duration or size recorded. */
    fun looksInterrupted(recording: Recording): Boolean =
        recording.transcriptionStatus == TranscriptionStatus.PENDING &&
            recording.duration == 0L &&
            recording.fileSize == 0L

    /**
     * Makes [file] a playable, correctly sized WAV and returns its length, or null when there is nothing to
     * recover (missing, header only, still being written, or not one of our recordings).
     *
     * The recorder writes a zeroed 44-byte placeholder header and fills it in on stop, so only a file that
     * still has the placeholder is rewritten. A file with a valid header but no database update (killed
     * between the two steps) is reported as is.
     */
    fun recoverFile(file: File, nowMs: Long = System.currentTimeMillis()): Recovered? {
        if (!file.isFile) return null
        val length = file.length()
        if (length <= HEADER_BYTES) return null
        if (nowMs - file.lastModified() < ACTIVE_WINDOW_MS) return null

        val header = ByteArray(HEADER_BYTES)
        try {
            RandomAccessFile(file, "r").use { it.readFully(header) }
        } catch (e: java.io.IOException) {
            return null
        }

        val dataBytes = (length - HEADER_BYTES) and 1L.inv() // whole 16-bit samples only
        when {
            header.all { it == 0.toByte() } -> try {
                RandomAccessFile(file, "rw").use {
                    it.seek(0)
                    it.write(AudioRecorderService.wavHeader(dataBytes))
                }
            } catch (e: java.io.IOException) {
                return null
            }
            isFinishedHeader(header) -> Unit
            else -> return null
        }
        return Recovered(AudioRecorderService.durationMsForWavBytes(length), length)
    }

    private fun isFinishedHeader(header: ByteArray): Boolean =
        String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(header, 8, 4, Charsets.US_ASCII) == "WAVE" &&
            String(header, 36, 4, Charsets.US_ASCII) == "data"

    /**
     * Recovers every interrupted recording in [all]. [update] saves a repaired row (with the new duration and
     * size) and returns normally once stored. Returns the recordings that were recovered, so the caller can
     * transcribe them.
     */
    suspend fun recover(
        all: List<Recording>,
        nowMs: Long = System.currentTimeMillis(),
        update: suspend (Recording) -> Unit
    ): List<Recording> {
        val recovered = ArrayList<Recording>()
        for (recording in all) {
            if (!looksInterrupted(recording)) continue
            val found = recoverFile(File(recording.audioFilePath), nowMs) ?: continue
            val repaired = recording.copy(duration = found.durationMs, fileSize = found.fileSize)
            update(repaired)
            recovered.add(repaired)
        }
        return recovered
    }
}
