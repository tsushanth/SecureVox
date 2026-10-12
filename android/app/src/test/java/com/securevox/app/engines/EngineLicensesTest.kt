package com.securevox.app.engines

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** An engine can only ship if the licences screen credits it. */
class EngineLicensesTest {
    private val text: String by lazy {
        // Unit tests run with the module directory (android/app) as the working directory.
        File("src/main/res/raw/open_source_licenses.txt").readText()
    }

    @Test fun `every shipped engine and library is credited`() {
        if (EngineGates.SENSE_VOICE_AVAILABLE) {
            assertTrue(text.contains("SenseVoice"))
            assertTrue(text.contains("FunASR Model Open Source License Agreement 1.1"))
            assertTrue(text.contains("=== FunASR Model Open Source License Agreement 1.1 ==="))
        }
        if (EngineGates.NEMO_FAST_CONFORMER_AVAILABLE) {
            assertTrue("NeMo FastConformer must be credited before it ships", text.contains("FastConformer"))
        }
        assertTrue(text.contains("Apache Commons Compress"))
    }

    @Test fun `licence file stays plain ASCII`() {
        assertTrue(text.all { it.code < 128 })
    }
}
