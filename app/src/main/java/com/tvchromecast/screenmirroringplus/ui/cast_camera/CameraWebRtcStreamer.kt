package com.tvchromecast.screenmirroringplus.ui.cast_camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.tvchromecast.screenmirroringplus.webrtc.WebRtcInitializer
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
import org.webrtc.VideoFrame
import org.webrtc.VideoCapturer
import org.webrtc.VideoProcessor
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import kotlin.math.abs
import kotlin.math.roundToInt

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
    private var activeCameraName: String? = null
    private val cameraEnumerator = Camera2Enumerator(context)
    private val zoomProcessor = CropZoomVideoProcessor()

    init {
        WebRtcInitializer.initialize(context)

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
        createdVideoSource.setVideoProcessor(zoomProcessor)

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

    fun getSupportedZoomRatios(useFrontCamera: Boolean): List<Float> {
        val ratios = mutableListOf(DEFAULT_ZOOM_RATIO, DOUBLE_ZOOM_RATIO)
        if (!useFrontCamera && findUltraWideBackCameraName() != null) {
            ratios.add(0, HALF_ZOOM_RATIO)
        }
        return ratios
    }

    fun setZoomRatio(ratio: Float): Boolean {
        if (videoCapturer == null) return false

        if (ratio < DEFAULT_ZOOM_RATIO) {
            val ultraWideCamera = findUltraWideBackCameraName() ?: return false
            if (activeCameraName != ultraWideCamera) {
                setTorchEnabled(false)
                (videoCapturer as? CameraVideoCapturer)?.switchCamera(null, ultraWideCamera)
                activeCameraName = ultraWideCamera
            }
            zoomProcessor.setZoomRatio(DEFAULT_ZOOM_RATIO)
            return true
        }

        if (!isFrontCamera) {
            val defaultBackCamera = findDefaultBackCameraName()
            if (defaultBackCamera != null && activeCameraName != defaultBackCamera) {
                setTorchEnabled(false)
                (videoCapturer as? CameraVideoCapturer)?.switchCamera(null, defaultBackCamera)
                activeCameraName = defaultBackCamera
            }
        }

        zoomProcessor.setZoomRatio(ratio.coerceIn(DEFAULT_ZOOM_RATIO, DOUBLE_ZOOM_RATIO))
        return true
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
                                        .put("streamKind", "camera")
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
        if (activeCameraName != null) {
            setTorchEnabled(false)
        }
        isFrontCamera = useFrontCamera
        localRenderer.setMirror(useFrontCamera)
        val targetCamera = findPreferredCameraName(useFrontCamera) ?: return
        activeCameraName = targetCamera
        zoomProcessor.setZoomRatio(DEFAULT_ZOOM_RATIO)
        (videoCapturer as? CameraVideoCapturer)?.switchCamera(null, targetCamera)
    }

    fun setAudioEnabled(enabled: Boolean) {
        audioTrack?.setEnabled(enabled)
    }

    fun hasTorchForCurrentCamera(): Boolean {
        val cameraName = activeCameraName ?: return false
        return !isFrontCamera && hasFlashUnit(cameraName)
    }

    fun setTorchEnabled(enabled: Boolean): Boolean {
        val cameraName = activeCameraName ?: return false

        if (isFrontCamera) return false
        if (!hasFlashUnit(cameraName)) return false

        val cameraManager =
            context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        return try {
            cameraManager.setTorchMode(cameraName, enabled)
            true
        } catch (e: Exception) {
            android.util.Log.e(
                "CameraTorch",
                "setTorchMode($cameraName, $enabled) failed",
                e
            )
            false
        }
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
        val deviceNames = cameraEnumerator.deviceNames
        val preferred = findPreferredCameraName(useFrontCamera)
        val fallback = deviceNames.firstOrNull()
        return listOfNotNull(preferred, fallback)
            .distinct()
            .firstNotNullOfOrNull { name ->
                runCatching { cameraEnumerator.createCapturer(name, null) }.getOrNull()?.also {
                    activeCameraName = name
                }
            }
    }

    private fun findPreferredCameraName(useFrontCamera: Boolean): String? {
        return if (useFrontCamera) {
            cameraEnumerator.deviceNames.firstOrNull { cameraEnumerator.isFrontFacing(it) }
        } else {
            findDefaultBackCameraName()
                ?: cameraEnumerator.deviceNames.firstOrNull { cameraEnumerator.isBackFacing(it) }
        }
    }

    private fun findDefaultBackCameraName(): String? {
        val backCameras = cameraDescriptors()
            .filter { !it.isFrontFacing }
            .sortedBy { abs(it.minFocalLength - DEFAULT_BACK_FOCAL_LENGTH) }

        return backCameras.firstOrNull()?.name
            ?: cameraEnumerator.deviceNames.firstOrNull { cameraEnumerator.isBackFacing(it) }
    }

    private fun findUltraWideBackCameraName(): String? {
        val backCameras = cameraDescriptors()
            .filter { !it.isFrontFacing }
            .sortedBy { it.minFocalLength }

        val ultraWide = backCameras.firstOrNull() ?: return null
        val defaultBack = findDefaultBackCameraName()
        if (ultraWide.name == defaultBack) return null

        val defaultFocalLength = backCameras
            .firstOrNull { it.name == defaultBack }
            ?.minFocalLength
            ?: return ultraWide.name

        return if (ultraWide.minFocalLength <= defaultFocalLength * ULTRA_WIDE_FOCAL_THRESHOLD) {
            ultraWide.name
        } else {
            null
        }
    }

    private fun cameraDescriptors(): List<CameraDescriptor> {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return emptyList()

        return runCatching {
            cameraEnumerator.deviceNames.mapNotNull { name ->
                val characteristics = cameraManager.getCameraCharacteristics(name)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                val focalLengths = characteristics.get(
                    CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
                )
                CameraDescriptor(
                    name = name,
                    isFrontFacing = facing == CameraCharacteristics.LENS_FACING_FRONT,
                    minFocalLength = focalLengths?.minOrNull() ?: DEFAULT_BACK_FOCAL_LENGTH
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun hasFlashUnit(cameraName: String): Boolean {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return false
        return runCatching {
            cameraManager
                .getCameraCharacteristics(cameraName)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }.getOrDefault(false)
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetFailure(error: String) = Unit
    }

    private data class CameraDescriptor(
        val name: String,
        val isFrontFacing: Boolean,
        val minFocalLength: Float
    )

    private class CropZoomVideoProcessor : VideoProcessor {
        @Volatile
        private var zoomRatio = DEFAULT_ZOOM_RATIO
        private var sink: VideoSink? = null

        fun setZoomRatio(ratio: Float) {
            zoomRatio = ratio.coerceIn(DEFAULT_ZOOM_RATIO, DOUBLE_ZOOM_RATIO)
        }

        override fun setSink(sink: VideoSink?) {
            this.sink = sink
        }

        override fun onCapturerStarted(success: Boolean) = Unit

        override fun onCapturerStopped() = Unit

        override fun onFrameCaptured(frame: VideoFrame) {
            val currentSink = sink ?: return
            val ratio = zoomRatio
            if (ratio <= DEFAULT_ZOOM_RATIO) {
                currentSink.onFrame(frame)
                return
            }

            val sourceBuffer = frame.buffer
            val cropWidth = evenDimension((sourceBuffer.width / ratio).roundToInt())
            val cropHeight = evenDimension((sourceBuffer.height / ratio).roundToInt())
            val cropX = evenDimension((sourceBuffer.width - cropWidth) / 2)
            val cropY = evenDimension((sourceBuffer.height - cropHeight) / 2)
            val zoomedBuffer = sourceBuffer.cropAndScale(
                cropX,
                cropY,
                cropWidth,
                cropHeight,
                sourceBuffer.width,
                sourceBuffer.height
            )
            val zoomedFrame = VideoFrame(zoomedBuffer, frame.rotation, frame.timestampNs)
            try {
                currentSink.onFrame(zoomedFrame)
            } finally {
                zoomedFrame.release()
            }
        }

        private fun evenDimension(value: Int): Int {
            return value.coerceAtLeast(2).let { if (it % 2 == 0) it else it - 1 }
        }
    }

    companion object {
        private const val HALF_ZOOM_RATIO = 0.5f
        private const val DEFAULT_ZOOM_RATIO = 1f
        private const val DOUBLE_ZOOM_RATIO = 2f
        private const val DEFAULT_BACK_FOCAL_LENGTH = 4f
        private const val ULTRA_WIDE_FOCAL_THRESHOLD = 0.8f
        private const val STREAM_ID = "camera_cast_stream"
        private const val VIDEO_TRACK_ID = "camera_cast_video"
        private const val AUDIO_TRACK_ID = "camera_cast_audio"
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val VIDEO_FPS = 30
        private const val STUN_SERVER = "stun:stun.l.google.com:19302"
    }
}
