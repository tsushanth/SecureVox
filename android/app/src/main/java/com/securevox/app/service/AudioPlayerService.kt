package com.securevox.app.service

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Service for playing back audio recordings.
 */
class AudioPlayerService(private val context: Context) {

    companion object {
        private const val TAG = "AudioPlayerService"
    }

    private var mediaPlayer: MediaPlayer? = null
    private var positionUpdateJob: Job? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * Load an audio file for playback.
     * @param filePath Path to the audio file
     * @return true if loaded successfully
     */
    fun load(filePath: String): Boolean {
        release()

        val file = File(filePath)
        if (!file.exists()) {
            Log.e(TAG, "File not found: $filePath")
            return false
        }

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(filePath)
                prepare()

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
            }

            _duration.value = mediaPlayer?.duration?.toLong() ?: 0L
            _currentPosition.value = 0L
            Log.i(TAG, "Loaded: $filePath, duration: ${_duration.value}ms")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Error loading file", e)
            return false
        }
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
        _isPlaying.value = false
        _currentPosition.value = 0L
        _duration.value = 0L
    }
}
