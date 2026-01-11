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
            modelName: String = "ggml-base.bin",
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

            // Initialize Whisper
            val whisperLib = WhisperLib(applicationContext)
            val initialized = whisperLib.initializeFromAsset(modelName)

            if (!initialized) {
                Log.e(TAG, "Failed to initialize Whisper model")
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
        if (!file.exists()) return null

        return try {
            FileInputStream(file).use { fis ->
                // Skip WAV header (44 bytes)
                fis.skip(44)

                // Read remaining as 16-bit PCM
                val bytes = fis.readBytes()
                val shortBuffer = ByteBuffer.wrap(bytes)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer()

                val samples = ShortArray(shortBuffer.remaining())
                shortBuffer.get(samples)

                // Convert to float array normalized to [-1, 1]
                FloatArray(samples.size) { i ->
                    samples[i].toFloat() / Short.MAX_VALUE
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading audio file", e)
            null
        }
    }
}
