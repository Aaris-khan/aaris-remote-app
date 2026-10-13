package com.aaris.remoteassist.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractionRepeatGuardTest {
    @Test
    fun forcedRepeatOfLastProducerTextureDoesNotEnterLiveVideo() {
        val guard = InteractionRepeatGuard()

        assertTrue(guard.shouldForwardToLiveVideo(100L, 1_000L))

        guard.armInteractionProbe(2_000L)
        assertFalse(guard.shouldForwardToLiveVideo(100L, 2_100L))
        

        assertTrue(guard.shouldForwardToLiveVideo(200L, 2_200L))
    }

    @Test
    fun naturalFrameThatWinsTheRaceStillPassesBeforeForcedRepeat() {
        val guard = InteractionRepeatGuard()

        assertTrue(guard.shouldForwardToLiveVideo(100L, 1_000L))

        guard.armInteractionProbe(2_000L)
        assertTrue(guard.shouldForwardToLiveVideo(200L, 2_100L))
        assertFalse(guard.shouldForwardToLiveVideo(200L, 2_200L))

        assertTrue(guard.shouldForwardToLiveVideo(300L, 2_300L))
    }

    @Test
    fun idleHeartbeatRepeatKeepsExistingLivenessBehavior() {
        val guard = InteractionRepeatGuard()

        assertTrue(guard.shouldForwardToLiveVideo(100L, 1_000L))
        assertTrue(
            guard.shouldForwardToLiveVideo(
                100L,
                1_000L +
                    InteractionRepeatGuard.INTERACTION_REPEAT_WINDOW_NS +
                    1L
            )
        )
    }

    @Test
    fun deferredRepeatInsideWindowIsStillDropped() {
        val guard = InteractionRepeatGuard()
        val start = 1_000_000_000L

        assertTrue(guard.shouldForwardToLiveVideo(100L, start))
        guard.armInteractionProbe(start + 1_000_000L)

        assertFalse(
            guard.shouldForwardToLiveVideo(
                100L,
                start +
                    1_000_000L +
                    InteractionRepeatGuard.INTERACTION_REPEAT_WINDOW_NS -
                    1L
            )
        )
    }

    @Test
    fun invalidProducerTimestampIsNeverInventedAsARepeat() {
        val guard = InteractionRepeatGuard()

        guard.armInteractionProbe(1_000L)
        assertTrue(guard.shouldForwardToLiveVideo(0L, 1_100L))
        assertTrue(guard.shouldForwardToLiveVideo(-1L, 1_200L))
    }
}
