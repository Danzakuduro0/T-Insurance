package ru.tinsurance.server.dashboard

import org.jetbrains.exposed.v1.jdbc.Database
import ru.tinsurance.api.ActorType
import ru.tinsurance.api.operator.DashboardDto
import ru.tinsurance.server.assignment.OperatorRepository
import ru.tinsurance.server.claims.Actor
import ru.tinsurance.server.common.ForbiddenException
import ru.tinsurance.server.common.tx
import java.time.OffsetDateTime

class DashboardService(private val db: Database, private val operators: OperatorRepository, private val config: DashboardConfig) {
    suspend fun get(actor: Actor): DashboardDto {
        if (actor.type != ActorType.ADMIN) throw ForbiddenException("Dashboard доступен только администратору")
        return db.tx {
            exec("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY")
            val queues = DashboardRules.queues(DashboardQueries.queues(), config)
            val staff = operators.list()
            DashboardDto(OffsetDateTime.now().toString(), queues, staff, DashboardQueries.metrics(this),
                queues.any { it.overloadWarning }, DashboardRules.recommendations(queues, staff),
                config.queuedThreshold, config.overdueThreshold)
        }
    }
}
