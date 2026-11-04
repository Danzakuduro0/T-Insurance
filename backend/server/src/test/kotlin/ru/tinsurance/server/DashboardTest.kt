package ru.tinsurance.server

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import ru.tinsurance.api.*
import ru.tinsurance.api.operator.*
import ru.tinsurance.server.claims.*
import ru.tinsurance.server.common.tx
import ru.tinsurance.server.dashboard.*
import ru.tinsurance.server.demo.DemoData
import java.time.OffsetDateTime
import kotlin.test.*

class DashboardTest {
    private class Rollback : RuntimeException()

    @Test
    fun onlyAdminCanReadDashboardAndConfiguredThresholdsAreReturned() = apiTest(
        TestDatabase.config("dashboard.queuedThreshold" to "2", "dashboard.overdueThreshold" to "1"),
    ) { http ->
        val admin = http.loginOperator(DemoData.ADMIN)
        val response = http.get(OperatorApi.DASHBOARD) { bearerAuth(admin.accessToken) }
        assertEquals(HttpStatusCode.OK, response.status)
        val dashboard = response.body<DashboardDto>()
        assertEquals(2, dashboard.queuedThreshold)
        assertEquals(1, dashboard.overdueThreshold)
        assertTrue(dashboard.queues.isNotEmpty())
        assertTrue(dashboard.operators.isNotEmpty())
        assertEquals(dashboard.queues.any { it.overloadWarning }, dashboard.overloadWarning)
        val operator = http.loginOperator()
        assertEquals(HttpStatusCode.Forbidden, http.get(OperatorApi.DASHBOARD) { bearerAuth(operator.accessToken) }.status)
        val client = http.loginClient()
        assertEquals(HttpStatusCode.Unauthorized, http.get(OperatorApi.DASHBOARD) { bearerAuth(client.accessToken) }.status)
        assertEquals(HttpStatusCode.Unauthorized, http.get(OperatorApi.DASHBOARD).status)
    }

    @Test
    fun metricsUseFirstAssignmentExcludeCancelledAndCountNeedsInfoOnce() = claimFlowTest {
        val completed = glassDraft().id
        val cancelled = glassDraft().id
        val operatorId = query("select min(id) from users where role='OPERATOR'")!!.toLong()
        val at = OffsetDateTime.now().minusDays(1)
        assertFailsWith<Rollback> {
            TestDatabase.database.tx {
                exec("update claims set submitted_at=null")
                val empty = DashboardQueries.metrics(this)
                assertEquals(0L, empty.submittedClaims)
                assertNull(empty.averageFirstAssignmentSeconds)
                assertNull(empty.averageCompletionSeconds)
                assertNull(empty.needsInfoShare)
                val client = Actor.client(userId)
                val operator = Actor(ActorType.OPERATOR, operatorId)
                exec("update claims set assigned_operator_id=$operatorId, direction_code='STANDARD', priority=1 where id=$completed")
                fun move(id: Long, from: ClaimStatus, to: ClaimStatus, seconds: Long, actor: Actor = Actor.SYSTEM) =
                    ClaimStatusService.transition(id, from, to, actor, reason = "test", at = at.plusSeconds(seconds))
                move(completed, ClaimStatus.DRAFT, ClaimStatus.SUBMITTED, 0, client)
                move(completed, ClaimStatus.SUBMITTED, ClaimStatus.IN_ANALYSIS, 1)
                move(completed, ClaimStatus.IN_ANALYSIS, ClaimStatus.QUEUED, 2)
                move(completed, ClaimStatus.QUEUED, ClaimStatus.ASSIGNED, 60)
                move(completed, ClaimStatus.ASSIGNED, ClaimStatus.QUEUED, 90, operator)
                move(completed, ClaimStatus.QUEUED, ClaimStatus.ASSIGNED, 120)
                move(completed, ClaimStatus.ASSIGNED, ClaimStatus.IN_REVIEW, 180, operator)
                move(completed, ClaimStatus.IN_REVIEW, ClaimStatus.COMPLETED, 300, operator)
                move(cancelled, ClaimStatus.DRAFT, ClaimStatus.SUBMITTED, 0, client)
                move(cancelled, ClaimStatus.SUBMITTED, ClaimStatus.NEEDS_INFO, 30)
                move(cancelled, ClaimStatus.NEEDS_INFO, ClaimStatus.SUBMITTED, 60, client)
                move(cancelled, ClaimStatus.SUBMITTED, ClaimStatus.NEEDS_INFO, 90)
                move(cancelled, ClaimStatus.NEEDS_INFO, ClaimStatus.CANCELLED, 600, client)
                val metrics = DashboardQueries.metrics(this)
                assertEquals(2L, metrics.submittedClaims)
                assertEquals(1L, metrics.assignedSamples)
                assertEquals(60.0, metrics.averageFirstAssignmentSeconds)
                assertEquals(1L, metrics.completedSamples)
                assertEquals(300.0, metrics.averageCompletionSeconds)
                assertEquals(1L, metrics.needsInfoClaims)
                assertEquals(0.5, metrics.needsInfoShare)
                throw Rollback()
            }
        }
        assertEquals("DRAFT", status(completed))
    }

