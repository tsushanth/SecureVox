package com.securevox.app.whisper

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Why a model download failed, in terms the user can act on. Pure JVM so it can be unit tested.
 */
enum class DownloadProblem {
    /** No network, or the connection dropped. Retrying when back online resumes. */
    OFFLINE,
    /** The server did not answer in time. */
    TIMEOUT,
    /** The phone has no room for the rest of the file. */
    STORAGE_FULL,
    /** The server answered with an error status. */
    SERVER,
    /** The stream ended before the whole file arrived. The partial file is kept for resuming. */
    INCOMPLETE,
    /** The file arrived but did not match the pinned digest; it was discarded. */
    CHECKSUM,
    OTHER
}

/** Typed failures thrown by [ModelManager] so [DownloadPolicy.classify] never has to parse our own wording. */
class DownloadHttpException(val code: Int) : Exception("Server returned HTTP $code")
class DownloadIncompleteException(got: Long, expected: Long) :
    Exception("Incomplete download: got $got of $expected bytes")
class DownloadChecksumException : Exception("Downloaded model failed checksum verification")
class DownloadOfflineException : Exception("No internet connection")
class DownloadStorageException(needed: Long, free: Long) :
    Exception("Not enough storage: need $needed bytes, $free available")

/** What the network looks like right now. UNKNOWN means the check itself was not possible; try anyway. */
enum class NetworkState { OFFLINE, METERED, UNMETERED, UNKNOWN }

object DownloadPolicy {

    /** Headroom kept free on top of the file, so a full download does not leave the phone at 0 bytes. */
    const val STORAGE_MARGIN_BYTES = 50_000_000L

    /** Bytes still to fetch, given what a partial file already holds. Never negative. */
    fun remainingBytes(model: WhisperModel, partialBytes: Long): Long =
        (model.sizeBytes - partialBytes.coerceIn(0L, model.sizeBytes))

    /** True when [freeBytes] covers [remaining] plus the safety margin. */
    fun hasRoom(remaining: Long, freeBytes: Long): Boolean =
        freeBytes >= remaining + STORAGE_MARGIN_BYTES

    fun classify(error: Throwable): DownloadProblem {
        // Walk the cause chain (bounded, in case of a cycle) and take the first thing we recognise.
        var t: Throwable? = error
        var depth = 0
        while (t != null && depth < 8) {
            when (t) {
                is DownloadOfflineException -> return DownloadProblem.OFFLINE
                is DownloadStorageException -> return DownloadProblem.STORAGE_FULL
                is DownloadHttpException -> return DownloadProblem.SERVER
                is DownloadIncompleteException -> return DownloadProblem.INCOMPLETE
                is DownloadChecksumException -> return DownloadProblem.CHECKSUM
                is UnknownHostException, is ConnectException, is NoRouteToHostException -> return DownloadProblem.OFFLINE
                is SocketTimeoutException -> return DownloadProblem.TIMEOUT
            }
            val msg = t.message.orEmpty()
            if (msg.contains("No space left", ignoreCase = true) || msg.contains("ENOSPC")) {
                return DownloadProblem.STORAGE_FULL
            }
            if (t is SocketException) return DownloadProblem.OFFLINE // reset, abort, broken pipe: connection lost
            t = t.cause
            depth++
        }
        return DownloadProblem.OTHER
    }
}
