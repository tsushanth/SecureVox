package com.securevox.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportConsentTest {

    /** In-memory stand-in for SharedPreferences, including the saved crash report. */
    private class FakeStore : ConsentStore {
        val booleans = HashMap<String, Boolean>()
        val pendingCrash get() = strings[ConsentState.PENDING_CRASH]
        val strings = HashMap<String, String>()
        override fun getBoolean(key: String, default: Boolean) = booleans[key] ?: default
        override fun apply(booleans: Map<String, Boolean>, remove: Set<String>) {
            this.booleans.putAll(booleans)
            remove.forEach { strings.remove(it); this.booleans.remove(it) }
        }
    }

    // ---- default off ----

    @Test fun reportingIsOffByDefault() {
        assertFalse(ReportConsent.DEFAULT_ENABLED)
        val state = ConsentState(FakeStore())
        assertFalse(state.isEnabled())
        assertFalse(state.hasAnswered())
    }

    @Test fun nothingIsStoredUntilTheUserChooses() {
        val store = FakeStore()
        ConsentState(store).isEnabled()
        ConsentState(store).hasAnswered()
        assertTrue(store.booleans.isEmpty())
    }

    // ---- choices ----

    @Test fun turningOnEnablesAndCountsAsAnswered() {
        val state = ConsentState(FakeStore())
        state.setEnabled(true)
        assertTrue(state.isEnabled())
        assertTrue(state.hasAnswered())
    }

    @Test fun noThanksStaysOffAndCountsAsAnswered() {
        val state = ConsentState(FakeStore())
        state.setEnabled(false)
        assertFalse(state.isEnabled())
        assertTrue(state.hasAnswered())
    }

    @Test fun turningOffStopsSendingAtOnce() {
        val store = FakeStore()
        val state = ConsentState(store)
        state.setEnabled(true)
        assertTrue(state.isEnabled())
        state.setEnabled(false)
        assertFalse(state.isEnabled())
        // a second reader of the same storage (the crash handler, the worker thread) sees it too
        assertFalse(ConsentState(store).isEnabled())
    }

    @Test fun turningOffDiscardsAWaitingCrashReport() {
        val store = FakeStore()
        val state = ConsentState(store)
        state.setEnabled(true)
        store.strings[ConsentState.PENDING_CRASH] = "{\"kind\":\"crash\"}"
        state.setEnabled(false)
        assertEquals(null, store.pendingCrash)
    }

    @Test fun turningOnKeepsAWaitingCrashReport() {
        val store = FakeStore()
        store.strings[ConsentState.PENDING_CRASH] = "{\"kind\":\"crash\"}"
        ConsentState(store).setEnabled(true)
        assertEquals("{\"kind\":\"crash\"}", store.pendingCrash)
    }

    @Test fun choiceSurvivesARestart() {
        val store = FakeStore()
        ConsentState(store).setEnabled(true)
        assertTrue(ConsentState(store).isEnabled())
        ConsentState(store).setEnabled(false)
        assertFalse(ConsentState(store).isEnabled())
        assertTrue(ConsentState(store).hasAnswered())
    }

    // ---- the one-time prompt ----

    @Test fun promptWaitsForTheFirstFinishedTranscription() {
        assertFalse(ReportConsent.shouldPrompt(answered = false, completedTranscriptions = 0))
        assertTrue(ReportConsent.shouldPrompt(answered = false, completedTranscriptions = 1))
        assertTrue(ReportConsent.shouldPrompt(answered = false, completedTranscriptions = 7))
    }

    @Test fun promptNeverAppearsOnceAnswered() {
        for (n in listOf(0, 1, 5)) assertFalse(ReportConsent.shouldPrompt(answered = true, completedTranscriptions = n))
    }

    @Test fun promptFlowFromFirstTranscriptionToAnswer() {
        val state = ConsentState(FakeStore())
        assertFalse(ReportConsent.shouldPrompt(state.hasAnswered(), 0))
        assertTrue(ReportConsent.shouldPrompt(state.hasAnswered(), 1))
        state.setEnabled(false) // "No thanks"
        assertFalse(ReportConsent.shouldPrompt(state.hasAnswered(), 2))
        assertFalse(state.isEnabled())
    }

    @Test fun usingTheSettingsSwitchFirstAlsoRetiresThePrompt() {
        val state = ConsentState(FakeStore())
        state.setEnabled(true) // Settings switch before any transcription finished
        assertFalse(ReportConsent.shouldPrompt(state.hasAnswered(), 3))
    }

    @Test fun ignoringThePromptNeverCountsAsConsent() {
        val state = ConsentState(FakeStore())
        assertTrue(ReportConsent.shouldPrompt(state.hasAnswered(), 4))
        assertFalse(state.isEnabled())
    }
}
