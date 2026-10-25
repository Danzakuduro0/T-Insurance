package ru.tinsurance.server.assignment

import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import ru.tinsurance.api.OperatorApi
import ru.tinsurance.api.operator.AssignClaimRequest
import ru.tinsurance.api.operator.AvailabilityRequest
import ru.tinsurance.api.operator.ReleaseClaimRequest
import ru.tinsurance.server.auth.actor
import ru.tinsurance.server.auth.user
import ru.tinsurance.server.common.longParam

/** Вызывается внутри authenticate(AUTH_OPERATOR). */
fun Route.assignmentRoutes(assignments: AssignmentService) {
    get(OperatorApi.OPERATORS) { call.respond(assignments.operators(call.user.actor)) }
    put(OperatorApi.ME_AVAILABILITY) {
        call.respond(assignments.setAvailability(call.user.actor, call.receive<AvailabilityRequest>()))
    }
    post(OperatorApi.CLAIM_TAKE) { call.respond(assignments.take(call.user.actor, call.longParam("id"))) }
    post(OperatorApi.CLAIM_START_REVIEW) { call.respond(assignments.startReview(call.user.actor, call.longParam("id"))) }
    post(OperatorApi.CLAIM_RELEASE) {
        call.respond(assignments.release(call.user.actor, call.longParam("id"), call.receive<ReleaseClaimRequest>()))
    }
    post(OperatorApi.CLAIM_ASSIGN) {
        call.respond(assignments.assign(call.user.actor, call.longParam("id"), call.receive<AssignClaimRequest>()))
    }
}
