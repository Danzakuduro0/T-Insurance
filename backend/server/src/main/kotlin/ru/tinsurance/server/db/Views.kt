package ru.tinsurance.server.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import ru.tinsurance.api.ClaimStatus

// Представления БД — только чтение.

object ClientClaimListView : Table("client_claim_list") {
    val id = long("id")
    val clientId = long("client_id")
    val claimNumber = text("claim_number")
    val status = enumText<ClaimStatus>("status")
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
    val status = enumText<ClaimStatus>("status")
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
