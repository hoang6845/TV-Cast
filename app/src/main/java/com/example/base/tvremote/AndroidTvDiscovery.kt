package com.example.base.tvremote

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal class AndroidTvDiscovery(
    context: Context,
    private val onDevicesChanged: (List<TvRemoteDevice>) -> Unit,
    private val onError: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val devices = linkedMapOf<String, TvRemoteDevice>()
    private val pendingResolveServices = mutableListOf<NsdServiceInfo>()
    private val queuedResolveKeys = mutableSetOf<String>()
    private val resolveRetryCounts = mutableMapOf<String, Int>()
    private val discoveryListeners = linkedMapOf<String, NsdManager.DiscoveryListener>()
    private val serviceDeviceIds = mutableMapOf<String, String>()
    private var multicastLock: WifiManager.MulticastLock? = null
    private var activeResolveKey: String? = null
    private var directScanExecutor: ExecutorService? = null
    @Volatile
    private var scanGeneration = 0
    private var isDiscovering = false

    fun start() {
        stop()
        val generation = ++scanGeneration
        devices.clear()
        pendingResolveServices.clear()
        queuedResolveKeys.clear()
        resolveRetryCounts.clear()
        serviceDeviceIds.clear()
        activeResolveKey = null
        isDiscovering = true
        acquireMulticastLock()
        SERVICE_TYPES.forEach { serviceType ->
            val listener = createDiscoveryListener(serviceType)
            discoveryListeners[serviceType] = listener
            runCatching {
                nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            }.onFailure {
                discoveryListeners.remove(serviceType)
                if (discoveryListeners.isEmpty()) {
                    stop()
                    onError("Could not start TV discovery.")
                }
            }
        }
        mainHandler.postDelayed({
            if (isDiscovering && scanGeneration == generation && devices.isEmpty()) {
                startDirectSubnetScan(generation)
            }
        }, DIRECT_SCAN_DELAY_MS)
    }

    fun stop() {
        isDiscovering = false
        scanGeneration++
        mainHandler.removeCallbacksAndMessages(null)
        pendingResolveServices.clear()
        queuedResolveKeys.clear()
        resolveRetryCounts.clear()
        serviceDeviceIds.clear()
        activeResolveKey = null
        discoveryListeners.values.toList().forEach {
            runCatching { nsdManager.stopServiceDiscovery(it) }
        }
        discoveryListeners.clear()
        directScanExecutor?.shutdownNow()
        directScanExecutor = null
        releaseMulticastLock()
    }

    private fun createDiscoveryListener(serviceType: String): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!isSupportedServiceType(serviceInfo.serviceType)) return
                enqueueResolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val key = serviceKey(serviceInfo)
                pendingResolveServices.removeAll { serviceKey(it) == key }
                queuedResolveKeys.remove(key)
                resolveRetryCounts.remove(key)
                serviceDeviceIds.remove(key)?.let { devices.remove(it) }
                emitDevices()
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                discoveryListeners.remove(serviceType)
                if (discoveryListeners.isEmpty()) {
                    stop()
                    onError("Could not start TV discovery. Error $errorCode")
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                discoveryListeners.remove(serviceType)
            }
        }
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
                serviceDeviceIds[key] = id
                emitDevices()
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

    private fun isSupportedServiceType(serviceType: String): Boolean {
        val normalizedType = serviceType.lowercase(Locale.US)
        return SERVICE_TYPES.any { normalizedType.contains(it.removeSuffix(".").lowercase(Locale.US)) }
    }

    private fun emitDevices() {
        onDevicesChanged(
            devices.values.sortedWith(
                compareBy<TvRemoteDevice> { it.name.lowercase(Locale.US) }
                    .thenBy { it.host }
            )
        )
    }

    private fun startDirectSubnetScan(generation: Int) {
        val hosts = localSubnetHosts()
        if (hosts.isEmpty()) return
        directScanExecutor?.shutdownNow()
        val executor = Executors.newFixedThreadPool(DIRECT_SCAN_THREADS)
        directScanExecutor = executor
        hosts.forEach { host ->
            executor.execute {
                if (!isDiscovering || scanGeneration != generation || Thread.currentThread().isInterrupted) {
                    return@execute
                }
                if (!isAndroidTvRemotePortOpen(host)) return@execute
                mainHandler.post {
                    if (!isDiscovering || scanGeneration != generation) return@post
                    val id = "$host:$DEFAULT_REMOTE_PORT"
                    if (devices.containsKey(id)) return@post
                    devices[id] = TvRemoteDevice(
                        id = id,
                        name = "Android TV $host",
                        host = host,
                        remotePort = DEFAULT_REMOTE_PORT,
                        pairPort = TvRemoteDevice.DEFAULT_PAIRING_PORT
                    )
                    emitDevices()
                }
            }
        }
        executor.shutdown()
    }

    private fun localSubnetHosts(): List<String> {
        val network = currentIpv4Network() ?: return emptyList()
        val mask = ipv4Mask(network.prefixLength)
        val networkAddress = network.address and mask
        val broadcastAddress = networkAddress or (mask xor IPV4_MAX)
        val hostCount = broadcastAddress - networkAddress - 1
        val range = if (hostCount in 1..MAX_DIRECT_SCAN_HOSTS.toLong()) {
            (networkAddress + 1)..(broadcastAddress - 1)
        } else {
            val localClassC = network.address and IPV4_CLASS_C_MASK
            (localClassC + 1)..(localClassC + 254)
        }
        return range
            .filter { it != network.address }
            .take(MAX_DIRECT_SCAN_HOSTS)
            .map(::ipv4LongToString)
    }

    private fun currentIpv4Network(): Ipv4Network? {
        runCatching {
            val activeNetwork = connectivityManager.activeNetwork ?: return@runCatching null
            connectivityManager.getLinkProperties(activeNetwork)
                ?.linkAddresses
                ?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
                ?.let {
                    return Ipv4Network(
                        address = (it.address as Inet4Address).toIpv4Long(),
                        prefixLength = it.prefixLength.coerceIn(MIN_IPV4_PREFIX_LENGTH, MAX_IPV4_PREFIX_LENGTH)
                    )
                }
        }
        runCatching {
            NetworkInterface.getNetworkInterfaces()
                ?.asSequence()
                ?.filter { it.isUp && !it.isLoopback }
                ?.flatMap { it.interfaceAddresses.asSequence() }
                ?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
                ?.let {
                    return Ipv4Network(
                        address = (it.address as Inet4Address).toIpv4Long(),
                        prefixLength = DEFAULT_IPV4_PREFIX_LENGTH
                    )
                }
        }
        return null
    }

    private fun isAndroidTvRemotePortOpen(host: String): Boolean {
        return isTcpPortOpen(host, DEFAULT_REMOTE_PORT) ||
            isTcpPortOpen(host, TvRemoteDevice.DEFAULT_PAIRING_PORT)
    }

    private fun isTcpPortOpen(host: String, port: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), DIRECT_SCAN_CONNECT_TIMEOUT_MS)
            }
            true
        }.getOrDefault(false)
    }

    private fun Inet4Address.toIpv4Long(): Long {
        return address.fold(0L) { result, byte ->
            (result shl 8) or (byte.toInt() and 0xff).toLong()
        } and IPV4_MAX
    }

    private fun ipv4Mask(prefixLength: Int): Long {
        return (IPV4_MAX shl (MAX_IPV4_PREFIX_LENGTH - prefixLength)) and IPV4_MAX
    }

    private fun ipv4LongToString(value: Long): String {
        return listOf(
            (value shr 24) and 0xff,
            (value shr 16) and 0xff,
            (value shr 8) and 0xff,
            value and 0xff
        ).joinToString(".")
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        multicastLock = runCatching {
            wifiManager.createMulticastLock(MULTICAST_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
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
        private val SERVICE_TYPES = arrayOf(
            "_androidtvremote2._tcp.",
            "_androidtvremote._tcp."
        )
        private const val DEFAULT_REMOTE_PORT = 6466
        private const val MULTICAST_LOCK_TAG = "tv_cast_android_tv_remote_discovery"
        private const val MAX_RESOLVE_RETRIES = 2
        private const val RESOLVE_RETRY_DELAY_MS = 500L
        private const val DIRECT_SCAN_DELAY_MS = 2_500L
        private const val DIRECT_SCAN_THREADS = 32
        private const val DIRECT_SCAN_CONNECT_TIMEOUT_MS = 220
        private const val MAX_DIRECT_SCAN_HOSTS = 254
        private const val DEFAULT_IPV4_PREFIX_LENGTH = 24
        private const val MIN_IPV4_PREFIX_LENGTH = 16
        private const val MAX_IPV4_PREFIX_LENGTH = 30
        private const val IPV4_MAX = 0xffffffffL
        private const val IPV4_CLASS_C_MASK = 0xffffff00L
    }

    private data class Ipv4Network(
        val address: Long,
        val prefixLength: Int
    )
}
