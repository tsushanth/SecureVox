package com.securevox.app

import android.app.Application
import android.util.Log
import com.securevox.app.data.local.SecureVoxDatabase
import com.securevox.app.whisper.ModelManager
import com.tiktok.TikTokBusinessSdk

class SecureVoxApp : Application() {

    val database: SecureVoxDatabase by lazy {
        SecureVoxDatabase.getInstance(this)
    }

    val modelManager: ModelManager by lazy {
        ModelManager(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        initTikTokSdk()
    }

    private fun initTikTokSdk() {
        try {
            val ttConfig = TikTokBusinessSdk.TTConfig(this)
                .setAppId("YOUR_TIKTOK_APP_ID") // TODO: Replace with actual TikTok App ID from TikTok for Business
                .setLogLevel(TikTokBusinessSdk.LogLevel.INFO)
            TikTokBusinessSdk.initializeSdk(ttConfig)
            Log.d(TAG, "TikTok Business SDK initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TikTok Business SDK", e)
        }
    }

    companion object {
        private const val TAG = "SecureVoxApp"
        lateinit var instance: SecureVoxApp
            private set
    }
}
