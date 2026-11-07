package ru.tinsurance.server.files

import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import ru.tinsurance.api.AnalysisStatus
import ru.tinsurance.api.ClaimFileDto
import ru.tinsurance.api.FileStatus
import ru.tinsurance.api.RejectionReason
import ru.tinsurance.server.analysis.QualityHints
import ru.tinsurance.server.common.iso
import ru.tinsurance.server.db.ClaimFiles
import ru.tinsurance.server.db.DocumentTypes
import java.time.OffsetDateTime
import kotlin.uuid.Uuid

data class DocumentType(val code: String, val isImage: Boolean, val allowedMimeTypes: List<String>, val maxSizeBytes: Long)

data class RejectedFile(val id: Long, val documentType: String, val reason: RejectionReason?)

data class AcceptedFile(val id: Long, val sha256: String, val sizeBytes: Long)

/** Новая запись claim_files: принятый файл, отклонённый или дубль. */
data class NewFile(
    val claimId: Long,
    val documentType: String,
    val storageKey: String,
    val originalName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val status: FileStatus,
    val uploadedBy: Long,
    val requestId: Uuid?,
    val rejectionReason: RejectionReason? = null,
    val duplicateOfFileId: Long? = null,
    val analysisStatus: AnalysisStatus = AnalysisStatus.NOT_REQUIRED,
    val width: Int? = null,
    val height: Int? = null,
    val phash: Long? = null,
)

/** Вызывается внутри транзакции. */
object FileRepository {
    /** Ключи сохранённых файлов; у отклонённых при загрузке — rejected/…, у дублей — duplicate/… */
    const val STORED_PREFIX = "claims/"

    fun documentType(code: String): DocumentType? =
        DocumentTypes.selectAll().where { DocumentTypes.code eq code }.singleOrNull()?.let {
            DocumentType(it[DocumentTypes.code], it[DocumentTypes.isImage], it[DocumentTypes.allowedMimeTypes], it[DocumentTypes.maxSizeBytes])
        }

    fun maxFileBytes(): Long = DocumentTypes.select(DocumentTypes.maxSizeBytes).maxOf { it[DocumentTypes.maxSizeBytes] }

    fun find(claimId: Long, fileId: Long): ResultRow? =
        ClaimFiles.selectAll().where { (ClaimFiles.id eq fileId) and (ClaimFiles.claimId eq claimId) }.singleOrNull()

    fun findByRequestId(requestId: Uuid): ResultRow? =
        ClaimFiles.selectAll().where { ClaimFiles.clientRequestId eq requestId }.singleOrNull()

    fun rows(claimId: Long): Map<Long, ResultRow> =
        ClaimFiles.selectAll().where { ClaimFiles.claimId eq claimId }.associateBy { it[ClaimFiles.id] }

    fun accepted(claimId: Long): List<AcceptedFile> =
        ClaimFiles.select(ClaimFiles.id, ClaimFiles.sha256, ClaimFiles.sizeBytes)
            .where { (ClaimFiles.claimId eq claimId) and (ClaimFiles.status eq FileStatus.ACCEPTED) }
            .map { AcceptedFile(it[ClaimFiles.id], it[ClaimFiles.sha256], it[ClaimFiles.sizeBytes]) }

    fun acceptedCountByType(claimId: Long): Map<String, Int> =
        ClaimFiles.select(ClaimFiles.documentType, ClaimFiles.id.count())
            .where { (ClaimFiles.claimId eq claimId) and (ClaimFiles.status eq FileStatus.ACCEPTED) }
            .groupBy(ClaimFiles.documentType)
            .associate { it[ClaimFiles.documentType] to it[ClaimFiles.id.count()].toInt() }

    fun rejectedNotReplaced(claimId: Long, documentTypes: List<String>): List<RejectedFile> =
        ClaimFiles.selectAll()
            .where {
                (ClaimFiles.claimId eq claimId) and (ClaimFiles.status eq FileStatus.REJECTED) and
                    ClaimFiles.replacedByFileId.isNull() and (ClaimFiles.documentType inList documentTypes)
            }
            .orderBy(ClaimFiles.id)
            .map { RejectedFile(it[ClaimFiles.id], it[ClaimFiles.documentType], it[ClaimFiles.rejectionReason]) }

