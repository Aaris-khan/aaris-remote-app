package com.aaris.remoteassist.ai

import com.aaris.remoteassist.control.SemanticClickTarget
import com.aaris.remoteassist.control.SemanticNodeIdentity
import java.security.MessageDigest

/** Coordinate -> bounded actionable node/parent. All semantics stay on-device. */
object AiSemanticClickResolver {
    fun resolve(ui: AiUiSnapshot, x: Double, y: Double, width: Int, height: Int, targetId: String? = null): SemanticClickTarget? {
        if (ui.sensitiveFocus || ui.packageName.isBlank() || ui.windowId < 0 || width <= 0 || height <= 0) return null
        if (!x.isFinite() || !y.isFinite() || x !in 0.0..1.0 || y !in 0.0..1.0) return null
        val px = x * (width - 1)
        val py = y * (height - 1)
        val areaLimit = width.toLong() * height / 3L
        if (targetId != null && (targetId.isBlank() || ui.nodes.count { it.id == targetId } != 1)) return null
        val candidates = ui.nodes.filter {
            it.enabled && it.supportsClick && it.area in 1 until areaLimit &&
                validPath(it.treePath) && (if (targetId != null) it.id == targetId else it.contains(px, py))
        }
        val smallestArea = candidates.minOfOrNull { it.area } ?: return null
        // Equal/overlapping controls cannot be disambiguated by a resource id.
        val target = candidates.filter { it.area == smallestArea }.singleOrNull() ?: return null
        return SemanticClickTarget(ui.packageName, ui.windowId, identity(target), labels(ui, target))
    }

    fun matches(target: SemanticClickTarget, ui: AiUiSnapshot): Boolean {
        if (ui.sensitiveFocus || target.packageName != ui.packageName || target.windowId != ui.windowId) return false
        val node = ui.nodes.singleOrNull { it.treePath == target.node.path } ?: return false
        return node.enabled && node.supportsClick && identity(node) == target.node && labels(ui, node) == target.labelDigest
    }

    fun identity(node: AiUiNode) = SemanticNodeIdentity(
        node.treePath, node.id, node.role, node.text,
        node.left, node.top, node.right, node.bottom, node.textDigest
    )

    private fun labels(ui: AiUiSnapshot, target: AiUiNode): String {
        // In recycled file rows the parent id/path/bounds may stay unchanged
        // while the PDF name changes. Bind its visible descendant labels too.
        val labels = ui.nodes.asSequence()
            .filter { it.treePath == target.treePath || it.treePath.startsWith(target.treePath + "/") }
            .filter { it.text.isNotBlank() && !it.editable }
            .sortedBy { it.treePath }
            .map { it.treePath + "\u0000" + it.textDigest.ifEmpty { it.text } }
            .joinToString("\u0001")
        return textDigest(labels)
    }

    fun textDigest(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun validPath(path: String) = path.matches(Regex("0(?:/[0-9]{1,3}){0,64}"))
}
