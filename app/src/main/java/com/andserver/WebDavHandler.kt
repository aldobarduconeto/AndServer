package com.andserver

import java.io.File
import java.io.OutputStream
import java.net.URI
import java.util.UUID

/**
 * Motor de protocolo WebDAV (RFC 4918) para AndServer.
 * Permite que o celular seja montado nativamente como uma Unidade de Rede (Network Drive)
 * no Windows Explorer, macOS Finder, Linux Nautilus/Dolphin e clientes WebDAV móveis.
 */
object WebDavHandler {

    fun handlePropFind(
        output: OutputStream,
        rawPath: String,
        depth: String,
        root: File,
        resolvePath: (String) -> File?,
        allowed: (String) -> Boolean,
        isHead: Boolean
    ) {
        val cleanRel = rawPath.removePrefix("/webdav/").removePrefix("/webdav").removePrefix("/drive/").removePrefix("/drive").trim('/')
        val target = if (cleanRel.isEmpty()) root else resolvePath(cleanRel)
        if (target == null || !target.exists()) {
            val errXml = "<?xml version=\"1.0\" encoding=\"utf-8\"?><D:error xmlns:D=\"DAV:\"><D:response-not-found/></D:error>"
            val bytes = errXml.toByteArray(Charsets.UTF_8)
            output.write("HTTP/1.1 404 Not Found\r\nDate: ${FormatUtils.httpDate()}\r\nContent-Type: application/xml; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            if (!isHead) output.write(bytes)
            output.flush()
            return
        }

        val prefix = if (rawPath.startsWith("/drive")) "/drive" else "/webdav"
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<D:multistatus xmlns:D=\"DAV:\">\n")

        fun appendItem(f: File) {
            val itemRel = f.relativeTo(root).invariantSeparatorsPath.trim('/')
            val href = if (itemRel.isEmpty()) "$prefix/" else "$prefix/${FormatUtils.urlPath(itemRel)}${if (f.isDirectory) "/" else ""}"
            val isDir = f.isDirectory
            val lastMod = FormatUtils.httpDate(f.lastModified())
            val name = FormatUtils.escapeHtml(if (itemRel.isEmpty()) "drive" else f.name)
            sb.append("  <D:response>\n")
            sb.append("    <D:href>$href</D:href>\n")
            sb.append("    <D:propstat>\n")
            sb.append("      <D:prop>\n")
            sb.append("        <D:displayname>$name</D:displayname>\n")
            if (isDir) {
                sb.append("        <D:resourcetype><D:collection/></D:resourcetype>\n")
            } else {
                sb.append("        <D:resourcetype/>\n")
                sb.append("        <D:getcontentlength>${f.length()}</D:getcontentlength>\n")
                sb.append("        <D:getcontenttype>${MimeTypes.getMimeType(f.name)}</D:getcontenttype>\n")
            }
            sb.append("        <D:getlastmodified>$lastMod</D:getlastmodified>\n")
            sb.append("      </D:prop>\n")
            sb.append("      <D:status>HTTP/1.1 200 OK</D:status>\n")
            sb.append("    </D:propstat>\n")
            sb.append("  </D:response>\n")
        }

        appendItem(target)
        if (target.isDirectory && depth != "0") {
            target.listFiles()?.filter { it.isDirectory || allowed(it.name) }
                ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
                ?.forEach { appendItem(it) }
        }
        sb.append("</D:multistatus>")

        val bytes = sb.toString().toByteArray(Charsets.UTF_8)
        output.write("HTTP/1.1 207 Multi-Status\r\nDate: ${FormatUtils.httpDate()}\r\nContent-Type: application/xml; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
        if (!isHead) output.write(bytes)
        output.flush()
    }

    fun handleLock(output: OutputStream, isHead: Boolean) {
        val token = "opaquelocktoken:${UUID.randomUUID()}"
        val xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<D:prop xmlns:D=\"DAV:\">\n  <D:lockdiscovery>\n    <D:activelock>\n      <D:locktype><D:write/></D:locktype>\n      <D:lockscope><D:exclusive/></D:lockscope>\n      <D:depth>Infinity</D:depth>\n      <D:timeout>Second-3600</D:timeout>\n      <D:locktoken><D:href>$token</D:href></D:locktoken>\n    </D:activelock>\n  </D:lockdiscovery>\n</D:prop>"
        val bytes = xml.toByteArray(Charsets.UTF_8)
        output.write("HTTP/1.1 200 OK\r\nLock-Token: <$token>\r\nContent-Type: application/xml; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
        if (!isHead) output.write(bytes)
        output.flush()
    }

    fun handleUnlock(output: OutputStream) {
        output.write("HTTP/1.1 204 No Content\r\nDate: ${FormatUtils.httpDate()}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    fun handlePropPatch(output: OutputStream, path: String, isHead: Boolean) {
        val xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<D:multistatus xmlns:D=\"DAV:\"><D:response><D:href>${FormatUtils.escapeHtml(path)}</D:href><D:propstat><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>"
        val bytes = xml.toByteArray(Charsets.UTF_8)
        output.write("HTTP/1.1 207 Multi-Status\r\nContent-Type: application/xml; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
        if (!isHead) output.write(bytes)
        output.flush()
    }

    fun handleMove(
        output: OutputStream,
        path: String,
        destHeader: String?,
        resolvePath: (String) -> File?,
        log: (String) -> Unit
    ) {
        val srcRel = path.removePrefix("/webdav/").removePrefix("/webdav").removePrefix("/drive/").removePrefix("/drive").trim('/')
        val srcFile = if (srcRel.isEmpty()) null else resolvePath(srcRel)
        val destUri = try { URI(destHeader ?: "") } catch (_: Exception) { null }
        val destPath = (destUri?.path ?: destHeader ?: "").removePrefix("/webdav/").removePrefix("/webdav").removePrefix("/drive/").removePrefix("/drive").trim('/')
        val destFile = if (destPath.isEmpty()) null else resolvePath(destPath)

        if (srcFile == null || !srcFile.exists() || destFile == null) {
            writeJsonError(output, 400, "Origem ou destino invalido")
        } else {
            if (destFile.exists()) destFile.deleteRecursively()
            if (srcFile.renameTo(destFile)) {
                log("Movido / Renomeado (WebDAV) · $srcRel -> $destPath")
                writeJsonOk(output, 201, "{\"moved\":\"${FormatUtils.escapeJson(destPath)}\"}")
            } else {
                writeJsonError(output, 500, "Falha ao mover arquivo")
            }
        }
    }

    fun handleCopy(
        output: OutputStream,
        path: String,
        destHeader: String?,
        resolvePath: (String) -> File?,
        log: (String) -> Unit
    ) {
        val srcRel = path.removePrefix("/webdav/").removePrefix("/webdav").removePrefix("/drive/").removePrefix("/drive").trim('/')
        val srcFile = if (srcRel.isEmpty()) null else resolvePath(srcRel)
        val destUri = try { URI(destHeader ?: "") } catch (_: Exception) { null }
        val destPath = (destUri?.path ?: destHeader ?: "").removePrefix("/webdav/").removePrefix("/webdav").removePrefix("/drive/").removePrefix("/drive").trim('/')
        val destFile = if (destPath.isEmpty()) null else resolvePath(destPath)

        if (srcFile == null || !srcFile.exists() || destFile == null) {
            writeJsonError(output, 400, "Origem ou destino invalido")
        } else {
            if (srcFile.isDirectory) srcFile.copyRecursively(destFile, overwrite = true)
            else srcFile.copyTo(destFile, overwrite = true)
            log("Copiado (WebDAV) · $srcRel -> $destPath")
            writeJsonOk(output, 201, "{\"copied\":\"${FormatUtils.escapeJson(destPath)}\"}")
        }
    }

    private fun writeJsonError(output: OutputStream, status: Int, errorMsg: String) {
        val bytes = "{\"error\":\"$errorMsg\"}".toByteArray(Charsets.UTF_8)
        output.write("HTTP/1.1 $status Error\r\nDate: ${FormatUtils.httpDate()}\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun writeJsonOk(output: OutputStream, status: Int, jsonPayload: String) {
        val bytes = jsonPayload.toByteArray(Charsets.UTF_8)
        output.write("HTTP/1.1 $status OK\r\nDate: ${FormatUtils.httpDate()}\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }
}
