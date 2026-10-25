package ru.tinsurance.server.db

import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import ru.tinsurance.api.*

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
    val status = enumText<ClaimStatus>("status")
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

object ClaimStatusHistory : Table("claim_status_history") {
    val id = long("id").autoIncrement()
    val claimId = long("claim_id")
    val fromStatus = enumText<ClaimStatus>("from_status").nullable()
    val toStatus = enumText<ClaimStatus>("to_status")
    val actorType = enumText<ActorType>("actor_type")
    val actorId = long("actor_id").nullable()
    val reason = text("reason").nullable()
    val clientTitle = text("client_title").nullable()
    val clientComment = text("client_comment").nullable()
    val visibleToClient = bool("visible_to_client")
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}

object ClaimDecisions : Table("claim_decisions") {
    val id = long("id").autoIncrement()
    val claimId = long("claim_id")
    val kind = enumText<DecisionKind>("kind")
    val decisionSource = enumText<DecisionSource>("source")
    val action = enumText<DecisionAction>("action").default(DecisionAction.SET)
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

object ClaimComments : Table("claim_comments") {
    val id = long("id").autoIncrement()
    val claimId = long("claim_id")
    val authorId = long("author_id")
    val visibility = enumText<CommentVisibility>("visibility")
    val body = text("body")
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}

object InfoRequests : Table("info_requests") {
    val id = long("id").autoIncrement()
    val claimId = long("claim_id")
    val requestSource = enumText<ActorType>("source")
    val requestedBy = long("requested_by").nullable()
    val message = text("message")
    val status = enumText<InfoRequestStatus>("status")
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
    val issue = enumText<MaterialIssue>("issue").default(MaterialIssue.MISSING)
    val hint = text("hint").nullable()
    val fileId = long("file_id").nullable()
    val isFulfilled = bool("is_fulfilled")
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
    val actorType = enumText<ActorType>("actor_type")
    val actorId = long("actor_id").nullable()
    val comment = text("comment").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}
