package com.aaris.remoteassist.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ControllerDisplayCapacityPolicyTest {
    @Test
    fun seamlessGroupIsInvariantAcrossSurfaceDrivenModeChanges() {
        val fromNinety =
            ControllerDisplayCapacityPolicy.resolve(
                activeModeRefreshRateHz = 90f,
                seamlessAlternativeRefreshRatesHz =
                    floatArrayOf(60f, 45f),
                sameResolutionRefreshRatesHz =
                    floatArrayOf(60f, 90f, 120f)
            )
        val fromFortyFive =
            ControllerDisplayCapacityPolicy.resolve(
                activeModeRefreshRateHz = 45f,
                seamlessAlternativeRefreshRatesHz =
                    floatArrayOf(60f, 90f),
                sameResolutionRefreshRatesHz =
                    floatArrayOf(60f, 90f, 120f)
            )

        assertEquals(90f, fromNinety!!, 0.001f)
        assertEquals(90f, fromFortyFive!!, 0.001f)
    }

    @Test
    fun modernPathDoesNotAdvertiseNonSeamlessSameResolutionMode() {
        val capacity =
            ControllerDisplayCapacityPolicy.resolve(
                activeModeRefreshRateHz = 60f,
                seamlessAlternativeRefreshRatesHz =
                    floatArrayOf(90f),
                sameResolutionRefreshRatesHz =
                    floatArrayOf(60f, 90f, 120f)
            )

        assertEquals(90f, capacity!!, 0.001f)
    }

    @Test
    fun legacyPathUsesSameResolutionRefreshGroup() {
        val capacity =
            ControllerDisplayCapacityPolicy.resolve(
                activeModeRefreshRateHz = 60f,
                seamlessAlternativeRefreshRatesHz = null,
                sameResolutionRefreshRatesHz =
                    floatArrayOf(60f, 90f, 120f)
            )

        assertEquals(120f, capacity!!, 0.001f)
    }

    @Test
    fun invalidVendorRatesCannotPoisonCapacity() {
        val capacity =
            ControllerDisplayCapacityPolicy.resolve(
                activeModeRefreshRateHz = Float.NaN,
                seamlessAlternativeRefreshRatesHz =
                    floatArrayOf(0f, 59.94f, 500f)
            )

        assertEquals(59.94f, capacity!!, 0.001f)
        assertNull(
            ControllerDisplayCapacityPolicy.resolve(
                activeModeRefreshRateHz = Float.NaN,
                seamlessAlternativeRefreshRatesHz =
                    floatArrayOf(0f, Float.POSITIVE_INFINITY, 500f)
            )
        )
    }
}
