package com.tvchromecast.screenmirroringplus.cast

import com.google.android.gms.cast.CastMediaControlIntent

object CastReceiverIds {
    const val MEDIA_RECEIVER = CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
    const val CUSTOM_RECEIVER = "9A396B53"
    const val CAMERA_WEBRTC = CUSTOM_RECEIVER
    const val YOUTUBE_NAMESPACE = "urn:x-cast:com.example.youtube"
}
