package com.aaris.remoteassist.webrtc

import com.aaris.remoteassist.capture.CaptureProfile

/**
 * Keeps compatibility-video GPU readback from monopolizing the primary RTP
 * capture callback when a device's texture-to-I420 conversion is expensive.
 *
 * This is deliberately a tiny controller for the fallback path only. It never
 * changes MediaProjection, native WebRTC adaptation, the wire format or FPS.
 * Sustained slow copies first reduce recovery cadence and may trim pixels only
 * down to the same 360px-class readable short-edge contract as primary video.
 * Recovery is slow and requires fresh, consecutive healthy samples to avoid
 * quality/cadence oscillation.
 */
internal class FallbackReadbackBudget {
    @Volatile private var pressureLevel = 0
    private var overloadVotes = 0
    private var healthyVotes = 0
    private var lastSampleAtMs = -1L
    private var lastTransitionAtMs = Long.MIN_VALUE / 2

    /**
     * Returns an even long-side limit for CpuFrameCopy.capture().
     *
     * Recovery is intentionally lower priority than primary RTP, but it must not
     * become unreadably soft under pressure. Preserve the same minimum readable
     * short edge used by CaptureProfile. If the source itself is narrower than
     * that floor, never upscale it.
     */
    fun maxSidePx(
        baseSidePx: Int,
        sourceWidthPx: Int,
        sourceHeightPx: Int
    ): Int {
        val base = baseSidePx.coerceAtLeast(2)
        val percent = when (pressureLevel) {
            1 -> 75L
            2 -> 50L
            else -> 100L
        }
        val pressureCandidate =
            ((base.toLong() * percent / 100L).toInt() / 2 * 2)
                .coerceAtLeast(2)

        val sourceLong =
            maxOf(sourceWidthPx, sourceHeightPx)
                .coerceAtLeast(2)
        val sourceShort =
            minOf(sourceWidthPx, sourceHeightPx)
                .coerceAtLeast(2)
        val readableShort =
            minOf(
                CaptureProfile.MIN_READABLE_CAPTURE_SHORT_SIDE,
                sourceShort
            )
        val requiredLong =
            (
                (
                    readableShort.toLong() * sourceLong.toLong() +
                        sourceShort.toLong() - 1L
                ) / sourceShort.toLong()
            )
                .toInt()
                .let { if (it % 2 == 0) it else it + 1 }
                .coerceAtLeast(2)

        return maxOf(
            pressureCandidate,
            requiredLong
        )
    }

    /**
     * Expensive GPU readback must spend temporal quality before more pixels.
     * The caller may already be slower because of network backpressure; never
     * accelerate it merely because the local copy path is healthy.
     */
    fun minimumIntervalMs(baseIntervalMs: Long): Long {
        val base = baseIntervalMs.coerceAtLeast(1L)
        val readbackFloorMs = when (pressureLevel) {
            1 -> PRESSURE_LEVEL_1_MIN_INTERVAL_MS
            2 -> PRESSURE_LEVEL_2_MIN_INTERVAL_MS
            else -> 0L
        }
        return maxOf(base, readbackFloorMs)
    }

    /**
     * Observe only successful copy operations. Return the new pressure tier
     * on a transition, or null when the image envelope remains unchanged.
     * Sampling gaps cannot be accumulated into false consecutive evidence.
     */
    @Synchronized
    fun observe(copyCostMs: Long, nowMs: Long): Int? {
        if (copyCostMs < 0L || nowMs < 0L) return null
        if (lastSampleAtMs >= 0L &&
            nowMs - lastSampleAtMs !in 0L..MAX_EVIDENCE_GAP_MS
        ) {
            overloadVotes = 0
            healthyVotes = 0
        }
        lastSampleAtMs = nowMs

        when {
            copyCostMs >= OVERLOAD_COPY_MS -> {
                overloadVotes = (overloadVotes + 1).coerceAtMost(OVERLOAD_VOTES)
                healthyVotes = 0
                if (overloadVotes >= OVERLOAD_VOTES &&
                    pressureLevel < MAX_LEVEL &&
                    nowMs - lastTransitionAtMs >= DOWNSHIFT_HOLD_MS
                ) {
                    pressureLevel++
                    overloadVotes = 0
                    lastTransitionAtMs = nowMs
                    return pressureLevel
                }
            }
            copyCostMs <= HEALTHY_COPY_MS -> {
                healthyVotes = (healthyVotes + 1).coerceAtMost(RECOVERY_VOTES)
                overloadVotes = 0
                if (healthyVotes >= RECOVERY_VOTES &&
                    pressureLevel > 0 &&
                    nowMs - lastTransitionAtMs >= RECOVERY_HOLD_MS
                ) {
                    pressureLevel--
                    healthyVotes = 0
                    lastTransitionAtMs = nowMs
                    return pressureLevel
                }
            }
            else -> {
                overloadVotes = 0
                healthyVotes = 0
            }
        }
        return null
    }

    @Synchronized
    fun reset() {
        pressureLevel = 0
        overloadVotes = 0
        healthyVotes = 0
        lastSampleAtMs = -1L
        lastTransitionAtMs = Long.MIN_VALUE / 2
    }

    private companion object {
        const val OVERLOAD_COPY_MS = 30L
        const val HEALTHY_COPY_MS = 16L
        const val OVERLOAD_VOTES = 2
        const val RECOVERY_VOTES = 10
        const val MAX_LEVEL = 2
        const val MAX_EVIDENCE_GAP_MS = 2_000L
        const val DOWNSHIFT_HOLD_MS = 2_000L
        const val RECOVERY_HOLD_MS = 15_000L
        const val PRESSURE_LEVEL_1_MIN_INTERVAL_MS = 120L
        const val PRESSURE_LEVEL_2_MIN_INTERVAL_MS = 180L
    }
}
