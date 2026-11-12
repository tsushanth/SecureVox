package com.securevox.app.util.funnel

/**
 * Where in the first-run and everyday flow a person got to. Names only: no step carries any content.
 *
 * This is "Tier A" of the funnel design: the steps are counted in a small ledger that lives on the phone
 * ([FunnelLedger]), is shown to the user under Settings > On this phone, and leaves the device only if the
 * user attaches it to a Report a Problem message they send themselves. Nothing here touches the network.
 *
 * [plain] is the English wording shown in the ledger screen and in the problem-report summary. It is kept in code,
 * not in string resources, on purpose: the same lines are read by the developer, who needs one language.
 */
enum class Step(val code: String, val plain: String) {
    FIRST_OPEN("first_open", "App opened for the first time"),
    SETUP_SHOWN("setup_shown", "Setup screen shown"),
    OFFLINE_AT_SETUP("offline_at_setup", "Setup showed the offline notice"),
    LANG_OFFER_SHOWN("lang_offer_shown", "Setup offered a better model for the language"),
    SIZE_CONFIRM_SHOWN("size_confirm_shown", "Large download confirmation shown"),
    SIZE_CONFIRM_CANCELLED("size_confirm_cancelled", "Large download confirmation cancelled"),
    DOWNLOAD_STARTED("download_started", "Model download started"),
    DOWNLOAD_COMPLETED("download_completed", "Model download finished"),
    DOWNLOAD_FAILED("download_failed", "Model download failed"),
    SETUP_SKIPPED("setup_skipped", "Setup skipped"),
    RECORD_BLOCKED_NO_MODEL("record_blocked_no_model", "Record or import tapped with no model installed"),
    MIC_RATIONALE_SHOWN("mic_rationale_shown", "Microphone explanation shown"),
    MIC_RATIONALE_CANCELLED("mic_rationale_cancelled", "Microphone explanation cancelled"),
    MIC_RESULT("mic_result", "Microphone permission answered"),
    NOTIF_PERMISSION("notif_permission", "Notification question answered"),
    FIRST_INPUT("first_input", "First recording or import"),
    RECORDING_STARTED("recording_started", "Recording started"),
    RECORDING_START_FAILED("recording_start_failed", "Recording could not start"),
    IMPORT_STARTED("import_started", "File import started"),
    IMPORT_FAILED("import_failed", "File import failed"),
    RECORDING_STOPPED("recording_stopped", "Recording stopped"),
    TRANSCRIPTION_STARTED("transcription_started", "Transcription started"),
    TRANSCRIPTION_COMPLETED("transcription_completed", "Transcription finished"),
    TRANSCRIPTION_FAILED("transcription_failed", "Transcription failed"),
    TRANSCRIPT_VIEWED("transcript_viewed", "Finished transcript opened"),
    VALUE_ACTION("value_action", "Copied, exported, shared or renamed"),
    RECORD_AGAIN("record_again", "Recorded again on a later day"),
    RETURNED("returned", "Came back after 1, 7 or 30 days"),
    LIVE_CAPTIONS("live_captions", "Live captions state for a recording"),
    SETTING_CHANGED("setting_changed", "A setting was changed"),
    REPORT_PROBLEM_OPENED("report_problem_opened", "Report a Problem opened"),

    // Rating seam: declared so the ledger and the summary know them; fed only once the app adapts RatingKit's
    // listener to [RatingFunnelEvent] (see RatingSeam.kt). Nothing in this app emits them yet.
    RATING_ELIGIBLE("rating_eligible", "Rating prompt became eligible"),
    RATING_BLOCKED("rating_blocked", "Rating prompt held back"),
    RATING_ASK("rating_ask", "Rating prompt requested"),
    RATING_FLOW_FAILED("rating_flow_failed", "Rating prompt could not open"),
    RATING_FLOW_RESULT("rating_flow_result", "Rating prompt finished");

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: String): Step? = byCode[code]
    }
}

/**
 * One closed-set fact about a step (a model, a failure class, a time bucket). Implemented only by enums, and
 * [Funnel.step] takes nothing but [Dim]s, so there is no way to pass free text (a title, a file name, a
 * transcript) into the ledger by accident.
 */
interface Dim {
    /** What this value describes, e.g. "model". */
    val group: String
    /** Stable machine value, e.g. "tiny". */
    val code: String
    /** English words for the ledger screen. */
    val plain: String
}

/** The one thing the rest of the app talks to. */
interface Funnel {
    /** Records that [step] happened, with optional closed-set [dims]. Must never throw and never block for long. */
    fun step(step: Step, vararg dims: Dim)

    /** Whether [step] has been recorded at least once (used to make "first time" steps fire once). */
    fun hasSeen(step: Step): Boolean = false

    /** The recorded entry for [step], or null. */
    fun entry(step: Step): StepEntry? = null

    /** How many times [dim] was recorded under [step]. */
    fun dimCount(step: Step, dim: Dim): Int = 0
}

/** Does nothing. The default before the app has set the ledger up, and in tests that do not care. */
object NoopFunnel : Funnel {
    override fun step(step: Step, vararg dims: Dim) = Unit
}

/** Records into a [FunnelLedger]. Any failure is swallowed: counting must never break the app. */
class LocalFunnel(private val ledger: FunnelLedger) : Funnel {
    override fun step(step: Step, vararg dims: Dim) {
        runCatching { ledger.record(step, *dims) }
    }

    override fun hasSeen(step: Step): Boolean = runCatching { ledger.has(step) }.getOrDefault(false)

    override fun entry(step: Step): StepEntry? = runCatching { ledger.entry(step) }.getOrNull()

    override fun dimCount(step: Step, dim: Dim): Int = runCatching { ledger.dimCount(step, dim) }.getOrDefault(0)
}

/** Process-wide access, set up once by the Application. Falls back to [NoopFunnel] (unit tests, previews). */
object FunnelHolder {
    @Volatile var current: Funnel = NoopFunnel
        private set

    /** The ledger behind [current], for the "On this phone" screen. Null when counting is not set up. */
    @Volatile var ledger: FunnelLedger? = null
        private set

    fun install(ledger: FunnelLedger) {
        this.ledger = ledger
        current = LocalFunnel(ledger)
    }

    /** For tests. */
    fun reset() {
        ledger = null
        current = NoopFunnel
    }
}

/** Shorthand used at every hook point. */
fun funnel(): Funnel = FunnelHolder.current
