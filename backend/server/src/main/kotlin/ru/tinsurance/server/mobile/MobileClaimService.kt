package ru.tinsurance.server.mobile

import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.jdbc.Database
import ru.tinsurance.api.PolicyDto
import ru.tinsurance.api.mobile.*
import ru.tinsurance.server.claims.*
import ru.tinsurance.server.common.ConflictException
import ru.tinsurance.server.common.parseRequestId
import ru.tinsurance.server.common.tx
import ru.tinsurance.server.db.Claims
import ru.tinsurance.server.users.UserRepository
import java.time.OffsetDateTime

/** API приложения клиента: клиент видит и меняет только свои обращения. */
class MobileClaimService(private val db: Database, config: ClaimsConfig = ClaimsConfig.DEFAULT) {
    private val submission = ClaimSubmission(config)

    suspend fun activePolicies(clientId: Long): List<PolicyDto> = db.tx { PolicyRepository.active(clientId) }

    suspend fun eventTypes(): List<EventTypeDto> = db.tx { MobileClaimQueries.eventTypes() }

    suspend fun list(clientId: Long): List<ClaimListItem> = db.tx { MobileClaimQueries.list(clientId) }

    suspend fun details(clientId: Long, claimId: Long): ClaimDetails = db.tx { MobileClaimQueries.details(clientId, claimId) }

    suspend fun timeline(clientId: Long, claimId: Long): List<TimelineEvent> = db.tx {
        ClaimRepository.findOwned(clientId, claimId)
        MobileClaimQueries.timeline(claimId)
    }

    suspend fun completeness(clientId: Long, claimId: Long): CompletenessDto = db.tx {
        CompletenessChecker.check(ClaimRepository.findOwned(clientId, claimId)).toDto()
    }

    /** Возвращает обращение и признак «создано сейчас» (false — повтор того же clientRequestId). */
    suspend fun createDraft(clientId: Long, request: CreateClaimRequest): Pair<ClaimDetails, Boolean> {
        val requestId = parseRequestId(request.clientRequestId)
        val input = ClaimInput.from(request).also(ClaimInputValidator::validate).normalized()
        val (claimId, created) = db.tx {
            requestId?.let(ClaimRepository::findByRequestId)?.let { existing ->
                if (existing[Claims.clientId] != clientId) throw ConflictException("clientRequestId уже использован")
                return@tx existing[Claims.id] to false
            }
            ClaimInputValidator.checkReferences(clientId, input)
            val details = input.details ?: JsonObject(emptyMap())
            ClaimInputValidator.validateDetails(input.eventType, details)
            val draft = input.copy(details = details, contactPhone = input.contactPhone ?: UserRepository.phone(clientId))

            val now = OffsetDateTime.now()
            val id = ClaimRepository.insertDraft(clientId, draft, requestId, now)
            input.participants?.let { ParticipantRepository.replace(id, it) }
            ClaimStatusService.recordCreated(id, Actor.client(clientId), now)
            id to true
        }
        return details(clientId, claimId) to created
    }

    suspend fun update(clientId: Long, claimId: Long, request: UpdateClaimRequest): ClaimDetails {
        val input = ClaimInput.from(request).also(ClaimInputValidator::validate).normalized()
        db.tx { ClaimUpdater.update(clientId, claimId, input) }
        return details(clientId, claimId)
    }

    suspend fun submit(clientId: Long, claimId: Long): ClaimDetails {
        db.tx { submission.submit(clientId, claimId) }
        return details(clientId, claimId)
    }
}
