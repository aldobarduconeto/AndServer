package com.andserver

import java.io.File

/**
 * Manipulador de mídia e streaming para AndServer.
 * Gera playlists no formato RFC 8216 / Extended M3U (#EXTM3U) e catálogo multimídia JSON
 * para reprodutores compatíveis (VLC, Kodi, Smart TVs e reprodutor web integrado).
 */
object MediaStreamHandler {

    private val MEDIA_EXTENSIONS = setOf(
        "mp4", "mkv", "webm", "mov", "avi", "ts",
        "mp3", "wav", "ogg", "flac", "m4a", "aac", "opus"
    )

    fun scanMediaFiles(root: File, allowed: (String) -> Boolean): Pair<List<File>, List<File>> {
        val all = root.walkTopDown().filter { it.isFile && allowed(it.name) }.toList()
        val videos = all.filter { MimeTypes.isVideo(it.name) }
        val audios = all.filter { MimeTypes.isAudio(it.name) }
        return Pair(videos, audios)
    }

    fun generateM3uPlaylist(
        root: File,
        hostHeader: String?,
        localAddress: String,
        port: Int,
        allowed: (String) -> Boolean
    ): ByteArray {
        val host = hostHeader ?: "$localAddress:$port"
        val scheme = "https"
        val files = root.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in MEDIA_EXTENSIONS && allowed(it.name) }
            .toList()

        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        files.forEach { f ->
            val rel = f.relativeTo(root).invariantSeparatorsPath
            val isVideo = MimeTypes.isVideo(f.name)
            val group = if (isVideo) "Vídeos" else "Músicas"
            sb.append("#EXTINF:-1 group-title=\"$group\",${f.nameWithoutExtension}\n")
            sb.append("$scheme://$host/files/${FormatUtils.urlPath(rel)}\n")
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    fun getMediaCatalogJson(root: File, allowed: (String) -> Boolean): String {
        val (videos, audios) = scanMediaFiles(root, allowed)

        fun toJsonArray(list: List<File>): String = list.joinToString(",") { f ->
            val rel = f.relativeTo(root).invariantSeparatorsPath
            "{\"name\":\"${FormatUtils.escapeJson(f.name)}\",\"path\":\"${FormatUtils.escapeJson(rel)}\",\"size\":${f.length()},\"sizeFormatted\":\"${FormatUtils.formatBytes(f.length())}\",\"modified\":${f.lastModified()},\"mime\":\"${MimeTypes.getMimeType(f.name)}\",\"url\":\"/files/${FormatUtils.urlPath(rel)}\"}"
        }

        return "{\"videos\":[${toJsonArray(videos)}],\"audios\":[${toJsonArray(audios)}],\"totalVideos\":${videos.size},\"totalAudios\":${audios.size},\"playlistUrl\":\"/stream/playlist.m3u\"}"
    }
}
