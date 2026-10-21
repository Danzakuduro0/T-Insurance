package ru.tinsurance.server.analysis

import ru.tinsurance.api.ClaimStatus
import ru.tinsurance.server.claims.ActorType
import ru.tinsurance.server.common.tx
import ru.tinsurance.server.db.AuditLog
import ru.tinsurance.server.db.ClaimFiles
import ru.tinsurance.server.db.Claims
import ru.tinsurance.server.db.ImageAnalyses
import ru.tinsurance.server.jobs.*
import ru.tinsurance.server.files.Rejection
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime

/**
 * Задача ANALYZE_IMAGE: фото → ML-сервис (или локальная проверка) → image_analyses.
 * Непригодное фото, пока клиент ещё может его заменить, отклоняется с подсказкой (вопрос 22).
 * Если анализ так и не удался — analysis_status FAILED, обращение уходит оператору без него (вопрос 22, раздел 6.5).
 */
class AnalyzeImageHandler(
    private val db: Database,
    private val analyzer: ImageAnalyzer,
    private val config: AnalysisConfig,
) : JobHandler {
    override val type = JobType.ANALYZE_IMAGE

    private class Target(val request: AnalysisRequest, val fileStatus: String, val analysisStatus: String)

    override suspend fun handle(job: Job): JobOutcome {
        val fileId = job.long("fileId")
        val target = db.tx { load(fileId) } ?: return JobOutcome.Done
        // файл заменили или уже проанализировали — анализ не нужен
        if (target.fileStatus != "ACCEPTED" || target.analysisStatus != "PENDING") return JobOutcome.Done

        val result = try {
            analyzer.analyze(target.request)
        } catch (e: AnalyzerUnavailableException) {
            return JobOutcome.Retry(e.message.orEmpty(), config.retryDelay(job.attempts))
        } catch (e: AnalyzerRejectedException) {
            return JobOutcome.Failed(e.message.orEmpty())
        }
        db.tx { save(target.request, result) }
        return JobOutcome.Done
    }

    override suspend fun giveUp(job: Job, error: String) {
        val fileId = job.long("fileId")
        db.tx {
            val claimId = ClaimFiles.select(ClaimFiles.claimId).where { ClaimFiles.id eq fileId }.singleOrNull()?.get(ClaimFiles.claimId)
                ?: return@tx
            ClaimFiles.update({ (ClaimFiles.id eq fileId) and (ClaimFiles.analysisStatus eq "PENDING") }) {
                it[analysisStatus] = "FAILED"
            }
            audit(claimId, fileId, "ANALYSIS_FAILED", buildJsonObject { put("error", error.take(300)) })
            JobQueue.wakeUp(this, JobType.ROUTE_CLAIM, "claimId", claimId)
        }
    }

    private fun load(fileId: Long): Target? =
        ClaimFiles.join(Claims, JoinType.INNER, ClaimFiles.claimId, Claims.id)
            .selectAll()
            .where { ClaimFiles.id eq fileId }
            .singleOrNull()
            ?.let {
                Target(
                    request = AnalysisRequest(
                        fileId = fileId,
                        claimId = it[ClaimFiles.claimId],
                        storageKey = it[ClaimFiles.storageKey],
                        documentType = it[ClaimFiles.documentType],
                        eventType = it[Claims.eventType],
                    ),
                    fileStatus = it[ClaimFiles.status],
                    analysisStatus = it[ClaimFiles.analysisStatus],
                )
            }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.save(request: AnalysisRequest, result: AnalysisResult) {
        val now = OffsetDateTime.now()
        ImageAnalyses.insert {
            it[fileId] = request.fileId
            it[modelVersion] = result.modelVersion
            it[qualityOk] = result.qualityAcceptable
            it[qualityScore] = result.qualityScore
            it[qualityIssues] = result.qualityIssues
            it[damageType] = result.damageType
            it[damageConfidence] = result.damageConfidence
            // вопрос 19: обязательный минимум — видна ли область повреждения
            it[damageDetected] = when {
                "NO_DAMAGE_VISIBLE" in result.qualityIssues -> false
                result.damageType != null -> true
                else -> null
            }
            it[carPart] = result.carPart
            it[damageArea] = result.damageArea
            it[rawResult] = result.raw
            it[createdAt] = now
        }
        ClaimFiles.update({ (ClaimFiles.id eq request.fileId) and (ClaimFiles.analysisStatus eq "PENDING") }) {
            it[analysisStatus] = "DONE"
        }

        val claimStatus = ClaimStatus.valueOf(
            Claims.select(Claims.status).where { Claims.id eq request.claimId }.single()[Claims.status]
        )
        // Пока клиент может заменить фото, непригодное отклоняется с подсказкой. Если обращение уже у оператора —
        // результат только показывается в карточке, решение за человеком.
        if (!result.qualityAcceptable && claimStatus in REPLACEABLE) {
            ClaimFiles.update({ (ClaimFiles.id eq request.fileId) and (ClaimFiles.status eq "ACCEPTED") }) {
                it[status] = "REJECTED"
                it[rejectionReason] = Rejection.LOW_QUALITY.name
            }
            audit(request.claimId, request.fileId, "FILE_REJECTED", buildJsonObject {
                put("reason", Rejection.LOW_QUALITY.name)
                putJsonArray("issues") { result.qualityIssues.forEach { add(it) } }
                put("modelVersion", result.modelVersion)
            })
        }
        JobQueue.wakeUp(this, JobType.ROUTE_CLAIM, "claimId", request.claimId)
    }

    private fun audit(claimId: Long, fileId: Long, action: String, value: kotlinx.serialization.json.JsonObject) {
        AuditLog.insert {
            it[AuditLog.claimId] = claimId
            it[entityType] = "FILE"
            it[entityId] = fileId
            it[AuditLog.action] = action
            it[newValue] = value
            it[actorType] = ActorType.SYSTEM.name
            it[createdAt] = OffsetDateTime.now()
        }
    }

    private companion object {
        val REPLACEABLE = setOf(ClaimStatus.DRAFT, ClaimStatus.NEEDS_INFO, ClaimStatus.SUBMITTED, ClaimStatus.IN_ANALYSIS)
    }
}
