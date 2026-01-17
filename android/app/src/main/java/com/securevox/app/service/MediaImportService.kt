package com.securevox.app.service

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*

/**
 * Result of a media import operation
 */
sealed class ImportResult {
    data class Success(
        val audioFilePath: String,
        val originalFileName: String,
        val duration: Long,
        val fileSize: Long
    ) : ImportResult()

    data class Error(val message: String) : ImportResult()
}

/**
 * Service for importing media files (audio and video) for transcription
 */
class MediaImportService(private val context: Context) {

    companion object {
        // Supported audio formats
        private val SUPPORTED_AUDIO_MIMES = setOf(
            "audio/mpeg",      // MP3
            "audio/mp4",       // M4A, AAC
            "audio/x-m4a",     // M4A
            "audio/aac",       // AAC
            "audio/wav",       // WAV
            "audio/x-wav",     // WAV
            "audio/ogg",       // OGG
            "audio/flac",      // FLAC
            "audio/amr",       // AMR
            "audio/3gpp",      // 3GPP
            "audio/*"          // Generic audio
        )

        // Supported video formats (will extract audio)
        private val SUPPORTED_VIDEO_MIMES = setOf(
            "video/mp4",
            "video/3gpp",
            "video/webm",
            "video/x-matroska",
            "video/quicktime",
            "video/*"
        )

        /**
         * Get MIME type filter for file picker
         */
        fun getSupportedMimeTypes(): Array<String> = arrayOf(
            "audio/*",
            "video/*"
        )

        @Volatile
        private var instance: MediaImportService? = null

        fun getInstance(context: Context): MediaImportService {
            return instance ?: synchronized(this) {
                instance ?: MediaImportService(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val recordingsDir: File by lazy {
        File(context.filesDir, "recordings").also { it.mkdirs() }
    }

    /**
     * Import a media file from URI
     *
     * @param uri Content URI of the media file
     * @return ImportResult indicating success or failure
     */
    suspend fun importMedia(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        try {
            val mimeType = context.contentResolver.getType(uri)
            val fileName = getFileName(uri) ?: "imported_media"

            when {
                mimeType == null -> {
                    ImportResult.Error("Could not determine file type")
                }
                isAudioType(mimeType) -> {
                    importAudioFile(uri, fileName)
                }
                isVideoType(mimeType) -> {
                    extractAudioFromVideo(uri, fileName)
                }
                else -> {
                    ImportResult.Error("Unsupported file type: $mimeType")
                }
            }
        } catch (e: Exception) {
            ImportResult.Error("Import failed: ${e.message}")
        }
    }

    /**
     * Import an audio file directly
     */
    private suspend fun importAudioFile(uri: Uri, originalFileName: String): ImportResult =
        withContext(Dispatchers.IO) {
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val extension = getExtensionFromFileName(originalFileName) ?: "m4a"
                val outputFileName = "import_${timestamp}.$extension"
                val outputFile = File(recordingsDir, outputFileName)

                // Copy file
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(outputFile).use { output ->
                        input.copyTo(output)
                    }
                } ?: return@withContext ImportResult.Error("Could not open file")

                // Get duration
                val duration = getMediaDuration(uri)

                ImportResult.Success(
                    audioFilePath = outputFile.absolutePath,
                    originalFileName = originalFileName,
                    duration = duration,
                    fileSize = outputFile.length()
                )
            } catch (e: Exception) {
                ImportResult.Error("Failed to import audio: ${e.message}")
            }
        }

    /**
     * Extract audio track from a video file
     */
    private suspend fun extractAudioFromVideo(uri: Uri, originalFileName: String): ImportResult =
        withContext(Dispatchers.IO) {
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val outputFileName = "import_${timestamp}.m4a"
                val outputFile = File(recordingsDir, outputFileName)

                // Create media extractor
                val extractor = MediaExtractor()
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    extractor.setDataSource(pfd.fileDescriptor)
                } ?: return@withContext ImportResult.Error("Could not open video file")

                // Find audio track
                var audioTrackIndex = -1
                var audioFormat: MediaFormat? = null

                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME)
                    if (mime?.startsWith("audio/") == true) {
                        audioTrackIndex = i
                        audioFormat = format
                        break
                    }
                }

                if (audioTrackIndex == -1 || audioFormat == null) {
                    extractor.release()
                    return@withContext ImportResult.Error("No audio track found in video")
                }

                // Select audio track
                extractor.selectTrack(audioTrackIndex)

                // Create muxer for output
                val muxer = MediaMuxer(
                    outputFile.absolutePath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )

                val outputTrackIndex = muxer.addTrack(audioFormat)
                muxer.start()

                // Extract and write audio
                val bufferSize = audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024)
                val buffer = ByteBuffer.allocate(bufferSize)
                val bufferInfo = android.media.MediaCodec.BufferInfo()

                while (true) {
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break

                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = extractor.sampleTime
                    bufferInfo.flags = extractor.sampleFlags

                    muxer.writeSampleData(outputTrackIndex, buffer, bufferInfo)
                    extractor.advance()
                }

                // Cleanup
                muxer.stop()
                muxer.release()
                extractor.release()

                // Get duration
                val duration = getMediaDuration(Uri.fromFile(outputFile))

                ImportResult.Success(
                    audioFilePath = outputFile.absolutePath,
                    originalFileName = originalFileName,
                    duration = duration,
                    fileSize = outputFile.length()
                )
            } catch (e: Exception) {
                ImportResult.Error("Failed to extract audio from video: ${e.message}")
            }
        }

    /**
     * Get the duration of a media file in milliseconds
     */
    private fun getMediaDuration(uri: Uri): Long {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            durationStr?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Get the file name from a content URI
     */
    private fun getFileName(uri: Uri): String? {
        var fileName: String? = null

        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        fileName = cursor.getString(nameIndex)
                    }
                }
            }
        }

        if (fileName == null) {
            fileName = uri.lastPathSegment
        }

        return fileName
    }

    /**
     * Get file extension from file name
     */
    private fun getExtensionFromFileName(fileName: String): String? {
        val lastDot = fileName.lastIndexOf('.')
        return if (lastDot >= 0 && lastDot < fileName.length - 1) {
            fileName.substring(lastDot + 1).lowercase()
        } else {
            null
        }
    }

    private fun isAudioType(mimeType: String): Boolean {
        return mimeType.startsWith("audio/") ||
                SUPPORTED_AUDIO_MIMES.any { mimeType.equals(it, ignoreCase = true) }
    }

    private fun isVideoType(mimeType: String): Boolean {
        return mimeType.startsWith("video/") ||
                SUPPORTED_VIDEO_MIMES.any { mimeType.equals(it, ignoreCase = true) }
    }
}
