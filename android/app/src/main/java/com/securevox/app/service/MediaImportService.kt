package com.securevox.app.service

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
 * Service for importing media files (audio and video) for transcription.
 *
 * All imported files are converted to WAV 16kHz mono 16-bit PCM,
 * matching the format expected by Whisper and TranscriptionWorker.
 */
class MediaImportService(private val context: Context) {

    companion object {
        private const val TAG = "MediaImportService"

        /** Whisper's required sample rate */
        private const val TARGET_SAMPLE_RATE = 16000

        /** Maximum import file size (2 GB) */
        private const val MAX_IMPORT_FILE_SIZE = 2L * 1024 * 1024 * 1024

        /** Timeout for MediaCodec dequeue operations in microseconds */
        private const val CODEC_TIMEOUT_US = 10_000L

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

            // Validate file size
            val fileSize = getFileSize(uri)
            if (fileSize > MAX_IMPORT_FILE_SIZE) {
                val sizeMB = fileSize / (1024 * 1024)
                return@withContext ImportResult.Error("File too large: ${sizeMB} MB. Maximum supported size is 2 GB.")
            }

            when {
                mimeType == null -> {
                    ImportResult.Error("Could not determine file type")
                }
                isAudioType(mimeType) -> {
                    importAndConvertMedia(uri, fileName)
                }
                isVideoType(mimeType) -> {
                    importAndConvertMedia(uri, fileName)
                }
                else -> {
                    ImportResult.Error("Unsupported file type: $mimeType")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            ImportResult.Error("Import failed: ${e.message}")
        }
    }

    /**
     * Import and convert any audio/video file to WAV 16kHz mono 16-bit PCM.
     * Uses MediaExtractor to find the audio track and MediaCodec to decode it.
     */
    private suspend fun importAndConvertMedia(uri: Uri, originalFileName: String): ImportResult =
        withContext(Dispatchers.IO) {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outputFileName = "import_${timestamp}.wav"
            val outputFile = File(recordingsDir, outputFileName)

            var extractor: MediaExtractor? = null
            var codec: MediaCodec? = null

            try {
                // Set up extractor
                extractor = MediaExtractor()
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    extractor.setDataSource(pfd.fileDescriptor)
                } ?: return@withContext ImportResult.Error("Could not open file")

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
                    return@withContext ImportResult.Error("No audio track found in file")
                }

                extractor.selectTrack(audioTrackIndex)

                val sourceMime = audioFormat.getString(MediaFormat.KEY_MIME) ?: "audio/mp4"
                val sourceSampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val sourceChannels = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                Log.i(TAG, "Source audio: $sourceMime, ${sourceSampleRate}Hz, ${sourceChannels}ch")

                // Configure decoder
                codec = MediaCodec.createDecoderByType(sourceMime)
                codec.configure(audioFormat, null, null, 0)
                codec.start()

                // Decode all audio to raw PCM samples
                val pcmSamples = decodeAudioToPcm(extractor, codec, sourceSampleRate, sourceChannels)
                Log.i(TAG, "Decoded ${pcmSamples.size} samples at ${TARGET_SAMPLE_RATE}Hz mono")

                // Write as WAV file
                writeWavFile(outputFile, pcmSamples, TARGET_SAMPLE_RATE)

                // Get duration
                val duration = getMediaDuration(uri)

                Log.i(TAG, "Import complete: ${outputFile.absolutePath}, ${outputFile.length()} bytes")

                ImportResult.Success(
                    audioFilePath = outputFile.absolutePath,
                    originalFileName = originalFileName,
                    duration = duration,
                    fileSize = outputFile.length()
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to convert media", e)
                outputFile.delete()
                ImportResult.Error("Failed to import media: ${e.message}")
            } finally {
                try { codec?.stop() } catch (_: Exception) {}
                try { codec?.release() } catch (_: Exception) {}
                try { extractor?.release() } catch (_: Exception) {}
            }
        }

