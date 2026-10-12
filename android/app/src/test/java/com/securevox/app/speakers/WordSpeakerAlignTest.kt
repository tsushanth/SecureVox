package com.securevox.app.speakers

import com.securevox.app.data.model.TranscriptSegment
import com.securevox.app.data.model.WordCodec
import com.securevox.app.data.model.WordTiming
import com.securevox.app.data.model.timedWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordSpeakerAlignTest {

    /** Builds a segment from "word@start" tokens, each word lasting 300 ms unless given. */
    private fun segOf(index: Int, vararg parts: Any, end: Long? = null): TranscriptSegment {
        val words = parts.toList().chunked(2).map { (t, s) -> WordTiming(t as String, s as Long, s + 300) }
        return TranscriptSegment(
            recordingId = "r",
            text = words.joinToString(" ") { it.text },
            startTimeMs = words.first().startMs,
            endTimeMs = end ?: words.last().endMs,
            segmentIndex = index,
            words = WordCodec.encode(words)
        )
    }

    @Test
    fun splitsAtATurnBoundaryInsideASegment() {
        // "Yes." is speaker A (0-1 s); "I think so." is speaker B (1-3 s).
        val seg = segOf(0, "Yes.", 0L, "I", 1_000L, "think", 1_300L, "so.", 1_600L, end = 2_000)
        val spans = listOf(SpeakerSpan(0, 950, 5), SpeakerSpan(950, 3_000, 9))
        val out = assignSpeakersByWord(listOf(seg), spans)
        assertEquals(2, out.size)
        assertEquals(listOf("Yes.", "I think so."), out.map { it.text })
        assertEquals(listOf(0, 1), out.map { it.speaker })
        assertEquals(listOf(0, 1), out.map { it.segmentIndex })
        // Contiguous times covering the original segment.
        assertEquals(0L, out[0].startTimeMs)
        assertEquals(1_000L, out[0].endTimeMs)
        assertEquals(1_000L, out[1].startTimeMs)
        assertEquals(2_000L, out[1].endTimeMs)
        // The first piece keeps the original row id; words travel with their piece.
        assertEquals(seg.id, out[0].id)
        assertTrue(out[1].id != seg.id)
        assertEquals(listOf("I", "think", "so."), out[1].timedWords().map { it.text })
    }

    @Test
    fun noChangeLeavesTheSegmentAlone() {
        val seg = segOf(0, "All", 0L, "one", 400L, "voice.", 800L)
        val out = assignSpeakersByWord(listOf(seg), listOf(SpeakerSpan(0, 2_000, 3)))
        assertEquals(1, out.size)
        assertEquals(seg.text, out[0].text)
        assertEquals(seg.words, out[0].words)
        assertEquals(seg.id, out[0].id)
        assertEquals(0, out[0].speaker)
    }

    @Test
    fun lateWordTimesDoNotCutASentenceInTwo() {
        // The diarizer's turn ends at 1.2 s but Whisper's word times run late, so the last two
        // words of A's sentence land in B's span. The sentence stays with A.
        val seg = segOf(0, "That", 0L, "is", 300L, "what", 600L, "I", 900L, "meant.", 1_300L, end = 1_700)
        val next = segOf(1, "Right.", 1_800L, end = 2_200)
        val spans = listOf(SpeakerSpan(0, 1_200, 1), SpeakerSpan(1_200, 3_000, 2))
        val out = assignSpeakersByWord(listOf(seg, next), spans)
        assertEquals(2, out.size)
        assertEquals("That is what I meant.", out[0].text)
        assertEquals(listOf(0, 1), out.map { it.speaker })
    }

    @Test
    fun aLongInterruptionInsideOneSentenceIsKept() {
        // No punctuation (a run-on): B takes over for 3 s and 5 words, so it is a real turn.
        val seg = segOf(
            0, "so", 0L, "anyway", 300L, "I", 600L,
            "no", 1_000L, "wait", 1_700L, "that", 2_300L, "is", 2_900L, "wrong", 3_500L
        )
        val spans = listOf(SpeakerSpan(0, 950, 1), SpeakerSpan(950, 5_000, 2))
        val out = assignSpeakersByWord(listOf(seg), spans)
        assertEquals(listOf("so anyway I", "no wait that is wrong"), out.map { it.text })
    }

    @Test
    fun speakersAreNumberedByFirstAppearanceAcrossSegments() {
        val s0 = segOf(0, "Hi.", 0L)
        val s1 = segOf(1, "Hello.", 1_000L)
        val s2 = segOf(2, "Again.", 2_000L)
        val spans = listOf(SpeakerSpan(0, 900, 7), SpeakerSpan(900, 1_900, 3), SpeakerSpan(1_900, 3_000, 7))
        val out = assignSpeakersByWord(listOf(s0, s1, s2), spans)
        assertEquals(listOf(0, 1, 0), out.map { it.speaker })
    }

    @Test
    fun segmentsWithoutWordsFallBackToTheDominantSpeaker() {
        val old = TranscriptSegment(recordingId = "r", text = "old row", startTimeMs = 0, endTimeMs = 4_000, segmentIndex = 0)
        val spans = listOf(SpeakerSpan(0, 1_000, 0), SpeakerSpan(1_000, 4_000, 1))
        val out = assignSpeakersByWord(listOf(old), spans)
        assertEquals(1, out.size)
        assertEquals("old row", out[0].text)
        assertEquals(0, out[0].speaker) // renumbered: the only speaker used is Speaker 1
    }

    @Test
    fun wordsInAGapGoToTheNearestSpan() {
        val words = listOf(WordTiming("a", 1_000, 1_200), WordTiming("b", 5_000, 5_200))
        val spans = listOf(SpeakerSpan(0, 500, 4), SpeakerSpan(6_000, 7_000, 8))
        assertEquals(listOf(4, 8), wordSpeakers(words, spans).toList())
    }

    @Test
    fun hasMultipleSpeakersStillWorksOnSplitOutput() {
        val seg = segOf(0, "Yes.", 0L, "No.", 1_000L)
        val out = assignSpeakersByWord(listOf(seg), listOf(SpeakerSpan(0, 900, 0), SpeakerSpan(900, 3_000, 1)))
        assertTrue(hasMultipleSpeakers(out))
    }
}
