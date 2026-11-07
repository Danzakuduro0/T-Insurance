package ru.tinsurance.server.auth

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.jdbc.Database
import ru.tinsurance.api.AuthResponse
import ru.tinsurance.api.MobileLoginRequest
import ru.tinsurance.api.OperatorLoginRequest
import ru.tinsurance.api.UserDto
import ru.tinsurance.server.common.UnauthorizedException
import ru.tinsurance.server.common.ValidationException
import ru.tinsurance.server.common.normalizePhone
import ru.tinsurance.server.common.tx
import ru.tinsurance.server.db.AuthSessions
import ru.tinsurance.server.db.Users
import ru.tinsurance.server.users.UserRepository
import ru.tinsurance.server.users.toUserDto
import java.time.OffsetDateTime

class AuthService(
    private val db: Database,
    private val tokens: TokenService,
    private val throttle: LoginThrottle,
) {

    suspend fun loginMobile(request: MobileLoginRequest, userAgent: String?): AuthResponse {
        if (request.device.installationId.isBlank()) throw ValidationException("Не указан installationId устройства")
        val phone = normalizePhone(request.phone)
        return withThrottle("MOBILE:$phone") {
            db.tx {
                val user = verified(UserRepository.findActiveClientByPhone(phone), request.password, "Неверный телефон или пароль")
                val deviceId = DeviceRepository.upsert(user[Users.id], request.device, OffsetDateTime.now())
                startSession(user, Channel.MOBILE, deviceId, userAgent)
            }
        }
    }

    suspend fun loginOperator(request: OperatorLoginRequest, userAgent: String?): AuthResponse {
        val email = request.email.trim().lowercase()
        return withThrottle("WEB:$email") {
            db.tx {
                val user = verified(UserRepository.findActiveStaffByEmail(email), request.password, "Неверный email или пароль")
                startSession(user, Channel.WEB, deviceId = null, userAgent)
            }
        }
    }

    /** Ротация: старый refresh-токен отзывается, выдаётся новая пара. */
    suspend fun refresh(refreshToken: String, channel: Channel, userAgent: String?): AuthResponse = db.tx {
        val now = OffsetDateTime.now()
        val session = SessionRepository.findActiveForUpdate(tokens.hash(refreshToken), channel, now)
            ?: throw UnauthorizedException("Сессия истекла, войдите заново")
        val user = UserRepository.findActive(session[AuthSessions.userId])
            ?: throw UnauthorizedException("Пользователь заблокирован")
        SessionRepository.revoke(session[AuthSessions.id], now)
        startSession(user, channel, session[AuthSessions.deviceId], userAgent)
    }

    suspend fun logout(sessionId: Long) = db.tx { SessionRepository.revoke(sessionId, OffsetDateTime.now()) }

    /** После выхода, смены refresh-токена или блокировки access-токен перестаёт работать сразу. */
    suspend fun isSessionActive(principal: UserPrincipal): Boolean = db.tx {
        SessionRepository.isActive(principal.sessionId, principal.userId, OffsetDateTime.now())
    }

    suspend fun me(userId: Long): UserDto = db.tx { UserRepository.get(userId) }

    private suspend fun <T> withThrottle(key: String, block: suspend () -> T): T {
        throttle.checkAllowed(key)
        val result = try {
            block()
        } catch (e: UnauthorizedException) {
            throttle.onFailure(key)
            throw e
        }
        throttle.onSuccess(key)
        return result
    }

    /** Несуществующий пользователь проверяется так же долго, как существующий. */
    private fun verified(user: ResultRow?, password: String, error: String): ResultRow {
        if (!PasswordHasher.verify(password, user?.get(Users.passwordHash))) throw UnauthorizedException(error)
        return user!!
    }

    private fun startSession(user: ResultRow, channel: Channel, deviceId: Long?, userAgent: String?): AuthResponse {
        val now = OffsetDateTime.now()
        val refreshToken = tokens.newRefreshToken()
        val sessionId = SessionRepository.create(
            user[Users.id], channel, deviceId, tokens.hash(refreshToken), userAgent,
            expiresAt = now.plus(tokens.config.refreshTtl(channel)), at = now,
        )
        UserRepository.touchLastSeen(user[Users.id], now)
        return AuthResponse(
            accessToken = tokens.accessToken(user[Users.id], user[Users.role].name, sessionId, channel),
            accessTokenExpiresIn = tokens.config.accessTokenTtl.seconds,
            refreshToken = refreshToken,
            user = user.toUserDto(),
        )
    }
}
