package com.aaris.remoteassist.control

/**
 * One-clock applied-ACK-to-next-swap responsiveness proxy.
 *
 * We cannot prove which encoded frame first contains a UI mutation without
 * putting application metadata into the video bitstream. Instead, authoritative
 * commands already receive CommandResult after the host applies them. The first
 * real EGL swap observed after that ACK measures this ordering:
 *
 *   input sent -> host applied -> ACK received -> next EGL swap
 *
 * That swap may still show a frame captured BEFORE the command, especially with
 * receiver buffering. It is neither a bound nor proof of the action becoming
 * visible. Keep the legacy field/wire names for compatibility; diagnostics must
 * call this a proxy. True input-to-photon needs a frame/action correlation marker
 * or an external optical measurement. It remains only a promotion gate.
 */
internal data class InputToGlassSample(
    val sequence: Long,
    val upperBoundMs: Int,
    val commandAckMs: Int,
    val postAckPresentMs: Int
)

internal class InputToGlassTracker {
    private var pending: AppliedCommandAck? = null
    private var lastCompletedSequence = 0L
    private val upperBoundSamples = ArrayDeque<Int>()
    private val postAckSamples = ArrayDeque<Int>()

    @Synchronized
    fun applied(ack: AppliedCommandAck) {
        if (
            ack.sequence <= lastCompletedSequence ||
            ack.ackLatencyMs !in 0..MAX_INPUT_TO_GLASS_MS
        ) {
            return
        }

        val current = pending
        if (current == null || ack.sequence > current.sequence) {
            // Freshness-first: if two authoritative commands are applied before
            // the next swap, the newer command is the one the user now cares about.
            pending = ack
        }
    }

    @Synchronized
    fun onPresented(presentedAtMs: Long): InputToGlassSample? {
        val ack = pending ?: return null
        if (presentedAtMs < ack.acknowledgedAtMs) return null

        val upper = presentedAtMs - ack.sentAtMs
        val postAck = presentedAtMs - ack.acknowledgedAtMs
        if (
            upper !in 0..MAX_INPUT_TO_GLASS_MS ||
            postAck !in 0..MAX_POST_ACK_PRESENT_MS
        ) {
            pending = null
            lastCompletedSequence = maxOf(lastCompletedSequence, ack.sequence)
            return null
        }

        pending = null
        lastCompletedSequence = maxOf(lastCompletedSequence, ack.sequence)

        val sample =
            InputToGlassSample(
                sequence = ack.sequence,
                upperBoundMs = upper.toInt(),
                commandAckMs = ack.ackLatencyMs.toInt(),
                postAckPresentMs = postAck.toInt()
            )
        upperBoundSamples.addLast(sample.upperBoundMs)
        postAckSamples.addLast(sample.postAckPresentMs)
        while (upperBoundSamples.size > MAX_SAMPLES) upperBoundSamples.removeFirst()
        while (postAckSamples.size > MAX_SAMPLES) postAckSamples.removeFirst()
        return sample
    }

    @Synchronized
    fun summary(): String {
        if (upperBoundSamples.isEmpty()) {
            return "ACK/swap proxy: waiting for applied input + EGL swap"
        }

        val upper = upperBoundSamples.sorted()
        val postAck = postAckSamples.sorted()
        fun percentile(values: List<Int>, percent: Int): Int =
            values[
                ((values.size * percent + 99) / 100 - 1)
                    .coerceIn(0, values.lastIndex)
            ]

        return "ACK/swap proxy p50=${percentile(upper, 50)}ms " +
            "p95=${percentile(upper, 95)}ms " +
            "postAck p95=${percentile(postAck, 95)}ms " +
            "n=${upper.size}"
    }

    @Synchronized
    fun reset() {
        pending = null
        lastCompletedSequence = 0L
        upperBoundSamples.clear()
        postAckSamples.clear()
    }

    private companion object {
        const val MAX_INPUT_TO_GLASS_MS = 30_000L
        const val MAX_POST_ACK_PRESENT_MS = 5_000L
        const val MAX_SAMPLES = 64
    }
}
