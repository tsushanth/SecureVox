package com.securevox.app.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.securevox.app.data.model.WordTiming

class ScriptAndPromptTest {
    @Test
    fun hinglishRewritesDevanagariOtherLanguagesAreUntouched() {
        assertEquals("kya haal hai?", OutputScript.apply("hi-Latn", "क्या हाल है?"))
        assertEquals("क्या हाल है?", OutputScript.apply("hi", "क्या हाल है?"))
        assertEquals("Hello", OutputScript.apply("hi-Latn", "Hello"))
        assertEquals("مرحبا", OutputScript.apply("ar", "مرحبا"))
        assertEquals("क्या", OutputScript.apply("auto", "क्या"))
    }

    @Test
    fun hinglishSegmentKeepsItsWordsInStepWithItsText() {
        val seg = TranscriptionSegment(
            text = "क्या हाल है?", startTimeMs = 0, endTimeMs = 1500,
            words = listOf(WordTiming("क्या", 0, 400), WordTiming("हाल", 450, 900), WordTiming("है?", 950, 1500))
        )
        val out = OutputScript.apply("hi-Latn", seg)
        assertEquals("kya haal hai?", out.text)
        assertEquals(listOf("kya", "haal", "hai?"), out.words.map { it.text })
        assertEquals(seg.words.map { it.startMs to it.endMs }, out.words.map { it.startMs to it.endMs })
        // Same text as the words rebuild, which is what tap-to-seek requires.
        assertEquals(out.text.filterNot { it.isWhitespace() }, out.words.joinToString("") { it.text })
        assertEquals(seg, OutputScript.apply("hi", seg))
    }

    @Test
    fun hinglishIsPrimedInRomanLettersAndHindiInDevanagari() {
        val roman = LanguagePrompt.build("hi-Latn", "")
        assertTrue(roman, roman.isNotBlank() && roman.none { it in '\u0900'..'\u097F' })
        if (LanguagePrompt.HINDI_PRIMER_ENABLED) {
            val dev = LanguagePrompt.build("hi", "")
            assertTrue(dev, dev.any { it in '\u0900'..'\u097F' })
            assertFalse(dev.any { it in 'a'..'z' })
        } else {
            assertEquals("", LanguagePrompt.build("hi", ""))
        }
        assertEquals("", LanguagePrompt.build("en", ""))
        assertEquals("", LanguagePrompt.build("auto", ""))
        assertEquals("", LanguagePrompt.build("ur", ""))
    }

    @Test
    fun dictionaryTermsStayLastSoTheyWeighMost() {
        val p = LanguagePrompt.build("hi-Latn", "Sushanth, SecureVox")
        assertTrue(p, p.endsWith("Sushanth, SecureVox"))
        assertEquals("Sushanth, SecureVox", LanguagePrompt.build("en", "Sushanth, SecureVox"))
    }
}
