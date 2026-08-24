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
    private var rokuSession: RokuTvRemoteSession? = null
    private var samsungSession: SamsungTvRemoteSession? = null
    private var webOsSession: WebOsTvRemoteSession? = null
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
        runCatching { rokuSession?.close() }
        runCatching { samsungSession?.close() }
        runCatching { webOsSession?.close() }
        remoteSession = null
        rokuSession = null
        samsungSession = null
        webOsSession = null
        when (device.protocol) {
            TvRemoteProtocol.AndroidTv -> connectAndroidTv(device)
            TvRemoteProtocol.Roku -> connectRoku(device)
            TvRemoteProtocol.Samsung -> connectSamsung(device)
            TvRemoteProtocol.WebOs -> connectWebOs(device)
            TvRemoteProtocol.FireTv -> throw TvRemoteException(
                "${device.type} was found on the network, but this remote adapter needs a separate pairing implementation."
            )
            TvRemoteProtocol.CastOrDlna -> throw TvRemoteException(
                "${device.type} supports casting, but it does not support phone remote control. Choose an Android TV, Roku, Samsung TV, or LG webOS TV."
            )
        }
    }

    private suspend fun connectAndroidTv(device: TvRemoteDevice) {
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

    private fun connectRoku(device: TvRemoteDevice) {
        val session = RokuTvRemoteSession(device)
        session.start()
        rokuSession = session
        onStateChanged(TvRemoteConnectionState.Connected(device.name))
    }

    private fun connectSamsung(device: TvRemoteDevice) {
        val session = SamsungTvRemoteSession(appContext, device)
        session.start()
        samsungSession = session
        onStateChanged(TvRemoteConnectionState.Connected(device.name))
    }

    private fun connectWebOs(device: TvRemoteDevice) {
        val session = WebOsTvRemoteSession(appContext, device)
        session.start()
        webOsSession = session
        onStateChanged(TvRemoteConnectionState.Connected(device.name))
    }

    suspend fun startPairing(device: TvRemoteDevice) = withContext(Dispatchers.IO) {
        if (device.protocol != TvRemoteProtocol.AndroidTv) {
            throw TvRemoteException("${device.type} does not use Android TV pairing.")
        }
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
        samsungSession?.let {
            it.sendKey(key)
            return
        }
        webOsSession?.let {
            it.sendKey(key)
            return
        }
        rokuSession?.let {
            it.sendKey(key)
            return
        }
        remoteSessionOrThrow().sendKey(key)
    }

    suspend fun sendText(text: String) {
        samsungSession?.let {
            it.sendText(text)
            return
        }
        webOsSession?.let {
            it.sendText(text)
            return
        }
        rokuSession?.let {
            it.sendText(text)
            return
        }
        remoteSessionOrThrow().sendText(text)
    }

    suspend fun launchApp(packageNameOrDeepLink: String) {
        samsungSession?.let {
            it.launchApp(packageNameOrDeepLink)
            return
        }
        webOsSession?.let {
            it.launchApp(packageNameOrDeepLink)
            return
        }
        rokuSession?.let {
            it.launchApp(packageNameOrDeepLink)
            return
        }
        remoteSessionOrThrow().launchApp(packageNameOrDeepLink)
    }

    fun disconnect() {
        manualDisconnect = true
        runCatching { pairingSession?.close() }
        runCatching { remoteSession?.close() }
        runCatching { rokuSession?.close() }
        runCatching { samsungSession?.close() }
        runCatching { webOsSession?.close() }
        pairingSession = null
        remoteSession = null
        rokuSession = null
        samsungSession = null
        webOsSession = null
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
