package com.example.base.ui.cast_camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.example.base.R
import com.example.base.databinding.FragmentCameraCastBinding
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import hoang.dqm.codebase.base.activity.BaseFragment
import hoang.dqm.codebase.base.activity.onBackPressed
import hoang.dqm.codebase.base.activity.popBackStack

class CameraCastFragment : BaseFragment<FragmentCameraCastBinding, CameraCastViewModel>() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences

    private var castContext: CastContext? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var scaleDetector: ScaleGestureDetector? = null

    private var pendingCast = false
    private var isCasting = false
    private var isStartingCast = false
    private var isReconnecting = false
    private var microphoneMuted = true
    private var microphonePermissionDenied = false
    private var torchEnabled = false
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var currentZoomRatio = DEFAULT_ZOOM_RATIO
    private var cameraReady = false
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
        } else {
            microphoneMuted = true
            microphonePermissionDenied = true
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
            if (isCasting) {
                beginReconnectState()
            } else {
                updateCastStatus(CastConnectionState.Connecting)
            }
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            val wasCasting = isCasting || isReconnecting
            pendingCast = false
            isStartingCast = false
            isCasting = false
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
            if (isCasting) {
                updateControls()
            }
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            isReconnecting = false
            isCasting = false
            updateCastStatus(CastConnectionState.Error)
            showConnectionLostDialog()
            updateControls()
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            if (isCasting) {
                beginReconnectState()
            }
        }
    }

    override fun initView() {
        prefs = requireContext().getSharedPreferences(PREFS_NAME, 0)
        applySystemInsets()
        setupCastButton()
        setupPreviewGestures()
        updateControls()
    }

    override fun initListener() {
        binding.btnBack.setOnClickListener { handleBackPressed() }
        binding.btnStartCasting.setOnClickListener { handleMainAction() }
        binding.placeholderAction.setOnClickListener { handlePlaceholderAction() }
        binding.btnFlash.setOnClickListener { toggleTorch() }
        binding.btnMic.setOnClickListener { toggleMicrophone() }
        binding.btnSwitchCamera.setOnClickListener { switchCamera() }
        binding.btnZoomHalf.setOnClickListener { setZoom(HALF_ZOOM_RATIO) }
        binding.btnZoomOne.setOnClickListener { setZoom(DEFAULT_ZOOM_RATIO) }
        onBackPressed(Runnable { handleBackPressed() })
    }

    override fun initData() {
        if (hasCameraPermission()) {
            startCamera()
        } else {
            showCameraPermissionDialog()
            showPermissionPlaceholder(permanentlyDenied = false)
        }
    }

    override fun onStart() {
        super.onStart()
        castContext?.sessionManager?.addSessionManagerListener(
            castSessionListener,
            CastSession::class.java
        )
        updateCastStatusFromSession()
    }

    override fun onResume() {
        super.onResume()
        if (hasCameraPermission() && !cameraReady) {
            hidePermissionPlaceholder()
            startCamera()
        }
    }

    override fun onStop() {
        castContext?.sessionManager?.removeSessionManagerListener(
            castSessionListener,
            CastSession::class.java
        )
        super.onStop()
    }

    override fun onDestroyView() {
        mainHandler.removeCallbacksAndMessages(null)
        stopCamera()
        super.onDestroyView()
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

    private fun setupCastButton() {
        runCatching {
            castContext = CastContext.getSharedInstance(requireContext())
            CastButtonFactory.setUpMediaRouteButton(requireContext(), binding.btnTopCast)
            updateCastStatusFromSession()
        }.onFailure {
            binding.btnTopCast.isEnabled = false
            binding.btnTopCast.alpha = 0.45f
            updateCastStatus(CastConnectionState.Error)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPreviewGestures() {
        scaleDetector = ScaleGestureDetector(
            requireContext(),
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val zoomState = camera?.cameraInfo?.zoomState?.value ?: return false
                    val nextZoom = (currentZoomRatio * detector.scaleFactor)
                        .coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                    setZoom(nextZoom)
                    return true
                }
            }
        )

        binding.previewView.setOnTouchListener { _, event ->
            scaleDetector?.onTouchEvent(event)
            true
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
        binding.previewView.isVisible = false
    }

    private fun hidePermissionPlaceholder() {
        binding.placeholderContainer.isVisible = false
        binding.previewView.isVisible = true
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
        if (!hasCameraPermission()) {
            showPermissionPlaceholder(permanentlyDenied = isCameraPermanentlyDenied())
            updateControls()
            return
        }

        binding.cameraLoading.isVisible = true
        binding.cameraLoadingText.text = getString(R.string.text_please_wait_a_moment)
        binding.placeholderContainer.isVisible = false
        binding.previewView.isVisible = true

        val providerFuture = ProcessCameraProvider.getInstance(requireContext())
        providerFuture.addListener(
            {
                runCatching {
                    cameraProvider = providerFuture.get()
                    bindCamera()
                }.onFailure {
                    showCameraStartError()
                }
            },
            ContextCompat.getMainExecutor(requireContext())
        )
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val preferredSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()
        val selector = if (provider.hasCamera(preferredSelector)) {
            preferredSelector
        } else {
            lensFacing = CameraSelector.LENS_FACING_BACK
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.previewView.surfaceProvider)
        }

        runCatching {
            provider.unbindAll()
            camera = provider.bindToLifecycle(viewLifecycleOwner, selector, preview)
            cameraReady = true
            binding.cameraLoading.isVisible = false
            currentZoomRatio = DEFAULT_ZOOM_RATIO
            setZoom(DEFAULT_ZOOM_RATIO)
            updateCameraCapabilities()
            updateControls()
        }.onFailure {
            showCameraStartError()
        }
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
        camera = null
        cameraReady = false
        torchEnabled = false
    }

    private fun showCameraStartError() {
        cameraReady = false
        isCasting = false
        isStartingCast = false
        stopCamera()
        binding.cameraLoading.isVisible = false
        binding.previewView.isVisible = false
        binding.placeholderContainer.isVisible = true
        binding.placeholderTitle.setText(R.string.text_unable_to_start_camera)
        binding.placeholderMessage.setText(R.string.text_unable_to_start_camera_message)
        binding.placeholderAction.setText(R.string.text_try_again)
        updateCastStatusFromSession()
        updateControls()
    }

    private fun updateCameraCapabilities() {
        val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true &&
                lensFacing == CameraSelector.LENS_FACING_BACK
        binding.btnFlash.isEnabled = hasFlash
        binding.btnFlash.alpha = if (hasFlash) 1f else 0.45f
        if (!hasFlash) {
            torchEnabled = false
        }

        val zoomState = camera?.cameraInfo?.zoomState?.value
        binding.btnZoomHalf.isVisible = (zoomState?.minZoomRatio ?: DEFAULT_ZOOM_RATIO) <= HALF_ZOOM_RATIO
    }

    private fun toggleTorch() {
        val currentCamera = camera ?: return
        if (currentCamera.cameraInfo.hasFlashUnit()) {
            torchEnabled = !torchEnabled
            currentCamera.cameraControl.enableTorch(torchEnabled)
            updateControls()
        }
    }

    private fun toggleMicrophone() {
        if (!microphoneMuted) {
            microphoneMuted = true
            updateControls()
            return
        }

        if (hasMicrophonePermission()) {
            microphoneMuted = false
            microphonePermissionDenied = false
            updateControls()
        } else {
            MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.text_microphone_permission_required)
                .setNegativeButton(R.string.text_not_now) { _, _ ->
                    microphoneMuted = true
                    microphonePermissionDenied = true
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
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        torchEnabled = false
        bindCamera()
        mainHandler.postDelayed({
            if (_binding != null && view != null) {
                binding.btnSwitchCamera.isEnabled = true
            }
        }, SWITCH_CAMERA_LOCK_MS)
    }

    private fun setZoom(ratio: Float) {
        val currentCamera = camera ?: return
        val zoomState = currentCamera.cameraInfo.zoomState.value ?: return
        currentZoomRatio = ratio.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
        currentCamera.cameraControl.setZoomRatio(currentZoomRatio)
        updateZoomButtons()
    }

    private fun updateZoomButtons() {
        val isHalf = currentZoomRatio < 0.75f
        tintBackground(binding.btnZoomHalf, if (isHalf) ACTIVE_CONTROL_COLOR else INACTIVE_ZOOM_COLOR)
        tintBackground(binding.btnZoomOne, if (!isHalf) ACTIVE_CONTROL_COLOR else INACTIVE_ZOOM_COLOR)
        binding.btnZoomHalf.setTextColor(Color.WHITE)
        binding.btnZoomOne.setTextColor(Color.WHITE)
    }

    private fun handleMainAction() {
        when {
            isCasting -> showStopCastingDialog()
            isReconnecting -> Unit
            else -> startCastFlow()
        }
    }

    private fun startCastFlow() {
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
        if (!cameraReady) {
            showCameraStartError()
            return
        }
        isStartingCast = true
        updateControls()
        mainHandler.postDelayed({
            if (_binding == null || view == null) return@postDelayed
            if (currentCastSession()?.isConnected == true) {
                isStartingCast = false
                isCasting = true
                updateCastStatus(CastConnectionState.Connected)
                updateControls()
            } else {
                isStartingCast = false
                showConnectFailedDialog()
                updateControls()
            }
        }, START_CAST_DELAY_MS)
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
        currentCastSession()?.remoteMediaClient?.stop()
        isCasting = false
        isStartingCast = false
        isReconnecting = false
        pendingCast = false
        updateControls()
    }

    private fun beginReconnectState() {
        if (!isCasting) return
        isReconnecting = true
        updateCastStatus(CastConnectionState.Connecting)
        updateControls()
        mainHandler.postDelayed({
            if (_binding != null && view != null && isReconnecting) {
                isReconnecting = false
                isCasting = false
                updateCastStatus(CastConnectionState.Error)
                showConnectionLostDialog()
                updateControls()
            }
        }, RECONNECT_TIMEOUT_MS)
    }

    private fun showNoDevicesDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.text_no_cast_devices_found)
            .setMessage(R.string.text_no_cast_devices_found_message)
            .setPositiveButton(R.string.text_try_again) { _, _ -> startCastFlow() }
            .show()
    }

    private fun showConnectFailedDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.text_could_not_connect_tv)
            .setNegativeButton(R.string.text_cancel, null)
            .setPositiveButton(R.string.text_retry) { _, _ -> startCastFlow() }
            .show()
    }

    private fun showConnectionLostDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.text_connection_lost_camera_stopped)
            .setNegativeButton(R.string.text_close, null)
            .setPositiveButton(R.string.text_choose_another_tv) { _, _ ->
                pendingCast = true
                binding.btnTopCast.performClick()
                updateControls(selectingTv = true)
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
            if (isCasting || isReconnecting) {
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

    private enum class CastConnectionState {
        Disconnected,
        Connecting,
        Connected,
        Error
    }

    companion object {
        private const val PREFS_NAME = "camera_cast"
        private const val KEY_CAMERA_PERMISSION_ASKED = "camera_permission_asked"
        private const val DEFAULT_ZOOM_RATIO = 1f
        private const val HALF_ZOOM_RATIO = 0.5f
        private const val CAST_SELECTION_TIMEOUT_MS = 30_000L
        private const val START_CAST_DELAY_MS = 900L
        private const val RECONNECT_TIMEOUT_MS = 5_000L
        private const val SWITCH_CAMERA_LOCK_MS = 500L
        private const val ACTIVE_CONTROL_COLOR = "#D6A948"
        private const val INACTIVE_CONTROL_COLOR = "#626262"
        private const val INACTIVE_ZOOM_COLOR = "#5D5743"
    }
}
