package ru.tinsurance.server.plugins

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import ru.tinsurance.server.AppContainer
import ru.tinsurance.server.common.tx
import ru.tinsurance.server.mobile.mobileRoutes
import ru.tinsurance.server.operator.operatorRoutes

@Serializable
data class HealthResponse(val status: String, val schemaVersion: String?)

fun Application.configureRouting(app: AppContainer) {
    routing {
        get("/health") {
            val schemaVersion = app.database.tx {
                exec("select max(version::int)::text from flyway_schema_history where success") { rs ->
                    if (rs.next()) rs.getString(1) else null
                }
            }
            call.respond(HealthResponse(status = "UP", schemaVersion = schemaVersion))
        }
        mobileRoutes(app.auth, app.mobileClaims, app.uploads, app.fileAccess)
        operatorRoutes(app.auth, app.operatorClaims, app.decisions, app.fileAccess)
    }
}
