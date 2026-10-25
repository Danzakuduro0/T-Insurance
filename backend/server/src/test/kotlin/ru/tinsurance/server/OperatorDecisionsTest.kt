package ru.tinsurance.server

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.tinsurance.api.*
import ru.tinsurance.api.operator.*
import ru.tinsurance.server.demo.DemoData
import kotlin.test.*

/** Шаг 4: оператор подтверждает или исправляет решения системы (AC-04). */
class OperatorDecisionsTest {

    /** Обращение прошло анализ (вмятина, 90%) и стоит в очереди STANDARD с приоритетом 1. */
    private fun decisionsTest(block: suspend ClaimFlow.(claimId: Long) -> Unit) = FakeMl().use { ml ->
        claimFlowTest("analysis.mlUrl" to ml.url) {
            val (claim, _) = readyClaim()
            submit(claim.id)
            runWorker()
            assertEquals("QUEUED", status(claim.id))
            block(claim.id)
        }
    }

    /** До шага 5 обращение назначается напрямую в БД — Марии: на демо-обращение Дмитрия опираются другие тесты. */
    private fun assign(claimId: Long, operatorId: Long) =
        sql("update claims set status = 'ASSIGNED', assigned_operator_id = $operatorId where id = $claimId")

    private suspend fun HttpClient.card(token: String, claimId: Long) =
        get(OperatorApi.CLAIM.path("id" to claimId)) { bearerAuth(token) }.body<ClaimCard>()

    private suspend fun HttpClient.confirm(token: String, claimId: Long, kind: DecisionKind) =
        post(OperatorApi.CLAIM_DECISION_CONFIRM.path("id" to claimId)) {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(ConfirmDecisionRequest(kind))
        }

    private suspend fun HttpClient.override(token: String, claimId: Long, request: OverrideDecisionRequest) =
        post(OperatorApi.CLAIM_DECISION_OVERRIDE.path("id" to claimId)) {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(request)
        }

    private fun ClaimCard.current(kind: DecisionKind) = decisions.single { it.kind == kind && it.isCurrent }

    @Test
    fun assignedOperatorConfirmsSystemDecision() = decisionsTest { claimId ->
        val operator = http.loginOperator(DemoData.OPERATOR_1)
        assign(claimId, operator.user.id)

        val response = http.confirm(operator.accessToken, claimId, DecisionKind.DAMAGE)
        assertEquals(HttpStatusCode.OK, response.status)
        val card = response.body<ClaimCard>()
        val confirmed = card.current(DecisionKind.DAMAGE)
        val original = card.decisions.single { it.id == confirmed.supersedesId }
        assertEquals(DecisionAction.CONFIRM, confirmed.action)
        assertEquals(DecisionSource.OPERATOR, confirmed.source)
        assertEquals(original.value, confirmed.value)
        assertEquals("Мария Иванова", confirmed.createdBy)
        assertEquals(DecisionSource.SYSTEM, original.source)
        assertFalse(original.isCurrent)

        assertEquals(HttpStatusCode.Conflict, http.confirm(operator.accessToken, claimId, DecisionKind.DAMAGE).status)
    }

