package com.securevox.app.whisper

import org.junit.Assert.assertEquals
import org.junit.Test

class NonSpeechTest {
    @Test fun dropsAWholeMarker() = assertEquals("", NonSpeech.strip("[BLANK_AUDIO]"))

    @Test fun dropsMarkersInsideSpeech() =
        assertEquals("Hello there.", NonSpeech.strip("Hello [MUSIC] there."))

    @Test fun dropsParenthesisedSoundCues() =
        assertEquals("Thanks.", NonSpeech.strip("(Music) Thanks. (applause)"))

    @Test fun dropsLowercaseCues() {
        assertEquals("", NonSpeech.strip("[inaudible]"))
        assertEquals("ok", NonSpeech.strip("ok [silence]"))
    }

    @Test fun keepsOrdinaryBracketsAndParentheses() =
        assertEquals("See note [1] (page 4) for details.", NonSpeech.strip("See note [1] (page 4) for details."))

    @Test fun collapsesLeftoverSpacing() = assertEquals("a b", NonSpeech.strip("a   [NOISE]   b"))
}
