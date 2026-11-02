package com.securevox.app.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class DownloadPolicyTest {

    private fun problem(t: Throwable) = DownloadPolicy.classify(t)

    @Test
    fun noNetworkIsOffline() {
        assertEquals(DownloadProblem.OFFLINE, problem(UnknownHostException("huggingface.co")))
        assertEquals(DownloadProblem.OFFLINE, problem(ConnectException("Failed to connect")))
        assertEquals(DownloadProblem.OFFLINE, problem(DownloadOfflineException()))
    }

    @Test
    fun connectionDroppedMidDownloadIsOfflineNotACorruptFile() {
        assertEquals(DownloadProblem.OFFLINE, problem(SocketException("Connection reset")))
        assertEquals(DownloadProblem.OFFLINE, problem(SocketException("Software caused connection abort")))
    }

    @Test
    fun timeoutIsItsOwnProblem() {
        assertEquals(DownloadProblem.TIMEOUT, problem(SocketTimeoutException("timeout")))
    }

    @Test
    fun storageFullIsRecognisedFromTheSystemMessageAndFromOurOwnCheck() {
        assertEquals(DownloadProblem.STORAGE_FULL, problem(IOException("write failed: ENOSPC (No space left on device)")))
        assertEquals(DownloadProblem.STORAGE_FULL, problem(IOException("No space left on device")))
        assertEquals(DownloadProblem.STORAGE_FULL, problem(DownloadStorageException(100, 10)))
    }

    @Test
    fun causeChainIsWalked() {
        val wrapped = RuntimeException("outer", IOException("copy failed", IOException("No space left on device")))
        assertEquals(DownloadProblem.STORAGE_FULL, problem(wrapped))
        assertEquals(DownloadProblem.OFFLINE, problem(IllegalStateException("x", UnknownHostException("h"))))
    }

    @Test
    fun cyclicCauseDoesNotHang() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertEquals(DownloadProblem.OTHER, problem(a))
    }

    @Test
    fun serverIncompleteAndChecksumAreDistinct() {
        assertEquals(DownloadProblem.SERVER, problem(DownloadHttpException(503)))
        assertEquals(DownloadProblem.SERVER, problem(DownloadHttpException(404)))
        assertEquals(DownloadProblem.INCOMPLETE, problem(DownloadIncompleteException(10, 100)))
        assertEquals(DownloadProblem.CHECKSUM, problem(DownloadChecksumException()))
    }

    @Test
    fun anythingElseIsOther() {
        assertEquals(DownloadProblem.OTHER, problem(Exception("Could not move model into place")))
    }

    @Test
    fun remainingBytesAccountsForPartialAndNeverGoesNegative() {
        val size = WhisperModel.TINY.sizeBytes
        assertEquals(size, DownloadPolicy.remainingBytes(WhisperModel.TINY, 0))
        assertEquals(size - 1000, DownloadPolicy.remainingBytes(WhisperModel.TINY, 1000))
        assertEquals(0L, DownloadPolicy.remainingBytes(WhisperModel.TINY, size))
        assertEquals(0L, DownloadPolicy.remainingBytes(WhisperModel.TINY, size * 2))
        assertEquals(size, DownloadPolicy.remainingBytes(WhisperModel.TINY, -5))
    }

    @Test
    fun roomRequiresTheMarginOnTopOfTheFile() {
        val need = 100_000_000L
        assertFalse(DownloadPolicy.hasRoom(need, need))
        assertFalse(DownloadPolicy.hasRoom(need, need + DownloadPolicy.STORAGE_MARGIN_BYTES - 1))
        assertTrue(DownloadPolicy.hasRoom(need, need + DownloadPolicy.STORAGE_MARGIN_BYTES))
        assertTrue(DownloadPolicy.hasRoom(0, DownloadPolicy.STORAGE_MARGIN_BYTES))
    }

    @Test
    fun resumingNeedsLessRoomThanAFreshStart() {
        val free = 120_000_000L
        val model = WhisperModel.BASE // 148 MB
        assertFalse(DownloadPolicy.hasRoom(DownloadPolicy.remainingBytes(model, 0), free))
        assertTrue(DownloadPolicy.hasRoom(DownloadPolicy.remainingBytes(model, 100_000_000L), free))
    }
}
