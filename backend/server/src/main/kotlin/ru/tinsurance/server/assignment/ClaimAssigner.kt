package ru.tinsurance.server.assignment

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import ru.tinsurance.api.ClaimStatus
import ru.tinsurance.server.claims.Actor
import ru.tinsurance.server.claims.AuditEntity
import ru.tinsurance.server.claims.AuditWriter
import ru.tinsurance.server.claims.ClaimStatusService
import ru.tinsurance.server.db.AuditLog
import ru.tinsurance.server.db.Claims
import java.time.OffsetDateTime

/** Назначение обращения оператору и снятие назначения, с записью в историю и журнал. Внутри транзакции. */
object ClaimAssigner {
    private const val ASSIGN = "ASSIGN"
    private const val REASSIGN = "REASSIGN"
    private const val RELEASE = "RELEASE"

    /** QUEUED → ASSIGNED. Исполнитель записывается до смены статуса: этого требует CHECK в БД. */
    fun assign(claimId: Long, operatorId: Long, actor: Actor, reason: String?, at: OffsetDateTime = OffsetDateTime.now()) {
        setAssignee(claimId, operatorId)
        ClaimStatusService.transition(claimId, ClaimStatus.QUEUED, ClaimStatus.ASSIGNED, actor, reason, at = at)
        AuditWriter.record(claimId, AuditEntity.CLAIM, claimId, ASSIGN, actor, operatorJson(operatorId), comment = reason, at = at)
    }

    /** Другой оператор без смены статуса — только администратором. */
    fun reassign(claimId: Long, from: Long?, to: Long, actor: Actor, reason: String, at: OffsetDateTime = OffsetDateTime.now()) {
        setAssignee(claimId, to)
        AuditWriter.record(
            claimId, AuditEntity.CLAIM, claimId, REASSIGN, actor,
            newValue = operatorJson(to), oldValue = from?.let(::operatorJson), comment = reason, at = at,
        )
    }

    /** ASSIGNED или IN_REVIEW → QUEUED, исполнитель снимается. */
    fun release(claimId: Long, from: ClaimStatus, operatorId: Long?, actor: Actor, reason: String, at: OffsetDateTime = OffsetDateTime.now()) {
        ClaimStatusService.transition(claimId, from, ClaimStatus.QUEUED, actor, reason, at = at)
        setAssignee(claimId, null)
        AuditWriter.record(claimId, AuditEntity.CLAIM, claimId, RELEASE, actor, oldValue = operatorId?.let(::operatorJson), comment = reason, at = at)
    }

    /** Кому обращение было назначено последним — к нему оно возвращается после дозагрузки материалов. */
    fun previousOperator(claimId: Long): Long? =
        AuditLog.select(AuditLog.newValue)
            .where { (AuditLog.claimId eq claimId) and (AuditLog.action inList listOf(ASSIGN, REASSIGN)) }
            .orderBy(AuditLog.id, SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.get(AuditLog.newValue)?.jsonObject?.get("operatorId")?.jsonPrimitive?.longOrNull

    private fun setAssignee(claimId: Long, operatorId: Long?) {
        Claims.update({ Claims.id eq claimId }) { it[assignedOperatorId] = operatorId }
    }

    private fun operatorJson(operatorId: Long) = buildJsonObject { put("operatorId", operatorId) }
}
