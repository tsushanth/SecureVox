package com.securevox.app.speakers

import com.securevox.app.data.model.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerAlignTest {

    private fun seg(i: Int, start: Long, end: Long) =
        TranscriptSegment(recordingId = "r", text = "s$i", startTimeMs = start, endTimeMs = end, segmentIndex = i)

    @Test
    fun picksTheSpeakerWhoTalksMostOfTheSegment() {
        val spans = listOf(SpeakerSpan(0, 1_000, 0), SpeakerSpan(1_000, 5_000, 1))
        val out = assignSpeakers(listOf(seg(0, 0, 4_000)), spans)
        // 1s of speaker 0 vs 3s of speaker 1, and speaker 1 is the only label so it becomes 0.
        assertEquals(0, out[0].speaker)
    }

    @Test
    fun numbersSpeakersByFirstAppearance() {
        val spans = listOf(SpeakerSpan(0, 2_000, 7), SpeakerSpan(2_000, 4_000, 3))
        val out = assignSpeakers(listOf(seg(0, 0, 2_000), seg(1, 2_000, 4_000), seg(2, 4_000, 4_500)), spans)
        assertEquals(listOf(0, 1, 1), out.map { it.speaker })
    }

    @Test
    fun usesTheNearestSpeakerForASegmentInAGap() {
        val spans = listOf(SpeakerSpan(0, 1_000, 0), SpeakerSpan(9_000, 10_000, 1))
        val out = assignSpeakers(listOf(seg(0, 1_200, 1_800), seg(1, 8_000, 8_500)), spans)
        assertEquals(listOf(0, 1), out.map { it.speaker })
    }

    @Test
    fun leavesSegmentsUnlabelledWithoutSpans() {
        val out = assignSpeakers(listOf(seg(0, 0, 1_000)), emptyList())
        assertEquals(-1, out[0].speaker)
    }

    @Test
    fun detectsWhetherThereIsMoreThanOneSpeaker() {
        val one = assignSpeakers(listOf(seg(0, 0, 1_000), seg(1, 1_000, 2_000)), listOf(SpeakerSpan(0, 2_000, 4)))
        assertFalse(hasMultipleSpeakers(one))
        val two = assignSpeakers(
            listOf(seg(0, 0, 1_000), seg(1, 1_000, 2_000)),
            listOf(SpeakerSpan(0, 1_000, 4), SpeakerSpan(1_000, 2_000, 5))
        )
        assertTrue(hasMultipleSpeakers(two))
    }
}
