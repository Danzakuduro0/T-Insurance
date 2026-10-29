package ru.tinsurance.server

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.jsonPrimitive
import ru.tinsurance.api.*
import ru.tinsurance.api.mobile.UpdateClaimRequest
import ru.tinsurance.api.operator.*
import ru.tinsurance.server.demo.DemoData
import kotlin.random.Random
import kotlin.test.*

class OperatorActionsTest {
    private fun flow(autoAssign: Boolean = false, block: suspend ClaimFlow.(Long, List<ClaimFileDto>, AuthResponse, FakeMl) -> Unit) = FakeMl().use { ml ->
        claimFlowTest("analysis.mlUrl" to ml.url, "assignment.defaultMaxActiveClaims" to "100", "assignment.autoAssign" to autoAssign.toString()) {
            val (claim, files) = readyClaim()
            submit(claim.id)
            runWorker()
            val operator = http.loginOperator(DemoData.OPERATOR_1)
            assertEquals(HttpStatusCode.OK, action(operator.accessToken, claim.id, "take").status)
            assertEquals(HttpStatusCode.OK, action(operator.accessToken, claim.id, "start-review").status)
            try { block(claim.id, files, operator, ml) } finally {
                sql("update claims set status = 'CANCELLED', closed_at = now() where id = ${claim.id} and status in ('ASSIGNED', 'IN_REVIEW', 'NEEDS_INFO')")
            }
        }
    }

    private suspend fun ClaimFlow.action(auth: String, id: Long, suffix: String, body: Any? = null, put: Boolean = false) =
        http.request("/api/operator/claims/$id/$suffix") {
            method = if (put) HttpMethod.Put else HttpMethod.Post
            bearerAuth(auth)
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }

    private fun materials(vararg items: RequestedMaterial) = RequestMaterialsRequest("Уточните материалы", items.toList())
    private suspend fun ClaimFlow.send(id: Long) = http.post("/api/mobile/claims/$id/submit") { bearerAuth(token) }

    @Test
    fun additionalPhotoMustBeNewAndRejectedAnalysisKeepsRequestOpen() = flow { id, _, operator, ml ->
        val request = materials(RequestedMaterial(documentType = "DAMAGE_PHOTO", comment = "Снимите стекло изнутри"))
        assertEquals(HttpStatusCode.OK, action(operator.accessToken, id, "request-materials", request).status)
        assertEquals("NEEDS_INFO", status(id))
        val requestId = query("select id from info_requests where claim_id = $id and status = 'OPEN'")
        assertEquals(HttpStatusCode.Conflict, send(id).status)
        val file = upload(id, noise(Random.nextInt()))
        ml.respond = { if (it["document_id"]!!.jsonPrimitive.content == file.id.toString()) 422 to FakeMl.badQuality("BLURRY") else 200 to FakeMl.ok("DENT", 0.9) }
        assertEquals(HttpStatusCode.OK, send(id).status)
        assertEquals("OPEN", query("select status from info_requests where id = $requestId"))
        runWorker()
        assertEquals("NEEDS_INFO", status(id))
        assertEquals("OPEN", query("select status from info_requests where id = $requestId"))
        ml.respond = { 200 to FakeMl.ok("DENT", 0.9) }
        upload(id, noise(Random.nextInt()), replaces = file.id)
        assertEquals(HttpStatusCode.OK, send(id).status)
        runWorker()
        assertEquals("QUEUED", status(id))
        assertEquals("FULFILLED", query("select status from info_requests where id = $requestId"))
        assertNull(query("select assigned_operator_id from claims where id = $id"))
    }

    @Test
    fun specificReplacementCannotBeSatisfiedByUnrelatedPhoto() = flow { id, files, operator, _ ->
        val request = materials(RequestedMaterial(documentType = "DAMAGE_PHOTO", fileId = files.first().id, comment = "Замените этот снимок"))
        assertEquals(HttpStatusCode.OK, action(operator.accessToken, id, "request-materials", request).status)
        upload(id, noise(Random.nextInt()))
        assertEquals(HttpStatusCode.Conflict, send(id).status)
        upload(id, noise(Random.nextInt()), replaces = files.first().id)
        assertEquals(HttpStatusCode.OK, send(id).status)
        runWorker()
        assertEquals("QUEUED", status(id))
    }

