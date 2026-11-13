package com.securevox.app.util.funnel

import com.securevox.app.util.MicStep
import com.securevox.app.whisper.WhisperLanguage
import com.securevox.app.whisper.WhisperModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Value-to-bucket functions. Buckets are closed sets so a ledger can never hold an exact length or time. */
object Buckets {
    fun dur(ms: Long): DurBucket = when {
        ms < 5_000L -> DurBucket.LT_5S
        ms < 30_000L -> DurBucket.S5_30
        ms < 120_000L -> DurBucket.S30_2M
        ms < 600_000L -> DurBucket.M2_10
        ms < 3_600_000L -> DurBucket.M10_60
        else -> DurBucket.GT_60M
    }

    fun took(ms: Long): TtcBucket = when {
        ms < 60_000L -> TtcBucket.LT_1M
        ms < 180_000L -> TtcBucket.M1_3
        ms < 600_000L -> TtcBucket.M3_10
        ms < 1_800_000L -> TtcBucket.M10_30
        ms < 3_600_000L -> TtcBucket.M30_60
        else -> TtcBucket.GT_60M
    }

    /** Processing time over audio length. Null when the audio length is unknown or zero. */
    fun speed(elapsedMs: Long, audioMs: Long): RtfBucket? {
        if (audioMs <= 0L || elapsedMs < 0L) return null
        val r = elapsedMs.toDouble() / audioMs.toDouble()
        return when {
            r < 0.5 -> RtfBucket.LT_05
            r < 1.0 -> RtfBucket.X05_1
            r < 2.0 -> RtfBucket.X1_2
            r < 5.0 -> RtfBucket.X2_5
            else -> RtfBucket.GT_5
        }
    }

    /** A failure in the first 10 seconds is almost always setup (model, memory), later ones are the run itself. */
    fun stage(elapsedMs: Long): RunStage = if (elapsedMs < 10_000L) RunStage.EARLY else RunStage.RUNNING

    fun yesNo(value: Boolean): YesNo = if (value) YesNo.YES else YesNo.NO

    fun toggle(on: Boolean): SettingTo = if (on) SettingTo.ON else SettingTo.OFF
}

/** Decides when a person counts as having come back: the highest of day 1 / 7 / 30 reached, once each. */
object ReturnPolicy {
    /**
     * Whole local days since [installMs]. [alreadyFired] says whether a [ReturnDay] was recorded before. A lower
     * day is never recorded after a higher one, so someone first back on day 8 counts as day 7, once.
     */
    fun dueDay(installMs: Long, nowMs: Long, zone: ZoneId, alreadyFired: (ReturnDay) -> Boolean): ReturnDay? {
        val days = ChronoUnit.DAYS.between(localDate(installMs, zone), localDate(nowMs, zone))
        val candidate = ReturnDay.entries.sortedByDescending { it.days }.firstOrNull { days >= it.days } ?: return null
        if (ReturnDay.entries.any { it.days >= candidate.days && alreadyFired(it) }) return null
        return candidate
    }
}

/** How long after the first recording the next recording on a later day came. */
object RecordAgainPolicy {
    /** Null when [nowMs] is still the same local day as [firstMs]. */
    fun gap(firstMs: Long, nowMs: Long, zone: ZoneId): GapBucket? {
        val days = ChronoUnit.DAYS.between(localDate(firstMs, zone), localDate(nowMs, zone))
        return when {
            days < 1 -> null
            days == 1L -> GapBucket.D1
            days <= 7 -> GapBucket.D2_7
            days <= 30 -> GapBucket.D8_30
            else -> GapBucket.GT_30
        }
    }
}

