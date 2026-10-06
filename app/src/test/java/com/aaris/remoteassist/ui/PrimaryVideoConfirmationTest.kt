package com.aaris.remoteassist.ui

import org.junit.Assert.*
import org.junit.Test

class PrimaryVideoConfirmationTest {
    @Test fun renderedVideoRetriesTemporaryChannelFailureWithoutPerFrameFlooding() {
        val confirmation = PrimaryVideoConfirmation()
        var attempts = 0
        fun present(now: Long, accepted: Boolean) {
            confirmation.onPresented(now) { attempts++; accepted }
        }
        present(1_000, false)
        repeat(15) { present(1_010L + it * 30L, false) }
        assertEquals(1, attempts)
        present(1_500, true)
        assertEquals(2, attempts)
        present(2_500, true)
        assertEquals(2, attempts)
        confirmation.reset()
        present(2_501, true)
        assertEquals(3, attempts)
    }

    @Test fun resetNeverSendsConfirmationBeforeANewPresentation() {
        val confirmation = PrimaryVideoConfirmation()
        var sends = 0
        confirmation.onPresented(100) { sends++; true }
        confirmation.reset()
        assertEquals(1, sends)
        confirmation.onPresented(101) { sends++; false }
        confirmation.onPresented(601) { sends++; true }
        assertEquals(3, sends)
    }
}
