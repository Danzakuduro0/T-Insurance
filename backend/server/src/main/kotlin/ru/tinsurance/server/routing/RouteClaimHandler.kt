package ru.tinsurance.server.routing

import ru.tinsurance.server.analysis.AnalysisConfig
import ru.tinsurance.api.ClaimStatus
import ru.tinsurance.server.claims.*
import ru.tinsurance.server.common.tx
import ru.tinsurance.server.db.ClaimFiles
import ru.tinsurance.server.db.Claims
import ru.tinsurance.server.db.DocumentTypes
import ru.tinsurance.server.db.ImageAnalyses
import ru.tinsurance.server.jobs.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import java.time.Duration
import java.time.OffsetDateTime

/**
 * Задача ROUTE_CLAIM — ставится, когда обращение переходит в IN_ANALYSIS.
 * Ждёт, пока проанализированы все фото (завершение анализа будит задачу), но не дольше maxWait:
 * если ML недоступен, обращение не застревает, а уходит оператору с пометкой (вопрос 22, раздел 6.5).
 * Дальше: фото отклонено как непригодное → запрос клиенту (NEEDS_INFO); иначе → очередь по правилам маршрутизации.
 */
class RouteClaimHandler(
    private val db: Database,
    private val analysis: AnalysisConfig,
    private val routing: RoutingConfig,
    private val claims: ClaimsConfig,
) : JobHandler {
    override val type = JobType.ROUTE_CLAIM

    override suspend fun handle(job: Job): JobOutcome = db.tx {
        val claimId = job.long("claimId")
        val claim = Claims.selectAll().where { Claims.id eq claimId }.forUpdate().singleOrNull()
        if (claim == null || claim[Claims.status] != ClaimStatus.IN_ANALYSIS.name) return@tx JobOutcome.Done

        val photos = photos(claimId)
        val now = OffsetDateTime.now()
        val waited = Duration.between(claim[Claims.statusChangedAt], now)
        if (photos.any { it.analysisStatus == "PENDING" } && waited < analysis.maxWait) {
            return@tx JobOutcome.Later(analysis.routeRecheck)
        }

        // Анализ отклонил фото как непригодное — клиенту запрос переснять (раздел 6.4: ADDITIONAL_DOCS)
        val completeness = CompletenessChecker.check(claim)
        if (!completeness.complete) {
            ClaimIntake.recordCompleteness(claimId, completeness, now)
            ClaimIntake.requestMaterials(claimId, ClaimStatus.IN_ANALYSIS, completeness, claims.infoRequestDueDays, now)
            return@tx JobOutcome.Done
        }

        val decision = RoutingRules.decide(RoutingInput(photos, duplicateClaimNumber(claim)), routing)
        apply(claimId, decision, photos, now)
        JobOutcome.Done
    }

    /** Повторы кончились из-за ошибок — не оставляем обращение в IN_ANALYSIS: отдаём оператору на ручную проверку. */
    override suspend fun giveUp(job: Job, error: String) {
        db.tx {
            val claimId = job.long("claimId")
            val claim = Claims.selectAll().where { Claims.id eq claimId }.forUpdate().singleOrNull() ?: return@tx
            if (claim[Claims.status] != ClaimStatus.IN_ANALYSIS.name) return@tx
            val decision = RoutingDecision(
                direction = RoutingRules.MANUAL_REVIEW, priority = 2, manualReview = true,
                reasons = listOf("Ошибка автоматической обработки — нужна ручная проверка"),
                confidence = null, damageType = null,
            )
            apply(claimId, decision, emptyList(), OffsetDateTime.now())
        }
    }

    /** Принятые фото обращения (те, что анализируются) и последний результат анализа каждого. */
    private fun photos(claimId: Long): List<PhotoAnalysis> {
        val files = ClaimFiles.join(DocumentTypes, JoinType.INNER, ClaimFiles.documentType, DocumentTypes.code)
            .select(ClaimFiles.id, ClaimFiles.analysisStatus)
            .where { (ClaimFiles.claimId eq claimId) and (ClaimFiles.status eq "ACCEPTED") and (DocumentTypes.isImage eq true) }
            .associate { it[ClaimFiles.id] to it[ClaimFiles.analysisStatus] }
        if (files.isEmpty()) return emptyList()
        val latest = ImageAnalyses.selectAll()
            .where { ImageAnalyses.fileId inList files.keys }
            .orderBy(ImageAnalyses.id to SortOrder.ASC)
            .associateBy { it[ImageAnalyses.fileId] }
        return files.map { (fileId, status) ->
            val analysis = latest[fileId]
            PhotoAnalysis(fileId, status, analysis?.get(ImageAnalyses.damageType), analysis?.get(ImageAnalyses.damageConfidence))
        }
    }

    private fun duplicateClaimNumber(claim: ResultRow): String? = claim[Claims.duplicateOfClaimId]?.let { other ->
        Claims.select(Claims.claimNumber).where { Claims.id eq other }.singleOrNull()?.get(Claims.claimNumber)
    }

    private fun apply(claimId: Long, decision: RoutingDecision, photos: List<PhotoAnalysis>, at: OffsetDateTime) {
        decision.damageType?.let { damage ->
            ClaimDecisionWriter.setBySystem(
                claimId, "DAMAGE",
                value = buildJsonObject {
                    put("damageType", damage)
                    putJsonArray("photos") {
                        photos.filter { it.damageType != null }.forEach { p ->
                            addJsonObject { put("fileId", p.fileId); put("damageType", p.damageType); put("confidence", p.confidence) }
                        }
                    }
                },
                confidence = decision.confidence,
                reason = "Модель: $damage",
                ruleVersion = RoutingRules.VERSION, at = at,
            )
        }
        ClaimDecisionWriter.setBySystem(
            claimId, "ROUTING",
            value = buildJsonObject {
                put("direction", decision.direction)
                put("manualReview", decision.manualReview)
                putJsonArray("reasons") { decision.reasons.forEach { add(it) } }
            },
            confidence = decision.confidence,
            reason = decision.reasons.joinToString("; "),
            ruleVersion = RoutingRules.VERSION, at = at,
        )
        ClaimDecisionWriter.setBySystem(
            claimId, "PRIORITY",
            value = buildJsonObject { put("priority", decision.priority) },
            confidence = null,
            reason = if (decision.manualReview) "Ручная проверка — приоритет повышен" else "Обычный приоритет",
            ruleVersion = RoutingRules.VERSION, at = at,
        )
        Claims.update({ Claims.id eq claimId }) {
            it[directionCode] = decision.direction
            it[priority] = decision.priority.toShort()
            it[requiresManualReview] = decision.manualReview
        }
        ClaimStatusService.transition(
            claimId, ClaimStatus.IN_ANALYSIS, ClaimStatus.QUEUED, Actor.SYSTEM,
            reason = decision.reasons.joinToString("; "), at = at,
        )
    }

    companion object {
        /**
         * Обращения в IN_ANALYSIS без активной задачи ROUTE_CLAIM (например, после сбоя) получают новую.
         * Запускается воркером периодически.
         */
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