    @Test
    fun fieldRequestRequiresClientResponseAndRestrictsOtherFields() = flow { id, _, operator, _ ->
        assertEquals(HttpStatusCode.OK, action(operator.accessToken, id, "request-materials",
            materials(RequestedMaterial(fieldName = "description", comment = "Опишите обстоятельства"))).status)
        assertEquals(HttpStatusCode.Conflict, send(id).status)
        val forbidden = http.patch("/api/mobile/claims/$id") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(UpdateClaimRequest(eventLocation = "Другое место"))
        }
        assertEquals(HttpStatusCode.Conflict, forbidden.status)
        val updated = http.patch("/api/mobile/claims/$id") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(UpdateClaimRequest(description = "Уточнённые обстоятельства"))
        }
        assertEquals(HttpStatusCode.OK, updated.status)
        assertEquals(HttpStatusCode.OK, send(id).status)
        runWorker()
        assertEquals("QUEUED", status(id))
    }

    @Test
    fun resultsKeepHistoryAndCompletionShowsOnlyClientComment() = flow { id, _, operator, _ ->
        assertEquals(HttpStatusCode.Conflict, action(operator.accessToken, id, "complete").status)
        val internal = "Внутренняя заметка эксперта"
        val publicText = "Рассмотрение материалов завершено"
        repeat(2) { index ->
            assertEquals(HttpStatusCode.OK, action(operator.accessToken, id, "review-result",
                ReviewResultRequest("$internal $index", publicText), put = true).status)
        }
        assertEquals("2", query("select count(*) from claim_decisions where claim_id = $id and kind = 'REVIEW_RESULT'"))
        assertEquals("1", query("select count(*) from claim_decisions where claim_id = $id and kind = 'REVIEW_RESULT' and is_current"))
        val responses = coroutineScope {
            val a = async { action(operator.accessToken, id, "complete").status }
            val b = async { action(operator.accessToken, id, "complete").status }
            listOf(a.await(), b.await())
        }
        assertEquals(1, responses.count { it == HttpStatusCode.OK })
        assertEquals(1, responses.count { it == HttpStatusCode.Conflict })
        assertEquals("COMPLETED", status(id))
        assertNotNull(query("select closed_at from claims where id = $id"))
        val timeline = http.get("/api/mobile/claims/$id/timeline") { bearerAuth(token) }.body<String>()
        assertFalse(timeline.contains(internal))
        assertTrue(timeline.contains(publicText))
    }

    @Test
    fun onlyAssigneeCanActAndInvalidRequestsDoNotChangeState() = flow { id, files, operator, _ ->
        val other = http.loginOperator(DemoData.OPERATOR_2)
        assertEquals(HttpStatusCode.Forbidden, action(other.accessToken, id, "review-result", ReviewResultRequest("Итог", "Готово"), true).status)
        assertEquals(HttpStatusCode.Unauthorized, action(token, id, "complete").status)
        val invalid = listOf(
            RequestMaterialsRequest("", emptyList()),
            materials(RequestedMaterial(fieldName = "unknown", comment = "Поле")),
            materials(RequestedMaterial(documentType = "DAMAGE_PHOTO", fileId = Long.MAX_VALUE, comment = "Чужое фото")),
            materials(RequestedMaterial(documentType = "DAMAGE_PHOTO", minCount = 15, comment = "Слишком много")),
            materials(RequestedMaterial(documentType = "DAMAGE_PHOTO", fileId = files[0].id, fieldName = "description", comment = "Оба")),
        )
        invalid.forEach { assertEquals(HttpStatusCode.BadRequest, action(operator.accessToken, id, "request-materials", it).status) }
        assertEquals("IN_REVIEW", status(id))
        assertEquals("0", query("select count(*) from info_requests where claim_id = $id"))
        assertEquals(HttpStatusCode.BadRequest, action(operator.accessToken, id, "cancel", CancelClaimRequest(" ", "Закрыто")).status)
    }

    @Test
    fun cancellationClosesRequestAndHidesInternalReason() = flow { id, _, operator, _ ->
        action(operator.accessToken, id, "request-materials", materials(RequestedMaterial(fieldName = "description", comment = "Уточните")))
        val reason = "Служебная причина закрытия"
        assertEquals(HttpStatusCode.OK, action(operator.accessToken, id, "cancel", CancelClaimRequest(reason, "Обращение закрыто без обработки")).status)
        assertEquals("CANCELLED", status(id))
        assertEquals("CANCELLED", query("select status from info_requests where claim_id = $id"))
        assertEquals(reason, query("select reason from claim_status_history where claim_id = $id and to_status = 'CANCELLED'"))
        assertEquals(HttpStatusCode.Conflict, action(operator.accessToken, id, "cancel", CancelClaimRequest(reason, "Закрыто")).status)
    }

    @Test
    fun priorResultCannotCloseNewReviewRound() = flow { id, _, operator, _ ->
        action(operator.accessToken, id, "review-result", ReviewResultRequest("Первый итог", "Проверено"), true)
        action(operator.accessToken, id, "request-materials", materials(RequestedMaterial(documentType = "DAMAGE_PHOTO", comment = "Ещё фото")))
        upload(id, noise(Random.nextInt()))
        send(id); runWorker()
        action(operator.accessToken, id, "take")
        action(operator.accessToken, id, "start-review")
        assertEquals(HttpStatusCode.Conflict, action(operator.accessToken, id, "complete").status)
    }
    @Test
    fun adminCanCancelQueuedClaimButOperatorCannot() = flow { id, _, operator, _ ->
        assertEquals(HttpStatusCode.OK, action(operator.accessToken, id, "release", ReleaseClaimRequest("Передача")).status)
        val request = CancelClaimRequest("Дубликат обращения", "Обращение закрыто: материалы уже рассматриваются")
        assertEquals(HttpStatusCode.Forbidden, action(operator.accessToken, id, "cancel", request).status)
        val admin = http.loginOperator(DemoData.ADMIN)
        assertEquals(HttpStatusCode.OK, action(admin.accessToken, id, "cancel", request).status)
        assertEquals("CANCELLED", status(id))
    }

    @Test
    fun resultRequiresStartedReview() = flow { id, _, operator, _ ->
        action(operator.accessToken, id, "release", ReleaseClaimRequest("Вернуть в очередь"))
        action(operator.accessToken, id, "take")
        assertEquals("ASSIGNED", status(id))
        assertEquals(HttpStatusCode.Conflict, action(operator.accessToken, id, "review-result",
            ReviewResultRequest("Результат", "Готово"), true).status)
        assertEquals(HttpStatusCode.Conflict, action(operator.accessToken, id, "request-materials",
            materials(RequestedMaterial(fieldName = "description", comment = "Уточните"))).status)
    }

    @Test
    fun completedClaimFreesSlotForNextQueuedClaim() = flow(autoAssign = true) { id, _, operator, _ ->
        val key = "actions_${System.nanoTime()}"
        val email = "$key@test.local"
        sql("insert into directions (code, name) values ('$key', 'Изолированная очередь')")
        sql("insert into users (role, full_name, email, password_hash, is_active, availability, max_active_claims, created_at) select 'OPERATOR', 'Новый оператор', '$email', password_hash, true, 'ONLINE', 1, now() from users where id = ${operator.user.id}")
        val dedicated = http.loginOperator(email)
        val opId = dedicated.user.id
        sql("insert into operator_directions (operator_id, direction_code) values ($opId, '$key')")
        sql("update claims set direction_code = '$key' where id = $id")
        val admin = http.loginOperator(DemoData.ADMIN)
        assertEquals(HttpStatusCode.OK, action(admin.accessToken, id, "assign", AssignClaimRequest(opId, "Тест освобождения места")).status)
        var nextId: Long? = null
        try {
            val (next, _) = readyClaim()
            nextId = next.id
            submit(next.id); runWorker()
            sql("update claims set direction_code = '$key' where id = ${next.id}")
            assertEquals("QUEUED", status(next.id))
            action(dedicated.accessToken, id, "review-result", ReviewResultRequest("Проверка окончена", "Готово"), true)
            assertEquals(HttpStatusCode.OK, action(dedicated.accessToken, id, "complete").status)
            assertEquals("ASSIGNED", status(next.id))
            assertEquals(opId.toString(), query("select assigned_operator_id from claims where id = ${next.id}"))
        } finally {
            sql("update users set availability = 'OFFLINE', max_active_claims = null where id = $opId")
            nextId?.let { sql("update claims set status = 'CANCELLED', closed_at = now() where id = $it and status in ('ASSIGNED', 'QUEUED')") }
        }
    }

}
