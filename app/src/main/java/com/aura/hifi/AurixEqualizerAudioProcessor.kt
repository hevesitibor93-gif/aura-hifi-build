package com.aura.hifi

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** App-local 12-band PCM EQ. It does not create Android global audio effects. */
@androidx.media3.common.util.UnstableApi
class AurixEqualizerAudioProcessor : BaseAudioProcessor() {
    private val frequencies = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 12000f, 14000f, 16000f)
    @Volatile private var gains = FloatArray(12)
    @Volatile private var bassDb = 0f
    @Volatile private var trebleDb = 0f
    @Volatile private var preampDb = 0f
    @Volatile private var coeffs = Array(12) { Coeff() }
    @Volatile var onLevel: ((Float, Float) -> Unit)? = null
    @Volatile var onSpectrum: ((Float, Float, Float) -> Unit)? = null
    private var channels = 2
    private var rate = 44100
    private var low250Filters = Array(2) { Biquad() }
    private var low4kFilters = Array(2) { Biquad() }

    private class Biquad {
        private var b0 = 1f; private var b1 = 0f; private var b2 = 0f
        private var a1 = 0f; private var a2 = 0f
        private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f
        fun configureLowPass(sampleRate: Int, frequency: Float) {
            val w = 2.0 * PI * frequency.coerceAtMost(sampleRate * 0.40f) / sampleRate
            val c = cos(w); val alpha = sin(w) / (2.0 * kotlin.math.sqrt(0.5))
            val a0 = 1.0 + alpha
            b0 = ((1.0 - c) / 2.0 / a0).toFloat()
            b1 = ((1.0 - c) / a0).toFloat()
            b2 = b0
            a1 = (-2.0 * c / a0).toFloat()
            a2 = ((1.0 - alpha) / a0).toFloat()
            reset()
        }
        fun process(input: Float): Float {
            val output = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = input; y2 = y1; y1 = output
            return output
        }
        fun reset() { x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f }
    }
    private var x1 = Array(12) { FloatArray(2) }; private var x2 = Array(12) { FloatArray(2) }
    private var y1 = Array(12) { FloatArray(2) }; private var y2 = Array(12) { FloatArray(2) }
    data class Coeff(val b0:Float=1f,val b1:Float=0f,val b2:Float=0f,val a1:Float=0f,val a2:Float=0f)

    fun setBandGainDb(i:Int,v:Float) { if(i !in 0..11)return; val a=gains.copyOf(); a[i]=v.coerceIn(-12f,12f); gains=a; rebuild() }
    fun setBassBoostDb(v:Float) { bassDb=v.coerceIn(0f,12f); rebuild() }
    fun setTrebleBoostDb(v:Float) { trebleDb=v.coerceIn(0f,12f); rebuild() }
    fun setPreampDb(v:Float) { preampDb=v.coerceIn(-6f,6f) }
    override fun onConfigure(f:AudioProcessor.AudioFormat):AudioProcessor.AudioFormat {
        if(f.encoding!=C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(f)
        rate=f.sampleRate.coerceAtLeast(8000); channels=f.channelCount.coerceIn(1,8); resetState(); configureSpectrumFilters(); rebuild(); return f
    }
    override fun onFlush() { resetState(); rebuild() }
    private fun resetState() {
        x1=Array(12){FloatArray(channels)}; x2=Array(12){FloatArray(channels)}
        y1=Array(12){FloatArray(channels)}; y2=Array(12){FloatArray(channels)}
        low250Filters = Array(channels) { Biquad() }
        low4kFilters = Array(channels) { Biquad() }
        configureSpectrumFilters()
    }
    private fun configureSpectrumFilters() {
        low250Filters.forEach { it.configureLowPass(rate, 250f) }
        low4kFilters.forEach { it.configureLowPass(rate, 4000f) }
    }
    private fun rebuild() {
        val g=gains
        coeffs=Array(12){i->
            val extra=when(i){0,1,2->bassDb;9,10,11->trebleDb;else->0f}
            peaking(frequencies[i],(g[i]+extra).coerceIn(-18f,18f))
        }
    }
    private fun peaking(freq:Float,gain:Float):Coeff {
        if(kotlin.math.abs(gain)<0.001f)return Coeff()
        val w=2.0*PI*freq.coerceIn(20f,rate*0.45f)/rate
        val alpha=sin(w)/2.0; val a=10.0.pow(gain/40.0); val c=cos(w); val a0=1.0+alpha/a
        return Coeff(((1+alpha*a)/a0).toFloat(),(-2*c/a0).toFloat(),((1-alpha*a)/a0).toFloat(),(-2*c/a0).toFloat(),((1-alpha/a)/a0).toFloat())
    }
    override fun queueInput(inputBuffer:ByteBuffer) {
        val n=inputBuffer.remaining(); if(n<=0)return
        val out=replaceOutputBuffer(n).order(ByteOrder.LITTLE_ENDIAN); val input=inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val cfs=coeffs; val pre=10.0.pow(preampDb/20.0).toFloat()
        val peaks = FloatArray(2)
        val bandPeaks = FloatArray(3)
        while(input.remaining()>=2*channels) for(ch in 0 until channels) {
            var sample=input.short/32768f*pre
            for(b in 0 until 12) {
                val c=cfs[b]
                val y=c.b0*sample+c.b1*x1[b][ch]+c.b2*x2[b][ch]-c.a1*y1[b][ch]-c.a2*y2[b][ch]
                x2[b][ch]=x1[b][ch]; x1[b][ch]=sample; y2[b][ch]=y1[b][ch]; y1[b][ch]=y; sample=y
            }
            val outputSample = sample.coerceIn(-1f,0.9999695f)
            peaks[if (ch == 0) 0 else 1] = maxOf(peaks[if (ch == 0) 0 else 1], kotlin.math.abs(outputSample))
            val low = low250Filters[ch].process(outputSample)
            val lowAndMid = low4kFilters[ch].process(outputSample)
            val bandIndex = if (ch == 0) 0 else 1
            bandPeaks[0] = maxOf(bandPeaks[0], kotlin.math.abs(low))
            bandPeaks[1] = maxOf(bandPeaks[1], kotlin.math.abs(lowAndMid - low))
            bandPeaks[2] = maxOf(bandPeaks[2], kotlin.math.abs(outputSample - lowAndMid))
            out.putShort((outputSample*32768f).toInt().toShort())
        }
        input.position(input.limit()); out.flip()
        val rightPeak = if (channels > 1) peaks[1] else peaks[0]
        onLevel?.invoke(peaks[0], rightPeak)
        onSpectrum?.invoke(bandPeaks[0], bandPeaks[1], bandPeaks[2])
    }
}
