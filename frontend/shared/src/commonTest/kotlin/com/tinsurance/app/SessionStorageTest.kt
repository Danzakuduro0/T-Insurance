package com.tinsurance.app

import com.tinsurance.app.data.ApiException
import com.tinsurance.app.data.MemoryStore
import com.tinsurance.app.data.MobileApiClient
import com.tinsurance.app.data.SessionStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class SessionStorageTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun auth(access: String, refresh: String) =
        """{"accessToken":"$access","accessTokenExpiresIn":900,"refreshToken":"$refresh",
           "user":{"id":2,"role":"CLIENT","fullName":"Анна Смирнова","phone":"+79000000002","email":null}}"""

    /** Сервер с одноразовыми refresh-токенами: r1 → r2 → r3… */
    private class Server {
        var validRefresh = "r1"
        var generation = 1
        var down = false
        val bodies = mutableListOf<String>()
        val seenAuth = mutableListOf<String?>()
    }

    private fun client(store: MemoryStore, server: Server) =
        MobileApiClient("http://test", SessionStorage(store)) { config ->
            HttpClient(
                MockEngine { request ->
                    if (server.down) throw Error("Fail to fetch")
                    val path = request.url.encodedPath
                    val body = request.body.toByteArray().decodeToString()
                    server.bodies += body
                    when {
                        path.endsWith("/auth/login") -> respond(auth("a1", "r1"), HttpStatusCode.OK, jsonHeaders)
                        path.endsWith("/auth/refresh") ->
                            if ("\"${server.validRefresh}\"" in body) {
                                server.generation++
                                server.validRefresh = "r${server.generation}"
                                respond(auth("a${server.generation}", server.validRefresh), HttpStatusCode.OK, jsonHeaders)
                            } else respond("""{"error":"UNAUTHORIZED"}""", HttpStatusCode.Unauthorized, jsonHeaders)
                        else -> {
                            server.seenAuth += request.headers[HttpHeaders.Authorization]
                            respond("[]", HttpStatusCode.OK, jsonHeaders)
                        }
                    }
                },
                config,
            )
        }

    @Test
    fun sessionSurvivesRestartAndRotatesRefreshToken() = runTest {
        val store = MemoryStore()
        val server = Server()
        client(store, server).login("+79000000002", "pw")

        // «Перезапуск»: новый клиент с тем же хранилищем.
        val restarted = client(store, server)
        assertTrue(restarted.hasStoredSession)
        assertEquals("Анна Смирнова", restarted.restore()?.fullName)
        restarted.claims()
        assertEquals(listOf<String?>("Bearer a2"), server.seenAuth)

        // Токен одноразовый: второй перезапуск работает только потому, что сохранён новый r2.
        assertNotNull(client(store, server).restore())
        assertEquals("r3", server.validRefresh)
    }

    @Test
    fun rejectedTokenIsForgottenButNetworkErrorKeepsIt() = runTest {
        val store = MemoryStore()
        val server = Server()
        client(store, server).login("+79000000002", "pw")

        server.down = true
        assertFailsWith<ApiException> { client(store, server).restore() }
        assertTrue(client(store, server).hasStoredSession)

        server.down = false
        server.validRefresh = "revoked-on-server"
        val api = client(store, server)
        assertNull(api.restore())
        assertFalse(api.hasStoredSession)
    }

    @Test
    fun logoutForgetsSessionAndInstallationIdIsStable() = runTest {
        val store = MemoryStore()
        val server = Server()
        val first = client(store, server)
        first.login("+79000000002", "pw")
        first.logout()
        assertFalse(first.hasStoredSession)
        assertNull(client(store, server).restore())

        client(store, server).login("+79000000002", "pw")
        val ids = server.bodies.mapNotNull { Regex("\"installationId\":\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
        assertEquals(2, ids.size)
        assertEquals(ids[0], ids[1])
    }
}
