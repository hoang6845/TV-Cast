package com.tvchromecast.screenmirroringplus.tvremote

data class TvRemoteDevice(
    val id: String,
    val name: String,
    val host: String,
    val remotePort: Int,
    val pairPort: Int = DEFAULT_PAIRING_PORT,
    val type: String = "Android TV"
) {
    val subtitle: String
        get() = "$type - $host"

    companion object {
        const val DEFAULT_PAIRING_PORT = 6467
    }
}

data class TvRemoteApp(
    val label: String,
    val packageName: String
)

enum class TvRemoteKey(val androidKeyCode: Int, val label: String) {
    Power(26, "Power"),
    TvPower(177, "Power"),
    Input(178, "Input"),
    Settings(176, "Settings"),
    Up(19, "Up"),
    Down(20, "Down"),
    Left(21, "Left"),
    Right(22, "Right"),
    Enter(23, "OK"),
    Back(4, "Back"),
    Home(3, "Home"),
    Menu(82, "Menu"),
    VolumeUp(24, "Volume +"),
    VolumeDown(25, "Volume -"),
    Mute(164, "Mute"),
    ChannelUp(166, "Channel +"),
    ChannelDown(167, "Channel -"),
    Rewind(89, "Rewind"),
    PlayPause(85, "Play/Pause"),
    Forward(90, "Forward")
}

sealed interface TvRemoteConnectionState {
    data object Idle : TvRemoteConnectionState
    data object Searching : TvRemoteConnectionState
    data class Pairing(val deviceName: String) : TvRemoteConnectionState
    data class Connecting(val deviceName: String) : TvRemoteConnectionState
    data class Connected(val deviceName: String) : TvRemoteConnectionState
    data class Reconnecting(val deviceName: String) : TvRemoteConnectionState
    data class Disconnected(val reason: String?) : TvRemoteConnectionState
    data class Error(val message: String) : TvRemoteConnectionState
}

open class TvRemoteException(message: String, cause: Throwable? = null) : Exception(message, cause)
class TvRemotePairingRequiredException(message: String = "Pairing required", cause: Throwable? = null) :
    TvRemoteException(message, cause)
