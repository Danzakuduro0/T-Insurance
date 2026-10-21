package ru.tinsurance.server.claims

import ru.tinsurance.api.ClaimFileDto
import ru.tinsurance.api.InfoRequestDto
import ru.tinsurance.api.InfoRequestItemDto
import ru.tinsurance.api.ParticipantDto
import ru.tinsurance.server.db.ClaimParticipants
import ru.tinsurance.server.common.iso
import ru.tinsurance.server.db.ClaimFiles
import ru.tinsurance.server.files.Rejection
import ru.tinsurance.server.db.DocumentTypes
import ru.tinsurance.server.db.InfoRequestItems
import ru.tinsurance.server.db.InfoRequests
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/** Запросы, общие для мобильного и операторского API. Вызываются внутри транзакции. */
object ClaimQueries {

    fun participants(claimId: Long): List<ParticipantDto> =
        ClaimParticipants.selectAll()
            .where { ClaimParticipants.claimId eq claimId }
            .orderBy(ClaimParticipants.id)
            .map {
                ParticipantDto(
                    id = it[ClaimParticipants.id],
                    role = it[ClaimParticipants.role],
                    fullName = it[ClaimParticipants.fullName],
                    phone = it[ClaimParticipants.phone],
                    vehicle = it[ClaimParticipants.vehicle] as? kotlinx.serialization.json.JsonObject,
                    insurer = it[ClaimParticipants.insurer],
                    note = it[ClaimParticipants.note],
                )
            }

    fun files(claimId: Long): List<ClaimFileDto> =
        ClaimFiles.join(DocumentTypes, JoinType.INNER, ClaimFiles.documentType, DocumentTypes.code)
            .selectAll()
            .where { ClaimFiles.claimId eq claimId }
            .orderBy(ClaimFiles.id)
            .map {
                ClaimFileDto(
                    id = it[ClaimFiles.id],
                    documentType = it[ClaimFiles.documentType],
                    documentTypeName = it[DocumentTypes.name],
                    originalName = it[ClaimFiles.originalName],
                    mimeType = it[ClaimFiles.mimeType],
                    sizeBytes = it[ClaimFiles.sizeBytes],
                    status = it[ClaimFiles.status],
                    rejectionReason = it[ClaimFiles.rejectionReason],
                    uploadedAt = it[ClaimFiles.uploadedAt].iso(),
                    hint = it[ClaimFiles.rejectionReason]?.let { r -> Rejection.entries.firstOrNull { e -> e.name == r }?.hint },
                    duplicateOfFileId = if (it[ClaimFiles.status] == "DUPLICATE") it[ClaimFiles.duplicateOfFileId] else null,
                )
            }

    fun infoRequests(claimId: Long, onlyOpen: Boolean): List<InfoRequestDto> {
        val requests = InfoRequests.selectAll()
            .where {
                val byClaim = InfoRequests.claimId eq claimId
                if (onlyOpen) byClaim and (InfoRequests.status eq "OPEN") else byClaim
            }
            .orderBy(InfoRequests.id)
            .toList()
        if (requests.isEmpty()) return emptyList()

        val items = InfoRequestItems
            .join(DocumentTypes, JoinType.LEFT, InfoRequestItems.documentType, DocumentTypes.code)
            .selectAll()
            .where { InfoRequestItems.requestId inList requests.map { it[InfoRequests.id] } }
            .orderBy(InfoRequestItems.id)
            .groupBy({ it[InfoRequestItems.requestId] }) {
                InfoRequestItemDto(
                    documentType = it[InfoRequestItems.documentType],
                    documentTypeName = it.getOrNull(DocumentTypes.name),
                    fieldName = it[InfoRequestItems.fieldName],
                    minCount = it[InfoRequestItems.minCount].toInt(),
                    comment = it[InfoRequestItems.comment],
                    issue = it[InfoRequestItems.issue],
                    hint = it[InfoRequestItems.hint],
                    fileId = it[InfoRequestItems.fileId],
                    isFulfilled = it[InfoRequestItems.isFulfilled],
                )
            }

        return requests.map {
            InfoRequestDto(
                id = it[InfoRequests.id],
                message = it[InfoRequests.message],
                status = it[InfoRequests.status],
                dueAt = it[InfoRequests.dueAt]?.iso(),
                createdAt = it[InfoRequests.createdAt].iso(),
                items = items[it[InfoRequests.id]].orEmpty(),
            )
        }
    }
}
