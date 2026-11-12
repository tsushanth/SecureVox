package com.securevox.app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.securevox.app.IncomingActions
import com.securevox.app.MainActivity
import com.securevox.app.R

/** The "Transcript ready" notification (the ongoing progress notification lives in TranscriptionWorker). */
object TranscriptNotifier {
    const val CHANNEL_READY = "transcript_ready"

    private fun notificationId(recordingId: String) = 20_000 + (recordingId.hashCode() and 0xFFFF)

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_READY,
            context.getString(R.string.notif_channel_ready_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = context.getString(R.string.notif_channel_ready_description) }
        manager.createNotificationChannel(channel)
    }

    /** Posts the notification unless it is not allowed, switched off, or the user is already looking at [recordingId]. */
    fun notifyReady(context: Context, recordingId: String, title: String, hasTranscript: Boolean) {
        val app = context.applicationContext
        ensureChannel(app)
        val manager = NotificationManagerCompat.from(app)
        val channelBlocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager.getNotificationChannel(CHANNEL_READY)?.importance == NotificationManagerCompat.IMPORTANCE_NONE
        val allowed = manager.areNotificationsEnabled() && (
            Build.VERSION.SDK_INT < NotificationPermission.FIRST_SDK_WITH_PERMISSION ||
                ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            )
        if (!ReadyNotification.shouldPost(allowed, channelBlocked, ViewedRecording.current == recordingId, hasTranscript)) return

        val open = Intent(app, MainActivity::class.java)
            .setAction(IncomingActions.ACTION_OPEN_RECORDING)
            .putExtra(IncomingActions.EXTRA_RECORDING_ID, recordingId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val tap = PendingIntent.getActivity(
            app, notificationId(recordingId), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // The lock screen shows only the generic text: a recording title can be private.
        val publicVersion = NotificationCompat.Builder(app, CHANNEL_READY)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(app.getString(R.string.notif_ready_title))
            .build()
        val notification = NotificationCompat.Builder(app, CHANNEL_READY)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(app.getString(R.string.notif_ready_title))
            .setContentText(title)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()
        try {
            manager.notify(notificationId(recordingId), notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post: nothing to do.
        }
    }

    /** Removes the notification once the user has opened the recording some other way. */
    fun cancel(context: Context, recordingId: String) {
        NotificationManagerCompat.from(context.applicationContext).cancel(notificationId(recordingId))
    }
}
