package ru.tinsurance.server.common

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Ошибка, которая отдаётся клиенту как есть: статус + код + понятное сообщение. */
open class ApiException(val status: HttpStatusCode, val code: String, message: String) : RuntimeException(message)

class NotFoundException(message: String = "Не найдено") : ApiException(HttpStatusCode.NotFound, "NOT_FOUND", message)
class UnauthorizedException(message: String) : ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", message)
class ValidationException(message: String) : ApiException(HttpStatusCode.BadRequest, "VALIDATION_ERROR", message)
class ConflictException(message: String) : ApiException(HttpStatusCode.Conflict, "CONFLICT", message)

/** Транзакция БД вне потоков обработки запросов (JDBC блокирующий). */
suspend fun <T> Database.tx(block: JdbcTransaction.() -> T): T =
    withContext(Dispatchers.IO) { transaction(this@tx) { block() } }

fun OffsetDateTime.iso(): String = format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

fun parseDateTime(value: String, field: String): OffsetDateTime = try {
    OffsetDateTime.parse(value)
} catch (e: DateTimeParseException) {
    throw ValidationException("Поле $field: ожидается дата в формате ISO-8601, например 2026-10-10T12:00:00+07:00")
}

fun RoutingCall.longParam(name: String): Long =
    parameters[name]?.toLongOrNull() ?: throw NotFoundException()

/** Телефон приводится к виду +7XXXXXXXXXX, чтобы «8 900 …» и «+7 (900) …» были одним номером. */
fun normalizePhone(raw: String): String {
    val digits = raw.filter { it.isDigit() }
    return when {
        digits.length == 11 && digits.startsWith("8") -> "+7" + digits.drop(1)
        digits.length == 10 -> "+7$digits"
        else -> "+$digits"
    }
}
