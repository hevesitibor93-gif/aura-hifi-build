package com.aura.hifi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Lightweight peak-envelope preview for the AURA Audio Lab WAV editor. */
class AuraWaveformView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var peaks = FloatArray(0)
    private val density = resources.displayMetrics.density

    fun setWaveform(values: FloatArray) {
        peaks = values
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        paint.style = Paint.Style.FILL
        paint.color = 0xff111720.toInt()
        canvas.drawRoundRect(RectF(0f, 0f, w, h), 10f * density, 10f * density, paint)
        paint.color = 0xff51432b.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        canvas.drawRoundRect(RectF(density, density, w - density, h - density), 10f * density, 10f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = 0xff66563b.toInt()
        canvas.drawLine(8f * density, h / 2f, w - 8f * density, h / 2f, paint)
        if (peaks.isEmpty()) {
            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 12f * density
            paint.color = 0xffa9a9a9.toInt()
            canvas.drawText("Hullámforma betöltése…", w / 2f, h / 2f + 4f * density, paint)
            return
        }
        val usable = (w - 20f * density).coerceAtLeast(1f)
        val step = usable / peaks.size
        paint.color = 0xffffbd55.toInt()
        paint.strokeWidth = (step * 0.62f).coerceAtLeast(1f)
        for (i in peaks.indices) {
            val x = 10f * density + (i + 0.5f) * step
            val amplitude = (peaks[i].coerceIn(0f, 1f) * h * 0.43f).coerceAtLeast(1f * density)
            canvas.drawLine(x, h / 2f - amplitude, x, h / 2f + amplitude, paint)
        }
        paint.style = Paint.Style.FILL
    }
}
