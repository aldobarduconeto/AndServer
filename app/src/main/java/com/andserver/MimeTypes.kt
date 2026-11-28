package com.andserver

/**
 * Mapeamento e utilitários centralizados para tipos MIME e categorização de arquivos.
 */
object MimeTypes {
    private val VIDEO_EXT = setOf("mp4", "webm", "mov", "mkv", "avi", "ts")
    private val AUDIO_EXT = setOf("mp3", "wav", "ogg", "flac", "m4a", "aac", "opus")
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "svg", "ico")
    private val DOC_EXT = setOf("pdf", "txt", "md", "csv", "doc", "docx", "xls", "xlsx", "ppt", "pptx")
    private val ARCHIVE_EXT = setOf("zip", "gz", "7z", "tar", "rar")

    /**
     * Retorna o tipo MIME correspondente à extensão do arquivo.
     */
    fun getMimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js", "mjs" -> "text/javascript; charset=utf-8"
        "json", "map" -> "application/json; charset=utf-8"
        "xml" -> "application/xml; charset=utf-8"
        "txt", "md", "csv" -> "text/plain; charset=utf-8"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "avif" -> "image/avif"
        "svg" -> "image/svg+xml"
        "ico" -> "image/x-icon"
        "pdf" -> "application/pdf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "zip" -> "application/zip"
        "gz" -> "application/gzip"
        "7z" -> "application/x-7z-compressed"
        "wasm" -> "application/wasm"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "flac" -> "audio/flac"
        "m4a", "aac" -> "audio/aac"
        "opus" -> "audio/opus"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "ts" -> "video/mp2t"
        "m3u", "m3u8" -> "audio/x-mpegurl; charset=utf-8"
        else -> "application/octet-stream"
    }

    fun isVideo(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in VIDEO_EXT
    fun isAudio(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in AUDIO_EXT
    fun isMedia(name: String): Boolean = isVideo(name) || isAudio(name)

    /**
     * Retorna ícone emoji representativo para o tipo de arquivo.
     */
    fun getFileIcon(name: String, isDir: Boolean = false): String {
        if (isDir) return "📁"
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            ext in IMAGE_EXT -> "🖼️"
            ext in AUDIO_EXT -> "🎵"
            ext in VIDEO_EXT -> "🎬"
            ext in ARCHIVE_EXT -> "📦"
            ext in DOC_EXT -> "📄"
            else -> "📎"
        }
    }

    /**
     * Verifica se o arquivo é permitido pelo conjunto de filtros ativo.
     */
    fun isAllowedByFilter(name: String, filters: Set<String>): Boolean {
        if (name.startsWith(".") || name.endsWith(".upload")) return false
        if ("all" in filters) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        val group = when {
            ext in IMAGE_EXT -> "images"
            ext in DOC_EXT || ext in setOf("html", "htm", "css", "js", "mjs", "json", "xml", "wasm", "woff", "woff2") -> "documents"
            ext in AUDIO_EXT -> "audio"
            ext in VIDEO_EXT -> "video"
            ext in ARCHIVE_EXT -> "archives"
            else -> "other"
        }
        return group in filters
    }
}
