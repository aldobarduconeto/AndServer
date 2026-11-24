package com.andserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.Context
import android.app.PendingIntent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import java.io.DataInputStream
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

data class ServerSnapshot(
    val running: Boolean, val port: Int?, val requests: Long, val clients: Int,
    val files: Int, val startedAt: Long, val recentEvents: List<String>, val type: String,
    val bytesReceived: Long, val bytesSent: Long,
    val networkScope: String = "local", val publicIp: String? = null
)

private data class ServerConfig(
    val port: Int, val type: String, val filters: Set<String>, val workers: Int,
    val password: String, val networkScope: String = "local"
)
private data class WebSocketPeer(val socket: Socket, val output: java.io.BufferedOutputStream, val lock: Any = Any())
private data class WebSocketFrame(val opcode: Int, val fin: Boolean, val payload: ByteArray)

class ServerService : Service() {
    private val binder = LocalBinder()
    @Volatile private var server: LanHttpServer? = null
    @Volatile var activePort: Int? = null
        private set
    @Volatile private var generation = 0
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var nsdManager: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null

    fun snapshot(): ServerSnapshot = server?.snapshot() ?: ServerSnapshot(
        false, null, 0, 0,
        File(filesDir, "hosted").listFiles()?.count { it.isFile } ?: 0,
        0, emptyList(), "web", 0, 0,
        getSharedPreferences("andserver", MODE_PRIVATE).getString("network_scope", "local") ?: "local",
        null
    )

    inner class LocalBinder : Binder() { fun service() = this@ServerService }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopServer(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            ACTION_START -> {
                launchServer(ServerConfig(
                    intent.getIntExtra(EXTRA_PORT, DEFAULT_TLS_PORT).coerceIn(1024, 65535),
                    intent.getStringExtra(EXTRA_TYPE) ?: "web",
                    intent.getStringArrayListExtra(EXTRA_FILTERS)?.toSet() ?: setOf("all"),
                    intent.getIntExtra(EXTRA_WORKERS, 8).coerceIn(2, 16),
                    intent.getStringExtra(EXTRA_PASSWORD).orEmpty(),
                    intent.getStringExtra(EXTRA_NETWORK_SCOPE) ?: "local"
                ))
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { stopServer(); unregisterDiscovery(); super.onDestroy() }
    private fun stopServer() { generation++; server?.stop(); server = null; activePort = null; unregisterDiscovery() }

    private fun launchServer(config: ServerConfig) {
        val settings = getSharedPreferences("andserver", MODE_PRIVATE)
        settings.edit().putInt("port", config.port).putString("type", config.type).putStringSet("filters", config.filters)
            .putInt("workers", config.workers).putString("password", config.password)
            .putString("network_scope", config.networkScope).apply()

        startForeground(NOTIFICATION_ID, notification(config.port, config.networkScope))
        server?.stop()
        val ownGeneration = ++generation
        val next = LanHttpServer(
            config.port, File(filesDir, "hosted").apply { mkdirs() }, config.type,
            config.filters, config.workers, config.password, config.networkScope,
            TlsIdentity.sslContext(), TlsIdentity.certificatePem(),
            onConfigure = { updated -> mainHandler.postDelayed({ if (generation == ownGeneration) launchServer(updated) }, 300) },
            onExit = { if (generation == ownGeneration) { server = null; activePort = null; stopSelf() } }
        )
        server = next
        activePort = config.port
        next.start()
        advertise(config.port)
    }

    private fun advertise(port: Int) {
        unregisterDiscovery()
        try {
            val manager = (getSystemService(Context.NSD_SERVICE) as? NsdManager) ?: return
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) { }
                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) { nsdListener = null }
                override fun onServiceUnregistered(info: NsdServiceInfo) { }
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) { }
            }
            val info = NsdServiceInfo().apply { serviceName = "AndServer"; serviceType = "_https._tcp."; setPort(port) }
            nsdManager = manager; nsdListener = listener
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) { nsdListener = null }
    }

    private fun unregisterDiscovery() {
        val listener = nsdListener ?: return
        try { nsdManager?.unregisterService(listener) } catch (_: Exception) { }
        nsdListener = null
    }

    private fun notification(port: Int, scope: String): Notification {
        val channelId = "andserver"
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "Servidor AndServer", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId) else Notification.Builder(this)
        val flags = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(this, 2, Intent(this, ServerService::class.java).setAction(ACTION_STOP), flags)
        val scopeLabel = if (scope == "internet") "Internet (Público)" else "Rede Local (LAN)"
        return builder.setContentTitle("AndServer Ativo · $scopeLabel")
            .setContentText("HTTPS na porta $port")
            .setSmallIcon(R.mipmap.ic_launcher).setOngoing(true).setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "Parar", stop).build()
    }

    companion object {
        const val ACTION_START = "com.andserver.START"
        const val ACTION_STOP = "com.andserver.STOP"
        const val EXTRA_PORT = "port"
        const val EXTRA_TYPE = "server_type"
        const val EXTRA_FILTERS = "file_filters"
        const val EXTRA_WORKERS = "workers"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_NETWORK_SCOPE = "network_scope"
        const val DEFAULT_TLS_PORT = 8443
        const val NOTIFICATION_ID = 41
    }
}

