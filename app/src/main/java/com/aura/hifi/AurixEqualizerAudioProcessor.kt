package com.aura.hifi

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * AURA app-local DSP.
 *
 * Keeps the signal in floating point through the EQ stage when Media3 can provide it.
 * Bass boost is a broad low-shelf rather than three overlapping peak filters.
 * No automatic compression or limiter is applied unless the user explicitly enables it.
 */
@androidx.media3.common.util.UnstableApi
class AurixEqualizerAudioProcessor : BaseAudioProcessor() {
    private val frequencies = floatArrayOf(
        31f, 62f, 125f, 250f, 500f, 1000f,
        2000f, 4000f, 8000f, 12000f, 14000f, 16000f
    )

    @Volatile private var gains = FloatArray(12)
    @Volatile private var bassDb = 0f
    @Volatile private var trebleDb = 0f
    @Volatile private var preampDb = 0f
    @Volatile private var coeffs = Array(12) { Coeff() }
    @Volatile private var bassCoeff = Coeff()
    @Volatile private var limiterEnabled = false
    @Volatile private var limiterCeiling = 0.8912509f
    // Finom, fix kimeneti tartalék: -0,2 dB az összes EQ-feldolgozás után.
    private val outputTrim = 10.0.pow(-0.2 / 20.0).toFloat()

    private var limiterGain = 1f
    private var limiterReleaseCoefficient = 0.999f
    private var channels = 2
    private var rate = 44100
    private var encoding = C.ENCODING_PCM_16BIT
    private var frameSamples = FloatArray(2)

    private var bassX1 = FloatArray(2)
    private var bassX2 = FloatArray(2)
    private var bassY1 = FloatArray(2)
    private var bassY2 = FloatArray(2)
    private var x1 = Array(12) { FloatArray(2) }
    private var x2 = Array(12) { FloatArray(2) }
    private var y1 = Array(12) { FloatArray(2) }
    private var y2 = Array(12) { FloatArray(2) }

    @Volatile var onLevel: ((Float, Float) -> Unit)? = null

    data class Coeff(
        val b0: Float = 1f,
        val b1: Float = 0f,
        val b2: Float = 0f,
        val a1: Float = 0f,
        val a2: Float = 0f
    )

    fun setBandGainDb(i: Int, v: Float) {
        if (i !in 0..11) return
        val updated = gains.copyOf()
        updated[i] = v.coerceIn(-12f, 12f)
        gains = updated
        rebuild()
    }

    fun setBassBoostDb(v: Float) {
        bassDb = v.coerceIn(0f, 12f)
        rebuild()
    }

    fun setTrebleBoostDb(v: Float) {
        trebleDb = v.coerceIn(0f, 12f)
        rebuild()
    }

    fun setPreampDb(v: Float) {
        preampDb = v.coerceIn(-6f, 6f)
    }

    /** Optional peak protection. It remains OFF unless the user turns it on. */
    fun setLimiterEnabled(enabled: Boolean) {
        limiterEnabled = enabled
        if (!enabled) limiterGain = 1f
    }

    override fun onConfigure(format: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (format.encoding != C.ENCODING_PCM_16BIT &&
            format.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(format)
        }

        rate = format.sampleRate.coerceAtLeast(8000)
        channels = format.channelCount.coerceIn(1, 8)
        encoding = format.encoding
        frameSamples = FloatArray(channels)
        limiterReleaseCoefficient = exp(-1.0 / (rate * 0.080)).toFloat()
        resetState()
        rebuild()
        return format
    }

    override fun onFlush() {
        resetState()
        rebuild()
    }

    private fun resetState() {
        bassX1 = FloatArray(channels)
        bassX2 = FloatArray(channels)
        bassY1 = FloatArray(channels)
        bassY2 = FloatArray(channels)
        x1 = Array(12) { FloatArray(channels) }
        x2 = Array(12) { FloatArray(channels) }
        y1 = Array(12) { FloatArray(channels) }
        y2 = Array(12) { FloatArray(channels) }
        limiterGain = 1f
    }

    private fun rebuild() {
        val currentGains = gains
        coeffs = Array(12) { index ->
            val extraTreble = if (index in 9..11) trebleDb else 0f
            peaking(
                frequencies[index],
                (currentGains[index] + extraTreble).coerceIn(-18f, 18f)
            )
        }
        bassCoeff = lowShelf(90f, bassDb)
    }

    private fun peaking(frequency: Float, gainDb: Float): Coeff {
        if (abs(gainDb) < 0.001f) return Coeff()
        val omega = 2.0 * PI * frequency.coerceIn(20f, rate * 0.45f) / rate
        val alpha = sin(omega) / 2.0
        val amplitude = 10.0.pow(gainDb / 40.0)
        val cosine = cos(omega)
        val a0 = 1.0 + alpha / amplitude
        return Coeff(
            ((1.0 + alpha * amplitude) / a0).toFloat(),
            (-2.0 * cosine / a0).toFloat(),
            ((1.0 - alpha * amplitude) / a0).toFloat(),
            (-2.0 * cosine / a0).toFloat(),
            ((1.0 - alpha / amplitude) / a0).toFloat()
        )
    }

