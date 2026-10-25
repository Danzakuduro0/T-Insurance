package ru.tinsurance.server.auth

import ru.tinsurance.api.ErrorResponse
import ru.tinsurance.api.Role
import ru.tinsurance.server.claims.Actor
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlin.time.Duration.Companion.minutes

const val AUTH_MOBILE = "mobile"
const val AUTH_OPERATOR = "operator"

data class UserPrincipal(val userId: Long, val role: Role, val sessionId: Long)

/** Лимит частоты для /auth/… — от перебора паролей по многим логинам с одного адреса. */
val AUTH_RATE_LIMIT = RateLimitName("auth")

/** [sessionActive] — проверка по БД: подпись JWT сама по себе не знает о выходе пользователя. */
fun Application.configureSecurity(
    tokens: TokenService,
    authRequestsPerMinute: Int,
    sessionActive: suspend (UserPrincipal) -> Boolean,
) {
    install(RateLimit) {
        register(AUTH_RATE_LIMIT) {
            rateLimiter(limit = authRequestsPerMinute, refillPeriod = 1.minutes)
            requestKey { call -> call.request.origin.remoteAddress }
        }
    }
    install(Authentication) {
        jwt(AUTH_MOBILE) {
            verifier(tokens.verifier(Channel.MOBILE))
            validate { credential -> credential.toPrincipal(setOf(Role.CLIENT))?.takeIf { sessionActive(it) } }
            challenge { _, _ -> call.respondUnauthorized() }
        }
        jwt(AUTH_OPERATOR) {
            verifier(tokens.verifier(Channel.WEB))
            validate { credential -> credential.toPrincipal(setOf(Role.OPERATOR, Role.ADMIN))?.takeIf { sessionActive(it) } }
            challenge { _, _ -> call.respondUnauthorized() }
        }
    }
}

private fun JWTCredential.toPrincipal(allowed: Set<Role>): UserPrincipal? {
    val role = payload.getClaim(TokenService.CLAIM_ROLE).asString()
        ?.let { runCatching { Role.valueOf(it) }.getOrNull() } ?: return null
    if (role !in allowed) return null
    return UserPrincipal(
        userId = payload.subject?.toLongOrNull() ?: return null,
        role = role,
        sessionId = payload.getClaim(TokenService.CLAIM_SESSION).asLong() ?: return null,
    )
}

private suspend fun ApplicationCall.respondUnauthorized() =
    respond(HttpStatusCode.Unauthorized, ErrorResponse("UNAUTHORIZED", "Требуется вход"))

val UserPrincipal.actor get() = Actor.user(role, userId)

val RoutingCall.user: UserPrincipal
    get() = principal<UserPrincipal>() ?: error("Маршрут не защищён authenticate()")
