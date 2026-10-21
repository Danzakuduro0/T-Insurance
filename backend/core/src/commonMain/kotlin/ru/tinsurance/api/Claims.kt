package ru.tinsurance.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Совпадает с кодами в таблице claim_statuses. */
@Serializable
enum class ClaimStatus {
    DRAFT, SUBMITTED, NEEDS_INFO, IN_ANALYSIS, QUEUED, ASSIGNED, IN_REVIEW, COMPLETED, CANCELLED
}

@Serializable
data class PolicyDto(
    val id: Long,
    val policyNumber: String,
    val product: String,
    /** Застрахованный объект (вымышленный): марка, модель, год, VIN. */
    val insuredObject: JsonObject,
    val validFrom: String,
    val validTo: String,
)

/** Создание черновика. Все поля можно заполнить позже. */
@Serializable
data class CreateClaimRequest(
    val policyId: Long? = null,
    val eventType: String? = null,
    val eventAt: String? = null,
    val eventLocation: String? = null,
    val description: String? = null,
    /** Телефон для уведомлений; если не указан — берётся телефон клиента. */
    val contactPhone: String? = null,
    /** Участники происшествия (для ДТП обязательны, вопрос 6). */
    val participants: List<ParticipantInput>? = null,
    /** Дополнительные сведения по типу события — ключи из GET /api/mobile/event-types (вопрос 7). */
    val details: JsonObject? = null,
    /** UUID, сгенерированный приложением: повтор запроса при плохой сети не создаст дубль. */
    val clientRequestId: String? = null,
)

/** Роль участника: APPLICANT_DRIVER, OTHER_DRIVER, VICTIM, WITNESS. Данные вымышленные. */
@Serializable
data class ParticipantInput(
    val role: String,
    val fullName: String,
    val phone: String? = null,
    /** марка, модель, госномер (вымышленный) */
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

/** Поле формы для типа события — приложение строит форму по нему, а не зашивает поля в экран (вопрос 7). */
@Serializable
data class FormFieldDto(
    val code: String,
    val title: String,
    /** CORE — поле обращения (eventLocation, participants…), DETAIL — ключ в details */
    val kind: String,
    /** STRING, BOOLEAN, NUMBER, LIST */
    val valueType: String,
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

/** Тип события с правилами: какие поля и документы нужны. */
@Serializable
data class EventTypeDto(
    val code: String,
    val name: String,
    val fields: List<FormFieldDto>,
    val documents: List<FormDocumentDto>,
)

/** Строка списка «Мои обращения». */
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
    /** Клиенту нужно что-то сделать — дозагрузить материалы. */
    val actionRequired: Boolean,
    val actionMessage: String?,
)

@Serializable
data class InfoRequestItemDto(
    val documentType: String?,
    val documentTypeName: String?,
    val fieldName: String?,
    val minCount: Int,
    val comment: String?,
    /** MISSING — нет файла/поля, INVALID_FORMAT — файл отклонён, LOW_QUALITY — фото непригодно (вопрос 15) */
    val issue: String,
    /** как исправить, например «увеличьте освещение» (вопрос 22) */
    val hint: String?,
    /** какой файл заменить (вопрос 16) */
    val fileId: Long?,
    val isFulfilled: Boolean,
)

@Serializable
data class InfoRequestDto(
    val id: Long,
    val message: String,
    val status: String,
    val dueAt: String?,
    val createdAt: String,
    val items: List<InfoRequestItemDto>,
)

@Serializable
data class ClaimFileDto(
    val id: Long,
    val documentType: String,
    val documentTypeName: String,
    val originalName: String,
    val mimeType: String,
    val sizeBytes: Long,
    /** ACCEPTED, REJECTED, DUPLICATE, REPLACED */
    val status: String,
    /** UNSUPPORTED_FORMAT, CORRUPTED, TOO_MANY_PIXELS, ACTIVE_CONTENT, LOW_QUALITY */
    val rejectionReason: String?,
    val uploadedAt: String,
    /** что сделать, если файл не принят (вопрос 22) */
    val hint: String? = null,
    /** для DUPLICATE — такой же файл, уже загруженный в это обращение */
    val duplicateOfFileId: Long? = null,
)

/** Экран обращения в мобильном приложении. */
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

/** Событие временной шкалы для клиента (внутренние события сюда не попадают). */
@Serializable
data class TimelineEvent(
    val status: ClaimStatus,
    val title: String,
    val comment: String?,
    val at: String,
)

/**
 * Поля обращения, которые могут быть обязательными для отправки.
 * apiName совпадает с именем поля в JSON — приложение может подсветить его на форме.
 */
@Serializable
enum class ClaimField(val apiName: String, val title: String) {
    POLICY("policyId", "Полис"),
    EVENT_TYPE("eventType", "Тип события"),
    EVENT_AT("eventAt", "Дата и время события"),
    EVENT_LOCATION("eventLocation", "Место события"),
    DESCRIPTION("description", "Описание события"),
    CONTACT_PHONE("contactPhone", "Контактный телефон");

    companion object {
        fun byApiName(name: String): ClaimField? = entries.firstOrNull { it.apiName == name }
    }
}

/**
 * Изменение обращения. null — поле не меняется.
 * В черновике можно менять всё; после запроса уточнений — только запрошенные поля (вопрос 10).
 */
@Serializable
data class UpdateClaimRequest(
    val policyId: Long? = null,
    val eventType: String? = null,
    val eventAt: String? = null,
    val eventLocation: String? = null,
    val description: String? = null,
    val contactPhone: String? = null,
    /** заменяет список участников целиком */
    val participants: List<ParticipantInput>? = null,
    /** переданные ключи заменяются, остальные сохраняются */
    val details: JsonObject? = null,
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

/** Чего не хватает для отправки. Тот же расчёт выполняется при отправке. */
@Serializable
data class ProblemFileDto(
    val fileId: Long,
    val documentType: String,
    /** INVALID_FORMAT или LOW_QUALITY */
    val issue: String,
    val reason: String?,
)

@Serializable
data class CompletenessDto(
    val complete: Boolean,
    val missingFields: List<MissingFieldDto>,
    val missingDocuments: List<MissingDocumentDto>,
    /** отклонённые файлы, которые нужно заменить */
    val problemFiles: List<ProblemFileDto>,
)
