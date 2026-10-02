package com.example.chat

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Mute is a notification-only setting.
 *
 * The dangerous failure mode is scope creep: if muting ever suppressed delivery
 * reporting or message sync, a muted family group would silently stall the
 * sender's ticks - the "fix one feature, break another" class this project has
 * hit before. The call sites are ordered so FcmRegistrar.reportDelivered() runs
 * before the mute check, and NotificationWorker still advances
 * lastNotifiedMessageId for muted conversations so unmuting does not deliver a
 * backlog.
 *
 * Instrumented because ChatPreferences is backed by SharedPreferences and there
 * is no Robolectric in this module.
 * Run with: gradlew :app:connectedBetaDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class ConversationMuteTest {

    private val ids = listOf(90_001L, 90_002L, 90_003L)

    private fun prefs() = ChatPreferences(ApplicationProvider.getApplicationContext())

    @After
    fun clearMuteState() {
        val p = prefs()
        ids.forEach { p.setConversationMuted(it, false) }
    }

    @Test
    fun conversationsAreUnmutedByDefault() {
        assertFalse(prefs().isConversationMuted(ids[0]))
    }

    @Test
    fun mutingIsPerConversation() {
        val p = prefs()
        p.setConversationMuted(ids[0], true)

        assertTrue(p.isConversationMuted(ids[0]))
        assertFalse("muting one conversation must not mute the others", p.isConversationMuted(ids[1]))
    }

    @Test
    fun mutingRoundTrips() {
        val p = prefs()
        p.setConversationMuted(ids[2], true)
        assertTrue(p.isConversationMuted(ids[2]))

        p.setConversationMuted(ids[2], false)
        assertFalse(p.isConversationMuted(ids[2]))
    }

    @Test
    fun invalidConversationIdsAreNeverConsideredMuted() {
        val p = prefs()
        p.setConversationMuted(0L, true)
        p.setConversationMuted(-5L, true)

        assertFalse(p.isConversationMuted(0L))
        assertFalse(p.isConversationMuted(-5L))
    }
}
