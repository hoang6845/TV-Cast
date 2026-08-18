package com.tvchromecast.screenmirroringplus.tvremote

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Controller for managing Bluetooth connections to Android TVs
 */
class BluetoothTvRemoteController(
    private val context: Context,
    private val onDevicesChanged: (List<BluetoothTvDevice>) -> Unit,
    private val onStateChanged: (BluetoothConnectionState) -> Unit
) {
    private val bluetoothManager: BluetoothManager? by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }
    private val bluetoothAdapter: BluetoothAdapter? by lazy { 
        bluetoothManager?.adapter
    }
    
    private val discoveredDevices = mutableListOf<BluetoothTvDevice>()
    private var bluetoothSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var isDiscovering = false

    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = 
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                    
                    device?.let {
                        if (!hasBluetoothPermission()) return
                        
                        val deviceName = it.name ?: "Unknown Device"
                        val deviceAddress = it.address
                        
                        // Filter for TV-like devices
                        if (deviceName.contains("TV", ignoreCase = true) ||
                            deviceName.contains("Android", ignoreCase = true) ||
                            deviceName.contains("Google", ignoreCase = true)) {
                            
                            val tvDevice = BluetoothTvDevice(
                                name = deviceName,
                                address = deviceAddress,
                                device = it
                            )
                            
                            if (!discoveredDevices.any { d -> d.address == deviceAddress }) {
                                discoveredDevices.add(tvDevice)
                                onDevicesChanged(discoveredDevices.toList())
                            }
                        }
                    }
                }
                
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    isDiscovering = false
                    if (discoveredDevices.isEmpty()) {
                        onStateChanged(BluetoothConnectionState.NoDevicesFound)
                    }
                }
            }
        }
    }

    /**
     * Check if Bluetooth is available on this device
     */
    fun isBluetoothAvailable(): Boolean {
        // Check if device has Bluetooth hardware
        val hasBluetooth = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
        Log.d("BluetoothController", "isBluetoothAvailable: hasBluetooth=$hasBluetooth, adapter=${bluetoothAdapter != null}")
        return hasBluetooth && bluetoothAdapter != null
    }

    /**
     * Check if Bluetooth is currently enabled
     */
    @SuppressLint("MissingPermission")
    fun isBluetoothEnabled(): Boolean {
        if (!hasBluetoothPermission()) return false
        return bluetoothAdapter?.isEnabled == true
    }

    /**
     * Enable Bluetooth programmatically (for Android 12 and below)
     * For Android 13+, must use Intent to request enable
     */
    @SuppressLint("MissingPermission")
    fun enableBluetooth(): Boolean {
        if (!hasBluetoothPermission() || bluetoothAdapter == null) {
            return false
        }
        
        // For Android 13+, this requires user interaction via Intent
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            try {
                @Suppress("DEPRECATION")
                bluetoothAdapter.enable()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enable Bluetooth", e)
                false
            }
        } else {
            // Cannot enable programmatically on Android 13+
            // Must use ACTION_REQUEST_ENABLE intent
            false
        }
    }

    /**
     * Check if app has required Bluetooth permissions
     */
    fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_ADMIN
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Get list of already paired Bluetooth devices
     */
    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothTvDevice> {
        if (!hasBluetoothPermission() || bluetoothAdapter == null) {
            return emptyList()
        }

        return try {
            bluetoothAdapter.bondedDevices
                ?.filter { device ->
                    val name = device.name ?: ""
                    name.contains("TV", ignoreCase = true) ||
                    name.contains("Android", ignoreCase = true) ||
                    name.contains("Google", ignoreCase = true)
                }
                ?.map { device ->
                    BluetoothTvDevice(
                        name = device.name ?: "Unknown Device",
                        address = device.address,
                        device = device,
                        isPaired = true
                    )
                } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting paired devices", e)
            emptyList()
        }
    }

    /**
     * Start discovering nearby Bluetooth devices
     */
    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        if (!hasBluetoothPermission()) {
            onStateChanged(BluetoothConnectionState.PermissionRequired)
            return
        }

        if (bluetoothAdapter == null) {
            onStateChanged(BluetoothConnectionState.BluetoothNotAvailable)
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            onStateChanged(BluetoothConnectionState.BluetoothDisabled)
            return
        }

        discoveredDevices.clear()
        
        // Add paired devices first
        discoveredDevices.addAll(getPairedDevices())
        onDevicesChanged(discoveredDevices.toList())

        // Register receiver for discovery
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        context.registerReceiver(discoveryReceiver, filter)

        // Cancel any ongoing discovery
        if (bluetoothAdapter.isDiscovering) {
            bluetoothAdapter.cancelDiscovery()
        }

        // Start discovery
        isDiscovering = bluetoothAdapter.startDiscovery()
        onStateChanged(BluetoothConnectionState.Scanning)
    }

    /**
     * Stop Bluetooth discovery
     */
    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        if (!hasBluetoothPermission() || bluetoothAdapter == null) return

        try {
            if (bluetoothAdapter.isDiscovering) {
                bluetoothAdapter.cancelDiscovery()
            }
            context.unregisterReceiver(discoveryReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping discovery", e)
        }
        isDiscovering = false
    }

    /**
     * Connect to a Bluetooth device
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothTvDevice) = withContext(Dispatchers.IO) {
        if (!hasBluetoothPermission()) {
            onStateChanged(BluetoothConnectionState.Error("Bluetooth permission not granted"))
            return@withContext
        }

        try {
            stopDiscovery()
            onStateChanged(BluetoothConnectionState.Connecting(device.name))

            // Create socket using SPP UUID (Serial Port Profile)
            val socket = device.device.createRfcommSocketToServiceRecord(SPP_UUID)
            
            bluetoothSocket = socket
            socket.connect()

            inputStream = socket.inputStream
            outputStream = socket.outputStream

            withContext(Dispatchers.Main) {
                onStateChanged(BluetoothConnectionState.Connected(device.name))
            }
        } catch (e: IOException) {
            Log.e(TAG, "Connection failed", e)
            disconnect()
            withContext(Dispatchers.Main) {
                onStateChanged(BluetoothConnectionState.Error("Connection failed: ${e.message}"))
            }
        }
    }

    /**
     * Disconnect from the current Bluetooth device
     */
    fun disconnect() {
        try {
            inputStream?.close()
            outputStream?.close()
            bluetoothSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing Bluetooth connection", e)
        } finally {
            inputStream = null
            outputStream = null
            bluetoothSocket = null
            onStateChanged(BluetoothConnectionState.Disconnected)
        }
    }

    /**
     * Send a remote key command via Bluetooth
     */
    suspend fun sendKey(key: TvRemoteKey) = withContext(Dispatchers.IO) {
        val output = outputStream
        if (output == null || bluetoothSocket?.isConnected != true) {
            throw IOException("Not connected to device")
        }

        try {
            // Send key code as bytes
            // This is a simplified implementation - actual protocol may vary by TV
            val keyCode = key.androidKeyCode.toByte()
            output.write(byteArrayOf(CMD_SEND_KEY, keyCode))
            output.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Error sending key", e)
            withContext(Dispatchers.Main) {
                onStateChanged(BluetoothConnectionState.Error("Failed to send command"))
            }
            throw e
        }
    }

    /**
     * Send text via Bluetooth
     */
    suspend fun sendText(text: String) = withContext(Dispatchers.IO) {
        val output = outputStream
        if (output == null || bluetoothSocket?.isConnected != true) {
            throw IOException("Not connected to device")
        }

        try {
            val textBytes = text.toByteArray(Charsets.UTF_8)

            output.write(byteArrayOf(CMD_SEND_TEXT))
            output.write(textBytes.size)
            output.write(textBytes)
            output.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Error sending text", e)
            throw e
        }
    }

    /**
     * Check if currently connected
     */
    fun isConnected(): Boolean {
        return bluetoothSocket?.isConnected == true
    }

    /**
     * Clean up resources
     */
    fun close() {
        stopDiscovery()
        disconnect()
    }

    companion object {
        private const val TAG = "BluetoothTvRemote"
        
        // Standard Serial Port Profile UUID for Bluetooth
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        
        // Command bytes (these would need to match the TV's protocol)
        private const val CMD_SEND_KEY: Byte = 0x01
        private const val CMD_SEND_TEXT: Byte = 0x02
    }
}

/**
 * Represents a Bluetooth TV device
 */
data class BluetoothTvDevice(
    val name: String,
    val address: String,
    val device: BluetoothDevice,
    val isPaired: Boolean = false
)

/**
 * Bluetooth connection states
 */
sealed class BluetoothConnectionState {
    object Idle : BluetoothConnectionState()
    object Scanning : BluetoothConnectionState()
    object PermissionRequired : BluetoothConnectionState()
    object BluetoothNotAvailable : BluetoothConnectionState()
    object BluetoothDisabled : BluetoothConnectionState()
    object NoDevicesFound : BluetoothConnectionState()
    data class Connecting(val deviceName: String) : BluetoothConnectionState()
    data class Connected(val deviceName: String) : BluetoothConnectionState()
    object Disconnected : BluetoothConnectionState()
    data class Error(val message: String) : BluetoothConnectionState()
}
