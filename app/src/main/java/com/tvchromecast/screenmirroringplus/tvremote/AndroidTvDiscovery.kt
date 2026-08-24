package com.tvchromecast.screenmirroringplus.tvremote

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URI
import java.net.URL
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
    private var ssdpScanExecutor: ExecutorService? = null
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
            if (isDiscovering && scanGeneration == generation) {
                startSsdpCandidateScan(generation)
                startDirectSubnetScan(generation)
            }
        }, CANDIDATE_SCAN_DELAY_MS)
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
        ssdpScanExecutor?.shutdownNow()
        ssdpScanExecutor = null
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
                if (isAndroidTvRemoteService(resolvedServiceInfo.serviceType)) {
                    val remotePort = resolvedServiceInfo.port.takeIf { it > 0 } ?: DEFAULT_REMOTE_PORT
                    val id = "${TvRemoteProtocol.AndroidTv.name.lowercase(Locale.US)}:$host:$remotePort"
                    devices[id] = TvRemoteDevice(
                        id = id,
                        name = resolvedServiceInfo.serviceName,
                        host = host,
                        remotePort = remotePort,
                        pairPort = TvRemoteDevice.DEFAULT_PAIRING_PORT,
                        protocol = TvRemoteProtocol.AndroidTv
                    )
                    serviceDeviceIds[key] = id
                    emitDevices()
                } else {
                    probeRemoteCandidate(
                        host = host,
                        name = resolvedServiceInfo.serviceName,
                        type = candidateType(resolvedServiceInfo.serviceType),
                        protocol = candidateProtocol(resolvedServiceInfo.serviceType),
                        generation = scanGeneration,
                        serviceKey = key
                    )
                }
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
                val device = probeKnownRemotePorts(host) ?: return@execute
                mainHandler.post { addRemoteCandidate(device, generation, serviceKey = null) }
            }
        }
        executor.shutdown()
    }

    private fun startSsdpCandidateScan(generation: Int) {
        ssdpScanExecutor?.shutdownNow()
        val executor = Executors.newSingleThreadExecutor()
        ssdpScanExecutor = executor
        executor.execute {
            val seenHosts = mutableSetOf<String>()
            SSDP_SEARCH_TARGETS.forEach { searchTarget ->
                if (!isDiscovering || scanGeneration != generation || Thread.currentThread().isInterrupted) {
                    return@execute
                }
                discoverSsdpCandidates(searchTarget).forEach { candidate ->
                    if (!seenHosts.add(candidate.host)) return@forEach
                    val remoteDevice = probeKnownRemotePorts(candidate.host)
                    val androidRemoteOpen = remoteDevice == null && isAndroidTvRemotePortOpen(candidate.host)
                    mainHandler.post {
                        addRemoteCandidate(
                            device = remoteDevice?.withCandidateIdentity(candidate) ?: candidate.toDevice(androidRemoteOpen),
                            generation = generation,
                            serviceKey = null
                        )
                    }
                }
            }
        }
        executor.shutdown()
    }

    private fun discoverSsdpCandidates(searchTarget: String): List<NetworkCandidate> {
        val request = buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: 239.255.255.250:1900\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: 1\r\n")
            append("ST: ").append(searchTarget).append("\r\n")
            append("\r\n")
        }.toByteArray(Charsets.UTF_8)
        val candidates = linkedMapOf<String, NetworkCandidate>()
        return runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = SSDP_RECEIVE_TIMEOUT_MS
                val packet = DatagramPacket(
                    request,
                    request.size,
                    InetAddress.getByName(SSDP_MULTICAST_ADDRESS),
                    SSDP_PORT
                )
                repeat(SSDP_REQUEST_REPEATS) {
                    socket.send(packet)
                }

                val startedAt = System.currentTimeMillis()
                while (System.currentTimeMillis() - startedAt < SSDP_SCAN_WINDOW_MS) {
                    val buffer = ByteArray(SSDP_RESPONSE_BUFFER_SIZE)
                    val responsePacket = DatagramPacket(buffer, buffer.size)
                    val response = runCatching {
                        socket.receive(responsePacket)
                        String(
                            responsePacket.data,
                            responsePacket.offset,
                            responsePacket.length,
                            Charsets.UTF_8
                        )
                    }.getOrNull() ?: continue
                    val location = ssdpHeader(response, "location")
                    val host = ssdpCandidateHost(location, responsePacket.address.hostAddress) ?: continue
                    val description = fetchSsdpDeviceDescription(location)
                    candidates[host] = NetworkCandidate.from(
                        host = host,
                        response = response,
                        description = description,
                        searchTarget = searchTarget,
                        location = location
                    )
                }
            }
            candidates.values.toList()
        }.getOrDefault(emptyList())
    }

    private fun probeRemoteCandidate(
        host: String,
        name: String,
        type: String,
        protocol: TvRemoteProtocol,
        generation: Int,
        serviceKey: String?
    ) {
        val currentExecutor = directScanExecutor
        val executor = if (currentExecutor == null || currentExecutor.isShutdown || currentExecutor.isTerminated) {
            Executors.newFixedThreadPool(DIRECT_SCAN_THREADS).also {
                directScanExecutor = it
            }
        } else {
            currentExecutor
        }
        executor.execute {
            if (!isDiscovering || scanGeneration != generation || Thread.currentThread().isInterrupted) {
                return@execute
            }
            val remoteDevice = probeKnownRemotePorts(host)
            if (remoteDevice != null) {
                mainHandler.post {
                    addRemoteCandidate(
                        device = remoteDevice.copy(
                            name = name.ifBlank { remoteDevice.name },
                            type = if (protocol == remoteDevice.protocol) {
                                type.takeIf { it.isNotBlank() } ?: remoteDevice.type
                            } else {
                                remoteDevice.type
                            }
                        ),
                        generation = generation,
                        serviceKey = serviceKey
                    )
                }
                return@execute
            }
            if (!protocol.supportsTvRemoteKeys()) return@execute
            val androidRemoteOpen = isAndroidTvRemotePortOpen(host)
            mainHandler.post {
                val resolvedProtocol = if (androidRemoteOpen) TvRemoteProtocol.AndroidTv else protocol
                val remotePort = when (resolvedProtocol) {
                    TvRemoteProtocol.AndroidTv -> DEFAULT_REMOTE_PORT
                    TvRemoteProtocol.Roku -> ROKU_ECP_PORT
                    TvRemoteProtocol.WebOs -> WEBOS_COMMAND_PORT
                    TvRemoteProtocol.Samsung -> SAMSUNG_SECURE_PORT
                    else -> 0
                }
                addRemoteCandidate(
                    device = TvRemoteDevice(
                        id = "${resolvedProtocol.name.lowercase(Locale.US)}:$host:$remotePort",
                        name = name,
                        host = host,
                        remotePort = remotePort,
                        pairPort = TvRemoteDevice.DEFAULT_PAIRING_PORT,
                        type = if (androidRemoteOpen) "Android TV" else type,
                        protocol = resolvedProtocol
                    ),
                    generation = generation,
                    serviceKey = serviceKey
                )
            }
        }
    }

    private fun addRemoteCandidate(
        device: TvRemoteDevice,
        generation: Int,
        serviceKey: String?
    ) {
        if (!isDiscovering || scanGeneration != generation) return
        if (!device.protocol.supportsTvRemoteKeys()) return
        val id = device.id
        if (devices.containsKey(id)) return
        devices[id] = device
        serviceKey?.let { serviceDeviceIds[it] = id }
        emitDevices()
    }

    private fun TvRemoteDevice.withCandidateIdentity(candidate: NetworkCandidate): TvRemoteDevice {
        return copy(
            name = candidate.name.ifBlank { name },
            type = if (candidate.protocol == protocol) {
                candidate.type.takeIf { it.isNotBlank() } ?: type
            } else {
                type
            }
        )
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

    private fun probeKnownRemotePorts(host: String): TvRemoteDevice? {
        val webOsPort = webOsRemotePort(host)
        return when {
            isAndroidTvRemotePortOpen(host) -> {
                val id = "${TvRemoteProtocol.AndroidTv.name.lowercase(Locale.US)}:$host:$DEFAULT_REMOTE_PORT"
                TvRemoteDevice(
                    id = id,
                    name = "Android TV $host",
                    host = host,
                    remotePort = DEFAULT_REMOTE_PORT,
                    pairPort = TvRemoteDevice.DEFAULT_PAIRING_PORT,
                    protocol = TvRemoteProtocol.AndroidTv
                )
            }

            isTcpPortOpen(host, ROKU_ECP_PORT) -> {
                val id = "${TvRemoteProtocol.Roku.name.lowercase(Locale.US)}:$host:$ROKU_ECP_PORT"
                TvRemoteDevice(
                    id = id,
                    name = "Roku $host",
                    host = host,
                    remotePort = ROKU_ECP_PORT,
                    type = "Roku",
                    protocol = TvRemoteProtocol.Roku
                )
            }

            webOsPort != null -> {
                val id = "${TvRemoteProtocol.WebOs.name.lowercase(Locale.US)}:$host:$webOsPort"
                TvRemoteDevice(
                    id = id,
                    name = "LG webOS $host",
                    host = host,
                    remotePort = webOsPort,
                    type = "LG webOS",
                    protocol = TvRemoteProtocol.WebOs
                )
            }

            isTcpPortOpen(host, SAMSUNG_SECURE_PORT) || isTcpPortOpen(host, SAMSUNG_INSECURE_PORT) -> {
                val id = "${TvRemoteProtocol.Samsung.name.lowercase(Locale.US)}:$host:$SAMSUNG_SECURE_PORT"
                TvRemoteDevice(
                    id = id,
                    name = "Samsung TV $host",
                    host = host,
                    remotePort = SAMSUNG_SECURE_PORT,
                    type = "Samsung TV",
                    protocol = TvRemoteProtocol.Samsung
                )
            }

            else -> null
        }
    }

    private fun webOsRemotePort(host: String): Int? {
        return when {
            isTcpPortOpen(host, WEBOS_COMMAND_PORT) -> WEBOS_COMMAND_PORT
            isTcpPortOpen(host, WEBOS_INSECURE_PORT) -> WEBOS_INSECURE_PORT
            else -> null
        }
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

    private fun isAndroidTvRemoteService(serviceType: String): Boolean {
        val normalizedType = serviceType.lowercase(Locale.US)
        return ANDROID_TV_REMOTE_SERVICE_TYPES.any {
            normalizedType.contains(it.removeSuffix(".").lowercase(Locale.US))
        }
    }

    private fun candidateType(serviceType: String): String {
        val normalizedType = serviceType.lowercase(Locale.US)
        return when {
            normalizedType.contains("_googlecast._tcp") -> "Chromecast / Android TV"
            normalizedType.contains("_roku") -> "Roku"
            normalizedType.contains("_samsung") -> "Samsung TV"
            normalizedType.contains("_webos") || normalizedType.contains("_lgsmarttv") -> "LG webOS"
            else -> "Android TV"
        }
    }

    private fun candidateProtocol(serviceType: String): TvRemoteProtocol {
        val normalizedType = serviceType.lowercase(Locale.US)
        return when {
            normalizedType.contains("_roku") -> TvRemoteProtocol.Roku
            normalizedType.contains("_samsung") -> TvRemoteProtocol.Samsung
            normalizedType.contains("_webos") || normalizedType.contains("_lgsmarttv") -> TvRemoteProtocol.WebOs
            else -> TvRemoteProtocol.CastOrDlna
        }
    }

    private fun ssdpCandidateHost(location: String?, fallbackHost: String?): String? {
        return location
            ?.let { runCatching { URI(it).host }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
            ?: fallbackHost?.takeIf { it.isNotBlank() }
    }

    private fun fetchSsdpDeviceDescription(location: String?): SsdpDeviceDescription? {
        if (location.isNullOrBlank()) return null
        return runCatching {
            val connection = URL(location).openConnection().apply {
                connectTimeout = SSDP_DESCRIPTION_TIMEOUT_MS
                readTimeout = SSDP_DESCRIPTION_TIMEOUT_MS
            }
            connection.getInputStream().bufferedReader().use { reader ->
                val xml = reader.readText().take(MAX_SSDP_DESCRIPTION_LENGTH)
                SsdpDeviceDescription(
                    friendlyName = xmlTagValue(xml, "friendlyName"),
                    manufacturer = xmlTagValue(xml, "manufacturer"),
                    modelName = xmlTagValue(xml, "modelName")
                )
            }
        }.getOrNull()
    }

    private fun xmlTagValue(xml: String, tagName: String): String? {
        val regex = Regex("<\\s*$tagName\\s*>(.*?)<\\s*/\\s*$tagName\\s*>", RegexOption.IGNORE_CASE)
        return regex.find(xml)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun ssdpHeader(response: String, name: String): String? {
        val prefix = "${name.lowercase(Locale.US)}:"
        return response
            .lineSequence()
            .firstOrNull { it.lowercase(Locale.US).startsWith(prefix) }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun TvRemoteProtocol.supportsTvRemoteKeys(): Boolean {
        return when (this) {
            TvRemoteProtocol.AndroidTv,
            TvRemoteProtocol.Roku,
            TvRemoteProtocol.WebOs,
            TvRemoteProtocol.Samsung -> true
            TvRemoteProtocol.FireTv,
            TvRemoteProtocol.CastOrDlna -> false
        }
    }

    companion object {
        private val ANDROID_TV_REMOTE_SERVICE_TYPES = arrayOf(
            "_androidtvremote2._tcp.",
            "_androidtvremote._tcp."
        )
        private val SERVICE_TYPES = ANDROID_TV_REMOTE_SERVICE_TYPES + arrayOf(
            "_googlecast._tcp.",
            "_roku-ecp._tcp.",
            "_samsungmsf._tcp.",
            "_webostv._tcp.",
            "_lgsmarttv._tcp."
        )
        private val SSDP_SEARCH_TARGETS = arrayOf(
            "urn:dial-multiscreen-org:service:dial:1",
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "ssdp:all"
        )
        private const val DEFAULT_REMOTE_PORT = 6466
        private const val MULTICAST_LOCK_TAG = "tv_cast_android_tv_remote_discovery"
        private const val MAX_RESOLVE_RETRIES = 2
        private const val RESOLVE_RETRY_DELAY_MS = 500L
        private const val CANDIDATE_SCAN_DELAY_MS = 1_500L
        private const val DIRECT_SCAN_THREADS = 32
        private const val DIRECT_SCAN_CONNECT_TIMEOUT_MS = 450
        private const val MAX_DIRECT_SCAN_HOSTS = 254
        private const val DEFAULT_IPV4_PREFIX_LENGTH = 24
        private const val MIN_IPV4_PREFIX_LENGTH = 16
        private const val MAX_IPV4_PREFIX_LENGTH = 30
        private const val IPV4_MAX = 0xffffffffL
        private const val IPV4_CLASS_C_MASK = 0xffffff00L
        private const val SSDP_MULTICAST_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val SSDP_REQUEST_REPEATS = 2
        private const val SSDP_RECEIVE_TIMEOUT_MS = 350
        private const val SSDP_SCAN_WINDOW_MS = 1_500L
        private const val SSDP_RESPONSE_BUFFER_SIZE = 4096
        private const val SSDP_DESCRIPTION_TIMEOUT_MS = 700
        private const val MAX_SSDP_DESCRIPTION_LENGTH = 48_000
        private const val ROKU_ECP_PORT = 8060
        private const val WEBOS_INSECURE_PORT = 3000
        private const val WEBOS_COMMAND_PORT = 3001
        private const val SAMSUNG_INSECURE_PORT = 8001
        private const val SAMSUNG_SECURE_PORT = 8002
    }

    private data class Ipv4Network(
        val address: Long,
        val prefixLength: Int
    )

    private data class NetworkCandidate(
        val host: String,
        val name: String,
        val type: String,
        val protocol: TvRemoteProtocol,
        val port: Int
    ) {
        fun toDevice(androidRemoteOpen: Boolean): TvRemoteDevice {
            val resolvedProtocol = if (androidRemoteOpen) TvRemoteProtocol.AndroidTv else protocol
            val resolvedType = if (androidRemoteOpen) "Android TV" else type
            val resolvedPort = when (resolvedProtocol) {
                TvRemoteProtocol.AndroidTv -> DEFAULT_REMOTE_PORT
                TvRemoteProtocol.Roku -> port.takeIf { it > 0 } ?: ROKU_ECP_PORT
                TvRemoteProtocol.WebOs -> port.takeIf { it > 0 } ?: WEBOS_COMMAND_PORT
                TvRemoteProtocol.Samsung -> port.takeIf { it > 0 } ?: SAMSUNG_SECURE_PORT
                else -> port
            }
            return TvRemoteDevice(
                id = "${resolvedProtocol.name.lowercase(Locale.US)}:$host:$resolvedPort",
                name = name.ifBlank { "$resolvedType $host" },
                host = host,
                remotePort = resolvedPort,
                pairPort = TvRemoteDevice.DEFAULT_PAIRING_PORT,
                type = resolvedType,
                protocol = resolvedProtocol
            )
        }

        companion object {
            fun from(
                host: String,
                response: String,
                description: SsdpDeviceDescription?,
                searchTarget: String,
                location: String?
            ): NetworkCandidate {
                val fingerprint = listOfNotNull(
                    ssdpHeader(response, "server"),
                    ssdpHeader(response, "st"),
                    searchTarget,
                    description?.manufacturer,
                    description?.modelName,
                    description?.friendlyName
                ).joinToString(" ").lowercase(Locale.US)
                val protocol = when {
                    fingerprint.contains("roku") -> TvRemoteProtocol.Roku
                    fingerprint.contains("webos") || fingerprint.contains("lg") -> TvRemoteProtocol.WebOs
                    fingerprint.contains("samsung") -> TvRemoteProtocol.Samsung
                    fingerprint.contains("fire tv") || fingerprint.contains("aft") || fingerprint.contains("amazon") -> TvRemoteProtocol.FireTv
                    else -> TvRemoteProtocol.CastOrDlna
                }
                val type = when (protocol) {
                    TvRemoteProtocol.Roku -> "Roku"
                    TvRemoteProtocol.WebOs -> "LG webOS"
                    TvRemoteProtocol.Samsung -> "Samsung TV"
                    TvRemoteProtocol.FireTv -> "Fire TV"
                    TvRemoteProtocol.AndroidTv -> "Android TV"
                    TvRemoteProtocol.CastOrDlna -> when {
                        fingerprint.contains("dial") -> "DIAL device"
                        fingerprint.contains("mediarenderer") -> "DLNA device"
                        else -> "Cast / DLNA device"
                    }
                }
                val name = description?.friendlyName
                    ?: ssdpHeader(response, "server")?.take(48)
                    ?: "$type $host"
                val port = location
                    ?.let { runCatching { URI(it).port }.getOrNull() }
                    ?.takeIf { it > 0 }
                    ?: if (protocol == TvRemoteProtocol.Roku) ROKU_ECP_PORT else 0
                return NetworkCandidate(host, name, type, protocol, port)
            }

            private fun ssdpHeader(response: String, name: String): String? {
                val prefix = "${name.lowercase(Locale.US)}:"
                return response
                    .lineSequence()
                    .firstOrNull { it.lowercase(Locale.US).startsWith(prefix) }
                    ?.substringAfter(':')
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }
        }
    }

    private data class SsdpDeviceDescription(
        val friendlyName: String?,
        val manufacturer: String?,
        val modelName: String?
    )
}
