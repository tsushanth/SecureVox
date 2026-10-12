package com.securevox.app.engines

import com.securevox.app.whisper.TranscriptionSegment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackEngineTest {

    private class Fake(
        override val id: EngineId,
        private val text: String,
        private val fail: Throwable? = null
    ) : TranscriptionEngine {
        var calls = 0
        var closed = false
        override suspend fun transcribe(request: ChunkRequest, onProgress: ((Int) -> Unit)?): List<TranscriptionSegment> {
            calls++
            fail?.let { throw it }
            return listOf(TranscriptionSegment(text, 0, 1000))
        }
        override fun close() { closed = true }
    }

    private val request = ChunkRequest(FloatArray(16000), "de")
    private val logs = mutableListOf<String>()
    private val log = { m: String, _: Throwable? -> logs += m; Unit }

    @Test fun `uses the alternative engine when it works`() = runBlocking {
        val primary = Fake(EngineId.NEMO_FAST_CONFORMER, "nemo")
        val whisper = Fake(EngineId.WHISPER, "whisper")
        val engine = FallbackEngine({ primary }, whisper, log)
        assertEquals("nemo", engine.transcribe(request, null).single().text)
        assertEquals(EngineId.NEMO_FAST_CONFORMER, engine.lastUsed)
        assertEquals(0, whisper.calls)
        assertTrue(logs.isEmpty())
    }

    @Test fun `falls back when the engine fails to load, and logs why`() = runBlocking {
        val whisper = Fake(EngineId.WHISPER, "whisper")
        val engine = FallbackEngine({ throw IllegalStateException("bad model") }, whisper, log)
        assertEquals("whisper", engine.transcribe(request, null).single().text)
        assertEquals(EngineId.WHISPER, engine.lastUsed)
        assertEquals(1, logs.size)
        assertTrue(logs[0].contains("Could not load"))
    }

    @Test fun `falls back when the engine throws mid-recording and stays on whisper`() = runBlocking {
        val primary = Fake(EngineId.SENSE_VOICE, "sv", fail = RuntimeException("boom"))
        val whisper = Fake(EngineId.WHISPER, "whisper")
        val engine = FallbackEngine({ primary }, whisper, log)
        assertEquals("whisper", engine.transcribe(request, null).single().text)
        assertEquals("whisper", engine.transcribe(request, null).single().text)
        assertEquals(1, primary.calls) // not retried after it failed
        assertTrue(primary.closed)
        assertEquals(2, whisper.calls)
        assertEquals(1, logs.size)
    }

    @Test fun `native-style errors are also caught`() = runBlocking {
        val primary = Fake(EngineId.SENSE_VOICE, "sv", fail = UnsatisfiedLinkError("no lib"))
        val whisper = Fake(EngineId.WHISPER, "whisper")
        val engine = FallbackEngine({ primary }, whisper, log)
        assertEquals("whisper", engine.transcribe(request, null).single().text)
    }

    @Test fun `no alternative engine means plain whisper`() = runBlocking {
        val whisper = Fake(EngineId.WHISPER, "whisper")
        val engine = FallbackEngine({ null }, whisper, log)
        assertEquals("whisper", engine.transcribe(request, null).single().text)
        assertTrue(logs.isEmpty())
    }

    @Test fun `close releases the alternative engine, not whisper, and runs the hook`() = runBlocking {
        val primary = Fake(EngineId.NEMO_FAST_CONFORMER, "nemo")
        val whisper = Fake(EngineId.WHISPER, "whisper")
        var hook = false
        val engine = FallbackEngine({ primary }, whisper, log, onClose = { hook = true })
        engine.transcribe(request, null)
        engine.close()
        assertTrue(primary.closed)
        assertTrue(!whisper.closed)
        assertTrue(hook)
        engine.close() // idempotent
    }

    @Test fun `cancellation is not swallowed`() {
        val primary = Fake(EngineId.NEMO_FAST_CONFORMER, "nemo", fail = kotlinx.coroutines.CancellationException("cancelled"))
        val whisper = Fake(EngineId.WHISPER, "whisper")
        val engine = FallbackEngine({ primary }, whisper, log)
        try {
            runBlocking { engine.transcribe(request, null) }
            throw AssertionError("expected cancellation")
        } catch (e: kotlinx.coroutines.CancellationException) {
            assertSame("cancelled", e.message)
        }
        assertEquals(0, whisper.calls)
    }
}
