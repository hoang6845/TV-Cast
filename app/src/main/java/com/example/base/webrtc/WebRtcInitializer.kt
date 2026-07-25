package com.example.base.webrtc

import android.content.Context
import org.webrtc.PeerConnectionFactory
import java.util.concurrent.atomic.AtomicBoolean

object WebRtcInitializer {
    private val initialized = AtomicBoolean(false)

    fun initialize(context: Context) {
        if (initialized.compareAndSet(false, true)) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .createInitializationOptions()
            )
        }
    }
}
