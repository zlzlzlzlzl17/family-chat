package com.example.chat

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat

private const val FAMILYCHAT_PHONE_ACCOUNT_ID = "familychat_self_managed"
private const val EXTRA_CONVERSATION_ID = "familychat_conversation_id"
private const val EXTRA_PEER_USER_CODE = "familychat_peer_user_code"
private const val EXTRA_PEER_USERNAME = "familychat_peer_username"

object FamilyChatTelecom {
    private var currentConnection: FamilyChatConnection? = null
    private var currentCallKey: String? = null
    private var requestedIncomingKey: String? = null
    private var requestedOutgoingKey: String? = null

    fun ensureRegistered(context: Context) {
        val telecomManager = telecomManager(context) ?: return
        val handle = phoneAccountHandle(context)
        val existing = runCatching { telecomManager.getPhoneAccount(handle) }.getOrNull()
        if (existing != null) return
        runCatching {
            val account = PhoneAccount.builder(handle, context.applicationInfo.loadLabel(context.packageManager))
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .setSupportedUriSchemes(listOf(PhoneAccount.SCHEME_SIP))
                .build()
            telecomManager.registerPhoneAccount(account)
        }
    }

    fun sync(context: Context, state: VoiceCallUiState) {
        val key = callKey(state)
        if (state.phase == VoiceCallPhase.IDLE || key == null) {
            currentConnection?.setDisconnectedIfNeeded(DisconnectCause(DisconnectCause.LOCAL))
            currentConnection?.destroySafely()
            currentConnection = null
            currentCallKey = null
            requestedIncomingKey = null
            requestedOutgoingKey = null
            return
        }

        if (!isSupported(context)) return
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.MANAGE_OWN_CALLS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        ensureRegistered(context)
        val telecomManager = telecomManager(context) ?: return

        currentConnection?.let { connection ->
            updateConnectionFromState(connection, state)
            return
        }

        when (state.phase) {
            VoiceCallPhase.INCOMING -> {
                if (requestedIncomingKey == key) return
                requestedIncomingKey = key
                runCatching {
                    telecomManager.addNewIncomingCall(
                        phoneAccountHandle(context),
                        buildExtras(state)
                    )
                }.onFailure {
                    requestedIncomingKey = null
                }
            }

            VoiceCallPhase.OUTGOING -> {
                if (requestedOutgoingKey == key) return
                requestedOutgoingKey = key
                runCatching {
                    val extras = buildExtras(state).apply {
                        putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle(context))
                    }
                    telecomManager.placeCall(callAddress(state), extras)
                }.onFailure {
                    requestedOutgoingKey = null
                }
            }

            else -> Unit
        }
    }

    fun requestAudioRoute(route: VoiceAudioRoute) {
        currentConnection?.requestAudioRoute(route)
    }

    internal fun onCreateIncomingConnection(context: Context, request: ConnectionRequest): Connection {
        val connection = FamilyChatConnection(context.applicationContext, isIncoming = true)
        attachConnection(connection, request.extras)
        val runtimeState = VoiceCallRuntime.state.value
        if (runtimeState.phase == VoiceCallPhase.IDLE) {
            connection.setInitializing()
        }
        updateConnectionFromState(connection, runtimeState.takeIf { it.phase != VoiceCallPhase.IDLE } ?: request.toVoiceCallState(incoming = true))
        return connection
    }

    internal fun onCreateOutgoingConnection(context: Context, request: ConnectionRequest): Connection {
        val connection = FamilyChatConnection(context.applicationContext, isIncoming = false)
        attachConnection(connection, request.extras)
        val runtimeState = VoiceCallRuntime.state.value
        if (runtimeState.phase == VoiceCallPhase.IDLE) {
            connection.setDialing()
        }
        updateConnectionFromState(connection, runtimeState.takeIf { it.phase != VoiceCallPhase.IDLE } ?: request.toVoiceCallState(incoming = false))
        return connection
    }

    internal fun onCreateConnectionFailed() {
        currentConnection?.destroySafely()
        currentConnection = null
        currentCallKey = null
    }

    internal fun onConnectionDestroyed(connection: FamilyChatConnection) {
        if (currentConnection === connection) {
            currentConnection = null
            currentCallKey = null
        }
    }

    private fun attachConnection(connection: FamilyChatConnection, extras: Bundle?) {
        val state = extras.toVoiceCallState(incoming = connection.isIncoming)
        connection.bind(state)
        currentConnection = connection
        currentCallKey = callKey(state)
        requestedIncomingKey = currentCallKey
        requestedOutgoingKey = currentCallKey
    }

    private fun updateConnectionFromState(connection: FamilyChatConnection, state: VoiceCallUiState) {
        connection.bind(state)
        when (state.phase) {
            VoiceCallPhase.INCOMING -> connection.setRingingIfNeeded()
            VoiceCallPhase.OUTGOING -> connection.setDialingIfNeeded()
            VoiceCallPhase.CONNECTING -> connection.setConnectingLikeIfNeeded(state.isIncoming)
            VoiceCallPhase.ACTIVE -> connection.setActiveIfNeeded()
            VoiceCallPhase.ENDED -> {
                connection.setDisconnectedIfNeeded(DisconnectCause(DisconnectCause.REMOTE))
                connection.destroySafely()
            }
            VoiceCallPhase.FAILED -> {
                connection.setDisconnectedIfNeeded(DisconnectCause(DisconnectCause.ERROR))
                connection.destroySafely()
            }
            VoiceCallPhase.IDLE -> {
                connection.setDisconnectedIfNeeded(DisconnectCause(DisconnectCause.LOCAL))
                connection.destroySafely()
            }
        }
    }

    private fun buildExtras(state: VoiceCallUiState): Bundle =
        Bundle().apply {
            putLong(EXTRA_CONVERSATION_ID, state.conversationId)
            putString(EXTRA_PEER_USER_CODE, state.peerUserCode)
            putString(EXTRA_PEER_USERNAME, state.peerUsername)
        }

    private fun callKey(state: VoiceCallUiState): String? =
        state.conversationId.takeIf { it > 0L }?.let { conversationId ->
            listOf(conversationId.toString(), state.peerUserCode, state.peerUsername).joinToString("|")
        }

    private fun callAddress(state: VoiceCallUiState): Uri =
        Uri.fromParts(
            PhoneAccount.SCHEME_SIP,
            state.peerUserCode.ifBlank { state.conversationId.toString() },
            null
        )

    private fun isSupported(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && telecomManager(context) != null

    private fun telecomManager(context: Context): TelecomManager? =
        runCatching { context.getSystemService(TelecomManager::class.java) }.getOrNull()

    private fun phoneAccountHandle(context: Context): PhoneAccountHandle =
        PhoneAccountHandle(
            ComponentName(context, FamilyChatConnectionService::class.java),
            FAMILYCHAT_PHONE_ACCOUNT_ID
        )

    private fun Bundle?.toVoiceCallState(incoming: Boolean): VoiceCallUiState {
        if (this == null) return VoiceCallUiState(isIncoming = incoming)
        return VoiceCallUiState(
            phase = if (incoming) VoiceCallPhase.INCOMING else VoiceCallPhase.OUTGOING,
            conversationId = getLong(EXTRA_CONVERSATION_ID, 0L),
            peerUserCode = getString(EXTRA_PEER_USER_CODE).orEmpty(),
            peerUsername = getString(EXTRA_PEER_USERNAME).orEmpty(),
            isIncoming = incoming,
            statusMessage = if (incoming) "incoming" else "calling"
        )
    }

    private fun ConnectionRequest.toVoiceCallState(incoming: Boolean): VoiceCallUiState =
        extras.toVoiceCallState(incoming)
}

