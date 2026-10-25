package ru.tinsurance.server

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import ru.tinsurance.api.*
import ru.tinsurance.api.operator.*
import ru.tinsurance.server.demo.DemoData
import kotlin.test.*

/**
 * Шаг 5: «Взять в работу» без двойного захвата, автоназначение, возврат в очередь, назначение администратором.
 * Каждый тест заводит своих операторов и своё направление, чтобы не трогать демо-данные и очередь других тестов.
 */
class AssignmentTest {

    /** Обращение прошло анализ и стоит в очереди STANDARD; автоназначение включено. */
    private fun assignmentTest(block: suspend ClaimFlow.(FakeMl) -> Unit) = FakeMl().use { ml ->
        claimFlowTest("analysis.mlUrl" to ml.url, "assignment.autoAssign" to "true") {
            sql("update users set availability = 'OFFLINE' where email like 'assign-%'")
            try {
                block(ml)
            } finally {
                sql("update users set availability = 'OFFLINE' where email like 'assign-%'")
            }
        }
    }

    private suspend fun ClaimFlow.queuedClaim(direction: String? = null): Long {
        val (claim, _) = readyClaim()
        submit(claim.id)
        runWorker()
        direction?.let { sql("update claims set direction_code = '$it' where id = ${claim.id}") }
        return claim.id
    }

    private fun direction(): String =
        "ASSIGN_${System.nanoTime()}".also { sql("insert into directions (code, name) values ('$it', 'Тест назначения')") }

    /** Новый оператор с паролем демо-учёток; по умолчанию не на смене. */
    private suspend fun ClaimFlow.operator(vararg directions: String, max: Int = 5, online: Boolean = false): AuthResponse {
        val email = "assign-${System.nanoTime()}@tstrah.test"
        sql(
            """
            insert into users (role, full_name, email, password_hash, is_active, availability, max_active_claims, created_at)
            select 'OPERATOR', 'Оператор $email', '$email', password_hash, true, '${if (online) "ONLINE" else "OFFLINE"}', $max, now()
              from users where email = '${DemoData.OPERATOR_1}'
            """.trimIndent()
        )
        directions.forEach { sql("insert into operator_directions (operator_id, direction_code) select id, '$it' from users where email = '$email'") }
        return http.loginOperator(email)
    }

    private suspend fun HttpClient.action(token: String, path: String, claimId: Long, body: Any? = null): HttpResponse =
        post(path.path("id" to claimId)) {
            bearerAuth(token)
            body?.let { contentType(ContentType.Application.Json); setBody(it) }
        }

    private fun assignee(claimId: Long) = query("select assigned_operator_id from claims where id = $claimId")?.toLong()

    @Test
    fun twoOperatorsTakeTheSameClaim_onlyOneGetsIt() = assignmentTest {
        val dir = direction()
        val a = operator(dir)
        val b = operator(dir)
        val claimId = queuedClaim(dir)

        val responses = coroutineScope {
            listOf(a, b).map { op -> async { http.action(op.accessToken, OperatorApi.CLAIM_TAKE, claimId) } }.map { it.await() }
        }
        assertEquals(listOf(HttpStatusCode.OK, HttpStatusCode.Conflict), responses.map { it.status }.sortedBy { it.value })
        val winner = responses.single { it.status == HttpStatusCode.OK }.body<ClaimCard>()
        assertEquals(ClaimStatus.ASSIGNED, winner.status)
        assertTrue(winner.assignedOperator!!.id in listOf(a.user.id, b.user.id))
        assertEquals("1", query("select count(*) from claim_status_history where claim_id = $claimId and to_status = 'ASSIGNED'"))
    }

    @Test
    fun takeChecksDirectionAndLimit() = assignmentTest {
        val mine = direction()
        val foreign = direction()
        val op = operator(mine, max = 1)
        val first = queuedClaim(mine)
        val second = queuedClaim(mine)
        val other = queuedClaim(foreign)

        assertEquals(HttpStatusCode.Forbidden, http.action(op.accessToken, OperatorApi.CLAIM_TAKE, other).status)
        assertEquals(HttpStatusCode.OK, http.action(op.accessToken, OperatorApi.CLAIM_TAKE, first).status)
        val overLimit = http.action(op.accessToken, OperatorApi.CLAIM_TAKE, second)
        assertEquals(HttpStatusCode.Conflict, overLimit.status)
        assertTrue(overLimit.bodyAsText().contains("лимит"))
    }

    @Test
    fun startReview_release_andOnlineOperatorPicksItUp() = assignmentTest {
        val dir = direction()
        val a = operator(dir)
        val b = operator(dir)
        val claimId = queuedClaim(dir)
        assertEquals(HttpStatusCode.OK, http.action(a.accessToken, OperatorApi.CLAIM_TAKE, claimId).status)

        assertEquals(HttpStatusCode.Forbidden, http.action(b.accessToken, OperatorApi.CLAIM_START_REVIEW, claimId).status)
        val review = http.action(a.accessToken, OperatorApi.CLAIM_START_REVIEW, claimId)
        assertEquals(ClaimStatus.IN_REVIEW, review.body<ClaimCard>().status)

        assertEquals(HttpStatusCode.BadRequest, http.action(a.accessToken, OperatorApi.CLAIM_RELEASE, claimId, ReleaseClaimRequest(" ")).status)
        assertEquals(HttpStatusCode.Forbidden, http.action(b.accessToken, OperatorApi.CLAIM_RELEASE, claimId, ReleaseClaimRequest("Не моё")).status)
        val released = http.action(a.accessToken, OperatorApi.CLAIM_RELEASE, claimId, ReleaseClaimRequest("Ухожу в отпуск")).body<ClaimCard>()
        // b не на смене — обращение остаётся в очереди
        assertEquals(ClaimStatus.QUEUED, released.status)
        assertNull(released.assignedOperator)
        assertEquals("Ухожу в отпуск", query("select comment from audit_log where claim_id = $claimId and action = 'RELEASE'"))

        val me = http.put(OperatorApi.ME_AVAILABILITY) {
            bearerAuth(b.accessToken); contentType(ContentType.Application.Json)
            setBody(AvailabilityRequest(OperatorAvailability.ONLINE))
        }.body<UserDto>()
        assertEquals(OperatorAvailability.ONLINE, me.availability)
        assertEquals(b.user.id, assignee(claimId))
        assertEquals("ASSIGNED", status(claimId))
    }