    /** RBJ low-shelf filter: smooth bass lift without stacking three overlapping peaks. */
    private fun lowShelf(frequency: Float, gainDb: Float): Coeff {
        if (abs(gainDb) < 0.001f) return Coeff()
        val omega = 2.0 * PI * frequency.coerceIn(20f, rate * 0.40f) / rate
        val cosine = cos(omega)
        val sine = sin(omega)
        val amplitude = 10.0.pow(gainDb / 40.0)
        val rootA = sqrt(amplitude)
        val alpha = sine / 2.0 * sqrt(2.0)
        val beta = 2.0 * rootA * alpha

        val b0 = amplitude * ((amplitude + 1.0) - (amplitude - 1.0) * cosine + beta)
        val b1 = 2.0 * amplitude * ((amplitude - 1.0) - (amplitude + 1.0) * cosine)
        val b2 = amplitude * ((amplitude + 1.0) - (amplitude - 1.0) * cosine - beta)
        val a0 = (amplitude + 1.0) + (amplitude - 1.0) * cosine + beta
        val a1 = -2.0 * ((amplitude - 1.0) + (amplitude + 1.0) * cosine)
        val a2 = (amplitude + 1.0) + (amplitude - 1.0) * cosine - beta

        return Coeff(
            (b0 / a0).toFloat(),
            (b1 / a0).toFloat(),
            (b2 / a0).toFloat(),
            (a1 / a0).toFloat(),
            (a2 / a0).toFloat()
        )
    }

    private fun processSample(input: Float, channel: Int, filters: Array<Coeff>, shelf: Coeff): Float {
        var sample = input
        var filtered = shelf.b0 * sample +
            shelf.b1 * bassX1[channel] +
            shelf.b2 * bassX2[channel] -
            shelf.a1 * bassY1[channel] -
            shelf.a2 * bassY2[channel]

        bassX2[channel] = bassX1[channel]
        bassX1[channel] = sample
        bassY2[channel] = bassY1[channel]
        bassY1[channel] = filtered
        sample = filtered

        for (band in 0 until 12) {
            val c = filters[band]
            filtered = c.b0 * sample +
                c.b1 * x1[band][channel] +
                c.b2 * x2[band][channel] -
                c.a1 * y1[band][channel] -
                c.a2 * y2[band][channel]

            x2[band][channel] = x1[band][channel]
            x1[band][channel] = sample
            y2[band][channel] = y1[band][channel]
            y1[band][channel] = filtered
            sample = filtered
        }
        return sample
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val frameBytes = bytesPerSample * channels
        if (inputBuffer.remaining() < frameBytes) return

        val input = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val output = replaceOutputBuffer(input.remaining()).order(ByteOrder.LITTLE_ENDIAN)
        val currentFilters = coeffs
        val currentShelf = bassCoeff
        val preamp = 10.0.pow(preampDb / 20.0).toFloat()
        val useLimiter = limiterEnabled
        val ceiling = limiterCeiling.coerceIn(0.70f, 0.98f)
        var peakLeft = 0f
        var peakRight = 0f

        while (input.remaining() >= frameBytes) {
            var framePeak = 0f
            for (channel in 0 until channels) {
                val source = if (encoding == C.ENCODING_PCM_FLOAT) {
                    input.float
                } else {
                    input.short / 32768f
                }
                val processed = processSample(source * preamp, channel, currentFilters, currentShelf)
                frameSamples[channel] = processed
                framePeak = max(framePeak, abs(processed))
            }

            var outputGain = 1f
            if (useLimiter) {
                val target = if (framePeak > ceiling && framePeak > 0f) ceiling / framePeak else 1f
                limiterGain = if (target < limiterGain) {
                    target
                } else {
                    limiterReleaseCoefficient * limiterGain +
                        (1f - limiterReleaseCoefficient) * target
                }
                outputGain = limiterGain
            } else {
                limiterGain = 1f
            }

            for (channel in 0 until channels) {
                val sample = frameSamples[channel] * outputGain * outputTrim
                if (channel == 0) peakLeft = max(peakLeft, abs(sample))
                else if (channel == 1) peakRight = max(peakRight, abs(sample))

                if (encoding == C.ENCODING_PCM_FLOAT) {
                    // Keep floating-point headroom; do not hard-clip inside the DSP chain.
                    output.putFloat(sample)
                } else {
                    // PCM16 fallback must quantize to its representable range.
                    val bounded = sample.coerceIn(-1f, 0.9999695f)
                    output.putShort(
                        (bounded * 32768f).toInt().coerceIn(-32768, 32767).toShort()
                    )
                }
            }
        }

        input.position(input.limit())
        output.flip()
        if (channels == 1) peakRight = peakLeft
        onLevel?.invoke(peakLeft, peakRight)
    }
}
