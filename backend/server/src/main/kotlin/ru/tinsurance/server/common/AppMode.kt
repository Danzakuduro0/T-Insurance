package ru.tinsurance.server.common

import io.ktor.server.application.*

/**
 * Режим запуска. DEV — локальная разработка: случайный JWT-секрет, если не задан, и демо-учётки с известным паролем.
 * PROD — всё остальное, по умолчанию: без JWT_SECRET сервер не стартует, демо-данные только по явному запросу.
 *
 * DEV включается переменной APP_MODE=dev или режимом разработки Ktor (-Dio.ktor.development=true —
 * его ставит запуск из IntelliJ и задача ./gradlew :server:run). Собранный jar/образ по умолчанию работает как PROD.
 */
enum class AppMode { DEV, PROD }

val Application.appMode: AppMode
    get() = when (environment.config.propertyOrNull("app.mode")?.getString()?.trim()?.lowercase()) {
        "dev" -> AppMode.DEV
        "prod" -> AppMode.PROD   // явно заданный режим сильнее флага разработки Ktor
        null, "" -> if (developmentMode) AppMode.DEV else AppMode.PROD
        else -> error("APP_MODE: допустимо dev или prod")
    }

/**
 * Часовой пояс для фильтров «за день» (app.timeZone, IANA: Europe/Moscow, Asia/Novosibirsk).
 * Не зависит от пояса машины: в Docker-контейнере это обычно UTC. Веб оператора может передать свой (?tz=).
 */
val Application.appTimeZone: java.time.ZoneId
    get() = environment.config.stringOrNull("app.timeZone")?.trim()
        ?.let { runCatching { java.time.ZoneId.of(it) }.getOrElse { _ -> error("APP_TIME_ZONE: неизвестный часовой пояс $it") } }
        ?: java.time.ZoneId.of("Europe/Moscow")

/** Пустая строка в конфиге (переменная окружения не задана) — то же, что отсутствие значения. */
fun io.ktor.server.config.ApplicationConfig.stringOrNull(path: String): String? =
    propertyOrNull(path)?.getString()?.takeIf { it.isNotBlank() }
