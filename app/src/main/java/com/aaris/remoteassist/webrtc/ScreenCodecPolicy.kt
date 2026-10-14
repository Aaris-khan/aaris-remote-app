package com.aaris.remoteassist.webrtc

import org.webrtc.RtpCapabilities

/** Prefer actual hardware support, while keeping negotiated baseline/VP8 safety paths. */
internal object ScreenCodecPolicy {
    fun preferred(hardware: Set<String>): String = if (hardware.any { it.equals("H264", true) }) "H264" else "VP8"

    /**
     * Called only after repeated requests without recent decode proof.
     * Reasserting the same codec does not recreate its stalled encoder/decoder.
     * Change the codec once; the normal factory still prefers hardware, while
     * software VP8 remains available when hardware H264 cannot start.
     * Renderer-only faults are filtered by the host before reaching this path.
     */
    fun recoveryCandidates(current: String): List<String> =
        listOf(if (current.equals("H264", true)) "VP8" else "H264")

    /**
     * Rank only H.264 profiles that pinned WebRTC's Android factories can
     * construct. Constrained High is published only when the selected hardware
     * encoder advertises WebRTC's guarded high-profile path; constrained
     * baseline remains immediately behind it for every peer/browser that does
     * not negotiate High.
     */
    private fun h264ProfileRank(
        codec: RtpCapabilities.CodecCapability
    ): Int? {
        if (
            !codec.name.equals("H264", true) ||
            codec.parameters["packetization-mode"] != "1"
        ) {
            return null
        }

        val profile =
            codec.parameters["profile-level-id"]
                ?.lowercase()
                ?: return null
        return when {
            profile.startsWith("640c") -> 0
            profile.startsWith("42") -> 1
            else -> null
        }
    }

    private fun preferredRank(
        codec: RtpCapabilities.CodecCapability,
        primary: String
    ): Int? {
        if (!codec.name.equals(primary, true)) return null
        return if (primary.equals("H264", true)) {
            h264ProfileRank(codec)
        } else {
            0
        }
    }

    fun order(codecs: List<RtpCapabilities.CodecCapability>, primary: String, exclusive: Boolean = false): List<RtpCapabilities.CodecCapability> {
        val payloadRanks =
            codecs.mapNotNull { codec ->
                preferredRank(codec, primary)?.let { rank ->
                    codec.preferredPayloadType.toString() to rank
                }
            }.toMap()
        if (payloadRanks.isEmpty()) {
            return if (exclusive) emptyList() else codecs
        }
        val lastPrimaryRank =
            payloadRanks.values.maxOrNull() ?: 0
        // Keep each RTX payload immediately after the profile it repairs.
        val protectionRank = (lastPrimaryRank + 1) * 2
        val fallbackRank = protectionRank + 1

        fun rank(codec: RtpCapabilities.CodecCapability): Int {
            preferredRank(codec, primary)?.let {
                return it * 2
            }
            if (codec.name.equals("RTX", true)) {
                payloadRanks[codec.parameters["apt"]]?.let {
                    return it * 2 + 1
                }
            }
            if (
                codec.name.uppercase() in
                setOf("RED", "ULPFEC", "FLEXFEC-03")
            ) {
                return protectionRank
            }
            if (codec.name.equals("VP8", true)) {
                return fallbackRank
            }
            h264ProfileRank(codec)?.let {
                return fallbackRank + 1 + it
            }
            return fallbackRank + 3
        }

        return codecs.withIndex().filter {
            !exclusive || rank(it.value) <= protectionRank
        }
            .sortedWith(compareBy({ rank(it.value) }, { it.index })).map { it.value }
    }

    fun remoteSupports(sdp: String, primary: String): Boolean {
        // Only inspect active video sections. Keep capability parsing independent
        // of transport/IP details, and never rewrite SDP strings.
        var video = false
        val lines = sdp.lineSequence().map(String::trim).toList()
        val videoLines = lines.filter { line ->
            if (line.startsWith("m=")) video = line.startsWith("m=video ") && !line.startsWith("m=video 0 ")
            video
        }
        val payloads = videoLines.mapNotNull { Regex("^a=rtpmap:(\\d+) ${Regex.escape(primary)}/90000(?:\\s.*)?$", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
        if (primary != "H264") return payloads.isNotEmpty()
        return payloads.any { pt -> videoLines.any { line ->
            if (!line.startsWith("a=fmtp:$pt ")) false else {
                val params = line.substringAfter(' ').split(';').map(String::trim)
                "packetization-mode=1" in params && params.any { it.startsWith("profile-level-id=42", true) }
            }
        } }
    }
}
