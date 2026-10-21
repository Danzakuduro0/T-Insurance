package ru.tinsurance.server

import ru.tinsurance.server.operator.ClaimCard
import ru.tinsurance.server.operator.Page
import ru.tinsurance.server.operator.QueueItem
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlin.test.*

class OperatorApiTest {

    @Test
    fun queueIsSortedByPriorityAndHidesDrafts() = apiTest { client ->
        val token = client.loginOperator().accessToken
        val page = client.get("/api/operator/queue?status=QUEUED") { bearerAuth(token) }.body<Page<QueueItem>>()

        assertTrue(page.items.isNotEmpty())
        assertTrue(page.items.all { it.status == "QUEUED" })
        val priorities = page.items.map { it.priority ?: 0 }
        assertEquals(priorities.sortedDescending(), priorities)
        // первым — сомнительный случай: высокий приоритет, ручная проверка, просрочен SLA
        assertTrue(page.items.first().requiresManualReview)
        assertTrue(page.items.first().isOverdue)

        val all = client.get("/api/operator/queue?limit=200") { bearerAuth(token) }.body<Page<QueueItem>>()
        assertTrue(all.items.none { it.status == "DRAFT" })
    }

    @Test
    fun queueFilters() = apiTest { client ->
        val token = client.loginOperator().accessToken
        val mine = client.get("/api/operator/queue?assigned=me") { bearerAuth(token) }.body<Page<QueueItem>>()
        assertEquals(listOf("IN_REVIEW"), mine.items.map { it.status })

        val standard = client.get("/api/operator/queue?direction=STANDARD") { bearerAuth(token) }.body<Page<QueueItem>>()
        assertTrue(standard.items.isNotEmpty() && standard.items.all { it.directionCode == "STANDARD" })

        // вопрос 39: тип случая, дата создания, назначенный сотрудник
        val accidents = client.get("/api/operator/queue?eventType=CAR_ACCIDENT") { bearerAuth(token) }.body<Page<QueueItem>>()
        assertTrue(accidents.items.isNotEmpty() && accidents.items.all { it.eventType == "CAR_ACCIDENT" })
        val today = java.time.LocalDate.now()
        val recent = client.get("/api/operator/queue?createdFrom=${today.minusDays(1)}&createdTo=$today") { bearerAuth(token) }.body<Page<QueueItem>>()
        assertTrue(recent.items.all { java.time.OffsetDateTime.parse(it.createdAt).toLocalDate() >= today.minusDays(2) })
        val me = client.get("/api/operator/me") { bearerAuth(token) }.body<ru.tinsurance.api.UserDto>()
        val byId = client.get("/api/operator/queue?assignedTo=${me.id}") { bearerAuth(token) }.body<Page<QueueItem>>()
        assertEquals(mine.items.map { it.id }, byId.items.map { it.id })

        val search = client.get("/api/operator/queue?search=kasko-2026-000102") { bearerAuth(token) }.body<Page<QueueItem>>()
        assertTrue(search.items.isNotEmpty() && search.items.all { it.policyNumber == "KASKO-2026-000102" })

        assertEquals(HttpStatusCode.BadRequest, client.get("/api/operator/queue?assigned=bob") { bearerAuth(token) }.status)
    }

    @Test
    fun dateFilterUsesOperatorTimeZoneNotServerOne() = apiTest { client ->
        val token = client.loginOperator().accessToken
        val claim = client.get("/api/operator/queue") { bearerAuth(token) }.body<Page<QueueItem>>().items.first()
        val created = java.time.OffsetDateTime.parse(claim.createdAt).toInstant()
        // пояса с разницей 25 часов: один и тот же момент — всегда разные календарные дни
        val east = java.time.ZoneId.of("Pacific/Kiritimati")
        val west = java.time.ZoneId.of("Pacific/Pago_Pago")
        val day = created.atZone(east).toLocalDate()

        suspend fun ids(tz: java.time.ZoneId) =
            client.get("/api/operator/queue?createdFrom=$day&createdTo=$day&tz=${tz.id}&limit=200") { bearerAuth(token) }
                .body<Page<QueueItem>>().items.map { it.id }
        assertTrue(claim.id in ids(east))
        assertFalse(claim.id in ids(west))
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/operator/queue?tz=Mars/Olympus") { bearerAuth(token) }.status)
    }

    @Test
    fun cardShowsOriginalDecisionNextToOperatorCorrection() = apiTest { client ->
        val token = client.loginOperator().accessToken
        val inReview = client.get("/api/operator/queue?assigned=me") { bearerAuth(token) }.body<Page<QueueItem>>().items.single()

        val card = client.get("/api/operator/claims/${inReview.id}") { bearerAuth(token) }.body<ClaimCard>()
        val priority = card.decisions.filter { it.kind == "PRIORITY" }
        val system = priority.single { it.source == "SYSTEM" }
        val operator = priority.single { it.source == "OPERATOR" }

        assertFalse(system.isCurrent)
        assertTrue(operator.isCurrent)
        assertEquals("OVERRIDE", operator.action)
        assertEquals(system.id, operator.supersedesId)
        assertEquals("Дмитрий Соколов", operator.createdBy)
        assertEquals(2, card.priority)

        // вопросы 34, 35: срок статуса, дополнительные сведения, статус анализа по каждому файлу
        assertNotNull(card.dueAt)
        assertTrue(card.isOverdue)
        assertTrue(card.details["circumstances"] != null)
        assertTrue(card.files.all { it.analysisStatus == "NOT_REQUIRED" })

        assertEquals("IN_REVIEW", card.history.last().toStatus)
        assertTrue(card.comments.any { it.visibility == "INTERNAL" })
        assertTrue(card.files.isNotEmpty())
        assertNotNull(card.policy)
        assertEquals(listOf("APPLICANT_DRIVER", "OTHER_DRIVER"), card.participants.map { it.role })
    }

    @Test
    fun draftCardNotAvailableToOperator() = apiTest { client ->
        val clientToken = client.loginClient().accessToken
        val draft = client.get("/api/mobile/claims") { bearerAuth(clientToken) }
            .body<List<ru.tinsurance.api.ClaimListItem>>()
            .first { it.status == ru.tinsurance.api.ClaimStatus.DRAFT }

        val token = client.loginOperator().accessToken
        assertEquals(HttpStatusCode.NotFound, client.get("/api/operator/claims/${draft.id}") { bearerAuth(token) }.status)
    }
}
