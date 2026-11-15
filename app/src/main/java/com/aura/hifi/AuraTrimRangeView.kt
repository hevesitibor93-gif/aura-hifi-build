package com.aura.hifi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/** Two-handle timeline selector. Values are fractions in the source duration. */
class AuraTrimRangeView(context: Context) : View(context) {
    var onRangeChanged: ((Float, Float) -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private var start = 0f
    private var end = 1f
    private var activeHandle = 0
    private val side get() = 18f * density
    private val trackY get() = height * 0.5f
    fun setRange(a: Float, b: Float) { start = a.coerceIn(0f, 1f); end = b.coerceIn(start, 1f); invalidate() }
    private fun xFor(v: Float) = side + v * (width - side * 2f)
    private fun valueFor(x: Float) = ((x - side) / (width - side * 2f).coerceAtLeast(1f)).coerceIn(0f, 1f)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val y = trackY; val left = side; val right = width - side
        paint.style = Paint.Style.FILL; paint.color = 0xff343941.toInt()
        canvas.drawRoundRect(RectF(left, y - 5f*density, right, y + 5f*density), 5f*density, 5f*density, paint)
        paint.color = 0xffffbd55.toInt()
        canvas.drawRoundRect(RectF(xFor(start), y - 5f*density, xFor(end), y + 5f*density), 5f*density, 5f*density, paint)
        for (x in floatArrayOf(xFor(start), xFor(end))) { paint.color = 0xffffd58b.toInt(); canvas.drawCircle(x,y,10f*density,paint); paint.color = 0xff3b2a12.toInt(); canvas.drawCircle(x,y,4f*density,paint) }
        paint.color = 0xffc5c9d0.toInt(); paint.textSize = 10f*density; canvas.drawText("KEZDÉS",left,13f*density,paint)
        paint.textAlign = Paint.Align.RIGHT; canvas.drawText("VÉGE",right,13f*density,paint); paint.textAlign = Paint.Align.LEFT
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when(event.action) {
            MotionEvent.ACTION_DOWN -> { activeHandle = if (abs(event.x-xFor(start)) <= abs(event.x-xFor(end))) 0 else 1; parent?.requestDisallowInterceptTouchEvent(true); update(event.x); return true }
            MotionEvent.ACTION_MOVE -> { update(event.x); return true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { update(event.x); parent?.requestDisallowInterceptTouchEvent(false); performClick(); return true }
        }
        return true
    }
    private fun update(x: Float) { val v=valueFor(x); if(activeHandle==0) start=v.coerceAtMost(end) else end=v.coerceAtLeast(start); invalidate(); onRangeChanged?.invoke(start,end) }
    override fun performClick(): Boolean { super.performClick(); return true }
}
