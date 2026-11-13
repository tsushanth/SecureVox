package com.securevox.app.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.delay
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.securevox.app.util.FailureReporter
import com.securevox.app.util.funnel.Step
import com.securevox.app.util.funnel.StartFailClass
import com.securevox.app.util.funnel.funnel

/**
 * Service for recording audio using AudioRecord.
 * Records at 16kHz mono for optimal Whisper compatibility.
 */
class AudioRecorderService(private val context: Context) {

    companion object {
        private const val TAG = "AudioRecorderService"
        const val SAMPLE_RATE = 16000

        /** Length of a 16 kHz mono 16-bit WAV file with a 44-byte header, in milliseconds. */
        fun durationMsForWavBytes(fileBytes: Long): Long =
            (fileBytes - 44).coerceAtLeast(0) * 1000 / (SAMPLE_RATE * 2)

        /** The 44-byte header of a 16 kHz mono 16-bit PCM WAV file holding [dataSize] bytes of audio. */
        fun wavHeader(dataSize: Long): ByteArray =
            ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt((dataSize + 36).toInt())
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)                  // Subchunk1Size
                putShort(1)                 // AudioFormat (PCM)
                putShort(1)                 // NumChannels (mono)
                putInt(SAMPLE_RATE)         // SampleRate
                putInt(SAMPLE_RATE * 2)     // ByteRate
                putShort(2)                 // BlockAlign
                putShort(16)                // BitsPerSample
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(dataSize.toInt())
            }.array()

        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var outputFile: File? = null

    // Raw 16 kHz PCM as it is captured, for live captions. Dropping the oldest chunk when a
    // collector falls behind keeps the recording loop from ever waiting on a slow consumer.
    private val _liveAudio = MutableSharedFlow<ShortArray>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val liveAudio: SharedFlow<ShortArray> = _liveAudio.asSharedFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _recordingDuration = MutableStateFlow(0L)
    val recordingDuration: StateFlow<Long> = _recordingDuration.asStateFlow()

    // Accumulated duration before current pause/resume segment
    private var accumulatedDurationMs = 0L
    private var segmentStartMs = 0L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Start recording audio to a file.
     * @param outputPath Path where the WAV file will be saved
     * @return true if recording started successfully
     */
    fun startRecording(outputPath: String): Boolean {
        if (_isRecording.value) {
            Log.w(TAG, "Already recording")
            return false
        }

        if (!hasPermission()) {
            Log.e(TAG, "No microphone permission")
            funnel().step(Step.RECORDING_START_FAILED, StartFailClass.NO_PERMISSION)
            return false
        }

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBuffer == AudioRecord.ERROR || minBuffer == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid buffer size: $minBuffer")
            FailureReporter.failure("recording.start", reason = "invalid_buffer_size", context = mapOf("code" to minBuffer.toString()))
            funnel().step(Step.RECORDING_START_FAILED, StartFailClass.BAD_BUFFER)
            return false
        }
        // Use at least 2× min buffer; round up to next power-of-two for compatibility
        val bufferSize = maxOf(minBuffer * 2, 8192)

        try {
            // Try preferred audio sources in order; some phones block MIC but allow VOICE_RECOGNITION
            val audioSources = listOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.DEFAULT
            )
            for (source in audioSources) {
                val candidate = AudioRecord(source, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    audioRecord = candidate
                    Log.i(TAG, "AudioRecord initialized with source=$source")
                    break
                } else {
                    candidate.release()
                    Log.w(TAG, "AudioRecord source=$source failed, trying next")
                }
            }

            if (audioRecord == null || audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize with all sources")
                audioRecord?.release()
                audioRecord = null
                // Mic busy (another app or a call holds it), blocked by the OS, or no input device.
                FailureReporter.failure("recording.start", reason = "mic_unavailable")
                funnel().step(Step.RECORDING_START_FAILED, StartFailClass.MIC_UNAVAILABLE)
                return false
            }

            outputFile = File(outputPath)
            outputFile?.parentFile?.mkdirs()

            audioRecord?.startRecording()
            _isRecording.value = true
            _isPaused.value = false
            _recordingDuration.value = 0L
            accumulatedDurationMs = 0L
            segmentStartMs = System.currentTimeMillis()

            recordingJob = scope.launch {
                try {
                    recordAudioToFile(bufferSize)
                } catch (t: Throwable) {
                    // Disk full or a write error mid-recording. Report, then rethrow so behaviour is unchanged.
                    FailureReporter.failure("recording.write", t)
                    throw t
                }
            }

            Log.i(TAG, "Recording started: $outputPath")
            return true

        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception starting recording", e)
            FailureReporter.failure("recording.start", e, reason = "security_exception")
            funnel().step(Step.RECORDING_START_FAILED, StartFailClass.NO_PERMISSION)
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording", e)
            FailureReporter.failure("recording.start", e)
            funnel().step(Step.RECORDING_START_FAILED, StartFailClass.EXCEPTION)
            audioRecord?.release()
            audioRecord = null
            return false
        }
    }

    /**
     * Pause an active recording. Audio capture stops but the file stays open.
     */
    fun pauseRecording() {
        if (!_isRecording.value || _isPaused.value) return
        accumulatedDurationMs += System.currentTimeMillis() - segmentStartMs
        _isPaused.value = true
        _audioLevel.value = 0f
        audioRecord?.stop()
    }

    /**
     * Resume a paused recording.
     */
    fun resumeRecording() {
        if (!_isRecording.value || !_isPaused.value) return
        try {
            audioRecord?.startRecording()
            segmentStartMs = System.currentTimeMillis()
            _isPaused.value = false
        } catch (e: Exception) {
            Log.e(TAG, "Error resuming recording", e)
            FailureReporter.failure("recording.resume", e)
        }
    }

    /**
     * Stop recording and finalize the WAV file.
     * @return Path to the recorded file, or null if failed
     */
    fun stopRecording(): String? {
        if (!_isRecording.value) return null

        // Capture final duration before stopping
        if (!_isPaused.value) {
            accumulatedDurationMs += System.currentTimeMillis() - segmentStartMs
        }
        _recordingDuration.value = accumulatedDurationMs
        val wallClockMs = accumulatedDurationMs

        _isRecording.value = false
        _isPaused.value = false

        // Wait for recording job to finish writing
        runBlocking {
            recordingJob?.join()
        }
        recordingJob = null

        try { audioRecord?.stop() } catch (_: Exception) {}
        audioRecord?.release()
        audioRecord = null

        val file = outputFile
        outputFile = null

        // The recording loop is finished, so this is the last write to the duration. Derive it
        // from the audio that actually reached the file: until now it was the wall clock, and
        // the loop's final pass added the last segment a second time, storing every recording
        // at about twice its real length.
        file?.let { _recordingDuration.value = durationMsForWavBytes(it.length()) }
        // A header-only file means the mic delivered nothing. It is deleted and not handed back, so no empty row is
        // saved or transcribed; it is reported only when the user recorded long enough for that to be a real
        // microphone problem rather than an accidental tap.
        val kept = EmptyRecording.settle(file, wallClockMs) {
            FailureReporter.failure("recording.stop", reason = "empty_recording")
        }

        Log.i(TAG, "Recording stopped: $kept, size: ${file?.length()} bytes")
        return kept
    }

    private suspend fun recordAudioToFile(bufferSize: Int) {
        val buffer = ShortArray(bufferSize)

        FileOutputStream(outputFile).use { fos ->
            // Write placeholder WAV header (will be updated at the end)
            val header = ByteArray(44)
            fos.write(header)

            var totalBytesWritten = 0L
            var readErrorReported = false

            while (_isRecording.value && coroutineContext.isActive) {
                if (_isPaused.value) {
                    // While paused: don't read/write audio, just yield
                    _recordingDuration.value = accumulatedDurationMs
                    delay(50)
                    continue
                }

                val readResult = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (readResult < 0 && !readErrorReported && _isRecording.value && !_isPaused.value) {
                    // ERROR_DEAD_OBJECT / ERROR_INVALID_OPERATION: the input was taken away (call, another app).
                    readErrorReported = true
                    FailureReporter.failure("recording.read", reason = "read_error", context = mapOf("code" to readResult.toString()))
                }

                if (readResult > 0) {
                    updateAudioLevel(buffer, readResult)
                    if (_liveAudio.subscriptionCount.value > 0) {
                        _liveAudio.tryEmit(buffer.copyOf(readResult))
                    }

                    val byteBuffer = ByteBuffer.allocate(readResult * 2)
                    byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until readResult) {
                        byteBuffer.putShort(buffer[i])
                    }
                    fos.write(byteBuffer.array())
                    totalBytesWritten += readResult * 2

                    // Update live duration
                    _recordingDuration.value = accumulatedDurationMs + (System.currentTimeMillis() - segmentStartMs)
                }

                yield()
            }

            // Update WAV header with actual size
            fos.close()
            updateWavHeader(outputFile!!, totalBytesWritten)
        }
    }

    private fun updateAudioLevel(buffer: ShortArray, length: Int) {
        var sum = 0.0
        for (i in 0 until length) {
            sum += buffer[i] * buffer[i]
        }
        val rms = kotlin.math.sqrt(sum / length)
        val db = 20 * kotlin.math.log10(rms / Short.MAX_VALUE)
        // Normalize to 0-1 range (assuming -60dB to 0dB range)
        val normalized = ((db + 60) / 60).coerceIn(0.0, 1.0)
        _audioLevel.value = normalized.toFloat()
    }

    private fun updateWavHeader(file: File, dataSize: Long) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(wavHeader(dataSize))
        }
    }

    private fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun release() {
        stopRecording()
        scope.cancel()
    }
}
