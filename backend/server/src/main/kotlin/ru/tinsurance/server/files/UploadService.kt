package ru.tinsurance.server.files

import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import ru.tinsurance.server.common.ApiException
import ru.tinsurance.server.common.ConflictException
import ru.tinsurance.server.common.ValidationException
import ru.tinsurance.server.common.parseRequestId
import ru.tinsurance.server.common.tx
import ru.tinsurance.server.db.ClaimFiles
import java.nio.file.Files
import java.nio.file.Path
import kotlin.uuid.Uuid

/**
 * Загрузка материалов клиентом. Формат и целостность проверяются сразу; непригодный файл отклоняется
 * с причиной, остальные материалы не затрагиваются.
 */
class UploadService(
    private val db: Database,
    private val storage: FileStorage,
    private val config: FilesConfig,
    private val inspector: FileInspector = FileInspector(config.maxImagePixels),
) {
    private val recorder = UploadRecorder(DuplicateFinder(config.phashMaxDistance))
    /** Каждый файл до 20 МБ проверяется в памяти — одновременно не больше maxConcurrentProcessing. */
    private val processing = Semaphore(config.maxConcurrentProcessing)

    suspend fun maxFileBytes(): Long = db.tx { FileRepository.maxFileBytes() }

    /** До чтения тела: своё ли обращение и можно ли сейчас добавлять материалы. */
    suspend fun checkCanUpload(clientId: Long, claimId: Long) {
        db.tx { UploadRules.uploadableClaim(clientId, claimId, forUpdate = false) }
    }

    suspend fun upload(clientId: Long, claimId: Long, form: UploadForm): UploadResult {
        val documentTypeCode = form.documentType?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ValidationException("Укажите тип документа (поле documentType)")
        val requestId = parseRequestId(form.clientRequestId)

        val documentType = when (val pre = precheck(clientId, claimId, documentTypeCode, requestId, form.replacesFileId)) {
            is Precheck.AlreadyUploaded -> return UploadResult(UploadOutcome.ALREADY_UPLOADED, pre.file)
            is Precheck.Ready -> pre.documentType
        }
        val contentFile = checkContent(form, documentType)
        val target = UploadTarget(clientId, claimId, documentTypeCode, UploadRules.safeName(form.originalName), requestId)

        return processing.withPermit {
            val content = withContext(Dispatchers.IO) { Files.readAllBytes(contentFile) }
            val inspected = try {
                withContext(Dispatchers.Default) { inspector.inspect(content, documentType.allowedMimeTypes) }
            } catch (e: RejectedFileException) {
                return@withPermit db.tx { recorder.rejected(target, content, e.reason) }
            }
            store(target, documentType, inspected, form.replacesFileId)
        }
    }

    private sealed interface Precheck {
        class AlreadyUploaded(val file: ru.tinsurance.api.ClaimFileDto) : Precheck
        class Ready(val documentType: DocumentType) : Precheck
    }

    /** Права, тип документа, повтор запроса и правила замены — до тяжёлой проверки содержимого. */
    private suspend fun precheck(clientId: Long, claimId: Long, code: String, requestId: Uuid?, replacesFileId: Long?): Precheck = db.tx {
        UploadRules.uploadableClaim(clientId, claimId, forUpdate = false)
        requestId?.let(FileRepository::findByRequestId)?.let { existing ->
            if (existing[ClaimFiles.claimId] != claimId) throw ConflictException("clientRequestId уже использован")
            return@tx Precheck.AlreadyUploaded(FileRepository.dto(existing[ClaimFiles.id]))
        }
        val type = FileRepository.documentType(code) ?: throw ValidationException("Неизвестный тип документа: $code")
        replacesFileId?.let { UploadRules.checkReplaceable(claimId, it, code) }
        Precheck.Ready(type)
    }

    private fun checkContent(form: UploadForm, type: DocumentType): Path {
        val contentFile = form.contentFile ?: throw ValidationException("Нет файла (поле file)")
        if (form.tooLarge || form.size > type.maxSizeBytes) {
            throw ApiException(HttpStatusCode.PayloadTooLarge, "FILE_TOO_LARGE", "Файл больше ${type.maxSizeBytes / MB} МБ")
        }
        if (form.size == 0L) throw ValidationException("Файл пустой")
        return contentFile
    }

    /** Под блокировкой обращения: лимиты и дубли считаются без гонок. */
    private suspend fun store(target: UploadTarget, type: DocumentType, file: InspectedFile, replacesFileId: Long?): UploadResult = db.tx {
        UploadRules.uploadableClaim(target.clientId, target.claimId, forUpdate = true)
        val accepted = FileRepository.accepted(target.claimId)
        accepted.firstOrNull { it.sha256 == file.sha256 }?.let { same -> return@tx recorder.duplicate(target, file, same.id) }
        checkLimits(accepted, file)

        val key = "${FileRepository.STORED_PREFIX}${target.claimId}/${Uuid.random()}.${file.extension}"
        storage.put(key, file.content)
        try {
            recorder.accepted(target, file, type.isImage, key, replacesFileId)
        } catch (e: Throwable) {
            storage.delete(key)
            throw e
        }
    }

    private fun checkLimits(accepted: List<AcceptedFile>, file: InspectedFile) {
        if (accepted.size >= config.maxFilesPerClaim) {
            throw ApiException(HttpStatusCode.Conflict, "FILES_LIMIT", "В обращении не больше ${config.maxFilesPerClaim} файлов")
        }
        if (accepted.sumOf { it.sizeBytes } + file.content.size > config.maxTotalBytesPerClaim) {
            throw ApiException(
                HttpStatusCode.Conflict, "FILES_LIMIT",
                "Общий объём файлов обращения — не больше ${config.maxTotalBytesPerClaim / MB} МБ",
            )
        }
    }

    private companion object {
        const val MB = 1024 * 1024
    }
}
