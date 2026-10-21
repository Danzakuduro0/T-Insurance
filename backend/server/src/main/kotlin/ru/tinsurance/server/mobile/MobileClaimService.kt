package ru.tinsurance.server.mobile

import ru.tinsurance.api.*
import ru.tinsurance.server.claims.*
import ru.tinsurance.server.common.*
import ru.tinsurance.server.db.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.uuid.Uuid

/** API мобильного приложения: клиент видит и меняет только свои обращения. */
class MobileClaimService(private val db: Database, private val config: ClaimsConfig = ClaimsConfig.DEFAULT) {

    suspend fun activePolicies(clientId: Long): List<PolicyDto> = db.tx {
        Policies.selectAll()
            .where { (Policies.clientId eq clientId) and (Policies.validTo greaterEq LocalDate.now()) }
            .orderBy(Policies.validTo, SortOrder.DESC)
            .map {
                PolicyDto(
                    id = it[Policies.id],
                    policyNumber = it[Policies.policyNumber],
                    product = it[Policies.product],
                    insuredObject = it[Policies.insuredObject],
                    validFrom = it[Policies.validFrom].toString(),
                    validTo = it[Policies.validTo].toString(),
                )
            }
    }

    /** Типы событий и их правила: по ним приложение строит форму, а не зашивает поля в экран (вопрос 7). */
    suspend fun eventTypes(): List<EventTypeDto> = db.tx {
        EventTypes.selectAll().orderBy(EventTypes.code).map { type ->
            val code = type[EventTypes.code]
            EventTypeDto(
                code = code,
                name = type[EventTypes.name],
                fields = ClaimRules.fields(code).map { FormFieldDto(it.code, it.title, it.kind, it.valueType, it.required) },
                documents = ClaimRules.documents(code).map {
                    FormDocumentDto(it.documentType, it.name, it.minCount, it.allowedMimeTypes, it.maxSizeBytes)
                },
            )
        }
    }

    /** Возвращает обращение и признак «создано сейчас» (false — повтор того же clientRequestId). */
    suspend fun createDraft(clientId: Long, request: CreateClaimRequest): Pair<ClaimDetails, Boolean> {
        val requestId = request.clientRequestId?.let {
            runCatching { Uuid.parse(it) }.getOrElse { throw ValidationException("clientRequestId должен быть UUID") }
        }
        val eventAt = request.eventAt?.let { parseDateTime(it, "eventAt") }
        validateSizes(request.eventLocation, request.description, request.contactPhone, request.participants, request.details)
        request.participants?.forEach(::validateParticipant)

        val (claimId, created) = db.tx {
            val existing = requestId?.let { rid ->
                Claims.selectAll().where { Claims.clientRequestId eq rid }.singleOrNull()
            }
            if (existing != null) {
                if (existing[Claims.clientId] != clientId) throw ConflictException("clientRequestId уже использован")
                return@tx existing[Claims.id] to false
            }

            request.policyId?.let { requireOwnPolicy(clientId, it) }
            request.eventType?.let(::requireEventType)
            val details = request.details ?: JsonObject(emptyMap())
            validateDetails(request.eventType, details)
            // вопрос 6: контакт для уведомлений; по умолчанию — телефон клиента
            val contact = request.contactPhone?.let(::normalizePhone)
                ?: Users.select(Users.phone).where { Users.id eq clientId }.single()[Users.phone]

            val now = OffsetDateTime.now()
            val id = Claims.insert {
                it[Claims.clientId] = clientId
                it[policyId] = request.policyId
                it[eventType] = request.eventType
                it[Claims.eventAt] = eventAt
                it[eventLocation] = request.eventLocation?.trim()
                it[description] = request.description?.trim()
                it[contactPhone] = contact
                it[eventDetails] = details
                it[status] = ClaimStatus.DRAFT.name
                it[statusChangedAt] = now
                it[requiresManualReview] = false
                it[clientRequestId] = requestId
                it[version] = 0
                it[createdAt] = now
                it[updatedAt] = now
            }[Claims.id]
            request.participants?.let { replaceParticipants(id, it) }
            ClaimStatusService.recordCreated(id, Actor(ActorType.CLIENT, clientId), now)
            id to true
        }
        return details(clientId, claimId) to created
    }

