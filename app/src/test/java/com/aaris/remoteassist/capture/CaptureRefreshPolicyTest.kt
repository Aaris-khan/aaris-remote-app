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

    @Test fun interactionRefreshOnlyPullsWhenNaturalProducerIsLate() {
        val policy = CaptureRefreshPolicy()
        assertFalse(policy.shouldRefreshForInteraction(1_000))

        policy.onSample(1_000)
        assertFalse(policy.shouldRefreshForInteraction(1_044))
        assertTrue(policy.shouldRefreshForInteraction(1_045))
        assertFalse(policy.shouldRefreshForInteraction(1_046))

        // A natural producer frame wins and defers the synthetic pull.
        policy.onSample(1_080)
        assertFalse(policy.shouldRefreshForInteraction(1_124))
        assertTrue(policy.shouldRefreshForInteraction(1_125))
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
}
