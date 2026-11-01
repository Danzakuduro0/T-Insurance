package ru.tinsurance.server.notifications

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import ru.tinsurance.api.mobile.NotificationDto
import ru.tinsurance.server.db.Notifications as N
import java.time.OffsetDateTime

object NotificationRepository {
    private fun owned(userId: Long) = (N.userId eq userId) and (N.channel eq "IN_APP")

    fun list(userId: Long, beforeId: Long?, limit: Int, unread: Boolean): List<NotificationDto> =
        N.selectAll().where {
            owned(userId) and (beforeId?.let { N.id less it } ?: Op.TRUE) and
                (if (unread) N.readAt.isNull() else Op.TRUE)
        }.orderBy(N.id, SortOrder.DESC).limit(limit).map { it.dto() }

    fun markRead(userId: Long, id: Long): NotificationDto? {
        N.update({ owned(userId) and (N.id eq id) and N.readAt.isNull() }) { it[readAt] = OffsetDateTime.now() }
        return N.selectAll().where { owned(userId) and (N.id eq id) }.singleOrNull()?.dto()
    }

    private fun ResultRow.dto() = NotificationDto(this[N.id], this[N.claimId], this[N.type], this[N.title],
        this[N.body], this[N.createdAt].toString(), this[N.readAt]?.toString())
}