    suspend fun list(clientId: Long): List<ClaimListItem> = db.tx {
        ClientClaimListView.selectAll()
            .where { ClientClaimListView.clientId eq clientId }
            .orderBy(ClientClaimListView.updatedAt, SortOrder.DESC)
            .map {
                ClaimListItem(
                    id = it[ClientClaimListView.id],
                    claimNumber = it[ClientClaimListView.claimNumber],
                    status = ClaimStatus.valueOf(it[ClientClaimListView.status]),
                    statusTitle = it[ClientClaimListView.statusTitle],
                    isFinal = it[ClientClaimListView.isFinal],
                    eventTypeName = it[ClientClaimListView.eventTypeName],
                    eventAt = it[ClientClaimListView.eventAt]?.iso(),
                    createdAt = it[ClientClaimListView.createdAt].iso(),
                    updatedAt = it[ClientClaimListView.updatedAt].iso(),
                    actionRequired = it[ClientClaimListView.actionRequired],
                    actionMessage = it[ClientClaimListView.actionMessage],
                )
            }
    }

    suspend fun details(clientId: Long, claimId: Long): ClaimDetails = db.tx {
        val row = Claims
            .join(ClaimStatuses, JoinType.INNER, Claims.status, ClaimStatuses.code)
            .join(Policies, JoinType.LEFT, Claims.policyId, Policies.id)
            .join(EventTypes, JoinType.LEFT, Claims.eventType, EventTypes.code)
            .selectAll()
            .where { (Claims.id eq claimId) and (Claims.clientId eq clientId) }  // чужое обращение = 404
            .singleOrNull() ?: throw NotFoundException("Обращение не найдено")

        ClaimDetails(
            id = row[Claims.id],
            claimNumber = row[Claims.claimNumber],
            status = ClaimStatus.valueOf(row[Claims.status]),
            statusTitle = row[ClaimStatuses.nameForClient],
            isFinal = row[ClaimStatuses.isFinal],
            policyId = row[Claims.policyId],
            policyNumber = row.getOrNull(Policies.policyNumber),
            eventType = row[Claims.eventType],
            eventTypeName = row.getOrNull(EventTypes.name),
            eventAt = row[Claims.eventAt]?.iso(),
            eventLocation = row[Claims.eventLocation],
            description = row[Claims.description],
            contactPhone = row[Claims.contactPhone],
            participants = ClaimQueries.participants(claimId),
            details = row[Claims.eventDetails],
            createdAt = row[Claims.createdAt].iso(),
            updatedAt = row[Claims.updatedAt].iso(),
            submittedAt = row[Claims.submittedAt]?.iso(),
            openInfoRequest = ClaimQueries.infoRequests(claimId, onlyOpen = true).firstOrNull(),
            files = ClaimQueries.files(claimId),
        )
    }

    /** Временная шкала: только события, видимые клиенту (вопрос 12). */
    suspend fun timeline(clientId: Long, claimId: Long): List<TimelineEvent> = db.tx {
        ownedClaim(clientId, claimId)
        ClaimStatusHistory.join(ClaimStatuses, JoinType.INNER, ClaimStatusHistory.toStatus, ClaimStatuses.code)
            .selectAll()
            .where { (ClaimStatusHistory.claimId eq claimId) and (ClaimStatusHistory.visibleToClient eq true) }
            .orderBy(ClaimStatusHistory.createdAt to SortOrder.ASC, ClaimStatusHistory.id to SortOrder.ASC)
            .map {
                TimelineEvent(
                    status = ClaimStatus.valueOf(it[ClaimStatusHistory.toStatus]),
                    title = it[ClaimStatusHistory.clientTitle] ?: it[ClaimStatuses.nameForClient],
                    comment = it[ClaimStatusHistory.clientComment],  // внутренняя причина (reason) клиенту не отдаётся
                    at = it[ClaimStatusHistory.createdAt].iso(),
                )
            }
    }

    /** Чего не хватает для отправки — без побочных эффектов (чек-лист на экране). */
    suspend fun completeness(clientId: Long, claimId: Long): CompletenessDto = db.tx {
        CompletenessChecker.check(ownedClaim(clientId, claimId)).toDto()
    }

