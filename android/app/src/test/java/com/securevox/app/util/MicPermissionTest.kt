package com.securevox.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class MicPermissionTest {

    @Test
    fun grantedProceeds() {
        for (rationale in listOf(true, false)) for (asked in listOf(true, false)) {
            assertEquals(MicStep.PROCEED, MicPermission.decide(true, rationale, asked))
        }
    }

    @Test
    fun firstTapExplainsBeforeAsking() {
        assertEquals(MicStep.EXPLAIN_THEN_ASK, MicPermission.decide(false, shouldShowRationale = false, askedBefore = false))
    }

    @Test
    fun afterOneDenialAndroidStillAsksSoWeExplainAgain() {
        assertEquals(MicStep.EXPLAIN_THEN_ASK, MicPermission.decide(false, shouldShowRationale = true, askedBefore = true))
    }

    @Test
    fun permanentDenialSendsToSettings() {
        // Android reports no rationale after "Don't ask again" / a second denial, and we did ask before.
        assertEquals(MicStep.OPEN_SETTINGS, MicPermission.decide(false, shouldShowRationale = false, askedBefore = true))
    }
}
