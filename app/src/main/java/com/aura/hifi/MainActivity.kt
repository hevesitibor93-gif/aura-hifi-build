package com.aura.hifi

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.provider.MediaStore
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
    private lateinit var artworkView: ImageView
    private lateinit var vinylView: CassetteView
    private var showCover = true
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
    private data class LibrarySong(val uri: Uri, val title: String, val artist: String)
    private val librarySongs = mutableListOf<LibrarySong>()
    private var libraryDisplayLimit = 100
    private val librarySongByUri = mutableMapOf<String, LibrarySong>()
    private lateinit var libraryContainer: LinearLayout
    private var currentIndex = 0
    private lateinit var nextTrackLabel: TextView
    private lateinit var queueContainer: LinearLayout
    private val bg = Color.rgb(9, 11, 16)
    private val panel = Color.rgb(20, 24, 33)
    private val gold = Color.rgb(255, 184, 0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg; window.navigationBarColor = bg
        val allowRotation = getSharedPreferences("aurix_settings", MODE_PRIVATE).getBoolean("screen_rotation", true)
        requestedOrientation = if (allowRotation) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        buildUi()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 77)
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(7, 9, 13), Color.rgb(20, 22, 29), Color.rgb(8, 10, 14)))
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
        val brand = TextView(this).apply { text = "AURIX"; textSize = 21f; letterSpacing = 0.10f; setTextColor(Color.rgb(230, 190, 105)); typeface = Typeface.create("sans-serif-black", Typeface.BOLD) }
        val hi = TextView(this).apply { text = "  V50"; textSize = 11f; setTextColor(gold); typeface = Typeface.DEFAULT_BOLD }
        header.addView(brand); header.addView(hi); header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val live = TextView(this).apply { text = "● LIVE AUDIO"; textSize = 10f; setTextColor(Color.rgb(90, 220, 150)) }
        header.addView(live); body.addView(header)
        body.addView(label("YOUR SOUND. YOUR SPACE.", 10f, Color.rgb(143, 148, 158)).apply { letterSpacing = 0.18f; setPadding(0, dp(2), 0, dp(4)) })
        val artFrame = LinearLayout(this).apply { gravity = Gravity.CENTER; background = rounded(Color.rgb(25, 27, 34), 18); setPadding(dp(5), dp(5), dp(5), dp(5)); elevation = dp(8).toFloat() }
        artworkView = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(66, 53, 31), Color.rgb(18, 20, 26), Color.rgb(37, 31, 22))).apply { cornerRadius = dp(18).toFloat() }; setImageDrawable(null) }
        vinylView = CassetteView()
        artFrame.addView(artworkView, LinearLayout.LayoutParams(dp(224), dp(224)))
        artFrame.addView(vinylView, LinearLayout.LayoutParams(dp(224), dp(224)))
        showCover = getSharedPreferences("aurix_settings", MODE_PRIVATE).getBoolean("show_cover", true)
        applyArtworkMode()
        body.addView(artFrame, LinearLayout.LayoutParams(-1, dp(252)).apply { setMargins(dp(2), dp(4), dp(2), dp(4)) })
        applyArtworkMode()
        body.addView(label("DUAL CHANNEL MONITOR", 10f, Color.LTGRAY).apply { setPadding(0, dp(2), 0, dp(2)) })
        vu = VuView(); body.addView(vu, LinearLayout.LayoutParams(-1, dp(44)).apply { setMargins(dp(4), 0, dp(4), 0); gravity = Gravity.CENTER })
        trackLabel = label("Válassz egy zenefájlt a kezdéshez", 14f, Color.WHITE).apply { gravity = Gravity.CENTER; setPadding(dp(4), dp(3), dp(4), dp(2)); maxLines = 1 }
        body.addView(trackLabel)
        metadataLabel = label("ELŐADÓ  —  ALBUM", 11f, Color.rgb(155, 163, 177)).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(2)) }
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
        body.addView(seekBar, LinearLayout.LayoutParams(-1, dp(20)))
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
            setPadding(dp(4), dp(2), dp(4), 0)
        }
        body.addView(nextTrackLabel)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(2), 0, dp(2)) }
        playButton = button("▶ LEJÁTSZÁS", Color.rgb(35, 41, 53), Color.WHITE) { togglePlayback() }
        val previous = button("⏮", Color.rgb(35, 41, 53), Color.WHITE) { playPrevious() }
        val next = button("⏭", Color.rgb(35, 41, 53), Color.WHITE) { playNext() }
        controls.addView(previous, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(5) })
        controls.addView(playButton, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(5) })
        controls.addView(next, LinearLayout.LayoutParams(dp(36), dp(36)))
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
        val playerPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val songsPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val eqPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val settingsPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pages = listOf(playerPage, songsPage, eqPage, settingsPage)
        val pageScrolls = pages.map { page -> ScrollView(this).apply { isFillViewport = true; addView(page) } }
        // A lejátszó cockpit fekvő módban egyetlen, nem görgethető képernyő. A többi lap marad görgethető.
        pageScrolls[0].isVerticalScrollBarEnabled = false
        pageScrolls[0].overScrollMode = View.OVER_SCROLL_NEVER
        if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            pageScrolls[0].setOnTouchListener { _, event -> event.action == android.view.MotionEvent.ACTION_MOVE }
        }
        val original = (0 until body.childCount).map { body.getChildAt(it) }
        body.removeAllViews()
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (landscape) {
            playerPage.orientation = LinearLayout.HORIZONTAL
            val cockpitLeft = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), 0, dp(8), 0) }
            val cockpitRight = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(4), 0) }
            original.forEachIndexed { i, child ->
                when (i) {
                    in 0..4 -> cockpitLeft.addView(child)
                    in 5..11 -> cockpitRight.addView(child)
                    12, 13 -> songsPage.addView(child)
                    14, 15 -> eqPage.addView(child)
                    else -> settingsPage.addView(child)
                }
            }
            playerPage.addView(cockpitLeft, LinearLayout.LayoutParams(0, -2, 1.05f))
            playerPage.addView(cockpitRight, LinearLayout.LayoutParams(0, -2, 1f))
            artFrame.layoutParams = LinearLayout.LayoutParams(-1, dp(112)).apply { setMargins(dp(2), 0, dp(2), dp(2)) }
            artFrame.setPadding(dp(6), dp(6), dp(6), dp(6))
            (artworkView.layoutParams as LinearLayout.LayoutParams).apply { width = dp(96); height = dp(96) }
            (vinylView.layoutParams as LinearLayout.LayoutParams).apply { width = dp(118); height = dp(118) }
            trackLabel.textSize = 12f
            trackLabel.setPadding(dp(4), dp(5), dp(4), dp(3))
            metadataLabel.textSize = 9f
            metadataLabel.setPadding(0, 0, 0, dp(2))
            vu.layoutParams = LinearLayout.LayoutParams(-1, dp(42)).apply { setMargins(dp(4), 0, dp(4), 0) }
            seekBar.layoutParams = LinearLayout.LayoutParams(-1, dp(20))
            controls.setPadding(0, dp(2), 0, dp(3))
            controls.getChildAt(0).layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(5) }
            controls.getChildAt(1).layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginEnd = dp(5) }
            controls.getChildAt(2).layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
        } else {
            original.forEachIndexed { i, child -> when (i) { in 0..11 -> playerPage.addView(child); 12, 13 -> songsPage.addView(child); 14, 15 -> eqPage.addView(child); else -> settingsPage.addView(child) } }
        }
        songsPage.addView(button("＋ FÁJLOK KÉZI HOZZÁADÁSA", gold, Color.BLACK) { openAudio() }, LinearLayout.LayoutParams(-1, dp(48)))
        songsPage.addView(sectionTitle("ESZKÖZÖN TALÁLHATÓ ZENÉK"))
        songsPage.addView(button("KÖNYVTÁR FRISSÍTÉSE", Color.rgb(35, 41, 53), Color.WHITE) { loadMusicLibrary() }, LinearLayout.LayoutParams(-1, dp(44)))
        libraryContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded(panel, 16); setPadding(dp(10), dp(6), dp(10), dp(6)) }
        songsPage.addView(libraryContainer)
        loadMusicLibrary()
        settingsPage.addView(sectionTitle("KIJELZŐ ÉS TÁJOLÁS"))
        val rotationRow = settingRow("KÉPERNYŐFORGATÁS", "Álló / fekvő nézet")
        val rotationSwitch = Switch(this).apply {
            text = ""
            isChecked = getSharedPreferences("aurix_settings", MODE_PRIVATE).getBoolean("screen_rotation", true)
            setOnCheckedChangeListener { _, checked ->
                getSharedPreferences("aurix_settings", MODE_PRIVATE).edit().putBoolean("screen_rotation", checked).apply()
                requestedOrientation = if (checked) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
        rotationRow.addView(rotationSwitch)
        settingsPage.addView(rotationRow)
        settingsPage.addView(sectionTitle("MEGJELENÉS"))
        settingsPage.addView(button("◈  BORÍTÓ / KAZETTA", Color.rgb(35, 41, 53), Color.WHITE) { showCover = !showCover; getSharedPreferences("aurix_settings", MODE_PRIVATE).edit().putBoolean("show_cover", showCover).apply(); applyArtworkMode() }, LinearLayout.LayoutParams(-1, dp(38)))
        settingsPage.addView(sectionTitle("VU-MÉRŐ MEGJELENÉSE"))
        val vuPrefs = getSharedPreferences("aurix_settings", MODE_PRIVATE)
        val vuStyleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(13, 16, 22), 14)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val carModeButton = button("◉  AUTÓS", Color.TRANSPARENT, Color.WHITE) {}
        val simpleModeButton = button("Ⅱ  LETISZTULT", Color.TRANSPARENT, Color.WHITE) {}
        fun refreshVuStyleButtons(style: String) {
            val carSelected = style == "car"
            carModeButton.background = rounded(if (carSelected) Color.rgb(91, 65, 27) else Color.TRANSPARENT, 10)
            carModeButton.setTextColor(if (carSelected) Color.rgb(255, 207, 112) else Color.rgb(150, 158, 172))
            simpleModeButton.background = rounded(if (!carSelected) Color.rgb(31, 69, 76) else Color.TRANSPARENT, 10)
            simpleModeButton.setTextColor(if (!carSelected) Color.rgb(119, 224, 218) else Color.rgb(150, 158, 172))
        }
        carModeButton.setOnClickListener {
            vu.setStyle("car"); vuPrefs.edit().putString("vu_style", "car").apply()
            refreshVuStyleButtons("car")
        }
        simpleModeButton.setOnClickListener {
            vu.setStyle("simple"); vuPrefs.edit().putString("vu_style", "simple").apply()
            refreshVuStyleButtons("simple")
        }
        vuStyleRow.addView(carModeButton, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(3) })
        vuStyleRow.addView(simpleModeButton, LinearLayout.LayoutParams(0, dp(38), 1f))
        settingsPage.addView(vuStyleRow)
        settingsPage.addView(label("Mindkét mód: 5 sárga + 3 piros LED csatornánként.", 11f, Color.rgb(155, 163, 177)).apply { setPadding(dp(4), dp(7), 0, dp(4)) })
        settingsPage.addView(label("VU-MÉRŐ FÉNYEREJE", 11f, gold).apply { setPadding(dp(4), dp(12), 0, 0) })
        val vuBrightness = SeekBar(this).apply {
            max = 100
            progress = vuPrefs.getInt("vu_brightness", 80).coerceIn(20, 100)
            progressTintList = android.content.res.ColorStateList.valueOf(gold)
            thumbTintList = android.content.res.ColorStateList.valueOf(gold)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seek: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) { vu.setBrightness(value); vuPrefs.edit().putInt("vu_brightness", value).apply() }
                }
                override fun onStartTrackingTouch(seek: SeekBar?) {}
                override fun onStopTrackingTouch(seek: SeekBar?) {}
            })
        }
        settingsPage.addView(vuBrightness)
        val animationRow = settingRow("VU-ANIMÁCIÓ", "LED mozgás")
        val vuAnimation = Switch(this).apply {
            text = ""
            isChecked = vuPrefs.getBoolean("vu_animation", true)
            setOnCheckedChangeListener { _, checked ->
                vu.setAnimationEnabled(checked)
                vuPrefs.edit().putBoolean("vu_animation", checked).apply()
            }
        }
        animationRow.addView(vuAnimation)
        settingsPage.addView(animationRow)
        val savedVuStyle = vuPrefs.getString("vu_style", "car") ?: "car"
        vu.setStyle(savedVuStyle)
        refreshVuStyleButtons(savedVuStyle)
        vu.setBrightness(vuPrefs.getInt("vu_brightness", 80))
        vu.setAnimationEnabled(vuPrefs.getBoolean("vu_animation", true))
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val navButtons = mutableListOf<Button>()
        fun showPage(index: Int) { pageScrolls.forEachIndexed { i, v -> v.visibility = if (i == index) View.VISIBLE else View.GONE }; navButtons.forEachIndexed { i, b -> b.setTextColor(if (i == index) gold else Color.WHITE); b.background = rounded(if (i == index) Color.rgb(48, 42, 29) else Color.rgb(35, 41, 53), 12) } }
        listOf("LEJÁTSZÓ", "DALOK", "EQ", "BEÁLLÍTÁS").forEachIndexed { i, title -> val b = button(title, Color.rgb(35, 41, 53), Color.WHITE) { showPage(i) }; navButtons.add(b); nav.addView(b, LinearLayout.LayoutParams(0, dp(44), 1f)) }
        root.addView(nav)
        pageScrolls.forEach { root.addView(it, LinearLayout.LayoutParams(-1, 0, 1f)) }
        showPage(0)
        setContentView(root)
    }

    private fun applyArtworkMode() {
        if (!::artworkView.isInitialized || !::vinylView.isInitialized) return
        artworkView.visibility = if (showCover) View.VISIBLE else View.GONE
        vinylView.visibility = if (showCover) View.GONE else View.VISIBLE
        vinylView.invalidate()
    }

    private fun settingRow(title: String, subtitle: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(34, 35, 38), Color.rgb(19, 23, 30))).apply {
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.rgb(91, 69, 39))
            }
            setPadding(dp(12), dp(5), dp(10), dp(5))
            val labels = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(label(title, 11f, Color.rgb(238, 202, 137)).apply { typeface = Typeface.DEFAULT_BOLD })
            labels.addView(label(subtitle, 9f, Color.rgb(145, 153, 166)))
            addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(7) }
        }
    }

    private fun sectionTitle(s: String) = label(s, 11f, gold).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(dp(2), dp(20), 0, dp(9)) }
    private fun label(s: String, size: Float, color: Int) = TextView(this).apply { text = s; textSize = size; setTextColor(color) }
    private fun button(s: String, color: Int, textColor: Int, click: () -> Unit) = Button(this).apply {
        text = s
        textSize = 10f
        setTextColor(textColor)
        backgroundTintList = null
        isAllCaps = false
        includeFontPadding = false
        minHeight = dp(34)
        minimumHeight = dp(34)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(8), dp(4), dp(8), dp(4))
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(43, 39, 31), Color.rgb(25, 29, 37))).apply {
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), Color.rgb(116, 87, 43))
        }
        elevation = dp(3).toFloat()
        stateListAnimator = null
        setOnClickListener { click() }
    }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun hasAudioPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_AUDIO" else Manifest.permission.READ_EXTERNAL_STORAGE
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
    private fun loadMusicLibrary() {
        if (!::libraryContainer.isInitialized) return
        if (!hasAudioPermission()) {
            val permission = if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_AUDIO" else Manifest.permission.READ_EXTERNAL_STORAGE
            if (Build.VERSION.SDK_INT >= 23) requestPermissions(arrayOf(permission), 78)
            libraryContainer.removeAllViews()
            libraryContainer.addView(label("A zenetár megjelenítéséhez engedélyezd a zene- és hangfájlok hozzáférését.", 12f, Color.WHITE).apply { setPadding(dp(8), dp(12), dp(8), dp(12)) })
            return
        }
        librarySongs.clear()
        librarySongByUri.clear()
        libraryDisplayLimit = 100
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST)
        val selection = MediaStore.Audio.Media.IS_MUSIC + " != 0"
        try {
            contentResolver.query(collection, projection, selection, null, MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC")?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val title = cursor.getString(titleCol) ?: "Ismeretlen cím"
                    val artist = cursor.getString(artistCol) ?: "Ismeretlen előadó"
                    val song = LibrarySong(android.content.ContentUris.withAppendedId(collection, id), title, artist)
                    librarySongs.add(song)
                    librarySongByUri[song.uri.toString()] = song
                }
            }
        } catch (_: Exception) { toast("A zenetár beolvasása nem sikerült.") }
        renderLibrary()
    }
    private fun renderLibrary() {
        if (!::libraryContainer.isInitialized) return
        libraryContainer.removeAllViews()
        libraryContainer.addView(label("TALÁLATOK: ${librarySongs.size}  •  MEGJELENÍTVE: ${minOf(libraryDisplayLimit, librarySongs.size)}", 10f, gold).apply { setPadding(dp(6), dp(7), dp(6), dp(7)) })
        if (librarySongs.isEmpty()) {
            libraryContainer.addView(label("Nem található zene. Használd a kézi hozzáadást, vagy frissítsd a könyvtárat.", 11f, Color.GRAY).apply { setPadding(dp(6), dp(10), dp(6), dp(10)) })
            return
        }
        val end = minOf(libraryDisplayLimit, librarySongs.size)
        for (index in 0 until end) {
            val song = librarySongs[index]
            val item = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(9), dp(8), dp(9)); isClickable = true; isFocusable = true }
            item.addView(label(song.title, 12f, Color.WHITE))
            item.addView(label(song.artist, 10f, Color.rgb(150, 160, 175)))
            item.setOnClickListener {
                queue.clear()
                queue.addAll(librarySongs.map { it.uri })
                currentIndex = index
                startTrack(song.uri)
                updateNextTrack()
                toast("Zenetár: " + song.title)
            }
            libraryContainer.addView(item)
            if (index < end - 1) libraryContainer.addView(View(this).apply { setBackgroundColor(Color.rgb(43, 48, 58)) }, LinearLayout.LayoutParams(-1, dp(1)))
        }
        if (end < librarySongs.size) {
            libraryContainer.addView(button("TOVÁBBI 100 ZENE BETÖLTÉSE", gold, Color.BLACK) {
                libraryDisplayLimit = minOf(libraryDisplayLimit + 100, librarySongs.size)
                renderLibrary()
            }, LinearLayout.LayoutParams(-1, dp(46)))
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 78 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) loadMusicLibrary()
        else if (requestCode == 78) toast("A zenetár engedély nélkül nem olvasható.")
    }
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
        return librarySongByUri[uri.toString()]?.title
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "Ismeretlen cím"
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
        val visibleStart = if (currentIndex >= 100) (currentIndex / 100) * 100 else 0
        val visibleEnd = minOf(queue.size, visibleStart + 100)
        if (visibleStart > 0) queueContainer.addView(label("… ${visibleStart} korábbi szám", 10f, Color.GRAY))
        for (index in visibleStart until visibleEnd) {
            val uri = queue[index]
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
            if (index < visibleEnd - 1) {
                val line = View(this).apply { setBackgroundColor(Color.rgb(43, 48, 58)) }
                queueContainer.addView(line, LinearLayout.LayoutParams(-1, dp(1)))
            }
        }
        if (visibleEnd < queue.size) queueContainer.addView(label("… ${queue.size - visibleEnd} további szám", 10f, Color.GRAY))
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
            val descriptor = contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("A fájl nem nyitható meg.")
            player = MediaPlayer().apply {
                setDataSource(descriptor.fileDescriptor)
                descriptor.close()
                setOnPreparedListener { mp ->
                    try {
                        mp.start()
                        playButton.text = "Ⅱ SZÜNET"
                        vinylView.invalidate()
                        setupEffects(mp.audioSessionId)
                        handler.removeCallbacks(progressTask)
                        handler.post(progressTask)
                    } catch (_: Exception) {
                        toast("A lejátszás indítása nem sikerült.")
                    }
                }
                setOnErrorListener { _, what, extra ->
                    playButton.text = "▶ LEJÁTSZÁS"
                    toast("Lejátszási hiba ($what/$extra).")
                    true
                }
                setOnCompletionListener {
                    vu.setLevels(0f, 0f)
                    if (currentIndex + 1 < queue.size) {
                        currentIndex++
                        selectedUri = queue[currentIndex]
                        updateNextTrack()
                        startTrack(queue[currentIndex])
                    } else {
                        playButton.text = "▶ LEJÁTSZÁS"
                        updateNextTrack()
                    }
                }
                prepareAsync()
            }
            trackLabel.text = "Betöltés…"
        } catch (e: Exception) {
            try { player?.release() } catch (_: Exception) {}
            player = null
            playButton.text = "▶ LEJÁTSZÁS"
            toast("Nem sikerült megnyitni: " + (e.message ?: "ismeretlen hiba"))
        }
    }
    private fun togglePlayback() {
        val p = player
        if (p == null) { selectedUri?.let { startTrack(it) } ?: openAudio(); return }
        try { if (p.isPlaying) { p.pause(); playButton.text = "▶ FOLYTATÁS"; vinylView.invalidate() } else { p.start(); playButton.text = "Ⅱ SZÜNET"; vinylView.invalidate() } } catch (_: Exception) {}
    }
    private fun setupEffects(session: Int) {
        try {
            equalizer?.release(); equalizer = Equalizer(0, session).apply { enabled = true }
            bassBoost?.release(); bassBoost = BassBoost(0, session).apply { enabled = true }
            visualizer?.release()
            visualizer = Visualizer(session).apply {
                // A VU-mérő ritkább mintavételezése csökkenti a főszál terhelését.
                captureSize = Visualizer.getCaptureSizeRange()[0]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                        if (waveform != null && waveform.isNotEmpty()) {
                            var sum = 0.0
                            for (b in waveform) { val x = (b.toInt() and 0xff) - 128; sum += x * x }
                            val rms = (kotlin.math.sqrt(sum / waveform.size) / 128.0).toFloat()
                            val targetGain = if (rms > 0.006f) (0.24f / rms).coerceIn(0.7f, 18f) else vuAutoGain
                            vuAutoGain = vuAutoGain * 0.88f + targetGain * 0.12f
                            val level = (rms * vuAutoGain * 1.45f).coerceIn(0f, 1f)
                            vu.post { vu.setLevels(level, (level * 0.97f).coerceIn(0f, 1f)) }
                        }
                    }
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {}
                }, (Visualizer.getMaxCaptureRate() / 2).coerceAtLeast(1000), true, false)
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
            val cover = r.embeddedPicture
            if (cover != null) { val bitmap = android.graphics.BitmapFactory.decodeByteArray(cover, 0, cover.size); if (bitmap != null) artworkView.setImageBitmap(bitmap) }
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
            vinylView.invalidate()
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

    private inner class CassetteView : View(this@MainActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var phase = 0f
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w=width.toFloat(); val h=height.toFloat()
            if(w<=0f||h<=0f)return
            val duration=(player?.duration?:0).coerceAtLeast(0);val position=(player?.currentPosition?:0).coerceAtLeast(0)
            val progress=if(duration>0)(position.toFloat()/duration).coerceIn(0f,1f) else 0f
            val playing=try{player?.isPlaying==true}catch(_:Exception){false}
            fun fill(c:Int){paint.color=c;paint.style=Paint.Style.FILL;paint.shader=null}
            fun line(c:Int,sw:Float){paint.color=c;paint.style=Paint.Style.STROKE;paint.strokeWidth=sw}
            val shell=RectF(w*.025f,h*.045f,w*.975f,h*.955f)
            fill(Color.rgb(31,33,35));canvas.drawRoundRect(shell,h*.055f,h*.055f,paint)
            line(Color.rgb(184,147,83),h*.009f);canvas.drawRoundRect(shell,h*.055f,h*.055f,paint)
            fill(Color.rgb(62,64,62));canvas.drawRoundRect(RectF(w*.065f,h*.095f,w*.935f,h*.905f),h*.035f,h*.035f,paint)
            line(Color.rgb(112,114,108),h*.005f)
            for(i in 0..5){val x=w*(.15f+i*.14f);canvas.drawLine(x,h*.19f,x,h*.27f,paint);canvas.drawLine(x,h*.73f,x,h*.81f,paint)}
            fill(Color.rgb(12,14,15));canvas.drawRoundRect(RectF(w*.12f,h*.31f,w*.88f,h*.72f),h*.03f,h*.03f,paint)
            fill(Color.rgb(5,7,8));canvas.drawRoundRect(RectF(w*.16f,h*.345f,w*.84f,h*.685f),h*.02f,h*.02f,paint)
            val cy=h*.515f;val lx=w*.34f;val rx=w*.66f;val r=h*.105f
            for((x,amount) in listOf(lx to (1f-progress),rx to progress)){
                fill(Color.rgb(24,26,27));canvas.drawCircle(x,cy,r,paint)
                line(Color.rgb(174,177,169),h*.008f);canvas.drawCircle(x,cy,r*.94f,paint)
                fill(Color.rgb(105,108,102));canvas.drawCircle(x,cy,r*.72f,paint)
                val pack=r*(.28f+.55f*amount);fill(Color.rgb(21,23,24));canvas.drawCircle(x,cy,pack,paint)
                line(Color.rgb(197,199,187),h*.006f);canvas.drawCircle(x,cy,pack,paint)
                canvas.save();canvas.rotate(if(playing)phase else 0f,x,cy)
                for(i in 0..5){canvas.save();canvas.rotate(i*60f,x,cy);fill(Color.rgb(185,187,177));canvas.drawRoundRect(RectF(x-r*.075f,cy-r*.62f,x+r*.075f,cy-r*.22f),r*.04f,r*.04f,paint);canvas.restore()}
                canvas.restore();fill(Color.rgb(13,15,16));canvas.drawCircle(x,cy,r*.19f,paint)
            }
            line(Color.rgb(190,157,101),h*.012f)
            canvas.drawLine(lx+r*.72f,cy+r*.55f,w*.46f,h*.64f,paint);canvas.drawLine(w*.46f,h*.64f,w*.54f,h*.64f,paint);canvas.drawLine(w*.54f,h*.64f,rx-r*.72f,cy+r*.55f,paint)
            fill(Color.rgb(214,210,191));canvas.drawRoundRect(RectF(w*.22f,h*.105f,w*.78f,h*.285f),h*.012f,h*.012f,paint)
            paint.color=Color.rgb(35,40,39);paint.textAlign=Paint.Align.CENTER;paint.typeface=Typeface.create("sans-serif-black",Typeface.BOLD)
            paint.textSize=(h*.095f).coerceAtLeast(dp(10).toFloat());canvas.drawText("FE",w*.34f,h*.225f,paint)
            paint.textSize=(h*.043f).coerceAtLeast(dp(6).toFloat());paint.typeface=Typeface.DEFAULT_BOLD
            canvas.drawText("AURIX  •  TYPE I",w*.59f,h*.18f,paint);canvas.drawText("NORMAL POSITION  •  SIDE A",w*.59f,h*.235f,paint)
            for((x,y) in listOf(w*.105f to h*.205f,w*.895f to h*.205f,w*.105f to h*.795f,w*.895f to h*.795f)){
                fill(Color.rgb(18,20,21));canvas.drawCircle(x,y,h*.018f,paint);line(Color.rgb(143,145,138),h*.004f);canvas.drawCircle(x,y,h*.018f,paint)
            }
            fill(Color.rgb(190,151,87));canvas.drawRoundRect(RectF(w*.31f,h*.79f,w*.69f,h*.88f),h*.012f,h*.012f,paint)
            paint.color=Color.rgb(29,27,23);paint.textSize=(h*.043f).coerceAtLeast(dp(5).toFloat());paint.textAlign=Paint.Align.CENTER
            canvas.drawText(if(duration>0) "${(progress*100).toInt()}%  •  -${formatTime((duration-position).coerceAtLeast(0))}" else "INSERT TAPE",w*.5f,h*.85f,paint)
            if(playing&&visibility==View.VISIBLE){phase=(phase+5f)%360f;postInvalidateDelayed(40)}
        }
    }

    private inner class VuView : View(this@MainActivity) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private var left = 0f
        private var right = 0f
        private var style = "car"
        private var brightness = 80
        private var animationEnabled = true

        fun setLevels(l: Float, r: Float) {
            if (!animationEnabled) return
            left = l.coerceIn(0f, 1f)
            right = r.coerceIn(0f, 1f)
            invalidate()
        }
        fun setStyle(value: String) { style = if (value == "simple") "simple" else "car"; invalidate() }
        fun setBrightness(value: Int) { brightness = value.coerceIn(20, 100); invalidate() }
        fun setAnimationEnabled(value: Boolean) { animationEnabled = value; if (!value) { left = 0f; right = 0f }; invalidate() }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val w = width.toFloat()
            val h = height.toFloat()
            val outer = RectF(dp(2).toFloat(), dp(2).toFloat(), w - dp(2), h - dp(2))
            p.shader = null
            p.style = Paint.Style.FILL
            p.color = if (style == "car") Color.rgb(12, 15, 19) else Color.rgb(17, 23, 29)
            c.drawRoundRect(outer, dp(if (style == "car") 6 else 17).toFloat(), dp(if (style == "car") 6 else 17).toFloat(), p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = dp(1).toFloat()
            p.color = Color.argb(brightness * 2, if (style == "car") 232 else 92, if (style == "car") 166 else 202, if (style == "car") 66 else 194)
            c.drawRoundRect(outer, dp(if (style == "car") 11 else 17).toFloat(), dp(if (style == "car") 11 else 17).toFloat(), p)
            p.style = Paint.Style.FILL
            if (style == "car") {
                val inner = RectF(dp(7).toFloat(), dp(7).toFloat(), w - dp(7), h - dp(7))
                p.color = Color.rgb(19, 22, 27)
                c.drawRoundRect(inner, dp(4).toFloat(), dp(4).toFloat(), p)
                p.color = Color.rgb(95, 73, 42)
                p.strokeWidth = dp(1).toFloat()
                p.style = Paint.Style.STROKE
                c.drawRoundRect(inner, dp(7).toFloat(), dp(7).toFloat(), p)
                p.style = Paint.Style.FILL
            }

            val inset = dp(12).toFloat()
            val labelW = dp(13).toFloat()
            val barW = w - inset * 2 - labelW
            val barH = if (style == "car") dp(8).toFloat() else dp(7).toFloat()
            val top = if (style == "car") h * .27f else h * .29f
            val bottom = if (style == "car") h * .61f else h * .70f
            drawBar(c, inset + labelW, top, barW, barH, left)
            drawBar(c, inset + labelW, bottom, barW, barH, right)

            p.shader = null
            p.color = Color.rgb(185, 157, 105)
            p.textSize = dp(9).toFloat()
            p.typeface = Typeface.DEFAULT_BOLD
            c.drawText("L", inset, top + barH * .78f, p)
            c.drawText("R", inset, bottom + barH * .78f, p)

            if (style == "car") {
                p.color = Color.rgb(235, 196, 125)
                p.textSize = dp(10).toFloat()
                p.typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                p.textAlign = Paint.Align.CENTER
                c.drawText("A U R I X   //   S T E R E O", w / 2f, h - dp(5).toFloat(), p)
                p.textAlign = Paint.Align.LEFT
            } else {
                p.color = Color.rgb(104, 91, 69)
                p.strokeWidth = dp(1).toFloat()
                c.drawLine(inset + labelW, h / 2f, w - inset, h / 2f, p)
                p.color = Color.rgb(190, 160, 105)
                p.textSize = dp(8).toFloat()
                p.textAlign = Paint.Align.CENTER
                c.drawText("AURIX  •  STEREO", w / 2f, h - dp(4).toFloat(), p)
                p.textAlign = Paint.Align.LEFT
            }
        }

        private fun drawBar(c: Canvas, x: Float, y: Float, width: Float, height: Float, level: Float) {
            val segments = 8
            val gap = dp(1).toFloat()
            val segmentW = (width - gap * (segments - 1)) / segments
            val lit = (level.coerceIn(0f, 1f) * segments).toInt()
            for (i in 0 until segments) {
                val sx = x + i * (segmentW + gap)
                val active = i < lit
                val base = if (i >= 5) Color.rgb(255, 66, 48) else Color.rgb(255, 190, 45)
                p.color = if (active) Color.argb(brightness * 255 / 100, Color.red(base), Color.green(base), Color.blue(base))
                    else Color.rgb(43, 45, 49)
                c.drawRoundRect(RectF(sx, y, sx + segmentW, y + height), dp(1).toFloat(), dp(1).toFloat(), p)
            }
        }
    }
}
