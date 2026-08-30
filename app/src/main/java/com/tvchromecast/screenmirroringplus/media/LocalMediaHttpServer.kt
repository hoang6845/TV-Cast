package com.tvchromecast.screenmirroringplus.media

import android.content.Context
import android.database.Cursor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URL
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class LocalMediaHttpServer(
    private val context: Context
) : Closeable {

    private val entries = ConcurrentHashMap<String, Entry>()
    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    private var acceptThread: Thread? = null

    val isRunning: Boolean
        get() = serverSocket?.isClosed == false

    fun start(): Boolean {
        if (isRunning) return true

        val socket = runCatching { ServerSocket(PREFERRED_MEDIA_PORT) }
            .recoverCatching { ServerSocket(0) }
            .getOrNull()
            ?: return false
        serverSocket = socket
        executor = Executors.newCachedThreadPool()
        acceptThread = Thread({ acceptLoop(socket) }, "LocalMediaHttpServer").apply {
            isDaemon = true
            start()
        }
        return true
    }

    fun register(
        uri: Uri,
        mimeType: String,
        displayName: String? = null,
        urlExtensionOverride: String? = null
    ): String? {
        if (!start()) return null

        val token = UUID.randomUUID().toString()
        val extension = urlExtensionOverride?.asSafeMediaExtension()
            ?: mediaExtension(
                hint = displayName ?: uri.lastPathSegment,
                mimeType = mimeType
            )
        entries[token] = Entry(
            source = MediaSource.LocalUri(uri),
            mimeType = mimeType,
            size = uri.querySize(context) ?: uri.queryAssetFileDescriptorSize(context),
            extension = extension
        )
        return buildMediaUrl(token, extension)?.also {
            Log.d(TAG, "Registered local media token=$token url=$it mime=$mimeType")
        }
    }

    fun registerCached(uri: Uri, mimeType: String, displayName: String? = null): String? {
        if (!start()) return null

        val token = UUID.randomUUID().toString()
        val extension = mediaExtension(
            hint = displayName ?: uri.lastPathSegment,
            mimeType = mimeType
        ) ?: "mp4"
        val cachedFile = File(cacheDirectory(), "$token.$extension")

        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                cachedFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }.getOrNull()

        if (copied == null || cachedFile.length() <= 0L) {
            cachedFile.delete()
            Log.w(TAG, "Could not cache local media uri=$uri")
            return null
        }

        entries[token] = Entry(
            source = MediaSource.CachedFile(cachedFile),
            mimeType = mimeType,
            size = cachedFile.length(),
            extension = extension
        )
        return buildMediaUrl(token, extension)?.also {
            Log.d(
                TAG,
                "Registered cached media token=$token url=$it mime=$mimeType size=${cachedFile.length()}"
            )
        }
    }

    fun registerCachedFile(file: File, mimeType: String): String? {
        if (!start()) return null
        if (!file.exists() || file.length() <= 0L) return null

        val token = UUID.randomUUID().toString()
        val extension = mediaExtension(
            hint = file.name,
            mimeType = mimeType
        ) ?: "mp4"
        entries[token] = Entry(
            source = MediaSource.CachedFile(file),
            mimeType = mimeType,
            size = file.length(),
            extension = extension
        )
        return buildMediaUrl(token, extension)?.also {
            Log.d(
                TAG,
                "Registered cached file token=$token url=$it mime=$mimeType size=${file.length()}"
            )
        }
    }

    fun registerRemoteUrl(
        url: String,
        mimeType: String,
        requestHeaders: Map<String, String> = emptyMap()
    ): String? {
        if (!start()) return null

        val token = UUID.randomUUID().toString()
        val headers = requestHeaders
            .withDefaultRemoteHeaders(url)
            .sanitizedRemoteHeaders()
        val extension = mediaExtension(
            hint = url.substringBefore('?').substringBefore('#'),
            mimeType = mimeType
        )
        entries[token] = Entry(
            source = MediaSource.RemoteUrl(url, headers),
            mimeType = mimeType,
            size = null,
            extension = extension
        )
        return buildMediaUrl(token, extension)?.also {
            Log.d(
                TAG,
                "Registered remote media token=$token proxyUrl=$it upstream=$url " +
                    "mime=$mimeType upstreamHeaders=${headers.toDebugHeaderNames()}"
            )
        }
    }

    fun clear() {
        entries.values.forEach { entry ->
            (entry.source as? MediaSource.CachedFile)?.file?.delete()
        }
        entries.clear()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            executor?.execute {
                runCatching { handle(client) }
                    .onFailure {
                        if (it.isExpectedClientDisconnect()) {
                            Log.d(TAG, "Media request closed by client: ${it.message}")
                        } else {
                            Log.e(TAG, "Failed to handle media request", it)
                        }
                    }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val requestLine = reader.readLine().orEmpty()
            if (requestLine.isBlank()) return

            val headers = linkedMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) break
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers[line.substring(0, separator).trim().lowercase()] =
                        line.substring(separator + 1).trim()
                }
            }

            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0).orEmpty()
            val path = parts.getOrNull(1).orEmpty()
            Log.d(
                TAG,
                "Incoming media request method=$method path=$path " +
                    "from=${client.inetAddress?.hostAddress} " +
                    "range=${headers["range"]} " +
                    "userAgent=${headers["user-agent"]} " +
                    "privateNetwork=${headers["access-control-request-private-network"]}"
            )
            val mediaPath = path.substringAfter("/media/", missingDelimiterValue = "")
                .substringBefore("?")
                .substringBefore("/")
            val token = mediaPath.substringBefore(".")

            if (method.equals("OPTIONS", ignoreCase = true)) {
                client.writeOptionsStatus()
                return
            }

            val entry = entries[token]
            if (entry == null || token.isBlank()) {
                client.writeStatus(404, "Not Found")
                return
            }

            if (!method.equals("GET", ignoreCase = true) &&
                !method.equals("HEAD", ignoreCase = true)
            ) {
                client.writeStatus(405, "Method Not Allowed")
                return
            }

            if (entry.source is MediaSource.RemoteUrl) {
                handleRemote(client, method, entry, headers)
                return
            }

            val rangeHeader = headers["range"]
            val range = rangeHeader?.let { parseRange(it, entry.size) }
            if (rangeHeader != null && range == null) {
                client.writeRangeNotSatisfiable(entry.size)
                return
            }
            val start = range?.first ?: 0L
            val end = range?.second ?: entry.size?.minus(1)
            val contentLength = end?.let { it - start + 1 }

            val statusLine = if (range != null && entry.size != null) {
                "HTTP/1.1 206 Partial Content"
            } else {
                "HTTP/1.1 200 OK"
            }

            val output = client.getOutputStream()
            val header = buildString {
                append(statusLine).append("\r\n")
                appendCorsHeaders()
                appendStreamingHeaders()
                append("Content-Type: ").append(entry.mimeType).append("\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Connection: close\r\n")
                append("Cache-Control: no-cache\r\n")
                contentLength?.let { append("Content-Length: ").append(it).append("\r\n") }
                if (range != null && entry.size != null && end != null) {
                    append("Content-Range: bytes ")
                        .append(start)
                        .append('-')
                        .append(end)
                        .append('/')
                        .append(entry.size)
                        .append("\r\n")
                }
                append("\r\n")
            }
            output.write(header.toByteArray())

            if (!method.equals("HEAD", ignoreCase = true)) {
                val copied = when (val source = entry.source) {
                    is MediaSource.LocalUri -> {
                        context.contentResolver.openInputStream(source.uri)?.use { input ->
                            input.skipFully(start)
                            input.copyLimitedTo(output, contentLength)
                        }
                    }

                    is MediaSource.CachedFile -> {
                        RandomAccessFile(source.file, "r").use { file ->
                            file.seek(start)
                            file.copyLimitedTo(output, contentLength)
                        }
                    }

                    is MediaSource.RemoteUrl -> null
                }
                if (copied == null) {
                    Log.w(TAG, "Could not open local media stream source=${entry.source}")
                }
            }
            output.flush()
        }
    }

    private fun handleRemote(
        client: Socket,
        method: String,
        entry: Entry,
        headers: Map<String, String>
    ) {
        val source = entry.source as MediaSource.RemoteUrl
        val isKnownPlaylist = isPlaylist(source.url, entry.mimeType)
        val requestedRange = headers["range"]
        val forwardedRange = requestedRange.takeUnless { isKnownPlaylist }
        val connection = (URL(source.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = REMOTE_CONNECT_TIMEOUT_MS
            readTimeout = REMOTE_READ_TIMEOUT_MS
            requestMethod = if (method.equals("HEAD", ignoreCase = true)) "HEAD" else "GET"
            setRequestProperty("User-Agent", REMOTE_USER_AGENT)
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Encoding", "identity")
            source.requestHeaders.forEach { (name, value) ->
                setRequestProperty(name, value)
            }
            forwardedRange?.let { setRequestProperty("Range", it) }
        }

        try {
            val responseCode = connection.responseCode
            val responseMessage = connection.responseMessage ?: "OK"
            val upstreamContentType = connection.contentType
                ?.substringBefore(";")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: entry.mimeType
            Log.d(
                TAG,
                "Upstream response kind=${source.url.remoteMediaKind(upstreamContentType)} " +
                    "url=${source.url} code=$responseCode " +
                    "message=$responseMessage contentType=${connection.contentType} " +
                    "contentLength=${connection.getHeaderField("Content-Length")} " +
                    "contentRange=${connection.getHeaderField("Content-Range")} " +
                    "requestedRange=$requestedRange forwardedRange=$forwardedRange " +
                    "upstreamHeaders=${source.requestHeaders.toDebugHeaderNames()}"
            )
            val responseStream = runCatching { connection.inputStream }
                .getOrElse { connection.errorStream }

            if (responseStream == null) {
                client.writeStatus(502, "Bad Gateway")
                return
            }

            if (responseCode >= HttpURLConnection.HTTP_BAD_REQUEST) {
                val errorPreview = responseStream.readTextLimited(ERROR_BODY_LOG_LIMIT)
                Log.w(
                    TAG,
                    "Upstream error response url=${source.url} code=$responseCode " +
                        "message=$responseMessage body=${errorPreview.ifBlank { "<empty>" }}"
                )
                client.writeStatus(responseCode, responseMessage)
                return
            }

            val shouldRewritePlaylist = !method.equals("HEAD", ignoreCase = true) &&
                (isKnownPlaylist || isPlaylist(source.url, upstreamContentType))

            if (shouldRewritePlaylist) {
                responseStream.use { input ->
                    val playlist = input.bufferedReader().readText()
                    val rewrittenPlaylist = rewritePlaylist(
                        playlist,
                        source.url,
                        source.requestHeaders
                    )
                    val rewritten = rewrittenPlaylist.toByteArray()
                    Log.d(
                        TAG,
                        "Rewrote playlist url=${source.url} originalBytes=${playlist.length} " +
                            "rewrittenBytes=${rewritten.size} " +
                            "kind=${playlist.playlistKind()} urls=${playlist.playlistUrlCount()}"
                    )
                    val output = client.getOutputStream()
                    output.write(
                        buildString {
                            append("HTTP/1.1 200 OK\r\n")
                            appendCorsHeaders()
                            appendStreamingHeaders()
                            append("Content-Type: ")
                                .append(normalizePlaylistContentType(upstreamContentType))
                                .append("\r\n")
                            append("Accept-Ranges: bytes\r\n")
                            append("Connection: close\r\n")
                            append("Cache-Control: no-cache\r\n")
                            append("Content-Length: ").append(rewritten.size).append("\r\n")
                            append("\r\n")
                        }.toByteArray()
                    )
                    output.write(rewritten)
                    output.flush()
                }
                return
            }

            val output = client.getOutputStream()
            output.write(
                buildString {
                    append("HTTP/1.1 ")
                        .append(responseCode)
                        .append(' ')
                        .append(responseMessage)
                        .append("\r\n")
                    appendCorsHeaders()
                    appendStreamingHeaders()
                    append("Content-Type: ").append(upstreamContentType).append("\r\n")
                    append("Accept-Ranges: ")
                        .append(connection.getHeaderField("Accept-Ranges") ?: "bytes")
                        .append("\r\n")
                    connection.getHeaderField("Content-Range")?.let {
                        append("Content-Range: ").append(it).append("\r\n")
                    }
                    val contentLength = connection.getHeaderField("Content-Length")
                    if (!contentLength.isNullOrBlank()) {
                        append("Content-Length: ").append(contentLength).append("\r\n")
                    }
                    append("Connection: close\r\n")
                    append("Cache-Control: no-cache\r\n")
                    append("\r\n")
                }.toByteArray()
            )

            if (!method.equals("HEAD", ignoreCase = true)) {
                val copiedBytes = responseStream.use { it.copyLimitedTo(output, null) }
                Log.d(
                    TAG,
                        "Served remote media kind=${source.url.remoteMediaKind(upstreamContentType)} " +
                        "url=${source.url} code=$responseCode bytes=$copiedBytes " +
                        "contentType=$upstreamContentType requestedRange=$requestedRange " +
                        "upstreamHeaders=${source.requestHeaders.toDebugHeaderNames()}"
                )
            } else {
                responseStream.close()
                Log.d(
                    TAG,
                    "Served remote HEAD kind=${source.url.remoteMediaKind(upstreamContentType)} " +
                        "url=${source.url} code=$responseCode contentType=$upstreamContentType " +
                        "requestedRange=$requestedRange upstreamHeaders=${source.requestHeaders.toDebugHeaderNames()}"
                )
            }
            output.flush()
        } finally {
            connection.disconnect()
        }
    }

    private fun StringBuilder.appendCorsHeaders() {
        append("Access-Control-Allow-Origin: *\r\n")
        append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
        append(
            "Access-Control-Allow-Headers: " +
                "Range, Content-Type, Origin, Accept, Access-Control-Request-Private-Network\r\n"
        )
        append("Access-Control-Allow-Private-Network: true\r\n")
        append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges, Content-Type\r\n")
    }

    private fun StringBuilder.appendStreamingHeaders() {
        append("Content-Disposition: inline\r\n")
        append("transferMode.dlna.org: Streaming\r\n")
        append("contentFeatures.dlna.org: ")
        append("DLNA.ORG_OP=01;DLNA.ORG_CI=0;")
        append("DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n")
    }

    private fun parseRange(rawRange: String, size: Long?): Pair<Long, Long?>? {
        if (!rawRange.startsWith("bytes=", ignoreCase = true)) return null
        val value = rawRange.substringAfter("=").substringBefore(",").trim()
        if (!value.contains("-")) return null

        val startValue = value.substringBefore("-").trim()
        val endValue = value.substringAfter("-", "").trim()

        if (startValue.isBlank() && size != null && endValue.isNotBlank()) {
            val suffixLength = endValue.toLongOrNull()?.takeIf { it > 0 } ?: return null
            val start = max(0L, size - suffixLength)
            return start to size - 1
        }

        val start = startValue.toLongOrNull()?.takeIf { it >= 0 } ?: return null
        if (size != null && start >= size) return null

        val end = endValue.toLongOrNull()?.takeIf { it >= 0 }
        val boundedEnd = when {
            size != null && end != null -> min(end, size - 1)
            size != null && endValue.isBlank() -> size - 1
            else -> end
        }
        if (boundedEnd != null && boundedEnd < start) return null

        return start to boundedEnd
    }

    private fun Socket.writeStatus(code: Int, message: String) {
        getOutputStream().write(
            buildString {
                append("HTTP/1.1 ").append(code).append(' ').append(message).append("\r\n")
                appendCorsHeaders()
                append("Connection: close\r\n")
                append("Content-Length: 0\r\n")
                append("\r\n")
            }
                .toByteArray()
        )
    }

    private fun Socket.writeOptionsStatus() {
        getOutputStream().write(
            buildString {
                append("HTTP/1.1 204 No Content\r\n")
                appendCorsHeaders()
                append("Connection: close\r\n")
                append("Content-Length: 0\r\n")
                append("\r\n")
            }
                .toByteArray()
        )
    }

    private fun Socket.writeRangeNotSatisfiable(size: Long?) {
        getOutputStream().write(
            buildString {
                append("HTTP/1.1 416 Range Not Satisfiable\r\n")
                appendCorsHeaders()
                size?.let { append("Content-Range: bytes */").append(it).append("\r\n") }
                append("Connection: close\r\n")
                append("Content-Length: 0\r\n")
                append("\r\n")
            }
                .toByteArray()
        )
    }

    private fun buildMediaUrl(token: String, extension: String?): String? {
        val host = findLocalIpv4Address(context) ?: return null
        val port = serverSocket?.localPort ?: return null
        val suffix = extension?.let { ".$it" }.orEmpty()
        return "http://$host:$port/media/$token$suffix"
    }

    override fun close() {
        clear()
        runCatching { serverSocket?.close() }
        executor?.shutdownNow()
        serverSocket = null
        executor = null
        acceptThread = null
    }

    private data class Entry(
        val source: MediaSource,
        val mimeType: String,
        val size: Long?,
        val extension: String?
    )

    private sealed class MediaSource {
        data class LocalUri(val uri: Uri) : MediaSource()
        data class CachedFile(val file: File) : MediaSource()
        data class RemoteUrl(
            val url: String,
            val requestHeaders: Map<String, String>
        ) : MediaSource()
    }

    private fun Uri.querySize(context: Context): Long? {
        return queryOpenableColumn(context, OpenableColumns.SIZE)?.toLongOrNull()
    }

    private fun Uri.queryAssetFileDescriptorSize(context: Context): Long? {
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(this, "r")?.use { descriptor ->
                descriptor.length
                    .takeIf { it >= 0 }
                    ?: descriptor.parcelFileDescriptor.statSize.takeIf { it >= 0 }
            }
        }.getOrNull()
    }

    companion object {
        @Volatile
        private var sharedInstance: LocalMediaHttpServer? = null

        private const val REMOTE_CONNECT_TIMEOUT_MS = 15_000
        private const val REMOTE_READ_TIMEOUT_MS = 120_000
        private const val REMOTE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
        private const val PREFERRED_MEDIA_PORT = 8080
        private const val ERROR_BODY_LOG_LIMIT = 700
        private const val TAG = "LocalMediaServer"

        fun shared(context: Context): LocalMediaHttpServer {
            return sharedInstance ?: synchronized(this) {
                sharedInstance ?: LocalMediaHttpServer(context.applicationContext)
                    .also { sharedInstance = it }
            }
        }

        fun queryDisplayName(context: Context, uri: Uri): String {
            return uri.queryOpenableColumn(context, OpenableColumns.DISPLAY_NAME)
                ?: uri.lastPathSegment
                ?: "Media"
        }

        private fun LocalMediaHttpServer.cacheDirectory(): File {
            return File(context.cacheDir, LOCAL_MEDIA_CACHE_DIR).apply {
                mkdirs()
            }
        }

        private fun findLocalIpv4Address(context: Context): String? {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (connectivityManager != null) {
                connectivityManager.allNetworks
                    .asSequence()
                    .filter { network ->
                        connectivityManager.getNetworkCapabilities(network)
                            ?.isCastReachableTransport() == true
                    }
                    .mapNotNull { network ->
                        connectivityManager.getLinkProperties(network)
                            ?.linkAddresses
                            ?.mapNotNull { it.address as? Inet4Address }
                            ?.firstOrNull { it.isUsableLanAddress() }
                            ?.hostAddress
                    }
                    .firstOrNull()
                    ?.let { return it }

                connectivityManager.activeNetwork
                    ?.let { connectivityManager.getLinkProperties(it) }
                    ?.linkAddresses
                    ?.mapNotNull { it.address as? Inet4Address }
                    ?.firstOrNull { it.isUsableLanAddress() }
                    ?.hostAddress
                    ?.let { return it }
            }

            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .sortedBy { it.castInterfacePriority() }

            return interfaces
                .flatMap { networkInterface ->
                    Collections.list(networkInterface.inetAddresses)
                        .filterIsInstance<Inet4Address>()
                        .map { networkInterface to it }
                }
                .firstOrNull { (_, address) -> address.isUsableLanAddress() }
                ?.second
                ?.hostAddress
                ?: interfaces
                    .flatMap { Collections.list(it.inetAddresses) }
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress }
                    ?.hostAddress
        }

        private fun NetworkCapabilities.isCastReachableTransport(): Boolean {
            if (hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false
            return hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }

        private fun Inet4Address.isUsableLanAddress(): Boolean {
            return !isAnyLocalAddress &&
                !isLoopbackAddress &&
                !isLinkLocalAddress &&
                !isMulticastAddress &&
                isSiteLocalAddress
        }

        private fun NetworkInterface.castInterfacePriority(): Int {
            val name = name.lowercase()
            return when {
                name.startsWith("wlan") || name.startsWith("wifi") -> 0
                name.startsWith("eth") -> 1
                name.startsWith("ap") || name.startsWith("swlan") -> 2
                else -> 3
            }
        }

        private fun mediaExtension(hint: String?, mimeType: String): String? {
            val fromHint = hint
                ?.substringBeforeLast('?')
                ?.substringBeforeLast('#')
                ?.substringAfterLast('.', missingDelimiterValue = "")
                ?.lowercase()
                ?.takeIf { it.matches(Regex("[a-z0-9]{2,5}")) }
            if (fromHint != null) return fromHint

            return when (mimeType.substringBefore(";").trim().lowercase()) {
                "image/jpeg", "image/jpg" -> "jpg"
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                "video/mp4", "video/m4v", "video/quicktime" -> "mp4"
                "video/webm" -> "webm"
                "video/mp2t" -> "ts"
                "audio/mpeg" -> "mp3"
                "audio/mp4" -> "m4a"
                "audio/aac" -> "aac"
                "application/x-mpegurl", "application/vnd.apple.mpegurl" -> "m3u8"
                "application/dash+xml" -> "mpd"
                "text/vtt" -> "vtt"
                else -> null
            }
        }

        private fun String.asSafeMediaExtension(): String? {
            return lowercase().takeIf { it.matches(Regex("[a-z0-9]{2,5}")) }
        }

        private const val LOCAL_MEDIA_CACHE_DIR = "cast_media"
    }

    private fun isPlaylist(url: String, contentType: String): Boolean {
        val lowerUrl = url.lowercase()
        val lowerType = contentType.lowercase()
        return lowerUrl.contains(".m3u8") ||
            lowerUrl.contains("m3u8") ||
            lowerType.contains("mpegurl") ||
            lowerType.contains("vnd.apple.mpegurl")
    }

    private fun rewritePlaylist(
        playlist: String,
        baseUrl: String,
        requestHeaders: Map<String, String>
    ): String {
        val rewritten = playlist
            .lineSequence()
            .map { line ->
                when {
                    line.trimStart().startsWith("#") ->
                        rewriteUriAttributes(line, baseUrl, requestHeaders)
                    line.isBlank() -> line
                    else -> proxyPlaylistUrl(line, baseUrl, requestHeaders) ?: line
                }
            }
            .joinToString("\n")
        return if (playlist.endsWith("\n") && !rewritten.endsWith("\n")) "$rewritten\n" else rewritten
    }

    private fun rewriteUriAttributes(
        line: String,
        baseUrl: String,
        requestHeaders: Map<String, String>
    ): String {
        return line.replace(URI_ATTRIBUTE_REGEX) { match ->
            val quote = match.groups[1]?.value.orEmpty()
            val uri = match.groups[2]?.value.orEmpty()
            val rewritten = proxyPlaylistUrl(uri, baseUrl, requestHeaders) ?: uri
            "URI=$quote$rewritten$quote"
        }
    }

    private fun proxyPlaylistUrl(
        url: String,
        baseUrl: String,
        requestHeaders: Map<String, String>
    ): String? {
        val trimmed = url.trim()
        if (trimmed.startsWith("data:", ignoreCase = true)) return null

        val resolved = runCatching { URL(URL(baseUrl), trimmed).toString() }.getOrNull()
            ?: return null
        return registerRemoteUrl(resolved, inferMimeType(resolved), requestHeaders)
    }

    private fun inferMimeType(url: String): String {
        val lowerUrl = url.lowercase()
        val cleanUrl = lowerUrl.substringBefore("#").substringBefore("?")
        return when {
            cleanUrl.endsWith(".m3u8") || lowerUrl.contains("m3u8") -> "application/x-mpegURL"
            cleanUrl.endsWith(".mpd") -> "application/dash+xml"
            cleanUrl.endsWith(".ts") -> "video/mp2t"
            cleanUrl.endsWith(".m4s") || cleanUrl.endsWith(".cmfv") -> "video/mp4"
            cleanUrl.endsWith(".webm") -> "video/webm"
            cleanUrl.endsWith(".mp3") -> "audio/mpeg"
            cleanUrl.endsWith(".m4a") -> "audio/mp4"
            cleanUrl.endsWith(".aac") || cleanUrl.endsWith(".cmfa") -> "audio/aac"
            cleanUrl.endsWith(".vtt") -> "text/vtt"
            else -> "video/mp4"
        }
    }

    private fun String.remoteMediaKind(contentType: String): String {
        val lowerUrl = lowercase()
        val cleanUrl = lowerUrl.substringBefore("#").substringBefore("?")
        val lowerType = contentType.lowercase()
        return when {
            cleanUrl.endsWith(".m3u8") || lowerUrl.contains("m3u8") || lowerType.contains("mpegurl") -> "hls-playlist"
            cleanUrl.endsWith(".mpd") || lowerType.contains("dash+xml") -> "dash-manifest"
            cleanUrl.endsWith(".ts") || lowerType == "video/mp2t" -> "hls-segment-ts"
            cleanUrl.endsWith(".m4s") ||
                cleanUrl.endsWith(".cmfv") ||
                cleanUrl.endsWith(".cmfa") -> "fragmented-segment"
            else -> "media"
        }
    }

    private fun normalizePlaylistContentType(contentType: String): String {
        val lowerType = contentType.substringBefore(";").trim().lowercase()
        return when {
            lowerType.contains("mpegurl") -> "application/vnd.apple.mpegurl"
            lowerType.contains("dash+xml") -> "application/dash+xml"
            else -> "application/vnd.apple.mpegurl"
        }
    }

    private fun String.playlistKind(): String {
        return when {
            lineSequence().any { it.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) } -> "master"
            lineSequence().any { it.startsWith("#EXT-X-PLAYLIST-TYPE:VOD", ignoreCase = true) } -> "vod"
            lineSequence().any { it.startsWith("#EXTINF", ignoreCase = true) } -> "media"
            else -> "unknown"
        }
    }

    private fun String.playlistUrlCount(): Int {
        return lineSequence().count { line ->
            val trimmed = line.trim()
            trimmed.isNotBlank() && !trimmed.startsWith("#")
        }
    }
}

