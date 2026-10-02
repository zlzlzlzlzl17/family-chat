package com.example.chat

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Process-wide signal used to restart suspended realtime work after a network becomes usable. */
internal class NetworkMonitor(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val mutableAvailable = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val available: SharedFlow<Unit> = mutableAvailable.asSharedFlow()

    init {
        runCatching {
            connectivity.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        mutableAvailable.tryEmit(Unit)
                    }
                },
            )
        }.onFailure { error ->
            FamilyChatDiagnostics.event(
                "network_monitor_registration_failed",
                "error" to error.javaClass.simpleName,
            )
        }
    }
}
