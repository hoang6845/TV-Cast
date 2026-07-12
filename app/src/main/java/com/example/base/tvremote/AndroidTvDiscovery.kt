package com.example.base.tvremote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

internal class AndroidTvDiscovery(
    context: Context,
    private val onDevicesChanged: (List<TvRemoteDevice>) -> Unit,
    private val onError: (String) -> Unit
) {
    private val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val devices = linkedMapOf<String, TvRemoteDevice>()
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun start() {
        stop()
        devices.clear()
        onDevicesChanged(emptyList())
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != SERVICE_TYPE) return
                resolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val key = serviceInfo.serviceName
                devices.entries.removeAll { it.key.contains(key) || it.value.name == key }
                onDevicesChanged(devices.values.toList())
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                stop()
                onError("Could not start TV discovery. Error $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                stop()
            }
        }
        discoveryListener = listener
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stop() {
        discoveryListener?.let {
            runCatching { nsdManager.stopServiceDiscovery(it) }
        }
        discoveryListener = null
    }

    private fun resolve(serviceInfo: NsdServiceInfo) {
        nsdManager.resolveService(
            serviceInfo,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

                override fun onServiceResolved(resolvedServiceInfo: NsdServiceInfo) {
                    val host = resolvedServiceInfo.host?.hostAddress ?: return
                    val remotePort = resolvedServiceInfo.port.takeIf { it > 0 } ?: DEFAULT_REMOTE_PORT
                    val id = "$host:$remotePort"
                    devices[id] = TvRemoteDevice(
                        id = id,
                        name = resolvedServiceInfo.serviceName,
                        host = host,
                        remotePort = remotePort,
                        pairPort = TvRemoteDevice.DEFAULT_PAIRING_PORT
                    )
                    onDevicesChanged(devices.values.toList())
                }
            }
        )
    }

    companion object {
        private const val SERVICE_TYPE = "_androidtvremote2._tcp."
        private const val DEFAULT_REMOTE_PORT = 6466
    }
}

