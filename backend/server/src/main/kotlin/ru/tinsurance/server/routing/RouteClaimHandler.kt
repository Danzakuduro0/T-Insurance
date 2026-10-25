package ru.tinsurance.server.routing

import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import ru.tinsurance.api.AnalysisStatus
import ru.tinsurance.api.ClaimStatus
import ru.tinsurance.api.FileStatus
import ru.tinsurance.server.analysis.AnalysisConfig
import ru.tinsurance.server.analysis.ImageAnalysisRepository
import ru.tinsurance.server.assignment.AutoAssigner
import ru.tinsurance.server.claims.ClaimIntake
import ru.tinsurance.server.claims.ClaimRepository
import ru.tinsurance.server.claims.ClaimsConfig
import ru.tinsurance.server.claims.CompletenessChecker
import ru.tinsurance.server.common.tx
import ru.tinsurance.server.db.ClaimFiles
import ru.tinsurance.server.db.Claims
import ru.tinsurance.server.db.DocumentTypes
import ru.tinsurance.server.db.ImageAnalyses
import ru.tinsurance.server.jobs.*
import java.time.Duration
import java.time.OffsetDateTime

/**
 * Задача ROUTE_CLAIM — ставится, когда обращение переходит в IN_ANALYSIS. Ждёт анализ всех фото, но не дольше
 * maxWait. Дальше: фото отклонено — запрос клиенту (NEEDS_INFO), иначе — очередь по правилам маршрутизации
 * и автоназначение оператору на смене.
 */
class RouteClaimHandler(
    private val db: Database,
    private val analysis: AnalysisConfig,
    private val routing: RoutingConfig,
    private val claims: ClaimsConfig,
    private val autoAssigner: AutoAssigner,
) : JobHandler {
    override val type = JobType.ROUTE_CLAIM

    override suspend fun handle(job: Job): JobOutcome = db.tx {
        val claimId = job.long("claimId")
        val claim = ClaimRepository.findForUpdate(claimId)
        if (claim == null || claim[Claims.status] != ClaimStatus.IN_ANALYSIS) return@tx JobOutcome.Done

        val photos = photos(claimId)
        val now = OffsetDateTime.now()
        val waited = Duration.between(claim[Claims.statusChangedAt], now)
        if (photos.any { it.analysisStatus == AnalysisStatus.PENDING } && waited < analysis.maxWait) {
            return@tx JobOutcome.Later(analysis.routeRecheck)
        }

        val completeness = CompletenessChecker.check(claim)
        if (!completeness.complete) {
            ClaimIntake.recordCompleteness(claimId, completeness, now)
            ClaimIntake.requestMaterials(claimId, ClaimStatus.IN_ANALYSIS, completeness, claims.infoRequestDueDays, now)
            return@tx JobOutcome.Done
        }
        val decision = RoutingRules.decide(RoutingInput(photos, duplicateClaimNumber(claim)), routing)
        RoutingDecisionWriter.apply(claimId, decision, photos, now)
        autoAssigner.assignQueued(claimId)
        JobOutcome.Done
    }

    /** Повторы кончились из-за ошибок — обращение не остаётся в IN_ANALYSIS, а уходит на ручную проверку. */
    override suspend fun giveUp(job: Job, error: String) {
        db.tx {
            val claimId = job.long("claimId")
            val claim = ClaimRepository.findForUpdate(claimId) ?: return@tx
            if (claim[Claims.status] != ClaimStatus.IN_ANALYSIS) return@tx
            val decision = RoutingDecision(
                direction = RoutingRules.MANUAL_REVIEW, priority = 2, manualReview = true,
                reasons = listOf("Ошибка автоматической обработки — нужна ручная проверка"),
                confidence = null, damageType = null,
            )
            RoutingDecisionWriter.apply(claimId, decision, emptyList(), OffsetDateTime.now())
            autoAssigner.assignQueued(claimId)
        }
    }

    /** Принятые фото обращения и последний результат анализа каждого. */
    private fun photos(claimId: Long): List<PhotoAnalysis> {
        val files = ClaimFiles.join(DocumentTypes, JoinType.INNER, ClaimFiles.documentType, DocumentTypes.code)
            .select(ClaimFiles.id, ClaimFiles.analysisStatus)
            .where { (ClaimFiles.claimId eq claimId) and (ClaimFiles.status eq FileStatus.ACCEPTED) and (DocumentTypes.isImage eq true) }
            .associate { it[ClaimFiles.id] to it[ClaimFiles.analysisStatus] }
        val latest = ImageAnalysisRepository.latestByFile(files.keys)
        return files.map { (fileId, status) ->
            val analysis = latest[fileId]
            PhotoAnalysis(fileId, status, analysis?.get(ImageAnalyses.damageType), analysis?.get(ImageAnalyses.damageConfidence))
        }
    }

    private fun duplicateClaimNumber(claim: ResultRow): String? = claim[Claims.duplicateOfClaimId]?.let { other ->
        Claims.select(Claims.claimNumber).where { Claims.id eq other }.singleOrNull()?.get(Claims.claimNumber)
    }

    companion object {
        /** Обращения в IN_ANALYSIS без активной задачи (например, после сбоя) получают новую. */
        fun scheduleMissing(tx: JdbcTransaction) {
            tx.exec(
                """
                insert into background_jobs (type, payload, dedup_key, status, attempts, max_attempts, run_after)
                select 'ROUTE_CLAIM', jsonb_build_object('claimId', c.id),
                       'ROUTE_CLAIM:claim:' || c.id || ':sweep:' || extract(epoch from now())::bigint,
                       'PENDING', 0, ${JobType.ROUTE_CLAIM.maxAttempts}, now()
                  from claims c
                 where c.status = 'IN_ANALYSIS'
                   and not exists (select 1 from background_jobs j
                                    where j.type = 'ROUTE_CLAIM' and j.status in ('PENDING', 'RUNNING')
                                      and (j.payload ->> 'claimId')::bigint = c.id)
                on conflict (dedup_key) do nothing
                """.trimIndent(),
            )
        }
    }
}
