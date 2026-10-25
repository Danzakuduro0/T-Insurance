package ru.tinsurance.server.claims

import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.jdbc.insert
import ru.tinsurance.server.db.AuditLog
import java.time.OffsetDateTime

enum class AuditEntity { CLAIM, FILE }

/** Журнал действий (audit_log). Вызывается внутри транзакции. */
object AuditWriter {

    fun record(
        claimId: Long,
        entity: AuditEntity,
        entityId: Long,
        action: String,
        actor: Actor,
        newValue: JsonElement? = null,
        oldValue: JsonElement? = null,
        comment: String? = null,
        at: OffsetDateTime = OffsetDateTime.now(),
    ) {
        AuditLog.insert {
            it[AuditLog.claimId] = claimId
            it[entityType] = entity.name
            it[AuditLog.entityId] = entityId
            it[AuditLog.action] = action
            it[AuditLog.oldValue] = oldValue
            it[AuditLog.newValue] = newValue
            it[actorType] = actor.type
            it[actorId] = actor.id
            it[AuditLog.comment] = comment
            it[createdAt] = at
        }
    }
}
