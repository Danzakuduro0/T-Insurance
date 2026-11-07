package ru.tinsurance.server.files

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.tinsurance.api.AnalysisStatus
import ru.tinsurance.api.FileStatus
import ru.tinsurance.api.RejectionReason
import ru.tinsurance.server.claims.Actor
import ru.tinsurance.server.claims.AuditEntity
import ru.tinsurance.server.claims.AuditWriter
import ru.tinsurance.server.claims.ClaimRepository
import ru.tinsurance.server.jobs.JobQueue
import ru.tinsurance.server.jobs.JobType
import java.time.OffsetDateTime
import kotlin.uuid.Uuid

/** Что загружает клиент и куда. */
data class UploadTarget(val clientId: Long, val claimId: Long, val documentType: String, val originalName: String, val requestId: Uuid?)

/** Запись результата загрузки в БД и журнал. Вызывается внутри транзакции. */
class UploadRecorder(private val duplicates: DuplicateFinder) {

    /** Содержимое отклонённого файла не храним. */
    fun rejected(target: UploadTarget, content: ByteArray, reason: RejectionReason): UploadResult {
        val id = FileRepository.insert(
            target.newFile(
                storageKey = "rejected/${Uuid.random()}",
                mimeType = FileInspector.detectMime(content) ?: "application/octet-stream",
                sizeBytes = content.size.toLong(),
                sha256 = FileInspector.sha256(content),
                status = FileStatus.REJECTED,
            ).copy(rejectionReason = reason),
            OffsetDateTime.now(),
        )
        audit(target, id, "FILE_REJECTED", buildJsonObject { put("documentType", target.documentType); put("reason", reason.name) })
        return UploadResult(UploadOutcome.REJECTED, FileRepository.dto(id))
    }

    /** Второй экземпляр того же файла в обращении не храним. */
    fun duplicate(target: UploadTarget, file: InspectedFile, originalId: Long): UploadResult {
        val id = FileRepository.insert(
            target.newFile("duplicate/${Uuid.random()}", file.mimeType, file.content.size.toLong(), file.sha256, FileStatus.DUPLICATE)
                .copy(duplicateOfFileId = originalId),
            OffsetDateTime.now(),
        )
        audit(target, id, "FILE_DUPLICATE", buildJsonObject { put("duplicateOf", originalId) })
        return UploadResult(UploadOutcome.DUPLICATE, FileRepository.dto(id))
    }

    /** Принятое фото сразу ставится в очередь анализа — в той же транзакции, поэтому задача не потеряется. */
    fun accepted(target: UploadTarget, file: InspectedFile, isImage: Boolean, storageKey: String, replacesFileId: Long?): UploadResult {
        val now = OffsetDateTime.now()
        val elsewhere = duplicates.findElsewhere(target.claimId, file.sha256, file.phash)
        val id = FileRepository.insert(
            target.newFile(storageKey, file.mimeType, file.content.size.toLong(), file.sha256, FileStatus.ACCEPTED).copy(
                width = file.width,
                height = file.height,
                phash = file.phash,
                analysisStatus = if (isImage) AnalysisStatus.PENDING else AnalysisStatus.NOT_REQUIRED,
                duplicateOfFileId = elsewhere?.fileId,
            ),
            now,
        )
        elsewhere?.let { ClaimRepository.markDuplicateOf(target.claimId, it.claimId) }

        if (replacesFileId != null) {
            FileRepository.markReplaced(replacesFileId, id)
            audit(target, id, "FILE_REPLACE", buildJsonObject { put("fileId", id) }, buildJsonObject { put("fileId", replacesFileId) })
        } else {
            audit(target, id, "FILE_ADD", buildJsonObject { put("documentType", target.documentType); put("fileId", id) })
        }
        if (isImage) {
            JobQueue.enqueue(JobType.ANALYZE_IMAGE, buildJsonObject { put("fileId", id) }, dedupKey = "ANALYZE_IMAGE:file:$id", runAfter = now)
        }
        return UploadResult(UploadOutcome.ACCEPTED, FileRepository.dto(id))
    }

    private fun UploadTarget.newFile(storageKey: String, mimeType: String, sizeBytes: Long, sha256: String, status: FileStatus) =
        NewFile(claimId, documentType, storageKey, originalName, mimeType, sizeBytes, sha256, status, clientId, requestId)

    private fun audit(
        target: UploadTarget, fileId: Long, action: String,
        newValue: kotlinx.serialization.json.JsonObject, oldValue: kotlinx.serialization.json.JsonObject? = null,
    ) = AuditWriter.record(target.claimId, AuditEntity.FILE, fileId, action, Actor.client(target.clientId), newValue, oldValue)
}
