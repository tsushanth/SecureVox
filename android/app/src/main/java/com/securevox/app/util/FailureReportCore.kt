package com.securevox.app.util

/**
 * The pure parts of failure reporting (no Android classes, so they run in plain JVM unit tests):
 * what is allowed to leave the device, how it is shaped, and how often it may be sent.
 * [FailureReporter] is the thin Android shell around this file.
 *
 * Privacy contract, enforced here and covered by FailureReportCoreTest:
 *  - no audio, transcript or note text, recording titles, file names or paths, URIs, emails;
 *  - exception MESSAGES are scrubbed; stack traces carry class names and line numbers only;
 *  - extra context is limited to an allowlist of keys with short token-like values.
 */
object FailureSanitizer {

    const val MAX_MESSAGE = 200
    const val MAX_FLOW = 60
    const val MAX_FRAMES = 25
    const val MAX_STACK = 5000
    private const val MAX_INPUT = 2000
    private const val MAX_CAUSES = 4

    /** Context keys the app may attach. Anything else is dropped. */
    val ALLOWED_CONTEXT_KEYS = setOf(
        "model", "engine", "stage", "reason", "chunk", "chunks", "source", "code", "status", "free_mb", "thread"
    )

    private val URI = Regex("""\b[a-zA-Z][a-zA-Z0-9+.\-]*://\S*""")
    private val EMAIL = Regex("""[\w.+\-]+@[\w\-]+\.[\w.\-]+""")
    // From a slash that starts a path (not "and/or") through the rest of it, spaces included, up to the next
    // character that cannot be part of a path (colon, comma, quote, bracket).
    private val PATH = Regex("""(?<!\w)/(?=[\w.@+~%$\-])[\w.@+~%$ /\-]*""")
    private val QUOTED = Regex("""(?<!\w)(?:'[^'\n]*'|"[^"\n]*"|`[^`\n]*`)(?!\w)""")
    private val FILE = Regex(
        """[\w. \-]+\.(?:wav|mp3|m4a|aac|ogg|oga|opus|flac|wma|amr|3gp|mp4|m4v|mkv|mov|webm|avi|txt|srt|vtt|json|csv|pdf|docx?|db|sqlite)\b""",
        RegexOption.IGNORE_CASE
    )
    private val UUID = Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")
    private val HEX = Regex("""\b[0-9a-fA-F]{16,}\b""")
    private val BEARER = Regex("""(bearer\s+)\S+""", RegexOption.IGNORE_CASE)
    private val SECRET = Regex("""\b(?:sk|pk|rk|key|token)[-_][A-Za-z0-9_\-]{16,}""", RegexOption.IGNORE_CASE)
    private val LONG_DIGITS = Regex("""\d{6,}""")
    private val NON_ASCII = Regex("""[^\x20-\x7E]""")
    private val SPACES = Regex("""\s+""")
    private val TOKEN_VALUE = Regex("""[A-Za-z0-9_.:\-]{1,40}""")
    private val FLOW_BAD = Regex("""[^a-z0-9._\-]""")

    /** A free-text message made safe to send: never contains paths, URIs, emails, quoted text or file names. */
    fun message(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var s = raw.take(MAX_INPUT)
        s = URI.replace(s, "<uri>")
        s = EMAIL.replace(s, "<email>")
        s = PATH.replace(s, "<path>")
        s = QUOTED.replace(s, "<str>")
        s = FILE.replace(s, "<file>")
        s = UUID.replace(s, "<id>")
        s = BEARER.replace(s, "$1<token>")
        s = SECRET.replace(s, "<secret>")
        s = HEX.replace(s, "<hex>")
        s = LONG_DIGITS.replace(s, "<n>")
        s = NON_ASCII.replace(s, "?")
        s = SPACES.replace(s, " ").trim()
        return s.take(MAX_MESSAGE)
    }

    /** A flow name is a stable code like "recording.start": lower-case letters, digits, dot, dash, underscore. */
    fun flow(raw: String): String {
        val s = FLOW_BAD.replace(raw.lowercase().trim(), "_").take(MAX_FLOW)
        return s.ifEmpty { "unknown" }
    }

