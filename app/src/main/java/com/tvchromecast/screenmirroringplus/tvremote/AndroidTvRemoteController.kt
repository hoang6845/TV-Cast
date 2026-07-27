package com.tvchromecast.screenmirroringplus.tvremote

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

class AndroidTvRemoteController(
    context: Context,
    private val onDevicesChanged: (List<TvRemoteDevice>) -> Unit,
    private val onStateChanged: (TvRemoteConnectionState) -> Unit
) {
    private val appContext = context.applicationContext
    private val certificateStore = AndroidTvCertificateStore(appContext)
    private val discovery = AndroidTvDiscovery(
        context = appContext,
        onDevicesChanged = onDevicesChanged,
        onError = { onStateChanged(TvRemoteConnectionState.Error(it)) }
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pairingSession: AndroidTvPairingSession? = null
    private var remoteSession: AndroidTvRemoteSession? = null
    private var currentDevice: TvRemoteDevice? = null
    private var manualDisconnect = false

    fun startDiscovery() {
        onStateChanged(TvRemoteConnectionState.Searching)
        discovery.start()
    }

    fun stopDiscovery() {
        discovery.stop()
    }

    suspend fun connect(device: TvRemoteDevice) = withContext(Dispatchers.IO) {
        manualDisconnect = false
        currentDevice = device
        onStateChanged(TvRemoteConnectionState.Connecting(device.name))
        runCatching { remoteSession?.close() }
        remoteSession = null
        val ports = checkRemotePorts(device)
        if (!ports.remoteOpen && !ports.pairingOpen) {
            throw TvRemoteException(appContext.getString(com.tvchromecast.screenmirroringplus.R.string.text_tv_remote_service_unavailable))
        }
        if (!ports.remoteOpen && ports.pairingOpen) {
            throw TvRemotePairingRequiredException()
        }
        val socketFactory = certificateStore.sslContext().socketFactory
        val socket = try {
            socketFactory.createAndroidTvSocket(device.host, device.remotePort)
        } catch (error: Throwable) {
            throw TvRemotePairingRequiredException(cause = error)
        }
        val session = AndroidTvRemoteSession(socket) { error ->
            remoteSession = null
            if (manualDisconnect) {
                onStateChanged(TvRemoteConnectionState.Disconnected(error?.message))
            } else {
                scope.launch { reconnectAfterConnectionLoss(device, error) }
            }
        }
        remoteSession = session
        try {
            session.start()
            onStateChanged(TvRemoteConnectionState.Connected(device.name))
        } catch (error: Throwable) {
            session.close()
            remoteSession = null
            throw TvRemotePairingRequiredException(cause = error)
        }
    }

    suspend fun startPairing(device: TvRemoteDevice) = withContext(Dispatchers.IO) {
        currentDevice = device
        onStateChanged(TvRemoteConnectionState.Pairing(device.name))
        runCatching { pairingSession?.close() }
        pairingSession = null
        if (!isTcpPortOpen(device.host, device.pairPort)) {
            throw TvRemoteException(appContext.getString(com.tvchromecast.screenmirroringplus.R.string.text_tv_remote_pairing_unavailable))
        }
        val socket = certificateStore.sslContext().socketFactory
            .createAndroidTvSocket(device.host, device.pairPort)
        val session = AndroidTvPairingSession(socket, certificateStore.certificate())
        pairingSession = session
        session.start(appContext.getString(com.tvchromecast.screenmirroringplus.R.string.app_name))
    }

    suspend fun finishPairing(pairingCode: String) = withContext(Dispatchers.IO) {
        val session = pairingSession ?: throw TvRemoteException("Pairing session is not active")
        try {
            session.finish(pairingCode)
        } finally {
            session.close()
            pairingSession = null
        }
    }

    suspend fun reconnect() {
        val device = currentDevice ?: throw TvRemoteException("No TV selected")
        onStateChanged(TvRemoteConnectionState.Reconnecting(device.name))
        connect(device)
    }

    suspend fun sendKey(key: TvRemoteKey) {
        remoteSessionOrThrow().sendKey(key)
    }

    suspend fun sendText(text: String) {
        remoteSessionOrThrow().sendText(text)
    }

    suspend fun launchApp(packageNameOrDeepLink: String) {
        remoteSessionOrThrow().launchApp(packageNameOrDeepLink)
    }

    fun disconnect() {
        manualDisconnect = true
        runCatching { pairingSession?.close() }
        runCatching { remoteSession?.close() }
        pairingSession = null
        remoteSession = null
        val deviceName = currentDevice?.name
        currentDevice = null
        onStateChanged(TvRemoteConnectionState.Disconnected(deviceName?.let { "Disconnected from $it" }))
    }

    fun close() {
        stopDiscovery()
        disconnect()
        scope.cancel()
    }

    private fun remoteSessionOrThrow(): AndroidTvRemoteSession {
        return remoteSession ?: throw TvRemoteException("TV is not connected")
    }

    private fun checkRemotePorts(device: TvRemoteDevice): RemotePorts {
        return RemotePorts(
            remoteOpen = isTcpPortOpen(device.host, device.remotePort),
            pairingOpen = isTcpPortOpen(device.host, device.pairPort)
        )
    }

    private fun isTcpPortOpen(host: String, port: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), PORT_CHECK_TIMEOUT_MS)
            }
            true
        }.getOrDefault(false)
    }

    private suspend fun reconnectAfterConnectionLoss(device: TvRemoteDevice, error: Throwable?) {
        onStateChanged(TvRemoteConnectionState.Reconnecting(device.name))
        var delayMs = 1_000L
        repeat(AUTO_RECONNECT_ATTEMPTS) {
            if (manualDisconnect) return
            delay(delayMs)
            try {
                connect(device)
                return
            } catch (_: TvRemotePairingRequiredException) {
                onStateChanged(TvRemoteConnectionState.Error("Pairing expired. Pair with ${device.name} again."))
                return
            } catch (_: Throwable) {
                delayMs *= 2
            }
        }
        onStateChanged(TvRemoteConnectionState.Disconnected(error?.message ?: "Connection lost"))
    }

    companion object {
        private const val AUTO_RECONNECT_ATTEMPTS = 3
        private const val PORT_CHECK_TIMEOUT_MS = 1_000
    }

    private data class RemotePorts(
        val remoteOpen: Boolean,
        val pairingOpen: Boolean
    )
}
