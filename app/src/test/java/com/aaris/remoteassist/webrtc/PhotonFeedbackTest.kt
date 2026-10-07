package com.aaris.remoteassist.webrtc

import com.aaris.remoteassist.control.ControlPacket
import com.aaris.remoteassist.control.ControlProtocol
import org.junit.Assert.*
import org.junit.Test

class PhotonFeedbackTest {
    private fun packet(sequence: Long = 1L) =
        ControlPacket.PhotonFeedback(
            leaseSecret = 42L,
            generation = 3,
            sequence = sequence,
            inputToGlassUpperBoundMs = 140,
            commandAckMs = 90,
            postAckPresentMs = 50
        )

    @Test
    fun photonPacketRoundTripsAndRejectsInvalidPayloads() {
        val p = packet()
        val bytes = ControlProtocol.encode(p)
        assertEquals(34, bytes.size)
        assertEquals(p, ControlProtocol.decode(bytes))
        assertNull(ControlProtocol.decode(bytes.copyOf(bytes.size - 1)))
        assertNull(ControlProtocol.decode(bytes + byteArrayOf(0)))

        assertThrows(IllegalArgumentException::class.java) {
            ControlProtocol.encode(
                p.copy(
                    inputToGlassUpperBoundMs = 20,
                    commandAckMs = 30
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ControlProtocol.encode(
                p.copy(postAckPresentMs = 5_001)
            )
        }
        assertNull(
            ControlProtocol.toRemoteCommand(
                sessionId = "s",
                packet = p,
                widthPx = 1080,
                heightPx = 2400
            )
        )
    }

    @Test
    fun photonFeedbackIsLeaseBoundReplaySafeAndRetainedBriefly() {
        val feedback = ReceiverVideoFeedback()
        val p = packet()

        assertFalse(feedback.accept(p, 99, 3, 1_000))
        assertFalse(feedback.accept(p, 42, 4, 1_000))
        assertTrue(feedback.accept(p, 42, 3, 1_000))
        assertFalse(feedback.accept(p, 42, 3, 1_001))

        val first = checkNotNull(feedback.consume(42, 3, 1_050))
        assertEquals(140, first.inputToGlassUpperBoundMs)
        assertEquals(90, first.commandAckMs)
        assertEquals(50, first.postAckPresentMs)
        assertEquals(1L, first.photonSampleSequence)
        assertEquals(50, first.photonSampleAgeMs)
        assertTrue(first.photonSampleFresh)

        val held = checkNotNull(feedback.consume(42, 3, 2_000))
        assertEquals(140, held.inputToGlassUpperBoundMs)
        assertEquals(1L, held.photonSampleSequence)
        assertFalse(held.photonSampleFresh)

        assertNull(
            feedback.consume(42, 3, 4_001)
                ?.inputToGlassUpperBoundMs
        )

        assertTrue(feedback.accept(packet(sequence = 2), 42, 3, 4_100))
        val next = checkNotNull(feedback.consume(42, 3, 4_150))
        assertEquals(2L, next.photonSampleSequence)
        assertTrue(next.photonSampleFresh)
    }
}
