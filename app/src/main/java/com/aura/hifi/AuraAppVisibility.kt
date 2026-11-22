package com.aura.hifi

/** Shared foreground state used by background-only track notifications. */
object AuraAppVisibility {
    @Volatile
    var isVisible: Boolean = false
}
