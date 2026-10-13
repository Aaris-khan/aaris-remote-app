package com.aaris.remoteassist.ui

import com.aaris.remoteassist.webrtc.PresentationCadenceWindow

/**
 * Keeps the controller's display scheduling stable for a latency-first remote
 * control stream.
 *
 * The primary renderer is intentionally "latest frame wins": decoded pixels are
 * handed to EglRenderer immediately, presentation timestamps are disabled, and a
 * stale pending frame may be overwritten. The Surface frame-rate vote must follow
 * the same model. It is a display-capacity hint, not a mirror of the variable RTP
 * delivery cadence.
 *
 * Android may change the physical display refresh rate, Choreographer timing and
 * buffer-release timing after Surface.setFrameRate(). Retuning that actuator to
 * measured 45/48/50/55-fps windows therefore creates a second pacing loop around
 * an otherwise render-ASAP pipeline. Even a perfectly stable network cadence can
 * cause a seamless mode transition, then another transition when WebRTC recovers
 * or the next interaction starts.
 *
 * Keep one high-capacity vote for the lifetime of the current stream instead:
 * - the first interaction installs it immediately;
 * - passive streaming may install it after consecutive clean presentation proof;
 * - congestion/thermal/network cadence never retunes it;
 * - reset/track replacement clears it.
 *
 * This matches the latency-first renderer contract without adding a playback
 * reservoir, Choreographer dependency, frame copy or second media clock.
 */
internal class SurfaceFrameRateHintGovernor(
    private val initialWindows: Int = DEFAULT_INITIAL_WINDOWS
) {
    private var currentHint = 0
    private var cleanWindows = 0

    init {
        require(initialWindows >= 1)
    }

    /**
     * Passive playback can establish the same stable display-capacity vote once
     * actual EGL swaps prove that the Surface is alive and presenting cleanly.
     *
     * Once a vote exists, presentation cadence remains health telemetry only.
     * It must never become a second actuator that follows sender/network jitter.
     */
    fun observe(window: PresentationCadenceWindow, nowMs: Long): Int? {
        if (nowMs < 0 || currentHint != 0) return null

        val fps = window.fps
        val stableGapBudgetMs =
            if (fps.isFinite() && fps > 0.0) {
                1_000.0 / fps * MAX_STABLE_GAP_FRAME_MULTIPLIER
            } else {
                0.0
            }
        val clean =
            window.renderedFrames >= MIN_PRESENTED_FRAMES &&
                fps.isFinite() &&
                fps in MIN_MEASURED_FPS..MAX_MEASURED_FPS &&
                window.maxGapMs <= stableGapBudgetMs

        if (!clean) {
            cleanWindows = 0
            return null
        }

        cleanWindows += 1
        if (cleanWindows < initialWindows) return null

        currentHint = PREFERRED_SURFACE_FPS
        cleanWindows = 0
        return currentHint
    }

    /**
     * Interactive control should not wait for cadence measurement. Install the
     * stable stream-lifetime vote immediately and leave actual frame admission to
     * WebRTC + EglRenderer.
     */
    fun beginInteraction(
        maxFps: Int = PREFERRED_SURFACE_FPS
    ): Int? {
        if (maxFps <= 0 || currentHint != 0) return null
        currentHint = minOf(maxFps, PREFERRED_SURFACE_FPS)
        cleanWindows = 0
        return currentHint
    }

    fun currentHintFps(): Int = currentHint

    /** Clear the Surface preference only when the stream resets/ends. */
    fun reset(): Boolean {
        val hadHint = currentHint > 0
        currentHint = 0
        cleanWindows = 0
        return hadHint
    }

    private companion object {
        const val PREFERRED_SURFACE_FPS = 60
        const val DEFAULT_INITIAL_WINDOWS = 3
        const val MIN_PRESENTED_FRAMES = 5
        const val MIN_MEASURED_FPS = 10.0
        const val MAX_MEASURED_FPS = 72.0

        /*
         * Passive bootstrap still requires clean presentation evidence. 1.9x is
         * intentionally below the ~2.0x signature of one dropped frame while
         * admitting ordinary display pulldown.
         */
        const val MAX_STABLE_GAP_FRAME_MULTIPLIER = 1.9
    }
}
