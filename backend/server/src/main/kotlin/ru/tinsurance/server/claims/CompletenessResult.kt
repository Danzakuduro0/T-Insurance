package ru.tinsurance.server.claims

import ru.tinsurance.api.MaterialIssue
import ru.tinsurance.api.RejectionReason
import ru.tinsurance.api.mobile.CompletenessDto
import ru.tinsurance.api.mobile.MissingDocumentDto
import ru.tinsurance.api.mobile.MissingFieldDto
import ru.tinsurance.api.mobile.ProblemFileDto

data class MissingField(val code: String, val title: String)

data class MissingDocument(val documentType: String, val name: String, val required: Int, val uploaded: Int)

data class ProblemFile(val fileId: Long, val documentType: String, val issue: MaterialIssue, val reason: RejectionReason?, val hint: String)

data class CompletenessResult(
    val missingFields: List<MissingField>,
    val missingDocuments: List<MissingDocument>,
    /** Отклонённые файлы тех типов, которых не хватает: клиенту нужно их заменить. */
    val problemFiles: List<ProblemFile>,
) {
    val complete get() = missingFields.isEmpty() && missingDocuments.isEmpty()
    val missingCoreFields get() = missingFields.filter { f -> CORE_FIELDS.any { it.apiName == f.code } }

    fun summary(): String = buildList {
        missingFields.forEach { add(it.title) }
        missingDocuments.forEach { add("${it.name} (${it.uploaded} из ${it.required})") }
    }.joinToString(", ")

    fun toDto() = CompletenessDto(
        complete = complete,
        missingFields = missingFields.map { MissingFieldDto(it.code, it.title) },
        missingDocuments = missingDocuments.map { MissingDocumentDto(it.documentType, it.name, it.required, it.uploaded) },
        problemFiles = problemFiles.map { ProblemFileDto(it.fileId, it.documentType, it.issue, it.reason) },
    )
}
