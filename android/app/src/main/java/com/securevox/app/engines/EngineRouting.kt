package com.securevox.app.engines

/** The speech recognizers the app can run. [WHISPER] (whisper.cpp) is the default and the fallback. */
enum class EngineId(val displayName: String) {
    WHISPER("Whisper"),
    NEMO_FAST_CONFORMER("NeMo FastConformer"),
    SENSE_VOICE("SenseVoice")
}

/** What the user asked for in Settings. */
enum class EnginePreference {
    /** Use an alternative engine for a language when one is installed and better. */
    AUTO,
    /** Always use whisper.cpp with the selected model. */
    WHISPER_ONLY
}

/**
 * Ship switches, in the style of SpeakerLabels.AVAILABLE. A false engine is built and tested but never
 * offered, downloaded or routed to.
 */
object EngineGates {
    /**
     * OFF until the licence is settled. The sherpa-onnx archive is a conversion of NVIDIA's multilingual
     * FastConformer hybrid (en/de/es/fr, 14,288 h), which is published on NGC "under the NGC Terms of Use" and
     * has no Hugging Face repository to read a licence from. NVIDIA's per-language siblings are CC BY 4.0 on
     * Hugging Face, but that does not prove this file's terms. Flip after the owner decides.
     */
    const val NEMO_FAST_CONFORMER_AVAILABLE = false

    /**
     * OFF for the 1.5.0 release until the owner decides. The model is a fine-tune of SenseVoice Small (FunASR
     * Model Open Source License 1.1: free use, copying, modification and sharing with attribution) published by
     * ASLP-lab under Apache 2.0 and converted to int8 ONNX by the sherpa-onnx project, but the FunASR maintainers'
     * clarification on commercial use covers the official weights, not third-party conversions.
     */
    const val SENSE_VOICE_AVAILABLE = false

    val available: Set<EngineId> = buildSet {
        add(EngineId.WHISPER)
        if (NEMO_FAST_CONFORMER_AVAILABLE) add(EngineId.NEMO_FAST_CONFORMER)
        if (SENSE_VOICE_AVAILABLE) add(EngineId.SENSE_VOICE)
    }
}

/** Pure engine selection, kept free of Android types so it can be unit tested. */
object EngineRouter {
    /**
     * Phones below this report too little RAM for the sherpa engines (NeMo peaked at 383 MB and SenseVoice at
     * 0.5 GB on a Pixel, on top of the Whisper model that stays loaded as the fallback). A 4 GB phone reports
     * about 3.8e9 bytes, so this only excludes 2 GB and 3 GB phones, where Whisper tiny/base is safer.
     */
    const val ALTERNATIVE_ENGINE_MIN_RAM_BYTES = 3_000_000_000L

    /** The alternative engine that beats whisper.cpp for [languageCode], or null. Ignores what is installed. */
    fun betterEngineFor(languageCode: String, available: Set<EngineId> = EngineGates.available): EngineId? {
        val engine = when (normalize(languageCode)) {
            "de", "es", "fr" -> EngineId.NEMO_FAST_CONFORMER
            "zh" -> EngineId.SENSE_VOICE
            else -> null
        }
        return engine?.takeIf { it in available }
    }

    /**
     * The engine to run. Only a better engine for the language that is installed, allowed by the user's
     * preference, and fits the phone is chosen; everything else is whisper.cpp with the selected model.
     */
    fun choose(
        languageCode: String,
        installed: Set<EngineId>,
        totalRamBytes: Long,
        preference: EnginePreference,
        available: Set<EngineId> = EngineGates.available
    ): EngineId {
        if (preference == EnginePreference.WHISPER_ONLY) return EngineId.WHISPER
        val better = betterEngineFor(languageCode, available) ?: return EngineId.WHISPER
        if (better !in installed) return EngineId.WHISPER
        if (totalRamBytes in 1 until ALTERNATIVE_ENGINE_MIN_RAM_BYTES) return EngineId.WHISPER
        return better
    }

    private fun normalize(code: String) = code.lowercase().substringBefore('-').substringBefore('_')
}
