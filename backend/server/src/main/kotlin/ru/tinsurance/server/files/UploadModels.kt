package ru.tinsurance.server.files

import io.ktor.http.*
import ru.tinsurance.api.ClaimFileDto
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/** Поля multipart-запроса; сам файл — во временном файле на диске. close() удаляет его. */
class UploadForm(
    val documentType: String?,
    val clientRequestId: String?,
    val replacesFileId: Long?,
    val originalName: String?,
    val contentFile: Path?,
    val size: Long,
    /** Файл больше допустимого — дочитывать не стали. */
    val tooLarge: Boolean,
) : AutoCloseable {
    override fun close() {
        contentFile?.let { Files.deleteIfExists(it) }
    }
}

enum class UploadOutcome(val status: HttpStatusCode) {
    ACCEPTED(HttpStatusCode.Created),
    /** Такой же файл уже есть в обращении. */
    DUPLICATE(HttpStatusCode.OK),
    /** Повтор запроса с тем же clientRequestId. */
    ALREADY_UPLOADED(HttpStatusCode.OK),
    REJECTED(HttpStatusCode.UnprocessableEntity),
}

data class UploadResult(val outcome: UploadOutcome, val file: ClaimFileDto)

class StoredFile(val mimeType: String, val downloadName: String, val content: InputStream)
