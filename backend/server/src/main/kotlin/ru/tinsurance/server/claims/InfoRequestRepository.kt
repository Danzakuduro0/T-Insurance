package ru.tinsurance.server.claims

import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import ru.tinsurance.api.ActorType
import ru.tinsurance.api.InfoRequestDto
import ru.tinsurance.api.InfoRequestItemDto
import ru.tinsurance.api.InfoRequestStatus
import ru.tinsurance.api.MaterialIssue
import ru.tinsurance.server.common.iso
import ru.tinsurance.server.db.DocumentTypes
import ru.tinsurance.server.db.InfoRequestItems
import ru.tinsurance.server.db.InfoRequests
import java.time.OffsetDateTime

/** Запросы дополнительных материалов. Вызывается внутри транзакции. */
object InfoRequestRepository {

    fun list(claimId: Long, onlyOpen: Boolean): List<InfoRequestDto> {
        val requests = InfoRequests.selectAll()
            .where {
                val byClaim = InfoRequests.claimId eq claimId
                if (onlyOpen) byClaim and (InfoRequests.status eq InfoRequestStatus.OPEN) else byClaim
            }
            .orderBy(InfoRequests.id)
            .toList()
        if (requests.isEmpty()) return emptyList()

        val items = itemsByRequest(requests.map { it[InfoRequests.id] })
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

    /** Поля, которые клиенту разрешено изменить по открытому запросу. */
    fun requestedFields(claimId: Long): Set<String> =
        itemsWithRequest().select(InfoRequestItems.fieldName)
            .where { isOpen(claimId) }
            .mapNotNull { it[InfoRequestItems.fieldName] }.toSet()

    fun isFileRequested(claimId: Long, fileId: Long): Boolean =
        itemsWithRequest().selectAll().where { isOpen(claimId) and (InfoRequestItems.fileId eq fileId) }.any()

    fun closeOpen(claimId: Long, status: InfoRequestStatus, at: OffsetDateTime) {
        val open = InfoRequests.select(InfoRequests.id)
            .where { isOpen(claimId) }
            .singleOrNull()?.get(InfoRequests.id) ?: return
        InfoRequests.update({ InfoRequests.id eq open }) {
            it[InfoRequests.status] = status
            it[closedAt] = at
        }
        if (status == InfoRequestStatus.FULFILLED) {
            InfoRequestItems.update({ InfoRequestItems.requestId eq open }) { it[isFulfilled] = true }
        }
    }

    /** Запрос различает «нет файла», «файл отклонён», «плохое качество» и подсказывает, что сделать. */
    fun createFromCompleteness(claimId: Long, result: CompletenessResult, dueDays: Long, at: OffsetDateTime) {
        val requestId = InfoRequests.insert {
            it[InfoRequests.claimId] = claimId
            it[requestSource] = ActorType.SYSTEM
            it[message] = "Для рассмотрения обращения не хватает материалов. Дополните их и отправьте обращение повторно."
            it[status] = InfoRequestStatus.OPEN
            it[dueAt] = at.plusDays(dueDays)
            it[createdAt] = at
        }[InfoRequests.id]

        result.missingDocuments.forEach { doc ->
            val problems = result.problemFiles.filter { it.documentType == doc.documentType }
            problems.forEach { file ->
                insertItem(requestId, documentType = doc.documentType, issue = file.issue, hint = file.hint,
                    fileId = file.fileId, comment = "Файл не принят — замените его")
            }
            val stillMissing = doc.required - doc.uploaded - problems.size
            if (stillMissing > 0) {
                insertItem(requestId, documentType = doc.documentType, minCount = stillMissing,
                    comment = "Загружено ${doc.uploaded} из ${doc.required}")
            }
        }
        result.missingFields.forEach { field ->
            insertItem(requestId, fieldName = field.code, comment = "Заполните поле «${field.title}»")
        }
    }

    private fun itemsWithRequest() =
        InfoRequestItems.join(InfoRequests, JoinType.INNER, InfoRequestItems.requestId, InfoRequests.id)

    private fun isOpen(claimId: Long) =
        (InfoRequests.claimId eq claimId) and (InfoRequests.status eq InfoRequestStatus.OPEN)

    private fun itemsByRequest(requestIds: List<Long>): Map<Long, List<InfoRequestItemDto>> =
        InfoRequestItems
            .join(DocumentTypes, JoinType.LEFT, InfoRequestItems.documentType, DocumentTypes.code)
            .selectAll()
            .where { InfoRequestItems.requestId inList requestIds }
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

    private fun insertItem(
        requestId: Long, documentType: String? = null, fieldName: String? = null, minCount: Int = 1,
        issue: MaterialIssue = MaterialIssue.MISSING, hint: String? = null, fileId: Long? = null, comment: String,
    ) {
        InfoRequestItems.insert {
            it[InfoRequestItems.requestId] = requestId
            it[InfoRequestItems.documentType] = documentType
            it[InfoRequestItems.fieldName] = fieldName
            it[InfoRequestItems.minCount] = minCount.toShort()
            it[InfoRequestItems.issue] = issue
            it[InfoRequestItems.hint] = hint
            it[InfoRequestItems.fileId] = fileId
            it[InfoRequestItems.comment] = comment
            it[isFulfilled] = false
        }
    }
}
