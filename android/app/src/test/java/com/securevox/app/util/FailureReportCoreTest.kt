package com.securevox.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class FailureReportCoreTest {

    // ---- sanitiser: nothing the user owns may survive ----

    private val leaky = listOf(
        "/storage/emulated/0/Download/Meeting with Bob.mp3: open failed: ENOENT",
        "/data/user/0/com.securevox.app/files/recordings/recording_20261101_101010.wav not found",
        "content://com.android.providers.media.documents/document/audio%3A1234",
        "file:///sdcard/Notes/Therapy session.m4a",
        "Unable to open Quarterly Review.mp4",
        "mail to jane.doe@example.com failed",
        "failed on 'my secret dictation about the merger'",
        "bad value \"call dad at 6\"",
        "id 3f2b8c1e-9a4d-4e0b-8f1a-1234567890ab missing",
        "hash deadbeefdeadbeefdeadbeef",
        "call 4155550123 now",
        "Authorization: Bearer abc123.def456",
        "token_ABCDEFGHIJKLMNOPQRSTUV leaked"
    )

    @Test fun messageStripsUserContent() {
        val banned = listOf(
            "Meeting", "Bob", "mp3", "recording_2026", "recordings", "Download", "Therapy", "Quarterly",
            "jane", "example.com", "merger", "call dad", "3f2b8c1e", "deadbeef", "4155550123", "abc123", "ABCDEFGH",
            "/storage", "content://", "file://", ".wav", ".m4a", ".mp4"
        )
        for (raw in leaky) {
            val clean = FailureSanitizer.message(raw)
            for (b in banned) assertFalse("'$b' leaked from [$raw] -> [$clean]", clean.contains(b))
        }
    }

    @Test fun messageKeepsTheUsefulPart() {
        assertEquals("<path>: open failed: ENOENT", FailureSanitizer.message("/a/b c/d.wav: open failed: ENOENT"))
        assertTrue(FailureSanitizer.message("write failed: ENOSPC (No space left on device)").contains("ENOSPC"))
        assertEquals("Incomplete download: got 123 of 456 bytes", FailureSanitizer.message("Incomplete download: got 123 of 456 bytes"))
    }

    @Test fun messageIsAsciiAndBounded() {
        val clean = FailureSanitizer.message("café 你好 — " + "x".repeat(5000))
        assertTrue(clean.length <= FailureSanitizer.MAX_MESSAGE)
        assertTrue(clean.all { it.code in 0x20..0x7e })
        assertEquals("", FailureSanitizer.message(null))
        assertEquals("", FailureSanitizer.message("   "))
    }

    @Test fun andOrAndRatiosAreNotPaths() {
        assertEquals("read and/or write failed", FailureSanitizer.message("read and/or write failed"))
        assertEquals("1 / 3 chunks", FailureSanitizer.message("1 / 3 chunks"))
    }

    @Test fun flowNamesAreStableCodes() {
        assertEquals("recording.start", FailureSanitizer.flow("recording.start"))
        assertEquals("recording.start", FailureSanitizer.flow(" Recording.Start "))
        assertEquals("my_meeting_mp3", FailureSanitizer.flow("My Meeting mp3"))
        assertEquals("unknown", FailureSanitizer.flow(""))
        assertTrue(FailureSanitizer.flow("a".repeat(500)).length <= FailureSanitizer.MAX_FLOW)
    }

    @Test fun contextIsAllowlistedAndTokenOnly() {
        val ctx = FailureSanitizer.context(
            mapOf(
                "model" to "ggml-base.bin",
                "chunk" to "3",
                "code" to "-3",
                "free_mb" to "512",
                "title" to "Board meeting",          // key not allowed
                "reason" to "Meeting with Bob.mp3",  // value not a token
                "stage" to "/sdcard/x/y",            // path
                "engine" to "recording_20261101.wav", // file name
                "status" to "a@b.com"                // email
            )
        )
        assertEquals(mapOf("model" to "ggml-base.bin", "chunk" to "3", "code" to "-3", "free_mb" to "512"), ctx)
    }

    @Test fun stackHasClassesAndLinesButNoMessages() {
        val secret = "my private dictation text"
        val t = IllegalStateException("bad $secret in /sdcard/Notes/a.wav", IOException("inner $secret"))
        val stack = FailureSanitizer.stack(t)
        assertTrue(stack.startsWith("java.lang.IllegalStateException\n"))
        assertTrue(stack.contains("Caused by: java.io.IOException\n"))
        assertTrue(stack.contains("\tat "))
        assertFalse(stack.contains(secret))
        assertFalse(stack.contains("/sdcard"))
        assertTrue(stack.length <= FailureSanitizer.MAX_STACK)
        assertEquals("", FailureSanitizer.stack(null))
    }

    @Test fun stackIsBoundedAndSurvivesCauseLoops() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        val stack = FailureSanitizer.stack(b)
        assertTrue(stack.length <= FailureSanitizer.MAX_STACK)
    }

    @Test fun describeScrubsTheMessage() {
        val d = FailureSanitizer.describe(IOException("/sdcard/Private Notes/a.wav: open failed"))
        assertTrue(d.startsWith("IOException: "))
        assertFalse(d.contains("Private"))
        assertEquals("IOException", FailureSanitizer.describe(IOException()))
        assertEquals("", FailureSanitizer.describe(null))
    }

    // ---- classification ----

    @Test fun storageFullIsDetectedAnywhereInTheChain() {
        assertTrue(FailureSanitizer.isStorageFull(IOException("write failed: ENOSPC (No space left on device)")))
        assertTrue(FailureSanitizer.isStorageFull(RuntimeException("x", IOException("No space left on device"))))
        assertFalse(FailureSanitizer.isStorageFull(IOException("broken pipe")))
        assertFalse(FailureSanitizer.isStorageFull(null))
    }

    @Test fun connectivityAndCancellationAreNotFailures() {
        assertTrue(FailureSanitizer.isConnectivity(UnknownHostException("huggingface.co")))
        assertTrue(FailureSanitizer.isConnectivity(RuntimeException("x", java.net.SocketTimeoutException())))
        assertFalse(FailureSanitizer.isConnectivity(IOException("disk")))
        assertTrue(FailureSanitizer.isCancellation(java.util.concurrent.CancellationException("job cancelled")))
        assertFalse(FailureSanitizer.isCancellation(IllegalStateException()))
    }

    // ---- payload shape ----

    @Suppress("UNCHECKED_CAST")
    private fun parse(json: String) = MiniJson.parse(json) as Map<String, Any?>

    @Test fun payloadHasExactlyTheAgreedFields() {
        val json = ReportPayload.build(
            kind = "failure", version = "1.5.1", flow = "recording.start", message = "mic_unavailable",
            stack = "java.lang.Foo\n\tat com.securevox.app.X.y(X.kt:1)\n", context = mapOf("code" to "-3", "title" to "Board meeting"),
            device = "Google Pixel 9 Pro", androidRelease = "15", sdk = 35
        )
        val m = parse(json)
        assertEquals(setOf("kind", "platform", "version", "flow", "message", "stack", "context"), m.keys)
        assertEquals("failure", m["kind"])
        assertEquals("android", m["platform"])
        assertEquals("1.5.1", m["version"])
        assertEquals("recording.start", m["flow"])
        assertEquals("mic_unavailable", m["message"])
        @Suppress("UNCHECKED_CAST")
        val ctx = m["context"] as Map<String, String>
        assertEquals(setOf("code", "device", "android", "sdk"), ctx.keys)
        assertEquals("Google Pixel 9 Pro", ctx["device"])
        assertEquals("15", ctx["android"])
        assertEquals("35", ctx["sdk"])
        assertFalse(json.contains("Board meeting"))
    }

    @Test fun payloadSanitisesEvenIfACallerForgets() {
        val json = ReportPayload.build(
            kind = "crash", version = "1.5.1", flow = "Uncaught Crash!", message = "failed on /sdcard/Voice/Therapy.wav for bob@x.com",
            stack = "", context = emptyMap(), device = "Acme é", androidRelease = "14", sdk = 34
        )
        val m = parse(json)
        assertEquals("uncaught_crash_", m["flow"])
        val msg = m["message"] as String
        assertFalse(msg.contains("Therapy")); assertFalse(msg.contains("bob")); assertFalse(msg.contains("/sdcard"))
        @Suppress("UNCHECKED_CAST")
        assertEquals("Acme ?", (m["context"] as Map<String, String>)["device"])
    }

    @Test fun payloadEscapesJsonAndNeverEmptyMessage() {
        val m = parse(ReportPayload.build("failure", "1", "f", "", "line1\n\t\"quoted\"\\", emptyMap(), "d", "r", 1))
        assertEquals("failure", m["message"])
        assertEquals("line1\n\t\"quoted\"\\", m["stack"])
    }

    // ---- rate limiting / dedupe ----

    @Test fun sameFailureIsSentOncePerHour() {
        var now = 0L
        val l = ReportLimiter({ now })
        assertTrue(l.allow("export.file", "IOException: ENOSPC"))
        assertFalse(l.allow("export.file", "IOException: ENOSPC"))
        now += 59 * 60_000L
        assertFalse(l.allow("export.file", "IOException: ENOSPC"))
        now += 2 * 60_000L
        assertTrue(l.allow("export.file", "IOException: ENOSPC"))
    }

    @Test fun numbersDoNotDefeatDedupe() {
        val l = ReportLimiter({ 0L })
        assertTrue(l.allow("model.download", "Incomplete download: got 100 of 200 bytes"))
        assertFalse(l.allow("model.download", "Incomplete download: got 150 of 200 bytes"))
    }

    @Test fun aFlowIsCappedAtThreePerHour() {
        var now = 0L
        val l = ReportLimiter({ now })
        assertTrue(l.allow("playback.load", "a"))
        assertTrue(l.allow("playback.load", "b"))
        assertTrue(l.allow("playback.load", "c"))
        assertFalse(l.allow("playback.load", "d"))
        assertTrue(l.allow("recording.start", "a")) // other flows unaffected
        now += 61 * 60_000L
        assertTrue(l.allow("playback.load", "d"))
    }

    @Test fun everythingTogetherIsCappedPerMinute() {
        var now = 0L
        val l = ReportLimiter({ now }, perFlowPerHour = 100, globalPerMinute = 10)
        repeat(10) { assertTrue(l.allow("f$it", "m")) }
        assertFalse(l.allow("f10", "m"))
        now += 61_000L
        assertTrue(l.allow("f10", "m"))
    }

    @Test fun aRefusedReportDoesNotUseUpBudget() {
        val l = ReportLimiter({ 0L }, perFlowPerHour = 2)
        assertTrue(l.allow("f", "same"))
        repeat(20) { assertFalse(l.allow("f", "same")) } // deduped, not counted
        assertTrue(l.allow("f", "other"))
    }
}
