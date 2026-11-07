package ru.tinsurance.server.claims

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import ru.tinsurance.api.DecisionAction
import ru.tinsurance.api.DecisionKind
import ru.tinsurance.api.DecisionSource
import ru.tinsurance.server.db.ClaimDecisions
import java.time.OffsetDateTime

/**
 * Решения по обращению (claim_decisions) только добавляются: новое решение того же вида становится действующим,
 * предыдущее остаётся в истории со ссылкой supersedes_id. Вызывается внутри транзакции.
 */
object ClaimDecisionWriter {

    fun current(claimId: Long, kind: DecisionKind): ResultRow? =
        ClaimDecisions.selectAll()
            .where { (ClaimDecisions.claimId eq claimId) and (ClaimDecisions.kind eq kind) and (ClaimDecisions.isCurrent eq true) }
            .singleOrNull()

    fun setBySystem(
        claimId: Long, kind: DecisionKind, value: JsonObject, confidence: Float?, reason: String, ruleVersion: String,
        at: OffsetDateTime,
    ): Long = replaceCurrent(claimId, kind, at) { previous ->
        NewDecision(DecisionSource.SYSTEM, DecisionAction.SET, value, confidence, reason, ruleVersion, previous, createdBy = null)
    }

    /** Сотрудник согласен с действующим решением: значение то же, причина не нужна. */
    fun confirm(current: ResultRow, staffId: Long, at: OffsetDateTime): Long =
        replaceCurrent(current[ClaimDecisions.claimId], current[ClaimDecisions.kind], at) { previous ->
            NewDecision(
                DecisionSource.OPERATOR, DecisionAction.CONFIRM, current[ClaimDecisions.value],
                current[ClaimDecisions.confidence], reason = null, ruleVersion = current[ClaimDecisions.ruleVersion],
                supersedesId = previous, createdBy = staffId,
            )
        }

    /** Сотрудник задаёт своё значение: OVERRIDE, если было решение системы, иначе SET. Причина обязательна. */
    fun overrideBy(claimId: Long, kind: DecisionKind, value: JsonObject, reason: String, staffId: Long, at: OffsetDateTime): Long =
        replaceCurrent(claimId, kind, at) { previous ->
            NewDecision(
                DecisionSource.OPERATOR, if (previous != null) DecisionAction.OVERRIDE else DecisionAction.SET,
                value, confidence = null, reason = reason, ruleVersion = null, supersedesId = previous, createdBy = staffId,
            )
        }

    private class NewDecision(
        val source: DecisionSource, val action: DecisionAction, val value: JsonElement, val confidence: Float?,
        val reason: String?, val ruleVersion: String?, val supersedesId: Long?, val createdBy: Long?,
    )

    private fun replaceCurrent(claimId: Long, kind: DecisionKind, at: OffsetDateTime, build: (previous: Long?) -> NewDecision): Long {
        val previous = current(claimId, kind)?.get(ClaimDecisions.id)
        previous?.let { id -> ClaimDecisions.update({ ClaimDecisions.id eq id }) { it[isCurrent] = false } }
        val d = build(previous)
        return ClaimDecisions.insert {
            it[ClaimDecisions.claimId] = claimId
            it[ClaimDecisions.kind] = kind
            it[decisionSource] = d.source
            it[action] = d.action
            it[value] = d.value
            it[confidence] = d.confidence
            it[reason] = d.reason
            it[ruleVersion] = d.ruleVersion
            it[supersedesId] = d.supersedesId
            it[isCurrent] = true
            it[createdBy] = d.createdBy
            it[createdAt] = at
        }[ClaimDecisions.id]
    }
}
