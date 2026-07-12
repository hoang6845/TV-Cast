package com.example.base.media

import android.content.Context
import android.database.Cursor
import android.net.ConnectivityManager
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStream
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
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

        val host = findLocalIpv4Address(context) ?: return null
        val port = serverSocket?.localPort ?: return null
        val token = UUID.randomUUID().toString()
        entries[token] = Entry(
            uri = uri,
            mimeType = mimeType,
            size = uri.querySize(context)
        )
        return "http://$host:$port/media/$token"
    }

    fun clear() {
        entries.clear()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            executor?.execute { handle(client) }
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
            val token = path.substringAfter("/media/", missingDelimiterValue = "")
                .substringBefore("?")
            val entry = entries[token]
            if (entry == null || token.isBlank()) {
                client.writeStatus(404, "Not Found")
                return
            }

            val range = headers["range"]?.let { parseRange(it, entry.size) }
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
                context.contentResolver.openInputStream(entry.uri)?.use { input ->
                    input.skipFully(start)
                    input.copyLimitedTo(output, contentLength)
                }
            }
            output.flush()
        }
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
            "HTTP/1.1 $code $message\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
                .toByteArray()
        )
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
        val uri: Uri,
        val mimeType: String,
        val size: Long?
    )

    private fun Uri.querySize(context: Context): Long? {
        return queryOpenableColumn(context, OpenableColumns.SIZE)?.toLongOrNull()
    }

    companion object {
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
}

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
