package com.securevox.app.whisper

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/** Captions so far: [committed] is settled text, [partial] is the current phrase and may still change. */
data class LiveCaption(val committed: String = "", val partial: String = "") {
    val isEmpty get() = committed.isEmpty() && partial.isEmpty()
}

/**
 * Rolling captions while recording. Every couple of seconds it re-transcribes the audio
 * since the last pause and shows that as the partial line; once speech pauses (or the
 * phrase gets long) the line is committed and a new one starts.
 *
 * This is a preview only. It runs on its own Whisper context and its output is never saved;
 * the saved transcript still comes from the full pass when the recording stops.
 */
class LiveTranscriber(
    private val context: Context,
    private val modelPath: String,
    private val language: String,
    private val audio: Flow<ShortArray>,
    private val scope: CoroutineScope
) {
    companion object {
        val KEY_ENABLED = booleanPreferencesKey("live_captions_enabled")

        private const val TAG = "LiveTranscriber"
        private const val SAMPLE_RATE = 16_000
        private const val TICK_MS = 250L
        private const val MIN_NEW_SAMPLES = 2 * SAMPLE_RATE      // re-run after 2s of new audio
        private const val MIN_COMMIT_SAMPLES = 4 * SAMPLE_RATE   // don't commit tiny fragments on a pause
        private const val MAX_PHRASE_SAMPLES = 10 * SAMPLE_RATE  // force a commit
        private const val BUFFER_CAP_SAMPLES = 24 * SAMPLE_RATE  // if inference falls behind, drop the oldest audio
        private const val TAIL_SAMPLES = SAMPLE_RATE * 6 / 10
        private const val TAIL_SILENCE_RMS = 0.003f
        private const val CHUNK_SILENCE_RMS = 0.0035f
    }

    private val _caption = MutableStateFlow(LiveCaption())
    val caption: StateFlow<LiveCaption> = _caption.asStateFlow()

    private val lock = Any()
    private var buffer = FloatArray(BUFFER_CAP_SAMPLES)
    private var length = 0
    private var job: Job? = null
    private val lib = WhisperLib(context)

    fun start() {
        job = scope.launch {
            val collector = launch { audio.collect { append(it) } }
            try {
                if (!lib.initialize(modelPath)) {
                    Log.e(TAG, "Could not load model for live captions")
                    return@launch
                }
                runLoop()
            } finally {
                collector.cancel()
            }
        }
    }

    /** Waits for any inference already running, then frees the model. Safe to call once. */
    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        lib.release()
    }

    private fun append(chunk: ShortArray) = synchronized(lock) {
        if (length + chunk.size > buffer.size) {
            // Keep the newest audio; the oldest phrase is lost rather than the app stalling.
            val keep = (buffer.size - chunk.size).coerceAtLeast(0)
            buffer.copyInto(buffer, 0, length - keep, length)
            length = keep
        }
        for (s in chunk) buffer[length++] = s / 32768f
    }

    private suspend fun runLoop() {
        var committed = ""
        var samplesAtLastRun = 0
        while (currentCoroutineContext().isActive) {
            delay(TICK_MS)

            val shot = synchronized(lock) {
                if (length - samplesAtLastRun < MIN_NEW_SAMPLES) null else buffer.copyOf(length)
            } ?: continue

            val level = rms(shot, 0, shot.size)
            if (level < CHUNK_SILENCE_RMS) {
                // Nothing but room tone: skip Whisper (it invents words on silence) and forget it.
                synchronized(lock) { dropFront(shot.size) }
                samplesAtLastRun = 0
                _caption.value = LiveCaption(committed, "")
                continue
            }

            val text = try {
                clean(lib.transcribe(shot, language, fast = true).joinToString(" ") { it.text })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Live pass failed", e)
                continue
            }

            val tailQuiet = rms(shot, shot.size - TAIL_SAMPLES, shot.size) < TAIL_SILENCE_RMS
            val commit = shot.size >= MAX_PHRASE_SAMPLES ||
                (tailQuiet && shot.size >= MIN_COMMIT_SAMPLES)

            if (commit) {
                if (text.isNotEmpty()) committed = if (committed.isEmpty()) text else "$committed $text"
                // Audio that arrived while Whisper was running belongs to the next phrase.
                synchronized(lock) { dropFront(shot.size) }
                samplesAtLastRun = 0
                _caption.value = LiveCaption(committed, "")
            } else {
                samplesAtLastRun = shot.size
                _caption.value = LiveCaption(committed, text)
            }
        }
    }

    private fun dropFront(count: Int) {
        val remaining = (length - count).coerceAtLeast(0)
        buffer.copyInto(buffer, 0, count.coerceAtMost(length), length)
        length = remaining
    }

    private fun rms(data: FloatArray, from: Int, to: Int): Float {
        val start = from.coerceAtLeast(0)
        if (to <= start) return 0f
        var sum = 0.0
        for (i in start until to) sum += data[i] * data[i]
        return sqrt(sum / (to - start)).toFloat()
    }

    /** Drops Whisper's non-speech markers, and the note symbols it sometimes emits for music. */
    private fun clean(raw: String): String =
        NonSpeech.strip(raw.replace(Regex("""\([^)]*\)"""), " ").replace(Regex("""[♪♫]+"""), " "))
}
