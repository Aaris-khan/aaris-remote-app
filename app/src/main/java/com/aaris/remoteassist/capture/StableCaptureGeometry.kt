package com.aaris.remoteassist.capture

/**
 * Owns the MediaProjection producer surface geometry.
 *
 * Before Android 12, WebRTC recreates the VirtualDisplay for every format
 * change and some OEMs can mirror it with the wrong scale. Keep the original
 * source pixel budget on those devices and resize only for real display
 * geometry changes.
 *
 * Android 12+ uses VirtualDisplay.resize() in ScreenCapturerAndroid. There the
 * producer may follow the application's already-hysteresis-gated spatial
 * envelope so pixel relief happens before texture production, not only after a
 * full-size frame reaches VideoSource.
 */
internal class StableCaptureGeometry(initial: CaptureProfile) {
    /*
     * Pre-Android-12 ScreenCapturerAndroid may recreate the VirtualDisplay on a
     * format change and historically exposed vendor scaling regressions. Keep
     * that legacy path bounded by the producer size admitted at session start.
     *
     * Android 12+ is different: the caller explicitly opts into adaptive
     * producer resizing after hysteresis/capability checks. Do not reuse this
     * bootstrap ceiling there, otherwise an earned LOW -> BALANCED or
     * 360 -> 540 recovery only raises VideoSource's requested output while the
     * MediaProjection producer remains stuck at the old low-resolution pixels.
     */
    private val legacyProducerLongSide =
        maxOf(initial.captureWidthPx, initial.captureHeightPx)
    private var displayWidth = initial.displayWidthPx
    private var displayHeight = initial.displayHeightPx

    var width = initial.captureWidthPx
        private set

    var height = initial.captureHeightPx
        private set

    fun update(
        profile: CaptureProfile,
        allowAdaptiveResize: Boolean
    ): Boolean {
        displayWidth = profile.displayWidthPx
        displayHeight = profile.displayHeightPx

        val requestedWidth: Int
        val requestedHeight: Int
        if (allowAdaptiveResize) {
            requestedWidth = profile.captureWidthPx
            requestedHeight = profile.captureHeightPx
        } else {
            requestedWidth = displayWidth
            requestedHeight = displayHeight
        }

        /*
         * Adaptive Android-12+ requests are already bounded by CaptureProfile
         * and MotionVideoBudget, but clamp once more at this producer boundary:
         * recovery may grow above the initial capture size, never above the
         * physical display itself. The legacy path retains its original
         * long-side ceiling and therefore cannot accidentally pay for a
         * full-resolution VirtualDisplay recreate.
         */
        val requestedLongSide =
            maxOf(requestedWidth, requestedHeight)
                .coerceAtLeast(1)
        val scale =
            if (allowAdaptiveResize) {
                1.0
            } else {
                minOf(
                    1.0,
                    legacyProducerLongSide.toDouble() /
                        requestedLongSide.toDouble()
                )
            }

        fun even(value: Int, displayLimit: Int): Int {
            val bounded =
                minOf(value, displayLimit)
                    .coerceAtLeast(2)
            return ((bounded * scale).toInt() / 2 * 2)
                .coerceAtLeast(2)
        }

        val nextWidth = even(requestedWidth, displayWidth)
        val nextHeight = even(requestedHeight, displayHeight)
        val changed = nextWidth != width || nextHeight != height
        width = nextWidth
        height = nextHeight
        return changed
    }
}
