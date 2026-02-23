package com.securevox.app

import android.app.Application
import com.facebook.FacebookSdk
import com.facebook.appevents.AppEventsLogger
import com.securevox.app.data.local.SecureVoxDatabase
import com.securevox.app.whisper.ModelManager

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

        // Initialize Facebook SDK
        FacebookSdk.sdkInitialize(this)
        AppEventsLogger.activateApp(this)
    }

    companion object {
        lateinit var instance: SecureVoxApp
            private set
    }
}
