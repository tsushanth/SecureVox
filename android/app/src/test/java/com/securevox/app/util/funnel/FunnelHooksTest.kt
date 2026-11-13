package com.securevox.app.util.funnel

import com.securevox.app.util.MicStep
import com.securevox.app.whisper.DownloadProblem
import com.securevox.app.whisper.WhisperLanguage
import com.securevox.app.whisper.WhisperModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class FunnelHooksTest {

    private val zone = ZoneId.of("America/Los_Angeles")
    private fun at(date: String, hour: Int = 12): Long =
        LocalDateTime.of(LocalDate.parse(date), java.time.LocalTime.of(hour, 0)).atZone(zone).toInstant().toEpochMilli()

    private var clock = 0L
    private val f = LocalFunnel(FunnelLedger(MemoryLedgerStore()) { clock })

    // ---- first open and coming back -------------------------------------------------------------------------

    @Test
    fun firstOpenFiresOnceWithHowItWasOpened() {
        val install = at("2026-11-14")
        FunnelHooks.onAppOpened(f, install, install, OpenSource.SHARE, install, zone)
        FunnelHooks.onAppOpened(f, install, install, OpenSource.LAUNCHER, install + 1000, zone)
        assertEquals(1, f.entry(Step.FIRST_OPEN)!!.count)
        assertEquals(1, f.dimCount(Step.FIRST_OPEN, OpenSource.SHARE))
        assertEquals(0, f.dimCount(Step.FIRST_OPEN, OpenSource.LAUNCHER))
    }

    @Test
    fun anOlderInstallUpdatedToThisVersionIsTaggedNotCountedAsNew() {
        val install = at("2026-09-01")
        val update = at("2026-11-12")
        FunnelHooks.onAppOpened(f, install, update, OpenSource.LAUNCHER, at("2026-11-14"), zone)
        assertEquals(1, f.dimCount(Step.FIRST_OPEN, OpenSource.UPDATED_INSTALL))
        assertEquals(0, f.dimCount(Step.FIRST_OPEN, OpenSource.LAUNCHER))
        // Such an install has no real day 0, so it never produces "came back" steps.
        FunnelHooks.onAppOpened(f, install, update, OpenSource.LAUNCHER, at("2026-12-20"), zone)
        assertFalse(f.hasSeen(Step.RETURNED))
    }

    @Test
    fun freshInstallWithinAMinuteOfUpdateTimeIsNew() {
        val install = at("2026-11-14")
        FunnelHooks.onAppOpened(f, install, install + FunnelHooks.UPDATE_GAP_MS, OpenSource.TILE, install, zone)
        assertEquals(1, f.dimCount(Step.FIRST_OPEN, OpenSource.TILE))
    }

    @Test
    fun theFirstOpenIsNeverAlsoAComeBack() {
        val install = at("2026-11-01")
        FunnelHooks.onAppOpened(f, install, install, OpenSource.LAUNCHER, at("2026-11-20"), zone)
        assertFalse(f.hasSeen(Step.RETURNED))
    }

    @Test
    fun returnedFiresOncePerDayMarkAndOnlyForTheHighestReached() {
        val install = at("2026-11-01")
        FunnelHooks.onAppOpened(f, install, install, OpenSource.LAUNCHER, install, zone)
        FunnelHooks.onAppOpened(f, install, install, OpenSource.LAUNCHER, at("2026-11-01", 18), zone) // same day
        assertFalse(f.hasSeen(Step.RETURNED))
        FunnelHooks.onAppOpened(f, install, install, OpenSource.LAUNCHER, at("2026-11-02"), zone)
        FunnelHooks.onAppOpened(f, install, install, OpenSource.LAUNCHER, at("2026-11-03"), zone)
        assertEquals(1, f.dimCount(Step.RETURNED, ReturnDay.D1))
        // First back on day 8: counts as day 7 (not also day 1 again), once.
        FunnelHooks.onAppOpened(f, install, install, OpenSource.LAUNCHER, at("2026-11-09"), zone)
        FunnelHooks.onAppOpened(f, install, install, OpenSource.LAUNCHER, at("2026-11-10"), zone)
        assertEquals(1, f.dimCount(Step.RETURNED, ReturnDay.D1))
        assertEquals(1, f.dimCount(Step.RETURNED, ReturnDay.D7))
        FunnelHooks.onAppOpened(f, install, install, OpenSource.LAUNCHER, at("2026-12-05"), zone)
        assertEquals(1, f.dimCount(Step.RETURNED, ReturnDay.D30))
        assertEquals(3, f.entry(Step.RETURNED)!!.count)
    }

    @Test
    fun returnPolicyRules() {
        val install = at("2026-11-01")
        val none = { _: ReturnDay -> false }
        assertNull(ReturnPolicy.dueDay(install, at("2026-11-01", 23), zone, none))
        assertEquals(ReturnDay.D1, ReturnPolicy.dueDay(install, at("2026-11-02", 1), zone, none))
        assertEquals(ReturnDay.D1, ReturnPolicy.dueDay(install, at("2026-11-07"), zone, none))
        assertEquals(ReturnDay.D7, ReturnPolicy.dueDay(install, at("2026-11-08"), zone, none))
        assertEquals(ReturnDay.D30, ReturnPolicy.dueDay(install, at("2026-12-01"), zone, none))
        // A higher day already recorded blocks every lower one.
        assertNull(ReturnPolicy.dueDay(install, at("2026-11-09"), zone) { it == ReturnDay.D7 })
        assertNull(ReturnPolicy.dueDay(install, at("2026-11-09"), zone) { it == ReturnDay.D30 })
        // A clock set before the install date produces nothing.
        assertNull(ReturnPolicy.dueDay(install, at("2026-10-01"), zone, none))
    }

    @Test
    fun daysAreLocalDaysNotTwentyFourHourBlocks() {
        // 23:30 one day to 00:30 the next is one local day later although only an hour passed.
        val install = LocalDateTime.of(2026, 11, 1, 23, 30).atZone(zone).toInstant().toEpochMilli()
        val now = LocalDateTime.of(2026, 11, 2, 0, 30).atZone(zone).toInstant().toEpochMilli()
        assertEquals(ReturnDay.D1, ReturnPolicy.dueDay(install, now, zone) { false })
    }

    // ---- recording, first input, record again ---------------------------------------------------------------

    @Test
    fun firstInputIsWhicheverCameFirstAndOnlyOnce() {
        FunnelHooks.onImportStarted(f, InputVia.IMPORT_SHARE)
        FunnelHooks.onRecordingStarted(f, at("2026-11-14"), zone)
        assertEquals(1, f.entry(Step.FIRST_INPUT)!!.count)
        assertEquals(1, f.dimCount(Step.FIRST_INPUT, InputVia.IMPORT_SHARE))
        assertEquals(0, f.dimCount(Step.FIRST_INPUT, InputVia.RECORD))
        assertEquals(1, f.entry(Step.IMPORT_STARTED)!!.count)
        assertEquals(1, f.entry(Step.RECORDING_STARTED)!!.count)
    }

    @Test
    fun firstRecordingFiresFirstInputRecord() {
        FunnelHooks.onRecordingStarted(f, at("2026-11-14"), zone)
        assertEquals(1, f.dimCount(Step.FIRST_INPUT, InputVia.RECORD))
        assertFalse(f.hasSeen(Step.RECORD_AGAIN))
    }

    @Test
    fun recordAgainNeedsALaterLocalDayAndFiresOnce() {
        clock = at("2026-11-14")
        FunnelHooks.onRecordingStarted(f, clock, zone)
        clock = at("2026-11-14", 20)
        FunnelHooks.onRecordingStarted(f, clock, zone) // same local day: no
        assertFalse(f.hasSeen(Step.RECORD_AGAIN))
        clock = at("2026-11-16")
        FunnelHooks.onRecordingStarted(f, clock, zone)
        assertEquals(1, f.dimCount(Step.RECORD_AGAIN, GapBucket.D2_7))
        clock = at("2026-12-30")
        FunnelHooks.onRecordingStarted(f, clock, zone)
        assertEquals(1, f.entry(Step.RECORD_AGAIN)!!.count)
        assertEquals(4, f.entry(Step.RECORDING_STARTED)!!.count)
    }

    @Test
    fun recordAgainGapBuckets() {
        val first = at("2026-11-01")
        assertNull(RecordAgainPolicy.gap(first, at("2026-11-01", 23), zone))
        assertEquals(GapBucket.D1, RecordAgainPolicy.gap(first, at("2026-11-02"), zone))
        assertEquals(GapBucket.D2_7, RecordAgainPolicy.gap(first, at("2026-11-03"), zone))
        assertEquals(GapBucket.D2_7, RecordAgainPolicy.gap(first, at("2026-11-08"), zone))
        assertEquals(GapBucket.D8_30, RecordAgainPolicy.gap(first, at("2026-11-09"), zone))
        assertEquals(GapBucket.D8_30, RecordAgainPolicy.gap(first, at("2026-12-01"), zone))
        assertEquals(GapBucket.GT_30, RecordAgainPolicy.gap(first, at("2026-12-02"), zone))
    }

    // ---- transcription ----------------------------------------------------------------------------------------

    @Test
    fun transcriptionStartedCarriesModelLanguageFitTriggerAndLength() {
        FunnelHooks.onTranscriptionStarted(f, WhisperModel.TINY, WhisperLanguage.FRENCH, Trigger.RETRY, 90_000L)
        assertEquals(1, f.dimCount(Step.TRANSCRIPTION_STARTED, FunnelModel.TINY))
        assertEquals(1, f.dimCount(Step.TRANSCRIPTION_STARTED, LangBucket.NON_EN_POOR)) // Tiny is POOR for French
        assertEquals(1, f.dimCount(Step.TRANSCRIPTION_STARTED, Trigger.RETRY))
        assertEquals(1, f.dimCount(Step.TRANSCRIPTION_STARTED, DurBucket.S30_2M))
    }

    @Test
    fun transcriptionCompletedCarriesSpeedAndNoSpeech() {
        FunnelHooks.onTranscriptionCompleted(f, WhisperModel.BASE, elapsedMs = 150_000L, audioMs = 60_000L, noSpeech = false)
        assertEquals(1, f.dimCount(Step.TRANSCRIPTION_COMPLETED, FunnelModel.BASE))
        assertEquals(1, f.dimCount(Step.TRANSCRIPTION_COMPLETED, TtcBucket.M1_3))
        assertEquals(1, f.dimCount(Step.TRANSCRIPTION_COMPLETED, RtfBucket.X2_5))
        assertEquals(1, f.dimCount(Step.TRANSCRIPTION_COMPLETED, YesNo.NO))
        // Unknown audio length: no speed value rather than a made-up one.
        FunnelHooks.onTranscriptionCompleted(f, WhisperModel.BASE, 1_000L, 0L, noSpeech = true)
        assertEquals(1, f.dimCount(Step.TRANSCRIPTION_COMPLETED, YesNo.YES))
        assertEquals(1, f.dimCount(Step.TRANSCRIPTION_COMPLETED, RtfBucket.X2_5))
    }

    @Test
    fun transcriptionFailureHasClassAndStageAndOptionalModel() {
        FunnelHooks.onTranscriptionFailed(f, WhisperModel.SMALL, TranscribeFailClass.OOM, elapsedMs = 4_000L)
        FunnelHooks.onTranscriptionFailed(f, null, TranscribeFailClass.OTHER, elapsedMs = 60_000L)
        assertEquals(2, f.entry(Step.TRANSCRIPTION_FAILED)!!.count)
        assertEquals(1, f.dimCount(Step.TRANSCRIPTION_FAILED, TranscribeFailClass.OOM))
        assertEquals(1, f.dimCount(Step.TRANSCRIPTION_FAILED, RunStage.EARLY))
        assertEquals(1, f.dimCount(Step.TRANSCRIPTION_FAILED, RunStage.RUNNING))
        assertEquals(1, f.dimCount(Step.TRANSCRIPTION_FAILED, FunnelModel.SMALL))
    }

    @Test
    fun triggerFromWorkDataFallsBackToAuto() {
        assertEquals(Trigger.RETRANSCRIBE, Trigger.fromCode("retranscribe"))
        assertEquals(Trigger.AUTO, Trigger.fromCode(null))
        assertEquals(Trigger.AUTO, Trigger.fromCode("something else"))
        Trigger.entries.forEach { assertEquals(it, Trigger.fromCode(it.code)) }
    }

    // ---- downloads --------------------------------------------------------------------------------------------

    @Test
    fun downloadStartedSaysWhetherItResumed() {
        FunnelHooks.onDownloadStarted(f, WhisperModel.TINY, 0L)
        FunnelHooks.onDownloadStarted(f, WhisperModel.TINY, 5_000_000L)
        assertEquals(1, f.dimCount(Step.DOWNLOAD_STARTED, Resumed.NO))
        assertEquals(1, f.dimCount(Step.DOWNLOAD_STARTED, Resumed.YES))
    }

    @Test
    fun downloadCompletedHasATimeBucketOnlyWhenItWasTimed() {
        FunnelHooks.onDownloadCompleted(f, WhisperModel.TINY, 200_000L)
        FunnelHooks.onDownloadCompleted(f, WhisperModel.TINY, null)
        assertEquals(2, f.entry(Step.DOWNLOAD_COMPLETED)!!.count)
        assertEquals(1, f.dimCount(Step.DOWNLOAD_COMPLETED, TtcBucket.M3_10))
        assertEquals(0, f.dimCount(Step.DOWNLOAD_COMPLETED, TtcBucket.LT_1M))
    }

    @Test
    fun everyDownloadProblemMapsToItsOwnValue() {
        val mapped = DownloadProblem.entries.map { DlProblem.of(it) }
        assertEquals(DownloadProblem.entries.size, mapped.toSet().size)
        assertEquals(DlProblem.STORAGE_FULL, DlProblem.of(DownloadProblem.STORAGE_FULL))
        assertEquals(DlProblem.CHECKSUM, DlProblem.of(DownloadProblem.CHECKSUM))
    }

    // ---- permissions and small mappings -----------------------------------------------------------------------

    @Test
    fun micResults() {
        assertEquals(MicResult.GRANTED, FunnelHooks.micResultFromDialog(true))
        assertEquals(MicResult.DENIED, FunnelHooks.micResultFromDialog(false))
        assertEquals(MicResult.BLOCKED_SETTINGS, FunnelHooks.micResultForTap(MicStep.OPEN_SETTINGS))
        assertNull(FunnelHooks.micResultForTap(MicStep.PROCEED))
        assertNull(FunnelHooks.micResultForTap(MicStep.EXPLAIN_THEN_ASK))
    }

    @Test
    fun notificationResults() {
        assertEquals(NotifResult.GRANTED, FunnelHooks.notifResultFromDialog(true))
        assertEquals(NotifResult.DENIED, FunnelHooks.notifResultFromDialog(false))
    }

    @Test
    fun exportKinds() {
        assertEquals(ValueKind.EXPORT_TXT, FunnelHooks.valueKindForExport("txt"))
        assertEquals(ValueKind.EXPORT_SRT, FunnelHooks.valueKindForExport("SRT"))
        assertEquals(ValueKind.EXPORT_VTT, FunnelHooks.valueKindForExport("vtt"))
        assertNull(FunnelHooks.valueKindForExport("docx"))
    }

    @Test
    fun languageBucketNeverNamesTheLanguage() {
        assertEquals(LangBucket.EN, LangBucket.of(WhisperLanguage.ENGLISH, WhisperModel.TINY))
        assertEquals(LangBucket.AUTO, LangBucket.of(WhisperLanguage.AUTO, WhisperModel.SMALL))
        assertEquals(LangBucket.NON_EN_GOOD, LangBucket.of(WhisperLanguage.SPANISH, WhisperModel.BASE))
        assertEquals(LangBucket.NON_EN_FAIR, LangBucket.of(WhisperLanguage.SPANISH, WhisperModel.TINY))
        assertEquals(LangBucket.NON_EN_POOR, LangBucket.of(WhisperLanguage.HINDI, WhisperModel.SMALL))
        // Two different languages with the same fit are indistinguishable in the ledger.
        assertEquals(LangBucket.of(WhisperLanguage.FRENCH, WhisperModel.TINY), LangBucket.of(WhisperLanguage.ARABIC, WhisperModel.TINY))
    }

    @Test
    fun importFailureClasses() {
        assertEquals(ImportFailClass.TOO_LARGE, ImportFailClass.classify("File too large: 3000 MB. Maximum supported size is 2 GB."))
        assertEquals(ImportFailClass.UNSUPPORTED, ImportFailClass.classify("Unsupported file type: text/plain"))
        assertEquals(ImportFailClass.UNSUPPORTED, ImportFailClass.classify("Could not determine file type"))
        assertEquals(ImportFailClass.UNREADABLE, ImportFailClass.classify("Could not open file"))
        assertEquals(ImportFailClass.UNREADABLE, ImportFailClass.classify("Import failed: /storage/emulated/0/secret name.mp3 not found"))
        assertEquals(ImportFailClass.OTHER, ImportFailClass.classify("Something else"))
        assertEquals(ImportFailClass.OTHER, ImportFailClass.classify(null))
    }

    @Test
    fun theMessageTextOfAnImportErrorNeverReachesTheLedger() {
        val secret = "My private memo.m4a"
        f.step(Step.IMPORT_FAILED, ImportFailClass.classify("Import failed: $secret"), InputVia.IMPORT_PICKER)
        val dump = (f.entry(Step.IMPORT_FAILED).toString()) +
            Dims.all.filter { f.dimCount(Step.IMPORT_FAILED, it) > 0 }.joinToString { it.code }
        assertFalse(dump.contains("memo", ignoreCase = true))
    }
}
