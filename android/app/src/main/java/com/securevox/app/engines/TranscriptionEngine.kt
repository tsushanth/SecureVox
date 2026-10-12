package com.securevox.app.engines

import com.securevox.app.whisper.TranscriptionSegment
import com.securevox.app.whisper.WhisperLib

/**
 * One chunk of audio for an engine: 16 kHz mono float samples. [leadingOverlapMs] is how much of the start
 * was already transcribed as the end of the previous chunk (the worker drops segments that start inside it).
 */
class ChunkRequest(
    val audio: FloatArray,
    val language: String,
    val leadingOverlapMs: Long = 0L
)

/**
 * A speech recognizer. Returns the app's sentence-sized segments with start/end in milliseconds from the start
 * of [ChunkRequest.audio], exactly like whisper.cpp does, so the worker, database and UI need no changes.
 */
interface TranscriptionEngine : AutoCloseable {
    val id: EngineId
    suspend fun transcribe(request: ChunkRequest, onProgress: ((Int) -> Unit)?): List<TranscriptionSegment>
    override fun close() {}
}

/**
 * whisper.cpp, the default engine. A thin pass-through to the already-initialised [WhisperLib], so behaviour is
 * identical to calling it directly. The caller owns the library (it initialises and releases it).
 */
class WhisperEngine(private val lib: WhisperLib) : TranscriptionEngine {
    override val id = EngineId.WHISPER

    override suspend fun transcribe(request: ChunkRequest, onProgress: ((Int) -> Unit)?): List<TranscriptionSegment> =
        lib.transcribe(audioData = request.audio, language = request.language, onProgress = onProgress)
}

/**
 * Runs [primary] and, when it is missing, fails to load or throws, falls back to [fallback] for this chunk and
 * every later one, so a broken alternative engine never fails the recording. [log] gets the reason.
 */
class FallbackEngine(
    primaryFactory: () -> TranscriptionEngine?,
    private val fallback: TranscriptionEngine,
    private val log: (String, Throwable?) -> Unit,
    private val onClose: () -> Unit = {}
) : TranscriptionEngine {
    private var primary: TranscriptionEngine? = null
    private var primaryTried = false
    private val loader = primaryFactory

    /** Engine that handled the last chunk, for logs and tests. */
    var lastUsed: EngineId = fallback.id
        private set

    override val id: EngineId get() = primary?.id ?: fallback.id

    override suspend fun transcribe(request: ChunkRequest, onProgress: ((Int) -> Unit)?): List<TranscriptionSegment> {
        if (!primaryTried) {
            primaryTried = true
            primary = try {
                loader()
            } catch (e: Throwable) {
                rethrowIfCancellation(e)
                log("Could not load alternative engine; using ${fallback.id}", e)
                null
            }
        }
        val engine = primary
        if (engine != null) {
            try {
                val result = engine.transcribe(request, onProgress)
                lastUsed = engine.id
                return result
            } catch (e: Throwable) {
                rethrowIfCancellation(e)
                log("${engine.id} failed on a chunk; falling back to ${fallback.id} for this recording", e)
                primary = null
                runCatching { engine.close() }
            }
        }
        lastUsed = fallback.id
        return fallback.transcribe(request, onProgress)
    }

    /** Releases the alternative engine's memory. The fallback's library stays the caller's to release. */
    override fun close() {
        runCatching { primary?.close() }
        primary = null
        runCatching(onClose)
    }

    private fun rethrowIfCancellation(e: Throwable) {
        if (e is kotlinx.coroutines.CancellationException) throw e
    }
}
