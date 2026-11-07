package ru.tinsurance.server.demo

import io.ktor.server.config.*
import ru.tinsurance.server.common.AppMode
import ru.tinsurance.server.common.stringOrNull

/** Нужно ли создавать демо-данные и с каким паролем. null — не создавать. */
object DemoSettings {
    private const val MIN_PROD_PASSWORD_LENGTH = 10

    fun demoPassword(config: ApplicationConfig, mode: AppMode): String? {
        val dev = mode == AppMode.DEV
        val seed = config.stringOrNull("demo.seed")?.trim()?.toBoolean() ?: dev
        if (!seed) return null
        val password = config.stringOrNull("demo.password")
        if (dev) return password ?: DemoData.DEV_DEMO_PASSWORD
        require(password != null) { "DEMO_SEED=true в режиме prod требует DEMO_PASSWORD" }
        require(password.length >= MIN_PROD_PASSWORD_LENGTH && password != DemoData.DEV_DEMO_PASSWORD) {
            "DEMO_PASSWORD в режиме prod: не короче $MIN_PROD_PASSWORD_LENGTH символов и не dev-пароль"
        }
        return password
    }
}
