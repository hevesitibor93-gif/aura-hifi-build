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
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
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

@androidx.media3.common.util.UnstableApi
class MainActivity : Activity() {
    private var player: ExoPlayer? = null
    private val eqProcessor = AurixEqualizerAudioProcessor()
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
    private val libraryExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var libraryLoadGeneration = 0
    private var libraryVisibleLimit = 80
    private var lastLibraryQuery = ""
    private var groupVisibleLimit = 80
    private var currentGroupType = ""
    private var libraryMode = "home"
    private var activePageIndex = 0
    private var librarySortMode = "Név szerint"
    private var openLibraryPage: (() -> Unit)? = null
    private var activeLibraryRenderer: (() -> Unit)? = null
    private var libraryScrollView: ScrollView? = null
    private var currentTrackRow: View? = null
    private val librarySongByUri = mutableMapOf<String, LibrarySong>()
    private val selectedFolderPaths = linkedSetOf<String>()
    private lateinit var libraryContainer: LinearLayout
    private lateinit var libraryCategoryRow: LinearLayout
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
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) { if (fromUser) try { player?.let { if (it.duration > 0) it.seekTo(it.duration * value / 1000L) } } catch (_: Exception) {} }
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
        // A lejátszási sor listája nem jelenik meg külön a főképernyőn.
        // A sor a háttérben megmarad a következő/előző szám funkcióhoz;
        // a dalok listája kizárólag a Zenetár kategóriájában látható.
        queueContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        body.addView(sectionTitle("HANGSZÍNSZABÁLYZÓ"))
        eqContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded(panel, 18); setPadding(dp(14), dp(12), dp(14), dp(12)) }
        body.addView(eqContainer)
        renderEqualizer()
        body.addView(sectionTitle("HANGFORMÁLÁS"))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("HÁTTÉR KÉP", Color.rgb(35, 41, 53), Color.WHITE) { openImage() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
        actions.addView(button("EQ ALAPHELYZET", Color.rgb(35, 41, 53), Color.WHITE) { resetEq() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        body.addView(actions)
        body.addView(label("Saját AURA Hi-Fi hangfeldolgozás • csak az alkalmazás lejátszására hat, a telefon rendszerhangját nem módosítja.", 11f, Color.rgb(155, 163, 177)).apply { setPadding(dp(3), dp(14), dp(3), dp(18)) })
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
        libraryScrollView = pageScrolls[1]
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
                    in 11..15 -> eqPage.addView(child)
                    else -> settingsPage.addView(child)
                }
            }
            leftPanel.addView(label("L", 11f, Color.rgb(255, 190, 65)).apply { gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD })
            sideVuLeft = VuView(true).apply { setStyle("car"); setBrightness(getSharedPreferences("aurix_settings", MODE_PRIVATE).getInt("vu_brightness", 80)); setAnimationEnabled(getSharedPreferences("aurix_settings", MODE_PRIVATE).getBoolean("vu_animation", true)); setActive(player?.isPlaying == true) }
            leftPanel.addView(sideVuLeft, LinearLayout.LayoutParams(-1, dp(82)).apply { setMargins(dp(2), dp(7), dp(2), dp(7)) })
            leftPanel.addView(neonRoundButton("◀", 42, false) { playPrevious() },
                LinearLayout.LayoutParams(dp(42), dp(42)).apply { setMargins(0, dp(9), 0, dp(9)) })
            leftPanel.addView(neonRoundButton("−", 42, false) { player?.volume = 0.65f },
                LinearLayout.LayoutParams(dp(42), dp(42)).apply { setMargins(0, dp(7), 0, dp(7)) })
            leftPanel.addView(label("VOL", 8f, Color.LTGRAY).apply { gravity = Gravity.CENTER })
            rightPanel.addView(label("R", 11f, Color.rgb(255, 190, 65)).apply { gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD })
            sideVuRight = VuView(true).apply { setStyle("car"); setBrightness(getSharedPreferences("aurix_settings", MODE_PRIVATE).getInt("vu_brightness", 80)); setAnimationEnabled(getSharedPreferences("aurix_settings", MODE_PRIVATE).getBoolean("vu_animation", true)); setActive(player?.isPlaying == true) }
            rightPanel.addView(sideVuRight, LinearLayout.LayoutParams(-1, dp(82)).apply { setMargins(dp(2), dp(7), dp(2), dp(7)) })
            rightPanel.addView(neonRoundButton("▶", 42, false) { playNext() },
                LinearLayout.LayoutParams(dp(42), dp(42)).apply { setMargins(0, dp(9), 0, dp(9)) })
            rightPanel.addView(neonRoundButton("＋", 42, false) { player?.volume = 1f },
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
            original.forEachIndexed { i, child -> when (i) { in 0..10 -> playerPage.addView(child); in 11..15 -> eqPage.addView(child); else -> settingsPage.addView(child) } }
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
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(130, 83, 194), Color.rgb(91, 83, 174), Color.rgb(24, 61, 133))).apply { cornerRadius = dp(17).toFloat(); setStroke(dp(1), Color.argb(220, 235, 190, 105)) }
                setPadding(dp(5), dp(12), dp(5), dp(12)); isClickable = true; isFocusable = true
                addView(label(icon, 25f, Color.WHITE).apply { gravity = Gravity.CENTER })
                addView(label(title, 12f, Color.WHITE).apply { gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD })
                addView(label(subtitle, 9f, Color.rgb(210, 211, 239)).apply { gravity = Gravity.CENTER })
                setOnClickListener { action() }
            }
        }
        categoryRow.addView(categoryCard("♫", "Összes dal", "Teljes lista") { libraryMode = "all"; librarySearch.setText(""); renderLibrary() }, LinearLayout.LayoutParams(0, dp(100), 1f).apply { marginEnd = dp(5) })
        categoryRow.addView(categoryCard("♫", "Előadók", "Zenék szerint") { showLibraryGroups("Előadók") }, LinearLayout.LayoutParams(0, dp(100), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        categoryRow.addView(categoryCard("▣", "Albumok", "Borítók") { showLibraryGroups("Albumok") }, LinearLayout.LayoutParams(0, dp(100), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        categoryRow.addView(categoryCard("▤", "Mappák", "Eszközön") { showLibraryGroups("Mappák") }, LinearLayout.LayoutParams(0, dp(100), 1f).apply { marginStart = dp(5) })
        val extraCategoryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        extraCategoryRow.addView(categoryCard("▤", "Lejátszási listák", "Saját listák") { showPlaylists() }, LinearLayout.LayoutParams(0, dp(92), 1f).apply { marginEnd = dp(5) })
        extraCategoryRow.addView(categoryCard("◷", "Legutóbb hozzáadott", "100 új dal") { showRecentAdded() }, LinearLayout.LayoutParams(0, dp(92), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        extraCategoryRow.addView(categoryCard("♫", "Nemrég játszott", "Előzmények") { showRecentlyPlayed() }, LinearLayout.LayoutParams(0, dp(92), 1f).apply { marginStart = dp(5) })
        val categoryGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        libraryCategoryRow = categoryGroup
        categoryGroup.addView(categoryRow)
        categoryGroup.addView(extraCategoryRow)
        val sortRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(3), dp(12), dp(3), dp(5)) }
        sortRow.addView(label("Rendezés:", 12f, Color.WHITE), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
        var sortSpinnerInitialized = false
        val sortSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Név szerint", "Előadó szerint", "Legújabb elöl", "Legrégebbi elöl"))
            setSelection(listOf("Név szerint", "Előadó szerint", "Legújabb elöl", "Legrégebbi elöl").indexOf(librarySortMode).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (!sortSpinnerInitialized) {
                        sortSpinnerInitialized = true
                        return
                    }
                    librarySortMode = listOf("Név szerint", "Előadó szerint", "Legújabb elöl", "Legrégebbi elöl")[position]
                    renderLibrary(librarySearch.text.toString())
                }
            }
        }
        sortRow.addView(sortSpinner, LinearLayout.LayoutParams(0, dp(42), 1f))
        val recentHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(3), dp(17), dp(3), dp(7)) }
        recentHeader.addView(label("◷  Legutóbb hozzáadott dalok", 16f, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, -2, 1f))
        recentHeader.addView(button("↻ Frissítés", Color.rgb(73, 54, 135), Color.WHITE) { loadMusicLibrary() })
        libraryContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(36, 39, 83), Color.rgb(20, 27, 60))).apply { cornerRadius = dp(18).toFloat(); setStroke(dp(1), Color.rgb(93, 102, 183)) }; setPadding(dp(9), dp(6), dp(9), dp(6)) }
        libraryContainer.visibility = View.GONE
        // A kategóriakártyák mindig a lista előtt legyenek a nézetfa sorrendjében.
        songsPage.addView(categoryGroup)
        songsPage.addView(libraryContainer)
        librarySearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { if (libraryMode == "all") renderLibrary(s?.toString().orEmpty()) }
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
        fun showPage(index: Int, resetLibrary: Boolean = true) {
            activePageIndex = index
            pageScrolls.forEachIndexed { i, v -> v.visibility = if (i == index) View.VISIBLE else View.GONE }
            if (index == 1 && resetLibrary) {
                showLibraryHome()
                pageScrolls[1].post { pageScrolls[1].scrollTo(0, 0) }
            }
            navButtons.forEachIndexed { i, b -> b.setTextColor(if (i == index) gold else Color.WHITE); b.background = rounded(if (i == index) Color.rgb(48, 42, 29) else Color.rgb(35, 41, 53), 12) }
        }
        openLibraryPage = {
            showPage(1, false)
            currentTrackRow = null
            if (activeLibraryRenderer == null) showLibraryHome() else activeLibraryRenderer?.invoke()
            scrollToCurrentLibraryRow()
        }
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
        // A MediaStore-lekérdezés háttérszálon fut, így nem fagyasztja meg a kezelőfelületet.
        val generation = ++libraryLoadGeneration
        libraryContainer.removeAllViews()
        libraryContainer.addView(label("♫  Zenetár betöltése…", 12f, Color.WHITE).apply {
            setPadding(dp(8), dp(14), dp(8), dp(14))
        })
        libraryExecutor.execute {
            val loadedSongs = mutableListOf<LibrarySong>()
            val loadedSongByUri = mutableMapOf<String, LibrarySong>()
            var loadFailed = false
            val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DATA
            )
            val selection = MediaStore.Audio.Media.IS_MUSIC + " != 0"
            try {
                contentResolver.query(collection, projection, selection, null,
                    MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC")?.use { cursor ->
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
                        val album = if (albumCol >= 0) cursor.getString(albumCol) ?: "Ismeretlen album" else "Ismeretlen album"
                        val fullPath = if (pathCol >= 0) cursor.getString(pathCol) else null
                        val folder = fullPath?.let { java.io.File(it).parentFile?.absolutePath }
                            ?.takeIf { it.isNotBlank() } ?: "Ismeretlen mappa"
                        val song = LibrarySong(android.content.ContentUris.withAppendedId(collection, id),
                            title, artist, album, folder, cursor.getLong(dateCol))
                        loadedSongs.add(song)
                        loadedSongByUri[song.uri.toString()] = song
                    }
                }
            } catch (_: Exception) {
                loadFailed = true
            }
            runOnUiThread {
                if (generation != libraryLoadGeneration || isFinishing || isDestroyed) return@runOnUiThread
                librarySongs.clear()
                librarySongs.addAll(loadedSongs)
                librarySongByUri.clear()
                librarySongByUri.putAll(loadedSongByUri)
                if (loadFailed) toast("A zenetár beolvasása nem sikerült.")
                // Háttérben befejeződő betöltés ne írja felül a másik oldal állapotát.
                if (activePageIndex == 1) {
                    if (libraryMode == "all") renderLibrary() else showLibraryHome()
                }
            }
        }
    }

    private val recentlyPlayed = mutableListOf<String>()

    private fun libraryRowBackground(selected: Boolean): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        if (selected) intArrayOf(Color.rgb(91, 67, 27), Color.rgb(48, 39, 24))
        else intArrayOf(Color.argb(35, 255, 255, 255), Color.argb(22, 255, 255, 255))
    ).apply {
        cornerRadius = dp(12).toFloat()
        setStroke(dp(if (selected) 2 else 1), if (selected) gold else Color.argb(35, 180, 190, 210))
    }

    private fun markLibrarySongRow(row: View, uri: Uri) {
        val selected = selectedUri == uri
        row.background = libraryRowBackground(selected)
        if (selected) currentTrackRow = row
    }

    private fun scrollToCurrentLibraryRow() {
        val scroll = libraryScrollView ?: return
        val row = currentTrackRow ?: return
        scroll.post {
            if (row.parent == null) return@post
            val rowLocation = IntArray(2)
            val scrollLocation = IntArray(2)
            row.getLocationOnScreen(rowLocation)
            scroll.getLocationOnScreen(scrollLocation)
            val target = scroll.scrollY + rowLocation[1] - scrollLocation[1] - dp(110)
            scroll.smoothScrollTo(0, target.coerceAtLeast(0))
        }
    }

    private fun refreshLibrarySelection() {
        if (activePageIndex == 1 && activeLibraryRenderer != null && libraryMode != "home") {
            currentTrackRow = null
            activeLibraryRenderer?.invoke()
            scrollToCurrentLibraryRow()
        }
    }

    private fun addLibraryBackButton() {
        libraryContainer.addView(label("‹  Vissza a zenetárhoz", 13f, Color.rgb(235, 205, 145)).apply { setPadding(dp(8), dp(12), dp(8), dp(12)); setOnClickListener { showLibraryHome() } })
    }

    private fun renderSongRows(songs: List<LibrarySong>) {
        songs.forEachIndexed { index, song ->
            val row = label("♫  " + song.title + " — " + song.artist, 12f, Color.WHITE).apply {
                setPadding(dp(9), dp(11), dp(8), dp(11))
                setOnClickListener { queue.clear(); queue.addAll(songs.map { it.uri }); currentIndex = index; startTrack(song.uri); updateNextTrack() }
            }
            markLibrarySongRow(row, song.uri)
            libraryContainer.addView(row)
        }
        if (songs.isEmpty()) libraryContainer.addView(label("Nincs megjeleníthető dal.", 12f, Color.LTGRAY))
    }

    private fun showRecentAdded() {
        activeLibraryRenderer = { showRecentAdded() }
        libraryMode = "recent_added"; libraryCategoryRow.visibility = View.GONE; libraryContainer.visibility = View.VISIBLE; libraryContainer.removeAllViews(); addLibraryBackButton()
        libraryContainer.addView(label("LEGUTÓBB HOZZÁADOTT • 100 DAL", 11f, Color.WHITE))
        renderSongRows(librarySongs.sortedByDescending { it.dateAdded }.take(100))
    }

    private fun showRecentlyPlayed() {
        activeLibraryRenderer = { showRecentlyPlayed() }
        libraryMode = "recent_played"; libraryCategoryRow.visibility = View.GONE; libraryContainer.visibility = View.VISIBLE; libraryContainer.removeAllViews(); addLibraryBackButton()
        libraryContainer.addView(label("NEMRÉG JÁTSZOTT", 11f, Color.WHITE))
        renderSongRows(recentlyPlayed.mapNotNull { librarySongByUri[it] }.distinctBy { it.uri.toString() }.take(100))
    }

    private fun showPlaylists() {
        activeLibraryRenderer = { showPlaylists() }
        libraryMode = "playlists"; libraryCategoryRow.visibility = View.GONE; libraryContainer.visibility = View.VISIBLE; libraryContainer.removeAllViews(); addLibraryBackButton()
        libraryContainer.addView(label("LEJÁTSZÁSI LISTÁK", 11f, Color.WHITE))
        val prefs = getSharedPreferences("aurix_library", MODE_PRIVATE)
        val names = prefs.getStringSet("playlist_names", emptySet()) ?: emptySet()
        names.sorted().forEach { name ->
            libraryContainer.addView(label("♫  " + name + "  ›", 13f, Color.WHITE).apply { setPadding(dp(9), dp(13), dp(8), dp(13)); setOnClickListener {
                val arr = org.json.JSONArray(prefs.getString("playlist_" + name, "[]") ?: "[]")
                val songs = (0 until arr.length()).mapNotNull { librarySongByUri[arr.optString(it)] }
                if (songs.isNotEmpty()) { queue.clear(); queue.addAll(songs.map { it.uri }); currentIndex = 0; startTrack(queue[0]); updateNextTrack() }
            } })
        }
        libraryContainer.addView(button("＋ Aktuális sor mentése listaként", Color.rgb(91, 67, 157), Color.WHITE) {
            if (queue.isEmpty()) { toast("Előbb válassz zenéket."); return@button }
            val edit = EditText(this).apply { hint = "Lejátszási lista neve" }
            android.app.AlertDialog.Builder(this).setTitle("Új lejátszási lista").setView(edit).setNegativeButton("Mégse", null).setPositiveButton("Mentés") { _, _ ->
                val name = edit.text.toString().trim()
                if (name.isNotEmpty()) { val arr = org.json.JSONArray(); queue.forEach { arr.put(it.toString()) }; val set = (prefs.getStringSet("playlist_names", emptySet()) ?: emptySet()).toMutableSet(); set.add(name); prefs.edit().putStringSet("playlist_names", set).putString("playlist_" + name, arr.toString()).apply(); showPlaylists() }
            }.show()
        })
        if (names.isEmpty()) libraryContainer.addView(label("Még nincs mentett lejátszási listád.", 12f, Color.LTGRAY))
    }

    private fun showSearchResults(query: String) {
        activeLibraryRenderer = { showSearchResults(query) }
        libraryMode = "search"; libraryCategoryRow.visibility = View.GONE; libraryContainer.visibility = View.VISIBLE; libraryContainer.removeAllViews(); addLibraryBackButton()
        val songs = librarySongs.filter { it.title.contains(query, true) || it.artist.contains(query, true) || it.album.contains(query, true) || it.folder.contains(query, true) }
        libraryContainer.addView(label("DALOK • " + songs.size, 11f, Color.rgb(235, 205, 145))); renderSongRows(songs)
        val folders = librarySongs.map { it.folder }.distinct().filter { it.contains(query, true) }.take(30)
        libraryContainer.addView(label("MAPPÁK • " + folders.size, 11f, Color.rgb(235, 205, 145)))
        folders.forEach { path -> libraryContainer.addView(label("📁  " + java.io.File(path).name + "  ›", 12f, Color.WHITE).apply { setPadding(dp(9), dp(10), dp(8), dp(10)); setOnClickListener { showFolderBrowser(path) } }) }
    }

    private fun showLibraryHome() {
        activeLibraryRenderer = null
        currentTrackRow = null
        libraryMode = "home"
        if (!::libraryContainer.isInitialized) return
        libraryCategoryRow.visibility = View.VISIBLE
        libraryContainer.visibility = View.GONE
        libraryContainer.removeAllViews()
        libraryContainer.addView(label("Válassz egy kategóriát a fenti kártyák közül.", 12f, Color.rgb(190, 194, 215)).apply { setPadding(dp(8), dp(14), dp(8), dp(14)) })
    }

    private fun showLibraryGroups(type: String) {
        if (!::libraryContainer.isInitialized) return
        activeLibraryRenderer = { showLibraryGroups(type) }
        if (type == "Mappák") { showFolderRoot(); return }
        if (currentGroupType != type) {
            currentGroupType = type
            groupVisibleLimit = 80
        }
        libraryMode = type
        libraryCategoryRow.visibility = View.GONE
        libraryContainer.visibility = View.VISIBLE
        libraryContainer.removeAllViews()
        val key: (LibrarySong) -> String = when (type) {
            "Előadók" -> { song -> song.artist.ifBlank { "Ismeretlen előadó" } }
            "Albumok" -> { song -> song.album.ifBlank { "Ismeretlen album" } }
            else -> { song -> song.folder.ifBlank { "Ismeretlen mappa" } }
        }
        val groups = librarySongs.groupBy(key).toSortedMap(String.CASE_INSENSITIVE_ORDER)
        libraryContainer.addView(label("‹  Vissza az összes dalhoz", 13f, Color.rgb(220, 195, 255)).apply {
            setPadding(dp(8), dp(12), dp(8), dp(14))
            setOnClickListener { showLibraryHome() }
        })
        libraryContainer.addView(label(type.uppercase() + "  •  ${groups.size}", 11f, Color.WHITE).apply { setPadding(dp(8), dp(4), dp(8), dp(10)) })
        groups.entries.take(groupVisibleLimit).forEach { entry ->
            val name = entry.key
            val songs = entry.value
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(9), dp(13), dp(9), dp(13)); isClickable = true; background = rounded(Color.argb(35,255,255,255), 10) }
            row.addView(label("♫", 19f, Color.rgb(194,151,255)), LinearLayout.LayoutParams(dp(34), -2))
            row.addView(label(name, 13f, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(label("${songs.size} dal  ›", 10f, Color.rgb(190,195,220)))
            row.setOnClickListener {
                activeLibraryRenderer = { showLibraryGroupSongs(type, name) }
                showLibraryGroupSongs(type, name)
            }
            libraryContainer.addView(row)
        }
        if (groups.size > groupVisibleLimit) {
            val remaining = groups.size - groupVisibleLimit
            libraryContainer.addView(button("TOVÁBBI ${minOf(80, remaining)} ${type.lowercase()}  •  MÉG $remaining", Color.rgb(73, 54, 135), Color.WHITE) {
                groupVisibleLimit += 80
                showLibraryGroups(type)
            }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) })
        }
    }

    private fun showLibraryGroupSongs(type: String, name: String) {
        val key: (LibrarySong) -> String = when (type) {
            "Előadók" -> { song -> song.artist.ifBlank { "Ismeretlen előadó" } }
            "Albumok" -> { song -> song.album.ifBlank { "Ismeretlen album" } }
            else -> { song -> song.folder.ifBlank { "Ismeretlen mappa" } }
        }
        val songs = librarySongs.filter { key(it) == name }
        libraryMode = type + "_songs"
        libraryCategoryRow.visibility = View.GONE
        libraryContainer.visibility = View.VISIBLE
        libraryContainer.removeAllViews()
        libraryContainer.addView(label("‹  Vissza a $type listához", 12f, gold).apply {
            setPadding(dp(8), dp(12), dp(8), dp(12))
            setOnClickListener { showLibraryGroups(type) }
        })
        songs.forEachIndexed { index, song ->
            val row = label("♫  ${song.title}  —  ${song.artist}", 12f, Color.WHITE).apply {
                setPadding(dp(8), dp(12), dp(8), dp(12))
                setOnClickListener {
                    queue.clear()
                    queue.addAll(songs.map { it.uri })
                    currentIndex = index
                    startTrack(song.uri)
                    updateNextTrack()
                }
            }
            markLibrarySongRow(row, song.uri)
            libraryContainer.addView(row)
        }
    }

    private fun showFolderRoot() {
        val root = android.os.Environment.getExternalStorageDirectory().absolutePath
        renderFolderBrowser(root, true)
    }

    private fun showFolderBrowser(path: String) {
        renderFolderBrowser(path, false)
    }

    private fun renderFolderBrowser(path: String, isRoot: Boolean) {
        activeLibraryRenderer = { renderFolderBrowser(path, isRoot) }
        libraryMode = "Mappák"
        libraryCategoryRow.visibility = View.GONE
        libraryContainer.visibility = View.VISIBLE
        libraryContainer.removeAllViews()
        val root = android.os.Environment.getExternalStorageDirectory().absolutePath
        libraryContainer.addView(label("‹  Vissza", 13f, Color.rgb(220,195,255)).apply {
            setPadding(dp(8), dp(12), dp(8), dp(10))
            setOnClickListener {
                if (isRoot) showLibraryHome()
                else {
                    val parent = java.io.File(path).parentFile?.absolutePath
                    if (parent != null && parent.startsWith(root) && parent != root) showFolderBrowser(parent)
                    else showFolderRoot()
                }
            }
        })
        libraryContainer.addView(label(if (isRoot) "MAPPÁK" else path, 10f, Color.rgb(190,195,220)).apply {
            setPadding(dp(8), dp(3), dp(8), dp(10))
        })

        if (selectedFolderPaths.isNotEmpty()) {
            libraryContainer.addView(button("▶  KIJELÖLT MAPPÁK LEJÁTSZÁSA  •  ${selectedFolderPaths.size}", Color.rgb(91,67,157), Color.WHITE) {
                val chosen = librarySongs.filter { song ->
                    selectedFolderPaths.any { folder ->
                        song.folder == folder || song.folder.startsWith(folder.trimEnd(java.io.File.separatorChar) + java.io.File.separator)
                    }
                }.distinctBy { it.uri.toString() }.sortedWith(compareBy<LibrarySong> { it.folder.lowercase() }.thenBy { it.title.lowercase() })
                if (chosen.isEmpty()) {
                    toast("A kijelölt mappákban nincs lejátszható zene.")
                } else {
                    queue.clear()
                    queue.addAll(chosen.map { it.uri })
                    currentIndex = 0
                    selectedFolderPaths.clear()
                    startTrack(queue[0])
                    updateNextTrack()
                    toast("${queue.size} dal indul a kijelölt mappákból.")
                    renderFolderBrowser(path, isRoot)
                }
            }, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
            libraryContainer.addView(label("Hosszan nyomd meg a további mappákat a kijelöléshez. Koppintással is hozzáadhatsz, ha már van kijelölés.", 10f, Color.rgb(190,195,220)).apply {
                setPadding(dp(8), dp(4), dp(8), dp(8))
            })
            libraryContainer.addView(label("Kijelölés törlése", 11f, gold).apply {
                setPadding(dp(8), dp(7), dp(8), dp(7))
                setOnClickListener { selectedFolderPaths.clear(); renderFolderBrowser(path, isRoot) }
            })
        }

        val prefix = path.trimEnd(java.io.File.separatorChar) + java.io.File.separator
        val children = librarySongs.map { it.folder }
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix).substringBefore(java.io.File.separator) }
            .filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }

        children.forEach { name ->
            val child = java.io.File(path, name).absolutePath
            val count = librarySongs.count { it.folder == child || it.folder.startsWith(child + java.io.File.separator) }
            val selected = selectedFolderPaths.contains(child)
            libraryContainer.addView(label((if (selected) "☑  " else "📁  ") + name + "   •   " + count + " dal  ›", 13f, if (selected) gold else Color.WHITE).apply {
                setPadding(dp(10), dp(13), dp(8), dp(13))
                setOnClickListener {
                    if (selectedFolderPaths.isNotEmpty()) {
                        if (selected) selectedFolderPaths.remove(child) else selectedFolderPaths.add(child)
                        renderFolderBrowser(path, isRoot)
                    } else showFolderBrowser(child)
                }
                setOnLongClickListener {
                    if (selected) selectedFolderPaths.remove(child) else selectedFolderPaths.add(child)
                    renderFolderBrowser(path, isRoot)
                    true
                }
            })
        }

        val songs = librarySongs.filter { it.folder == path }.sortedBy { it.title.lowercase() }
        songs.forEachIndexed { index, song ->
            val row = label("♫  " + song.title + "  —  " + song.artist, 12f, Color.WHITE).apply {
                setPadding(dp(9), dp(11), dp(8), dp(11))
                setOnClickListener {
                    queue.clear()
                    queue.addAll(songs.map { it.uri })
                    currentIndex = index
                    startTrack(song.uri)
                    updateNextTrack()
                }
            }
            markLibrarySongRow(row, song.uri)
            libraryContainer.addView(row)
        }
        if (children.isEmpty() && songs.isEmpty()) libraryContainer.addView(label("Ebben a mappában nincs beolvasott zene.", 12f, Color.rgb(190,194,215)))
    }

    private fun renderLibrary(query: String = "") {
        if (!::libraryContainer.isInitialized) return
        activeLibraryRenderer = { renderLibrary(query) }
        libraryMode = "all"
        libraryCategoryRow.visibility = View.GONE
        libraryContainer.visibility = View.VISIBLE
        libraryContainer.removeAllViews()
        libraryContainer.addView(label("‹  Vissza a könyvtárhoz", 13f, Color.rgb(220,195,255)).apply { setPadding(dp(8), dp(12), dp(8), dp(10)); setOnClickListener { showLibraryHome() } })
        if (query != lastLibraryQuery) {
            lastLibraryQuery = query
            libraryVisibleLimit = 80
        }
        val filtered = librarySongs.filter { it.title.contains(query, true) || it.artist.contains(query, true) }.let { songs -> when (librarySortMode) { "Előadó szerint" -> songs.sortedBy { it.artist.lowercase() }; "Legújabb elöl" -> songs.sortedByDescending { it.dateAdded }; "Legrégebbi elöl" -> songs.sortedBy { it.dateAdded }; else -> songs.sortedBy { it.title.lowercase() } } }
        if (filtered.isEmpty()) {
            libraryContainer.addView(label(if (librarySongs.isEmpty()) "Még nincs zene a könyvtárban. Adj hozzá fájlokat vagy frissítsd a listát." else "Nincs találat erre a keresésre.", 12f, Color.rgb(190, 194, 215)).apply { setPadding(dp(8), dp(14), dp(8), dp(14)) })
            return
        }
        val end = minOf(filtered.size, libraryVisibleLimit)
        for (index in 0 until end) {
            val song = filtered[index]
            val item = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(10), dp(8), dp(10)); isClickable = true; isFocusable = true; background = rounded(Color.argb(35, 255, 255, 255), 12) }
            val coverIcon = label("♫", 20f, Color.rgb(194, 151, 255)).apply { gravity = Gravity.CENTER }
            item.addView(coverIcon, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(10) })
            val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            names.addView(label(song.title, 13f, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            names.addView(label(song.artist, 11f, Color.rgb(190, 195, 220)).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            item.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
            markLibrarySongRow(item, song.uri)
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
        if (filtered.size > end) {
            val remaining = filtered.size - end
            libraryContainer.addView(button("TOVÁBBI ${minOf(80, remaining)} DAL BETÖLTÉSE  •  MÉG $remaining", Color.rgb(73, 54, 135), Color.WHITE) {
                libraryVisibleLimit += 80
                renderLibrary(query)
            }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) })
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
            return        }
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
        eqProcessor.onLevel = { left, right ->
            // A PCM feldolgozó előre megkapja a hangmintákat az AudioTrack pufferéhez.
            // A rövid késleltetés a VU-kijelzést a tényleges hallható hanghoz igazítja.
            handler.postDelayed({
                if (player?.isPlaying == true) {
                    setVuLevels((left * 2f).coerceIn(0f, 1f), (right * 2f).coerceIn(0f, 1f))
                }
            }, 200L)
        }
        try {
            selectedUri = uri
            readMetadata(uri)
            updateNextTrack()
            refreshLibrarySelection()
            val renderersFactory = object : DefaultRenderersFactory(this) {
                override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink {
                    return DefaultAudioSink.Builder(context).setEnableFloatOutput(false)
                        .setAudioProcessors(arrayOf(eqProcessor)).build()
                }
            }
            player = ExoPlayer.Builder(this, renderersFactory).build().also { exo ->
                exo.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> if (exo.playWhenReady) {
                                setVuActive(true); playButton.text = "Ⅱ"; vinylView.invalidate()
                                handler.removeCallbacks(progressTask); handler.post(progressTask)
                            }
                            Player.STATE_ENDED -> {
                                setVuActive(false)
                                if (currentIndex + 1 < queue.size) {
                                    currentIndex++; selectedUri = queue[currentIndex]; updateNextTrack(); startTrack(queue[currentIndex])
                                } else { playButton.text = "▶"; updateNextTrack() }
                            }
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        playButton.text = "▶"; toast("Lejátszási hiba: " + (error.message ?: "ismeretlen hiba"))
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        setVuActive(isPlaying); playButton.text = if (isPlaying) "Ⅱ" else "▶"; vinylView.invalidate()
                    }
                })
                exo.setMediaItem(MediaItem.fromUri(uri)); exo.prepare(); exo.playWhenReady = true
            }
        } catch (e: Exception) {
            try { player?.release() } catch (_: Exception) {}
            player = null; playButton.text = "▶"
            toast("Nem sikerült megnyitni: " + (e.message ?: "ismeretlen hiba"))
        }
    }
    private fun togglePlayback() {
        val p = player
        if (p == null) { selectedUri?.let { startTrack(it) } ?: openAudio(); return }
        try {
            if (p.isPlaying) { p.pause(); setVuActive(false); playButton.text = "▶"; vinylView.invalidate() }
            else { p.play(); setVuActive(true); playButton.text = "Ⅱ"; vinylView.invalidate() }
        } catch (_: Exception) {}
    }
    private fun renderEqualizer() {
        if (!::eqContainer.isInitialized) return
        eqContainer.removeAllViews()
        val prefs=getSharedPreferences("aurix_settings",MODE_PRIVATE)
        val names=listOf("31 Hz","62 Hz","125 Hz","250 Hz","500 Hz","1 kHz","2 kHz","4 kHz","8 kHz","12 kHz","14 kHz","16 kHz")
        eqContainer.addView(label("12 SÁVOS SAJÁT DIGITÁLIS EQ  •  ±12 dB",11f,gold))
        names.forEachIndexed { index,name ->
            val saved=prefs.getInt("eq_band_$index",0).coerceIn(-12,12)
            eqProcessor.setBandGainDb(index,saved.toFloat())
            addSlider(eqContainer,name+"  "+saved+" dB",0,240,saved+120){value->
                val db=value-120;eqProcessor.setBandGainDb(index,db.toFloat());prefs.edit().putInt("eq_band_$index",db).apply()
            }
        }
        val bass=prefs.getInt("eq_bass",0).coerceIn(0,120);eqProcessor.setBassBoostDb(bass/10f)
        eqContainer.addView(label("MÉLYHANG KIEMELÉS",11f,gold).apply{setPadding(0,dp(12),0,0)})
        addSlider(eqContainer,"Basszus kiemelés",0,120,bass){v->eqProcessor.setBassBoostDb(v/10f);prefs.edit().putInt("eq_bass",v).apply()}
        val treble=prefs.getInt("eq_treble",0).coerceIn(0,120);eqProcessor.setTrebleBoostDb(treble/10f)
        addSlider(eqContainer,"Magas hangok kiemelése",0,120,treble){v->eqProcessor.setTrebleBoostDb(v/10f);prefs.edit().putInt("eq_treble",v).apply()}
        val preamp=prefs.getInt("eq_preamp",0).coerceIn(-6,6);eqProcessor.setPreampDb(preamp.toFloat())
        addSlider(eqContainer,"Előerősítés (−6…+6 dB)",0,12,preamp+6){v->val db=v-6;eqProcessor.setPreampDb(db.toFloat());prefs.edit().putInt("eq_preamp",db).apply()}
        addSlider(eqContainer,"Lejátszó hangerő",0,100,100){v->player?.volume=(v/100f).coerceIn(0f,1f)}
        eqContainer.addView(label("Csak az AURA zenéjét módosítja. Erős kiemelésnél a digitális csúcsok korlátozva vannak.",10f,Color.GRAY).apply{setPadding(0,dp(8),0,0)})
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
    private fun formatTime(ms: Long): String {
        val total = (ms.coerceAtLeast(0L) / 1000L).toInt()
        return "%02d:%02d".format(total / 60, total % 60)
    }
    private fun updateProgress() {
        val p = player ?: return
        try {
            val duration = p.duration; val position = p.currentPosition
            if (duration > 0) seekBar.progress = (position * 1000L / duration).toInt()
            vinylView.invalidate()
            val timeText = if (showRemaining) "-${formatTime(duration-position)}  /  ${formatTime(duration)}" else "${formatTime(position)}  /  ${formatTime(duration)}"
            val trackCount = if (queue.isEmpty()) "0 / 0" else "${(currentIndex + 1).coerceAtMost(queue.size)} / ${queue.size}"
            timeLabel.text = "$timeText   •   $trackCount"
        } catch (_: Exception) {}
    }

    private fun resetEq() {
        val prefs=getSharedPreferences("aurix_settings",MODE_PRIVATE);val edit=prefs.edit()
        for(i in 0 until 12){eqProcessor.setBandGainDb(i,0f);edit.putInt("eq_band_$i",0)}
        eqProcessor.setBassBoostDb(0f);eqProcessor.setTrebleBoostDb(0f);eqProcessor.setPreampDb(0f)
        edit.putInt("eq_bass",0).putInt("eq_treble",0).putInt("eq_preamp",0).apply()
        renderEqualizer();toast("AURA EQ visszaállítva.")
    }
    private fun releaseAudio() {
        handler.removeCallbacks(progressTask)
        if (::vu.isInitialized) {
            setVuActive(false)
            setVuLevels(0f, 0f)
        }
        try { player?.release() } catch (_: Exception) {}; player = null
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    override fun onDestroy() { releaseAudio(); libraryExecutor.shutdownNow(); super.onDestroy() }

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