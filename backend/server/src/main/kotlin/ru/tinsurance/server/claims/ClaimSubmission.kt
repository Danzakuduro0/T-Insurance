package ru.tinsurance.server.claims

import io.ktor.http.*
import ru.tinsurance.api.ClaimStatus
import ru.tinsurance.server.common.ApiException
import ru.tinsurance.server.common.ConflictException
import ru.tinsurance.server.db.Claims
import java.time.OffsetDateTime

/**
 * Отправка обращения клиентом. Неполный комплект выявляется сразу: SUBMITTED → NEEDS_INFO с перечнем
 * недостающего, полный — SUBMITTED → IN_ANALYSIS. Вызывается внутри транзакции.
 */
class ClaimSubmission(private val config: ClaimsConfig) {

    fun submit(clientId: Long, claimId: Long) {
        val claim = ClaimRepository.findOwned(clientId, claimId, forUpdate = true)
        val status = claim[Claims.status]
        if (status != ClaimStatus.DRAFT && status != ClaimStatus.NEEDS_INFO) throw ConflictException("Обращение уже отправлено")

        val result = CompletenessChecker.check(claim)
        if (result.missingCoreFields.isNotEmpty()) {
            throw ApiException(
                HttpStatusCode.BadRequest, "INCOMPLETE_DRAFT",
                "Перед отправкой заполните: ${result.missingCoreFields.joinToString { it.title }}",
            )
        }
        ClaimInputValidator.validateEventDate(claim)

        val now = OffsetDateTime.now()
        ClaimStatusService.transition(
            claimId, status, ClaimStatus.SUBMITTED, Actor.client(clientId),
            clientTitle = if (status == ClaimStatus.NEEDS_INFO) "Получены дополнительные материалы" else null,
            at = now,
        )
        ClaimIntake.recordCompleteness(claimId, result, now)
        if (result.complete) {
            ClaimIntake.startAnalysis(claimId, now)
        } else {
            ClaimIntake.requestMaterials(claimId, ClaimStatus.SUBMITTED, result, config.infoRequestDueDays, now)
        }
    }
}
