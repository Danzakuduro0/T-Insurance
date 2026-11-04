package ru.tinsurance.server.dashboard

import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.selectAll
import ru.tinsurance.api.operator.ProcessingMetricsDto
import ru.tinsurance.api.operator.QueueLoadDto
import ru.tinsurance.server.db.QueueLoadView as Q

object DashboardQueries {
    fun queues(): List<QueueLoadDto> = Q.selectAll().orderBy(Q.directionCode).map {
        QueueLoadDto(it[Q.directionCode], it[Q.queued], it[Q.queuedUrgent], it[Q.overdue], it[Q.oldestQueuedAt]?.toString())
    }

    fun metrics(tx: JdbcTransaction): ProcessingMetricsDto = tx.exec("""
        with history as (
            select h.claim_id,
                   min(h.created_at) filter (where h.to_status = 'ASSIGNED' and h.created_at >= c.submitted_at) as first_assigned,
                   bool_or(h.to_status = 'NEEDS_INFO') as needed_info
            from claim_status_history h join claims c on c.id = h.claim_id
            where c.submitted_at is not null
            group by h.claim_id
        )
        select count(*) as submitted,
               count(h.first_assigned) as assigned_samples,
               avg(extract(epoch from h.first_assigned - c.submitted_at))::double precision as assignment_seconds,
               count(*) filter (where c.status = 'COMPLETED' and c.closed_at >= c.submitted_at) as completed_samples,
               avg(extract(epoch from c.closed_at - c.submitted_at)) filter
                   (where c.status = 'COMPLETED' and c.closed_at >= c.submitted_at)::double precision as completion_seconds,
               count(*) filter (where h.needed_info) as needs_info
        from claims c left join history h on h.claim_id = c.id
        where c.submitted_at is not null
    """.trimIndent(), explicitStatementType = StatementType.SELECT) { rs ->
        check(rs.next())
        val submitted = rs.getLong("submitted")
        val needsInfo = rs.getLong("needs_info")
        ProcessingMetricsDto(submitted, rs.getLong("assigned_samples"),
            rs.getObject("assignment_seconds")?.let { (it as Number).toDouble() },
            rs.getLong("completed_samples"), rs.getObject("completion_seconds")?.let { (it as Number).toDouble() },
            needsInfo, if (submitted == 0L) null else needsInfo.toDouble() / submitted)
    }!!
}
