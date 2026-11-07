package ru.tinsurance.server.auth

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import ru.tinsurance.api.MobileLoginRequest
import ru.tinsurance.api.OperatorLoginRequest
import ru.tinsurance.api.RefreshRequest

/** Вход, обновление токена и выход — с лимитом частоты запросов с одного адреса. */
fun Route.authRoutes(auth: AuthService, channel: Channel, loginPath: String, refreshPath: String, logoutPath: String) =
    rateLimit(AUTH_RATE_LIMIT) {
        post(loginPath) {
            val userAgent = call.request.userAgent()
            call.respond(
                when (channel) {
                    Channel.MOBILE -> auth.loginMobile(call.receive<MobileLoginRequest>(), userAgent)
                    Channel.WEB -> auth.loginOperator(call.receive<OperatorLoginRequest>(), userAgent)
                }
            )
        }
        post(refreshPath) {
            call.respond(auth.refresh(call.receive<RefreshRequest>().refreshToken, channel, call.request.userAgent()))
        }
        authenticate(channel.authProvider) {
            post(logoutPath) {
                auth.logout(call.user.sessionId)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
