package com.tvchromecast.screenmirroringplus.tvremote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.EOFException
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

internal class AndroidTvPairingSession(
    private val socket: SSLSocket,
    private val clientCertificate: X509Certificate
) {
    private val input = socket.inputStream
    private val output = socket.outputStream
    private val serverCertificate = socket.session.peerCertificates.first() as X509Certificate

    suspend fun start(clientName: String) = withContext(Dispatchers.IO) {
        ProtobufCodec.writeFrame(output, AndroidTvProtocolCodec.pairingRequest(clientName))
        while (true) {
            when (val message = AndroidTvProtocolCodec.parsePairingMessage(ProtobufCodec.readFrame(input))) {
                PairingMessageType.RequestAck -> {
                    ProtobufCodec.writeFrame(output, AndroidTvProtocolCodec.pairingOptions())
                }

                PairingMessageType.Options -> {
                    ProtobufCodec.writeFrame(output, AndroidTvProtocolCodec.pairingConfiguration())
                }

                PairingMessageType.ConfigurationAck -> return@withContext
                is PairingMessageType.Error -> throw TvRemoteException("Pairing failed with status ${message.status}")
                PairingMessageType.SecretAck,
                PairingMessageType.Unknown -> Unit
            }
        }
    }

    suspend fun finish(pairingCode: String) = withContext(Dispatchers.IO) {
        val secret = AndroidTvProtocolCodec.pairingSecret(
            pairingCode = pairingCode,
            clientCertificate = clientCertificate,
            serverCertificate = serverCertificate
        )
        ProtobufCodec.writeFrame(output, secret)
        while (true) {
            when (val message = AndroidTvProtocolCodec.parsePairingMessage(ProtobufCodec.readFrame(input))) {
                PairingMessageType.SecretAck -> return@withContext
                is PairingMessageType.Error -> throw TvRemoteException("Pairing failed with status ${message.status}")
                else -> Unit
            }
        }
    }

    fun close() {
        runCatching { socket.close() }
    }
}

internal class AndroidTvRemoteSession(
    private val socket: SSLSocket,
    private val onConnectionLost: (Throwable?) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val input = socket.inputStream
    private val output = socket.outputStream
    private val started = CompletableDeferred<Unit>()
    private var readerJob: Job? = null
    private var imeCounter = 0
    private var fieldCounter = 0

    suspend fun start() {
        readerJob = scope.launch { readLoop() }
        withTimeout(REMOTE_START_TIMEOUT_MS) { started.await() }
    }

    suspend fun sendKey(key: TvRemoteKey) = write(AndroidTvProtocolCodec.remoteKey(key))

    suspend fun sendText(text: String) = write(
        AndroidTvProtocolCodec.remoteText(
            text = text,
            imeCounter = imeCounter,
            fieldCounter = fieldCounter
        )
    )

    suspend fun launchApp(packageNameOrDeepLink: String) = write(
        AndroidTvProtocolCodec.remoteLaunchApp(packageNameOrDeepLink)
    )

    fun close() {
        readerJob?.cancel()
        scope.cancel()
        runCatching { socket.close() }
    }

    private suspend fun write(message: ByteArray) = withContext(Dispatchers.IO) {
        synchronized(output) {
            ProtobufCodec.writeFrame(output, message)
        }
    }

    private fun readLoop() {
        try {
            while (!socket.isClosed) {
                when (val message = AndroidTvProtocolCodec.parseRemoteMessage(ProtobufCodec.readFrame(input))) {
                    RemoteIncomingMessage.Configure -> {
                        ProtobufCodec.writeFrame(output, AndroidTvProtocolCodec.remoteConfigureResponse())
                    }

                    RemoteIncomingMessage.SetActive -> {
                        ProtobufCodec.writeFrame(output, AndroidTvProtocolCodec.remoteSetActiveResponse())
                    }

                    is RemoteIncomingMessage.Ping -> {
                        ProtobufCodec.writeFrame(output, AndroidTvProtocolCodec.remotePingResponse(message.value))
                    }

                    RemoteIncomingMessage.Started -> {
                        if (!started.isCompleted) started.complete(Unit)
                    }

                    is RemoteIncomingMessage.ImeCounters -> {
                        imeCounter = message.imeCounter
                        fieldCounter = message.fieldCounter
                    }

                    RemoteIncomingMessage.Unknown -> Unit
                }
            }
        } catch (error: Throwable) {
            if (error !is EOFException && !socket.isClosed) {
                onConnectionLost(error)
            } else if (!socket.isClosed) {
                onConnectionLost(error)
            }
            if (!started.isCompleted) started.completeExceptionally(error)
        }
    }

    companion object {
        private const val REMOTE_START_TIMEOUT_MS = 10_000L
    }
}

internal fun SSLSocketFactory.createAndroidTvSocket(host: String, port: Int): SSLSocket {
    val socket = createSocket() as SSLSocket
    socket.useClientMode = true
    socket.supportedProtocols
        .filter { it == "TLSv1.2" }
        .takeIf { it.isNotEmpty() }
        ?.let { socket.enabledProtocols = it.toTypedArray() }
    socket.soTimeout = 0
    socket.connect(InetSocketAddress(host, port), 8_000)
    socket.startHandshake()
    return socket
}
