package com.aaris.remoteassist.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeMediaPolicyTest {
    @Test
    fun remoteControlUsesBoundedLowLatencyPlayout() {
        assertEquals(0, RealtimeMediaPolicy.PLAYOUT_DELAY_MIN_MS)
        assertTrue(
            RealtimeMediaPolicy.PLAYOUT_DELAY_MAX_MS in 1..500
        )
        assertEquals(
            "WebRTC-ForceSendPlayoutDelay/min_ms:0,max_ms:100/",
            RealtimeMediaPolicy.FIELD_TRIALS
        )
    }

    @Test
    fun activeInteractionGetsHigherSchedulingPriorityThanIdle() {
        assertTrue(
            RealtimeMediaPolicy.threadPriority(true) <
                RealtimeMediaPolicy.threadPriority(false)
        )
    }
}