    /**
     * Изменение обращения. В черновике — любые поля. После запроса уточнений — только запрошенные,
     * и каждое изменение пишется в журнал со старым и новым значением (вопросы 10, 42).
     */
    suspend fun update(clientId: Long, claimId: Long, request: UpdateClaimRequest): ClaimDetails {
        val eventAt = request.eventAt?.let { parseDateTime(it, "eventAt") }
        validateSizes(request.eventLocation, request.description, request.contactPhone, request.participants, request.details)
        request.participants?.forEach(::validateParticipant)
        db.tx {
            val claim = ownedClaim(clientId, claimId, forUpdate = true)
            val status = ClaimStatus.valueOf(claim[Claims.status])
            if (status != ClaimStatus.DRAFT && status != ClaimStatus.NEEDS_INFO) {
                throw ConflictException("После отправки обращение можно только дополнить по запросу")
            }
            request.policyId?.let { requireOwnPolicy(clientId, it) }
            request.eventType?.let(::requireEventType)

            val newValues = buildJsonObject {
                request.policyId?.let { put("policyId", it) }
                request.eventType?.let { put("eventType", it) }
                eventAt?.let { put("eventAt", it.iso()) }
                request.eventLocation?.let { put("eventLocation", it.trim()) }
                request.description?.let { put("description", it.trim()) }
                request.contactPhone?.let { put("contactPhone", normalizePhone(it)) }
                request.participants?.let { list ->
                    putJsonArray(CompletenessChecker.PARTICIPANTS) { list.forEach { p -> add(participantJson(p)) } }
                }
                request.details?.forEach { (key, value) -> put(key, value) }
            }
            if (newValues.isEmpty()) return@tx

            if (status == ClaimStatus.NEEDS_INFO) {
                val requested = InfoRequestItems.join(InfoRequests, JoinType.INNER, InfoRequestItems.requestId, InfoRequests.id)
                    .select(InfoRequestItems.fieldName)
                    .where { (InfoRequests.claimId eq claimId) and (InfoRequests.status eq "OPEN") }
                    .mapNotNull { it[InfoRequestItems.fieldName] }.toSet()
                val notRequested = newValues.keys - requested
                if (notRequested.isNotEmpty()) {
                    throw ConflictException(
                        "После отправки можно изменить только запрошенные поля" +
                            (if (requested.isEmpty()) "" else ": ${requested.joinToString()}") +
                            ". Не запрошены: ${notRequested.joinToString()}"
                    )
                }
            }

            val effectiveType = request.eventType ?: claim[Claims.eventType]
            val mergedDetails = request.details?.let { JsonObject(claim[Claims.eventDetails] + it) }
            mergedDetails?.let { validateDetails(effectiveType, it) }

            val oldValues = buildJsonObject {
                newValues.keys.forEach { key ->
                    when (key) {
                        "policyId" -> put(key, claim[Claims.policyId])
                        "eventType" -> put(key, claim[Claims.eventType])
                        "eventAt" -> put(key, claim[Claims.eventAt]?.iso())
                        "eventLocation" -> put(key, claim[Claims.eventLocation])
                        "description" -> put(key, claim[Claims.description])
                        "contactPhone" -> put(key, claim[Claims.contactPhone])
                        CompletenessChecker.PARTICIPANTS -> put(key, JsonArray(ClaimQueries.participants(claimId).map { p ->
                            buildJsonObject { put("role", p.role); put("fullName", p.fullName) }
                        }))
                        else -> put(key, claim[Claims.eventDetails][key] ?: JsonNull)
                    }
                }
            }

            Claims.update({ Claims.id eq claimId }) {
                request.policyId?.let { v -> it[policyId] = v }
                request.eventType?.let { v -> it[eventType] = v }
                eventAt?.let { v -> it[Claims.eventAt] = v }
                request.eventLocation?.let { v -> it[eventLocation] = v.trim() }
                request.description?.let { v -> it[description] = v.trim() }
                request.contactPhone?.let { v -> it[contactPhone] = normalizePhone(v) }
                mergedDetails?.let { v -> it[eventDetails] = v }
                it[version] = Claims.version + 1
            }
            request.participants?.let { replaceParticipants(claimId, it) }

            AuditLog.insert {
                it[AuditLog.claimId] = claimId
                it[entityType] = "CLAIM"
                it[entityId] = claimId
                it[action] = "UPDATE"
                it[oldValue] = oldValues
                it[newValue] = newValues
                it[actorType] = ActorType.CLIENT.name
                it[actorId] = clientId
                it[comment] = if (status == ClaimStatus.NEEDS_INFO) "Уточнение по запросу" else null
                it[createdAt] = OffsetDateTime.now()
            }
        }
        return details(clientId, claimId)
    }

