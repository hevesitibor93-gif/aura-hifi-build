package com.aura.hifi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.session.MediaSession
import android.os.Build
import android.os.Bundle

/**
 * Best-effort native Vivo Origin Island publisher.
 *
 * Uses the documented-by-reverse-engineering SuperX notification payload.
 * It never spoofs the AURA package name and never includes album artwork.
 * On unsupported/blocked Vivo firmware, Android simply treats this as a normal
 * ongoing media notification.
 */
object AuraOriginIsland {
    const val ACTION_SHUFFLE = "com.aura.hifi.action.SHUFFLE"
    const val ACTION_PREVIOUS = "com.aura.hifi.action.PREVIOUS"
    const val ACTION_PLAY_PAUSE = "com.aura.hifi.action.PLAY_PAUSE"
    const val ACTION_NEXT = "com.aura.hifi.action.NEXT"
    const val ACTION_CLOSE = "com.aura.hifi.action.CLOSE"

    private const val CHANNEL_ID = "aura_origin_island_v1"
    private const val CHANNEL_NAME = "AURA lejátszójelzés"
    private const val NOTIFICATION_TAG = "VIVO_SUPERX_TAG"
    private const val NOTIFICATION_ID = 7136
    private const val SCENE = "NAVIGATION"
    private val AURA_WAVE_COLORS = arrayListOf("#00D9D0", "#36F5E8", "#00B8C9")
    private const val DISPLAYS = 65808 // lockscreen + status bar + AOD; keep shade popup separate

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "AURA Hi-Fi jelzés a Vivo Origin Islandhez"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun update(
        context: Context,
        isPlaying: Boolean,
        trackTitle: String,
        artist: String,
        sessionToken: MediaSession.Token?
    ) {
        if (!isVivoDevice()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) return

        runCatching {
            ensureChannel(context)
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            registerScene(manager, context.packageName)

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("aura_open_player", true)
            }
            val clickIntent = PendingIntent.getActivity(
                context,
                7136,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Reuse the same app icon instance in every Vivo bundle. No track artwork is read.
            val appIcon = Icon.createWithResource(context, R.mipmap.ic_launcher)
            val cleanTitle = trackTitle.trim().ifBlank { "Ismeretlen cím" }.take(48)
            val cleanArtist = artist.trim().ifBlank { "AURA Hi-Fi" }.take(48)
            val extras = buildSuperXExtras(appIcon, clickIntent, isPlaying, cleanTitle, cleanArtist)

            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(cleanTitle)
                .setContentText(cleanArtist)
                .setContentIntent(clickIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setAutoCancel(false)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setPriority(Notification.PRIORITY_HIGH)
                .addExtras(extras)

            // Add real notification actions: attaching a MediaSession token alone
            // does not populate actions on a notification built outside Media3.
            builder
                .addAction(mediaAction(context, android.R.drawable.ic_menu_sort_by_size,
                    "Keverés", ACTION_SHUFFLE, 7137))
                .addAction(mediaAction(context, android.R.drawable.ic_media_previous,
                    "Előző", ACTION_PREVIOUS, 7138))
                .addAction(mediaAction(context,
                    if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (isPlaying) "Szünet" else "Lejátszás", ACTION_PLAY_PAUSE, 7139))
                .addAction(mediaAction(context, android.R.drawable.ic_media_next,
                    "Következő", ACTION_NEXT, 7140))
                .addAction(mediaAction(context, R.drawable.ic_notification_close,
                    "Bezárás", ACTION_CLOSE, 7141))

            if (sessionToken != null) {
                builder.setStyle(
                    Notification.MediaStyle()
                        .setMediaSession(sessionToken)
                        .setShowActionsInCompactView(1, 2, 3)
                )
            }
            manager.notify(NOTIFICATION_TAG, NOTIFICATION_ID, builder.build())
        }
    }

    private fun mediaAction(
        context: Context,
        iconRes: Int,
        title: String,
        action: String,
        requestCode: Int
    ): Notification.Action {
        val intent = Intent(context, AuraMediaActionReceiver::class.java).setAction(action)
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Action.Builder(
            Icon.createWithResource(context, iconRes),
            title,
            pending
        ).build()
    }

    fun cancel(context: Context) {
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val end = Bundle().apply {
                putInt("notification.superx.operation", 2)
                putBoolean("notification.superx.showNotify", false)
                putString("notification.superx.scene", SCENE)
                putInt("notification.superx.changedRecord", Int.MAX_VALUE)
            }
            val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }
                .setSmallIcon(R.mipmap.ic_launcher)
                .addExtras(end)
                .build()
            manager.notify(NOTIFICATION_TAG, NOTIFICATION_ID, notification)
            manager.cancel(NOTIFICATION_TAG, NOTIFICATION_ID)
        }
    }

    private fun isVivoDevice(): Boolean {
        val brand = Build.BRAND.orEmpty()
        val manufacturer = Build.MANUFACTURER.orEmpty()
        return brand.contains("vivo", true) ||
            brand.contains("iqoo", true) ||
            manufacturer.contains("vivo", true)
    }

    private fun registerScene(manager: NotificationManager, packageName: String) {
        runCatching {
            val method = NotificationManager::class.java.getMethod(
                "setSuperXInfosSceneList",
                List::class.java,
                List::class.java,
                List::class.java,
                List::class.java
            )
            method.invoke(
                manager,
                arrayListOf(SCENE),
                arrayListOf("true"),
                arrayListOf(packageName),
                arrayListOf("true")
            )
        }
    }

    private fun buildSuperXExtras(
        icon: Icon,
        clickIntent: PendingIntent,
        isPlaying: Boolean,
        trackTitle: String,
        artist: String
    ): Bundle {
        val root = Bundle().apply {
            putInt("notification.superx.operation", 0)
            putBoolean("notification.superx.showNotify", true)
            putInt("notification.superx.template", 4)
            putString("notification.superx.scene", SCENE)
            putInt("notification.superx.changedRecord", 0)
            putInt("notification.superx.displays", DISPLAYS)
            putParcelable("notification.superx.clickResp", clickIntent)
        }

        root.putBundle("notification.superx.baseInfos", Bundle().apply {
            putParcelable("notification.superx.baseInfos.icon", icon)
            putCharSequence("notification.superx.baseInfos.title", trackTitle)
            putCharSequence(
                "notification.superx.baseInfos.content",
                if (isPlaying) artist else "Szünet"
            )
            putInt("notification.superx.baseInfos.subInfo", 0)
        })

        root.putBundle("notification.superx.infos", Bundle().apply {
            putString("notification.superx.infos.describe", trackTitle)
            putString(
                "notification.superx.infos.coreInfo",
                artist
            )
            putParcelable("notification.superx.infos.image", icon)
            putParcelable("notification.superx.infos.imageClickResp", clickIntent)
        })

        root.putBundle("notification.superx.shortInfos", Bundle().apply {
            putParcelable("notification.superx.shortInfos.image", icon)
            putString("notification.superx.shortInfos.describeShort", trackTitle)
            putString(
                "notification.superx.shortInfos.coreInfoShort",
                if (isPlaying) artist else "Szünet"
            )
            putParcelable("notification.superx.shortInfos.imageClickResp", clickIntent)
        })

        root.putBundle("notification.superx.capsule", Bundle().apply {
            putInt("notification.superx.capsule.state", 1)
            putParcelable("notification.superx.capsule.icon", icon)
            putString("notification.superx.capsule.content", "AURA")
        })

        // Vivo expects the island payload under island.superx.infos.
        root.putBundle("island.superx.infos", Bundle().apply {
            putInt("island.superx.leftTemplate", 3)
            putInt("island.superx.rightTemplate", 1)
            putBoolean("island.superx.forceShow", true)
            putBoolean("island.superx.showBarWhenCard", false)
            putInt("island.superx.islandClick", 0)
            putParcelable("island.superx.clickResp", clickIntent)

            putBundle("island.superx.leftInfo", Bundle().apply {
                putParcelable("island.superx.leftInfo.icon", icon)
                putCharSequenceArrayList(
                    "island.superx.leftInfo.doublelineText",
                    arrayListOf(trackTitle, artist)
                )
            })

            putBundle("island.superx.rightInfo", Bundle().apply {
                putInt("island.superx.rightInfo.waveState", if (isPlaying) 1 else 0)
                putStringArrayList(
                    "island.superx.rightInfo.waveColor",
                    ArrayList(AURA_WAVE_COLORS)
                )
            })
        })

        return root
    }
}
