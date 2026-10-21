package ru.tinsurance.server.files

import ru.tinsurance.api.ClaimFileDto
import ru.tinsurance.api.ClaimStatus
import ru.tinsurance.server.claims.ActorType
import ru.tinsurance.server.claims.ClaimQueries
import ru.tinsurance.server.common.*
import ru.tinsurance.server.db.*
import ru.tinsurance.server.jobs.JobQueue
import ru.tinsurance.server.jobs.JobType
import io.ktor.http.*
import io.ktor.server.config.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import kotlin.uuid.Uuid

data class FilesConfig(
    val storageDir: Path,
    /** вопрос 14: число и общий объём файлов обращения задаёт команда */
    val maxFilesPerClaim: Int,
    val maxTotalBytesPerClaim: Long,
    /** защита от «бомб»: изображение с огромным числом пикселей при маленьком размере файла */
    val maxImagePixels: Long,
    /** сколько загрузок одновременно проверяются в памяти — остальные ждут (защита от OutOfMemory под нагрузкой) */
    val maxConcurrentProcessing: Int,
    /** до скольких различающихся бит перцептивного хэша фото считаются одним снимком (вопрос 17) */
    val phashMaxDistance: Int,
) {
    companion object {
        fun from(config: ApplicationConfig) = FilesConfig(
            // по умолчанию — в домашней папке: один и тот же каталог при запуске из IntelliJ и через gradle
            storageDir = config.propertyOrNull("storage.localDir")?.getString()?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
                ?: Path.of(System.getProperty("user.home"), ".tstrah", "files"),
            maxFilesPerClaim = config.propertyOrNull("files.maxFilesPerClaim")?.getString()?.toInt() ?: 15,
            maxTotalBytesPerClaim = config.propertyOrNull("files.maxTotalBytesPerClaim")?.getString()?.toLong() ?: (100L * 1024 * 1024),
            maxImagePixels = config.propertyOrNull("files.maxImagePixels")?.getString()?.toLong() ?: 50_000_000L,
            maxConcurrentProcessing = config.propertyOrNull("files.maxConcurrentProcessing")?.getString()?.toInt() ?: 4,
            phashMaxDistance = config.propertyOrNull("files.phashMaxDistance")?.getString()?.toInt() ?: 5,
        )
    }
}

/**
 * Что пришло в multipart-запросе загрузки. Сам файл — во временном файле на диске, а не в памяти:
 * пока запрос медленно загружается по сети, он не занимает память сервера. close() удаляет временный файл.
 */
class UploadForm(
    val documentType: String?,
    val clientRequestId: String?,
    val replacesFileId: Long?,
    val originalName: String?,
    val contentFile: Path?,
    val size: Long,
    /** файл оказался больше допустимого — дочитывать не стали */
    val tooLarge: Boolean,
) : AutoCloseable {
    override fun close() {
        contentFile?.let { Files.deleteIfExists(it) }
    }
}

enum class UploadOutcome(val status: HttpStatusCode) {
    ACCEPTED(HttpStatusCode.Created),
    DUPLICATE(HttpStatusCode.OK),          // такой же файл уже есть в обращении (вопрос 17)
    ALREADY_UPLOADED(HttpStatusCode.OK),   // повтор запроса с тем же clientRequestId
    REJECTED(HttpStatusCode.UnprocessableEntity),
}

data class UploadResult(val outcome: UploadOutcome, val file: ClaimFileDto)

private class Precheck(val existingFileId: Long?, val docType: ResultRow?)

class StoredFile(val mimeType: String, val downloadName: String, val content: InputStream)

/**
 * Загрузка материалов клиентом (шаг 2). Формат и целостность проверяются сразу (вопрос 50),
 * непригодный файл отклоняется с причиной, остальные материалы не затрагиваются (вопрос 16).
 */
