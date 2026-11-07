package ru.tinsurance.server.claims

import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import ru.tinsurance.api.ClaimField
import ru.tinsurance.api.FieldKind
import ru.tinsurance.api.FieldValueType
import ru.tinsurance.server.db.DocumentTypes
import ru.tinsurance.server.db.EventTypeFields
import ru.tinsurance.server.db.RequiredDocuments

/** Минимум для регистрации обращения: без этих полей оно остаётся черновиком (их же требует CHECK в БД). */
val CORE_FIELDS = listOf(ClaimField.POLICY, ClaimField.EVENT_TYPE, ClaimField.EVENT_AT)

data class FieldRule(val code: String, val title: String, val kind: FieldKind, val valueType: FieldValueType, val required: Boolean)

data class DocumentRule(val documentType: String, val name: String, val minCount: Int, val allowedMimeTypes: List<String>, val maxSizeBytes: Long)

/** Правила по типу события из справочников. Вызывается внутри транзакции. */
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
