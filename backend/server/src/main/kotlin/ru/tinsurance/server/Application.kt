package ru.tinsurance.server

import ru.tinsurance.server.auth.AuthService
import ru.tinsurance.server.auth.JwtConfig
import ru.tinsurance.server.auth.LoginThrottle
import ru.tinsurance.server.auth.LoginThrottleConfig
import ru.tinsurance.server.auth.TokenService
import ru.tinsurance.server.auth.configureSecurity
import ru.tinsurance.server.claims.ClaimsConfig
import ru.tinsurance.server.common.appMode
import ru.tinsurance.server.common.appTimeZone
import ru.tinsurance.server.db.DatabaseConfig
import ru.tinsurance.server.db.configureDatabase
import ru.tinsurance.server.demo.DemoData
import ru.tinsurance.server.demo.DemoSettings
import ru.tinsurance.server.files.FileService
import ru.tinsurance.server.files.FilesConfig
import ru.tinsurance.server.files.LocalFileStorage
import ru.tinsurance.server.mobile.MobileClaimService
import ru.tinsurance.server.operator.OperatorClaimService
import ru.tinsurance.server.plugins.BodyLimits
import ru.tinsurance.server.plugins.configureHttp
import ru.tinsurance.server.plugins.configureRouting
import io.ktor.server.application.*
import io.ktor.server.netty.*

fun main(args: Array<String>) = EngineMain.main(args)

// Подключается из application.yaml (ktor.application.modules)
fun Application.module() {
    val config = environment.config
    val mode = appMode
    val defaultTimeZone = appTimeZone
    log.info("Режим запуска: $mode")

    // Проверки конфигурации до подключения к БД: в prod без секретов сервер не стартует
    val tokens = TokenService(JwtConfig.from(config, mode))
    val demoPassword = DemoSettings.demoPassword(config, mode)

    val database = configureDatabase(DatabaseConfig.from(config))
    demoPassword?.let { DemoData.seedIfEmpty(database, it) }

    val authService = AuthService(database, tokens, LoginThrottle(LoginThrottleConfig.from(config)))

    configureHttp(config.propertyOrNull("cors.allowedHosts")?.getList().orEmpty(), mode, BodyLimits.from(config))
    configureSecurity(
        tokens,
        authRequestsPerMinute = config.propertyOrNull("security.authRequestsPerMinute")?.getString()?.toInt() ?: 30,
        sessionActive = authService::isSessionActive,
    )
    configureRouting(
        database = database,
        auth = authService,
        mobileClaims = MobileClaimService(database, ClaimsConfig.from(config)),
        operatorClaims = OperatorClaimService(database, defaultTimeZone),
        files = FilesConfig.from(config).let { FileService(database, LocalFileStorage(it.storageDir), it) },
    )
}