    /** Keeps only allowlisted keys whose value is a short token that survives [message] unchanged. */
    fun context(raw: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((k, v) in raw) {
            if (k !in ALLOWED_CONTEXT_KEYS) continue
            if (!TOKEN_VALUE.matches(v)) continue
            if (message(v) != v) continue // looks like a file name, id or path
            out[k] = v
        }
        return out
    }

    /** Device strings from the OS (manufacturer, model, version): ASCII, short, no control characters. */
    fun device(raw: String?): String = NON_ASCII.replace(raw ?: "", "?").replace(SPACES, " ").trim().take(60)

    /**
     * Class names and code locations only. Exception messages are deliberately left out of the stack: they are
     * where file names and user text end up. The message, scrubbed, travels separately.
     */
    fun stack(t: Throwable?): String {
        if (t == null) return ""
        val sb = StringBuilder()
        var cur: Throwable? = t
        var depth = 0
        val seen = HashSet<Throwable>()
        while (cur != null && depth < MAX_CAUSES && seen.add(cur)) {
            sb.append(if (depth == 0) "" else "Caused by: ").append(cur.javaClass.name).append('\n')
            for (e in cur.stackTrace.take(MAX_FRAMES)) sb.append("\tat ").append(e.toString()).append('\n')
            cur = cur.cause
            depth++
        }
        return sb.toString().take(MAX_STACK)
    }

    /** "ClassName: scrubbed message" for a throwable, or "" when there is none. */
    fun describe(t: Throwable?): String {
        if (t == null) return ""
        val name = t.javaClass.simpleName.ifEmpty { t.javaClass.name }
        val msg = message(t.message)
        return if (msg.isEmpty()) name else "$name: $msg"
    }

    private fun chain(t: Throwable?): Sequence<Throwable> = generateSequence(t) { it.cause }.take(8)

    /** Out of disk space anywhere in the cause chain (java.io.IOException "No space left on device", SQLite full). */
    fun isStorageFull(t: Throwable?): Boolean = chain(t).any {
        val m = it.message.orEmpty()
        m.contains("ENOSPC", ignoreCase = true) ||
            m.contains("No space left", ignoreCase = true) ||
            it.javaClass.simpleName == "SQLiteFullException"
    }

    /** The phone is offline or the network dropped: the user's connection, not a bug in the app. */
    fun isConnectivity(t: Throwable?): Boolean = chain(t).any {
        it is java.net.UnknownHostException || it is java.net.SocketException ||
            it is java.net.SocketTimeoutException || it is javax.net.ssl.SSLException
    }

    /** Coroutine / worker cancellation is control flow, never a failure. */
    fun isCancellation(t: Throwable?): Boolean = chain(t).any { it is java.util.concurrent.CancellationException }
}

/** Builds the JSON body of one report. Hand-rolled on [MiniJson.quote] so it is testable on the JVM. */
object ReportPayload {

    const val PLATFORM = "android"

    fun build(
        kind: String,
        version: String,
        flow: String,
        message: String,
        stack: String,
        context: Map<String, String>,
        device: String,
        androidRelease: String,
        sdk: Int
    ): String {
        val ctx = LinkedHashMap<String, String>()
        ctx.putAll(FailureSanitizer.context(context))
        ctx["device"] = FailureSanitizer.device(device)
        ctx["android"] = FailureSanitizer.device(androidRelease)
        ctx["sdk"] = sdk.toString()
        val q = MiniJson::quote
        return buildString {
            append('{')
            append("\"kind\":").append(q(kind))
            append(",\"platform\":").append(q(PLATFORM))
            append(",\"version\":").append(q(FailureSanitizer.device(version).ifEmpty { "unknown" }))
            append(",\"flow\":").append(q(FailureSanitizer.flow(flow)))
            append(",\"message\":").append(q(FailureSanitizer.message(message).ifEmpty { "failure" }))
            append(",\"stack\":").append(q(stack.take(FailureSanitizer.MAX_STACK)))
            append(",\"context\":{")
            append(ctx.entries.joinToString(",") { q(it.key) + ":" + q(it.value) })
            append("}}")
        }
    }
}

