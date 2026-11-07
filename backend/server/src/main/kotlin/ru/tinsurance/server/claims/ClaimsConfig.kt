package ru.tinsurance.server.claims

import io.ktor.server.config.*
import ru.tinsurance.server.common.long

data class ClaimsConfig(
    /** Сколько дней у клиента на дозагрузку материалов. */
    val infoRequestDueDays: Long,
) {
    companion object {
        val DEFAULT = ClaimsConfig(infoRequestDueDays = 7)

        fun from(config: ApplicationConfig) = ClaimsConfig(
            infoRequestDueDays = config.long("claims.infoRequestDueDays", DEFAULT.infoRequestDueDays),
        )
    }
}
