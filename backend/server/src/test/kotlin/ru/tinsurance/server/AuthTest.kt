package ru.tinsurance.server

import ru.tinsurance.api.*
import ru.tinsurance.server.demo.DemoData
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlin.test.*

class AuthTest {

    @Test
    fun clientLogsInByPhoneInAnyFormat() = apiTest { client ->
        val auth = client.loginClient(phone = "8 (900) 000-00-01")
        assertEquals(Role.CLIENT, auth.user.role)
        assertEquals(DemoData.CLIENT_IVAN, auth.user.phone)

        val me = client.get("/api/mobile/me") { bearerAuth(auth.accessToken) }
        assertEquals(HttpStatusCode.OK, me.status)
        assertEquals(auth.user.id, me.body<UserDto>().id)
    }

    @Test
    fun wrongPasswordRejected() = apiTest { client ->
        val response = client.post("/api/mobile/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(MobileLoginRequest(DemoData.CLIENT_IVAN, "wrong", DeviceInfo("d1", DevicePlatform.IOS)))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun operatorCannotLoginToMobileAndClientCannotLoginToWeb() = apiTest { client ->
        val clientOnWeb = client.post("/api/operator/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(OperatorLoginRequest(DemoData.CLIENT_IVAN, DemoData.DEV_DEMO_PASSWORD))
        }
        assertEquals(HttpStatusCode.Unauthorized, clientOnWeb.status)
    }

    @Test
    fun tokensAreNotInterchangeableBetweenChannels() = apiTest { client ->
        val clientToken = client.loginClient().accessToken
        val operatorToken = client.loginOperator().accessToken

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/operator/queue") { bearerAuth(clientToken) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/mobile/claims") { bearerAuth(operatorToken) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/mobile/claims").status)
    }

    @Test
    fun refreshRotatesTokenAndOldOneStopsWorking() = apiTest { client ->
        val first = client.loginClient()

        val refreshed = client.post("/api/mobile/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(first.refreshToken))
        }
        assertEquals(HttpStatusCode.OK, refreshed.status)
        val second = refreshed.body<AuthResponse>()
        assertNotEquals(first.refreshToken, second.refreshToken)

        val reused = client.post("/api/mobile/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(first.refreshToken))
        }
        assertEquals(HttpStatusCode.Unauthorized, reused.status)
    }

    @Test
    fun logoutRevokesRefreshToken() = apiTest { client ->
        val auth = client.loginOperator()
        assertEquals(HttpStatusCode.NoContent, client.post("/api/operator/auth/logout") { bearerAuth(auth.accessToken) }.status)

        val refresh = client.post("/api/operator/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(auth.refreshToken))
        }
        assertEquals(HttpStatusCode.Unauthorized, refresh.status)
    }

    @Test
    fun accessTokenStopsWorkingRightAfterLogoutAndRotation() = apiTest { client ->
        // веб: после выхода access-токен не живёт оставшиеся 15 минут
        val web = client.loginOperator()
        assertEquals(HttpStatusCode.OK, client.get("/api/operator/me") { bearerAuth(web.accessToken) }.status)
        client.post("/api/operator/auth/logout") { bearerAuth(web.accessToken) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/operator/me") { bearerAuth(web.accessToken) }.status)

        // телефон: после обновления пары старый access-токен больше не принимается, новый — да
        val mobile = client.loginClient(DemoData.CLIENT_IVAN)
        val rotated = client.post("/api/mobile/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(mobile.refreshToken))
        }.body<AuthResponse>()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/mobile/me") { bearerAuth(mobile.accessToken) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/mobile/me") { bearerAuth(rotated.accessToken) }.status)

        // заблокированный пользователь теряет доступ сразу
        TestDatabase.connection().use { it.createStatement().execute("update users set is_active = false where phone = '${DemoData.CLIENT_IVAN}'") }
        try {
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/mobile/me") { bearerAuth(rotated.accessToken) }.status)
        } finally {
            TestDatabase.connection().use { it.createStatement().execute("update users set is_active = true where phone = '${DemoData.CLIENT_IVAN}'") }
        }
    }
}
