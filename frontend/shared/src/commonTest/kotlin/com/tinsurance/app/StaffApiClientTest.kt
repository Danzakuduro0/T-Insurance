package com.tinsurance.app

import com.tinsurance.app.data.ApiException
import com.tinsurance.app.data.MemoryStore
import com.tinsurance.app.data.SessionStorage
import com.tinsurance.app.data.StaffApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import ru.tinsurance.api.AuthResponse
import ru.tinsurance.api.OperatorApi
import ru.tinsurance.api.OperatorLoginRequest
import ru.tinsurance.api.RefreshRequest
import ru.tinsurance.api.Role
import ru.tinsurance.api.UserDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StaffApiClientTest {
    private val store = MemoryStore()
    private val mobileSession = SessionStorage(store).apply { refreshToken = "mobile-refresh" }

    @Test
    fun signsInWithEmailAndUsesRoleFromServer() = runTest {
        val client = api { request ->
            assertEquals(OperatorApi.LOGIN, request.url.encodedPath)
            assertEquals(HttpMethod.Post, request.method)
            assertNull(request.headers[HttpHeaders.Authorization])
            assertEquals(OperatorLoginRequest("operator@tstrah.test", "password"), Json.decodeFromString(request.jsonBody()))
            HttpStatusCode.OK to session(Role.OPERATOR)
        }
        try {
            assertEquals(Role.OPERATOR, client.login(" operator@tstrah.test ", "password").role)
            assertEquals("staff-r1", store.get(StaffToken))
            assertEquals("mobile-refresh", mobileSession.refreshToken)
        } finally {
            client.close()
        }
    }

    @Test
    fun restoresAdminWithRotatedRefreshTokenThenRevokesSession() = runTest {
        store.set(StaffToken, "staff-r1")
        val client = api { request ->
            when (request.url.encodedPath) {
                OperatorApi.REFRESH -> {
                    assertEquals(RefreshRequest("staff-r1"), Json.decodeFromString(request.jsonBody()))
                    assertNull(request.headers[HttpHeaders.Authorization])
                    HttpStatusCode.OK to session(Role.ADMIN, "a2", "staff-r2")
                }
                OperatorApi.LOGOUT -> {
                    assertEquals("Bearer a2", request.headers[HttpHeaders.Authorization])
                    HttpStatusCode.NoContent to ""
                }
                else -> error("Unexpected route ${request.url.encodedPath}")
            }
        }
        try {
            assertEquals(Role.ADMIN, client.restore()?.role)
            assertEquals("staff-r2", store.get(StaffToken))
            client.logout()
            assertFalse(client.hasStoredSession)
            assertEquals("mobile-refresh", mobileSession.refreshToken)
        } finally {
            client.close()
        }
    }

    @Test
    fun rejectsClientRoleFromStaffLoginResponse() = runTest {
        val client = api { HttpStatusCode.OK to session(Role.CLIENT) }
        try {
            val error = assertFailsWith<ApiException> { client.login("client@tstrah.test", "password") }
            assertEquals(403, error.status)
            assertFalse(client.hasStoredSession)
            assertEquals("mobile-refresh", mobileSession.refreshToken)
        } finally {
            client.close()
        }
    }

    @Test
    fun invalidCredentialsAreNotRetried() = runTest {
        var loginRequests = 0
        val client = api { request ->
            assertEquals(OperatorApi.LOGIN, request.url.encodedPath)
            loginRequests++
            HttpStatusCode.Unauthorized to """{"error":"UNAUTHORIZED","message":"Неверный email или пароль"}"""
        }
        try {
            val error = assertFailsWith<ApiException> { client.login("operator@tstrah.test", "bad") }
            assertEquals("Неверный email или пароль", error.message)
            assertEquals(1, loginRequests)
            assertFalse(client.hasStoredSession)
        } finally {
            client.close()
        }
    }

    @Test
    fun unauthorizedRefreshClearsStaffSessionOnly() = runTest {
        store.set(StaffToken, "expired")
        var refreshRequests = 0
        val client = api {
            refreshRequests++
            HttpStatusCode.Unauthorized to """{"error":"UNAUTHORIZED","message":"Сессия истекла"}"""
        }
        try {
            val error = assertFailsWith<ApiException> { client.restore() }
            assertTrue(error.unauthorized)
            assertEquals(1, refreshRequests)
            assertFalse(client.hasStoredSession)
            assertEquals("mobile-refresh", mobileSession.refreshToken)
        } finally {
            client.close()
        }
    }

    @Test
    fun unavailableServerKeepsRefreshTokenForRetry() = runTest {
        store.set(StaffToken, "staff-r1")
        val client = api { throw Error("Fail to fetch") }
        try {
            val error = assertFailsWith<ApiException> { client.restore() }
            assertEquals(0, error.status)
            assertEquals("staff-r1", store.get(StaffToken))
        } finally {
            client.close()
        }
    }

    @Test
    fun logoutRefreshesExpiredAccessTokenAndRetriesOnce() = runTest {
        val logoutTokens = mutableListOf<String?>()
        var refreshRequests = 0
        val client = api { request ->
            when (request.url.encodedPath) {
                OperatorApi.LOGIN -> HttpStatusCode.OK to session(Role.OPERATOR)
                OperatorApi.REFRESH -> {
                    refreshRequests++
                    assertEquals(RefreshRequest("staff-r1"), Json.decodeFromString(request.jsonBody()))
                    assertNull(request.headers[HttpHeaders.Authorization])
                    HttpStatusCode.OK to session(Role.OPERATOR, "a2", "staff-r2")
                }
                OperatorApi.LOGOUT -> {
                    val header = request.headers[HttpHeaders.Authorization]
                    logoutTokens += header
                    if (header == "Bearer a2") HttpStatusCode.NoContent to ""
                    else HttpStatusCode.Unauthorized to """{"error":"UNAUTHORIZED"}"""
                }
                else -> error("Unexpected route ${request.url.encodedPath}")
            }
        }
        try {
            client.login("operator@tstrah.test", "password")
            client.logout()
            assertEquals(1, refreshRequests)
            assertEquals(listOf<String?>("Bearer a1", "Bearer a2"), logoutTokens)
            assertFalse(client.hasStoredSession)
        } finally {
            client.close()
        }
    }

    @Test
    fun logoutClearsLocalStaffSessionWhenServerIsUnavailable() = runTest {
        val client = api { request ->
            when (request.url.encodedPath) {
                OperatorApi.LOGIN -> HttpStatusCode.OK to session(Role.OPERATOR)
                OperatorApi.LOGOUT -> throw Error("Fail to fetch")
                else -> error("Unexpected route ${request.url.encodedPath}")
            }
        }
        try {
            client.login("operator@tstrah.test", "password")
            assertFailsWith<ApiException> { client.logout() }
            assertFalse(client.hasStoredSession)
            assertEquals("mobile-refresh", mobileSession.refreshToken)
        } finally {
            client.close()
        }
    }

    private fun api(handler: (HttpRequestData) -> Pair<HttpStatusCode, String>) =
        StaffApiClient(store, "http://test") { config ->
            HttpClient(
                MockEngine { request ->
                    val (status, body) = handler(request)
                    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
                },
                config,
            )
        }

    private fun session(role: Role, access: String = "a1", refresh: String = "staff-r1"): String =
        Json.encodeToString(
            AuthResponse(access, 900, refresh, UserDto(42, role, "Тестовый сотрудник", null, "staff@tstrah.test")),
        )

    private fun HttpRequestData.jsonBody() = (body as OutgoingContent.ByteArrayContent).bytes().decodeToString()

    private companion object {
        const val StaffToken = "tinsurance.staff.refreshToken"
    }
}
