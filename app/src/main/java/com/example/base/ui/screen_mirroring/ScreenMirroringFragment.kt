package com.example.base.ui.screen_mirroring

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.example.base.R
import com.example.base.databinding.FragmentScreenMirroringBinding
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import hoang.dqm.codebase.base.activity.BaseFragment
import hoang.dqm.codebase.base.activity.onBackPressed
import hoang.dqm.codebase.base.activity.popBackStack

class ScreenMirroringFragment : BaseFragment<FragmentScreenMirroringBinding, ScreenMirroringViewModel>() {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var castContext: CastContext? = null
    private var selectedQuality = MirroringQuality.HIGH
    private var autoRotateEnabled = true
    private var soundEnabled = true
    private var pendingMirroring = false
    private var isRequestingPermission = false
    private var isPreparing = false
    private var isMirroring = false
    private var isReconnecting = false
    private var toolbarBaseHeight = 0
    private var bottomButtonBaseMargin = 0

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isRequestingPermission = false
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            beginMirroring()
        } else {
            pendingMirroring = false
            showPermissionDeniedDialog()
            updateControls()
        }
    }

    private val castSessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            updateCastStatus(CastConnectionState.Connecting)
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            updateCastStatus(CastConnectionState.Connected)
            if (pendingMirroring) {
                requestScreenCapture()
            } else {
                updateControls()
            }
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            pendingMirroring = false
            isRequestingPermission = false
            updateCastStatus(CastConnectionState.Error)
            showConnectFailedDialog()
            updateControls()
        }

        override fun onSessionEnding(session: CastSession) {
            if (isMirroring) {
                beginReconnectState()
            } else {
                updateCastStatus(CastConnectionState.Connecting)
            }
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            val wasMirroring = isMirroring || isReconnecting
            pendingMirroring = false
            isRequestingPermission = false
            isPreparing = false
            isMirroring = false
            isReconnecting = false
            updateCastStatus(CastConnectionState.Disconnected)
            if (wasMirroring) {
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
            updateControls()
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            isReconnecting = false
            isMirroring = false
            updateCastStatus(CastConnectionState.Error)
            showConnectionLostDialog()
            updateControls()
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            if (isMirroring) {
                beginReconnectState()
            }
        }
    }

    override fun initView() {
        applySystemInsets()
        setupCastButton()
        updateQualitySelection()
        updateControls()
    }

    override fun initListener() {
        binding.btnBack.setOnClickListener { handleBackPressed() }
        binding.btnStartMirroring.setOnClickListener { handleMainAction() }
        binding.helpChip.setOnClickListener { showHelpDialog() }
        binding.rowHigh.setOnClickListener { selectQuality(MirroringQuality.HIGH) }
        binding.rowMedium.setOnClickListener { selectQuality(MirroringQuality.MEDIUM) }
        binding.rowLow.setOnClickListener { selectQuality(MirroringQuality.LOW) }
        binding.switchAutoRotate.setOnCheckedChangeListener { _, isChecked ->
            autoRotateEnabled = isChecked
        }
        binding.switchSound.setOnCheckedChangeListener { _, isChecked ->
            soundEnabled = isChecked
            if (isChecked && isMirroring) {
                Toast.makeText(
                    requireContext(),
                    R.string.text_audio_sharing_limited,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        onBackPressed(Runnable { handleBackPressed() })
    }

    override fun initData() = Unit

    override fun onStart() {
        super.onStart()
        castContext?.sessionManager?.addSessionManagerListener(
            castSessionListener,
            CastSession::class.java
        )
        updateCastStatusFromSession()
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

    private fun selectQuality(quality: MirroringQuality) {
        selectedQuality = quality
        selectedQuality.toConfig()
        updateQualitySelection()
        if (isMirroring) {
            Toast.makeText(
                requireContext(),
                R.string.text_quality_changes_next_session,
                Toast.LENGTH_SHORT
            ).show()
        }
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
            isMirroring || isPreparing || isReconnecting -> showStopMirroringDialog()
            else -> startMirroringFlow()
        }
    }

    private fun startMirroringFlow() {
        val session = currentCastSession()
        if (session?.isConnected != true) {
            pendingMirroring = true
            updateControls(selectingTv = true)
            binding.btnTopCast.performClick()
            mainHandler.postDelayed({
                if (_binding != null && view != null && pendingMirroring && currentCastSession()?.isConnected != true) {
                    pendingMirroring = false
                    showNoDevicesDialog()
                    updateControls()
                }
            }, CAST_SELECTION_TIMEOUT_MS)
            return
        }

        requestScreenCapture()
    }

    private fun requestScreenCapture() {
        if (isRequestingPermission) return

        pendingMirroring = false
        isRequestingPermission = true
        updateControls()

        val projectionManager = requireContext().getSystemService(MediaProjectionManager::class.java)
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun beginMirroring() {
        isPreparing = true
        updateControls()
        mainHandler.postDelayed({
            if (_binding == null || view == null) return@postDelayed
            isPreparing = false
            if (currentCastSession()?.isConnected == true) {
                isMirroring = true
                updateCastStatus(CastConnectionState.Connected)
            } else {
                showConnectFailedDialog()
            }
            updateControls()
        }, START_MIRRORING_DELAY_MS)
    }

    private fun showStopMirroringDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.text_stop_screen_mirroring_title)
            .setMessage(R.string.text_stop_screen_mirroring_message)
            .setNegativeButton(R.string.text_cancel, null)
            .setPositiveButton(R.string.text_stop_mirroring) { _, _ -> stopMirroring() }
            .show()
    }

    private fun stopMirroring() {
        currentCastSession()?.remoteMediaClient?.stop()
        pendingMirroring = false
        isRequestingPermission = false
        isPreparing = false
        isMirroring = false
        isReconnecting = false
        updateControls()
    }

    private fun beginReconnectState() {
        if (!isMirroring) return
        isReconnecting = true
        updateCastStatus(CastConnectionState.Connecting)
        updateControls()
        mainHandler.postDelayed({
            if (_binding != null && view != null && isReconnecting) {
                isReconnecting = false
                isMirroring = false
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
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.text_no_cast_devices_found)
            .setMessage(R.string.text_no_cast_devices_found_message)
            .setNegativeButton(R.string.text_cancel, null)
            .setPositiveButton(R.string.text_try_again) { _, _ -> startMirroringFlow() }
            .show()
    }

    private fun showConnectFailedDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.text_could_not_connect_tv)
            .setMessage(R.string.text_select_tv_for_mirroring)
            .setNegativeButton(R.string.text_cancel, null)
            .setPositiveButton(R.string.text_retry) { _, _ -> startMirroringFlow() }
            .show()
    }

    private fun showConnectionLostDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.text_connection_lost_mirroring_stopped)
            .setNegativeButton(R.string.text_close, null)
            .setPositiveButton(R.string.text_choose_another_tv) { _, _ ->
                pendingMirroring = true
                binding.btnTopCast.performClick()
                updateControls(selectingTv = true)
            }
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
                isRequestingPermission ||
                pendingMirroring ||
                isReconnecting ||
                currentCastSession()?.isConnected == true

        binding.btnStartMirroring.isEnabled = !isRequestingPermission && !pendingMirroring
        binding.btnStartMirroring.alpha = if (binding.btnStartMirroring.isEnabled) 1f else 0.72f
        binding.btnStartMirroring.text = when {
            isReconnecting -> getString(R.string.text_reconnecting)
            isMirroring -> getString(R.string.text_stop_mirroring)
            isPreparing -> getString(R.string.text_preparing_screen)
            isRequestingPermission -> getString(R.string.text_waiting_for_permission)
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

        val deviceName = currentCastSession()?.castDevice?.friendlyName ?: "TV"
        binding.statusText.text = when {
            isMirroring -> getString(R.string.text_mirroring_to_tv, deviceName)
            isPreparing -> getString(R.string.text_preparing_screen)
            isRequestingPermission -> getString(R.string.text_waiting_for_permission)
            pendingMirroring || selectingTv -> getString(R.string.text_select_a_tv)
            isReconnecting -> getString(R.string.text_reconnecting)
            currentCastSession()?.isConnected == true -> getString(R.string.text_casting_to_tv, deviceName)
            else -> getString(R.string.text_mirroring_not_started)
        }
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
        if (isMirroring || isPreparing || isReconnecting) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.text_stop_screen_mirroring_title)
                .setMessage(R.string.text_stop_screen_mirroring_message)
                .setNegativeButton(R.string.text_cancel, null)
                .setPositiveButton(R.string.text_stop_mirroring) { _, _ ->
                    stopMirroring()
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

    companion object {
        private const val CAST_SELECTION_TIMEOUT_MS = 30_000L
        private const val START_MIRRORING_DELAY_MS = 900L
        private const val RECONNECT_TIMEOUT_MS = 5_000L
    }
}
