package com.securevox.app

import android.app.Application
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
    }

    companion object {
        lateinit var instance: SecureVoxApp
            private set
    }
}
