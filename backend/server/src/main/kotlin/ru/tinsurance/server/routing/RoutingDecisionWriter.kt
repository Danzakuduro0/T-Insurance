package ru.tinsurance.server.routing

import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import ru.tinsurance.api.ClaimStatus
import ru.tinsurance.api.DecisionKind
import ru.tinsurance.server.claims.Actor
import ru.tinsurance.server.claims.ClaimDecisionWriter
import ru.tinsurance.server.claims.ClaimStatusService
import ru.tinsurance.server.db.Claims
import java.time.OffsetDateTime

/** Записывает решение маршрутизации и ставит обращение в очередь. Вызывается внутри транзакции. */
object RoutingDecisionWriter {

    fun apply(claimId: Long, decision: RoutingDecision, photos: List<PhotoAnalysis>, at: OffsetDateTime) {
        decision.damageType?.let { recordDamage(claimId, it, decision.confidence, photos, at) }
        ClaimDecisionWriter.setBySystem(
            claimId, DecisionKind.ROUTING,
            value = buildJsonObject {
                put("direction", decision.direction)
                put("manualReview", decision.manualReview)
                putJsonArray("reasons") { decision.reasons.forEach { add(it) } }
            },
            confidence = decision.confidence,
            reason = decision.reasons.joinToString("; "),
            ruleVersion = RoutingRules.VERSION, at = at,
        )
        ClaimDecisionWriter.setBySystem(
            claimId, DecisionKind.PRIORITY,
            value = buildJsonObject { put("priority", decision.priority) },
            confidence = null,
            reason = if (decision.manualReview) "Ручная проверка — приоритет повышен" else "Обычный приоритет",
            ruleVersion = RoutingRules.VERSION, at = at,
        )
        Claims.update({ Claims.id eq claimId }) {
            it[directionCode] = decision.direction
            it[priority] = decision.priority.toShort()
            it[requiresManualReview] = decision.manualReview
        }
        ClaimStatusService.transition(
            claimId, ClaimStatus.IN_ANALYSIS, ClaimStatus.QUEUED, Actor.SYSTEM,
            reason = decision.reasons.joinToString("; "), at = at,
        )
    }

    private fun recordDamage(claimId: Long, damage: String, confidence: Float?, photos: List<PhotoAnalysis>, at: OffsetDateTime) {
        ClaimDecisionWriter.setBySystem(
            claimId, DecisionKind.DAMAGE,
            value = buildJsonObject {
                put("damageType", damage)
                putJsonArray("photos") {
                    photos.filter { it.damageType != null }.forEach { p ->
                        addJsonObject { put("fileId", p.fileId); put("damageType", p.damageType); put("confidence", p.confidence) }
                    }
                }
            },
            confidence = confidence,
            reason = "Модель: $damage",
            ruleVersion = RoutingRules.VERSION, at = at,
        )
    }
}
