package ru.tinsurance.server.claims

import ru.tinsurance.server.analysis.QualityHints
import ru.tinsurance.api.*
import ru.tinsurance.server.db.*
import io.ktor.server.config.*
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/** Настройки обращений из application.yaml. Правила полей и документов — в справочниках БД (вопросы 7, 13). */
data class ClaimsConfig(
    /** Вопрос 34: сколько дней у клиента на дозагрузку материалов. */
    val infoRequestDueDays: Long,
) {
    companion object {
        val DEFAULT = ClaimsConfig(infoRequestDueDays = 7)

        fun from(config: ApplicationConfig) = ClaimsConfig(
            infoRequestDueDays = config.propertyOrNull("claims.infoRequestDueDays")?.getString()?.toLong()
                ?: DEFAULT.infoRequestDueDays,
        )
    }
}

/**
 * Минимум для регистрации обращения (вопрос 13): без них обращение остаётся черновиком.
 * Их же гарантирует CHECK claims_required_fields_chk в БД.
 */
val CORE_FIELDS = listOf(ClaimField.POLICY, ClaimField.EVENT_TYPE, ClaimField.EVENT_AT)

data class FieldRule(val code: String, val title: String, val kind: String, val valueType: String, val required: Boolean)

data class DocumentRule(val documentType: String, val name: String, val minCount: Int, val allowedMimeTypes: List<String>, val maxSizeBytes: Long)

/** Правила по типу события из справочников event_type_fields и required_documents. Вызывается внутри транзакции. */
object ClaimRules {

    fun fields(eventType: String): List<FieldRule> =
        EventTypeFields.selectAll()
            .where { EventTypeFields.eventType eq eventType }
            .orderBy(EventTypeFields.sortOrder)
            .map {
                FieldRule(
                    code = it[EventTypeFields.fieldCode],
                    title = it[EventTypeFields.title],
                    kind = it[EventTypeFields.kind],
                    valueType = it[EventTypeFields.valueType],
                    required = it[EventTypeFields.required],
                )
            }

    fun documents(eventType: String): List<DocumentRule> =
        RequiredDocuments
            .join(DocumentTypes, JoinType.INNER, RequiredDocuments.documentType, DocumentTypes.code)
            .selectAll()
            .where { RequiredDocuments.eventType eq eventType }
            .orderBy(RequiredDocuments.documentType)
            .map {
                DocumentRule(
                    documentType = it[RequiredDocuments.documentType],
                    name = it[DocumentTypes.name],
                    minCount = it[RequiredDocuments.minCount].toInt(),
                    allowedMimeTypes = it[DocumentTypes.allowedMimeTypes],
                    maxSizeBytes = it[DocumentTypes.maxSizeBytes],
                )
            }
}

data class MissingField(val code: String, val title: String)
data class MissingDocument(val documentType: String, val name: String, val required: Int, val uploaded: Int)
data class ProblemFile(val fileId: Long, val documentType: String, val issue: String, val reason: String?, val hint: String)

data class CompletenessResult(
    val missingFields: List<MissingField>,
    val missingDocuments: List<MissingDocument>,
    /** Отклонённые файлы тех типов, которых не хватает: клиенту нужно их заменить (вопросы 15, 16). */
    val problemFiles: List<ProblemFile>,
) {
    val complete get() = missingFields.isEmpty() && missingDocuments.isEmpty()
    val missingCoreFields get() = missingFields.filter { f -> CORE_FIELDS.any { it.apiName == f.code } }

    fun toDto() = CompletenessDto(
        complete = complete,
        missingFields = missingFields.map { MissingFieldDto(it.code, it.title) },
        missingDocuments = missingDocuments.map { MissingDocumentDto(it.documentType, it.name, it.required, it.uploaded) },
        problemFiles = problemFiles.map { ProblemFileDto(it.fileId, it.documentType, it.issue, it.reason) },
    )

    fun summary(): String = buildList {
        missingFields.forEach { add(it.title) }
        missingDocuments.forEach { add("${it.name} (${it.uploaded} из ${it.required})") }
    }.joinToString(", ")
}

/** Вызывается внутри транзакции. Учитываются только принятые файлы (status = ACCEPTED). */
object CompletenessChecker {

