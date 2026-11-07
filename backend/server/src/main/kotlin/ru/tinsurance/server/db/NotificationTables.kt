package ru.tinsurance.server.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

object Notifications : Table("notifications") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val claimId = long("claim_id").nullable()
    val type = text("type")
    val title = text("title")
    val body = text("body")
    val channel = text("channel")
    val createdAt = timestampWithTimeZone("created_at")
    val sentAt = timestampWithTimeZone("sent_at").nullable()
    val readAt = timestampWithTimeZone("read_at").nullable()
    override val primaryKey = PrimaryKey(id)
}
