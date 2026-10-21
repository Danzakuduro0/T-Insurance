package ru.tinsurance.server.operator

import ru.tinsurance.api.ClaimFileDto
import ru.tinsurance.api.InfoRequestDto
import ru.tinsurance.api.ParticipantDto
import ru.tinsurance.api.UserDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Модели API веб-интерфейса оператора. Веб на React/Angular, поэтому они живут на сервере, а не в core.

@Serializable
data class QueueItem(
    val id: Long,
    val claimNumber: String,
    val status: String,
    val directionCode: String?,
    val directionName: String?,
    val priority: Int?,
    val requiresManualReview: Boolean,
    val eventType: String?,
    val eventTypeName: String?,
    val clientName: String,
    val policyNumber: String?,
    val assignedOperatorId: Long?,
    val assignedOperatorName: String?,
    val submittedAt: String?,
    val createdAt: String,
    /** вопрос 34: когда обращение вошло в текущий статус и когда истекает срок статуса */
    val statusChangedAt: String,
    val dueAt: String?,
    val isOverdue: Boolean,
    val filesCount: Long,
    /** вопрос 51: сколько фото ещё ждут автоматического анализа */
    val analysesPending: Long,
    val hasDuplicate: Boolean,
    val updatedAt: String,
)

@Serializable
data class Page<T>(val items: List<T>, val total: Long, val limit: Int, val offset: Long)

@Serializable
data class ClientInfo(val id: Long, val fullName: String, val phone: String?, val email: String?)

@Serializable
data class PolicyInfo(
    val id: Long,
    val policyNumber: String,
    val product: String,
    val insuredObject: JsonObject,
    val validFrom: String,
    val validTo: String,
)

/** Решение системы или оператора. Исправление ссылается на исходное через supersedesId. */
@Serializable
data class DecisionDto(
    val id: Long,
    val kind: String,
    val source: String,
    /** SET — значение задано, CONFIRM — сотрудник подтвердил, OVERRIDE — исправил (вопросы 36–38) */
    val action: String,
    val value: JsonElement,
    val confidence: Float?,
    val reason: String?,
    val ruleVersion: String?,
    val supersedesId: Long?,
    val isCurrent: Boolean,
    val createdBy: String?,
    val createdAt: String,
)

@Serializable
data class StatusHistoryDto(
    val fromStatus: String?,
    val toStatus: String,
    val actorType: String,
    val actorName: String?,
    val reason: String?,
    /** как событие подписано у клиента (если отличается от названия статуса) */
    val clientTitle: String?,
    /** что видит клиент; reason — внутренний */
    val clientComment: String?,
    val visibleToClient: Boolean,
    val at: String,
)

@Serializable
data class CommentDto(
    val id: Long,
    val authorName: String,
    val visibility: String,
    val body: String,
    val createdAt: String,
)

/** Результат автоматического анализа фото: оператор видит значение, уверенность и проверяемые признаки (вопрос 23). */
@Serializable
data class ImageAnalysisDto(
    val modelVersion: String,
    val qualityOk: Boolean,
    val qualityScore: Float?,
    val qualityIssues: List<String>,
    val damageDetected: Boolean?,
    val damageType: String?,
    val damageConfidence: Float?,
    val carPart: String?,
    val damageArea: JsonElement?,
    val createdAt: String,
)

/** Файл в карточке оператора: данные файла + статус и результат анализа (вопросы 35, 51). */
@Serializable
data class OperatorFileDto(
    val file: ClaimFileDto,
    /** NOT_REQUIRED, PENDING, DONE, FAILED */
    val analysisStatus: String,
    val analysis: ImageAnalysisDto?,
    /** этот снимок уже есть в другом обращении (вопрос 17) — признак для проверки, не решение */
    val foundInOtherClaim: SimilarFileDto? = null,
)

@Serializable
data class SimilarFileDto(
    val claimId: Long,
    val claimNumber: String,
    val fileId: Long,
    /** EXACT — тот же файл байт в байт; SIMILAR — тот же снимок, пересжатый или уменьшенный */
    val match: String,
)

/** Карточка случая — всё, что оператор видит на одном экране. */
@Serializable
data class ClaimCard(
    val id: Long,
    val claimNumber: String,
    val status: String,
    val statusTitle: String,
    val eventType: String?,
    val eventTypeName: String?,
    val eventAt: String?,
    val eventLocation: String?,
    val description: String?,
    val contactPhone: String?,
    val directionCode: String?,
    val priority: Int?,
    val requiresManualReview: Boolean,
    val assignedOperator: UserDto?,
    val duplicateOfClaimId: Long?,
    val statusChangedAt: String,
    val dueAt: String?,
    val isOverdue: Boolean,
    /** дополнительные сведения по типу события (обстоятельства и т.п.) */
    val details: JsonObject,
    val submittedAt: String?,
    val createdAt: String,
    val updatedAt: String,
    val version: Int,
    val client: ClientInfo,
    val policy: PolicyInfo?,
    val participants: List<ParticipantDto>,
    val files: List<OperatorFileDto>,
    val decisions: List<DecisionDto>,
    val history: List<StatusHistoryDto>,
    val comments: List<CommentDto>,
    val infoRequests: List<InfoRequestDto>,
)
