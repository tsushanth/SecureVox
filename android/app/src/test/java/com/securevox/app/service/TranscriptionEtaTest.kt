package com.securevox.app.service

import com.securevox.app.service.TranscriptionEta.Estimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranscriptionEtaTest {
    private val minute = 60_000L

    @Test
    fun noRunMeansNoClaim() {
        assertEquals(Estimate.Unknown, TranscriptionEta.estimate(null, 1_000, 60 * minute))
    }

    @Test
    fun withNoMeasurementAndNoProgressNothingIsClaimed() {
        // The old code claimed "Whisper tiny ~4x real-time" for every model; with nothing measured we say nothing.
        val run = TranscriptionRun(progress = 0, startedAtMs = 0, speed = null)
        assertEquals(Estimate.Unknown, TranscriptionEta.estimate(run, 2_000, 10 * minute))
    }

    @Test
    fun measuredSpeedGivesATotalBeforeProgressIsKnown() {
        // Small ran at 2.5x on this phone last time: a 10 minute recording is about 25 minutes.
        val run = TranscriptionRun(progress = 0, startedAtMs = 0, speed = 2.5f)
        assertEquals(Estimate.Total(25), TranscriptionEta.estimate(run, 2_000, 10 * minute))
    }

    @Test
    fun speedIsPerModelSoTheTotalScalesWithIt() {
        val tiny = TranscriptionRun(0, 0, 0.3f)
        val small = TranscriptionRun(0, 0, 3.0f)
        val tinyMin = (TranscriptionEta.estimate(tiny, 0, 20 * minute) as Estimate.Total).minutes
        val smallMin = (TranscriptionEta.estimate(small, 0, 20 * minute) as Estimate.Total).minutes
        assertEquals(6, tinyMin)
        assertEquals(60, smallMin)
    }

    @Test
    fun extrapolatesFromTheRealPaceOfThisRun() {
        // 25% done after 5 minutes: 15 minutes left.
        val run = TranscriptionRun(progress = 25, startedAtMs = 0, speed = 99f)
        assertEquals(Estimate.Left(15), TranscriptionEta.estimate(run, 5 * minute, 10 * minute))
    }

    @Test
    fun measuredPaceWinsOverTheRememberedSpeed() {
        val withSpeed = TranscriptionRun(50, 0, 100f)
        val without = TranscriptionRun(50, 0, null)
        assertEquals(
            TranscriptionEta.estimate(without, 4 * minute, 8 * minute),
            TranscriptionEta.estimate(withSpeed, 4 * minute, 8 * minute)
        )
    }

    @Test
    fun theEstimateDependsOnTheStartTimeNotOnWhenTheRowWasComposed() {
        // Same run state, evaluated at two later moments: only the clock moves, so "left" can only shrink.
        val run = TranscriptionRun(progress = 40, startedAtMs = 1_000_000, speed = null)
        val a = TranscriptionEta.estimate(run, 1_000_000 + 4 * minute, 5 * minute) as Estimate.Left
        val b = TranscriptionEta.estimate(run, 1_000_000 + 4 * minute, 5 * minute) as Estimate.Left
        assertEquals(a, b)
        assertEquals(6, a.minutes)
    }

    @Test
    fun tooEarlyToExtrapolateFallsBackToTheRememberedSpeed() {
        val early = TranscriptionRun(progress = 2, startedAtMs = 0, speed = 1.0f)
        assertEquals(Estimate.Total(10), TranscriptionEta.estimate(early, 3_000, 10 * minute))
        val justStarted = TranscriptionRun(progress = 30, startedAtMs = 0, speed = null)
        assertEquals(Estimate.Unknown, TranscriptionEta.estimate(justStarted, 1_000, 10 * minute))
    }

    @Test
    fun neverShowsZeroMinutesAndRoundsUp() {
        val nearlyDone = TranscriptionRun(progress = 99, startedAtMs = 0, speed = null)
        assertEquals(Estimate.Left(1), TranscriptionEta.estimate(nearlyDone, 10 * minute, 10 * minute))
        val oneAndABit = TranscriptionRun(progress = 50, startedAtMs = 0, speed = null)
        assertEquals(Estimate.Left(2), TranscriptionEta.estimate(oneAndABit, 90_000, minute))
    }

    @Test
    fun aClockMovingBackwardsDoesNotProduceANegativeEstimate() {
        val run = TranscriptionRun(progress = 50, startedAtMs = 10 * minute, speed = null)
        assertEquals(Estimate.Unknown, TranscriptionEta.estimate(run, 0, minute))
    }

    @Test
    fun measuredSpeedIgnoresClipsTooShortToTell() {
        assertNull(TranscriptionEta.measuredSpeed(processingMs = 9_000, audioMs = 5_000))
        assertNull(TranscriptionEta.measuredSpeed(processingMs = 0, audioMs = 60_000))
        assertEquals(0.5f, TranscriptionEta.measuredSpeed(processingMs = 30_000, audioMs = 60_000)!!, 0.0001f)
    }
}
