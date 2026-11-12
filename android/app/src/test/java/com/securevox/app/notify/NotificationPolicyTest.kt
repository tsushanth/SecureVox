package com.securevox.app.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPolicyTest {

    @Test
    fun androidBefore13NeedsNoPrompt() {
        assertFalse(NotificationPermission.shouldOffer(sdkInt = 32, granted = false, askedBefore = false))
        assertFalse(NotificationPermission.shouldOffer(sdkInt = 26, granted = false, askedBefore = false))
    }

    @Test
    fun android13OffersOnceWhenNotGranted() {
        assertTrue(NotificationPermission.shouldOffer(sdkInt = 33, granted = false, askedBefore = false))
        assertTrue(NotificationPermission.shouldOffer(sdkInt = 36, granted = false, askedBefore = false))
    }

    @Test
    fun neverAsksAgainAfterAnswerOrWhenAlreadyGranted() {
        assertFalse(NotificationPermission.shouldOffer(sdkInt = 34, granted = false, askedBefore = true))
        assertFalse(NotificationPermission.shouldOffer(sdkInt = 34, granted = true, askedBefore = false))
    }

    @Test
    fun postsWhenAllowedAndNobodyIsLookingAtIt() {
        assertTrue(ReadyNotification.shouldPost(true, channelBlocked = false, viewingThisRecording = false, hasTranscript = true))
    }

    @Test
    fun respectsPermissionAndChannelSettings() {
        assertFalse(ReadyNotification.shouldPost(false, false, false, true))
        assertFalse(ReadyNotification.shouldPost(true, true, false, true))
    }

    @Test
    fun staysQuietWhenTheUserIsAlreadyOnThatRecording() {
        assertFalse(ReadyNotification.shouldPost(true, false, viewingThisRecording = true, hasTranscript = true))
    }

    @Test
    fun anEmptyResultIsNotAnnouncedAsReady() {
        assertFalse(ReadyNotification.shouldPost(true, false, false, hasTranscript = false))
    }

    @Test
    fun viewedRecordingTracksOnlyTheScreenThatIsResumed() {
        ViewedRecording.enter("a")
        assertEquals("a", ViewedRecording.current)
        // A different recording leaving must not clear this one.
        ViewedRecording.leave("b")
        assertEquals("a", ViewedRecording.current)
        ViewedRecording.enter("b")
        ViewedRecording.leave("a") // late pause of the previous screen
        assertEquals("b", ViewedRecording.current)
        ViewedRecording.leave("b")
        assertNull(ViewedRecording.current)
    }
}
