package com.andserver

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.net.SocketTimeoutException

/**
 * Motor de comunicação RFC 6455 do protocolo WebSocket para streaming bidirecional
 * em tempo real de logs, status e métricas operacionais para o dashboard web.
 */
object WebSocketHandler {

    fun readWebSocketFrame(input: DataInputStream): WebSocketFrame? {
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

    fun sendWebSocketFrame(output: BufferedOutputStream, lock: Any, opcode: Int, payload: ByteArray) {
        synchronized(lock) {
            output.write(0x80 or (opcode and 0x0f))
            when {
                payload.size < 126 -> output.write(payload.size)
                payload.size <= 65_535 -> {
                    output.write(126)
                    output.write(payload.size ushr 8)
                    output.write(payload.size)
                }
                else -> {
                    output.write(127)
                    for (shift in 56 downTo 0 step 8) output.write((payload.size.toLong() ushr shift).toInt())
                }
            }
            output.write(payload)
            output.flush()
        }
    }

    fun broadcast(peers: Set<WebSocketPeer>, payload: ByteArray) {
        peers.toList().forEach { peer ->
            try {
                sendWebSocketFrame(peer.output, peer.lock, 0x1, payload)
            } catch (_: Exception) {
                try { peer.socket.close() } catch (_: Exception) { }
            }
        }
    }

    fun webSocketLoop(
        peer: WebSocketPeer,
        input: BufferedInputStream,
        isRunning: () -> Boolean,
        onClose: () -> Unit
    ) {
        peer.socket.soTimeout = 30_000
        val data = DataInputStream(input)
        var awaitingPong = false
        try {
            while (!peer.socket.isClosed && isRunning()) {
                val frame = readWebSocketFrame(data) ?: break
                when (frame.opcode) {
                    0x8 -> {
                        sendWebSocketFrame(peer.output, peer.lock, 0x8, ByteArray(0))
                        break
                    }
                    0x9 -> sendWebSocketFrame(peer.output, peer.lock, 0xa, frame.payload)
                    0xa -> awaitingPong = false
                    0x1 -> {
                        val text = String(frame.payload, Charsets.UTF_8)
                        if (text == "ping") {
                            sendWebSocketFrame(peer.output, peer.lock, 0x1, "{\"type\":\"pong\"}".toByteArray(Charsets.UTF_8))
                        }
                    }
                }
            }
        } catch (_: SocketTimeoutException) {
            if (awaitingPong) {
                try { peer.socket.close() } catch (_: Exception) { }
            } else {
                awaitingPong = true
                try { sendWebSocketFrame(peer.output, peer.lock, 0x9, ByteArray(0)) } catch (_: Exception) { }
            }
        } catch (_: Exception) {
            // Conexão encerrada normalmente
        } finally {
            try { peer.socket.close() } catch (_: Exception) { }
            onClose()
        }
    }
}
