package ru.tinsurance.server.operator

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import ru.tinsurance.api.DecisionKind
import ru.tinsurance.api.operator.OverrideDecisionRequest
import ru.tinsurance.server.claims.DirectionRepository
import ru.tinsurance.server.common.ValidationException
import ru.tinsurance.server.db.Claims
import ru.tinsurance.server.routing.RoutingRules

/** Новое значение решения из запроса оператора и его применение к обращению. Внутри транзакции. */
object DecisionOverride {
    private val PRIORITIES = 1..3

    fun value(request: OverrideDecisionRequest): JsonObject = when (request.kind) {
        DecisionKind.PRIORITY -> {
            val priority = request.priority ?: throw ValidationException("Укажите priority")
            if (priority !in PRIORITIES) throw ValidationException("priority: от ${PRIORITIES.first} до ${PRIORITIES.last}")
            buildJsonObject { put("priority", priority) }
        }
        DecisionKind.ROUTING -> {
            val direction = request.direction?.trim()?.takeIf { it.isNotEmpty() } ?: throw ValidationException("Укажите direction")
            if (!DirectionRepository.exists(direction)) throw ValidationException("Неизвестное направление: $direction")
            buildJsonObject {
                put("direction", direction)
                put("manualReview", direction == RoutingRules.MANUAL_REVIEW)
            }
        }
        DecisionKind.DAMAGE -> {
            val damage = request.damageType ?: throw ValidationException("Укажите damageType")
            buildJsonObject { put("damageType", damage.name) }
        }
        else -> throw ValidationException("Оператор подтверждает или исправляет только DAMAGE, ROUTING и PRIORITY")
    }

    /** Направление и приоритет хранятся и в самом обращении — по ним строится очередь. */
    fun applyToClaim(claimId: Long, request: OverrideDecisionRequest) {
        when (request.kind) {
            DecisionKind.PRIORITY -> Claims.update({ Claims.id eq claimId }) { it[priority] = request.priority!!.toShort() }
            DecisionKind.ROUTING -> {
                val direction = request.direction!!.trim()
                Claims.update({ Claims.id eq claimId }) {
                    it[directionCode] = direction
                    it[requiresManualReview] = direction == RoutingRules.MANUAL_REVIEW
                }
            }
            else -> Unit
        }
    }
}
