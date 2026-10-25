package ru.tinsurance.server.claims

import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
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

    fun setBySystem(
        claimId: Long, kind: DecisionKind, value: JsonObject, confidence: Float?, reason: String, ruleVersion: String,
        at: OffsetDateTime,
    ): Long {
        val previous = currentId(claimId, kind)
        previous?.let { id -> ClaimDecisions.update({ ClaimDecisions.id eq id }) { it[isCurrent] = false } }

        return ClaimDecisions.insert {
            it[ClaimDecisions.claimId] = claimId
            it[ClaimDecisions.kind] = kind
            it[decisionSource] = DecisionSource.SYSTEM
            it[action] = DecisionAction.SET
            it[ClaimDecisions.value] = value
            it[ClaimDecisions.confidence] = confidence
            it[ClaimDecisions.reason] = reason
            it[ClaimDecisions.ruleVersion] = ruleVersion
            it[supersedesId] = previous
            it[isCurrent] = true
            it[createdAt] = at
        }[ClaimDecisions.id]
    }

    private fun currentId(claimId: Long, kind: DecisionKind): Long? =
        ClaimDecisions.select(ClaimDecisions.id)
            .where { (ClaimDecisions.claimId eq claimId) and (ClaimDecisions.kind eq kind) and (ClaimDecisions.isCurrent eq true) }
            .singleOrNull()?.get(ClaimDecisions.id)
}
