package com.aaris.remoteassist.webrtc

internal enum class MediaRecoveryAction {
    REFRESH_PRIMARY,
    COMPATIBILITY_ONLY,
    COMPATIBILITY_AND_CODEC
}

/** Bounded renderer grace; only an actual primary presentation ends recovery. */
internal class MediaRecoveryPolicy(
    private val rendererGraceMs: Long = 2_000L,
    private val compatibilityCodecEscapeMs: Long = 4_000L
) {
    private var rendererRepairStartedAtMs: Long? = null
    private var compatibilityRequested = false
    private var compatibilityStartedAtMs: Long? = null

    fun request(decodedRecently: Boolean, nowMs: Long): MediaRecoveryAction {
        if (!decodedRecently) {
            enterCompatibility(nowMs)
            return MediaRecoveryAction.COMPATIBILITY_AND_CODEC
        }
        // Decoder progress can resume before the EGL Surface presents. Do not
        // remove the only visible recovery picture on decode proof alone.
        if (compatibilityRequested) {
            val startedAt = compatibilityStartedAtMs
            return if (
                startedAt != null &&
                nowMs >= startedAt &&
                nowMs - startedAt >= compatibilityCodecEscapeMs
            ) {
                /*
                 * A hardware decoder can advance getStats while its texture
                 * output never reaches this device's EGL surface. Decoder
                 * progress alone must not pin the viewer to the 2-10 fps JPEG/
                 * delta recovery lane forever. After one bounded visible-
                 * recovery window, allow exactly the existing one-shot codec
                 * repair to try the negotiated alternate (normally VP8).
                 */
                MediaRecoveryAction.COMPATIBILITY_AND_CODEC
            } else {
                MediaRecoveryAction.COMPATIBILITY_ONLY
            }
        }

        val startedAt = rendererRepairStartedAtMs
        if (startedAt == null || nowMs < startedAt) {
            rendererRepairStartedAtMs = nowMs
            return MediaRecoveryAction.REFRESH_PRIMARY
        }
        if (nowMs - startedAt < rendererGraceMs) return MediaRecoveryAction.REFRESH_PRIMARY

        enterCompatibility(nowMs)
        return MediaRecoveryAction.COMPATIBILITY_ONLY
    }

    fun primaryPresented() {
        rendererRepairStartedAtMs = null
        compatibilityRequested = false
        compatibilityStartedAtMs = null
    }

    private fun enterCompatibility(nowMs: Long) {
        compatibilityRequested = true
        val startedAt = compatibilityStartedAtMs
        if (startedAt == null || nowMs < startedAt) {
            compatibilityStartedAtMs = nowMs
        }
    }
}
