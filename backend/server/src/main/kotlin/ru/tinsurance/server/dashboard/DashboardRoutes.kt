package ru.tinsurance.server.dashboard

import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import ru.tinsurance.api.OperatorApi
import ru.tinsurance.server.auth.AUTH_OPERATOR
import ru.tinsurance.server.auth.actor
import ru.tinsurance.server.auth.user

fun Route.dashboardRoutes(service: DashboardService) {
    authenticate(AUTH_OPERATOR) {
        get(OperatorApi.DASHBOARD) { call.respond(service.get(call.user.actor)) }
    }
}