    fun check(claim: ResultRow): CompletenessResult {
        val missingFields = mutableListOf<MissingField>()
        if (claim[Claims.policyId] == null) missingFields += ClaimField.POLICY.missing()
        if (claim[Claims.eventType] == null) missingFields += ClaimField.EVENT_TYPE.missing()
        if (claim[Claims.eventAt] == null) missingFields += ClaimField.EVENT_AT.missing()

        // Без типа события неизвестно, какие поля и документы нужны
        val eventType = claim[Claims.eventType] ?: return CompletenessResult(missingFields, emptyList(), emptyList())
        val claimId = claim[Claims.id]

        ClaimRules.fields(eventType).filter { it.required }.forEach { rule ->
            if (!isFilled(claim, rule)) missingFields += MissingField(rule.code, rule.title)
        }

        val accepted = ClaimFiles.select(ClaimFiles.documentType, ClaimFiles.id.count())
            .where { (ClaimFiles.claimId eq claimId) and (ClaimFiles.status eq "ACCEPTED") }
            .groupBy(ClaimFiles.documentType)
            .associate { it[ClaimFiles.documentType] to it[ClaimFiles.id.count()].toInt() }

        val documentRules = ClaimRules.documents(eventType)
        val missingDocuments = documentRules.mapNotNull { rule ->
            val have = accepted[rule.documentType] ?: 0
            if (have >= rule.minCount) null else MissingDocument(rule.documentType, rule.name, rule.minCount, have)
        }

        val missingTypes = missingDocuments.map { it.documentType }
        val rejected = if (missingTypes.isEmpty()) emptyList() else ClaimFiles.selectAll()
            .where {
                (ClaimFiles.claimId eq claimId) and (ClaimFiles.status eq "REJECTED") and
                    ClaimFiles.replacedByFileId.isNull() and (ClaimFiles.documentType inList missingTypes)
            }
            .orderBy(ClaimFiles.id)
            .toList()
        val qualityIssues = QualityHints.issuesByFile(rejected.filter { it[ClaimFiles.rejectionReason] == "LOW_QUALITY" }.map { it[ClaimFiles.id] })
        val problemFiles = rejected.map { file ->
                val reason = file[ClaimFiles.rejectionReason]
                val lowQuality = reason == "LOW_QUALITY"
                val rule = documentRules.first { it.documentType == file[ClaimFiles.documentType] }
                ProblemFile(
                    fileId = file[ClaimFiles.id],
                    documentType = rule.documentType,
                    issue = if (lowQuality) "LOW_QUALITY" else "INVALID_FORMAT",
                    reason = reason,
                    hint = if (lowQuality) {
                        QualityHints.forIssues(qualityIssues[file[ClaimFiles.id]].orEmpty())
                    } else {
                        "Загрузите ${formats(rule.allowedMimeTypes)} размером до ${rule.maxSizeBytes / (1024 * 1024)} МБ"
                    },
                )
            }

        return CompletenessResult(missingFields, missingDocuments, problemFiles)
    }

    private fun isFilled(claim: ResultRow, rule: FieldRule): Boolean = when (rule.kind) {
        "CORE" -> when (rule.code) {
            ClaimField.EVENT_LOCATION.apiName -> !claim[Claims.eventLocation].isNullOrBlank()
            ClaimField.DESCRIPTION.apiName -> !claim[Claims.description].isNullOrBlank()
            ClaimField.CONTACT_PHONE.apiName -> !claim[Claims.contactPhone].isNullOrBlank()
            PARTICIPANTS -> ClaimParticipants.selectAll().where { ClaimParticipants.claimId eq claim[Claims.id] }.any()
            else -> error("event_type_fields: неизвестное CORE-поле '${rule.code}'")
        }
        else -> isDetailFilled(claim[Claims.eventDetails], rule.code)
    }

    private fun isDetailFilled(details: JsonObject, key: String): Boolean {
        val value = details[key] ?: return false
        return value !is JsonNull && !(value is JsonPrimitive && value.isString && value.content.isBlank())
    }

    private fun ClaimField.missing() = MissingField(apiName, title)

    private fun formats(mimeTypes: List<String>) =
        mimeTypes.joinToString(", ") { it.substringAfter('/').uppercase() }

    const val PARTICIPANTS = "participants"
}
