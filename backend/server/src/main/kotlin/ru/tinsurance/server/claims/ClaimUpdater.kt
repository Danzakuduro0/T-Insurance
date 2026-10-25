package ru.tinsurance.server.claims

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.ResultRow
import ru.tinsurance.api.ClaimField
import ru.tinsurance.api.ClaimStatus
import ru.tinsurance.server.common.ConflictException
import ru.tinsurance.server.common.iso
import ru.tinsurance.server.db.Claims
import java.time.OffsetDateTime

/**
 * Изменение обращения клиентом. В черновике — любые поля; после запроса уточнений — только запрошенные,
 * и каждое изменение пишется в журнал со старым и новым значением. Вызывается внутри транзакции.
 */
object ClaimUpdater {

    fun update(clientId: Long, claimId: Long, input: ClaimInput) {
        val claim = ClaimRepository.findOwned(clientId, claimId, forUpdate = true)
        val status = claim[Claims.status]
        if (status != ClaimStatus.DRAFT && status != ClaimStatus.NEEDS_INFO) {
            throw ConflictException("После отправки обращение можно только дополнить по запросу")
        }
        ClaimInputValidator.checkReferences(clientId, input)

        val newValues = input.changes()
        if (newValues.isEmpty()) return
        if (status == ClaimStatus.NEEDS_INFO) checkRequested(claimId, newValues.keys)

        val mergedDetails = input.details?.let { JsonObject(claim[Claims.eventDetails] + it) }
        mergedDetails?.let { ClaimInputValidator.validateDetails(input.eventType ?: claim[Claims.eventType], it) }
        val oldValues = oldValues(claim, newValues.keys)

        ClaimRepository.update(claimId, input, mergedDetails)
        input.participants?.let { ParticipantRepository.replace(claimId, it) }
        AuditWriter.record(
            claimId, AuditEntity.CLAIM, claimId, "UPDATE", Actor.client(clientId),
            newValue = newValues, oldValue = oldValues,
            comment = if (status == ClaimStatus.NEEDS_INFO) "Уточнение по запросу" else null,
            at = OffsetDateTime.now(),
        )
    }

    private fun checkRequested(claimId: Long, changed: Set<String>) {
        val requested = InfoRequestRepository.requestedFields(claimId)
        val notRequested = changed - requested
        if (notRequested.isNotEmpty()) {
            throw ConflictException(
                "После отправки можно изменить только запрошенные поля" +
                    (if (requested.isEmpty()) "" else ": ${requested.joinToString()}") +
                    ". Не запрошены: ${notRequested.joinToString()}"
            )
        }
    }

    private fun oldValues(claim: ResultRow, keys: Set<String>) = buildJsonObject {
        keys.forEach { key ->
            when (key) {
                ClaimField.POLICY.apiName -> put(key, claim[Claims.policyId])
                ClaimField.EVENT_TYPE.apiName -> put(key, claim[Claims.eventType])
                ClaimField.EVENT_AT.apiName -> put(key, claim[Claims.eventAt]?.iso())
                ClaimField.EVENT_LOCATION.apiName -> put(key, claim[Claims.eventLocation])
                ClaimField.DESCRIPTION.apiName -> put(key, claim[Claims.description])
                ClaimField.CONTACT_PHONE.apiName -> put(key, claim[Claims.contactPhone])
                ClaimField.PARTICIPANTS -> put(key, JsonArray(ParticipantRepository.list(claim[Claims.id]).map { p ->
                    buildJsonObject { put("role", p.role); put("fullName", p.fullName) }
                }))
                else -> put(key, claim[Claims.eventDetails][key] ?: JsonNull)
            }
        }
    }
}
