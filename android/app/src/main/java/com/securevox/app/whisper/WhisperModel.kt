package com.securevox.app.whisper

/**
 * Available Whisper models with their properties.
 * Models are downloaded from HuggingFace whisper.cpp repository.
 */
enum class WhisperModel(
    val fileName: String,
    val displayName: String,
    val sizeBytes: Long,
    val description: String,
    val accuracy: String,
    val speed: String
) {
    TINY(
        fileName = "ggml-tiny.bin",
        displayName = "Tiny",
        sizeBytes = 75_000_000L,  // ~75MB
        description = "Fast transcription, good for quick notes",
        accuracy = "Good",
        speed = "~1x realtime"
    ),
    BASE(
        fileName = "ggml-base.bin",
        displayName = "Base",
        sizeBytes = 148_000_000L,  // ~148MB
        description = "Balanced speed and accuracy",
        accuracy = "Better",
        speed = "~2-3x realtime"
    ),
    SMALL(
        fileName = "ggml-small.bin",
        displayName = "Small",
        sizeBytes = 488_000_000L,  // ~488MB
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

/**
 * Supported languages for transcription.
 */
enum class WhisperLanguage(
    val code: String,
    val displayName: String
) {
    AUTO("auto", "Auto-detect"),
    ENGLISH("en", "English"),
    SPANISH("es", "Spanish"),
    FRENCH("fr", "French"),
    GERMAN("de", "German"),
    ITALIAN("it", "Italian"),
    PORTUGUESE("pt", "Portuguese"),
    DUTCH("nl", "Dutch"),
    POLISH("pl", "Polish"),
    RUSSIAN("ru", "Russian"),
    CHINESE("zh", "Chinese"),
    JAPANESE("ja", "Japanese"),
    KOREAN("ko", "Korean"),
    ARABIC("ar", "Arabic"),
    HINDI("hi", "Hindi");

    companion object {
        val DEFAULT = ENGLISH

        fun fromCode(code: String): WhisperLanguage {
            return entries.find { it.code == code } ?: DEFAULT
        }
    }
}
