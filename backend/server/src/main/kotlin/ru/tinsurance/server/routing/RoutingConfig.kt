package ru.tinsurance.server.routing

import io.ktor.server.config.*
import ru.tinsurance.server.common.float

data class RoutingConfig(
    /** Уверенность модели ниже порога — ручная проверка. */
    val confidenceThreshold: Float,
) {
    companion object {
        fun from(config: ApplicationConfig) = RoutingConfig(
            confidenceThreshold = config.float("routing.confidenceThreshold", 0.65f),
        )
    }
}
