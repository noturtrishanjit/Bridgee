package com.example.server

import android.content.Context
import android.net.Uri
import com.example.crypto.CryptoEngine
import com.example.model.PeerDevice
import com.example.model.SharedFile
import com.example.model.TransferDirection
import com.example.transfer.StorageManager
import com.example.transfer.TransferManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class BridgeHttpServer(
    private val context: Context,
    private val storageManager: StorageManager,
    private val onFilesChanged: (() -> Unit)? = null,
    private val onPeersChanged: ((Int) -> Unit)? = null,
    private val onFileReceived: ((SharedFile) -> Unit)? = null,
    private val onError: ((Throwable) -> Unit)? = null
) {
    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    @Volatile
    private var isRunning = false

    private val sharedFiles = CopyOnWriteArrayList<SharedFile>()
    private val connectedClients = ConcurrentHashMap<String, PeerDevice>()
    private val sseClients = CopyOnWriteArrayList<OutputStream>()

    var sessionToken: String = ""
        private set
    var currentPort: Int = 8080
        private set

    var deviceName: String = "Bridge Device"
    var avatarId: String = "cyber_falcon"
    var isEncryptionEnabled: Boolean = true
    var encryptionPassphrase: String = ""

    fun setFiles(files: List<SharedFile>) {
        sharedFiles.clear()
        sharedFiles.addAll(files)
        broadcastSse("{\"type\": \"files_updated\"}")
    }

    fun addFile(file: SharedFile) {
        sharedFiles.add(file)
        broadcastSse("{\"type\": \"files_updated\"}")
        onFilesChanged?.invoke()
    }

    fun getFiles(): List<SharedFile> = sharedFiles.toList()

    fun isServerActive(): Boolean {
        return isRunning && serverSocket != null && serverSocket!!.isBound && !serverSocket!!.isClosed
    }

    @Synchronized
    fun start(port: Int, token: String): Result<Int> {
        if (isServerActive()) return Result.success(currentPort)
        stop()

        sessionToken = token
        val exec = Executors.newCachedThreadPool()
        executor = exec

        val candidatePorts = listOf(port, 8080, 8088, 8888, 9000, 0).distinct()
        var boundSocket: ServerSocket? = null
        var lastException: Exception? = null

        for (candidate in candidatePorts) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("0.0.0.0", candidate), 50)
                boundSocket = ss
                currentPort = ss.localPort
                break
            } catch (e: Exception) {
                lastException = e
            }
        }

        if (boundSocket == null) {
            exec.shutdownNow()
            executor = null
            onError?.invoke(lastException ?: Exception("Could not bind HTTP server"))
            return Result.failure(lastException ?: Exception("Could not bind HTTP server"))
        }

        serverSocket = boundSocket
        isRunning = true

        exec.submit {
            try {
                while (isRunning && !boundSocket.isClosed) {
                    val clientSocket = boundSocket.accept()
                    // Set socket timeouts to prevent dead client stalls
                    clientSocket.soTimeout = 30000
                    exec.submit {
                        handleClient(clientSocket)
                    }
                }
            } catch (e: Exception) {
                if (isRunning && e !is SocketException) {
                    e.printStackTrace()
                    onError?.invoke(e)
                }
            }
        }

        return Result.success(currentPort)
    }

    @Synchronized
    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (ignored: Exception) {}
        serverSocket = null

        sseClients.forEach {
            try { it.close() } catch (ignored: Exception) {}
        }
        sseClients.clear()
        connectedClients.clear()
        onPeersChanged?.invoke(0)

        executor?.shutdownNow()
        executor = null
    }

    private fun handleClient(socket: Socket) {
        var isSseConnection = false
        try {
            val clientIp = socket.inetAddress.hostAddress ?: "Unknown"
            val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
            val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)

            // Parse headers byte-by-byte so input stream is NOT over-read by a BufferedReader
            val parsed = readHttpRequestHeaders(input) ?: return
            val requestLine = parsed.first
            val headers = parsed.second

            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0].uppercase()
            val fullPath = parts[1]

            val userAgent = headers["user-agent"] ?: "Browser"
            trackPeer(clientIp, userAgent)

            val uri = Uri.parse("http://localhost$fullPath")
            val path = uri.path ?: "/"

            // Handle CORS preflight
            if (method == "OPTIONS") {
                sendCorsPreflight(output)
                return
            }

            when {
                // Serve Web Client index.html
                method == "GET" && (path == "/" || path == "/index.html" || path.startsWith("/share")) -> {
                    serveWebClient(output)
                }

                // Serve Logo
                method == "GET" && (path == "/logo.png" || path == "/favicon.ico") -> {
                    serveLogo(output)
                }

                // API: Session info
                method == "GET" && path == "/api/session" -> {
                    serveSessionInfo(output)
                }

                // API: List of shared files
                method == "GET" && path == "/api/files" -> {
                    serveFileList(output)
                }

                // API: Server-Sent Events stream for real-time updates
                method == "GET" && path == "/api/events" -> {
                    isSseConnection = true
                    handleSseStream(output, socket)
                }

                // API: Download file (direct streaming)
                method == "GET" && path.startsWith("/api/download/") -> {
                    val fileId = path.removePrefix("/api/download/")
                    handleFileDownload(fileId, uri, headers, output, clientIp, userAgent)
                }

                // API: Upload file (direct streaming to disk)
                method == "POST" && path == "/api/upload" -> {
                    handleFileUpload(uri, headers, input, output, clientIp, userAgent)
                }

                else -> {
                    sendNotFound(output)
                }
            }
        } catch (e: Exception) {
            // Closed or interrupted
        } finally {
            if (!isSseConnection) {
                try { socket.close() } catch (ignored: Exception) {}
            }
        }
    }

    /**
     * Reads HTTP headers from InputStream byte-by-byte up to \r\n\r\n or \n\n.
     * Leaves input position at exactly byte 0 of the HTTP body.
     */
    private fun readHttpRequestHeaders(input: InputStream): Pair<String, Map<String, String>>? {
        val headerBytes = ByteArrayOutputStream()
        var prev = -1
        var prevPrev = -1
        var prevPrevPrev = -1

        while (true) {
            val b = input.read()
            if (b == -1) {
                if (headerBytes.size() == 0) return null
                break
            }
            headerBytes.write(b)
            // Stop on \r\n\r\n or \n\n
            if ((prevPrevPrev == '\r'.code && prevPrev == '\n'.code && prev == '\r'.code && b == '\n'.code) ||
                (prev == '\n'.code && b == '\n'.code)) {
                break
            }
            prevPrevPrev = prevPrev
            prevPrev = prev
            prev = b
        }

        val headerText = headerBytes.toString(Charsets.UTF_8.name())
        val lines = headerText.split("\r\n", "\n")
        if (lines.isEmpty()) return null

        val requestLine = lines[0].trim()
        val headers = mutableMapOf<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon > 0) {
                val k = line.substring(0, colon).trim().lowercase()
                val v = line.substring(colon + 1).trim()
                headers[k] = v
            }
        }
        return Pair(requestLine, headers)
    }

    private fun trackPeer(ip: String, userAgent: String) {
        val deviceName = parseDeviceName(userAgent)
        connectedClients[ip] = PeerDevice(
            id = ip,
            ip = ip,
            userAgent = userAgent,
            deviceName = deviceName
        )
        onPeersChanged?.invoke(connectedClients.size)
    }

    private fun parseDeviceName(ua: String): String {
        return when {
            ua.contains("Android", true) -> "Android Device"
            ua.contains("iPhone", true) -> "iPhone"
            ua.contains("iPad", true) -> "iPad"
            ua.contains("Macintosh", true) -> "Mac"
            ua.contains("Windows", true) -> "Windows PC"
            ua.contains("Linux", true) -> "Linux PC"
            else -> "Web Browser"
        }
    }

    private fun serveWebClient(output: OutputStream) {
        try {
            val htmlBytes = context.assets.open("web/index.html").use { it.readBytes() }
            val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${htmlBytes.size}\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n"
            output.write(header.toByteArray(Charsets.UTF_8))
            output.write(htmlBytes)
            output.flush()
        } catch (e: Exception) {
            sendNotFound(output)
        }
    }

    private fun serveLogo(output: OutputStream) {
        try {
            val logoBytes = context.assets.open("web/logo.png").use { it.readBytes() }
            val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: image/png\r\n" +
                    "Content-Length: ${logoBytes.size}\r\n" +
                    "Cache-Control: public, max-age=86400\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n"
            output.write(header.toByteArray(Charsets.UTF_8))
            output.write(logoBytes)
            output.flush()
        } catch (e: Exception) {
            sendNotFound(output)
        }
    }

    private fun serveSessionInfo(output: OutputStream) {
        val json = JSONObject().apply {
            put("sessionId", sessionToken)
            put("port", currentPort)
            put("deviceName", deviceName)
            put("avatarId", avatarId)
            put("isEncrypted", isEncryptionEnabled)
            put("fileCount", sharedFiles.size)
            put("connectedClients", connectedClients.size)
        }.toString()

        sendJsonResponse(output, 200, json)
    }

    private fun serveFileList(output: OutputStream) {
        val filesArray = JSONArray()
        for (file in sharedFiles) {
            val obj = JSONObject().apply {
                put("id", file.id)
                put("name", file.name)
                put("size", file.size)
                put("mimeType", file.mimeType)
            }
            filesArray.put(obj)
        }
        val root = JSONObject().apply {
            put("files", filesArray)
            put("count", sharedFiles.size)
        }.toString()

        sendJsonResponse(output, 200, root)
    }

    private fun handleSseStream(output: OutputStream, socket: Socket) {
        try {
            val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/event-stream\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "Connection: keep-alive\r\n" +
                    "Access-Control-Allow-Origin: *\r\n\r\n"
            output.write(header.toByteArray(Charsets.UTF_8))
            output.write("data: {\"type\": \"connected\"}\n\n".toByteArray(Charsets.UTF_8))
            output.flush()

            sseClients.add(output)

            while (isRunning && !socket.isClosed) {
                Thread.sleep(15000)
                try {
                    output.write(": ping\n\n".toByteArray(Charsets.UTF_8))
                    output.flush()
                } catch (e: Exception) {
                    break
                }
            }
        } catch (ignored: Exception) {
        } finally {
            sseClients.remove(output)
        }
    }

    private fun broadcastSse(message: String) {
        val payload = "data: $message\n\n".toByteArray(Charsets.UTF_8)
        val iterator = sseClients.iterator()
        while (iterator.hasNext()) {
            val client = iterator.next()
            try {
                client.write(payload)
                client.flush()
            } catch (e: Exception) {
                sseClients.remove(client)
            }
        }
    }

    private fun handleFileDownload(
        fileId: String,
        uri: Uri,
        headers: Map<String, String>,
        output: OutputStream,
        clientIp: String,
        userAgent: String
    ) {
        val file = sharedFiles.firstOrNull { it.id == fileId }
        if (file == null) {
            sendNotFound(output)
            return
        }

        val totalSize = file.size
        val isRaw = uri.getQueryParameter("raw") == "1" || uri.getQueryParameter("plain") == "1"
        val shouldEncrypt = isEncryptionEnabled && !isRaw

        val transferId = UUID.randomUUID().toString()

        TransferManager.startTransfer(
            id = transferId,
            fileId = file.id,
            fileName = file.name,
            totalBytes = totalSize,
            direction = TransferDirection.UPLOAD,
            peerAddress = clientIp,
            peerDeviceName = parseDeviceName(userAgent),
            isEncrypted = shouldEncrypt
        )

        try {
            val inputStream = storageManager.openInputStream(file)
            if (inputStream == null) {
                TransferManager.failTransfer(transferId, "Cannot open file source")
                sendNotFound(output)
                return
            }

            inputStream.use { inStream ->
                if (shouldEncrypt) {
                    val statusLine = "HTTP/1.1 200 OK\r\n"
                    val sb = StringBuilder(statusLine)
                    sb.append("Content-Type: application/octet-stream\r\n")
                    sb.append("Content-Disposition: attachment; filename=\"${file.name.replace("\"", "")}\"\r\n")
                    sb.append("X-Encrypted: true\r\n")
                    sb.append("X-Cipher: AES-256-GCM\r\n")
                    sb.append("Access-Control-Allow-Origin: *\r\n")
                    sb.append("Access-Control-Expose-Headers: X-Encrypted, X-Cipher\r\n")
                    sb.append("Connection: close\r\n\r\n")

                    output.write(sb.toString().toByteArray(Charsets.UTF_8))
                    output.flush()

                    val effectiveKey = if (encryptionPassphrase.isNotBlank()) encryptionPassphrase else sessionToken
                    CryptoEngine.encryptStream(
                        input = inStream,
                        output = output,
                        passphrase = effectiveKey,
                        originalSize = totalSize,
                        onProgress = { processed ->
                            TransferManager.updateProgress(transferId, processed)
                        }
                    )

                    if (TransferManager.isCancelled(transferId)) {
                        TransferManager.cancelTransfer(transferId)
                    } else {
                        TransferManager.completeTransfer(transferId)
                    }
                } else {
                    var startByte = 0L
                    var endByte = if (totalSize > 0) totalSize - 1 else 0L

                    val rangeHeader = headers["range"]
                    val isRange = rangeHeader != null && rangeHeader.startsWith("bytes=")
                    if (isRange && totalSize > 0) {
                        val rangeValue = rangeHeader!!.removePrefix("bytes=").trim()
                        val rangeParts = rangeValue.split("-")
                        startByte = rangeParts[0].toLongOrNull() ?: 0L
                        if (rangeParts.size > 1 && rangeParts[1].isNotEmpty()) {
                            endByte = (rangeParts[1].toLongOrNull() ?: endByte).coerceAtMost(totalSize - 1)
                        }
                    }

                    val bytesToTransfer = if (totalSize > 0) (endByte - startByte + 1).coerceAtLeast(0L) else totalSize

                    if (startByte > 0) {
                        inStream.skip(startByte)
                    }

                    val statusLine = if (isRange) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n"
                    val sb = StringBuilder(statusLine)
                    sb.append("Content-Type: ${file.mimeType}\r\n")
                    if (bytesToTransfer > 0) {
                        sb.append("Content-Length: $bytesToTransfer\r\n")
                    }
                    sb.append("Content-Disposition: attachment; filename=\"${file.name.replace("\"", "")}\"\r\n")
                    sb.append("Accept-Ranges: bytes\r\n")
                    if (isRange && totalSize > 0) {
                        sb.append("Content-Range: bytes $startByte-$endByte/$totalSize\r\n")
                    }
                    sb.append("Access-Control-Allow-Origin: *\r\n")
                    sb.append("Connection: close\r\n\r\n")

                    output.write(sb.toString().toByteArray(Charsets.UTF_8))
                    output.flush()

                    val buffer = ByteArray(64 * 1024)
                    var bytesSent = 0L

                    while (bytesSent < bytesToTransfer) {
                        if (TransferManager.isCancelled(transferId)) break
                        while (TransferManager.isPaused(transferId)) {
                            Thread.sleep(200)
                        }

                        val maxToRead = (bytesToTransfer - bytesSent).coerceAtMost(buffer.size.toLong()).toInt()
                        val read = inStream.read(buffer, 0, maxToRead)
                        if (read <= 0) break

                        output.write(buffer, 0, read)
                        bytesSent += read
                        TransferManager.updateProgress(transferId, startByte + bytesSent)
                    }
                    output.flush()

                    if (TransferManager.isCancelled(transferId)) {
                        TransferManager.cancelTransfer(transferId)
                    } else {
                        TransferManager.completeTransfer(transferId)
                    }
                }
            }
        } catch (e: Exception) {
            TransferManager.failTransfer(transferId, e.message ?: "Transfer interrupted")
        }
    }

    private fun handleFileUpload(
        uri: Uri,
        headers: Map<String, String>,
        input: InputStream,
        output: OutputStream,
        clientIp: String,
        userAgent: String
    ) {
        val contentLength = headers["content-length"]?.toLongOrNull() ?: -1L
        var fileName = uri.getQueryParameter("filename")
        if (fileName.isNullOrEmpty()) {
            fileName = headers["x-filename"]?.let {
                try { URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { it }
            }
        }
        if (fileName.isNullOrEmpty()) {
            fileName = "upload_${System.currentTimeMillis()}"
        }

        val destinationFile = storageManager.createDestinationFile(fileName)
        val transferId = UUID.randomUUID().toString()

        TransferManager.startTransfer(
            id = transferId,
            fileId = destinationFile.name,
            fileName = destinationFile.name,
            totalBytes = if (contentLength > 0) contentLength else 0L,
            direction = TransferDirection.DOWNLOAD,
            peerAddress = clientIp,
            peerDeviceName = parseDeviceName(userAgent)
        )

        try {
            FileOutputStream(destinationFile).use { fileOut ->
                val buffer = ByteArray(64 * 1024)
                var totalRead = 0L

                if (contentLength > 0) {
                    while (totalRead < contentLength) {
                        if (TransferManager.isCancelled(transferId)) break
                        val toRead = (contentLength - totalRead).coerceAtMost(buffer.size.toLong()).toInt()
                        val read = input.read(buffer, 0, toRead)
                        if (read <= 0) break
                        fileOut.write(buffer, 0, read)
                        totalRead += read
                        TransferManager.updateProgress(transferId, totalRead)
                    }
                } else {
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        if (TransferManager.isCancelled(transferId)) break
                        fileOut.write(buffer, 0, read)
                        totalRead += read
                        TransferManager.updateProgress(transferId, totalRead)
                    }
                }
                fileOut.flush()
            }

            if (TransferManager.isCancelled(transferId)) {
                destinationFile.delete()
                TransferManager.cancelTransfer(transferId)
                sendJsonResponse(output, 400, "{\"error\": \"Upload cancelled\"}")
            } else {
                TransferManager.completeTransfer(transferId)
                storageManager.notifyMediaStore(destinationFile)

                val mime = storageManager.guessMimeType(destinationFile.name)
                val newFile = SharedFile(
                    id = UUID.randomUUID().toString(),
                    name = destinationFile.name,
                    size = destinationFile.length(),
                    mimeType = mime,
                    filePath = destinationFile.absolutePath,
                    isUploadedByPeer = true
                )
                onFileReceived?.invoke(newFile)
                addFile(newFile)

                sendJsonResponse(output, 200, "{\"success\": true, \"name\": \"${destinationFile.name}\"}")
            }
        } catch (e: Exception) {
            destinationFile.delete()
            TransferManager.failTransfer(transferId, e.message ?: "Upload failed")
            sendJsonResponse(output, 500, "{\"error\": \"${e.message}\"}")
        }
    }

    private fun sendJsonResponse(output: OutputStream, statusCode: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $statusCode OK\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun sendCorsPreflight(output: OutputStream) {
        val header = "HTTP/1.1 204 No Content\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Content-Type, Range, X-Filename\r\n" +
                "Access-Control-Max-Age: 86400\r\n" +
                "Connection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun sendNotFound(output: OutputStream) {
        val msg = "404 Not Found"
        val header = "HTTP/1.1 404 Not Found\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: ${msg.length}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n" + msg
        output.write(header.toByteArray(Charsets.UTF_8))
        output.flush()
    }
}
