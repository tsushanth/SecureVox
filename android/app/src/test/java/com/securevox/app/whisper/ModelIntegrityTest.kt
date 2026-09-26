package com.securevox.app.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelIntegrityTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val payload = "abc"
    private val payloadSha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private val payloadSize = 3L

    private fun fileWith(contents: String, name: String = "model.bin"): File =
        temp.newFile(name).apply { writeText(contents) }

    @Test
    fun `sha256 matches known digest`() {
        assertEquals(payloadSha, ModelIntegrity.sha256(fileWith(payload)))
    }

    @Test
    fun `sha256 of empty file matches known digest`() {
        val empty = temp.newFile("empty.bin")
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ModelIntegrity.sha256(empty)
        )
    }

    @Test
    fun `missing file is not installed`() {
        val absent = File(temp.root, "nope.bin")
        assertFalse(ModelIntegrity.isInstalledAgainst(absent, payloadSize, payloadSha))
    }

    @Test
    fun `truncated file is not installed`() {
        val file = fileWith("ab")
        assertFalse(ModelIntegrity.isInstalledAgainst(file, payloadSize, payloadSha))
    }

    @Test
    fun `correctly sized file without marker is treated as installed`() {
        val file = fileWith(payload)
        assertTrue(ModelIntegrity.isInstalledAgainst(file, payloadSize, payloadSha))
    }

    @Test
    fun `stale marker with wrong digest is not installed`() {
        val file = fileWith(payload)
        ModelIntegrity.markerFile(file).writeText("0000")
        assertFalse(ModelIntegrity.isInstalledAgainst(file, payloadSize, payloadSha))
    }

    @Test
    fun `verify succeeds for matching content and writes marker`() {
        val file = fileWith(payload)
        assertTrue(ModelIntegrity.verifyAgainst(file, payloadSize, payloadSha))
        assertEquals(payloadSha, ModelIntegrity.markerFile(file).readText())
    }

    @Test
    fun `verify fails when content does not match expected digest`() {
        val file = fileWith("abd")
        assertFalse(ModelIntegrity.verifyAgainst(file, payloadSize, payloadSha))
        assertFalse("marker must not survive a failed verify", ModelIntegrity.markerFile(file).exists())
    }

    @Test
    fun `verify fails on size mismatch and clears an existing marker`() {
        val file = fileWith(payload)
        ModelIntegrity.markerFile(file).writeText(payloadSha)
        assertFalse(ModelIntegrity.verifyAgainst(file, payloadSize + 1, payloadSha))
        assertFalse(ModelIntegrity.markerFile(file).exists())
    }

    @Test
    fun `verify fails for missing file`() {
        assertFalse(ModelIntegrity.verifyAgainst(File(temp.root, "nope.bin"), payloadSize, payloadSha))
    }

    @Test
    fun `every shipped model pins a plausible digest and size`() {
        for (model in WhisperModel.entries) {
            assertTrue("${model.name} size must be positive", model.sizeBytes > 0)
            assertEquals("${model.name} digest must be 64 hex chars", 64, model.sha256.length)
            assertTrue(
                "${model.name} digest must be lowercase hex",
                model.sha256.all { it in "0123456789abcdef" }
            )
        }
        assertEquals(
            "model sizes must be distinct",
            WhisperModel.entries.size,
            WhisperModel.entries.map { it.sizeBytes }.toSet().size
        )
    }

    @Test
    fun `shipped model digests are the published upstream values`() {
        assertEquals(77_691_713L, WhisperModel.TINY.sizeBytes)
        assertEquals(
            "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21",
            WhisperModel.TINY.sha256
        )
        assertEquals(147_951_465L, WhisperModel.BASE.sizeBytes)
        assertEquals(
            "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe",
            WhisperModel.BASE.sha256
        )
        assertEquals(487_601_967L, WhisperModel.SMALL.sizeBytes)
        assertEquals(
            "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b",
            WhisperModel.SMALL.sha256
        )
    }
}