    @Test
    fun queueViewCountsOnlyQueuedAndUsesStatusSla() = claimFlowTest {
        val id = glassDraft().id
        assertFailsWith<Rollback> {
            TestDatabase.database.tx {
                exec("insert into directions(code,name) values ('DASH_TEST','Dashboard test')")
                val old = OffsetDateTime.now().minusDays(10)
                ClaimStatusService.transition(id, ClaimStatus.DRAFT, ClaimStatus.SUBMITTED, Actor.client(userId), at = old)
                ClaimStatusService.transition(id, ClaimStatus.SUBMITTED, ClaimStatus.IN_ANALYSIS, Actor.SYSTEM, at = old)
                exec("update claims set direction_code='DASH_TEST', priority=3 where id=$id")
                ClaimStatusService.transition(id, ClaimStatus.IN_ANALYSIS, ClaimStatus.QUEUED, Actor.SYSTEM, at = old)
                val queued = DashboardQueries.queues().single { it.directionCode == "DASH_TEST" }
                assertEquals(1L, queued.queued)
                assertEquals(1L, queued.queuedUrgent)
                assertEquals(1L, queued.overdue)
                assertNotNull(queued.oldestQueuedAt)
                exec("update claims set assigned_operator_id=(select min(id) from users where role='OPERATOR') where id=$id")
                ClaimStatusService.transition(id, ClaimStatus.QUEUED, ClaimStatus.ASSIGNED, Actor.SYSTEM)
                val cleared = DashboardQueries.queues().single { it.directionCode == "DASH_TEST" }
                assertEquals(0L, cleared.queued)
                assertNull(cleared.oldestQueuedAt)
                throw Rollback()
            }
        }
    }

    @Test
    fun recommendationsRespectAvailabilitySpecializationAndCapacity() {
        val config = DashboardConfig(2, 1)
        val queues = DashboardRules.queues(listOf(QueueLoadDto("MANUAL_REVIEW", 2, 1, 0, null)), config)
        assertTrue(queues.single().overloadWarning)
        val operator = OperatorDto(1, "Test", OperatorAvailability.ONLINE, listOf("MANUAL_REVIEW"), 0, 5)
        fun code(staff: OperatorDto) = DashboardRules.recommendations(queues, listOf(staff)).single().code
        assertEquals("ASSIGN_AVAILABLE", code(operator))
        assertEquals("ADD_CAPACITY", code(operator.copy(availability = OperatorAvailability.OFFLINE)))
        assertEquals("ADD_CAPACITY", code(operator.copy(directions = listOf("STANDARD"))))
        assertEquals("ADD_CAPACITY", code(operator.copy(activeClaims = 5)))
        val overdue = DashboardRules.queues(listOf(QueueLoadDto("STANDARD", 1, 0, 1, null)), config)
        assertTrue(overdue.single().overloadWarning)
        assertTrue(DashboardRules.recommendations(overdue, emptyList()).any { it.code == "REVIEW_OVERDUE" })
        assertFalse(DashboardRules.queues(listOf(QueueLoadDto("STANDARD", 1, 0, 0, null)), config).single().overloadWarning)
    }

    @Test
    fun nonPositiveThresholdsAreRejected() {
        assertFailsWith<IllegalArgumentException> { DashboardConfig(0, 1) }
        assertFailsWith<IllegalArgumentException> { DashboardConfig(1, -1) }
    }
}
