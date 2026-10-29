package ru.tinsurance.server.claims

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import ru.tinsurance.api.*
import ru.tinsurance.api.operator.RequestMaterialsRequest
import ru.tinsurance.server.db.*
import java.time.OffsetDateTime

object ManualMaterialsRepository {
    fun open(claimId: Long): ResultRow? = InfoRequests.selectAll().where {
        (InfoRequests.claimId eq claimId) and (InfoRequests.status eq InfoRequestStatus.OPEN) and
            (InfoRequests.requestSource eq ActorType.OPERATOR)
    }.singleOrNull()

    fun create(claimId: Long, operatorId: Long, request: RequestMaterialsRequest, dueDays: Long, at: OffsetDateTime): Long {
        val id = InfoRequests.insert {
            it[InfoRequests.claimId] = claimId
            it[requestSource] = ActorType.OPERATOR
            it[requestedBy] = operatorId
            it[message] = request.message.trim()
            it[status] = InfoRequestStatus.OPEN
            it[dueAt] = at.plusDays(dueDays)
            it[createdAt] = at
        }[InfoRequests.id]
        request.items.forEach { item ->
            InfoRequestItems.insert {
                it[requestId] = id
                it[documentType] = item.documentType
                it[fieldName] = item.fieldName
                it[fileId] = item.fileId
                it[minCount] = item.minCount.toShort()
                it[comment] = item.comment.trim()
                it[hint] = item.comment.trim()
                it[issue] = MaterialIssue.MISSING
                it[isFulfilled] = false
            }
        }
        return id
    }

    fun items(requestId: Long): List<ResultRow> = InfoRequestItems.selectAll()
        .where { InfoRequestItems.requestId eq requestId }.toList()

    fun latestFieldAnswers(claimId: Long, since: OffsetDateTime): Map<String, JsonElement> =
        AuditLog.selectAll().where {
            (AuditLog.claimId eq claimId) and (AuditLog.action eq "UPDATE") and
                (AuditLog.actorType eq ActorType.CLIENT) and (AuditLog.createdAt greaterEq since)
        }.orderBy(AuditLog.id, SortOrder.DESC).flatMap { row ->
            (row[AuditLog.newValue] as? JsonObject).orEmpty().entries.map { it.key to it.value }
        }.groupBy({ it.first }, { it.second }).mapValues { it.value.first() }

}
