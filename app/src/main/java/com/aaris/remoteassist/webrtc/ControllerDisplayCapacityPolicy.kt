package com.aaris.remoteassist.webrtc

/**
 * Resolves the controller display's stable presentation capacity without feeding
 * Aaris' own Surface.setFrameRate() actuator back into the sender.
 *
 * On Android 12+, Display.Mode.alternativeRefreshRates is the platform's
 * authoritative seamless-switch equivalence class. The relation is symmetric
 * and transitive, so its maximum remains stable when SurfaceFlinger moves among
 * those modes in response to our frame-rate hint.
 *
 * Older Android versions do not expose that group. Their best available
 * approximation is the maximum refresh rate at the active physical resolution.
 */
internal object ControllerDisplayCapacityPolicy {
    fun resolve(
        activeModeRefreshRateHz: Float,
        seamlessAlternativeRefreshRatesHz: FloatArray?,
        sameResolutionRefreshRatesHz: FloatArray = floatArrayOf()
    ): Float? {
        var best: Float? = null

        fun consider(value: Float) {
            if (!value.isFinite() || value !in MIN_REFRESH_HZ..MAX_REFRESH_HZ) {
                return
            }
            best = best?.let { maxOf(it, value) } ?: value
        }

        consider(activeModeRefreshRateHz)

        val stableGroup =
            seamlessAlternativeRefreshRatesHz
                ?: sameResolutionRefreshRatesHz
        stableGroup.forEach(::consider)

        return best
    }

    private const val MIN_REFRESH_HZ = 1f
    private const val MAX_REFRESH_HZ = 240f
}
