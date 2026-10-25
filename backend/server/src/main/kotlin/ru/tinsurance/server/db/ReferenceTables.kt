package ru.tinsurance.server.db

import org.jetbrains.exposed.v1.core.Table
import ru.tinsurance.api.ClaimStatus
import ru.tinsurance.api.FieldKind
import ru.tinsurance.api.FieldValueType

object ClaimStatuses : Table("claim_statuses") {
    val code = enumText<ClaimStatus>("code")
    val nameForClient = text("name_for_client")
    val isFinal = bool("is_final")
    val sortOrder = short("sort_order")
    val visibleToClient = bool("visible_to_client")
    val slaHours = integer("sla_hours").nullable()
    override val primaryKey = PrimaryKey(code)
}

object EventTypes : Table("event_types") {
    val code = text("code")
    val name = text("name")
    override val primaryKey = PrimaryKey(code)
}

object DocumentTypes : Table("document_types") {
    val code = text("code")
    val name = text("name")
    val isImage = bool("is_image")
    val allowedMimeTypes = textArray("allowed_mime_types")
    val maxSizeBytes = long("max_size_bytes")
    override val primaryKey = PrimaryKey(code)
}

/** CORE — колонки обращения, DETAIL — ключи в claims.event_details. */
object EventTypeFields : Table("event_type_fields") {
    val eventType = text("event_type")
    val fieldCode = text("field_code")
    val title = text("title")
    val kind = enumText<FieldKind>("kind")
    val valueType = enumText<FieldValueType>("value_type")
    val required = bool("required")
    val sortOrder = short("sort_order")
    override val primaryKey = PrimaryKey(eventType, fieldCode)
}

object RequiredDocuments : Table("required_documents") {
    val eventType = text("event_type")
    val documentType = text("document_type")
    val minCount = short("min_count")
    override val primaryKey = PrimaryKey(eventType, documentType)
}