class FamilyChatConnectionService : ConnectionService() {
    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle,
        request: ConnectionRequest
    ): Connection = FamilyChatTelecom.onCreateIncomingConnection(this, request)

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle,
        request: ConnectionRequest
    ): Connection = FamilyChatTelecom.onCreateOutgoingConnection(this, request)

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle,
        request: ConnectionRequest
    ) {
        super.onCreateIncomingConnectionFailed(connectionManagerPhoneAccount, request)
        FamilyChatTelecom.onCreateConnectionFailed()
    }

    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle,
        request: ConnectionRequest
    ) {
        super.onCreateOutgoingConnectionFailed(connectionManagerPhoneAccount, request)
        FamilyChatTelecom.onCreateConnectionFailed()
    }
}

internal class FamilyChatConnection(
    private val appContext: Context,
    val isIncoming: Boolean,
) : Connection() {
    private var conversationId: Long = 0L

    init {
        connectionProperties = connectionProperties or PROPERTY_SELF_MANAGED
        connectionCapabilities = connectionCapabilities or CAPABILITY_MUTE
        setAudioModeIsVoip(true)
    }

    fun bind(state: VoiceCallUiState) {
        conversationId = state.conversationId
        val address = Uri.fromParts(
            PhoneAccount.SCHEME_SIP,
            state.peerUserCode.ifBlank { state.conversationId.toString() },
            null
        )
        setAddress(address, TelecomManager.PRESENTATION_ALLOWED)
        setCallerDisplayName(state.peerUsername.ifBlank { "Family Chat" }, TelecomManager.PRESENTATION_ALLOWED)
    }

    fun setRingingIfNeeded() {
        if (state != STATE_RINGING) setRinging()
    }

    fun setDialingIfNeeded() {
        if (state != STATE_DIALING) setDialing()
    }

    fun setConnectingLikeIfNeeded(incoming: Boolean) {
        when {
            incoming && state == STATE_RINGING -> Unit
            !incoming && state == STATE_DIALING -> Unit
            else -> setInitializing()
        }
    }

    fun setActiveIfNeeded() {
        if (state != STATE_ACTIVE) setActive()
    }

    fun setDisconnectedIfNeeded(cause: DisconnectCause) {
        if (state != STATE_DISCONNECTED) setDisconnected(cause)
    }

    fun destroySafely() {
        runCatching { destroy() }
    }

    @Suppress("DEPRECATION")
    fun requestAudioRoute(route: VoiceAudioRoute) {
        val telecomRoute = when (route) {
            VoiceAudioRoute.BLUETOOTH -> CallAudioState.ROUTE_BLUETOOTH
            VoiceAudioRoute.SPEAKER -> CallAudioState.ROUTE_SPEAKER
            VoiceAudioRoute.EARPIECE -> CallAudioState.ROUTE_EARPIECE
        }
        runCatching { setAudioRoute(telecomRoute) }
    }

    override fun onAnswer() {
        VoiceCallRuntime.accept()
    }

    override fun onReject() {
        VoiceCallRuntime.decline()
    }

    override fun onReject(rejectReason: Int) {
        VoiceCallRuntime.decline()
    }

    override fun onDisconnect() {
        setDisconnectedIfNeeded(DisconnectCause(DisconnectCause.LOCAL))
        destroySafely()
        VoiceCallRuntime.end()
    }

    override fun onAbort() {
        setDisconnectedIfNeeded(DisconnectCause(DisconnectCause.CANCELED))
        destroySafely()
        VoiceCallRuntime.end()
    }

    override fun onSilence() = Unit

    override fun onShowIncomingCallUi() {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { appContext.startActivity(intent) }
    }

    @SuppressLint("MissingPermission")
    override fun onCallAudioStateChanged(state: CallAudioState) {
        super.onCallAudioStateChanged(state)
        when (state.route) {
            CallAudioState.ROUTE_BLUETOOTH -> VoiceCallRuntime.syncAudioRouteFromSystem(VoiceAudioRoute.BLUETOOTH)
            CallAudioState.ROUTE_SPEAKER -> VoiceCallRuntime.syncAudioRouteFromSystem(VoiceAudioRoute.SPEAKER)
            else -> VoiceCallRuntime.syncAudioRouteFromSystem(VoiceAudioRoute.EARPIECE)
        }
    }

    override fun onStateChanged(state: Int) {
        super.onStateChanged(state)
        if (state == STATE_DISCONNECTED) {
            FamilyChatTelecom.onConnectionDestroyed(this)
        }
    }
}
