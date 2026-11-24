package com.andserver

import android.graphics.Bitmap
import android.graphics.Color

object QrGenerator {
    private val expTable = IntArray(512)
    private val logTable = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            expTable[i] = x
            logTable[x] = i
            x = x shl 1
            if (x >= 256) x = x xor 285
        }
        for (i in 255 until 512) {
            expTable[i] = expTable[i - 255]
        }
    }

    private fun gfMul(a: Int, b: Int): Int {
        if (a == 0 || b == 0) return 0
        return expTable[(logTable[a] + logTable[b]) % 255]
    }

    private fun rsGeneratorPoly(ecCount: Int): IntArray {
        var gen = intArrayOf(1)
        for (i in 0 until ecCount) {
            val next = IntArray(gen.size + 1)
            val root = expTable[i]
            for (j in gen.indices) {
                next[j] = next[j] xor gen[j]
                next[j + 1] = next[j + 1] xor gfMul(gen[j], root)
            }
            gen = next
        }
        return gen
    }

    fun encode(text: String): Array<BooleanArray> {
        val bytes = text.toByteArray(Charsets.UTF_8)
        // Versão 3 (29x29, até 53 bytes) ou Versão 4 (33x33, até 78 bytes) ou Versão 5 (37x37, até 106 bytes)
        val (version, size, totalCodewords, dataCodewords, ecCodewords, alignPos) = when {
            bytes.size <= 53 -> arrayOf(3, 29, 70, 55, 15, 22)
            bytes.size <= 78 -> arrayOf(4, 33, 100, 80, 20, 26)
            else -> arrayOf(5, 37, 134, 108, 26, 30)
        }

        // Codificação em Modo Byte (0100)
        val bitBuffer = mutableListOf<Boolean>()
        fun addBits(value: Int, count: Int) {
            for (i in count - 1 downTo 0) {
                bitBuffer.add(((value ushr i) and 1) == 1)
            }
        }

        addBits(4, 4) // Modo Byte = 4
        addBits(bytes.size, 8) // Tamanho (8 bits para versões 1 a 9)
        for (b in bytes) {
            addBits(b.toInt() and 0xFF, 8)
        }

        // Terminador (até 4 bits 0)
        val totalDataBits = dataCodewords * 8
        val termLen = minOf(4, totalDataBits - bitBuffer.size)
        repeat(termLen) { bitBuffer.add(false) }

        // Alinhamento para múltiplos de 8
        while (bitBuffer.size % 8 != 0) {
            bitBuffer.add(false)
        }

        // Codewords de dados com preenchimento alternado 0xEC e 0x11
        val dataBytes = IntArray(dataCodewords)
        for (i in 0 until bitBuffer.size / 8) {
            var v = 0
            for (bit in 0 until 8) {
                if (bitBuffer[i * 8 + bit]) v = v or (1 shl (7 - bit))
            }
            dataBytes[i] = v
        }
        var padToggle = true
        for (i in (bitBuffer.size / 8) until dataCodewords) {
            dataBytes[i] = if (padToggle) 0xEC else 0x11
            padToggle = !padToggle
        }

        // Cálculo dos codewords de Correção de Erro (Reed-Solomon)
        val gen = rsGeneratorPoly(ecCodewords)
        val ecBytes = IntArray(ecCodewords)
        for (b in dataBytes) {
            val factor = b xor ecBytes[0]
            for (j in 0 until ecCodewords - 1) {
                ecBytes[j] = ecBytes[j + 1] xor gfMul(gen[j + 1], factor)
            }
            ecBytes[ecCodewords - 1] = gfMul(gen[ecCodewords], factor)
        }

        // Agrupamento de todos os codewords
        val allCodewords = IntArray(totalCodewords)
        System.arraycopy(dataBytes, 0, allCodewords, 0, dataCodewords)
        System.arraycopy(ecBytes, 0, allCodewords, dataCodewords, ecCodewords)

        val allBits = mutableListOf<Boolean>()
        for (c in allCodewords) {
            for (i in 7 downTo 0) {
                allBits.add(((c ushr i) and 1) == 1)
            }
        }

        // Matriz QR
        val matrix = Array(size) { BooleanArray(size) }
        val reserved = Array(size) { BooleanArray(size) }

        fun setModule(r: Int, c: Int, dark: Boolean) {
            if (r in 0 until size && c in 0 until size) {
                matrix[r][c] = dark
                reserved[r][c] = true
            }
        }

        // Padrões de Localização (Finder Patterns 7x7) + Separadores
        fun placeFinder(top: Int, left: Int) {
            for (r in -1..7) {
                for (c in -1..7) {
                    val mr = top + r
                    val mc = left + c
                    if (mr in 0 until size && mc in 0 until size) {
                        val dark = (r in 0..6 && (c == 0 || c == 6)) ||
                                   (c in 0..6 && (r == 0 || r == 6)) ||
                                   (r in 2..4 && c in 2..4)
                        setModule(mr, mc, dark)
                    }
                }
            }
        }
        placeFinder(0, 0)
        placeFinder(0, size - 7)
        placeFinder(size - 7, 0)

        // Padrão de Alinhamento (5x5)
        for (r in -2..2) {
            for (c in -2..2) {
                val dark = (r == -2 || r == 2 || c == -2 || c == 2 || (r == 0 && c == 0))
                setModule(alignPos + r, alignPos + c, dark)
            }
        }

        // Padrões de Temporização (Timing)
        for (i in 8 until size - 8) {
            setModule(6, i, i % 2 == 0)
            setModule(i, 6, i % 2 == 0)
        }

        // Módulo Escuro Fixo
        setModule(size - 8, 8, true)

        // Formato (Nível L, Máscara 0 = (r+c)%2 == 0) -> bits 111011010010110
        val formatBits = intArrayOf(1, 1, 1, 0, 1, 1, 0, 1, 0, 0, 1, 0, 1, 1, 0)
        val formatCoordsTopLeft = listOf(
            0 to 8, 1 to 8, 2 to 8, 3 to 8, 4 to 8, 5 to 8, 7 to 8, 8 to 8,
            8 to 7, 8 to 5, 8 to 4, 8 to 3, 8 to 2, 8 to 1, 8 to 0
        )
        for (i in 0 until 15) {
            val (r, c) = formatCoordsTopLeft[i]
            setModule(r, c, formatBits[i] == 1)
        }
        for (i in 0..6) {
            setModule(size - 1 - i, 8, formatBits[i] == 1)
        }
        for (i in 7..14) {
            setModule(8, size - 15 + i, formatBits[i] == 1)
        }

        // Posicionamento em zigue-zague dos dados + Máscara 0
        var bitIndex = 0
        var upwards = true
        var col = size - 1
        while (col > 0) {
            if (col == 6) col-- // pula a linha de temporização
            val rowRange = if (upwards) (size - 1 downTo 0) else (0 until size)
            for (row in rowRange) {
                for (c in listOf(col, col - 1)) {
                    if (!reserved[row][c]) {
                        var bit = if (bitIndex < allBits.size) allBits[bitIndex++] else false
                        if ((row + c) % 2 == 0) bit = !bit // Máscara 0
                        matrix[row][c] = bit
                        reserved[row][c] = true
                    }
                }
            }
            upwards = !upwards
            col -= 2
        }

        return matrix
    }

    fun toBitmap(matrix: Array<BooleanArray>, sizePx: Int = 400, quietZone: Int = 4): Bitmap {
        val n = matrix.size
        val totalModules = n + (quietZone * 2)
        val scale = maxOf(1, sizePx / totalModules)
        val finalSize = totalModules * scale
        val bitmap = Bitmap.createBitmap(finalSize, finalSize, Bitmap.Config.ARGB_8888)

        // Fundo branco
        bitmap.eraseColor(Color.WHITE)

        val pixels = IntArray(scale * scale) { Color.rgb(15, 23, 42) } // Slate profundo elegante
        for (r in 0 until n) {
            for (c in 0 until n) {
                if (matrix[r][c]) {
                    val startX = (c + quietZone) * scale
                    val startY = (r + quietZone) * scale
                    bitmap.setPixels(pixels, 0, scale, startX, startY, scale, scale)
                }
            }
        }
        return bitmap
    }

    fun toSvg(matrix: Array<BooleanArray>, quietZone: Int = 4): String {
        val n = matrix.size
        val total = n + (quietZone * 2)
        val sb = StringBuilder()
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $total $total" shape-rendering="crispEdges">""")
        sb.append("""<rect width="100%" height="100%" fill="#ffffff"/>""")
        sb.append("""<path fill="#0f172a" d="""")
        for (r in 0 until n) {
            for (c in 0 until n) {
                if (matrix[r][c]) {
                    sb.append("M${c + quietZone},${r + quietZone}h1v1h-1z")
                }
            }
        }
        sb.append(""""/> </svg>""")
        return sb.toString()
    }
}
