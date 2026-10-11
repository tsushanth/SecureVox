package com.securevox.app

import android.app.Application
import com.kreativekoala.ratingkit.RatingKit
import com.securevox.app.data.DurationRepair
import com.securevox.app.data.local.SecureVoxDatabase
import com.securevox.app.tts.ReadAloudManager
import com.securevox.app.util.CrashLogger
import com.securevox.app.whisper.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SecureVoxApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        // Local-only failure visibility: no backend, no crash-reporting SDK. Logs uncaught
        // exceptions to a private on-disk file the user can attach via Settings > Report a
        // Problem. Chains to the previous default handler so normal OS crash behavior continues.
        CrashLogger.install(this)
        RatingKit.init(this, appId = "securevox")
        appScope.launch { runCatching { DurationRepair.runOnce(this@SecureVoxApp) } }
    }

    companion object {
        lateinit var instance: SecureVoxApp
            private set
    }
}
