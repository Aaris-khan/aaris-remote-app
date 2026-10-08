package com.aaris.remoteassist.webrtc

import org.junit.Assert.assertEquals
import org.junit.Test

class InboundVideoLivenessMonitorTest {
    private val none = InboundVideoLivenessAction.NONE
    private val repair = InboundVideoLivenessAction.REQUEST_RECOVERY
    private val resumed = InboundVideoLivenessAction.CONFIRM_PRIMARY_RECOVERED

    @Test
    fun healthyDecodeProgressAtDifferentSamplingRatesNeverRequestsRecovery() {
        val monitor = InboundVideoLivenessMonitor()
        assertEquals(none, monitor.observe(100, 100, 0))
        assertEquals(none, monitor.observe(120, 120, 100))
        assertEquals(none, monitor.observe(140, 140, 600))
        assertEquals(none, monitor.observe(180, 180, 2_100))
        assertEquals(none, monitor.observe(200, 200, 4_100))
    }

    @Test
    fun progressingRtpWithFrozenDecoderRecoversByElapsedTime() {
        val monitor = InboundVideoLivenessMonitor()
        monitor.observe(100, 100, 0)
        assertEquals(none, monitor.observe(120, 100, 500))
        assertEquals(none, monitor.observe(140, 100, 1_000))
        assertEquals(none, monitor.observe(160, 100, 2_900))
        assertEquals(repair, monitor.observe(180, 100, 3_000))
        assertEquals(none, monitor.observe(200, 100, 3_500))
    }

    @Test
    fun veryShortStatsProbesCannotAccelerateDecoderStallTimeout() {
        val monitor = InboundVideoLivenessMonitor()
        monitor.observe(100, 100, 0)
        for (time in 100L..2_400L step 100L) {
            assertEquals(none, monitor.observe(100 + time / 100, 100, time))
        }
        assertEquals(repair, monitor.observe(130, 100, 2_600))
    }

    @Test
    fun oneNetworkGapClearsAccumulatedDecoderOnlyEvidence() {
        val monitor = InboundVideoLivenessMonitor()
        monitor.observe(100, 100, 0)
        assertEquals(none, monitor.observe(120, 100, 500))
        assertEquals(none, monitor.observe(120, 100, 1_500))
        assertEquals(none, monitor.observe(140, 100, 2_500))
        assertEquals(none, monitor.observe(160, 100, 4_900))
        assertEquals(repair, monitor.observe(180, 100, 5_000))
    }

    @Test
    fun staticOrNetworkQuietScreenUsesLongerDeadline() {
        val monitor = InboundVideoLivenessMonitor()
        monitor.observe(100, 100, 0)
        assertEquals(none, monitor.observe(100, 100, 1_000))
        assertEquals(none, monitor.observe(100, 100, 6_500))
        assertEquals(none, monitor.observe(100, 100, 12_999))
        assertEquals(repair, monitor.observe(100, 100, 13_000))
    }

    @Test
    fun noDecodedFramesAtStartupCannotTriggerFallback() {
        val monitor = InboundVideoLivenessMonitor()
        assertEquals(none, monitor.observe(0, 0, 0))
        assertEquals(none, monitor.observe(300, 0, 5_000))
        assertEquals(none, monitor.observe(400, 0, 20_000))
        assertEquals(none, monitor.observe(450, 300, 21_000))
    }

    @Test
    fun decoderResumeNeedsGlassConfirmationAndThenStopsRepeating() {
        val monitor = InboundVideoLivenessMonitor()
        monitor.observe(100, 100, 0)
        monitor.observe(120, 100, 500)
        assertEquals(repair, monitor.observe(140, 100, 3_000))
        assertEquals(resumed, monitor.observe(150, 110, 3_500))
        assertEquals(resumed, monitor.observe(160, 120, 4_000))
        monitor.markPrimaryRecoveredConfirmed()
        assertEquals(none, monitor.observe(170, 130, 4_500))
    }

    @Test
    fun failedRecoveryRequestHasCooldownAndMayRetry() {
        val monitor = InboundVideoLivenessMonitor()
        monitor.observe(100, 100, 0)
        monitor.observe(120, 100, 500)
        assertEquals(repair, monitor.observe(140, 100, 3_000))
        monitor.markRecoveryRequestFailed()
        assertEquals(none, monitor.observe(150, 100, 3_500))
        assertEquals(none, monitor.observe(160, 100, 6_999))
        assertEquals(repair, monitor.observe(180, 100, 7_000))
    }

    @Test
    fun counterResetAndClockRollbackClearStaleEvidence() {
        val monitor = InboundVideoLivenessMonitor()
        monitor.observe(100, 100, 0)
        monitor.observe(120, 100, 500)
        assertEquals(none, monitor.observe(3, 2, 2_900))
        assertEquals(none, monitor.observe(4, 2, 3_000))
        assertEquals(none, monitor.observe(5, 2, 100))
        assertEquals(none, monitor.observe(6, 3, 500))
    }

    @Test
    fun invalidSamplesDoNotAccumulateOrDamageKnownCounterBaseline() {
        val monitor = InboundVideoLivenessMonitor()
        monitor.observe(100, 100, 0)
        assertEquals(none, monitor.observe(-1, 100, 500))
        assertEquals(none, monitor.observe(120, 100, 1_000))
        assertEquals(none, monitor.observe(130, 100, 3_000))
        assertEquals(repair, monitor.observe(140, 100, 3_500))
    }
}
