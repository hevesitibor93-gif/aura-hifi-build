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

/** Original AURA app-local 12-band PCM EQ. It does not create Android global audio effects. */
@androidx.media3.common.util.UnstableApi
class AurixEqualizerAudioProcessor : BaseAudioProcessor() {
    private val frequencies = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 12000f, 14000f, 16000f)
    @Volatile private var gains = FloatArray(12)
    @Volatile private var bassDb = 0f
    @Volatile private var trebleDb = 0f
    @Volatile private var preampDb = 0f
    @Volatile private var coeffs = Array(12) { Coeff() }
    private var channels = 2
    private var rate = 44100
    private var x1 = Array(12) { FloatArray(2) }; private var x2 = Array(12) { FloatArray(2) }
    private var y1 = Array(12) { FloatArray(2) }; private var y2 = Array(12) { FloatArray(2) }
    data class Coeff(val b0:Float=1f,val b1:Float=0f,val b2:Float=0f,val a1:Float=0f,val a2:Float=0f)
    @Volatile var onLevel: ((Float, Float) -> Unit)? = null
    @Volatile var onClipping: (() -> Unit)? = null
    private var clippingFrames = 0

    fun setBandGainDb(i:Int,v:Float) { if(i !in 0..11)return; val a=gains.copyOf(); a[i]=v.coerceIn(-12f,12f); gains=a; rebuild() }
    fun setBassBoostDb(v:Float) { bassDb=v.coerceIn(0f,12f); rebuild() }
    fun setTrebleBoostDb(v:Float) { trebleDb=v.coerceIn(0f,12f); rebuild() }
    fun setPreampDb(v:Float) { preampDb=v.coerceIn(-6f,6f) }
    override fun onConfigure(f:AudioProcessor.AudioFormat):AudioProcessor.AudioFormat {
        if(f.encoding!=C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(f)
        rate=f.sampleRate.coerceAtLeast(8000); channels=f.channelCount.coerceIn(1,8); resetState(); clippingFrames = 0; rebuild(); return f
    }
    override fun onFlush() { resetState(); clippingFrames = 0; rebuild() }
    private fun resetState() {
        x1=Array(12){FloatArray(channels)}; x2=Array(12){FloatArray(channels)}
        y1=Array(12){FloatArray(channels)}; y2=Array(12){FloatArray(channels)}
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
        var peakLeft = 0f
        var peakRight = 0f
        var clipped = false
        while(input.remaining()>=2*channels) for(ch in 0 until channels) {
            var sample=input.short/32768f*pre
            for(b in 0 until 12) {
                val c=cfs[b]
                val y=c.b0*sample+c.b1*x1[b][ch]+c.b2*x2[b][ch]-c.a1*y1[b][ch]-c.a2*y2[b][ch]
                x2[b][ch]=x1[b][ch]; x1[b][ch]=sample; y2[b][ch]=y1[b][ch]; y1[b][ch]=y; sample=y
            }
            // PCM16 represents -1.0 exactly; +0.9999695 is also the normal positive maximum.
            // Only values outside that representable range indicate actual clipping.
            if (sample < -1f || sample > 0.9999695f) clipped = true
            val bounded = sample.coerceIn(-1f,0.9999695f)
            if (ch == 0) peakLeft = maxOf(peakLeft, kotlin.math.abs(bounded))
            else if (ch == 1) peakRight = maxOf(peakRight, kotlin.math.abs(bounded))
            out.putShort((bounded*32768f).toInt().toShort())
        }
        input.position(input.limit()); out.flip()
        if (channels == 1) peakRight = peakLeft
        onLevel?.invoke(peakLeft, peakRight)
        if (clipped) {
            clippingFrames++
            if (clippingFrames == 1 || clippingFrames % 20 == 0) onClipping?.invoke()
        } else {
            clippingFrames = 0
        }
    }
}
