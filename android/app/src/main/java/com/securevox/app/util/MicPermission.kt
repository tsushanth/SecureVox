package com.securevox.app.util

/** What to do when the user taps Record and the microphone permission may be missing. */
enum class MicStep {
    /** Already granted: start recording. */
    PROCEED,
    /** Explain why the microphone is needed, then show the system prompt. */
    EXPLAIN_THEN_ASK,
    /** The system will no longer show its prompt: send the user to the app's Settings page. */
    OPEN_SETTINGS
}

object MicPermission {

    /**
     * [shouldShowRationale] is Android's `shouldShowRequestPermissionRationale`. It is false both before the
     * first request and after a permanent denial, so [askedBefore] (remembered by us) tells the two apart.
     */
    fun decide(granted: Boolean, shouldShowRationale: Boolean, askedBefore: Boolean): MicStep = when {
        granted -> MicStep.PROCEED
        !shouldShowRationale && askedBefore -> MicStep.OPEN_SETTINGS
        else -> MicStep.EXPLAIN_THEN_ASK
    }
}
