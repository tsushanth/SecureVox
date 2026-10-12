package com.securevox.app.data

import com.securevox.app.data.local.SecureVoxDatabase
import com.securevox.app.data.model.TranscriptSegment
import com.securevox.app.data.model.WordCodec
import com.securevox.app.data.model.WordLayout
import com.securevox.app.data.model.WordTiming
import com.securevox.app.data.model.activeWordIndex
import com.securevox.app.data.model.timedWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentWordsTest {

    private val words = listOf(WordTiming("Hello,", 1000, 1400), WordTiming("big", 1500, 1800), WordTiming("world.", 1900, 2400))

    private fun seg(text: String, encoded: String) = TranscriptSegment(
        recordingId = "r", text = text, startTimeMs = 1000, endTimeMs = 2500, segmentIndex = 0, words = encoded
    )

    @Test
    fun oldRowsWithNoWordsFallBackToSegmentLevel() {
        assertTrue(seg("Hello, big world.", "").timedWords().isEmpty())
    }

    @Test
    fun wordsAreReturnedOnlyWhenTheySpellTheText() {
        val enc = WordCodec.encode(words)
        assertEquals(words, seg("Hello, big world.", enc).timedWords())
        assertEquals(words, seg("Hello,  big\nworld.", enc).timedWords()) // whitespace differences are fine
        assertTrue(seg("Hello, small world.", enc).timedWords().isEmpty())
        assertTrue(seg("Hello, big", enc).timedWords().isEmpty())
    }

    @Test
    fun layoutMapsCharactersBackToWords() {
        val layout = WordLayout(words)
        assertEquals("Hello, big world.", layout.text)
        assertEquals(0, layout.wordAt(0))
        assertEquals(0, layout.wordAt(5))
        assertEquals(0, layout.wordAt(6))   // the space after a word belongs to it
        assertEquals(1, layout.wordAt(7))
        assertEquals(2, layout.wordAt(100)) // past the end picks the last word
        assertEquals(7 + 3, layout.endOf(1))
    }

    @Test
    fun activeWordIsTheLastOneStartedAndHoldsThroughPauses() {
        assertEquals(-1, activeWordIndex(words, 999, 1000, 2500))   // before the segment
        assertEquals(-1, activeWordIndex(words, 2500, 1000, 2500)) // after it
        assertEquals(0, activeWordIndex(words, 1000, 1000, 2500))
        assertEquals(0, activeWordIndex(words, 1450, 1000, 2500))   // pause between words keeps the previous lit
        assertEquals(1, activeWordIndex(words, 1500, 1000, 2500))
        assertEquals(2, activeWordIndex(words, 2450, 1000, 2500))
        assertEquals(-1, activeWordIndex(emptyList(), 1200, 1000, 2500))
    }

    @Test
    fun migrationSqlAddsANonNullColumnWithEmptyDefault() {
        // Room validates the migrated table against the entity: TEXT NOT NULL DEFAULT ''.
        assertEquals(
            "ALTER TABLE transcript_segments ADD COLUMN words TEXT NOT NULL DEFAULT ''",
            SecureVoxDatabase.ADD_WORDS_COLUMN_SQL
        )
        assertEquals(3, SecureVoxDatabase.MIGRATION_3_4.startVersion)
        assertEquals(4, SecureVoxDatabase.MIGRATION_3_4.endVersion)
    }
}
