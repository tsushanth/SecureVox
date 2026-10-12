package com.securevox.app.whisper

/** What the language picker should tell the user about the selected language and model. */
data class LanguageAdvice(
    val tier: QualityTier,
    val recommended: WhisperModel,
    /** True when the recommended model is a different, better model than the selected one. */
    val canSwitch: Boolean,
    val recommendedDownloaded: Boolean
)

object LanguageQuality {

    /** Null when nothing needs saying: the selected model is already GOOD for the language. */
    fun adviceFor(language: WhisperLanguage, selected: WhisperModel, downloaded: Set<WhisperModel>): LanguageAdvice? {
        val tier = language.tierFor(selected)
        if (tier == QualityTier.GOOD) return null
        val rec = language.recommendedModel
        val better = language.tierFor(rec) < tier
        return LanguageAdvice(
            tier = tier,
            recommended = rec,
            canSwitch = better,
            recommendedDownloaded = rec in downloaded
        )
    }

    /**
     * On picking a language, move off a POOR model to a downloaded model with a strictly better tier
     * (the best tier, then the smaller file). Null means keep the current model.
     */
    fun autoUpgrade(language: WhisperLanguage, selected: WhisperModel, downloaded: Set<WhisperModel>): WhisperModel? {
        if (language.tierFor(selected) != QualityTier.POOR) return null
        return WhisperModel.entries
            .filter { it in downloaded && language.tierFor(it) < language.tierFor(selected) }
            .minWithOrNull(compareBy({ language.tierFor(it) }, { it.sizeBytes }))
    }
}
