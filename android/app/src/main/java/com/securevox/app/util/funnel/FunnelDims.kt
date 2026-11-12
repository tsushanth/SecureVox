package com.securevox.app.util.funnel

import com.securevox.app.whisper.DownloadProblem
import com.securevox.app.whisper.QualityTier
import com.securevox.app.whisper.WhisperLanguage
import com.securevox.app.whisper.WhisperModel

// Every value the ledger can hold besides a step name. All closed sets (enums), see [Dim].

enum class FunnelModel(override val code: String, override val plain: String) : Dim {
    TINY("tiny", "Tiny"), BASE("base", "Base"), SMALL("small", "Small");

    override val group get() = "model"

    companion object {
        fun of(model: WhisperModel): FunnelModel = when (model) {
            WhisperModel.TINY -> TINY
            WhisperModel.BASE -> BASE
            WhisperModel.SMALL -> SMALL
        }
    }
}

/** How the app was opened the first time. [UPDATED_INSTALL] = installed before this counting existed. */
enum class OpenSource(override val code: String, override val plain: String) : Dim {
    LAUNCHER("launcher", "from the launcher"),
    SHARE("share", "from a shared file"),
    TILE("tile", "from the quick-settings tile"),
    UPDATED_INSTALL("updated_install", "after an update of an older install");

    override val group get() = "source"
}

/** Which door led to the setup screen. */
enum class SetupEntry(override val code: String, override val plain: String) : Dim {
    FIRST_RUN("first_run", "first run"),
    BANNER("banner", "from the no-model banner"),
    RECORD_TAP("record_tap", "after tapping Record without a model");

    override val group get() = "entry"
}

/** The language the person transcribes in, as how well the model in use handles it (never which language). */
enum class LangBucket(override val code: String, override val plain: String) : Dim {
    EN("en", "English"),
    AUTO("auto", "auto-detect"),
    NON_EN_GOOD("non_en_good", "other language, good fit"),
    NON_EN_FAIR("non_en_fair", "other language, fair fit"),
    NON_EN_POOR("non_en_poor", "other language, poor fit");

    override val group get() = "language"

    companion object {
        fun of(language: WhisperLanguage, model: WhisperModel): LangBucket = when {
            language == WhisperLanguage.ENGLISH -> EN
            language == WhisperLanguage.AUTO -> AUTO
            else -> when (language.tierFor(model)) {
                QualityTier.GOOD -> NON_EN_GOOD
                QualityTier.FAIR -> NON_EN_FAIR
                QualityTier.POOR -> NON_EN_POOR
            }
        }
    }
}

enum class DlProblem(override val code: String, override val plain: String) : Dim {
    OFFLINE("offline", "offline"),
    TIMEOUT("timeout", "timed out"),
    STORAGE_FULL("storage_full", "storage full"),
    SERVER("server", "server error"),
    INCOMPLETE("incomplete", "incomplete file"),
    CHECKSUM("checksum", "checksum mismatch"),
    OTHER("other", "other");

    override val group get() = "problem"

    companion object {
        fun of(problem: DownloadProblem): DlProblem = when (problem) {
            DownloadProblem.OFFLINE -> OFFLINE
            DownloadProblem.TIMEOUT -> TIMEOUT
            DownloadProblem.STORAGE_FULL -> STORAGE_FULL
            DownloadProblem.SERVER -> SERVER
            DownloadProblem.INCOMPLETE -> INCOMPLETE
            DownloadProblem.CHECKSUM -> CHECKSUM
            DownloadProblem.OTHER -> OTHER
        }
    }
}

enum class Resumed(override val code: String, override val plain: String) : Dim {
    YES("yes", "resumed a partial file"), NO("no", "from the start");

    override val group get() = "resumed"
}

enum class MicResult(override val code: String, override val plain: String) : Dim {
    GRANTED("granted", "granted"),
    DENIED("denied", "denied"),
    BLOCKED_SETTINGS("blocked_settings", "blocked, sent to system settings");

    override val group get() = "result"
}

enum class NotifResult(override val code: String, override val plain: String) : Dim {
    GRANTED("granted", "allowed"),
    DENIED("denied", "not allowed"),
    NOT_NOW("not_now", "not now / dismissed");

