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
        for (l in WhisperLanguage.entries.filter { it != WhisperLanguage.AUTO }) {
            assertEquals(l.code, false, languageNameIn(l.code, Locale.ENGLISH) == l.code)
        }
    }
}
