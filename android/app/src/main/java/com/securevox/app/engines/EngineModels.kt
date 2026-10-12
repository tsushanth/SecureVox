package com.securevox.app.engines

import android.content.Context
import com.securevox.app.speakers.ArchiveModel
import com.securevox.app.speakers.ModelFile
import java.io.File

/**
 * Pinned downloads for the alternative engines. Sizes and SHA-256 were re-checked against fresh downloads on
 * 2026-10-12 (they match ClearVoice's pins). Every file is verified before use because native code parses it.
 */
object EngineModels {
    private const val HF = "https://huggingface.co/csukuangfj"

    /** What a language needs downloaded for an engine: a plain file list, or a .tar.bz2 to unpack. */
    class Package(
        val dirName: String,
        val files: List<ModelFile>,
        val archive: ArchiveModel? = null,
        /** Bytes to download, for the UI. */
        val downloadBytes: Long
    ) {
        val downloadMb: Int get() = ((downloadBytes + 999_999) / 1_000_000).toInt()
    }

    /**
     * NVIDIA NeMo FastConformer CTC (en/de/es/fr), int8. Its Hugging Face repository is empty; the only copy is
     * this archive on the sherpa-onnx releases page, from which the two files below are taken.
     */
    private const val NEMO_PREFIX = "sherpa-onnx-nemo-fast-conformer-ctc-en-de-es-fr-14288-int8"
    private val NEMO_FILES = listOf(
        ModelFile("", "model.int8.onnx", 132_445_228, "85451f03e004d607f86f4d6291ca55a8ca984d7e32dedc828cebe7fe61447511"),
        ModelFile("", "tokens.txt", 24_057, "29effa220ddd8fcae96d2d6c78c2e24de4cd9dfaa4ceb86d54dc3d22259c1f93")
    )
    val NEMO_FAST_CONFORMER = Package(
        dirName = "nemo-fastconformer-int8",
        files = NEMO_FILES,
        archive = ArchiveModel(
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$NEMO_PREFIX.tar.bz2",
            sizeBytes = 102_875_642,
            sha256 = "ea7434ecff117272a70b8a60b70cfc2f04b9b07553aa0ecb91065b69c7b91ec5",
            files = NEMO_FILES
        ),
        downloadBytes = 102_875_642
    )

    /** SenseVoice (zh/en/ja/ko/yue) int8. Used for Chinese only: it measured 11.9 CER there but is broken for ja/ko. */
    private val SENSE_VOICE_FILES = listOf(
        ModelFile(
            "$HF/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09/resolve/main/model.int8.onnx",
            "model.onnx", 237_115_547, "12ca1a2ae7ecf3e0019ef2822307ee0b5cadc9196569e379b4c4026f8205276d"
        ),
        ModelFile(
            "$HF/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09/resolve/main/tokens.txt",
            "tokens.txt", 315_894, "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"
        )
    )
    val SENSE_VOICE = Package(
        dirName = "sensevoice-int8",
        files = SENSE_VOICE_FILES,
        downloadBytes = SENSE_VOICE_FILES.sumOf { it.sizeBytes }
    )

    fun packageFor(id: EngineId): Package? = when (id) {
        EngineId.NEMO_FAST_CONFORMER -> NEMO_FAST_CONFORMER
        EngineId.SENSE_VOICE -> SENSE_VOICE
        EngineId.WHISPER -> null
    }

    fun dir(context: Context, id: EngineId): File? =
        packageFor(id)?.let { File(File(context.filesDir, "engines"), it.dirName) }
}
