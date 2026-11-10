package com.securevox.app.data

import com.securevox.app.data.model.Recording
import com.securevox.app.data.model.TranscriptionStatus
import com.securevox.app.service.AudioRecorderService
import com.securevox.app.service.WavReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecordingRecoveryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val now = 10_000_000_000L
    private val old = now - RecordingRecovery.ACTIVE_WINDOW_MS - 1

    /** What the recorder leaves behind when it is killed: a zeroed 44-byte header, then PCM. */
    private fun interruptedFile(pcmBytes: Int, lastModified: Long = old, name: String = "r.wav"): File =
        tmp.newFile(name).also {
            it.writeBytes(ByteArray(44) + ByteArray(pcmBytes) { i -> (i % 100).toByte() })
            it.setLastModified(lastModified)
        }

    private fun row(
        file: File,
        status: TranscriptionStatus = TranscriptionStatus.PENDING,
        duration: Long = 0L,
        size: Long = 0L,
        id: String = file.name
    ) = Recording(id = id, title = "t", audioFilePath = file.absolutePath, duration = duration, fileSize = size,
        transcriptionStatus = status)

    @Test
    fun repairsAZeroedHeaderFromTheFileLength() {
        val file = interruptedFile(pcmBytes = 96_000) // 3 s at 16 kHz mono PCM16

        val found = RecordingRecovery.recoverFile(file, now)

        assertNotNull(found)
        assertEquals(3_000L, found!!.durationMs)
        assertEquals(96_044L, found.fileSize)
        // The repaired file is what the recorder would have written, and the transcriber can read it.
        assertArrayEquals(AudioRecorderService.wavHeader(96_000), file.readBytes().copyOf(44))
        val info = WavReader.readWavInfo(file.absolutePath)
        assertNotNull(info)
        assertEquals(96_000L, info!!.dataSizeBytes)
        assertEquals(48_000L, info.totalMonoSamples)
    }

    @Test
    fun keepsTheAudioBytesUntouched() {
        val file = interruptedFile(pcmBytes = 1_000)
        val before = file.readBytes().copyOfRange(44, 1_044)
        RecordingRecovery.recoverFile(file, now)
        assertArrayEquals(before, file.readBytes().copyOfRange(44, 1_044))
    }

    @Test
    fun dropsATrailingHalfSampleFromTheDataSize() {
        val file = interruptedFile(pcmBytes = 1_001)
        RecordingRecovery.recoverFile(file, now)
        assertEquals(1_000L, WavReader.readWavInfo(file.absolutePath)!!.dataSizeBytes)
    }

    @Test
    fun leavesAFileThatMayStillBeRecordingAlone() {
        val file = interruptedFile(pcmBytes = 96_000, lastModified = now - 5_000)
        assertNull(RecordingRecovery.recoverFile(file, now))
        assertTrue(file.readBytes().copyOf(44).all { it == 0.toByte() })
    }

    @Test
    fun reportsAFinishedFileWithoutRewritingIt() {
        val file = tmp.newFile("done.wav")
        file.writeBytes(AudioRecorderService.wavHeader(32_000) + ByteArray(32_000))
        file.setLastModified(old)
        val before = file.readBytes()

        val found = RecordingRecovery.recoverFile(file, now)

        assertEquals(1_000L, found!!.durationMs)
        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun ignoresFilesWithNothingToRecover() {
        assertNull(RecordingRecovery.recoverFile(File(tmp.root, "missing.wav"), now))

        val headerOnly = tmp.newFile("empty.wav").also { it.writeBytes(ByteArray(44)); it.setLastModified(old) }
        assertNull(RecordingRecovery.recoverFile(headerOnly, now))

        // Not one of our recordings: some other content with a non-zero, non-WAV header.
        val other = tmp.newFile("other.bin").also { it.writeBytes(ByteArray(200) { 7 }); it.setLastModified(old) }
        assertNull(RecordingRecovery.recoverFile(other, now))
        assertTrue(other.readBytes().all { it == 7.toByte() })
    }

    @Test
    fun wavHeaderLayoutMatchesTheRecorder() {
        val h = AudioRecorderService.wavHeader(96_000)
        assertEquals(44, h.size)
        assertEquals("RIFF", String(h, 0, 4))
        assertEquals("WAVE", String(h, 8, 4))
        assertEquals("fmt ", String(h, 12, 4))
        assertEquals("data", String(h, 36, 4))
        fun int(at: Int) = (h[at].toInt() and 0xFF) or ((h[at + 1].toInt() and 0xFF) shl 8) or
            ((h[at + 2].toInt() and 0xFF) shl 16) or ((h[at + 3].toInt() and 0xFF) shl 24)
        assertEquals(96_036, int(4))
        assertEquals(16_000, int(24))
        assertEquals(32_000, int(28))
        assertEquals(96_000, int(40))
    }

    @Test
    fun onlyUnfinishedPendingRowsLookInterrupted() {
        val f = File("x.wav")
        assertTrue(RecordingRecovery.looksInterrupted(row(f)))
        assertFalse(RecordingRecovery.looksInterrupted(row(f, duration = 5_000L)))
        assertFalse(RecordingRecovery.looksInterrupted(row(f, size = 100L)))
        assertFalse(RecordingRecovery.looksInterrupted(row(f, status = TranscriptionStatus.COMPLETED)))
        assertFalse(RecordingRecovery.looksInterrupted(row(f, status = TranscriptionStatus.FAILED)))
        assertFalse(RecordingRecovery.looksInterrupted(row(f, status = TranscriptionStatus.IN_PROGRESS)))
    }

    @Test
    fun recoverUpdatesAndReturnsOnlyTheRowsItRepaired() = runBlocking {
        val killed = interruptedFile(96_000, name = "killed.wav")
        val live = interruptedFile(96_000, lastModified = now - 1_000, name = "live.wav")
        val finished = tmp.newFile("finished.wav").also { it.writeBytes(AudioRecorderService.wavHeader(100) + ByteArray(100)) }
        val rows = listOf(
            row(killed),
            row(live),
            row(finished, status = TranscriptionStatus.COMPLETED, duration = 3L, size = 144L),
            row(File(tmp.root, "gone.wav"))
        )
        val saved = mutableListOf<Recording>()

        val recovered = RecordingRecovery.recover(rows, now) { saved.add(it) }

        assertEquals(listOf("killed.wav"), recovered.map { it.id })
        assertEquals(recovered, saved)
        assertEquals(3_000L, recovered[0].duration)
        assertEquals(96_044L, recovered[0].fileSize)
        assertEquals(TranscriptionStatus.PENDING, recovered[0].transcriptionStatus)
    }
}
