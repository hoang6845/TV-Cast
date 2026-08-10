package com.tvchromecast.screenmirroringplus.ui.cast_camera

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.tvchromecast.screenmirroringplus.R
import com.tvchromecast.screenmirroringplus.cast.CastReceiverIds
import com.tvchromecast.screenmirroringplus.databinding.FragmentCameraCastBinding
import com.tvchromecast.screenmirroringplus.ui.common.showCastFailureDialog
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import hoang.dqm.codebase.base.activity.BaseFragment
import hoang.dqm.codebase.base.activity.onBackPressed
import hoang.dqm.codebase.base.activity.popBackStack
import org.json.JSONObject
import org.webrtc.SurfaceViewRenderer
import kotlin.math.abs

class CameraCastFragment : BaseFragment<FragmentCameraCastBinding, CameraCastViewModel>(),
    CameraWebRtcStreamer.Listener {
    override val viewModelClass: Class<CameraCastViewModel>
        get() = CameraCastViewModel::class.java

    override fun inflateBinding(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?
    ): FragmentCameraCastBinding {
        return FragmentCameraCastBinding.inflate(inflater, container, false)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences

    private var castContext: CastContext? = null
    private var localRenderer: SurfaceViewRenderer? = null
    private var webRtcStreamer: CameraWebRtcStreamer? = null
    private var scaleDetector: ScaleGestureDetector? = null

    private var pendingCast = false
    private var isCasting = false
    private var isStartingCast = false
    private var isReconnecting = false
    private var microphoneMuted = false
    private var microphonePermissionDenied = false
    private var torchEnabled = false
    private var useFrontCamera = false
    private var cameraReady = false
    private var currentZoomRatio = DEFAULT_ZOOM_RATIO
    private var supportedZoomRatios = listOf(DEFAULT_ZOOM_RATIO, DOUBLE_ZOOM_RATIO)
    private var toolbarBaseHeight = 0
    private var bottomButtonBaseMargin = 0

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        markCameraPermissionAsked()
        if (granted) {
            hidePermissionPlaceholder()
            startCamera()
        } else {
            cameraReady = false
            isCasting = false
            if (isCameraPermanentlyDenied()) {
                showCameraSettingsDialog()
                showPermissionPlaceholder(permanentlyDenied = true)
            } else {
                showPermissionPlaceholder(permanentlyDenied = false)
            }
            updateControls()
        }
    }

    private val microphonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            microphoneMuted = false
            microphonePermissionDenied = false
            webRtcStreamer?.setAudioEnabled(true)
        } else {
            microphoneMuted = true
            microphonePermissionDenied = true
            webRtcStreamer?.setAudioEnabled(false)
            Toast.makeText(
                requireContext(),
                R.string.text_microphone_audio_not_transmitted,
                Toast.LENGTH_SHORT
            ).show()
        }
        updateControls()
    }

    private val castSessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            updateCastStatus(CastConnectionState.Connecting)
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            updateCastStatus(CastConnectionState.Connected)
            if (pendingCast) {
                pendingCast = false
                beginCameraCast()
            } else {
                updateControls()
            }
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            pendingCast = false
            isStartingCast = false
            updateCastStatus(CastConnectionState.Error)
            showConnectFailedDialog()
            updateControls()
        }

        override fun onSessionEnding(session: CastSession) {
            if (isCasting || isStartingCast) {
                beginReconnectState()
            } else {
                updateCastStatus(CastConnectionState.Connecting)
            }
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            val wasCasting = isCasting || isReconnecting || isStartingCast
            removeCastMessageCallback(session)
            pendingCast = false
            isStartingCast = false
            isCasting = false
            isReconnecting = false
            webRtcStreamer?.stopCasting()
            updateCastStatus(CastConnectionState.Disconnected)
            if (wasCasting) {
                showConnectionLostDialog()
            }
            updateControls()
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) {
            beginReconnectState()
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            isReconnecting = false
            updateCastStatus(CastConnectionState.Connected)
            if (isCasting || isStartingCast) {
                beginCameraCast()
            } else {
                updateControls()
            }
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            isReconnecting = false
            isStartingCast = false
            isCasting = false
            updateCastStatus(CastConnectionState.Error)
            showConnectionLostDialog()
            updateControls()
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            if (isCasting || isStartingCast) {
                beginReconnectState()
            }
        }
    }

    private val receiverMessageCallback = Cast.MessageReceivedCallback { _, _, message ->
        handleReceiverMessage(message)
    }

    override fun initView() {
        releaseLog("CameraCast.initView: start")
        prefs = requireContext().getSharedPreferences(PREFS_NAME, 0)
        applySystemInsets()
        setupLocalRenderer()
        setupCastButton()
        setupPreviewGestures()
        updateControls()
        releaseLog("CameraCast.initView: done")
    }

    override fun initListener() {
        releaseLog("CameraCast.initListener")
        binding.btnBack.setOnClickListener { handleBackPressed() }
        binding.btnStartCasting.setOnClickListener { handleMainAction() }
        binding.placeholderAction.setOnClickListener { handlePlaceholderAction() }
        binding.btnFlash.setOnClickListener { toggleTorch() }
        binding.btnMic.setOnClickListener { toggleMicrophone() }
        binding.btnSwitchCamera.setOnClickListener { switchCamera() }
        binding.btnZoomHalf.setOnClickListener { setZoom(HALF_ZOOM_RATIO) }
        binding.btnZoomOne.setOnClickListener { setZoom(DEFAULT_ZOOM_RATIO) }
        binding.btnZoomTwo.setOnClickListener { setZoom(DOUBLE_ZOOM_RATIO) }
        onBackPressed(Runnable { handleBackPressed() })
    }

    override fun initData() {
        releaseLog("CameraCast.initData: hasCameraPermission=${hasCameraPermission()}")
        if (hasCameraPermission()) {
            startCamera()
        } else {
            showCameraPermissionDialog()
            showPermissionPlaceholder(permanentlyDenied = false)
        }
    }

    override fun onStart() {
        releaseLog("CameraCast.onStart")
        super.onStart()
        castContext?.sessionManager?.addSessionManagerListener(
            castSessionListener,
            CastSession::class.java
        )
        updateCastStatusFromSession()
    }

    override fun onResume() {
        releaseLog("CameraCast.onResume: hasCameraPermission=${hasCameraPermission()} cameraReady=$cameraReady")
        super.onResume()
        if (hasCameraPermission() && !cameraReady) {
            hidePermissionPlaceholder()
            startCamera()
        }
    }

    override fun onStop() {
        releaseLog("CameraCast.onStop")
        castContext?.sessionManager?.removeSessionManagerListener(
            castSessionListener,
            CastSession::class.java
        )
        super.onStop()
    }

    override fun onDestroyView() {
        releaseLog("CameraCast.onDestroyView")
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { currentCastSession()?.let(::removeCastMessageCallback) }
            .onFailure { releaseLog("CameraCast.onDestroyView: remove callback failed", it) }
        runCatching { webRtcStreamer?.release() }
            .onFailure { releaseLog("CameraCast.onDestroyView: streamer release failed", it) }
        webRtcStreamer = null
        localRenderer = null
        super.onDestroyView()
    }

    override fun onWebRtcConnected() {
        mainHandler.post {
            if (_binding == null || view == null) return@post
            isStartingCast = false
            isReconnecting = false
            isCasting = true
            updateCastStatus(CastConnectionState.Connected)
            updateControls()
        }
    }

    override fun onWebRtcDisconnected() {
        mainHandler.post {
            if (_binding == null || view == null) return@post
            if (isCasting || isStartingCast) {
                beginReconnectState()
            }
        }
    }

    override fun onWebRtcError(message: String) {
        mainHandler.post {
            if (_binding == null || view == null) return@post
            isStartingCast = false
            isCasting = false
            updateCastStatus(CastConnectionState.Error)
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            updateControls()
        }
    }

    private fun applySystemInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            if (toolbarBaseHeight == 0) {
                toolbarBaseHeight = binding.toolbar.layoutParams.height
            }
            binding.toolbar.layoutParams = binding.toolbar.layoutParams.apply {
                height = toolbarBaseHeight + systemBars.top
            }
            binding.toolbar.updatePadding(top = systemBars.top)

            val params = binding.btnStartCasting.layoutParams as ViewGroup.MarginLayoutParams
            if (bottomButtonBaseMargin == 0) {
                bottomButtonBaseMargin = params.bottomMargin
            }
            params.bottomMargin = bottomButtonBaseMargin + systemBars.bottom
            binding.btnStartCasting.layoutParams = params

            insets
        }
    }

    private fun setupLocalRenderer() {
        releaseLog("CameraCast.setupLocalRenderer: start")
        runCatching {
            val parent = binding.previewView.parent as ViewGroup
            val renderer = SurfaceViewRenderer(requireContext()).apply {
                layoutParams = ConstraintLayout.LayoutParams(
                    ConstraintLayout.LayoutParams.MATCH_PARENT,
                    ConstraintLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                    endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                    topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                }
            }
            parent.addView(renderer, 0)
            binding.previewView.isVisible = false
            localRenderer = renderer
            webRtcStreamer = CameraWebRtcStreamer(
                requireContext().applicationContext,
                renderer,
                ::sendSignalToReceiver,
                this
            )
            releaseLog("CameraCast.setupLocalRenderer: WebRTC streamer ready")
        }.onFailure {
            releaseLog("CameraCast.setupLocalRenderer: failed", it)
            showCameraStartError(it)
        }
    }

    private fun setupCastButton() {
        releaseLog("CameraCast.setupCastButton")
        runCatching {
            castContext = CastContext.getSharedInstance(requireContext())
            castContext?.setReceiverApplicationId(CastReceiverIds.CAMERA_WEBRTC)
            CastButtonFactory.setUpMediaRouteButton(requireContext(), binding.btnTopCast)
            updateCastStatusFromSession()
            releaseLog("CameraCast.setupCastButton: ready")
        }.onFailure {
            releaseLog("CameraCast.setupCastButton: failed", it)
            binding.btnTopCast.isEnabled = false
            binding.btnTopCast.alpha = 0.45f
            updateCastStatus(CastConnectionState.Error)
        }
    }

    private fun setupPreviewGestures() {
        scaleDetector = ScaleGestureDetector(
            requireContext(),
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (!cameraReady) return false
                    val minZoom = supportedZoomRatios.minOrNull() ?: DEFAULT_ZOOM_RATIO
                    val maxZoom = supportedZoomRatios.maxOrNull() ?: DOUBLE_ZOOM_RATIO
                    val nextZoom = (currentZoomRatio * detector.scaleFactor).coerceIn(minZoom, maxZoom)
                    val snappedZoom = if (nextZoom < DEFAULT_ZOOM_RATIO) HALF_ZOOM_RATIO else nextZoom
                    return applyZoom(snappedZoom)
                }
            }
        )
        binding.previewCard.setOnTouchListener { _, event ->
            scaleDetector?.onTouchEvent(event)
            false
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun showCameraPermissionDialog() {
        if (isCameraPermanentlyDenied()) {
            showCameraSettingsDialog()
            return
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.text_camera_permission_required)
            .setMessage(R.string.text_camera_permission_required_message)
            .setNegativeButton(R.string.text_cancel, null)
            .setPositiveButton(R.string.text_allow_camera) { _, _ ->
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            .show()
    }

    private fun showCameraSettingsDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.text_camera_access_disabled)
            .setMessage(R.string.text_camera_access_disabled_message)
            .setNegativeButton(R.string.text_cancel, null)
            .setPositiveButton(R.string.text_open_settings) { _, _ -> openAppSettings() }
            .show()
    }

    private fun showPermissionPlaceholder(permanentlyDenied: Boolean) {
        binding.placeholderContainer.isVisible = true
        binding.placeholderTitle.text = getString(
            if (permanentlyDenied) {
                R.string.text_camera_access_disabled
            } else {
                R.string.text_camera_permission_required
            }
        )
        binding.placeholderMessage.text = getString(
            if (permanentlyDenied) {
                R.string.text_camera_access_disabled_message
            } else {
                R.string.text_camera_permission_placeholder
            }
        )
        binding.placeholderAction.text = getString(
            if (permanentlyDenied) {
                R.string.text_open_settings
            } else {
                R.string.text_allow_camera
            }
        )
        localRenderer?.isVisible = false
    }

    private fun hidePermissionPlaceholder() {
        binding.placeholderContainer.isVisible = false
        localRenderer?.isVisible = true
    }

    private fun handlePlaceholderAction() {
        when {
            isCameraPermanentlyDenied() -> openAppSettings()
            !hasCameraPermission() -> showCameraPermissionDialog()
            else -> startCamera()
        }
    }

    private fun markCameraPermissionAsked() {
        prefs.edit().putBoolean(KEY_CAMERA_PERMISSION_ASKED, true).apply()
    }

    private fun isCameraPermanentlyDenied(): Boolean {
        return prefs.getBoolean(KEY_CAMERA_PERMISSION_ASKED, false) &&
            !hasCameraPermission() &&
            !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", requireContext().packageName, null)
            )
        )
    }

    private fun startCamera() {
        releaseLog("CameraCast.startCamera")
        if (!hasCameraPermission()) {
            showPermissionPlaceholder(permanentlyDenied = isCameraPermanentlyDenied())
            updateControls()
            return
        }

        binding.cameraLoading.isVisible = true
        binding.cameraLoadingText.text = getString(R.string.text_please_wait_a_moment)
        binding.placeholderContainer.isVisible = false
        localRenderer?.isVisible = true

        runCatching {
            val streamer = webRtcStreamer
                ?: throw IllegalStateException("Camera WebRTC streamer is not ready")
            streamer.startLocalPreview(
                useFrontCamera = useFrontCamera,
                enableAudio = hasMicrophonePermission() && !microphoneMuted
            )
        }.onSuccess {
            releaseLog("CameraCast.startCamera: success")
            cameraReady = true
            binding.cameraLoading.isVisible = false
            updateCameraCapabilities()
            promptForMicrophoneIfNeeded()
            updateControls()
        }.onFailure {
            releaseLog("CameraCast.startCamera: failed", it)
            showCameraStartError(it)
        }
    }

    private fun stopCamera() {
        webRtcStreamer?.stopCasting()
        cameraReady = false
        torchEnabled = false
    }

    private fun showCameraStartError(error: Throwable? = null) {
        error?.let { releaseLog("CameraCast.showCameraStartError", it) }
        cameraReady = false
        isCasting = false
        isStartingCast = false
        stopCamera()
        binding.cameraLoading.isVisible = false
        localRenderer?.isVisible = false
        binding.placeholderContainer.isVisible = true
        binding.placeholderTitle.setText(R.string.text_unable_to_start_camera)
        binding.placeholderMessage.text = error?.message
            ?: getString(R.string.text_unable_to_start_camera_message)
        binding.placeholderAction.setText(R.string.text_try_again)
        updateCastStatusFromSession()
        updateControls()
    }

    private fun updateCameraCapabilities() {
        binding.btnFlash.isEnabled = false
        binding.btnFlash.alpha = 0.45f
        supportedZoomRatios = webRtcStreamer?.getSupportedZoomRatios(useFrontCamera)
            ?: listOf(DEFAULT_ZOOM_RATIO, DOUBLE_ZOOM_RATIO)
        if (supportedZoomRatios.none { isSameZoom(it, currentZoomRatio) }) {
            currentZoomRatio = DEFAULT_ZOOM_RATIO
            webRtcStreamer?.setZoomRatio(currentZoomRatio)
        }
        binding.btnZoomHalf.isVisible = supportedZoomRatios.any { isSameZoom(it, HALF_ZOOM_RATIO) }
        binding.btnZoomOne.isVisible = supportedZoomRatios.any { isSameZoom(it, DEFAULT_ZOOM_RATIO) }
        binding.btnZoomTwo.isVisible = supportedZoomRatios.any { isSameZoom(it, DOUBLE_ZOOM_RATIO) }
        torchEnabled = false
    }

    private fun toggleTorch() {
        Toast.makeText(requireContext(), R.string.text_camera_ready, Toast.LENGTH_SHORT).show()
    }

    private fun promptForMicrophoneIfNeeded() {
        if (microphoneMuted || hasMicrophonePermission() || microphonePermissionDenied) return

        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.text_microphone_permission_required)
            .setNegativeButton(R.string.text_not_now) { _, _ ->
                microphoneMuted = true
                microphonePermissionDenied = true
                webRtcStreamer?.setAudioEnabled(false)
                updateControls()
            }
            .setPositiveButton(R.string.text_allow_microphone) { _, _ ->
                microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            .show()
    }

    private fun toggleMicrophone() {
        if (!microphoneMuted) {
            microphoneMuted = true
            webRtcStreamer?.setAudioEnabled(false)
            updateControls()
            return
        }

        if (hasMicrophonePermission()) {
            microphoneMuted = false
            microphonePermissionDenied = false
            webRtcStreamer?.setAudioEnabled(true)
            updateControls()
        } else {
            MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.text_microphone_permission_required)
                .setNegativeButton(R.string.text_not_now) { _, _ ->
                    microphoneMuted = true
                    microphonePermissionDenied = true
                    webRtcStreamer?.setAudioEnabled(false)
                    updateControls()
                }
                .setPositiveButton(R.string.text_allow_microphone) { _, _ ->
                    microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
                .show()
        }
    }

    private fun switchCamera() {
        if (!cameraReady) return
        binding.btnSwitchCamera.isEnabled = false
        binding.cameraLoading.isVisible = true
        binding.cameraLoadingText.setText(R.string.text_switching_camera)
        useFrontCamera = !useFrontCamera
        currentZoomRatio = DEFAULT_ZOOM_RATIO
        webRtcStreamer?.switchCamera(useFrontCamera)
        updateCameraCapabilities()
        updateZoomButtons(currentZoomRatio)
        mainHandler.postDelayed({
            if (_binding != null && view != null) {
                binding.cameraLoading.isVisible = false
                binding.btnSwitchCamera.isEnabled = true
            }
        }, SWITCH_CAMERA_LOCK_MS)
    }

    private fun setZoom(ratio: Float) {
        applyZoom(ratio)
    }

    private fun applyZoom(ratio: Float): Boolean {
        val supportsRatio = supportedZoomRatios.any { isSameZoom(it, ratio) } ||
            ratio in DEFAULT_ZOOM_RATIO..DOUBLE_ZOOM_RATIO
        if (!cameraReady || !supportsRatio) return false
        val applied = webRtcStreamer?.setZoomRatio(ratio) == true
        if (!applied) return false

        currentZoomRatio = ratio
        updateZoomButtons(ratio)
        return true
    }

    private fun updateZoomButtons(ratio: Float = currentZoomRatio) {
        tintBackground(
            binding.btnZoomHalf,
            if (isSameZoom(ratio, HALF_ZOOM_RATIO)) ACTIVE_CONTROL_COLOR else INACTIVE_ZOOM_COLOR
        )
        tintBackground(
            binding.btnZoomOne,
            if (isSameZoom(ratio, DEFAULT_ZOOM_RATIO)) ACTIVE_CONTROL_COLOR else INACTIVE_ZOOM_COLOR
        )
        tintBackground(
            binding.btnZoomTwo,
            if (isSameZoom(ratio, DOUBLE_ZOOM_RATIO)) ACTIVE_CONTROL_COLOR else INACTIVE_ZOOM_COLOR
        )
        binding.btnZoomHalf.setTextColor(Color.WHITE)
        binding.btnZoomOne.setTextColor(Color.WHITE)
        binding.btnZoomTwo.setTextColor(Color.WHITE)
    }

    private fun isSameZoom(first: Float, second: Float): Boolean {
        return abs(first - second) < ZOOM_EPSILON
    }

    private fun handleMainAction() {
        releaseLog(
            "CameraCast.handleMainAction: casting=$isCasting starting=$isStartingCast reconnecting=$isReconnecting cameraReady=$cameraReady"
        )
        when {
            isCasting || isStartingCast -> showStopCastingDialog()
            isReconnecting -> Unit
            else -> startCastFlow()
        }
    }

    private fun startCastFlow() {
        releaseLog("CameraCast.startCastFlow: cameraReady=$cameraReady connected=${currentCastSession()?.isConnected == true}")
        if (!cameraReady) {
            if (!hasCameraPermission()) {
                showCameraPermissionDialog()
            } else {
                showCameraStartError()
            }
            return
        }

        val session = currentCastSession()
        if (session?.isConnected != true) {
            pendingCast = true
            updateControls(selectingTv = true)
            binding.btnTopCast.performClick()
            mainHandler.postDelayed({
                if (_binding != null && view != null && pendingCast && currentCastSession()?.isConnected != true) {
                    pendingCast = false
                    showNoDevicesDialog()
                    updateControls()
                }
            }, CAST_SELECTION_TIMEOUT_MS)
            return
        }

        beginCameraCast()
    }

    private fun beginCameraCast() {
        releaseLog("CameraCast.beginCameraCast")
        val session = currentCastSession()
        if (session?.isConnected != true) {
            startCastFlow()
            return
        }

        isStartingCast = true
        isReconnecting = false
        updateControls()
        setCastMessageCallback(session)
        mainHandler.postDelayed({
            if (_binding == null || view == null) return@postDelayed
            runCatching {
                val streamer = webRtcStreamer
                    ?: throw IllegalStateException("Camera WebRTC streamer is not ready")
                streamer.startCasting()
            }.onFailure {
                releaseLog("CameraCast.beginCameraCast: startCasting failed", it)
                isStartingCast = false
                updateCastStatus(CastConnectionState.Error)
                Toast.makeText(
                    requireContext(),
                    it.message ?: getString(R.string.text_could_not_connect_tv),
                    Toast.LENGTH_SHORT
                ).show()
                updateControls()
            }
        }, RECEIVER_READY_DELAY_MS)
    }

    private fun sendSignalToReceiver(message: JSONObject) {
        mainHandler.post {
            currentCastSession()
                ?.sendMessage(WEBRTC_NAMESPACE, message.toString())
                ?.setResultCallback { result ->
                    if (!result.status.isSuccess && _binding != null && view != null) {
                        isStartingCast = false
                        updateCastStatus(CastConnectionState.Error)
                        updateControls()
                    }
                }
        }
    }

    private fun setCastMessageCallback(session: CastSession) {
        runCatching {
            session.removeMessageReceivedCallbacks(WEBRTC_NAMESPACE)
            session.setMessageReceivedCallbacks(WEBRTC_NAMESPACE, receiverMessageCallback)
        }.onFailure {
            onWebRtcError(it.message ?: getString(R.string.text_could_not_connect_tv))
        }
    }

    private fun removeCastMessageCallback(session: CastSession) {
        runCatching {
            session.removeMessageReceivedCallbacks(WEBRTC_NAMESPACE)
        }
    }

    private fun handleReceiverMessage(rawMessage: String) {
        val message = runCatching { JSONObject(rawMessage) }.getOrNull() ?: return
        when (message.optString("type")) {
            "ANSWER" -> webRtcStreamer?.handleAnswer(message.optString("sdp"))
            "ICE_CANDIDATE" -> {
                val candidate = message.optJSONObject("candidate") ?: return
                webRtcStreamer?.handleRemoteIceCandidate(candidate)
            }
            "ERROR" -> onWebRtcError(
                message.optString("message", getString(R.string.text_could_not_connect_tv))
            )
        }
    }

    private fun showStopCastingDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.text_stop_camera_casting_title)
            .setMessage(R.string.text_stop_camera_casting_message)
            .setNegativeButton(R.string.text_cancel, null)
            .setPositiveButton(R.string.text_stop_casting) { _, _ -> stopCasting() }
            .show()
    }

    private fun stopCasting() {
        sendSignalToReceiver(JSONObject().put("type", "STOP"))
        currentCastSession()?.let(::removeCastMessageCallback)
        webRtcStreamer?.stopCasting()
        isCasting = false
        isStartingCast = false
        isReconnecting = false
        pendingCast = false
        updateControls()
    }

    private fun beginReconnectState() {
        if (!isCasting && !isStartingCast) return
        isReconnecting = true
        updateCastStatus(CastConnectionState.Connecting)
        updateControls()
        mainHandler.postDelayed({
            if (_binding != null && view != null && isReconnecting) {
                isReconnecting = false
                isCasting = false
                isStartingCast = false
                updateCastStatus(CastConnectionState.Error)
                showConnectionLostDialog()
                updateControls()
            }
        }, RECONNECT_TIMEOUT_MS)
    }

    private fun showNoDevicesDialog() {
        showCastFailureDialog()
    }

    private fun showConnectFailedDialog() {
        showCastFailureDialog()
    }

    private fun showConnectionLostDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.text_connection_lost_camera_stopped)
            .setNegativeButton(R.string.text_close, null)
            .setPositiveButton(R.string.text_choose_another_tv) { _, _ ->
                pendingCast = true
                isStartingCast = true
                updateControls(selectingTv = true)
                binding.btnTopCast.performClick()
            }
            .show()
    }

    private fun updateControls(selectingTv: Boolean = false) {
        if (_binding == null || view == null) return

        binding.btnStartCasting.isEnabled = cameraReady && !isReconnecting
        binding.btnStartCasting.alpha = if (binding.btnStartCasting.isEnabled) 1f else 0.62f
        binding.btnStartCasting.text = when {
            isReconnecting -> getString(R.string.text_reconnecting)
            isCasting -> getString(R.string.text_stop_casting)
            isStartingCast -> getString(R.string.text_starting_camera_cast)
            pendingCast || selectingTv -> getString(R.string.text_select_a_tv)
            else -> getString(R.string.text_start_casting)
        }
        binding.btnStartCasting.setBackgroundResource(
            if (isCasting || isStartingCast || isReconnecting) {
                R.drawable.bg_cast_media_stop_action
            } else {
                R.drawable.bg_cast_youtube_action
            }
        )

        binding.btnMic.setImageResource(
            if (microphoneMuted) R.drawable.ic_mic_off_white else R.drawable.ic_mic_white
        )
        tintBackground(binding.btnMic, if (microphoneMuted) INACTIVE_CONTROL_COLOR else ACTIVE_CONTROL_COLOR)
        tintBackground(binding.btnFlash, if (torchEnabled) ACTIVE_CONTROL_COLOR else INACTIVE_CONTROL_COLOR)

        binding.audioNotice.isVisible = microphonePermissionDenied && microphoneMuted
        binding.castStatusText.text = when {
            isCasting -> {
                val deviceName = currentCastSession()?.castDevice?.friendlyName ?: "TV"
                val audio = getString(
                    if (microphoneMuted) {
                        R.string.text_camera_audio_muted
                    } else {
                        R.string.text_camera_audio_on
                    }
                )
                "${getString(R.string.text_casting_to_tv, deviceName)} · $audio"
            }
            isReconnecting -> getString(R.string.text_reconnecting)
            isStartingCast -> getString(R.string.text_starting_camera_cast)
            pendingCast || selectingTv -> getString(R.string.text_select_a_tv)
            cameraReady -> getString(R.string.text_camera_ready)
            else -> getString(R.string.text_camera_permission_required)
        }
        updateZoomButtons()
    }

    private fun updateCastStatusFromSession() {
        val state = if (currentCastSession()?.isConnected == true) {
            CastConnectionState.Connected
        } else {
            CastConnectionState.Disconnected
        }
        updateCastStatus(state)
        updateControls()
    }

    private fun updateCastStatus(state: CastConnectionState) {
        val color = when (state) {
            CastConnectionState.Disconnected -> "#777777"
            CastConnectionState.Connecting -> "#F4D188"
            CastConnectionState.Connected -> "#84FF6A"
            CastConnectionState.Error -> "#FF5C5C"
        }
        ViewCompat.setBackgroundTintList(
            binding.connectionDot,
            ColorStateList.valueOf(Color.parseColor(color))
        )
    }

    private fun currentCastSession(): CastSession? {
        return castContext?.sessionManager?.currentCastSession
    }

    private fun handleBackPressed() {
        releaseLog("CameraCast.handleBackPressed")
        if (isCasting || isStartingCast || isReconnecting) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.text_camera_currently_casting)
                .setMessage(R.string.text_camera_currently_casting_message)
                .setNegativeButton(R.string.text_cancel, null)
                .setPositiveButton(R.string.text_stop_casting) { _, _ ->
                    stopCasting()
                    popBackStack()
                }
                .show()
        } else {
            stopCamera()
            popBackStack()
        }
    }

    private fun tintBackground(view: View, color: String) {
        ViewCompat.setBackgroundTintList(view, ColorStateList.valueOf(Color.parseColor(color)))
    }

    private fun releaseLog(message: String) {
        Log.i(TAG_RELEASE, message)
    }

    private fun releaseLog(message: String, throwable: Throwable) {
        Log.e(TAG_RELEASE, message, throwable)
    }

    private enum class CastConnectionState {
        Disconnected,
        Connecting,
        Connected,
        Error
    }

    companion object {
        private const val TAG_RELEASE = "TVCastReleaseLog"
        private const val PREFS_NAME = "camera_cast"
        private const val KEY_CAMERA_PERMISSION_ASKED = "camera_permission_asked"
        private const val DEFAULT_ZOOM_RATIO = 1f
        private const val HALF_ZOOM_RATIO = 0.5f
        private const val DOUBLE_ZOOM_RATIO = 2f
        private const val ZOOM_EPSILON = 0.01f
        private const val CAST_SELECTION_TIMEOUT_MS = 30_000L
        private const val RECEIVER_READY_DELAY_MS = 700L
        private const val RECONNECT_TIMEOUT_MS = 5_000L
        private const val SWITCH_CAMERA_LOCK_MS = 500L
        private const val WEBRTC_NAMESPACE = "urn:x-cast:com.example.camera.webrtc"
        private const val ACTIVE_CONTROL_COLOR = "#D6A948"
        private const val INACTIVE_CONTROL_COLOR = "#626262"
        private const val INACTIVE_ZOOM_COLOR = "#5D5743"
    }
}
