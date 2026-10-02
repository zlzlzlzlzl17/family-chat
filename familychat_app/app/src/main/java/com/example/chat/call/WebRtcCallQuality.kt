package com.example.chat

import kotlin.math.max

data class WebRtcCallQuality(
    val packetLossPercent: Double? = null,
    val jitterMs: Double? = null,
    val roundTripTimeMs: Double? = null,
    val sampledAt: Long = 0L,
) {
    val hasMetrics: Boolean
        get() = packetLossPercent != null || jitterMs != null || roundTripTimeMs != null
}

internal data class RtcStatRecord(
    val type: String,
    val members: Map<String, Any?>,
)

internal object WebRtcStatsParser {
    fun parse(records: Collection<RtcStatRecord>, sampledAt: Long = System.currentTimeMillis()): WebRtcCallQuality {
        val inboundAudio = records.firstOrNull { record ->
            record.type == "inbound-rtp" &&
                !record.boolean("isRemote") &&
                (record.string("kind") == "audio" || record.string("mediaType") == "audio")
        }
        val received = inboundAudio?.number("packetsReceived")?.toDouble()?.coerceAtLeast(0.0)
        val lost = inboundAudio?.number("packetsLost")?.toDouble()?.coerceAtLeast(0.0)
        val packetLoss = if (received != null && lost != null && received + lost > 0.0) {
            (lost / (received + lost) * 100.0).coerceIn(0.0, 100.0)
        } else {
            null
        }
        val jitterMs = inboundAudio?.number("jitter")?.toDouble()?.let { max(0.0, it * 1_000.0) }

        val candidatePair = records.firstOrNull { record ->
            record.type == "candidate-pair" &&
                (record.boolean("selected") || record.boolean("nominated") || record.string("state") == "succeeded") &&
                record.number("currentRoundTripTime") != null
        }
        val remoteInbound = records.firstOrNull { record ->
            record.type == "remote-inbound-rtp" &&
                (record.string("kind") == "audio" || record.string("mediaType") == "audio") &&
                record.number("roundTripTime") != null
        }
        val rttSeconds = candidatePair?.number("currentRoundTripTime")?.toDouble()
            ?: remoteInbound?.number("roundTripTime")?.toDouble()
        val rttMs = rttSeconds?.let { max(0.0, it * 1_000.0) }

        return WebRtcCallQuality(
            packetLossPercent = packetLoss,
            jitterMs = jitterMs,
            roundTripTimeMs = rttMs,
            sampledAt = sampledAt,
        )
    }

    private fun RtcStatRecord.number(name: String): Number? = members[name] as? Number
    private fun RtcStatRecord.string(name: String): String = members[name]?.toString().orEmpty()
    private fun RtcStatRecord.boolean(name: String): Boolean = when (val value = members[name]) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        else -> value?.toString()?.equals("true", ignoreCase = true) == true
    }
}
