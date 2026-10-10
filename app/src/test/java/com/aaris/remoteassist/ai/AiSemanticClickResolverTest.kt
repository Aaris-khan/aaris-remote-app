package com.aaris.remoteassist.ai

import com.aaris.remoteassist.control.SemanticClickCommand
import com.aaris.remoteassist.control.TapCommand
import com.aaris.remoteassist.session.LiveLease
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AiSemanticClickResolverTest {
    private val row1 = AiUiNode("files:id/row", "FrameLayout", "", 0, 80, 120, 112,
        true, false, false, true, treePath = "0/0")
    private val row2 = row1.copy(top = 120, bottom = 152, treePath = "0/1")
    private val label1 = AiUiNode("files:id/title", "TextView", "resume-old.pdf", 14, 88, 108, 104,
        false, false, false, true, treePath = "0/0/0")
    private val label2 = label1.copy(text = "resume-final.pdf", top = 128, bottom = 144, treePath = "0/1/0")
    private val ui = AiUiSnapshot("com.android.documentsui", JSONArray(), emptyList(), false, false, 7,
        listOf(row1, label1, row2, label2))

    private fun resolve(snapshot: AiUiSnapshot = ui) = AiSemanticClickResolver.resolve(snapshot, 0.50, 0.56, 120, 240)

    @Test fun pdfLabelResolvesItsOwnClickableParentDespiteRepeatedResourceIds() {
        val target = requireNotNull(resolve())
        assertEquals("0/1", target.node.path)
        assertEquals(120, target.node.top)
        assertTrue(AiSemanticClickResolver.matches(target, ui))
        assertNull(AiSemanticClickResolver.resolve(ui, 0.50, 0.56, 120, 240, "files:id/row"))
    }

    @Test fun recycledRowWithDifferentFilenameIsRejectedEvenWhenParentIsIdentical() {
        val target = requireNotNull(resolve())
        val recycled = ui.copy(nodes = listOf(row1, label1, row2, label2.copy(text = "other-person.pdf")))
        assertFalse(AiSemanticClickResolver.matches(target, recycled))
    }

    @Test fun filenamesThatShareTruncatedVisibleTextStillHaveDifferentIdentities() {
        val prefix = "a".repeat(160)
        val first = label2.copy(text = prefix, textDigest = AiSemanticClickResolver.textDigest(prefix + "-resume.pdf"))
        val snapshot = ui.copy(nodes = listOf(row1, label1, row2, first))
        val target = requireNotNull(resolve(snapshot))
        val different = first.copy(textDigest = AiSemanticClickResolver.textDigest(prefix + "-other.pdf"))
        assertFalse(AiSemanticClickResolver.matches(target, snapshot.copy(nodes = listOf(row1, label1, row2, different))))
    }

    @Test fun movedDisabledMissingOrUnsupportedTargetCannotBeClicked() {
        val target = requireNotNull(resolve())
        for (node in listOf(row2.copy(top = 121), row2.copy(enabled = false), row2.copy(supportsClick = false))) {
            assertFalse(AiSemanticClickResolver.matches(target, ui.copy(nodes = listOf(row1, label1, node, label2))))
        }
        assertFalse(AiSemanticClickResolver.matches(target, ui.copy(nodes = listOf(row1, label1))))
        assertNull(resolve(ui.copy(nodes = listOf(row2.copy(supportsClick = false), label2))))
    }

    @Test fun foreignWindowSensitiveFocusCanvasAndAmbiguousOverlapStayBlocked() {
        val target = requireNotNull(resolve())
        assertFalse(AiSemanticClickResolver.matches(target, ui.copy(windowId = 8)))
        assertFalse(AiSemanticClickResolver.matches(target, ui.copy(packageName = "another.app")))
        assertNull(resolve(ui.copy(sensitiveFocus = true)))
        assertNull(resolve(ui.copy(nodes = listOf(row2.copy(top = 0, bottom = 240)))))
        assertNull(resolve(ui.copy(nodes = ui.nodes + row2.copy(treePath = "0/2"))))
    }

    @Test fun explicitSemanticClickWorksInAnyAppAndNormalTapNeverSwitchesMethods() {
        val app = ui.copy(packageName = "some.website.browser")
        val target = requireNotNull(resolve(app))
        val lease = LiveLease("ai-session", 77, 3)
        val tap = JSONObject().put("action", "tap").put("x", 0.5).put("y", 0.56)
        assertTrue(AiActionTranslator.translate(tap, lease, 1, 120, 240, target) is TapCommand)
        val semantic = JSONObject(tap.toString()).put("action", "semantic_click")
        val command = AiActionTranslator.translate(semantic, lease, 2, 120, 240, target) as SemanticClickCommand
        assertEquals(target, command.target)
        assertEquals(lease.leaseSecret, command.leaseSecret)
    }

    @Test fun unrelatedTextChangesDoNotChangeSemanticIdentity() {
        val target = requireNotNull(resolve())
        assertTrue(AiSemanticClickResolver.matches(target,
            ui.copy(nodes = listOf(row1, label1.copy(text = "unrelated-update.pdf"), row2, label2))))
    }
}
