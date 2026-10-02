package com.example.chat

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessTokenFreshnessTest {
    @Test
    fun extractsJwtExpiration() {
        val token = tokenWithExpiry(1_800_000_000L)

        assertEquals(1_800_000_000_000L, accessTokenExpiresAtMs(token))
    }

    @Test
    fun refreshesTokenBeforeMinimumValidityWindow() {
        val now = 1_700_000_000_000L
        val token = tokenWithExpiry((now + 90_000L) / 1_000L)

        assertTrue(accessTokenNeedsRefresh(token, nowMs = now, minimumValidityMs = 120_000L))
    }

    @Test
    fun keepsTokenWithEnoughValidity() {
        val now = 1_700_000_000_000L
        val token = tokenWithExpiry((now + 10L * 60L * 1_000L) / 1_000L)

        assertFalse(accessTokenNeedsRefresh(token, nowMs = now, minimumValidityMs = 120_000L))
    }

    @Test
    fun malformedTokenIsTreatedAsNeedingRefresh() {
        assertNull(accessTokenExpiresAtMs("not-a-jwt"))
        assertTrue(accessTokenNeedsRefresh("not-a-jwt", nowMs = 1L))
    }

    private fun tokenWithExpiry(expirySeconds: Long): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("{\"alg\":\"none\"}".toByteArray(StandardCharsets.UTF_8))
        val payload = encoder.encodeToString(
            "{\"sub\":\"test\",\"exp\":$expirySeconds}".toByteArray(StandardCharsets.UTF_8),
        )
        return "$header.$payload.signature"
    }
}
