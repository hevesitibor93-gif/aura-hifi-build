package com.aura.hifi

import co.ntbl.lame.lowlevel.LameDecoder
import co.ntbl.lame.mp3.Lame
import co.ntbl.lame.mp3.MPEGMode
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * MP3 -> PCM -> AURA DSP -> MP3 pipeline.
 * Uses the pure-Java LAME decoder/encoder; source files are never modified.
 * Temporary decoded PCM is streamed from cache to keep memory use bounded.
 */
object AuraMp3Processor {
    private const val MAX_MP3_BYTES = 32 * 1024 * 1024
    private const val MAX_PCM_BYTES = 48L * 1024L * 1024L
    private const val TARGET_PEAK = 29127.0 // approximately -1 dBFS

    fun process(
        input: InputStream,
        output: OutputStream,
        cacheDir: File,
        normalize: Boolean,
        preampDb: Int,
        bassDb: Int,
        trebleDb: Int,
        bitrateKbps: Int = 320
    ) {
        val mp3 = readBounded(input, MAX_MP3_BYTES)
        require(mp3.size >= 128) { "A kiválasztott fájl túl kicsi vagy nem MP3." }
        val pcmFile = File.createTempFile("aura_mp3_pcm_", ".raw", cacheDir)
        try {
            val decoder = LameDecoder(mp3)
            val channels = decoder.channels
            val sampleRate = decoder.sampleRate
            require(channels in 1..2 && sampleRate in 8000..48000) {
                "Nem támogatott MP3 hangformátum."
            }
            try {
                BufferedOutputStream(FileOutputStream(pcmFile)).use { pcmOut ->
                    val buffer = java.nio.ByteBuffer.allocate(decoder.bufferSize)
                    while (true) {
                        buffer.clear()
                        java.util.Arrays.fill(buffer.array(), 0.toByte())
                        if (!decoder.decode(buffer)) break
                        pcmOut.write(buffer.array())
                        require(pcmFile.length() <= MAX_PCM_BYTES) {
                            "A dal túl hosszú ehhez a telefonos MP3-szerkesztőhöz (48 MB PCM limit)."
                        }
                    }
                }
            } finally {
                decoder.close()
            }
            require(pcmFile.length() >= channels * 2L * 1152L) { "Az MP3 nem tartalmaz feldolgozható hangot." }
            encodeProcessedPcm(
                pcmFile, mp3, output, channels, sampleRate, normalize,
                preampDb.coerceIn(-12, 12), bassDb.coerceIn(-12, 12),
                trebleDb.coerceIn(-12, 12), bitrateKbps.coerceIn(128, 320)
            )
        } finally {
            pcmFile.delete()
        }
    }

