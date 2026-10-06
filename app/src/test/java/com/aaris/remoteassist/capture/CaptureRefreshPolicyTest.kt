package com.aaris.remoteassist.capture

import org.junit.Assert.*
import org.junit.Test

class CaptureRefreshPolicyTest {
    @Test fun idleRefreshRequiresARealTextureAndIsRateLimitedEvenIfProducerIsStuck() {
        val policy = CaptureRefreshPolicy()
        assertFalse(policy.shouldRefresh(10_000))
        policy.onSample(10_000)
        assertFalse(policy.shouldRefresh(10_999))
        assertTrue(policy.shouldRefresh(11_000))
        assertFalse(policy.shouldRefresh(11_001))
        assertFalse(policy.shouldRefresh(11_999))
        assertTrue(policy.shouldRefresh(12_000))
    }

    @Test fun appliedInteractionPullsAfterOneTickOnlyWhenNaturalProducerStayedStale() {
        val policy = CaptureRefreshPolicy()
        policy.onSample(1_000)

        policy.onInteractionApplied(1_010)
        assertFalse(policy.shouldRefreshForInteraction(1_015))
        assertTrue(policy.shouldRefreshForInteraction(1_016))
        assertFalse(policy.shouldRefreshForInteraction(1_017))

        policy.onInteractionApplied(2_000)
        policy.onSample(2_004)
        assertFalse(policy.shouldRefreshForInteraction(2_020))
    }

    @Test fun rapidInteractionsNeverBuildSyntheticRefreshBacklog() {
        val policy = CaptureRefreshPolicy()
        policy.onSample(10_000)

        policy.onInteractionApplied(10_010)
        policy.onInteractionApplied(10_014)
        policy.onInteractionApplied(10_018)
        assertFalse(policy.shouldRefreshForInteraction(10_023))
        assertTrue(policy.shouldRefreshForInteraction(10_024))
        assertFalse(policy.shouldRefreshForInteraction(10_025))
    }

    @Test fun movingScreenGetsNoDuplicateWorkAndLatestSampleDefersIdleProbe() {
        val policy = CaptureRefreshPolicy()
        for (time in 0L..10_000L step 33L) {
            policy.onSample(time)
            assertFalse(policy.shouldRefresh(time + 1))
        }
        policy.onSample(10_000)
        assertFalse(policy.shouldRefresh(10_999))
        assertTrue(policy.shouldRefresh(11_000))
        policy.onSample(11_100)
        assertFalse(policy.shouldRefresh(12_000))
        assertTrue(policy.shouldRefresh(12_100))
    }

    @Test fun staticHeartbeatDoesNotPretendThatSourcePixelsAreMoving() {
        val refresh = CaptureRefreshPolicy()
        val activity = CaptureActivityTracker()
        val clock = CaptureSampleClock()
        val textureTimestamp = 1_000_000_000L
        activity.sampleFps(1_000)
        repeat(5) { index ->
            val now = 1_000L + index * 1_000L
            refresh.onSample(now)
            assertFalse(activity.onFrame(textureTimestamp))
            assertTrue(clock.timestamp(textureTimestamp, now * 1_000_000L) >= textureTimestamp)
            assertTrue(refresh.shouldRefresh(now + 1_000))
        }
        assertEquals(0.2, activity.sampleFps(6_000)!!, 0.001)
    }

    @Test fun displayTickTimingTracksRefreshRateWithoutPathologicallySmallDelays() {
        assertEquals(17L, InteractionRefreshTiming.oneDisplayTickMs(60f))
        assertEquals(12L, InteractionRefreshTiming.oneDisplayTickMs(90f))
        assertEquals(9L, InteractionRefreshTiming.oneDisplayTickMs(120f))
        assertEquals(8L, InteractionRefreshTiming.oneDisplayTickMs(144f))
        assertEquals(17L, InteractionRefreshTiming.oneDisplayTickMs(null))
        assertEquals(17L, InteractionRefreshTiming.oneDisplayTickMs(Float.NaN))
    }
}
