package com.example.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncRetryPolicyTest {
    @Test
    fun delayUsesBoundedExponentialBackoff() {
        val delays = (0..12).map { SyncRetryPolicy.delayMs(it) }
        assertEquals(1_000L, delays.first())
        assertTrue(delays.zipWithNext().all { (left, right) -> right >= left })
        assertTrue(delays.all { it <= 5L * 60L * 1_000L })
        assertEquals(5L * 60L * 1_000L, delays.last())
    }

    @Test
    fun deterministicJitterNeverExceedsMaximum() {
        val first = SyncRetryPolicy.delayMs(3, jitterSeed = -42L)
        val second = SyncRetryPolicy.delayMs(3, jitterSeed = -42L)
        assertEquals(first, second)
        assertTrue(first in 8_000L..(5L * 60L * 1_000L))
    }
}
