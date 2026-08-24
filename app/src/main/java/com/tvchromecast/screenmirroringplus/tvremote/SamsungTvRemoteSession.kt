package com.tvchromecast.screenmirroringplus.tvremote

import android.content.Context
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

internal class SamsungTvRemoteSession(
    context: Context,
    private val device: TvRemoteDevice
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val client = buildUnsafeWebSocketClient()
    private var webSocket: WebSocket? = null

    fun start() {
        val errors = mutableListOf<Throwable>()
        val secureUrl = buildSocketUrl(secure = true, token = savedToken())
        runCatching { connect(secureUrl) }
            .onSuccess { return }
            .onFailure { errors += it }

        val insecureUrl = buildSocketUrl(secure = false, token = savedToken())
        runCatching { connect(insecureUrl) }
            .onSuccess { return }
            .onFailure { errors += it }

        throw TvRemoteException(
            "Could not connect to Samsung TV remote. Accept the pairing prompt on the TV and try again.",
            errors.lastOrNull()
        )
    }

    fun sendKey(key: TvRemoteKey) {
        val samsungKey = key.toSamsungKey()
            ?: throw TvRemoteException("${key.label} is not supported by Samsung TV remote.")
        sendSamsungKey(samsungKey)
    }

    fun sendText(text: String) {
        text.forEach { char ->
            when (char) {
                '\n', '\r' -> sendSamsungKey("KEY_ENTER")
                ' ' -> sendSamsungKey("KEY_SPACE")
                else -> sendSamsungKey("KEY_${char.uppercaseChar()}")
            }
        }
    }

    fun launchApp(appId: String) {
        val normalized = appId.lowercase()
        val samsungAppId = when {
            normalized.contains("youtube") -> "111299001912"
            normalized.contains("netflix") -> "11101200001"
            normalized.contains("prime") || normalized.contains("amazon") -> "3201512006785"
            normalized.contains("hulu") -> "3201601007625"
            normalized.contains("disney") -> "3201901017640"
            else -> throw TvRemoteException("This Samsung app shortcut is not mapped yet.")
        }
        sendJson(
            JSONObject()
                .put("method", "ms.channel.emit")
                .put(
                    "params",
                    JSONObject()
                        .put("event", "ed.apps.launch")
                        .put("to", "host")
                        .put(
                            "data",
                            JSONObject()
                                .put("appId", samsungAppId)
                                .put("action_type", "NATIVE_LAUNCH")
                        )
                )
        )
    }

    fun close() {
        webSocket?.close(1000, "closed")
        webSocket = null
    }

    private fun connect(url: String) {
        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        var connected = false
        val socket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = Unit

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val event = runCatching { JSONObject(text).optString("event") }.getOrNull()
                    when (event) {
                        "ms.channel.connect" -> {
                            connected = true
                            runCatching {
                                JSONObject(text)
                                    .optJSONObject("data")
                                    ?.optString("token")
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let(::saveToken)
                            }
                            latch.countDown()
                        }

                        "ms.channel.unauthorized" -> {
                            failure = TvRemoteException(
                                "Samsung TV rejected the remote connection. Remove the app from TV allowed devices and pair again."
                            )
                            clearToken()
                            latch.countDown()
                        }

                        "ms.channel.timeOut" -> {
                            failure = TvRemoteException("Samsung TV pairing timed out.")
                            latch.countDown()
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    failure = t
                    latch.countDown()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!connected) {
                        failure = TvRemoteException(reason.ifBlank { "Samsung TV remote socket closed." })
                        latch.countDown()
                    }
                }
            }
        )

        if (!latch.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            socket.cancel()
            throw TvRemoteException("Samsung TV remote connection timed out.")
        }
        failure?.let {
            socket.cancel()
            throw it
        }
        if (!connected) {
            socket.cancel()
            throw TvRemoteException("Samsung TV did not accept the remote connection.")
        }
        webSocket = socket
    }

    private fun sendSamsungKey(key: String) {
        sendJson(
            JSONObject()
                .put("method", "ms.remote.control")
                .put(
                    "params",
                    JSONObject()
                        .put("Cmd", "Click")
                        .put("DataOfCmd", key)
                        .put("Option", "false")
                        .put("TypeOfRemote", "SendRemoteKey")
                )
        )
    }

    private fun sendJson(payload: JSONObject) {
        val socket = webSocket ?: throw TvRemoteException("Samsung TV is not connected")
        if (!socket.send(payload.toString())) {
            throw TvRemoteException("Could not send Samsung TV remote command.")
        }
    }

    private fun buildSocketUrl(secure: Boolean, token: String?): String {
        val scheme = if (secure) "wss" else "ws"
        val port = if (secure) 8002 else 8001
        val tokenParam = token?.takeIf { it.isNotBlank() }?.let { "&token=$it" }.orEmpty()
        return "$scheme://${device.host}:$port/api/v2/channels/samsung.remote.control" +
            "?name=${encodedAppName()}&role=host&privilege_level=ALL$tokenParam"
    }

    private fun encodedAppName(): String {
        val name = appContext.getString(com.tvchromecast.screenmirroringplus.R.string.app_name)
        return Base64.encodeToString(name.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun savedToken(): String? = prefs.getString(tokenKey(), null)

    private fun saveToken(token: String) {
        prefs.edit().putString(tokenKey(), token).apply()
    }

    private fun clearToken() {
        prefs.edit().remove(tokenKey()).apply()
    }

    private fun tokenKey(): String = "samsung_token_${device.host}"

    private fun TvRemoteKey.toSamsungKey(): String? {
        return when (this) {
            TvRemoteKey.Power,
            TvRemoteKey.TvPower -> "KEY_POWER"
            TvRemoteKey.Input -> "KEY_SOURCE"
            TvRemoteKey.Settings -> "KEY_SETTINGS"
            TvRemoteKey.Up -> "KEY_UP"
            TvRemoteKey.Down -> "KEY_DOWN"
            TvRemoteKey.Left -> "KEY_LEFT"
            TvRemoteKey.Right -> "KEY_RIGHT"
            TvRemoteKey.Enter -> "KEY_ENTER"
            TvRemoteKey.Back -> "KEY_RETURN"
            TvRemoteKey.Home -> "KEY_HOME"
            TvRemoteKey.Menu -> "KEY_MENU"
            TvRemoteKey.VolumeUp -> "KEY_VOLUP"
            TvRemoteKey.VolumeDown -> "KEY_VOLDOWN"
            TvRemoteKey.Mute -> "KEY_MUTE"
            TvRemoteKey.ChannelUp -> "KEY_CHUP"
            TvRemoteKey.ChannelDown -> "KEY_CHDOWN"
            TvRemoteKey.Rewind -> "KEY_REWIND"
            TvRemoteKey.PlayPause -> "KEY_PLAYPAUSE"
            TvRemoteKey.Forward -> "KEY_FF"
        }
    }

    companion object {
        private const val PREFS_NAME = "samsung_tv_remote"
        private const val CONNECT_TIMEOUT_SECONDS = 12L

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
