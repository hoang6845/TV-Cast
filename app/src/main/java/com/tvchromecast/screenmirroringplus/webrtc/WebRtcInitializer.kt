package com.tvchromecast.screenmirroringplus.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.PeerConnectionFactory
import java.util.concurrent.atomic.AtomicBoolean

object WebRtcInitializer {
    private val initialized = AtomicBoolean(false)

    fun initialize(context: Context) {
        if (initialized.compareAndSet(false, true)) {
            Log.i(TAG, "WebRtcInitializer.initialize: start")
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .createInitializationOptions()
            )
            Log.i(TAG, "WebRtcInitializer.initialize: done")
        } else {
            Log.i(TAG, "WebRtcInitializer.initialize: already initialized")
        }
    }

    private const val TAG = "TVCastReleaseLog"
}
