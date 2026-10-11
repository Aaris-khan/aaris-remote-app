package com.aaris.remoteassist.webrtc

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaRecoveryPolicyTest {
    @Test fun decoderProgressGetsOneBoundedRendererGraceThenVisibleRecovery() {
        val policy = MediaRecoveryPolicy()
        assertEquals(MediaRecoveryAction.REFRESH_PRIMARY, policy.request(true, 3_500))
        assertEquals(MediaRecoveryAction.REFRESH_PRIMARY, policy.request(true, 5_499))
        assertEquals(MediaRecoveryAction.COMPATIBILITY_ONLY, policy.request(true, 5_500))
        assertEquals(MediaRecoveryAction.COMPATIBILITY_ONLY, policy.request(true, 8_000))
    }

    @Test fun codecRepairRequiresMissingDecodeAndDecodeCannotRetireFallback() {
        val policy = MediaRecoveryPolicy()
        assertEquals(MediaRecoveryAction.COMPATIBILITY_AND_CODEC, policy.request(false, 3_500))
        assertEquals(MediaRecoveryAction.COMPATIBILITY_ONLY, policy.request(true, 7_500))
        assertEquals(MediaRecoveryAction.COMPATIBILITY_ONLY, policy.request(true, 30_000))
        assertEquals(MediaRecoveryAction.COMPATIBILITY_AND_CODEC, policy.request(false, 34_000))
    }

    @Test fun actualPrimaryPresentationRestoresZeroCopyGraceForANewFailure() {
        val policy = MediaRecoveryPolicy()
        policy.request(false, 3_500)
        policy.primaryPresented()
        assertEquals(MediaRecoveryAction.REFRESH_PRIMARY, policy.request(true, 8_000))
        assertEquals(MediaRecoveryAction.COMPATIBILITY_ONLY, policy.request(true, 10_000))
    }

    @Test fun backwardsClockDoesNotEscalateAPrimaryRenderer() {
        val policy = MediaRecoveryPolicy()
        policy.request(true, 8_000)
        assertEquals(MediaRecoveryAction.REFRESH_PRIMARY, policy.request(true, 4_000))
        assertEquals(MediaRecoveryAction.REFRESH_PRIMARY, policy.request(true, 5_999))
    }
}
