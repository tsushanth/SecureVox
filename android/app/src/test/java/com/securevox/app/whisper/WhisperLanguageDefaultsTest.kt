package com.securevox.app.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Transcription language defaults: the phone / app language when the app can transcribe it, otherwise
 * auto-detect, never silently English (an English default garbles every other language).
 */
class WhisperLanguageDefaultsTest {
    private fun res(): File = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    /** Languages the app has a UI for, from res/xml/locales_config.xml (the shipped list). */
    private fun uiLanguages(): List<String> =
        Regex("""<locale\s+android:name="([^"]+)"""").findAll(File(res(), "xml/locales_config.xml").readText())
            .map { it.groupValues[1].substringBefore('-') }.distinct().toList()

    @Test
    fun everyUiLanguageHasItsOwnTranscriptionLanguage() {
        for (ui in uiLanguages()) {
            val l = WhisperLanguage.forLocale(ui)
            assertNotEquals("$ui must not fall back to Auto-detect", WhisperLanguage.AUTO, l)
            if (ui != "en") assertNotEquals("$ui must not default to English", WhisperLanguage.ENGLISH, l)
        }
    }

    @Test
    fun javaLegacyAndFilipinoCodesAreUnderstood() {
        assertEquals(WhisperLanguage.INDONESIAN, WhisperLanguage.forLocale("in")) // Android reports Indonesian as "in"
        assertEquals(WhisperLanguage.INDONESIAN, WhisperLanguage.forLocale("id"))
        assertEquals(WhisperLanguage.FILIPINO, WhisperLanguage.forLocale("fil"))
        assertEquals(WhisperLanguage.FILIPINO, WhisperLanguage.forLocale("tl"))
        assertEquals("tl", WhisperLanguage.FILIPINO.whisperCode) // Whisper knows Filipino as Tagalog
    }

    @Test
    fun englishPhonesStayEnglishAndUnknownLanguagesAutoDetect() {
        assertEquals(WhisperLanguage.ENGLISH, WhisperLanguage.forLocale("en"))
        assertEquals(WhisperLanguage.AUTO, WhisperLanguage.forLocale("gu")) // Gujarati: no model support to name
        assertEquals(WhisperLanguage.AUTO, WhisperLanguage.forLocale(""))
        assertEquals(WhisperLanguage.AUTO, WhisperLanguage.forLocale("xx"))
    }

    @Test
    fun hindiAndIndicLanguagesAreSelectable() {
        assertEquals(WhisperLanguage.HINDI, WhisperLanguage.forLocale("hi"))
        for (code in listOf("ur", "bn", "ta", "te", "mr")) {
            val l = WhisperLanguage.fromCode(code)
            assertEquals(code, l.code)
            assertEquals(code, l.whisperCode)
        }
    }

    @Test
    fun hinglishIsHindiToTheDecoderAndRomanForTheReader() {
        val h = WhisperLanguage.HINGLISH
        assertEquals("hi-Latn", h.code)
        assertEquals("hi", h.whisperCode)
        assertTrue(h.romanScript)
        assertEquals(h, WhisperLanguage.fromCode("hi-Latn"))
        assertEquals(WhisperLanguage.HINDI, WhisperLanguage.fromCode("hi"))
        assertEquals(false, WhisperLanguage.HINDI.romanScript)
    }

    @Test
    fun hinglishNeedsOnlyTheBaseModelButDevanagariHindiNeedsSmall() {
        assertEquals(WhisperModel.BASE, WhisperLanguage.HINGLISH.recommendedModel)
        assertEquals(WhisperModel.SMALL, WhisperLanguage.HINDI.recommendedModel)
        assertEquals(QualityTier.POOR, WhisperLanguage.HINGLISH.tierFor(WhisperModel.TINY))
    }

    @Test
    fun codeToDecoderMappingForAnySavedCode() {
        assertEquals("hi", WhisperLanguage.decoderCode("hi-Latn"))
        assertEquals("hi", WhisperLanguage.decoderCode("hi"))
        assertEquals("auto", WhisperLanguage.decoderCode("auto"))
        assertEquals("en", WhisperLanguage.decoderCode("en"))
        assertEquals("id", WhisperLanguage.decoderCode("in"))
        // A saved code the app no longer knows falls back to English rather than crashing the decoder.
        assertEquals("en", WhisperLanguage.decoderCode("zz"))
    }

    @Test
    fun savedLanguageCodesAreUnique() {
        val codes = WhisperLanguage.entries.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun newLanguagesCarryTheWhisperPaperTiersNotOptimisticOnes() {
        // Whisper paper (Radford et al. 2022), appendix Table 13, FLEURS WER: Hindi tiny 102 / base 101 / small 38 ->
        // POOR everywhere. Indonesian 52 / 33 / 16 -> POOR, POOR, FAIR. Turkish 43 / 28 / 16 -> POOR, FAIR, FAIR.
        assertEquals(QualityTier.POOR, WhisperLanguage.HINDI.tierFor(WhisperModel.SMALL))
        assertEquals(QualityTier.FAIR, WhisperLanguage.INDONESIAN.tierFor(WhisperModel.SMALL))
        assertEquals(QualityTier.POOR, WhisperLanguage.INDONESIAN.tierFor(WhisperModel.BASE))
        assertEquals(QualityTier.FAIR, WhisperLanguage.TURKISH.tierFor(WhisperModel.BASE))
        // Telugu and Bengali are unusable on every model (WER above 98).
        for (l in listOf(WhisperLanguage.TELUGU, WhisperLanguage.BENGALI)) {
            for (m in WhisperModel.entries) assertEquals(l.code, QualityTier.POOR, l.tierFor(m))
        }
        // Tiny is only usable for English among the languages with a tier.
        for (l in WhisperLanguage.entries.filter { it != WhisperLanguage.AUTO && it != WhisperLanguage.ENGLISH }) {
            assertTrue(l.code, l.tierFor(WhisperModel.TINY) != QualityTier.GOOD)
        }
    }

    @Test
    fun everyLanguageHasADecoderCodeWhisperKnows() {
        val whisperCodes = setOf(
            "auto", "en", "es", "fr", "de", "it", "pt", "nl", "pl", "ru", "zh", "ja", "ko", "ar", "hi", "ur", "bn", "ta", "te",
            "mr", "id", "ms", "th", "tr", "vi", "tl", "uk", "cs", "el", "fi", "hu", "ro", "sv"
        )
        for (l in WhisperLanguage.entries) assertTrue(l.code, l.whisperCode in whisperCodes)
    }
}
