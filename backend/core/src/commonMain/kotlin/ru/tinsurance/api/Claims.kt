package ru.tinsurance.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Модели, общие для приложения клиента и веба оператора.

@Serializable
data class PolicyDto(
    val id: Long,
    val policyNumber: String,
    val product: String,
    /** Застрахованный объект: марка, модель, год, VIN. */
    val insuredObject: JsonObject,
    val validFrom: String,
    val validTo: String,
)

/** role: APPLICANT_DRIVER, OTHER_DRIVER, VICTIM, WITNESS. */
@Serializable
data class ParticipantInput(
    val role: String,
    val fullName: String,
    val phone: String? = null,
    val vehicle: JsonObject? = null,
    val insurer: String? = null,
    val note: String? = null,
)

@Serializable
data class ParticipantDto(
    val id: Long,
    val role: String,
    val fullName: String,
    val phone: String?,
    val vehicle: JsonObject?,
    val insurer: String?,
    val note: String?,
)

@Serializable
data class ClaimFileDto(
    val id: Long,
    val documentType: String,
    val documentTypeName: String,
    val originalName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val status: FileStatus,
    val rejectionReason: RejectionReason?,
    val uploadedAt: String,
    /** Что сделать, если файл не принят. */
    val hint: String? = null,
    /** Для DUPLICATE — такой же файл, уже загруженный в это обращение. */
    val duplicateOfFileId: Long? = null,
)

@Serializable
data class InfoRequestItemDto(
    val documentType: String?,
    val documentTypeName: String?,
    val fieldName: String?,
    val minCount: Int,
    val comment: String?,
    val issue: MaterialIssue,
    val hint: String?,
    /** Какой файл заменить. */
    val fileId: Long?,
    val isFulfilled: Boolean,
)

@Serializable
data class InfoRequestDto(
    val id: Long,
    val message: String,
    val status: InfoRequestStatus,
    val dueAt: String?,
    val createdAt: String,
    val items: List<InfoRequestItemDto>,
)
