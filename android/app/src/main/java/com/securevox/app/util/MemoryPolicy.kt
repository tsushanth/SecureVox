package com.securevox.app.util

import android.app.ActivityManager
import android.content.Context
import com.securevox.app.whisper.WhisperModel

/**
 * Device-aware memory decisions. The decision functions are pure (numbers in, decision out) so they can
 * be unit tested; [read] is the only part that touches the system.
 *
 * Evidence behind the thresholds:
 *  - ClearVoice (same ggml-small.bin) measured Whisper small peaking at about 1.3 GB on a Pixel 9 Pro, and
 *    falls back to a lighter engine below 5e9 bytes of reported RAM. A "4 GB" phone reports about 3.8e9,
 *    a "6 GB" phone about 5.7e9, a "3 GB" phone about 2.7e9, a "2 GB" phone about 1.8e9.
 *  - whisper.cpp's own table lists about 0.27 GB (tiny), 0.39 GB (base), 0.85 GB (small); the Android
 *    measurement for small is higher, so Base is assumed at roughly 0.6 GB with the same overhead.
 *  - Android reports availMem as an estimate (it includes cache the system can drop), so every budget
 *    below keeps a safety factor and also respects [Snapshot.thresholdBytes] (the level at which the
 *    system starts killing background processes).
 */
object MemoryPolicy {

    /** What the system says about memory right now. All values are in bytes. */
    data class Snapshot(
        val totalBytes: Long,
        val availBytes: Long,
        val thresholdBytes: Long,
        val lowMemory: Boolean,
        /** Java heap still free for allocation: Runtime.maxMemory minus what is in use. */
        val heapFreeBytes: Long
    )

    fun read(context: Context): Snapshot {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        val rt = Runtime.getRuntime()
        return Snapshot(
            totalBytes = info.totalMem,
            availBytes = info.availMem,
            thresholdBytes = info.threshold,
            lowMemory = info.lowMemory,
            heapFreeBytes = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        )
    }

    // ---- Model picker -------------------------------------------------------------------------

    const val SMALL_MIN_TOTAL_BYTES = 5_000_000_000L
    /** Below this Small will probably be killed while running (peak 1.3 GB). */
    const val SMALL_RISKY_TOTAL_BYTES = 3_000_000_000L
    const val BASE_MIN_TOTAL_BYTES = 2_500_000_000L

    enum class ModelAdvice { RECOMMENDED, NOT_RECOMMENDED, LIKELY_TO_FAIL }

    data class ModelDecision(val advice: ModelAdvice, val reason: String?) {
        /** The user must confirm before downloading or selecting. They are never blocked outright. */
        val needsConfirm: Boolean get() = advice != ModelAdvice.RECOMMENDED
    }

    fun modelAdvice(model: WhisperModel, totalBytes: Long): ModelDecision {
        val gb = String.format(java.util.Locale.US, "%.1f", totalBytes / 1e9)
        return when (model) {
            WhisperModel.TINY -> ModelDecision(ModelAdvice.RECOMMENDED, null)
            WhisperModel.BASE ->
                if (totalBytes < BASE_MIN_TOTAL_BYTES)
                    ModelDecision(ModelAdvice.NOT_RECOMMENDED, "Base needs about 0.6 GB while running; this phone has $gb GB")
                else ModelDecision(ModelAdvice.RECOMMENDED, null)
            WhisperModel.SMALL -> when {
                totalBytes < SMALL_RISKY_TOTAL_BYTES ->
                    ModelDecision(ModelAdvice.LIKELY_TO_FAIL, "Small peaks at about 1.3 GB; this phone has $gb GB and will likely run out of memory")
                totalBytes < SMALL_MIN_TOTAL_BYTES ->
                    ModelDecision(ModelAdvice.NOT_RECOMMENDED, "Small peaks at about 1.3 GB; this phone has $gb GB and may be slow or run out of memory")
                else -> ModelDecision(ModelAdvice.RECOMMENDED, null)
            }
        }
    }

