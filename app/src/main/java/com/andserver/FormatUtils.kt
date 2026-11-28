package com.andserver

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Utilitários centralizados de formatação e manipulação de rede/texto.
 */
object FormatUtils {

    /**
     * Formata bytes em representação humana legível (B, KB, MB, GB).
     */
    fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    /**
     * Formata milissegundos em duração HH:mm:ss ou mm:ss.
     */
    fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> "%02d:%02d:%02d".format(h, m, s)
            else -> "%02d:%02d".format(m, s)
        }
    }

    /**
     * Obtém o primeiro endereço IPv4 não-loopback da interface ativa de rede local (Wi-Fi/Ethernet).
     */
    fun getLanAddress(): String {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { !it.isLoopback && it.isUp }
                .flatMap { it.inetAddresses.asSequence() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress ?: "127.0.0.1"
        } catch (_: Exception) {
            "127.0.0.1"
        }
    }

    /**
     * Formata carimbo de data/hora no padrão RFC 1123 / HTTP Date GMT.
     */
    fun httpDate(timestamp: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }.format(Date(timestamp))

    /**
     * Escapa caracteres especiais para inserção segura em HTML.
     */
    fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    /**
     * Escapa caracteres de controle e aspas para JSON válido.
     */
    fun escapeJson(s: String): String = buildString {
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

    /**
     * Codifica segmentos de caminho preservando as barras para URLs.
     */
    fun urlPath(s: String): String =
        s.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    /**
     * Lê uma linha ASCII terminada em \n ou \r\n com limite máximo de bytes para evitar DoS de memória.
     */
    fun BufferedInputStream.readLineAscii(max: Int): String? {
        val out = ByteArrayOutputStream(64)
        var count = 0
        while (count < max) {
            val b = read()
            if (b < 0) {
                if (out.size() == 0) return null
                break
            }
            count++
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
        }
        return out.toString("US-ASCII")
    }
}
