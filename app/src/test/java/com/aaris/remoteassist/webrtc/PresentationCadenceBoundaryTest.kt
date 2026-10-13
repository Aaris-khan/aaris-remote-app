package com.aaris.remoteassist.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresentationCadenceBoundaryTest {
    @Test
    fun alternatingNinetyHzPulldownIsVisibleAsGapVariance() {
        val tracker = PresentationCadenceTracker()
        var now = 1_000L
        assertNull(tracker.onPresented(now))

        var sample: PresentationCadenceWindow? = null
        repeat(40) { index ->
            now += if (index % 2 == 0) 11L else 22L
            sample = tracker.onPresented(now) ?: sample
        }

        val measured = checkNotNull(sample)
        assertTrue(measured.gapStdDevMs >= 5)
        assertTrue(measured.maxGapMs >= 22)
    }

    @Test
    fun steadySixtyHzCadenceHasNearZeroGapVariance() {
        val tracker = PresentationCadenceTracker()
        var now = 1_000L
        assertNull(tracker.onPresented(now))

        var sample: PresentationCadenceWindow? = null
        repeat(40) { index ->
            now += if (index % 2 == 0) 16L else 17L
            sample = tracker.onPresented(now) ?: sample
        }

        val measured = checkNotNull(sample)
        assertTrue(measured.gapStdDevMs <= 1)
        assertTrue(measured.maxGapMs <= 17)
    }

    @Test fun sparseFramesAreNotDoubleCountedAfterStartAndReset() {
        val tracker = PresentationCadenceTracker()
        assertNull(tracker.onPresented(1_000))
        assertEquals(2.0, tracker.onPresented(1_500)!!.fps, 0.001)
        assertEquals(2.0, tracker.onPresented(2_000)!!.fps, 0.001)
        tracker.reset()
        assertNull(tracker.onPresented(4_000))
        assertEquals(2.0, tracker.onPresented(4_500)!!.fps, 0.001)
        assertNull(tracker.onPresented(12_000))
        assertEquals(2.0, tracker.onPresented(12_500)!!.fps, 0.001)
    }
}
