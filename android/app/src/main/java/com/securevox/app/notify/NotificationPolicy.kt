package com.securevox.app.notify

/** When to ask for the notification permission (Android 13+ only; earlier versions need no prompt). */
object NotificationPermission {
    const val FIRST_SDK_WITH_PERMISSION = 33

    /**
     * Offer once: the moment a first transcription starts, with a reason shown before the system prompt.
     * After that (granted, declined, or dismissed) we never ask again, and recording never waits on it.
     */
    fun shouldOffer(sdkInt: Int, granted: Boolean, askedBefore: Boolean): Boolean =
        sdkInt >= FIRST_SDK_WITH_PERMISSION && !granted && !askedBefore
}

/** Whether a finished transcription should raise a "Transcript ready" notification. */
object ReadyNotification {
    /**
     * @param notificationsEnabled the app's notifications are allowed (permission on Android 13+, the app toggle)
     * @param channelBlocked the user switched this notification category off
     * @param viewingThisRecording the app is in the foreground showing this very recording
     * @param hasTranscript something was actually transcribed (an empty result is not "ready")
     */
    fun shouldPost(
        notificationsEnabled: Boolean,
        channelBlocked: Boolean,
        viewingThisRecording: Boolean,
        hasTranscript: Boolean
    ): Boolean = notificationsEnabled && !channelBlocked && !viewingThisRecording && hasTranscript
}

/**
 * The recording whose detail screen is on screen right now (resumed), or null. Lets the worker skip the
 * notification when the user is already looking at the result.
 */
object ViewedRecording {
    @Volatile
    var current: String? = null
        private set

    @Synchronized
    fun enter(recordingId: String) {
        current = recordingId
    }

    @Synchronized
    fun leave(recordingId: String) {
        if (current == recordingId) current = null
    }
}
