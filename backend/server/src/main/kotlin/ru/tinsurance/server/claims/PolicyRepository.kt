package ru.tinsurance.server.claims

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.jdbc.selectAll
import ru.tinsurance.api.PolicyDto
import ru.tinsurance.server.db.Policies
import java.time.LocalDate

/** Вызывается внутри транзакции. */
object PolicyRepository {

    data class Period(val number: String, val validFrom: LocalDate, val validTo: LocalDate)

    fun active(clientId: Long): List<PolicyDto> =
        Policies.selectAll()
            .where { (Policies.clientId eq clientId) and (Policies.validTo greaterEq LocalDate.now()) }
            .orderBy(Policies.validTo, SortOrder.DESC)
            .map { it.toPolicyDto() }

    fun find(id: Long): PolicyDto? = Policies.selectAll().where { Policies.id eq id }.singleOrNull()?.toPolicyDto()

    fun get(id: Long): Period = Policies.selectAll().where { Policies.id eq id }.single()
        .let { Period(it[Policies.policyNumber], it[Policies.validFrom], it[Policies.validTo]) }

    fun isOwnedBy(id: Long, clientId: Long): Boolean =
        Policies.selectAll().where { (Policies.id eq id) and (Policies.clientId eq clientId) }.any()

    private fun ResultRow.toPolicyDto() = PolicyDto(
        id = this[Policies.id],
        policyNumber = this[Policies.policyNumber],
        product = this[Policies.product],
        insuredObject = this[Policies.insuredObject],
        validFrom = this[Policies.validFrom].toString(),
        validTo = this[Policies.validTo].toString(),
    )
}
