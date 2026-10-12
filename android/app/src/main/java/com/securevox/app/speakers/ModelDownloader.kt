package com.securevox.app.speakers

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** One downloadable model file, pinned by size and SHA-256 because native code parses it. */
data class ModelFile(val url: String, val fileName: String, val sizeBytes: Long, val sha256: String)

/** Speaker-label models: pyannote segmentation (who is talking) and CAM++ (whose voice is it). */
object SpeakerModels {
    private const val HF = "https://huggingface.co/csukuangfj"

    const val DIARIZATION_DIR = "speaker-diarization"
    val DIARIZATION = listOf(
        ModelFile(
            "$HF/sherpa-onnx-pyannote-segmentation-3-0/resolve/main/model.onnx",
            "segmentation.onnx", 5_992_913, "220ad67ca923bef2fa91f2390c786097bf305bceb5e261d4af67b38e938e1079"
        ),
        ModelFile(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/wespeaker_en_voxceleb_CAM++.onnx",
            "embedding.onnx", 29_292_684, "c46fad10b5f81e1aa4a60c162714208577093655076c5450f8c469e522ec54ef"
        )
    )
}

object ModelDownloader {
    fun isReady(dir: File, files: List<ModelFile>): Boolean =
        files.all { File(dir, it.fileName).let { f -> f.exists() && f.length() == it.sizeBytes } }

    /**
     * Downloads any missing file into [dir], checking size and SHA-256 before it is moved into
     * place, so a truncated or tampered download is never loaded. [onProgress] gets 0..1 across
     * all files. Throws on any failure.
     */
    fun ensure(dir: File, files: List<ModelFile>, onProgress: (Float) -> Unit) {
        dir.mkdirs()
        val total = files.sumOf { it.sizeBytes }.toFloat()
        var done = 0L
        for (file in files) {
            val target = File(dir, file.fileName)
            if (target.exists() && target.length() == file.sizeBytes) {
                done += file.sizeBytes
                onProgress(done / total)
                continue
            }
            val part = File(dir, file.fileName + ".part")
            val digest = MessageDigest.getInstance("SHA-256")
            val conn = (URL(file.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            try {
                check(conn.responseCode == 200) { "Download failed (HTTP ${conn.responseCode})" }
                conn.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read = input.read(buf)
                        var fileDone = 0L
                        while (read >= 0) {
                            out.write(buf, 0, read)
                            digest.update(buf, 0, read)
                            fileDone += read
                            onProgress((done + fileDone) / total)
                            read = input.read(buf)
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (part.length() != file.sizeBytes || actual != file.sha256) {
                part.delete()
                error("Downloaded model failed its integrity check")
            }
            check(part.renameTo(target)) { "Could not save model file" }
            done += file.sizeBytes
            onProgress(done / total)
        }
    }
}
