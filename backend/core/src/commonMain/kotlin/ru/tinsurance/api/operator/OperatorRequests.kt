package ru.tinsurance.api.operator

import kotlinx.serialization.Serializable
import ru.tinsurance.api.DamageType
import ru.tinsurance.api.DecisionKind
import ru.tinsurance.api.OperatorAvailability

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

/** ONLINE — на смене: система назначает обращения; BREAK и OFFLINE — новые не назначаются. */
@Serializable
data class AvailabilityRequest(val availability: OperatorAvailability)

/** Вернуть обращение в очередь; причина попадёт в журнал. */
@Serializable
data class ReleaseClaimRequest(val reason: String)

/** Администратор назначает или переназначает обращение; причина обязательна. */
@Serializable
data class AssignClaimRequest(val operatorId: Long, val reason: String)

@Serializable
data class OperatorDto(
    val id: Long,
    val fullName: String,
    val availability: OperatorAvailability?,
    val directions: List<String>,
    /** Обращения в статусах ASSIGNED и IN_REVIEW. */
    val activeClaims: Int,
    val maxActiveClaims: Int,
)
