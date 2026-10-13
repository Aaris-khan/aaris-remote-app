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

    /**
     * Compact, backward-compatible description of high-cadence stream rates
     * that at least one stable controller display mode can present without
     * fractional pulldown.
     *
     * Bits 0..29 correspond to 31..60 fps. <=30 fps remains the existing
     * freshness/congestion ladder and intentionally needs no mask bit.
     *
     * Android mode rates are nominal floats (59.94, 89.9, etc). Normalize them
     * to the same integer clock model already used by DisplayCadencePolicy
     * before testing exact multiples. A null result means capability is unknown;
     * a zero mask is a valid statement that no >30 fps integer cadence can be
     * presented evenly by the stable mode group.
     */
    fun highCadenceMask(
        activeModeRefreshRateHz: Float,
        seamlessAlternativeRefreshRatesHz: FloatArray?,
        sameResolutionRefreshRatesHz: FloatArray = floatArrayOf()
    ): Int? {
        val stableRates = ArrayList<Int>()

        fun add(value: Float) {
            if (!value.isFinite() || value !in MIN_REFRESH_HZ..MAX_REFRESH_HZ) {
                return
            }
            val normalized = kotlin.math.round(value).toInt()
            if (normalized in 1..MAX_REFRESH_HZ.toInt() &&
                normalized !in stableRates
            ) {
                stableRates += normalized
            }
        }

        add(activeModeRefreshRateHz)
        val stableGroup =
            seamlessAlternativeRefreshRatesHz
                ?: sameResolutionRefreshRatesHz
        stableGroup.forEach(::add)

        if (stableRates.isEmpty()) return null

        var mask = 0
        for (fps in HIGH_CADENCE_MIN_FPS..HIGH_CADENCE_MAX_FPS) {
            val compatible =
                stableRates.any { refresh ->
                    refresh >= fps && refresh % fps == 0
                }
            if (compatible) {
                mask = mask or bitForHighCadence(fps)
            }
        }
        return mask
    }

    fun supportsHighCadence(mask: Int?, fps: Int): Boolean {
        if (fps <= LOW_CADENCE_MAX_FPS) return true
        if (mask == null) return true
        if (fps !in HIGH_CADENCE_MIN_FPS..HIGH_CADENCE_MAX_FPS) {
            return false
        }
        return mask and bitForHighCadence(fps) != 0
    }

    private fun bitForHighCadence(fps: Int): Int =
        1 shl (fps - HIGH_CADENCE_MIN_FPS)

    private const val LOW_CADENCE_MAX_FPS = 30
    private const val HIGH_CADENCE_MIN_FPS = LOW_CADENCE_MAX_FPS + 1
    private const val HIGH_CADENCE_MAX_FPS = 60

    private const val MIN_REFRESH_HZ = 1f
    private const val MAX_REFRESH_HZ = 240f
}