    // ---- Speaker labels -----------------------------------------------------------------------

    /** Models, runtime and activations of the two ONNX models, independent of audio length. */
    const val SPEAKER_FIXED_BYTES = 350_000_000L
    /** Per sample: 4 as a Java float, about 4 for the copy made at the native boundary, 4 of slack. */
    const val SPEAKER_BYTES_PER_SAMPLE = 12L
    /** Only this share of what is left after the fixed cost is planned for, since availMem is an estimate. */
    const val SPEAKER_SAFETY = 0.5
    /** The Java float array must fit in the heap with room to spare. */
    const val SPEAKER_HEAP_SHARE = 0.7
    /** Labelling less than a minute of audio is not worth the risk. */
    const val SPEAKER_MIN_SAMPLES = 60L * 16000

    enum class SpeakerSkip { TOO_LONG, LOW_MEMORY }

    data class SpeakerDecision(
        val allowed: Boolean,
        /** Longest recording (in 16 kHz samples) that may be labelled right now. */
        val maxSamples: Long,
        val skip: SpeakerSkip?,
        val reason: String?
    )

    /** Longest audio that can be labelled now: the smaller of [capSamples] and what memory allows. */
    fun speakerMaxSamples(s: Snapshot, capSamples: Long): Long {
        if (s.lowMemory) return 0L
        val usable = s.availBytes - s.thresholdBytes - SPEAKER_FIXED_BYTES
        if (usable <= 0) return 0L
        val byRam = (usable * SPEAKER_SAFETY / SPEAKER_BYTES_PER_SAMPLE).toLong()
        val byHeap = (s.heapFreeBytes.coerceAtLeast(0) * SPEAKER_HEAP_SHARE / 4).toLong()
        return minOf(capSamples, byRam, byHeap)
    }

    fun speakerDecision(s: Snapshot, totalSamples: Long, capSamples: Long): SpeakerDecision {
        val max = speakerMaxSamples(s, capSamples)
        return when {
            totalSamples > capSamples ->
                SpeakerDecision(
                    false, max, SpeakerSkip.TOO_LONG,
                    "recording is ${totalSamples / 16000 / 60} min, over the ${capSamples / 16000 / 60} min limit"
                )
            totalSamples > max || max < SPEAKER_MIN_SAMPLES ->
                SpeakerDecision(
                    false, max, SpeakerSkip.LOW_MEMORY,
                    "not enough free memory (lowMemory=${s.lowMemory}, avail=${s.availBytes / 1_000_000} MB, " +
                        "heapFree=${s.heapFreeBytes / 1_000_000} MB, limit=${max / 16000 / 60} min, " +
                        "recording=${totalSamples / 16000 / 60} min)"
                )
            else -> SpeakerDecision(true, max, null, null)
        }
    }

    // ---- Live captions ------------------------------------------------------------------------

    const val LIVE_BASE_MIN_TOTAL_BYTES = 4_000_000_000L
    /** Free memory (above the kill threshold) wanted for a Base context (about 0.6 GB) plus safety. */
    const val LIVE_BASE_MIN_FREE_BYTES = 1_000_000_000L
    /** Free memory wanted for a Tiny context (about 0.3 GB) plus safety. */
    const val LIVE_TINY_MIN_FREE_BYTES = 450_000_000L

    /** Largest model live captions may load, or null to leave live captions off for this recording. */
    fun liveCaptionModel(s: Snapshot): WhisperModel? {
        val free = s.availBytes - s.thresholdBytes
        return when {
            s.lowMemory || free < LIVE_TINY_MIN_FREE_BYTES -> null
            s.totalBytes >= LIVE_BASE_MIN_TOTAL_BYTES && free >= LIVE_BASE_MIN_FREE_BYTES -> WhisperModel.BASE
            else -> WhisperModel.TINY
        }
    }
}
