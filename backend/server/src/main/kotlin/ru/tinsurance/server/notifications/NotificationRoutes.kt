package ru.tinsurance.server.notifications

import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import ru.tinsurance.api.MobileApi
import ru.tinsurance.server.auth.AUTH_MOBILE
import ru.tinsurance.server.auth.user
import ru.tinsurance.server.common.*

fun Route.notificationRoutes(service: NotificationService) {
    authenticate(AUTH_MOBILE) {
        get(MobileApi.NOTIFICATIONS) {
            val q = call.request.queryParameters
            val limit = q["limit"]?.let { it.toIntOrNull() ?: throw ValidationException("Некорректный limit") } ?: 20
            val before = q["beforeId"]?.let { it.toLongOrNull() ?: throw ValidationException("Некорректный beforeId") }
            val unread = q["unreadOnly"]?.let { it.toBooleanStrictOrNull() ?: throw ValidationException("Некорректный unreadOnly") } ?: false
            call.respond(service.list(call.user.userId, before, limit, unread))
        }
        post(MobileApi.NOTIFICATION_READ) {
            call.respond(service.markRead(call.user.userId, call.longParam("id")))
        }
    }
}
