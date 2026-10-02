package com.example.chat

import android.content.Context
import org.webrtc.PeerConnectionFactory

/** Owns WebRTC's process-global native initialization. */
internal object WebRtcRuntime {
    @Volatile
    private var initialized = false

    @Synchronized
    fun initializeIfNeeded(
        context: Context,
        beforeInitialize: () -> Unit,
    ): Boolean {
        if (initialized) return false
        beforeInitialize()
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        initialized = true
        return true
    }
}
