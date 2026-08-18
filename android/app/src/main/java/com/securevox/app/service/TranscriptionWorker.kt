package com.securevox.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.securevox.app.R
import com.securevox.app.data.local.SecureVoxDatabase
import com.securevox.app.data.model.TranscriptSegment
import com.securevox.app.data.model.TranscriptionStatus
import com.securevox.app.data.repository.RecordingRepository
import com.securevox.app.whisper.TranscriptionSegment as WhisperSegment
import com.securevox.app.whisper.WhisperLib
import com.securevox.app.whisper.WhisperModel
import com.securevox.app.SecureVoxApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WorkManager Worker for background transcription.
 */
class TranscriptionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "TranscriptionWorker"
        const val KEY_RECORDING_ID = "recording_id"
        const val KEY_MODEL_NAME = "model_name"
        const val KEY_LANGUAGE = "language"
        const val KEY_PROGRESS = "progress"
        const val TAG_TRANSCRIPTION = "transcription"
        const val TAG_RECORDING_PREFIX = "recording:"

        private const val NOTIFICATION_CHANNEL_ID = "transcription"
        private const val NOTIFICATION_ID = 9101

        // Whisper processes 30-second windows; chunk to this size to avoid OOM on large files.
        // 30s × 16000 samples/s = 480,000 samples ≈ 1.8 MB as FloatArray.
        private const val CHUNK_SAMPLES = 30 * 16000
        // 1-second overlap between chunks so words at boundaries aren't cut off.
        private const val OVERLAP_SAMPLES = 16000

        fun createWorkRequest(
            recordingId: String,
            modelName: String = "ggml-tiny.bin",
            language: String = "en"
        ): OneTimeWorkRequest {
            val inputData = workDataOf(
                KEY_RECORDING_ID to recordingId,
                KEY_MODEL_NAME to modelName,
                KEY_LANGUAGE to language
            )

            return OneTimeWorkRequestBuilder<TranscriptionWorker>()
                .setInputData(inputData)
                .addTag(TAG_TRANSCRIPTION)
                .addTag("$TAG_RECORDING_PREFIX$recordingId")
                .build()
        }
    }

    private val database = SecureVoxDatabase.getInstance(applicationContext)
    private val repository = RecordingRepository(
        database.recordingDao(),
        database.transcriptSegmentDao()
    )

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val recordingId = inputData.getString(KEY_RECORDING_ID)
            ?: return@withContext Result.failure()
        val modelName = inputData.getString(KEY_MODEL_NAME) ?: "ggml-base.bin"
        val language = inputData.getString(KEY_LANGUAGE) ?: "en"

        Log.i(TAG, "Starting transcription for recording: $recordingId")

        // Confirmed root cause of "won't complete a transcript for meetings": this worker ran as
        // a plain background CoroutineWorker, which Android's WorkManager kills after roughly 10
        // minutes of execution. On-device whisper.cpp transcription of a real meeting-length
        // recording (45-90+ min, chunked at 30s each) routinely exceeds that on a mid-range
        // device. Promoting to a foreground service removes that background time limit — long
        // recordings now run for as long as they actually take, with a visible progress
        // notification instead of silently dying partway through.
        try {
            setForeground(createForegroundInfo(0))
        } catch (e: Exception) {
            Log.w(TAG, "Could not promote to foreground service, continuing in background", e)
        }

        try {
            repository.updateTranscriptionStatus(recordingId, TranscriptionStatus.IN_PROGRESS, 0)

            val recording = repository.getRecordingById(recordingId)
                ?: return@withContext Result.failure()

            val whisperLib = WhisperLib(applicationContext)
            val modelManager = SecureVoxApp.instance.modelManager

            val whisperModel = WhisperModel.fromFileName(modelName) ?: WhisperModel.TINY

            if (!modelManager.isModelDownloaded(whisperModel)) {
                Log.e(TAG, "Model not downloaded: ${whisperModel.fileName}")
                repository.updateTranscriptionStatus(recordingId, TranscriptionStatus.FAILED, 0)
                return@withContext Result.failure()
            }

            val modelPath = modelManager.getModelPath(whisperModel)
            Log.i(TAG, "Initializing Whisper with model: $modelPath")

            val initialized = whisperLib.initialize(modelPath)
            if (!initialized) {
                Log.e(TAG, "Failed to initialize Whisper model")
                repository.updateTranscriptionStatus(recordingId, TranscriptionStatus.FAILED, 0)
                return@withContext Result.failure()
            }

            // Open audio file and get WAV metadata without loading the whole file
            val wavInfo = readWavInfo(recording.audioFilePath)
            if (wavInfo == null) {
                Log.e(TAG, "Failed to parse WAV info")
                repository.updateTranscriptionStatus(recordingId, TranscriptionStatus.FAILED, 0)
                whisperLib.release()
                return@withContext Result.failure()
            }

            val totalAudioSamples = wavInfo.totalMonoSamples
            val totalChunks = (((totalAudioSamples + CHUNK_SAMPLES - 1) / CHUNK_SAMPLES).coerceAtLeast(1)).toInt()
            Log.i(TAG, "Audio: ${totalAudioSamples} samples, processing in $totalChunks chunks")

            val allSegments = mutableListOf<TranscriptSegment>()
            var segmentIndex = 0

            repository.deleteSegmentsForRecording(recordingId)

            for (chunkIdx in 0 until totalChunks) {
                val chunkStartSample = (chunkIdx * CHUNK_SAMPLES - if (chunkIdx > 0) OVERLAP_SAMPLES else 0).coerceAtLeast(0)
                val chunkEndSample = minOf(chunkStartSample + CHUNK_SAMPLES + OVERLAP_SAMPLES, totalAudioSamples.toInt())

                val chunkData = loadAudioChunk(wavInfo, chunkStartSample, chunkEndSample)
                if (chunkData == null) {
                    Log.e(TAG, "Failed to load chunk $chunkIdx")
                    continue
                }

                val chunkOffsetMs = (chunkStartSample.toLong() * 1000L) / 16000L

                val chunkSegments = whisperLib.transcribe(
                    audioData = chunkData,
                    language = language,
                    onProgress = { chunkProgress ->
                        val overall = ((chunkIdx * 100 + chunkProgress) / totalChunks).coerceIn(0, 99)
                        setProgressAsync(workDataOf(KEY_PROGRESS to overall))
                    }
                )

                // Adjust timestamps by chunk offset and filter out overlap duplicates
                val overlapMs = if (chunkIdx > 0) (OVERLAP_SAMPLES.toLong() * 1000L / 16000L) else 0L
                val newSegments = chunkSegments
                    .filter { it.startTimeMs >= overlapMs } // skip the overlap region from previous chunk
                    .map { seg ->
                        TranscriptSegment(
                            recordingId = recordingId,
                            text = seg.text,
                            startTimeMs = seg.startTimeMs - overlapMs + chunkOffsetMs,
                            endTimeMs = seg.endTimeMs - overlapMs + chunkOffsetMs,
                            segmentIndex = segmentIndex++
                        )
                    }

                allSegments.addAll(newSegments)

                // Save incrementally so partial results appear in UI during long transcriptions
                if (newSegments.isNotEmpty()) {
                    repository.saveSegments(newSegments)
                }

                val progress = ((chunkIdx + 1) * 100 / totalChunks).coerceIn(0, 99)
                setProgressAsync(workDataOf(KEY_PROGRESS to progress))
                try {
                    setForeground(createForegroundInfo(progress))
                } catch (e: Exception) {
                    Log.w(TAG, "Could not update foreground notification", e)
                }
                Log.i(TAG, "Chunk $chunkIdx/$totalChunks done, ${newSegments.size} segments, progress=$progress%")
            }

            repository.updateTranscriptionStatus(recordingId, TranscriptionStatus.COMPLETED, 100)
            whisperLib.release()
            Log.i(TAG, "Transcription completed: ${allSegments.size} total segments")

            Result.success()

        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM during transcription — device RAM too low", e)
            repository.updateTranscriptionStatus(recordingId, TranscriptionStatus.FAILED, 0)
            Result.failure()
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
            repository.updateTranscriptionStatus(recordingId, TranscriptionStatus.FAILED, 0)
            Result.failure()
        }
    }

    private fun createForegroundInfo(progress: Int): ForegroundInfo {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Transcription",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shows progress while transcribing a recording" }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Transcribing recording")
            .setContentText("$progress% complete")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setProgress(100, progress, false)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    /** Metadata from WAV header needed for chunked reading. */
    private data class WavInfo(
        val filePath: String,
        val channels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
        val audioFormat: Int,
        val dataOffsetBytes: Long,  // byte offset in file where PCM data begins
        val dataSizeBytes: Long,
        val totalMonoSamples: Long   // after channel-mixing and resampling to 16kHz
    )

    /** Parse WAV header and locate the data chunk without reading PCM data. */
    private fun readWavInfo(filePath: String): WavInfo? {
        val file = File(filePath)
        if (!file.exists()) return null

        return try {
            FileInputStream(file).use { fis ->
                val riff = ByteArray(12)
                if (fis.read(riff) < 12) return null
                if (String(riff, 0, 4) != "RIFF" || String(riff, 8, 4) != "WAVE") return null

                var audioFormat = 1
                var channels = 1
                var sampleRate = 16000
                var bitsPerSample = 16
                var dataOffsetBytes = -1L
                var dataSizeBytes = -1L
                var bytesRead = 12L

                val idBuf = ByteArray(4)
                val szBuf = ByteArray(4)

                while (true) {
                    if (fis.read(idBuf) < 4) break
                    if (fis.read(szBuf) < 4) break
                    bytesRead += 8
                    val chunkSize = ByteBuffer.wrap(szBuf).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                    val chunkId = String(idBuf, 0, 4)

                    when (chunkId) {
                        "fmt " -> {
                            val fmtData = ByteArray(chunkSize.toInt().coerceAtMost(40))
                            val n = fis.read(fmtData)
                            val buf = ByteBuffer.wrap(fmtData, 0, n).order(ByteOrder.LITTLE_ENDIAN)
                            audioFormat = buf.getShort(0).toInt() and 0xFFFF
                            channels = buf.getShort(2).toInt() and 0xFFFF
                            sampleRate = buf.getInt(4)
                            bitsPerSample = buf.getShort(14).toInt() and 0xFFFF
                            val skip = chunkSize - n
                            if (skip > 0) fis.skip(skip)
                            bytesRead += chunkSize
                        }
                        "data" -> {
                            dataOffsetBytes = bytesRead + 8 - 8 // position right after chunk header
                            // Recalculate: bytesRead already includes the 8-byte header we just read
                            dataOffsetBytes = bytesRead
                            dataSizeBytes = chunkSize
                            break
                        }
                        else -> {
                            val skip = chunkSize + (chunkSize and 1)
                            fis.skip(skip)
                            bytesRead += skip
                        }
                    }
                }

                if (dataOffsetBytes < 0 || dataSizeBytes < 0) {
                    Log.e(TAG, "No data chunk in WAV")
                    return null
                }

                if (audioFormat != 1 && audioFormat != 3) {
                    Log.e(TAG, "Unsupported WAV format $audioFormat")
                    return null
                }

                val bytesPerSample = bitsPerSample / 8
                val rawSamples = dataSizeBytes / bytesPerSample
                val monoSamples = rawSamples / channels
                // Adjust for resampling to 16kHz
                val targetSamples = if (sampleRate != 16000) {
                    (monoSamples * 16000L / sampleRate).toLong()
                } else {
                    monoSamples
                }

                Log.i(TAG, "WAV info: ${sampleRate}Hz ${channels}ch ${bitsPerSample}bit, dataOffset=$dataOffsetBytes, totalMonoSamples=$targetSamples")

                WavInfo(filePath, channels, sampleRate, bitsPerSample, audioFormat, dataOffsetBytes, dataSizeBytes, targetSamples)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading WAV info", e)
            null
        }
    }

    /**
     * Load a chunk of audio [startSample, endSample) (in 16kHz mono sample indices)
     * directly from the WAV file without loading the entire file.
     */
    private fun loadAudioChunk(info: WavInfo, startSample: Int, endSample: Int): FloatArray? {
        if (startSample >= endSample) return FloatArray(0)

        val file = File(info.filePath)
        val bytesPerRawSample = info.bitsPerSample / 8
        val bytesPerFrame = bytesPerRawSample * info.channels // one frame = all channels

        return try {
            // Convert 16kHz target sample indices back to source sample indices
            val ratio = if (info.sampleRate != 16000) info.sampleRate.toDouble() / 16000.0 else 1.0
            val srcStartSample = (startSample * ratio).toLong()
            val srcEndSample = ((endSample * ratio) + 1).toLong()
                .coerceAtMost(info.dataSizeBytes / bytesPerFrame)

            val frameCount = (srcEndSample - srcStartSample).toInt()
            val byteOffset = info.dataOffsetBytes + srcStartSample * bytesPerFrame
            val byteCount = (frameCount * bytesPerFrame).toInt()

            val rawBytes = ByteArray(byteCount)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(byteOffset)
                var pos = 0
                while (pos < byteCount) {
                    val n = raf.read(rawBytes, pos, byteCount - pos)
                    if (n < 0) break
                    pos += n
                }
            }

            val buf = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN)

            // Decode samples
            val rawSamples = ShortArray(frameCount * info.channels)
            when {
                info.audioFormat == 1 && info.bitsPerSample == 16 -> {
                    buf.asShortBuffer().get(rawSamples)
                }
                info.audioFormat == 3 && info.bitsPerSample == 32 -> {
                    val floatBuf = buf.asFloatBuffer()
                    for (i in rawSamples.indices) {
                        rawSamples[i] = (floatBuf.get() * Short.MAX_VALUE)
                            .toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    }
                }
            }

            // Mix to mono
            val mono = if (info.channels > 1) {
                ShortArray(frameCount) { i ->
                    var sum = 0L
                    for (ch in 0 until info.channels) sum += rawSamples[i * info.channels + ch]
                    (sum / info.channels).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
            } else {
                rawSamples
            }

            // Resample to 16kHz if needed
            val final16k = if (info.sampleRate != 16000) {
                val outputLen = (mono.size / ratio).toInt()
                ShortArray(outputLen) { i ->
                    val srcPos = i * ratio
                    val srcIdx = srcPos.toInt().coerceAtMost(mono.size - 1)
                    val frac = srcPos - srcIdx
                    val s1 = mono[srcIdx]
                    val s2 = if (srcIdx + 1 < mono.size) mono[srcIdx + 1] else s1
                    (s1 + frac * (s2 - s1)).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
            } else {
                mono
            }

            FloatArray(final16k.size) { i -> final16k[i].toFloat() / Short.MAX_VALUE }
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM loading audio chunk", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error loading audio chunk", e)
            null
        }
    }

}