    private fun encodeProcessedPcm(
        pcmFile: File,
        sourceMp3: ByteArray,
        output: OutputStream,
        channels: Int,
        sampleRate: Int,
        normalize: Boolean,
        preampDb: Int,
        bassDb: Int,
        trebleDb: Int,
        bitrateKbps: Int
    ) {
        val frameBytes = channels * 2
        val frameCount = pcmFile.length() / frameBytes
        var peak = 0
        BufferedInputStream(FileInputStream(pcmFile)).use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                var i = 0
                while (i + 1 < count) {
                    val sample = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xff)).toShort().toInt()
                    peak = max(peak, abs(sample))
                    i += 2
                }
            }
        }
        val preamp = Math.pow(10.0, preampDb / 20.0)
        var gain = (if (normalize && peak > 0) TARGET_PEAK / peak else 1.0) * preamp
        if (peak > 0 && peak * gain > 32700.0) gain = 32700.0 / peak

        val lame = Lame()
        val flags = lame.flags
        flags.setInNumChannels(channels)
        flags.setInSampleRate(sampleRate)
        flags.setBitRate(bitrateKbps)
        flags.setMode(if (channels == 1) MPEGMode.MONO else MPEGMode.STEREO)
        flags.setQuality(2)
        flags.setWriteId3tagAutomatic(false)
        val initResult = lame.initParams()
        require(initResult == 0) { "Az MP3-kódoló nem tudta inicializálni a hangformátumot ($initResult)." }

        try {
            val id3v2End = id3v2TagEnd(sourceMp3)
            if (id3v2End > 0) output.write(sourceMp3, 0, id3v2End)
            val lowAlpha = 1.0 - exp(-2.0 * Math.PI * 180.0 / sampleRate)
            val highAlpha = 1.0 - exp(-2.0 * Math.PI * 4000.0 / sampleRate)
            val lowState = DoubleArray(channels)
            val highState = DoubleArray(channels)
            val chunkFrames = 4096
            val pcmBytes = ByteArray(chunkFrames * frameBytes)
            val left = FloatArray(chunkFrames)
            val right = FloatArray(chunkFrames)
            val encoded = ByteArray(Lame.LAME_MAXMP3BUFFER)
            val bassGain = Math.pow(10.0, bassDb / 20.0)
            val trebleGain = Math.pow(10.0, trebleDb / 20.0)
            BufferedInputStream(FileInputStream(pcmFile)).use { input ->
                var framesRemaining = frameCount
                while (framesRemaining > 0) {
                    val wantedFrames = minOf(chunkFrames.toLong(), framesRemaining).toInt()
                    val wantedBytes = wantedFrames * frameBytes
                    var read = 0
                    while (read < wantedBytes) {
                        val n = input.read(pcmBytes, read, wantedBytes - read)
                        if (n < 0) break
                        read += n
                    }
                    val actualFrames = read / frameBytes
                    if (actualFrames <= 0) break
                    for (frame in 0 until actualFrames) {
                        for (channel in 0 until channels) {
                            val index = frame * frameBytes + channel * 2
                            val sample = ((pcmBytes[index + 1].toInt() shl 8) or (pcmBytes[index].toInt() and 0xff)).toShort().toInt().toDouble()
                            lowState[channel] += lowAlpha * (sample - lowState[channel])
                            highState[channel] += highAlpha * (sample - highState[channel])
                            val shaped = (sample + lowState[channel] * (bassGain - 1.0) +
                                (sample - highState[channel]) * (trebleGain - 1.0)) * gain
                            val safe = shaped.roundToInt().coerceIn(-32768, 32767).toFloat()
                            if (channel == 0) left[frame] = safe else right[frame] = safe
                        }
                        if (channels == 1) right[frame] = left[frame]
                    }
                    val written = lame.encodeBuffer(left, right, actualFrames, encoded)
                    if (written > 0) output.write(encoded, 0, written)
                    framesRemaining -= actualFrames
                }
            }
            val flushed = lame.encodeFlush(encoded)
            if (flushed > 0) output.write(encoded, 0, flushed)
            // Preserve an existing ID3v1 tag, if present.
            if (sourceMp3.size >= 128 && String(sourceMp3, sourceMp3.size - 128, 3, Charsets.US_ASCII) == "TAG") {
                output.write(sourceMp3, sourceMp3.size - 128, 128)
            }
            output.flush()
        } finally {
            lame.close()
        }
    }

    private fun id3v2TagEnd(data: ByteArray): Int {
        if (data.size < 10 || String(data, 0, 3, Charsets.US_ASCII) != "ID3") return 0
        val size = ((data[6].toInt() and 0x7f) shl 21) or
            ((data[7].toInt() and 0x7f) shl 14) or
            ((data[8].toInt() and 0x7f) shl 7) or
            (data[9].toInt() and 0x7f)
        val footer = if ((data[5].toInt() and 0x10) != 0) 10 else 0
        return (10L + size + footer).coerceAtMost(data.size.toLong()).toInt()
    }

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "Az MP3 túl nagy (max. 32 MB)." }
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }
}
