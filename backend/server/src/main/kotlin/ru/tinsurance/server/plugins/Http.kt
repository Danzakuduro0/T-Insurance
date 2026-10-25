package ru.tinsurance.server.plugins

import ru.tinsurance.api.ErrorResponse
import ru.tinsurance.server.auth.TooManyAttemptsException
import ru.tinsurance.server.common.ApiException
import ru.tinsurance.server.common.AppMode
import ru.tinsurance.server.common.long
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.bodylimit.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException

/** Лимиты размера тела запроса: обычный JSON и загрузка файла (multipart). */
data class BodyLimits(val jsonBytes: Long, val uploadBytes: Long) {
    companion object {
        fun from(config: ApplicationConfig) = BodyLimits(
            jsonBytes = config.long("security.maxJsonBodyBytes", 256L * 1024),
            uploadBytes = config.long("files.maxUploadRequestBytes", 21L * 1024 * 1024),
        )
    }
}

/** Загрузка файла — единственный запрос, которому разрешено большое тело. */
fun ApplicationCall.isFileUpload() =
    request.httpMethod == HttpMethod.Post && request.path().endsWith("/files")

fun Application.configureHttp(corsAllowedHosts: List<String>, mode: AppMode, limits: BodyLimits) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    install(CallLogging)

    install(RequestBodyLimit) {
        bodyLimit { call -> if (call.isFileUpload()) limits.uploadBytes else limits.jsonBytes }
    }

    // веб оператора открывается с другого адреса (dev-сервер); мобильному приложению CORS не нужен
    install(CORS) {
        corsAllowedHosts.forEach { allowHost(it, schemes = listOf("http", "https")) }
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
    }

    install(StatusPages) {
        exception<TooManyAttemptsException> { call, cause ->
            call.response.header(HttpHeaders.RetryAfter, cause.retryAfterSeconds.toString())
            call.respond(cause.status, ErrorResponse(cause.code, cause.message))
        }
        exception<ApiException> { call, cause ->
            call.respond(cause.status, ErrorResponse(cause.code, cause.message))
        }
        exception<PayloadTooLargeException> { call, _ ->
            call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("PAYLOAD_TOO_LARGE", "Слишком большой запрос"))
        }
        exception<BadRequestException> { call, cause ->
            // детали разбора JSON — только в dev
            val detail = if (mode == AppMode.DEV) cause.rootMessage() else null
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("BAD_REQUEST", detail ?: "Некорректный запрос: проверьте формат и обязательные поля"))
        }
        // нарушение правил БД: клиенту — понятный текст без имён таблиц, подробности — в лог
        exception<ExposedSQLException> { call, cause ->
            val (status, code, message) = when (cause.sqlState) {
                "23514" -> Triple(HttpStatusCode.Conflict, "RULE_VIOLATION", "Действие нарушает правила обработки обращения")
                "42501" -> Triple(HttpStatusCode.Forbidden, "FORBIDDEN_TRANSITION", "Недостаточно прав для этого действия")
                "23505" -> Triple(HttpStatusCode.Conflict, "DUPLICATE", "Такая запись уже существует")
                "23503" -> Triple(HttpStatusCode.BadRequest, "INVALID_REFERENCE", "Ссылка на несуществующий объект")
                else -> Triple(HttpStatusCode.InternalServerError, "INTERNAL_ERROR", null)
            }
            if (status == HttpStatusCode.InternalServerError) {
                call.application.environment.log.error("Database error", cause)
            } else {
                call.application.environment.log.warn("Rejected by database rule [${cause.sqlState}]: ${cause.cause?.message?.lineSequence()?.firstOrNull()}")
            }
            call.respond(status, ErrorResponse(code, message))
        }
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled error", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("INTERNAL_ERROR"))
        }
    }
}

private fun Throwable.rootMessage(): String? {
    var e: Throwable = this
    while (e.cause != null && e.cause !== e) e = e.cause!!
    return e.message
}
