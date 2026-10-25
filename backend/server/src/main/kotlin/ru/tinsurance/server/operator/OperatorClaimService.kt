package ru.tinsurance.server.operator

import org.jetbrains.exposed.v1.jdbc.Database
import ru.tinsurance.api.Page
import ru.tinsurance.api.operator.ClaimCard
import ru.tinsurance.api.operator.QueueItem
import ru.tinsurance.server.common.tx
import java.time.ZoneId

class OperatorClaimService(private val db: Database, private val defaultZone: ZoneId) {

    suspend fun queue(operatorId: Long, filter: QueueFilter): Page<QueueItem> =
        db.tx { QueueRepository.page(operatorId, filter, defaultZone) }

    suspend fun card(claimId: Long): ClaimCard = db.tx { ClaimCardQueries.card(claimId) }
}
