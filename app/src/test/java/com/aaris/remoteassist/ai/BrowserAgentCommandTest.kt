package com.aaris.remoteassist.ai

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class BrowserAgentCommandTest {
    private val nonce = "temporary-nonce"
    private val command = JSONObject().put("protocol", "aaris-link-v1").put("nonce", nonce)
        .put("actionId", "unique_action_1").put("observationId", "d4c605a7-19cb-40d7-b7dd-84744653a123")
        .put("screenVersion", 3).put("action", "open_app").put("app", "WhatsApp")
    private fun block(json: String) = "Some normal conversation\nAARIS_ACTION_BEGIN\n$json\nAARIS_ACTION_END"
    @Test fun onlyCompleteAssistantCommandInCurrentSessionIsAccepted() {
        assertEquals("open_app", BrowserAgentCommand.parse(block(command.toString()), nonce, true)?.getString("action"))
        assertNull(BrowserAgentCommand.parse(block(command.toString()), nonce, false))
        assertNull(BrowserAgentCommand.parse(block(command.toString()), "old-nonce", true))
        assertNull(BrowserAgentCommand.parse("AARIS_ACTION_BEGIN\n$command", nonce, true))
        assertNull(BrowserAgentCommand.parse("Open WhatsApp now", nonce, true))
        assertNull(BrowserAgentCommand.parse(block(command.toString()) + block(command.toString()), nonce, true))
    }
    @Test fun duplicateKeysUnknownFieldsUnsupportedActionsAndInvalidCoordinatesAreRejected() {
        assertNull(BrowserAgentCommand.parse(block(command.toString().replace("\"open_app\"", "\"open_app\",\"action\":\"home\"")), nonce, true))
        assertNull(BrowserAgentCommand.parse(block(JSONObject(command.toString()).put("extra", "execute").toString()), nonce, true))
        assertNull(BrowserAgentCommand.parse(block(JSONObject(command.toString()).put("action", "shell").toString()), nonce, true))
        val tap = JSONObject(command.toString()).apply { remove("app"); put("action", "tap"); put("x", 1.2); put("y", .5) }
        assertNull(BrowserAgentCommand.parse(block(tap.toString()), nonce, true))
    }
    @Test fun copiedLinkIsServerReadableAndKeepsTheStableRefreshIdentity() {
        val url=BrowserLinkTicket("a".repeat(64), "b".repeat(43), 1, "https://remote.example", "client-id").link
        assertTrue(url.contains("/remote/?session="));assertTrue(url.contains("&access="));assertTrue(url.endsWith("&client=client-id"));assertFalse(url.contains('#'))
    }
}
