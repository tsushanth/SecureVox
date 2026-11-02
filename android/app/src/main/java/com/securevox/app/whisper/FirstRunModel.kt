package com.securevox.app.whisper

/** What first-run setup should offer for a language. */
data class SetupOffer(
    /** The model the language benchmark says is the smallest that reaches the best quality tier. */
    val suggested: WhisperModel,
    /** True when [suggested] is bigger than the default Tiny, so Tiny is offered as the quick alternative. */
    val offersAlternative: Boolean,
    /** How Tiny does for this language; shown when Tiny is not GOOD. */
    val tinyTier: QualityTier,
    val suggestedTier: QualityTier
)

/**
 * First-run model choice. The data lives in [WhisperLanguage] (tools/lang-bench/results.md, FLEURS, Tiny/Base/Small):
 * Tiny is GOOD for English only; Base reaches GOOD for Spanish and Korean; most other languages need Small.
 */
object FirstRunModel {

    /** Anything at or above this is a download the user must be told the size of before it starts. */
    const val CONFIRM_OVER_BYTES = 100_000_000L

    fun offerFor(language: WhisperLanguage): SetupOffer {
        val suggested = language.recommendedModel
        return SetupOffer(
            suggested = suggested,
            offersAlternative = suggested != WhisperModel.TINY,
            tinyTier = language.tierFor(WhisperModel.TINY),
            suggestedTier = language.tierFor(suggested)
        )
    }

    /** A big model is never fetched without an explicit yes that states the size. */
    fun needsSizeConfirmation(model: WhisperModel): Boolean = model.sizeBytes >= CONFIRM_OVER_BYTES

    /** Whether to suggest Wi-Fi: only when the download is big and the connection is metered. */
    fun suggestWifi(model: WhisperModel, network: NetworkState): Boolean =
        network == NetworkState.METERED && model.sizeBytes >= CONFIRM_OVER_BYTES
}

/** Which model transcription should really use. */
object ModelSelection {

    /**
     * The saved choice if it is installed. Otherwise the installed model that is best for [language] (best tier,
     * then smaller file), so a phone that only has Small never fails with "model not downloaded". Null when no
     * model is installed at all.
     */
    fun effective(saved: WhisperModel?, installed: Set<WhisperModel>, language: WhisperLanguage): WhisperModel? {
        if (saved != null && saved in installed) return saved
        return installed.minWithOrNull(compareBy({ language.tierFor(it) }, { it.sizeBytes }))
    }
}

/** Whether the first-run setup screen should block the app. */
object SetupGate {
    /** The screen shows only until a model is installed or the user has chosen to continue without one. */
    fun needsSetup(anyModelInstalled: Boolean, skipped: Boolean): Boolean = !anyModelInstalled && !skipped

    /** Recording and in-app import need a model to transcribe with. */
    fun canTranscribe(anyModelInstalled: Boolean): Boolean = anyModelInstalled
}
