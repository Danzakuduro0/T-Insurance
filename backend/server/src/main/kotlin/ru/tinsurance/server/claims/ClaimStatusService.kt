package ru.tinsurance.server.claims

import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.Coalesce
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZoneLiteral
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import ru.tinsurance.api.ClaimStatus
import ru.tinsurance.server.common.ConflictException
import ru.tinsurance.server.common.ValidationException
import ru.tinsurance.server.db.ClaimStatusHistory
import ru.tinsurance.server.db.ClaimStatuses
import ru.tinsurance.server.db.Claims
import java.time.OffsetDateTime

/** Единственное место, где меняется статус обращения. Вызывается внутри транзакции. */
object ClaimStatusService {

    fun recordCreated(claimId: Long, actor: Actor, at: OffsetDateTime = OffsetDateTime.now()) {
        insertHistory(claimId, from = null, to = ClaimStatus.DRAFT, actor, visible = true, at = at)
        AuditWriter.record(claimId, AuditEntity.CLAIM, claimId, "CREATE", actor, at = at)
    }

    /**
     * [visibleToClient] null — как задано для статуса в справочнике; [reason] внутренний,
     * клиент видит [clientTitle] и [clientComment].
     */
    fun transition(
        claimId: Long,
        from: ClaimStatus,
        to: ClaimStatus,
        actor: Actor,
        reason: String? = null,
        visibleToClient: Boolean? = null,
        clientTitle: String? = null,
        clientComment: String? = null,
        at: OffsetDateTime = OffsetDateTime.now(),
    ) {
        ClaimTransitions.check(from, to, actor.type)
        if (ClaimTransitions.requiresReason(to, actor.type) && reason.isNullOrBlank()) {
            throw ValidationException("Для этого действия нужно указать причину — она попадёт в журнал")
        }
        updateStatus(claimId, from, to, at)
        insertHistory(
            claimId, from, to, actor,
            visible = visibleToClient ?: isVisibleToClient(to),
            reason = reason, clientTitle = clientTitle, clientComment = clientComment, at = at,
        )
        AuditWriter.record(
            claimId, AuditEntity.CLAIM, claimId, "STATUS_CHANGE", actor,
            newValue = JsonPrimitive(to.name), oldValue = JsonPrimitive(from.name), comment = reason, at = at,
        )
    }

    /** `where status = from` защищает от гонки: если статус уже сменили, ничего не обновится. */
    private fun updateStatus(claimId: Long, from: ClaimStatus, to: ClaimStatus, at: OffsetDateTime) {
        val updated = Claims.update({ (Claims.id eq claimId) and (Claims.status eq from) }) {
            it[status] = to
            it[statusChangedAt] = at
            it[version] = Claims.version + 1
            if (from.isFinal) it[closedAt] = null
            when (to) {
                // повторная отправка после дозагрузки не сдвигает обращение в конец очереди
                ClaimStatus.SUBMITTED -> it[submittedAt] = Coalesce(Claims.submittedAt, timestampWithTimeZoneLiteral(at))
                ClaimStatus.ASSIGNED -> it[assignedAt] = at
                ClaimStatus.COMPLETED, ClaimStatus.CANCELLED -> it[closedAt] = at
                else -> Unit
            }
        }
        if (updated == 0) throw ConflictException("Статус обращения уже изменился, обновите данные")
    }

    private fun isVisibleToClient(status: ClaimStatus): Boolean =
        ClaimStatuses.select(ClaimStatuses.visibleToClient).where { ClaimStatuses.code eq status }.single()[ClaimStatuses.visibleToClient]

    private fun insertHistory(
        claimId: Long, from: ClaimStatus?, to: ClaimStatus, actor: Actor, visible: Boolean,
        reason: String? = null, clientTitle: String? = null, clientComment: String? = null, at: OffsetDateTime,
    ) {
        ClaimStatusHistory.insert {
            it[ClaimStatusHistory.claimId] = claimId
            it[fromStatus] = from
            it[toStatus] = to
            it[actorType] = actor.type
            it[actorId] = actor.id
            it[ClaimStatusHistory.reason] = reason
            it[ClaimStatusHistory.clientTitle] = clientTitle
            it[ClaimStatusHistory.clientComment] = clientComment
            it[visibleToClient] = visible
            it[createdAt] = at
        }
    }
}
