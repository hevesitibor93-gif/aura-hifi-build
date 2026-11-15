package com.aura.hifi

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
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** Standalone AURA studio screen. Drum synthesis is isolated from the existing player audio engine. */
class AuraRemixerActivity : Activity() {
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private val gold = Color.rgb(255, 190, 82)
    private val activeTracks = mutableSetOf<AudioTrack>()
    private val handler = Handler(Looper.getMainLooper())
    private var kitIndex = 0
    private var bpm = 120
    private var bpmLabel: TextView? = null
    private val kitNames = listOf("AURA ACOUSTIC", "AURA ELECTRO", "AURA 808")
    private val padNames = listOf("KICK", "SNARE", "CLOSED HAT", "OPEN HAT", "CLAP", "LOW TOM", "HIGH TOM", "RIM")
    private val padColors = listOf(
        Color.rgb(174, 91, 54), Color.rgb(166, 75, 83), Color.rgb(53, 133, 147), Color.rgb(53, 133, 147),
        Color.rgb(130, 91, 166), Color.rgb(75, 111, 158), Color.rgb(75, 111, 158), Color.rgb(166, 133, 68)
    )

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun panel(title: String, detail: String, state: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(15))
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(31, 42, 58), Color.rgb(15, 21, 32))
        ).apply {
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), Color.rgb(91, 108, 133))
        }
        addView(label(title, 16f, Color.WHITE, true))
        addView(label(detail, 11f, Color.rgb(185, 196, 211)).apply { setPadding(0, dp(5), 0, dp(9)) })
        addView(label(state, 9f, gold, true))
    }

    private fun rounded(color: Int, stroke: Int = Color.rgb(92, 111, 137)) =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(color, Color.rgb(17, 24, 36))).apply {
            cornerRadius = dp(13).toFloat()
            setStroke(dp(1), stroke)
        }

    private fun playPad(index: Int) {
        val sampleRate = 44100
        val samples = makeDrumSample(index, sampleRate, kitIndex)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
        } catch (_: Exception) {
            return
        }
        try {
            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            synchronized(activeTracks) { activeTracks.add(track) }
            track.play()
            handler.postDelayed({
                synchronized(activeTracks) { activeTracks.remove(track) }
                try { track.release() } catch (_: Exception) {}
            }, (samples.size * 1000L / sampleRate) + 180L)
        } catch (_: Exception) {
            try { track.release() } catch (_: Exception) {}
        }
    }

    /** Small synthesized one-shot drum kit; no downloaded samples or external service required. */
    private fun makeDrumSample(pad: Int, rate: Int, kit: Int): ShortArray {
        val duration = when (pad) {
            0 -> 0.48; 1 -> 0.34; 2 -> 0.12; 3 -> 0.62; 4 -> 0.30; 5 -> 0.42; 6 -> 0.30; else -> 0.16
        }
        val count = (duration * rate).toInt()
        val result = ShortArray(count)
        val random = Random(pad * 7919 + kit * 104729 + 17)
        var noiseState = 0.0
        val kitPitch = when (kit) { 1 -> 1.22; 2 -> 0.82; else -> 1.0 }
        for (i in 0 until count) {
            val t = i.toDouble() / rate
            val progress = i.toDouble() / count
            val noise = random.nextDouble(-1.0, 1.0)
            val value = when (pad) {
                0 -> { // Kick: falling sine sweep with a short click.
                    val f0 = if (kit == 2) 105.0 else 145.0
                    val freq = (f0 * exp(-t * (if (kit == 1) 12.0 else 16.0)) + 38.0) * kitPitch
                    (sin(2.0 * PI * freq * t) * exp(-t * 7.5) * 0.92) + (noise * exp(-t * 85.0) * 0.10)
                }
                1 -> { // Snare: noise body plus a tuned shell.
                    noise * exp(-t * 17.0) * 0.58 + sin(2.0 * PI * 185.0 * t) * exp(-t * 23.0) * 0.28
                }
                2, 3 -> { // Metallic hats: inharmonic partials with fast or open decay.
                    noiseState = noiseState * 0.35 + noise * 0.65
                    val decay = if (pad == 2) 38.0 else 6.0
                    val metal = sin(2.0 * PI * 6130.0 * t) * 0.22 +
                        sin(2.0 * PI * 8170.0 * t) * 0.18 +
                        sin(2.0 * PI * 9430.0 * t) * 0.14
                    (noiseState * 0.48 + metal) * exp(-t * decay)
                }
                4 -> { // Clap: several tight noise bursts.
                    val bursts = listOf(0.0, 0.018, 0.036, 0.055).sumOf { offset ->
                        if (t >= offset) noise * exp(-(t - offset) * 75.0) else 0.0
                    }
                    bursts * exp(-t * 7.0) * 0.42
                }
                5, 6 -> { // Toms: descending tuned tones.
                    val base = if (pad == 5) 105.0 else 185.0
                    val freq = (base * exp(-t * 4.0) + base * 0.42) * kitPitch
                    sin(2.0 * PI * freq * t) * exp(-t * 8.5) * 0.78
                }
                else -> { // Rim: short bright wooden click.
                    sin(2.0 * PI * 1650.0 * t) * exp(-t * 42.0) * 0.52 + noise * exp(-t * 95.0) * 0.12
                }
            }
            val normalized = (value * 0.88).coerceIn(-0.98, 0.98)
            result[i] = (normalized * Short.MAX_VALUE).toInt().toShort()
        }
        return result
    }

    private fun refreshKitLabel(view: TextView) {
        view.text = "KIT  •  ${kitNames[kitIndex]}   ↻"
    }

    private fun addDrumMachine(parent: LinearLayout) {
        val machine = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(Color.rgb(24, 32, 45), Color.rgb(104, 119, 143))
        }
        machine.addView(label("DRUM MACHINE", 12f, gold, true))
        machine.addView(label("ÉRINTSD MEG A PADeket A HANGOK MEGSZÓLALTATÁSÁHOZ", 9f, Color.rgb(175, 188, 207)).apply {
            setPadding(0, dp(4), 0, dp(12))
        })
        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val kitButton = label("", 10f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(56, 67, 87), gold)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { kitIndex = (kitIndex + 1) % kitNames.size; refreshKitLabel(this) }
        }
        refreshKitLabel(kitButton)
        topRow.addView(kitButton, LinearLayout.LayoutParams(0, dp(40), 1f))
        topRow.addView(label("TAP TEMPO", 9f, Color.rgb(155, 171, 192), true).apply {
            gravity = Gravity.CENTER; setPadding(dp(10), 0, dp(5), 0)
        })
        val minus = label("−", 20f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; background = rounded(Color.rgb(48, 58, 76)); setOnClickListener { bpm = (bpm - 1).coerceAtLeast(50); bpmLabel?.text = "$bpm BPM" }
        }
        val plus = label("+", 20f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; background = rounded(Color.rgb(48, 58, 76)); setOnClickListener { bpm = (bpm + 1).coerceAtMost(220); bpmLabel?.text = "$bpm BPM" }
        }
        bpmLabel = label("$bpm BPM", 11f, gold, true).apply { gravity = Gravity.CENTER }
        topRow.addView(minus, LinearLayout.LayoutParams(dp(38), dp(40)).apply { marginStart = dp(5) })
        topRow.addView(bpmLabel, LinearLayout.LayoutParams(dp(64), dp(40)))
        topRow.addView(plus, LinearLayout.LayoutParams(dp(38), dp(40)))
        machine.addView(topRow)

        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, 0) }
        for (row in 0..1) {
            val rowView = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (column in 0..3) {
                val index = row * 4 + column
                val pad = label("${padNames[index]}\n\n●", 11f, Color.WHITE, true).apply {
                    gravity = Gravity.CENTER
                    background = rounded(padColors[index], Color.rgb(145, 158, 180))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        playPad(index)
                        alpha = 0.72f
                        postDelayed({ alpha = 1f }, 85)
                    }
                }
                rowView.addView(pad, LinearLayout.LayoutParams(0, dp(94), 1f).apply {
                    if (column > 0) marginStart = dp(6)
                    if (column < 3) marginEnd = dp(2)
                })
            }
            grid.addView(rowView, LinearLayout.LayoutParams(-1, -2).apply {
                if (row == 0) bottomMargin = dp(6)
            })
        }
        machine.addView(grid)
        machine.addView(label("A hangok helyben, a telefonon generálódnak. Külső AI vagy internet nem szükséges a dobpadekhez.", 9f, Color.rgb(151, 165, 185)).apply {
            setPadding(dp(2), dp(10), dp(2), 0)
        })
        parent.addView(machine, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(7, 10, 16)
        window.navigationBarColor = Color.rgb(7, 10, 16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(18))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(5, 8, 14), Color.rgb(19, 27, 40), Color.rgb(5, 8, 14))
            )
        }
        root.addView(label("‹  VISSZA A ZENETÁRHOZ", 11f, gold, true).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(7), dp(4), dp(15))
            setOnClickListener { finish() }
        })
        root.addView(label("AURA  /  CREATIVE AUDIO", 10f, Color.rgb(153, 174, 204), true))
        root.addView(label("REMIXER STUDIO", 27f, Color.WHITE, true).apply { setPadding(0, dp(3), 0, dp(3)) })
        root.addView(label("SAJÁT HANGZÁS. SAJÁT RITMUS. SAJÁT ZENE.", 9f, gold, true).apply { setPadding(0, 0, 0, dp(15)) })
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        addDrumMachine(list)
        list.addView(panel(
            "AI STEM SEPARATOR",
            "MP3 dalok szétválasztása énekre, dobra, basszusra és egyéb hangszerekre.",
            "KÖVETKEZŐ FEJLESZTÉSI LÉPÉS"
        ), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        list.addView(panel(
            "MULTITRACK MIXER",
            "Többsávos keverés, hangerő, mute, solo, effektek és export.",
            "A STÚDIÓ KÖZPONTI EGYSÉGE"
        ), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        list.addView(panel(
            "AURA ARRANGER",
            "Automatikus kíséretek, akkordkövetés, stílusok és hangszerbankok.",
            "KÜLÖN MODULKÉNT TERVEZVE"
        ))
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        synchronized(activeTracks) {
            activeTracks.forEach { try { it.stop(); it.release() } catch (_: Exception) {} }
            activeTracks.clear()
        }
        super.onDestroy()
    }
}
