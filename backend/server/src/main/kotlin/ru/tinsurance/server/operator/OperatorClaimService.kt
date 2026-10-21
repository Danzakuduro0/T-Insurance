package ru.tinsurance.server.operator

import ru.tinsurance.server.auth.toUserDto
import ru.tinsurance.server.claims.ClaimQueries
import ru.tinsurance.server.common.NotFoundException
import ru.tinsurance.server.common.iso
import ru.tinsurance.server.common.tx
import ru.tinsurance.server.db.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

data class QueueFilter(
    val statuses: List<String> = emptyList(),
    val direction: String? = null,
    val minPriority: Int? = null,
    /** "me" — мои, "none" — никому не назначены, null — все */
    val assigned: String? = null,
    /** вопрос 39: конкретный сотрудник */
    val assignedTo: Long? = null,
    /** вопрос 39: тип случая */
    val eventType: String? = null,
    /** вопрос 39: дата создания (включительно) */
    val createdFrom: java.time.LocalDate? = null,
    val createdTo: java.time.LocalDate? = null,
    /** в каком поясе считать границы дней createdFrom/createdTo; null — пояс по умолчанию (app.timeZone) */
    val zone: java.time.ZoneId? = null,
    val overdueOnly: Boolean = false,
    val manualReviewOnly: Boolean = false,
    /** номер обращения, номер полиса или имя клиента */
    val search: String? = null,
    val limit: Int = 50,
    val offset: Long = 0,
)

class OperatorClaimService(private val db: Database, private val defaultZone: java.time.ZoneId) {

    suspend fun queue(operatorId: Long, filter: QueueFilter): Page<QueueItem> = db.tx {
        val v = OperatorClaimListView
        val zone = filter.zone ?: defaultZone
        val conditions = buildList<Op<Boolean>> {
            if (filter.statuses.isNotEmpty()) add(v.status inList filter.statuses)
            else add(v.status notInList listOf("COMPLETED", "CANCELLED"))  // по умолчанию — только активные
            filter.direction?.let { add(v.directionCode eq it) }
            filter.minPriority?.let { add(v.priority greaterEq it.toShort()) }
            when (filter.assigned) {
                "me" -> add(v.assignedOperatorId eq operatorId)
                "none" -> add(v.assignedOperatorId.isNull())
            }
            filter.assignedTo?.let { add(v.assignedOperatorId eq it) }
            filter.eventType?.let { add(v.eventType eq it) }
            filter.createdFrom?.let { add(v.createdAt greaterEq it.atStartOfDay(zone).toOffsetDateTime()) }
            filter.createdTo?.let { add(v.createdAt less it.plusDays(1).atStartOfDay(zone).toOffsetDateTime()) }
            if (filter.overdueOnly) add(v.isOverdue eq true)
            if (filter.manualReviewOnly) add(v.requiresManualReview eq true)
            filter.search?.trim()?.takeIf { it.isNotEmpty() }?.let { q ->
                val pattern = "%" + q.lowercase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
                add(
                    (v.claimNumber.lowerCase() like pattern) or
                        (v.policyNumber.lowerCase() like pattern) or
                        (v.clientName.lowerCase() like pattern)
                )
            }
        }
        val where = conditions.reduce { acc, op -> acc and op }

        val total = v.selectAll().where(where).count()
        val items = v.selectAll().where(where)
            // сначала срочные, внутри — у кого раньше истекает SLA
            .orderBy(
                v.priority to SortOrder.DESC_NULLS_LAST,
                v.dueAt to SortOrder.ASC_NULLS_LAST,
                v.submittedAt to SortOrder.ASC_NULLS_LAST,
            )
            .limit(filter.limit)
            .offset(filter.offset)
            .map {
                QueueItem(
                    id = it[v.id],
                    claimNumber = it[v.claimNumber],
                    status = it[v.status],
                    directionCode = it[v.directionCode],
                    directionName = it[v.directionName],
                    priority = it[v.priority]?.toInt(),
                    requiresManualReview = it[v.requiresManualReview],
                    eventType = it[v.eventType],
                    eventTypeName = it[v.eventTypeName],
                    clientName = it[v.clientName],
                    policyNumber = it[v.policyNumber],
                    assignedOperatorId = it[v.assignedOperatorId],
                    assignedOperatorName = it[v.assignedOperatorName],
                    submittedAt = it[v.submittedAt]?.iso(),
                    createdAt = it[v.createdAt].iso(),
                    statusChangedAt = it[v.statusChangedAt].iso(),
                    dueAt = it[v.dueAt]?.iso(),
                    isOverdue = it[v.isOverdue],
                    filesCount = it[v.filesCount],
                    analysesPending = it[v.analysesPending],
                    hasDuplicate = it[v.hasDuplicate],
                    updatedAt = it[v.updatedAt].iso(),
                )
            }
        Page(items, total, filter.limit, filter.offset)
    }

