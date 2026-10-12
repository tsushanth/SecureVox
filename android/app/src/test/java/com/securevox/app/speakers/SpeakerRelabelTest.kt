package com.securevox.app.speakers

import com.securevox.app.data.local.TranscriptSegmentDao
import com.securevox.app.data.model.TranscriptSegment
import com.securevox.app.data.model.WordCodec
import com.securevox.app.data.model.WordTiming
import com.securevox.app.data.model.timedWords
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerRelabelTest {

    private fun seg(id: String, index: Int, vararg parts: Any, end: Long? = null, speaker: Int = -1, split: Boolean = false): TranscriptSegment {
        val words = parts.toList().chunked(2).map { (t, s) -> WordTiming(t as String, s as Long, s + 300) }
        return TranscriptSegment(
            id = id, recordingId = "r", text = words.joinToString(" ") { it.text },
            startTimeMs = words.first().startMs, endTimeMs = end ?: words.last().endMs,
            segmentIndex = index, speaker = speaker, words = WordCodec.encode(words), speakerSplit = split
        )
    }

    // Three Whisper segments, never labelled. Voices by time: A 0-1s, B 1-2.5s, C 2.5-4.5s.
    private fun original() = listOf(
        seg("s1", 0, "Yes.", 0L, "I", 1_000L, "think", 1_300L, "so.", 1_600L, end = 2_000),
        seg("s2", 1, "Okay", 3_000L, "then.", 3_300L, end = 3_700),
        seg("s3", 2, "Right.", 4_000L)
    )

    private val twoSpeakers = listOf(SpeakerSpan(0, 950, 5), SpeakerSpan(950, 3_000, 9), SpeakerSpan(3_000, 5_000, 5))
    private val threeSpeakers = listOf(SpeakerSpan(0, 950, 5), SpeakerSpan(950, 2_700, 9), SpeakerSpan(2_700, 5_000, 4))

    private fun first(): List<TranscriptSegment> = assignSpeakersByWord(original(), twoSpeakers)

    @Test
    fun firstLabellingMarksContinuationPieces() {
        val l = first()
        assertEquals(listOf("Yes.", "I think so.", "Okay then.", "Right."), l.map { it.text })
        assertEquals(listOf(false, true, false, false), l.map { it.speakerSplit })
    }

    @Test
    fun mergeBackRestoresTheOriginalSegments() {
        val merged = SpeakerRelabel.mergeSpeakerSplits(first())
        assertEquals(original().map { it.text }, merged.map { it.text })
        assertEquals(original().map { it.startTimeMs }, merged.map { it.startTimeMs })
        assertEquals(original().map { it.endTimeMs }, merged.map { it.endTimeMs })
        assertEquals(original().map { it.words }, merged.map { it.words })
        assertEquals(listOf("s1", "s2", "s3"), merged.map { it.id }) // s2/s3 never split, ids stable
        assertTrue(merged.none { it.speakerSplit })
    }

    @Test
    fun twoToThreeSpeakers() {
        val out = SpeakerRelabel.rebuild(first(), threeSpeakers, 3)
        assertEquals(listOf("Yes.", "I think so.", "Okay then.", "Right."), out.map { it.text })
        assertEquals(listOf(0, 1, 2, 2), out.map { it.speaker })
        assertEquals(listOf(0, 1, 2, 3), out.map { it.segmentIndex })
        assertEquals(3, SpeakerRelabel.speakerCount(out))
        assertTrue(SpeakerRelabel.textPreserved(first(), out))
        assertEquals("s1", out[0].id)
    }

    @Test
    fun threeToOneRemovesLabelsAndMergesSplits() {
        val three = SpeakerRelabel.rebuild(first(), threeSpeakers, 3)
        val out = SpeakerRelabel.rebuild(three, emptyList(), 1)
        assertEquals(original().map { it.text }, out.map { it.text })
        assertTrue(out.all { it.speaker == -1 && !it.speakerSplit })
        assertEquals(listOf(0, 1, 2), out.map { it.segmentIndex })
        assertTrue(SpeakerRelabel.textPreserved(three, out))
    }

    @Test
    fun singleVoiceFoundByDiarizerAlsoRemovesLabels() {
        val out = SpeakerRelabel.rebuild(first(), listOf(SpeakerSpan(0, 5_000, 0)), null)
        assertTrue(out.all { it.speaker == -1 })
        assertEquals(3, out.size)
    }

    @Test
    fun twoToTwoWithDifferentBoundariesDoesNotDoubleSplit() {
        // The B turn now starts a sentence later: "Yes. I think so." is all A.
        val moved = listOf(SpeakerSpan(0, 2_900, 1), SpeakerSpan(2_900, 3_800, 2), SpeakerSpan(3_800, 5_000, 1))
        val out = SpeakerRelabel.rebuild(first(), moved, 2)
        assertEquals(listOf("Yes. I think so.", "Okay then.", "Right."), out.map { it.text })
        assertEquals(listOf(0, 1, 0), out.map { it.speaker })
        assertEquals(0L, out[0].startTimeMs)
        assertEquals(2_000L, out[0].endTimeMs)
        assertEquals(listOf("Yes.", "I", "think", "so."), out[0].timedWords().map { it.text })
        assertEquals(listOf(false, false, false), out.map { it.speakerSplit })
    }

    @Test
    fun relabelIsIdempotentForSameSpans() {
        val a = SpeakerRelabel.rebuild(first(), twoSpeakers, 2)
        val b = SpeakerRelabel.rebuild(a, twoSpeakers, 2)
        assertEquals(a.map { it.text to it.speaker }, b.map { it.text to it.speaker })
        // Original (first-piece) ids are stable; only continuation pieces get fresh ids.
        assertEquals(a.filter { !it.speakerSplit }.map { it.id }, b.filter { !it.speakerSplit }.map { it.id })
    }

    @Test
    fun segmentsWithoutWordsAreLabelledWhole() {
        val noWords = original().map { it.copy(words = "") }
        val out = SpeakerRelabel.rebuild(noWords, threeSpeakers, 3)
        assertEquals(3, out.size)
        assertEquals(noWords.map { it.text }, out.map { it.text })
        assertTrue(out.all { it.speaker >= 0 })
    }

    @Test
    fun mergeWithMissingWordsJoinsTextAndDropsWords() {
        val pieces = listOf(
            seg("a", 0, "Hello", 0L, end = 1_000).copy(words = ""),
            seg("b", 1, "there.", 1_000L, end = 2_000, split = true).copy(words = "")
        )
        val m = SpeakerRelabel.mergeSpeakerSplits(pieces)
        assertEquals(1, m.size)
        assertEquals("Hello there.", m[0].text)
        assertEquals("", m[0].words)
        assertEquals(2_000L, m[0].endTimeMs)
    }

    @Test
    fun emptyInput() {
        assertTrue(SpeakerRelabel.rebuild(emptyList(), threeSpeakers, 3).isEmpty())
        assertTrue(SpeakerRelabel.mergeSpeakerSplits(emptyList()).isEmpty())
        assertTrue(SpeakerRelabel.textPreserved(emptyList(), emptyList()))
    }

    @Test
    fun leadingContinuationFlagIsNotMergedIntoNothing() {
        val m = SpeakerRelabel.mergeSpeakerSplits(listOf(seg("x", 0, "Odd.", 0L, split = true)))
        assertEquals(1, m.size)
    }

    @Test
    fun textPreservedDetectsChange() {
        assertFalse(SpeakerRelabel.textPreserved(original(), original().dropLast(1)))
    }

    @Test
    fun speakerCountHelper() {
        assertNull(SpeakerCount.normalize(0))
        assertNull(SpeakerCount.normalize(9))
        assertEquals(8, SpeakerCount.normalize(8))
        assertEquals(-1, SpeakerCount.clusters(null))
        assertEquals(3, SpeakerCount.clusters(3))
    }

    // ---- replace logic against a fake DAO ------------------------------------------------------

    /** In-memory DAO; replaceSegments (the @Transaction default) runs against it. */
    private class FakeDao(var rows: MutableList<TranscriptSegment>, val failOnInsert: Boolean = false) : TranscriptSegmentDao {
        override fun getSegmentsForRecording(recordingId: String): Flow<List<TranscriptSegment>> = flowOf(rows.toList())
        override suspend fun getSegmentsForRecordingSync(recordingId: String) = rows.sortedBy { it.segmentIndex }
        override suspend fun insertSegment(segment: TranscriptSegment) { rows.removeAll { it.id == segment.id }; rows.add(segment) }
        override suspend fun insertSegments(segments: List<TranscriptSegment>) {
            if (failOnInsert) throw IllegalStateException("boom")
            segments.forEach { insertSegment(it) }
        }
        override suspend fun deleteSegment(segment: TranscriptSegment) { rows.removeAll { it.id == segment.id } }
        override suspend fun deleteSegmentsForRecording(recordingId: String) { rows.removeAll { it.recordingId == recordingId } }
        override suspend fun getFullTranscriptText(recordingId: String): String? = rows.joinToString(" ") { it.text }
    }

    @Test
    fun replaceLeavesNoStaleOrDuplicateRows() = runBlocking {
        val dao = FakeDao(first().toMutableList())
        val out = SpeakerRelabel.rebuild(dao.getSegmentsForRecordingSync("r"), twoSpeakers.take(1) + SpeakerSpan(950, 5_000, 9), 2)
        dao.replaceSegments("r", out)
        val rows = dao.getSegmentsForRecordingSync("r")
        assertEquals(out.map { it.id }, rows.map { it.id })
        assertEquals(rows.size, rows.map { it.id }.toSet().size)
        assertEquals(rows.indices.toList(), rows.map { it.segmentIndex })
    }

    @Test
    fun relabelNeverHandsBackDuplicateIdsOrIndexGaps() {
        for (n in listOf(null, 1, 2, 3)) {
            val out = SpeakerRelabel.rebuild(first(), threeSpeakers, n)
            assertEquals(out.size, out.map { it.id }.toSet().size)
            assertEquals(out.indices.toList(), out.map { it.segmentIndex })
            assertTrue(SpeakerRelabel.textPreserved(first(), out))
        }
    }

    @Test
    fun regressionFirstTimeLabellingUnchangedByRelabelCode() {
        // The shared function the automatic pass uses still gives the same speakers/texts as before.
        val l = first()
        assertEquals(listOf(0, 1, 0, 0), l.map { it.speaker })
        assertEquals(listOf(0, 1, 2, 3), l.map { it.segmentIndex })
    }
}
