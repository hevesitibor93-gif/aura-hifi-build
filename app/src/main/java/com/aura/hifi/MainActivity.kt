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
import androidx.media3.common.MediaMetadata
import androidx.media3.common.ForwardingPlayer
import androidx.media3.session.MediaSession
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
    private val backupExportRequest = 7102
    private val backupImportRequest = 7103
    private val player: ExoPlayer? get() = AuraPlaybackService.player
    private val eqProcessor get() = AuraPlaybackService.eqProcessor
    private val soundEngineProcessor get() = AuraPlaybackService.soundEngineProcessor
    private var playbackListener: Player.Listener? = null
    private var playbackRestoreAttempts = 0
    private var pendingTrackStartUri: Uri? = null
    private var pendingTrackStartAttempts = 0
    private var pendingTrackStartRunnable: Runnable? = null
    private var clippingStatusLabel: TextView? = null
    private var eqClippingActive = false
    private var soundEngineClippingActive = false

    private var soundOutputProfile = "default"
    private var soundOutputDeviceLabel = "Telefonhangszóró"
    private var preferredOutputDeviceId: Int? = null
    private var forceDefaultOutputProfile = false
    private var soundProfileStatusRefresher: (() -> Unit)? = null
    private val soundProfileRefreshers = mutableListOf<() -> Unit>()
    private val soundProfileKeys = listOf(
        "sound_dsp_bypass",
        "sound_bass_depth_enabled", "sound_bass_depth_db",
        "sound_bass_punch_enabled", "sound_bass_punch_db",
        "sound_harmonic_bass_enabled", "sound_harmonic_bass",
        "sound_dynamic_punch_enabled", "sound_dynamic_punch",
        "sound_clarity_enabled", "sound_clarity_db",
        "sound_tone_enabled", "sound_tone_amount",
        "sound_stereo_width_enabled", "sound_stereo_width",
        "sound_balance_enabled", "sound_balance",
        "sound_preamp_enabled", "sound_preamp_db",
        "eq_bass", "eq_treble", "eq_preamp",
        "eq_band_0", "eq_band_1", "eq_band_2", "eq_band_3",
        "eq_band_4", "eq_band_5", "eq_band_6", "eq_band_7",
        "eq_band_8", "eq_band_9", "eq_band_10", "eq_band_11"
    )
    private lateinit var vu: VuView
    private var sideVuLeft: VuView? = null
    private var sideVuRight: VuView? = null
    private var centerAurixVu: AurixVuView? = null
    private var settingsPreviewVu: VuView? = null
    private lateinit var artworkView: ImageView
    private lateinit var vinylView: VinylView
    private var showCover = true
    private var currentArtworkData: ByteArray? = null
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
    @Volatile private var vuThreshold = 0.035f
    @Volatile private var vuGain = 1.20f
    @Volatile private var vuDelayMs = 400L
    @Volatile private var vuMono = false
    @Volatile private var vuProfile = "speaker"
    private var vuProfileControls: (() -> Unit)? = null
    private var audioDeviceCallback: android.media.AudioDeviceCallback? = null
    private val queue = mutableListOf<Uri>()
    private data class LibrarySong(val uri: Uri, val title: String, val artist: String, val album: String, val folder: String, val dateAdded: Long, val fileName: String, val albumId: Long)
    private val librarySongs = mutableListOf<LibrarySong>()
    private val libraryExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val artworkExecutor = java.util.concurrent.Executors.newFixedThreadPool(2)
    private val artworkCache = object : android.util.LruCache<Long, Bitmap>(6 * 1024) {
        override fun sizeOf(key: Long, bitmap: Bitmap): Int = (bitmap.byteCount / 1024).coerceAtLeast(1)
    }
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
    private var openPlayerPage: (() -> Unit)? = null
    private var openVuSettingsPage: (() -> Unit)? = null
    private var openSoundEnginePage: (() -> Unit)? = null
    private var shuffleMode = 0
    private var repeatMode = 0
    private var shuffleOriginalQueue: List<Uri>? = null
    private var shuffleButton: Button? = null
    private var repeatButton: Button? = null
    private val abQuickButtons = mutableMapOf<String, Button>()
    private var abQuickStatus: TextView? = null
    private var selectAbQuickSlot: ((String) -> Unit)? = null
    private var refreshAbQuickButtons: (() -> Unit)? = null
    private lateinit var nextTrackLabel: TextView
    private lateinit var queueContainer: LinearLayout
    private val bg = Color.rgb(9, 11, 16)
    private val panel = Color.rgb(20, 24, 33)
    private val gold = Color.rgb(255, 184, 0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startService(Intent(this, AuraPlaybackService::class.java))
        window.statusBarColor = bg; window.navigationBarColor = bg
        val prefs = getSharedPreferences("aurix_settings", MODE_PRIVATE)
        if (!prefs.getBoolean("eq_safe_neutral_reset_v1", false)) {
            val safeEq = prefs.edit().putInt("eq_bass", 0).putInt("eq_treble", 0)
            for (i in 0 until 12) safeEq.putInt("eq_band_${i}", 0)
            safeEq.putBoolean("eq_safe_neutral_reset_v1", true).apply()
        }
        ensureSoundProfileDefaults(prefs)
        vuProfile = detectVuOutputProfile()
        val initialSoundTarget = resolveSoundOutputProfile()
        soundOutputDeviceLabel = initialSoundTarget.second
        initializeSoundOutputProfiles(prefs, initialSoundTarget.first)
        loadAuraSoundEngineSettings(prefs)
        applyEqualizerSettings(prefs)
        loadVuProfile(vuProfile)
        val allowRotation = prefs.getBoolean("screen_rotation", true)
        requestedOrientation = if (allowRotation) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        buildUi()
        handler.postDelayed({ restorePlaybackState() }, 250L)
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        audioDeviceCallback = object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<android.media.AudioDeviceInfo>) {
                val newestExternal = addedDevices.lastOrNull { isExternalAudioOutput(it) }
                if (newestExternal != null) {
                    preferredOutputDeviceId = newestExternal.id
                    forceDefaultOutputProfile = false
                }
                refreshVuOutputProfile()
                refreshSoundOutputProfile()
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<android.media.AudioDeviceInfo>) {
                if (removedDevices.any { it.id == preferredOutputDeviceId }) preferredOutputDeviceId = null
                forceDefaultOutputProfile = false
                refreshVuOutputProfile()
                refreshSoundOutputProfile()
            }
        }
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, handler)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("aura_open_player", false)) {
            openPlayerPage?.invoke()
        }
    }

    private fun openBackupExport() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "AURA-HiFi-backup.json")
        }
        startActivityForResult(intent, backupExportRequest)
    }

    private fun openBackupImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        startActivityForResult(intent, backupImportRequest)
    }

    override fun onPause() {
        // Persist the active output profile even if Android later recreates the Activity.
        val prefs = getSharedPreferences("aurix_settings", MODE_PRIVATE)
        if (isSoundProfileSaved(prefs, soundOutputProfile)) saveSoundProfile(prefs, soundOutputProfile)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        // Reattach the meter whenever the screen returns, not only after a track change.
        handler.post {
            val exo = player
            if (exo != null) {
                attachVuMeter()
                setVuActive(exo.isPlaying)
            }
            refreshSoundOutputProfile()
        }
    }


    /**
     * Reconnects the screen to playback that may have continued in AuraPlaybackService
     * while the Activity was closed or recreated.
     */
    private fun restorePlaybackState() {
        // The service is started asynchronously. A fixed 250 ms delay is not
        // enough on every device/startup, so retry until its player is ready.
        val exo = player
        if (exo == null) {
            if (playbackRestoreAttempts < 50) {
                playbackRestoreAttempts++
                handler.postDelayed({ restorePlaybackState() }, 100L)
            }
            return
        }
        playbackRestoreAttempts = 0
        val snapshot = AuraPlaybackService.getQueueSnapshot()
        queue.clear()
        queue.addAll(snapshot.uris)
        currentIndex = snapshot.index.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
        repeatMode = snapshot.repeatMode
        shuffleMode = snapshot.shuffleMode

        // Respect the user's startup preference even when the playback service
        // was already alive in the background before the Activity was opened.
        val resumeOnAppStart = getSharedPreferences("aurix_settings", MODE_PRIVATE)
            .getBoolean("resume_on_app_start", true)
        if (!resumeOnAppStart) exo.pause()
        // Reconnect the EQ tap whenever the Activity is recreated while the service keeps playing.
        attachVuMeter()
        val item = exo.currentMediaItem
        val uri = item?.localConfiguration?.uri
        if (uri != null) {
            selectedUri = uri
            readMetadata(uri)
            val metadata = item.mediaMetadata
            if (trackLabel.text.isNullOrBlank()) trackLabel.text = metadata.title ?: "Ismeretlen cím"
            if (metadataLabel.text.isNullOrBlank()) metadataLabel.text = metadata.artist ?: "Ismeretlen előadó"
            if (albumLabel.text.isNullOrBlank()) albumLabel.text = metadata.albumTitle ?: "Ismeretlen album"
            val queuePosition = queue.indexOf(uri)
            if (queuePosition >= 0) currentIndex = queuePosition
            updateNextTrack()
            renderQueue()

            // The queue is now restored, so Activity controls can safely handle
            // Bluetooth next/previous requests while this screen is alive.
            if (queue.isNotEmpty()) {
                AuraPlaybackService.onPreviousRequested = { playPrevious() }
                AuraPlaybackService.onNextRequested = { playNext() }
            }
        }

        playbackListener?.let { exo.removeListener(it) }
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) updateProgress()
                if (playbackState == Player.STATE_ENDED) {
                    setVuActive(false)
                    playButton.text = "▶"
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                setVuActive(isPlaying)
                playButton.text = if (isPlaying) "Ⅱ" else "▶"
                vinylView.invalidate()
                if (isPlaying) {
                    handler.removeCallbacks(progressTask)
                    handler.post(progressTask)
                } else {
                    handler.removeCallbacks(progressTask)
                    updateProgress()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val currentUri = mediaItem?.localConfiguration?.uri ?: return
                selectedUri = currentUri
                val index = queue.indexOf(currentUri)
                if (index >= 0) currentIndex = index
                readMetadata(currentUri)
                updateNextTrack()
                renderQueue()
                updateProgress()
            }
        }
        playbackListener = listener
        exo.addListener(listener)

        val isPlaying = exo.isPlaying
        setVuActive(isPlaying)
        playButton.text = if (isPlaying) "Ⅱ" else "▶"
        vinylView.invalidate()
        if (isPlaying) {
            handler.removeCallbacks(progressTask)
            handler.post(progressTask)
        } else {
            updateProgress()
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



    private fun ensureSoundProfileDefaults(prefs: android.content.SharedPreferences) {
        val editor = prefs.edit()
        if (!prefs.contains("sound_dsp_bypass")) editor.putBoolean("sound_dsp_bypass", true)
        if (!prefs.contains("sound_bass_depth_enabled")) editor.putBoolean("sound_bass_depth_enabled", false)
        if (!prefs.contains("sound_bass_depth_db")) editor.putInt("sound_bass_depth_db", 0)
        if (!prefs.contains("sound_bass_punch_enabled")) editor.putBoolean("sound_bass_punch_enabled", false)
        if (!prefs.contains("sound_bass_punch_db")) editor.putInt("sound_bass_punch_db", 0)
        if (!prefs.contains("sound_harmonic_bass_enabled")) editor.putBoolean("sound_harmonic_bass_enabled", false)
        if (!prefs.contains("sound_harmonic_bass")) editor.putInt("sound_harmonic_bass", 0)
        if (!prefs.contains("sound_dynamic_punch_enabled")) editor.putBoolean("sound_dynamic_punch_enabled", false)
        if (!prefs.contains("sound_dynamic_punch")) editor.putInt("sound_dynamic_punch", 35)
        if (!prefs.contains("sound_clarity_enabled")) editor.putBoolean("sound_clarity_enabled", false)
        if (!prefs.contains("sound_clarity_db")) editor.putInt("sound_clarity_db", 0)
        if (!prefs.contains("sound_tone_enabled")) editor.putBoolean("sound_tone_enabled", false)
        if (!prefs.contains("sound_tone_amount")) editor.putInt("sound_tone_amount", 0)
        if (!prefs.contains("sound_stereo_width_enabled")) editor.putBoolean("sound_stereo_width_enabled", false)
        if (!prefs.contains("sound_stereo_width")) editor.putInt("sound_stereo_width", 100)
        if (!prefs.contains("sound_balance_enabled")) editor.putBoolean("sound_balance_enabled", false)
        if (!prefs.contains("sound_balance")) editor.putInt("sound_balance", 0)
        if (!prefs.contains("sound_preamp_enabled")) editor.putBoolean("sound_preamp_enabled", false)
        if (!prefs.contains("sound_preamp_db")) editor.putInt("sound_preamp_db", 0)
        if (!prefs.contains("eq_bass")) editor.putInt("eq_bass", 0)
        if (!prefs.contains("eq_treble")) editor.putInt("eq_treble", 0)
        if (!prefs.contains("eq_preamp")) editor.putInt("eq_preamp", 0)
        for (i in 0 until 12) if (!prefs.contains("eq_band_$i")) editor.putInt("eq_band_$i", 0)
        editor.apply()
    }

    private fun initializeSoundOutputProfiles(prefs: android.content.SharedPreferences, activeProfile: String) {
        val initialized = prefs.getBoolean("sound_profiles_initialized_v1", false)
        if (!initialized) {
            // Preserve the old two-output snapshots during the migration.
            saveSoundProfile(prefs, "speaker")
            saveSoundProfile(prefs, "bluetooth")
            prefs.edit().putBoolean("sound_profiles_initialized_v1", true).apply()
        }
        if (!prefs.getBoolean("sound_profiles_initialized_v2", false)) {
            // The current, already-tested sound becomes the universal starting profile.
            saveSoundProfile(prefs, "default")
            prefs.edit().putBoolean("sound_profiles_initialized_v2", true).apply()
        }
        soundOutputProfile = activeProfile
        loadSoundProfile(prefs, activeProfile)
    }

    private fun isSoundProfileSaved(prefs: android.content.SharedPreferences, profile: String): Boolean =
        prefs.getBoolean("sound_profile_${profile}_saved", false)

    private fun saveSoundProfile(prefs: android.content.SharedPreferences, profile: String) {
        val prefix = "sound_profile_${profile}_"
        val editor = prefs.edit()
        val all = prefs.all
        soundProfileKeys.forEach { key ->
            when (val value = all[key]) {
                is Boolean -> editor.putBoolean(prefix + key, value)
                is Int -> editor.putInt(prefix + key, value)
                is Long -> editor.putLong(prefix + key, value)
                is Float -> editor.putFloat(prefix + key, value)
                is String -> editor.putString(prefix + key, value)
            }
        }
        editor.putBoolean(prefix + "saved", true).apply()
    }

    private fun removeSoundProfile(prefs: android.content.SharedPreferences, profile: String) {
        if (profile == "default") return
        val editor = prefs.edit()
        soundProfileKeys.forEach { editor.remove("sound_profile_${profile}_$it") }
        editor.remove("sound_profile_${profile}_saved").apply()
    }

    private fun loadSoundProfile(prefs: android.content.SharedPreferences, profile: String) {
        val sourceProfile = if (profile != "default" && !isSoundProfileSaved(prefs, profile)) "default" else profile
        val prefix = "sound_profile_${sourceProfile}_"
        val editor = prefs.edit()
        soundProfileKeys.forEach { key ->
            val storedKey = prefix + key
            if (!prefs.contains(storedKey)) return@forEach
            when (val value = prefs.all[storedKey]) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
            }
        }
        editor.apply()
        loadAuraSoundEngineSettings(prefs)
        applyEqualizerSettings(prefs)
        if (::eqContainer.isInitialized) renderEqualizer()
        soundProfileRefreshers.forEach { it() }
    }

    private fun applyEqualizerSettings(prefs: android.content.SharedPreferences) {
        for (i in 0 until 12) eqProcessor.setBandGainDb(i, prefs.getInt("eq_band_$i", 0).coerceIn(-12, 12).toFloat())
        eqProcessor.setBassBoostDb(prefs.getInt("eq_bass", 0).coerceIn(0, 120) / 10f)
        eqProcessor.setTrebleBoostDb(prefs.getInt("eq_treble", 0).coerceIn(0, 120) / 10f)
        eqProcessor.setPreampDb(prefs.getInt("eq_preamp", 0).coerceIn(-6, 6).toFloat())
    }

    private fun isBluetoothAudioOutput(device: android.media.AudioDeviceInfo): Boolean =
        device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= 31 && (device.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET ||
                device.type == android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER))

    private fun isExternalAudioOutput(device: android.media.AudioDeviceInfo): Boolean =
        device.type != android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER &&
            device.type != android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE

    private fun audioDeviceProfileKey(device: android.media.AudioDeviceInfo): String {
        val address = if (Build.VERSION.SDK_INT >= 28) device.address.trim() else ""
        val identity = if (address.isNotBlank()) "${device.type}|$address"
            else "${device.type}|${device.productName.toString().trim().lowercase(java.util.Locale.ROOT)}"
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
        val suffix = digest.take(8).joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 0xff) }
        return "device_$suffix"
    }

    private fun audioDeviceDisplayName(device: android.media.AudioDeviceInfo): String {
        val name = device.productName.toString().trim().ifBlank { "Ismeretlen eszköz" }
        val type = when (device.type) {
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Vezetékes"
            android.media.AudioDeviceInfo.TYPE_USB_DEVICE,
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
            android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB"
            else -> "Külső kimenet"
        }
        return "$name • $type"
    }

    private fun resolveSoundOutputProfile(): Pair<String, String> {
        if (forceDefaultOutputProfile) return "default" to "Alapértelmezett / telefonhangszóró"
        val outputs = (getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager)
            .getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
            .filter { isExternalAudioOutput(it) }
        val selected = preferredOutputDeviceId?.let { id -> outputs.firstOrNull { it.id == id } }
            ?: outputs.lastOrNull()
        if (selected == null) {
            preferredOutputDeviceId = null
            return "default" to "Telefonhangszóró / alapértelmezett"
        }
        preferredOutputDeviceId = selected.id
        return audioDeviceProfileKey(selected) to audioDeviceDisplayName(selected)
    }

    private fun showOutputDeviceChooser() {
        val prefs = getSharedPreferences("aurix_settings", MODE_PRIVATE)
        val outputs = (getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager)
            .getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
            .filter { isExternalAudioOutput(it) }
        val entries = mutableListOf<Pair<String, String>>()
        entries.add("Alapértelmezett / telefonhangszóró" to "default")
        outputs.forEach { device ->
            entries.add(audioDeviceDisplayName(device) to audioDeviceProfileKey(device))
        }
        val labels = entries.map { (name, profile) ->
            val active = profile == soundOutputProfile
            val saved = profile != "default" && isSoundProfileSaved(prefs, profile)
            buildString {
                append(if (active) "✓  " else "   ")
                append(name)
                append(if (saved) "  •  SAJÁT PROFIL" else "  •  ALAP PROFIL")
            }
        }
        val subtitle = "Válaszd ki a kimenetet. A saját profil csak akkor aktív, ha külön elmented."
        android.app.AlertDialog.Builder(this)
            .setTitle("KIMENETI ESZKÖZ PROFILJA")
            .setMessage(subtitle)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) {
                    preferredOutputDeviceId = null
                    forceDefaultOutputProfile = true
                } else {
                    preferredOutputDeviceId = outputs[which - 1].id
                    forceDefaultOutputProfile = false
                }
                refreshSoundOutputProfile(true)
            }
            .setNegativeButton("MÉGSE", null)
            .show()
    }

    private fun refreshSoundOutputProfile(force: Boolean = false) {
        val (detected, label) = resolveSoundOutputProfile()
        if (!force && detected == soundOutputProfile) {
            soundOutputDeviceLabel = label
            soundProfileStatusRefresher?.invoke()
            return
        }
        val prefs = getSharedPreferences("aurix_settings", MODE_PRIVATE)
        if (isSoundProfileSaved(prefs, soundOutputProfile)) saveSoundProfile(prefs, soundOutputProfile)
        soundOutputProfile = detected
        soundOutputDeviceLabel = label
        loadSoundProfile(prefs, detected)
        soundProfileStatusRefresher?.invoke()
    }

    private fun saveSoundAbSlot(prefs: android.content.SharedPreferences, profile: String, slot: String) {
        val prefix = "sound_ab_${profile}_${slot}_"
        val editor = prefs.edit()
        val all = prefs.all
        soundProfileKeys.forEach { key ->
            when (val value = all[key]) {
                is Boolean -> editor.putBoolean(prefix + key, value)
                is Int -> editor.putInt(prefix + key, value)
                is Long -> editor.putLong(prefix + key, value)
                is Float -> editor.putFloat(prefix + key, value)
                is String -> editor.putString(prefix + key, value)
            }
        }
        editor.putBoolean("sound_ab_${profile}_${slot}_saved", true).apply()
    }

    private fun loadSoundAbSlot(prefs: android.content.SharedPreferences, profile: String, slot: String): Boolean {
        if (!prefs.getBoolean("sound_ab_${profile}_${slot}_saved", false)) return false
        val prefix = "sound_ab_${profile}_${slot}_"
        val editor = prefs.edit()
        soundProfileKeys.forEach { key ->
            val storedKey = prefix + key
            if (!prefs.contains(storedKey)) return@forEach
            when (val value = prefs.all[storedKey]) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
            }
        }
        editor.apply()
        loadAuraSoundEngineSettings(prefs)
        applyEqualizerSettings(prefs)
        if (::eqContainer.isInitialized) renderEqualizer()
        soundProfileRefreshers.forEach { it() }
        return true
    }

    private fun loadAuraSoundEngineSettings(prefs: android.content.SharedPreferences) {
        // Neutral and disabled defaults preserve the original AURA EQ sound.
        val dspActive = !prefs.getBoolean("sound_dsp_bypass", true)
        soundEngineProcessor.setBassDepthEnabled(dspActive && prefs.getBoolean("sound_bass_depth_enabled", false))
        soundEngineProcessor.setBassPunchEnabled(dspActive && prefs.getBoolean("sound_bass_punch_enabled", false))
        soundEngineProcessor.setHarmonicBassEnabled(dspActive && prefs.getBoolean("sound_harmonic_bass_enabled", false))
        soundEngineProcessor.setClarityEnabled(dspActive && prefs.getBoolean("sound_clarity_enabled", false))
        soundEngineProcessor.setToneEnabled(dspActive && prefs.getBoolean("sound_tone_enabled", false))
        soundEngineProcessor.setStereoWidthEnabled(dspActive && prefs.getBoolean("sound_stereo_width_enabled", false))
        soundEngineProcessor.setBalanceEnabled(dspActive && prefs.getBoolean("sound_balance_enabled", false))
        soundEngineProcessor.setPreampEnabled(dspActive && prefs.getBoolean("sound_preamp_enabled", false))
        soundEngineProcessor.setDynamicPunchEnabled(dspActive && prefs.getBoolean("sound_dynamic_punch_enabled", false))
        soundEngineProcessor.setBassDepthDb(prefs.getInt("sound_bass_depth_db", 0) / 100f)
        soundEngineProcessor.setBassPunchDb(prefs.getInt("sound_bass_punch_db", 0) / 100f)
        soundEngineProcessor.setHarmonicBassAmount(prefs.getInt("sound_harmonic_bass", 0) / 100f)
        soundEngineProcessor.setClarityDb(prefs.getInt("sound_clarity_db", 0) / 100f)
        soundEngineProcessor.setToneAmount(prefs.getInt("sound_tone_amount", 0) / 100f)
        soundEngineProcessor.setStereoWidth(prefs.getInt("sound_stereo_width", 100) / 100f)
        soundEngineProcessor.setBalance(prefs.getInt("sound_balance", 0) / 100f)
        soundEngineProcessor.setPreampDb(prefs.getInt("sound_preamp_db", 0) / 100f)
        soundEngineProcessor.setDynamicPunchAmount(prefs.getInt("sound_dynamic_punch", 35) / 100f)
    }

    private fun detectVuOutputProfile(): String {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val bluetoothConnected = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any { device ->
            device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= 31 && (device.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    device.type == android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER))
        }
        return if (bluetoothConnected) "bluetooth" else "speaker"
    }

    private fun loadVuProfile(profile: String) {
        vuProfile = if (profile == "bluetooth") "bluetooth" else "speaker"
        val prefs = getSharedPreferences("aurix_settings", MODE_PRIVATE)
        val prefix = if (vuProfile == "bluetooth") "vu_bluetooth_" else "vu_speaker_"
        val legacyPrefix = "vu_"
        val thresholdDefault = if (vuProfile == "speaker") prefs.getInt("vu_threshold_milli", 35) else 35
        val gainDefault = if (vuProfile == "speaker") prefs.getInt("vu_gain_percent", 120) else 120
        val delayDefault = if (vuProfile == "speaker") prefs.getInt("vu_delay_ms", 400) else 400
        val monoDefault = if (vuProfile == "speaker") prefs.getBoolean("vu_mono", false) else false
        vuThreshold = prefs.getInt(prefix + "threshold_milli", thresholdDefault).coerceIn(10, 250) / 1000f
        vuGain = prefs.getInt(prefix + "gain_percent", gainDefault).coerceIn(50, 200) / 100f
        vuDelayMs = prefs.getInt(prefix + "delay_ms", delayDefault).coerceIn(400, 1400).toLong()
        vuMono = prefs.getBoolean(prefix + "mono", monoDefault)
        prefs.edit()
            .putInt(prefix + "threshold_milli", (vuThreshold * 1000).toInt())
            .putInt(prefix + "gain_percent", (vuGain * 100).toInt())
            .putInt(prefix + "delay_ms", vuDelayMs.toInt())
            .putBoolean(prefix + "mono", vuMono)
            .apply()
        vuProfileControls?.invoke()
    }

    private fun refreshVuOutputProfile() {
        val detected = detectVuOutputProfile()
        if (detected != vuProfile) {
            loadVuProfile(detected)
            runOnUiThread { vuProfileControls?.invoke() }
        }
    }

    private fun saveVuProfileInt(name: String, value: Int) {
        val prefix = if (vuProfile == "bluetooth") "vu_bluetooth_" else "vu_speaker_"
        getSharedPreferences("aurix_settings", MODE_PRIVATE).edit().putInt(prefix + name, value).apply()
    }

    private fun saveVuProfileBoolean(name: String, value: Boolean) {
        val prefix = if (vuProfile == "bluetooth") "vu_bluetooth_" else "vu_speaker_"
        getSharedPreferences("aurix_settings", MODE_PRIVATE).edit().putBoolean(prefix + name, value).apply()
    }

    private fun setVuActive(value: Boolean) {
        vu.setActive(value); sideVuLeft?.setActive(value); sideVuRight?.setActive(value); centerAurixVu?.setActive(value); settingsPreviewVu?.setActive(value)
    }
    private fun setVuLevels(left: Float, right: Float) {
        vu.setLevels(left, right); sideVuLeft?.setLevels(left, right); sideVuRight?.setLevels(left, right); centerAurixVu?.setLevels(left, right); settingsPreviewVu?.setLevels(left, right)
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
        val themePrefs = getSharedPreferences("aurix_settings", MODE_PRIVATE)
        val goldStudioTheme = themePrefs.getString("aura_theme", "gold_studio") == "gold_studio"
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
        val brand = TextView(this).apply { text = "AURA"; textSize = 24f; letterSpacing = 0.16f; setTextColor(Color.rgb(238, 202, 137)); typeface = Typeface.create("sans-serif-black", Typeface.BOLD) }
        val hi = TextView(this).apply { text = "  HI-FI STUDIO"; textSize = 10f; letterSpacing = 0.13f; setTextColor(Color.rgb(180, 151, 105)); typeface = Typeface.DEFAULT_BOLD }
        header.addView(brand); header.addView(hi); header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        body.addView(header)
        val artFrame = LinearLayout(this).apply { gravity = Gravity.CENTER; background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(39, 35, 29), Color.rgb(13, 16, 22), Color.rgb(31, 29, 26))).apply { cornerRadius = dp(21).toFloat(); setStroke(dp(1), Color.rgb(139, 111, 68)) }; setPadding(dp(6), dp(6), dp(6), dp(6)); elevation = dp(10).toFloat() }
        artworkView = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(82, 65, 39), Color.rgb(18, 20, 26), Color.rgb(47, 37, 24))).apply { cornerRadius = dp(16).toFloat() }; setImageDrawable(null) }
        vinylView = VinylView()
        artFrame.addView(artworkView, LinearLayout.LayoutParams(dp(224), dp(224)))
        artFrame.addView(vinylView, LinearLayout.LayoutParams(dp(224), dp(224)))
        showCover = getSharedPreferences("aurix_settings", MODE_PRIVATE).getBoolean("show_cover", true)
        applyArtworkMode()
        body.addView(artFrame, LinearLayout.LayoutParams(-1, dp(252)).apply { setMargins(dp(2), dp(4), dp(2), dp(4)) })
        applyArtworkMode()
        vu = VuView().apply { isClickable = true; setOnClickListener { openVuSettingsPage?.invoke() } }; body.addView(vu, LinearLayout.LayoutParams(-1, dp(44)).apply { setMargins(dp(4), 0, dp(4), 0); gravity = Gravity.CENTER })
        trackLabel = label("", 20f, Color.rgb(242, 211, 157)).apply { gravity = Gravity.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); letterSpacing = 0.015f; setPadding(dp(4), dp(5), dp(4), dp(3)); maxLines = 1; isClickable = true; setOnClickListener { openLibraryPage?.invoke() } }
        body.addView(trackLabel)
        metadataLabel = label("", 12f, Color.rgb(184, 177, 160)).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, 0); maxLines = 1 }
        body.addView(metadataLabel)
        albumLabel = label("", 10f, Color.rgb(151, 137, 111)).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(3)); maxLines = 1 }
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
        val transportExtras = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(2), 0, dp(5)) }
        val rewindButton = neonRoundButton("↶ 10", 38, false) { seekBy(-10_000L) }
        val forwardButton = neonRoundButton("10 ↷", 38, false) { seekBy(10_000L) }
        val shuffleControl = button("SHUFFLE: KI", Color.rgb(35, 41, 53), Color.WHITE) { cycleShuffleMode() }
        val repeatControl = button("ISMÉTLÉS: KI", Color.rgb(35, 41, 53), Color.WHITE) { cycleRepeatMode() }
        shuffleButton = shuffleControl; repeatButton = repeatControl
        transportExtras.addView(rewindButton, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(5) })
        transportExtras.addView(shuffleControl, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(5) })
        transportExtras.addView(repeatControl, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(5) })
        transportExtras.addView(forwardButton, LinearLayout.LayoutParams(0, dp(38), 1f))
        val controlStack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        controlStack.addView(controls, LinearLayout.LayoutParams(-1, -2))
        controlStack.addView(transportExtras, LinearLayout.LayoutParams(-1, -2))
        val abQuickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(1), dp(2), dp(2))
        }
        abQuickStatus = label("A/B  •  HANGZÁSVÁLTÁS", 9f, Color.rgb(255, 207, 112)).apply {
            gravity = Gravity.CENTER_VERTICAL
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(5), 0, dp(8), 0)
        }
        abQuickRow.addView(abQuickStatus, LinearLayout.LayoutParams(0, dp(34), 1f))
        listOf("A", "B").forEach { slot ->
            val quickButton = button(slot, Color.rgb(43, 39, 31), Color.WHITE) {
                selectAbQuickSlot?.invoke(slot)
            }
            quickButton.textSize = 11f
            abQuickButtons[slot] = quickButton
            abQuickRow.addView(quickButton, LinearLayout.LayoutParams(dp(52), dp(34)).apply {
                marginStart = dp(4)
            })
        }
        controlStack.addView(abQuickRow, LinearLayout.LayoutParams(-1, -2))
        body.addView(controlStack)
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
        body.addView(label("Saját AURA Hi-Fi hangfeldolgozás • csak az alkalmazás lejátszására hat, a telefon rendszerhangját nem módosítja.", 11f, Color.rgb(155, 163, 177)).apply { setPadding(dp(3), dp(14), dp(3), dp(18)) })
        val playerPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(5, 7, 11), Color.rgb(27, 27, 29), Color.rgb(10, 12, 16), Color.rgb(4, 6, 10))).apply {
                cornerRadius = dp(26).toFloat()
                setStroke(dp(1), Color.rgb(112, 91, 61))
            }
            elevation = dp(12).toFloat()
        }
        val songsPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val eqPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val settingsPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val soundPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(16)) }
        val vuSettingsPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(16)) }
        val pages = listOf(playerPage, songsPage, eqPage, settingsPage, vuSettingsPage, soundPage)
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
            sideVuLeft?.isClickable = true
            sideVuLeft?.setOnClickListener { openVuSettingsPage?.invoke() }
            leftPanel.addView(sideVuLeft, LinearLayout.LayoutParams(-1, dp(82)).apply { setMargins(dp(2), dp(7), dp(2), dp(7)) })
            leftPanel.addView(neonRoundButton("◀", 42, false) { playPrevious() },
                LinearLayout.LayoutParams(dp(42), dp(42)).apply { setMargins(0, dp(9), 0, dp(9)) })
            leftPanel.addView(neonRoundButton("−", 42, false) { player?.volume = 0.65f },
                LinearLayout.LayoutParams(dp(42), dp(42)).apply { setMargins(0, dp(7), 0, dp(7)) })
            leftPanel.addView(label("VOL", 8f, Color.LTGRAY).apply { gravity = Gravity.CENTER })
            rightPanel.addView(label("R", 11f, Color.rgb(255, 190, 65)).apply { gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD })
            sideVuRight = VuView(true).apply { setStyle("car"); setBrightness(getSharedPreferences("aurix_settings", MODE_PRIVATE).getInt("vu_brightness", 80)); setAnimationEnabled(getSharedPreferences("aurix_settings", MODE_PRIVATE).getBoolean("vu_animation", true)); setActive(player?.isPlaying == true) }
            sideVuRight?.isClickable = true
            sideVuRight?.setOnClickListener { openVuSettingsPage?.invoke() }
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
                isClickable = true
                setOnClickListener { openVuSettingsPage?.invoke() }
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
            headUnit.addView(controlStack, LinearLayout.LayoutParams(-1, -2))
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
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                if (goldStudioTheme) intArrayOf(Color.rgb(35, 32, 27), Color.rgb(17, 20, 25)) else intArrayOf(Color.rgb(49, 54, 111), Color.rgb(36, 43, 91))
            ).apply { cornerRadius = dp(22).toFloat(); setStroke(dp(1), if (goldStudioTheme) Color.rgb(139, 111, 68) else Color.rgb(126, 113, 221)) }
            setPadding(dp(18), dp(4), dp(18), dp(4))
        }
        songsPage.addView(librarySearch, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(14) })
        val categoryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun categoryCard(icon: String, title: String, subtitle: String, action: () -> Unit): View {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    if (goldStudioTheme) intArrayOf(Color.rgb(91, 66, 31), Color.rgb(48, 39, 27), Color.rgb(17, 20, 25))
                    else intArrayOf(Color.rgb(130, 83, 194), Color.rgb(91, 83, 174), Color.rgb(24, 61, 133))
                ).apply { cornerRadius = dp(17).toFloat(); setStroke(dp(1), if (goldStudioTheme) Color.rgb(194, 151, 82) else Color.argb(220, 235, 190, 105)) }
                setPadding(dp(5), dp(12), dp(5), dp(12)); isClickable = true; isFocusable = true
                addView(label(icon, 25f, Color.WHITE).apply { gravity = Gravity.CENTER })
                addView(label(title, 12f, Color.WHITE).apply { gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD })
                addView(label(subtitle, 9f, if (goldStudioTheme) Color.rgb(220, 204, 174) else Color.rgb(210, 211, 239)).apply { gravity = Gravity.CENTER })
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
        recentHeader.addView(button("↻ Frissítés", if (goldStudioTheme) Color.rgb(91, 67, 31) else Color.rgb(73, 54, 135), Color.WHITE) { loadMusicLibrary() })
        libraryContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                if (goldStudioTheme) intArrayOf(Color.rgb(28, 27, 25), Color.rgb(13, 16, 21)) else intArrayOf(Color.rgb(36, 39, 83), Color.rgb(20, 27, 60))
            ).apply { cornerRadius = dp(18).toFloat(); setStroke(dp(1), if (goldStudioTheme) Color.rgb(112, 91, 61) else Color.rgb(93, 102, 183)) }
            setPadding(dp(9), dp(6), dp(9), dp(6))
        }
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
        soundPage.addView(label("BEÁLLÍTÁSOK  /  HANG", 10f, Color.rgb(155, 163, 177)).apply { setPadding(dp(2), dp(4), 0, dp(3)) })
        soundPage.addView(label("AURA SOUND ENGINE", 21f, Color.rgb(255, 207, 112)).apply { typeface = Typeface.create("sans-serif-black", Typeface.BOLD); setPadding(dp(2), 0, 0, dp(5)) })
        soundPage.addView(label("Az eredeti AURA EQ változatlan marad. Az új modulok külön aktiválhatók.", 11f, Color.rgb(155, 163, 177)).apply { setPadding(dp(2), 0, 0, dp(12)) })
        soundPage.addView(label("AUTOMATIKUS KIMENETI PROFIL", 10f, Color.rgb(255, 207, 112)).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(dp(2), 0, 0, dp(4)) })
        val soundProfileStatus = label("", 11f, Color.rgb(119, 224, 218)).apply { setPadding(dp(2), 0, 0, dp(8)) }
        fun refreshSoundProfileStatus() {
            val profileName = if (soundOutputProfile == "default") "ALAP PROFIL" else
                if (isSoundProfileSaved(getSharedPreferences("aurix_settings", MODE_PRIVATE), soundOutputProfile)) "SAJÁT ESZKÖZPROFIL" else "AZ ALAP PROFILT HASZNÁLJA"
            soundProfileStatus.text = "AKTÍV KIMENET: $soundOutputDeviceLabel\n$profileName"
        }
        refreshSoundProfileStatus()
        soundPage.addView(soundProfileStatus)
        val soundPrefs = getSharedPreferences("aurix_settings", MODE_PRIVATE)
        soundPage.addView(button("♫  KIMENETI ESZKÖZ / PROFIL KIVÁLASZTÁSA", Color.rgb(35, 55, 67), Color.WHITE) {
            showOutputDeviceChooser()
        }, LinearLayout.LayoutParams(-1, dp(42)).apply { bottomMargin = dp(6) })
        soundPage.addView(button("💾  AKTÍV ESZKÖZPROFIL MENTÉSE", Color.rgb(91, 65, 27), Color.WHITE) {
            saveSoundProfile(soundPrefs, soundOutputProfile)
            refreshSoundProfileStatus()
            toast("Hangprofil elmentve: $soundOutputDeviceLabel")
        }, LinearLayout.LayoutParams(-1, dp(42)).apply { bottomMargin = dp(6) })
        soundPage.addView(button("💾  ALAPBEÁLLÍTÁS MENTÉSE", Color.rgb(31, 69, 76), Color.WHITE) {
            saveSoundProfile(soundPrefs, "default")
            refreshSoundProfileStatus()
            toast("Az AURA alap hangprofil elmentve.")
        }, LinearLayout.LayoutParams(-1, dp(42)).apply { bottomMargin = dp(6) })
        soundPage.addView(button("↺  AKTÍV ESZKÖZ VISSZAÁLLÍTÁSA AZ ALAPRA", Color.rgb(35, 41, 53), Color.WHITE) {
            if (soundOutputProfile == "default") {
                toast("Most az alap profil aktív.")
            } else {
                removeSoundProfile(soundPrefs, soundOutputProfile)
                loadSoundProfile(soundPrefs, "default")
                refreshSoundProfileStatus()
                toast("Az eszköz mostantól az alap profilt használja.")
            }
        }, LinearLayout.LayoutParams(-1, dp(42)).apply { bottomMargin = dp(12) })
        soundProfileStatusRefresher = { refreshSoundProfileStatus() }
        // Keep this list declared before the BYPASS listener, which updates these controls.
        val soundControlViews = mutableListOf<View>()
        soundProfileRefreshers.clear()
        val bypassRow = settingRow("DSP BYPASS", "Az új DSP-modulok kikapcsolása; az EQ aktív marad")
        val bypassSwitch = Switch(this).apply {
            text = ""
            isChecked = soundPrefs.getBoolean("sound_dsp_bypass", true)

        }
        bypassRow.addView(bypassSwitch)
        soundProfileRefreshers.add {
            bypassSwitch.setOnCheckedChangeListener(null)
            bypassSwitch.isChecked = soundPrefs.getBoolean("sound_dsp_bypass", true)
            bypassSwitch.setOnCheckedChangeListener { _, checked ->
                soundPrefs.edit().putBoolean("sound_dsp_bypass", checked).apply()
                soundControlViews.forEach { it.isEnabled = !checked }
                val active = !checked
                soundEngineProcessor.setBassDepthEnabled(active && soundPrefs.getBoolean("sound_bass_depth_enabled", false))
                soundEngineProcessor.setBassPunchEnabled(active && soundPrefs.getBoolean("sound_bass_punch_enabled", false))
                soundEngineProcessor.setHarmonicBassEnabled(active && soundPrefs.getBoolean("sound_harmonic_bass_enabled", false))
                soundEngineProcessor.setClarityEnabled(active && soundPrefs.getBoolean("sound_clarity_enabled", false))
                soundEngineProcessor.setToneEnabled(active && soundPrefs.getBoolean("sound_tone_enabled", false))
                soundEngineProcessor.setStereoWidthEnabled(active && soundPrefs.getBoolean("sound_stereo_width_enabled", false))
                soundEngineProcessor.setBalanceEnabled(active && soundPrefs.getBoolean("sound_balance_enabled", false))
                soundEngineProcessor.setPreampEnabled(active && soundPrefs.getBoolean("sound_preamp_enabled", false))
                soundEngineProcessor.setDynamicPunchEnabled(active && soundPrefs.getBoolean("sound_dynamic_punch_enabled", false))
            }
            soundControlViews.forEach { it.isEnabled = !bypassSwitch.isChecked }
        }
        soundPage.addView(bypassRow)

        // Six compact sections keep the Sound Engine controls easy to navigate.
        val soundCategoryHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val soundCategoryViews = linkedMapOf<String, LinearLayout>()
        val soundCategoryButtons = linkedMapOf<String, TextView>()
        val soundCategoryNames = listOf(
            "bass" to "MÉLY",
            "dynamics" to "DINAMIKA",
            "tone" to "HANGSZÍN",
            "stereo" to "SZTEREÓ",
            "level" to "HANGERŐ",
            "ab" to "A/B TESZT"
        )
        val soundCategoryNav = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun showSoundCategory(key: String) {
            soundCategoryViews.forEach { (name, panel) -> panel.visibility = if (name == key) View.VISIBLE else View.GONE }
            soundCategoryButtons.forEach { (name, item) ->
                val selected = name == key
                item.background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(if (selected) Color.rgb(111, 83, 42) else Color.rgb(35, 42, 58))
                    setStroke(dp(1), if (selected) gold else Color.rgb(67, 76, 98))
                }
                item.setTextColor(if (selected) Color.rgb(255, 224, 160) else Color.rgb(220, 225, 237))
            }
        }
        soundCategoryNames.chunked(3).forEach { group ->
            val navRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            group.forEach { (key, title) ->
                val item = TextView(this).apply {
                    text = title
                    textSize = 10f
                    gravity = Gravity.CENTER
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(4), dp(12), dp(4), dp(12))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { showSoundCategory(key) }
                }
                soundCategoryButtons[key] = item
                navRow.addView(item, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    if (navRow.childCount > 0) marginStart = dp(5)
                })
            }
            soundCategoryNav.addView(navRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) })
        }
        soundPage.addView(label("HANGMOTOR BEÁLLÍTÁSAI", 10f, Color.rgb(255, 207, 112)).apply {
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(2), dp(12), 0, dp(7))
        })
        soundPage.addView(soundCategoryNav)
        soundCategoryNames.forEach { (key, _) ->
            val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            soundCategoryViews[key] = panel
            soundCategoryHost.addView(panel, LinearLayout.LayoutParams(-1, -2))
        }
        soundPage.addView(soundCategoryHost)
        var activeSoundCategory = soundCategoryViews.getValue("bass")
        showSoundCategory("bass")

        soundCategoryViews.getValue("ab").addView(sectionTitle("A/B HANGZÁS-ÖSSZEHASONLÍTÁS"))
        soundCategoryViews.getValue("ab").addView(label(
            "Ments el két külön hangbeállítást, majd válts közöttük lejátszás közben.",
            11f, Color.rgb(174, 178, 187)
        ).apply { setPadding(dp(2), 0, dp(2), dp(8)) })

        val abProfileRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(4))
        }
        val abSaveRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(6))
        }
        val abButtons = mutableMapOf<String, Button>()
        val abStatusLabel = label("", 11f, Color.rgb(255, 207, 112)).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(5), dp(4), dp(8))
        }
        fun isAbSlotSaved(slot: String): Boolean =
            soundPrefs.getBoolean("sound_ab_${soundOutputProfile}_${slot}_saved", false)

        fun refreshAbButtons() {
            val selected = soundPrefs.getString("sound_ab_active_${soundOutputProfile}", "A") ?: "A"
            listOf("A", "B").forEach { slot ->
                val saved = isAbSlotSaved(slot)
                abButtons[slot]?.text = if (saved) "$slot  ✓" else "$slot  +"
                abButtons[slot]?.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    if (selected == slot) gold else Color.rgb(48, 54, 64)
                )
                abButtons[slot]?.setTextColor(if (selected == slot) Color.BLACK else Color.WHITE)
            }
            val other = if (selected == "A") "B" else "A"
            abStatusLabel.text = "AKTÍV: $selected   •   A: ${if (isAbSlotSaved("A")) "mentve" else "üres"}   •   B: ${if (isAbSlotSaved("B")) "mentve" else "üres"}"
            abButtons[other]?.contentDescription = "Váltás a $other hangállapotra"
            abQuickStatus?.text = "A/B  •  AKTÍV: $selected"
            listOf("A", "B").forEach { quickSlot ->
                val quickSelected = selected == quickSlot
                abQuickButtons[quickSlot]?.apply {
                    text = if (isAbSlotSaved(quickSlot)) "$quickSlot  ✓" else "$quickSlot  +"
                    setTextColor(if (quickSelected) Color.BLACK else Color.WHITE)
                    background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                        if (quickSelected) intArrayOf(Color.rgb(255, 207, 112), Color.rgb(210, 151, 52))
                        else intArrayOf(Color.rgb(43, 39, 31), Color.rgb(25, 29, 37))).apply {
                        cornerRadius = dp(10).toFloat()
                        setStroke(dp(1), if (quickSelected) Color.rgb(255, 222, 151) else Color.rgb(116, 87, 43))
                    }
                    contentDescription = "Váltás a $quickSlot hangállapotra"
                }
            }
        }

        fun selectAbSlot(slot: String) {
            if (!isAbSlotSaved(slot)) {
                // An empty slot is initialized from the currently audible settings.
                saveSoundAbSlot(soundPrefs, soundOutputProfile, slot)
            }
            soundPrefs.edit().putString("sound_ab_active_${soundOutputProfile}", slot).apply()
            loadSoundAbSlot(soundPrefs, soundOutputProfile, slot)
            refreshAbButtons()
            refreshAbQuickButtons?.invoke()
        }
        selectAbQuickSlot = { slot -> selectAbSlot(slot) }
        refreshAbQuickButtons = { refreshAbButtons() }

        listOf("A", "B").forEach { slot ->
            val selectButton = Button(this).apply {
                text = slot
                isAllCaps = false
                textSize = 15f
                setOnClickListener { selectAbSlot(slot) }
            }
            abButtons[slot] = selectButton
            abProfileRow.addView(selectButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                marginEnd = dp(4)
            })

            val saveButton = Button(this).apply {
                text = "MENTÉS $slot"
                isAllCaps = false
                textSize = 10f
                setOnClickListener {
                    saveSoundAbSlot(soundPrefs, soundOutputProfile, slot)
                    soundPrefs.edit().putString("sound_ab_active_${soundOutputProfile}", slot).apply()
                    refreshAbButtons()
                    toast("A $slot hangállapot elmentve")
                }
            }
            abSaveRow.addView(saveButton, LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                marginEnd = dp(4)
            })
        }

        val swapButton = Button(this).apply {
            text = "A ↔ B CSERE"
            isAllCaps = false
            textSize = 10f
            setOnClickListener {
                if (!isAbSlotSaved("A") || !isAbSlotSaved("B")) {
                    toast("Előbb mentsd el külön az A és B állapotot")
                } else {
                    val prefixA = "sound_ab_${soundOutputProfile}_A_"
                    val prefixB = "sound_ab_${soundOutputProfile}_B_"
                    val editor = soundPrefs.edit()
                    val all = soundPrefs.all
                    soundProfileKeys.forEach { key ->
                        val valueA = all[prefixA + key]
                        val valueB = all[prefixB + key]
                        when (valueB) {
                            is Boolean -> editor.putBoolean(prefixA + key, valueB)
                            is Int -> editor.putInt(prefixA + key, valueB)
                            is Long -> editor.putLong(prefixA + key, valueB)
                            is Float -> editor.putFloat(prefixA + key, valueB)
                            is String -> editor.putString(prefixA + key, valueB)
                            null -> editor.remove(prefixA + key)
                        }
                        when (valueA) {
                            is Boolean -> editor.putBoolean(prefixB + key, valueA)
                            is Int -> editor.putInt(prefixB + key, valueA)
                            is Long -> editor.putLong(prefixB + key, valueA)
                            is Float -> editor.putFloat(prefixB + key, valueA)
                            is String -> editor.putString(prefixB + key, valueA)
                            null -> editor.remove(prefixB + key)
                        }
                    }
                    editor.apply()
                    val active = soundPrefs.getString("sound_ab_active_${soundOutputProfile}", "A") ?: "A"
                    loadSoundAbSlot(soundPrefs, soundOutputProfile, active)
                    refreshAbButtons()
                    toast("Az A és B hangállapot felcserélve")
                }
            }
        }
        abSaveRow.addView(swapButton, LinearLayout.LayoutParams(0, dp(38), 1f))

        soundCategoryViews.getValue("ab").addView(abProfileRow)
        soundCategoryViews.getValue("ab").addView(abStatusLabel)
        soundCategoryViews.getValue("ab").addView(abSaveRow)
        soundCategoryViews.getValue("ab").addView(label(
            "Az A/B mentések külön-külön tárolódnak az aktuális kimeneti eszközhöz.",
            10f, Color.rgb(145, 150, 160)
        ).apply { setPadding(dp(2), 0, dp(2), dp(6)) })
        soundProfileRefreshers.add { refreshAbButtons(); refreshAbQuickButtons?.invoke() }
        refreshAbButtons()

        activeSoundCategory.addView(sectionTitle("BASSZUS ÉS MÉLYHANGZÁS"))
        fun addSoundSlider(title: String, subtitle: String, prefKey: String, minValue: Int, maxValue: Int, defaultValue: Int, enabledKey: String, enabledSetter: (Boolean) -> Unit, valueSetter: (Float) -> Unit, scale: Float = 100f, suffix: String = "") {
            val row = settingRow(title, subtitle)
            val toggle = Switch(this).apply {
                text = ""
                isChecked = soundPrefs.getBoolean(enabledKey, false)
                isEnabled = !bypassSwitch.isChecked
                setOnCheckedChangeListener { _, checked ->
                    soundPrefs.edit().putBoolean(enabledKey, checked).apply()
                    enabledSetter(checked && !bypassSwitch.isChecked)
                }
            }
            row.addView(toggle)
            soundControlViews.add(toggle)
            soundProfileRefreshers.add {
                toggle.setOnCheckedChangeListener(null)
                toggle.isChecked = soundPrefs.getBoolean(enabledKey, false)
                toggle.setOnCheckedChangeListener { _, checked ->
                    soundPrefs.edit().putBoolean(enabledKey, checked).apply()
                    enabledSetter(checked && !bypassSwitch.isChecked)
                }
                toggle.isEnabled = !bypassSwitch.isChecked
            }
            activeSoundCategory.addView(row)
            val valueLabel = label("", 10f, Color.rgb(255, 207, 112)).apply { gravity = Gravity.END }
            val seek = SeekBar(this).apply {
                max = maxValue - minValue
                progress = (soundPrefs.getInt(prefKey, defaultValue) - minValue).coerceIn(0, max)
                progressTintList = android.content.res.ColorStateList.valueOf(gold)
                thumbTintList = android.content.res.ColorStateList.valueOf(gold)
                isEnabled = !bypassSwitch.isChecked
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        val raw = minValue + progress
                        valueLabel.text = if (suffix == " dB") String.format(java.util.Locale.US, "%.1f dB", raw / scale) else if (suffix == "%") "$raw%" else raw.toString()
                        if (fromUser) {
                            soundPrefs.edit().putInt(prefKey, raw).apply()
                            valueSetter(raw / scale)
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
            val initial = soundPrefs.getInt(prefKey, defaultValue).coerceIn(minValue, maxValue)
            valueLabel.text = if (suffix == " dB") String.format(java.util.Locale.US, "%.1f dB", initial / scale) else if (suffix == "%") "$initial%" else initial.toString()
            activeSoundCategory.addView(valueLabel)
            activeSoundCategory.addView(seek, LinearLayout.LayoutParams(-1, dp(34)))
            soundControlViews.add(seek)
            soundProfileRefreshers.add {
                val restored = soundPrefs.getInt(prefKey, defaultValue).coerceIn(minValue, maxValue)
                seek.progress = restored - minValue
                valueLabel.text = if (suffix == " dB") String.format(java.util.Locale.US, "%.1f dB", restored / scale) else if (suffix == "%") "$restored%" else restored.toString()
                seek.isEnabled = !bypassSwitch.isChecked
            }
        }
        addSoundSlider("BASSZUSMÉLYSÉG", "Alsó frekvenciák finom emelése", "sound_bass_depth_db", 0, 600, 0, "sound_bass_depth_enabled", { soundEngineProcessor.setBassDepthEnabled(it) }, { soundEngineProcessor.setBassDepthDb(it) }, 100f, " dB")
        addSoundSlider("BASSZUSÜTÉS", "Dobok és basszusok ütősebb tartománya", "sound_bass_punch_db", 0, 600, 0, "sound_bass_punch_enabled", { soundEngineProcessor.setBassPunchEnabled(it) }, { soundEngineProcessor.setBassPunchDb(it) }, 100f, " dB")
        addSoundSlider("HARMONIKUS BASSZUS", "Érzékelt mélyhangok kiegészítése", "sound_harmonic_bass", 0, 100, 0, "sound_harmonic_bass_enabled", { soundEngineProcessor.setHarmonicBassEnabled(it) }, { soundEngineProcessor.setHarmonicBassAmount(it) }, 100f, "%")
        activeSoundCategory = soundCategoryViews.getValue("dynamics")
        activeSoundCategory.addView(sectionTitle("DINAMIKA ÉS ÜTÉS"))
        addSoundSlider("DYNAMIC PUNCH", "A dobok és tranziensek támadását emeli — kompresszor és limiter nélkül; legfeljebb +6 dB tranzienskiemelés", "sound_dynamic_punch", 0, 100, 35, "sound_dynamic_punch_enabled", { soundEngineProcessor.setDynamicPunchEnabled(it) }, { soundEngineProcessor.setDynamicPunchAmount(it) }, 100f, "%")
        activeSoundCategory = soundCategoryViews.getValue("tone")
        activeSoundCategory.addView(sectionTitle("MAGASAK ÉS HANGSZÍN"))
        addSoundSlider("MAGAS TISZTASÁG", "Részletek és felső frekvenciák", "sound_clarity_db", 0, 600, 0, "sound_clarity_enabled", { soundEngineProcessor.setClarityEnabled(it) }, { soundEngineProcessor.setClarityDb(it) }, 100f, " dB")
        addSoundSlider("HANGSZÍN", "Melegebb ↔ világosabb karakter", "sound_tone_amount", -100, 100, 0, "sound_tone_enabled", { soundEngineProcessor.setToneEnabled(it) }, { soundEngineProcessor.setToneAmount(it) }, 100f, "%")
        activeSoundCategory = soundCategoryViews.getValue("stereo")
        activeSoundCategory.addView(sectionTitle("SZTEREÓ TÉR ÉS CSATORNÁK"))
        addSoundSlider("SZTEREÓ SZÉLESSÉG", "100% = eredeti sztereó tér", "sound_stereo_width", 0, 150, 100, "sound_stereo_width_enabled", { soundEngineProcessor.setStereoWidthEnabled(it) }, { soundEngineProcessor.setStereoWidth(it) }, 100f, "%")
        addSoundSlider("BAL / JOBB BALANSZ", "Középállás = egyenlő csatornaszint", "sound_balance", -100, 100, 0, "sound_balance_enabled", { soundEngineProcessor.setBalanceEnabled(it) }, { soundEngineProcessor.setBalance(it) }, 100f, "%")
        activeSoundCategory = soundCategoryViews.getValue("level")
        activeSoundCategory.addView(sectionTitle("HANGERŐ ÉS JELFIGYELÉS"))
        clippingStatusLabel = label("JELSZINT: RENDBEN — nincs digitális túlvezérlés", 10f, Color.rgb(120, 205, 145)).apply {
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = GradientDrawable().apply { cornerRadius = dp(9).toFloat(); setColor(Color.rgb(22, 43, 35)); setStroke(dp(1), Color.rgb(48, 105, 75)) }
        }
        activeSoundCategory.addView(clippingStatusLabel)
        soundEngineProcessor.onClipping = { clipping ->
            handler.post {
                soundEngineClippingActive = clipping
                clippingStatusLabel?.apply {
                    if (eqClippingActive) {
                        text = "FIGYELEM: CLIPPING AZ EREDETI EQ KIMENETÉN — a jel már az új DSP előtt túlvezérlődik"
                        setTextColor(Color.rgb(255, 130, 105))
                        background = GradientDrawable().apply { cornerRadius = dp(9).toFloat(); setColor(Color.rgb(55, 28, 27)); setStroke(dp(1), Color.rgb(150, 65, 55)) }
                    } else if (soundEngineClippingActive) {
                        text = "FIGYELEM: DIGITÁLIS CLIPPING ÉSZLELVE — csökkentsd az EQ vagy az előerősítés szintjét"
                        setTextColor(Color.rgb(255, 130, 105))
                        background = GradientDrawable().apply { cornerRadius = dp(9).toFloat(); setColor(Color.rgb(55, 28, 27)); setStroke(dp(1), Color.rgb(150, 65, 55)) }
                    } else {
                        text = "JELSZINT: RENDBEN — nincs digitális túlvezérlés"
                        setTextColor(Color.rgb(120, 205, 145))
                        background = GradientDrawable().apply { cornerRadius = dp(9).toFloat(); setColor(Color.rgb(22, 43, 35)); setStroke(dp(1), Color.rgb(48, 105, 75)) }
                    }
                }
            }
        }
        addSoundSlider("ELŐERŐSÍTÉS", "Kimeneti szint korrekciója", "sound_preamp_db", -1200, 600, 0, "sound_preamp_enabled", { soundEngineProcessor.setPreampEnabled(it) }, { soundEngineProcessor.setPreampDb(it) }, 100f, " dB")
        bypassSwitch.setOnCheckedChangeListener { _, checked ->
            soundPrefs.edit().putBoolean("sound_dsp_bypass", checked).apply()
            soundControlViews.forEach { it.isEnabled = !checked }
            val active = !checked
            soundEngineProcessor.setBassDepthEnabled(active && soundPrefs.getBoolean("sound_bass_depth_enabled", false))
            soundEngineProcessor.setBassPunchEnabled(active && soundPrefs.getBoolean("sound_bass_punch_enabled", false))
            soundEngineProcessor.setHarmonicBassEnabled(active && soundPrefs.getBoolean("sound_harmonic_bass_enabled", false))
            soundEngineProcessor.setClarityEnabled(active && soundPrefs.getBoolean("sound_clarity_enabled", false))
            soundEngineProcessor.setToneEnabled(active && soundPrefs.getBoolean("sound_tone_enabled", false))
            soundEngineProcessor.setStereoWidthEnabled(active && soundPrefs.getBoolean("sound_stereo_width_enabled", false))
            soundEngineProcessor.setBalanceEnabled(active && soundPrefs.getBoolean("sound_balance_enabled", false))
            soundEngineProcessor.setPreampEnabled(active && soundPrefs.getBoolean("sound_preamp_enabled", false))
            soundEngineProcessor.setDynamicPunchEnabled(active && soundPrefs.getBoolean("sound_dynamic_punch_enabled", false))
            refreshSoundProfileStatus()
        }
        soundProfileRefreshers.add { refreshSoundProfileStatus() }

        // Conservative, opt-in starting points. They modify only the AURA Sound Engine,
        // never the original 12-band EQ, and are saved to the active output profile.
        fun applySoundPreset(preset: String) {
            if (preset == "ORIGINAL") {
                soundPrefs.edit().putBoolean("sound_dsp_bypass", true).apply()
                loadAuraSoundEngineSettings(soundPrefs)
                soundProfileRefreshers.forEach { it() }
                saveSoundProfile(soundPrefs, soundOutputProfile)
                refreshSoundProfileStatus()
                toast("Eredeti AURA hang: az új DSP kikapcsolva, az EQ aktív marad")
                return
            }
            val enabled = mapOf(
                "sound_bass_depth_enabled" to (preset == "PUNCH"),
                "sound_bass_punch_enabled" to (preset == "PUNCH"),
                "sound_harmonic_bass_enabled" to false,
                "sound_dynamic_punch_enabled" to false,
                "sound_clarity_enabled" to (preset == "CLEAR"),
                "sound_tone_enabled" to (preset == "CLEAR"),
                "sound_stereo_width_enabled" to (preset == "STAGE"),
                "sound_balance_enabled" to false,
                "sound_preamp_enabled" to false
            )
            val values = mapOf(
                "sound_bass_depth_db" to if (preset == "PUNCH") 80 else 0,
                "sound_bass_punch_db" to if (preset == "PUNCH") 120 else 0,
                "sound_harmonic_bass" to 0,
                "sound_dynamic_punch" to 0,
                "sound_clarity_db" to if (preset == "CLEAR") 100 else 0,
                "sound_tone_amount" to if (preset == "CLEAR") 8 else 0,
                "sound_stereo_width" to if (preset == "STAGE") 108 else 100,
                "sound_balance" to 0,
                "sound_preamp_db" to 0
            )
            val editor = soundPrefs.edit().putBoolean("sound_dsp_bypass", false)
            enabled.forEach { (key, value) -> editor.putBoolean(key, value) }
            values.forEach { (key, value) -> editor.putInt(key, value) }
            editor.apply()
            loadAuraSoundEngineSettings(soundPrefs)
            soundProfileRefreshers.forEach { it() }
            saveSoundProfile(soundPrefs, soundOutputProfile)
            refreshSoundProfileStatus()
            toast(when (preset) {
                "PUNCH" -> "PUNCH: visszafogott mély- és ütéskiemelés"
                "CLEAR" -> "CLEAR: finom részlet- és hangszínkiemelés"
                else -> "STAGE: enyhén szélesebb sztereótér"
            })
        }

        val soundPresetPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(3), dp(2), dp(9))
        }
        soundPresetPanel.addView(label("GYORS HANGKARAKTEREK", 10f, Color.rgb(255, 207, 112)).apply {
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(2), 0, dp(5))
        })
        soundPresetPanel.addView(label(
            "Óvatos kiinduló beállítások. Csak választáskor módosítanak; az eredeti EQ-hoz nem nyúlnak.",
            10f, Color.rgb(155, 163, 177)
        ).apply { setPadding(0, 0, 0, dp(5)) })
        val presetScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "ORIGINAL" to "EREDETI AURA",
            "PUNCH" to "PUNCH",
            "CLEAR" to "CLEAR",
            "STAGE" to "WIDE STAGE"
        ).forEach { (key, title) ->
            presetRow.addView(button(title, Color.rgb(43, 39, 31), Color.rgb(255, 224, 160)) {
                applySoundPreset(key)
            }, LinearLayout.LayoutParams(-2, dp(40)).apply {
                marginEnd = dp(7)
            })
        }
        presetScroll.addView(presetRow)
        soundPresetPanel.addView(presetScroll)
        soundPage.addView(soundPresetPanel, soundPage.indexOfChild(soundCategoryNav))
        activeSoundCategory.addView(label("A clipping figyelése külön jelzésként kerül be. A jelenlegi PCM16 kimenet csúcsértékeit már méri a hangmotor; limiter nem kapcsolódik be automatikusan.", 10f, Color.rgb(155, 163, 177)).apply { setPadding(dp(3), dp(8), dp(3), dp(14)) })
        // Settings hub: every non-audio setting lives in its own category page.
        val settingsMenu = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val playbackSettingsPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val displaySettingsPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val appearanceSettingsPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val vuAppearanceSettingsPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val backupSettingsPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val settingsSubPages = listOf(playbackSettingsPage, displaySettingsPage, appearanceSettingsPage, vuAppearanceSettingsPage, backupSettingsPage)
        fun showSettingsCategory(page: LinearLayout?) {
            settingsMenu.visibility = if (page == null) View.VISIBLE else View.GONE
            settingsSubPages.forEach { it.visibility = if (it === page) View.VISIBLE else View.GONE }
        }
        fun settingsBackButton(target: LinearLayout) {
            target.addView(button("‹  VISSZA A BEÁLLÍTÁSOKHOZ", Color.rgb(35, 41, 53), Color.WHITE) {
                showSettingsCategory(null)
            }, LinearLayout.LayoutParams(-1, dp(40)).apply { bottomMargin = dp(12) })
        }
        settingsPage.addView(settingsMenu)
        settingsPage.addView(playbackSettingsPage)
        settingsPage.addView(displaySettingsPage)
        settingsPage.addView(appearanceSettingsPage)
        settingsPage.addView(vuAppearanceSettingsPage)
        settingsPage.addView(backupSettingsPage)
        settingsMenu.addView(label("BEÁLLÍTÁSOK", 18f, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(dp(3), dp(4), 0, dp(12)) })
        fun settingsCategoryButton(title: String, subtitle: String, page: LinearLayout) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(Color.rgb(24, 29, 39), 14)
                setPadding(dp(14), dp(12), dp(12), dp(12))
                isClickable = true
                isFocusable = true
                setOnClickListener { performAuraHaptic(this); showSettingsCategory(page) }
                applyPremiumPressEffect(this)
            }
            item.addView(label(title, 14f, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD })
            item.addView(label(subtitle, 10f, Color.rgb(155, 163, 177)).apply { setPadding(0, dp(3), 0, 0) })
            settingsMenu.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
        }
        settingsCategoryButton("▶  LEJÁTSZÁS", "Automatikus indítás és folytatás", playbackSettingsPage)
        settingsCategoryButton("▣  KIJELZŐ", "Képernyőforgatás és tájolás", displaySettingsPage)
        settingsCategoryButton("◈  MEGJELENÉS", "Borító és kazetta megjelenítése", appearanceSettingsPage)
        settingsCategoryButton("▥  VU-MÉRŐ", "Stílus, fényerő, animáció és részletes beállítások", vuAppearanceSettingsPage)
        settingsCategoryButton("▣  MENTÉS ÉS VISSZAÁLLÍTÁS", "Az összes AURA-beállítás és lejátszási lista közös mentése", backupSettingsPage)

        settingsBackButton(playbackSettingsPage)
        settingsBackButton(displaySettingsPage)
        settingsBackButton(appearanceSettingsPage)
        settingsBackButton(vuAppearanceSettingsPage)
        settingsBackButton(backupSettingsPage)
        backupSettingsPage.addView(sectionTitle("TELJES AURA BIZTONSÁGI MENTÉS"))
        backupSettingsPage.addView(label("Egyetlen fájlba menti az EQ-t, a Sound Engine-t, a kimeneti profilokat, a VU-beállításokat, a könyvtárnézeteket és a lejátszási listákat. A zenefájlokat nem másolja.", 11f, Color.rgb(155, 163, 177)).apply { setPadding(dp(3), dp(4), dp(3), dp(12)) })
        backupSettingsPage.addView(button("💾  TELJES MENTÉS KÉSZÍTÉSE", Color.rgb(91, 65, 27), Color.WHITE) { openBackupExport() }, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
        backupSettingsPage.addView(button("↻  MENTÉS VISSZAÁLLÍTÁSA", Color.rgb(31, 69, 76), Color.WHITE) { openBackupImport() }, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })

        playbackSettingsPage.addView(sectionTitle("AUTOMATIKUS LEJÁTSZÁS"))
        val playbackPrefs = getSharedPreferences("aurix_settings", MODE_PRIVATE)
        val resumeRow = settingRow("INDULÁSKOR FOLYTATÁS", "Az előző lejátszás folytatása az alkalmazás megnyitásakor")
        val resumeSwitch = Switch(this).apply {
            text = ""
            isChecked = playbackPrefs.getBoolean("resume_on_app_start", true)
            setOnCheckedChangeListener { _, checked ->
                playbackPrefs.edit().putBoolean("resume_on_app_start", checked).apply()
            }
        }
        resumeRow.addView(resumeSwitch)
        playbackSettingsPage.addView(resumeRow)

        val bluetoothAutoPlayRow = settingRow("BLUETOOTH AUTO-INDÍTÁS", "Lejátszás indítása Bluetooth-füles, hangszóró vagy autórádió csatlakozásakor")
        val bluetoothAutoPlaySwitch = Switch(this).apply {
            text = ""
            isChecked = playbackPrefs.getBoolean("auto_play_on_bluetooth_connect", false)
            setOnCheckedChangeListener { _, checked ->
                playbackPrefs.edit().putBoolean("auto_play_on_bluetooth_connect", checked).apply()
            }
        }
        bluetoothAutoPlayRow.addView(bluetoothAutoPlaySwitch)
        playbackSettingsPage.addView(bluetoothAutoPlayRow)

        playbackSettingsPage.addView(sectionTitle("KEZELÉS ÉS VISSZAJELZÉS"))
        val hapticRow = settingRow("HAPTIKUS VISSZAJELZÉS", "Finom rezgés az AURA gombjainak megnyomásakor")
        val hapticSwitch = Switch(this).apply {
            text = ""
            isChecked = playbackPrefs.getBoolean("haptic_feedback", true)
            setOnCheckedChangeListener { _, checked ->
                performAuraHaptic(this)
                playbackPrefs.edit().putBoolean("haptic_feedback", checked).apply()
            }
        }
        hapticRow.addView(hapticSwitch)
        playbackSettingsPage.addView(hapticRow)

        displaySettingsPage.addView(sectionTitle("KIJELZŐ ÉS TÁJOLÁS"))
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
        displaySettingsPage.addView(rotationRow)
        appearanceSettingsPage.addView(sectionTitle("TÉMÁK"))
        appearanceSettingsPage.addView(label("Válassz az AURA két külön megjelenése közül. A választás nem módosítja a hangzást vagy a VU-beállításokat.", 11f, Color.rgb(155, 163, 177)).apply { setPadding(dp(3), 0, dp(3), dp(10)) })
        appearanceSettingsPage.addView(button("◈  AURA ORIGINAL  •  EREDETI", Color.rgb(35, 41, 53), Color.WHITE) {
            themePrefs.edit().putString("aura_theme", "original").apply()
            toast("AURA Original kiválasztva. A teljes témaváltás az alkalmazás újraindításakor lép életbe.")
        }, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(7) })
        appearanceSettingsPage.addView(button("✦  AURA GOLD STUDIO", Color.rgb(91, 65, 27), Color.rgb(255, 225, 174)) {
            themePrefs.edit().putString("aura_theme", "gold_studio").apply()
            toast("AURA Gold Studio kiválasztva. A teljes témaváltás az alkalmazás újraindításakor lép életbe.")
        }, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(12) })
        appearanceSettingsPage.addView(sectionTitle("LEJÁTSZÓ MEGJELENÉSE"))
        appearanceSettingsPage.addView(button("◈  BORÍTÓ / KAZETTA", Color.rgb(35, 41, 53), Color.WHITE) { showCover = !showCover; getSharedPreferences("aurix_settings", MODE_PRIVATE).edit().putBoolean("show_cover", showCover).apply(); applyArtworkMode() }, LinearLayout.LayoutParams(-1, dp(38)))
        vuAppearanceSettingsPage.addView(sectionTitle("VU-MÉRŐ MEGJELENÉSE"))
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
        vuAppearanceSettingsPage.addView(vuStyleRow)
        vuAppearanceSettingsPage.addView(label("Mindkét mód: 5 sárga + 3 piros LED csatornánként.", 11f, Color.rgb(155, 163, 177)).apply { setPadding(dp(4), dp(7), 0, dp(4)) })
        vuAppearanceSettingsPage.addView(label("VU-MÉRŐ FÉNYEREJE", 11f, gold).apply { setPadding(dp(4), dp(12), 0, 0) })
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
        vuAppearanceSettingsPage.addView(vuBrightness)
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
        vuAppearanceSettingsPage.addView(animationRow)

        val vuSettingsMenuButton = button("▥  VU-MÉRŐ FINOMHANGOLÁSA", Color.rgb(35, 41, 53), Color.WHITE) {}
        vuAppearanceSettingsPage.addView(vuSettingsMenuButton, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(10); bottomMargin = dp(12) })

        vuSettingsPage.addView(label("BEÁLLÍTÁSOK  /  VU-MÉRŐ", 10f, Color.rgb(155, 163, 177)).apply { setPadding(dp(2), dp(4), 0, dp(4)) })
        vuSettingsPage.addView(label("VU / HANGREAKCIÓ FINOMHANGOLÁSA", 18f, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(dp(2), dp(2), 0, dp(8)) })
        val vuSettingsBackButton = button("‹  VISSZA A BEÁLLÍTÁSOKHOZ", Color.rgb(35, 41, 53), Color.WHITE) {}
        vuSettingsPage.addView(vuSettingsBackButton, LinearLayout.LayoutParams(-1, dp(40)).apply { bottomMargin = dp(10) })

        vuSettingsPage.addView(label("ÉLŐ VU-KIJELZÉS", 11f, gold).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(dp(2), dp(8), 0, dp(5)) })
        vuSettingsPage.addView(label("Zene lejátszása közben itt azonnal látod, hogyan reagál a mérő a módosításokra.", 10f, Color.rgb(155, 163, 177)).apply { setPadding(dp(2), 0, dp(2), dp(8)) })
        val previewCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(28, 27, 24), Color.rgb(9, 12, 17))).apply {
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.rgb(125, 91, 39))
            }
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val previewHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        previewHeader.addView(label("L  /  BAL", 10f, gold), LinearLayout.LayoutParams(0, -2, 1f))
        previewHeader.addView(label("R  /  JOBB", 10f, gold).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f))
        previewCard.addView(previewHeader)
        settingsPreviewVu = VuView().apply {
            setStyle(vuPrefs.getString("vu_style", "car") ?: "car")
            setBrightness(vuPrefs.getInt("vu_brightness", 80))
            setAnimationEnabled(vuPrefs.getBoolean("vu_animation", true))
            setActive(player?.isPlaying == true)
        }
        previewCard.addView(settingsPreviewVu, LinearLayout.LayoutParams(-1, dp(74)).apply { topMargin = dp(4) })
        vuSettingsPage.addView(previewCard, LinearLayout.LayoutParams(-1, dp(112)).apply { bottomMargin = dp(12) })
        vuSettingsPage.addView(label("KÜLÖN HANGKIMENETI PROFILOK", 11f, gold).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(dp(2), dp(8), 0, dp(5)) })
        vuSettingsPage.addView(label("A csatlakoztatott kimenetet automatikusan felismeri. A két profil külön mentődik.", 10f, Color.rgb(155, 163, 177)).apply { setPadding(dp(2), 0, dp(2), dp(8)) })

        val profileStatus = label("", 10f, Color.rgb(155, 163, 177)).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, dp(7))
        }
        vuSettingsPage.addView(profileStatus)
        val profileRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(13, 16, 22), 14)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val speakerProfileButton = button("▣  HANGSZÓRÓ", Color.TRANSPARENT, Color.WHITE) {}
        val bluetoothProfileButton = button("♫  BLUETOOTH", Color.TRANSPARENT, Color.WHITE) {}
        profileRow.addView(speakerProfileButton, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(3) })
        profileRow.addView(bluetoothProfileButton, LinearLayout.LayoutParams(0, dp(40), 1f))
        vuSettingsPage.addView(profileRow)

        fun saveVuProfileNow(profile: String) {
            val prefix = if (profile == "bluetooth") "vu_bluetooth_" else "vu_speaker_"
            val isActive = vuProfile == profile
            val thresholdValue = if (isActive) (vuThreshold * 1000).toInt() else vuPrefs.getInt(prefix + "threshold_milli", 35)
            val gainValue = if (isActive) (vuGain * 100).toInt() else vuPrefs.getInt(prefix + "gain_percent", 120)
            val delayValue = if (isActive) vuDelayMs.toInt() else vuPrefs.getInt(prefix + "delay_ms", 400)
            val monoValue = if (isActive) vuMono else vuPrefs.getBoolean(prefix + "mono", false)
            val saved = vuPrefs.edit()
                .putInt(prefix + "threshold_milli", thresholdValue.coerceIn(10, 250))
                .putInt(prefix + "gain_percent", gainValue.coerceIn(50, 200))
                .putInt(prefix + "delay_ms", delayValue.coerceIn(400, 1400))
                .putBoolean(prefix + "mono", monoValue)
                .commit()
            toast(if (saved) (if (profile == "bluetooth") "Bluetooth profil elmentve." else "Hangszóró profil elmentve.") else "A mentés nem sikerült.")
        }
        val saveProfilesRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        saveProfilesRow.addView(button("💾  HANGSZÓRÓ MENTÉSE", Color.rgb(91, 65, 27), Color.WHITE) {
            saveVuProfileNow("speaker")
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(4) })
        saveProfilesRow.addView(button("💾  BT MENTÉSE", Color.rgb(31, 69, 76), Color.WHITE) {
            saveVuProfileNow("bluetooth")
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(4) })
        vuSettingsPage.addView(saveProfilesRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        fun vuSlider(
            title: String,
            subtitle: String,
            maxValue: Int,
            initial: Int,
            format: (Int) -> String,
            changed: (Int) -> Unit
        ): SeekBar {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.rgb(29, 30, 34), Color.rgb(14, 17, 23))).apply {
                    cornerRadius = dp(13).toFloat()
                    setStroke(dp(1), Color.rgb(91, 69, 39))
                }
                setPadding(dp(12), dp(8), dp(12), dp(5))
            }
            val headerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            headerRow.addView(label(title, 11f, Color.rgb(238, 202, 137)).apply { typeface = Typeface.DEFAULT_BOLD },
                LinearLayout.LayoutParams(0, -2, 1f))
            val valueLabel = label(format(initial), 12f, gold).apply { typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.END }
            headerRow.addView(valueLabel)
            card.addView(headerRow)
            card.addView(label(subtitle, 9f, Color.rgb(145, 153, 166)))
            val seek = SeekBar(this).apply {
                max = maxValue
                progress = initial.coerceIn(0, maxValue)
                progressTintList = android.content.res.ColorStateList.valueOf(gold)
                thumbTintList = android.content.res.ColorStateList.valueOf(gold)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                        valueLabel.text = format(value)
                        if (fromUser) changed(value)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
            card.addView(seek)
            vuSettingsPage.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            return seek
        }

        val thresholdSeek = vuSlider(
            "ÉRZÉKENYSÉG  •  THRESHOLD",
            "Alacsonyabb érték = a halkabb hangokra is reagál.",
            240, (vuThreshold * 1000).toInt().minus(10).coerceIn(0, 240),
            { "%.3f".format(java.util.Locale.US, (it + 10) / 1000f) }
        ) { value ->
            vuThreshold = (value + 10) / 1000f
            saveVuProfileInt("threshold_milli", value + 10)
        }
        val gainSeek = vuSlider(
            "VU ERŐSÍTÉS  •  GAIN",
            "A kijelző kitérésének mértéke. Nem változtatja a hangerőt.",
            150, ((vuGain * 100).toInt() - 50).coerceIn(0, 150),
            { "%.2fx".format(java.util.Locale.US, 0.50f + it / 100f) }
        ) { value ->
            vuGain = 0.50f + value / 100f
            saveVuProfileInt("gain_percent", value + 50)
        }
        val delaySeek = vuSlider(
            "KÉSLELTETÉS  •  DELAY",
            "Állítsd a kijelzést a hallható zenéhez. Bluetoothnál gyakran nagyobb érték kell.",
            1000, (vuDelayMs.toInt() - 400).coerceIn(0, 1000),
            { "${it + 400} ms" }
        ) { value ->
            vuDelayMs = (value + 400).toLong()
            saveVuProfileInt("delay_ms", value + 400)
        }

        val monoRow = settingRow("MONO KIJELZÉS", "A két csatorna átlaga mindkét oldalon")
        val monoSwitch = Switch(this).apply {
            text = ""
            isChecked = vuMono
            setOnCheckedChangeListener { _, checked ->
                vuMono = checked
                saveVuProfileBoolean("mono", checked)
            }
        }
        monoRow.addView(monoSwitch)
        vuSettingsPage.addView(monoRow)
        vuSettingsPage.addView(label(
            "Jelforrás: az EQ és a hangkiemelések utáni digitális hangjel. A beállítások csak a VU-kijelzést módosítják.",
            9f, Color.rgb(145, 153, 166)
        ).apply { setPadding(dp(4), dp(5), dp(4), dp(8)) })

        fun refreshVuProfileControls() {
            val isBluetooth = vuProfile == "bluetooth"
            profileStatus.text = if (isBluetooth) "AKTÍV PROFIL: BLUETOOTH" else "AKTÍV PROFIL: TELEFONHANGSZÓRÓ"
            speakerProfileButton.background = rounded(if (!isBluetooth) Color.rgb(91, 65, 27) else Color.TRANSPARENT, 10)
            bluetoothProfileButton.background = rounded(if (isBluetooth) Color.rgb(31, 69, 76) else Color.TRANSPARENT, 10)
            speakerProfileButton.setTextColor(if (!isBluetooth) Color.rgb(255, 207, 112) else Color.rgb(150, 158, 172))
            bluetoothProfileButton.setTextColor(if (isBluetooth) Color.rgb(119, 224, 218) else Color.rgb(150, 158, 172))
            thresholdSeek.progress = (vuThreshold * 1000).toInt().minus(10).coerceIn(0, 240)
            gainSeek.progress = ((vuGain * 100).toInt() - 50).coerceIn(0, 150)
            delaySeek.progress = (vuDelayMs.toInt() - 400).coerceIn(0, 1000)
            monoSwitch.isChecked = vuMono
        }
        vuProfileControls = { refreshVuProfileControls() }
        speakerProfileButton.setOnClickListener {
            loadVuProfile("speaker")
            refreshVuProfileControls()
        }
        bluetoothProfileButton.setOnClickListener {
            loadVuProfile("bluetooth")
            refreshVuProfileControls()
        }
        vuSettingsPage.addView(button("↺  AKTÍV PROFIL ALAPÉRTÉKEI", Color.rgb(35, 41, 53), Color.WHITE) {
            val profile = vuProfile
            val prefix = if (profile == "bluetooth") "vu_bluetooth_" else "vu_speaker_"
            vuThreshold = 0.035f
            vuGain = 1.20f
            vuDelayMs = 400L
            vuMono = false
            vuPrefs.edit()
                .putInt(prefix + "threshold_milli", 35)
                .putInt(prefix + "gain_percent", 120)
                .putInt(prefix + "delay_ms", 400)
                .putBoolean(prefix + "mono", false)
                .apply()
            refreshVuProfileControls()
            toast("Az aktív VU-profil visszaállítva.")
        }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(4); bottomMargin = dp(12) })

        val savedVuStyle = vuPrefs.getString("vu_style", "car") ?: "car"
        setVuStyle(savedVuStyle)
        refreshVuStyleButtons(savedVuStyle)
        setVuBrightness(vuPrefs.getInt("vu_brightness", 80))
        setVuAnimation(vuPrefs.getBoolean("vu_animation", true))
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(5), dp(5), dp(5), dp(5)); background = rounded(if (goldStudioTheme) Color.rgb(19, 17, 13) else Color.rgb(16, 18, 22), 16); elevation = dp(7).toFloat() }
        val navButtons = mutableListOf<Button>()
        fun showPage(index: Int, resetLibrary: Boolean = true) {
            activePageIndex = index
            pageScrolls.forEachIndexed { i, v -> v.visibility = if (i == index) View.VISIBLE else View.GONE }
            if (index == 1 && resetLibrary) {
                showLibraryHome()
                pageScrolls[1].post { pageScrolls[1].scrollTo(0, 0) }
            }
            navButtons.forEachIndexed { i, b -> val selected = i == (if (index == 4 || index == 5) 3 else index); b.setTextColor(if (selected) Color.rgb(245, 211, 151) else Color.rgb(160, 160, 158)); b.background = rounded(if (selected) Color.rgb(57, 46, 30) else Color.rgb(26, 28, 31), 12) }
        }
        settingsMenu.addView(button("♫  HANGBEÁLLÍTÁSOK  ›", Color.rgb(75, 57, 29), Color.rgb(255, 207, 112)) { showPage(5) },
            LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(3); bottomMargin = dp(12) })
        soundPage.addView(button("‹  VISSZA A BEÁLLÍTÁSOKHOZ", Color.rgb(35, 41, 53), Color.WHITE) { showPage(3) }, LinearLayout.LayoutParams(-1, dp(40)).apply { bottomMargin = dp(12) })

        var vuSettingsReturnToCategory = false
        vuSettingsMenuButton.setOnClickListener {
            vuSettingsReturnToCategory = true
            showPage(4)
        }
        vuSettingsBackButton.setOnClickListener {
            if (vuSettingsReturnToCategory) {
                showPage(3)
                showSettingsCategory(vuAppearanceSettingsPage)
            } else {
                showPage(0, false)
            }
        }
        openPlayerPage = { showPage(0, false) }
        openVuSettingsPage = {
            vuSettingsReturnToCategory = false
            showPage(4, false)
        }
        openSoundEnginePage = { showPage(5, false) }
        openLibraryPage = {
            showPage(1, false)
            currentTrackRow = null
            if (activeLibraryRenderer == null) showLibraryHome() else activeLibraryRenderer?.invoke()
            scrollToCurrentLibraryRow()
        }
        listOf("LEJÁTSZÓ", "DALOK", "EQ", "BEÁLLÍTÁS").forEachIndexed { i, title -> val b = button(title, Color.rgb(29, 29, 30), Color.rgb(190, 184, 171)) { showPage(i) }; b.textSize = 10f; b.letterSpacing = 0.04f; navButtons.add(b); nav.addView(b, LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(2), 0, dp(2), 0) }) }
        pageScrolls.forEach { root.addView(it, LinearLayout.LayoutParams(-1, 0, 1f)) }
        root.addView(nav, LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(8) })
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

    private fun performAuraHaptic(view: View) {
        val enabled = getSharedPreferences("aurix_settings", MODE_PRIVATE).getBoolean("haptic_feedback", true)
        if (enabled) view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun applyPremiumPressEffect(view: View) {
        val restingElevation = view.elevation
        view.setOnTouchListener { touched, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    touched.animate().cancel()
                    touched.animate().scaleX(0.965f).scaleY(0.965f)
                        .translationZ(dp(3).toFloat()).setDuration(85L).start()
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    touched.animate().cancel()
                    touched.animate().scaleX(1f).scaleY(1f).translationZ(0f)
                        .setDuration(220L)
                        .setInterpolator(android.view.animation.OvershootInterpolator(1.25f)).start()
                }
            }
            false
        }
    }

    private fun neonRoundButton(symbol: String, size: Int, primary: Boolean, click: () -> Unit) = Button(this).apply {
        text = symbol
        textSize = if (primary) 23f else 19f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        setTextColor(if (primary) Color.rgb(255, 220, 165) else Color.rgb(196, 184, 158))
        includeFontPadding = false
        isAllCaps = false
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding(0, 0, 0, 0)
        backgroundTintList = null
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.rgb(13, 18, 26))
            setStroke(dp(if (primary) 2 else 1), if (primary) Color.rgb(224, 174, 91) else Color.rgb(104, 99, 88))
        }
        elevation = dp(7).toFloat()
        stateListAnimator = null
        applyPremiumPressEffect(this)
        setOnClickListener { performAuraHaptic(this); click() }
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
        applyPremiumPressEffect(this)
        setOnClickListener { performAuraHaptic(this); click() }
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
                MediaStore.Audio.Media.DATA,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.Audio.Media.ALBUM_ID
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
                    val displayNameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val albumIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val title = cursor.getString(titleCol) ?: "Ismeretlen cím"
                        val artist = cursor.getString(artistCol) ?: "Ismeretlen előadó"
                        val album = if (albumCol >= 0) cursor.getString(albumCol) ?: "Ismeretlen album" else "Ismeretlen album"
                        val fullPath = if (pathCol >= 0) cursor.getString(pathCol) else null
                        val fileName = (if (displayNameCol >= 0) cursor.getString(displayNameCol) else null)
                            ?.takeIf { it.isNotBlank() } ?: fullPath?.let { java.io.File(it).name } ?: title
                        val folder = fullPath?.let { java.io.File(it).parentFile?.absolutePath }
                            ?.takeIf { it.isNotBlank() } ?: "Ismeretlen mappa"
                        val albumId = if (albumIdCol >= 0) cursor.getLong(albumIdCol) else -1L
                        val song = LibrarySong(android.content.ContentUris.withAppendedId(collection, id),
                            title, artist, album, folder, cursor.getLong(dateCol), fileName, albumId)
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

    private fun listPreferenceKey(scope: String, kind: String): String {
        val safe = scope.hashCode().toUInt().toString(16)
        return "list_${kind}_${safe}"
    }

    private fun listSortMode(scope: String, allowManual: Boolean = false): String {
        val fallback = if (allowManual) "manual" else "title_asc"
        return getSharedPreferences("aurix_library", MODE_PRIVATE).getString(listPreferenceKey(scope, "sort"), fallback) ?: fallback
    }

    private fun listViewMode(scope: String): String =
        getSharedPreferences("aurix_library", MODE_PRIVATE).getString(listPreferenceKey(scope, "view"), "detailed") ?: "detailed"

    /** Natural sorting: 2 comes before 10, while text is compared using Hungarian collation. */
    private fun compareNaturalText(first: String, second: String): Int {
        var a = 0
        var b = 0
        val collator = java.text.Collator.getInstance(java.util.Locale.forLanguageTag("hu-HU")).apply {
            strength = java.text.Collator.SECONDARY
        }
        while (a < first.length && b < second.length) {
            val aIsDigit = first[a] in '0'..'9'
            val bIsDigit = second[b] in '0'..'9'
            if (aIsDigit && bIsDigit) {
                val aStart = a
                val bStart = b
                while (a < first.length && first[a] in '0'..'9') a++
                while (b < second.length && second[b] in '0'..'9') b++
                val rawA = first.substring(aStart, a)
                val rawB = second.substring(bStart, b)
                val numberA = rawA.trimStart('0').ifEmpty { "0" }
                val numberB = rawB.trimStart('0').ifEmpty { "0" }
                if (numberA.length != numberB.length) return numberA.length.compareTo(numberB.length)
                val numericCompare = numberA.compareTo(numberB)
                if (numericCompare != 0) return numericCompare
                if (rawA.length != rawB.length) return rawA.length.compareTo(rawB.length)
            } else {
                val aStart = a
                val bStart = b
                while (a < first.length && first[a] !in '0'..'9') a++
                while (b < second.length && second[b] !in '0'..'9') b++
                val textCompare = collator.compare(first.substring(aStart, a), second.substring(bStart, b))
                if (textCompare != 0) return textCompare
            }
        }
        return (first.length - a).compareTo(second.length - b)
    }

    private fun sortLibrarySongs(songs: List<LibrarySong>, scope: String, allowManual: Boolean = false): List<LibrarySong> {
        val mode = listSortMode(scope, allowManual)
        val titleAscending = Comparator<LibrarySong> { left, right -> compareNaturalText(left.title, right.title) }
        val artistAscending = Comparator<LibrarySong> { left, right -> compareNaturalText(left.artist, right.artist) }
        val albumAscending = Comparator<LibrarySong> { left, right -> compareNaturalText(left.album, right.album) }
        val fileAscending = Comparator<LibrarySong> { left, right ->
            compareNaturalText(left.fileName, right.fileName)
        }
        return when (mode) {
            "title_desc" -> songs.sortedWith(titleAscending.reversed())
            "artist_asc" -> songs.sortedWith(artistAscending)
            "artist_desc" -> songs.sortedWith(artistAscending.reversed())
            "album_asc" -> songs.sortedWith(albumAscending)
            "album_desc" -> songs.sortedWith(albumAscending.reversed())
            "added_new" -> songs.sortedByDescending { it.dateAdded }
            "added_old" -> songs.sortedBy { it.dateAdded }
            "file_asc" -> songs.sortedWith(fileAscending)
            "file_desc" -> songs.sortedWith(fileAscending.reversed())
            "manual" -> if (allowManual) songs else songs.sortedWith(titleAscending)
            else -> songs.sortedWith(titleAscending)
        }
    }

    private fun addListOptionsHeader(title: String, scope: String, refresh: () -> Unit, allowManual: Boolean = false) {
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(7), dp(5), dp(3), dp(8)) }
        header.addView(label(title, 11f, Color.rgb(235, 205, 145)).apply { typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, -2, 1f))
        val more = Button(this).apply {
            text = "⋮"; textSize = 20f; setTextColor(Color.WHITE); isAllCaps = false
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(48, 52, 72))
            minHeight = dp(34); minWidth = dp(42); setPadding(0, 0, 0, 0)
            setOnClickListener {
            val popup = android.widget.PopupMenu(this@MainActivity, this)
            val sort = popup.menu.addSubMenu("Rendezés")
            val choices = mutableListOf(
                "Cím • A–Z" to "title_asc", "Cím • Z–A" to "title_desc",
                "Előadó • A–Z" to "artist_asc", "Előadó • Z–A" to "artist_desc",
                "Album • A–Z" to "album_asc", "Album • Z–A" to "album_desc",
                "Hozzáadás • legújabb" to "added_new", "Hozzáadás • legrégebbi" to "added_old",
                "Fájlnév • A–Z" to "file_asc", "Fájlnév • Z–A" to "file_desc"
            )
            if (allowManual) choices.add("Kézi sorrend" to "manual")
            choices.forEachIndexed { index, pair ->
                val item = sort.add(0, 100 + index, index, pair.first)
                item.isCheckable = true
                item.isChecked = listSortMode(scope, allowManual) == pair.second
            }
            val view = popup.menu.addSubMenu("Megjelenítés")
            listOf("Kompakt lista" to "compact", "Részletes lista" to "detailed", "Borítórács" to "grid").forEachIndexed { index, pair ->
                val item = view.add(0, 200 + index, index, pair.first)
                item.isCheckable = true
                item.isChecked = listViewMode(scope) == pair.second
            }
            popup.menu.add("Frissítés")
            popup.setOnMenuItemClickListener { item ->
                val prefs = getSharedPreferences("aurix_library", MODE_PRIVATE)
                when (item.itemId) {
                    in 100..199 -> {
                        val selected = choices.getOrNull(item.itemId - 100)?.second ?: return@setOnMenuItemClickListener false
                        prefs.edit().putString(listPreferenceKey(scope, "sort"), selected).apply()
                        refresh()
                        true
                    }
                    in 200..299 -> {
                        val selected = listOf("compact", "detailed", "grid").getOrNull(item.itemId - 200) ?: return@setOnMenuItemClickListener false
                        prefs.edit().putString(listPreferenceKey(scope, "view"), selected).apply()
                        refresh()
                        true
                    }
                    else -> { refresh(); true }
                }
            }
            popup.show()
            }
        }
        header.addView(more, LinearLayout.LayoutParams(dp(42), dp(38)))
        libraryContainer.addView(header)
    }

    private fun decodeLibraryArtwork(bytes: ByteArray, target: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= target &&
                bounds.outHeight / (sample * 2) >= target) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                })
        } catch (_: Exception) { null }
    }

    private fun libraryArtworkView(song: LibrarySong, sizeDp: Int): ImageView {
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(Color.rgb(31, 35, 44), 9)
            clipToOutline = true
            setImageResource(android.R.drawable.ic_menu_gallery)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.rgb(210, 170, 91))
            contentDescription = "Albumborító: ${song.album}"
            tag = song.uri.toString()
        }
        if (song.albumId > 0L) {
            artworkCache.get(song.albumId)?.let {
                image.imageTintList = null
                image.setImageBitmap(it)
                return image
            }
        }
        artworkExecutor.execute {
            val target = (dp(sizeDp) * 2).coerceAtLeast(96)
            var bitmap: Bitmap? = null

            // Elsőként az Android MediaStore album-borítóját próbáljuk megnyitni.
            if (song.albumId > 0L) {
                try {
                    val artUri = Uri.parse("content://media/external/audio/albumart/${song.albumId}")
                    val bytes = contentResolver.openInputStream(artUri)?.use { it.readBytes() }
                    if (bytes != null) bitmap = decodeLibraryArtwork(bytes, target)
                } catch (_: Exception) { }
            }

            // Sok telefonon a MediaStore album-borító URI-ja üres. Ilyenkor
            // közvetlenül a zenefájl beágyazott képét olvassuk ki.
            if (bitmap == null) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(this, song.uri)
                    val embedded = retriever.embeddedPicture
                    if (embedded != null) bitmap = decodeLibraryArtwork(embedded, target)
                } catch (_: Exception) {
                } finally {
                    try { retriever.release() } catch (_: Exception) { }
                }
            }

            if (bitmap != null) {
                if (song.albumId > 0L) artworkCache.put(song.albumId, bitmap)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && image.tag == song.uri.toString()) {
                        // A helykitöltő ikon arany árnyalata ne színezze el a valódi borítót.
                        image.imageTintList = null
                        image.setImageBitmap(bitmap)
                    }
                }
            }
        }
        return image
    }

    private fun renderSongRows(songs: List<LibrarySong>, scope: String = "library", allowManual: Boolean = false) {
        val ordered = sortLibrarySongs(songs, scope, allowManual)
        val viewMode = listViewMode(scope)
        val goldStudioTheme = getSharedPreferences("aurix_settings", MODE_PRIVATE).getString("aura_theme", "gold_studio") == "gold_studio"
        val tileBackground = if (goldStudioTheme) GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(39, 35, 29), Color.rgb(19, 21, 25))).apply {
            cornerRadius = dp(12).toFloat(); setStroke(dp(1), Color.rgb(112, 91, 61))
        } else rounded(Color.rgb(27, 32, 48), 12)
        fun playFrom(index: Int, song: LibrarySong) {
            queue.clear()
            queue.addAll(ordered.map { it.uri })
            currentIndex = index
            startTrack(song.uri)
            updateNextTrack()
        }
        if (viewMode == "grid") {
            var index = 0
            while (index < ordered.size) {
                val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
                for (column in 0..1) {
                    if (index >= ordered.size) {
                        line.addView(View(this), LinearLayout.LayoutParams(0, dp(176), 1f))
                    } else {
                        val song = ordered[index]
                        val itemIndex = index
                        val tile = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                            background = tileBackground
                            setPadding(dp(7), dp(10), dp(7), dp(10))
                            addView(libraryArtworkView(song, 104), LinearLayout.LayoutParams(-1, dp(104)).apply { bottomMargin = dp(7) })
                            addView(label(song.title, 11f, Color.WHITE).apply { gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; typeface = Typeface.DEFAULT_BOLD })
                            addView(label(song.artist, 9f, if (goldStudioTheme) Color.rgb(190, 174, 145) else Color.rgb(190, 195, 220)).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                            setOnClickListener { playFrom(itemIndex, song) }
                        }
                        markLibrarySongRow(tile, song.uri)
                        line.addView(tile, LinearLayout.LayoutParams(0, dp(176), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
                        index++
                    }
                }
                libraryContainer.addView(line)
            }
        } else {
            ordered.forEachIndexed { index, song ->
                val row = if (viewMode == "compact") {
                    LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(libraryArtworkView(song, 34), LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(9) })
                        addView(label("${song.title} — ${song.artist}", 12f, Color.WHITE).apply {
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                        setPadding(dp(9), dp(5), dp(8), dp(5))
                        setOnClickListener { playFrom(index, song) }
                    }
                } else {
                    LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        background = tileBackground
                        setPadding(dp(8), dp(8), dp(8), dp(8))
                        addView(libraryArtworkView(song, 48), LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(10) })
                        val details = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                        details.addView(label(song.title, 12f, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                        details.addView(label(song.artist, 10f, if (goldStudioTheme) Color.rgb(190, 174, 145) else Color.rgb(190, 195, 220)).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                        details.addView(label(song.album, 9f, if (goldStudioTheme) Color.rgb(145, 139, 126) else Color.rgb(145, 153, 175)).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                        addView(details, LinearLayout.LayoutParams(0, -2, 1f))
                        setOnClickListener { playFrom(index, song) }
                    }
                }
                markLibrarySongRow(row, song.uri)
                libraryContainer.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
            }
        }
        if (ordered.isEmpty()) libraryContainer.addView(label("Nincs megjeleníthető dal.", 12f, Color.LTGRAY))
    }


    private fun showRecentAdded() {
        activeLibraryRenderer = { showRecentAdded() }
        libraryMode = "recent_added"; libraryCategoryRow.visibility = View.GONE; libraryContainer.visibility = View.VISIBLE; libraryContainer.removeAllViews(); addLibraryBackButton()
        addListOptionsHeader("LEGUTÓBB HOZZÁADOTT • 100 DAL", "recent_added", { showRecentAdded() })
        renderSongRows(librarySongs.take(100), "recent_added")
    }

    private fun showRecentlyPlayed() {
        activeLibraryRenderer = { showRecentlyPlayed() }
        libraryMode = "recent_played"; libraryCategoryRow.visibility = View.GONE; libraryContainer.visibility = View.VISIBLE; libraryContainer.removeAllViews(); addLibraryBackButton()
        addListOptionsHeader("NEMRÉG JÁTSZOTT", "recent_played", { showRecentlyPlayed() })
        renderSongRows(recentlyPlayed.mapNotNull { librarySongByUri[it] }.distinctBy { it.uri.toString() }.take(100), "recent_played")
    }

    private fun showPlaylists() {
        activeLibraryRenderer = { showPlaylists() }
        libraryMode = "playlists"
        libraryCategoryRow.visibility = View.GONE
        libraryContainer.visibility = View.VISIBLE
        libraryContainer.removeAllViews()
        addLibraryBackButton()
        libraryContainer.addView(label("LEJÁTSZÁSI LISTÁK", 11f, Color.WHITE))

        val prefs = getSharedPreferences("aurix_library", MODE_PRIVATE)
        fun playlistNames(): MutableSet<String> =
            (prefs.getStringSet("playlist_names", emptySet()) ?: emptySet()).toMutableSet()
        fun playlistUris(name: String): MutableList<String> {
            val raw = prefs.getString("playlist_" + name, "[]") ?: "[]"
            return try {
                val arr = org.json.JSONArray(raw)
                MutableList(arr.length()) { index -> arr.optString(index) }.filter { it.isNotBlank() }.toMutableList()
            } catch (_: Exception) { mutableListOf() }
        }
        fun savePlaylist(name: String, uris: List<String>) {
            val arr = org.json.JSONArray()
            uris.forEach { arr.put(it) }
            prefs.edit().putString("playlist_" + name, arr.toString()).apply()
        }

        lateinit var showPlaylistDetail: (String) -> Unit
        showPlaylistDetail = { playlistName ->
            activeLibraryRenderer = { showPlaylistDetail(playlistName) }
            libraryContainer.removeAllViews()
            libraryContainer.addView(label("‹  VISSZA A LISTÁKHOZ", 13f, Color.rgb(235, 205, 145)).apply {
                setPadding(dp(8), dp(12), dp(8), dp(12))
                setOnClickListener { showPlaylists() }
            })
            val uris = playlistUris(playlistName)
            val playlistScope = "playlist:${playlistName}"
            val songs = sortLibrarySongs(uris.mapNotNull { librarySongByUri[it] }, playlistScope, true)
            libraryContainer.addView(label(playlistName.uppercase(), 16f, Color.WHITE).apply {
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(8), dp(4), dp(8), dp(4))
            })
            libraryContainer.addView(label("${songs.size} dal", 10f, Color.rgb(165, 172, 190)).apply {
                setPadding(dp(8), 0, dp(8), dp(10))
            })
            addListOptionsHeader("LISTA RENDEZÉSE ÉS NÉZETE", playlistScope, { showPlaylistDetail(playlistName) }, true)
            libraryContainer.addView(button("▶  TELJES LISTA LEJÁTSZÁSA", Color.rgb(91, 67, 157), Color.WHITE) {
                if (songs.isEmpty()) { toast("Ez a lista jelenleg üres."); return@button }
                queue.clear()
                queue.addAll(songs.map { it.uri })
                currentIndex = 0
                startTrack(queue[0])
                updateNextTrack()
            }, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(7) })
            libraryContainer.addView(button("＋  DALOK HOZZÁADÁSA", Color.rgb(35, 65, 75), Color.WHITE) {
                if (librarySongs.isEmpty()) { toast("Előbb frissítsd a zenetárat."); return@button }
                val titles = librarySongs.map { it.title + " — " + it.artist }.toTypedArray()
                val checked = BooleanArray(librarySongs.size)
                val chosen = mutableListOf<Int>()
                android.app.AlertDialog.Builder(this)
                    .setTitle("Dalok hozzáadása")
                    .setMultiChoiceItems(titles, checked) { _, which, isChecked ->
                        if (isChecked) chosen.add(which) else chosen.remove(which)
                    }
                    .setNegativeButton("Mégse", null)
                    .setPositiveButton("HOZZÁADÁS") { _, _ ->
                        val updated = playlistUris(playlistName)
                        chosen.sorted().forEach { index ->
                            val uri = librarySongs[index].uri.toString()
                            if (!updated.contains(uri)) updated.add(uri)
                        }
                        savePlaylist(playlistName, updated)
                        showPlaylistDetail(playlistName)
                        toast("A kiválasztott dalok hozzáadva.")
                    }.show()
            }, LinearLayout.LayoutParams(-1, dp(42)).apply { bottomMargin = dp(10) })

            if (songs.isEmpty()) {
                libraryContainer.addView(label("Még nincs dal ebben a listában.", 12f, Color.LTGRAY).apply {
                    setPadding(dp(8), dp(10), dp(8), dp(10))
                })
            }
            val playlistViewMode = listViewMode(playlistScope)
            fun addPlaylistSongActions(row: LinearLayout, song: LibrarySong) {
                val originalIndex = uris.indexOf(song.uri.toString())
                if (listSortMode(playlistScope, true) == "manual") {
                    row.addView(button("↑", Color.rgb(35, 41, 53), Color.WHITE) {
                        val updated = playlistUris(playlistName)
                        if (originalIndex > 0 && originalIndex < updated.size) {
                            val item = updated.removeAt(originalIndex)
                            updated.add(originalIndex - 1, item)
                            savePlaylist(playlistName, updated)
                            showPlaylistDetail(playlistName)
                        }
                    }, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(2) })
                    row.addView(button("↓", Color.rgb(35, 41, 53), Color.WHITE) {
                        val updated = playlistUris(playlistName)
                        if (originalIndex >= 0 && originalIndex < updated.lastIndex) {
                            val item = updated.removeAt(originalIndex)
                            updated.add(originalIndex + 1, item)
                            savePlaylist(playlistName, updated)
                            showPlaylistDetail(playlistName)
                        }
                    }, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(2) })
                }
                row.addView(button("×", Color.rgb(83, 39, 43), Color.WHITE) {
                    val updated = playlistUris(playlistName)
                    if (originalIndex in updated.indices) {
                        updated.removeAt(originalIndex)
                        savePlaylist(playlistName, updated)
                        showPlaylistDetail(playlistName)
                    }
                }, LinearLayout.LayoutParams(dp(32), dp(32)))
            }
            fun playlistSongClick(index: Int, song: LibrarySong) {
                queue.clear()
                queue.addAll(songs.map { it.uri })
                currentIndex = index
                startTrack(song.uri)
                updateNextTrack()
            }

            if (playlistViewMode == "grid") {
                songs.forEachIndexed { index, song ->
                    if (index % 2 == 0) {
                        val line = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.TOP
                        }
                        for (column in 0..1) {
                            val itemIndex = index + column
                            if (itemIndex < songs.size) {
                                val item = songs[itemIndex]
                                val tile = LinearLayout(this).apply {
                                    orientation = LinearLayout.VERTICAL
                                    background = rounded(Color.rgb(24, 29, 39), 11)
                                    setPadding(dp(7), dp(8), dp(7), dp(7))
                                }
                                tile.addView(label("♫", 21f, Color.rgb(230, 190, 105)).apply { gravity = Gravity.CENTER })
                                tile.addView(label(item.title, 11f, Color.WHITE).apply {
                                    typeface = Typeface.DEFAULT_BOLD
                                    gravity = Gravity.CENTER
                                    maxLines = 2
                                    ellipsize = android.text.TextUtils.TruncateAt.END
                                    setOnClickListener { playlistSongClick(itemIndex, item) }
                                })
                                tile.addView(label(item.artist, 9f, Color.rgb(190, 195, 220)).apply {
                                    gravity = Gravity.CENTER
                                    maxLines = 1
                                    ellipsize = android.text.TextUtils.TruncateAt.END
                                })
                                tile.addView(label(item.album, 8f, Color.rgb(145, 153, 175)).apply {
                                    gravity = Gravity.CENTER
                                    maxLines = 1
                                    ellipsize = android.text.TextUtils.TruncateAt.END
                                })
                                val controls = LinearLayout(this).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    gravity = Gravity.CENTER
                                }
                                addPlaylistSongActions(controls, item)
                                tile.addView(controls)
                                line.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply {
                                    setMargins(dp(3), dp(3), dp(3), dp(3))
                                })
                            } else {
                                line.addView(View(this), LinearLayout.LayoutParams(0, dp(120), 1f))
                            }
                        }
                        libraryContainer.addView(line)
                    }
                }
            } else {
                songs.forEachIndexed { index, song ->
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = rounded(Color.rgb(24, 29, 39), 10)
                        setPadding(dp(8), dp(3), dp(4), dp(3))
                    }
                    if (playlistViewMode == "detailed") {
                        row.addView(label("♫", 19f, Color.rgb(230, 190, 105)).apply {
                            gravity = Gravity.CENTER
                        }, LinearLayout.LayoutParams(dp(30), dp(44)))
                    }
                    val text = if (playlistViewMode == "compact") {
                        "${song.title}  •  ${song.artist}"
                    } else {
                        "${song.title}\n${song.artist}\n${song.album}"
                    }
                    row.addView(label(text, if (playlistViewMode == "compact") 10f else 11f, Color.WHITE).apply {
                        setPadding(dp(3), dp(8), dp(3), dp(8))
                        maxLines = if (playlistViewMode == "compact") 1 else 3
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setOnClickListener { playlistSongClick(index, song) }
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addPlaylistSongActions(row, song)
                    libraryContainer.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                        bottomMargin = dp(5)
                    })
                }
            }

            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            actions.addView(button("ÁTNEVEZÉS", Color.rgb(49, 57, 72), Color.WHITE) {
                val edit = EditText(this).apply { setText(playlistName); hint = "Lista neve" }
                android.app.AlertDialog.Builder(this).setTitle("Lista átnevezése").setView(edit)
                    .setNegativeButton("Mégse", null)
                    .setPositiveButton("MENTÉS") { _, _ ->
                        val newName = edit.text.toString().trim()
                        val currentNames = playlistNames()
                        if (newName.isBlank()) toast("A név nem lehet üres.")
                        else if (newName != playlistName && currentNames.contains(newName)) toast("Ilyen nevű lista már létezik.")
                        else if (newName != playlistName) {
                            val content = prefs.getString("playlist_" + playlistName, "[]") ?: "[]"
                            currentNames.remove(playlistName)
                            currentNames.add(newName)
                            prefs.edit().remove("playlist_" + playlistName)
                                .putString("playlist_" + newName, content)
                                .putStringSet("playlist_names", currentNames).apply()
                            showPlaylists()
                        }
                    }.show()
            }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(4) })
            actions.addView(button("LISTA TÖRLÉSE", Color.rgb(83, 39, 43), Color.WHITE) {
                android.app.AlertDialog.Builder(this).setTitle("Lejátszási lista törlése?")
                    .setMessage("A dalok a telefonról nem törlődnek, csak ez a lista.")
                    .setNegativeButton("Mégse", null)
                    .setPositiveButton("TÖRLÉS") { _, _ ->
                        val currentNames = playlistNames()
                        currentNames.remove(playlistName)
                        prefs.edit().remove("playlist_" + playlistName)
                            .putStringSet("playlist_names", currentNames).apply()
                        showPlaylists()
                    }.show()
            }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(4) })
            libraryContainer.addView(actions, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(12)
                bottomMargin = dp(12)
            })
        }

        val names = playlistNames()
        names.sorted().forEach { name ->
            val uris = playlistUris(name)
            val songCount = uris.count { librarySongByUri.containsKey(it) }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(Color.rgb(24, 29, 39), 12)
                setPadding(dp(12), dp(10), dp(8), dp(10))
                isClickable = true
                isFocusable = true
            }
            row.addView(label("♫  ${name}\n     ${songCount} dal", 12f, Color.WHITE).apply {
                typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(button("MEGNYIT", Color.rgb(49, 57, 72), Color.WHITE) {
                showPlaylistDetail(name)
            }, LinearLayout.LayoutParams(dp(88), dp(38)))
            row.setOnClickListener { showPlaylistDetail(name) }
            libraryContainer.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
        }

        libraryContainer.addView(button("＋ AKTUÁLIS SOR MENTÉSE LISTAKÉNT", Color.rgb(91, 67, 157), Color.WHITE) {
            if (queue.isEmpty()) { toast("Előbb válassz zenéket."); return@button }
            val edit = EditText(this).apply { hint = "Lejátszási lista neve" }
            android.app.AlertDialog.Builder(this).setTitle("Új lejátszási lista").setView(edit)
                .setNegativeButton("Mégse", null)
                .setPositiveButton("MENTÉS") { _, _ ->
                    val name = edit.text.toString().trim()
                    val currentNames = playlistNames()
                    if (name.isBlank()) toast("Adj nevet a listának.")
                    else if (currentNames.contains(name)) toast("Ilyen nevű lista már létezik.")
                    else {
                        currentNames.add(name)
                        val snapshot = queue.map { it.toString() }
                        val arr = org.json.JSONArray()
                        snapshot.forEach { arr.put(it) }
                        prefs.edit().putStringSet("playlist_names", currentNames)
                            .putString("playlist_" + name, arr.toString()).apply()
                        showPlaylists()
                    }
                }.show()
        }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8); bottomMargin = dp(8) })

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

        val songs = librarySongs.filter { it.folder == path }
        val folderScope = "folder_songs"
        addListOptionsHeader("DALOK A MAPPÁBAN • ${songs.size}", folderScope, { renderFolderBrowser(path, isRoot) })
        renderSongRows(songs, folderScope)
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
        val filtered = sortLibrarySongs(librarySongs.filter { it.title.contains(query, true) || it.artist.contains(query, true) || it.album.contains(query, true) || it.folder.contains(query, true) }, "library")
        addListOptionsHeader("ZENETÁR • ${filtered.size} DAL", "library", { renderLibrary(query) })
        if (filtered.isEmpty()) {
            libraryContainer.addView(label(if (librarySongs.isEmpty()) "Még nincs zene a könyvtárban. Adj hozzá fájlokat vagy frissítsd a listát." else "Nincs találat erre a keresésre.", 12f, Color.rgb(190, 194, 215)).apply { setPadding(dp(8), dp(14), dp(8), dp(14)) })
            return
        }
        val end = minOf(filtered.size, libraryVisibleLimit)
        renderSongRows(filtered.take(end), "library")
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
        if (requestCode == backupExportRequest) {
            val uri = data.data ?: return
            runCatching { AuraBackupManager.write(this, uri) }
                .onSuccess { toast("AURA biztonsági mentés elkészült.") }
                .onFailure { toast("A mentés nem sikerült: ${it.message ?: "hiba"}") }
            return
        }
        if (requestCode == backupImportRequest) {
            val uri = data.data ?: return
            runCatching { AuraBackupManager.read(this, uri) }
                .onSuccess { backup ->
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Beállítások visszaállítása?")
                        .setMessage("A mentés felülírja az AURA jelenlegi beállításait és lejátszási listáit. A zenefájlokat nem módosítja.")
                        .setNegativeButton("MÉGSE", null)
                        .setPositiveButton("VISSZAÁLLÍTÁS") { _, _ ->
                            runCatching { AuraBackupManager.restore(this, backup) }
                                .onSuccess { toast("AURA mentés visszaállítva."); recreate() }
                                .onFailure { toast("A visszaállítás nem sikerült: ${it.message ?: "hiba"}") }
                        }.show()
                }
                .onFailure { toast("A mentés nem olvasható: ${it.message ?: "hiba"}") }
            return
        }
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
    private fun updateShuffleRepeatButtons() {
        shuffleButton?.text = when (shuffleMode) { 1 -> "SHUFFLE: LISTA"; 2 -> "SHUFFLE: ÖSSZES"; else -> "SHUFFLE: KI" }
        repeatButton?.text = when (repeatMode) { 1 -> "ISMÉTLÉS: DAL"; 2 -> "ISMÉTLÉS: LISTA"; else -> "ISMÉTLÉS: KI" }
        shuffleButton?.setTextColor(if (shuffleMode == 0) Color.WHITE else gold)
        repeatButton?.setTextColor(if (repeatMode == 0) Color.WHITE else gold)
    }
    private fun cycleShuffleMode() {
        if (shuffleMode == 0) shuffleOriginalQueue = queue.toList()
        shuffleMode = (shuffleMode + 1) % 3
        when (shuffleMode) {
            1 -> {
                val current = selectedUri ?: queue.getOrNull(currentIndex)
                val source = shuffleOriginalQueue ?: queue.toList()
                queue.clear(); current?.let { queue.add(it) }; queue.addAll(source.filter { it != current }.shuffled())
                currentIndex = 0
            }
            2 -> {
                val current = selectedUri ?: queue.getOrNull(currentIndex)
                val all = librarySongs.map { it.uri }.distinct()
                queue.clear(); current?.takeIf { all.contains(it) }?.let { queue.add(it) }
                queue.addAll(all.filter { it != current }.shuffled())
                if (queue.isEmpty()) queue.addAll((shuffleOriginalQueue ?: emptyList()).shuffled())
                currentIndex = 0
            }
            else -> {
                val original = shuffleOriginalQueue
                if (!original.isNullOrEmpty()) {
                    val current = selectedUri
                    queue.clear(); queue.addAll(original)
                    current?.let { uri -> queue.indexOf(uri).takeIf { it >= 0 }?.let { currentIndex = it } }
                }
                shuffleOriginalQueue = null
            }
        }
        updateShuffleRepeatButtons(); updateNextTrack()
        toast(when (shuffleMode) { 1 -> "Az aktuális lista keverése bekapcsolva"; 2 -> "A teljes zenetár keverése bekapcsolva"; else -> "Keverés kikapcsolva" })
    }
    private fun cycleRepeatMode() {
        repeatMode = (repeatMode + 1) % 3
        updateShuffleRepeatButtons()
        toast(when (repeatMode) { 1 -> "Aktuális dal ismétlése"; 2 -> "Lista ismétlése"; else -> "Ismétlés kikapcsolva" })
    }
    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        try { if (p.duration > 0) p.seekTo((p.currentPosition + deltaMs).coerceIn(0L, p.duration)) } catch (_: Exception) {}
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
        else if (repeatMode == 2 || shuffleMode != 0) currentIndex = 0
        else return
        startTrack(queue[currentIndex])
    }
    private fun attachVuMeter() {
        eqProcessor.onLevel = { left, right ->
            val threshold = vuThreshold
            val gain = vuGain
            val delay = vuDelayMs
            // Soft-knee response: suppresses low-level peaks while keeping strong transients visible.
            val leftRaw = ((left - threshold).coerceAtLeast(0f) / (1f - threshold).coerceAtLeast(0.01f) * gain).coerceIn(0f, 1f)
            val rightRaw = ((right - threshold).coerceAtLeast(0f) / (1f - threshold).coerceAtLeast(0.01f) * gain).coerceIn(0f, 1f)
            // Keep the VU response proportional to signal amplitude. Squaring
            // the level made normal and medium passages look far too weak.
            val leftLevel = leftRaw
            val rightLevel = rightRaw
            val mono = vuMono
            val displayLeft = if (mono) (leftLevel + rightLevel) * 0.5f else leftLevel
            val displayRight = if (mono) displayLeft else rightLevel
            handler.postDelayed({
                if (player?.isPlaying == true) setVuLevels(displayLeft, displayRight)
            }, delay)
        }
    }

    private fun startTrack(uri: Uri) {
        val activePlayer = player
        if (activePlayer == null) {
            // Service creation is asynchronous; queue the requested track instead of failing immediately.
            pendingTrackStartUri = uri
            pendingTrackStartAttempts = 0
            pendingTrackStartRunnable?.let { handler.removeCallbacks(it) }
            try { startService(Intent(this, AuraPlaybackService::class.java)) } catch (_: Exception) {}
            val retry = object : Runnable {
                override fun run() {
                    val requestedUri = pendingTrackStartUri ?: return
                    val readyPlayer = player
                    if (readyPlayer != null) {
                        pendingTrackStartUri = null
                        pendingTrackStartRunnable = null
                        pendingTrackStartAttempts = 0
                        startTrack(requestedUri)
                        return
                    }
                    pendingTrackStartAttempts++
                    if (pendingTrackStartAttempts % 10 == 0) {
                        try { startService(Intent(this@MainActivity, AuraPlaybackService::class.java)) } catch (_: Exception) {}
                    }
                    if (pendingTrackStartAttempts < 40) {
                        handler.postDelayed(this, 150L)
                    } else {
                        pendingTrackStartUri = null
                        pendingTrackStartRunnable = null
                        pendingTrackStartAttempts = 0
                        toast("A háttérlejátszó nem indult el. Próbáld újra az alkalmazás megnyitása után.")
                    }
                }
            }
            pendingTrackStartRunnable = retry
            toast("AURA lejátszó indítása…")
            handler.postDelayed(retry, 150L)
            return
        }
        pendingTrackStartUri = null
        pendingTrackStartRunnable = null
        pendingTrackStartAttempts = 0
        eqClippingActive = false
        soundEngineClippingActive = false
        clippingStatusLabel?.apply {
            text = "JELSZINT: RENDBEN — nincs digitális túlvezérlés"
            setTextColor(Color.rgb(120, 205, 145))
            background = GradientDrawable().apply { cornerRadius = dp(9).toFloat(); setColor(Color.rgb(22, 43, 35)); setStroke(dp(1), Color.rgb(48, 105, 75)) }
        }
        openPlayerPage?.invoke()
        renderQueue()
        releaseAudio()
        attachVuMeter()
        eqProcessor.onClipping = {
            handler.post {
                eqClippingActive = true
                clippingStatusLabel?.apply {
                    text = "FIGYELEM: CLIPPING AZ EREDETI EQ KIMENETÉN — a jel már az új DSP előtt túlvezérlődik"
                    setTextColor(Color.rgb(255, 130, 105))
                    background = GradientDrawable().apply { cornerRadius = dp(9).toFloat(); setColor(Color.rgb(55, 28, 27)); setStroke(dp(1), Color.rgb(150, 65, 55)) }
                }
            }
        }
        try {
            selectedUri = uri
            readMetadata(uri)
            updateNextTrack()
            refreshLibrarySelection()
            AuraPlaybackService.onPreviousRequested = { playPrevious() }
            AuraPlaybackService.onNextRequested = { playNext() }
            AuraPlaybackService.syncQueue(queue.toList(), currentIndex, repeatMode, shuffleMode)
            val exo = activePlayer
            playbackListener?.let { exo.removeListener(it) }
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> if (exo.playWhenReady) {
                            setVuActive(true); playButton.text = "Ⅱ"; vinylView.invalidate()
                            handler.removeCallbacks(progressTask); handler.post(progressTask)
                        }
                        Player.STATE_ENDED -> {
                            setVuActive(false)
                            when {
                                repeatMode == 1 -> { exo.seekTo(0); exo.play() }
                                currentIndex + 1 < queue.size -> { currentIndex++; selectedUri = queue[currentIndex]; updateNextTrack(); startTrack(queue[currentIndex]) }
                                repeatMode == 2 || shuffleMode != 0 -> { currentIndex = 0; selectedUri = queue[currentIndex]; updateNextTrack(); startTrack(queue[currentIndex]) }
                                else -> { playButton.text = "▶"; updateNextTrack() }
                            }
                        }
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    playButton.text = "▶"; toast("Lejátszási hiba: " + (error.message ?: "ismeretlen hiba"))
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    setVuActive(isPlaying); playButton.text = if (isPlaying) "Ⅱ" else "▶"; vinylView.invalidate()
                }
            }
            playbackListener = listener
            exo.addListener(listener)
            val metadataBuilder = MediaMetadata.Builder()
                .setTitle(trackLabel.text.toString())
                .setArtist(metadataLabel.text.toString())
                .setAlbumTitle(albumLabel.text.toString())
            currentArtworkData?.let { metadataBuilder.setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
            val mediaMetadata = metadataBuilder.build()
            exo.setMediaItem(MediaItem.Builder().setUri(uri).setMediaMetadata(mediaMetadata).build())
            exo.prepare()
            exo.playWhenReady = true
        } catch (e: Exception) {
            try { player?.stop() } catch (_: Exception) {}
            playButton.text = "▶"
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
        eqContainer.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(5, 7, 11), Color.rgb(19, 20, 25), Color.rgb(7, 9, 13))).apply {
            cornerRadius = dp(20).toFloat()
            setStroke(dp(1), Color.rgb(83, 67, 39))
        }
        eqContainer.setPadding(dp(12), dp(14), dp(12), dp(14))
        val prefs = getSharedPreferences("aurix_settings", MODE_PRIVATE)
        val names = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "12k", "14k", "16k")
        val values = IntArray(12) { i -> prefs.getInt("eq_band_$i", 0).coerceIn(-12, 12) }
        values.forEachIndexed { i, value -> eqProcessor.setBandGainDb(i, value.toFloat()) }

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(label("EQUALIZER", 19f, Color.WHITE).apply { typeface = Typeface.create("sans-serif-black", Typeface.BOLD); letterSpacing = .06f })
        heading.addView(label("Prémium hangzás, egyszerűen", 10f, Color.rgb(157, 160, 168)))
        top.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        val soundEngineQuickButton = label("DSP  ⚙", 10f, Color.rgb(255, 207, 112)).apply {
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(Color.rgb(48, 39, 25))
                setStroke(dp(1), Color.rgb(145, 105, 48))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { openSoundEnginePage?.invoke() }
        }
        top.addView(soundEngineQuickButton, LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(9) })
        top.addView(label("12 BAND  •  ±12 dB", 9f, gold).apply { typeface = Typeface.DEFAULT_BOLD })
        eqContainer.addView(top)

        val presetScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val presets = listOf(
            "Flat" to intArrayOf(0,0,0,0,0,0,0,0,0,0,0,0),
            "Rock" to intArrayOf(5,4,3,1,-1,-1,0,2,4,5,5,4),
            "Pop" to intArrayOf(-1,1,3,4,2,0,-1,-1,1,2,2,2),
            "Jazz" to intArrayOf(3,2,1,0,-1,-1,0,2,3,3,2,1),
            "Classical" to intArrayOf(4,3,2,1,-1,-2,-1,1,2,3,3,3),
            "Dance" to intArrayOf(5,4,2,0,0,2,3,4,4,3,2,1)
        )
        presets.forEach { preset ->
            val chip = TextView(this).apply {
                text = preset.first
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(if (preset.first == "Flat") gold else Color.rgb(222, 224, 230))
                setPadding(dp(17), dp(10), dp(17), dp(10))
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.rgb(33, 31, 27), Color.rgb(15, 17, 21))).apply {
                    cornerRadius = dp(13).toFloat()
                    setStroke(dp(1), if (preset.first == "Flat") Color.rgb(194, 139, 40) else Color.rgb(65, 68, 75))
                }
                isClickable = true
                setOnClickListener {
                    preset.second.forEachIndexed { i, v ->
                        prefs.edit().putInt("eq_band_$i", v).apply()
                        eqProcessor.setBandGainDb(i, v.toFloat())
                    }
                    renderEqualizer()
                }
            }
            presetRow.addView(chip, LinearLayout.LayoutParams(-2, dp(39)).apply { marginEnd = dp(7) })
        }
        presetScroll.addView(presetRow)
        eqContainer.addView(presetScroll, LinearLayout.LayoutParams(-1, dp(47)).apply { topMargin = dp(13) })

        eqContainer.addView(label("FREKVENCIA SÁVOK  •  HÚZD FEL / LE A CSÚSZKÁKAT", 9f, Color.rgb(174, 160, 133)).apply {
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(2), dp(10), 0, dp(5))
        })
        val bandScroll = object : HorizontalScrollView(this) {
            private var gestureDownX = 0f
            private var gestureDownY = 0f
            override fun onInterceptTouchEvent(event: android.view.MotionEvent): Boolean {
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        gestureDownX = event.x
                        gestureDownY = event.y
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dx = kotlin.math.abs(event.x - gestureDownX)
                        val dy = kotlin.math.abs(event.y - gestureDownY)
                        val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
                        // Intercept a clear horizontal swipe before the EQ band control can adjust its value.
                        if (dx > slop && dx > dy * 1.15f) return true
                    }
                }
                return super.onInterceptTouchEvent(event)
            }
        }.apply {
            isHorizontalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, dp(3), 0, dp(5))
        }
        val bandRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        names.forEachIndexed { index, name ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.rgb(25, 26, 29), Color.rgb(10, 12, 16))).apply {
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1), Color.rgb(59, 54, 44))
                }
                setPadding(dp(3), dp(7), dp(3), dp(6))
            }
            val bandView = EqBandControl(name, values[index]) { db ->
                values[index] = db
                prefs.edit().putInt("eq_band_$index", db).apply()
                eqProcessor.setBandGainDb(index, db.toFloat())
            }
            // Szélesebb, teljes magasságú érintési felület a keskeny EQ csúszka körül.
            card.addView(bandView, LinearLayout.LayoutParams(dp(62), dp(214)))
            bandRow.addView(card, LinearLayout.LayoutParams(dp(68), dp(225)).apply { marginEnd = dp(7) })
        }
        bandScroll.addView(bandRow)
        eqContainer.addView(bandScroll, LinearLayout.LayoutParams(-1, dp(238)))

        // Dedicated, wider touch strip for horizontal EQ navigation.
        val bandScrollHandle = object : View(this) {
            private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(54, 55, 59) }
            private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(205, 151, 65) }
            private var draggingHandle = false
            private var touchOffset = 0f

            private fun metrics(): Triple<Float, Float, Float> {
                val inset = dp(8).toFloat()
                val trackWidth = (width - inset * 2f).coerceAtLeast(1f)
                val contentWidth = (bandScroll.getChildAt(0)?.width ?: width).toFloat().coerceAtLeast(width.toFloat())
                val maxScroll = (contentWidth - width).coerceAtLeast(0f)
                val thumbWidth = (trackWidth * width / contentWidth).coerceAtLeast(dp(42).toFloat()).coerceAtMost(trackWidth)
                val thumbLeft = inset + if (maxScroll > 0f) {
                    (trackWidth - thumbWidth) * (bandScroll.scrollX / maxScroll).coerceIn(0f, 1f)
                } else 0f
                return Triple(thumbLeft, thumbWidth, maxScroll)
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val inset = dp(8).toFloat()
                val centerY = height / 2f
                val trackRight = (width - inset).coerceAtLeast(inset + 1f)
                canvas.drawRoundRect(inset, centerY - dp(2).toFloat(), trackRight, centerY + dp(2).toFloat(), dp(2).toFloat(), dp(2).toFloat(), trackPaint)
                val (thumbLeft, thumbWidth, _) = metrics()
                canvas.drawRoundRect(thumbLeft, centerY - dp(4).toFloat(), thumbLeft + thumbWidth, centerY + dp(4).toFloat(), dp(4).toFloat(), dp(4).toFloat(), thumbPaint)
            }

            override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
                val (thumbLeft, thumbWidth, maxScroll) = metrics()
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        draggingHandle = true
                        touchOffset = if (event.x in thumbLeft..(thumbLeft + thumbWidth)) event.x - thumbLeft else thumbWidth / 2f
                        updateScroll(event.x - touchOffset + thumbWidth / 2f, thumbWidth, maxScroll)
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        if (draggingHandle) updateScroll(event.x - touchOffset + thumbWidth / 2f, thumbWidth, maxScroll)
                        return true
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        draggingHandle = false
                        parent?.requestDisallowInterceptTouchEvent(false)
                        performClick()
                        return true
                    }
                }
                return true
            }

            private fun updateScroll(thumbCenterX: Float, thumbWidth: Float, maxScroll: Float) {
                val inset = dp(8).toFloat()
                val trackWidth = (width - inset * 2f).coerceAtLeast(1f)
                val fraction = ((thumbCenterX - inset - thumbWidth / 2f) / (trackWidth - thumbWidth).coerceAtLeast(1f)).coerceIn(0f, 1f)
                bandScroll.scrollTo((fraction * maxScroll).toInt(), 0)
                invalidate()
            }

            override fun performClick(): Boolean { super.performClick(); return true }
        }.apply {
            contentDescription = "EQ sávok vízszintes görgetése"
            isClickable = true
        }
        bandScroll.setOnScrollChangeListener { _, _, _, _, _ -> bandScrollHandle.invalidate() }
        eqContainer.addView(bandScrollHandle, LinearLayout.LayoutParams(-1, dp(32)).apply {
            topMargin = dp(2)
            bottomMargin = dp(5)
        })

        val dialRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(13), 0, dp(5))
        }
        fun addDial(title: String, shortTitle: String, initial: Int, minValue: Int, maxValue: Int, format: (Int) -> String, onChange: (Int) -> Unit) {
            var selectedValue = initial
            val dial = TextView(this).apply {
                text = "${shortTitle}\n${format(initial)}"
                gravity = Gravity.CENTER
                textSize = 10f
                setTextColor(Color.rgb(255, 214, 145))
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.rgb(57, 43, 23), Color.rgb(15, 17, 21))).apply {
                    shape = GradientDrawable.OVAL
                    setStroke(dp(2), Color.rgb(176, 126, 48))
                }
                isClickable = true
                isFocusable = true
            }
            dialRow.addView(dial, LinearLayout.LayoutParams(dp(82), dp(82)).apply {
                weight = 1f
                width = 0
                marginStart = dp(5)
                marginEnd = dp(5)
            })
            dial.setOnClickListener {
                val dialogContent = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(22), dp(8), dp(22), dp(4))
                }
                val currentValue = TextView(this).apply {
                    text = format(selectedValue)
                    textSize = 18f
                    gravity = Gravity.CENTER
                    setTextColor(gold)
                    typeface = Typeface.DEFAULT_BOLD
                }
                val seek = SeekBar(this).apply {
                    max = maxValue - minValue
                    progress = (selectedValue - minValue).coerceIn(0, max)
                    progressTintList = android.content.res.ColorStateList.valueOf(gold)
                    thumbTintList = android.content.res.ColorStateList.valueOf(Color.rgb(255, 207, 91))
                }
                seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            val value = progress + minValue
                            selectedValue = value
                            currentValue.text = format(value)
                            dial.text = "${shortTitle}\n${format(value)}"
                            onChange(value)
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
                dialogContent.addView(currentValue)
                dialogContent.addView(seek)
                android.app.AlertDialog.Builder(this)
                    .setTitle(title)
                    .setView(dialogContent)
                    .setPositiveButton("KÉSZ", null)
                    .show()
            }
        }

        val bass = prefs.getInt("eq_bass", 0).coerceIn(0, 120)
        eqProcessor.setBassBoostDb(bass / 10f)
        addDial("Bass Boost", "BASS", bass, 0, 120, { "%.1f dB".format(it / 10f) }) { v ->
            prefs.edit().putInt("eq_bass", v).apply()
            eqProcessor.setBassBoostDb(v / 10f)
        }
        val treble = prefs.getInt("eq_treble", 0).coerceIn(0, 120)
        eqProcessor.setTrebleBoostDb(treble / 10f)
        addDial("Treble Boost", "TREBLE", treble, 0, 120, { "%.1f dB".format(it / 10f) }) { v ->
            prefs.edit().putInt("eq_treble", v).apply()
            eqProcessor.setTrebleBoostDb(v / 10f)
        }
        val preamp = prefs.getInt("eq_preamp", 0).coerceIn(-6, 6)
        eqProcessor.setPreampDb(preamp.toFloat())
        addDial("Előerősítés", "PREAMP", preamp, -6, 6, { "%+d dB".format(it) }) { v ->
            prefs.edit().putInt("eq_preamp", v).apply()
            eqProcessor.setPreampDb(v.toFloat())
        }
        eqContainer.addView(dialRow, LinearLayout.LayoutParams(-1, dp(94)))

        val reset = TextView(this).apply {
            text = "↺    EQ ALAPHELYZET"
            textSize = 11f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(255, 214, 126))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(48, 37, 20), Color.rgb(20, 18, 16))).apply {
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.rgb(150, 105, 36))
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isClickable = true
            setOnClickListener { resetEq() }
        }
        eqContainer.addView(reset, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(12) })
        eqContainer.addView(label("A beállítások azonnal érvényesülnek a lejátszásban.", 10f, Color.rgb(135, 139, 148)).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(9), 0, dp(2))
        })
    }

    private inner class EqBandControl(
        private val frequency: String,
        initialDb: Int,
        private val onGainChanged: (Int) -> Unit
    ) : View(this@MainActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var gain = initialDb.coerceIn(-12, 12)
        private var dragging = false

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            paint.shader = null
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            paint.textSize = dp(10).toFloat()
            paint.color = Color.rgb(224, 218, 205)
            canvas.drawText(frequency, w / 2f, dp(15).toFloat(), paint)
            paint.textSize = dp(8).toFloat()
            paint.color = Color.rgb(132, 133, 137)
            canvas.drawText("Hz", w / 2f, dp(27).toFloat(), paint)

            val x = w / 2f
            val top = dp(39).toFloat()
            val bottom = h - dp(35).toFloat()
            val center = (top + bottom) / 2f
            val knobY = center - (gain / 12f) * ((bottom - top) / 2f)
            paint.strokeWidth = dp(4).toFloat()
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = Color.rgb(58, 59, 62)
            canvas.drawLine(x, top, x, bottom, paint)
            paint.strokeWidth = dp(3).toFloat()
            paint.color = Color.rgb(255, 190, 50)
            canvas.drawLine(x, center, x, knobY, paint)
            paint.strokeWidth = dp(1).toFloat()
            paint.color = Color.rgb(92, 83, 65)
            canvas.drawLine(x - dp(12), center, x + dp(12), center, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(255, 191, 55)
            canvas.drawCircle(x, knobY, dp(9).toFloat(), paint)
            paint.color = Color.rgb(27, 27, 29)
            canvas.drawCircle(x, knobY, dp(5).toFloat(), paint)
            paint.textSize = dp(10).toFloat()
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.color = Color.rgb(255, 205, 105)
            canvas.drawText("%+d".format(gain), x, h - dp(7).toFloat(), paint)
        }

        private fun updateFromY(y: Float) {
            val top = dp(39).toFloat()
            val bottom = height - dp(35).toFloat()
            if (bottom <= top) return
            val fraction = ((bottom - y) / (bottom - top)).coerceIn(0f, 1f)
            val next = (fraction * 24f - 12f).toInt().coerceIn(-12, 12)
            if (next != gain) { gain = next; onGainChanged(next); invalidate() }
        }

        private var downX = 0f
        private var downY = 0f
        private var touchDirectionChosen = false
        private var horizontalGesture = false

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    touchDirectionChosen = false
                    horizontalGesture = false
                    downX = event.x
                    downY = event.y
                    // The EQ control owns touches; horizontal navigation is available only on the handle below.
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (!dragging || horizontalGesture) return true
                    val dx = kotlin.math.abs(event.x - downX)
                    val dy = kotlin.math.abs(event.y - downY)
                    if (!touchDirectionChosen && (dx > slop || dy > slop)) {
                        touchDirectionChosen = true
                        if (dx > dy * 1.15f) {
                            // Ignore horizontal movement on the band itself; do not scroll or change its value.
                            horizontalGesture = true
                            dragging = false
                            return true
                        }
                    }
                    if (dragging) updateFromY(event.y)
                    return true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (dragging && !horizontalGesture) updateFromY(event.y)
                    dragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    performClick()
                    return true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    horizontalGesture = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
            return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
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
        // Dalváltáskor az előző szám borítóját azonnal töröljük.
        // Ha az új fájlban nincs beágyazott kép, az alap háttér marad látható.
        currentArtworkData = null
        if (::artworkView.isInitialized) artworkView.setImageDrawable(null)

        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(this, uri)
            val librarySong = librarySongByUri[uri.toString()]
            trackLabel.text = librarySong?.title?.takeIf { it.isNotBlank() }
                ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment?.substringAfterLast("/")?.substringBeforeLast(".")?.takeIf { it.isNotBlank() && !it.all { ch -> ch.isDigit() } }
                ?: "Ismeretlen cím"
            val cover = r.embeddedPicture
            currentArtworkData = cover
            if (cover != null) {
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(cover, 0, cover.size)
                if (bitmap != null) artworkView.setImageBitmap(bitmap)
            }
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
        playbackListener?.let { player?.removeListener(it) }; playbackListener = null
        eqProcessor.onLevel = null
        eqProcessor.onClipping = null
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    override fun onDestroy() {
        audioDeviceCallback?.let { (getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager).unregisterAudioDeviceCallback(it) }
        audioDeviceCallback = null
        AuraPlaybackService.onPreviousRequested = null
        AuraPlaybackService.onNextRequested = null
        releaseAudio()
        soundEngineProcessor.onClipping = null
        libraryExecutor.shutdownNow(); super.onDestroy()
    }

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
            // A piros tartomány csak a legerősebb, 90% feletti csúcsoknál indul.
            // A skála 90%-áig a sárga/zöld szegmensek mozognak.
            val safeLevel = level.coerceIn(0f, 1f)
            val lit = if (safeLevel <= 0.90f) {
                (safeLevel / 0.90f * 20f).toInt()
            } else {
                (20f + (safeLevel - 0.90f) / 0.10f * 12f).toInt()
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