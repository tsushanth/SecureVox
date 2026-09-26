package com.securevox.app.whisper

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Verifies downloaded Whisper models against pinned size and SHA-256 digests.
 *
 * A model is only treated as installed once its digest matches. Hashing a model
 * is expensive (hundreds of MB), so a successful verification is recorded in a
 * sidecar marker file. Callers on hot paths should use [isInstalled] and let
 * [verifyInBackground] do the one-time full hash after an app upgrade, rather
 * than hashing on every check.
 */
object ModelIntegrity {

    private const val TAG = "ModelIntegrity"
    private const val BUFFER_SIZE = 64 * 1024

    private const val MARKER_SUFFIX = ".verified"

    /**
     * Cheap check: does the model file exist at the expected size and carry a
     * marker recording a previously verified digest?
     *
     * Returns true for a file that is the right size but has never been hashed
     * (for example a model installed by an older app version). Those files are
     * reported as installed here and validated by [verifyInBackground].
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
        val marker = markerFile(file)
        if (!marker.exists()) return true
        return marker.readText().trim() == expectedSha256
    }

    /**
     * Fully validate a model file, hashing it. Safe to call off the main thread.
     *
     * On success the digest is written to the sidecar marker. On failure any
     * marker is removed so the model is not trusted again.
     */
    fun verify(file: File, model: WhisperModel): Boolean {
        return verifyAgainst(file, model.sizeBytes, model.sha256)
    }

    internal fun verifyAgainst(file: File, expectedSize: Long, expectedSha256: String): Boolean {
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

        writeMarker(file, expectedSha256)
        Log.i(TAG, "Verified ${file.name}")
        return true
    }

    /**
     * Verify a model that was installed before markers existed, without
     * blocking the caller. If verification fails the model file is deleted so
     * the UI can prompt for a fresh download instead of failing at load time.
     */
    fun verifyInBackground(
        file: File,
        model: WhisperModel,
        onResult: (Boolean) -> Unit
    ) {
        if (!file.exists()) {
            onResult(false)
            return
        }
        if (markerFile(file).exists()) {
            onResult(isInstalled(file, model))
            return
        }

        Thread({
            val ok = verify(file, model)
            if (!ok) {
                Log.w(TAG, "Discarding corrupt model ${model.fileName}")
                file.delete()
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

    private fun writeMarker(file: File, sha256: String) {
        try {
            markerFile(file).writeText(sha256)
        } catch (e: Exception) {
            Log.w(TAG, "Could not write verification marker", e)
        }
    }
}