    @Test
    fun afterAnalysisClaimGoesToLeastLoadedOnlineOperator() = assignmentTest {
        val a = operator("STANDARD", online = true)
        val b = operator("STANDARD", online = true)

        val first = queuedClaim()
        assertEquals("ASSIGNED", status(first))
        assertEquals(a.user.id, assignee(first))   // при равной нагрузке — первый по порядку
        val second = queuedClaim()
        assertEquals(b.user.id, assignee(second))
    }

    @Test
    fun claimReturnedAfterExtraMaterialsGoesBackToPreviousOperator() = assignmentTest {
        val idle = operator("STANDARD")             // создан раньше: при равной нагрузке выбрали бы его
        val previous = operator("STANDARD", online = true)
        val claimId = queuedClaim()
        assertEquals(previous.user.id, assignee(claimId))

        sql("update users set availability = 'ONLINE' where id = ${idle.user.id}")
        // до шага 6 запрос материалов оператором имитируется в БД
        sql("update claims set status = 'IN_REVIEW' where id = $claimId")
        sql("update claims set status = 'NEEDS_INFO', assigned_operator_id = null where id = $claimId")
        submit(claimId)
        runWorker()
        assertEquals("ASSIGNED", status(claimId))
        assertEquals(previous.user.id, assignee(claimId))
    }

    @Test
    fun returnedClaimInAnotherDirectionGoesToOperatorOfThatDirection() = assignmentTest { ml ->
        val previous = operator("STANDARD", online = true)
        val claimId = queuedClaim()
        assertEquals(previous.user.id, assignee(claimId))
        val expert = operator("MANUAL_REVIEW", online = true)

        sql("update claims set status = 'IN_REVIEW' where id = $claimId")
        sql("update claims set status = 'NEEDS_INFO', assigned_operator_id = null where id = $claimId")
        // новое фото модель не узнаёт уверенно — после повторной отправки обращение уходит на ручную проверку
        ml.respond = { 200 to FakeMl.ok("DENT", 0.3) }
        upload(claimId, noise(seed = 99))
        submit(claimId)
        runWorker()
        assertEquals("MANUAL_REVIEW", query("select direction_code from claims where id = $claimId"))
        assertEquals(expert.user.id, assignee(claimId))
    }

    @Test
    fun adminAssignsAndReassigns() = assignmentTest {
        val dir = direction()
        val a = operator(dir)
        val b = operator(dir)
        val admin = http.loginOperator(DemoData.ADMIN)
        val claimId = queuedClaim(dir)

        assertEquals(HttpStatusCode.Forbidden, http.action(a.accessToken, OperatorApi.CLAIM_ASSIGN, claimId, AssignClaimRequest(a.user.id, "Сам себе")).status)
        assertEquals(HttpStatusCode.BadRequest, http.action(admin.accessToken, OperatorApi.CLAIM_ASSIGN, claimId, AssignClaimRequest(a.user.id, "")).status)

        val assigned = http.action(admin.accessToken, OperatorApi.CLAIM_ASSIGN, claimId, AssignClaimRequest(a.user.id, "Опытный оператор"))
        assertEquals(HttpStatusCode.OK, assigned.status, assigned.bodyAsText())
        assertEquals(a.user.id, assigned.body<ClaimCard>().assignedOperator!!.id)

        val reassigned = http.action(admin.accessToken, OperatorApi.CLAIM_ASSIGN, claimId, AssignClaimRequest(b.user.id, "Оператор заболел")).body<ClaimCard>()
        assertEquals(ClaimStatus.ASSIGNED, reassigned.status)
        assertEquals(b.user.id, reassigned.assignedOperator!!.id)
        assertEquals("Оператор заболел", query("select comment from audit_log where claim_id = $claimId and action = 'REASSIGN'"))

        val operators = http.get(OperatorApi.OPERATORS) { bearerAuth(admin.accessToken) }.body<List<OperatorDto>>()
        assertEquals(1, operators.single { it.id == b.user.id }.activeClaims)
        assertEquals(listOf(dir), operators.single { it.id == a.user.id }.directions)
        assertEquals(HttpStatusCode.Forbidden, http.get(OperatorApi.OPERATORS) { bearerAuth(a.accessToken) }.status)
        val adminShift = http.put(OperatorApi.ME_AVAILABILITY) {
            bearerAuth(admin.accessToken); contentType(ContentType.Application.Json)
            setBody(AvailabilityRequest(OperatorAvailability.ONLINE))
        }
        assertEquals(HttpStatusCode.Forbidden, adminShift.status)
    }
}
