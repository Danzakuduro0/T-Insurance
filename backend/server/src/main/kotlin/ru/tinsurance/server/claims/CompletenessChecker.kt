package ru.tinsurance.server.claims

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.ResultRow
import ru.tinsurance.api.ClaimField
import ru.tinsurance.api.FieldKind
import ru.tinsurance.api.MaterialIssue
import ru.tinsurance.api.RejectionReason
import ru.tinsurance.server.analysis.QualityHints
import ru.tinsurance.server.db.Claims
import ru.tinsurance.server.files.FileRepository
import ru.tinsurance.server.files.hint

/** Чего не хватает обращению. Учитываются только принятые файлы. Вызывается внутри транзакции. */
object CompletenessChecker {

    fun check(claim: ResultRow): CompletenessResult {
        val missingFields = missingCoreFields(claim)
        val eventType = claim[Claims.eventType] ?: return CompletenessResult(missingFields, emptyList(), emptyList())
        val claimId = claim[Claims.id]

        ClaimRules.fields(eventType).filter { it.required && !isFilled(claim, it) }
            .forEach { missingFields += MissingField(it.code, it.title) }

        val documentRules = ClaimRules.documents(eventType)
        val accepted = FileRepository.acceptedCountByType(claimId)
        val missingDocuments = documentRules.mapNotNull { rule ->
            val have = accepted[rule.documentType] ?: 0
            if (have >= rule.minCount) null else MissingDocument(rule.documentType, rule.name, rule.minCount, have)
        }
        val problemFiles = problemFiles(claimId, documentRules.filter { r -> missingDocuments.any { it.documentType == r.documentType } })
        return CompletenessResult(missingFields, missingDocuments, problemFiles)
    }

    private fun missingCoreFields(claim: ResultRow): MutableList<MissingField> = buildList {
        if (claim[Claims.policyId] == null) add(ClaimField.POLICY.missing())
        if (claim[Claims.eventType] == null) add(ClaimField.EVENT_TYPE.missing())
        if (claim[Claims.eventAt] == null) add(ClaimField.EVENT_AT.missing())
    }.toMutableList()

    /** Отклонённые и не заменённые файлы недостающих типов — с подсказкой, что сделать. */
    private fun problemFiles(claimId: Long, rules: List<DocumentRule>): List<ProblemFile> {
        if (rules.isEmpty()) return emptyList()
        val rejected = FileRepository.rejectedNotReplaced(claimId, rules.map { it.documentType })
        val qualityIssues = QualityHints.issuesByFile(rejected.filter { it.reason == RejectionReason.LOW_QUALITY }.map { it.id })
        return rejected.map { file ->
            val rule = rules.first { it.documentType == file.documentType }
            val lowQuality = file.reason == RejectionReason.LOW_QUALITY
            ProblemFile(
                fileId = file.id,
                documentType = rule.documentType,
                issue = if (lowQuality) MaterialIssue.LOW_QUALITY else MaterialIssue.INVALID_FORMAT,
                reason = file.reason,
                hint = if (lowQuality) QualityHints.forIssues(qualityIssues[file.id].orEmpty())
                else "Загрузите ${formats(rule.allowedMimeTypes)} размером до ${rule.maxSizeBytes / (1024 * 1024)} МБ",
            )
        }
    }

    private fun isFilled(claim: ResultRow, rule: FieldRule): Boolean = when (rule.kind) {
        FieldKind.CORE -> when (rule.code) {
            ClaimField.EVENT_LOCATION.apiName -> !claim[Claims.eventLocation].isNullOrBlank()
            ClaimField.DESCRIPTION.apiName -> !claim[Claims.description].isNullOrBlank()
            ClaimField.CONTACT_PHONE.apiName -> !claim[Claims.contactPhone].isNullOrBlank()
            ClaimField.PARTICIPANTS -> ParticipantRepository.exists(claim[Claims.id])
            else -> error("event_type_fields: неизвестное CORE-поле '${rule.code}'")
        }
        FieldKind.DETAIL -> isDetailFilled(claim[Claims.eventDetails], rule.code)
    }

    private fun isDetailFilled(details: JsonObject, key: String): Boolean {
        val value = details[key] ?: return false
        return value !is JsonNull && !(value is JsonPrimitive && value.isString && value.content.isBlank())
    }

    private fun ClaimField.missing() = MissingField(apiName, title)

    private fun formats(mimeTypes: List<String>) = mimeTypes.joinToString(", ") { it.substringAfter('/').uppercase() }
}
