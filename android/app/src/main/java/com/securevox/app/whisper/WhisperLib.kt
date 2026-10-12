package com.securevox.app.whisper

import android.content.Context
import com.securevox.app.data.model.WordTiming
import com.securevox.app.util.MiniJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Kotlin wrapper for whisper.cpp native library.
 * Provides speech-to-text transcription using OpenAI's Whisper model.
 */
class WhisperLib(private val context: Context) {

    companion object {
        init {
            System.loadLibrary("whisper_jni")
        }

        private const val TAG = "WhisperLib"
    }

    private var contextPtr: Long = 0

    /**
     * Initialize the Whisper context with a model file.
     * @param modelPath Path to the GGML model file
     * @return true if initialization succeeded
     */
    suspend fun initialize(modelPath: String): Boolean = withContext(Dispatchers.IO) {
        if (contextPtr != 0L) {
            freeContext(contextPtr)
        }
        contextPtr = initContext(modelPath)
        contextPtr != 0L
    }

    /**
     * Initialize with a model from assets.
     * Copies the model to internal storage if needed.
     */
    suspend fun initializeFromAsset(assetName: String): Boolean = withContext(Dispatchers.IO) {
        val modelFile = File(context.filesDir, "models/$assetName")

        if (!modelFile.exists()) {
            modelFile.parentFile?.mkdirs()
            try {
                context.assets.open(assetName).use { input ->
                    FileOutputStream(modelFile).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to copy model from assets", e)
                return@withContext false
            }
        }

        initialize(modelFile.absolutePath)
    }

    /**
     * Transcribe audio samples.
     * @param audioData PCM audio samples at 16kHz, mono, float32
     * @param language Language code (e.g., "en", "auto" for detection)
     * @param onProgress Progress callback (0-100)
     * @param fast Trade accuracy for a bounded, short run time (live captions)
     * @return List of transcription segments
     */
    suspend fun transcribe(
        audioData: FloatArray,
        language: String = "en",
        onProgress: ((Int) -> Unit)? = null,
        fast: Boolean = false
    ): List<TranscriptionSegment> = withContext(Dispatchers.Default) {
        if (contextPtr == 0L) {
            throw IllegalStateException("Whisper context not initialized")
        }

        val callback = onProgress?.let { ProgressCallback(it) }
        val jsonResult = transcribeAudio(contextPtr, audioData, language, callback, fast)

        parseSegments(jsonResult)
    }

    /**
     * Check if the loaded model is multilingual.
     */
    fun isMultilingual(): Boolean {
        return contextPtr != 0L && isMultilingual(contextPtr)
    }

    /**
     * Get system info for debugging.
     */
    fun getSystemInfoString(): String = getSystemInfo()

    /**
     * Release native resources.
     */
    fun release() {
        if (contextPtr != 0L) {
            freeContext(contextPtr)
            contextPtr = 0
        }
    }

    private fun parseSegments(json: String): List<TranscriptionSegment> = parseWhisperSegments(json)

    // JNI methods
    private external fun initContext(modelPath: String): Long
    private external fun freeContext(contextPtr: Long)
    private external fun transcribeAudio(
        contextPtr: Long,
        audioData: FloatArray,
        language: String,
        progressCallback: ProgressCallback?,
        fast: Boolean
    ): String
    private external fun getSystemInfo(): String
    private external fun isMultilingual(contextPtr: Long): Boolean
}

/**
 * A single transcription segment with timing information.
 */
data class TranscriptionSegment(
    val text: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    /** Word times relative to this transcribe() call's audio; empty when not requested or unusable. */
    val words: List<WordTiming> = emptyList()
)

/**
 * Parses the JSON from the native bridge: `[{"text","start","end"[,"tokens":[[tok,startMs,endMs]..]]}]`.
 * Pure function so it can be unit tested. Segments with no text (after stripping [BLANK_AUDIO]-style
 * markers) are dropped, as before; segments without usable tokens simply have no words.
 */
fun parseWhisperSegments(json: String): List<TranscriptionSegment> {
    if (json.isEmpty() || json == "[]") return emptyList()
    val list = MiniJson.parse(json) as? List<*> ?: return emptyList()
    val segments = ArrayList<TranscriptionSegment>(list.size)
    for (item in list) {
        val obj = item as? Map<*, *> ?: continue
        val text = (obj["text"] as? String ?: continue).trim().let { NonSpeech.strip(it) }
        if (text.isEmpty()) continue
        val startMs = (obj["start"] as? Double ?: 0.0).toLong()
        val endMs = (obj["end"] as? Double ?: 0.0).toLong()
        segments.add(TranscriptionSegment(text, startMs, endMs, wordsOf(obj["tokens"], text, startMs, endMs)))
    }
    return segments
}

private fun wordsOf(tokens: Any?, text: String, startMs: Long, endMs: Long): List<WordTiming> {
    val list = tokens as? List<*> ?: return emptyList()
    val timed = list.mapNotNull { t ->
        val triple = t as? List<*> ?: return@mapNotNull null
        if (triple.size != 3) return@mapNotNull null
        TokenTime(
            triple[0] as? String ?: return@mapNotNull null,
            (triple[1] as? Double)?.toLong() ?: -1L,
            (triple[2] as? Double)?.toLong() ?: -1L
        )
    }
    // Markers like [BLANK_AUDIO] are stripped from the segment text; drop them from the words too.
    val words = groupTokensIntoWords(timed).filter { NonSpeech.strip(it.text).isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    // Only trust the words if they spell exactly what the segment says.
    val joined = joinWords(words.map { it.text })
    if (joined.filterNot { it.isWhitespace() } != text.filterNot { it.isWhitespace() }) return emptyList()
    return repairWordTimes(words, startMs, endMs)
}

/**
 * Progress callback for JNI.
 */
class ProgressCallback(private val onProgress: (Int) -> Unit) {
    @Suppress("unused") // Called from JNI
    fun onProgress(progress: Int) {
        onProgress.invoke(progress)
    }
}
