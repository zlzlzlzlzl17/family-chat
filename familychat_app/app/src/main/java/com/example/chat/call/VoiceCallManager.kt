package com.example.chat

import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule

data class CallIceServerConfig(
    val urls: List<String>,
    val username: String = "",
    val credential: String = "",
)

enum class VoiceAudioRoute {
    EARPIECE,
    SPEAKER,
    BLUETOOTH,
}

enum class VoiceCallPhase {
    IDLE,
    OUTGOING,
    INCOMING,
    CONNECTING,
    ACTIVE,
    ENDED,
    FAILED,
}

data class VoiceCallUiState(
    val phase: VoiceCallPhase = VoiceCallPhase.IDLE,
    val conversationId: Long = 0L,
    val peerUserCode: String = "",
    val peerUsername: String = "",
    val isIncoming: Boolean = false,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val currentAudioRoute: VoiceAudioRoute = VoiceAudioRoute.EARPIECE,
    val availableAudioRoutes: List<VoiceAudioRoute> = listOf(VoiceAudioRoute.EARPIECE, VoiceAudioRoute.SPEAKER),
    val startedAt: Long = 0L,
    val statusMessage: String = "",
    val quality: WebRtcCallQuality = WebRtcCallQuality(),
)

class VoiceCallManager(
    private val context: Context,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onSignal(type: String, payload: JSONObject)
        fun onStateChanged(state: VoiceCallUiState)
        fun onError(message: String)
    }

    @Volatile
    private var currentState = VoiceCallUiState()
    private val callThread = HandlerThread("familychat-webrtc").apply { start() }
    private val callHandler = Handler(callThread.looper)
    private var factory: PeerConnectionFactory? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var peerConnection: PeerConnection? = null
    private var peerGeneration = 0L
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var remoteDescriptionSet = false
    private val pendingIceCandidates = mutableListOf<IceCandidate>()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var previousAudioMode = AudioManager.MODE_NORMAL
    private var previousSpeakerphone = false
    private var previousMicrophoneMute = false
    private var audioSessionActive = false
    private var isClearingPeerConnection = false
    private var audioFocusRequest: AudioFocusRequest? = null
    private var iceServers: List<CallIceServerConfig> = defaultIceServers()
    private val audioDeviceCallback =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                    enqueue("audio_devices_added", ::onAudioDevicesChanged)
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                    enqueue("audio_devices_removed", ::onAudioDevicesChanged)
                }
            }
        } else {
            null
        }
    private var audioDeviceCallbackRegistered = false
    private var disconnectTimeoutRunnable: Runnable? = null
    private var statsSamplingRunnable: Runnable? = null
    private val statsSamplingIntervalMs = 2_000L
    private val reconnectPolicy = CallReconnectPolicy()
    private var signalingReconnectPending = false
    private var signalingConnected = true
    private var iceRestartInFlight = false
    private var lastIceRestartAt = 0L
    private val logTag = "FamilyChatCall"

    fun state(): VoiceCallUiState = currentState

    fun updateIceServers(servers: List<CallIceServerConfig>) {
        enqueue("update_ice_servers") {
            iceServers = if (servers.isNotEmpty()) servers else defaultIceServers()
            debugLog("ice_servers_updated", "count=${iceServers.size}")
        }
    }

    fun startOutgoing(conversationId: Long, peerUserCode: String, peerUsername: String) {
        enqueue("start_outgoing") {
            startOutgoingOnCallThread(conversationId, peerUserCode, peerUsername)
        }
    }

    private fun startOutgoingOnCallThread(conversationId: Long, peerUserCode: String, peerUsername: String) {
        if (!canStartNewCall()) return
        debugLog("start_outgoing", "conversationId=$conversationId peer=$peerUsername/$peerUserCode")
        updateState(
            VoiceCallUiState(
                phase = VoiceCallPhase.OUTGOING,
                conversationId = conversationId,
                peerUserCode = peerUserCode,
                peerUsername = peerUsername,
                statusMessage = "calling"
            )
        )
        callbacks.onSignal(
            "call_invite",
            JSONObject().put("conversation_id", conversationId)
        )
    }

    fun receiveIncomingInvite(
        conversationId: Long,
        peerUserCode: String,
        peerUsername: String,
        onResult: (Boolean) -> Unit = {},
    ) {
        enqueue("receive_incoming_invite") {
            onResult(receiveIncomingInviteOnCallThread(conversationId, peerUserCode, peerUsername))
        }
    }

    private fun receiveIncomingInviteOnCallThread(
        conversationId: Long,
        peerUserCode: String,
        peerUsername: String,
    ): Boolean {
        debugLog("receive_incoming_invite", "conversationId=$conversationId peer=$peerUsername/$peerUserCode phase=${currentState.phase}")
        if (
            currentState.phase == VoiceCallPhase.INCOMING &&
            currentState.conversationId == conversationId &&
            currentState.peerUsername == peerUsername &&
            currentState.peerUserCode == peerUserCode
        ) {
            return true
        }
        if (currentState.phase != VoiceCallPhase.IDLE) {
            callbacks.onSignal(
                "call_busy",
                JSONObject().put("conversation_id", conversationId)
            )
            return false
        }
        updateState(
            VoiceCallUiState(
                phase = VoiceCallPhase.INCOMING,
                conversationId = conversationId,
                peerUserCode = peerUserCode,
                peerUsername = peerUsername,
                isIncoming = true,
                statusMessage = "incoming"
            )
        )
        return true
    }

    fun restoreIncomingInvite(state: VoiceCallUiState) {
        enqueue("restore_incoming_invite") { restoreIncomingInviteOnCallThread(state) }
    }

    private fun restoreIncomingInviteOnCallThread(state: VoiceCallUiState) {
        if (state.phase != VoiceCallPhase.INCOMING || state.conversationId <= 0L) return
        if (
            currentState.phase == VoiceCallPhase.INCOMING &&
            currentState.conversationId == state.conversationId &&
            currentState.peerUsername == state.peerUsername &&
            currentState.peerUserCode == state.peerUserCode
        ) {
            return
        }
        if (currentState.phase != VoiceCallPhase.IDLE) return
        updateState(
            state.copy(
                phase = VoiceCallPhase.INCOMING,
                isIncoming = true,
                statusMessage = if (state.statusMessage.isBlank()) "incoming" else state.statusMessage,
            )
        )
    }

    fun acceptIncoming() {
        enqueue("accept_incoming") { acceptIncomingOnCallThread() }
    }

    private fun acceptIncomingOnCallThread() {
        val connectingState = CallStateTransitions.beginAccept(currentState) ?: return
        if (!hasRecordAudioPermission()) {
            callBreadcrumb("accept_blocked_microphone_permission")
            callbacks.onError("Microphone permission is required")
            return
        }
        callBreadcrumb("accept_started")
        updateState(connectingState)
        if (!preparePeerConnection()) {
            finishWithTerminalState(VoiceCallPhase.FAILED, "failed")
            return
        }
        debugLog("accept_incoming", "conversationId=${currentState.conversationId}")
        callbacks.onSignal(
            "call_accept",
            JSONObject().put("conversation_id", currentState.conversationId)
        )
        callBreadcrumb("accept_signaled")
    }

    fun rejectIncoming() {
        enqueue("reject_incoming") { rejectIncomingOnCallThread() }
    }

    private fun rejectIncomingOnCallThread() {
        if (currentState.phase != VoiceCallPhase.INCOMING) return
        debugLog("reject_incoming", "conversationId=${currentState.conversationId}")
        callbacks.onSignal(
            "call_reject",
            JSONObject().put("conversation_id", currentState.conversationId)
        )
        clearToIdle()
    }

    fun onRemoteAccepted() {
        enqueue("remote_accepted") { onRemoteAcceptedOnCallThread() }
    }

    private fun onRemoteAcceptedOnCallThread() {
        if (currentState.phase != VoiceCallPhase.OUTGOING) return
        if (!hasRecordAudioPermission()) {
            callbacks.onError("Microphone permission is required")
            finishWithTerminalState(VoiceCallPhase.FAILED, "failed")
            return
        }
        if (!preparePeerConnection()) return
        debugLog("remote_accepted", "conversationId=${currentState.conversationId}")
        updateState(currentState.copy(phase = VoiceCallPhase.CONNECTING, statusMessage = "connecting"))
        createOffer()
    }

    fun onRemoteRejected() {
        enqueue("remote_rejected") {
            debugLog("remote_rejected", "conversationId=${currentState.conversationId}")
            finishWithTerminalState(VoiceCallPhase.ENDED, "rejected")
        }
    }

    fun onRemoteBusy() {
        enqueue("remote_busy") {
            debugLog("remote_busy", "conversationId=${currentState.conversationId}")
            finishWithTerminalState(VoiceCallPhase.ENDED, "busy")
        }
    }

    fun onRemoteUnavailable() {
        enqueue("remote_unavailable") {
            debugLog("remote_unavailable", "conversationId=${currentState.conversationId}")
            finishWithTerminalState(VoiceCallPhase.ENDED, "unavailable")
        }
    }

    fun onRemoteHangup() {
        enqueue("remote_hangup") {
            debugLog("remote_hangup", "conversationId=${currentState.conversationId}")
            finishWithTerminalState(VoiceCallPhase.ENDED, "ended")
        }
    }

    fun onRemoteOffer(description: JSONObject) {
        enqueue("remote_offer") { onRemoteOfferOnCallThread(description) }
    }

    private fun onRemoteOfferOnCallThread(description: JSONObject) {
        debugLog("remote_offer", "conversationId=${currentState.conversationId} phase=${currentState.phase} type=${description.optString("type")} sdpLength=${description.optString("sdp").length}")
        if (
            currentState.phase != VoiceCallPhase.CONNECTING &&
            currentState.phase != VoiceCallPhase.INCOMING &&
            currentState.phase != VoiceCallPhase.ACTIVE
        ) return
        if (!preparePeerConnection()) return
        iceRestartInFlight = false
        val recoveringExistingCall = currentState.startedAt > 0L || currentState.phase == VoiceCallPhase.ACTIVE
        val remoteDescription = description.toSessionDescription() ?: return
        val connection = peerConnection ?: return
        val generation = peerGeneration
        connection.setRemoteDescription(
            object : SdpObserverAdapter() {
                override fun onSetSuccess() {
                    enqueue("remote_offer_set_success") {
                        if (!isCurrentPeer(connection, generation)) return@enqueue
                        debugLog("remote_offer_set_success", "conversationId=${currentState.conversationId} recovering=$recoveringExistingCall")
                        remoteDescriptionSet = true
                        flushPendingIceCandidates()
                        createAnswer(recoveringExistingCall)
                    }
                }

                override fun onSetFailure(message: String?) {
                    enqueue("remote_offer_set_failure") {
                        if (!isCurrentPeer(connection, generation)) return@enqueue
                        iceRestartInFlight = false
                        warnLog("remote_offer_set_failure", "conversationId=${currentState.conversationId} recovering=$recoveringExistingCall error=${message ?: "unknown"}")
                        callbacks.onError(message ?: "Unable to accept call offer")
                        if (recoveringExistingCall) {
                            enterReconnectWindow()
                        } else {
                            finishWithTerminalState(VoiceCallPhase.FAILED, "failed")
                        }
                    }
                }
            },
            remoteDescription
        )
    }

    fun onRemoteAnswer(description: JSONObject) {
        enqueue("remote_answer") { onRemoteAnswerOnCallThread(description) }
    }

    private fun onRemoteAnswerOnCallThread(description: JSONObject) {
        debugLog("remote_answer", "conversationId=${currentState.conversationId} type=${description.optString("type")} sdpLength=${description.optString("sdp").length}")
        val remoteDescription = description.toSessionDescription() ?: return
        val connection = peerConnection ?: return
        val generation = peerGeneration
        connection.setRemoteDescription(
            object : SdpObserverAdapter() {
                override fun onSetSuccess() {
                    enqueue("remote_answer_set_success") {
                        if (!isCurrentPeer(connection, generation)) return@enqueue
                        iceRestartInFlight = false
                        debugLog("remote_answer_set_success", "conversationId=${currentState.conversationId}")
                        remoteDescriptionSet = true
                        flushPendingIceCandidates()
                    }
                }

                override fun onSetFailure(message: String?) {
                    enqueue("remote_answer_set_failure") {
                        if (!isCurrentPeer(connection, generation)) return@enqueue
                        iceRestartInFlight = false
                        warnLog("remote_answer_set_failure", "conversationId=${currentState.conversationId} error=${message ?: "unknown"}")
                        callbacks.onError(message ?: "Unable to accept call answer")
                        finishWithTerminalState(VoiceCallPhase.FAILED, "failed")
                    }
                }
            },
            remoteDescription
        )
    }

    fun onRemoteIceCandidate(candidateJson: JSONObject) {
        enqueue("remote_ice_candidate") { onRemoteIceCandidateOnCallThread(candidateJson) }
    }

    private fun onRemoteIceCandidateOnCallThread(candidateJson: JSONObject) {
        debugLog(
            "remote_ice_candidate",
            "conversationId=${currentState.conversationId} mid=${candidateJson.optString("sdpMid")} line=${candidateJson.optInt("sdpMLineIndex", -1)} remoteDescriptionSet=$remoteDescriptionSet"
        )
        val candidate = candidateJson.toIceCandidate() ?: return
        if (peerConnection == null || !remoteDescriptionSet) {
            pendingIceCandidates += candidate
        } else {
            peerConnection?.addIceCandidate(candidate)
        }
    }

    fun toggleMute() {
        enqueue("toggle_mute") { toggleMuteOnCallThread() }
    }

    private fun toggleMuteOnCallThread() {
        val nextMuted = !currentState.isMuted
        localAudioTrack?.setEnabled(!nextMuted)
        audioManager.isMicrophoneMute = nextMuted
        updateState(currentState.copy(isMuted = nextMuted))
    }

    fun toggleSpeaker() {
        enqueue("toggle_speaker") { toggleSpeakerOnCallThread() }
    }

    private fun toggleSpeakerOnCallThread() {
        val nextRoute = if (currentState.currentAudioRoute == VoiceAudioRoute.SPEAKER) {
            if (currentState.availableAudioRoutes.contains(VoiceAudioRoute.BLUETOOTH)) VoiceAudioRoute.BLUETOOTH else VoiceAudioRoute.EARPIECE
        } else {
            VoiceAudioRoute.SPEAKER
        }
        selectAudioRouteOnCallThread(nextRoute)
    }

    fun selectAudioRoute(route: VoiceAudioRoute) {
        enqueue("select_audio_route") { selectAudioRouteOnCallThread(route) }
    }

    private fun selectAudioRouteOnCallThread(route: VoiceAudioRoute) {
        val availableRoutes = computeAvailableAudioRoutes()
        val resolvedRoute = when {
            availableRoutes.contains(route) -> route
            availableRoutes.contains(VoiceAudioRoute.EARPIECE) -> VoiceAudioRoute.EARPIECE
            else -> VoiceAudioRoute.SPEAKER
        }
        updateState(
            currentState.copy(
                currentAudioRoute = resolvedRoute,
                isSpeakerOn = resolvedRoute == VoiceAudioRoute.SPEAKER,
                availableAudioRoutes = availableRoutes
            )
        )
        FamilyChatTelecom.requestAudioRoute(resolvedRoute)
        if (audioSessionActive) applyCurrentAudioRoute()
    }

    fun syncSystemAudioRoute(route: VoiceAudioRoute) {
        enqueue("sync_system_audio_route") { syncSystemAudioRouteOnCallThread(route) }
    }

    private fun syncSystemAudioRouteOnCallThread(route: VoiceAudioRoute) {
        val availableRoutes = computeAvailableAudioRoutes()
        val resolvedRoute = when {
            availableRoutes.contains(route) -> route
            availableRoutes.contains(VoiceAudioRoute.EARPIECE) -> VoiceAudioRoute.EARPIECE
            else -> VoiceAudioRoute.SPEAKER
        }
        if (
            currentState.currentAudioRoute == resolvedRoute &&
            currentState.isSpeakerOn == (resolvedRoute == VoiceAudioRoute.SPEAKER) &&
            currentState.availableAudioRoutes == availableRoutes
        ) {
            return
        }
        debugLog(
            "sync_system_audio_route",
            "reported=$route resolved=$resolvedRoute available=$availableRoutes"
        )
        updateState(
            currentState.copy(
                currentAudioRoute = resolvedRoute,
                isSpeakerOn = resolvedRoute == VoiceAudioRoute.SPEAKER,
                availableAudioRoutes = availableRoutes,
            )
        )
    }

    fun endCall(sendSignal: Boolean = true) {
        enqueue("end_call") { endCallOnCallThread(sendSignal) }
    }

    private fun endCallOnCallThread(sendSignal: Boolean) {
        val conversationId = currentState.conversationId
        debugLog("end_call", "conversationId=$conversationId sendSignal=$sendSignal phase=${currentState.phase}")
        if (sendSignal && conversationId > 0L && currentState.phase != VoiceCallPhase.IDLE) {
            callbacks.onSignal(
                "call_hangup",
                JSONObject().put("conversation_id", conversationId)
            )
        }
        clearToIdle()
    }

    fun dismissTerminalState() {
        enqueue("dismiss_terminal_state") { dismissTerminalStateOnCallThread() }
    }

    private fun dismissTerminalStateOnCallThread() {
        if (currentState.phase == VoiceCallPhase.ENDED || currentState.phase == VoiceCallPhase.FAILED) {
            clearToIdle()
        }
    }

    fun onSignalingDisconnected() {
        enqueue("signaling_disconnected") { onSignalingDisconnectedOnCallThread() }
    }

    private fun onSignalingDisconnectedOnCallThread() {
        signalingConnected = false
        warnLog("signaling_disconnected", "conversationId=${currentState.conversationId} phase=${currentState.phase}")
        if (currentState.phase != VoiceCallPhase.IDLE && currentState.phase != VoiceCallPhase.ENDED) {
            signalingReconnectPending = true
            // The chat WebSocket carries call control messages, but established audio
            // continues on the WebRTC transport. Do not present a media outage while
            // PeerConnection/ICE are still reporting an active call.
            if (currentState.phase != VoiceCallPhase.ACTIVE || currentState.statusMessage != "active") {
                enterReconnectWindow("reconnecting")
            }
        }
    }

    fun onSignalingReconnected() {
        enqueue("signaling_reconnected") { onSignalingReconnectedOnCallThread() }
    }

    private fun onSignalingReconnectedOnCallThread() {
        signalingConnected = true
        debugLog("signaling_reconnected", "conversationId=${currentState.conversationId} phase=${currentState.phase} reconnectPending=$signalingReconnectPending")
        if (!signalingReconnectPending) return
        signalingReconnectPending = false
        if (currentState.phase == VoiceCallPhase.IDLE || currentState.phase == VoiceCallPhase.ENDED || currentState.phase == VoiceCallPhase.FAILED) {
            return
        }
        if (currentState.statusMessage == "reconnecting") {
            maybeRequestIceRestart()
        }
    }

    fun release() {
        enqueue("release") { releaseOnCallThread() }
    }

    private fun releaseOnCallThread() {
        debugLog("release", "conversationId=${currentState.conversationId}")
        clearPeerConnection()
        clearToIdle()
        audioDeviceModule?.release()
        audioDeviceModule = null
        factory?.dispose()
        factory = null
    }

    private fun canStartNewCall(): Boolean {
        if (currentState.phase != VoiceCallPhase.IDLE) {
            warnLog("start_blocked", "phase=${currentState.phase} conversationId=${currentState.conversationId}")
            callbacks.onError("Call already in progress")
            return false
        }
        return true
    }

    private fun preparePeerConnection(): Boolean {
        callBreadcrumb("peer_prepare_started")
        return try {
            ensureFactory()
            ensureAudioSession()
            ensureLocalAudioTrack()
            if (peerConnection != null) {
                callBreadcrumb("peer_prepare_reused")
                return true
            }
            debugLog("prepare_peer_connection", "conversationId=${currentState.conversationId}")
            remoteDescriptionSet = false
            pendingIceCandidates.clear()
            val config = PeerConnection.RTCConfiguration(iceServers.toPeerConnectionServers())
            config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            val generation = ++peerGeneration
            callBreadcrumb("peer_create_started")
            peerConnection = factory?.createPeerConnection(
                config,
                object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    enqueue("local_ice_candidate") {
                        if (generation != peerGeneration || isClearingPeerConnection) return@enqueue
                        debugLog("local_ice_candidate", "conversationId=${currentState.conversationId} mid=${candidate.sdpMid} line=${candidate.sdpMLineIndex}")
                        callbacks.onSignal(
                            "call_ice",
                            JSONObject()
                                .put("conversation_id", currentState.conversationId)
                                .put(
                                    "candidate",
                                    JSONObject()
                                        .put("sdpMid", candidate.sdpMid)
                                        .put("sdpMLineIndex", candidate.sdpMLineIndex)
                                        .put("candidate", candidate.sdp)
                                )
                        )
                    }
                }

                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                    enqueue("peer_connection_state") {
                        if (generation != peerGeneration || isClearingPeerConnection) return@enqueue
                        debugLog("peer_connection_state", "conversationId=${currentState.conversationId} state=$newState phase=${currentState.phase} status=${currentState.statusMessage}")
                        when (newState) {
                            PeerConnection.PeerConnectionState.CONNECTED -> {
                                cancelDisconnectTimeout()
                                iceRestartInFlight = false
                                startStatsSampling()
                                if (currentState.phase != VoiceCallPhase.ACTIVE || currentState.statusMessage != "active") {
                                    updateState(
                                        currentState.copy(
                                            phase = VoiceCallPhase.ACTIVE,
                                            startedAt = currentState.startedAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
                                            statusMessage = "active"
                                        )
                                    )
                                }
                            }

                            PeerConnection.PeerConnectionState.DISCONNECTED,
                            PeerConnection.PeerConnectionState.FAILED,
                            PeerConnection.PeerConnectionState.CLOSED -> {
                                if (
                                    currentState.phase != VoiceCallPhase.IDLE &&
                                    currentState.phase != VoiceCallPhase.ENDED &&
                                    currentState.phase != VoiceCallPhase.FAILED
                                ) {
                                    enterReconnectWindow()
                                }
                            }

                            else -> Unit
                        }
                    }
                }

                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                    enqueue("ice_connection_state") {
                        if (generation != peerGeneration || isClearingPeerConnection) return@enqueue
                        debugLog("ice_connection_state", "conversationId=${currentState.conversationId} state=$newState phase=${currentState.phase} status=${currentState.statusMessage}")
                        when (newState) {
                            PeerConnection.IceConnectionState.CONNECTED,
                            PeerConnection.IceConnectionState.COMPLETED -> {
                                cancelDisconnectTimeout()
                                iceRestartInFlight = false
                                startStatsSampling()
                                if (currentState.phase != VoiceCallPhase.ACTIVE || currentState.statusMessage != "active") {
                                    updateState(
                                        currentState.copy(
                                            phase = VoiceCallPhase.ACTIVE,
                                            startedAt = currentState.startedAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
                                            statusMessage = "active"
                                        )
                                    )
                                }
                            }

                            PeerConnection.IceConnectionState.DISCONNECTED,
                            PeerConnection.IceConnectionState.FAILED -> enterReconnectWindow()

                            else -> Unit
                        }
                    }
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) {
                    enqueue("ice_receiving_change") {
                        if (generation != peerGeneration || isClearingPeerConnection) return@enqueue
                        debugLog("ice_receiving_change", "conversationId=${currentState.conversationId} receiving=$receiving phase=${currentState.phase}")
                        // This callback can briefly become false while an audio source is
                        // silent or routes change. PeerConnection and ICE state changes are
                        // the authoritative media-connectivity signals.
                    }
                }
                override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) = Unit
                override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
                override fun onAddStream(stream: MediaStream) = Unit
                override fun onRemoveStream(stream: MediaStream) = Unit
                override fun onDataChannel(dataChannel: org.webrtc.DataChannel) = Unit
                override fun onRenegotiationNeeded() = Unit
                override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) = Unit
                }
            )
            val track = localAudioTrack ?: return false
            peerConnection?.addTrack(track)
            val prepared = peerConnection != null
            callBreadcrumb(if (prepared) "peer_prepare_completed" else "peer_prepare_empty")
            prepared
        } catch (error: RuntimeException) {
            warnLog("prepare_peer_connection_failure", "error=${error.javaClass.simpleName}")
            FamilyChatDiagnostics.event(
                "call_operation_failed",
                "operation" to "prepare_peer_connection",
                "error" to error.javaClass.name,
            )
            false
        }
    }

    private fun hasRecordAudioPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun createOffer(iceRestart: Boolean = false) {
        val connection = peerConnection ?: return
        val generation = peerGeneration
        debugLog("create_offer", "conversationId=${currentState.conversationId} iceRestart=$iceRestart")
        if (iceRestart) {
            runCatching { connection.restartIce() }
        }
        connection.createOffer(
            object : SdpObserverAdapter() {
                override fun onCreateSuccess(description: SessionDescription?) {
                    if (description == null) return
                    enqueue("create_offer_success") {
                        if (!isCurrentPeer(connection, generation)) return@enqueue
                        debugLog("create_offer_success", "conversationId=${currentState.conversationId} type=${description.type} sdpLength=${description.description.length}")
                        connection.setLocalDescription(
                            object : SdpObserverAdapter() {
                                override fun onSetSuccess() {
                                    enqueue("set_local_offer_success") {
                                        if (!isCurrentPeer(connection, generation)) return@enqueue
                                        debugLog("set_local_offer_success", "conversationId=${currentState.conversationId} iceRestart=$iceRestart")
                                        callbacks.onSignal(
                                            "call_offer",
                                            JSONObject()
                                                .put("conversation_id", currentState.conversationId)
                                                .put(
                                                    "description",
                                                    JSONObject()
                                                        .put("type", description.type.canonicalForm())
                                                        .put("sdp", description.description)
                                                )
                                        )
                                    }
                                }

                                override fun onSetFailure(message: String?) {
                                    enqueue("set_local_offer_failure") {
                                        if (!isCurrentPeer(connection, generation)) return@enqueue
                                        if (iceRestart) iceRestartInFlight = false
                                        warnLog("set_local_offer_failure", "conversationId=${currentState.conversationId} iceRestart=$iceRestart error=${message ?: "unknown"}")
                                        callbacks.onError(message ?: "Unable to create call offer")
                                        if (iceRestart) {
                                            enterReconnectWindow()
                                        } else {
                                            finishWithTerminalState(VoiceCallPhase.FAILED, "failed")
                                        }
                                    }
                                }
                            },
                            description
                        )
                    }
                }

                override fun onCreateFailure(message: String?) {
                    enqueue("create_offer_failure") {
                        if (!isCurrentPeer(connection, generation)) return@enqueue
                        if (iceRestart) iceRestartInFlight = false
                        warnLog("create_offer_failure", "conversationId=${currentState.conversationId} iceRestart=$iceRestart error=${message ?: "unknown"}")
                        callbacks.onError(message ?: "Unable to create call offer")
                        if (iceRestart) {
                            enterReconnectWindow()
                        } else {
                            finishWithTerminalState(VoiceCallPhase.FAILED, "failed")
                        }
                    }
                }
            },
            MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                if (iceRestart) {
                    mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
                }
            }
        )
    }

    private fun createAnswer(recoveringExistingCall: Boolean = false) {
        val connection = peerConnection ?: return
        val generation = peerGeneration
        debugLog("create_answer", "conversationId=${currentState.conversationId} recovering=$recoveringExistingCall")
        connection.createAnswer(
            object : SdpObserverAdapter() {
                override fun onCreateSuccess(description: SessionDescription?) {
                    if (description == null) return
                    enqueue("create_answer_success") {
                        if (!isCurrentPeer(connection, generation)) return@enqueue
                        debugLog("create_answer_success", "conversationId=${currentState.conversationId} type=${description.type} sdpLength=${description.description.length}")
                        connection.setLocalDescription(
                            object : SdpObserverAdapter() {
                                override fun onSetSuccess() {
                                    enqueue("set_local_answer_success") {
                                        if (!isCurrentPeer(connection, generation)) return@enqueue
                                        debugLog("set_local_answer_success", "conversationId=${currentState.conversationId} recovering=$recoveringExistingCall")
                                        callbacks.onSignal(
                                            "call_answer",
                                            JSONObject()
                                                .put("conversation_id", currentState.conversationId)
                                                .put(
                                                    "description",
                                                    JSONObject()
                                                        .put("type", description.type.canonicalForm())
                                                        .put("sdp", description.description)
                                                )
                                        )
                                    }
                                }

                                override fun onSetFailure(message: String?) {
                                    enqueue("set_local_answer_failure") {
                                        if (!isCurrentPeer(connection, generation)) return@enqueue
                                        iceRestartInFlight = false
                                        warnLog("set_local_answer_failure", "conversationId=${currentState.conversationId} recovering=$recoveringExistingCall error=${message ?: "unknown"}")
                                        callbacks.onError(message ?: "Unable to create call answer")
                                        if (recoveringExistingCall) {
                                            enterReconnectWindow()
                                        } else {
                                            finishWithTerminalState(VoiceCallPhase.FAILED, "failed")
                                        }
                                    }
                                }
                            },
                            description
                        )
                    }
                }

                override fun onCreateFailure(message: String?) {
                    enqueue("create_answer_failure") {
                        if (!isCurrentPeer(connection, generation)) return@enqueue
                        iceRestartInFlight = false
                        warnLog("create_answer_failure", "conversationId=${currentState.conversationId} recovering=$recoveringExistingCall error=${message ?: "unknown"}")
                        callbacks.onError(message ?: "Unable to create call answer")
                        if (recoveringExistingCall) {
                            enterReconnectWindow()
                        } else {
                            finishWithTerminalState(VoiceCallPhase.FAILED, "failed")
                        }
                    }
                }
            },
            MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            }
        )
    }

    private fun ensureFactory() {
        if (factory != null) return
        val initializedNow = WebRtcRuntime.initializeIfNeeded(context) {
            callBreadcrumb("webrtc_initialize_started")
        }
        callBreadcrumb(if (initializedNow) "webrtc_initialize_completed" else "webrtc_initialize_reused")
        callBreadcrumb("audio_module_create_started")
        audioDeviceModule = JavaAudioDeviceModule.builder(context).createAudioDeviceModule()
        callBreadcrumb("audio_module_create_completed")
        callBreadcrumb("factory_create_started")
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()
        callBreadcrumb("factory_create_completed")
    }

    private fun ensureLocalAudioTrack() {
        if (localAudioTrack != null) return
        val peerFactory = factory ?: return
        callBreadcrumb("local_audio_track_create_started")
        localAudioSource = peerFactory.createAudioSource(MediaConstraints())
        localAudioTrack = peerFactory.createAudioTrack("familychat_audio_track", localAudioSource).also {
            it.setEnabled(!currentState.isMuted)
        }
        callBreadcrumb("local_audio_track_create_completed")
    }

    private fun ensureAudioSession() {
        if (audioSessionActive) return
        callBreadcrumb("audio_session_started")
        previousAudioMode = audioManager.mode
        previousSpeakerphone = audioManager.isSpeakerphoneOn
        previousMicrophoneMute = audioManager.isMicrophoneMute
        requestAudioFocus()
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isMicrophoneMute = currentState.isMuted
        audioSessionActive = true
        registerAudioDeviceCallback()
        refreshAvailableAudioRoutes()
        applyCurrentAudioRoute()
        callBreadcrumb("audio_session_completed")
    }

    private fun restoreAudioSession() {
        if (!audioSessionActive) return
        unregisterAudioDeviceCallback()
        clearPreferredCommunicationDevice()
        stopBluetoothRouting()
        audioManager.mode = previousAudioMode
        audioManager.isSpeakerphoneOn = previousSpeakerphone
        audioManager.isMicrophoneMute = previousMicrophoneMute
        abandonAudioFocus()
        audioSessionActive = false
    }

    private fun applyCurrentAudioRoute() {
        when (currentState.currentAudioRoute) {
            VoiceAudioRoute.SPEAKER -> {
                stopBluetoothRouting()
                clearPreferredCommunicationDevice()
                val preferredSpeaker = preferBuiltInSpeakerRoute()
                audioManager.isSpeakerphoneOn = true
                debugLog(
                    "apply_audio_route",
                    "target=speaker preferred=$preferredSpeaker device=${currentCommunicationDeviceSummary()} speakerphoneOn=${audioManager.isSpeakerphoneOn}"
                )
                return
            }

            VoiceAudioRoute.BLUETOOTH -> {
                audioManager.isSpeakerphoneOn = false
                if (preferBluetoothRoute()) return
                val fallbackRoute =
                    if (computeAvailableAudioRoutes().contains(VoiceAudioRoute.EARPIECE)) VoiceAudioRoute.EARPIECE else VoiceAudioRoute.SPEAKER
                updateState(
                    currentState.copy(
                        currentAudioRoute = fallbackRoute,
                        isSpeakerOn = fallbackRoute == VoiceAudioRoute.SPEAKER
                    )
                )
                applyCurrentAudioRoute()
                return
            }

            VoiceAudioRoute.EARPIECE -> Unit
        }

        clearPreferredCommunicationDevice()
        audioManager.isSpeakerphoneOn = false
        if (!hasBuiltInEarpiece()) {
            stopBluetoothRouting()
            audioManager.isSpeakerphoneOn = true
            debugLog(
                "apply_audio_route",
                "target=earpiece_fallback_to_speaker device=${currentCommunicationDeviceSummary()} speakerphoneOn=${audioManager.isSpeakerphoneOn}"
            )
            return
        }

        stopBluetoothRouting()
        val preferredEarpiece = preferBuiltInEarpieceRoute()
        if (!preferredEarpiece) {
            clearPreferredCommunicationDevice()
        }
        debugLog(
            "apply_audio_route",
            "target=earpiece preferred=$preferredEarpiece device=${currentCommunicationDeviceSummary()} speakerphoneOn=${audioManager.isSpeakerphoneOn}"
        )
    }

    private fun preferBuiltInSpeakerRoute(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val speakerDevice = audioManager.availableCommunicationDevices.firstOrNull { device ->
            device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } ?: return false
        return runCatching { audioManager.setCommunicationDevice(speakerDevice) }.getOrDefault(false)
    }

    private fun preferBuiltInEarpieceRoute(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val earpieceDevice = audioManager.availableCommunicationDevices.firstOrNull { device ->
            device.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        } ?: return false
        return runCatching { audioManager.setCommunicationDevice(earpieceDevice) }.getOrDefault(false)
    }

    private fun preferBluetoothRoute(): Boolean {
        if (!audioManager.isBluetoothScoAvailableOffCall) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val bluetoothDevice = audioManager.availableCommunicationDevices.firstOrNull { device ->
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
            } ?: return false
            runCatching { audioManager.setCommunicationDevice(bluetoothDevice) }.getOrDefault(false)
        } else {
            runCatching { audioManager.startBluetoothSco() }
            runCatching { audioManager.isBluetoothScoOn = true }
            true
        }
    }

    private fun stopBluetoothRouting() {
        runCatching { audioManager.stopBluetoothSco() }
        runCatching { audioManager.isBluetoothScoOn = false }
    }

    private fun computeAvailableAudioRoutes(): List<VoiceAudioRoute> {
        val routes = mutableListOf<VoiceAudioRoute>()
        if (hasBuiltInEarpiece()) routes += VoiceAudioRoute.EARPIECE
        if (hasBluetoothRoute()) routes += VoiceAudioRoute.BLUETOOTH
        routes += VoiceAudioRoute.SPEAKER
        return routes.distinct()
    }

    private fun hasBuiltInEarpiece(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        } else {
            true
        }

    private fun hasBluetoothRoute(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices.any { device ->
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS or AudioManager.GET_DEVICES_OUTPUTS).any { device ->
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            }
        } else {
            audioManager.isBluetoothScoOn
        }

    private fun clearPreferredCommunicationDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.clearCommunicationDevice() }
        }
    }

    private fun currentCommunicationDeviceSummary(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice?.let { device ->
                "${device.type}:${device.productName}"
            } ?: "none"
        } else {
            "legacy"
        }

    private fun onAudioDevicesChanged() {
        if (!audioSessionActive) return
        val routeChanged = refreshAvailableAudioRoutes()
        if (routeChanged) {
            applyCurrentAudioRoute()
        }
    }

    private fun refreshAvailableAudioRoutes(): Boolean {
        val availableRoutes = computeAvailableAudioRoutes()
        val resolvedRoute = when {
            availableRoutes.contains(currentState.currentAudioRoute) -> currentState.currentAudioRoute
            availableRoutes.contains(VoiceAudioRoute.EARPIECE) -> VoiceAudioRoute.EARPIECE
            else -> VoiceAudioRoute.SPEAKER
        }
        val nextState = currentState.copy(
            isSpeakerOn = resolvedRoute == VoiceAudioRoute.SPEAKER,
            currentAudioRoute = resolvedRoute,
            availableAudioRoutes = availableRoutes
        )
        val routeChanged = resolvedRoute != currentState.currentAudioRoute
        if (nextState != currentState) {
            currentState = nextState
            callbacks.onStateChanged(nextState)
        }
        return routeChanged
    }

    private fun registerAudioDeviceCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !audioDeviceCallbackRegistered) {
            audioDeviceCallback?.let { callback ->
                runCatching { audioManager.registerAudioDeviceCallback(callback, callHandler) }
                audioDeviceCallbackRegistered = true
            }
        }
    }

    private fun unregisterAudioDeviceCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioDeviceCallbackRegistered) {
            audioDeviceCallback?.let { callback ->
                runCatching { audioManager.unregisterAudioDeviceCallback(callback) }
            }
            audioDeviceCallbackRegistered = false
        }
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { }
                .build()
                .also { audioFocusRequest = it }
            runCatching { audioManager.requestAudioFocus(request) }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
            }
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { request ->
                runCatching { audioManager.abandonAudioFocusRequest(request) }
            }
        } else {
            @Suppress("DEPRECATION")
            runCatching { audioManager.abandonAudioFocus(null) }
        }
    }

    private fun clearPeerConnection() {
        stopStatsSampling()
        if (isClearingPeerConnection) return
        isClearingPeerConnection = true
        peerGeneration += 1L
        debugLog("clear_peer_connection", "conversationId=${currentState.conversationId} phase=${currentState.phase} status=${currentState.statusMessage}")
        cancelDisconnectTimeout()
        signalingReconnectPending = false
        signalingConnected = true
        iceRestartInFlight = false
        pendingIceCandidates.clear()
        remoteDescriptionSet = false
        val connection = peerConnection
        val audioTrack = localAudioTrack
        val audioSource = localAudioSource
        peerConnection = null
        localAudioTrack = null
        localAudioSource = null
        try {
            runCatching { connection?.close() }
            runCatching { connection?.dispose() }
            runCatching { audioTrack?.dispose() }
            runCatching { audioSource?.dispose() }
        } finally {
            restoreAudioSession()
            isClearingPeerConnection = false
        }
    }

    private fun flushPendingIceCandidates() {
        val connection = peerConnection ?: return
        val iterator = pendingIceCandidates.toList()
        pendingIceCandidates.clear()
        iterator.forEach(connection::addIceCandidate)
    }

    private fun finishWithTerminalState(phase: VoiceCallPhase, statusMessage: String) {
        warnLog("finish_terminal_state", "conversationId=${currentState.conversationId} phase=$phase reason=$statusMessage")
        clearPeerConnection()
        updateState(
            currentState.copy(
                phase = phase,
                statusMessage = statusMessage
            )
        )
    }

    private fun clearToIdle() {
        debugLog("clear_to_idle", "conversationId=${currentState.conversationId}")
        clearPeerConnection()
        updateState(VoiceCallUiState())
    }

    private fun enterReconnectWindow(statusMessage: String = "reconnecting") {
        if (!reconnectPolicy.canReconnect(currentState.phase)) return
        warnLog("enter_reconnect_window", "conversationId=${currentState.conversationId} phase=${currentState.phase} status=${currentState.statusMessage} startedAt=${currentState.startedAt}")
        val reconnectPhase = reconnectPolicy.reconnectPhase(currentState.phase, currentState.startedAt)
        if (currentState.phase != reconnectPhase || currentState.statusMessage != statusMessage) {
            updateState(
                currentState.copy(
                    phase = reconnectPhase,
                    statusMessage = statusMessage
                )
            )
        }
        scheduleDisconnectTimeout()
        maybeRequestIceRestart()
    }

    private fun scheduleDisconnectTimeout() {
        cancelDisconnectTimeout()
        debugLog("schedule_disconnect_timeout", "conversationId=${currentState.conversationId} delayMs=${reconnectPolicy.gracePeriodMs}")
        disconnectTimeoutRunnable = Runnable {
            if (reconnectPolicy.shouldFailAfterTimeout(currentState.phase)) {
                warnLog("disconnect_timeout_fired", "conversationId=${currentState.conversationId} phase=${currentState.phase} status=${currentState.statusMessage}")
                finishWithTerminalState(VoiceCallPhase.FAILED, "connection_lost")
            }
        }.also { runnable ->
            callHandler.postDelayed(runnable, reconnectPolicy.gracePeriodMs)
        }
    }

    private fun cancelDisconnectTimeout() {
        if (disconnectTimeoutRunnable != null) {
            debugLog("cancel_disconnect_timeout", "conversationId=${currentState.conversationId}")
        }
        disconnectTimeoutRunnable?.let(callHandler::removeCallbacks)
        disconnectTimeoutRunnable = null
    }

    private fun startStatsSampling() {
        if (statsSamplingRunnable != null) return
        statsSamplingRunnable = object : Runnable {
            override fun run() {
                val connection = peerConnection
                if (connection == null || currentState.phase == VoiceCallPhase.IDLE) {
                    stopStatsSampling()
                    return
                }
                connection.getStats { report ->
                    val records = report.statsMap.values.map { stat ->
                        RtcStatRecord(type = stat.type, members = stat.members)
                    }
                    val quality = WebRtcStatsParser.parse(records)
                    enqueue("stats_sample") {
                        if (connection !== peerConnection || !quality.hasMetrics) return@enqueue
                        updateState(currentState.copy(quality = quality))
                        FamilyChatDiagnostics.sampled(
                            "webrtc_quality",
                            10_000L,
                            "conversation_id" to currentState.conversationId,
                            "loss_percent" to quality.packetLossPercent,
                            "jitter_ms" to quality.jitterMs,
                            "rtt_ms" to quality.roundTripTimeMs,
                        )
                    }
                }
                callHandler.postDelayed(this, statsSamplingIntervalMs)
            }
        }.also(callHandler::post)
    }

    private fun stopStatsSampling() {
        statsSamplingRunnable?.let(callHandler::removeCallbacks)
        statsSamplingRunnable = null
    }

    private fun updateState(next: VoiceCallUiState) {
        val availableRoutes = computeAvailableAudioRoutes()
        val desiredRoute = if (availableRoutes.contains(next.currentAudioRoute)) {
            next.currentAudioRoute
        } else if (availableRoutes.contains(VoiceAudioRoute.EARPIECE)) {
            VoiceAudioRoute.EARPIECE
        } else {
            VoiceAudioRoute.SPEAKER
        }
        val decorated = next.copy(
            isSpeakerOn = desiredRoute == VoiceAudioRoute.SPEAKER,
            currentAudioRoute = desiredRoute,
            availableAudioRoutes = availableRoutes
        )
        if (decorated != currentState) {
            debugLog(
                "state_updated",
                "conversationId=${decorated.conversationId} phase=${currentState.phase}->${decorated.phase} status=${currentState.statusMessage}->${decorated.statusMessage} incoming=${decorated.isIncoming} startedAt=${decorated.startedAt}"
            )
        }
        currentState = decorated
        callbacks.onStateChanged(decorated)
    }

    private fun isCurrentPeer(connection: PeerConnection, generation: Long): Boolean =
        generation == peerGeneration && connection === peerConnection && !isClearingPeerConnection

    private fun maybeRequestIceRestart() {
        if (peerConnection == null) return
        if (!signalingConnected) return
        if (currentState.isIncoming) return
        if (!remoteDescriptionSet) return
        if (
            currentState.phase == VoiceCallPhase.IDLE ||
            currentState.phase == VoiceCallPhase.ENDED ||
            currentState.phase == VoiceCallPhase.FAILED
        ) return
        val now = System.currentTimeMillis()
        if (!reconnectPolicy.canRequestIceRestart(iceRestartInFlight, lastIceRestartAt, now)) return
        lastIceRestartAt = now
        iceRestartInFlight = true
        warnLog("request_ice_restart", "conversationId=${currentState.conversationId} phase=${currentState.phase} status=${currentState.statusMessage}")
        createOffer(iceRestart = true)
    }

    private fun enqueue(operation: String, block: () -> Unit) {
        val task = Runnable {
            try {
                block()
            } catch (error: RuntimeException) {
                warnLog("operation_failure", "operation=$operation error=${error.javaClass.simpleName}")
                FamilyChatDiagnostics.event(
                    "call_operation_failed",
                    "operation" to operation,
                    "error" to error.javaClass.name,
                    "phase" to currentState.phase.name,
                    "conversation_id" to currentState.conversationId,
                )
                callbacks.onError("Call failed")
            }
        }
        if (Looper.myLooper() == callThread.looper) {
            task.run()
        } else if (!callHandler.post(task)) {
            warnLog("operation_rejected", "operation=$operation")
        }
    }

    private fun callBreadcrumb(stage: String) {
        FamilyChatDiagnostics.event(
            "call_stage",
            "stage" to stage,
            "phase" to currentState.phase.name,
            "conversation_id" to currentState.conversationId,
            "thread" to Thread.currentThread().name,
        )
    }

    private fun defaultIceServers(): List<CallIceServerConfig> =
        listOf(CallIceServerConfig(urls = listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302")))

    private fun debugLog(event: String, message: String) {
        Log.d(logTag, "$event | $message")
    }

    private fun warnLog(event: String, message: String) {
        Log.w(logTag, "$event | $message")
    }
}

private open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription?) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(message: String?) = Unit
    override fun onSetFailure(message: String?) = Unit
}