    /**
     * Отправка. Неполный комплект выявляется здесь, до основной обработки:
     * SUBMITTED → NEEDS_INFO с перечнем недостающего, полный — SUBMITTED → IN_ANALYSIS.
     */
    suspend fun submit(clientId: Long, claimId: Long): ClaimDetails {
        db.tx {
            val claim = ownedClaim(clientId, claimId, forUpdate = true)
            val status = ClaimStatus.valueOf(claim[Claims.status])
            if (status != ClaimStatus.DRAFT && status != ClaimStatus.NEEDS_INFO) {
                throw ConflictException("Обращение уже отправлено")
            }

            val result = CompletenessChecker.check(claim)
            if (result.missingCoreFields.isNotEmpty()) {
                // вопрос 13: без минимальных данных обращение не регистрируется — остаётся черновиком
                throw ApiException(
                    HttpStatusCode.BadRequest, "INCOMPLETE_DRAFT",
                    "Перед отправкой заполните: ${result.missingCoreFields.joinToString { it.title }}",
                )
            }
            validateEvent(claim)

            val now = OffsetDateTime.now()
            ClaimStatusService.transition(
                claimId, status, ClaimStatus.SUBMITTED, Actor(ActorType.CLIENT, clientId),
                // вопрос 12: повторная отправка для клиента — «получены материалы», а не новое обращение
                clientTitle = if (status == ClaimStatus.NEEDS_INFO) "Получены дополнительные материалы" else null,
                at = now,
            )
            recordCompletenessDecision(claimId, result, now)

            if (result.complete) {
                closeOpenInfoRequest(claimId, "FULFILLED", now)
                ClaimStatusService.transition(
                    claimId, ClaimStatus.SUBMITTED, ClaimStatus.IN_ANALYSIS, Actor.SYSTEM,
                    reason = "Комплект материалов полный", at = now,
                )
            } else {
                closeOpenInfoRequest(claimId, "CANCELLED", now)  // заменяется новым запросом с актуальным перечнем
                createInfoRequest(claimId, result, now)
                val missing = "Не хватает: ${result.summary()}"
                ClaimStatusService.transition(
                    claimId, ClaimStatus.SUBMITTED, ClaimStatus.NEEDS_INFO, Actor.SYSTEM,
                    reason = missing, clientComment = missing, at = now,
                )
            }
        }
        return details(clientId, claimId)
    }

    private fun ownedClaim(clientId: Long, claimId: Long, forUpdate: Boolean = false): ResultRow {
        val query = Claims.selectAll().where { (Claims.id eq claimId) and (Claims.clientId eq clientId) }
        return (if (forUpdate) query.forUpdate() else query).singleOrNull()
            ?: throw NotFoundException("Обращение не найдено")  // чужое обращение = 404
    }

    private fun requireOwnPolicy(clientId: Long, policyId: Long) {
        val owned = Policies.selectAll().where { (Policies.id eq policyId) and (Policies.clientId eq clientId) }.any()
        if (!owned) throw ValidationException("Полис не найден")
    }

    private fun requireEventType(type: String) {
        if (EventTypes.selectAll().where { EventTypes.code eq type }.empty()) {
            throw ValidationException("Неизвестный тип события: $type")
        }
    }

