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
import com.securevox.app.data.model.TranscriptionStatus
import com.securevox.app.data.repository.RecordingRepository
import com.securevox.app.speakers.SpeakerCount
import com.securevox.app.speakers.SpeakerLabels
import com.securevox.app.speakers.SpeakerPass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Re-runs only the speaker pass for one recording with a user-chosen speaker count. The transcript
 * text and timings are never changed; see [com.securevox.app.speakers.SpeakerRelabel].
 */
class SpeakerRelabelWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "SpeakerRelabelWorker"
        const val KEY_RECORDING_ID = "recording_id"
        /** 0 = automatic, else 1..8. */
        const val KEY_NUM_SPEAKERS = "num_speakers"
        const val KEY_RESULT = "result"
        const val KEY_ERROR = "error"
        const val TAG_PREFIX = "relabel:"
        const val RESULT_DONE = "done"
        const val RESULT_SKIPPED = "skipped"
        const val RESULT_MODELS_MISSING = "models_missing"
        private const val CHANNEL_ID = "speaker_relabel"
        private const val NOTIFICATION_ID = 9102

        fun uniqueName(recordingId: String) = "$TAG_PREFIX$recordingId"

        /** Queues a relabel; a second request while one runs is ignored (the button is disabled meanwhile). */
        fun enqueue(context: Context, recordingId: String, numSpeakers: Int?) {
            SpeakerLabels.setChosenCount(context, recordingId, numSpeakers)
            val request = OneTimeWorkRequestBuilder<SpeakerRelabelWorker>()
                .setInputData(
                    workDataOf(
                        KEY_RECORDING_ID to recordingId,
                        KEY_NUM_SPEAKERS to (SpeakerCount.normalize(numSpeakers) ?: 0)
                    )
                )
                .addTag(uniqueName(recordingId))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(uniqueName(recordingId), ExistingWorkPolicy.KEEP, request)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val recordingId = inputData.getString(KEY_RECORDING_ID) ?: return@withContext Result.failure()
        val requested = SpeakerCount.normalize(inputData.getInt(KEY_NUM_SPEAKERS, 0))
        val db = SecureVoxDatabase.getInstance(applicationContext)
        val repository = RecordingRepository(db.recordingDao(), db.transcriptSegmentDao())

        try {
            setForeground(foregroundInfo())
        } catch (e: Exception) {
            Log.w(TAG, "Could not promote to foreground service, continuing in background", e)
        }

        val recording = repository.getRecordingById(recordingId)
            ?: return@withContext Result.failure(workDataOf(KEY_ERROR to "Recording not found"))
        if (recording.transcriptionStatus == TranscriptionStatus.IN_PROGRESS) {
            return@withContext Result.failure(workDataOf(KEY_ERROR to "The recording is still being transcribed"))
        }

        // The 30-minute cap: the Worker's own limit is generous, but a hung native pass must not
        // pin the foreground notification for ever. (The length policy itself is in SpeakerPass.)
        val outcome = try {
            kotlinx.coroutines.withTimeout(TimeUnit.MINUTES.toMillis(30)) {
                SpeakerPass(applicationContext, repository)
                    .run(recordingId, recording.audioFilePath, requested, SpeakerPass.Mode.RELABEL)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            return@withContext Result.failure(workDataOf(KEY_ERROR to "Timed out after 30 minutes"))
        }

        when (outcome) {
            is SpeakerPass.Outcome.Done ->
                Result.success(workDataOf(KEY_RESULT to RESULT_DONE))
            is SpeakerPass.Outcome.Skipped ->
                Result.success(workDataOf(KEY_RESULT to RESULT_SKIPPED))
            SpeakerPass.Outcome.ModelsMissing ->
                Result.failure(workDataOf(KEY_RESULT to RESULT_MODELS_MISSING))
            SpeakerPass.Outcome.PreviousCrash ->
                Result.failure(workDataOf(KEY_ERROR to "Speaker labelling stopped unexpectedly last time. Try again."))
            is SpeakerPass.Outcome.Failed ->
                Result.failure(workDataOf(KEY_ERROR to outcome.message))
        }
    }

    private fun foregroundInfo(): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Speaker labels", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Shown while re-labelling the speakers of a recording" }
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.relabel_notification_title))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }
}
