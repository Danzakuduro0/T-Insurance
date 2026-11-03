package com.tinsurance.app

import com.tinsurance.app.data.MobileApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class NotificationsTest {
    private val requests = mutableListOf<String>()

    private val api =
        MobileApiClient("http://test") { config ->
            HttpClient(
                MockEngine { request ->
                    requests += "${request.method.value} ${request.url.encodedPath}?${request.url.encodedQuery}"
                    val body =
                        if (request.method == HttpMethod.Post)
                            """{"id":7,"claimId":5,"type":"STATUS_CHANGED","title":"t","body":"b",
                               "createdAt":"2026-11-02T10:00:00+07:00","readAt":"2026-11-02T10:05:00+07:00"}"""
                        else
                            """{"items":[{"id":7,"claimId":5,"type":"DOCUMENTS_REQUIRED","title":"Нужны документы",
                               "body":"Добавьте извещение","createdAt":"2026-11-02T10:00:00+07:00","readAt":null}],
                               "nextBeforeId":7}"""
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                },
                config,
            )
        }

    @Test
    fun pagesArePassedAsBeforeId() = runTest {
        val page = api.notifications(beforeId = 12, limit = 5, unreadOnly = true)
        assertEquals(7L, page.nextBeforeId)
        assertNull(page.items.single().readAt)
        assertEquals(
            listOf("GET /api/mobile/notifications?limit=5&beforeId=12&unreadOnly=true"),
            requests,
        )
    }

    @Test
    fun firstPageOmitsOptionalParameters() = runTest {
        api.notifications()
        assertEquals(listOf("GET /api/mobile/notifications?limit=20"), requests)
    }

    @Test
    fun markReadPostsToNotificationPath() = runTest {
        val updated = api.markNotificationRead(7)
        assertNotNull(updated.readAt)
        assertEquals(listOf("POST /api/mobile/notifications/7/read?"), requests)
    }
}
