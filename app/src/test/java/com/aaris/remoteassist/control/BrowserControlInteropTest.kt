package com.aaris.remoteassist.control

import java.util.Properties
import org.junit.Assert.*
import org.junit.Test

class BrowserControlInteropTest {
    private val fixtures = Properties().apply {
        BrowserControlInteropTest::class.java.getResourceAsStream("/browser-control-v1.properties")!!.use { load(it) }
    }
    private fun packet(key: String): ControlPacket = checkNotNull(ControlProtocol.decode(
        fixtures.getProperty(key).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    ))

    @Test fun browserTapPreservesLeaseBeyondJavascriptNumberPrecision() {
        val tap = packet("tap") as ControlPacket.Tap
        assertEquals(-9007199254740997L, tap.leaseSecret)
        assertEquals(9007199254740999L, tap.sequence)
        assertEquals(42, tap.generation)
        assertEquals(32767f / 65535f, tap.nx, 0f)
        assertEquals(1f, tap.ny, 0f)
    }

    @Test fun browserGestureNavigationAndUtf8PacketsDecodeInTheProductionProtocol() {
        assertEquals(600, (packet("longPress") as ControlPacket.LongPress).durationMs)
        assertEquals(350, (packet("swipe") as ControlPacket.Swipe).durationMs)
        assertTrue(packet("back") is ControlPacket.Back)
        assertTrue(packet("home") is ControlPacket.Home)
        assertTrue(packet("recents") is ControlPacket.Recents)
        assertEquals("Hello नमस्ते", (packet("text") as ControlPacket.Text).text)
        val drag = packet("drag") as ControlPacket.GesturePath
        assertEquals(3, drag.points.size)
        assertEquals(700, drag.durationMs)
    }
}
