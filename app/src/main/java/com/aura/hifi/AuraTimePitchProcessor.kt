package com.aura.hifi

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Offline WSOLA time-stretch with pitch transposition by resampling followed by
 * compensating time-stretch. Input/output are interleaved signed 16-bit PCM.
 */
object AuraTimePitchProcessor {
    fun process(pcm: ByteArray, sampleRate: Int, channels: Int, tempoPercent: Int, pitchSemitones: Int): ByteArray {
        require(channels in 1..2 && sampleRate in 8000..192000)
        val tempo = tempoPercent.coerceIn(50, 200) / 100.0
        val semitones = pitchSemitones.coerceIn(-12, 12)
        if (tempo == 1.0 && semitones == 0) return pcm
        require(pcm.size <= 64 * 1024 * 1024) { "A tempó/hangmagasság feldolgozás jelenleg legfeljebb 64 MB WAV-adaton használható." }
        val frameSize = channels * 2
        val inputFrames = pcm.size / frameSize
        require(inputFrames > 0)
        val pitchFactor = 2.0.pow(semitones / 12.0)
        val pitchedFrames = max(1, (inputFrames / pitchFactor).roundToInt())
        val pitched = FloatArray(pitchedFrames * channels)
        for (frame in 0 until pitchedFrames) {
            val source = (frame * pitchFactor).coerceAtMost((inputFrames - 1).toDouble())
            val a = source.toInt()
            val b = min(a + 1, inputFrames - 1)
            val fraction = source - a
            for (channel in 0 until channels) {
                val ia = (a * channels + channel) * 2
                val ib = (b * channels + channel) * 2
                val sa = (((pcm[ia + 1].toInt() shl 8) or (pcm[ia].toInt() and 255)).toShort()).toInt()
                val sb = (((pcm[ib + 1].toInt() shl 8) or (pcm[ib].toInt() and 255)).toShort()).toInt()
                pitched[frame * channels + channel] = (sa + (sb - sa) * fraction).toFloat()
            }
        }

        val targetFrames = max(1, (inputFrames / tempo).roundToInt())
        val stretch = targetFrames.toDouble() / pitchedFrames
        val window = (sampleRate * 40 / 1000).coerceAtLeast(256)
        val hop = (sampleRate * 10 / 1000).coerceAtLeast(64).coerceAtMost(window / 2)
        val overlap = window - hop
        val search = (sampleRate * 5 / 1000).coerceAtLeast(16)
        val output = FloatArray(targetFrames * channels)
        val firstCount = min(window, min(pitchedFrames, targetFrames))
        pitched.copyInto(output, 0, 0, firstCount * channels)
        var outPos = hop
        while (outPos < targetFrames) {
            val expected = (outPos / stretch).roundToInt().coerceIn(0, max(0, pitchedFrames - window))
            val low = max(0, expected - search)
            val high = min(max(0, pitchedFrames - window), expected + search)
            var best = expected.coerceIn(low, high)
            var bestScore = Double.NEGATIVE_INFINITY
            val compare = min(overlap, min(targetFrames - outPos, pitchedFrames - best))
            if (compare > 0) {
                for (candidate in low..high step max(1, search / 12)) {
                    var dot = 0.0
                    var energy = 1.0
                    val count = min(compare, window)
                    val stride = max(1, count / 500)
                    var f = 0
                    while (f < count) {
                        for (ch in 0 until channels) {
                            val old = output[(outPos + f) * channels + ch].toDouble()
                            val fresh = pitched[(candidate + f) * channels + ch].toDouble()
                            dot += old * fresh
                            energy += fresh * fresh
                        }
                        f += stride
                    }
                    val score = dot / kotlin.math.sqrt(energy)
                    if (score > bestScore) { bestScore = score; best = candidate }
                }
            }
            val blend = min(overlap, min(targetFrames - outPos, pitchedFrames - best))
            for (f in 0 until blend) {
                val alpha = (f + 1.0) / (blend + 1.0)
                for (ch in 0 until channels) {
                    val index = (outPos + f) * channels + ch
                    val fresh = pitched[(best + f) * channels + ch]
                    output[index] = (output[index] * (1.0 - alpha) + fresh * alpha).toFloat()
                }
            }
            val copyStart = outPos + blend
            val sourceStart = best + blend
            val copyCount = min(window - blend, min(targetFrames - copyStart, pitchedFrames - sourceStart))
            if (copyCount > 0) {
                pitched.copyInto(output, copyStart * channels, sourceStart * channels, (sourceStart + copyCount) * channels)
            }
            outPos += hop
        }
        val result = ByteArray(output.size * 2)
        for (i in output.indices) {
            val sample = output[i].roundToInt().coerceIn(-32768, 32767)
            result[i * 2] = sample.toByte()
            result[i * 2 + 1] = (sample shr 8).toByte()
        }
        return result
    }
}
