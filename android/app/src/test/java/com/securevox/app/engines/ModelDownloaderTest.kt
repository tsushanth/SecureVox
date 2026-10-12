package com.securevox.app.engines

import com.securevox.app.speakers.ArchiveModel
import com.securevox.app.speakers.DownloadCancelledException
import com.securevox.app.speakers.ModelDownloader
import com.securevox.app.speakers.ModelFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Download verification and archive unpacking, against local file:// fixtures (no network). */
class ModelDownloaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val abcSha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    private fun source(name: String, text: String): File = File(temp.root, "src-$name").apply { writeText(text) }

    private fun spec(file: File, name: String, text: String) =
        ModelFile(file.toURI().toString(), name, text.toByteArray().size.toLong(), ModelDownloader.sha256Hex(file))

    private fun expectFailure(message: String? = null, block: () -> Unit) {
        try {
            block()
            fail("expected failure")
        } catch (e: AssertionError) {
            throw e
        } catch (e: Exception) {
            if (message != null) assertTrue(e.message, e.message!!.contains(message))
        }
    }

    // --- plain downloads -------------------------------------------------------------------------------

    @Test fun `sha256Hex matches the known digest of abc`() {
        assertEquals(abcSha, ModelDownloader.sha256Hex(source("abc", "abc")))
    }

    @Test fun `verified download lands in place and reports progress`() {
        val src = source("a", "abc")
        val dir = File(temp.root, "models")
        val progress = mutableListOf<Float>()
        ModelDownloader.ensure(dir, listOf(ModelFile(src.toURI().toString(), "a.bin", 3, abcSha))) { progress += it }
        assertEquals("abc", File(dir, "a.bin").readText())
        assertFalse(File(dir, "a.bin.part").exists())
        assertEquals(1f, progress.last(), 0f)
        assertTrue(ModelDownloader.isReady(dir, listOf(ModelFile("", "a.bin", 3, abcSha))))
    }

    @Test fun `tampered content fails the hash and leaves nothing behind`() {
        val src = source("evil", "abd") // same size, different bytes
        val dir = File(temp.root, "models")
        expectFailure("integrity") {
            ModelDownloader.ensure(dir, listOf(ModelFile(src.toURI().toString(), "a.bin", 3, abcSha))) {}
        }
        assertFalse(File(dir, "a.bin").exists())
        assertFalse(File(dir, "a.bin.part").exists())
    }

    @Test fun `wrong size fails even when the hash is right`() {
        val src = source("a", "abc")
        val dir = File(temp.root, "models")
        expectFailure("integrity") {
            ModelDownloader.ensure(dir, listOf(ModelFile(src.toURI().toString(), "a.bin", 4, abcSha))) {}
        }
        assertFalse(File(dir, "a.bin").exists())
    }

    @Test fun `a failed download does not replace a previous good file`() {
        val dir = File(temp.root, "models").apply { mkdirs() }
        File(dir, "a.bin").writeText("older")
        val src = source("evil", "abd")
        // size differs from the on-disk file, so ensure() tries to re-download, and fails
        expectFailure { ModelDownloader.ensure(dir, listOf(ModelFile(src.toURI().toString(), "a.bin", 3, abcSha))) {} }
        assertEquals("older", File(dir, "a.bin").readText())
    }

    @Test fun `cancel stops the download and removes the partial file`() {
        val src = File(temp.root, "big").apply { writeBytes(ByteArray(1_000_000) { 1 }) }
        val dir = File(temp.root, "models")
        val file = ModelFile(src.toURI().toString(), "big.bin", 1_000_000, ModelDownloader.sha256Hex(src))
        var calls = 0
        try {
            ModelDownloader.ensure(dir, listOf(file), isCancelled = { ++calls > 2 }) {}
            fail("expected cancel")
        } catch (e: DownloadCancelledException) {
            // expected
        }
        assertFalse(File(dir, "big.bin").exists())
        assertFalse(File(dir, "big.bin.part").exists())
    }

    // --- archives --------------------------------------------------------------------------------------

    private fun tarBz2(vararg entries: Pair<String, String>): File {
        val out = File(temp.root, "model-${System.nanoTime()}.tar.bz2")
        TarArchiveOutputStream(BZip2CompressorOutputStream(out.outputStream())).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            for ((name, text) in entries) {
                val bytes = text.toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
        return out
    }

    private fun fileSpec(name: String, text: String): ModelFile {
        val f = source(name, text)
        return ModelFile("", name, text.toByteArray().size.toLong(), ModelDownloader.sha256Hex(f))
    }

    @Test fun `extracts only the pinned files, ignoring directories and extras`() {
        val model = fileSpec("model.int8.onnx", "model-bytes")
        val tokens = fileSpec("tokens.txt", "a 0\nb 1\n")
        val archive = tarBz2(
            "pkg/model.int8.onnx" to "model-bytes",
            "pkg/tokens.txt" to "a 0\nb 1\n",
            "pkg/test_wavs/readme.txt" to "ignored",
            "pkg/other.bin" to "ignored"
        )
        val dest = File(temp.root, "out").apply { mkdirs() }
        ModelDownloader.extractModelFiles(archive, listOf(model, tokens), dest)
        assertEquals(setOf("model.int8.onnx", "tokens.txt"), dest.list()!!.toSet())
        assertEquals("model-bytes", File(dest, "model.int8.onnx").readText())
    }

    @Test fun `zip-slip entries are rejected and nothing is written outside`() {
        val model = fileSpec("model.int8.onnx", "model-bytes")
        val archive = tarBz2("../../escape/model.int8.onnx" to "model-bytes")
        val dest = File(temp.root, "out/inner").apply { mkdirs() }
        expectFailure("Unsafe") { ModelDownloader.extractModelFiles(archive, listOf(model), dest) }
        assertFalse(File(temp.root, "escape").exists())
        assertFalse(File(temp.root, "out/escape").exists())
        assertEquals(0, dest.list()!!.size)
    }

    @Test fun `a malicious entry anywhere in the archive rejects the whole archive`() {
        val model = fileSpec("model.int8.onnx", "model-bytes")
        val archive = tarBz2(
            "pkg/model.int8.onnx" to "model-bytes",
            "pkg/../../evil.sh" to "x"
        )
        val dest = File(temp.root, "out/inner").apply { mkdirs() }
        expectFailure("Unsafe") { ModelDownloader.extractModelFiles(archive, listOf(model), dest) }
    }

    @Test fun `safeEntryName rules`() {
        val wanted = setOf("model.int8.onnx")
        assertEquals("model.int8.onnx", ModelDownloader.safeEntryName("pkg/model.int8.onnx", wanted))
        assertEquals("model.int8.onnx", ModelDownloader.safeEntryName("./model.int8.onnx", wanted))
        assertNull(ModelDownloader.safeEntryName("pkg/other.bin", wanted))
        assertNull(ModelDownloader.safeEntryName("pkg/", wanted))
        for (bad in listOf("../x", "a/../../x", "/etc/passwd", "a\\..\\x", "a/b\u0000c")) {
            expectFailure("Unsafe") { ModelDownloader.safeEntryName(bad, wanted) }
        }
    }

    @Test fun `extracted file with the wrong hash is rejected and removed`() {
        val good = fileSpec("tokens.txt", "a 0\n")
        val archive = tarBz2("pkg/tokens.txt" to "a 1\n") // same length, different content
        val dest = File(temp.root, "out").apply { mkdirs() }
        expectFailure("integrity") { ModelDownloader.extractModelFiles(archive, listOf(good), dest) }
        assertEquals(0, dest.list()!!.size)
    }

    @Test fun `extracted file with the wrong size is rejected`() {
        val good = fileSpec("tokens.txt", "a 0\n")
        val archive = tarBz2("pkg/tokens.txt" to "a 0\nextra")
        val dest = File(temp.root, "out").apply { mkdirs() }
        expectFailure("size") { ModelDownloader.extractModelFiles(archive, listOf(good), dest) }
    }

    @Test fun `missing pinned file is an error`() {
        val model = fileSpec("model.int8.onnx", "model-bytes")
        val tokens = fileSpec("tokens.txt", "a 0\n")
        val archive = tarBz2("pkg/model.int8.onnx" to "model-bytes")
        val dest = File(temp.root, "out").apply { mkdirs() }
        expectFailure("missing") { ModelDownloader.extractModelFiles(archive, listOf(model, tokens), dest) }
    }

    @Test fun `ensureArchive downloads, verifies the archive, unpacks and deletes it`() {
        val model = fileSpec("model.int8.onnx", "model-bytes")
        val archive = tarBz2("pkg/model.int8.onnx" to "model-bytes", "pkg/readme" to "x")
        val pin = ArchiveModel(archive.toURI().toString(), archive.length(), ModelDownloader.sha256Hex(archive), listOf(model))
        val dir = File(temp.root, "engine")
        val progress = mutableListOf<Float>()
        ModelDownloader.ensureArchive(dir, pin) { progress += it }
        assertEquals(listOf("model.int8.onnx"), dir.list()!!.toList())
        assertEquals(1f, progress.last(), 0f)
        assertTrue(ModelDownloader.isReady(dir, pin.files))
        // already installed: nothing to do
        ModelDownloader.ensureArchive(dir, pin) { fail("should not download again") }
    }

    @Test fun `ensureArchive rejects a tampered archive before unpacking it`() {
        val model = fileSpec("model.int8.onnx", "model-bytes")
        val archive = tarBz2("pkg/model.int8.onnx" to "model-bytes")
        val pin = ArchiveModel(archive.toURI().toString(), archive.length(), "0".repeat(64), listOf(model))
        val dir = File(temp.root, "engine")
        expectFailure("integrity") { ModelDownloader.ensureArchive(dir, pin) {} }
        assertEquals(0, dir.list()!!.size)
    }

    // --- pins --------------------------------------------------------------------------------------------

    @Test fun `engine pins are well formed`() {
        for (pkg in listOf(EngineModels.NEMO_FAST_CONFORMER, EngineModels.SENSE_VOICE)) {
            for (f in pkg.files) {
                assertTrue(f.fileName, f.sha256.matches(Regex("[0-9a-f]{64}")))
                assertTrue(f.fileName, f.sizeBytes > 0)
            }
            pkg.archive?.let {
                assertTrue(it.url.startsWith("https://"))
                assertTrue(it.sha256.matches(Regex("[0-9a-f]{64}")))
            }
            if (pkg.archive == null) pkg.files.forEach { assertTrue(it.url.startsWith("https://")) }
        }
        assertEquals(102_875_642L, EngineModels.NEMO_FAST_CONFORMER.archive!!.sizeBytes)
        assertEquals(103, EngineModels.NEMO_FAST_CONFORMER.downloadMb)
        assertEquals(238, EngineModels.SENSE_VOICE.downloadMb)
    }
}
