package com.tvchromecast.screenmirroringplus.tvremote

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

internal class WebOsTvRemoteSession(
    context: Context,
    private val device: TvRemoteDevice
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val client = buildUnsafeWebSocketClient()
    private val pendingResponses = ConcurrentHashMap<String, PendingResponse>()
    private var commandSocket: WebSocket? = null
    private var pointerSocket: WebSocket? = null

    fun start() {
        connectCommandSocket()
        registerClient()
    }

    fun sendKey(key: TvRemoteKey) {
        when (key) {
            TvRemoteKey.Power,
            TvRemoteKey.TvPower -> request("ssap://system/turnOff")
            else -> sendPointerButton(
                key.toWebOsButton()
                    ?: throw TvRemoteException("${key.label} is not supported by LG webOS remote.")
            )
        }
    }

    fun sendText(text: String) {
        val socket = ensurePointerSocket()
        text.forEach { char ->
            when (char) {
                '\n', '\r' -> sendPointerButton("ENTER")
                else -> socket.send("type:text\ntext:$char\n\n")
            }
        }
    }

    fun launchApp(packageNameOrDeepLink: String) {
        val normalized = packageNameOrDeepLink.lowercase()
        val appId = when {
            normalized.contains("youtube") -> "youtube.leanback.v4"
            normalized.contains("netflix") -> "netflix"
            normalized.contains("prime") || normalized.contains("amazon") -> "amazon"
            normalized.contains("spotify") -> "spotify-beehive"
            normalized.contains("settings") -> "com.palm.app.settings"
            else -> throw TvRemoteException("This LG webOS app shortcut is not mapped yet.")
        }
        request(
            uri = "ssap://system.launcher/launch",
            payload = JSONObject().put("id", appId)
        )
    }

    fun close() {
        pendingResponses.values.forEach { it.latch.countDown() }
        pendingResponses.clear()
        pointerSocket?.close(1000, "closed")
        commandSocket?.close(1000, "closed")
        pointerSocket = null
        commandSocket = null
    }

    private fun connectCommandSocket() {
        val failures = mutableListOf<Throwable>()
        commandSocketUrls().forEach { url ->
            runCatching {
                connectCommandSocket(url)
            }.onSuccess {
                return
            }.onFailure { failure ->
                failures += failure
            }
        }
        throw TvRemoteException(
            "Could not connect to LG webOS TV. Make sure the TV is on and approve the pairing prompt.",
            failures.lastOrNull()
        )
    }

    private fun connectCommandSocket(url: String) {
        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        var opened = false
        val socket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened = true
                    latch.countDown()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = runCatching { JSONObject(text) }.getOrNull() ?: return
                    val id = message.optString("id").takeIf { it.isNotBlank() } ?: return
                    pendingResponses.remove(id)?.let { pending ->
                        pending.response = message
                        pending.latch.countDown()
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    failure = t
                    latch.countDown()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!opened) {
                        failure = TvRemoteException(reason.ifBlank { "LG webOS remote socket closed." })
                        latch.countDown()
                    }
                }
            }
        )

        if (!latch.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            socket.cancel()
            throw TvRemoteException("LG webOS remote connection timed out.")
        }
        failure?.let {
            socket.cancel()
            throw it
        }
        commandSocket = socket
    }

    private fun commandSocketUrls(): List<String> {
        val urls = linkedSetOf<String>()
        when (device.remotePort) {
            WEBOS_INSECURE_PORT -> urls += "ws://${device.host}:$WEBOS_INSECURE_PORT"
            WEBOS_COMMAND_PORT -> urls += "wss://${device.host}:$WEBOS_COMMAND_PORT"
            in 1..Int.MAX_VALUE -> urls += "wss://${device.host}:${device.remotePort}"
        }
        urls += "wss://${device.host}:$WEBOS_COMMAND_PORT"
        urls += "ws://${device.host}:$WEBOS_INSECURE_PORT"
        return urls.toList()
    }

    private fun registerClient() {
        val manifest = JSONObject()
            .put("manifestVersion", 1)
            .put("appVersion", "1.0")
            .put("signed", JSONObject().put("created", ""))
            .put(
                "permissions",
                listOf(
                    "LAUNCH",
                    "LAUNCH_WEBAPP",
                    "CONTROL_AUDIO",
                    "CONTROL_DISPLAY",
                    "CONTROL_INPUT_MEDIA_PLAYBACK",
                    "CONTROL_INPUT_TEXT",
                    "CONTROL_MOUSE_AND_KEYBOARD",
                    "CONTROL_POWER",
                    "READ_INSTALLED_APPS"
                )
            )

        val payload = JSONObject()
            .put("pairingType", "PROMPT")
            .put("forcePairing", false)
            .put("manifest", manifest)
        savedClientKey()?.let { payload.put("client-key", it) }

        val response = sendAndAwait(
            JSONObject()
                .put("type", "register")
                .put("payload", payload),
            REGISTER_TIMEOUT_SECONDS
        )
        val type = response.optString("type")
        if (type != "registered") {
            throw TvRemoteException(
                "LG webOS pairing was not accepted. Approve the prompt on the TV and try again."
            )
        }
        response.optJSONObject("payload")
            ?.optString("client-key")
            ?.takeIf { it.isNotBlank() }
            ?.let(::saveClientKey)
    }

    private fun request(uri: String, payload: JSONObject = JSONObject()): JSONObject {
        return sendAndAwait(
            JSONObject()
                .put("type", "request")
                .put("uri", uri)
                .put("payload", payload),
            REQUEST_TIMEOUT_SECONDS
        )
    }

    private fun sendAndAwait(message: JSONObject, timeoutSeconds: Long): JSONObject {
        val socket = commandSocket ?: throw TvRemoteException("LG webOS TV is not connected")
        val id = message.optString("id").takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
        val pending = PendingResponse()
        pendingResponses[id] = pending
        message.put("id", id)

        if (!socket.send(message.toString())) {
            pendingResponses.remove(id)
            throw TvRemoteException("Could not send LG webOS remote command.")
        }

        if (!pending.latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
            pendingResponses.remove(id)
            throw TvRemoteException("LG webOS TV did not respond.")
        }
        val response = pending.response ?: throw TvRemoteException("LG webOS TV returned an empty response.")
        if (response.optString("type") == "error") {
            throw TvRemoteException(response.optString("error", "LG webOS command failed."))
        }
        return response
    }

    private fun ensurePointerSocket(): WebSocket {
        pointerSocket?.let { return it }
        val response = request("ssap://com.webos.service.networkinput/getPointerInputSocket")
        val socketPath = response.optJSONObject("payload")
            ?.optString("socketPath")
            ?.takeIf { it.isNotBlank() }
            ?: throw TvRemoteException("LG webOS pointer input is unavailable.")

        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        var opened = false
        val socket = client.newWebSocket(
            Request.Builder().url(socketPath).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened = true
                    latch.countDown()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    failure = t
                    latch.countDown()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!opened) {
                        failure = TvRemoteException(reason.ifBlank { "LG webOS pointer socket closed." })
                        latch.countDown()
                    }
                }
            }
        )

        if (!latch.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            socket.cancel()
            throw TvRemoteException("LG webOS pointer connection timed out.")
        }
        failure?.let {
            socket.cancel()
            throw it
        }
        pointerSocket = socket
        return socket
    }

    private fun sendPointerButton(button: String) {
        if (!ensurePointerSocket().send("type:button\nname:$button\n\n")) {
            throw TvRemoteException("Could not send LG webOS remote key.")
        }
    }

    private fun savedClientKey(): String? = prefs.getString(clientKeyName(), null)

    private fun saveClientKey(clientKey: String) {
        prefs.edit().putString(clientKeyName(), clientKey).apply()
    }

    private fun clientKeyName(): String = "webos_client_key_${device.host}"

    private fun TvRemoteKey.toWebOsButton(): String? {
        return when (this) {
            TvRemoteKey.Input -> "INPUT"
            TvRemoteKey.Settings -> "MENU"
            TvRemoteKey.Up -> "UP"
            TvRemoteKey.Down -> "DOWN"
            TvRemoteKey.Left -> "LEFT"
            TvRemoteKey.Right -> "RIGHT"
            TvRemoteKey.Enter -> "ENTER"
            TvRemoteKey.Back -> "BACK"
            TvRemoteKey.Home -> "HOME"
            TvRemoteKey.Menu -> "MENU"
            TvRemoteKey.VolumeUp -> "VOLUMEUP"
            TvRemoteKey.VolumeDown -> "VOLUMEDOWN"
            TvRemoteKey.Mute -> "MUTE"
            TvRemoteKey.ChannelUp -> "CHANNELUP"
            TvRemoteKey.ChannelDown -> "CHANNELDOWN"
            TvRemoteKey.Rewind -> "REWIND"
            TvRemoteKey.PlayPause -> "PLAY"
            TvRemoteKey.Forward -> "FASTFORWARD"
            TvRemoteKey.Power,
            TvRemoteKey.TvPower -> null
        }
    }

    private class PendingResponse {
        val latch = CountDownLatch(1)
        @Volatile
        var response: JSONObject? = null
    }

    companion object {
        private const val PREFS_NAME = "webos_tv_remote"
        private const val WEBOS_INSECURE_PORT = 3000
        private const val WEBOS_COMMAND_PORT = 3001
        private const val CONNECT_TIMEOUT_SECONDS = 8L
        private const val REGISTER_TIMEOUT_SECONDS = 30L
        private const val REQUEST_TIMEOUT_SECONDS = 8L

        private fun buildUnsafeWebSocketClient(): OkHttpClient {
            val trustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
            }
            return OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
        }
    }
}
