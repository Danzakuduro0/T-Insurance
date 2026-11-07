package ru.tinsurance.server.assignment

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import ru.tinsurance.api.ClaimStatus
import ru.tinsurance.api.OperatorAvailability
import ru.tinsurance.api.Role
import ru.tinsurance.api.operator.OperatorDto
import ru.tinsurance.server.db.Claims
import ru.tinsurance.server.db.OperatorDirections
import ru.tinsurance.server.db.Users

/** Оператор с текущей нагрузкой. */
data class OperatorLoad(val id: Long, val availability: OperatorAvailability?, val activeClaims: Int, val maxActiveClaims: Int) {
    val hasCapacity get() = activeClaims < maxActiveClaims
}

/** Операторы, их направления и нагрузка. Вызывается внутри транзакции. */
class OperatorRepository(private val defaultMaxActiveClaims: Int) {

    /** Блокирует строку оператора: параллельные назначения ему считают нагрузку по очереди. */
    fun lockActive(operatorId: Long): OperatorLoad? =
        Users.selectAll()
            .where { (Users.id eq operatorId) and (Users.role eq Role.OPERATOR) and (Users.isActive eq true) }
            .forUpdate()
            .singleOrNull()
            ?.let { load(it, activeClaims(listOf(operatorId))[operatorId] ?: 0) }

    /** Операторы направления на смене со свободными местами — сначала наименее загруженные. */
    fun onlineWithCapacity(direction: String): List<OperatorLoad> {
        val rows = Users.join(OperatorDirections, JoinType.INNER, Users.id, OperatorDirections.operatorId)
            .select(Users.id, Users.availability, Users.maxActiveClaims)
            .where {
                (Users.role eq Role.OPERATOR) and (Users.isActive eq true) and
                    (Users.availability eq OperatorAvailability.ONLINE) and (OperatorDirections.directionCode eq direction)
            }
            .toList()
        val active = activeClaims(rows.map { it[Users.id] })
        return rows.map { load(it, active[it[Users.id]] ?: 0) }
            .filter { it.hasCapacity }
            .sortedWith(compareBy({ it.activeClaims }, { it.id }))
    }

    fun online(): List<Long> =
        Users.select(Users.id)
            .where { (Users.role eq Role.OPERATOR) and (Users.isActive eq true) and (Users.availability eq OperatorAvailability.ONLINE) }
            .map { it[Users.id] }

    fun directions(operatorId: Long): List<String> =
        OperatorDirections.select(OperatorDirections.directionCode)
            .where { OperatorDirections.operatorId eq operatorId }
            .map { it[OperatorDirections.directionCode] }

    fun setAvailability(operatorId: Long, availability: OperatorAvailability) {
        Users.update({ Users.id eq operatorId }) { it[Users.availability] = availability }
    }

    fun list(): List<OperatorDto> {
        val rows = Users.selectAll()
            .where { (Users.role eq Role.OPERATOR) and (Users.isActive eq true) }
            .orderBy(Users.fullName)
            .toList()
        val ids = rows.map { it[Users.id] }
        val active = activeClaims(ids)
        val directions = OperatorDirections.selectAll().where { OperatorDirections.operatorId inList ids }
            .groupBy({ it[OperatorDirections.operatorId] }) { it[OperatorDirections.directionCode] }
        return rows.map {
            val load = load(it, active[it[Users.id]] ?: 0)
            OperatorDto(load.id, it[Users.fullName], load.availability, directions[load.id].orEmpty(), load.activeClaims, load.maxActiveClaims)
        }
    }

    private fun activeClaims(operatorIds: List<Long>): Map<Long, Int> =
        if (operatorIds.isEmpty()) emptyMap()
        else Claims.select(Claims.assignedOperatorId, Claims.id.count())
            .where { (Claims.assignedOperatorId inList operatorIds) and (Claims.status inList IN_WORK) }
            .groupBy(Claims.assignedOperatorId)
            .associate { it[Claims.assignedOperatorId]!! to it[Claims.id.count()].toInt() }

    private fun load(row: ResultRow, active: Int) = OperatorLoad(
        id = row[Users.id],
        availability = row[Users.availability],
        activeClaims = active,
        maxActiveClaims = row[Users.maxActiveClaims]?.toInt() ?: defaultMaxActiveClaims,
    )

    companion object {
        val IN_WORK = listOf(ClaimStatus.ASSIGNED, ClaimStatus.IN_REVIEW)
    }
}
