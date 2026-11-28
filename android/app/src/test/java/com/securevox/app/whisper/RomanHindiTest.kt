package com.securevox.app.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Hinglish output: Devanagari from the Hindi model written in Roman letters the way people type it
 * ("kya haal hai"), on the phone, with rules only. Spellings follow common chat usage, so a word can have
 * more than one acceptable form; these cases pin the forms the app produces.
 */
class RomanHindiTest {
    private fun r(s: String) = RomanHindi.fromDevanagari(s)

    @Test
    fun commonWordsUseChatSpellings() {
        assertEquals("kya haal hai?", r("क्या हाल है?"))
        assertEquals("main theek hoon.", r("मैं ठीक हूँ।"))
        assertEquals("aap kaise hain?", r("आप कैसे हैं?"))
        assertEquals("namaste", r("नमस्ते"))
        assertEquals("mujhe yeh nahi pata.", r("मुझे यह नहीं पता।"))
        assertEquals("bahut accha hai.", r("बहुत अच्छा है।"))
        assertEquals("hum kal milenge.", r("हम कल मिलेंगे।"))
    }

    @Test
    fun finalAndMedialSchwaIsDroppedLikeSpeech() {
        assertEquals("kamal", r("कमल"))
        assertEquals("kamra", r("कमरा"))
        assertEquals("samajh", r("समझ"))
        assertEquals("ghar", r("घर"))
        assertEquals("sundar", r("सुंदर"))
        assertEquals("dhanyavaad", r("धन्यवाद"))
        assertEquals("aapko", r("आपको"))
    }

    @Test
    fun conjunctsAndSpecialLetters() {
        assertEquals("shiksha", r("शिक्षा"))
        assertEquals("gyaan", r("ज्ञान"))
        assertEquals("prem", r("प्रेम"))
        assertEquals("zindagi", r("ज़िंदगी"))
        assertEquals("phool", r("फूल"))
        assertEquals("fan", r("फ़न"))
    }

    @Test
    fun anusvaraBecomesMBeforeLabialsElseN() {
        assertEquals("sambandh", r("संबंध")) // m before ब, n before ध
        assertEquals("chanda", r("चंदा"))
        assertEquals("hindi", r("हिंदी"))
        assertEquals("kampan", r("कंपन"))
    }

    @Test
    fun digitsAndDanda() {
        assertEquals("ek 2 3. phir 10", r("एक २ ३। फिर १०"))
    }

    @Test
    fun latinTextAndPunctuationPassThroughUnchanged() {
        assertEquals("Meeting kal 5 baje hai", r("Meeting कल 5 बजे है"))
        assertEquals("", r(""))
        assertEquals("Hello, world!", r("Hello, world!"))
    }

    @Test
    fun neverLeavesDevanagariBehind() {
        val sample = "मैंने कल दोपहर में डॉक्टर से बात की और उन्होंने कहा कि चिंता की कोई बात नहीं है। ऑफिस में काम बहुत है।"
        val out = r(sample)
        assertFalse(out, out.any { it in 'ऀ'..'ॿ' })
    }

    @Test
    fun containsDevanagariDetectsOnlyDevanagari() {
        assertEquals(true, RomanHindi.containsDevanagari("नमस्ते world"))
        assertEquals(false, RomanHindi.containsDevanagari("namaste"))
        assertEquals(false, RomanHindi.containsDevanagari(""))
    }
}
