package com.securevox.app.data

import android.content.Context
import com.securevox.app.data.local.SecureVoxDatabase
import com.securevox.app.service.AudioRecorderService

/**
 * One-time fix for recordings saved by versions that stored about twice the real duration
 * (see [AudioRecorderService.stopRecording]). The file size is the source of truth, so a
 * recording is only touched when its stored duration is roughly double what the file holds;
 * correct rows, imports and anything else are left alone, which also makes this safe to re-run.
 */
object DurationRepair {
    private const val PREFS = "securevox_prefs"
    private const val DONE_KEY = "duration_repair_v1_done"

    /** The corrected duration, or null when [duration] doesn't look like the doubling bug. */
    fun repairedDuration(duration: Long, fileSize: Long): Long? {
        val real = AudioRecorderService.durationMsForWavBytes(fileSize)
        if (real <= 0L || duration <= 0L) return null
        val ratio = duration.toDouble() / real
        return if (ratio in 1.8..2.2) real else null
    }

    suspend fun runOnce(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(DONE_KEY, false)) return

        val dao = SecureVoxDatabase.getInstance(context).recordingDao()
        dao.getAllOnce().forEach { recording ->
            repairedDuration(recording.duration, recording.fileSize)?.let { real ->
                dao.updateRecording(recording.copy(duration = real))
            }
        }
        prefs.edit().putBoolean(DONE_KEY, true).apply()
    }
}
