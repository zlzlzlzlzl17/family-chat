package com.example.chat

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.UUID

internal data class PushHealthCheckResult(
    val serverReachable: Boolean,
    val requestAccepted: Boolean,
    val pushReceived: Boolean,
    val requestRoundTripMs: Long,
    val pushRoundTripMs: Long,
    val reason: String = "",
)

internal class PushCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val registrar = FcmRegistrar(appContext)
    private val preferences = ChatPreferences(appContext)

    fun registerCurrentDevice() = registrar.registerCurrentTokenIfAvailable()

    fun unregisterCurrentDevice(token: String, serverUrl: String) =
        registrar.unregisterCurrentTokenIfAvailable(token, serverUrl)

    fun scheduleHealthChecks() = FcmRegistrar.schedulePeriodicTokenSync(appContext)

    fun cancelBackgroundWork() = FcmRegistrar.cancelBackgroundWork(appContext)

    suspend fun runHealthCheck(timeoutMs: Long = 30_000L): PushHealthCheckResult {
        val diagnosticId = UUID.randomUUID().toString()
        preferences.lastPushDiagnosticId = ""
        preferences.lastPushDiagnosticAt = 0L
        runCatching { registrar.ensureCurrentTokenRegisteredForSelfTest() }
            .onFailure { error ->
                FamilyChatDiagnostics.event(
                    "fcm_self_test_token_sync_failed",
                    "error" to error.javaClass.simpleName,
                )
            }
        val requestStartedAt = System.currentTimeMillis()
        val request = runCatching {
            withContext(Dispatchers.IO) {
                registrar.requestPushSelfTestBlocking(diagnosticId)
            }
        }.getOrElse { error ->
            return PushHealthCheckResult(
                serverReachable = false,
                requestAccepted = false,
                pushReceived = false,
                requestRoundTripMs = System.currentTimeMillis() - requestStartedAt,
                pushRoundTripMs = 0L,
                reason = error.javaClass.simpleName,
            )
        }
        val responseReceivedAt = System.currentTimeMillis()
        val requestRoundTrip = responseReceivedAt - requestStartedAt
        if (!request.requestAccepted) {
            return PushHealthCheckResult(
                serverReachable = request.serverReachable,
                requestAccepted = false,
                pushReceived = false,
                requestRoundTripMs = requestRoundTrip,
                pushRoundTripMs = 0L,
                reason = request.reason,
            )
        }
        while (System.currentTimeMillis() - responseReceivedAt < timeoutMs) {
            if (preferences.lastPushDiagnosticId == diagnosticId) {
                return PushHealthCheckResult(
                    serverReachable = true,
                    requestAccepted = true,
                    pushReceived = true,
                    requestRoundTripMs = requestRoundTrip,
                    pushRoundTripMs = (preferences.lastPushDiagnosticAt - requestStartedAt).coerceAtLeast(0L),
                )
            }
            delay(250L)
        }
        return PushHealthCheckResult(true, true, false, requestRoundTrip, 0L, "receive_timeout")
    }
}
