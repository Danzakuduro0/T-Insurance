package ru.tinsurance.server.common

import io.ktor.server.config.*
import java.time.Duration

/** Пустая строка (переменная окружения не задана) — то же, что отсутствие значения. */
fun ApplicationConfig.stringOrNull(path: String): String? =
    propertyOrNull(path)?.getString()?.takeIf { it.isNotBlank() }

fun ApplicationConfig.string(path: String, default: String): String = stringOrNull(path) ?: default

fun ApplicationConfig.int(path: String, default: Int): Int = stringOrNull(path)?.toInt() ?: default

fun ApplicationConfig.long(path: String, default: Long): Long = stringOrNull(path)?.toLong() ?: default

fun ApplicationConfig.boolean(path: String, default: Boolean): Boolean = stringOrNull(path)?.toBoolean() ?: default

fun ApplicationConfig.float(path: String, default: Float): Float = stringOrNull(path)?.toFloat() ?: default

fun ApplicationConfig.seconds(path: String, default: Long): Duration = Duration.ofSeconds(long(path, default))

fun ApplicationConfig.minutes(path: String, default: Long): Duration = Duration.ofMinutes(long(path, default))

fun ApplicationConfig.days(path: String, default: Long): Duration = Duration.ofDays(long(path, default))

fun ApplicationConfig.millis(path: String, default: Long): Duration = Duration.ofMillis(long(path, default))

fun ApplicationConfig.list(path: String): List<String> = propertyOrNull(path)?.getList().orEmpty()
