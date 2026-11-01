package ru.tinsurance.server.notifications

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import ru.tinsurance.api.ClaimStatus
import ru.tinsurance.server.db.*
import ru.tinsurance.server.jobs.JobQueue
import ru.tinsurance.server.jobs.JobType
import java.time.OffsetDateTime

object StatusNotifications {
    fun create(claimId: Long, status: ClaimStatus, title: String?, comment: String?, at: OffsetDateTime) {
        val channels = NotificationPolicy.channels(status)
        if (channels.isEmpty()) return
        val userId = Claims.select(Claims.clientId).where { Claims.id eq claimId }.single()[Claims.clientId]
        val heading = title ?: ClaimStatuses.select(ClaimStatuses.nameForClient).where { ClaimStatuses.code eq status }.single()[ClaimStatuses.nameForClient]
        for (channel in channels) {
            val id = Notifications.insert {
                it[Notifications.userId] = userId
                it[Notifications.claimId] = claimId
                it[type] = if (status == ClaimStatus.NEEDS_INFO) "DOCUMENTS_REQUIRED" else "STATUS_CHANGED"
                it[Notifications.title] = heading
                it[body] = comment ?: heading
                it[Notifications.channel] = channel
                it[createdAt] = at
                it[sentAt] = if (channel == "IN_APP") at else null
            }[Notifications.id]
            if (channel != "IN_APP") JobQueue.enqueue(JobType.SEND_NOTIFICATION,
                buildJsonObject { put("notificationId", id) }, "SEND_NOTIFICATION:$id", at)
        }
    }
}
