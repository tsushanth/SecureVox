package com.securevox.app.whisper

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class LanguageNameTest {
    @Test
    fun namesAreWrittenInTheUiLanguage() {
        assertEquals("Japanisch", languageNameIn("ja", Locale.GERMAN))
        assertEquals("Japanese", languageNameIn("ja", Locale.ENGLISH))
        assertEquals("Español", languageNameIn("es", Locale("es")))
    }

    @Test
    fun everyTranscriptionLanguageHasAName() {
        // Auto-detect and Hinglish have their own string resources.
        for (l in WhisperLanguage.entries.filter { it != WhisperLanguage.AUTO && it != WhisperLanguage.HINGLISH }) {
            assertEquals(l.code, false, languageNameIn(l.nameTag, Locale.ENGLISH) == l.nameTag)
        }
    }

    @Test
    fun filipinoIsNamedFilipinoNotTagalog() {
        assertEquals("Filipino", languageNameIn(WhisperLanguage.FILIPINO.nameTag, Locale.ENGLISH))
    }
}
