package com.tinsurance.app.client

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.tinsurance.api.ClaimField
import ru.tinsurance.api.ClaimFileDto
import ru.tinsurance.api.mobile.UpdateClaimRequest

data class ReplyState(
    val uploading: Boolean = false,
    val sending: Boolean = false,
    val uploads: List<UploadedFile> = emptyList(),
    val error: String? = null,
)

data class UploadedFile(
    val name: String,
    val documentTypeName: String,
    val result: ClaimFileDto,
)

// Полис, тип события, дата и участники требуют отдельных элементов ввода.
fun isTextField(name: String): Boolean =
    name !in listOf(
        ClaimField.POLICY.apiName,
        ClaimField.EVENT_TYPE.apiName,
        ClaimField.EVENT_AT.apiName,
        ClaimField.PARTICIPANTS,
    )

fun fieldsUpdate(fields: Map<String, String>): UpdateClaimRequest {
    val details = fields.filterKeys { name ->
        isTextField(name) && ClaimField.entries.none { it.apiName == name }
    }
    return UpdateClaimRequest(
        description = fields[ClaimField.DESCRIPTION.apiName],
        eventLocation = fields[ClaimField.EVENT_LOCATION.apiName],
        contactPhone = fields[ClaimField.CONTACT_PHONE.apiName],
        details = details.takeIf { it.isNotEmpty() }?.let {
            JsonObject(it.mapValues { (_, value) -> JsonPrimitive(value) })
        },
    )
}
