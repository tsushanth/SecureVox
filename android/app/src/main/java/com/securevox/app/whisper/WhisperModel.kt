package com.securevox.app.whisper

import com.securevox.app.whisper.QualityTier.FAIR
import com.securevox.app.whisper.QualityTier.GOOD
import com.securevox.app.whisper.QualityTier.POOR

/**
 * Available Whisper models with their properties.
 * Models are downloaded from HuggingFace whisper.cpp repository.
 */
enum class WhisperModel(
    val fileName: String,
    val displayName: String,
    val sizeBytes: Long,
    val sha256: String,
    val description: String,
    val accuracy: String,
    val speed: String
) {
    TINY(
        fileName = "ggml-tiny.bin",
        displayName = "Tiny",
        sizeBytes = 77_691_713L,
        sha256 = "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21",
        description = "Fast transcription, good for quick notes",
        accuracy = "Good",
        speed = "~1x realtime"
    ),
    BASE(
        fileName = "ggml-base.bin",
        displayName = "Base",
        sizeBytes = 147_951_465L,
        sha256 = "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe",
        description = "Balanced speed and accuracy",
        accuracy = "Better",
        speed = "~2-3x realtime"
    ),
    SMALL(
        fileName = "ggml-small.bin",
        displayName = "Small",
        sizeBytes = 487_601_967L,
        sha256 = "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b",
        description = "Best accuracy for mobile devices",
        accuracy = "Best",
        speed = "~5-8x realtime"
    );

    val sizeMB: Int get() = (sizeBytes / 1_000_000).toInt()

    companion object {
        val DEFAULT = TINY

        fun fromFileName(fileName: String): WhisperModel? {
            return entries.find { it.fileName == fileName }
        }
    }
}

/** How usable a model is for a language: GOOD at most 15% error, FAIR at most 30%, POOR above that. */
enum class QualityTier { GOOD, FAIR, POOR }

/**
 * Supported languages for transcription.
 */
enum class WhisperLanguage(
    val code: String,
    val displayName: String,
    /** Measured quality with Tiny, Base and Small (tools/lang-bench/results.md, FLEURS, 25 utterances each). */
    val tiny: QualityTier,
    val base: QualityTier,
    val small: QualityTier
) {
    // Auto-detect: tiers reflect language-ID reliability (Tiny misjudged 5 of 140 clips), not word error rate.
    AUTO("auto", "Auto-detect", FAIR, GOOD, GOOD),
    ENGLISH("en", "English", GOOD, GOOD, GOOD),
    SPANISH("es", "Spanish", FAIR, GOOD, GOOD),
    FRENCH("fr", "French", POOR, POOR, GOOD),
    GERMAN("de", "German", FAIR, FAIR, GOOD),
    ITALIAN("it", "Italian", FAIR, FAIR, GOOD),
    PORTUGUESE("pt", "Portuguese", FAIR, FAIR, GOOD),
    DUTCH("nl", "Dutch", POOR, POOR, FAIR),
    POLISH("pl", "Polish", POOR, POOR, FAIR),
    RUSSIAN("ru", "Russian", POOR, FAIR, GOOD),
    CHINESE("zh", "Chinese", POOR, POOR, FAIR),
    JAPANESE("ja", "Japanese", POOR, FAIR, GOOD),
    KOREAN("ko", "Korean", FAIR, GOOD, GOOD),
    ARABIC("ar", "Arabic", POOR, POOR, FAIR),
    HINDI("hi", "Hindi", POOR, POOR, POOR);

    fun tierFor(model: WhisperModel): QualityTier = when (model) {
        WhisperModel.TINY -> tiny
        WhisperModel.BASE -> base
        WhisperModel.SMALL -> small
    }

    /**
     * Smallest model that reaches this language's best tier. If even the best is POOR (Hindi) the tiers do not
     * separate the models, so recommend Small, which measured clearly best.
     */
    val recommendedModel: WhisperModel
        get() {
            val best = WhisperModel.entries.minOf { tierFor(it) }
            if (best == QualityTier.POOR) return WhisperModel.SMALL
            return WhisperModel.entries.first { tierFor(it) == best }
        }

    companion object {
        val DEFAULT = ENGLISH

        fun fromCode(code: String): WhisperLanguage {
            return entries.find { it.code == code } ?: DEFAULT
        }

        /**
         * Returns the WhisperLanguage matching the device locale, or ENGLISH if unsupported.
         */
        fun fromDeviceLocale(): WhisperLanguage {
            val deviceLanguage = java.util.Locale.getDefault().language
            return entries.find { it.code == deviceLanguage } ?: DEFAULT
        }
    }
}
