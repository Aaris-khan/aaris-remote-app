package com.aaris.remoteassist.ui

internal enum class RemoteScreenOrientation {
    PORTRAIT,
    LANDSCAPE
}

internal data class RemoteContentInsets(
    val leftPx: Int,
    val rightPx: Int
)

internal object RemoteControllerLayoutPolicy {
    fun orientation(
        widthPx: Int,
        heightPx: Int
    ): RemoteScreenOrientation? =
        when {
            widthPx <= 0 || heightPx <= 0 -> null
            widthPx > heightPx -> RemoteScreenOrientation.LANDSCAPE
            heightPx > widthPx -> RemoteScreenOrientation.PORTRAIT
            else -> null
        }

    fun contentInsets(
        dockVisible: Boolean,
        dockOnRight: Boolean,
        dockWidthPx: Int,
        gapPx: Int
    ): RemoteContentInsets {
        if (!dockVisible || dockWidthPx <= 0) {
            return RemoteContentInsets(
                leftPx = 0,
                rightPx = 0
            )
        }

        val reserved =
            (dockWidthPx + gapPx.coerceAtLeast(0))
                .coerceAtLeast(0)

        return if (dockOnRight) {
            RemoteContentInsets(
                leftPx = 0,
                rightPx = reserved
            )
        } else {
            RemoteContentInsets(
                leftPx = reserved,
                rightPx = 0
            )
        }
    }
}
