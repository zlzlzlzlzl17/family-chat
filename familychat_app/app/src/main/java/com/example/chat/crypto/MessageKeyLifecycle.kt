package com.example.chat

internal data class ExpiringMessageKey(
    val value: String,
    val expiresAt: Long,
)

internal object MessageKeyLifecycle {
    const val UNCONFIRMED_KEY_RETENTION_MS = 7L * 24L * 60L * 60L * 1000L
    const val LEGACY_MIGRATION_RETENTION_MS = 7L * 24L * 60L * 60L * 1000L
    private const val FORMAT_PREFIX = "mk1:"

    val storagePrefixes = listOf(
        "direct_v2_sent_key_",
        "direct_v2_recv_key_",
        "direct_sent_key_",
        "direct_recv_key_",
        "attachment_file_key_",
    )

    fun effectiveExpiry(
        messageExpiresAt: Long,
        @Suppress("UNUSED_PARAMETER") now: Long = System.currentTimeMillis(),
    ): Long = messageExpiresAt.coerceAtLeast(0L)

    fun legacyFallbackExpiry(now: Long = System.currentTimeMillis()): Long =
        now + LEGACY_MIGRATION_RETENTION_MS

    fun unconfirmedKeyExpiry(now: Long = System.currentTimeMillis()): Long =
        now + UNCONFIRMED_KEY_RETENTION_MS

    fun encode(value: String, expiresAt: Long): String =
        "$FORMAT_PREFIX$expiresAt:$value"

    fun decode(encoded: String): ExpiringMessageKey? {
        if (!encoded.startsWith(FORMAT_PREFIX)) return null
        val separator = encoded.indexOf(':', FORMAT_PREFIX.length)
        if (separator < 0) return null
        val expiresAt = encoded.substring(FORMAT_PREFIX.length, separator).toLongOrNull() ?: return null
        return ExpiringMessageKey(
            value = encoded.substring(separator + 1),
            expiresAt = expiresAt,
        )
    }

    fun isExpired(key: ExpiringMessageKey, now: Long = System.currentTimeMillis()): Boolean =
        key.expiresAt > 0L && key.expiresAt <= now
}
