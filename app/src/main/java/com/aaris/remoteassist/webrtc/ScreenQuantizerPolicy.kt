package com.aaris.remoteassist.webrtc

/**
 * Codec-aware interpretation of RTCStats qpSum/frame deltas.
 *
 * QP scales are codec-specific and must never be compared across codecs. These
 * thresholds mirror libwebrtc's established QualityScaler defaults for the two
 * codecs Aaris negotiates for the primary screen stream:
 *
 * - H.264: low 24, high 37
 * - VP8: low 29, high 95
 *
 * Unknown codecs or missing vendor qpSum telemetry intentionally produce no
 * adaptation signal, preserving the existing policy on OEM builds that do not
 * expose quantizer statistics.
 */
internal object ScreenQuantizerPolicy {
    private data class Thresholds(
        val low: Double,
        val high: Double
    )

    private val H264 = Thresholds(low = 24.0, high = 37.0)
    private val VP8 = Thresholds(low = 29.0, high = 95.0)

    private fun thresholds(codec: String?): Thresholds? {
        val normalized =
            codec
                ?.substringAfterLast('/')
                ?.trim()
                ?.uppercase()
                ?: return null

        return when (normalized) {
            "H264" -> H264
            "VP8" -> VP8
            else -> null
        }
    }

    /**
     * High quantizer means the current admitted pixels are being compressed
     * aggressively enough that text edges/detail can visibly break down.
     *
     * Use a strict greater-than comparison, matching libwebrtc QualityScaler's
     * high-QP trigger semantics.
     */
    fun isHighPressure(
        codec: String?,
        qpAverage: Double?
    ): Boolean {
        val value =
            qpAverage
                ?.takeIf { it.isFinite() && it >= 0.0 }
                ?: return false
        val threshold = thresholds(codec)?.high ?: return false
        return value > threshold
    }

    /**
     * Conservative recovery may use the established spatial upshift path once
     * known quantizer telemetry is no longer above libwebrtc's high threshold.
     * Missing telemetry must not regress existing OEM behavior.
     */
    fun isRecoverySafe(
        codec: String?,
        qpAverage: Double?
    ): Boolean {
        val value =
            qpAverage
                ?.takeIf { it.isFinite() && it >= 0.0 }
                ?: return true
        val threshold = thresholds(codec)?.high ?: return true
        return value <= threshold
    }

    /**
     * Fast pixel recovery is deliberately stricter: require known QP to reach
     * the codec's low-quality-scaler threshold before enlarging frames again.
     * This keeps a just-recovered path from bouncing 540 -> 720 -> 540 while
     * the encoder is still visibly starved for bits per pixel.
     */
    fun isFastRecoveryClean(
        codec: String?,
        qpAverage: Double?
    ): Boolean {
        val value =
            qpAverage
                ?.takeIf { it.isFinite() && it >= 0.0 }
                ?: return true
        val threshold = thresholds(codec)?.low ?: return true
        return value <= threshold
    }
}
