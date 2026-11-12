package com.securevox.app.service

/**
 * What the list knows about a transcription that is running: how far it is, when this run started
 * (written by the worker, so it survives recomposition, a new ViewModel and a process restart), and
 * how fast the chosen model ran on this phone last time, if it ever finished one.
 */
data class TranscriptionRun(
    val progress: Int,
    val startedAtMs: Long?,
    /** Milliseconds of work per millisecond of audio, measured on this phone for the model in use. */
    val speed: Float?
)

/**
 * The "about N min" shown while a recording is transcribed. There is no built-in guess of how fast a
 * model is: that varies several-fold between Tiny and Small and between phones, and a wrong number
 * reads as a hang. Instead:
 *  - once the run is a few percent in, the remaining time is extrapolated from the real pace of this run;
 *  - before that, a total is shown only if this phone has measured this model before;
 *  - with neither, nothing is claimed.
 */
object TranscriptionEta {
    /** Below this the pace is too noisy to extrapolate (model loading dominates the start). */
    const val MIN_PROGRESS_PERCENT = 5
    const val MIN_ELAPSED_MS = 5_000L
    /** Shorter clips are dominated by model loading and say nothing about the model's speed. */
    const val MIN_AUDIO_MS_FOR_SPEED = 15_000L

    sealed interface Estimate {
        data object Unknown : Estimate
        data class Total(val minutes: Int) : Estimate
        data class Left(val minutes: Int) : Estimate
    }

    fun estimate(run: TranscriptionRun?, nowMs: Long, audioMs: Long): Estimate {
        val progress = run?.progress ?: return Estimate.Unknown
        val startedAt = run.startedAtMs
        if (startedAt != null && progress >= MIN_PROGRESS_PERCENT) {
            val elapsedMs = (nowMs - startedAt).coerceAtLeast(0L)
            if (elapsedMs >= MIN_ELAPSED_MS) {
                val remaining = elapsedMs.toDouble() * (100 - progress.coerceAtMost(100)) / progress
                return Estimate.Left(minutesRoundedUp(remaining))
            }
        }
        val speed = run.speed
        if (speed != null && speed > 0f && audioMs > 0L) {
            return Estimate.Total(minutesRoundedUp(audioMs.toDouble() * speed))
        }
        return Estimate.Unknown
    }

    /** Pace of a finished run, or null when the clip was too short (or the numbers make no sense) to trust. */
    fun measuredSpeed(processingMs: Long, audioMs: Long): Float? {
        if (audioMs < MIN_AUDIO_MS_FOR_SPEED || processingMs <= 0L) return null
        return (processingMs.toDouble() / audioMs).toFloat()
    }

    /** Whole minutes, rounded up; whole seconds first so float noise (6.0000002 min) does not read as 7. */
    private fun minutesRoundedUp(ms: Double): Int =
        Math.ceil(Math.round(ms / 1000.0) / 60.0).toInt().coerceAtLeast(1)
}
