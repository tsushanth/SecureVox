package com.securevox.app.presentation

import com.securevox.app.data.model.Recording
import com.securevox.app.presentation.recordings.RecordingRows
import com.securevox.app.presentation.recordings.RecordingStopper
import com.securevox.app.service.AudioRecorderService
import com.securevox.app.service.EmptyRecording
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The accidental Start-then-Stop: a header-only WAV must leave no row, never reach transcription, and be reported
 * only when the user recorded long enough for "no audio" to be a real microphone fault.
 */
class EmptyRecordingTest {
    private lateinit var dir: File
    private val rows = mutableMapOf<String, Recording>()
    private var transcriptions = 0
    private var reports = 0

    private val fakeRows = object : RecordingRows {
        override suspend fun get(id: String) = rows[id]
        override suspend fun update(recording: Recording) { rows[recording.id] = recording }
        override suspend fun delete(id: String) { rows.remove(id) }
    }
    private val stopper = RecordingStopper(fakeRows) { transcriptions++ }

    @Before fun setUp() { dir = java.nio.file.Files.createTempDirectory("emptyrec").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    /** What AudioRecorderService writes: a 44-byte header, then [audioBytes] of samples. */
    private fun wav(name: String, audioBytes: Int): File =
        File(dir, name).apply { writeBytes(AudioRecorderService.wavHeader(audioBytes.toLong()) + ByteArray(audioBytes)) }

    /** The row RecordingsViewModel saves the moment recording starts. */
    private fun startRow(file: File): String =
        Recording(title = "Recording", audioFilePath = file.absolutePath).also { rows[it.id] = it }.id

    /** Stop as the ViewModel does: the recorder settles the file, then the stopper finishes the row. */
    private fun stop(id: String, file: File, wallClockMs: Long): RecordingStopper.Result {
        val kept = EmptyRecording.settle(file, wallClockMs) { reports++ }
        return runBlocking { stopper.onStopped(id, kept, if (kept == null) 0 else AudioRecorderService.durationMsForWavBytes(file.length())) }
    }

    @Test
    fun instantStartStopLeavesNoRowNoTranscriptionNoReport() {
        val file = wav("a.wav", 0)
        val id = startRow(file)
        assertEquals(RecordingStopper.Result.DISCARDED, stop(id, file, wallClockMs = 300))
        assertTrue(rows.isEmpty())
        assertEquals(0, transcriptions)
        assertEquals(0, reports)
        assertFalse(file.exists())
    }

    @Test
    fun alongerRecordingWithNoSamplesIsReportedAndLeavesNoRow() {
        val file = wav("b.wav", 0)
        val id = startRow(file)
        assertEquals(RecordingStopper.Result.DISCARDED, stop(id, file, wallClockMs = 4_000))
        assertTrue(rows.isEmpty())
        assertEquals(0, transcriptions)
        assertEquals(1, reports)
    }

    @Test
    fun theReportThresholdIsExclusive() {
        val atLimit = wav("c.wav", 0)
        EmptyRecording.settle(atLimit, EmptyRecording.REPORT_AFTER_MS) { reports++ }
        assertEquals(0, reports)
        val over = wav("d.wav", 0)
        EmptyRecording.settle(over, EmptyRecording.REPORT_AFTER_MS + 1) { reports++ }
        assertEquals(1, reports)
    }

    @Test
    fun aRealRecordingIsKeptAndTranscribedWithItsDuration() {
        val file = wav("e.wav", 32_000 * 3) // three seconds of audio
        val id = startRow(file)
        assertEquals(RecordingStopper.Result.KEPT, stop(id, file, wallClockMs = 3_000))
        assertEquals(1, transcriptions)
        assertEquals(0, reports)
        assertEquals(3_000L, rows.getValue(id).duration)
        assertEquals(file.length(), rows.getValue(id).fileSize)
    }

    @Test
    fun aRecorderThatHadNothingToReturnNeverDeletesARowWithAudioBehindIt() {
        // e.g. stop pressed when the recorder was not recording: the row's file has real audio, so keep the row.
        val file = wav("f.wav", 32_000)
        val id = startRow(file)
        val result = runBlocking { stopper.onStopped(id, null, 0) }
        assertEquals(RecordingStopper.Result.IGNORED, result)
        assertTrue(rows.containsKey(id))
        assertEquals(0, transcriptions)
    }

    @Test
    fun missingRowAndNoFileIsHarmless() {
        assertEquals(RecordingStopper.Result.IGNORED, runBlocking { stopper.onStopped("nope", null, 0) })
        assertNull(EmptyRecording.settle(null, 5_000) { reports++ })
        assertEquals(0, reports)
    }
}
