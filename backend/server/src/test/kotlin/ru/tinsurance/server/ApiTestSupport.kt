package ru.tinsurance.server

import ru.tinsurance.api.*
import ru.tinsurance.api.mobile.*
import ru.tinsurance.api.operator.*
import ru.tinsurance.server.demo.DemoData
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals

fun apiTest(
    config: io.ktor.server.config.MapApplicationConfig = TestDatabase.config(),
    extra: io.ktor.server.application.Application.() -> Unit = {},
    block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
) = testApplication {
    environment { this.config = config }
    application { module(); extra() }
    val client = createClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
    block(client)
}

suspend fun HttpClient.loginClient(phone: String = DemoData.CLIENT_IVAN, installationId: String = "test-device-1"): AuthResponse {
    val response = post("/api/mobile/auth/login") {
        contentType(ContentType.Application.Json)
        setBody(MobileLoginRequest(phone, DemoData.DEV_DEMO_PASSWORD, DeviceInfo(installationId, DevicePlatform.ANDROID)))
    }
    assertEquals(HttpStatusCode.OK, response.status)
    return response.body()
}

suspend fun HttpClient.loginOperator(email: String = DemoData.OPERATOR_2): AuthResponse {
    val response = post("/api/operator/auth/login") {
        contentType(ContentType.Application.Json)
        setBody(OperatorLoginRequest(email, DemoData.DEV_DEMO_PASSWORD))
    }
    assertEquals(HttpStatusCode.OK, response.status)
    return response.body()
}
