package ru.tinsurance.server.openapi

import io.ktor.http.ContentType
import io.ktor.server.response.*
import io.ktor.server.routing.*
import ru.tinsurance.server.plugins.HealthResponse

internal fun buildOpenApi() = ApiDocument().apply {
    operation("get", "/health", "Состояние сервера и версия схемы", schema<HealthResponse>(), public = true)
    authOperations(); mobileOperations(); operatorOperations()
}.build()

fun Route.openApiRoutes() {
    val document = buildOpenApi().toString()
    get("/openapi.json") { call.respondText(document, ContentType.Application.Json) }
    get("/swagger") { call.respondRedirect("/swagger/index.html") }
    get("/swagger/index.html") {
        call.respondText(javaClass.getResource("/openapi/index.html")!!.readText(), ContentType.Text.Html)
    }
    for ((file, mime) in mapOf("swagger-ui.css" to "text/css", "swagger-ui-bundle.js" to "application/javascript")) {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/META-INF/resources/webjars/swagger-ui/5.34.1/$file")).use { it.readBytes() }
        get("/swagger/$file") { call.respondBytes(bytes, ContentType.parse(mime)) }
    }
}
