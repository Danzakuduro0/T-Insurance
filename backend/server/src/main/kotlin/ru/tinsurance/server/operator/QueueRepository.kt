package ru.tinsurance.server.operator

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import ru.tinsurance.api.ClaimStatus
import ru.tinsurance.api.Page
import ru.tinsurance.api.operator.QueueItem
import ru.tinsurance.server.common.containsPattern
import ru.tinsurance.server.common.iso
import ru.tinsurance.server.db.OperatorClaimListView
import java.time.ZoneId

/** Очередь обращений для оператора. Вызывается внутри транзакции. */
object QueueRepository {
    private val v = OperatorClaimListView
    private val CLOSED = listOf(ClaimStatus.COMPLETED, ClaimStatus.CANCELLED)

    fun page(operatorId: Long, filter: QueueFilter, defaultZone: ZoneId): Page<QueueItem> {
        val where = conditions(operatorId, filter, filter.zone ?: defaultZone).reduce { acc, op -> acc and op }
        val total = v.selectAll().where(where).count()
        val items = v.selectAll().where(where)
            // сначала срочные, внутри — у кого раньше истекает срок
            .orderBy(
                v.priority to SortOrder.DESC_NULLS_LAST,
                v.dueAt to SortOrder.ASC_NULLS_LAST,
                v.submittedAt to SortOrder.ASC_NULLS_LAST,
            )
            .limit(filter.limit)
            .offset(filter.offset)
            .map { it.toQueueItem() }
        return Page(items, total, filter.limit, filter.offset)
    }

    private fun conditions(operatorId: Long, f: QueueFilter, zone: ZoneId): List<Op<Boolean>> = buildList {
        add(if (f.statuses.isNotEmpty()) v.status inList f.statuses else v.status notInList CLOSED)
        f.direction?.let { add(v.directionCode eq it) }
        f.minPriority?.let { add(v.priority greaterEq it.toShort()) }
        when (f.assigned) {
            AssignedFilter.ME -> add(v.assignedOperatorId eq operatorId)
            AssignedFilter.NONE -> add(v.assignedOperatorId.isNull())
            null -> Unit
        }
        f.assignedTo?.let { add(v.assignedOperatorId eq it) }
        f.eventType?.let { add(v.eventType eq it) }
        f.createdFrom?.let { add(v.createdAt greaterEq it.atStartOfDay(zone).toOffsetDateTime()) }
        f.createdTo?.let { add(v.createdAt less it.plusDays(1).atStartOfDay(zone).toOffsetDateTime()) }
        if (f.overdueOnly) add(v.isOverdue eq true)
        if (f.manualReviewOnly) add(v.requiresManualReview eq true)
        f.search?.let { q ->
            val pattern = containsPattern(q)
            add((v.claimNumber.lowerCase() like pattern) or (v.policyNumber.lowerCase() like pattern) or (v.clientName.lowerCase() like pattern))
        }
    }

    private fun ResultRow.toQueueItem() = QueueItem(
        id = this[v.id],
        claimNumber = this[v.claimNumber],
        status = this[v.status],
        directionCode = this[v.directionCode],
        directionName = this[v.directionName],
        priority = this[v.priority]?.toInt(),
        requiresManualReview = this[v.requiresManualReview],
        eventType = this[v.eventType],
        eventTypeName = this[v.eventTypeName],
        clientName = this[v.clientName],
        policyNumber = this[v.policyNumber],
        assignedOperatorId = this[v.assignedOperatorId],
        assignedOperatorName = this[v.assignedOperatorName],
        submittedAt = this[v.submittedAt]?.iso(),
        createdAt = this[v.createdAt].iso(),
        statusChangedAt = this[v.statusChangedAt].iso(),
        dueAt = this[v.dueAt]?.iso(),
        isOverdue = this[v.isOverdue],
        filesCount = this[v.filesCount],
        analysesPending = this[v.analysesPending],
        hasDuplicate = this[v.hasDuplicate],
        updatedAt = this[v.updatedAt].iso(),
    )
}
