package ru.tinsurance.server

import io.ktor.server.config.*
import org.jetbrains.exposed.v1.jdbc.Database
import ru.tinsurance.server.analysis.AnalysisConfig
import ru.tinsurance.server.analysis.AnalyzeImageHandler
import ru.tinsurance.server.analysis.HttpImageAnalyzer
import ru.tinsurance.server.analysis.ImageAnalyzer
import ru.tinsurance.server.analysis.LocalImageAnalyzer
import ru.tinsurance.server.auth.AuthService
import ru.tinsurance.server.auth.LoginThrottle
import ru.tinsurance.server.auth.LoginThrottleConfig
import ru.tinsurance.server.auth.TokenService
import ru.tinsurance.server.claims.ClaimsConfig
import ru.tinsurance.server.common.tx
import ru.tinsurance.server.files.FileAccessService
import ru.tinsurance.server.files.FilesConfig
import ru.tinsurance.server.files.LocalFileStorage
import ru.tinsurance.server.files.UploadService
import ru.tinsurance.server.jobs.JobWorker
import ru.tinsurance.server.jobs.WorkerConfig
import ru.tinsurance.server.mobile.MobileClaimService
import ru.tinsurance.server.operator.OperatorClaimService
import ru.tinsurance.server.routing.RouteClaimHandler
import ru.tinsurance.server.routing.RoutingConfig
import java.time.ZoneId

/** Все сервисы приложения — создаются один раз и передаются через конструкторы. */
class AppContainer(
    config: ApplicationConfig,
    val database: Database,
    val tokens: TokenService,
    timeZone: ZoneId,
) {
    private val claimsConfig = ClaimsConfig.from(config)
    private val filesConfig = FilesConfig.from(config)
    private val storage = LocalFileStorage(filesConfig.storageDir)

    val auth = AuthService(database, tokens, LoginThrottle(LoginThrottleConfig.from(config)))
    val mobileClaims = MobileClaimService(database, claimsConfig)
    val operatorClaims = OperatorClaimService(database, timeZone)
    val uploads = UploadService(database, storage, filesConfig)
    val fileAccess = FileAccessService(database, storage)

    val analysisConfig = AnalysisConfig.from(config)
    val workerConfig = WorkerConfig.from(config)
    val jobWorker = JobWorker(
        database,
        handlers = listOf(
            AnalyzeImageHandler(database, imageAnalyzer(), analysisConfig),
            RouteClaimHandler(database, analysisConfig, RoutingConfig.from(config), claimsConfig),
        ),
        config = workerConfig,
        maintenanceTasks = listOf { database.tx { RouteClaimHandler.scheduleMissing(this) } },
    )

    /** Без ML_URL — локальная проверка качества, вид повреждения не определяется. */
    private fun imageAnalyzer(): ImageAnalyzer =
        analysisConfig.mlUrl?.let { HttpImageAnalyzer(it, analysisConfig.timeout, analysisConfig.modelVersion) }
            ?: LocalImageAnalyzer(storage)
}
