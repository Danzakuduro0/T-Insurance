package ru.tinsurance.server.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

object QueueLoadView : Table("queue_load") {
    val directionCode = text("direction_code")
    val queued = long("queued")
    val queuedUrgent = long("queued_urgent")
    val overdue = long("overdue")
    val oldestQueuedAt = timestampWithTimeZone("oldest_queued_at").nullable()
}
