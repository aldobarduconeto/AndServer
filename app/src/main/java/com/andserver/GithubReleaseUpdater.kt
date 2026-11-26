package com.andserver

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Consulta, valida e baixa releases oficiais do GitHub com cache inteligente e progresso em tempo real. */
internal class GithubReleaseUpdater {
    data class Release(
        val tag: String,
        val name: String,
        val notes: String,
        val downloadUrl: String,
        val sizeBytes: Long,
        val publishedAt: String,
        val prerelease: Boolean
    )

    data class UpdateCheckResult(
        val updateAvailable: Boolean,
        val currentVersion: String,
        val latestRelease: Release?,
        val message: String
    )

    @Volatile
    private var cachedRelease: Release? = null
    @Volatile
    private var lastCheckTime: Long = 0L

    fun checkUpdate(currentVersionName: String, forceRefresh: Boolean = false): UpdateCheckResult {
        val now = System.currentTimeMillis()
        val release = if (!forceRefresh && cachedRelease != null && (now - lastCheckTime < CACHE_TTL_MS)) {
            cachedRelease!!
        } else {
            val fetched = latest()
            cachedRelease = fetched
            lastCheckTime = now
            fetched
        }

        val hasNewVersion = isVersionNewer(currentVersionName, release.tag)
        val msg = if (hasNewVersion) {
            "Nova versão ${release.tag} disponível para instalação!"
        } else {
            "Você já está na versão mais recente ($currentVersionName)."
        }

        return UpdateCheckResult(
            updateAvailable = hasNewVersion,
            currentVersion = currentVersionName,
            latestRelease = release,
            message = msg
        )
    }

    fun latest(): Release {
        val json = request("https://api.github.com/repos/aldobarduconeto/AndServer/releases/latest") { conn ->
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        val release = JSONObject(json)
        val assets = release.getJSONArray("assets")
        val asset = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name") == "AndServer-release.apk" }
            ?: (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", true) }
            ?: throw IllegalStateException("Esta release não contém o arquivo AndServer-release.apk.")

        val url = asset.getString("browser_download_url")
        val size = asset.optLong("size", 0L)
        val published = release.optString("published_at", "")
        val isPrerelease = release.optBoolean("prerelease", false)

        if (!url.startsWith("https://github.com/aldobarduconeto/AndServer/releases/download/")) {
            throw IllegalStateException("Endereço de download com origem não autorizada.")
        }
        return Release(
            tag = release.optString("tag_name"),
            name = release.optString("name", release.optString("tag_name")),
            notes = release.optString("body"),
            downloadUrl = url,
            sizeBytes = size,
            publishedAt = published,
            prerelease = isPrerelease
        )
    }

    fun download(release: Release, destination: File, onProgress: ((downloaded: Long, total: Long, percent: Int) -> Unit)? = null) {
        destination.parentFile?.mkdirs()
        request(release.downloadUrl) { conn ->
            val length = if (conn.contentLengthLong > 0) conn.contentLengthLong else release.sizeBytes
            if (length > MAX_APK_SIZE) throw IllegalStateException("O APK excede o limite de segurança de 200 MB.")
            conn.inputStream.buffered(64 * 1024).use { input ->
                destination.outputStream().buffered(64 * 1024).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    var lastReportPercent = -1
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_APK_SIZE) throw IllegalStateException("O APK excede o limite de segurança de 200 MB.")
                        output.write(buffer, 0, count)

                        if (length > 0 && onProgress != null) {
                            val percent = ((total * 100) / length).toInt().coerceIn(0, 100)
                            if (percent != lastReportPercent) {
                                lastReportPercent = percent
                                onProgress(total, length, percent)
                            }
                        }
                    }
                    if (length > 0 && total != length) throw IllegalStateException("Download incompleto (recebido: $total, esperado: $length bytes).")
                    if (total == 0L) throw IllegalStateException("O arquivo baixado está vazio.")
                }
            }
        }
    }

    private fun isVersionNewer(current: String, candidate: String): Boolean {
        val currClean = current.trimStart('v', 'V').trim()
        val candClean = candidate.trimStart('v', 'V').trim()
        if (currClean == candClean) return false

        val currParts = currClean.split('.').mapNotNull { it.toIntOrNull() }
        val candParts = candClean.split('.').mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(currParts.size, candParts.size)
        for (i in 0 until maxLen) {
            val vCurr = currParts.getOrElse(i) { 0 }
            val vCand = candParts.getOrElse(i) { 0 }
            if (vCand > vCurr) return true
            if (vCand < vCurr) return false
        }
        return false
    }

    private fun <T> request(address: String, read: (HttpURLConnection) -> T): T {
        var currentUrl = address
        var redirects = 0
        while (redirects < 5) {
            val connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 25_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "AndServer-Android-Updater/1.0")
            }
            try {
                val code = connection.responseCode
                if (code in 301..308) {
                    val location = connection.getHeaderField("Location") ?: throw IllegalStateException("Redirecionamento sem cabeçalho Location.")
                    currentUrl = location
                    redirects++
                    continue
                }
                if (code !in 200..299) throw IllegalStateException("GitHub respondeu HTTP $code.")
                return read(connection)
            } finally { connection.disconnect() }
        }
        throw IllegalStateException("Muitos redirecionamentos ao baixar.")
    }

    companion object {
        private const val MAX_APK_SIZE = 200L * 1024 * 1024
        private const val CACHE_TTL_MS = 60_000L // 1 minuto de cache
    }
}