    suspend fun card(claimId: Long): ClaimCard = db.tx {
        val row = Claims
            .join(ClaimStatuses, JoinType.INNER, Claims.status, ClaimStatuses.code)
            .join(EventTypes, JoinType.LEFT, Claims.eventType, EventTypes.code)
            .selectAll()
            .where { (Claims.id eq claimId) and (Claims.status neq "DRAFT") }  // черновики оператору не видны
            .singleOrNull() ?: throw NotFoundException("Обращение не найдено")

        val userNames = mutableMapOf<Long, String>()
        fun userName(id: Long?): String? = id?.let {
            userNames.getOrPut(it) { Users.select(Users.fullName).where { Users.id eq it }.single()[Users.fullName] }
        }

        val client = Users.selectAll().where { Users.id eq row[Claims.clientId] }.single()
        val policy = row[Claims.policyId]?.let { pid -> Policies.selectAll().where { Policies.id eq pid }.single() }
        val assigned = row[Claims.assignedOperatorId]?.let { oid -> Users.selectAll().where { Users.id eq oid }.single() }

        ClaimCard(
            id = row[Claims.id],
            claimNumber = row[Claims.claimNumber],
            status = row[Claims.status],
            statusTitle = row[ClaimStatuses.nameForClient],
            eventType = row[Claims.eventType],
            eventTypeName = row.getOrNull(EventTypes.name),
            eventAt = row[Claims.eventAt]?.iso(),
            eventLocation = row[Claims.eventLocation],
            description = row[Claims.description],
            contactPhone = row[Claims.contactPhone],
            directionCode = row[Claims.directionCode],
            priority = row[Claims.priority]?.toInt(),
            requiresManualReview = row[Claims.requiresManualReview],
            assignedOperator = assigned?.toUserDto(),
            duplicateOfClaimId = row[Claims.duplicateOfClaimId],
            statusChangedAt = row[Claims.statusChangedAt].iso(),
            dueAt = row[ClaimStatuses.slaHours]?.let { h -> row[Claims.statusChangedAt].plusHours(h.toLong()).iso() },
            isOverdue = row[ClaimStatuses.slaHours]?.let { h ->
                row[Claims.statusChangedAt].plusHours(h.toLong()).isBefore(java.time.OffsetDateTime.now())
            } ?: false,
            details = row[Claims.eventDetails],
            submittedAt = row[Claims.submittedAt]?.iso(),
            createdAt = row[Claims.createdAt].iso(),
            updatedAt = row[Claims.updatedAt].iso(),
            version = row[Claims.version],
            client = ClientInfo(client[Users.id], client[Users.fullName], client[Users.phone], client[Users.email]),
            policy = policy?.let {
                PolicyInfo(
                    id = it[Policies.id],
                    policyNumber = it[Policies.policyNumber],
                    product = it[Policies.product],
                    insuredObject = it[Policies.insuredObject],
                    validFrom = it[Policies.validFrom].toString(),
                    validTo = it[Policies.validTo].toString(),
                )
            },
            participants = ClaimQueries.participants(claimId),
            files = files(claimId),
            decisions = ClaimDecisions.selectAll()
                .where { ClaimDecisions.claimId eq claimId }
                .orderBy(ClaimDecisions.id)
                .map {
                    DecisionDto(
                        id = it[ClaimDecisions.id],
                        kind = it[ClaimDecisions.kind],
                        source = it[ClaimDecisions.decisionSource],
                        action = it[ClaimDecisions.action],
                        value = it[ClaimDecisions.value],
                        confidence = it[ClaimDecisions.confidence],
                        reason = it[ClaimDecisions.reason],
                        ruleVersion = it[ClaimDecisions.ruleVersion],
                        supersedesId = it[ClaimDecisions.supersedesId],
                        isCurrent = it[ClaimDecisions.isCurrent],
                        createdBy = userName(it[ClaimDecisions.createdBy]),
                        createdAt = it[ClaimDecisions.createdAt].iso(),
                    )
                },
            history = ClaimStatusHistory.selectAll()
                .where { ClaimStatusHistory.claimId eq claimId }
                .orderBy(ClaimStatusHistory.createdAt to SortOrder.ASC, ClaimStatusHistory.id to SortOrder.ASC)
                .map {
                    StatusHistoryDto(
                        fromStatus = it[ClaimStatusHistory.fromStatus],
                        toStatus = it[ClaimStatusHistory.toStatus],
                        actorType = it[ClaimStatusHistory.actorType],
                        actorName = userName(it[ClaimStatusHistory.actorId]),
                        reason = it[ClaimStatusHistory.reason],
                        clientTitle = it[ClaimStatusHistory.clientTitle],
                        clientComment = it[ClaimStatusHistory.clientComment],
                        visibleToClient = it[ClaimStatusHistory.visibleToClient],
                        at = it[ClaimStatusHistory.createdAt].iso(),
                    )
                },
            comments = ClaimComments.selectAll()
                .where { ClaimComments.claimId eq claimId }
                .orderBy(ClaimComments.id)
                .map {
                    CommentDto(
                        id = it[ClaimComments.id],
                        authorName = userName(it[ClaimComments.authorId]).orEmpty(),
                        visibility = it[ClaimComments.visibility],
                        body = it[ClaimComments.body],
                        createdAt = it[ClaimComments.createdAt].iso(),
                    )
                },
            infoRequests = ClaimQueries.infoRequests(claimId, onlyOpen = false),
        )
    }

