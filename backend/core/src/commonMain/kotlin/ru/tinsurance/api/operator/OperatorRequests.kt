package ru.tinsurance.api.operator

import kotlinx.serialization.Serializable
import ru.tinsurance.api.DamageType
import ru.tinsurance.api.DecisionKind

@Serializable
data class DirectionDto(val code: String, val name: String, val description: String?)

/** Оператор согласен с действующим решением системы (DAMAGE, ROUTING или PRIORITY). */
@Serializable
data class ConfirmDecisionRequest(val kind: DecisionKind)

/**
 * Оператор исправляет решение; причина обязательна, исходное решение остаётся в истории.
 * Заполняется поле, соответствующее [kind]: PRIORITY → [priority] (1–3), ROUTING → [direction]
 * (код из GET /directions), DAMAGE → [damageType].
 */
@Serializable
data class OverrideDecisionRequest(
    val kind: DecisionKind,
    val reason: String,
    val priority: Int? = null,
    val direction: String? = null,
    val damageType: DamageType? = null,
)
