package com.example.chat

import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

internal data class PushSelfTestRequestResult(
    val serverReachable: Boolean,
    val requestAccepted: Boolean,
    val statusCode: Int = 0,
    val reason: String = "",
)

class FcmRegistrar(private val context: Context) {
    private val prefs = ChatPreferences(context)
    private val client = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    fun registerCurrentTokenIfAvailable(
        authTokenOverride: String? = null,
        serverUrlOverride: String? = null,
    ) {
        if (authTokenOverride == null && serverUrlOverride == null) {
            enqueueTokenSync(context, "app_session_ready")
            return
        }
        if (FirebaseApp.initializeApp(context) == null) {
            FamilyChatDiagnostics.event("fcm_init_failed", "reason" to "missing_firebase_config")
            return
        }
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            registerToken(token, authTokenOverride, serverUrlOverride)
        }.addOnFailureListener { error ->
            FamilyChatDiagnostics.event(
                "fcm_get_token_failed",
                "error" to error.javaClass.simpleName,
                "detail" to error.message.orEmpty()
            )
        }
    }

    fun unregisterCurrentTokenIfAvailable(
        authTokenOverride: String? = null,
        serverUrlOverride: String? = null,
    ) {
        FirebaseApp.initializeApp(context) ?: return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            unregisterToken(token, authTokenOverride, serverUrlOverride)
        }
    }

    fun registerToken(
        token: String,
        authTokenOverride: String? = null,
        serverUrlOverride: String? = null,
    ) {
        if (authTokenOverride == null && serverUrlOverride == null) {
            enqueueTokenSync(context, "firebase_token_changed")
            return
        }
        registerTokenAsync(token, authTokenOverride, serverUrlOverride)
    }

    internal fun registerTokenBlocking(
        token: String,
        authTokenOverride: String? = null,
        serverUrlOverride: String? = null,
    ): Boolean {
        val serverUrl = serverUrlOverride ?: prefs.serverUrl
        val authToken = authTokenOverride ?: prefs.token
        if (
            token.isBlank() ||
            (authToken.isBlank() && prefs.refreshToken.isBlank()) ||
            serverUrl.isBlank()
        ) return false
        val locale = if (prefs.language.equals(AppLanguage.ZH.name, ignoreCase = true)) "zh-CN" else "en-US"
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        val model = Build.MODEL.orEmpty()
        val body = JSONObject()
            .put("token", token)
            .put("platform", "android")
            .put("locale", locale)
            .put("manufacturer", manufacturer)
            .put("model", model)
            .put("blog_notifications_enabled", prefs.blogNotificationsEnabled)
            .toString()
        return executeAuthorized(serverUrl, authToken, authTokenOverride == null) { currentToken ->
            Request.Builder()
                .url("${serverUrl.trimEnd('/')}/api/fcm_register")
                .header(APP_CLIENT_HEADER_NAME, APP_CLIENT_HEADER_VALUE)
                .header("Authorization", "Bearer $currentToken")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        }
    }

    fun unregisterToken(
        token: String,
        authTokenOverride: String? = null,
        serverUrlOverride: String? = null,
    ) {
        val authToken = authTokenOverride ?: prefs.token
        val serverUrl = serverUrlOverride ?: prefs.serverUrl
        if (token.isBlank() || authToken.isBlank() || serverUrl.isBlank()) return
        Thread {
            runCatching {
                val body = JSONObject()
                    .put("token", token)
                    .toString()
                executeAuthorized(serverUrl, authToken, authTokenOverride == null) { currentToken ->
                    Request.Builder()
                        .url("${serverUrl.trimEnd('/')}/api/fcm_unregister")
                        .header(APP_CLIENT_HEADER_NAME, APP_CLIENT_HEADER_VALUE)
                        .header("Authorization", "Bearer $currentToken")
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .build()
                }
            }
        }.start()
    }

    fun reportDelivered(conversationId: Long, messageId: Long) {
        if (conversationId <= 0L || messageId <= 0L) return
        enqueueDeliveryReport(context, conversationId, messageId)
    }

    internal fun reportDeliveredBlocking(conversationId: Long, messageId: Long): Boolean {
        val authToken = prefs.token
        val serverUrl = prefs.serverUrl
        if (
            conversationId <= 0L ||
            messageId <= 0L ||
            (authToken.isBlank() && prefs.refreshToken.isBlank()) ||
            serverUrl.isBlank()
        ) return false
        val body = JSONObject()
            .put("conversation_id", conversationId)
            .put("last_delivered_message_id", messageId)
            .toString()
        return executeAuthorized(serverUrl, authToken, true) { currentToken ->
            Request.Builder()
                .url("${serverUrl.trimEnd('/')}/api/delivered")
                .header(APP_CLIENT_HEADER_NAME, APP_CLIENT_HEADER_VALUE)
                .header("Authorization", "Bearer $currentToken")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        }
    }

    internal suspend fun ensureCurrentTokenRegisteredForSelfTest(): Boolean {
        if (FirebaseApp.initializeApp(context) == null) return false
        val token = withTimeoutOrNull(10_000L) {
            suspendCancellableCoroutine<String?> { continuation ->
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    if (continuation.isActive) {
                        val currentToken = if (task.isSuccessful) task.result else null
                        continuation.resume(currentToken?.takeIf(String::isNotBlank))
                    }
                }
            }
        } ?: return false
        return withContext(Dispatchers.IO) { registerTokenBlocking(token) }
    }

    internal fun requestPushSelfTestBlocking(diagnosticId: String): PushSelfTestRequestResult {
        val authToken = prefs.token
        val serverUrl = prefs.serverUrl
        if (diagnosticId.isBlank()) {
            return PushSelfTestRequestResult(false, false, reason = "bad_diagnostic_id")
        }
        if (authToken.isBlank() && prefs.refreshToken.isBlank()) {
            return PushSelfTestRequestResult(false, false, reason = "missing_session")
        }
        val body = JSONObject().put("diagnostic_id", diagnosticId).toString()
        val requestFactory: (String) -> Request = { currentToken ->
            Request.Builder()
                .url("${serverUrl.trimEnd('/')}/api/push_self_test")
                .header(APP_CLIENT_HEADER_NAME, APP_CLIENT_HEADER_VALUE)
                .header("Authorization", "Bearer $currentToken")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        }
        var usableToken = authToken
        if (usableToken.isBlank() && prefs.refreshToken.isNotBlank()) {
            usableToken = try {
                SessionRefreshCoordinator.refresh(context, serverUrl).token
            } catch (error: Exception) {
                return selfTestRefreshFailure(error, serverResponded = false)
            }
        }
        if (usableToken.isBlank()) {
            return PushSelfTestRequestResult(false, false, reason = "missing_session")
        }
        client.newCall(requestFactory(usableToken)).execute().use { response ->
            if (response.code != 401 || prefs.refreshToken.isBlank()) {
                return parsePushSelfTestResponse(response.code, response.body?.string().orEmpty())
            }
        }
        val refreshed = try {
            SessionRefreshCoordinator.refresh(context, serverUrl)
        } catch (error: Exception) {
            return selfTestRefreshFailure(error, serverResponded = true)
        }
        if (refreshed.token.isBlank()) {
            return PushSelfTestRequestResult(true, false, statusCode = 401, reason = "unauthorized")
        }
        client.newCall(requestFactory(refreshed.token)).execute().use { response ->
            return parsePushSelfTestResponse(response.code, response.body?.string().orEmpty())
        }
    }

    private fun parsePushSelfTestResponse(statusCode: Int, responseBody: String): PushSelfTestRequestResult {
        val payload = runCatching { JSONObject(responseBody) }.getOrNull()
        val dispatched = payload?.optBoolean("delivered", payload.optBoolean("ok", false))
            ?: (statusCode in 200..299)
        val reason = if (statusCode == 401) "unauthorized" else payload?.optString("reason")
            ?.takeIf(String::isNotBlank)
            ?: payload?.optString("error")?.takeIf(String::isNotBlank)
            ?: if (dispatched) "sent" else "request_rejected"
        if (statusCode !in 200..299) {
            FamilyChatDiagnostics.event(
                "fcm_self_test_result",
                "status" to statusCode,
                "reason" to reason,
            )
        }
        return PushSelfTestRequestResult(
            serverReachable = true,
            requestAccepted = dispatched,
            statusCode = statusCode,
            reason = reason,
        )
    }

    private fun selfTestRefreshFailure(error: Exception, serverResponded: Boolean): PushSelfTestRequestResult {
        val response = error as? ChatApiException
        val expired = error.isDefinitiveSessionFailure()
        return PushSelfTestRequestResult(
            serverReachable = serverResponded || response != null,
            requestAccepted = false,
            statusCode = response?.statusCode ?: if (serverResponded) 401 else 0,
            reason = when {
                expired -> "unauthorized"
                serverResponded || response != null -> "session_refresh_failed"
                else -> "network_error"
            },
        )
    }

    private fun executeAuthorized(
        serverUrl: String,
        initialToken: String,
        allowRefresh: Boolean,
        requestFactory: (String) -> Request,
    ): Boolean {
        var usableToken = initialToken
        if (usableToken.isBlank() && allowRefresh && prefs.refreshToken.isNotBlank()) {
            usableToken = SessionRefreshCoordinator.refresh(context, serverUrl).token
        }
        if (usableToken.isBlank()) return false
        client.newCall(requestFactory(usableToken)).execute().use { response ->
            if (response.isSuccessful) return true
            if (response.code != 401 || !allowRefresh || prefs.refreshToken.isBlank()) {
                FamilyChatDiagnostics.event(
                    "fcm_api_rejected",
                    "path" to response.request.url.encodedPath,
                    "status" to response.code
                )
                return false
            }
        }

        val refreshed = SessionRefreshCoordinator.refresh(context, serverUrl)
        client.newCall(requestFactory(refreshed.token)).execute().use { response ->
            return response.isSuccessful
        }
    }

    private fun registerTokenAsync(
        token: String,
        authTokenOverride: String?,
        serverUrlOverride: String?,
    ) {
        Thread {
            runCatching {
                check(registerTokenBlocking(token, authTokenOverride, serverUrlOverride))
            }.onSuccess {
                FamilyChatDiagnostics.event("fcm_token_registered")
            }.onFailure { error ->
                FamilyChatDiagnostics.event(
                    "fcm_token_register_failed",
                    "error" to error.javaClass.simpleName,
                    "detail" to error.message.orEmpty()
                )
                if (authTokenOverride == null && serverUrlOverride == null) {
                    enqueueTokenSync(context, "direct_registration_failed")
                }
            }
        }.start()
    }

    companion object {
        const val TOKEN_SYNC_WORK_NAME = "family-chat-fcm-token-sync"
        const val PERIODIC_TOKEN_SYNC_WORK_NAME = "family-chat-fcm-token-health"
        const val DELIVERY_CONVERSATION_ID = "conversation_id"
        const val DELIVERY_MESSAGE_ID = "message_id"

        fun enqueueTokenSync(context: Context, reason: String) {
            FamilyChatDiagnostics.event("fcm_token_sync_enqueued", "reason" to reason)
            val request = OneTimeWorkRequestBuilder<FcmTokenSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                TOKEN_SYNC_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun schedulePeriodicTokenSync(context: Context) {
            val request = PeriodicWorkRequestBuilder<FcmTokenSyncWorker>(12, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                PERIODIC_TOKEN_SYNC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun cancelBackgroundWork(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(TOKEN_SYNC_WORK_NAME)
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(PERIODIC_TOKEN_SYNC_WORK_NAME)
        }

        private fun enqueueDeliveryReport(context: Context, conversationId: Long, messageId: Long) {
            val request = OneTimeWorkRequestBuilder<FcmDeliveryReceiptWorker>()
                .setInputData(
                    workDataOf(
                        DELIVERY_CONVERSATION_ID to conversationId,
                        DELIVERY_MESSAGE_ID to messageId
                    )
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "family-chat-delivery-$conversationId",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
