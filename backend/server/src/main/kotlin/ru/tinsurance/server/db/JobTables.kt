package ru.tinsurance.server.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

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
