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

/** App-local 12-band PCM EQ. No system-wide effects or automatic compression. */
@androidx.media3.common.util.UnstableApi
class AurixEqualizerAudioProcessor : BaseAudioProcessor() {
    private val frequencies = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 12000f, 14000f, 16000f)
    @Volatile private var gains = FloatArray(12)
    @Volatile private var bassDb = 0f
    @Volatile private var trebleDb = 0f
    @Volatile private var preampDb = 0f
    @Volatile private var coeffs = Array(12) { Coeff() }

    // The limiter is an optional safety switch. It is OFF by default.
    @Volatile private var limiterEnabled = false
    @Volatile private var limiterCeiling = 0.8912509f // -1 dBFS when enabled
    private var limiterGain = 1f
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

    /** Enables only the optional output peak protection. No compressor is used. */
    fun setLimiterEnabled(enabled:Boolean) {
        limiterEnabled=enabled
        if(!enabled) limiterGain=1f
    }

    override fun onConfigure(f:AudioProcessor.AudioFormat):AudioProcessor.AudioFormat {
        if(f.encoding!=C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(f)
        rate=f.sampleRate.coerceAtLeast(8000)
        channels=f.channelCount.coerceIn(1,8)
        frameSamples=FloatArray(channels)
        limiterReleaseCoefficient=exp(-1.0/(rate*0.080)).toFloat()
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
        limiterGain=1f
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
        val useLimiter=limiterEnabled
        val ceiling=limiterCeiling.coerceIn(0.70f,0.98f)
        var peakLeft=0f
        var peakRight=0f

        while(input.remaining()>=2*channels) {
            var framePeak=0f
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
                frameSamples[ch]=sample
                framePeak=max(framePeak,abs(sample))
            }

            var gain=1f
            if(useLimiter) {
                val target=if(framePeak>ceiling && framePeak>0f) ceiling/framePeak else 1f
                limiterGain=if(target<limiterGain) target
                    else limiterReleaseCoefficient*limiterGain+(1f-limiterReleaseCoefficient)*target
                gain=limiterGain
            } else {
                limiterGain=1f
            }

            for(ch in 0 until channels) {
                val processed=frameSamples[ch]*gain
                // PCM16 has a hard representable ceiling; without the optional limiter
                // this clamp acts only when the EQ/preamp actually exceeds full scale.
                val outputSample=processed.coerceIn(-1f,0.9999695f)
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
