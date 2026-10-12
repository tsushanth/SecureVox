package com.securevox.app.util

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs

/**
 * Reduces a 16-bit PCM WAV file (what SecureVox records and what imports are converted to)
 * to [bars] peak levels in 0..1, for drawing a waveform. Streams the file, so a
 * multi-hour recording costs a few buffers of memory.
 */
object WaveformExtractor {

    fun extract(file: File, bars: Int = 160): FloatArray {
        if (!file.exists() || bars <= 0) return FloatArray(0)
        RandomAccessFile(file, "r").use { raf ->
            val dataStart = findDataStart(raf) ?: return FloatArray(0)
            val dataBytes = (raf.length() - dataStart) / 2 * 2
            val totalSamples = dataBytes / 2
            if (totalSamples <= 0) return FloatArray(0)

            val peaks = FloatArray(bars)
            val samplesPerBar = (totalSamples / bars).coerceAtLeast(1)
            val buffer = ByteArray(64 * 1024)
            raf.seek(dataStart)

            var sampleIndex = 0L
            var remaining = dataBytes
            while (remaining > 0) {
                val read = raf.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read <= 0) break
                var i = 0
                while (i + 1 < read) {
                    val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
                    val bar = (sampleIndex / samplesPerBar).toInt().coerceAtMost(bars - 1)
                    val level = abs(sample.toShort().toInt()) / 32768f
                    if (level > peaks[bar]) peaks[bar] = level
                    sampleIndex++
                    i += 2
                }
                remaining -= read
            }

            // Speech peaks are far below full scale; stretch so quiet recordings still show shape.
            val max = peaks.maxOrNull() ?: 0f
            if (max > 0f) for (i in peaks.indices) peaks[i] = (peaks[i] / max).coerceIn(0f, 1f)
            return peaks
        }
    }

    /** Offset of the PCM bytes: just past the "data" chunk header, or 44 for a plain header. */
    private fun findDataStart(raf: RandomAccessFile): Long? {
        if (raf.length() < 12) return null
        val header = ByteArray(12)
        raf.seek(0)
        raf.readFully(header)
        if (String(header, 0, 4) != "RIFF" || String(header, 8, 4) != "WAVE") return null

        var pos = 12L
        val chunk = ByteArray(8)
        while (pos + 8 <= raf.length()) {
            raf.seek(pos)
            raf.readFully(chunk)
            val id = String(chunk, 0, 4)
            val size = (chunk[4].toLong() and 0xFF) or
                ((chunk[5].toLong() and 0xFF) shl 8) or
                ((chunk[6].toLong() and 0xFF) shl 16) or
                ((chunk[7].toLong() and 0xFF) shl 24)
            if (id == "data") return pos + 8
            pos += 8 + size + (size and 1)
        }
        return null
    }
}
