package ru.tinsurance.server.auth

import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import ru.tinsurance.server.db.AuthSessions
import ru.tinsurance.server.db.Users
import java.time.OffsetDateTime

/** Сессии входа: в БД хранится только хэш refresh-токена. Вызывается внутри транзакции. */
object SessionRepository {

    fun create(userId: Long, channel: Channel, deviceId: Long?, refreshHash: String, userAgent: String?, expiresAt: OffsetDateTime, at: OffsetDateTime): Long =
        AuthSessions.insert {
            it[AuthSessions.userId] = userId
            it[clientType] = channel.clientType
            it[AuthSessions.deviceId] = deviceId
            it[refreshTokenHash] = refreshHash
            it[AuthSessions.userAgent] = userAgent?.take(500)
            it[createdAt] = at
            it[lastUsedAt] = at
            it[AuthSessions.expiresAt] = expiresAt
        }[AuthSessions.id]

    fun findActiveForUpdate(refreshHash: String, channel: Channel, now: OffsetDateTime): ResultRow? =
        AuthSessions.selectAll()
            .where {
                (AuthSessions.refreshTokenHash eq refreshHash) and (AuthSessions.clientType eq channel.clientType) and
                    AuthSessions.revokedAt.isNull() and (AuthSessions.expiresAt greater now)
            }
            .forUpdate()
            .singleOrNull()

    fun revoke(sessionId: Long, at: OffsetDateTime) {
        AuthSessions.update({ (AuthSessions.id eq sessionId) and AuthSessions.revokedAt.isNull() }) {
            it[revokedAt] = at
            it[lastUsedAt] = at
        }
    }

    /** Удаляет сессии, истёкшие или отозванные раньше [before]; возвращает их число. */
    fun deleteStale(before: OffsetDateTime): Int =
        AuthSessions.deleteWhere { (AuthSessions.expiresAt less before) or (AuthSessions.revokedAt less before) }

    /** Сессия не отозвана, не истекла, пользователь не заблокирован. */
    fun isActive(sessionId: Long, userId: Long, now: OffsetDateTime): Boolean =
        AuthSessions.join(Users, JoinType.INNER, AuthSessions.userId, Users.id)
            .select(AuthSessions.id)
            .where {
                (AuthSessions.id eq sessionId) and (AuthSessions.userId eq userId) and AuthSessions.revokedAt.isNull() and
                    (AuthSessions.expiresAt greater now) and (Users.isActive eq true)
            }
            .any()
}