private class LanHttpServer(
    private val port: Int, private val root: File, private val type: String,
    private val filters: Set<String>, private val workers: Int, private val password: String,
    private val networkScope: String,
    private val tlsContext: javax.net.ssl.SSLContext, private val certificatePem: String,
    private val onConfigure: (ServerConfig) -> Unit, private val onExit: () -> Unit
) {
    private val running = AtomicBoolean(false)
    private val requests = AtomicLong(0)
    private val activeClients = AtomicInteger(0)
    private val startedAt = AtomicLong(0)
    private val bytesReceived = AtomicLong(0)
    private val bytesSent = AtomicLong(0)
    private val events = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val logFile = File(root.parentFile, "access.log")
    private val openSockets = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    private val fileCount = AtomicInteger(0)
    private val fileScanner = ScheduledThreadPoolExecutor(1, java.util.concurrent.ThreadFactory { task -> Thread(task, "AndServer-FileIndex").apply { isDaemon = true } })
    private val clients = ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(64))
    private val webSocketPeers = java.util.concurrent.ConcurrentHashMap.newKeySet<WebSocketPeer>()
    private val webSocketToken = android.util.Base64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
    @Volatile private var socket: ServerSocket? = null
    @Volatile private var publicIp: String? = null

    // Proteção contra Força Bruta (Brute-Force Shield)
    private data class IpBlock(var count: Int = 0, var blockedUntil: Long = 0)
    private val failedLogins = java.util.concurrent.ConcurrentHashMap<String, IpBlock>()

    // Links de Compartilhamento Temporários com Expiração
    private data class ShareLink(val relPath: String, val expiresAt: Long)
    private val activeShares = java.util.concurrent.ConcurrentHashMap<String, ShareLink>()

    init {
        fileScanner.scheduleWithFixedDelay({ try { fileCount.set(root.walkTopDown().count { it.isFile }) } catch (_: Exception) { } }, 0, 5, TimeUnit.SECONDS)
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        startedAt.set(System.currentTimeMillis())
        val scopeName = if (networkScope == "internet") "Toda a Internet" else "Rede Local (LAN)"
        log("Servidor iniciado · $type · porta $port · Modo: $scopeName")
        if (networkScope == "internet") {
            fetchPublicIp()
        }

        Thread {
            try {
                val tlsListener = tlsContext.serverSocketFactory.createServerSocket() as javax.net.ssl.SSLServerSocket
                tlsListener.enabledProtocols = tlsListener.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
                tlsListener.reuseAddress = true
                tlsListener.bind(InetSocketAddress(port), 64)
                tlsListener.use { listener ->
                    socket = listener
                    while (running.get()) {
                        val client = listener.accept()
                        openSockets.add(client)
                        try { clients.execute { handle(client) } }
                        catch (_: RejectedExecutionException) {
                            try { client.getOutputStream().write("HTTP/1.1 503 Service Unavailable\r\nRetry-After: 2\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray()) } catch (_: Exception) { }
                            try { client.close() } catch (_: Exception) { }
                            openSockets.remove(client)
                            log("Conexao recusada · limite de clientes atingido")
                        }
                    }
                }
            } catch (e: Exception) { if (running.get()) log("Falha ao aceitar conexao · ${e.javaClass.simpleName}") }
            finally { running.set(false); clients.shutdownNow(); onExit() }
        }.apply { name = "AndServer-Accept"; isDaemon = true; start() }
    }

    private fun fetchPublicIp() {
        Thread {
            try {
                val conn = java.net.URL("https://api.ipify.org").openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                if (conn.responseCode == 200) {
                    val ip = conn.inputStream.bufferedReader().readText().trim()
                    publicIp = ip
                    log("IP Público externo detectado · $ip")
                }
            } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
    }

    fun stop() {
        running.set(false)
        try { socket?.close() } catch (_: Exception) { }
        openSockets.forEach { try { it.close() } catch (_: Exception) { } }
        openSockets.clear()
        clients.shutdownNow()
        fileScanner.shutdownNow()
    }

    fun snapshot() = ServerSnapshot(
        running.get(), port, requests.get(), activeClients.get() + clients.queue.size, fileCount.get(),
        startedAt.get(), synchronized(events) { events.toList().reversed() }, type, bytesReceived.get(), bytesSent.get(),
        networkScope, publicIp
    )

    private fun log(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val safeMessage = message.replace("\r", " ").replace("\n", " ")
        synchronized(events) {
            events.add(0, "$time  $safeMessage"); while (events.size > 30) events.removeAt(events.lastIndex)
            try {
                if (logFile.length() > 1024L * 1024) {
                    File(logFile.parentFile, "access.log.1").delete()
                    logFile.renameTo(File(logFile.parentFile, "access.log.1"))
                }
                logFile.appendText("$time  $safeMessage\n", Charsets.UTF_8)
            } catch (_: Exception) { }
        }
        val message = "{\"type\":\"event\",\"message\":\"${json("$time  $safeMessage")}\"}"
        webSocketPeers.toList().forEach { peer -> try { sendWebSocketFrame(peer.output, peer.lock, 0x1, message.toByteArray(Charsets.UTF_8)) } catch (_: Exception) { webSocketPeers.remove(peer); try { peer.socket.close() } catch (_: Exception) { } } }
    }

    private fun allowed(name: String): Boolean {
        if (name.startsWith(".") || name.endsWith(".upload")) return false
        if ("all" in filters) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        val group = when (ext) {
            "jpg", "jpeg", "png", "gif", "webp", "svg", "bmp", "heic" -> "images"
            "pdf", "txt", "md", "doc", "docx", "odt", "rtf", "xls", "xlsx", "csv", "ppt", "pptx", "html", "htm", "css", "js", "mjs", "json", "xml", "wasm", "woff", "woff2" -> "documents"
            "mp3", "wav", "ogg", "m4a", "aac", "flac" -> "audio"
            "mp4", "m4v", "mov", "mkv", "webm", "avi" -> "video"
            "zip", "7z", "rar", "tar", "gz" -> "archives"
            else -> "other"
        }
        return group in filters
    }

    private fun isLocalOrPrivateAddress(addr: java.net.InetAddress): Boolean {
        if (addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress || addr.isAnyLocalAddress) return true
        val host = addr.hostAddress ?: return false
        return host == "127.0.0.1" || host == "::1" || host.startsWith("192.168.") || host.startsWith("10.") ||
               (host.startsWith("172.") && try { val second = host.split(".")[1].toInt(); second in 16..31 } catch (_: Exception) { false })
    }

    private fun handle(s: Socket) {
        activeClients.incrementAndGet()
        s.use {
            try {
                s.soTimeout = 15000
                val input = s.getInputStream().buffered()
                val output = s.getOutputStream().buffered()
                val first = input.readLineAscii(8192) ?: return
                val parts = first.split(' ')
                if (parts.size < 3 || !parts[2].startsWith("HTTP/1.")) { response(output, 400, "text/plain", "Requisicao invalida"); return }
                val method = parts[0].uppercase()
                val target = parts[1]
                val headers = linkedMapOf<String, String>()
                var headerBytes = 0
                while (true) {
                    val line = input.readLineAscii(8192) ?: break
                    headerBytes += line.length
                    if (headerBytes > 32_768) { response(output, 431, "text/plain", "Cabecalhos muito grandes"); return }
                    if (line.isEmpty()) break
                    val split = line.indexOf(':')
                    if (split > 0) headers[line.substring(0, split).trim().lowercase()] = line.substring(split + 1).trim()
                }
                val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
                val rawPath = target.substringBefore('?')
                val path = try { URLDecoder.decode(rawPath.replace("+", "%2B"), "UTF-8") } catch (_: Exception) { response(output, 400, "text/plain", "Caminho invalido"); return }
                val clientIp = s.inetAddress.hostAddress ?: "desconhecido"

                // 1. Verificação de Escopo de Rede (Local vs. Internet)
                if (networkScope == "local" && !isLocalOrPrivateAddress(s.inetAddress)) {
                    log("Acesso externo bloqueado (Modo Rede Local) · $clientIp")
                    response(output, 403, "application/json; charset=utf-8", "{\"error\":\"Acesso restrito a rede local. Altere para o modo 'Toda a Internet' para conexoes externas.\"}")
                    return
                }

                // 2. Proteção contra Força Bruta (Brute-Force Shield) & Rate Limiting
                val now = System.currentTimeMillis()
                val block = failedLogins[clientIp]
                if (block != null && block.blockedUntil > now) {
                    val remaining = (block.blockedUntil - now) / 1000
                    output.write("HTTP/1.1 429 Too Many Requests\r\nContent-Type: application/json; charset=utf-8\r\nRetry-After: $remaining\r\nConnection: close\r\n\r\n{\"error\":\"Muitas tentativas invalidas. IP bloqueado temporariamente por mais $remaining segundos.\"}".toByteArray())
                    output.flush()
                    return
                }

                requests.incrementAndGet()
                log("$method $path · $clientIp")

                val websocketRequest = method == "GET" && path == "/ws"
                val websocketAuthorized = websocketRequest && queryParameter(target, "token") == webSocketToken
                val isShareDownload = method in setOf("GET", "HEAD") && path.startsWith("/share/") && path.length > 7
                val isQrRequest = method in setOf("GET", "HEAD") && path == "/api/qrcode"

                // 3. Autenticação HTTP Basic com proteção anti-força bruta
                if (password.isNotEmpty() && !websocketAuthorized && !isShareDownload && !isQrRequest) {
                    val authHeader = headers["authorization"]
                    val isAuthed = authorized(authHeader)
                    if (!isAuthed) {
                        val entry = failedLogins.compute(clientIp) { _, existing ->
                            val b = existing ?: IpBlock()
                            b.count++
                            if (b.count >= 5) {
                                b.blockedUntil = now + (5 * 60 * 1000)
                                log("🛡️ IP bloqueado por forca bruta (5 falhas): $clientIp")
                            }
                            b
                        }
                        if (entry != null && entry.blockedUntil > now) {
                            output.write("HTTP/1.1 429 Too Many Requests\r\nContent-Type: application/json; charset=utf-8\r\nRetry-After: 300\r\nConnection: close\r\n\r\n{\"error\":\"Limite de tentativas excedido. IP bloqueado por 5 minutos.\"}".toByteArray())
                            output.flush()
                            return
                        }
                        output.write("HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"AndServer\", charset=\"UTF-8\"\r\nCache-Control: no-store\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        output.flush()
                        return
                    } else {
                        failedLogins.remove(clientIp)
                    }
                }

                if (headers["transfer-encoding"]?.contains("chunked", true) == true) { response(output, 501, "application/json", "{\"error\":\"Transfer-Encoding chunked nao suportado; envie Content-Length\"}"); return }
                if (headers["expect"]?.equals("100-continue", true) == true) { output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray()); output.flush() }
                if (method == "OPTIONS") { response(output, 204, "text/plain", ""); return }
                if (method !in setOf("GET", "HEAD", "PUT", "POST", "DELETE", "MKCOL")) { response(output, 405, "application/json", "{\"error\":\"Metodo HTTP nao suportado\"}"); return }
                val isHead = method == "HEAD"
                val apiPath = path.removePrefix("/api/files/").takeIf { path.startsWith("/api/files/") }
                val legacyUpload = path.removePrefix("/upload/").takeIf { path.startsWith("/upload/") }

                when {
                    websocketRequest -> {
                        val key = headers["sec-websocket-key"]
                        val validKey = try { key != null && android.util.Base64.decode(key, android.util.Base64.DEFAULT).size == 16 } catch (_: Exception) { false }
                        val connectionUpgrade = headers["connection"]?.split(',')?.any { it.trim().equals("upgrade", true) } == true
                        val validUpgrade = headers["upgrade"]?.equals("websocket", ignoreCase = true) == true
                        if (!validUpgrade || headers["sec-websocket-version"] != "13" || !connectionUpgrade || !validKey) {
                            response(output, 426, "application/json", "{\"error\":\"Handshake WebSocket RFC 6455 invalido\"}")
                        } else {
                            val accept = android.util.Base64.encodeToString(
                                java.security.MessageDigest.getInstance("SHA-1").digest((key!! + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)),
                                android.util.Base64.NO_WRAP
                            )
                            output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray(Charsets.US_ASCII)); output.flush()
                            val peer = WebSocketPeer(s, output)
                            webSocketPeers.add(peer)
                            log("WebSocket conectado · $clientIp")
                            try { webSocketLoop(peer, input) } finally { webSocketPeers.remove(peer) }
                        }
                    }
                    isShareDownload -> {
                        // 4. Download via Link de Compartilhamento Temporário
                        val token = path.removePrefix("/share/").trim('/')
                        val share = activeShares[token]
                        if (share == null || System.currentTimeMillis() > share.expiresAt) {
                            activeShares.remove(token)
                            response(output, 410, "text/plain; charset=utf-8", "Link de compartilhamento expirado ou invalido.", head = isHead)
                        } else {
                            val file = resolvePath(share.relPath)
                            if (file == null || !file.isFile || !allowed(file.name)) {
                                response(output, 404, "text/plain", "Arquivo compartilhado nao encontrado", head = isHead)
                            } else {
                                log("Download via link temporario · ${share.relPath} · $clientIp")
                                sendFile(output, file, attachment = true, head = isHead, rangeHeader = headers["range"], ifNoneMatch = headers["if-none-match"])
                            }
                        }
                    }
                    isQrRequest -> {
                        // 5. Endpoint de QR Code SVG dinâmico
                        val textParam = queryParameter(target, "text") ?: "https://${s.inetAddress.hostAddress}:$port"
                        val svg = QrGenerator.toSvg(QrGenerator.encode(textParam))
                        response(output, 200, "image/svg+xml; charset=utf-8", svg, head = isHead)
                    }
                    method in setOf("GET", "HEAD") && (path == "/api/zip" || path.startsWith("/api/zip/")) -> {
                        // 6. Download em Lote como ZIP
                        val queryRel = queryParameter(target, "path")?.trim('/') ?: path.removePrefix("/api/zip").trim('/')
                        val folder = if (queryRel.isEmpty()) root else resolvePath(queryRel)
                        if (folder == null || !folder.exists()) {
                            response(output, 404, "application/json", "{\"error\":\"Pasta nao encontrada\"}")
                        } else {
                            val zipName = if (folder == root) "andserver-arquivos.zip" else "${folder.name}.zip"
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nContent-Disposition: attachment; filename=\"$zipName\"\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                            if (!isHead) {
                                java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(output)).use { zos ->
                                    val base = if (folder.isDirectory) folder else folder.parentFile
                                    folder.walkTopDown().filter { it.isFile && allowed(it.name) }.forEach { f ->
                                        val entryName = f.relativeTo(base).invariantSeparatorsPath
                                        zos.putNextEntry(java.util.zip.ZipEntry(entryName))
                                        f.inputStream().use { it.copyTo(zos) }
                                        zos.closeEntry()
                                    }
                                    zos.finish()
                                }
                            }
                            output.flush()
                            log("Download ZIP gerado · $zipName")
                        }
                    }
                    method == "POST" && path == "/api/share" -> {
                        // 7. Geração de Link Temporário com Expiração
                        if (contentLength <= 0 || contentLength > 4096) {
                            response(output, 400, "application/json", "{\"error\":\"Requisicao de compartilhamento invalida\"}"); return
                        }
                        val body = ByteArray(contentLength.toInt())
                        var readCount = 0
                        while (readCount < body.size) {
                            val r = input.read(body, readCount, body.size - readCount)
                            if (r < 0) break
                            readCount += r
                        }
                        val json = try { org.json.JSONObject(String(body, Charsets.UTF_8)) } catch (_: Exception) { org.json.JSONObject() }
                        val rel = json.optString("path").trim('/')
                        val hours = json.optInt("hours", 24).coerceIn(1, 168)
                        val file = resolvePath(rel)
                        if (file == null || !file.isFile || !allowed(file.name)) {
                            response(output, 404, "application/json", "{\"error\":\"Arquivo nao encontrado\"}")
                        } else {
                            val token = android.util.Base64.encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) }, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                            val expiresAt = System.currentTimeMillis() + (hours.toLong() * 3600 * 1000)
                            activeShares[token] = ShareLink(rel, expiresAt)
                            log("Link temporario criado · $rel (expira em ${hours}h)")
                            response(output, 201, "application/json", "{\"token\":\"$token\",\"url\":\"/share/$token\",\"expiresAt\":$expiresAt,\"hours\":$hours}")
                        }
                    }
                    method in setOf("GET", "HEAD") && (path == "/" || path == "/index.html") -> {
                        if (type == "site") {
                            val wwwRoot = File(root, "www")
                            val siteIndex = File(wwwRoot, "index.html")
                            if (siteIndex.isFile) {
                                sendFile(output, siteIndex, attachment = false, head = isHead, rangeHeader = headers["range"], ifNoneMatch = headers["if-none-match"])
                            } else {
                                response(output, 200, "text/html; charset=utf-8", page(), head = isHead)
                            }
                        } else {
                            response(output, 200, "text/html; charset=utf-8", page(), head = isHead)
                        }
                    }
                    method in setOf("GET", "HEAD") && path == "/health" -> response(output, 200, "application/json; charset=utf-8", "{\"status\":\"ok\"}", head = isHead)
                    method in setOf("GET", "HEAD") && path == "/api/certificate" -> response(output, 200, "application/x-pem-file", certificatePem, head = isHead)
                    method in setOf("GET", "HEAD") && path == "/api/ws-token" -> response(output, 200, "application/json; charset=utf-8", "{\"token\":\"$webSocketToken\"}", head = isHead)
                    method in setOf("GET", "HEAD") && path == "/api/config" -> {
                        val json = org.json.JSONObject().put("type", type).put("port", port).put("workers", workers)
                            .put("filters", org.json.JSONArray(filters.toList())).put("passwordEnabled", password.isNotEmpty())
                            .put("networkScope", networkScope).put("publicIp", publicIp ?: "")
                            .put("tls", true).put("websocket", true)
                        response(output, 200, "application/json; charset=utf-8", json.toString(), head = isHead)
                    }
                    method == "PUT" && path == "/api/config" -> {
                        if (contentLength < 1 || contentLength > 8192) { response(output, 413, "application/json", "{\"error\":\"Configuracao deve ter ate 8 KB\"}"); return }
                        val body = ByteArray(contentLength.toInt())
                        var offset = 0
                        while (offset < body.size) {
                            val count = input.read(body, offset, body.size - offset)
                            if (count < 0) { response(output, 400, "application/json", "{\"error\":\"Corpo incompleto\"}"); return }
                            offset += count
                        }
                        try {
                            val json = org.json.JSONObject(String(body, Charsets.UTF_8))
                            val newPort = json.optInt("port", port)
                            val newType = json.optString("type", type)
                            val newWorkers = json.optInt("workers", workers)
                            val newScope = json.optString("networkScope", networkScope).takeIf { it in setOf("local", "internet") } ?: networkScope
                            val array = json.optJSONArray("filters") ?: org.json.JSONArray(filters.toList())
                            val newFilters = (0 until array.length()).map { array.getString(it) }.toSet()
                            val newPassword = json.optString("password", password)
                            if (newPort !in 1024..65535 || newType !in setOf("site", "web", "files", "api") || newWorkers !in setOf(2, 4, 8, 12, 16) ||
                                newFilters.any { it !in setOf("all", "images", "documents", "audio", "video", "archives") } ||
                                newPassword.isNotEmpty() && newPassword.length < 8) {
                                response(output, 400, "application/json", "{\"error\":\"Revise porta, perfil, conexoes, filtros e senha\"}")
                            } else {
                                val finalFilters = if ("all" in newFilters) setOf("all") else newFilters
                                response(output, 202, "application/json", "{\"restarting\":true,\"port\":$newPort}")
                                onConfigure(ServerConfig(newPort, newType, finalFilters, newWorkers, newPassword, newScope))
                            }
                        } catch (_: Exception) { response(output, 400, "application/json", "{\"error\":\"JSON de configuracao invalido\"}") }
                    }
                    method in setOf("GET", "HEAD") && path == "/api/status" -> {
                        val body = """{"name":"AndServer","scheme":"https","websocket":"wss","type":"$type","port":$port,"networkScope":"$networkScope","publicIp":"${publicIp ?: ""}","requests":${requests.get()},"clients":${activeClients.get() + clients.queue.size},"files":${fileCount.get()},"bytesReceived":${bytesReceived.get()},"bytesSent":${bytesSent.get()},"uptimeSeconds":${((System.currentTimeMillis() - startedAt.get()) / 1000).coerceAtLeast(0)}}"""
                        response(output, 200, "application/json; charset=utf-8", body, head = isHead)
                    }
                    method in setOf("GET", "HEAD") && path == "/api/logs" -> {
                        val body = (try { logFile.readLines(Charsets.UTF_8).takeLast(500) } catch (_: Exception) { synchronized(events) { events.toList() } }).joinToString(",") { "\"${json(it)}\"" }
                        response(output, 200, "application/json; charset=utf-8", "[$body]", head = isHead)
                    }
                    method in setOf("GET", "HEAD") && path == "/api/logs.txt" -> {
                        val body = try { logFile.readText(Charsets.UTF_8) } catch (_: Exception) { "" }
                        response(output, 200, "text/plain; charset=utf-8", body, head = isHead)
                    }
                    method in setOf("GET", "HEAD") && path == "/api/system" -> {
                        val runtime = Runtime.getRuntime()
                        val body = org.json.JSONObject().put("device", android.os.Build.MODEL).put("android", android.os.Build.VERSION.RELEASE)
                            .put("processors", runtime.availableProcessors()).put("memoryMaxBytes", runtime.maxMemory())
                            .put("memoryFreeBytes", runtime.freeMemory()).put("memoryTotalBytes", runtime.totalMemory()).toString()
                        response(output, 200, "application/json; charset=utf-8", body, head = isHead)
                    }
                    method in setOf("GET", "HEAD") && path == "/api/files" -> {
                        val files = root.walkTopDown().drop(1).filter { it.isDirectory || it.isFile && allowed(it.name) }.map {
                            val rel = it.relativeTo(root).invariantSeparatorsPath
                            val url = if (it.isDirectory) "/public/${urlPath(rel)}/" else "/files/${urlPath(rel)}"
                            "{\"name\":\"${json(rel)}\",\"directory\":${it.isDirectory},\"size\":${if (it.isFile) it.length() else 0},\"modified\":${it.lastModified()},\"url\":\"$url\"}"
                        }.toList().joinToString(",")
                        response(output, 200, "application/json; charset=utf-8", "[$files]", head = isHead)
                    }
                    method == "MKCOL" && apiPath != null -> {
                        val dir = resolvePath(apiPath)
                        if (dir == null) response(output, 400, "application/json", "{\"error\":\"Caminho invalido\"}")
                        else if (dir.exists()) response(output, 405, "application/json", "{\"error\":\"O caminho ja existe\"}")
                        else if (!dir.mkdirs()) response(output, 500, "application/json", "{\"error\":\"Nao foi possivel criar a pasta\"}")
                        else { log("Pasta criada · $apiPath"); response(output, 201, "application/json", "{\"created\":\"${json(apiPath)}\"}") }
                    }
                    method == "DELETE" && apiPath != null -> {
                        val item = resolvePath(apiPath)
                        if (item == null || !item.exists() || item == root) response(output, 404, "application/json", "{\"error\":\"Arquivo ou pasta nao encontrado\"}")
                        else if (!item.deleteRecursively()) response(output, 500, "application/json", "{\"error\":\"Nao foi possivel apagar\"}")
                        else { log("Removido · $apiPath"); response(output, 200, "application/json", "{\"deleted\":\"${json(apiPath)}\"}") }
                    }
                    method == "PUT" && (apiPath != null || legacyUpload != null) -> {
                        if (contentLength <= 0) { response(output, 411, "application/json", "{\"error\":\"Content-Length obrigatorio\"}"); return }
                        if (contentLength > 100L * 1024 * 1024) { response(output, 413, "application/json", "{\"error\":\"Limite de upload: 100 MB\"}"); return }
                        val rel = apiPath ?: legacyUpload!!
                        val targetFile = resolvePath(rel)
                        if (targetFile == null || rel.isBlank() || targetFile == root) { response(output, 400, "application/json", "{\"error\":\"Caminho invalido\"}"); return }
                        if (!allowed(targetFile.name)) { response(output, 415, "application/json", "{\"error\":\"Tipo de arquivo bloqueado pela configuracao\"}"); return }
                        if (!targetFile.parentFile.exists() && !targetFile.parentFile.mkdirs()) { response(output, 500, "application/json", "{\"error\":\"Nao foi possivel criar a pasta pai\"}"); return }
                        val existed = targetFile.exists()
                        val tempFile = File(targetFile.parentFile, ".${targetFile.name}.${java.util.UUID.randomUUID()}.upload")
                        try {
                            tempFile.outputStream().use { out ->
                                val buffer = ByteArray(32 * 1024); var remaining = contentLength
                                while (remaining > 0) {
                                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                    if (read < 0) throw java.io.EOFException()
                                    out.write(buffer, 0, read); remaining -= read; bytesReceived.addAndGet(read.toLong())
                                }
                                out.fd.sync()
                            }
                            try {
                                java.nio.file.Files.move(tempFile.toPath(), targetFile.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                                java.nio.file.Files.move(tempFile.toPath(), targetFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                            }
                        } finally { if (tempFile.exists()) tempFile.delete() }
                        log("${if (existed) "Arquivo atualizado" else "Upload concluido"} · $rel · $contentLength bytes")
                        response(output, if (existed) 200 else 201, "application/json", "{\"saved\":\"${json(rel)}\",\"bytes\":$contentLength}")
                    }
                    method in setOf("GET", "HEAD") && (path.startsWith("/files/") || type in setOf("web", "site") && path.startsWith("/public/")) -> {
                        val attachment = path.startsWith("/files/")
                        val prefix = if (attachment) "/files/" else "/public/"
                        val rel = path.removePrefix(prefix).trimEnd('/').ifEmpty { "index.html" }
                        val file = resolvePath(rel)
                        if (file == null) response(output, 400, "text/plain", "Caminho invalido", head = isHead)
                        else if (file.isDirectory) {
                            val index = File(file, "index.html")
                            if (index.isFile && allowed(index.name)) sendFile(output, index, attachment, isHead, headers["range"], headers["if-none-match"])
                            else if (!attachment) response(output, 200, "text/html; charset=utf-8", directoryPage(file), head = isHead)
                            else response(output, 404, "text/plain", "Pasta sem arquivo index", head = isHead)
                        } else if (!file.isFile || !allowed(file.name)) response(output, 404, "text/plain", "Arquivo nao encontrado", head = isHead)
                        else sendFile(output, file, attachment, isHead, headers["range"], headers["if-none-match"])
                    }
                    method in setOf("GET", "HEAD") && type == "site" -> {
                        val relPath = path.trimStart('/').ifEmpty { "index.html" }
                        val targetFile = resolvePath("www/$relPath")
                        val siteIndex = resolvePath("www/index.html")
                        when {
                            targetFile != null && targetFile.isFile && allowed(targetFile.name) -> {
                                sendFile(output, targetFile, attachment = false, head = isHead, rangeHeader = headers["range"], ifNoneMatch = headers["if-none-match"])
                            }
                            targetFile != null && targetFile.isDirectory -> {
                                val nestedIndex = File(targetFile, "index.html")
                                if (nestedIndex.isFile && allowed(nestedIndex.name)) {
                                    sendFile(output, nestedIndex, attachment = false, head = isHead, rangeHeader = headers["range"], ifNoneMatch = headers["if-none-match"])
                                } else if (siteIndex != null && siteIndex.isFile) {
                                    sendFile(output, siteIndex, attachment = false, head = isHead, rangeHeader = headers["range"], ifNoneMatch = headers["if-none-match"])
                                } else {
                                    response(output, 404, "text/html; charset=utf-8", "<h1>404 Não Encontrado</h1><p>Arquivo ou pasta não encontrado no site.</p>", head = isHead)
                                }
                            }
                            siteIndex != null && siteIndex.isFile && !path.contains('.') -> {
                                // Fallback para roteamento Single Page Application (React/Vue/Angular)
                                sendFile(output, siteIndex, attachment = false, head = isHead, rangeHeader = headers["range"], ifNoneMatch = headers["if-none-match"])
                            }
                            else -> response(output, 404, "text/html; charset=utf-8", "<h1>404 Arquivo Não Encontrado</h1><p>Recurso não encontrado no site hospedado.</p>", head = isHead)
                        }
                    }
                    else -> response(output, 404, "application/json; charset=utf-8", "{\"error\":\"Rota nao encontrada\"}")
                }
            } catch (e: Exception) { log("Falha de requisicao · ${e.javaClass.simpleName}") }
            finally { activeClients.decrementAndGet(); openSockets.remove(s) }
        }
    }

    private fun queryParameter(target: String, name: String): String? = target.substringAfter('?', "").split('&').firstNotNullOfOrNull { item ->
        val parts = item.split('=', limit = 2)
        if (parts.size == 2 && parts[0] == name) try { URLDecoder.decode(parts[1], "UTF-8") } catch (_: Exception) { null } else null
    }

    private fun webSocketLoop(peer: WebSocketPeer, input: java.io.BufferedInputStream) {
        peer.socket.soTimeout = 30_000
        val data = DataInputStream(input)
        var awaitingPong = false
        try {
            while (!peer.socket.isClosed && running.get()) {
                val frame = readWebSocketFrame(data) ?: break
                when (frame.opcode) {
                    0x8 -> { sendWebSocketFrame(peer.output, peer.lock, 0x8, ByteArray(0)); break }
                    0x9 -> sendWebSocketFrame(peer.output, peer.lock, 0xa, frame.payload)
                    0xa -> awaitingPong = false
                    0x1 -> {
                        val text = String(frame.payload, Charsets.UTF_8)
                        if (text == "ping") sendWebSocketFrame(peer.output, peer.lock, 0x1, "{\"type\":\"pong\"}".toByteArray(Charsets.UTF_8))
                    }
                }
            }
        } catch (_: SocketTimeoutException) {
            if (awaitingPong) { try { peer.socket.close() } catch (_: Exception) { } }
            else { awaitingPong = true; try { sendWebSocketFrame(peer.output, peer.lock, 0x9, ByteArray(0)) } catch (_: Exception) { } }
        } catch (_: Exception) { }
        finally { try { peer.socket.close() } catch (_: Exception) { } }
    }

    private fun readWebSocketFrame(input: DataInputStream): WebSocketFrame? {
        val b0 = input.read()
        if (b0 < 0) return null
        val fin = (b0 and 0x80) != 0
        val opcode = b0 and 0x0f
        val b1 = input.read()
        if (b1 < 0) return null
        val masked = (b1 and 0x80) != 0
        var len = (b1 and 0x7f).toLong()
        if (len == 126L) len = input.readUnsignedShort().toLong()
        else if (len == 127L) len = input.readLong()
        if (len < 0 || len > 1024 * 1024) return null
        val mask = if (masked) ByteArray(4).also { input.readFully(it) } else null
        val payload = ByteArray(len.toInt())
        input.readFully(payload)
        if (mask != null) {
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        return WebSocketFrame(opcode, fin, payload)
    }

    private fun sendWebSocketFrame(output: java.io.BufferedOutputStream, lock: Any, opcode: Int, payload: ByteArray) {
        synchronized(lock) {
            output.write(0x80 or (opcode and 0x0f))
            when {
                payload.size < 126 -> output.write(payload.size)
                payload.size <= 65_535 -> { output.write(126); output.write(payload.size ushr 8); output.write(payload.size) }
                else -> {
                    output.write(127)
                    for (shift in 56 downTo 0 step 8) output.write((payload.size.toLong() ushr shift).toInt())
                }
            }
            output.write(payload); output.flush()
        }
    }

    private fun authorized(header: String?): Boolean {
        if (header == null || !header.startsWith("Basic ", true)) return false
        val credentials = try { String(android.util.Base64.decode(header.substringAfter(' '), android.util.Base64.DEFAULT), Charsets.UTF_8) } catch (_: Exception) { return false }
        val supplied = credentials.substringAfter(':', "")
        return java.security.MessageDigest.isEqual(password.toByteArray(), supplied.toByteArray())
    }
    private fun resolvePath(raw: String): File? {
        val relative = raw.trimStart('/')
        if (relative.isBlank() || relative.split('/').any { it == ".." || it == "." || it.isEmpty() } || relative.contains('\\') || relative.contains('\u0000')) return if (relative.isEmpty()) root else null
        val base = root.canonicalFile
        val result = File(base, relative).canonicalFile
        return result.takeIf { it.path.startsWith(base.path + File.separator) || it == base }
    }
    private fun urlPath(s: String) = s.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    private fun json(s: String) = buildString {
        s.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }
    private fun httpDate(timestamp: Long = System.currentTimeMillis()): String = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("GMT")
    }.format(java.util.Date(timestamp))

    private fun response(out: java.io.OutputStream, code: Int, type: String, body: String, head: Boolean = false) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val label = when (code) {
            200 -> "OK"; 201 -> "Created"; 202 -> "Accepted"; 204 -> "No Content"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"
            405 -> "Method Not Allowed"; 410 -> "Gone"; 411 -> "Length Required"; 413 -> "Payload Too Large"; 415 -> "Unsupported Media Type"
            416 -> "Range Not Satisfiable"; 429 -> "Too Many Requests"; 431 -> "Request Header Fields Too Large"; 500 -> "Internal Server Error"; else -> "Error"
        }
        out.write("HTTP/1.1 $code $label\r\nDate: ${httpDate()}\r\nServer: AndServer/1.0\r\nCache-Control: no-store\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nAllow: GET, HEAD, PUT, POST, DELETE, MKCOL, OPTIONS\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
        if (!head && code != 204) { out.write(bytes); bytesSent.addAndGet(bytes.size.toLong()) }
        out.flush()
    }

    private fun sendFile(out: java.io.OutputStream, file: File, attachment: Boolean, head: Boolean, rangeHeader: String?, ifNoneMatch: String?) {
        val size = file.length()
        val etag = "\"${size.toString(16)}-${file.lastModified().toString(16)}\""
        if (ifNoneMatch?.split(',')?.any { it.trim() == etag || it.trim() == "*" } == true) {
            out.write("HTTP/1.1 304 Not Modified\r\nDate: ${httpDate()}\r\nETag: $etag\r\nCache-Control: public, max-age=0, must-revalidate\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8)); out.flush(); return
        }
        var start = 0L; var end = (size - 1).coerceAtLeast(0); var partial = false
        if (rangeHeader != null) {
            val match = Regex("bytes=(\\d*)-(\\d*)").matchEntire(rangeHeader.trim())
            if (match == null || size == 0L) {
                out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$size\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush(); return
            }
            val a = match.groupValues[1]; val b = match.groupValues[2]
            if (a.isEmpty()) { val suffix = b.toLongOrNull() ?: 0; start = (size - suffix.coerceAtMost(size)).coerceAtLeast(0) }
            else start = a.toLongOrNull() ?: size
            end = if (b.isEmpty()) size - 1 else (b.toLongOrNull() ?: -1).coerceAtMost(size - 1)
            if (start >= size || end < start) {
                out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$size\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush(); return
            }
            partial = true
        }
        val length = if (size == 0L) 0 else end - start + 1
        val name = file.name.replace("\"", "_").replace("\r", "_").replace("\n", "_")
        val disposition = if (attachment) "attachment" else "inline"
        val status = if (partial) "206 Partial Content" else "200 OK"
        out.write("HTTP/1.1 $status\r\nDate: ${httpDate()}\r\nServer: AndServer/1.0\r\nContent-Type: ${mimeType(file.name)}\r\nContent-Length: $length\r\nAccept-Ranges: bytes\r\nETag: $etag\r\nLast-Modified: ${httpDate(file.lastModified())}\r\nCache-Control: public, max-age=0, must-revalidate\r\nContent-Disposition: $disposition; filename=\"$name\"; filename*=UTF-8''${urlPath(file.name)}\r\n${if (partial) "Content-Range: bytes $start-$end/$size\r\n" else ""}Connection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
        if (!head && length > 0) java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(start); val buffer = ByteArray(32 * 1024); var remaining = length
            while (remaining > 0) { val read = raf.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt()); if (read < 0) break; out.write(buffer, 0, read); bytesSent.addAndGet(read.toLong()); remaining -= read }
        }
        out.flush()
    }

    private fun mimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"; "css" -> "text/css; charset=utf-8"; "js", "mjs" -> "text/javascript; charset=utf-8"
        "json", "map" -> "application/json; charset=utf-8"; "xml" -> "application/xml; charset=utf-8"; "txt", "md", "csv" -> "text/plain; charset=utf-8"
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"; "avif" -> "image/avif"; "svg" -> "image/svg+xml"; "ico" -> "image/x-icon"
        "pdf" -> "application/pdf"; "doc" -> "application/msword"; "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"; "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"; "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "zip" -> "application/zip"; "gz" -> "application/gzip"; "7z" -> "application/x-7z-compressed"; "wasm" -> "application/wasm"
        "woff" -> "font/woff"; "woff2" -> "font/woff2"; "ttf" -> "font/ttf"; "otf" -> "font/otf"
        "mp3" -> "audio/mpeg"; "wav" -> "audio/wav"; "ogg" -> "audio/ogg"; "mp4" -> "video/mp4"; "webm" -> "video/webm"; "mov" -> "video/quicktime"
        else -> "application/octet-stream"
    }

    private fun directoryPage(dir: File): String {
        val rel = dir.relativeTo(root).invariantSeparatorsPath
        val items = dir.listFiles()?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }).orEmpty()
            .filter { it.isDirectory || allowed(it.name) }.joinToString("") { file ->
                val child = if (rel.isEmpty()) file.name else "$rel/${file.name}"
                val suffix = if (file.isDirectory) "/" else ""
                val icon = if (file.isDirectory) "📁" else when (file.extension.lowercase()) {
                    "jpg", "jpeg", "png", "gif", "webp", "svg" -> "🖼️"
                    "mp3", "wav", "ogg", "aac", "flac" -> "🎵"
                    "mp4", "mkv", "webm", "mov" -> "🎬"
                    "zip", "rar", "7z", "tar", "gz" -> "📦"
                    "pdf", "doc", "docx", "txt", "md" -> "📄"
                    else -> "📎"
                }
                val sizeText = if (file.isFile) formatSize(file.length()) else "Pasta"
                """<div class="file-row"><a class="file-link" href="/public/${urlPath(child)}$suffix"><span class="icon">$icon</span><span class="name">${html(file.name)}$suffix</span></a><span class="file-size">$sizeText</span></div>"""
            }
        val parent = if (rel.contains('/')) rel.substringBeforeLast('/') else ""
        val up = if (rel.isNotEmpty()) """<a class="btn-back" href="/public/${urlPath(parent)}">← Pasta anterior</a>""" else ""
        return """<!doctype html><html lang="pt-br"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="theme-color" content="#0b0f17"><title>Arquivos · AndServer</title><link rel="icon" type="image/svg+xml" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'%3E%3Crect width='32' height='32' rx='8' fill='%230b0f17'/%3E%3Cpath d='M8 10h16v4H8zm0 8h16v4H8z' fill='%231a253c' stroke='%23263450' stroke-width='.5'/%3E%3Ccircle cx='21' cy='12' r='1' fill='%2310b981'/%3E%3Ccircle cx='21' cy='20' r='1' fill='%2338bdf8'/%3E%3Cpath d='M11 6a8 8 0 0 1 10 0' stroke='%2338bdf8' stroke-width='1.5' stroke-linecap='round' fill='none'/%3E%3C/svg%3E"><style>
            :root{--bg:#0b0f17;--card:#131b2c;--border:#263450;--primary:#10b981;--text:#f8fafc;--text-muted:#94a3b8}
            *{box-sizing:border-box;margin:0;padding:0}body{font:15px -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;background:var(--bg);color:var(--text);padding:32px 18px 60px;max-width:840px;margin:0 auto}
            header{margin-bottom:24px}h1{font-size:24px;font-weight:700;margin-bottom:8px}.path-sub{color:var(--text-muted);font-size:13px;word-break:break-all}
            .card{background:var(--card);border:1px solid var(--border);border-radius:16px;padding:8px 16px;box-shadow:0 4px 20px -2px rgba(0,0,0,0.4)}
            .file-row{display:flex;align-items:center;justify-content:space-between;padding:12px 6px;border-bottom:1px solid rgba(255,255,255,0.06);gap:12px}
            .file-row:last-child{border-bottom:none}.file-link{display:flex;align-items:center;gap:10px;color:var(--text);text-decoration:none;font-weight:500;min-width:0;overflow:hidden}
            .file-link:hover .name{color:var(--primary)}.file-link .icon{font-size:18px}.file-link .name{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
            .file-size{color:var(--text-muted);font-size:13px;flex-shrink:0}.btn-back{display:inline-flex;align-items:center;gap:6px;color:var(--primary);text-decoration:none;font-weight:600;font-size:13px;margin-bottom:16px}
            @media(max-width:600px){body{padding:20px 14px}h1{font-size:20px}}
        </style></head><body><header>$up<h1>Explorador de Arquivos</h1><div class="path-sub">/${html(rel)}</div></header><main class="card">${if (items.isEmpty()) """<div style="padding:24px;text-align:center;color:var(--text-muted)">Nenhum arquivo nesta pasta.</div>""" else items}</main></body></html>"""
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun html(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    private fun page(): String {
        val typeLabel = when (type) { "site" -> "Site Web Hospedado (www)"; "files" -> "Servidor de arquivos"; "api" -> "Servidor de API"; else -> "Servidor web" }
        val filterLabels = if ("all" in filters) "Todos os formatos" else filters.mapNotNull {
            when (it) { "images" -> "Imagens"; "documents" -> "Documentos"; "audio" -> "Áudio"; "video" -> "Vídeo"; "archives" -> "Compactados"; else -> null }
        }.joinToString(" · ").ifEmpty { "Nenhum formato permitido" }
        val protection = if (password.isEmpty()) "Desativada (Acesso livre)" else "Ativa · HTTP Basic (Com Escudo Anti-Força Bruta)"
        val scopeLabel = if (networkScope == "internet") "Toda a Internet (Público)" else "Rede Local (LAN)"

        return """<!doctype html><html lang="pt-br"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="theme-color" content="#0b0f17"><title>AndServer · Painel do Servidor</title><link rel="icon" type="image/svg+xml" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'%3E%3Crect width='32' height='32' rx='8' fill='%230b0f17'/%3E%3Cpath d='M8 10h16v4H8zm0 8h16v4H8z' fill='%231a253c' stroke='%23263450' stroke-width='.5'/%3E%3Ccircle cx='21' cy='12' r='1' fill='%2310b981'/%3E%3Ccircle cx='21' cy='20' r='1' fill='%2338bdf8'/%3E%3Cpath d='M11 6a8 8 0 0 1 10 0' stroke='%2338bdf8' stroke-width='1.5' stroke-linecap='round' fill='none'/%3E%3C/svg%3E"><style>
            :root{
                --bg:#0b0f17;--card:#131b2c;--card-alt:#1a253c;--border:#263450;--border-subtle:rgba(255,255,255,0.07);
                --primary:#10b981;--primary-glow:rgba(16,185,129,0.25);--text:#f8fafc;--text-muted:#94a3b8;--text-subtle:#64748b;
                --danger:#ef4444;--danger-bg:#3d1a24;--radius:16px;--radius-sm:10px
            }
            *{box-sizing:border-box;margin:0;padding:0}
            body{font:15px -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;background:var(--bg);color:var(--text);padding:24px 18px 60px;max-width:1120px;margin:0 auto;-webkit-font-smoothing:antialiased}
            header{display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:16px;margin-bottom:28px;padding-bottom:18px;border-bottom:1px solid var(--border)}
            .brand-wrapper{display:flex;flex-direction:column;gap:4px}
            .brand-badge{display:inline-flex;align-items:center;gap:6px;background:var(--card-alt);border:1px solid var(--border);color:var(--primary);font-size:11px;font-weight:800;letter-spacing:.08em;padding:4px 10px;border-radius:999px;width:fit-content}
            .brand-dot{width:6px;height:6px;border-radius:50%;background:var(--primary)}
            h1{font-size:26px;font-weight:800;color:var(--text);letter-spacing:-.02em}
            .subtitle{color:var(--text-muted);font-size:13px}
            .header-badges{display:flex;align-items:center;gap:10px;flex-wrap:wrap}
            .live-badge{display:inline-flex;align-items:center;gap:8px;background:rgba(16,185,129,0.12);border:1px solid rgba(16,185,129,0.3);color:var(--primary);font-size:12px;font-weight:700;padding:7px 14px;border-radius:999px}
            .scope-badge{display:inline-flex;align-items:center;gap:6px;background:rgba(56,189,248,0.12);border:1px solid rgba(56,189,248,0.3);color:#38bdf8;font-size:12px;font-weight:700;padding:7px 14px;border-radius:999px}
            .pulse-dot{width:8px;height:8px;border-radius:50%;background:var(--primary);box-shadow:0 0 0 0 rgba(16,185,129,0.7);animation:pulse 2s infinite}
            @keyframes pulse{0%{transform:scale(.95);box-shadow:0 0 0 0 rgba(16,185,129,.7)}70%{transform:scale(1);box-shadow:0 0 0 8px rgba(16,185,129,0)}100%{transform:scale(.95);box-shadow:0 0 0 0 rgba(16,185,129,0)}}
            h2{font-size:16px;font-weight:700;color:var(--text);margin-bottom:14px;display:flex;align-items:center;gap:8px}
            .grid-stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:12px;margin-bottom:16px}
            .card{background:var(--card);border:1px solid var(--border);border-radius:var(--radius);padding:18px;margin-bottom:16px;box-shadow:0 4px 20px -2px rgba(0,0,0,0.35)}
            .stat-card{background:var(--card);border:1px solid var(--border);border-radius:var(--radius-sm);padding:14px 16px}
            .label{font-size:11px;color:var(--text-subtle);text-transform:uppercase;font-weight:700;letter-spacing:.06em}
            .value{font-size:18px;font-weight:700;color:var(--text);margin-top:6px;overflow-wrap:anywhere}
            .nav-tabs{display:flex;gap:8px;margin:24px 0 16px;background:var(--card);padding:5px;border-radius:12px;border:1px solid var(--border);width:fit-content;flex-wrap:wrap}
            .tab-btn{border:0;background:transparent;color:var(--text-muted);font-weight:600;font-size:13px;padding:9px 18px;border-radius:8px;cursor:pointer;transition:all .2s ease}
            .tab-btn.selected{background:var(--primary);color:#0b0f17;box-shadow:0 2px 8px var(--primary-glow)}
            .toolbar{display:flex;gap:10px;flex-wrap:wrap;align-items:center;margin-bottom:16px}
            input,select{color:var(--text);background:var(--card-alt);border:1px solid var(--border);border-radius:8px;padding:10px 14px;font-size:14px;transition:border-color .2s;outline:none}
            input:focus,select:focus{border-color:var(--primary)}
            input[type=search]{flex:1;min-width:200px}
            .btn{border:0;border-radius:8px;padding:10px 16px;font-weight:600;font-size:13px;cursor:pointer;display:inline-flex;align-items:center;gap:6px;transition:all .15s ease;text-decoration:none}
            .btn-primary{background:var(--primary);color:#0b0f17}.btn-primary:hover{opacity:.9}
            .btn-secondary{background:var(--card-alt);color:var(--text);border:1px solid var(--border)}.btn-secondary:hover{background:#23304c}
            .btn-danger{background:var(--danger-bg);color:#fca5a5;border:1px solid rgba(239,68,68,0.25);padding:6px 10px;font-size:12px}.btn-danger:hover{background:#52222d}
            .btn-sm{padding:6px 10px;font-size:12px;border-radius:6px}
            .dropzone{border:2px dashed var(--border);border-radius:var(--radius-sm);padding:24px 16px;text-align:center;cursor:pointer;background:rgba(26,37,60,0.3);transition:all .2s;position:relative;margin-bottom:16px}
            .dropzone.dragover{border-color:var(--primary);background:rgba(16,185,129,0.06)}
            .dropzone input[type=file]{position:absolute;inset:0;opacity:0;cursor:pointer;width:100%;height:100%}
            .dropzone .icon{font-size:28px;margin-bottom:6px}.dropzone .hint{font-size:12px;color:var(--text-subtle);margin-top:4px}
            .file-item{display:flex;align-items:center;justify-content:space-between;padding:12px 8px;border-bottom:1px solid var(--border-subtle);gap:12px}
            .file-item:last-child{border-bottom:none}.file-meta{display:flex;align-items:center;gap:10px;min-width:0;overflow:hidden}
            .file-meta a.clickable-title{color:var(--text);text-decoration:none;font-weight:500;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;cursor:pointer}
            .file-meta a.clickable-title:hover{color:var(--primary);text-decoration:underline}
            .file-meta .icon{font-size:18px;flex-shrink:0}
            .file-actions{display:flex;align-items:center;gap:8px;flex-shrink:0}
            .file-size{font-size:13px;color:var(--text-muted);margin-right:6px}
            .log-box{background:var(--card-alt);border:1px solid var(--border);border-radius:var(--radius-sm);padding:14px;max-height:220px;overflow-y:auto;font-family:monospace;font-size:12px;color:var(--text-muted)}
            .log-box li{list-style:none;padding:4px 0;border-bottom:1px solid rgba(255,255,255,0.04);word-break:break-all}
            .fields-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:14px;margin-bottom:14px}
            .field{display:grid;gap:6px}.field label{color:var(--text-muted);font-size:13px;font-weight:500}
            .checks{display:flex;gap:14px;flex-wrap:wrap;padding:6px 0}.checks label{display:flex;align-items:center;gap:6px;font-size:13px;color:var(--text);cursor:pointer}
            .notice{background:rgba(245,158,11,0.08);border:1px solid rgba(245,158,11,0.25);color:#fde68a;font-size:13px;line-height:1.5;padding:12px 14px;border-radius:var(--radius-sm);margin-top:14px}
            .api-endpoint{display:flex;align-items:center;justify-content:space-between;padding:11px 0;border-bottom:1px solid var(--border-subtle);font-size:13px}
            .api-endpoint:last-child{border-bottom:none}.api-endpoint code{color:var(--primary);background:var(--card-alt);padding:3px 7px;border-radius:5px;font-family:monospace;font-size:12px}
            .hidden{display:none}
            .modal-backdrop{position:fixed;inset:0;background:rgba(0,0,0,0.75);backdrop-filter:blur(6px);display:flex;align-items:center;justify-content:center;z-index:9999;padding:16px}
            .modal-box{background:var(--card);border:1px solid var(--border);border-radius:18px;max-width:540px;width:100%;padding:22px;box-shadow:0 10px 40px rgba(0,0,0,0.8);position:relative}
            .modal-title{font-size:18px;font-weight:700;margin-bottom:12px}
            .modal-close{position:absolute;top:16px;right:16px;background:none;border:none;color:var(--text-muted);font-size:20px;cursor:pointer}
            .qr-display{display:flex;flex-direction:column;align-items:center;justify-content:center;padding:14px 0}
            .qr-display img{width:240px;height:240px;border-radius:12px;background:#fff;padding:8px}
            .media-box{max-height:75vh;display:flex;align-items:center;justify-content:center;margin:12px 0}
            .media-box img{max-width:100%;max-height:70vh;border-radius:8px;object-fit:contain}
            .media-box video,.media-box audio{width:100%;border-radius:8px}
            @media(max-width:640px){
                body{padding:16px 12px 40px}header{flex-direction:column;align-items:flex-start}
                .grid-stats{grid-template-columns:repeat(2,1fr);gap:8px}.nav-tabs{width:100%}
                .tab-btn{flex:1;text-align:center}.toolbar{flex-direction:column;align-items:stretch}
                .toolbar input[type=search],.btn{width:100%;justify-content:center}
                .file-item{flex-direction:column;align-items:flex-start;gap:8px}
                .file-actions{width:100%;justify-content:space-between;flex-wrap:wrap}
            }
            @media(min-width:641px) and (max-width:1024px){
                .grid-stats{grid-template-columns:repeat(3,1fr)}
            }
            @media(min-width:1025px){
                .grid-stats{grid-template-columns:repeat(4,1fr)}
            }
        </style></head><body>
            <header>
                <div class="brand-wrapper">
                    <div class="brand-badge"><span class="brand-dot"></span>AND SERVER</div>
                    <h1>Central do Servidor</h1>
                    <div class="subtitle">Serviço HTTPS seguro com controle de acesso LAN / Internet</div>
                </div>
                <div class="header-badges">
                    <button class="btn btn-secondary btn-sm" onclick="showQrModal()">📱 QR Code de Acesso</button>
                    <div class="scope-badge">${if (networkScope == "internet") "🌍 Toda a Internet" else "🏠 Rede Local"}</div>
                    <div class="live-badge"><span class="pulse-dot"></span>ONLINE</div>
                </div>
            </header>

            <main>
                <section class="card">
                    <h2>Visão Geral do Servidor</h2>
                    <div class="grid-stats">
                        <div class="stat-card"><div class="label">Escopo de Rede</div><div class="value">$scopeLabel</div></div>
                        <div class="stat-card"><div class="label">Porta HTTPS</div><div class="value">$port</div></div>
                        <div class="stat-card"><div class="label">IP Local (LAN)</div><div id="statLocalIp" class="value">—</div></div>
                        <div class="stat-card"><div class="label">IP Público (Internet)</div><div id="statPublicIp" class="value">${publicIp ?: "—"}</div></div>
                        <div class="stat-card"><div class="label">Autenticação</div><div class="value">$protection</div></div>
                        <div class="stat-card"><div class="label">Escudo Força Bruta</div><div class="value">🛡️ Ativo (Auto-Ban)</div></div>
                        <div class="stat-card"><div class="label">Conexões Concorrentes</div><div class="value">$workers</div></div>
                        <div class="stat-card"><div class="label">Limite de Upload</div><div class="value">100 MB</div></div>
                        <div class="stat-card"><div class="label">Requisições</div><div id="requests" class="value">—</div></div>
                        <div class="stat-card"><div class="label">Clientes Conectados</div><div id="clients" class="value">—</div></div>
                        <div class="stat-card"><div class="label">Dados Recebidos</div><div id="incoming" class="value">—</div></div>
                        <div class="stat-card"><div class="label">Dados Enviados</div><div id="outgoing" class="value">—</div></div>
                    </div>
                    <div class="notice">
                        ${if (networkScope == "internet") "⚠️ <strong>Modo Toda a Internet Ativo:</strong> O servidor aceita conexões externas de qualquer rede. O escudo de força bruta bloqueia tentativas abusivas após 5 erros." else "🏠 <strong>Modo Rede Local Ativo:</strong> Conexões externas de fora da sua rede Wi-Fi são bloqueadas automaticamente pelo firewall interno."}
                    </div>
                </section>

                <nav class="nav-tabs">
                    <button class="tab-btn selected" data-tab="files">📁 Arquivos & Mídia</button>
                    <button class="tab-btn" data-tab="config">⚙️ Configurações & Rede</button>
                    <button class="tab-btn" data-tab="live">⚡ Eventos ao Vivo</button>
                    <button class="tab-btn" data-tab="api">📡 API, ZIP & TLS</button>
                </nav>

                <!-- ABA 1: ARQUIVOS & MÍDIA -->
                <section id="files">
                    <div class="card">
                        <h2>Gerenciador de Arquivos & Streaming</h2>
                        <div id="dropzone" class="dropzone">
                            <input id="picker" type="file" multiple onchange="uploadFiles()">
                            <div class="icon">☁️</div>
                            <div style="font-weight:600">Arraste e solte arquivos aqui para enviar</div>
                            <div class="hint">ou clique para selecionar do seu dispositivo (até 100 MB por arquivo)</div>
                        </div>
                        <div class="toolbar">
                            <input id="search" type="search" placeholder="🔍 Filtrar arquivos..." oninput="filterRows()">
                            <button class="btn btn-secondary" onclick="createFolder()">➕ Nova Pasta</button>
                            <a class="btn btn-secondary" href="/api/zip" download="arquivos-completos.zip">📦 Baixar Tudo (.ZIP)</a>
                            <button class="btn btn-secondary" onclick="refresh()">🔄 Atualizar</button>
                        </div>
                        <div id="message" style="margin-bottom:10px;font-size:13px;color:var(--primary);font-weight:500"></div>
                        <div id="filelist">Carregando lista de arquivos...</div>
                    </div>
                </section>

                <!-- ABA 2: CONFIGURAÇÕES & REDE -->
                <section id="config" class="hidden">
                    <form id="settings" class="card">
                        <h2>Ajustar Configuração e Acesso</h2>
                        <div class="fields-grid">
                            <div class="field">
                                <label for="cfgNetworkScope">Escopo de Conexão</label>
                                <select id="cfgNetworkScope">
                                    <option value="local">🏠 Apenas Rede Local (LAN) - Mesma Wi-Fi</option>
                                    <option value="internet">🌍 Toda a Internet (Público / WAN) - Acesso Global</option>
                                </select>
                            </div>
                            <div class="field">
                                <label for="cfgType">Perfil de Servidor</label>
                                <select id="cfgType">
                                    <option value="site">🌐 Site Web Hospedado (Pasta /www)</option>
                                    <option value="web">Central Web Completa & Arquivos</option>
                                    <option value="files">Servidor de Arquivos</option>
                                    <option value="api">Servidor de API JSON</option>
                                </select>
                            </div>
                            <div class="field">
                                <label for="cfgPort">Porta HTTPS (1024 a 65535)</label>
                                <input id="cfgPort" type="number" min="1024" max="65535" value="$port" required>
                            </div>
                            <div class="field">
                                <label for="cfgWorkers">Conexões Simultâneas</label>
                                <select id="cfgWorkers"><option>2</option><option>4</option><option>8</option><option>12</option><option>16</option></select>
                            </div>
                        </div>

                        <div class="field" style="margin-bottom:14px">
                            <label>Formatos de Arquivo Permitidos</label>
                            <div class="checks">
                                <label><input type="checkbox" name="filter" value="all"> Todos</label>
                                <label><input type="checkbox" name="filter" value="images"> Imagens</label>
                                <label><input type="checkbox" name="filter" value="documents"> Documentos</label>
                                <label><input type="checkbox" name="filter" value="audio"> Áudio</label>
                                <label><input type="checkbox" name="filter" value="video"> Vídeo</label>
                                <label><input type="checkbox" name="filter" value="archives"> Compactados</label>
                            </div>
                        </div>

                        <div class="fields-grid">
                            <div class="field">
                                <label for="cfgPassword">Senha de Acesso (HTTP Basic)</label>
                                <input id="cfgPassword" type="password" autocomplete="new-password" placeholder="Mínimo 8 caracteres (ou em branco)">
                            </div>
                        </div>
                        <div style="margin-bottom:16px">
                            <label style="display:inline-flex;align-items:center;gap:6px;font-size:13px;cursor:pointer">
                                <input id="removePassword" type="checkbox"> Remover proteção por senha
                            </label>
                        </div>
                        <div class="toolbar">
                            <button class="btn btn-primary" type="submit">Salvar e Reiniciar Servidor</button>
                            <span id="configMessage" style="color:var(--text-muted);font-size:13px"></span>
                        </div>
                    </form>
                </section>

                <!-- ABA 3: EVENTOS AO VIVO -->
                <section id="live" class="hidden">
                    <div class="card">
                        <h2>Registro de Atividade e Conexões em Tempo Real</h2>
                        <div id="wsStatus" style="font-size:13px;color:var(--primary);margin-bottom:10px">Conectando ao WebSocket seguro…</div>
                        <ul id="liveEvents" class="log-box"></ul>
                    </div>
                </section>

                <!-- ABA 4: API, ZIP & TLS -->
                <section id="api" class="hidden">
                    <div class="card">
                        <h2>Recursos Avançados e Endpoints da API</h2>
                        <div class="api-endpoint"><code>GET /</code><span style="color:var(--text-muted)">Carrega o Site Web (no perfil 'site') ou Painel Admin</span></div>
                        <div class="api-endpoint"><code>GET /health</code><span style="color:var(--text-muted)">Health check do servidor</span></div>
                        <div class="api-endpoint"><code>GET /api/status</code><span style="color:var(--text-muted)">Métricas, IP público e conexões</span></div>
                        <div class="api-endpoint"><code>GET /api/zip?path={pasta}</code><span style="color:var(--text-muted)">Compactar e baixar pasta completa em ZIP</span></div>
                        <div class="api-endpoint"><code>POST /api/share</code><span style="color:var(--text-muted)">Criar link temporário expirável (1h, 24h, 7d)</span></div>
                        <div class="api-endpoint"><code>GET /share/{token}</code><span style="color:var(--text-muted)">Download por token temporário sem expor senha</span></div>
                        <div class="api-endpoint"><code>GET /api/qrcode?text={url}</code><span style="color:var(--text-muted)">Gerar imagem SVG do QR Code dinâmico</span></div>
                        <div class="api-endpoint"><code>GET /api/certificate</code><span style="color:var(--text-muted)">Baixar certificado público TLS (.pem)</span></div>
                        <div class="api-endpoint"><code>WSS /ws</code><span style="color:var(--text-muted)">Eventos e telemetria em tempo real</span></div>
                        <div style="margin-top:20px;display:flex;gap:10px;flex-wrap:wrap">
                            <a class="btn btn-secondary" href="/api/certificate" download="andserver-certificate.pem">📥 Baixar Certificado TLS (.pem)</a>
                            <a class="btn btn-secondary" href="/api/zip" download="todos-arquivos.zip">📦 Baixar Todos os Arquivos (.zip)</a>
                        </div>
                    </div>
                </section>
            </main>

            <!-- MODAL: QR CODE -->
            <div id="modalQr" class="modal-backdrop hidden">
                <div class="modal-box">
                    <button class="modal-close" onclick="closeModals()">✕</button>
                    <div class="modal-title">📱 Conectar via QR Code</div>
                    <p style="color:var(--text-muted);font-size:13px">Aponte a câmera do celular ou tablet para acessar instantaneamente:</p>
                    <div class="qr-display">
                        <img id="qrImage" src="" alt="QR Code">
                        <div id="qrUrlLabel" style="margin-top:12px;font-family:monospace;font-size:13px;word-break:break-all;text-align:center"></div>
                    </div>
                    <div style="display:flex;justify-content:center;gap:10px">
                        <button class="btn btn-primary" onclick="copyCurrentUrl()">Copiar Link</button>
                        <button class="btn btn-secondary" onclick="closeModals()">Fechar</button>
                    </div>
                </div>
            </div>

            <!-- MODAL: COMPARTILHAMENTO TEMPORÁRIO -->
            <div id="modalShare" class="modal-backdrop hidden">
                <div class="modal-box">
                    <button class="modal-close" onclick="closeModals()">✕</button>
                    <div class="modal-title">🔗 Gerar Link Temporário</div>
                    <p style="color:var(--text-muted);font-size:13px;margin-bottom:12px">Crie um link de download com validade pré-definida sem precisar compartilhar sua senha:</p>
                    <div class="field" style="margin-bottom:14px">
                        <label>Arquivo selecionado:</label>
                        <input id="shareFilePath" type="text" readonly>
                    </div>
                    <div class="field" style="margin-bottom:14px">
                        <label>Tempo de validade:</label>
                        <select id="shareDuration">
                            <option value="1">1 hora</option>
                            <option value="12">12 horas</option>
                            <option value="24" selected>24 horas (1 dia)</option>
                            <option value="72">3 dias</option>
                            <option value="168">7 dias (1 semana)</option>
                        </select>
                    </div>
                    <button class="btn btn-primary" onclick="executeCreateShare()" style="width:100%;justify-content:center">Gerar Link de Download</button>
                    <div id="shareResultBox" class="hidden" style="margin-top:14px">
                        <label style="font-size:12px;color:var(--primary);font-weight:600">Link gerado com sucesso:</label>
                        <input id="shareGeneratedUrl" type="text" readonly style="margin:6px 0;width:100%">
                        <button class="btn btn-secondary btn-sm" onclick="copyShareUrl()" style="width:100%;justify-content:center">Copiar Link de Compartilhamento</button>
                    </div>
                </div>
            </div>

            <!-- MODAL: PRÉVIA DE MÍDIA (LIGHTBOX / PLAYER) -->
            <div id="modalMedia" class="modal-backdrop hidden">
                <div class="modal-box" style="max-width:800px">
                    <button class="modal-close" onclick="closeModals()">✕</button>
                    <div id="mediaTitle" class="modal-title">Prévia de Arquivo</div>
                    <div id="mediaContainer" class="media-box"></div>
                    <div style="display:flex;justify-content:space-between;align-items:center;margin-top:14px">
                        <span id="mediaInfo" style="font-size:12px;color:var(--text-muted)"></span>
                        <a id="mediaDownloadBtn" class="btn btn-primary btn-sm" href="#" download>Baixar Arquivo</a>
                    </div>
                </div>
            </div>

            <script>
            const tabs=document.querySelectorAll(".tab-btn");
            const sections={files:document.querySelector("#files"),config:document.querySelector("#config"),live:document.querySelector("#live"),api:document.querySelector("#api")};
            tabs.forEach(btn=>{
                btn.onclick=()=>{
                    tabs.forEach(b=>b.classList.toggle("selected",b===btn));
                    const target=btn.dataset.tab;
                    Object.keys(sections).forEach(k=>sections[k].classList.toggle("hidden",k!==target));
                };
            });

            function closeModals(){
                document.querySelectorAll(".modal-backdrop").forEach(m=>m.classList.add("hidden"));
                document.querySelector("#mediaContainer").replaceChildren();
            }

            function showQrModal(){
                const curUrl=location.protocol+"//"+location.host;
                document.querySelector("#qrImage").src="/api/qrcode?text="+encodeURIComponent(curUrl);
                document.querySelector("#qrUrlLabel").textContent=curUrl;
                document.querySelector("#modalQr").classList.remove("hidden");
            }

            function copyCurrentUrl(){
                navigator.clipboard.writeText(location.protocol+"//"+location.host);
                alert("Endereço copiado para a área de transferência!");
            }

            function openShareModal(path){
                document.querySelector("#shareFilePath").value=path;
                document.querySelector("#shareResultBox").classList.add("hidden");
                document.querySelector("#modalShare").classList.remove("hidden");
            }

            async function executeCreateShare(){
                const path=document.querySelector("#shareFilePath").value;
                const hours=Number(document.querySelector("#shareDuration").value);
                try{
                    let r=await fetch("/api/share",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({path,hours})});
                    let data=await r.json();
                    if(!r.ok) throw Error(data.error||"Erro ao gerar link");
                    const fullUrl=location.protocol+"//"+location.host+data.url;
                    document.querySelector("#shareGeneratedUrl").value=fullUrl;
                    document.querySelector("#shareResultBox").classList.remove("hidden");
                }catch(e){alert(e.message)}
            }

            function copyShareUrl(){
                const u=document.querySelector("#shareGeneratedUrl").value;
                navigator.clipboard.writeText(u);
                alert("Link copiado! Qualquer pessoa pode usar este link para baixar até o prazo expirar.");
            }

            function openMediaPreview(name,url,sizeStr){
                const container=document.querySelector("#mediaContainer");
                container.replaceChildren();
                document.querySelector("#mediaTitle").textContent=name;
                document.querySelector("#mediaInfo").textContent="Tamanho: "+sizeStr;
                const dlBtn=document.querySelector("#mediaDownloadBtn");
                dlBtn.href=url;
                dlBtn.setAttribute("download",name);

                const ext=name.split('.').pop().toLowerCase();
                if(["jpg","jpeg","png","gif","webp","svg"].includes(ext)){
                    const img=document.createElement("img");
                    img.src=url;
                    container.append(img);
                } else if(["mp4","webm","mkv","mov"].includes(ext)){
                    const video=document.createElement("video");
                    video.src=url;
                    video.controls=true;
                    video.autoplay=true;
                    container.append(video);
                } else if(["mp3","wav","ogg","aac","flac"].includes(ext)){
                    const audio=document.createElement("audio");
                    audio.src=url;
                    audio.controls=true;
                    audio.autoplay=true;
                    container.append(audio);
                } else {
                    location.href=url;
                    return;
                }
                document.querySelector("#modalMedia").classList.remove("hidden");
            }

            const dropzone=document.querySelector("#dropzone");
            ["dragenter","dragover"].forEach(name=>{dropzone.addEventListener(name,e=>{e.preventDefault();dropzone.classList.add("dragover")})});
            ["dragleave","drop"].forEach(name=>{dropzone.addEventListener(name,e=>{e.preventDefault();dropzone.classList.remove("dragover")})});
            dropzone.addEventListener("drop",e=>{
                if(e.dataTransfer&&e.dataTransfer.files.length){
                    document.querySelector("#picker").files=e.dataTransfer.files;
                    uploadFiles();
                }
            });

            async function refresh(){
                let box=document.querySelector("#filelist");
                try{
                    let r=await fetch("/api/files");
                    if(!r.ok)throw Error("Não foi possível carregar os arquivos");
                    let files=await r.json();
                    box.replaceChildren();
                    if(!files.length){
                        box.innerHTML='<div style="padding:20px;text-align:center;color:var(--text-muted)">Nenhum arquivo armazenado ainda. Use a área acima para enviar.</div>';
                        return;
                    }
                    for(const item of files){
                        let row=document.createElement("div");
                        row.className="file-item";
                        let meta=document.createElement("div");
                        meta.className="file-meta";
                        let icon=document.createElement("span");
                        icon.className="icon";
                        icon.textContent=item.directory?"📁":getFileIcon(item.name);

                        let title=document.createElement("a");
                        title.className="clickable-title";
                        title.textContent=item.name;
                        if(item.directory){
                            title.href=item.url;
                        } else {
                            title.onclick=()=>openMediaPreview(item.name,item.url,formatSize(item.size));
                        }
                        meta.append(icon,title);

                        let actions=document.createElement("div");
                        actions.className="file-actions";

                        let size=document.createElement("span");
                        size.className="file-size";
                        size.textContent=item.directory?"Pasta":formatSize(item.size);
                        actions.append(size);

                        if(item.directory){
                            let zipBtn=document.createElement("a");
                            zipBtn.className="btn btn-secondary btn-sm";
                            zipBtn.href="/api/zip?path="+encodePath(item.name);
                            zipBtn.textContent="📦 ZIP";
                            actions.append(zipBtn);
                        } else {
                            let shareBtn=document.createElement("button");
                            shareBtn.className="btn btn-secondary btn-sm";
                            shareBtn.textContent="🔗 Link";
                            shareBtn.onclick=()=>openShareModal(item.name);

                            let dl=document.createElement("a");
                            dl.className="btn btn-secondary btn-sm";
                            dl.href=item.url;
                            dl.setAttribute("download",item.name);
                            dl.textContent="⬇️";

                            actions.append(shareBtn,dl);
                        }

                        let del=document.createElement("button");
                        del.className="btn btn-danger";
                        del.textContent="Excluir";
                        del.onclick=()=>removeFile(item.name,item.directory);
                        actions.append(del);

                        row.append(meta,actions);
                        box.append(row);
                    }
                    filterRows();
                }catch(e){box.textContent=e.message}
            }

            function getFileIcon(n){
                let ext=n.split('.').pop().toLowerCase();
                if(["jpg","jpeg","png","gif","webp","svg"].includes(ext)) return "🖼️";
                if(["mp3","wav","ogg","aac","flac"].includes(ext)) return "🎵";
                if(["mp4","mkv","webm","mov","avi"].includes(ext)) return "🎬";
                if(["zip","rar","7z","tar","gz"].includes(ext)) return "📦";
                if(["pdf","doc","docx","txt","md"].includes(ext)) return "📄";
                return "📎";
            }

            function formatSize(n){
                return n<1024?n+" B":n<1048576?(n/1024).toFixed(1)+" KB":(n/1048576).toFixed(1)+" MB";
            }
            function encodePath(s){return s.split("/").map(encodeURIComponent).join("/");}

            async function uploadFiles(){
                let files=document.querySelector("#picker").files;
                let msg=document.querySelector("#message");
                for(const f of files){
                    msg.textContent="Enviando "+f.name+"...";
                    let r=await fetch("/api/files/"+encodePath(f.name),{method:"PUT",body:f});
                    msg.textContent=r.ok?"Enviado com sucesso: "+f.name:"Falha no envio de "+f.name;
                }
                await refresh();
            }

            async function removeFile(name,directory){
                if(!confirm((directory?"Excluir a pasta e todo o conteúdo de ":"Excluir o arquivo ")+name+"?")) return;
                let r=await fetch("/api/files/"+encodePath(name),{method:"DELETE"});
                document.querySelector("#message").textContent=r.ok?"Item removido com sucesso.":"Falha ao remover item.";
                await refresh();
            }

            async function createFolder(){
                let name=prompt("Nome da nova pasta:");
                if(!name)return;
                let r=await fetch("/api/files/"+encodePath(name),{method:"MKCOL"});
                document.querySelector("#message").textContent=r.ok?"Pasta criada!":"Não foi possível criar a pasta.";
                await refresh();
            }

            function filterRows(){
                let term=document.querySelector("#search").value.toLowerCase();
                document.querySelectorAll("#filelist .file-item").forEach(row=>{
                    row.classList.toggle("hidden",!row.textContent.toLowerCase().includes(term));
                });
            }

            function formatTraffic(n){
                return n<1024?n+" B":n<1048576?(n/1024).toFixed(1)+" KB":n<1073741824?(n/1048576).toFixed(1)+" MB":(n/1073741824).toFixed(1)+" GB";
            }

            async function refreshStatus(){
                try{
                    let s=await(await fetch("/api/status")).json();
                    document.querySelector("#requests").textContent=s.requests;
                    document.querySelector("#clients").textContent=s.clients;
                    document.querySelector("#incoming").textContent=formatTraffic(s.bytesReceived);
                    document.querySelector("#outgoing").textContent=formatTraffic(s.bytesSent);
                    document.querySelector("#statLocalIp").textContent=location.hostname;
                    if(s.publicIp) document.querySelector("#statPublicIp").textContent=s.publicIp;
                }catch(e){}
            }

            async function loadConfig(){
                try{
                    let c=await(await fetch("/api/config")).json();
                    document.querySelector("#cfgType").value=c.type;
                    document.querySelector("#cfgPort").value=c.port;
                    document.querySelector("#cfgWorkers").value=c.workers;
                    if(c.networkScope) document.querySelector("#cfgNetworkScope").value=c.networkScope;
                    document.querySelectorAll("input[name=filter]").forEach(x=>x.checked=c.filters.includes(x.value));
                    if(c.filters.includes("all")) document.querySelectorAll("input[name=filter]").forEach(x=>x.checked=x.value==="all");
                }catch(e){}
            }

            document.querySelectorAll("input[name=filter]").forEach(x=>x.onchange=()=>{
                let all=document.querySelector('input[name=filter][value="all"]');
                if(x.value==="all"&&x.checked) document.querySelectorAll("input[name=filter]").forEach(y=>y.checked=y===x);
                else if(x.value!=="all"&&x.checked) all.checked=false;
            });

            document.querySelector("#settings").onsubmit=async e=>{
                e.preventDefault();
                let filters=[...document.querySelectorAll("input[name=filter]:checked")].map(x=>x.value);
                let data={
                    type:document.querySelector("#cfgType").value,
                    port:Number(document.querySelector("#cfgPort").value),
                    workers:Number(document.querySelector("#cfgWorkers").value),
                    networkScope:document.querySelector("#cfgNetworkScope").value,
                    filters
                };
                let pwd=document.querySelector("#cfgPassword").value;
                if(pwd) data.password=pwd;
                if(document.querySelector("#removePassword").checked) data.password="";
                let result=document.querySelector("#configMessage");
                try{
                    let r=await fetch("/api/config",{method:"PUT",headers:{"Content-Type":"application/json"},body:JSON.stringify(data)});
                    let res=await r.json();
                    if(!r.ok)throw Error(res.error||"Falha ao salvar configuração");
                    result.textContent="Configuração salva! Reiniciando...";
                    setTimeout(()=>{
                        let p=Number(data.port);
                        location.href=location.protocol+"//"+location.hostname+(p===80?"":":"+p)+"/";
                    },1400);
                }catch(err){result.textContent=err.message}
            };

            function connectLive(){
                let ws=new WebSocket("wss://"+location.host+"/ws?token=$webSocketToken");
                ws.onopen=()=>document.querySelector("#wsStatus").textContent="● Conexão WSS ativa com o servidor";
                ws.onmessage=e=>{
                    try{
                        let data=JSON.parse(e.data);
                        let row=document.createElement("li");
                        let time=new Date().toLocaleTimeString();
                        row.textContent="["+time+"] "+(data.message||JSON.stringify(data));
                        let list=document.querySelector("#liveEvents");
                        list.prepend(row);
                        while(list.children.length>30) list.lastElementChild.remove();
                    }catch(err){}
                };
                ws.onerror=()=>document.querySelector("#wsStatus").textContent="Falha na conexão WebSocket";
                ws.onclose=()=>{
                    document.querySelector("#wsStatus").textContent="Reconectando WebSocket...";
                    setTimeout(connectLive,3000);
                };
            }

            refresh();refreshStatus();loadConfig();connectLive();setInterval(refreshStatus,3000);
            </script>
        </body></html>"""
    }

    private fun java.io.BufferedInputStream.readLineAscii(max: Int): String? {
        val b = java.io.ByteArrayOutputStream(); var c: Int
        while (b.size() < max) { c = read(); if (c < 0) return if (b.size() == 0) null else b.toString("US-ASCII"); if (c == 10) break; if (c != 13) b.write(c) }
        return b.toString("US-ASCII")
    }
}
