package com.aura.hifi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * Static, touch-free front-panel illustration for the AURA modular music center.
 * The display windows are intentionally kept completely black so live player
 * information can be added later without redesigning the chassis.
 */
class AuraMusicCenterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.1f * density
        color = Color.rgb(154, 157, 155)
    }
    private val displayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 0, 0)
    }
    private val displayEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        color = Color.rgb(8, 9, 10)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(177, 157, 122)
        textSize = 7.5f * density
        typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
    }
    private val screwPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(164, 164, 157)
    }
    private val screwLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(74, 76, 76)
        strokeWidth = 1f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val footPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(45, 35, 25)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desiredHeight = (250f * density).toInt()
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val side = 7f * density
        val top = 5f * density
        val bottom = h - 8f * density
        val gap = 4f * density
        val unitCount = 4
        val unitHeight = (bottom - top - gap * (unitCount - 1)) / unitCount
        val left = side
        val right = w - side

        // Small feet give the stack the weight of a real desktop component system.
        canvas.drawRoundRect(
            RectF(left + 19f * density, bottom - 1f * density, right - 19f * density, bottom + 5f * density),
            3f * density, 3f * density, footPaint
        )

        val names = listOf("DIGITAL TUNER", "DIGITAL PLAYER", "PRE AMPLIFIER", "POWER AMPLIFIER")
        for (index in 0 until unitCount) {
            val y = top + index * (unitHeight + gap)
            val rect = RectF(left, y, right, y + unitHeight)
            drawChassis(canvas, rect, names[index], index)
        }
    }

    private fun drawChassis(canvas: Canvas, r: RectF, name: String, index: Int) {
        val radius = 5f * density
        bodyPaint.shader = LinearGradient(
            r.left, r.top, r.left, r.bottom,
            intArrayOf(
                Color.rgb(103, 106, 105),
                Color.rgb(57, 60, 61),
                Color.rgb(31, 34, 36),
                Color.rgb(68, 70, 69)
            ),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(r, radius, radius, bodyPaint)
        bodyPaint.shader = null
        canvas.drawRoundRect(r, radius, radius, edgePaint)

        val screwRadius = 2.2f * density
        val screwInsetX = 7f * density
        val screwInsetY = 6f * density
        val screwPoints = arrayOf(
            r.left + screwInsetX to r.top + screwInsetY,
            r.right - screwInsetX to r.top + screwInsetY,
            r.left + screwInsetX to r.bottom - screwInsetY,
            r.right - screwInsetX to r.bottom - screwInsetY
        )
        screwPoints.forEach { (x, y) ->
            canvas.drawCircle(x, y, screwRadius, screwPaint)
            canvas.drawLine(x - screwRadius * .55f, y, x + screwRadius * .55f, y, screwLinePaint)
        }

        val labelX = r.left + 17f * density
        val labelY = r.centerY() + labelPaint.textSize * .35f
        canvas.drawText("AURA", labelX, labelY, labelPaint)
        val smallNamePaint = Paint(labelPaint).apply {
            textSize = 5.8f * density
            color = Color.rgb(139, 140, 137)
        }
        canvas.drawText(name, labelX, labelY + 8f * density, smallNamePaint)

        val displayTop = r.top + r.height() * .19f
        val displayBottom = r.bottom - r.height() * .19f
        val displayHeight = displayBottom - displayTop
        val bezel = 3f * density
        val displayRadius = 3f * density

        fun display(x1: Float, x2: Float, y1: Float = displayTop, y2: Float = displayBottom) {
            val outer = RectF(x1, y1, x2, y2)
            val inner = RectF(outer.left + bezel, outer.top + bezel, outer.right - bezel, outer.bottom - bezel)
            val bezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(12, 13, 14) }
            canvas.drawRoundRect(outer, displayRadius, displayRadius, bezelPaint)
            canvas.drawRoundRect(inner, displayRadius * .55f, displayRadius * .55f, displayPaint)
            canvas.drawRoundRect(outer, displayRadius, displayRadius, displayEdgePaint)
        }

        val displayLeft = r.left + r.width() * .36f
        val displayRight = r.right - r.width() * .07f
        when (index) {
            0 -> display(displayLeft, displayRight)
            1 -> display(r.left + r.width() * .29f, r.right - r.width() * .07f,
                r.top + r.height() * .16f, r.bottom - r.height() * .16f)
            2 -> display(r.left + r.width() * .39f, r.right - r.width() * .07f,
                r.top + r.height() * .24f, r.bottom - r.height() * .24f)
            else -> {
                val centerGap = 5f * density
                val pairLeft = r.left + r.width() * .35f
                val pairRight = r.right - r.width() * .07f
                val half = (pairRight - pairLeft - centerGap) / 2f
                display(pairLeft, pairLeft + half)
                display(pairLeft + half + centerGap, pairRight)
            }
        }
    }
}
