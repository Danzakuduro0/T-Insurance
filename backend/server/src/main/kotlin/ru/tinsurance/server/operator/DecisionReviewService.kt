package ru.tinsurance.server.operator

import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.jdbc.Database
import ru.tinsurance.api.DecisionKind
import ru.tinsurance.api.DecisionSource
import ru.tinsurance.api.operator.ClaimCard
import ru.tinsurance.api.operator.ConfirmDecisionRequest
import ru.tinsurance.api.operator.OverrideDecisionRequest
import ru.tinsurance.server.claims.*
import ru.tinsurance.server.common.ConflictException
import ru.tinsurance.server.common.NotFoundException
import ru.tinsurance.server.common.ValidationException
import ru.tinsurance.server.common.tx
import ru.tinsurance.server.db.ClaimDecisions
import java.time.OffsetDateTime

/**
 * Оператор подтверждает или исправляет решение системы. Исходное решение остаётся в истории,
 * при исправлении нужна причина; всё пишется в журнал.
 */
class DecisionReviewService(private val db: Database) {

    suspend fun confirm(actor: Actor, claimId: Long, request: ConfirmDecisionRequest): ClaimCard = db.tx {
        requireReviewable(request.kind)
        editableClaim(claimId, actor)
        val current = ClaimDecisionWriter.current(claimId, request.kind)
            ?: throw ConflictException("Решения «${request.kind}» пока нет — его можно только задать с причиной")
        if (current[ClaimDecisions.decisionSource] != DecisionSource.SYSTEM) {
            throw ConflictException("Это решение уже проверено сотрудником")
        }
        val id = ClaimDecisionWriter.confirm(current, actor.id!!, OffsetDateTime.now())
        AuditWriter.record(claimId, AuditEntity.DECISION, id, "DECISION_CONFIRM", actor, newValue = current[ClaimDecisions.value])
        ClaimCardQueries.card(claimId)
    }

    suspend fun override(actor: Actor, claimId: Long, request: OverrideDecisionRequest): ClaimCard = db.tx {
        requireReviewable(request.kind)
        val reason = request.reason.trim().takeIf { it.isNotEmpty() }
            ?: throw ValidationException("Укажите причину исправления — она попадёт в журнал")
        editableClaim(claimId, actor)
        val value = DecisionOverride.value(request)
        val old = ClaimDecisionWriter.current(claimId, request.kind)?.get(ClaimDecisions.value) as? JsonObject
        if (old != null && sameValue(old, value)) throw ValidationException("Значение не изменилось — подтвердите решение")

        val id = ClaimDecisionWriter.overrideBy(claimId, request.kind, value, reason, actor.id!!, OffsetDateTime.now())
        DecisionOverride.applyToClaim(claimId, request)
        AuditWriter.record(claimId, AuditEntity.DECISION, id, "DECISION_OVERRIDE", actor, newValue = value, oldValue = old, comment = reason)
        ClaimCardQueries.card(claimId)
    }

    private fun requireReviewable(kind: DecisionKind) {
        if (!kind.isReviewable) throw ValidationException("Оператор подтверждает или исправляет только DAMAGE, ROUTING и PRIORITY")
    }

    private fun editableClaim(claimId: Long, actor: Actor) {
        val claim = ClaimRepository.findForUpdate(claimId) ?: throw NotFoundException("Обращение не найдено")
        ClaimWorkAccess.requireCanEdit(claim, actor)
    }

    /** Сравниваются только ключи нового значения: у решения системы бывают дополнительные поля (фото, причины). */
    private fun sameValue(old: JsonObject, new: JsonObject) = new.all { (key, value) -> old[key] == value }
}