/**
 * Client-side throttle so a failure loop cannot hammer the network or the battery:
 *  - the same flow + message is sent at most once per hour,
 *  - one flow is sent at most [perFlowPerHour] times per hour,
 *  - everything together at most [globalPerMinute] per minute.
 * The Worker dedupes too; this keeps the traffic from being generated at all.
 */
class ReportLimiter(
    private val now: () -> Long = System::currentTimeMillis,
    private val perFlowPerHour: Int = 3,
    private val globalPerMinute: Int = 10
) {
    private val hourMs = 60 * 60 * 1000L
    private val minuteMs = 60 * 1000L
    private val perFlow = HashMap<String, ArrayDeque<Long>>()
    private val lastSame = HashMap<String, Long>()
    private val recent = ArrayDeque<Long>()

    @Synchronized
    fun allow(flow: String, message: String): Boolean {
        val t = now()
        prune(t)
        val fp = flow + "|" + message.replace(Regex("""\d+"""), "#")
        if (lastSame[fp]?.let { t - it < hourMs } == true) return false
        val flowTimes = perFlow.getOrPut(flow) { ArrayDeque() }
        if (flowTimes.size >= perFlowPerHour) return false
        if (recent.size >= globalPerMinute) return false
        flowTimes.addLast(t)
        recent.addLast(t)
        lastSame[fp] = t
        return true
    }

    private fun prune(t: Long) {
        while (recent.isNotEmpty() && t - recent.first() >= minuteMs) recent.removeFirst()
        val iter = perFlow.entries.iterator()
        while (iter.hasNext()) {
            val q = iter.next().value
            while (q.isNotEmpty() && t - q.first() >= hourMs) q.removeFirst()
            if (q.isEmpty()) iter.remove()
        }
        if (lastSame.size > 200) lastSame.entries.removeAll { t - it.value >= hourMs }
    }
}

/**
 * Opt-in policy for diagnostic reports, kept free of Android types so the JVM tests can pin it.
 * Reporting is OFF until the user says yes; the question is asked once, and only after the app has
 * already done something useful for them (a first finished transcription).
 */
object ReportConsent {
    /** Nothing is sent unless the user turned reporting on. */
    const val DEFAULT_ENABLED = false

    /** Number of finished transcriptions after which the one-time card may appear. */
    const val PROMPT_AFTER_COMPLETED = 1

    /**
     * Show the one-time card: not answered yet (neither on the card nor with the Settings switch) and the user
     * has seen at least one finished transcription. An unanswered card never counts as consent.
     */
    fun shouldPrompt(answered: Boolean, completedTranscriptions: Int): Boolean =
        !answered && completedTranscriptions >= PROMPT_AFTER_COMPLETED
}

/** Minimal key-value storage the consent state needs (SharedPreferences in the app, a map in tests). */
interface ConsentStore {
    fun getBoolean(key: String, default: Boolean): Boolean

    /** Writes [booleans] and deletes [remove] in one step. */
    fun apply(booleans: Map<String, Boolean>, remove: Set<String> = emptySet())
}

/**
 * The user's choice about diagnostic reports. Default is off. Whatever the user picks, on the card or with the
 * Settings switch, counts as answering the question, so the card is never shown again. Turning reporting off
 * also deletes a crash report that was saved for sending later.
 */
class ConsentState(private val store: ConsentStore) {
    fun isEnabled(): Boolean = store.getBoolean(ENABLED, ReportConsent.DEFAULT_ENABLED)

    fun hasAnswered(): Boolean = store.getBoolean(ASKED, false)

    fun setEnabled(enabled: Boolean) {
        store.apply(mapOf(ENABLED to enabled, ASKED to true), if (enabled) emptySet() else setOf(PENDING_CRASH))
    }

    companion object {
        const val ENABLED = "enabled"
        const val ASKED = "asked"
        /** Key (in the reporter's own preferences) holding a crash report waiting to be sent. */
        const val PENDING_CRASH = "pending_crash"
    }
}
