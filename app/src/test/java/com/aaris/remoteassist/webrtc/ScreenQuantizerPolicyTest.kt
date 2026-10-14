package com.aaris.remoteassist.webrtc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenQuantizerPolicyTest {
    @Test
    fun h264UsesLibwebrtcCodecSpecificThresholds() {
        assertFalse(
            ScreenQuantizerPolicy.isHighPressure(
                codec = "video/H264",
                qpAverage = 37.0
            )
        )
        assertTrue(
            ScreenQuantizerPolicy.isHighPressure(
                codec = "video/H264",
                qpAverage = 38.0
            )
        )

        assertTrue(
            ScreenQuantizerPolicy.isFastRecoveryClean(
                codec = "video/H264",
                qpAverage = 24.0
            )
        )
        assertFalse(
            ScreenQuantizerPolicy.isFastRecoveryClean(
                codec = "video/H264",
                qpAverage = 25.0
            )
        )
    }

    @Test
    fun vp8DoesNotReuseH264Scale() {
        assertFalse(
            ScreenQuantizerPolicy.isHighPressure(
                codec = "video/VP8",
                qpAverage = 95.0
            )
        )
        assertTrue(
            ScreenQuantizerPolicy.isHighPressure(
                codec = "video/VP8",
                qpAverage = 96.0
            )
        )

        assertTrue(
            ScreenQuantizerPolicy.isFastRecoveryClean(
                codec = "video/VP8",
                qpAverage = 29.0
            )
        )
        assertFalse(
            ScreenQuantizerPolicy.isFastRecoveryClean(
                codec = "video/VP8",
                qpAverage = 30.0
            )
        )
    }

    @Test
    fun unknownOrMissingQuantizerTelemetryPreservesExistingBehavior() {
        assertFalse(
            ScreenQuantizerPolicy.isHighPressure(
                codec = "video/AV1",
                qpAverage = 100.0
            )
        )
        assertFalse(
            ScreenQuantizerPolicy.isHighPressure(
                codec = "video/H264",
                qpAverage = null
            )
        )
        assertTrue(
            ScreenQuantizerPolicy.isRecoverySafe(
                codec = "video/AV1",
                qpAverage = 100.0
            )
        )
        assertTrue(
            ScreenQuantizerPolicy.isFastRecoveryClean(
                codec = "video/H264",
                qpAverage = null
            )
        )
    }

    @Test
    fun mediumKnownQpAllowsConservativeButNotFastRecovery() {
        assertTrue(
            ScreenQuantizerPolicy.isRecoverySafe(
                codec = "video/H264",
                qpAverage = 30.0
            )
        )
        assertFalse(
            ScreenQuantizerPolicy.isFastRecoveryClean(
                codec = "video/H264",
                qpAverage = 30.0
            )
        )
    }
}
