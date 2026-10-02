package com.example.chat

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class FcmTokenSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = ChatPreferences(applicationContext)
        if (
            prefs.serverUrl.isBlank() ||
            (prefs.token.isBlank() && prefs.refreshToken.isBlank())
        ) {
            return@withContext Result.success()
        }

        val result = runCatching {
            checkNotNull(FirebaseApp.initializeApp(applicationContext)) {
                "missing_firebase_config"
            }
            val token = Tasks.await(
                FirebaseMessaging.getInstance().token,
                30,
                TimeUnit.SECONDS
            )
            check(token.isNotBlank()) { "empty_fcm_token" }
            check(FcmRegistrar(applicationContext).registerTokenBlocking(token)) {
                "fcm_registration_rejected"
            }
        }
        result.onSuccess {
            FamilyChatDiagnostics.event(
                "fcm_token_sync_succeeded",
                "attempt" to runAttemptCount
            )
        }.onFailure { error ->
            FamilyChatDiagnostics.event(
                "fcm_token_sync_failed",
                "attempt" to runAttemptCount,
                "error" to error.javaClass.simpleName,
                "detail" to error.message.orEmpty()
            )
        }
        if (result.isSuccess) {
            Result.success()
        } else if (runAttemptCount >= 5) {
            Result.failure()
        } else {
            Result.retry()
        }
    }
}

class FcmDeliveryReceiptWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val conversationId = inputData.getLong(FcmRegistrar.DELIVERY_CONVERSATION_ID, 0L)
        val messageId = inputData.getLong(FcmRegistrar.DELIVERY_MESSAGE_ID, 0L)
        if (conversationId <= 0L || messageId <= 0L) {
            return@withContext Result.failure()
        }

        val result = runCatching {
            check(FcmRegistrar(applicationContext).reportDeliveredBlocking(conversationId, messageId))
        }
        result.onSuccess {
            FamilyChatDiagnostics.event(
                "fcm_delivered_report_succeeded",
                "conversation_id" to conversationId,
                "message_id" to messageId,
                "attempt" to runAttemptCount
            )
        }.onFailure { error ->
            FamilyChatDiagnostics.event(
                "fcm_delivered_report_failed",
                "conversation_id" to conversationId,
                "message_id" to messageId,
                "attempt" to runAttemptCount,
                "error" to error.javaClass.simpleName,
                "detail" to error.message.orEmpty()
            )
        }
        if (result.isSuccess) {
            Result.success()
        } else if (runAttemptCount >= 5) {
            Result.failure()
        } else {
            Result.retry()
        }
    }
}
