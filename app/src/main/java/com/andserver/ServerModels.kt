package com.andserver

import java.io.BufferedOutputStream
import java.net.Socket

/**
 * Snapshot imutável do estado operacional do servidor AndServer.
 */
data class ServerSnapshot(
    val running: Boolean,
    val port: Int?,
    val requests: Long,
    val clients: Int,
    val files: Int,
    val startedAt: Long,
    val recentEvents: List<String>,
    val type: String,
    val bytesReceived: Long,
    val bytesSent: Long,
    val networkScope: String = "local",
    val publicIp: String? = null,
    val storageLocation: String = "internal",
    val powerProfile: String = ""
)

/**
 * Configuração de inicialização ou reconfiguração do servidor.
 */
data class ServerConfig(
    val port: Int,
    val type: String,
    val filters: Set<String>,
    val workers: Int,
    val password: String,
    val networkScope: String = "local",
    val storageLocation: String = "internal"
)

/**
 * Definição centralizada de um perfil de servidor.
 */
data class ServerProfile(
    val id: String,
    val name: String,
    val description: String,
    val icon: String = "🌐"
)

/**
 * Registro de todos os perfis disponíveis no AndServer.
 */
object ServerProfiles {
    val DRIVE = ServerProfile(
        id = "drive",
        name = "Drive na Rede Local (Armazenamento LAN)",
        description = "Mapeie seu celular como unidade de rede local (WebDAV RFC 4918) no Windows Explorer, Mac Finder ou Linux.",
        icon = "💾"
    )
    val STREAMING = ServerProfile(
        id = "streaming",
        name = "Servidor de Streaming & Mídia",
        description = "Transmita vídeos e músicas via HTTP Range (206) e playlist M3U integrada para VLC, Kodi e Smart TVs.",
        icon = "🎬"
    )
    val SITE = ServerProfile(
        id = "site",
        name = "Site Web Hospedado (www)",
        description = "Hospede seu site HTML5/CSS3/JS estático ou SPA na pasta /hosted/www com suporte a rotas e favicon.",
        icon = "🚀"
    )
    val WEB = ServerProfile(
        id = "web",
        name = "Central Web & Arquivos",
        description = "Interface web moderna com upload drag-and-drop, player multimídia, links temporários e terminal de logs.",
        icon = "🌐"
    )
    val FILES = ServerProfile(
        id = "files",
        name = "Servidor de arquivos",
        description = "Downloads e uploads diretos de arquivos com controle de tipos e visualização de diretórios.",
        icon = "📁"
    )
    val API = ServerProfile(
        id = "api",
        name = "Servidor de API",
        description = "Endpoints JSON (/api/status, /api/files, /api/logs, /api/media) e WebSocket (/ws) para automações.",
        icon = "⚡"
    )

    val ALL = listOf(DRIVE, STREAMING, SITE, WEB, FILES, API)

    fun get(id: String): ServerProfile = ALL.firstOrNull { it.id == id } ?: WEB
    fun nameOf(id: String): String = get(id).name
    fun descOf(id: String): String = get(id).description
}

/**
 * Controle de bloqueio temporário por tentativas consecutivas inválidas (Brute-Force Shield).
 */
data class IpBlock(var count: Int = 0, var blockedUntil: Long = 0)

/**
 * Link temporário seguro de compartilhamento de arquivo com data de expiração.
 */
data class ShareLink(val relPath: String, val expiresAt: Long)

/**
 * Conexão cliente WebSocket ativa.
 */
data class WebSocketPeer(val socket: Socket, val output: BufferedOutputStream, val lock: Any = Any())

/**
 * Frame RFC 6455 do protocolo WebSocket.
 */
data class WebSocketFrame(val opcode: Int, val fin: Boolean, val payload: ByteArray)
