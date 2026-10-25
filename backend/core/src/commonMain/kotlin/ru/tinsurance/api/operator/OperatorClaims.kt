package ru.tinsurance.api.operator

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import ru.tinsurance.api.*

@Serializable
data class QueueItem(
    val id: Long,
    val claimNumber: String,
    val status: ClaimStatus,
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
    /** Когда обращение вошло в текущий статус и когда истекает срок статуса. */
    val statusChangedAt: String,
    val dueAt: String?,
    val isOverdue: Boolean,
    val filesCount: Long,
    /** Сколько фото ещё ждут автоматического анализа. */
    val analysesPending: Long,
    val hasDuplicate: Boolean,
    val updatedAt: String,
)

/** Решение системы или оператора; исправление ссылается на исходное через supersedesId. */
@Serializable
data class DecisionDto(
    val id: Long,
    val kind: DecisionKind,
    val source: DecisionSource,
    val action: DecisionAction,
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
    val fromStatus: ClaimStatus?,
    val toStatus: ClaimStatus,
    val actorType: ActorType,
    val actorName: String?,
    /** Внутренняя причина, клиенту не показывается. */
    val reason: String?,
    val clientTitle: String?,
    val clientComment: String?,
    val visibleToClient: Boolean,
    val at: String,
)

@Serializable
data class CommentDto(
    val id: Long,
    val authorName: String,
    val visibility: CommentVisibility,
    val body: String,
    val createdAt: String,
)

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

@Serializable
data class SimilarFileDto(
    val claimId: Long,
    val claimNumber: String,
    val fileId: Long,
    val match: MatchType,
)

@Serializable
data class OperatorFileDto(
    val file: ClaimFileDto,
    val analysisStatus: AnalysisStatus,
    val analysis: ImageAnalysisDto?,
    /** Снимок уже есть в другом обращении — признак для проверки, не решение. */
    val foundInOtherClaim: SimilarFileDto? = null,
)

/** Всё, что оператор видит на одном экране. */
@Serializable
data class ClaimCard(
    val id: Long,
    val claimNumber: String,
    val status: ClaimStatus,
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
    val details: JsonObject,
    val submittedAt: String?,
    val createdAt: String,
    val updatedAt: String,
    val version: Int,
    val client: UserDto,
    val policy: PolicyDto?,
    val participants: List<ParticipantDto>,
    val files: List<OperatorFileDto>,
    val decisions: List<DecisionDto>,
    val history: List<StatusHistoryDto>,
    val comments: List<CommentDto>,
    val infoRequests: List<InfoRequestDto>,
)
