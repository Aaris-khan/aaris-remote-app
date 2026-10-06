package com.aaris.remoteassist.capture

import kotlin.math.ceil

/**
 * Schedules the post-input freshness probe after roughly one local display tick.
 * This is intentionally a tiny policy helper, not a frame timer: natural
 * MediaProjection callbacks always win and cancel the synthetic pull.
 */
internal object InteractionRefreshTiming {
    fun oneDisplayTickMs(refreshRateHz: Float?): Long {
        val hz =
            refreshRateHz
                ?.takeIf { it.isFinite() && it in 24f..240f }
                ?: 60f

        return ceil(1_000.0 / hz.toDouble())
            .toLong()
            .coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
    }

    private const val MIN_DELAY_MS = 8L
    private const val MAX_DELAY_MS = 20L
}