private fun Map<String, String>.sanitizedRemoteHeaders(): Map<String, String> {
    if (isEmpty()) return emptyMap()

    return mapNotNull { (rawName, rawValue) ->
        val name = rawName.trim()
        val value = rawValue.trim()
        if (name.isBlank() || value.isBlank()) return@mapNotNull null

        when (name.lowercase()) {
            "authorization",
            "cookie",
            "origin",
            "referer",
            "user-agent" -> name to value
            else -> null
        }
    }.toMap()
}

private fun Map<String, String>.withDefaultRemoteHeaders(url: String): Map<String, String> {
    val headers = linkedMapOf<String, String>()
    headers.putAll(this)

    if (!headers.containsHeader("Referer")) {
        headers["Referer"] = url
    }

    if (!headers.containsHeader("Origin")) {
        url.toOrigin()?.let { headers["Origin"] = it }
    }

    return headers
}

private fun Map<String, String>.containsHeader(name: String): Boolean {
    return keys.any { it.equals(name, ignoreCase = true) }
}

private fun String.toOrigin(): String? {
    val uri = runCatching { Uri.parse(this) }.getOrNull() ?: return null
    val scheme = uri.scheme ?: return null
    val host = uri.host ?: return null
    val port = if (uri.port > 0) ":${uri.port}" else ""
    return "$scheme://$host$port"
}

