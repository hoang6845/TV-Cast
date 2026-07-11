package tpt.dev.monetization.utils.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Implementation of [ConnectivityObserver]
 */
class NetworkObserver(context: Context) : ConnectivityObserver {
    /**
     * The connectivity manager obtains the actual status from the system
     */
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * This implementation's purpose is to convert the [connectivityManager] statuses to flows
     * When a specific callback is triggered, this function will send a value
     *
     * The callbackFlow call inside needs
     * ```<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />```
     * to be added to AndroidManifest
     */
    override fun observe(): Flow<ConnectivityObserver.Status> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
//            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
//                super.onCapabilitiesChanged(network, networkCapabilities)
//                launch {
//                    networkCapabilities.run {
//                        if (hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
//                            send(ConnectivityObserver.Status.InternetWithConnection)
//                        } else if (hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
//                            send(ConnectivityObserver.Status.WithoutNetworkAccess)
//                        } else if (!hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)) {
//                            send(ConnectivityObserver.Status.NetworkSuspended)
//                        }
//                    }
//                }
//            }

            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                launch { send(ConnectivityObserver.Status.Available) }
            }

            override fun onUnavailable() {
                super.onUnavailable()
                launch { send(ConnectivityObserver.Status.Unavailable) }
            }

            override fun onLosing(network: Network, maxMsToLive: Int) {
                super.onLosing(network, maxMsToLive)
                launch { send(ConnectivityObserver.Status.Losing) }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                launch { send(ConnectivityObserver.Status.Lost) }
            }
        }
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, callback)
        if (connectivityManager.activeNetwork == null) {
            launch { send(ConnectivityObserver.Status.Lost) }
        }
        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged() // Ensures only firing when the value is changed
}
