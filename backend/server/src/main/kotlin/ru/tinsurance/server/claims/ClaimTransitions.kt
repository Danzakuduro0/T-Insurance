package ru.tinsurance.server.claims

import ru.tinsurance.api.ClaimStatus
import ru.tinsurance.api.ClaimStatus.*
import ru.tinsurance.server.claims.ActorType.*
import ru.tinsurance.server.common.ApiException
import io.ktor.http.*

class IllegalTransitionException(message: String) : ApiException(HttpStatusCode.Conflict, "ILLEGAL_TRANSITION", message)

/**
 * Граф переходов статусов. Копия таблицы status_transitions (миграции V2, V4):
 * код отказывает с понятной ошибкой до запроса в БД, а триггер БД остаётся страховкой.
 * Совпадение двух графов проверяет тест ClaimTransitionsTest — меняя одно, меняйте и другое.
 * Статусы и переходы — по ответам на вопросы 31–33.
 */
object ClaimTransitions {
    val allowed: Map<Pair<ClaimStatus, ClaimStatus>, Set<ActorType>> = mapOf(
        // клиент
        (DRAFT to SUBMITTED) to setOf(CLIENT),
        (DRAFT to CANCELLED) to setOf(CLIENT),
        (NEEDS_INFO to SUBMITTED) to setOf(CLIENT),
        // система: проверка комплектности, анализ, очередь
        (SUBMITTED to NEEDS_INFO) to setOf(SYSTEM),
        (SUBMITTED to IN_ANALYSIS) to setOf(SYSTEM),
        (IN_ANALYSIS to NEEDS_INFO) to setOf(SYSTEM),
        (IN_ANALYSIS to QUEUED) to setOf(SYSTEM, ADMIN),
        // назначение и рассмотрение
        (QUEUED to ASSIGNED) to setOf(SYSTEM, OPERATOR, ADMIN),
        (ASSIGNED to QUEUED) to setOf(OPERATOR, ADMIN),
        (ASSIGNED to IN_REVIEW) to setOf(OPERATOR),
        (IN_REVIEW to NEEDS_INFO) to setOf(OPERATOR),
        (IN_REVIEW to QUEUED) to setOf(OPERATOR, ADMIN),
        (IN_REVIEW to COMPLETED) to setOf(OPERATOR),
        // закрытие без обработки (вопросы 31, 32) — сотрудником только с причиной
        (NEEDS_INFO to CANCELLED) to setOf(CLIENT, SYSTEM, OPERATOR, ADMIN),
        (QUEUED to CANCELLED) to setOf(OPERATOR, ADMIN),
        (ASSIGNED to CANCELLED) to setOf(OPERATOR, ADMIN),
        (IN_REVIEW to CANCELLED) to setOf(OPERATOR, ADMIN),
        (IN_ANALYSIS to CANCELLED) to setOf(ADMIN),
        // исправление ошибочного состояния администратором (вопрос 32) — только с причиной
        (NEEDS_INFO to QUEUED) to setOf(ADMIN),
        (COMPLETED to IN_REVIEW) to setOf(ADMIN),
        (CANCELLED to QUEUED) to setOf(ADMIN),
    )

    /** Переходы, для которых обязательна причина (её же требует CHECK в claim_status_history). */
    fun requiresReason(to: ClaimStatus, actor: ActorType) =
        actor == ADMIN || (to == CANCELLED && actor != CLIENT)

    fun check(from: ClaimStatus, to: ClaimStatus, actor: ActorType) {
        val actors = allowed[from to to] ?: throw IllegalTransitionException("Переход $from → $to не предусмотрен")
        if (actor !in actors) throw IllegalTransitionException("$actor не может выполнить переход $from → $to")
    }
}
