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
    fun highCadenceMaskKeepsOnlyEvenPresentationRates() {
        val ninetyOnly =
            ControllerDisplayCapacityPolicy.highCadenceMask(
                activeModeRefreshRateHz = 90f,
                seamlessAlternativeRefreshRatesHz = floatArrayOf()
            )
        assertEquals(
            true,
            ControllerDisplayCapacityPolicy.supportsHighCadence(
                ninetyOnly,
                45
            )
        )
        assertEquals(
            false,
            ControllerDisplayCapacityPolicy.supportsHighCadence(
                ninetyOnly,
                60
            )
        )

        val oneTwenty =
            ControllerDisplayCapacityPolicy.highCadenceMask(
                activeModeRefreshRateHz = 120f,
                seamlessAlternativeRefreshRatesHz = floatArrayOf()
            )
        assertEquals(
            true,
            ControllerDisplayCapacityPolicy.supportsHighCadence(
                oneTwenty,
                60
            )
        )
        assertEquals(
            true,
            ControllerDisplayCapacityPolicy.supportsHighCadence(
                oneTwenty,
                40
            )
        )

        val oneFortyFour =
            ControllerDisplayCapacityPolicy.highCadenceMask(
                activeModeRefreshRateHz = 144f,
                seamlessAlternativeRefreshRatesHz = floatArrayOf()
            )
        assertEquals(
            true,
            ControllerDisplayCapacityPolicy.supportsHighCadence(
                oneFortyFour,
                48
            )
        )
        assertEquals(
            true,
            ControllerDisplayCapacityPolicy.supportsHighCadence(
                oneFortyFour,
                36
            )
        )
        assertEquals(
            false,
            ControllerDisplayCapacityPolicy.supportsHighCadence(
                oneFortyFour,
                60
            )
        )
    }

    @Test
    fun seamlessAlternativeCanMakeSixtyJudderFreeOnNinetyClassPanel() {
        val mask =
            ControllerDisplayCapacityPolicy.highCadenceMask(
                activeModeRefreshRateHz = 90f,
                seamlessAlternativeRefreshRatesHz =
                    floatArrayOf(60f)
            )

        assertEquals(
            true,
            ControllerDisplayCapacityPolicy.supportsHighCadence(
                mask,
                60
            )
        )
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
