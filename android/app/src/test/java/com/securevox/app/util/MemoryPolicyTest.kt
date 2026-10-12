package com.securevox.app.util

import com.securevox.app.util.MemoryPolicy.ModelAdvice
import com.securevox.app.util.MemoryPolicy.Snapshot
import com.securevox.app.util.MemoryPolicy.SpeakerSkip
import com.securevox.app.whisper.WhisperModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPolicyTest {
    private val gb = 1_000_000_000L
    private val cap = 30L * 60 * 16000

    private fun snap(
        total: Long = 8 * gb,
        avail: Long = 4 * gb,
        threshold: Long = 200_000_000L,
        low: Boolean = false,
        heap: Long = 400_000_000L
    ) = Snapshot(total, avail, threshold, low, heap)

    // ---- model picker ----

    @Test fun tinyIsAlwaysRecommended() {
        assertEquals(ModelAdvice.RECOMMENDED, MemoryPolicy.modelAdvice(WhisperModel.TINY, 1 * gb).advice)
    }

    @Test fun smallBoundaryAtFiveGb() {
        val at = MemoryPolicy.modelAdvice(WhisperModel.SMALL, MemoryPolicy.SMALL_MIN_TOTAL_BYTES)
        val below = MemoryPolicy.modelAdvice(WhisperModel.SMALL, MemoryPolicy.SMALL_MIN_TOTAL_BYTES - 1)
        assertEquals(ModelAdvice.RECOMMENDED, at.advice)
        assertFalse(at.needsConfirm)
        assertNull(at.reason)
        assertEquals(ModelAdvice.NOT_RECOMMENDED, below.advice)
        assertTrue(below.needsConfirm)
        assertNotNull(below.reason)
    }

    @Test fun smallOnSixAndFourGbPhones() {
        assertEquals(ModelAdvice.RECOMMENDED, MemoryPolicy.modelAdvice(WhisperModel.SMALL, 5_700_000_000L).advice)
        assertEquals(ModelAdvice.NOT_RECOMMENDED, MemoryPolicy.modelAdvice(WhisperModel.SMALL, 3_800_000_000L).advice)
    }

    @Test fun smallOnThreeGbOrLessLikelyFailsButIsNeverBlocked() {
        val d = MemoryPolicy.modelAdvice(WhisperModel.SMALL, 2_700_000_000L)
        assertEquals(ModelAdvice.LIKELY_TO_FAIL, d.advice)
        assertTrue(d.needsConfirm)
        assertEquals(ModelAdvice.NOT_RECOMMENDED, MemoryPolicy.modelAdvice(WhisperModel.SMALL, MemoryPolicy.SMALL_RISKY_TOTAL_BYTES).advice)
        assertEquals(ModelAdvice.LIKELY_TO_FAIL, MemoryPolicy.modelAdvice(WhisperModel.SMALL, MemoryPolicy.SMALL_RISKY_TOTAL_BYTES - 1).advice)
    }

    @Test fun baseOnlyFlaggedOnVerySmallPhones() {
        assertEquals(ModelAdvice.NOT_RECOMMENDED, MemoryPolicy.modelAdvice(WhisperModel.BASE, 1_800_000_000L).advice)
        assertEquals(ModelAdvice.RECOMMENDED, MemoryPolicy.modelAdvice(WhisperModel.BASE, MemoryPolicy.BASE_MIN_TOTAL_BYTES).advice)
        assertEquals(ModelAdvice.RECOMMENDED, MemoryPolicy.modelAdvice(WhisperModel.BASE, 3_800_000_000L).advice)
    }

    // ---- speaker labels ----

    @Test fun roomyPhoneGetsTheFullCap() {
        val s = snap()
        assertEquals(cap, MemoryPolicy.speakerMaxSamples(s, cap))
        assertTrue(MemoryPolicy.speakerDecision(s, cap, cap).allowed)
    }

    @Test fun lowMemoryFlagSkipsEvenShortAudio() {
        val d = MemoryPolicy.speakerDecision(snap(low = true), 10L * 60 * 16000, cap)
        assertFalse(d.allowed)
        assertEquals(SpeakerSkip.LOW_MEMORY, d.skip)
        assertEquals(0L, d.maxSamples)
        assertNotNull(d.reason)
    }

    @Test fun overCapIsTooLongNotMemory() {
        val d = MemoryPolicy.speakerDecision(snap(), cap + 1, cap)
        assertFalse(d.allowed)
        assertEquals(SpeakerSkip.TOO_LONG, d.skip)
    }

    @Test fun limitShrinksWithAvailableMemory() {
        // usable = 1.2e9 - 0.2e9 - 0.35e9 = 0.65e9; *0.5 / 12 = 27.08M samples (about 28 min)
        val s = snap(avail = 1_200_000_000L)
        val max = MemoryPolicy.speakerMaxSamples(s, cap)
        assertEquals(27_083_333L, max)
        assertTrue(max < cap)
        assertTrue(MemoryPolicy.speakerDecision(s, 20L * 60 * 16000, cap).allowed)
        val long = MemoryPolicy.speakerDecision(s, 29L * 60 * 16000, cap)
        assertFalse(long.allowed)
        assertEquals(SpeakerSkip.LOW_MEMORY, long.skip)
    }

    @Test fun tinyMemoryLeavesNothingToLabel() {
        // Below fixed cost plus threshold, the budget is zero.
        assertEquals(0L, MemoryPolicy.speakerMaxSamples(snap(avail = 500_000_000L), cap))
        val d = MemoryPolicy.speakerDecision(snap(avail = 500_000_000L), 5L * 16000, cap)
        assertEquals(SpeakerSkip.LOW_MEMORY, d.skip)
    }

    @Test fun budgetUnderOneMinuteIsSkipped() {
        // usable = 0.6e9-0.2e9-0.35e9 = 0.05e9 -> 2.08M samples? no: *0.5/12 = 2.08M (130 s). Shrink further.
        val s = snap(avail = 560_000_000L) // usable 10 MB -> 416k samples = 26 s
        assertTrue(MemoryPolicy.speakerMaxSamples(s, cap) < MemoryPolicy.SPEAKER_MIN_SAMPLES)
        val d = MemoryPolicy.speakerDecision(s, 10L * 16000, cap)
        assertFalse(d.allowed)
        assertEquals(SpeakerSkip.LOW_MEMORY, d.skip)
    }

    @Test fun javaHeapAlsoLimits() {
        // 64 MB heap free * 0.7 / 4 = 11.2M samples (about 11.7 min) even with plenty of RAM
        val s = snap(heap = 64_000_000L)
        assertEquals(11_200_000L, MemoryPolicy.speakerMaxSamples(s, cap))
        assertFalse(MemoryPolicy.speakerDecision(s, 15L * 60 * 16000, cap).allowed)
        assertTrue(MemoryPolicy.speakerDecision(s, 10L * 60 * 16000, cap).allowed)
    }

    @Test fun negativeHeapIsTreatedAsZero() {
        assertEquals(0L, MemoryPolicy.speakerMaxSamples(snap(heap = -5L), cap))
    }

    @Test fun exactlyAtLimitIsAllowed() {
        val s = snap(avail = 1_200_000_000L)
        val max = MemoryPolicy.speakerMaxSamples(s, cap)
        assertTrue(MemoryPolicy.speakerDecision(s, max, cap).allowed)
        assertFalse(MemoryPolicy.speakerDecision(s, max + 1, cap).allowed)
    }

    // ---- live captions ----

    @Test fun roomyPhoneUsesBase() {
        assertEquals(WhisperModel.BASE, MemoryPolicy.liveCaptionModel(snap()))
    }

    @Test fun lowMemoryFlagTurnsLiveCaptionsOff() {
        assertNull(MemoryPolicy.liveCaptionModel(snap(low = true)))
    }

    @Test fun smallRamPhoneIsTinyOnly() {
        assertEquals(WhisperModel.TINY, MemoryPolicy.liveCaptionModel(snap(total = 3_800_000_000L, avail = 2 * gb)))
    }

    @Test fun baseNeedsFreeMemoryNotJustTotal() {
        // 8 GB phone that is nearly full: free = 0.8e9 - 0.2e9 = 0.6e9, under 1e9 for Base, enough for Tiny
        assertEquals(WhisperModel.TINY, MemoryPolicy.liveCaptionModel(snap(avail = 800_000_000L)))
    }

    @Test fun baseBoundaries() {
        val ok = snap(total = MemoryPolicy.LIVE_BASE_MIN_TOTAL_BYTES, avail = 1_200_000_000L) // free exactly 1e9
        assertEquals(WhisperModel.BASE, MemoryPolicy.liveCaptionModel(ok))
        assertEquals(WhisperModel.TINY, MemoryPolicy.liveCaptionModel(ok.copy(totalBytes = ok.totalBytes - 1)))
        assertEquals(WhisperModel.TINY, MemoryPolicy.liveCaptionModel(ok.copy(availBytes = ok.availBytes - 1)))
    }

    @Test fun offWhenNotEvenTinyFits() {
        val atTiny = snap(avail = 650_000_000L) // free exactly 450 MB
        assertEquals(WhisperModel.TINY, MemoryPolicy.liveCaptionModel(atTiny))
        assertNull(MemoryPolicy.liveCaptionModel(atTiny.copy(availBytes = atTiny.availBytes - 1)))
    }
}
