package ru.tinsurance.server.common

import io.ktor.http.*

/** Ошибка, которая отдаётся клиенту как есть: статус, код и понятное сообщение. */
open class ApiException(val status: HttpStatusCode, val code: String, message: String) : RuntimeException(message)

class NotFoundException(message: String = "Не найдено") : ApiException(HttpStatusCode.NotFound, "NOT_FOUND", message)
class UnauthorizedException(message: String) : ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", message)
class ValidationException(message: String) : ApiException(HttpStatusCode.BadRequest, "VALIDATION_ERROR", message)
class ConflictException(message: String) : ApiException(HttpStatusCode.Conflict, "CONFLICT", message)
