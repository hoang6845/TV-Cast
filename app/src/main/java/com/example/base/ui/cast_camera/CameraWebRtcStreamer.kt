package com.example.base.ui.cast_camera

import android.content.Context
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
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
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.atomic.AtomicBoolean

class CameraWebRtcStreamer(
    private val context: Context,
    private val localRenderer: SurfaceViewRenderer,
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
    private var isFrontCamera = false

    init {
        initializeWebRtc(context)

        localRenderer.init(eglBase.eglBaseContext, null)
        localRenderer.setEnableHardwareScaler(true)

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

    fun startLocalPreview(
        useFrontCamera: Boolean,
        enableAudio: Boolean
    ) {
        if (videoCapturer != null) {
            setAudioEnabled(enableAudio)
            return
        }

        isFrontCamera = useFrontCamera
        localRenderer.setMirror(useFrontCamera)

        val capturer = createCameraCapturer(useFrontCamera)
            ?: throw IllegalStateException("Unable to open camera")
        val textureHelper = SurfaceTextureHelper.create(
            "CameraWebRtcCapture",
            eglBase.eglBaseContext
        )
        val createdVideoSource = factory.createVideoSource(false)

        capturer.initialize(
            textureHelper,
            context,
            createdVideoSource.capturerObserver
        )
        capturer.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS)

        val createdVideoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, createdVideoSource)
        createdVideoTrack.setEnabled(true)
        createdVideoTrack.addSink(localRenderer)

        val createdAudioSource = factory.createAudioSource(MediaConstraints())
        val createdAudioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, createdAudioSource)
        createdAudioTrack.setEnabled(enableAudio)

        surfaceTextureHelper = textureHelper
        videoCapturer = capturer
        videoSource = createdVideoSource
        videoTrack = createdVideoTrack
        audioSource = createdAudioSource
        audioTrack = createdAudioTrack
    }

    fun startCasting() {
        val currentVideoTrack = videoTrack
            ?: throw IllegalStateException("Camera preview is not ready")
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

    fun switchCamera(useFrontCamera: Boolean) {
        isFrontCamera = useFrontCamera
        localRenderer.setMirror(useFrontCamera)
        (videoCapturer as? CameraVideoCapturer)?.switchCamera(null)
    }

    fun setAudioEnabled(enabled: Boolean) {
        audioTrack?.setEnabled(enabled)
    }

    fun stopCasting() {
        peerConnection?.close()
        peerConnection = null
    }

    fun release() {
        stopCasting()
        videoTrack?.removeSink(localRenderer)
        videoTrack?.dispose()
        audioTrack?.dispose()
        runCatching { videoCapturer?.stopCapture() }
        videoCapturer?.dispose()
        videoSource?.dispose()
        audioSource?.dispose()
        surfaceTextureHelper?.dispose()
        localRenderer.release()
        factory.dispose()
        eglBase.release()

        videoTrack = null
        audioTrack = null
        videoCapturer = null
        videoSource = null
        audioSource = null
        surfaceTextureHelper = null
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

    private fun createCameraCapturer(useFrontCamera: Boolean): VideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames
        val preferred = deviceNames.firstOrNull {
            if (useFrontCamera) {
                enumerator.isFrontFacing(it)
            } else {
                enumerator.isBackFacing(it)
            }
        }
        val fallback = deviceNames.firstOrNull()
        return listOfNotNull(preferred, fallback)
            .distinct()
            .firstNotNullOfOrNull { name ->
                runCatching { enumerator.createCapturer(name, null) }.getOrNull()
            }
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetFailure(error: String) = Unit
    }

    companion object {
        private val factoryInitialized = AtomicBoolean(false)
        private const val STREAM_ID = "camera_cast_stream"
        private const val VIDEO_TRACK_ID = "camera_cast_video"
        private const val AUDIO_TRACK_ID = "camera_cast_audio"
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val VIDEO_FPS = 30
        private const val STUN_SERVER = "stun:stun.l.google.com:19302"

        private fun initializeWebRtc(context: Context) {
            if (factoryInitialized.compareAndSet(false, true)) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context)
                        .createInitializationOptions()
                )
            }
        }
    }
}
