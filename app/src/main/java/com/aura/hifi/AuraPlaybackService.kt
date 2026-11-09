package com.aura.hifi

import android.content.Context
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

    override fun onCreate() {
        super.onCreate()
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
                onPreviousRequested?.invoke() ?: super.seekToPreviousMediaItem()
            }

            override fun seekToNextMediaItem() {
                onNextRequested?.invoke() ?: super.seekToNextMediaItem()
            }
        }

        mediaSession = MediaSession.Builder(this, sessionPlayer)
            .setId("aura-hifi-session")
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
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
