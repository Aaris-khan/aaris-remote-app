package com.aaris.remoteassist.webrtc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighCadencePressureGateTest {
    @Test
    fun isolatedTelemetryBlipDoesNotConfirm() {
        val gate = HighCadencePressureGate()
        assertFalse(gate.observeTelemetry(1_000L, true, false))
        assertFalse(gate.observeTelemetry(1_500L, false, false))
        assertFalse(gate.observeTelemetry(2_000L, true, false))
    }

    @Test
    fun twoFreshTelemetryWindowsConfirmSustainedPressure() {
        val gate = HighCadencePressureGate()
        assertFalse(gate.observeTelemetry(1_000L, true, false))
        assertTrue(gate.observeTelemetry(1_500L, true, false))
    }

    @Test
    fun corroboratedResidenceAndGlassCanConfirmOneTelemetryWindow() {
        val gate = HighCadencePressureGate()
        assertTrue(gate.observeTelemetry(1_000L, true, true))
    }

    @Test
    fun presentationNeedsTwoDistinctSoftWindows() {
        val gate = HighCadencePressureGate()
        assertFalse(gate.observePresentation(1_000L, true, false))
        assertFalse(gate.observePresentation(1_100L, true, false))
        assertTrue(gate.observePresentation(1_500L, true, false))
    }

    @Test
    fun cleanPresentationWindowErasesOldSoftVote() {
        val gate = HighCadencePressureGate()
        assertFalse(gate.observePresentation(1_000L, true, false))
        assertFalse(gate.observePresentation(1_500L, false, false))
        assertFalse(gate.observePresentation(2_000L, true, false))
    }

    @Test
    fun severePresentationRemainsImmediateEmergencyEvidence() {
        val gate = HighCadencePressureGate()
        assertTrue(gate.observePresentation(1_000L, true, true))
    }
}
