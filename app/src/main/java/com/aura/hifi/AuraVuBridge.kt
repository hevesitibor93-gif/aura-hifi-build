package com.aura.hifi

/**
 * Read-only bridge for secondary UI surfaces that display AURA's existing VU signal.
 * The audio processing and VU gain/delay configuration remain owned by MainActivity.
 */
internal object AuraVuBridge {
    @Volatile var left: Float = 0f
    @Volatile var right: Float = 0f
}
