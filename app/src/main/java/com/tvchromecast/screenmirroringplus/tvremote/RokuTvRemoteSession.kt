package com.tvchromecast.screenmirroringplus.tvremote

import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.Locale

internal class RokuTvRemoteSession(private val device: TvRemoteDevice) {
    fun start() {
        request("GET", "query/device-info")
    }

    fun sendKey(key: TvRemoteKey) {
        val rokuKey = when (key) {
            TvRemoteKey.Power,
            TvRemoteKey.TvPower -> "Power"
            TvRemoteKey.Up -> "Up"
            TvRemoteKey.Down -> "Down"
            TvRemoteKey.Left -> "Left"
            TvRemoteKey.Right -> "Right"
            TvRemoteKey.Enter -> "Select"
            TvRemoteKey.Back -> "Back"
            TvRemoteKey.Home -> "Home"
            TvRemoteKey.VolumeUp -> "VolumeUp"
            TvRemoteKey.VolumeDown -> "VolumeDown"
            TvRemoteKey.Mute -> "VolumeMute"
            TvRemoteKey.Rewind -> "Rev"
            TvRemoteKey.PlayPause -> "Play"
            TvRemoteKey.Forward -> "Fwd"
            TvRemoteKey.Input -> "InputTuner"
            TvRemoteKey.Settings,
            TvRemoteKey.Menu -> "Info"
            TvRemoteKey.ChannelUp -> "ChannelUp"
            TvRemoteKey.ChannelDown -> "ChannelDown"
        }
        request("POST", "keypress/$rokuKey")
    }

    fun sendText(text: String) {
        text.forEach { char ->
            val encoded = URLEncoder.encode(char.toString(), "UTF-8")
                .replace("+", "%20")
            request("POST", "keypress/Lit_$encoded")
        }
    }

    fun launchApp(appId: String) {
        val normalizedId = appId.trim().lowercase(Locale.US)
        val rokuAppId = when {
            normalizedId.contains("youtube") -> "837"
            normalizedId.contains("netflix") -> "12"
            normalizedId.contains("hulu") -> "2285"
            normalizedId.contains("disney") -> "291097"
            normalizedId.contains("prime") || normalizedId.contains("amazon") -> "13"
            else -> throw TvRemoteException("This Roku app shortcut is not mapped yet.")
        }
        request("POST", "launch/$rokuAppId")
    }

    fun close() = Unit

    private fun request(method: String, path: String) {
        val port = device.remotePort.takeIf { it > 0 } ?: DEFAULT_ROKU_PORT
        val connection = URL("http://${device.host}:$port/$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = REQUEST_TIMEOUT_MS
        connection.readTimeout = REQUEST_TIMEOUT_MS
        connection.doInput = true
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw TvRemoteException("Roku command failed with HTTP $code.")
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val DEFAULT_ROKU_PORT = 8060
        private const val REQUEST_TIMEOUT_MS = 1_500
    }
}
