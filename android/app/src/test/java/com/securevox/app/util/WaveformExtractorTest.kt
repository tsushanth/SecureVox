package com.securevox.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveformExtractorTest {

    @Test
    fun percentileIgnoresASingleLoudOutlier() {
        val bars = FloatArray(100) { 0.1f }.also { it[40] = 1f }
        // The loudest bar is 10x the rest, but it must not set the scale for all of them.
        assertEquals(0.1f, WaveformExtractor.percentile(bars, 0.95f), 1e-6f)
    }

    @Test
    fun percentileOfEmptyInputIsZero() {
        assertEquals(0f, WaveformExtractor.percentile(FloatArray(0), 0.95f), 0f)
    }

    @Test
    fun percentileOfOneValueIsThatValue() {
        assertTrue(WaveformExtractor.percentile(floatArrayOf(0.4f), 0.95f) == 0.4f)
    }
}
