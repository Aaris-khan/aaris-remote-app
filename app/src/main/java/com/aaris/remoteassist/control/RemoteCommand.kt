package com.aaris.remoteassist.control

sealed interface RemoteCommand {
    val sessionId: String
    val leaseSecret: Long
    val generation: Int
    val sequence: Long
}

data class TapCommand(
    override val sessionId: String,
    override val leaseSecret: Long,
    override val generation: Int,
    override val sequence: Long,
    val xPx: Float,
    val yPx: Float
) : RemoteCommand

/** Immutable on-device identity; resource ids alone are not unique in lists. */
data class SemanticNodeIdentity(
    val path: String,
    val id: String,
    val role: String,
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val textDigest: String = ""
)

data class SemanticClickTarget(
    val packageName: String,
    val windowId: Int,
    val node: SemanticNodeIdentity,
    val labelDigest: String
)

/** Explicit AI alternative to touch. Executes one ACTION_CLICK, with no retry. */
data class SemanticClickCommand(
    override val sessionId: String,
    override val leaseSecret: Long,
    override val generation: Int,
    override val sequence: Long,
    val target: SemanticClickTarget
) : RemoteCommand

data class LongPressCommand(
    override val sessionId: String,
    override val leaseSecret: Long,
    override val generation: Int,
    override val sequence: Long,
    val xPx: Float,
    val yPx: Float,
    val durationMs: Long = 650L
) : RemoteCommand

data class SwipeCommand(
    override val sessionId: String,
    override val leaseSecret: Long,
    override val generation: Int,
    override val sequence: Long,
    val fromXPx: Float,
    val fromYPx: Float,
    val toXPx: Float,
    val toYPx: Float,
    val durationMs: Long
) : RemoteCommand

data class RemotePathPoint(
    val xPx: Float,
    val yPx: Float
)

data class GesturePathCommand(
    override val sessionId: String,
    override val leaseSecret: Long,
    override val generation: Int,
    override val sequence: Long,
    val points: List<RemotePathPoint>,
    val durationMs: Long
) : RemoteCommand {
    init {
        require(points.size >= 2)
    }
}

enum class GestureStreamPhase {
    START,
    CONTINUE,
    END
}

data class GestureStreamCommand(
    override val sessionId: String,
    override val leaseSecret: Long,
    override val generation: Int,
    override val sequence: Long,
    val streamId: Long,
    val phase: GestureStreamPhase,
    val points: List<RemotePathPoint>,
    val durationMs: Long
) : RemoteCommand {
    init {
        require(points.isNotEmpty())
    }
}

data class TwoFingerCommand(
    override val sessionId: String,
    override val leaseSecret: Long,
    override val generation: Int,
    override val sequence: Long,
    val firstFromXPx: Float,
    val firstFromYPx: Float,
    val firstToXPx: Float,
    val firstToYPx: Float,
    val secondFromXPx: Float,
    val secondFromYPx: Float,
    val secondToXPx: Float,
    val secondToYPx: Float,
    val durationMs: Long
) : RemoteCommand

enum class GlobalAction { BACK, HOME, RECENTS }

data class GlobalActionCommand(
    override val sessionId: String,
    override val leaseSecret: Long,
    override val generation: Int,
    override val sequence: Long,
    val action: GlobalAction
) : RemoteCommand

data class SetTextCommand(
    override val sessionId: String,
    override val leaseSecret: Long,
    override val generation: Int,
    override val sequence: Long,
    val text: String,
    // AI exact/clear operations overwrite the focused field; human typing remains insertion.
    val replaceAll: Boolean = false
) : RemoteCommand

