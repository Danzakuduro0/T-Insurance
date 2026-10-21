package ru.tinsurance.server.auth

import ru.tinsurance.api.*
import ru.tinsurance.server.common.UnauthorizedException
import ru.tinsurance.server.common.ValidationException
import ru.tinsurance.server.common.normalizePhone
import ru.tinsurance.server.common.tx
import ru.tinsurance.server.db.AuthSessions
import ru.tinsurance.server.db.UserDevices
import ru.tinsurance.server.db.Users
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import java.time.OffsetDateTime

class AuthService(
    private val db: Database,
    private val tokens: TokenService,
    private val throttle: LoginThrottle,
) {

    suspend fun loginMobile(request: MobileLoginRequest, userAgent: String?): AuthResponse {
        if (request.device.installationId.isBlank()) throw ValidationException("Не указан installationId устройства")
        val phone = normalizePhone(request.phone)
        val throttleKey = "MOBILE:$phone"
        throttle.checkAllowed(throttleKey)
        return db.tx {
            val user = Users.selectAll()
                .where { (Users.phone eq phone) and (Users.role eq Role.CLIENT.name) and (Users.isActive eq true) }
                .singleOrNull()
            if (!PasswordHasher.verify(request.password, user?.get(Users.passwordHash))) {
                throttle.onFailure(throttleKey)
                throw UnauthorizedException("Неверный телефон или пароль")
            }
            throttle.onSuccess(throttleKey)
            val deviceId = upsertDevice(user!![Users.id], request.device)
            startSession(user, Channel.MOBILE, deviceId, userAgent)
        }
    }

    suspend fun loginOperator(request: OperatorLoginRequest, userAgent: String?): AuthResponse {
        val email = request.email.trim().lowercase()
        val throttleKey = "WEB:$email"
        throttle.checkAllowed(throttleKey)
        return db.tx {
            val user = Users.selectAll()
                .where {
                    (Users.email.lowerCase() eq email) and
                        (Users.role inList listOf(Role.OPERATOR.name, Role.ADMIN.name)) and
                        (Users.isActive eq true)
                }
                .singleOrNull()
            if (!PasswordHasher.verify(request.password, user?.get(Users.passwordHash))) {
                throttle.onFailure(throttleKey)
                throw UnauthorizedException("Неверный email или пароль")
            }
            throttle.onSuccess(throttleKey)
            startSession(user!!, Channel.WEB, deviceId = null, userAgent)
        }
    }

    /** Ротация: старый refresh-токен отзывается, выдаётся новая пара. */
    suspend fun refresh(refreshToken: String, channel: Channel, userAgent: String?): AuthResponse = db.tx {
        val now = OffsetDateTime.now()
        val session = AuthSessions.selectAll()
            .where {
                (AuthSessions.refreshTokenHash eq tokens.hash(refreshToken)) and
                    (AuthSessions.clientType eq channel.clientType) and
                    AuthSessions.revokedAt.isNull() and
                    (AuthSessions.expiresAt greater now)
            }
            .forUpdate()
            .singleOrNull() ?: throw UnauthorizedException("Сессия истекла, войдите заново")

        val user = Users.selectAll()
            .where { (Users.id eq session[AuthSessions.userId]) and (Users.isActive eq true) }
            .singleOrNull() ?: throw UnauthorizedException("Пользователь заблокирован")

        AuthSessions.update({ AuthSessions.id eq session[AuthSessions.id] }) {
            it[revokedAt] = now
            it[lastUsedAt] = now
        }
        startSession(user, channel, session[AuthSessions.deviceId], userAgent)
    }

    suspend fun logout(sessionId: Long) = db.tx {
        AuthSessions.update({ (AuthSessions.id eq sessionId) and AuthSessions.revokedAt.isNull() }) {
            it[revokedAt] = OffsetDateTime.now()
        }
    }

    /**
     * Действует ли сессия, к которой выдан access-токен. После выхода, смены refresh-токена или блокировки
     * пользователя — нет: токен перестаёт работать сразу, не дожидаясь конца своих 15 минут.
     */
    suspend fun isSessionActive(principal: UserPrincipal): Boolean = db.tx {
        AuthSessions.join(Users, JoinType.INNER, AuthSessions.userId, Users.id)
            .select(AuthSessions.id)
            .where {
                (AuthSessions.id eq principal.sessionId) and (AuthSessions.userId eq principal.userId) and
                    AuthSessions.revokedAt.isNull() and (AuthSessions.expiresAt greater OffsetDateTime.now()) and
                    (Users.isActive eq true)
            }
            .any()
    }

    suspend fun me(userId: Long): UserDto = db.tx {
        Users.selectAll().where { Users.id eq userId }.single().toUserDto()
    }

    private fun upsertDevice(userId: Long, device: DeviceInfo): Long {
        val now = OffsetDateTime.now()
        // push-токен принадлежит одному устройству: если он был у другой записи — снимаем его там
        device.pushToken?.let { token ->
            UserDevices.update({ UserDevices.pushToken eq token }) { it[pushToken] = null }
        }
        val existing = UserDevices.selectAll()
            .where { (UserDevices.userId eq userId) and (UserDevices.installationId eq device.installationId) }
            .singleOrNull()
        return if (existing != null) {
            UserDevices.update({ UserDevices.id eq existing[UserDevices.id] }) {
                it[platform] = device.platform.name
                it[pushToken] = device.pushToken
                it[appVersion] = device.appVersion
                it[osVersion] = device.osVersion
                it[isActive] = true
                it[lastSeenAt] = now
            }
            existing[UserDevices.id]
        } else {
            UserDevices.insert {
                it[UserDevices.userId] = userId
                it[installationId] = device.installationId
                it[platform] = device.platform.name
                it[pushToken] = device.pushToken
                it[appVersion] = device.appVersion
                it[osVersion] = device.osVersion
                it[isActive] = true
                it[createdAt] = now
                it[lastSeenAt] = now
            }[UserDevices.id]
        }
    }

    private fun startSession(user: ResultRow, channel: Channel, deviceId: Long?, userAgent: String?): AuthResponse {
        val now = OffsetDateTime.now()
        val refreshToken = tokens.newRefreshToken()
        val sessionId = AuthSessions.insert {
            it[userId] = user[Users.id]
            it[clientType] = channel.clientType
            it[AuthSessions.deviceId] = deviceId
            it[refreshTokenHash] = tokens.hash(refreshToken)
            it[AuthSessions.userAgent] = userAgent?.take(500)
            it[createdAt] = now
            it[lastUsedAt] = now
            it[expiresAt] = now.plus(tokens.config.refreshTtl(channel))
        }[AuthSessions.id]
        Users.update({ Users.id eq user[Users.id] }) { it[lastSeenAt] = now }

        return AuthResponse(
            accessToken = tokens.accessToken(user[Users.id], user[Users.role], sessionId, channel),
            accessTokenExpiresIn = tokens.config.accessTokenTtl.seconds,
            refreshToken = refreshToken,
            user = user.toUserDto(),
        )
    }
}

fun ResultRow.toUserDto() = UserDto(
    id = this[Users.id],
    role = Role.valueOf(this[Users.role]),
    fullName = this[Users.fullName],
    phone = this[Users.phone],
    email = this[Users.email],
)
