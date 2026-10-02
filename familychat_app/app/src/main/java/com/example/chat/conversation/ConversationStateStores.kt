package com.example.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

internal data class ConversationSelection(
    val conversationId: Long = 0L,
    val forceRefresh: Boolean = false,
    val generation: Long = 0L,
)

internal class ConversationSelectionStore {
    private val generation = AtomicLong(0L)
    private val mutableSelection = MutableStateFlow(ConversationSelection())
    val selection: StateFlow<ConversationSelection> = mutableSelection.asStateFlow()

    fun select(conversationId: Long, forceRefresh: Boolean = false) {
        if (conversationId <= 0L) return
        mutableSelection.value = ConversationSelection(
            conversationId = conversationId,
            forceRefresh = forceRefresh,
            generation = generation.incrementAndGet(),
        )
    }

    fun clear() {
        mutableSelection.value = ConversationSelection(generation = generation.incrementAndGet())
    }
}

internal class RealtimeConnectionStore {
    private val mutableStatus = MutableStateFlow(ConnectionStatus.OFFLINE)
    val status: StateFlow<ConnectionStatus> = mutableStatus.asStateFlow()

    fun publish(status: ConnectionStatus) {
        mutableStatus.value = status
    }
}
