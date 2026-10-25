package ru.tinsurance.server.assignment

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.select
import ru.tinsurance.api.ClaimStatus
import ru.tinsurance.api.OperatorAvailability
import ru.tinsurance.server.claims.Actor
import ru.tinsurance.server.claims.ClaimRepository
import ru.tinsurance.server.db.Claims

/**
 * Автоназначение: обращение из очереди получает оператор направления на смене с наименьшей нагрузкой;
 * вернувшееся после дозагрузки — прежний оператор, если он на смене. Вызывается внутри транзакции.
 */
class AutoAssigner(private val operators: OperatorRepository, private val enabled: Boolean) {

    /** Назначить обращение из очереди; [exclude] — оператор, который только что его вернул. */
    fun assignQueued(claimId: Long, exclude: Long? = null): Long? {
        if (!enabled) return null
        val claim = ClaimRepository.findForUpdate(claimId) ?: return null
        val direction = claim[Claims.directionCode]
        if (claim[Claims.status] != ClaimStatus.QUEUED || direction == null) return null

        val previous = ClaimAssigner.previousOperator(claimId)
        val candidates = (listOfNotNull(previous) + operators.onlineWithCapacity(direction).map { it.id })
            .distinct()
            .filter { it != exclude }
        val operatorId = candidates.firstOrNull { canTakeMore(it) } ?: return null
        val reason = if (operatorId == previous) "Возврат прежнему оператору" else "Автоназначение: наименьшая нагрузка"
        ClaimAssigner.assign(claimId, operatorId, Actor.SYSTEM, reason)
        return operatorId
    }

    /** Оператор вышел на смену или освободился — назначить ему обращения из очереди его направлений. */
    fun fillOperator(operatorId: Long): Int {
        if (!enabled) return 0
        val operator = operators.lockActive(operatorId) ?: return 0
        if (operator.availability != OperatorAvailability.ONLINE || !operator.hasCapacity) return 0
        val directions = operators.directions(operatorId)
        if (directions.isEmpty()) return 0

        val claims = Claims.select(Claims.id)
            .where {
                (Claims.status eq ClaimStatus.QUEUED) and Claims.assignedOperatorId.isNull() and
                    (Claims.directionCode inList directions)
            }
            .orderBy(Claims.priority to SortOrder.DESC_NULLS_LAST, Claims.statusChangedAt to SortOrder.ASC)
            .limit(operator.maxActiveClaims - operator.activeClaims)
            .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
            .map { it[Claims.id] }
        claims.forEach { ClaimAssigner.assign(it, operatorId, Actor.SYSTEM, "Автоназначение: оператор вышел на смену") }
        return claims.size
    }

    /** Периодическая проверка: обращения, которые никому не достались, и освободившиеся операторы. */
    fun sweep(): Int = if (!enabled) 0 else operators.online().sumOf { fillOperator(it) }

    private fun canTakeMore(operatorId: Long): Boolean {
        val operator = operators.lockActive(operatorId) ?: return false
        return operator.availability == OperatorAvailability.ONLINE && operator.hasCapacity
    }
}
