package com.aaris.remoteassist.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteControllerLayoutPolicyTest {
    @Test
    fun remoteAspectChoosesMatchingControllerOrientation() {
        assertEquals(
            RemoteScreenOrientation.PORTRAIT,
            RemoteControllerLayoutPolicy.orientation(1080, 2400)
        )
        assertEquals(
            RemoteScreenOrientation.LANDSCAPE,
            RemoteControllerLayoutPolicy.orientation(2400, 1080)
        )
        assertNull(
            RemoteControllerLayoutPolicy.orientation(1000, 1000)
        )
    }

    @Test
    fun visibleDockReservesOnlyItsPhysicalSide() {
        assertEquals(
            RemoteContentInsets(0, 140),
            RemoteControllerLayoutPolicy.contentInsets(
                dockVisible = true,
                dockOnRight = true,
                dockWidthPx = 120,
                gapPx = 20
            )
        )
        assertEquals(
            RemoteContentInsets(140, 0),
            RemoteControllerLayoutPolicy.contentInsets(
                dockVisible = true,
                dockOnRight = false,
                dockWidthPx = 120,
                gapPx = 20
            )
        )
    }

    @Test
    fun hiddenDockNeverShrinksRemoteViewport() {
        assertEquals(
            RemoteContentInsets(0, 0),
            RemoteControllerLayoutPolicy.contentInsets(
                dockVisible = false,
                dockOnRight = true,
                dockWidthPx = 120,
                gapPx = 20
            )
        )
    }
}
