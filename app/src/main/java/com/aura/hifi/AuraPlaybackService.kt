package com.aura.hifi

import android.content.Context
import android.net.Uri
import android.media.MediaMetadataRetriever
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

        val exoPlayer = ExoPlayer.Builder(this, renderersFactory).build()
        player = exoPlayer

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

        mediaSession = MediaSession.Builder(this, sessionPlayer)
            .setId("aura-hifi-session")
            .build()
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

    private fun playQueueIndex(index: Int) {
        val exo = player ?: return
        val uri = queueUris.getOrNull(index) ?: return
        queueIndex = index
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
        exo.setMediaItem(MediaItem.Builder().setUri(uri).setMediaMetadata(metadata).build())
        exo.prepare()
        exo.play()
    }

    private fun updateQueue(items: List<Uri>, index: Int, repeatMode: Int, shuffleMode: Int) {
        queueUris = items.toList()
        queueIndex = index.coerceIn(0, (queueUris.size - 1).coerceAtLeast(0))
        queueRepeatMode = repeatMode
        queueShuffleMode = shuffleMode
    }

    override fun onDestroy() {
        instance = null
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var instance: AuraPlaybackService? = null

        fun syncQueue(items: List<Uri>, index: Int, repeatMode: Int, shuffleMode: Int) {
            instance?.updateQueue(items, index, repeatMode, shuffleMode)
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
