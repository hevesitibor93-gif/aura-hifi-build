package com.aura.hifi

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioAttributes as PlatformAudioAttributes
import android.os.Build
import org.json.JSONArray
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Owns playback independently from the Activity, keeping audio and the Android
 * media session alive when the player screen is closed or the device is locked.
 */
@androidx.media3.common.util.UnstableApi
class AuraPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var queueUris: List<Uri> = emptyList()
    private var queueIndex = 0
    private var queueRepeatMode = 0
    private var queueShuffleMode = 0
    private val playbackPrefs by lazy { getSharedPreferences("aura_playback_state", Context.MODE_PRIVATE) }
    private val saveHandler = Handler(Looper.getMainLooper())
    private var audioDeviceCallback: android.media.AudioDeviceCallback? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var isDuckedForNotification = false
    private var volumeBeforeDucking = 1f

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        val activePlayer = player ?: return@OnAudioFocusChangeListener
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (!isDuckedForNotification) {
                    volumeBeforeDucking = activePlayer.volume
                    isDuckedForNotification = true
                }
                activePlayer.volume = volumeBeforeDucking * 0.20f
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (isDuckedForNotification) {
                    activePlayer.volume = volumeBeforeDucking
                    isDuckedForNotification = false
                }
                hasAudioFocus = true
                // Deliberately do not resume playback: interruptions require a manual Play.
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS -> {
                restoreVolumeAfterDucking()
                activePlayer.pause()
                abandonAudioFocus()
            }
        }
    }
    private val savePositionTask = object : Runnable {
        override fun run() {
            savePlaybackState()
            if (player?.isPlaying == true) saveHandler.postDelayed(this, 5000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean
            ): androidx.media3.exoplayer.audio.AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false)
                    .setAudioProcessors(arrayOf(eqProcessor, soundEngineProcessor))
                    .build()
            }
        }

        val exoPlayer = ExoPlayer.Builder(this, renderersFactory).build().apply {
            // Keep the CPU available for active playback while the screen is off.
            setWakeMode(androidx.media3.common.C.WAKE_MODE_LOCAL)
        }
        player = exoPlayer

        // Keep Bluetooth auto-start available while the Activity is not visible.
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager?.let { manager ->
            audioDeviceCallback = object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<android.media.AudioDeviceInfo>) {
                val bluetoothOutputAdded = addedDevices.any { device ->
                    device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        (android.os.Build.VERSION.SDK_INT >= 31 &&
                            (device.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET ||
                                device.type == android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER))
                }
                if (!bluetoothOutputAdded) return
                val enabled = getSharedPreferences("aurix_settings", Context.MODE_PRIVATE)
                    .getBoolean("auto_play_on_bluetooth_connect", false)
                val activePlayer = player ?: return
                if (enabled && activePlayer.mediaItemCount > 0 && !activePlayer.isPlaying) {
                    activePlayer.play()
                }
            }
        }
            manager.registerAudioDeviceCallback(audioDeviceCallback, saveHandler)
        }

        // Handle focus ourselves so interruptions pause without auto-resuming,
        // while short notification sounds duck the current output to exactly 20%.
        exoPlayer.setAudioAttributes(
            androidx.media3.common.AudioAttributes.DEFAULT,
            false
        )
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady) {
                    if (!requestAudioFocus()) {
                        exoPlayer.pause()
                    }
                } else {
                    restoreVolumeAfterDucking()
                    abandonAudioFocus()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val uri = mediaItem?.localConfiguration?.uri ?: return
                val index = queueUris.indexOf(uri)
                if (index >= 0) queueIndex = index
                savePlaybackState()
                AuraMiniWidgetProvider.updateAll(this@AuraPlaybackService)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                savePlaybackState()
                AuraMiniWidgetProvider.updateAll(this@AuraPlaybackService)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                saveHandler.removeCallbacks(savePositionTask)
                if (isPlaying) saveHandler.postDelayed(savePositionTask, 5000L)
                savePlaybackState()
                AuraMiniWidgetProvider.updateAll(this@AuraPlaybackService)
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                savePlaybackState()
            }
        })

        // Restore only after the focus listener is installed, so startup resume
        // follows the same audio-focus rules as a manual Play action.
        restoreSavedQueue()

        val sessionPlayer = object : androidx.media3.common.ForwardingPlayer(exoPlayer) {
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .build()
            }

            override fun seekToPreviousMediaItem() {
                val callback = onPreviousRequested
                if (callback != null) callback() else skipToPreviousFromService()
            }

            override fun seekToNextMediaItem() {
                val callback = onNextRequested
                if (callback != null) callback() else skipToNextFromService()
            }
        }

        // Tapping the system media notification returns directly to the AURA player tab.
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("aura_open_player", true)
        }
        val sessionActivity = PendingIntent.getActivity(
            this,
            7101,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        mediaSession = MediaSession.Builder(this, sessionPlayer)
            .setId("aura-hifi-session")
            .setSessionActivity(sessionActivity)
            .build()
    }

    private fun requestAudioFocus(): Boolean {
        val manager = audioManager ?: return false
        if (hasAudioFocus) return true

        val attributes = PlatformAudioAttributes.Builder()
            .setUsage(PlatformAudioAttributes.USAGE_MEDIA)
            .setContentType(PlatformAudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener, saveHandler)
                .build()
            audioFocusRequest = request
            manager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }

        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasAudioFocus
    }

    private fun restoreVolumeAfterDucking() {
        val activePlayer = player ?: return
        if (isDuckedForNotification) {
            activePlayer.volume = volumeBeforeDucking
            isDuckedForNotification = false
        }
    }

    private fun abandonAudioFocus() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { manager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(audioFocusChangeListener)
        }
        hasAudioFocus = false
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    private fun skipToPreviousFromService() {
        if (queueUris.isEmpty()) return
        playQueueIndex((queueIndex - 1).coerceAtLeast(0))
    }

    private fun skipToNextFromService() {
        if (queueUris.isEmpty()) return
        when {
            queueIndex < queueUris.lastIndex -> playQueueIndex(queueIndex + 1)
            queueRepeatMode == 2 || queueShuffleMode != 0 -> playQueueIndex(0)
        }
    }

    private fun buildMediaItem(uri: Uri): MediaItem {
        val retriever = MediaMetadataRetriever()
        val metadata = try {
            retriever.setDataSource(this, uri)
            val builder = MediaMetadata.Builder()
                .setTitle(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?: uri.lastPathSegment?.substringAfterLast("/")?.substringBeforeLast(".") ?: "Ismeretlen cím")
                .setArtist(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Ismeretlen előadó")
                .setAlbumTitle(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "Ismeretlen album")
            retriever.embeddedPicture?.let { builder.setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
            builder.build()
        } catch (_: Exception) {
            MediaMetadata.Builder().setTitle(uri.lastPathSegment ?: "Ismeretlen cím").build()
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
        return MediaItem.Builder().setUri(uri).setMediaMetadata(metadata).build()
    }

    private fun playQueueIndex(index: Int) {
        val exo = player ?: return
        val uri = queueUris.getOrNull(index) ?: return
        queueIndex = index
        exo.setMediaItem(buildMediaItem(uri))
        exo.prepare()
        exo.play()
        savePlaybackState()
    }

    private fun restoreSavedQueue() {
        val saved = playbackPrefs
        val array = try { JSONArray(saved.getString(KEY_QUEUE, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        val restored = mutableListOf<Uri>()
        for (i in 0 until array.length()) {
            val value = array.optString(i)
            if (value.isNotBlank()) {
                val uri = Uri.parse(value)
                try {
                    contentResolver.openFileDescriptor(uri, "r")?.use { restored.add(uri) }
                } catch (_: Exception) {
                    // A document may have been moved or its persisted permission revoked.
                }
            }
        }
        queueUris = restored
        queueIndex = saved.getInt(KEY_INDEX, 0).coerceIn(0, (queueUris.size - 1).coerceAtLeast(0))
        queueRepeatMode = saved.getInt(KEY_REPEAT, 0)
        queueShuffleMode = saved.getInt(KEY_SHUFFLE, 0)
        val exo = player ?: return
        val uri = queueUris.getOrNull(queueIndex) ?: return
        val position = saved.getLong(KEY_POSITION, 0L).coerceAtLeast(0L)
        val wasPlaying = saved.getBoolean(KEY_WAS_PLAYING, false)
        val resumeOnAppStart = getSharedPreferences("aurix_settings", Context.MODE_PRIVATE)
            .getBoolean("resume_on_app_start", true)
        val shouldResume = wasPlaying && resumeOnAppStart
        exo.setMediaItem(buildMediaItem(uri))
        exo.prepare()
        if (position > 0L) exo.seekTo(position)
        exo.playWhenReady = shouldResume
    }

    private fun savePlaybackState() {
        val exo = player
        val currentUri = exo?.currentMediaItem?.localConfiguration?.uri
        if (currentUri != null) {
            val foundIndex = queueUris.indexOf(currentUri)
            if (foundIndex >= 0) queueIndex = foundIndex
        }
        val queueJson = JSONArray()
        queueUris.forEach { queueJson.put(it.toString()) }
        playbackPrefs.edit()
            .putString(KEY_QUEUE, queueJson.toString())
            .putInt(KEY_INDEX, queueIndex)
            .putInt(KEY_REPEAT, queueRepeatMode)
            .putInt(KEY_SHUFFLE, queueShuffleMode)
            .putLong(KEY_POSITION, exo?.currentPosition?.coerceAtLeast(0L) ?: 0L)
            .putBoolean(KEY_WAS_PLAYING, exo?.isPlaying == true || exo?.playWhenReady == true)
            .apply()
    }

    data class QueueSnapshot(
        val uris: List<Uri>,
        val index: Int,
        val repeatMode: Int,
        val shuffleMode: Int
    )

    private fun snapshotQueue(): QueueSnapshot =
        QueueSnapshot(queueUris.toList(), queueIndex, queueRepeatMode, queueShuffleMode)

    private fun updateQueue(items: List<Uri>, index: Int, repeatMode: Int, shuffleMode: Int) {
        queueUris = items.toList()
        queueIndex = index.coerceIn(0, (queueUris.size - 1).coerceAtLeast(0))
        queueRepeatMode = repeatMode
        queueShuffleMode = shuffleMode
        savePlaybackState()
    }

    override fun onDestroy() {
        savePlaybackState()
        saveHandler.removeCallbacks(savePositionTask)
        audioDeviceCallback?.let { audioManager?.unregisterAudioDeviceCallback(it) }
        audioDeviceCallback = null
        restoreVolumeAfterDucking()
        abandonAudioFocus()
        audioManager = null
        instance = null
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        private const val KEY_QUEUE = "queue_uris"
        private const val KEY_INDEX = "queue_index"
        private const val KEY_REPEAT = "repeat_mode"
        private const val KEY_SHUFFLE = "shuffle_mode"
        private const val KEY_POSITION = "playback_position"
        private const val KEY_WAS_PLAYING = "was_playing"

        @Volatile
        private var instance: AuraPlaybackService? = null

        fun syncQueue(items: List<Uri>, index: Int, repeatMode: Int, shuffleMode: Int) {
            instance?.updateQueue(items, index, repeatMode, shuffleMode)
        }

        fun getQueueSnapshot(): QueueSnapshot =
            instance?.snapshotQueue() ?: QueueSnapshot(emptyList(), 0, 0, 0)

        fun togglePlaybackFromWidget() {
            val active = player ?: return
            if (active.isPlaying) active.pause() else active.play()
            instance?.let { AuraMiniWidgetProvider.updateAll(it) }
        }

        fun previousFromWidget() {
            val service = instance ?: return
            val callback = onPreviousRequested
            if (callback != null) callback() else service.skipToPreviousFromService()
            AuraMiniWidgetProvider.updateAll(service)
        }

        fun nextFromWidget() {
            val service = instance ?: return
            val callback = onNextRequested
            if (callback != null) callback() else service.skipToNextFromService()
            AuraMiniWidgetProvider.updateAll(service)
        }

        @Volatile
        var player: ExoPlayer? = null
            private set

        val eqProcessor = AurixEqualizerAudioProcessor()
        val soundEngineProcessor = AuraSoundEngineAudioProcessor()

        @Volatile
        var onPreviousRequested: (() -> Unit)? = null

        @Volatile
        var onNextRequested: (() -> Unit)? = null
    }
}
