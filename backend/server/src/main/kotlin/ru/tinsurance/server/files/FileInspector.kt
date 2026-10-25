package ru.tinsurance.server.files

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import ru.tinsurance.api.RejectionReason

data class InspectedFile(
    val mimeType: String,
    val extension: String,
    /** содержимое для хранения: у изображений без метаданных (EXIF с геолокацией и т.п.) */
    val content: ByteArray,
    val sha256: String,
    val width: Int?,
    val height: Int?,
    /** перцептивный хэш изображения (см. [PerceptualHash]); null — у PDF и у однотонных картинок */
    val phash: Long? = null,
)

/**
 * Проверка файла по содержимому, а не по имени или Content-Type: сигнатура → разрешён ли формат →
 * цел ли файл → размер в пикселях. Изображения очищаются от метаданных, ориентация сохраняется.
 */
class FileInspector(private val maxImagePixels: Long) {

    fun inspect(bytes: ByteArray, allowedMimeTypes: List<String>): InspectedFile {
        val mime = detectMime(bytes) ?: throw RejectedFileException(RejectionReason.UNSUPPORTED_FORMAT)
        if (mime !in allowedMimeTypes) throw RejectedFileException(RejectionReason.UNSUPPORTED_FORMAT)

        return when (mime) {
            JPEG -> {
                val image = checkImage(bytes)
                if (!hasJpegEnd(bytes)) throw RejectedFileException(RejectionReason.CORRUPTED)  // обрезанный файл
                val stripped = JpegMetadata.strip(bytes)
                inspected(mime, "jpg", stripped, image, PerceptualHash.dHash(image.preview, JpegMetadata.orientation(stripped)))
            }
            PNG -> {
                val image = checkImage(bytes)
                inspected(mime, "png", PngMetadata.strip(bytes), image, PerceptualHash.dHash(image.preview))
            }
            PDF -> {
                checkPdf(bytes)
                inspected(mime, "pdf", bytes, null, null)
            }
            else -> throw RejectedFileException(RejectionReason.UNSUPPORTED_FORMAT)
        }
    }

    private fun inspected(mime: String, ext: String, content: ByteArray, image: DecodedImage?, phash: Long?) =
        InspectedFile(mime, ext, content, sha256(content), image?.width, image?.height, phash)

    /** Размеры исходной картинки и её уменьшенная в 8 раз копия (из неё считается перцептивный хэш). */
    private class DecodedImage(val width: Int, val height: Int, val preview: BufferedImage)

    /** Размер читается из заголовка до декодирования — «бомба» в 100 000 × 100 000 пикселей не попадёт в память. */
    private fun checkImage(bytes: ByteArray): DecodedImage {
        val stream = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
            ?: throw RejectedFileException(RejectionReason.CORRUPTED)
        stream.use { input ->
            val reader: ImageReader = ImageIO.getImageReaders(input).asSequence().firstOrNull()
                ?: throw RejectedFileException(RejectionReason.CORRUPTED)
            try {
                reader.input = input
                val (w, h) = try {
                    reader.getWidth(0) to reader.getHeight(0)
                } catch (e: Exception) {
                    throw RejectedFileException(RejectionReason.CORRUPTED)
                }
                if (w <= 0 || h <= 0) throw RejectedFileException(RejectionReason.CORRUPTED)
                if (w.toLong() * h > maxImagePixels) throw RejectedFileException(RejectionReason.TOO_MANY_PIXELS)
                // Полное декодирование с прореживанием 8×8: проверяет все данные, но занимает в памяти 1/64 картинки
                val param = reader.defaultReadParam.apply { setSourceSubsampling(8, 8, 0, 0) }
                val preview = try {
                    reader.read(0, param)
                } catch (e: Exception) {
                    throw RejectedFileException(RejectionReason.CORRUPTED)
                }
                return DecodedImage(w, h, preview)
            } finally {
                reader.dispose()
            }
        }
    }

    private fun checkPdf(bytes: ByteArray) {
        val tail = String(bytes, maxOf(0, bytes.size - 1024), minOf(1024, bytes.size), Charsets.ISO_8859_1)
        if (!tail.contains("%%EOF")) throw RejectedFileException(RejectionReason.CORRUPTED)
        // PDF откроет оператор в браузере — не принимаем документы со скриптами, запуском программ и вложениями
        val text = String(bytes, Charsets.ISO_8859_1)
        if (PDF_ACTIVE.any { text.contains(it) }) throw RejectedFileException(RejectionReason.ACTIVE_CONTENT)
    }

    private fun hasJpegEnd(bytes: ByteArray): Boolean {
        val from = maxOf(2, bytes.size - 1024)
        for (i in bytes.size - 2 downTo from) {
            if (bytes[i] == 0xFF.toByte() && bytes[i + 1] == 0xD9.toByte()) return true
        }
        return false
    }

    companion object {
        const val JPEG = "image/jpeg"
        const val PNG = "image/png"
        const val PDF = "application/pdf"
        private val PDF_ACTIVE = listOf("/JavaScript", "/JS ", "/JS(", "/JS<", "/Launch", "/EmbeddedFile", "/RichMedia")

        fun detectMime(b: ByteArray): String? = when {
            b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> JPEG
            b.size >= 8 && b.copyOfRange(0, 8).contentEquals(PngMetadata.SIGNATURE) -> PNG
            b.size >= 5 && String(b, 0, 5, Charsets.US_ASCII) == "%PDF-" -> PDF
            else -> null
        }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
