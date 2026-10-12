package com.securevox.app.whisper

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Verifies downloaded Whisper models against pinned size and SHA-256 digests.
 *
 * Hashing a model costs hundreds of megabytes of reads, so a successful
 * verification is recorded in a sidecar marker file stamped with the app
 * version that performed it. Callers on hot paths use [isInstalled], which is a
 * cheap size plus marker check, and [verifyInBackground] does the real hashing
 * only when the marker is missing or was written by an older app version.
 */
object ModelIntegrity {

    private const val TAG = "ModelIntegrity"
    private const val BUFFER_SIZE = 64 * 1024

    private const val MARKER_SUFFIX = ".verified"

    /**
     * Cheap check: does the model exist at the expected size and, if it carries
     * a marker, does that marker vouch for the expected digest?
     *
     * A correctly sized file with no marker is reported as installed so that
     * models installed by older app versions are not forced to re-download;
     * [verifyInBackground] validates those once.
     */
    fun isInstalled(file: File, model: WhisperModel): Boolean {
        return isInstalledAgainst(file, model.sizeBytes, model.sha256)
    }

    internal fun isInstalledAgainst(file: File, expectedSize: Long, expectedSha256: String): Boolean {
        if (!file.exists()) return false
        if (file.length() != expectedSize) {
            Log.w(TAG, "Size mismatch for ${file.name}: ${file.length()} != $expectedSize")
            return false
        }
        val marker = readMarker(file) ?: return true
        return marker.first == expectedSha256
    }

    /**
     * Whether the file must be hashed before it can be trusted: either it was
     * never hashed, or it was last hashed by a different app version.
     */
    fun needsVerification(file: File, appVersion: Long): Boolean {
        if (!file.exists()) return false
        val marker = readMarker(file) ?: return true
        return marker.second != appVersion
    }

    /**
     * Fully validate a model file, hashing it. Safe to call off the main thread.
     *
     * On success the digest is stamped with [appVersion] in the sidecar marker.
     * On failure any marker is removed so the model is not trusted again.
     */
    fun verify(file: File, model: WhisperModel, appVersion: Long): Boolean {
        return verifyAgainst(file, model.sizeBytes, model.sha256, appVersion)
    }

    internal fun verifyAgainst(
        file: File,
        expectedSize: Long,
        expectedSha256: String,
        appVersion: Long
    ): Boolean {
        if (!file.exists()) return false

        if (file.length() != expectedSize) {
            Log.w(TAG, "Size mismatch for ${file.name}: ${file.length()} != $expectedSize")
            markerFile(file).delete()
            return false
        }

        val actual = sha256(file)
        if (actual != expectedSha256) {
            Log.e(TAG, "Checksum mismatch for ${file.name}: got $actual")
            markerFile(file).delete()
            return false
        }

        writeMarker(file, expectedSha256, appVersion)
        Log.i(TAG, "Verified ${file.name}")
        return true
    }

    /**
     * Validate a model off the main thread, hashing it only when the marker is
     * absent or stale. If verification fails the model file is deleted so the UI
     * offers a fresh download instead of failing later at transcription time.
     */
    fun verifyInBackground(
        file: File,
        model: WhisperModel,
        appVersion: Long,
        onResult: (Boolean) -> Unit
    ) {
        if (!file.exists()) {
            onResult(false)
            return
        }

        if (!needsVerification(file, appVersion)) {
            onResult(isInstalled(file, model))
            return
        }

        Thread({
            val ok = verify(file, model, appVersion)
            if (!ok) {
                Log.w(TAG, "Discarding corrupt model ${model.fileName}")
                file.delete()
                ModelIntegrity.deleteMarker(file)
            }
            onResult(ok)
        }, "model-integrity-${model.name}").apply { isDaemon = true }.start()
    }

    /**
     * Compute the SHA-256 digest of a file as lowercase hex.
     */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun markerFile(file: File): File = File(file.parentFile, file.name + MARKER_SUFFIX)

    fun deleteMarker(file: File) {
        markerFile(file).delete()
    }

    /**
     * Read the marker as digest to app version, or null when absent/unreadable.
     * Markers written by earlier builds held only the digest; those are treated
     * as stale so the model is re-verified once.
     */
    private fun readMarker(file: File): Pair<String, Long>? {
        val marker = markerFile(file)
        if (!marker.exists()) return null
        val parts = try {
            marker.readText().trim().split(":")
        } catch (e: Exception) {
            Log.w(TAG, "Unreadable marker for ${file.name}", e)
            return null
        }
        val digest = parts.firstOrNull().orEmpty()
        val version = parts.getOrNull(1)?.toLongOrNull()
        if (digest.isEmpty() || version == null) {
            return digest.takeIf { it.isNotEmpty() }?.let { it to STALE_VERSION }
        }
        return digest to version
    }

    private fun writeMarker(file: File, sha256: String, appVersion: Long) {
        try {
            markerFile(file).writeText("$sha256:$appVersion")
        } catch (e: Exception) {
            Log.w(TAG, "Could not write verification marker", e)
        }
    }

    private const val STALE_VERSION = -1L
}
