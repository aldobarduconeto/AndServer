package com.andserver

import android.util.Base64
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import org.json.JSONArray
import org.json.JSONObject

/**
 * Servidor HTTP/HTTPS e WebDAV de alta performance e baixo consumo energético.
 * Gerencia ciclo de vida dos sockets, rate limiting, proteção contra força bruta,
 * roteamento de endpoints, downloads em lote, streaming e WebSocket.
 *
 * Otimizações de bateria e hardware:
 * - Contagem reativa de arquivos sem acordar CPU periodicamente (elimina polling de disco).
 * - Compressão dinâmica GZIP em tempo real para reduzir tráfego e tempo de antena Wi-Fi/4G.
 * - Buffer adaptativo de 128 KB para streaming e downloads velozes com menos syscalls.
 * - Pool elástico de threads com descarte automático de ociosos (allowCoreThreadTimeOut).
 * - Integração inteligente com DevicePowerManager para Wi-Fi locks sob demanda.
 */
class LanHttpServer(
    val port: Int,
    val root: File,
    val type: String,
    val filters: Set<String>,
    val workers: Int,
    val password: String,
    val networkScope: String,
    private val tlsContext: SSLContext,
    private val certificatePem: String,
    private val appVersion: String = "1.0",
    val storageLocation: String = "internal",
    private val powerManager: DevicePowerManager? = null,
    private val onConfigure: (ServerConfig) -> Unit,
    private val onExit: () -> Unit
) {
    private val running = AtomicBoolean(false)
    private val requests = AtomicLong(0)
    private val activeClients = AtomicInteger(0)
    private val startedAt = AtomicLong(0)
    private val bytesReceived = AtomicLong(0)
    private val bytesSent = AtomicLong(0)
    private val events = Collections.synchronizedList(mutableListOf<String>())
    private val logFile = File(root.parentFile, "access.log")
    private val openSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val fileCount = AtomicInteger(0)
    private var lastFileScan = 0L

    private val effectiveWorkers = powerManager?.getOptimalWorkers(workers) ?: workers

    private val clients = ThreadPoolExecutor(
        2.coerceAtMost(effectiveWorkers),
        effectiveWorkers,
        20L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue<Runnable>(256),
        ThreadFactory { task ->
            Thread(task, "AndServer-Worker").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY
            }
        }
    ).apply { allowCoreThreadTimeOut(true) }

    private val webSocketPeers = ConcurrentHashMap.newKeySet<WebSocketPeer>()
    private val webSocketToken = Base64.encodeToString(
        ByteArray(32).also { SecureRandom().nextBytes(it) },
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    )
    @Volatile private var socket: ServerSocket? = null
    @Volatile private var publicIp: String? = null

    // Proteção contra Força Bruta (Brute-Force Shield)
    private val failedLogins = ConcurrentHashMap<String, IpBlock>()

    // Links de Compartilhamento Temporários com Expiração
    private val activeShares = ConcurrentHashMap<String, ShareLink>()

    init {
        refreshFileCountAsync(force = true)
    }

    private fun refreshFileCountAsync(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && (now - lastFileScan < 60_000)) return
        lastFileScan = now
        Thread {
            try {
                fileCount.set(root.walkTopDown().count { it.isFile })
            } catch (_: Exception) { }
        }.apply { name = "AndServer-FileScan"; isDaemon = true; start() }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        startedAt.set(System.currentTimeMillis())
        val scopeName = if (networkScope == "internet") "Toda a Internet" else "Rede Local (LAN)"
        val storageLabel = if (storageLocation == "external") "Cartão SD / Externo" else "Armazenamento Interno"
        log("Servidor iniciado · $type · porta $port · Modo: $scopeName · Storage: $storageLabel")
        if (networkScope == "internet") {
            fetchPublicIp()
        }

        Thread {
            try {
                val tlsListener = tlsContext.serverSocketFactory.createServerSocket() as SSLServerSocket
                tlsListener.enabledProtocols = tlsListener.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
                tlsListener.reuseAddress = true
                tlsListener.bind(InetSocketAddress(port), 128)
                tlsListener.use { listener ->
                    socket = listener
                    while (running.get()) {
                        val client = listener.accept()
                        client.tcpNoDelay = true
                        client.keepAlive = true
                        client.sendBufferSize = 64 * 1024
                        client.receiveBufferSize = 64 * 1024
                        openSockets.add(client)
                        try {
                            clients.execute { handle(client) }
                        } catch (_: RejectedExecutionException) {
                            try {
                                client.getOutputStream().write("HTTP/1.1 503 Service Unavailable\r\nRetry-After: 2\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray())
                            } catch (_: Exception) { }
                            try { client.close() } catch (_: Exception) { }
                            openSockets.remove(client)
                            log("Conexão recusada · limite de clientes atingido")
                        }
                    }
                }
            } catch (e: Exception) {
                if (running.get()) log("Falha ao aceitar conexão · ${e.javaClass.simpleName}")
            } finally {
                running.set(false)
                clients.shutdownNow()
                powerManager?.releaseAll()
                onExit()
            }
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
        powerManager?.releaseAll()
    }

    fun snapshot(): ServerSnapshot {
        val powerProfile = powerManager?.getPowerProfileLabel() ?: ""
        return ServerSnapshot(
            running.get(), port, requests.get(), activeClients.get() + clients.queue.size, fileCount.get(),
            startedAt.get(), synchronized(events) { events.toList().reversed() }, type, bytesReceived.get(), bytesSent.get(),
            networkScope, publicIp, storageLocation, powerProfile
        )
    }

    private fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val safeMessage = message.replace("\r", " ").replace("\n", " ")
        synchronized(events) {
            events.add(0, "$time  $safeMessage")
            while (events.size > 30) events.removeAt(events.lastIndex)
            try {
                if (logFile.length() > 1024L * 1024) {
                    File(logFile.parentFile, "access.log.1").delete()
                    logFile.renameTo(File(logFile.parentFile, "access.log.1"))
                }
                logFile.appendText("$time  $safeMessage\n", Charsets.UTF_8)
            } catch (_: Exception) { }
        }
        val wsMsg = "{\"type\":\"event\",\"message\":\"${FormatUtils.escapeJson("$time  $safeMessage")}\"}"
        WebSocketHandler.broadcast(webSocketPeers, wsMsg.toByteArray(Charsets.UTF_8))
    }

    private fun allowed(name: String): Boolean = MimeTypes.isAllowedByFilter(name, filters)

    private fun isLocalOrPrivateAddress(addr: InetAddress): Boolean {
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
                if (parts.size < 3 || !parts[2].startsWith("HTTP/1.")) {
                    response(output, 400, "text/plain", "Requisicao invalida")
                    return
                }
                val method = parts[0].uppercase()
                val target = parts[1]
                val headers = linkedMapOf<String, String>()
                var headerBytes = 0
                while (true) {
                    val line = input.readLineAscii(8192) ?: break
                    headerBytes += line.length
                    if (headerBytes > 32_768) {
                        response(output, 431, "text/plain", "Cabecalhos muito grandes")
                        return
                    }
                    if (line.isEmpty()) break
                    val split = line.indexOf(':')
                    if (split > 0) headers[line.substring(0, split).trim().lowercase()] = line.substring(split + 1).trim()
                }
                val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
                val rawPath = target.substringBefore('?')
                val path = try {
                    URLDecoder.decode(rawPath.replace("+", "%2B"), "UTF-8")
                } catch (_: Exception) {
                    response(output, 400, "text/plain", "Caminho invalido")
                    return
                }
                val clientIp = s.inetAddress.hostAddress ?: "desconhecido"
                val acceptsGzip = headers["accept-encoding"]?.contains("gzip", true) == true

                // 1. Verificação de Escopo de Rede (Local vs. Internet)
                if (networkScope == "local" && !isLocalOrPrivateAddress(s.inetAddress)) {
                    log("Acesso externo bloqueado (Modo Rede Local) · $clientIp")
                    response(output, 403, "application/json; charset=utf-8", "{\"error\":\"Acesso restrito a rede local. Altere para o modo 'Toda a Internet' para conexoes externas.\"}")
                    return
                }

                // 2. Proteção contra Força Bruta (Brute-Force Shield)
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

                if (headers["transfer-encoding"]?.contains("chunked", true) == true) {
                    response(output, 501, "application/json", "{\"error\":\"Transfer-Encoding chunked nao suportado; envie Content-Length\"}")
                    return
                }
                if (headers["expect"]?.equals("100-continue", true) == true) {
                    output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
                    output.flush()
                }
                if (method == "OPTIONS") {
                    output.write("HTTP/1.1 204 No Content\r\nDAV: 1, 2\r\nMS-Author-Via: DAV\r\nAllow: OPTIONS, GET, HEAD, POST, PUT, DELETE, MKCOL, PROPFIND, PROPPATCH, MOVE, COPY, LOCK, UNLOCK\r\nAccept-Ranges: bytes\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    output.flush()
                    return
                }
                if (method !in setOf("GET", "HEAD", "PUT", "POST", "DELETE", "MKCOL", "PROPFIND", "PROPPATCH", "MOVE", "COPY", "LOCK", "UNLOCK")) {
                    response(output, 405, "application/json", "{\"error\":\"Metodo HTTP nao suportado\"}")
                    return
                }
                val isHead = method == "HEAD"
                val isWebDav = path.startsWith("/webdav") || path.startsWith("/drive")
                val webdavRel = if (isWebDav) path.removePrefix("/webdav/").removePrefix("/webdav").removePrefix("/drive/").removePrefix("/drive").trim('/') else null
                val apiPath = path.removePrefix("/api/files/").takeIf { path.startsWith("/api/files/") }
                val legacyUpload = path.removePrefix("/upload/").takeIf { path.startsWith("/upload/") }
                val effectiveUploadRel = apiPath ?: legacyUpload ?: webdavRel

                when {
                    websocketRequest -> {
                        val key = headers["sec-websocket-key"]
                        val validKey = try { key != null && Base64.decode(key, Base64.DEFAULT).size == 16 } catch (_: Exception) { false }
                        val connectionUpgrade = headers["connection"]?.split(',')?.any { it.trim().equals("upgrade", true) } == true
                        val validUpgrade = headers["upgrade"]?.equals("websocket", ignoreCase = true) == true
                        if (!validUpgrade || headers["sec-websocket-version"] != "13" || !connectionUpgrade || !validKey) {
                            response(output, 426, "application/json", "{\"error\":\"Handshake WebSocket RFC 6455 invalido\"}")
                        } else {
                            val accept = Base64.encodeToString(
                                MessageDigest.getInstance("SHA-1").digest((key!! + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)),
                                Base64.NO_WRAP
                            )
                            output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray(Charsets.US_ASCII))
                            output.flush()
                            val peer = WebSocketPeer(s, output)
                            webSocketPeers.add(peer)
                            log("WebSocket conectado · $clientIp")
                            WebSocketHandler.webSocketLoop(peer, input, { running.get() }, { webSocketPeers.remove(peer) })
                        }
                    }
                    isShareDownload -> {
                        // Download via Link de Compartilhamento Temporário
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
                        // QR Code SVG dinâmico
                        val textParam = queryParameter(target, "text") ?: "https://${s.inetAddress.hostAddress}:$port"
                        val svg = QrGenerator.toSvg(QrGenerator.encode(textParam))
                        response(output, 200, "image/svg+xml; charset=utf-8", svg, head = isHead, acceptGzip = acceptsGzip)
                    }
                    method in setOf("GET", "HEAD") && (path == "/api/zip" || path.startsWith("/api/zip/")) -> {
                        // Download em Lote como ZIP
                        val queryRel = queryParameter(target, "path")?.trim('/') ?: path.removePrefix("/api/zip").trim('/')
                        val folder = if (queryRel.isEmpty()) root else resolvePath(queryRel)
                        if (folder == null || !folder.exists()) {
                            response(output, 404, "application/json", "{\"error\":\"Pasta nao encontrada\"}")
                        } else {
                            val zipName = if (folder == root) "andserver-arquivos.zip" else "${folder.name}.zip"
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nContent-Disposition: attachment; filename=\"$zipName\"\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                            if (!isHead) {
                                powerManager?.onTransferStart()
                                try {
                                    ZipOutputStream(BufferedOutputStream(output, 64 * 1024)).use { zos ->
                                        val base = if (folder.isDirectory) folder else folder.parentFile
                                        folder.walkTopDown().filter { it.isFile && allowed(it.name) }.forEach { f ->
                                            val entryName = f.relativeTo(base).invariantSeparatorsPath
                                            zos.putNextEntry(ZipEntry(entryName))
                                            f.inputStream().use { it.copyTo(zos) }
                                            zos.closeEntry()
                                        }
                                        zos.finish()
                                    }
                                } finally {
                                    powerManager?.onTransferEnd()
                                }
                            }
                            output.flush()
                            log("Download ZIP gerado · $zipName")
                        }
                    }
                    method == "POST" && path == "/api/share" -> {
                        // Criação de Link Temporário com Expiração
                        if (contentLength <= 0 || contentLength > 4096) {
                            response(output, 400, "application/json", "{\"error\":\"Requisicao de compartilhamento invalida\"}")
                            return
                        }
                        val body = ByteArray(contentLength.toInt())
                        var readCount = 0
                        while (readCount < body.size) {
                            val r = input.read(body, readCount, body.size - readCount)
                            if (r < 0) break
                            readCount += r
                        }
                        val json = try { JSONObject(String(body, Charsets.UTF_8)) } catch (_: Exception) { JSONObject() }
                        val rel = json.optString("path").trim('/')
                        val hours = json.optInt("hours", 24).coerceIn(1, 168)
                        val file = resolvePath(rel)
                        if (file == null || !file.isFile || !allowed(file.name)) {
                            response(output, 404, "application/json", "{\"error\":\"Arquivo nao encontrado\"}")
                        } else {
                            val token = Base64.encodeToString(
                                ByteArray(16).also { SecureRandom().nextBytes(it) },
                                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                            )
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
                                response(output, 200, "text/html; charset=utf-8", WebDashboardRenderer.renderWebDashboard(type, filters, password.isNotEmpty(), networkScope, port, effectiveWorkers, publicIp, webSocketToken), head = isHead, acceptGzip = acceptsGzip)
                            }
                        } else {
                            response(output, 200, "text/html; charset=utf-8", WebDashboardRenderer.renderWebDashboard(type, filters, password.isNotEmpty(), networkScope, port, effectiveWorkers, publicIp, webSocketToken), head = isHead, acceptGzip = acceptsGzip)
                        }
                    }
                    method in setOf("GET", "HEAD") && path == "/health" -> response(output, 200, "application/json; charset=utf-8", "{\"status\":\"ok\"}", head = isHead)
                    method in setOf("GET", "HEAD") && path == "/api/certificate" -> response(output, 200, "application/x-pem-file", certificatePem, head = isHead)
                    method in setOf("GET", "HEAD") && path == "/api/ws-token" -> response(output, 200, "application/json; charset=utf-8", "{\"token\":\"$webSocketToken\"}", head = isHead)
                    method in setOf("GET", "HEAD") && path == "/api/config" -> {
                        val json = JSONObject().put("type", type).put("port", port).put("workers", workers)
                            .put("filters", JSONArray(filters.toList())).put("passwordEnabled", password.isNotEmpty())
                            .put("networkScope", networkScope).put("publicIp", publicIp ?: "")
                            .put("storageLocation", storageLocation)
                            .put("tls", true).put("websocket", true)
                        response(output, 200, "application/json; charset=utf-8", json.toString(), head = isHead, acceptGzip = acceptsGzip)
                    }
                    method == "PUT" && path == "/api/config" -> {
                        if (contentLength < 1 || contentLength > 8192) {
                            response(output, 413, "application/json", "{\"error\":\"Configuracao deve ter ate 8 KB\"}")
                            return
                        }
                        val body = ByteArray(contentLength.toInt())
                        var offset = 0
                        while (offset < body.size) {
                            val count = input.read(body, offset, body.size - offset)
                            if (count < 0) { response(output, 400, "application/json", "{\"error\":\"Corpo incompleto\"}"); return }
                            offset += count
                        }
                        try {
                            val json = JSONObject(String(body, Charsets.UTF_8))
                            val newPort = json.optInt("port", port)
                            val newType = json.optString("type", type)
                            val newWorkers = json.optInt("workers", workers)
                            val newScope = json.optString("networkScope", networkScope).takeIf { it in setOf("local", "internet") } ?: networkScope
                            val newLoc = json.optString("storageLocation", storageLocation).takeIf { it in setOf("internal", "external") } ?: storageLocation
                            val array = json.optJSONArray("filters") ?: JSONArray(filters.toList())
                            val newFilters = (0 until array.length()).map { array.getString(it) }.toSet()
                            val newPassword = json.optString("password", password)
                            if (newPort !in 1024..65535 || newType !in setOf("site", "web", "files", "api", "drive", "streaming") || newWorkers !in 2..32 ||
                                newFilters.any { it !in setOf("all", "images", "documents", "audio", "video", "archives") } ||
                                (newPassword.isNotEmpty() && newPassword.length < 8)) {
                                response(output, 400, "application/json", "{\"error\":\"Revise porta, perfil, conexoes, filtros e senha\"}")
                            } else {
                                val finalFilters = if ("all" in newFilters) setOf("all") else newFilters
                                response(output, 202, "application/json", "{\"restarting\":true,\"port\":$newPort}")
                                onConfigure(ServerConfig(newPort, newType, finalFilters, newWorkers, newPassword, newScope, newLoc))
                            }
                        } catch (_: Exception) {
                            response(output, 400, "application/json", "{\"error\":\"JSON de configuracao invalido\"}")
                        }
                    }
                    method in setOf("GET", "HEAD") && path == "/api/update" -> {
                        val updater = GithubReleaseUpdater()
                        val check = try { updater.checkUpdate(appVersion) } catch (_: Exception) { null }
                        val json = JSONObject()
                        json.put("currentVersion", appVersion)
                        json.put("updateAvailable", check?.updateAvailable ?: false)
                        json.put("message", check?.message ?: "Não foi possível verificar atualizações no momento")
                        if (check?.latestRelease != null) {
                            val rel = check.latestRelease
                            val relObj = JSONObject()
                                .put("tag", rel.tag)
                                .put("name", rel.name)
                                .put("notes", rel.notes)
                                .put("downloadUrl", rel.downloadUrl)
                                .put("sizeBytes", rel.sizeBytes)
                                .put("publishedAt", rel.publishedAt)
                            json.put("latestRelease", relObj)
                        }
                        response(output, 200, "application/json; charset=utf-8", json.toString(), head = isHead, acceptGzip = acceptsGzip)
                    }
                    method in setOf("GET", "HEAD") && path == "/api/status" -> {
                        refreshFileCountAsync(force = false)
                        val stat = try { android.os.StatFs(root.path) } catch (_: Exception) { null }
                        val totalStorage = stat?.totalBytes ?: 0L
                        val freeStorage = stat?.availableBytes ?: 0L
                        val hostedStorage = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                        val powerProfile = powerManager?.getPowerProfileLabel() ?: ""
                        val body = """{"name":"AndServer","scheme":"https","websocket":"wss","type":"$type","port":$port,"networkScope":"$networkScope","storageLocation":"$storageLocation","powerProfile":"${FormatUtils.escapeJson(powerProfile)}","publicIp":"${publicIp ?: ""}","requests":${requests.get()},"clients":${activeClients.get() + clients.queue.size},"files":${fileCount.get()},"bytesReceived":${bytesReceived.get()},"bytesSent":${bytesSent.get()},"uptimeSeconds":${((System.currentTimeMillis() - startedAt.get()) / 1000).coerceAtLeast(0)},"storageTotal":$totalStorage,"storageFree":$freeStorage,"storageHosted":$hostedStorage}"""
                        response(output, 200, "application/json; charset=utf-8", body, head = isHead, acceptGzip = acceptsGzip)
                    }
                    method in setOf("GET", "HEAD") && path == "/api/logs" -> {
                        val body = (try { logFile.readLines(Charsets.UTF_8).takeLast(500) } catch (_: Exception) { synchronized(events) { events.toList() } }).joinToString(",") { "\"${FormatUtils.escapeJson(it)}\"" }
                        response(output, 200, "application/json; charset=utf-8", "[$body]", head = isHead, acceptGzip = acceptsGzip)
                    }
                    method in setOf("GET", "HEAD") && path == "/api/logs.txt" -> {
                        val body = try { logFile.readText(Charsets.UTF_8) } catch (_: Exception) { "" }
                        response(output, 200, "text/plain; charset=utf-8", body, head = isHead, acceptGzip = acceptsGzip)
                    }
                    method in setOf("GET", "HEAD") && path == "/api/system" -> {
                        val runtime = Runtime.getRuntime()
                        val body = JSONObject()
                            .put("device", android.os.Build.MODEL)
                            .put("android", android.os.Build.VERSION.RELEASE)
                            .put("processors", runtime.availableProcessors())
                            .put("memoryMaxBytes", runtime.maxMemory())
                            .put("memoryFreeBytes", runtime.freeMemory())
                            .put("memoryTotalBytes", runtime.totalMemory())
                            .put("batteryPercent", powerManager?.batteryPercent ?: 100)
                            .put("isCharging", powerManager?.isCharging ?: false)
                            .put("powerSaveMode", powerManager?.isPowerSaveMode ?: false)
                            .toString()
                        response(output, 200, "application/json; charset=utf-8", body, head = isHead, acceptGzip = acceptsGzip)
                    }
                    method in setOf("GET", "HEAD") && path == "/api/files" -> {
                        val files = root.walkTopDown().drop(1).filter { it.isDirectory || (it.isFile && allowed(it.name)) }.map {
                            val rel = it.relativeTo(root).invariantSeparatorsPath
                            val url = if (it.isDirectory) "/public/${FormatUtils.urlPath(rel)}/" else "/files/${FormatUtils.urlPath(rel)}"
                            "{\"name\":\"${FormatUtils.escapeJson(rel)}\",\"directory\":${it.isDirectory},\"size\":${if (it.isFile) it.length() else 0},\"modified\":${it.lastModified()},\"url\":\"$url\"}"
                        }.toList().joinToString(",")
                        response(output, 200, "application/json; charset=utf-8", "[$files]", head = isHead, acceptGzip = acceptsGzip)
                    }
                    method == "PROPFIND" -> {
                        WebDavHandler.handlePropFind(output, path, headers["depth"] ?: "1", root, ::resolvePath, ::allowed, isHead)
                    }
                    method == "LOCK" -> {
                        WebDavHandler.handleLock(output, isHead)
                    }
                    method == "UNLOCK" -> {
                        WebDavHandler.handleUnlock(output)
                    }
                    method == "PROPPATCH" -> {
                        WebDavHandler.handlePropPatch(output, path, isHead)
                    }
                    method == "MOVE" -> {
                        WebDavHandler.handleMove(output, path, headers["destination"], ::resolvePath, ::log)
                    }
                    method == "COPY" -> {
                        WebDavHandler.handleCopy(output, path, headers["destination"], ::resolvePath, ::log)
                    }
                    method == "MKCOL" && (apiPath != null || webdavRel != null) -> {
                        val targetRel = apiPath ?: webdavRel!!
                        val dir = if (targetRel.isEmpty()) root else resolvePath(targetRel)
                        if (dir == null) response(output, 400, "application/json", "{\"error\":\"Caminho invalido\"}")
                        else if (dir.exists()) response(output, 405, "application/json", "{\"error\":\"O caminho ja existe\"}")
                        else if (!dir.mkdirs()) response(output, 500, "application/json", "{\"error\":\"Nao foi possivel criar a pasta\"}")
                        else {
                            log("Pasta criada · $targetRel")
                            response(output, 201, "application/json", "{\"created\":\"${FormatUtils.escapeJson(targetRel)}\"}")
                        }
                    }
                    method == "DELETE" && (apiPath != null || webdavRel != null) -> {
                        val targetRel = apiPath ?: webdavRel!!
                        val item = if (targetRel.isEmpty()) null else resolvePath(targetRel)
                        if (item == null || !item.exists() || item == root) {
                            response(output, 404, "application/json", "{\"error\":\"Arquivo ou pasta nao encontrado\"}")
                        } else if (!item.deleteRecursively()) {
                            response(output, 500, "application/json", "{\"error\":\"Nao foi possivel apagar\"}")
                        } else {
                            fileCount.decrementAndGet()
                            log("Removido · $targetRel")
                            response(output, 200, "application/json", "{\"deleted\":\"${FormatUtils.escapeJson(targetRel)}\"}")
                        }
                    }
                    method == "PUT" && effectiveUploadRel != null -> {
                        if (contentLength <= 0) { response(output, 411, "application/json", "{\"error\":\"Content-Length obrigatorio\"}"); return }
                        if (contentLength > 500L * 1024 * 1024) { response(output, 413, "application/json", "{\"error\":\"Limite de upload: 500 MB\"}"); return }
                        val rel = effectiveUploadRel
                        val targetFile = resolvePath(rel)
                        if (targetFile == null || rel.isBlank() || targetFile == root) { response(output, 400, "application/json", "{\"error\":\"Caminho invalido\"}"); return }
                        if (!allowed(targetFile.name)) { response(output, 415, "application/json", "{\"error\":\"Tipo de arquivo bloqueado pela configuracao\"}"); return }
                        if (!targetFile.parentFile.exists() && !targetFile.parentFile.mkdirs()) { response(output, 500, "application/json", "{\"error\":\"Nao foi possivel criar a pasta pai\"}"); return }
                        val existed = targetFile.exists()
                        val tempFile = File(targetFile.parentFile, ".${targetFile.name}.${UUID.randomUUID()}.upload")
                        powerManager?.onTransferStart()
                        try {
                            tempFile.outputStream().use { out ->
                                val buffer = ByteArray(64 * 1024)
                                var remaining = contentLength
                                while (remaining > 0) {
                                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                    if (read < 0) throw java.io.EOFException()
                                    out.write(buffer, 0, read)
                                    remaining -= read
                                    bytesReceived.addAndGet(read.toLong())
                                }
                                out.fd.sync()
                            }
                            try {
                                Files.move(tempFile.toPath(), targetFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                                Files.move(tempFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                            }
                            if (!existed) fileCount.incrementAndGet()
                        } finally {
                            powerManager?.onTransferEnd()
                            if (tempFile.exists()) tempFile.delete()
                        }
                        log("${if (existed) "Arquivo atualizado" else "Upload concluido"} · $rel · $contentLength bytes")
                        response(output, if (existed) 200 else 201, "application/json", "{\"saved\":\"${FormatUtils.escapeJson(rel)}\",\"bytes\":$contentLength}")
                    }
                    method in setOf("GET", "HEAD") && isWebDav -> {
                        val file = if (webdavRel.isNullOrEmpty()) root else resolvePath(webdavRel)
                        if (file == null) {
                            response(output, 400, "text/plain", "Caminho invalido", head = isHead)
                        } else if (file.isDirectory) {
                            if (headers["depth"] != null || headers["user-agent"]?.contains("WebDAV", ignoreCase = true) == true || headers["user-agent"]?.contains("MiniRedir", ignoreCase = true) == true) {
                                WebDavHandler.handlePropFind(output, path, headers["depth"] ?: "1", root, ::resolvePath, ::allowed, isHead)
                            } else {
                                response(output, 200, "text/html; charset=utf-8", WebDashboardRenderer.renderDirectoryPage(file, root, ::allowed), head = isHead, acceptGzip = acceptsGzip)
                            }
                        } else if (!file.isFile || !allowed(file.name)) {
                            response(output, 404, "text/plain", "Arquivo nao encontrado", head = isHead)
                        } else {
                            sendFile(output, file, attachment = false, head = isHead, rangeHeader = headers["range"], ifNoneMatch = headers["if-none-match"])
                        }
                    }
                    method in setOf("GET", "HEAD") && (path == "/stream/playlist.m3u" || path == "/playlist.m3u") -> {
                        val playlistBytes = MediaStreamHandler.generateM3uPlaylist(root, headers["host"], s.localAddress.hostAddress ?: "127.0.0.1", port, ::allowed)
                        output.write("HTTP/1.1 200 OK\r\nDate: ${FormatUtils.httpDate()}\r\nContent-Type: audio/x-mpegurl; charset=utf-8\r\nContent-Disposition: inline; filename=\"andserver-streaming.m3u\"\r\nContent-Length: ${playlistBytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        if (!isHead) output.write(playlistBytes)
                        output.flush()
                        log("Playlist M3U gerada · $clientIp")
                    }
                    method in setOf("GET", "HEAD") && path == "/api/media" -> {
                        val json = MediaStreamHandler.getMediaCatalogJson(root, ::allowed)
                        response(output, 200, "application/json; charset=utf-8", json, head = isHead, acceptGzip = acceptsGzip)
                    }
                    method in setOf("GET", "HEAD") && path.startsWith("/stream/") && path != "/stream/playlist.m3u" -> {
                        val rel = path.removePrefix("/stream/").trim('/')
                        val file = resolvePath(rel)
                        if (file == null || !file.isFile || !allowed(file.name)) {
                            response(output, 404, "text/plain", "Mídia não encontrada", head = isHead)
                        } else {
                            sendFile(output, file, attachment = false, head = isHead, rangeHeader = headers["range"], ifNoneMatch = headers["if-none-match"])
                        }
                    }
                    method in setOf("GET", "HEAD") && (path.startsWith("/files/") || (type in setOf("web", "site") && path.startsWith("/public/"))) -> {
                        val attachment = path.startsWith("/files/")
                        val prefix = if (attachment) "/files/" else "/public/"
                        val rel = path.removePrefix(prefix).trimEnd('/').ifEmpty { "index.html" }
                        val file = resolvePath(rel)
                        if (file == null) {
                            response(output, 400, "text/plain", "Caminho invalido", head = isHead)
                        } else if (file.isDirectory) {
                            val index = File(file, "index.html")
                            if (index.isFile && allowed(index.name)) {
                                sendFile(output, index, attachment, isHead, headers["range"], headers["if-none-match"])
                            } else if (!attachment) {
                                response(output, 200, "text/html; charset=utf-8", WebDashboardRenderer.renderDirectoryPage(file, root, ::allowed), head = isHead, acceptGzip = acceptsGzip)
                            } else {
                                response(output, 404, "text/plain", "Pasta sem arquivo index", head = isHead)
                            }
                        } else if (!file.isFile || !allowed(file.name)) {
                            response(output, 404, "text/plain", "Arquivo nao encontrado", head = isHead)
                        } else {
                            sendFile(output, file, attachment, isHead, headers["range"], headers["if-none-match"])
                        }
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
                                sendFile(output, siteIndex, attachment = false, head = isHead, rangeHeader = headers["range"], ifNoneMatch = headers["if-none-match"])
                            }
                            else -> response(output, 404, "text/html; charset=utf-8", "<h1>404 Arquivo Não Encontrado</h1><p>Recurso não encontrado no site hospedado.</p>", head = isHead)
                        }
                    }
                    else -> response(output, 404, "application/json; charset=utf-8", "{\"error\":\"Rota nao encontrada\"}")
                }
            } catch (e: Exception) {
                log("Falha de requisicao · ${e.javaClass.simpleName}")
            } finally {
                activeClients.decrementAndGet()
                openSockets.remove(s)
            }
        }
    }

    private fun queryParameter(target: String, name: String): String? = target.substringAfter('?', "").split('&').firstNotNullOfOrNull { item ->
        val parts = item.split('=', limit = 2)
        if (parts.size == 2 && parts[0] == name) try { URLDecoder.decode(parts[1], "UTF-8") } catch (_: Exception) { null } else null
    }

    private fun authorized(header: String?): Boolean {
        if (header == null || !header.startsWith("Basic ", true)) return false
        val credentials = try { String(Base64.decode(header.substringAfter(' '), Base64.DEFAULT), Charsets.UTF_8) } catch (_: Exception) { return false }
        val supplied = credentials.substringAfter(':', "")
        return MessageDigest.isEqual(password.toByteArray(), supplied.toByteArray())
    }

    fun resolvePath(raw: String): File? {
        val relative = raw.trimStart('/')
        if (relative.isBlank() || relative.split('/').any { it == ".." || it == "." || it.isEmpty() } || relative.contains('\\') || relative.contains('\u0000')) return if (relative.isEmpty()) root else null
        val base = root.canonicalFile
        val result = File(base, relative).canonicalFile
        return result.takeIf { it.path.startsWith(base.path + File.separator) || it == base }
    }

    private fun isCompressible(type: String): Boolean =
        type.startsWith("text/") ||
        type.contains("json") ||
        type.contains("javascript") ||
        type.contains("xml") ||
        type.contains("svg")

    private fun response(out: OutputStream, code: Int, type: String, body: String, head: Boolean = false, acceptGzip: Boolean = false) {
        val rawBytes = body.toByteArray(Charsets.UTF_8)
        val shouldGzip = acceptGzip && isCompressible(type) && rawBytes.size > 256
        val (finalBytes, isGzipped) = if (shouldGzip) {
            val bos = ByteArrayOutputStream()
            GZIPOutputStream(bos).use { it.write(rawBytes) }
            val compressed = bos.toByteArray()
            if (compressed.size < rawBytes.size) Pair(compressed, true) else Pair(rawBytes, false)
        } else {
            Pair(rawBytes, false)
        }

        val label = when (code) {
            200 -> "OK"; 201 -> "Created"; 202 -> "Accepted"; 204 -> "No Content"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"
            405 -> "Method Not Allowed"; 410 -> "Gone"; 411 -> "Length Required"; 413 -> "Payload Too Large"; 415 -> "Unsupported Media Type"
            416 -> "Range Not Satisfiable"; 429 -> "Too Many Requests"; 431 -> "Request Header Fields Too Large"; 500 -> "Internal Server Error"; else -> "Error"
        }
        val encodingHeader = if (isGzipped) "Content-Encoding: gzip\r\nVary: Accept-Encoding\r\n" else ""
        out.write("HTTP/1.1 $code $label\r\nDate: ${FormatUtils.httpDate()}\r\nServer: AndServer/1.0\r\nCache-Control: no-store\r\nContent-Type: $type\r\n${encodingHeader}Content-Length: ${finalBytes.size}\r\nAllow: GET, HEAD, PUT, POST, DELETE, MKCOL, OPTIONS\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
        if (!head && code != 204) {
            out.write(finalBytes)
            bytesSent.addAndGet(finalBytes.size.toLong())
        }
        out.flush()
    }

    private fun sendFile(out: OutputStream, file: File, attachment: Boolean, head: Boolean, rangeHeader: String?, ifNoneMatch: String?) {
        powerManager?.onTransferStart()
        try {
            val size = file.length()
            val etag = "\"${size.toString(16)}-${file.lastModified().toString(16)}\""
            if (ifNoneMatch?.split(',')?.any { it.trim() == etag || it.trim() == "*" } == true) {
                out.write("HTTP/1.1 304 Not Modified\r\nDate: ${FormatUtils.httpDate()}\r\nETag: $etag\r\nCache-Control: public, max-age=0, must-revalidate\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
                out.flush()
                return
            }
            var start = 0L
            var end = (size - 1).coerceAtLeast(0)
            var partial = false
            if (rangeHeader != null) {
                val match = Regex("bytes=(\\d*)-(\\d*)").matchEntire(rangeHeader.trim())
                if (match == null || size == 0L) {
                    out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$size\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    return
                }
                val a = match.groupValues[1]
                val b = match.groupValues[2]
                if (a.isEmpty()) {
                    val suffix = b.toLongOrNull() ?: 0
                    start = (size - suffix.coerceAtMost(size)).coerceAtLeast(0)
                } else {
                    start = a.toLongOrNull() ?: size
                }
                end = if (b.isEmpty()) size - 1 else (b.toLongOrNull() ?: -1).coerceAtMost(size - 1)
                if (start >= size || end < start) {
                    out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$size\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    return
                }
                partial = true
            }
            val length = if (size == 0L) 0 else end - start + 1
            val name = file.name.replace("\"", "_").replace("\r", "_").replace("\n", "_")
            val disposition = if (attachment) "attachment" else "inline"
            val status = if (partial) "206 Partial Content" else "200 OK"
            out.write("HTTP/1.1 $status\r\nDate: ${FormatUtils.httpDate()}\r\nServer: AndServer/1.0\r\nContent-Type: ${MimeTypes.getMimeType(file.name)}\r\nContent-Length: $length\r\nAccept-Ranges: bytes\r\nETag: $etag\r\nLast-Modified: ${FormatUtils.httpDate(file.lastModified())}\r\nCache-Control: public, max-age=0, must-revalidate\r\nContent-Disposition: $disposition; filename=\"$name\"; filename*=UTF-8''${FormatUtils.urlPath(file.name)}\r\n${if (partial) "Content-Range: bytes $start-$end/$size\r\n" else ""}Connection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
            if (!head && length > 0) {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(start)
                    // Buffer otimizado de 128 KB para streaming e arquivos grandes
                    val bufferSize = if (length >= 512 * 1024) 128 * 1024 else 32 * 1024
                    val buffer = ByteArray(bufferSize)
                    var remaining = length
                    while (remaining > 0) {
                        val read = raf.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        bytesSent.addAndGet(read.toLong())
                        remaining -= read
                    }
                }
            }
            out.flush()
        } finally {
            powerManager?.onTransferEnd()
        }
    }
}
