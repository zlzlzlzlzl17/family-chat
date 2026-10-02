package com.example.chat

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class FamilyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        FamilyChatDiagnostics.event("fcm_token_changed", "token_length" to token.length)
        FcmRegistrar(applicationContext).registerToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        NotificationCenter.ensureChannel(this)
        val data = message.data
        FamilyChatDiagnostics.event(
            "fcm_message_received",
            "type" to data["type"].orEmpty(),
            "conversation_id" to data["conversation_id"].orEmpty(),
            "message_id" to data["message_id"].orEmpty(),
            "transport_delay_ms" to (System.currentTimeMillis() - message.sentTime).coerceAtLeast(0L)
        )
        when (data["type"]) {
            "push_diagnostic" -> {
                val diagnosticId = data["diagnostic_id"].orEmpty()
                if (diagnosticId.isNotBlank()) {
                    ChatPreferences(applicationContext).apply {
                        lastPushDiagnosticId = diagnosticId
                        lastPushDiagnosticAt = System.currentTimeMillis()
                    }
                }
                FamilyChatDiagnostics.event("fcm_diagnostic_received")
                return
            }
            "incoming_call" -> {
                val invite = PendingIncomingCallInvite(
                    conversationId = data["conversation_id"]?.toLongOrNull() ?: 0L,
                    peerUserCode = data["peer_user_code"].orEmpty(),
                    peerUsername = data["peer_username"].orEmpty(),
                    createdAt = data["created_at"]?.toLongOrNull() ?: System.currentTimeMillis(),
                )
                if (invite.conversationId > 0L && invite.peerUsername.isNotBlank()) {
                    ChatPreferences(applicationContext).setPendingIncomingCall(invite)
                    VoiceCallNotificationService.sync(
                        context = this,
                        state = VoiceCallUiState(
                            phase = VoiceCallPhase.INCOMING,
                            conversationId = invite.conversationId,
                            peerUserCode = invite.peerUserCode,
                            peerUsername = invite.peerUsername,
                            isIncoming = true,
                            statusMessage = "incoming",
                        ),
                        isMinimized = false,
                        isAppInForeground = false,
                    )
                    NotificationCenter.showIncomingCallNotification(
                        context = this,
                        invite = invite,
                        localeTag = data["locale"].orEmpty()
                    )
                }
                return
            }
            "call_hangup" -> {
                val prefs = ChatPreferences(applicationContext)
                val conversationId = data["conversation_id"]?.toLongOrNull() ?: 0L
                if (conversationId > 0L && prefs.pendingIncomingCall()?.conversationId == conversationId) {
                    prefs.setPendingIncomingCall(null)
                }
                VoiceCallNotificationService.sync(
                    context = this,
                    state = VoiceCallUiState(),
                    isMinimized = false,
                    isAppInForeground = false,
                )
                NotificationCenter.clearIncomingCallNotification(this)
                return
            }
            "chat" -> {
                FcmRegistrar(applicationContext).reportDelivered(
                    conversationId = data["conversation_id"]?.toLongOrNull() ?: 0L,
                    messageId = data["message_id"]?.toLongOrNull() ?: 0L
                )
            }
            "blog_comment" -> {
                val preferences = ChatPreferences(applicationContext)
                if (preferences.blogNotificationsEnabled) {
                    NotificationCenter.showBlogCommentNotification(
                        context = this,
                        postId = data["blog_post_id"]?.toLongOrNull() ?: 0L,
                        localeTag = data["locale"].orEmpty(),
                    )
                }
                FamilyChatDiagnostics.event(
                    "fcm_blog_comment_handled",
                    "post_id" to data["blog_post_id"].orEmpty(),
                    "enabled" to preferences.blogNotificationsEnabled,
                )
                return
            }
        }
        val unreadCount = data["unread_count"]?.toIntOrNull() ?: 1
        val locale = data["locale"].orEmpty()
        val title = data["title"] ?: message.notification?.title
        val body = data["body"] ?: message.notification?.body

        // Muting is deliberately applied here and not earlier: reportDelivered
        // above must still run, or muting a conversation would stall the
        // sender's delivery ticks. Mute silences the notification, nothing else.
        val mutedConversationId = data["conversation_id"]?.toLongOrNull() ?: 0L
        val muted = ChatPreferences(applicationContext).isConversationMuted(mutedConversationId)
        if (!muted) {
            NotificationCenter.showUnreadNotification(
                context = this,
                unreadCount = unreadCount,
                localeTag = locale,
                titleOverride = title,
                bodyOverride = body
            )
        }
        FamilyChatDiagnostics.event(
            "fcm_notification_posted",
            "conversation_id" to data["conversation_id"].orEmpty(),
            "message_id" to data["message_id"].orEmpty(),
            "unread_count" to unreadCount,
            "notifications_enabled" to NotificationCenter.messageNotificationsEnabled(this)
        )
    }
}
