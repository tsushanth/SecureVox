package com.securevox.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DurationRepairTest {

    // 475,180 bytes of 16 kHz mono PCM16 is 14,848 ms of audio.
    private val fileSize = 475_180L

    @Test
    fun halvesADurationStoredAtTwiceTheRealLength() {
        assertEquals(14_848L, DurationRepair.repairedDuration(29_484L, fileSize))
    }

    @Test
    fun leavesACorrectDurationAlone() {
        assertNull(DurationRepair.repairedDuration(14_849L, fileSize))
    }

    @Test
    fun leavesImportsWithSmallMetadataDifferencesAlone() {
        assertNull(DurationRepair.repairedDuration(4_475L, 143_234L))
    }

    @Test
    fun ignoresRowsWithNoAudioOrNoDuration() {
        assertNull(DurationRepair.repairedDuration(0L, 0L))
        assertNull(DurationRepair.repairedDuration(5_000L, 0L))
        assertNull(DurationRepair.repairedDuration(0L, fileSize))
    }
}
