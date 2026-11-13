package com.securevox.app.util.funnel

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Where the ledger keeps its text values. Android: a private SharedPreferences file. Tests: memory. */
interface LedgerStore {
    fun readAll(): Map<String, String>
    fun write(updates: Map<String, String>)
    fun clear()
}

class MemoryLedgerStore : LedgerStore {
    private val data = LinkedHashMap<String, String>()
    override fun readAll(): Map<String, String> = LinkedHashMap(data)
    override fun write(updates: Map<String, String>) { data.putAll(updates) }
    override fun clear() = data.clear()
}

/** How often a step happened and when the first and last time was (epoch ms, phone clock). */
data class StepEntry(val count: Int, val firstMs: Long, val lastMs: Long)

/** How often one closed-set value was recorded under a step. [dim] is null for a value this version does not know. */
data class DimTally(val group: String, val code: String, val count: Int) {
    val dim: Dim? get() = Dims.lookup(group, code)
}

data class StepStats(val step: Step, val entry: StepEntry, val dims: List<DimTally>)

data class LedgerSnapshot(val sinceMs: Long?, val steps: List<StepStats>) {
    val isEmpty: Boolean get() = steps.isEmpty()
}

/**
 * The local usage ledger: per step a count plus first and last time, and per (step, closed-set value) a count.
 * No content of any kind: step names and [Dim] enum values only, so the ledger is bounded by the size of
 * those enums. Pure Kotlin over a [LedgerStore] so it runs in plain JVM tests.
 */
class FunnelLedger(private val store: LedgerStore, private val clock: () -> Long) {

    private val lock = Any()
    private var cache: MutableMap<String, String>? = null

    private fun data(): MutableMap<String, String> =
        cache ?: store.readAll().toMutableMap().also { cache = it }

    fun record(step: Step, vararg dims: Dim) {
        val now = clock()
        synchronized(lock) {
            val d = data()
            val updates = LinkedHashMap<String, String>()
            val stepKey = stepKey(step)
            val old = parseEntry(d[stepKey])
            updates[stepKey] = formatEntry(
                if (old == null) StepEntry(1, now, now) else StepEntry((old.count + 1).coerceAtMost(MAX_COUNT), old.firstMs, now)
            )
            for (dim in dims) {
                val key = dimKey(step, dim.group, dim.code)
                updates[key] = (((d[key]?.toIntOrNull() ?: 0) + 1).coerceAtMost(MAX_COUNT)).toString()
            }
            if (!d.containsKey(META_SINCE)) updates[META_SINCE] = now.toString()
            d.putAll(updates)
            store.write(updates)
        }
    }

    fun has(step: Step): Boolean = entry(step) != null

    fun entry(step: Step): StepEntry? = synchronized(lock) { parseEntry(data()[stepKey(step)]) }

    fun dimCount(step: Step, dim: Dim): Int =
        synchronized(lock) { data()[dimKey(step, dim.group, dim.code)]?.toIntOrNull() ?: 0 }

    fun snapshot(): LedgerSnapshot = synchronized(lock) {
        val d = data()
        val steps = Step.entries.mapNotNull { step ->
            val entry = parseEntry(d[stepKey(step)]) ?: return@mapNotNull null
            val prefix = "d|${step.code}|"
            val tallies = d.entries
                .filter { it.key.startsWith(prefix) }
                .mapNotNull { (key, value) ->
                    val parts = key.removePrefix(prefix).split('|')
                    val count = value.toIntOrNull()
                    if (parts.size != 2 || count == null) null else DimTally(parts[0], parts[1], count)
                }
                .sortedWith(compareBy({ it.group }, { -it.count }, { it.code }))
            StepStats(step, entry, tallies)
        }
        LedgerSnapshot(d[META_SINCE]?.toLongOrNull(), steps)
    }

    /** Forgets everything, including which "first time" steps already happened. */
    fun reset() = synchronized(lock) {
        store.clear()
        cache = mutableMapOf()
    }

    companion object {
        const val MAX_COUNT = 1_000_000
        private const val META_SINCE = "meta|since"
        private fun stepKey(step: Step) = "s|${step.code}"
        private fun dimKey(step: Step, group: String, code: String) = "d|${step.code}|$group|$code"

        internal fun parseEntry(raw: String?): StepEntry? {
            val p = raw?.split(',') ?: return null
            if (p.size != 3) return null
            val count = p[0].toIntOrNull() ?: return null
            val first = p[1].toLongOrNull() ?: return null
            val last = p[2].toLongOrNull() ?: return null
            return if (count < 1) null else StepEntry(count, first, last)
        }

        private fun formatEntry(e: StepEntry) = "${e.count},${e.firstMs},${e.lastMs}"
    }
}

/** The ledger in plain words, for the "On this phone" screen and for the Report a Problem message. */
object LedgerSummary {

    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

    fun formatTime(ms: Long, zone: ZoneId): String = stamp.format(Instant.ofEpochMilli(ms).atZone(zone))

    /** "model: Tiny 3, Base 1; language: English 4" or "" when the step has no values. */
    fun dimsText(stats: StepStats): String =
        stats.dims.groupBy { it.group }.entries.joinToString("; ") { (group, tallies) ->
            group.replace('_', ' ') + ": " + tallies.joinToString(", ") { "${it.dim?.plain ?: it.code} ${it.count}" }
        }

    /** First and last time, or just one when they are the same minute. */
    fun whenText(entry: StepEntry, zone: ZoneId): String {
        val first = formatTime(entry.firstMs, zone)
        val last = formatTime(entry.lastMs, zone)
        return if (entry.count == 1 || first == last) first else "first $first, last $last"
    }

    /**
     * Plain-text block appended to a Report a Problem message. Shown to the user in their own mail draft before
     * anything is sent. Contains step names, counts, dates and closed-set values only.
     */
    fun reportText(snapshot: LedgerSnapshot, appVersion: String, zone: ZoneId): String = buildString {
        append("Setup and usage summary (counts and dates only: no recordings, text, titles or file names)\n")
        append("App version: ").append(appVersion).append('\n')
        snapshot.sinceMs?.let { append("Counting since: ").append(formatTime(it, zone)).append('\n') }
        if (snapshot.isEmpty) {
            append("Nothing counted yet.\n")
        } else {
            for (s in snapshot.steps) {
                append("- ").append(s.step.plain).append(": ").append(s.entry.count).append("x (")
                append(whenText(s.entry, zone)).append(')')
                val dims = dimsText(s)
                if (dims.isNotEmpty()) append(" [").append(dims).append(']')
                append('\n')
            }
        }
    }
}

/** The text of the Report a Problem message: the user's own space, the usage summary, then the diagnostic log. */
object ProblemReportText {
    const val INTRO = "Please describe the problem below."
    const val MAX_INLINE_LOG = 20_000

    /** [logInline] false means the log is too big for the message body and must be attached as a file instead. */
    data class Body(val text: String, val logInline: Boolean)

    fun build(summary: String, logText: String): Body {
        val summaryBlock = if (summary.isBlank()) "" else "\n\n---\n" + summary.trimEnd()
        return if (logText.isNotBlank() && logText.length <= MAX_INLINE_LOG) {
            Body("$INTRO$summaryBlock\n\n---\nDiagnostic log:\n\n$logText", logInline = true)
        } else {
            Body("$INTRO$summaryBlock", logInline = false)
        }
    }
}
