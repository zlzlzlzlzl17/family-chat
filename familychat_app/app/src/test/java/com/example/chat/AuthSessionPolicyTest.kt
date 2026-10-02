package com.example.chat

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthSessionPolicyTest {
    @Test
    fun networkAndServerFailuresKeepRefreshCredentials() {
        assertFalse(IOException("timeout").isDefinitiveSessionFailure())
        assertFalse(ChatApiException(500, "request_failed").isDefinitiveSessionFailure())
        assertFalse(ChatApiException(429, "cooldown_active").isDefinitiveSessionFailure())
    }

    @Test
    fun revokedOrExpiredSessionsRequireSignIn() {
        assertTrue(ChatApiException(401, "session_expired").isDefinitiveSessionFailure())
        assertTrue(ChatApiException(401, "device_revoked").isDefinitiveSessionFailure())
        assertTrue(ChatApiException(403, "account_unavailable").isDefinitiveSessionFailure())
        assertTrue(ChatApiException(400, "bad_refresh_request").isDefinitiveSessionFailure())
    }

    @Test
    fun onlyUnauthorizedAccessResponseTriggersRefresh() {
        assertTrue(ChatApiException(401, "unauthorized").requiresAccessTokenRefresh())
        assertFalse(ChatApiException(500, "request_failed").requiresAccessTokenRefresh())
        assertFalse(IOException("offline").requiresAccessTokenRefresh())
    }
}
