package ru.tinsurance.server.common

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.uuid.Uuid

fun OffsetDateTime.iso(): String = format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

fun parseDateTime(value: String, field: String): OffsetDateTime = try {
    OffsetDateTime.parse(value)
} catch (e: DateTimeParseException) {
    throw ValidationException("Поле $field: ожидается дата в формате ISO-8601, например 2026-10-10T12:00:00+07:00")
}

fun parseDate(value: String, field: String): LocalDate =
    runCatching { LocalDate.parse(value) }.getOrElse { throw ValidationException("$field: ожидается дата ГГГГ-ММ-ДД") }

fun parseZone(value: String, field: String): ZoneId =
    runCatching { ZoneId.of(value) }.getOrElse { throw ValidationException("$field: ожидается часовой пояс, например Europe/Moscow") }

fun parseRequestId(value: String?): Uuid? = value?.takeIf { it.isNotBlank() }?.let {
    runCatching { Uuid.parse(it) }.getOrElse { throw ValidationException("clientRequestId должен быть UUID") }
}

/** «8 900 …» и «+7 (900) …» — один номер: +7XXXXXXXXXX. */
fun normalizePhone(raw: String): String {
    val digits = raw.filter { it.isDigit() }
    return when {
        digits.length == 11 && digits.startsWith("8") -> "+7" + digits.drop(1)
        digits.length == 10 -> "+7$digits"
        else -> "+$digits"
    }
}

/** Шаблон LIKE «содержит» без подстановочных символов из пользовательского ввода. */
fun containsPattern(query: String): String =
    "%" + query.lowercase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
