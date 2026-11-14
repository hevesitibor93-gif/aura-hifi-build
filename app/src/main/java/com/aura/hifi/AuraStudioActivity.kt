package com.aura.hifi

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.max

/**
 * AURA Studio is an additional visual theme. MainActivity (Classic) remains
 * the default player and is not replaced by this screen.
 */
class AuraStudioActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var titleView: TextView? = null
    private var artistView: TextView? = null
    private var playButton: Button? = null
    private var progress: SeekBar? = null
    private var elapsedView: TextView? = null
    private var durationView: TextView? = null
    private var userSeeking = false

    private val refreshTask = object : Runnable {
        override fun run() {
            refreshPlaybackUi()
            handler.postDelayed(this, 500L)
        }
    }

    private val gold = Color.rgb(229, 166, 68)
    private val ivory = Color.rgb(232, 224, 207)
    private val muted = Color.rgb(164, 157, 145)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(18, 13, 9)
        window.navigationBarColor = Color.rgb(18, 13, 9)
        setContentView(buildStudio())
        refreshPlaybackUi()
        handler.post(refreshTask)
    }

    private fun buildStudio(): View {
        val root = FrameLayout(this)
        val background = ImageView(this).apply {
            setImageResource(R.drawable.aura_studio_bg)
            scaleType = ImageView.ScaleType.FIT_XY
            contentDescription = "AURA Hi-Fi Studio grafit és arany előlap"
        }
        root.addView(background, FrameLayout.LayoutParams(-1, -1))

        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), 0, dp(28), 0)
        }
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        addSpace(overlay, 12f)

        val display = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(5), dp(12), dp(5))
        }
        titleView = text("AURA HI-FI STUDIO", 17f, ivory, true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
        }
        artistView = text("Válassz zenét a Classic zenetárban", 11f, muted).apply {
            gravity = Gravity.CENTER
            maxLines = 1
        }
        display.addView(titleView, LinearLayout.LayoutParams(-1, 0, 1f))
        display.addView(artistView, LinearLayout.LayoutParams(-1, 0, 0.7f))
        overlay.addView(display, LinearLayout.LayoutParams(-1, 0, 18f))

        addSpace(overlay, 1.5f)

        val meters = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        meters.addView(meterColumn("LEFT / L"), LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(12) })
        meters.addView(meterColumn("RIGHT / R"), LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(12) })
        overlay.addView(meters, LinearLayout.LayoutParams(-1, 0, 16f))

        addSpace(overlay, 1.5f)

        val seekPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(7), dp(20), dp(7))
        }
        progress = SeekBar(this).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(gold)
            thumbTintList = android.content.res.ColorStateList.valueOf(ivory)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) userSeeking = true
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) { userSeeking = true }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val player = AuraPlaybackService.player
                    if (player != null && player.duration > 0L) {
                        player.seekTo((player.duration * ((seekBar?.progress ?: 0) / 1000f)).toLong())
                    }
                    userSeeking = false
                }
            })
        }
        seekPanel.addView(progress, LinearLayout.LayoutParams(-1, dp(34)))
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        elapsedView = text("00:00", 10f, ivory, true)
        durationView = text("00:00", 10f, muted)
        times.addView(elapsedView, LinearLayout.LayoutParams(0, -2, 1f))
        times.addView(durationView)
        seekPanel.addView(times)
        overlay.addView(seekPanel, LinearLayout.LayoutParams(-1, 0, 16f))

        addSpace(overlay, 1.5f)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val transport = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        transport.addView(controlButton("◀◀") { AuraPlaybackService.previousFromWidget() },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(7) })
        playButton = controlButton("▶") { AuraPlaybackService.togglePlaybackFromWidget() }
        transport.addView(playButton, LinearLayout.LayoutParams(0, dp(48), 1.25f).apply { marginHorizontal = dp(7) })
        transport.addView(controlButton("▶▶") { AuraPlaybackService.nextFromWidget() },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(7) })
        controls.addView(transport, LinearLayout.LayoutParams(-1, -2))
        controls.addView(text("AURA STUDIO  •  CLASSIC ZENETÁR / EQ KAPCSOLAT KÖVETKEZIK", 8f, muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(7), 0, 0)
        })
        overlay.addView(controls, LinearLayout.LayoutParams(-1, 0, 16f))

        addSpace(overlay, 17.5f)
        return root
    }

    private fun meterColumn(label: String): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        column.addView(text(label, 10f, gold, true).apply { gravity = Gravity.CENTER })
        val track = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(44, 34, 24), Color.rgb(17, 17, 16))).apply {
                cornerRadius = dp(4).toFloat()
                setStroke(dp(1), Color.rgb(83, 64, 43))
            }
        }
        column.addView(track, LinearLayout.LayoutParams(-1, dp(22)).apply {
            topMargin = dp(10)
            bottomMargin = dp(4)
        })
        column.addView(text("STEREO LEVEL", 8f, muted).apply { gravity = Gravity.CENTER })
        return column
    }

    private fun controlButton(caption: String, action: () -> Unit): Button =
        Button(this).apply {
            text = caption
            textSize = 14f
            setTextColor(ivory)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.rgb(30, 25, 20))
                setStroke(dp(1), gold)
            }
            setOnClickListener { action(); refreshPlaybackUi() }
        }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun addSpace(parent: LinearLayout, weight: Float) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, 0, weight))
    }

    private fun refreshPlaybackUi() {
        val player = AuraPlaybackService.player ?: return
        val metadata = player.mediaMetadata
        titleView?.text = metadata.title?.toString()?.takeIf { it.isNotBlank() } ?: "AURA HI-FI STUDIO"
        artistView?.text = metadata.artist?.toString()?.takeIf { it.isNotBlank() } ?: "Nincs aktív előadó"
        playButton?.text = if (player.isPlaying) "Ⅱ" else "▶"
        val duration = max(0L, player.duration)
        if (!userSeeking && duration > 0L) {
            progress?.progress = ((player.currentPosition.toFloat() / duration) * 1000f).toInt().coerceIn(0, 1000)
        }
        elapsedView?.text = formatTime(player.currentPosition)
        durationView?.text = formatTime(duration)
    }

    private fun formatTime(ms: Long): String {
        val total = (ms.coerceAtLeast(0L) / 1000L).toInt()
        return "%02d:%02d".format(total / 60, total % 60)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        handler.removeCallbacks(refreshTask)
        super.onDestroy()
    }
}
