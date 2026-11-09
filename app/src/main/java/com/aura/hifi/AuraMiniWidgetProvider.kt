package com.aura.hifi

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class AuraMiniWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { updateWidget(context, manager, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_PLAY_PAUSE -> AuraPlaybackService.togglePlaybackFromWidget()
            ACTION_PREVIOUS -> AuraPlaybackService.previousFromWidget()
            ACTION_NEXT -> AuraPlaybackService.nextFromWidget()
        }
        updateAll(context)
    }

    companion object {
        private const val ACTION_PLAY_PAUSE = "com.aura.hifi.widget.PLAY_PAUSE"
        private const val ACTION_PREVIOUS = "com.aura.hifi.widget.PREVIOUS"
        private const val ACTION_NEXT = "com.aura.hifi.widget.NEXT"

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, AuraMiniWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { updateWidget(context, manager, it) }
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.aura_mini_widget)
            val active = AuraPlaybackService.player
            val metadata = active?.currentMediaMetadata
            views.setTextViewText(R.id.widget_title, metadata?.title?.toString()?.takeIf { it.isNotBlank() } ?: "AURA Hi-Fi")
            views.setTextViewText(R.id.widget_artist, metadata?.artist?.toString()?.takeIf { it.isNotBlank() } ?: "Válassz zenét")
            views.setTextViewText(R.id.widget_play, if (active?.isPlaying == true) "Ⅱ" else "▶")

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("aura_open_player", true)
            }
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(context, appWidgetId * 10, openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )
            views.setOnClickPendingIntent(R.id.widget_previous, actionIntent(context, appWidgetId * 10 + 1, ACTION_PREVIOUS))
            views.setOnClickPendingIntent(R.id.widget_play, actionIntent(context, appWidgetId * 10 + 2, ACTION_PLAY_PAUSE))
            views.setOnClickPendingIntent(R.id.widget_next, actionIntent(context, appWidgetId * 10 + 3, ACTION_NEXT))
            manager.updateAppWidget(appWidgetId, views)
        }

        private fun actionIntent(context: Context, requestCode: Int, action: String): PendingIntent {
            val intent = Intent(context, AuraMiniWidgetProvider::class.java).setAction(action)
            return PendingIntent.getBroadcast(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}
