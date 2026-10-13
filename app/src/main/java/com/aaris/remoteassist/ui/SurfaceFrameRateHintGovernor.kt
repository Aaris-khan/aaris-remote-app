package com.aaris.remoteassist.ui

import com.aaris.remoteassist.webrtc.PresentationCadenceWindow
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Keeps the controller's display scheduling stable for an interactive stream.
 *
 * The observed VIDEO cadence is not the display's sustainable refresh rate:
 * native WebRTC may temporarily deliver 15/24/30 fps due to network or codec
 * pressure, then recover on the next gesture. Low-cadence pressure must never
 * vote the display down and steal presentation capacity from that recovery.
 *
 * High cadence is different. A stable 45/48/50 fps stream presented on a
 * Surface that keeps advertising 60 fps can manufacture deterministic display
 * judder even when capture, encoder, network and decoder are all healthy.
 * Therefore the first gesture still installs the low-latency 60 fps bootstrap,
 * but consecutive clean EGL windows may correct that hint to the ACTUAL stable
 * high cadence. The vote is based on an average across multiple windows, not a
 * single noisy sample, and low-cadence congestion never changes it.
 *
 * Android treats setFrameRate() as a display-scheduler hint, not a producer
 * throttle. DEFAULT compatibility lets SurfaceFlinger choose a compatible
 * physical mode (for example 90 Hz for a stable 45 fps stream) without adding
 * a frame queue in Aaris.
 */
internal class SurfaceFrameRateHintGovernor(
    private val initialWindows: Int = DEFAULT_INITIAL_WINDOWS
) {
    private var currentHint = 0
    private var cleanWindows = 0
    private var cleanFpsSum = 0.0
    private var lastHintChangedAtMs = Long.MIN_VALUE / 2

    init {
        require(initialWindows >= 1)
    }

    /** Passive streaming can establish the one stable display vote. */
    fun observe(window: PresentationCadenceWindow, nowMs: Long): Int? {
        if (nowMs < 0) return null

        val fps = window.fps
        val clean =
            window.renderedFrames >= MIN_PRESENTED_FRAMES &&
                fps.isFinite() &&
                fps in MIN_MEASURED_FPS..MAX_MEASURED_FPS &&
                window.maxGapMs <= max(
                    MAX_STABLE_GAP_MS,
                    1_000.0 / fps * MAX_STABLE_GAP_FRAME_MULTIPLIER
                )

        if (!clean) {
            resetCandidate()
            return null
        }

        cleanWindows += 1
        cleanFpsSum += fps
        if (cleanWindows < initialWindows) return null

        val measuredFps =
            (cleanFpsSum / cleanWindows.toDouble())
                .roundToInt()
                .coerceIn(1, PREFERRED_SURFACE_FPS)
        resetCandidate()

        /*
         * <=30 fps is commonly a temporary congestion/thermal recovery rung.
         * Keep display capacity at 60 rather than following that pressure down.
         * Above 30, use the proven cadence itself so 45/48/50-class streams do
         * not get forced through a 60-Hz presentation pattern.
         */
        val nextHint =
            if (measuredFps <= LOW_CADENCE_CEILING_FPS) {
                PREFERRED_SURFACE_FPS
            } else {
                measuredFps
            }

        if (nextHint == currentHint) return null
        if (
            currentHint != 0 &&
            nowMs - lastHintChangedAtMs < MIN_HINT_CHANGE_INTERVAL_MS
        ) {
            return null
        }

        currentHint = nextHint
        lastHintChangedAtMs = nowMs
        return currentHint
    }

    /**
     * The first gesture may precede any measured presentation windows.
     * Bootstrap at a display-class ceiling, then let clean EGL cadence correct
     * the vote if the real high-cadence operating point is not 60 fps.
     */
    fun beginInteraction(
        maxFps: Int = PREFERRED_SURFACE_FPS,
        nowMs: Long = -1L
    ): Int? {
        if (maxFps <= 0 || currentHint != 0) return null
        currentHint = minOf(maxFps, PREFERRED_SURFACE_FPS)
        resetCandidate()
        if (nowMs >= 0L) {
            lastHintChangedAtMs = nowMs
        }
        return currentHint
    }

    fun currentHintFps(): Int = currentHint

    /** Clear the Surface preference only when the stream resets/ends. */
    fun reset(): Boolean {
        val hadHint = currentHint > 0
        currentHint = 0
        resetCandidate()
        lastHintChangedAtMs = Long.MIN_VALUE / 2
        return hadHint
    }

    private fun resetCandidate() {
        cleanWindows = 0
        cleanFpsSum = 0.0
    }

    private companion object {
        const val PREFERRED_SURFACE_FPS = 60
        const val LOW_CADENCE_CEILING_FPS = 30
        const val DEFAULT_INITIAL_WINDOWS = 3
        const val MIN_HINT_CHANGE_INTERVAL_MS = 1_200L
        const val MIN_PRESENTED_FRAMES = 5
        const val MIN_MEASURED_FPS = 10.0
        const val MAX_MEASURED_FPS = 72.0
        const val MAX_STABLE_GAP_MS = 72.0
        const val MAX_STABLE_GAP_FRAME_MULTIPLIER = 2.75
    }
}
