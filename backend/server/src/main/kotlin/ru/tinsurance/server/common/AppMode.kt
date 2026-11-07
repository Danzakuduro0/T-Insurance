package ru.tinsurance.server.common

import io.ktor.server.application.*
import java.time.ZoneId

/**
 * DEV — локальная разработка: случайный JWT-секрет и демо-учётки. Включается APP_MODE=dev
 * или флагом -Dio.ktor.development=true (запуск из IntelliJ и ./gradlew :server:run).
 * PROD — всё остальное: без JWT_SECRET сервер не стартует.
 */
enum class AppMode { DEV, PROD }

val Application.appMode: AppMode
    get() = when (environment.config.stringOrNull("app.mode")?.trim()?.lowercase()) {
        "dev" -> AppMode.DEV
        "prod" -> AppMode.PROD
        null -> if (developmentMode) AppMode.DEV else AppMode.PROD
        else -> error("APP_MODE: допустимо dev или prod")
    }

/** Пояс для фильтров «за день», если веб оператора не передал свой (?tz=). */
val Application.appTimeZone: ZoneId
    get() = environment.config.stringOrNull("app.timeZone")?.trim()
        ?.let { runCatching { ZoneId.of(it) }.getOrElse { _ -> error("APP_TIME_ZONE: неизвестный часовой пояс $it") } }
        ?: ZoneId.of("Europe/Moscow")
