package com.aaris.remoteassist.webrtc

internal enum class MediaRecoveryAction {
    REFRESH_PRIMARY,
    COMPATIBILITY_ONLY,
    COMPATIBILITY_AND_CODEC
}

/** Bounded renderer grace; only an actual primary presentation ends recovery. */
internal class MediaRecoveryPolicy(private val rendererGraceMs: Long = 2_000L) {
    private var rendererRepairStartedAtMs: Long? = null
    private var compatibilityRequested = false

    fun request(decodedRecently: Boolean, nowMs: Long): MediaRecoveryAction {
        if (!decodedRecently) {
            compatibilityRequested = true
            return MediaRecoveryAction.COMPATIBILITY_AND_CODEC
        }
        // Decoder progress can resume before the EGL Surface presents. Do not
        // remove the only visible recovery picture on decode proof alone.
        if (compatibilityRequested) return MediaRecoveryAction.COMPATIBILITY_ONLY

        val startedAt = rendererRepairStartedAtMs
        if (startedAt == null || nowMs < startedAt) {
            rendererRepairStartedAtMs = nowMs
            return MediaRecoveryAction.REFRESH_PRIMARY
        }
        if (nowMs - startedAt < rendererGraceMs) return MediaRecoveryAction.REFRESH_PRIMARY

        compatibilityRequested = true
        return MediaRecoveryAction.COMPATIBILITY_ONLY
    }

    fun primaryPresented() {
        rendererRepairStartedAtMs = null
        compatibilityRequested = false
    }
}
