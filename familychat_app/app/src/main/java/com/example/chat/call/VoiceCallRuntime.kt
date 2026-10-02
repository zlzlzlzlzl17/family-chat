package com.example.chat

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object VoiceCallRuntime {
    interface Controller {
        fun start(conversationId: Long)
        fun accept()
        fun decline()
        fun end()
        fun toggleMute()
        fun toggleSpeaker()
        fun selectAudioRoute(route: VoiceAudioRoute)
        fun syncAudioRouteFromSystem(route: VoiceAudioRoute)
        fun dismissTerminal()
    }

    private val _state = MutableStateFlow(VoiceCallUiState())
    val state: StateFlow<VoiceCallUiState> = _state
    private val _isMinimized = MutableStateFlow(false)
    val isMinimized: StateFlow<Boolean> = _isMinimized
    private val _isAppInForeground = MutableStateFlow(true)
    val isAppInForeground: StateFlow<Boolean> = _isAppInForeground

    @Volatile
    private var controller: Controller? = null

    fun attach(controller: Controller) {
        this.controller = controller
    }

    fun detach() {
        controller = null
    }

    fun updateState(context: Context, state: VoiceCallUiState) {
        _state.value = state
        if (
            state.phase == VoiceCallPhase.IDLE ||
            state.phase == VoiceCallPhase.INCOMING ||
            state.phase == VoiceCallPhase.ENDED ||
            state.phase == VoiceCallPhase.FAILED
        ) {
            _isMinimized.value = false
        }
        VoiceCallNotificationService.sync(
            context.applicationContext,
            state,
            _isMinimized.value,
            _isAppInForeground.value
        )
        FamilyChatTelecom.sync(context.applicationContext, state)
    }

    fun setMinimized(context: Context, minimized: Boolean) {
        _isMinimized.value = minimized
        VoiceCallNotificationService.sync(
            context.applicationContext,
            _state.value,
            _isMinimized.value,
            _isAppInForeground.value
        )
    }

    fun setAppForeground(context: Context, isForeground: Boolean) {
        _isAppInForeground.value = isForeground
        VoiceCallNotificationService.sync(
            context.applicationContext,
            _state.value,
            _isMinimized.value,
            _isAppInForeground.value
        )
    }

    fun accept() {
        controller?.accept()
    }

    fun start(conversationId: Long) {
        controller?.start(conversationId)
    }

    fun decline() {
        controller?.decline()
    }

    fun end() {
        controller?.end()
    }

    fun toggleMute() {
        controller?.toggleMute()
    }

    fun toggleSpeaker() {
        controller?.toggleSpeaker()
    }

    fun selectAudioRoute(route: VoiceAudioRoute) {
        controller?.selectAudioRoute(route)
    }

    fun syncAudioRouteFromSystem(route: VoiceAudioRoute) {
        controller?.syncAudioRouteFromSystem(route)
    }

    fun dismissTerminal() {
        controller?.dismissTerminal()
    }
}

class VoiceCallNotificationService : android.app.Service() {
    companion object {
        private const val CHANNEL_ID = "voice_call_channel"
        private const val CHANNEL_NAME = "Voice Calls"
        private const val NOTIFICATION_ID = 3201
        private const val ACTION_SYNC = "com.example.chat.VOICE_CALL_SYNC"
        private const val ACTION_ACCEPT = "com.example.chat.VOICE_CALL_ACCEPT"
        private const val ACTION_DECLINE = "com.example.chat.VOICE_CALL_DECLINE"
        private const val ACTION_END = "com.example.chat.VOICE_CALL_END"
        private const val EXTRA_PHASE = "phase"
        private const val EXTRA_CONVERSATION_ID = "conversation_id"
        private const val EXTRA_PEER_USER_CODE = "peer_user_code"
        private const val EXTRA_PEER_USERNAME = "peer_username"
        private const val EXTRA_IS_INCOMING = "is_incoming"
        private const val EXTRA_IS_MUTED = "is_muted"
        private const val EXTRA_IS_SPEAKER_ON = "is_speaker_on"
        private const val EXTRA_STARTED_AT = "started_at"
        private const val EXTRA_STATUS_MESSAGE = "status_message"
        private const val EXTRA_IS_MINIMIZED = "is_minimized"
        private const val EXTRA_IS_APP_FOREGROUND = "is_app_foreground"

        fun sync(
            context: Context,
            state: VoiceCallUiState,
            isMinimized: Boolean,
            isAppInForeground: Boolean,
        ) {
            val intent = Intent(context, VoiceCallNotificationService::class.java).apply {
                action = ACTION_SYNC
                putExtra(EXTRA_PHASE, state.phase.name)
                putExtra(EXTRA_CONVERSATION_ID, state.conversationId)
                putExtra(EXTRA_PEER_USER_CODE, state.peerUserCode)
                putExtra(EXTRA_PEER_USERNAME, state.peerUsername)
                putExtra(EXTRA_IS_INCOMING, state.isIncoming)
                putExtra(EXTRA_IS_MUTED, state.isMuted)
                putExtra(EXTRA_IS_SPEAKER_ON, state.isSpeakerOn)
                putExtra(EXTRA_STARTED_AT, state.startedAt)
                putExtra(EXTRA_STATUS_MESSAGE, state.statusMessage)
                putExtra(EXTRA_IS_MINIMIZED, isMinimized)
                putExtra(EXTRA_IS_APP_FOREGROUND, isAppInForeground)
            }
            if (state.phase == VoiceCallPhase.IDLE) {
                context.stopService(intent)
            } else {
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
            }
        }
    }

