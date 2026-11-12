package com.securevox.app.util.funnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class LedgerSummaryTest {
    private val utc = ZoneId.of("UTC")
    private var now = 1_794_000_000_000L // 2026-11-07 UTC, any fixed instant
    private val ledger = FunnelLedger(MemoryLedgerStore()) { now }

    @Test
    fun emptyLedgerSaysSo() {
        val text = LedgerSummary.reportText(ledger.snapshot(), "1.5.5", utc)
        assertTrue(text.contains("Nothing counted yet."))
        assertTrue(text.contains("App version: 1.5.5"))
        assertFalse(text.contains("Counting since"))
    }

    @Test
    fun reportListsStepsInPlainWordsWithCountsDatesAndValues() {
        ledger.record(Step.DOWNLOAD_STARTED, FunnelModel.TINY, Resumed.NO)
        now += 3_600_000L
        ledger.record(Step.DOWNLOAD_FAILED, DlProblem.OFFLINE, FunnelModel.TINY)
        now += 3_600_000L
        ledger.record(Step.DOWNLOAD_FAILED, DlProblem.OFFLINE, FunnelModel.TINY)
        val text = LedgerSummary.reportText(ledger.snapshot(), "1.5.5", utc)
        assertTrue(text, text.contains("- Model download started: 1x ("))
        assertTrue(text, text.contains("- Model download failed: 2x (first "))
        assertTrue(text, text.contains("problem: offline 2"))
        assertTrue(text, text.contains("model: Tiny 2"))
        assertTrue(text, text.contains("Counting since: "))
    }

    @Test
    fun reportContainsNoStepCodesOrContentWords() {
        Step.entries.forEach { ledger.record(it) }
        val text = LedgerSummary.reportText(ledger.snapshot(), "1.5.5", utc)
        // Header states what is NOT inside; the body lines are English sentences, not machine codes.
        assertTrue(text.lines().first().contains("no recordings, text, titles or file names"))
        assertEquals(Step.entries.size, text.lines().count { it.startsWith("- ") })
    }

    @Test
    fun singleOccurrenceShowsOneTimeStamp() {
        ledger.record(Step.SETUP_SHOWN)
        val s = ledger.snapshot().steps.single()
        val t = LedgerSummary.whenText(s.entry, utc)
        assertFalse(t, t.contains("first"))
    }

    @Test
    fun formatTimeIsStableAndZoneAware() {
        assertEquals("1970-01-01 00:00", LedgerSummary.formatTime(0L, ZoneId.of("UTC")))
        assertEquals("1969-12-31 16:00", LedgerSummary.formatTime(0L, ZoneId.of("America/Los_Angeles")))
    }

    @Test
    fun unknownValueFallsBackToItsCode() {
        val stats = StepStats(Step.SETUP_SHOWN, StepEntry(1, 1, 1), listOf(DimTally("entry", "from_the_future", 2)))
        assertEquals("entry: from_the_future 2", LedgerSummary.dimsText(stats))
    }

    @Test
    fun problemReportBodyPutsSummaryAboveTheLogAndKeepsSmallLogsInline() {
        val body = ProblemReportText.build("Setup and usage summary\n- a: 1x\n", "LOG LINE")
        assertTrue(body.logInline)
        val t = body.text
        assertTrue(t.startsWith(ProblemReportText.INTRO))
        assertTrue(t.indexOf("Setup and usage summary") < t.indexOf("Diagnostic log:"))
        assertTrue(t.endsWith("LOG LINE"))
    }

    @Test
    fun problemReportBodyAttachesALargeLogAndStillCarriesTheSummary() {
        val body = ProblemReportText.build("SUMMARY", "x".repeat(ProblemReportText.MAX_INLINE_LOG + 1))
        assertFalse(body.logInline)
        assertTrue(body.text.contains("SUMMARY"))
        assertFalse(body.text.contains("Diagnostic log:"))
    }

    @Test
    fun problemReportBodyWithNoSummaryOrLogIsJustTheIntro() {
        val body = ProblemReportText.build("", "")
        assertFalse(body.logInline)
        assertEquals(ProblemReportText.INTRO, body.text)
    }
}