private fun Map<String, String>.toDebugHeaderNames(): String {
    if (isEmpty()) return "[]"
    return keys
        .map { name ->
            when {
                name.equals("Cookie", ignoreCase = true) -> "Cookie(redacted)"
                name.equals("Authorization", ignoreCase = true) -> "Authorization(redacted)"
                else -> name
            }
        }
        .sorted()
        .joinToString(prefix = "[", postfix = "]")
}

private val URI_ATTRIBUTE_REGEX = Regex("""URI=("?)([^",]+)\1""")

private fun Uri.queryOpenableColumn(context: Context, column: String): String? {
    val cursor: Cursor = context.contentResolver.query(
        this,
        arrayOf(column),
        null,
        null,
        null
    ) ?: return null

    cursor.use {
        if (!it.moveToFirst()) return null
        val index = it.getColumnIndex(column)
        if (index < 0) return null

        return when (it.getType(index)) {
            Cursor.FIELD_TYPE_INTEGER -> it.getLong(index).toString()
            Cursor.FIELD_TYPE_STRING -> it.getString(index)
            else -> null
        }
    }
}

private fun InputStream.skipFully(bytes: Long) {
    var remaining = bytes
    val scratch = ByteArray(DEFAULT_BUFFER_SIZE)
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
        } else {
            val read = read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
            if (read == -1) return
            remaining -= read
        }
    }
}

