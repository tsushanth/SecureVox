package com.securevox.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Something another app, the quick-settings tile or a launcher shortcut asked SecureVox to do.
 *
 * Held here instead of acted on in MainActivity so it survives the app lock and the first-run
 * model download: the recordings screen picks it up once it is actually on screen.
 */
sealed interface IncomingAction {
    /** Transcribe an audio or video file shared or opened from another app. */
    data class ImportMedia(val uri: Uri) : IncomingAction

    /** Begin a new recording, from the tile or the launcher shortcut. */
    data object StartRecording : IncomingAction
}

object IncomingActions {
    const val ACTION_START_RECORDING = "com.securevox.app.action.START_RECORDING"

    val pending = MutableStateFlow<IncomingAction?>(null)

    fun handle(intent: Intent?) {
        pending.value = parse(intent) ?: return
    }

    /** Clears [action] only if nothing newer replaced it while it was being handled. */
    fun consume(action: IncomingAction) {
        pending.compareAndSet(action, null)
    }

    private fun parse(intent: Intent?): IncomingAction? {
        intent ?: return null
        return when (intent.action) {
            ACTION_START_RECORDING -> IncomingAction.StartRecording
            Intent.ACTION_SEND -> sharedStream(intent)?.let { IncomingAction.ImportMedia(it) }
            Intent.ACTION_VIEW -> intent.data?.let { IncomingAction.ImportMedia(it) }
            else -> null
        }
    }

    @Suppress("DEPRECATION")
    private fun sharedStream(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }
}
