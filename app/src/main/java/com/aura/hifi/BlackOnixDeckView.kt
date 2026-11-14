package com.aura.hifi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Landscape BLACK-ONIX skin surface.
 * Coordinates follow the original 899 x 401 AIMP skin canvas.
 * The playback engine remains in MainActivity; this view only renders the skin
 * and forwards transport actions.
 */
class BlackOnixDeckView(
    context: Context,
    private val onPrevious: () -> Unit,
    private val onPlayPause: () -> Unit,
    private val onNext: () -> Unit,
    private val onStop: () -> Unit,
    private val onShuffle: () -> Unit,
    private val onRepeat: () -> Unit,
    private val onOpen: () -> Unit,
    private val onSleep: () -> Unit
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bitmaps = HashMap<Int, Bitmap?>()
    private var title = "AURA Hi-Fi"
    private var elapsed = "00:00"
    private var remaining = "-00:00"
    private var playing = false
    private var shuffle = false
    private var repeat = false
    private var trackCounter = "0 DAL HÁTRA"
    private var vuMode = 0
    private var leftLevel = 0.08f
    private var rightLevel = 0.08f
    private var fft = ByteArray(0)
    private var scroll = 0f
    private var lastFrame = 0L
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(225, 221, 205)
        textSize = 11f
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
    }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(222, 190, 137)
        textSize = 9f
        typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD)
    }
    private val spectrumPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hitTargets = ArrayList<Pair<RectF, () -> Unit>>()

    private fun bitmap(id: Int): Bitmap? {
        if (!bitmaps.containsKey(id)) {
            bitmaps[id] = try { BitmapFactory.decodeResource(resources, id) } catch (_: Exception) { null }
        }
        return bitmaps[id]
    }

    private fun draw(id: Int, canvas: Canvas, x: Float, y: Float, w: Float? = null, h: Float? = null) {
        val b = bitmap(id) ?: return
        val dst = RectF(x, y, x + (w ?: b.width.toFloat()), y + (h ?: b.height.toFloat()))
        canvas.drawBitmap(b, null, dst, paint)
    }

    private fun sprite(id: Int, canvas: Canvas, x: Float, y: Float, w: Float, h: Float, frame: Int = 0, frames: Int = 2) {
        val b = bitmap(id) ?: return
        val fh = b.height / frames
        if (fh <= 0) return
        val index = frame.coerceIn(0, frames - 1)
        canvas.drawBitmap(b, Rect(0, index * fh, b.width, (index + 1) * fh), RectF(x, y, x + w, y + h), paint)
    }

    fun setTrack(title: String, elapsed: String, remaining: String) {
        this.title = title.ifBlank { "AURA Hi-Fi" }
        this.elapsed = elapsed
        this.remaining = remaining
        invalidate()
    }

    fun setTrackCounter(value: String) { trackCounter = value; invalidate() }

    fun setPlaybackState(isPlaying: Boolean, shuffleEnabled: Boolean, repeatEnabled: Boolean) {
        playing = isPlaying
        shuffle = shuffleEnabled
        repeat = repeatEnabled
        invalidate()
    }

    fun setFft(data: ByteArray?) {
        fft = data?.copyOf() ?: ByteArray(0)
        var l = 0f
        var r = 0f
        if (fft.size > 8) {
            val half = fft.size / 2
            for (i in 2 until min(half, 48)) {
                val re = fft[i * 2].toInt()
                val im = fft[i * 2 + 1].toInt()
                val mag = (kotlin.math.sqrt((re * re + im * im).toDouble()).toFloat() / 180f).coerceIn(0f, 1f)
                if (i < 24) l = max(l, mag) else r = max(r, mag)
            }
        }
        leftLevel = (leftLevel * .55f + l * .45f).coerceIn(.02f, 1f)
        rightLevel = (rightLevel * .55f + r * .45f).coerceIn(.02f, 1f)
        postInvalidateOnAnimation()
    }

    fun setVuMode(mode: Int) {
        vuMode = mode.coerceIn(0, 2)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = min(width / 899f, height / 401f)
        val ox = (width - 899f * scale) / 2f
        val oy = (height - 401f * scale) / 2f
        canvas.save()
        canvas.translate(ox, oy)
        canvas.scale(scale, scale)
        canvas.drawColor(Color.rgb(2, 4, 7))
        paint.color = Color.WHITE

        // Outer frame and the original main screen composition.
        draw(R.drawable.onix_base_top_texture, canvas, 0f, 0f)
        draw(R.drawable.onix_base_left_texture, canvas, 0f, 57f)
        draw(R.drawable.onix_base_right_texture, canvas, 848f, 57f)
        draw(R.drawable.onix_base_bottom_texture, canvas, 0f, 380f)
        draw(R.drawable.onix_screen_main_texture, canvas, 113f, 57f)
        draw(R.drawable.onix_container_main_top_texture, canvas, 113f, 57f)
        draw(R.drawable.onix_container_main_left_texture, canvas, 113f, 127f)
        draw(R.drawable.onix_container_main_center_texture, canvas, 279f, 127f)
        draw(R.drawable.onix_container_main_right_texture, canvas, 620f, 127f)
        draw(R.drawable.onix_bottom_gw_texture, canvas, 113f, 261f)
        draw(R.drawable.onix_panel_bottom_left_texture, canvas, 0f, 323f)
        draw(R.drawable.onix_panel_bottom_center_main_texture, canvas, 117f, 323f)
        draw(R.drawable.onix_panel_bottom_right_texture, canvas, 782f, 323f)
        draw(R.drawable.onix_vuppm_mode1_skin, canvas, 70f, 121f, 27f, 27f)
        draw(R.drawable.onix_vuppm_mode2_skin, canvas, 97f, 121f, 27f, 27f)
        draw(R.drawable.onix_vuppm_mode3_skin, canvas, 124f, 121f, 27f, 27f)

        // Original scrolling-title strip and the two time readouts.
        draw(R.drawable.onix_running_line_skin, canvas, 127f, 79f, 645f, 24f)
        canvas.save()
        canvas.clipRect(129f, 81f, 770f, 101f)
        val textWidth = textPaint.measureText(title)
        if (textWidth > 630f) {
            if (playing) {
                val now = SystemClock.uptimeMillis()
                if (lastFrame != 0L) scroll += ((now - lastFrame).coerceAtMost(80L) * .035f)
                if (scroll > textWidth + 60f) scroll = 0f
                lastFrame = now
                postInvalidateDelayed(40L)
            }
            canvas.drawText(title, 135f - scroll, 96f, textPaint)
            canvas.drawText(title, 135f - scroll + textWidth + 65f, 96f, textPaint)
        } else {
            canvas.drawText(title, 135f, 96f, textPaint)
        }
        canvas.restore()
        canvas.drawText(remaining, 149f, 119f, smallPaint)
        canvas.drawText(elapsed, 688f, 119f, smallPaint)
        canvas.drawText(trackCounter, 12f, 365f, smallPaint)

        // Original left/right dual VU strips. The level changes the active frame.
        val lf = if (leftLevel > .62f) 1 else 0
        val rf = if (rightLevel > .62f) 1 else 0
        sprite(R.drawable.onix_vu_left_l_skin, canvas, 263f, 159f, 5f, 82f, lf)
        sprite(R.drawable.onix_vu_left_r_skin, canvas, 269f, 159f, 5f, 82f, lf)
        sprite(R.drawable.onix_vu_right_l_skin, canvas, 625f, 159f, 5f, 82f, rf)
        sprite(R.drawable.onix_vu_right_r_skin, canvas, 631f, 159f, 5f, 82f, rf)

        // Spectrum area: FFT-driven bars in the original A1 spectrum window.
        draw(R.drawable.onix_spec_mode_a1_texture, canvas, 285f, 175f, 330f, 80f)
        val bars = 30
        val gap = 2.2f
        val bw = (326f - gap * (bars - 1)) / bars
        for (i in 0 until bars) {
            val rawIndex = 2 + i * 2
            val value = if (rawIndex + 1 < fft.size) {
                val re = fft[rawIndex].toInt()
                val im = fft[rawIndex + 1].toInt()
                (kotlin.math.sqrt((re * re + im * im).toDouble()).toFloat() / 145f).coerceIn(0f, 1f)
            } else 0.025f
            val bh = max(2f, value * 66f)
            val x = 287f + i * (bw + gap)
            spectrumPaint.color = if (value > .78f) Color.rgb(239, 67, 48) else if (value > .48f) Color.rgb(245, 174, 63) else Color.rgb(195, 202, 174)
            canvas.drawRoundRect(RectF(x, 250f - bh, x + bw, 250f), 1.2f, 1.2f, spectrumPaint)
        }

        // The four independent red/amber side-meter sprite sets (three selectable modes).
        val prefixVu = when (vuMode) {
            1 -> arrayOf(R.drawable.onix_vu_mode2_top_skin, R.drawable.onix_vu_mode2_bottom_skin, R.drawable.onix_vu_mode2_add_top_skin, R.drawable.onix_vu_mode2_add_bottom_skin)
            2 -> arrayOf(R.drawable.onix_vu_mode3_top_skin, R.drawable.onix_vu_mode3_bottom_skin, R.drawable.onix_vu_mode3_add_top_skin, R.drawable.onix_vu_mode3_add_bottom_skin)
            else -> arrayOf(R.drawable.onix_vu_mode1_top_skin, R.drawable.onix_vu_mode1_bottom_skin, R.drawable.onix_vu_mode1_add_top_skin, R.drawable.onix_vu_mode1_add_bottom_skin)
        }
        val frameL = (leftLevel * 35f).toInt().coerceIn(0, 35)
        val frameR = (rightLevel * 35f).toInt().coerceIn(0, 35)
        sprite(prefixVu[0], canvas, 327f, 131f, 245f, 6f, frameL, 36)
        sprite(prefixVu[2], canvas, 327f, 139f, 245f, 6f, frameL, 36)
        sprite(prefixVu[1], canvas, 327f, 146f, 245f, 6f, frameR, 36)
        sprite(prefixVu[3], canvas, 327f, 154f, 245f, 6f, frameR, 36)

        // Transport controls keep the source skin's sprite artwork.
        sprite(R.drawable.black_onix_prev_normal, canvas, 123f, 328f, 73f, 49f, 0, 1)
        sprite(if (playing) R.drawable.black_onix_pause_normal else R.drawable.black_onix_play_normal, canvas, 196f, 328f, 73f, 49f, 0, 1)
        sprite(R.drawable.black_onix_next_normal, canvas, 269f, 328f, 73f, 49f, 0, 1)
        sprite(R.drawable.onix_button_stop_skin, canvas, 342f, 328f, 73f, 49f)
        sprite(R.drawable.onix_button_open_skin, canvas, 515f, 339f, 37f, 27f)
        sprite(R.drawable.onix_button_sleep_skin, canvas, 551f, 339f, 37f, 27f)
        sprite(R.drawable.onix_button_repeat_skin, canvas, 659f, 339f, 37f, 27f)
        sprite(R.drawable.onix_button_shuffle_skin, canvas, 695f, 339f, 37f, 27f)
        canvas.restore()
        buildHitTargets()
    }

    private fun buildHitTargets() {
        hitTargets.clear()
        hitTargets += RectF(123f, 328f, 196f, 377f) to onPrevious
        hitTargets += RectF(196f, 328f, 269f, 377f) to onPlayPause
        hitTargets += RectF(269f, 328f, 342f, 377f) to onNext
        hitTargets += RectF(342f, 328f, 415f, 377f) to onStop
        hitTargets += RectF(515f, 339f, 552f, 366f) to onOpen
        hitTargets += RectF(551f, 339f, 588f, 366f) to onSleep
        hitTargets += RectF(659f, 339f, 696f, 366f) to onRepeat
        hitTargets += RectF(695f, 339f, 732f, 366f) to onShuffle
        hitTargets += RectF(70f, 121f, 97f, 148f) to { setVuMode(0) }
        hitTargets += RectF(97f, 121f, 124f, 148f) to { setVuMode(1) }
        hitTargets += RectF(124f, 121f, 151f, 148f) to { setVuMode(2) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val scale = min(width / 899f, height / 401f)
        val ox = (width - 899f * scale) / 2f
        val oy = (height - 401f * scale) / 2f
        val x = (event.x - ox) / scale
        val y = (event.y - oy) / scale
        hitTargets.firstOrNull { it.first.contains(x, y) }?.second?.invoke()
        performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
