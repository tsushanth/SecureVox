package com.securevox.app.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunModelTest {

    private fun suggested(l: WhisperLanguage) = FirstRunModel.offerFor(l).suggested

    @Test
    fun englishStaysOnTinyWithNoExtraOffer() {
        val o = FirstRunModel.offerFor(WhisperLanguage.ENGLISH)
        assertEquals(WhisperModel.TINY, o.suggested)
        assertFalse(o.offersAlternative)
        assertEquals(QualityTier.GOOD, o.tinyTier)
    }

    @Test
    fun suggestionsFollowTheBenchmarkTiers() {
        // tools/lang-bench/results.md: Tiny is GOOD for English only; Base reaches GOOD for es and ko.
        assertEquals(WhisperModel.BASE, suggested(WhisperLanguage.SPANISH))
        assertEquals(WhisperModel.BASE, suggested(WhisperLanguage.KOREAN))
        assertEquals(WhisperModel.BASE, suggested(WhisperLanguage.AUTO)) // Tiny misjudged the language 5 of 140 times
        // French, German, Italian, Portuguese, Russian, Japanese only reach GOOD with Small.
        for (l in listOf(
            WhisperLanguage.FRENCH, WhisperLanguage.GERMAN, WhisperLanguage.ITALIAN,
            WhisperLanguage.PORTUGUESE, WhisperLanguage.RUSSIAN, WhisperLanguage.JAPANESE
        )) {
            assertEquals(l.code, WhisperModel.SMALL, suggested(l))
        }
        // Small is only FAIR for these, but still clearly the best measured; Hindi is POOR everywhere.
        for (l in listOf(
            WhisperLanguage.DUTCH, WhisperLanguage.POLISH, WhisperLanguage.CHINESE,
            WhisperLanguage.ARABIC, WhisperLanguage.HINDI
        )) {
            assertEquals(l.code, WhisperModel.SMALL, suggested(l))
        }
    }

    @Test
    fun everyNonEnglishLanguageOffersTinyAsTheQuickAlternative() {
        for (l in WhisperLanguage.entries) {
            val o = FirstRunModel.offerFor(l)
            assertEquals(l.code, l != WhisperLanguage.ENGLISH, o.offersAlternative)
        }
    }

    @Test
    fun suggestionIsNeverWorseThanTiny() {
        for (l in WhisperLanguage.entries) {
            val o = FirstRunModel.offerFor(l)
            assertTrue(l.code, o.suggestedTier <= o.tinyTier) // GOOD < FAIR < POOR
        }
    }

    @Test
    fun largeDownloadsAlwaysAskFirstTinyDoesNot() {
        assertFalse(FirstRunModel.needsSizeConfirmation(WhisperModel.TINY))
        assertTrue(FirstRunModel.needsSizeConfirmation(WhisperModel.BASE))
        assertTrue(FirstRunModel.needsSizeConfirmation(WhisperModel.SMALL))
    }

    @Test
    fun wifiIsSuggestedOnlyForBigDownloadsOnMeteredData() {
        assertTrue(FirstRunModel.suggestWifi(WhisperModel.SMALL, NetworkState.METERED))
        assertFalse(FirstRunModel.suggestWifi(WhisperModel.SMALL, NetworkState.UNMETERED))
        assertFalse(FirstRunModel.suggestWifi(WhisperModel.SMALL, NetworkState.UNKNOWN))
        assertFalse(FirstRunModel.suggestWifi(WhisperModel.TINY, NetworkState.METERED))
    }

    @Test
    fun savedModelWinsWhenInstalled() {
        val installed = setOf(WhisperModel.TINY, WhisperModel.SMALL)
        assertEquals(WhisperModel.TINY, ModelSelection.effective(WhisperModel.TINY, installed, WhisperLanguage.FRENCH))
    }

    @Test
    fun missingSavedModelFallsBackToTheBestInstalledForTheLanguage() {
        // Setup downloaded only Small; the default saved choice (Tiny) is not on the phone.
        assertEquals(
            WhisperModel.SMALL,
            ModelSelection.effective(WhisperModel.TINY, setOf(WhisperModel.SMALL), WhisperLanguage.FRENCH)
        )
        assertEquals(
            WhisperModel.BASE,
            ModelSelection.effective(null, setOf(WhisperModel.BASE, WhisperModel.SMALL), WhisperLanguage.SPANISH)
        )
        // Equal tier: the smaller file wins.
        assertEquals(
            WhisperModel.TINY,
            ModelSelection.effective(WhisperModel.SMALL, setOf(WhisperModel.TINY), WhisperLanguage.ENGLISH)
        )
    }

    @Test
    fun noModelMeansNull() {
        assertNull(ModelSelection.effective(WhisperModel.TINY, emptySet(), WhisperLanguage.ENGLISH))
    }

    @Test
    fun setupBlocksOnlyUntilModelOrSkip() {
        assertTrue(SetupGate.needsSetup(anyModelInstalled = false, skipped = false))
        assertFalse(SetupGate.needsSetup(anyModelInstalled = false, skipped = true))
        assertFalse(SetupGate.needsSetup(anyModelInstalled = true, skipped = false))
        assertFalse(SetupGate.needsSetup(anyModelInstalled = true, skipped = true))
    }

    @Test
    fun canTranscribeOnlyWithAModel() {
        assertFalse(SetupGate.canTranscribe(false))
        assertTrue(SetupGate.canTranscribe(true))
    }
}
