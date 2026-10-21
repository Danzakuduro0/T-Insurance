package ru.tinsurance.server.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb

// Описание таблиц для запросов через Exposed.
// Схемой управляет Flyway (resources/db/migration) — SchemaUtils.create здесь не используется.
// Поля должны совпадать с миграциями; при изменении схемы правим оба места.

private val dbJson = Json { ignoreUnknownKeys = true }

private fun Table.jsonbElement(name: String): Column<JsonElement> =
    jsonb(name, dbJson, JsonElement.serializer())

private fun Table.jsonbObject(name: String): Column<JsonObject> =
    jsonb(name, dbJson, JsonObject.serializer())

private fun Table.textArray(name: String): Column<List<String>> =
    array(name, TextColumnType())

// ---------------------------------------------------------------- справочники

object ClaimStatuses : Table("claim_statuses") {
    val code = text("code")
    val nameForClient = text("name_for_client")
    val isFinal = bool("is_final")
    val sortOrder = short("sort_order")
    val visibleToClient = bool("visible_to_client")
    val slaHours = integer("sla_hours").nullable()
    override val primaryKey = PrimaryKey(code)
}

object StatusTransitions : Table("status_transitions") {
    val fromStatus = text("from_status")
    val toStatus = text("to_status")
    val allowedActors = textArray("allowed_actors")
    override val primaryKey = PrimaryKey(fromStatus, toStatus)
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

/** Поля формы по типу события (вопросы 6, 7): CORE — колонки обращения, DETAIL — ключи в claims.event_details. */
object EventTypeFields : Table("event_type_fields") {
    val eventType = text("event_type")
    val fieldCode = text("field_code")
    val title = text("title")
    val kind = text("kind")
    val valueType = text("value_type")
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

object Directions : Table("directions") {
    val code = text("code")
    val name = text("name")
    val description = text("description").nullable()
    override val primaryKey = PrimaryKey(code)
}

// ---------------------------------------------------------------- пользователи и договоры

object Users : Table("users") {
    val id = long("id").autoIncrement()
    val role = text("role")
    val fullName = text("full_name")
    val email = text("email").nullable()
    val phone = text("phone").nullable()
    val passwordHash = text("password_hash")
    val maxActiveClaims = short("max_active_claims").nullable()
    val isActive = bool("is_active")
    val availability = text("availability").nullable()
    val lastSeenAt = timestampWithTimeZone("last_seen_at").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}

object OperatorDirections : Table("operator_directions") {
    val operatorId = long("operator_id")
    val directionCode = text("direction_code")
    override val primaryKey = PrimaryKey(operatorId, directionCode)
}

object Policies : Table("policies") {
    val id = long("id").autoIncrement()
    val policyNumber = text("policy_number")
    val clientId = long("client_id")
    val product = text("product")
    val insuredObject = jsonbObject("insured_object")
    val validFrom = date("valid_from")
    val validTo = date("valid_to")
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}

object UserDevices : Table("user_devices") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val platform = text("platform")
    val installationId = text("installation_id")
    val pushToken = text("push_token").nullable()
    val appVersion = text("app_version").nullable()
    val osVersion = text("os_version").nullable()
    val isActive = bool("is_active")
    val createdAt = timestampWithTimeZone("created_at")
    val lastSeenAt = timestampWithTimeZone("last_seen_at")
    override val primaryKey = PrimaryKey(id)
}

object AuthSessions : Table("auth_sessions") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val clientType = text("client_type")
    val deviceId = long("device_id").nullable()
    val refreshTokenHash = text("refresh_token_hash")
    val userAgent = text("user_agent").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val lastUsedAt = timestampWithTimeZone("last_used_at")
    val expiresAt = timestampWithTimeZone("expires_at")
    val revokedAt = timestampWithTimeZone("revoked_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

// ---------------------------------------------------------------- обращения

object Claims : Table("claims") {
    val id = long("id").autoIncrement()
    val claimNumber = text("claim_number").databaseGenerated()
    val clientId = long("client_id")
    val policyId = long("policy_id").nullable()
    val eventType = text("event_type").nullable()
    val eventAt = timestampWithTimeZone("event_at").nullable()
    val eventLocation = text("event_location").nullable()
    val description = text("description").nullable()
    val contactPhone = text("contact_phone").nullable()
    val status = text("status")
    val directionCode = text("direction_code").nullable()
    val priority = short("priority").nullable()
    val requiresManualReview = bool("requires_manual_review")
    val assignedOperatorId = long("assigned_operator_id").nullable()
    val duplicateOfClaimId = long("duplicate_of_claim_id").nullable()
    val clientRequestId = uuid("client_request_id").nullable()
    val eventDetails = jsonbObject("event_details").default(JsonObject(emptyMap()))
    val statusChangedAt = timestampWithTimeZone("status_changed_at").defaultExpression(CurrentTimestampWithTimeZone)
    val submittedAt = timestampWithTimeZone("submitted_at").nullable()
    val assignedAt = timestampWithTimeZone("assigned_at").nullable()
    val closedAt = timestampWithTimeZone("closed_at").nullable()
    val version = integer("version")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object ClaimParticipants : Table("claim_participants") {
    val id = long("id").autoIncrement()
    val claimId = long("claim_id")
    val role = text("role")
    val fullName = text("full_name")
    val phone = text("phone").nullable()
    val vehicle = jsonbElement("vehicle").nullable()
    val insurer = text("insurer").nullable()
    val note = text("note").nullable()
    override val primaryKey = PrimaryKey(id)
}

object ClaimFiles : Table("claim_files") {
    val id = long("id").autoIncrement()
    val claimId = long("claim_id")
    val documentType = text("document_type")
    val storageKey = text("storage_key")
    val originalName = text("original_name")
    val mimeType = text("mime_type")
    val sizeBytes = long("size_bytes")
    val sha256 = text("sha256")
    val phash = long("phash").nullable()
    val width = integer("width").nullable()
    val height = integer("height").nullable()
    val status = text("status")
    val analysisStatus = text("analysis_status").default("NOT_REQUIRED")
    val rejectionReason = text("rejection_reason").nullable()
    val duplicateOfFileId = long("duplicate_of_file_id").nullable()
    val replacedByFileId = long("replaced_by_file_id").nullable()
    val uploadedBy = long("uploaded_by")
    val clientRequestId = uuid("client_request_id").nullable()
    val uploadedAt = timestampWithTimeZone("uploaded_at")
    override val primaryKey = PrimaryKey(id)
}

object ImageAnalyses : Table("image_analyses") {
    val id = long("id").autoIncrement()
    val fileId = long("file_id")
    val modelVersion = text("model_version")
    val qualityOk = bool("quality_ok")
    val qualityScore = float("quality_score").nullable()
    val qualityIssues = textArray("quality_issues")
    val vehicleDetected = bool("vehicle_detected").nullable()
    val damageType = text("damage_type").nullable()
    val damageConfidence = float("damage_confidence").nullable()
    val damageDetected = bool("damage_detected").nullable()
    val carPart = text("car_part").nullable()
    val damageArea = jsonbElement("damage_area").nullable()
    val rawResult = jsonbElement("raw_result").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}

object ClaimDecisions : Table("claim_decisions") {
    val id = long("id").autoIncrement()
    val claimId = long("claim_id")
    val kind = text("kind")
    val decisionSource = text("source")
    val action = text("action").default("SET")
    val value = jsonbElement("value")
    val confidence = float("confidence").nullable()
    val reason = text("reason").nullable()
    val ruleVersion = text("rule_version").nullable()
    val supersedesId = long("supersedes_id").nullable()
    val isCurrent = bool("is_current")
    val createdBy = long("created_by").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}

object ClaimStatusHistory : Table("claim_status_history") {
    val id = long("id").autoIncrement()
    val claimId = long("claim_id")
    val fromStatus = text("from_status").nullable()
    val toStatus = text("to_status")
    val actorType = text("actor_type")
    val actorId = long("actor_id").nullable()
    val reason = text("reason").nullable()
    val clientTitle = text("client_title").nullable()
    val clientComment = text("client_comment").nullable()
    val visibleToClient = bool("visible_to_client")
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}

object AuditLog : Table("audit_log") {
    val id = long("id").autoIncrement()
    val claimId = long("claim_id").nullable()
    val entityType = text("entity_type")
    val entityId = long("entity_id").nullable()
    val action = text("action")
    val oldValue = jsonbElement("old_value").nullable()
    val newValue = jsonbElement("new_value").nullable()
    val actorType = text("actor_type")
    val actorId = long("actor_id").nullable()
    val comment = text("comment").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}

object InfoRequests : Table("info_requests") {
    val id = long("id").autoIncrement()
    val claimId = long("claim_id")
    val requestSource = text("source")
    val requestedBy = long("requested_by").nullable()
    val message = text("message")
    val status = text("status")
    val dueAt = timestampWithTimeZone("due_at").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val closedAt = timestampWithTimeZone("closed_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object InfoRequestItems : Table("info_request_items") {
    val id = long("id").autoIncrement()
    val requestId = long("request_id")
    val documentType = text("document_type").nullable()
    val fieldName = text("field_name").nullable()
    val minCount = short("min_count")
    val comment = text("comment").nullable()
    val issue = text("issue").default("MISSING")
    val hint = text("hint").nullable()
    val fileId = long("file_id").nullable()
    val isFulfilled = bool("is_fulfilled")
    override val primaryKey = PrimaryKey(id)
}

object ClaimComments : Table("claim_comments") {
    val id = long("id").autoIncrement()
    val claimId = long("claim_id")
    val authorId = long("author_id")
    val visibility = text("visibility")
    val body = text("body")
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}

object Notifications : Table("notifications") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val claimId = long("claim_id").nullable()
    val type = text("type")
    val title = text("title")
    val body = text("body")
    val channel = text("channel")
    val payload = jsonbObject("payload")
    val createdAt = timestampWithTimeZone("created_at")
    val sentAt = timestampWithTimeZone("sent_at").nullable()
    val readAt = timestampWithTimeZone("read_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object BackgroundJobs : Table("background_jobs") {
    val id = long("id").autoIncrement()
    val type = text("type")
    val payload = jsonbElement("payload")
    val dedupKey = text("dedup_key").nullable()
    val status = text("status")
    val attempts = integer("attempts")
    val maxAttempts = integer("max_attempts")
    val runAfter = timestampWithTimeZone("run_after")
    val lockedAt = timestampWithTimeZone("locked_at").nullable()
    val lockedBy = text("locked_by").nullable()
    val lastError = text("last_error").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val finishedAt = timestampWithTimeZone("finished_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

// ---------------------------------------------------------------- представления (только чтение)

object ClientClaimListView : Table("client_claim_list") {
    val id = long("id")
    val clientId = long("client_id")
    val claimNumber = text("claim_number")
    val status = text("status")
    val statusTitle = text("status_title")
    val isFinal = bool("is_final")
    val eventTypeName = text("event_type_name").nullable()
    val eventAt = timestampWithTimeZone("event_at").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    val actionRequired = bool("action_required")
    val actionMessage = text("action_message").nullable()
}

object OperatorClaimListView : Table("operator_claim_list") {
    val id = long("id")
    val claimNumber = text("claim_number")
    val status = text("status")
    val directionCode = text("direction_code").nullable()
    val directionName = text("direction_name").nullable()
    val priority = short("priority").nullable()
    val requiresManualReview = bool("requires_manual_review")
    val eventType = text("event_type").nullable()
    val eventTypeName = text("event_type_name").nullable()
    val clientId = long("client_id")
    val clientName = text("client_name")
    val policyNumber = text("policy_number").nullable()
    val assignedOperatorId = long("assigned_operator_id").nullable()
    val assignedOperatorName = text("assigned_operator_name").nullable()
    val submittedAt = timestampWithTimeZone("submitted_at").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val statusChangedAt = timestampWithTimeZone("status_changed_at")
    val dueAt = timestampWithTimeZone("due_at").nullable()
    val isOverdue = bool("is_overdue")
    val filesCount = long("files_count")
    val analysesPending = long("analyses_pending")
    val hasDuplicate = bool("has_duplicate")
    val updatedAt = timestampWithTimeZone("updated_at")
}
