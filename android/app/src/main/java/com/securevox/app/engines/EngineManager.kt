package com.securevox.app.engines

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.securevox.app.presentation.settings.dataStore
import com.securevox.app.speakers.DownloadCancelledException
import com.securevox.app.speakers.ModelDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface EngineDownloadState {
    data object Idle : EngineDownloadState
    data class Downloading(val progress: Float) : EngineDownloadState
    data class Failed(val message: String) : EngineDownloadState
}

/**
 * Downloads, deletes and reports the alternative engines, and builds the per-recording engine for the worker.
 * Nothing here downloads on its own: only [download], called from a tap in Settings, does. Downloads run in a
 * process-wide scope so leaving the Settings screen does not cancel them.
 */
object EngineManager {
    private const val TAG = "EngineManager"
    private const val PREFS = "securevox_prefs"

    val KEY_WHISPER_ONLY = booleanPreferencesKey("engines_whisper_only")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<EngineId, Job>()

    private val _states = MutableStateFlow<Map<EngineId, EngineDownloadState>>(emptyMap())
    val states: StateFlow<Map<EngineId, EngineDownloadState>> = _states.asStateFlow()

    /** Bumped whenever an install or delete finishes, so the UI re-reads [isInstalled]. */
    private val _installVersion = MutableStateFlow(0)
    val installVersion: StateFlow<Int> = _installVersion.asStateFlow()

    fun isInstalled(context: Context, id: EngineId): Boolean {
        val pkg = EngineModels.packageFor(id) ?: return id == EngineId.WHISPER
        val dir = EngineModels.dir(context, id) ?: return false
        return ModelDownloader.isReady(dir, pkg.files)
    }

    fun installedEngines(context: Context): Set<EngineId> =
        EngineGates.available.filterTo(mutableSetOf()) { isInstalled(context, it) }

    fun download(context: Context, id: EngineId) {
        if (id !in EngineGates.available) return
        val pkg = EngineModels.packageFor(id) ?: return
        val dir = EngineModels.dir(context, id) ?: return
        synchronized(jobs) {
            if (jobs[id]?.isActive == true) return
            _states.value = _states.value + (id to EngineDownloadState.Downloading(0f))
            jobs[id] = scope.launch {
                val job = coroutineContext[Job]!!
                val result = runCatching {
                    val onProgress = { p: Float -> _states.value = _states.value + (id to EngineDownloadState.Downloading(p)) }
                    val cancelled = { !job.isActive }
                    val archive = pkg.archive
                    if (archive != null) ModelDownloader.ensureArchive(dir, archive, cancelled, onProgress)
                    else ModelDownloader.ensure(dir, pkg.files, cancelled, onProgress)
                }
                val failure = result.exceptionOrNull()
                _states.value = _states.value + (id to when {
                    failure == null -> EngineDownloadState.Idle
                    failure is DownloadCancelledException || !job.isActive -> EngineDownloadState.Idle
                    else -> {
                        Log.w(TAG, "Engine download failed for $id", failure)
                        EngineDownloadState.Failed(failure.message ?: "Download failed")
                    }
                })
                _installVersion.value++
            }
        }
    }

    fun cancel(id: EngineId) {
        synchronized(jobs) { jobs[id]?.cancel() }
    }

    fun delete(context: Context, id: EngineId) {
        cancel(id)
        EngineModels.dir(context, id)?.deleteRecursively()
        _states.value = _states.value + (id to EngineDownloadState.Idle)
        _installVersion.value++
    }

    /** Builds the engine for one recording: the routed alternative engine with whisper.cpp as the fallback. */
    suspend fun createEngine(
        context: Context,
        whisper: TranscriptionEngine,
        languageCode: String,
        recordingId: String
    ): TranscriptionEngine {
        val log: (String, Throwable?) -> Unit = { msg, t -> if (t != null) Log.w(TAG, msg, t) else Log.i(TAG, msg); Unit }
        val whisperOnly = runCatching { context.dataStore.data.first()[KEY_WHISPER_ONLY] ?: false }.getOrDefault(false)
        val choice = EngineRouter.choose(
            languageCode = languageCode,
            installed = installedEngines(context),
            totalRamBytes = totalRamBytes(context),
            preference = if (whisperOnly) EnginePreference.WHISPER_ONLY else EnginePreference.AUTO
        )
        if (choice == EngineId.WHISPER) return whisper

        // A native crash cannot be caught and WorkManager re-runs a worker whose process died, so leave a marker
        // while the engine is in use and skip it on a retry instead of crashing in a loop (as the speaker pass does).
        val guard = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val guardKey = "engine_started_$recordingId"
        if (guard.getBoolean(guardKey, false)) {
            log("Engine $choice crashed last time for $recordingId; using whisper.cpp", null)
            guard.edit().remove(guardKey).apply()
            return whisper
        }
        val dir = EngineModels.dir(context, choice) ?: return whisper
        log("Using $choice for $languageCode", null)
        return FallbackEngine(
            primaryFactory = {
                guard.edit().putBoolean(guardKey, true).commit()
                SherpaEngine(choice, dir, languageCode)
            },
            fallback = whisper,
            log = log,
            onClose = { guard.edit().remove(guardKey).apply() }
        )
    }

    private fun totalRamBytes(context: Context): Long = try {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        info.totalMem
    } catch (e: Throwable) {
        0L // unknown: the router treats 0 as "do not restrict"
    }

    suspend fun setWhisperOnly(context: Context, whisperOnly: Boolean) {
        context.dataStore.edit { it[KEY_WHISPER_ONLY] = whisperOnly }
    }
}
