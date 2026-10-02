package com.example.chat

import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class PageUiStateStoreTest {
    @Test
    fun messageChanges_onlyInvalidateChatSnapshot() {
        val initial = ChatUiState(isLoggedIn = true)
        val store = PageUiStateStore(initial)
        val shellBefore = store.shell
        val homeBefore = store.home
        val settingsBefore = store.settings
        val managementBefore = store.management
        val chatBefore = store.chat

        store.publish(initial.copy(messages = listOf(message(1L))))

        assertNotSame(chatBefore, store.chat)
        assertSame(shellBefore, store.shell)
        assertSame(homeBefore, store.home)
        assertSame(settingsBefore, store.settings)
        assertSame(managementBefore, store.management)
    }

    @Test
    fun conversationChanges_invalidateOnlyConversationSurfaces() {
        val initial = ChatUiState(isLoggedIn = true)
        val store = PageUiStateStore(initial)
        val shellBefore = store.shell
        val loginBefore = store.login
        val settingsBefore = store.settings
        val homeBefore = store.home
        val chatBefore = store.chat
        val managementBefore = store.management

        store.publish(initial.copy(currentConversationId = 42L))

        assertNotSame(homeBefore, store.home)
        assertNotSame(chatBefore, store.chat)
        assertNotSame(managementBefore, store.management)
        assertSame(shellBefore, store.shell)
        assertSame(loginBefore, store.login)
        assertSame(settingsBefore, store.settings)
    }

    private fun message(id: Long) = ChatMessage(
        id = id,
        conversationId = 42L,
        ts = 1L,
        expiresAt = 0L,
        userCode = "12345678",
        username = "test",
        color = "#128C7E",
        kind = "text",
        payload = "ciphertext",
        e2ee = true,
        replyTo = null,
        mentions = emptyList(),
    )
}
