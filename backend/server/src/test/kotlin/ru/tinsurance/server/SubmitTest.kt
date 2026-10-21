package ru.tinsurance.server

import ru.tinsurance.api.*
import ru.tinsurance.server.demo.DemoData
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import java.time.OffsetDateTime
import kotlin.test.*

/** Шаг 1 плана: отправка с проверкой комплектности (CHECK-002). Клиент — Олег, чтобы не мешать другим тестам. */
class SubmitTest {

    private class Session(val client: HttpClient, val token: String, val userId: Long, val policyId: Long)

    private suspend fun HttpClient.session(): Session {
        val auth = loginClient(DemoData.CLIENT_OLEG, installationId = "submit-test")
        val policy = get("/api/mobile/policies") { bearerAuth(auth.accessToken) }.body<List<PolicyDto>>().first()
        return Session(this, auth.accessToken, auth.user.id, policy.id)
    }

    private suspend fun Session.draft(request: CreateClaimRequest): ClaimDetails =
        client.post("/api/mobile/claims") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(request)
        }.body()

    private suspend fun Session.fullDraft(eventType: String = "CAR_GLASS") = draft(
        CreateClaimRequest(
            policyId = policyId,
            eventType = eventType,
            eventAt = OffsetDateTime.now().minusDays(1).toString(),
            eventLocation = "Новосибирск, ул. Гоголя, 1",
            description = "Трещина на лобовом стекле",
        )
    )

    private suspend fun Session.submit(id: Long): HttpResponse = client.post("/api/mobile/claims/$id/submit") { bearerAuth(token) }

    private suspend fun Session.timeline(id: Long) =
        client.get("/api/mobile/claims/$id/timeline") { bearerAuth(token) }.body<List<TimelineEvent>>().map { it.status }

    /** Принятый файл напрямую в БД — загрузку файлов делает шаг 2. */
    private fun Session.acceptedFile(claimId: Long, documentType: String, count: Int = 1) = repeat(count) {
        TestDatabase.connection().use { c ->
            c.prepareStatement(
                "insert into claim_files(claim_id, document_type, storage_key, original_name, mime_type, size_bytes, sha256, status, uploaded_by) " +
                    "values (?, ?, ?, 'f.jpg', 'image/jpeg', 1000, ?, 'ACCEPTED', ?)"
            ).use { st ->
                val key = "test/${System.nanoTime()}"
                st.setLong(1, claimId); st.setString(2, documentType); st.setString(3, key); st.setString(4, key); st.setLong(5, userId)
                st.executeUpdate()
            }
        }
    }

    private fun queryLong(sql: String): Long = TestDatabase.connection().use { c ->
        c.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) } }
    }

    @Test
    fun draftWithoutCoreFieldsStaysDraft() = apiTest { http ->
        val s = http.session()
        val claim = s.draft(CreateClaimRequest(description = "Только описание"))

        val response = s.submit(claim.id)
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.body<ErrorResponse>()
        assertEquals("INCOMPLETE_DRAFT", error.error)
        assertTrue(error.message!!.contains("Полис"))
        assertEquals(listOf(ClaimStatus.DRAFT), s.timeline(claim.id))
    }

    @Test
    fun incompleteMaterialsDetectedBeforeProcessing() = apiTest { http ->
        val s = http.session()
        val claim = s.draft(
            CreateClaimRequest(policyId = s.policyId, eventType = "CAR_GLASS", eventAt = OffsetDateTime.now().minusDays(1).toString())
        )
        s.acceptedFile(claim.id, "DAMAGE_PHOTO", 1)

        // чек-лист до отправки совпадает с тем, что найдёт отправка
        val check = http.get("/api/mobile/claims/${claim.id}/completeness") { bearerAuth(s.token) }.body<CompletenessDto>()
        assertFalse(check.complete)
        assertEquals(listOf("eventLocation", "description"), check.missingFields.map { it.field })
        assertEquals(listOf(MissingDocumentDto("DAMAGE_PHOTO", "Фото повреждений", 2, 1)), check.missingDocuments)

        val response = s.submit(claim.id)
        assertEquals(HttpStatusCode.OK, response.status)
        val result = response.body<ClaimDetails>()
        assertEquals(ClaimStatus.NEEDS_INFO, result.status)

        val request = result.openInfoRequest!!
        assertEquals(
            listOf(Triple("DAMAGE_PHOTO", 1, "MISSING")),
            request.items.filter { it.documentType != null }.map { Triple(it.documentType, it.minCount, it.issue) },
        )
        assertEquals(listOf("eventLocation", "description"), request.items.mapNotNull { it.fieldName })

        // основная обработка не началась
        assertEquals(listOf(ClaimStatus.DRAFT, ClaimStatus.SUBMITTED, ClaimStatus.NEEDS_INFO), s.timeline(claim.id))
        assertEquals(0, queryLong("select count(*) from claim_status_history where claim_id = ${claim.id} and to_status = 'IN_ANALYSIS'"))

        val list = http.get("/api/mobile/claims") { bearerAuth(s.token) }.body<List<ClaimListItem>>()
        assertTrue(list.single { it.id == claim.id }.actionRequired)
    }

    @Test
    fun resubmitAfterAddingMaterialsGoesToAnalysis() = apiTest { http ->
        val s = http.session()
        val claim = s.draft(
            CreateClaimRequest(policyId = s.policyId, eventType = "CAR_GLASS", eventAt = OffsetDateTime.now().minusDays(1).toString())
        )
        assertEquals(ClaimStatus.NEEDS_INFO, s.submit(claim.id).body<ClaimDetails>().status)
        val firstSubmittedAt = queryLong("select extract(epoch from submitted_at)::bigint from claims where id = ${claim.id}")

        // повторная отправка, когда не хватает по-прежнему: старый запрос отменён, новый открыт
        s.acceptedFile(claim.id, "DAMAGE_PHOTO", 2)
        assertEquals(ClaimStatus.NEEDS_INFO, s.submit(claim.id).body<ClaimDetails>().status)
        assertEquals(1, queryLong("select count(*) from info_requests where claim_id = ${claim.id} and status = 'CANCELLED'"))
        assertEquals(1, queryLong("select count(*) from info_requests where claim_id = ${claim.id} and status = 'OPEN'"))

        // дозаполнили поля и документы
        val patched = http.patch("/api/mobile/claims/${claim.id}") {
            bearerAuth(s.token); contentType(ContentType.Application.Json)
            setBody(UpdateClaimRequest(eventLocation = "Трасса Р-254", description = "Скол от камня"))
        }
        assertEquals(HttpStatusCode.OK, patched.status)

        val done = s.submit(claim.id).body<ClaimDetails>()
        assertEquals(ClaimStatus.IN_ANALYSIS, done.status)
        assertNull(done.openInfoRequest)
        assertEquals(1, queryLong("select count(*) from info_requests where claim_id = ${claim.id} and status = 'FULFILLED'"))
        assertEquals(
            listOf(
                ClaimStatus.DRAFT, ClaimStatus.SUBMITTED, ClaimStatus.NEEDS_INFO, ClaimStatus.SUBMITTED, ClaimStatus.NEEDS_INFO,
                ClaimStatus.SUBMITTED, ClaimStatus.IN_ANALYSIS,
            ),
            s.timeline(claim.id),
        )
        // время первой отправки сохраняется — повтор не отодвигает обращение в конец очереди
        assertEquals(firstSubmittedAt, queryLong("select extract(epoch from submitted_at)::bigint from claims where id = ${claim.id}"))

        // решения о комплектности: три, действует последнее, каждое ссылается на предыдущее
        assertEquals(3, queryLong("select count(*) from claim_decisions where claim_id = ${claim.id} and kind = 'COMPLETENESS'"))
        assertEquals(1, queryLong("select count(*) from claim_decisions where claim_id = ${claim.id} and kind = 'COMPLETENESS' and is_current"))
        assertEquals(2, queryLong("select count(*) from claim_decisions where claim_id = ${claim.id} and kind = 'COMPLETENESS' and supersedes_id is not null"))
    }

    @Test
    fun completeClaimGoesStraightToAnalysis() = apiTest { http ->
        val s = http.session()
        val claim = s.fullDraft()
        s.acceptedFile(claim.id, "DAMAGE_PHOTO", 2)

        assertEquals(ClaimStatus.IN_ANALYSIS, s.submit(claim.id).body<ClaimDetails>().status)
        assertEquals(listOf(ClaimStatus.DRAFT, ClaimStatus.SUBMITTED, ClaimStatus.IN_ANALYSIS), s.timeline(claim.id))
    }

    @Test
    fun submittedClaimCannotBeSubmittedOrEditedAgain() = apiTest { http ->
        val s = http.session()
        val claim = s.fullDraft()
        s.acceptedFile(claim.id, "DAMAGE_PHOTO", 2)
        s.submit(claim.id)

        assertEquals(HttpStatusCode.Conflict, s.submit(claim.id).status)
        val edit = http.patch("/api/mobile/claims/${claim.id}") {
            bearerAuth(s.token); contentType(ContentType.Application.Json); setBody(UpdateClaimRequest(description = "ещё"))
        }
        assertEquals(HttpStatusCode.Conflict, edit.status)
    }

    @Test
    fun eventDateValidation() = apiTest { http ->
        val s = http.session()
        val future = s.draft(
            CreateClaimRequest(policyId = s.policyId, eventType = "CAR_GLASS", eventAt = OffsetDateTime.now().plusDays(2).toString())
        )
        assertEquals(HttpStatusCode.BadRequest, s.submit(future.id).status)

        val beforePolicy = s.draft(
            CreateClaimRequest(policyId = s.policyId, eventType = "CAR_GLASS", eventAt = "2020-01-01T12:00:00+07:00")
        )
        val response = s.submit(beforePolicy.id)
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.body<ErrorResponse>().message!!.contains("периода действия полиса"))
        assertEquals(listOf(ClaimStatus.DRAFT), s.timeline(beforePolicy.id))
    }

    @Test
    fun cannotSubmitSomeoneElsesClaim() = apiTest { http ->
        val s = http.session()
        val claim = s.fullDraft()
        val anna = http.loginClient(DemoData.CLIENT_ANNA, installationId = "anna-submit").accessToken
        assertEquals(HttpStatusCode.NotFound, http.post("/api/mobile/claims/${claim.id}/submit") { bearerAuth(anna) }.status)
        assertEquals(HttpStatusCode.NotFound, http.get("/api/mobile/claims/${claim.id}/completeness") { bearerAuth(anna) }.status)
    }
}
