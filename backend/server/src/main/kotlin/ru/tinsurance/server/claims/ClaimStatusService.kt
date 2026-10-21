package ru.tinsurance.server.claims

import ru.tinsurance.api.ClaimStatus
import ru.tinsurance.server.common.ConflictException
import ru.tinsurance.server.common.ValidationException
import ru.tinsurance.server.db.AuditLog
import ru.tinsurance.server.db.ClaimStatuses
import ru.tinsurance.server.db.ClaimStatusHistory
import ru.tinsurance.server.db.Claims
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZoneLiteral
import org.jetbrains.exposed.v1.jdbc.*
import java.time.OffsetDateTime

enum class ActorType { SYSTEM, CLIENT, OPERATOR, ADMIN }

data class Actor(val type: ActorType, val id: Long?) {
    companion object {
        val SYSTEM = Actor(ActorType.SYSTEM, null)
    }
}

/**
 * Единственное место, где меняется статус обращения.
 * Вызывается внутри транзакции. Допустимость перехода и права исполнителя
 * дополнительно проверяют триггеры БД (status_transitions).
 */
object ClaimStatusService {

    fun recordCreated(claimId: Long, actor: Actor, at: OffsetDateTime = OffsetDateTime.now()) {
        ClaimStatusHistory.insert {
            it[ClaimStatusHistory.claimId] = claimId
            it[fromStatus] = null
            it[toStatus] = ClaimStatus.DRAFT.name
            it[actorType] = actor.type.name
            it[actorId] = actor.id
            it[visibleToClient] = true
            it[createdAt] = at
        }
        AuditLog.insert {
            it[AuditLog.claimId] = claimId
            it[entityType] = "CLAIM"
            it[entityId] = claimId
            it[action] = "CREATE"
            it[actorType] = actor.type.name
            it[actorId] = actor.id
            it[createdAt] = at
        }
    }

    fun transition(
        claimId: Long,
        from: ClaimStatus,
        to: ClaimStatus,
        actor: Actor,
        reason: String? = null,
        /** null — как задано для статуса в claim_statuses.visible_to_client (вопрос 12) */
        visibleToClient: Boolean? = null,
        /** подпись события для клиента, если отличается от названия статуса */
        clientTitle: String? = null,
        /** пояснение для клиента в таймлайне; reason — внутренний и клиенту не показывается (вопрос 12) */
        clientComment: String? = null,
        at: OffsetDateTime = OffsetDateTime.now(),
    ) {
        ClaimTransitions.check(from, to, actor.type)
        if (ClaimTransitions.requiresReason(to, actor.type) && reason.isNullOrBlank()) {
            throw ValidationException("Для этого действия нужно указать причину — она попадёт в журнал")
        }
        val visible = visibleToClient ?: ClaimStatuses.select(ClaimStatuses.visibleToClient)
            .where { ClaimStatuses.code eq to.name }.single()[ClaimStatuses.visibleToClient]

        // where status = from — защита от гонки: если статус уже успели сменить, ничего не обновится
        val updated = Claims.update({ (Claims.id eq claimId) and (Claims.status eq from.name) }) {
            it[status] = to.name
            it[statusChangedAt] = at   // вопрос 34: от времени входа в статус считается превышение срока
            it[version] = Claims.version + 1
            if (from == ClaimStatus.COMPLETED || from == ClaimStatus.CANCELLED) it[closedAt] = null  // переоткрыто администратором
            when (to) {
                // время первой отправки: повторная отправка после дозагрузки не сдвигает обращение в конец очереди
                ClaimStatus.SUBMITTED -> it[submittedAt] = Coalesce(Claims.submittedAt, timestampWithTimeZoneLiteral(at))
                ClaimStatus.ASSIGNED -> it[assignedAt] = at
                ClaimStatus.COMPLETED, ClaimStatus.CANCELLED -> it[closedAt] = at
                else -> Unit
            }
        }
        if (updated == 0) throw ConflictException("Статус обращения уже изменился, обновите данные")

        ClaimStatusHistory.insert {
            it[ClaimStatusHistory.claimId] = claimId
            it[fromStatus] = from.name
            it[toStatus] = to.name
            it[actorType] = actor.type.name
            it[actorId] = actor.id
            it[ClaimStatusHistory.reason] = reason
            it[ClaimStatusHistory.clientTitle] = clientTitle
            it[ClaimStatusHistory.clientComment] = clientComment
            it[ClaimStatusHistory.visibleToClient] = visible
            it[createdAt] = at
        }
        AuditLog.insert {
            it[AuditLog.claimId] = claimId
            it[entityType] = "CLAIM"
            it[entityId] = claimId
            it[action] = "STATUS_CHANGE"
            it[oldValue] = JsonPrimitive(from.name)
            it[newValue] = JsonPrimitive(to.name)
            it[actorType] = actor.type.name
            it[actorId] = actor.id
            it[comment] = reason
            it[createdAt] = at
        }
    }
}
