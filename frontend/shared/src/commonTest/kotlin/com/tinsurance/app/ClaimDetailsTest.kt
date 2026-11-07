package com.tinsurance.app

import com.tinsurance.app.client.filesCount
import com.tinsurance.app.client.formatDate
import com.tinsurance.app.client.formatDateTime
import com.tinsurance.app.client.timelineItems
import com.tinsurance.app.data.MobileApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonObject
import ru.tinsurance.api.ClaimStatus
import ru.tinsurance.api.mobile.ClaimDetails
import ru.tinsurance.api.mobile.TimelineEvent

class ClaimDetailsTest {
    private val Novosibirsk = TimeZone.of("Asia/Novosibirsk")

    private fun details(status: ClaimStatus) =
        ClaimDetails(
            id = 5,
            claimNumber = "TS-0000005",
            status = status,
            statusTitle = "",
            isFinal = status.isFinal,
            policyId = 1,
            policyNumber = "KASKO-1",
            eventType = "CAR_ACCIDENT",
            eventTypeName = "ДТП",
            eventAt = null,
            eventLocation = null,
            description = null,
            contactPhone = null,
            participants = emptyList(),
            details = JsonObject(emptyMap()),
            createdAt = "2026-10-27T10:00:00+07:00",
            updatedAt = "2026-10-27T10:00:00+07:00",
            submittedAt = null,
            openInfoRequest = null,
            files = emptyList(),
        )

    private val events =
        listOf(
            TimelineEvent(ClaimStatus.DRAFT, "Черновик", null, "2026-10-27T09:00:00+07:00"),
            TimelineEvent(ClaimStatus.SUBMITTED, "Отправлено", null, "2026-10-27T10:00:00+07:00"),
            TimelineEvent(ClaimStatus.NEEDS_INFO, "Нужны документы", null, "2026-10-27T11:00:00+07:00"),
            TimelineEvent(ClaimStatus.IN_REVIEW, "На рассмотрении", null, "2026-10-28T09:05:00+07:00"),
        )

    @Test
    fun activeClaimMarksLastEventCurrentAndAddsPendingStep() {
        val items = timelineItems(details(ClaimStatus.IN_REVIEW), events, Novosibirsk)
        assertEquals(listOf("done", "request", "current", "waiting"), items.map { it.status })
        assertEquals("28 октября, 09:05", items[2].time)
        assertNull(items.last().time)
    }

    @Test
    fun finishedClaimHasNoPendingStep() {
        val done = events + TimelineEvent(ClaimStatus.COMPLETED, "Завершено", "Готово", "2026-10-29T15:20:00+07:00")
        val items = timelineItems(details(ClaimStatus.COMPLETED), done, Novosibirsk)
        assertEquals("done", items.last().status)
        assertEquals(4, items.size)
    }

    @Test
    fun claimAndTimelineAreRequestedById() = runTest {
        val paths = mutableListOf<String>()
        val api =
            MobileApiClient("http://test") { config ->
                HttpClient(
                    MockEngine { request ->
                        paths += request.url.encodedPath
                        respond("[]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                    config,
                )
            }
        assertTrue(api.timeline(5).isEmpty())
        assertEquals(listOf("/api/mobile/claims/5/timeline"), paths)
    }

    @Test
    fun russianPluralsAndTimeFormatting() {
        assertEquals("1 файл", filesCount(1))
        assertEquals("3 файла", filesCount(3))
        assertEquals("5 файлов", filesCount(5))
        assertEquals("11 файлов", filesCount(11))
        assertEquals("22 файла", filesCount(22))
        assertEquals("17 октября, 18:35", formatDateTime("2026-10-17T18:35:00+07:00", Novosibirsk))
        assertEquals("31 декабря", formatDateTime("2026-12-31", Novosibirsk))
    }
}

class ServerTimeTest {
    private val novosibirsk = TimeZone.of("Asia/Novosibirsk")

    @Test
    fun utcFromServerIsShownInDeviceZone() {
        assertEquals("3 ноября, 14:20", formatDateTime("2026-11-03T07:20:00Z", novosibirsk))
        // Так сервер отдаёт OffsetDateTime.toString(): с долями секунды.
        assertEquals("3 ноября, 19:55", formatDateTime("2026-11-03T12:55:20.276811Z", novosibirsk))
        // Поздний вечер по UTC — уже следующий день по местному времени.
        assertEquals("4 ноября", formatDate("2026-11-03T20:00:00Z", zone = novosibirsk))
    }
}
