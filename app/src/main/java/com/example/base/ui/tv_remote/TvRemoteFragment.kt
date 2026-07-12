package com.example.base.ui.tv_remote

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.example.base.R
import com.example.base.databinding.FragmentTvRemoteBinding
import com.example.base.tvremote.AndroidTvRemoteController
import com.example.base.tvremote.TvRemoteApp
import com.example.base.tvremote.TvRemoteConnectionState
import com.example.base.tvremote.TvRemoteDevice
import com.example.base.tvremote.TvRemoteException
import com.example.base.tvremote.TvRemoteKey
import com.example.base.tvremote.TvRemotePairingRequiredException
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import hoang.dqm.codebase.base.activity.BaseFragment
import hoang.dqm.codebase.base.activity.onBackPressed
import hoang.dqm.codebase.base.activity.popBackStack
import kotlinx.coroutines.launch
import kotlin.math.abs

class TvRemoteFragment : BaseFragment<FragmentTvRemoteBinding, TvRemoteViewModel>() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val appShortcuts = arrayOf(
        TvRemoteApp("YouTube", "com.google.android.youtube.tv"),
        TvRemoteApp("Netflix", "com.netflix.ninja"),
        TvRemoteApp("Prime Video", "com.amazon.amazonvideo.livingroom"),
        TvRemoteApp("Spotify", "com.spotify.tv.android"),
        TvRemoteApp("Google Play", "com.android.vending"),
        TvRemoteApp("Settings", "com.android.tv.settings")
    )
    private val inputSources = arrayOf(
        TvRemoteKey.Input,
        TvRemoteKey.Input,
        TvRemoteKey.Input,
        TvRemoteKey.Input,
        TvRemoteKey.Input,
        TvRemoteKey.Input
    )
    private val inputLabels = arrayOf("TV Input", "HDMI 1", "HDMI 2", "AV", "USB", "Screen Cast")

    private lateinit var controller: AndroidTvRemoteController
    private var discoveredDevices = emptyList<TvRemoteDevice>()
    private var selectedDevice: TvRemoteDevice? = null
    private var isScanning = false
    private var isConnected = false
    private var isReconnecting = false
    private var toolbarBaseHeight = 0
    private var contentBaseBottomPadding = -1
    private var lastCommandAt = 0L
    private var viewActive = false

    private val nearbyWifiPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!canUseBinding()) return@registerForActivityResult
        if (granted) {
            startScan()
        } else {
            renderNoPermission()
        }
    }

    override fun initView() {
        viewActive = true
        controller = AndroidTvRemoteController(
            context = requireContext(),
            onDevicesChanged = { devices ->
                mainHandler.post {
                    if (!canUseBinding()) return@post
                    discoveredDevices = devices
                    if (devices.isNotEmpty()) {
                        isScanning = false
                    }
                    renderDisconnected()
                }
            },
            onStateChanged = { state ->
                mainHandler.post {
                    if (!canUseBinding()) return@post
                    renderState(state)
                }
            }
        )
        applySystemInsets()
        renderDisconnected()
    }

    override fun initListener() {
        binding.btnBack.setOnClickListener { handleBackPressed() }
        binding.btnRefresh.setOnClickListener { startScan() }
        binding.btnMore.setOnClickListener { showDeviceMenu() }
        binding.rowLivingRoom.setOnClickListener { connectToDiscoveredDevice(0) }
        binding.rowBedroom.setOnClickListener { connectToDiscoveredDevice(1) }
        binding.rowOffice.setOnClickListener { connectToDiscoveredDevice(2) }

        binding.btnPower.setOnClickListener { showPowerDialog() }
        binding.btnInput.setOnClickListener { showInputDialog() }
        binding.btnSettings.setOnClickListener { sendRemoteKey(TvRemoteKey.Settings) }
        binding.btnUp.setOnClickListener { sendRemoteKey(TvRemoteKey.Up) }
        binding.btnDown.setOnClickListener { sendRemoteKey(TvRemoteKey.Down) }
        binding.btnLeft.setOnClickListener { sendRemoteKey(TvRemoteKey.Left) }
        binding.btnRight.setOnClickListener { sendRemoteKey(TvRemoteKey.Right) }
        binding.btnOk.setOnClickListener { sendRemoteKey(TvRemoteKey.Enter) }
        binding.btnBackRemote.setOnClickListener { sendRemoteKey(TvRemoteKey.Back) }
        binding.btnHome.setOnClickListener { sendRemoteKey(TvRemoteKey.Home) }
        binding.btnMenu.setOnClickListener { sendRemoteKey(TvRemoteKey.Menu) }
        binding.btnVolUp.setOnClickListener { sendRemoteKey(TvRemoteKey.VolumeUp) }
        binding.btnVolDown.setOnClickListener { sendRemoteKey(TvRemoteKey.VolumeDown) }
        binding.btnMute.setOnClickListener { sendRemoteKey(TvRemoteKey.Mute) }
        binding.btnChannelUp.setOnClickListener { sendRemoteKey(TvRemoteKey.ChannelUp) }
        binding.btnChannelDown.setOnClickListener { sendRemoteKey(TvRemoteKey.ChannelDown) }
        binding.btnRewind.setOnClickListener { sendRemoteKey(TvRemoteKey.Rewind) }
        binding.btnPlayPause.setOnClickListener { sendRemoteKey(TvRemoteKey.PlayPause) }
        binding.btnForwardMedia.setOnClickListener { sendRemoteKey(TvRemoteKey.Forward) }
        binding.btnKeyboard.setOnClickListener { showKeyboardDialog() }
        binding.btnApps.setOnClickListener { showAppsDialog() }
        binding.btnTouchpad.setOnClickListener { showTouchpadDialog() }

        onBackPressed(Runnable { handleBackPressed() })
    }

    override fun initData() {
        startScan()
    }

    override fun onDestroyView() {
        viewActive = false
        if (::controller.isInitialized) {
            controller.close()
        }
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
            if (contentBaseBottomPadding == -1) {
                contentBaseBottomPadding = binding.contentScroll.paddingBottom
            }
            binding.contentScroll.updatePadding(bottom = systemBars.bottom + contentBaseBottomPadding)
            insets
        }
    }

    private fun startScan() {
        if (!hasNearbyWifiPermission()) {
            requestNearbyWifiPermission()
            return
        }
        selectedDevice = null
        isConnected = false
        isReconnecting = false
        isScanning = true
        discoveredDevices = emptyList()
        renderDisconnected()
        mainHandler.removeCallbacksAndMessages(null)
        controller.startDiscovery()
        mainHandler.postDelayed({
            if (_binding == null || view == null) return@postDelayed
            isScanning = false
            renderDisconnected()
        }, SCAN_TIMEOUT_MS)
    }

    private fun renderDisconnected() {
        if (!canUseBinding()) return
        binding.title.text = getString(R.string.text_tv_remote)
        binding.btnRefresh.isVisible = true
        binding.btnMore.isVisible = false
        binding.scanContainer.isVisible = true
        binding.remoteContainer.isVisible = false

        binding.progressSearching.isVisible = isScanning
        binding.textScanState.text = if (isScanning) {
            getString(R.string.text_searching_for_tvs)
        } else {
            getString(R.string.text_select_your_tv)
        }
        binding.textScanHelp.text = if (isScanning) {
            getString(R.string.text_tv_remote_scan_help)
        } else {
            getString(R.string.text_tv_remote_same_wifi_hint)
        }
        binding.deviceList.isVisible = !isScanning && discoveredDevices.isNotEmpty()
        binding.emptyContainer.isVisible = !isScanning && discoveredDevices.isEmpty()
        bindDeviceRows()
    }

    private fun bindDeviceRows() {
        if (!canUseBinding()) return
        val rows = listOf(
            Triple(binding.rowLivingRoom, binding.livingRoomName, binding.livingRoomMeta),
            Triple(binding.rowBedroom, binding.bedroomName, binding.bedroomMeta),
            Triple(binding.rowOffice, binding.officeName, binding.officeMeta)
        )
        rows.forEachIndexed { index, row ->
            val device = discoveredDevices.getOrNull(index)
            row.first.isVisible = device != null
            if (device != null) {
                row.second.text = device.name
                row.third.text = device.subtitle
            }
        }
    }

    private fun renderNoPermission() {
        if (!canUseBinding()) return
        isScanning = false
        discoveredDevices = emptyList()
        renderDisconnected()
        binding.textScanState.text = getString(R.string.text_permission_required)
        binding.textScanHelp.text = getString(R.string.text_nearby_wifi_permission_message)
    }

    private fun renderState(state: TvRemoteConnectionState) {
        if (!canUseBinding()) return
        when (state) {
            TvRemoteConnectionState.Idle -> Unit
            TvRemoteConnectionState.Searching -> {
                isScanning = true
                renderDisconnected()
            }

            is TvRemoteConnectionState.Pairing -> {
                binding.textScanState.text = getString(R.string.text_pairing_with_tv, state.deviceName)
                binding.textScanHelp.text = getString(R.string.text_check_tv_pairing_code)
            }

            is TvRemoteConnectionState.Connecting -> {
                binding.textScanState.text = getString(R.string.text_connecting_to_device, state.deviceName)
                binding.textScanHelp.text = getString(R.string.text_please_wait_a_moment)
            }

            is TvRemoteConnectionState.Connected -> {
                isConnected = true
                isReconnecting = false
                renderRemote()
            }

            is TvRemoteConnectionState.Reconnecting -> {
                isReconnecting = true
                if (isConnected) {
                    binding.connectionText.text = getString(R.string.text_reconnecting)
                    binding.commandStatusText.text = getString(R.string.text_remote_locked_reconnecting)
                    setRemoteEnabled(false)
                }
            }

            is TvRemoteConnectionState.Disconnected -> {
                isConnected = false
                isReconnecting = false
                if (binding.remoteContainer.isVisible) {
                    binding.connectionText.text = getString(R.string.text_connection_lost)
                    binding.commandStatusText.text = state.reason ?: getString(R.string.text_connection_lost)
                    setRemoteEnabled(false)
                }
            }

            is TvRemoteConnectionState.Error -> {
                Toast.makeText(requireContext(), state.message, Toast.LENGTH_SHORT).show()
                isScanning = false
                renderDisconnected()
                Log.d("renderState", "renderState: ${state.message}")
            }
        }
    }

    private fun connectToDiscoveredDevice(index: Int) {
        val device = discoveredDevices.getOrNull(index) ?: return
        connectToDevice(device)
    }

    private fun connectToDevice(device: TvRemoteDevice) {
        selectedDevice = device
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                controller.stopDiscovery()
                controller.connect(device)
            } catch (_: TvRemotePairingRequiredException) {
                beginPairing(device)
            } catch (error: Throwable) {
                showConnectionError(error)
            }
        }
    }

    private suspend fun beginPairing(device: TvRemoteDevice) {
        try {
            controller.startPairing(device)
            showPairingDialog(device)
        } catch (error: Throwable) {
            showConnectionError(error)
        }
    }

    private fun showPairingDialog(device: TvRemoteDevice) {
        if (isScanning) return

        val codeInput = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(PAIRING_CODE_LENGTH))
            hint = getString(R.string.text_pairing_code_hint)
            setSingleLine(true)
            setPadding(32, 16, 32, 16)
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.text_enter_pairing_code_title))
            .setMessage(getString(R.string.text_enter_pairing_code_message, device.name))
            .setView(codeInput)
            .setNegativeButton(R.string.text_cancel, null)
            .setPositiveButton(R.string.text_connect, null)
            .show()

        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val code = codeInput.text.toString().trim()
            if (!PAIRING_CODE_REGEX.matches(code)) {
                codeInput.error = getString(R.string.text_pairing_code_format_error)
                return@setOnClickListener
            }
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    controller.finishPairing(code)
                    dialog.dismiss()
                    controller.connect(device)
                } catch (error: Throwable) {
                    codeInput.error = error.message ?: getString(R.string.text_incorrect_pairing_code)
                }
            }
        }

        codeInput.requestFocus()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }

    private fun renderRemote() {
        if (!canUseBinding()) return
        val device = selectedDevice ?: return
        binding.title.text = device.name
        binding.btnRefresh.isVisible = false
        binding.btnMore.isVisible = true
        binding.scanContainer.isVisible = false
        binding.remoteContainer.isVisible = true
        binding.connectionText.text = if (isReconnecting) {
            getString(R.string.text_reconnecting)
        } else {
            getString(R.string.text_connected_to_tv, device.name)
        }
        binding.commandStatusText.text = getString(R.string.text_remote_ready)
        setRemoteEnabled(!isReconnecting && isConnected)
    }

    private fun setRemoteEnabled(enabled: Boolean) {
        if (!canUseBinding()) return
        val controls = listOf(
            binding.btnPower,
            binding.btnInput,
            binding.btnSettings,
            binding.btnUp,
            binding.btnDown,
            binding.btnLeft,
            binding.btnRight,
            binding.btnOk,
            binding.btnBackRemote,
            binding.btnHome,
            binding.btnMenu,
            binding.btnVolUp,
            binding.btnVolDown,
            binding.btnMute,
            binding.btnChannelUp,
            binding.btnChannelDown,
            binding.btnRewind,
            binding.btnPlayPause,
            binding.btnForwardMedia,
            binding.btnKeyboard,
            binding.btnApps,
            binding.btnTouchpad
        )
        controls.forEach {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else 0.45f
        }
    }

    private fun sendRemoteKey(key: TvRemoteKey) {
        if (!isConnected || selectedDevice == null) {
            showConnectionLostDialog()
            return
        }
        if (isReconnecting) return

        val now = System.currentTimeMillis()
        if (now - lastCommandAt < COMMAND_DEBOUNCE_MS) return
        lastCommandAt = now

        binding.root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                controller.sendKey(key)
            }.onSuccess {
                binding.commandStatusText.text = getString(R.string.text_sent_remote_command, key.label)
            }.onFailure {
                handleCommandError(it)
            }
        }
    }

    private fun showPowerDialog() {
        val device = selectedDevice ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.text_turn_off_tv_title, device.name))
            .setMessage(R.string.text_turn_off_tv_message)
            .setNegativeButton(R.string.text_cancel, null)
            .setPositiveButton(R.string.text_turn_off) { _, _ -> sendRemoteKey(TvRemoteKey.TvPower) }
            .show()
    }

    private fun showKeyboardDialog() {
        if (!ensureConnected()) return
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            hint = getString(R.string.text_type_to_tv_hint)
            setSingleLine(false)
            minLines = 2
            setPadding(32, 16, 32, 16)
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.text_keyboard)
            .setView(input)
            .setNegativeButton(R.string.text_cancel, null)
            .setPositiveButton(R.string.text_send, null)
            .show()

        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) {
                viewLifecycleOwner.lifecycleScope.launch {
                    runCatching {
                        controller.sendText(text)
                    }.onSuccess {
                        dialog.dismiss()
                        hideKeyboard(input)
                        binding.commandStatusText.text = getString(R.string.text_sent_text_to_tv)
                    }.onFailure {
                        input.error = it.message ?: getString(R.string.text_something_went_wrong)
                    }
                }
            }
        }
        input.requestFocus()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }

    private fun showAppsDialog() {
        if (!ensureConnected()) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.text_apps)
            .setItems(appShortcuts.map { it.label }.toTypedArray()) { _, which ->
                val app = appShortcuts[which]
                viewLifecycleOwner.lifecycleScope.launch {
                    runCatching {
                        controller.launchApp(app.packageName)
                    }.onSuccess {
                        binding.commandStatusText.text = getString(R.string.text_opening_tv_app, app.label)
                    }.onFailure {
                        handleCommandError(it)
                    }
                }
            }
            .show()
    }

    private fun showInputDialog() {
        if (!ensureConnected()) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.text_select_input)
            .setItems(inputLabels) { _, which ->
                val inputName = inputLabels[which]
                sendRemoteKey(inputSources[which])
                binding.commandStatusText.text = getString(R.string.text_selected_input, inputName)
            }
            .show()
    }

    private fun showTouchpadDialog() {
        if (!ensureConnected()) return
        val touchPad = View(requireContext()).apply {
            setBackgroundResource(R.drawable.bg_mirroring_status)
            minimumHeight = (180 * resources.displayMetrics.density).toInt()
            var downX = 0f
            var downY = 0f
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                        true
                    }

                    MotionEvent.ACTION_UP -> {
                        val dx = event.x - downX
                        val dy = event.y - downY
                        val key = when {
                            abs(dx) < TOUCH_TAP_SLOP && abs(dy) < TOUCH_TAP_SLOP -> TvRemoteKey.Enter
                            abs(dx) > abs(dy) && dx > 0 -> TvRemoteKey.Right
                            abs(dx) > abs(dy) -> TvRemoteKey.Left
                            dy > 0 -> TvRemoteKey.Down
                            else -> TvRemoteKey.Up
                        }
                        view.performClick()
                        sendRemoteKey(key)
                        true
                    }

                    else -> true
                }
            }
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.text_touchpad)
            .setMessage(R.string.text_touchpad_message)
            .setView(touchPad)
            .setPositiveButton(R.string.text_ok, null)
            .show()
    }

    private fun showDeviceMenu() {
        val device = selectedDevice ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(device.name)
            .setItems(
                arrayOf(
                    getString(R.string.text_device_information),
                    getString(R.string.text_reconnect),
                    getString(R.string.text_disconnect),
                    getString(R.string.text_forget_device)
                )
            ) { _, which ->
                when (which) {
                    0 -> showDeviceInfo(device)
                    1 -> reconnect()
                    2 -> disconnect()
                    3 -> showForgetDeviceDialog(device)
                }
            }
            .show()
    }

    private fun showDeviceInfo(device: TvRemoteDevice) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.text_device_information)
            .setMessage("${device.name}\n${device.type}\n${device.host}:${device.remotePort}")
            .setPositiveButton(R.string.text_ok, null)
            .show()
    }

    private fun reconnect() {
        val device = selectedDevice ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                isReconnecting = true
                binding.connectionText.text = getString(R.string.text_reconnecting)
                binding.commandStatusText.text = getString(R.string.text_remote_locked_reconnecting)
                setRemoteEnabled(false)
                controller.connect(device)
            } catch (error: Throwable) {
                showConnectionError(error)
            }
        }
    }

    private fun disconnect() {
        controller.disconnect()
        isConnected = false
        isReconnecting = false
        renderDisconnected()
    }

    private fun showForgetDeviceDialog(device: TvRemoteDevice) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.text_forget_tv_title, device.name))
            .setMessage(R.string.text_forget_tv_message)
            .setNegativeButton(R.string.text_cancel, null)
            .setPositiveButton(R.string.text_forget) { _, _ -> startScan() }
            .show()
    }

    private fun showConnectionLostDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.text_connection_lost)
            .setMessage(R.string.text_connection_lost_tv_remote_message)
            .setNegativeButton(R.string.text_cancel, null)
            .setPositiveButton(R.string.text_reconnect) { _, _ -> reconnect() }
            .show()
    }

    private fun ensureConnected(): Boolean {
        if (isConnected && selectedDevice != null && !isReconnecting) return true
        showConnectionLostDialog()
        return false
    }

    private fun showConnectionError(error: Throwable) {
        if (!canUseBinding()) return
        Toast.makeText(
            requireContext(),
            error.message ?: getString(R.string.text_could_not_connect_tv),
            Toast.LENGTH_LONG
        ).show()
        Log.d("renderState", "renderState: ${error.message}")

        isConnected = false
        isReconnecting = false
        renderDisconnected()
    }

    private fun handleCommandError(error: Throwable) {
        if (!canUseBinding()) return
        binding.commandStatusText.text = error.message ?: getString(R.string.text_connection_lost)
        if (error is TvRemoteException) {
            showConnectionLostDialog()
        }
    }

    private fun hasNearbyWifiPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.NEARBY_WIFI_DEVICES
                ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestNearbyWifiPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            nearbyWifiPermissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }

    private fun hideKeyboard(view: View) {
        requireContext().getSystemService<InputMethodManager>()
            ?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun handleBackPressed() {
        if (isConnected) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.text_disconnect)
                .setMessage(R.string.text_disconnect_tv_remote_message)
                .setNegativeButton(R.string.text_cancel, null)
                .setPositiveButton(R.string.text_disconnect) { _, _ ->
                    disconnect()
                    popBackStack()
                }
                .show()
        } else {
            popBackStack()
        }
    }

    private fun canUseBinding(): Boolean {
        return viewActive && _binding != null && view != null && isAdded
    }

    companion object {
        private const val SCAN_TIMEOUT_MS = 12_000L
        private const val COMMAND_DEBOUNCE_MS = 180L
        private const val PAIRING_CODE_LENGTH = 6
        private const val TOUCH_TAP_SLOP = 32
        private val PAIRING_CODE_REGEX = Regex("^[0-9A-Fa-f]{6}$")
    }
}
