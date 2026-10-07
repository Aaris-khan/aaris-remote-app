package com.aaris.remoteassist.diagnostics

import android.os.SystemClock
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * In-process connection flight recorder shared by the foreground Activity,
 * MediaProjection service and WebRTC sessions.
 *
 * It deliberately stores only stage names/timing. No pairing codes, tokens,
 * SDP bodies, ICE addresses or remote screen content are recorded.
 */
object ConnectionFlightRecorder {
    private data class Trace(
        val startedAtMs: Long,
        val lines: ArrayDeque<String>,
        val live: LinkedHashMap<String, String> = LinkedHashMap()
    )

    private val traces = ConcurrentHashMap<String, Trace>()
    private val listeners =
        CopyOnWriteArraySet<(String, String) -> Unit>()

    @Synchronized
    fun reset(sessionId: String, side: String) {
        val trace = Trace(
            startedAtMs = SystemClock.elapsedRealtime(),
            lines = ArrayDeque()
        )
        traces[sessionId] = trace
        appendLocked(
            sessionId = sessionId,
            trace = trace,
            message = "$side monitor started",
            failure = false
        )
    }

    fun pass(sessionId: String, message: String) {
        if (message.startsWith(LIVE_PREFIX)) {
            updateLive(sessionId, message)
        } else {
            append(sessionId, message, false)
        }
    }

    fun fail(sessionId: String, message: String) {
        append(sessionId, message, true)
    }

    fun snapshot(sessionId: String): String =
        synchronized(this) {
            traces[sessionId]
                ?.let(::render)
                .orEmpty()
        }

    fun addListener(
        listener: (sessionId: String, report: String) -> Unit
    ) {
        listeners += listener
    }

    fun removeListener(
        listener: (sessionId: String, report: String) -> Unit
    ) {
        listeners -= listener
    }

    /**
     * Replace one live debug lane in-place instead of appending another history
     * event. HostWebRtcSession emits these from the already-running WebRTC health
     * callback, so the on-screen QD gains detail without another polling loop.
     *
     * Wire format: QD|<lane>|<one-or-multi-line text>
     */
    @Synchronized
    private fun updateLive(
        sessionId: String,
        message: String
    ) {
        val first = message.indexOf('|')
        val second = message.indexOf('|', first + 1)
        if (first < 0 || second <= first + 1 || second >= message.lastIndex) {
            append(sessionId, message, false)
            return
        }

        val lane = message.substring(first + 1, second).take(24)
        val value = message.substring(second + 1).take(MAX_LIVE_CHARS)
        val trace = traces.getOrPut(sessionId) {
            Trace(
                startedAtMs = SystemClock.elapsedRealtime(),
                lines = ArrayDeque()
            )
        }
        if (trace.live[lane] == value) return
        trace.live[lane] = value

        val report = render(trace)
        listeners.forEach { listener ->
            runCatching { listener(sessionId, report) }
        }
    }

    private fun render(trace: Trace): String =
        buildString {
            append(trace.lines.joinToString("\n"))
            if (trace.live.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append("── REALTIME QD ──")
                trace.live.values.forEach { value ->
                    append('\n')
                    append(value)
                }
            }
        }

    @Synchronized
    private fun append(
        sessionId: String,
        message: String,
        failure: Boolean
    ) {
        val trace = traces.getOrPut(sessionId) {
            Trace(
                startedAtMs = SystemClock.elapsedRealtime(),
                lines = ArrayDeque()
            )
        }
        appendLocked(sessionId, trace, message, failure)
    }

    private fun appendLocked(
        sessionId: String,
        trace: Trace,
        message: String,
        failure: Boolean
    ) {
        val elapsedMs =
            (SystemClock.elapsedRealtime() - trace.startedAtMs)
                .coerceAtLeast(0L)
        val seconds = elapsedMs / 1_000L
        val tenths = (elapsedMs % 1_000L) / 100L
        val marker = if (failure) "✕" else "✓"
        val line = "[+$seconds.$tenths s] $marker $message"

        if (trace.lines.lastOrNull() == line) return
        trace.lines.addLast(line)
        while (trace.lines.size > MAX_LINES) {
            trace.lines.removeFirst()
        }

        val report = render(trace)
        listeners.forEach { listener ->
            runCatching { listener(sessionId, report) }
        }
    }

    private const val MAX_LINES = 12
    private const val LIVE_PREFIX = "QD|"
    private const val MAX_LIVE_CHARS = 2_000
}
