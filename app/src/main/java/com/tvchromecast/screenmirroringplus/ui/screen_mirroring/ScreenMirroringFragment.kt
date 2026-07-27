package com.tvchromecast.screenmirroringplus.ui.screen_mirroring

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.Surface
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.mediarouter.app.MediaRouteChooserDialogFragment
import androidx.mediarouter.app.MediaRouteControllerDialogFragment
import androidx.mediarouter.app.MediaRouteDialogFactory
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tvchromecast.screenmirroringplus.R
import com.tvchromecast.screenmirroringplus.cast.CastReceiverIds
import com.tvchromecast.screenmirroringplus.databinding.FragmentScreenMirroringBinding
import com.tvchromecast.screenmirroringplus.ui.common.showCastFailureDialog
import com.tvchromecast.screenmirroringplus.utils.AppConstants
import hoang.dqm.codebase.base.activity.BaseFragment
import hoang.dqm.codebase.base.activity.onBackPressed
import hoang.dqm.codebase.base.activity.popBackStack
import org.json.JSONObject

class ScreenMirroringFragment : BaseFragment<FragmentScreenMirroringBinding, ScreenMirroringViewModel>(),
    ScreenWebRtcStreamer.Listener {

    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var displayManager: DisplayManager
    private var displayListener: DisplayManager.DisplayListener? = null
    private var castContext: CastContext? = null
    private var mediaRouter: MediaRouter? = null
    private var screenStreamer: ScreenWebRtcStreamer? = null
    private var mediaProjectionData: Intent? = null
    private var screenLandscape = false
    private var selectedQuality = MirroringQuality.HIGH
    private var autoRotateEnabled = true
    private var soundEnabled = true
    private var pendingMirroring = false
    private var isPreparing = false
    private var isMirroring = false
    private var isReconnecting = false
    private var isStoppingMirroring = false
    private var resumeStartAfterAudioPermission = false
    private var updatingSoundSwitch = false
    private var toolbarBaseHeight = 0
    private var bottomButtonBaseMargin = 0

    private val usesSystemMirroring: Boolean
        get() = AppConstants.SCREEN_MIRRORING_OPTION ==
            AppConstants.SCREEN_MIRRORING_OPTION_SYSTEM

    private val screenCapturePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            mediaProjectionData = data
            if (usesSystemMirroring) {
                syncMirroringState()
            } else {
                beginScreenMirroring()
            }
        } else {
            resetPendingMirroring()
            syncMirroringState()
            showPermissionDeniedDialog()
        }
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        soundEnabled = granted
        updateSoundSwitch(granted)
        screenStreamer?.setAudioEnabled(granted)
        if (!granted) {
            Toast.makeText(
                requireContext(),
                R.string.text_screen_audio_not_transmitted,
                Toast.LENGTH_SHORT
            ).show()
        }

        if (resumeStartAfterAudioPermission) {
            resumeStartAfterAudioPermission = false
            startMirroringFlow()
        } else {
            updateControls()
        }
    }

    private val receiverMessageCallback = Cast.MessageReceivedCallback { _, _, message ->
        handleReceiverMessage(message)
    }

    private val castRouteSelector = MediaRouteSelector.Builder()
        .addControlCategory(CastMediaControlIntent.categoryForCast(CastReceiverIds.CAMERA_WEBRTC))
        .build()

    private val mediaRouteDialogFactory = object : MediaRouteDialogFactory() {
        override fun onCreateChooserDialogFragment(): MediaRouteChooserDialogFragment {
            return ScreenMirroringRouteChooserDialogFragment()
        }

        override fun onCreateControllerDialogFragment(): MediaRouteControllerDialogFragment {
            return ScreenMirroringRouteControllerDialogFragment()
        }
    }

    private val mediaRouterCallback = object : MediaRouter.Callback() {
        override fun onRouteSelected(
            router: MediaRouter,
            route: MediaRouter.RouteInfo,
            reason: Int
        ) {
            handleMediaRouteChanged()
        }

        override fun onRouteSelected(
            router: MediaRouter,
            selectedRoute: MediaRouter.RouteInfo,
            reason: Int,
            requestedRoute: MediaRouter.RouteInfo
        ) {
            handleMediaRouteChanged()
        }

        override fun onRouteUnselected(
            router: MediaRouter,
            route: MediaRouter.RouteInfo,
            reason: Int
        ) {
            handleMediaRouteChanged()
        }

        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) {
            handleMediaRouteChanged()
        }

        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) {
            handleMediaRouteChanged()
        }
    }

    private val castSessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            updateCastStatus(CastConnectionState.Connecting)
            updateControls()
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            updateCastStatus(CastConnectionState.Connected)
            if (pendingMirroring) {
                beginScreenMirroring()
            } else {
                updateControls()
            }
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            resetPendingMirroring()
            updateCastStatus(CastConnectionState.Error)
            showConnectFailedDialog()
            updateControls()
        }

        override fun onSessionEnding(session: CastSession) {
            if ((isMirroring || isPreparing) && !isStoppingMirroring) {
                beginReconnectState()
            } else {
                updateCastStatus(CastConnectionState.Connecting)
            }
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            val wasMirroring = isMirroring || isPreparing || isReconnecting
            removeCastMessageCallback(session)
            resetMirroringState()
            clearScreenCapture()
            ScreenMirroringForegroundService.stop(requireContext())
            updateCastStatus(CastConnectionState.Disconnected)
            if (wasMirroring && !isStoppingMirroring) {
                showConnectionLostDialog()
            }
            isStoppingMirroring = false
            updateControls()
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) {
            beginReconnectState()
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            isReconnecting = false
            updateCastStatus(CastConnectionState.Connected)
            if (isMirroring || isPreparing) {
                beginScreenMirroring()
            } else {
                updateControls()
            }
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            resetMirroringState()
            updateCastStatus(CastConnectionState.Error)
            showConnectionLostDialog()
            updateControls()
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            if (isMirroring || isPreparing) {
                beginReconnectState()
            }
        }
    }

    override fun initView() {
        mediaProjectionManager = requireContext()
            .getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        displayManager = requireContext()
            .getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        screenStreamer = ScreenWebRtcStreamer(
            requireContext().applicationContext,
            ::sendSignalToReceiver,
            this
        )
        setupDisplayListener()
        applySystemInsets()
        applyRequestedOrientation()
        setupCastButton()
        updateQualitySelection()
        updateControls()
    }

    override fun initListener() {
        setupRouteDialogCloseListener()
        binding.btnBack.setOnClickListener { handleBackPressed() }
        binding.btnStartMirroring.setOnClickListener { handleMainAction() }
        binding.helpChip.setOnClickListener { showHelpDialog() }
        binding.rowHigh.setOnClickListener { selectQuality(MirroringQuality.HIGH) }
        binding.rowMedium.setOnClickListener { selectQuality(MirroringQuality.MEDIUM) }
        binding.rowLow.setOnClickListener { selectQuality(MirroringQuality.LOW) }
        binding.switchAutoRotate.setOnCheckedChangeListener { _, isChecked ->
            autoRotateEnabled = isChecked
            applyLiveConfig()
        }
        binding.switchSound.setOnCheckedChangeListener { _, isChecked ->
            if (updatingSoundSwitch) return@setOnCheckedChangeListener

            if (isChecked && !hasAudioPermission()) {
                soundEnabled = false
                updateSoundSwitch(false)
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return@setOnCheckedChangeListener
            }

            soundEnabled = isChecked
            screenStreamer?.setAudioEnabled(isChecked)
            updateControls()
        }
        onBackPressed(Runnable { handleBackPressed() })
    }

    private fun setupRouteDialogCloseListener() {
        requireActivity().supportFragmentManager.setFragmentResultListener(
            ROUTE_DIALOG_CLOSED_REQUEST_KEY,
            viewLifecycleOwner
        ) { _, _ ->
            handleRouteChooserClosed()
        }
    }

    override fun initData() = Unit

    override fun onStart() {
        super.onStart()
        if (!usesSystemMirroring) {
            castContext?.sessionManager?.addSessionManagerListener(
                castSessionListener,
                CastSession::class.java
            )
        }
        mediaRouter?.addCallback(
            castRouteSelector,
            mediaRouterCallback,
            MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY or
                MediaRouter.CALLBACK_FLAG_UNFILTERED_EVENTS
        )
        syncMirroringState()
    }

    override fun onResume() {
        super.onResume()
        syncMirroringState()
    }

    override fun onStop() {
        mediaRouter?.removeCallback(mediaRouterCallback)
        if (!usesSystemMirroring) {
            castContext?.sessionManager?.removeSessionManagerListener(
                castSessionListener,
                CastSession::class.java
            )
        }
        super.onStop()
    }

    override fun onDestroyView() {
        stopMirroring(updateUi = false, endSession = true)
        mainHandler.removeCallbacksAndMessages(null)
        displayListener?.let(displayManager::unregisterDisplayListener)
        displayListener = null
        screenStreamer?.release()
        screenStreamer = null
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        super.onDestroyView()
    }

    override fun onWebRtcConnected() {
        mainHandler.post {
            if (_binding == null || view == null) return@post
            pendingMirroring = false
            isPreparing = false
            isReconnecting = false
            isMirroring = true
            updateCastStatus(CastConnectionState.Connected)
            updateControls()
        }
    }

    override fun onWebRtcDisconnected() {
        mainHandler.post {
            if (_binding == null || view == null) return@post
            if ((isMirroring || isPreparing) && !isStoppingMirroring) {
                beginReconnectState()
            }
        }
    }

    override fun onWebRtcError(message: String) {
        mainHandler.post {
            if (_binding == null || view == null) return@post
            resetMirroringState()
            clearScreenCapture()
            ScreenMirroringForegroundService.stop(requireContext())
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

            val params = binding.btnStartMirroring.layoutParams as ViewGroup.MarginLayoutParams
            if (bottomButtonBaseMargin == 0) {
                bottomButtonBaseMargin = params.bottomMargin
            }
            params.bottomMargin = bottomButtonBaseMargin + systemBars.bottom
            binding.btnStartMirroring.layoutParams = params

            insets
        }
    }

    private fun setupCastButton() {
        mediaRouter = MediaRouter.getInstance(requireContext())
        if (usesSystemMirroring) {
            binding.btnTopCast.isVisible = false
            syncMirroringState()
            return
        }

        binding.btnTopCast.isVisible = true
        runCatching {
            castContext = CastContext.getSharedInstance(requireContext())
            castContext?.setReceiverApplicationId(CastReceiverIds.CAMERA_WEBRTC)
            CastButtonFactory.setUpMediaRouteButton(requireContext(), binding.btnTopCast)
            binding.btnTopCast.setDialogFactory(mediaRouteDialogFactory)
            updateCastStatusFromSession()
        }.onFailure {
            binding.btnTopCast.isEnabled = false
            binding.btnTopCast.alpha = 0.45f
            updateCastStatus(CastConnectionState.Error)
        }
    }

    private fun handleMediaRouteChanged() {
        if (_binding == null || view == null) return

        if (usesSystemMirroring) {
            syncMirroringState()
            return
        }

        if (!hasConnectedCastRoute() && (isMirroring || isPreparing || isReconnecting)) {
            handleExternalRouteDisconnected()
            return
        }

        updateCastStatusFromSession()
    }

    private fun handleRouteChooserClosed() {
        mainHandler.postDelayed({
            if (_binding == null || view == null) return@postDelayed
            if (!pendingMirroring) {
                updateCastStatusFromSession()
                return@postDelayed
            }
            if (currentCastSession()?.isConnected == true || selectedNonLocalRoute() != null) {
                updateCastStatusFromSession()
                return@postDelayed
            }

            resetPendingMirroring()
            updateCastStatusFromSession()
        }, ROUTE_DIALOG_CLOSE_SETTLE_DELAY_MS)
    }

    private fun handleExternalRouteDisconnected() {
        val wasMirroring = isMirroring || isPreparing || isReconnecting
        resetMirroringState()
        clearScreenCapture()
        ScreenMirroringForegroundService.stop(requireContext())
        updateCastStatus(CastConnectionState.Disconnected)
        updateControls()
        if (wasMirroring && !isStoppingMirroring) {
            showConnectionLostDialog()
        }
    }

    private fun selectQuality(quality: MirroringQuality) {
        if (selectedQuality == quality) return

        selectedQuality = quality
        updateQualitySelection()
        applyLiveConfig()
        updateControls()
    }

    private fun applyLiveConfig() {
        screenStreamer?.applyConfig(
            selectedQuality.toConfig(),
            autoRotateEnabled,
            isStreamLandscape(),
            selectedQuality.name.lowercase()
        )
    }

    private fun updateQualitySelection() {
        binding.checkHigh.isVisible = selectedQuality == MirroringQuality.HIGH
        binding.checkMedium.isVisible = selectedQuality == MirroringQuality.MEDIUM
        binding.checkLow.isVisible = selectedQuality == MirroringQuality.LOW

        binding.textHigh.setTextColor(qualityTextColor(MirroringQuality.HIGH))
        binding.textMedium.setTextColor(qualityTextColor(MirroringQuality.MEDIUM))
        binding.textLow.setTextColor(qualityTextColor(MirroringQuality.LOW))
    }

    private fun qualityTextColor(quality: MirroringQuality): Int {
        return Color.parseColor(if (selectedQuality == quality) "#FFFFFF" else "#D6D6D6")
    }

    private fun handleMainAction() {
        when {
            isMirroring || isPreparing -> showStopMirroringDialog()
            isReconnecting -> Unit
            else -> startMirroringFlow()
        }
    }

    private fun startMirroringFlow() {
        isStoppingMirroring = false

        if (usesSystemMirroring) {
            startSystemMirroringFlow()
            return
        }

        if (currentCastSession()?.isConnected != true) {
            startCastFlow()
            return
        }

        if (soundEnabled && !hasAudioPermission()) {
            pendingMirroring = true
            isPreparing = true
            resumeStartAfterAudioPermission = true
            updateControls()
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        if (mediaProjectionData == null) {
            pendingMirroring = true
            isPreparing = true
            updateCastStatus(CastConnectionState.Connecting)
            updateControls()
            screenCapturePermissionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
            return
        }

        beginScreenMirroring()
    }

    private fun startSystemMirroringFlow() {
        if (hasSystemMirroringSession()) {
            syncMirroringState()
            return
        }

        pendingMirroring = true
        isPreparing = false
        isReconnecting = false
        updateControls(selectingTv = true)

        runCatching {
            startActivity(Intent(Settings.ACTION_CAST_SETTINGS))
        }.onFailure {
            resetPendingMirroring()
            updateCastStatus(CastConnectionState.Error)
            Toast.makeText(
                requireContext(),
                R.string.text_system_mirroring_unavailable,
                Toast.LENGTH_SHORT
            ).show()
            updateControls()
        }
    }

    private fun startCastFlow() {
        val session = currentCastSession()
        if (session?.isConnected != true) {
            pendingMirroring = true
            isPreparing = false
            updateControls(selectingTv = true)
            binding.btnTopCast.performClick()
            mainHandler.postDelayed({
                if (_binding != null &&
                    view != null &&
                    pendingMirroring &&
                    currentCastSession()?.isConnected != true
                ) {
                    resetPendingMirroring()
                    showNoDevicesDialog()
                    updateCastStatusFromSession()
                }
            }, CAST_SELECTION_TIMEOUT_MS)
            return
        }

        beginScreenMirroring()
    }

    private fun beginScreenMirroring() {
        val session = currentCastSession()
        val permissionData = mediaProjectionData
        if (session?.isConnected != true) {
            startCastFlow()
            return
        }
        if (permissionData == null) {
            pendingMirroring = true
            isPreparing = true
            updateControls()
            screenCapturePermissionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
            return
        }

        pendingMirroring = false
        isPreparing = true
        isReconnecting = false
        updateControls()
        setCastMessageCallback(session)
        val appContext = requireContext().applicationContext
        ScreenMirroringForegroundService.start(appContext)
        mainHandler.postDelayed({
            if (_binding == null || view == null) {
                ScreenMirroringForegroundService.stop(appContext)
                return@postDelayed
            }
            startScreenCaptureAndCast(session, permissionData, appContext)
        }, FOREGROUND_SERVICE_READY_DELAY_MS)
    }

    private fun startScreenCaptureAndCast(
        session: CastSession,
        permissionData: Intent,
        appContext: Context
    ) {
        runCatching {
            screenStreamer?.startCapture(
                permissionData,
                selectedQuality.toConfig(),
                soundEnabled,
                autoRotateEnabled,
                isStreamLandscape(),
                selectedQuality.name.lowercase()
            )
        }.onFailure {
            resetPreparingState()
            ScreenMirroringForegroundService.stop(appContext)
            updateCastStatus(CastConnectionState.Error)
            Toast.makeText(
                requireContext(),
                it.message ?: getString(R.string.text_could_not_connect_tv),
                Toast.LENGTH_SHORT
            ).show()
            updateControls()
            return
        }

        mainHandler.postDelayed({
            if (_binding == null || view == null) return@postDelayed
            runCatching {
                screenStreamer?.startCasting()
            }.onFailure {
                resetPreparingState()
                ScreenMirroringForegroundService.stop(appContext)
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
                    if (!result.status.isSuccess && _binding != null && view != null && !isStoppingMirroring) {
                        resetPreparingState()
                        updateCastStatus(CastConnectionState.Error)
                        updateControls()
                    }
                }
        }
    }

    private fun sendStopToReceiver() {
        currentCastSession()?.let { session ->
            runCatching {
                session.sendMessage(WEBRTC_NAMESPACE, JSONObject().put("type", "STOP").toString())
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
            "ANSWER" -> screenStreamer?.handleAnswer(message.optString("sdp"))
            "ICE_CANDIDATE" -> {
                val candidate = message.optJSONObject("candidate") ?: return
                screenStreamer?.handleRemoteIceCandidate(candidate)
            }
            "ERROR" -> onWebRtcError(
                message.optString("message", getString(R.string.text_could_not_connect_tv))
            )
        }
    }

    private fun showStopMirroringDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.text_stop_screen_mirroring_title)
            .setMessage(
                if (usesSystemMirroring) {
                    R.string.text_stop_system_mirroring_message
                } else {
                    R.string.text_stop_screen_mirroring_message
                }
            )
            .setNegativeButton(R.string.text_cancel, null)
            .setPositiveButton(
                if (usesSystemMirroring) {
                    R.string.text_open_system_mirroring_controls
                } else {
                    R.string.text_stop_mirroring
                }
            ) { _, _ ->
                if (usesSystemMirroring) {
                    openSystemMirroringControls()
                } else {
                    stopMirroring()
                }
            }
            .show()
    }

    private fun openSystemMirroringControls() {
        runCatching {
            startActivity(Intent(Settings.ACTION_CAST_SETTINGS))
        }.onFailure {
            Toast.makeText(
                requireContext(),
                R.string.text_system_mirroring_unavailable,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun stopMirroring(updateUi: Boolean = true, endSession: Boolean = false) {
        if (usesSystemMirroring) {
            resetMirroringState()
            if (updateUi) {
                syncMirroringState()
            }
            return
        }

        isStoppingMirroring = true
        val connectedSession = currentCastSession()
        sendStopToReceiver()
        connectedSession?.let(::removeCastMessageCallback)
        clearScreenCapture()
        resetMirroringState()
        ScreenMirroringForegroundService.stop(requireContext())
        if (endSession && connectedSession?.isConnected == true) {
            castContext?.sessionManager?.endCurrentSession(true)
        } else {
            isStoppingMirroring = false
        }
        if (updateUi) {
            updateCastStatusFromSession()
            updateControls()
        }
    }

    private fun beginReconnectState() {
        if (!isMirroring && !isPreparing) return
        isReconnecting = true
        updateCastStatus(CastConnectionState.Connecting)
        updateControls()
        mainHandler.postDelayed({
            if (_binding != null && view != null && isReconnecting) {
                resetMirroringState()
                updateCastStatus(CastConnectionState.Error)
                showConnectionLostDialog()
                updateControls()
            }
        }, RECONNECT_TIMEOUT_MS)
    }

    private fun showPermissionDeniedDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.text_screen_sharing_permission_required)
            .setNegativeButton(R.string.text_cancel, null)
            .setPositiveButton(R.string.text_try_again) { _, _ -> startMirroringFlow() }
            .show()
    }

    private fun showNoDevicesDialog() {
        showCastFailureDialog()
    }

    private fun showConnectFailedDialog() {
        showCastFailureDialog()
    }

    private fun showConnectionLostDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.text_connection_lost_mirroring_stopped)
            .setNegativeButton(R.string.text_close, null)
            .setPositiveButton(R.string.text_choose_another_tv) { _, _ -> startCastFlow() }
            .show()
    }

    private fun showHelpDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.text_mirroring_help_title)
            .setMessage(R.string.text_mirroring_help_message)
            .setPositiveButton(R.string.text_ok, null)
            .show()
    }

    private fun updateControls(selectingTv: Boolean = false) {
        if (_binding == null || view == null) return

        binding.statusContainer.isVisible = isMirroring ||
            isPreparing ||
            pendingMirroring ||
            isReconnecting ||
            hasConnectedCastRoute() ||
            (usesSystemMirroring && hasSystemMirroringSession())

        binding.btnStartMirroring.isEnabled = !pendingMirroring && !isReconnecting
        binding.btnStartMirroring.alpha = if (binding.btnStartMirroring.isEnabled) 1f else 0.72f
        binding.btnStartMirroring.text = when {
            isReconnecting -> getString(R.string.text_reconnecting)
            isMirroring -> getString(R.string.text_stop_mirroring)
            isPreparing -> getString(R.string.text_preparing_screen)
            pendingMirroring || selectingTv -> getString(R.string.text_select_a_tv)
            else -> getString(R.string.text_start_mirroring)
        }
        binding.btnStartMirroring.setBackgroundResource(
            if (isMirroring || isPreparing || isReconnecting) {
                R.drawable.bg_cast_media_stop_action
            } else {
                R.drawable.bg_cast_youtube_action
            }
        )

        val deviceName = connectedDeviceName()
        binding.statusText.text = when {
            usesSystemMirroring && isMirroring -> {
                getString(R.string.text_mirroring_to_tv, connectedDeviceName())
            }
            isMirroring -> {
                val quality = selectedQualityLabel()
                val sound = getString(
                    if (soundEnabled) R.string.text_screen_sound_on else R.string.text_screen_sound_off
                )
                "${getString(R.string.text_mirroring_to_tv, deviceName)} · $quality · $sound"
            }
            isPreparing -> getString(R.string.text_preparing_screen)
            pendingMirroring || selectingTv -> getString(R.string.text_select_a_tv)
            isReconnecting -> getString(R.string.text_reconnecting)
            hasAndroidManagedScreenCast() -> getString(R.string.text_screen_cast_managed_by_android)
            hasConnectedCastRoute() -> getString(R.string.text_casting_to_tv, deviceName)
            else -> getString(R.string.text_mirroring_not_started)
        }
    }

    private fun updateCastStatusFromSession() {
        if (usesSystemMirroring) {
            syncSystemMirroringState()
            return
        }

        val state = when {
            hasConnectedCastRoute() -> CastConnectionState.Connected
            isSelectedRouteConnecting() -> CastConnectionState.Connecting
            else -> CastConnectionState.Disconnected
        }
        updateCastStatus(state)
        updateControls()
    }

    private fun syncMirroringState() {
        if (usesSystemMirroring) {
            syncSystemMirroringState()
        } else {
            updateCastStatusFromSession()
        }
    }

    private fun syncSystemMirroringState() {
        if (hasSystemMirroringSession()) {
            pendingMirroring = false
            isPreparing = false
            isMirroring = true
            isReconnecting = false
            updateCastStatus(CastConnectionState.Connected)
        } else {
            resetMirroringState()
            updateCastStatus(CastConnectionState.Disconnected)
        }
        updateControls()
    }

    private fun updateCastStatus(state: CastConnectionState) {
        if (_binding == null || view == null) return

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

    private fun resetPendingMirroring() {
        pendingMirroring = false
        isPreparing = false
        isReconnecting = false
        resumeStartAfterAudioPermission = false
    }

    private fun resetPreparingState() {
        pendingMirroring = false
        isPreparing = false
        isReconnecting = false
        resumeStartAfterAudioPermission = false
    }

    private fun resetMirroringState() {
        pendingMirroring = false
        isPreparing = false
        isMirroring = false
        isReconnecting = false
        resumeStartAfterAudioPermission = false
    }

    private fun updateSoundSwitch(checked: Boolean) {
        if (_binding == null || view == null) return

        updatingSoundSwitch = true
        binding.switchSound.isChecked = checked
        updatingSoundSwitch = false
    }

    private fun applyRequestedOrientation() {
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isStreamLandscape(): Boolean {
        return autoRotateEnabled && screenLandscape
    }

    private fun clearScreenCapture() {
        mediaProjectionData = null
        screenStreamer?.stopCapture()
    }

    private fun setupDisplayListener() {
        screenLandscape = isDisplayLandscape()
        displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) {
                    syncMirroringState()
                }
            }

            override fun onDisplayRemoved(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) {
                    syncMirroringState()
                }
            }

            override fun onDisplayChanged(displayId: Int) {
                when (displayId) {
                    Display.DEFAULT_DISPLAY -> {
                        if (updateStreamOrientationFromDisplay() && autoRotateEnabled) {
                            applyLiveConfig()
                        }
                    }
                    else -> syncMirroringState()
                }
            }
        }.also { displayManager.registerDisplayListener(it, mainHandler) }
    }

    private fun updateStreamOrientationFromDisplay(): Boolean {
        val nextLandscape = isDisplayLandscape()
        if (screenLandscape == nextLandscape) return false

        screenLandscape = nextLandscape
        return true
    }

    private fun isDisplayLandscape(): Boolean {
        return when (displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation) {
            Surface.ROTATION_90,
            Surface.ROTATION_270 -> true
            else -> false
        }
    }

    private fun currentCastSession(): CastSession? {
        return castContext?.sessionManager?.currentCastSession
    }

    private fun hasConnectedCastRoute(): Boolean {
        return currentCastSession()?.isConnected == true ||
            selectedNonLocalRoute()?.let { !isRouteConnecting(it) } == true ||
            activePresentationDisplay() != null
    }

    private fun isSelectedRouteConnecting(): Boolean {
        return selectedNonLocalRoute()?.let(::isRouteConnecting) == true
    }

    private fun hasAndroidManagedScreenCast(): Boolean {
        return currentCastSession()?.isConnected != true &&
            selectedNonLocalRoute() == null &&
            activePresentationDisplay() != null
    }

    private fun hasSystemMirroringSession(): Boolean {
        return activePresentationDisplay() != null
    }

    private fun activePresentationDisplay(): Display? {
        return displayManager
            .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { display ->
                display.displayId != Display.DEFAULT_DISPLAY &&
                    display.isValid &&
                    (display.flags and Display.FLAG_PRESENTATION) != 0
            }
    }

    private fun selectedNonLocalRoute(): MediaRouter.RouteInfo? {
        val route = mediaRouter?.selectedRoute ?: return null
        if (!route.isEnabled || route.isDefaultOrBluetooth) return null

        val isCastRoute = route.matchesSelector(castRouteSelector)
        val isRemoteRoute = route.playbackType == MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE
        val isKnownDevice = route.deviceType != MediaRouter.RouteInfo.DEVICE_TYPE_UNKNOWN
        return route.takeIf { isCastRoute || isRemoteRoute || isKnownDevice }
    }

    private fun isRouteConnecting(route: MediaRouter.RouteInfo): Boolean {
        return route.connectionState == MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTING
    }

    private fun connectedDeviceName(): String {
        return currentCastSession()?.castDevice?.friendlyName
            ?: selectedNonLocalRoute()?.name
            ?: activePresentationDisplay()?.name
            ?: "TV"
    }

    private fun selectedQualityLabel(): String {
        return getString(
            when (selectedQuality) {
                MirroringQuality.HIGH -> R.string.text_quality_high
                MirroringQuality.MEDIUM -> R.string.text_quality_medium
                MirroringQuality.LOW -> R.string.text_quality_low
            }
        )
    }

    private fun handleBackPressed() {
        if (isMirroring || isPreparing || isReconnecting) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.text_stop_screen_mirroring_title)
                .setMessage(
                    if (usesSystemMirroring) {
                        R.string.text_stop_system_mirroring_message
                    } else {
                        R.string.text_stop_screen_mirroring_message
                    }
                )
                .setNegativeButton(R.string.text_cancel, null)
                .setPositiveButton(
                    if (usesSystemMirroring) {
                        R.string.text_open_system_mirroring_controls
                    } else {
                        R.string.text_stop_mirroring
                    }
                ) { _, _ ->
                    if (usesSystemMirroring) {
                        openSystemMirroringControls()
                    } else {
                        stopMirroring(endSession = true)
                    }
                    popBackStack()
                }
                .show()
        } else {
            popBackStack()
        }
    }

    private enum class CastConnectionState {
        Disconnected,
        Connecting,
        Connected,
        Error
    }

    class ScreenMirroringRouteChooserDialogFragment : MediaRouteChooserDialogFragment() {
        override fun onCancel(dialog: DialogInterface) {
            super.onCancel(dialog)
            notifyRouteDialogClosed()
        }

        override fun onDismiss(dialog: DialogInterface) {
            super.onDismiss(dialog)
            notifyRouteDialogClosed()
        }
    }

    class ScreenMirroringRouteControllerDialogFragment : MediaRouteControllerDialogFragment() {
        override fun onDismiss(dialog: DialogInterface) {
            super.onDismiss(dialog)
            notifyRouteDialogClosed()
        }
    }

    companion object {
        private const val CAST_SELECTION_TIMEOUT_MS = 30_000L
        private const val RECEIVER_READY_DELAY_MS = 700L
        private const val FOREGROUND_SERVICE_READY_DELAY_MS = 350L
        private const val RECONNECT_TIMEOUT_MS = 5_000L
        private const val ROUTE_DIALOG_CLOSE_SETTLE_DELAY_MS = 250L
        private const val ROUTE_DIALOG_CLOSED_REQUEST_KEY = "screen_mirroring_route_dialog_closed"
        private const val WEBRTC_NAMESPACE = "urn:x-cast:com.example.camera.webrtc"

        private fun MediaRouteChooserDialogFragment.notifyRouteDialogClosed() {
            parentFragmentManager.setFragmentResult(
                ROUTE_DIALOG_CLOSED_REQUEST_KEY,
                Bundle.EMPTY
            )
        }

        private fun MediaRouteControllerDialogFragment.notifyRouteDialogClosed() {
            parentFragmentManager.setFragmentResult(
                ROUTE_DIALOG_CLOSED_REQUEST_KEY,
                Bundle.EMPTY
            )
        }
    }
}
