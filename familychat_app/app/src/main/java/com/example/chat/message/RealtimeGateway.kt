package com.example.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/** A single application-wide WebSocket. Incoming message state is committed before events are emitted. */
internal class RealtimeGateway(
    private val auth: AuthSessionManager,
    private val repository: MessageRepository,
    private val local: LocalChatRepository,
    private val connectionStore: RealtimeConnectionStore,
    private val scope: CoroutineScope,
) {
    private val mutableEvents = MutableSharedFlow<String>(extraBufferCapacity = 256)
    @Volatile
    private var socket: WebSocket? = null
    @Volatile
    private var connecting = false
    @Volatile
    private var allowReconnect = false
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    private var handshakeTimeoutJob: Job? = null
    @Volatile
    private var socketCredentialVersion = 0L
    @Volatile
    private var desiredPresence = "background"

    val events: SharedFlow<String> = mutableEvents.asSharedFlow()

    init {
        scope.launch {
            auth.state.collect { state ->
                if (state.phase == AuthSessionPhase.AUTHENTICATED) {
                    allowReconnect = true
                    val replaceCredentials =
                        (socket != null || connecting) && socketCredentialVersion != state.credentialVersion
                    connect(force = replaceCredentials)
                } else {
                    disconnect()
                }
            }
        }
    }

    @Synchronized
    fun connect(force: Boolean = false) {
        val session = auth.sessionSnapshot()
        if (!session.isReady) return
        allowReconnect = true
        if (force) {
            reconnectJob?.cancel()
            reconnectJob = null
            handshakeTimeoutJob?.cancel()
            handshakeTimeoutJob = null
            socket?.cancel()
            socket = null
            connecting = false
            connectionStore.publish(ConnectionStatus.RECONNECTING)
            FamilyChatDiagnostics.sampled("realtime_force_reconnect", 2_000L)
        }
        if (socket != null || connecting) return
        connecting = true
        socketCredentialVersion = auth.state.value.credentialVersion
        connectionStore.publish(ConnectionStatus.RECONNECTING)
        val credentialVersion = socketCredentialVersion
        val candidate = runCatching {
            repository.connect(
                session.serverUrl,
                session.accessToken,
                object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    scope.launch {
                        val accepted = synchronized(this@RealtimeGateway) {
                            if (socket !== webSocket || socketCredentialVersion != credentialVersion) {
                                false
                            } else {
                                connecting = false
                                reconnectAttempts = 0
                                reconnectJob?.cancel()
                                reconnectJob = null
                                handshakeTimeoutJob?.cancel()
                                handshakeTimeoutJob = null
                                true
                            }
                        }
                        if (!accepted) {
                            webSocket.cancel()
                            return@launch
                        }
                        connectionStore.publish(ConnectionStatus.CONNECTED)
                        webSocket.send(
                            JSONObject().put("type", "presence").put("state", desiredPresence).toString()
                        )
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    scope.launch { persistThenEmit(text) }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    handleDisconnect(webSocket)
                }

                override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                    FamilyChatDiagnostics.sampled(
                        "realtime_socket_failure",
                        10_000L,
                        "error" to error.javaClass.simpleName,
                    )
                    if (response?.code == 401) {
                        auth.requestAccessTokenRefresh("websocket_unauthorized")
                    }
                    handleDisconnect(webSocket)
                }
                },
            )
        }.getOrElse { error ->
            connecting = false
            socketCredentialVersion = 0L
            FamilyChatDiagnostics.sampled(
                "realtime_connect_failed",
                10_000L,
                "error" to error.javaClass.simpleName,
            )
            connectionStore.publish(ConnectionStatus.RECONNECTING)
            scheduleReconnect()
            return
        }
        socket = candidate
        scheduleHandshakeTimeout(candidate)
    }

    fun send(payload: String): Boolean = socket?.send(payload) == true

    fun send(payload: JSONObject): Boolean = send(payload.toString())

    fun setPresence(state: String) {
        desiredPresence = state
        send(JSONObject().put("type", "presence").put("state", state))
    }

    @Synchronized
    fun disconnect() {
        allowReconnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        handshakeTimeoutJob?.cancel()
        handshakeTimeoutJob = null
        connecting = false
        socket?.close(1000, "session_end")
        socket = null
        socketCredentialVersion = 0L
        connectionStore.publish(ConnectionStatus.OFFLINE)
    }

    private fun handleDisconnect(disconnected: WebSocket) {
        scope.launch {
            val reconnect = synchronized(this@RealtimeGateway) {
                if (socket !== disconnected) {
                    false
                } else {
                    socket = null
                    connecting = false
                    socketCredentialVersion = 0L
                    handshakeTimeoutJob?.cancel()
                    handshakeTimeoutJob = null
                    allowReconnect
                }
            }
            if (!reconnect && !allowReconnect) {
                connectionStore.publish(ConnectionStatus.OFFLINE)
                return@launch
            }
            if (socket !== null) return@launch
            connectionStore.publish(if (allowReconnect) ConnectionStatus.RECONNECTING else ConnectionStatus.OFFLINE)
            scheduleReconnect()
        }
    }

    @Synchronized
    private fun scheduleReconnect() {
        if (!allowReconnect || reconnectJob?.isActive == true || !auth.sessionSnapshot().isReady) return
        reconnectJob = scope.launch {
            while (allowReconnect && socket == null && auth.sessionSnapshot().isReady) {
                reconnectAttempts += 1
                val delayMs = SyncRetryPolicy.delayMs(reconnectAttempts.coerceAtMost(6), reconnectAttempts.toLong())
                    .coerceAtMost(15_000L)
                delay(delayMs)
                if (!allowReconnect || socket != null) return@launch
                connect()
                delay(1_000L)
            }
        }
    }

    private fun scheduleHandshakeTimeout(candidate: WebSocket) {
        handshakeTimeoutJob?.cancel()
        handshakeTimeoutJob = scope.launch {
            delay(12_000L)
            val timedOut = synchronized(this@RealtimeGateway) {
                if (socket !== candidate || !connecting) {
                    false
                } else {
                    socket = null
                    connecting = false
                    socketCredentialVersion = 0L
                    true
                }
            }
            if (!timedOut) return@launch
            candidate.cancel()
            FamilyChatDiagnostics.event("realtime_handshake_timeout")
            connectionStore.publish(ConnectionStatus.RECONNECTING)
            scheduleReconnect()
        }
    }

    private suspend fun persistThenEmit(text: String) {
        val session = auth.sessionSnapshot()
        if (!session.isReady) return
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (json.optString("type")) {
            "chat" -> {
                val message = ChatApi.parseMessage(json)
                local.mergeRemotePage(
                    ownerId = session.ownerId,
                    conversationId = message.conversationId,
                    messages = listOf(message),
                    deliveryStates = emptyMap(),
                    readStates = emptyMap(),
                    hasMoreBefore = local.syncCursor(session.ownerId, message.conversationId).hasMoreBefore,
                )
                val ownCode = auth.state.value.me?.userCode.orEmpty()
                if (message.userCode.isNotBlank() && message.userCode != ownCode) {
                    val identity = ownCode.ifBlank { auth.state.value.me?.username.orEmpty() }
                    if (identity.isNotBlank()) {
                        local.queueReceipt(
                            ownerId = session.ownerId,
                            conversationId = message.conversationId,
                            participantIdentity = identity,
                            deliveredMessageId = message.id,
                            readMessageId = 0L,
                        )
                    }
                    send(
                        JSONObject()
                            .put("type", "delivered")
                            .put("conversation_id", message.conversationId)
                            .put("last_delivered_message_id", message.id)
                    )
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            repository.markDelivered(
                                session.serverUrl,
                                session.accessToken,
                                message.conversationId,
                                message.id,
                            )
                            if (identity.isNotBlank()) {
                                local.acknowledgeReceipt(
                                    session.ownerId,
                                    message.conversationId,
                                    identity,
                                )
                            }
                        }
                    }
                }
            }
            "message_recalled" -> {
                val message = json.optJSONObject("message")?.let(ChatApi::parseMessage)
                if (message != null) {
                    local.mergeRemotePage(
                        ownerId = session.ownerId,
                        conversationId = message.conversationId,
                        messages = listOf(message),
                        deliveryStates = emptyMap(),
                        readStates = emptyMap(),
                        hasMoreBefore = local.syncCursor(session.ownerId, message.conversationId).hasMoreBefore,
                    )
                }
            }
            "delivered_receipt", "read_receipt" -> {
                val conversationId = json.optLong("conversation_id", 0L)
                val participant = json.optString("user_code").ifBlank { json.optString("username") }
                local.mergeReceipt(
                    ownerId = session.ownerId,
                    conversationId = conversationId,
                    participantIdentity = participant,
                    deliveredMessageId = json.optLong("last_delivered_message_id", 0L),
                    readMessageId = json.optLong("last_read_message_id", 0L),
                )
            }
            "conversation_history_deleted" -> {
                local.clearConversation(session.ownerId, json.optLong("conversation_id", 0L))
            }
            "history_deleted" -> local.clearAllConversationContent(session.ownerId)
            "conversation_removed" -> {
                local.deleteConversation(session.ownerId, json.optLong("conversation_id", 0L))
            }
            "force_logout" -> auth.logout(remote = false)
        }
        mutableEvents.emit(text)
    }
}
