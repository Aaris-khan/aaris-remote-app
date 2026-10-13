package com.aaris.remoteassist.webrtc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackRefreshPolicyTest {
    @Test fun newlyEnabledFallbackCanRequestItsFirstRecoveryAnchor() {
        assertTrue(FallbackRefreshPolicy.shouldForce(1000L, Long.MIN_VALUE / 2, 500L))
    }

    @Test fun ongoingNaturalCaptureSuppressesUnnecessarySyntheticTextures() {
        // Real capture updates the sink even when its JPEG worker is busy.
        for (now in 500L..3_000L step 500L) {
            assertFalse(FallbackRefreshPolicy.shouldForce(now, now - 33L, 500L))
        }
    }

    @Test fun stoppedProducerStillGetsRecoveryRefreshAfterQuietInterval() {
        assertFalse(FallbackRefreshPolicy.shouldForce(1_499L, 1_000L, 500L))
        assertTrue(FallbackRefreshPolicy.shouldForce(1_500L, 1_000L, 500L))
        assertTrue(FallbackRefreshPolicy.shouldForce(2_000L, 1_500L, 500L))
    }

    @Test fun monotonicClockDiscontinuityCannotPermanentlyStarveRecovery() {
        assertTrue(FallbackRefreshPolicy.shouldForce(200L, 900L, 500L))
        assertFalse(FallbackRefreshPolicy.shouldForce(-1L, 900L, 500L))
    }
}
