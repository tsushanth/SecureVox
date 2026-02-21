package com.securevox.app.service

import android.content.Context
import android.util.Log
import androidx.work.*
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
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
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

        try {
            // Update status to in progress
            repository.updateTranscriptionStatus(recordingId, TranscriptionStatus.IN_PROGRESS, 0)

            // Get recording
            val recording = repository.getRecordingById(recordingId)
                ?: return@withContext Result.failure()

            // Initialize Whisper with downloaded model
            val whisperLib = WhisperLib(applicationContext)
            val modelManager = SecureVoxApp.instance.modelManager

            // Find the model by filename, default to TINY
            val whisperModel = WhisperModel.fromFileName(modelName) ?: WhisperModel.TINY

            // Check if model is downloaded
            if (!modelManager.isModelDownloaded(whisperModel)) {
                Log.e(TAG, "Model not downloaded: ${whisperModel.fileName}")
                repository.updateTranscriptionStatus(recordingId, TranscriptionStatus.FAILED, 0)
                return@withContext Result.failure()
            }

            // Get the model path from ModelManager
            val modelPath = modelManager.getModelPath(whisperModel)
            Log.i(TAG, "Initializing Whisper with model: $modelPath")

            val initialized = whisperLib.initialize(modelPath)

            if (!initialized) {
                Log.e(TAG, "Failed to initialize Whisper model from: $modelPath")
                repository.updateTranscriptionStatus(recordingId, TranscriptionStatus.FAILED, 0)
                return@withContext Result.failure()
            }

            // Load audio file
            val audioData = loadAudioFile(recording.audioFilePath)
            if (audioData == null) {
                Log.e(TAG, "Failed to load audio file")
                repository.updateTranscriptionStatus(recordingId, TranscriptionStatus.FAILED, 0)
                whisperLib.release()
                return@withContext Result.failure()
            }

            // Transcribe
            val segments = whisperLib.transcribe(
                audioData = audioData,
                language = language,
                onProgress = { progress ->
                    setProgressAsync(workDataOf(KEY_PROGRESS to progress))
                    // Can't call suspend functions here, just log
                    Log.d(TAG, "Transcription progress: $progress%")
                }
            )

            // Save segments
            val transcriptSegments = segments.mapIndexed { index, segment ->
                TranscriptSegment(
                    recordingId = recordingId,
                    text = segment.text,
                    startTimeMs = segment.startTimeMs,
                    endTimeMs = segment.endTimeMs,
                    segmentIndex = index
                )
            }

            repository.deleteSegmentsForRecording(recordingId)
            repository.saveSegments(transcriptSegments)

            // Update status to completed
            repository.updateTranscriptionStatus(recordingId, TranscriptionStatus.COMPLETED, 100)

            whisperLib.release()
            Log.i(TAG, "Transcription completed: ${segments.size} segments")

            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
            repository.updateTranscriptionStatus(recordingId, TranscriptionStatus.FAILED, 0)
            Result.failure()
        }
    }

    private fun loadAudioFile(filePath: String): FloatArray? {
        val file = File(filePath)
        if (!file.exists()) {
            Log.e(TAG, "Audio file not found: $filePath")
            return null
        }

        return try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(44)
                val headerBytesRead = fis.read(header)
                if (headerBytesRead < 44) {
                    Log.e(TAG, "File too small for WAV header: $headerBytesRead bytes")
                    return null
                }

                // Validate RIFF/WAVE header
                val riff = String(header, 0, 4)
                val wave = String(header, 8, 4)
                if (riff != "RIFF" || wave != "WAVE") {
                    Log.e(TAG, "Invalid WAV header: RIFF=$riff, WAVE=$wave")
                    return null
                }

                // Parse WAV format info
                val headerBuffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val audioFormat = headerBuffer.getShort(20).toInt()
                val channels = headerBuffer.getShort(22).toInt()
                val sampleRate = headerBuffer.getInt(24)
                val bitsPerSample = headerBuffer.getShort(34).toInt()

                Log.i(TAG, "WAV format: ${sampleRate}Hz, ${channels}ch, ${bitsPerSample}bit, fmt=$audioFormat")

                if (audioFormat != 1) { // 1 = PCM
                    Log.e(TAG, "Unsupported WAV audio format: $audioFormat (expected PCM=1)")
                    return null
                }

                if (bitsPerSample != 16) {
                    Log.e(TAG, "Unsupported bits per sample: $bitsPerSample (expected 16)")
                    return null
                }

                // Read PCM data
                val bytes = fis.readBytes()
                val shortBuffer = ByteBuffer.wrap(bytes)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer()

                val samples = ShortArray(shortBuffer.remaining())
                shortBuffer.get(samples)

                // Mix to mono if multi-channel
                val monoSamples = if (channels > 1) {
                    val monoLength = samples.size / channels
                    ShortArray(monoLength) { i ->
                        var sum = 0L
                        for (ch in 0 until channels) {
                            sum += samples[i * channels + ch]
                        }
                        (sum / channels).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    }
                } else {
                    samples
                }

                // Resample to 16kHz if needed
                val targetRate = 16000
                val finalSamples = if (sampleRate != targetRate) {
                    Log.i(TAG, "Resampling from ${sampleRate}Hz to ${targetRate}Hz")
                    val ratio = sampleRate.toDouble() / targetRate.toDouble()
                    val outputLength = (monoSamples.size / ratio).toInt()
                    ShortArray(outputLength) { i ->
                        val srcPos = i * ratio
                        val srcIndex = srcPos.toInt()
                        val fraction = srcPos - srcIndex
                        val s1 = monoSamples[srcIndex]
                        val s2 = if (srcIndex + 1 < monoSamples.size) monoSamples[srcIndex + 1] else s1
                        (s1 + (fraction * (s2 - s1))).toInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    }
                } else {
                    monoSamples
                }

                Log.i(TAG, "Loaded ${finalSamples.size} samples (${finalSamples.size / targetRate.toFloat()}s)")

                // Convert to float array normalized to [-1, 1]
                FloatArray(finalSamples.size) { i ->
                    finalSamples[i].toFloat() / Short.MAX_VALUE
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading audio file", e)
            null
        }
    }
}
