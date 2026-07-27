package com.tvchromecast.screenmirroringplus.media

import android.content.Context
import android.database.Cursor
import android.net.ConnectivityManager
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStream
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
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

        val socket = runCatching { ServerSocket(0) }.getOrNull() ?: return false
        serverSocket = socket
        executor = Executors.newCachedThreadPool()
        acceptThread = Thread({ acceptLoop(socket) }, "LocalMediaHttpServer").apply {
            isDaemon = true
            start()
        }
        return true
    }

    fun register(uri: Uri, mimeType: String): String? {
        if (!start()) return null

        val token = UUID.randomUUID().toString()
        entries[token] = Entry(
            source = MediaSource.LocalUri(uri),
            mimeType = mimeType,
            size = uri.querySize(context)
        )
        return buildMediaUrl(token)?.also {
            Log.d(TAG, "Registered local media token=$token url=$it mime=$mimeType")
        }
    }

    fun registerRemoteUrl(
        url: String,
        mimeType: String,
        requestHeaders: Map<String, String> = emptyMap()
    ): String? {
        if (!start()) return null

        val token = UUID.randomUUID().toString()
        entries[token] = Entry(
            source = MediaSource.RemoteUrl(url, requestHeaders.sanitizedRemoteHeaders()),
            mimeType = mimeType,
            size = null
        )
        return buildMediaUrl(token)?.also {
            Log.d(TAG, "Registered remote media token=$token proxyUrl=$it upstream=$url mime=$mimeType")
        }
    }

    fun clear() {
        entries.clear()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            executor?.execute {
                runCatching { handle(client) }
                    .onFailure { Log.e(TAG, "Failed to handle media request", it) }
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
            Log.d(TAG, "Incoming media request method=$method path=$path")
            val token = path.substringAfter("/media/", missingDelimiterValue = "")
                .substringBefore("?")

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

            val range = headers["range"]?.let { parseRange(it, entry.size) }
            val start = range?.first ?: 0L
            val end = range?.second ?: entry.size?.minus(1)
            val contentLength = end?.let { it - start + 1 }

            if (entry.source is MediaSource.RemoteUrl) {
                handleRemote(client, method, entry, headers)
                return
            }

            val statusLine = if (range != null && entry.size != null) {
                "HTTP/1.1 206 Partial Content"
            } else {
                "HTTP/1.1 200 OK"
            }

            val output = client.getOutputStream()
            val header = buildString {
                append(statusLine).append("\r\n")
                appendCorsHeaders()
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
                val source = entry.source as MediaSource.LocalUri
                context.contentResolver.openInputStream(source.uri)?.use { input ->
                    input.skipFully(start)
                    input.copyLimitedTo(output, contentLength)
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
            headers["range"]?.let { setRequestProperty("Range", it) }
        }

        try {
            val responseCode = connection.responseCode
            val responseMessage = connection.responseMessage ?: "OK"
            Log.d(
                TAG,
                "Upstream response url=${source.url} code=$responseCode " +
                    "message=$responseMessage contentType=${connection.contentType}"
            )
            val responseStream = runCatching { connection.inputStream }
                .getOrElse { connection.errorStream }

            if (responseStream == null) {
                client.writeStatus(502, "Bad Gateway")
                return
            }

            val upstreamContentType = connection.contentType
                ?.substringBefore(";")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: entry.mimeType

            val shouldRewritePlaylist = !method.equals("HEAD", ignoreCase = true) &&
                isPlaylist(source.url, upstreamContentType)

            if (shouldRewritePlaylist) {
                responseStream.use { input ->
                    val playlist = input.bufferedReader().readText()
                    val rewritten = rewritePlaylist(
                        playlist,
                        source.url,
                        source.requestHeaders
                    ).toByteArray()
                    Log.d(
                        TAG,
                        "Rewrote playlist url=${source.url} originalBytes=${playlist.length} " +
                            "rewrittenBytes=${rewritten.size}"
                    )
                    val output = client.getOutputStream()
                    output.write(
                        buildString {
                            append("HTTP/1.1 200 OK\r\n")
                            appendCorsHeaders()
                            append("Content-Type: ").append(upstreamContentType).append("\r\n")
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
                responseStream.use { it.copyLimitedTo(output, null) }
            } else {
                responseStream.close()
            }
            output.flush()
        } finally {
            connection.disconnect()
        }
    }

    private fun StringBuilder.appendCorsHeaders() {
        append("Access-Control-Allow-Origin: *\r\n")
        append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
        append("Access-Control-Allow-Headers: Range, Content-Type, Origin, Accept\r\n")
        append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n")
    }

    private fun parseRange(rawRange: String, size: Long?): Pair<Long, Long?>? {
        if (!rawRange.startsWith("bytes=", ignoreCase = true)) return null
        val value = rawRange.removePrefix("bytes=").substringBefore(",")
        val startValue = value.substringBefore("-").trim()
        val endValue = value.substringAfter("-", "").trim()

        if (startValue.isBlank() && size != null && endValue.isNotBlank()) {
            val suffixLength = endValue.toLongOrNull() ?: return null
            val start = max(0L, size - suffixLength)
            return start to size - 1
        }

        val start = startValue.toLongOrNull() ?: return null
        val end = endValue.toLongOrNull()
        val boundedEnd = if (size != null && end != null) min(end, size - 1) else end
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

    private fun buildMediaUrl(token: String): String? {
        val host = findLocalIpv4Address(context) ?: return null
        val port = serverSocket?.localPort ?: return null
        return "http://$host:$port/media/$token"
    }

    override fun close() {
        entries.clear()
        runCatching { serverSocket?.close() }
        executor?.shutdownNow()
        serverSocket = null
        executor = null
        acceptThread = null
    }

    private data class Entry(
        val source: MediaSource,
        val mimeType: String,
        val size: Long?
    )

    private sealed class MediaSource {
        data class LocalUri(val uri: Uri) : MediaSource()
        data class RemoteUrl(
            val url: String,
            val requestHeaders: Map<String, String>
        ) : MediaSource()
    }

    private fun Uri.querySize(context: Context): Long? {
        return queryOpenableColumn(context, OpenableColumns.SIZE)?.toLongOrNull()
    }

    companion object {
        private const val REMOTE_CONNECT_TIMEOUT_MS = 15_000
        private const val REMOTE_READ_TIMEOUT_MS = 30_000
        private const val REMOTE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
        private const val TAG = "LocalMediaServer"

        fun queryDisplayName(context: Context, uri: Uri): String {
            return uri.queryOpenableColumn(context, OpenableColumns.DISPLAY_NAME)
                ?: uri.lastPathSegment
                ?: "Media"
        }

        private fun findLocalIpv4Address(context: Context): String? {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = connectivityManager?.activeNetwork
            if (connectivityManager != null && activeNetwork != null) {
                connectivityManager.getLinkProperties(activeNetwork)
                    ?.linkAddresses
                    ?.mapNotNull { it.address as? Inet4Address }
                    ?.firstOrNull { !it.isLoopbackAddress }
                    ?.hostAddress
                    ?.let { return it }
            }

            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            return interfaces
                .flatMap { Collections.list(it.inetAddresses) }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        }
    }

    private fun isPlaylist(url: String, contentType: String): Boolean {
        val lowerUrl = url.lowercase()
        val lowerType = contentType.lowercase()
        return lowerUrl.contains(".m3u8") ||
            lowerType.contains("mpegurl") ||
            lowerType.contains("vnd.apple.mpegurl")
    }

    private fun rewritePlaylist(
        playlist: String,
        baseUrl: String,
        requestHeaders: Map<String, String>
    ): String {
        return playlist
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
        val cleanUrl = url.lowercase().substringBefore("#").substringBefore("?")
        return when {
            cleanUrl.endsWith(".m3u8") -> "application/x-mpegURL"
            cleanUrl.endsWith(".mpd") -> "application/dash+xml"
            cleanUrl.endsWith(".ts") -> "video/mp2t"
            cleanUrl.endsWith(".webm") -> "video/webm"
            cleanUrl.endsWith(".mp3") -> "audio/mpeg"
            cleanUrl.endsWith(".m4a") -> "audio/mp4"
            else -> "video/mp4"
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
    output: java.io.OutputStream,
    limit: Long?
) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var remaining = limit

    while (true) {
        val maxRead = remaining?.let { minOf(buffer.size.toLong(), it).toInt() } ?: buffer.size
        if (maxRead <= 0) break

        val read = read(buffer, 0, maxRead)
        if (read == -1) break

        output.write(buffer, 0, read)
        remaining = remaining?.minus(read)
    }
}