    override val group get() = "notifications"
}

/** What the first recording or import came through. */
enum class InputVia(override val code: String, override val plain: String) : Dim {
    RECORD("record", "recording"),
    IMPORT_PICKER("import_picker", "file picker"),
    IMPORT_SHARE("import_share", "shared in from another app");

    override val group get() = "via"
}

enum class StartFailClass(override val code: String, override val plain: String) : Dim {
    NO_PERMISSION("no_permission", "no microphone permission"),
    BAD_BUFFER("bad_buffer", "bad audio buffer"),
    MIC_UNAVAILABLE("mic_unavailable", "microphone unavailable"),
    EXCEPTION("exception", "error");

    override val group get() = "start_error"
}

enum class ImportFailClass(override val code: String, override val plain: String) : Dim {
    TOO_LARGE("too_large", "file too large"),
    UNSUPPORTED("unsupported", "unsupported type"),
    UNREADABLE("unreadable", "could not read or decode"),
    OTHER("other", "other");

    override val group get() = "import_error"

    companion object {
        /** The importer reports a message, not a type; its stable openings tell the classes apart. */
        fun classify(message: String?): ImportFailClass {
            val m = message.orEmpty()
            return when {
                m.startsWith("File too large") -> TOO_LARGE
                m.startsWith("Unsupported file type") || m.startsWith("Could not determine file type") -> UNSUPPORTED
                m.startsWith("Could not open file") || m.startsWith("Import failed") || m.contains("audio track", true) -> UNREADABLE
                else -> OTHER
            }
        }
    }
}

enum class DurBucket(override val code: String, override val plain: String) : Dim {
    LT_5S("lt5s", "under 5 seconds"),
    S5_30("5_30s", "5-30 seconds"),
    S30_2M("30s_2m", "30 seconds to 2 minutes"),
    M2_10("2_10m", "2-10 minutes"),
    M10_60("10_60m", "10-60 minutes"),
    GT_60M("gt60m", "over an hour");

    override val group get() = "length"
}

/** Time a step took, as a coarse bucket. */
enum class TtcBucket(override val code: String, override val plain: String) : Dim {
    LT_1M("lt1m", "under 1 minute"),
    M1_3("1_3m", "1-3 minutes"),
    M3_10("3_10m", "3-10 minutes"),
    M10_30("10_30m", "10-30 minutes"),
    M30_60("30_60m", "30-60 minutes"),
    GT_60M("gt60m", "over an hour");

    override val group get() = "took"
}

/** Processing time relative to audio length. */
enum class RtfBucket(override val code: String, override val plain: String) : Dim {
    LT_05("lt0_5x", "faster than half the audio length"),
    X05_1("0_5_1x", "about half to 1x the audio length"),
    X1_2("1_2x", "1-2x the audio length"),
    X2_5("2_5x", "2-5x the audio length"),
    GT_5("gt5x", "over 5x the audio length");

    override val group get() = "speed"
}

/** How long after the first recording the next one came. */
enum class GapBucket(override val code: String, override val plain: String) : Dim {
    D1("d1", "the next day"),
    D2_7("d2_7", "2-7 days later"),
    D8_30("d8_30", "8-30 days later"),
    GT_30("gt30", "over 30 days later");

    override val group get() = "gap"
}

enum class Trigger(override val code: String, override val plain: String) : Dim {
    AUTO("auto", "after recording"),
    IMPORT("import", "after importing a file"),
    RETRY("retry", "retry"),
    RETRANSCRIBE("retranscribe", "re-run with another model"),
    RECOVERY("recovery", "recovered an interrupted recording");

    override val group get() = "trigger"

    companion object {
        fun fromCode(code: String?): Trigger = entries.firstOrNull { it.code == code } ?: AUTO
    }
}

enum class TranscribeFailClass(override val code: String, override val plain: String) : Dim {
    MODEL_MISSING("model_missing", "model missing"),
    MODEL_INIT_FAILED("model_init_failed", "model would not load"),
    AUDIO_UNREADABLE("audio_unreadable", "audio unreadable"),
    OOM("oom", "out of memory"),
    OTHER("other", "other error");

