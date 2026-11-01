package ru.tinsurance.server.notifications

import org.jetbrains.exposed.v1.jdbc.Database
import ru.tinsurance.api.mobile.NotificationsPage
import ru.tinsurance.server.common.*

class NotificationService(private val db: Database) {
    suspend fun list(userId: Long, beforeId: Long?, limit: Int, unread: Boolean): NotificationsPage {
        if (limit !in 1..100 || (beforeId != null && beforeId <= 0)) throw ValidationException("Некорректные параметры страницы")
        return db.tx {
            val rows = NotificationRepository.list(userId, beforeId, limit + 1, unread)
            NotificationsPage(rows.take(limit), if (rows.size > limit) rows[limit - 1].id else null)
        }
    }

    suspend fun markRead(userId: Long, id: Long) = db.tx {
        NotificationRepository.markRead(userId, id) ?: throw NotFoundException("Уведомление не найдено")
    }
}
