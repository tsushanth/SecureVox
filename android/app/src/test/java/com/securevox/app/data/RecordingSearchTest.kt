package com.securevox.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingSearchTest {

    @Test
    fun titleMatchIgnoresCaseAndSurroundingSpace() {
        assertTrue(RecordingSearch.titleMatches("Recording Nov 9, 3:41 PM", "  NOV "))
        assertFalse(RecordingSearch.titleMatches("Recording Nov 9", "dec"))
    }

    @Test
    fun blankQueryMatchesEveryTitleButNoTranscript() {
        assertTrue(RecordingSearch.titleMatches("anything", "   "))
        assertFalse(RecordingSearch.textMatches("anything", ""))
    }

    @Test
    fun transcriptMatchIgnoresCase() {
        assertTrue(RecordingSearch.textMatches("We agreed to ship the Budget on Friday", "budget"))
        assertTrue(RecordingSearch.textMatches("we agreed to ship the budget", "BUDGET"))
        assertFalse(RecordingSearch.textMatches("we agreed to ship", "budget"))
    }

    @Test
    fun nonAsciiCaseIsFoldedToo() {
        // SQLite's LIKE cannot do this, which is why non-ASCII queries are matched in Kotlin.
        assertTrue(RecordingSearch.textMatches("Привет, Москва", "привет"))
        assertTrue(RecordingSearch.textMatches("Élan vital", "élan"))
    }

    @Test
    fun onlyAsciiQueriesGoToSqlLike() {
        assertTrue(RecordingSearch.canUseSqlLike("budget 2026"))
        assertTrue(RecordingSearch.canUseSqlLike("  "))
        assertFalse(RecordingSearch.canUseSqlLike("привет"))
        assertFalse(RecordingSearch.canUseSqlLike("café"))
        assertFalse(RecordingSearch.canUseSqlLike("会議"))
    }

    @Test
    fun likeWildcardsAreSearchedLiterally() {
        assertEquals("%50\\% off%", RecordingSearch.likePattern("50% off"))
        assertEquals("%a\\_b%", RecordingSearch.likePattern(" a_b "))
        assertEquals("%c:\\\\temp%", RecordingSearch.likePattern("c:\\temp"))
        assertEquals("%plain%", RecordingSearch.likePattern("plain"))
    }

    @Test
    fun matchingIdsReturnsEachRecordingOnceAndOnlyMatches() {
        val segments = listOf(
            SegmentText("a", "Hello there"),
            SegmentText("a", "hello again"),
            SegmentText("b", "nothing here"),
            SegmentText("c", "HELLO"),
        )
        assertEquals(setOf("a", "c"), RecordingSearch.matchingIds(segments, "hello"))
        assertEquals(emptySet<String>(), RecordingSearch.matchingIds(segments, " "))
    }

    @Test
    fun newTitleIsTrimmedSingleLineAndCapped() {
        assertEquals("Team standup", RecordingSearch.normalizeTitle("  Team   standup \n"))
        assertNull(RecordingSearch.normalizeTitle("   \n "))
        val long = "x".repeat(300)
        assertEquals(RecordingSearch.MAX_TITLE_LENGTH, RecordingSearch.normalizeTitle(long)!!.length)
    }
}
