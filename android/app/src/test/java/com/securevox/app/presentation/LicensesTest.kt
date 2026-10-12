package com.securevox.app.presentation

import com.securevox.app.presentation.settings.parseLicenseBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LicensesTest {

    private val licenseFile = File("src/main/res/raw/open_source_licenses.txt")

    @Test
    fun reflowsWrappedParagraphsButKeepsHeadings() {
        val blocks = parseLicenseBlocks("=== MIT License ===\n(applies to X)\n\nLine one\n   line two\n\nSecond paragraph")
        assertEquals(3, blocks.size)
        assertTrue(blocks[0].isHeading)
        assertEquals("Line one line two", blocks[1].text)
        assertEquals("Second paragraph", blocks[2].text)
    }

    @Test
    fun bundledFileCreditsEveryShippedComponentAndHasTheFullTexts() {
        val text = licenseFile.readText()
        listOf(
            "whisper.cpp", "ggml authors", "OpenAI", "sherpa-onnx", "ONNX Runtime", "Microsoft Corporation",
            "pyannote", "CNRS", "TitaNet", "NVIDIA", "Hanken Grotesk", "Source Serif 4", "Jetpack"
        ).forEach { assertTrue("missing credit: $it", text.contains(it)) }
        assertTrue(text.contains("=== Apache License 2.0 ==="))
        assertTrue(text.contains("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION"))
        assertTrue(text.contains("=== MIT License ==="))
        assertTrue(text.contains("THE SOFTWARE IS PROVIDED \"AS IS\""))
        assertTrue(text.contains("=== SIL Open Font License 1.1 ==="))
        assertTrue(text.contains("PERMISSION & CONDITIONS"))
    }
}
