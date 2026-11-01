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
    private var sideVuLeft: VuView? = null
    private var sideVuRight: VuView? = null
    private var centerAurixVu: AurixVuView? = null
    private lateinit var artworkView: ImageView
    private lateinit var vinylView: VinylView
    private var showCover = true
    private lateinit var trackLabel: TextView
    private lateinit var playButton: Button
    private lateinit var eqContainer: LinearLayout
    private lateinit var seekBar: SeekBar
    private lateinit var timeLabel: TextView
    private lateinit var metadataLabel: TextView
    private lateinit var albumLabel: TextView
    private lateinit var technicalLabel: TextView
    private var showRemaining = false
    private val handler = Handler(Looper.getMainLooper())
    private val progressTask = object : Runnable { override fun run() { updateProgress(); handler.postDelayed(this, 500) } }
    private var selectedUri: Uri? = null
    private var vuAutoGain = 1f
    private var vuSmoothedLevel = 0f
    private val queue = mutableListOf<Uri>()
    private data class LibrarySong(val uri: Uri, val title: String, val artist: String, val album: String, val folder: String, val dateAdded: Long)
    private val librarySongs = mutableListOf<LibrarySong>()
    private var librarySortMode = "Név szerint"
    private var openLibraryPage: (() -> Unit)? = null
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

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // A lejátszó és a metaadatok maradjanak meg tájolásváltáskor is.
        val savedTitle = if (::trackLabel.isInitialized) trackLabel.text.toString() else ""
        val savedArtist = if (::metadataLabel.isInitialized) metadataLabel.text.toString() else ""
        val savedAlbum = if (::albumLabel.isInitialized) albumLabel.text.toString() else ""
        buildUi()
        trackLabel.text = savedTitle
        metadataLabel.text = savedArtist
        albumLabel.text = savedAlbum
    }


    private fun setVuActive(value: Boolean) {
        vu.setActive(value); sideVuLeft?.setActive(value); sideVuRight?.setActive(value); centerAurixVu?.setActive(value)
    }
    private fun setVuLevels(left: Float, right: Float) {
        vu.setLevels(left, right); sideVuLeft?.setLevels(left, right); sideVuRight?.setLevels(left, right); centerAurixVu?.setLevels(left, right)
    }
    private fun setVuStyle(value: String) {
        vu.setStyle(value); sideVuLeft?.setStyle(value); sideVuRight?.setStyle(value)
    }
    private fun setVuBrightness(value: Int) {
        vu.setBrightness(value); sideVuLeft?.setBrightness(value); sideVuRight?.setBrightness(value); centerAurixVu?.setBrightness(value)
    }
    private fun setVuAnimation(value: Boolean) {
        vu.setAnimationEnabled(value); sideVuLeft?.setAnimationEnabled(value); sideVuRight?.setAnimationEnabled(value)
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
        val brand = TextView(this).apply { text = "AURIX"; textSize = 23f; letterSpacing = 0.10f; setTextColor(Color.rgb(230, 190, 105)); typeface = Typeface.create("sans-serif-black", Typeface.BOLD) }
        val hi = TextView(this).apply { text = "  V50"; textSize = 12f; setTextColor(gold); typeface = Typeface.DEFAULT_BOLD }
        header.addView(brand); header.addView(hi); header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        body.addView(header)
        val artFrame = LinearLayout(this).apply { gravity = Gravity.CENTER; background = rounded(Color.rgb(25, 27, 34), 18); setPadding(dp(5), dp(5), dp(5), dp(5)); elevation = dp(8).toFloat() }
        artworkView = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(66, 53, 31), Color.rgb(18, 20, 26), Color.rgb(37, 31, 22))).apply { cornerRadius = dp(18).toFloat() }; setImageDrawable(null) }
        vinylView = VinylView()
        artFrame.addView(artworkView, LinearLayout.LayoutParams(dp(224), dp(224)))
        artFrame.addView(vinylView, LinearLayout.LayoutParams(dp(224), dp(224)))
        showCover = getSharedPreferences("aurix_settings", MODE_PRIVATE).getBoolean("show_cover", true)
        applyArtworkMode()
        body.addView(artFrame, LinearLayout.LayoutParams(-1, dp(252)).apply { setMargins(dp(2), dp(4), dp(2), dp(4)) })
        applyArtworkMode()
        vu = VuView(); body.addView(vu, LinearLayout.LayoutParams(-1, dp(44)).apply { setMargins(dp(4), 0, dp(4), 0); gravity = Gravity.CENTER })
        trackLabel = label("", 18f, Color.rgb(255, 190, 75)).apply { gravity = Gravity.CENTER; setPadding(dp(4), dp(3), dp(4), dp(2)); maxLines = 1; isClickable = true; setOnClickListener { openLibraryPage?.invoke() } }
        body.addView(trackLabel)
        metadataLabel = label("", 12f, Color.rgb(80, 165, 255)).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, 0); maxLines = 1 }
        body.addView(metadataLabel)
        albumLabel = label("", 10f, Color.rgb(155, 205, 145)).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(2)); maxLines = 1 }
        body.addView(albumLabel)
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
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8)) }
        playButton = neonRoundButton("Ⅱ", 58, true) { togglePlayback() }
        val previous = neonRoundButton("◀", 48, false) { playPrevious() }
        val next = neonRoundButton("▶", 48, false) { playNext() }
        controls.addView(previous, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(22) })
        controls.addView(playButton, LinearLayout.LayoutParams(dp(58), dp(58)).apply { marginEnd = dp(22) })
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
        val playerPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(4, 7, 12), Color.rgb(38, 42, 51), Color.rgb(9, 12, 18), Color.rgb(3, 5, 9))).apply {
                cornerRadius = dp(30).toFloat()
                setStroke(dp(2), Color.rgb(103, 111, 124))
            }
            elevation = dp(18).toFloat()
        }
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
            playerPage.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(3, 5, 9), Color.rgb(48, 52, 61), Color.rgb(13, 17, 24), Color.rgb(2, 4, 8))).apply {
                cornerRadius = dp(26).toFloat()
                setStroke(dp(3), Color.rgb(117, 125, 137))
            }
            playerPage.setPadding(dp(8), dp(7), dp(8), dp(7))
            fun makeSidePanel(reverse: Boolean) = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(3), dp(5), dp(3), dp(5))
                background = GradientDrawable(
                    if (reverse) GradientDrawable.Orientation.RIGHT_LEFT else GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(Color.rgb(34, 39, 48), Color.rgb(5, 8, 13))
                ).apply { cornerRadius = dp(18).toFloat(); setStroke(dp(1), Color.rgb(83, 94, 110)) }
            }
            val leftPanel = makeSidePanel(false)
            val rightPanel = makeSidePanel(true)
            val headUnit = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(3), dp(10), dp(3))
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.rgb(5, 9, 15), Color.rgb(20, 26, 36), Color.rgb(4, 7, 12))).apply {
                    cornerRadius = dp(17).toFloat()
                    setStroke(dp(2), Color.rgb(104, 126, 149))
                }
                elevation = dp(10).toFloat()
            }
            original.forEachIndexed { i, child ->
                when (i) {
                    in 0..10 -> headUnit.addView(child)
                    11, 12 -> songsPage.addView(child)
                    13, 14 -> eqPage.addView(child)
                    else -> settingsPage.addView(child)
                }
            }
            leftPanel.addView(label("L", 11f, Color.rgb(255, 190, 65)).apply { gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD })
            sideVuLeft = VuView(true).apply { setStyle("car"); setBrightness(getSharedPreferences("aurix_settings", MODE_PRIVATE).getInt("vu_brightness", 80)); setAnimationEnabled(getSharedPreferences("aurix_settings", MODE_PRIVATE).getBoolean("vu_animation", true)); setActive(player?.isPlaying == true) }
            leftPanel.addView(sideVuLeft, LinearLayout.LayoutParams(-1, dp(82)).apply { setMargins(dp(2), dp(7), dp(2), dp(7)) })
            leftPanel.addView(neonRoundButton("◀", 42, false) { playPrevious() },
                LinearLayout.LayoutParams(dp(42), dp(42)).apply { setMargins(0, dp(9), 0, dp(9)) })
            leftPanel.addView(neonRoundButton("−", 42, false) { player?.setVolume(0.65f, 0.65f) },
                LinearLayout.LayoutParams(dp(42), dp(42)).apply { setMargins(0, dp(7), 0, dp(7)) })
            leftPanel.addView(label("VOL", 8f, Color.LTGRAY).apply { gravity = Gravity.CENTER })
            rightPanel.addView(label("R", 11f, Color.rgb(255, 190, 65)).apply { gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD })
            sideVuRight = VuView(true).apply { setStyle("car"); setBrightness(getSharedPreferences("aurix_settings", MODE_PRIVATE).getInt("vu_brightness", 80)); setAnimationEnabled(getSharedPreferences("aurix_settings", MODE_PRIVATE).getBoolean("vu_animation", true)); setActive(player?.isPlaying == true) }
            rightPanel.addView(sideVuRight, LinearLayout.LayoutParams(-1, dp(82)).apply { setMargins(dp(2), dp(7), dp(2), dp(7)) })
            rightPanel.addView(neonRoundButton("▶", 42, false) { playNext() },
                LinearLayout.LayoutParams(dp(42), dp(42)).apply { setMargins(0, dp(9), 0, dp(9)) })
            rightPanel.addView(neonRoundButton("＋", 42, false) { player?.setVolume(1f, 1f) },
                LinearLayout.LayoutParams(dp(42), dp(42)).apply { setMargins(0, dp(7), 0, dp(7)) })
            rightPanel.addView(label("VOL", 8f, Color.LTGRAY).apply { gravity = Gravity.CENTER })
            playerPage.addView(leftPanel, LinearLayout.LayoutParams(dp(54), -1))
            playerPage.addView(headUnit, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(7), 0, dp(7), 0) })
            playerPage.addView(rightPanel, LinearLayout.LayoutParams(dp(54), -1))
            artFrame.visibility = View.GONE
            artFrame.layoutParams = LinearLayout.LayoutParams(-1, dp(174)).apply { setMargins(dp(4), dp(2), dp(4), dp(3)) }
            artFrame.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(3,6,10), Color.rgb(37,43,53), Color.rgb(4,7,12))).apply {
                cornerRadius = dp(14).toFloat()
                setStroke(dp(2), Color.rgb(111,133,157))
            }
            artFrame.setPadding(dp(5), dp(5), dp(5), dp(5))
            (artworkView.layoutParams as LinearLayout.LayoutParams).apply { width = dp(164); height = dp(164) }
            (vinylView.layoutParams as LinearLayout.LayoutParams).apply { width = dp(164); height = dp(164) }
            trackLabel.textSize = 12f
            trackLabel.setPadding(dp(3), dp(2), dp(3), 0)
            metadataLabel.textSize = 10f
            albumLabel.textSize = 9f
            metadataLabel.setPadding(0, 0, 0, 0)
            vu.layoutParams = LinearLayout.LayoutParams(-1, dp(34)).apply { setMargins(dp(3), 0, dp(3), 0) }
            seekBar.layoutParams = LinearLayout.LayoutParams(-1, dp(18))
            timeLabel.textSize = 9f
            technicalLabel.visibility = View.GONE
            nextTrackLabel.visibility = View.GONE
            controls.setPadding(0, 0, 0, 0)
            controls.getChildAt(0).layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(13) }
            controls.getChildAt(1).layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(13) }
            controls.getChildAt(2).layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            // Fekvő nézet: széles, arany AURIX VU középen, alatta jól olvasható cím.
            val headerView = headUnit.getChildAt(0)
            headUnit.removeAllViews()
            headUnit.addView(headerView, LinearLayout.LayoutParams(-1, dp(25)))
            centerAurixVu = AurixVuView().apply {
                setActive(player?.isPlaying == true)
                setBrightness(getSharedPreferences("aurix_settings", MODE_PRIVATE).getInt("vu_brightness", 80))
            }
            headUnit.addView(centerAurixVu, LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) })
            trackLabel.textSize = 16f
            trackLabel.setTextColor(Color.WHITE)
            trackLabel.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            trackLabel.gravity = Gravity.CENTER
            trackLabel.maxLines = 1
            trackLabel.ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            trackLabel.isSelected = true
            headUnit.addView(trackLabel, LinearLayout.LayoutParams(-1, dp(27)))
            metadataLabel.textSize = 11f
            albumLabel.textSize = 9f
            headUnit.addView(metadataLabel, LinearLayout.LayoutParams(-1, dp(17)))
            headUnit.addView(albumLabel, LinearLayout.LayoutParams(-1, dp(14)))
            headUnit.addView(seekBar, LinearLayout.LayoutParams(-1, dp(18)))
            headUnit.addView(timeLabel, LinearLayout.LayoutParams(-1, dp(16)))
            headUnit.addView(controls, LinearLayout.LayoutParams(-1, dp(48)))
            vu.visibility = View.GONE
            technicalLabel.visibility = View.GONE
            nextTrackLabel.visibility = View.GONE
        } else {
            // Visszavaltas allo modba: a fekvo nezetben elrejtett fo VU visszaallitasa.
            vu.visibility = View.VISIBLE
            centerAurixVu = null
            sideVuLeft = null
            sideVuRight = null
            original.forEachIndexed { i, child -> when (i) { in 0..10 -> playerPage.addView(child); 11, 12 -> songsPage.addView(child); 13, 14 -> eqPage.addView(child); else -> settingsPage.addView(child) } }
        }
        songsPage.setPadding(dp(12), dp(10), dp(12), dp(14))
        songsPage.addView(label("AURIX  •  MUSIC LIBRARY", 11f, Color.rgb(183, 150, 255)).apply { letterSpacing = 0.12f; setPadding(dp(4), dp(5), 0, dp(3)) })
        val librarySearch = EditText(this).apply {
            hint = "⌕  Keresés a zenék között..."
            setSingleLine(true); textSize = 14f; setTextColor(Color.WHITE); setHintTextColor(Color.rgb(177, 180, 205))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.rgb(49, 54, 111), Color.rgb(36, 43, 91))).apply { cornerRadius = dp(22).toFloat(); setStroke(dp(1), Color.rgb(126, 113, 221)) }
            setPadding(dp(18), dp(4), dp(18), dp(4))
        }
        songsPage.addView(librarySearch, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(14) })
        val categoryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun categoryCard(icon: String, title: String, subtitle: String, action: () -> Unit): View {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(83, 48, 151), Color.rgb(24, 61, 133))).apply { cornerRadius = dp(17).toFloat(); setStroke(dp(1), Color.rgb(133, 116, 230)) }
                setPadding(dp(5), dp(12), dp(5), dp(12)); isClickable = true; isFocusable = true
                addView(label(icon, 25f, Color.WHITE).apply { gravity = Gravity.CENTER })
                addView(label(title, 12f, Color.WHITE).apply { gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD })
                addView(label(subtitle, 9f, Color.rgb(210, 211, 239)).apply { gravity = Gravity.CENTER })
                setOnClickListener { action() }
            }
        }
        categoryRow.addView(categoryCard("♫", "Összes dal", "Teljes lista") { librarySearch.setText("") }, LinearLayout.LayoutParams(0, dp(100), 1f).apply { marginEnd = dp(5) })
        categoryRow.addView(categoryCard("♫", "Előadók", "Zenék szerint") { showLibraryGroups("Előadók") }, LinearLayout.LayoutParams(0, dp(100), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        categoryRow.addView(categoryCard("▣", "Albumok", "Borítók") { showLibraryGroups("Albumok") }, LinearLayout.LayoutParams(0, dp(100), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        categoryRow.addView(categoryCard("▤", "Mappák", "Eszközön") { showLibraryGroups("Mappák") }, LinearLayout.LayoutParams(0, dp(100), 1f).apply { marginStart = dp(5) })
        songsPage.addView(categoryRow)
        val sortRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(3), dp(12), dp(3), dp(5)) }
        sortRow.addView(label("Rendezés:", 12f, Color.WHITE), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
        val sortSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Név szerint", "Előadó szerint", "Legújabb elöl", "Legrégebbi elöl"))
            setSelection(listOf("Név szerint", "Előadó szerint", "Legújabb elöl", "Legrégebbi elöl").indexOf(librarySortMode).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    librarySortMode = listOf("Név szerint", "Előadó szerint", "Legújabb elöl", "Legrégebbi elöl")[position]
                    renderLibrary(librarySearch.text.toString())
                }
            }
        }
        sortRow.addView(sortSpinner, LinearLayout.LayoutParams(0, dp(42), 1f))
        songsPage.addView(sortRow)
        val recentHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(3), dp(17), dp(3), dp(7)) }
        recentHeader.addView(label("◷  Legutóbb hozzáadott dalok", 16f, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, -2, 1f))
        recentHeader.addView(button("↻ Frissítés", Color.rgb(73, 54, 135), Color.WHITE) { loadMusicLibrary() })
        songsPage.addView(recentHeader)
        songsPage.addView(button("＋  Fájlok kézi hozzáadása", Color.rgb(83, 48, 151), Color.WHITE) { openAudio() }, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(8) })
        libraryContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(36, 39, 83), Color.rgb(20, 27, 60))).apply { cornerRadius = dp(18).toFloat(); setStroke(dp(1), Color.rgb(93, 102, 183)) }; setPadding(dp(9), dp(6), dp(9), dp(6)) }
        songsPage.addView(libraryContainer)
        librarySearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderLibrary(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
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
            setVuStyle("car"); vuPrefs.edit().putString("vu_style", "car").apply()
            refreshVuStyleButtons("car")
        }
        simpleModeButton.setOnClickListener {
            setVuStyle("simple"); vuPrefs.edit().putString("vu_style", "simple").apply()
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
                    if (fromUser) { setVuBrightness(value); vuPrefs.edit().putInt("vu_brightness", value).apply() }
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
                setVuAnimation(checked)
                vuPrefs.edit().putBoolean("vu_animation", checked).apply()
            }
        }
        animationRow.addView(vuAnimation)
        settingsPage.addView(animationRow)
        val savedVuStyle = vuPrefs.getString("vu_style", "car") ?: "car"
        setVuStyle(savedVuStyle)
        refreshVuStyleButtons(savedVuStyle)
        setVuBrightness(vuPrefs.getInt("vu_brightness", 80))
        setVuAnimation(vuPrefs.getBoolean("vu_animation", true))
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val navButtons = mutableListOf<Button>()
        fun showPage(index: Int) { pageScrolls.forEachIndexed { i, v -> v.visibility = if (i == index) View.VISIBLE else View.GONE }; navButtons.forEachIndexed { i, b -> b.setTextColor(if (i == index) gold else Color.WHITE); b.background = rounded(if (i == index) Color.rgb(48, 42, 29) else Color.rgb(35, 41, 53), 12) } }
        openLibraryPage = { showPage(1) }
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

    private fun neonRoundButton(symbol: String, size: Int, primary: Boolean, click: () -> Unit) = Button(this).apply {
        text = symbol
        textSize = if (primary) 23f else 19f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        setTextColor(if (primary) Color.rgb(255, 220, 155) else Color.rgb(190, 222, 255))
        includeFontPadding = false
        isAllCaps = false
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding(0, 0, 0, 0)
        backgroundTintList = null
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.rgb(13, 18, 26))
            setStroke(dp(if (primary) 2 else 1), if (primary) Color.rgb(255, 177, 64) else Color.rgb(67, 157, 224))
        }
        elevation = dp(7).toFloat()
        stateListAnimator = null
        setOnClickListener { click() }
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
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DATA)
        val selection = MediaStore.Audio.Media.IS_MUSIC + " != 0"
        try {
            contentResolver.query(collection, projection, selection, null, MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC")?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val pathCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val title = cursor.getString(titleCol) ?: "Ismeretlen cím"
                    val artist = cursor.getString(artistCol) ?: "Ismeretlen előadó"
                    val song = LibrarySong(android.content.ContentUris.withAppendedId(collection, id), title, artist, cursor.getLong(dateCol))
                    librarySongs.add(song)
                    librarySongByUri[song.uri.toString()] = song
                }
            }
        } catch (_: Exception) { toast("A zenetár beolvasása nem sikerült.") }
        renderLibrary()
    }

    private fun showLibraryGroups(type: String) {
        if (!::libraryContainer.isInitialized) return
        libraryContainer.removeAllViews()
        val key: (LibrarySong) -> String = when (type) {
            "Előadók" -> { song -> song.artist.ifBlank { "Ismeretlen előadó" } }
            "Albumok" -> { song -> song.album.ifBlank { "Ismeretlen album" } }
            else -> { song -> song.folder.substringAfterLast("/").ifBlank { "Eszköz" } }
        }
        val groups = librarySongs.groupBy(key).toSortedMap(String.CASE_INSENSITIVE_ORDER)
        libraryContainer.addView(label("‹  Vissza az összes dalhoz", 13f, Color.rgb(220, 195, 255)).apply {
            setPadding(dp(8), dp(12), dp(8), dp(14))
            setOnClickListener { renderLibrary() }
        })
        libraryContainer.addView(label(type.uppercase() + "  •  ${groups.size}", 11f, Color.WHITE).apply { setPadding(dp(8), dp(4), dp(8), dp(10)) })
        groups.forEach { (name, songs) ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(9), dp(13), dp(9), dp(13)); isClickable = true; background = rounded(Color.argb(35,255,255,255), 10) }
            row.addView(label("♫", 19f, Color.rgb(194,151,255)), LinearLayout.LayoutParams(dp(34), -2))
            row.addView(label(name, 13f, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(label("${songs.size} dal  ›", 10f, Color.rgb(190,195,220)))
            row.setOnClickListener {
                libraryContainer.removeAllViews()
                songs.forEachIndexed { index, song ->
                    libraryContainer.addView(label("♫  ${song.title}  —  ${song.artist}", 12f, Color.WHITE).apply {
                        setPadding(dp(8), dp(12), dp(8), dp(12))
                        setOnClickListener {
                            queue.clear(); queue.addAll(songs.map { it.uri }); currentIndex = index
                            startTrack(song.uri); updateNextTrack()
                        }
                    })
                }
                libraryContainer.addView(label("‹  Vissza a $type listához", 12f, gold).apply { setPadding(dp(8), dp(12), dp(8), dp(12)); setOnClickListener { showLibraryGroups(type) } })
            }
            libraryContainer.addView(row)
        }
    }

    private fun renderLibrary(query: String = "") {
        if (!::libraryContainer.isInitialized) return
        libraryContainer.removeAllViews()
        val filtered = librarySongs.filter { it.title.contains(query, true) || it.artist.contains(query, true) }.let { songs -> when (librarySortMode) { "Előadó szerint" -> songs.sortedBy { it.artist.lowercase() }; "Legújabb elöl" -> songs.sortedByDescending { it.dateAdded }; "Legrégebbi elöl" -> songs.sortedBy { it.dateAdded }; else -> songs.sortedBy { it.title.lowercase() } } }
        if (filtered.isEmpty()) {
            libraryContainer.addView(label(if (librarySongs.isEmpty()) "Még nincs zene a könyvtárban. Adj hozzá fájlokat vagy frissítsd a listát." else "Nincs találat erre a keresésre.", 12f, Color.rgb(190, 194, 215)).apply { setPadding(dp(8), dp(14), dp(8), dp(14)) })
            return
        }
        val end = filtered.size
        for (index in 0 until end) {
            val song = filtered[index]
            val item = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(10), dp(8), dp(10)); isClickable = true; isFocusable = true; background = rounded(Color.argb(35, 255, 255, 255), 12) }
            val coverIcon = label("♫", 20f, Color.rgb(194, 151, 255)).apply { gravity = Gravity.CENTER }
            item.addView(coverIcon, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(10) })
            val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            names.addView(label(song.title, 13f, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            names.addView(label(song.artist, 11f, Color.rgb(190, 195, 220)).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            item.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
            item.setOnClickListener {
                queue.clear()
                queue.addAll(filtered.map { it.uri })
                currentIndex = index
                startTrack(song.uri)
                updateNextTrack()
                toast("Zenetár: " + song.title)
            }
            libraryContainer.addView(item)
            if (index < end - 1) libraryContainer.addView(View(this).apply { setBackgroundColor(Color.rgb(43, 48, 58)) }, LinearLayout.LayoutParams(-1, dp(1)))
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
                        setVuActive(true)
                        playButton.text = "Ⅱ"
                        vinylView.invalidate()
                        setupEffects(mp.audioSessionId)
                        handler.removeCallbacks(progressTask)
                        handler.post(progressTask)
                    } catch (_: Exception) {
                        toast("A lejátszás indítása nem sikerült.")
                    }
                }
                setOnErrorListener { _, what, extra ->
                    playButton.text = "▶"
                    toast("Lejátszási hiba ($what/$extra).")
                    true
                }
                setOnCompletionListener {
                    setVuActive(false)
                    if (currentIndex + 1 < queue.size) {
                        currentIndex++
                        selectedUri = queue[currentIndex]
                        updateNextTrack()
                        startTrack(queue[currentIndex])
                    } else {
                        playButton.text = "▶"
                        updateNextTrack()
                    }
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            try { player?.release() } catch (_: Exception) {}
            player = null
            playButton.text = "▶"
            toast("Nem sikerült megnyitni: " + (e.message ?: "ismeretlen hiba"))
        }
    }
    private fun togglePlayback() {
        val p = player
        if (p == null) { selectedUri?.let { startTrack(it) } ?: openAudio(); return }
        try { if (p.isPlaying) { p.pause(); setVuActive(false); playButton.text = "▶"; vinylView.invalidate() } else { p.start(); setVuActive(true); playButton.text = "Ⅱ"; vinylView.invalidate() } } catch (_: Exception) {}
    }
    private fun setupEffects(session: Int) {
        try {
            equalizer?.release(); equalizer = Equalizer(0, session).apply { enabled = true }
            bassBoost?.release(); bassBoost = BassBoost(0, session).apply { enabled = true }
            // Új dalnál nullázzuk az előző dalból megmaradt mérési simítást.
            vuSmoothedLevel = 0f
            visualizer?.let { old ->
                try { old.enabled = false } catch (_: Exception) {}
                try { old.release() } catch (_: Exception) {}
            }
            visualizer = Visualizer(session).apply {
                // A VU-mérő ritkább mintavételezése csökkenti a főszál terhelését.
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                        // A VU-kijelzőt ismét kizárólag az FFT-mérés vezérli.
                    }
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        // Csak az aktuális dal aktív Visualizerétől és tényleges lejátszásakor fogadunk adatot.
                        if (v == null || v !== visualizer || player?.isPlaying != true) return
                        val activePlayer = player ?: return
                        // A dal utolsó 1 másodpercében nem fogadunk új FFT-adatot.
                        try {
                            val remainingMs = activePlayer.duration - activePlayer.currentPosition
                            if (remainingMs <= 1000) {
                                vuSmoothedLevel = 0f
                                setVuLevels(0f, 0f)
                                return
                            }
                        } catch (_: Exception) { return }
                        if (fft == null || fft.size < 16) return
                        fun bandEnergy(firstBin: Int, lastBin: Int): Float {
                            var energy = 0.0
                            var count = 0
                            val end = minOf(lastBin, fft.size / 2 - 1)
                            for (bin in firstBin..end) {
                                val index = bin * 2
                                val real = fft[index].toInt()
                                val imaginary = fft[index + 1].toInt()
                                energy += (real * real + imaginary * imaginary).toDouble()
                                count++
                            }
                            return if (count == 0) 0f else
                                (kotlin.math.sqrt(energy / count) / 128.0).toFloat().coerceIn(0f, 1f)
                        }
                        val bass = bandEnergy(1, 8)
                        val mid = bandEnergy(9, 40)
                        val treble = bandEnergy(41, 120)
                        val combined = bass * 0.48f + mid * 0.34f + treble * 0.18f
                        val linearLevel = ((combined - 0.012f).coerceAtLeast(0f) * 3.2f).coerceIn(0f, 1f)
                        val targetLevel = linearLevel * linearLevel * linearLevel
                        val smoothing = if (targetLevel > vuSmoothedLevel) 0.38f else 0.22f
                        vuSmoothedLevel += (targetLevel - vuSmoothedLevel) * smoothing
                        setVuLevels(vuSmoothedLevel, vuSmoothedLevel)
                    }
                }, Visualizer.getMaxCaptureRate(), false, true)
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
            val librarySong = librarySongByUri[uri.toString()]
            trackLabel.text = librarySong?.title?.takeIf { it.isNotBlank() }
                ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment?.substringAfterLast("/")?.substringBeforeLast(".")?.takeIf { it.isNotBlank() && !it.all { ch -> ch.isDigit() } }
                ?: "Ismeretlen cím"
            val cover = r.embeddedPicture
            if (cover != null) { val bitmap = android.graphics.BitmapFactory.decodeByteArray(cover, 0, cover.size); if (bitmap != null) artworkView.setImageBitmap(bitmap) }
            val artist = librarySong?.artist?.takeIf { it.isNotBlank() } ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Ismeretlen előadó"
            val album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "Ismeretlen album"
            metadataLabel.text = artist
            albumLabel.text = album
            val bitrate = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()?.div(1000)
            technicalLabel.text = "AUDIO INFO  •  " + (bitrate?.let { "$it kbps" } ?: "bitráta —")
        } catch (_: Exception) {
            trackLabel.text = uri.lastPathSegment?.substringAfterLast("/")?.substringBeforeLast(".") ?: "Ismeretlen cím"
            metadataLabel.text = "Ismeretlen előadó"
            albumLabel.text = "Ismeretlen album"
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
        if (::vu.isInitialized) {
            setVuActive(false)
            setVuLevels(0f, 0f)
        }
        try { visualizer?.release() } catch (_: Exception) {}; visualizer = null
        try { equalizer?.release() } catch (_: Exception) {}; equalizer = null
        try { bassBoost?.release() } catch (_: Exception) {}; bassBoost = null
        try { player?.release() } catch (_: Exception) {}; player = null
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    override fun onDestroy() { releaseAudio(); super.onDestroy() }

    private inner class VinylView : View(this@MainActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var angle = 0f
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f; val cy = height / 2f
            val radius = min(width, height) * 0.47f
            paint.shader = null; paint.color = Color.rgb(8, 9, 12)
            canvas.drawCircle(cx, cy, radius, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1).toFloat(); paint.color = Color.rgb(65, 59, 46)
            for (i in 1..13) canvas.drawCircle(cx, cy, radius * (0.42f + i * 0.041f), paint)
            paint.style = Paint.Style.FILL
            canvas.save(); canvas.rotate(angle, cx, cy)
            paint.color = Color.rgb(183, 132, 42); canvas.drawCircle(cx, cy, radius * 0.34f, paint)
            paint.color = Color.rgb(22, 22, 23); canvas.drawCircle(cx, cy, radius * 0.30f, paint)
            paint.color = Color.rgb(235, 190, 105); paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create("sans-serif-black", Typeface.BOLD); paint.textSize = dp(19).toFloat()
            canvas.drawText("AURIX", cx, cy + dp(3), paint)
            paint.textSize = dp(7).toFloat(); paint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText("V50  •  HI-FI", cx, cy + dp(17), paint)
            paint.color = Color.rgb(235, 190, 105); canvas.drawCircle(cx, cy, dp(3).toFloat(), paint)
            canvas.restore()
            if (player?.isPlaying == true && visibility == View.VISIBLE) { angle = (angle + 1.2f) % 360f; postInvalidateDelayed(30) }
        }
    }

    private fun blendVuColor(start: Int, end: Int, fraction: Float): Int {
        val t = fraction.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(start) + (Color.red(end) - Color.red(start)) * t).toInt(),
            (Color.green(start) + (Color.green(end) - Color.green(start)) * t).toInt(),
            (Color.blue(start) + (Color.blue(end) - Color.blue(start)) * t).toInt()
        )
    }

    private inner class AurixVuView : View(this@MainActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var left = 0f
        private var right = 0f
        private var active = false
        private var brightness = 80
        fun setActive(v: Boolean) { active = v; if (!v) { left = 0f; right = 0f }; invalidate() }
        fun setLevels(l: Float, r: Float) { if (!active) return; left=l.coerceIn(0f,1f); right=r.coerceIn(0f,1f); invalidate() }
        fun setBrightness(v: Int) { brightness=v.coerceIn(20,100); invalidate() }
        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val w=width.toFloat(); val h=height.toFloat()
            if (w<=0f || h<=0f) return
            val frame=RectF(dp(3).toFloat(),dp(4).toFloat(),w-dp(3).toFloat(),h-dp(4).toFloat())
            paint.style=Paint.Style.FILL
            paint.shader=android.graphics.LinearGradient(0f,0f,0f,h,intArrayOf(Color.rgb(5,5,6),Color.rgb(24,18,10),Color.rgb(3,3,4)),null,android.graphics.Shader.TileMode.CLAMP)
            c.drawRoundRect(frame,dp(18).toFloat(),dp(18).toFloat(),paint); paint.shader=null
            paint.style=Paint.Style.STROKE; paint.strokeWidth=dp(3).toFloat()
            paint.color=Color.rgb(255,190,75); c.drawRoundRect(frame,dp(18).toFloat(),dp(18).toFloat(),paint)
            val pad=dp(8).toFloat()
            val centerW=w*0.22f
            val sideW=(w-centerW-pad*4)/2f
            val barH=max(dp(5).toFloat(),h*0.17f)
            drawGoldBar(c,pad,h*0.25f,sideW,barH,left)
            drawGoldBar(c,w-pad-sideW,h*0.25f,sideW,barH,right)
            paint.style=Paint.Style.FILL; paint.textAlign=Paint.Align.CENTER
            paint.typeface=Typeface.create("sans-serif-black",Typeface.BOLD)
            paint.color=Color.rgb(255,222,157); paint.textSize=h*0.31f
            c.drawText("VU",w/2f,h*0.49f,paint)
            paint.textSize=h*0.13f; paint.color=Color.rgb(238,190,112)
            c.drawText("AURIX",w/2f,h*0.70f,paint)
            paint.textAlign=Paint.Align.LEFT
        }
        private fun drawGoldBar(c: Canvas,x:Float,y:Float,width:Float,height:Float,level:Float) {
            val n=32; val gap=dp(1).toFloat(); val sw=(width-gap*(n-1))/n
            val lit=(level*n).toInt()
            for(i in 0 until n) {
                val sx=x+i*(sw+gap)
                val color = when {
                    i >= 20 -> Color.rgb(255, 35, 22)
                    i < 5 -> blendVuColor(Color.rgb(255, 194, 42), Color.rgb(190, 225, 135), i / 5f)
                    i < 10 -> blendVuColor(Color.rgb(190, 225, 135), Color.rgb(145, 215, 190), (i - 5) / 5f)
                    i < 15 -> blendVuColor(Color.rgb(145, 215, 190), Color.rgb(135, 205, 235), (i - 10) / 5f)
                    else -> blendVuColor(Color.rgb(135, 205, 235), Color.rgb(65, 155, 225), (i - 15) / 5f)
                }
                paint.style=Paint.Style.FILL
                paint.color=if(active && i<lit) Color.argb(brightness*255/100,Color.red(color),Color.green(color),Color.blue(color)) else Color.rgb(48,39,25)
                c.drawRoundRect(RectF(sx,y,sx+sw,y+height),dp(1).toFloat(),dp(1).toFloat(),paint)
            }
        }
    }

    private inner class VuView(private val verticalSingle: Boolean = false) : View(this@MainActivity) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private var left = 0f
        private var right = 0f
        private var style = "car"
        private var brightness = 80
        private var animationEnabled = true
        private var active = false

        fun setActive(value: Boolean) { active = value; if (!value) { left = 0f; right = 0f }; invalidate() }
        fun setLevels(l: Float, r: Float) {
            if (!animationEnabled || !active) return
            left = l.coerceIn(0f, 1f)
            right = r.coerceIn(0f, 1f)
            invalidate()
        }
        fun setStyle(value: String) { style = if (value == "simple") "simple" else "car"; invalidate() }
        fun setBrightness(value: Int) { brightness = value.coerceIn(20, 100); invalidate() }
        fun setAnimationEnabled(value: Boolean) { animationEnabled = value; if (!value) { left = 0f; right = 0f }; invalidate() }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            if (!active) return
            val w = width.toFloat()
            val h = height.toFloat()
            if (verticalSingle) { drawVerticalSingle(c, w, h); return }
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
                // A feliratot eltávolítottuk a letisztult LED-képért.
                p.textAlign = Paint.Align.LEFT
            } else {
                p.color = Color.rgb(104, 91, 69)
                p.strokeWidth = dp(1).toFloat()
                c.drawLine(inset + labelW, h / 2f, w - inset, h / 2f, p)
                p.color = Color.rgb(190, 160, 105)
                p.textSize = dp(8).toFloat()
                p.textAlign = Paint.Align.CENTER
                // Nincs alsó dekoratív felirat.
                p.textAlign = Paint.Align.LEFT
            }
        }

        private fun drawVerticalSingle(c: Canvas, w: Float, h: Float) {
            val outer = RectF(dp(2).toFloat(), dp(2).toFloat(), w-dp(2).toFloat(), h-dp(2).toFloat())
            p.style = Paint.Style.FILL
            p.color = Color.rgb(8, 10, 14)
            c.drawRoundRect(outer, dp(5).toFloat(), dp(5).toFloat(), p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = dp(1).toFloat()
            p.color = Color.argb(brightness * 2, 238, 177, 67)
            c.drawRoundRect(outer, dp(5).toFloat(), dp(5).toFloat(), p)
            p.style = Paint.Style.FILL
            val count = 12
            val gap = dp(2).toFloat()
            val top = dp(5).toFloat()
            val bottom = h - dp(5).toFloat()
            val segH = (bottom-top-gap*(count-1))/count
            val lit = ((left + right) * 0.5f * count).toInt()
            for (i in 0 until count) {
                val y = bottom - (i+1)*segH - i*gap
                val base = when {
                    i >= 20 -> Color.rgb(255, 35, 24)
                    i < 5 -> blendVuColor(Color.rgb(255, 194, 42), Color.rgb(190, 225, 135), i / 5f)
                    i < 10 -> blendVuColor(Color.rgb(190, 225, 135), Color.rgb(145, 215, 190), (i - 5) / 5f)
                    i < 15 -> blendVuColor(Color.rgb(145, 215, 190), Color.rgb(135, 205, 235), (i - 10) / 5f)
                    else -> blendVuColor(Color.rgb(135, 205, 235), Color.rgb(65, 155, 225), (i - 15) / 5f)
                }
                p.color = if (i < lit) Color.argb(brightness*255/100, Color.red(base), Color.green(base), Color.blue(base)) else Color.rgb(42, 43, 45)
                c.drawRoundRect(RectF(dp(6).toFloat(), y, w-dp(6).toFloat(), y+segH), dp(2).toFloat(), dp(2).toFloat(), p)
            }
        }

        private fun drawBar(c: Canvas, x: Float, y: Float, width: Float, height: Float, level: Float) {
            val segments = 32
            val gap = dp(1).toFloat()
            val segmentW = (width - gap * (segments - 1)) / segments
            // A 32 LED-es skála első 20 szegmense a jelszint 0–75%-át,
            // a piros 12 szegmens pedig a felső 25%-át fedi le.
            // A LED-ek száma, sorrendje és színei változatlanok.
            val safeLevel = level.coerceIn(0f, 1f)
            val lit = if (safeLevel <= 0.75f) {
                (safeLevel / 0.75f * 20f).toInt()
            } else {
                (20f + (safeLevel - 0.75f) / 0.25f * 12f).toInt()
            }.coerceIn(0, segments)
            for (i in 0 until segments) {
                val sx = x + i * (segmentW + gap)
                val active = i < lit
                val base = when {
                    i >= 20 -> Color.rgb(255, 66, 48)
                    i < 5 -> blendVuColor(Color.rgb(255, 194, 42), Color.rgb(190, 225, 135), i / 5f)
                    i < 10 -> blendVuColor(Color.rgb(190, 225, 135), Color.rgb(145, 215, 190), (i - 5) / 5f)
                    i < 15 -> blendVuColor(Color.rgb(145, 215, 190), Color.rgb(135, 205, 235), (i - 10) / 5f)
                    else -> blendVuColor(Color.rgb(135, 205, 235), Color.rgb(65, 155, 225), (i - 15) / 5f)
                }
                p.color = if (active) Color.argb(brightness * 255 / 100, Color.red(base), Color.green(base), Color.blue(base))
                    else Color.rgb(43, 45, 49)
                c.drawRoundRect(RectF(sx, y, sx + segmentW, y + height), dp(1).toFloat(), dp(1).toFloat(), p)
            }
        }
    }
}