    /** Файлы с последним результатом анализа по каждому. Вызывается внутри транзакции. */
    private fun files(claimId: Long): List<OperatorFileDto> {
        val files = ClaimQueries.files(claimId)
        if (files.isEmpty()) return emptyList()
        val rows = ClaimFiles.selectAll().where { ClaimFiles.claimId eq claimId }.associateBy { it[ClaimFiles.id] }
        val similar = similarElsewhere(claimId, rows.values)
        val latest = ImageAnalyses.selectAll()
            .where { ImageAnalyses.fileId inList files.map { it.id } }
            .orderBy(ImageAnalyses.id to SortOrder.ASC)
            .associateBy { it[ImageAnalyses.fileId] }   // последний перезаписывает предыдущие
        return files.map { file ->
            OperatorFileDto(
                file = file,
                analysisStatus = rows.getValue(file.id)[ClaimFiles.analysisStatus],
                foundInOtherClaim = similar[file.id],
                analysis = latest[file.id]?.let {
                    ImageAnalysisDto(
                        modelVersion = it[ImageAnalyses.modelVersion],
                        qualityOk = it[ImageAnalyses.qualityOk],
                        qualityScore = it[ImageAnalyses.qualityScore],
                        qualityIssues = it[ImageAnalyses.qualityIssues],
                        damageDetected = it[ImageAnalyses.damageDetected],
                        damageType = it[ImageAnalyses.damageType],
                        damageConfidence = it[ImageAnalyses.damageConfidence],
                        carPart = it[ImageAnalyses.carPart],
                        damageArea = it[ImageAnalyses.damageArea],
                        createdAt = it[ImageAnalyses.createdAt].iso(),
                    )
                },
            )
        }
    }

    /** Файлы обращения, которые совпали с файлами других обращений при загрузке (точно или по перцептивному хэшу). */
    private fun similarElsewhere(claimId: Long, files: Collection<ResultRow>): Map<Long, SimilarFileDto> {
        val links = files.filter { it[ClaimFiles.status] != "DUPLICATE" }
            .mapNotNull { row -> row[ClaimFiles.duplicateOfFileId]?.let { row to it } }
        if (links.isEmpty()) return emptyMap()
        val targets = ClaimFiles.join(Claims, JoinType.INNER, ClaimFiles.claimId, Claims.id)
            .select(ClaimFiles.id, ClaimFiles.sha256, Claims.id, Claims.claimNumber)
            .where { (ClaimFiles.id inList links.map { it.second }) and (Claims.id neq claimId) }
            .associateBy { it[ClaimFiles.id] }
        return links.mapNotNull { (row, targetId) ->
            val target = targets[targetId] ?: return@mapNotNull null
            row[ClaimFiles.id] to SimilarFileDto(
                claimId = target[Claims.id],
                claimNumber = target[Claims.claimNumber],
                fileId = targetId,
                match = if (target[ClaimFiles.sha256] == row[ClaimFiles.sha256]) "EXACT" else "SIMILAR",
            )
        }.toMap()
    }
}
