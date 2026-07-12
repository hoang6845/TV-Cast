package com.example.base.tvremote

import android.content.Context
import android.net.wifi.WifiManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper

internal class AndroidTvDiscovery(
    context: Context,
    private val onDevicesChanged: (List<TvRemoteDevice>) -> Unit,
    private val onError: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val devices = linkedMapOf<String, TvRemoteDevice>()
    private val pendingResolveServices = mutableListOf<NsdServiceInfo>()
    private val queuedResolveKeys = mutableSetOf<String>()
    private val resolveRetryCounts = mutableMapOf<String, Int>()
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var activeResolveKey: String? = null
    private var isDiscovering = false

    fun start() {
        stop()
        devices.clear()
        pendingResolveServices.clear()
        queuedResolveKeys.clear()
        resolveRetryCounts.clear()
        activeResolveKey = null
        isDiscovering = true
        acquireMulticastLock()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.contains("_androidtvremote2")) return
                enqueueResolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val key = serviceInfo.serviceName
                pendingResolveServices.removeAll { it.serviceName == key }
                queuedResolveKeys.remove(serviceKey(serviceInfo))
                resolveRetryCounts.remove(serviceKey(serviceInfo))
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
        isDiscovering = false
        mainHandler.removeCallbacksAndMessages(null)
        pendingResolveServices.clear()
        queuedResolveKeys.clear()
        resolveRetryCounts.clear()
        activeResolveKey = null
        discoveryListener?.let {
            runCatching { nsdManager.stopServiceDiscovery(it) }
        }
        discoveryListener = null
        releaseMulticastLock()
    }

    private fun enqueueResolve(serviceInfo: NsdServiceInfo) {
        val key = serviceKey(serviceInfo)
        if (key == activeResolveKey || !queuedResolveKeys.add(key)) return
        pendingResolveServices += serviceInfo
        resolveNext()
    }

    private fun resolveNext() {
        if (!isDiscovering || activeResolveKey != null || pendingResolveServices.isEmpty()) return
        val serviceInfo = pendingResolveServices.removeAt(0)
        val key = serviceKey(serviceInfo)
        queuedResolveKeys.remove(key)
        activeResolveKey = key
        resolve(serviceInfo, key)
    }

    private fun resolve(serviceInfo: NsdServiceInfo, key: String) {
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                activeResolveKey = null
                val retryCount = resolveRetryCounts[key] ?: 0
                if (isDiscovering && retryCount < MAX_RESOLVE_RETRIES) {
                    resolveRetryCounts[key] = retryCount + 1
                    mainHandler.postDelayed({
                        if (isDiscovering) {
                            enqueueResolve(serviceInfo)
                        }
                    }, RESOLVE_RETRY_DELAY_MS)
                } else {
                    resolveRetryCounts.remove(key)
                }
                resolveNext()
            }

            override fun onServiceResolved(resolvedServiceInfo: NsdServiceInfo) {
                activeResolveKey = null
                resolveRetryCounts.remove(key)
                val host = resolvedServiceInfo.host?.hostAddress
                if (host == null) {
                    resolveNext()
                    return
                }
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
                resolveNext()
            }
        }

        runCatching {
            nsdManager.resolveService(serviceInfo, listener)
        }.onFailure {
            activeResolveKey = null
            mainHandler.postDelayed({
                if (isDiscovering) {
                    enqueueResolve(serviceInfo)
                }
            }, RESOLVE_RETRY_DELAY_MS)
            resolveNext()
        }
    }

    private fun serviceKey(serviceInfo: NsdServiceInfo): String {
        return "${serviceInfo.serviceName}:${serviceInfo.serviceType}"
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        multicastLock = wifiManager.createMulticastLock(MULTICAST_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { lock ->
            if (lock.isHeld) {
                runCatching { lock.release() }
            }
        }
        multicastLock = null
    }

    companion object {
        private const val SERVICE_TYPE = "_androidtvremote2._tcp."
        private const val DEFAULT_REMOTE_PORT = 6466
        private const val MULTICAST_LOCK_TAG = "tv_cast_android_tv_remote_discovery"
        private const val MAX_RESOLVE_RETRIES = 2
        private const val RESOLVE_RETRY_DELAY_MS = 500L
    }
}
