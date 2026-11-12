package com.securevox.app

import android.app.Application
import com.kreativekoala.ratingkit.RatingKit
import com.securevox.app.data.DurationRepair
import com.securevox.app.data.LegacySettings
import com.securevox.app.data.local.SecureVoxDatabase
import com.securevox.app.presentation.settings.dataStore
import com.securevox.app.tts.ReadAloudManager
import com.securevox.app.util.CrashLogger
import com.securevox.app.util.FailureReporter
import com.securevox.app.util.funnel.FunnelHolder
import com.securevox.app.util.funnel.FunnelLedger
import com.securevox.app.util.funnel.PrefsLedgerStore
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
        // On-phone usage ledger (Tier A): counts of setup and usage steps, shown under Settings > On this phone.
        // Local only: no network, nothing leaves the phone unless the user attaches it to a problem report.
        FunnelHolder.install(FunnelLedger(PrefsLedgerStore(this), System::currentTimeMillis))
        // Central crash + handled-failure reporting (diagnostics only, never user content). Opt-in: nothing is
        // sent until the user turns it on (card after the first transcription, or Settings). Installed first so the local CrashLogger below chains to it. The key identifies this
        // app to the reporter Worker: it ships in the app like the other apps' keys, an identifier not a secret.
        val versionName = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown" } catch (_: Exception) { "unknown" }
        FailureReporter.init(this, "securevox", REPORTER_KEY, versionName)
        // Local-only failure visibility: no backend, no crash-reporting SDK. Logs uncaught
        // exceptions to a private on-disk file the user can attach via Settings > Report a
        // Problem. Chains to the previous default handler so normal OS crash behavior continues.
        CrashLogger.install(this)
        RatingKit.init(this, appId = "securevox")
        // Rating seam: when RatingKit's event listener lands, adapt it to RatingFunnelAdapter here (see RatingSeam.kt).
        appScope.launch { runCatching { DurationRepair.runOnce(this@SecureVoxApp) } }
        // Drop values left by settings that were shown but never did anything (Recycle Bin, Auto-Delete Audio).
        appScope.launch { runCatching { LegacySettings.purge(dataStore) } }
    }

    companion object {
        private const val REPORTER_KEY = "afr_451d4d145abb3eca7c741cccd9000c6c"

        lateinit var instance: SecureVoxApp
            private set
    }
}
