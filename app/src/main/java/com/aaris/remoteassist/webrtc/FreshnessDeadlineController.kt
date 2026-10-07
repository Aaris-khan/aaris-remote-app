package com.aaris.remoteassist.webrtc

/**
 * One control signal for interactive-video staleness.
 *
 * Big realtime systems do not try to make propagation disappear. They separate
 * latency we can control (codec + pacer + playout/decode residence) from path
 * propagation we cannot. This controller therefore budgets only queueable work.
 *
 * The budget is frame-relative so 60 fps reacts before several obsolete visual
 * states accumulate, while 24/30 fps gets proportionally more room. Complete
 * evidence requires both sender-pacer and receiver-playout residence; when one
 * side does not expose the stat, callers keep their legacy per-layer fallback.
 */
internal object FreshnessDeadlineController {
    internal enum class State {
        UNKNOWN,
        CLEAN,
        WATCH,
        PRESSURE,
        URGENT,
        SEVERE
    }

    internal data class Assessment(
        val state: State,
        val framePeriodMs: Double,
        val deadlineMs: Double,
        val controllableDebtMs: Double?,
        val utilization: Double?,
        val complete: Boolean
    ) {
        val pressure: Boolean
            get() =
                state == State.PRESSURE ||
                    state == State.URGENT ||
                    state == State.SEVERE

        val urgent: Boolean
            get() = state == State.URGENT || state == State.SEVERE

        val severe: Boolean
            get() = state == State.SEVERE

        fun compact(): String {
            val debt = controllableDebtMs?.let { "${Math.round(it)}ms" } ?: "-"
            val util = utilization?.let { "${Math.round(it * 100.0)}%" } ?: "-"
            return "deadline=${state.name.lowercase()} debt=$debt budget=${Math.round(deadlineMs)}ms util=$util"
        }
    }

    fun assess(
        targetFps: Int,
        sender: VideoHealthWindow?,
        receiver: VideoHealthWindow?
    ): Assessment {
        val framePeriodMs = 1_000.0 / targetFps.coerceAtLeast(1)
        val deadlineMs =
            (framePeriodMs * DEADLINE_FRAME_PERIODS)
                .coerceIn(MIN_DEADLINE_MS, MAX_DEADLINE_MS)

        val sendQueueMs = sender?.sendQueueMs.valid()
        val playoutMs = receiver?.playoutDelayMs.valid()
        val complete = sendQueueMs != null && playoutMs != null

        if (!complete) {
            return Assessment(
                state = State.UNKNOWN,
                framePeriodMs = framePeriodMs,
                deadlineMs = deadlineMs,
                controllableDebtMs = null,
                utilization = null,
                complete = false
            )
        }

        // Codec times are useful debt when exposed, but absence must not make
        // the whole controller blind because pacer/playout residence are the
        // dominant queueing signals and are required above.
        val encodeMs = sender?.processingMs.valid() ?: 0.0
        val decodeMs = receiver?.processingMs.valid() ?: 0.0
        val debtMs = encodeMs + checkNotNull(sendQueueMs) + decodeMs + checkNotNull(playoutMs)
        val utilization = debtMs / deadlineMs

        val state =
            when {
                utilization >= SEVERE_UTILIZATION -> State.SEVERE
                utilization >= URGENT_UTILIZATION -> State.URGENT
                utilization >= PRESSURE_UTILIZATION -> State.PRESSURE
                utilization >= WATCH_UTILIZATION -> State.WATCH
                else -> State.CLEAN
            }

        return Assessment(
            state = state,
            framePeriodMs = framePeriodMs,
            deadlineMs = deadlineMs,
            controllableDebtMs = debtMs,
            utilization = utilization,
            complete = true
        )
    }

    private fun Double?.valid(): Double? =
        this?.takeIf { it.isFinite() && it >= 0.0 }

    private const val DEADLINE_FRAME_PERIODS = 3.0
    private const val MIN_DEADLINE_MS = 50.0
    private const val MAX_DEADLINE_MS = 180.0

    private const val WATCH_UTILIZATION = 0.80
    private const val PRESSURE_UTILIZATION = 1.00
    private const val URGENT_UTILIZATION = 2.00
    private const val SEVERE_UTILIZATION = 4.00
}
