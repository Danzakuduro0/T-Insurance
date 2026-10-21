package ru.tinsurance.server.mobile

import ru.tinsurance.api.CreateClaimRequest
import ru.tinsurance.api.UpdateClaimRequest
import ru.tinsurance.server.auth.AUTH_MOBILE
import ru.tinsurance.server.auth.AuthService
import ru.tinsurance.server.auth.mobileAuthRoutes
import ru.tinsurance.server.auth.user
import ru.tinsurance.server.common.longParam
import ru.tinsurance.server.files.FileService
import ru.tinsurance.server.files.receiveUpload
import ru.tinsurance.server.files.respondStoredFile
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/** Всё, что вызывает мобильное приложение клиента: /api/mobile/... */
fun Route.mobileRoutes(auth: AuthService, claims: MobileClaimService, files: FileService) = route("/api/mobile") {
    mobileAuthRoutes(auth)

    authenticate(AUTH_MOBILE) {
        get("/me") { call.respond(auth.me(call.user.userId)) }

        get("/policies") { call.respond(claims.activePolicies(call.user.userId)) }

        // типы событий с обязательными полями и документами — по ним строится форма (вопрос 7)
        get("/event-types") { call.respond(claims.eventTypes()) }

        route("/claims") {
            get { call.respond(claims.list(call.user.userId)) }

            post {
                val (claim, created) = claims.createDraft(call.user.userId, call.receive<CreateClaimRequest>())
                call.respond(if (created) HttpStatusCode.Created else HttpStatusCode.OK, claim)
            }

            get("/{id}") { call.respond(claims.details(call.user.userId, call.longParam("id"))) }

            patch("/{id}") {
                call.respond(claims.update(call.user.userId, call.longParam("id"), call.receive<UpdateClaimRequest>()))
            }

            // чек-лист «чего не хватает» — тот же расчёт, что при отправке, но без её последствий
            get("/{id}/completeness") { call.respond(claims.completeness(call.user.userId, call.longParam("id"))) }

            post("/{id}/submit") { call.respond(claims.submit(call.user.userId, call.longParam("id"))) }

            get("/{id}/timeline") { call.respond(claims.timeline(call.user.userId, call.longParam("id"))) }

            // Загрузка материала: multipart с полями documentType, file, [clientRequestId], [replacesFileId].
            // 201 — принят, 200 — дубль или повтор запроса, 422 — отклонён (причина и подсказка в ответе), 413 — слишком большой
            post("/{id}/files") {
                val claimId = call.longParam("id")
                files.checkCanUpload(call.user.userId, claimId)   // права и статус — до чтения тела
                val result = call.receiveUpload(files.maxFileBytes()).use { form -> files.upload(call.user.userId, claimId, form) }
                call.respond(result.outcome.status, result.file)
            }

            get("/{id}/files/{fileId}/content") {
                call.respondStoredFile(files.openForClient(call.user.userId, call.longParam("id"), call.longParam("fileId")))
            }
        }
    }
}
