package com.aaris.remoteassist.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PresentationCadenceBoundaryTest {
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
