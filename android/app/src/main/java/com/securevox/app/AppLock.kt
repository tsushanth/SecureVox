package com.securevox.app

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.fragment.app.FragmentActivity

/** Biometric or screen-lock gate in front of the whole app. */
object AppLock {

    val KEY_ENABLED = booleanPreferencesKey("app_lock_enabled")

    /** Re-lock only after the app has been out of sight this long, so the file picker and permission dialogs don't trigger it. */
    const val RELOCK_AFTER_MS = 30_000L

    /**
     * Strongest authenticator set this device can actually use, or null when it has no screen
     * lock or biometric at all. Biometric plus device credential is not supported before
     * Android 11, so older devices fall back to biometric only.
     */
    private fun authenticators(context: Context): Int? {
        val manager = BiometricManager.from(context)
        return listOf(BIOMETRIC_WEAK or DEVICE_CREDENTIAL, BIOMETRIC_WEAK).firstOrNull {
            manager.canAuthenticate(it) == BiometricManager.BIOMETRIC_SUCCESS
        }
    }

    fun isAvailable(context: Context): Boolean = authenticators(context) != null

    /**
     * Shows the system prompt. With no screen lock set up there is nothing to check against
     * (anyone holding the phone already has everything), so it unlocks instead of locking
     * the owner out of their recordings.
     */
    fun authenticate(activity: FragmentActivity, onSuccess: () -> Unit) {
        val allowed = authenticators(activity)
        if (allowed == null) {
            onSuccess()
            return
        }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.lock_prompt_title))
            .setSubtitle(activity.getString(R.string.lock_prompt_subtitle))
            .setAllowedAuthenticators(allowed)
            .apply {
                if (allowed and DEVICE_CREDENTIAL == 0) {
                    setNegativeButtonText(activity.getString(R.string.cancel))
                }
            }
            .build()
        prompt.authenticate(info)
    }
}
