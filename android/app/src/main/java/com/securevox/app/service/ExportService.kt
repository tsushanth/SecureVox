package com.securevox.app.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.securevox.app.data.model.TranscriptSegment
import java.io.File
import com.securevox.app.util.FailureReporter

/**
 * Export format options matching iOS
 */
enum class ExportFormat(val extension: String, val mimeType: String, @androidx.annotation.StringRes val labelRes: Int) {
    TXT("txt", "text/plain", com.securevox.app.R.string.export_format_txt),
    SRT("srt", "application/x-subrip", com.securevox.app.R.string.export_format_srt),
    VTT("vtt", "text/vtt", com.securevox.app.R.string.export_format_vtt)
}

/**
 * Service for exporting transcripts to various formats.
 * Matches iOS ExportService functionality.
 */
class ExportService(private val context: Context) {

    /** "Speaker 2: text" when the transcript has speaker labels, otherwise just the text. */
    private fun labelled(segment: TranscriptSegment, corrector: com.securevox.app.data.DictionaryCorrector): String {
        val text = corrector.correct(segment.text)
        return if (segment.speaker >= 0) "${context.getString(com.securevox.app.R.string.speaker_n, segment.speaker + 1)}: $text" else text
    }


    companion object {
        private const val TAG = "ExportService"
    }

    /**
     * Generate transcript content in the specified format.
     */
    fun generateContent(
        segments: List<TranscriptSegment>,
        format: ExportFormat,
        corrector: com.securevox.app.data.DictionaryCorrector = com.securevox.app.data.DictionaryCorrector.EMPTY
    ): String {
        return when (format) {
            ExportFormat.TXT -> generateTxt(segments, corrector)
            ExportFormat.SRT -> generateSrt(segments, corrector)
            ExportFormat.VTT -> generateVtt(segments, corrector)
        }
    }

    /**
     * Export transcript to a file and return a share Intent.
     */
    fun exportToFile(
        segments: List<TranscriptSegment>,
        format: ExportFormat,
        fileName: String,
        corrector: com.securevox.app.data.DictionaryCorrector = com.securevox.app.data.DictionaryCorrector.EMPTY
    ): Intent? {
        try {
            val content = generateContent(segments, format, corrector)
            val sanitizedFileName = sanitizeFileName(fileName)
            val file = createExportFile(sanitizedFileName, format.extension)

            file.writeText(content)
            Log.i(TAG, "Exported to: ${file.absolutePath}")

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )

            return Intent(Intent.ACTION_SEND).apply {
                type = format.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Export failed: ${e.message}", e)
            FailureReporter.failure("export.file", e, context = mapOf("stage" to format.name.lowercase()))
            return null
        }
    }

    /**
     * Generate plain text format.
     * Simple text without timestamps, segments joined with double line breaks.
     */
    private fun generateTxt(segments: List<TranscriptSegment>, corrector: com.securevox.app.data.DictionaryCorrector): String {
        return segments.joinToString("\n\n") { labelled(it, corrector) }
    }

    /**
     * Generate SRT (SubRip) format.
     * Industry-standard subtitle format with timestamps.
     *
     * Format:
     * 1
     * 00:00:00,000 --> 00:00:05,000
     * First segment text
     *
     * 2
     * 00:00:05,000 --> 00:00:10,000
     * Second segment text
     */
    private fun generateSrt(segments: List<TranscriptSegment>, corrector: com.securevox.app.data.DictionaryCorrector): String {
        return segments.mapIndexed { index, segment ->
            val startTime = formatSrtTimestamp(segment.startTimeMs)
            val endTime = formatSrtTimestamp(segment.endTimeMs)
            "${index + 1}\n$startTime --> $endTime\n${labelled(segment, corrector)}"
        }.joinToString("\n\n")
    }

    /**
     * Generate WebVTT format.
     * Web-compatible subtitle format.
     *
     * Format:
     * WEBVTT
     *
     * 00:00:00.000 --> 00:00:05.000
     * First segment text
     *
     * 00:00:05.000 --> 00:00:10.000
     * Second segment text
     */
    private fun generateVtt(segments: List<TranscriptSegment>, corrector: com.securevox.app.data.DictionaryCorrector): String {
        val header = "WEBVTT\n\n"
        val body = segments.joinToString("\n\n") { segment ->
            val startTime = formatVttTimestamp(segment.startTimeMs)
            val endTime = formatVttTimestamp(segment.endTimeMs)
            "$startTime --> $endTime\n${labelled(segment, corrector)}"
        }
        return header + body
    }

    /**
     * Format timestamp for SRT format: HH:MM:SS,mmm
     */
    private fun formatSrtTimestamp(millis: Long): String {
        val hours = millis / (1000 * 60 * 60)
        val minutes = (millis / (1000 * 60)) % 60
        val seconds = (millis / 1000) % 60
        val ms = millis % 1000
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d,%03d", hours, minutes, seconds, ms)
    }

    /**
     * Format timestamp for VTT format: HH:MM:SS.mmm
     */
    private fun formatVttTimestamp(millis: Long): String {
        val hours = millis / (1000 * 60 * 60)
        val minutes = (millis / (1000 * 60)) % 60
        val seconds = (millis / 1000) % 60
        val ms = millis % 1000
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d.%03d", hours, minutes, seconds, ms)
    }

    /**
     * Sanitize filename by removing invalid characters.
     */
    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .take(100) // Limit length
    }

    /**
     * Create export file in cache directory.
     */
    private fun createExportFile(baseName: String, extension: String): File {
        val exportDir = File(context.cacheDir, "exports")
        if (!exportDir.exists()) {
            exportDir.mkdirs()
        }
        return File(exportDir, "$baseName.$extension")
    }
}
