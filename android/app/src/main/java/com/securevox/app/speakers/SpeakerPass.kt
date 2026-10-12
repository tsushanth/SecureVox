package com.securevox.app.speakers

import android.content.Context
import android.util.Log
import com.securevox.app.data.repository.RecordingRepository
import com.securevox.app.service.WavReader
import com.securevox.app.util.MemoryPolicy
import kotlinx.coroutines.CancellationException

/**
 * The speaker pass, shared by the post-transcription step and the user-triggered relabel.
 * Memory guards, the crash-loop marker and the length cap are identical for both.
 */
class SpeakerPass(private val context: Context, private val repository: RecordingRepository) {

    enum class Mode { FIRST_TIME, RELABEL }

    sealed class Outcome {
        /** Finished; [speakers] is the number of labelled speakers (0 = labels removed / none). */
        data class Done(val speakers: Int) : Outcome()
        /** Skipped by the memory/length policy (note stored for the transcript hint). */
        data class Skipped(val skip: MemoryPolicy.SpeakerSkip?) : Outcome()
        object ModelsMissing : Outcome()
        /** The previous attempt died natively; the marker was cleared so a retry runs. */
        object PreviousCrash : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    suspend fun run(recordingId: String, audioPath: String, numSpeakers: Int?, mode: Mode): Outcome {
        val prefs = context.getSharedPreferences("securevox_prefs", Context.MODE_PRIVATE)
        val guardKey = "speaker_pass_started_$recordingId"
        try {
            SpeakerLabels.setSkipNote(context, recordingId, null) // forget a note from an earlier run
            val fixed = SpeakerCount.normalize(numSpeakers)
            // One speaker needs no audio or models: just merge back and remove labels.
            if (mode == Mode.RELABEL && fixed == 1) return applyRelabel(recordingId, emptyList(), 1)

            val dir = SpeakerLabels.modelDir(context)
            if (!ModelDownloader.isReady(dir, SpeakerModels.DIARIZATION)) return Outcome.ModelsMissing
            val info = WavReader.readWavInfo(audioPath) ?: return Outcome.Failed("Could not read the audio file")
            val total = info.totalMonoSamples
            val decision = MemoryPolicy.speakerDecision(MemoryPolicy.read(context), total, SpeakerLabels.MAX_SAMPLES)
            if (!decision.allowed) {
                Log.i(TAG, "Speaker labels skipped for $recordingId: ${decision.reason}")
                SpeakerLabels.setSkipNote(context, recordingId, decision.skip)
                return Outcome.Skipped(decision.skip)
            }
            // A native crash cannot be caught and WorkManager re-runs a worker whose process died:
            // the marker makes the retry skip the pass instead of crashing again in a loop.
            if (prefs.getBoolean(guardKey, false)) {
                Log.w(TAG, "Speaker pass crashed last time for $recordingId; skipping it")
                prefs.edit().remove(guardKey).apply()
                return Outcome.PreviousCrash
            }
            val samples = WavReader.loadAudioChunk(info, 0, total.toInt())
                ?: return Outcome.Failed("Could not load the audio")
            prefs.edit().putBoolean(guardKey, true).commit()
            val startedAt = System.nanoTime()
            val spans = SpeakerDiarizer(dir, numSpeakers = fixed).diarize(samples)
            prefs.edit().remove(guardKey).apply()
            Log.i(
                TAG,
                "Diarization: ${total / 16000}s audio in ${(System.nanoTime() - startedAt) / 1_000_000}ms, " +
                    "${spans.size} spans, ${spans.map { it.speaker }.toSet().size} raw speakers"
            )
            return when (mode) {
                Mode.RELABEL -> applyRelabel(recordingId, spans, fixed)
                Mode.FIRST_TIME -> {
                    val segments = repository.getSegmentsForRecordingSync(recordingId)
                    // Word-level: labels each word and splits a segment where the speaker changes
                    // (whole-segment labels for recordings without word timing).
                    val labelled = assignSpeakersByWord(segments, spans)
                    // One voice means there is nothing to label; keep the transcript clean.
                    if (hasMultipleSpeakers(labelled)) repository.saveSegments(labelled)
                    Log.i(TAG, "Speaker pass done: ${labelled.map { it.speaker }.toSet().size} speakers")
                    Outcome.Done(SpeakerRelabel.speakerCount(labelled))
                }
            }
        } catch (e: CancellationException) {
            prefs.edit().remove(guardKey).apply()
            throw e
        } catch (e: Throwable) {
            // Caught on the Java side, so there is no native crash to guard against next time.
            prefs.edit().remove(guardKey).apply()
            if (e is OutOfMemoryError) {
                SpeakerLabels.setSkipNote(context, recordingId, MemoryPolicy.SpeakerSkip.LOW_MEMORY)
                return Outcome.Skipped(MemoryPolicy.SpeakerSkip.LOW_MEMORY)
            }
            Log.w(TAG, "Speaker labelling skipped", e)
            return Outcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun applyRelabel(
        recordingId: String,
        spans: List<SpeakerSpan>,
        numSpeakers: Int?
    ): Outcome {
        val current = repository.getSegmentsForRecordingSync(recordingId)
        if (current.isEmpty()) return Outcome.Failed("This recording has no transcript")
        val rebuilt = SpeakerRelabel.rebuild(current, spans, numSpeakers)
        // The transcript text must never change; refuse to write if it somehow would.
        if (!SpeakerRelabel.textPreserved(current, rebuilt)) return Outcome.Failed("Re-labelling would change the text; nothing was changed")
        repository.replaceSegments(recordingId, rebuilt)
        return Outcome.Done(SpeakerRelabel.speakerCount(rebuilt))
    }

    private companion object {
        const val TAG = "SpeakerPass"
    }
}
