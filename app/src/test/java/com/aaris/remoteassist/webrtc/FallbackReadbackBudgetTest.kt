package com.aaris.remoteassist.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FallbackReadbackBudgetTest {
    private companion object {
        const val SOURCE_WIDTH = 1080
        const val SOURCE_HEIGHT = 2400
    }

    @Test fun defaultEnvelopePreservesLegacyAndInteractiveQuality() {
        val budget = FallbackReadbackBudget()
        assertEquals(
            2560,
            budget.maxSidePx(2560, SOURCE_WIDTH, SOURCE_HEIGHT)
        )
        assertEquals(
            1200,
            budget.maxSidePx(1200, SOURCE_WIDTH, SOURCE_HEIGHT)
        )
        assertEquals(90L, budget.minimumIntervalMs(90L))
        assertEquals(300L, budget.minimumIntervalMs(300L))
    }

    @Test fun isolatedSlowCopyDoesNotReduceResolutionOrCadence() {
        val budget = FallbackReadbackBudget()
        assertNull(budget.observe(70, 1_000))
        assertNull(budget.observe(18, 1_100))
        assertNull(budget.observe(70, 1_200))
        assertEquals(
            1200,
            budget.maxSidePx(1200, SOURCE_WIDTH, SOURCE_HEIGHT)
        )
        assertEquals(90L, budget.minimumIntervalMs(90L))
    }

    @Test fun sustainedSlowCopiesSpendCadenceBeforeReadablePixels() {
        val budget = FallbackReadbackBudget()
        assertNull(budget.observe(40, 1_000))
        assertEquals(1, budget.observe(42, 1_100))

        // Level 1 trims the 1200px recovery long edge to 900px. On a 20:9
        // phone that still leaves a 405px short edge, above the 360px floor.
        assertEquals(
            900,
            budget.maxSidePx(1200, SOURCE_WIDTH, SOURCE_HEIGHT)
        )
        assertEquals(120L, budget.minimumIntervalMs(90L))

        // Sustained pressure may step down again, but never twice at once.
        assertNull(budget.observe(55, 1_150))
        assertNull(budget.observe(55, 1_200))
        assertEquals(
            900,
            budget.maxSidePx(1200, SOURCE_WIDTH, SOURCE_HEIGHT)
        )
        assertEquals(2, budget.observe(55, 3_100))

        // 50% of 1200 would be 600px long (~270px short). Clamp to 800x360
        // instead and relieve the expensive synchronous path temporally.
        assertEquals(
            800,
            budget.maxSidePx(1200, SOURCE_WIDTH, SOURCE_HEIGHT)
        )
        assertEquals(
            800,
            budget.maxSidePx(1200, SOURCE_HEIGHT, SOURCE_WIDTH)
        )
        assertEquals(180L, budget.minimumIntervalMs(90L))
        assertEquals(
            "Existing slower network/backpressure cadence must remain authoritative",
            300L,
            budget.minimumIntervalMs(300L)
        )
        assertNull(budget.observe(99, 3_150))
        assertEquals(
            800,
            budget.maxSidePx(1200, SOURCE_WIDTH, SOURCE_HEIGHT)
        )
    }

    @Test fun readableFloorNeverUpscalesAWeakSource() {
        val budget = FallbackReadbackBudget()
        budget.observe(40, 1_000)
        budget.observe(40, 1_100)
        budget.observe(40, 1_150)
        budget.observe(40, 3_100)

        // A 320x720 source cannot be made 360p by policy. The long-side limit
        // may remain above the source, but CpuFrameCopy's ratio is capped at 1
        // so no synthetic upscaling occurs.
        assertEquals(
            800,
            budget.maxSidePx(1200, 320, 720)
        )
    }

    @Test fun recoveryRequiresEnoughFreshHealthySamplesAndHoldTime() {
        val budget = FallbackReadbackBudget()
        budget.observe(40, 1_000)
        budget.observe(40, 1_050)
        assertEquals(
            1920,
            budget.maxSidePx(2560, SOURCE_WIDTH, SOURCE_HEIGHT)
        )
        assertEquals(120L, budget.minimumIntervalMs(90L))

        repeat(10) {
            assertNull(budget.observe(8, 5_000L + it * 100L))
        }
        assertEquals(
            1920,
            budget.maxSidePx(2560, SOURCE_WIDTH, SOURCE_HEIGHT)
        )

        // A pause invalidates the old votes; the first healthy callback cannot
        // recover quality on stale proof, even though the hold time has elapsed.
        assertNull(budget.observe(8, 20_000))
        assertEquals(
            1920,
            budget.maxSidePx(2560, SOURCE_WIDTH, SOURCE_HEIGHT)
        )
        repeat(8) {
            assertNull(budget.observe(8, 20_100L + it * 100L))
        }
        assertEquals(0, budget.observe(8, 20_900))
        assertEquals(
            2560,
            budget.maxSidePx(2560, SOURCE_WIDTH, SOURCE_HEIGHT)
        )
        assertEquals(90L, budget.minimumIntervalMs(90L))
    }

    @Test fun disconnectedEvidenceAndResetDoNotLeakAcrossRecoverySessions() {
        val budget = FallbackReadbackBudget()
        budget.observe(45, 100)
        assertNull(budget.observe(45, 3_000))
        assertEquals(
            2560,
            budget.maxSidePx(2560, SOURCE_WIDTH, SOURCE_HEIGHT)
        )
        assertEquals(1, budget.observe(45, 3_100))
        budget.reset()
        assertEquals(
            2560,
            budget.maxSidePx(2560, SOURCE_WIDTH, SOURCE_HEIGHT)
        )
        assertEquals(90L, budget.minimumIntervalMs(90L))
        assertNull(budget.observe(45, 50))
        assertEquals(
            2560,
            budget.maxSidePx(2560, SOURCE_WIDTH, SOURCE_HEIGHT)
        )
    }

    @Test fun invalidMeasurementsHaveNoEffect() {
        val budget = FallbackReadbackBudget()
        assertNull(budget.observe(-1, 10))
        assertNull(budget.observe(40, -1))
        assertEquals(
            2560,
            budget.maxSidePx(2560, SOURCE_WIDTH, SOURCE_HEIGHT)
        )
        assertEquals(90L, budget.minimumIntervalMs(90L))
    }
}
