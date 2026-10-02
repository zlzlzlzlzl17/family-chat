package com.example.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageKeyLifecycleTest {
    @Test
    fun encodedKeyRoundTripsWithoutChangingItsValue() {
        val encoded = MessageKeyLifecycle.encode("base64:value:with:separators", 42_000L)

        assertEquals(
            ExpiringMessageKey("base64:value:with:separators", 42_000L),
            MessageKeyLifecycle.decode(encoded),
        )
    }

    @Test
    fun legacyRawValueIsNotMistakenForVersionedKey() {
        assertNull(MessageKeyLifecycle.decode("legacy-base64-value"))
    }

    @Test
    fun attachmentFileKeysParticipateInTheSameCleanupPolicy() {
        assertTrue(MessageKeyLifecycle.storagePrefixes.contains("attachment_file_key_"))
    }

    @Test
    fun messageExpiryIsPreservedAndZeroMeansNoAutomaticExpiry() {
        assertEquals(12_345L, MessageKeyLifecycle.effectiveExpiry(12_345L, now = 1_000L))
        assertEquals(0L, MessageKeyLifecycle.effectiveExpiry(0L, now = 1_000L))
    }

    @Test
    fun legacyValueGetsBoundedMigrationWindow() {
        assertEquals(
            1_000L + MessageKeyLifecycle.LEGACY_MIGRATION_RETENTION_MS,
            MessageKeyLifecycle.legacyFallbackExpiry(1_000L),
        )
    }

    @Test
    fun unconfirmedOutgoingKeyGetsBoundedRetention() {
        assertEquals(
            1_000L + MessageKeyLifecycle.UNCONFIRMED_KEY_RETENTION_MS,
            MessageKeyLifecycle.unconfirmedKeyExpiry(1_000L),
        )
    }

    @Test
    fun expirationBoundaryIsInclusiveButZeroDoesNotExpire() {
        assertFalse(MessageKeyLifecycle.isExpired(ExpiringMessageKey("key", 0L), now = Long.MAX_VALUE))
        assertFalse(MessageKeyLifecycle.isExpired(ExpiringMessageKey("key", 1_001L), now = 1_000L))
        assertTrue(MessageKeyLifecycle.isExpired(ExpiringMessageKey("key", 1_000L), now = 1_000L))
    }
}
