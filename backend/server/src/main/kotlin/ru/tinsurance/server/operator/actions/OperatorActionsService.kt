package ru.tinsurance.server.operator.actions

import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.jdbc.Database
import ru.tinsurance.api.*
import ru.tinsurance.api.operator.*
import ru.tinsurance.server.operator.ClaimCardQueries
import ru.tinsurance.server.assignment.AutoAssigner
import ru.tinsurance.server.claims.*
import ru.tinsurance.server.common.*
import ru.tinsurance.server.db.*
import java.time.OffsetDateTime

class OperatorActionsService(
    private val db: Database,
    private val claimsConfig: ClaimsConfig,
    private val maxFiles: Int,
    private val autoAssigner: AutoAssigner,
) {
    suspend fun requestMaterials(actor: Actor, claimId: Long, request: RequestMaterialsRequest): ClaimCard = db.tx {
        val claim = inReview(actor, claimId)
        MaterialsRequestValidator.validate(claim, request, maxFiles)
        val now = OffsetDateTime.now()
        InfoRequestRepository.closeOpen(claimId, InfoRequestStatus.CANCELLED, now)
        val id = ManualMaterialsRepository.create(claimId, actor.id!!, request, claimsConfig.infoRequestDueDays, now)
        ClaimStatusService.transition(claimId, ClaimStatus.IN_REVIEW, ClaimStatus.NEEDS_INFO, actor,
            clientComment = request.message.trim(), at = now)
        AuditWriter.record(claimId, AuditEntity.CLAIM, claimId, "REQUEST_MATERIALS", actor,
            newValue = buildJsonObject { put("requestId", id) }, at = now)
        autoAssigner.fillOperator(actor.id)
        ClaimCardQueries.card(claimId)
    }

    suspend fun reviewResult(actor: Actor, claimId: Long, request: ReviewResultRequest): ClaimCard = db.tx {
        inReview(actor, claimId)
        val summary = MaterialsRequestValidator.text(request.summary, "summary")
        val clientComment = MaterialsRequestValidator.text(request.clientComment, "clientComment")
        val value = buildJsonObject { put("summary", summary); put("clientComment", clientComment) }
        val previous = ClaimDecisionWriter.current(claimId, DecisionKind.REVIEW_RESULT)?.get(ClaimDecisions.value)
        val id = ClaimDecisionWriter.overrideBy(claimId, DecisionKind.REVIEW_RESULT, value, summary, actor.id!!, OffsetDateTime.now())
        AuditWriter.record(claimId, AuditEntity.DECISION, id, "REVIEW_RESULT", actor, newValue = value, oldValue = previous)
        ClaimCardQueries.card(claimId)
    }

    suspend fun complete(actor: Actor, claimId: Long): ClaimCard = db.tx {
        val claim = inReview(actor, claimId)
        val result = ClaimDecisionWriter.current(claimId, DecisionKind.REVIEW_RESULT)
            ?.takeIf { it[ClaimDecisions.createdAt] >= claim[Claims.statusChangedAt] }
            ?: throw ConflictException("Сначала сохраните итог текущего рассмотрения")
        if (ManualMaterialsRepository.open(claimId) != null) throw ConflictException("Запрос материалов ещё открыт")
        val message = (result[ClaimDecisions.value] as JsonObject).getValue("clientComment").jsonPrimitive.content
        ClaimStatusService.transition(claimId, ClaimStatus.IN_REVIEW, ClaimStatus.COMPLETED, actor, clientComment = message)
        autoAssigner.fillOperator(actor.id!!)
        ClaimCardQueries.card(claimId)
    }

    suspend fun cancel(actor: Actor, claimId: Long, request: CancelClaimRequest): ClaimCard = db.tx {
        val claim = lock(claimId)
        if (actor.type != ActorType.ADMIN && (actor.type != ActorType.OPERATOR || claim[Claims.assignedOperatorId] != actor.id)) {
            throw ForbiddenException("Закрыть обращение может назначенный оператор или администратор")
        }
        val reason = MaterialsRequestValidator.text(request.reason, "reason")
        val comment = MaterialsRequestValidator.text(request.clientComment, "clientComment")
        val now = OffsetDateTime.now()
        ClaimStatusService.transition(claimId, claim[Claims.status], ClaimStatus.CANCELLED, actor,
            reason = reason, clientComment = comment, at = now)
        InfoRequestRepository.closeOpen(claimId, InfoRequestStatus.CANCELLED, now)
        claim[Claims.assignedOperatorId]?.let { autoAssigner.fillOperator(it) }
        ClaimCardQueries.card(claimId)
    }

    private fun lock(claimId: Long): ResultRow = ClaimRepository.findForUpdate(claimId)
        ?.takeIf { it[Claims.status] != ClaimStatus.DRAFT } ?: throw NotFoundException("Обращение не найдено")

    private fun inReview(actor: Actor, claimId: Long): ResultRow {
        val claim = lock(claimId)
        if (actor.type != ActorType.OPERATOR || claim[Claims.assignedOperatorId] != actor.id) {
            throw ForbiddenException("Действие доступно назначенному оператору")
        }
        if (claim[Claims.status] != ClaimStatus.IN_REVIEW) throw ConflictException("Сначала начните рассмотрение обращения")
        return claim
    }
}
