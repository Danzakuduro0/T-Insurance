package ru.tinsurance.server

import ru.tinsurance.api.*
import ru.tinsurance.server.demo.DemoData
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.time.OffsetDateTime
import kotlin.test.*

/** Регрессионные тесты по итогам проверки безопасности (пункты 1–5 и 7). */
class SecurityTest {

    private fun startFails(config: io.ktor.server.config.MapApplicationConfig): Throwable =
        assertFails {
            testApplication {
                environment { this.config = config }
                application { module() }
                startApplication()
            }
        }

    // ---------------------------------------------------------------- 1. секреты по умолчанию

    @Test
    fun prodWithoutJwtSecretDoesNotStart() {
        val e = startFails(TestDatabase.config("app.mode" to "prod", "demo.seed" to "false", without = setOf("jwt.secret")))
        assertTrue(generateSequence(e) { it.cause }.any { it.message.orEmpty().contains("JWT_SECRET") }, e.toString())
    }

    @Test
    fun prodDemoDataRequiresOwnStrongPassword() {
        val noPassword = startFails(TestDatabase.config("app.mode" to "prod"))
        assertTrue(generateSequence(noPassword) { it.cause }.any { it.message.orEmpty().contains("DEMO_PASSWORD") })
        val devPassword = startFails(TestDatabase.config("app.mode" to "prod", "demo.password" to DemoData.DEV_DEMO_PASSWORD))
        assertTrue(generateSequence(devPassword) { it.cause }.any { it.message.orEmpty().contains("DEMO_PASSWORD") })
    }

    @Test
    fun devWithoutSecretUsesRandomOne() = apiTest(TestDatabase.config(without = setOf("jwt.secret"))) { http ->
        val token = http.loginClient().accessToken
        assertEquals(HttpStatusCode.OK, http.get("/api/mobile/me") { bearerAuth(token) }.status)
    }

    @Test
    fun realYamlConfigIsWellFormed() {
        // тесты задают конфиг в коде — этот проверяет сам application.yaml (модуль в ktor.application, пустые значения по умолчанию)
        val config = io.ktor.server.config.ApplicationConfig("application.yaml")
        assertEquals(listOf("ru.tinsurance.server.ApplicationKt.module"), config.property("ktor.application.modules").getList())
        listOf("app.mode", "jwt.secret", "demo.seed", "demo.password").forEach { key ->
            assertNotNull(config.propertyOrNull(key), "нет ключа $key")
        }
        if (System.getenv("JWT_SECRET") == null) assertEquals("", config.property("jwt.secret").getString())
    }

    // ---------------------------------------------------------------- 2. база не открыта в сеть

