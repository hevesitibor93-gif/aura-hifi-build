package com.aura.hifi

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.exp
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

    /** Returns a compact peak envelope suitable for an on-screen waveform preview. */
    fun waveform(input: InputStream, bucketCount: Int = 220): FloatArray {
        val wav = parse(input)
        require(wav.audioFormat == 1 && wav.bitsPerSample == 16) {
            "Csak 16 bites, tömörítetlen PCM WAV támogatott."
        }
        val frameSize = wav.channels * 2
        val totalFrames = wav.pcm.size / frameSize
        require(totalFrames > 0) { "A WAV nem tartalmaz hangadatot." }
        val count = bucketCount.coerceIn(32, 600)
        val peaks = FloatArray(count)
        for (bucket in 0 until count) {
            val first = (bucket.toLong() * totalFrames / count).toInt()
            val last = (((bucket + 1L) * totalFrames / count).toInt()).coerceAtLeast(first + 1).coerceAtMost(totalFrames)
            var peak = 0
            for (frame in first until last) {
                val base = frame * frameSize
                for (channel in 0 until wav.channels) {
                    val index = base + channel * 2
                    val sample = ((wav.pcm[index + 1].toInt() shl 8) or (wav.pcm[index].toInt() and 0xff)).toShort().toInt()
                    peak = max(peak, abs(sample))
                }
            }
            peaks[bucket] = peak / 32768f
        }
        return peaks
    }

    fun process(
        input: InputStream,
        output: OutputStream,
        trimStartMs: Long,
        trimEndMs: Long,
        normalize: Boolean,
        noiseGateDb: Int,
        fadeInMs: Long = 0L,
        fadeOutMs: Long = 0L,
        gainDb: Int = 0,
        echoDelayMs: Long = 250L,
        echoMixPercent: Int = 0,
        reverbPercent: Int = 0,
        bassDb: Int = 0,
        trebleDb: Int = 0,
        stereoWidthPercent: Int = 100,
        noiseReductionPercent: Int = 0
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
        // Gentle low/high shelving and mid-side stereo width, applied before time effects.
        val bassGain = Math.pow(10.0, bassDb.coerceIn(-12, 12) / 20.0)
        val trebleGain = Math.pow(10.0, trebleDb.coerceIn(-12, 12) / 20.0)
        val width = stereoWidthPercent.coerceIn(0, 200) / 100.0
        val lowAlpha = 1.0 - exp(-2.0 * Math.PI * 180.0 / wav.sampleRate)
        val highAlpha = 1.0 - exp(-2.0 * Math.PI * 4000.0 / wav.sampleRate)
        val lowState = DoubleArray(wav.channels)
        val highState = DoubleArray(wav.channels)
        var frameOffset = 0
        while (frameOffset + frameSize <= selected.size) {
            val samples = IntArray(wav.channels)
            for (channel in 0 until wav.channels) {
                val index = frameOffset + channel * 2
                samples[channel] = ((selected[index + 1].toInt() shl 8) or (selected[index].toInt() and 0xff)).toShort().toInt()
            }
            if (wav.channels == 2 && width != 1.0) {
                val mid = (samples[0] + samples[1]) * 0.5
                val side = (samples[0] - samples[1]) * 0.5 * width
                samples[0] = (mid + side).roundToInt().coerceIn(-32768, 32767)
                samples[1] = (mid - side).roundToInt().coerceIn(-32768, 32767)
            }
            for (channel in 0 until wav.channels) {
                val sample = samples[channel].toDouble()
                lowState[channel] += lowAlpha * (sample - lowState[channel])
                highState[channel] += highAlpha * (sample - highState[channel])
                val adjusted = (sample + lowState[channel] * (bassGain - 1.0) +
                    (sample - highState[channel]) * (trebleGain - 1.0)).roundToInt().coerceIn(-32768, 32767)
                val index = frameOffset + channel * 2
                selected[index] = adjusted.toByte()
                selected[index + 1] = (adjusted shr 8).toByte()
            }
            frameOffset += frameSize
        }
        val threshold = Math.pow(10.0, noiseGateDb.coerceIn(-90, -10) / 20.0).toDouble() * 32767.0
        var peak = 0
        var i = 0
        while (i + 1 < selected.size) {
            val sample = ((selected[i + 1].toInt() shl 8) or (selected[i].toInt() and 0xff)).toShort().toInt()
            peak = max(peak, abs(sample))
            i += 2
        }
        val echoMix = echoMixPercent.coerceIn(0, 50) / 100.0
        val reverbMix = reverbPercent.coerceIn(0, 50) / 100.0
        val normalizationGain = if (normalize && peak > 0) minOf(32767.0 / (peak * (1.0 + echoMix + reverbMix * 0.65)), 8.0) else 1.0
        val manualGain = Math.pow(10.0, gainDb.coerceIn(-12, 12) / 20.0)
        val gain = normalizationGain * manualGain
        val original = if (echoMix > 0.0 || reverbMix > 0.0) selected.copyOf() else selected
        val echoDelayFrames = (echoDelayMs.coerceIn(50L, 1000L) * wav.sampleRate / 1000L).toInt()
        val echoDelayBytes = echoDelayFrames * frameSize
        val selectedFrames = selected.size / frameSize
        val fadeInFrames = (fadeInMs.coerceAtLeast(0L) * wav.sampleRate / 1000L).coerceAtMost(selectedFrames.toLong()).toInt()
        val fadeOutFrames = (fadeOutMs.coerceAtLeast(0L) * wav.sampleRate / 1000L).coerceAtMost(selectedFrames.toLong()).toInt()
        i = 0
        while (i + 1 < selected.size) {
            val sample = ((selected[i + 1].toInt() shl 8) or (selected[i].toInt() and 0xff)).toShort().toInt()
            val magnitude = abs(sample).toDouble()
            val reduction = noiseReductionPercent.coerceIn(0, 100) / 100.0
            val softAttenuation = if (reduction > 0.0 && threshold > 0.0 && magnitude < threshold) {
                val belowThreshold = (1.0 - magnitude / threshold).coerceIn(0.0, 1.0)
                1.0 - reduction * belowThreshold * belowThreshold
            } else 1.0
            val gated = if (noiseGateDb > -90 && magnitude < threshold && noiseReductionPercent == 0) 0
                else (sample * softAttenuation).roundToInt()
            val delayedIndex = i - echoDelayBytes
            val delayed = if (echoMix > 0.0 && delayedIndex >= 0) {
                ((original[delayedIndex + 1].toInt() shl 8) or (original[delayedIndex].toInt() and 0xff)).toShort().toInt()
            } else 0
            val reverb = if (reverbMix > 0.0) {
                val taps = intArrayOf(37, 53, 71, 89)
                var reflected = 0.0
                for (tapMs in taps) {
                    val tapIndex = i - (tapMs * wav.sampleRate / 1000) * frameSize
                    if (tapIndex >= 0) {
                        reflected += ((original[tapIndex + 1].toInt() shl 8) or (original[tapIndex].toInt() and 0xff)).toShort().toInt()
                    }
                }
                reflected / taps.size * reverbMix
            } else 0.0
            val withEcho = gated + delayed * echoMix + reverb
            val frame = i / frameSize
            val fadeIn = if (fadeInFrames > 0 && frame < fadeInFrames) frame.toDouble() / fadeInFrames else 1.0
            val framesFromEnd = selectedFrames - 1 - frame
            val fadeOut = if (fadeOutFrames > 0 && framesFromEnd < fadeOutFrames) framesFromEnd.toDouble() / fadeOutFrames else 1.0
            val envelope = minOf(fadeIn, fadeOut).coerceIn(0.0, 1.0)
            val adjusted = (withEcho * gain * envelope).roundToInt().coerceIn(-32768, 32767)
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
        val bytes = readBounded(input)
        require(bytes.size >= 44 && ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 4) == "WAVE") {
            "A fájl nem érvényes WAV."
        }
        val riffLength = leInt(bytes, 4).toLong() and 0xffffffffL
        val riffEndLong = 8L + riffLength
        require(riffEndLong in 44L..bytes.size.toLong()) { "A WAV RIFF mérete hibás vagy a fájl csonka." }
        val riffEnd = riffEndLong.toInt()
        var offset = 12
        var fmt: ByteArray? = null
        var pcm: ByteArray? = null
        var audioFormat = 0
        var channels = 0
        var sampleRate = 0
        var byteRate = 0
        var bits = 0
        while (offset + 8 <= riffEnd) {
            val id = ascii(bytes, offset, 4)
            val size = leInt(bytes, offset + 4)
            require(size >= 0) { "Hibás WAV chunk-méret." }
            val dataStart = offset + 8
            val dataEndLong = dataStart.toLong() + size.toLong()
            require(dataEndLong <= riffEnd.toLong()) { "A WAV egyik adatblokkja csonka." }
            val dataEnd = dataEndLong.toInt()
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
        require(audioFormat == 1 && bits == 16) { "Csak 16 bites, tömörítetlen PCM WAV támogatott." }
        require(channels in 1..2 && sampleRate in 8000..192000 && byteRate > 0) { "Nem támogatott WAV paraméterek." }
        val frameSize = channels * 2
        require(leShort(format, 12) == frameSize && byteRate == sampleRate * frameSize) { "A WAV fejlécének hangformátum-adatai ellentmondásosak." }
        require(audio.isNotEmpty() && audio.size % frameSize == 0) { "A WAV hangadata hiányos vagy üres." }
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

    private fun readBounded(input: InputStream): ByteArray {
        val limit = 129L * 1024L * 1024L
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "A WAV-fájl túl nagy (max. 129 MB)." }
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
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
