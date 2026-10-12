package com.securevox.app.speakers

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File

/** Finds who spoke when, on-device, using pyannote segmentation plus speaker embeddings. */
class SpeakerDiarizer(private val modelDir: File, private val threshold: Float = 0.85f) {

    fun diarize(samples: FloatArray): List<SpeakerSpan> {
        val config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                    model = File(modelDir, "segmentation.onnx").absolutePath
                ),
                numThreads = 2
            ),
            embedding = SpeakerEmbeddingExtractorConfig(
                model = File(modelDir, "embedding.onnx").absolutePath,
                numThreads = 2
            ),
            clustering = FastClusteringConfig(numClusters = -1, threshold = threshold),
            minDurationOn = 0.3f,
            minDurationOff = 0.5f
        )
        val diarization = OfflineSpeakerDiarization(null, config)
        try {
            check(diarization.sampleRate() == 16000) { "Unexpected diarization sample rate" }
            // Not processWithCallback: its native side looks up a specific `invoke` signature on the
            // callback object that a Kotlin lambda does not have, and the whole process aborts.
            val segments = diarization.process(samples)
            return segments.map {
                SpeakerSpan((it.start * 1000).toLong(), (it.end * 1000).toLong(), it.speaker)
            }
        } finally {
            diarization.release()
        }
    }

    private companion object {
        // Higher merges more voices into one; lower splits one voice into several. Tuned for the
        // TitaNet-small embedding (ClearVoice found the right speaker count across about 0.8-0.9).
        const val CLUSTER_THRESHOLD = 0.85f
    }
}