private fun InputStream.copyLimitedTo(
    output: OutputStream,
    limit: Long?
): Long {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var remaining = limit
    var copied = 0L

    while (true) {
        val maxRead = remaining?.let { minOf(buffer.size.toLong(), it).toInt() } ?: buffer.size
        if (maxRead <= 0) break

        val read = read(buffer, 0, maxRead)
        if (read == -1) break

        output.write(buffer, 0, read)
        copied += read
        remaining = remaining?.minus(read)
    }

    return copied
}

private fun InputStream.readTextLimited(limit: Int): String {
    return use { input ->
        val buffer = ByteArray(limit.coerceAtLeast(1))
        val read = input.read(buffer)
        if (read <= 0) {
            ""
        } else {
            String(buffer, 0, read)
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim()
        }
    }
}

private fun RandomAccessFile.copyLimitedTo(
    output: OutputStream,
    limit: Long?
): Long {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var remaining = limit
    var copied = 0L

    while (true) {
        val maxRead = remaining?.let { minOf(buffer.size.toLong(), it).toInt() } ?: buffer.size
        if (maxRead <= 0) break

        val read = read(buffer, 0, maxRead)
        if (read == -1) break

        output.write(buffer, 0, read)
        copied += read
        remaining = remaining?.minus(read)
    }

    return copied
}

private fun Throwable.isExpectedClientDisconnect(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is SocketException || current is IOException) {
            val message = current.message.orEmpty().lowercase()
            if (
                message.contains("connection reset") ||
                message.contains("broken pipe") ||
                message.contains("socket closed")
            ) {
                return true
            }
        }
        current = current.cause
    }
    return false
}
