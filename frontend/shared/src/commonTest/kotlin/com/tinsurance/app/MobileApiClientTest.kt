package com.tinsurance.app

import com.tinsurance.app.client.formatDate
import com.tinsurance.app.data.ApiException
import com.tinsurance.app.data.MobileApiClient
import com.tinsurance.app.data.normalizePhone
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class MobileApiClientTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private val authJson =
        """{"accessToken":"a1","accessTokenExpiresIn":900,"refreshToken":"r1",
           "user":{"id":1,"role":"CLIENT","fullName":"Анна Смирнова","phone":"+79000000002","email":null}}"""

    private fun client(handler: (path: String, auth: String?) -> Pair<HttpStatusCode, String>) =
        MobileApiClient("http://test") { config ->
            HttpClient(
                MockEngine { request ->
                    val (status, body) = handler(request.url.encodedPath, request.headers[HttpHeaders.Authorization])
                    respond(body, status, jsonHeaders)
                },
                config,
            )
        }

    @Test
    fun loginSendsBearerTokenOnNextRequests() = runTest {
        val seen = mutableListOf<String?>()
        val api = client { path, auth ->
            seen += auth
            when (path) {
                "/api/mobile/auth/login" -> HttpStatusCode.OK to authJson
                "/api/mobile/claims" -> HttpStatusCode.OK to "[]"
                else -> HttpStatusCode.NotFound to "{}"
            }
        }
        assertEquals("Анна Смирнова", api.login("+79000000002", "pw").fullName)
        assertTrue(api.claims().isEmpty())
        assertEquals(listOf(null, "Bearer a1"), seen)
    }

    @Test
    fun serverErrorMessageIsShownToUser() = runTest {
        val api = client { _, _ ->
            HttpStatusCode.Unauthorized to """{"error":"INVALID_CREDENTIALS","message":"Неверный телефон или пароль"}"""
        }
        val error = assertFailsWith<ApiException> { api.login("+79000000002", "bad") }
        assertEquals("Неверный телефон или пароль", error.message)
        assertTrue(error.unauthorized)
    }

    @Test
    fun expiredAccessTokenIsRefreshed() = runTest {
        val api = client { path, auth ->
            when (path) {
                "/api/mobile/auth/login" -> HttpStatusCode.OK to authJson
                "/api/mobile/auth/refresh" ->
                    HttpStatusCode.OK to authJson.replace("\"a1\"", "\"a2\"").replace("\"r1\"", "\"r2\"")
                "/api/mobile/claims" ->
                    if (auth == "Bearer a2") HttpStatusCode.OK to "[]"
                    else HttpStatusCode.Unauthorized to """{"error":"UNAUTHORIZED"}"""
                else -> HttpStatusCode.NotFound to "{}"
            }
        }
        api.login("+79000000002", "pw")
        assertTrue(api.claims().isEmpty())
    }

    @Test
    fun phoneIsNormalizedToServerFormat() {
        assertEquals("+79000000001", normalizePhone("8 900 000-00-01"))
        assertEquals("+79000000001", normalizePhone("+7 (900) 000-00-01"))
        assertEquals("+79000000001", normalizePhone("9000000001"))
    }

    @Test
    fun isoDatesAreShownInRussian() {
        assertEquals("17 октября", formatDate("2026-10-17T18:35:00+07:00"))
        assertEquals("31 декабря 2026", formatDate("2026-12-31", withYear = true))
        assertEquals("скоро", formatDate("скоро"))
    }
}
