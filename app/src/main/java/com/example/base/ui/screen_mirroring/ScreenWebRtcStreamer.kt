package com.example.base.ui.screen_mirroring

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import com.example.base.webrtc.WebRtcInitializer
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import kotlin.math.max
import kotlin.math.min

class ScreenWebRtcStreamer(
    private val context: Context,
    private val sendSignal: (JSONObject) -> Unit,
    private val listener: Listener
) {

    interface Listener {
        fun onWebRtcConnected()
        fun onWebRtcDisconnected()
        fun onWebRtcError(message: String)
    }

    private val eglBase = EglBase.create()
    private val factory: PeerConnectionFactory
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var peerConnection: PeerConnection? = null
    private var captureConfig = MirroringQuality.HIGH.toConfig()
    private var autoRotateEnabled = true
    private var isLandscape = false
    private var qualityName = "high"
    private var hasStartedCapture = false
    private var activeCaptureSize: CaptureSize? = null
    private var activeCaptureFps: Int? = null

    init {
        WebRtcInitializer.initialize(context)

        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase.eglBaseContext,
            true,
            true
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    fun startCapture(
        permissionData: Intent,
        config: MirroringConfig,
        enableAudio: Boolean,
        enableAutoRotate: Boolean,
        landscape: Boolean,
        quality: String
    ) {
        captureConfig = config
        autoRotateEnabled = enableAutoRotate
        isLandscape = landscape
        qualityName = quality

        if (videoCapturer == null) {
            val capturer = ScreenCapturerAndroid(
                permissionData,
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        listener.onWebRtcDisconnected()
                    }
                }
            )
            val textureHelper = SurfaceTextureHelper.create(
                "ScreenWebRtcCapture",
                eglBase.eglBaseContext
            )
            val createdVideoSource = factory.createVideoSource(capturer.isScreencast)
            val createdVideoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, createdVideoSource)
            createdVideoTrack.setEnabled(true)

            val createdAudioSource = factory.createAudioSource(MediaConstraints())
            val createdAudioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, createdAudioSource)
            createdAudioTrack.setEnabled(enableAudio)

            capturer.initialize(
                textureHelper,
                context,
                createdVideoSource.capturerObserver
            )

            surfaceTextureHelper = textureHelper
            videoCapturer = capturer
            videoSource = createdVideoSource
            videoTrack = createdVideoTrack
            audioSource = createdAudioSource
            audioTrack = createdAudioTrack
        }

        val captureSize = captureSize()
        if (!hasStartedCapture) {
            videoCapturer?.startCapture(captureSize.width, captureSize.height, config.fps)
            hasStartedCapture = true
            activeCaptureSize = captureSize
            activeCaptureFps = config.fps
        }
        videoSource?.adaptOutputFormat(captureSize.width, captureSize.height, config.fps)
        setAudioEnabled(enableAudio)
        sendStreamConfig(captureSize, config)
    }

    fun applyConfig(
        config: MirroringConfig,
        enableAutoRotate: Boolean,
        landscape: Boolean,
        quality: String
    ) {
        captureConfig = config
        autoRotateEnabled = enableAutoRotate
        isLandscape = landscape
        qualityName = quality
        val captureSize = captureSize()
        videoSource?.adaptOutputFormat(captureSize.width, captureSize.height, config.fps)
        applySenderEncoding(config)
        sendStreamConfig(captureSize, config)
    }

    fun setAudioEnabled(enabled: Boolean) {
        audioTrack?.setEnabled(enabled)
    }

    fun startCasting() {
        val currentVideoTrack = videoTrack
            ?: throw IllegalStateException("Screen capture is not ready")
        val connection = createPeerConnection()
            ?: throw IllegalStateException("Unable to create WebRTC connection")

        if (connection.senders.none { it.track()?.id() == currentVideoTrack.id() }) {
            connection.addTrack(currentVideoTrack, listOf(STREAM_ID))
        }

        audioTrack?.let { track ->
            if (connection.senders.none { it.track()?.id() == track.id() }) {
                connection.addTrack(track, listOf(STREAM_ID))
            }
        }

        connection.createOffer(
            object : SimpleSdpObserver() {
                override fun onCreateSuccess(description: SessionDescription) {
                    connection.setLocalDescription(
                        object : SimpleSdpObserver() {
                            override fun onSetSuccess() {
                                sendSignal(
                                    JSONObject()
                                        .put("type", "OFFER")
                                        .put("streamKind", "screen")
                                        .put("width", activeCaptureSize?.width ?: captureConfig.width)
                                        .put("height", activeCaptureSize?.height ?: captureConfig.height)
                                        .put("landscape", isLandscape)
                                        .put("quality", qualityName)
                                        .put("sdp", description.description)
                                )
                            }

                            override fun onSetFailure(error: String) {
                                listener.onWebRtcError(error)
                            }
                        },
                        description
                    )
                }

                override fun onCreateFailure(error: String) {
                    listener.onWebRtcError(error)
                }
            },
            MediaConstraints()
        )
        applySenderEncoding(captureConfig)
    }

    fun handleAnswer(sdp: String) {
        val connection = peerConnection ?: return
        connection.setRemoteDescription(
            object : SimpleSdpObserver() {
                override fun onSetFailure(error: String) {
                    listener.onWebRtcError(error)
                }
            },
            SessionDescription(SessionDescription.Type.ANSWER, sdp)
        )
    }

    fun handleRemoteIceCandidate(candidateJson: JSONObject) {
        val connection = peerConnection ?: return
        val candidate = IceCandidate(
            candidateJson.optString("sdpMid"),
            candidateJson.optInt("sdpMLineIndex"),
            candidateJson.optString("candidate")
        )
        connection.addIceCandidate(candidate)
    }

    fun stopCasting() {
        peerConnection?.close()
        peerConnection = null
    }

    fun stopCapture() {
        stopCasting()
        videoTrack?.dispose()
        audioTrack?.dispose()
        runCatching {
            if (hasStartedCapture) {
                videoCapturer?.stopCapture()
            }
        }
        videoCapturer?.dispose()
        videoSource?.dispose()
        audioSource?.dispose()
        surfaceTextureHelper?.dispose()

        videoTrack = null
        audioTrack = null
        videoCapturer = null
        videoSource = null
        audioSource = null
        surfaceTextureHelper = null
        hasStartedCapture = false
        activeCaptureSize = null
        activeCaptureFps = null
    }

    fun release() {
        stopCapture()
        factory.dispose()
        eglBase.release()
    }

    private fun createPeerConnection(): PeerConnection? {
        peerConnection?.let { return it }

        val configuration = PeerConnection.RTCConfiguration(
            listOf(
                PeerConnection.IceServer.builder(STUN_SERVER).createIceServer()
            )
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        return factory.createPeerConnection(configuration, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> listener.onWebRtcConnected()

                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.CLOSED -> listener.onWebRtcDisconnected()

                    else -> Unit
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit

            override fun onIceCandidate(candidate: IceCandidate) {
                sendSignal(
                    JSONObject()
                        .put("type", "ICE_CANDIDATE")
                        .put(
                            "candidate",
                            JSONObject()
                                .put("sdpMid", candidate.sdpMid)
                                .put("sdpMLineIndex", candidate.sdpMLineIndex)
                                .put("candidate", candidate.sdp)
                        )
                )
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit

            override fun onAddStream(stream: MediaStream) = Unit

            override fun onRemoveStream(stream: MediaStream) = Unit

            override fun onDataChannel(dataChannel: DataChannel) = Unit

            override fun onRenegotiationNeeded() = Unit

            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
        }).also {
            peerConnection = it
        }
    }

    private fun applySenderEncoding(config: MirroringConfig) {
        val trackId = videoTrack?.id() ?: return
        val sender = peerConnection?.senders?.firstOrNull { it.track()?.id() == trackId } ?: return
        val parameters = sender.parameters
        parameters.encodings.forEach { encoding ->
            encoding.maxBitrateBps = config.bitrate
            encoding.maxFramerate = config.fps
        }
        sender.setParameters(parameters)
    }

    private fun sendStreamConfig(captureSize: CaptureSize, config: MirroringConfig) {
        sendSignal(
            JSONObject()
                .put("type", "STREAM_CONFIG")
                .put("streamKind", "screen")
                .put("width", captureSize.width)
                .put("height", captureSize.height)
                .put("fps", config.fps)
                .put("bitrate", config.bitrate)
                .put("landscape", isLandscape)
                .put("autoRotate", autoRotateEnabled)
                .put("quality", qualityName)
        )
    }

    private fun captureSize(): CaptureSize {
        val longSide = max(captureConfig.width, captureConfig.height)
        val shortSide = min(captureConfig.width, captureConfig.height)

        return if (!autoRotateEnabled || !isLandscape) {
            CaptureSize(shortSide, longSide)
        } else {
            CaptureSize(longSide, shortSide)
        }
    }

    private data class CaptureSize(
        val width: Int,
        val height: Int
    )

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetFailure(error: String) = Unit
    }

    companion object {
        private const val STREAM_ID = "screen_mirroring_stream"
        private const val VIDEO_TRACK_ID = "screen_mirroring_video"
        private const val AUDIO_TRACK_ID = "screen_mirroring_audio"
        private const val STUN_SERVER = "stun:stun.l.google.com:19302"
    }
}
