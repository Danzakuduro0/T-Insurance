package ru.tinsurance.server.mobile

import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import ru.tinsurance.api.mobile.*
import ru.tinsurance.server.claims.ClaimRules
import ru.tinsurance.server.claims.InfoRequestRepository
import ru.tinsurance.server.claims.ParticipantRepository
import ru.tinsurance.server.common.NotFoundException
import ru.tinsurance.server.common.iso
import ru.tinsurance.server.db.*
import ru.tinsurance.server.files.FileRepository

/** Чтение данных для приложения клиента. Вызывается внутри транзакции. */
object MobileClaimQueries {

    fun eventTypes(): List<EventTypeDto> =
        EventTypes.selectAll().orderBy(EventTypes.code).map { type ->
            val code = type[EventTypes.code]
            EventTypeDto(
                code = code,
                name = type[EventTypes.name],
                fields = ClaimRules.fields(code).map { FormFieldDto(it.code, it.title, it.kind, it.valueType, it.required) },
                documents = ClaimRules.documents(code).map {
                    FormDocumentDto(it.documentType, it.name, it.minCount, it.allowedMimeTypes, it.maxSizeBytes)
                },
            )
        }

    fun list(clientId: Long): List<ClaimListItem> {
        val v = ClientClaimListView
        return v.selectAll()
            .where { v.clientId eq clientId }
            .orderBy(v.updatedAt, SortOrder.DESC)
            .map {
                ClaimListItem(
                    id = it[v.id],
                    claimNumber = it[v.claimNumber],
                    status = it[v.status],
                    statusTitle = it[v.statusTitle],
                    isFinal = it[v.isFinal],
                    eventTypeName = it[v.eventTypeName],
                    eventAt = it[v.eventAt]?.iso(),
                    createdAt = it[v.createdAt].iso(),
                    updatedAt = it[v.updatedAt].iso(),
                    actionRequired = it[v.actionRequired],
                    actionMessage = it[v.actionMessage],
                )
            }
    }

    fun details(clientId: Long, claimId: Long): ClaimDetails {
        val row = Claims
            .join(ClaimStatuses, JoinType.INNER, Claims.status, ClaimStatuses.code)
            .join(Policies, JoinType.LEFT, Claims.policyId, Policies.id)
            .join(EventTypes, JoinType.LEFT, Claims.eventType, EventTypes.code)
            .selectAll()
            .where { (Claims.id eq claimId) and (Claims.clientId eq clientId) }
            .singleOrNull() ?: throw NotFoundException("Обращение не найдено")
        return row.toClaimDetails()
    }

    /** Только события, видимые клиенту; внутренняя причина перехода не отдаётся. */
    fun timeline(claimId: Long): List<TimelineEvent> =
        ClaimStatusHistory.join(ClaimStatuses, JoinType.INNER, ClaimStatusHistory.toStatus, ClaimStatuses.code)
            .selectAll()
            .where { (ClaimStatusHistory.claimId eq claimId) and (ClaimStatusHistory.visibleToClient eq true) }
            .orderBy(ClaimStatusHistory.createdAt to SortOrder.ASC, ClaimStatusHistory.id to SortOrder.ASC)
            .map {
                TimelineEvent(
                    status = it[ClaimStatusHistory.toStatus],
                    title = it[ClaimStatusHistory.clientTitle] ?: it[ClaimStatuses.nameForClient],
                    comment = it[ClaimStatusHistory.clientComment],
                    at = it[ClaimStatusHistory.createdAt].iso(),
                )
            }

    private fun ResultRow.toClaimDetails(): ClaimDetails {
        val claimId = this[Claims.id]
        return ClaimDetails(
            id = claimId,
            claimNumber = this[Claims.claimNumber],
            status = this[Claims.status],
            statusTitle = this[ClaimStatuses.nameForClient],
            isFinal = this[ClaimStatuses.isFinal],
            policyId = this[Claims.policyId],
            policyNumber = getOrNull(Policies.policyNumber),
            eventType = this[Claims.eventType],
            eventTypeName = getOrNull(EventTypes.name),
            eventAt = this[Claims.eventAt]?.iso(),
            eventLocation = this[Claims.eventLocation],
            description = this[Claims.description],
            contactPhone = this[Claims.contactPhone],
            participants = ParticipantRepository.list(claimId),
            details = this[Claims.eventDetails],
            createdAt = this[Claims.createdAt].iso(),
            updatedAt = this[Claims.updatedAt].iso(),
            submittedAt = this[Claims.submittedAt]?.iso(),
            openInfoRequest = InfoRequestRepository.list(claimId, onlyOpen = true).firstOrNull(),
            files = FileRepository.listDtos(claimId),
        )
    }
}
