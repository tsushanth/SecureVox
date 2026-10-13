package com.securevox.app.engines

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngineRouterTest {
    private val all = setOf(EngineId.WHISPER, EngineId.NEMO_FAST_CONFORMER, EngineId.SENSE_VOICE)
    private val gb = 1_000_000_000L

    private fun choose(
        lang: String,
        installed: Set<EngineId> = all,
        ram: Long = 8 * gb,
        pref: EnginePreference = EnginePreference.AUTO,
        available: Set<EngineId> = all
    ) = EngineRouter.choose(lang, installed, ram, pref, available)

    @Test fun `german spanish french use NeMo when installed`() {
        for (l in listOf("de", "es", "fr")) assertEquals(EngineId.NEMO_FAST_CONFORMER, choose(l))
    }

    @Test fun `chinese uses SenseVoice when installed`() {
        assertEquals(EngineId.SENSE_VOICE, choose("zh"))
    }

    @Test fun `other languages always use whisper`() {
        for (l in listOf("en", "it", "pt", "nl", "pl", "ru", "ja", "ko", "ar", "hi", "auto")) {
            assertEquals(l, EngineId.WHISPER, choose(l))
        }
    }

    @Test fun `not installed falls back to whisper`() {
        assertEquals(EngineId.WHISPER, choose("de", installed = setOf(EngineId.WHISPER)))
        assertEquals(EngineId.WHISPER, choose("zh", installed = setOf(EngineId.NEMO_FAST_CONFORMER)))
    }

    @Test fun `whisper only preference wins`() {
        assertEquals(EngineId.WHISPER, choose("de", pref = EnginePreference.WHISPER_ONLY))
    }

    @Test fun `low RAM phones stay on whisper, unknown RAM does not restrict`() {
        assertEquals(EngineId.WHISPER, choose("de", ram = 2 * gb))
        assertEquals(EngineId.WHISPER, choose("zh", ram = EngineRouter.ALTERNATIVE_ENGINE_MIN_RAM_BYTES - 1))
        assertEquals(EngineId.SENSE_VOICE, choose("zh", ram = EngineRouter.ALTERNATIVE_ENGINE_MIN_RAM_BYTES))
        assertEquals(EngineId.SENSE_VOICE, choose("zh", ram = 3_800_000_000L)) // a 4 GB phone
        assertEquals(EngineId.SENSE_VOICE, choose("zh", ram = 0L))
    }

    @Test fun `a gated engine is never chosen or suggested`() {
        val noNemo = setOf(EngineId.WHISPER, EngineId.SENSE_VOICE)
        assertEquals(EngineId.WHISPER, choose("de", available = noNemo))
        assertNull(EngineRouter.betterEngineFor("de", noNemo))
        assertEquals(EngineId.SENSE_VOICE, EngineRouter.betterEngineFor("zh", noNemo))
    }

    @Test fun `region tags are ignored`() {
        assertEquals(EngineId.NEMO_FAST_CONFORMER, choose("de-AT"))
        assertEquals(EngineId.SENSE_VOICE, choose("ZH_cn"))
    }

    @Test fun `suggestion exists only for languages with a better engine`() {
        assertEquals(EngineId.NEMO_FAST_CONFORMER, EngineRouter.betterEngineFor("fr", all))
        assertNull(EngineRouter.betterEngineFor("en", all))
        assertNull(EngineRouter.betterEngineFor("ja", all)) // SenseVoice is broken for ja/ko in ClearVoice's benchmark
    }

    @Test fun `shipped gates`() {
        // 1.5.0 ships with both alternative engines off until their licences are settled.
        assertEquals(false, EngineGates.NEMO_FAST_CONFORMER_AVAILABLE)
        assertEquals(false, EngineGates.SENSE_VOICE_AVAILABLE)
        assertEquals(setOf(EngineId.WHISPER), EngineGates.available)
    }
}
