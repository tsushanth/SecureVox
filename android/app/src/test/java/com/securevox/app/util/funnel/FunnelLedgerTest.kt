package com.securevox.app.util.funnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FunnelLedgerTest {

    private var now = 1_000L
    private val store = MemoryLedgerStore()
    private fun ledger(s: LedgerStore = store) = FunnelLedger(s) { now }

    @Test
    fun countsAndFirstAndLastTimes() {
        val l = ledger()
        now = 1_000L; l.record(Step.SETUP_SHOWN)
        now = 5_000L; l.record(Step.SETUP_SHOWN)
        now = 9_000L; l.record(Step.SETUP_SHOWN)
        assertEquals(StepEntry(3, 1_000L, 9_000L), l.entry(Step.SETUP_SHOWN))
        assertNull(l.entry(Step.DOWNLOAD_STARTED))
        assertTrue(l.has(Step.SETUP_SHOWN))
        assertFalse(l.has(Step.DOWNLOAD_STARTED))
    }

    @Test
    fun valuesAreCountedPerStepWithoutDoubleCountingTheStep() {
        val l = ledger()
        l.record(Step.TRANSCRIPTION_STARTED, FunnelModel.TINY, LangBucket.EN)
        l.record(Step.TRANSCRIPTION_STARTED, FunnelModel.TINY, LangBucket.NON_EN_POOR)
        assertEquals(2, l.entry(Step.TRANSCRIPTION_STARTED)!!.count)
        assertEquals(2, l.dimCount(Step.TRANSCRIPTION_STARTED, FunnelModel.TINY))
        assertEquals(1, l.dimCount(Step.TRANSCRIPTION_STARTED, LangBucket.EN))
        assertEquals(1, l.dimCount(Step.TRANSCRIPTION_STARTED, LangBucket.NON_EN_POOR))
        assertEquals(0, l.dimCount(Step.TRANSCRIPTION_STARTED, FunnelModel.BASE))
        // The same value under another step is a separate tally.
        assertEquals(0, l.dimCount(Step.DOWNLOAD_STARTED, FunnelModel.TINY))
    }

    @Test
    fun survivesRestartBecauseItLivesInTheStore() {
        ledger().record(Step.FIRST_OPEN, OpenSource.LAUNCHER)
        val again = ledger()
        assertTrue(again.has(Step.FIRST_OPEN))
        assertEquals(1, again.dimCount(Step.FIRST_OPEN, OpenSource.LAUNCHER))
        assertEquals(1_000L, again.snapshot().sinceMs)
    }

    @Test
    fun snapshotIsInStepOrderAndSkipsStepsThatNeverHappened() {
        val l = ledger()
        l.record(Step.TRANSCRIPT_VIEWED)
        l.record(Step.SETUP_SHOWN)
        l.record(Step.DOWNLOAD_COMPLETED)
        assertEquals(
            listOf(Step.SETUP_SHOWN, Step.DOWNLOAD_COMPLETED, Step.TRANSCRIPT_VIEWED),
            l.snapshot().steps.map { it.step }
        )
    }

    @Test
    fun resetForgetsEverythingIncludingFirstTimeFlags() {
        val l = ledger()
        l.record(Step.FIRST_INPUT, InputVia.RECORD)
        l.reset()
        assertFalse(l.has(Step.FIRST_INPUT))
        assertTrue(l.snapshot().isEmpty)
        assertNull(l.snapshot().sinceMs)
        assertTrue(store.readAll().isEmpty())
        // And it counts again from scratch afterwards, also across a restart.
        now = 7_000L
        l.record(Step.FIRST_INPUT)
        assertEquals(StepEntry(1, 7_000L, 7_000L), ledger().entry(Step.FIRST_INPUT))
    }

    @Test
    fun corruptStoredValuesAreIgnoredNotFatal() {
        store.write(mapOf("s|setup_shown" to "garbage", "s|download_started" to "0,1,2", "d|setup_shown|entry|first_run" to "x", "s|unknown_step" to "1,1,1"))
        val l = ledger()
        assertFalse(l.has(Step.SETUP_SHOWN))
        assertFalse(l.has(Step.DOWNLOAD_STARTED))
        assertTrue(l.snapshot().isEmpty)
        l.record(Step.SETUP_SHOWN, SetupEntry.FIRST_RUN) // still works on top of the junk
        assertEquals(1, l.entry(Step.SETUP_SHOWN)!!.count)
    }

    @Test
    fun countsStopAtTheCap() {
        store.write(mapOf("s|setup_shown" to "${FunnelLedger.MAX_COUNT},1,1"))
        val l = ledger()
        l.record(Step.SETUP_SHOWN)
        assertEquals(FunnelLedger.MAX_COUNT, l.entry(Step.SETUP_SHOWN)!!.count)
    }

    @Test
    fun theLedgerOnlyEverHoldsStepAndValueCodes() {
        val l = ledger()
        Step.entries.forEach { l.record(it, *Dims.all.take(3).toTypedArray()) }
        val allowedSteps = Step.entries.map { it.code }.toSet()
        val allowedDims = Dims.all.map { "${it.group}|${it.code}" }.toSet()
        for (key in store.readAll().keys) {
            val parts = key.split('|')
            when (parts[0]) {
                "s" -> assertTrue(key, parts[1] in allowedSteps)
                "d" -> { assertTrue(key, parts[1] in allowedSteps); assertTrue(key, "${parts[2]}|${parts[3]}" in allowedDims) }
                "meta" -> assertEquals("since", parts[1])
                else -> throw AssertionError("unexpected key $key")
            }
        }
    }

    @Test
    fun stepCodesAndValueCodesAreUnique() {
        assertEquals(Step.entries.size, Step.entries.map { it.code }.toSet().size)
        assertEquals(Dims.all.size, Dims.all.map { it.group to it.code }.toSet().size)
        Dims.all.forEach { assertNotNull(Dims.lookup(it.group, it.code)) }
        Step.entries.forEach { assertEquals(it, Step.fromCode(it.code)) }
    }

    @Test
    fun noWordsOrCodesCarryContentLookingText() {
        // Codes are lowercase tokens; a stray free-text label would show up here.
        val token = Regex("[a-z0-9_]+")
        Step.entries.forEach { assertTrue(it.code, token.matches(it.code)) }
        Dims.all.forEach { assertTrue("${it.group}/${it.code}", token.matches(it.code) && token.matches(it.group)) }
    }

    @Test
    fun localFunnelSwallowsStoreFailures() {
        val broken = object : LedgerStore {
            override fun readAll(): Map<String, String> = throw IllegalStateException("disk")
            override fun write(updates: Map<String, String>) = throw IllegalStateException("disk")
            override fun clear() = Unit
        }
        val f = LocalFunnel(FunnelLedger(broken) { 1L })
        f.step(Step.SETUP_SHOWN, SetupEntry.FIRST_RUN) // must not throw
        assertFalse(f.hasSeen(Step.SETUP_SHOWN))
        assertNull(f.entry(Step.SETUP_SHOWN))
        assertEquals(0, f.dimCount(Step.SETUP_SHOWN, SetupEntry.FIRST_RUN))
    }

    @Test
    fun noopFunnelRecordsNothing() {
        NoopFunnel.step(Step.SETUP_SHOWN, SetupEntry.FIRST_RUN)
        assertFalse(NoopFunnel.hasSeen(Step.SETUP_SHOWN))
    }

    @Test
    fun holderFallsBackToNoopUntilInstalled() {
        FunnelHolder.reset()
        assertTrue(funnel() === NoopFunnel)
        FunnelHolder.install(ledger())
        funnel().step(Step.SETUP_SHOWN)
        assertTrue(FunnelHolder.ledger!!.has(Step.SETUP_SHOWN))
        FunnelHolder.reset()
        assertTrue(funnel() === NoopFunnel)
    }
}
