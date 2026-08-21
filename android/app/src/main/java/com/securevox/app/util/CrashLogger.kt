package com.securevox.app.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local-only, on-device failure log. SecureVox has no backend and no crash-reporting SDK
 * (Crashlytics, Sentry, etc.) by design — this is deliberately just a file in the app's
 * private storage that the user can choose to attach to a support email themselves via
 * "Report a Problem" in Settings. Nothing here is ever transmitted automatically.
 *
 * Two writers feed the same file:
 *  - the uncaught-exception handler installed in [com.securevox.app.SecureVoxApp], for hard crashes
 *  - [TranscriptionWorker][com.securevox.app.service.TranscriptionWorker]'s failure paths, for
 *    silent/handled failures (e.g. OOM, a stall) that never throw past a catch block and would
 *    otherwise leave no trace once Logcat's ring buffer rolls over.
 */
object CrashLogger {

    private const val TAG = "CrashLogger"
    const val LOG_DIR_NAME = "logs"
    const val LOG_FILE_NAME = "crash_log.txt"
    private const val OLD_LOG_FILE_NAME = "crash_log.txt.old"
    private const val MAX_LOG_SIZE_BYTES = 500 * 1024L // 500KB

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * Installs an uncaught-exception handler that logs to disk and then chains to whatever
     * default handler was already registered, so normal OS crash behavior (process death,
     * Play crash reporting, etc.) is unaffected.
     */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                appendEntry(appContext, "UNCAUGHT EXCEPTION on thread '${thread.name}'\n$sw")
            } catch (loggingFailure: Throwable) {
                // Never let the logger itself cause a crash loop.
                Log.e(TAG, "Failed to write crash log", loggingFailure)
            }

            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Appends a plain-text entry to the local log file, with a timestamp header. Safe to call
     * from any thread; failures are swallowed (after a Logcat warning) so this can never be the
     * cause of a crash.
     */
    fun appendEntry(context: Context, message: String) {
        try {
            val logFile = logFile(context)
            rotateIfNeeded(logFile)
            logFile.parentFile?.mkdirs()
            logFile.appendText("---- ${dateFormat.format(Date())} ----\n$message\n\n")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to append to crash log", e)
        }
    }

    /** The active log file, e.g. for reading its contents or attaching it to an email. */
    fun logFile(context: Context): File {
        val dir = File(context.applicationContext.filesDir, LOG_DIR_NAME)
        return File(dir, LOG_FILE_NAME)
    }

    private fun rotateIfNeeded(logFile: File) {
        if (logFile.exists() && logFile.length() > MAX_LOG_SIZE_BYTES) {
            val oldFile = File(logFile.parentFile, OLD_LOG_FILE_NAME)
            if (oldFile.exists()) oldFile.delete()
            logFile.renameTo(oldFile)
        }
    }
}
