package ru.tinsurance.server.claims

import kotlinx.serialization.json.*
import ru.tinsurance.api.ClaimStatus
import ru.tinsurance.api.DecisionKind
import ru.tinsurance.api.InfoRequestStatus
import ru.tinsurance.server.jobs.JobQueue
import ru.tinsurance.server.jobs.JobType
import java.time.OffsetDateTime

/**
 * Приём обращения: решение о комплектности, запрос материалов, переход к анализу.
 * Вызывается при отправке клиентом и фоновым воркером. Внутри транзакции.
 */
object ClaimIntake {
    const val COMPLETENESS_RULES_VERSION = "completeness-v2"

    fun recordCompleteness(claimId: Long, result: CompletenessResult, at: OffsetDateTime) {
        ClaimDecisionWriter.setBySystem(
            claimId, DecisionKind.COMPLETENESS,
            value = completenessJson(result),
            confidence = null,
            reason = if (result.complete) "Комплект полный" else "Не хватает: ${result.summary()}",
            ruleVersion = COMPLETENESS_RULES_VERSION,
            at = at,
        )
    }

    /** Комплект полный: SUBMITTED → IN_ANALYSIS и задача ROUTE_CLAIM, которая дождётся анализа фото. */
    fun startAnalysis(claimId: Long, at: OffsetDateTime) {
        InfoRequestRepository.closeOpen(claimId, InfoRequestStatus.FULFILLED, at)
        ClaimStatusService.transition(
            claimId, ClaimStatus.SUBMITTED, ClaimStatus.IN_ANALYSIS, Actor.SYSTEM,
            reason = "Комплект материалов полный", at = at,
        )
        JobQueue.enqueue(
            JobType.ROUTE_CLAIM,
            buildJsonObject { put("claimId", claimId) },
            dedupKey = "ROUTE_CLAIM:claim:$claimId",
        )
    }

    /** Не хватает материалов: новый запрос с актуальным перечнем, обращение возвращается клиенту. */
    fun requestMaterials(claimId: Long, from: ClaimStatus, result: CompletenessResult, dueDays: Long, at: OffsetDateTime) {
        InfoRequestRepository.closeOpen(claimId, InfoRequestStatus.CANCELLED, at)
        InfoRequestRepository.createFromCompleteness(claimId, result, dueDays, at)
        val missing = "Не хватает: ${result.summary()}"
        ClaimStatusService.transition(
            claimId, from, ClaimStatus.NEEDS_INFO, Actor.SYSTEM,
            reason = missing, clientComment = missing, at = at,
        )
    }

    private fun completenessJson(result: CompletenessResult) = buildJsonObject {
        put("complete", result.complete)
        putJsonArray("missingFields") { result.missingFields.forEach { add(it.code) } }
        putJsonArray("missingDocuments") {
            result.missingDocuments.forEach { d ->
                addJsonObject {
                    put("documentType", d.documentType)
                    put("required", d.required)
                    put("uploaded", d.uploaded)
                }
            }
        }
        putJsonArray("problemFiles") {
            result.problemFiles.forEach { f -> addJsonObject { put("fileId", f.fileId); put("issue", f.issue.name) } }
        }
    }
}
