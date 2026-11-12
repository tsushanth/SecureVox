package com.securevox.app.util.funnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.ZoneId

class RatingSeamTest {
    private var now = 1_794_000_000_000L
    private val ledger = FunnelLedger(MemoryLedgerStore()) { now }
    private val seam = RatingFunnelAdapter(LocalFunnel(ledger), { now }, ZoneId.of("UTC"))

    @Test
    fun mapsEachEventToItsStep() {
        seam.onEvent(RatingFunnelEvent.Eligible)
        seam.onEvent(RatingFunnelEvent.AskAttempted)
        seam.onEvent(RatingFunnelEvent.FlowRequestFailed)
        seam.onEvent(RatingFunnelEvent.FlowResult(likelyShown = true))
        seam.onEvent(RatingFunnelEvent.FlowResult(likelyShown = false))
        assertEquals(1, ledger.entry(Step.RATING_ELIGIBLE)!!.count)
        assertEquals(1, ledger.entry(Step.RATING_ASK)!!.count)
        assertEquals(1, ledger.entry(Step.RATING_FLOW_FAILED)!!.count)
        assertEquals(2, ledger.entry(Step.RATING_FLOW_RESULT)!!.count)
        assertEquals(1, ledger.dimCount(Step.RATING_FLOW_RESULT, RatingShown.SHOWN))
        assertEquals(1, ledger.dimCount(Step.RATING_FLOW_RESULT, RatingShown.NOT_SHOWN))
    }

    @Test
    fun blockedIsThrottledToOnePerReasonPerDay() {
        repeat(5) { seam.onEvent(RatingFunnelEvent.Blocked(RatingReason.TOO_EARLY)) }
        seam.onEvent(RatingFunnelEvent.Blocked(RatingReason.ASK_LIMIT))
        assertEquals(2, ledger.entry(Step.RATING_BLOCKED)!!.count)
        now += 86_400_000L
        seam.onEvent(RatingFunnelEvent.Blocked(RatingReason.TOO_EARLY))
        assertEquals(3, ledger.entry(Step.RATING_BLOCKED)!!.count)
        assertEquals(2, ledger.dimCount(Step.RATING_BLOCKED, RatingReason.TOO_EARLY))
    }

    @Test
    fun nothingInTheAppFeedsTheSeamYet() {
        assertFalse(ledger.has(Step.RATING_ELIGIBLE))
    }
}
