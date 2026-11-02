package com.securevox.app

import android.app.Application
import com.kreativekoala.ratingkit.RatingKit
import com.securevox.app.data.DurationRepair
import com.securevox.app.data.local.SecureVoxDatabase
import com.securevox.app.tts.ReadAloudManager
import com.securevox.app.util.CrashLogger
import com.securevox.app.util.FailureReporter
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
        // Central crash + handled-failure reporting (diagnostics only, never user content; the user can turn it
        // off in Settings). Installed first so the local CrashLogger below chains to it. The key identifies this
        // app to the reporter Worker: it ships in the app like the other apps' keys, an identifier not a secret.
        val versionName = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown" } catch (_: Exception) { "unknown" }
        FailureReporter.init(this, "securevox", REPORTER_KEY, versionName)
        // Local-only failure visibility: no backend, no crash-reporting SDK. Logs uncaught
        // exceptions to a private on-disk file the user can attach via Settings > Report a
        // Problem. Chains to the previous default handler so normal OS crash behavior continues.
        CrashLogger.install(this)
        RatingKit.init(this, appId = "securevox")
        appScope.launch { runCatching { DurationRepair.runOnce(this@SecureVoxApp) } }
    }

    companion object {
        private const val REPORTER_KEY = "afr_451d4d145abb3eca7c741cccd9000c6c"

        lateinit var instance: SecureVoxApp
            private set
    }
}
