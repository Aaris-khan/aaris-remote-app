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

        assertTrue(guard.shouldForwardToLiveVideo(100L))

        guard.beginInteractionProbe()
        assertTrue(guard.shouldForwardToLiveVideo(200L))
        assertFalse(guard.shouldForwardToLiveVideo(200L))
        guard.endInteractionProbe()

        assertTrue(guard.shouldForwardToLiveVideo(300L))
    }

    @Test
    fun idleHeartbeatRepeatKeepsExistingLivenessBehavior() {
        val guard = InteractionRepeatGuard()

        assertTrue(guard.shouldForwardToLiveVideo(100L))
        assertTrue(guard.shouldForwardToLiveVideo(100L))
    }

    @Test
    fun invalidProducerTimestampIsNeverInventedAsARepeat() {
        val guard = InteractionRepeatGuard()

        guard.beginInteractionProbe()
        assertTrue(guard.shouldForwardToLiveVideo(0L))
        assertTrue(guard.shouldForwardToLiveVideo(-1L))
        guard.endInteractionProbe()
    }
}
