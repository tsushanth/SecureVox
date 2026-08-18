package com.securevox.app

import android.app.Application
import com.kreativekoala.ratingkit.RatingKit
import com.securevox.app.data.local.SecureVoxDatabase
import com.securevox.app.tts.ReadAloudManager
import com.securevox.app.whisper.ModelManager

class SecureVoxApp : Application() {

    val database: SecureVoxDatabase by lazy {
        SecureVoxDatabase.getInstance(this)
    }

    val modelManager: ModelManager by lazy {
        ModelManager(this)
    }

    val readAloudManager: ReadAloudManager by lazy {
        ReadAloudManager.get(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        RatingKit.init(this, appId = "securevox")
    }

    companion object {
        lateinit var instance: SecureVoxApp
            private set
    }
}
