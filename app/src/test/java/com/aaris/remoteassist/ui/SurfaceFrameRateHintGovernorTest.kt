package com.aaris.remoteassist.ui

import com.aaris.remoteassist.webrtc.PresentationCadenceWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceFrameRateHintGovernorTest {
    @Test
    fun stablePassiveVideoVotesOnceForDisplayClassCadence() {
        val governor = SurfaceFrameRateHintGovernor(initialWindows = 3)
        val twentyFour = window(250, 6, 43)
        assertNull(governor.observe(twentyFour, 1_000))
        assertNull(governor.observe(twentyFour, 1_250))
        assertEquals(60, governor.observe(twentyFour, 1_500))
        assertEquals(60, governor.currentHintFps())

        // Network/capture throttling must never vote the display down to 15 Hz.
        repeat(30) {
            assertNull(
                governor.observe(
                    window(500, 1, 500),
                    2_000L + it * 500L
                )
            )
        }
        assertEquals(60, governor.currentHintFps())
    }

    @Test
    fun repeatedTapAndSwipeNeverSwitchRefreshMode() {
        val governor = SurfaceFrameRateHintGovernor()
        assertEquals(60, governor.beginInteraction())
        repeat(100) {
            assertNull(governor.beginInteraction())
            assertEquals(60, governor.currentHintFps())
            assertNull(governor.observe(window(200, 6, 35), it * 200L))
        }
        assertTrue(governor.reset())
        assertEquals(0, governor.currentHintFps())
    }

    @Test
    fun stableHighCadenceCannotRetuneLatencyFirstSurfaceVote() {
        val governor = SurfaceFrameRateHintGovernor(initialWindows = 2)
        assertEquals(60, governor.beginInteraction())

        // A stable 45-fps network/decode cadence is health evidence only.
        repeat(6) { sample ->
            assertNull(
                governor.observe(
                    window(500, if (sample % 2 == 0) 22 else 23, 24),
                    700L + sample * 600L
                )
            )
        }
        assertEquals(60, governor.currentHintFps())

        // Recovery to 60 likewise does not create a second display transition.
        repeat(4) { sample ->
            assertNull(
                governor.observe(
                    window(500, 30, 18),
                    4_500L + sample * 600L
                )
            )
        }
        assertEquals(60, governor.currentHintFps())
    }

    @Test
    fun oneMissedSixtyFpsFrameCannotRetuneSurfaceToTransientFiftyEight() {
        val governor = SurfaceFrameRateHintGovernor(initialWindows = 2)
        assertEquals(60, governor.beginInteraction())

        repeat(4) { sample ->
            assertNull(
                governor.observe(
                    window(500, 29, 33),
                    700L + sample * 600L
                )
            )
        }
        assertEquals(60, governor.currentHintFps())
    }

    @Test
    fun stableFiftyFiveClassDeliveryCannotRetuneSurface() {
        val governor = SurfaceFrameRateHintGovernor(initialWindows = 2)
        assertEquals(60, governor.beginInteraction())

        assertNull(governor.observe(window(500, 27, 33), 1_400))
        assertNull(governor.observe(window(500, 28, 33), 2_000))
        assertEquals(60, governor.currentHintFps())
    }

    @Test
    fun congestionCadenceNeverVotesDisplayBelowSixty() {
        val governor = SurfaceFrameRateHintGovernor(initialWindows = 2)
        assertEquals(60, governor.beginInteraction())

        assertNull(governor.observe(window(500, 15, 36), 700))
        assertNull(governor.observe(window(500, 15, 36), 1_300))
        assertEquals(60, governor.currentHintFps())

        repeat(6) {
            assertNull(
                governor.observe(
                    window(500, 12, 45),
                    1_800L + it * 500L
                )
            )
        }
        assertEquals(60, governor.currentHintFps())
    }

    @Test
    fun noPassiveHintUntilCleanConsecutivePresentationWindows() {
        val governor = SurfaceFrameRateHintGovernor(initialWindows = 2)
        val clean = window(167, 10, 18)
        val hitch = window(200, 6, 130)
        assertNull(governor.observe(clean, 1_000))
        assertNull(governor.observe(hitch, 1_200))
        assertNull(governor.observe(clean, 1_400))
        assertEquals(60, governor.observe(clean, 1_600))
    }

    @Test
    fun passiveHighCadenceBootstrapsSixtyInsteadOfMeasuredRate() {
        val governor = SurfaceFrameRateHintGovernor(initialWindows = 2)

        assertNull(governor.observe(window(500, 22, 24), 1_000))
        assertEquals(60, governor.observe(window(500, 23, 24), 1_600))
        assertEquals(60, governor.currentHintFps())

        // Once installed, later cadence changes cannot move the actuator.
        assertNull(governor.observe(window(500, 27, 33), 2_200))
        assertNull(governor.observe(window(500, 28, 33), 2_800))
        assertEquals(60, governor.currentHintFps())
    }

    @Test
    fun frozenOrSparseStreamCannotAdvertiseNewCadence() {
        val governor = SurfaceFrameRateHintGovernor(initialWindows = 1)
        assertNull(governor.observe(window(500, 1, 500), 1_000))
        assertNull(governor.observe(window(200, 6, 150), 1_200))
        assertNull(governor.observe(window(250, 6, 43), -1))
        assertEquals(0, governor.currentHintFps())
    }

    @Test
    fun resetRemovesSurfaceVoteAndRestartsMeasurement() {
        val governor = SurfaceFrameRateHintGovernor(initialWindows = 2)
        assertFalse(governor.reset())
        assertNull(governor.observe(window(167, 10, 18), 1_000))
        assertEquals(60, governor.observe(window(167, 10, 18), 1_200))
        assertTrue(governor.reset())
        assertFalse(governor.reset())
        assertNull(governor.observe(window(167, 10, 18), 1_400))
        assertEquals(60, governor.observe(window(167, 10, 18), 1_600))
    }

    @Test
    fun firstGestureHonorsCallerCeilingAndInvalidHint() {
        val governor = SurfaceFrameRateHintGovernor()
        assertNull(governor.beginInteraction(0))
        assertEquals(30, governor.beginInteraction(30))
        assertNull(governor.beginInteraction(60))
        assertEquals(30, governor.currentHintFps())
        governor.reset()
        assertEquals(60, governor.beginInteraction(120))
    }

    private fun window(
        intervalMs: Int,
        frames: Int,
        maxGapMs: Int
    ) = PresentationCadenceWindow(
        intervalMs,
        frames,
        maxGapMs
    )
}
