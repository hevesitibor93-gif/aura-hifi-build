package com.aura.hifi

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaPlayer
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Visualizer
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.view.WindowInsets
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {
    private var player: MediaPlayer? = null
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var visualizer: Visualizer? = null
    private lateinit var vu: VuView
    private lateinit var trackLabel: TextView
    private lateinit var playButton: Button
    private lateinit var eqContainer: LinearLayout
    private lateinit var seekBar: SeekBar
    private lateinit var timeLabel: TextView
    private lateinit var metadataLabel: TextView
    private lateinit var technicalLabel: TextView
    private var showRemaining = false
    private val handler = Handler(Looper.getMainLooper())
    private val progressTask = object : Runnable { override fun run() { updateProgress(); handler.postDelayed(this, 500) } }
    private var selectedUri: Uri? = null
    private var vuAutoGain = 1f
    private val queue = mutableListOf<Uri>()
    private var currentIndex = 0
    private lateinit var nextTrackLabel: TextView
    private lateinit var queueContainer: LinearLayout
    private val bg = Color.rgb(9, 11, 16)
    private val panel = Color.rgb(20, 24, 33)
    private val gold = Color.rgb(255, 184, 0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg; window.navigationBarColor = bg
        buildUi()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 77)
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setOnApplyWindowInsetsListener { view, insets ->
                val topInset = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.statusBars()).top else insets.systemWindowInsetTop
                val bottomInset = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.navigationBars()).bottom else insets.systemWindowInsetBottom
                view.setPadding(dp(18), dp(12) + topInset, dp(18), dp(12) + bottomInset)
                insets
            }
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val brand = TextView(this).apply { text = "AURIX"; textSize = 29f; setTextColor(Color.rgb(230, 190, 105)); typeface = Typeface.create("sans-serif-black", Typeface.BOLD) }
        val hi = TextView(this).apply { text = "  V50"; textSize = 13f; setTextColor(gold); typeface = Typeface.DEFAULT_BOLD }
        header.addView(brand); header.addView(hi); header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val live = TextView(this).apply { text = "● LIVE AUDIO"; textSize = 10f; setTextColor(Color.rgb(90, 220, 150)) }
        header.addView(live); body.addView(header)
        body.addView(label("DUAL CHANNEL MONITOR", 11f, Color.LTGRAY).apply { setPadding(0, dp(4), 0, dp(10)) })
        vu = VuView(); body.addView(vu, LinearLayout.LayoutParams(-1, dp(94)))
        trackLabel = label("Válassz egy zenefájlt a kezdéshez", 14f, Color.WHITE).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(16), dp(8), dp(8)); maxLines = 2 }
        body.addView(trackLabel)
        metadataLabel = label("ELŐADÓ  —  ALBUM", 11f, Color.rgb(155, 163, 177)).apply { gravity = Gravity.CENTER; setPadding(0, dp(2), 0, dp(8)) }
        body.addView(metadataLabel)
        seekBar = SeekBar(this).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(gold)
            thumbTintList = android.content.res.ColorStateList.valueOf(gold)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) { if (fromUser) try { player?.let { if (it.duration > 0) it.seekTo((it.duration * value / 1000f).toInt()) } } catch (_: Exception) {} }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        body.addView(seekBar, LinearLayout.LayoutParams(-1, dp(24)))
        timeLabel = label("00:00  /  00:00", 11f, Color.WHITE).apply { gravity = Gravity.CENTER; setOnClickListener { showRemaining = !showRemaining; updateProgress() } }
        body.addView(timeLabel)
        technicalLabel = label("AUDIO INFO  •  —", 9f, Color.rgb(125, 137, 155)).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(6)) }
        body.addView(technicalLabel)
        nextTrackLabel = label("NEXT TRACK  •  Nincs következő szám", 10f, gold).apply {
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSelected = true
            setPadding(dp(8), dp(8), dp(8), dp(4))
        }
        body.addView(nextTrackLabel)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(14)) }
        val choose = button("ZENÉK +", gold, Color.BLACK) { openAudio() }
        playButton = button("▶ LEJÁTSZÁS", Color.rgb(35, 41, 53), Color.WHITE) { togglePlayback() }
        val previous = button("⏮", Color.rgb(35, 41, 53), Color.WHITE) { playPrevious() }
        val next = button("⏭", Color.rgb(35, 41, 53), Color.WHITE) { playNext() }
        controls.addView(previous, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(6) })
        controls.addView(choose, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        controls.addView(playButton, LinearLayout.LayoutParams(0, dp(48), 1.2f).apply { marginEnd = dp(6) })
        controls.addView(next, LinearLayout.LayoutParams(dp(48), dp(48)))
        body.addView(controls)
        body.addView(sectionTitle("LEJÁTSZÁSI SOR  •  ${queue.size} ZENESZÁM"))
        queueContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(panel, 16)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        body.addView(queueContainer)
        renderQueue()
        body.addView(sectionTitle("HANGSZÍNSZABÁLYZÓ"))
        eqContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded(panel, 18); setPadding(dp(14), dp(12), dp(14), dp(12)) }
        body.addView(eqContainer)
        body.addView(sectionTitle("HANGFORMÁLÁS"))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("HÁTTÉR KÉP", Color.rgb(35, 41, 53), Color.WHITE) { openImage() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
        actions.addView(button("EQ ALAPHELYZET", Color.rgb(35, 41, 53), Color.WHITE) { resetEq() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        body.addView(actions)
        body.addView(label("Az EQ és a basszuskiemelés az Android eszközén elérhető hanghatásoktól függ. Más alkalmazások (YouTube/Spotify) teljes rendszerhangját ez az app nem tudja garantáltan módosítani.", 11f, Color.rgb(155, 163, 177)).apply { setPadding(dp(3), dp(14), dp(3), dp(18)) })
        scroll.addView(body); root.addView(scroll); setContentView(root)
    }

    private fun sectionTitle(s: String) = label(s, 11f, gold).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(dp(2), dp(20), 0, dp(9)) }
    private fun label(s: String, size: Float, color: Int) = TextView(this).apply { text = s; textSize = size; setTextColor(color) }
    private fun button(s: String, color: Int, textColor: Int, click: () -> Unit) = Button(this).apply { text = s; textSize = 11f; setTextColor(textColor); background = rounded(color, 13); isAllCaps = false; setOnClickListener { click() } }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun openAudio() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "audio/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true) }
        startActivityForResult(i, 1)
    }
    private fun openImage() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" }
        startActivityForResult(i, 2)
    }
    @Deprecated("Legacy callback used for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode == 2) {
            val uri = data.data ?: return
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); window.decorView.background = android.graphics.drawable.Drawable.createFromStream(contentResolver.openInputStream(uri), null) } catch (_: Exception) { toast("A háttérkép nem tölthető be.") }
        } else if (requestCode == 1) {
            val chosen = mutableListOf<Uri>()
            val clip = data.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { chosen.add(it) }
            } else data.data?.let { chosen.add(it) }
            if (chosen.isEmpty()) return
            queue.clear()
            queue.addAll(chosen.distinct())
            queue.forEach { uri -> try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {} }
            currentIndex = 0
            selectedUri = queue.first()
            updateNextTrack()
            renderQueue()
            startTrack(queue[currentIndex])
            toast("${queue.size} szám a várólistában")
        }
    }
    private fun metadataTitle(uri: Uri): String {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Ismeretlen cím"
        } catch (_: Exception) {
            uri.lastPathSegment?.substringAfterLast('/') ?: "Ismeretlen cím"
        } finally { try { retriever.release() } catch (_: Exception) {} }
    }
    private fun updateNextTrack() {
        val next = queue.getOrNull(currentIndex + 1)
        nextTrackLabel.text = if (next != null) "NEXT TRACK  •  ${metadataTitle(next)}" else "NEXT TRACK  •  Nincs következő szám"
        renderQueue()
    }
    private fun renderQueue() {
        if (!::queueContainer.isInitialized) return
        queueContainer.removeAllViews()
        queueContainer.addView(label("ÖSSZESEN: ${queue.size} ZENESZÁM  •  ${if (queue.isEmpty()) 0 else currentIndex + 1} / ${queue.size}", 10f, gold).apply { setPadding(dp(6), dp(7), dp(6), dp(7)) })
        if (queue.isEmpty()) {
            queueContainer.addView(label("Még nincs zeneszám a listában.", 11f, Color.GRAY).apply { setPadding(dp(6), dp(10), dp(6), dp(10)) })
            return
        }
        queue.forEachIndexed { index, uri ->
            val item = TextView(this).apply {
                text = (if (index == currentIndex) "▶  " else "${index + 1}.  ") + metadataTitle(uri)
                textSize = 12f
                setTextColor(if (index == currentIndex) gold else Color.rgb(220, 225, 235))
                setPadding(dp(8), dp(11), dp(8), dp(11))
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                setOnClickListener {
                    currentIndex = index
                    startTrack(queue[currentIndex])
                    updateNextTrack()
                }
            }
            queueContainer.addView(item)
            if (index < queue.lastIndex) {
                val line = View(this).apply { setBackgroundColor(Color.rgb(43, 48, 58)) }
                queueContainer.addView(line, LinearLayout.LayoutParams(-1, dp(1)))
            }
        }
    }
    private fun playPrevious() {
        if (queue.isEmpty()) return
        currentIndex = if (currentIndex > 0) currentIndex - 1 else 0
        startTrack(queue[currentIndex])
    }
    private fun playNext() {
        if (queue.isEmpty()) return
        if (currentIndex < queue.lastIndex) currentIndex++
        startTrack(queue[currentIndex])
    }
    private fun startTrack(uri: Uri) {
        renderQueue()
        releaseAudio()
        try {
            selectedUri = uri
            readMetadata(uri)
            updateNextTrack()
            player = MediaPlayer().apply {
                setDataSource(this@MainActivity, uri)
                setOnPreparedListener { mp ->
                    mp.start(); playButton.text = "Ⅱ SZÜNET"
                    setupEffects(mp.audioSessionId)
                    handler.removeCallbacks(progressTask); handler.post(progressTask)
                }
                setOnCompletionListener { vu.setLevels(0f, 0f); if (currentIndex + 1 < queue.size) { currentIndex++; selectedUri = queue[currentIndex]; updateNextTrack(); startTrack(queue[currentIndex]) } else { playButton.text = "▶ LEJÁTSZÁS"; updateNextTrack() } }
                prepareAsync()
            }
            trackLabel.text = "Betöltés…"
        } catch (e: Exception) { toast("Nem sikerült megnyitni ezt a zenét.") }
    }
    private fun togglePlayback() {
        val p = player
        if (p == null) { selectedUri?.let { startTrack(it) } ?: openAudio(); return }
        try { if (p.isPlaying) { p.pause(); playButton.text = "▶ FOLYTATÁS" } else { p.start(); playButton.text = "Ⅱ SZÜNET" } } catch (_: Exception) {}
    }
    private fun setupEffects(session: Int) {
        try {
            equalizer?.release(); equalizer = Equalizer(0, session).apply { enabled = true }
            bassBoost?.release(); bassBoost = BassBoost(0, session).apply { enabled = true }
            visualizer?.release()
            visualizer = Visualizer(session).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                        if (waveform != null && waveform.isNotEmpty()) {
                            var sum = 0.0
                            for (b in waveform) { val x = (b.toInt() and 0xff) - 128; sum += x * x }
                            val rms = (kotlin.math.sqrt(sum / waveform.size) / 128.0).toFloat()
                            val targetGain = if (rms > 0.006f) (0.24f / rms).coerceIn(0.7f, 18f) else vuAutoGain
                            vuAutoGain = vuAutoGain * 0.88f + targetGain * 0.12f
                            val level = (rms * vuAutoGain * 1.45f).coerceIn(0f, 1f)
                            runOnUiThread { vu.setLevels(level, (level * 0.97f).coerceIn(0f, 1f)) }
                        }
                    }
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {}
                }, Visualizer.getMaxCaptureRate() / 2, true, false)
                enabled = true
            }
            renderEqualizer()
        } catch (_: Exception) { toast("Az eszköz hanghatásai korlátozottak lehetnek.") }
    }
    private fun renderEqualizer() {
        eqContainer.removeAllViews()
        val eq = equalizer ?: return
        val count = eq.numberOfBands.toInt()
        val range = eq.bandLevelRange
        for (i in 0 until count) {
            val freq = try { eq.getCenterFreq(i.toShort()) / 1000 } catch (_: Exception) { 0 }
            val rowTitle = when {
                freq < 100 -> "${freq} Hz"
                freq >= 1000 -> "${freq / 1000f} kHz"
                else -> "$freq Hz"
            }
            addSlider(eqContainer, rowTitle, range[0].toInt(), range[1].toInt(), 0) { v -> try { eq.setBandLevel(i.toShort(), v.toShort()) } catch (_: Exception) {} }
        }
        if (count == 0) eqContainer.addView(label("Az eszköz nem kínál EQ-sávokat.", 12f, Color.WHITE))
        eqContainer.addView(label("MÉLYHANG KIEMELÉS", 11f, gold).apply { setPadding(0, dp(12), 0, 0) })
        addSlider(eqContainer, "Bass Boost", 0, 1000, 0) { v -> try { bassBoost?.setStrength(v.toShort()) } catch (_: Exception) {} }
        addSlider(eqContainer, "Lejátszó hangerő", 50, 150, 100) { v -> player?.setVolume(v / 100f, v / 100f) }
        eqContainer.addView(label("Az EQ-sávok száma a telefon Android-audio rendszerétől függ.", 10f, Color.GRAY).apply { setPadding(0, dp(8), 0, 0) })
    }
    private fun addSlider(parent: LinearLayout, title: String, minV: Int, maxV: Int, initial: Int, onValue: (Int) -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(5), 0, dp(4)) }
        val t = label(title, 12f, Color.rgb(220, 225, 235))
        val seek = SeekBar(this).apply { max = maxV - minV; progress = (initial - minV).coerceIn(0, maxV - minV); progressTintList = android.content.res.ColorStateList.valueOf(gold); thumbTintList = android.content.res.ColorStateList.valueOf(gold); setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) { if (user) onValue(p + minV) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        }) }
        row.addView(t); row.addView(seek); parent.addView(row)
    }

    private fun readMetadata(uri: Uri) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(this, uri)
            trackLabel.text = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: "Ismeretlen cím"
            val artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Ismeretlen előadó"
            val album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "Ismeretlen album"
            metadataLabel.text = "$artist  •  $album"
            val bitrate = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()?.div(1000)
            technicalLabel.text = "AUDIO INFO  •  " + (bitrate?.let { "$it kbps" } ?: "bitráta —")
        } catch (_: Exception) {
            trackLabel.text = uri.lastPathSegment ?: "Kiválasztott zenefájl"
            metadataLabel.text = "Ismeretlen előadó  •  Ismeretlen album"
            technicalLabel.text = "AUDIO INFO  •  nem érhető el"
        } finally { try { r.release() } catch (_: Exception) {} }
    }
    private fun formatTime(ms: Int): String {
        val total = ms.coerceAtLeast(0) / 1000
        return "%02d:%02d".format(total / 60, total % 60)
    }
    private fun updateProgress() {
        val p = player ?: return
        try {
            val duration = p.duration; val position = p.currentPosition
            if (duration > 0) seekBar.progress = (position * 1000L / duration).toInt()
            timeLabel.text = if (showRemaining) "-${formatTime(duration-position)}  /  ${formatTime(duration)}" else "${formatTime(position)}  /  ${formatTime(duration)}"
        } catch (_: Exception) {}
    }

    private fun resetEq() {
        try { equalizer?.let { e -> for (i in 0 until e.numberOfBands.toInt()) e.setBandLevel(i.toShort(), 0) }; bassBoost?.setStrength(0) } catch (_: Exception) {}
        toast("EQ visszaállítva, ahol a készülék ezt engedi.")
    }
    private fun releaseAudio() {
        handler.removeCallbacks(progressTask)
        try { visualizer?.release() } catch (_: Exception) {}; visualizer = null
        try { equalizer?.release() } catch (_: Exception) {}; equalizer = null
        try { bassBoost?.release() } catch (_: Exception) {}; bassBoost = null
        try { player?.release() } catch (_: Exception) {}; player = null
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    override fun onDestroy() { releaseAudio(); super.onDestroy() }

    private inner class VuView : View(this@MainActivity) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private var left = 0f; private var right = 0f
        fun setLevels(l: Float, r: Float) { left = l; right = r; invalidate() }
        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val w = width.toFloat(); val h = height.toFloat(); val gap = dp(12).toFloat(); val leftPad = dp(8).toFloat(); val rightPad = dp(8).toFloat()
            p.color = panel; c.drawRoundRect(RectF(0f, 0f, w, h), dp(18).toFloat(), dp(18).toFloat(), p)
            val labelW = dp(24).toFloat(); val barX = leftPad + labelW; val barW = w - barX - rightPad; val barH = dp(13).toFloat()
            val y1 = h * .40f; val y2 = h * .72f
            drawBar(c, "L", barX, y1, barW, barH, left)
            drawBar(c, "R", barX, y2, barW, barH, right)
            p.color = Color.rgb(145, 153, 168); p.textSize = dp(10).toFloat(); p.typeface = Typeface.DEFAULT_BOLD
            c.drawText("L", leftPad, y1 + barH * .65f, p); c.drawText("R", leftPad, y2 + barH * .65f, p)
            p.color = Color.rgb(105, 115, 132); p.textSize = dp(9).toFloat(); p.typeface = Typeface.DEFAULT
            c.drawText("STEREO  •  LIVE", barX, dp(16).toFloat(), p)
        }
        private fun drawBar(c: Canvas, name: String, x: Float, y: Float, width: Float, height: Float, level: Float) {
            val segments = 48
            val gap = dp(1).toFloat()
            val segmentW = (width - gap * (segments - 1)) / segments
            val lit = (level.coerceIn(0f, 1f) * segments).toInt()
            for (i in 0 until segments) {
                val sx = x + i * (segmentW + gap)
                p.shader = null
                p.color = if (i < lit) {
                    if (i >= segments * 0.82f) Color.rgb(255, 55, 42) else Color.rgb(255, 195, 20)
                } else Color.rgb(39, 43, 50)
                c.drawRoundRect(RectF(sx, y, sx + segmentW, y + height), height / 2, height / 2, p)
            }
        }
    }
}
