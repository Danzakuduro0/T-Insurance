package ru.tinsurance.server.analysis

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import ru.tinsurance.api.AnalysisStatus
import ru.tinsurance.api.ClaimStatus
import ru.tinsurance.api.FileStatus
import ru.tinsurance.api.RejectionReason
import ru.tinsurance.server.claims.Actor
import ru.tinsurance.server.claims.AuditEntity
import ru.tinsurance.server.claims.AuditWriter
import ru.tinsurance.server.claims.ClaimRepository
import ru.tinsurance.server.common.tx
import ru.tinsurance.server.db.ClaimFiles
import ru.tinsurance.server.db.Claims
import ru.tinsurance.server.jobs.*
import java.time.OffsetDateTime

/**
 * Задача ANALYZE_IMAGE: фото → ML-сервис (или локальная проверка) → image_analyses.
 * Непригодное фото, пока клиент может его заменить, отклоняется с подсказкой.
 * Если анализ так и не удался — FAILED, и обращение уходит оператору без него.
 */
class AnalyzeImageHandler(
    private val db: Database,
    private val analyzer: ImageAnalyzer,
    private val config: AnalysisConfig,
) : JobHandler {
    override val type = JobType.ANALYZE_IMAGE

    private class Target(val request: AnalysisRequest, val fileStatus: FileStatus, val analysisStatus: AnalysisStatus)

    override suspend fun handle(job: Job): JobOutcome {
        val target = db.tx { load(job.long("fileId")) } ?: return JobOutcome.Done
        // файл заменили или уже проанализировали
        if (target.fileStatus != FileStatus.ACCEPTED || target.analysisStatus != AnalysisStatus.PENDING) return JobOutcome.Done

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
            setAnalysisStatus(fileId, AnalysisStatus.FAILED)
            AuditWriter.record(claimId, AuditEntity.FILE, fileId, "ANALYSIS_FAILED", Actor.SYSTEM, buildJsonObject { put("error", error.take(300)) })
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
                    request = AnalysisRequest(fileId, it[ClaimFiles.claimId], it[ClaimFiles.storageKey], it[ClaimFiles.documentType], it[Claims.eventType]),
                    fileStatus = it[ClaimFiles.status],
                    analysisStatus = it[ClaimFiles.analysisStatus],
                )
            }

    /** Блокировка обращения упорядочивает анализ с отправкой и маршрутизацией: статус читается актуальный. */
    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.save(request: AnalysisRequest, result: AnalysisResult) {
        val claimStatus = ClaimRepository.findForUpdate(request.claimId)?.get(Claims.status) ?: return
        ImageAnalysisRepository.insert(request.fileId, result, OffsetDateTime.now())
        setAnalysisStatus(request.fileId, AnalysisStatus.DONE)
        // пока клиент может заменить фото, непригодное отклоняется; у оператора — только показывается в карточке
        if (!result.qualityAcceptable && claimStatus in REPLACEABLE) reject(request, result)
        JobQueue.wakeUp(this, JobType.ROUTE_CLAIM, "claimId", request.claimId)
    }

    private fun reject(request: AnalysisRequest, result: AnalysisResult) {
        ClaimFiles.update({ (ClaimFiles.id eq request.fileId) and (ClaimFiles.status eq FileStatus.ACCEPTED) }) {
            it[status] = FileStatus.REJECTED
            it[rejectionReason] = RejectionReason.LOW_QUALITY
        }
        AuditWriter.record(request.claimId, AuditEntity.FILE, request.fileId, "FILE_REJECTED", Actor.SYSTEM, buildJsonObject {
            put("reason", RejectionReason.LOW_QUALITY.name)
            putJsonArray("issues") { result.qualityIssues.forEach { add(it) } }
            put("modelVersion", result.modelVersion)
        })
    }

    private fun setAnalysisStatus(fileId: Long, status: AnalysisStatus) {
        ClaimFiles.update({ (ClaimFiles.id eq fileId) and (ClaimFiles.analysisStatus eq AnalysisStatus.PENDING) }) {
            it[analysisStatus] = status
        }
    }

    private companion object {
        val REPLACEABLE = setOf(ClaimStatus.DRAFT, ClaimStatus.NEEDS_INFO, ClaimStatus.SUBMITTED, ClaimStatus.IN_ANALYSIS)
    }
}
