package ru.tinsurance.server.files

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Разбор multipart: поля documentType, clientRequestId, replacesFileId и сам файл (file).
 * Файл пишется во временный файл (не в память) и не больше maxBytes: дальше не дочитываем и помечаем как слишком большой.
 * Временный файл удаляет UploadForm.close() — вызывайте через use { }.
 */
suspend fun ApplicationCall.receiveUpload(maxBytes: Long): UploadForm {
    var documentType: String? = null
    var clientRequestId: String? = null
    var replacesFileId: Long? = null
    var originalName: String? = null
    var contentFile: Path? = null
    var size = 0L
    var tooLarge = false

    try {
        receiveMultipart(formFieldLimit = maxBytes + 1).forEachPart { part ->
            try {
                when (part) {
                    is PartData.FormItem -> when (part.name) {
                        "documentType" -> documentType = part.value.take(64)
                        "clientRequestId" -> clientRequestId = part.value.take(64)
                        "replacesFileId" -> replacesFileId = part.value.trim().toLongOrNull()
                            ?: throw ru.tinsurance.server.common.ValidationException("replacesFileId: ожидается id файла")
                    }
                    is PartData.FileItem -> if (part.name == "file" && contentFile == null) {
                        originalName = part.originalFileName
                        val file = withContext(Dispatchers.IO) { Files.createTempFile("tstrah-upload-", ".part") }
                        contentFile = file
                        val written = withContext(Dispatchers.IO) { part.provider().toInputStream().use { it.copyLimited(file, maxBytes) } }
                        if (written == null) tooLarge = true else size = written
                    }
                    else -> Unit
                }
            } finally {
                part.dispose()
            }
        }
    } catch (e: Throwable) {
        contentFile?.let { Files.deleteIfExists(it) }   // запрос оборвался — временный файл не оставляем
        throw e
    }
    return UploadForm(documentType, clientRequestId, replacesFileId, originalName, contentFile, size, tooLarge)
}

/** Копирует поток в файл; null — если данных больше maxBytes (остаток не читается). */
private fun InputStream.copyLimited(target: Path, maxBytes: Long): Long? {
    Files.newOutputStream(target).use { out ->
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = read(buffer)
            if (n < 0) return total
            total += n
            if (total > maxBytes) return null
            out.write(buffer, 0, n)
        }
    }
}

/** Отдача содержимого: тип из БД, без угадывания браузером, без кэширования, имя файла генерирует сервер. */
suspend fun ApplicationCall.respondStoredFile(file: StoredFile) {
    response.header(HttpHeaders.ContentDisposition,
        ContentDisposition.Inline.withParameter(ContentDisposition.Parameters.FileName, file.downloadName).toString())
    response.header("X-Content-Type-Options", "nosniff")
    response.header(HttpHeaders.CacheControl, "private, no-store")
    respondOutputStream(ContentType.parse(file.mimeType)) {
        withContext(Dispatchers.IO) { file.content.use { it.copyTo(this@respondOutputStream) } }
    }
}