    override val group get() = "failure"
}

enum class RunStage(override val code: String, override val plain: String) : Dim {
    EARLY("early", "in the first 10 seconds"), RUNNING("running", "later");

    override val group get() = "stage"
}

enum class YesNo(override val code: String, override val plain: String) : Dim {
    YES("yes", "yes"), NO("no", "no");

    override val group get() = "empty"
}

enum class ValueKind(override val code: String, override val plain: String) : Dim {
    COPY("copy", "copy transcript"),
    EXPORT_TXT("export_txt", "export .txt"),
    EXPORT_SRT("export_srt", "export .srt"),
    EXPORT_VTT("export_vtt", "export .vtt"),
    SHARE_AUDIO("share_audio", "share audio"),
    RENAME("rename", "rename");

    override val group get() = "kind"
}

enum class ReturnDay(override val code: String, override val plain: String) : Dim {
    D1("d1", "day 1"), D7("d7", "day 7"), D30("d30", "day 30");

    override val group get() = "day"

    val days: Int get() = when (this) { D1 -> 1; D7 -> 7; D30 -> 30 }
}

enum class LiveState(override val code: String, override val plain: String) : Dim {
    ON("on", "on"), OFF("off", "off"), SKIPPED_LOW_MEMORY("skipped_low_memory", "skipped, low memory");

    override val group get() = "captions"
}

enum class SettingWhich(override val code: String, override val plain: String) : Dim {
    LANGUAGE("language", "language"),
    MODEL("model", "model"),
    MODEL_DELETE("model_delete", "model deleted"),
    SPEAKERS("speakers", "speaker labels"),
    APP_LOCK("app_lock", "app lock"),
    LIVE_CAPTIONS("live_captions", "live captions");

    override val group get() = "which"
}

enum class SettingTo(override val code: String, override val plain: String) : Dim {
    ON("on", "on"), OFF("off", "off");

    override val group get() = "to"
}

enum class RatingShown(override val code: String, override val plain: String) : Dim {
    SHOWN("shown", "shown"), NOT_SHOWN("not_shown", "not shown");

    override val group get() = "shown"
}

enum class RatingReason(override val code: String, override val plain: String) : Dim {
    TOO_EARLY("too_early", "too early (days of use)"),
    TOO_FEW_MOMENTS("too_few_moments", "too few good moments"),
    ASK_LIMIT("ask_limit", "asked enough times"),
    TOO_SOON_AFTER_ASK("too_soon_after_ask", "asked too recently"),
    RECENT_ERROR("recent_error", "a failure happened recently"),
    OTHER("other", "other");

    override val group get() = "reason"
}

/** Every [Dim] value, so stored (group, code) pairs can be turned back into words. */
object Dims {
    val all: List<Dim> = listOf<Array<out Dim>>(
        FunnelModel.entries.toTypedArray(), OpenSource.entries.toTypedArray(), SetupEntry.entries.toTypedArray(),
        LangBucket.entries.toTypedArray(), DlProblem.entries.toTypedArray(), Resumed.entries.toTypedArray(),
        MicResult.entries.toTypedArray(), NotifResult.entries.toTypedArray(), InputVia.entries.toTypedArray(),
        StartFailClass.entries.toTypedArray(), ImportFailClass.entries.toTypedArray(), DurBucket.entries.toTypedArray(),
        TtcBucket.entries.toTypedArray(), RtfBucket.entries.toTypedArray(), GapBucket.entries.toTypedArray(),
        Trigger.entries.toTypedArray(), TranscribeFailClass.entries.toTypedArray(), RunStage.entries.toTypedArray(),
        YesNo.entries.toTypedArray(), ValueKind.entries.toTypedArray(), ReturnDay.entries.toTypedArray(),
        LiveState.entries.toTypedArray(), SettingWhich.entries.toTypedArray(), SettingTo.entries.toTypedArray(),
        RatingShown.entries.toTypedArray(), RatingReason.entries.toTypedArray()
    ).flatMap { it.asList() }

    private val index = all.groupBy { it.group }.mapValues { (_, v) -> v.associateBy { it.code } }

    fun lookup(group: String, code: String): Dim? = index[group]?.get(code)
}
