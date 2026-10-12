package com.securevox.app.service

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** WAV header parsing and chunked 16 kHz mono float loading, shared by transcription and the speaker pass. */
object WavReader {
    private const val TAG = "WavReader"

    /** Metadata from WAV header needed for chunked reading. */
    data class WavInfo(
        val filePath: String,
        val channels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
        val audioFormat: Int,
        val dataOffsetBytes: Long,  // byte offset in file where PCM data begins
        val dataSizeBytes: Long,
        val totalMonoSamples: Long   // after channel-mixing and resampling to 16kHz
    )

    /** Parse WAV header and locate the data chunk without reading PCM data. */
    fun readWavInfo(filePath: String): WavInfo? {
        val file = File(filePath)
        if (!file.exists()) return null

        return try {
            FileInputStream(file).use { fis ->
                val riff = ByteArray(12)
                if (fis.read(riff) < 12) return null
                if (String(riff, 0, 4) != "RIFF" || String(riff, 8, 4) != "WAVE") return null

                var audioFormat = 1
                var channels = 1
                var sampleRate = 16000
                var bitsPerSample = 16
                var dataOffsetBytes = -1L
                var dataSizeBytes = -1L
                var bytesRead = 12L

                val idBuf = ByteArray(4)
                val szBuf = ByteArray(4)

                while (true) {
                    if (fis.read(idBuf) < 4) break
                    if (fis.read(szBuf) < 4) break
                    bytesRead += 8
                    val chunkSize = ByteBuffer.wrap(szBuf).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                    val chunkId = String(idBuf, 0, 4)

                    when (chunkId) {
                        "fmt " -> {
                            val fmtData = ByteArray(chunkSize.toInt().coerceAtMost(40))
                            val n = fis.read(fmtData)
                            val buf = ByteBuffer.wrap(fmtData, 0, n).order(ByteOrder.LITTLE_ENDIAN)
                            audioFormat = buf.getShort(0).toInt() and 0xFFFF
                            channels = buf.getShort(2).toInt() and 0xFFFF
                            sampleRate = buf.getInt(4)
                            bitsPerSample = buf.getShort(14).toInt() and 0xFFFF
                            val skip = chunkSize - n
                            if (skip > 0) fis.skip(skip)
                            bytesRead += chunkSize
                        }
                        "data" -> {
                            dataOffsetBytes = bytesRead + 8 - 8 // position right after chunk header
                            // Recalculate: bytesRead already includes the 8-byte header we just read
                            dataOffsetBytes = bytesRead
                            dataSizeBytes = chunkSize
                            break
                        }
                        else -> {
                            val skip = chunkSize + (chunkSize and 1)
                            fis.skip(skip)
                            bytesRead += skip
                        }
                    }
                }

                if (dataOffsetBytes < 0 || dataSizeBytes < 0) {
                    Log.e(TAG, "No data chunk in WAV")
                    return null
                }

                if (audioFormat != 1 && audioFormat != 3) {
                    Log.e(TAG, "Unsupported WAV format $audioFormat")
                    return null
                }

                val bytesPerSample = bitsPerSample / 8
                val rawSamples = dataSizeBytes / bytesPerSample
                val monoSamples = rawSamples / channels
                // Adjust for resampling to 16kHz
                val targetSamples = if (sampleRate != 16000) {
                    (monoSamples * 16000L / sampleRate).toLong()
                } else {
                    monoSamples
                }

                Log.i(TAG, "WAV info: ${sampleRate}Hz ${channels}ch ${bitsPerSample}bit, dataOffset=$dataOffsetBytes, totalMonoSamples=$targetSamples")

                WavInfo(filePath, channels, sampleRate, bitsPerSample, audioFormat, dataOffsetBytes, dataSizeBytes, targetSamples)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading WAV info", e)
            null
        }
    }

    /**
     * Load a chunk of audio [startSample, endSample) (in 16kHz mono sample indices)
     * directly from the WAV file without loading the entire file.
     */
    fun loadAudioChunk(info: WavInfo, startSample: Int, endSample: Int): FloatArray? {
        if (startSample >= endSample) return FloatArray(0)

        val file = File(info.filePath)
        val bytesPerRawSample = info.bitsPerSample / 8
        val bytesPerFrame = bytesPerRawSample * info.channels // one frame = all channels

        return try {
            // Convert 16kHz target sample indices back to source sample indices
            val ratio = if (info.sampleRate != 16000) info.sampleRate.toDouble() / 16000.0 else 1.0
            val srcStartSample = (startSample * ratio).toLong()
            val srcEndSample = ((endSample * ratio) + 1).toLong()
                .coerceAtMost(info.dataSizeBytes / bytesPerFrame)

            val frameCount = (srcEndSample - srcStartSample).toInt()
            val byteOffset = info.dataOffsetBytes + srcStartSample * bytesPerFrame
            val byteCount = (frameCount * bytesPerFrame).toInt()

            val rawBytes = ByteArray(byteCount)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(byteOffset)
                var pos = 0
                while (pos < byteCount) {
                    val n = raf.read(rawBytes, pos, byteCount - pos)
                    if (n < 0) break
                    pos += n
                }
            }

            val buf = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN)

            // Decode samples
            val rawSamples = ShortArray(frameCount * info.channels)
            when {
                info.audioFormat == 1 && info.bitsPerSample == 16 -> {
                    buf.asShortBuffer().get(rawSamples)
                }
                info.audioFormat == 3 && info.bitsPerSample == 32 -> {
                    val floatBuf = buf.asFloatBuffer()
                    for (i in rawSamples.indices) {
                        rawSamples[i] = (floatBuf.get() * Short.MAX_VALUE)
                            .toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    }
                }
            }

            // Mix to mono
            val mono = if (info.channels > 1) {
                ShortArray(frameCount) { i ->
                    var sum = 0L
                    for (ch in 0 until info.channels) sum += rawSamples[i * info.channels + ch]
                    (sum / info.channels).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
            } else {
                rawSamples
            }

            // Resample to 16kHz if needed
            val final16k = if (info.sampleRate != 16000) {
                val outputLen = (mono.size / ratio).toInt()
                ShortArray(outputLen) { i ->
                    val srcPos = i * ratio
                    val srcIdx = srcPos.toInt().coerceAtMost(mono.size - 1)
                    val frac = srcPos - srcIdx
                    val s1 = mono[srcIdx]
                    val s2 = if (srcIdx + 1 < mono.size) mono[srcIdx + 1] else s1
                    (s1 + frac * (s2 - s1)).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
            } else {
                mono
            }

            FloatArray(final16k.size) { i -> final16k[i].toFloat() / Short.MAX_VALUE }
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM loading audio chunk", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error loading audio chunk", e)
            null
        }
    }
}
