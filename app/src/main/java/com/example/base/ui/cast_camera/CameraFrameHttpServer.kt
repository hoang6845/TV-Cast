package com.example.base.ui.cast_camera

import android.content.Context
import android.net.ConnectivityManager
import java.io.Closeable
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraFrameHttpServer(
    private val context: Context,
    private val frameProvider: () -> ByteArray?
) : Closeable {

    private val token = UUID.randomUUID().toString()
    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    private var acceptThread: Thread? = null

    val isRunning: Boolean
        get() = serverSocket?.isClosed == false

    fun start(): String? {
        if (!isRunning) {
            val socket = runCatching { ServerSocket(0) }.getOrNull() ?: return null
            serverSocket = socket
            executor = Executors.newCachedThreadPool()
            acceptThread = Thread({ acceptLoop(socket) }, "CameraFrameHttpServer").apply {
                isDaemon = true
                start()
            }
        }

        val host = findLocalIpv4Address(context) ?: return null
        val port = serverSocket?.localPort ?: return null
        return "http://$host:$port/camera/$token.jpg"
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            executor?.execute {
                runCatching { handle(client) }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            val input = client.getInputStream().bufferedReader()
            val requestLine = input.readLine().orEmpty()
            if (requestLine.isBlank()) return

            while (true) {
                val line = input.readLine() ?: break
                if (line.isBlank()) break
            }

            val path = requestLine.split(" ").getOrNull(1).orEmpty().substringBefore("?")
            if (path != "/camera/$token.jpg") {
                client.writeStatus(404, "Not Found")
                return
            }

            val frame = frameProvider()
            if (frame == null) {
                client.writeStatus(503, "Camera Frame Not Ready")
                return
            }

            val output = client.getOutputStream()
            val header = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: image/jpeg\r\n")
                append("Content-Length: ").append(frame.size).append("\r\n")
                append("Cache-Control: no-store, no-cache, must-revalidate\r\n")
                append("Pragma: no-cache\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            output.write(header.toByteArray())
            output.write(frame)
            output.flush()
        }
    }

    private fun Socket.writeStatus(code: Int, message: String) {
        getOutputStream().write(
            "HTTP/1.1 $code $message\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
                .toByteArray()
        )
    }

    override fun close() {
        runCatching { serverSocket?.close() }
        executor?.shutdownNow()
        serverSocket = null
        executor = null
        acceptThread = null
    }

    companion object {
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
