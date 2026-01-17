package com.securevox.app.service

import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Playback speed options matching iOS
 */
enum class PlaybackSpeed(val speed: Float, val displayName: String) {
    SPEED_0_5X(0.5f, "0.5x"),
    SPEED_0_75X(0.75f, "0.75x"),
    SPEED_1X(1.0f, "1x"),
    SPEED_1_25X(1.25f, "1.25x"),
    SPEED_1_5X(1.5f, "1.5x"),
    SPEED_2X(2.0f, "2x");

    companion object {
        val DEFAULT = SPEED_1X
    }
}

/**
 * Service for playing back audio recordings.
 * This is a singleton to preserve playback state across navigation.
 */
class AudioPlayerService private constructor(private val context: Context) {

    companion object {
        private const val TAG = "AudioPlayerService"

        @Volatile
        private var instance: AudioPlayerService? = null

        fun getInstance(context: Context): AudioPlayerService {
            return instance ?: synchronized(this) {
                instance ?: AudioPlayerService(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    // Track currently loaded file path
    private var currentFilePath: String? = null

    private var mediaPlayer: MediaPlayer? = null
    private var positionUpdateJob: Job? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(PlaybackSpeed.DEFAULT)
    val playbackSpeed: StateFlow<PlaybackSpeed> = _playbackSpeed.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    /**
     * Load an audio file for playback.
     * If the same file is already loaded, this does nothing.
     * @param filePath Path to the audio file
     * @return true if loaded successfully (or already loaded)
     */
    fun load(filePath: String): Boolean {
        // If this file is already loaded, don't reload
        if (filePath == currentFilePath && mediaPlayer != null && _isLoaded.value) {
            Log.d(TAG, "File already loaded: $filePath")
            return true
        }

        // Different file or not loaded - release and load fresh
        release()
        _isLoaded.value = false

        if (filePath.isEmpty()) {
            Log.e(TAG, "Empty file path")
            return false
        }

        val file = File(filePath)
        if (!file.exists()) {
            Log.e(TAG, "File not found: $filePath")
            return false
        }

        if (file.length() < 44) {
            Log.e(TAG, "File too small to be valid WAV: ${file.length()} bytes")
            return false
        }

        Log.i(TAG, "Loading audio file: $filePath (${file.length()} bytes)")

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(filePath)

                setOnCompletionListener {
                    _isPlaying.value = false
                    _currentPosition.value = 0L
                    seekTo(0)
                    stopPositionUpdates()
                }

                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                    _isPlaying.value = false
                    true
                }

                prepare()
            }

            currentFilePath = filePath
            _duration.value = mediaPlayer?.duration?.toLong() ?: 0L
            _currentPosition.value = 0L
            _isLoaded.value = true
            Log.i(TAG, "Loaded successfully: $filePath, duration: ${_duration.value}ms")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Error loading file: ${e.message}", e)
            mediaPlayer?.release()
            mediaPlayer = null
            currentFilePath = null
            _isLoaded.value = false
            return false
        }
    }

    /**
     * Check if a specific file is currently loaded
     */
    fun isFileLoaded(filePath: String): Boolean {
        return filePath == currentFilePath && _isLoaded.value
    }

    /**
     * Start or resume playback.
     */
    fun play() {
        mediaPlayer?.let { player ->
            if (!player.isPlaying) {
                player.start()
                _isPlaying.value = true
                startPositionUpdates()
                Log.i(TAG, "Playback started")
            }
        }
    }

    /**
     * Pause playback.
     */
    fun pause() {
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
                _isPlaying.value = false
                stopPositionUpdates()
                Log.i(TAG, "Playback paused")
            }
        }
    }

    /**
     * Toggle play/pause.
     */
    fun togglePlayPause() {
        if (_isPlaying.value) {
            pause()
        } else {
            play()
        }
    }

    /**
     * Seek to a specific position.
     * @param positionMs Position in milliseconds
     */
    fun seekTo(positionMs: Long) {
        mediaPlayer?.let { player ->
            val safePosition = positionMs.coerceIn(0L, _duration.value)
            player.seekTo(safePosition.toInt())
            _currentPosition.value = safePosition
            Log.d(TAG, "Seeked to: ${safePosition}ms")
        }
    }

    /**
     * Skip forward by specified milliseconds.
     */
    fun skipForward(ms: Long = 10000) {
        val newPosition = (_currentPosition.value + ms).coerceAtMost(_duration.value)
        seekTo(newPosition)
    }

    /**
     * Skip backward by specified milliseconds.
     */
    fun skipBackward(ms: Long = 10000) {
        val newPosition = (_currentPosition.value - ms).coerceAtLeast(0)
        seekTo(newPosition)
    }

    /**
     * Set playback speed.
     * @param speed PlaybackSpeed enum value
     */
    fun setPlaybackSpeed(speed: PlaybackSpeed) {
        _playbackSpeed.value = speed
        mediaPlayer?.let { player ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val params = player.playbackParams
                    params.speed = speed.speed
                    player.playbackParams = params
                    Log.i(TAG, "Playback speed set to: ${speed.displayName}")
                } else {
                    Log.w(TAG, "Playback speed change requires Android M or higher")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting playback speed: ${e.message}")
            }
        }
    }

    /**
     * Cycle to the next playback speed.
     */
    fun cyclePlaybackSpeed() {
        val speeds = PlaybackSpeed.entries
        val currentIndex = speeds.indexOf(_playbackSpeed.value)
        val nextIndex = (currentIndex + 1) % speeds.size
        setPlaybackSpeed(speeds[nextIndex])
    }

    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = scope.launch {
            while (isActive && _isPlaying.value) {
                mediaPlayer?.let { player ->
                    _currentPosition.value = player.currentPosition.toLong()
                }
                delay(100)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }

    /**
     * Release all resources.
     */
    fun release() {
        stopPositionUpdates()
        mediaPlayer?.release()
        mediaPlayer = null
        currentFilePath = null
        _isPlaying.value = false
        _currentPosition.value = 0L
        _duration.value = 0L
        _isLoaded.value = false
        _playbackSpeed.value = PlaybackSpeed.DEFAULT
    }

    /**
     * Stop playback but keep file loaded (for use when navigating away)
     */
    fun stop() {
        pause()
        seekTo(0)
    }
}
