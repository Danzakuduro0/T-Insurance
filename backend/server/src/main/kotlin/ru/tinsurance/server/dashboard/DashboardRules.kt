package ru.tinsurance.server.dashboard

import ru.tinsurance.api.OperatorAvailability
import ru.tinsurance.api.operator.*

object DashboardRules {
    fun queues(rows: List<QueueLoadDto>, config: DashboardConfig) = rows.map {
        it.copy(overloadWarning = it.queued >= config.queuedThreshold || it.overdue >= config.overdueThreshold)
    }

    fun recommendations(queues: List<QueueLoadDto>, operators: List<OperatorDto>): List<DashboardRecommendation> = buildList {
        for (queue in queues.filter { it.overloadWarning }) {
            if (queue.overdue > 0) add(DashboardRecommendation("REVIEW_OVERDUE", queue.directionCode,
                "Проверьте просроченные обращения направления ${queue.directionCode}: ${queue.overdue}"))
            val available = operators.any {
                it.availability == OperatorAvailability.ONLINE && queue.directionCode in it.directions && it.activeClaims < it.maxActiveClaims
            }
            add(if (available) DashboardRecommendation("ASSIGN_AVAILABLE", queue.directionCode,
                "В направлении ${queue.directionCode} есть операторы на смене со свободными местами — проверьте назначение")
            else DashboardRecommendation("ADD_CAPACITY", queue.directionCode,
                "В направлении ${queue.directionCode} нет свободных операторов на смене — подключите специалиста этого направления"))
        }
    }
}
