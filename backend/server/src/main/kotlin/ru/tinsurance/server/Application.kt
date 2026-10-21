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
import ru.tinsurance.server.analysis.AnalysisConfig
import ru.tinsurance.server.analysis.AnalyzeImageHandler
import ru.tinsurance.server.analysis.HttpImageAnalyzer
import ru.tinsurance.server.analysis.LocalImageAnalyzer
import ru.tinsurance.server.common.tx
import ru.tinsurance.server.jobs.JobWorker
import ru.tinsurance.server.jobs.WorkerConfig
import ru.tinsurance.server.routing.RouteClaimHandler
import ru.tinsurance.server.routing.RoutingConfig
import io.ktor.server.application.*
import io.ktor.server.netty.*
import io.ktor.util.*
import org.jetbrains.exposed.v1.jdbc.Database

fun main(args: Array<String>) = EngineMain.main(args)

/** Фоновый воркер приложения (тесты запускают его вручную через runReady()). */
val JobWorkerKey = AttributeKey<JobWorker>("JobWorker")

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
    val claimsConfig = ClaimsConfig.from(config)
    val filesConfig = FilesConfig.from(config)
    val storage = LocalFileStorage(filesConfig.storageDir)
    configureRouting(
        database = database,
        auth = authService,
        mobileClaims = MobileClaimService(database, claimsConfig),
        operatorClaims = OperatorClaimService(database, defaultTimeZone),
        files = FileService(database, storage, filesConfig),
    )
    configureJobs(database, storage, claimsConfig)
}

/** Шаг 3: фоновый анализ фото и маршрутизация обращения после анализа. */
private fun Application.configureJobs(database: Database, storage: LocalFileStorage, claimsConfig: ClaimsConfig) {
    val config = environment.config
    val analysis = AnalysisConfig.from(config)
    val analyzer = analysis.mlUrl
        ?.let { HttpImageAnalyzer(it, analysis.timeout, analysis.modelVersion) }
        ?: LocalImageAnalyzer(storage)
    log.info(
        if (analysis.mlUrl != null) "Анализ фото: ML-сервис ${analysis.mlUrl}"
        else "Анализ фото: локальная проверка качества (ML_URL не задан, вид повреждения не определяется)"
    )
    val workerConfig = WorkerConfig.from(config)
    val worker = JobWorker(
        database,
        handlers = listOf(
            AnalyzeImageHandler(database, analyzer, analysis),
            RouteClaimHandler(database, analysis, RoutingConfig.from(config), claimsConfig),
        ),
        config = workerConfig,
        maintenanceTasks = listOf { database.tx { RouteClaimHandler.scheduleMissing(this) } },
    )
    attributes.put(JobWorkerKey, worker)
    if (workerConfig.enabled) {
        val loop = worker.start(this)
        monitor.subscribe(ApplicationStopping) { loop.cancel() }
    }
}
