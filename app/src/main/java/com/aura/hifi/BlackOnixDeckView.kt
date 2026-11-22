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
    private val onLibrary: () -> Unit,
    private val onEqualizer: () -> Unit,
    private val onEqualizerToggle: () -> Unit,
    private val onUtilities: () -> Unit,
    private val onRadio: () -> Unit,
    private val onSettings: () -> Unit,
    private val onMainMenu: () -> Unit,
    private val onMinimize: () -> Unit,
    private val onExit: () -> Unit,
    private val onVuModeChanged: (Int) -> Unit,
    private val onSpectrumModeChanged: (Int) -> Unit,
    private val onSpectrumColorModeChanged: (Int) -> Unit,
    private val onSpectrumSegmentedChanged: (Boolean) -> Unit,
    private val onSeekStyleChanged: (Int) -> Unit,
    private val onSeekColorChanged: (Int) -> Unit,
    private val onSeekTo: (Long) -> Unit,
    private val onVolumeChanged: (Int) -> Unit,
    private val onMute: () -> Unit,
    private val onVolumeMax: () -> Unit,
    private val onPreampAdjust: () -> Unit
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
    private var muted = false
    private var equalizerEnabled = true
    private var spectrumMode = 0
    private var spectrumColorMode = 0 // 0 = original, 1 = red, 2 = blue
    private var spectrumSegmented = true
    private var leftLevel = 0.08f
    private var rightLevel = 0.08f
    // Dedicated faster response for the upper BLACK-ONIX VU only.
    private var onixVuLeftLevel = 0.08f
    private var onixVuRightLevel = 0.08f
    private var fft = ByteArray(0)
    private var fftActive = false
    private val spectrumLevels = FloatArray(30)
    private var systemVolume = 0
    private var volumeDragging = false
    private var volumeTouchStartX = 0f
    private var volumeTouchStartY = 0f
    private var volumeTouchMoved = false
    private var sampleRateText = "-- kHz"
    private var bitrateText = "-- kbps"
    private var systemClock = "--:--:--"
    private var trackPositionMs = 0L
    private var trackDurationMs = 0L
    private var seekPreviewMs = 0L
    private var seekDragging = false
    private var seekStyle = 0
    private var seekColor = 0
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

    fun setTrackProgress(positionMs: Long, durationMs: Long) {
        trackDurationMs = durationMs.coerceAtLeast(0L)
        trackPositionMs = positionMs.coerceIn(0L, trackDurationMs)
        if (!seekDragging) seekPreviewMs = trackPositionMs
        invalidate()
    }

    fun setSystemInfo(volumePercent: Int, sampleRate: String, bitrate: String, clock: String) {
        setSystemVolume(volumePercent)
        sampleRateText = sampleRate
        bitrateText = bitrate
        systemClock = clock
        invalidate()
    }

    fun setSystemVolume(value: Int) { systemVolume = value.coerceIn(0, 100); invalidate() }

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

    // Keep the original 1.43 signal-driven spectrum response.
    fun setLevels(left: Float, right: Float) {
        val l = left.coerceIn(0f, 1f)
        val r = right.coerceIn(0f, 1f)
        leftLevel = if (l >= leftLevel) leftLevel * .28f + l * .72f else leftLevel * .78f + l * .22f
        rightLevel = if (r >= rightLevel) rightLevel * .28f + r * .72f else rightLevel * .78f + r * .22f
        // Dedicated 1.22x gain and quicker response for the upper ONIX VU only.
        val onixL = (l * 1.22f).coerceIn(0f, 1f)
        val onixR = (r * 1.22f).coerceIn(0f, 1f)
        onixVuLeftLevel = if (onixL >= onixVuLeftLevel) onixVuLeftLevel * .12f + onixL * .88f else onixVuLeftLevel * .58f + onixL * .42f
        onixVuRightLevel = if (onixR >= onixVuRightLevel) onixVuRightLevel * .12f + onixR * .88f else onixVuRightLevel * .58f + onixR * .42f
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
        vuMode = mode.coerceIn(0, 5)
        onVuModeChanged(vuMode)
        invalidate()
    }

    // The three numbered keys select the meter design only.
    // VU/PPM is a separate selector in the left side of the meter strip.
    private fun selectVuPpmMode(index: Int) {
        val selected = index.coerceIn(0, 2)
        setVuMode((if (vuMode >= 3) 3 else 0) + selected)
    }

    private fun toggleVuPpmType() {
        setVuMode(if (vuMode >= 3) vuMode - 3 else vuMode + 3)
    }

    fun setMuted(value: Boolean) { muted = value; invalidate() }
    fun setEqualizerEnabled(value: Boolean) { equalizerEnabled = value; invalidate() }

    fun setSpectrumColorMode(mode: Int) { spectrumColorMode = mode.coerceIn(0, 2); invalidate() }
    fun setSpectrumSegmented(value: Boolean) { spectrumSegmented = value; invalidate() }

    fun setSpectrumMode(mode: Int) {
        spectrumMode = mode.coerceIn(0, 17)
        onSpectrumModeChanged(spectrumMode)
        invalidate()
    }

    fun setSeekStyle(style: Int) { seekStyle = style.coerceIn(0, 3); invalidate() }
    fun setSeekColor(color: Int) { seekColor = color.coerceIn(0, 1); invalidate() }

    private fun cycleSpectrumGroup(group: Int) {
        val currentGroup = spectrumMode / 6
        val currentVariant = spectrumMode % 6
        val nextVariant = if (currentGroup == group) (currentVariant + 1) % 6 else 0
        setSpectrumMode(group * 6 + nextVariant)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Fit the complete ONIX frame with a very small safety margin.
        // This keeps the lower feet visible on displays with slightly different aspect ratios.
        val scale = (width / 899f + min(width / 899f, height / 401f) * 0.985f) / 2f
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
        sprite(R.drawable.onix_vuppm_mode1_skin, canvas, 19f, 121f, 27f, 27f, if (vuMode % 3 == 0) 1 else 0, 6)
        sprite(R.drawable.onix_vuppm_mode2_skin, canvas, 46f, 121f, 27f, 27f, if (vuMode % 3 == 1) 3 else 2, 6)
        sprite(R.drawable.onix_vuppm_mode3_skin, canvas, 73f, 121f, 27f, 27f, if (vuMode % 3 == 2) 5 else 4, 6)
        sprite(R.drawable.onix_s_mode_a_skin, canvas, 799f, 223f, 21f, 25f, if (spectrumMode / 6 == 0) 1 else 0, 6)
        sprite(R.drawable.onix_s_mode_b_skin, canvas, 829f, 223f, 21f, 25f, if (spectrumMode / 6 == 1) 3 else 2, 6)
        sprite(R.drawable.onix_s_mode_c_skin, canvas, 859f, 223f, 21f, 25f, if (spectrumMode / 6 == 2) 5 else 4, 6)

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
        drawTimeString(canvas, R.drawable.onix_time_system_skin, 684f, 135f, systemClock)
        canvas.drawText(trackCounter, 149f, 137f, smallPaint)

        // Stereo side meters: independent L/R levels, with a continuous calibrated scale.
        if (vuMode != 3) {
            drawSideMeter(canvas, 263f, 159f, leftLevel)
            drawSideMeter(canvas, 269f, 159f, rightLevel)
            drawSideMeter(canvas, 625f, 159f, leftLevel)
            drawSideMeter(canvas, 631f, 159f, rightLevel)
        }

        // SpectrumA1 is positioned inside SpecModeA1: container origin (279,159),
        // control margins (6,0,5,3), exact 330x80 display area.
        val spectrumBackground = when (spectrumMode) {
            0 -> R.drawable.onix_spec_mode_a1_texture
            1 -> R.drawable.onix_spec_mode_a2_texture
            2 -> R.drawable.onix_spec_mode_a3_texture
            3 -> R.drawable.onix_spec_mode_a4_texture
            4 -> R.drawable.onix_spec_mode_a5_texture
            5 -> R.drawable.onix_spec_mode_a6_texture
            6 -> R.drawable.onix_spec_mode_b1_texture
            7 -> R.drawable.onix_spec_mode_b2_texture
            8 -> R.drawable.onix_spec_mode_b3_texture
            9 -> R.drawable.onix_spec_mode_b4_texture
            10 -> R.drawable.onix_spec_mode_b5_texture
            11 -> R.drawable.onix_spec_mode_b6_texture
            12 -> R.drawable.onix_spec_mode_c1_texture
            13 -> R.drawable.onix_spec_mode_c2_texture
            14 -> R.drawable.onix_spec_mode_c3_texture
            15 -> R.drawable.onix_spec_mode_c4_texture
            16 -> R.drawable.onix_spec_mode_c5_texture
            17 -> R.drawable.onix_spec_mode_c6_texture
            else -> R.drawable.onix_spec_mode_a1_texture
        }
        draw(spectrumBackground, canvas, 279f, 159f, 341f, 99f)
        for (i in 0 until 30) {
            val value = spectrumLevels[i].coerceIn(0f, 1f)
            val x = 286f + i * 11f
            val t = i / 29f
            val red: Int
            val green: Int
            val blue: Int
            when (spectrumColorMode) {
                1 -> { red = 220 + (t * 35).toInt(); green = 45 + (t * 30).toInt(); blue = 28 }
                2 -> { red = 25; green = 105 + (t * 75).toInt(); blue = 210 + (t * 45).toInt() }
                else -> { red = if (t < .62f) (45 + t / .62f * 190).toInt() else 235; green = if (t < .62f) 205 else (205 - (t - .62f) / .38f * 150).toInt(); blue = if (t < .62f) 95 else 45 }
            }
            spectrumPaint.color = Color.rgb(red.coerceIn(0, 255), green.coerceIn(0, 255), blue.coerceIn(0, 255))
            when (spectrumMode / 6) {
                1 -> {
                    if (spectrumSegmented) {
                        val segments = 8
                        val lit = (value * segments).toInt().coerceIn(0, segments)
                        for (segment in 0 until lit) {
                            val offset = segment * 4.5f
                            canvas.drawRoundRect(RectF(x, 199f - offset - 4f, x + 8f, 199f - offset - .5f), 1f, 1f, spectrumPaint)
                            canvas.drawRoundRect(RectF(x, 199f + offset + .5f, x + 8f, 199f + offset + 4f), 1f, 1f, spectrumPaint)
                        }
                    } else {
                        val barHeight = max(1.5f, value * 34f)
                        canvas.drawRoundRect(RectF(x, 199f - barHeight, x + 8f, 199f + barHeight), 1.5f, 1.5f, spectrumPaint)
                    }
                }
                2 -> {
                    if (spectrumSegmented) {
                        val segments = 8
                        val lit = (value * segments).toInt().coerceIn(0, segments)
                        for (segment in 0 until lit) {
                            val top = 239f - (segment + 1) * 9f
                            canvas.drawRoundRect(RectF(x, top, x + 8f, top + 7f), 1f, 1f, spectrumPaint)
                        }
                    } else {
                        val barHeight = max(2f, value * 73f)
                        canvas.drawRoundRect(RectF(x, 239f - barHeight, x + 8f, 239f), 1.5f, 1.5f, spectrumPaint)
                    }
                }
                else -> {
                    val barHeight = max(2f, value * 73f)
                    if (spectrumSegmented) {
                        val segments = 8
                        val lit = (value * segments).toInt().coerceIn(0, segments)
                        for (segment in 0 until lit) {
                            val top = 239f - (segment + 1) * 9f
                            canvas.drawRoundRect(RectF(x, top, x + 8f, top + 7f), 1f, 1f, spectrumPaint)
                        }
                    } else {
                        canvas.drawRoundRect(RectF(x, 239f - barHeight, x + 8f, 239f), 1.5f, 1.5f, spectrumPaint)
                        if (value > .12f) {
                            spectrumPaint.color = Color.argb(150, 245, 220, 160)
                            canvas.drawRect(x, 239f - barHeight, x + 8f, 237f - barHeight, spectrumPaint)
                        }
                    }
                }
            }
        }

        // Full-width BLACK-ONIX seek rail: four distinct visual styles.
        val seekLeft = 27f
        val seekRight = 872f
        val seekY = 278f
        val fraction = if (trackDurationMs > 0L) (seekPreviewMs.toFloat() / trackDurationMs).coerceIn(0f, 1f) else 0f
        val activeX = seekLeft + (seekRight - seekLeft) * fraction
        val activeColor = if (seekColor == 1) Color.rgb(255, 48, 48) else Color.rgb(0, 174, 255)
        spectrumPaint.style = Paint.Style.FILL
        spectrumPaint.color = Color.rgb(4, 7, 12)
        canvas.drawRoundRect(RectF(seekLeft - 5f, seekY - 12f, seekRight + 5f, seekY + 12f), 11f, 11f, spectrumPaint)
        spectrumPaint.style = Paint.Style.STROKE
        spectrumPaint.strokeWidth = 1.3f
        spectrumPaint.color = Color.rgb(74, 87, 103)
        canvas.drawRoundRect(RectF(seekLeft - 5f, seekY - 12f, seekRight + 5f, seekY + 12f), 11f, 11f, spectrumPaint)
        spectrumPaint.style = Paint.Style.FILL
        spectrumPaint.color = Color.rgb(18, 23, 31)
        canvas.drawRoundRect(RectF(seekLeft, seekY - 5f, seekRight, seekY + 5f), 5f, 5f, spectrumPaint)
        when (seekStyle) {
            0 -> { // LED dots
                var x = seekLeft + 5f
                while (x < seekRight - 3f) {
                    spectrumPaint.color = if (x <= activeX) activeColor else Color.rgb(48, 57, 70)
                    canvas.drawCircle(x, seekY, 2.5f, spectrumPaint)
                    x += 10f
                }
            }
            1 -> { // diagonal segmented slashes
                var x = seekLeft + 3f
                while (x < seekRight - 4f) {
                    spectrumPaint.color = if (x <= activeX) activeColor else Color.rgb(48, 57, 70)
                    val path = android.graphics.Path().apply {
                        moveTo(x, seekY + 4f); lineTo(x + 4f, seekY - 4f)
                        lineTo(x + 7f, seekY - 4f); lineTo(x + 3f, seekY + 4f); close()
                    }
                    canvas.drawPath(path, spectrumPaint)
                    x += 11f
                }
            }
            2 -> { // energy channel
                spectrumPaint.color = Color.argb(65, Color.red(activeColor), Color.green(activeColor), Color.blue(activeColor))
                canvas.drawRoundRect(RectF(seekLeft, seekY - 7f, seekRight, seekY + 7f), 7f, 7f, spectrumPaint)
                spectrumPaint.color = activeColor
                canvas.drawRoundRect(RectF(seekLeft, seekY - 2f, activeX, seekY + 2f), 2f, 2f, spectrumPaint)
                var x = seekLeft + 12f
                while (x < activeX) {
                    spectrumPaint.color = Color.argb(210, 220, 245, 255)
                    canvas.drawCircle(x, seekY, 1.6f, spectrumPaint)
                    x += 25f
                }
            }
            else -> { // hexagon chain
                var x = seekLeft + 5f
                while (x < seekRight - 6f) {
                    spectrumPaint.color = if (x <= activeX) activeColor else Color.rgb(48, 57, 70)
                    val path = android.graphics.Path()
                    val r = 4.2f
                    for (i in 0..5) {
                        val a = Math.PI / 3.0 * i
                        val px = x + kotlin.math.cos(a).toFloat() * r
                        val py = seekY + kotlin.math.sin(a).toFloat() * r
                        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }
                    path.close()
                    canvas.drawPath(path, spectrumPaint)
                    x += 11f
                }
            }
        }
        spectrumPaint.color = Color.BLACK
        canvas.drawCircle(activeX, seekY, 10f, spectrumPaint)
        spectrumPaint.style = Paint.Style.STROKE
        spectrumPaint.strokeWidth = 2.4f
        spectrumPaint.color = activeColor
        canvas.drawCircle(activeX, seekY, 9f, spectrumPaint)
        spectrumPaint.style = Paint.Style.FILL
        spectrumPaint.color = Color.rgb(220, 230, 240)
        canvas.drawCircle(activeX, seekY, 3.2f, spectrumPaint)

        val styleNames = arrayOf("1  LED DOTS", "2  SLASH", "3  ENERGY", "5  HEX")
        val cardLefts = floatArrayOf(18f, 234f, 450f, 666f)
        for (i in 0..3) {
            val left = cardLefts[i]
            val right = left + 207f
            spectrumPaint.style = Paint.Style.FILL
            spectrumPaint.color = if (seekStyle == i) Color.rgb(25, 34, 48) else Color.rgb(8, 11, 16)
            canvas.drawRoundRect(RectF(left, 294f, right, 320f), 5f, 5f, spectrumPaint)
            spectrumPaint.style = Paint.Style.STROKE
            spectrumPaint.strokeWidth = if (seekStyle == i) 1.8f else 1f
            spectrumPaint.color = if (seekStyle == i) activeColor else Color.rgb(79, 87, 98)
            canvas.drawRoundRect(RectF(left, 294f, right, 320f), 5f, 5f, spectrumPaint)
            smallPaint.color = if (seekStyle == i) activeColor else Color.rgb(180, 188, 198)
            smallPaint.textSize = 10f
            smallPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(styleNames[i], (left + right) / 2f, 311f, smallPaint)
        }
        smallPaint.textAlign = Paint.Align.LEFT
        spectrumPaint.style = Paint.Style.FILL
        spectrumPaint.color = Color.rgb(0, 174, 255)
        canvas.drawCircle(844f, 265f, 4f, spectrumPaint)
        spectrumPaint.color = Color.rgb(255, 48, 48)
        canvas.drawCircle(862f, 265f, 4f, spectrumPaint)
        spectrumPaint.style = Paint.Style.STROKE
        spectrumPaint.strokeWidth = 1.2f
        spectrumPaint.color = if (seekColor == 0) Color.WHITE else Color.GRAY
        canvas.drawCircle(844f, 265f, 6.5f, spectrumPaint)
        spectrumPaint.color = if (seekColor == 1) Color.WHITE else Color.GRAY
        canvas.drawCircle(862f, 265f, 6.5f, spectrumPaint)
        spectrumPaint.style = Paint.Style.FILL

        // Original 341x32 VU mode background, followed by its animated level sprites.
        val ppmMode = vuMode >= 3
        val meterIndex = vuMode % 3
        val vuTextures = intArrayOf(R.drawable.onix_vu_mode1_texture, R.drawable.onix_vu_mode2_texture, R.drawable.onix_vu_mode3_texture)
        val ppmTextures = intArrayOf(R.drawable.onix_ppm_mode1_texture, R.drawable.onix_ppm_mode2_texture, R.drawable.onix_ppm_mode3_texture)
        val meterTexture = if (ppmMode) ppmTextures[meterIndex] else vuTextures[meterIndex]
        draw(meterTexture, canvas, 279f, 127f, 341f, 32f)
        // The skin's VU / PPM labels are the independent type selector.
        // A restrained gold outline makes the active type visible.
        val typeHighlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.2f
            color = Color.rgb(211, 168, 96)
        }
        val activeTypeRect = if (ppmMode) RectF(280f, 143f, 309f, 158f) else RectF(280f, 128f, 309f, 143f)
        canvas.drawRoundRect(activeTypeRect, 2f, 2f, typeHighlight)

        // Original VU and PPM modes use separate sprite sets with matching stereo peaks.
        val vuSprites = arrayOf(
            intArrayOf(R.drawable.onix_vu_mode1_top_skin, R.drawable.onix_vu_mode1_bottom_skin, R.drawable.onix_vu_mode1_add_top_skin, R.drawable.onix_vu_mode1_add_bottom_skin),
            intArrayOf(R.drawable.onix_vu_mode2_top_skin, R.drawable.onix_vu_mode2_bottom_skin, R.drawable.onix_vu_mode2_add_top_skin, R.drawable.onix_vu_mode2_add_bottom_skin),
            intArrayOf(R.drawable.onix_vu_mode3_top_skin, R.drawable.onix_vu_mode3_bottom_skin, R.drawable.onix_vu_mode3_add_top_skin, R.drawable.onix_vu_mode3_add_bottom_skin)
        )
        val ppmSprites = arrayOf(
            intArrayOf(R.drawable.onix_ppm_mode1_top_skin, R.drawable.onix_ppm_mode1_bottom_skin, R.drawable.onix_ppm_mode1_add_top_skin, R.drawable.onix_ppm_mode1_add_bottom_skin),
            intArrayOf(R.drawable.onix_ppm_mode2_top_skin, R.drawable.onix_ppm_mode2_bottom_skin, R.drawable.onix_ppm_mode2_add_top_skin, R.drawable.onix_ppm_mode2_add_bottom_skin),
            intArrayOf(R.drawable.onix_ppm_mode3_top_skin, R.drawable.onix_ppm_mode3_bottom_skin, R.drawable.onix_ppm_mode3_add_top_skin, R.drawable.onix_ppm_mode3_add_bottom_skin)
        )
        val meterSprites = if (ppmMode) ppmSprites[meterIndex] else vuSprites[meterIndex]
        val frameL = (onixVuLeftLevel * 35f).toInt().coerceIn(0, 35)
        val frameR = (onixVuRightLevel * 35f).toInt().coerceIn(0, 35)
        sprite(meterSprites[0], canvas, 327f, 131f, 245f, 6f, frameL, 36)
        sprite(meterSprites[2], canvas, 327f, 131f, 245f, 6f, frameL, 36)
        sprite(meterSprites[1], canvas, 327f, 146f, 245f, 6f, frameR, 36)
        sprite(meterSprites[3], canvas, 327f, 146f, 245f, 6f, frameR, 36)

        // Cover the baked AIMP3 branding in the top control row and place AURA
        // in the clear center gap between the left and right button groups.
        val brandBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(4, 7, 11) }
        canvas.drawRect(96f, 27f, 800f, 50f, brandBg)
        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(224, 190, 128)
            textSize = 14f
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            setShadowLayer(2f, 0f, 1f, Color.BLACK)
        }
        canvas.drawText("AURA", 448f, 44f, brandPaint)
        sprite(R.drawable.onix_button_mute_volume_skin, canvas, 799f, 184f, 41f, 25f, if (muted) 3 else 0, 6)

        // Top bar: original ONIX controls.
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

        // Original AIMP BLACK-ONIX 102-frame masked rotary volume slider.
        // Frame 101 is the hit-test mask; frames 0..100 represent volume 0..100.
        spriteHorizontal(R.drawable.onix_volume_knob, canvas, 787f, 63f, 107f, 121f,
            systemVolume.coerceIn(0, 100), 102)
        smallPaint.color = Color.rgb(222, 190, 137)
        smallPaint.textSize = 9f
        canvas.drawText("VOLUME", 151f, 174f, smallPaint)
        canvas.drawText(sampleRateText, 149f, 222f, smallPaint)
        canvas.drawText(bitrateText, 140f, 242f, smallPaint)
        // Original AIMP screen tabs on the left bottom panel.
        sprite(R.drawable.onix_pl_scr_a_skin, canvas, 26f, 341f, 44f, 19f, 0, 4)
        sprite(R.drawable.onix_eq_scr_a_skin, canvas, 70f, 341f, 44f, 19f, 0, 4)
        spectrumPaint.color = if (equalizerEnabled) Color.rgb(105, 205, 128) else Color.rgb(105, 112, 122)
        canvas.drawCircle(108f, 345f, 2.5f, spectrumPaint)

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
        hitTargets += RectF(623f, 339f, 660f, 366f) to onLibrary
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
        // VU / PPM labels on the left edge of the original meter strip.
        hitTargets += RectF(279f, 127f, 310f, 143f) to { setVuMode(vuMode % 3) }
        hitTargets += RectF(279f, 143f, 310f, 159f) to { setVuMode(3 + (vuMode % 3)) }
        // Numbered VU meter design keys (1, 2, 3).
        hitTargets += RectF(19f, 121f, 46f, 148f) to { selectVuPpmMode(0) }
        hitTargets += RectF(46f, 121f, 73f, 148f) to { selectVuPpmMode(1) }
        hitTargets += RectF(73f, 121f, 100f, 148f) to { selectVuPpmMode(2) }
        hitTargets += RectF(799f, 184f, 820f, 209f) to onMute
        hitTargets += RectF(820f, 184f, 840f, 209f) to onVolumeMax
        hitTargets += RectF(799f, 223f, 820f, 248f) to { cycleSpectrumGroup(0) }
        hitTargets += RectF(829f, 223f, 850f, 248f) to { cycleSpectrumGroup(1) }
        hitTargets += RectF(859f, 223f, 880f, 248f) to { cycleSpectrumGroup(2) }
        hitTargets += RectF(18f, 293f, 225f, 321f) to { seekStyle = 0; onSeekStyleChanged(seekStyle); invalidate() }
        hitTargets += RectF(234f, 293f, 441f, 321f) to { seekStyle = 1; onSeekStyleChanged(seekStyle); invalidate() }
        hitTargets += RectF(450f, 293f, 657f, 321f) to { seekStyle = 2; onSeekStyleChanged(seekStyle); invalidate() }
        hitTargets += RectF(666f, 293f, 873f, 321f) to { seekStyle = 3; onSeekStyleChanged(seekStyle); invalidate() }
        hitTargets += RectF(836f, 257f, 852f, 273f) to { seekColor = 0; onSeekColorChanged(seekColor); invalidate() }
        hitTargets += RectF(854f, 257f, 870f, 273f) to { seekColor = 1; onSeekColorChanged(seekColor); invalidate() }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Fit the complete ONIX frame with a very small safety margin.
        // This keeps the lower feet visible on displays with slightly different aspect ratios.
        val scale = (width / 899f + min(width / 899f, height / 401f) * 0.985f) / 2f
        val ox = (width - 899f * scale) / 2f
        val oy = (height - 401f * scale) / 2f
        val x = (event.x - ox) / scale
        val y = (event.y - oy) / scale
        if (event.action == MotionEvent.ACTION_DOWN) {
            touchDownTime = event.eventTime
            if (RectF(787f, 63f, 894f, 184f).contains(x, y)) {
                volumeDragging = true
                volumeTouchStartX = x
                volumeTouchStartY = y
                volumeTouchMoved = false
                return true
            }
            if (RectF(20f, 265f, 879f, 289f).contains(x, y) && trackDurationMs > 0L) {
                seekDragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                updateSeekPreview(x)
            }
            return true
        }
        if (event.action == MotionEvent.ACTION_MOVE && volumeDragging) {
            if (abs(x - volumeTouchStartX) > 3f || abs(y - volumeTouchStartY) > 3f) volumeTouchMoved = true
            if (volumeTouchMoved) updateVolumeFromTouch(x, y)
            return true
        }
        if (event.action == MotionEvent.ACTION_MOVE && seekDragging) {
            updateSeekPreview(x)
            return true
        }
        if (event.action == MotionEvent.ACTION_CANCEL) {
            volumeDragging = false
            seekDragging = false
            parent?.requestDisallowInterceptTouchEvent(false)
            seekPreviewMs = trackPositionMs
            invalidate()
            return true
        }
        if (event.action != MotionEvent.ACTION_UP) return true
        val held = event.eventTime - touchDownTime
        if (volumeDragging) {
            if (held >= 500L && !volumeTouchMoved) onPreampAdjust() else updateVolumeFromTouch(x, y)
            volumeDragging = false
            performClick()
            return true
        }
        if (seekDragging) {
            updateSeekPreview(x)
            seekDragging = false
            onSeekTo(seekPreviewMs)
            parent?.requestDisallowInterceptTouchEvent(false)
            performClick()
            return true
        }
        if (RectF(659f, 339f, 696f, 366f).contains(x, y) && held >= 500L) {
            onRepeatLong()
        } else if (RectF(799f, 223f, 880f, 248f).contains(x, y) && held >= 500L) {
            spectrumColorMode = (spectrumColorMode + 1) % 3
            onSpectrumColorModeChanged(spectrumColorMode)
            invalidate()
        } else if (RectF(279f, 159f, 620f, 258f).contains(x, y) && held >= 500L) {
            spectrumSegmented = !spectrumSegmented
            onSpectrumSegmentedChanged(spectrumSegmented)
            invalidate()
        } else if (RectF(70f, 341f, 114f, 360f).contains(x, y) && held >= 500L) {
            onEqualizerToggle()
        } else {
            hitTargets.firstOrNull { it.first.contains(x, y) }?.second?.invoke()
        }
        performClick()
        return true
    }

    private fun drawSideMeter(canvas: Canvas, x: Float, y: Float, rawLevel: Float) {
        val level = rawLevel.coerceIn(0f, 1f)
        val h = 82f
        val segments = 30
        val gap = 1f
        val segmentH = (h - gap * (segments - 1)) / segments
        for (i in 0 until segments) {
            val active = i < (level * segments).toInt()
            val ratio = i.toFloat() / (segments - 1)
            val color = when {
                ratio > .88f -> Color.rgb(235, 42, 35)
                ratio > .70f -> Color.rgb(242, 150, 42)
                else -> Color.rgb(112, 190, 92)
            }
            spectrumPaint.color = if (active) color else Color.rgb(31, 35, 40)
            val top = y + h - (i + 1) * segmentH - i * gap
            canvas.drawRoundRect(RectF(x, top, x + 4f, top + segmentH), 1f, 1f, spectrumPaint)
        }
    }

    private fun updateVolumeFromTouch(x: Float, y: Float) {
        val centerX = 840.5f
        val centerY = 123.5f
        var angle = Math.toDegrees(kotlin.math.atan2((y - centerY).toDouble(), (x - centerX).toDouble())).toFloat()
        if (angle < 0f) angle += 360f
        // The original dial sweeps 300 degrees, leaving a 60-degree dead zone at the bottom.
        if (angle in 60f..120f) angle = if (angle - 60f <= 120f - angle) 60f else 120f
        if (angle < 120f) angle += 360f
        val percent = (((angle - 120f) / 300f) * 100f).toInt().coerceIn(0, 100)
        if (percent != systemVolume) {
            systemVolume = percent
            onVolumeChanged(percent)
            invalidate()
        }
    }

    private fun updateSeekPreview(x: Float) {
        val fraction = ((x - 27f) / (872f - 27f)).coerceIn(0f, 1f)
        seekPreviewMs = (trackDurationMs * fraction).toLong()
        invalidate()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
