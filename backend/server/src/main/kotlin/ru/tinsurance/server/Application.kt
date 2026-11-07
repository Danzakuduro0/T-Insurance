package ru.tinsurance.server

import io.ktor.server.application.*
import io.ktor.server.netty.*
import io.ktor.util.*
import ru.tinsurance.server.auth.JwtConfig
import ru.tinsurance.server.auth.TokenService
import ru.tinsurance.server.auth.configureSecurity
import ru.tinsurance.server.common.appMode
import ru.tinsurance.server.common.appTimeZone
import ru.tinsurance.server.common.int
import ru.tinsurance.server.common.list
import ru.tinsurance.server.db.DatabaseConfig
import ru.tinsurance.server.db.configureDatabase
import ru.tinsurance.server.demo.DemoData
import ru.tinsurance.server.demo.DemoSettings
import ru.tinsurance.server.jobs.JobWorker
import ru.tinsurance.server.plugins.BodyLimits
import ru.tinsurance.server.plugins.configureHttp
import ru.tinsurance.server.plugins.configureRouting

fun main(args: Array<String>) = EngineMain.main(args)

/** Фоновый воркер; тесты запускают его вручную через runReady(). */
val JobWorkerKey = AttributeKey<JobWorker>("JobWorker")

fun Application.module() {
    val config = environment.config
    val mode = appMode
    log.info("Режим запуска: $mode")

    // проверки конфигурации до подключения к БД: в prod без секретов сервер не стартует
    val tokens = TokenService(JwtConfig.from(config, mode))
    val demoPassword = DemoSettings.demoPassword(config, mode)
    val database = configureDatabase(DatabaseConfig.from(config))
    demoPassword?.let { DemoData.seedIfEmpty(database, it) }

    val app = AppContainer(config, database, tokens, appTimeZone)
    configureHttp(config.list("cors.allowedHosts"), mode, BodyLimits.from(config))
    configureSecurity(tokens, config.int("security.authRequestsPerMinute", 30), app.auth::isSessionActive)
    configureRouting(app)
    startJobs(app)
}

private fun Application.startJobs(app: AppContainer) {
    log.info(
        app.analysisConfig.mlUrl?.let { "Анализ фото: ML-сервис $it" }
            ?: "Анализ фото: локальная проверка качества (ML_URL не задан, вид повреждения не определяется)"
    )
    attributes.put(JobWorkerKey, app.jobWorker)
    if (app.workerConfig.enabled) {
        val loop = app.jobWorker.start(this)
        monitor.subscribe(ApplicationStopping) { loop.cancel() }
    }
}