    /** Только сохранённые файлы: у отклонённых при загрузке и дублей содержимого нет. */
    fun findStored(claimId: Long, fileId: Long, statuses: Set<FileStatus>): ResultRow? =
        ClaimFiles.selectAll()
            .where {
                (ClaimFiles.id eq fileId) and (ClaimFiles.claimId eq claimId) and (ClaimFiles.status inList statuses) and
                    (ClaimFiles.storageKey like "$STORED_PREFIX%")
            }
            .singleOrNull()

    fun insert(file: NewFile, at: OffsetDateTime): Long =
        ClaimFiles.insert {
            it[claimId] = file.claimId
            it[documentType] = file.documentType
            it[storageKey] = file.storageKey
            it[originalName] = file.originalName
            it[mimeType] = file.mimeType
            it[sizeBytes] = file.sizeBytes
            it[sha256] = file.sha256
            it[width] = file.width
            it[height] = file.height
            it[phash] = file.phash
            it[status] = file.status
            it[analysisStatus] = file.analysisStatus
            it[rejectionReason] = file.rejectionReason
            it[duplicateOfFileId] = file.duplicateOfFileId
            it[uploadedBy] = file.uploadedBy
            it[clientRequestId] = file.requestId
            it[uploadedAt] = at
        }[ClaimFiles.id]

    fun markDeleted(fileId: Long) {
        ClaimFiles.update({ ClaimFiles.id eq fileId }) { it[status] = FileStatus.DELETED }
    }

    fun markReplaced(oldId: Long, newId: Long) {
        ClaimFiles.update({ ClaimFiles.id eq oldId }) {
            it[status] = FileStatus.REPLACED
            it[replacedByFileId] = newId
        }
    }

    fun dto(fileId: Long): ClaimFileDto {
        val claimId = ClaimFiles.select(ClaimFiles.claimId).where { ClaimFiles.id eq fileId }.single()[ClaimFiles.claimId]
        return listDtos(claimId).single { it.id == fileId }
    }

    /** Для фото, отклонённых анализом, подсказка — по конкретным проблемам (размыто, темно…). */
    fun listDtos(claimId: Long): List<ClaimFileDto> {
        val rows = ClaimFiles.join(DocumentTypes, JoinType.INNER, ClaimFiles.documentType, DocumentTypes.code)
            .selectAll()
            .where { (ClaimFiles.claimId eq claimId) and (ClaimFiles.status neq FileStatus.DELETED) }
            .orderBy(ClaimFiles.id)
            .toList()
        val qualityIssues = QualityHints.issuesByFile(
            rows.filter { it[ClaimFiles.rejectionReason] == RejectionReason.LOW_QUALITY }.map { it[ClaimFiles.id] }
        )
        return rows.map {
            val reason = it[ClaimFiles.rejectionReason]
            ClaimFileDto(
                id = it[ClaimFiles.id],
                documentType = it[ClaimFiles.documentType],
                documentTypeName = it[DocumentTypes.name],
                originalName = it[ClaimFiles.originalName],
                mimeType = it[ClaimFiles.mimeType],
                sizeBytes = it[ClaimFiles.sizeBytes],
                status = it[ClaimFiles.status],
                rejectionReason = reason,
                uploadedAt = it[ClaimFiles.uploadedAt].iso(),
                hint = when (reason) {
                    null -> null
                    RejectionReason.LOW_QUALITY -> QualityHints.forIssues(qualityIssues[it[ClaimFiles.id]].orEmpty())
                    else -> reason.hint()
                },
                duplicateOfFileId = if (it[ClaimFiles.status] == FileStatus.DUPLICATE) it[ClaimFiles.duplicateOfFileId] else null,
            )
        }
    }
}
