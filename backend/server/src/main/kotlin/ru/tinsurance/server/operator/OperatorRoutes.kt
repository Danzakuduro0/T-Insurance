package ru.tinsurance.server.operator

import ru.tinsurance.server.auth.AUTH_OPERATOR
import ru.tinsurance.server.auth.AuthService
import ru.tinsurance.server.auth.operatorAuthRoutes
import ru.tinsurance.server.auth.user
import ru.tinsurance.server.common.ValidationException
import ru.tinsurance.server.common.longParam
import ru.tinsurance.server.files.FileService
import ru.tinsurance.server.files.respondStoredFile
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/** Всё, что вызывает веб-интерфейс оператора: /api/operator/... */
fun Route.operatorRoutes(auth: AuthService, claims: OperatorClaimService, files: FileService) = route("/api/operator") {
    operatorAuthRoutes(auth)

    authenticate(AUTH_OPERATOR) {
        get("/me") { call.respond(auth.me(call.user.userId)) }

        // GET /api/operator/queue?status=QUEUED&status=IN_REVIEW&direction=STANDARD&minPriority=2&eventType=CAR_ACCIDENT
        //     &assigned=me|none&assignedTo=5&createdFrom=2026-10-01&createdTo=2026-10-14&tz=Asia/Novosibirsk
        //     &overdue=true&manualReview=true&search=TS-00&limit=50&offset=0
        get("/queue") {
            call.respond(claims.queue(call.user.userId, call.request.queryParameters.toQueueFilter()))
        }

        get("/claims/{id}") { call.respond(claims.card(call.longParam("id"))) }

        get("/claims/{id}/files/{fileId}/content") {
            call.respondStoredFile(files.openForOperator(call.longParam("id"), call.longParam("fileId")))
        }
    }
}

private fun Parameters.toQueueFilter(): QueueFilter {
    val assigned = this["assigned"]
    if (assigned != null && assigned !in setOf("me", "none")) throw ValidationException("assigned: допустимо me или none")
    return QueueFilter(
        statuses = getAll("status").orEmpty().flatMap { it.split(',') }.map { it.trim().uppercase() }.filter { it.isNotEmpty() },
        direction = this["direction"],
        minPriority = this["minPriority"]?.toIntOrNull(),
        assigned = assigned,
        assignedTo = this["assignedTo"]?.let { it.toLongOrNull() ?: throw ValidationException("assignedTo: ожидается id сотрудника") },
        eventType = this["eventType"],
        createdFrom = this["createdFrom"]?.let { parseDate(it, "createdFrom") },
        createdTo = this["createdTo"]?.let { parseDate(it, "createdTo") },
        // браузер знает пояс оператора: Intl.DateTimeFormat().resolvedOptions().timeZone
        zone = this["tz"]?.let { tz ->
            runCatching { java.time.ZoneId.of(tz) }.getOrElse { throw ValidationException("tz: ожидается часовой пояс, например Europe/Moscow") }
        },
        overdueOnly = this["overdue"] == "true",
        manualReviewOnly = this["manualReview"] == "true",
        search = this["search"],
        limit = (this["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200),
        offset = (this["offset"]?.toLongOrNull() ?: 0).coerceAtLeast(0),
    )
}

private fun parseDate(value: String, field: String): java.time.LocalDate =
    runCatching { java.time.LocalDate.parse(value) }.getOrElse { throw ValidationException("$field: ожидается дата ГГГГ-ММ-ДД") }
