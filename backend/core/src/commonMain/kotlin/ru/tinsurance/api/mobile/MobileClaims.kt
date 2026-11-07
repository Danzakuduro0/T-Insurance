package ru.tinsurance.api.mobile

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import ru.tinsurance.api.*

/** Поле формы по типу события: приложение строит форму по этим правилам. */
@Serializable
data class FormFieldDto(
    val code: String,
    val title: String,
    /** CORE — поле обращения (eventLocation, participants…), DETAIL — ключ в details. */
    val kind: FieldKind,
    val valueType: FieldValueType,
    val required: Boolean,
)

@Serializable
data class FormDocumentDto(
    val documentType: String,
    val name: String,
    val minCount: Int,
    val allowedMimeTypes: List<String>,
    val maxSizeBytes: Long,
)

@Serializable
data class EventTypeDto(
    val code: String,
    val name: String,
    val fields: List<FormFieldDto>,
    val documents: List<FormDocumentDto>,
)

/** Создание черновика: все поля можно заполнить позже. */
@Serializable
data class CreateClaimRequest(
    val policyId: Long? = null,
    val eventType: String? = null,
    val eventAt: String? = null,
    val eventLocation: String? = null,
    val description: String? = null,
    /** Телефон для уведомлений; по умолчанию — телефон клиента. */
    val contactPhone: String? = null,
    val participants: List<ParticipantInput>? = null,
    /** Ключи из GET /event-types. */
    val details: JsonObject? = null,
    /** UUID от приложения: повтор запроса не создаст дубль. */
    val clientRequestId: String? = null,
)

/** null — поле не меняется. После запроса уточнений можно менять только запрошенные поля. */
@Serializable
data class UpdateClaimRequest(
    val policyId: Long? = null,
    val eventType: String? = null,
    val eventAt: String? = null,
    val eventLocation: String? = null,
    val description: String? = null,
    val contactPhone: String? = null,
    /** Заменяет список участников целиком. */
    val participants: List<ParticipantInput>? = null,
    /** Переданные ключи заменяются, остальные сохраняются. */
    val details: JsonObject? = null,
)

@Serializable
data class ClaimListItem(
    val id: Long,
    val claimNumber: String,
    val status: ClaimStatus,
    val statusTitle: String,
    val isFinal: Boolean,
    val eventTypeName: String?,
    val eventAt: String?,
    val createdAt: String,
    val updatedAt: String,
    /** Клиенту нужно дозагрузить материалы. */
    val actionRequired: Boolean,
    val actionMessage: String?,
)

@Serializable
data class ClaimDetails(
    val id: Long,
    val claimNumber: String,
    val status: ClaimStatus,
    val statusTitle: String,
    val isFinal: Boolean,
    val policyId: Long?,
    val policyNumber: String?,
    val eventType: String?,
    val eventTypeName: String?,
    val eventAt: String?,
    val eventLocation: String?,
    val description: String?,
    val contactPhone: String?,
    val participants: List<ParticipantDto>,
    val details: JsonObject,
    val createdAt: String,
    val updatedAt: String,
    val submittedAt: String?,
    val openInfoRequest: InfoRequestDto?,
    val files: List<ClaimFileDto>,
)

/** Событие временной шкалы клиента; внутренние события сюда не попадают. */
@Serializable
data class TimelineEvent(
    val status: ClaimStatus,
    val title: String,
    val comment: String?,
    val at: String,
)

@Serializable
data class MissingFieldDto(val field: String, val title: String)

@Serializable
data class MissingDocumentDto(
    val documentType: String,
    val documentTypeName: String,
    val required: Int,
    val uploaded: Int,
)

@Serializable
data class ProblemFileDto(
    val fileId: Long,
    val documentType: String,
    val issue: MaterialIssue,
    val reason: RejectionReason?,
)

/** Чего не хватает для отправки — тот же расчёт, что при отправке. */
@Serializable
data class CompletenessDto(
    val complete: Boolean,
    val missingFields: List<MissingFieldDto>,
    val missingDocuments: List<MissingDocumentDto>,
    val problemFiles: List<ProblemFileDto>,
)
