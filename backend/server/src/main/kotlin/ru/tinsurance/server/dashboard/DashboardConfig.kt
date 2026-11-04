package ru.tinsurance.server.dashboard

import io.ktor.server.config.ApplicationConfig
import ru.tinsurance.server.common.int

data class DashboardConfig(val queuedThreshold: Int, val overdueThreshold: Int) {
    init {
        require(queuedThreshold > 0 && overdueThreshold > 0) { "Dashboard thresholds must be positive" }
    }

    companion object {
        fun from(config: ApplicationConfig) = DashboardConfig(
            config.int("dashboard.queuedThreshold", 20), config.int("dashboard.overdueThreshold", 5),
        )
    }
}
