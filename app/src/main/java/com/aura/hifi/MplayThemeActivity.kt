package com.aura.hifi

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.C

/**
 * Mplay-style native presentation. It uses AURA's existing playback service;
 * it does not create a second player or change the Classic screen.
 */
class MplayThemeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var titleView: TextView? = null
    private var artistView: TextView? = null
    private var timeView: TextView? = null
    private var progressBar: SeekBar? = null
    private var volumeBar: SeekBar? = null
    private var playButton: Button? = null
    private var leftBars: List<View> = emptyList()
    private var rightBars: List<View> = emptyList()
    private var eqDeck: LinearLayout? = null

    private val refreshTask = object : Runnable {
        override fun run() {
            refreshPlaybackUi()
            handler.postDelayed(this, 400L)
        }
    }

    private val player get() = AuraPlaybackService.player

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(8, 9, 9)
        window.navigationBarColor = Color.rgb(8, 9, 9)
        buildScreen()
        refreshPlaybackUi()
        handler.post(refreshTask)
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshTask)
        super.onDestroy()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(8, 9, 9))
            setPadding(dp(10), dp(12), dp(10), dp(12))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER }
        val rack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(16, 17, 17), Color.rgb(43, 35, 25), Color.rgb(10, 11, 11))).apply {
                cornerRadius = dp(11).toFloat()
                setStroke(dp(1), Color.rgb(130, 119, 96))
            }
            setPadding(dp(15), dp(12), dp(12), dp(12))
        }
        val brand = TextView(this).apply {
            text = "AURA"
            textSize = 25f
            letterSpacing = 0.12f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextColor(Color.rgb(226, 169, 70))
        }
        val subtitle = TextView(this).apply {
            text = "  Hi-Fi Studio"
            textSize = 13f
            setTextColor(Color.rgb(201, 152, 75))
        }
        header.addView(brand)
        header.addView(subtitle, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(actionButton("CLASSIC") { returnToClassic() }, LinearLayout.LayoutParams(dp(82), dp(36)))
        rack.addView(header)

        val playerDeck = chassis(206)
        val display = displayWell().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(8))
        }
        display.addView(TextView(this).apply {
            text = "●  NOW PLAYING"
            textSize = 9f
            letterSpacing = 0.16f
            setTextColor(Color.rgb(155, 135, 101))
        })
        titleView = TextView(this).apply {
            text = "Nincs betöltött zene"
            textSize = 16f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(Color.rgb(225, 218, 199))
            setPadding(0, dp(8), 0, dp(2))
        }
        display.addView(titleView)
        artistView = TextView(this).apply {
            textSize = 10f
            maxLines = 1
            setTextColor(Color.rgb(143, 139, 126))
        }
        display.addView(artistView)
        timeView = TextView(this).apply {
            text = "00:00  /  00:00"
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(172, 143, 94))
            gravity = Gravity.CENTER_VERTICAL
        }
        display.addView(timeView)
        progressBar = SeekBar(this).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(201, 137, 48))
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.rgb(230, 174, 82))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val active = player ?: return
                        val duration = active.duration
                        if (duration > 0 && duration != C.TIME_UNSET) active.seekTo(duration * progress / 1000L)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        display.addView(progressBar, LinearLayout.LayoutParams(-1, dp(24)))
        playerDeck.addView(display, LinearLayout.LayoutParams(-1, dp(112)))
        val transport = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(9), 0, 0)
        }
        transport.addView(actionButton("◀") { player?.let { AuraPlaybackService.onPreviousRequested?.invoke() ?: it.seekToPreviousMediaItem() } }, LinearLayout.LayoutParams(dp(44), dp(38)).apply { marginEnd = dp(10) })
        playButton = actionButton("▶") { togglePlayback() }
        transport.addView(playButton, LinearLayout.LayoutParams(dp(56), dp(42)).apply { marginEnd = dp(10) })
        transport.addView(actionButton("▶|") { player?.let { AuraPlaybackService.onNextRequested?.invoke() ?: it.seekToNextMediaItem() } }, LinearLayout.LayoutParams(dp(44), dp(38)))
        playerDeck.addView(transport, LinearLayout.LayoutParams(-1, dp(46)))
        rack.addView(playerDeck)

        val ampDeck = chassis(152)
        val meters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; }
        val leftMeter = meterPanel("LEFT")
        val rightMeter = meterPanel("RIGHT")
        leftBars = leftMeter.second
        rightBars = rightMeter.second
        meters.addView(leftMeter.first, LinearLayout.LayoutParams(0, dp(78), 1f).apply { marginEnd = dp(8) })
        meters.addView(rightMeter.first, LinearLayout.LayoutParams(0, dp(78), 1f))
        ampDeck.addView(meters)
        val volumeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(8), dp(4), 0) }
        volumeRow.addView(TextView(this).apply { text = "VOL"; textSize = 9f; setTextColor(Color.rgb(174, 150, 110)) })
        volumeBar = SeekBar(this).apply {
            max = 100
            progress = ((player?.volume ?: 1f) * 100).toInt()
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(201, 137, 48))
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.rgb(230, 174, 82))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) player?.volume = progress / 100f
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        volumeRow.addView(volumeBar, LinearLayout.LayoutParams(0, dp(28), 1f).apply { marginStart = dp(10) })
        ampDeck.addView(volumeRow)
        rack.addView(ampDeck)

        val eqToggle = actionButton("EQUALIZER  •  12 BAND") {
            eqDeck?.visibility = if (eqDeck?.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        rack.addView(eqToggle, LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(10) })
        eqDeck = buildEqDeck().apply { visibility = View.GONE }
        rack.addView(eqDeck)

        val libraryDeck = chassis(112, LinearLayout.HORIZONTAL)
        val libraryText = TextView(this).apply {
            text = "LOCAL LIBRARY\nAURA ZENETÁR"
            gravity = Gravity.CENTER
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(Color.rgb(191, 169, 126))
        }
        libraryDeck.addView(libraryText, LinearLayout.LayoutParams(0, -1, 1f))
        libraryDeck.addView(actionButton("ZENETÁR") { openClassicLibrary() }, LinearLayout.LayoutParams(dp(104), dp(40)))
        rack.addView(libraryDeck)

        val radioDeck = chassis(94)
        radioDeck.addView(TextView(this).apply {
            text = "AURA RADIO   •   INTERNET RÁDIÓ"
            gravity = Gravity.CENTER
            textSize = 10f
            letterSpacing = 0.12f
            setTextColor(Color.rgb(176, 145, 95))
        }, LinearLayout.LayoutParams(-1, -1))
        rack.addView(radioDeck)

        val feet = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(34), 0, dp(34), 0)
        }
        repeat(2) {
            val foot = View(this).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.rgb(143, 91, 36), Color.rgb(33, 23, 14))).apply {
                    cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, dp(9).toFloat(), dp(9).toFloat(), dp(9).toFloat(), dp(9).toFloat())
                }
            }
            feet.addView(foot, LinearLayout.LayoutParams(dp(64), dp(12)).apply {
                if (it == 0) marginEnd = 0 else marginStart = 0
                weight = 1f
            })
        }
        rack.addView(feet, LinearLayout.LayoutParams(-1, dp(14)))
        scroll.addView(rack)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun chassis(height: Int, direction: Int = LinearLayout.VERTICAL): LinearLayout = LinearLayout(this).apply {
        orientation = direction
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.rgb(76, 77, 75), Color.rgb(38, 40, 40), Color.rgb(19, 20, 20))).apply {
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), Color.rgb(155, 154, 143))
        }
        setPadding(dp(24), dp(20), dp(24), dp(20))
        elevation = dp(7).toFloat()
        layoutParams = LinearLayout.LayoutParams(-1, dp(height)).apply { topMargin = dp(12) }
    }

    private fun displayWell(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.rgb(3, 5, 6), Color.rgb(10, 12, 12), Color.rgb(2, 3, 3))).apply {
            cornerRadius = dp(7).toFloat()
            setStroke(dp(2), Color.rgb(5, 7, 7))
        }
        elevation = dp(3).toFloat()
    }

    private fun buildEqDeck(): LinearLayout {
        val deck = chassis(202)
        val prefs = getSharedPreferences("aurix_settings", MODE_PRIVATE)
        deck.addView(TextView(this).apply {
            text = "AURA EQUALIZER  •  12 BAND"
            textSize = 10f
            letterSpacing = 0.12f
            setTextColor(Color.rgb(211, 174, 105))
            setPadding(0, 0, 0, dp(7))
        })
        val presets = listOf(
            "FLAT" to intArrayOf(0,0,0,0,0,0,0,0,0,0,0,0),
            "ROCK" to intArrayOf(5,4,3,1,-1,-1,0,2,4,5,5,4),
            "POP" to intArrayOf(-1,1,3,4,2,0,-1,-1,1,2,2,2),
            "JAZZ" to intArrayOf(3,2,1,0,-1,-1,0,2,3,3,2,1),
            "CLASSIC" to intArrayOf(4,3,2,1,-1,-2,-1,1,2,3,3,3),
            "DANCE" to intArrayOf(5,4,2,0,0,2,3,4,4,3,2,1)
        )
        val presetScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        presets.forEach { preset ->
            presetRow.addView(actionButton(preset.first) {
                val edit = prefs.edit()
                preset.second.forEachIndexed { index, value ->
                    edit.putInt("eq_band_$index", value)
                    AuraPlaybackService.eqProcessor.setBandGainDb(index, value.toFloat())
                }
                edit.apply()
                refreshEqControls(deck)
            }, LinearLayout.LayoutParams(dp(66), dp(31)).apply { marginEnd = dp(5) })
        }
        presetScroll.addView(presetRow)
        deck.addView(presetScroll, LinearLayout.LayoutParams(-1, dp(34)))

        val bandScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val bandRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val names = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "12k", "14k", "16k")
        names.forEachIndexed { index, name ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = displayWell().background
                setPadding(dp(5), dp(4), dp(5), dp(3))
            }
            val valueLabel = TextView(this).apply {
                textSize = 9f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(211, 174, 105))
            }
            val bandBar = SeekBar(this).apply {
                max = 24
                progress = prefs.getInt("eq_band_$index", 0).coerceIn(-12, 12) + 12
                progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(201, 137, 48))
                thumbTintList = android.content.res.ColorStateList.valueOf(Color.rgb(230, 174, 82))
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val db = progress - 12
                        valueLabel.text = if (db > 0) "+$db dB" else "$db dB"
                        if (fromUser) {
                            prefs.edit().putInt("eq_band_$index", db).apply()
                            AuraPlaybackService.eqProcessor.setBandGainDb(index, db.toFloat())
                        }
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }
            valueLabel.text = "\${prefs.getInt("eq_band_$index", 0).coerceIn(-12, 12)} dB"
            card.addView(TextView(this).apply {
                text = name
                textSize = 9f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(180, 174, 159))
            })
            card.addView(valueLabel)
            card.addView(bandBar, LinearLayout.LayoutParams(dp(78), dp(34)))
            bandRow.addView(card, LinearLayout.LayoutParams(dp(88), dp(76)).apply { marginEnd = dp(5) })
        }
        bandScroll.addView(bandRow)
        deck.addView(bandScroll, LinearLayout.LayoutParams(-1, dp(82)).apply { topMargin = dp(7) })

        val toneRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf("BASS" to "eq_bass", "TREBLE" to "eq_treble").forEach { (label, key) ->
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val current = TextView(this).apply {
                textSize = 9f
                setTextColor(Color.rgb(211, 174, 105))
            }
            val bar = SeekBar(this).apply {
                max = 120
                progress = prefs.getInt(key, 0).coerceIn(0, 120)
                progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(201, 137, 48))
                thumbTintList = android.content.res.ColorStateList.valueOf(Color.rgb(230, 174, 82))
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        current.text = "$label  \${"%.1f".format(java.util.Locale.ROOT, progress / 10f)} dB"
                        if (fromUser) {
                            prefs.edit().putInt(key, progress).apply()
                            if (key == "eq_bass") AuraPlaybackService.eqProcessor.setBassBoostDb(progress / 10f)
                            else AuraPlaybackService.eqProcessor.setTrebleBoostDb(progress / 10f)
                        }
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }
            current.text = "$label  \${"%.1f".format(java.util.Locale.ROOT, bar.progress / 10f)} dB"
            column.addView(current)
            column.addView(bar, LinearLayout.LayoutParams(-1, dp(30)))
            toneRow.addView(column, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (label == "BASS") marginEnd = dp(8) else marginStart = dp(8)
            })
        }
        deck.addView(toneRow, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(3) })
        return deck
    }

    private fun refreshEqControls(oldDeck: LinearLayout) {
        val parent = oldDeck.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(oldDeck)
        val visible = oldDeck.visibility
        parent.removeView(oldDeck)
        val replacement = buildEqDeck().apply { visibility = visible }
        parent.addView(replacement, index)
        eqDeck = replacement
    }

    private fun meterPanel(name: String): Pair<LinearLayout, List<View>> {
        val panel = displayWell().apply { setPadding(dp(10), dp(8), dp(10), dp(6)) }
        panel.addView(TextView(this).apply {
            text = name
            textSize = 8f
            letterSpacing = 0.16f
            setTextColor(Color.rgb(121, 123, 115))
        })
        val bars = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        val views = (0 until 8).map { index ->
            View(this).apply {
                background = GradientDrawable().apply {
                    setColor(if (index >= 6) Color.rgb(181, 113, 45) else Color.rgb(142, 93, 42))
                    cornerRadius = dp(1).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(0, dp(listOf(15, 23, 30, 19, 34, 26, 38, 29)[index]), 1f).apply {
                    marginEnd = dp(3)
                }
            }
        }
        views.forEach { bars.addView(it) }
        panel.addView(bars, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6) })
        return panel to views
    }

    private fun actionButton(caption: String, action: () -> Unit): Button =
        Button(this).apply {
            text = caption
            textSize = 9f
            isAllCaps = false
            setTextColor(Color.rgb(226, 207, 170))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.rgb(73, 66, 54), Color.rgb(27, 27, 25))).apply {
                cornerRadius = dp(7).toFloat()
                setStroke(dp(1), Color.rgb(115, 101, 77))
            }
            setOnClickListener { action() }
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(4), 0, dp(4), 0)
        }

    private fun togglePlayback() {
        val active = player ?: return
        if (active.isPlaying) active.pause() else active.play()
        refreshPlaybackUi()
    }

    private fun refreshPlaybackUi() {
        refreshVuMeters()
        val active = player
        val item = active?.currentMediaItem
        titleView?.text = item?.mediaMetadata?.title?.toString()?.takeIf { it.isNotBlank() } ?: "Nincs betöltött zene"
        artistView?.text = item?.mediaMetadata?.artist?.toString().orEmpty()
        playButton?.text = if (active?.isPlaying == true) "Ⅱ" else "▶"
        val duration = active?.duration ?: 0L
        val position = active?.currentPosition ?: 0L
        if (duration > 0L && duration != C.TIME_UNSET) {
            progressBar?.progress = (position * 1000L / duration).toInt().coerceIn(0, 1000)
            timeView?.text = "${formatTime(position)}  /  ${formatTime(duration)}"
        } else {
            progressBar?.progress = 0
            timeView?.text = "00:00  /  00:00"
        }
        volumeBar?.let { bar ->
            val value = ((active?.volume ?: 1f) * 100f).toInt().coerceIn(0, 100)
            if (!bar.isPressed && bar.progress != value) bar.progress = value
        }
    }

    private fun refreshVuMeters() {
        val active = player
        val left = if (active?.isPlaying == true) AuraVuBridge.left else 0f
        val right = if (active?.isPlaying == true) AuraVuBridge.right else 0f
        updateMeterBars(leftBars, left)
        updateMeterBars(rightBars, right)
    }

    private fun updateMeterBars(bars: List<View>, level: Float) {
        val lit = (level.coerceIn(0f, 1f) * bars.size).toInt()
        bars.forEachIndexed { index, bar ->
            bar.pivotY = bar.height.toFloat()
            val active = index < lit
            bar.alpha = if (active) 1f else 0.20f
            bar.scaleY = if (active) 1f else 0.30f
        }
    }

    private fun formatTime(ms: Long): String {
        val total = (ms.coerceAtLeast(0L) / 1000L).toInt()
        return "%02d:%02d".format(total / 60, total % 60)
    }

    private fun openClassicLibrary() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("aura_open_library", true)
        }
        startActivity(intent)
        finish()
    }

    private fun returnToClassic() {
        finish()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
