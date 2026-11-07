package ru.tinsurance.server.claims

import io.ktor.http.*
import ru.tinsurance.api.ActorType
import ru.tinsurance.api.ActorType.*
import ru.tinsurance.api.ClaimStatus
import ru.tinsurance.api.ClaimStatus.*
import ru.tinsurance.server.common.ApiException

class IllegalTransitionException(message: String) : ApiException(HttpStatusCode.Conflict, "ILLEGAL_TRANSITION", message)

/**
 * Граф переходов статусов — копия таблицы status_transitions: код отказывает понятной ошибкой до запроса,
 * триггер БД остаётся страховкой. Совпадение графов проверяет ClaimTransitionsTest.
 */
object ClaimTransitions {
    val allowed: Map<Pair<ClaimStatus, ClaimStatus>, Set<ActorType>> = mapOf(
        (DRAFT to SUBMITTED) to setOf(CLIENT),
        (DRAFT to CANCELLED) to setOf(CLIENT),
        (NEEDS_INFO to SUBMITTED) to setOf(CLIENT),
        (SUBMITTED to NEEDS_INFO) to setOf(SYSTEM),
        (SUBMITTED to IN_ANALYSIS) to setOf(SYSTEM),
        (IN_ANALYSIS to NEEDS_INFO) to setOf(SYSTEM),
        (IN_ANALYSIS to QUEUED) to setOf(SYSTEM, ADMIN),
        (QUEUED to ASSIGNED) to setOf(SYSTEM, OPERATOR, ADMIN),
        (ASSIGNED to QUEUED) to setOf(OPERATOR, ADMIN),
        (ASSIGNED to IN_REVIEW) to setOf(OPERATOR),
        (IN_REVIEW to NEEDS_INFO) to setOf(OPERATOR),
        (IN_REVIEW to QUEUED) to setOf(OPERATOR, ADMIN),
        (IN_REVIEW to COMPLETED) to setOf(OPERATOR),
        (NEEDS_INFO to CANCELLED) to setOf(CLIENT, SYSTEM, OPERATOR, ADMIN),
        (QUEUED to CANCELLED) to setOf(OPERATOR, ADMIN),
        (ASSIGNED to CANCELLED) to setOf(OPERATOR, ADMIN),
        (IN_REVIEW to CANCELLED) to setOf(OPERATOR, ADMIN),
        (IN_ANALYSIS to CANCELLED) to setOf(ADMIN),
        (NEEDS_INFO to QUEUED) to setOf(ADMIN),
        (COMPLETED to IN_REVIEW) to setOf(ADMIN),
        (CANCELLED to QUEUED) to setOf(ADMIN),
    )

    /** Для этих переходов причина обязательна (её же требует CHECK в claim_status_history). */
    fun requiresReason(to: ClaimStatus, actor: ActorType) =
        actor == ADMIN || (to == CANCELLED && actor != CLIENT)

    fun check(from: ClaimStatus, to: ClaimStatus, actor: ActorType) {
        val actors = allowed[from to to] ?: throw IllegalTransitionException("Переход $from → $to не предусмотрен")
        if (actor !in actors) throw IllegalTransitionException("$actor не может выполнить переход $from → $to")
    }
}
