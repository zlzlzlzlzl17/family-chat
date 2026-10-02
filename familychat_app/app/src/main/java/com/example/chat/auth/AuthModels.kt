package com.example.chat

import java.nio.charset.StandardCharsets
import java.util.Base64

data class LoginResult(
    val token: String,
    val refreshToken: String = "",
    val sessionId: String = "",
    val deviceStatus: String = "trusted",
    val userCode: String = "",
    val username: String,
    val color: String,
    val avatarUrl: String,
    val isAdmin: Boolean,
)

data class RegistrationRequestResult(
    val requestToken: String,
)

data class RegistrationRequestStatus(
    val status: String,
    val userCode: String,
    val reviewNote: String,
)

data class SessionStatus(
    val userCode: String,
    val username: String,
    val deviceId: String,
    val deviceStatus: String,
    val sessionStatus: String,
)

internal enum class SessionRefreshOutcome {
    TRUSTED,
    PENDING,
    TRANSIENT_FAILURE,
    EXPIRED,
}

internal fun Throwable.isDefinitiveSessionFailure(): Boolean = when (this) {
    is ChatApiException ->
        statusCode == 401 || errorCode in setOf(
            "session_expired",
            "device_revoked",
            "account_unavailable",
            "bad_refresh_request",
        )
    is IllegalArgumentException -> message == "session_expired"
    else -> false
}

internal fun Throwable.requiresAccessTokenRefresh(): Boolean =
    this is ChatApiException && statusCode == 401

internal fun accessTokenExpiresAtMs(token: String): Long? = runCatching {
    val payload = token.split('.').getOrNull(1).orEmpty()
    require(payload.isNotBlank())
    val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
    val decoded = String(Base64.getUrlDecoder().decode(padded), StandardCharsets.UTF_8)
    val seconds = Regex("\\\"exp\\\"\\s*:\\s*(\\d+)")
        .find(decoded)
        ?.groupValues
        ?.getOrNull(1)
        ?.toLongOrNull()
        ?: return@runCatching null
    seconds * 1_000L
}.getOrNull()

internal fun accessTokenNeedsRefresh(
    token: String,
    nowMs: Long = System.currentTimeMillis(),
    minimumValidityMs: Long = 2L * 60L * 1_000L,
): Boolean {
    val expiresAt = accessTokenExpiresAtMs(token) ?: return true
    return expiresAt <= nowMs + minimumValidityMs
}
