package com.securevox.app.speakers

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** One downloadable model file, pinned by size and SHA-256 because native code parses it. */
data class ModelFile(val url: String, val fileName: String, val sizeBytes: Long, val sha256: String)

/** A model published as a .tar.bz2: the archive is pinned, and so are the files taken out of it. */
data class ArchiveModel(val url: String, val sizeBytes: Long, val sha256: String, val files: List<ModelFile>)

/** Speaker-label models: pyannote segmentation (who is talking) and CAM++ (whose voice is it). */
object SpeakerModels {
    private const val HF = "https://huggingface.co/csukuangfj"

    const val DIARIZATION_DIR = "speaker-diarization"
    val DIARIZATION = listOf(
        ModelFile(
            "$HF/sherpa-onnx-pyannote-segmentation-3-0/resolve/main/model.onnx",
            "segmentation.onnx", 5_992_913, "220ad67ca923bef2fa91f2390c786097bf305bceb5e261d4af67b38e938e1079"
        ),
        // NeMo TitaNet-small. On real-speaker test conversations ClearVoice found the right number
        // of speakers 16/16 times at threshold 0.8-0.9, where CAM++ got 1/16 at its best.
        ModelFile(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/nemo_en_titanet_small.onnx",
            "embedding.onnx", 40_257_283, "ad4a1802485d8b34c722d2a9d04249662f2ece5d28a7a039063ca22f515a789e"
        )
    )
}

/** Thrown when a download is cancelled by the user. */
class DownloadCancelledException : RuntimeException("Download cancelled")

object ModelDownloader {
    fun isReady(dir: File, files: List<ModelFile>): Boolean =
        files.all { File(dir, it.fileName).let { f -> f.exists() && f.length() == it.sizeBytes } }

    /**
     * Downloads any missing file into [dir], checking size and SHA-256 before it is moved into
     * place, so a truncated or tampered download is never loaded. [onProgress] gets 0..1 across
     * all files. Throws on any failure, and [DownloadCancelledException] when [isCancelled] turns true.
     */
    fun ensure(dir: File, files: List<ModelFile>, isCancelled: () -> Boolean = { false }, onProgress: (Float) -> Unit) {
        dir.mkdirs()
        val total = files.sumOf { it.sizeBytes }.toFloat()
        var done = 0L
        for (file in files) {
            val target = File(dir, file.fileName)
            if (!(target.exists() && target.length() == file.sizeBytes)) {
                val base = done
                downloadVerified(file.url, target, file.sizeBytes, file.sha256, isCancelled) { onProgress((base + it) / total) }
            }
            done += file.sizeBytes
            onProgress(done / total)
        }
    }

    /**
     * Downloads a .tar.bz2 (verified against its pinned size and SHA-256), takes out only the files in
     * [archive], verifies each, and deletes the archive. Progress is 0..0.9 for the download, the rest extraction.
     */
    fun ensureArchive(dir: File, archive: ArchiveModel, isCancelled: () -> Boolean = { false }, onProgress: (Float) -> Unit) {
        if (isReady(dir, archive.files)) return
        dir.mkdirs()
        val tmp = File(dir, "download.tar.bz2")
        try {
            downloadVerified(archive.url, tmp, archive.sizeBytes, archive.sha256, isCancelled) {
                onProgress(0.9f * it / archive.sizeBytes)
            }
            extractModelFiles(tmp, archive.files, dir)
            onProgress(1f)
        } finally {
            tmp.delete()
        }
    }

    /**
     * Name an archive entry may be unpacked under, or null if it is not one we want. Entries whose path climbs
     * out of the archive root ("..", absolute paths, backslashes, NUL) are an attack (zip-slip), not just
     * unwanted, so they throw. Only the last path segment is ever used, and only for names in [wanted].
     */
    fun safeEntryName(entryName: String, wanted: Set<String>): String? {
        if (entryName.contains('\u0000') || entryName.contains('\\') || entryName.startsWith("/")) {
            error("Unsafe archive entry name")
        }
        val segments = entryName.split('/')
        if (segments.any { it == ".." }) error("Unsafe archive entry name")
        val name = segments.last()
        return name.takeIf { it in wanted }
    }

    /**
     * Extracts from a .tar.bz2 only the entries whose file name matches one of [files], writing each to
     * [destDir] after checking its size and SHA-256. Throws if a wanted file is missing or fails its check, or
     * if any entry tries to escape the archive root.
     */
    fun extractModelFiles(archiveFile: File, files: List<ModelFile>, destDir: File) {
        val wanted = files.associateBy { it.fileName }
        val found = mutableSetOf<String>()
        val root = destDir.canonicalFile
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(archiveFile.inputStream()))).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val name = safeEntryName(entry.name, wanted.keys)
                if (!entry.isFile || name == null) continue
                val spec = wanted.getValue(name)
                if (entry.size != spec.sizeBytes) error("Archive entry $name has an unexpected size")
                val part = File(destDir, "$name.part")
                check(part.canonicalFile.parentFile == root) { "Unsafe archive entry location" }
                val digest = MessageDigest.getInstance("SHA-256")
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = tar.read(buf)
                    while (read >= 0) {
                        out.write(buf, 0, read); digest.update(buf, 0, read); read = tar.read(buf)
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (part.length() != spec.sizeBytes || actual != spec.sha256) {
                    part.delete()
                    error("Extracted $name failed its integrity check")
                }
                val target = File(destDir, name)
                target.delete()
                check(part.renameTo(target)) { "Could not save $name" }
                found += name
            }
        }
        val missing = wanted.keys - found
        if (missing.isNotEmpty()) error("Archive is missing ${missing.joinToString()}")
    }

    /** SHA-256 of [file] as lowercase hex. */
    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var read = input.read(buf)
            while (read >= 0) { digest.update(buf, 0, read); read = input.read(buf) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Streams [url] to [target] via a .part file, then checks size and SHA-256 before moving it into place;
     * a mismatch deletes the partial file and throws. Accepts any URL the JDK can open (http, https, file),
     * which lets tests run against local fixtures.
     */
    internal fun downloadVerified(
        url: String, target: File, sizeBytes: Long, sha256: String,
        isCancelled: () -> Boolean, onBytes: (Long) -> Unit
    ) {
        val part = File(target.parentFile, target.name + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        val conn = URL(url).openConnection().apply {
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        if (conn is HttpURLConnection) conn.instanceFollowRedirects = true
        try {
            if (conn is HttpURLConnection) check(conn.responseCode == 200) { "Download failed (HTTP ${conn.responseCode})" }
            conn.getInputStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = input.read(buf)
                    var got = 0L
                    while (read >= 0) {
                        if (isCancelled()) { part.delete(); throw DownloadCancelledException() }
                        out.write(buf, 0, read)
                        digest.update(buf, 0, read)
                        got += read
                        onBytes(got)
                        read = input.read(buf)
                    }
                }
            }
        } catch (e: Throwable) {
            part.delete()
            throw e
        } finally {
            (conn as? HttpURLConnection)?.disconnect()
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (part.length() != sizeBytes || actual != sha256) {
            part.delete()
            error("Downloaded model failed its integrity check")
        }
        target.delete()
        check(part.renameTo(target)) { "Could not save model file" }
    }
}
