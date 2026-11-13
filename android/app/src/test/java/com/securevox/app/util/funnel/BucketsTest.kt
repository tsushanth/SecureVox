package com.securevox.app.util.funnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BucketsTest {
    @Test fun durationBucketBoundaries() {
        assertEquals(DurBucket.LT_5S, Buckets.dur(0))
        assertEquals(DurBucket.LT_5S, Buckets.dur(4_999))
        assertEquals(DurBucket.S5_30, Buckets.dur(5_000))
        assertEquals(DurBucket.S5_30, Buckets.dur(29_999))
        assertEquals(DurBucket.S30_2M, Buckets.dur(30_000))
        assertEquals(DurBucket.M2_10, Buckets.dur(120_000))
        assertEquals(DurBucket.M10_60, Buckets.dur(600_000))
        assertEquals(DurBucket.GT_60M, Buckets.dur(3_600_000))
    }

    @Test fun tookBucketBoundaries() {
        assertEquals(TtcBucket.LT_1M, Buckets.took(59_999))
        assertEquals(TtcBucket.M1_3, Buckets.took(60_000))
        assertEquals(TtcBucket.M3_10, Buckets.took(180_000))
        assertEquals(TtcBucket.M10_30, Buckets.took(600_000))
        assertEquals(TtcBucket.M30_60, Buckets.took(1_800_000))
        assertEquals(TtcBucket.GT_60M, Buckets.took(3_600_000))
    }

    @Test fun speedBuckets() {
        assertEquals(RtfBucket.LT_05, Buckets.speed(29_000, 60_000))
        assertEquals(RtfBucket.X05_1, Buckets.speed(30_000, 60_000))
        assertEquals(RtfBucket.X1_2, Buckets.speed(60_000, 60_000))
        assertEquals(RtfBucket.X2_5, Buckets.speed(120_000, 60_000))
        assertEquals(RtfBucket.GT_5, Buckets.speed(300_000, 60_000))
        assertNull(Buckets.speed(1_000, 0))
        assertNull(Buckets.speed(-1, 1_000))
    }

    @Test fun stageSplitsAtTenSeconds() {
        assertEquals(RunStage.EARLY, Buckets.stage(9_999))
        assertEquals(RunStage.RUNNING, Buckets.stage(10_000))
    }
}
