package ru.tinsurance.server.operator

import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import ru.tinsurance.api.OperatorApi
import ru.tinsurance.api.operator.ConfirmDecisionRequest
import ru.tinsurance.api.operator.OverrideDecisionRequest
import ru.tinsurance.server.auth.AUTH_OPERATOR
import ru.tinsurance.server.auth.AuthService
import ru.tinsurance.server.auth.Channel
import ru.tinsurance.server.auth.actor
import ru.tinsurance.server.auth.authRoutes
import ru.tinsurance.server.auth.user
import ru.tinsurance.server.common.longParam
import ru.tinsurance.server.files.FileAccessService
import ru.tinsurance.server.files.respondStoredFile

fun Route.operatorRoutes(
    auth: AuthService,
    claims: OperatorClaimService,
    decisions: DecisionReviewService,
    files: FileAccessService,
) {
    authRoutes(auth, Channel.WEB, OperatorApi.LOGIN, OperatorApi.REFRESH, OperatorApi.LOGOUT)

    authenticate(AUTH_OPERATOR) {
        get(OperatorApi.ME) { call.respond(auth.me(call.user.userId)) }
        get(OperatorApi.DIRECTIONS) { call.respond(claims.directions()) }
        get(OperatorApi.QUEUE) { call.respond(claims.queue(call.user.userId, QueueFilter.from(call.request.queryParameters))) }
        get(OperatorApi.CLAIM) { call.respond(claims.card(call.longParam("id"))) }
        get(OperatorApi.CLAIM_FILE_CONTENT) {
            call.respondStoredFile(files.openForOperator(call.longParam("id"), call.longParam("fileId")))
        }
        post(OperatorApi.CLAIM_DECISION_CONFIRM) {
            call.respond(decisions.confirm(call.user.actor, call.longParam("id"), call.receive<ConfirmDecisionRequest>()))
        }
        post(OperatorApi.CLAIM_DECISION_OVERRIDE) {
            call.respond(decisions.override(call.user.actor, call.longParam("id"), call.receive<OverrideDecisionRequest>()))
        }
    }
}
