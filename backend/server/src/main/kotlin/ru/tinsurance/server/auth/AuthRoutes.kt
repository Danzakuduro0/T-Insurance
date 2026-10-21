package ru.tinsurance.server.auth

import ru.tinsurance.api.MobileLoginRequest
import ru.tinsurance.api.OperatorLoginRequest
import ru.tinsurance.api.RefreshRequest
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/** /api/mobile/auth/... — с лимитом частоты запросов с одного адреса */
fun Route.mobileAuthRoutes(auth: AuthService) = route("/auth") {
    rateLimit(AUTH_RATE_LIMIT) {
        post("/login") {
            call.respond(auth.loginMobile(call.receive<MobileLoginRequest>(), call.request.userAgent()))
        }
        post("/refresh") {
            call.respond(auth.refresh(call.receive<RefreshRequest>().refreshToken, Channel.MOBILE, call.request.userAgent()))
        }
        authenticate(AUTH_MOBILE) {
            post("/logout") {
                auth.logout(call.user.sessionId)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

/** /api/operator/auth/... — с лимитом частоты запросов с одного адреса */
fun Route.operatorAuthRoutes(auth: AuthService) = route("/auth") {
    rateLimit(AUTH_RATE_LIMIT) {
        post("/login") {
            call.respond(auth.loginOperator(call.receive<OperatorLoginRequest>(), call.request.userAgent()))
        }
        post("/refresh") {
            call.respond(auth.refresh(call.receive<RefreshRequest>().refreshToken, Channel.WEB, call.request.userAgent()))
        }
        authenticate(AUTH_OPERATOR) {
            post("/logout") {
                auth.logout(call.user.sessionId)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
