package com.securevox.app.service

import com.securevox.app.whisper.parseWhisperSegments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkTimingTest {
    private val chunk = 30 * 16000
    private val overlap = 16000

    @Test
    fun threeChunkRecordingMapsLocalTimesToRecordingTimes() {
        // Chunk starts: 0 s, 29 s, 59 s (chunks after the first begin 1 s early).
        val starts = (0..2).map { ChunkTiming.chunkStartSample(it, chunk, overlap) }
        assertEquals(listOf(0, 29 * 16000, 59 * 16000), starts)
        val offsets = starts.map { ChunkTiming.chunkOffsetMs(it) }
        assertEquals(listOf(0L, 29_000L, 59_000L), offsets)

        // Something said at 40.0 s in the recording is 11.0 s into chunk 1 and 1.0 s... into chunk 2 it is -19 (not there).
        // A segment 5.0 s into chunk 1 happened at 34.0 s in the recording (NOT 33.0 s).
        assertEquals(34_000L, ChunkTiming.toRecordingMs(5_000, offsets[1]))
        // A segment 3.5 s into chunk 2 happened at 62.5 s.
        assertEquals(62_500L, ChunkTiming.toRecordingMs(3_500, offsets[2]))
        // Chunk 0 is unchanged.
        assertEquals(12_000L, ChunkTiming.toRecordingMs(12_000, offsets[0]))
    }

    @Test
    fun overlapFilterIsChunkLocal() {
        assertEquals(0L, ChunkTiming.overlapMs(0, overlap))
        assertEquals(1_000L, ChunkTiming.overlapMs(1, overlap))
        assertTrue(ChunkTiming.isNewSegment(0, 0))
        assertFalse(ChunkTiming.isNewSegment(400, 1_000))  // already transcribed by the previous chunk
        assertTrue(ChunkTiming.isNewSegment(1_000, 1_000))
    }

    @Test
    fun wordTimesUseTheSameOffsetAsTheirSegment() {
        val json = """[{"text":" Hello there","start":5000,"end":6000,"tokens":[[" Hello",5000,5400],[" there",5500,5900]]}]"""
        val seg = parseWhisperSegments(json).single()
        val offset = ChunkTiming.chunkOffsetMs(ChunkTiming.chunkStartSample(1, chunk, overlap))
        val start = ChunkTiming.toRecordingMs(seg.startTimeMs, offset)
        val shifted = seg.words.map { it.shifted(offset) }
        assertEquals(34_000L, start)
        assertEquals(34_000L, shifted[0].startMs) // first word starts with the segment
        assertEquals(34_500L, shifted[1].startMs)
        assertTrue(shifted.all { it.startMs >= start && it.endMs <= ChunkTiming.toRecordingMs(seg.endTimeMs, offset) })
    }
}