private fun List<CallIceServerConfig>.toPeerConnectionServers(): List<PeerConnection.IceServer> =
    flatMap { config ->
        config.urls.map { url ->
            PeerConnection.IceServer.builder(url)
                .setUsername(config.username)
                .setPassword(config.credential)
                .createIceServer()
        }
    }

private fun JSONObject.toSessionDescription(): SessionDescription? {
    val typeText = optString("type").trim()
    val sdp = optString("sdp")
    if (typeText.isBlank() || sdp.isBlank()) return null
    val type = when (typeText.lowercase()) {
        "offer" -> SessionDescription.Type.OFFER
        "answer" -> SessionDescription.Type.ANSWER
        "pranswer" -> SessionDescription.Type.PRANSWER
        "rollback" -> SessionDescription.Type.ROLLBACK
        else -> return null
    }
    return SessionDescription(type, sdp)
}

private fun JSONObject.toIceCandidate(): IceCandidate? {
    val sdpMid = optString("sdpMid").ifBlank { null }
    val sdpMLineIndex = optInt("sdpMLineIndex", -1)
    val candidate = optString("candidate")
    if (sdpMid == null || sdpMLineIndex < 0 || candidate.isBlank()) return null
    return IceCandidate(sdpMid, sdpMLineIndex, candidate)
}
