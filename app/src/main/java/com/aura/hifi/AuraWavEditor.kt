package com.aura.hifi

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Small, lossless-format WAV editor. Supports uncompressed PCM, 16-bit WAV files.
 * The source is never modified; edited audio is written to a new WAV.
 */
object AuraWavEditor {
    data class WavInfo(val sampleRate: Int, val channels: Int, val bitsPerSample: Int, val durationMs: Long)

    fun inspect(input: InputStream): WavInfo {
        val parsed = parse(input)
        return WavInfo(parsed.sampleRate, parsed.channels, parsed.bitsPerSample,
            (parsed.pcm.size.toLong() * 1000L) / parsed.byteRate)
    }

    fun process(
        input: InputStream,
        output: OutputStream,
        trimStartMs: Long,
        trimEndMs: Long,
        normalize: Boolean,
        noiseGateDb: Int
    ) {
        val wav = parse(input)
        require(wav.audioFormat == 1 && wav.bitsPerSample == 16) {
            "Csak 16 bites, tömörítetlen PCM WAV támogatott."
        }
        val frameSize = wav.channels * 2
        val totalFrames = wav.pcm.size / frameSize
        val startFrame = ((trimStartMs.coerceAtLeast(0L) * wav.sampleRate) / 1000L)
            .coerceIn(0L, totalFrames.toLong()).toInt()
        val requestedEnd = if (trimEndMs <= 0L) totalFrames.toLong()
            else (trimEndMs * wav.sampleRate / 1000L).coerceIn(0L, totalFrames.toLong())
        val endFrame = requestedEnd.toInt().coerceAtLeast(startFrame + 1).coerceAtMost(totalFrames)
        require(endFrame > startFrame) { "A kivágott rész túl rövid." }
        val from = startFrame * frameSize
        val to = endFrame * frameSize
        val selected = wav.pcm.copyOfRange(from, to)
        val threshold = Math.pow(10.0, noiseGateDb.coerceIn(-90, -10) / 20.0).toDouble() * 32767.0
        var peak = 0
        var i = 0
        while (i + 1 < selected.size) {
            val sample = ((selected[i + 1].toInt() shl 8) or (selected[i].toInt() and 0xff)).toShort().toInt()
            peak = max(peak, abs(sample))
            i += 2
        }
        val gain = if (normalize && peak > 0) minOf(32767.0 / peak, 8.0) else 1.0
        i = 0
        while (i + 1 < selected.size) {
            val sample = ((selected[i + 1].toInt() shl 8) or (selected[i].toInt() and 0xff)).toShort().toInt()
            val gated = if (noiseGateDb > -90 && abs(sample).toDouble() < threshold) 0 else sample
            val adjusted = (gated * gain).roundToInt().coerceIn(-32768, 32767)
            selected[i] = adjusted.toByte()
            selected[i + 1] = (adjusted shr 8).toByte()
            i += 2
        }
        writeWav(output, wav.fmt, selected)
    }

    private data class Parsed(
        val fmt: ByteArray, val pcm: ByteArray, val audioFormat: Int,
        val channels: Int, val sampleRate: Int, val byteRate: Int, val bitsPerSample: Int
    )

    private fun parse(input: InputStream): Parsed {
        val bytes = input.readBytes()
        require(bytes.size >= 44 && ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 4) == "WAVE") {
            "A fájl nem érvényes WAV."
        }
        var offset = 12
        var fmt: ByteArray? = null
        var pcm: ByteArray? = null
        var audioFormat = 0
        var channels = 0
        var sampleRate = 0
        var byteRate = 0
        var bits = 0
        while (offset + 8 <= bytes.size) {
            val id = ascii(bytes, offset, 4)
            val size = leInt(bytes, offset + 4).coerceAtLeast(0)
            val dataStart = offset + 8
            val dataEnd = (dataStart.toLong() + size).coerceAtMost(bytes.size.toLong()).toInt()
            if (dataEnd < dataStart) break
            when (id) {
                "fmt " -> {
                    require(size >= 16 && dataEnd - dataStart >= 16) { "Hibás WAV formátumfejléc." }
                    fmt = bytes.copyOfRange(dataStart, dataEnd)
                    audioFormat = leShort(bytes, dataStart)
                    channels = leShort(bytes, dataStart + 2)
                    sampleRate = leInt(bytes, dataStart + 4)
                    byteRate = leInt(bytes, dataStart + 8)
                    bits = leShort(bytes, dataStart + 14)
                }
                "data" -> pcm = bytes.copyOfRange(dataStart, dataEnd)
            }
            offset = dataEnd + (size and 1)
        }
        val format = fmt ?: throw IllegalArgumentException("Hiányzó WAV formátumadat.")
        val audio = pcm ?: throw IllegalArgumentException("Hiányzó WAV hangadat.")
        require(channels in 1..2 && sampleRate > 0 && byteRate > 0) { "Nem támogatott WAV paraméterek." }
        require(audio.size <= 128 * 1024 * 1024) { "A WAV túl nagy a beépített szerkesztőhöz (128 MB limit)." }
        return Parsed(format, audio, audioFormat, channels, sampleRate, byteRate, bits)
    }

    private fun writeWav(output: OutputStream, fmt: ByteArray, pcm: ByteArray) {
        val out = ByteArrayOutputStream(12 + fmt.size + pcm.size + 16)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeLeInt(out, 4 + (8 + fmt.size) + (8 + pcm.size))
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        writeLeInt(out, fmt.size)
        out.write(fmt)
        if (fmt.size and 1 == 1) out.write(0)
        out.write("data".toByteArray(Charsets.US_ASCII))
        writeLeInt(out, pcm.size)
        out.write(pcm)
        if (pcm.size and 1 == 1) out.write(0)
        out.writeTo(output)
        output.flush()
    }

    private fun ascii(bytes: ByteArray, offset: Int, count: Int) =
        String(bytes, offset, count, Charsets.US_ASCII)
    private fun leShort(bytes: ByteArray, offset: Int) =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
    private fun leInt(bytes: ByteArray, offset: Int) =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or (bytes[offset + 3].toInt() shl 24)
    private fun writeLeInt(out: OutputStream, value: Int) {
        out.write(value and 0xff); out.write((value ushr 8) and 0xff)
        out.write((value ushr 16) and 0xff); out.write((value ushr 24) and 0xff)
    }
}
