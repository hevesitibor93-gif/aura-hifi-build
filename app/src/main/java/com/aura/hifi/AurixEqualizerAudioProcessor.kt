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

/** App-local EQ plus the first AURA Sound Engine Premium Power dynamics stage. */
@androidx.media3.common.util.UnstableApi
class AurixEqualizerAudioProcessor : BaseAudioProcessor() {
    private val frequencies = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 12000f, 14000f, 16000f)
    @Volatile private var gains = FloatArray(12)
    @Volatile private var bassDb = 0f
    @Volatile private var trebleDb = 0f
    @Volatile private var preampDb = 0f
    @Volatile private var coeffs = Array(12) { Coeff() }

    // Premium Power foundation. Conservative defaults preserve punch while controlling peaks.
    @Volatile private var soundEngineEnabled = true
    @Volatile private var compressorThresholdDb = -12f
    @Volatile private var compressorRatio = 2.2f
    @Volatile private var makeupGainDb = 1.5f
    @Volatile private var limiterCeiling = 0.8912509f // -1 dBFS sample-peak ceiling
    @Volatile private var harmonicBassAmount = 0.42f
    private var bassLowPassCoefficient = 0.98f
    private var bassLowState = FloatArray(2)
    private var compressorEnvelope = 0f
    private var limiterGain = 1f
    private var compressorReleaseCoefficient = 0.999f
    private var limiterReleaseCoefficient = 0.999f
    private var frameSamples = FloatArray(2)

    @Volatile var onLevel: ((Float, Float) -> Unit)? = null
    private var channels = 2
    private var rate = 44100
    private var x1 = Array(12) { FloatArray(2) }
    private var x2 = Array(12) { FloatArray(2) }
    private var y1 = Array(12) { FloatArray(2) }
    private var y2 = Array(12) { FloatArray(2) }

    data class Coeff(val b0:Float=1f,val b1:Float=0f,val b2:Float=0f,val a1:Float=0f,val a2:Float=0f)

    fun setBandGainDb(i:Int,v:Float) {
        if(i !in 0..11)return
        val a=gains.copyOf()
        a[i]=v.coerceIn(-12f,12f)
        gains=a
        rebuild()
    }
    fun setBassBoostDb(v:Float) { bassDb=v.coerceIn(0f,12f); rebuild() }
    fun setTrebleBoostDb(v:Float) { trebleDb=v.coerceIn(0f,12f); rebuild() }
    fun setPreampDb(v:Float) { preampDb=v.coerceIn(-6f,6f) }

    /** Enables/disables AURA dynamics without changing the user's EQ settings. */
    fun setSoundEngineEnabled(enabled:Boolean) { soundEngineEnabled=enabled }

    /** Controls the generated low-bass harmonics; 0 disables the effect. */
    fun setHarmonicBassAmount(amount:Float) { harmonicBassAmount=amount.coerceIn(0f,0.8f) }

    override fun onConfigure(f:AudioProcessor.AudioFormat):AudioProcessor.AudioFormat {
        if(f.encoding!=C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(f)
        rate=f.sampleRate.coerceAtLeast(8000)
        channels=f.channelCount.coerceIn(1,8)
        frameSamples=FloatArray(channels)
        compressorReleaseCoefficient=exp(-1.0/(rate*0.090)).toFloat()
        limiterReleaseCoefficient=exp(-1.0/(rate*0.075)).toFloat()
        bassLowPassCoefficient=exp(-2.0*PI*145.0/rate).toFloat()
        resetState()
        rebuild()
        return f
    }

    override fun onFlush() { resetState(); rebuild() }

    private fun resetState() {
        x1=Array(12){FloatArray(channels)}
        x2=Array(12){FloatArray(channels)}
        y1=Array(12){FloatArray(channels)}
        y2=Array(12){FloatArray(channels)}
        compressorEnvelope=0f
        limiterGain=1f
        bassLowState=FloatArray(channels)
    }

    private fun rebuild() {
        val g=gains
        coeffs=Array(12){i->
            val extra=when(i){0,1,2->bassDb;9,10,11->trebleDb;else->0f}
            peaking(frequencies[i],(g[i]+extra).coerceIn(-18f,18f))
        }
    }

    private fun peaking(freq:Float,gain:Float):Coeff {
        if(abs(gain)<0.001f)return Coeff()
        val w=2.0*PI*freq.coerceIn(20f,rate*0.45f)/rate
        val alpha=sin(w)/2.0
        val a=10.0.pow(gain/40.0)
        val c=cos(w)
        val a0=1.0+alpha/a
        return Coeff(
            ((1+alpha*a)/a0).toFloat(),
            (-2*c/a0).toFloat(),
            ((1-alpha*a)/a0).toFloat(),
            (-2*c/a0).toFloat(),
            ((1-alpha/a)/a0).toFloat()
        )
    }

    override fun queueInput(inputBuffer:ByteBuffer) {
        val n=inputBuffer.remaining()
        if(n<=0)return
        val out=replaceOutputBuffer(n).order(ByteOrder.LITTLE_ENDIAN)
        val input=inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val cfs=coeffs
        val pre=10.0.pow(preampDb/20.0).toFloat()
        val useDynamics=soundEngineEnabled
        val threshold=10.0.pow(compressorThresholdDb/20.0).toFloat()
        val ratio=compressorRatio.coerceIn(1f,4f)
        val makeup=if(useDynamics)10.0.pow(makeupGainDb/20.0).toFloat() else 1f
        val ceiling=limiterCeiling.coerceIn(0.70f,0.98f)
        val bassAmount=harmonicBassAmount
        var peakLeft=0f
        var peakRight=0f

        while(input.remaining()>=2*channels) {
            var framePeak=0f

            // EQ is processed in floating point; no hard clipping occurs before dynamics.
            for(ch in 0 until channels) {
                var sample=input.short/32768f*pre
                for(b in 0 until 12) {
                    val c=cfs[b]
                    val y=c.b0*sample+c.b1*x1[b][ch]+c.b2*x2[b][ch]-c.a1*y1[b][ch]-c.a2*y2[b][ch]
                    x2[b][ch]=x1[b][ch]
                    x1[b][ch]=sample
                    y2[b][ch]=y1[b][ch]
                    y1[b][ch]=y
                    sample=y
                }
                // Parallel low-frequency extraction adds a restrained third-harmonic layer.
                // This creates bass presence without simply boosting sub-bass level.
                if(bassAmount>0f) {
                    val low=bassLowState[ch]+(1f-bassLowPassCoefficient)*(sample-bassLowState[ch])
                    bassLowState[ch]=low
                    sample += low*low*low*bassAmount
                }
                frameSamples[ch]=sample
                framePeak=max(framePeak,abs(sample))
            }

            var compressorGain=1f
            var nextLimiterGain=1f
            if(useDynamics) {
                // Fast attack / smooth release keeps transients punchy without abrupt pumping.
                if(framePeak>compressorEnvelope) compressorEnvelope=framePeak
                else compressorEnvelope=compressorReleaseCoefficient*compressorEnvelope+
                    (1f-compressorReleaseCoefficient)*framePeak

                if(compressorEnvelope>threshold && compressorEnvelope>0f) {
                    compressorGain=(threshold/compressorEnvelope).pow(1f-1f/ratio)
                }

                var postDynamicsPeak=framePeak*compressorGain*makeup
                val targetLimiterGain=if(postDynamicsPeak>ceiling && postDynamicsPeak>0f)
                    ceiling/postDynamicsPeak else 1f

                // Limiter attack is immediate; release is smoothed to avoid distortion modulation.
                limiterGain=if(targetLimiterGain<limiterGain) targetLimiterGain
                    else limiterReleaseCoefficient*limiterGain+(1f-limiterReleaseCoefficient)*targetLimiterGain
                nextLimiterGain=limiterGain
            }

            for(ch in 0 until channels) {
                val processed=frameSamples[ch]*compressorGain*makeup*nextLimiterGain
                val outputSample=processed.coerceIn(-ceiling,ceiling)
                if(ch==0) peakLeft=max(peakLeft,abs(outputSample))
                else if(ch==1) peakRight=max(peakRight,abs(outputSample))
                out.putShort((outputSample*32768f).toInt().coerceIn(-32768,32767).toShort())
            }
        }

        input.position(input.limit())
        out.flip()
        if(channels==1) peakRight=peakLeft
        onLevel?.invoke(peakLeft,peakRight)
    }
}
