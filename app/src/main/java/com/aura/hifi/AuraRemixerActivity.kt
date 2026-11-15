package com.aura.hifi

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Standalone studio screen; the existing player and audio engine remain untouched. */
class AuraRemixerActivity : Activity() {
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private val gold = Color.rgb(255, 190, 82)
    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun module(title: String, detail: String, state: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(15))
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(31, 42, 58), Color.rgb(15, 21, 32))).apply {
            cornerRadius = dp(16).toFloat(); setStroke(dp(1), Color.rgb(91, 108, 133))
        }
        addView(label(title, 16f, Color.WHITE, true))
        addView(label(detail, 11f, Color.rgb(185, 196, 211)).apply { setPadding(0, dp(5), 0, dp(9)) })
        addView(label(state, 9f, gold, true))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(7, 10, 16); window.navigationBarColor = Color.rgb(7, 10, 16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(18))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(5, 8, 14), Color.rgb(19, 27, 40), Color.rgb(5, 8, 14)))
        }
        root.addView(label("‹  VISSZA A ZENETÁRHOZ", 11f, gold, true).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(7), dp(4), dp(15)); setOnClickListener { finish() }
        })
        root.addView(label("AURA  /  CREATIVE AUDIO", 10f, Color.rgb(153, 174, 204), true))
        root.addView(label("REMIXER STUDIO", 27f, Color.WHITE, true).apply { setPadding(0, dp(3), 0, dp(3)) })
        root.addView(label("SAJÁT HANGZÁS. SAJÁT RITMUS. SAJÁT ZENE.", 9f, gold, true).apply { setPadding(0, 0, 0, dp(18)) })
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(module("DRUMS  /  INSTRUMENTS", "Dobpadek, basszusok, szintetizátorok, zongora és egyéb hangszerek.", "KÖVETKEZŐ FEJLESZTÉSI LÉPÉS"), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        list.addView(module("AI STEM SEPARATOR", "MP3 dalok szétválasztása énekre, dobra, basszusra és egyéb hangszerekre.", "NYÍLT FORRÁSÚ MOTOR VIZSGÁLATA"), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        list.addView(module("MULTITRACK MIXER", "Többsávos keverés, hangerő, mute, solo, effektek és export.", "A STÚDIÓ KÖZPONTI EGYSÉGE"), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        list.addView(module("AURA ARRANGER", "Automatikus kíséretek, akkordkövetés, stílusok és hangszerbankok.", "KÜLÖN MODULKÉNT TERVEZVE"))
        scroll.addView(list); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }
}