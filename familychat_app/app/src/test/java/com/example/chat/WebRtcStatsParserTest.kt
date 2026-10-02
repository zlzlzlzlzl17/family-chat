package com.example.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebRtcStatsParserTest {
    @Test
    fun parsesInboundAudioLossJitterAndSelectedPairRtt() {
        val quality = WebRtcStatsParser.parse(
            records = listOf(
                RtcStatRecord(
                    "inbound-rtp",
                    mapOf("kind" to "audio", "packetsReceived" to 990L, "packetsLost" to 10L, "jitter" to 0.012),
                ),
                RtcStatRecord(
                    "candidate-pair",
                    mapOf("state" to "succeeded", "nominated" to true, "currentRoundTripTime" to 0.085),
                ),
            ),
            sampledAt = 100L,
        )

        assertEquals(1.0, quality.packetLossPercent!!, 0.0001)
        assertEquals(12.0, quality.jitterMs!!, 0.0001)
        assertEquals(85.0, quality.roundTripTimeMs!!, 0.0001)
        assertEquals(100L, quality.sampledAt)
    }

    @Test
    fun missingStatsStayUnknownRatherThanReportingFalseZero() {
        val quality = WebRtcStatsParser.parse(emptyList(), sampledAt = 200L)

        assertNull(quality.packetLossPercent)
        assertNull(quality.jitterMs)
        assertNull(quality.roundTripTimeMs)
    }
}