    @Test
    fun overrideNeedsReasonAndKeepsOriginalDecision() = decisionsTest { claimId ->
        val operator = http.loginOperator(DemoData.OPERATOR_1)
        assign(claimId, operator.user.id)
        val token = operator.accessToken

        fun routing(direction: String?, reason: String) = OverrideDecisionRequest(DecisionKind.ROUTING, reason, direction = direction)
        assertEquals(HttpStatusCode.BadRequest, http.override(token, claimId, routing("MANUAL_REVIEW", "  ")).status)
        assertEquals(HttpStatusCode.BadRequest, http.override(token, claimId, routing("STANDARD", "Без изменений")).status)
        assertEquals(HttpStatusCode.BadRequest, http.override(token, claimId, routing("UNKNOWN", "Опечатка")).status)
        assertEquals(HttpStatusCode.BadRequest, http.override(token, claimId, routing(null, "Без направления")).status)
        val badPriority = OverrideDecisionRequest(DecisionKind.PRIORITY, "Срочно", priority = 5)
        assertEquals(HttpStatusCode.BadRequest, http.override(token, claimId, badPriority).status)

        val reason = "На фото повреждены две детали — нужна проверка эксперта"
        val response = http.override(token, claimId, routing("MANUAL_REVIEW", reason))
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val card = response.body<ClaimCard>()
        assertEquals("MANUAL_REVIEW", card.directionCode)
        assertTrue(card.requiresManualReview)
        val overridden = card.current(DecisionKind.ROUTING)
        assertEquals(DecisionAction.OVERRIDE, overridden.action)
        assertEquals(reason, overridden.reason)
        assertEquals("MANUAL_REVIEW", (overridden.value as JsonObject)["direction"]!!.jsonPrimitive.content)
        val original = card.decisions.single { it.id == overridden.supersedesId }
        assertEquals(DecisionAction.SET, original.action)
        assertEquals("STANDARD", (original.value as JsonObject)["direction"]!!.jsonPrimitive.content)

        assertEquals(reason, query("select comment from audit_log where claim_id = $claimId and action = 'DECISION_OVERRIDE'"))
        assertEquals("STANDARD", query(
            "select old_value ->> 'direction' from audit_log where claim_id = $claimId and action = 'DECISION_OVERRIDE'"
        ))
    }

    @Test
    fun onlyAssignedOperatorChangesDecisions() = decisionsTest { claimId ->
        val assigned = http.loginOperator(DemoData.OPERATOR_1)
        val other = http.loginOperator(DemoData.OPERATOR_2)
        val priority = OverrideDecisionRequest(DecisionKind.PRIORITY, "Клиент в больнице", priority = 3)

        // в очереди обращение ещё никому не назначено
        assertEquals(HttpStatusCode.Forbidden, http.override(assigned.accessToken, claimId, priority).status)

        assign(claimId, assigned.user.id)
        assertEquals(HttpStatusCode.Forbidden, http.override(other.accessToken, claimId, priority).status)
        assertEquals(HttpStatusCode.Forbidden, http.confirm(other.accessToken, claimId, DecisionKind.DAMAGE).status)
        assertEquals(HttpStatusCode.BadRequest, http.confirm(assigned.accessToken, claimId, DecisionKind.COMPLETENESS).status)
        assertEquals(1, http.card(assigned.accessToken, claimId).priority)
    }

    @Test
    fun adminChangesPriorityOfQueuedClaim() = decisionsTest { claimId ->
        val admin = http.loginOperator(DemoData.ADMIN)
        val response = http.override(admin.accessToken, claimId, OverrideDecisionRequest(DecisionKind.PRIORITY, "Жалоба клиента", priority = 3))
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())

        val number = response.body<ClaimCard>().claimNumber
        val queue = http.get(OperatorApi.QUEUE) { bearerAuth(admin.accessToken); parameter("search", number) }.body<Page<QueueItem>>()
        assertEquals(3, queue.items.single().priority)
        assertEquals("Администратор", http.card(admin.accessToken, claimId).current(DecisionKind.PRIORITY).createdBy)
    }

    @Test
    fun directionsAreListedForTheOverrideForm() = apiTest { http ->
        val token = http.loginOperator().accessToken
        val directions = http.get(OperatorApi.DIRECTIONS) { bearerAuth(token) }.body<List<DirectionDto>>()
        assertTrue(directions.map { it.code }.containsAll(listOf("STANDARD", "MANUAL_REVIEW")))
    }

    @Test
    fun staleSessionsAreDeletedByMaintenance() = claimFlowTest {
        sql(
            """
            insert into auth_sessions (user_id, client_type, refresh_token_hash, created_at, last_used_at, expires_at)
            values ($userId, 'WEB', 'stale-session', now() - interval '100 days', now() - interval '100 days', now() - interval '40 days')
            """.trimIndent()
        )
        worker.maintenance()
        assertNull(query("select id from auth_sessions where refresh_token_hash = 'stale-session'"))
        assertNotNull(query("select id from auth_sessions where user_id = $userId and revoked_at is null and expires_at > now()"))
    }
}
