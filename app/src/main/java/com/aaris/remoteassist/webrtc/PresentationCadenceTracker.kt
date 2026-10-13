package com.aaris.remoteassist.webrtc

import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Actual controller-side EGL presentation cadence, not decoder/RTP cadence. */
data class PresentationCadenceWindow(
    val intervalMs: Int,
    val renderedFrames: Int,
    val maxGapMs: Int,
    val gapStdDevMs: Int = 0
) {
    init {
        require(intervalMs in 1..6_000)
        require(renderedFrames in 1..2_048)
        require(maxGapMs in 0..6_000)
        require(gapStdDevMs in 0..6_000)
    }

    val fps: Double
        get() = renderedFrames * 1000.0 / intervalMs

    fun compact(): String =
        "present=${fps.roundToInt()}fps maxGap=${maxGapMs}ms " +
            "gapStd=${gapStdDevMs}ms"
}

/**
 * Converts real EGL swaps into bounded half-second feedback windows. Shorter
 * windows surface a render hitch sooner than a one-second stats cycle, while
 * ReceiverVideoFeedback aggregates consecutive windows until the host consumes
 * them. That preserves a stable cadence estimate and the worst observed swap
 * gap without increasing libwebrtc getStats() polling or JNI churn.
 *
 * The tracker is synchronized because EglRenderer invokes its render listener
 * on a render thread while session lifecycle/reset may run on Android's main
 * thread.
 */
class PresentationCadenceTracker(
    private val minWindowMs: Long = 500L,
    private val maxGapMs: Long = 6_000L
) {
    init {
        require(minWindowMs in 500L..3_000L)
        require(maxGapMs in minWindowMs..6_000L)
    }

    private var windowStartMs = -1L
    private var lastFrameMs = -1L
    private var frames = 0
    private var largestGapMs = 0L
    private var gapSamples = 0
    private var meanGapMs = 0.0
    private var gapM2 = 0.0

    @Synchronized
    fun onPresented(nowMs: Long): PresentationCadenceWindow? {
        if (nowMs < 0L) return null

        if (
            windowStartMs < 0L ||
            lastFrameMs < 0L ||
            nowMs < lastFrameMs ||
            nowMs - lastFrameMs > maxGapMs
        ) {
            windowStartMs = nowMs
            lastFrameMs = nowMs
            // First EGL swap is the interval anchor, not an interval arrival.
            frames = 0
            largestGapMs = 0L
            resetGapVariance()
            return null
        }

        val gapMs = nowMs - lastFrameMs
        largestGapMs = maxOf(largestGapMs, gapMs)
        gapSamples += 1
        val delta = gapMs.toDouble() - meanGapMs
        meanGapMs += delta / gapSamples.toDouble()
        val deltaAfterMean = gapMs.toDouble() - meanGapMs
        gapM2 += delta * deltaAfterMean

        lastFrameMs = nowMs
        frames += 1

        val elapsed = nowMs - windowStartMs
        if (elapsed < minWindowMs) return null

        val sample = PresentationCadenceWindow(
            intervalMs = elapsed.coerceIn(1L, 6_000L).toInt(),
            renderedFrames = frames.coerceIn(1, 2_048),
            maxGapMs = largestGapMs.coerceIn(0L, 6_000L).toInt(),
            gapStdDevMs =
                sqrt(
                    (gapM2 / gapSamples.coerceAtLeast(1).toDouble())
                        .coerceAtLeast(0.0)
                )
                    .roundToInt()
                    .coerceIn(0, 6_000)
        )

        // The current frame closes the old window. The next frame starts the
        // new count, preventing one EGL swap from being counted twice.
        windowStartMs = nowMs
        frames = 0
        largestGapMs = 0L
        resetGapVariance()
        return sample
    }

    @Synchronized
    fun reset() {
        windowStartMs = -1L
        lastFrameMs = -1L
        frames = 0
        largestGapMs = 0L
        resetGapVariance()
    }

    private fun resetGapVariance() {
        gapSamples = 0
        meanGapMs = 0.0
        gapM2 = 0.0
    }
}