    @Test
    fun databasePortBoundToLocalhostOnly() {
        val compose = File("../docker-compose.yml").readText()
        assertTrue(compose.contains("\"127.0.0.1:5432:5432\""), "Порт Postgres должен быть привязан к 127.0.0.1")
        assertFalse(Regex("""-\s*"5432:5432"""").containsMatchIn(compose))
    }

    // ---------------------------------------------------------------- 3. перебор паролей

    @Test
    fun loginLockedAfterRepeatedFailures_evenWithCorrectPassword() = apiTest { http ->
        suspend fun login(password: String) = http.post("/api/mobile/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(MobileLoginRequest(DemoData.CLIENT_OLEG, password, DeviceInfo("brute", DevicePlatform.ANDROID)))
        }
        repeat(5) { assertEquals(HttpStatusCode.Unauthorized, login("wrong-$it").status) }

        val locked = login(DemoData.DEV_DEMO_PASSWORD)
        assertEquals(HttpStatusCode.TooManyRequests, locked.status)
        assertEquals("TOO_MANY_ATTEMPTS", locked.body<ErrorResponse>().error)
        assertNotNull(locked.headers[HttpHeaders.RetryAfter])
        // другой логин не заблокирован
        assertEquals(HttpStatusCode.OK, http.post("/api/mobile/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(MobileLoginRequest(DemoData.CLIENT_ANNA, DemoData.DEV_DEMO_PASSWORD, DeviceInfo("ok", DevicePlatform.ANDROID)))
        }.status)
    }

    @Test
    fun authEndpointsRateLimitedPerAddress() = apiTest(TestDatabase.config("security.authRequestsPerMinute" to "3")) { http ->
        val statuses = (1..4).map { i ->
            http.post("/api/operator/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(OperatorLoginRequest("nobody$i@tstrah.test", "x"))
            }.status
        }
        assertEquals(listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Unauthorized, HttpStatusCode.Unauthorized, HttpStatusCode.TooManyRequests), statuses)
    }

    // ---------------------------------------------------------------- 4. ошибки без внутренностей

    @Test
    fun databaseErrorsDoNotLeakSchema() = apiTest(extra = {
        routing { get("/test/db-error") { transaction { exec("insert into claims(client_id) values (-1)") }; call.respondText("unreachable") } }
    }) { http ->
        val response = http.get("/test/db-error")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue("INVALID_REFERENCE" in body)
        listOf("claims", "constraint", "violates", "fkey").forEach { assertFalse(it in body, "В ответе есть «$it»: $body") }
    }

    @Test
    fun prodHidesJsonParsingDetails() = apiTest(TestDatabase.config("app.mode" to "prod", "demo.password" to "long-prod-demo-password")) { http ->
        val response = http.post("/api/mobile/auth/login") { contentType(ContentType.Application.Json); setBody("""{"phone": 1""") }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val message = response.body<ErrorResponse>().message.orEmpty()
        assertFalse("kotlinx" in message || "ru.tinsurance" in message || "Exception" in message, message)
    }

    // ---------------------------------------------------------------- 5. внутренняя причина не уходит клиенту

    @Test
    fun internalReasonNotShownInClientTimeline() = apiTest { http ->
        val auth = http.loginClient(DemoData.CLIENT_OLEG, installationId = "security-oleg")
        val inReview = http.get("/api/mobile/claims") { bearerAuth(auth.accessToken) }.body<List<ClaimListItem>>()
            .single { it.status == ClaimStatus.IN_REVIEW }
        TestDatabase.connection().use { c ->
            c.createStatement().use {
                it.execute(
                    "insert into claim_status_history(claim_id, from_status, to_status, actor_type, actor_id, reason, client_comment, visible_to_client) " +
                        "select ${inReview.id}, 'IN_REVIEW', 'IN_REVIEW', 'SYSTEM', null, 'Подозрение на повторное обращение (служебное)', null, true " +
                        "where false"  // переходы IN_REVIEW → IN_REVIEW нет; проверяем на существующей истории ниже
                )
                it.execute("update claim_status_history set reason = 'Служебная пометка оператора' where claim_id = ${inReview.id} and to_status = 'IN_REVIEW'")
            }
        }
        val timeline = http.get("/api/mobile/claims/${inReview.id}/timeline") { bearerAuth(auth.accessToken) }.body<List<TimelineEvent>>()
        assertTrue(timeline.none { it.comment.orEmpty().contains("Служебная") }, timeline.toString())
    }

    // ---------------------------------------------------------------- 7. размеры запроса и полей

    @Test
    fun oversizedBodyAndFieldsRejected() = apiTest { http ->
        val auth = http.loginClient(DemoData.CLIENT_ANNA, installationId = "security-anna")
        val huge = http.post("/api/mobile/claims") {
            bearerAuth(auth.accessToken); contentType(ContentType.Application.Json)
            setBody("""{"description": "${"x".repeat(300 * 1024)}"}""")
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, huge.status)

        val longField = http.post("/api/mobile/claims") {
            bearerAuth(auth.accessToken); contentType(ContentType.Application.Json)
            setBody(CreateClaimRequest(description = "x".repeat(4001)))
        }
        assertEquals(HttpStatusCode.BadRequest, longField.status)

        val tooManyParticipants = http.post("/api/mobile/claims") {
            bearerAuth(auth.accessToken); contentType(ContentType.Application.Json)
            setBody(CreateClaimRequest(eventType = "CAR_ACCIDENT", eventAt = OffsetDateTime.now().minusDays(1).toString(),
                participants = List(21) { ParticipantInput("WITNESS", "Свидетель $it") }))
        }
        assertEquals(HttpStatusCode.BadRequest, tooManyParticipants.status)
    }
}
