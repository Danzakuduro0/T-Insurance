package ru.tinsurance.server

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.tinsurance.api.ClaimStatus
import ru.tinsurance.api.mobile.*
import ru.tinsurance.server.claims.*
import ru.tinsurance.server.common.tx
import ru.tinsurance.server.jobs.*
import ru.tinsurance.server.notifications.*
import kotlin.test.*

class NotificationsTest {
    @Test
    fun submitCreatesInboxAndDeliveryJobsWithoutTechnicalDuplicates() = claimFlowTest {
        val id = glassDraft().id
        submit(id)
        assertEquals("2", query("select count(*) from notifications where claim_id=$id and channel='IN_APP'"))
        assertEquals("3", query("select count(*) from notifications where claim_id=$id and channel in ('PUSH','SMS')"))
        runWorker()
        assertEquals("3", query("select count(*) from notifications where claim_id=$id and channel in ('PUSH','SMS') and sent_at is not null"))
        assertEquals("0", query("select count(*) from background_jobs where type='SEND_NOTIFICATION' and status='FAILED' and (payload->>'notificationId')::bigint in (select id from notifications where claim_id=$id)"))
    }

    @Test
    fun ownershipReadIdempotencyAndPagination() = claimFlowTest {
        submit(glassDraft().id)
        val base = "/api/mobile/notifications"
        val first = http.get("$base?limit=1") { bearerAuth(token) }.body<NotificationsPage>()
        assertEquals(1, first.items.size)
        val next = http.get("$base?limit=1&beforeId=${first.nextBeforeId}") { bearerAuth(token) }.body<NotificationsPage>()
        assertTrue(next.items.single().id < first.items.single().id)
        val id = first.items.single().id
        val other = http.loginClient()
        assertEquals(HttpStatusCode.NotFound, http.post("$base/$id/read") { bearerAuth(other.accessToken) }.status)
        assertEquals(HttpStatusCode.Unauthorized, http.get(base).status)
        val op = http.loginOperator()
        assertEquals(HttpStatusCode.Unauthorized, http.get(base) { bearerAuth(op.accessToken) }.status)
        val read = http.post("$base/$id/read") { bearerAuth(token) }.body<NotificationDto>()
        assertNotNull(read.readAt)
        assertEquals(read.readAt, http.post("$base/$id/read") { bearerAuth(token) }.body<NotificationDto>().readAt)
        val unread = http.get("$base?unreadOnly=true&limit=100") { bearerAuth(token) }.body<NotificationsPage>()
        assertTrue(unread.items.none { it.id == id })
        for (q in listOf("limit=0", "limit=101", "beforeId=no", "unreadOnly=maybe")) {
            assertEquals(HttpStatusCode.BadRequest, http.get("$base?$q") { bearerAuth(token) }.status)
        }
    }

    @Test
    fun rollbackAndVisibilityDoNotLeakNotificationsOrInternalReason() = claimFlowTest {
        val id = glassDraft().id
        assertFailsWith<IllegalStateException> {
            TestDatabase.database.tx {
                ClaimStatusService.transition(id, ClaimStatus.DRAFT, ClaimStatus.SUBMITTED, Actor.client(userId))
                error("rollback")
            }
        }
        assertEquals("DRAFT", status(id))
        assertEquals("0", query("select count(*) from notifications where claim_id=$id"))
        TestDatabase.database.tx {
            ClaimStatusService.transition(id, ClaimStatus.DRAFT, ClaimStatus.SUBMITTED, Actor.client(userId), visibleToClient = false)
        }
        assertEquals("0", query("select count(*) from notifications where claim_id=$id"))
        TestDatabase.database.tx {
            ClaimStatusService.transition(id, ClaimStatus.SUBMITTED, ClaimStatus.NEEDS_INFO, Actor.SYSTEM,
                reason = "internal secret", clientComment = "Добавьте фото")
        }
        assertEquals("Добавьте фото", query("select body from notifications where claim_id=$id and channel='IN_APP'"))
    }

    @Test
    fun senderRetriesAndDoesNotResendDeliveredNotification() = claimFlowTest {
        val claim = glassDraft().id
        submit(claim)
        val id = query("select id from notifications where claim_id=$claim and channel='PUSH' order by id limit 1")!!.toLong()
        val job = Job(1, JobType.SEND_NOTIFICATION, buildJsonObject { put("notificationId", id) }, 1, 5)
        val failing = SendNotificationHandler(TestDatabase.database, NotificationSender { _, _ -> error("offline") })
        assertIs<JobOutcome.Retry>(failing.handle(job))
        assertNull(query("select sent_at from notifications where id=$id"))
        var delivered = 0
        val handler = SendNotificationHandler(TestDatabase.database, NotificationSender { _, _ -> delivered++ })
        assertEquals(JobOutcome.Done, handler.handle(job))
        assertEquals(JobOutcome.Done, handler.handle(job))
        assertEquals(1, delivered)
    }
    @Test
    fun workerStopsAfterFiveFailuresAndInboxRemainsAvailable() = claimFlowTest {
        val id = glassDraft().id
        submit(id)
        var attempts = 0
        val handler = SendNotificationHandler(TestDatabase.database, NotificationSender { _, _ ->
            attempts++
            error("offline")
        })
        val failingWorker = JobWorker(TestDatabase.database, listOf(handler), WorkerConfig.from(TestDatabase.config()))
        repeat(5) {
            sql("update background_jobs set run_after=now() where type='SEND_NOTIFICATION' and status='PENDING'")
            failingWorker.runReady()
        }
        assertEquals(15, attempts)
        assertEquals("3", query("select count(*) from background_jobs where type='SEND_NOTIFICATION' and status='FAILED' and (payload->>'notificationId')::bigint in (select id from notifications where claim_id=$id)"))
        assertEquals("0", query("select count(*) from notifications where claim_id=$id and channel!='IN_APP' and sent_at is not null"))
        assertEquals("2", query("select count(*) from notifications where claim_id=$id and channel='IN_APP'"))
    }

    @Test
    fun policyIncludesOnlyKeyEvents() {
        for (status in ClaimStatus.entries) {
            val expected = when (status) {
                ClaimStatus.NEEDS_INFO -> listOf("IN_APP", "PUSH", "SMS")
                ClaimStatus.SUBMITTED, ClaimStatus.IN_REVIEW, ClaimStatus.COMPLETED, ClaimStatus.CANCELLED -> listOf("IN_APP", "PUSH")
                else -> emptyList()
            }
            assertEquals(expected, NotificationPolicy.channels(status), status.name)
        }
    }

}
