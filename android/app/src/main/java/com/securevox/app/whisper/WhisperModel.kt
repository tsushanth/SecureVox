package com.securevox.app.whisper

import androidx.annotation.StringRes
import com.securevox.app.R
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
    @StringRes val descriptionRes: Int,
    @StringRes val accuracyRes: Int,
    @StringRes val speedRes: Int
) {
    TINY(
        fileName = "ggml-tiny.bin",
        displayName = "Tiny",
        sizeBytes = 77_691_713L,
        sha256 = "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21",
        descriptionRes = R.string.model_desc_tiny,
        accuracyRes = R.string.model_accuracy_good,
        speedRes = R.string.model_speed_tiny
    ),
    BASE(
        fileName = "ggml-base.bin",
        displayName = "Base",
        sizeBytes = 147_951_465L,
        sha256 = "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe",
        descriptionRes = R.string.model_desc_base,
        accuracyRes = R.string.model_accuracy_better,
        speedRes = R.string.model_speed_base
    ),
    SMALL(
        fileName = "ggml-small.bin",
        displayName = "Small",
        sizeBytes = 487_601_967L,
        sha256 = "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b",
        descriptionRes = R.string.model_desc_small,
        accuracyRes = R.string.model_accuracy_best,
        speedRes = R.string.model_speed_small
    );

    /** Size in whole MB (1 MB = 1,000,000 bytes), rounded to nearest: Tiny 77.7 MB reads 78. */
    val sizeMB: Int get() = ((sizeBytes + 500_000L) / 1_000_000L).toInt()

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
 *
 * Tiers for the first 14 languages are measured with the shipped models (tools/lang-bench/results.md, FLEURS, 25
 * utterances each). Tiers for the languages added later come from the Whisper paper (Radford et al. 2022, appendix
 * Table 13, FLEURS WER of Whisper tiny / base / small) with the same thresholds; they were not re-measured with this
 * app's greedy decoder, so treat them as indicative. Rule: GOOD at most 15 WER, FAIR at most 30, POOR above.
 */
enum class WhisperLanguage(
    /** What is saved and passed around. Equals [whisperCode] except for [HINGLISH]. */
    val code: String,
    val displayName: String,
    /** Quality with Tiny, Base and Small. */
    val tiny: QualityTier,
    val base: QualityTier,
    val small: QualityTier,
    /** The language the decoder is told to expect. */
    val whisperCode: String = code,
    /** Show the Hindi the decoder writes in Roman letters (Hinglish). */
    val romanScript: Boolean = false,
    /** BCP-47 tag used to look up the language's name in the user's language, when it differs from [code]. */
    val nameTag: String = code
) {
    // Auto-detect: tiers reflect language-ID reliability (Tiny misjudged 5 of 140 clips), not word error rate.
    AUTO("auto", "Auto-detect", FAIR, GOOD, GOOD),
    ENGLISH("en", "English", GOOD, GOOD, GOOD),
    // Hindi in Devanagari is POOR on every shipped model (Small 54% WER measured, 38% in the Whisper paper), so it is
    // offered with an honest note. Hinglish is Hindi decoded with a Roman-letter prompt, written in Roman letters:
    // on 25 FLEURS Hindi clips a phonetic character error rate (not WER) of 35 / 21 / 17 for Tiny / Base / Small
    // (tools/lang-bench/results-hindi.md), so Base is the smallest model that reaches FAIR.
    HINDI("hi", "Hindi", POOR, POOR, POOR),
    HINGLISH("hi-Latn", "Hinglish", POOR, FAIR, FAIR, whisperCode = "hi", romanScript = true), // i18n-ok: saved code and display fallback; the label is R.string.language_hinglish
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

    // Added for the app's other UI languages and for South Asia. Paper FLEURS WER, tiny / base / small:
    URDU("ur", "Urdu", POOR, POOR, POOR),                // 65 / 52 / 37
    BENGALI("bn", "Bengali", POOR, POOR, POOR),          // 102 / 101 / 104
    TAMIL("ta", "Tamil", POOR, POOR, POOR),              // 100 / 59 / 35
    TELUGU("te", "Telugu", POOR, POOR, POOR),            // 105 / 105 / 98
    MARATHI("mr", "Marathi", POOR, POOR, POOR),          // 100 / 100 / 60
    INDONESIAN("id", "Indonesian", POOR, POOR, FAIR),    // 52 / 33 / 16
    MALAY("ms", "Malay", POOR, POOR, FAIR),              // 51 / 35 / 19
    THAI("th", "Thai", POOR, POOR, FAIR),                // 59 / 38 / 22
    TURKISH("tr", "Turkish", POOR, FAIR, FAIR),          // 43 / 28 / 16
    VIETNAMESE("vi", "Vietnamese", POOR, POOR, FAIR),    // 60 / 41 / 21
    FILIPINO("tl", "Filipino", POOR, POOR, FAIR, nameTag = "fil"), // 66 / 46 / 28 (Whisper calls it Tagalog)
    UKRAINIAN("uk", "Ukrainian", POOR, POOR, FAIR),      // 51 / 38 / 19
    CZECH("cs", "Czech", POOR, POOR, POOR),              // 83 / 66 / 38
    GREEK("el", "Greek", POOR, POOR, POOR),              // 67 / 54 / 31
    FINNISH("fi", "Finnish", POOR, POOR, FAIR),          // 60 / 43 / 24
    HUNGARIAN("hu", "Hungarian", POOR, POOR, POOR),      // 84 / 65 / 39
    ROMANIAN("ro", "Romanian", POOR, POOR, FAIR),        // 75 / 56 / 30
    SWEDISH("sv", "Swedish", POOR, POOR, FAIR);          // 53 / 37 / 21

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
        /** Only for a saved code the app no longer knows. A new install does not start here; see [forLocale]. */
        val DEFAULT = ENGLISH

        /** Android and Java still report some languages by their old codes. */
        private fun canonical(code: String): String = when (code.lowercase()) {
            "in" -> "id"
            "iw" -> "he"
            "fil" -> "tl"
            else -> code
        }

        fun fromCode(code: String): WhisperLanguage {
            val c = canonical(code)
            return entries.find { it.code.equals(c, ignoreCase = true) } ?: DEFAULT
        }

        /** The language code to give the decoder for a saved code ("hi-Latn" is decoded as "hi"). */
        fun decoderCode(code: String): String = fromCode(code).whisperCode

        /**
         * The transcription language for a phone / app language: its own entry when the app can transcribe it,
         * else Auto-detect. Never English by default, which garbles every other language.
         */
        fun forLocale(language: String): WhisperLanguage {
            val c = canonical(language)
            return entries.find { it.code == c && it != HINGLISH } ?: AUTO
        }

        /** [forLocale] for the phone / per-app language currently in effect. */
        fun fromDeviceLocale(): WhisperLanguage = forLocale(java.util.Locale.getDefault().language)
    }
}