    /** Ограничения размеров: защита памяти и БД от огромных значений. */
    private fun validateSizes(
        eventLocation: String?, description: String?, contactPhone: String?,
        participants: List<ParticipantInput>?, details: JsonObject?,
    ) {
        checkLength("eventLocation", eventLocation, 500)
        checkLength("description", description, 4000)
        checkLength("contactPhone", contactPhone, 32)
        participants?.let { list ->
            if (list.size > MAX_PARTICIPANTS) throw ValidationException("Участников не больше $MAX_PARTICIPANTS")
            list.forEach { p ->
                checkLength("participants.fullName", p.fullName, 200)
                checkLength("participants.phone", p.phone, 32)
                checkLength("participants.insurer", p.insurer, 200)
                checkLength("participants.note", p.note, 1000)
                checkLength("participants.vehicle", p.vehicle?.toString(), 2000)
            }
        }
        details?.forEach { (key, value) ->
            if (value is JsonPrimitive && value.isString) checkLength(key, value.content, 2000)
        }
    }

    private fun checkLength(field: String, value: String?, max: Int) {
        if (value != null && value.length > max) throw ValidationException("Поле «$field»: не длиннее $max символов")
    }

    private fun validateParticipant(p: ParticipantInput) {
        if (p.role !in PARTICIPANT_ROLES) throw ValidationException("Роль участника: одна из ${PARTICIPANT_ROLES.joinToString()}")
        if (p.fullName.isBlank()) throw ValidationException("Укажите имя участника")
    }

    /** Дополнительные сведения: только ключи, предусмотренные для типа события, и значения нужного вида. */
    private fun validateDetails(eventType: String?, details: JsonObject) {
        if (details.isEmpty()) return
        val type = eventType ?: throw ValidationException("Дополнительные сведения зависят от типа события — сначала укажите eventType")
        val rules = ClaimRules.fields(type).filter { it.kind == "DETAIL" }.associateBy { it.code }
        details.forEach { (key, value) ->
            val rule = rules[key] ?: throw ValidationException("Поле «$key» не предусмотрено для типа события $type")
            val ok = value is JsonNull || when (rule.valueType) {
                "BOOLEAN" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
                "NUMBER" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
                "STRING" -> value is JsonPrimitive && value.isString
                else -> true
            }
            if (!ok) throw ValidationException("Поле «${rule.title}»: ожидается значение типа ${rule.valueType}")
        }
    }

    private fun replaceParticipants(claimId: Long, participants: List<ParticipantInput>) {
        ClaimParticipants.deleteWhere { ClaimParticipants.claimId eq claimId }
        participants.forEach { p ->
            ClaimParticipants.insert {
                it[ClaimParticipants.claimId] = claimId
                it[role] = p.role
                it[fullName] = p.fullName.trim()
                it[phone] = p.phone?.let(::normalizePhone)
                it[vehicle] = p.vehicle
                it[insurer] = p.insurer
                it[note] = p.note
            }
        }
    }

    private fun participantJson(p: ParticipantInput) = buildJsonObject {
        put("role", p.role)
        put("fullName", p.fullName.trim())
    }

    private fun validateEvent(claim: ResultRow) {
        val eventAt = claim[Claims.eventAt]!!
        if (eventAt.isAfter(OffsetDateTime.now().plus(EVENT_CLOCK_SKEW))) {
            throw ValidationException("Дата события не может быть в будущем")
        }
        val policy = Policies.selectAll().where { Policies.id eq claim[Claims.policyId]!! }.single()
        val eventDate = eventAt.toLocalDate()
        if (eventDate < policy[Policies.validFrom] || eventDate > policy[Policies.validTo]) {
            throw ValidationException(
                "Дата события вне периода действия полиса ${policy[Policies.policyNumber]} " +
                    "(${policy[Policies.validFrom]} — ${policy[Policies.validTo]})"
            )
        }
    }

