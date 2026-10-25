package ru.tinsurance.server.assignment

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.jdbc.Database
import ru.tinsurance.api.ActorType
import ru.tinsurance.api.ClaimStatus
import ru.tinsurance.api.UserDto
import ru.tinsurance.api.operator.*
import ru.tinsurance.server.claims.Actor
import ru.tinsurance.server.claims.ClaimRepository
import ru.tinsurance.server.claims.ClaimStatusService
import ru.tinsurance.server.common.ConflictException
import ru.tinsurance.server.common.ForbiddenException
import ru.tinsurance.server.common.NotFoundException
import ru.tinsurance.server.common.ValidationException
import ru.tinsurance.server.common.tx
import ru.tinsurance.server.db.Claims
import ru.tinsurance.server.operator.ClaimCardQueries
import ru.tinsurance.server.users.UserRepository

/**
 * Работа оператора с назначением: смена, «Взять в работу», начало рассмотрения, возврат в очередь;
 * администратор назначает и переназначает. Двойной захват исключён блокировкой строки обращения.
 */
class AssignmentService(
    private val db: Database,
    private val operators: OperatorRepository,
    private val autoAssigner: AutoAssigner,
) {

    suspend fun operators(actor: Actor): List<OperatorDto> = db.tx {
        requireAdmin(actor)
        operators.list()
    }

    /** На смене (ONLINE) оператор сразу получает обращения из очереди своих направлений. */
    suspend fun setAvailability(actor: Actor, request: AvailabilityRequest): UserDto = db.tx {
        val operatorId = requireOperator(actor)
        operators.setAvailability(operatorId, request.availability)
        autoAssigner.fillOperator(operatorId)
        UserRepository.get(operatorId)
    }

    suspend fun take(actor: Actor, claimId: Long): ClaimCard = db.tx {
        val operatorId = requireOperator(actor)
        val claim = lockClaim(claimId)
        when (claim[Claims.status]) {
            ClaimStatus.QUEUED -> Unit
            ClaimStatus.ASSIGNED, ClaimStatus.IN_REVIEW -> throw ConflictException("Обращение уже взял другой оператор")
            else -> throw ConflictException("Обращение не в очереди")
        }
        claim[Claims.directionCode]?.let { direction ->
            if (direction !in operators.directions(operatorId)) {
                throw ForbiddenException("Обращения направления $direction рассматривают операторы этого направления")
            }
        }
        val load = operators.lockActive(operatorId) ?: throw ForbiddenException("Недостаточно прав")
        if (!load.hasCapacity) throw ConflictException("Достигнут лимит обращений в работе: ${load.maxActiveClaims}")
        ClaimAssigner.assign(claimId, operatorId, actor, reason = null)
        ClaimCardQueries.card(claimId)
    }

    suspend fun startReview(actor: Actor, claimId: Long): ClaimCard = db.tx {
        val operatorId = requireOperator(actor)
        val claim = lockClaim(claimId)
        if (claim[Claims.status] != ClaimStatus.ASSIGNED) throw ConflictException("Начать рассмотрение можно только назначенного обращения")
        if (claim[Claims.assignedOperatorId] != operatorId) throw ForbiddenException("Обращение назначено другому оператору")
        ClaimStatusService.transition(claimId, ClaimStatus.ASSIGNED, ClaimStatus.IN_REVIEW, actor)
        ClaimCardQueries.card(claimId)
    }

    /** Возврат в очередь: обращение сразу предлагается другому оператору на смене. */
    suspend fun release(actor: Actor, claimId: Long, request: ReleaseClaimRequest): ClaimCard = db.tx {
        val reason = requireReason(request.reason)
        val claim = lockClaim(claimId)
        val status = claim[Claims.status]
        if (status !in OperatorRepository.IN_WORK) throw ConflictException("Обращение не в работе")
        val assignee = claim[Claims.assignedOperatorId]
        if (actor.type != ActorType.ADMIN && assignee != actor.id) throw ForbiddenException("Обращение назначено другому оператору")

        ClaimAssigner.release(claimId, status, assignee, actor, reason)
        autoAssigner.assignQueued(claimId, exclude = assignee)
        ClaimCardQueries.card(claimId)
    }

    /** Администратор: из очереди — назначить, из работы — передать другому оператору. */
    suspend fun assign(actor: Actor, claimId: Long, request: AssignClaimRequest): ClaimCard = db.tx {
        requireAdmin(actor)
        val reason = requireReason(request.reason)
        val claim = lockClaim(claimId)
        operators.lockActive(request.operatorId) ?: throw ValidationException("Оператор не найден")
        when (claim[Claims.status]) {
            ClaimStatus.QUEUED -> ClaimAssigner.assign(claimId, request.operatorId, actor, reason)
            ClaimStatus.ASSIGNED, ClaimStatus.IN_REVIEW -> {
                val current = claim[Claims.assignedOperatorId]
                if (current == request.operatorId) throw ValidationException("Обращение уже назначено этому оператору")
                ClaimAssigner.reassign(claimId, current, request.operatorId, actor, reason)
            }
            else -> throw ConflictException("Назначить можно обращение из очереди или в работе")
        }
        ClaimCardQueries.card(claimId)
    }

    private fun lockClaim(claimId: Long): ResultRow =
        ClaimRepository.findForUpdate(claimId)?.takeIf { it[Claims.status] != ClaimStatus.DRAFT }
            ?: throw NotFoundException("Обращение не найдено")

    private fun requireOperator(actor: Actor): Long {
        if (actor.type != ActorType.OPERATOR) throw ForbiddenException("Действие доступно оператору")
        return actor.id!!
    }

    private fun requireAdmin(actor: Actor) {
        if (actor.type != ActorType.ADMIN) throw ForbiddenException("Действие доступно администратору")
    }

    private fun requireReason(reason: String): String =
        reason.trim().takeIf { it.isNotEmpty() } ?: throw ValidationException("Укажите причину — она попадёт в журнал")
}
