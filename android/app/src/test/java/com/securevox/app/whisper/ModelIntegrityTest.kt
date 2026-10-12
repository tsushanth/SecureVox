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
    private val version = 31L

    private fun fileWith(contents: String, name: String = "model.bin"): File =
        temp.newFile(name).apply { writeText(contents) }

    @Test
    fun `sha256 matches known digest`() {
        assertEquals(payloadSha, ModelIntegrity.sha256(fileWith(payload)))
    }

    @Test
    fun `sha256 of empty file matches known digest`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ModelIntegrity.sha256(temp.newFile("empty.bin"))
        )
    }

    @Test
    fun `missing file is not installed`() {
        assertFalse(
            ModelIntegrity.isInstalledAgainst(File(temp.root, "nope.bin"), payloadSize, payloadSha)
        )
    }

    @Test
    fun `truncated file is not installed`() {
        assertFalse(ModelIntegrity.isInstalledAgainst(fileWith("ab"), payloadSize, payloadSha))
    }

    @Test
    fun `correctly sized file without marker is treated as installed`() {
        assertTrue(ModelIntegrity.isInstalledAgainst(fileWith(payload), payloadSize, payloadSha))
    }

    @Test
    fun `marker with wrong digest is not installed`() {
        val file = fileWith(payload)
        ModelIntegrity.markerFile(file).writeText("0000:$version")
        assertFalse(ModelIntegrity.isInstalledAgainst(file, payloadSize, payloadSha))
    }

    @Test
    fun `verify succeeds for matching content and stamps version`() {
        val file = fileWith(payload)
        assertTrue(ModelIntegrity.verifyAgainst(file, payloadSize, payloadSha, version))
        assertEquals("$payloadSha:$version", ModelIntegrity.markerFile(file).readText())
    }

    @Test
    fun `verify fails when content does not match expected digest`() {
        val file = fileWith("abd")
        assertFalse(ModelIntegrity.verifyAgainst(file, payloadSize, payloadSha, version))
        assertFalse(
            "marker must not survive a failed verify",
            ModelIntegrity.markerFile(file).exists()
        )
    }

    @Test
    fun `verify fails on size mismatch and clears an existing marker`() {
        val file = fileWith(payload)
        ModelIntegrity.markerFile(file).writeText("$payloadSha:$version")
        assertFalse(ModelIntegrity.verifyAgainst(file, payloadSize + 1, payloadSha, version))
        assertFalse(ModelIntegrity.markerFile(file).exists())
    }

    @Test
    fun `verify fails for missing file`() {
        assertFalse(
            ModelIntegrity.verifyAgainst(File(temp.root, "nope.bin"), payloadSize, payloadSha, version)
        )
    }

    // Regression: verifyInBackground used to short-circuit whenever a marker was
    // present, so a forged or stale marker meant the file was never hashed and
    // silent corruption went undetected.

    @Test
    fun `needs verification when marker is absent`() {
        assertTrue(ModelIntegrity.needsVerification(fileWith(payload), version))
    }

    @Test
    fun `needs verification when marker is from a different app version`() {
        val file = fileWith(payload)
        ModelIntegrity.markerFile(file).writeText("$payloadSha:30")
        assertTrue(ModelIntegrity.needsVerification(file, version))
    }

    @Test
    fun `needs verification when marker holds only a bare digest`() {
        val file = fileWith(payload)
        ModelIntegrity.markerFile(file).writeText(payloadSha)
        assertTrue(ModelIntegrity.needsVerification(file, version))
    }

    @Test
    fun `does not need verification when marker matches app version`() {
        val file = fileWith(payload)
        ModelIntegrity.markerFile(file).writeText("$payloadSha:$version")
        assertFalse(ModelIntegrity.needsVerification(file, version))
    }

    @Test
    fun `forged marker does not make corrupt content pass verification`() {
        val file = fileWith("abd")
        // A marker that claims the correct digest for the wrong bytes.
        ModelIntegrity.markerFile(file).writeText("$payloadSha:$version")

        // The cheap check is fooled...
        assertTrue(ModelIntegrity.isInstalledAgainst(file, payloadSize, payloadSha))
        // ...but the model is still flagged for re-hashing, which then fails.
        assertTrue(ModelIntegrity.needsVerification(file, version + 1))
        assertFalse(ModelIntegrity.verifyAgainst(file, payloadSize, payloadSha, version))
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