    private var currentState = VoiceCallUiState()
    private var incomingRingtone: android.media.Ringtone? = null
    private var isMinimized = false
    private var isAppInForeground = true
    private var windowManager: android.view.WindowManager? = null
    private var overlayView: android.view.View? = null
    private var overlayTitleView: android.widget.TextView? = null
    private var overlaySubtitleView: android.widget.TextView? = null
    private var overlayLayoutParams: android.view.WindowManager.LayoutParams? = null
    private val overlayHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var overlayTicker: Runnable? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_SYNC) {
            ACTION_ACCEPT -> VoiceCallRuntime.accept()
            ACTION_DECLINE -> VoiceCallRuntime.decline()
            ACTION_END -> VoiceCallRuntime.end()
            ACTION_SYNC -> {
                currentState = intent?.toVoiceCallState() ?: VoiceCallRuntime.state.value
                isMinimized = intent?.getBooleanExtra(EXTRA_IS_MINIMIZED, VoiceCallRuntime.isMinimized.value)
                    ?: VoiceCallRuntime.isMinimized.value
                isAppInForeground = intent?.getBooleanExtra(EXTRA_IS_APP_FOREGROUND, VoiceCallRuntime.isAppInForeground.value)
                    ?: VoiceCallRuntime.isAppInForeground.value
            }
        }
        updateNotification()
        updateOverlay()
        return START_STICKY
    }

    override fun onDestroy() {
        stopIncomingRingtone()
        removeOverlay()
        super.onDestroy()
    }

    private fun updateNotification() {
        val state = currentState
        if (state.phase == VoiceCallPhase.IDLE) {
            stopIncomingRingtone()
            stopForeground(STOP_FOREGROUND_REMOVE)
            removeOverlay()
            stopSelf()
            return
        }

        ensureNotificationChannel()
        if (state.phase == VoiceCallPhase.INCOMING) startIncomingRingtone() else stopIncomingRingtone()

        val notification = buildNotification(state)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val foregroundType = when {
                state.phase == VoiceCallPhase.INCOMING ->
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                hasRecordAudioPermission() ->
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                else ->
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            }
            startForeground(
                NOTIFICATION_ID,
                notification,
                foregroundType
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun hasRecordAudioPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun buildNotification(state: VoiceCallUiState): android.app.Notification {
        val prefs = ChatPreferences(applicationContext)
        val strings = stringsFor(AppLanguage.fromStored(prefs.language))
        val openIntent = android.content.Intent(this, MainActivity::class.java).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val contentIntent = android.app.PendingIntent.getActivity(
            this,
            100,
            openIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentImmutableFlag()
        )
        val title = when (state.phase) {
            VoiceCallPhase.INCOMING -> strings.incomingCall
            else -> state.peerUsername.ifBlank { strings.voiceCall }
        }
        val builder = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle(title)
            .setContentText(state.statusMessage.ifBlank { strings.voiceCall })
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_CALL)
            .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(
                if (state.phase == VoiceCallPhase.INCOMING) androidx.core.app.NotificationCompat.PRIORITY_MAX
                else androidx.core.app.NotificationCompat.PRIORITY_HIGH
            )
            .setOngoing(state.phase != VoiceCallPhase.ENDED && state.phase != VoiceCallPhase.FAILED)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setForegroundServiceBehavior(androidx.core.app.NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (state.phase == VoiceCallPhase.ACTIVE && state.startedAt > 0L) {
            builder.setWhen(state.startedAt).setUsesChronometer(true)
        }

        if (state.phase == VoiceCallPhase.INCOMING) {
            builder.setFullScreenIntent(contentIntent, true)
            builder.addAction(
                0,
                strings.decline,
                serviceActionPendingIntent(ACTION_DECLINE, 201)
            )
            builder.addAction(
                0,
                strings.accept,
                serviceActionPendingIntent(ACTION_ACCEPT, 202)
            )
        } else if (state.phase != VoiceCallPhase.ENDED && state.phase != VoiceCallPhase.FAILED) {
            builder.addAction(
                0,
                strings.endCall,
                serviceActionPendingIntent(ACTION_END, 203)
            )
        }

        return builder.build()
    }

    private fun updateOverlay() {
        if (!shouldShowOverlay()) {
            removeOverlay()
            return
        }
        if (overlayView == null) {
            showOverlay()
        } else {
            refreshOverlayView()
        }
        startOverlayTickerIfNeeded()
    }

    private fun shouldShowOverlay(): Boolean =
        isMinimized &&
            !isAppInForeground &&
            currentState.phase != VoiceCallPhase.IDLE &&
            currentState.phase != VoiceCallPhase.ENDED &&
            currentState.phase != VoiceCallPhase.FAILED &&
            canDrawOverlays()

    private fun canDrawOverlays(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M ||
            android.provider.Settings.canDrawOverlays(this)

    private fun showOverlay() {
        val manager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        windowManager = manager
        val density = resources.displayMetrics.density
        val card = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((14 * density).toInt(), (10 * density).toInt(), (14 * density).toInt(), (10 * density).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 24f * density
                setColor(android.graphics.Color.parseColor("#E61A1F29"))
                setStroke((1.2f * density).toInt(), android.graphics.Color.parseColor("#334455"))
            }
            elevation = 12f * density
        }
        val avatar = android.widget.TextView(this).apply {
            text = currentState.peerUsername.take(1).uppercase().ifBlank { "F" }
            gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            textSize = 15f
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(android.graphics.Color.parseColor("#128C7E"))
            }
            layoutParams = android.widget.LinearLayout.LayoutParams((38 * density).toInt(), (38 * density).toInt())
        }
        val textContainer = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (10 * density).toInt()
                marginEnd = (10 * density).toInt()
            }
        }
        val title = android.widget.TextView(this).apply {
            id = android.view.View.generateViewId()
            setTextColor(android.graphics.Color.WHITE)
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val subtitle = android.widget.TextView(this).apply {
            id = android.view.View.generateViewId()
            setTextColor(android.graphics.Color.parseColor("#B8C4D6"))
            textSize = 12f
        }
        textContainer.addView(title)
        textContainer.addView(subtitle)
        val endButton = android.widget.ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(android.graphics.Color.parseColor("#FF6B6B"))
            setPadding((6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt())
            setOnClickListener { VoiceCallRuntime.end() }
        }
        card.addView(avatar)
        card.addView(textContainer)
        card.addView(endButton)

        val params = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                android.view.WindowManager.LayoutParams.TYPE_PHONE
            },
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            x = (resources.displayMetrics.widthPixels * 0.55f).toInt()
            y = (resources.displayMetrics.heightPixels * 0.2f).toInt()
        }

        bindOverlayInteractions(card, params)
        overlayTitleView = title
        overlaySubtitleView = subtitle
        overlayView = card
        overlayLayoutParams = params
        refreshOverlayView()
        runCatching { manager.addView(card, params) }
    }

    private fun bindOverlayInteractions(
        view: android.view.View,
        params: android.view.WindowManager.LayoutParams
    ) {
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        view.setOnTouchListener(object : android.view.View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var dragging = false

            override fun onTouch(v: android.view.View, event: android.view.MotionEvent): Boolean {
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        dragging = false
                        return true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (!dragging && kotlin.math.abs(dx) + kotlin.math.abs(dy) > touchSlop) {
                            dragging = true
                        }
                        if (dragging) {
                            params.x = initialX + dx
                            params.y = initialY + dy
                            overlayLayoutParams = params
                            runCatching { windowManager?.updateViewLayout(v, params) }
                        }
                        return true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        if (!dragging) {
                            v.performClick()
                            VoiceCallRuntime.setMinimized(applicationContext, false)
                            val openIntent = Intent(this@VoiceCallNotificationService, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            }
                            runCatching { startActivity(openIntent) }
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun refreshOverlayView() {
        overlayTitleView?.text = currentState.peerUsername.ifBlank { "Family Chat" }
        overlaySubtitleView?.text = overlaySubtitle()
    }

    private fun overlaySubtitle(): String =
        if (currentState.phase == VoiceCallPhase.ACTIVE && currentState.startedAt > 0L) {
            val elapsedSeconds = ((System.currentTimeMillis() - currentState.startedAt) / 1000L).coerceAtLeast(0L)
            String.format(java.util.Locale.US, "%02d:%02d", elapsedSeconds / 60L, elapsedSeconds % 60L)
        } else {
            currentState.statusMessage
        }

    private fun startOverlayTickerIfNeeded() {
        if (currentState.phase != VoiceCallPhase.ACTIVE || overlayView == null) {
            stopOverlayTicker()
            return
        }
        if (overlayTicker != null) return
        overlayTicker = object : Runnable {
            override fun run() {
                refreshOverlayView()
                overlayHandler.postDelayed(this, 1_000L)
            }
        }.also { overlayHandler.post(it) }
    }

    private fun stopOverlayTicker() {
        overlayTicker?.let(overlayHandler::removeCallbacks)
        overlayTicker = null
    }

    private fun removeOverlay() {
        stopOverlayTicker()
        overlayView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        overlayView = null
        overlayTitleView = null
        overlaySubtitleView = null
        overlayLayoutParams = null
        windowManager = null
    }

    private fun serviceActionPendingIntent(action: String, requestCode: Int): android.app.PendingIntent {
        val intent = Intent(this, VoiceCallNotificationService::class.java).apply { this.action = action }
        return android.app.PendingIntent.getService(
            this,
            requestCode,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentImmutableFlag()
        )
    }

    private fun startIncomingRingtone() {
        if (incomingRingtone?.isPlaying == true) return
        val ringtoneUri =
            android.media.RingtoneManager.getActualDefaultRingtoneUri(this, android.media.RingtoneManager.TYPE_RINGTONE)
                ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
        val ringtone = android.media.RingtoneManager.getRingtone(this, ringtoneUri) ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            ringtone.isLooping = true
        }
        ringtone.audioAttributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        runCatching { ringtone.play() }
        incomingRingtone = ringtone
    }

    private fun stopIncomingRingtone() {
        runCatching { incomingRingtone?.stop() }
        incomingRingtone = null
    }

    private fun ensureNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = android.app.NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            android.app.NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Ongoing and incoming voice calls"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    private fun pendingIntentImmutableFlag(): Int =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) android.app.PendingIntent.FLAG_IMMUTABLE else 0

    private fun Intent.toVoiceCallState(): VoiceCallUiState =
        VoiceCallUiState(
            phase = runCatching { VoiceCallPhase.valueOf(getStringExtra(EXTRA_PHASE).orEmpty()) }.getOrDefault(VoiceCallPhase.IDLE),
            conversationId = getLongExtra(EXTRA_CONVERSATION_ID, 0L),
            peerUserCode = getStringExtra(EXTRA_PEER_USER_CODE).orEmpty(),
            peerUsername = getStringExtra(EXTRA_PEER_USERNAME).orEmpty(),
            isIncoming = getBooleanExtra(EXTRA_IS_INCOMING, false),
            isMuted = getBooleanExtra(EXTRA_IS_MUTED, false),
            isSpeakerOn = getBooleanExtra(EXTRA_IS_SPEAKER_ON, false),
            startedAt = getLongExtra(EXTRA_STARTED_AT, 0L),
            statusMessage = getStringExtra(EXTRA_STATUS_MESSAGE).orEmpty(),
        )
}
