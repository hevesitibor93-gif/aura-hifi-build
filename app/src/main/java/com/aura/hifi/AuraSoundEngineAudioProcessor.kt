package com.aura.hifi

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * AURA's optional post-EQ sound engine.
 *
 * All creative modules start disabled and all numeric controls start neutral.
 * This processor is deliberately separate from AurixEqualizerAudioProcessor:
 * disabling every module leaves the original AURA EQ signal unchanged.
 */
@androidx.media3.common.util.UnstableApi
class AuraSoundEngineAudioProcessor : BaseAudioProcessor() {
    @Volatile private var bassDepthEnabled = false
    @Volatile private var bassPunchEnabled = false
    @Volatile private var harmonicBassEnabled = false
    @Volatile private var clarityEnabled = false
    @Volatile private var toneEnabled = false
    @Volatile private var stereoWidthEnabled = false
    @Volatile private var balanceEnabled = false
    @Volatile private var preampEnabled = false

    @Volatile private var bassDepthDb = 0f
    @Volatile private var bassPunchDb = 0f
    @Volatile private var harmonicBassAmount = 0f
    @Volatile private var clarityDb = 0f
    @Volatile private var toneAmount = 0f
    @Volatile private var stereoWidth = 1f
    @Volatile private var balance = 0f
    @Volatile private var preampDb = 0f

    @Volatile var onPeak: ((Float, Float) -> Unit)? = null
    @Volatile var onClipping: (() -> Unit)? = null

    private var sampleRate = 44100
    private var channelCount = 2
    private var depthFilters = emptyArray<Biquad>()
    private var punchFilters = emptyArray<Biquad>()
    private var clarityFilters = emptyArray<Biquad>()
    private var lowToneFilters = emptyArray<Biquad>()
    private var highToneFilters = emptyArray<Biquad>()
    private var bassLowPass = FloatArray(2)
    private var harmonicDc = FloatArray(2)
    private var clippingFrames = 0

    fun setBassDepthEnabled(v: Boolean) { bassDepthEnabled = v }
    fun setBassPunchEnabled(v: Boolean) { bassPunchEnabled = v }
    fun setHarmonicBassEnabled(v: Boolean) { harmonicBassEnabled = v }
    fun setClarityEnabled(v: Boolean) { clarityEnabled = v }
    fun setToneEnabled(v: Boolean) { toneEnabled = v }
    fun setStereoWidthEnabled(v: Boolean) { stereoWidthEnabled = v }
    fun setBalanceEnabled(v: Boolean) { balanceEnabled = v }
    fun setPreampEnabled(v: Boolean) { preampEnabled = v }

