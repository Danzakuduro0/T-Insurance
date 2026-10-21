package ru.tinsurance.server.plugins

import ru.tinsurance.server.auth.AuthService
import ru.tinsurance.server.common.tx
import ru.tinsurance.server.files.FileService
import ru.tinsurance.server.mobile.MobileClaimService
import ru.tinsurance.server.mobile.mobileRoutes
import ru.tinsurance.server.operator.OperatorClaimService
import ru.tinsurance.server.operator.operatorRoutes
import ru.tinsurance.core.sayHello
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.Database

@Serializable
data class HealthResponse(val status: String, val schemaVersion: String?)

fun Application.configureRouting(
    database: Database,
    auth: AuthService,
    mobileClaims: MobileClaimService,
    operatorClaims: OperatorClaimService,
    files: FileService,
) {
    routing {
        get("/") {
            call.respondText(sayHello("Ktor"))
        }
        get("/health") {
            val schemaVersion = database.tx {
                exec("select max(version::int)::text from flyway_schema_history where success") { rs ->
                    if (rs.next()) rs.getString(1) else null
                }
            }
            call.respond(HealthResponse(status = "UP", schemaVersion = schemaVersion))
        }

        mobileRoutes(auth, mobileClaims, files)       // /api/mobile/...   — приложение клиента
        operatorRoutes(auth, operatorClaims, files)   // /api/operator/... — веб оператора
    }
}
