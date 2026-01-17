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
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Service for recording audio using AudioRecord.
 * Records at 16kHz mono for optimal Whisper compatibility.
 */
class AudioRecorderService(private val context: Context) {

    companion object {
        private const val TAG = "AudioRecorderService"
        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var outputFile: File? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _recordingDuration = MutableStateFlow(0L)
    val recordingDuration: StateFlow<Long> = _recordingDuration.asStateFlow()

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
            return false
        }

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid buffer size: $bufferSize")
            return false
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                audioRecord?.release()
                audioRecord = null
                return false
            }

            outputFile = File(outputPath)
            outputFile?.parentFile?.mkdirs()

            audioRecord?.startRecording()
            _isRecording.value = true
            _recordingDuration.value = 0L

            recordingJob = scope.launch {
                recordAudioToFile(bufferSize)
            }

            Log.i(TAG, "Recording started: $outputPath")
            return true

        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception starting recording", e)
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording", e)
            audioRecord?.release()
            audioRecord = null
            return false
        }
    }

    /**
     * Stop recording and finalize the WAV file.
     * @return Path to the recorded file, or null if failed
     */
    fun stopRecording(): String? {
        if (!_isRecording.value) return null

        _isRecording.value = false

        // Wait for recording job to finish writing
        runBlocking {
            recordingJob?.join()
        }
        recordingJob = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        val file = outputFile
        outputFile = null

        Log.i(TAG, "Recording stopped: ${file?.absolutePath}, size: ${file?.length()} bytes")
        return file?.absolutePath
    }

    private suspend fun recordAudioToFile(bufferSize: Int) {
        val buffer = ShortArray(bufferSize)
        val startTime = System.currentTimeMillis()

        FileOutputStream(outputFile).use { fos ->
            // Write placeholder WAV header (will be updated at the end)
            val header = ByteArray(44)
            fos.write(header)

            var totalBytesWritten = 0L

            while (_isRecording.value && coroutineContext.isActive) {
                val readResult = audioRecord?.read(buffer, 0, buffer.size) ?: -1

                if (readResult > 0) {
                    // Calculate audio level for visualization
                    updateAudioLevel(buffer, readResult)

                    // Convert shorts to bytes and write
                    val byteBuffer = ByteBuffer.allocate(readResult * 2)
                    byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until readResult) {
                        byteBuffer.putShort(buffer[i])
                    }
                    fos.write(byteBuffer.array())
                    totalBytesWritten += readResult * 2

                    // Update duration
                    _recordingDuration.value = System.currentTimeMillis() - startTime
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
            val totalSize = dataSize + 36

            // Write WAV header
            raf.seek(0)
            raf.writeBytes("RIFF")
            raf.writeIntLE(totalSize.toInt())
            raf.writeBytes("WAVE")
            raf.writeBytes("fmt ")
            raf.writeIntLE(16) // Subchunk1Size
            raf.writeShortLE(1) // AudioFormat (PCM)
            raf.writeShortLE(1) // NumChannels (mono)
            raf.writeIntLE(SAMPLE_RATE) // SampleRate
            raf.writeIntLE(SAMPLE_RATE * 2) // ByteRate
            raf.writeShortLE(2) // BlockAlign
            raf.writeShortLE(16) // BitsPerSample
            raf.writeBytes("data")
            raf.writeIntLE(dataSize.toInt())
        }
    }

    private fun RandomAccessFile.writeIntLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun RandomAccessFile.writeShortLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
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