private fun localDate(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

/**
 * The decisions behind the hook points that need more than "record this step": first-time steps, the
 * return / record-again rules, and the closed-set values each step carries. Everything here is pure over a
 * [Funnel], so the JVM tests cover what a hook would do without any Android classes.
 */
object FunnelHooks {

    /** A launch counts as an update of an older install when the package was updated well after it was installed. */
    const val UPDATE_GAP_MS = 60_000L

    fun onAppOpened(
        f: Funnel,
        firstInstallMs: Long,
        lastUpdateMs: Long,
        launchedAs: OpenSource,
        nowMs: Long,
        zone: ZoneId
    ) {
        if (!f.hasSeen(Step.FIRST_OPEN)) {
            val updated = lastUpdateMs - firstInstallMs > UPDATE_GAP_MS
            f.step(Step.FIRST_OPEN, if (updated) OpenSource.UPDATED_INSTALL else launchedAs)
            return // the first open is never also a "came back"
        }
        // Installs that predate the ledger have no real day 0, so their return days would be meaningless.
        if (f.dimCount(Step.FIRST_OPEN, OpenSource.UPDATED_INSTALL) > 0) return
        val due = ReturnPolicy.dueDay(firstInstallMs, nowMs, zone) { f.dimCount(Step.RETURNED, it) > 0 }
        if (due != null) f.step(Step.RETURNED, due)
    }

    /** First recording or import, whichever comes first, once. */
    fun firstInput(f: Funnel, via: InputVia) {
        if (!f.hasSeen(Step.FIRST_INPUT)) f.step(Step.FIRST_INPUT, via)
    }

    fun onRecordingStarted(f: Funnel, nowMs: Long, zone: ZoneId) {
        val first = f.entry(Step.RECORDING_STARTED)?.firstMs
        if (first != null && !f.hasSeen(Step.RECORD_AGAIN)) {
            RecordAgainPolicy.gap(first, nowMs, zone)?.let { f.step(Step.RECORD_AGAIN, it) }
        }
        f.step(Step.RECORDING_STARTED)
        firstInput(f, InputVia.RECORD)
    }

    fun onImportStarted(f: Funnel, via: InputVia) {
        f.step(Step.IMPORT_STARTED, via)
        firstInput(f, via)
    }

    fun onTranscriptionStarted(f: Funnel, model: WhisperModel, language: WhisperLanguage, trigger: Trigger, audioMs: Long) {
        f.step(
            Step.TRANSCRIPTION_STARTED,
            FunnelModel.of(model), LangBucket.of(language, model), trigger, Buckets.dur(audioMs)
        )
    }

    fun onTranscriptionCompleted(f: Funnel, model: WhisperModel, elapsedMs: Long, audioMs: Long, noSpeech: Boolean) {
        val dims = mutableListOf<Dim>(FunnelModel.of(model), Buckets.took(elapsedMs), Buckets.yesNo(noSpeech))
        Buckets.speed(elapsedMs, audioMs)?.let { dims += it }
        f.step(Step.TRANSCRIPTION_COMPLETED, *dims.toTypedArray())
    }

    fun onTranscriptionFailed(f: Funnel, model: WhisperModel?, failure: TranscribeFailClass, elapsedMs: Long) {
        val dims = mutableListOf<Dim>(failure, Buckets.stage(elapsedMs))
        model?.let { dims += FunnelModel.of(it) }
        f.step(Step.TRANSCRIPTION_FAILED, *dims.toTypedArray())
    }

    fun onDownloadStarted(f: Funnel, model: WhisperModel, resumedBytes: Long) {
        f.step(Step.DOWNLOAD_STARTED, FunnelModel.of(model), if (resumedBytes > 0L) Resumed.YES else Resumed.NO)
    }

    /** [tookMs] is null for a download that had already finished before this attempt (nothing was timed). */
    fun onDownloadCompleted(f: Funnel, model: WhisperModel, tookMs: Long?) {
        if (tookMs == null) f.step(Step.DOWNLOAD_COMPLETED, FunnelModel.of(model))
        else f.step(Step.DOWNLOAD_COMPLETED, FunnelModel.of(model), Buckets.took(tookMs))
    }

    /** A permission-dialog answer. */
    fun micResultFromDialog(granted: Boolean): MicResult = if (granted) MicResult.GRANTED else MicResult.DENIED

    /** The tap on Record when Android will no longer show its own prompt is its own outcome. */
    fun micResultForTap(step: MicStep): MicResult? = if (step == MicStep.OPEN_SETTINGS) MicResult.BLOCKED_SETTINGS else null

    fun notifResultFromDialog(granted: Boolean): NotifResult = if (granted) NotifResult.GRANTED else NotifResult.DENIED

    fun valueKindForExport(extension: String): ValueKind? = when (extension.lowercase()) {
        "txt" -> ValueKind.EXPORT_TXT
        "srt" -> ValueKind.EXPORT_SRT
        "vtt" -> ValueKind.EXPORT_VTT
        else -> null
    }
}
