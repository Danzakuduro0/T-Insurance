package ru.tinsurance.server

import ru.tinsurance.api.*
import ru.tinsurance.server.demo.DemoData
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import java.time.OffsetDateTime
import kotlin.test.*

/**
 * Правила из ответов на вопросы документа уточнений (номера — в именах и комментариях).
 * Клиент — Анна, чтобы не мешать тестам, которые проверяют обращения Ивана и Олега.
 */
class AnswersAlignmentTest {

    private class Session(val http: HttpClient, val token: String, val userId: Long, val phone: String, val policyId: Long)

    private suspend fun HttpClient.anna(): Session {
        val auth = loginClient(DemoData.CLIENT_ANNA, installationId = "answers-test")
        val policy = get("/api/mobile/policies") { bearerAuth(auth.accessToken) }.body<List<PolicyDto>>().first()
        return Session(this, auth.accessToken, auth.user.id, auth.user.phone!!, policy.id)
    }

    private suspend fun Session.accidentDraft(details: JsonObject? = null, participants: List<ParticipantInput>? = null) =
        http.post("/api/mobile/claims") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(
                CreateClaimRequest(
                    policyId = policyId, eventType = "CAR_ACCIDENT", eventAt = OffsetDateTime.now().minusHours(5).toString(),
                    eventLocation = "Новосибирск, Красный проспект, 1", description = "Столкновение на перекрёстке",
                    details = details, participants = participants,
                )
            )
        }

    private suspend fun Session.submit(id: Long) = http.post("/api/mobile/claims/$id/submit") { bearerAuth(token) }.body<ClaimDetails>()

    private suspend fun Session.patch(id: Long, body: UpdateClaimRequest): HttpResponse =
        http.patch("/api/mobile/claims/$id") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(body) }

    private fun sql(statement: String) = TestDatabase.connection().use { c -> c.createStatement().use { it.execute(statement) } }

    private fun <T> query(sql: String, map: (java.sql.ResultSet) -> T): T = TestDatabase.connection().use { c ->
        c.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next(); map(rs) } }
    }

    private fun Session.file(claimId: Long, type: String, status: String = "ACCEPTED", reason: String? = null): Long =
        query(
            "insert into claim_files(claim_id, document_type, storage_key, original_name, mime_type, size_bytes, sha256, status, rejection_reason, uploaded_by) " +
                "values ($claimId, '$type', 'test/${System.nanoTime()}', 'f.jpg', 'image/jpeg', 1000, 'h${System.nanoTime()}', '$status', " +
                (reason?.let { "'$it'" } ?: "null") + ", $userId) returning id"
        ) { it.getLong(1) }

    @Test
    fun q7_formRulesComeFromReferenceTables() = apiTest { http ->
        val s = http.anna()
        val types = http.get("/api/mobile/event-types") { bearerAuth(s.token) }.body<List<EventTypeDto>>()
        val accident = types.single { it.code == "CAR_ACCIDENT" }

        assertEquals(
            listOf("eventLocation", "description", "contactPhone", "participants", "circumstances"),
            accident.fields.filter { it.required }.map { it.code },
        )
        assertEquals(listOf("policeCalled", "europrotocol"), accident.fields.filterNot { it.required }.map { it.code })
        // вопрос 13: для ДТП — фотографии и извещение/заявление
        assertEquals(listOf("ACCIDENT_NOTICE" to 1, "DAMAGE_PHOTO" to 3), accident.documents.map { it.documentType to it.minCount })
        // вопрос 14: JPEG, PNG, PDF; HEIC не поддерживается
        assertTrue(accident.documents.none { "image/heic" in it.allowedMimeTypes })
        // вопрос 2: дополнительные поля зависят от типа
        assertTrue(types.single { it.code == "CAR_GLASS" }.fields.none { it.code == "participants" })
    }

    @Test
    fun q6_accidentNeedsParticipantsAndCircumstances_contactDefaultsToClientPhone() = apiTest { http ->
        val s = http.anna()
        val claim = s.accidentDraft().body<ClaimDetails>()
        assertEquals(s.phone, claim.contactPhone)

        val result = s.submit(claim.id)
        assertEquals(ClaimStatus.NEEDS_INFO, result.status)
        val fields = result.openInfoRequest!!.items.mapNotNull { it.fieldName }
        assertEquals(listOf("participants", "circumstances"), fields)
    }

    @Test
    fun q7_detailsAreValidatedAgainstEventType() = apiTest { http ->
        val s = http.anna()
        val unknown = s.accidentDraft(details = buildJsonObject { put("hairColor", "рыжий") })
        assertEquals(HttpStatusCode.BadRequest, unknown.status)
        val wrongType = s.accidentDraft(details = buildJsonObject { put("policeCalled", "да") })
        assertEquals(HttpStatusCode.BadRequest, wrongType.status)
        val ok = s.accidentDraft(details = buildJsonObject { put("policeCalled", true); put("circumstances", "Не уступил дорогу") })
        assertEquals(HttpStatusCode.Created, ok.status)
    }

    @Test
    fun q10_q42_afterSubmitOnlyRequestedFieldsChange_withOldAndNewInJournal() = apiTest { http ->
        val s = http.anna()
        val claim = s.accidentDraft(participants = listOf(ParticipantInput("APPLICANT_DRIVER", "Анна Смирнова"))).body<ClaimDetails>()
        assertEquals(ClaimStatus.NEEDS_INFO, s.submit(claim.id).status)  // не хватает обстоятельств и документов

        // незапрошенное поле менять нельзя
        val notRequested = s.patch(claim.id, UpdateClaimRequest(description = "Другое описание"))
        assertEquals(HttpStatusCode.Conflict, notRequested.status)

        // запрошенное — можно, и в журнале есть старое и новое значение
        val requested = s.patch(claim.id, UpdateClaimRequest(details = buildJsonObject { put("circumstances", "Второй участник не уступил дорогу") }))
        assertEquals(HttpStatusCode.OK, requested.status)
        val (oldValue, newValue, comment) = query(
            "select old_value::text, new_value::text, comment from audit_log where claim_id = ${claim.id} and action = 'UPDATE' order by id desc limit 1"
        ) { Triple(it.getString(1), it.getString(2), it.getString(3)) }
        assertEquals("""{"circumstances": null}""", oldValue)
        assertTrue(newValue.contains("Второй участник не уступил дорогу"))
        assertEquals("Уточнение по запросу", comment)
    }

    @Test
    fun q12_timelineShowsKeyStagesOnly_andResubmissionIsMaterialsReceived() = apiTest { http ->
        val s = http.anna()
        val claim = s.accidentDraft(
            details = buildJsonObject { put("circumstances", "Не уступил дорогу") },
            participants = listOf(ParticipantInput("APPLICANT_DRIVER", "Анна Смирнова")),
        ).body<ClaimDetails>()
        assertEquals(ClaimStatus.NEEDS_INFO, s.submit(claim.id).status)

        repeat(3) { s.file(claim.id, "DAMAGE_PHOTO") }
        s.file(claim.id, "ACCIDENT_NOTICE")
        assertEquals(ClaimStatus.IN_ANALYSIS, s.submit(claim.id).status)

        // система ставит в очередь и назначает — клиент этого не видит
        sql("update claims set status = 'QUEUED' where id = ${claim.id}")
        sql("insert into claim_status_history(claim_id, from_status, to_status, actor_type, visible_to_client) values (${claim.id}, 'IN_ANALYSIS', 'QUEUED', 'SYSTEM', false)")

        val timeline = http.get("/api/mobile/claims/${claim.id}/timeline") { bearerAuth(s.token) }.body<List<TimelineEvent>>()
        assertEquals(
            listOf("Черновик", "Обращение отправлено", "Нужны дополнительные материалы", "Получены дополнительные материалы", "Проверяем материалы"),
            timeline.map { it.title },
        )
    }

    @Test
    fun q12_demoClaimInReviewHidesQueueAndAssignment() = apiTest { http ->
        val oleg = http.loginClient(DemoData.CLIENT_OLEG, installationId = "answers-oleg").accessToken
        val inReview = http.get("/api/mobile/claims") { bearerAuth(oleg) }.body<List<ClaimListItem>>()
            .single { it.status == ClaimStatus.IN_REVIEW }
        val statuses = http.get("/api/mobile/claims/${inReview.id}/timeline") { bearerAuth(oleg) }.body<List<TimelineEvent>>().map { it.status }
        assertEquals(listOf(ClaimStatus.DRAFT, ClaimStatus.SUBMITTED, ClaimStatus.IN_ANALYSIS, ClaimStatus.IN_REVIEW), statuses)
    }

    @Test
    fun q15_q16_rejectedFileProducesReplaceRequestWithHint() = apiTest { http ->
        val s = http.anna()
        val claim = s.accidentDraft(
            details = buildJsonObject { put("circumstances", "Не уступил дорогу") },
            participants = listOf(ParticipantInput("APPLICANT_DRIVER", "Анна Смирнова")),
        ).body<ClaimDetails>()
        repeat(2) { s.file(claim.id, "DAMAGE_PHOTO") }
        val broken = s.file(claim.id, "DAMAGE_PHOTO", status = "REJECTED", reason = "CORRUPTED")
        s.file(claim.id, "ACCIDENT_NOTICE")

        val check = http.get("/api/mobile/claims/${claim.id}/completeness") { bearerAuth(s.token) }.body<CompletenessDto>()
        assertEquals(listOf(ProblemFileDto(broken, "DAMAGE_PHOTO", "INVALID_FORMAT", "CORRUPTED")), check.problemFiles)

        val items = s.submit(claim.id).openInfoRequest!!.items
        val replace = items.single { it.fileId == broken }
        assertEquals("INVALID_FORMAT", replace.issue)
        assertTrue(replace.hint!!.contains("JPEG"))
        assertTrue(items.none { it.issue == "MISSING" && it.documentType == "DAMAGE_PHOTO" })  // недостающее фото = заменить битое
    }

    @Test
    fun q27_q34_q38_rulesEnforcedByDatabase() {
        apiTest { _ -> }  // миграции и демо-данные
        val claim = query("select id from claims where status = 'QUEUED' order by id limit 1") { it.getLong(1) }
        val operator = query("select id from users where email = '${DemoData.OPERATOR_1}'") { it.getLong(1) }
        val system = query("select id from claim_decisions where claim_id = $claim and kind = 'DAMAGE' and is_current") { it.getLong(1) }

        fun rejected(sql: String, constraint: String) {
            val e = assertFailsWith<java.sql.SQLException> { sql(sql) }
            assertTrue(e.message!!.contains(constraint), e.message)
        }
        // 27: три уровня приоритета
        rejected("update claims set priority = 4 where id = $claim", "claims_priority_check")
        // 32, 38: администратор и закрытие сотрудником — только с причиной
        rejected(
            "insert into claim_status_history(claim_id, from_status, to_status, actor_type, actor_id) values ($claim, 'QUEUED', 'CANCELLED', 'OPERATOR', $operator)",
            "claim_status_history_reason_chk",
        )
        // 38: исправление без причины — нельзя, подтверждение — можно
        rejected(
            "insert into claim_decisions(claim_id, kind, source, action, value, supersedes_id, created_by, is_current) " +
                "values ($claim, 'DAMAGE', 'OPERATOR', 'OVERRIDE', '{}', $system, $operator, false)",
            "claim_decisions_action_chk",
        )
        TestDatabase.connection().use { c ->
            c.autoCommit = false
            c.createStatement().use {
                it.execute(
                    "insert into claim_decisions(claim_id, kind, source, action, value, supersedes_id, created_by, is_current) " +
                        "values ($claim, 'DAMAGE', 'OPERATOR', 'CONFIRM', '{}', $system, $operator, false)"
                )
            }
            c.rollback()
        }
        // 34: превышение срока считается от времени входа в статус
        assertTrue(query("select is_overdue from operator_claim_list where id = $claim") { it.getBoolean(1) })
    }
}
