package com.aura.remixer

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.HorizontalScrollView
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import java.util.concurrent.Executors

/** Standalone AURA Remixer. Its sound engine is independent of AURA Hi-Fi. */
class RemixerActivity : Activity() {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val gold = Color.rgb(255, 190, 82)
    private val handler = Handler(Looper.getMainLooper())
    private val tracks = mutableSetOf<AudioTrack>()
    private val audioExecutor = Executors.newSingleThreadExecutor()
    private var kit = 0
    private var playing = false
    private var currentStep = -1
    private val pattern = Array(8) { BooleanArray(16) }
    private val stepCells = Array(8) { arrayOfNulls<TextView>(16) }
    private lateinit var sequenceButton: TextView
    private val sequenceTick = object : Runnable {
        override fun run() {
            if (!playing) return
            currentStep = (currentStep + 1) % 16
            updateSequencer()
            for (trackIndex in 0..7) {
                if (pattern[trackIndex][currentStep]) {
                    audioExecutor.execute { sound(trackIndex) }
                }
            }
            handler.postDelayed(this, (60000L / (bpm * 4)).coerceAtLeast(20L))
        }
    }
    init {
        pattern[0][0] = true; pattern[0][8] = true
        pattern[1][4] = true; pattern[1][12] = true
        for (i in 0 until 16 step 2) pattern[2][i] = true
        pattern[4][12] = true
        pattern[5][10] = true
    }
    private var bpm = 120
    private lateinit var bpmText: TextView
    private lateinit var kitText: TextView
    private val names = listOf("KICK", "SNARE", "CLOSED HAT", "OPEN HAT", "CLAP", "LOW TOM", "HIGH TOM", "RIM")
    private val colors = listOf(
        0xffa45b3d.toInt(), 0xffa34e59.toInt(), 0xff397f91.toInt(), 0xff397f91.toInt(),
        0xff805ca1.toInt(), 0xff4b719e.toInt(), 0xff4b719e.toInt(), 0xff967b42.toInt()
    )
    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun surface(color: Int, border: Int = 0xff596a82.toInt()) =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(color, 0xff111a28.toInt())).apply {
            cornerRadius = dp(14).toFloat(); setStroke(dp(1), border)
        }

    private fun sound(pad: Int) {
        val rate = 44100
        val seconds = when (pad) { 0 -> .48; 1 -> .34; 2 -> .12; 3 -> .62; 4 -> .30; 5 -> .42; 6 -> .30; else -> .16 }
        val data = ShortArray((rate * seconds).toInt())
        val random = Random(pad * 7919 + kit * 104729 + 17)
        var filteredNoise = 0.0
        val pitch = when (kit) { 1 -> 1.22; 2 -> .82; else -> 1.0 }
        for (i in data.indices) {
            val t = i.toDouble() / rate
            val n = random.nextDouble(-1.0, 1.0)
            val v = when (pad) {
                0 -> sin(2 * PI * ((if (kit == 2) 105.0 else 145.0) * exp(-t * 15) + 38) * pitch * t) * exp(-t * 7.5) * .92 + n * exp(-t * 85) * .1
                1 -> n * exp(-t * 17) * .58 + sin(2 * PI * 185 * t) * exp(-t * 23) * .28
                2, 3 -> {
                    filteredNoise = filteredNoise * .35 + n * .65
                    (filteredNoise * .48 + sin(2 * PI * 6130 * t) * .22 + sin(2 * PI * 8170 * t) * .18 + sin(2 * PI * 9430 * t) * .14) * exp(-t * if (pad == 2) 38 else 6)
                }
                4 -> listOf(0.0, .018, .036, .055).sumOf { off -> if (t >= off) n * exp(-(t - off) * 75) else 0.0 } * exp(-t * 7) * .42
                5, 6 -> {
                    val base = if (pad == 5) 105.0 else 185.0
                    sin(2 * PI * (base * exp(-t * 4) + base * .42) * pitch * t) * exp(-t * 8.5) * .78
                }
                else -> sin(2 * PI * 1650 * t) * exp(-t * 42) * .52 + n * exp(-t * 95) * .12
            }
            data[i] = (v * .82).coerceIn(-.98, .98).times(Short.MAX_VALUE).toInt().toShort()
        }
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(data.size * 2)
                .build()
            track.write(data, 0, data.size, AudioTrack.WRITE_BLOCKING)
            synchronized(tracks) { tracks.add(track) }
            track.play()
            handler.postDelayed({
                synchronized(tracks) { tracks.remove(track) }
                try { track.release() } catch (_: Exception) {}
            }, (data.size * 1000L / rate) + 200L)
        } catch (_: Exception) {}
    }

    private fun buildPads(parent: LinearLayout) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14))
            background = surface(0xff202d40.toInt(), gold)
        }
        panel.addView(text("DRUM MACHINE", 13f, gold, true))
        panel.addView(text("ÉRINTSD MEG A PADeket A HANGOKHOZ", 9f, 0xffb5c2d4.toInt()).apply { setPadding(0, dp(5), 0, dp(12)) })
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        kitText = text("", 10f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; background = surface(0xff34445d.toInt())
            setPadding(dp(10), dp(11), dp(10), dp(11))
            setOnClickListener { kit = (kit + 1) % 3; kitText.text = "KIT  •  " + listOf("AURA ACOUSTIC", "AURA ELECTRO", "AURA 808")[kit] + "  ↻" }
        }
        kitText.text = "KIT  •  AURA ACOUSTIC  ↻"
        controls.addView(kitText, LinearLayout.LayoutParams(0, dp(42), 1f))
        val minus = text("−", 21f, Color.WHITE, true).apply { gravity = Gravity.CENTER; background = surface(0xff34445d.toInt()); setOnClickListener { bpm = (bpm - 1).coerceAtLeast(50); bpmText.text = "$bpm BPM" } }
        val plus = text("+", 21f, Color.WHITE, true).apply { gravity = Gravity.CENTER; background = surface(0xff34445d.toInt()); setOnClickListener { bpm = (bpm + 1).coerceAtMost(220); bpmText.text = "$bpm BPM" } }
        bpmText = text("$bpm BPM", 11f, gold, true).apply { gravity = Gravity.CENTER }
        controls.addView(minus, LinearLayout.LayoutParams(dp(36), dp(42)).apply { marginStart = dp(5) })
        controls.addView(bpmText, LinearLayout.LayoutParams(dp(64), dp(42)))
        controls.addView(plus, LinearLayout.LayoutParams(dp(36), dp(42)))
        panel.addView(controls)
        for (row in 0..1) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (col in 0..3) {
                val i = row * 4 + col
                val pad = text(names[i] + "\n\n●", 10f, Color.WHITE, true).apply {
                    gravity = Gravity.CENTER; background = surface(colors[i], 0xffa0aec0.toInt())
                    setOnClickListener { sound(i); alpha = .7f; postDelayed({ alpha = 1f }, 90) }
                }
                line.addView(pad, LinearLayout.LayoutParams(0, dp(94), 1f).apply {
                    if (col > 0) marginStart = dp(5)
                })
            }
            panel.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
        }
        panel.addView(text("A hangok helyben, a telefonon generálódnak. Nincs szükség internetre vagy külső hangmintákra.", 9f, 0xff9eacc0.toInt()).apply { setPadding(dp(2), dp(10), dp(2), 0) })
        parent.addView(panel)
    }

    private fun buildSequencer(parent: LinearLayout) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = surface(0xff202d40.toInt(), gold)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(text("16-STEP SEQUENCER", 13f, gold, true), LinearLayout.LayoutParams(0, -2, 1f))
        sequenceButton = text("▶ PLAY", 11f, Color.BLACK, true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(gold); cornerRadius = dp(9).toFloat() }
            setPadding(dp(15), dp(10), dp(15), dp(10))
            setOnClickListener { if (playing) stopSequencer() else startSequencer() }
        }
        top.addView(sequenceButton)
        panel.addView(top)
        panel.addView(text("Építs ritmust a 16 lépés ki- és bekapcsolásával.", 9f, 0xffb5c2d4.toInt()).apply {
            setPadding(0, dp(6), 0, dp(10))
        })

        val horizontal = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = true
            overScrollMode = android.view.View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(text("SÁV", 8f, 0xff9eacc0.toInt(), true).apply {
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(dp(76), dp(24)))
        for (step in 0 until 16) {
            header.addView(text(if (step % 4 == 0) "${step + 1}" else "·", 8f,
                if (step % 4 == 0) gold else 0xff8290a5.toInt(), true).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(25), dp(24)))
        }
        grid.addView(header)
        val labels = listOf("KICK", "SNARE", "C.HAT", "O.HAT", "CLAP", "L.TOM", "H.TOM", "RIM")
        for (trackIndex in 0..7) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(text(labels[trackIndex], 8f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(dp(76), dp(34)))
            for (step in 0 until 16) {
                val cell = text(if (pattern[trackIndex][step]) "●" else "", 9f, Color.WHITE, true).apply {
                    gravity = Gravity.CENTER
                    setOnClickListener {
                        pattern[trackIndex][step] = !pattern[trackIndex][step]
                        updateSequencer()
                    }
                }
                stepCells[trackIndex][step] = cell
                row.addView(cell, LinearLayout.LayoutParams(dp(25), dp(34)).apply {
                    setMargins(dp(1), dp(1), dp(1), dp(1))
                })
            }
            grid.addView(row)
        }
        horizontal.addView(grid)
        panel.addView(horizontal)
        panel.addView(text("A lejátszás a beállított BPM szerint körbe-körbe fut.", 9f, 0xff9eacc0.toInt()).apply {
            setPadding(dp(2), dp(9), dp(2), 0)
        })
        parent.addView(panel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        updateSequencer()
    }

    private fun updateSequencer() {
        for (trackIndex in 0..7) {
            for (step in 0 until 16) {
                val cell = stepCells[trackIndex][step] ?: continue
                val active = pattern[trackIndex][step]
                val isPlayhead = playing && step == currentStep
                val fill = when {
                    isPlayhead && active -> gold
                    isPlayhead -> 0xff52647c.toInt()
                    active -> colors[trackIndex]
                    else -> 0xff182334.toInt()
                }
                cell.setTextColor(if (isPlayhead && active) Color.BLACK else Color.WHITE)
                cell.text = if (active) "●" else if (isPlayhead) "▏" else ""
                cell.background = GradientDrawable().apply {
                    setColor(fill)
                    cornerRadius = dp(5).toFloat()
                    setStroke(dp(1), if (isPlayhead) gold else 0xff40516a.toInt())
                }
            }
        }
    }

    private fun startSequencer() {
        playing = true
        currentStep = -1
        sequenceButton.text = "■ STOP"
        handler.removeCallbacks(sequenceTick)
        sequenceTick.run()
    }

    private fun stopSequencer() {
        playing = false
        handler.removeCallbacks(sequenceTick)
        currentStep = -1
        sequenceButton.text = "▶ PLAY"
        updateSequencer()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xff070a10.toInt()
        window.navigationBarColor = 0xff070a10.toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(15), dp(16), dp(18))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xff05080e.toInt(), 0xff192738.toInt(), 0xff05080e.toInt()))
        }
        root.addView(text("AURA  /  CREATIVE AUDIO", 10f, 0xff99aecb.toInt(), true))
        root.addView(text("REMIXER STUDIO", 28f, Color.WHITE, true).apply { setPadding(0, dp(4), 0, dp(3)) })
        root.addView(text("SAJÁT HANGZÁS. SAJÁT RITMUS. SAJÁT ZENE.", 9f, gold, true).apply { setPadding(0, 0, 0, dp(16)) })
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        buildPads(content)
        buildSequencer(content)
        content.addView(text("KÖVETKEZŐ MODULOK", 11f, gold, true).apply { setPadding(dp(3), dp(20), 0, dp(8)) })
        for ((title, desc) in listOf(
            "AI STEM SEPARATOR" to "Ének, dob, basszus és hangszer sávok szétválasztása.",
            "MULTITRACK MIXER" to "Többsávos keverés, effektek és hangexport.",
            "AURA ARRANGER" to "Automatikus kíséretek, akkordok és stílusok."
        )) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(15), dp(14), dp(15), dp(14)); background = surface(0xff1f2a3b.toInt())
                addView(text(title, 14f, Color.WHITE, true))
                addView(text(desc, 10f, 0xffb5c2d4.toInt()).apply { setPadding(0, dp(5), 0, 0) })
            }
            content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) })
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onDestroy() {
        playing = false
        handler.removeCallbacksAndMessages(null)
        audioExecutor.shutdownNow()
        synchronized(tracks) {
            tracks.forEach { try { it.stop(); it.release() } catch (_: Exception) {} }
            tracks.clear()
        }
        super.onDestroy()
    }
}