    /**
     * Decode audio using MediaCodec, resample to 16kHz mono, return as ShortArray.
     */
    private fun decodeAudioToPcm(
        extractor: MediaExtractor,
        codec: MediaCodec,
        sourceSampleRate: Int,
        sourceChannels: Int
    ): ShortArray {
        val allSamples = mutableListOf<Short>()
        val bufferInfo = MediaCodec.BufferInfo()
        var isEos = false
        var inputDone = false

        while (!isEos) {
            // Feed input buffers
            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)

                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val presentationTimeUs = extractor.sampleTime
                        codec.queueInputBuffer(inputIndex, 0, sampleSize, presentationTimeUs, 0)
                        extractor.advance()
                    }
                }
            }

            // Drain output buffers
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
            if (outputIndex >= 0) {
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    isEos = true
                }

                val outputBuffer = codec.getOutputBuffer(outputIndex)
                if (outputBuffer != null && bufferInfo.size > 0) {
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                    // Read decoded PCM samples (16-bit)
                    val shortBuffer = outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val samples = ShortArray(shortBuffer.remaining())
                    shortBuffer.get(samples)

                    // Mix down to mono if stereo/multi-channel
                    val monoSamples = if (sourceChannels > 1) {
                        mixToMono(samples, sourceChannels)
                    } else {
                        samples
                    }

                    // Resample to target rate if needed
                    val resampled = if (sourceSampleRate != TARGET_SAMPLE_RATE) {
                        resample(monoSamples, sourceSampleRate, TARGET_SAMPLE_RATE)
                    } else {
                        monoSamples
                    }

                    for (s in resampled) {
                        allSamples.add(s)
                    }
                }

                codec.releaseOutputBuffer(outputIndex, false)
            }
        }

        return allSamples.toShortArray()
    }

    /**
     * Mix multi-channel audio down to mono by averaging channels.
     */
    private fun mixToMono(samples: ShortArray, channels: Int): ShortArray {
        val monoLength = samples.size / channels
        val mono = ShortArray(monoLength)
        for (i in 0 until monoLength) {
            var sum = 0L
            for (ch in 0 until channels) {
                sum += samples[i * channels + ch]
            }
            mono[i] = (sum / channels).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return mono
    }

    /**
     * Resample audio using linear interpolation.
     */
    private fun resample(samples: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate == toRate || samples.isEmpty()) return samples

        val ratio = fromRate.toDouble() / toRate.toDouble()
        val outputLength = (samples.size / ratio).toInt()
        val output = ShortArray(outputLength)

        for (i in 0 until outputLength) {
            val srcPos = i * ratio
            val srcIndex = srcPos.toInt()
            val fraction = srcPos - srcIndex

            val sample1 = samples[srcIndex]
            val sample2 = if (srcIndex + 1 < samples.size) samples[srcIndex + 1] else sample1

            // Linear interpolation
            val interpolated = sample1 + (fraction * (sample2 - sample1))
            output[i] = interpolated.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        return output
    }

    /**
     * Write PCM samples as a WAV file with proper RIFF header.
     */
    private fun writeWavFile(file: File, samples: ShortArray, sampleRate: Int) {
        val dataSize = samples.size * 2 // 16-bit = 2 bytes per sample

        FileOutputStream(file).use { fos ->
            // Write placeholder header
            fos.write(ByteArray(44))

            // Write PCM data
            val buffer = ByteBuffer.allocate(samples.size * 2)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            for (sample in samples) {
                buffer.putShort(sample)
            }
            fos.write(buffer.array())
        }

        // Update WAV header
        RandomAccessFile(file, "rw").use { raf ->
            val totalSize = dataSize + 36

            raf.seek(0)
            raf.writeBytes("RIFF")
            raf.writeIntLE(totalSize)
            raf.writeBytes("WAVE")
            raf.writeBytes("fmt ")
            raf.writeIntLE(16)          // Subchunk1Size
            raf.writeShortLE(1)         // AudioFormat (PCM)
            raf.writeShortLE(1)         // NumChannels (mono)
            raf.writeIntLE(sampleRate)  // SampleRate
            raf.writeIntLE(sampleRate * 2) // ByteRate (sampleRate * channels * bytesPerSample)
            raf.writeShortLE(2)         // BlockAlign (channels * bytesPerSample)
            raf.writeShortLE(16)        // BitsPerSample
            raf.writeBytes("data")
            raf.writeIntLE(dataSize)
        }
    }

    private fun RandomAccessFile.writeIntLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun RandomAccessFile.writeShortLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
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
     * Get file size from URI
     */
    private fun getFileSize(uri: Uri): Long {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        return@use cursor.getLong(sizeIndex)
                    }
                }
                0L
            } ?: 0L
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

    private fun isAudioType(mimeType: String): Boolean {
        return mimeType.startsWith("audio/") ||
                SUPPORTED_AUDIO_MIMES.any { mimeType.equals(it, ignoreCase = true) }
    }

    private fun isVideoType(mimeType: String): Boolean {
        return mimeType.startsWith("video/") ||
                SUPPORTED_VIDEO_MIMES.any { mimeType.equals(it, ignoreCase = true) }
    }
}
