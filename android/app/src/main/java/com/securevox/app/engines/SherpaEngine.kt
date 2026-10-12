package com.securevox.app.engines

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.securevox.app.whisper.NonSpeech
import com.securevox.app.whisper.TranscriptionSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * NeMo FastConformer or SenseVoice through sherpa-onnx (already bundled for speaker labels). Both are
 * non-autoregressive CTC-style models, so a whole 30 s chunk from the worker is decoded in one pass: they have no
 * 30 s window like Whisper, cost grows roughly linearly with length, and cutting at the worker's fixed chunk
 * edges is the same trade-off whisper.cpp already makes there. ClearVoice cut windows at the quietest moment
 * after 22 s; here the worker owns chunking and the 1 s overlap, so that is not repeated.
 *
 * The recognizer reports tokens with start times (no durations). Those are grouped into words, the
 * recognizer's habit of stamping the first token 0.00 is clamped to the first speech, and the words are
 * grouped into sentence-sized segments (see [segmentWords]).
 */
class SherpaEngine(
    override val id: EngineId,
    modelDir: File,
    languageCode: String
) : TranscriptionEngine {

    private val recognizer: OfflineRecognizer = OfflineRecognizer(
        null,
        OfflineRecognizerConfig(
            modelConfig = when (id) {
                EngineId.SENSE_VOICE -> OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = File(modelDir, "model.onnx").absolutePath,
                        language = languageCode.substringBefore('-'),
                        useInverseTextNormalization = true
                    ),
                    tokens = File(modelDir, "tokens.txt").absolutePath,
                    numThreads = 4
                )
                EngineId.NEMO_FAST_CONFORMER -> OfflineModelConfig(
                    nemo = OfflineNemoEncDecCtcModelConfig(model = File(modelDir, "model.int8.onnx").absolutePath),
                    tokens = File(modelDir, "tokens.txt").absolutePath,
                    numThreads = 4
                )
                EngineId.WHISPER -> error("Whisper does not run through sherpa-onnx here")
            }
        )
    )

    override suspend fun transcribe(request: ChunkRequest, onProgress: ((Int) -> Unit)?): List<TranscriptionSegment> =
        withContext(Dispatchers.Default) {
            if (request.audio.isEmpty()) return@withContext emptyList()
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(request.audio, SAMPLE_RATE)
                recognizer.decode(stream)
                val result = recognizer.getResult(stream)
                val spans = speechSpans(request.audio)
                val words = clampStartsToSpeech(tokensToTimedWords(result.tokens, result.timestamps), spans)
                onProgress?.invoke(100)
                val segments = if (words.isNotEmpty()) segmentWords(words, request.leadingOverlapMs)
                else if (result.text.isNotBlank()) {
                    // No usable token times: keep the text as one segment over the speech rather than lose it.
                    listOf(TranscriptionSegment(result.text.trim(), spans.first().startMs, spans.last().endMs))
                } else emptyList()
                segments
                    .map { it.copy(text = NonSpeech.strip(it.text)) }
                    .filter { it.text.isNotEmpty() }
            } finally {
                stream.release()
            }
        }

    override fun close() {
        recognizer.release()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}
