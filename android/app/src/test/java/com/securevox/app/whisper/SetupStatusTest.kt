package com.securevox.app.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupStatusTest {

    private val offlineError = DownloadState.Error(WhisperModel.TINY, "No internet connection", DownloadProblem.OFFLINE)

    @Test
    fun offlineNetworkShowsOfflineEvenWithNoError() {
        assertEquals(SetupNotice.OFFLINE, SetupStatus.notice(NetworkState.OFFLINE, null))
    }

    @Test
    fun staleOfflineErrorRecoversWhenConnectionReturns() {
        assertEquals(SetupNotice.NONE, SetupStatus.notice(NetworkState.UNMETERED, DownloadProblem.OFFLINE))
        assertEquals(SetupNotice.NONE, SetupStatus.notice(NetworkState.METERED, DownloadProblem.OFFLINE))
    }

    @Test
    fun offlineErrorStaysWhileConnectionIsStillUnconfirmed() {
        // UNKNOWN is a captive portal or a link still being validated: not proof the network is back.
        assertEquals(SetupNotice.OFFLINE, SetupStatus.notice(NetworkState.UNKNOWN, DownloadProblem.OFFLINE))
        assertEquals(SetupNotice.OFFLINE, SetupStatus.notice(NetworkState.OFFLINE, DownloadProblem.OFFLINE))
    }

    @Test
    fun otherErrorsAreNotTreatedAsOfflineAndSurviveReconnect() {
        for (p in DownloadProblem.entries.filter { it != DownloadProblem.OFFLINE }) {
            assertEquals(SetupNotice.ERROR, SetupStatus.notice(NetworkState.UNMETERED, p))
            assertEquals(SetupNotice.ERROR, SetupStatus.notice(NetworkState.UNKNOWN, p))
        }
    }

    @Test
    fun noErrorAndOnlineIsReady() {
        assertEquals(SetupNotice.NONE, SetupStatus.notice(NetworkState.UNMETERED, null))
        assertEquals(SetupNotice.NONE, SetupStatus.notice(NetworkState.UNKNOWN, null))
    }

    @Test
    fun clearsOnlyTheOfflineErrorAndOnlyWhenOnline() {
        assertTrue(SetupStatus.shouldClearOfflineError(offlineError, NetworkState.UNMETERED))
        assertTrue(SetupStatus.shouldClearOfflineError(offlineError, NetworkState.METERED))
        assertFalse(SetupStatus.shouldClearOfflineError(offlineError, NetworkState.OFFLINE))
        assertFalse(SetupStatus.shouldClearOfflineError(offlineError, NetworkState.UNKNOWN))
    }

    @Test
    fun inFlightOrFinishedOrOtherFailuresAreNeverCleared() {
        val online = NetworkState.UNMETERED
        assertFalse(
            SetupStatus.shouldClearOfflineError(
                DownloadState.Downloading(WhisperModel.TINY, 10L, WhisperModel.TINY.sizeBytes), online
            )
        )
        assertFalse(SetupStatus.shouldClearOfflineError(DownloadState.Idle, online))
        assertFalse(SetupStatus.shouldClearOfflineError(DownloadState.Completed(WhisperModel.TINY), online))
        for (p in DownloadProblem.entries.filter { it != DownloadProblem.OFFLINE }) {
            assertFalse(SetupStatus.shouldClearOfflineError(DownloadState.Error(WhisperModel.TINY, "x", p), online))
        }
    }

    @Test
    fun resumingReportsRemainingBytesNotTheFullSize() {
        val tiny = WhisperModel.TINY
        val partial = 46_691_713L
        assertEquals(31_000_000L, SetupStatus.remainingWhenResuming(tiny, partial))
        assertNull(SetupStatus.remainingWhenResuming(tiny, 0L))
        assertNull(SetupStatus.remainingWhenResuming(tiny, tiny.sizeBytes))
        assertNull(SetupStatus.remainingWhenResuming(tiny, -5L))
    }

    @Test
    fun tinyIsAlways78MbAndOthersRoundToNearest() {
        assertEquals(78, WhisperModel.TINY.sizeMB)
        assertEquals(148, WhisperModel.BASE.sizeMB)
        assertEquals(488, WhisperModel.SMALL.sizeMB)
    }
}
