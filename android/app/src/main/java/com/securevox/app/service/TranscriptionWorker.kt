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
import com.securevox.app.data.model.WordCodec
import com.securevox.app.data.model.TranscriptionStatus
import com.securevox.app.data.repository.RecordingRepository
import com.securevox.app.whisper.TranscriptionSegment as WhisperSegment
import com.securevox.app.whisper.WhisperLib
import com.securevox.app.engines.ChunkRequest
import com.securevox.app.engines.EngineManager
import com.securevox.app.engines.TranscriptionEngine
import com.securevox.app.engines.WhisperEngine
import com.securevox.app.speakers.ModelDownloader
import com.securevox.app.speakers.SpeakerDiarizer
import com.securevox.app.speakers.SpeakerLabels
import com.securevox.app.speakers.SpeakerPass
import com.securevox.app.speakers.SpeakerModels
import com.securevox.app.speakers.assignSpeakersByWord
import com.securevox.app.speakers.hasMultipleSpeakers
import com.securevox.app.presentation.settings.dataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import com.securevox.app.whisper.WhisperModel
import com.securevox.app.SecureVoxApp
import com.securevox.app.util.CrashLogger
import com.securevox.app.util.MemoryPolicy
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

        // Tracked so a FAILED entry in the local log has real context (how far it got, how long
        // it ran) instead of just an exception message — Logcat's ring buffer can't be relied on
        // to still hold this by the time a user reports a problem.
        val transcriptionStartedAtMs = System.currentTimeMillis()
        var lastChunkIndexReached = -1
        var lastTotalChunks = -1
        // Speech engine for this recording (engines package); closed in the finally below.
        var engine: TranscriptionEngine? = null

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
            val wavInfo = WavReader.readWavInfo(recording.audioFilePath)
            if (wavInfo == null) {
                Log.e(TAG, "Failed to parse WAV info")
                repository.updateTranscriptionStatus(recordingId, TranscriptionStatus.FAILED, 0)
                whisperLib.release()
                return@withContext Result.failure()
            }

            val totalAudioSamples = wavInfo.totalMonoSamples
            val totalChunks = (((totalAudioSamples + CHUNK_SAMPLES - 1) / CHUNK_SAMPLES).coerceAtLeast(1)).toInt()
            lastTotalChunks = totalChunks
            Log.i(TAG, "Audio: ${totalAudioSamples} samples, processing in $totalChunks chunks")

            val allSegments = mutableListOf<TranscriptSegment>()
            var segmentIndex = 0

            // ENGINES: whisper.cpp unless the user installed a better engine for this language (see
            // engines/EngineRouter); an engine that fails to load or throws falls back to whisper.cpp.
            val activeEngine = EngineManager.createEngine(applicationContext, WhisperEngine(whisperLib), language, recordingId)
            engine = activeEngine

            repository.deleteSegmentsForRecording(recordingId)

            for (chunkIdx in 0 until totalChunks) {
                lastChunkIndexReached = chunkIdx
                val chunkStartSample = ChunkTiming.chunkStartSample(chunkIdx, CHUNK_SAMPLES, OVERLAP_SAMPLES)
                val chunkEndSample = minOf(chunkStartSample + CHUNK_SAMPLES + OVERLAP_SAMPLES, totalAudioSamples.toInt())

                val chunkData = WavReader.loadAudioChunk(wavInfo, chunkStartSample, chunkEndSample)
                if (chunkData == null) {
                    Log.e(TAG, "Failed to load chunk $chunkIdx")
                    continue
                }

                val chunkOffsetMs = ChunkTiming.chunkOffsetMs(chunkStartSample)

                // ENGINES dispatch hook: was whisperLib.transcribe(chunkData, language, onProgress).
                val chunkSegments = activeEngine.transcribe(
                    ChunkRequest(chunkData, language, if (chunkIdx > 0) OVERLAP_SAMPLES * 1000L / 16000L else 0L),
                    onProgress = { chunkProgress ->
                        val overall = ((chunkIdx * 100 + chunkProgress) / totalChunks).coerceIn(0, 99)
                        setProgressAsync(workDataOf(KEY_PROGRESS to overall))
                    }
                )

                // Adjust timestamps by chunk offset and filter out overlap duplicates
                val overlapMs = ChunkTiming.overlapMs(chunkIdx, OVERLAP_SAMPLES)
                // chunkOffsetMs already points at the chunk's first sample (overlap included), so
                // a chunk-relative time maps to chunkOffsetMs + time. The old "- overlapMs" put
                // every segment after the first chunk about one second too early.
                val newSegments = chunkSegments
                    .filter { ChunkTiming.isNewSegment(it.startTimeMs, overlapMs) } // skip overlap from previous chunk
                    .map { seg ->
                        TranscriptSegment(
                            recordingId = recordingId,
                            text = seg.text,
                            startTimeMs = ChunkTiming.toRecordingMs(seg.startTimeMs, chunkOffsetMs),
                            endTimeMs = ChunkTiming.toRecordingMs(seg.endTimeMs, chunkOffsetMs),
                            segmentIndex = segmentIndex++,
                            // Word timing: same offset as the segment; "" when none were produced.
                            words = WordCodec.encode(seg.words.map { it.shifted(chunkOffsetMs) })
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

            // Free the Whisper model before the speaker pass loads its own models.
            activeEngine.close()
            whisperLib.release()
            labelSpeakers(recordingId, recording.audioFilePath)

            repository.updateTranscriptionStatus(recordingId, TranscriptionStatus.COMPLETED, 100)
            Log.i(TAG, "Transcription completed: ${allSegments.size} total segments")

            Result.success()

        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM during transcription — device RAM too low", e)
            repository.updateTranscriptionStatus(recordingId, TranscriptionStatus.FAILED, 0)
            logFailureToDisk(
                recordingId, "OutOfMemoryError", e.message,
                transcriptionStartedAtMs, lastChunkIndexReached, lastTotalChunks
            )
            Result.failure()
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
            repository.updateTranscriptionStatus(recordingId, TranscriptionStatus.FAILED, 0)
            logFailureToDisk(
                recordingId, e.javaClass.simpleName, e.message,
                transcriptionStartedAtMs, lastChunkIndexReached, lastTotalChunks
            )
            Result.failure()
        } finally {
            engine?.close() // ENGINES: also frees the alternative engine on failure/cancel (idempotent)
        }
    }

    /**
     * Optional second pass: labels who is speaking. It must never fail the transcription, so
     * any problem (models missing, too little memory, an error in the native library) just
     * leaves the transcript unlabelled. The work itself lives in [SpeakerPass].
     */
    private suspend fun labelSpeakers(recordingId: String, audioPath: String) {
        val enabled = applicationContext.dataStore.data.first()[SpeakerLabels.KEY_ENABLED] ?: false
        if (!enabled) {
            SpeakerLabels.setSkipNote(applicationContext, recordingId, null)
            return
        }
        SpeakerPass(applicationContext, repository)
            .run(recordingId, audioPath, SpeakerLabels.chosenCount(applicationContext, recordingId), SpeakerPass.Mode.FIRST_TIME)
    }

    /**
     * Appends a diagnostic entry to the local on-device failure log for a FAILED transcription.
     * This covers the case that actually bit us in production: a stall with no exception thrown
     * still hits one of these catch blocks via the coroutine's cancellation/failure path, but even
     * when it doesn't, having duration + chunk progress on disk for the exception cases we DO see
     * is what lets a "Report a Problem" email actually be diagnosable instead of just "it failed".
     */
    private fun logFailureToDisk(
        recordingId: String,
        exceptionType: String,
        exceptionMessage: String?,
        startedAtMs: Long,
        lastChunkIndexReached: Int,
        totalChunks: Int
    ) {
        val durationSeconds = (System.currentTimeMillis() - startedAtMs) / 1000
        CrashLogger.appendEntry(
            applicationContext,
            "TRANSCRIPTION FAILED\n" +
                "recordingId=$recordingId\n" +
                "exception=$exceptionType: ${exceptionMessage ?: "(no message)"}\n" +
                "durationSeconds=$durationSeconds\n" +
                "chunkReached=${if (lastChunkIndexReached >= 0) lastChunkIndexReached else "n/a"}" +
                "/${if (totalChunks > 0) totalChunks else "n/a"}"
        )
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
}