    fun setBassDepthDb(v: Float) { bassDepthDb = v.coerceIn(0f, 6f); rebuildFilters() }
    fun setBassPunchDb(v: Float) { bassPunchDb = v.coerceIn(0f, 6f); rebuildFilters() }
    fun setHarmonicBassAmount(v: Float) { harmonicBassAmount = v.coerceIn(0f, 1f) }
    fun setClarityDb(v: Float) { clarityDb = v.coerceIn(0f, 6f); rebuildFilters() }
    fun setToneAmount(v: Float) { toneAmount = v.coerceIn(-1f, 1f); rebuildFilters() }
    fun setStereoWidth(v: Float) { stereoWidth = v.coerceIn(0f, 1.5f) }
    fun setBalance(v: Float) { balance = v.coerceIn(-1f, 1f) }
    fun setPreampDb(v: Float) { preampDb = v.coerceIn(-12f, 6f) }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(8000)
        channelCount = inputAudioFormat.channelCount.coerceIn(1, 8)
        resetState()
        rebuildFilters()
        return inputAudioFormat
    }

    override fun onFlush() {
        resetState()
        rebuildFilters()
    }

    private fun resetState() {
        bassLowPass = FloatArray(channelCount)
        harmonicDc = FloatArray(channelCount)
        depthFilters = Array(channelCount) { Biquad() }
        punchFilters = Array(channelCount) { Biquad() }
        clarityFilters = Array(channelCount) { Biquad() }
        lowToneFilters = Array(channelCount) { Biquad() }
        highToneFilters = Array(channelCount) { Biquad() }
        clippingFrames = 0
    }

    private fun rebuildFilters() {
        if (channelCount <= 0) return
        depthFilters = Array(channelCount) { Biquad.peaking(sampleRate, 58f, 0.85f, bassDepthDb) }
        punchFilters = Array(channelCount) { Biquad.peaking(sampleRate, 105f, 0.9f, bassPunchDb) }
        clarityFilters = Array(channelCount) { Biquad.peaking(sampleRate, 8500f, 0.75f, clarityDb) }
        val lowDb = (-toneAmount * 3.0f)
        val highDb = (toneAmount * 3.0f)
        lowToneFilters = Array(channelCount) { Biquad.lowShelf(sampleRate, 220f, lowDb) }
        highToneFilters = Array(channelCount) { Biquad.highShelf(sampleRate, 3500f, highDb) }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frameBytes = 2 * channelCount
        val byteCount = inputBuffer.remaining() / frameBytes * frameBytes
        if (byteCount <= 0) return
        val output = replaceOutputBuffer(byteCount).order(ByteOrder.LITTLE_ENDIAN)
        val input = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val useDepth = bassDepthEnabled
        val usePunch = bassPunchEnabled
        val useHarmonics = harmonicBassEnabled
        val useClarity = clarityEnabled
        val useTone = toneEnabled
        val useWidth = stereoWidthEnabled && channelCount >= 2
        val useBalance = balanceEnabled && channelCount >= 2
        val gain = if (preampEnabled) 10.0.pow(preampDb / 20.0).toFloat() else 1f
        val width = if (useWidth) stereoWidth else 1f
        val bal = if (useBalance) balance else 0f
        val leftGain = if (bal > 0f) 1f - bal else 1f
        val rightGain = if (bal < 0f) 1f + bal else 1f
        var peakLeft = 0f
        var peakRight = 0f
        var clipped = false
        val lowPassAlpha = (1.0 - kotlin.math.exp(-2.0 * PI * 150.0 / sampleRate)).toFloat()
        val dcAlpha = (1.0 - kotlin.math.exp(-2.0 * PI * 8.0 / sampleRate)).toFloat()

        while (input.remaining() >= frameBytes) {
            val frame = FloatArray(channelCount)
            for (ch in 0 until channelCount) {
                var x = input.short / 32768f
                if (useDepth) x = depthFilters[ch].process(x)
                if (usePunch) x = punchFilters[ch].process(x)
                if (useClarity) x = clarityFilters[ch].process(x)
                if (useTone) {
                    x = lowToneFilters[ch].process(x)
                    x = highToneFilters[ch].process(x)
                }
                if (useHarmonics && harmonicBassAmount > 0f) {
                    bassLowPass[ch] += lowPassAlpha * (x - bassLowPass[ch])
                    val bass = bassLowPass[ch]
                    val square = bass * bass
                    harmonicDc[ch] += dcAlpha * (square - harmonicDc[ch])
                    x += (square - harmonicDc[ch]) * harmonicBassAmount * 0.75f
                }
                frame[ch] = x * gain
            }

            if (useWidth) {
                val mid = (frame[0] + frame[1]) * 0.5f
                val side = (frame[0] - frame[1]) * 0.5f * width
                frame[0] = mid + side
                frame[1] = mid - side
            }
            if (useBalance) {
                frame[0] *= leftGain
                frame[1] *= rightGain
            }

            for (ch in 0 until channelCount) {
                val sample = frame[ch]
                if (abs(sample) >= 1f) clipped = true
                val bounded = sample.coerceIn(-1f, 0.9999695f)
                if (ch == 0) peakLeft = maxOf(peakLeft, abs(sample))
                else if (ch == 1) peakRight = maxOf(peakRight, abs(sample))
                output.putShort((bounded * 32768f).toInt().toShort())
            }
        }
        input.position(input.position() + (input.remaining() / frameBytes) * frameBytes)
        output.flip()
        if (channelCount == 1) peakRight = peakLeft
        onPeak?.invoke(peakLeft.coerceAtMost(1f), peakRight.coerceAtMost(1f))
        if (clipped) {
            clippingFrames++
            if (clippingFrames == 1 || clippingFrames % 20 == 0) onClipping?.invoke()
        } else {
            clippingFrames = 0
        }
    }

    private class Biquad {
        private var b0 = 1f
        private var b1 = 0f
        private var b2 = 0f
        private var a1 = 0f
        private var a2 = 0f
        private var z1 = 0f
        private var z2 = 0f

        fun process(x: Float): Float {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }

        companion object {
            fun peaking(rate: Int, frequency: Float, q: Float, db: Float): Biquad {
                val result = Biquad()
                if (abs(db) < 0.0001f) return result
                val w = 2.0 * PI * frequency.coerceIn(20f, rate * 0.45f) / rate
                val alpha = sin(w) / (2.0 * q)
                val a = 10.0.pow(db / 40.0)
                val c = cos(w)
                val a0 = 1.0 + alpha / a
                result.b0 = ((1.0 + alpha * a) / a0).toFloat()
                result.b1 = ((-2.0 * c) / a0).toFloat()
                result.b2 = ((1.0 - alpha * a) / a0).toFloat()
                result.a1 = ((-2.0 * c) / a0).toFloat()
                result.a2 = ((1.0 - alpha / a) / a0).toFloat()
                return result
            }

            fun lowShelf(rate: Int, frequency: Float, db: Float): Biquad = shelf(rate, frequency, db, true)
            fun highShelf(rate: Int, frequency: Float, db: Float): Biquad = shelf(rate, frequency, db, false)

            private fun shelf(rate: Int, frequency: Float, db: Float, low: Boolean): Biquad {
                val result = Biquad()
                if (abs(db) < 0.0001f) return result
                val a = 10.0.pow(db / 40.0)
                val w = 2.0 * PI * frequency.coerceIn(20f, rate * 0.45f) / rate
                val c = cos(w)
                val s = sin(w)
                val alpha = s / 2.0 * kotlin.math.sqrt(2.0)
                val beta = 2.0 * kotlin.math.sqrt(a) * alpha
                val a0: Double
                val b0: Double
                val b1: Double
                val b2: Double
                val aa1: Double
                val aa2: Double
                if (low) {
                    b0 = a * ((a + 1) - (a - 1) * c + beta)
                    b1 = 2 * a * ((a - 1) - (a + 1) * c)
                    b2 = a * ((a + 1) - (a - 1) * c - beta)
                    a0 = (a + 1) + (a - 1) * c + beta
                    aa1 = -2 * ((a - 1) + (a + 1) * c)
                    aa2 = (a + 1) + (a - 1) * c - beta
                } else {
                    b0 = a * ((a + 1) + (a - 1) * c + beta)
                    b1 = -2 * a * ((a - 1) + (a + 1) * c)
                    b2 = a * ((a + 1) + (a - 1) * c - beta)
                    a0 = (a + 1) - (a - 1) * c + beta
                    aa1 = 2 * ((a - 1) - (a + 1) * c)
                    aa2 = (a + 1) - (a - 1) * c - beta
                }
                result.b0 = (b0 / a0).toFloat()
                result.b1 = (b1 / a0).toFloat()
                result.b2 = (b2 / a0).toFloat()
                result.a1 = (aa1 / a0).toFloat()
                result.a2 = (aa2 / a0).toFloat()
                return result
            }
        }
    }
}
