package com.aaris.remoteassist.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LiveIceRecoveryPolicyTest {
    @Test
    fun isolatedRecoveryPrefersAllPaths() {
        val policy = LiveIceRecoveryPolicy(escalationWindowMs = 12_000L)

        assertEquals(
            LiveIceRecoveryMode.ALL_PATHS,
            policy.next(1_000L)
        )
    }

    @Test
    fun repeatedFailureEscalatesToRelayOnly() {
        val policy = LiveIceRecoveryPolicy(escalationWindowMs = 12_000L)

        assertEquals(
            LiveIceRecoveryMode.ALL_PATHS,
            policy.next(1_000L)
        )
        assertEquals(
            LiveIceRecoveryMode.RELAY_ONLY,
            policy.next(5_000L)
        )
        assertEquals(
            LiveIceRecoveryMode.RELAY_ONLY,
            policy.next(8_000L)
        )
    }

    @Test
    fun healthyGapDecaysBackToDirectFirst() {
        val policy = LiveIceRecoveryPolicy(escalationWindowMs = 12_000L)

        policy.next(1_000L)
        assertEquals(
            LiveIceRecoveryMode.RELAY_ONLY,
            policy.next(5_000L)
        )

        assertEquals(
            LiveIceRecoveryMode.ALL_PATHS,
            policy.next(20_001L)
        )
    }

    @Test
    fun resetClearsEscalationHistory() {
        val policy = LiveIceRecoveryPolicy(escalationWindowMs = 12_000L)

        policy.next(1_000L)
        assertEquals(
            LiveIceRecoveryMode.RELAY_ONLY,
            policy.next(2_000L)
        )

        policy.reset()

        assertEquals(
            LiveIceRecoveryMode.ALL_PATHS,
            policy.next(2_001L)
        )
    }

    @Test
    fun rejectsInvalidClockInput() {
        val policy = LiveIceRecoveryPolicy()

        assertThrows(IllegalArgumentException::class.java) {
            policy.next(-1L)
        }
    }
}
