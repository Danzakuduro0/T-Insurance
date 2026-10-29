package ru.tinsurance.server.operator.actions

import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import ru.tinsurance.api.OperatorApi
import ru.tinsurance.api.operator.*
import ru.tinsurance.server.auth.actor
import ru.tinsurance.server.auth.user
import ru.tinsurance.server.common.longParam

fun Route.operatorActionRoutes(actions: OperatorActionsService) {
    post(OperatorApi.CLAIM_REQUEST_MATERIALS) {
        call.respond(actions.requestMaterials(call.user.actor, call.longParam("id"), call.receive<RequestMaterialsRequest>()))
    }
    put(OperatorApi.CLAIM_REVIEW_RESULT) {
        call.respond(actions.reviewResult(call.user.actor, call.longParam("id"), call.receive<ReviewResultRequest>()))
    }
    post(OperatorApi.CLAIM_COMPLETE) {
        call.respond(actions.complete(call.user.actor, call.longParam("id")))
    }
    post(OperatorApi.CLAIM_CANCEL) {
        call.respond(actions.cancel(call.user.actor, call.longParam("id"), call.receive<CancelClaimRequest>()))
    }
}
