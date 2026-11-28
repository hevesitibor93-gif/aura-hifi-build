package com.aura.hifi

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioAttributes as PlatformAudioAttributes
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.content.ContentUris
import org.json.JSONArray
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.CommandButton
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executors

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
    private val shuffleExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "AuraFullLibraryShuffle").apply { isDaemon = true }
    }
    private val queueRestoreExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "AuraQueueRestore").apply { isDaemon = true }
    }
    @Volatile private var queueRestoreComplete = false
    private var queueRevision = 0
    @Volatile private var serviceDestroyed = false
    private var audioDeviceCallback: android.media.AudioDeviceCallback? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var radioPausedAtLiveEdge = false
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
        createTrackNotificationChannel()
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
                    // A paused live stream must jump back to the live edge on resume,
                    // rather than replaying an old buffered segment and stalling later.
                    if (radioPausedAtLiveEdge) {
                        radioPausedAtLiveEdge = false
                        exoPlayer.prepare()
                    }
                    if (!requestAudioFocus()) {
                        exoPlayer.pause()
                    }
                } else {
                    val currentUri = exoPlayer.currentMediaItem?.localConfiguration?.uri
                    val isNetworkStream = currentUri?.scheme.equals("http", true) ||
                        currentUri?.scheme.equals("https", true)
                    if (isNetworkStream && exoPlayer.mediaItemCount > 0) {
                        // Stop the old buffer completely. On Play, prepare() reconnects
                        // to the stream so live radio does not resume minutes behind.
                        radioPausedAtLiveEdge = true
                        exoPlayer.stop()
                    }
                    restoreVolumeAfterDucking()
                    abandonAudioFocus()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                radioPausedAtLiveEdge = false
                val uri = mediaItem?.localConfiguration?.uri ?: return
                val index = queueUris.indexOf(uri)
                if (index >= 0) queueIndex = index
                savePlaybackState()
                showTrackChangeNotification(mediaItem)
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
        // Android notification shade controls only: shuffle | previous | play/pause | next | close.
        val shuffleButton = CommandButton.Builder(CommandButton.ICON_SHUFFLE_ON)
            .setDisplayName("Teljes zenetár keverése")
            .setSessionCommand(SessionCommand(CMD_SHUFFLE_ALL, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_BACK_SECONDARY)
            .build()
        val previousButton = CommandButton.Builder(CommandButton.ICON_PREVIOUS)
            .setDisplayName("Előző")
            .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .setSlots(CommandButton.SLOT_BACK)
            .build()
        val playPauseButton = CommandButton.Builder(CommandButton.ICON_PLAY)
            .setDisplayName("Lejátszás / szünet")
            .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
            .setSlots(CommandButton.SLOT_CENTRAL)
            .build()
        val nextButton = CommandButton.Builder(CommandButton.ICON_NEXT)
            .setDisplayName("Következő")
            .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .setSlots(CommandButton.SLOT_FORWARD)
            .build()
        val closeButton = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setCustomIconResId(R.drawable.ic_notification_close)
            .setDisplayName("AURA bezárása")
            .setSessionCommand(SessionCommand(CMD_CLOSE_PLAYER, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_FORWARD_SECONDARY)
            .build()

        mediaSession = MediaSession.Builder(this, sessionPlayer)
            .setId("aura-hifi-session")
            .setSessionActivity(sessionActivity)
            .setCallback(sessionCallback)
            .setMediaButtonPreferences(
                listOf(shuffleButton, previousButton, playPauseButton, nextButton, closeButton)
            )
            .build()
    }


    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_SHUFFLE_ALL, Bundle.EMPTY))
                .add(SessionCommand(CMD_CLOSE_PLAYER, Bundle.EMPTY))
                .build()

            // Explicitly grant both player commands and the complete button layout
            // during connection. This is especially important for Media3's
            // notification controller, which backs Android's notification shade.
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(commands)
                .setAvailablePlayerCommands(session.player.availableCommands)
                .setMediaButtonPreferences(session.mediaButtonPreferences)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_SHUFFLE_ALL -> shuffleEntireLibrary()
                CMD_CLOSE_PLAYER -> closePlayback()
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    private fun shuffleEntireLibrary() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.READ_MEDIA_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) return
        if (Build.VERSION.SDK_INT < 33 &&
            checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) return

        val currentUri = player?.currentMediaItem?.localConfiguration?.uri
        val currentPosition = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
        shuffleExecutor.execute {
            val allUris = mutableListOf<Uri>()
            try {
                val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                contentResolver.query(
                    collection,
                    arrayOf(MediaStore.Audio.Media._ID),
                    MediaStore.Audio.Media.IS_MUSIC + " != 0",
                    null,
                    MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC"
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    while (cursor.moveToNext()) {
                        allUris.add(ContentUris.withAppendedId(collection, cursor.getLong(idColumn)))
                    }
                }
            } catch (_: Exception) {
                return@execute
            }
            if (allUris.isEmpty()) return@execute
            val ordered = mutableListOf<Uri>()
            currentUri?.takeIf { allUris.contains(it) }?.let { ordered.add(it) }
            ordered.addAll(allUris.filter { it != currentUri }.shuffled())
            val items = ordered.map { buildMediaItem(it) }
            saveHandler.post {
                if (serviceDestroyed || items.isEmpty()) return@post
                queueRevision++
                queueRestoreComplete = true
                queueUris = ordered
                queueIndex = 0
                queueShuffleMode = 2
                val exo = player ?: return@post
                exo.setMediaItems(items, 0, if (currentUri != null && ordered.firstOrNull() == currentUri) currentPosition else 0L)
                exo.prepare()
                exo.play()
                savePlaybackState()
                AuraMiniWidgetProvider.updateAll(this)
            }
        }
    }

    private fun closePlayback() {
        saveHandler.removeCallbacks(savePositionTask)
        player?.apply {
            pause()
            stop()
            clearMediaItems()
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(TRACK_NOTIFICATION_ID)
        saveHandler.post { stopSelf() }
    }

    private fun createTrackNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                TRACK_CHANNEL_ID,
                "AURA dalváltási értesítések",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Rövid értesítés az újonnan elinduló zeneszámokról"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun showTrackChangeNotification(item: MediaItem?) {
        if (item == null || AuraAppVisibility.isVisible) return
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) return

        val metadata = item.mediaMetadata
        val title = metadata.title?.toString()?.takeIf { it.isNotBlank() } ?: "Ismeretlen cím"
        val artist = metadata.artist?.toString()?.takeIf { it.isNotBlank() } ?: "AURA Hi-Fi"
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("aura_open_player", true)
        }
        val pending = PendingIntent.getActivity(
            this, 7135, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, TRACK_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(artist)
            .setSubText("AURA HI-FI  •  MOST SZÓL")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setPriority(Notification.PRIORITY_HIGH)
            .setTimeoutAfter(4200L)
            .build()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(TRACK_NOTIFICATION_ID, notification)
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
        val queueJson = saved.getString(KEY_QUEUE, "[]") ?: "[]"
        val savedIndex = saved.getInt(KEY_INDEX, 0)
        val savedRepeatMode = saved.getInt(KEY_REPEAT, 0)
        val savedShuffleMode = saved.getInt(KEY_SHUFFLE, 0)
        val position = saved.getLong(KEY_POSITION, 0L).coerceAtLeast(0L)
        val wasPlaying = saved.getBoolean(KEY_WAS_PLAYING, false)
        val resumeOnAppStart = getSharedPreferences("aurix_settings", Context.MODE_PRIVATE)
            .getBoolean("resume_on_app_start", true)
        val shouldResume = wasPlaying && resumeOnAppStart
        val restoreRevision = queueRevision
        queueRestoreComplete = false

        // URI permission checks and metadata extraction can block on external
        // document providers. Keep all of that work off Android's main thread.
        queueRestoreExecutor.execute {
            val array = try { JSONArray(queueJson) } catch (_: Exception) { JSONArray() }
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

            val restoredIndex = savedIndex.coerceIn(0, (restored.size - 1).coerceAtLeast(0))
            val restoredUri = restored.getOrNull(restoredIndex)
            val restoredMediaItem = restoredUri?.let { buildMediaItem(it) }

            saveHandler.post {
                if (serviceDestroyed) return@post

                // A user action may replace the queue while restoration is running.
                // In that case, never overwrite the newer queue with this old snapshot.
                if (queueRevision == restoreRevision) {
                    queueUris = restored
                    queueIndex = restoredIndex
                    queueRepeatMode = savedRepeatMode
                    queueShuffleMode = savedShuffleMode

                    val exo = player
                    if (restoredMediaItem != null && exo != null) {
                        exo.setMediaItem(restoredMediaItem)
                        exo.prepare()
                        if (position > 0L) exo.seekTo(position)
                        exo.playWhenReady = shouldResume
                    }
                }
                queueRestoreComplete = true
            }
        }
    }

    private fun savePlaybackState() {
        // Do not persist the temporary empty queue while async restoration is running.
        if (!queueRestoreComplete) return
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
        queueRevision++
        queueRestoreComplete = true
        queueUris = items.toList()
        queueIndex = index.coerceIn(0, (queueUris.size - 1).coerceAtLeast(0))
        queueRepeatMode = repeatMode
        queueShuffleMode = shuffleMode
        savePlaybackState()
    }

    override fun onDestroy() {
        serviceDestroyed = true
        queueRestoreExecutor.shutdownNow()
        shuffleExecutor.shutdownNow()
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
        private const val TRACK_CHANNEL_ID = "aura_track_changes"
        private const val TRACK_NOTIFICATION_ID = 7135
        private const val CMD_SHUFFLE_ALL = "com.aura.hifi.SHUFFLE_ALL"
        private const val CMD_CLOSE_PLAYER = "com.aura.hifi.CLOSE_PLAYER"
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

        fun isQueueRestoreComplete(): Boolean = instance?.queueRestoreComplete ?: true

        fun shuffleAllFromNotification() {
            instance?.shuffleEntireLibrary()
        }

        fun closeFromNotification() {
            instance?.closePlayback()
        }

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
