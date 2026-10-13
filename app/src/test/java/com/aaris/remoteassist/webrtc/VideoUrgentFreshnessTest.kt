package com.aaris.remoteassist.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoUrgentFreshnessTest {
    private fun outbound(queueMs: Double): VideoHealthSnapshot =
        VideoHealthSnapshot(
            direction = "outbound",
            codec = "video/H264",
            bytes = 1_000_000,
            frames = 60,
            framesSecondary = 60,
            packets = 180,
            packetsLost = 0,
            frameWidth = 1080,
            frameHeight = 2400,
            qualityLimitationReason = "none",
            roundTripTimeMs = 40,
            packetLossRatio = 0.0,
            recent = VideoHealthWindow(
                intervalMs = 1_000,
                frames = 60,
                processingMs = 5.0,
                jitterMs = null,
                dropped = null,
                freezes = null,
                sendQueueMs = queueMs
            )
        )

    private fun receiver(jitterMs: Double = 20.0): VideoHealthWindow =
        VideoHealthWindow(
            intervalMs = 1_000,
            frames = 60,
            processingMs = 5.0,
            jitterMs = jitterMs,
            dropped = 0,
            freezes = 0
        )

    private fun measuredHighCadence(): VideoCadenceGovernor =
        VideoCadenceGovernor(60).apply {
            assertFalse(observe(outbound(queueMs = 0.0), null, 60, 0L))
        }

    @Test
    fun clearlyStaleButNonSevereQueueGetsOneFastSoftStep() {
        val governor = VideoCadenceGovernor(60)

        assertTrue(governor.observe(outbound(queueMs = 120.0), receiver(), 60, 2_000))
        assertEquals(58, governor.cap)
    }

    @Test
    fun isolatedOrdinaryFullCadenceQueueBlipDoesNotDownshift() {
        val governor = VideoCadenceGovernor(60)

        assertFalse(governor.observe(outbound(queueMs = 45.0), receiver(), 60, 1_000))
        assertEquals(60, governor.cap)

        assertFalse(governor.observe(outbound(queueMs = 0.0), receiver(), 60, 1_500))
        assertEquals(60, governor.cap)
    }

    @Test
    fun sustainedOrdinaryFullCadenceQueueGetsFineRelief() {
        val governor = VideoCadenceGovernor(60)

        assertFalse(governor.observe(outbound(queueMs = 45.0), receiver(), 60, 1_000))
        assertTrue(governor.observe(outbound(queueMs = 45.0), receiver(), 60, 1_500))
        assertEquals(58, governor.cap)
    }

    @Test
    fun smallBweMissDoesNotHalveKnownSixtyHzSource() {
        val governor = VideoCadenceGovernor(60)

        assertFalse(
            governor.observeClarityBandwidthCeiling(
                ceilingFps = 59,
                requestedFps = 60,
                nowMs = 500,
                sourceRefreshRateHz = 60.0
            )
        )
        assertFalse(
            governor.observeClarityBandwidthCeiling(
                ceilingFps = 59,
                requestedFps = 60,
                nowMs = 1_000,
                sourceRefreshRateHz = 60.0
            )
        )
        assertEquals(60, governor.cap)
    }

    @Test
    fun provenThirtyFpsBweCeilingCanUsePhaseStableRelief() {
        val governor = VideoCadenceGovernor(60)

        assertFalse(
            governor.observeClarityBandwidthCeiling(
                ceilingFps = 30,
                requestedFps = 60,
                nowMs = 500,
                sourceRefreshRateHz = 60.0
            )
        )
        assertTrue(
            governor.observeClarityBandwidthCeiling(
                ceilingFps = 30,
                requestedFps = 60,
                nowMs = 1_000,
                sourceRefreshRateHz = 60.0
            )
        )
        assertEquals(30, governor.cap)
    }

    @Test
    fun lowerCadenceOrdinaryQueueStillRequiresSustainedPressure() {
        val governor = VideoCadenceGovernor(30)

        assertFalse(governor.observe(outbound(queueMs = 80.0), receiver(), 30, 1_000))
        assertTrue(governor.observe(outbound(queueMs = 80.0), receiver(), 30, 2_000))
        assertEquals(24, governor.cap)
    }

    @Test
    fun oneModerateHalfSecondGlassHitchIsTolerated() {
        val governor = measuredHighCadence()

        assertFalse(
            governor.observeFastPresentation(
                intervalMs = 500,
                renderedFrames = 22,
                maxGapMs = 80,
                requestedFps = 60,
                interactionActive = true,
                nowMs = 500
            )
        )
        assertEquals(60, governor.cap)
    }

    @Test
    fun isolatedMissedVsyncWindowDoesNotDownshiftHealthySixtyFps() {
        val governor = measuredHighCadence()

        assertFalse(
            governor.observeFastPresentation(
                intervalMs = 500,
                renderedFrames = 30,
                maxGapMs = 34,
                requestedFps = 60,
                interactionActive = true,
                nowMs = 500
            )
        )
        assertFalse(
            governor.observeFastPresentation(
                intervalMs = 500,
                renderedFrames = 30,
                maxGapMs = 20,
                requestedFps = 60,
                interactionActive = true,
                nowMs = 1_000
            )
        )
        assertEquals(60, governor.cap)
    }

    @Test
    fun recurringPacingOnlyMissedVsyncDoesNotManufactureSourceJudder() {
        val governor = measuredHighCadence()

        assertFalse(
            governor.observeFastPresentation(
                intervalMs = 500,
                renderedFrames = 30,
                maxGapMs = 34,
                requestedFps = 60,
                interactionActive = true,
                nowMs = 500,
                sourceRefreshRateHz = 60.0
            )
        )
        assertFalse(
            governor.observeFastPresentation(
                intervalMs = 500,
                renderedFrames = 30,
                maxGapMs = 34,
                requestedFps = 60,
                interactionActive = true,
                nowMs = 1_000,
                sourceRefreshRateHz = 60.0
            )
        )
        // All 30 expected frames arrived in each half-second window. A source
        // downshift would convert occasional scheduling jitter into a repeating
        // cadence defect, so the fast path keeps the healthy 60 fps envelope.
        assertEquals(60, governor.cap)
    }

    @Test
    fun softDeliveryDeficitWaitsForCorroboratedTelemetryWhenNoFineHarmonicExists() {
        val governor = measuredHighCadence()

        repeat(2) { index ->
            assertFalse(
                governor.observeFastPresentation(
                    intervalMs = 500,
                    renderedFrames = 28,
                    maxGapMs = 34,
                    requestedFps = 60,
                    interactionActive = true,
                    nowMs = 500L + index * 500L,
                    sourceRefreshRateHz = 60.0
                )
            )
        }
        // 50/48/45 are not exact divisors of a 60 Hz producer. Soft glass
        // evidence alone cannot justify the only phase-stable step, 60 -> 30.
        assertEquals(60, governor.cap)
    }

    @Test
    fun twoModerateGlassHitchesConfirmFineRelief() {
        val governor = measuredHighCadence()

        assertFalse(
            governor.observeFastPresentation(
                intervalMs = 500,
                renderedFrames = 22,
                maxGapMs = 80,
                requestedFps = 60,
                interactionActive = true,
                nowMs = 500
            )
        )
        assertTrue(
            governor.observeFastPresentation(
                intervalMs = 500,
                renderedFrames = 22,
                maxGapMs = 80,
                requestedFps = 60,
                interactionActive = true,
                nowMs = 1_000
            )
        )
        assertEquals(58, governor.cap)
    }

    @Test
    fun ordinaryFineCadenceVarianceDoesNotCascade() {
        val governor = measuredHighCadence()
        assertFalse(
            governor.observeFastPresentation(
                intervalMs = 500,
                renderedFrames = 22,
                maxGapMs = 80,
                requestedFps = 60,
                interactionActive = true,
                nowMs = 500
            )
        )
        assertTrue(
            governor.observeFastPresentation(
                intervalMs = 500,
                renderedFrames = 22,
                maxGapMs = 80,
                requestedFps = 60,
                interactionActive = true,
                nowMs = 1_000
            )
        )

        assertFalse(
            governor.observeFastPresentation(
                intervalMs = 500,
                renderedFrames = 20,
                maxGapMs = 80,
                requestedFps = 58,
                interactionActive = true,
                nowMs = 1_500
            )
        )
        assertEquals(58, governor.cap)
    }

    @Test
    fun severePersistentGlassStallCanTakeSecondBoundedStep() {
        val governor = measuredHighCadence()
        assertTrue(
            governor.observeFastPresentation(
                intervalMs = 500,
                renderedFrames = 10,
                maxGapMs = 180,
                requestedFps = 60,
                interactionActive = true,
                nowMs = 500
            )
        )
        assertEquals(58, governor.cap)

        assertTrue(
            governor.observeFastPresentation(
                intervalMs = 500,
                renderedFrames = 10,
                maxGapMs = 180,
                requestedFps = 58,
                interactionActive = true,
                nowMs = 1_000
            )
        )
        assertEquals(55, governor.cap)
    }

    @Test
    fun presentationFastPathNeedsActiveRecentHighCadenceEvidence() {
        val inactive = measuredHighCadence()
        assertFalse(
            inactive.observeFastPresentation(
                intervalMs = 500,
                renderedFrames = 10,
                maxGapMs = 180,
                requestedFps = 60,
                interactionActive = false,
                nowMs = 500
            )
        )
        assertEquals(60, inactive.cap)

        val stale = VideoCadenceGovernor(60)
        assertFalse(
            stale.observeFastPresentation(
                intervalMs = 3_000,
                renderedFrames = 30,
                maxGapMs = 180,
                requestedFps = 60,
                interactionActive = true,
                nowMs = 3_000
            )
        )
        assertEquals(60, stale.cap)

        val lowCadence = VideoCadenceGovernor(30)
        assertFalse(
            lowCadence.observeFastPresentation(
                intervalMs = 500,
                renderedFrames = 5,
                maxGapMs = 180,
                requestedFps = 30,
                interactionActive = true,
                nowMs = 500
            )
        )
        assertEquals(30, lowCadence.cap)
    }

    @Test
    fun fastSoftStepCannotImmediatelyCascadeToThirty() {
        val governor = VideoCadenceGovernor(60)
        assertTrue(governor.observe(outbound(queueMs = 120.0), receiver(), 60, 2_000))

        assertFalse(governor.observe(outbound(queueMs = 120.0), receiver(), 58, 3_000))
        assertEquals(58, governor.cap)
    }
}
