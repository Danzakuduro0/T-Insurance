package ru.tinsurance.server.files

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import ru.tinsurance.api.RejectionReason

/**
 * Удаление метаданных JPEG: EXIF/XMP (APP1) с геолокацией и моделью телефона, IPTC (APP13), комментарии.
 * Сохраняются JFIF (APP0), цветовой профиль (APP2), Adobe (APP14). Ориентация снимка из EXIF
 * переносится в минимальный EXIF только с этим полем, чтобы фото не «легло на бок».
 */
object JpegMetadata {

    fun strip(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(b.size)
        out.write(b, 0, 2)  // SOI
        var i = 2
        while (i + 1 < b.size) {
            if (b[i] != 0xFF.toByte()) throw RejectedFileException(RejectionReason.CORRUPTED)
            val marker = b[i + 1].toInt() and 0xFF
            when {
                marker == 0xFF -> { i++; continue }                       // заполняющий байт
                marker == 0xDA || marker == 0xD9 -> {                       // начало данных изображения / конец
                    out.write(b, i, b.size - i); return out.toByteArray()
                }
                marker in 0xD0..0xD7 || marker == 0x01 -> { out.write(b, i, 2); i += 2; continue }
            }
            if (i + 3 >= b.size) throw RejectedFileException(RejectionReason.CORRUPTED)
            val length = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (length < 2 || i + 2 + length > b.size) throw RejectedFileException(RejectionReason.CORRUPTED)
            when (marker) {
                0xE1 -> orientation(b, i + 4, length - 2)?.takeIf { it in 2..8 }?.let { out.write(minimalExif(it)) }
                in DROP -> Unit
                else -> out.write(b, i, 2 + length)
            }
            i += 2 + length
        }
        throw RejectedFileException(RejectionReason.CORRUPTED)
    }

    /** APP3–APP13, APP15 и комментарии — удаляем; APP1 обрабатываем отдельно. */
    private val DROP = (0xE3..0xED).toSet() + 0xEF + 0xFE

    /** Ориентация снимка из EXIF (1–8); 1 — если её нет. */
    fun orientation(b: ByteArray): Int {
        var i = 2
        while (i + 3 < b.size && b[i] == 0xFF.toByte()) {
            val marker = b[i + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) break
            val length = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (length < 2 || i + 2 + length > b.size) break
            if (marker == 0xE1) orientation(b, i + 4, length - 2)?.takeIf { it in 1..8 }?.let { return it }
            i += 2 + length
        }
        return 1
    }

    private fun orientation(b: ByteArray, start: Int, len: Int): Int? {
        if (len < 14 || String(b, start, 4, Charsets.US_ASCII) != "Exif") return null
        val tiff = start + 6
        val little = b[tiff] == 'I'.code.toByte()
        fun u16(p: Int) = if (little) (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)
        else ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
        fun u32(p: Int) = if (little) u16(p) or (u16(p + 2) shl 16) else (u16(p) shl 16) or u16(p + 2)
        val end = start + len
        val ifd = tiff + u32(tiff + 4)
        if (ifd < tiff + 8 || ifd + 2 > end) return null   // смещение из файла — не доверяем ему
        val count = u16(ifd)
        for (k in 0 until count) {
            val entry = ifd + 2 + k * 12
            if (entry + 12 > end) return null
            if (u16(entry) == 0x0112) return u16(entry + 8)
        }
        return null
    }

    /** APP1 с одним полем Orientation (little-endian TIFF). */
    private fun minimalExif(orientation: Int): ByteArray {
        val data = byteArrayOf(
            'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0,
            'I'.code.toByte(), 'I'.code.toByte(), 0x2A, 0, 8, 0, 0, 0,     // заголовок TIFF, IFD0 по смещению 8
            1, 0,                                                          // одна запись
            0x12, 0x01, 3, 0, 1, 0, 0, 0, orientation.toByte(), 0, 0, 0,   // Orientation, SHORT, 1 значение
            0, 0, 0, 0,                                                    // следующего IFD нет
        )
        val length = data.size + 2
        return byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + data
    }
}

/** Удаление текстовых и EXIF-блоков PNG (tEXt, zTXt, iTXt, eXIf, tIME). Остальное сохраняется как есть. */
object PngMetadata {
    val SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
    private val DROP = setOf("tEXt", "zTXt", "iTXt", "eXIf", "tIME")

    fun strip(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(b.size)
        out.write(b, 0, 8)
        var i = 8
        while (i + 12 <= b.size) {
            val length = ((b[i].toLong() and 0xFF) shl 24) or ((b[i + 1].toLong() and 0xFF) shl 16) or
                ((b[i + 2].toLong() and 0xFF) shl 8) or (b[i + 3].toLong() and 0xFF)
            if (length > b.size - i - 12) throw RejectedFileException(RejectionReason.CORRUPTED)
            val type = String(b, i + 4, 4, Charsets.US_ASCII)
            val chunk = 12 + length.toInt()
            if (type !in DROP) out.write(b, i, chunk)
            i += chunk
            if (type == "IEND") return out.toByteArray()
        }
        throw RejectedFileException(RejectionReason.CORRUPTED)  // нет IEND — файл обрезан
    }
}

/**
 * Перцептивный хэш (dHash, 64 бита) для поиска «той же картинки» после пересжатия, смены размера
 * или пересылки через мессенджер — SHA-256 у такой копии другой, а dHash отличается лишь на несколько бит.
 * Картинка делится на сетку 9×8, в каждой клетке — средняя яркость; бит = «клетка ярче соседней справа».
 * Ориентация из EXIF учитывается: копия, которую мессенджер повернул «по-настоящему», даёт тот же хэш.
 */
object PerceptualHash {
    private const val COLS = 9
    private const val ROWS = 8
    /** Однотонная картинка (чёрный кадр, засвет) даёт случайный хэш — такие не сравниваем. */
    private const val MIN_CONTRAST = 8.0

    fun dHash(image: BufferedImage, orientation: Int = 1): Long? {
        val w = image.width
        val h = image.height
        val swapped = orientation in 5..8
        val ow = if (swapped) h else w
        val oh = if (swapped) w else h
        if (ow < COLS || oh < ROWS) return null

        val sum = DoubleArray(COLS * ROWS)
        val count = IntArray(COLS * ROWS)
        for (y in 0 until h) for (x in 0 until w) {
            // координаты пикселя на снимке так, как его показывают (после поворота по EXIF)
            val u = when (orientation) { 2, 3 -> w - 1 - x; 5, 8 -> y; 6, 7 -> h - 1 - y; else -> x }
            val v = when (orientation) { 3, 4 -> h - 1 - y; 5, 6 -> x; 7, 8 -> w - 1 - x; else -> y }
            val rgb = image.getRGB(x, y)
            val cell = (v * ROWS / oh) * COLS + (u * COLS / ow)
            sum[cell] += 0.299 * (rgb shr 16 and 0xFF) + 0.587 * (rgb shr 8 and 0xFF) + 0.114 * (rgb and 0xFF)
            count[cell]++
        }
        val mean = DoubleArray(COLS * ROWS) { sum[it] / count[it] }
        if (mean.max() - mean.min() < MIN_CONTRAST) return null

        var hash = 0L
        for (row in 0 until ROWS) for (col in 0 until COLS - 1) {
            val i = row * COLS + col
            hash = (hash shl 1) or (if (mean[i] > mean[i + 1]) 1L else 0L)
        }
        return hash
    }

    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
}