    /** Решение системы о комплектности; предыдущее не перезаписывается, а заменяется (supersedes_id). */
    private fun recordCompletenessDecision(claimId: Long, result: CompletenessResult, at: OffsetDateTime) {
        val previous = ClaimDecisions.select(ClaimDecisions.id)
            .where { (ClaimDecisions.claimId eq claimId) and (ClaimDecisions.kind eq "COMPLETENESS") and (ClaimDecisions.isCurrent eq true) }
            .singleOrNull()?.get(ClaimDecisions.id)
        previous?.let { id -> ClaimDecisions.update({ ClaimDecisions.id eq id }) { it[isCurrent] = false } }

        ClaimDecisions.insert {
            it[ClaimDecisions.claimId] = claimId
            it[kind] = "COMPLETENESS"
            it[decisionSource] = "SYSTEM"
            it[action] = "SET"
            it[value] = buildJsonObject {
                put("complete", result.complete)
                putJsonArray("missingFields") { result.missingFields.forEach { f -> add(f.code) } }
                putJsonArray("missingDocuments") {
                    result.missingDocuments.forEach { d ->
                        addJsonObject {
                            put("documentType", d.documentType)
                            put("required", d.required)
                            put("uploaded", d.uploaded)
                        }
                    }
                }
                putJsonArray("problemFiles") {
                    result.problemFiles.forEach { f -> addJsonObject { put("fileId", f.fileId); put("issue", f.issue) } }
                }
            }
            // вопрос 23: для решения на правилах вместо вероятности показываем сработавшие проверки
            it[reason] = if (result.complete) "Комплект полный" else "Не хватает: ${result.summary()}"
            it[ruleVersion] = COMPLETENESS_RULES_VERSION
            it[supersedesId] = previous
            it[isCurrent] = true
            it[createdAt] = at
        }
    }

    private fun closeOpenInfoRequest(claimId: Long, newStatus: String, at: OffsetDateTime) {
        val open = InfoRequests.select(InfoRequests.id)
            .where { (InfoRequests.claimId eq claimId) and (InfoRequests.status eq "OPEN") }
            .singleOrNull()?.get(InfoRequests.id) ?: return
        InfoRequests.update({ InfoRequests.id eq open }) {
            it[status] = newStatus
            it[closedAt] = at
        }
        if (newStatus == "FULFILLED") {
            InfoRequestItems.update({ InfoRequestItems.requestId eq open }) { it[isFulfilled] = true }
        }
    }

    /** Запрос различает «нет файла», «файл отклонён», «плохое качество» и подсказывает, что сделать (вопросы 15, 16, 22). */
    private fun createInfoRequest(claimId: Long, result: CompletenessResult, at: OffsetDateTime) {
        val requestId = InfoRequests.insert {
            it[InfoRequests.claimId] = claimId
            it[requestSource] = "SYSTEM"
            it[message] = "Для рассмотрения обращения не хватает материалов. Дополните их и отправьте обращение повторно."
            it[status] = "OPEN"
            it[dueAt] = at.plusDays(config.infoRequestDueDays)
            it[createdAt] = at
        }[InfoRequests.id]

        result.missingDocuments.forEach { doc ->
            val problems = result.problemFiles.filter { it.documentType == doc.documentType }
            problems.forEach { file ->
                InfoRequestItems.insert {
                    it[InfoRequestItems.requestId] = requestId
                    it[documentType] = doc.documentType
                    it[minCount] = 1
                    it[issue] = file.issue
                    it[hint] = file.hint
                    it[fileId] = file.fileId
                    it[comment] = "Файл не принят — замените его"
                    it[isFulfilled] = false
                }
            }
            val stillMissing = doc.required - doc.uploaded - problems.size
            if (stillMissing > 0) {
                InfoRequestItems.insert {
                    it[InfoRequestItems.requestId] = requestId
                    it[documentType] = doc.documentType
                    it[minCount] = stillMissing.toShort()
                    it[issue] = "MISSING"
                    it[comment] = "Загружено ${doc.uploaded} из ${doc.required}"
                    it[isFulfilled] = false
                }
            }
        }
        result.missingFields.forEach { field ->
            InfoRequestItems.insert {
                it[InfoRequestItems.requestId] = requestId
                it[fieldName] = field.code
                it[minCount] = 1
                it[issue] = "MISSING"
                it[comment] = "Заполните поле «${field.title}»"
                it[isFulfilled] = false
            }
        }
    }

    private companion object {
        const val COMPLETENESS_RULES_VERSION = "completeness-v2"
        const val MAX_PARTICIPANTS = 20
        val PARTICIPANT_ROLES = setOf("APPLICANT_DRIVER", "OTHER_DRIVER", "VICTIM", "WITNESS")
        /** допуск на расхождение часов телефона и сервера */
        val EVENT_CLOCK_SKEW: Duration = Duration.ofMinutes(5)
    }
}
