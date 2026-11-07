package ru.tinsurance.server.operator

import org.jetbrains.exposed.v1.core.ResultRow
import ru.tinsurance.api.ActorType
import ru.tinsurance.api.ClaimStatus
import ru.tinsurance.server.claims.Actor
import ru.tinsurance.server.common.ConflictException
import ru.tinsurance.server.common.ForbiddenException
import ru.tinsurance.server.db.Claims

/** Кто из сотрудников может менять обращение. */
object ClaimWorkAccess {
    private val IN_WORK = setOf(ClaimStatus.ASSIGNED, ClaimStatus.IN_REVIEW)
    private val ADMIN_EDITABLE = IN_WORK + ClaimStatus.QUEUED

    /** Оператор — только назначенное ему обращение в работе; администратор — также обращения в очереди. */
    fun requireCanEdit(claim: ResultRow, actor: Actor) {
        val status = claim[Claims.status]
        when (actor.type) {
            ActorType.ADMIN ->
                if (status !in ADMIN_EDITABLE) throw ConflictException("Обращение в статусе $status изменить нельзя")
            ActorType.OPERATOR -> {
                if (status !in IN_WORK || claim[Claims.assignedOperatorId] != actor.id) {
                    throw ForbiddenException("Изменять обращение может оператор, которому оно назначено")
                }
            }
            else -> throw ForbiddenException("Недостаточно прав")
        }
    }
}
