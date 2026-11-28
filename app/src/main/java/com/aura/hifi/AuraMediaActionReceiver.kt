package com.aura.hifi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AuraMediaActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AuraOriginIsland.ACTION_SHUFFLE ->
                AuraPlaybackService.shuffleAllFromNotification()
            AuraOriginIsland.ACTION_PREVIOUS ->
                AuraPlaybackService.previousFromWidget()
            AuraOriginIsland.ACTION_PLAY_PAUSE ->
                AuraPlaybackService.togglePlaybackFromWidget()
            AuraOriginIsland.ACTION_NEXT ->
                AuraPlaybackService.nextFromWidget()
            AuraOriginIsland.ACTION_CLOSE ->
                AuraPlaybackService.closeFromNotification()
        }
    }
}