class FileService(
    private val db: Database,
    private val storage: FileStorage,
    private val config: FilesConfig,
    private val inspector: FileInspector = FileInspector(config.maxImagePixels),
) {
    /** Одновременно в памяти — не больше maxConcurrentProcessing файлов (каждый до 20 МБ плюс очищенная копия). */
    private val processing = Semaphore(config.maxConcurrentProcessing)

    /** Самый большой допустимый файл — столько максимум читаем из запроса. */
    suspend fun maxFileBytes(): Long = db.tx { DocumentTypes.select(DocumentTypes.maxSizeBytes).maxOf { it[DocumentTypes.maxSizeBytes] } }

    /** Проверка до чтения тела: своё ли обращение и можно ли сейчас добавлять материалы. */
    suspend fun checkCanUpload(clientId: Long, claimId: Long) = db.tx { uploadableClaim(clientId, claimId, forUpdate = false) }

    suspend fun upload(clientId: Long, claimId: Long, form: UploadForm): UploadResult {
        val documentType = form.documentType?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ValidationException("Укажите тип документа (поле documentType)")
        val requestId = form.clientRequestId?.takeIf { it.isNotBlank() }?.let {
            runCatching { Uuid.parse(it) }.getOrElse { throw ValidationException("clientRequestId должен быть UUID") }
        }

        // 1. Права, тип документа, идемпотентность, правила замены — до тяжёлой проверки содержимого
        val pre = db.tx {
            uploadableClaim(clientId, claimId, forUpdate = false)
            val existing = requestId?.let { rid -> ClaimFiles.selectAll().where { ClaimFiles.clientRequestId eq rid }.singleOrNull() }
            if (existing != null) {
                if (existing[ClaimFiles.claimId] != claimId) throw ConflictException("clientRequestId уже использован")
                return@tx Precheck(existingFileId = existing[ClaimFiles.id], docType = null)
            }
            val type = DocumentTypes.selectAll().where { DocumentTypes.code eq documentType }.singleOrNull()
                ?: throw ValidationException("Неизвестный тип документа: $documentType")
            form.replacesFileId?.let { checkReplaceable(claimId, it, documentType) }
            Precheck(existingFileId = null, docType = type)
        }
        // повтор того же запроса (плохая сеть) — возвращаем уже загруженный файл
        pre.existingFileId?.let { id -> return UploadResult(UploadOutcome.ALREADY_UPLOADED, db.tx { fileDto(id) }) }
        val docType = pre.docType!!

        val contentFile = form.contentFile ?: throw ValidationException("Нет файла (поле file)")
        val maxSize = docType[DocumentTypes.maxSizeBytes]
        if (form.tooLarge || form.size > maxSize) {
            throw ApiException(HttpStatusCode.PayloadTooLarge, "FILE_TOO_LARGE", "Файл больше ${maxSize / (1024 * 1024)} МБ")
        }
        if (form.size == 0L) throw ValidationException("Файл пустой")
        val originalName = safeName(form.originalName)

        // 2–3. Проверка и сохранение: файл целиком в памяти только у ограниченного числа загрузок сразу
        return processing.withPermit {
            val content = withContext(Dispatchers.IO) { Files.readAllBytes(contentFile) }
            // проверка содержимого — вне транзакции (это CPU, а не БД)
            val inspected = try {
                withContext(Dispatchers.Default) { inspector.inspect(content, docType[DocumentTypes.allowedMimeTypes]) }
            } catch (e: RejectedFileException) {
                return@withPermit db.tx { recordRejected(clientId, claimId, documentType, originalName, content, requestId, e.rejection) }
            }
            store(clientId, claimId, documentType, docType, originalName, inspected, requestId, form.replacesFileId)
        }
    }

    @Suppress("LongParameterList")
    private suspend fun store(
        clientId: Long, claimId: Long, documentType: String, docType: ResultRow, originalName: String,
        inspected: InspectedFile, requestId: Uuid?, replacesFileId: Long?,
    ): UploadResult {
        return db.tx {
            uploadableClaim(clientId, claimId, forUpdate = true)  // блокировка: лимиты и дубли считаются без гонок

            val accepted = ClaimFiles.selectAll()
                .where { (ClaimFiles.claimId eq claimId) and (ClaimFiles.status eq "ACCEPTED") }
                .toList()
            accepted.firstOrNull { it[ClaimFiles.sha256] == inspected.sha256 }?.let { same ->
                return@tx recordDuplicate(clientId, claimId, documentType, originalName, inspected, requestId, same[ClaimFiles.id])
            }
            if (accepted.size >= config.maxFilesPerClaim) {
                throw ApiException(HttpStatusCode.Conflict, "FILES_LIMIT", "В обращении не больше ${config.maxFilesPerClaim} файлов")
            }
            if (accepted.sumOf { it[ClaimFiles.sizeBytes] } + inspected.content.size > config.maxTotalBytesPerClaim) {
                throw ApiException(HttpStatusCode.Conflict, "FILES_LIMIT", "Общий объём файлов обращения — не больше ${config.maxTotalBytesPerClaim / (1024 * 1024)} МБ")
            }

            val key = "$STORED_PREFIX$claimId/${Uuid.random()}.${inspected.extension}"
            storage.put(key, inspected.content)
            try {
                recordAccepted(clientId, claimId, documentType, docType[DocumentTypes.isImage], originalName, inspected, requestId, key, replacesFileId)
            } catch (e: Throwable) {
                storage.delete(key)   // запись в БД не удалась — не оставляем «осиротевший» файл
                throw e
            }
        }
    }

    /**
     * Содержимое файла для клиента — только своего обращения: принятый файл, а также фото, которое
     * анализ отклонил по качеству (клиент видит, какой снимок переснять).
     */
    suspend fun openForClient(clientId: Long, claimId: Long, fileId: Long): StoredFile = db.tx {
        val owned = Claims.selectAll().where { (Claims.id eq claimId) and (Claims.clientId eq clientId) }.any()
        if (!owned) throw NotFoundException("Файл не найден")
        open(claimId, fileId, setOf("ACCEPTED", "REJECTED"))
    }

    /**
     * Содержимое файла для оператора — кроме черновиков. Доступны заменённые версии (история) и фото,
     * отклонённые анализом: оператор подтверждает или переопределяет вердикт модели, глядя на сам снимок.
     */
    suspend fun openForOperator(claimId: Long, fileId: Long): StoredFile = db.tx {
        val visible = Claims.selectAll().where { (Claims.id eq claimId) and (Claims.status neq ClaimStatus.DRAFT.name) }.any()
        if (!visible) throw NotFoundException("Файл не найден")
        open(claimId, fileId, setOf("ACCEPTED", "REPLACED", "REJECTED"))
    }

    // ------------------------------------------------------------------ внутреннее (внутри транзакции)

    private fun uploadableClaim(clientId: Long, claimId: Long, forUpdate: Boolean): ResultRow {
        val query = Claims.selectAll().where { (Claims.id eq claimId) and (Claims.clientId eq clientId) }
        val claim = (if (forUpdate) query.forUpdate() else query).singleOrNull()
            ?: throw NotFoundException("Обращение не найдено")
        val status = ClaimStatus.valueOf(claim[Claims.status])
        // вопрос 10: после отправки клиент только добавляет материалы по запросу
        if (status != ClaimStatus.DRAFT && status != ClaimStatus.NEEDS_INFO) {
            throw ConflictException("Материалы можно добавлять в черновик или по запросу дополнительных материалов")
        }
        return claim
    }

    /** Заменить можно отклонённый файл; принятый — только в черновике или если его замену запросили. */
    private fun checkReplaceable(claimId: Long, fileId: Long, documentType: String) {
        val file = ClaimFiles.selectAll().where { (ClaimFiles.id eq fileId) and (ClaimFiles.claimId eq claimId) }.singleOrNull()
            ?: throw ValidationException("Заменяемый файл не найден")
        if (file[ClaimFiles.documentType] != documentType) throw ValidationException("Замена должна быть того же типа документа")
        when (file[ClaimFiles.status]) {
            "REJECTED" -> Unit
            "ACCEPTED" -> {
                val draft = Claims.select(Claims.status).where { Claims.id eq claimId }.single()[Claims.status] == ClaimStatus.DRAFT.name
                val requested = InfoRequestItems.join(InfoRequests, JoinType.INNER, InfoRequestItems.requestId, InfoRequests.id)
                    .selectAll()
                    .where { (InfoRequests.claimId eq claimId) and (InfoRequests.status eq "OPEN") and (InfoRequestItems.fileId eq fileId) }
                    .any()
                if (!draft && !requested) throw ConflictException("Принятый файл после отправки можно заменить только по запросу")
            }
            else -> throw ConflictException("Этот файл уже заменён")
        }
    }

    private fun recordRejected(
        clientId: Long, claimId: Long, documentType: String, originalName: String,
        content: ByteArray, requestId: Uuid?, rejection: Rejection,
    ): UploadResult {
        val id = ClaimFiles.insert {
            it[ClaimFiles.claimId] = claimId
            it[ClaimFiles.documentType] = documentType
            it[storageKey] = "rejected/${Uuid.random()}"   // содержимое отклонённого файла не храним
            it[ClaimFiles.originalName] = originalName
            it[mimeType] = FileInspector.detectMime(content) ?: "application/octet-stream"
            it[sizeBytes] = content.size.toLong()
            it[sha256] = FileInspector.sha256(content)
            it[status] = "REJECTED"
            it[rejectionReason] = rejection.name
            it[uploadedBy] = clientId
            it[clientRequestId] = requestId
            it[uploadedAt] = OffsetDateTime.now()
        }[ClaimFiles.id]
        audit(claimId, id, "FILE_REJECTED", clientId, buildJsonObject {
            put("documentType", documentType); put("reason", rejection.name)
        })
        return UploadResult(UploadOutcome.REJECTED, fileDto(id))
    }

    private fun recordDuplicate(
        clientId: Long, claimId: Long, documentType: String, originalName: String,
        inspected: InspectedFile, requestId: Uuid?, originalId: Long,
    ): UploadResult {
        val id = ClaimFiles.insert {
            it[ClaimFiles.claimId] = claimId
            it[ClaimFiles.documentType] = documentType
            it[storageKey] = "duplicate/${Uuid.random()}"  // второй экземпляр не храним
            it[ClaimFiles.originalName] = originalName
            it[mimeType] = inspected.mimeType
            it[sizeBytes] = inspected.content.size.toLong()
            it[sha256] = inspected.sha256
            it[status] = "DUPLICATE"
            it[duplicateOfFileId] = originalId
            it[uploadedBy] = clientId
            it[clientRequestId] = requestId
            it[uploadedAt] = OffsetDateTime.now()
        }[ClaimFiles.id]
        audit(claimId, id, "FILE_DUPLICATE", clientId, buildJsonObject { put("duplicateOf", originalId) })
        return UploadResult(UploadOutcome.DUPLICATE, fileDto(id))
    }

    @Suppress("LongParameterList")
    private fun recordAccepted(
        clientId: Long, claimId: Long, documentType: String, isImage: Boolean, originalName: String,
        inspected: InspectedFile, requestId: Uuid?, key: String, replacesFileId: Long?,
    ): UploadResult {
        val now = OffsetDateTime.now()
        // Тот же снимок в другом обращении — признак для ручной проверки (маршрутизация, шаг 4).
        // Сначала точная копия (SHA-256), иначе — похожая по перцептивному хэшу (пересжатая, уменьшенная).
        val otherClaimFiles = (ClaimFiles.claimId neq claimId) and (ClaimFiles.status eq "ACCEPTED")
        val elsewhere = ClaimFiles.selectAll()
            .where { (ClaimFiles.sha256 eq inspected.sha256) and otherClaimFiles }
            .orderBy(ClaimFiles.id)
            .firstOrNull()
            ?: inspected.phash?.let { phash ->
                ClaimFiles.selectAll()
                    .where { HammingDistanceAtMost(ClaimFiles.phash, phash, config.phashMaxDistance) and otherClaimFiles }
                    .orderBy(ClaimFiles.id)
                    .firstOrNull()
            }

        val id = ClaimFiles.insert {
            it[ClaimFiles.claimId] = claimId
            it[ClaimFiles.documentType] = documentType
            it[storageKey] = key
            it[ClaimFiles.originalName] = originalName
            it[mimeType] = inspected.mimeType
            it[sizeBytes] = inspected.content.size.toLong()
            it[sha256] = inspected.sha256
            it[width] = inspected.width
            it[height] = inspected.height
            it[phash] = inspected.phash
            it[status] = "ACCEPTED"
            it[analysisStatus] = if (isImage) "PENDING" else "NOT_REQUIRED"   // вопрос 51: анализ в фоне
            it[duplicateOfFileId] = elsewhere?.get(ClaimFiles.id)
            it[uploadedBy] = clientId
            it[clientRequestId] = requestId
            it[uploadedAt] = now
        }[ClaimFiles.id]

        elsewhere?.let { other ->
            Claims.update({ (Claims.id eq claimId) and Claims.duplicateOfClaimId.isNull() }) {
                it[duplicateOfClaimId] = other[ClaimFiles.claimId]
            }
        }
        replacesFileId?.let { oldId ->
            ClaimFiles.update({ ClaimFiles.id eq oldId }) {
                it[status] = "REPLACED"
                it[replacedByFileId] = id
            }
            audit(claimId, id, "FILE_REPLACE", clientId, buildJsonObject { put("fileId", id) }, buildJsonObject { put("fileId", oldId) })
        } ?: audit(claimId, id, "FILE_ADD", clientId, buildJsonObject { put("documentType", documentType); put("fileId", id) })

        if (isImage) {
            // анализ — в фоне, в той же транзакции: задача не потеряется (вопрос 51, AC-05)
            JobQueue.enqueue(JobType.ANALYZE_IMAGE, buildJsonObject { put("fileId", id) }, dedupKey = "ANALYZE_IMAGE:file:$id", runAfter = now)
        }
        return UploadResult(UploadOutcome.ACCEPTED, fileDto(id))
    }

    private fun audit(
        claimId: Long, fileId: Long, action: String, clientId: Long,
        newValue: kotlinx.serialization.json.JsonObject, oldValue: kotlinx.serialization.json.JsonObject? = null,
    ) {
        AuditLog.insert {
            it[AuditLog.claimId] = claimId
            it[entityType] = "FILE"
            it[entityId] = fileId
            it[AuditLog.action] = action
            it[AuditLog.oldValue] = oldValue
            it[AuditLog.newValue] = newValue
            it[actorType] = ActorType.CLIENT.name
            it[actorId] = clientId
            it[createdAt] = OffsetDateTime.now()
        }
    }

    private fun fileDto(fileId: Long): ClaimFileDto {
        val claimId = ClaimFiles.select(ClaimFiles.claimId).where { ClaimFiles.id eq fileId }.single()[ClaimFiles.claimId]
        return ClaimQueries.files(claimId).single { it.id == fileId }
    }

    private fun open(claimId: Long, fileId: Long, statuses: Set<String>): StoredFile {
        val file = ClaimFiles.selectAll()
            .where {
                (ClaimFiles.id eq fileId) and (ClaimFiles.claimId eq claimId) and (ClaimFiles.status inList statuses) and
                    // содержимое есть только у сохранённых файлов; отклонённые при загрузке и дубли не хранятся
                    (ClaimFiles.storageKey like "$STORED_PREFIX%")
            }
            .singleOrNull() ?: throw NotFoundException("Файл не найден")
        val ext = file[ClaimFiles.storageKey].substringAfterLast('.', "bin")
        return StoredFile(
            mimeType = file[ClaimFiles.mimeType],
            downloadName = "claim-$claimId-file-$fileId.$ext",   // имя от клиента в заголовок не попадает
            content = storage.open(file[ClaimFiles.storageKey]),
        )
    }

    /** Имя файла от клиента храним только для показа: без пути, управляющих символов и длиной до 200. */
    private fun safeName(name: String?): String =
        name?.substringAfterLast('/')?.substringAfterLast('\\')
            ?.filter { !it.isISOControl() }?.trim()?.take(200)?.takeIf { it.isNotEmpty() } ?: "file"

    private companion object {
        /** Ключи сохранённых файлов; у отклонённых при загрузке — rejected/…, у дублей — duplicate/… */
        const val STORED_PREFIX = "claims/"
    }
}

/** bit_count(a xor b) <= maxBits: перцептивные хэши отличаются не больше чем на maxBits бит. */
private class HammingDistanceAtMost(val column: Column<Long?>, val value: Long, val maxBits: Int) : Op<Boolean>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder {
        append("bit_count((", column, " # (", value.toString(), ")::bigint)::bit(64)) <= ", maxBits.toString())
    }
}
