package com.securevox.app.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageQualityTest {

    @Test
    fun tierLookupMatchesMeasuredResults() {
        assertEquals(QualityTier.GOOD, WhisperLanguage.ENGLISH.tierFor(WhisperModel.TINY))
        assertEquals(QualityTier.POOR, WhisperLanguage.FRENCH.tierFor(WhisperModel.BASE))
        assertEquals(QualityTier.GOOD, WhisperLanguage.FRENCH.tierFor(WhisperModel.SMALL))
        assertEquals(QualityTier.POOR, WhisperLanguage.HINDI.tierFor(WhisperModel.SMALL))
    }

    @Test
    fun recommendedModelIsSmallestModelWithBestTier() {
        assertEquals(WhisperModel.TINY, WhisperLanguage.ENGLISH.recommendedModel)
        assertEquals(WhisperModel.BASE, WhisperLanguage.SPANISH.recommendedModel)
        assertEquals(WhisperModel.BASE, WhisperLanguage.KOREAN.recommendedModel)
        assertEquals(WhisperModel.SMALL, WhisperLanguage.FRENCH.recommendedModel)
        assertEquals(WhisperModel.SMALL, WhisperLanguage.HINDI.recommendedModel)
    }

    @Test
    fun biggerModelNeverWorseThanSmallerOne() {
        for (l in WhisperLanguage.entries) {
            assertTrue(l.code, l.base <= l.tiny)
            assertTrue(l.code, l.small <= l.base)
        }
    }

    @Test
    fun allFifteenLanguagesStillPresent() {
        assertEquals(15, WhisperLanguage.entries.size)
    }

    @Test
    fun noAdviceWhenSelectedModelIsGood() {
        assertNull(LanguageQuality.adviceFor(WhisperLanguage.ENGLISH, WhisperModel.TINY, emptySet()))
        assertNull(LanguageQuality.adviceFor(WhisperLanguage.FRENCH, WhisperModel.SMALL, setOf(WhisperModel.SMALL)))
    }

    @Test
    fun poorWithTinyOffersSwitchWhenRecommendedDownloaded() {
        val a = LanguageQuality.adviceFor(
            WhisperLanguage.FRENCH, WhisperModel.TINY, setOf(WhisperModel.TINY, WhisperModel.SMALL)
        )
        assertNotNull(a)
        assertEquals(QualityTier.POOR, a!!.tier)
        assertEquals(WhisperModel.SMALL, a.recommended)
        assertTrue(a.canSwitch)
        assertTrue(a.recommendedDownloaded)
    }

    @Test
    fun poorWithTinyPointsToDownloadWhenRecommendedMissing() {
        val a = LanguageQuality.adviceFor(WhisperLanguage.RUSSIAN, WhisperModel.TINY, setOf(WhisperModel.TINY))!!
        assertTrue(a.canSwitch)
        assertFalse(a.recommendedDownloaded)
    }

    @Test
    fun hindiOnSmallHasNoSwitchOnlyAnHonestNote() {
        val a = LanguageQuality.adviceFor(WhisperLanguage.HINDI, WhisperModel.SMALL, setOf(WhisperModel.SMALL))!!
        assertEquals(QualityTier.POOR, a.tier)
        assertFalse(a.canSwitch)
    }

    @Test
    fun fairOnBaseStillSuggestsSmall() {
        val a = LanguageQuality.adviceFor(WhisperLanguage.GERMAN, WhisperModel.BASE, setOf(WhisperModel.BASE))!!
        assertEquals(QualityTier.FAIR, a.tier)
        assertEquals(WhisperModel.SMALL, a.recommended)
        assertTrue(a.canSwitch)
    }

    @Test
    fun autoUpgradeOnlyLeavesPoorModelsForADownloadedBetterOne() {
        val all = WhisperModel.entries.toSet()
        assertEquals(WhisperModel.SMALL, LanguageQuality.autoUpgrade(WhisperLanguage.FRENCH, WhisperModel.TINY, all))
        // Russian: Base is FAIR, Small GOOD; the best tier wins over the smaller file
        assertEquals(WhisperModel.SMALL, LanguageQuality.autoUpgrade(WhisperLanguage.RUSSIAN, WhisperModel.TINY, all))
        // Only Base downloaded: Russian Tiny POOR -> Base FAIR
        assertEquals(
            WhisperModel.BASE,
            LanguageQuality.autoUpgrade(WhisperLanguage.RUSSIAN, WhisperModel.TINY, setOf(WhisperModel.TINY, WhisperModel.BASE))
        )
        // No better model downloaded
        assertNull(LanguageQuality.autoUpgrade(WhisperLanguage.FRENCH, WhisperModel.TINY, setOf(WhisperModel.TINY)))
        // FAIR is left alone: a mild issue does not override the user's model choice
        assertNull(LanguageQuality.autoUpgrade(WhisperLanguage.GERMAN, WhisperModel.TINY, all))
        // Hindi: nothing is better than POOR
        assertNull(LanguageQuality.autoUpgrade(WhisperLanguage.HINDI, WhisperModel.TINY, all))
    }
}
