package ru.tinsurance.server.mobile

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import ru.tinsurance.api.MobileApi
import ru.tinsurance.api.mobile.CreateClaimRequest
import ru.tinsurance.api.mobile.UpdateClaimRequest
import ru.tinsurance.server.auth.AUTH_MOBILE
import ru.tinsurance.server.auth.AuthService
import ru.tinsurance.server.auth.Channel
import ru.tinsurance.server.auth.authRoutes
import ru.tinsurance.server.auth.user
import ru.tinsurance.server.common.longParam
import ru.tinsurance.server.files.FileAccessService
import ru.tinsurance.server.files.UploadService
import ru.tinsurance.server.files.receiveUpload
import ru.tinsurance.server.files.respondStoredFile

fun Route.mobileRoutes(auth: AuthService, claims: MobileClaimService, uploads: UploadService, files: FileAccessService) {
    authRoutes(auth, Channel.MOBILE, MobileApi.LOGIN, MobileApi.REFRESH, MobileApi.LOGOUT)

    authenticate(AUTH_MOBILE) {
        get(MobileApi.ME) { call.respond(auth.me(call.user.userId)) }
        get(MobileApi.POLICIES) { call.respond(claims.activePolicies(call.user.userId)) }
        get(MobileApi.EVENT_TYPES) { call.respond(claims.eventTypes()) }

        get(MobileApi.CLAIMS) { call.respond(claims.list(call.user.userId)) }
        post(MobileApi.CLAIMS) {
            val (claim, created) = claims.createDraft(call.user.userId, call.receive<CreateClaimRequest>())
            call.respond(if (created) HttpStatusCode.Created else HttpStatusCode.OK, claim)
        }
        get(MobileApi.CLAIM) { call.respond(claims.details(call.user.userId, call.longParam("id"))) }
        patch(MobileApi.CLAIM) {
            call.respond(claims.update(call.user.userId, call.longParam("id"), call.receive<UpdateClaimRequest>()))
        }
        get(MobileApi.CLAIM_COMPLETENESS) { call.respond(claims.completeness(call.user.userId, call.longParam("id"))) }
        post(MobileApi.CLAIM_SUBMIT) { call.respond(claims.submit(call.user.userId, call.longParam("id"))) }
        get(MobileApi.CLAIM_TIMELINE) { call.respond(claims.timeline(call.user.userId, call.longParam("id"))) }

        // multipart: documentType, file, [clientRequestId], [replacesFileId]; 201 принят, 200 дубль/повтор, 422 отклонён
        post(MobileApi.CLAIM_FILES) {
            val claimId = call.longParam("id")
            uploads.checkCanUpload(call.user.userId, claimId)
            val result = call.receiveUpload(uploads.maxFileBytes()).use { form -> uploads.upload(call.user.userId, claimId, form) }
            call.respond(result.outcome.status, result.file)
        }
        get(MobileApi.CLAIM_FILE_CONTENT) {
            call.respondStoredFile(files.openForClient(call.user.userId, call.longParam("id"), call.longParam("fileId")))
        }
    }
}
