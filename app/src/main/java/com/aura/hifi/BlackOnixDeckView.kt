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
    private val onRepeatLong: () -> Unit,
    private val onOpen: () -> Unit,
    private val onSleep: () -> Unit,
    private val onPlaylist: () -> Unit,
    private val onEqualizer: () -> Unit,
    private val onUtilities: () -> Unit,
    private val onRadio: () -> Unit,
    private val onSettings: () -> Unit,
    private val onMainMenu: () -> Unit,
    private val onMinimize: () -> Unit,
    private val onExit: () -> Unit,
    private val onVuModeChanged: (Int) -> Unit,
    private val onSpectrumModeChanged: (Int) -> Unit
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bitmaps = HashMap<Int, Bitmap?>()
    private var title = "AURA Hi-Fi"
    private var elapsed = "00:00"
    private var remaining = "-00:00"
    private var playing = false
    private var shuffleMode = 0
    private var repeatMode = 0
    private var trackCounter = "0 DAL HÁTRA"
    private var vuMode = 0
    private var spectrumMode = 0
    private var leftLevel = 0.08f
    private var rightLevel = 0.08f
    private var fft = ByteArray(0)
    private var fftActive = false
    private val spectrumLevels = FloatArray(30)
    private var systemVolume = 0
    private var sampleRateText = "-- kHz"
    private var bitrateText = "-- kbps"
    private var systemClock = "--:--"
    private var scroll = 0f
    private var lastFrame = 0L
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(225, 221, 205)
        textSize = 16f
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
    }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(222, 190, 137)
        textSize = 9f
        typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD)
    }
    private val spectrumPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hitTargets = ArrayList<Pair<RectF, () -> Unit>>()
    private var touchDownTime = 0L

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

    private fun spriteHorizontal(id: Int, canvas: Canvas, x: Float, y: Float, w: Float, h: Float, frame: Int, frames: Int) {
        val b = bitmap(id) ?: return
        val fw = b.width / frames
        if (fw <= 0) return
        val index = frame.coerceIn(0, frames - 1)
        canvas.drawBitmap(b, Rect(index * fw, 0, (index + 1) * fw, b.height), RectF(x, y, x + w, y + h), paint)
    }

    fun setTrack(title: String, elapsed: String, remaining: String) {
        this.title = title.ifBlank { "AURA Hi-Fi" }
        this.elapsed = elapsed
        this.remaining = remaining
        invalidate()
    }

    fun setTrackCounter(value: String) { trackCounter = value; invalidate() }

    fun setSystemInfo(volumePercent: Int, sampleRate: String, bitrate: String, clock: String) {
        systemVolume = volumePercent.coerceIn(0, 100)
        sampleRateText = sampleRate
        bitrateText = bitrate
        systemClock = clock
        invalidate()
    }

    fun setPlaybackState(isPlaying: Boolean, shuffleState: Int, repeatState: Int) {
        playing = isPlaying
        shuffleMode = shuffleState.coerceIn(0, 2)
        repeatMode = repeatState.coerceIn(0, 3)
        invalidate()
    }

    fun setFft(data: ByteArray?) {
        fft = data?.copyOf() ?: ByteArray(0)
        if (fft.size > 8) {
            fftActive = true
            val bins = fft.size / 2
            for (i in 0 until 30) {
                val startBin = Math.pow(220.0, i / 29.0).toInt().coerceAtLeast(1)
                val endBin = Math.pow(220.0, (i + 1) / 29.0).toInt().coerceAtLeast(startBin + 1)
                var peak = 0f
                for (bin in startBin until min(endBin, bins)) {
                    val offset = bin * 2
                    if (offset + 1 >= fft.size) break
                    val re = fft[offset].toInt()
                    val im = fft[offset + 1].toInt()
                    peak = max(peak, kotlin.math.sqrt((re * re + im * im).toDouble()).toFloat())
                }
                val target = kotlin.math.sqrt((peak / 105f).coerceIn(0f, 1f))
                spectrumLevels[i] = if (target > spectrumLevels[i]) spectrumLevels[i] * .24f + target * .76f
                    else spectrumLevels[i] * .78f + target * .22f
            }
        } else {
            fftActive = false
            for (i in spectrumLevels.indices) spectrumLevels[i] *= .82f
        }
        postInvalidateOnAnimation()
    }

    fun setLevels(left: Float, right: Float) {
        val l = left.coerceIn(0f, 1f)
        val r = right.coerceIn(0f, 1f)
        leftLevel = if (l >= leftLevel) leftLevel * .28f + l * .72f else leftLevel * .78f + l * .22f
        rightLevel = if (r >= rightLevel) rightLevel * .28f + r * .72f else rightLevel * .78f + r * .22f
        if (!fftActive) {
            val signal = max(l, r)
            for (i in spectrumLevels.indices) {
                val bandShape = .22f + .78f * abs(kotlin.math.sin(i * .47f))
                val target = (signal * bandShape).coerceIn(0f, 1f)
                spectrumLevels[i] = if (target > spectrumLevels[i]) spectrumLevels[i] * .35f + target * .65f
                    else spectrumLevels[i] * .86f + target * .14f
            }
        }
        postInvalidateOnAnimation()
    }

    fun setVuMode(mode: Int) {
        vuMode = mode.coerceIn(0, 2)
        onVuModeChanged(vuMode)
        invalidate()
    }

    fun setSpectrumMode(mode: Int) {
        spectrumMode = mode.coerceIn(0, 2)
        onSpectrumModeChanged(spectrumMode)
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
        draw(R.drawable.onix_base_left_texture, canvas, 0f, 0f)
        draw(R.drawable.onix_base_right_texture, canvas, 848f, 0f)
        draw(R.drawable.onix_base_bottom_texture, canvas, 0f, 380f)
        draw(R.drawable.onix_container_left_top_main_texture, canvas, 0f, 57f)
        draw(R.drawable.onix_container_left_bottom_texture, canvas, 0f, 157f)
        draw(R.drawable.onix_container_right_top_texture, canvas, 786f, 57f)
        draw(R.drawable.onix_container_right_bottom_main_texture, canvas, 786f, 181f)
        draw(R.drawable.onix_screen_main_texture, canvas, 113f, 57f)
        draw(R.drawable.onix_container_main_top_texture, canvas, 113f, 57f)
        draw(R.drawable.onix_container_main_left_texture, canvas, 113f, 127f)
        draw(R.drawable.onix_container_main_center_texture, canvas, 279f, 127f)
        draw(R.drawable.onix_container_main_right_texture, canvas, 620f, 127f)
        draw(R.drawable.onix_bottom_gw_texture, canvas, 113f, 261f)
        draw(R.drawable.onix_panel_bottom_left_texture, canvas, 0f, 323f)
        draw(R.drawable.onix_panel_bottom_center_main_texture, canvas, 117f, 323f)
        draw(R.drawable.onix_panel_bottom_right_texture, canvas, 782f, 323f)
        sprite(R.drawable.onix_vuppm_mode1_skin, canvas, 19f, 121f, 27f, 27f, if (vuMode == 0) 1 else 0, 6)
        sprite(R.drawable.onix_vuppm_mode2_skin, canvas, 46f, 121f, 27f, 27f, if (vuMode == 1) 3 else 2, 6)
        sprite(R.drawable.onix_vuppm_mode3_skin, canvas, 73f, 121f, 27f, 27f, if (vuMode == 2) 5 else 4, 6)
        sprite(R.drawable.onix_s_mode_a_skin, canvas, 799f, 223f, 21f, 25f, if (spectrumMode == 0) 1 else 0, 6)
        sprite(R.drawable.onix_s_mode_b_skin, canvas, 829f, 223f, 21f, 25f, if (spectrumMode == 1) 3 else 2, 6)
        sprite(R.drawable.onix_s_mode_c_skin, canvas, 859f, 223f, 21f, 25f, if (spectrumMode == 2) 5 else 4, 6)

        // Original scrolling-title strip and the two time readouts.
        sprite(R.drawable.onix_running_line_skin, canvas, 127f, 79f, 645f, 24f, 0, 2)
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
        drawTimeString(canvas, R.drawable.onix_time_display_skin, 149f, 110f, remaining)
        drawTimeString(canvas, R.drawable.onix_time_display1_skin, 688f, 110f, elapsed)
        canvas.drawText(trackCounter, 149f, 137f, smallPaint)

        // Original left/right dual VU strips. The level changes the active frame.
        val lf = if (leftLevel > .62f) 1 else 0
        val rf = if (rightLevel > .62f) 1 else 0
        spriteHorizontal(R.drawable.onix_vu_left_l_skin, canvas, 263f, 159f, 5f, 82f, lf, 2)
        spriteHorizontal(R.drawable.onix_vu_left_r_skin, canvas, 269f, 159f, 5f, 82f, lf, 2)
        spriteHorizontal(R.drawable.onix_vu_right_l_skin, canvas, 625f, 159f, 5f, 82f, rf, 2)
        spriteHorizontal(R.drawable.onix_vu_right_r_skin, canvas, 631f, 159f, 5f, 82f, rf, 2)

        // SpectrumA1 is positioned inside SpecModeA1: container origin (279,159),
        // control margins (6,0,5,3), exact 330x80 display area.
        val spectrumBackground = when (spectrumMode) {
            1 -> R.drawable.onix_spec_mode_b1_texture
            2 -> R.drawable.onix_spec_mode_c1_texture
            else -> R.drawable.onix_spec_mode_a1_texture
        }
        draw(spectrumBackground, canvas, 279f, 159f, 341f, 99f)
        for (i in 0 until 30) {
            val value = spectrumLevels[i].coerceIn(0f, 1f)
            val x = 286f + i * 11f
            val t = i / 29f
            val red = if (t < .62f) (45 + t / .62f * 190).toInt() else 235
            val green = if (t < .62f) 205 else (205 - (t - .62f) / .38f * 150).toInt()
            val blue = if (t < .62f) 95 else 45
            spectrumPaint.color = Color.rgb(red.coerceIn(0, 255), green.coerceIn(0, 255), blue)
            when (spectrumMode) {
                1 -> {
                    val barHeight = max(1.5f, value * 34f)
                    canvas.drawRoundRect(RectF(x, 199f - barHeight, x + 8f, 199f + barHeight), 1.5f, 1.5f, spectrumPaint)
                }
                2 -> {
                    val segments = 8
                    val lit = (value * segments).toInt().coerceIn(0, segments)
                    for (segment in 0 until lit) {
                        val top = 239f - (segment + 1) * 9f
                        canvas.drawRoundRect(RectF(x, top, x + 8f, top + 7f), 1f, 1f, spectrumPaint)
                    }
                }
                else -> {
                    val barHeight = max(2f, value * 73f)
                    canvas.drawRoundRect(RectF(x, 239f - barHeight, x + 8f, 239f), 1.5f, 1.5f, spectrumPaint)
                    if (value > .12f) {
                        spectrumPaint.color = Color.argb(150, 245, 220, 160)
                        canvas.drawRect(x, 239f - barHeight, x + 8f, 237f - barHeight, spectrumPaint)
                    }
                }
            }
        }

        // Original 341x32 VU mode background, followed by its animated level sprites.
        val vuTexture = when (vuMode) {
            1 -> R.drawable.onix_vu_mode2_texture
            2 -> R.drawable.onix_vu_mode3_texture
            else -> R.drawable.onix_vu_mode1_texture
        }
        draw(vuTexture, canvas, 279f, 127f, 341f, 32f)

        // The four independent red/amber side-meter sprite sets (three selectable modes).
        val prefixVu = when (vuMode) {
            1 -> arrayOf(R.drawable.onix_vu_mode2_top_skin, R.drawable.onix_vu_mode2_bottom_skin, R.drawable.onix_vu_mode2_add_top_skin, R.drawable.onix_vu_mode2_add_bottom_skin)
            2 -> arrayOf(R.drawable.onix_vu_mode3_top_skin, R.drawable.onix_vu_mode3_bottom_skin, R.drawable.onix_vu_mode3_add_top_skin, R.drawable.onix_vu_mode3_add_bottom_skin)
            else -> arrayOf(R.drawable.onix_vu_mode1_top_skin, R.drawable.onix_vu_mode1_bottom_skin, R.drawable.onix_vu_mode1_add_top_skin, R.drawable.onix_vu_mode1_add_bottom_skin)
        }
        val frameL = (leftLevel * 35f).toInt().coerceIn(0, 35)
        val frameR = (rightLevel * 35f).toInt().coerceIn(0, 35)
        sprite(prefixVu[0], canvas, 327f, 131f, 245f, 6f, frameL, 36)
        sprite(prefixVu[2], canvas, 327f, 131f, 245f, 6f, frameL, 36)
        sprite(prefixVu[1], canvas, 327f, 146f, 245f, 6f, frameR, 36)
        sprite(prefixVu[3], canvas, 327f, 146f, 245f, 6f, frameR, 36)

        // Top bar: original AIMP window controls in BaseTop.
        sprite(R.drawable.onix_button_top_main_menu_skin, canvas, 10f, 27f, 23f, 23f, 0, 4)
        sprite(R.drawable.onix_button_top_options_skin, canvas, 40f, 27f, 23f, 23f, 0, 4)
        sprite(R.drawable.onix_button_top_utilities_skin, canvas, 70f, 27f, 23f, 23f, 0, 4)
        sprite(R.drawable.onix_to_tray_btn_skin, canvas, 805f, 27f, 23f, 23f, 0, 4)
        sprite(R.drawable.onix_close_btn_skin, canvas, 865f, 27f, 23f, 23f, 0, 4)

        // Original action strip sits beside the transport controls in the lower panel.
        sprite(R.drawable.onix_button_open_skin, canvas, 515f, 339f, 37f, 27f, 0, 4)
        sprite(R.drawable.onix_button_sleep_skin, canvas, 551f, 339f, 37f, 27f, 0, 4)
        sprite(R.drawable.onix_button_manager_skin, canvas, 587f, 339f, 37f, 27f, 0, 4)
        sprite(R.drawable.onix_button_library_skin, canvas, 623f, 339f, 37f, 27f, 0, 4)
        sprite(R.drawable.onix_button_repeat_skin, canvas, 659f, 339f, 37f, 27f, when (repeatMode) { 0 -> 0; 1 -> 3; else -> 5 }, 6)
        sprite(R.drawable.onix_button_shuffle_skin, canvas, 695f, 339f, 37f, 27f, when (shuffleMode) { 0 -> 0; 1 -> 3; else -> 5 }, 6)
        sprite(R.drawable.onix_button_radio_skin, canvas, 731f, 339f, 37f, 27f, 0, 6)

        // Live phone/system information in the original right-hand display field.
        smallPaint.color = Color.rgb(222, 190, 137)
        smallPaint.textSize = 10f
        canvas.drawText(systemClock, 684f, 145f, smallPaint)
        canvas.drawText("$systemVolume%", 163f, 171f, smallPaint)
        canvas.drawText(sampleRateText, 149f, 222f, smallPaint)
        canvas.drawText(bitrateText, 140f, 242f, smallPaint)
        // Original AIMP screen tabs on the left bottom panel.
        sprite(R.drawable.onix_pl_scr_a_skin, canvas, 26f, 341f, 44f, 19f, 0, 4)
        sprite(R.drawable.onix_eq_scr_a_skin, canvas, 70f, 341f, 44f, 19f, 0, 4)

        // Original five-button transport row in its source order.
        sprite(R.drawable.black_onix_prev_normal, canvas, 123f, 328f, 73f, 49f, 0, 1)
        sprite(R.drawable.onix_button_stop_skin, canvas, 196f, 328f, 73f, 49f, 0, 8)
        if (playing) {
            sprite(R.drawable.black_onix_pause_normal, canvas, 342f, 328f, 73f, 49f, 0, 1)
        } else {
            sprite(R.drawable.black_onix_play_normal, canvas, 269f, 328f, 73f, 49f, 0, 1)
        }
        sprite(R.drawable.black_onix_next_normal, canvas, 415f, 328f, 73f, 49f, 0, 1)
        canvas.restore()
        buildHitTargets()
    }


    private fun drawTimeString(canvas: Canvas, resource: Int, x: Float, y: Float, value: String) {
        val sheet = bitmap(resource) ?: return
        val cellWidth = sheet.width / 13
        val cellHeight = sheet.height
        value.take(8).forEachIndexed { index, char ->
            val frame = when (char) {
                in '0'..'9' -> char - '0'
                ':' -> 10
                '-' -> 11
                else -> 12
            }
            val left = frame * cellWidth
            canvas.drawBitmap(sheet, Rect(left, 0, left + cellWidth, cellHeight),
                RectF(x + index * cellWidth, y, x + (index + 1) * cellWidth, y + cellHeight), paint)
        }
    }

    private fun buildHitTargets() {
        hitTargets.clear()
        hitTargets += RectF(123f, 328f, 196f, 377f) to onPrevious
        hitTargets += RectF(196f, 328f, 269f, 377f) to onStop
        if (playing) hitTargets += RectF(342f, 328f, 415f, 377f) to onPlayPause
        else hitTargets += RectF(269f, 328f, 342f, 377f) to onPlayPause
        hitTargets += RectF(415f, 328f, 488f, 377f) to onNext
        hitTargets += RectF(515f, 339f, 552f, 366f) to onOpen
        hitTargets += RectF(551f, 339f, 588f, 366f) to onSleep
        hitTargets += RectF(587f, 339f, 624f, 366f) to onUtilities
        hitTargets += RectF(623f, 339f, 660f, 366f) to onMainMenu
        hitTargets += RectF(659f, 339f, 696f, 366f) to onRepeat
        hitTargets += RectF(695f, 339f, 732f, 366f) to onShuffle
        hitTargets += RectF(731f, 339f, 768f, 366f) to onRadio
        hitTargets += RectF(26f, 341f, 70f, 360f) to onPlaylist
        hitTargets += RectF(70f, 341f, 114f, 360f) to onEqualizer
        hitTargets += RectF(10f, 27f, 33f, 50f) to onMainMenu
        hitTargets += RectF(40f, 27f, 63f, 50f) to onSettings
        hitTargets += RectF(70f, 27f, 93f, 50f) to onUtilities
        hitTargets += RectF(805f, 27f, 828f, 50f) to onMinimize
        hitTargets += RectF(865f, 27f, 888f, 50f) to onExit
        hitTargets += RectF(19f, 121f, 46f, 148f) to { setVuMode(0) }
        hitTargets += RectF(46f, 121f, 73f, 148f) to { setVuMode(1) }
        hitTargets += RectF(73f, 121f, 100f, 148f) to { setVuMode(2) }
        hitTargets += RectF(799f, 223f, 820f, 248f) to { setSpectrumMode(0) }
        hitTargets += RectF(829f, 223f, 850f, 248f) to { setSpectrumMode(1) }
        hitTargets += RectF(859f, 223f, 880f, 248f) to { setSpectrumMode(2) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            touchDownTime = event.eventTime
            return true
        }
        if (event.action == MotionEvent.ACTION_CANCEL) return true
        if (event.action != MotionEvent.ACTION_UP) return true
        val scale = min(width / 899f, height / 401f)
        val ox = (width - 899f * scale) / 2f
        val oy = (height - 401f * scale) / 2f
        val x = (event.x - ox) / scale
        val y = (event.y - oy) / scale
        val held = event.eventTime - touchDownTime
        if (RectF(659f, 339f, 696f, 366f).contains(x, y) && held >= 500L) {
            onRepeatLong()
        } else {
            hitTargets.firstOrNull { it.first.contains(x, y) }?.second?.invoke()
        }
        performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
