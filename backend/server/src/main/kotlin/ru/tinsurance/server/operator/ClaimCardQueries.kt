package ru.tinsurance.server.operator

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import ru.tinsurance.api.ClaimStatus
import ru.tinsurance.api.FileStatus
import ru.tinsurance.api.MatchType
import ru.tinsurance.api.operator.*
import ru.tinsurance.server.analysis.ImageAnalysisRepository
import ru.tinsurance.server.analysis.toImageAnalysisDto
import ru.tinsurance.server.claims.InfoRequestRepository
import ru.tinsurance.server.claims.ParticipantRepository
import ru.tinsurance.server.claims.PolicyRepository
import ru.tinsurance.server.common.NotFoundException
import ru.tinsurance.server.common.iso
import ru.tinsurance.server.db.*
import ru.tinsurance.server.files.FileRepository
import ru.tinsurance.server.users.UserRepository
import java.time.OffsetDateTime

/** Карточка обращения для оператора. Вызывается внутри транзакции. */
object ClaimCardQueries {

    fun card(claimId: Long): ClaimCard {
        val row = Claims
            .join(ClaimStatuses, JoinType.INNER, Claims.status, ClaimStatuses.code)
            .join(EventTypes, JoinType.LEFT, Claims.eventType, EventTypes.code)
            .selectAll()
            .where { (Claims.id eq claimId) and (Claims.status neq ClaimStatus.DRAFT) }
            .singleOrNull() ?: throw NotFoundException("Обращение не найдено")

        val decisions = ClaimDecisions.selectAll().where { ClaimDecisions.claimId eq claimId }.orderBy(ClaimDecisions.id).toList()
        val history = ClaimStatusHistory.selectAll().where { ClaimStatusHistory.claimId eq claimId }
            .orderBy(ClaimStatusHistory.createdAt to SortOrder.ASC, ClaimStatusHistory.id to SortOrder.ASC).toList()
        val comments = ClaimComments.selectAll().where { ClaimComments.claimId eq claimId }.orderBy(ClaimComments.id).toList()
        val names = UserRepository.namesByIds(
            decisions.mapNotNull { it[ClaimDecisions.createdBy] } +
                history.mapNotNull { it[ClaimStatusHistory.actorId] } +
                comments.map { it[ClaimComments.authorId] }
        )

        val statusChangedAt = row[Claims.statusChangedAt]
        val dueAt = row[ClaimStatuses.slaHours]?.let { statusChangedAt.plusHours(it.toLong()) }
        return ClaimCard(
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
            assignedOperator = row[Claims.assignedOperatorId]?.let(UserRepository::get),
            duplicateOfClaimId = row[Claims.duplicateOfClaimId],
            statusChangedAt = statusChangedAt.iso(),
            dueAt = dueAt?.iso(),
            isOverdue = dueAt?.isBefore(OffsetDateTime.now()) ?: false,
            details = row[Claims.eventDetails],
            submittedAt = row[Claims.submittedAt]?.iso(),
            createdAt = row[Claims.createdAt].iso(),
            updatedAt = row[Claims.updatedAt].iso(),
            version = row[Claims.version],
            client = UserRepository.get(row[Claims.clientId]),
            policy = row[Claims.policyId]?.let(PolicyRepository::find),
            participants = ParticipantRepository.list(claimId),
            files = files(claimId),
            decisions = decisions.map { it.toDecisionDto(names) },
            history = history.map { it.toStatusHistoryDto(names) },
            comments = comments.map { it.toCommentDto(names) },
            infoRequests = InfoRequestRepository.list(claimId, onlyOpen = false),
        )
    }

    /** Файлы с последним результатом анализа и совпадениями в других обращениях. */
    private fun files(claimId: Long): List<OperatorFileDto> {
        val files = FileRepository.listDtos(claimId)
        if (files.isEmpty()) return emptyList()
        val rows = FileRepository.rows(claimId)
        val similar = similarElsewhere(claimId, rows.values)
        val analyses = ImageAnalysisRepository.latestByFile(files.map { it.id })
        return files.map { file ->
            OperatorFileDto(
                file = file,
                analysisStatus = rows.getValue(file.id)[ClaimFiles.analysisStatus],
                analysis = analyses[file.id]?.toImageAnalysisDto(),
                foundInOtherClaim = similar[file.id],
            )
        }
    }

    private fun similarElsewhere(claimId: Long, files: Collection<ResultRow>): Map<Long, SimilarFileDto> {
        val links = files.filter { it[ClaimFiles.status] != FileStatus.DUPLICATE }
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
                match = if (target[ClaimFiles.sha256] == row[ClaimFiles.sha256]) MatchType.EXACT else MatchType.SIMILAR,
            )
        }.toMap()
    }

    private fun ResultRow.toDecisionDto(names: Map<Long, String>) = DecisionDto(
        id = this[ClaimDecisions.id],
        kind = this[ClaimDecisions.kind],
        source = this[ClaimDecisions.decisionSource],
        action = this[ClaimDecisions.action],
        value = this[ClaimDecisions.value],
        confidence = this[ClaimDecisions.confidence],
        reason = this[ClaimDecisions.reason],
        ruleVersion = this[ClaimDecisions.ruleVersion],
        supersedesId = this[ClaimDecisions.supersedesId],
        isCurrent = this[ClaimDecisions.isCurrent],
        createdBy = this[ClaimDecisions.createdBy]?.let(names::get),
        createdAt = this[ClaimDecisions.createdAt].iso(),
    )

    private fun ResultRow.toStatusHistoryDto(names: Map<Long, String>) = StatusHistoryDto(
        fromStatus = this[ClaimStatusHistory.fromStatus],
        toStatus = this[ClaimStatusHistory.toStatus],
        actorType = this[ClaimStatusHistory.actorType],
        actorName = this[ClaimStatusHistory.actorId]?.let(names::get),
        reason = this[ClaimStatusHistory.reason],
        clientTitle = this[ClaimStatusHistory.clientTitle],
        clientComment = this[ClaimStatusHistory.clientComment],
        visibleToClient = this[ClaimStatusHistory.visibleToClient],
        at = this[ClaimStatusHistory.createdAt].iso(),
    )

    private fun ResultRow.toCommentDto(names: Map<Long, String>) = CommentDto(
        id = this[ClaimComments.id],
        authorName = names[this[ClaimComments.authorId]].orEmpty(),
        visibility = this[ClaimComments.visibility],
        body = this[ClaimComments.body],
        createdAt = this[ClaimComments.createdAt].iso(),
    )
}
