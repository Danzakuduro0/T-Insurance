package ru.tinsurance.server.claims

import ru.tinsurance.server.db.ClaimDecisions
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime

/**
 * Запись решений системы в claim_decisions (только добавление, история не перезаписывается).
 * Новое решение того же вида становится действующим, предыдущее остаётся в истории со ссылкой supersedes_id.
 * Подтверждение и исправление оператором — шаг 4. Вызывается внутри транзакции.
 */
object ClaimDecisionWriter {

    fun setBySystem(
        claimId: Long, kind: String, value: JsonObject, confidence: Float?, reason: String, ruleVersion: String,
        at: OffsetDateTime,
    ): Long {
        val previous = ClaimDecisions.select(ClaimDecisions.id)
            .where { (ClaimDecisions.claimId eq claimId) and (ClaimDecisions.kind eq kind) and (ClaimDecisions.isCurrent eq true) }
            .singleOrNull()?.get(ClaimDecisions.id)
        previous?.let { id -> ClaimDecisions.update({ ClaimDecisions.id eq id }) { it[isCurrent] = false } }

        return ClaimDecisions.insert {
            it[ClaimDecisions.claimId] = claimId
            it[ClaimDecisions.kind] = kind
            it[decisionSource] = "SYSTEM"
            it[action] = "SET"
            it[ClaimDecisions.value] = value
            it[ClaimDecisions.confidence] = confidence
            // вопрос 23: для решения на правилах вместо вероятности — сработавшие правила
            it[ClaimDecisions.reason] = reason
            it[ClaimDecisions.ruleVersion] = ruleVersion
            it[supersedesId] = previous
            it[isCurrent] = true
            it[createdAt] = at
        }[ClaimDecisions.id]
    }
}
