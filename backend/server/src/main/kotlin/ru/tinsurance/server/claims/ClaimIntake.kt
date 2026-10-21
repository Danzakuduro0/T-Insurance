package ru.tinsurance.server.claims

import ru.tinsurance.api.ClaimStatus
import ru.tinsurance.server.db.InfoRequestItems
import ru.tinsurance.server.db.InfoRequests
import ru.tinsurance.server.jobs.JobQueue
import ru.tinsurance.server.jobs.JobType
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime

/**
 * Общие шаги приёма обращения: решение о комплектности, запрос материалов, переход к анализу.
 * Вызываются и при отправке клиентом, и фоновым воркером (фото отклонено анализом). Внутри транзакции.
 */
object ClaimIntake {
    const val COMPLETENESS_RULES_VERSION = "completeness-v2"

    fun recordCompleteness(claimId: Long, result: CompletenessResult, at: OffsetDateTime) {
        ClaimDecisionWriter.setBySystem(
            claimId, "COMPLETENESS",
            value = buildJsonObject {
                put("complete", result.complete)
                putJsonArray("missingFields") { result.missingFields.forEach { f -> add(f.code) } }
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
                    result.problemFiles.forEach { f -> addJsonObject { put("fileId", f.fileId); put("issue", f.issue) } }
                }
            },
            confidence = null,
            reason = if (result.complete) "Комплект полный" else "Не хватает: ${result.summary()}",
            ruleVersion = COMPLETENESS_RULES_VERSION,
            at = at,
        )
    }

    /** Комплект полный: SUBMITTED → IN_ANALYSIS и задача ROUTE_CLAIM — она дождётся анализа фото и отправит дальше. */
    fun startAnalysis(claimId: Long, at: OffsetDateTime) {
        closeOpenInfoRequest(claimId, "FULFILLED", at)
        ClaimStatusService.transition(
            claimId, ClaimStatus.SUBMITTED, ClaimStatus.IN_ANALYSIS, Actor.SYSTEM,
            reason = "Комплект материалов полный", at = at,
        )
        JobQueue.enqueue(
            JobType.ROUTE_CLAIM,
            buildJsonObject { put("claimId", claimId) },
            dedupKey = "ROUTE_CLAIM:claim:$claimId:${at.toInstant().toEpochMilli()}",
        )
    }

    /** Не хватает материалов: новый запрос с актуальным перечнем, обращение возвращается клиенту (NEEDS_INFO). */
    fun requestMaterials(claimId: Long, from: ClaimStatus, result: CompletenessResult, dueDays: Long, at: OffsetDateTime) {
        closeOpenInfoRequest(claimId, "CANCELLED", at)  // заменяется новым запросом с актуальным перечнем
        createInfoRequest(claimId, result, dueDays, at)
        val missing = "Не хватает: ${result.summary()}"
        ClaimStatusService.transition(
            claimId, from, ClaimStatus.NEEDS_INFO, Actor.SYSTEM,
            reason = missing, clientComment = missing, at = at,
        )
    }

    private fun closeOpenInfoRequest(claimId: Long, newStatus: String, at: OffsetDateTime) {
        val open = InfoRequests.select(InfoRequests.id)
            .where { (InfoRequests.claimId eq claimId) and (InfoRequests.status eq "OPEN") }
            .singleOrNull()?.get(InfoRequests.id) ?: return
        InfoRequests.update({ InfoRequests.id eq open }) {
            it[status] = newStatus
            it[closedAt] = at
        }
        if (newStatus == "FULFILLED") {
            InfoRequestItems.update({ InfoRequestItems.requestId eq open }) { it[isFulfilled] = true }
        }
    }

    /** Запрос различает «нет файла», «файл отклонён», «плохое качество» и подсказывает, что сделать (вопросы 15, 16, 22). */
    private fun createInfoRequest(claimId: Long, result: CompletenessResult, dueDays: Long, at: OffsetDateTime) {
        val requestId = InfoRequests.insert {
            it[InfoRequests.claimId] = claimId
            it[requestSource] = "SYSTEM"
            it[message] = "Для рассмотрения обращения не хватает материалов. Дополните их и отправьте обращение повторно."
            it[status] = "OPEN"
            it[dueAt] = at.plusDays(dueDays)
            it[createdAt] = at
        }[InfoRequests.id]

        result.missingDocuments.forEach { doc ->
            val problems = result.problemFiles.filter { it.documentType == doc.documentType }
            problems.forEach { file ->
                InfoRequestItems.insert {
                    it[InfoRequestItems.requestId] = requestId
                    it[documentType] = doc.documentType
                    it[minCount] = 1
                    it[issue] = file.issue
                    it[hint] = file.hint
                    it[fileId] = file.fileId
                    it[comment] = "Файл не принят — замените его"
                    it[isFulfilled] = false
                }
            }
            val stillMissing = doc.required - doc.uploaded - problems.size
            if (stillMissing > 0) {
                InfoRequestItems.insert {
                    it[InfoRequestItems.requestId] = requestId
                    it[documentType] = doc.documentType
                    it[minCount] = stillMissing.toShort()
                    it[issue] = "MISSING"
                    it[comment] = "Загружено ${doc.uploaded} из ${doc.required}"
                    it[isFulfilled] = false
                }
            }
        }
        result.missingFields.forEach { field ->
            InfoRequestItems.insert {
                it[InfoRequestItems.requestId] = requestId
                it[fieldName] = field.code
                it[minCount] = 1
                it[issue] = "MISSING"
                it[comment] = "Заполните поле «${field.title}»"
                it[isFulfilled] = false
            }
        }
    }
}
