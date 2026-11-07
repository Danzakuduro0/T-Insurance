package ru.tinsurance.server.claims

import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import ru.tinsurance.api.ClaimStatus
import ru.tinsurance.server.common.NotFoundException
import ru.tinsurance.server.db.Claims
import ru.tinsurance.server.db.EventTypes
import java.time.OffsetDateTime
import kotlin.uuid.Uuid

/** Вызывается внутри транзакции. */
object ClaimRepository {

    /** Обращение клиента; чужое — 404, как несуществующее. */
    fun findOwned(clientId: Long, claimId: Long, forUpdate: Boolean = false): ResultRow {
        val query = Claims.selectAll().where { (Claims.id eq claimId) and (Claims.clientId eq clientId) }
        return (if (forUpdate) query.forUpdate() else query).singleOrNull()
            ?: throw NotFoundException("Обращение не найдено")
    }

    fun findForUpdate(claimId: Long): ResultRow? =
        Claims.selectAll().where { Claims.id eq claimId }.forUpdate().singleOrNull()

    fun isOwnedBy(claimId: Long, clientId: Long): Boolean =
        Claims.selectAll().where { (Claims.id eq claimId) and (Claims.clientId eq clientId) }.any()

    /** Черновики оператору не видны. */
    fun isVisibleToOperator(claimId: Long): Boolean =
        Claims.selectAll().where { (Claims.id eq claimId) and (Claims.status neq ClaimStatus.DRAFT) }.any()

    fun status(claimId: Long): ClaimStatus =
        Claims.select(Claims.status).where { Claims.id eq claimId }.single()[Claims.status]

    fun findByRequestId(requestId: Uuid): ResultRow? =
        Claims.selectAll().where { Claims.clientRequestId eq requestId }.singleOrNull()

    fun eventTypeExists(code: String): Boolean =
        !EventTypes.selectAll().where { EventTypes.code eq code }.empty()

    /** [input] уже проверен и нормализован. */
    fun insertDraft(clientId: Long, input: ClaimInput, requestId: Uuid?, at: OffsetDateTime): Long =
        Claims.insert {
            it[Claims.clientId] = clientId
            it[policyId] = input.policyId
            it[eventType] = input.eventType
            it[eventAt] = input.eventAt
            it[eventLocation] = input.eventLocation
            it[description] = input.description
            it[contactPhone] = input.contactPhone
            it[eventDetails] = input.details ?: JsonObject(emptyMap())
            it[status] = ClaimStatus.DRAFT
            it[statusChangedAt] = at
            it[requiresManualReview] = false
            it[clientRequestId] = requestId
            it[version] = 0
            it[createdAt] = at
            it[updatedAt] = at
        }[Claims.id]

    /** Меняет только заданные поля; [details] — уже объединённые с прежними. */
    fun update(claimId: Long, input: ClaimInput, details: JsonObject?) {
        Claims.update({ Claims.id eq claimId }) {
            input.policyId?.let { v -> it[policyId] = v }
            input.eventType?.let { v -> it[eventType] = v }
            input.eventAt?.let { v -> it[eventAt] = v }
            input.eventLocation?.let { v -> it[eventLocation] = v }
            input.description?.let { v -> it[description] = v }
            input.contactPhone?.let { v -> it[contactPhone] = v }
            details?.let { v -> it[eventDetails] = v }
            it[version] = Claims.version + 1
        }
    }

    fun markDuplicateOf(claimId: Long, otherClaimId: Long) {
        Claims.update({ (Claims.id eq claimId) and Claims.duplicateOfClaimId.isNull() }) {
            it[duplicateOfClaimId] = otherClaimId
        }
    }
}
