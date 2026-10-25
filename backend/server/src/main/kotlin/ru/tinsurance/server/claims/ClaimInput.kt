package ru.tinsurance.server.claims

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject
import ru.tinsurance.api.ClaimField
import ru.tinsurance.api.ParticipantInput
import ru.tinsurance.api.mobile.CreateClaimRequest
import ru.tinsurance.api.mobile.UpdateClaimRequest
import ru.tinsurance.server.common.iso
import ru.tinsurance.server.common.normalizePhone
import ru.tinsurance.server.common.parseDateTime
import java.time.OffsetDateTime

/** Поля обращения от клиента — общие для создания и изменения. null — не задано. */
data class ClaimInput(
    val policyId: Long? = null,
    val eventType: String? = null,
    val eventAt: OffsetDateTime? = null,
    val eventLocation: String? = null,
    val description: String? = null,
    val contactPhone: String? = null,
    val participants: List<ParticipantInput>? = null,
    val details: JsonObject? = null,
) {
    fun normalized() = copy(
        eventLocation = eventLocation?.trim(),
        description = description?.trim(),
        contactPhone = contactPhone?.let(::normalizePhone),
    )

    /** Изменённые поля в виде JSON для журнала; ключи совпадают с именами полей API. */
    fun changes(): JsonObject = buildJsonObject {
        policyId?.let { put(ClaimField.POLICY.apiName, it) }
        eventType?.let { put(ClaimField.EVENT_TYPE.apiName, it) }
        eventAt?.let { put(ClaimField.EVENT_AT.apiName, it.iso()) }
        eventLocation?.let { put(ClaimField.EVENT_LOCATION.apiName, it) }
        description?.let { put(ClaimField.DESCRIPTION.apiName, it) }
        contactPhone?.let { put(ClaimField.CONTACT_PHONE.apiName, it) }
        participants?.let { list ->
            putJsonArray(ClaimField.PARTICIPANTS) {
                list.forEach { p -> addJsonObject { put("role", p.role); put("fullName", p.fullName.trim()) } }
            }
        }
        details?.forEach { (key, value) -> put(key, value) }
    }

    companion object {
        fun from(r: CreateClaimRequest) = ClaimInput(
            r.policyId, r.eventType, r.eventAt?.let { parseDateTime(it, "eventAt") },
            r.eventLocation, r.description, r.contactPhone, r.participants, r.details,
        )

        fun from(r: UpdateClaimRequest) = ClaimInput(
            r.policyId, r.eventType, r.eventAt?.let { parseDateTime(it, "eventAt") },
            r.eventLocation, r.description, r.contactPhone, r.participants, r.details,
        )
    }
}
