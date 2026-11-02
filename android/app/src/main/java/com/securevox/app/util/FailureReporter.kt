package com.securevox.app.util

import android.content.Context
import android.os.Build
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.securevox.app.util.FailureReporter

/**
 * Sends crashes and handled failures to the shared app-failure-reporter Worker, which dedupes them and
 * emails the developer. No third-party dependencies, fire-and-forget, and it never throws into the caller.
 *
 *   FailureReporter.init(this, "securevox", KEY, versionName)   // Application.onCreate, before anything else
 *   FailureReporter.failure("export.file", exception)           // a named flow broke but the app is alive
 *
 * What is sent: app version, Android version, device model, free storage in MB, a stable flow name, and an
 * exception class with a scrubbed message and code locations. Never audio, transcripts, note text, titles,
 * file names or paths, emails: see [FailureSanitizer], which every report passes through. The user can turn
 * reporting off in Settings ([setEnabled]); it is on by default.
 *
 * The local, user-initiated log ([CrashLogger]) is unchanged and independent of this.
 */
object FailureReporter {
    private const val ENDPOINT = "https://app-failure-reporter.t-sushanth.workers.dev/v1/report"
    private const val PREFS = "failure_reporter"
    private const val PENDING = "pending_crash"
    private const val ENABLED = "enabled"
    private const val CRASH_WAIT_MS = 3000L

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "failure-reporter").apply { isDaemon = true } }
    private val limiter = ReportLimiter()
    @Volatile private var appCtx: Context? = null
    @Volatile private var key = ""
    @Volatile private var version = ""

    /** [appName] is kept for parity with the other apps; the Worker identifies the app by [ingestKey]. */
    @Suppress("UNUSED_PARAMETER")
    fun init(context: Context, appName: String, ingestKey: String, versionName: String) {
        try {
            appCtx = context.applicationContext
            key = ingestKey
            version = versionName
            flushPendingCrash()
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, t ->
                try {
                    if (isEnabled()) {
                        val body = payload("crash", "uncaught", FailureSanitizer.describe(t), FailureSanitizer.stack(t), mapOf("thread" to thread.name))
                        // Persist first: the process is about to die and the network may be down.
                        prefs()?.edit()?.putString(PENDING, body)?.commit()
                        // Post on the reporter thread and wait a few seconds: a crash on the main thread
                        // may not open a socket itself (NetworkOnMainThreadException).
                        val sent = io.submit(Callable { post(body, CRASH_WAIT_MS.toInt()) }).get(CRASH_WAIT_MS, TimeUnit.MILLISECONDS)
                        if (sent) prefs()?.edit()?.remove(PENDING)?.commit()
                    }
                } catch (_: Throwable) {
                } finally {
                    previous?.uncaughtException(thread, t)
                }
            }
        } catch (_: Throwable) {
            // Reporting must never be the reason the app fails to start.
        }
    }

    /**
     * A handled failure: a user-facing flow broke but the app did not crash.
     * [flow] is a stable code ("recording.start"); [reason] a stable short code ("mic_unavailable");
     * [context] only allowlisted keys with token-like values ([FailureSanitizer.ALLOWED_CONTEXT_KEYS]).
     */
    fun failure(flow: String, t: Throwable? = null, reason: String? = null, context: Map<String, String> = emptyMap()) {
        try {
            val ctx = appCtx ?: return
            if (!isEnabled() || FailureSanitizer.isCancellation(t)) return
            io.execute {
                try {
                    val message = buildList {
                        if (FailureSanitizer.isStorageFull(t)) add("storage_full")
                        if (!reason.isNullOrBlank()) add(reason)
                        val d = FailureSanitizer.describe(t)
                        if (d.isNotEmpty()) add(d)
                    }.joinToString(" | ").ifEmpty { "failure" }
                    val safeFlow = FailureSanitizer.flow(flow)
                    if (!limiter.allow(safeFlow, FailureSanitizer.message(message))) return@execute
                    val extra = context + mapOf("free_mb" to (ctx.filesDir.usableSpace / (1024 * 1024)).coerceAtMost(99_999L).toString())
                    post(payload("failure", safeFlow, message, FailureSanitizer.stack(t), extra), 8000)
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    fun isEnabled(): Boolean = try { prefs()?.getBoolean(ENABLED, true) ?: false } catch (_: Throwable) { false }

    fun isEnabled(context: Context): Boolean =
        try { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, true) } catch (_: Throwable) { true }

    /** User choice from Settings. Turning it off also discards a crash report that was waiting to be sent. */
    fun setEnabled(context: Context, enabled: Boolean) {
        try {
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, enabled)
            if (!enabled) p.remove(PENDING)
            p.apply()
        } catch (_: Throwable) {
        }
    }

    private fun payload(kind: String, flow: String, message: String, stack: String, ctx: Map<String, String>): String =
        ReportPayload.build(
            kind = kind, version = version, flow = flow, message = message, stack = stack, context = ctx,
            device = "${Build.MANUFACTURER} ${Build.MODEL}", androidRelease = Build.VERSION.RELEASE.orEmpty(), sdk = Build.VERSION.SDK_INT
        )

    private fun post(body: String, timeoutMs: Int): Boolean {
        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"; conn.doOutput = true
            conn.connectTimeout = timeoutMs; conn.readTimeout = timeoutMs
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-Report-Key", key)
            conn.outputStream.use { it.write(body.toByteArray()) }
            conn.responseCode in 200..299
        } finally { conn.disconnect() }
    }

    private fun prefs() = appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun flushPendingCrash() {
        val pending = prefs()?.getString(PENDING, null) ?: return
        if (!isEnabled()) { prefs()?.edit()?.remove(PENDING)?.apply(); return }
        io.execute { try { if (post(pending, 8000)) prefs()?.edit()?.remove(PENDING)?.apply() } catch (_: Throwable) {} }
    }
}
