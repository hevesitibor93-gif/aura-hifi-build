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
import kotlin.math.max
import kotlin.math.min

/** Portrait BLACK-ONIX layout. Uses the same playback callbacks and source textures as the landscape deck. */
class BlackOnixPortraitDeckView(
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
    private val onSpectrumModeChanged: (Int) -> Unit,
    private val onSeekTo: (Long) -> Unit,
    private val onMute: () -> Unit
) : View(context) {
    private val bitmaps = HashMap<Int, Bitmap?>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(232, 218, 190)
        textSize = 16f
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(213, 178, 119)
        textSize = 11f
        typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD)
    }
    private val meterPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hitTargets = ArrayList<Pair<RectF, () -> Unit>>()
    private var scaleNow = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var title = "AURA Hi-Fi"
    private var elapsed = "00:00"
    private var remaining = "-00:00"
    private var trackCounter = "0 DAL HÁTRA"
    private var playing = false
    private var shuffleMode = 0
    private var repeatMode = 0
    private var vuMode = 0
    private var spectrumMode = 0
    private var muted = false
    private var leftLevel = .08f
    private var rightLevel = .08f
    private var spectrum = FloatArray(30)
    private var fftActive = false
    private var durationMs = 0L
    private var positionMs = 0L
    private var previewMs = 0L
    private var draggingSeek = false
    private var systemVolume = 0
    private var sampleRate = "-- kHz"
    private var bitrate = "-- kbps"
    private var clock = "--:--:--"
    private var lastFrame = 0L
    private var scroll = 0f

    private fun bmp(id: Int): Bitmap? {
        if (!bitmaps.containsKey(id)) bitmaps[id] = try { BitmapFactory.decodeResource(resources, id) } catch (_: Exception) { null }
        return bitmaps[id]
    }
    private fun image(c: Canvas, id: Int, x: Float, y: Float, w: Float, h: Float) {
        val b = bmp(id) ?: return
        c.drawBitmap(b, null, RectF(x, y, x + w, y + h), paint)
    }
    private fun sprite(c: Canvas, id: Int, x: Float, y: Float, w: Float, h: Float, frame: Int, frames: Int) {
        val b = bmp(id) ?: return
        val fh = b.height / frames
        if (fh <= 0) return
        val f = frame.coerceIn(0, frames - 1)
        c.drawBitmap(b, Rect(0, f * fh, b.width, (f + 1) * fh), RectF(x, y, x + w, y + h), paint)
    }
    private fun panel(c: Canvas, r: RectF, fill: Int = Color.rgb(9, 12, 17)) {
        meterPaint.color = fill
        c.drawRoundRect(r, 12f, 12f, meterPaint)
        meterPaint.color = Color.argb(170, 157, 119, 66)
        meterPaint.style = Paint.Style.STROKE
        meterPaint.strokeWidth = 1.2f
        c.drawRoundRect(r, 12f, 12f, meterPaint)
        meterPaint.style = Paint.Style.FILL
    }
    private fun label(c: Canvas, value: String, x: Float, y: Float, size: Float = 13f, color: Int = Color.rgb(213, 178, 119)) {
        small.color = color
        small.textSize = size
        c.drawText(value, x, y, small)
    }
    private fun target(r: RectF, action: () -> Unit) { hitTargets.add(r to action) }

    fun setTrack(value: String, elapsedText: String, remainingText: String) {
        title = value.ifBlank { "AURA Hi-Fi" }; elapsed = elapsedText; remaining = remainingText; invalidate()
    }
    fun setTrackCounter(value: String) { trackCounter = value; invalidate() }
    fun setTrackProgress(position: Long, duration: Long) {
        durationMs = duration.coerceAtLeast(0L)
        positionMs = position.coerceIn(0L, durationMs)
        if (!draggingSeek) previewMs = positionMs
        invalidate()
    }
    fun setSystemInfo(volume: Int, rate: String, bit: String, time: String) {
        systemVolume = volume.coerceIn(0, 100); sampleRate = rate; bitrate = bit; clock = time; invalidate()
    }
    fun setPlaybackState(isPlaying: Boolean, shuffle: Int, repeat: Int) {
        playing = isPlaying; shuffleMode = shuffle.coerceIn(0, 2); repeatMode = repeat.coerceIn(0, 3); invalidate()
    }
    fun setMuted(value: Boolean) { muted = value; invalidate() }
    fun setVuMode(value: Int) { vuMode = value.coerceIn(0, 3); onVuModeChanged(vuMode); invalidate() }
    fun setSpectrumMode(value: Int) { spectrumMode = value.coerceIn(0, 2); onSpectrumModeChanged(spectrumMode); invalidate() }
    fun setLevels(left: Float, right: Float) {
        val l = left.coerceIn(0f, 1f); val r = right.coerceIn(0f, 1f)
        leftLevel = if (l > leftLevel) leftLevel * .28f + l * .72f else leftLevel * .78f + l * .22f
        rightLevel = if (r > rightLevel) rightLevel * .28f + r * .72f else rightLevel * .78f + r * .22f
        if (!fftActive) for (i in spectrum.indices) {
            val v = max(l, r) * (.25f + .75f * kotlin.math.abs(kotlin.math.sin(i * .47f)))
            spectrum[i] = if (v > spectrum[i]) spectrum[i] * .35f + v * .65f else spectrum[i] * .86f + v * .14f
        }
        postInvalidateOnAnimation()
    }
    fun setFft(data: ByteArray?) {
        if (data == null || data.size < 10) {
            fftActive = false
            for (i in spectrum.indices) spectrum[i] *= .82f
        } else {
            fftActive = true
            val bins = data.size / 2
            for (i in spectrum.indices) {
                val start = (1 + i * (bins - 2) / spectrum.size).coerceAtLeast(1)
                val end = (start + max(1, bins / spectrum.size)).coerceAtMost(bins)
                var peak = 0f
                for (b in start until end) {
                    val re = data[b * 2].toInt(); val im = data[b * 2 + 1].toInt()
                    peak = max(peak, kotlin.math.sqrt((re * re + im * im).toDouble()).toFloat())
                }
                spectrum[i] = (peak / 105f).coerceIn(0f, 1f).let { kotlin.math.sqrt(it) }
            }
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = min(width / 420f, height / 740f).coerceAtLeast(.1f)
        scaleNow = s
        offsetX = (width - 420f * s) / 2f
        offsetY = (height - 740f * s) / 2f
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(s, s)
        canvas.drawColor(Color.rgb(2, 4, 7))
        hitTargets.clear()

        // Outer BLACK-ONIX frame and branding.
        panel(canvas, RectF(8f, 8f, 412f, 732f), Color.rgb(5, 8, 12))
        panel(canvas, RectF(16f, 16f, 404f, 68f), Color.rgb(13, 17, 23))
        label(canvas, "AURA", 164f, 49f, 23f, Color.rgb(232, 195, 132))
        label(canvas, clock, 25f, 47f, 10f)
        sprite(canvas, R.drawable.onix_button_top_main_menu_skin, 22f, 83f, 34f, 34f, 0, 4)
        sprite(canvas, R.drawable.onix_button_top_options_skin, 64f, 83f, 34f, 34f, 0, 4)
        sprite(canvas, R.drawable.onix_button_top_utilities_skin, 106f, 83f, 34f, 34f, 0, 4)
        sprite(canvas, R.drawable.onix_to_tray_btn_skin, 352f, 83f, 28f, 28f, 0, 4)
        sprite(canvas, R.drawable.onix_close_btn_skin, 386f, 83f, 28f, 28f, 0, 4)
        target(RectF(15f, 75f, 60f, 125f), onMainMenu)
        target(RectF(60f, 75f, 102f, 125f), onSettings)
        target(RectF(102f, 75f, 148f, 125f), onUtilities)
        target(RectF(345f, 75f, 385f, 125f), onMinimize)
        target(RectF(380f, 75f, 420f, 125f), onExit)

        // Track title and metadata.
        panel(canvas, RectF(20f, 137f, 400f, 220f))
        image(canvas, R.drawable.onix_running_line_skin, 29f, 148f, 362f, 34f)
        canvas.save()
        canvas.clipRect(37f, 151f, 383f, 178f)
        text.textSize = 17f
        val tw = text.measureText(title)
        if (tw > 335f && playing) {
            val now = SystemClock.uptimeMillis()
            if (lastFrame != 0L) scroll += ((now - lastFrame).coerceAtMost(80L) * .035f)
            if (scroll > tw + 30f) scroll = 0f
            lastFrame = now
            postInvalidateDelayed(40L)
        }
        canvas.drawText(title, 39f - scroll, 173f, text)
        canvas.restore()
        label(canvas, trackCounter, 30f, 205f, 10f)
        label(canvas, "$elapsed   /   $remaining", 258f, 205f, 11f)

        // Two-row stereo VU section.
        panel(canvas, RectF(20f, 232f, 400f, 326f))
        label(canvas, "STEREO VU / PPM", 31f, 252f, 11f)
        val meterLeft = 56f
        val meterWidth = 320f
        if (vuMode != 3) {
            val vuBg = when (vuMode) { 1 -> R.drawable.onix_vu_mode2_texture; 2 -> R.drawable.onix_vu_mode3_texture; else -> R.drawable.onix_vu_mode1_texture }
            image(canvas, vuBg, meterLeft, 258f, meterWidth, 58f)
            val frames = when (vuMode) {
                1 -> arrayOf(R.drawable.onix_vu_mode2_top_skin, R.drawable.onix_vu_mode2_bottom_skin, R.drawable.onix_vu_mode2_add_top_skin, R.drawable.onix_vu_mode2_add_bottom_skin)
                2 -> arrayOf(R.drawable.onix_vu_mode3_top_skin, R.drawable.onix_vu_mode3_bottom_skin, R.drawable.onix_vu_mode3_add_top_skin, R.drawable.onix_vu_mode3_add_bottom_skin)
                else -> arrayOf(R.drawable.onix_vu_mode1_top_skin, R.drawable.onix_vu_mode1_bottom_skin, R.drawable.onix_vu_mode1_add_top_skin, R.drawable.onix_vu_mode1_add_bottom_skin)
            }
            val fl = (leftLevel * 35f).toInt().coerceIn(0, 35)
            val fr = (rightLevel * 35f).toInt().coerceIn(0, 35)
            sprite(canvas, frames[0], meterLeft + 25f, 266f, meterWidth - 50f, 12f, fl, 36)
            sprite(canvas, frames[2], meterLeft + 25f, 266f, meterWidth - 50f, 12f, fl, 36)
            sprite(canvas, frames[1], meterLeft + 25f, 293f, meterWidth - 50f, 12f, fr, 36)
            sprite(canvas, frames[3], meterLeft + 25f, 293f, meterWidth - 50f, 12f, fr, 36)
        } else {
            label(canvas, "VU / PPM OFF", 153f, 291f, 18f, Color.rgb(115, 120, 128))
        }
        target(RectF(20f, 232f, 400f, 326f)) { setVuMode((vuMode + 1) % 4) }

        // Seek slider and playback timing.
        panel(canvas, RectF(20f, 390f, 400f, 468f))
        label(canvas, "LEJÁTSZÁS", 31f, 411f, 10f)
        val sx = 34f; val ex = 386f; val sy = 439f
        val fraction = if (durationMs > 0L) (previewMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        meterPaint.color = Color.rgb(31, 36, 44)
        canvas.drawRoundRect(RectF(sx, sy - 4f, ex, sy + 4f), 4f, 4f, meterPaint)
        meterPaint.color = Color.rgb(193, 153, 91)
        canvas.drawRoundRect(RectF(sx, sy - 4f, sx + (ex - sx) * fraction, sy + 4f), 4f, 4f, meterPaint)
        meterPaint.color = Color.rgb(244, 218, 166)
        canvas.drawCircle(sx + (ex - sx) * fraction, sy, 7f, meterPaint)
        target(RectF(sx, sy - 18f, ex, sy + 18f)) { }
        label(canvas, "MUTE", 31f, 458f, 9f)
        label(canvas, "$systemVolume%   $sampleRate   $bitrate", 110f, 458f, 9f)
        sprite(canvas, R.drawable.onix_button_mute_volume_skin, 355f, 588f, 30f, 20f, if (muted) 3 else 0, 6)
        target(RectF(348f, 580f, 394f, 468f), onMute)

        // Large transport controls, thumb-friendly spacing.
        panel(canvas, RectF(20f, 482f, 400f, 580f))
        sprite(canvas, R.drawable.black_onix_prev_normal, 34f, 504f, 70f, 52f, 0, 1)
        sprite(canvas, R.drawable.onix_button_stop_skin, 112f, 504f, 56f, 52f, 0, 8)
        if (playing) sprite(canvas, R.drawable.black_onix_pause_normal, 180f, 496f, 74f, 66f, 0, 1)
        else sprite(canvas, R.drawable.black_onix_play_normal, 180f, 496f, 74f, 66f, 0, 1)
        sprite(canvas, R.drawable.black_onix_next_normal, 266f, 504f, 70f, 52f, 0, 1)
        sprite(canvas, R.drawable.onix_button_repeat_skin, 350f, 504f, 36f, 28f, when (repeatMode) { 0 -> 0; 1 -> 3; else -> 5 }, 6)
        sprite(canvas, R.drawable.onix_button_shuffle_skin, 350f, 538f, 36f, 28f, when (shuffleMode) { 0 -> 0; 1 -> 3; else -> 5 }, 6)
        target(RectF(25f, 491f, 108f, 574f), onPrevious)
        target(RectF(108f, 491f, 176f, 574f), onStop)
        target(RectF(176f, 491f, 260f, 574f), onPlayPause)
        target(RectF(260f, 491f, 344f, 574f), onNext)
        target(RectF(345f, 496f, 398f, 534f), onRepeat)
        target(RectF(345f, 534f, 398f, 576f), onShuffle)

        // Utility shortcuts.
        panel(canvas, RectF(20f, 594f, 400f, 688f))
        val shortcuts = listOf(
            Triple("OPEN", 28f, onOpen), Triple("SLEEP", 103f, onSleep),
            Triple("LISTA", 178f, onPlaylist), Triple("EQ", 253f, onEqualizer),
            Triple("RADIO", 328f, onRadio)
        )
        shortcuts.forEach { (name, x, action) ->
            panel(canvas, RectF(x, 614f, x + 64f, 667f), Color.rgb(19, 23, 30))
            label(canvas, name, x + 10f, 646f, 10f)
            target(RectF(x, 608f, x + 68f, 674f), action)
        }
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = (event.x - offsetX) / scaleNow
        val y = (event.y - offsetY) / scaleNow
        val seek = RectF(34f, 421f, 386f, 457f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (seek.contains(x, y)) {
                    draggingSeek = true
                    updatePreview(x)
                    return true
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingSeek) { updatePreview(x); return true }
            }
            MotionEvent.ACTION_UP -> {
                if (draggingSeek) {
                    draggingSeek = false
                    if (durationMs > 0L) onSeekTo(previewMs)
                    return true
                }
                hitTargets.lastOrNull { it.first.contains(x, y) }?.second?.invoke()
                return true
            }
            MotionEvent.ACTION_CANCEL -> { draggingSeek = false; previewMs = positionMs; invalidate(); return true }
        }
        return true
    }
    private fun updatePreview(x: Float) {
        val fraction = ((x - 34f) / 352f).coerceIn(0f, 1f)
        previewMs = (durationMs * fraction).toLong()
        invalidate()
    }
}